package nebflow.core.delegate

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.core.delegate.WorkspaceLedger.*

class WorkspaceReconcileSpec extends CatsEffectSuite:
  private def fixture(f: (os.Path, os.Path, WorkspaceLedger, WorkspaceReconcile) => IO[Unit]): IO[Unit] = IO {
    val root = os.temp.dir(prefix = "workspace-reconcile-")
    val repo = root / "repo"
    val tree = root / "seat"
    os.makeDir.all(repo)
    def git(args: String*) = os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main"); git("config", "user.name", "test"); git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    git("worktree", "add", "-b", "actual-branch", tree.toString, "main")
    os.write(tree / "result", "done")
    os.proc("git", "-C", tree.toString, "add", "result").call()
    os.proc("git", "-C", tree.toString, "commit", "-m", "result").call(stdout = os.Pipe, stderr = os.Pipe)
    val ledger = new WorkspaceLedger(root / "ledger.json")
    val workspace = Workspace("ws", "project", WorkspaceLedger.repositoryKey(repo), tree.toNIO.toRealPath().toString,
      "stale-recorded-branch", "main", git("rev-parse", "main"), "owner", "kernel")
    ledger.register(workspace)
    val idle = new WorkspaceOccupancy(_ => IO.pure(DelegateInflightGate.Reading(Nil, readable = true)), _ => IO.pure(Nil))
    (root, tree, ledger, new WorkspaceReconcile(ledger, idle))
  }.flatMap { case (root, tree, ledger, service) =>
    f(root, tree, ledger, service).guarantee(IO(os.remove.all(root)))
  }

  private def request(tree: os.Path, ledger: WorkspaceLedger, id: String = "op") =
    val w = ledger.snapshot.workspaces.head
    val head = os.proc("git", "-C", tree.toString, "rev-parse", "HEAD").call().out.text().trim
    CompletionConfirmation(id, w.id, w.owner, w.path, head, w.revision, "acceptance://test/123", "confirmed by root")

  test("explicit confirmation maps verified branch identity and replays idempotently") {
    fixture { (_, tree, ledger, service) =>
      val op = request(tree, ledger)
      for
        first <- service.confirm(op)
        replay <- service.confirm(op)
      yield
        assert(first.isRight, first.toString)
        assertEquals(replay, first)
        assertEquals(ledger.snapshot.workspaces.head.branch, "actual-branch")
        assertEquals(ledger.snapshot.completionConfirmations.size, 1)
    }
  }

  test("dirty worktree and mismatched identity are rejected without ledger writes") {
    fixture { (_, tree, ledger, service) =>
      val op = request(tree, ledger)
      val before = ledger.snapshot
      os.write(tree / "untracked", "dirty")
      for dirty <- service.confirm(op) yield
        assert(dirty.isLeft)
        assertEquals(ledger.snapshot, before)
        assert(service.confirm(op.copy(owner = "other")).unsafeRunSync().isLeft)
    }
  }

  test("expected revision is a CAS gate and live occupancy fails closed") {
    fixture { (_, tree, ledger, _) =>
      val live = new WorkspaceOccupancy(_ => IO.raiseError(new RuntimeException("unknown occupancy")), _ => IO.pure(Nil))
      val service = new WorkspaceReconcile(ledger, live)
      val op = request(tree, ledger)
      for result <- service.confirm(op) yield assert(result.isLeft)
    }
  }

  test("concurrent confirmations on one revision admit exactly one operation") {
    fixture { (_, tree, ledger, service) =>
      val first = request(tree, ledger, "race-a")
      val second = request(tree, ledger, "race-b")
      for
        outcomes <- List(first, second).parTraverse(service.confirm)
      yield
        assertEquals(outcomes.count(_.isRight), 1)
        assertEquals(ledger.snapshot.completionConfirmations.size, 1)
        assertEquals(ledger.snapshot.workspaces.head.revision, first.expectedRevision + 1)
    }
  }

  test("same operation id with different evidence is rejected") {
    fixture { (_, tree, ledger, service) =>
      val op = request(tree, ledger)
      for
        first <- service.confirm(op)
        conflict <- service.confirm(op.copy(acceptanceEvidence = "different"))
      yield
        assert(first.isRight)
        assert(conflict.isLeft)
    }
  }
end WorkspaceReconcileSpec
