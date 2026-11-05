package nebflow.actor

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

/**
 * 冻结状态两套真相批（2026-11-05 10:42 报障）的后端判红锚。
 *
 * 报障逐字：「'delegate-nebflow-f7c6cd3a' is not in the delegate-error frozen state
 * (frozenReason=none) — the wake action only applies to frozen delegates，现在前端
 * 显示有矛盾，显示需要我唤醒，我点了唤醒又弹这个」。
 *
 * 本 spec 钉住单一真相 [[FreezeTruth]] 的四条不变量（对应任务书验收 1–4）：
 *   ① 曾冻结 → 被 Mail 唤醒 → 正在运行 ⇒ **不再是冻结态、不给按钮**（wakeable=false）；
 *   ② 真冻结且需人工 ⇒ wakeable=true（面板给按钮 ⇔ 唤醒闸放行）；
 *   ③ 真冻结但可自动恢复 ⇒ frozen=true / wakeable=false（显示「等待自动恢复」、无按钮）；
 *   ④ 唤醒被拒的文案必须带出**实际状态 + 原因**（不许只回 frozenReason=none）。
 * 另加一条：两处写点**一方漏清**时并集仍判冻结（脏态不制造第二套真相）。
 */
class FreezeTruthSpec extends FunSuite:

  private val now = 1_700_000_000_000L

  private def live(
    status: AgentStatus,
    reason: Option[String] = None
  ): AgentRecord =
    AgentRecord(
      sessionId = "delegate-nebflow-f7c6cd3a",
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = AgentKind.Delegate,
      rootSessionId = "root-1",
      status = status,
      frozenReason = reason
    )

  // ── ① 报障那一格：Mail 唤醒清掉槽之后 ───────────────────────────────
  test("① 曾冻结 → Mail 已唤醒 → 正在运行：不再是冻结态，无唤醒按钮（wakeable=false）") {
    // Mail 唤醒腿（onWoken）把两处都清了：registry 记录回 Idle、落盘槽为空。
    val t = FreezeTruth.of(Some(live(AgentStatus.Idle)), None, None, now)
    assertEquals(t.frozen, false)
    assertEquals(t.wakeable, false)
    assertEquals(t.reasonWire, "")
    assertEquals(t.status, "Idle")
    assertEquals(t.source, "none")
    assert(t.notWakableWhy.contains("NOT frozen"), s"拒绝必须说清'已不冻结'：${t.notWakableWhy}")
    assert(t.notWakableWhy.contains("status=Idle"), s"拒绝必须带实际状态：${t.notWakableWhy}")
  }

  test("① 唤醒后即使面板那侧的行还挂着旧原因，闸也只认后端真相（同一读数给面板）") {
    val t = FreezeTruth.of(Some(live(AgentStatus.Processing)), None, None, now)
    assertEquals(t.frozen, false)
    assertEquals(t.json.hcursor.downField("frozen").as[Boolean].toOption, Some(false))
    // 帧/快照块 always carries asOf ⇒ 前端可按时间定序把旧标记降级。
    assert(t.json.hcursor.downField("asOf").as[Long].toOption.exists(_ == now))
  }

  // ── ② 真冻结且需人工 ⇒ 面板给按钮 ⇔ 闸放行 ──────────────────────────
  test("② 真冻结且需人工（delegate 错误冻结）：wakeable=true，分类=needs-human") {
    val t = FreezeTruth.of(
      Some(live(AgentStatus.Frozen, Some("delegate-frozen:llm-failure"))),
      Some("delegate-frozen:llm-failure"),
      Some(now - 60_000),
      now
    )
    assertEquals(t.frozen, true)
    assertEquals(t.needsHuman, true)
    assertEquals(t.wakeable, true)
    assertEquals(t.kindWire, "needs-human")
    assertEquals(t.source, "both")
    assertEquals(t.since, Some(now - 60_000))
  }

  test("② 循环检测（loop）同样是需人冻结：wakeable=true") {
    val t = FreezeTruth.of(Some(live(AgentStatus.Frozen, Some("loop"))), None, None, now)
    assertEquals(t.frozen, true)
    assertEquals(t.wakeable, true)
    assertEquals(t.reasonWire, "loop")
  }

  // ── ③ 自动恢复族 ⇒ 无按钮（与 10:36 裁定一致）────────────────────────
  test("③ 真冻结但可自动恢复（llm-transient / schedule）：frozen=true、wakeable=false") {
    List("llm-transient", "network", "provider-down", "restart-recovery", "schedule").foreach { wire =>
      val t = FreezeTruth.of(Some(live(AgentStatus.Frozen, Some(wire))), None, None, now)
      assertEquals(t.frozen, true, s"$wire 必须仍判冻结")
      assertEquals(t.wakeable, false, s"$wire 不得给唤醒按钮")
      assertEquals(t.needsHuman, false, s"$wire 不得说需人")
    }
  }

  // ── 脏态：两处写点一方漏清 ─────────────────────────────────────────
  test("两处写点一方漏清时并集仍判冻结（不制造第二套真相）") {
    // 落盘槽清了、内存态还在冻结（或反之）——旧实现里这一格就是「面板给按钮、闸说 none」。
    val agentOnly = FreezeTruth.of(
      Some(live(AgentStatus.Frozen, Some("delegate-frozen:engine-fault"))),
      None,
      None,
      now
    )
    assertEquals(agentOnly.frozen, true)
    assertEquals(agentOnly.source, "agent")
    assertEquals(agentOnly.wakeable, true)

    val slotOnly = FreezeTruth.of(
      None,
      Some("delegate-frozen:tool-failure"),
      Some(now - 1_000),
      now
    )
    assertEquals(slotOnly.frozen, true)
    assertEquals(slotOnly.source, "delegate")
    assertEquals(slotOnly.wakeable, true)
    assertEquals(slotOnly.status, "unknown")
  }

  test("无活体、无落盘槽 ⇒ 运行中（非冻结），未知原因的一律诚实判需人") {
    val none = FreezeTruth.of(None, None, None, now)
    assertEquals(none.frozen, false)
    assertEquals(none.status, "unknown")

    val unknown = FreezeTruth.of(Some(live(AgentStatus.Frozen, Some("who-knows"))), None, None, now)
    assertEquals(unknown.frozen, true)
    assertEquals(unknown.needsHuman, true, "原因未知时不许承诺自动恢复")
    assertEquals(unknown.wakeable, true)
  }

  // ── ④ 拒绝文案：实际状态 + 为什么不需要 ─────────────────────────────
  test("④ 自动恢复族的拒绝文案说明'不需要唤醒'与原因") {
    val t = FreezeTruth.of(Some(live(AgentStatus.Frozen, Some("llm-transient"))), None, None, now)
    val why = t.notWakableWhy
    assert(why.contains("needs no human action"), why)
    assert(why.contains("llm-transient"), why)
    assert(!why.contains("frozenReason=none"), why)
  }

  // ── 线面契约：json ⇄ fromJson ───────────────────────────────────────
  test("冻结块 json 可回读（帧/快照同一契约，坏数据不假装冻结）") {
    val t = FreezeTruth.of(Some(live(AgentStatus.Frozen, Some("delegate-frozen:engine-fault"))), None, None, now)
    val back = FreezeTruth.fromJson(t.json, now)
    assertEquals(back.frozen, t.frozen)
    assertEquals(back.kindWire, t.kindWire)
    assertEquals(back.needsHuman, t.needsHuman)
    assertEquals(back.wakeable, t.wakeable)
    assertEquals(back.reasonWire, t.reasonWire)
    assertEquals(back.asOf, t.asOf)

    val garbage = FreezeTruth.fromJson(Json.obj(), now)
    assertEquals(garbage.frozen, false)
    assertEquals(garbage.wakeable, false)
  }
