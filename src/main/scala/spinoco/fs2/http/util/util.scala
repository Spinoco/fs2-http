package spinoco.fs2.http

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import fs2.Chunk.ByteVectorChunk
import fs2._
import scodec.bits.{BitVector, ByteVector}
import scodec.bits.Bases.{Alphabets, Base64Alphabet}

import spinoco.protocol.mime.{ContentType, MIMECharset}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

package object util {


  /**
    * Encodes bytes to base64 encoded bytes [[http://tools.ietf.org/html/rfc4648#section-5 RF4648 section 5]]
    * Encoding is done lazily to support very large Base64 bodies i.e. email, attachments..)
    * @param alphabet   Alphabet to use
    * @return
    */
  def encodeBase64Raw[F[_]](alphabet:Base64Alphabet): Pipe[F, Byte, Byte] = {
    def go(rem:ByteVector): Stream[F,Byte] => Pull[F, Byte, Unit] = {
      _.pull.unconsChunk flatMap {
        case None =>
          if (rem.size == 0) Pull.done
          else Pull.output(ByteVectorChunk(ByteVector.view(rem.toBase64(alphabet).getBytes)))

        case Some((chunk, tl)) =>
          val n = rem ++ chunk2ByteVector(chunk)
          if (n.size/3 > 0) {
            val pad = n.size % 3
            val enc = n.dropRight(pad)
            val out = Array.ofDim[Byte]((enc.size.toInt / 3) * 4)
            var pos = 0
            enc.toBitVector.grouped(6) foreach { group =>
              val idx = group.padTo(8).shiftRight(2, signExtension = false).toByteVector.head
              out(pos) = alphabet.toChar(idx).toByte
              pos = pos + 1
            }
            Pull.output(ByteVectorChunk(ByteVector.view(out))) >> go(n.takeRight(pad))(tl)
          } else {
            go(n)(tl)
          }

      }

    }
    src => go(ByteVector.empty)(src).stream
  }

  /** encodes base64 encoded stream [[http://tools.ietf.org/html/rfc4648#section-5 RF4648 section 5]]. Whitespaces are ignored **/
  def encodeBase64Url[F[_]]:Pipe[F, Byte, Byte] =
    encodeBase64Raw(Alphabets.Base64Url)

  /** encodes base64 encoded stream [[http://tools.ietf.org/html/rfc4648#section-4 RF4648 section 4]] **/
  def encodeBase64[F[_]]:Pipe[F, Byte, Byte] =
    encodeBase64Raw[F](Alphabets.Base64)


  /**
    * Decodes base64 encoded stream with supplied alphabet. Whitespaces are ignored.
    * Decoding is lazy to support very large Base64 bodies (i.e. email)
    */
  def decodeBase64Raw[F[_]](alphabet:Base64Alphabet):Pipe[F, Byte, Byte] = {
    val Pad = alphabet.pad
    def go(remAcc:BitVector): Stream[F, Byte] => Pull[F, Byte, Unit] = {
      _.pull.unconsChunk flatMap {
        case None => Pull.done

        case Some((chunk,tl)) =>
          val bv = chunk2ByteVector(chunk)
          var acc = remAcc
          var idx = 0
          var term = false
          try {
            bv.foreach  { b =>
              b.toChar match {
                case c if alphabet.ignore(c) => // ignore no-op
                case Pad => term = true
                case c =>
                  if (!term) acc = acc ++ BitVector(alphabet.toIndex(c)).drop(2)
                  else {
                    throw new IllegalArgumentException(s"Unexpected character '$c' at index $idx after padding character; only '=' and whitespace characters allowed after first padding character")
                  }
              }
              idx = idx + 1
            }
            val aligned = (acc.size / 8) * 8
            if (aligned <= 0 && !term) go(acc)(tl)
            else {
              val (out, rem) = acc.splitAt(aligned)
              if (term) Pull.output(ByteVectorChunk(out.toByteVector))
              else Pull.output(ByteVectorChunk(out.toByteVector)) >> go(rem)(tl)
            }

          } catch {
            case e: IllegalArgumentException =>
              Pull.raiseError(new Throwable(s"Invalid base 64 encoding at index $idx", e))
          }
      }
    }
    src => go(BitVector.empty)(src).stream

  }

  /** decodes base64 encoded stream [[http://tools.ietf.org/html/rfc4648#section-5 RF4648 section 5]]. Whitespaces are ignored **/
  def decodeBase64Url[F[_]]:Pipe[F, Byte, Byte] =
    decodeBase64Raw(Alphabets.Base64Url)

  /** decodes base64 encoded stream [[http://tools.ietf.org/html/rfc4648#section-4 RF4648 section 4]] **/
  def decodeBase64[F[_]]:Pipe[F, Byte, Byte] =
    decodeBase64Raw(Alphabets.Base64)

  /** converts chunk of bytes to ByteVector **/
  def chunk2ByteVector(chunk: Chunk[Byte]):ByteVector = {
    chunk match  {
      case bv: ByteVectorChunk => bv.toByteVector
      case other =>
        val bs = other.toBytes
        ByteVector(bs.values, bs.offset, bs.size)
    }
  }

  /** converts ByteVector to chunk **/
  def byteVector2Chunk(bv: ByteVector): Chunk[Byte] = {
    ByteVectorChunk(bv)
  }

  /** helper to create named daemon thread factories **/
  def mkThreadFactory(name: String, daemon: Boolean, exitJvmOnFatalError: Boolean = true): ThreadFactory = {
    new ThreadFactory {
      val idx = new AtomicInteger(0)
      val defaultFactory = Executors.defaultThreadFactory()
      def newThread(r: Runnable): Thread = {
        val t = defaultFactory.newThread(r)
        t.setName(s"$name-${idx.incrementAndGet()}")
        t.setDaemon(daemon)
        t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
          def uncaughtException(t: Thread, e: Throwable): Unit = {
            ExecutionContext.defaultReporter(e)
            if (exitJvmOnFatalError) {
              e match {
                case NonFatal(_) => ()
                case fatal => System.exit(-1)
              }
            }
          }
        })
        t
      }
    }
  }

  def getCharset(ct: ContentType): Option[MIMECharset] = {
    ct match {
      case ContentType.TextContent(_, maybeCharset) => maybeCharset
      case _ => None
    }
  }

}