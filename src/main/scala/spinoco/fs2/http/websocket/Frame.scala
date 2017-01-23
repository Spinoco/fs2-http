package spinoco.fs2.http.websocket


sealed trait Frame[A] { self =>
  def a: A
  def isText: Boolean = self match {
    case Frame.Text(_) => true
    case _ => false
  }

  def isBinary = !isText
}


object Frame {

  case class Binary[A](a: A) extends Frame[A]

  case class Text[A](a: A) extends Frame[A]


}
