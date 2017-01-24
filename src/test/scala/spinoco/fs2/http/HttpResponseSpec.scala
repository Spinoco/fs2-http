package spinoco.fs2.http

import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import scodec.Attempt
import spinoco.protocol.http.header._
import spinoco.protocol.http.codec.HttpResponseHeaderCodec
import spinoco.protocol.http.{HttpResponseHeader, HttpStatusCode}
import spinoco.fs2.http.internal._
import spinoco.protocol.http.header.value.{ContentType, HttpCharset, MediaType}


object HttpResponseSpec extends Properties("HttpResponse") {

  property("encode") = secure {

    val response =
      HttpResponse[Task](HttpStatusCode.Ok)
      .withUtf8Body("Hello World")

    HttpResponse.toStream(response, HttpResponseHeaderCodec.defaultCodec)
      .chunks.runLog.map { _.map(chunk2ByteVector).reduce { _ ++ _ }.decodeUtf8 }
      .unsafeRun() ?=
      Right(Seq(
        "HTTP/1.1 200 OK"
        , "Content-Type: text/plain; charset=utf-8"
        , "Content-Length: 11"
        , ""
        , "Hello World"
      ).mkString("\r\n"))

  }


  property("decode") = secure {

    Stream.chunk(Chunk.bytes(
      Seq(
        "HTTP/1.1 200 OK"
        , "Content-Type: text/plain; charset=utf-8"
        , "Content-Length: 11"
        , ""
        , "Hello World"
      ).mkString("\r\n").getBytes
    ))
    .through(HttpResponse.fromStream[Task](4096, HttpResponseHeaderCodec.defaultCodec))
    .flatMap { response => Stream.eval(response.bodyAsString).map(response.header -> _ ) }
    .runLog.unsafeRun() ?=
    Vector(
      HttpResponseHeader(
        status = HttpStatusCode.Ok
        , reason = "OK"
        , headers = List(
          `Content-Type`(ContentType(MediaType.`text/plain`, Some(HttpCharset.`UTF-8`), None))
          , `Content-Length`(11)
        )
      ) -> Attempt.Successful("Hello World")
    )
  }



}
