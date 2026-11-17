package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.delegate.{DelegateRegistry, DelegateRoundLedger}
import nebflow.core.mail.{CompletionDebtStore, SessionMailbox}
import nebflow.shared.{PathUtil, SubAgentTask}

import scala.concurrent.duration.*

/**
 * RecoverySummary 红绿验收（recovery-summary 批 2026-11-16；规格 =
 * `startup-load-inventory__20261116_081800.md` §「后续裁决与验证缺口」#1）。
 *
 * 结构 = **五个状态面各自一红一绿**＋**保守回退红绿**＋正常校验断言：
 *  - 状态面①非终态节点：全终态 ⇒ 0/quiet（绿）；running+pending+interrupted+
 *    legacy blocked 全计数（红）。
 *  - 状态面②人工保持：空集 ⇒ 0（绿）；manualRecoveryNodes 全集含出图幽灵引用（红）。
 *  - 状态面③孤儿暂停：有注册记录 ⇒ 非孤儿（绿）；无记录 ⇒ 孤儿（红）；有记录但
 *    链台账暂停 ⇒ pausedChains 记录（红·子面）。
 *  - 状态面④轮次：无未结算轮（绿，含「开轮后结算」对照）；开轮未结算（红）。
 *  - 状态面⑤待投递：四路全净（绿）；node-message queued＋完成债＋failed 未通知＋
 *    根结果未达四路全记录（红）；完成债**盘面坏形按欠账计**（保守红）。
 *  - 回退：缺失 / 垃圾字节 / 载荷篡改（digest 闸）/ schema 升格 / 指纹漂移（stale）/
 *    非法 scope 六路各自 Fallback；round-trip 正常摘要 == Trusted 且逐字段相等。
 *  - archivedAt 陷阱：已归档身份带 running 节点 ⇒ **不 quiet**（687/13 实证形态）；
 *    已归档全净 ⇒ quiet（archivedAt 是判据①的必要条件，不是充分条件）。
 *
 * 阶段① 增补（delegate-lifecycle-reform，2026-11-17）= **判据①②两面 + 查询面**：
 *  - 判据①身份终态：未归档 / 归档带活标记（cancel 墓碑等）/ 记录缺失 ⇒ 面落档且不 quiet；
 *    归档且四槽全空 ⇒ terminal。
 *  - 判据②子项：档缺席 = 零子项；requesting/running/ready-to-merge/pending-recovery/
 *    incomplete 行计入，completed/failed/cancelled 行不计；档在场但坏形 = 保守记 1。
 *  - `blockers`：每条阻塞事实一个具名单位（不把不同处置动作相加）；空表 ⟺ quiet。
 *  - `query`：Trusted ⇒ deferrable == quiet；Fallback（missing/stale/invalid-scope）⇒ 恒 false。
 *  - v1 兼容：阶段① 前的摘要档经 schema 闸整体回退（保守全量加载），绝不静默 quiet。
 *  - 正向腿的 fixture 自阶段① 起一律取「已归档」身份（终态形态），断言逐条不变。
 *
 * 隔离数据根纪律 = BootCrashRecoverySpec 同款（类体 setDataRoot + afterAll 还原）；
 * 全部走 store 级 API（零 NodeEngine / 零 ActorSystem——摘要机制本体不依赖宿主）。
 */
class RecoverySummarySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-recovery-summary"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private val ws: os.Path = tempRoot / "ws"
  private val wsPath: String = ws.toString
  private val now: Long = System.currentTimeMillis()

  // ── 基建 ─────────────────────────────────────────────────────────────────

  /** 已通知终态节点（quiet 各测试的标准积木：notifySentAt 置位 ⇒ 不入待投递面）。 */
  private def terminalNode(id: String, status: String): NodeDef =
    NodeDef(
      id = id,
      name = id,
      agent = "general",
      status = status,
      result = Some(s"$id done"),
      notifySentAt = Some(now),
      createdAt = now
    )

  private def mkNode(id: String, status: String): NodeDef =
    NodeDef(id = id, name = id, agent = "general", status = status, createdAt = now)

  private def putRecord(
    scope: String,
    archivedAt: Option[Long] = None,
    roundNo: Option[Int] = None,
    roundId: Option[String] = None
  ): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = scope,
        executor = "general",
        cwd = wsPath,
        project = None,
        taskId = None,
        parentSessionId = "root-sid",
        title = "recovery-summary spec",
        createdAt = now,
        archivedAt = archivedAt,
        roundNo = roundNo,
        roundId = roundId
      )
    )

  private def seedScope(
    scope: String,
    nodes: List[NodeDef],
    holds: Set[String] = Set.empty
  ): IO[Unit] =
    FlowMapStore.openScope(scope, "rs-spec-project", wsPath).flatMap { store =>
      store.mutate { st =>
        st.copy(nodes = nodes.map(n => n.id -> n).toMap, manualRecoveryNodes = holds)
      }.void
    }

  private def pauseChain(scope: String, chainId: String): IO[Unit] =
    val dir = RecoverySummary.scopeDirOf(wsPath, scope)
    ChainLedgerStore
      .open("rs-spec-project", dir / ChainLedger.FileName, dir / ChainLedger.ArchiveDirName)
      .flatMap(_.setChainControl(chainId, ChainLedger.StatusPaused, now))
      .void

  private def gen(scope: String): RecoverySummary.Summary =
    RecoverySummary.generate(wsPath, scope).unsafeRunSync() match
      case Right(s) => s
      case Left(err) => fail(s"generate failed for '$scope': $err")

  private def inspect(scope: String): RecoverySummary.Verdict =
    RecoverySummary.inspect(wsPath, scope).unsafeRunSync()

  private def expectTrusted(scope: String): RecoverySummary.Summary =
    inspect(scope) match
      case RecoverySummary.Verdict.Trusted(s) => s
      case RecoverySummary.Verdict.Fallback(r) => fail(s"expected Trusted for '$scope', got Fallback($r)")

  private def expectFallback(scope: String)(prefix: String): String =
    inspect(scope) match
      case RecoverySummary.Verdict.Fallback(r) =>
        assert(r.startsWith(prefix), s"expected fallback prefix '$prefix', got: $r")
        r
      case RecoverySummary.Verdict.Trusted(_) => fail(s"expected Fallback($prefix...) for '$scope', got Trusted")

  /** 载荷面篡改（不动 digest / schema——分别喂 digest 闸与 schema 闸的探针）。 */
  private def tamperJson(scope: String)(f: io.circe.Json => io.circe.Json): Unit =
    val p = RecoverySummary.summaryPath(wsPath, scope)
    val json = io.circe.parser.parse(os.read(p)).fold(e => fail(s"unparseable: $e"), identity)
    os.write.over(p, f(json).noSpaces)

  private def debtFileOf(scope: String): os.Path =
    PathUtil.dataRoot / "sessions" / SessionMailbox.safeId(scope) / "completion-debt.json"

  // ── 阶段① 子项档（subagent-tasks/<scope>.json，写真行经 shared SubAgentTask codec）──

  private def childFileOf(scope: String): os.Path =
    PathUtil.dataRoot / "subagent-tasks" / s"$scope.json"

  private def childRow(
    scope: String,
    id: String,
    status: String,
    executionStatus: Option[String] = None,
    incomplete: Option[Boolean] = None
  ): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = scope,
      agentName = "general",
      prompt = "",
      description = id,
      status = status,
      retryCount = 0,
      spawnedAt = now,
      completedAt = None,
      lastError = None,
      source = "delegate",
      executionStatus = executionStatus,
      incomplete = incomplete
    )

  private def writeChildren(scope: String, rows: List[SubAgentTask]): Unit =
    os.write.over(childFileOf(scope), rows.asJson.noSpaces, createFolders = true)

  // ── 状态面①：非终态节点 ─────────────────────────────────────────────────

  test("facet① green: all-terminal scope counts zero non-terminal nodes and is quiet") {
    val scope = "rs-nt-green"
    // 阶段① 起 quiet 含判据①身份终态：正向腿的作用域一律取「已归档」身份（真终态形态）。
    putRecord(scope, archivedAt = Some(now))
    seedScope(
      scope,
      List(
        terminalNode("ntg-done", NodeLifecycle.Completed),
        terminalNode("ntg-fail", NodeLifecycle.Failed),
        terminalNode("ntg-cancel", NodeLifecycle.Cancelled)
      )
    ).unsafeRunSync()

    val s = gen(scope)
    assertEquals(s.facets.nonTerminalNodes.count, 0)
    assertEquals(s.facets.nonTerminalNodes.ids, Nil)
    assertEquals(s.facets.nonTerminalNodes.byStatus, Nil)
    assert(s.quiet, "all-terminal + notified scope must be quiet")
    assert(expectTrusted(scope).quiet, "inspect must trust and confirm quiet")
  }

  test("facet① red: running/pending/interrupted/legacy-blocked are all counted non-terminal") {
    val scope = "rs-nt-red"
    putRecord(scope)
    seedScope(
      scope,
      List(
        mkNode("ntr-run", NodeLifecycle.Running),
        mkNode("ntr-pend", NodeLifecycle.Pending),
        mkNode("ntr-int", NodeLifecycle.Interrupted),
        mkNode("ntr-block", NodeLifecycle.Blocked), // legacy retired token — still non-terminal
        terminalNode("ntr-done", NodeLifecycle.Completed)
      )
    ).unsafeRunSync()

    val s = gen(scope)
    assertEquals(s.facets.nonTerminalNodes.count, 4)
    assertEquals(s.facets.nonTerminalNodes.ids, List("ntr-block", "ntr-int", "ntr-pend", "ntr-run"))
    assertEquals(
      s.facets.nonTerminalNodes.byStatus,
      List(("blocked", 1), ("interrupted", 1), ("pending", 1), ("running", 1))
    )
    assert(!s.quiet, "non-terminal nodes must break quiet")
  }

  // ── 状态面②：人工保持 ───────────────────────────────────────────────────

  test("facet② green: no manual recovery holds counts zero") {
    val scope = "rs-hh-green"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("hhg-done", NodeLifecycle.Completed))).unsafeRunSync()

    val s = gen(scope)
    assertEquals(s.facets.humanHolds.count, 0)
    assertEquals(s.facets.humanHolds.ids, Nil)
    assert(s.quiet)
  }

  test("facet② red: manualRecoveryNodes recorded in full (off-graph ghost refs included)") {
    val scope = "rs-hh-red"
    putRecord(scope)
    seedScope(
      scope,
      List(terminalNode("hhr-done", NodeLifecycle.Completed)),
      holds = Set("hhr-done", "hhr-ghost-gone") // ghost = hold on a node no longer in the map
    ).unsafeRunSync()

    val s = gen(scope)
    assertEquals(s.facets.humanHolds.count, 2)
    assertEquals(s.facets.humanHolds.ids, List("hhr-done", "hhr-ghost-gone"))
    assert(!s.quiet, "human holds must break quiet (ghost refs are still human gates)")
  }

  // ── 状态面③：孤儿暂停 ───────────────────────────────────────────────────

  test("facet③ green: registry record present and no paused chains is not orphan") {
    val scope = "rs-or-green"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("org-done", NodeLifecycle.Completed))).unsafeRunSync()

    val s = gen(scope)
    assert(!s.facets.orphanSuspension.orphanCandidate)
    assertEquals(s.facets.orphanSuspension.pausedChains, Nil)
    assert(s.quiet)
  }

  test("facet③ red: scope with flow map but no registry record is an orphan candidate") {
    val scope = "rs-or-red"
    // deliberately NO DelegateRegistry record
    seedScope(scope, List(terminalNode("orr-done", NodeLifecycle.Completed))).unsafeRunSync()

    val s = gen(scope)
    assert(
      s.facets.orphanSuspension.orphanCandidate,
      "missing registry record = orphan (recoverDelegateScope judgement)"
    )
    assert(s.facets.orphanSuspension.registryArchivedAt.isEmpty)
    assert(!s.quiet, "orphan candidate must break quiet — conservative full load")
  }

  test("facet③ red: paused chain recorded even with a live registry record") {
    val scope = "rs-or-paused"
    putRecord(scope)
    seedScope(scope, List(terminalNode("orp-done", NodeLifecycle.Completed))).unsafeRunSync()
    pauseChain(scope, "chain-x").unsafeRunSync()

    val s = gen(scope)
    assert(!s.facets.orphanSuspension.orphanCandidate)
    assertEquals(s.facets.orphanSuspension.pausedChains, List("chain-x"))
    assert(!s.quiet, "paused chain must break quiet")
  }

  // ── 状态面④：轮次 ───────────────────────────────────────────────────────

  test("facet④ green: no open rounds (settled round does not hold)") {
    val scope = "rs-rd-green"
    putRecord(scope, archivedAt = Some(now), roundNo = Some(1), roundId = Some("rid-g1"))
    seedScope(scope, List(terminalNode("rdg-done", NodeLifecycle.Completed))).unsafeRunSync()
    DelegateRoundLedger.openRound(scope, 1, "rid-g1", DelegateRoundLedger.CauseSpawn, None, None)
    DelegateRoundLedger.settleRound(scope, 1, "rid-g1", "pass", Nil, None)

    val s = gen(scope)
    assertEquals(s.facets.rounds.openRoundNos, Nil)
    assertEquals(s.facets.rounds.currentRoundNo, Some(1)) // pointer is observation only
    assertEquals(s.facets.rounds.currentRoundId, Some("rid-g1"))
    assert(s.quiet, "settled round + clean scope must be quiet")
  }

  test("facet④ red: unsettled round recorded as open") {
    val scope = "rs-rd-red"
    putRecord(scope, roundNo = Some(1), roundId = Some("rid-r1"))
    seedScope(scope, List(terminalNode("rdr-done", NodeLifecycle.Completed))).unsafeRunSync()
    DelegateRoundLedger.openRound(scope, 1, "rid-r1", DelegateRoundLedger.CauseSpawn, None, None)

    val s = gen(scope)
    assertEquals(s.facets.rounds.openRoundNos, List(1))
    assert(!s.quiet, "unsettled round must break quiet")
  }

  // ── 状态面⑤：待投递 ─────────────────────────────────────────────────────

  test("facet⑤ green: consumed messages / no debt / notified terminals / delivered roots count zero") {
    val scope = "rs-pd-green"
    putRecord(scope, archivedAt = Some(now))
    val rootDelivered =
      NodeDef(
        id = "pdg-root",
        name = "pdg-root",
        agent = "general",
        status = NodeLifecycle.Completed,
        result = Some("done"),
        out = List(OutEdge(OutEdge.RootTarget, Set(OutEdge.Pass), OutEdge.Result)),
        notifySentAt = Some(now),
        nebulaDeliveredAt = Some(now), // delivered ⇒ not an undelivered root result
        createdAt = now
      )
    seedScope(scope, List(terminalNode("pdg-done", NodeLifecycle.Completed), rootDelivered)).unsafeRunSync()
    NodeMessageLedger
      .append(
        wsPath,
        NodeMessageLedger.Row(
          id = NodeMessageLedger.newId(),
          event = NodeMessageLedger.Consumed, // consumed ⇒ not pending
          nodeId = "pdg-done",
          nodeName = "pdg-done",
          text = "hi",
          route = NodeMessageLedger.RouteInject,
          ts = now
        )
      )
      .unsafeRunSync()

    val s = gen(scope)
    assertEquals(s.facets.pendingDeliveries.nodeMessages, 0)
    assert(!s.facets.pendingDeliveries.completionDebt)
    assertEquals(s.facets.pendingDeliveries.unnotifiedTerminals, Nil)
    assertEquals(s.facets.pendingDeliveries.undeliveredRootResults, Nil)
    assertEquals(s.facets.pendingDeliveries.total, 0)
    assert(s.quiet)
  }

  test("facet⑤ red: all four delivery legs recorded") {
    val scope = "rs-pd-red"
    putRecord(scope)
    val failedUnnotified =
      NodeDef(
        id = "pdr-failed",
        name = "pdr-failed",
        agent = "general",
        status = NodeLifecycle.Failed,
        result = Some("boom"), // failed + result + no notifySentAt ⇒ unnotified terminal
        createdAt = now
      )
    val rootUndelivered =
      NodeDef(
        id = "pdr-root",
        name = "pdr-root",
        agent = "general",
        status = NodeLifecycle.Completed,
        result = Some("done"),
        out = List(OutEdge(OutEdge.RootTarget, Set(OutEdge.Pass), OutEdge.Result)),
        notifySentAt = Some(now), // dispatcher leg satisfied …
        nebulaDeliveredAt = None, // … but root result leg still owed
        createdAt = now
      )
    seedScope(scope, List(terminalNode("pdr-clean", NodeLifecycle.Completed), failedUnnotified, rootUndelivered))
      .unsafeRunSync()
    NodeMessageLedger
      .append(
        wsPath,
        NodeMessageLedger.Row(
          id = NodeMessageLedger.newId(),
          event = NodeMessageLedger.Queued, // queued ⇒ pending
          nodeId = "pdr-clean",
          nodeName = "pdr-clean",
          text = "hi",
          route = NodeMessageLedger.RouteInject,
          ts = now
        )
      )
      .unsafeRunSync()
    CompletionDebtStore.record(scope, "root-sid", Nil).unsafeRunSync() // phase=deliverable ⇒ owed

    val s = gen(scope)
    assertEquals(s.facets.pendingDeliveries.nodeMessages, 1)
    assert(s.facets.pendingDeliveries.completionDebt)
    assertEquals(s.facets.pendingDeliveries.unnotifiedTerminals, List("pdr-failed"))
    assertEquals(s.facets.pendingDeliveries.undeliveredRootResults, List("pdr-root"))
    assert(s.facets.pendingDeliveries.total >= 4)
    assert(!s.quiet, "any delivery leg owed must break quiet")
  }

  test("facet⑤ red: corrupted completion-debt file on disk counts as pending (conservative)") {
    val scope = "rs-pd-debt-corrupt"
    putRecord(scope)
    seedScope(scope, List(terminalNode("pdc-done", NodeLifecycle.Completed))).unsafeRunSync()
    os.write.over(debtFileOf(scope), "{not-json", createFolders = true)

    val s = gen(scope)
    assert(s.facets.pendingDeliveries.completionDebt, "unreadable debt must count as owed, never skipped")
    assert(!s.quiet)
  }

  // ── 保守回退（红）与正常校验（绿）──────────────────────────────────────

  test("fallback red: missing summary file → Fallback(missing) — full-load semantics") {
    val scope = "rs-v-missing"
    putRecord(scope)
    seedScope(scope, Nil).unsafeRunSync() // scope dir exists, but no summary written

    expectFallback(scope)("missing:")
  }

  test("fallback red: garbage bytes → Fallback(corrupt)") {
    val scope = "rs-v-garbage"
    putRecord(scope)
    seedScope(scope, Nil).unsafeRunSync()
    gen(scope) // write a valid summary first
    os.write.over(RecoverySummary.summaryPath(wsPath, scope), " garbage not json {")

    expectFallback(scope)("corrupt:")
  }

  test("fallback red: payload tampered without digest → Fallback(corrupt: digest mismatch)") {
    val scope = "rs-v-tamper"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("vt-done", NodeLifecycle.Completed))).unsafeRunSync()
    val generated = gen(scope)
    assert(generated.quiet)

    // flip a facet value in place; schema & digest fields untouched ⇒ digest gate must catch it
    tamperJson(scope) { j =>
      j.hcursor
        .downField("facets")
        .downField("nonTerminalNodes")
        .downField("count")
        .set(42.asJson)
        .top
        .getOrElse(fail("tamper path missing"))
    }

    val r = expectFallback(scope)("corrupt:")
    assert(r.contains("digest"), s"must be the digest gate that rejects, got: $r")
    // full-load semantics preserved: the tampered file can never be read as quiet
    inspect(scope) match
      case RecoverySummary.Verdict.Fallback(_) => // expected
      case RecoverySummary.Verdict.Trusted(s) => fail(s"tampered summary trusted as quiet=${s.quiet}")
  }

  test("fallback red: schema bumped → Fallback(schema)") {
    val scope = "rs-v-schema"
    putRecord(scope)
    seedScope(scope, Nil).unsafeRunSync()
    gen(scope)
    tamperJson(scope) { j =>
      j.hcursor.downField("schema").set((RecoverySummary.SchemaVersion + 1).asJson).top.getOrElse(fail("no schema"))
    }

    expectFallback(scope)("schema:")
  }

  test("fallback red: source drift after generation → Fallback(stale)") {
    val scope = "rs-v-stale"
    putRecord(scope)
    seedScope(scope, List(terminalNode("vs-done", NodeLifecycle.Completed))).unsafeRunSync()
    gen(scope)
    expectTrusted(scope) // fresh right after generation

    DelegateRoundLedger.openRound(scope, 7, "rid-vs-7", DelegateRoundLedger.CauseSpawn, None, None) // facts moved

    val r = expectFallback(scope)("stale:")
    assert(r.contains("roundsLedger"), s"drift must name the moved source, got: $r")
  }

  test("fallback red: invalid scope id → Fallback(invalid-scope); generate refuses Left") {
    expectFallback("bad scope with spaces")("invalid-scope:")

    val res = RecoverySummary.generate(wsPath, "bad/scope").unsafeRunSync()
    res match
      case Left(err) => assert(err.contains("invalid scope id"))
      case Right(_) => fail("generate must refuse invalid scope ids")
  }

  test("round-trip green: generate → inspect returns the byte-identical trusted summary") {
    val scope = "rs-roundtrip"
    putRecord(scope, roundNo = Some(2), roundId = Some("rid-rt2"))
    seedScope(
      scope,
      List(
        mkNode("rt-run", NodeLifecycle.Running),
        terminalNode("rt-done", NodeLifecycle.Completed)
      ),
      holds = Set("rt-done")
    ).unsafeRunSync()
    DelegateRoundLedger.openRound(scope, 2, "rid-rt2", DelegateRoundLedger.CauseSpawn, None, None)
    pauseChain(scope, "chain-rt").unsafeRunSync()

    val generated = gen(scope)
    assertEquals(inspect(scope), RecoverySummary.Verdict.Trusted(generated))
    assert(!generated.quiet)
    // spot-check facet fidelity survived the disk round-trip
    val back = expectTrusted(scope)
    assertEquals(back.facets.nonTerminalNodes.ids, List("rt-run"))
    assertEquals(back.facets.humanHolds.ids, List("rt-done"))
    assertEquals(back.facets.orphanSuspension.pausedChains, List("chain-rt"))
    assertEquals(back.facets.rounds.openRoundNos, List(2))
  }

  test("archivedAt trap: archived record with running node is NOT quiet; archived clean scope IS quiet") {
    // dirty leg — the 687-archived / 13-with-live-state shape from the inventory audit
    val dirty = "rs-arch-dirty"
    putRecord(dirty, archivedAt = Some(now))
    seedScope(dirty, List(mkNode("ad-run", NodeLifecycle.Running))).unsafeRunSync()
    val dirtySummary = gen(dirty)
    assertEquals(dirtySummary.facets.orphanSuspension.registryArchivedAt, Some(now))
    assert(!dirtySummary.quiet, "archivedAt alone must NEVER justify skipping load")
    assert(!expectTrusted(dirty).quiet)

    // clean leg — archived and genuinely settled: quiet is derived from facets only
    val clean = "rs-arch-clean"
    putRecord(clean, archivedAt = Some(now))
    seedScope(clean, List(terminalNode("ac-done", NodeLifecycle.Completed))).unsafeRunSync()
    val cleanSummary = gen(clean)
    assertEquals(cleanSummary.facets.orphanSuspension.registryArchivedAt, Some(now))
    assert(cleanSummary.quiet, "archived + all-facets-clean IS quiet (archivedAt is observation, not a blocker)")
    assert(expectTrusted(clean).quiet)
  }

  // ── 阶段①：完整可见性判据的七面（身份终态 ① / 子项 ②）与查询面 ──────────────

  test("stage① facet: terminal state is derived from the registry record (archive + four live marks)") {
    val scope = "rs-v2-terminal"
    putRecord(scope) // 未归档 ⇒ 非终态
    seedScope(scope, List(terminalNode("v2t-done", NodeLifecycle.Completed))).unsafeRunSync()
    val unarchived = gen(scope).facets.terminal
    assert(!unarchived.terminal && !unarchived.archived)
    assertEquals(unarchived.archivedAt, None)
    assert(unarchived.frozen === false && unarchived.standby === false)

    putRecord(scope, archivedAt = Some(now)) // 存量归档旗（只读兼容证据）⇒ 状态派生终态
    val archived = gen(scope).facets.terminal
    assert(archived.terminal && archived.archived)
    assert(archived.archivedAt.isDefined)

    DelegateRegistry.markCancelRequested(scope, "the user panel") // 活标记 ⇒ 回落非终态
    val marked = gen(scope).facets.terminal
    assert(!marked.terminal && marked.archived && marked.cancelRequested)

    val noRecord = "rs-v2-terminal-missing"
    seedScope(noRecord, List(terminalNode("v2tm-done", NodeLifecycle.Completed))).unsafeRunSync()
    val missing = gen(noRecord).facets.terminal
    assert(!missing.terminal && !missing.archived, "no evidence is never read as terminal")
  }

  test("stage① facet: child items count only non-settled rows; missing file = zero; corrupt file = conservative 1") {
    val scope = "rs-v2-children"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("v2c-done", NodeLifecycle.Completed))).unsafeRunSync()

    assert(!os.exists(childFileOf(scope)))
    val absent = gen(scope).facets.childItems
    assertEquals(absent.count, 0)
    assert(!absent.fileUnreadable)

    writeChildren(
      scope,
      List(
        childRow(scope, "delegate-ok", "completed", Some("finished")),
        childRow(scope, "delegate-cancelled", "cancelled", Some("cancelled")),
        childRow(scope, "delegate-live", "running", Some("running")),
        childRow(scope, "delegate-holdmerge", "ready-to-merge"),
        childRow(scope, "delegate-recovery", "pending-recovery", Some("interrupted")),
        childRow(scope, "delegate-incomplete", "completed", Some("finished"), incomplete = Some(true))
      )
    )
    val mixed = gen(scope).facets.childItems
    assertEquals(mixed.count, 4)
    assertEquals(
      mixed.ids,
      List("delegate-holdmerge", "delegate-incomplete", "delegate-live", "delegate-recovery")
    )
    assertEquals(
      mixed.byStatus,
      List(("completed", 1), ("pending-recovery", 1), ("ready-to-merge", 1), ("running", 1))
    )
    assert(!mixed.fileUnreadable)

    os.write.over(childFileOf(scope), "{ not a json array")
    val corrupt = gen(scope).facets.childItems
    assert(corrupt.fileUnreadable, "a present-but-corrupt child file must fall back conservatively")
    assertEquals(corrupt.count, 1)
    assertEquals(corrupt.ids, List(RecoverySummary.ChildItems.UnreadableId))
  }

  test("stage①: blockers is empty exactly when quiet, and every open item carries its own unit") {
    val scope = "rs-v2-blockers"
    putRecord(scope) // 未归档身份 + 无记录子项 + 开轮 + 人工保持 + 非终态节点
    seedScope(
      scope,
      List(mkNode("v2b-run", NodeLifecycle.Running), terminalNode("v2b-done", NodeLifecycle.Completed)),
      holds = Set("v2b-done")
    ).unsafeRunSync()
    DelegateRoundLedger.openRound(scope, 1, "rid-v2b", DelegateRoundLedger.CauseSpawn, None, None)
    pauseChain(scope, "chain-v2b").unsafeRunSync()

    val s = gen(scope)
    assertEquals(
      s.blockers,
      List(
        "identity-not-settled",
        "non-terminal-nodes:1",
        "human-holds:1",
        "paused-chains:1",
        "open-rounds:1"
      )
    )
    assert(!s.quiet, "non-empty blockers ⇒ not quiet")

    // 面视图与七判据的差异面（阶段① 红绿验收钉的就是它）：五面全净但身份未终态。
    val facetsOnly = "rs-v2-blockers-facetsonly"
    putRecord(facetsOnly)
    seedScope(facetsOnly, List(terminalNode("v2bf-done", NodeLifecycle.Completed))).unsafeRunSync()
    val fs = gen(facetsOnly)
    assert(fs.facetsClean, "five-facet view is clean here")
    assert(!fs.quiet, "…but the five-facet view must never be used as the defer gate")
    assertEquals(fs.blockers, List("identity-not-settled"))

    // 全净⇒空表（同一作用域清干净：归档身份、零保持、零开轮、零暂停、全终态节点）
    val clean = "rs-v2-blockers-clean"
    putRecord(clean, archivedAt = Some(now))
    seedScope(clean, List(terminalNode("v2bc-done", NodeLifecycle.Completed))).unsafeRunSync()
    val cs = gen(clean)
    assertEquals(cs.blockers, Nil)
    assert(cs.quiet && cs.facetsClean)
  }

  test("stage① query: Trusted carries deferrable == quiet; Fallback is never deferrable") {
    val scope = "rs-v2-query"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("v2q-done", NodeLifecycle.Completed))).unsafeRunSync()
    gen(scope)

    val trusted = RecoverySummary.query(wsPath, scope).unsafeRunSync()
    assert(trusted.deferrable, s"expected deferrable, blockers=${trusted.blockers}")
    assertEquals(trusted.blockers, Nil)
    trusted.verdict match
      case RecoverySummary.Verdict.Trusted(s) => assert(s.quiet)
      case RecoverySummary.Verdict.Fallback(r) => fail(s"expected Trusted, got Fallback($r)")

    DelegateRoundLedger.openRound(scope, 3, "rid-v2q", DelegateRoundLedger.CauseSpawn, None, None) // facts moved
    val stale = RecoverySummary.query(wsPath, scope).unsafeRunSync()
    assert(!stale.deferrable)
    assert(stale.blockers.exists(_.startsWith("summary-fallback:stale")), s"got ${stale.blockers}")

    val missing = RecoverySummary.query(wsPath, "rs-v2-query-missing").unsafeRunSync()
    assert(!missing.deferrable)
    assert(missing.blockers.exists(_.startsWith("summary-fallback:missing")), s"got ${missing.blockers}")

    val invalid = RecoverySummary.query(wsPath, "bad scope with spaces").unsafeRunSync()
    assert(!invalid.deferrable)
    assert(invalid.blockers.exists(_.startsWith("summary-fallback:invalid-scope")), s"got ${invalid.blockers}")
  }

  test("stage① compat: a v1 summary (pre-stage① payload) falls back on the schema gate, never silently quiet") {
    val scope = "rs-v2-v1compat"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("v1c-done", NodeLifecycle.Completed))).unsafeRunSync()
    gen(scope) // v2 档先落盘

    // 降格成 v1 形态：schema=1、facets 摘掉阶段①两面、digest 保持原值（schema 闸先行）
    tamperJson(scope) { j =>
      val dropped = j.hcursor.downField("facets").downField("terminal").delete.top.getOrElse(j)
      val dropped2 = dropped.hcursor.downField("facets").downField("childItems").delete.top.getOrElse(dropped)
      dropped2.hcursor.downField("schema").set(1.asJson).top.getOrElse(dropped2)
    }

    expectFallback(scope)("schema:")
    val q = RecoverySummary.query(wsPath, scope).unsafeRunSync()
    assert(!q.deferrable)
    assert(q.blockers.exists(_.startsWith("summary-fallback:schema")), s"got ${q.blockers}")
  }

end RecoverySummarySpec
