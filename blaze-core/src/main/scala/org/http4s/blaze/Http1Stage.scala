package org.http4s
package blaze

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.Header.`Transfer-Encoding`
import org.http4s.blaze.util.BufferTools.{concatBuffers, emptyBuffer}
import org.http4s.blaze.http.http_parser.BaseExceptions.ParserException
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.{Command, TailStage}
import org.http4s.blaze.util.{ChunkProcessWriter, CachingStaticWriter, CachingChunkWriter, ProcessWriter}
import org.http4s.util.{Writer, StringWriter}
import scodec.bits.ByteVector

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Failure, Success}
import scalaz.stream.Process._
import scalaz.stream.Cause.{Terminated, End}
import scalaz.{-\/, \/-}
import scalaz.concurrent.Task

trait Http1Stage { self: TailStage[ByteBuffer] =>

  /** ExecutionContext to be used for all Future continuations
    * '''WARNING:''' The ExecutionContext should trampoline or risk possibly unhandled stack overflows */
  protected implicit def ec: ExecutionContext

  protected def doParseContent(buffer: ByteBuffer): Option[ByteBuffer]

  protected def contentComplete(): Boolean

  /** Encodes the headers into the Writer, except the Transfer-Encoding header which may be returned
    * Note: this method is very niche but useful for both server and client. */
  protected def encodeHeaders(headers: Headers, rr: Writer): Option[`Transfer-Encoding`] = {
    var encoding: Option[`Transfer-Encoding`] = None
    headers.foreach( header =>
      if (header.name != `Transfer-Encoding`.name) rr << header << '\r' << '\n'
      else encoding = `Transfer-Encoding`.matchHeader(header)
    )
    encoding
  }

  /** Check Connection header and add applicable headers to response */
  protected def checkCloseConnection(conn: Header.Connection, rr: StringWriter): Boolean = {
    if (conn.hasKeepAlive) {                          // connection, look to the request
      logger.trace("Found Keep-Alive header")
      false
    }
    else if (conn.hasClose) {
      logger.trace("Found Connection:Close header")
      rr << "Connection:close\r\n"
      true
    }
    else {
      logger.info(s"Unknown connection header: '${conn.value}'. Closing connection upon completion.")
      rr << "Connection:close\r\n"
      true
    }
  }

  /** Get the proper body encoder based on the message headers */
  final protected def getEncoder(msg: Message,
                                 rr: StringWriter,
                                 minor: Int,
                                 closeOnFinish: Boolean): ProcessWriter = {
    val headers = msg.headers
    getEncoder(Header.Connection.from(headers),
               Header.`Transfer-Encoding`.from(headers),
               Header.`Content-Length`.from(headers),
               msg.trailerHeaders,
               rr,
               minor,
               closeOnFinish)
  }

  /** Get the proper body encoder based on the message headers,
    * adding the appropriate Connection and Transfer-Encoding headers along the way */
  protected def getEncoder(connectionHeader: Option[Header.Connection],
                 bodyEncoding: Option[Header.`Transfer-Encoding`],
                 lengthHeader: Option[Header.`Content-Length`],
                 trailer: Task[Headers],
                 rr: StringWriter,
                 minor: Int,
                 closeOnFinish: Boolean): ProcessWriter = lengthHeader match {
    case Some(h) if bodyEncoding.isEmpty =>
      logger.trace("Using static encoder")

      // add KeepAlive to Http 1.0 responses if the header isn't already present
      if (!closeOnFinish && minor == 0 && connectionHeader.isEmpty) rr << "Connection:keep-alive\r\n\r\n"
      else rr << '\r' << '\n'

      val b = ByteBuffer.wrap(rr.result().getBytes(StandardCharsets.US_ASCII))
      new StaticWriter(b, h.length, this)

    case _ =>  // No Length designated for body or Transfer-Encoding included
      if (minor == 0) { // we are replying to a HTTP 1.0 request see if the length is reasonable
        if (closeOnFinish) {  // HTTP 1.0 uses a static encoder
          logger.trace("Using static encoder")
          rr << '\r' << '\n'
          val b = ByteBuffer.wrap(rr.result().getBytes(StandardCharsets.US_ASCII))
          new StaticWriter(b, -1, this)
        }
        else {  // HTTP 1.0, but request was Keep-Alive.
          logger.trace("Using static encoder without length")
          new CachingStaticWriter(rr, this) // will cache for a bit, then signal close if the body is long
        }
      }
      else {
        bodyEncoding match { // HTTP >= 1.1 request without length. Will use a chunked encoder
          case Some(h) => // Signaling chunked means flush every chunk
            if (!h.hasChunked) logger.warn(s"Unknown transfer encoding: '${h.value}'. Defaulting to Chunked Encoding")
            new ChunkProcessWriter(rr, this, trailer)

          case None =>     // use a cached chunk encoder for HTTP/1.1 without length of transfer encoding
            logger.trace("Using Caching Chunk Encoder")
            new CachingChunkWriter(rr, this, trailer)
        }
      }
  }

  final protected def collectBodyFromParser(buffer: ByteBuffer): (EntityBody, () => Future[ByteBuffer]) = {
    if (contentComplete()) {
      if (buffer.remaining() == 0) Http1Stage.CachedEmptyBody
      else (EmptyBody, () => Future.successful(buffer))
    }
    else {
      @volatile var currentBuffer = buffer

      // TODO: we need to work trailers into here somehow
      val t = Task.async[ByteVector]{ cb =>
        if (!contentComplete()) {

          def go(): Unit = try {
            doParseContent(currentBuffer) match {
              case Some(result) => cb(\/-(ByteVector(result)))
              case None if contentComplete() => cb(-\/(Terminated(End)))
              case None =>
                channelRead().onComplete {
                  case Success(b)   => currentBuffer = b; go() // Need more data...
                  case Failure(EOF) => cb(-\/(InvalidBodyException("Received premature EOF.")))
                  case Failure(t)   => cb(-\/(t))
                }
            }
          } catch {
            case t: ParserException =>
              fatalError(t, "Error parsing request body")
              cb(-\/(InvalidBodyException(t.msg())))

            case t: Throwable =>
              fatalError(t, "Error collecting body")
              cb(-\/(t))
          }
          go()
        }
        else cb(-\/(Terminated(End)))
      }

      (repeatEval(t), () => drainBody(currentBuffer))
    }
  }

  /** Called when a fatal error has occurred
    * The method logs an error and shuts down the stage, sending the error outbound
    * @param t
    * @param msg
    */
  protected def fatalError(t: Throwable, msg: String = "") {
    logger.error(t)(s"Fatal Error: $msg")
    stageShutdown()
    sendOutboundCommand(Command.Error(t))
  }

  /** Cleans out any remaining body from the parser */
  final protected def drainBody(buffer: ByteBuffer): Future[ByteBuffer] = {
    if (!contentComplete()) {
      while(!contentComplete() && doParseContent(buffer).nonEmpty) { /* we just discard the results */ }

      if (!contentComplete()) channelRead().flatMap(newBuffer => drainBody(concatBuffers(buffer, newBuffer)))
      else Future.successful(buffer)
    }
    else Future.successful(buffer)
  }
}

private object Http1Stage {
  val CachedEmptyBody = {
    val f = Future.successful(emptyBuffer)
    (EmptyBody, () => f)
  }
}
