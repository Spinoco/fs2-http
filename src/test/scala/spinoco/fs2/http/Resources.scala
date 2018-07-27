package spinoco.fs2.http

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import cats.effect.{Concurrent, IO, Timer}

import scala.concurrent.ExecutionContext


object Resources {

  implicit val _timer: Timer[IO] =IO.timer(ExecutionContext.Implicits.global)
  implicit val _concurrent: Concurrent[IO] = IO.ioConcurrentEffect(_timer)
  implicit val AG: AsynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool(util.mkThreadFactory("fs2-http-spec-AG", daemon = true)))


}
