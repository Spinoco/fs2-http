package spinoco.fs2.http.body

import fs2._
import spinoco.protocol.mime.{ContentType, MIMECharset}


sealed trait StreamBodyDecoder[F[_], A] {

  /** decodes stream with supplied content type. yields to None, if the ContentType is not of required type **/
  def decode(ct: ContentType): Option[Pipe[F, Byte, A]]

}


object StreamBodyDecoder {

  def apply[F[_], A](f: ContentType => Option[Pipe[F, Byte, A]]): StreamBodyDecoder[F, A] =
    new StreamBodyDecoder[F, A] { def decode(ct: ContentType): Option[Pipe[F, Byte, A]] = f(ct) }

  def utf8StringDecoder[F[_]]: StreamBodyDecoder[F, String] =
    StreamBodyDecoder { ct => ct match {
      case t: ContentType.TextContent =>
        if (t.charset.contains(MIMECharset.`UTF-8`)) Some(text.utf8Decode[F])
        else None
      case other => None
    }}

}
