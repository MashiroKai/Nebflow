package nebflow.actor

import io.circe.Json
import munit.FunSuite

/**
 * 冻结态误报批（2026-11-05）的后端判红锚：`FreezeDisplay` 的呈现判定表 +
 * `AgentStreamEvent.Frozen` 的线包字段。
 *
 * 报障原文（作者 2026-11-05 09:33）：「它显示已冻结，但是我没有冻结它，而且也没有
 * 显示恢复按钮」。真实命中 = LoopGuard 的跨轮循环检测（同一操作连续 3 个 turn 失败，
 * 每个 turn 的失败原因都是「scope flow map is not available」——环境未就绪），冻结时
 * resumeAt = +1 年、面板只写「已冻结」。本 spec 把「哪种冻结该说成什么」钉住：
 *   - 退避/时间表族：auto/window、needsHuman=false、autoRecoverable=true；
 *   - 循环检测 / delegate 错误冻结 / 已升级到人：manual、needsHuman=true；
 *   - 长停（resumeAt 超过自动恢复时限）不得继续标成「自动恢复中」（判词诚实铁律）。
 */
class FreezeDisplaySpec extends FunSuite:

  private val now = 1_700_000_000_000L
  private val minute = 60_000L
  private val hour = 60L * minute
  private val day = 24L * hour

  test("时间表冻结 = 系统等待：不需人、可自动恢复") {
    val fd = FreezeDisplay.of(FreezeReason.Schedule, Some(now + 3 * hour), None, now)
    assertEquals(fd.kind, FreezeKind.WaitingWindow)
    assertEquals(fd.needsHuman, false)
    assertEquals(fd.autoRecoverable, true)
    assert(!fd.labelZh.contains("已冻结"), s"等待工作时段不得写「已冻结」：${fd.labelZh}")
    assertEquals(fd.hintZh, "")
  }

  test("错误退避族（5 个原因）= 自动恢复中：不需人、无按钮文案") {
    val family = List(
      FreezeReason.LlmTransient,
      FreezeReason.Network,
      FreezeReason.ProviderDown,
      FreezeReason.RestartRecovery,
      FreezeReason.WaitingNetwork
    )
    family.foreach { r =>
      val fd = FreezeDisplay.of(r, Some(now + 30_000L), None, now)
      assertEquals(fd.kind, FreezeKind.AutoRecovering, s"$r")
      assertEquals(fd.needsHuman, false, s"$r")
      assertEquals(fd.autoRecoverable, true, s"$r")
      assert(!fd.labelZh.contains("已冻结"), s"$r 的文案含需人工措辞：${fd.labelZh}")
      assert(!fd.labelEn.toLowerCase.contains("frozen"), s"$r 的文案含需人工措辞：${fd.labelEn}")
    }
  }

  test("循环检测 L2 = 需人介入：引擎不会自己好") {
    // 真实命中形态：resumeAt = +1 年（LoopFreezeResumeMs）
    val fd = FreezeDisplay.of(FreezeReason.Loop, Some(now + 365 * day), None, now)
    assertEquals(fd.kind, FreezeKind.NeedsHuman)
    assertEquals(fd.needsHuman, true)
    assertEquals(fd.autoRecoverable, false)
    assertEquals(fd.reasonWire, "loop")
    assert(fd.hintZh.nonEmpty && fd.hintEn.nonEmpty, "需人介入必须给出真实出口")
  }

  test("长停的错误族（resumeAt 超过自动恢复时限）⇒ 按需人工呈现，不谎称自动重试") {
    val fd = FreezeDisplay.of(FreezeReason.ProviderDown, Some(now + 3 * day), None, now)
    assertEquals(fd.kind, FreezeKind.NeedsHuman)
    assertEquals(fd.needsHuman, true)
    assertEquals(fd.autoRecoverable, false)
    assert(fd.labelZh.contains("不会自己好"), fd.labelZh)
  }

  test("已升级到用户（escalateAt=0 终态）⇒ 无论哪族都需人决策") {
    val esc = EscalationInfo(level = 3, escalateAt = 0L, awaitedParentSessionId = "")
    val fd = FreezeDisplay.of(FreezeReason.LlmTransient, Some(now + minute), Some(esc), now)
    assertEquals(fd.kind, FreezeKind.NeedsHuman)
    assertEquals(fd.needsHuman, true)
    assert(fd.labelZh.contains("需你决定"), fd.labelZh)
  }

  test("等待上级决策（escalateAt 未到期）不夺走族的自动性") {
    val esc = EscalationInfo(level = 1, escalateAt = now + 10 * minute, awaitedParentSessionId = "s-1")
    val fd = FreezeDisplay.of(FreezeReason.LlmTransient, Some(now + minute), Some(esc), now)
    assertEquals(fd.kind, FreezeKind.AutoRecovering)
    assertEquals(fd.needsHuman, false)
  }

  test("线值判定：delegate-frozen:* 一律需唤醒（needsHuman）") {
    List("delegate-frozen:llm-failure", "delegate-frozen:tool-failure", "delegate-frozen:engine-fault",
      "delegate-frozen:cancelled-by-quota").foreach { wire =>
      val fd = FreezeDisplay.ofWire(wire, None, now)
      assertEquals(fd.kind, FreezeKind.NeedsHuman, wire)
      assertEquals(fd.needsHuman, true, wire)
      assert(fd.labelZh.contains("需唤醒"), s"$wire: ${fd.labelZh}")
      assert(FreezeDisplay.needsHumanWire(wire), wire)
    }
  }

  test("线值判定：错误族线值 → auto，schedule → window，未知/空 → 需人工确认") {
    assertEquals(FreezeDisplay.ofWire("llm-transient", None, now).kind, FreezeKind.AutoRecovering)
    assertEquals(FreezeDisplay.ofWire("waiting-network", None, now).kind, FreezeKind.AutoRecovering)
    assertEquals(FreezeDisplay.ofWire("schedule", None, now).kind, FreezeKind.WaitingWindow)
    assertEquals(FreezeDisplay.ofWire("loop", None, now).kind, FreezeKind.NeedsHuman)
    // 冻结着却记不下原因 ⇒ 不许承诺自动恢复
    assertEquals(FreezeDisplay.ofWire("", None, now).kind, FreezeKind.NeedsHuman)
    assertEquals(FreezeDisplay.ofWire("something-new", None, now).kind, FreezeKind.NeedsHuman)
    assertEquals(FreezeDisplay.needsHumanWire("llm-transient"), false)
  }

  test("FreezeKind 线值双向稳定（前端 freezeState.js 逐字同值）") {
    assertEquals(FreezeKind.WaitingWindow.wire, "waiting-window")
    assertEquals(FreezeKind.AutoRecovering.wire, "auto-recovering")
    assertEquals(FreezeKind.NeedsHuman.wire, "needs-human")
    FreezeKind.values.foreach(k => assertEquals(FreezeKind.fromWire(k.wire), Some(k)))
    assertEquals(FreezeKind.fromWire("bogus"), None)
  }

  test("线包 frozen 帧带分类三字段（真实命中形态：loop ⇒ needs-human）") {
    val ev: AgentStreamEvent = AgentStreamEvent.Frozen(
      resumeAtMillis = Some(now + 365 * day),
      reason = FreezeReason.Loop,
      detail = Some("loop-detected: identical call failed in 3 separate turns"),
      retryCount = 1,
      escalation = None
    )
    val json = ev.toJson("agent-x", isSubagent = true, sessionId = Some("delegate-nebflow-be138470"))
    def field(name: String): Option[Json] = json.asObject.flatMap(_.apply(name))
    assertEquals(field("type").flatMap(_.asString), Some("agentFrozen"))
    assertEquals(field("freezeKind").flatMap(_.asString), Some("needs-human"))
    assertEquals(field("needsHuman").flatMap(_.asBoolean), Some(true))
    assertEquals(field("autoRecoverable").flatMap(_.asBoolean), Some(false))
  }

  test("线包 frozen 帧：退避族 ⇒ auto-recovering + needsHuman=false") {
    val ev: AgentStreamEvent = AgentStreamEvent.Frozen(
      resumeAtMillis = Some(now + 15_000L),
      reason = FreezeReason.LlmTransient,
      detail = None,
      retryCount = 2,
      escalation = None
    )
    val json = ev.toJson("agent-x", isSubagent = false, sessionId = Some("root-1"))
    def field(name: String): Option[Json] = json.asObject.flatMap(_.apply(name))
    assertEquals(field("type").flatMap(_.asString), Some("frozen"))
    assertEquals(field("freezeKind").flatMap(_.asString), Some("auto-recovering"))
    assertEquals(field("needsHuman").flatMap(_.asBoolean), Some(false))
    assertEquals(field("autoRecoverable").flatMap(_.asBoolean), Some(true))
  }

  test("迁移日志正文格式（本次报障那条的逐字样例）") {
    val fd = FreezeDisplay.of(FreezeReason.Loop, Some(now + 365 * day), None, now)
    val line = FreezeDisplay.transitionDetail("running", "frozen", fd, "loop-guard", Some(now + 365 * day), now)
    assertEquals(
      line,
      "running->frozen kind=needs-human needsHuman=true autoRecoverable=false reason=loop " +
        "by=loop-guard resumeAt=in31536000000ms"
    )
    val resumed = FreezeDisplay.of(FreezeReason.Loop, None, None, now)
    assertEquals(
      FreezeDisplay.transitionDetail("frozen", "running", resumed, "user-wake", None, now),
      "frozen->running kind=needs-human needsHuman=true autoRecoverable=false reason=loop by=user-wake resumeAt=-"
    )
  }

end FreezeDisplaySpec
