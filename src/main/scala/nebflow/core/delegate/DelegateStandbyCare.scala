package nebflow.core.delegate

import cats.effect.IO
import nebflow.actor.{ActorRef, AgentCommand, AgentEvent, AgentStatus}
import nebflow.core.AgentRuntimePort
import nebflow.shared.{Defaults, NebflowLogger}

import scala.concurrent.duration.*

/**
 * DelegateStandbyCare —— 完成通知矩阵下**唯一剩下的提醒腿**（未申报空等
 * · 矩阵组合 complete×idle）。案A 实施批 2026-10-31；状态矩阵批 2026-11-01；
 * **通知矩阵批 2026-11-03 · 作者 18:14 裁定逐字**：「我们有四个状态 idle、stand by、
 * merge、finish。只有在 idle 状态下，进入等待（complete），才会有 system reminder
 * 通知。它都已经报了 stand by 了，还通知什么？所以这里的四个状态 idle complete 通知，
 * stand by/merge complete 不通知 finish complete 回报给主 agent。」
 *
 * == 两种腿型（矩阵两行）==
 *   - [[IdleNudge]]（complete×**idle** 未申报空等）= 唯一注入 system-reminder 的腿：
 *     退避 10s→30s→60s 后转 10min 档，逐档推进、穷尽即静默；
 *   - [[Declared]]（complete×**standby** 已申报待命）= **零注入零唤醒**：
 *     [[arm]] 对本腿是零动作（不建 caretaker、不写记账、不起 fiber），完成事件静默
 *     入队，会话下次自然醒来才可见。Declared 的提醒文案随本批**退役**（没有提醒腿就
 *     不该留提醒文案）。
 *
 * == 职责与红线 ==
 *   - 只**提醒**，零 wall-clock 判死：提醒穷尽 ⇒ 回纯事件驱动（Mail 随时唤醒，
 *     「无超时」红线照旧——空等会话绝不因超时被终态化）；
 *   - 注入形态 = `<system-reminder>` 的 UserInput（replyTo=supervisor，唤醒轮的
 *     Completed 仍回监督者矩阵）；
 *   - 自取消判据（每次到点注入前复验，fail-soft）：记录在册未归档 ∧ 活体在册 ∧
 *     **会话未处于申报待命**（`standbySince` 置位即静默——Mail 唤醒清槽 / 会话终态
 *     同样自断了断）；外加本 caretaker 的槽仍是当前槽（槽被撤 ⇒ 静默退出）。
 */
object DelegateStandbyCare:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.standby-care")

  /** 提醒腿型（矩阵两行：idle = 提醒；standby = 静默）。 */
  sealed trait PromptKind

  /** 显式申报 standby（矩阵 complete×standby）：零提醒（作者 2026-11-03 18:14）。 */
  case object Declared extends PromptKind

  /** 未申报空等（矩阵 complete×idle）：唯一提醒腿。 */
  case object IdleNudge extends PromptKind

  /** [[CareState.kind]] 取值（记账面的提醒型标签）。 */
  val KindDeclared: String = "Declared"
  val KindIdleNudge: String = "IdleNudge"

  /** PromptKind → 记账标签（单点映射）。 */
  def kindTag(kind: PromptKind): String = kind match
    case Declared => KindDeclared
    case IdleNudge => KindIdleNudge

  /**
   * 第 attempt 次提醒的退避延迟（attempt 1-based；纯函数，spec 判红锚）：短档序列
   * 内逐档取 `delays`，其后走平长档 `longMs`。
   */
  def delayFor(attempt: Int, delays: List[Long], longMs: Long): Long =
    if attempt <= delays.length then delays(math.max(0, attempt - 1)) else longMs

  /** 提醒总次数（纯函数）：短档各一次 + 长档 N 次。 */
  def totalAttempts(delays: List[Long], longMax: Int): Int =
    delays.length + math.max(0, longMax)

  /**
   * 生效延迟（纯函数，spec 判红锚 · 作者 2026-11-03 18:14 裁定的退避判据）：
   * 会话**答复过**之后（同一待命期内 `answers > 0`）或**第 2 档起**（`attempt >= 2`），
   * 相邻提醒间隔一律 ≥ `floorMs`（缺省 10min）——治「答了无变化还 1 分钟连戳」。
   * 首档（会话尚未被提醒过）照旧走短档，保留「刚空等就提醒」的及时性。
   */
  def effectiveDelay(attempt: Int, delays: List[Long], longMs: Long, floorMs: Long, answers: Int): Long =
    val base = delayFor(attempt, delays, longMs)
    if answers > 0 || attempt >= 2 then math.max(base, math.max(0L, floorMs)) else base

  /**
   * 提醒载荷（模型可见文案；bg-lifecycle nudge 同款 `<system-reminder>` 形态，
   * 双语在册——en 主文 + zh 摘要行）。终态申报工具通道退役（T2 批）后本文案钉
   * **纯文本语义**：回合结束 = 完成事件，待命 = 正文明说在等什么——不再引导调用
   * 任何申报工具。
   * `lastReminder` = 档序穷尽腿——明示「此后回纯事件驱动、零杀零超时」。
   * `chainsInFlight` / `emptyTurn` = 未申报腿的两种形态锚：等上游链（链在飞也必须
   * 交代）/ 空烧轮。
   */
  def nudgePayload(
    lastReminder: Boolean,
    chainsInFlight: Boolean = false,
    emptyTurn: Boolean = false
  ): String =
    val why =
      if chainsInFlight then
        "your dispatcher chains are still in flight — if you are waiting on upstream results, say exactly that"
      else if emptyTurn then "the turn produced no text output and no tool calls (empty burn)"
      else "the turn ended with a substantive result but NO closing statement"
    val enBody =
      s"""[delegate-matrix] Your turn ended WITHOUT a closing statement — the engine did NOT judge it complete (matrix rule: complete × undeclared = remind, never complete). $why. STATE YOUR POSITION NOW, in plain text, before anything else: if you are waiting for orders/events, say explicitly what you are waiting for; if the end state is reached, write your final report as normal text output (the work result first — it is what the dispatcher receives)."""
    val zhBody =
      if chainsInFlight then
        "【状态矩阵】你的回合结束时没有交代状态——引擎未判完成（矩阵规则：complete×未申报 = 提醒补告，绝不判完成）。你的分发链仍在飞——若在等上游结果，请用正文明说在等什么；已到终态 = 用正文输出最终报告（工作结果开头——那是交付给派发方的正文）。"
      else
        "【状态矩阵】你的回合结束时没有交代状态——引擎未判完成（矩阵规则：complete×未申报 = 提醒补告，绝不判完成）。请立即用正文交代：在等指令/事件 = 明说在等什么；工作已到终态 = 正文输出最终报告（工作结果开头——那是交付给派发方的正文）。"
    val tail =
      if lastReminder then
        "\nThis is the LAST reminder of the sequence — the session returns to pure event-driven waiting (Mail wakes it anytime; nothing is killed; no timeout).\n【提醒穷尽】这是本序列最后一条提醒——此后回纯事件驱动等待（Mail 随时唤醒；零杀零超时）。"
      else ""
    s"""<system-reminder>
       |$enBody
       |$zhBody$tail
       |</system-reminder>""".stripMargin

  end nudgePayload

  /** delaysDesc 文案（审计日志用；纯函数）。 */
  def delaysDesc(delays: List[Long], longMs: Long, longMax: Int): String =
    val short = delays.map(ms => s"${ms / 1000}s").mkString("→")
    if longMax > 0 then s"$short, then every ${longMs / 60000}min ×$longMax"
    else short

  // ── 退避状态机（2026-11-03 应答消费批 + 18:14 通知矩阵批）─────────────────
  //
  // 病灶一（应答消费）：旧 arm() 无条件「先取消旧 fiber 再从 attempt=1 起新序列」
  // ⇒ 提醒唤醒会话 → 会话重申 → 回合收尾又 arm ⇒ 序列永远停在第 1 档（10s）。
  // 修法 = 重申当**应答**消费（fiber 活着零动作；fiber 自死从下一档续跑）。
  //
  // 病灶二（换型重置，本批修）：`armDecision` 曾把「提醒型变了（Declared ↔
  // IdleNudge）」判成 StartFresh ⇒ 同一待命期内换型即把 attempt 打回 1，退避序
  // 重置 ⇒ 分钟级连戳。作者 2026-11-03 18:14 裁定后本腿只剩 IdleNudge，且换型一律
  // **单调续档**（见 armDecision）。
  //
  // 病灶三（答复后仍连戳，本批修）：退避下限 [[Defaults.DelegateStandbyNudgeAnswerFloorMs]]
  // ——答复过或第 2 档起的间隔 ≥ 10min（[[effectiveDelay]] 取大值）。
  //
  // 待命期边界 = 停靠槽由 Some → None（Mail 唤醒 / 新申报 / 会话终态），由
  // [[resetPeriod]] 记账清零（写点 = DelegateRegistry.clearStandby 与 updateReport
  // 的 standbyCleared 分支）。

  /** 同一待命期内的看护记账（进程内存态，重启即清）。 */
  final case class CareState(
    /** 已发提醒数（下一档 = attempt + 1）。 */
    attempt: Int = 0,
    /** 序列是否已穷尽（本期空等此后静默）。 */
    exhausted: Boolean = false,
    /** 被当应答消费的重申次数（观测面/审计面）。 */
    answers: Int = 0,
    /** 本记账所属的提醒型（`IdleNudge` 的 toString；"" = 旧记账/未标注）。 */
    kind: String = ""
  )

  /** arm() 的决策面（纯函数，spec 判红锚——「应答后不再重复提醒」的机械锚）。 */
  sealed trait ArmDecision extends Product with Serializable

  object ArmDecision:
    /** 新待命期（无先验记账）：从第 1 档起。 */
    case object StartFresh extends ArmDecision

    /** 同一待命期、fiber 已不在：从第 attempt 档续跑（不重发已发档）。 */
    final case class ResumeAt(attempt: Int) extends ArmDecision

    /** 同一待命期、fiber 在跑：重申 = 应答 —— 零动作（推进由在跑 caretaker 承担）。 */
    case object AnswerConsumed extends ArmDecision

    /** 序列穷尽：本期空等静默（零提醒，只保留终态判定）。 */
    case object StaySilent extends ArmDecision

  /**
   * 决策表：
   *   - 无记账 ⇒ StartFresh（新待命期）；
   *   - 已穷尽（或已发满 total 档）⇒ StaySilent（封顶硬约束）；
   *   - fiber 在跑 ⇒ AnswerConsumed（**不重发、不重启**——退避自然推进）；
   *   - 否则 ⇒ ResumeAt(attempt + 1)（fiber 中途自死也不回第一档）。
   *
   * **换型不再重置退避序**（作者 2026-11-03 18:14 裁定的退避判据）：旧「kind 变了 ⇒
   * StartFresh」把 attempt 打回 1 ⇒ 退避序永远停首档（分钟级连戳，正是本裁定要断的
   * 形态）。本批起同一待命期内换型一律**单调续档**；`kind` 只作记账标签，不产生
   * StartFresh（`""` = 调用方不关心型，旧调用面逐字兼容）。
   */
  def armDecision(prev: Option[CareState], fiberLive: Boolean, total: Int, kind: String = ""): ArmDecision =
    prev match
      case None => ArmDecision.StartFresh
      case Some(st) if st.exhausted => ArmDecision.StaySilent
      case Some(st) if st.attempt >= total => ArmDecision.StaySilent
      case Some(_) if fiberLive => ArmDecision.AnswerConsumed
      case Some(st) => ArmDecision.ResumeAt(math.max(1, st.attempt + 1))

  /** 提醒总数封顶（delay 档长 + 长档次数）——同一待命期提醒次数上界。 */
  def sequenceTotal: Int =
    totalAttempts(Defaults.DelegateStandbyNudgeDelaysMs, math.max(0, Defaults.DelegateStandbyNudgeLongMax))

  // ── 执行面 ─────────────────────────────────────────────────────────────

  /** per-delegate caretaker 槽（token = 自死 fiber 的归属判据：只移除自己的槽）。 */
  private final case class Slot(token: Long)

  /**
   * per-delegate 单槽 caretaker 注册表。含 fiber ⇒ 该空等期的序列在跑（= 应答面
   * 「fiberLive」判据）；fiber 自终即移除自己的槽（token 比对，防误删新槽）；槽被
   * 撤（申报待命 / 新待命期）⇒ 在跑 fiber 下一拍静默退出。
   */
  private val arms = new java.util.concurrent.ConcurrentHashMap[String, Slot]()

  /** per-delegate 退避记账（跨 arm 存活 = 应答消费的载体）。 */
  private val states = new java.util.concurrent.ConcurrentHashMap[String, CareState]()

  /**
   * 待命期边界：停靠槽由 Some → None（Mail 唤醒 / 新申报覆盖 / 终态）时清零。
   *  纯簿记（无 IO）——从 DelegateRegistry 的写锁临界区内调用，绝不阻塞投递。
   */
  def resetPeriod(delegateId: String): Unit =
    if delegateId != null && delegateId.nonEmpty then
      states.remove(delegateId)
      ()

  /** 读面（spec 断言 / 审计）：当前待命期的看护记账。 */
  private[delegate] def careStateOf(delegateId: String): Option[CareState] =
    Option(states.get(delegateId))

  /** 读面：是否有 caretaker 在跑（spec 断言面）。 */
  private[delegate] def caretakerLive(delegateId: String): Boolean = arms.containsKey(delegateId)

  /** spec 现场复原（生产无调用方）。 */
  private[delegate] def clearAllForSpec(): Unit =
    arms.clear()
    states.clear()

  /**
   * 武装一轮空等看护（回合收尾矩阵分流 ⇒ 监督者调用）。fire-and-forget：启动失败
   * 只 WARN。
   *
   * **通知矩阵批 2026-11-03（作者 18:14）**：
   *   - `kind = Declared`（矩阵 complete×standby）⇒ **零动作**：不建 caretaker、不写
   *     记账、不起 fiber（顺带撤掉旧未申报腿的槽——申报即静默），只留一条审计行；
   *     完成事件静默入队，会话下次自然醒来才可见。
   *   - `kind = IdleNudge`（矩阵 complete×idle）⇒ 幂等「确保本期有一条 caretaker」，
   *     决策表见 [[armDecision]]（新期待命从头起 / 重申即应答零动作 / fiber 自死续档 /
   *     穷尽静默）。
   */
  def arm(
    delegateId: String,
    childRef: ActorRef[AgentCommand],
    supervisorRef: ActorRef[AgentEvent],
    resources: AgentRuntimePort,
    kind: PromptKind,
    chainsInFlight: Boolean = false,
    emptyTurn: Boolean = false
  ): IO[Unit] =
    if !Defaults.DelegateStandbyNudgeEnabled then IO.unit
    else
      kind match
        case Declared =>
          // 作者 2026-11-03 18:14：「都已经报了 stand by 了，还通知什么」——
          // 申报待命腿零注入零唤醒（矩阵 standby×complete = QueueSilently）。
          IO.blocking {
            arms.remove(delegateId)
            states.remove(delegateId)
            ()
          }.handleErrorWith(e =>
            logger.warn(s"[standby-care] declared-silence bookkeeping failed for $delegateId: ${e.getMessage}")
          ) *> logger.info(
            s"[standby-care] declared standby for $delegateId — matrix standby×complete ⇒ silent queue, " +
              "ZERO nudge (no caretaker, no care state; completion event waits for the session's next natural wake)"
          )
        case IdleNudge =>
          val total = sequenceTotal
          val kindTag = DelegateStandbyCare.kindTag(kind)
          IO.blocking {
            val prev = Option(states.get(delegateId))
            val live = arms.containsKey(delegateId)
            (prev, armDecision(prev, live, total, kindTag))
          }.flatMap {
            case (_, ArmDecision.AnswerConsumed) =>
              IO.blocking {
                states.compute(
                  delegateId,
                  (_, cur) =>
                    Option(cur)
                      .getOrElse(CareState())
                      .copy(answers = Option(cur).map(_.answers).getOrElse(0) + 1, kind = kindTag)
                )
                val st = Option(states.get(delegateId)).getOrElse(CareState())
                logger.info(
                  s"[standby-care] re-declared idle state consumed as an ANSWER for $delegateId " +
                    s"(answers=${st.answers}, reminders sent=${st.attempt}/$total) — backoff ADVANCES, no reminder re-sent"
                )
                ()
              }

            case (_, ArmDecision.StaySilent) =>
              IO.blocking {
                val st = Option(states.get(delegateId)).getOrElse(CareState())
                logger.info(
                  s"[standby-care] reminder sequence EXHAUSTED for $delegateId (${st.attempt}/$total) — " +
                    "this idle period stays silent (pure event-driven wait; Mail wakes anytime; zero timeout)"
                )
                ()
              }

            case (_, decision) =>
              val startAttempt = decision match
                case ArmDecision.StartFresh => 1
                case ArmDecision.ResumeAt(n) => n
                case _ => 1
              val token = System.nanoTime()
              val slot = Slot(token)
              IO.blocking {
                arms.put(delegateId, slot)
                states.put(delegateId, CareState(attempt = startAttempt - 1, kind = kindTag))
                ()
              } *>
                IO.defer(
                  loop(
                    delegateId,
                    slot,
                    childRef,
                    supervisorRef,
                    resources,
                    attempt = startAttempt,
                    chainsInFlight,
                    emptyTurn
                  )
                ).start
                  .void
          }.handleErrorWith(e => logger.warn(s"[standby-care] arm failed for $delegateId: ${e.getMessage}").void)

  /**
   * caretaker 本体：算档 → 睡 → 复验仍等 → 注入提醒 → 递进（穷尽即静默）。
   *
   * 记账面：每次注入即落 `attempt`（下一档据此续跑），穷尽即落 `exhausted` 并**移除
   * 自己的槽**（本期空等此后静默——重申不再武装新序列）。自我了断（已唤醒 / 已申报
   * 待命 / 会话终态 / 槽被撤）同样移除自己的槽（token 比对，防误删新槽）。
   */
  private def loop(
    delegateId: String,
    slot: Slot,
    childRef: ActorRef[AgentCommand],
    supervisorRef: ActorRef[AgentEvent],
    resources: AgentRuntimePort,
    attempt: Int,
    chainsInFlight: Boolean,
    emptyTurn: Boolean
  ): IO[Unit] =
    val delays = Defaults.DelegateStandbyNudgeDelaysMs
    val longMs = Defaults.DelegateStandbyNudgeLongMs
    val longMax = math.max(0, Defaults.DelegateStandbyNudgeLongMax)
    val total = totalAttempts(delays, longMax)
    val floorMs = Defaults.DelegateStandbyNudgeAnswerFloorMs
    val baseDelay = delayFor(attempt, delays, longMs)
    val releaseSlot: IO[Unit] = IO
      .blocking {
        arms.remove(delegateId, slot)
        ()
      }
      .handleErrorWith(_ => IO.unit)
    // 槽仍是当前槽？（申报待命腿 / 新待命期会把槽撤掉 ⇒ 本 fiber 静默退出，绝不注入）
    val slotStillCurrent: IO[Boolean] =
      IO.blocking(Option(arms.get(delegateId)).contains(slot)).handleErrorWith(_ => IO.pure(false))
    val answersNow: IO[Int] =
      IO.blocking(Option(states.get(delegateId)).map(_.answers).getOrElse(0)).handleErrorWith(_ => IO.pure(0))
    answersNow.flatMap { answers =>
      val sleepMs = effectiveDelay(attempt, delays, longMs, floorMs, answers)
      val floorNote: IO[Unit] =
        if sleepMs > baseDelay then
          logger.info(
            s"[standby-care] answer floor applied for $delegateId (attempt=$attempt, answers=$answers, " +
              s"base=${baseDelay}ms, floor=${floorMs}ms ⇒ sleep=${sleepMs}ms)"
          )
        else IO.unit
      floorNote *> IO.sleep(sleepMs.millis) *> slotStillCurrent.flatMap {
        case false =>
          // 槽被撤（申报待命 / 新待命期 / 终态清算）⇒ 静默退出；记账已被撤回方清空
          releaseSlot
        case true =>
          stillWaiting(delegateId, resources).flatMap {
            case false =>
              // 已唤醒 / 已申报待命 / 终态 ⇒ 自我了断（绝不在在飞回合中途打扰）；槽归还，
              // 记账保留（同一空等期内后续重申按 ResumeAt 续档，不回第一档）。
              releaseSlot
            case true =>
              val last = attempt >= total
              val payload = nudgePayload(last, chainsInFlight, emptyTurn)
              (childRef ! AgentCommand.UserInput(
                text = payload,
                replyTo = Some(supervisorRef),
                source = Some("standby-care"),
                eventType = Some("standby-nudge")
              )).void
                .handleErrorWith(e =>
                  logger.warn(s"[standby-care] nudge #$attempt inject failed for $delegateId: ${e.getMessage}").void
                ) *>
                // 记账：已发档数推进（穷尽位随最后一档置位）。answers 面**保留**——
                // 计数由 arm() 的 AnswerConsumed 分支独占推进，注入腿只读不改。
                IO.blocking {
                  states.compute(
                    delegateId,
                    (_, cur) =>
                      CareState(
                        attempt = attempt,
                        exhausted = last,
                        answers = Option(cur).map(_.answers).getOrElse(0),
                        kind = KindIdleNudge
                      )
                  )
                  ()
                } *>
                IO.whenA(!last)(
                  loop(delegateId, slot, childRef, supervisorRef, resources, attempt + 1, chainsInFlight, emptyTurn)
                ) *>
                IO.whenA(last)(
                  releaseSlot *>
                    logger.info(
                      s"[standby-care] reminder sequence exhausted for $delegateId " +
                        s"(matrix idle×complete, $total nudge(s), ${delaysDesc(delays, longMs, longMax)}) — this idle period goes SILENT " +
                        "(pure event-driven waiting: Mail wakes; zero timeout; re-declarations no longer re-arm)"
                    )
                )
          }
      }
    }

  end loop

  /**
   * 复验「仍在等」：**未申报空等**判据 = 记录在册未归档 ∧ 活体在册 ∧ 会话未处于
   * 申报待命（`standbySince` 为空）。会话一旦申报 standby（停靠槽置位）本腿即静默
   * ——作者 2026-11-03 18:14「都已经报了 stand by 了，还通知什么」。fail-soft：读面
   * 任何故障 ⇒ false（保守自我了断——宁可少催，绝不打扰在飞回合）。
   */
  private def stillWaiting(delegateId: String, resources: AgentRuntimePort): IO[Boolean] =
    IO.blocking(DelegateRegistry.find(delegateId))
      .flatMap { rec =>
        resources.agentRegistry.get.map { reg =>
          rec.exists(r => r.archivedAt.isEmpty && r.standbySince.isEmpty) && reg.contains(delegateId)
        }
      }
      .handleErrorWith(e =>
        logger
          .warn(s"[standby-care] waiting recheck failed for $delegateId (failing safe to cancel): ${e.getMessage}")
          .as(false)
      )

end DelegateStandbyCare
