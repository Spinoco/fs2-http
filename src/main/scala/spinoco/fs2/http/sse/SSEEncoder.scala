package spinoco.fs2.http.sse

import scodec.Attempt


sealed trait SSEEncoder[A] { self =>

  def encode(a: A) : Attempt[SSEMessage]

  def mapIn[B](f: B => A): SSEEncoder[B] =
    SSEEncoder.instance { b => self.encode(f(b)) }

}


object SSEEncoder {

  @inline def apply[A](implicit instance: SSEEncoder[A]): SSEEncoder[A] = instance

  def instance[A](f: A => Attempt[SSEMessage]): SSEEncoder[A] =
    new SSEEncoder[A] { def encode(a: A): Attempt[SSEMessage] = f(a) }

  /** simple encoder of string messages **/
  val stringEncoder: SSEEncoder[String] =
    SSEEncoder.instance { s => Attempt.successful(SSEMessage.SSEData(Vector(s), None, None)) }

}
