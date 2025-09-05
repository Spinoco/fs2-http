package spinoco.fs2.http.internal

import cats.effect.IO
import spinoco.fs2.http.{HttpClient, HttpRequest}
import spinoco.protocol.http.Uri


object HttpClientApp extends App {

  import spinoco.fs2.http.Resources._

  HttpClient.create[IO]().flatMap { httpClient =>

    httpClient.request(HttpRequest.get(Uri.https("www.google.cz", "/"))).use { resp =>
      resp.bodyAsString.map(println)
    }

  }.unsafeRunSync()
}
