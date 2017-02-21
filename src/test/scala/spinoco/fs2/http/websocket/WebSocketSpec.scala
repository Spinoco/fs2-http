package spinoco.fs2.http.websocket

import java.net.InetSocketAddress

import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._
import spinoco.fs2.http

import scala.concurrent.duration._

object WebSocketSpec extends Properties("WebSocket") {
  import spinoco.fs2.http.Resources._

  property("computes-fingerprint") = secure {
    val key = ByteVector.fromBase64("L54CF9+DxAZSOHDW3AoG1A==").get
    val fp = WebSocket.impl.computeFingerPrint(key)

    fp ?= ByteVector.fromBase64("rsNEx/DEOf5YTl9Jd/jPWeKlKbk=").get
  }

  property("websocket-server") = secure {
    implicit val codecString: Codec[String] = utf8

    var received:List[Frame[String]] = Nil

    def serverEcho: Pipe[Task, Frame[String], Frame[String]] = { in => in }

    def clientData: Pipe[Task, Frame[String], Frame[String]] = { inbound =>
      val output =  time.awakeEvery[Task](1.seconds).map { dur => Frame.Text(s" ECHO $dur") }.take(5)
      inbound.take(5).map { in => received = received :+ in }
      .mergeDrainL(output)
    }

    val serverStream =
      http.server[Task](new InetSocketAddress("127.0.0.1", 9090))(
        server (
          { header => Right(serverEcho) }
          , pingInterval = 500.millis
          , handshakeTimeout = 10.seconds
        )
      )

    val clientStream =
      time.sleep_[Task](3.seconds) ++
      WebSocket.client(
        WebSocketRequest.ws("127.0.0.1", 9090, "/")
        , clientData
      )

    val resultClient =
      (serverStream.drain mergeHaltBoth clientStream).runLog.unsafeTimed(20.seconds).unsafeRun()

    (resultClient ?= Vector(None)) &&
      (received.size ?= 5)

  }


}
