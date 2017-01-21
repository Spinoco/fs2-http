package spinoco.fs2.http.server

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import fs2._
import fs2.util.Async
import scodec.Codec
import spinoco.fs2.http.{HttpRequest, HttpResponse}
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader}
import spinoco.protocol.http.codec.{HttpRequestHeaderCodec, HttpResponseHeaderCodec}

import scala.concurrent.duration._


object HttpServer {

  /**
    * Creates simple http server,
    *
    * Serve will run after the resulting stream is run.
    *
    * @param bindTo                       Address and port where to bind server to
    * @param maxConcurrent                Maximum requests to process concurrently
    * @param receiveBufferSize            Receive buffer size for each connection
    * @param maxHeaderSize                Maximum size of http header for incoming requests, in bytes
    * @param requestHeaderReceiveTimeout  A timeout to await request header to be fully received.
    *                                     Request will fail, if the header won't be read within this timeout.
    * @param requestCodec                 Codec for Http Request Header
    * @param service                      Pipe that defines handling of each incoming request and produces a response
    */
  def apply[F[_]](
    maxConcurrent: Int = Int.MaxValue
    , receiveBufferSize: Int = 256 * 1024
    , maxHeaderSize: Int = 10 *1024
    , requestHeaderReceiveTimeout: Duration = 5.seconds
    , requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
    , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
    , bindTo: InetSocketAddress
    , service:  Pipe[F, (HttpRequestHeader, Stream[F,Byte]), HttpResponse[F]]
  )(
    implicit
    AG: AsynchronousChannelGroup
    , F: Async[F]
  ):Stream[F,Unit] = {
    val readDuration = requestHeaderReceiveTimeout match {
      case fin: FiniteDuration => Some(fin)
      case _ => None
    }

    concurrent.join(maxConcurrent)(
      io.tcp.server(bindTo, receiveBufferSize = receiveBufferSize).map { _.flatMap { socket =>
        socket.
        reads(maxHeaderSize, readDuration)
        .through(HttpRequest.fromStream(maxHeaderSize, requestCodec))
        .through(service)
        .flatMap { resp =>
          HttpResponse.toStream(resp, responseCodec).through(socket.writes())
        }
      }}
    )

  }

}
