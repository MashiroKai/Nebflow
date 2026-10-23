package nebflow.gateway

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.ActorRef
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord}
import nebflow.shared.SessionMeta

/**
 * Contract tests for the activeAgents restore reply entry
 * (WebSocketRoutes.activeAgentEntryJson).
 *
 * The frontend keys its bg-agent map by the agentId of BOTH this restore
 * reply and live agentStart events. Live events carry the ACTOR PATH NAME
 * (ctx.self.path.name), so the two key spaces only merge when every
 * subagent spawn path names its actor by the session id — the Mail path
 * used to spawn "mail-<sid8>", and after a browser refresh the restore
 * reply (agentId=sid) plus the next live agentStart (agentId=mail-<sid8>)
 * filed TWO running rows for ONE session: the Teams panel double-entry
 * ghost (2026-08-17 report, Mail-activation path).
 *
 * `task` mirrors the live agentStart taskDescription (= session display
 * name, "team/agent" for mounted team sessions) so restored rows render
 * with the same team attribution as live ones.
 */
class ActiveAgentsEntrySpec extends FunSuite:

  // activeAgentEntryJson never touches the actor ref — null is safe here
  // (same pattern as ActiveAgentsFilterSpec).
  private def rec(sessionId: String, kind: AgentKind, root: String = "root-1"): AgentRecord =
    AgentRecord(
      sessionId = sessionId,
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = kind,
      rootSessionId = root
    )

  private def meta(
    id: String,
    name: String,
    agentName: Option[String]
  ): SessionMeta =
    SessionMeta(
      id = id,
      name = name,
      createdAt = 0L,
      updatedAt = 0L,
      hasUnread = false,
      agentName = agentName
    )

  test("contract: agentId equals sessionId — restore and live key spaces merge") {
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-123", AgentKind.Team), None)
    assertEquals(json.hcursor.get[String]("agentId"), Right("sid-123"))
    assertEquals(json.hcursor.get[String]("sessionId"), Right("sid-123"))
  }

  test("mounted team session restores with team attribution (task = session name)") {
    val m = meta("sid-123", "nebflow-project/Frontend", Some("Frontend"))
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-123", AgentKind.Team), Some(m))
    assertEquals(json.hcursor.get[String]("agentName"), Right("Frontend"))
    assertEquals(json.hcursor.get[String]("task"), Right("nebflow-project/Frontend"))
    assertEquals(json.hcursor.get[String]("kind"), Right("Team"))
  }

  test("missing meta falls back: agentName = sessionId, task empty") {
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-xyz", AgentKind.Delegate), None)
    assertEquals(json.hcursor.get[String]("agentName"), Right("sid-xyz"))
    assertEquals(json.hcursor.get[String]("task"), Right(""))
  }

  // ── 2026-08-22 缺口 2：快照自带可刷新恢复三字段 ───────────

  test("panel refresh fields: status/startedAt/retryCount present with defaults") {
    // 不传 retryCount（默认）——Ephemeral/无 taskStore 记录 → 0
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-r1", AgentKind.Delegate), None)
    assertEquals(json.hcursor.get[String]("status"), Right("Idle"))
    assertEquals(json.hcursor.get[Long]("startedAt"), Right(0L))
    assertEquals(json.hcursor.get[Int]("retryCount"), Right(0))
    // 向后兼容：六字段不变（旧前端无感）
    assertEquals(json.hcursor.get[String]("agentId"), Right("sid-r1"))
    assertEquals(json.hcursor.get[String]("kind"), Right("Delegate"))
  }

  test("panel refresh fields: status/startedAt/retryCount reflect record+taskStore") {
    val r = rec("sid-r2", AgentKind.SubTask).copy(
      status = nebflow.actor.AgentStatus.Processing,
      startedAt = 1724336000000L
    )
    val json = WebSocketRoutes.activeAgentEntryJson(r, None, retryCount = Some(3))
    assertEquals(json.hcursor.get[String]("status"), Right("Processing"))
    assertEquals(json.hcursor.get[Long]("startedAt"), Right(1724336000000L))
    assertEquals(json.hcursor.get[Int]("retryCount"), Right(3))
  }

  test("panel refresh fields: Error status renders with message (toString wire form)") {
    val r = rec("sid-r3", AgentKind.Delegate)
      .copy(status = nebflow.actor.AgentStatus.Error("boom"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, None)
    assertEquals(json.hcursor.get[String]("status"), Right("Error(boom)"))
  }

  // ── 2026-09-06 项目归属徽标：恢复路径 project 字段 ───────────

  test("project badge field: default empty (non-project agents render no badge)") {
    // Delegate/SubTask/Team/Ephemeral 等注册点不带 project → 空串（前端 falsy）
    val json = WebSocketRoutes.activeAgentEntryJson(rec("sid-p0", AgentKind.Delegate), None)
    assertEquals(json.hcursor.get[String]("project"), Right(""))
  }

  test("project badge field: project-domain record echoes project name") {
    // node-*/dispatcher-* 注册点写 AgentRecord.project（NodeEngine/ProjectActor）
    val r = rec("node-ab12cd34", AgentKind.Flow).copy(project = Some("nebflow"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, None)
    assertEquals(json.hcursor.get[String]("project"), Right("nebflow"))
  }

  // ── 20260907 节点名刷新持久化：agentName 三档链（meta → displayName → sessionId）──

  test("project node session without meta restores agentName = displayName (Flow Map node name)") {
    // node-/dispatcher- 会话从不过 SessionStore.createSession（index 恒无条目）——
    // 修复前此处 fallback sessionId，subagent 面板刷新后行显「node-xx 默认名」。
    val r = rec("node-ab12cd34", AgentKind.Flow).copy(displayName = Some("实施-节点命名验证"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, None)
    assertEquals(json.hcursor.get[String]("agentName"), Right("实施-节点命名验证"))
  }

  test("indexed meta takes precedence over displayName (team attribution unchanged)") {
    // team/主会话既有归属语义零回归：meta.agentName 恒优先。
    val m = meta("sid-team", "nebflow-project/Frontend", Some("Frontend"))
    val r = rec("sid-team", AgentKind.Team).copy(displayName = Some("不应胜出"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, Some(m))
    assertEquals(json.hcursor.get[String]("agentName"), Right("Frontend"))
  }

  test("no meta + no displayName falls back to sessionId (legacy behavior intact)") {
    val json = WebSocketRoutes.activeAgentEntryJson(rec("delegate-x-12345678", AgentKind.Delegate), None)
    assertEquals(json.hcursor.get[String]("agentName"), Right("delegate-x-12345678"))
  }

  test("dispatcher session restores agentName = dispatcher/<project>") {
    val r = rec("dispatcher-ab12cd34", AgentKind.Flow).copy(displayName = Some("dispatcher/e2e-proj"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, None)
    assertEquals(json.hcursor.get[String]("agentName"), Right("dispatcher/e2e-proj"))
  }

  // ── 显示名契约批（2026-10-22 · 子智能体面板行显示名修复）：delegateTitle 第四档 ──
  // 症状：面板行「先批次长名、某时刻收缩成 delegate-nebflow-2c2db017 短 id」——
  // 恢复脸（activeAgentEntryJson）对 delegate 会话 meta/displayName 恒缺席 ⇒ 直落
  // sessionId；live 脸 agentId == sessionId，同 key 合并「快照为准」每次把 live
  // 长名行覆盖成短 id（每次开面板 getActiveAgents 即触发，重启后全行 id）。

  test("display-name contract: delegate row restores agentName = delegateTitle (batch title), not sessionId") {
    // DelegateRegistry.Record.title / SubAgentTask.description 解析产物（handleGetActiveAgents 薄调用）。
    val json = WebSocketRoutes.activeAgentEntryJson(
      rec("delegate-nebflow-2c2db017", AgentKind.Delegate),
      None,
      delegateTitle = Some("【delegate 监督链自动恢复机制批】（作者令，引擎机制补全）")
    )
    assertEquals(
      json.hcursor.get[String]("agentName"),
      Right("【delegate 监督链自动恢复机制批】（作者令，引擎机制补全）")
    )
    // task 保持空（meta 缺席）——行渲染纯标题，无「标题 · 标题」重复。
    assertEquals(json.hcursor.get[String]("task"), Right(""))
  }

  test("display-name contract: precedence meta > displayName > delegateTitle > sessionId (node/team face untouched)") {
    val m = meta("sid-team", "nebflow-project/Frontend", Some("Frontend"))
    val r = rec("sid-team", AgentKind.Team).copy(displayName = Some("不应胜出"))
    val json = WebSocketRoutes.activeAgentEntryJson(r, Some(m), delegateTitle = Some("也不应胜出"))
    assertEquals(json.hcursor.get[String]("agentName"), Right("Frontend"))
    // displayName 档仍压 delegateTitle（Project 域行零回归）
    val r2 = rec("node-ab12cd34", AgentKind.Flow).copy(displayName = Some("实施-节点命名验证"))
    val json2 = WebSocketRoutes.activeAgentEntryJson(r2, None, delegateTitle = Some("不应胜出"))
    assertEquals(json2.hcursor.get[String]("agentName"), Right("实施-节点命名验证"))
  }

  test("display-name contract: delegateTitle absent → sessionId fallback intact (legacy rows)") {
    // 缺省 delegateTitle = None：既有「no meta + no displayName → sessionId」行为逐字节不变。
    val json = WebSocketRoutes.activeAgentEntryJson(rec("delegate-x-12345678", AgentKind.Delegate), None)
    assertEquals(json.hcursor.get[String]("agentName"), Right("delegate-x-12345678"))
  }

  // ── delegateDisplayTitle 解析缝（纯函数）：DelegateRegistry 主源 → 任务记录回填 ──

  test("delegateDisplayTitle: Delegate kind — registry title wins, store description backfills") {
    assertEquals(
      WebSocketRoutes.delegateDisplayTitle(AgentKind.Delegate, Some("注册表批名"), Some("任务记录批名")),
      Some("注册表批名")
    )
    assertEquals(
      WebSocketRoutes.delegateDisplayTitle(AgentKind.Delegate, None, Some("任务记录批名")),
      Some("任务记录批名")
    )
  }

  test("delegateDisplayTitle: blank values never surface; SubTask takes store description only") {
    assertEquals(WebSocketRoutes.delegateDisplayTitle(AgentKind.Delegate, Some("   "), Some("任务记录批名")), Some("任务记录批名"))
    assertEquals(WebSocketRoutes.delegateDisplayTitle(AgentKind.Delegate, Some(""), None), None)
    assertEquals(WebSocketRoutes.delegateDisplayTitle(AgentKind.SubTask, Some("注册表批名"), Some("任务记录批名")), Some("任务记录批名"))
    assertEquals(WebSocketRoutes.delegateDisplayTitle(AgentKind.SubTask, None, None), None)
  }

  test("delegateDisplayTitle: non-task kinds untouched (Team/Flow/Ephemeral → None)") {
    assertEquals(WebSocketRoutes.delegateDisplayTitle(AgentKind.Team, Some("X"), Some("Y")), None)
    assertEquals(WebSocketRoutes.delegateDisplayTitle(AgentKind.Flow, Some("X"), Some("Y")), None)
    assertEquals(WebSocketRoutes.delegateDisplayTitle(AgentKind.Ephemeral, Some("X"), Some("Y")), None)

  // ── #752 空闲细分（在等什么可见化）：快照恢复字段 ──────────────────

  test("#752 idle-subdivision fields: defaults empty/zero on a bare record") {
    val json = WebSocketRoutes.activeAgentEntryJson(rec("delegate-n752-1", AgentKind.Delegate), None)
    assertEquals(json.hcursor.get[String]("currentTool"), Right(""))
    assertEquals(json.hcursor.get[Long]("lastActivityMs"), Right(0L))
    assertEquals(json.hcursor.get[Int]("outstandingSubagents"), Right(0))
    assertEquals(json.hcursor.get[Vector[Json]]("bgWaiting"), Right(Vector.empty))
  }

  test("#752 idle-subdivision fields: record fields echo through (currentTool/lastActivityMs/outstanding)") {
    val r = rec("delegate-n752-2", AgentKind.Delegate).copy(
      currentToolName = Some("Bash"),
      lastActivityMs = 1724336000123L,
      outstandingSubagents = 2,
      pendingEventCount = 3,
      unreadReceiptCount = 1
    )
    val json = WebSocketRoutes.activeAgentEntryJson(r, None)
    assertEquals(json.hcursor.get[String]("currentTool"), Right("Bash"))
    assertEquals(json.hcursor.get[Long]("lastActivityMs"), Right(1724336000123L))
    assertEquals(json.hcursor.get[Int]("outstandingSubagents"), Right(2))
    // 回执两族字段（既有批）不被 #752 改动面触碰
    assertEquals(json.hcursor.get[Int]("heldReceipts"), Right(3))
    assertEquals(json.hcursor.get[Int]("unreadReceipts"), Right(1))
  }

  test("#752 bgWaiting carries (taskId, description, startedAt) triples") {
    val json = WebSocketRoutes.activeAgentEntryJson(
      rec("delegate-n752-3", AgentKind.Delegate),
      None,
      bgWaiting = List(("job-1", "background waiter 67", 1724336000456L), ("job-2", "sbt test", 1724336000789L))
    )
    val arr = json.hcursor.downField("bgWaiting").values.map(_.toVector).getOrElse(Vector.empty)
    assertEquals(arr.size, 2)
    assertEquals(arr(0).hcursor.get[String]("taskId"), Right("job-1"))
    assertEquals(arr(0).hcursor.get[String]("description"), Right("background waiter 67"))
    assertEquals(arr(0).hcursor.get[Long]("startedAt"), Right(1724336000456L))
    assertEquals(arr(1).hcursor.get[String]("description"), Right("sbt test"))
  }

end ActiveAgentsEntrySpec
