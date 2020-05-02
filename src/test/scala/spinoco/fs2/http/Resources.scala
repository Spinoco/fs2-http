package spinoco.fs2.http

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import cats.effect.{Blocker, Concurrent, ContextShift, IO, Resource, Timer}
import fs2.io.tcp.SocketGroup
import fs2.io.tls.TLSContext

import scala.concurrent.ExecutionContext


object Resources {

  implicit val _timer: Timer[IO] =IO.timer(ExecutionContext.Implicits.global)
  implicit val _contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  implicit val _concurrent: Concurrent[IO] = IO.ioConcurrentEffect(_contextShift)
  implicit val AG: AsynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool(util.mkThreadFactory("fs2-http-spec-AG", daemon = true)))

  val httpResources: Resource[IO, (SocketGroup, TLSContext)] = {
    Blocker[IO].flatMap { blocker =>
      SocketGroup(blocker).evalMap { group =>
        TLSContext.system(blocker).map(group -> _)
      }
    }
  }

}
