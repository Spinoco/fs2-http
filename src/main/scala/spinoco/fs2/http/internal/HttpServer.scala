package spinoco.fs2.http.internal

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import fs2._
import fs2.util.Async
import scodec.Codec
import spinoco.fs2.http.{HttpRequest, HttpResponse, internal}
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
    * @param requestFailure               A function to be evaluated when request failed while receiving the header
    * @param sendFailure                  A function to be evaluated, when response has been sending result to client.
    */
  def apply[F[_]](
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
    implicit
    AG: AsynchronousChannelGroup
    , F: Async[F]
  ):Stream[F,Unit] = {
    import Stream._
    import internal._
    val (initial, readDuration) = requestHeaderReceiveTimeout match {
      case fin: FiniteDuration => (true, fin)
      case _ => (false, 0.millis)
    }

    concurrent.join(maxConcurrent)(
      io.tcp.server(bindTo, receiveBufferSize = receiveBufferSize).map { _.flatMap { socket =>
        eval(async.signalOf(initial)).flatMap { timeoutSignal =>
          readWithTimeout[F](socket, readDuration, timeoutSignal.get, receiveBufferSize)
          .through(HttpRequest.fromStream(maxHeaderSize, requestCodec))
          .flatMap { read => eval_(timeoutSignal.set(false)) ++ emit(read) }
          .attempt.flatMap { attempt =>
            def send(request:Option[HttpRequestHeader]): Pipe[F, HttpResponse[F], Unit] = {
              _.flatMap { resp =>
                HttpResponse.toStream(resp, responseCodec).through(socket.writes())
                  .attempt.flatMap {
                  case Left(err) => sendFailure(request, resp, err)
                  case _ => Stream.empty
                }
              }
            }

            attempt match {
              case Left(err) => requestFailure(err) through send(None)
              case Right((request, body)) => service(request, body).take(1) through send(Some(request))
            }
          }
          .drain
        }
      }}
    )

  }

  /** default handler for parsing request errors **/
  def handleRequestParseError[F[_]](err: Throwable): Stream[F, HttpResponse[F]] = {
    Stream.suspend {
      err.printStackTrace()
      Stream.emit(HttpResponse(HttpStatusCode.BadRequest))
    }
  }

  /** default handler for failures of sending request/response **/
  def handleSendFailure[F[_]](header: Option[HttpRequestHeader], response: HttpResponse[F], err:Throwable): Stream[F, Nothing] = {
    Stream.suspend {
      err.printStackTrace()
      Stream.empty
    }
  }

}
