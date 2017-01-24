package spinoco.fs2.http.client

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.TimeoutException

import fs2._
import fs2.io.tcp.Socket
import fs2.util.Async
import scodec.Codec
import spinoco.fs2.http.{HttpRequest, HttpResponse}
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader}

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
     , chunkSize: Int = 10*1024
     , maxResponseHeaderSize: Int = 4096
     , timeout: Duration = 5.seconds
  ):Stream[F,HttpResponse[F]]



}


 object HttpClient {


   /**
     * Creates an Http Client
     * @param requestCodec    Codec used to decode request header
     * @param responseCodec   Codec used to encode response header
     */
  def apply[F[_]](
    requestCodec: Codec[HttpRequestHeader]
    , responseCodec: Codec[HttpResponseHeader]
  )(implicit AG: AsynchronousChannelGroup, F: Async[F]):F[HttpClient[F]] = F.delay {

    new HttpClient[F] {
      def request(
       request: HttpRequest[F]
       , chunkSize: Int
       , maxResponseHeaderSize: Int
       , timeout: Duration
      ): Stream[F, HttpResponse[F]] = {
        import spinoco.fs2.http.internal._
        Stream.eval(addressForRequest(request.scheme, request.host)).flatMap { address =>
        io.tcp.client(address).flatMap { impl.request(request, chunkSize, maxResponseHeaderSize, timeout, requestCodec, responseCodec ) }}
      }
    }
  }


   private[client] object impl {

     def request[F[_]](
      request: HttpRequest[F]
      , chunkSize: Int
      , maxResponseHeaderSize: Int
      , timeout: Duration
      , requestCodec: Codec[HttpRequestHeader]
      , responseCodec: Codec[HttpResponseHeader]
     )(socket: Socket[F])(implicit F: Async[F]): Stream[F, HttpResponse[F]] = {
       import Stream._
       timeout match {
         case fin: FiniteDuration =>
           eval(F.delay(System.currentTimeMillis())).flatMap { start =>
           HttpRequest.toStream(request, requestCodec).to(socket.writes(Some(fin))).last.flatMap { _ =>
           eval(async.signalOf[F, Boolean](true)).flatMap { timeoutSignal =>
             def readWithTimeout: Stream[F, Byte] = {
               eval(timeoutSignal.get).flatMap { shallTimeout =>
                 if (!shallTimeout) eval(socket.read(chunkSize, None))
                 else {
                   eval(F.delay(System.currentTimeMillis())).flatMap { now =>
                     val remains = fin - (now - start).millis
                     if (remains <= 0.millis) Stream.fail(new TimeoutException())
                     else {
                       eval(socket.read(chunkSize, Some(remains)))
                     }
                   }
                 }
               }.flatMap {
                 case Some(bytes) => Stream.chunk(bytes) ++ readWithTimeout
                 case None => Stream.empty
               }
             }

             readWithTimeout through  HttpResponse.fromStream[F](maxResponseHeaderSize, responseCodec) flatMap { response =>
               eval_(timeoutSignal.set(false)) ++ emit(response)
             }
           }
           }}

         case infinite =>
           HttpRequest.toStream(request, requestCodec).to(socket.writes(None)).last.flatMap { _ =>
             socket.reads(chunkSize, None) through HttpResponse.fromStream[F](maxResponseHeaderSize, responseCodec)
           }
       }
     }

   }


}

