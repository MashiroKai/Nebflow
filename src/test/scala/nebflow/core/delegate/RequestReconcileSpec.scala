package nebflow.core.delegate

import WorkspaceLedger.*

/**
 * Startup reconciliation and recovery closure (node-feedback delivery · Priority C):
 * read-only readings of in-flight merge requests against the actual Git state, plus
 * the legal cleanup-only closure for a source that was integrated without this
 * flow's merge commit (crash before the report, or external integration).
 */
class RequestReconcileSpec extends munit.FunSuite:

  /** Real temp git repository with a registered development worktree and one accepted request. */
  private def fixture(tag: String): (os.Path, os.Path, WorkspaceLedger, String) =
    val root = os.temp.dir(prefix = s"reconcile-$tag-")
    val repo = root / "repo"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    git("init", "-b", "main")
    git("config", "user.name", "test")
    git("config", "user.email", "test@example.invalid")
    git("commit", "--allow-empty", "-m", "base")
    val base = git("rev-parse", "HEAD")
    val tree = root / "task"
    git("worktree", "add", "-b", "delegate/task", tree.toString, "main")
    os.write(tree / "task.txt", "authorized work")
    os.proc("git", "add", "task.txt").call(cwd = tree)
    os.proc("git", "commit", "-m", "task").call(cwd = tree, stdout = os.Pipe)
    val ledger = new WorkspaceLedger(root / "ledger.json")
    val repository = WorkspaceLedger.repositoryKey(repo)
    ledger.merger("project", repository, "merger")
    val workspace = Workspace(
      "task",
      "project",
      repository,
      tree.toNIO.toRealPath().toString,
      "delegate/task",
      "main",
      base,
      "owner",
      "kernel"
    )
    ledger.register(workspace)
    val item = MergerFacts.pinSource(workspace).toOption.get
    assert(ledger.requestMerge(MergeRequest("request", repository, "merger", "fixture authorization", List(item))).isRight)
    (repo, tree, ledger, git("rev-parse", "delegate/task"))

  private def gitIn(repo: os.Path)(args: String*): String =
    os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim

  private def proof(
    repo: os.Path,
    name: String,
    source: String,
    mergeCommit: Option[String],
    integration: Option[String]
  ): String =
    val path = repo / ".nebflow" / "evidence" / name
    os.makeDir.all(path / os.up)
    os.write(
      path,
      io.circe.Json
        .fromFields(
          List(
            "status" -> io.circe.Json.fromString("passed"),
            "sourceCommit" -> io.circe.Json.fromString(source)
          ) ++
            mergeCommit.map(v => "mergeCommit" -> io.circe.Json.fromString(v)) ++
            integration.map(v => "integration" -> io.circe.Json.fromString(v)) ++
            List(
              "command" -> io.circe.Json.fromString("git diff --check"),
              "exitCode" -> io.circe.Json.fromInt(0),
              "ranAt" -> io.circe.Json.fromString("2026-11-08T00:00:00Z")
            )
        )
        .noSpaces
    )
    path.toString

  test("crash before the report: reconcile recovers the merge commit and the request closes without re-merging") {
    val (repo, tree, ledger, source) = fixture("crash")
    try
      def report(event: MergeReport) = ledger.reportMerge("request", "merger", event)(MergerFacts.validate)
      assert(report(MergeReport("check", 0, "task", "checking")).isRight)
      // The merger drives a real --no-ff merge with plain git, then crashes without reporting it.
      gitIn(repo)("merge", "--no-ff", "delegate/task", "-m", "crash fixture integration")
      val merge = gitIn(repo)("rev-parse", "HEAD")
      val reading = RequestReconcile.request(ledger.snapshot.requests.head, ledger.snapshot.workspaces)
      assertEquals(reading.items.map(i => (i.reading, i.integration, i.mergeCommit)), List(("integrated", Some("merge-commit"), Some(merge))))
      // Reconcile is read-only: a repeated reading is identical and the ledger does not move.
      val revisionBefore = ledger.snapshot.revision
      assertEquals(RequestReconcile.forMerger("merger", ledger), List(reading))
      assertEquals(ledger.snapshot.revision, revisionBefore)
      // The merger never re-merges: it closes with the recovered commit (checking -> merged-cleanup-pending).
      assert(report(MergeReport("close", 1, "task", "merged-cleanup-pending", Some(merge))).isRight)
      assertEquals(ledger.snapshot.requests.head.items.head.mergeCommit, Some(merge))
      gitIn(repo)("worktree", "remove", tree.toString)
      val verification = proof(repo, "crash-verification.json", source, Some(merge), None)
      assert(report(MergeReport("done", 2, "task", "completed", Some(merge), Some(verification))).isRight)
      assertEquals(ledger.snapshot.requests.head.status, "completed")
      assertEquals(ledger.snapshot.workspaces.head.lifecycle, "closed-merged")
      // A completed request no longer appears in the startup reading.
      assertEquals(RequestReconcile.forMerger("merger", ledger), Nil)
    finally os.remove.all(repo / os.up)
    end try
  }

  test("external integration mid-request: reconcile reads source-ancestor and cleanup-only closure completes") {
    val (repo, tree, ledger, source) = fixture("external")
    try
      def report(event: MergeReport) = ledger.reportMerge("request", "merger", event)(MergerFacts.validate)
      assert(report(MergeReport("check", 0, "task", "checking")).isRight)
      // A third party integrates the source without a two-parent merge commit.
      gitIn(repo)("merge", "--ff-only", "delegate/task")
      assert(gitIn(repo)("log", "--merges", "--format=%H", "main").isEmpty, "fixture: external integration is not a merge commit")
      val reading = RequestReconcile.request(ledger.snapshot.requests.head, ledger.snapshot.workspaces)
      assertEquals(reading.items.map(i => (i.reading, i.integration, i.mergeCommit)), List(("integrated", Some("source-ancestor"), None)))
      // Recovery stays manual: nothing auto-runs and the request is still open.
      assertEquals(ledger.snapshot.requests.head.status, "processing")
      // A fabricated merge commit on the declaration is rejected without moving the ledger.
      val before = ledger.snapshot.revision
      assert(report(MergeReport("fabricate", 1, "task", "merged-cleanup-pending", Some("f" * 40), None, Some("source-ancestor"))).isLeft)
      assertEquals(ledger.snapshot.revision, before)
      // Legal cleanup-only closure: no new merge commit, the source is never re-merged.
      assert(report(MergeReport("close", 1, "task", "merged-cleanup-pending", None, None, Some("source-ancestor"))).isRight)
      assertEquals(ledger.snapshot.requests.head.items.head.integration, Some("source-ancestor"))
      gitIn(repo)("worktree", "remove", tree.toString)
      val verification = proof(repo, "external-verification.json", source, None, Some("source-ancestor"))
      assert(report(MergeReport("done", 2, "task", "completed", None, Some(verification))).isRight)
      assertEquals(ledger.snapshot.requests.head.status, "completed")
      assert(gitIn(repo)("log", "--merges", "--format=%H", "main").isEmpty, "closure must not create a merge commit")
      assertEquals(
        os.proc("git", "rev-parse", "--verify", "delegate/task^{commit}").call(cwd = repo, check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode,
        0,
        "the retained branch proves the source was never undone"
      )
    finally os.remove.all(repo / os.up)
    end try
  }

  test("reconcile readings: resumable when intact, unknown when the directory, branch or repository is gone") {
    val (repo, tree, ledger, source) = fixture("readings")
    val ghostDir = os.temp.dir(prefix = "reconcile-ghost-")
    try
      val repository = WorkspaceLedger.repositoryKey(repo)
      // Intact workspace, source not contained in the target: resumable for a manual continue.
      val resumable = RequestReconcile.request(ledger.snapshot.requests.head, ledger.snapshot.workspaces)
      assertEquals(resumable.items.map(_.reading), List("resumable"))
      // Workspace directory deleted: unknown.
      os.remove.all(tree)
      val removed = RequestReconcile.request(ledger.snapshot.requests.head, ledger.snapshot.workspaces)
      assertEquals(removed.items.map(_.reading), List("unknown"))
      assert(removed.items.head.detail.contains("Workspace directory is missing"))
      // Workspace branch gone (directory intact): unknown.
      assert(
        ledger.register(
          Workspace("ghost", "project", repository, ghostDir.toString, "delegate/ghost", "main", "base", "owner-ghost", "kernel")
        ).isRight
      )
      assert(
        ledger
          .requestMerge(MergeRequest("ghost-request", repository, "merger", "fixture authorization", List(MergeItem("ghost", source, "main"))))
          .isRight
      )
      val ghost = RequestReconcile.forMerger("merger", ledger).find(_.requestId == "ghost-request").get
      assertEquals(ghost.items.map(_.reading), List("unknown"))
      assert(ghost.items.head.detail.contains("Workspace branch is gone"))
      // Repository unreadable: unknown.
      val missing = os.Path(s"/tmp/reconcile-missing-repo-${System.identityHashCode(ledger)}")
      ledger.merger("project2", missing.toString, "merger2")
      ledger.register(
        Workspace(
          "orphan",
          "project2",
          missing.toString,
          s"/tmp/reconcile-missing-ws-${System.identityHashCode(ledger)}",
          "delegate/orphan",
          "main",
          "base",
          "owner-orphan",
          "kernel"
        )
      )
      assert(
        ledger
          .requestMerge(MergeRequest("orphan-request", missing.toString, "merger2", "fixture authorization", List(MergeItem("orphan", "a" * 40, "main"))))
          .isRight
      )
      val orphan = RequestReconcile.forMerger("merger2", ledger).find(_.requestId == "orphan-request").get
      assertEquals(orphan.items.map(_.reading), List("unknown"))
    finally
      os.remove.all(repo / os.up)
      os.remove.all(ghostDir)
    end try
  }

  test("integration closure transitions: declarations are guarded, revisions and event idempotency hold") {
    val root = os.temp.dir(prefix = "reconcile-transitions-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      ledger.merger("project", "repo", "merger")
      ledger.register(Workspace("a", "project", "repo", "/tmp/a", "delegate/a", "main", "base", "owner-a", "kernel"))
      assert(ledger.requestMerge(MergeRequest("request", "repo", "merger", "auth", List(MergeItem("a", "a" * 40, "main")))).isRight)
      def report(event: MergeReport) = ledger.reportMerge("request", "merger", event)((_, _, _) => Right(()))
      // A bare closure from a pre-merge state carries no evidence of integration: illegal.
      assert(report(MergeReport("bare", 0, "a", "merged-cleanup-pending")).isLeft)
      // Unknown integration kinds are rejected.
      assert(report(MergeReport("unknown-kind", 0, "a", "merged-cleanup-pending", None, None, Some("rebase"))).isLeft)
      // A fabricated merge commit on a source-ancestor declaration is rejected.
      assert(report(MergeReport("fabricate", 0, "a", "merged-cleanup-pending", Some("f" * 40), None, Some("source-ancestor"))).isLeft)
      // Declared source-ancestor closure from reserved is legal and recorded on the item.
      assert(report(MergeReport("close-a", 0, "a", "merged-cleanup-pending", None, None, Some("source-ancestor"))).isRight)
      assertEquals(ledger.snapshot.requests.head.items.head.integration, Some("source-ancestor"))
      // Replaying the identical event is idempotent: same request, revision unmoved.
      val replay = ledger
        .reportMerge("request", "merger", MergeReport("close-a", 0, "a", "merged-cleanup-pending", None, None, Some("source-ancestor")))(
          (_, _, _) => Right(())
        )
      assertEquals(replay.map(_.revision), Right(1L))
      assertEquals(ledger.snapshot.requests.head.revision, 1L)
      // A stale revision is rejected.
      assert(report(MergeReport("stale", 0, "a", "completed")).isLeft)
      // Declaring merge-commit on a source-ancestor item is a fabrication: rejected.
      assert(report(MergeReport("swap-kind", 1, "a", "completed", Some("c" * 40), None, Some("merge-commit"))).isLeft)
      // Completion falls back to the item's recorded integration kind.
      assert(report(MergeReport("done-a", 1, "a", "completed")).isRight)
      assertEquals(ledger.snapshot.requests.head.status, "completed")
      // From awaiting-user: bare closure is illegal, declared source-ancestor closure is legal.
      val paused = new WorkspaceLedger(root / "paused.json")
      paused.merger("project", "repo", "merger")
      paused.register(Workspace("p", "project", "repo", "/tmp/p", "delegate/p", "main", "base", "owner-p", "kernel"))
      assert(paused.requestMerge(MergeRequest("paused", "repo", "merger", "auth", List(MergeItem("p", "b" * 40, "main")))).isRight)
      def pausedReport(event: MergeReport) = paused.reportMerge("paused", "merger", event)((_, _, _) => Right(()))
      assert(pausedReport(MergeReport("pause", 0, "p", "awaiting-user")).isRight)
      assert(pausedReport(MergeReport("bare-p", 1, "p", "merged-cleanup-pending")).isLeft)
      assert(pausedReport(MergeReport("close-p", 1, "p", "merged-cleanup-pending", None, None, Some("source-ancestor"))).isRight)
    finally os.remove.all(root)
    end try
  }
end RequestReconcileSpec
