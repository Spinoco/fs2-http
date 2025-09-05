package spinoco.fs2.http.internal

import cats.effect.{IO, Resource}
import fs2._
import spinoco.fs2.http.{HttpResponse, HttpServer}
import spinoco.protocol.http.header._
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}
import spinoco.protocol.mime.{ContentType, MediaType}
import com.comcast.ip4s._


object HttpServerApp extends App {

  import spinoco.fs2.http.Resources._

  def service(request: HttpRequestHeader, body: Stream[IO,Byte]): Resource[IO,HttpResponse[IO]] = {
    if (request.path != Uri.Path / "echo") Resource.pure(HttpResponse[IO](HttpStatusCode.Ok).withUtf8Body("Hello World"))
    else {
      val ct =  request.headers.collectFirst { case `Content-Type`(ct) => ct }.getOrElse(ContentType.BinaryContent(MediaType.`application/octet-stream`, None))
      val size = request.headers.collectFirst { case `Content-Length`(sz) => sz }.getOrElse(0L)
      val ok = HttpResponse(HttpStatusCode.Ok).chunkedEncoding.withContentType(ct).withBodySize(size)

      Resource.pure(ok.copy(body = body.take(size)))
    }
  }

  HttpServer.create[IO](Some(SocketAddress(ipv4"127.0.0.1", port"9090")))(service).parJoin(10).compile.drain.unsafeRunSync()

}
