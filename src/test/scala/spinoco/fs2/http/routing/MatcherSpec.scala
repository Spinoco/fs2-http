package spinoco.fs2.http.routing

import cats.effect.IO
import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import spinoco.fs2.http.HttpResponse
import spinoco.protocol.http.{HttpMethod, HttpRequestHeader, HttpStatusCode, Uri}

object MatcherSpec extends Properties("Matcher"){

  val request = HttpRequestHeader(
    method = HttpMethod.GET
  )

  def requestAt(s: String): HttpRequestHeader =
    request.copy(path = Uri.Path.fromUtf8String(s))

  implicit class RouteTaskInstance(val self: Route[IO]) {
    def matches(request: HttpRequestHeader, body: Stream[IO, Byte] = Stream.empty.covaryAll[IO, Byte]): MatchResult[IO, Stream[IO, HttpResponse[IO]]] =
      Matcher.run(self)(request, body).unsafeRunSync()
  }

  val RespondOk: Stream[IO, HttpResponse[IO]] = Stream.emit(HttpResponse[IO](HttpStatusCode.Ok)).covary[IO]

  property("matches-uri") = secure {

    val r: Route[IO] = "hello" / "world" map { _ => RespondOk }

    (r matches requestAt("/hello/world") isSuccess) &&
    (r matches requestAt("/hello/world2") isFailure)

  }


  property("matches-uri-alternative") = secure {
    val r: Route[IO] = "hello" / choice(
      "world"
      , "nation" / ( "greeks" or "romans" )
      , "city" / "of" / "prague"
    ) map { _ => RespondOk }

    (r matches requestAt("/hello/world") isSuccess) &&
    (r matches requestAt("/hello/nation/greeks") isSuccess) &&
    (r matches requestAt("/hello/nation/romans") isSuccess) &&
    (r matches requestAt("/hello/city/of/prague") isSuccess) &&
    (r matches requestAt("/bye") isFailure) &&
    (r matches requestAt("/hello/town") isFailure) &&
    (r matches requestAt("/hello/nation/egyptians") isFailure) &&
    (r matches requestAt("/hello/city/of/berlin") isFailure)
  }


  property("matches-deep-uri") = secure {
    val r: Route[IO] = (1 until 10000).foldLeft[Matcher[IO, String]]("deep" / "0") {
      case (m, next) => m / next.toString
    } map { _ => RespondOk }

    val path = (1 until 10000).mkString("/deep/0/", "/", "")

    r matches requestAt(path) isSuccess
  }


  property("matcher-hlist")  = secure {
    val r: Route[IO] = "hello" :/: "body" :/: as[Int] :/: "foo" :/: as[Long] map { _ => RespondOk }

    r matches(requestAt("/hello/body/33/foo/22")) isSuccess

  }


  property("matcher-advance-recover") = secure {
    val r: Route[IO] = choice(
      "hello" / choice (  "user" / "one" ) map { _ => RespondOk }
      , "hello" / "people" map { _ => RespondOk }
    )

    (r matches(requestAt("/hello/people")) isSuccess)
  }


  property("matches-uri-alternative") = secure {
    val r: Route[IO] = "hello" / choice(
      "world"
      , "nation" / ( "greeks" or "romans" )
      , "city" / "of" / "prague"
    ) map { _ => RespondOk }

    (r matches requestAt("/hello/world") isSuccess) &&
    (r matches requestAt("/hello/nation/greeks") isSuccess) &&
    (r matches requestAt("/hello/nation/romans") isSuccess) &&
    (r matches requestAt("/hello/city/of/prague") isSuccess) &&
    (r matches requestAt("/bye") isFailure) &&
    (r matches requestAt("/hello/town") isFailure) &&
    (r matches requestAt("/hello/nation/egyptians") isFailure) &&
    (r matches requestAt("/hello/city/of/berlin") isFailure)
  }


  property("matches-deep-uri") = secure {
    val r: Route[IO] = (1 until 10000).foldLeft[Matcher[IO, String]]("deep" / "0") {
      case (m, next) => m / next.toString
    } map { _ => RespondOk }

    val path = (1 until 10000).mkString("/deep/0/", "/", "")

    r matches requestAt(path) isSuccess
  }


  property("matcher-hlist")  = secure {
    val r: Route[IO] = "hello" :/: "body" :/: as[Int] :/: "foo" :/: as[Long] map { _ => RespondOk }

    r matches(requestAt("/hello/body/33/foo/22")) isSuccess

  }



}
