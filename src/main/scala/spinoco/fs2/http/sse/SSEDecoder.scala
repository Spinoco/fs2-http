package spinoco.fs2.http.sse

import scodec.Attempt


sealed trait SSEDecoder[A] { self =>

  def decode(in: SSEMessage): Attempt[A]

  def map[B](f: A => B): SSEDecoder[B] =
    SSEDecoder { a => self.decode(a).map(f) }

}


object SSEDecoder {

  def apply[A](f: SSEMessage => Attempt[A]): SSEDecoder[A] =
    new SSEDecoder[A] { def decode(in: SSEMessage): Attempt[A] = f(in) }

}
