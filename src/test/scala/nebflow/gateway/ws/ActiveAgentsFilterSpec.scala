package nebflow.gateway.ws

import nebflow.gateway.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, AgentStatus}
import nebflow.actor.ActorRef

/**
 * `filterActiveAgents` — the in-flight agent list behind getActiveAgents /
 * the bg-agent dropdown.
 *
 * 团队功能删除批（team-feature-deletion-20261104）阶段 3：本件原有四条 **team
 * busy 门**用例（idle team agent is NOT reported / busy team agent IS reported /
 * team agent transitions out when the turn ends / mixed registry）与 `withBusy`
 * helper 全部删除。它们断言的是 `TeamSessionRegistry.markBusy/markIdle` 的 busymap
 * 门（2026-08-16 Teams ghost-running 修复）——该散表随团队会话登记表本体一并退役，
 * `filterActiveAgents` 现在只按 `taskKinds` 过滤（Team 记录不再出现在在飞列表里，
 * 也不再有 busy 门）。删因：**团队 busy 能力已退役**（不是「跑不过」）。
 *
 * 保留：task-lifecycle 三 kind 的 presence 语义、Root 不报告、sessionBusyStates。
 *
 * 方案 A 批（2026-11-18 · 作者令「行集一字不动，只加一个权威活性位」）：本件新增
 * 「Idle / 停靠 / 冻结 / 重启中断行**仍在集内**，但权威活性位为假」一例——行集判据与
 * 活性判据自此显式分家（`filterActiveAgents` 是行集导入器，[[WebSocketRoutes.panelRowActivity]]
 * 是唯一活性判据）。
 */
class ActiveAgentsFilterSpec extends FunSuite:

  // filterActiveAgents never touches the actor ref — null is safe here.
  private def rec(
    sessionId: String,
    kind: AgentKind,
    status: AgentStatus = AgentStatus.Idle
  ): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = kind,
      rootSessionId = "root-1",
      status = status
    )

  private def run(registry: Map[String, AgentRecord]): Set[(String, AgentKind)] =
    WebSocketRoutes
      .filterActiveAgents(registry)
      .unsafeRunSync()
      .map(r => (r.sessionId, r.kind))
      .toSet

  test("task-lifecycle kinds are presence-based (no busy gating)") {
    // Delegate/Flow unregister on completion, so registry presence == in flight.
    // (裁定5：Ephemeral 已删；B5：SubTask 枚举值已删，子代理会话即 Delegate。)
    val registry = Map(
      "del-1" -> rec("del-1", AgentKind.Delegate),
      "sub-1" -> rec("sub-1", AgentKind.Delegate), // 子代理会话（原 SubTask kind）
      "flow-1" -> rec("flow-1", AgentKind.Flow)
    )
    val out = run(registry)
    assertEquals(
      out,
      Set(
        ("del-1", AgentKind.Delegate),
        ("sub-1", AgentKind.Delegate),
        ("flow-1", AgentKind.Flow)
      )
    )
  }

  test("root agents are never reported") {
    val out = run(Map("root-1" -> rec("root-1", AgentKind.Root)))
    assert(out.isEmpty)
  }

  test("sessionBusyStates: only Processing is busy; idle/background wait/unknown are authoritative false") {
    val registry = Map(
      "root-processing" -> rec("root-processing", AgentKind.Root, AgentStatus.Processing),
      // Idle 是后台任务等待期的合法相位，不能因此让 composer 显示不可解释的停止钮。
      "root-bg-wait" -> rec("root-bg-wait", AgentKind.Root, AgentStatus.Idle),
      "root-ask-wait" -> rec("root-ask-wait", AgentKind.Root, AgentStatus.WaitingForUser),
      "delegate-processing" -> rec("delegate-processing", AgentKind.Delegate, AgentStatus.Processing),
      "not-requested" -> rec("not-requested", AgentKind.Root, AgentStatus.Processing)
    )
    val out = WebSocketRoutes.sessionBusyStates(
      registry,
      List("root-processing", "root-bg-wait", "root-ask-wait", "missing", "delegate-processing", "root-processing")
    )
    assertEquals(
      out,
      Map(
        "root-processing" -> true,
        "root-bg-wait" -> false,
        "root-ask-wait" -> false,
        "missing" -> false,
        "delegate-processing" -> true
      )
    )
    assert(!out.contains("not-requested"), "response must stay scoped to requested session ids")
  }

  // ── 方案 A 批（2026-11-18）：权威活性位 ─────────────────────────────────────
  test("panel liveness bit: idle/parked/frozen/restart-interrupted rows stay IN the set but report active=false") {
    val registry = Map(
      "del-live" -> rec("del-live", AgentKind.Delegate, AgentStatus.Processing),
      "del-idle" -> rec("del-idle", AgentKind.Delegate, AgentStatus.Idle),
      "del-standby" -> rec("del-standby", AgentKind.Delegate, AgentStatus.Waiting),
      "del-frozen" -> rec("del-frozen", AgentKind.Delegate, AgentStatus.Frozen),
      "sub-restart" -> rec("sub-restart", AgentKind.Delegate, AgentStatus.Idle),
      "flow-idle" -> rec("flow-idle", AgentKind.Flow, AgentStatus.Idle)
    )
    val rows = WebSocketRoutes.filterActiveAgents(registry).unsafeRunSync()
    // ① 行集一字不动：六条全在——没有按活性位筛行、没有删停靠/冻结行（唤醒/恢复入口靠它）。
    assertEquals(rows.map(_.sessionId).toSet, registry.keySet)

    def entry(sid: String) =
      val r = registry(sid)
      WebSocketRoutes.activeAgentEntryJson(
        r,
        None,
        standbySince = if sid == "del-standby" then Some(1700000000000L) else None,
        frozenSince = if sid == "del-frozen" then Some(1700000000000L) else None,
        taskStatus = if sid == "sub-restart" then Some("pending-recovery") else None
      )

    def field[A: io.circe.Decoder](sid: String, key: String): Option[A] = entry(sid).hcursor.get[A](key).toOption

    val bits = registry.keys
      .map(sid =>
        sid -> field[Boolean](sid, "active").getOrElse(fail(s"row $sid must carry the authoritative liveness bit"))
      )
      .toMap
    // ② 活性位读数：只有真在跑的那条为真；Idle / 待命 / 冻结 / 重启中断全部为假。
    assertEquals(
      bits,
      Map(
        "del-live" -> true,
        "del-idle" -> false,
        "del-standby" -> false,
        "del-frozen" -> false,
        "sub-restart" -> false,
        "flow-idle" -> false
      )
    )
    // ③ 同一判据的分类线值随行出帧（停靠/冻结/重启中断各自的档）。
    assertEquals(field[String]("del-live", "activeState"), Some("running"))
    assertEquals(field[String]("del-idle", "activeState"), Some("idle"))
    assertEquals(field[String]("del-standby", "activeState"), Some("standby"))
    assertEquals(field[String]("del-frozen", "activeState"), Some("frozen"))
    assertEquals(field[String]("sub-restart", "activeState"), Some("pending-recovery"))
    // ④ 与 sessionBusyStates 同一条单点：本 fixture 里活性位为真 ⇔ 该会话判忙。
    assertEquals(bits, WebSocketRoutes.sessionBusyStates(registry, registry.keys.toList))
    // ⑤ 判定点直测（闸的破坏面）：presence 不再是活跃，停靠/冻结/重启中断叠在 Processing 上
    //    一律非活跃——把本函数改回「presence 即活跃」或删掉 active 键，本组立刻红。
    assertEquals(WebSocketRoutes.panelRowActivity(AgentStatus.Processing), WebSocketRoutes.PanelActivity.Running)
    assertEquals(WebSocketRoutes.panelRowActivity(AgentStatus.Idle), WebSocketRoutes.PanelActivity.Idle)
    assertEquals(
      WebSocketRoutes.panelRowActivity(AgentStatus.Processing, standbySince = Some(5L)),
      WebSocketRoutes.PanelActivity.Standby
    )
    assertEquals(
      WebSocketRoutes.panelRowActivity(AgentStatus.Processing, frozen = true),
      WebSocketRoutes.PanelActivity.Frozen
    )
    assertEquals(
      WebSocketRoutes.panelRowActivity(AgentStatus.Processing, pendingRecovery = true),
      WebSocketRoutes.PanelActivity.PendingRecovery
    )
  }
end ActiveAgentsFilterSpec
