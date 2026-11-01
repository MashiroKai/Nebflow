package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{Defaults, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * 四修②（2026-10-07 修复令「判词诚实化」）判读 + 状态矩阵批锚演进（2026-11-01 ·
 * 作者令「complete × 未申报/idle ⇒ system reminder 提醒其调整状态，不终判」；
 * 审计基线
 * `docs/20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939.md`）：
 *
 *  - **锚演进（未申报空回合）**：`sessionToolCalls = Some(0)` 且终文空/空烧 stub
 *    且**无申报** ⇒ 状态矩阵未申报腿——Outcome.NudgeIdle（提醒补报，绝不终判）：
 *    零终判、零父投递、零 terminal-verdict 行、零 standbySince（「待命」徽标只归
 *    显式申报——未申报空等≠申报待命）；「提醒进入 standby 或报 finish」由
 *    DelegateStandbyCare.IdleNudge 承担（判定机零 LLM 消耗不变——ExplodingJudge 钉）。
 *  - 诚实未判：判定机两层全败＋**无申报 × ahead>0 防退化**（矩阵下判词链的
 *    唯一未申报门票角——就地座位，无 worktree ⇒ Release＋unmerged-work note，
 *    不翻转）⇒ 仍 completed（fail-open 既有语义不变），但判词行 =
 *    `completed (unjudged — judge unavailable)`，ChainStore 行 source=`unjudged`；
 *  - 不误伤：有工具调用（Some(3)）／调用方未计数（None）／真实终文的**未申报**
 *    回合同样归未申报腿（提醒），只有 finish/merge 申报（或合并轮/防退化回落）
 *    才进判词链；自申报在场时不算 unjudged（自申报背书——平线 source=judge）。
 *
 * 现场：鹦鹉骑自行车 delegate——空烧 stub 终文＋零工具调用，判定机两腿全败
 * （jev 无凭据 403＋SmallJudge 静默 None），旧回落链报了假 completed。
 */
class DelegateTerminalPrecheckSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dt-precheck"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot)

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    MergeQueue.reset()
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")

  override def afterEach(context: munit.AfterEach): Unit = MergeQueue.reset()

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  /** 判定机在场的证明件：被问即炸——确定性预检必须零 LLM 消耗。 */
  private class ExplodingJudge extends DelegateJudge.DelegateJudgePort.Face:

    def decideWorktree(task: String): IO[Option[Boolean]] =
      IO.raiseError(new RuntimeException("judge must NOT be consulted when the deterministic precheck fires"))

    def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
      IO.raiseError(new RuntimeException("judge must NOT be consulted when the deterministic precheck fires"))

  private def seedRecord(id: String, taskId: Option[String]): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = tempRoot.toString,
        project = None,
        taskId = taskId,
        parentSessionId = "nebula-root",
        title = "seeded",
        createdAt = 1L,
        branch = None,
        baseBranch = Some("main"),
        projectWorkspace = Some(tempRoot.toString)
      )
    )

  /** 真仓夹具：main init + branch 领先 ahead 个提交（防退化角需真实 ahead）。 */
  private def mkRealRepo(dir: os.Path, branch: String, ahead: Int): os.Path =
    os.remove.all(dir)
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

  /** 落在真仓座位上的记录（cwd=repo、branch=工作分支、无 worktree——就地座位）。 */
  private def seedRealRecord(id: String, taskId: String, repo: os.Path, branch: String): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id,
        executor = "nebflow",
        cwd = repo.toString,
        project = None,
        taskId = Some(taskId),
        parentSessionId = "nebula-root",
        title = "seeded-real",
        createdAt = 1L,
        branch = Some(branch),
        baseBranch = Some("main"),
        projectWorkspace = Some(repo.toString)
      )
    )

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

  private def verdictLines(delegateId: String): List[(String, String)] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict").flatMap { e =>
      e.payload.hcursor.get[String]("delegateId").toOption.filter(_ == delegateId).map { _ =>
        (
          e.payload.hcursor.get[String]("verdict").toOption.getOrElse(""),
          e.payload.hcursor.get[String]("source").toOption.getOrElse("")
        )
      }
    }

  private def lastVerdict(delegateId: String): Option[(String, String)] = verdictLines(delegateId).lastOption

  test(
    "① matrix complete×idle (empty-burn form): zero tool calls + empty resultText + no report ⇒ NudgeIdle WITHOUT consulting the judge — no verdict line, no terminal, no standby badge"
  ) {
    val system = ActorSystem(s"dtp-1-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(new ExplodingJudge)
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-precheck-empty", Some("81"))
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-precheck-empty",
          "nebula-root",
          "t",
          "画一个「鹦鹉骑自行车」的循环动画",
          resultText = "",
          child,
          sup,
          sessionToolCalls = Some(0)
        )
        v = lastVerdict("delegate-precheck-empty")
        rec = DelegateRegistry.find("delegate-precheck-empty")
        standbyLines = ChainStore.listSync().flatMap(_.entries).filter(_.kind == "standby")
          .filter(_.payload.hcursor.get[String]("delegateId").toOption.contains("delegate-precheck-empty"))
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // 状态矩阵未申报腿（空烧形态）：Outcome.NudgeIdle——提醒补报，绝不终判
        // （ExplodingJudge 在场即炸证明零 LLM 消耗）。
        outcome match
          case DelegateTerminal.Outcome.NudgeIdle(chainsInFlight, emptyTurn) =>
            assert(!chainsInFlight, "no flow host in the fixture ⇒ zero chains in flight")
            assert(emptyTurn, "the empty-burn form must be named to the reminder")
          case other => fail(s"expected Outcome.NudgeIdle (matrix idle leg), got: $other")
        // 🔴 不给 completed 假终态：零 terminal-verdict 行、零父投递（Release 形态）。
        assertEquals(v, None, "no terminal verdict line may be written for an undeclared empty turn")
        // 「待命」徽标只归显式申报：未申报腿不落 standbySince、不补种申报槽。
        assertEquals(rec.flatMap(_.standbySince), None, "the standby badge is declared-only")
        assertEquals(rec.flatMap(_.reportStatus), None, "the declaration slot stays untouched (still undeclared)")
        assertEquals(standbyLines, Nil, "no standby audit line for the undeclared leg")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("② the max_tokens empty-burn stub is NOT real output either: stub + zero tools + no report ⇒ the same NudgeIdle (the incident form, no fake completion)") {
    val system = ActorSystem(s"dtp-2-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(new ExplodingJudge)
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-precheck-stub", Some("82"))
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-precheck-stub",
          "nebula-root",
          "t",
          "brief",
          resultText = Defaults.MaxTokensStubText,
          child,
          sup,
          sessionToolCalls = Some(0)
        )
        v = lastVerdict("delegate-precheck-stub")
        rec = DelegateRegistry.find("delegate-precheck-stub")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        outcome match
          case DelegateTerminal.Outcome.NudgeIdle(_, emptyTurn) => assert(emptyTurn)
          case other => fail(s"stub-only output must land in the reminder leg, not a verdict: $other")
        assertEquals(v, None, "no terminal verdict for the undeclared stub-only turn")
        assert(rec.flatMap(_.standbySince).isEmpty)
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "③ honesty when both judge legs fail AND no self-report: the matrix's unjudged corner (undeclared × ahead>0 anti-degradation) keeps legacy completed semantics, but the line reads (unjudged — judge unavailable)"
  ) {
    val system = ActorSystem(s"dtp-3-${scala.util.Random.nextInt(100000)}")
    val repo = tempRoot / "unjudged-repo"
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 判定机不可用 = 端口卸载（两层全败的测试形态）。
        _ = DelegateJudge.DelegateJudgePort.clear()
        (child, sup) <- noopRefs(system)
        _ = mkRealRepo(repo, "delegate/unjudged-1", ahead = 1)
        _ = seedRealRecord("delegate-unjudged", "83", repo, "delegate/unjudged-1")
        // 状态矩阵下 unjudged 的唯一可达角：**无申报** × 防退化（ahead>0 拒停靠）
        // ⇒ 槽位闸放行进判词链；判定机缺席 ⇒ fail-open completed 但标 unjudged。
        // （finish 申报在场时是自申报背书——平线 source=judge，见④(b)。）
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-unjudged",
          "nebula-root",
          "t",
          "brief",
          resultText = "real result text",
          child,
          sup,
          sessionToolCalls = Some(0)
        )
        v = lastVerdict("delegate-unjudged")
        rec = DelegateRegistry.find("delegate-unjudged")
        unmergedNote = ChainStore.listSync().flatMap(_.entries)
          .filter(_.kind == "unmerged-work")
          .filter(_.payload.hcursor.get[String]("delegateId").toOption.contains("delegate-unjudged"))
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // fail-open 不变：verdict 仍是 completed（既有语义/下游零迁移）……
        assertEquals(v.map(_._1), Some("completed"))
        // ……但审计面可分「判过」与「没判成」：source=unjudged。
        assertEquals(v.map(_._2), Some("unjudged"), "the chain line must mark the unjudged form")
        val line = outcome.toString
        assert(line.contains("(unjudged — judge unavailable)"), s"the honest marker must be in the verdict line: $line")
        // 未申报 + ahead>0 + 无 worktree ⇒ 就地座位角：Release；零 queueFlip
        // （翻转只认 worktree 座位）、零停靠。unmerged-work 注记行在此形态不可达
        // （aheadOfBase 只对 worktree/ReadyToMerge 座位计算——既有语义，非本批面）。
        assert(outcome.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $outcome")
        assertEquals(unmergedNote, Nil, "the in-place-seat corner carries no unmerged-work audit row (existing semantics)")
        assert(rec.flatMap(_.standbySince).isEmpty, "no standby parking on the anti-degradation leg")
        assert(MergeQueue.snapshot.isEmpty, "no worktree ⇒ no queue flip")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "④ no false positives (matrix): substantive-but-undeclared turns (tools counted or uncounted) are REMINDED too (NudgeIdle — only finish/merge declarations reach the chain); a self-reported finish is never 'unjudged'"
  ) {
    val system = ActorSystem(s"dtp-4-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(new ExplodingJudge)
        (child, sup) <- noopRefs(system)
        // (a) Some(3)（有工具）＋空终文＋未申报 ⇒ 未申报腿（提醒），不进判词链。
        _ = seedRecord("delegate-tools3", Some("84"))
        r1 <- DelegateTerminal.onCompleted(
          res,
          "delegate-tools3",
          "nebula-root",
          "t",
          "brief",
          resultText = "",
          child,
          sup,
          sessionToolCalls = Some(3)
        )
        _ = seedRecord("delegate-uncounted", Some("85"))
        r2 <- DelegateTerminal.onCompleted(
          res,
          "delegate-uncounted",
          "nebula-root",
          "t",
          "brief",
          resultText = "",
          child,
          sup,
          sessionToolCalls = None
        )
        _ = seedRecord("delegate-substantive", Some("87"))
        r2b <- DelegateTerminal.onCompleted(
          res,
          "delegate-substantive",
          "nebula-root",
          "t",
          "brief",
          resultText = "a full substantive report with no declaration",
          child,
          sup,
          sessionToolCalls = Some(7)
        )
        // (b) finish 自申报在场 ⇒ 判词链（判定机缺席 ⇒ 自申报背书——卸载端口，
        //     否则 ExplodingJudge 会被真实咨询而炸——链门票语义的另一面证明）。
        _ = DelegateJudge.DelegateJudgePort.clear()
        _ = seedRecord("delegate-selfrep", Some("86"))
        _ = DelegateRegistry.updateReport("delegate-selfrep", "finish", "declared", "")
        r3 <- DelegateTerminal.onCompleted(
          res,
          "delegate-selfrep",
          "nebula-root",
          "t",
          "brief",
          resultText = "did the work",
          child,
          sup,
          sessionToolCalls = Some(0)
        )
        v1 = lastVerdict("delegate-tools3")
        v2 = lastVerdict("delegate-uncounted")
        v2b = lastVerdict("delegate-substantive")
        v3 = lastVerdict("delegate-selfrep")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // 未申报（无论产出形态）= 提醒腿：零判词行、零终态。
        assertEquals(v1, None, "undeclared + tools ⇒ reminded, not unjudged-completed")
        assertEquals(v2, None, "undeclared + uncounted ⇒ reminded, not judged")
        assertEquals(v2b, None, "a substantive report WITHOUT a declaration is still just reminded (finish is the only judged-complete combination)")
        assert(r1.isInstanceOf[DelegateTerminal.Outcome.NudgeIdle], s"got: $r1")
        assert(r2.isInstanceOf[DelegateTerminal.Outcome.NudgeIdle], s"got: $r2")
        assert(r2b.isInstanceOf[DelegateTerminal.Outcome.NudgeIdle], "substantive but undeclared ⇒ remind (matrix), s got: $r2b")
        // finish 自申报 → 判词链（判定机缺席 → 自申报背书 completed，source=judge）
        assertEquals(v3.map(_._1), Some("completed"))
        assertEquals(v3.map(_._2), Some("judge"), "a self-reported finish is NOT the unjudged form")
        assert(!r3.toString.contains("unjudged"), s"self-report backed completions keep the plain line: $r3")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("⑤ pure anchors: hasRealOutput / deterministicPrecheck / verdictLineCompleted") {
    // stub 与空白都不是真实产出
    assertEquals(DelegateTerminal.hasRealOutput(""), false)
    assertEquals(DelegateTerminal.hasRealOutput("   \n "), false)
    assertEquals(DelegateTerminal.hasRealOutput(Defaults.MaxTokensStubText), false)
    assertEquals(DelegateTerminal.hasRealOutput("actual deliverable"), true)
    // 预检只认 Some(0)（None = 调用方未计数，绝不发明数据）
    assertEquals(DelegateTerminal.deterministicPrecheck("", Some(0)).isDefined, true)
    assertEquals(DelegateTerminal.deterministicPrecheck(Defaults.MaxTokensStubText, Some(0)).isDefined, true)
    assertEquals(DelegateTerminal.deterministicPrecheck("", Some(2)), None, "tools were called ⇒ not an empty burn")
    assertEquals(DelegateTerminal.deterministicPrecheck("", None), None, "uncounted ⇒ precheck skipped")
    assertEquals(
      DelegateTerminal.deterministicPrecheck("done", Some(0)),
      None,
      "real text ⇒ judge's input, not the precheck's"
    )
    // 判词行措辞（作者令原文形态）
    assertEquals(DelegateTerminal.verdictLineCompleted(unjudged = false), "\n\n[delegate-judge] verdict: completed.")
    assertEquals(
      DelegateTerminal.verdictLineCompleted(unjudged = true),
      "\n\n[delegate-judge] verdict: completed (unjudged — judge unavailable)."
    )
  }

end DelegateTerminalPrecheckSpec
