package spinoco.fs2.http

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

import spinoco.protocol.mime.{ContentType, MIMECharset}
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

package object util {

  /** helper to create named daemon thread factories **/
  def mkThreadFactory(name: String, daemon: Boolean, exitJvmOnFatalError: Boolean = true): ThreadFactory = {
    new ThreadFactory {
      val idx = new AtomicInteger(0)
      val defaultFactory = Executors.defaultThreadFactory()
      def newThread(r: Runnable): Thread = {
        val t = defaultFactory.newThread(r)
        t.setName(s"$name-${idx.incrementAndGet()}")
        t.setDaemon(daemon)
        t.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
          def uncaughtException(t: Thread, e: Throwable): Unit = {
            ExecutionContext.defaultReporter(e)
            if (exitJvmOnFatalError) {
              e match {
                case NonFatal(_) => ()
                case fatal => System.exit(-1)
              }
            }
          }
        })
        t
      }
    }
  }

  def getCharset(ct: ContentType): Option[MIMECharset] = {
    ct match {
      case ContentType.TextContent(_, maybeCharset) => maybeCharset
      case _ => None
    }
  }

}