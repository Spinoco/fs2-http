package spinoco.fs2.http

import java.nio.channels.AsynchronousChannelGroup

import fs2.util.Async


package object client {

  /**
    * Creates a client that can be used to make http requests to servers
    */
  def apply[F[_]](implicit AG: AsynchronousChannelGroup, F: Async[F]):F[HttpClient[F]] = HttpClient.apply

}
