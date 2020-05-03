package spinoco.fs2.http

import cats.effect.IO
import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import spinoco.protocol.http._
import spinoco.protocol.http.codec.HttpRequestHeaderCodec
import spinoco.protocol.http.header._
import spinoco.protocol.mime.{ContentType, MIMECharset, MediaType}


object HttpRequestSpec extends Properties("HttpRequest") {

  property("encode") = secure {

    val request =
      HttpRequest.get[IO](
        Uri.http("www.spinoco.com", "/hello-world.html")
      ).withUtf8Body("Hello World")


    HttpRequest.toStream(request, HttpRequestHeaderCodec.defaultCodec)
    .chunks.compile.toVector.map { _.map(_.toByteVector).reduce { _ ++ _ }.decodeUtf8 }
    .unsafeRunSync() ?=
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
    .covary[IO]
    .through(HttpRequest.fromStream[IO](4096,HttpRequestHeaderCodec.defaultCodec))
    .flatMap { case (header, body) =>
      Stream.eval(body.chunks.compile.toVector.map(_.map(_.toByteVector).reduce(_ ++ _).decodeUtf8)).map { bodyString =>
        header -> bodyString
      }
    }.compile.toVector.unsafeRunSync() ?= Vector(
      HttpRequestHeader(
        method = HttpMethod.GET
        , path = Uri.Path / "hello-world.html"
        , headers = List(
          Host(HostPort("www.spinoco.com", None))
          , `Content-Type`(ContentType.TextContent(MediaType.`text/plain`, Some(MIMECharset.`UTF-8`)))
          , `Content-Length`(11)
        )
        , query  = Uri.Query.empty
      ) -> Right("Hello World")
    )

  }

}
