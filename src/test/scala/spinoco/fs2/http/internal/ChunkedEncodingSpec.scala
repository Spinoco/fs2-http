package spinoco.fs2.http.internal

import cats.effect.IO
import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import scodec.bits.ByteVector
import spinoco.fs2.http.util.chunk2ByteVector

object ChunkedEncodingSpec extends Properties("ChunkedEncoding") {
  val charset = java.nio.charset.StandardCharsets.UTF_8

  property("encode-decode") = forAll { strings: List[String] =>
    val in = strings.foldLeft(Stream.empty.covaryAll[IO, Byte]) { case(s,n) => s ++ Stream.chunk(Chunk.bytes(n.getBytes(charset))) }


    (in through ChunkedEncoding.encode through ChunkedEncoding.decode(1024))
    .chunks
    .runLog
    .map(_.foldLeft(ByteVector.empty){ case (bv, n) => bv ++ chunk2ByteVector(n) })
    .map(_.decodeString(charset))
    .unsafeRunSync() ?= Right(
      strings.mkString
    )

  }

  val wikiExample = "4\r\n" +
    "Wiki\r\n" +
    "5\r\n" +
    "pedia\r\n" +
    "E\r\n" +
    " in\r\n"+
    "\r\n" +
    "chunks.\r\n" +
    "0\r\n" +
    "\r\n"

  property("encoded-wiki-example") = secure {


    (Stream.chunk[Byte](Chunk.bytes(wikiExample.getBytes(charset))) through ChunkedEncoding.decode(1024))
    .covary[IO]
    .chunks
    .runLog
    .map(_.foldLeft(ByteVector.empty){ case (bv, n) => bv ++ chunk2ByteVector(n) })
    .map(_.decodeString(charset))
    .unsafeRunSync() ?= Right(
    "Wikipedia in\r\n\r\nchunks."
    )

  }

  property("decoded-wiki-example") = secure {
    val chunks:Stream[IO,Byte] = Stream.emits(
      Seq(
        "Wiki"
        , "pedia"
        , " in\r\n\r\nchunks."
      )
    ).flatMap(s => Stream.chunk[Byte](Chunk.bytes(s.getBytes(charset))))

    (chunks through ChunkedEncoding.encode)
      .chunks
      .runLog
      .map(_.foldLeft(ByteVector.empty){ case (bv, n) => bv ++ chunk2ByteVector(n) })
      .map(_.decodeString(charset))
      .unsafeRunSync() ?= Right(wikiExample)
  }




}
