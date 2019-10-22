package spinoco.fs2.http

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.TimeUnit

import cats.Applicative
import javax.net.ssl.SSLContext
import cats.effect._
import fs2._
import fs2.concurrent.SignallingRef
import fs2.io.tcp.Socket
import scodec.{Codec, Decoder, Encoder}
import spinoco.fs2.http.internal.{addressForRequest, clientLiftToSecure, readWithTimeout}
import spinoco.fs2.http.sse.{SSEDecoder, SSEEncoding}
import spinoco.fs2.http.websocket.{Frame, WebSocket, WebSocketRequest}
import spinoco.protocol.http.header._
import spinoco.protocol.mime.MediaType
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


trait HttpClient[F[_]] {

  /**
    * Performs a single `request`. Returns one response if client replied.
    *
    * Note that request may contain stream of bytes that shall be sent to client.
    * The response from server is evaluated _after_ client sent all data, including the body to the server.
    *
    * Note that the evaluation of `body` in HttpResponse may not outlive scope of resulting stream. That means
    * only correct way to process the result is within the flatMap i.e.
    *  `
    *  request(thatRequest).flatMap { response =>
    *    response.body.through(bodyProcessor)
    *  }
    *  `
    *
    * This methods allows to be supplied with timeout (default is 5s) that the request awaits to be completed before
    * failure.
    *
    * Timeout is computed once the requests was sent and includes also the time for processing the response header
    * but not the body.
    *
    * Resulting stream fails with TimeoutException if the timeout is triggered
    *
    * @param request        Request to make to server
    * @param chunkSize      Size of the chunk to used when receiving response from server
    * @param timeout        Request will fail if response header and response body is not received within supplied timeout
    *
    */
  def request(
     request: HttpRequest[F]
     , chunkSize: Int = 32*1024
     , maxResponseHeaderSize: Int = 4096
     , timeout: Duration = 5.seconds
  ):Stream[F,HttpResponse[F]]


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
    * @param pipe                 Pipe that is consulted when WebSocket is established correctly
    * @param maxResponseHeaderSize  Max size of  Http Response header received
    * @param chunkSize            Size of receive buffer to use
    * @param maxFrameSize         Maximum size of single WebSocket frame. If the binary size of single frame is larger than
    *                             supplied value, WebSocket will fail.
    *
    */
  def websocket[I : Decoder, O : Encoder](
     request: WebSocketRequest
     , pipe: Pipe[F, Frame[I], Frame[O]]
     , maxResponseHeaderSize: Int = 4096
     , chunkSize: Int = 32 * 1024
     , maxFrameSize: Int = 1024*1024
  ): Stream[F, Option[HttpResponseHeader]]

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
    , chunkSize: Int = 32 * 1024
  ): Stream[F, A]

}


 object HttpClient {


   /**
     * Creates an Http Client
     * @param requestCodec    Codec used to decode request header
     * @param responseCodec   Codec used to encode response header
     * @param sslExecutionContext     Strategy used when communication with SSL (https or wss)
     * @param sslContext      SSL Context to use with SSL Client (https, wss)
     * @param blocker         An execution context for blocking operations
     */
  def apply[F[_] : ConcurrentEffect : ContextShift : Timer](
   requestCodec         : Codec[HttpRequestHeader]
   , responseCodec      : Codec[HttpResponseHeader]
   , sslExecutionContext: => ExecutionContext
   , sslContext         : => SSLContext
   , blocker            : Blocker
  )(implicit AG: AsynchronousChannelGroup):F[HttpClient[F]] = Sync[F].delay {
    lazy val sslCtx = sslContext
    lazy val sslS = sslExecutionContext

    new HttpClient[F] {
      def request(
       request: HttpRequest[F]
       , chunkSize: Int
       , maxResponseHeaderSize: Int
       , timeout: Duration
      ): Stream[F, HttpResponse[F]] = {
        Stream.eval(addressForRequest[F](request.scheme, request.host)).flatMap { address =>
        Stream.resource(new io.tcp.SocketGroup(AG, blocker).client[F](address))
        .evalMap { socket =>
          if (!request.isSecure) Applicative[F].pure(socket)
          else clientLiftToSecure[F](sslS, sslCtx)(socket, request.host)
        }
        .flatMap { impl.request[F](request, chunkSize, maxResponseHeaderSize, timeout, requestCodec, responseCodec ) }}
      }

      def websocket[I : Decoder, O : Encoder](
        request: WebSocketRequest
        , pipe: Pipe[F, Frame[I], Frame[O]]
        , maxResponseHeaderSize: Int
        , chunkSize: Int
        , maxFrameSize: Int
      ): Stream[F, Option[HttpResponseHeader]] =
        WebSocket.client(request,pipe,maxResponseHeaderSize,chunkSize,maxFrameSize, requestCodec, responseCodec, sslS, sslCtx)


      def sse[A : SSEDecoder](rq: HttpRequest[F], maxResponseHeaderSize: Int, chunkSize: Int): Stream[F, A] =
        request(rq, chunkSize, maxResponseHeaderSize, Duration.Inf).flatMap { resp =>
          if (resp.header.headers.exists { 
              case `Content-Type`(ct) => ct.mediaType == MediaType.`text/event-stream`
              case _ => false
            })
            resp.body through SSEEncoding.decodeA[F, A]
          else
            Stream.raiseError(new Throwable(s"Received response is not SSE: $resp"))
        }
    }
  }


   private[http] object impl {

     def request[F[_] : Concurrent](
      request: HttpRequest[F]
      , chunkSize: Int
      , maxResponseHeaderSize: Int
      , timeout: Duration
      , requestCodec: Codec[HttpRequestHeader]
      , responseCodec: Codec[HttpResponseHeader]
     )(socket: Socket[F])(implicit clock: Clock[F]):Stream[F, HttpResponse[F]] = {
       import Stream._
       timeout match {
         case fin: FiniteDuration =>
           eval(clock.realTime(TimeUnit.MILLISECONDS)).flatMap { start =>
           HttpRequest.toStream(request, requestCodec).through(socket.writes(Some(fin))).last.onFinalize(socket.endOfOutput).flatMap { _ =>
           eval(SignallingRef[F, Boolean](true)).flatMap { timeoutSignal =>
           eval(clock.realTime(TimeUnit.MILLISECONDS)).flatMap { sent =>
             val remains = fin - (sent - start).millis
             readWithTimeout(socket, remains, timeoutSignal.get, chunkSize)
             .through (HttpResponse.fromStream[F](maxResponseHeaderSize, responseCodec))
             .flatMap { response =>
               eval_(timeoutSignal.set(false)) ++ emit(response)
             }
           }}}}

         case _ =>
           HttpRequest.toStream(request, requestCodec).through(socket.writes(None)).last.onFinalize(socket.endOfOutput).flatMap { _ =>
             socket.reads(chunkSize, None) through HttpResponse.fromStream[F](maxResponseHeaderSize, responseCodec)
           }
       }
     }

   }


}

