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

  // ── 执行面 ─────────────────────────────────────────────────────────────

  /**
   * per-delegate 单槽 caretaker 注册表（ConcurrentHashMap——arm 幂等：先取消旧
   * fiber 再置新；自死 fiber 的残留条目无害：cancel 已终 fiber = no-op，且下次
   * arm 直接覆写。进程内存态，重启即清——与「重启后会话不 live」同生命周期）。
   */
  private val arms = new java.util.concurrent.ConcurrentHashMap[String, Fiber[IO, Throwable, Unit]]()

  /**
   * 武装一轮待命看护（回合收尾矩阵分流 ⇒ 监督者调用）。fire-and-forget：启动失败
   * 只 WARN（看护缺失不改写待命语义——只是少了提醒）。总闸关 = 零动作。
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
      (IO.blocking(Option(arms.get(delegateId)))
        .flatMap(_.fold(IO.unit)(_.cancel))
        .flatMap { _ =>
          IO
            .defer(loop(delegateId, childRef, supervisorRef, resources, kind, attempt = 1, chainsInFlight, emptyTurn))
            .start
            .flatMap(f => IO.blocking { arms.put(delegateId, f); () })
        })
        .handleErrorWith(e => logger.warn(s"[standby-care] arm failed for $delegateId: ${e.getMessage}").void)

  /** caretaker 本体：睡档 → 复验仍等 → 注入提醒 → 递进（穷尽即静默）。 */
  private def loop(
    delegateId: String,
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
    IO.sleep(delayFor(attempt, delays, longMs).millis) *>
      stillWaiting(delegateId, resources, kind).flatMap {
        case false => IO.unit // 已唤醒/已补报/终态 ⇒ 自我了断（绝不在在飞回合中途打扰）
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
            IO.whenA(!last)(loop(delegateId, childRef, supervisorRef, resources, kind, attempt + 1, chainsInFlight, emptyTurn)) *>
            IO.whenA(last)(
              logger.info(
                s"[standby-care] reminder sequence exhausted for $delegateId " +
                  s"(matrix ${kind.toString.toLowerCase}, $total nudge(s)) — back to pure event-driven waiting (Mail wakes; zero timeout)"
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
