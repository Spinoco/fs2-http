package spinoco.fs2.http.internal

import cats.effect.IO
import fs2._
import org.scalacheck.Properties
import org.scalacheck.Prop._
import scodec.bits.ByteVector

object ChunkedEncodingSpec extends Properties("ChunkedEncoding") {
  import cats.effect.unsafe.implicits.global

  property("encode-decode") = forAll { strings: List[String] =>
    val in = strings.foldLeft(Stream.empty.covaryAll[IO, Byte]) { case(s,n) => s ++ Stream.chunk(Chunk.byteVector(ByteVector.view(n.getBytes))) }


    (in through ChunkedEncoding.encode through ChunkedEncoding.decode(1024))
    .chunks
    .compile.toVector
    .map(_.foldLeft(ByteVector.empty){ case (bv, n) => bv ++ n.toByteVector })
    .map(_.decodeUtf8)
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


    (Stream.chunk[IO, Byte](Chunk.byteVector(ByteVector.view(wikiExample.getBytes))) through ChunkedEncoding.decode(1024))
    .covary[IO]
    .chunks
    .compile.toVector
    .map(_.foldLeft(ByteVector.empty){ case (bv, n) => bv ++ n.toByteVector })
    .map(_.decodeUtf8)
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
    ).flatMap(s => Stream.chunk[IO, Byte](Chunk.byteVector(ByteVector.view(s.getBytes))))

    (chunks through ChunkedEncoding.encode)
      .chunks
      .compile.toVector
      .map(_.foldLeft(ByteVector.empty){ case (bv, n) => bv ++ n.toByteVector })
      .map(_.decodeUtf8)
      .unsafeRunSync() ?= Right(wikiExample)
  }




}
