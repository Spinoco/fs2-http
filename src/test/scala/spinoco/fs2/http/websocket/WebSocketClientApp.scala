package spinoco.fs2.http.websocket


import cats.effect.IO

import scala.concurrent.duration._
import fs2._
import scodec.Codec
import scodec.codecs._
import spinoco.protocol.http.Uri.QueryParameter


object WebSocketClientApp extends App {

  import spinoco.fs2.http.Resources._


  def wspipe: Pipe[IO, Frame[String], Frame[String]] = { inbound =>
    val output =  Stream.awakeEvery[IO](1.second).map { dur => println(s"SENT $dur"); Frame.Text(s" ECHO $dur") }.take(5)
    output concurrently inbound.take(5).map { in => println(("RECEIVED ", in)) }
  }

  implicit val codecString: Codec[String] = utf8

  Stream.resource(httpResources).flatMap { case (group, tls) =>
    WebSocket.client(
      WebSocketRequest.ws("echo.websocket.org", "/", QueryParameter.single("encoding", "text"))
      , wspipe
    )(group, tls).map { x =>
      println(("RESULT OF WS", x))
    }
  }.compile.drain.unsafeRunSync()

}
