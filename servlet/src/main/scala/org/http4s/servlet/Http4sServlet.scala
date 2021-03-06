package org.http4s
package servlet

import scodec.bits.ByteVector
import java.util.concurrent.ExecutorService
import server._

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}
import java.net.InetAddress

import scala.collection.JavaConverters._
import javax.servlet._

import scala.concurrent.duration.Duration
import scalaz.concurrent.Actor
import scalaz.stream.Cause.{End, Terminated}
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.io._
import scalaz.{\/, -\/, \/-}
import scala.util.control.NonFatal
import org.parboiled2.ParseError
import org.log4s.getLogger

class Http4sServlet(service: HttpService,
                    asyncTimeout: Duration = Duration.Inf,
                    chunkSize: Int = 4096,
                    threadPool: ExecutorService = Strategy.DefaultExecutorService)
  extends HttpServlet
{
  import Http4sServlet._

  private[this] val logger = getLogger

  private val asyncTimeoutMillis = if (asyncTimeout.isFinite()) asyncTimeout.toMillis else -1  // -1 == Inf

  private[this] var serverSoftware: ServerSoftware = _
  private[this] var inputStreamReader: BodyReader = _

  private class Http4sAsyncListener extends AsyncListener {
    override def onComplete(event: AsyncEvent): Unit = {}

    override def onError(event: AsyncEvent): Unit = logger.error(event.getThrowable)("Async error processing request")

    override def onStartAsync(event: AsyncEvent): Unit = {}

    override def onTimeout(event: AsyncEvent): Unit = {
      val ctx = event.getAsyncContext
      val servletResponse = ctx.getResponse.asInstanceOf[HttpServletResponse]
      val response = ResponseBuilder(Status.InternalServerError, "Service timed out.")
      if (!servletResponse.isCommitted)
        Http4sServlet.this.renderResponse(response, servletResponse)
      else {
        val servletRequest = ctx.getRequest.asInstanceOf[HttpServletRequest]
        logger.warn(s"Async context timed out after servlet response was already committed on ${servletRequest.getMethod} ${servletRequest.getPathInfo}")
      }
      ctx.complete()
    }
  }

  override def init(config: ServletConfig) {
    val servletContext = config.getServletContext
    serverSoftware = ServerSoftware(servletContext.getServerInfo)
    val servletApiVersion = ServletApiVersion(servletContext)
    logger.info(s"Detected Servlet API version $servletApiVersion")

    inputStreamReader = if (servletApiVersion >= ServletApiVersion(3, 1))
      asyncInputStreamReader(chunkSize)
    else
      syncInputStreamReader(chunkSize)
  }

  override def service(servletRequest: HttpServletRequest, servletResponse: HttpServletResponse) {
    try {
      val ctx = servletRequest.startAsync()
      toRequest(servletRequest) match {
        case -\/(e) =>
          // TODO make more http4sy
          servletResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, e.sanitized)
        case \/-(req) =>
          ctx.addListener(new Http4sAsyncListener)
          ctx.setTimeout(asyncTimeoutMillis)
          handle(req, ctx)
      }
    } catch {
      case NonFatal(e) => handleError(e, servletResponse)
    }
  }

  private def handleError(t: Throwable, response: HttpServletResponse) {
    if (!response.isCommitted) t match {
      case ParseError(_, _) =>
        logger.info(t)("Error during processing phase of request")
        response.sendError(HttpServletResponse.SC_BAD_REQUEST)

      case _ =>
        logger.error(t)("Error processing request")
        response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
    }
    else logger.error(t)("Error processing request")

  }

  private def handle(request: Request, ctx: AsyncContext): Unit = {
    val servletResponse = ctx.getResponse.asInstanceOf[HttpServletResponse]
    Task.fork {
      val response = service.or(request, ResponseBuilder.notFound(request))
      renderResponse(response, servletResponse)
    }(threadPool).runAsync {
      case \/-(_) =>
        ctx.complete()
      case -\/(t) =>
        handleError(t, servletResponse)
        ctx.complete()
    }
  }

  private def renderResponse(response: Task[Response], servletResponse: HttpServletResponse): Task[Unit] =
    response.map { r =>
      servletResponse.setStatus(r.status.code, r.status.reason)
      for (header <- r.headers)
        servletResponse.addHeader(header.name.toString, header.value)
      val out = servletResponse.getOutputStream
      val isChunked = r.isChunked
      r.body.map { chunk =>
        out.write(chunk.toArray)
        if (isChunked) servletResponse.flushBuffer()
      }
    }

  protected def toRequest(req: HttpServletRequest): ParseResult[Request] =
    for {
      method <- Method.fromString(req.getMethod)
      uri <- Uri.fromString(req.getRequestURI)
      version <- HttpVersion.fromString(req.getProtocol)
    } yield Request(
      method = method,
      uri = uri,
      httpVersion = version,
      headers = toHeaders(req),
      body = inputStreamReader(req),
      attributes = AttributeMap(
        Request.Keys.PathInfoCaret(req.getServletPath.length),
        Request.Keys.Remote(InetAddress.getByName(req.getRemoteAddr)),
        Request.Keys.ServerSoftware(serverSoftware)
      )
    )

  protected def toHeaders(req: HttpServletRequest): Headers = {
    val headers = for {
      name <- req.getHeaderNames.asScala
      value <- req.getHeaders(name).asScala
    } yield Header(name, value)
    Headers(headers.toSeq : _*)
  }
}

object Http4sServlet {
  import scalaz.stream.Process
  import scalaz.concurrent.Task

  private[this] val logger = getLogger

  private[servlet] val DefaultChunkSize = 4096

  private type BodyReader = HttpServletRequest => EntityBody

  private def asyncInputStreamReader(chunkSize: Int)(req: HttpServletRequest): EntityBody = {
    type Callback = Throwable \/ ByteVector => Unit
    case object DataAvailable
    case object AllDataRead

    val in = req.getInputStream

    var buff: Array[Byte] = null

    var callbacks: List[Callback] = Nil

    def doRead(cb: Callback): Unit = {
      if (buff == null) buff = new Array[Byte](chunkSize)
      val len = in.read(buff)

      val buffer = if (len == chunkSize) {
        val b = ByteVector.view(buff)
        buff = null
        b
      } else if (len > 0) {
        // Need to truncate the array
        val b2 = new Array[Byte](len)
        System.arraycopy(buff, 0, b2, 0, len)
        ByteVector.view(b2)
      } else ByteVector.empty

      cb(\/-(buffer))
    }

    if (in.isFinished) Process.halt
    else {
      val actor = Actor.actor[Any] {
        case cb: Callback =>
          if (in.isFinished) {
            logger.debug("Finished.")
            cb(-\/(Terminated(End)))
          }
          else if (in.isReady) {
            doRead(cb)
          }
          else {
            // Consuming this stream on multiple threads can lead to multiple
            // callbacks accruing.  We shouldn't ever see this list grow beyond
            // one, but in the spirit of defensive programming, we'll try to
            // fulfill them all.
            callbacks = cb :: callbacks
          }

        case DataAvailable =>
          callbacks match {
            case head :: losers =>
              doRead(head)
              losers.foreach(_(\/-(ByteVector.empty)))
              callbacks = Nil
            case _ =>
          }

        case AllDataRead =>
          logger.debug("ReadListener signaled completion")
          if (callbacks.nonEmpty) {
            callbacks.foreach(_(-\/(Terminated(End))))
            callbacks = Nil
          }

        case t: Throwable =>
          logger.error(t)("Error during Servlet Async Read")
          if (callbacks.nonEmpty) {
            callbacks.foreach(_(-\/(t)))
            callbacks = Nil
          }
      }

      // Initialized the listener
      in.setReadListener(new ReadListener {
        override def onError(t: Throwable): Unit = actor ! t
        override def onDataAvailable(): Unit = actor ! DataAvailable
        override def onAllDataRead(): Unit = actor ! AllDataRead
      })

      Process.repeatEval(Task.async[ByteVector] { actor ! _ })
    }
  }

  private def syncInputStreamReader(chunkSize: Int)(req: HttpServletRequest): EntityBody =
    chunkR(req.getInputStream).map(_(chunkSize)).eval
}
