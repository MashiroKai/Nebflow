package nebflow.core.processor

import munit.FunSuite
import nebflow.actor.{AgentCommand, AgentStatus}
import nebflow.core.processor.HeldDeliveryWatchdog.*
import nebflow.shared.Defaults

import java.nio.file.Files

/**
 * 平台修复批「delegate 回执投递可靠性」（作者令 2026-10-21，fix 2 Idle 挂起自检 /
 * fix 1 终态落盘）—— [[HeldDeliveryWatchdog.scanCore]] 决策面的规格：
 *
 *  - W1 到期未穷尽 ⇒ 催注（Nudge 动作携带 DrainHeldReceipts 目标与回执 id 集）；
 *  - W2 未到期 / 空台账 ⇒ 零动作（不误催）；
 *  - W3 催注穷尽 ⇒ 置旗停催（Exhaust）+ unread 徽标对账（BadgeSync）；
 *  - W4 unread 现值与快照一致 ⇒ 无 BadgeSync（广播面最小化）；
 *  - W5 执行面 scan 只催注 idle 会话（Processing 候选留任 turn 边界 drain 兜住）。
 */
class HeldDeliveryWatchdogSpec extends FunSuite:

  private val now = 1_000_000_000L
  private val first = Defaults.HeldDeliveryFirstRetryMs

  private def held(id: String, producedAt: Long, attempts: Int = 0, exhausted: Boolean = false) =
    HeldReceiptStore.HeldReceipt(id, "delegate", "completed", s"summary-$id", producedAt, attempts, 0L, exhausted)

  private val noopRef = new nebflow.actor.ActorRef[AgentCommand]:
    def path = nebflow.actor.ActorPath("noop", "wd-spec")
    def !(msg: AgentCommand) = cats.effect.IO.unit
    def ?[Reply](makeMsg: nebflow.actor.ActorRef[Reply] => AgentCommand, timeout: Option[scala.concurrent.duration.FiniteDuration]) =
      cats.effect.IO.raiseError(new RuntimeException("no-op ref cannot ask"))

  test("W1 到期未穷尽 ⇒ 催注 + 记 attempts（动作携带 id 集，催注计数由执行面落账）"):
    val actions = scanCore("s1", noopRef, List(held("r1", now - first - 1)), unreadSnapshot = 0, now)
    val nudges = actions.collect { case n: Nudge => n }
    assertEquals(nudges.size, 1)
    assertEquals(nudges.head.sessionId, "s1")
    assertEquals(nudges.head.ids, List("r1"))
    assert(actions.collect { case e: Exhaust => e }.isEmpty, "not yet exhausted")

  test("W2 未到期 / 空台账 ⇒ 零动作"):
    assertEquals(scanCore("s1", noopRef, List(held("r1", now - first + 60_000)), 0, now), Nil)
    assertEquals(scanCore("s1", noopRef, Nil, 0, now), Nil)
    // 已穷尽（之前扫已置旗）⇒ 不再重复置旗也不再催注。
    assertEquals(scanCore("s1", noopRef, List(held("r1", now - first - 1, exhausted = true)), 1, now), Nil)

  test("W3 下一次催注超 HeldDeliveryMaxAttempts ⇒ Exhaust + BadgeSync（终态落盘 + 停催）"):
    // attempts 已用尽 ⇒ 第 6 催窗口 = 退避封顶（30min）：producedAt 必须早于 now - 30min 才到期。
    val rows = List(held("r1", now - Defaults.HeldDeliveryMaxBackoffMs - 1, attempts = Defaults.HeldDeliveryMaxAttempts))
    val actions = scanCore("s1", noopRef, rows, unreadSnapshot = 0, now)
    assert(actions.collect { case n: Nudge => n }.isEmpty, "exhausted rows are never nudged again")
    val exhaust = actions.collect { case e: Exhaust => e }
    assertEquals(exhaust.size, 1)
    assertEquals(exhaust.head.ids, List("r1"))
    val badge = actions.collect { case b: BadgeSync => b }
    assertEquals(badge.map(_.unreadNow), List(1), "unread count reflects the exhausted row")

  test("W4 unread 快照与现值一致 ⇒ 无徽标动作（广播面最小化）"):
    val rows = List(held("r1", now - first - 1, attempts = Defaults.HeldDeliveryMaxAttempts - 1))
    val actions = scanCore("s1", noopRef, rows, unreadSnapshot = 0, now)
    assert(actions.collect { case b: BadgeSync => b }.isEmpty)

  test("W5 到期判定以 producedAt + 退避间隔为准（不同 attempts 不同窗口）"):
    // attempts=1 ⇒ 第 2 催窗口 = 2×first；未到 ⇒ 不催。
    val recent = List(held("r1", now - first * 2 + 1, attempts = 1))
    assertEquals(scanCore("s1", noopRef, recent, 0, now), Nil)
    val due = List(held("r1", now - first * 2, attempts = 1))
    assertEquals(scanCore("s1", noopRef, due, 0, now).collect { case n: Nudge => n }.size, 1)

  test("W6 混合批次：部分到期催注、部分未到期留任"):
    val rows = List(
      held("due-a", now - first - 1),
      held("due-b", now - first - 5),
      held("fresh", now - 1000)
    )
    val nudge = scanCore("s1", noopRef, rows, 0, now).collect { case n: Nudge => n }
    assertEquals(nudge.head.ids.sorted, List("due-a", "due-b"))

end HeldDeliveryWatchdogSpec
