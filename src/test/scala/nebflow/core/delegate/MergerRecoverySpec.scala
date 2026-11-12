package nebflow.core.delegate

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentDef, RootAgentIdentity}
import nebflow.core.delegate.WorkspaceLedger.{MergeItem, MergeRequest, MergerRelease}
import nebflow.shared.PathUtil

/**
 * Cancelled-merger recovery 批 spec：被取消 merger 身份的**受支持释放/替换面**。
 *
 * 红绿锚：
 *  - 红（修前）：`WorkspaceLedger.mergers` 终身记录一个身份；其 delegate 会话被取消
 *    （取消墓碑——复活被永久拒绝）后，`Delegate(mode=merger)` 永远返回死地址
 *    （taskAccepted=false），无受支持的释放/替换入口——集成永久阻塞
 *    （实证 delegate-nebflow-f2f610f2）。
 *  - 绿（修后）：[[WorkspaceLedger.releaseMerger]] 单事务门禁释放 +
 *    [[MergerRecovery]] 服务编排（可信 root 在工具面、墓碑证明确认、活性/租约/
 *    在飞集成/占用 fail-closed、幂等重放、崩溃恢复可验证）。
 */
class MergerRecoverySpec extends CatsEffectSuite:

  private val previousRoot = PathUtil.dataRoot
  private var root = os.pwd

  override def beforeEach(context: munit.BeforeEach): Unit =
    root = os.temp.dir(prefix = "merger-recovery-")
    PathUtil.setDataRoot(root)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(previousRoot)
    os.remove.all(root)

  private val Project = "proj"
  private val Repository = "/repo"
  private val MergerId = "delegate-nebflow-deadbee"

  /** Record shape valid enough for DelegateRegistry.put. */
  private def record(id: String) = DelegateRegistry.Record(
    id = id,
    executor = "nebflow",
    cwd = root.toString,
    project = Some(Project),
    taskId = None,
    parentSessionId = "root-1",
    title = "cancelled merger fixture",
    createdAt = System.currentTimeMillis() - 60_000L,
    mode = Some("merger")
  )

  /** Register the merger identity and cancel its delegate (proven tombstone). */
  private def cancelledMerger(ledger: WorkspaceLedger, id: String = MergerId, by: String = "the user panel"): Long =
    ledger.merger(Project, Repository, id)
    DelegateRegistry.put(record(id))
    DelegateRegistry.markCancelRequested(id, by).getOrElse(fail("tombstone write refused"))
  end cancelledMerger

  /** A terminal (cancelled) merge request and its reopened workspace. */
  private def cancelledRequest(ledger: WorkspaceLedger, workspaceId: String = "w1"): Unit =
    ledger.register(
      WorkspaceLedger.Workspace(
        workspaceId, Project, Repository, s"/tmp/$workspaceId", s"delegate/$workspaceId", "main", "base",
        s"owner-$workspaceId", "kernel"
      )
    )
    assert(
      ledger
        .requestMerge(
          MergeRequest("req", Repository, MergerId, "auth", List(MergeItem(workspaceId, "a" * 40, "main")))
        )
        .isRight
    )
    assert(ledger.cancelRequest("req").isRight)
    assert(ledger.releaseUnfinished("req", MergerId, "Merge request cancelled").isRight)
  end cancelledRequest

  /** A ledger file whose single workspace sits reserved for a vanished request. */
  private def ledgerWithStaleReservation(path: os.Path, id: String): WorkspaceLedger =
    os.makeDir.all(path / os.up)
    os.write(
      path,
      s"""{"workspaces":[{"id":"w1","project":"$Project","repository":"$Repository","path":"/tmp/w1",""" +
        """"branch":"delegate/w1","baseBranch":"main","baseCommit":"b","owner":"owner-w1","ownerMode":"kernel",""" +
        """"lifecycle":"reserved","activity":"idle","reservation":"stale-req","revision":3}],""" +
        s""""mergers":[{"project":"$Project","repository":"$Repository","delegateId":"$id"}],"revision":9}"""
    )
    new WorkspaceLedger(path)
  end ledgerWithStaleReservation

  /** Registry + tombstone for a handcrafted ledger file (its merger row is already on disk). */
  private def tombstonedRegistry(id: String = MergerId): Unit =
    DelegateRegistry.put(record(id))
    DelegateRegistry.markCancelRequested(id, "the user panel")
    ()
  end tombstonedRegistry

  /** Workspace row with every derived-codec field (defaults are not honored on decode). */
  private def workspaceRow(lifecycle: String, reservation: Option[String]): String =
    val quoted = reservation.fold("null")(r => "\"" + r + "\"")
    s"""{"id":"w1","project":"$Project","repository":"$Repository","path":"/tmp/w1",""" +
      s""""branch":"delegate/w1","baseBranch":"main","baseCommit":"b","owner":"owner-w1","ownerMode":"kernel",""" +
      s""""lifecycle":"$lifecycle","activity":"idle","reservation":$quoted,"revision":3}"""

  /** MergeRequest row with every derived-codec field (legacy / crash-residue shapes). */
  private def requestRow(id: String, repository: String, workspaceId: String, status: String, itemPhase: String): String =
    s"""{"id":"$id","repository":"$repository","mergerId":"$MergerId","authorization":"auth",""" +
      s""""items":[{"workspaceId":"$workspaceId","sourceCommit":"${"a" * 40}","targetBranch":"main","phase":"$itemPhase",""" +
      s""""mergeCommit":null,"verification":null,"integration":null,"error":null}],""" +
      s""""status":"$status","revision":4,"cancelRequested":false,"events":{}}"""

  /** A ledger file from raw rows; the fixture must decode (read-faces fail closed on corrupt data). */
  private def handcraftedLedger(path: os.Path, workspace: String, request: Option[String] = None): WorkspaceLedger =
    val requestsJson = request.map(r => s"[$r]").getOrElse("[]")
    os.makeDir.all(path / os.up)
    os.write(
      path,
      s"""{"workspaces":[$workspace],"requests":$requestsJson,""" +
        s""""mergers":[{"project":"$Project","repository":"$Repository","delegateId":"$MergerId"}],"revision":9}"""
    )
    val decoded = io.circe.parser.decode[WorkspaceLedger.State](os.read(path))
    assert(decoded.isRight, s"fixture decode failed: ${decoded.left.map(_.getMessage)}")
    new WorkspaceLedger(path)
  end handcraftedLedger

  // ── 台账面（独立 ledger 文件，零 dataRoot 依赖）──────────────────────────

  test("T1 release removes only the identity slot; history (requests, workspaces, seed, tombstone) is preserved") {
    val root = os.temp.dir(prefix = "mr-t1-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      cancelledMerger(ledger)
      cancelledRequest(ledger)
      val tombstoneAt = DelegateRegistry.find(MergerId).get.cancelRequestedAt.get
      val eventId = MergerRecovery.eventIdFor(Repository, MergerId, tombstoneAt)

      val release = ledger.releaseMerger(Project, Repository, MergerId, eventId, "reason").getOrElse(fail("release refused"))
      assertEquals(release.replayed, false)
      assertEquals(release.releasedDelegateId, MergerId)
      assertEquals(release.tombstoneAt, Some(tombstoneAt))
      assertEquals(release.cancelledBy, Some("the user panel"))

      val after = ledger.snapshot
      assertEquals(after.mergers, Nil, "the identity slot is gone")
      assertEquals(after.requests.map(_.id), List("req"), "request history preserved")
      assertEquals(after.requests.head.status, "cancelled", "terminal request untouched")
      assertEquals(after.workspaces.head.lifecycle, "open", "workspace record preserved")
      assertEquals(after.mergerBootstraps, Map.empty[String, WorkspaceLedger.MergerBootstrap], "no stray seeds")
      assert(after.mergerEvents.contains(eventId), "release event recorded")
      assertEquals(DelegateRegistry.find(MergerId).get.cancelRequestedAt, Some(tombstoneAt), "registry tombstone untouched")
    finally os.remove.all(root)
    end try
  }

  test("T2 fail-closed without a cancel tombstone: a merger that is not cancelled keeps its own recovery path") {
    val root = os.temp.dir(prefix = "mr-t2-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      ledger.merger(Project, Repository, MergerId)
      DelegateRegistry.put(record(MergerId)) // no tombstone
      val refused = ledger.releaseMerger(Project, Repository, MergerId, "evt", "reason")
      assert(refused.left.toOption.get.contains("not cancelled"), s"unexpected: $refused")
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(MergerId), "state unchanged")
      assertEquals(ledger.snapshot.mergerEvents, Map.empty[String, String])
    finally os.remove.all(root)
    end try
  }

  test("T3 fail-closed when the delegate record is missing (unclear state)") {
    val root = os.temp.dir(prefix = "mr-t3-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      ledger.merger(Project, Repository, MergerId) // no registry record at all
      val refused = ledger.releaseMerger(Project, Repository, MergerId, "evt", "reason")
      assert(refused.left.toOption.get.contains("unclear"), s"unexpected: $refused")
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(MergerId), "state unchanged")
    finally os.remove.all(root)
    end try
  }

  test("T4 fail-closed while integration is in flight; releasable once terminal; request history kept") {
    val root = os.temp.dir(prefix = "mr-t4-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      cancelledMerger(ledger)
      ledger.register(
        WorkspaceLedger.Workspace("w1", Project, Repository, "/tmp/w1", "delegate/w1", "main", "base", "owner-w1", "kernel")
      )
      assert(
        ledger
          .requestMerge(MergeRequest("req", Repository, MergerId, "auth", List(MergeItem("w1", "a" * 40, "main"))))
          .isRight
      )
      val refused = ledger.releaseMerger(Project, Repository, MergerId, "evt", "reason")
      assert(refused.left.toOption.get.contains("in flight"), s"unexpected: $refused")
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(MergerId), "the identity stays while work runs")

      assert(ledger.cancelRequest("req").isRight)
      assert(ledger.releaseUnfinished("req", MergerId, "cancelled").isRight)
      val tombstoneAt = DelegateRegistry.find(MergerId).get.cancelRequestedAt.get
      val eventId = MergerRecovery.eventIdFor(Repository, MergerId, tombstoneAt)
      assert(ledger.releaseMerger(Project, Repository, MergerId, eventId, "reason").isRight)
      assertEquals(ledger.snapshot.mergers, Nil)
      assertEquals(ledger.snapshot.requests.head.id, "req", "request history kept verbatim")
      assertEquals(ledger.snapshot.requests.head.items.head.phase, "cancelled")
    finally os.remove.all(root)
    end try
  }

  test("T5 fail-closed while a workspace is occupied by merger activity (stale reservation included)") {
    val root = os.temp.dir(prefix = "mr-t5-")
    try
      // Handcrafted state: workspace reserved, request record absent — the occupation
      // gate must fire on the stale state alone (fail-closed on unclear).
      val ledger = ledgerWithStaleReservation(root / "ledger.json", MergerId)
      val decoded = io.circe.parser.decode[WorkspaceLedger.State](os.read(root / "ledger.json"))
      assert(decoded.isRight, s"fixture decode failed: ${decoded.left.map(_.getMessage)}")
      DelegateRegistry.put(record(MergerId))
      DelegateRegistry.markCancelRequested(MergerId, "the user panel")
      val refused = ledger.releaseMerger(Project, Repository, MergerId, "evt", "reason")
      assert(refused.left.toOption.get.contains("occupied"), s"unexpected: $refused")
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(MergerId), "state unchanged")
    finally os.remove.all(root)
    end try
  }

  test("T6 race guard: a moved identity slot is refused, not overwritten; a fresh replacement is not releasable") {
    val root = os.temp.dir(prefix = "mr-t6-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      cancelledMerger(ledger, "delegate-nebflow-oldid")
      val tombstoneAt = DelegateRegistry.find("delegate-nebflow-oldid").get.cancelRequestedAt.get
      val firstEvent = MergerRecovery.eventIdFor(Repository, "delegate-nebflow-oldid", tombstoneAt)
      assert(ledger.releaseMerger(Project, Repository, "delegate-nebflow-oldid", firstEvent, "reason").isRight)

      // The replacement is minted through the normal creation entry (no tombstone).
      ledger.merger(Project, Repository, "delegate-nebflow-newid")
      DelegateRegistry.put(record("delegate-nebflow-newid"))
      val freshRelease =
        ledger.releaseMerger(Project, Repository, "delegate-nebflow-newid", "evt-new", "reason")
      assert(freshRelease.left.toOption.get.contains("not cancelled"), "a non-cancelled replacement must not be releasable")

      // A stale caller still aiming at the old id refuses without side effects.
      val stale = ledger.releaseMerger(Project, Repository, "delegate-nebflow-oldid", "evt-stale", "reason")
      assert(stale.left.toOption.get.contains("identity changed"), s"unexpected: $stale")
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List("delegate-nebflow-newid"))
      assertEquals(ledger.snapshot.mergerEvents.size, 1)
    finally os.remove.all(root)
    end try
  }

  test("T7 repository mismatch refuses like the creation face; no merger refuses") {
    val root = os.temp.dir(prefix = "mr-t7-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      cancelledMerger(ledger)
      val mismatch = ledger.releaseMerger(Project, "/other-repo", "delegate-nebflow-x", "evt", "reason")
      assert(mismatch.left.toOption.get.contains("repository changed"), s"unexpected: $mismatch")
      val absent = ledger.releaseMerger("other-project", "/nowhere", "delegate-nebflow-x", "evt", "reason")
      assertEquals(absent, Left("No merger is recorded for this repository"))
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(MergerId))
    finally os.remove.all(root)
    end try
  }

  test("T8 idempotent replay: the same event id never touches a replacement created meanwhile") {
    val root = os.temp.dir(prefix = "mr-t8-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      cancelledMerger(ledger)
      val tombstoneAt = DelegateRegistry.find(MergerId).get.cancelRequestedAt.get
      val eventId = MergerRecovery.eventIdFor(Repository, MergerId, tombstoneAt)
      assert(ledger.releaseMerger(Project, Repository, MergerId, eventId, "reason").isRight)

      val replacement = ledger.merger(Project, Repository, "delegate-nebflow-fresh")
      assertEquals(replacement.delegateId, "delegate-nebflow-fresh")

      val replay = ledger.releaseMerger(Project, Repository, MergerId, eventId, "reason").getOrElse(fail("replay refused"))
      assertEquals(replay.replayed, true)
      assertEquals(replay.releasedDelegateId, MergerId, "replay reports the original release facts")
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List("delegate-nebflow-fresh"), "replacement untouched")
      assertEquals(ledger.snapshot.mergerEvents.size, 1, "no duplicate event")

      // A DIFFERENT event id after the release is not a replay — and with a fresh
      // replacement already recorded, the honest refusal is the moved-slot guard.
      val stale = ledger.releaseMerger(Project, Repository, MergerId, "other-event", "reason")
      assert(stale.left.toOption.get.contains("identity changed"), s"unexpected: $stale")
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List("delegate-nebflow-fresh"))
    finally os.remove.all(root)
    end try
  }

  test("T9 concurrent callers with the same event id: exactly one release, everyone succeeds") {
    // Cleanup MUST ride the IO (guarantee), not a try/finally: the finally fires when
    // the IO VALUE is built — before the suite runs it — and would delete the ledger
    // before the race begins.
    val root = os.temp.dir(prefix = "mr-t9-")
    val ledger = new WorkspaceLedger(root / "ledger.json")
    cancelledMerger(ledger)
    val tombstoneAt = DelegateRegistry.find(MergerId).get.cancelRequestedAt.get
    val eventId = MergerRecovery.eventIdFor(Repository, MergerId, tombstoneAt)

    for
      gate <- Deferred[IO, Unit]
      slots <- (1 to 12).toList.traverse(_ => Deferred[IO, Either[String, MergerRelease]])
      fibers <- slots.traverse(slot =>
        (gate.get *> IO.blocking(ledger.releaseMerger(Project, Repository, MergerId, eventId, "reason")))
          .flatMap(slot.complete(_).void)
          .start
      )
      _ <- gate.complete(())
      results <- slots.traverse(_.get)
      _ <- fibers.traverse(_.join)
      _ = assert(results.forall(_.isRight), s"every caller must succeed: $results")
      state = ledger.snapshot
      _ = assertEquals(state.mergers, Nil, "exactly one release landed")
      _ = assertEquals(state.mergerEvents.size, 1, "one event, no duplicates")
      replay <- IO.blocking(new WorkspaceLedger(root / "ledger.json").releaseMerger(Project, Repository, MergerId, eventId, "reason"))
      _ = assert(replay.getOrElse(fail("replay after concurrency refused")).replayed)
      _ <- IO(os.remove.all(root))
    yield ()
  }

  test("T10 crash recovery is verifiable from disk: a fresh process reads the release and replays it") {
    val root = os.temp.dir(prefix = "mr-t10-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      cancelledMerger(ledger)
      val tombstoneAt = DelegateRegistry.find(MergerId).get.cancelRequestedAt.get
      val eventId = MergerRecovery.eventIdFor(Repository, MergerId, tombstoneAt)
      assert(ledger.releaseMerger(Project, Repository, MergerId, eventId, "reason").isRight)

      // "Restart": a brand-new handle over the same file.
      val rebooted = new WorkspaceLedger(root / "ledger.json")
      assertEquals(rebooted.snapshot.mergers, Nil, "the release survived the restart")
      assert(rebooted.snapshot.mergerEvents.contains(eventId), "the audit event is durable")
      val replay = rebooted.releaseMerger(Project, Repository, MergerId, eventId, "reason")
      assertEquals(replay.map(_.replayed), Right(true))
      assertEquals(replay.map(_.releasedDelegateId), Right(MergerId))
    finally os.remove.all(root)
    end try
  }

  test("T11 legacy ledger without the mergerEvents key decodes with an empty map (history not lost)") {
    val root = os.temp.dir(prefix = "mr-t11-")
    try
      val path = root / "legacy.json"
      os.write.over(
        path,
        """{"workspaces":[],"mergers":[{"project":"p","repository":"/r","delegateId":"m"}],"revision":7}"""
      )
      val state = io.circe.parser.decode[WorkspaceLedger.State](os.read(path)).getOrElse(fail("legacy decode failed"))
      assertEquals(state.mergers.map(_.delegateId), List("m"))
      assertEquals(state.mergerEvents, Map.empty[String, String])
      val ledger = new WorkspaceLedger(path)
      assertEquals(ledger.recordedMerger("p", "/r").map(_.delegateId), Right("m"))
    finally os.remove.all(root)
    end try
  }

  // ── 服务面（dataRoot 重定向：默认台账 + 登记簿 + 会话启动租约）─────────────

  test("T12 service release: happy path, deterministic event id, replay on the second call") {
    val id = "delegate-nebflow-svc1"
    val ledger = WorkspaceLedger.default
    cancelledMerger(ledger, id)
    val tombstoneAt = DelegateRegistry.find(id).get.cancelRequestedAt.get
    for
      first <- MergerRecovery.release(Project, Repository, "r", _ => IO.pure(false))
      _ = assertEquals(first.map(_.releasedDelegateId), Right(id))
      _ = assertEquals(first.map(_.eventId), Right(MergerRecovery.eventIdFor(Repository, id, tombstoneAt)))
      _ = assertEquals(ledger.snapshot.mergers, Nil)
      second <- MergerRecovery.release(Project, Repository, "r", _ => IO.pure(false))
      _ = assertEquals(second.map(_.replayed), Right(true))
      _ = assertEquals(ledger.snapshot.mergerEvents.size, 1)
    yield ()
  }

  test("T13 service fail-closed: live session keeps the identity") {
    val id = "delegate-nebflow-svc2"
    val ledger = WorkspaceLedger.default
    cancelledMerger(ledger, id)
    for
      refused <- MergerRecovery.release(Project, Repository, "r", _ => IO.pure(true))
      _ = assert(refused.left.toOption.get.contains("still live"), s"unexpected: $refused")
      _ = assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(id), "state unchanged")
    yield ()
  }

  test("T14 service fail-closed: a merger without a cancel tombstone keeps its own recovery path") {
    val id = "delegate-nebflow-svc3"
    val ledger = WorkspaceLedger.default
    ledger.merger(Project, Repository, id)
    DelegateRegistry.put(record(id)) // no tombstone
    for
      refused <- MergerRecovery.release(Project, Repository, "r", _ => IO.pure(false))
      _ = assert(refused.left.toOption.get.contains("not cancelled"), s"unexpected: $refused")
      _ = assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(id))
    yield ()
  }

  test("T15 service fail-closed: a missing delegate record is an unclear state") {
    val id = "delegate-nebflow-svc4"
    val ledger = WorkspaceLedger.default
    ledger.merger(Project, Repository, id) // no registry record
    for
      refused <- MergerRecovery.release(Project, Repository, "r", _ => IO.pure(false))
      _ = assert(refused.left.toOption.get.contains("unclear"), s"unexpected: $refused")
      _ = assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(id))
    yield ()
  }

  test("T16 the session-start lease excludes a genuinely in-flight initialization (fail-closed)") {
    val id = "delegate-nebflow-svc5"
    val ledger = WorkspaceLedger.default
    cancelledMerger(ledger, id)
    for
      started <- Deferred[IO, Unit]
      holder <- MergerInitialization.exclusive(id)(started.complete(()) *> IO.never[Unit]).start
      refused <- started.get *> MergerRecovery
        .release(Project, Repository, "r", _ => IO.pure(false))
        .guarantee(holder.cancel)
      _ = assert(refused.left.toOption.get.contains("fail-closed"), s"unexpected: $refused")
      _ = assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(id), "state unchanged during the busy window")
      after <- MergerRecovery.release(Project, Repository, "r", _ => IO.pure(false))
      _ = assert(after.isRight, "release succeeds once the lease is free")
    yield ()
  }

  test("T17 releaseForProject refuses unknown projects; the tool face stays root-only and runtime-gated") {
    val plainCtx = nebflow.core.tools.ToolContext(projectRoot = root.toString)
    val rootCtx = nebflow.core.tools.ToolContext(
      projectRoot = root.toString,
      agentDef = Some(AgentDef(name = RootAgentIdentity.Name, description = "")),
      depth = 0
      // sharedResources=None → the runtime gate refuses fail-closed
    )
    val releaseCall = io.circe.JsonObject(
      "action" -> io.circe.Json.fromString("releaseMerger"),
      "project" -> io.circe.Json.fromString(Project)
    )
    for
      unknown <- MergerRecovery.releaseForProject("no-such-project", _ => IO.pure(false))
      _ = assertEquals(unknown, Left("Project not found"))
      nonRoot <- nebflow.core.tools.MergerRequestTool.call(releaseCall, plainCtx)
      _ = assert(nonRoot.left.toOption.get.message.contains("Only the main agent"))
      noRuntime <- nebflow.core.tools.MergerRequestTool.call(releaseCall, rootCtx)
      _ = assert(noRuntime.left.toOption.get.message.contains("fail-closed"))
    yield ()
  }

  // ── 占用判定（定向修复批）：合法终态历史 reservation 不阻断，不明者仍 fail-closed ──

  test("T18 closed-merged seat with its completed history never blocks release (real-ledger regression)") {
    // Driven through the real ledger faces — requestMerge reserves, reportMerge
    // completes the item: `closed-merged` + reservation KEPT (only cancelled/failed
    // clear it) — the exact resting shape every real ledger carries, which the
    // pre-fix gate (`reservation.isDefined`) refused forever.
    val root = os.temp.dir(prefix = "mr-t18-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      cancelledMerger(ledger)
      ledger.register(
        WorkspaceLedger.Workspace("w1", Project, Repository, "/tmp/w1", "delegate/w1", "main", "main", "owner-w1", "kernel")
      )
      assert(
        ledger
          .requestMerge(MergeRequest("req", Repository, MergerId, "auth", List(MergeItem("w1", "a" * 40, "main"))))
          .isRight
      )
      assert(
        ledger
          .reportMerge(
            "req",
            MergerId,
            WorkspaceLedger.MergeReport("e1", 0, "w1", "merged-cleanup-pending", integration = Some("source-ancestor"))
          )((_, _, _) => Right(()))
          .isRight
      )
      assert(
        ledger
          .reportMerge("req", MergerId, WorkspaceLedger.MergeReport("e2", 1, "w1", "completed"))((_, _, _) => Right(()))
          .isRight
      )
      val seat = ledger.snapshot.workspaces.head
      assertEquals(seat.lifecycle, "closed-merged", "the resting shape of a completed integration")
      assertEquals(seat.reservation, Some("req"), "reportMerge keeps the reservation on completion")
      assertEquals(ledger.snapshot.requests.head.status, "merged", "the request is terminal")

      val tombstoneAt = DelegateRegistry.find(MergerId).get.cancelRequestedAt.get
      val eventId = MergerRecovery.eventIdFor(Repository, MergerId, tombstoneAt)
      val released = ledger.releaseMerger(Project, Repository, MergerId, eventId, "reason")
      assert(released.isRight, s"a terminal history must not block the release: $released")
      assertEquals(ledger.snapshot.mergers, Nil, "identity released")
      assertEquals(ledger.snapshot.workspaces.head.lifecycle, "closed-merged", "history preserved verbatim")
      assertEquals(ledger.snapshot.requests.map(_.id), List("req"), "request history preserved")
    finally os.remove.all(root)
    end try
  }

  test("T19 a historical reservation on a failed terminal request does not block") {
    val root = os.temp.dir(prefix = "mr-t19-")
    try
      val ledger = handcraftedLedger(
        root / "ledger.json",
        workspaceRow("open", Some("req-failed")),
        Some(requestRow("req-failed", Repository, "w1", "failed", "failed"))
      )
      tombstonedRegistry()
      val released = ledger.releaseMerger(Project, Repository, MergerId, "evt", "reason")
      assert(released.isRight, s"failed-request residue must not block: $released")
      assertEquals(ledger.snapshot.mergers, Nil)
    finally os.remove.all(root)
    end try
  }

  test("T20 a historical reservation on a cancelled terminal request does not block") {
    val root = os.temp.dir(prefix = "mr-t20-")
    try
      val ledger = handcraftedLedger(
        root / "ledger.json",
        workspaceRow("open", Some("req-cancelled")),
        Some(requestRow("req-cancelled", Repository, "w1", "cancelled", "cancelled"))
      )
      tombstonedRegistry()
      val released = ledger.releaseMerger(Project, Repository, MergerId, "evt", "reason")
      assert(released.isRight, s"cancelled-request residue must not block: $released")
      assertEquals(ledger.snapshot.mergers, Nil)
    finally os.remove.all(root)
    end try
  }

  test("T21 unclear reservations still occupy: orphan on an open seat, cross-repo, item-mismatched") {
    // Terminal status alone is not enough — the reservation must also be attributable
    // to THIS seat (same repository, an item for this workspace) or it refuses closed.
    val shapes = List(
      ("orphan on an open seat", workspaceRow("open", Some("ghost-req")), None),
      (
        "cross-repo reservation",
        workspaceRow("open", Some("req-other")),
        Some(requestRow("req-other", "/other-repo", "w1", "merged", "completed"))
      ),
      (
        "item-mismatched reservation",
        workspaceRow("open", Some("req-stray")),
        Some(requestRow("req-stray", Repository, "w2", "merged", "completed"))
      )
    )
    shapes.foreach { case (label, workspace, requests) =>
      val root = os.temp.dir(prefix = "mr-t21-")
      try
        val ledger = handcraftedLedger(root / "ledger.json", workspace, requests)
        tombstonedRegistry()
        val refused = ledger.releaseMerger(Project, Repository, MergerId, "evt", "reason")
        assert(refused.left.toOption.get.contains("occupied"), s"$label must refuse: $refused")
        assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(MergerId), s"$label: state unchanged")
      finally os.remove.all(root)
      end try
    }
  }

  test("T22 a genuinely merger-occupied lifecycle still occupies (merging seat)") {
    val root = os.temp.dir(prefix = "mr-t22-")
    try
      val ledger = handcraftedLedger(root / "ledger.json", workspaceRow("merging", None))
      tombstonedRegistry()
      val refused = ledger.releaseMerger(Project, Repository, MergerId, "evt", "reason")
      assert(refused.left.toOption.get.contains("occupied"), s"unexpected: $refused")
      assertEquals(ledger.snapshot.mergers.map(_.delegateId), List(MergerId), "state unchanged")
    finally os.remove.all(root)
    end try
  }

  test("T23 reservation occupancy truth table (the release gate's predicate)") {
    import WorkspaceLedger.reservationOccupies
    val seat = WorkspaceLedger
      .Workspace("w1", Project, Repository, "/tmp/w1", "delegate/w1", "main", "main", "o", "kernel")
      .copy(reservation = Some("req"))
    val req = (id: String, repo: String, status: String, ws: String) =>
      MergeRequest(id, repo, MergerId, "auth", List(MergeItem(ws, "a" * 40, "main")), status = status)
    assert(!reservationOccupies(seat.copy(reservation = None), Nil), "no reservation, no occupation")
    assert(
      !reservationOccupies(seat, List(req("req", Repository, "merged", "w1"))),
      "attributable terminal history does not occupy"
    )
    assert(
      !reservationOccupies(seat, List(req("req", Repository, "completed", "w1"))),
      "legacy completed status is terminal too"
    )
    assert(
      !reservationOccupies(seat, List(req("req", Repository, "failed", "w1"))),
      "failed terminal history does not occupy"
    )
    assert(
      reservationOccupies(seat, List(req("req", Repository, "submitted", "w1"))),
      "a request still in flight occupies (defense in depth; the request gate refuses first)"
    )
    assert(reservationOccupies(seat, Nil), "an orphan reservation occupies (unclear, fail-closed)")
    assert(
      reservationOccupies(seat, List(req("req", "/other-repo", "merged", "w1"))),
      "a foreign-repository request occupies"
    )
    assert(
      reservationOccupies(seat, List(req("req", Repository, "merged", "w2"))),
      "a request without this seat's item occupies"
    )
  }

end MergerRecoverySpec
