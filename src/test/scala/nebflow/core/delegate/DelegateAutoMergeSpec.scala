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
 *
 * 合并静默搁浅修复批（2026-10-23 作者令三腿；**同日裁定纠正：合并不自动化，
 * delegate 自己负责合并，平台只做排队/通知——零代跑**）：
 *  - ⑨ 腿① 脏树拒合并 ⇒ 双通知到达（delegate 本人可行动诊断 + 父会话知会一
 *    行）；同脏重评 = correlationId 稳定（持久队列幂等键不漂移）；
 *  - ⑩ delegate 清障后自行重试成功：清障后平台零动作（main 不动），delegate
 *    自己的重评拍才落地——平台零代跑；
 *  - ⑪ 腿③ evidence/、reports/ 生成物覆写 ⇒ 自动还原 HEAD + 还原前审计行
 *    （路径+diff 摘要），树转洁后合并落地；开关 off = 不还原照旧拒；
 *  - ⑫ 源码路径脏文件 ⇒ 绝不还原（内容原样），refused + dirtyPaths 记录。
 */
class DelegateAutoMergeSpec extends FunSuite:

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-auto-merge"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    nebflow.ForceRemove.removeAll(tempRoot / "chains")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "tasks")

  private val on: DelegateAutoMerge.Config =
    DelegateAutoMerge.Config(enabled = true, targetBranch = None, restoreArtifacts = true, absorbArtifacts = true)
  private val off: DelegateAutoMerge.Config =
    DelegateAutoMerge.Config(enabled = false, targetBranch = None, restoreArtifacts = true, absorbArtifacts = true)

  private def gitAvailable: Boolean =
    Try(os.proc("git", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0)
      .getOrElse(false)

  /**
   * main@init + delegate 分支交付一笔；conflict=true ⇒ main 上同文件再改（冲突形态）；
   * checkedOut="release" ⇒ 另建 release 分支并被出（目标覆盖形态）。
   */
  private def mkRepo(dir: os.Path, branch: String, conflict: Boolean = false, checkedOut: String = "main"): os.Path =
    nebflow.ForceRemove.removeAll(dir)
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
    nebflow.ForceRemove.removeAll(wt)
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
      val suffix = hook(r, DelegateAutoMerge.Config(enabled = true, targetBranch = Some("release"), restoreArtifacts = true, absorbArtifacts = true))
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

  // ── 合并静默搁浅修复批（2026-10-23 作者令三腿；同日裁定纠正：合并不自动化，
  //    delegate 自己负责合并，平台只做排队/通知——零代跑） ─────────────────────

  private final class RecordingFace:
    private val lock = new Object
    private val buf =
      scala.collection.mutable.ListBuffer.empty[(String, String, AgentCommand.ExternalEvent, AgentCommand.ExternalEvent)]
    val face: MergeRefusalCare.EscalationPort.Face = (delegateId, parentSessionId, delegateEvent, parentEvent) =>
      IO.blocking(lock.synchronized(buf += ((delegateId, parentSessionId, delegateEvent, parentEvent)))).void
    def all: List[(String, String, AgentCommand.ExternalEvent, AgentCommand.ExternalEvent)] =
      lock.synchronized(buf.toList)

  private def installPort(rf: RecordingFace): Unit = MergeRefusalCare.EscalationPort.install(rf.face)

  test(
    "⑨ leg1 dirty-tree refusal ⇒ BOTH notifications arrive (delegate actionable diagnosis + parent one-line FYI); correlationId stable across same-dirt re-refusals"
  ) {
    if !gitAvailable then ()
    else
      val rf = RecordingFace()
      installPort(rf)
      try
        val repo = mkRepo(tempRoot / "am-esc", branch = "delegate/am-esc")
        os.write(repo / "half-done.txt", "human work in progress") // 非还原面的脏（untracked 源文件）
        val r = rec("delegate-am-esc", repo, "delegate/am-esc", worktree = None)
        val mainBefore = headSha(repo, "main")
        val suffix = hook(r, on)
        assertEquals(headSha(repo, "main"), mainBefore, "refusal must not move main")
        assertEquals(autoMergeRows("delegate-am-esc").lastOption.map(_._1), Some("refused"))
        assert(suffix.exists(_.contains("NOT merged")), s"suffix: $suffix")
        // 双通知到达：delegate 主通知 + 父会话知会一行
        val got = rf.all
        assertEquals(got.size, 1, s"exactly one dual-notification on first refusal: ${got.size}")
        val (delegateId, parentSid, ev, pev) = got.head
        assertEquals(delegateId, "delegate-am-esc")
        assertEquals(parentSid, "nebula-root")
        // delegate 主通知：可行动诊断（路径、清法、责任归属——平台零代跑）
        assertEquals(ev.source, "auto-merge")
        assertEquals(ev.eventType, "failed", "failed-shaped event (existing event machinery treats it uniformly)")
        assert(
          ev.correlationId.exists(_.startsWith(s"${MergeRefusalCare.CorrelationPrefix}-delegate-am-esc")),
          s"correlationId must carry the idempotency family: ${ev.correlationId}"
        )
        assert(ev.payload.contains("half-done.txt"), "delegate payload names the dirty path (actionable)")
        assert(ev.payload.contains("Your move"), "delegate payload assigns the action to the delegate itself")
        assert(ev.payload.contains("clean the dirty paths"), "delegate payload carries the how-to-clean guidance")
        assert(!ev.payload.toLowerCase.contains("engine retries"), "platform must NOT promise proxy retries (zero proxy-run)")
        // 父会话知会：一行、责任在 delegate、无需行动
        assert(pev.correlationId.exists(_.startsWith(MergeRefusalCare.ParentCorrelationPrefix)), s"parent cid: ${pev.correlationId}")
        assert(pev.payload.contains("delegate-am-esc".take(8)), "parent FYI names the delegate")
        assert(pev.payload.contains("no action needed"), "parent FYI states no action needed")
        assert(pev.payload.length < ev.payload.length, "parent line must stay a one-liner (diagnosis lives with the delegate)")
        // 同脏重评：correlationId 稳定（持久注入队列靠它挡重；诊断不漂移）
        hook(r, on)
        val got2 = rf.all
        assertEquals(got2.size, 2, "the re-evaluation notifies the live channel again…")
        assertEquals(got2(1)._3.correlationId, got(0)._3.correlationId, "…but with the SAME correlationId — the persistent queue dedups it")
      finally MergeRefusalCare.EscalationPort.clear()
  }

  test(
    "⑩ delegate clears the obstacle then retries ITSELF ⇒ merge lands on the delegate's own re-evaluation; platform takes zero proxy action in between"
  ) {
    if !gitAvailable then ()
    else
      val rf = RecordingFace()
      installPort(rf)
      try
        val repo = mkRepo(tempRoot / "am-retry", branch = "delegate/am-retry")
        os.write(repo / "uncommitted.txt", "dirt") // 脏：拒绝（untracked 非还原面）
        val r = rec("delegate-am-retry", repo, "delegate/am-retry", worktree = None)
        val s1 = hook(r, on)
        assertEquals(autoMergeRows("delegate-am-retry").lastOption.map(_._1), Some("refused"), s"s1: $s1")
        assert(rf.all.nonEmpty, "the delegate was notified of the refusal")
        // ── delegate 自己清障（它自己的一拍：清理基工作区脏文件）──
        os.remove(repo / "uncommitted.txt")
        // 平台零代跑：清障后、delegate 重试前，main 必须原地不动（无任何代跑腿）
        val mainAfterClean = headSha(repo, "main")
        val rowsAfterClean = autoMergeRows("delegate-am-retry").map(_._1)
        assertEquals(rowsAfterClean.count(_ == "merged"), 0, s"platform must not merge on its own: $rowsAfterClean")
        assertEquals(rowsAfterClean.count(_ == "already-merged"), 0, s"no hidden re-evaluation may run: $rowsAfterClean")
        // ── delegate 自己的重试拍（重评终态）⇒ 合并落地 ──
        val s2 = hook(r, on)
        assert(s2.exists(_.contains("merged into 'main'")), s"delegate's own retry lands: $s2")
        assert(GitWorktrees.isAncestor(repo, "delegate/am-retry", "main").contains(true), "the branch is merged")
        assertEquals(
          autoMergeRows("delegate-am-retry").count(_._1 == "merged"),
          1,
          "exactly one merge — run at the delegate's own re-evaluation, never by the platform"
        )
        val mainNow = headSha(repo, "main")
        assert(mainNow != mainAfterClean, "main advanced only at the delegate's own retry beat")
      finally MergeRefusalCare.EscalationPort.clear()
  }

  test(
    "⑪ leg3 evidence/+reports/ artifact overwrite ⇒ auto-restored to HEAD with a PRE-restore audit row (path+diff), merge then lands"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-restore", branch = "delegate/am-restore")
      def g(args: String*): os.CommandResult =
        os.proc(Seq("git", "-C", repo.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      g("checkout", "main")
      os.makeDir.all(repo / "evidence")
      os.makeDir.all(repo / "reports")
      os.write(repo / "evidence" / "pic.png", "PNGDATA-V1")
      os.write(repo / "reports" / "note.md", "generated v1\n")
      g("add", ".")
      g("commit", "-m", "artifacts v1")
      // 模拟测试覆写生成物（未提交修改，非人写代码）
      os.write.over(repo / "evidence" / "pic.png", "PNGDATA-TEST-RUN-OVERWRITE")
      os.write.over(repo / "reports" / "note.md", "overwritten by the test run\n")
      val r = rec("delegate-am-restore", repo, "delegate/am-restore", worktree = None)
      val suffix = hook(r, on)
      // 还原落地：内容回 HEAD、树转洁、合并照常落地
      assertEquals(os.read(repo / "evidence" / "pic.png"), "PNGDATA-V1", "evidence artifact restored to HEAD")
      assertEquals(os.read(repo / "reports" / "note.md"), "generated v1\n", "reports artifact restored to HEAD")
      assert(suffix.exists(_.contains("merged into 'main'")), s"tree de-clogged ⇒ merge lands: $suffix")
      assertEquals(parents(repo, "main").size, 2, "the --no-ff merge landed after the restore")
      // 审计面：还原前审计行（路径 + diff 摘要在案）
      val actions = autoMergeRows("delegate-am-restore").map(_._1)
      assert(actions.contains("artifact-restore"), s"an artifact-restore audit row must exist: $actions")
      val (_, payload) = autoMergeRows("delegate-am-restore").find(_._1 == "artifact-restore").getOrElse(fail("restore row"))
      val entries = payload.hcursor.downField("plannedRestores").values.map(_.toList).getOrElse(Nil)
      val paths = entries.map(_.hcursor.get[String]("path").toOption.getOrElse(""))
      assert(paths.contains("evidence/pic.png"), s"restore row must name evidence/pic.png: $paths")
      assert(paths.contains("reports/note.md"), s"restore row must name reports/note.md: $paths")
      val diffs = entries.map(_.hcursor.get[String]("diff").toOption.getOrElse(""))
      assert(diffs.nonEmpty && diffs.forall(_.nonEmpty), s"each restore entry carries a diff summary: $diffs")
      assertEquals(actions.lastOption, Some("merged"), "the merge row closes the evaluation")
  }

  test(
    "⑪b restoreArtifacts=false ⇒ no restore (dirty stays dirty), refused — the kill-switch returns the legacy semantics"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-restore-off", branch = "delegate/am-restore-off")
      def g(args: String*): os.CommandResult =
        os.proc(Seq("git", "-C", repo.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      g("checkout", "main")
      os.makeDir.all(repo / "evidence")
      os.write(repo / "evidence" / "pic.png", "PNGDATA-V1")
      g("add", ".")
      g("commit", "-m", "artifacts v1")
      os.write.over(repo / "evidence" / "pic.png", "PNGDATA-TEST-RUN-OVERWRITE")
      val r = rec("delegate-am-restore-off", repo, "delegate/am-restore-off", worktree = None)
      val cfg = DelegateAutoMerge.Config(enabled = true, targetBranch = None, restoreArtifacts = false, absorbArtifacts = true)
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, cfg)
      assertEquals(os.read(repo / "evidence" / "pic.png"), "PNGDATA-TEST-RUN-OVERWRITE", "switch off ⇒ no restore")
      assertEquals(headSha(repo, "main"), mainBefore, "switch off ⇒ refused, zero merge")
      assertEquals(autoMergeRows("delegate-am-restore-off").lastOption.map(_._1), Some("refused"))
      assertEquals(
        autoMergeRows("delegate-am-restore-off").exists(_._1 == "artifact-restore"),
        false,
        "switch off ⇒ no restore audit row"
      )
      assert(suffix.exists(_.contains("NOT merged")), s"suffix: $suffix")
  }

  test(
    "⑫ source-path dirt is NEVER auto-restored ⇒ refused with the file intact and dirtyPaths recorded"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-src", branch = "delegate/am-src")
      def g(args: String*): os.CommandResult =
        os.proc(Seq("git", "-C", repo.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      g("checkout", "main")
      os.makeDir.all(repo / "src")
      os.write(repo / "src" / "Main.scala", "object Main // original")
      g("add", ".")
      g("commit", "-m", "source")
      os.write.over(repo / "src" / "Main.scala", "object Main // half-done human edit") // 源码脏——还原禁区
      val r = rec("delegate-am-src", repo, "delegate/am-src", worktree = None)
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, on)
      assertEquals(
        os.read(repo / "src" / "Main.scala"),
        "object Main // half-done human edit",
        "source files must NEVER be auto-restored — human work is untouchable"
      )
      assertEquals(headSha(repo, "main"), mainBefore, "refused ⇒ main untouched")
      assertEquals(autoMergeRows("delegate-am-src").lastOption.map(_._1), Some("refused"))
      assertEquals(autoMergeRows("delegate-am-src").exists(_._1 == "artifact-restore"), false, "no restore row for source paths")
      val (_, payload) = autoMergeRows("delegate-am-src").lastOption.getOrElse(fail("refused row"))
      val dirty = payload.hcursor.get[List[String]]("dirtyPaths").toOption.getOrElse(Nil)
      assert(dirty.contains("src/Main.scala"), s"dirtyPaths must record the source path: $dirty")
      assert(suffix.exists(_.contains("src/Main.scala")), s"suffix must carry the actionable path: $suffix")
  }

  // ── 腿⑤ 工件处置兑现·收编（merge-queue 滞留修复批 2026-10-25 作者令腿①） ──

  test(
    "⑬ untracked reports/evidence artifacts ⇒ auto-ABSORBED into the merge batch (add+commit), tree de-clogged, merge lands; artifact-absorb row carries paths+commit"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-absorb", branch = "delegate/am-absorb")
      val wt = addWorktree(repo, "delegate/am-absorb", tempRoot / "am-absorb-wt")
      // delegate 分支携带 reports/ 交付物（合并时会撞上主树同名未跟踪目录——
      // 10-25 实证病灶：未跟踪工件挡死合并队列）
      def gw(args: String*): os.CommandResult =
        os.proc(Seq("git", "-C", wt.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      os.makeDir.all(wt / "reports" / "20261025_panels-mobile-ux")
      os.write(wt / "reports" / "20261025_panels-mobile-ux" / "REPORT.md", "# ux batch report\n")
      gw("add", ".")
      gw("commit", "-m", "report deliverable")
      // 主树同路径未跟踪工件（测试/任务在主树落报告——收编对象）
      os.makeDir.all(repo / "reports" / "20261023_182241_panels-mobile-ux")
      os.write(repo / "reports" / "20261023_182241_panels-mobile-ux" / "old.md", "# older report\n")
      val r = rec("delegate-am-absorb", repo, "delegate/am-absorb", Some(wt))
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, on)
      // 收编落地：报告文件入册（内容保留）、树转洁、合并照常落地
      assert(os.exists(repo / "reports" / "20261023_182241_panels-mobile-ux" / "old.md"), "absorbed artifact is committed, not deleted")
      assert(suffix.exists(_.contains("merged into 'main'")), s"tree de-clogged ⇒ merge lands: $suffix")
      assertEquals(parents(repo, "main").size, 2, "the --no-ff merge landed after absorption")
      assert(headSha(repo, "main") != mainBefore, "main advanced")
      // 审计面：artifact-absorb 行（路径 + 收编提交）
      val actions = autoMergeRows("delegate-am-absorb").map(_._1)
      assert(actions.contains("artifact-absorb"), s"an artifact-absorb audit row must exist: $actions")
      val (_, payload) = autoMergeRows("delegate-am-absorb").find(_._1 == "artifact-absorb").getOrElse(fail("absorb row"))
      val paths = payload.hcursor.get[List[String]]("absorbedPaths").toOption.getOrElse(Nil)
      assert(paths.exists(_.startsWith("reports/")), s"absorb row must name the reports/ path: $paths")
      assert(payload.hcursor.get[String]("absorbCommit").toOption.exists(_.nonEmpty), "absorb row carries the chore commit sha")
      assertEquals(actions.lastOption, Some("merged"), "the merge row closes the evaluation")
  }

  test(
    "⑬b absorbArtifacts=false ⇒ untracked artifacts stay untracked, refused — the kill-switch returns the pre-batch semantics"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-absorb-off", branch = "delegate/am-absorb-off")
      os.makeDir.all(repo / "reports" / "stray-batch")
      os.write(repo / "reports" / "stray-batch" / "note.md", "generated\n")
      val r = rec("delegate-am-absorb-off", repo, "delegate/am-absorb-off", worktree = None)
      val cfg = DelegateAutoMerge.Config(enabled = true, targetBranch = None, restoreArtifacts = true, absorbArtifacts = false)
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, cfg)
      assertEquals(
        os.read(repo / "reports" / "stray-batch" / "note.md"),
        "generated\n",
        "switch off ⇒ the untracked artifact stays untracked (untouched)"
      )
      assertEquals(headSha(repo, "main"), mainBefore, "switch off ⇒ refused, zero merge")
      assertEquals(autoMergeRows("delegate-am-absorb-off").lastOption.map(_._1), Some("refused"))
      assertEquals(
        autoMergeRows("delegate-am-absorb-off").exists(_._1 == "artifact-absorb"),
        false,
        "switch off ⇒ no absorb audit row"
      )
      assert(suffix.exists(_.contains("NOT merged")), s"suffix: $suffix")
  }

  test(
    "⑬c mixed dirt (untracked reports/ + untracked source) ⇒ only the whitelist is absorbed, source stays, refusal is fail-closed with remaining paths"
  ) {
    if !gitAvailable then ()
    else
      val repo = mkRepo(tempRoot / "am-absorb-mixed", branch = "delegate/am-absorb-mixed")
      os.makeDir.all(repo / "reports" / "gen")
      os.write(repo / "reports" / "gen" / "note.md", "generated\n")
      os.makeDir.all(repo / "src")
      os.write(repo / "src" / "HalfDone.scala", "// human work in progress")
      val r = rec("delegate-am-absorb-mixed", repo, "delegate/am-absorb-mixed", worktree = None)
      val mainBefore = headSha(repo, "main")
      val suffix = hook(r, on)
      // 白名单收编照常（生成工件入册——一笔 chore 提交先落 main）；源码原样滞留（fail-closed 禁区）
      assertEquals(os.read(repo / "src" / "HalfDone.scala"), "// human work in progress", "source files are NEVER touched")
      assert(suffix.exists(_.contains("NOT merged")), s"still refused over the source dirt: $suffix")
      // porcelain 将全未跟踪目录折叠为 `src/` 形——拒绝面携目录路径（既有 dirtyPaths 语义）
      assert(suffix.exists(_.contains("src/")), s"the refusal must name the remaining path: $suffix")
      // 收编提交已让 main 前进（chore 一笔、单亲——合并本身仍被拒）
      assert(headSha(repo, "main") != mainBefore, "the absorb commit advanced main")
      assertEquals(parents(repo, "main").size, 1, "refused ⇒ NO merge commit on main")
      val tracked = os
        .proc("git", "-C", repo.toString, "ls-files", "reports/gen/note.md")
        .call(check = true, stdout = os.Pipe)
        .out
        .trim()
      assertEquals(tracked, "reports/gen/note.md", "the absorbed artifact is committed (tracked), not deleted")
      val (_, payload) = autoMergeRows("delegate-am-absorb-mixed").lastOption.getOrElse(fail("refused row"))
      assertEquals(payload.hcursor.get[String]("action").toOption, Some("refused"))
      val dirty = payload.hcursor.get[List[String]]("dirtyPaths").toOption.getOrElse(Nil)
      assert(dirty.contains("src/"), s"dirtyPaths must carry the remaining source path: $dirty")
  }

end DelegateAutoMergeSpec
