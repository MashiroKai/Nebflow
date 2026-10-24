package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.GitWorktrees

import scala.util.Try

/**
 * A5 步1（判否腿轻量 worktree + 四配套 · 评审 chain-n-247e206d §六 步1，作者
 * 2026-10-08 三项裁定「①②组合、①先②后」）判读——临时真仓实测（GitWorktreesSpec
 * 形态，对主仓零 git 写）：
 *
 *  - 红验① 判否（fake Face `decideWorktree=Some(false)`）⇒ prepare 仍有座位：
 *    `binding.worktree=Some` 且 `cwd ≠ workspace`（只读座位 = 同判是建权路径，
 *    仅 brief 行走只读变体）；
 *  - 红验② 机械强制力固化断言：该 worktree 内 `git checkout <基分支>` ⇒ 非零
 *    退出且报「already checked out / used by worktree」——座位在 worktree 内后
 *    「直改基分支」从可违约束变不可达形态（git 本体拒绝，同分支双检出禁令）；
 *  - 红验③ worktree 建败 ⇒ inPlaceBinding 回落（branch 有 / worktree None /
 *    brief 行含 GIT_INDEX_FILE 配方）——fail-open 合同不回归。建败 mock = 目录
 *    候选（`<base>`、`<base>-2`…`<base>-99`）99 个全占满（真仓无 hook，
 *    createWorktree 在碰 git 前即 Left）；
 *  - 判是腿对照：工作座位 brief 行（交付提交措辞 + merge 纪律行）逐字不回归；
 *  - 非 git 腿：prepare = None（既有语义）。
 *
 * （红验④ 零领先提交降级 = DelegateTerminalSpec 扩展例；红验⑤ = 既有断言 +
 * `sbt test` 全绿。）
 */
class DelegateWorkspaceWorktreeLegSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-wt-leg"

  private def gitAvailable: Boolean =
    Try(os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
      .getOrElse(false)

  private def mkRepo(dir: os.Path): Unit =
    os.remove.all(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)

  /** fake 判定机（钉死判词），用毕卸载（端口是全局注册面——测试间零残留）。 */
  private def withJudge[A](answer: Option[Boolean])(io: IO[A]): IO[A] =
    IO {
      DelegateJudge.DelegateJudgePort.install(
        new DelegateJudge.DelegateJudgePort.Face:
          def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(answer)
          def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] = IO.pure(None)
      )
    } *> io <* IO(DelegateJudge.DelegateJudgePort.clear())

  test(
    "红验① 判否腿: decideWorktree=Some(false) still creates branch + worktree (read-only seat); cwd = the seat, not the workspace"
  ) {
    if !gitAvailable then () // 环境无 git = 跳过（CI 侧 git 前置同既有 MergeMutexGateSpec 先例）
    else
      val repo = tempRoot / "no-leg-repo"
      mkRepo(repo)
      val id = "nebflow-ro1"
      val b = withJudge(Some(false))(
        DelegateWorkspace.prepare(repo.toString, "read-only inspection task", id)
      ).unsafeRunSync()
      val binding = b.getOrElse(fail("judged-no leg must still produce a binding (read-only seat), not None"))
      assertEquals(binding.branch, s"delegate/$id")
      val seat = binding.worktree.getOrElse(fail("judged-no leg must carry a worktree seat"))
      assertEquals(
        os.Path(seat).toString,
        (repo / ".nebflow" / "worktrees" / GitWorktrees.sanitizeRefName(id)).toString
      )
      assert(binding.cwd != repo.toString, "the delegate must NOT sit in the shared workspace any more")
      assertEquals(binding.cwd, seat, "cwd = the worktree seat")
      assertEquals(GitWorktrees.currentBranch(os.Path(seat)), Some(s"delegate/$id"))
      assertEquals(GitWorktrees.currentBranch(repo), Some("main"), "the shared HEAD stays on the base branch")
      assertEquals(binding.baseBranch, Some("main"))
      assertEquals(binding.baseWs, repo.toString)
      assertEquals(
        GitWorktrees.commitsAhead(repo, "main", binding.branch),
        0,
        "a fresh read-only seat has zero commits ahead"
      )
      // brief 行 = 只读座位变体（🔴 模型可见文案，§16 待过目——随批呈报件）
      val line = binding.briefLine.getOrElse(fail("the read-only seat brief line must exist"))
      assert(line.contains(s"[working branch] delegate/$id"), line)
      assert(line.contains("read-only seat"), line)
      assert(line.contains("do NOT create commits"), line)
      assert(line.contains("NEVER commit directly to main"), line)
  }

  test("红验② 机械强制力: git checkout of the base branch inside the seat is REFUSED by git itself (double-checkout ban)") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "force-repo"
      mkRepo(repo)
      val id = "nebflow-force1"
      val b = withJudge(Some(false))(
        DelegateWorkspace.prepare(repo.toString, "read-only task", id)
      ).unsafeRunSync()
      val seat = os.Path(b.getOrElse(fail("binding must exist")).worktree.getOrElse(fail("worktree seat must exist")))
      // 自然形态的直改 main 第一步（checkout）被 git 本体拒绝。LC_ALL=C 钉住
      // git 报文语言（本机 zh_CN locale 下 git 出中文报文——「已经被工作区使用」
      // 与英文 "already used by worktree" 同义；断言只认英文形态 ⇒ 钉 locale）。
      val res = os
        .proc("git", "-C", seat.toString, "checkout", "main")
        .call(check = false, mergeErrIntoOut = true, env = Map("LC_ALL" -> "C"))
      assert(res.exitCode != 0, s"checkout of the already-checked-out base branch must fail, got: ${res.out.trim()}")
      val msg = res.out.trim().toLowerCase
      assert(
        msg.contains("already checked out") || msg.contains("used by worktree"),
        s"the refusal text must name the double-checkout ban, got: ${res.out.trim()}"
      )
  }

  test(
    "红验③ worktree 建败 ⇒ inPlaceBinding 回落: branch set / worktree None / brief line carries the GIT_INDEX_FILE recipe (fail-open intact)"
  ) {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "fallback-repo"
      mkRepo(repo)
      val id = "exhaust"
      // 建败 mock（真仓无 hook）：候选目录 <base>, <base>-2 … <base>-99 全部占满
      // ⇒ freeName=None ⇒ createWorktree 在 git 之前即 Left ⇒ 回落腿。
      val nebflowDir = repo / ".nebflow"
      os.makeDir.all(nebflowDir)
      (1 to 99).foreach { i =>
        val n = if i == 1 then id else s"$id-$i"
        os.makeDir.all(nebflowDir / n)
      }
      val b = withJudge(Some(true))(
        DelegateWorkspace.prepare(repo.toString, "task", id)
      ).unsafeRunSync()
      val binding =
        b.getOrElse(fail("fallback must still produce a binding — dispatch is never rejected on a git-face failure"))
      assertEquals(binding.worktree, None, "worktree creation failed ⇒ no worktree in the binding")
      assertEquals(binding.branch, s"delegate/$id", "the in-place branch is still created")
      assertEquals(binding.cwd, repo.toString, "the fallback seat = the original workspace")
      assert(GitWorktrees.branchExists(repo, s"delegate/$id"), "the fallback branch ref must exist")
      assertEquals(GitWorktrees.currentBranch(repo), Some("main"), "shared HEAD untouched by the fallback")
      assert(binding.briefLine.exists(_.contains("GIT_INDEX_FILE")), binding.briefLine.getOrElse("missing brief line"))
  }

  test(
    "判是腿对照: decideWorktree=Some(true) — worktree seat with the deliverable-commit brief line unchanged (word-for-word leg)"
  ) {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "yes-leg-repo"
      mkRepo(repo)
      val id = "nebflow-yes1"
      val b = withJudge(Some(true))(
        DelegateWorkspace.prepare(repo.toString, "implement the fix", id)
      ).unsafeRunSync()
      val binding = b.getOrElse(fail("judged-yes leg must produce a binding"))
      assertEquals(binding.worktree.isDefined, true)
      assertEquals(binding.cwd, binding.worktree.getOrElse(fail("seat")))
      val line = binding.briefLine.getOrElse(fail("brief line"))
      assert(line.contains("commit your deliverable commits here"), line)
      assert(line.contains("delegate_report(category=\"merge\""), line)
      assert(!line.contains("read-only seat"), "the working seat must NOT carry the read-only variant")
  }

  test("非 git 腿: prepare = None (legacy semantics intact)") {
    val plain = os.temp.dir(prefix = "dwl-plain") // /var/folders 下，任何仓库之外
    val r = DelegateWorkspace.prepare(plain.toString, "task", "nebflow-nogit").unsafeRunSync()
    assertEquals(r, None)
  }

end DelegateWorkspaceWorktreeLegSpec
