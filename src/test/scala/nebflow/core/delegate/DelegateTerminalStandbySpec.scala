package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * 案A 显式待命态判读（2026-10-31 批 · 作者 11:40 拍板「案A 显式待命态为主干」；
 * 状态矩阵批 2026-11-01 收编为矩阵组合 **complete × standby**；审计基线
 * `docs/20261031_112344_delegate-standby-semantics-audit__delegate-nebflow-c0a2b939.md`）：
 *
 *  - ① 显式申报 standby（含真实回执终文——两个活体实例形态）⇒ Outcome.Standby
 *    （declared=true）：判词序整体跳过（ExplodingJudge 在场即炸证明零 LLM）、
 *    零 terminal-verdict 行、standbySince 落盘、ChainStore `standby` 审计行
 *    （declared=true）；申报 detail（在等什么）随行。
 *  - ② 防退化闸：申报 standby 但分支有未并交付提交（真仓 ahead>0）⇒ **不采纳**——
 *    回落正常判词链（fake judge 判 completed ⇒ Release），合并义务当场兑现；
 *    standbySince 不落盘。
 *  - ③ 唤醒语义（干活→待命→唤醒→续跑）：park 后 DelegateRegistry.clearStandby
 *    （MailTool deliverToDelegate live 腿的执行点）清申报槽+停靠槽 ⇒ 唤醒轮
 *    **无新申报** = 矩阵未申报腿（NudgeIdle 提醒，绝不静默判完成）；唤醒轮
 *    **补报 finish** = 正常终判（Release completed + terminal-verdict 行）。
 *  - ④ 申报覆写语义：待命停靠后再 updateReport(finish)（唤醒轮的新申报）⇒
 *    standbySince 被清（新申报覆旧申报）——终态路径零待命残留。
 *
 * 矩阵四组合的引擎级钉死在 [[DelegateTerminalMatrixSpec]]；未申报空等腿的形态锚
 * 在 [[DelegateTerminalPrecheckSpec]]①/②。
 */
class DelegateTerminalStandbySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dt-standby"
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

  /** 判定机在场的证明件：被问即炸——待命裁决必须零 LLM 消耗。 */
  private class ExplodingJudge extends DelegateJudge.DelegateJudgePort.Face:
    def decideWorktree(task: String): IO[Option[Boolean]] =
      IO.raiseError(new RuntimeException("judge must NOT be consulted on a standby turn"))
    def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
      IO.raiseError(new RuntimeException("judge must NOT be consulted on a standby turn"))

  private class CompletedJudge extends DelegateJudge.DelegateJudgePort.Face:
    def decideWorktree(task: String): IO[Option[Boolean]] = IO.pure(None)
    def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
      IO.pure(Some(DelegateJudge.Verdict.Completed))

  private def seedRecord(id: String, taskId: Option[String], cwd: os.Path, branch: Option[String]): Unit =
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
        projectWorkspace = Some(cwd.toString)
      )
    )

  /** 真仓夹具：main init + branch 领先 ahead 个提交（DelegateTerminalSpec 同款）。 */
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

  private def terminalVerdicts(delegateId: String): List[String] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict").flatMap { e =>
      e.payload.hcursor.get[String]("delegateId").toOption.filter(_ == delegateId).map { _ =>
        e.payload.hcursor.get[String]("verdict").toOption.getOrElse("")
      }
    }

  private def standbyLines(delegateId: String): List[io.circe.Json] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "standby").flatMap { e =>
      e.payload.hcursor.get[String]("delegateId").toOption.filter(_ == delegateId).map(_ => e.payload)
    }

  test("① self-declared standby (with a real receipt as the final text — the live-incident form) ⇒ Outcome.Standby, no verdict, dock parked, zero LLM") {
    val system = ActorSystem(s"dt-sb-1-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(new ExplodingJudge)
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-sb-declared", Some("91"), tempRoot, branch = None)
        _ = DelegateRegistry.updateReport("delegate-sb-declared", "standby", "等作者下一条 Mail 再续", "")
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-sb-declared",
          "nebula-root",
          "t",
          "任务 brief",
          resultText = "收到，立即停手。当前状态冻结如下……待命，等下一条 Mail 再续。",
          child,
          sup,
          sessionToolCalls = Some(2)
        )
        rec = DelegateRegistry.find("delegate-sb-declared")
        lines = standbyLines("delegate-sb-declared")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        outcome match
          case DelegateTerminal.Outcome.Standby(detail, declared) =>
            assert(declared, "the declared leg must be marked declared=true")
            assertEquals(detail, "等作者下一条 Mail 再续")
          case other => fail(s"expected Outcome.Standby, got: $other")
        // 不终判：零 terminal-verdict 行、判词序零 LLM（ExplodingJudge 未炸即证）。
        assertEquals(terminalVerdicts("delegate-sb-declared"), Nil)
        // 停靠面：申报槽保真 + 停靠时刻落盘（面板/快照「待命」数据源）。
        assertEquals(rec.flatMap(_.reportStatus), Some("standby"))
        assert(rec.flatMap(_.standbySince).isDefined, "standbySince must be parked on adoption")
        // 审计行：standby（非终态）行在场、declared=true、detail 随行。
        assertEquals(lines.size, 1, s"exactly one standby audit line, got: $lines")
        assertEquals(lines.head.hcursor.get[Boolean]("declared").toOption, Some(true))
        assertEquals(
          lines.head.hcursor.get[String]("detail").toOption,
          Some("等作者下一条 Mail 再续")
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("② anti-degradation gate: standby declared BUT the branch carries unmerged deliverable commits (real repo, ahead>0) ⇒ NOT honored — the verdict chain runs and the merge obligation settles") {
    val system = ActorSystem(s"dt-sb-2-${scala.util.Random.nextInt(100000)}")
    val repo = mkRealRepo(tempRoot / "repo-sb2", "delegate/delegate-sb-blocked", ahead = 2)
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 拒绝后回落判词链 → 判定机被咨询（completed 判词形态）。
        _ = DelegateJudge.DelegateJudgePort.install(new CompletedJudge)
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-sb-blocked", Some("92"), repo, branch = Some("delegate/delegate-sb-blocked"))
        _ = DelegateRegistry.updateReport("delegate-sb-blocked", "standby", "想待命", "")
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-sb-blocked",
          "nebula-root",
          "t",
          "任务 brief",
          resultText = "回执：待命。",
          child,
          sup,
          sessionToolCalls = Some(1)
        )
        rec = DelegateRegistry.find("delegate-sb-blocked")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        // 防退化：standby 不得用于逃避合并义务 ⇒ 回落正常判词链（Release completed）。
        assert(
          outcome.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"standby with unmerged commits must fall through to the verdict chain, got: $outcome"
        )
        assertEquals(terminalVerdicts("delegate-sb-blocked"), List("completed"))
        assert(
          standbyLines("delegate-sb-blocked").isEmpty,
          "a refused standby must NOT write a standby audit line"
        )
        assert(
          rec.flatMap(_.standbySince).isEmpty,
          "a refused standby must NOT park the dock timestamp"
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("③ wake semantics (matrix): after a parked standby, clearStandby (the Mail wake point) resets both slots ⇒ the woken turn with NO new report is REMINDED (NudgeIdle), and a finish declaration is judged normally") {
    val system = ActorSystem(s"dt-sb-3-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(new ExplodingJudge)
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-sb-wake", Some("93"), tempRoot, branch = None)
        _ = DelegateRegistry.updateReport("delegate-sb-wake", "standby", "等 Mail", "")
        parked <- DelegateTerminal.onCompleted(
          res, "delegate-sb-wake", "nebula-root", "t", "brief",
          resultText = "待命回执", child, sup, sessionToolCalls = Some(1)
        )
        _ = assert(parked.isInstanceOf[DelegateTerminal.Outcome.Standby])
        // ── Mail 唤醒（deliverToDelegate live 腿的执行点）──
        _ <- IO.blocking(DelegateRegistry.clearStandby("delegate-sb-wake"))
        cleared = DelegateRegistry.find("delegate-sb-wake")
        // 唤醒轮：无新申报、有真实产出 → 矩阵未申报腿（提醒，绝不静默判完成）。
        wokenNoReport <- DelegateTerminal.onCompleted(
          res, "delegate-sb-wake", "nebula-root", "t", "brief",
          resultText = "按 Mail 指示干完了活，交付如下……", child, sup, sessionToolCalls = Some(4)
        )
        // 唤醒轮补报 finish → 正常终判（矩阵唯一终判组合；ExplodingJudge 会让咨询
        // 炸——先卸载端口模拟判定机缺席，自申报背书 completed）。
        _ = DelegateJudge.DelegateJudgePort.clear()
        _ = DelegateRegistry.updateReport("delegate-sb-wake", "finish", "干完了", "")
        wokenFinish <- DelegateTerminal.onCompleted(
          res, "delegate-sb-wake", "nebula-root", "t", "brief",
          resultText = "干完了", child, sup, sessionToolCalls = Some(2)
        )
        rec = DelegateRegistry.find("delegate-sb-wake")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assertEquals(cleared.flatMap(_.standbySince), None, "wake clears the dock timestamp")
        assertEquals(cleared.flatMap(_.reportStatus), None, "wake clears the declaration slot")
        assert(
          wokenNoReport.isInstanceOf[DelegateTerminal.Outcome.NudgeIdle],
          s"the woken turn WITHOUT a declaration is reminded (matrix complete×idle), got: $wokenNoReport"
        )
        assert(
          wokenFinish.isInstanceOf[DelegateTerminal.Outcome.Release] && wokenFinish.toString.contains("completed"),
          s"the woken finish-declared turn runs the normal verdict chain, got: $wokenFinish"
        )
        assertEquals(terminalVerdicts("delegate-sb-wake"), List("completed"))
        assertEquals(rec.flatMap(_.standbySince), None)
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test("④ a fresh finish report after a parked standby supersedes it: updateReport clears standbySince (new declaration wins)") {
    val id = "delegate-sb-supersede"
    seedRecord(id, Some("94"), tempRoot, branch = None)
    DelegateRegistry.updateReport(id, "standby", "等 Mail", "")
    val parkedTs = DelegateRegistry.parkStandby(id)
    assert(parkedTs.isRight)
    assert(DelegateRegistry.find(id).flatMap(_.standbySince).isDefined)
    // 唤醒轮申报 finish ⇒ 待命槽被新申报覆清。
    DelegateRegistry.updateReport(id, "finish", "干完了", "")
    val rec = DelegateRegistry.find(id)
    assertEquals(rec.flatMap(_.standbySince), None, "a non-standby re-report clears the dock slot")
    assertEquals(rec.flatMap(_.reportStatus), Some("finish"))
  }

end DelegateTerminalStandbySpec
