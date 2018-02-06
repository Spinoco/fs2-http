package spinoco.fs2.http

import java.net.InetSocketAddress

import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import spinoco.fs2.http
import spinoco.fs2.http.body.BodyEncoder
import spinoco.protocol.http.header.{`Content-Length`, `Content-Type`}
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}
import spinoco.protocol.mime.{ContentType, MediaType}

import scala.concurrent.duration._

object HttpServerSpec extends Properties("HttpServer"){
  import Resources._

  def echoService(request: HttpRequestHeader, body: Stream[Task,Byte]): Stream[Task,HttpResponse[Task]] = {
    if (request.path != Uri.Path / "echo") Stream.emit(HttpResponse(HttpStatusCode.Ok).withUtf8Body("Hello World"))
    else {
      val ct =  request.headers.collectFirst { case `Content-Type`(ct0) => ct0 }.getOrElse(ContentType.BinaryContent(MediaType.`application/octet-stream`, None))
      val size = request.headers.collectFirst { case `Content-Length`(sz) => sz }.getOrElse(0l)
      val ok = HttpResponse(HttpStatusCode.Ok).chunkedEncoding.withContentType(ct).withBodySize(size)

      Stream.emit(ok.copy(body = body.take(size)))
    }
  }

  def failRouteService(request: HttpRequestHeader, body: Stream[Task,Byte]): Stream[Task,HttpResponse[Task]] = {
    Stream.fail(new Throwable("Booom!"))
  }

  def failingResponse(request: HttpRequestHeader, body: Stream[Task,Byte]): Stream[Task,HttpResponse[Task]] = Stream {
    HttpResponse(HttpStatusCode.Ok).copy(body = Stream.fail(new Throwable("Kaboom!")))
  }


  property("simultaneous-requests") = secure {
    // run up to count parallel requests and then make sure all of them pass within timeout
    val count = 100

    def clients : Stream[Task, Stream[Task, (Int, Boolean)]] = {
      val request = HttpRequest.get[Task](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))
      Stream.eval(client[Task]()).flatMap { httpClient =>
      Stream.range(0,count).unchunk.map { idx =>
        httpClient.request(request).map(resp => idx -> (resp.header.status == HttpStatusCode.Ok))
      }}
    }


    concurrent.join(Int.MaxValue)(Stream(
      http.server[Task](new InetSocketAddress("127.0.0.1", 9090))(echoService).drain
    ) ++ time.sleep_[Task](1.second) ++ clients)
    .take(count)
    .filter { case (idx, success) => success }
    .runLog.unsafeTimed(30.seconds).unsafeRun().size ?= count



  }

  property("simultaneous-requests-echo body") = secure {
    // run up to count parallel requests with body,  and then make sure all of them pass within timeout with body echoed back
    val count = 100

    def clients : Stream[Task, Stream[Task, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[Task](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))
       .withBody("Hello")(BodyEncoder.utf8String)

      Stream.eval(client[Task]()).flatMap { httpClient =>
        Stream.range(0,count).unchunk.map { idx =>
          httpClient.request(request).flatMap (resp =>
            Stream.eval(resp.bodyAsString).map { attempt =>
              val okResult = resp.header.status == HttpStatusCode.Ok
              attempt.map(_ == "Hello").map(r => idx -> (r && okResult)) .getOrElse(idx -> false )
            }
          )
        }}
    }

    ( time.sleep_[Task](3.second) ++
    concurrent.join(Int.MaxValue)(Stream(
      http.server[Task](new InetSocketAddress("127.0.0.1", 9090))(echoService).drain
    ) ++ time.sleep_[Task](1.second) ++ clients))
    .take(count)
    .filter { case (idx, success) => success }
    .runLog.unsafeTimed(30.seconds).unsafeRun().size ?= count

  }


  property("request-failed-to-route") = secure {
    // run up to count parallel requests with body, server shall fail each, nevertheless response shall be delivered.
    val count = 100

    def clients : Stream[Task, Stream[Task, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[Task](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))


      Stream.eval(client[Task]()).flatMap { httpClient =>
      Stream.range(0,count).unchunk.map { idx =>
      httpClient.request(request).map { resp =>
        idx -> (resp.header.status == HttpStatusCode.BadRequest)
      }}}
    }

    (time.sleep_[Task](3.second) ++
    concurrent.join(Int.MaxValue)(Stream(
      http.server[Task](
        new InetSocketAddress("127.0.0.1", 9090)
      , requestFailure = _ => Stream(HttpResponse(HttpStatusCode.BadRequest))
      )(failRouteService).drain
    ) ++ time.sleep_[Task](1.second) ++ clients))
      .take(count)
      .filter { case (idx, success) => success }
      .runLog.unsafeTimed(30.seconds).unsafeRun().size ?= count
  }


  property("request-failed-body-send") = secure {
    // run up to count parallel requests with body, server shall fail each (when sending body), nevertheless response shall be delivered.
    val count = 100

    def clients : Stream[Task, Stream[Task, (Int, Boolean)]] = {
      val request =
        HttpRequest.get[Task](Uri.parse("http://127.0.0.1:9090/echo").getOrElse(throw new Throwable("Invalid uri")))

      Stream.eval(client[Task]()).flatMap { httpClient =>
        Stream.range(0,count).unchunk.map { idx =>
          httpClient.request(request).map { resp =>
            idx -> (resp.header.status == HttpStatusCode.Ok) // body won't be consumed, and request was succesfully sent
          }
        }
      }
    }

    (time.sleep_[Task](3.second) ++
    concurrent.join(Int.MaxValue)(Stream(
      http.server[Task](
        new InetSocketAddress("127.0.0.1", 9090)
        , sendFailure = (_, _, _) => Stream.empty
      )(failingResponse).drain
    ) ++ time.sleep_[Task](1.second) ++ clients))
      .take(count)
      .filter { case (idx, success) => success }
      .runLog.unsafeTimed(30.seconds).unsafeRun().size ?= count
  }



}
