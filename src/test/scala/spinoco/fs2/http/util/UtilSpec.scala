package spinoco.fs2.http.util

import cats.effect.IO
import fs2._
import org.scalacheck.Prop._
import org.scalacheck.{Arbitrary, Gen, Properties}
import scodec.bits.Bases.{Alphabets, Base64Alphabet}
import scodec.bits.ByteVector
import shapeless.the
import spinoco.fs2.http.util



object UtilSpec extends Properties("util"){
  val charset = java.nio.charset.StandardCharsets.UTF_8

  case class EncodingSample(chunkSize:Int, text:String, alphabet: Base64Alphabet)

  implicit val encodingTestInstance : Arbitrary[EncodingSample] = Arbitrary {
    for {
      s <- the[Arbitrary[String]].arbitrary
      chunkSize <- Gen.choose(1,s.getBytes(charset).length max 1)
      alphabet <- Gen.oneOf(Seq(Alphabets.Base64Url, Alphabets.Base64))
    } yield EncodingSample(chunkSize, s, alphabet)
  }

  property("encodes.base64") = forAll { sample: EncodingSample =>
    Stream.chunk[Byte](Chunk.bytes(sample.text.getBytes(charset))).chunkLimit(sample.chunkSize).flatMap(Stream.chunk[Byte])
    .covary[IO]
    .through(util.encodeBase64Raw(sample.alphabet))
    .chunks
    .fold(ByteVector.empty){ case (acc, n) => acc ++ chunk2ByteVector(n)}
    .map(_.decodeString(charset))
    .runLog.unsafeRunSync() ?= Vector(
      Right(ByteVector.view(sample.text.getBytes(charset)).toBase64(sample.alphabet))
    )
  }


  property("decodes.base64") = forAll { sample: EncodingSample =>
    val encoded = ByteVector.view(sample.text.getBytes(charset)).toBase64(sample.alphabet)
    Stream.chunk[Byte](Chunk.bytes(encoded.getBytes(charset)))
    .covary[IO]
    .chunkLimit(sample.chunkSize).flatMap(Stream.chunk[Byte])
    .through(util.decodeBase64Raw(sample.alphabet))
    .chunks
    .fold(ByteVector.empty){ case (acc, n) => acc ++ chunk2ByteVector(n)}
    .map(_.decodeString(charset))
    .runLog.unsafeRunSync() ?= Vector(
      Right(sample.text)
    )
  }

  property("encodes.decodes.base64") =  forAll { sample: EncodingSample =>
    val r =
      Stream.chunk[Byte](Chunk.bytes(sample.text.getBytes(charset))).covary[IO].chunkLimit(sample.chunkSize).flatMap(Stream.chunk[Byte])
      .through(util.encodeBase64Raw(sample.alphabet))
      .through(util.decodeBase64Raw(sample.alphabet))
      .chunks
      .fold(ByteVector.empty){ case (acc, n) => acc ++ chunk2ByteVector(n)}
      .runLog.unsafeRunSync()


    r ?= Vector(ByteVector.view(sample.text.getBytes(charset)))
  }



}
