package spinoco.fs2.http.routing


import fs2._



/**
  * Created by pach on 26/01/17.
  */
trait HttpService[F[_]] {



//  def decodeAs[A](implicit decoder: StringDecoder[A]): RequestMatcher[F,A] =
//    RequestMatcher { (request, segments) =>
//      segments.headOption.flatMap(decoder.decode) match {
//        case None => RequestMatchResult.NotFoundResponse
//        case Some(a) => RequestMatchResult.Matched(a, segments.tail)
//      }
//    }


}


object HttpService {


  val route : Route[Task] = {
    "hello" / choice(
      "dolly" / decodeAs[String] :/: decodeAs[Int] :/: decodeAs[Double] :: path :: Get :: body[Task].bytes
      , "molly" / "goosh"
    ) map { x => println(x); Stream.empty }
  }

}