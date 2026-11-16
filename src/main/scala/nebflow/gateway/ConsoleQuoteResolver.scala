package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.msgtokens.MsgTokenRegistry
import nebflow.core.scheduler.ScheduledTaskStore
import nebflow.shared.NebflowLogger

/** Enrich author input without changing the stored transcript. */
object ConsoleQuoteResolver:
  private val logger = NebflowLogger.forName("nebflow.console-quotes")
  private val TodoPrefix = "(?s)^#([^\\n]+?)·([0-9a-f]{8})(?=\\s|$)".r
  val Threshold = 4000
  val SummaryLength = 500

  def resolve(text: String, tasks: ScheduledTaskStore, tokens: MsgTokenRegistry): IO[String] =
    val todo = TodoPrefix.findFirstMatchIn(text) match
      case None => IO.pure(text)
      case Some(m) =>
        val id = m.group(2)
        tasks.findTaskById(id).attempt.flatMap {
          case Right(Some(t)) if t.isTodo =>
            val body = t.detail.getOrElse(t.content)
            val label = m.group(1)
            val block = if body.length <= Threshold then
              s"[引用 · 待办 $label · $id]\n$body\n[引用结束]"
            else
              s"[引用 · 待办 $label · $id · 共 ${body.length} 字 · 已折叠；全文查询 Schedule {action:\"get\",id:\"$id\"}]\n${body.take(SummaryLength)}\n[摘要结束]"
            IO.pure(text + "\n\n" + block)
          case result =>
            val cause = result.fold(_ => "store error", _ => "not found or not a todo")
            logger.warn(s"CONSOLE-QUOTE-MISS todo=$id reason=$cause") *>
              IO.pure(text + s"\n\n[引用未能解析：待办 $id] 原因：$cause")
        }
    tokens.resolveForConsole(text).flatMap { resolved =>
      todo.map { withTodo =>
        if withTodo == text then resolved else resolved + withTodo.substring(text.length)
      }
    }
end ConsoleQuoteResolver
