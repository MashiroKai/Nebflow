package nebflow.core.delegate

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, AgentCommand, AgentEvent, Behavior}
import nebflow.agent.SpecResources
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * delegate 终态交付契约（T2 批改钉）：终态申报工具通道退役后，回合结束 = **完成
 * 交付事件**——`onCompleted` 一律 Release 直通（末条正文本体随载荷投父），槽位
 * （finish/standby/退役值）不再改写终局、判词行/提醒腿/unmerged-work 注记行随
 * 通道一并退役。本件把直通契约的各形态用引擎级 spec 钉死：
 *
 *  - ② complete × 未申报（substantive 终文）⇒ Release 直通，槽位不动；
 *  - ③ complete × finish 槽 ⇒ 同一直通（槽位不分叉结局）；
 *  - ⑤ 未申报 × 工作座位 × 分支有领先提交 ⇒ Release 直通，零注记行
 *    （集成走项目 merger 的授权请求，引擎自己绝不合并）。
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

  private class CompletedJudge extends DelegateJudge.DelegateJudgePort.Face:

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

  private def terminalVerdicts(delegateId: String): List[(String, String)] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict").flatMap { e =>
      e.payload.hcursor.get[String]("delegateId").toOption.filter(_ == delegateId).map { _ =>
        (
          e.payload.hcursor.get[String]("verdict").toOption.getOrElse(""),
          e.payload.hcursor.get[String]("source").toOption.getOrElse("")
        )
      }
    }

  test(
    "② pass-through (substantive undeclared turn): the turn end releases to the parent — slot untouched, no verdict line, no dock badge (the reminder protocol retired with the declaration channel)"
  ) {
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
          resultText = "A full substantive report that never stated its closing position.",
          child,
          sup,
          sessionToolCalls = Some(12)
        )
        verdicts = terminalVerdicts("delegate-mx-idle")
        rec = DelegateRegistry.find("delegate-mx-idle")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(
          outcome.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"every turn end must release (pass-through), got: $outcome"
        )
        assert(
          outcome.toString.contains("A full substantive report"),
          "the release payload carries the final text"
        )
        assertEquals(verdicts, Nil, "no verdict line (retired protocol)")
        assertEquals(rec.flatMap(_.reportStatus), None, "slot untouched — still undeclared")
        assertEquals(rec.flatMap(_.standbySince), None, "the standby badge is never engine-seeded")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "③ pass-through with a finish slot (judge absent): the turn end releases; no verdict line, no unjudged marker (the self-report-backing protocol retired with the declaration channel)"
  ) {
    val system = ActorSystem(s"dtmx-4-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 判定机缺席（finish 组合走自申报背书的确定性形态）。
        _ = DelegateJudge.DelegateJudgePort.clear()
        (child, sup) <- noopRefs(system)
        _ = seedRecord("delegate-mx-finish", Some("203"), tempRoot, branch = None)
        _ = DelegateRegistry.updateReport("delegate-mx-finish", "finish", "pure read", "")
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
        assert(outcome.toString.contains("final report body"), "the release payload carries the final text")
        assert(!outcome.toString.contains("unjudged"), s"plain line: $outcome")
        assertEquals(verdicts, Nil, "no verdict line (retired protocol)")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

  test(
    "⑤ pass-through on a working seat (undeclared × branch ahead>0): the turn end releases; no unmerged-work audit row (the note channel retired); the engine never merges anything itself"
  ) {
    val system = ActorSystem(s"dtmx-5-${scala.util.Random.nextInt(100000)}")
    try
      for
        res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
        // 判定机判 completed——未提交申报的领先提交只注记不阻断。
        _ = DelegateJudge.DelegateJudgePort.install(new CompletedJudge)
        (child, sup) <- noopRefs(system)
        repo = mkRealRepo(tempRoot / "mx-note-repo", "delegate/delegate-mx-note", ahead = 1)
        // worktree 座位形态。
        _ = seedRecord(
          "delegate-mx-note",
          Some("204"),
          repo,
          branch = Some("delegate/delegate-mx-note"),
          worktree = Some(repo)
        )
        // 🔴 无申报（workReport 槽也无）。
        outcome <- DelegateTerminal.onCompleted(
          res,
          "delegate-mx-note",
          "nebula-root",
          "t",
          "brief",
          resultText = "committed the work but forgot to declare",
          child,
          sup,
          sessionToolCalls = Some(5)
        )
        noteRows = ChainStore.listSync().flatMap(_.entries).filter(_.kind == "unmerged-work").flatMap { e =>
          e.payload.hcursor
            .get[String]("delegateId")
            .toOption
            .filter(_ == "delegate-mx-note")
            .map(_ => e.payload.hcursor.get[Int]("commitsAhead").toOption)
        }
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(
          outcome.isInstanceOf[DelegateTerminal.Outcome.Release],
          s"the seat releases to the parent, got: $outcome"
        )
        assert(outcome.toString.contains("committed the work but forgot to declare"), "the payload carries the final text")
        assertEquals(noteRows, Nil, "no unmerged-work audit row (retired protocol)")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
    end try
  }

end DelegateTerminalMatrixSpec
