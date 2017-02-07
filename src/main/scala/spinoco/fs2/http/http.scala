package spinoco.fs2

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import javax.net.ssl.SSLContext

import fs2.{Strategy, Stream}
import fs2.util._
import scodec.Codec
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader}
import spinoco.protocol.http.codec.{HttpRequestHeaderCodec, HttpResponseHeaderCodec}

import scala.concurrent.duration._


package object http {

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
  def server[F[_]](
     bindTo: InetSocketAddress
     , maxConcurrent: Int = Int.MaxValue
     , receiveBufferSize: Int = 256 * 1024
     , maxHeaderSize: Int = 10 *1024
     , requestHeaderReceiveTimeout: Duration = 5.seconds
     , requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
     , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
     , requestFailure : Throwable => Stream[F, HttpResponse[F]] = HttpServer.handleRequestParseError[F] _
     , sendFailure: (Option[HttpRequestHeader], HttpResponse[F], Throwable) => Stream[F, Nothing] = HttpServer.handleSendFailure[F] _
   )(
     service:  (HttpRequestHeader, Stream[F,Byte]) => Stream[F,HttpResponse[F]]
   )(
     implicit
     AG: AsynchronousChannelGroup
     , F: Async[F]
   ):Stream[F,Unit] = HttpServer(
    maxConcurrent = maxConcurrent
    , receiveBufferSize = receiveBufferSize
    , maxHeaderSize = maxHeaderSize
    , requestHeaderReceiveTimeout = requestHeaderReceiveTimeout
    , requestCodec = requestCodec
    , responseCodec = responseCodec
    , bindTo = bindTo
    , service = service
    , requestFailure = requestFailure
    , sendFailure = sendFailure
  )


  /**
    * Creates a client that can be used to make http requests to servers
    *
    * @param requestCodec    Codec used to decode request header
    * @param responseCodec   Codec used to encode response header
    * @param sslStrategy     Strategy used to perform blocking SSL operations
    */
  def client[F[_]](
   requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
   , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
   , sslStrategy: => Strategy = Strategy.fromCachedDaemonPool("fs2-http-ssl")
   , sslContext: => SSLContext = { val ctx = SSLContext.getInstance("TLS"); ctx.init(null,null,null); ctx }
  )(implicit AG: AsynchronousChannelGroup, F: Async[F]):F[HttpClient[F]] =
    HttpClient(requestCodec, responseCodec, sslStrategy, sslContext)

}
