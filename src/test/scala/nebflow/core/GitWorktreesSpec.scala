package nebflow.core

import munit.FunSuite

import scala.util.Try

/**
 * Read-only Git facts spec: the seat-reading faces the engine keeps (branch /
 * HEAD readings, dirty sets, status digests, ancestry facts). Worktree/branch
 * creation is deliberately absent from the util — delegates that need a
 * worktree create and manage their own with `git worktree`.
 */
class GitWorktreesSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-git-worktrees"

  private def gitAvailable: Boolean =
    Try(os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
      .getOrElse(false)

  private def mkRepo(dir: os.Path): Unit =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)

  test("isGitRepo / headSha / currentBranch: a plain repo reads its checkout facts; a non-git dir reads nothing") {
    if !gitAvailable then () // environment without git = skip
    else
      val repo = tempRoot / "read-repo"
      mkRepo(repo)
      assert(GitWorktrees.isGitRepo(repo))
      assert(GitWorktrees.currentBranch(repo).contains("main"))
      assert(GitWorktrees.headSha(repo).nonEmpty)
      val plain = os.temp.dir(prefix = "not-a-repo")
      assert(!GitWorktrees.isGitRepo(plain))
      assertEquals(GitWorktrees.currentBranch(plain), None)
      assertEquals(GitWorktrees.headSha(plain), None)
      assertEquals(GitWorktrees.dirtyPaths(plain), None, "fail-soft: None keeps 'unreadable' distinct from clean")
  }

  test("dirtyPaths: uncommitted and untracked paths surface; a clean repo reads Some(Nil)") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "dirty-repo"
      mkRepo(repo)
      assertEquals(GitWorktrees.dirtyPaths(repo), Some(Nil))
      os.write.over(repo / "tracked.txt", "dirty")
      os.write(repo / "untracked.txt", "new")
      val dirty = GitWorktrees.dirtyPaths(repo).getOrElse(Nil)
      assert(dirty.exists(_.contains("tracked.txt")))
      assert(dirty.exists(_.contains("untracked.txt")))
  }

  test("statusDigest: judge input carries branch/base/dirty/ahead sections and survives non-git dirs") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "digest-repo"
      mkRepo(repo)
      os.write.over(repo / "tracked.txt", "dirty")
      val digest = GitWorktrees.statusDigest(repo, Some("main"))
      assert(digest.contains("branch: main"))
      assert(digest.contains("base: main"))
      assert(digest.contains("tracked.txt"), "uncommitted paths must be visible to the judge")
      assert(!digest.contains("commits ahead"), "HEAD == base means no ahead section")
      // a branch checkout with a commit surfaces the ahead section
      os.proc("git", "-C", repo.toString, "checkout", "-b", "wt").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      os.proc("git", "-C", repo.toString, "add", ".").call(check = true)
      os.proc("git", "-C", repo.toString, "commit", "-m", "wip").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      val digest2 = GitWorktrees.statusDigest(repo, Some("main"))
      assert(digest2.contains("branch: wt"))
      assert(digest2.contains("commits ahead"), "a commit ahead of base must surface")
      // non-git dir: bounded digest, never throws
      val plain = os.temp.dir(prefix = "digest-plain")
      val plainDigest = GitWorktrees.statusDigest(plain, Some("main"))
      assert(plainDigest.contains("branch: (detached or unknown)"))
      assert(!plainDigest.contains("uncommitted changes"))
  }

  test("commitsAhead / isAncestor / resolveRef / mergeBase: ancestry facts for settlement annotations") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "ahead-repo"
      mkRepo(repo)
      os.proc("git", "-C", repo.toString, "checkout", "-b", "feat")
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      os.proc(
        "git",
        "-C",
        repo.toString,
        "commit",
        "--allow-empty",
        "-m",
        "work"
      ).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      assertEquals(GitWorktrees.commitsAhead(repo, "main", "feat"), 1)
      assertEquals(GitWorktrees.commitsAhead(repo, "feat", "main"), 0)
      assert(GitWorktrees.isAncestor(repo, "main", "feat").contains(true))
      assert(GitWorktrees.isAncestor(repo, "feat", "main").contains(false))
      assert(GitWorktrees.resolveRef(repo, "main").nonEmpty)
      assert(GitWorktrees.mergeBase(repo, "main", "feat").nonEmpty)
  }

end GitWorktreesSpec
