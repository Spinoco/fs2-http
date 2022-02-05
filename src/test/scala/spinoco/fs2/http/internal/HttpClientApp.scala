package spinoco.fs2.http.internal

import cats.effect.IO
import fs2._
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import spinoco.fs2.http
import spinoco.fs2.http.HttpRequest
import spinoco.protocol.http.Uri


object HttpClientApp extends App {

  import cats.effect.unsafe.implicits.global
  implicit val network: Network[IO] = Network.forAsync[IO]


  TLSContext.Builder.forAsync[IO].system.flatMap { tls =>
  http.client[IO]()(tls).flatMap { httpClient =>

    httpClient.request(HttpRequest.get(Uri.https("www.google.cz", "/"))).flatMap { resp =>
      Stream.eval(resp.bodyAsString)
    }.compile.toVector.map {
      println
    }

  }}.unsafeRunSync()
}
