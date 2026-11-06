package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.GitWorktrees

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/**
 * worktree 参数决定论 spec（delegate-param-decisions 批 2026-11-04 · 作者令
 * 「我想去掉由小llm来决策是否分配worktree」「不填worktree的任务是适合于只读型任务
 * 工作还是要默认创建worktree」）——临时真仓实测（GitWorktreesSpec 形态，对主仓
 * 零 git 写）。**验收第 1/2/3/4 条的机械判据都在本文件**：
 *
 *  - **W1 零分配**：`prepareInPlace`（= `worktree` 参数缺省）不建任何树——
 *    `<基工作区>/.nebflow/worktrees/` **前后目录列表逐项相同**；binding 的
 *    `worktree=None`、`seatSource=none`、brief 行明确「NO worktree was allocated」，
 *    且带派发时快照（收尾护栏的基线）。
 *  - **W2 按名建树**：`createNamed` 建 `<worktreeRoot>/<name>`，分支 `delegate/<name>`
 *    （作者令：填的内容就是 worktree 的 name），`seatSource=created`。
 *  - **W3 同名复用**：同名目录已被同一棵树占用时——解析面判定 exact-name 命中
 *    （复用，不新建）；即使有人硬走建树腿也**绝不产生第二个目录**（显式 Left）。
 *  - **W4 零模型调用**：装一个**计数判官面**（`DelegateJudgePort` 的唯一模型缝），
 *    跑完全部座位腿后计数 = 0——spawn 路径（座位分配 + 模式解析）没有模型调用点。
 *  - 非 git 工作区 = 就地腿 None（旧语义不回归）。
 */
class DelegateWorkspaceWorktreeLegSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-wt-leg"

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

  private def wtDirs(ws: os.Path): List[String] =
    val root = ws / ".nebflow" / "worktrees"
    if !os.exists(root) then Nil else os.list(root).map(_.last).toList.sorted

  /** 计数判官面：判定机只剩终态判词——座位腿跑完它必须**一次都没被问**。 */
  private def withCountingJudge[A](counter: AtomicInteger)(io: IO[A]): IO[A] =
    IO {
      DelegateJudge.DelegateJudgePort.install(
        new DelegateJudge.DelegateJudgePort.Face:
          def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
            IO { counter.incrementAndGet(); None }
      )
    } *> io <* IO(DelegateJudge.DelegateJudgePort.clear())

  test("W1 零分配：worktree 参数缺省 ⇒ 不建树，新建目录清单前后逐项相同 + brief/回执都写明未分配") {
    if !gitAvailable then () // 环境无 git = 跳过（CI 侧 git 前置同既有 MergeMutexGateSpec 先例）
    else
      val repo = tempRoot / "no-alloc-repo"
      mkRepo(repo)
      val id = "nebflow-noalloc1"
      val before = wtDirs(repo)
      val binding = DelegateWorkspace
        .prepareInPlace(repo.toString, id)
        .unsafeRunSync()
        .getOrElse(fail("a git workspace must still produce an in-place binding"))
      val after = wtDirs(repo)
      assertEquals(after, before, "zero allocation: the worktree root must list EXACTLY the same entries before and after")
      assertEquals(after, Nil, "a fresh repo has no worktree directory at all")
      assert(!os.exists(repo / ".nebflow" / "worktrees"), "no worktree root may even be created on the zero-allocation leg")
      assertEquals(binding.worktree, None, "no worktree in the binding")
      assertEquals(binding.cwd, repo.toString, "the seat is the project workspace itself")
      assertEquals(binding.seatSource, DelegateWorkspace.SeatSource.InPlace)
      assertEquals(binding.branch, s"delegate/$id")
      assertEquals(GitWorktrees.currentBranch(repo), Some("main"), "the shared HEAD stays on the base branch")
      assertEquals(GitWorktrees.branchExists(repo, s"delegate/$id"), true, "the in-place branch is still created")
      // 快照（收尾护栏的基线）：HEAD + 空脏集
      assertEquals(binding.snapshotSha, GitWorktrees.headSha(repo))
      assertEquals(binding.snapshotDirty, Some(Nil))
      val line = binding.briefLine.getOrElse(fail("the in-place brief line must exist"))
      assert(line.contains("NO worktree was allocated"), line)
      assert(line.contains("READ-ONLY channel"), line)
      assert(line.contains("GIT_INDEX_FILE"), line)
      val receipt = DelegateWorkspace.receiptLine(Some(binding), repo.toString)
      assert(receipt.contains("NONE ALLOCATED"), receipt)
      assert(receipt.contains("read-only channel"), receipt)
  }

  test("W2 按名建树：worktree=\"<name>\" ⇒ <worktreeRoot>/<name> 且分支 delegate/<name>，回执写『精确 name/新建』") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "named-repo"
      mkRepo(repo)
      val name = "liquid-glass-batch"
      val binding = DelegateWorkspace
        .createNamed(repo.toString, name, "nebflow-named1")
        .unsafeRunSync()
        .getOrElse(fail("creating a fresh name must succeed"))
      assertEquals(os.exists(repo / ".nebflow" / "worktrees" / name), true, "the directory is named EXACTLY as the parameter")
      assertEquals(wtDirs(repo), List(name), "and it is the only directory created")
      assertEquals(binding.branch, s"delegate/$name", "the branch follows the NAME (not the delegate id)")
      assertEquals(GitWorktrees.currentBranch(os.Path(binding.cwd)), Some(s"delegate/$name"))
      assertEquals(binding.worktree, Some((repo / ".nebflow" / "worktrees" / name).toString))
      assertEquals(binding.seatSource, DelegateWorkspace.SeatSource.Created)
      assertEquals(binding.seatName, Some(name))
      val receipt = DelegateWorkspace.receiptLine(Some(binding), repo.toString)
      assert(receipt.contains("created for name 'liquid-glass-batch'"), receipt)
  }

  test("W3 同名复用：同一 name 再派一次 ⇒ 复用既有树，不产生第二个目录") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "reuse-name-repo"
      mkRepo(repo)
      val name = "shared-name"
      val first = DelegateWorkspace.createNamed(repo.toString, name, "nebflow-reuse-a").unsafeRunSync().getOrElse(fail("first create"))
      // 解析面：同名 = exact-name 命中（复用），绝不落到 Create。
      val inv = WorktreeCandidates.inventory(repo.toString).unsafeRunSync()
      WorktreeCandidates.resolveNamed(inv, repo.toString, name, force = false) match
        case Right(WorktreeCandidates.SeatPlan.Reuse(cand, source)) =>
          assertEquals(source, DelegateWorkspace.SeatSource.ExactName, "the exact-name rule must be the one that hits")
          val second = DelegateWorkspace.reuse(repo.toString, "nebflow-reuse-b", cand, source).unsafeRunSync()
          assertEquals(second.worktree, first.worktree, "the SAME tree is reused")
          assertEquals(second.seatSource, DelegateWorkspace.SeatSource.ExactName)
          assertEquals(second.reusedFrom.isDefined, true)
          val receipt = DelegateWorkspace.receiptLine(Some(second), repo.toString)
          assert(receipt.contains("exact name 'shared-name'"), receipt)
        case other => fail(s"a second dispatch with the same name must resolve to an exact-name reuse, got $other")
      assertEquals(wtDirs(repo), List(name), "no second directory appears")
      // 兜底闸：即便有人硬调建树腿，也必须显式失败（绝不悄悄建第二棵）。
      assert(
        DelegateWorkspace.createNamed(repo.toString, name, "nebflow-reuse-c").unsafeRunSync().isLeft,
        "creating a name that already exists must fail explicitly"
      )
      assertEquals(wtDirs(repo), List(name), "still exactly one directory")
  }

  test("W4 零模型调用：装计数判官面，跑完零分配腿 + 按名建树腿 + 模式解析后计数仍为 0") {
    if !gitAvailable then ()
    else
      val repo = tempRoot / "zero-model-repo"
      mkRepo(repo)
      val counter = new AtomicInteger(0)
      val modes = withCountingJudge(counter)(
        IO.blocking {
          DelegateWorkspace.prepareInPlace(repo.toString, "nebflow-zm1").unsafeRunSync()
          val created = DelegateWorkspace.createNamed(repo.toString, "zm-name", "nebflow-zm2").unsafeRunSync()
          val reused = created.toOption.flatMap { b =>
            WorktreeCandidates.inventory(repo.toString).unsafeRunSync() match
              case inv =>
                WorktreeCandidates.resolveNamed(inv, repo.toString, "zm-name", force = false).toOption.collect {
                  case WorktreeCandidates.SeatPlan.Reuse(c, s) => DelegateWorkspace.reuse(repo.toString, "nebflow-zm3", c, s).unsafeRunSync()
                }
          }
          // 模式解析（同一条 spawn 路径）：不填 ⇒ kernel；显式 dispatcher ⇒ dispatcher；auto ⇒ 拒。
          val m1 = nebflow.core.delegate.ModeParam.resolve(None)
          val m2 = nebflow.core.delegate.ModeParam.resolve(Some("dispatcher"))
          val m3 = nebflow.core.delegate.ModeParam.resolve(Some("auto"))
          (reused, m1, m2, m3)
        }
      ).unsafeRunSync()
      assertEquals(counter.get(), 0, "the spawn path (seat allocation + mode resolution) must make ZERO model calls")
      assert(modes._1.isDefined, "the reuse leg also ran inside the same window")
      assertEquals(modes._2.map(_.mode), Right(DualModeConfig.ModeKernel))
      assertEquals(modes._3.map(_.mode), Right(DualModeConfig.ModeDispatcher))
      assert(modes._4.isLeft, "auto is rejected without any allocator call")
  }

  test("W5 非 git 工作区：就地腿 = None（旧语义不回归）") {
    val plain = os.temp.dir(prefix = "dwl-plain") // /var/folders 下，任何仓库之外
    val r = DelegateWorkspace.prepareInPlace(plain.toString, "nebflow-nogit").unsafeRunSync()
    assertEquals(r, None)
    assert(DelegateWorkspace.createNamed(plain.toString, "x", "nebflow-nogit2").unsafeRunSync().isLeft)
  }

end DelegateWorkspaceWorktreeLegSpec
