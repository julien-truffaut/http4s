package org.http4s.server
package blaze

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.{Header, Response}
import org.http4s.Status._
import org.http4s.blaze._
import org.http4s.blaze.pipeline.{Command => Cmd}
import org.http4s.util.CaseInsensitiveString._
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

import scalaz.concurrent.Task
import scalaz.stream.Process

import scodec.bits.ByteVector

class Http1ServerStageSpec extends Specification with NoTimeConversions {
  def makeString(b: ByteBuffer): String = {
    val p = b.position()
    val a = new Array[Byte](b.remaining())
    b.get(a).position(p)
    new String(a)
  }

  def runRequest(req: Seq[String], service: HttpService): Future[ByteBuffer] = {
    val head = new SeqTestHead(req.map(s => ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII))))
    val httpStage = new Http1ServerStage(service, None) {
      override def reset(): Unit = head.stageShutdown()     // shutdown the stage after a complete request
    }
    pipeline.LeafBuilder(httpStage).base(head)
    head.sendInboundCommand(Cmd.Connected)
    head.result
  }

  "Http1ServerStage: Common responses" should {
    ServerTestRoutes.testRequestResults.zipWithIndex.foreach { case ((req, (status,headers,resp)), i) =>
      s"Run request $i Run request: --------\n${req.split("\r\n\r\n")(0)}\n" in {
        val result = runRequest(Seq(req), ServerTestRoutes())
        result.map(ResponseParser.apply(_)) must be_== ((status, headers, resp)).await(0, FiniteDuration(5, "seconds"))
      }
    }
  }

  "Http1ServerStage: Errors" should {
    val exceptionService = HttpService {
      case r if r.uri.path == "/sync" => sys.error("Synchronous error!")
      case r if r.uri.path == "/async" => Task.fail(new Exception("Asynchronous error!"))
    }

    def runError(path: String) = runRequest(List(path), exceptionService)
        .map(ResponseParser.apply(_))
        .map{ case (s, h, r) =>
        val close = h.find{ h => h.toRaw.name == "connection".ci && h.toRaw.value == "close"}.isDefined
        (s, close, r)
      }

    "Deal with synchronous errors" in {
      val path = "GET /sync HTTP/1.1\r\nConnection:keep-alive\r\n\r\n"
      val result = runError(path)

      result.map{ case (s, c, _) => (s, c)} must be_== ((InternalServerError, true)).await
    }

    "Deal with asynchronous errors" in {
      val path = "GET /async HTTP/1.1\r\nConnection:keep-alive\r\n\r\n"
      val result = runError(path)

      result.map{ case (s, c, _) => (s, c)} must be_== ((InternalServerError, true)).await
    }
  }

  "Http1ServerStage: routes" should {

    def httpStage(service: HttpService, requests: Int, input: Seq[String]): Future[ByteBuffer] = {
      val head = new SeqTestHead(input.map(s => ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII))))
      val httpStage = new Http1ServerStage(service, None) {
        @volatile var count = 0

        override def reset(): Unit = {
          // shutdown the stage after it completes two requests
          count += 1
          if (count < requests) super.reset()
          else head.stageShutdown()
        }
      }

      pipeline.LeafBuilder(httpStage).base(head)
      head.sendInboundCommand(Cmd.Connected)
      head.result
    }

    "Handle routes that consumes the full request body for non-chunked" in {
      val service = HttpService {
        case req => Task.now(Response(body = req.body))
      }

      // The first request will get split into two chunks, leaving the last byte off
      val req1 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 4\r\n\r\ndone"
      val (r11,r12) = req1.splitAt(req1.length - 1)

      val buff = Await.result(httpStage(service, 1, Seq(r11,r12)), 5.seconds)

      // Both responses must succeed
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(4)), "done"))
    }

    "Handle routes that ignores the body for non-chunked" in {
      val service = HttpService {
        case req => Task.now(Response(body = req.body))
      }

      // The first request will get split into two chunks, leaving the last byte off
      val req1 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 4\r\n\r\ndone"
      val (r11,r12) = req1.splitAt(req1.length - 1)

      val buff = Await.result(httpStage(service, 1, Seq(r11,r12)), 5.seconds)

      // Both responses must succeed
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(4)), "done"))
    }

    "Handle routes that ignores request body for non-chunked" in {

      val service = HttpService {
        case req =>  Task.now(Response(body = Process.emit(ByteVector.view("foo".getBytes))))
      }

      // The first request will get split into two chunks, leaving the last byte off
      val req1 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 4\r\n\r\ndone"
      val (r11,r12) = req1.splitAt(req1.length - 1)
      val req2 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 5\r\n\r\ntotal"

      val buff = Await.result(httpStage(service, 2, Seq(r11,r12,req2)), 5.seconds)

      // Both responses must succeed
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(3)), "foo"))
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(3)), "foo"))
    }

    "Handle routes that runs the request body for non-chunked" in {

      val service = HttpService {
        case req =>  req.body.run.map { _ =>
          Response(body = Process.emit(ByteVector.view("foo".getBytes)))
        }
      }

      // The first request will get split into two chunks, leaving the last byte off
      val req1 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 4\r\n\r\ndone"
      val (r11,r12) = req1.splitAt(req1.length - 1)
      val req2 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 5\r\n\r\ntotal"

      val buff = Await.result(httpStage(service, 2, Seq(r11,r12,req2)), 5.seconds)

      // Both responses must succeed
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(3)), "foo"))
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(3)), "foo"))
    }

    "Handle routes that kills the request body for non-chunked" in {

      val service = HttpService {
        case req =>  req.body.kill.run.map { _ =>
          Response(body = Process.emit(ByteVector.view("foo".getBytes)))
        }
      }

      // The first request will get split into two chunks, leaving the last byte off
      val req1 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 4\r\n\r\ndone"
      val (r11,r12) = req1.splitAt(req1.length - 1)
      val req2 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 5\r\n\r\ntotal"

      val buff = Await.result(httpStage(service, 2, Seq(r11,r12,req2)), 5.seconds)

      // Both responses must succeed
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(3)), "foo"))
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(3)), "foo"))
    }

    // Think of this as drunk HTTP pipelineing
    "Not die when two requests come in back to back" in {

      val service = HttpService {
        case req => req.body.toTask.map { bytes =>
          Response(body = Process.emit(bytes))
        }
      }

      // The first request will get split into two chunks, leaving the last byte off
      val req1 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 4\r\n\r\ndone"
      val req2 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 5\r\n\r\ntotal"

      val buff = Await.result(httpStage(service, 2, Seq(req1 + req2)), 5.seconds)

      // Both responses must succeed
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(4)), "done"))
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(5)), "total"))
    }

    "Handle using the request body as the response body" in {

      val service = HttpService {
        case req => Task.now(Response(body = req.body))
      }

      // The first request will get split into two chunks, leaving the last byte off
      val req1 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 4\r\n\r\ndone"
      val req2 = "POST /sync HTTP/1.1\r\nConnection:keep-alive\r\nContent-Length: 5\r\n\r\ntotal"

      val buff = Await.result(httpStage(service, 2, Seq(req1, req2)), 5.seconds)

      // Both responses must succeed
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(4)), "done"))
      ResponseParser.parseBuffer(buff) must_== ((Ok, Set(Header.`Content-Length`(5)), "total"))
    }
  }
}
