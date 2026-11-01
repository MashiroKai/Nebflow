package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * 完成判定状态矩阵（作者令 2026-11-01 · delegate 状态矩阵批）——引擎侧 complete
 * （回合结束）只是**触发判定的事件**，绝不直接等于完成；必须与 delegate_report
 * 状态槽取交集。本件把矩阵四组合各用引擎级 spec 钉死：
 *
 *  - ① complete × standby（申报）⇒ 等待：跳过终判、保活（详见
 *    [[DelegateTerminalStandbySpec]]①——此处收组合完整性引用腿）；
 *  - ② complete × idle（未申报）⇒ system reminder 提醒调整状态（NudgeIdle），
 *    不终判——含「链在飞 × 未申报」的 dispatcher 断供场景（S3/D1 对账）；
 *  - ③ complete × merge ⇒ 排队等合并（既有 MergeQueue FIFO——入队 + 单持有人）；
 *  - ④ complete × finish ⇒ **唯一允许判完成的组合**（Release）；
 *  - ⑤ 状态翻转保留（作者令「不许静默丢失」）：未申报 × 分支有领先提交 ⇒ 防退化
 *    落判词链 ⇒ queueFlip 翻转 ready-to-merge 自动入队；零领先降级回归随行；
 *  - ⑥ 合并轮豁免：引擎授命的合并执行窗——槽位（含陈旧 standby 申报）不得拦截，
 *    机械验收闸照常。
 */
class DelegateTerminalMatrixSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dt-matrix"
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

  private class CompletedJudge extends DelegateJudge.DelegateJudgePort.Face:
    def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(None)
    def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
      IO.pure(Some(DelegateJudge.Verdict.Completed))

  private def seedRecord(
    id: String,
    taskId: Option[String],
    cwd: os.Path,
    branch: Option[String],
    worktree: Option[os.Path] = None
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
        worktree = worktree.map(_.toString)
      )
    )

  /** 真仓夹具：main init + branch 领先 ahead 个提交。 */
  private def mkRealRepo(dir: os.Path, branch: String, ahead: Int): os.Path =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "checkout", "-b", branch).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    (1 to ahead).foreach { i =>
      os.write(dir / f"deliverable-$i.txt", s"deliverable $i")
      os.proc("git", "-C", dir.toString, "add", ".").call(check = true)
      os.proc("git", "-C", dir.toString, "commit", "-m", s"deliverable $i")
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    }
    dir

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

  private def terminalVerdicts(delegateId: String): List[(String, String)] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict").flatMap { e =>
      e.payload.hcursor.get[String]("delegateId").toOption.filter(_ == delegateId).map { _ =>
        (
          e.payload.hcursor.get[String]("verdict").toOption.getOrElse(""),
          e.payload.hcursor.get[String]("source").toOption.getOrElse("")
        )
      }
    }

  test("② matrix complete×idle: an undeclared substantive turn is reminded (NudgeIdle), never judged — and the same holds with chains in flight (dispatcher-cutoff scenario)") {
    val system = ActorSystem(s"dtmx-2-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-mx-idle", Some("201"), tempRoot, branch = None)
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-mx-idle",
          "nebula-root",
          "t",
          "brief",
          resultText = "A full substantive report that never called delegate_report.",
          child,
          sup,
          sessionToolCalls = Some(12)
        )
        verdicts = terminalVerdicts("delegate-mx-idle")
        rec = DelegateRegistry.find("delegate-mx-idle")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        outcome match
          case DelegateTerminal.Outcome.NudgeIdle(chainsInFlight, emptyTurn) =>
            assert(!emptyTurn, "a substantive report is NOT an empty burn (reminder names the real reason)")
            // chainsInFlight=false：夹具未挂 flow 宿主（链在飞腿的 true 形态由
            // 「fail-soft 0」读数面与 IdleNudge 文案锚承担——挂宿 e2e 归 S3 批）。
            assert(!chainsInFlight, "no flow host ⇒ zero chains")
          case other => fail(s"expected Outcome.NudgeIdle, got: $other")
        assertEquals(verdicts, Nil, "the idle row NEVER writes a verdict line")
        assertEquals(rec.flatMap(_.reportStatus), None, "slot untouched — still undeclared")
        assertEquals(rec.flatMap(_.standbySince), None, "the standby badge is declared-only")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("③ matrix complete×merge: the merge declaration rides the existing MergeQueue FIFO (enqueued, single-flight holder, task row ready-to-merge)") {
    val system = ActorSystem(s"dtmx-3-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 判定机缺席（mkResources 构造行装的是真机——clear 后 merge 自申报回落
        // ReadyToMerge 的路径才确定性可达）。
        _ = DelegateJudge.DelegateJudgePort.clear()
        (child, sup) <- noopRefs(system)
        repo = mkRealRepo(tempRoot / "mx-merge-repo", "delegate/delegate-mx-merge", ahead = 1)
        // 座位形态归位：基工作区 HEAD 回 main（可并形态——放行净空闸要求目标分支
        // 必须是检出分支）。
        _ = os.proc("git", "-C", repo.toString, "checkout", "main").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        _ = seedRecord("delegate-mx-merge", Some("202"), repo, branch = Some("delegate/delegate-mx-merge"))
        _ = DelegateRegistry.updateReport("delegate-mx-merge", "merge", "deliverable commits on the branch", "")
        // 任务行夹具（updateStatus 写 ready-to-merge 需要行在场——DelegateTerminalSpec② 同款）。
        _ <- res.subAgentTaskStore.recordTask(
          nebflow.shared.SubAgentTask(
            taskId = "delegate-mx-merge",
            parentSessionId = "nebula-root",
            agentName = "nebflow",
            prompt = "brief",
            description = "t",
            status = "running",
            retryCount = 0,
            spawnedAt = 1L,
            completedAt = None,
            lastError = None,
            source = "delegate"
          )
        )
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-mx-merge",
          "nebula-root",
          "t",
          "brief",
          resultText = "work complete, commits ready to land",
          child,
          sup,
          sessionToolCalls = Some(3)
        )
        queued = MergeQueue.find("delegate-mx-merge")
        tasks <- res.subAgentTaskStore.findByTaskId("delegate-mx-merge")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // 判定机缺席 ⇒ merge 自申报回落 ReadyToMerge（③组合 = 既有 FIFO 通道）。
        assert(outcome == DelegateTerminal.Outcome.HoldForMerge, s"got: $outcome")
        assert(queued.isDefined, "complete×merge must land in the MergeQueue")
        assertEquals(queued.map(_.branch), Some("delegate/delegate-mx-merge"))
        assertEquals(tasks.map(_.status), Some("ready-to-merge"), "the task row carries the merge-queued status")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("④ matrix complete×finish: the ONLY combination judged complete (judge absent ⇒ self-report-backed completed, plain honest line)") {
    val system = ActorSystem(s"dtmx-4-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 判定机缺席（finish 组合走自申报背书的确定性形态）。
        _ = DelegateJudge.DelegateJudgePort.clear()
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-mx-finish", Some("203"), tempRoot, branch = None)
        _ = DelegateRegistry.updateReport("delegate-mx-finish", "finish", "pure read, nothing to merge", "")
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-mx-finish",
          "nebula-root",
          "t",
          "brief",
          resultText = "final report body",
          child,
          sup,
          sessionToolCalls = Some(2)
        )
        verdicts = terminalVerdicts("delegate-mx-finish")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $outcome")
        // finish 自申报背书 ⇒ 诚实平线（不标 unjudged——申报在场即「判过」语义）。
        assert(!outcome.toString.contains("unjudged"), s"self-report backed: $outcome")
        assertEquals(verdicts.map(_._1), List("completed"))
        assertEquals(verdicts.map(_._2), List("judge"), "a finish declaration backs the completion (source=judge face)")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("⑤ flip preserved (author: never silently dropped): undeclared × branch ahead>0 ⇒ anti-degradation falls to the chain ⇒ queueFlip enqueues ready-to-merge") {
    val system = ActorSystem(s"dtmx-5-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 判定机判 completed（「无可并之物」误判形态）——引擎凭确定性读数翻转。
        _ = DelegateJudge.DelegateJudgePort.install(new CompletedJudge)
        (child, sup) <- noopRefs(system)
        repo = mkRealRepo(tempRoot / "mx-flip-repo", "delegate/delegate-mx-flip", ahead = 1)
        _ = os.proc("git", "-C", repo.toString, "checkout", "main").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        // worktree 座位形态（queueFlip 的判据要件——翻转要求 worktree 在场）。
        _ = seedRecord(
          "delegate-mx-flip",
          Some("204"),
          repo,
          branch = Some("delegate/delegate-mx-flip"),
          worktree = Some(repo)
        )
        // 🔴 无申报（workReport 槽也无）——交付义务由防退化 + 翻转兜住。
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-mx-flip",
          "nebula-root",
          "t",
          "brief",
          resultText = "committed the work but forgot to declare",
          child,
          sup,
          sessionToolCalls = Some(5)
        )
        queued = MergeQueue.find("delegate-mx-flip")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(
          outcome == DelegateTerminal.Outcome.HoldForMerge,
          s"undeclared × ahead>0 must fall through to the chain and flip to ready-to-merge, got: $outcome"
        )
        assert(queued.isDefined, "the flipped delegate is enqueued (delivery never silently dropped)")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("⑥ merge-round exemption: a stale standby declaration CANNOT trap the engine-ordered merge window (chain settles; mechanical acceptance passes on a really-landed merge)") {
    val system = ActorSystem(s"dtmx-6-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 判定机缺席（合并轮的机械验收闸对「真并」放行——不需要 LLM）。
        _ = DelegateJudge.DelegateJudgePort.clear()
        (child, sup) <- noopRefs(system)
        repo = mkRealRepo(tempRoot / "mx-round-repo", "delegate/delegate-mx-round", ahead = 1)
        _ = seedRecord("delegate-mx-round", Some("205"), repo, branch = Some("delegate/delegate-mx-round"))
        // 合并轮在飞（账本 turn-granted ⇒ mergeRound=true，台账 #896 放行判据）。
        _ = MergeQueue.enqueue(
          MergeQueue.Member(
            delegateId = "delegate-mx-round",
            branch = "delegate/delegate-mx-round",
            baseWs = repo.toString,
            baseBranch = "main",
            worktree = false,
            projectKey = "p-mx-round",
            taskId = Some("205"),
            parentSessionId = "nebula-root",
            description = "t",
            readyAt = 1L,
            channelKind = "internal",
            internal = Some(MergeQueue.InternalLeg(child, sup))
          )
        )
        // 真并（P1-1① 机械验收闸要求基 HEAD = 双亲 merge ＋第二亲=支头）。
        _ = os
          .proc("git", "-C", repo.toString, "merge", "--no-ff", "delegate/delegate-mx-round", "-m", "merge mx-round")
          .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        // 陈旧 standby 申报在场（上一轮的待命意图未被覆写）——合并轮必须不被拦截。
        _ = DelegateRegistry.updateReport("delegate-mx-round", "standby", "stale wait", "")
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-mx-round",
          "nebula-root",
          "t",
          "brief",
          resultText = "merge landed",
          child,
          sup,
          sessionToolCalls = Some(2)
        )
        verdicts = terminalVerdicts("delegate-mx-round")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(
          outcome.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"the merge window must settle (standby slot ignored inside a merge round), got: $outcome"
        )
        assert(outcome.toString.contains("completed"), s"got: $outcome")
        assertEquals(verdicts.map(_._1), List("completed"), "the mechanical acceptance passed (really landed)")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

end DelegateTerminalMatrixSpec
