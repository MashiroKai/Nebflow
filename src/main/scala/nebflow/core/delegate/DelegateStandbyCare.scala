package nebflow.core.delegate

import cats.effect.{Fiber, IO}
import nebflow.actor.{ActorRef, AgentCommand, AgentEvent, AgentStatus}
import nebflow.core.AgentRuntimePort
import nebflow.shared.{Defaults, NebflowLogger}

import scala.concurrent.duration.*

/**
 * DelegateStandbyCare —— 待命挂起期的机械退避看护（案A 实施批 2026-10-31 · 作者
 * 11:57 令「用 system reminder 提醒进入 standby，不进入就退避提醒」；审计基线
 * `docs/20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939.md`
 * 案B1 退避档 10s→30s→60s 各一次后转 10min 档对齐现有提醒哲学。**状态矩阵批
 * 2026-11-01 扩面**：作者令「complete × idle（未申报）⇒ system reminder 提醒其
 * 调整状态，不终判」——JeV 单问句兜底（案B2）本轮作者未点名，由本看护承担，
 * 不再做 JeV 兜底）。
 *
 * == 职责与红线 ==
 *   - 只**提醒**，零 wall-clock 判死：提醒穷尽 ⇒ 回纯事件驱动（Mail 随时唤醒，
 *     「无超时」红线照旧——待命会话绝不因空等被终态化）；
 *   - 注入形态 = `<system-reminder>` 的 UserInput（MergeQueue.notifyTurn 合并
 *     brief 同款通道：replyTo=supervisor，唤醒轮的 Completed 仍回监督者矩阵）；
 *   - 每轮停靠/提醒轮（re-park / re-nudge）重武装一条 caretaker（新退避序从头发）；
 *     旧 fiber 先取消（ConcurrentHashMap 单槽 per delegate——重复 arm 幂等，最后
 *     一条 wins）；
 *   - 自取消判据（每次到点注入前复验，fail-soft）：kind 分立——[[Declared]] 腿 =
 *     registry standbySince 在场 ∧ 活体 Waiting（Mail 唤醒清槽 / 会话终态 ⇒ 自我了
 *     断）；[[IdleNudge]] 腿 = registry 记录在册未归档 ∧ 活体在册（补报后的回合
 *     收尾会重新分流，本轮不越权打扰在飞回合）。
 *
 * == 提醒文案三型（作者令「提醒它调整状态：报 standby 或 finish」）==
 *   - [[PromptKind.Declared]]（显式申报腿）：已在待命——催其续报（re-declare
 *     standby）或交终态报告，勿把提醒当骚扰；
 *   - [[PromptKind.IdleNudge]]（未申报空等腿 · 矩阵组合 complete×idle）：回合
 *     无申报收尾被判「提醒、不终判」——催其**现在补报** standby 或 finish；
 *   - 文案双语在册（验收②）：en 主文 + zh 摘要行随载荷（模型任读其一，审计面
 *     两语齐备）。
 */
object DelegateStandbyCare:

  private val logger = NebflowLogger.forName("nebflow.core.delegate.standby-care")

  /** 提醒文案型（纯文案分叉；退避序两型全同）。 */
  sealed trait PromptKind
  /** 显式申报 standby 后的待命看护。 */
  case object Declared extends PromptKind
  /** 未申报空等（矩阵组合 complete×idle）的「提醒调整状态」看护。 */
  case object IdleNudge extends PromptKind

  /**
   * [[CareState.kind]] 取值（记账面的提醒型标签；缺陷三同族漏网修的判据常量）：
   * 换型（Declared ↔ IdleNudge）= 新待命期 ⇒ StartFresh。
   */
  val KindDeclared: String = "Declared"
  val KindIdleNudge: String = "IdleNudge"

  /** PromptKind → 记账标签（单点映射；判据与写面同源）。 */
  def kindTag(kind: PromptKind): String = kind match
    case Declared  => KindDeclared
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
   * 提醒载荷（模型可见文案；bg-lifecycle nudge 同款 `<system-reminder>` 形态，
   * 双语在册——en 主文 + zh 摘要行，验收②的注册锚）。
   * `lastReminder` = 档序穷尽腿——明示「此后回纯事件驱动、零杀零超时」。
   * `chainsInFlight` / `emptyTurn`（仅 IdleNudge 型消费）= 矩阵未申报腿的两种
   * 形态锚：等上游链（链在飞也必须申报，不许裸等）/ 空烧轮。
   */
  def nudgePayload(
    kind: PromptKind,
    attempt: Int,
    detail: String,
    delaysDesc: String,
    lastReminder: Boolean,
    chainsInFlight: Boolean = false,
    emptyTurn: Boolean = false
  ): String =
    val enBody = kind match
      case IdleNudge =>
        val why =
          if chainsInFlight then
            "your dispatcher chains are still in flight — if you are waiting on upstream results, that is exactly what standby is for"
          else if emptyTurn then
            "the turn produced no text output and no tool calls (empty burn)"
          else
            "the turn ended with a substantive result but NO declaration"
        s"""[delegate-matrix] Your turn ended WITHOUT a delegate_report — the engine did NOT judge it complete (matrix rule: complete × undeclared = remind, never complete). $why. DECLARE NOW, before anything else: call delegate_report(category="standby", detail="what you are waiting for") if you are waiting for orders/events, or delegate_report(category="finish"|"merge", detail=…) if the end state is reached — then END YOUR TURN with your final report as normal text output (finish must lead with the work result; it is what the dispatcher receives)."""
      case Declared =>
        s"""[delegate-standby] You are parked in standby${if detail.trim.nonEmpty then s" (waiting for: ${detail.take(200)})" else ""}. Reminder #$attempt of the backoff sequence ($delaysDesc). Still waiting? Reply briefly or re-declare delegate_report(category="standby"); reached the end state? Report finish|merge so the verdict sequence can run."""
    val zhBody = kind match
      case IdleNudge =>
        if chainsInFlight then
          "【状态矩阵】你的回合结束时没有调用 delegate_report——引擎未判完成（矩阵规则：complete×未申报 = 提醒补报，绝不判完成）。你的分发链仍在飞——若在等上游结果，这正是 standby 的用途。请立即补报：等指令/等上游 = delegate_report(category=\"standby\", detail=在等什么)；已到终态 = delegate_report(category=\"finish\"或\"merge\", detail=…)；补报后用正文输出最终报告再结束回合。"
        else
          "【状态矩阵】你的回合结束时没有调用 delegate_report——引擎未判完成（矩阵规则：complete×未申报 = 提醒补报，绝不判完成）。请立即补报：在等指令/事件 = delegate_report(category=\"standby\", detail=在等什么)；工作已到终态 = delegate_report(category=\"finish\"或\"merge\", detail=…)；补报后用正文输出最终报告再结束回合（finish 报文须以工作结果开头——那是交付给派发方的正文）。"
      case Declared =>
        s"【待命看护】你已停靠待命${if detail.trim.nonEmpty then s"（在等：${detail.take(120)}）" else ""}。这是退避提醒 #$attempt（$delaysDesc）。仍在等？简短回复或重申 delegate_report(category=\"standby\")；已到终态？报 finish/merge 让判词序运行。"
    val tail =
      if lastReminder then
        "\nThis is the LAST reminder of the sequence — the session returns to pure event-driven waiting (Mail wakes it anytime; nothing is killed; no timeout).\n【提醒穷尽】这是本序列最后一条提醒——此后回纯事件驱动等待（Mail 随时唤醒；零杀零超时）。"
      else ""
    s"""<system-reminder>
       |$enBody
       |$zhBody$tail
       |</system-reminder>""".stripMargin

  /** delaysDesc 文案（注入载荷用；纯函数）。 */
  def delaysDesc(delays: List[Long], longMs: Long, longMax: Int): String =
    val short = delays.map(ms => s"${ms / 1000}s").mkString("→")
    if longMax > 0 then s"$short, then every ${longMs / 60000}min ×$longMax"
    else short

  // ── 退避状态机（2026-11-03 应答消费批 · 作者报障「报了 standby 还一直通知让
  //    standby」）──────────────────────────────────────────────────────────
  //
  // 病灶（实证 delegate-nebflow-1e759bae 单会话 49 条 [delegate-standby]，**全部
  // 写着 Reminder #1**，间隔 20-50s）：旧 arm() 无条件「先取消旧 fiber 再从
  // attempt=1 起新序列」。守护提醒唤醒会话 → 会话重申 standby → 回合收尾又一次
  // 命中 Standby 腿 → arm() 再次把序列**从头开始** ⇒ 退避序永远停在第一档
  // （10s），短档/长档/封顶全部作废——「应答不被消费、退避不推进」。
  //
  // 修法 = 把「重申 standby」当**应答**消费：同期待命期内 arm() 不再重启序列，
  // 在跑的 caretaker 按自己的档序继续（fiber 活着 ⇒ 零动作，让下一档自然到点）；
  // fiber 已自死（回合在飞/重启等）⇒ 从**下一档**续跑（ResumeAt，不重发已发档）；
  // 序列穷尽 ⇒ 本期待命期**静默**（只保留终态判定）。待命期边界 = 停靠槽由
  // Some → None（Mail 唤醒 / 新申报 / 会话终态），由 [[resetPeriod]] 记账清零
  // （写点 = DelegateRegistry.clearStandby 与 updateReport 的 standbyCleared 分支）。

  /** 同一待命期内的看护记账（进程内存态，重启即清）。 */
  final case class CareState(
    /** 已发提醒数（下一档 = attempt + 1）。 */
    attempt: Int = 0,
    /** 序列是否已穷尽（本期待命期此后静默）。 */
    exhausted: Boolean = false,
    /** 被当应答消费的重申次数（应答消费批的观测面/审计面）。 */
    answers: Int = 0,
    /**
     * 本记账所属的提醒型（`Declared` / `IdleNudge` 的 toString；"" = 旧记账/未标注）。
     *
     * 同族漏网修（引擎会话治理专项 · 缺陷三，2026-11-03 实证）：旧 `CareState` 不
     * 区分提醒型 ⇒ IdleNudge 序列穷尽（`exhausted=true`）后，会话补报 standby 换
     * 到 **Declared** 腿时，`armDecision` 命中「已穷尽 ⇒ StaySilent」⇒ **申报后的
     * 正式待命期零提醒**（「报了 standby 还一直通知」的镜像：报了反而不催）。口
     * 径：**换型 = 新待命期**（两种腿的催法不同、判据不同，旧腿的穷尽预算不该吞
     * 掉新腿的提醒），同型重申才走应答消费/续档。
     */
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
    /** 序列穷尽：本期待命期静默（零提醒，只保留终态判定）。 */
    case object StaySilent extends ArmDecision

  /**
   * 决策表：
   *   无记账 ⇒ StartFresh；
   *   **提醒型变了**（Declared ↔ IdleNudge）⇒ StartFresh（新腿 = 新待命期；
   *        旧腿的穷尽预算绝不吃掉新腿的提醒——缺陷三同族漏网修）；
   *   已穷尽（或已发满 total 档）⇒ StaySilent（封顶硬约束）；
   *   fiber 在跑 ⇒ AnswerConsumed（**不重发、不重启**——退避自然推进）；
   *   否则 ⇒ ResumeAt(attempt + 1)（fiber 中途自死也不回第一档）。
   *
   * `kind` 缺省 "" = 调用方不关心型（旧调用面/单测逐字兼容：双方 kind 非空时才
   * 比较，空 = 未标注 ⇒ 不比）。
   */
  def armDecision(prev: Option[CareState], fiberLive: Boolean, total: Int, kind: String = ""): ArmDecision =
    prev match
      case None                                 => ArmDecision.StartFresh
      case Some(st) if kind.nonEmpty && st.kind.nonEmpty && st.kind != kind => ArmDecision.StartFresh
      case Some(st) if st.exhausted             => ArmDecision.StaySilent
      case Some(st) if st.attempt >= total      => ArmDecision.StaySilent
      case Some(_) if fiberLive                 => ArmDecision.AnswerConsumed
      case Some(st)                             => ArmDecision.ResumeAt(math.max(1, st.attempt + 1))

  /** 提醒总数封顶（delay 档长 + 长档次数）——同一待命期提醒次数上界。 */
  def sequenceTotal: Int =
    totalAttempts(Defaults.DelegateStandbyNudgeDelaysMs, math.max(0, Defaults.DelegateStandbyNudgeLongMax))

  // ── 执行面 ─────────────────────────────────────────────────────────────

  /** per-delegate caretaker 槽（token = 自死 fiber 的归属判据：只移除自己的槽）。 */
  private final case class Slot(token: Long)

  /**
   * per-delegate 单槽 caretaker 注册表。含 fiber ⇒ 该待命期的序列在跑（= 应答面
   * 「fiberLive」判据）；fiber 自终即移除自己的槽（token 比对，防误删新槽）。
   * 进程内存态，重启即清——与「重启后会话不 live」同生命周期。
   */
  private val arms = new java.util.concurrent.ConcurrentHashMap[String, Slot]()

  /** per-delegate 退避记账（跨 arm 存活 = 应答消费的载体）。 */
  private val states = new java.util.concurrent.ConcurrentHashMap[String, CareState]()

  /** 待命期边界：停靠槽由 Some → None（Mail 唤醒 / 新申报覆盖 / 终态）时清零。
   *  纯簿记（无 IO）——从 DelegateRegistry 的写锁临界区内调用，绝不阻塞投递。 */
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
   * 武装一轮待命看护（回合收尾矩阵分流 ⇒ 监督者调用）。fire-and-forget：启动失败
   * 只 WARN（看护缺失不改写待命语义——只是少了提醒）。总闸关 = 零动作。
   *
   * **应答消费批（2026-11-03）**：本方法现在是幂等「确保本期待命期有一条
   * caretaker」，不再无条件重启序列——决策表见 [[armDecision]]：
   *   - 新待命期 ⇒ 从第 1 档起；
   *   - 同期待命期内 fiber 在跑（会话刚被我们的提醒唤醒、重申 standby）⇒ **应答
   *     消费**：零动作，在跑 fiber 按档序自然推进（不重发、不回第一档）；
   *   - 同期待命期内 fiber 已自死 ⇒ 从下一档续跑（不回第一档）；
   *   - 序列穷尽 ⇒ 静默（同一 standby 期间提醒次数封顶）。
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
              s"[standby-care] re-declared standby consumed as an ANSWER for $delegateId " +
                s"(answers=${st.answers}, reminders sent=${st.attempt}/$total) — backoff ADVANCES, no reminder re-sent"
            )
            ()
          }

        case (_, ArmDecision.StaySilent) =>
          IO.blocking {
            val st = Option(states.get(delegateId)).getOrElse(CareState())
            logger.info(
              s"[standby-care] reminder sequence EXHAUSTED for $delegateId (${st.attempt}/$total) — " +
                "this standby period stays silent (pure event-driven wait; Mail wakes anytime; zero timeout)"
            )
            ()
          }

        case (_, decision) =>
          val startAttempt = decision match
            case ArmDecision.StartFresh     => 1
            case ArmDecision.ResumeAt(n)    => n
            case _                          => 1
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
                kind,
                attempt = startAttempt,
                chainsInFlight,
                emptyTurn
              )
            ).start.void
      }.handleErrorWith(e => logger.warn(s"[standby-care] arm failed for $delegateId: ${e.getMessage}").void)

  /**
   * caretaker 本体：睡档 → 复验仍等 → 注入提醒 → 递进（穷尽即静默）。
   *
   * 记账面（应答消费批）：每次注入即落 `attempt`（下一档据此续跑），穷尽即落
   * `exhausted` 并**移除自己的槽**（本期待命期此后静默——重申不再武装新序列）。
   * 自我了断（已唤醒/已离开待命/会话终态）同样移除自己的槽（token 比对，防误删
   * 新待命期的新槽）。
   */
  private def loop(
    delegateId: String,
    slot: Slot,
    childRef: ActorRef[AgentCommand],
    supervisorRef: ActorRef[AgentEvent],
    resources: AgentRuntimePort,
    kind: PromptKind,
    attempt: Int,
    chainsInFlight: Boolean,
    emptyTurn: Boolean
  ): IO[Unit] =
    val delays = Defaults.DelegateStandbyNudgeDelaysMs
    val longMs = Defaults.DelegateStandbyNudgeLongMs
    val longMax = math.max(0, Defaults.DelegateStandbyNudgeLongMax)
    val total = totalAttempts(delays, longMax)
    val kindTag = DelegateStandbyCare.kindTag(kind)
    val releaseSlot: IO[Unit] = IO.blocking {
      arms.remove(delegateId, slot)
      ()
    }.handleErrorWith(_ => IO.unit)
    IO.sleep(delayFor(attempt, delays, longMs).millis) *>
      stillWaiting(delegateId, resources, kind).flatMap {
        case false =>
          // 已唤醒/已补报/终态 ⇒ 自我了断（绝不在在飞回合中途打扰）；槽归还，
          // 记账保留（同期待命期内后续重申按 ResumeAt 续档，不回第一档）。
          releaseSlot
        case true =>
          val last = attempt >= total
          val detail = IO
            .blocking(DelegateRegistry.find(delegateId).flatMap(_.reportDetail).getOrElse(""))
            .handleErrorWith(_ => IO.pure(""))
          detail.flatMap { d =>
            val payload = nudgePayload(kind, attempt, d, delaysDesc(delays, longMs, longMax), last, chainsInFlight, emptyTurn)
            (childRef ! AgentCommand.UserInput(
              text = payload,
              replyTo = Some(supervisorRef),
              source = Some("standby-care"),
              eventType = Some("standby-nudge")
            )).void
              .handleErrorWith(e => logger.warn(s"[standby-care] nudge #$attempt inject failed for $delegateId: ${e.getMessage}").void)
          } *>
            // 记账：已发档数推进（穷尽位随最后一档置位）。answers 面**保留**——
            // 计数由 arm() 的 AnswerConsumed 分支独占推进，注入腿只读不改
            // （否则每次注入把应答计数清零，审计面失真）。
            IO.blocking {
              states.compute(
                delegateId,
                (_, cur) => CareState(attempt = attempt, exhausted = last, answers = Option(cur).map(_.answers).getOrElse(0), kind = kindTag)
              )
              ()
            } *>
            IO.whenA(!last)(
              loop(delegateId, slot, childRef, supervisorRef, resources, kind, attempt + 1, chainsInFlight, emptyTurn)
            ) *>
            IO.whenA(last)(
              releaseSlot *>
                logger.info(
                  s"[standby-care] reminder sequence exhausted for $delegateId " +
                    s"(matrix ${kind.toString.toLowerCase}, $total nudge(s)) — this standby period goes SILENT " +
                    "(pure event-driven waiting: Mail wakes; zero timeout; re-declarations no longer re-arm)"
                )
            )
      }

  /**
   * 复验「仍在等」：kind 分立（见类注）。fail-soft：读面任何故障 ⇒ false（保守
   * 自我了断——宁可少催，绝不打扰在飞回合）。
   */
  private def stillWaiting(delegateId: String, resources: AgentRuntimePort, kind: PromptKind): IO[Boolean] =
    IO.blocking(DelegateRegistry.find(delegateId))
      .flatMap { rec =>
        resources.agentRegistry.get.map { reg =>
          kind match
            // 显式申报腿：停靠槽在场 ∧ 活体 Waiting（Mail 唤醒清槽 ⇒ 了断）。
            case Declared =>
              rec.flatMap(_.standbySince).isDefined && reg.get(delegateId).exists(_.status == AgentStatus.Waiting)
            // 未申报空等腿：记录在册未归档 ∧ 活体在册（补报后的回合收尾重新分流）。
            case IdleNudge =>
              rec.exists(_.archivedAt.isEmpty) && reg.contains(delegateId)
        }
      }
      .handleErrorWith(e =>
        logger
          .warn(s"[standby-care] waiting recheck failed for $delegateId (failing safe to cancel): ${e.getMessage}")
          .as(false)
      )

end DelegateStandbyCare
