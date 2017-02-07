package spinoco.fs2.http.routing

import scodec.bits.{Bases, ByteVector}
import shapeless.tag
import shapeless.tag.@@

import scala.reflect.ClassTag
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

  implicit val boolInstance: StringDecoder[Boolean] =
    StringDecoder { s =>
      if (s.equalsIgnoreCase("true") ) Some(true)
      else if (s.equalsIgnoreCase("false")) Some(false)
      else None
    }

  implicit val stringInstance: StringDecoder[String] =
    StringDecoder { Some(_) }

  implicit val byteInstance : StringDecoder[Byte] =
    StringDecoder { s => Try { s.toByte }.toOption }

  implicit val shortInstance : StringDecoder[Short] =
    StringDecoder { s => Try { s.toShort }.toOption }

  implicit val intInstance : StringDecoder[Int] =
    StringDecoder { s => Try { s.toInt }.toOption }

  implicit val longInstance : StringDecoder[Long] =
    StringDecoder { s => Try { s.toLong }.toOption }

  implicit val doubleInstance : StringDecoder[Double] =
    StringDecoder { s => Try { s.toDouble }.toOption }

  implicit val floatInstance : StringDecoder[Float] =
    StringDecoder { s => Try { s.toFloat }.toOption }

  implicit val bigIntInstance : StringDecoder[BigInt] =
    StringDecoder { s => Try { BigInt(s) }.toOption }

  implicit val bigDecimalInstance : StringDecoder[BigDecimal] =
    StringDecoder { s => Try { BigDecimal(s) }.toOption }

  implicit val base64UrlInstance: StringDecoder[ByteVector @@ Base64Url] =
    StringDecoder { s => ByteVector.fromBase64(s,Bases.Alphabets.Base64Url).map(tag[Base64Url](_)) }

  implicit def enumInstance[E <: Enumeration : ClassTag] : StringDecoder[E#Value] = {
    val E = implicitly[ClassTag[E]].runtimeClass.getField("MODULE$").get((): Unit).asInstanceOf[Enumeration]
    StringDecoder { s => Try { E.withName(s)}.toOption.map(_.asInstanceOf[E#Value]) }
  }

}