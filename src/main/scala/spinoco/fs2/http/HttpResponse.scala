package spinoco.fs2.http

import spinoco.protocol.http.HttpResponseHeader
import fs2._
import scodec.Attempt.{Failure, Successful}
import scodec.Codec
import spinoco.fs2.interop.scodec.ByteVectorChunk

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
    , responseCodec: Codec[HttpResponseHeader]
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


  /** Encodes response to stream of bytes **/
  def toStream[F[_]](
    response: HttpResponse[F]
    , headerCodec: Codec[HttpResponseHeader]
  ): Stream[F, Byte] = Stream.suspend {
    import internal._

    headerCodec.encode(response.header) match {
      case Failure(err) => Stream.fail(new Throwable(s"Failed to encode http response : $response :$err "))
      case Successful(encoded) =>
        val body =
          if (bodyIsChunked(response.header.headers)) response.body through ChunkedEncoding.encode
          else response.body

        Stream.chunk[F,Byte](ByteVectorChunk(encoded.bytes ++ `\r\n\r\n`)) ++ body
    }

  }

}