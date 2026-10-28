package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior, Behaviors}
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.core.GitWorktrees
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * DelegateTerminal 终态判定钩子判读（worktree-judge-fifo 批 #1224 增补 §3.1/§3.3；
 * #566 修复批：blocked 判词退役——判官/预检/漂移/验收的「没做成」一律 completed＋注记）：
 *
 *  - ① 判定机全败回落链（fail-open）：无自申报 → completed（既有语义）；
 *    自申报 finish → completed；自申报 blocked（退役词）→ completed＋注记；
 *  - ② 判定机判 ready-to-merge：任务库状态改 ready-to-merge（completedAt = FIFO
 *    锚）+ MergeQueue 入队 + Outcome.HoldForMerge（**不报父不停 actor** 的判据面
 *    = 返回值），ChainStore 落 terminal-verdict 行；
 *  - ③ 无分支可并的 ready-to-merge = 降级 completed（诚实终态）；A5 步1 扩展
 *    （例⑤）：分支在但零领先提交同降级（commitsAhead==0，防空合并轮）；
 *  - ④ onTerminalCleanup：completed 腿不重复落判词行；failed/cancelled 补
 *    engine 判词行（#566：verdict=failed/cancelled 真值）+ MergeQueue 出队幂等。
 *  - ⑥-⑩（A5 步2 漂移闸红验①-⑤）：确证 → completed＋注记（source=drift）/
 *    账本归因豁免 / 合并轮豁免 / 读数不可确证 fail-open / worktree 座位只注记
 *    ——另附分类器纯函数锚（读数显式入参，零 git）。
 *
 * 判定机输入注入 = DelegateJudgePort 注册器（fake Face——M2 先例的测试装配面）。
 */
class DelegateTerminalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-terminal"
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
    branch: Option[String] = None,
    cwd: os.Path = tempRoot,
    worktree: Option[os.Path] = None,
    projectWorkspace: Option[os.Path] = None,
    worktreeJudged: Option[Boolean] = None
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
        projectWorkspace = Some(projectWorkspace.getOrElse(cwd).toString),
        worktree = worktree.map(_.toString),
        worktreeJudged = worktreeJudged
      )
    )

  /**
   * 真仓夹具（A5 步1 判据需要 git 面可读）：main 上 init；ahead > 0 ⇒ branch 上
   * 造领先提交（工作座位形态），ahead = 0 ⇒ branch park 在 main HEAD（只读座位
   * 形态）。GitWorktreesSpec 同款 git 序列。
   */
  private def mkRealRepo(dir: os.Path, branch: String, ahead: Int): os.Path =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    os.proc("git", "-C", dir.toString, "config", "user.email", "spec@nebflow.local").call(check = true)
    os.proc("git", "-C", dir.toString, "config", "user.name", "spec").call(check = true)
    os.proc("git", "-C", dir.toString, "commit", "--allow-empty", "-m", "init")
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    if ahead > 0 then
      os.proc("git", "-C", dir.toString, "checkout", "-b", branch)
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      (1 to ahead).foreach { i =>
        os.write(dir / f"deliverable-$i.txt", s"deliverable $i")
        os.proc("git", "-C", dir.toString, "add", ".").call(check = true)
        os.proc("git", "-C", dir.toString, "commit", "-m", s"deliverable $i")
          .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      }
    else os.proc("git", "-C", dir.toString, "branch", branch).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    dir

  end mkRealRepo

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

  /** 判定机不可用 = 端口卸载（未注册 → None → 回落链）。 */
  private def withJudgeAbsent[A](io: IO[A]): IO[A] =
    IO { DelegateJudge.DelegateJudgePort.clear() } *> io <* IO { DelegateJudge.DelegateJudgePort.clear() }

  private def verdictLine(delegateId: String): Option[String] =
    ChainStore
      .listSync()
      .flatMap(_.entries)
      .filter(_.kind == "terminal-verdict")
      .flatMap { e =>
        e.payload.hcursor
          .get[String]("delegateId")
          .toOption
          .filter(_ == delegateId)
          .map(_ => e.payload.hcursor.get[String]("verdict").toOption.getOrElse(""))
      }
      .lastOption

  test(
    "① fallback chain: judge absent + no self-report → completed (legacy); self-report finish → completed; self-report blocked (retired) → completed + note"
  ) {
    val system = ActorSystem(s"dt-1-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      (child, sup) <- noopRefs(system)
      _ = seedRecord("delegate-nebflow-f1", Some("41"))
      r1 <- withJudgeAbsent(
        DelegateTerminal.onCompleted(res, "delegate-nebflow-f1", "nebula-root", "t", "brief", "done work", child, sup)
      )
      _ = seedRecord("delegate-nebflow-f2", Some("42"))
      _ = DelegateRegistry.updateReport("delegate-nebflow-f2", "finish", "declared done", "")
      r2 <- withJudgeAbsent(
        DelegateTerminal.onCompleted(res, "delegate-nebflow-f2", "nebula-root", "t", "brief", "result text", child, sup)
      )
      _ = seedRecord("delegate-nebflow-f3", Some("43"))
      _ = DelegateRegistry.updateReport("delegate-nebflow-f3", "blocked", "missing key material", "")
      r3 <- withJudgeAbsent(
        DelegateTerminal.onCompleted(res, "delegate-nebflow-f3", "nebula-root", "t", "brief", "gave up", child, sup)
      )
      v1 = verdictLine("delegate-nebflow-f1")
      v2 = verdictLine("delegate-nebflow-f2")
      v3 = verdictLine("delegate-nebflow-f3")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(r1.isInstanceOf[DelegateTerminal.Outcome.Release], "no judge + no report ⇒ legacy completed release")
      assert(r1.toString.contains("completed"))
      assert(r2.isInstanceOf[DelegateTerminal.Outcome.Release])
      assertEquals(v1, Some("completed"), "the verdict line lands in ChainStore even on the fallback leg")
      assertEquals(v2, Some("completed"))
      // #566：退役 blocked 申报 → completed＋注记（wire 无 blocked；注记走尾注+reason）。
      assert(
        r3.isInstanceOf[DelegateTerminal.Outcome.Release] && r3.toString.contains("completed"),
        "self-report blocked (retired) → completed release"
      )
      assert(r3.toString.contains("missing key material"), "the retired-blocked detail rides the report note")
      assertEquals(v3, Some("completed"), "no separate blocked wire word is ever written")
      assert(r3.toString.contains("[delegate-judge] note:"), "the note channel is explicit in the release suffix")
    end for
  }

  test(
    "② judge says ready-to-merge: task status becomes ready-to-merge (completedAt anchored), the entry is enqueued (FIFO), outcome = HoldForMerge, verdict line = ready-to-merge"
  ) {
    val system = ActorSystem(s"dt-2-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 注册在 mkResources **之后**——SharedResources 构造行安装的是真判定机
        // 执行体（DelegateJudgeMachine.face），先装会被覆盖（测试装配顺序面）。
        _ = DelegateJudge.DelegateJudgePort.install(
          new DelegateJudge.DelegateJudgePort.Face:
            def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(Some(false))
            def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
              IO.pure(Some(DelegateJudge.Verdict.ReadyToMerge))
        )
        (child, sup) <- noopRefs(system)
        // A5 步1 配套：ready-to-merge 新资格 = 分支上确有领先提交 ⇒ 座位夹具从
        // 裸 tempRoot（无该分支 ⇒ commitsAhead fail-soft 0 会被新判据降级）换成
        // 真仓 + 真领先提交（下方既有断言逐字不动——红验⑤）。
        holdRepo = mkRealRepo(tempRoot / "hold-repo", branch = "delegate/delegate-nebflow-hold", ahead = 1)
        // 座位形态归位：基工作区 HEAD 回 main（可并形态——放行净空闸要求目标
        // 分支必须是检出分支；台账 #896 交通引导员制）。
        _ = os
          .proc("git", "-C", holdRepo.toString, "checkout", "main")
          .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        _ = seedRecord(
          "delegate-nebflow-hold",
          Some("51"),
          branch = Some("delegate/delegate-nebflow-hold"),
          cwd = holdRepo
        )
        _ <- res.subAgentTaskStore.recordTask(
          nebflow.shared.SubAgentTask(
            taskId = "delegate-nebflow-hold",
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
          "delegate-nebflow-hold",
          "nebula-root",
          "t",
          "brief",
          "commits ready",
          child,
          sup
        )
        tasks <- res.subAgentTaskStore.findByTaskId("delegate-nebflow-hold")
        queued = MergeQueue.find("delegate-nebflow-hold")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(
          outcome == DelegateTerminal.Outcome.HoldForMerge,
          "ready-to-merge must HOLD (no parent notify, no actor stop)"
        )
        assertEquals(tasks.map(_.status), Some("ready-to-merge"))
        assertEquals(tasks.flatMap(_.completedAt).isDefined, true, "completedAt = the FIFO rank anchor")
        assert(queued.isDefined, "the entry must be enqueued for the merge round")
        assertEquals(queued.map(_.branch), Some("delegate/delegate-nebflow-hold"))
        assertEquals(verdictLine("delegate-nebflow-hold"), Some("ready-to-merge"))
        // 审计行：enqueued 1 行；空队列 pump 立即放行（放行 = 通知）⇒ granted 1 行
        val mqLines = ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind)
        assertEquals(
          mqLines.count(l => l.payload.hcursor.get[String]("action").toOption.contains("enqueued")),
          1,
          "the enqueued audit line lands"
        )
        assertEquals(
          mqLines.count(l => l.payload.hcursor.get[String]("action").toOption.contains("granted")),
          1,
          "the empty-queue pump grants the turn immediately (notification = grant, single-flight holder)"
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("③ no branch to merge ⇒ ready-to-merge degrades to completed (honest terminal: nothing mergeable exists)") {
    val system = ActorSystem(s"dt-3-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 同②：真判定机在 mkResources 构造行安装，fake 必须后装才生效。
        _ = DelegateJudge.DelegateJudgePort.install(
          new DelegateJudge.DelegateJudgePort.Face:
            def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(Some(false))
            def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
              IO.pure(Some(DelegateJudge.Verdict.ReadyToMerge))
        )
        (child, sup) <- noopRefs(system)
        // branch = None（非 git 工作区形态）——无可并之物
        _ = seedRecord("delegate-nebflow-nobranch", Some("61"), branch = None)
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-nobranch",
          "nebula-root",
          "t",
          "brief",
          "done",
          child,
          sup
        )
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $outcome")
        assertEquals(verdictLine("delegate-nebflow-nobranch"), Some("completed"))
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "⑤ (A5 步1 红验④) branch present but ZERO commits ahead ⇒ ready-to-merge degrades to completed (empty merge round prevented)"
  ) {
    val system = ActorSystem(s"dt-5-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 同②③：真判定机在 mkResources 构造行安装，fake 必须后装才生效。
        _ = DelegateJudge.DelegateJudgePort.install(
          new DelegateJudge.DelegateJudgePort.Face:
            def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(Some(false))
            def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
              IO.pure(Some(DelegateJudge.Verdict.ReadyToMerge))
        )
        (child, sup) <- noopRefs(system)
        // 只读座位形态（A5 步1 判否腿）：分支已建但零领先提交——judge 误判
        // ready-to-merge ⇒ 凭 commitsAhead==0 降级，绝不进空合并轮。
        zeroRepo = mkRealRepo(tempRoot / "zero-repo", branch = "delegate/delegate-nebflow-zero", ahead = 0)
        _ = seedRecord(
          "delegate-nebflow-zero",
          Some("62"),
          branch = Some("delegate/delegate-nebflow-zero"),
          cwd = zeroRepo
        )
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-zero",
          "nebula-root",
          "t",
          "brief",
          "done reading",
          child,
          sup
        )
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $outcome")
        assertEquals(
          verdictLine("delegate-nebflow-zero"),
          Some("completed"),
          "zero commits ahead ⇒ degrade to completed (honest terminal)"
        )
        assert(
          MergeQueue.find("delegate-nebflow-zero").isEmpty,
          "an empty branch must never be enqueued into a merge round"
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "④ cleanup: failed/cancelled terminals write the engine verdict line (verdict=failed, #566 true value) and dequeue; the completed leg does NOT double-write"
  ) {
    val system = ActorSystem(s"dt-4-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      (child, sup) <- noopRefs(system)
      _ = seedRecord("delegate-nebflow-fail", Some("71"), branch = Some("delegate/delegate-nebflow-fail"))
      _ = MergeQueue.enqueue(
        MergeQueue.Member(
          delegateId = "delegate-nebflow-fail",
          branch = "delegate/delegate-nebflow-fail",
          baseWs = tempRoot.toString,
          baseBranch = "main",
          worktree = false,
          projectKey = "p-fail",
          taskId = Some("71"),
          parentSessionId = "nebula-root",
          description = "t",
          readyAt = 1L,
          channelKind = "internal",
          internal = Some(MergeQueue.InternalLeg(child, sup))
        )
      )
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-fail", "nebula-root", "failed", "failed")
      // completed 腿（onCompleted 已写 judge 行）→ cleanup 不得再补 engine 行
      before = ChainStore
        .listSync()
        .flatMap(_.entries)
        .count(e => e.kind == "terminal-verdict" && e.payload.hcursor.get[String]("source").toOption.contains("engine"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-fail", "nebula-root", "completed", "completed")
      after = ChainStore
        .listSync()
        .flatMap(_.entries)
        .count(e => e.kind == "terminal-verdict" && e.payload.hcursor.get[String]("source").toOption.contains("engine"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // 台账 #896：终态 = **关账**（成员留在持久账本，state 落终态；开放队列视图
      // 释放）——不再是「出队消失」。关账前账实核对先行（此处分支未并 ⇒ owner-gone）。
      assert(
        MergeQueue.find("delegate-nebflow-fail").exists(m => !m.open),
        "any terminal closes the queue membership (no open state survives)"
      )
      assert(MergeQueue.queueViewOf("delegate-nebflow-fail").isEmpty, "the closed member leaves the open queue view")
      assertEquals(before, 1, "the failed terminal wrote exactly one engine verdict line")
      assertEquals(after, before, "the completed leg must not double-write an engine verdict line")
      // #566：engine 判词行写真值（verdict=failed），不再冒充 blocked。
      assert(
        ChainStore
          .listSync()
          .flatMap(_.entries)
          .filter(e =>
            e.kind == "terminal-verdict" && e.payload.hcursor.get[String]("source").toOption.contains("engine")
          )
          .forall(_.payload.hcursor.get[String]("verdict").toOption.contains("failed")),
        "the engine verdict line carries verdict=failed"
      )
    end for
  }

  // ── A5 步2 漂移闸（评审 §六步2 红验①-⑤）─────────────────────────────

  /** 判词行三元读数：(verdict, source, reason)——payload 承重键直读。 */
  private def verdictRows(delegateId: String): List[(String, String, String)] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict").flatMap { e =>
      val c = e.payload.hcursor
      c.get[String]("delegateId")
        .toOption
        .filter(_ == delegateId)
        .map(_ =>
          (
            c.get[String]("verdict").toOption.getOrElse(""),
            c.get[String]("source").toOption.getOrElse(""),
            c.get[String]("reason").toOption.getOrElse("")
          )
        )
    }

  /**
   * 漂移真仓夹具：main@init(F) + delegate 分支 park 在 F（只读座位形态）；
   * advance="direct" ⇒ main 直写一笔（不可归属证据）；advance="delegateMerge" ⇒
   * delegate/other 一笔后 `--no-ff` 并回 main（归属候选——账本行由测试侧落）。
   * 返回 (repo, forkSha, baseTipSha)——全 sha，与判词 reason 里的证据直比。
   */
  private def mkDriftRepo(dir: os.Path, delegateBranch: String, advance: String): (os.Path, String, String) =
    nebflow.ForceRemove.removeAll(dir)
    os.makeDir.all(dir)
    os.proc("git", "init", "-b", "main", dir.toString).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    def g(args: String*): os.CommandResult =
      os.proc(Seq("git", "-C", dir.toString) ++ args).call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    g("config", "user.email", "spec@nebflow.local")
    g("config", "user.name", "spec")
    g("commit", "--allow-empty", "-m", "init")
    val fork = os.proc("git", "-C", dir.toString, "rev-parse", "main").call(check = true).out.trim()
    g("branch", delegateBranch)
    if advance == "direct" then
      os.write(dir / "outside.txt", "third-party write on the base branch")
      g("add", ".")
      g("commit", "-m", "outside direct commit")
    else if advance == "delegateMerge" then
      g("checkout", "-b", "delegate/other")
      os.write(dir / "other.txt", "other delegate deliverable")
      g("add", ".")
      g("commit", "-m", "other delegate deliverable")
      g("checkout", "main")
      g("merge", "--no-ff", "delegate/other", "-m", "merge delegate/other")
    val baseTip = os.proc("git", "-C", dir.toString, "rev-parse", "main").call(check = true).out.trim()
    (dir, fork, baseTip)

  end mkDriftRepo

  /** worktree 座位夹具：为已 park 的分支加挂 worktree（路径在仓外，防 untracked 互染）。 */
  private def addWorktree(repo: os.Path, branch: String, wt: os.Path): os.Path =
    os.proc("git", "-C", repo.toString, "worktree", "add", wt.toString, branch)
      .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
    wt

  test(
    "⑥ (A5 步2 红验①; #566) shared-checkout seat + zero-ahead + non-attributable base advance ⇒ completed+note, source=drift, reason carries fork/baseTip/commits"
  ) {
    val system = ActorSystem(s"dt-6-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      // 判定机缺席：漂移闸独立定罪（确证 ⇒ 根本不进判定机——闸先于判定机）
      (child, sup) <- noopRefs(system)
      (repo, fork, baseTip) = mkDriftRepo(
        tempRoot / "drift-direct",
        "delegate/delegate-nebflow-dg1",
        advance = "direct"
      )
      _ = seedRecord("delegate-nebflow-dg1", Some("91"), branch = Some("delegate/delegate-nebflow-dg1"), cwd = repo)
      outcome <- withJudgeAbsent(
        DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-dg1",
          "nebula-root",
          "t",
          "brief",
          "done reading",
          child,
          sup
        )
      )
      rows = verdictRows("delegate-nebflow-dg1")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      // #566：确证漂移 = completed＋注记（source=drift），不再是独立 blocked 状态。
      assert(outcome.toString.contains("completed"), s"confirmed drift must land a completed release: $outcome")
      assert(
        outcome.toString.contains("[delegate-judge] note:"),
        s"the drift evidence rides the note channel: $outcome"
      )
      assertEquals(
        rows.map(_._1).lastOption,
        Some("completed"),
        "the verdict line must be completed (note form, no blocked wire word)"
      )
      assertEquals(rows.map(_._2).lastOption, Some("drift"), "the payload source must be drift")
      val reason = rows.map(_._3).mkString
      // 证据三件：fork / baseTip / 不可归属提交列表（全 sha 直比）
      assert(reason.contains(fork), s"reason must carry the fork sha $fork: $reason")
      assert(reason.contains(baseTip), s"reason must carry the baseTip sha $baseTip: $reason")
      assert(
        reason.contains("non-attributable commits") && reason.contains(baseTip) && reason.contains("direct commit"),
        s"reason must carry the non-attributable commit list: $reason"
      )
      assert(MergeQueue.find("delegate-nebflow-dg1").isEmpty, "a drift block must land BEFORE the merge queue")
    end for
  }

  test(
    "⑦ (红验②) same drift shape but the base advance is another delegate's merge WITH a merge-queue ledger row ⇒ not penalized"
  ) {
    val system = ActorSystem(s"dt-7-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      (child, sup) <- noopRefs(system)
      (repo, _, _) = mkDriftRepo(tempRoot / "drift-attr", "delegate/delegate-nebflow-dg2", advance = "delegateMerge")
      // 账本行：真流程由 MergeQueue.record 落在被并 delegate 自己的键下（跨键扫描
      // 才可达）——此处按同一行契约直落 merge-merged 行
      _ = ChainStore.appendSync(
        "other-task-51",
        MergeQueue.EntryKind,
        Json.obj(
          "action" -> "merge-merged".asJson,
          "delegateId" -> "delegate-nebflow-other".asJson,
          "branch" -> "delegate/other".asJson,
          "projectKey" -> "p-other".asJson,
          "readyAt" -> 1L.asJson
        )
      )
      _ = seedRecord("delegate-nebflow-dg2", Some("92"), branch = Some("delegate/delegate-nebflow-dg2"), cwd = repo)
      outcome <- withJudgeAbsent(
        DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-dg2",
          "nebula-root",
          "t",
          "brief",
          "done reading",
          child,
          sup
        )
      )
      rows = verdictRows("delegate-nebflow-dg2")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(
        rows.map(_._1).lastOption,
        Some("completed"),
        "a fully-attributable base advance must NOT be penalized"
      )
      assert(!rows.map(_._2).contains("drift"), "no drift source may land on the attributable shape")
      assert(MergeQueue.find("delegate-nebflow-dg2").isEmpty, "zero-ahead ⇒ still never enqueued (step-1 degradation)")
      assert(outcome.toString.contains("completed"), s"got: $outcome")
    end for
  }

  test("⑧ (红验③) mergeRound=true ⇒ drift exempt (the A4 merge-round re-judge stays reachable)") {
    val system = ActorSystem(s"dt-8-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      // 判定机缺席（withJudgeAbsent 同⑥⑦⑨）：闸豁免后走既有回落链——completed
      (child, sup) <- noopRefs(system)
      (repo, _, _) = mkDriftRepo(tempRoot / "drift-round", "delegate/delegate-nebflow-dg3", advance = "direct")
      // 支先落一笔真实交付提交（分叉形态——若 park 在 fork，放行时账实核对直接
      // 关账 merged，合并轮在飞形态就位不了）。落笔后 HEAD 回 main。
      _ = os
        .proc("git", "-C", repo.toString, "checkout", "delegate/delegate-nebflow-dg3")
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      _ = os.write(repo / "dg3-deliverable.txt", "dg3 deliverable")
      _ = os.proc("git", "-C", repo.toString, "add", ".").call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      _ = os
        .proc("git", "-C", repo.toString, "commit", "-m", "dg3 deliverable")
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      _ = os
        .proc("git", "-C", repo.toString, "checkout", "main")
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      _ = seedRecord("delegate-nebflow-dg3", Some("93"), branch = Some("delegate/delegate-nebflow-dg3"), cwd = repo)
      // 合并轮在飞（账本 turn-granted ⇒ mergeRound=true，台账 #896 放行判据）
      _ = MergeQueue.enqueue(
        MergeQueue.Member(
          delegateId = "delegate-nebflow-dg3",
          branch = "delegate/delegate-nebflow-dg3",
          baseWs = repo.toString,
          baseBranch = "main",
          worktree = false,
          projectKey = "p-dg3",
          taskId = Some("93"),
          parentSessionId = "nebula-root",
          description = "t",
          readyAt = 1L,
          channelKind = "internal",
          internal = Some(MergeQueue.InternalLeg(child, sup))
        )
      )
      // 引擎加固批 2026-10-09（P1-1① 机械验收闸）：合并轮 completed 判词的 Release
      // 前置闸要求基分支 HEAD 真是「双亲 merge ＋第二亲=支头」——夹具必须**真并**
      // （放行后属主自己完成合并——交通引导员制的真实时序：grant → 属主 git merge
      // → 回报终态）。原先 park 在 fork 的零领先形态 `merge --no-ff` = Already up
      // to date 不产 merge commit，在 P1-1① 起会被判否。
      _ = os
        .proc("git", "-C", repo.toString, "merge", "--no-ff", "delegate/delegate-nebflow-dg3", "-m", "merge dg3")
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      outcome <- withJudgeAbsent(
        DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-dg3",
          "nebula-root",
          "t",
          "brief",
          "merge round done",
          child,
          sup
        )
      )
      rows = verdictRows("delegate-nebflow-dg3")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assertEquals(
        rows.map(_._1).lastOption,
        Some("completed"),
        "merge-round delegates are exempt (own A4 merge moved the base)"
      )
      assert(!rows.map(_._2).contains("drift"), "no drift source may land inside a merge round")
      assert(
        !rows.map(_._2).contains("merge-verify"),
        "a really-landed merge passes the mechanical acceptance gate (no refusal)"
      )
      assert(outcome.toString.contains("completed"), s"got: $outcome")
    end for
  }

  test(
    "⑨ (红验④) git reading unverifiable ⇒ no penalty, WARN only (fail-open, no fake block); classifier Warn branch anchored"
  ) {
    val system = ActorSystem(s"dt-9-${scala.util.Random.nextInt(100000)}")
    // 分类器纯函数锚（零 git）：锚读数缺失 ⇒ Warn，绝不 Penalty（🔴 禁假阻塞）
    val rec = DelegateRegistry.Record(
      id = "dg-x",
      executor = "nebflow",
      cwd = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "p",
      title = "t",
      createdAt = 1L,
      branch = Some("delegate/x"),
      baseBranch = Some("main")
    )
    val warned = DelegateTerminal.classifyDrift(
      rec,
      mergeRound = false,
      DelegateTerminal.DriftReadings(
        fork = None,
        baseTip = Some("bt"),
        branchTip = Some("bt"),
        baseAdvances = (_, _) => None,
        branchTips = () => None,
        isAncestorOf = (_, _) => Some(false)
      ),
      queueFootprint = _ => true
    )
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      (child, sup) <- noopRefs(system)
      // e2e：branch ref 缺失（rev-parse 不可确证）⇒ 闸 WARN 不罚
      (repo, _, _) = mkDriftRepo(tempRoot / "drift-gone", "delegate/delegate-nebflow-dg4", advance = "direct")
      _ = os
        .proc("git", "-C", repo.toString, "branch", "-D", "delegate/delegate-nebflow-dg4")
        .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
      _ = seedRecord("delegate-nebflow-dg4", Some("94"), branch = Some("delegate/delegate-nebflow-dg4"), cwd = repo)
      outcome <- withJudgeAbsent(
        DelegateTerminal.onCompleted(res, "delegate-nebflow-dg4", "nebula-root", "t", "brief", "done", child, sup)
      )
      rows = verdictRows("delegate-nebflow-dg4")
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(warned.isInstanceOf[DelegateTerminal.DriftResult.Warn], s"missing anchor reading ⇒ Warn, got: $warned")
      assertEquals(rows.map(_._1).lastOption, Some("completed"), "unverifiable git readings must NOT block (fail-open)")
      assertEquals(rows.map(_._2).lastOption, Some("unjudged"), "the fallback leg keeps the honest unjudged source")
      assert(outcome.toString.contains("completed"), s"got: $outcome")
    end for
  }

  test("⑩ (红验⑤) worktree seat, same drift shape ⇒ note merged into the judge digest, no penalty") {
    val system = ActorSystem(s"dt-10-${scala.util.Random.nextInt(100000)}")
    val captured = ListBuffer.empty[DelegateJudge.TerminalInput]
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // fake 判定机（后装覆盖真机——同②③⑤装配序）：捕获 gitDigest 输入
        _ = DelegateJudge.DelegateJudgePort.install(
          new DelegateJudge.DelegateJudgePort.Face:
            def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(Some(false))
            def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
              IO { captured += input; Some(DelegateJudge.Verdict.Completed) }
        )
        (child, sup) <- noopRefs(system)
        (repo, fork, baseTip) = mkDriftRepo(tempRoot / "drift-wt", "delegate/delegate-nebflow-dg5", advance = "direct")
        wt = addWorktree(repo, "delegate/delegate-nebflow-dg5", tempRoot / "wt-dg5")
        _ = seedRecord(
          "delegate-nebflow-dg5",
          Some("95"),
          branch = Some("delegate/delegate-nebflow-dg5"),
          cwd = wt,
          worktree = Some(wt),
          projectWorkspace = Some(repo)
        )
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-dg5",
          "nebula-root",
          "t",
          "brief",
          "done reading",
          child,
          sup
        )
        rows = verdictRows("delegate-nebflow-dg5")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assertEquals(rows.map(_._1).lastOption, Some("completed"), "worktree seat ⇒ note only, never blocked")
        assert(!rows.map(_._2).contains("drift"), "no drift source may land on the worktree-seat shape")
        assert(
          captured.exists(i =>
            i.gitDigest.contains("[base-drift note]") && i.gitDigest.contains(fork) && i.gitDigest.contains(baseTip)
          ),
          s"the drift note (with fork/baseTip evidence) must be merged into the judge digest; captured: ${captured.map(_.gitDigest)}"
        )
        assert(outcome.toString.contains("completed"), s"got: $outcome")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("⑪ classifier matrix (pure, zero git): penalty/note/clear/attribution rows") {
    val base = DelegateRegistry.Record(
      id = "dg-m",
      executor = "nebflow",
      cwd = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "p",
      title = "t",
      createdAt = 1L,
      branch = Some("delegate/m"),
      baseBranch = Some("main")
    )
    val shared = DelegateTerminal.DriftReadings(
      fork = Some("f"),
      baseTip = Some("b"),
      branchTip = Some("f"),
      baseAdvances = (_, _) => Some(List(GitWorktrees.CommitInfo("c1", List("f"), "wip"))),
      branchTips = () => Some(Nil),
      isAncestorOf = (_, _) => Some(false)
    )
    val fp = (b: String) => b == "delegate/other"
    // 共享座位 × 不可归属直写 ⇒ Penalty，reason 携证据三件
    val p = DelegateTerminal.classifyDrift(base, mergeRound = false, shared, fp)
    assert(p.isInstanceOf[DelegateTerminal.DriftResult.Penalty], s"got: $p")
    val reason = p.asInstanceOf[DelegateTerminal.DriftResult.Penalty].reason
    assert(
      reason.contains("f") && reason.contains("b") && reason.contains("c1") && reason.contains("direct commit"),
      reason
    )
    // worktree 座位同读数 ⇒ NoteOnly（只注记不罚）
    val wtRec = base.copy(worktree = Some("/wt"))
    assert(DelegateTerminal.classifyDrift(wtRec, false, shared, fp).isInstanceOf[DelegateTerminal.DriftResult.NoteOnly])
    // 合并轮 ⇒ Clear（豁免先于读数）
    assert(DelegateTerminal.classifyDrift(base, mergeRound = true, shared, fp) == DelegateTerminal.DriftResult.Clear)
    // delegate 形 merge（有账本行）＋其搭载提交（可达被并 tip）⇒ Clear（归属豁免——
    // fork..baseTip 含被并分支自身提交，随并归属）
    val merged = shared.copy(
      baseAdvances = (_, _) =>
        Some(
          List(
            GitWorktrees.CommitInfo("m1", List("f", "t2"), "merge other"),
            GitWorktrees.CommitInfo("d1", List("f"), "other's own deliverable")
          )
        ),
      branchTips = () => Some(List("delegate/other" -> "t2")),
      isAncestorOf = (c, t) => Some(c == "d1" && t == "t2")
    )
    assert(DelegateTerminal.classifyDrift(base, false, merged, fp) == DelegateTerminal.DriftResult.Clear)
    // delegate 形 merge 但无账本行 ⇒ Warn（不可确证压过一切，fail-open）
    assert(
      DelegateTerminal.classifyDrift(base, false, merged, _ => false).isInstanceOf[DelegateTerminal.DriftResult.Warn]
    )
    // 搭载提交不可达（祖先读数不可确证）⇒ Warn（fail-open）
    val brokenAncestry = merged.copy(isAncestorOf = (_, _) => None)
    assert(
      DelegateTerminal.classifyDrift(base, false, brokenAncestry, fp).isInstanceOf[DelegateTerminal.DriftResult.Warn]
    )
    // 非 delegate ref 的 merge（有行也不豁免）＋不在任何 attributable 支上的单亲 ⇒ Penalty
    val foreignMerge = merged.copy(
      branchTips = () => Some(List("feat/landing" -> "t2")),
      isAncestorOf = (_, _) => Some(false)
    )
    val fpNone = (b: String) => false
    assert(
      DelegateTerminal
        .classifyDrift(base, false, foreignMerge, fpNone)
        .isInstanceOf[DelegateTerminal.DriftResult.Penalty]
    )
  }

  test(
    "⑫ legacy shapes stay Clear: no branch anchor, non-git cwd, zero-ahead with unmoved base (existing ①③⑤ regression)"
  ) {
    val base = DelegateRegistry.Record(
      id = "dg-l",
      executor = "nebflow",
      cwd = "/tmp",
      project = None,
      taskId = None,
      parentSessionId = "p",
      title = "t",
      createdAt = 1L,
      branch = None,
      baseBranch = Some("main")
    )
    val readings = DelegateTerminal.DriftReadings(None, None, None, (_, _) => None, () => None, (_, _) => Some(false))
    // 无锚 ⇒ 闸盲（外部执行器腿 / 判否零绑定 legacy 记录同形）
    assert(DelegateTerminal.classifyDrift(base, false, readings, _ => false) == DelegateTerminal.DriftResult.Clear)
    // D1∨D2 不成立 ⇒ Clear（delegate 自己有领先提交 / 基未动的零领先）
    val noDrift =
      DelegateTerminal.DriftReadings(Some("f"), Some("f"), Some("f"), (_, _) => None, () => None, (_, _) => Some(false))
    val withBranch = base.copy(branch = Some("delegate/l"))
    assert(DelegateTerminal.classifyDrift(withBranch, false, noDrift, _ => false) == DelegateTerminal.DriftResult.Clear)
    val ownWork =
      DelegateTerminal.DriftReadings(Some("f"), Some("b"), Some("w"), (_, _) => None, () => None, (_, _) => Some(false))
    assert(DelegateTerminal.classifyDrift(withBranch, false, ownWork, _ => false) == DelegateTerminal.DriftResult.Clear)
  }

  test(
    "⑬ (P2-3 只读座位诚实化 × 台账 #896 交通引导员制) judged-completed seat + commits ahead ⇒ flips to ready-to-merge (auto-enqueued) for BOTH read-only and working seats; the engine never merges anything itself"
  ) {
    val system = ActorSystem(s"dt-13-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 同②③：真判定机在 mkResources 构造行安装，fake 必须后装才生效。
        // 判定机裁定 completed（「无可并之物」的误判形态——引擎凭确定性读数翻转）。
        _ = DelegateJudge.DelegateJudgePort.install(
          new DelegateJudge.DelegateJudgePort.Face:
            def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(Some(false))
            def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
              IO.pure(Some(DelegateJudge.Verdict.Completed))
        )
        (child, sup) <- noopRefs(system)
        // 只读座位形态：分支领先 1（delegate 违反只读纪律交了活）＋ 判否旗在场
        readOnlyRepo = mkRealRepo(tempRoot / "ro-flip-repo", branch = "delegate/delegate-nebflow-ro", ahead = 1)
        _ = seedRecord(
          "delegate-nebflow-ro",
          Some("63"),
          branch = Some("delegate/delegate-nebflow-ro"),
          cwd = readOnlyRepo,
          worktree = Some(readOnlyRepo),
          worktreeJudged = Some(false)
        )
        flipOutcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-ro",
          "nebula-root",
          "t",
          "brief",
          "done (made commits anyway)",
          child,
          sup
        )
        queued = MergeQueue.find("delegate-nebflow-ro")
        flipVerdict = verdictLine("delegate-nebflow-ro")
        // 工作座位对照：同形态但判是旗在场 ⇒ 台账 #896 起同样翻转入队（引擎零代并
        // ——DelegateAutoMerge 已退役，交付走队列：放行 = 通知属主自己合并）。
        workingRepo = mkRealRepo(tempRoot / "work-note-repo", branch = "delegate/delegate-nebflow-wn", ahead = 1)
        // 座位形态归位：基工作区 HEAD 回 main（可并形态——目标分支必须是检出分支）
        _ = os
          .proc("git", "-C", workingRepo.toString, "checkout", "main")
          .call(check = true, stdout = os.Pipe, stderr = os.Pipe)
        _ = seedRecord(
          "delegate-nebflow-wn",
          Some("64"),
          branch = Some("delegate/delegate-nebflow-wn"),
          cwd = workingRepo,
          worktree = Some(workingRepo),
          worktreeJudged = Some(true)
        )
        workOutcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-wn",
          "nebula-root",
          "t",
          "brief",
          "done",
          child,
          sup
        )
        queuedWn = MergeQueue.find("delegate-nebflow-wn")
        noteRows = ChainStore.listSync().flatMap(_.entries).filter(_.kind == "unmerged-work").flatMap { e =>
          e.payload.hcursor
            .get[String]("delegateId")
            .toOption
            .filter(_ == "delegate-nebflow-wn")
            .map(_ => e.payload.hcursor.get[Int]("commitsAhead").toOption)
        }
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(
          flipOutcome == DelegateTerminal.Outcome.HoldForMerge,
          "a read-only seat with delivered commits must flip to ready-to-merge (delivery is never silently dropped)"
        )
        assertEquals(flipVerdict, Some("ready-to-merge"), "the flipped verdict line is ready-to-merge")
        assert(queued.isDefined, "the flipped delegate must be enqueued into the merge queue")
        assertEquals(queued.map(_.branch), Some("delegate/delegate-nebflow-ro"))
        assert(
          workOutcome == DelegateTerminal.Outcome.HoldForMerge,
          "a working seat with delivered commits flips too — the engine never runs merges itself (traffic-controller model)"
        )
        assertEquals(
          verdictLine("delegate-nebflow-wn"),
          Some("ready-to-merge"),
          "the working seat's verdict line flips as well"
        )
        assertEquals(queuedWn.map(_.branch), Some("delegate/delegate-nebflow-wn"), "the working seat is enqueued")
        assert(
          !GitWorktrees.isAncestor(workingRepo, "delegate/delegate-nebflow-wn", "main").contains(true),
          "the engine did NOT merge anything itself (zero proxy-merge — the queue only notifies)"
        )
        // 台账 #896：旧 unmerged-work 注记被「翻转入队」取代（交付不再只注记）。
        assertEquals(
          noteRows,
          Nil,
          "the legacy unmerged-work note is superseded by the queue flip (delivery goes through the merge queue)"
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "⑭ (misc②; #566) ready-to-merge without a recorded baseBranch ⇒ completed+note (refuses to guess 'main'), engine-source verdict row lands"
  ) {
    val system = ActorSystem(s"dt-14-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(
          new DelegateJudge.DelegateJudgePort.Face:
            def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(Some(false))
            def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
              IO.pure(Some(DelegateJudge.Verdict.ReadyToMerge))
        )
        (child, sup) <- noopRefs(system)
        // detached-HEAD 形态：spawn 绑定缺 baseBranch 锚——holdForMerge 绝不猜 main
        _ = DelegateRegistry.put(
          DelegateRegistry.Record(
            id = "delegate-nebflow-nobase",
            executor = "nebflow",
            cwd = tempRoot.toString,
            project = None,
            taskId = Some("65"),
            parentSessionId = "nebula-root",
            title = "seeded",
            createdAt = 1L,
            branch = Some("delegate/delegate-nebflow-nobase"),
            baseBranch = None,
            projectWorkspace = Some(tempRoot.toString)
          )
        )
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-nebflow-nobase",
          "nebula-root",
          "t",
          "brief",
          "ready to merge",
          child,
          sup
        )
        rows = verdictRows("delegate-nebflow-nobase")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $outcome")
        // #566：降级 = completed＋注记（无独立 blocked 状态——注记交付）。
        assert(outcome.toString.contains("completed"), "the hold-degraded terminal must release as completed")
        assert(outcome.toString.contains("[delegate-judge] note:"), "the refusal rides the note channel")
        assert(
          outcome.toString.contains("refusing to guess 'main'"),
          s"the refusal reason must name the no-guessing rule: $outcome"
        )
        assertEquals(rows.lastOption.map(_._1), Some("completed"), "the verdict line is completed (note form)")
        assertEquals(
          rows.lastOption.map(_._2),
          Some("engine"),
          "the note line is engine-sourced (closes the ready-to-merge row)"
        )
        assert(MergeQueue.find("delegate-nebflow-nobase").isEmpty, "nothing must be enqueued without a merge target")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

end DelegateTerminalSpec
