package spinoco.fs2.http.internal

import cats.effect.IO
import com.comcast.ip4s._
import fs2._
import fs2.io.net.Network
import spinoco.fs2.http
import spinoco.fs2.http.HttpResponse
import spinoco.protocol.http.header._
import spinoco.protocol.mime.{ContentType, MediaType}
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}


object HttpServerApp extends App {

  import cats.effect.unsafe.implicits.global
  implicit val network: Network[IO] = Network.forAsync[IO]


  def service(request: HttpRequestHeader, body: Stream[IO,Byte]): Stream[IO,HttpResponse[IO]] = {
    if (request.path != Uri.Path / "echo") Stream.emit(HttpResponse[IO](HttpStatusCode.Ok).withUtf8Body("Hello World")).covary[IO]
    else {
      val ct =  request.headers.collectFirst { case `Content-Type`(ct) => ct }.getOrElse(ContentType.BinaryContent(MediaType.`application/octet-stream`, None))
      val size = request.headers.collectFirst { case `Content-Length`(sz) => sz }.getOrElse(0L)
      val ok = HttpResponse(HttpStatusCode.Ok).chunkedEncoding.withContentType(ct).withBodySize(size)

      Stream.emit(ok.copy(body = body.take(size)))
    }
  }

  http.server(SocketAddress(host"127.0.0.1", port"9090"))(service)
  .compile.drain.unsafeRunSync()

}
