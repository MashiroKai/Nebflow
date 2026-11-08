package nebflow.core.delegate

import WorkspaceLedger.*

class MergeRequestLifecycleSpec extends munit.FunSuite:
  test("authorization is immutable, reports are identity checked, serial and idempotent") {
    val root = os.temp.dir(prefix = "merge-request-")
    try
      val ledger = new WorkspaceLedger(root / "ledger.json")
      ledger.merger("project", "repo", "merger")
      List("a", "b").foreach(id => ledger.register(Workspace(id, "project", "repo", s"/tmp/$id", s"delegate/$id", "main", "base", s"owner-$id", "kernel")))
      val request = MergeRequest("request", "repo", "merger", "user selected a,b", List(
        MergeItem("a", "a" * 40, "main"), MergeItem("b", "b" * 40, "main")))
      assert(ledger.requestMerge(request).isRight)
      assert(ledger.requestMerge(request.copy(items = request.items.take(1))).isLeft)
      assert(ledger.admit("owner-a", true).isLeft)
      def report(event: MergeReport, caller: String = "merger") = ledger.reportMerge("request", caller, event)((_, _, _) => Right(()))
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
  }

  test("completed requires actual merge, matching verification and worktree removal") {
    val root = os.temp.dir(prefix = "merger-facts-")
    val repo = root / "repo"
    os.makeDir.all(repo)
    def git(args: String*) = os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
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
      git("merge", "--no-ff", "delegate/task", "-m", "merge isolated fixture")
      val merge = git("rev-parse", "HEAD")
      val workspace = Workspace("task", "project", WorkspaceLedger.repositoryKey(repo), tree.toNIO.toRealPath().toString,
        "delegate/task", "main", base, "owner", "kernel")
      val item = MergeItem("task", source, "main", "merged-cleanup-pending", Some(merge))
      val proof = repo / ".nebflow" / "evidence" / "verification.json"
      os.makeDir.all(proof / os.up)
      os.write(proof, s"""{"status":"passed","sourceCommit":"$source","mergeCommit":"$merge"}""")
      val event = MergeReport("complete", 1, "task", "completed", Some(merge), Some(proof.toString))
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      git("worktree", "remove", tree.toString)
      assertEquals(MergerFacts.validate(workspace, item, event), Right(()))
      os.write.over(proof, """{"status":"passed","sourceCommit":"wrong"}""")
      assert(MergerFacts.validate(workspace, item, event).isLeft)
      assert(MergerFacts.validate(workspace, item, event.copy(phase = "cancelled")).isLeft)
    finally os.remove.all(root)
  }
