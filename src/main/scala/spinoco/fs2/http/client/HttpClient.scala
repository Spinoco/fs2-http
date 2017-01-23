package spinoco.fs2.http.client

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.TimeoutException

import fs2._
import fs2.util.Async
import scodec.Codec
import spinoco.fs2.http.{HttpRequest, HttpResponse}
import spinoco.protocol.http.codec.{HttpRequestHeaderCodec, HttpResponseHeaderCodec}
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
    * @param chunkSize      Size of the chunk to used when sending//receiving stream to/from server
    * @param timeout        Request will fail if response header and response body is not received within supplied timeout
    * @param requestCodec   Codec used to encode request
    * @param responseCodec  Codec used to decode response
    *
    */
  def request(
     request: HttpRequest[F]
     , chunkSize: Int = 10*1024
     , maxResponseHeaderSize: Int = 4096
     , timeout: Duration = 5.seconds
     , requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
     , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
  ):Stream[F,HttpResponse[F]]



}


 object HttpClient {



  def apply[F[_]](implicit AG: AsynchronousChannelGroup, F: Async[F]):F[HttpClient[F]] = F.delay {



    new HttpClient[F] {
      def request(
       request: HttpRequest[F]
       , chunkSize: Int
       , maxResponseHeaderSize: Int
       , timeout: Duration
       , requestCodec: Codec[HttpRequestHeader]
       , responseCodec: Codec[HttpResponseHeader]
      ): Stream[F, HttpResponse[F]] = {
        import spinoco.fs2.http.internal._
        Stream.eval(addressForRequest(request.scheme, request.host)).flatMap { address =>
        io.tcp.client(address).flatMap { socket =>
          timeout match {
            case fin: FiniteDuration =>
              Stream.eval(F.delay(System.currentTimeMillis())).flatMap { start =>
              HttpRequest.toStream(request, requestCodec).to(socket.writes(Some(fin))).last.flatMap { _ =>
              Stream.eval(F.delay(System.currentTimeMillis())).flatMap { requestSent =>
                val remains = fin - (requestSent - start).millis
                if (remains <= 0.millis) Stream.fail(new TimeoutException()) // todo: correct the timeout reads for the body.
                else socket.reads(chunkSize, Some(remains)) through HttpResponse.fromStream[F](maxResponseHeaderSize, responseCodec)
              }}}

            case infinite =>
              HttpRequest.toStream(request, requestCodec).to(socket.writes(None)).last.flatMap { _ =>
                socket.reads(chunkSize, None) through HttpResponse.fromStream[F](maxResponseHeaderSize, responseCodec)
              }
          }
        }}
      }
    }
  }


}

