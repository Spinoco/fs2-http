package spinoco.fs2.http

import java.net.InetSocketAddress

import cats.effect.IO
import fs2._
import fs2.io.tcp.SocketGroup
import fs2.io.tls.TLSContext
import org.scalacheck.Properties
import org.scalacheck.Prop._
import spinoco.fs2.http
import spinoco.fs2.http.body.BodyEncoder
import spinoco.protocol.http.header.{`Content-Length`, `Content-Type`}
import spinoco.protocol.mime.{ContentType, MediaType}
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}

import scala.concurrent.duration._

object HttpServerSpec extends Properties("HttpServer"){
  import Resources._

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
    Stream.raiseError(new Throwable("Booom!"))
  }

  def failingResponse(request: HttpRequestHeader, body: Stream[IO,Byte]): Stream[IO,HttpResponse[IO]] = Stream {
    HttpResponse(HttpStatusCode.Ok).copy(body = Stream.raiseError(new Throwable("Kaboom!")).covary[IO])
  }.covary[IO]


  property("simultaneous-requests") = secure {
    // run up to count parallel requests and then make sure all of them pass within timeout
    val count = 100

    def clients(socketGroup: SocketGroup, tls: TLSContext) : Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request = HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))
      Stream.eval(client[IO]()(socketGroup, tls)).flatMap { httpClient =>
      Stream.range(0,count).unchunk.map { idx =>
        httpClient.request(request).map(resp => idx -> (resp.header.status == HttpStatusCode.Ok))
      }}
    }


    Stream.resource(httpResources).flatMap { case (group, tls) =>
      (Stream(
        http.server[IO](new InetSocketAddress("127.0.0.1", 9090))(echoService)(group).drain
      ).covary[IO] ++ Stream.sleep_[IO](1.second) ++ clients(group, tls))
      .parJoin[IO, (Int, Boolean)](MaxConcurrency)
      .take(count)
      .filter { case (_, success) => success }
    }.compile.toVector.unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)


  }

  property("simultaneous-requests-echo body") = secure {
    // run up to count parallel requests with body,  and then make sure all of them pass within timeout with body echoed back
    val count = 100

    def clients(socketGroup: SocketGroup, tls: TLSContext): Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))
       .withBody("Hello")(BodyEncoder.utf8String, implicitly[RaiseThrowable[IO]])

      Stream.eval(client[IO]()(socketGroup, tls)).flatMap { httpClient =>
        Stream.range(0,count).unchunk.map { idx =>
          httpClient.request(request).flatMap { resp =>
            Stream.eval(resp.bodyAsString).map { attempt =>
              val okResult = resp.header.status == HttpStatusCode.Ok
              attempt.map(_ == "Hello").map(r => idx -> (r && okResult)).getOrElse(idx -> false)
            }
          }
        }}
    }

    Stream.resource(httpResources).flatMap { case (group, tls) =>
      ( Stream.sleep_[IO](3.second) ++
      (Stream(
        http.server[IO](new InetSocketAddress("127.0.0.1", 9090))(echoService)(group).drain
      ).covary[IO] ++ Stream.sleep_[IO](3.second) ++ clients(group, tls)).parJoin[IO, (Int, Boolean)](MaxConcurrency))
      .take(count)
      .filter { case (_, success) => success }
    }.compile.toVector.unsafeRunTimed(60.seconds).map { _.size } ?= Some(count)

  }


  property("request-failed-to-route") = secure {
    // run up to count parallel requests with body, server shall fail each, nevertheless response shall be delivered.
    val count = 1

    def clients(socketGroup: SocketGroup, tls: TLSContext): Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))

      Stream.eval(client[IO]()(socketGroup, tls)).flatMap { httpClient =>
      Stream.range(0,count).unchunk.map { idx =>
      httpClient.request(request).map { resp =>
        idx -> (resp.header.status == HttpStatusCode.BadRequest)
      }}}
    }

    Stream.resource(httpResources).flatMap { case (group, tls) =>
      (Stream.sleep_[IO](3.second) ++
        (Stream(
          http.server[IO](
            new InetSocketAddress("127.0.0.1", 9090)
            , requestFailure = _ => {
              Stream(HttpResponse[IO](HttpStatusCode.BadRequest)).covary[IO]
            }
          )(failRouteService)(group).drain
        ).covary[IO] ++ Stream.sleep_[IO](1.second) ++ clients(group, tls)).parJoin[IO, (Int, Boolean)](MaxConcurrency))
        .take(count)
        .filter { case (_, success) => success }
    }.compile.toVector.unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)
  }



  property("request-failed-body-send") = secure {
    // run up to count parallel requests with body, server shall fail each (when sending body), nevertheless response shall be delivered.
    val count = 100

    def clients(socketGroup: SocketGroup, tls: TLSContext): Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))

      Stream.eval(client[IO]()(socketGroup, tls)).flatMap { httpClient =>
        Stream.range(0,count).unchunk.map { idx =>
          httpClient.request(request).map { resp =>
            idx -> (resp.header.status == HttpStatusCode.Ok) // body won't be consumed, and request was succesfully sent
          }
        }
      }
    }

    Stream.resource(httpResources).flatMap { case (group, tls) =>
      (Stream.sleep_[IO](3.second) ++
        (Stream(
          http.server[IO](
            new InetSocketAddress("127.0.0.1", 9090)
            , sendFailure = (_, _, _) => Stream.empty
          )(failingResponse)(group).drain
        ).covary[IO] ++ Stream.sleep_[IO](1.second) ++ clients(group, tls)).parJoin[IO, (Int, Boolean)](MaxConcurrency))
        .take(count)
        .filter { case (_, success) => success }
    }.compile.toVector.unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)
  }



}
