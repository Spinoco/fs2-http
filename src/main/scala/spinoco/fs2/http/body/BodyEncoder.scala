package spinoco.fs2.http.body

import scodec.bits.ByteVector
import scodec.{Attempt, Encoder, Err}
import spinoco.protocol.http.Uri
import spinoco.protocol.http.header.value.{ContentType, HttpCharset, MediaType}

/**
  * Encodes one `A` to body, strictly
  */
sealed trait BodyEncoder[A] { self =>
  def encode(a: A): Attempt[ByteVector]
  def contentType: ContentType

  /** given f, converts to encoder BodyEncoder[F, B] **/
  def mapIn[B](f: B => A): BodyEncoder[B] =
    BodyEncoder(self.contentType) { b => self.encode(f(b)) }

  /** given f, converts to encoder BodyEncoder[F, B] **/
  def mapInAttempt[B](f: B => Attempt[A]): BodyEncoder[B] =
    BodyEncoder(self.contentType) { b => f(b).flatMap(self.encode) }

  def withContentType(tpe: ContentType): BodyEncoder[A] =
    BodyEncoder(tpe)(self.encode)
}


object BodyEncoder {

  def apply[A](tpe: ContentType)(f: A => Attempt[ByteVector]): BodyEncoder[A] =
    new BodyEncoder[A] {
      def encode(a: A): Attempt[ByteVector] = f(a)
      def contentType: ContentType = tpe
    }

  def byteVector(tpe: ContentType = ContentType(MediaType.`application/octet-stream`, None, None)): BodyEncoder[ByteVector] =
    BodyEncoder(tpe)(Attempt.successful)

  val utf8String: BodyEncoder[String] =
    BodyEncoder(ContentType(MediaType.`text/plain`, Some(HttpCharset.`UTF-8`), None)){ s =>
      Attempt.fromEither(ByteVector.encodeUtf8(s).left.map { ex => Err(s"Failed to encode string: ${ex.getMessage}, ($s)") })
    }

  def forEncoder[A](tpe: ContentType)(codec: Encoder[A]):BodyEncoder[A] =
    BodyEncoder(tpe)(a => codec.encode(a).map(_.bytes))

  /** encodes supplied query as application/x-www-form-urlencoded data **/
  def `x-www-form-urlencoded`: BodyEncoder[Uri.Query] =
    forEncoder(ContentType(MediaType.`application/x-www-form-urlencoded`, None, None))(Uri.Query.codec)

}