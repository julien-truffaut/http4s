package org.http4s.server.blaze

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets._

import org.http4s.Header._
import org.http4s._
import org.http4s.blaze.http.websocket.{WSFrameAggregator, WebSocketDecoder}
import org.http4s.websocket.WebsocketHandshake
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.websocket.Http4sWSStage
import org.http4s.util.CaseInsensitiveString._
import scodec.bits.ByteVector

import scala.util.{Failure, Success}
import scala.concurrent.Future

import scalaz.stream.Process

trait WebSocketSupport extends Http1ServerStage {
  override protected def renderResponse(req: Request, resp: Response, cleanup: () => Future[ByteBuffer]): Unit = {
    val ws = resp.attributes.get(org.http4s.server.websocket.websocketKey)
    logger.debug(s"Websocket key: $ws\nRequest headers: " + req.headers)

    if (ws.isDefined) {
      val hdrs =  req.headers.map(h=>(h.name.toString,h.value))
      if (WebsocketHandshake.isWebSocketRequest(hdrs)) {
        WebsocketHandshake.serverHandshake(hdrs) match {
          case Left((code, msg)) =>
            logger.info(s"Invalid handshake $code, $msg")
            val body = Process.emit(ByteVector(msg.toString.getBytes(req.charset.nioCharset)))
            val headers = Headers(`Content-Length`(msg.length),
                                   Connection("close".ci),
                                   Header.Raw(Header.`Sec-WebSocket-Version`.name, "13"))

            val rsp = Response(status = Status.BadRequest, body = body, headers = headers)
            super.renderResponse(req, rsp, cleanup)

          case Right(hdrs) =>  // Successful handshake
            val sb = new StringBuilder
            sb.append("HTTP/1.1 101 Switching Protocols\r\n")
            hdrs.foreach { case (k, v) => sb.append(k).append(": ").append(v).append('\r').append('\n') }
            sb.append('\r').append('\n')

            // write the accept headers and reform the pipeline
            channelWrite(ByteBuffer.wrap(sb.result().getBytes(US_ASCII))).onComplete {
              case Success(_) =>
                logger.debug("Switching pipeline segments for websocket")

                val segment = LeafBuilder(new Http4sWSStage(ws.get))
                              .prepend(new WSFrameAggregator)
                              .prepend(new WebSocketDecoder(false))

                this.replaceInline(segment)

              case Failure(t) => fatalError(t, "Error writing Websocket upgrade response")
            }(ec)
        }

      } else super.renderResponse(req, resp, cleanup)
    } else super.renderResponse(req, resp, cleanup)
  }
}
