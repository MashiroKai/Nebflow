package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * delegate-finish-report 修复批（2026-10-25 · 作者令：「delegate report merge 这次是
 * 比较详细的汇报，而这次汇报的内容不会回传，而是汇报 delegate report finish……
 * 导致工作结果丢失」）判读——finish 回传必含工作结果：
 *
 *  - ① 根因① 停靠保真：judge ready-to-merge ⇒ HoldForMerge 时工作轮终稿进
 *    `DelegateRegistry.workReport`（first-write-wins）；
 *  - ② 根因② 留底覆写：`updateReport` 单槽覆写（merge→finish）时旧 detail 留底
 *    进 workReport；已保真槽（停靠腿）不被顶掉；同 detail 重报零留底；
 *  - ③ 根因③ 附页回传：合并轮 Release 尾注携三段附页——merge status 一行（合并
 *    信息降为一行状态）+ work result 保真段 + REPORT.md 路径与内容摘要
 *    （真仓真并 e2e——第一亲 diff 的 basename=REPORT.md 判据）；
 *  - ④ 向后兼容：老 delegate（无保真、无留底、diff 无报告文件）⇒ 附页整段缺席，
 *    Release 尾注逐字现状（仅判词行）；
 *  - ⑤ 纯组装锚：appendixFor 空输入 = ""；workReport 截断边界（Bound.WorkChars）。
 */
class DelegateFinishWorkResultSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-finish-report"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  nebflow.ForceRemove.removeAll(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    nebflow.ForceRemove.removeAll(tempRoot / "chains")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit = MergeQueue.reset()

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def seedRecord(
    id: String,
    taskId: Option[String],
    branch: Option[String],
    cwd: os.Path,
    workReport: Option[String] = None
  ): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = cwd.toString,
        project = None,
        taskId = taskId,
        parentSessionId = "nebula-root",
        title = "seeded",
        createdAt = 1L,
        branch = branch,
        baseBranch = Some("main"),
        projectWorkspace = Some(cwd.toString),
        workReport = workReport
      )
    )

  /**
   * 真仓夹具：main@init；delegate 分支领先 1 笔（交付物 + 可选 REPORT.md）；
   * HEAD 回 main（可并形态——目标分支必须是检出分支）。
   */
  private def mkRepo(
    dir: os.Path,
    branch: String,
    withReport: Boolean
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
    os.write(dir / "deliverable.txt", "deliverable body")
    if withReport then
      os.makeDir.all(dir / "reports" / "20261025_120000_delegate-finish-fix")
      os.write(
        dir / "reports" / "20261025_120000_delegate-finish-fix" / "REPORT.md",
        "REPORT HEADING LINE\nroot cause: line two\n"
      )
    g("add", ".")
    g("commit", "-m", "delegate deliverable")
    g("checkout", "main")
    dir

  end mkRepo

  /** 真并（机械验收闸判据：双亲 merge ＋ 第二亲 = 支头）。 */
  private def mergeNoFF(repo: os.Path, branch: String): Unit =
    os.proc("git", "-C", repo.toString, "merge", "--no-ff", branch, "-m", "merge round")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)

  private def noopRefs(system: ActorSystem): IO[(ActorRef[AgentCommand], ActorRef[AgentEvent])] =
    for
      child <- system.spawn(
        new Behavior[AgentCommand]:
          def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] =
            IO.pure(this)
        ,
        s"child-${scala.util.Random.nextInt(100000)}"
      )
      sup <- system.spawn(
        new Behavior[AgentEvent]:
          def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] =
            IO.pure(this)
        ,
        s"sup-${scala.util.Random.nextInt(100000)}"
      )
    yield (child, sup)

  /** fake 判定机（钉死判词），用毕卸载——DelegateTerminalSpec 同款装配面。 */
  private def withJudge[A](verdict: DelegateJudge.Verdict)(io: IO[A]): IO[A] =
    IO {
      DelegateJudge.DelegateJudgePort.install(
        new DelegateJudge.DelegateJudgePort.Face:
          def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(Some(false))
          def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] = IO.pure(Some(verdict))
      )
    } *> io <* IO(DelegateJudge.DelegateJudgePort.clear())

  /** 判定机缺席（回落链——自申报 finish ⇒ completed）。 */
  private def withJudgeAbsent[A](io: IO[A]): IO[A] =
    IO { DelegateJudge.DelegateJudgePort.clear() } *> io <* IO { DelegateJudge.DelegateJudgePort.clear() }

  /** 合并轮在飞夹具：入账（空队列 pump 立即放行通知——mergeRound 判据与生产同源）。 */
  private def enqueueRound(
    id: String,
    branch: String,
    repoPath: os.Path,
    taskId: String,
    child: ActorRef[AgentCommand],
    sup: ActorRef[AgentEvent]
  ): Unit =
    MergeQueue.enqueue(
      MergeQueue.Member(
        delegateId = id,
        branch = branch,
        baseWs = repoPath.toString,
        baseBranch = "main",
        worktree = false,
        projectKey = s"p-$id",
        taskId = Some(taskId),
        parentSessionId = "nebula-root",
        description = "t",
        readyAt = 1L,
        channelKind = "internal",
        internal = Some(MergeQueue.InternalLeg(child, sup))
      )
    )

  test(
    "① hold preserves the work-phase final text: judge ready-to-merge ⇒ workReport = the work result (first-write-wins)"
  ) {
    val system = ActorSystem(s"dfwr-1-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        (child, sup) <- noopRefs(system)
        repo = mkRepo(tempRoot / "hold-repo", "delegate/delegate-nebflow-wr1", withReport = false)
        _ = seedRecord("delegate-nebflow-wr1", Some("81"), Some("delegate/delegate-nebflow-wr1"), repo)
        outcome <- withJudge(DelegateJudge.Verdict.ReadyToMerge) {
          DelegateTerminal.onCompleted(
            res,
            "delegate-nebflow-wr1",
            "nebula-root",
            "t",
            "brief",
            "WORK PHASE FINAL REPORT (unique-hold-marker): fixed the merge-report loss, evidence at line 42",
            child,
            sup
          )
        }
        saved = DelegateRegistry.find("delegate-nebflow-wr1").flatMap(_.workReport)
        // 出队（模拟合并轮已走完）——否则第二跑会被队列条的 reminderSentAt 判成
        // 合并轮，走验收闸而非停靠
        _ = MergeQueue.onTerminal("delegate-nebflow-wr1")
        again <- withJudge(DelegateJudge.Verdict.ReadyToMerge) {
          DelegateTerminal.onCompleted(res, "delegate-nebflow-wr1", "nebula-root", "t", "brief", "SECOND run text", child, sup)
        }
        after = DelegateRegistry.find("delegate-nebflow-wr1").flatMap(_.workReport)
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assertEquals(outcome, DelegateTerminal.Outcome.HoldForMerge, "ready-to-merge must hold")
        assert(
          saved.exists(_.contains("unique-hold-marker")),
          s"the work-phase final text must be preserved at hold time; got: $saved"
        )
        assertEquals(again, DelegateTerminal.Outcome.HoldForMerge, "second run holds again")
        assert(
          after.exists(_.contains("unique-hold-marker")),
          s"first-write-wins: a re-hold must NOT overwrite the preserved work report; got: $after"
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "② updateReport preserve-before-overwrite: the merge-phase detail survives the finish overwrite; a preserved slot is never displaced"
  ) {
    // 老停靠形态（无保真槽）：merge 详报被 finish 短报覆写 ⇒ 旧 detail 留底
    seedRecord("delegate-nebflow-wr2", Some("82"), Some("delegate/x"), tempRoot)
    DelegateRegistry.updateReport("delegate-nebflow-wr2", "merge", "MERGE-PHASE WORK DETAIL: root cause + evidence", "")
    DelegateRegistry.updateReport("delegate-nebflow-wr2", "finish", "merged to main cleanly", "")
    val legacy = DelegateRegistry.find("delegate-nebflow-wr2")
    // 停靠保真形态：workReport 已在槽 ⇒ 申报覆写绝不顶掉
    seedRecord("delegate-nebflow-wr3", Some("83"), Some("delegate/x"), tempRoot, workReport = Some("PRESERVED WORK TEXT"))
    DelegateRegistry.updateReport("delegate-nebflow-wr3", "finish", "merged ok", "")
    val preserved = DelegateRegistry.find("delegate-nebflow-wr3")
    // 同 detail 重报：无留底（无信息可保）
    seedRecord("delegate-nebflow-wr4", Some("84"), Some("delegate/x"), tempRoot)
    DelegateRegistry.updateReport("delegate-nebflow-wr4", "merge", "same detail", "")
    DelegateRegistry.updateReport("delegate-nebflow-wr4", "merge", "same detail", "")
    val noSeed = DelegateRegistry.find("delegate-nebflow-wr4")
    assert(
      legacy.flatMap(_.workReport).contains("MERGE-PHASE WORK DETAIL: root cause + evidence"),
      s"the overwritten merge-phase detail must be preserved into workReport; got: ${legacy.flatMap(_.workReport)}"
    )
    assertEquals(legacy.flatMap(_.reportDetail), Some("merged to main cleanly"), "the finish detail still owns the slot")
    assertEquals(preserved.flatMap(_.workReport), Some("PRESERVED WORK TEXT"), "first-write-wins over the report slot")
    assertEquals(noSeed.flatMap(_.workReport), None, "identical re-reports must not seed a work report")
  }

  test(
    "③ merge-round release carries the work result: merge status one line + preserved work report + REPORT.md path and digest (real merge e2e)"
  ) {
    val system = ActorSystem(s"dfwr-3-${scala.util.Random.nextInt(100000)}")
    try
      val repo = mkRepo(tempRoot / "e2e-repo", "delegate/delegate-nebflow-wr5", withReport = true)
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-nebflow-wr5", Some("85"), Some("delegate/delegate-nebflow-wr5"), repo)
        // 工作轮终态：judge ready-to-merge ⇒ 停靠 + 保真（根因①）
        _ <- withJudge(DelegateJudge.Verdict.ReadyToMerge) {
          DelegateTerminal.onCompleted(
            res,
            "delegate-nebflow-wr5",
            "nebula-root",
            "t",
            "brief",
            "WORK RESULT (unique-work-marker): the fix lands in DelegateWorkReport with spec coverage",
            child,
            sup
          )
        }
        held = DelegateRegistry.find("delegate-nebflow-wr5").flatMap(_.workReport)
        // 合并轮：真并 + finish 申报（覆写单槽——根因②的现场）
        _ = mergeNoFF(repo, "delegate/delegate-nebflow-wr5")
        _ = DelegateRegistry.updateReport("delegate-nebflow-wr5", "finish", "merged to main cleanly", "")
        // 合并轮终态：judge 缺席 ⇒ 自申报 finish 回落 completed；附页随 Release 尾注
        release <- withJudgeAbsent {
          DelegateTerminal.onCompleted(
            res,
            "delegate-nebflow-wr5",
            "nebula-root",
            "t",
            "brief",
            "merge done",
            child,
            sup
          )
        }
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(held.exists(_.contains("unique-work-marker")), s"hold must preserve the work text; got: $held")
        assert(release.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $release")
        val suffix = release.asInstanceOf[DelegateTerminal.Outcome.Release].verdictSuffix
        // 合并信息降为一行状态
        assert(suffix.contains("[delegate-finish] merge status: merged to main cleanly"), suffix)
        // 工作结果段（保真的工作轮终稿——作者令：内容不回传即丢失）
        assert(suffix.contains("work result (preserved from the work phase"), suffix)
        assert(suffix.contains("unique-work-marker"), suffix)
        // REPORT.md 路径 + 内容摘要（第一亲 diff 判据）
        assert(suffix.contains("reports/20261025_120000_delegate-finish-fix/REPORT.md"), suffix)
        assert(suffix.contains("REPORT HEADING LINE"), suffix)
        // 判词行照旧（附页是增量，不替换既有尾注）
        assert(suffix.contains("[delegate-judge] verdict: completed."), suffix)
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "④ backward compat: an old-shape delegate (no preserved work text, no report files) degrades gracefully — merge-status line only; a bare record stays byte-identical legacy"
  ) {
    val system = ActorSystem(s"dfwr-4-${scala.util.Random.nextInt(100000)}")
    try
      val repo = mkRepo(tempRoot / "legacy-repo", "delegate/delegate-nebflow-wr6", withReport = false)
      val bareRepo = mkRepo(tempRoot / "legacy-bare-repo", "delegate/delegate-nebflow-wr8", withReport = false)
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        (child, sup) <- noopRefs(system)
        // ④a 老盘面形态：无停靠保真（修复前已完成停靠）、finish 自申报在槽、无
        // 报告文件 ⇒ 附页 = merge status 一行（merge 信息降为一行——正是修复形态），
        // 无 work result 段、无 report files 段。
        // 台账 #896 时序：先入队（放行 = 通知 ⇒ turn-granted = 合并轮在飞判据）
        // 再真并——若先并后入队，放行时账实核对会直接关账 merged，合并轮形态失位。
        _ = seedRecord("delegate-nebflow-wr6", Some("86"), Some("delegate/delegate-nebflow-wr6"), repo)
        _ = enqueueRound("delegate-nebflow-wr6", "delegate/delegate-nebflow-wr6", repo, "86", child, sup)
        _ = mergeNoFF(repo, "delegate/delegate-nebflow-wr6")
        _ = DelegateRegistry.updateReport("delegate-nebflow-wr6", "finish", "merged to main cleanly", "")
        releaseA <- withJudgeAbsent {
          DelegateTerminal.onCompleted(res, "delegate-nebflow-wr6", "nebula-root", "t", "brief", "merge done", child, sup)
        }
        // ④b 全裸记录（连 finish 自申报都没有——fail-open unjudged 腿）：附页整段
        // 缺席，Release 尾注逐字现状
        _ = seedRecord("delegate-nebflow-wr8", Some("88"), Some("delegate/delegate-nebflow-wr8"), bareRepo)
        _ = enqueueRound("delegate-nebflow-wr8", "delegate/delegate-nebflow-wr8", bareRepo, "88", child, sup)
        _ = mergeNoFF(bareRepo, "delegate/delegate-nebflow-wr8")
        releaseB <- withJudgeAbsent {
          DelegateTerminal.onCompleted(res, "delegate-nebflow-wr8", "nebula-root", "t", "brief", "merge done", child, sup)
        }
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(releaseA.isInstanceOf[DelegateTerminal.Outcome.Release], s"A got: $releaseA")
        val sfxA = releaseA.asInstanceOf[DelegateTerminal.Outcome.Release].verdictSuffix
        assert(sfxA.contains("[delegate-finish] merge status: merged to main cleanly"), sfxA)
        assert(!sfxA.contains("work result (preserved"), s"no preserved text must mean no work section: $sfxA")
        assert(!sfxA.contains("report files"), s"no report files must mean no files section: $sfxA")
        assert(sfxA.contains("verdict: completed"), sfxA)
        assert(releaseB.isInstanceOf[DelegateTerminal.Outcome.Release], s"B got: $releaseB")
        assertEquals(
          releaseB.asInstanceOf[DelegateTerminal.Outcome.Release].verdictSuffix,
          "\n\n[delegate-judge] verdict: completed (unjudged — judge unavailable).",
          "a bare record (nothing to attach) must keep the legacy suffix byte-identical"
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("⑤ appendixFor anchors: empty record ⇒ \"\"; bounds honored (work text truncated at Bound.WorkChars)") {
    assertEquals(DelegateWorkReport.appendixFor(None), "", "no record ⇒ no appendix (never an empty stub)")
    val bare = DelegateRegistry.Record(
      id = "delegate-nebflow-wr7",
      executor = "nebflow",
      cwd = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "p",
      title = "t",
      createdAt = 1L
    )
    assertEquals(
      DelegateWorkReport.appendixFor(Some(bare)),
      "",
      "a record with no work text and no git anchors ⇒ no appendix (backward-compatible silence)"
    )
    val longWork = "W" * (DelegateWorkReport.Bound.WorkChars + 500)
    val rec = bare.copy(reportDetail = Some("merged ok"), workReport = Some(longWork))
    val apx = DelegateWorkReport.appendixFor(Some(rec))
    assert(apx.startsWith("\n\n[delegate-finish] merge status: merged ok"), apx.take(80))
    assert(apx.contains("work result (preserved from the work phase"), apx.take(200))
    assert(
      apx.contains(longWork.take(64)) && !apx.contains(longWork),
      "the work section must be truncated at Bound.WorkChars (summary, not the full text)"
    )
    // 纯组装：三段全空 = ""（不产空附页）
    assertEquals(DelegateWorkReport.compose(None, None, None), "")
  }

end DelegateFinishWorkResultSpec
