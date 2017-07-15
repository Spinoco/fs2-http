package spinoco.fs2.http

import java.nio.channels.AsynchronousChannelGroup
import java.util.concurrent.Executors

import fs2.Scheduler

import scala.concurrent.ExecutionContext


object Resources {


  implicit val EC : ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8, util.mkThreadFactory("fs2-http-spec-ec", daemon = true)))

  implicit val Sch : Scheduler = Scheduler.fromScheduledExecutorService(Executors.newScheduledThreadPool(4, util.mkThreadFactory("fs2-http-spec-scheduler", daemon = true)))

  implicit val AG: AsynchronousChannelGroup = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool(util.mkThreadFactory("fs2-http-spec-AG", daemon = true)))


}
