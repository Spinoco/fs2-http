package spinoco.fs2.http

import fs2.util.Catchable
import fs2.{Stream, _}
import scodec.Attempt.{Failure, Successful}
import scodec.{Attempt, Codec, Err}
import spinoco.fs2.http.body.{BodyDecoder, BodyEncoder, StreamBodyEncoder}
import spinoco.fs2.interop.scodec.ByteVectorChunk
import spinoco.protocol.http._
import spinoco.protocol.http.header.{Host, HttpHeader, `Content-Length`, `Content-Type`}

/** common request/response methods **/
sealed trait HttpRequestOrResponse[F[_]] { self =>
  type Self <: HttpRequestOrResponse[F]

  /** yields to true, if body of this request shall be chunked **/
  lazy val bodyIsChunked : Boolean =
    withHeaders(internal.bodyIsChunked)

  /** allows to stream arbitrary sized stream of `A` to remote party (i.e. upload) **/
  def withStreamBody[A](body: Stream[F, A])(implicit E: StreamBodyEncoder[F, A]): Self = {
    updateBody(body through E.encode)
    .updateHeaders(withHeaders(internal.swapHeader(`Content-Type`(E.contentType))))
    .asInstanceOf[Self]
  }

  /** like `stream` except one `A` that is streamed lazily **/
  def withStreamBody1[A](a: => A)(implicit E: StreamBodyEncoder[F, A]): Self =
    withStreamBody(Stream.suspend(Stream.emit(a)))

  /** sets body size to supplied value **/
  def bodySize(sz: Int): Self =
    updateHeaders(withHeaders(internal.swapHeader(`Content-Length`(sz))))

  protected def body: Stream[F, Byte]

  /** encodes body `A` given BodyEncoder exists **/
  def withBody[A](a: A)(implicit W: BodyEncoder[A]): Self = {
    W.encode(a) match {
      case Failure(err) => updateBody(body = Stream.fail(new Throwable(s"failed to encode $a: $err")))
      case Successful(bytes) =>
        val headers = withHeaders {
           _.filterNot { h => h.isInstanceOf[`Content-Type`] || h.isInstanceOf[`Content-Length`] } ++
            List(`Content-Type`(W.contentType), `Content-Length`(bytes.size))
        }

        updateBody(Stream.chunk(ByteVectorChunk(bytes)))
        .updateHeaders(headers)
        .asInstanceOf[Self]
    }
  }

  /** encodes body as utf8 string **/
  def withUtf8Body(s: String): Self =
    withBody(s)(BodyEncoder.utf8String)

  /** Decodes body with supplied decoder of `A` **/
  def bodyAs[A](implicit D: BodyDecoder[A], F: Catchable[F]): F[Attempt[A]] = {
    withHeaders { _.collectFirst { case `Content-Type`(ct) => ct } match {
      case None => F.pure(Attempt.failure(Err("Content type is not known")))
      case Some(ct) =>
        F.map(self.body.chunks.map(internal.chunk2ByteVector).runLog) { bs =>
          if (bs.isEmpty) Attempt.failure(Err("Body is empty"))
          else D.decode(bs.reduce(_ ++ _), ct)
        }
    }}
  }

  /** decodes body as string with encoding supplied in ContentType **/
  def bodyAsString(implicit F: Catchable[F]): F[Attempt[String]] =
    bodyAs[String](BodyDecoder.stringDecoder, F)



  private def withHeaders[A](f: List[HttpHeader] => A): A = self match {
    case HttpRequest(_,_,header,_) => f(header.headers)
    case HttpResponse(header, _) => f(header.headers)
  }

  protected def updateBody(body: Stream[F, Byte]): Self

  protected def updateHeaders(headers: List[HttpHeader]): Self

}





/**
  * Model of Http Request
  *
  * @param host     Host/port where to perform the request to
  * @param header   Header of the request
  * @param body     Body of the request. If empty, no body will be emitted.
  */
final case class HttpRequest[F[_]](
 scheme: HttpScheme.Value
 , host: HostPort
 , header: HttpRequestHeader
 , body: Stream[F, Byte]
) extends HttpRequestOrResponse[F]  { self =>

  override type Self = HttpRequest[F]

  def withMethod(method: HttpMethod.Value): HttpRequest[F] = {
    self.copy(header = self.header.copy(method = method))
  }

  protected def updateBody(body: Stream[F, Byte]): Self =
    self.copy(body = body)

  protected def updateHeaders(headers: List[HttpHeader]): Self =
    self.copy(header = self.header.copy(headers = headers))
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

  def post[F[_], A](uri: Uri, a: A)(implicit E: BodyEncoder[A]): HttpRequest[F] =
    get(uri).withMethod(HttpMethod.POST).withBody(a)

  def put[F[_], A](uri: Uri, a: A)(implicit E: BodyEncoder[A]): HttpRequest[F] =
    get(uri).withMethod(HttpMethod.PUT).withBody(a)

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


/**
* Model of Http Response
*
* @param header   Header of the response
* @param body     Body of the response. If empty, no body will be emitted.
*/
final case class HttpResponse[F[_]](
 header: HttpResponseHeader
 , body: Stream[F, Byte]
) extends HttpRequestOrResponse[F] { self =>
  override type Self = HttpResponse[F]

  protected def updateBody(body: Stream[F, Byte]): Self =
    self.copy(body = body)

  protected def updateHeaders(headers: List[HttpHeader]): Self =
    self.copy(header= self.header.copy(headers = headers))
}


object HttpResponse {


  def apply[F[_]](sc: HttpStatusCode):HttpResponse[F] = {
    HttpResponse(
      header = HttpResponseHeader(status = sc, reason = sc.label)
      , body = Stream.empty
    )
  }


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