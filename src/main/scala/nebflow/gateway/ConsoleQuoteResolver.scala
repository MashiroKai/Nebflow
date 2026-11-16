package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
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

  /** A client snapshot is not a durable message identity; mint an independent lookup only for a complete long snapshot. */
  def structured(ref: Json, sessionId: String, tokens: MsgTokenRegistry): IO[String] =
    val cursor = ref.hcursor
    val content = cursor.downField("content")
    val body = content.downField("fullText").as[String].getOrElse("")
    val complete = content.downField("fullTextComplete").as[Boolean].getOrElse(false)
    val source = cursor.downField("source")
    val id = RefResolver.sanitize(source.downField("messageId").as[String].getOrElse("")).take(100)
    val name = RefResolver.sanitize(source.downField("senderName").as[String].getOrElse(""))
    val attachments = content.downField("attachments").as[List[Json]].getOrElse(Nil).take(8).map { a =>
      val c = a.hcursor
      val kind = RefResolver.sanitize(c.downField("type").as[String].getOrElse("file"))
      val name = RefResolver.sanitize(c.downField("name").as[String].getOrElse(""))
      val path = RefResolver.sanitize(c.downField("path").as[String].getOrElse(""))
      val local = scala.util.Try(java.nio.file.Path.of(path)).toOption.filter(_.isAbsolute).flatMap { p =>
        scala.util.Try(p.toRealPath()).toOption.filter(p => java.nio.file.Files.isRegularFile(p) && java.nio.file.Files.isReadable(p))
      }
      val location = local.fold("本机不可用或跨设备；请重新提供原图")(
        p => s"本机可读绝对路径: $p（客户端声明，来源和内容未验证）"
      )
      s"[引用附件 · $kind · $name · $location · 原始媒体未随引用注入]"
    }.mkString("\n")
    val position = content.downField("itemIndex").as[Int].toOption.filter(_ >= 0).map { index =>
      val total = content.downField("itemTotal").as[Int].toOption.filter(_ > index).fold("")(n => s"/$n")
      s" · 第 ${index + 1}$total 项"
    }.getOrElse("")
    val suffix = if attachments.nonEmpty then "\n" + attachments else ""
    val header = s"[引用 · 消息 · $name$position]"
    if body.isEmpty || !complete then
      logger.warn(s"CONSOLE-QUOTE-MISS structured id=$id reason=missing-or-unverified-snapshot") *>
        IO.pure(s"[引用未能解析：聊天消息 $id] 原因：正文缺失或旧版快照不保证完整$suffix")
    else if body.length <= Threshold then IO.pure(s"$header\n$body$suffix")
    else
      tokens.mint(Some(sessionId).filter(_.nonEmpty), body).flatMap {
        case Some(mapping) =>
          val core = s"${mapping.sid8}-${f"${mapping.seq}%06d"}"
          IO.pure(s"$header\n[共 ${body.length} 字 · 已折叠；全文查询 GET /api/sessions/msg-token/$core]\n${body.take(SummaryLength)}\n[摘要结束]$suffix")
        case None =>
          logger.warn(s"CONSOLE-QUOTE-MISS structured id=$id reason=snapshot-mint-failed") *>
            IO.pure(s"[引用未能解析：聊天消息 $id] 原因：全文句柄创建失败$suffix")
      }
end ConsoleQuoteResolver
