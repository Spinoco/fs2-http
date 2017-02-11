package spinoco.fs2.http.routing

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

  implicit class RouteTaskInstance(val self: Route[Task]) {
    def matches(request: HttpRequestHeader, body: Stream[Task, Byte] = Stream.empty): MatchResult[Task, Stream[Task, HttpResponse[Task]]] =
      Matcher.run(self)(request, body).unsafeRun()
  }

  val RespondOk: Stream[Task, HttpResponse[Task]] = Stream.emit(HttpResponse(HttpStatusCode.Ok))

  property("matches-uri") = secure {

    val r: Route[Task] = "hello" / "world" map { _ => RespondOk }

    (r matches requestAt("/hello/world") isSuccess) &&
    (r matches requestAt("/hello/world2") isFailure)

  }


  property("matches-uri-alternative") = secure {
    val r: Route[Task] = "hello" / choice(
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
    val r: Route[Task] = (1 until 10000).foldLeft[Matcher[Task, String]]("deep" / "0") {
      case (m, next) => m / next.toString
    } map { _ => RespondOk }

    val path = (1 until 10000).mkString("/deep/0/", "/", "")

    r matches requestAt(path) isSuccess
  }


  property("matcher-hlist")  = secure {
    val r: Route[Task] = "hello" :/: "body" :/: as[Int] :/: "foo" :/: as[Long] map { _ => RespondOk }

    r matches(requestAt("/hello/body/33/foo/22")) isSuccess

  }



}
