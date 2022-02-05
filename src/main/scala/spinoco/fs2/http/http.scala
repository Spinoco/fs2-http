package spinoco.fs2

import cats.effect.Async
import com.comcast.ip4s.{Host, SocketAddress}

import fs2._
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
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
    * @param service                      Pipe that defines handling of each incoming request and produces a response
    * @param socketGroup                  Group of sockets from which to create the server socket.
    */
  def server[F[_]: Async: Network](
     bindTo: SocketAddress[Host]
     , maxConcurrent: Int = Int.MaxValue
     , receiveBufferSize: Int = 256 * 1024
     , maxHeaderSize: Int = 10 *1024
     , requestHeaderReceiveTimeout: Duration = 5.seconds
     , requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
     , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
   )(
     service:  (HttpRequestHeader, Stream[F,Byte]) => Stream[F,HttpResponse[F]]
   ):Stream[F,Unit] = HttpServer.mk(
    maxConcurrent = maxConcurrent
    , receiveBufferSize = receiveBufferSize
    , maxHeaderSize = maxHeaderSize
    , requestHeaderReceiveTimeout = requestHeaderReceiveTimeout
    , requestCodec = requestCodec
    , responseCodec = responseCodec
    , bindTo = bindTo
    , service = service
    , requestFailure = HttpServer.handleRequestParseError[F] _
    , sendFailure = HttpServer.handleSendFailure[F] _
  )


  /**
    * Creates a client that can be used to make http requests to servers
    *
    * @param requestCodec    Codec used to decode request header
    * @param responseCodec   Codec used to encode response header
    * @param socketGroup     Group of sockets from which to create the client for http request.
    * @param tlsContext      The TLS context used for elevating the http socket to https.
    */
  def client[F[_]: Async: Network](
    requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
    , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
  )(
    tlsContext: TLSContext[F]
  ):F[HttpClient[F]] = {
    HttpClient.mk(requestCodec, responseCodec)(tlsContext)
  }

}
