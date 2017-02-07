package spinoco.fs2.http.routing

import fs2._
import fs2.util._
import shapeless.ops.function.FnToProduct
import shapeless.ops.hlist.Prepend
import shapeless.{::, HList, HNil}
import spinoco.fs2.http.HttpResponse
import spinoco.fs2.http.routing.MatchResult.Success
import spinoco.protocol.http.{HttpRequestHeader, HttpStatusCode, Uri}


sealed trait Matcher[+F[_], +A] { self =>
  import MatchResult._
  import Matcher._


  /** transforms this matcher with supplied `f` **/
  def map[B](f: A => B): Matcher[F, B] =
    Bind[F, A, B](self, r => Matcher.ofResult(r.map(f)) )

  /** defined ad map { _ => b} **/
  def *>[B](b: B): Matcher[F, B] =
    self.map { _ => b }


  /** like `map` but allows to evaluate `F` **/
  def evalMap[F0[_],Lub[_], B](f: A => F0[B])(implicit L: Lub1[F,F0,Lub]): Matcher[Lub, B] =
    self.flatMap { a => Eval(f(a)) }


  /** transforms this matcher to another matcher with supplied `f` **/
  def flatMap[F0[_],Lub[_], B](f: A => Matcher[F0, B])(implicit L: Lub1[F,F0,Lub]):  Matcher[Lub, B]  =
    Bind[Lub, A, B](self.asInstanceOf[Matcher[Lub, A]], {
      case success:Success[A]  => f(success.result).asInstanceOf[Matcher[Lub, B]]
      case failed:Failed[Lub] => Matcher.respond[Lub](failed.response)
    })

  /** allias for flatMap **/
  def >>=[F0[_],Lub[_], B](f: A => Matcher[F0, B])(implicit L: Lub1[F,F0,Lub]):  Matcher[Lub, B]  =
    flatMap(f)

  /** defined as flatMap { _ => fb } **/
  def >>[F0[_],Lub[_], B](fb: Matcher[F0, B])(implicit L: Lub1[F,F0,Lub]):  Matcher[Lub, B]  =
    flatMap(_ => fb)

  /** defined as flatMap { a => fb map { _ => a} } **/
  def <<[F0[_],Lub[_], B](fb: Matcher[F0, B])(implicit L: Lub1[F,F0,Lub]):  Matcher[Lub, A]  =
    flatMap(a => fb map { _ => a})

  /** defined as advance.flatMap(f) **/
  def />>=[F0[_],Lub[_], B](f: A => Matcher[F0, B])(implicit L: Lub1[F,F0,Lub]):  Matcher[Lub, B]  =
    self.advance.flatMap(f)

  /** advances path by one segment, after this matches **/
  def advance: Matcher[F, A] =
    Advance(self)


  /** like flatMap, but allows to apply `f` when match failed **/
  def flatMapR[F0[_],Lub[_], B](f: MatchResult[Lub,A] => Matcher[F0, B])(implicit L: Lub1[F,F0,Lub]):  Matcher[Lub, B]  =
    Bind[Lub, A, B](self.asInstanceOf[Matcher[Lub, A]], f andThen (_.asInstanceOf[Matcher[Lub,B]]))

  /** applies `f` only when matcher fails to match **/
  def recover[F0[_], Lub[_], A0 >: A](f: HttpResponse[Lub] => Matcher[F0, A0])(implicit R: RealSupertype[A, A0], L: Lub1[F,F0,Lub]):  Matcher[Lub, A0] =
    Bind[Lub, A, A0](self.asInstanceOf[Matcher[Lub, A]], {
      case success: Success[A]  => Matcher.success(success.result.asInstanceOf[A0])
      case failed: Failed[Lub] =>  f(failed.response).asInstanceOf[Matcher[Lub, A0]]
    })

  /** matches and consumes current path segment throwing away `A` **/
  def / [F0[_],Lub[_], B](other : Matcher[F0, B])(implicit L: Lub1[F,F0,Lub]): Matcher[Lub, B] =
    self.advance.flatMap { _ => other }

  /** matches and consumes current path segment throwing away `B` **/
  def </[F0[_],Lub[_], B](other:  Matcher[F0, B])(implicit L: Lub1[F,F0,Lub]):  Matcher[Lub, A] =
    self.advance.flatMap { a => other.map { _ => a } }

  /** matches this or alternative **/
  def or[F0[_],Lub[_], A0 >: A](alt : => Matcher[F0, A0])(implicit R: RealSupertype[A, A0], L: Lub1[F,F0,Lub]): Matcher[Lub, A0] =
    Bind[Lub, A, A0](self.asInstanceOf[Matcher[Lub, A]], {
      case success: Success[A] => Matcher.ofResult(success)
      case failed: Failed[Lub] => alt.asInstanceOf[Matcher[Lub, A0]]
    })

  /** matches this or yields to None **/
  def ? : Matcher[F, Option[A]] =
    self.map(Some(_)) or Matcher.success(None: Option[A])


}


object Matcher {

  case class Match[F[_], A](f:(HttpRequestHeader, Stream[F, Byte]) => MatchResult[F, A]) extends Matcher[F, A]
  case class Bind[F[_], A, B](m: Matcher[F, A], f: MatchResult[F,A] => Matcher[F, B]) extends Matcher[F, B]
  case class Advance[F[_], A](m: Matcher[F, A]) extends Matcher[F, A]
  case class Eval[F[_], A](f: F[A]) extends Matcher[F, A]



  def success[A](a: A): Matcher[Nothing, A] =
    Match[Nothing,A] { (_,_) => MatchResult.Success[A](a) }

  def respond[F[_]](response: HttpResponse[F]): Matcher[F, Nothing] =
    Match[F, Nothing] { (_, _) => MatchResult.Failed[F](response) }

  def respondWith(code: HttpStatusCode): Matcher[Nothing, Nothing] =
    respond(HttpResponse(code))

  def ofResult[F[_], A](result:MatchResult[F,A]): Matcher[F, A] =
    Match[F, A] { (_, _) => result }

  /**
    * Interprets matcher to obtain the result.
    */
  def run[F[_], A](matcher: Matcher[F, A])(header: HttpRequestHeader, body: Stream[F, Byte])(implicit F: Suspendable[F]): F[MatchResult[F, A]] = {
    def go[B](current:Matcher[F,B], path: Uri.Path):F[(MatchResult[F, B], Uri.Path)] = {
      current match {
        case m: Match[F,B] => F.map(F.pure(m.f(header.copy(path = path), body))) { _ -> path }
        case m: Eval[F, B] => F.map(m.f)(b => Success(b) -> path)
        case m: Bind[F, _, B] => F.flatMap(F.suspend(go(m.m, path))){ case (r, path0) => go(m.f(r), path0) }
        case m: Advance[F, B] => F.map(F.suspend(go(m.m, path))){ case (r, path0) =>
          if (r.isSuccess) r -> path0.copy(segments = if (path0.segments.nonEmpty) path0.segments.tail else Nil)
          else r -> path0
        }
      }
    }
    F.map(go(matcher, header.path)) { _._1 }
  }


  implicit class RequestMatcherHListSyntax[F[_], L <: HList](val self: Matcher[F, L]) extends AnyVal {
    /** combines two matcher'r result to resulting hlist **/
    def ::[B](other: Matcher[F, B]): Matcher[F, B :: L] =
      self.flatMap { l => other.map { b => b :: l } }

    /** combines this matcher with other matcher appending result of other matcher at the end **/
    def :+[B](other: Matcher[F, B])(implicit P : Prepend[L, B :: HNil]): Matcher[F, P.Out] =
      self.flatMap { l => other.map { b => l :+ b } }

    /** prepends result of other matcher before the result of this matcher **/
    def :::[L2 <: HList, HL <: HList](other: Matcher[F, L2])(implicit P: Prepend.Aux[L2, L, HL]): Matcher[F, HL] =
      self.flatMap { l => other.map { l2 => l2 ::: l } }

    /** combines two matcher'r result to resulting hlist, and advances path between them  **/
    def :/:[B](other : Matcher[F, B]): Matcher[F, B :: L] =
      self.advance.flatMap { l => other.map { b => b :: l } }

    /** like `map` but instead (L:HList) => B, takes ordinary function **/
    def mapH[FF, B](f: FF)(implicit F2P: FnToProduct.Aux[FF, L => B]): Matcher[F, B] =
      self.map { l => F2P(f)(l) }


  }


  implicit class RequestMatcherSyntax[F[_], A](val self: Matcher[F, A]) extends AnyVal {
    /** applies this matcher and if it is is successful then applies `other` returning result in HList B :: A :: HNil */
    def :: [B](other : Matcher[F, B]): Matcher[F, B :: A :: HNil] =
      self.flatMap { a => other.map { b => b :: a :: HNil } }

    def :/:[B](other : Matcher[F, B]): Matcher[F, B :: A :: HNil] =
      self.advance.flatMap { a => other.map { b => b :: a :: HNil } }



  }




}
