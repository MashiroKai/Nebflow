package nebscala.core

import cats.effect.IO

/** 提问项 */
case class AskItem(
  question: String,
  options: List[AskOption]
)

case class AskOption(
  label: String,
  description: Option[String] = None
)

/** 用户按 Esc 中断 */
class UserAbort extends Exception("UserAbort")

object AskUser:
  @volatile private var handler: Option[List[AskItem] => IO[List[String]]] = None

  def setHandler(h: List[AskItem] => IO[List[String]]): Unit =
    handler = Some(h)

  def ask(items: List[AskItem]): IO[List[String]] =
    handler match
      case Some(h) => h(items)
      case None => IO.raiseError(new Exception("AskUser not available in non-interactive mode"))

  def isInteractive: Boolean = handler.isDefined
