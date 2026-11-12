package nebflow.core.delegate

import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.delegate.WorkspaceLedger.{CompletionConfirmation, MergeItem, MergeRequest}
import nebflow.shared.PathUtil

/**
 * 交叉回归（整合批 2026-11-12）：e821 完成对账面 × 963 取消 merger 释放面在同一
 * WorkspaceLedger 上的共存锚。两条批各加一个 State 字段（completionConfirmations /
 * mergerEvents），本 spec 钉住三件事：
 *  1. 完成对账面的严格 reservation 门**不**继承释放面的宽容——可归属终态历史
 *     reservation 在 963 的 reservationOccupies 里不占用，但在 reconcileCompletion /
 *     reconcileRunningCompletion 里仍然拒绝（完成只认 open+idle/running+unreserved）。
 *  2. 两个新字段经真实面写入后互不干扰：完成确认不阻断、也不被 merger 释放清除；
 *     释放事件不阻断、也不被完成写覆盖。
 *  3. 解码缝隙（唯一真冲突区）：同文件携带 completionConfirmations + mergerEvents
 *     两个键时 State 解码两侧都保留。
 */
class ReleaseCompletionCrossSpec extends CatsEffectSuite:

  private val previousRoot = PathUtil.dataRoot
  private var root = os.pwd

  override def beforeEach(context: munit.BeforeEach): Unit =
    root = os.temp.dir(prefix = "release-completion-cross-")
    PathUtil.setDataRoot(root)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(previousRoot)
    os.remove.all(root)

  private val Project = "proj"
  private val Repository = "/repo"
  private val MergerId = "delegate-nebflow-cross"
  private val Head = "a" * 40

  private def record(id: String) = DelegateRegistry.Record(
    id = id,
    executor = "nebflow",
    cwd = root.toString,
    project = Some(Project),
    taskId = None,
    parentSessionId = "root-1",
    title = "cross fixture",
    createdAt = System.currentTimeMillis() - 60_000L,
    mode = Some("merger")
  )

  private def cancelledMerger(ledger: WorkspaceLedger): Unit =
    ledger.merger(Project, Repository, MergerId)
    DelegateRegistry.put(record(MergerId))
    DelegateRegistry.markCancelRequested(MergerId, "the user panel").getOrElse(fail("tombstone write refused"))

  /** MergeRequest row with every derived-codec field (defaults are not honored on decode). */
  private def terminalRequestRow(requestId: String, workspaceId: String, status: String): String =
    s"""{"id":"$requestId","repository":"$Repository","mergerId":"$MergerId","authorization":"auth",""" +
      s""""items":[{"workspaceId":"$workspaceId","sourceCommit":"${"a" * 40}","targetBranch":"main",""" +
      """"phase":"completed","mergeCommit":null,"verification":null,"integration":null,"error":null}],""" +
      s""""status":"$status","revision":4,"cancelRequested":false,"events":{}}"""

  /** Workspace row (every derived-codec field) with an explicit activity. */
  private def workspaceRow(id: String, lifecycle: String, activity: String, reservation: Option[String]): String =
    val quoted = reservation.fold("null")(r => "\"" + r + "\"")
    s"""{"id":"$id","project":"$Project","repository":"$Repository","path":"/tmp/$id",""" +
      s""""branch":"delegate/$id","baseBranch":"main","baseCommit":"b","owner":"owner-$id","ownerMode":"kernel",""" +
      s""""lifecycle":"$lifecycle","activity":"$activity","reservation":$quoted,"revision":3}"""

  private def handcraftedLedger(
    path: os.Path,
    workspaces: List[String],
    requests: List[String],
    extra: String = ""
  ): WorkspaceLedger =
    os.makeDir.all(path / os.up)
    os.write(
      path,
      s"""{"workspaces":[${workspaces.mkString(",")}],"requests":[${requests.mkString(",")}],""" +
        s""""mergers":[{"project":"$Project","repository":"$Repository","delegateId":"$MergerId"}],$extra"revision":9}"""
    )
    val decoded = io.circe.parser.decode[WorkspaceLedger.State](os.read(path))
    assert(decoded.isRight, s"fixture decode failed: ${decoded.left.map(_.getMessage)}")
    new WorkspaceLedger(path)
  end handcraftedLedger

  private def confirmation(workspaceId: String) = CompletionConfirmation(
    operationId = s"op-$workspaceId",
    workspaceId = workspaceId,
    owner = s"owner-$workspaceId",
    expectedPath = s"/tmp/$workspaceId",
    expectedHead = "a" * 40,
    expectedRevision = 0L,
    acceptanceEvidence = "evidence",
    explicitRootConfirmation = "root confirmed"
  )

  test("X1 completion reconcile keeps its STRICT reservation gate: attributable terminal history still refuses") {
    // The release face (963) treats an attributable terminal-history reservation as
    // non-occupying; the completion faces (e821) must NOT inherit that leniency — a
    // reservation is a reservation until the worker clears it.
    val root = os.temp.dir(prefix = "x1-")
    try
      val ledger = handcraftedLedger(
        root / "ledger.json",
        List(workspaceRow("w1", "open", "idle", Some("req-t"))),
        List(terminalRequestRow("req-t", "w1", "merged"))
      )
      val refused = ledger.reconcileCompletion(confirmation("w1").copy(expectedRevision = 3L), _ => Right("main"))
      assert(refused.left.toOption.get.contains("unreserved"), s"unexpected: $refused")
      assertEquals(ledger.snapshot.completionConfirmations, Map.empty, "no durable write on refusal")

      // Same strictness on the running-recovery face: a terminal-history reservation on
      // a stale-running seat still refuses.
      val running = handcraftedLedger(
        root / "running.json",
        List(workspaceRow("w2", "open", "running", Some("req-t2"))),
        List(terminalRequestRow("req-t2", "w2", "cancelled"))
      )
      val refusedRunning =
        running.reconcileRunningCompletion(confirmation("w2").copy(expectedRevision = 3L), _ => Right("main"))
      assert(refusedRunning.left.toOption.get.contains("unreserved"), s"unexpected: $refusedRunning")
    finally os.remove.all(root)
    end try
  }

  test("X2 the two new State fields coexist through the real faces: neither blocks nor clears the other") {
    val root = os.temp.dir(prefix = "x2-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      // Completion face writes its confirmation first.
      ledger.register(
        WorkspaceLedger.Workspace(
          "w1",
          Project,
          Repository,
          "/tmp/w1",
          "delegate/w1",
          "main",
          "base",
          "owner-w1",
          "kernel"
        )
      )
      val confirmed = ledger
        .reconcileCompletion(confirmation("w1"), _ => Right("delegate/w1"))
        .getOrElse(fail("confirmation refused"))
      assertEquals(confirmed.stage, "completed")
      assertEquals(ledger.snapshot.completionConfirmations.size, 1)

      // Release face then runs on a cancelled merger with terminal-history residue on w2.
      cancelledMerger(ledger)
      ledger.register(
        WorkspaceLedger.Workspace(
          "w2",
          Project,
          Repository,
          "/tmp/w2",
          "delegate/w2",
          "main",
          "base",
          "owner-w2",
          "kernel"
        )
      )
      assert(
        ledger
          .requestMerge(MergeRequest("req2", Repository, MergerId, "auth", List(MergeItem("w2", "a" * 40, "main"))))
          .isRight
      )
      assert(ledger.cancelRequest("req2").isRight)
      assert(ledger.releaseUnfinished("req2", MergerId, "cancelled").isRight)

      val tombstoneAt = DelegateRegistry.find(MergerId).get.cancelRequestedAt.get
      val eventId = MergerRecovery.eventIdFor(Repository, MergerId, tombstoneAt)
      assert(
        ledger.releaseMerger(Project, Repository, MergerId, eventId, "reason").isRight,
        "confirmations must not block the release"
      )

      val after = ledger.snapshot
      assertEquals(after.completionConfirmations.size, 1, "completion history preserved verbatim by the release")
      assertEquals(after.mergerEvents.size, 1, "the release event is recorded")

      // A later completion write after the release preserves the release events.
      ledger.register(
        WorkspaceLedger.Workspace(
          "w3",
          Project,
          Repository,
          "/tmp/w3",
          "delegate/w3",
          "main",
          "base",
          "owner-w3",
          "kernel"
        )
      )
      assert(ledger.reconcileCompletion(confirmation("w3"), _ => Right("delegate/w3")).isRight)
      assertEquals(ledger.snapshot.mergerEvents.size, 1, "completion writes never touch mergerEvents")
      assertEquals(ledger.snapshot.completionConfirmations.size, 2)

      // Fresh handle ("restart"): both maps decode from the same file.
      val rebooted = new WorkspaceLedger(root / "ledger.json")
      assertEquals(rebooted.snapshot.completionConfirmations.size, 2)
      assertEquals(rebooted.snapshot.mergerEvents.size, 1)
    finally os.remove.all(root)
    end try
  }

  test("X3 decoder seam: a file carrying BOTH new keys decodes both maps (the only true conflict region)") {
    val root = os.temp.dir(prefix = "x3-")
    try
      val path = root / "both.json"
      // mergerEvents values are JSON STRINGS (releaseMerger stores release.asJson.noSpaces).
      val release = WorkspaceLedger.MergerRelease(
        eventId = s"release-merger:$Repository:$MergerId:123",
        project = Project,
        repository = Repository,
        releasedDelegateId = MergerId,
        replayed = false,
        tombstoneAt = Some(123L),
        cancelledBy = Some("root"),
        reason = "r",
        releasedAt = 456L
      )
      val eventsJson = io.circe.Json
        .obj(s"release-merger:$Repository:$MergerId:123" -> io.circe.Json.fromString(release.asJson.noSpaces))
        .toString
      os.write.over(
        path,
        s"""{"workspaces":[],"mergers":[],"requests":[],"revision":1,""" +
          s""""mergerEvents":$eventsJson,""" +
          """"completionConfirmations":{"op-1":{"operationId":"op-1","workspaceId":"w1","owner":"owner-w1",""" +
          s""""expectedPath":"/tmp/w1","expectedHead":"$Head","expectedRevision":0,""" +
          """"acceptanceEvidence":"e","explicitRootConfirmation":"c","actualBranch":null,"recordedAt":0,""" +
          """"stage":"completed","recoverRunning":false}}}"""
      )
      val state = io.circe.parser
        .decode[WorkspaceLedger.State](os.read(path))
        .getOrElse(
          fail(s"decode failed: ${io.circe.parser.decode[WorkspaceLedger.State](os.read(path)).left.map(_.getMessage)}")
        )
      assertEquals(state.mergerEvents.size, 1, "mergerEvents survives the decode")
      assertEquals(state.completionConfirmations.size, 1, "completionConfirmations survives the decode")
      assertEquals(state.completionConfirmations("op-1").workspaceId, "w1")
    finally os.remove.all(root)
    end try
  }

end ReleaseCompletionCrossSpec
