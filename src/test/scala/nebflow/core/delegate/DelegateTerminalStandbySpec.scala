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
    nebflow.ForceRemove.removeAll(tempRoot / "chains")
    nebflow.ForceRemove.removeAll(tempRoot / "delegates")
    nebflow.ForceRemove.removeAll(tempRoot / "subagent-tasks")

  private class SilentLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  /** 判定机在场的证明件：被问即炸——待命裁决必须零 LLM 消耗。 */
  private class ExplodingJudge extends DelegateJudge.DelegateJudgePort.Face:

    def judgeTerminal(input: DelegateJudge.TerminalInput): IO[Option[DelegateJudge.Verdict]] =
      IO.raiseError(new RuntimeException("judge must NOT be consulted on a standby turn"))

  private class CompletedJudge extends DelegateJudge.DelegateJudgePort.Face:

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

  test(
    "① pass-through contract (report channel retired): a standby-slot record + turn end releases to the parent — no dock parking, no standby audit line, zero LLM (exploding judge proves the judge is not consulted)"
  ) {
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
        // 申报通道退役（T2 批）：回合结束 = 完成交付——槽位在也直通。
        assert(
          outcome.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"every turn end must release (pass-through), got: $outcome"
        )
        assert(outcome.toString.contains("待命，等下一条 Mail 再续"), "the release payload carries the final text")
        // 不终判：零 terminal-verdict 行、判词序零 LLM（ExplodingJudge 未炸即证）。
        assertEquals(terminalVerdicts("delegate-sb-declared"), Nil)
        // 槽位面：申报槽保真（引擎内部承接写点维护）；停靠时刻不再由终态采纳点写。
        assertEquals(rec.flatMap(_.reportStatus), Some("standby"))
        assert(rec.flatMap(_.standbySince).isEmpty, "no dock parking on turn end (the adoption point retired)")
        // 审计行退役。
        assertEquals(lines, Nil, "no standby audit line (retired protocol)")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "② pass-through on a working seat (standby slot + unmerged deliverable commits): the turn end releases the same way — the engine never merges and writes no dock/audit faces"
  ) {
    val system = ActorSystem(s"dt-sb-2-${scala.util.Random.nextInt(100000)}")
    val repo = mkRealRepo(tempRoot / "repo-sb2", "delegate/delegate-sb-blocked", ahead = 2)
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
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
        assert(
          outcome.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"every turn end must release (pass-through), got: $outcome"
        )
        assertEquals(terminalVerdicts("delegate-sb-blocked"), Nil, "no verdict line (retired protocol)")
        assert(
          standbyLines("delegate-sb-blocked").isEmpty,
          "no standby audit line"
        )
        assert(
          rec.flatMap(_.standbySince).isEmpty,
          "no dock timestamp on turn end"
        )
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "③ wake-slot mechanics stay live (the Mail wake point): parkStandby seeds the dock pair, clearStandby (deliverToDelegate live leg) resets both slots; onCompleted stays pass-through across the whole cycle"
  ) {
    val system = ActorSystem(s"dt-sb-3-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        _ = DelegateJudge.DelegateJudgePort.install(new ExplodingJudge)
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-sb-wake", Some("93"), tempRoot, branch = None)
        _ = DelegateRegistry.updateReport("delegate-sb-wake", "standby", "等 Mail", "")
        parked <- DelegateTerminal.onCompleted(
          res,
          "delegate-sb-wake",
          "nebula-root",
          "t",
          "brief",
          resultText = "待命回执",
          child,
          sup,
          sessionToolCalls = Some(1)
        )
        // 停靠槽播种（注册表层操作：终态采纳点退役后，槽对由引擎内部面维护）。
        _ = assert(DelegateRegistry.parkStandby("delegate-sb-wake").isRight)
        preWake = DelegateRegistry.find("delegate-sb-wake")
        // ── Mail 唤醒（deliverToDelegate live 腿的执行点）──
        _ <- IO.blocking(DelegateRegistry.clearStandby("delegate-sb-wake"))
        cleared = DelegateRegistry.find("delegate-sb-wake")
        // 唤醒轮：无新申报、有真实产出 → 直通交付（通道退役）。
        wokenNoReport <- DelegateTerminal.onCompleted(
          res,
          "delegate-sb-wake",
          "nebula-root",
          "t",
          "brief",
          resultText = "按 Mail 指示干完了活，交付如下……",
          child,
          sup,
          sessionToolCalls = Some(4)
        )
        // 唤醒轮 finish 槽在 → 同样直通（槽位不改写终局）。
        _ = DelegateRegistry.updateReport("delegate-sb-wake", "finish", "干完了", "")
        wokenFinish <- DelegateTerminal.onCompleted(
          res,
          "delegate-sb-wake",
          "nebula-root",
          "t",
          "brief",
          resultText = "干完了",
          child,
          sup,
          sessionToolCalls = Some(2)
        )
        rec = DelegateRegistry.find("delegate-sb-wake")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(parked.isInstanceOf[DelegateTerminal.Outcome.Release], "turn end releases (pass-through)")
        assert(preWake.flatMap(_.standbySince).isDefined, "parkStandby seeds the dock timestamp (registry-level)")
        assertEquals(cleared.flatMap(_.standbySince), None, "wake clears the dock timestamp")
        assertEquals(cleared.flatMap(_.reportStatus), None, "wake clears the declaration slot")
        assert(
          wokenNoReport.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"the woken turn releases (pass-through), got: $wokenNoReport"
        )
        assert(
          wokenFinish.isInstanceOf[DelegateTerminal.Outcome.Release] && wokenFinish.toString.contains("干完了"),
          s"the finish-slot turn releases the same way, got: $wokenFinish"
        )
        assertEquals(terminalVerdicts("delegate-sb-wake"), Nil, "no verdict lines (retired protocol)")
        assertEquals(rec.flatMap(_.standbySince), None)
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "④ a fresh finish report after a parked standby supersedes it: updateReport clears standbySince (new declaration wins)"
  ) {
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
