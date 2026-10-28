package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.GitWorktrees

import scala.util.Try

/**
 * PostMergeCleanup 合并后现场回收判读（引擎加固批 2026-10-09，P2-4；真仓实测，
 * 对主仓零 git 写）：
 *
 *  - ① 验收通过的合并现场（支已并：ahead==0 且祖先真）+ worktree 座位 ⇒
 *    `git worktree remove`（无 force）+ `git branch -d`（小写）双动作落地，
 *    ChainStore `worktree-cleanup` 行 action=removed；
 *  - ② 零领先守卫：分支仍有领先提交 ⇒ 现场保留（worktree/分支双在），
 *    行 action=kept 且 reason 携 zero-ahead guard；
 *  - ③ 缺锚（无 worktree / 无分支 / 无基分支）= 零动作（false，无行）；
 *  - ④ 基工作区不可读（非 git）= fail-soft 保留现场（绝不抛）；
 *  - ⑤ 无记录 = false（no-op）；
 *  - ⑥ 抢救守卫（WorktreeRescue）：合并现场有主工作区缺失的报告 ⇒ 移除前
 *    抢救回主工作区，账本行携带 rescue 摘要（作者令 2026-10-24）。
 */
class PostMergeCleanupSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-post-merge-cleanup"

  private def gitAvailable: Boolean =
    Try(os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
      .getOrElse(false)

  /**
   * main@init + delegate 分支；merge=deliver ⇒ 分支一笔交付提交后 --no-ff 并回
   * main（验收通过的合并形态）；否则分支带领先提交停在 fork（守卫应拦）。
   * extraFile = 分支上随交付提交一起提交的附加文件（报告抢救测试用——文件
   * 落进合并提交，两侧都被跟踪）。
   */
  private def mkRepo(
    dir: os.Path,
    branch: String,
    merged: Boolean,
    extraFile: Option[(os.RelPath, String)] = None
  ): os.Path =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    g("checkout", "-b", branch)
    os.write(dir / "deliverable.txt", "deliverable")
    extraFile.foreach { case (rel, content) =>
      os.makeDir.all(dir / rel / os.up)
      os.write(dir / rel, content)
    }
    g("add", ".")
    g("commit", "-m", "deliverable")
    g("checkout", "main")
    if merged then g("merge", "--no-ff", branch, "-m", "merge the branch")
    dir

  end mkRepo

  /**
   * 给分支挂真 worktree（路径在仓外，避免 untracked 互染）。先清残留（kept 场景
   * 的 wt 目录跨轮存活——测试夹具幂等性；否则 worktree add 撞 already exists 128）。
   */
  private def addWorktree(repo: os.Path, branch: String, wt: os.Path): os.Path =
    nebflow.ForceRemove.removeAll(wt)
    os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, branch)
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    wt

  private def rec(id: String, repo: os.Path, branch: String, worktree: Option[os.Path]): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = worktree.map(_.toString).getOrElse(repo.toString),
      project = None,
      taskId = Some("88"),
      parentSessionId = "nebula-root",
      title = "t",
      createdAt = 1L,
      branch = Some(branch),
      baseBranch = Some("main"),
      projectWorkspace = Some(repo.toString),
      worktree = worktree.map(_.toString)
    )

  private def cleanupRows(id: String): List[(String, String)] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == PostMergeCleanup.EntryKind).flatMap { e =>
      val c = e.payload.hcursor
      c.get[String]("delegateId")
        .toOption
        .filter(_ == id)
        .map(_ =>
          (
            c.get[String]("action").toOption.getOrElse(""),
            c.get[String]("detail").toOption.getOrElse("")
          )
        )
    }

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot / "chains")

  test("① merged scene (ahead==0, ancestor true) + worktree seat ⇒ worktree removed + branch -d + removed row") {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "clean-merged", branch = "delegate/pm-merged", merged = true)
      val wt = addWorktree(repo, "delegate/pm-merged", tempRoot / "wt-merged")
      val r = rec("delegate-pm-merged", repo, "delegate/pm-merged", Some(wt))
      val removed = PostMergeCleanup.run(Some(r)).unsafeRunSync()
      assert(removed, "a fully merged worktree scene must be removed")
      assert(!os.exists(wt), "the worktree directory must be gone (git worktree remove)")
      assert(
        !GitWorktrees.branchExists(repo, "delegate/pm-merged"),
        "the merged branch must be deleted (branch -d, unmerged refusal not needed here)"
      )
      val rows = cleanupRows("delegate-pm-merged")
      assertEquals(rows.map(_._1).lastOption, Some("removed"), s"the removed row must be audited: $rows")
  }

  test("② zero-ahead guard: branch still ahead ⇒ scene KEPT (worktree and branch intact), kept row names the guard") {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "clean-ahead", branch = "delegate/pm-ahead", merged = false)
      val wt = addWorktree(repo, "delegate/pm-ahead", tempRoot / "wt-ahead")
      val r = rec("delegate-pm-ahead", repo, "delegate/pm-ahead", Some(wt))
      val removed = PostMergeCleanup.run(Some(r)).unsafeRunSync()
      assert(!removed, "unmerged work must never be cleaned up")
      assert(os.exists(wt), "the worktree must be kept")
      assert(GitWorktrees.branchExists(repo, "delegate/pm-ahead"), "the branch must be kept")
      assert(GitWorktrees.commitsAhead(repo, "main", "delegate/pm-ahead") > 0, "fixture sanity: the branch is ahead")
      val rows = cleanupRows("delegate-pm-ahead")
      val (_, detail) = rows.lastOption.getOrElse(fail("a kept row must be audited"))
      assert(rows.lastOption.exists(_._1 == "kept"), s"action=kept: $rows")
      assert(detail.contains("zero-ahead guard"), s"the guard must be named in the row: $detail")
  }

  test("③ missing anchors ⇒ zero action (no worktree / no branch / no baseBranch): false, no row") {
    val repo = os.temp.dir(prefix = "pm-plain")
    val noWorktree = rec("delegate-pm-nowt", repo, "delegate/pm-nowt", worktree = None)
    assert(!PostMergeCleanup.run(Some(noWorktree)).unsafeRunSync(), "no worktree ⇒ nothing to clean")
    val noBranch = rec("delegate-pm-nobranch", repo, "delegate/pm-nobranch", worktree = Some(repo))
      .copy(branch = None)
    assert(!PostMergeCleanup.run(Some(noBranch)).unsafeRunSync(), "no branch anchor ⇒ zero action")
    val noBase = rec("delegate-pm-nobase", repo, "delegate/pm-nobase", worktree = Some(repo))
      .copy(baseBranch = None)
    assert(!PostMergeCleanup.run(Some(noBase)).unsafeRunSync(), "no baseBranch anchor ⇒ zero action")
    assertEquals(cleanupRows("delegate-pm-nowt"), Nil, "zero-action paths audit nothing")
  }

  test("④ unreadable base workspace (non-git) ⇒ fail-soft kept, never throws") {
    if !gitAvailable then ()
    else
      val notARepo = os.temp.dir(prefix = "pm-notrepo")
      val wt = os.temp.dir(prefix = "pm-notrepo-wt")
      val r = rec("delegate-pm-notrepo", notARepo, "delegate/pm-notrepo", Some(wt))
      val removed = PostMergeCleanup.run(Some(r)).unsafeRunSync()
      assert(!removed, "unreadable git face ⇒ keep the scene")
      assert(os.exists(wt), "the worktree must be kept on an unreadable base")
      val rows = cleanupRows("delegate-pm-notrepo")
      assert(rows.lastOption.exists(_._1 == "kept"), s"a kept row must be audited: $rows")
  }

  test("⑤ no record ⇒ false (no-op)") {
    assert(!PostMergeCleanup.run(None).unsafeRunSync(), "None record ⇒ false")
  }

  test(
    "⑥ rescue guard: merged scene with a report missing from the main workspace ⇒ rescued before removal, row carries the rescue summary"
  ) {
    if !gitAvailable then ()
    else
      val rel = os.rel / "reports" / "20261024_120000_rescued-fix" / "REPORT.md"
      val repo = mkRepo(
        tempRoot / "clean-rescue",
        branch = "delegate/pm-rescue",
        merged = true,
        extraFile = Some(rel -> "the rescued report body")
      )
      val wt = addWorktree(repo, "delegate/pm-rescue", tempRoot / "wt-rescue")
      // 「未并报告」的可测形态：文件两侧都被跟踪，主工作区工作树未提交删除
      // ⇒ 主工作区同相对路径缺失，守卫必须先抢救再移除。
      os.remove(repo / rel)
      assert(!os.exists(repo / rel), "fixture sanity: the report is missing from the main workspace")
      val r = rec("delegate-pm-rescue", repo, "delegate/pm-rescue", Some(wt))
      val removed = PostMergeCleanup.run(Some(r)).unsafeRunSync()
      assert(removed, "the rescue is additive: a git-clean merged scene must still be removed")
      assertEquals(os.read(repo / rel), "the rescued report body", "the report must be rescued into the main workspace")
      assert(!os.exists(wt), "the worktree is still gone after the rescue")
      val rows = cleanupRows("delegate-pm-rescue")
      val (_, detail) = rows.lastOption.getOrElse(fail("a cleanup row must be audited"))
      assert(detail.contains("rescue rescued=1"), s"the row must carry the rescue summary: $detail")
  }

end PostMergeCleanupSpec
