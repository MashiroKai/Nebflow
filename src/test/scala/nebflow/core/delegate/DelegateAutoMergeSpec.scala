package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.agent.SpecResources
import nebflow.core.GitWorktrees
import nebflow.core.project.{TaskLedgerHistory, TaskLedgerStore}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.util.Try

/**
 * DelegateAutoMerge —— 引擎原生 delegate worktree 自动合并（#524 · 2026-10-04
 * 裁定「合并节点引擎化」落地批）判读。真仓实测（对主仓零 git 写）：
 *
 *  - ① 零冲突自动合并（主路径②）：completed × 分支领先 × 基工作区干净 ⇒
 *    `--no-ff` 自动并回 main；落地提交双亲且第二亲 = 支头；ChainStore
 *    `auto-merge` 行 action=merged 携 mergeCommit；
 *  - ② 冲突挂起（主路径③）：同文件双改 ⇒ merge 失败 ⇒ `merge --abort` 原地
 *    复原（无 MERGE_HEAD、基内容/支头原样——**绝不强推**），行
 *    action=conflict-pending 携冲突文件清单，任务台账落挂起 note，尾注 CONFLICT；
 *  - ③ 幂等（主路径④）：重跑 = already-merged（目标 sha 不动）；批内已自行
 *    合并的分支（祖先真）首评即 already-merged——零重复合并；
 *  - ④ 开关关闭（主路径⑤）：`enabled=false` ⇒ 尾注 None、零审计行、零合并
 *    （回既有 P2-3 注记路径）；
 *  - ⑤ 拒并挂起：基工作区脏 ⇒ refused（fail-closed，基原样）；
 *  - ⑥ 目标可配：`targetBranch=release` ⇒ 并进 release，main 零触碰；
 *  - ⑦ FIFO 单飞行：同 projectKey 并发完成的两个 delegate 串行合并（双双落地、
 *    零 failed/refused 行——绝不并发写同一基工作区）；
 *  - ⑧ 生命周期挂点（内部腿接线）：DelegateTerminal.onCompleted 判词 completed
 *    ⇒ Release payload 携 auto-merge 尾注（delegate 终态承载面）。
 */
class DelegateAutoMergeSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-auto-merge"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "tasks")

  private val on: DelegateAutoMerge.Config = DelegateAutoMerge.Config(enabled = true, targetBranch = None)
  private val off: DelegateAutoMerge.Config = DelegateAutoMerge.Config(enabled = false, targetBranch = None)

  private def gitAvailable: Boolean =
    Try(os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
      .getOrElse(false)

  /**
   * main@init + delegate 分支交付一笔；conflict=true ⇒ main 上同文件再改（冲突形态）；
   * checkedOut="release" ⇒ 另建 release 分支并被出（目标覆盖形态）。
   */
  private def mkRepo(dir: os.Path, branch: String, conflict: Boolean = false, checkedOut: String = "main"): os.Path =
    os.remove.all(dir)
    os.makeDir.all(dir)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    if checkedOut != "main" then g("branch", checkedOut)
    g("checkout", "-b", branch)
    os.write(dir / "deliverable.txt", "branch version")
    g("add", ".")
    g("commit", "-m", "deliverable")
    g("checkout", if checkedOut != "main" then checkedOut else "main")
    if conflict then
      os.write(dir / "deliverable.txt", "main version")
      g("add", ".")
      g("commit", "-m", "main moves the same file")
    dir

  end mkRepo

  private def addWorktree(repo: os.Path, branch: String, wt: os.Path): os.Path =
    os.remove.all(wt)
    os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, branch)
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    wt

  private def rec(
    id: String,
    repo: os.Path,
    branch: String,
    worktree: Option[os.Path],
    taskId: Option[String] = None
  ): DelegateRegistry.Record =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = worktree.map(_.toString).getOrElse(repo.toString),
      project = None,
      taskId = taskId,
      parentSessionId = "nebula-root",
      title = "t",
      createdAt = 1L,
      branch = Some(branch),
      baseBranch = Some("main"),
      projectWorkspace = Some(repo.toString),
      worktree = worktree.map(_.toString)
    )

  private def hook(r: DelegateRegistry.Record, cfg: DelegateAutoMerge.Config): Option[String] =
    DelegateAutoMerge.onCompletedHook(Some(r), Some(cfg)).unsafeRunSync()

  private def autoMergeRows(id: String): List[(String, io.circe.Json)] =
    ChainStore
      .listSync()
      .flatMap(_.entries)
      .filter(_.kind == DelegateAutoMerge.EntryKind)
      .flatMap { e =>
        e.payload.hcursor.get[String]("delegateId").toOption.filter(_ == id).map(_ => e.payload)
      }
      .map(p => (p.hcursor.get[String]("action").toOption.getOrElse(""), p))

  private def headSha(repo: os.Path, ref: String): String =
    os.proc("git", "-C", repo.toString, "rev-parse", ref).call(check = true, stdout = os.Pipe).out.trim()

  private def parents(repo: os.Path, ref: String): List[String] =
    os.proc("git", "-C", repo.toString, "log", "-1", "--format=%P", ref)
      .call(check = true, stdout = os.Pipe)
      .out
      .trim()
      .split(' ')
      .toList
      .filter(_.nonEmpty)

  test(
    "① zero-conflict auto merge: --no-ff into main, two-parent commit, merged audit row with mergeCommit, suffix line"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-clean", branch = "delegate/am-clean")
      val wt = addWorktree(repo, "delegate/am-clean", tempRoot / "am-clean-wt")
      val r = rec("delegate-am-clean", repo, "delegate/am-clean", Some(wt))
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, on)
      val mainAfter = headSha(repo, "main")
      // 落地面：main 前进、双亲 merge commit、第二亲 = 支头（--no-ff 构造性证据）
      assert(mainAfter != mainBefore, "main must advance")
      val ps = parents(repo, "main")
      assertEquals(ps.size, 2, s"a --no-ff merge must be a two-parent commit: $ps")
      assertEquals(ps(1), headSha(repo, "delegate/am-clean"), "second parent must be the branch tip")
      assert(
        GitWorktrees.isAncestor(repo, "delegate/am-clean", "main").contains(true),
        "branch must be an ancestor of main"
      )
      // 审计面（⑥）：merged 行携 mergeCommit
      val rows = autoMergeRows("delegate-am-clean")
      val (_, payload) = rows.lastOption.getOrElse(fail("a merged audit row must exist"))
      assertEquals(rows.lastOption.map(_._1), Some("merged"), s"action=merged: $rows")
      assertEquals(
        payload.hcursor.get[String]("mergeCommit").toOption,
        Some(mainAfter),
        "the row must carry the merge commit"
      )
      // 终态尾注（delegate 终态承载面）
      assert(suffix.exists(_.contains("[auto-merge] branch merged into 'main'")), s"suffix: $suffix")
  }

  test(
    "② conflict ⇒ merge aborted cleanly (no MERGE_HEAD, base & branch intact), conflict-pending row with file list, ledger note, CONFLICT suffix"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-conflict", branch = "delegate/am-conflict", conflict = true)
      val wt = addWorktree(repo, "delegate/am-conflict", tempRoot / "am-conflict-wt")
      val tid =
        TaskLedgerStore.open().createSyncReturningId(title = "am-conflict-task").toOption.getOrElse(fail("ledger seed"))
      val r = rec("delegate-am-conflict", repo, "delegate/am-conflict", Some(wt), taskId = Some(tid))
      val mainBefore = headSha(repo, "main")
      val branchBefore = headSha(repo, "delegate/am-conflict")
      val baseContent = os.read(repo / "deliverable.txt")
      val suffix = hook(r, on)
      // 绝不强推：合并挂起、现场全保留
      assertEquals(headSha(repo, "main"), mainBefore, "main must NOT move on a conflict")
      assertEquals(headSha(repo, "delegate/am-conflict"), branchBefore, "the branch must be preserved")
      assertEquals(os.read(repo / "deliverable.txt"), baseContent, "base file content restored (abort)")
      val mergeHead = os
        .proc("git", "-C", repo.toString, "rev-parse", "-q", "--verify", "MERGE_HEAD")
        .call(check = false, stdout = os.Pipe, stderr = os.Pipe)
        .exitCode
      assertEquals(mergeHead, 1, "no MERGE_HEAD may survive (merge --abort)")
      assertEquals(GitWorktrees.currentBranch(repo), Some("main"), "base workspace stays on main")
      // 状态 + 冲突文件清单：审计行 + 任务台账 + 尾注三承载面
      val rows = autoMergeRows("delegate-am-conflict")
      val (_, payload) = rows.lastOption.getOrElse(fail("a conflict-pending row must exist"))
      assertEquals(rows.lastOption.map(_._1), Some("conflict-pending"), s"action=conflict-pending: $rows")
      assertEquals(
        payload.hcursor.get[List[String]]("conflictFiles").toOption,
        Some(List("deliverable.txt")),
        "the row must carry the conflict file list"
      )
      val notes = TaskLedgerHistory
        .open()
        .readFor(tid)
        .events
        .filter(e =>
          e.kind == TaskLedgerHistory.Kinds.Note && e.text.exists(_.contains("[auto-merge] conflict pending"))
        )
      assert(notes.nonEmpty, "the task ledger must carry the pending-adjudication note")
      assert(suffix.exists(_.contains("CONFLICT")), s"suffix: $suffix")
      assert(suffix.exists(_.contains("deliverable.txt")), s"suffix must name the conflicting file: $suffix")
      assert(suffix.exists(_.contains("PENDING")), s"suffix must state the pending adjudication: $suffix")
  }

  test(
    "③ idempotency: re-completion ⇒ already-merged (main sha untouched); a batch-self-merged branch is already-merged on FIRST evaluation"
  ) {
    if !gitAvailable then ()
    else
      // (a) 重试零重复合并
      val repo = mkRepo(tempRoot / "am-idem", branch = "delegate/am-idem")
      val wt = addWorktree(repo, "delegate/am-idem", tempRoot / "am-idem-wt")
      val r = rec("delegate-am-idem", repo, "delegate/am-idem", Some(wt))
      assert(hook(r, on).exists(_.contains("merged into 'main'")), "first evaluation merges")
      val afterFirst = headSha(repo, "main")
      val suffix2 = hook(r, on) // 重复完成 / 引擎重试
      assertEquals(headSha(repo, "main"), afterFirst, "a re-run must NOT create a second merge commit")
      assert(suffix2.exists(_.contains("already contains the branch head")), s"suffix: $suffix2")
      assertEquals(autoMergeRows("delegate-am-idem").lastOption.map(_._1), Some("already-merged"))
      // (b) 批内已自行合并的场景（分支已是 main 祖先）——首评即 already-merged
      val repo2 = mkRepo(tempRoot / "am-selfmerged", branch = "delegate/am-selfmerged")
      def g2(args: String*): os.CommandResult =
        os.proc(Seq("git", "-C", repo2.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      g2("merge", "--no-ff", "delegate/am-selfmerged", "-m", "batch self-merge (legacy in-batch behaviour)")
      val r2 = rec("delegate-am-selfmerged", repo2, "delegate/am-selfmerged", worktree = None)
      val mainBefore = headSha(repo2, "main")
      assert(hook(r2, on).exists(_.contains("already contains the branch head")), "self-merged ⇒ nothing to do")
      assertEquals(headSha(repo2, "main"), mainBefore, "self-merged scene must stay untouched")
  }

  test(
    "④ switch off (enabled=false) ⇒ None suffix, zero audit rows, zero merge — legacy P2-3 note path stays the owner"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-off", branch = "delegate/am-off")
      val wt = addWorktree(repo, "delegate/am-off", tempRoot / "am-off-wt")
      val r = rec("delegate-am-off", repo, "delegate/am-off", Some(wt))
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, off)
      assertEquals(suffix, None, "switch off ⇒ no suffix (caller falls back to the legacy note path)")
      assertEquals(headSha(repo, "main"), mainBefore, "switch off ⇒ zero merge")
      assertEquals(autoMergeRows("delegate-am-off"), Nil, "switch off ⇒ zero audit rows")
      assertEquals(GitWorktrees.commitsAhead(repo, "main", "delegate/am-off"), 1, "the branch stays ahead (unmerged)")
  }

  test("⑤ dirty base workspace ⇒ refused (fail-closed), base untouched, row action=refused") {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-dirty", branch = "delegate/am-dirty")
      os.write(repo / "uncommitted.txt", "half-done work") // untracked ⇒ porcelain 非空
      val r = rec("delegate-am-dirty", repo, "delegate/am-dirty", worktree = None)
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, on)
      assertEquals(headSha(repo, "main"), mainBefore, "a dirty base must never be merged over")
      assertEquals(autoMergeRows("delegate-am-dirty").lastOption.map(_._1), Some("refused"))
      assert(suffix.exists(_.contains("NOT merged")), s"suffix: $suffix")
      assert(suffix.exists(_.contains("uncommitted changes")), s"the refusal must name its reason: $suffix")
  }

  test("⑥ configurable target (targetBranch=release) ⇒ merges into release, main untouched") {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-target", branch = "delegate/am-target", checkedOut = "release")
      val wt = addWorktree(repo, "delegate/am-target", tempRoot / "am-target-wt")
      val r = rec("delegate-am-target", repo, "delegate/am-target", Some(wt))
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, DelegateAutoMerge.Config(enabled = true, targetBranch = Some("release")))
      assert(suffix.exists(_.contains("merged into 'release'")), s"suffix: $suffix")
      assertEquals(headSha(repo, "main"), mainBefore, "main must stay untouched when the target is overridden")
      assertEquals(parents(repo, "release").size, 2, "release must receive the two-parent merge")
  }

  test(
    "⑦ FIFO single flight: two concurrent completions on one project serialize — both land, zero failed/refused rows"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-fifo", branch = "delegate/am-fifo-a")
      def gf(args: String*): os.CommandResult =
        os.proc(Seq("git", "-C", repo.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      gf("branch", "delegate/am-fifo-b") // 第二支：同一 fork，改不同文件（互不冲突）
      gf("checkout", "delegate/am-fifo-b")
      os.write(repo / "other.txt", "b work")
      gf("add", ".")
      gf("commit", "-m", "b deliverable")
      gf("checkout", "main")
      val ra = rec("delegate-am-fifo-a", repo, "delegate/am-fifo-a", worktree = None)
      val rb = rec("delegate-am-fifo-b", repo, "delegate/am-fifo-b", worktree = None)
      val io = DelegateAutoMerge
        .onCompletedHook(Some(ra), Some(on))
        .both(
          DelegateAutoMerge.onCompletedHook(Some(rb), Some(on))
        )
      val (sa, sb) = io.unsafeRunSync()
      assert(sa.exists(_.contains("merged into 'main'")), s"a suffix: $sa")
      assert(sb.exists(_.contains("merged into 'main'")), s"b suffix: $sb")
      assert(GitWorktrees.isAncestor(repo, "delegate/am-fifo-a", "main").contains(true), "a merged")
      assert(GitWorktrees.isAncestor(repo, "delegate/am-fifo-b", "main").contains(true), "b merged")
      val actions = autoMergeRows("delegate-am-fifo-a").map(_._1) ++ autoMergeRows("delegate-am-fifo-b").map(_._1)
      assertEquals(
        actions.filter(a => a == "failed" || a == "refused"),
        Nil,
        s"serialization must leave no failure rows: $actions"
      )
      assertEquals(actions.count(_ == "merged"), 2, s"both merges audited: $actions")
  }

  // ── ⑧ 内部腿生命周期挂点（DelegateTerminal.onCompleted 接线） ────────────

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def noopRefs(system: ActorSystem): IO[(ActorRef[AgentCommand], ActorRef[AgentEvent])] =
    for
      child <- system.spawn(
        new Behavior[AgentCommand]:
          def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
            IO.pure(this)
        ,
        s"am-child-${scala.util.Random.nextInt(100000)}"
      )
      sup <- system.spawn(
        new Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] =
            IO.pure(this)
        ,
        s"am-sup-${scala.util.Random.nextInt(100000)}"
      )
    yield (child, sup)

  private def withJudgeAbsent[A](io: IO[A]): IO[A] =
    IO { DelegateJudge.DelegateJudgePort.clear() } *> io <* IO { DelegateJudge.DelegateJudgePort.clear() }

  test(
    "⑧ lifecycle hook (internal leg): onCompleted with an ahead branch ⇒ Release payload carries the auto-merge suffix, merge really lands"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-wiring", branch = "delegate/am-wiring")
      val wt = addWorktree(repo, "delegate/am-wiring", tempRoot / "am-wiring-wt")
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = "delegate-am-wiring",
          executor = "nebflow",
          cwd = wt.toString,
          project = None,
          taskId = Some("501"),
          parentSessionId = "nebula-root",
          title = "wiring",
          createdAt = 1L,
          branch = Some("delegate/am-wiring"),
          baseBranch = Some("main"),
          projectWorkspace = Some(repo.toString),
          worktree = Some(wt.toString)
        )
      )
      val system = ActorSystem(s"am-wiring-${scala.util.Random.nextInt(100000)}")
      val outcome = withJudgeAbsent {
        for
          res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
          (child, sup) <- noopRefs(system)
          oc <- nebflow.core.delegate.DelegateTerminal.onCompleted(
            resources = res,
            delegateId = "delegate-am-wiring",
            parentSessionId = "nebula-root",
            description = "wiring probe",
            initialPrompt = "do work",
            resultText = "work done, deliverable committed",
            childRef = child,
            supervisorRef = sup,
            sessionToolCalls = Some(3)
          )
        yield oc
      }.unsafeRunSync()
      outcome match
        case DelegateTerminal.Outcome.Release(suffix, _) =>
          assert(suffix.contains("[delegate-judge] verdict: completed"), s"verdict line intact: $suffix")
          assert(
            suffix.contains("[auto-merge] branch merged into 'main'"),
            s"auto-merge suffix in the terminal payload: $suffix"
          )
        case other => fail(s"expected Release, got: $other")
      val mainParents = parents(repo, "main")
      assertEquals(mainParents.size, 2, "the wiring test merge really landed (--no-ff two-parent)")
      assertEquals(autoMergeRows("delegate-am-wiring").lastOption.map(_._1), Some("merged"))
  }

end DelegateAutoMergeSpec
