package spinoco.fs2.http.internal

import java.net.InetSocketAddress

import cats.effect.IO
import fs2._
import spinoco.fs2.http
import spinoco.fs2.http.HttpResponse
import spinoco.protocol.http.header._
import spinoco.protocol.mime.{ContentType, MediaType}
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}


object HttpServerApp extends App {

  import spinoco.fs2.http.Resources._

  def service(request: HttpRequestHeader, body: Stream[IO,Byte]): Stream[IO,HttpResponse[IO]] = {
    if (request.path != Uri.Path / "echo") Stream.emit(HttpResponse[IO](HttpStatusCode.Ok).withUtf8Body("Hello World")).covary[IO]
    else {
      val ct =  request.headers.collectFirst { case `Content-Type`(ct) => ct }.getOrElse(ContentType.BinaryContent(MediaType.`application/octet-stream`, None))
      val size = request.headers.collectFirst { case `Content-Length`(sz) => sz }.getOrElse(0l)
      val ok = HttpResponse(HttpStatusCode.Ok).chunkedEncoding.withContentType(ct).withBodySize(size)

      Stream.emit(ok.copy(body = body.take(size)))
    }
  }

  http.server(new InetSocketAddress("127.0.0.1", 9090))(service).compile.drain.unsafeRunSync()

}
