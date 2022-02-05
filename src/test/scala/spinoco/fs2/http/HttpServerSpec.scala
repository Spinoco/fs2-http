package spinoco.fs2.http

import cats.effect.IO
import com.comcast.ip4s._
import fs2._
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.scalacheck.Properties
import org.scalacheck.Prop._
import spinoco.fs2.http
import spinoco.fs2.http.body.BodyEncoder
import spinoco.protocol.http.header.{`Content-Length`, `Content-Type`}
import spinoco.protocol.mime.{ContentType, MediaType}
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}

import scala.concurrent.duration._

object HttpServerSpec extends Properties("HttpServer"){
  import cats.effect.unsafe.implicits.global
  implicit val network: Network[IO] = Network.forAsync[IO]


  val MaxConcurrency: Int = 10

  def echoService(request: HttpRequestHeader, body: Stream[IO,Byte]): Stream[IO,HttpResponse[IO]] = {
    if (request.path != Uri.Path / "echo") Stream.emit(HttpResponse[IO](HttpStatusCode.Ok).withUtf8Body("Hello World")).covary[IO]
    else {
      val ct =  request.headers.collectFirst { case `Content-Type`(ct0) => ct0 }.getOrElse(ContentType.BinaryContent(MediaType.`application/octet-stream`, None))
      val size = request.headers.collectFirst { case `Content-Length`(sz) => sz }.getOrElse(0L)
      val ok = HttpResponse(HttpStatusCode.Ok).chunkedEncoding.withContentType(ct).withBodySize(size)

      Stream.emit(ok.copy(body = body.take(size)))
    }
  }

  def failRouteService(request: HttpRequestHeader, body: Stream[IO,Byte]): Stream[IO,HttpResponse[IO]] = {
    Stream.raiseError[IO](new Throwable("Booom!")).covaryOutput[HttpResponse[IO]]
  }

  def failingResponse(request: HttpRequestHeader, body: Stream[IO,Byte]): Stream[IO,HttpResponse[IO]] = Stream {
    HttpResponse(HttpStatusCode.Ok).copy(body = Stream.raiseError[IO](new Throwable("Kaboom!")).covaryOutput[Byte])
  }.covary[IO]


  property("simultaneous-requests") = secure {
    // run up to count parallel requests and then make sure all of them pass within timeout
    val count = 100

    def clients(tls: TLSContext[IO]) : Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request = HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))
      Stream.eval(client[IO]()(tls)).flatMap { httpClient =>
      Stream.range(0,count).chunkLimit(1).unchunks.map { idx =>
        httpClient.request(request).map(resp => idx -> (resp.header.status == HttpStatusCode.Ok))
      }}
    }


    Stream.eval(TLSContext.Builder.forAsync[IO].system).flatMap { tls =>
      (Stream(
        http.server[IO](SocketAddress(host"127.0.0.1", port"9090"))(echoService).drain
      ).covary[IO] ++ Stream.sleep_[IO](1.second) ++ clients(tls))
      .parJoin(MaxConcurrency)
      .take(count)
      .filter { case (_, success) => success }
    }.compile.toVector.unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)


  }

  property("simultaneous-requests-echo body") = secure {
    // run up to count parallel requests with body,  and then make sure all of them pass within timeout with body echoed back
    val count = 100

    def clients(tls: TLSContext[IO]): Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))
       .withBody("Hello")(BodyEncoder.utf8String, RaiseThrowable.fromApplicativeError[IO])

      Stream.eval(client[IO]()(tls)).flatMap { httpClient =>
        Stream.range(0,count).chunkLimit(1).unchunks.map { idx =>
          httpClient.request(request).flatMap { resp =>
            Stream.eval(resp.bodyAsString).map { attempt =>
              val okResult = resp.header.status == HttpStatusCode.Ok
              attempt.map(_ == "Hello").map(r => idx -> (r && okResult)).getOrElse(idx -> false)
            }
          }
        }}
    }

    Stream.eval(TLSContext.Builder.forAsync[IO].system).flatMap { tls =>
      ( Stream.sleep_[IO](3.second) ++
      (Stream(
        http.server[IO](SocketAddress(host"127.0.0.1", port"9090"))(echoService).drain
      ).covary[IO] ++ Stream.sleep_[IO](3.second) ++ clients(tls)).parJoin(MaxConcurrency))
      .take(count)
      .filter { case (_, success) => success }
    }.compile.toVector.unsafeRunTimed(60.seconds).map { _.size } ?= Some(count)

  }


  property("request-failed-to-route") = secure {
    // run up to count parallel requests with body, server shall fail each, nevertheless response shall be delivered.
    val count = 1

    def clients(tls: TLSContext[IO]): Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))

      Stream.eval(client[IO]()(tls)).flatMap { httpClient =>
      Stream.range(0,count).chunkLimit(1).unchunks.map { idx =>
      httpClient.request(request).map { resp =>
        idx -> (resp.header.status == HttpStatusCode.BadRequest)
      }}}
    }

    Stream.eval(TLSContext.Builder.forAsync[IO].system).flatMap { tls =>
      (Stream.sleep_[IO](3.second) ++
        (Stream(
          HttpServer.mk[IO](
            bindTo = SocketAddress(host"127.0.0.1", port"9090")
            , service = failRouteService
            , requestFailure = _ => { Stream(HttpResponse[IO](HttpStatusCode.BadRequest)).covary[IO] }
            , sendFailure = HttpServer.handleSendFailure[IO] _
          ).drain
        ).covary[IO] ++ Stream.sleep_[IO](1.second) ++ clients(tls)).parJoin(MaxConcurrency))
        .take(count)
        .filter { case (_, success) => success }
    }.compile.toVector.unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)
  }



  property("request-failed-body-send") = secure {
    // run up to count parallel requests with body, server shall fail each (when sending body), nevertheless response shall be delivered.
    val count = 100

    def clients(tls: TLSContext[IO]): Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))

      Stream.eval(client[IO]()(tls)).flatMap { httpClient =>
        Stream.range(0,count).chunkLimit(1).unchunks.map { idx =>
          httpClient.request(request).map { resp =>
            idx -> (resp.header.status == HttpStatusCode.Ok) // body won't be consumed, and request was succesfully sent
          }
        }
      }
    }

    Stream.eval(TLSContext.Builder.forAsync[IO].system).flatMap { tls =>
      (Stream.sleep_[IO](3.second) ++
        (Stream(
          HttpServer.mk[IO](
            bindTo = SocketAddress(host"127.0.0.1", port"9090")
            , service = failingResponse
            , requestFailure = HttpServer.handleRequestParseError[IO] _
            , sendFailure = (_, _, _) => Stream.empty
          ).drain
        ).covary[IO] ++ Stream.sleep_[IO](1.second) ++ clients(tls)).parJoin(MaxConcurrency))
        .take(count)
        .filter { case (_, success) => success }
    }.compile.toVector.unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)
  }



}
