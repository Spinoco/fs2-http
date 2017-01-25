package spinoco.fs2.http.server

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import fs2._
import spinoco.fs2.http.HttpResponse
import spinoco.protocol.http.header._
import spinoco.protocol.http.header.value.{ContentType, MediaType}
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}


object HttpServerApp extends App {

  val ES = Executors.newCachedThreadPool(Strategy.daemonThreadFactory("AG"))

  implicit val S = Strategy.fromExecutor(ES)

  implicit val AG = AsynchronousChannelGroup.withThreadPool(ES)

  def service(request: HttpRequestHeader, body: Stream[Task,Byte]): Stream[Task,HttpResponse[Task]] = {
    if (request.path != Uri.Path / "echo") Stream.emit(HttpResponse(HttpStatusCode.Ok).withUtf8Body("Hello World"))
    else {
      val ct =  request.headers.collectFirst { case `Content-Type`(ct) => ct }.getOrElse(ContentType(MediaType.`application/octet-stream`, None, None))
      val size = request.headers.collectFirst { case `Content-Length`(sz) => sz }.getOrElse(0l)
      val ok = HttpResponse(HttpStatusCode.Ok).chunkedEncoding.withContentType(ct).withBodySize(size)

      Stream.emit(ok.copy(body = body.take(size)))
    }
  }

  apply()(
    new InetSocketAddress("127.0.0.1", 9090)
    , service
  ).run.unsafeRun()

}
