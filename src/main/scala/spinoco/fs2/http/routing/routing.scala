package spinoco.fs2.http

import cats.effect.{Concurrent, Effect, Timer}
import fs2._
import scodec.{Attempt, Decoder, Encoder}
import scodec.bits.Bases.Base64Alphabet
import scodec.bits.{Bases, ByteVector}
import shapeless.Typeable

import spinoco.fs2.http.body.{BodyDecoder, StreamBodyDecoder}
import spinoco.fs2.http.routing.MatchResult._
import spinoco.fs2.http.routing.Matcher.{Eval, Match}
import spinoco.protocol.http.header._
import spinoco.protocol.http.{HttpMethod, HttpRequestHeader, HttpStatusCode, Uri}
import spinoco.fs2.http.util.chunk2ByteVector
import spinoco.fs2.http.websocket.{Frame, WebSocket}
import scala.concurrent.duration._



package object routing {
  type Route[F[_]] = Matcher[F, Stream[F, HttpResponse[F]]]

  /** tags bytes encoded as Base64Url **/
  sealed trait Base64Url


  /** converts supplied route to function that is handled over to server to perform the routing **/
  def route[F[_]](r:Route[F])(implicit F: Effect[F]):(HttpRequestHeader, Stream[F, Byte]) => Stream[F, HttpResponse[F]] = {
    (header, body) =>
      Stream.eval(Matcher.run[F, Stream[F, HttpResponse[F]]](r)(header, body)).flatMap { mr =>
        mr.fold((resp : HttpResponse[F]) => Stream.emit(resp), identity )
      }
  }

  implicit class StringMatcherSyntax(val self: String) extends AnyVal {
    def /[F[_], A] (m: Matcher[F, A]) : Matcher[F, A] =
    string2RequestMatcher(self) / m

    def or[F[_]] (m: Matcher[F, String]) : Matcher[F, String] =
      string2RequestMatcher(self) or m
  }

  implicit def string2RequestMatcher(s:String): Matcher[Nothing, String] =
    as(StringDecoder.stringInstance.filter(_ == s))


  /** matches supplied method **/
  def method[F[_]](method: HttpMethod.Value): Matcher[F, HttpMethod.Value] =
    Match[F, HttpMethod.Value] { (rq, body) =>
      if (rq.method == method) MatchResult.Success(method)
      else MatchResult.MethodNotAllowed
    }

  val Get = method(HttpMethod.GET)
  val Put = method(HttpMethod.PUT)
  val Post = method(HttpMethod.POST)
  val Delete = method(HttpMethod.DELETE)
  val Options = method(HttpMethod.OPTIONS)

  /** matches to relative path in current context **/
  def path: Matcher[Nothing, Uri.Path] = {
    Match[Nothing, Uri.Path]{ (request, _) =>
      Success[Uri.Path](request.path.copy(initialSlash = false))
    }
  }

  /** matches any supplied matcher **/
  def choice[F[_],A](matcher: Matcher[F, A], matchers: Matcher[F, A]*): Matcher[F, A] = {
    def go(m: Matcher[F,A], next: Seq[Matcher[F, A]]): Matcher[F, A] = {
      next.headOption match {
        case None => m
        case Some(nm) => m.flatMapR[A] {
          case Success(a) => Matcher.success(a)
          case f: Failed[F] => go(nm, next.tail)
        }
      }
    }
    go(matcher, matchers)
  }

  /** matches if remaining path segments are empty **/
  val empty : Matcher[Nothing, Unit] =
    Match[Nothing, Unit] { (request, _) =>
      if (request.path.segments.isEmpty) Success(())
      else NotFoundResponse
    }

  /** matches header of type `h` **/
  def header[H <: HttpHeader](implicit T: Typeable[H]): Matcher[Nothing, H] =
    Match[Nothing, H] { (request, _) =>
      request.headers.collectFirst(Function.unlift(T.cast)) match {
        case None => BadRequest
        case Some(h) => Success(h)
      }
    }

  /**
    * Matches if query contains `key` and that can be decoded to `A` via supplied decoder
    */
  def param[A](key: String)(implicit decoder: StringDecoder[A]) : Matcher[Nothing,A] =
    Match[Nothing, A] { (header, _) =>
      header.query.valueOf(key).flatMap(decoder.decode) match {
        case None => BadRequest
        case Some(a) => Success(a)
      }
    }

  /** Decodes Base64 (Url) encoded binary data in parameter specified by `key` **/
  def paramBase64(key: String, alphabet: Base64Alphabet = Bases.Alphabets.Base64Url): Matcher[Nothing, ByteVector] =
    param[String](key).flatMap { s =>
      ByteVector.fromBase64(s, alphabet) match {
        case None => Matcher.respondWith(HttpStatusCode.BadRequest)
        case Some(bv) => Matcher.success(bv)
      }
    }

  /** decodes head of the path to `A` givne supplied decoder from string **/
  def as[A](implicit decoder: StringDecoder[A]): Matcher[Nothing, A] =
    Match[Nothing, A] { (request, _) =>
      request.path.segments.headOption.flatMap(decoder.decode) match {
        case None => NotFoundResponse
        case Some(a) => Success(a)
      }
    }

  /**
    * Creates a Matcher that when supplied a pipe will create the websocket connection.
    * `I` is received from the client and `O` is sent to client.
    * Decoder (for I) and Encoder (for O) must be supplied.
    *
    * @param pingInterval     An interval for the Ping / Pong protocol.
    * @param handshakeTimeout An timeout to await for handshake to be successfull. If the handshake is not completed
    *                         within supplied period, connection is terminated.
    * @param maxFrameSize     Maximum size of single websocket frame. If the binary size of single frame is larger than
    *                         supplied value, websocket will fail.
    */
  def websocket[F[_] : Concurrent : Timer, I : Decoder, O : Encoder](
    pingInterval: Duration = 30.seconds
    , handshakeTimeout: FiniteDuration = 10.seconds
    , maxFrameSize: Int = 1024*1024
  ): Match[Nothing, (Pipe[F, Frame[I], Frame[O]]) => Stream[F, HttpResponse[F]]] =
    Match[Nothing, (Pipe[F, Frame[I], Frame[O]]) => Stream[F, HttpResponse[F]]] { (request, body) =>
      Success(
        WebSocket.server[F, I, O](_, pingInterval, handshakeTimeout, maxFrameSize)(request, body)
      )
    }

  /**
    * Evaluates `f` returning its result as successful matcher
    */
  def eval[F[_],A](f: F[A]): Matcher[F, A] =
    Eval(f)

  /** extracts body of the request **/
  def body[F[_]]: BodyHelper[F] = new BodyHelper[F] {}

  trait BodyHelper[F[_]] {
    /**
      * extract body as raw bytes w/o checking its content type bytes.
      * If `Content-Length` header is provided, then up to that much bytes is consumed from the body.
      * Otherwise this prouces a stream of bytes that is terminated after clients signal EOF
      */
    def bytes:  Matcher[F, Stream[F, Byte]] =
      header[`Content-Length`].?.flatMap { maybeSized =>
        Match[F, Stream[F, Byte]] { (_, body) =>
          MatchResult.success(maybeSized.map(_.value).fold(body) { sz => body.take(sz) })
        }
      }



    /** extracts body as stream of `A` **/
    def stream[A](implicit D: StreamBodyDecoder[F, A]):  Matcher[F, Stream[F, A]] =
      header[`Content-Type`].flatMap { ct =>
        bytes.flatMap { s =>
          D.decode(ct.value) match {
            case None => Matcher.ofResult(BadRequest)
            case Some(decode) => Matcher.success(s through decode)
          }
        }
      }

    /** extracts last element of the `body` or responds BadRequest if body can't be extracted **/
    def as[A](implicit D: BodyDecoder[A], F: Effect[F]): Matcher[F, A] = {
      header[`Content-Type`].flatMap { ct =>
        bytes.flatMap { s => eval {
          F.map(s.chunks.compile.toVector) { chunks =>
            val bytes =
              if (chunks.isEmpty) ByteVector.empty
              else chunks.map(chunk2ByteVector).reduce(_ ++ _)
            D.decode(bytes, ct.value)
          }
        }}.flatMap {
          case Attempt.Successful(a) => Matcher.success(a)
          case Attempt.Failure(err) => Matcher.ofResult(BadRequest)
        }
      }
    }
  }



}
