package spinoco.fs2.http.body

import scodec.bits.ByteVector
import scodec.{Attempt, Decoder, Err}
import spinoco.protocol.http.Uri
import spinoco.protocol.mime.{ContentType, MIMECharset, MediaType}
import spinoco.fs2.http.util


trait BodyDecoder[A] {
  def decode(bytes: ByteVector, contentType: ContentType): Attempt[A]
}


object BodyDecoder {

  @inline def apply[A](implicit instance: BodyDecoder[A]): BodyDecoder[A] = instance

  def instance[A](f: (ByteVector, ContentType) => Attempt[A]): BodyDecoder[A] =
    new BodyDecoder[A] {
      def decode(bytes: ByteVector, contentType: ContentType): Attempt[A] =
        f(bytes, contentType)
    }

  def forDecoder[A](f: ContentType => Attempt[Decoder[A]]): BodyDecoder[A] =
    BodyDecoder.instance { (bs, ct) => f(ct).flatMap(_.decodeValue(bs.bits)) }

  val stringDecoder: BodyDecoder[String] = BodyDecoder.instance { case (bytes, ct) =>
    if (! ct.mediaType.isText) Attempt.Failure(Err(s"Media Type must be text, but is ${ct.mediaType}"))
    else {
      MIMECharset.asJavaCharset(util.getCharset(ct).getOrElse(MIMECharset.`UTF-8`)).flatMap { implicit chs =>
        Attempt.fromEither(bytes.decodeString.left.map(ex => Err(s"Failed to decode string ContentType: $ct, charset: $chs, err: ${ex.getMessage}")))
      }
    }
  }

  /** decodes body as query encoded as application/x-www-form-urlencoded data **/
  val `x-www-form-urlencoded`: BodyDecoder[Uri.Query] =
    forDecoder { ct =>
      if (ct.mediaType == MediaType.`application/x-www-form-urlencoded`) Attempt.successful(Uri.Query.codec)
      else Attempt.failure(Err(s"Unsupported content type : $ct"))
    }

}
