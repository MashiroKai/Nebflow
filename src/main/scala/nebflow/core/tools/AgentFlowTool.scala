package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*

/**
 * AgentFlow —— 内联 DAG 便捷面（N1 复名＋非阻塞薄壳化；v3 §5 S4 荐案候过目，
 * 复名连带细案沿 v2 §3 原样引用＝案 A「仅 legacy 形态拒收」）。
 *
 * == 薄壳化（v3 §5：一个执行引擎，两个提交面）==
 * AgentFlow(steps=[...]) ＝ **同闸同底座**的 ChainWrite 便捷入口：steps 映射
 * [[ChainWriteTool.ChainNode]]（id/task/deps 逐字；name/description 缺省）→
 * [[ChainWriteTool.registerChain]]（自动链号 `flow-<8hex>`）→ 整链校验 →
 * 原子落盘 → 入口节点放行（后台 fork）→ ACK 即返（非阻塞，S2）。步会话从
 * BlockingSubagent 步子会话改为节点 agent 会话（NodeRunner·leaf；15min 步硬顶
 * 取消，兜底 = TaskStuckWatcher 零活动 + settle watchdog——v3 §5 语义差面，
 * 随刀报申报）。执行体（rounds 轮次调度 + BlockingSubagent 桥）随薄壳化删除
 * （死代码零保留）。
 *
 * == 案 A legacy guard（v2 §3.3 判据 G1）==
 * `steps` 键缺席 ∧ (`task` ∨ `project` 键在场) → 唯一动作 = 错误文案
 * （[[LegacyShapeCode]]），零执行零账行。旧派发意图自纠改走 Delegate。
 *
 * == 授权面（A4 双保险）==
 * 挂载面 = RootExclusiveTools 防逃逸集（随名）＋ NebflowFixedTools（真实授能）；
 * 工具内第二保险 = delegate 会话 + 宿主在册（fail-closed）——与 ChainWrite 同款
 * 「挂载面 + 工具内身份拒绝」两层收口。
 */
object AgentFlowTool extends Tool:
  val name = "AgentFlow"

  /** 规模上限（ChainWrite 同一上限单源——两提交面防误用对称；v2 §6.A/§6.B 家族口径）。 */
  private val MaxSteps: Int = ChainWriteTool.MaxNodes

  /** 案 A legacy guard 错误码（spec 断言单点，禁字面量散落）。 */
  val LegacyShapeCode: String = "AGENTFLOW_LEGACY_SHAPE"

  val description =
    """Submit a DAG of steps and run it in the background: independent steps run CONCURRENTLY, each in a fresh sub-agent session, and every step receives its upstream results (under `=== Node <name> ===` headers) in its brief.

**When to use:** genuinely parallelizable multi-part work (survey A + implement B + test C). A short sequential task is faster without it; a task needing your own judgment between phases is better done by you directly.

**Parameters:**
- `steps` (required): array of {id, task, deps} — id: short unique token; task: self-contained brief for that step (absolute paths; what done looks like); deps: ids that must finish first (omit for entry steps).
- `description` (optional): short UI label.

**Semantics (non-blocking):** the whole chain is validated first (unique ids, deps closure, acyclic); if ANY check fails the call is rejected and NOTHING runs. On success you get an ack with the chain id immediately — your turn continues; do NOT wait idle and do NOT re-dispatch the same work. End steps' results arrive in this session as node completion notifications (one per end step, upstream results attached).

**Merge discipline:** steps must NOT arrange merges of any produced branches — branch/merge discipline follows the delegate-layer arbiter; report readiness instead.

**Family:** `Subagent` = one blocking read-only recon call (this turn, result as the tool result); this tool = parallel multi-step execution in the background; `Delegate` = one background task on a full executor (branch/merge machinery); `ChainWrite` = persistent registered chains with per-node editing and id+name addressing (the ledger-backed sibling of this tool — same gate, same engine, more control). Pick by scope, not by habit."""

  def inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "steps" -> io.circe.Json.obj(
          "type" -> "array".asJson,
          "description" -> "DAG steps: [{\"id\": \"survey\", \"task\": \"...\", \"deps\": [\"...\"]}]. deps omitted = entry step.".asJson,
          "items" -> io.circe.Json.obj(
            "type" -> "object".asJson,
            "properties" -> io.circe.Json.obj(
              "id" -> io.circe.Json.obj("type" -> "string".asJson),
              "task" -> io.circe.Json.obj("type" -> "string".asJson),
              "deps" -> io.circe.Json
                .obj("type" -> "array".asJson, "items" -> io.circe.Json.obj("type" -> "string".asJson))
            ),
            "required" -> io.circe.Json.arr("id".asJson, "task".asJson)
          )
        ),
        "description" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Short label for the flow (session naming).".asJson
        )
      ),
      "required" -> io.circe.Json.arr("steps".asJson)
    )
  )

  final case class Step(id: String, task: String, deps: List[String])

  /** 案 A guard 判据 G1（纯谓词，spec 直驱）：steps 键缺席 ∧ (task ∨ project 键在场)。 */
  private[tools] def legacyGuardHit(input: JsonObject): Boolean =
    input("steps").isEmpty && (input.contains("task") || input.contains("project"))

  /** 案 A guard 文案（v2 §6.G 候选逐字；触发 = 唯一动作，零执行零账行）。 */
  private[tools] def legacyGuardError: ToolError =
    ToolError(
      """The legacy AgentFlow dispatch form is not available — dispatch goes through `Delegate(task=<brief>, project=<optional mounted project>)`. The receipt carries the task number (task #N) and the continuation address `delegate:<id>`; supplements/corrections go through `Mail(address="delegate:<id>", message=...)`. (""" + LegacyShapeCode + """)"""
    )

  /** 形态解析 + 结构校验（纯函数，spec 直驱）：非空、id 唯一、deps 存在、有环检测。 */
  private[tools] def parseSteps(input: JsonObject): Either[ToolError, List[Step]] =
    val parsed: Either[ToolError, List[Step]] = input("steps").flatMap(_.asArray) match
      case None => Left(ToolError("Missing required parameter: steps (an array of {id, task, deps})."))
      case Some(arr) =>
        arr.foldLeft[Either[ToolError, List[Step]]](Right(Nil)) { (acc, item) =>
          for
            steps <- acc
            obj <- item.asObject.toRight(ToolError("each step must be an object {id, task, deps}."))
            id <- obj("id").flatMap(_.asString).toRight(ToolError("each step needs an \"id\" string."))
            task <- obj("task").flatMap(_.asString).toRight(ToolError(s"step '$id' needs a \"task\" brief."))
            deps = obj("deps").flatMap(_.asArray).map(_.flatMap(_.asString).toList).getOrElse(Nil)
          yield steps :+ Step(id.trim, task, deps.map(_.trim).filter(_.nonEmpty))
        }
    parsed.flatMap { steps =>
      if steps.isEmpty then Left(ToolError(s"steps is empty — nothing to run. (${ChainWriteTool.Codes.Empty})"))
      else if steps.size > MaxSteps then
        Left(
          ToolError(
            s"Too many steps (${steps.size}) — the cap is $MaxSteps. Split into multiple AgentFlow calls. " +
              s"(${ChainWriteTool.Codes.TooMany})"
          )
        )
      else
        val ids = steps.map(_.id)
        val dup = ids.diff(ids.distinct)
        if dup.nonEmpty then
          Left(ToolError(s"Duplicate step id(s): ${dup.mkString(", ")}. (${ChainWriteTool.Codes.DuplicateId})"))
        else
          val known = ids.toSet
          val unknownDeps = steps.flatMap(_.deps.filterNot(known.contains)).distinct
          if unknownDeps.nonEmpty then
            Left(
              ToolError(
                s"deps reference unknown step id(s): ${unknownDeps.mkString(", ")}. (${ChainWriteTool.Codes.DepsUnknown})"
              )
            )
          else if hasCycle(steps) then
            Left(ToolError(s"Cycle detected in deps — the flow must be a DAG. (${ChainWriteTool.Codes.Cycle})"))
          else Right(steps)
    }

  end parseSteps

  /** Kahn 判环（纯函数）。 */
  private[tools] def hasCycle(steps: List[Step]): Boolean =
    val depsOf = steps.map(s => s.id -> s.deps.toSet).toMap
    @scala.annotation.tailrec
    def go(remaining: Set[String], resolved: Set[String]): Boolean =
      if remaining.isEmpty then false
      else
        val ready = remaining.filter(id => depsOf(id).forall(resolved.contains))
        if ready.isEmpty then true // 有剩余但无可入度者 ⇒ 环
        else go(remaining -- ready, resolved ++ ready)
    go(steps.map(_.id).toSet, Set.empty)

  /** 非 delegate 会话 fail-closed（工具内第二保险——挂载面之外的调用一概拒）。 */
  private[tools] def notDelegateError: String =
    "AgentFlow runs steps inside the calling DELEGATE's own scope flow map — it is not available outside a " +
      "delegate session. Nebula dispatches work with Delegate(task=<brief>); background single tasks go to " +
      "Delegate, read-only recon goes to Subagent."

  def summarize(input: JsonObject): String =
    val n = input("steps").flatMap(_.asArray).map(_.size).getOrElse(0)
    val label = input("description").flatMap(_.asString).getOrElse("")
    s"AgentFlow(${n} steps${if label.nonEmpty then s": $label" else ""})"

  def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // 案 A guard 首站（判据 G1）——命中即错误文案，零执行零账行。
    if legacyGuardHit(input) then IO.pure(Left(legacyGuardError))
    // 双模式批（2026-10-28 · 设计 §4.2 · 双保险第二险）：kernel 腿 delegate 无
    // AgentFlow（编排面整体退场；dispatcher 腿保留本件）。挂载面已摘，本闸防逃逸。
    else if ctx.sessionId.flatMap(sid => nebflow.core.delegate.DelegateRegistry.modeFaceOf(Some(sid)))
      .contains(nebflow.core.delegate.DualModeConfig.ModeKernel)
    then
      IO.pure(
        Left(
          ToolError(
            "AgentFlow is not available in kernel-mode delegate sessions (minimal executor — no orchestration face). (DUAL_MODE_FACE_REMOVED)"
          )
        )
      )
    else
      parseSteps(input) match
        case Left(err) => IO.pure(Left(err))
        case Right(steps) =>
          // 薄壳放行：delegate 会话 + 宿主在册 → steps 映射整链登记（自动链号）→
          // ChainWrite.registerChain 单点（校验/落库/放行/ACK 全套共用，零第二实现）。
          if !nebflow.core.delegate.DelegateSessions.isDelegateSession(ctx.sessionId) then
            IO.pure(Left(ToolError(notDelegateError)))
          else
            val nodes = steps.map(s => ChainWriteTool.ChainNode(s.id, None, s.task, s.deps, None))
            nebflow.core.project.DelegateFlowHosts.get(ctx.sessionId.getOrElse("")).flatMap {
              case None =>
                IO.pure(Left(ToolError(NodeTools.scopeUnavailableError(ctx.sessionId.getOrElse("")))))
              case Some(h) =>
                ChainWriteTool.registerChain(h.projectRuntime, nodes, chainName = None).flatMap {
                  case Left(err) => IO.pure(Left(err))
                  case Right(ok) =>
                    // G1 编排模式惰性升级（delegate-ui 批 2026-10-08）：flow 结构确立
                    // （链登记成功）⇒ orchestrationMode 升 `agentflow`（值域顶，只上不
                    // 下；升级器自带记录闸与幂等秩守卫，失败不阻断本次链登记回执）。
                    IO.blocking(
                      nebflow.core.delegate.DelegateRegistry
                        .upgradeOrchestrationMode(
                          ctx.sessionId.getOrElse(""),
                          nebflow.core.delegate.DelegateRegistry.ModeAgentflow
                        )
                    ).void
                      .as(Right(ok))
                }
            }

end AgentFlowTool
