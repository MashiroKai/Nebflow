package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{Defaults, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * 四修②（2026-10-07 修复令「判词诚实化」）判读：
 *
 *  - 确定性预检（不依赖 LLM 判）：`sessionToolCalls = Some(0)` 且终文空/空烧
 *    stub ⇒ 直接判 blocked——判定机两层连问都不问（fake judge 在场即炸，
 *    证明 LLM 判零消耗）；ChainStore 行 source=`precheck`；
 *  - 诚实未判：判定机两层全败＋自申报缺失 ⇒ 仍 completed（fail-open 既有语义
 *    不变），但判词行 = `completed (unjudged — judge unavailable)`，ChainStore
 *    行 source=`unjudged`；
 *  - 不误伤：有工具调用（Some(3)）／调用方未计数（None）／真实终文 —— 预检
 *    全部让路；自申报在场时不算 unjudged（自申报背书）。
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
    "① deterministic precheck: zero tool calls + empty resultText ⇒ blocked WITHOUT consulting the judge (source=precheck)"
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
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $outcome")
        val line = outcome.toString
        assert(line.contains("verdict: blocked"), s"the honest blocked verdict line must land: $line")
        assert(line.contains("deterministic precheck"), s"the precheck marker must be named: $line")
        assertEquals(v, Some(("blocked", "precheck")), "the chain line carries verdict=blocked source=precheck")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("② the max_tokens empty-burn stub is NOT real output: stub + zero tools ⇒ blocked (the incident form)") {
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
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome.toString.contains("verdict: blocked"), s"stub-only output must be judged incomplete: $outcome")
        assertEquals(v, Some(("blocked", "precheck")))
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "③ honesty when both judge legs fail AND no self-report: legacy completed semantics stay, but the line reads (unjudged — judge unavailable)"
  ) {
    val system = ActorSystem(s"dtp-3-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 判定机不可用 = 端口卸载（两层全败的测试形态）。
        _ = DelegateJudge.DelegateJudgePort.clear()
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-unjudged", Some("83"))
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
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // fail-open 不变：verdict 仍是 completed（既有语义/下游零迁移）……
        assertEquals(v.map(_._1), Some("completed"))
        // ……但审计面可分「判过」与「没判成」：source=unjudged。
        assertEquals(v.map(_._2), Some("unjudged"), "the chain line must mark the unjudged form")
        val line = outcome.toString
        assert(line.contains("(unjudged — judge unavailable)"), s"the honest marker must be in the verdict line: $line")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "④ no false positives: real text, counted tools, or uncounted sessions all bypass the precheck; a self-report is never 'unjudged'"
  ) {
    val system = ActorSystem(s"dtp-4-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(new ExplodingJudge)
        (child, sup) <- noopRefs(system)
        // (a) Some(3)（有工具）＋空终文 ⇒ 预检不中 → 判定机会被问 → 炸 ⇒ 端口
        //     在场证明「未计零」不走预检。此处换卸载端口走 unjudged 腿验证。
        _ = DelegateJudge.DelegateJudgePort.clear()
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
        // (b) 自申报在场 ⇒ 不是 unjudged（自申报背书完成）。
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
        v3 = lastVerdict("delegate-selfrep")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // 有工具/未计数 → 预检让路（判定机缺席 → unjudged completed，非 blocked）
        assertEquals(v1, Some(("completed", "unjudged")), "tools present ⇒ not an empty burn even with empty text")
        assertEquals(v2, Some(("completed", "unjudged")), "uncounted sessions bypass the precheck (no invented data)")
        assert(
          r1.toString.contains("(unjudged — judge unavailable)"),
          s"tools+no-text with the judge down is honestly unjudged: $r1"
        )
        // 自申报背书 → source 仍是 judge 口径，行不带 unjudged 标注
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
