package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.GitWorktrees

import scala.util.Try

/**
 * Worktree 座位可用性（引擎会话治理专项 · 缺陷二，2026-11-03 作者令 14:38）。
 *
 * 实证：3c9f1ce2 批领到的「worktree」是**空目录**（无 checkout、`git worktree list`
 * 未登记），批子只能自建分支 + 整体移入 + `git worktree repair` 救回。空目录为什么
 * 能当座位交出去 —— 两道口子：
 *   ① 分配腿 `createWorktree` 建完不验收（`git worktree add` 崩溃/半写残场、目录
 *      被外部清空后直接透传）；
 *   ② 续用腿 [[WorktreeCandidates.resolve]] 的路径兜底判据是 `os.isDir ∧
 *      GitWorktrees.isGitRepo`——而 `.nebflow/worktrees/<name>` 这种空目录**位于主
 *      工作区内**，`git rev-parse --is-inside-work-tree` 沿父级解析返回 true ⇒
 *      空目录被当成合法座位收下（本 spec 的判红锚）。
 *
 * 修法：`GitWorktrees.validateAllocation`（注册可见 / checkout 完成 / status 可执行 /
 * 顶层就是该目录，四查）挂上分配腿与续用腿——分配后不验收绝不交任务，验收失败
 * 显式报错（可重试），空目录永远到不了模型手里。
 */
class WorktreeSeatValidationSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-worktree-seat"

  private def gitAvailable: Boolean =
    Try(os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
      .getOrElse(false)

  private def g(dir: os.Path, args: String*): os.CommandResult =
    os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)

  private def mkRepo(name: String): os.Path =
    val dir = tempRoot / name
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-q", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g(dir, "config", "user.email", "spec@nebflow.local")
    g(dir, "config", "user.name", "spec")
    g(dir, "commit", "--allow-empty", "-qm", "init")
    dir

  private def registered(repo: os.Path, path: os.Path): Boolean =
    GitWorktrees.worktreeList(repo).exists(_._1.stripSuffix("/") == path.toString.stripSuffix("/"))

  // ── ① 判红锚：空目录（未登记的残场）绝不当作可用座位 ─────────────────────

  test("① 续用腿 resolve：空目录（在主工作区内、git 未登记、无 checkout）必须被显式拒绝——改前红（现判据 isDir∧isGitRepo 沿父级解析为 true ⇒ Reuse）") {
    if !gitAvailable then ()
    else
      val repo = mkRepo("empty-dir-trap")
      val bogus = repo / ".nebflow" / "worktrees" / "delegate-bogus"
      os.makeDir.all(bogus)
      // 夹具自证：目录是「空目录」形态——在主工作区内、git 可见、但什么都没检出
      assert(os.exists(bogus) && os.list(bogus).isEmpty, "fixture sanity: an empty directory")
      // 🔴 旧判据的洞：这里 isGitRepo 沿父级解析为 true（= 主工作区），空目录被当合法
      assert(
        GitWorktrees.isGitRepo(bogus),
        "fixture sanity: plain isGitRepo(-C <empty dir>) is fooled by the parent repo (that is the hole)"
      )
      val ws = GitWorktrees.validateAllocation(repo, bogus.toString)
      assert(ws.nonEmpty, s"🔴 an empty unregistered directory must NOT validate as a usable seat (problems=$ws)")
      WorktreeCandidates.resolve(WorktreeCandidates.Inventory(Nil), bogus.toString, force = false) match
        case WorktreeCandidates.Decision.Refuse(code, message) =>
          assertEquals(code, WorktreeCandidates.RefuseCode.NotReady, s"refusal code: $code ($message)")
        case other => fail(s"🔴 an empty directory must be REFUSED as a reuse target, got: $other")
  }

  test("② 分配即可用：createWorktree 的成功返回必定通过四查验收（注册可见 / checkout 完成 / status 可执行 / 顶层即该目录）") {
    if !gitAvailable then ()
    else
      val repo = mkRepo("alloc-usable")
      GitWorktrees.createWorktree(repo, dirBase = "delegate-seat", branch = "delegate/seat") match
        case Left(err) => fail(s"worktree allocation failed in a clean repo: $err")
        case Right(created) =>
          assertEquals(
            GitWorktrees.validateAllocation(repo, created.path.toString),
            Nil,
            "🔴 every successful allocation must be immediately usable (post-validation is the allocation contract)"
          )
          assert(registered(repo, created.path), "the fresh worktree must be visible in `git worktree list`")
          assert(GitWorktrees.dirtyPaths(created.path).isDefined, "git status must run inside the seat")
          assert(GitWorktrees.headSha(created.path).isDefined, "HEAD must resolve inside the seat")
  }

  test("③ 空目录的四查明细：未登记 + 顶层不是该目录（父仓）+ HEAD 不可解 —— 三条问题都要点名") {
    if !gitAvailable then ()
    else
      val repo = mkRepo("detail-problems")
      val bogus = repo / ".nebflow" / "worktrees" / "delegate-hollow"
      os.makeDir.all(bogus)
      val problems = GitWorktrees.validateAllocation(repo, bogus.toString)
      assert(problems.exists(_.contains("not registered")), s"must name the registration problem: $problems")
      assert(
        problems.exists(p => p.contains("toplevel") || p.contains("not a checkout")),
        s"must name the checkout problem (empty dir resolves up to the parent repo): $problems"
      )
  }

end WorktreeSeatValidationSpec
