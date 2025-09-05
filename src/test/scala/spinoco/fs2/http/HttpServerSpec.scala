package spinoco.fs2.http

import cats.effect.{IO, Resource}
import com.comcast.ip4s._
import fs2.{RaiseThrowable, _}
import org.scalacheck.Prop._
import org.scalacheck.Properties
import spinoco.protocol.http.header.{`Content-Length`, `Content-Type`}
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}
import spinoco.protocol.mime.{ContentType, MediaType}

import scala.concurrent.duration._

object HttpServerSpec extends Properties("HttpServer"){
  import Resources._
  
  implicit val raiseThrowable: RaiseThrowable[IO] = new RaiseThrowable[IO] {
    def raiseError[A](e: Throwable): IO[A] = IO.raiseError(e)
  }

  val MaxConcurrency: Int = 10

  def echoService(request: HttpRequestHeader, body: Stream[IO,Byte]): Resource[IO,HttpResponse[IO]] = {
    if (request.path != Uri.Path / "echo") Resource.pure(HttpResponse[IO](HttpStatusCode.Ok).withUtf8Body("Hello World"))
    else {
      val ct =  request.headers.collectFirst { case `Content-Type`(ct0) => ct0 }.getOrElse(ContentType.BinaryContent(MediaType.`application/octet-stream`, None))
      val size = request.headers.collectFirst { case `Content-Length`(sz) => sz }.getOrElse(0l)
      val ok = HttpResponse(HttpStatusCode.Ok).chunkedEncoding.withContentType(ct).withBodySize(size)

      Resource.pure(ok.copy(body = body.take(size)))
    }
  }

  def failRouteService(request: HttpRequestHeader, body: Stream[IO,Byte]): Resource[IO,HttpResponse[IO]] = {
    Resource.raiseError[IO, HttpResponse[IO], Throwable](new Throwable("Booom!"))
  }

  def failingResponse(request: HttpRequestHeader, body: Stream[IO,Byte]): Resource[IO,HttpResponse[IO]] = Resource.pure {
    HttpResponse(HttpStatusCode.Ok).copy(body = Stream.raiseError[IO](new Throwable("Kaboom!")))
  }



  property("simultaneous-requests") = secure {
    // run up to count parallel requests and then make sure all of them pass within timeout
    val count = 100

    def clients : Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request = HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))
      Stream.eval(HttpClient.create[IO]()).flatMap { httpClient =>
      Stream.range(0,count).chunkLimit(1).unchunks.map { idx =>
        Stream.resource(httpClient.request(request)).map(resp => idx -> (resp.header.status == HttpStatusCode.Ok))
      }}
    }


    (Stream(
      HttpServer.create[IO](Some(SocketAddress(ipv4"127.0.0.1", port"9090")))(echoService).parJoin(MaxConcurrency).drain
    ).covary[IO] ++ Stream.sleep_[IO](1.second) ++ clients)
    .parJoin(MaxConcurrency)
    .take(count)
    .filter { case (idx, success) => success }
    .compile.toVector.unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)



  }

  property("simultaneous-requests-echo body") = secure {
    // run up to count parallel requests with body,  and then make sure all of them pass within timeout with body echoed back
    val count = 100

    def clients : Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))
       .withBody("Hello")(BodyEncoder.utf8String, raiseThrowable)

      Stream.eval(HttpClient.create[IO]()).flatMap { httpClient =>
        Stream.range(0,count).chunkLimit(1).unchunks.map { idx =>
          Stream.resource(httpClient.request(request)).flatMap { resp =>
            Stream.eval(resp.bodyAsString).map { attempt =>
              val okResult = resp.header.status == HttpStatusCode.Ok
              attempt.map(_ == "Hello").map(r => idx -> (r && okResult)).getOrElse(idx -> false)
            }
          }
        }}
    }

    ( Stream.sleep_[IO](3.second) ++
    (Stream(
      HttpServer.create[IO](Some(SocketAddress(ipv4"127.0.0.1", port"9090")))(echoService).parJoin(MaxConcurrency).drain
    ).covary[IO] ++ Stream.sleep_[IO](3.second) ++ clients).parJoin(MaxConcurrency))
    .take(count)
    .filter { case (idx, success) => success }
    .compile.toVector.unsafeRunTimed(60.seconds).map { _.size } ?= Some(count)

  }


  property("request-failed-to-route") = secure {
    // run up to count parallel requests with body, server shall fail each, nevertheless response shall be delivered.
    val count = 1

    def clients : Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))

      Stream.eval(HttpClient.create[IO]()).flatMap { httpClient =>
      Stream.range(0,count).chunkLimit(1).unchunks.map { idx =>
        // individual client stream
        Stream.resource(httpClient.request(request).attempt).map {
          case Left(resp) =>
            idx -> false
          case Right(resp) =>
            idx -> (resp.header.status == HttpStatusCode.BadRequest)
        }
      }}
    }

    def server =
      Stream.sleep[IO](3.second) >>
        HttpServer.create[IO](
          Some(SocketAddress(ipv4"127.0.0.1", port"9090"))
        )(failRouteService).parJoin(MaxConcurrency)

    def clientsStream =
      Stream.sleep[IO](5.second) >> clients.parJoin(MaxConcurrency).drain

    server.mergeHaltBoth(clientsStream)
      .compile.toList
      .unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)

  }



  property("request-failed-body-send") = secure {
    // run up to count parallel requests with body, server shall fail each (when sending body), nevertheless response shall be delivered.
    val count = 100

    def clients : Stream[IO, Stream[IO, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[IO](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))

      Stream.eval(HttpClient.create[IO]()).flatMap { httpClient =>
        Stream.range(0,count).chunkLimit(1).unchunks.map { idx =>
          Stream.resource(httpClient.request(request)).map { resp =>
            idx -> (resp.header.status == HttpStatusCode.Ok) // body won't be consumed, and request was succesfully sent
          }
        }
      }
    }

    (Stream.sleep_[IO](3.second) ++
    (Stream(
      HttpServer.create[IO](
        Some(SocketAddress(ipv4"127.0.0.1", port"9090"))
      )(failingResponse).parJoin(MaxConcurrency).drain
    ).covary[IO] ++ Stream.sleep_[IO](1.second) ++ clients).parJoin(MaxConcurrency))
      .take(count)
      .filter { case (idx, success) => success }
      .compile.toVector.unsafeRunTimed(30.seconds).map { _.size } ?= Some(count)
  }



}
