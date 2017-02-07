package spinoco.fs2.http.routing

import fs2.util.Lub1
import spinoco.fs2.http.HttpResponse
import spinoco.protocol.http.HttpStatusCode


trait MatchResult[+F[_],+A] { self =>
  import MatchResult._

  def map[B](f: A => B): MatchResult[F, B] = self match {
    case Success(a) => Success(f(a))
    case fail@Failed(_) => fail
  }

  def isSuccess: Boolean = self match {
    case Success(_) => true
    case _ => false
  }

  def isFailure: Boolean = ! isSuccess

  def fold[Lub[_], F0[_], B](fa: HttpResponse[F0] => B, fb: A => B)(implicit L: Lub1[F,F0,Lub]):B = self match {
    case Success(a) => fb(a)
    case Failed(resp) => fa(resp.asInstanceOf[HttpResponse[F0]])
  }

}

object MatchResult {

  case class Success[A](result: A) extends MatchResult[Nothing, A]

  case class Failed[F[_]](response: HttpResponse[F]) extends MatchResult[F, Nothing]

  def success[A](a: A) : MatchResult[Nothing, A] = Success(a)

  def reply(code: HttpStatusCode):MatchResult[Nothing,Nothing] =
    Failed[Nothing](HttpResponse[Nothing](code))

  val NotFoundResponse: MatchResult[Nothing,Nothing] = reply(HttpStatusCode.NotFound)

  val MethodNotAllowed: MatchResult[Nothing, Nothing] = reply(HttpStatusCode.MethodNotAllowed)

  val BadRequest: MatchResult[Nothing, Nothing] = reply(HttpStatusCode.BadRequest)

}
