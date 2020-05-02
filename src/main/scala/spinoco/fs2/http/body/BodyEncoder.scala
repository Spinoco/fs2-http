package spinoco.fs2.http.body

import scodec.bits.ByteVector
import scodec.{Attempt, Encoder, Err}
import spinoco.protocol.http.Uri
import spinoco.protocol.mime.{ContentType, MIMECharset, MediaType}

/**
  * Encodes one `A` to body, strictly
  */
sealed trait BodyEncoder[A] { self =>
  def encode(a: A): Attempt[ByteVector]
  def contentType: ContentType

  /** given f, converts to encoder BodyEncoder[F, B] **/
  def mapIn[B](f: B => A): BodyEncoder[B] =
    BodyEncoder.instance(self.contentType) { b => self.encode(f(b)) }

  /** given f, converts to encoder BodyEncoder[F, B] **/
  def mapInAttempt[B](f: B => Attempt[A]): BodyEncoder[B] =
    BodyEncoder.instance(self.contentType) { b => f(b).flatMap(self.encode) }

  def withContentType(tpe: ContentType): BodyEncoder[A] =
    BodyEncoder.instance(tpe)(self.encode)
}


object BodyEncoder {

  @inline def apply[A](implicit instance: BodyEncoder[A]): BodyEncoder[A] = instance

  def instance[A](tpe: ContentType)(f: A => Attempt[ByteVector]): BodyEncoder[A] =
    new BodyEncoder[A] {
      def encode(a: A): Attempt[ByteVector] = f(a)
      def contentType: ContentType = tpe
    }

  def byteVector(tpe: ContentType = ContentType.BinaryContent(MediaType.`application/octet-stream`, None)): BodyEncoder[ByteVector] =
    BodyEncoder.instance(tpe)(Attempt.successful)

  val utf8String: BodyEncoder[String] =
    BodyEncoder.instance(ContentType.TextContent(MediaType.`text/plain`, Some(MIMECharset.`UTF-8`))){ s =>
      Attempt.fromEither(ByteVector.encodeUtf8(s).left.map { ex => Err(s"Failed to encode string: ${ex.getMessage}, ($s)") })
    }

  def forEncoder[A](tpe: ContentType)(codec: Encoder[A]):BodyEncoder[A] =
    BodyEncoder.instance(tpe)(a => codec.encode(a).map(_.bytes))

  /** encodes supplied query as application/x-www-form-urlencoded data **/
  def `x-www-form-urlencoded`: BodyEncoder[Uri.Query] =
    forEncoder(ContentType.TextContent(MediaType.`application/x-www-form-urlencoded`, None))(Uri.Query.codec)

}