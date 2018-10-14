package spinoco.fs2.http.internal

import fs2.Chunk.ByteVectorChunk
import fs2._
import scodec.bits.ByteVector

import spinoco.fs2.http.util.chunk2ByteVector

/**
  * Created by pach on 20/01/17.
  */
object ChunkedEncoding {


  /** decodes from the HTTP chunked encoding. After last chunk this terminates. Allows to specify max header size, after which this terminates
    * Please see https://en.wikipedia.org/wiki/Chunked_transfer_encoding for details
    */
  def decode[F[_] : RaiseThrowable](maxChunkHeaderSize:Int): Pipe[F, Byte, Byte] = {
    // on left reading the header of chunk (acting as buffer)
    // on right reading the chunk itself, and storing remaining bytes of the chunk
    def go(expect:Either[ByteVector,Long], in: Stream[F, Byte]): Pull[F, Byte, Unit] = {
      in.pull.uncons.flatMap {
        case None => Pull.done
        case Some((h, tl)) =>
          val bv = chunk2ByteVector(h)
          expect match {
            case Left(header) =>
              val nh = header ++ bv
              val endOfheader = nh.indexOfSlice(`\r\n`)
              if (endOfheader == 0) go(expect, Stream.chunk(ByteVectorChunk(bv.drop(`\r\n`.size))) ++ tl) //strip any leading crlf on header, as this starts with /r/n
              else if (endOfheader < 0 && nh.size > maxChunkHeaderSize) Pull.raiseError(new Throwable(s"Failed to get Chunk header. Size exceeds max($maxChunkHeaderSize) : ${nh.size} ${nh.decodeUtf8}"))
              else if (endOfheader < 0) go(Left(nh), tl)
              else {
                val (hdr,rem) = nh.splitAt(endOfheader + `\r\n`.size)
                readChunkedHeader(hdr.dropRight(`\r\n`.size)) match {
                  case None => Pull.raiseError(new Throwable(s"Failed to parse chunked header : ${hdr.decodeUtf8}"))
                  case Some(0) => Pull.done
                  case Some(sz) => go(Right(sz), Stream.chunk(ByteVectorChunk(rem)) ++ tl)
                }
              }

            case Right(remains) =>
              if (remains == bv.size) Pull.output(ByteVectorChunk(bv)) >> go(Left(ByteVector.empty), tl)
              else if (remains > bv.size) Pull.output(ByteVectorChunk(bv)) >> go(Right(remains - bv.size), tl)
              else {
                val (out,next) = bv.splitAt(remains.toInt)
                Pull.output(ByteVectorChunk(out)) >> go(Left(ByteVector.empty), Stream.chunk(ByteVectorChunk(next)) ++ tl)
              }
          }

      }
    }

    go(Left(ByteVector.empty), _) stream
  }


  private val lastChunk: Chunk[Byte] = ByteVectorChunk((ByteVector('0') ++ `\r\n` ++ `\r\n`).compact)

  /**
    * Encodes chunk of bytes to http chunked encoding.
    */
  def encode[F[_]]:Pipe[F,Byte,Byte] = {
    def encodeChunk(bv:ByteVector):Chunk[Byte] = {
      if (bv.isEmpty) Chunk.empty
      else ByteVectorChunk(ByteVector.view(bv.size.toHexString.toUpperCase.getBytes) ++ `\r\n` ++ bv ++ `\r\n` )
    }
    _.mapChunks { ch => encodeChunk(chunk2ByteVector(ch)) } ++ Stream.chunk(lastChunk)
  }



  /** yields to size of header in case the chunked header was succesfully parsed, else yields to None **/
  private def readChunkedHeader(hdr:ByteVector):Option[Long] = {
    hdr.decodeUtf8.right.toOption.flatMap { s =>
      val parts = s.split(';') // lets ignore any extensions
      if (parts.isEmpty) None
      else {
        try { Some(java.lang.Long.parseLong(parts(0).trim,16))}
        catch { case t: Throwable => None }
      }
    }
  }


}
