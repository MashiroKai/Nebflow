package nebflow.core.tools

import munit.FunSuite
import nebflow.shared.Defaults

/**
 * sleep-poll 禁令批 B 段 spec：[[SleepPollGuard]] 纯函数判据（检测 / 阶梯 /
 * 冷却 / 开关 / 文案）。根因锚 = delegate-nebflow-747cd36a（`sleep 570; echo wN`
 * 心跳 27 拍轮询 5h16m）——LoopGuard R-call 判同参指纹抓不到变参心跳，本护栏
 * 换判据「命令形态」。
 */
class SleepPollGuardSpec extends FunSuite:

  private val cfg = SleepPollGuard.Config(enabled = true, thresholdSec = 60L, escalateAfter = 3)
  private val t0 = 1_000_000L

  // ── 检测（747cd36a 复现场景）──

  test("检测：747cd36a 心跳形态 `sleep 570; echo wN` ⇒ Some(570)") {
    assertEquals(SleepPollGuard.maxSleepSeconds("sleep 570; echo w1"), Some(570L))
    assertEquals(SleepPollGuard.maxSleepSeconds("sleep 570; echo w26; ps aux | grep 49177"), Some(570L))
  }

  test("检测：多段命令取最大 sleep（cd && sleep 120 && tail ⇒ 120）") {
    assertEquals(
      SleepPollGuard.maxSleepSeconds("cd /tmp && sleep 120 && tail -5 /tmp/nb-fulltest.log"),
      Some(120L)
    )
    assertEquals(SleepPollGuard.maxSleepSeconds("sleep 30; sleep 240; echo done"), Some(240L))
  }

  test("检测：无 sleep 透传；碎睡被检测但裁决透传（阈值下）") {
    assertEquals(SleepPollGuard.maxSleepSeconds("sbt -batch test"), None)
    // 碎睡：检测面如实读数，裁决面按阈值豁免（decide = Pass）
    assertEquals(SleepPollGuard.maxSleepSeconds("sleep 2 && flaky-test"), Some(2L))
    assertEquals(
      SleepPollGuard.decide("sleep 2 && flaky-test", SleepPollGuard.State(), t0, cfg),
      SleepPollGuard.Verdict.Pass
    )
    assertEquals(SleepPollGuard.maxSleepSeconds(""), None)
    // 阈值边界：恰等于阈值 = 透传（> 阈值才计犯）
    val at = SleepPollGuard.decide("sleep 60; echo x", SleepPollGuard.State(), t0, cfg)
    assertEquals(at, SleepPollGuard.Verdict.Pass)
  }

  test("检测：子串不误判（asleep/Netflix 不命中；管道/分隔符后命中）") {
    assertEquals(SleepPollGuard.maxSleepSeconds("echo asleep 500"), None)
    assertEquals(SleepPollGuard.maxSleepSeconds("grep sleep x.log"), None)
    assertEquals(SleepPollGuard.maxSleepSeconds("echo x && sleep 90"), Some(90L))
    assertEquals(SleepPollGuard.maxSleepSeconds("(sleep 90) & echo hi"), Some(90L))
  }

  // ── 阶梯（首犯警告 → 第 N 次拒绝）──

  test("阶梯：首犯 WarnExecute（放行+警告），第 escalateAfter 次 Reject（拒绝）") {
    val s1 = SleepPollGuard.decide("sleep 570; echo w1", SleepPollGuard.State(), t0, cfg)
    val w1 = s1.asInstanceOf[SleepPollGuard.Verdict.WarnExecute]
    assertEquals(w1.sleepSec, 570L)
    assertEquals(w1.state.count, 1)

    val s2 = SleepPollGuard.decide("sleep 570; echo w2", w1.state, t0 + 1000, cfg)
    val w2 = s2.asInstanceOf[SleepPollGuard.Verdict.WarnExecute]
    assertEquals(w2.state.count, 2)

    val s3 = SleepPollGuard.decide("sleep 570; echo w3", w2.state, t0 + 2000, cfg)
    val r3 = s3.asInstanceOf[SleepPollGuard.Verdict.Reject]
    assertEquals(r3.state.count, 3)
  }

  test("阶梯：变参心跳各自计犯（同会话累计——与 LoopGuard 指纹语义的互补面）") {
    // 每拍参数都不同（echo wN），会话计数仍累积到拒绝——747cd36a 的机制补位锚
    val v1 = SleepPollGuard.decide("sleep 100; echo a", SleepPollGuard.State(), t0, cfg)
    val v2 = SleepPollGuard.decide("sleep 200; echo bb", v1.asInstanceOf[SleepPollGuard.Verdict.WarnExecute].state, t0 + 1, cfg)
    val v3 = SleepPollGuard.decide("sleep 300; echo ccc", v2.asInstanceOf[SleepPollGuard.Verdict.WarnExecute].state, t0 + 2, cfg)
    assert(v3.isInstanceOf[SleepPollGuard.Verdict.Reject], s"third distinct-args sleep-poll must reject, got $v3")
  }

  test("冷却：30 分钟无新犯 ⇒ 计数归零重计") {
    val v1 = SleepPollGuard.decide("sleep 100; echo a", SleepPollGuard.State(), t0, cfg)
    val st1 = v1.asInstanceOf[SleepPollGuard.Verdict.WarnExecute].state
    // 冷却窗内（<30min）第二次犯 ⇒ count=2
    val inWindow = SleepPollGuard.decide("sleep 100; echo b", st1, t0 + SleepPollGuard.CooldownMs - 1, cfg)
    assertEquals(inWindow.asInstanceOf[SleepPollGuard.Verdict.WarnExecute].state.count, 2)
    // 冷却窗外 ⇒ 归零重计（count=1，再 Warn）
    val afterWindow = SleepPollGuard.decide("sleep 100; echo b", st1, t0 + SleepPollGuard.CooldownMs + 1, cfg)
    assertEquals(afterWindow.asInstanceOf[SleepPollGuard.Verdict.WarnExecute].state.count, 1)
  }

  test("开关：enabled=false ⇒ 一切透传（回退面）") {
    val off = cfg.copy(enabled = false)
    assertEquals(
      SleepPollGuard.decide("sleep 570; echo w1", SleepPollGuard.State(9, t0), t0 + 1, off),
      SleepPollGuard.Verdict.Pass
    )
  }

  // ── 文案（可描述错误三要素：错在哪 / 期望 / 修法 + 实际值）──

  test("文案：警告与拒绝文案都点名 run_in_background + END YOUR TURN + 阈值与实际值") {
    for text <- List(SleepPollGuard.warningText(570L), SleepPollGuard.rejectionText(3, 570L)) do
      assert(text.contains("run_in_background"), s"text must teach the fix: $text")
      assert(text.contains("END YOUR TURN"), s"text must teach ending the turn: $text")
      assert(text.contains("570"), s"text must carry the actual value: $text")
    assert(SleepPollGuard.rejectionText(3, 570L).contains("REJECTED"))
    assert(SleepPollGuard.warningText(570L).startsWith("⚠"))
  }

  test("生产阈值默认：60s 阈值 / 3 次升级 / 开关开（作者令建议值）") {
    assert(Defaults.SleepPollThresholdSec >= 30, "threshold must stay in the tens-of-seconds band")
    assert(Defaults.SleepPollEscalateAfter >= 2, "must warn at least once before rejecting")
    // 编译期锚：Config 默认面 = Defaults 现读（值可被作者 prop 面调校，此处只钉量级）
  }
end SleepPollGuardSpec
