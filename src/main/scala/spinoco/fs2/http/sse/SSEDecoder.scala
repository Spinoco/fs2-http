package spinoco.fs2.http.sse

import scodec.Attempt


sealed trait SSEDecoder[A] { self =>

  def decode(in: SSEMessage): Attempt[A]

  def map[B](f: A => B): SSEDecoder[B] =
    SSEDecoder.instance { a => self.decode(a).map(f) }

}


object SSEDecoder {

  @inline def apply[A](implicit instance: SSEDecoder[A]): SSEDecoder[A] = instance

  def instance[A](f: SSEMessage => Attempt[A]): SSEDecoder[A] =
    new SSEDecoder[A] { def decode(in: SSEMessage): Attempt[A] = f(in) }

}
