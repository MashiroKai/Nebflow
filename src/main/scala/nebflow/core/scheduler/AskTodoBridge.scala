package nebflow.core.scheduler

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

import java.time.ZoneId

/**
 * Ask→待办 bridge（待办分家批 2026-10-30，作者令「待办接 AskUserQuestion：
 * Ask 产生的事项自动入待办，闭环 Ask→待办→用户答→销账」；
 * 待办体系改版批 2026-10-31 扩面：Permission / McpPermission 卡同步镜像
 * （perm-<requestId>，待批准组））：
 * InteractionHub 每挂起一张 AskUser / Permission / McpPermission 卡 ⇒ 同 root
 * 会话的待办收件箱 upsert 一条 kind=todo 镜像条目（name=<前缀><requestId> 稳定
 * 幂等键，重放/重连补发不叠加）；卡的任意终态（作答 / 用户取消 / 调用方撤回 /
 * 源会话死亡 / 重启孤儿对账）⇒ 按 requestId 幂等销账（ask- 与 perm- 两个前缀
 * 一并清，单边缺失 = no-op）。
 *
 * 语义红线：
 *   - 镜像条目的唯一销账路 = 卡终态（引擎侧单写者）。待办面 UI 对 ask- / perm-
 *     前缀行不给打卡圆钮 / 取消 ×（打卡 = 留痕语义属于人建待办；镜像行是跳转入口——
 *     点击跳回卡片处理，卡关 ⇒ 镜像随行销账，闭环）。
 *   - 每日 09:00 待办浮出汇总照收镜像：长挂未处理 ⇒ 模型收到处置提示，可自行
 *     重问或撤销——闭环的模型侧半环，不替用户作答/批。
 *   - 全部写操作 best-effort（失败记 WARN 吞掉）：待办桥绝不影响提问/批准链本身。
 */
final class AskTodoBridge(
  store: ScheduledTaskStore,
  service: Option[ScheduledTaskService],
  broadcast: Json => IO[Unit]
):
  private val logger = NebflowLogger.forName("nebflow.ask-todo")

  import AskTodoBridge.*

  /** 挂起即入：幂等 upsert（同 requestId 重放/多端重复 attach 收敛单份）。
   *  改版批追加令：project = 事项归属标签（取 askUser payload 的 project 字段，
   *  AgentActor buildAskUserJson 既有来源）。 */
  def attach(
    rootSessionId: String,
    requestId: String,
    question: String,
    sourceAgent: String,
    project: Option[String] = None
  ): IO[Unit] =
    upsert(rootSessionId, requestId, nameOf(requestId), todoContent(question, sourceAgent), project, "attach")

  /**
   * 待批准镜像（改版批 2026-10-31）：Permission / McpPermission 卡挂起 ⇒ perm-
   * 前缀镜像。content 从卡 payload 提取（toolName + summary 两族字段同构覆盖
   * 内置 permission 与 mcpPermission，见 AgentSessionExecution permJson /
   * McpToolGate.cardPayload）。project 取 payload 的 project/sourceFlow 字段。
   */
  def attachPermission(
    rootSessionId: String,
    requestId: String,
    payload: Json,
    sourceAgent: String
  ): IO[Unit] =
    upsert(
      rootSessionId, requestId, permNameOf(requestId),
      permContent(payload, sourceAgent), payloadProject(payload), "attach-perm"
    )

  private def upsert(
    rootSessionId: String,
    requestId: String,
    name: String,
    content: String,
    project: Option[String],
    logTag: String
  ): IO[Unit] =
    if rootSessionId.isEmpty || requestId.isEmpty then IO.unit
    else
      val todo = ScheduledTask.create(
        rootSessionId,
        content,
        ScheduledTask.nextTodoSurfaceAt(System.currentTimeMillis(), ZoneId.systemDefault()),
        None,
        None,
        Some(name),
        Some(ScheduledTask.KindTodo),
        None,
        project
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
              "kind" -> ScheduledTask.KindTodo.asJson,
              "project" -> todo.project.asJson
            )
          )
        )
      yield ()).handleErrorWith(e =>
        logger.warn(s"ask-todo $logTag failed requestId=$requestId root=$rootSessionId: ${e.getMessage}")
      )

  /**
   * 终态销账：按 requestId 同时清 ask- 与 perm- 两个前缀（幂等；单边缺失 =
   * no-op，重复收口安全）。四个终态路 + 重启孤儿对账共用这个单点——permission
   * 卡不需要各自记前缀。
   */
  def retire(requestId: String): IO[Unit] =
    if requestId.isEmpty then IO.unit
    else
      (for
        removedAsk <- store.removeByName(nameOf(requestId), Some(ScheduledTask.KindTodo))
        removedPerm <- store.removeByName(permNameOf(requestId), Some(ScheduledTask.KindTodo))
        removed = removedAsk ++ removedPerm
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

  /** 待批准镜像条目的稳定 upsert 名前缀（改版批 2026-10-31）。 */
  val PermNamePrefix: String = "perm-"

  def nameOf(requestId: String): String = NamePrefix + requestId

  def permNameOf(requestId: String): String = PermNamePrefix + requestId

  /** requestId ← 待办 name 反解（ask- 前缀）；非 ask- 前缀 ⇒ None（人建待办）。 */
  def requestIdOf(name: Option[String]): Option[String] =
    name.filter(_.startsWith(NamePrefix)).map(_.drop(NamePrefix.length)).filter(_.nonEmpty)

  /** requestId ← 待办 name 反解（perm- 前缀）；非 perm- 前缀 ⇒ None。 */
  def permRequestIdOf(name: Option[String]): Option[String] =
    name.filter(_.startsWith(PermNamePrefix)).map(_.drop(PermNamePrefix.length)).filter(_.nonEmpty)

  /** 镜像条目文案：`[待答] 问题摘录（— 提问方）`。 */
  def todoContent(question: String, sourceAgent: String): String =
    val q = if question.nonEmpty then question else "-"
    val agent = Option(sourceAgent).map(_.trim).filter(_.nonEmpty)
    agent.fold(s"[待答] $q")(a => s"[待答] $q — $a")

  /** 镜像 project 提取单点：优先 payload.project（askUser 卡），回落 sourceFlow
   *  （permission enrichment，AgentSessionExecution）。空串视同未分组。 */
  def payloadProject(payload: Json): Option[String] =
    List("project", "sourceFlow").toList
      .flatMap(k => payload.hcursor.downField(k).as[String].toOption)
      .map(_.trim).filter(_.nonEmpty)
      .headOption

  /**
   * 待批准镜像文案（改版批 2026-10-31）：`[待批准] <toolName> · <summary>（— <agent>）`。
   * payload 字段提取覆盖两条 producer 的同构字段——内置 permission（AgentSessionExecution
   * permJson：toolName/summary）与 mcpPermission（McpToolGate.cardPayload：toolName/
   * summary/serverId）；缺字段时回落顺序 toolName → serverId → server → "approval"，
   * summary 缺省不拼接。
   */
  def permContent(payload: Json, sourceAgent: String): String =
    def field(keys: String*): Option[String] =
      keys.toList.flatMap(k => payload.hcursor.downField(k).as[String].toOption).headOption
        .map(_.trim).filter(_.nonEmpty)
    val tool = field("toolName", "serverId", "server", "plugin").getOrElse("approval")
    val summary = field("summary", "inputSummary")
    val body = summary.fold(tool)(s => s"$tool · $s")
    val agent = Option(sourceAgent).map(_.trim).filter(_.nonEmpty)
    agent.fold(s"[待批准] $body")(a => s"[待批准] $body — $a")

end AskTodoBridge
