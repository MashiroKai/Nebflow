package nebflow.agent

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * R11 第 4 层 wall-clock 预算（3600s）+ 作者裁定 U1=C-a（预算**不含**人机等待期）
 * 与 U8=(ii)（ask 发起/答复两个既有单点发暂停/恢复信号，不轮询）的自验。
 *
 * 验红对照（V6）：
 *  - 正控：等待 30min 后答复 ⇒ 该段等待**不**计入预算（剩余 = 3600s − 执行时长）。
 *  - 负控（C-b 形态，即「去掉暂停机制」）：同一时间线按纯 wall-clock 计 ⇒
 *    t≈3599s 提问、t≈5399s 答复的会话会被收割 —— 这正是 C-a 与 C-b 的可区分点，
 *    也是「去掉该机制即红」的证据。
 */
class DelegateBudgetSpec extends CatsEffectSuite:

  private val budget = 3_600_000L

  test("V6 正控 (C-a): 30min human-wait is excluded — only execution time counts"):
    val l = DelegateBudget.Ledger(budgetMs = budget, startedAt = 0).pause(10_000).resume(1_810_000)
    assertEquals(l.countedMs(1_810_000), 10_000L) // 只算了 ask 之前那 10s
    assertEquals(l.remainingMs(1_810_000), budget - 10_000L)
    assert(!l.exhausted(1_810_000), "等待 30min 后预算远未耗尽（C-a）")

  test("V6 验红对照 (C-b, no pause): the same timeline IS harvested at t≈3600s"):
    // 去掉暂停机制（= 纯 wall-clock）后，同一时间线在 3600s 处即超预算——本用例
    // 是 C-a 的判别式：任何人把 pause/resume 摘掉，正控用例必红。
    val cb = DelegateBudget.Ledger(budgetMs = budget, startedAt = 0) // 无 pause
    assert(cb.exhausted(3_600_001L), "纯 wall-clock 形态在 3600s 处被收割（C-b）")
    val ca = DelegateBudget.Ledger(budgetMs = budget, startedAt = 0).pause(3_599_000).resume(5_399_000)
    assert(ca.remainingMs(5_399_000L) == 1_000L, s"等待期不计入 ⇒ 剩余 1s，实得 ${ca.remainingMs(5_399_000L)}")
    assert(!ca.exhausted(5_399_000L), "C-a：等待期不被 3600s 收割")

  test("waiting is unbounded: a paused ledger is never exhausted, however long"):
    val l = DelegateBudget.Ledger(budgetMs = 1_000L, startedAt = 0).pause(0L)
    assert(!l.exhausted(10_000_000_000L), "等待无界（R1）——暂停态永不判超时")

  test("pause is idempotent and resume without pause is a no-op"):
    val l = DelegateBudget.Ledger(budgetMs = budget, startedAt = 0).pause(100).pause(500)
    assertEquals(l.pausedAt, Some(100L))
    val r = DelegateBudget.Ledger(budgetMs = budget, startedAt = 0).resume(999)
    assertEquals(r.pausedTotalMs, 0L)
    assertEquals(r.countedMs(1_000), 1_000L)

  test("runtime: the budget fires onTimeout once the wall-clock budget elapses"):
    for
      fired <- Deferred[IO, Unit]
      _ <- DelegateBudget.register("delegate-kernel-rt1", budget = 250.millis)(fired.complete(()).void)
      _ <- fired.get.timeout(5.seconds)
    yield assert(DelegateBudget.activeCount >= 0)

  test("runtime V6: pause suspends the timer, resume re-arms it (wait time not charged)"):
    for
      fired <- Deferred[IO, Unit]
      _ <- DelegateBudget.register("delegate-kernel-rt2", budget = 400.millis)(fired.complete(()).void)
      _ <- DelegateBudget.pause("delegate-kernel-rt2")
      _ <- IO.sleep(1_000.millis) // 等待期 >> 预算：暂停生效则不会触发
      duringWait <- fired.tryGet
      _ = assert(duringWait.isEmpty, "等待期不得收割（U1=C-a）")
      _ <- DelegateBudget.resume("delegate-kernel-rt2")
      _ <- fired.get.timeout(5.seconds)
    yield ()

  test("runtime: release stops the budget (terminal states never fire a timeout)"):
    for
      fired <- Deferred[IO, Unit]
      _ <- DelegateBudget.register("delegate-kernel-rt3", budget = 200.millis)(fired.complete(()).void)
      _ <- DelegateBudget.release("delegate-kernel-rt3")
      _ <- IO.sleep(600.millis)
      after <- fired.tryGet
    yield assert(after.isEmpty, "终态释放后不得再超时取消")

  test("signals for unregistered sessions are harmless no-ops (non-delegate sessions)"):
    for
      _ <- DelegateBudget.pause("node-41-some-session")
      _ <- DelegateBudget.resume("node-41-some-session")
      _ <- DelegateBudget.release("node-41-some-session")
    yield assertEquals(DelegateBudget.DefaultBudget, 3600.seconds.toMillis.millis)

end DelegateBudgetSpec
