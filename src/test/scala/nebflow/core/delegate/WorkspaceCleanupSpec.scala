package nebflow.core.delegate

import munit.FunSuite
import WorkspaceLedger.*

class WorkspaceCleanupSpec extends FunSuite:

  private def fixture(f: (os.Path, os.Path, WorkspaceLedger, WorkspaceCleanup) => Unit): Unit =
    val root = os.temp.dir(prefix = "workspace-cleanup-")
    val repo = root / "repo"
    val tree = root / "task"
    os.makeDir.all(repo)
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = repo, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    try
      git("init", "-b", "main"); git("config", "user.name", "test"); git("config", "user.email", "test@example.invalid")
      git("commit", "--allow-empty", "-m", "base")
      val base = git("rev-parse", "HEAD")
      git("worktree", "add", "-b", "delegate/task", tree.toString, "main")
      os.write(tree / "result.txt", "committed result")
      os.proc("git", "add", "result.txt").call(cwd = tree)
      os.proc("git", "commit", "-m", "result").call(cwd = tree, stdout = os.Pipe)
      val ledger = new WorkspaceLedger(root / "ledger.json")
      ledger.register(
        Workspace(
          "task",
          "project",
          WorkspaceLedger.repositoryKey(repo),
          tree.toNIO.toRealPath().toString,
          "delegate/task",
          "main",
          base,
          "delegate-owner",
          "kernel"
        )
      )
      f(repo, tree, ledger, new WorkspaceCleanup(ledger))
    finally os.remove.all(root)

    end try

  end fixture

  test("unmerged cleanup needs explicit confirmation and always retains the branch") {
    fixture { (repo, tree, ledger, cleanup) =>
      val preview = cleanup.inspect("task").toOption.get
      assert(preview.unmerged)
      assert(cleanup.remove("task", preview.previewToken, false, false).isLeft)
      assert(os.exists(tree / "result.txt"))
      assert(cleanup.remove("task", preview.previewToken, true, false).isRight)
      assert(!os.exists(tree))
      assertEquals(os.proc("git", "rev-parse", "--verify", "delegate/task").call(cwd = repo).exitCode, 0)
      assertEquals(ledger.owned("delegate-owner").get.lifecycle, "closed-discarded")
      assert(ledger.admit("delegate-owner", true).isLeft)
    }
  }

  test("confirmation detects changed file content even when porcelain status is unchanged") {
    fixture { (_, tree, _, cleanup) =>
      os.write.over(tree / "result.txt", "first edit")
      val preview = cleanup.inspect("task").toOption.get
      assert(preview.changes.nonEmpty)
      os.write.over(tree / "result.txt", "different edit")
      assert(cleanup.remove("task", preview.previewToken, true, true).isLeft)
      val latest = cleanup.inspect("task").toOption.get
      assert(cleanup.remove("task", latest.previewToken, true, false).isLeft)
      assert(cleanup.remove("task", latest.previewToken, true, true).isRight)
    }
  }

  test("ignored files are disclosed and fingerprinted before destructive cleanup") {
    fixture { (_, tree, _, cleanup) =>
      os.write(tree / ".gitignore", "private.cache\n")
      os.write(tree / "private.cache", "first private content")
      val preview = cleanup.inspect("task").toOption.get
      assert(preview.changes.exists(_.contains("private.cache")))
      os.write.over(tree / "private.cache", "changed private content")
      assert(cleanup.remove("task", preview.previewToken, true, true).isLeft)
      val latest = cleanup.inspect("task").toOption.get
      assert(cleanup.remove("task", latest.previewToken, true, false).isLeft)
      assert(os.exists(tree / "private.cache"))
      assert(cleanup.remove("task", latest.previewToken, true, true).isRight)
    }
  }

  test("busy owners are refused, removal failures preserve retry state and branches") {
    fixture { (repo, tree, ledger, cleanup) =>
      assert(cleanup.inspect("task", Set("delegate-owner")).isLeft)
      os.proc("git", "worktree", "lock", tree.toString).call(cwd = repo)
      val preview = cleanup.inspect("task").toOption.get
      assert(cleanup.remove("task", preview.previewToken, true, false).isLeft)
      assertEquals(ledger.owned("delegate-owner").get.lifecycle, "open")
      assert(ledger.snapshot.cleanups("task").error.nonEmpty)
      assert(os.exists(tree))
      os.proc("git", "worktree", "unlock", tree.toString).call(cwd = repo)
      val retry = cleanup.inspect("task").toOption.get
      assert(cleanup.remove("task", retry.previewToken, true, false).isRight)
    }
  }

  test("integrated cleanup only removes the tree; verification and final merger report remain required") {
    fixture { (repo, tree, ledger, cleanup) =>
      os.proc("git", "merge", "--no-ff", "delegate/task", "-m", "isolated fixture merge")
        .call(cwd = repo, stdout = os.Pipe)
      val before = os.proc("git", "rev-parse", "HEAD").call(cwd = repo).out.text().trim
      val w = ledger.snapshot.workspaces.head
      val other = new WorkspaceLedger(ledger.path / os.up / "pending-ledger.json")
      other.register(w.copy(lifecycle = "merged-cleanup-pending"))
      val pending = new WorkspaceCleanup(other)
      val preview = pending.inspect("task").toOption.get
      assert(!preview.unmerged)
      assert(pending.remove("task", preview.previewToken, false, false).isRight)
      assertEquals(other.snapshot.workspaces.head.lifecycle, "merged-cleanup-pending")
      assert(other.snapshot.cleanups("task").removed)
      assertEquals(os.proc("git", "rev-parse", "HEAD").call(cwd = repo).out.text().trim, before)
      assert(!os.exists(tree))
    }
  }

  test("common-dir lock excludes operations from another data root") {
    fixture { (repo, _, _, _) =>
      val key = WorkspaceLedger.repositoryKey(repo)
      val outer = RepositoryWriteLock.withLock(key) {
        assert(RepositoryWriteLock.withLock(key)(()).isLeft)
      }
      assert(outer.isRight)
      assert(RepositoryWriteLock.withLock(key)(()).isRight)
    }
  }

  test("interrupted cleanup is reconciled on explicit retry without resuming deletion") {
    fixture { (_, tree, ledger, cleanup) =>
      val preview = cleanup.inspect("task").toOption.get
      assert(ledger.claimCleanup("task", preview.revision, preview.previewToken).isRight)
      assert(ledger.admit("delegate-owner", true).isLeft)
      assert(cleanup.reconcile("task").isRight)
      assert(os.exists(tree))
      assertEquals(ledger.snapshot.workspaces.head.lifecycle, "open")
      val refreshed = cleanup.inspect("task").toOption.get
      assert(cleanup.remove("task", refreshed.previewToken, true, false).isRight)
      assert(cleanup.remove("task", refreshed.previewToken, true, false).isRight)
    }
  }

  test("externally replacing a task path with a symlink is not cleanup authorization") {
    fixture { (repo, tree, _, cleanup) =>
      val saved = tree / os.up / "saved-task"
      os.move(tree, saved)
      java.nio.file.Files.createSymbolicLink(tree.toNIO, repo.toNIO)
      assert(cleanup.inspect("task").isLeft)
      assert(os.exists(repo / ".git"))
    }
  }

  test("ledger revision notifications occur after durable writes and cannot fail the commit") {
    fixture { (_, _, ledger, _) =>
      val before = ledger.snapshot.revision
      WorkspaceLedger.onChanged((_, _) => throw new RuntimeException("observer unavailable"))
      try
        assert(ledger.setBaseline("repo", "release").isRight)
        assertEquals(new WorkspaceLedger(ledger.path).snapshot.revision, before + 1)
      finally WorkspaceLedger.onChanged((_, _) => ())
    }
  }
end WorkspaceCleanupSpec
