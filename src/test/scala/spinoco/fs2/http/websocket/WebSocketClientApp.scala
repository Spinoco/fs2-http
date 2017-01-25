package spinoco.fs2.http.websocket


import scala.concurrent.duration._

import fs2._
import scodec.Codec
import scodec.codecs._


object WebSocketClientApp extends App {

  import spinoco.fs2.http.Resources._


  def wspipe: Pipe[Task, Frame[String], Frame[String]] = { inbound =>
    val output =  time.awakeEvery[Task](1.second).map { dur => println(s"SENT $dur"); Frame.Text(s" ECHO $dur") }.take(5)
    inbound.take(5).map { in => println(("RECEIVED ", in)) }
    .mergeDrainL(output)
  }

  implicit val codecString: Codec[String] = utf8

  WebSocket.client(
    WebSocketRequest.ws("echo.websocket.org", "/", "encoding" -> "text")
    , wspipe
  ).map { x =>
    println(("RESULT OF WS", x))
  }.run.unsafeRun()

}
