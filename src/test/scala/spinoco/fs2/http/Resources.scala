package spinoco.fs2.http

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import cats.effect.{Blocker, Concurrent, ContextShift, IO, Timer}
import fs2.io.tcp.SocketGroup

import scala.concurrent.ExecutionContext


object Resources {

  implicit val _cxs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  implicit val _timer: Timer[IO] = IO.timer(ExecutionContext.Implicits.global)
  implicit val _concurrent: Concurrent[IO] = IO.ioConcurrentEffect(_cxs)
  lazy val AG: AsynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool(util.mkThreadFactory("fs2-http-spec-AG", daemon = true)))
  lazy val blocker: Blocker = Blocker.liftExecutionContext(ExecutionContext.global)
  implicit val SG: SocketGroup = new SocketGroup(AG, blocker)
}
