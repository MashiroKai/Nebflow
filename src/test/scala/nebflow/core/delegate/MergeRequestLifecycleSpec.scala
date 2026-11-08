package nebflow.core.delegate

import WorkspaceLedger.*

class MergeRequestLifecycleSpec extends munit.FunSuite:

  test("authorization is immutable, reports are identity checked, serial and idempotent") {
    val root = os.temp.dir(prefix = "merge-request-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      ledger.merger("project", "repo", "merger")
      List("a", "b").foreach(id =>
        ledger.register(
          Workspace(id, "project", "repo", s"/tmp/$id", s"delegate/$id", "main", "base", s"owner-$id", "kernel")
        )
      )
      val request = MergeRequest(
        "request",
        "repo",
        "merger",
        "user selected a,b",
        List(MergeItem("a", "a" * 40, "main"), MergeItem("b", "b" * 40, "main"))
      )
      assert(ledger.requestMerge(request).isRight)
      assert(ledger.requestMerge(request.copy(items = request.items.take(1))).isLeft)
      assert(ledger.admit("owner-a", true).isLeft)
      def report(event: MergeReport, caller: String = "merger") =
        ledger.reportMerge("request", caller, event)((_, _, _) => Right(()))
      val checking = MergeReport("check-a", 0, "a", "checking")
      assert(report(checking, "intruder").isLeft)
      assert(report(checking).isRight)
      assert(report(checking).isRight)
      assert(report(checking.copy(phase = "merging")).isLeft)
      assert(report(MergeReport("check-b", 1, "b", "checking")).isLeft)
      assert(report(MergeReport("merge-a", 1, "a", "merging")).isRight)
      assert(report(MergeReport("land-a", 2, "a", "merged-cleanup-pending", Some("c" * 40))).isRight)
      assert(report(MergeReport("merge-again", 3, "a", "merging")).isLeft)
      assert(report(MergeReport("done-a", 3, "a", "completed")).isRight)
      assertEquals(ledger.owned("owner-a").get.lifecycle, "closed-merged")
      assert(ledger.admit("owner-a", true).isLeft)
      val stopped = ledger.cancelRequest("request").toOption.get
      assert(stopped.cancelRequested)
      assertEquals(stopped.items.head.phase, "completed")
      assertEquals(new WorkspaceLedger(ledger.path).snapshot.requests.head.items.head.phase, "completed")
    finally os.remove.all(root)
    end try
  }

  test("completed requires actual merge, matching verification and worktree removal") {
    val root = os.temp.dir(prefix = "merger-facts-")
    val repo = root / "repo"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    try
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
      val source = git("rev-parse", "delegate/task")
      val beforeWorkspace = Workspace(
        "task",
        "project",
        WorkspaceLedger.repositoryKey(repo),
        tree.toNIO.toRealPath().toString,
        "delegate/task",
        "main",
        base,
        "owner",
        "kernel"
      )
      assertEquals(MergerFacts.pinSource(beforeWorkspace).map(_.sourceCommit), Right(source))
      val abandoned = root / "abandoned"
      git("worktree", "add", "-b", "delegate/abandoned", abandoned.toString, "main")
      os.remove.all(abandoned)
      assertEquals(MergerFacts.pinSource(beforeWorkspace).map(_.sourceCommit), Right(source))
      assert(
        MergerFacts
          .validate(
            beforeWorkspace,
            MergeItem("task", "f" * 40, "main"),
            MergeReport("unknown-source", 0, "task", "cancelled")
          )
          .isLeft
      )
      val queued = new WorkspaceLedger(root / "queued-ledger.json")
      queued.merger("project", beforeWorkspace.repository, "merger")
      queued.register(beforeWorkspace)
      queued.requestMerge(
        MergeRequest(
          "queued",
          beforeWorkspace.repository,
          "merger",
          "explicit fixture authorization",
          List(MergerFacts.pinSource(beforeWorkspace).toOption.get)
        )
      )
      os.write(tree / "uncommitted.txt", "not authorized")
      assert(MergerFacts.pinSource(beforeWorkspace).left.toOption.exists(_.contains("uncommitted")))
      assert(
        queued
          .reportMerge("queued", "merger", MergeReport("check-dirty", 0, "task", "checking"))(MergerFacts.validate)
          .isLeft
      )
      assert(
        queued
          .reportMerge("queued", "merger", MergeReport("pause-dirty", 0, "task", "awaiting-user"))(MergerFacts.validate)
          .isRight
      )
      assertEquals(queued.snapshot.requests.head.status, "paused")
      os.remove(tree / "uncommitted.txt")
      assert(
        queued
          .reportMerge("queued", "merger", MergeReport("check-repaired", 1, "task", "checking"))(MergerFacts.validate)
          .isRight
      )
      git("merge", "--no-ff", "delegate/task", "-m", "merge isolated fixture")
      assert(MergerFacts.pinSource(beforeWorkspace).left.toOption.exists(_.contains("already contained")))
      val merge = git("rev-parse", "HEAD")
      val workspace = Workspace(
        "task",
        "project",
        WorkspaceLedger.repositoryKey(repo),
        tree.toNIO.toRealPath().toString,
        "delegate/task",
        "main",
        base,
        "owner",
        "kernel"
      )
      val item = MergeItem("task", source, "main", "merged-cleanup-pending", Some(merge))
      val proof = repo / ".nebflow" / "evidence" / "verification.json"
      os.makeDir.all(proof / os.up)
      os.write(
        proof,
        s"""{"status":"passed","sourceCommit":"$source","mergeCommit":"$merge","command":"git diff --check","exitCode":0,"ranAt":"2026-11-08T00:00:00Z"}"""
      )
      val event = MergeReport("complete", 1, "task", "completed", Some(merge), Some(proof.toString))
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      git("worktree", "remove", tree.toString)
      assertEquals(MergerFacts.validate(workspace, item, event), Right(()))
      os.write.over(proof, s"""{"status":"passed","sourceCommit":"$source","mergeCommit":"$merge"}""")
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      os.write.over(
        proof,
        s"""{"status":"passed","sourceCommit":"$source","mergeCommit":"$merge","command":"git diff --check","exitCode":0,"ranAt":"2026-11-08T00:00:00Z"}"""
      )
      os.write.over(
        proof,
        """{"status":"passed","sourceCommit":"wrong","mergeCommit":"wrong","command":"c","exitCode":0,"ranAt":"t"}"""
      )
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      os.write.over(
        proof,
        s"""{"status":"passed","sourceCommit":"$source","mergeCommit":"$merge","exitCode":0,"ranAt":"t"}"""
      )
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      os.write.over(
        proof,
        s"""{"status":"passed","sourceCommit":"$source","mergeCommit":"$merge","command":"git diff --check","ranAt":"t"}"""
      )
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      os.write.over(
        proof,
        s"""{"status":"passed","sourceCommit":"$source","mergeCommit":"$merge","command":"git diff --check","exitCode":1,"ranAt":"t"}"""
      )
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      os.write.over(
        proof,
        s"""{"status":"passed","sourceCommit":"$source","mergeCommit":"$merge","command":"git diff --check","exitCode":0}"""
      )
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      assert(MergerFacts.validate(workspace, item, event.copy(phase = "cancelled")).isLeft)
    finally os.remove.all(root)
    end try
  }
end MergeRequestLifecycleSpec
