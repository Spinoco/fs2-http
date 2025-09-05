package spinoco.fs2.http

import cats.Applicative
import cats.effect.{Async, Resource}
import fs2._
import fs2.io.net.tls.TLSContext
import fs2.io.net.{Network, Socket}
import scodec.{Codec, Decoder, Encoder}
import spinoco.fs2.http.internal.addressForRequest
import spinoco.fs2.http.sse.{SSEDecoder, SSEEncoding}
import spinoco.fs2.http.websocket.{Frame, WebSocket, WebSocketRequest}
import spinoco.protocol.http.codec.{HttpRequestHeaderCodec, HttpResponseHeaderCodec}
import spinoco.protocol.http.header._
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader}
import spinoco.protocol.mime.MediaType

import scala.concurrent.duration._


trait HttpClient[F[_]] {

  /**
    * Performs a single `request`. Returns a Resource that provides one response if client replied.
    *
    * Note that request may contain stream of bytes that shall be sent to client.
    * The response from server is evaluated _after_ client sent all data, including the body to the server.
    *
    * The Resource ensures proper cleanup of the underlying connection. The response body stream
    * remains available during the Resource's lifetime. Typical usage:
    *  `
    *  request(thatRequest).use { response =>
    *    response.body.through(bodyProcessor).compile.drain
    *  }
    *  `
    *
    * This methods allows to be supplied with timeout (default is 5s) that the request awaits to be completed before
    * failure.
    *
    * Timeout is computed once the requests was sent and includes also the time for processing the response header
    * but not the body.
    *
    * Resulting Resource fails with TimeoutException if the timeout is triggered
    *
    * @param request        Request to make to server
    * @param timeout        Request will fail if response header is not received within supplied timeout
   *                        Note that this is timeout just for the header, the body is available as stream and may be processed
   *                        at leisure. Also timeout applies after the request was sent, so if the request body is a stream
   *                        that does not end, the timeout will not be applied.
    *
    */
  def request(
     request: HttpRequest[F]
     , maxResponseHeaderSize: Int = 4096
     , timeout: Duration = 5.seconds
  ): Resource[F,HttpResponse[F]]


  /**
    * Establishes websocket connection to the server.
    *
    * Implementation is according to RFC-6455 (https://tools.ietf.org/html/rfc6455).
    *
    * If this is established successfully, then this consults `pipe` to receive/sent any frames
    * From/To server. Once the connection finishes, this will emit once None.
    *
    * If the connection was not established correctly (i.e. Authorization failure) this will not
    * consult supplied pipe and instead this will immediately emit response received from the server.
    *
    * @param request              WebSocket request
    * @param maxResponseHeaderSize  Max size of  Http Response header received
    * @param maxFrameSize         Maximum size of single WebSocket frame. If the binary size of single frame is larger than
    *                             supplied value, WebSocket will fail.
    * @param onConnect            Function to evaluate to pipe when successfully connected.
    */
  def websocket[I : Decoder, O : Encoder](
     request: WebSocketRequest
     , maxResponseHeaderSize: Int = 4096
     , maxFrameSize: Int = 1024*1024
  )(onConnect: HttpResponseHeader => Pipe[F, Frame[I], Frame[O]]): Stream[F, Option[HttpResponseHeader]]

  /**
    * Reads SSE encoded stream of data from the server.
    *
    * @param request                  Request to server. Note that this must be `GET` request.
    * @param maxResponseHeaderSize    Max size of expected response header
    * @param chunkSize                Max size of the chunk
    */
  def sse[A : SSEDecoder](
    request: HttpRequest[F]
    , maxResponseHeaderSize: Int = 4096
  ): Stream[F, A]

}


 object HttpClient {


   @inline def apply[F[_]](implicit instance: HttpClient[F]): HttpClient[F] = instance

   /**
     * Creates an Http Client
     * @param requestCodec    Codec used to decode request header
     * @param responseCodec   Codec used to encode response header
     */
  def create[F[_]
  : Async
  : Network
  : TLSContext
  ](
   requestCodec         : Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
   , responseCodec      : Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
  ):F[HttpClient[F]] = Applicative[F].pure {

    new HttpClient[F] {
      def request(
       request: HttpRequest[F]
       , maxResponseHeaderSize: Int
       , timeout: Duration
      ): Resource[F, HttpResponse[F]] = {
        for {
          address <- Resource.eval(addressForRequest[F](request.scheme, request.host))
          tcpSocket <- Network[F].client(address)
          socket <- {
            if (!request.isSecure) Resource.pure[F, Socket[F]](tcpSocket)
            else spinoco.fs2.http.internal.clientLiftToSecure[F](tcpSocket, request.host) // need to lift this to resource
          }
          response <- Resource.eval(impl.request[F](request, maxResponseHeaderSize, timeout, requestCodec, responseCodec)(socket))
        } yield response
      }

      def websocket[I : Decoder, O : Encoder](
        request: WebSocketRequest
        , maxResponseHeaderSize: Int
        , maxFrameSize: Int
      )(onConnect: HttpResponseHeader => Pipe[F, Frame[I], Frame[O]]): Stream[F, Option[HttpResponseHeader]] =
        WebSocket.client(request,maxResponseHeaderSize,  maxFrameSize, requestCodec, responseCodec)(onConnect)


      def sse[A : SSEDecoder](rq: HttpRequest[F], maxResponseHeaderSize: Int): Stream[F, A] =
        Stream.resource(request(rq, maxResponseHeaderSize, Duration.Inf)).flatMap { resp =>
          if (resp.header.headers.exists { case `Content-Type`(ct) => ct.mediaType == MediaType.`text/event-stream`  })
            Stream.raiseError[F](new Throwable(s"Received response is not SSE: $resp"))
          else
            resp.body through SSEEncoding.decodeA[F, A]
        }
    }
  }


   private[http] object impl {

     def request[F[_] : Async](
      request: HttpRequest[F]
      , maxResponseHeaderSize: Int
      , timeout: Duration
      , requestCodec: Codec[HttpRequestHeader]
      , responseCodec: Codec[HttpResponseHeader]
     )(socket: Socket[F]):F[HttpResponse[F]] = {
       timeout match {
         case finite: FiniteDuration =>
           (Stream.eval(HttpRequest.toStream(request, requestCodec).through(socket.writes).compile.drain) >>
             socket.reads.through(HttpResponse.fromStream[F](maxResponseHeaderSize, responseCodec))
             .timeout(finite)).compile.lastOrError

         case _ =>
           (Stream.eval(HttpRequest.toStream(request, requestCodec).through(socket.writes).compile.drain) >>
             socket.reads.through(HttpResponse.fromStream[F](maxResponseHeaderSize, responseCodec)))
             .compile.lastOrError
       }
     }

   }


}

