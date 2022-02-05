package spinoco.fs2.http.body

import fs2._
import spinoco.protocol.mime.{ContentType, MIMECharset}
import spinoco.fs2.http.util


sealed trait StreamBodyDecoder[F[_], A] {

  /** decodes stream with supplied content type. yields to None, if the ContentType is not of required type **/
  def decode(ct: ContentType): Option[Pipe[F, Byte, A]]

}


object StreamBodyDecoder {

  @inline def apply[F[_], A](implicit instance: StreamBodyDecoder[F, A]): StreamBodyDecoder[F, A] = instance

  def instance[F[_], A](f: ContentType => Option[Pipe[F, Byte, A]]): StreamBodyDecoder[F, A] =
    new StreamBodyDecoder[F, A] { def decode(ct: ContentType): Option[Pipe[F, Byte, A]] = f(ct) }

  def utf8StringDecoder[F[_]]: StreamBodyDecoder[F, String] =
    StreamBodyDecoder.instance { ct =>
      if (ct.mediaType.isText && util.getCharset(ct).contains(MIMECharset.`UTF-8`)) Some(text.utf8.decode[F])
      else None
    }

}
