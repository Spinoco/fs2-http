package spinoco.fs2.http.routing

import scodec.bits.{Bases, ByteVector}
import shapeless.tag
import shapeless.tag.@@

import scala.util.Try

/**
  * Decoder for `A` to be decoded from supplied String
  */
sealed trait StringDecoder[A] { self =>
  /** decode `A` from supplied string **/
  def decode(s:String): Option[A]

  def map[B](f: A => B): StringDecoder[B] =
    StringDecoder { s => self.decode(s).map(f)  }

  def mapO[B](f: A => Option[B]): StringDecoder[B] =
    StringDecoder { s => self.decode(s).flatMap(f) }

  def filter(f: A => Boolean): StringDecoder[A] =
    StringDecoder { s => self.decode(s).filter(f) }
}


object StringDecoder {

  def apply[A]( f: String => Option[A]):StringDecoder[A] =
    new StringDecoder[A] { def decode(s: String): Option[A] = f(s) }

  implicit val stringInstance: StringDecoder[String] =
    StringDecoder { Some(_) }

  implicit val intInstance : StringDecoder[Int] =
    StringDecoder { s => Try { s.toInt }.toOption }

  implicit val longInstance : StringDecoder[Long] =
    StringDecoder { s => Try { s.toLong }.toOption }

  implicit val doubleInstance : StringDecoder[Double] =
    StringDecoder { s => Try { s.toDouble }.toOption }

  implicit val base64UrlInstance: StringDecoder[ByteVector @@ Base64Url] =
    StringDecoder { s => ByteVector.fromBase64(s,Bases.Alphabets.Base64Url).map(tag[Base64Url](_)) }


}