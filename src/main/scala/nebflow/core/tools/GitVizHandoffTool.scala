package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.{AskHandoffPort}
import nebflow.core.ask.{AskGatewayServices, AskSessions}

import scala.util.control.NonFatal

/* ═══════════════════════════════════════════════════════════════════════════
 * 分支管理问答（gitviz）· 交办工具 `gitviz_handoff`
 *
 * 作者令（2026-11-19 18:41）：「它也是像 canvas ask 一样只问答，做事就转发给主会话，
 * 比如不仅可以上面多选进行合并，也可以跟这个agent说，它转交」+「两个入口收敛到同一个
 * 出口（不许两条各写一套）」。
 *
 * 本工具是「对话入口」的执行腿，与面板「多选 + 合并」钮**产出同一份
 * `AskHandoffPort.Request`**（面板腿 = `WsAskHandlers.handleAskHandoff` 的 `git` 子对象；
 * 工具腿 = 本文件），投递面同一个 `AskHandoffPort.deliver`、回执帧同一个
 * `AskTools.pushHandoffFrame`（`askHandoffAck`）⇒ 前端一个消费者。
 *
 * 🔴 它是**请求生成器**，不是执行器：不跑任何 git 命令、不碰 ref；投进主会话后由主会话
 *   按落支规则派发执行（写操作唯一执行者 = 主会话）。
 *
 * 为什么需要它（而不是让模型自由文本「说」就当转交）：gitviz 工具面里**故意拿掉了**
 * `send_message_to_main_chat`（那是文件 ask 的自由文本转交腿），所以 git 交办必须有自己
 * 的结构化腿 —— 四个字段是机器可读契约，不是应用户读排版。
 * ═══════════════════════════════════════════════════════════════════════════ */
object GitVizHandoffTool extends Tool:

  val Name: String = "gitviz_handoff"

  override def name: String = Name

  override def description: String =
    """Hand a BRANCH-MANAGEMENT task to the MAIN session — the branch-management ask session's only outbound channel, and the ONLY way to make something happen (this session has no git write ability). Use it whenever the user asks to merge / branch / delete / push / rebase / tag: fill the structured request — `branches` (exact branch names), `target` (which branch the operation lands on), `intent` (merge|branch|delete|push|rebase|tag), `reason` (why the main session must do it). After sending, tell the user it was handed over and that the main session will dispatch it by the landing rules — then keep answering questions in this window. Never claim you performed the operation yourself."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "branches" -> Json.obj(
        "type" -> "array".asJson,
        "description" -> "Exact branch names involved (e.g. [\"feat/a\",\"feat/b\"]). Required, at least one.".asJson,
        "items" -> Json.obj("type" -> "string".asJson)
      ),
      "target" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "The target line the operation lands on (e.g. \"feat/base\"). Required.".asJson
      ),
      "intent" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "One of: merge | branch | delete | push | rebase | tag. Default merge.".asJson
      ),
      "reason" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "Why the main session must do it (one sentence).".asJson
      ),
      "notes" -> Json.obj(
        "type" -> "array".asJson,
        "description" -> "Optional per-branch notes carried with the request.".asJson,
        "items" -> Json.obj("type" -> "string".asJson)
      )
    ),
    "required" -> Json.arr("branches".asJson, "target".asJson)
  )

  override def summarize(input: JsonObject): String =
    val branches = input("branches").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
    val target = input("target").flatMap(_.asString).getOrElse("?")
    s"gitviz_handoff(${branches.mkString("、")} → $target)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 160 then result.take(157) + "..." else result

  private def strs(input: JsonObject, key: String): List[String] =
    input(key).flatMap(_.asArray).getOrElse(Vector.empty).toList
      .flatMap(_.asString).map(_.trim).filter(_.nonEmpty)

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sid = ctx.sessionId.getOrElse("")
    val branches = strs(input, "branches")
    val target = input("target").flatMap(_.asString).map(_.trim).getOrElse("")
    val intentRaw = input("intent").flatMap(_.asString).map(_.trim.toLowerCase).getOrElse("")
    val intent =
      if Set("merge", "branch", "delete", "push", "rebase", "tag").contains(intentRaw) then intentRaw
      else "merge"
    val reason = input("reason").flatMap(_.asString).map(_.trim).getOrElse("")
    val notes = strs(input, "notes")

    // 身份双保险之工具内侧：非 gitviz ask 会话（含 REST 直调 / 其余一切身份）拒绝。
    if !AskSessions.isGitVizSession(sid) then
      IO.pure(Left(ToolError(
        "gitviz_handoff: permission denied — this tool is exclusive to branch-management ask sessions (ask-gv-*). (GITVIZ_HANDOFF_FORBIDDEN)")))
    else if branches.isEmpty then
      IO.pure(Left(ToolError("gitviz_handoff: `branches` must list at least one branch name.")))
    else if target.isEmpty then
      IO.pure(Left(ToolError("gitviz_handoff: `target` (the target line) is required.")))
    else if ctx.agentDef.isEmpty then
      IO.pure(Left(ToolError("gitviz_handoff: no agent context — handoff unavailable. (GITVIZ_HANDOFF_FORBIDDEN)")))
    else
      val bindingIO: IO[Option[nebflow.core.ask.AskBinding]] =
        AskGatewayServices.instance.map(_.registry) match
          case Some(r) => r.byId(sid)
          case None => IO.pure(None)

      val flow: IO[Either[ToolError, String]] = bindingIO.flatMap {
        case None =>
          IO.pure(Left(ToolError(
            "gitviz_handoff: this ask session has no project binding — open the Git 可视化 panel first. (GITVIZ_HANDOFF_NO_BINDING)")))
        case Some(binding) if !binding.isGitViz =>
          IO.pure(Left(ToolError(
            "gitviz_handoff: binding is not a branch-management scope. (GITVIZ_HANDOFF_NO_BINDING)")))
        case Some(binding) if binding.mainSessionId.trim.isEmpty =>
          IO.pure(Left(ToolError(
            "gitviz_handoff: main session not connected yet — reopen the 分支管理问答 dock, then try again. (GITVIZ_HANDOFF_NO_TARGET)")))
        case Some(binding) =>
          val now = System.currentTimeMillis()
          // 频控复用文件 ask 的同一条（每会话每分钟 1 条）：git 交办一次请求可能
          // 触发多条执行，刷屏面必须同档收敛。
          if !AskHandoffRateLimit.tryAcquire(sid, now) then
            IO.pure(Left(ToolError("稍后再转：转交频控为每分钟 1 条（GITVIZ_HANDOFF_RATE_LIMITED）。")))
          else
            val sep = "、"
            val branchList = branches.mkString(sep)
            val expectedText = target + " 上出现这些分支的操作结果（按落支规则）"
            val taskText =
              if intent == "merge" then "把 " + branchList + " 合进 " + target
              else intent + "：" + branchList + " → " + target
            val gitReq = AskHandoffPort.GitRequest(
              project = binding.fileName,
              branches = branches,
              target = target,
              intent = intent,
              notes = notes,
              source = "ask"
            )
            val req = AskHandoffPort.Request(
              askSessionId = sid,
              file = binding.file,
              fileName = binding.fileName,
              question = AskHandoffPort.handoffSummary(taskText, target :: branches, reason, expectedText),
              leg = "ask-gitviz",
              task = taskText,
              paths = target :: branches,
              reason = if reason.nonEmpty then reason else "分支管理问答 agent 只读，写操作唯一执行者 = 主会话",
              expected = expectedText,
              git = Some(gitReq)
            )
            val okText = "已把交办请求转给主会话：" + taskText +
              "（分支清单 " + branches.length.toString + " 支 · 目标线 " + target + " · 意图 " + intent +
              "）。它按落支规则派发执行；我仍在本窗继续回答分支问题。"
            AskHandoffPort
              .deliver(req)
              .flatMap {
                case right @ Right(_) =>
                  AskTools.pushHandoffFrame(sid, req, ctx, ok = true, error = "") *> IO.pure(right)
                case left @ Left(err) =>
                  AskTools.pushHandoffFrame(sid, req, ctx, ok = false, error = err.message) *> IO.pure(left)
              }
              .map(_.map(_ => okText))
      }
      flow.handleErrorWith(e => IO.pure(Left(ToolError(s"gitviz_handoff failed: ${e.getMessage}"))))
    end if
  end call
end GitVizHandoffTool
