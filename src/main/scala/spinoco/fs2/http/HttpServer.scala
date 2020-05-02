package spinoco.fs2.http

import java.net.InetSocketAddress

import cats.effect.{ConcurrentEffect, ContextShift, Sync, Timer}
import cats.syntax.all._
import fs2._
import fs2.concurrent.SignallingRef
import fs2.io.tcp.SocketGroup
import scodec.Codec
import spinoco.protocol.http.codec.{HttpRequestHeaderCodec, HttpResponseHeaderCodec}
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader, HttpStatusCode}

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
    * @param requestFailure               A function to be evaluated when server failed to read the request header.
    *                                     This may generate the default server response on unexpected failure.
    *                                     This is also evaluated when the server failed to process the request itself (i.e. `service` did not handle the failure )
    * @param sendFailure                  A function to be evaluated on failure to process the the response.
    *                                     Request is not suplied if failure happened before request was constructed.
    *
    */
  def apply[F[_] : ConcurrentEffect : Timer: ContextShift](
    maxConcurrent: Int = Int.MaxValue
    , receiveBufferSize: Int = 256 * 1024
    , maxHeaderSize: Int = 10 *1024
    , requestHeaderReceiveTimeout: Duration = 5.seconds
    , requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
    , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
    , bindTo: InetSocketAddress
    , service:  (HttpRequestHeader, Stream[F,Byte]) => Stream[F,HttpResponse[F]]
    , requestFailure : Throwable => Stream[F, HttpResponse[F]]
    , sendFailure: (Option[HttpRequestHeader], HttpResponse[F], Throwable) => Stream[F, Nothing]
  )(
    socketGroup: SocketGroup
  ): Stream[F, Unit] = {
    import Stream._
    import spinoco.fs2.http.internal._
    val (initial, readDuration) = requestHeaderReceiveTimeout match {
      case fin: FiniteDuration => (true, fin)
      case _ => (false, 0.millis)
    }

    socketGroup.server[F](bindTo, receiveBufferSize = receiveBufferSize).map { resource =>
      Stream.resource(resource).flatMap { socket =>
      eval(SignallingRef[F, Boolean](initial)).flatMap { timeoutSignal =>
        readWithTimeout[F](socket, readDuration, timeoutSignal.get, receiveBufferSize)
        .through(HttpRequest.fromStream(maxHeaderSize, requestCodec))
        .flatMap { case (request, body) =>
          eval_(timeoutSignal.set(false)) ++
          service(request, body).take(1).handleErrorWith { rsn => requestFailure(rsn).take(1) }
          .map { resp => (request, resp) }
        }
        .attempt
        .evalMap { attempt =>

          def send(request:Option[HttpRequestHeader], resp: HttpResponse[F]): F[Unit] = {
            HttpResponse.toStream(resp, responseCodec).through(socket.writes()).onFinalize(socket.endOfOutput).compile.drain.attempt flatMap {
              case Left(err) => sendFailure(request, resp, err).compile.drain
              case Right(()) => Sync[F].pure(())
            }
          }

          attempt match {
            case Right((request, response)) => send(Some(request), response)
            case Left(err) => requestFailure(err).evalMap { send(None, _) }.compile.drain
          }
        }
        .drain
      }
    }}.parJoin(maxConcurrent)


  }

  /** default handler for parsing request errors **/
  def handleRequestParseError[F[_] : RaiseThrowable](err: Throwable): Stream[F, HttpResponse[F]] = {
    Stream.suspend {
      err.printStackTrace()
      Stream.emit(HttpResponse[F](HttpStatusCode.BadRequest))
    }.covary[F]
  }

  /** default handler for failures of sending request/response **/
  def handleSendFailure[F[_]](header: Option[HttpRequestHeader], response: HttpResponse[F], err:Throwable): Stream[F, Nothing] = {
    Stream.suspend {
      err.printStackTrace()
      Stream.empty
    }
  }

}
