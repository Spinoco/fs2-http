package spinoco.fs2.http

import spinoco.protocol.http.HttpResponseHeader
import fs2._
import scodec.Attempt.{Failure, Successful}
import scodec.Codec
import spinoco.protocol.http.codec.HttpResponseHeaderCodec

/**
  * Model of Http Response
  *
  * @param header   Header of the response
  * @param body     Body of the response. If empty, no body will be emitted.
  */
case class HttpResponse[F[_]](
  header: HttpResponseHeader
  , body: Stream[F, Byte]
)


object HttpResponse {


  /**
    * Decodes stream of bytes as HttpResponse.
    */
  def fromStream[F[_]](
    maxHeaderSize: Int
    , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
  ): Pipe[F,Byte, HttpResponse[F]] = {
    import internal._

    _ through httpHeaderAndBody(maxHeaderSize) flatMap { case (header, bodyRaw) =>
      responseCodec.decodeValue(header.bits) match {
        case Failure(err) => Stream.fail(new Throwable(s"Failed to decode http response :$err"))
        case Successful(response) =>
          val body =
            if (bodyIsChunked(response.headers)) bodyRaw through ChunkedEncoding.decode(1024)
            else bodyRaw

        Stream.emit(HttpResponse(response, body))
      }
    }
  }

}