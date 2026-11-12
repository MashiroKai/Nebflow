package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.AgentDef
import nebflow.core.delegate.WorkspaceLedger.*
import nebflow.core.tools.{ToolContext, WorkspaceReconcileTool}
import io.circe.JsonObject
import io.circe.syntax.*

import scala.collection.mutable.ListBuffer

/** Isolated registry sink: records receipts in memory, can inject write failures. */
private class StubRegistry extends ((String, String, String) => Either[String, Unit]):
  val calls: ListBuffer[(String, String, String)] = ListBuffer.empty
  @volatile var failWrites: Boolean = false

  def apply(owner: String, operationId: String, receipt: String): Either[String, Unit] =
    calls += ((owner, operationId, receipt))
    if failWrites then Left("registry sync injected failure")
    else Right(())

class WorkspaceReconcileSpec extends CatsEffectSuite:
  private def fixture(
    register: os.Path => List[Workspace] = _ => Nil
  )(
    f: (os.Path, os.Path, WorkspaceLedger, StubRegistry, WorkspaceReconcile) => IO[Unit]
  ): IO[Unit] = IO {
    val root = os.temp.dir(prefix = "workspace-reconcile-")
    val repo = root / "repo"
    val tree = root / "seat"
    os.makeDir.all(repo)
    def git(cwd: os.Path, args: String*) =
      os.proc(Seq("git", "-C", cwd.toString) ++ args).call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git(repo, "init", "-b", "main")
    git(repo, "config", "user.name", "test")
    git(repo, "config", "user.email", "test@example.invalid")
    git(repo, "commit", "--allow-empty", "-m", "base")
    git(repo, "worktree", "add", "-b", "actual-branch", tree.toString, "main")
    os.write(tree / "result", "done")
    os.proc("git", "-C", tree.toString, "add", "result").call(stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", tree.toString, "commit", "-m", "result").call(stdout = os.Pipe, stderr = os.Pipe)
    val ledger = new WorkspaceLedger(root / "ledger.json")
    register(root).foreach(ledger.register)
    val registry = StubRegistry()
    val idle = new WorkspaceOccupancy(_ => IO.pure(DelegateInflightGate.Reading(Nil, readable = true)), _ => IO.pure(Nil))
    val service = new WorkspaceReconcile(ledger, idle, registry = registry)
    (root, tree, ledger, registry, service)
  }.flatMap { case (root, tree, ledger, registry, service) =>
    f(root, tree, ledger, registry, service).guarantee(IO(os.remove.all(root)))
  }

  private def standardWorkspace(repo: os.Path, tree: os.Path): Workspace =
    Workspace("ws", "project", WorkspaceLedger.repositoryKey(repo), tree.toNIO.toRealPath().toString,
      "stale-recorded-branch", "main", "base", "delegate-owner", "kernel")

  private def request(tree: os.Path, ledger: WorkspaceLedger, id: String = "op") =
    val w = ledger.snapshot.workspaces.head
    val head = os.proc("git", "-C", tree.toString, "rev-parse", "HEAD").call(stdout = os.Pipe).out.text().trim
    CompletionConfirmation(id, w.id, w.owner, w.path, head, w.revision, "acceptance://test/123", "confirmed by root")

  test("explicit confirmation converges ledger and registry receipt, maps actual branch, replays idempotently") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (_, tree, ledger, registry, service) =>
      val op = request(tree, ledger)
      for
        first <- service.confirm(op)
        replay <- service.confirm(op)
      yield
        val done = first.getOrElse(fail(first.toString))
        assertEquals(replay, first)
        val w = ledger.snapshot.workspaces.head
        assertEquals(w.branch, "actual-branch") // Git-verified branch replaces the stale recorded one
        assertEquals(w.activity, "idle")
        assertEquals(w.revision, op.expectedRevision + 1)
        val confirmation = ledger.snapshot.completionConfirmations(op.operationId)
        assertEquals(confirmation.stage, "completed")
        // Registry receipt is deterministic and replay adds no divergent second write.
        assertEquals(registry.calls.map(_._3).distinct.size, 1)
        assert(registry.calls.forall(_._1 == w.owner))
        assert(done.actualBranch.contains("actual-branch"))
    }
  }

  test("registered subdirectory (not the worktree root) is refused") {
    fixture(register = root =>
      List(standardWorkspace(root / "repo", root / "seat"), {
        val nested = root / "seat" / "nested"
        os.makeDir.all(nested)
        Workspace("ws-nested", "project", WorkspaceLedger.repositoryKey(root / "repo"),
          nested.toNIO.toRealPath().toString, "stale-recorded-branch", "main", "base", "delegate-owner", "kernel")
      })
    ) { (_, tree, ledger, _, service) =>
      val w = ledger.snapshot.workspaces.find(_.id == "ws-nested").getOrElse(fail("nested workspace missing"))
      val head = os.proc("git", "-C", tree.toString, "rev-parse", "HEAD").call(stdout = os.Pipe).out.text().trim
      val op = CompletionConfirmation("nested-op", w.id, w.owner, w.path, head, w.revision, "e", "root")
      for result <- service.confirm(op) yield
        assert(result.left.exists(_.contains("not the Git worktree root")), result.toString)
        assert(!ledger.snapshot.completionConfirmations.contains("nested-op"))
    }
  }

  test("owner/path/HEAD mismatches and dirty state are refused without any durable write") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (root, tree, ledger, registry, service) =>
      val before = ledger.snapshot
      val head = os.proc("git", "-C", tree.toString, "rev-parse", "HEAD").call(stdout = os.Pipe).out.text().trim
      val op = request(tree, ledger)
      val wrongOwner = op.copy(owner = "someone-else")
      val wrongPath = op.copy(expectedPath = (root / "repo").toNIO.toRealPath().toString)
      val wrongHead = op.copy(expectedHead = "0" * 40)
      for
        results <- List(service.confirm(wrongOwner), service.confirm(wrongPath), service.confirm(wrongHead)).sequence
        _ <- IO(os.write(tree / "untracked", "dirty")) // dirty AFTER identity failures: also refused
        dirty <- service.confirm(op)
      yield
        assert(results.forall(_.isLeft), results.toString)
        assert(dirty.isLeft, dirty.toString)
        assertEquals(ledger.snapshot, before)
        assert(registry.calls.isEmpty)
    }
  }

  test("tool refuses non-root callers before touching any store") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (_, _, _, registry, _) =>
      val input = JsonObject(
        "operationId" -> "op".asJson, "workspaceId" -> "ws".asJson, "owner" -> "delegate-owner".asJson,
        "expectedPath" -> "/tmp".asJson, "expectedHead" -> ("a" * 40).asJson, "expectedRevision" -> 0L.asJson,
        "rootConfirmation" -> "root".asJson, "acceptanceEvidence" -> "e".asJson
      )
      val refused = ToolContext(projectRoot = "/tmp", agentDef = Some(AgentDef(name = "General", description = "")))
      val noDef = ToolContext(projectRoot = "/tmp", agentDef = None)
      for
        r1 <- WorkspaceReconcileTool.call(input, refused)
        r2 <- WorkspaceReconcileTool.call(input, noDef)
      yield
        assert(r1.left.exists(_.message.contains("limited to the main agent")), r1.toString)
        assert(r2.left.exists(_.message.contains("limited to the main agent")), r2.toString)
        assert(registry.calls.isEmpty)
    }
  }

  test("live occupancy and unknown occupancy fail closed at both gates") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (_, tree, ledger, registry, _) =>
      val busyJob = nebflow.core.tools.BgTaskRegistry.ActiveTask(
        jobId = "job-1", sessionId = "session-x", description = "busy", startedAtMs = 0L, kind = "local")
      val busy = new WorkspaceOccupancy(_ => IO.pure(DelegateInflightGate.Reading(Nil, readable = true)), _ => IO.pure(List(busyJob)))
      val unknown = new WorkspaceOccupancy(_ => IO.raiseError(new RuntimeException("gate unreadable")), _ => IO.pure(Nil))
      val ledgerBefore = ledger.snapshot
      val op = request(tree, ledger)
      for
        busyResult <- new WorkspaceReconcile(ledger, busy, registry = StubRegistry()).confirm(op)
        unknownResult <- new WorkspaceReconcile(ledger, unknown, registry = StubRegistry()).confirm(op)
      yield
        assert(busyResult.left.exists(_.contains("live/busy")), busyResult.toString)
        assert(unknownResult.left.exists(msg => msg.contains("unreadable") || msg.contains("Occupancy unknown")),
          unknownResult.toString) // fail-closed either way
        assertEquals(ledger.snapshot, ledgerBefore)
        assert(registry.calls.isEmpty)
    }
  }

  test("concurrent confirmations on one revision admit exactly one winner without a false registry finish") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (_, tree, ledger, registry, service) =>
      val a = request(tree, ledger, "race-a")
      val b = request(tree, ledger, "race-b")
      for outcomes <- List(a, b).parTraverse(service.confirm) yield
        assertEquals(outcomes.count(_.isRight), 1)
        val snapshot = ledger.snapshot
        assertEquals(snapshot.completionConfirmations.size, 1)
        assertEquals(snapshot.workspaces.head.revision, a.expectedRevision + 1)
        assertEquals(registry.calls.size, 1) // loser never reaches the registry
        assert(outcomes.find(_.isRight).exists(_.getOrElse(fail("right outcome missing")).operationId == registry.calls.head._2))
    }
  }

  test("same operation id with different acceptance evidence is refused") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (_, tree, ledger, _, service) =>
      val op = request(tree, ledger)
      for
        first <- service.confirm(op)
        conflict <- service.confirm(op.copy(acceptanceEvidence = "different"))
      yield
        assert(first.isRight)
        assert(conflict.left.exists(_.contains("conflicts")), conflict.toString)
    }
  }

  test("late replay against a refusing registry keeps ledger completion and reports not-converged") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (_, tree, ledger, registry, service) =>
      val op = request(tree, ledger)
      for
        first <- service.confirm(op)
        replay <- { registry.failWrites = true; service.confirm(op) } // stands in for a newer-claim refusal
      yield
        assert(first.isRight)
        assert(replay.isLeft, replay.toString) // honest divergence, never a silent overwrite
        assertEquals(ledger.snapshot.completionConfirmations(op.operationId).stage, "completed")
    }
  }

  test("completion write decision never clobbers a newer registry claim (deterministic)") {
    val base = DelegateRegistry.Record(id = "d1", executor = "kernel", cwd = "/x", project = None, taskId = None,
      parentSessionId = "p", title = "t", createdAt = 0L, workReport = Some("work text"))
    val receipt = s"workspace-reconcile op1 owner=o workspace=w path=/p head=${"a" * 40} branch=b evidence=e confirmedAt=1"
    // Unreported slot: allowed; workReport preserved; suggestion cleared.
    val allowed = DelegateRegistry.decideCompletionWrite(base, receipt)
    val written = allowed.getOrElse(fail(allowed.toString))
    assertEquals(written.reportStatus, Some("finish"))
    assertEquals(written.reportDetail, Some(receipt))
    assertEquals(written.workReport, Some("work text"))
    assertEquals(written.reportSuggestion, None)
    // Idempotent against itself.
    assertEquals(DelegateRegistry.decideCompletionWrite(written, receipt), Right(written))
    // Any newer claim wins: refused, original record returned untouched.
    val newerFinish = written.copy(reportStatus = Some("finish"), reportDetail = Some("newer round finish"))
    val standby = written.copy(reportStatus = Some("standby"), reportDetail = Some("waiting"), reportSuggestion = Some("s"))
    val blocked = written.copy(reportStatus = Some("blocked"), reportDetail = Some("why"))
    for same <- List(newerFinish, standby, blocked) do
      val verdict = DelegateRegistry.decideCompletionWrite(same, receipt)
      assert(verdict.isLeft, verdict.toString)
      assert(verdict.left.exists(_.contains("newer claim")), verdict.toString)
  }

  test("cancel tombstone refuses a completion receipt even with an unreported slot (deterministic)") {
    val base = DelegateRegistry.Record(id = "d2", executor = "kernel", cwd = "/x", project = None, taskId = None,
      parentSessionId = "p", title = "t", createdAt = 0L)
    val receipt = s"workspace-reconcile op2 owner=o workspace=w path=/p head=${"b" * 40} branch=b evidence=e confirmedAt=1"
    // The exact reported gap: cancelRequestedAt set, reportStatus=None — must NOT receive finish.
    val cancelledUnreported = base.copy(cancelRequestedAt = Some(123L), cancelRequestedBy = Some("root"))
    val verdict = DelegateRegistry.decideCompletionWrite(cancelledUnreported, receipt)
    assert(verdict.isLeft, verdict.toString)
    assert(verdict.left.exists(_.contains("cancel tombstone")), verdict.toString)
    // Cancelled + newer-claim slots refuse equally.
    for cancelled <- List(
        cancelledUnreported.copy(reportStatus = Some("standby"), reportDetail = Some("waiting")),
        cancelledUnreported.copy(reportStatus = Some("finish"), reportDetail = Some("older finish"))) do
      assert(DelegateRegistry.decideCompletionWrite(cancelled, receipt).isLeft)
    // An already-recorded identical receipt stays idempotent (zero write, no new finish invented).
    val alreadyOurs = cancelledUnreported.copy(reportStatus = Some("finish"), reportDetail = Some(receipt))
    assertEquals(DelegateRegistry.decideCompletionWrite(alreadyOurs, receipt), Right(alreadyOurs))
  }

  test("root-explicit stale-running recovery converges the seat in one locked transaction") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat").copy(activity = "running"))) { (_, tree, ledger, registry, service) =>
      val op = request(tree, ledger).copy(recoverRunning = true)
      for result <- service.confirm(op) yield
        val done = result.getOrElse(fail(result.toString))
        assertEquals(done.operationId, op.operationId)
        val w = ledger.snapshot.workspaces.head
        assertEquals(w.activity, "idle") // running → idle inside the same transaction as the completion
        assertEquals(w.branch, "actual-branch")
        assertEquals(w.revision, op.expectedRevision + 1)
        assertEquals(ledger.snapshot.completionConfirmations(op.operationId).stage, "completed")
        assertEquals(registry.calls.size, 1)
        assert(registry.calls.head._3.contains(s"workspace-reconcile ${op.operationId}"))
    }
  }

  test("stale-running recovery is fail-closed on live occupancy, dirty tree, and idle seats") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat").copy(activity = "running"))) { (root, tree, ledger, registry, _) =>
      val busyJob = nebflow.core.tools.BgTaskRegistry.ActiveTask(
        jobId = "job-2", sessionId = "live-1", description = "busy", startedAtMs = 0L, kind = "local")
      val busy = new WorkspaceOccupancy(_ => IO.pure(DelegateInflightGate.Reading(Nil, readable = true)), _ => IO.pure(List(busyJob)))
      val idle = new WorkspaceOccupancy(_ => IO.pure(DelegateInflightGate.Reading(Nil, readable = true)), _ => IO.pure(Nil))
      val service = new WorkspaceReconcile(ledger, busy, registry = StubRegistry())
      val clean = new WorkspaceReconcile(ledger, idle, registry = StubRegistry())
      val ledgerBefore = ledger.snapshot
      val op = request(tree, ledger).copy(recoverRunning = true)
      for
        liveRefused <- service.confirm(op)
        dirtyRefused <- { os.write(tree / "untracked", "dirty"); clean.confirm(op) }
      yield
        assert(liveRefused.left.exists(_.contains("live/busy")), liveRefused.toString)
        assert(dirtyRefused.isLeft, dirtyRefused.toString)
        assertEquals(ledger.snapshot, ledgerBefore)
        assert(registry.calls.isEmpty)
    }
  }

  test("recoverRunning on an idle seat and plain confirm on a running seat both refuse (gates stay directional)") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat").copy(activity = "running"))) { (_, tree, ledger, registry, service) =>
      val stub = StubRegistry()
      val plain = new WorkspaceReconcile(ledger,
        new WorkspaceOccupancy(_ => IO.pure(DelegateInflightGate.Reading(Nil, readable = true)), _ => IO.pure(Nil)),
        registry = stub)
      val op = request(tree, ledger)
      for
        runningRefused <- plain.confirm(op) // plain path requires idle: running seat refuses
        recovery <- plain.confirm(request(tree, ledger, "rec-1").copy(recoverRunning = true))
        moved = ledger.snapshot.workspaces.head
        lateRunningGate <- plain.confirm(request(tree, ledger, "rec-2").copy(recoverRunning = true)) // seat now idle
        staleGate <- plain.confirm(request(tree, ledger, "rec-3").copy(recoverRunning = true, expectedRevision = moved.revision - 1))
      yield
        assert(runningRefused.left.exists(_.contains("open, idle and unreserved")), runningRefused.toString)
        assert(recovery.isRight, recovery.toString)
        assert(lateRunningGate.left.exists(_.contains("requires an open, running, unreserved")), lateRunningGate.toString)
        assert(staleGate.left.exists(_.contains("Stale workspace revision")), staleGate.toString)
        assertEquals(stub.calls.count(_._2 == "rec-1"), 1)
        assertEquals(stub.calls.count(call => call._2 == "rec-2" || call._2 == "rec-3"), 0)
        assertEquals(ledger.snapshot.workspaces.head.activity, "idle")
    }
  }

  test("concurrent stale-running recoveries on one revision admit exactly one winner") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat").copy(activity = "running"))) { (_, tree, ledger, registry, service) =>
      val a = request(tree, ledger, "rec-a").copy(recoverRunning = true)
      val b = request(tree, ledger, "rec-b").copy(recoverRunning = true)
      for outcomes <- List(a, b).parTraverse(service.confirm) yield
        assertEquals(outcomes.count(_.isRight), 1)
        assertEquals(ledger.snapshot.completionConfirmations.size, 1)
        assertEquals(ledger.snapshot.workspaces.head.activity, "idle")
        assertEquals(registry.calls.size, 1)
    }
  }

  test("registry sync failure after the ledger commit reports not-converged and replay heals without refinish") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (_, tree, ledger, registry, service) =>
      val op = request(tree, ledger)
      registry.failWrites = true
      for
        failed <- service.confirm(op)
        healed <- { registry.failWrites = false; service.confirm(op) }
      yield
        assert(failed.isLeft, failed.toString) // honest: ledger done, registry not synced
        val afterFailure = ledger.snapshot
        assertEquals(afterFailure.completionConfirmations(op.operationId).stage, "completed")
        assertEquals(afterFailure.workspaces.head.branch, "actual-branch") // ledger truth stands
        val done = healed.getOrElse(fail(healed.toString))
        assertEquals(done.operationId, op.operationId)
        // Replay re-issues the SAME receipt (deterministic), never a second divergent finish.
        assertEquals(registry.calls.map(_._3).distinct.size, 1)
        assertEquals(registry.calls.count(_._2 == op.operationId) >= 2, true)
    }
  }

  test("replay of a completed confirmation resyncs a registry that lost its finish slot") {
    fixture(register = root => List(standardWorkspace(root / "repo", root / "seat"))) { (_, tree, ledger, registry, service) =>
      val op = request(tree, ledger)
      for
        first <- service.confirm(op)
        _ <- IO { registry.calls.clear() } // simulate crash after ledger commit, before registry sync
        healed <- service.confirm(op)
      yield
        assert(first.isRight)
        assertEquals(healed, first)
        assertEquals(registry.calls.count(_._2 == op.operationId), 1)
        assert(registry.calls.head._3.contains(s"workspace-reconcile ${op.operationId}"))
    }
  }

  test("durable ledger write failure keeps the original evidence untouched") {
    IO {
      val root = os.temp.dir(prefix = "workspace-reconcile-ro-")
      val repo = root / "repo"
      val tree = root / "seat"
      os.makeDir.all(repo)
      def git(cwd: os.Path, args: String*) =
        os.proc(Seq("git", "-C", cwd.toString) ++ args).call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim
      git(repo, "init", "-b", "main")
      git(repo, "config", "user.name", "test")
      git(repo, "config", "user.email", "test@example.invalid")
      git(repo, "commit", "--allow-empty", "-m", "base")
      git(repo, "worktree", "add", "-b", "actual-branch", tree.toString, "main")
      os.write(tree / "result", "done")
      os.proc("git", "-C", tree.toString, "add", "result").call(stdout = os.Pipe, stderr = os.Pipe)
      os.proc("git", "-C", tree.toString, "commit", "-m", "result").call(stdout = os.Pipe, stderr = os.Pipe)
      val roDir = root / "ro"
      os.makeDir.all(roDir)
      val ledger = new WorkspaceLedger(roDir / "ledger.json")
      ledger.register(standardWorkspace(repo, tree))
      val before = os.read(roDir / "ledger.json")
      val w = ledger.snapshot.workspaces.head
      val head = os.proc("git", "-C", tree.toString, "rev-parse", "HEAD").call(stdout = os.Pipe).out.text().trim
      val op = CompletionConfirmation("ro-op", w.id, w.owner, w.path, head, w.revision, "e", "root")
      // Make the ledger directory unwritable: the durable write must fail without touching the file.
      java.nio.file.Files.setPosixFilePermissions(roDir.toNIO, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"))
      (root, roDir, ledger, op, before)
    }.flatMap { case (root, roDir, ledger, op, before) =>
      val registry = StubRegistry()
      val idle = new WorkspaceOccupancy(_ => IO.pure(DelegateInflightGate.Reading(Nil, readable = true)), _ => IO.pure(Nil))
      val service = new WorkspaceReconcile(ledger, idle, registry = registry)
      service
        .confirm(op)
        .guarantee(IO(java.nio.file.Files.setPosixFilePermissions(roDir.toNIO,
          java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))))
        .flatMap { result =>
          IO(assert(result.isLeft, result.toString)) >> IO {
            assertEquals(os.read(roDir / "ledger.json"), before) // original evidence preserved byte-for-byte
            assert(registry.calls.isEmpty)
          }
        }
        .guarantee(IO(os.remove.all(root)))
    }
  }
end WorkspaceReconcileSpec
