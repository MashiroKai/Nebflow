package nebflow.core

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

/** 提问项 */
case class AskItem(
  question: String,
  options: List[AskOption],
  allowOther: Boolean = true
)

case class AskOption(
  label: String,
  description: Option[String] = None
)

/** 用户按 Esc 中断 */
class UserAbort extends Exception("UserAbort")

object AskUser:
  private val handler = new AtomicReference[Option[List[AskItem] => IO[List[String]]]](None)

  def setHandler(h: List[AskItem] => IO[List[String]]): Unit =
    handler.set(Some(h))

  def clearHandler(): Unit =
    handler.set(None)

  def ask(items: List[AskItem]): IO[List[String]] =
    handler.get() match
      case Some(h) => h(items)
      case None => IO.raiseError(new Exception("AskUser not available in non-interactive mode"))

  def isInteractive: Boolean = handler.get().isDefined
