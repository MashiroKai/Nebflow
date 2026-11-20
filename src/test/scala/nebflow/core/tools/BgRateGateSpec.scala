package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite

import nebflow.shared.Defaults

/**
 * #753 会话级后台任务创建速率闸——判定面 + IO 面契约。
 *
 * 实证背景：delegate-nebflow-59f171d8 八分钟造 88 个 Decision-point waiter
 * 刷爆后台任务面板（81 条同行族）。闸 = 两档（warn 告警 / breaker 熔断），
 * 判定单点 = BgRateGate.decide（纯函数），IO 面 =
 * BgTaskRegistry.gateCreation（Ref.modify 单点记账，显式后台与收编路径共用）。
 */
class BgRateGateSpec extends FunSuite:

  private val now = 1_800_000_000_000L
  private val window = Defaults.BgRateWindowMs
  private val cooldown = Defaults.BgRateBreakerCooldownMs
  private val warnAfter = Defaults.BgRateWarnAfter
  private val breakerAfter = Defaults.BgRateBreakerAfter

  // ── 纯判定面（decide）──────────────────────────────────────

  test("decide: empty history allows without warn") {
    val d = BgRateGate.decide(Vector.empty, None, now, window, warnAfter, breakerAfter, cooldown)
    assert(clue(d.allowed))
    assert(!clue(d.warn))
    assert(!clue(d.breakerEngaged))
    assertEquals(clue(d.recentCount), 1) // 本拍计入后 = 1
    assertEquals(clue(d.message), None)
  }

  test("decide: out-of-window creations do not count") {
    val old = Vector.fill(breakerAfter)(now - window - 1000L)
    val d = BgRateGate.decide(old, None, now, window, warnAfter, breakerAfter, cooldown)
    assert(clue(d.allowed), "窗口外历史不得累计")
    assert(!clue(d.warn))
  }

  test("decide: warn tier fires at warnAfter-1 prior creations, still allows") {
    val prior = Vector.fill(warnAfter - 1)(now - 1000L)
    val d = BgRateGate.decide(prior, None, now, window, warnAfter, breakerAfter, cooldown)
    assert(clue(d.allowed))
    assert(clue(d.warn), "本拍计入后到达告警档")
    assert(!clue(d.breakerEngaged))
    assertEquals(clue(d.recentCount), warnAfter)
  }

  test("decide: breaker engages at breakerAfter prior creations — rejected with message") {
    val prior = Vector.fill(breakerAfter)(now - 1000L)
    val d = BgRateGate.decide(prior, None, now, window, warnAfter, breakerAfter, cooldown)
    assert(!clue(d.allowed))
    assert(clue(d.breakerEngaged))
    assertEquals(clue(d.breakerUntilMs), Some(now + cooldown))
    val msg = clue(d.message).getOrElse("")
    assert(msg.contains("rate limit") && msg.contains("circuit breaker"), msg)
    assert(msg.contains(s"$breakerAfter background tasks"), msg)
  }

  test("decide: active breaker rejects without re-engaging (alert edge once)") {
    val until = now + cooldown
    val d = BgRateGate.decide(Vector.empty, Some(until), now, window, warnAfter, breakerAfter, cooldown)
    assert(!clue(d.allowed))
    assert(!clue(d.breakerEngaged), "激活期重复拒绝不再触发沿")
    val msg = clue(d.message).getOrElse("")
    assert(msg.contains("rejected for another"), msg)
  }

  test("decide: expired breaker re-evaluates from window state") {
    val d =
      BgRateGate.decide(Vector.fill(3)(now - 1000L), Some(now - 1L), now, window, warnAfter, breakerAfter, cooldown)
    assert(clue(d.allowed), "到期熔断解除，窗口计数照常")
    assert(!clue(d.breakerEngaged))
  }

  test("decide: reject message coaches toward single consolidated waiter") {
    val prior = Vector.fill(breakerAfter)(now - 1000L)
    val d = BgRateGate.decide(prior, None, now, window, warnAfter, breakerAfter, cooldown)
    val msg = d.message.getOrElse("")
    assert(msg.contains("ONE background task"), s"文案必须给出替代姿势: $msg")
  }

  // ── IO 面（gateCreation）──────────────────────────────────

  test("gateCreation: allows the first creation and records it") {
    val sid = s"delegate-ratespec-${System.nanoTime()}"
    val d1 = BgTaskRegistry.gateCreation(sid).unsafeRunSync()
    assert(clue(d1.allowed))
  }

  test("gateCreation: empty sessionId bypasses the gate") {
    val d = BgTaskRegistry.gateCreation("  ").unsafeRunSync()
    assert(clue(d.allowed))
  }

  test("gateCreation: breaker trips after breakerAfter creations within window and rejects the next") {
    val sid = s"delegate-ratespec-flood-${System.nanoTime()}"
    // 前 breakerAfter 拍全部放行（并记账）。
    (1 to breakerAfter).foreach { i =>
      val d = BgTaskRegistry.gateCreation(sid).unsafeRunSync()
      assert(clue(d.allowed), s"creation #$i should pass")
    }
    // 第 breakerAfter+1 拍触发熔断：拒绝 + 触发沿。
    val trip = BgTaskRegistry.gateCreation(sid).unsafeRunSync()
    assert(!clue(trip.allowed), "超档创建必须拒绝")
    assert(clue(trip.breakerEngaged), "本拍是触发沿")
    // 冷却期内的下一拍：仍拒绝，但不再触发沿（防告警帧刷屏）。
    val again = BgTaskRegistry.gateCreation(sid).unsafeRunSync()
    assert(!clue(again.allowed))
    assert(!clue(again.breakerEngaged), "激活期重复拒绝不再触发沿")
    // 告警观察面记录了熔断事件（同 suite 顺序执行；他 suite 不触闸 → 无竞态）。
    val alert = BgTaskRegistry.lastRateAlert.unsafeRunSync()
    assert(clue(alert).exists(_.sessionId == sid), s"expected rate alert for $sid, got $alert")
  }

  test("gateCreation: gate is per-session (other sessions unaffected)") {
    val a = s"delegate-ratespec-a-${System.nanoTime()}"
    val b = s"delegate-ratespec-b-${System.nanoTime()}"
    (1 to breakerAfter).foreach(_ => BgTaskRegistry.gateCreation(a).unsafeRunSync())
    val tripA = BgTaskRegistry.gateCreation(a).unsafeRunSync()
    assert(!clue(tripA.allowed), "会话 A 熔断")
    val dB = BgTaskRegistry.gateCreation(b).unsafeRunSync()
    assert(clue(dB.allowed), "会话 B 不受 A 的熔断影响（闸按会话隔离）")
  }
end BgRateGateSpec
