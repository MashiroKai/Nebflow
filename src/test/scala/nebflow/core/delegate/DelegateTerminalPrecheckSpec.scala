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
    os.remove.all(tempRoot / "chains")
    os.remove.all(tempRoot / "delegates")
    os.remove.all(tempRoot / "subagent-tasks")

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  /** 判定机在场的证明件：被问即炸——确定性预检必须零 LLM 消耗。 */
  private class ExplodingJudge extends DelegateJudge.DelegateJudgePort.Face:

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

  end mkRealRepo

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
    "① pass-through (empty-burn form): zero tool calls + empty resultText + no slots ⇒ Release with the (no text output) body, WITHOUT consulting the judge — no verdict line, no terminal, no standby badge"
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
        standbyLines = ChainStore
          .listSync()
          .flatMap(_.entries)
          .filter(_.kind == "standby")
          .filter(_.payload.hcursor.get[String]("delegateId").toOption.contains("delegate-precheck-empty"))
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // 申报通道退役（T2 批）：回合结束 = 直通交付——空烧形态也交付（正文缺位
        // 显式化为 "(no text output)"），绝不再走提醒腿（ExplodingJudge 在场即炸
        // 证明零 LLM 消耗）。
        assert(
          outcome.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"every turn end must release (pass-through), got: $outcome"
        )
        assert(outcome.toString.contains("(no text output)"), "the empty body is explicit in the payload")
        assertEquals(v, None, "no terminal verdict line may be written for an empty turn")
        assertEquals(rec.flatMap(_.standbySince), None, "the standby badge is never engine-seeded")
        assertEquals(rec.flatMap(_.reportStatus), None, "the declaration slot stays untouched")
        assertEquals(standbyLines, Nil, "no standby audit line")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "② the max_tokens stub text is NOT special either: stub + zero tools + no slots ⇒ the same pass-through Release (no fake completion marker, no reminder leg)"
  ) {
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
        assert(
          outcome.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"stub-only output still releases (pass-through), got: $outcome"
        )
        assertEquals(v, None, "no terminal verdict for the stub-only turn")
        assert(rec.flatMap(_.standbySince).isEmpty)
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "③ pass-through on a real working seat (ahead>0, no slots): the release carries the text verbatim; no unjudged marker, no dock parking (the honesty-marker protocol retired with the declaration channel)"
  ) {
    val system = ActorSystem(s"dtp-3-${scala.util.Random.nextInt(100000)}")
    val repo = tempRoot / "unjudged-repo"
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.clear()
        (child, sup) <- noopRefs(system)
        _ = mkRealRepo(repo, "delegate/unjudged-1", ahead = 1)
        _ = seedRealRecord("delegate-unjudged", "83", repo, "delegate/unjudged-1")
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
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $outcome")
        assert(outcome.toString.contains("real result text"), "the release payload carries the final text verbatim")
        assertEquals(v, None, "no chain line (the unjudged-marker protocol retired)")
        assert(rec.flatMap(_.standbySince).isEmpty, "no standby parking")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "④ pass-through uniformity: substantive-but-undeclared turns (tools counted or uncounted) release the same way; a finish-slot record releases identically — the slot never forks the outcome"
  ) {
    val system = ActorSystem(s"dtp-4-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(new ExplodingJudge)
        (child, sup) <- noopRefs(system)
        // (a) Some(3)（有工具）＋空终文 ⇒ 直通。
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
        // (b) finish 槽在场 ⇒ 同一直通（判定机缺席也不咨询——ExplodingJudge 不炸即证）。
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
        // 直通.uniform：零判词行，槽位不改变结果形态。
        List("v1" -> v1, "v2" -> v2, "v2b" -> v2b, "v3" -> v3).foreach { (name, v) =>
          assertEquals(v, None, s"$name: no chain line (retired protocol)")
        }
        List("r1" -> r1, "r2" -> r2, "r2b" -> r2b, "r3" -> r3).foreach { (name, r) =>
          assert(r.isInstanceOf[DelegateTerminal.Outcome.Release], s"$name must release (pass-through): $r")
        }
        assert(r2b.toString.contains("a full substantive report"), "the substantive text rides the payload")
        assert(r3.toString.contains("did the work"), "the finish-slot turn releases the text the same way")
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
