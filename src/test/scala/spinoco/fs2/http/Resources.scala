package spinoco.fs2.http

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import fs2.{Scheduler, Strategy}


object Resources {

  val ES = Executors.newCachedThreadPool(Strategy.daemonThreadFactory("AG"))

  implicit val S = Strategy.fromExecutor(ES)

  implicit val Sch = Scheduler.fromScheduledExecutorService(Executors.newScheduledThreadPool(4, Strategy.daemonThreadFactory("S")))

  implicit val AG = AsynchronousChannelGroup.withThreadPool(ES)


}
