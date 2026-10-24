package nebflow.core.processor

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.{AgentCommand, AgentRecord, AgentStatus}
import nebflow.core.{AgentRuntimePort, EventSink}
import nebflow.shared.{Defaults, NebflowLogger}

import scala.concurrent.duration.FiniteDuration

/**
 * HeldDeliveryWatchdog —— held 回执投递可靠性自检（平台修复批「delegate 回执投递
 * 可靠性」作者令 2026-10-21，fix 1/2/3 的调度面）。
 *
 * 实证机制链（本批要治本）：delegate 干完活 → 回执进入「待投递」held 态（子代理批
 * 屏障 pendingEvents）→ 注入父会话失败（issue #31 phantom slot：成员 hang / 停止
 * 失败，屏障槽位永不归还，held 回执直到重启才可能注入）→ 会话无 wall-clock 超时
 * （10-19 拍板不动）→ 永久 Idle → 回执烂在队列。今日受害 6 支，Root 的 AgentControl
 * barrier 面实证挂着 1/1 outstanding held result——监管面可见、父会话不可见不可消费。
 *
 * 职责（FreezeScheduler 姊妹模式）：
 *   ① 指数退避催注：idle 会话的台账（[[HeldReceiptStore]]）中有到期（producedAt +
 *      退避间隔）未注入的回执 → 发 `AgentCommand.DrainHeldReceipts` 催注；记账
 *      attempts。**不改「无超时」哲学**——本扫描器只催注回执投递，绝不杀会话。
 *   ② 穷尽终态：催注 HeldDeliveryMaxAttempts 次仍 held → exhausted 旗落盘（终态
 *      落盘：回执本体已随 injection-queues 磁盘队列持久）+ 响亮 WARN + 停催——
 *      父会话任何后续事件的「先补注入未读回执」腿消费（AgentIdle 三腿）。
 *   ③ 徽标广播（fix 3）：unread（exhausted）计数写 registry 快照
 *      （`unreadReceiptCount`，本扫描器独占写权）+ 计数变化时 hub 广播
 *      `heldReceipts` 帧——任务栏/面板「N 条回执待投递」，父会话与作者都可见。
 *
 * 工程约束（同 TaskStuckWatcher/FreezeScheduler）：错误自愈（scan 失败吞掉防 fiber
 * 崩溃）；`>>`（by-name）递归防栈溢出。判定面（[[scanCore]]）为纯函数——决策与
 * 执行分离，spec 直接断言决策面。
 */
object HeldDeliveryWatchdog:

  private val logger = NebflowLogger.forName("nebflow.core.processor.held-delivery")

  /** 徽标帧类型（前端 heldReceipts 处理器注册名）。 */
  val FrameType: String = "heldReceipts"

  /** 单轮扫描的决策动作（[[scanCore]] 产出、[[scan]] 执行）。 */
  sealed trait ScanAction

  final case class Nudge(sessionId: String, ids: List[String], ref: nebflow.actor.ActorRef[AgentCommand])
      extends ScanAction
  final case class Exhaust(sessionId: String, ids: List[String], heads: List[String]) extends ScanAction
  final case class BadgeSync(sessionId: String, unreadNow: Int, unreadSnapshot: Int) extends ScanAction

  /**
   * 决策面（纯函数，spec 判红锚）：对**单个** idle 会话的台账行集给出动作序列。
   *   - 到期（producedAt + 退避间隔 ≤ now）且未穷尽 → 催注；
   *   - 到期且下一次催注将超 HeldDeliveryMaxAttempts → 终态置旗（不再催注）；
   *   - unread 快照与现值不一致 → 徽标对账。
   * 非到期 / 空台账 / 已穷尽（不再重复置旗）→ 零动作。
   */
  def scanCore(
    sessionId: String,
    ref: nebflow.actor.ActorRef[AgentCommand],
    entries: List[HeldReceiptStore.HeldReceipt],
    unreadSnapshot: Int,
    now: Long
  ): List[ScanAction] =
    val active = entries.filterNot(_.exhausted)
    val due = active.filter(e => HeldDeliveryPolicy.isDue(e, now))
    val (exhaust, nudge) = due.partition(HeldDeliveryPolicy.shouldExhaust)
    val unreadNow = entries.count(_.exhausted) + exhaust.size
    val nudgeAct = if nudge.isEmpty then Nil else List(Nudge(sessionId, nudge.map(_.id), ref))
    val exhaustAct =
      if exhaust.isEmpty then Nil else List(Exhaust(sessionId, exhaust.map(_.id), exhaust.map(_.summary)))
    val badgeAct = if unreadNow == unreadSnapshot then Nil else List(BadgeSync(sessionId, unreadNow, unreadSnapshot))
    exhaustAct ++ nudgeAct ++ badgeAct
  end scanCore

  /** 周期扫描循环：scan → sleep(interval) → 递归。由 GatewayMain 以 fiber 启动。 */
  def run(resources: AgentRuntimePort, wsHub: EventSink, interval: FiniteDuration): IO[Unit] =
    def loop: IO[Unit] =
      scan(resources, wsHub).handleErrorWith(e =>
        logger.warn(s"HeldDeliveryWatchdog scan failed (will retry next cycle): ${e.getMessage}")
      ) *> IO.sleep(interval) >> loop
    loop

  /**
   * 单轮扫描（执行面）。候选 = registry 中 **Idle** 会话（Processing/frozen 由各自
   * turn 边界 drain / 解冻 drain 兜住，催注留任——非 idle 的竞态窗口由各 behavior
   * 的 DrainHeldReceipts no-op 分支宽容）。registry 快照计数（pendingEventCount）
   * 不作候选过滤（快照在 processing 期可滞后——台账是磁盘真源）。
   */
  def scan(resources: AgentRuntimePort, wsHub: EventSink, now: Long = System.currentTimeMillis()): IO[Unit] =
    resources.agentRegistry.get.flatMap { registry =>
      val idleSessions = registry.values.toList.filter(_.status == AgentStatus.Idle)
      idleSessions.traverse_ { rec =>
        HeldReceiptStore.load(rec.sessionId).flatMap { entries =>
          execute(resources, wsHub, scanCore(rec.sessionId, rec.ref, entries, rec.unreadReceiptCount, now))
        }
      }
    }

  /** 决策执行：置旗 / 记账+催注 / 徽标回写+广播。全部 best-effort（失败只 WARN）。 */
  private def execute(resources: AgentRuntimePort, wsHub: EventSink, actions: List[ScanAction]): IO[Unit] =
    actions.traverse_ {
      case Exhaust(sessionId, ids, heads) =>
        logger.warn(
          s"[held-receipts-exhausted] $sessionId: ${ids.size} held receipt(s) could not be delivered after " +
            s"${Defaults.HeldDeliveryMaxAttempts} nudges — terminal unread state persisted (ledger exhausted flag; " +
            s"receipt bodies live in the injection-queues disk queue). They will be flushed ahead of the session's NEXT " +
            s"input (held-first-inject). Heads: ${heads.map(h => h.take(80)).mkString(" | ")}"
        ) *> HeldReceiptStore.markExhausted(sessionId, ids)
      case Nudge(sessionId, ids, ref) =>
        logger.info(
          s"HeldDeliveryWatchdog: nudging $sessionId — ${ids.size} held receipt(s) undelivered past the retry " +
            "deadline (issue #31 phantom-slot class), DrainHeldReceipts sent"
        ) *>
          HeldReceiptStore.markAttempted(sessionId, ids, System.currentTimeMillis()) *>
          (ref ! AgentCommand.DrainHeldReceipts).handleErrorWith(e =>
            logger.warn(s"HeldDeliveryWatchdog: nudge to $sessionId failed: ${e.getMessage}")
          )
      case BadgeSync(sessionId, unreadNow, _) =>
        resources.agentRegistry.modify { m =>
          m.get(sessionId) match
            case Some(rec) => (m.updated(sessionId, rec.copy(unreadReceiptCount = unreadNow)), ())
            case None => (m, ())
        } *> wsHub
          .broadcast(
            Json.obj(
              "type" -> FrameType.asJson,
              "sessionId" -> sessionId.asJson,
              "unread" -> unreadNow.asJson
            )
          )
          .handleErrorWith(e => logger.warn(s"HeldDeliveryWatchdog: badge broadcast failed: ${e.getMessage}"))
    }

end HeldDeliveryWatchdog
