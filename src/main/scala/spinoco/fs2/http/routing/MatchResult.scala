package spinoco.fs2.http.routing


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

  def covary[F0[_] >: F[_]]: MatchResult[F0, A] = self.asInstanceOf[MatchResult[F0, A]]

}

object MatchResult {

  implicit class MatchResultInvariantSyntax[F[_], A](val self: MatchResult[F,A]) extends AnyVal {
    def fold[B](fa: HttpResponse[F] => B, fb: A => B):B = self match {
      case Success(a) => fb(a)
      case Failed(resp) => fa(resp.asInstanceOf[HttpResponse[F]])
    }
  }

  case class Success[A](result: A) extends MatchResult[Nothing, A]

  case class Failed[F[_]](response: HttpResponse[F]) extends MatchResult[F, Nothing]

  def success[A](a: A) : MatchResult[Nothing, A] = Success(a)

  def reply(code: HttpStatusCode):MatchResult[Nothing,Nothing] =
    Failed[Nothing](HttpResponse[Nothing](code))

  val NotFoundResponse: MatchResult[Nothing,Nothing] = reply(HttpStatusCode.NotFound)

  val MethodNotAllowed: MatchResult[Nothing, Nothing] = reply(HttpStatusCode.MethodNotAllowed)

  val BadRequest: MatchResult[Nothing, Nothing] = reply(HttpStatusCode.BadRequest)

}
