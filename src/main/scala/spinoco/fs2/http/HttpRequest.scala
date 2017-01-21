package spinoco.fs2.http

import spinoco.protocol.http._
import fs2._
import scodec.Attempt.{Failure, Successful}
import scodec.Codec
import spinoco.fs2.interop.scodec.ByteVectorChunk
import spinoco.protocol.http.header.Host

/**
  * Model of Http Request
  *
  * @param host     Host/port where to perform the request to
  * @param header   Header of the request
  * @param body     Body of the request. If empty, no body will be emitted.
  */
sealed case class HttpRequest[F[_]](
  scheme: HttpScheme.Value
  , host: HostPort
  , header: HttpRequestHeader
  , body: Stream[F, Byte]
) { self =>

  /** yields to true, if body of this request shall be chunked **/
  lazy val bodyIsChunked : Boolean =
    internal.bodyIsChunked(self.header.headers)

  def withMethod(method: HttpMethod.Value): HttpRequest[F] = {
    self.copy(header = self.header.copy(method = method))
  }

}

object HttpRequest {

  def get[F[_]](uri:  Uri): HttpRequest[F] =
    HttpRequest(
      scheme = uri.scheme
      , host = uri.host
      , header = HttpRequestHeader(
        method = HttpMethod.GET
        , path = uri.path
        , query = uri.query
        , headers = List(
          Host(uri.host)
        )
      )
      , body = Stream.empty)

  def post[F[_]](uri: Uri): HttpRequest[F] =
    get(uri).withMethod(HttpMethod.POST)

  def put[F[_]](uri: Uri): HttpRequest[F] =
    get(uri).withMethod(HttpMethod.PUT)

  def delete[F[_]](uri: Uri): HttpRequest[F] =
    get(uri).withMethod(HttpMethod.DELETE)


  /**
    * Reads http header and body from the stream of bytes.
    *
    * If the body is encoded in chunked encoding this will decode it
    *
    * @param maxHeaderSize    Maximum size of the http header
    * @param headerCodec      header codec to use
    * @tparam F
    * @return
    */
  def fromStream[F[_]](
    maxHeaderSize: Int
    , headerCodec: Codec[HttpRequestHeader]
  ): Pipe[F, Byte, (HttpRequestHeader, Stream[F, Byte])] = {
    import internal._
    _ through httpHeaderAndBody(maxHeaderSize) flatMap { case (header, bodyRaw) =>
      headerCodec.decodeValue(header.bits) match {
        case Failure(err) => Stream.fail(new Throwable(s"Decoding of the request header failed: $err"))
        case Successful(decoded) =>
          val body =
            if (bodyIsChunked(decoded.headers)) bodyRaw through ChunkedEncoding.decode(1000)
            else bodyRaw

          Stream.emit(decoded -> body)
      }
    }
  }


  /**
    * Converts the supplied request to binary stream of data to be sent over wire.
    * Note that this inspects the headers to eventually perform chunked encoding of the stream,
    * if that indication is present in headers,
    * otherwise this just encodes as binary stream of data after header of the request.
    *
    * @param request        request to convert to stream
    * @param headerCodec    Codec to convert the header to bytes
    */
  def toStream[F[_]](
    request: HttpRequest[F]
    , headerCodec: Codec[HttpRequestHeader]
  ): Stream[F, Byte] = Stream.suspend {
    import internal._

    headerCodec.encode(request.header) match {
      case Failure(err) => Stream.fail(new Throwable(s"Encoding of the header failed: $err"))
      case Successful(bits) =>
        val body =
          if (request.bodyIsChunked)  request.body through ChunkedEncoding.encode
          else request.body

        Stream.chunk[F,Byte](ByteVectorChunk(bits.bytes ++ `\r\n\r\n`)) ++ body
    }
  }


}

