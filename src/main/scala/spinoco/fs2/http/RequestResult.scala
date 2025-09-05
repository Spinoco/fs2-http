package spinoco.fs2.http

import com.comcast.ip4s.{IpAddress, SocketAddress}
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader}

/**
  * Represents the result of processing a server request, capturing both success and failure scenarios.
  */
sealed trait RequestResult[F[_]] {
  def remote: SocketAddress[IpAddress]
  def local: SocketAddress[IpAddress]
}

object RequestResult {
  /** Request was successfully processed and response was sent */
  case class Success[F[_]](
    request: HttpRequestHeader
    , response: HttpResponseHeader
    , remote: SocketAddress[IpAddress]
    , local: SocketAddress[IpAddress]
  ) extends RequestResult[F]
  
  /** Request parsing or timeout error occurred before service could be invoked */
  case class InvalidRequest[F[_]](
    error: Throwable
    , remote: SocketAddress[IpAddress]
    , local: SocketAddress[IpAddress]
  ) extends RequestResult[F]
  
  /** Service failed during request processing */
  case class ServiceFailed[F[_]](
    request: HttpRequestHeader
    , error: Throwable
    , remote: SocketAddress[IpAddress]
    , local: SocketAddress[IpAddress]
  ) extends RequestResult[F]
  
  /** Response streaming or encoding failed */
  case class ResponseFailed[F[_]](
    request: HttpRequestHeader
    , response: HttpResponseHeader
    , error: Throwable
    , remote: SocketAddress[IpAddress]
    , local: SocketAddress[IpAddress]
  ) extends RequestResult[F]
}