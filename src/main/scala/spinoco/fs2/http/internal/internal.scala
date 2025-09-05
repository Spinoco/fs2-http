package spinoco.fs2.http

import cats.effect.{Concurrent, Resource, Sync}
import com.comcast.ip4s.{Host, Port, SocketAddress}
import fs2.Stream._
import fs2.io.net.Socket
import fs2.io.net.tls.{TLSContext, TLSParameters, TLSSocket}
import fs2.{Chunk, RaiseThrowable, Stream, _}
import scodec.bits.ByteVector
import spinoco.protocol.http.header.{HttpHeader, `Transfer-Encoding`}
import spinoco.protocol.http.{HostPort, HttpScheme, Scheme}

import javax.net.ssl.SNIHostName
import scala.reflect.ClassTag


package object internal {

  val `\n` : ByteVector = ByteVector('\n')

  val `\r` : ByteVector = ByteVector('\r')

  val `\r\n`: ByteVector = ByteVector('\r','\n')

  val `\r\n\r\n` = (`\r\n` ++ `\r\n`).compact



  /** yields to true, if chunked encoding header is present **/
  def bodyIsChunked(headers:List[HttpHeader]):Boolean = {
    headers.exists {
      case `Transfer-Encoding`(encodings) => encodings.exists(_.equalsIgnoreCase("chunked"))
      case _ => false
    }
  }



  /**
    * From the stream of bytes this extracts Http Header and body part.
    */
  def httpHeaderAndBody[F[_]: RaiseThrowable](maxHeaderSize: Int): Pipe[F, Byte, (ByteVector, Stream[F, Byte])] = {
    def go(buff: ByteVector, in: Stream[F, Byte]): Pull[F, (ByteVector, Stream[F, Byte]), Unit] = {
      in.pull.uncons flatMap {
        case None =>
          Pull.raiseError[F](new Throwable(s"Incomplete Header received (sz = ${buff.size}): ${buff.decodeUtf8}"))
        case Some((chunk, tl)) =>
          val bv = spinoco.fs2.http.util.chunk2ByteVector(chunk)
          val all = buff ++ bv
          val idx = all.indexOfSlice(`\r\n\r\n`)
          if (idx < 0) {
            if (all.size > maxHeaderSize) Pull.raiseError[F](new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
            else go(all, tl)
          } else {
            val (h, t) = all.splitAt(idx)
            if (h.size > maxHeaderSize)  Pull.raiseError[F](new Throwable(s"Size of the header exceeded the limit of $maxHeaderSize (${all.size})"))
            else  Pull.output1((h, Stream.chunk(Chunk.byteVector(t.drop(`\r\n\r\n`.size))) ++ tl))

          }
      }
    }

    src => go(ByteVector.empty, src) stream
  }


  /** evaluates address from the host port and scheme, if this is a custom scheme we will default to port 8080**/
  def addressForRequest[F[_] : Sync](scheme: Scheme, host: HostPort):F[SocketAddress[Host]] = Sync[F].delay {
    val port = host.port.getOrElse {
      scheme match {
        case HttpScheme.HTTPS | HttpScheme.WSS => 443
        case HttpScheme.HTTP | HttpScheme.WS => 80
        case _ => 8080
      }
    }

    val addressHost = Host.fromString(host.host).getOrElse(throw new IllegalArgumentException(s"Invalid host: ${host.host}"))
    val addressPort = Port.fromInt(port).getOrElse(throw new IllegalArgumentException(s"Invalid port: $port"))

    SocketAddress(addressHost, addressPort)
  }

  /** swaps header `H` for new value. If header exists, it is discarded. Appends header to the end**/
  def swapHeader[H <: HttpHeader](header: H)(headers: List[HttpHeader])(implicit CT: ClassTag[H]) : List[HttpHeader] = {
    headers.filterNot(CT.runtimeClass.isInstance) :+ header
  }


  /** creates a function that lifts supplied socket to secure socket **/
  def clientLiftToSecure[F[_] : Concurrent : Sync : TLSContext](socket: Socket[F], server: HostPort): Resource[F, TLSSocket[F]] = {
    // Create TLS parameters with SNI configuration
    val tlsParams = TLSParameters(
      serverNames = Some(List(new SNIHostName(server.host)))
      , endpointIdentificationAlgorithm = Some("HTTPS")
      , useCipherSuitesOrder = true
    )

    // Upgrade the existing socket to TLS
    implicitly[TLSContext[F]].clientBuilder(socket)
      .withParameters(tlsParams)
      .build

  }

}
