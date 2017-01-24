package spinoco.fs2.http.client

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import fs2._
import spinoco.fs2.http.HttpRequest
import spinoco.protocol.http.Uri


object HttpClientApp extends App {

  val ES = Executors.newCachedThreadPool(Strategy.daemonThreadFactory("AG"))

  implicit val S = Strategy.fromExecutor(ES)

  implicit val AG = AsynchronousChannelGroup.withThreadPool(ES)

  apply[Task]().flatMap { httpClient =>

    httpClient.request(HttpRequest.get(Uri.http("www.spinoco.com", "/"))).flatMap { resp =>
      Stream.eval(resp.bodyAsString)
    }.runLog.map {
      println
    }

  }.unsafeRun()
}
