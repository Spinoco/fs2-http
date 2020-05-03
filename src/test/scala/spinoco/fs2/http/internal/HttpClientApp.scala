package spinoco.fs2.http.internal

import cats.effect.IO
import fs2._
import spinoco.fs2.http
import spinoco.fs2.http.HttpRequest
import spinoco.protocol.http.Uri


object HttpClientApp extends App {

  import spinoco.fs2.http.Resources._



  httpResources.use { case (group, tls) =>
  http.client[IO]()(group, tls).flatMap { httpClient =>

    httpClient.request(HttpRequest.get(Uri.https("www.google.cz", "/"))).flatMap { resp =>
      Stream.eval(resp.bodyAsString)
    }.compile.toVector.map {
      println
    }

  }}.unsafeRunSync()
}
