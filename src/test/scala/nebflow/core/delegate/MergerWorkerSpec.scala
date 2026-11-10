package nebflow.core.delegate

import cats.effect.IO
import WorkspaceLedger.{MergeItem, MergeReport, MergeRequest}

import munit.CatsEffectSuite
import nebflow.shared.PathUtil

import scala.util.Try

/**
 * The persistent merger worker against a real repository: one pass lands one
 * request (serial claims), a claimed in-flight request recovers at boot and
 * lands on the resumed pass, an externally integrated source closes out
 * without a second merge, and the phase machine refuses skips and fails
 * cleanly when a workspace side becomes unreconcilable.
 */
class MergerWorkerSpec extends CatsEffectSuite:
  private val previous = PathUtil.dataRoot
  private var root: os.Path = os.pwd

  override def beforeEach(context: BeforeEach): Unit =
    root = os.temp.dir(prefix = "merger-worker-")
    PathUtil.setDataRoot(root)

  override def afterEach(context: AfterEach): Unit =
    PathUtil.setDataRoot(previous)
    os.remove.all(root)

  /** Real repository with one registered development worktree per id. */
  private def fixture(
    tag: String,
    ids: String*
  ): (os.Path, String, Map[String, os.Path], Map[String, String]) =
    val repo = root / s"repo-$tag"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main")
    git("config", "user.name", "test")
    git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    val base = git("rev-parse", "HEAD")
    val trees = ids.map { id =>
      val tree = root / s"$tag-$id"
      git("worktree", "add", "-b", s"delegate/$id", tree.toString, "main")
      os.write(tree / s"$id.txt", s"work of $id")
      os.proc("git", "add", s"$id.txt").call(cwd = tree, stdout = os.Pipe, stderr = os.Pipe)
      os.proc("git", "commit", "-m", s"work of $id").call(cwd = tree, stdout = os.Pipe, stderr = os.Pipe)
      id -> tree
    }.toMap
    val repository = WorkspaceLedger.repositoryKey(repo)
    val ledger = WorkspaceLedger.default
    ledger.merger("project", repository, "merger")
    ids.foreach { id =>
      ledger.register(
        WorkspaceLedger.Workspace(
          id,
          "project",
          repository,
          trees(id).toNIO.toRealPath().toString,
          s"delegate/$id",
          "main",
          base,
          s"owner-$id",
          "kernel"
        )
      )
    }
    (repo, repository, trees, ids.map(id => id -> git("rev-parse", s"delegate/$id")).toMap)
  end fixture

  /** Queue one request whose item is pinned by the same facts the merger proves. */
  private def queue(
    ledger: WorkspaceLedger,
    requestId: String,
    repository: String,
    workspaceId: String
  ): MergeItem =
    val ws = ledger.snapshot.workspaces.find(_.id == workspaceId).getOrElse(fail(s"workspace $workspaceId missing"))
    val item = MergerFacts
      .pinSource(ws)
      .fold(error => fail(s"pinning $workspaceId failed: $error"), identity)
    assert(
      ledger.requestMerge(MergeRequest(requestId, repository, "merger", "user authorized", List(item))).isRight,
      s"queueing $requestId must be accepted"
    )
    item
  end queue

  private def gitLines(args: String*): List[String] =
    os.proc(Seq("git") ++ args).call(stdout = os.Pipe, stderr = os.Pipe).out.text().trim.linesIterator.toList

  test("one pass claims a queued request and lands it: merge, evidence, cleanup, completed") {
    val ledger = WorkspaceLedger.default
    for
      setup <- IO.blocking {
        val (repo, repository, trees, sources) = fixture("land", "task")
        queue(ledger, "request", repository, "task")
        (repo, repository, trees, sources)
      }
      (repo, repository, trees, sources) = setup
      done <- MergerWorker.runOnce()
    yield
      assert(done.exists(_.contains("request request")), done)
      val state = ledger.snapshot
      val request = state.requests.head
      assertEquals(request.status, "merged")
      val seat = state.workspaces.head
      assertEquals(seat.lifecycle, "closed-merged")
      assertEquals(seat.reservation, Some("request"), "the closing request stays recorded on the closed seat")
      assert(!os.exists(trees("task")), "the worktree is removed")
      val branchGone = os
        .proc("git", "--git-dir", repository, "rev-parse", "--verify", "refs/heads/delegate/task")
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .exitCode
      assert(branchGone != 0, "the fully merged branch is deleted")
      val ancestors = gitLines("--git-dir", repository, "merge-base", "--is-ancestor", sources("task"), "main")
      assert(ancestors.isEmpty, "the authorized source is contained in the target")
      val merges = gitLines("--git-dir", repository, "log", "--merges", "--format=%H %P", "main")
      assertEquals(merges.size, 1, "exactly one two-parent merge commit lands on main")
      val List(commit, _, second) = merges.head.split(" ").toList
      assertEquals(second, sources("task"), "the authorized source is the merge commit's second parent")
      val evidence = repo / ".nebflow" / "evidence" / "merger" / "request" / "task.json"
      assert(os.exists(evidence), "verification evidence is written under the repository")
      val item = request.items.head
      assert(item.verification.exists(path => os.exists(os.Path(path))), "the item records the evidence path")
      assert(item.verification.exists(_.endsWith(s"/merger/request/task.json")))
      val proof = io.circe.parser.parse(os.read(evidence)).fold(e => fail(e.toString), identity).hcursor
      assertEquals(proof.get[String]("status").toOption, Some("passed"))
      assertEquals(proof.get[String]("sourceCommit").toOption, Some(sources("task")))
      assert(proof.get[String]("mergeCommit").toOption.contains(commit), "the evidence names the merge commit")
      assertEquals(item.phase, "completed")
    end for
  }

  test("the worker claims serially: a second request waits until the first rests") {
    val ledger = WorkspaceLedger.default
    for
      repository <- IO.blocking {
        val (_, repository, _, sources) = fixture("serial", "a", "b")
        queue(ledger, "req-a", repository, "a")
        queue(ledger, "req-b", repository, "b")
        repository
      }
      first <- IO.blocking(ledger.claimNextMerge())
      _ = assertEquals(first, Some("req-a"), "the oldest queued request is claimed")
      second <- IO.blocking(ledger.claimNextMerge())
      _ = assertEquals(second, None, "no second claim while a request is in flight")
      released <- IO.blocking {
        val claim = ledger.snapshot.requests.find(_.id == "req-a").get
        ledger.reportMerge("req-a", "merger", MergeReport("release-a", claim.revision, "a", "cancelled"))(
          MergerFacts.validate
        )
      }
      _ = assert(released.isRight, s"the cancelled release must land, got: $released")
      third <- IO.blocking(ledger.claimNextMerge())
    yield
      assertEquals(third, Some("req-b"), "the slot frees when the claimed request rests")
      assert(repository.nonEmpty)
    end for
  }

  test("a claimed in-flight request recovers at boot and the resumed pass lands it") {
    val ledger = WorkspaceLedger.default
    for
      trees <- IO.blocking {
        val (_, repository, trees, _) = fixture("recover", "task")
        queue(ledger, "request", repository, "task")
        assertEquals(ledger.claimNextMerge(), Some("request"))
        assert(ledger.beginItem("request", "merger", "task").isRight)
        val before = ledger.snapshot.requests.head
        assertEquals(before.status, "verifying", "the interrupted pass had begun the item")
        assertEquals(before.items.head.phase, "merging")
        // Boot recovery reads only Git facts: the item returns to the rest
        // state and the request re-queues, nothing is undone.
        ledger.recoverRequests()
        val after = ledger.snapshot
        assertEquals(after.requests.head.status, "submitted", "the interrupted request re-queues")
        assertEquals(after.requests.head.items.head.phase, "reserved")
        assertEquals(after.requests.head.items.head.error, None)
        assertEquals(after.workspaces.head.lifecycle, "reserved", "the reservation survives recovery")
        trees
      }
      done <- MergerWorker.runOnce()
    yield
      assert(done.exists(_.contains("request request")), done)
      val state = ledger.snapshot
      assertEquals(state.requests.head.status, "merged")
      assertEquals(state.workspaces.head.lifecycle, "closed-merged")
      assert(!os.exists(trees("task")))
    end for
  }

  test("an externally integrated source is adopted as cleanup-only closure without a second merge") {
    val ledger = WorkspaceLedger.default
    for
      setup <- IO.blocking {
        val (repo, repository, trees, sources) = fixture("external", "task")
        queue(ledger, "request", repository, "task")
        assertEquals(ledger.claimNextMerge(), Some("request"))
        assert(ledger.beginItem("request", "merger", "task").isRight)
        // A third party integrates the source with a fast-forward while the
        // item is in flight: recovery must adopt the facts, not merge again.
        os.proc("git", "-C", repo.toString, "merge", "--ff-only", "delegate/task")
          .call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe)
        ledger.recoverRequests()
        val recovered = ledger.snapshot
        val item = recovered.requests.head.items.head
        assertEquals(item.phase, "merged-cleanup-pending", "recovery adopts the integrated facts")
        assertEquals(item.integration, Some("source-ancestor"))
        assertEquals(item.mergeCommit, None)
        assertEquals(recovered.workspaces.head.lifecycle, "merged-cleanup-pending")
        val mergesBefore =
          os.proc("git", "--git-dir", repository, "log", "--merges", "--format=%H", "main")
            .call(stdout = os.Pipe, stderr = os.Pipe)
            .out
            .text()
            .trim
        assert(mergesBefore.isEmpty, "fixture: the external fast-forward is not a merge commit")
        (repo, repository, trees, sources)
      }
      (repo, repository, trees, sources) = setup
      done <- MergerWorker.runOnce()
    yield
      assert(done.exists(_.contains("request request")), done)
      val state = ledger.snapshot
      assertEquals(state.requests.head.status, "merged")
      assertEquals(state.workspaces.head.lifecycle, "closed-merged")
      val merges = os.proc("git", "--git-dir", repository, "log", "--merges", "--format=%H", "main")
        .call(stdout = os.Pipe, stderr = os.Pipe)
        .out
        .text()
        .trim
      assert(merges.isEmpty, "cleanup-only closure must not create a merge commit")
      assert(!os.exists(trees("task")))
      val evidence = repo / ".nebflow" / "evidence" / "merger" / "request" / "task.json"
      assert(os.exists(evidence))
      val proof = io.circe.parser.parse(os.read(evidence)).fold(e => fail(e.toString), identity).hcursor
      assertEquals(proof.get[String]("integration").toOption, Some("source-ancestor"))
      assertEquals(proof.get[String]("sourceCommit").toOption, Some(sources("task")))
    end for
  }

  test("the phase machine refuses skips, and an unreconcilable item fails cleanly and opens the seat") {
    val ledger = WorkspaceLedger.default
    for
      repository <- IO.blocking {
        val (_, repository, trees, _) = fixture("guard", "task")
        queue(ledger, "request", repository, "task")
        val revision = ledger.snapshot.requests.head.revision
        val skipped = for
          _ <- ledger
            .reportMerge("request", "merger", MergeReport("skip-merge", revision, "task", "merging"))(
              MergerFacts.validate
            )
          _ <- ledger
            .reportMerge("request", "merger", MergeReport("skip-done", revision, "task", "completed"))(
              MergerFacts.validate
            )
        yield ()
        assert(skipped.isLeft, "reserved cannot jump straight to merging or completed")
        assertEquals(ledger.snapshot.requests.head.status, "submitted", "a refused skip moves nothing")
        // The workspace side becomes unreconcilable (its branch is gone) while
        // the repository stays readable: the item fails with recorded facts.
        os.proc("git", "--git-dir", repository, "update-ref", "-d", "refs/heads/delegate/task")
          .call(stdout = os.Pipe, stderr = os.Pipe)
        repository
      }
      done <- MergerWorker.runOnce()
    yield
      assert(done.exists(_.contains("request request")), done)
      val state = ledger.snapshot
      val request = state.requests.head
      assertEquals(request.status, "failed")
      val item = request.items.head
      assertEquals(item.phase, "failed")
      assert(item.error.nonEmpty, "the item carries the reconcile detail")
      assert(item.error.exists(_.contains("branch is gone")), item.error.getOrElse(""))
      val seat = state.workspaces.head
      assertEquals(seat.lifecycle, "open", "a failed item reopens the workspace")
      assertEquals(seat.reservation, None)
    end for
  }

end MergerWorkerSpec
