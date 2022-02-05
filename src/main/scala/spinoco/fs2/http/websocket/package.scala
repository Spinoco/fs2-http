package spinoco.fs2.http

import cats.effect.kernel.Temporal
import fs2._
import scodec.{Decoder, Encoder}
import spinoco.protocol.http.HttpRequestHeader

import scala.concurrent.duration._


package object websocket {

  /**
    * Creates a websocket to be used on server side.
    *
    * Implementation is according to RFC-6455 (https://tools.ietf.org/html/rfc6455).
    *
    * @param pipe             A websocket pipe. `I` is received from the client and `O` is sent to client.
    *                         Decoder (for I) and Encoder (for O) must be supplied.
    *                         Note that this function may evaluate on the left, to indicate response to the client before
    *                         the handshake took place (i.e. Unauthorized).
    * @param pingInterval     An interval for the Ping / Pong protocol.
    * @param handshakeTimeout An timeout to await for handshake to be successfull. If the handshake is not completed
    *                         within supplied period, connection is terminated.
    * @tparam F
    * @return
    */
  def server[F[_] : Temporal, I : Decoder, O : Encoder](
    pipe: Pipe[F, Frame[I], Frame[O]]
    , pingInterval: Duration = 30.seconds
    , handshakeTimeout: FiniteDuration = 10.seconds
  )(header: HttpRequestHeader, input: Stream[F,Byte]): Stream[F, HttpResponse[F]] =
    WebSocket.server(pipe, pingInterval, handshakeTimeout)(header, input)

}
