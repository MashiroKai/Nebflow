package nebflow.core.project

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.delegate.DelegateRegistry
import nebflow.shared.{PathUtil, SubAgentTask}

import scala.concurrent.duration.*

/**
 * RecoverySummary 完整可见性红绿验收（delegate-lifecycle-reform 阶段① · 2026-11-17）。
 *
 * 规格 = 作者「完整可见性判据」：一个 delegate 作用域只有**同时**满足下面七条，才可判为
 * 「无待处理事实」（`Summary.quiet` ⇒ 后续延后加载批可跳过完整打开）：
 *
 *   ①身份终态（terminal，delegate 记录已归档且无 standby/frozen/restart-interrupted/
 *     cancel 活标记）②无子项（scope 的子代理任务行全部终态）③无非终态节点
 *   ④无人工保持 ⑤无孤儿暂停 ⑥无未结算轮 ⑦无未投递事项。
 *
 * 本 spec 是**阶段① 实施前的真实行为红**（基线 commit 0f9f5aab7 的 `Summary.quiet`
 * 只算了后五项）：所有断言都落在既有 `Summary.quiet` 面上——断言编译得过（不吃编译
 * 错误当红），但 ①② 两条漏判使 1–6 号用例在基线上失败。实施后同一组断言逐条转绿；
 * 7/8/9 号（13 个反例、全净正向腿、子项档缺失正向腿）在前后都必须为绿——它们是
 * 「归档 ≠ 安全跳过」的对照面，不是红。
 *
 * 实证口径（2026-11-17 盘面只读统计）：delegate 注册目录 910 条记录、863 条已归档，
 * 其中 54 条仍带活标记（restartInterruptedAt 22 / cancelRequestedAt 32）；带非终态
 * 子项行的父会话 11 个，其中仅 1 个身份同时已归档（`delegate-nebflow-f28d909a`）
 * ——即旧五项判据今日会漏判的真实规模。数字是采样口径，不是判据输入。
 *
 * 隔离数据根纪律 = RecoverySummarySpec 同款（类体 setDataRoot + afterAll 还原）；
 * 全部走 store 级 API（零 NodeEngine / 零 ActorSystem）。
 */
class RecoverySummaryVisibilitySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-recovery-visibility"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  private val ws: os.Path = tempRoot / "ws"
  private val wsPath: String = ws.toString
  private val now: Long = System.currentTimeMillis()

  // ── 基建 ─────────────────────────────────────────────────────────────────

  /** 已通知终态节点（五项判据全净的标准积木：不进非终态面／不进待投递面）。 */
  private def terminalNode(id: String): NodeDef =
    NodeDef(
      id = id,
      name = id,
      agent = "general",
      status = NodeLifecycle.Completed,
      result = Some(s"$id done"),
      notifySentAt = Some(now),
      createdAt = now
    )

  private def mkNode(id: String, status: String): NodeDef =
    NodeDef(id = id, name = id, agent = "general", status = status, createdAt = now)

  private def putRecord(scope: String, archivedAt: Option[Long] = None): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = scope,
        executor = "general",
        cwd = wsPath,
        project = None,
        taskId = None,
        parentSessionId = "root-sid",
        title = "recovery-summary visibility spec",
        createdAt = now,
        archivedAt = archivedAt
      )
    )

  private def seedScope(scope: String, nodes: List[NodeDef]): IO[Unit] =
    FlowMapStore.openScope(scope, "rs-visibility-project", wsPath).flatMap { store =>
      store.mutate(st => st.copy(nodes = nodes.map(n => n.id -> n).toMap)).void
    }

  private def gen(scope: String): RecoverySummary.Summary =
    RecoverySummary.generate(wsPath, scope).unsafeRunSync() match
      case Right(s) => s
      case Left(err) => fail(s"generate failed for '$scope': $err")

  // ── 子项（subagent-tasks/<scope>.json，与 SubAgentTaskStore 同布局）──────────

  private def childFileOf(scope: String): os.Path =
    PathUtil.dataRoot / "subagent-tasks" / s"$scope.json"

  private def childRow(
    scope: String,
    id: String,
    status: String,
    executionStatus: Option[String] = None,
    incomplete: Option[Boolean] = None,
    notificationPending: Option[Boolean] = None
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
      incomplete = incomplete,
      notificationPending = notificationPending
    )

  private def writeChildren(scope: String, rows: List[SubAgentTask]): Unit =
    os.write.over(childFileOf(scope), rows.asJson.noSpaces, createFolders = true)

  // ── 判据①：身份终态 ─────────────────────────────────────────────────────

  test("red①: a five-facet-clean scope whose identity is NOT archived (not terminal) is not quiet") {
    val scope = "rv-terminal-unarchived"
    putRecord(scope) // archivedAt = None ⇒ 身份非终态（可能仍 live／可复活）
    seedScope(scope, List(terminalNode("rv-tu-done"))).unsafeRunSync()

    val s = gen(scope)
    assertEquals(s.facets.nonTerminalNodes.count, 0) // 后五项全净——旧判据唯一依据
    assert(
      !s.quiet,
      "non-terminal identity must never read as 'no work left' (clause ① terminal)"
    )
  }

  test("red①: archived identity carrying a live mark (restart-interrupted / cancel tombstone) is not quiet") {
    val scope = "rv-terminal-livemark"
    putRecord(scope, archivedAt = Some(now))
    DelegateRegistry.markCancelRequested(scope, "the user panel")
    seedScope(scope, List(terminalNode("rv-tl-done"))).unsafeRunSync()

    val s = gen(scope)
    assert(!s.quiet, "an archived identity with a cancel tombstone is not terminal (clause ①)")
  }

  // ── 判据②：无子项 ───────────────────────────────────────────────────────

  test("red②: archived scope with one running child item is not quiet") {
    val scope = "rv-child-running"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("rv-cr-done"))).unsafeRunSync()
    writeChildren(scope, List(childRow(scope, "delegate-child-a", "running", Some("running"))))

    val s = gen(scope)
    assert(!s.quiet, "a live child item must never read as 'no work left' (clause ② child items)")
  }

  test("red②: archived scope with a ready-to-merge child item is not quiet") {
    val scope = "rv-child-holdmerges"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("rv-ch-done"))).unsafeRunSync()
    writeChildren(scope, List(childRow(scope, "delegate-child-b", "ready-to-merge")))

    val s = gen(scope)
    assert(!s.quiet, "ready-to-merge = produced but unlanded work (clause ②)")
  }

  test("red②: archived scope with a pending-recovery child row is not quiet") {
    val scope = "rv-child-pending-recovery"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("rv-cp-done"))).unsafeRunSync()
    writeChildren(scope, List(childRow(scope, "delegate-child-c", "pending-recovery", Some("interrupted"))))

    val s = gen(scope)
    assert(!s.quiet, "pending-recovery = awaiting recovery, not settled (clause ②)")
  }

  test("red②: archived scope with an incomplete child row is not quiet (missing keys are never read as false)") {
    val scope = "rv-child-incomplete"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("rv-ci-done"))).unsafeRunSync()
    writeChildren(
      scope,
      List(childRow(scope, "delegate-child-d", "completed", Some("finished"), incomplete = Some(true)))
    )

    val s = gen(scope)
    assert(!s.quiet, "incomplete=true = the run did not finish its work (clause ②)")
  }

  test("red②: archived scope with an unreadable child-task file is not quiet (conservative)") {
    val scope = "rv-child-unreadable"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("rv-cu-done"))).unsafeRunSync()
    os.write.over(childFileOf(scope), "{ not a json array", createFolders = true)

    val s = gen(scope)
    assert(!s.quiet, "a present-but-corrupt child file must fall back conservatively, never read as 'no children'")
  }

  // ── 反例面：13 个「身份已归档仍有非终态／旧状态节点」的作用域 ────────────────

  test("counterexamples: 13 formerly archived scopes with non-terminal / retired-state nodes are never quiet") {
    // 固定启动样本的实证形态（清单 §作用域：687 归档身份中 13 个仍带非终态节点；
    // 活动节点状态分布 completed 46 / failed 3 / cancelled 5 / pending 101 / running 1 /
    // wiring 3 / legacy blocked 29）——按此分布取 13 例，逐例断言「归档 ≠ 安全跳过」。
    val statuses = List(
      NodeLifecycle.Running,
      NodeLifecycle.Pending,
      NodeLifecycle.Pending,
      NodeLifecycle.Pending,
      NodeLifecycle.Wiring,
      NodeLifecycle.Interrupted,
      NodeLifecycle.Blocked,
      NodeLifecycle.Blocked,
      NodeLifecycle.Pending,
      NodeLifecycle.Wiring,
      NodeLifecycle.Interrupted,
      NodeLifecycle.Blocked,
      NodeLifecycle.Pending
    )
    statuses.zipWithIndex.foreach { case (status, i) =>
      val scope = s"rv-counter-$i"
      putRecord(scope, archivedAt = Some(now))
      seedScope(scope, List(terminalNode(s"rv-c$i-done"), mkNode(s"rv-c$i-live", status))).unsafeRunSync()

      val s = gen(scope)
      assertEquals(s.facets.nonTerminalNodes.ids, List(s"rv-c$i-live"))
      assert(
        !s.quiet,
        s"scope '$scope' is archived but holds a '$status' node — archivedAt alone must never justify skipping load"
      )
    }
  }

  // ── 正向腿（前后都必须为绿）──────────────────────────────────────────────

  test("green: archived identity + all children terminal + five facets clean IS quiet") {
    val scope = "rv-green-all-terminal-children"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("rv-gc-done"))).unsafeRunSync()
    writeChildren(
      scope,
      List(
        childRow(scope, "delegate-child-ok", "completed", Some("finished")),
        childRow(scope, "delegate-child-cancelled", "cancelled", Some("cancelled")),
        childRow(scope, "delegate-child-failed", "failed", Some("failed"))
      )
    )

    val s = gen(scope)
    assert(s.quiet, "terminal identity + terminal children + clean facets is the only deferrable shape")
  }

  test("green: a scope with no child-task file at all IS quiet (missing file = no children, not unreadable)") {
    val scope = "rv-green-no-child-file"
    putRecord(scope, archivedAt = Some(now))
    seedScope(scope, List(terminalNode("rv-gn-done"))).unsafeRunSync()
    assert(!os.exists(childFileOf(scope)))

    val s = gen(scope)
    assert(s.quiet, "no child-task file = zero children recorded; only a present-but-corrupt file is conservative-red")
  }

end RecoverySummaryVisibilitySpec
