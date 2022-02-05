package spinoco.fs2.http.websocket


import cats.effect.IO

import scala.concurrent.duration._
import fs2._
import scodec.Codec
import scodec.codecs._
import spinoco.protocol.http.Uri.QueryParameter
import cats.effect.unsafe.implicits.global
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext


object WebSocketClientApp extends App {



  def wspipe: Pipe[IO, Frame[String], Frame[String]] = { inbound =>
    val output =  Stream.awakeEvery[IO](1.second).map { dur => println(s"SENT $dur"); Frame.Text(s" ECHO $dur") }.take(5)
    output concurrently inbound.take(5).map { in => println(("RECEIVED ", in)) }
  }

  implicit val codecString: Codec[String] = utf8
  implicit val network: Network[IO] = Network.forAsync[IO]


  Stream.eval(TLSContext.Builder.forAsync[IO].system).flatMap { tls =>
    WebSocket.client(
      WebSocketRequest.ws("echo.websocket.org", "/", QueryParameter.single("encoding", "text"))
      , wspipe
    )(tls).map { x =>
      println(("RESULT OF WS", x))
    }
  }.compile.drain.unsafeRunSync()

}
