package nebflow.core.scheduler

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

import java.time.ZoneId

/**
 * Ask→待办 bridge（待办分家批 2026-10-30，作者令「待办接 AskUserQuestion：
 * Ask 产生的事项自动入待办，闭环 Ask→待办→用户答→销账」）：
 * InteractionHub 每挂起一张 AskUser 卡 ⇒ 同 root 会话的待办收件箱 upsert 一条
 * kind=todo 镜像条目（name=ask-<requestId> 稳定幂等键，重放/重连补发不叠加）；
 * 卡的任意终态（作答 / 用户取消 / 调用方撤回 / 源会话死亡 / 重启孤儿对账）
 * ⇒ 按名幂等销账。
 *
 * 语义红线：
 *   - 镜像条目的唯一销账路 = ask 终态（引擎侧单写者）。待办面 UI 对 ask- 前缀行
 *     不给打卡圆钮 / 取消 ×（打卡 = 留痕语义属于人建待办；ask 镜像行是跳转入口——
 *     点击跳回提问卡作答，答毕卡关 ⇒ 镜像随行销账，闭环）。
 *   - 每日 09:00 待办浮出汇总照收 ask 镜像：长挂未答 ⇒ 模型收到处置提示，可自行
 *     重问或撤销——闭环的模型侧半环，不替用户作答。
 *   - 全部写操作 best-effort（失败记 WARN 吞掉）：待办桥绝不影响提问链本身。
 */
final class AskTodoBridge(
  store: ScheduledTaskStore,
  service: Option[ScheduledTaskService],
  broadcast: Json => IO[Unit]
):
  private val logger = NebflowLogger.forName("nebflow.ask-todo")

  import AskTodoBridge.*

  /** 挂起即入：幂等 upsert（同 requestId 重放/多端重复 attach 收敛单份）。 */
  def attach(rootSessionId: String, requestId: String, question: String, sourceAgent: String): IO[Unit] =
    if rootSessionId.isEmpty || requestId.isEmpty then IO.unit
    else
      val todo = ScheduledTask.create(
        rootSessionId,
        todoContent(question, sourceAgent),
        ScheduledTask.nextTodoSurfaceAt(System.currentTimeMillis(), ZoneId.systemDefault()),
        None,
        None,
        Some(nameOf(requestId)),
        Some(ScheduledTask.KindTodo)
      )
      (for
        removed <- store.upsertTaskByName(todo)
        _ <- service.traverse_(_.notifyTaskChange())
        // 同名替换掉的旧行先行广播删除（跨会话安全，ScheduleTool 先例），再广播创建。
        _ <- removed.traverse_(old =>
          broadcast(
            Json.obj(
              "type" -> "scheduledTaskDeleted".asJson,
              "id" -> old.id.asJson,
              "sessionId" -> old.sessionId.asJson
            )
          )
        )
        _ <- broadcast(
          Json.obj(
            "type" -> "scheduledTaskCreated".asJson,
            "sessionId" -> rootSessionId.asJson,
            "task" -> Json.obj(
              "id" -> todo.id.asJson,
              "content" -> todo.content.asJson,
              "triggerAt" -> todo.triggerAt.asJson,
              "createdAt" -> todo.createdAt.asJson,
              "referencePath" -> Json.Null,
              "repeat" -> Json.Null,
              "name" -> todo.name.asJson,
              "kind" -> ScheduledTask.KindTodo.asJson
            )
          )
        )
      yield ()).handleErrorWith(e =>
        logger.warn(s"ask-todo attach failed requestId=$requestId root=$rootSessionId: ${e.getMessage}")
      )

  /** 终态销账：按稳定名跨会话幂等删除（行已不在 = no-op，重复收口安全）。 */
  def retire(requestId: String): IO[Unit] =
    if requestId.isEmpty then IO.unit
    else
      (for
        removed <- store.removeByName(nameOf(requestId), Some(ScheduledTask.KindTodo))
        _ <- service.traverse_(_.notifyTaskChange())
        _ <- removed.traverse_(old =>
          broadcast(
            Json.obj(
              "type" -> "scheduledTaskDeleted".asJson,
              "id" -> old.id.asJson,
              "sessionId" -> old.sessionId.asJson
            )
          )
        )
      yield ()).handleErrorWith(e =>
        logger.warn(s"ask-todo retire failed requestId=$requestId: ${e.getMessage}")
      )

end AskTodoBridge

object AskTodoBridge:

  /** ask 镜像条目的稳定 upsert 名前缀（前端据此前缀识别「跳转回答」行）。 */
  val NamePrefix: String = "ask-"

  def nameOf(requestId: String): String = NamePrefix + requestId

  /** requestId ← 待办 name 反解；非 ask- 前缀 ⇒ None（人建待办）。 */
  def requestIdOf(name: Option[String]): Option[String] =
    name.filter(_.startsWith(NamePrefix)).map(_.drop(NamePrefix.length)).filter(_.nonEmpty)

  /** 镜像条目文案：`[待答] 问题摘录（— 提问方）`。 */
  def todoContent(question: String, sourceAgent: String): String =
    val q = if question.nonEmpty then question else "-"
    val agent = Option(sourceAgent).map(_.trim).filter(_.nonEmpty)
    agent.fold(s"[待答] $q")(a => s"[待答] $q — $a")

end AskTodoBridge
