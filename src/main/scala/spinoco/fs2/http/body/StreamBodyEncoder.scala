package spinoco.fs2.http.body

import cats.MonadError
import fs2._
import scodec.Attempt.{Failure, Successful}
import scodec.bits.ByteVector

import spinoco.protocol.mime.{ContentType, MIMECharset, MediaType}


trait StreamBodyEncoder[F[_], A] {
  /** an pipe to encode stram of `A` to stream of bytes **/
  def encode: Pipe[F, A, Byte]

  def contentType: ContentType

  /** given f, converts to encoder BodyEncoder[F, B] **/
  def mapIn[B](f: B => A): StreamBodyEncoder[F, B] =
    StreamBodyEncoder.instance(contentType) { _ map f through encode }

  /** given f, converts to encoder BodyEncoder[F, B] **/
  def mapInF[B](f: B => F[A]): StreamBodyEncoder[F, B] =
    StreamBodyEncoder.instance(contentType) { _ evalMap  f through encode }

  /** changes content type of this encoder **/
  def withContentType(tpe: ContentType): StreamBodyEncoder[F, A] =
    StreamBodyEncoder.instance(tpe)(encode)

}

object StreamBodyEncoder {

  @inline def apply[F[_], A](implicit instance: StreamBodyEncoder[F, A]): StreamBodyEncoder[F, A] = instance

  def instance[F[_], A](tpe: ContentType)(pipe: Pipe[F, A, Byte]): StreamBodyEncoder[F, A] =
    new StreamBodyEncoder[F, A] {
      def contentType: ContentType = tpe
      def encode: Pipe[F, A, Byte] = pipe
    }

  /** encoder that encodes bytes as they come in, with `application/octet-stream` content type **/
  def byteEncoder[F[_]] : StreamBodyEncoder[F, Byte] =
    StreamBodyEncoder.instance(ContentType.BinaryContent(MediaType.`application/octet-stream`, None)) { identity }

  /** encoder that encodes ByteVector as they come in, with `application/octet-stream` content type **/
  def byteVectorEncoder[F[_]] : StreamBodyEncoder[F, ByteVector] =
    StreamBodyEncoder.instance(ContentType.BinaryContent(MediaType.`application/octet-stream`, None)) { _.flatMap { bv => Stream.chunk(Chunk.byteVector(bv)) } }

  /** encoder that encodes utf8 string, with `text/plain` utf8 content type **/
  def utf8StringEncoder[F[_]: RaiseThrowable](implicit F: MonadError[F, Throwable]) : StreamBodyEncoder[F, String] =
    byteVectorEncoder mapInF[String] { s =>
      ByteVector.encodeUtf8(s) match {
        case Right(bv) => F.pure(bv)
        case Left(err) => F.raiseError[ByteVector](new Throwable(s"Failed to encode string: $err ($s) "))
      }
    } withContentType ContentType.TextContent(MediaType.`text/plain`, Some(MIMECharset.`UTF-8`))

  /** a convenience wrapper to convert body encoder to StreamBodyEncoder **/
  def fromBodyEncoder[F[_]: RaiseThrowable, A](implicit E: BodyEncoder[A]):StreamBodyEncoder[F, A] =
    StreamBodyEncoder.instance(E.contentType) { _.flatMap { a =>
      E.encode(a) match {
        case Failure(err) => Stream.raiseError(new Throwable(s"Failed to encode: $err ($a)"))
        case Successful(bytes) => Stream.chunk(Chunk.byteVector(bytes))
      }
    }}



}
