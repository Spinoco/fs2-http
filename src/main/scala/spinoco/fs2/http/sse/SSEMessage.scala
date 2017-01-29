package spinoco.fs2.http.sse

import scala.concurrent.duration.FiniteDuration

/**
  * SSE Message modeled after
  * https://www.w3.org/TR/2011/WD-eventsource-20111020/
  */
sealed trait SSEMessage


object SSEMessage {

  /**
    * SSE Data received
    * @param data     Data fields, received in singel event
    * @param event    Name of the event, if one provided, empty otherwise
    * @param id       Id of the event if one provided, empty otherwise.
    */
  case class SSEData(data: Seq[String], event: Option[String], id: Option[String]) extends SSEMessage
  case class SSERetry(retryIN: FiniteDuration) extends SSEMessage

}