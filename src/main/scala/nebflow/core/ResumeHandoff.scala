package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.{ActorRef, AgentCommand, AgentEvent}
import nebflow.shared.{ContentBlock, Message, MessageRole, NebflowLogger}

/**
 * ResumeHandoff —— **中断续跑的唯一交接单点**（无感恢复批 2026-11-07，作者令逐字：
 * 「参考一下我们之前的冻结和重启的恢复逻辑 现在是通过注入消息 之前我们是把那一轮的
 * 工具调用结果发过去 等于llm是无感恢复的」）。
 *
 * == 被替换的形态（缺陷本体）==
 * 改前六条恢复腿各自拼一条**人话提示**注入会话：
 *
 *   `[system] The engine process restarted while your task was in flight. Your work
 *    so far (N transcript messages) is loaded below — continue the task from where
 *    you left off.`（DelegateRevival.reviveImpl）
 *   `[frozen-wake] You were FROZEN at this point …`（DelegateWakeLeg /
 *   `nebflow.core.delegate.DelegateFreeze.wakeBanner`）
 *   `[system] Your previous turn was interrupted by a crash. Please continue …`
 *   （BackoffSupervisor.respawnBody）
 *   `[system] A supplement arrived while no live actor was holding this session …`
 *   （`nebflow.core.delegate.SessionResumption.resumeInPlace`）
 *
 * 后果（不是文案问题，是**协议结构**问题）：中断那一轮的 assistant 消息里带着
 * `tool_use`，而它**永远不会有 result**（进程死在工具批次中途），恢复腿再追加一条
 * 纯文本 user 消息 ⇒ 模型读到的是
 *
 *   `… assistant(tool_use X) → user("[system] the engine restarted …")`
 *
 * 即「我这个工具调用没人回答，然后有人插嘴说了句系统提示」。模型只能自己猜那次
 * 调用发生了什么（SubagentCrashRecovery 的 990bd848 笔记：「模型看不见任何失败
 * 事实，只能盲目重发同一调用（自毒根因）」），并**清楚地知道自己被中断过**。
 *
 * == 本件（无感形态）==
 * 把「那一轮的工具调用结果」按**原始结构**发回去：对断点处未完成的工具调用，生成
 * `user(tool_result …)` 块——
 *   - 已完成的调用：原样留在 transcript 里（本件**不碰**任何已配对的消息）；
 *   - 未完成的调用：补一条 `isError=true` 的结果，正文以机器可查 token
 *     [[InterruptedToken]] 开头，说明「这次调用在 X 时刻断在半路、没有产生结果、
 *     需要就重发一次，不需要就继续」。
 * 于是模型下一轮看到的最后一条消息是**工具边界**（tool_result），而不是一条系统
 * 提示——它可以接着调用链往下走，**察觉不到**中间断过。
 *
 * == 兼容（旧会话必须照读）==
 * 存量会话里已经躺着旧的注入文本（`[system] …` / `[frozen-wake] …`），形态是
 * `assistant(tool_use) → user(旧提示)`——工具调用**已经被一条 user 消息续过去了**。
 * 此时再补 tool_result 会违反「tool_result 必须紧随 tool_use」的协议约束，故
 * [[plan]] 在这种情况下**不动注入载荷、不补块**，逐字回落旧文案注入
 * （[[Plan.atToolBoundary]] = false，[[Plan.fallbackReason]] 记录原因）。
 * 判据是纯函数、无盘面副作用 ⇒ 存量会话读崩率为零。
 *
 * == 单点纪律（本批的核心交付）==
 * 六条腿（冻结唤醒、引擎重启 boot 复活、Mail/面板原位居续、supervisor 进程内
 * respawn、flow 上游失败重启、节点 L3 硬恢复）**一律**只问本件要
 * `(initialMessages, 注入载荷)`，禁止各自拼字符串。新增恢复腿必须复用本件。
 */
object ResumeHandoff:

  private val logger = NebflowLogger.forName("nebflow.agent.resume-handoff")

  /** 未完成工具调用的结果正文 token（机器可查；spec 判红锚）。 */
  val InterruptedToken: String = "[tool-call interrupted]"

  /** 注入源标记（审计/面板来源标签）。 */
  val Source: String = "resume"

  /** 中断原因分类（决定结果正文里「为什么没有结果」一句；词表即状态面）。 */
  enum Cause:
    /** supervisor 进程内 respawn（子会话崩溃 / 空烧）。 */
    case Crash

    /** 引擎进程重启后的 boot 复活。 */
    case EngineRestart

    /** 错误冻结后的唤醒。 */
    case Freeze

    /** 非冻结的续聊（Mail / 面板唤醒一个没有活体的会话）。 */
    case Continuation

    /** flow 节点的上游失败重启。 */
    case UpstreamFailure

    /** 项目节点的 L3 硬恢复（挂死会话断点续跑）。 */
    case NodeRecovery

  end Cause

  /** 原因 → 一句话（进 tool_result 正文；对模型说）。 */
  def causePhrase(c: Cause): String = c match
    case Cause.Crash => "the session crashed in the middle of this call"
    case Cause.EngineRestart =>
      "the engine process restarted in the middle of this call"
    case Cause.Freeze => "the session was frozen in the middle of this call"
    case Cause.Continuation =>
      "no live engine process was holding the session while this call was in flight"
    case Cause.UpstreamFailure =>
      "the upstream step failed and the session restarted in the middle of this call"
    case Cause.NodeRecovery =>
      "the session hung and was recovered from the disk breakpoint in the middle of this call"

  /** 原因 → 线值（审计/日志；短横线小写）。 */
  def causeWire(c: Cause): String = c match
    case Cause.Crash => "crash"
    case Cause.EngineRestart => "engine-restart"
    case Cause.Freeze => "freeze"
    case Cause.Continuation => "continuation"
    case Cause.UpstreamFailure => "upstream-failure"
    case Cause.NodeRecovery => "node-recovery"

  /** 一条未完成的工具调用。 */
  final case class PendingCall(toolName: String, toolUseId: String)

  /**
   * 交接单（[[plan]] 的产出；调用方只读，不自行拼注入文本）。
   *
   * @param transcript         交给 spawn 的 `initialMessages`（恒 = 入参 transcript，或
   *                           中段悬挂的就地修复形态；**已配对的消息一个字节都不改**）
   * @param pending            断点处（transcript 尾部）未完成的工具调用
   * @param injectBlocks       Some = 本次注入走**工具结果**（模型看到 tool_result 边界）；
   *                           None = 走纯文本（无悬挂调用 / 旧格式回落）
   * @param injectText         注入文本（人话；blocks 在场时只用于面板气泡与审计，
   *                           模型侧读的是 blocks）
   * @param atToolBoundary     true = 无感形态（工具边界续跑）
   * @param fallbackReason     回落原因（旧格式 / 无悬挂 —— 审计与 spec 读数）
   * @param repairedMidHistory 中段悬挂的修补条数（transcript 形态异常的旁证）
   */
  final case class Plan(
    transcript: List[Message],
    pending: List[PendingCall],
    injectBlocks: Option[List[ContentBlock]],
    injectText: String,
    atToolBoundary: Boolean,
    fallbackReason: Option[String],
    repairedMidHistory: Int
  )

  /** 已配对的 tool_use id 集（其后任何 user 消息里的 tool_result 都算配对）。 */
  private def answeredIds(messages: List[Message]): Set[String] =
    messages
      .collect { case Message(MessageRole.User, Right(blocks), _, _, _, _) =>
        blocks.collect { case ContentBlock.ToolResult(id, _, _) => id }
      }
      .flatten
      .toSet

  /** Recovery cannot infer whether an interrupted tool produced external effects. */
  def hasUnresolvedToolCalls(messages: List[Message]): Boolean =
    val answered = answeredIds(messages)
    messages.exists(m => danglingOf(m, answered).nonEmpty)

  private def danglingOf(msg: Message, answered: Set[String]): List[(ContentBlock.ToolUse, String)] =
    msg match
      case Message(MessageRole.Assistant, Right(blocks), _, _, _, _) =>
        blocks.collect { case u @ ContentBlock.ToolUse(id, name, _, _) if !answered.contains(id) => (u, name) }
      case _ => Nil

  /**
   * 未完成工具调用的结果正文。**逐字给定**（spec 判据锚）：token 领头 + 原因句 +
   * 处置指引。读者是模型——不写引擎内部黑话，也不写「system」字样（它不是提示，
   * 它是这次调用的结果）。
   */
  def interruptedResultText(c: Cause, toolName: String, toolUseId: String): String =
    s"$InterruptedToken The '$toolName' call (id $toolUseId) never produced a result: ${causePhrase(c)}. " +
      "Whatever it was doing is done or gone — treat this as the outcome of that call. " +
      "If the step is still needed, issue a fresh call; otherwise continue with the rest of the task."

  /** 中段悬挂的修补正文（transcript 形态异常；与 990bd848 的自毒修复同源）。 */
  def midHistoryRepairText(c: Cause, toolName: String, toolUseId: String): String =
    s"$InterruptedToken The '$toolName' call (id $toolUseId) never produced a result: ${causePhrase(c)}. " +
      "It was repaired while this session's history was restored; re-issue it if the step still matters."

  /**
   * **交接单生成单点**（纯函数；零 IO——transcript 由调用方读盘后传入）。
   *
   * 判定顺序（自上而下）：
   *   ① 无未配对工具调用 ⇒ 文本注入（[[Plan.atToolBoundary]]=false）——普通中断的
   *      既有语义逐字保留；
   *   ② 未配对调用位于 transcript **尾部**（最后一条 assistant 消息）⇒ **无感形态**：
   *      transcript 原样、注入块 = 逐条 tool_result（+ 指令文本块）；
   *   ③ 未配对调用位于**中段**（其后已有 user 消息 = 旧格式会话已被一条文本提示续过）
   *      ⇒ transcript 就地补一条 tool_result 消息（紧随该 assistant 消息；旧文本消息
   *      留原位，由 adapter 的同角色合并收进同一条 user 消息），注入走文本。
   *
   * @param instruction 调用方要给模型的**新指令正文**（Mail 正文 / 唤醒指令）；
   *                    空串 = 纯续跑。带上它时它作为 tool_result 之后的 Text 块
   *                    （工具边界优先，指令随后 —— 协议的块序要求）。
   * @param legacyText  无工具边界时的**回落文案**（各腿原有提示原文；由调用方给，
   *                    保证 ② 不成立时行为逐字不变）。
   */
  def plan(
    transcript: List[Message],
    cause: Cause,
    instruction: String = "",
    legacyText: String = ""
  ): Plan =
    val answered = answeredIds(transcript)
    val lastIdx = transcript.size - 1
    val danglingAll = transcript.zipWithIndex.flatMap { (m, i) => danglingOf(m, answered).map((i, _)) }
    val tailCalls = danglingAll.collect { case (i, (u, n)) if i == lastIdx => (u, n) }
    val midCalls = danglingAll.filter { case (i, _) => i != lastIdx }
    val instr = Option(instruction).getOrElse("").trim

    if tailCalls.nonEmpty then
      val results = tailCalls.map { case (u, n) =>
        ContentBlock.ToolResult(u.id, interruptedResultText(cause, n, u.id), Some(true))
      }
      val blocks = if instr.isEmpty then results else results :+ ContentBlock.Text(instr)
      Plan(
        // ② 尾部悬挂：transcript 一个字节不动（结果由注入块承担，绝不重复配对）。
        transcript = repairMidHistory(transcript, midCalls, cause)._1,
        pending = tailCalls.map { case (u, n) => PendingCall(n, u.id) },
        injectBlocks = Some(blocks),
        injectText = if instr.nonEmpty then instr else results.head.content,
        atToolBoundary = true,
        fallbackReason = None,
        repairedMidHistory = midCalls.size
      )
    else if danglingAll.nonEmpty then
      val (repaired, count) = repairMidHistory(transcript, midCalls, cause)
      Plan(
        transcript = repaired,
        pending = Nil,
        injectBlocks = None,
        injectText = legacyText,
        atToolBoundary = false,
        fallbackReason = Some(
          s"${danglingAll.size} dangling tool call(s) are not at the transcript tail — the history already " +
            "continued past them (legacy injected notice): repaired in place, injection stays textual"
        ),
        repairedMidHistory = count
      )
    else
      Plan(
        transcript = transcript,
        pending = Nil,
        injectBlocks = None,
        injectText = legacyText,
        atToolBoundary = false,
        fallbackReason = Some("no pending tool call — ordinary interruption, textual continuation"),
        repairedMidHistory = 0
      )
    end if
  end plan

  /** 中段悬挂就地修补（紧随该 assistant 消息插一条 tool_result 消息）。 */
  private def repairMidHistory(
    transcript: List[Message],
    midCalls: List[(Int, (ContentBlock.ToolUse, String))],
    cause: Cause
  ): (List[Message], Int) =
    if midCalls.isEmpty then (transcript, 0)
    else
      val byIdx = midCalls.groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      val out = scala.collection.mutable.ListBuffer.empty[Message]
      var repaired = 0
      transcript.zipWithIndex.foreach { (m, i) =>
        out += m
        byIdx.get(i).foreach { calls =>
          repaired += calls.size
          out += Message(
            MessageRole.User,
            Right(calls.map { case (u, n) =>
              ContentBlock.ToolResult(u.id, midHistoryRepairText(cause, n, u.id), Some(true))
            }),
            source = Some(Source)
          )
        }
      }
      (out.toList, repaired)

  /**
   * 交接单 → `AgentCommand.UserInput`（注入载荷单点；各腿不得自行构造）。
   *
   * 无感形态下 `blocks` 在场：模型读到 tool_result（+ 指令文本块），`text` 只喂
   * 面板气泡/审计——**这就是「注入一条消息」被替换掉的地方**：投递路径没变，
   * 但模型读到的东西从「一条提示」变成了「那次调用的结果」。
   */
  /** Resolve persisted AskHost calls without generating a new request. */
  def resolveAskHost(sessionId: String, p: Plan): IO[Plan] =
    nebflow.core.tools.AskHostLedger.recover.flatMap { rows =>
      p.pending
        .filter(_.toolName == "AskHost")
        .traverse { call =>
          rows.filter(r => r.pending.workerSession == sessionId && r.pending.toolCallId == call.toolUseId) match
            case row :: Nil =>
              nebflow.core.tools.AskHostLedger
                .restoreWaiter(row.pending.correlationId)
                .flatMap(_.get)
                .map(call.toolUseId -> _)
            case _ =>
              IO.raiseError(
                new IllegalStateException(s"AskHost recovery requires one durable request for ${call.toolUseId}")
              )
        }
        .map { resolved =>
          val answers = resolved.toMap
          val blocks = p.injectBlocks.map(_.map {
            case r: ContentBlock.ToolResult if answers.contains(r.toolUseId) =>
              ContentBlock.ToolResult(r.toolUseId, answers(r.toolUseId), Some(false))
            case other => other
          })
          p.copy(injectBlocks = blocks)
        }
    }

  def recoverableAskHostOnly(sessionId: String, transcript: List[Message]): IO[Boolean] =
    val p = plan(transcript, Cause.Crash)
    if !hasUnresolvedToolCalls(transcript) then IO.pure(true)
    else if !p.atToolBoundary || p.repairedMidHistory > 0 || p.pending.isEmpty || p.pending.exists(
        _.toolName != "AskHost"
      )
    then IO.pure(false)
    else
      nebflow.core.tools.AskHostLedger.recover.map { rows =>
        p.pending.forall(call =>
          rows.count(r => r.pending.workerSession == sessionId && r.pending.toolCallId == call.toolUseId) == 1
        )
      }

  def userInput(
    p: Plan,
    replyTo: Option[ActorRef[AgentEvent]] = None,
    clientMessageId: Option[String] = None,
    sender: Option[String] = None,
    eventType: Option[String] = None
  ): AgentCommand.UserInput =
    AgentCommand.UserInput(
      text = p.injectText,
      replyTo = replyTo,
      clientMessageId = clientMessageId,
      blocks = p.injectBlocks,
      source = Some(Source),
      sender = sender,
      eventType = eventType,
      fromUser = false
    )

  /** 交接单的一行审计读数（日志/ChainStore；机械可核）。 */
  def describe(p: Plan, cause: Cause): String =
    val calls = p.pending.map(c => s"(${c.toolName}:${c.toolUseId})").mkString
    s"cause=${causeWire(cause)} atToolBoundary=${p.atToolBoundary} pendingToolCalls=${p.pending.size}" +
      (if calls.nonEmpty then s" $calls" else "") +
      s" repairedMidHistory=${p.repairedMidHistory} fallback=${p.fallbackReason.getOrElse("-")}"

  /** 审计一行（各腿在注入后调用；fail-soft——日志永不影响恢复本体）。 */
  def logPlan(sessionId: String, p: Plan, cause: Cause): Unit =
    logger.info(s"[resume-handoff] $sessionId ${describe(p, cause)}")

end ResumeHandoff
