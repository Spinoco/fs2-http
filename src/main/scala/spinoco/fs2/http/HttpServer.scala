package spinoco.fs2.http

import cats.Applicative
import cats.effect.{Async, Resource}
import cats.syntax.all._
import com.comcast.ip4s.{Host, SocketAddress}
import fs2._
import fs2.io.net.{Network, Socket}
import scodec.Codec
import spinoco.protocol.http.codec.{HttpRequestHeaderCodec, HttpResponseHeaderCodec}
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader, HttpStatusCode}

import scala.concurrent.duration._

object HttpServer {

  /**
    * Creates simple http server,
    *
    * Returns a stream of connection streams. Each inner stream represents a client connection.
    * Users can control parallelism by using parJoin with desired concurrency level.
    *
    * @param bindTo                       Optional socket address (host and port) where to bind server to. If None, binds to all interfaces.
    * @param maxHeaderSize                Maximum size of http header for incoming requests, in bytes
    * @param requestHeaderReceiveTimeout  A timeout to await request header to be fully received.
    *                                     Request will fail, if the header won't be read within this timeout.
    * @param requestCodec                 Codec for Http Request Header
    * @param responseCodec                Codec for Http Response Header
    * @param service                      A function that handles successful requests only.
    *                                     Takes (HttpRequestHeader, Stream[F,Byte]) and returns a Resource containing the HTTP response.
    *
    * @return                             A stream of connection streams, each emitting ServerResult. Use .parJoin(maxConcurrency) to control parallelism.
    */
  def create[F[_]
  : Async
  : Network
  ](
    bindTo: Option[SocketAddress[Host]] = None
    , maxHeaderSize: Int = 10 *1024
    , requestHeaderReceiveTimeout: Duration = 5.seconds
    , requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
    , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
  )(
    service: (HttpRequestHeader, Stream[F,Byte]) => Resource[F,HttpResponse[F]]
  ): Stream[F, Stream[F, RequestResult[F]]] = {
    Network[F].server(bindTo.map(_.host), bindTo.map(_.port))
    .map(handleConnection(maxHeaderSize, requestHeaderReceiveTimeout, requestCodec, responseCodec, service))
  }

  /**
    * Handles a single client connection by processing HTTP requests and generating RequestResults.
    *
    * This method processes the complete lifecycle of HTTP requests on a socket connection:
    * - Reads and parses HTTP request headers with timeout
    * - Invokes the service function with parsed request and body stream
    * - Streams the response back to the client
    * - Generates appropriate RequestResult for success, service failure, or response failure cases
    * - Ensures client receives proper HTTP responses even when service fails
    *
    * @param socket                      The client socket connection
    * @param maxHeaderSize               Maximum size allowed for HTTP request headers
    * @param requestHeaderReceiveTimeout Timeout for receiving complete request headers
    * @param requestCodec                Codec for decoding HTTP request headers
    * @param responseCodec               Codec for encoding HTTP response headers  
    * @param service                     Function to process requests and generate responses
    * @return                            Stream of RequestResult capturing the outcome of each request
    */
  def handleConnection[F[_]: Async](
    maxHeaderSize: Int
    , requestHeaderReceiveTimeout: Duration
    , requestCodec: Codec[HttpRequestHeader]
    , responseCodec: Codec[HttpResponseHeader]
    , service: (HttpRequestHeader, Stream[F,Byte]) => Resource[F,HttpResponse[F]]
  )(socket: Socket[F]): Stream[F, RequestResult[F]] = {
    def mkSocketStream(socket: Socket[F]): Stream[F, (HttpRequestHeader, Stream[F, Byte])] = {
      val socketStream = socket.reads.through(HttpRequest.fromStream(maxHeaderSize, requestCodec))
      requestHeaderReceiveTimeout match {
        case fin: FiniteDuration => socketStream.timeout(fin)
        case _ => socketStream
      }
    }

    Stream.eval(socket.remoteAddress).flatMap { remoteAddr =>
    Stream.eval(socket.localAddress).flatMap { localAddr =>
      mkSocketStream(socket)
        .attempt
        .evalMap[F, RequestResult[F]] {
          case Left(requestError) =>
            // Request parsing or timeout error
            Applicative[F].pure(RequestResult.InvalidRequest[F](requestError, remoteAddr, localAddr): RequestResult[F])

          case Right((request, body)) =>
            def sendResponse(resp: HttpResponse[F]): F[Either[Throwable, Unit]] = {
              HttpResponse.toStream(resp, responseCodec)
                .through(socket.writes)
                .onFinalize(socket.endOfOutput)
                .compile.drain.attempt
            }

            // Process successful request and emit Success result
            service(request, body).attempt.use {
              case Right(resp) =>
                // Service produced a response, try to stream it
                // Try to stream the response and distinguish service vs response errors
                sendResponse(resp).map[RequestResult[F]] {
                  case Right(_) => RequestResult.Success[F](request, resp.header, remoteAddr, localAddr)
                  case Left(err) => RequestResult.ResponseFailed[F](request, resp.header, err, remoteAddr, localAddr)
                }

              case Left(err) =>
                // service failed producing a response
                // we still respond with internal server erro so the client is not left hanging
                val resp = HttpResponse[F](HttpStatusCode.InternalServerError)
                sendResponse(resp).map[RequestResult[F]] {
                  case Right(_) =>
                    RequestResult.ServiceFailed[F](request, err, remoteAddr, localAddr)
                  case Left(err) =>
                    RequestResult.ResponseFailed[F](request, resp.header, err, remoteAddr, localAddr)
                }
            }
        }
      }}
  }


}
