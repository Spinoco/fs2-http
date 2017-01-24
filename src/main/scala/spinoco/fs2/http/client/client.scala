package spinoco.fs2.http

import java.nio.channels.AsynchronousChannelGroup

import fs2.util.Async
import scodec.Codec
import spinoco.protocol.http.{HttpRequestHeader, HttpResponseHeader}
import spinoco.protocol.http.codec.{HttpRequestHeaderCodec, HttpResponseHeaderCodec}


package object client {

  /**
    * Creates a client that can be used to make http requests to servers
    *
    * @param requestCodec    Codec used to decode request header
    * @param responseCodec   Codec used to encode response header
    */
  def apply[F[_]](
     requestCodec: Codec[HttpRequestHeader] = HttpRequestHeaderCodec.defaultCodec
     , responseCodec: Codec[HttpResponseHeader] = HttpResponseHeaderCodec.defaultCodec
   )(implicit AG: AsynchronousChannelGroup, F: Async[F]):F[HttpClient[F]] =
    HttpClient(requestCodec, responseCodec)

}
