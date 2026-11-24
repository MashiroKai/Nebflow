package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*
import nebflow.pilogic.*
import nebflow.pilogic.{ToolCall as PiCall, ToolExecutionResult as PiResult}
import nebflow.shared.*
import nebflow.shared.ToolCall

/**
 * pi-logic 接线层（轮 A，L-6=逐位点织入；计划件 [[PiLoopWiring-PLAN]] / docs 计划全文）。
 *
 * == 定位 ==
 * 现主链（Actor 消息循环）仍是驱动者；本件把 pilogic 的语义对象在**真实时刻、真实地点**
 * 织入：事件 lattice（② PiBridge）、run 账本（③ RunLedger/L-8）、崩溃兜底（④ crashTail）、
 * 序域/策略位/翻译表/分类器/hook 映射/双适配器（接线点 2/3/4/5/8/9/10）。
 *
 * == 纪律（作者 11-04 20:06「可信」双定义） ==
 * - 上层单向引用：本件 import pilogic（叶子）+ 既有持久化面（logAgentEvent JSONL 主档、
 *   db appendEvent 影子）——**不自造持久化/锁/恢复原语**；
 * - 零 object 可变态：PiBridge 无 mutable 字段，run 账本以 RunLedger 不可变值经
 *   AgentState.pilogicLedger 值传递（病灶 #1 免疫纪律延伸到接线层）；
 * - 行为等价：新增面仅 `pilogic-*` 审计行与 KernelEvent 影子写（best-effort），
 *   既有 WS 帧/日志/消息流逐字节不变。
 */
object PiLoopWiring:

  // ── ③ run 边界 + L-8 账本（AgentState.pilogicLedger 值传递）────────────────

  /**
   * run 边界判定：ledger.runKey 与宿主 loopTurnKey 同域——真实 turn 唤醒 +1、
   * 续轮/续跑不递增（AgentState.withNextLoopTurn 注释口径）⇒ key 不等或 run 已收口
   * （runId 空）= 新 run。
   */
  final case class RunBoundary(fresh: Boolean, ledger: RunLedger, runId: String)

  def runBoundary(prev: RunLedger, loopTurnKey: Long, startTurn: Long): RunBoundary =
    if prev.runKey == loopTurnKey && prev.runId.nonEmpty then RunBoundary(false, prev, prev.runId)
    else
      val runId = s"r-${java.util.UUID.randomUUID().toString.take(8)}"
      RunBoundary(true, RunLedger.start(loopTurnKey, runId, prev, startTurn), runId)

  // ── ② PiBridge：EventSink[IO] → 审计行 + KernelEvent 影子（audit 面生效；
  //     ws 面翻译见 wsFrame，发帧闸门关——双发=帧重复=行为变化，割接轮开）──────

  /**
   * KernelEvent 影子写（db 可信基复用，C5 先例逐字同形）：DbHost.peek 只查不开，
   * best-effort——影子失败 WARN 不上抛、不影响主档（logAgentEvent 已落）。
   */
  def dbShadow(sessionId: String)(e: KernelEvent): Unit =
    if sessionId.nonEmpty then
      import cats.effect.unsafe.implicits.global
      nebflow.db.DbHost.peek(nebflow.shared.PathUtil.dataRoot.toNIO).foreach { db =>
        try
          val dedupKey = s"$sessionId:${e.eventType}:${e.seqInSession}"
          db.appendEvent(e.asJson, Some(dedupKey)).unsafeRunSync()
        catch
          case err: Throwable =>
            NebflowLogger
              .forName("nebflow.agent.pilogic")
              .warnSync(s"db event shadow append failed (session=$sessionId type=${e.eventType}): ${err.getMessage}")
      }

  /**
   * 事件→审计行子集（防洪口径）：Run/Turn/Message/ToolExecution 的起止落行、
   * TurnEnded/RunEnded/HookErrored/BudgetReported 落行；逐增量（MessageDelta /
   * ToolExecutionDelta）不落行——既有 SSE/WS 腿已逐块记录，无信息损失。
   */
  final class PiBridge(
    audit: (String, String) => Unit,
    shadow: KernelEvent => Unit,
    sessionId: String,
    runId: String,
    startTurn: Long
  ) extends EventSink[IO]:

    /** L-8 生产者侧映射：MessageSeq 每 run 重置、run 内单调（dispatch 轮号相对 run 起点）。 */
    def messageSeq(turnId: Long): MessageSeq = MessageSeq((turnId - startTurn + 1L).max(1L))

    private def cap(s: String): String = if s.length <= 160 then s else s.take(160) + "…"

    def emitSync(e: LoopEvent): Unit = e match
      case LoopEvent.RunStarted => audit("pilogic-run-started", s"runId=$runId")
      case LoopEvent.TurnStarted(t) => audit("pilogic-turn-started", s"turn=${t.value} runId=$runId")
      case LoopEvent.MessageStarted(seq) => audit("pilogic-message-started", s"seq=${seq.value} runId=$runId")
      case LoopEvent.MessageDelta(_, _) => () // 防洪：逐块不落行
      case LoopEvent.MessageEnded(seq, m) =>
        audit(
          "pilogic-message-ended",
          s"seq=${seq.value} role=${m.role.toString} len=${cap(m.content.noSpaces).length}"
        )
      case LoopEvent.ToolExecutionStarted(id, name) =>
        audit("pilogic-tool-started", s"callId=$id name=$name runId=$runId")
      case LoopEvent.ToolExecutionDelta(_, _) => () // 防洪：进度不落行
      case LoopEvent.ToolExecutionEnded(id, _, isError) =>
        audit("pilogic-tool-ended", s"callId=$id isError=$isError runId=$runId")
      case LoopEvent.TurnEnded(t, msg, results, outcome) =>
        audit(
          "pilogic-turn-ended",
          s"turn=${t.value} outcome=${outcome.toString} results=${results.size} " +
            s"textLen=${msg.map(m => cap(m.content.noSpaces).length).getOrElse(0)} runId=$runId"
        )
      case LoopEvent.RunEnded(outcome) =>
        audit("pilogic-run-ended", s"runId=$runId outcome=${outcome.getClass.getSimpleName}")
      case LoopEvent.HookErrored(p, h, c) => audit("pilogic-hook-errored", s"point=$p hook=$h cause=${cap(c)}")
      case LoopEvent.BudgetReported(s) =>
        audit("pilogic-budget-reported", s"tokens=${s.tokensSpent} turns=${s.turnsCompleted}")

    def emit(e: LoopEvent): IO[Unit] = IO(emitSync(e))

    /** KernelEvent 行消费一序并推进账本（会话域跨 run 连续——L-8 消费侧单点）。 */
    private def kernelRow(typ: String, ledger: RunLedger): RunLedger =
      shadow(
        KernelEvent(
          eventType = typ,
          occurredAt = System.currentTimeMillis(),
          sessionId = sessionId,
          seqInSession = ledger.seqInSession + 1L,
          payload = Json.obj("runId" -> runId.asJson, "detail" -> typ.asJson)
        )
      )
      ledger.nextSeq

    /** run 起点（接线点 1）：RunStarted 审计行 + run-started KernelEvent；返回推进后账本。 */
    def runStart(ledger: RunLedger): RunLedger =
      emitSync(LoopEvent.RunStarted)
      kernelRow("pilogic-run-started", ledger)

    /** run 收口（接线点 1/④）：RunEnded 审计行 + run-ended KernelEvent；账本关 run（seq 保留）。 */
    def runEnd(outcome: String, ledger: RunLedger): RunLedger =
      audit("pilogic-run-ended", s"runId=$runId outcome=$outcome")
      kernelRow("pilogic-run-ended", ledger).closed

    // ── 主链织入点便捷方法（全部走 emitSync/audit——零新帧零新消息）──────────

    /** 接线点 1：轮起点。 */
    def turnStart(turnId: Long): Unit = emitSync(LoopEvent.TurnStarted(TurnNumber(turnId)))

    /** 接线点 1：LLM 流起点（sendStream 派发位）。 */
    def messageStart(turnId: Long): Unit =
      emitSync(LoopEvent.MessageStarted(messageSeq(turnId)))

    /** 接线点 1/4：LLM 聚合完成位（MessageEnded）。 */
    def messageEnd(turnId: Long, stopReason: Option[String], hasToolCalls: Boolean): Unit =
      emitSync(
        LoopEvent.MessageEnded(
          messageSeq(turnId),
          LoopMessage(
            LoopMessageRole.Assistant,
            Json.obj("stopReason" -> stopReason.asJson, "toolCalls" -> hasToolCalls.asJson)
          )
        )
      )

    /** 接线点 4：LlmFailed 完成位（轮以错误收束——事件面；重试/冻结由 handler 决定）。 */
    def llmFailedTurnEnd(turnId: Long): Unit =
      emitSync(LoopEvent.TurnEnded(TurnNumber(turnId), None, Vector.empty, TurnEndOutcome.Error("llm-failed")))

    /** 接线点 5：六分支收束轮（非工具分支——工具分支的轮收束在 ToolsComplete）。 */
    def branchTurnEnd(turnId: Long, branch: String): Unit =
      audit("pilogic-turn-ended", s"turn=$turnId outcome=Natural branch=$branch runId=$runId")

    /** 接线点 6/9：工具批收束（batch.Settled + 轮收束——下一 dispatch 即下一轮）。 */
    def toolsComplete(results: Int, errors: Int): Unit =
      audit("pilogic-tool-batch-settled", s"results=$results errors=$errors runId=$runId")

    /** 接线点 9：单调用起止（既有 ToolStart/ToolEnd 帧位同步）。 */
    def toolStart(callId: String, name: String): Unit =
      emitSync(LoopEvent.ToolExecutionStarted(callId, name))

    def toolEnd(callId: String, isError: Boolean): Unit =
      emitSync(LoopEvent.ToolExecutionEnded(callId, Json.Null, isError))

    /** 接线点 7：迟到消息丢弃（idle/frozen 吞分支——现状零日志，织入=可观测性升）。 */
    def lateDiscarded(where: String, detail: String): Unit =
      audit("pilogic-late-event-discarded", s"where=$where $detail")
  end PiBridge

  /** 从 ActorState 构造桥（runId 取自在飞 run 账本；无在飞 run 时 runId="-"）。 */
  def bridge(state: AgentState, ledger: RunLedger)(audit: (String, String) => Unit): PiBridge =
    new PiBridge(
      audit,
      dbShadow(state.sessionId.getOrElse("")),
      state.sessionId.getOrElse(""),
      Option.when(ledger.runId.nonEmpty)(ledger.runId).getOrElse("-"),
      ledger.startTurn
    )

  /**
   * ws 面翻译（②；闸门关——不接线，仅测试钉形状）：LoopEvent → 既有帧形。
   * 覆盖高频增量族（与 StreamBatching.textFrame/thinkingFrame 逐字节对拍）；
   * ToolStart 仅 name 面（LoopEvent 不携参——割接轮由位入参补全）；RunEnded/Done
   * 由主链 returnToIdle 单点构造（状态面在主链侧），翻译表不产帧。
   */
  def wsFrame(
    e: LoopEvent,
    isSubagent: Boolean,
    sessionId: Option[String],
    agentPath: String,
    isAskMode: Boolean
  ): Option[Json] = e match
    case LoopEvent.MessageDelta(_, chunk) =>
      (chunk.hcursor.get[String]("kind").toOption, chunk.hcursor.get[String]("delta").toOption) match
        case (Some("thinking"), Some(d)) => Some(StreamBatching.thinkingFrame(d, isSubagent, sessionId, agentPath))
        case (_, Some(d)) => Some(StreamBatching.textFrame(d, isAskMode, isSubagent, sessionId, agentPath))
        case _ => None
    case LoopEvent.ToolExecutionStarted(_, name) =>
      Some(
        if isSubagent then AgentStreamEvent.ToolStart(name).toJson(agentPath, true, None)
        else Json.obj("type" -> "toolStart".asJson, "sessionId" -> sessionId.asJson, "label" -> name.asJson)
      )
    case _ => None

  // ── ④ 崩溃兜底：pilogic 合成助手接入真实崩溃点 ────────────────────────────

  /**
   * 循环崩溃对下游仍呈现为一次正常收尾（对位 pi handleRunFailure）：合成
   * TurnEnded(空)+RunEnded 终态对经桥 audit 面落审计行（KernelEvent 单源在 runEnd）。
   */
  def crashTailIO(bridge: PiBridge, activeTurnId: Long, aborted: Boolean = false): IO[Unit] =
    EventSink
      .synthesizeTruncatedEvents(Option.when(activeTurnId > 0L)(TurnNumber(activeTurnId)), aborted)
      .traverse_(bridge.emit)

  // ── 接线点 2：TurnNumber 序域（取号/stale 判定类型化单点）──────────────────

  def nextTurnNumber(currentTurnId: Long): TurnNumber = TurnNumber(currentTurnId + 1L)

  def isStaleTurn(eventTurnId: Long, currentTurnId: Long): Boolean =
    TurnNumber(eventTurnId).value != TurnNumber(currentTurnId).value

  // ── 接线点 3：per-turn LLM 重试闸 → RetryPolicy 策略位（归属显式化）────────

  /**
   * 数值单源仍是 Fallback.MaxTurnLlmCalls（fallback.scala 不动）；归属经
   * RetryPolicy 显式。等价性：旧谓词 `n >= MaxTurnLlmCalls` ≡ `!allows(n)`。
   */
  def turnRetryPolicy: RetryPolicy = RetryPolicy(maxAttempts = Some(nebflow.llm.Fallback.MaxTurnLlmCalls))

  def turnRetryExceeded(llmCallsThisTurn: Int): Boolean = !turnRetryPolicy.allows(llmCallsThisTurn)

  // ── 接线点 4：LoopEvent ↔ AgentCommand 翻译表 ─────────────────────────────

  /**
   * 完成腿 → 对应 LoopEvent（影子发射用；驱动割接（D-A）后此表反向生效：
   * AgentLoop 的事件流驱动出等价 AgentCommand 语义）。
   */
  object PiLoopTranslator:

    def eventsOnLlmComplete(cr: ConsumeResult, turn: TurnNumber, seq: MessageSeq): Vector[LoopEvent] =
      Vector(
        LoopEvent.MessageEnded(
          seq,
          LoopMessage(
            LoopMessageRole.Assistant,
            Json.obj("stopReason" -> cr.stopReason.asJson, "toolCalls" -> cr.toolCalls.nonEmpty.asJson)
          )
        ),
        LoopEvent.TurnEnded(
          turn,
          None,
          Vector.empty,
          if cr.toolCalls.nonEmpty then TurnEndOutcome.Natural else TurnEndOutcome.Natural
        )
      )

    def eventsOnLlmFailed(turn: TurnNumber): Vector[LoopEvent] =
      Vector(LoopEvent.TurnEnded(turn, None, Vector.empty, TurnEndOutcome.Error("llm-failed")))

    def eventsOnToolsComplete(results: List[(ToolCall, ToolExecResult)], turn: TurnNumber): Vector[LoopEvent] =
      Vector(
        LoopEvent.TurnEnded(
          turn,
          None,
          results.map(r => LoopMessage(LoopMessageRole.ToolResult, r._2.content.asJson)).toVector,
          TurnEndOutcome.Natural
        )
      )

    /** 反向对位表（文档性映射——割接轮的事件→命令驱动面）。 */
    val commandCounterpart: List[(String, String)] = List(
      "MessageEnded" -> "LlmComplete(聚合完成)",
      "TurnEnded(Error)" -> "LlmFailed",
      "TurnEnded(Natural|ToolUse)" -> "ToolsComplete(批收束后)",
      "RunEnded" -> "returnToIdle/enterErrorFrozen/Interrupt",
      "ToolExecutionStarted" -> "ToolStart 帧",
      "ToolExecutionEnded" -> "ToolEnd 帧"
    )
  end PiLoopTranslator

  // ── 接线点 5：六分支分类器（只读镜像 AgentFinishTurn.handleLlmCompleteBranch
  //     的 ladder 条件——分类结果仅入事件载荷，不参与路由）───────────────────

  def classifyLlmComplete(state: AgentState, result: ConsumeResult): String =
    if state.pendingCompaction.isDefined && result.toolCalls.isEmpty && result.text.nonEmpty then "compact-response"
    else if state.pendingCompaction.exists(_.phase == CompactionPhase.Compact) &&
      result.toolCalls.isEmpty && result.text.isEmpty
    then "compact-empty"
    else if state.pendingCompaction.isDefined && result.toolCalls.nonEmpty then "compact-toolcalls"
    else if state.askMode.isDefined && result.toolCalls.isEmpty then "ask-complete"
    else if result.toolCalls.nonEmpty then "tool-batch"
    else if result.text.nonEmpty || result.thinking.nonEmpty then "text-finish"
    else "empty-response"

  // ── 接线点 8：core/hooks 8 事件 → HookPoint 映射 + 桥接适配器 ─────────────

  /**
   * 映射表（设计 §Ⅳ.3 第 8 条）。🔴 轮 A **不注册进分派路径**——外部进程 hook 已在
   * executeToolInner 真实执行，双发=副作用重复；本表是割接轮（D-A）的接线权威，
   * 适配器经单测钉调用形状。PostToolUseFailure 并入 ToolPost（isError 载荷承载）。
   */
  val hookMapping: List[(nebflow.core.hooks.HookEvent, HookPoint)] = List(
    nebflow.core.hooks.HookEvent.PreToolUse -> HookPoint.ToolPre,
    nebflow.core.hooks.HookEvent.PostToolUse -> HookPoint.ToolPost,
    nebflow.core.hooks.HookEvent.PostToolUseFailure -> HookPoint.ToolPost,
    nebflow.core.hooks.HookEvent.PreCompact -> Reserved.Compact.BeforeCompact,
    nebflow.core.hooks.HookEvent.PostCompact -> HookPoint.CompactEnded,
    nebflow.core.hooks.HookEvent.SessionStart -> HookPoint.SessionStarted,
    nebflow.core.hooks.HookEvent.SessionEnd -> HookPoint.SessionEnding,
    nebflow.core.hooks.HookEvent.Stop -> HookPoint.PostRunSettled
  )

  /**
   * 桥接适配器（HookPoint 分派 → 外部 hook 引擎调用；割接轮注册用）：
   * ToolPre 的 Block→HookEffect.Block、updatedInput→Modify 语义对齐
   * HookRunner 的 recoverWith 双臂（continueOnError 缺省=Continue 档）。
   */
  final class PiHookAdapter(
    beforeTool: (String, io.circe.JsonObject) => IO[Option[(Boolean, String, Option[io.circe.JsonObject])]]
  ):

    /** 返回 pilogic Interceptor 效果：Some(block, reason, updated) → Block/Modify。 */
    def toolPreEffect(name: String, args: Json): IO[HookEffect] =
      beforeTool(name, args.asObject.getOrElse(io.circe.JsonObject())).map {
        case Some((true, reason, _)) => HookEffect.Block(reason, terminate = false)
        case Some((false, _, Some(upd))) => HookEffect.Modify(upd.asJson)
        case _ => HookEffect.Proceed
      }
  end PiHookAdapter

  // ── 接线点 9：ToolExecutor 适配器（批本体 parTraverse 不动——D-B 上报）────

  /**
   * 包装既有 executeTool 全链（权限/心跳/结构化日志在闭包内）；pilogic 面承载
   * 模型面投影（content/isError——frontendContent/imageBlocks 留主链消费）。
   * onDelta：主链工具无逐块进度面（心跳走 WS ToolHeartbeat）⇒ 忽略（文档化）；
   * signal：中断在 Actor 层（D-A 割接面）——适配器不拦截。
   */
  final class PiToolExecutor(run: ToolCall => IO[ToolExecResult]) extends nebflow.pilogic.ToolExecutor[IO]:

    def execute(call: PiCall, signal: AbortSignal[IO], onDelta: Json => IO[Unit]): IO[PiResult] =
      run(
        ToolCall(
          id = call.callId,
          name = call.name,
          input = call.args.asObject.getOrElse(io.circe.JsonObject())
        )
      ).map(r => PiResult(output = Json.fromString(r.content), isError = r.isError))

  // ── 接线点 10：ProviderPort 适配器（llm 本体零改——sendStream 面注入）──────

  /** StreamChunk→ProviderChunk 的规范增量形（wsFrame 消费 kind/delta 两键）。 */
  def chunkJson(kind: String, delta: String): Json =
    Json.obj("kind" -> kind.asJson, "delta" -> delta.asJson)

  /** stopReason 字符串→pilogic StopReason 映射（adapter 实测值域；未识别→Stop 保守档）。 */
  def mapStopReason(stopReason: Option[String], hasToolCalls: Boolean): StopReason =
    stopReason match
      case Some("tool_use") => StopReason.ToolUse
      case Some("length") => StopReason.Length
      case Some("error") => StopReason.Error
      case Some("aborted") => StopReason.Aborted
      case Some("deferred") => StopReason.Deferred
      case _ => if hasToolCalls then StopReason.ToolUse else StopReason.Stop

  /**
   * ProviderPort[IO]：sendStream/聚合均注入（生产 = resources.llm.sendStream +
   * StreamBatching 管线 + aggregateChunks——既有腿全复用；测试=脚本桩）。
   */
  final class PiProviderPort(
    sendStream: () => fs2.Stream[IO, StreamChunk],
    aggregate: List[StreamChunk] => ConsumeResult
  ) extends ProviderPort[IO]:

    def stream(req: ProviderRequest, signal: AbortSignal[IO]): IO[ProviderStream] =
      sendStream().compile.toList.map { chunks =>
        val cr = aggregate(chunks)
        val piChunks = chunks.collect {
          case StreamChunk.TextDelta(d) if d.nonEmpty => ProviderChunk(chunkJson("text", d))
          case StreamChunk.ThinkingDelta(d) if d.nonEmpty => ProviderChunk(chunkJson("thinking", d))
        }
        ProviderStream(
          piChunks.toVector,
          ProviderResponse(
            message = LoopMessage(
              LoopMessageRole.Assistant,
              Json.obj("text" -> cr.text.asJson, "thinking" -> cr.thinking.asJson)
            ),
            stopReason = mapStopReason(cr.stopReason, cr.toolCalls.nonEmpty),
            toolCalls = cr.toolCalls.map(tc => PiCall(tc.id, tc.name, tc.input.asJson)).toVector
          )
        )
      }
  end PiProviderPort

end PiLoopWiring
