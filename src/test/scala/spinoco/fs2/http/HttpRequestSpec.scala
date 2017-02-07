package spinoco.fs2.http

import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import spinoco.protocol.http._
import spinoco.protocol.http.codec.HttpRequestHeaderCodec
import spinoco.protocol.http.header._
import spinoco.protocol.http.header.value.{ContentType, HttpCharset, MediaType}


object HttpRequestSpec extends Properties("HttpRequest") {
  import spinoco.fs2.http.util.chunk2ByteVector

  property("encode") = secure {

    val request =
      HttpRequest.get[Task](
        Uri.http("www.spinoco.com", "/hello-world.html")
      ).withUtf8Body("Hello World")


    HttpRequest.toStream(request, HttpRequestHeaderCodec.defaultCodec)
    .chunks.runLog.map { _.map(chunk2ByteVector).reduce { _ ++ _ }.decodeUtf8 }
    .unsafeRun() ?=
    Right(Seq(
      "GET /hello-world.html HTTP/1.1"
      , "Host: www.spinoco.com"
      , "Content-Type: text/plain; charset=utf-8"
      , "Content-Length: 11"
      , ""
      , "Hello World"
    ).mkString("\r\n"))
  }


  property("decode") = secure {
    Stream.chunk(Chunk.bytes(
      Seq(
        "GET /hello-world.html HTTP/1.1"
        , "Host: www.spinoco.com"
        , "Content-Type: text/plain; charset=utf-8"
        , "Content-Length: 11"
        , ""
        , "Hello World"
      ).mkString("\r\n").getBytes
    ))
    .through(HttpRequest.fromStream[Task](4096,HttpRequestHeaderCodec.defaultCodec))
    .flatMap { case (header, body) =>
      Stream.eval(body.chunks.runLog.map(_.map(chunk2ByteVector).reduce(_ ++ _).decodeUtf8)).map { bodyString =>
        header -> bodyString
      }
    }.runLog.unsafeRun() ?= Vector(
      HttpRequestHeader(
        method = HttpMethod.GET
        , path = Uri.Path / "hello-world.html"
        , headers = List(
          Host(HostPort("www.spinoco.com", None))
          , `Content-Type`(ContentType(MediaType.`text/plain`, Some(HttpCharset.`UTF-8`), None))
          , `Content-Length`(11)
        )
        , query  = Uri.Query.empty
      ) -> Right("Hello World")
    )

  }

}
