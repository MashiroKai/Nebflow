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
 * DelegateTerminal 终态判定钩子判读（worktree-judge-fifo 批 #1224 增补 §3.1/§3.3）：
 *
 *  - ① 判定机全败回落链（fail-open）：无自申报 → completed（既有语义）；
 *    自申报 finish → completed；自申报 blocked → blocked(detail)；
 *  - ② 判定机判 ready-to-merge：任务库状态改 ready-to-merge（completedAt = FIFO
 *    锚）+ MergeQueue 入队 + Outcome.HoldForMerge（**不报父不停 actor** 的判据面
 *    = 返回值），ChainStore 落 terminal-verdict 行；
 *  - ③ 无分支可并的 ready-to-merge = 降级 completed（诚实终态）；
 *  - ④ onTerminalCleanup：completed 腿不重复落判词行；failed/cancelled 补
 *    engine blocked 行 + MergeQueue 出队幂等。
 *
 * 判定机输入注入 = DelegateJudgePort 注册器（fake Face——M2 先例的测试装配面）。
 */
class DelegateTerminalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-terminal"
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

  private def seedRecord(id: String, taskId: Option[String], branch: Option[String] = None): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = id, executor = "nebflow", cwd = tempRoot.toString, project = None, taskId = taskId,
        parentSessionId = "nebula-root", title = "seeded", createdAt = 1L,
        branch = branch, baseBranch = Some("main"), projectWorkspace = Some(tempRoot.toString)
      )
    )

  private def noopRefs(system: ActorSystem): IO[(ActorRef[AgentCommand], ActorRef[AgentEvent])] =
    for
      child <- system.spawn(new Behavior[AgentCommand]:
        def receive(ctx: nebflow.actor.ActorContext[AgentCommand], msg: AgentCommand): IO[Behavior[AgentCommand]] = IO.pure(this)
      , s"child-${scala.util.Random.nextInt(100000)}")
      sup <- system.spawn(new Behavior[AgentEvent]:
        def receive(ctx: nebflow.actor.ActorContext[AgentEvent], msg: AgentEvent): IO[Behavior[AgentEvent]] = IO.pure(this)
      , s"sup-${scala.util.Random.nextInt(100000)}")
    yield (child, sup)

  /** 判定机不可用 = 端口卸载（未注册 → None → 回落链）。 */
  private def withJudgeAbsent[A](io: IO[A]): IO[A] =
    IO { DelegateJudge.DelegateJudgePort.clear() } *> io <* IO { DelegateJudge.DelegateJudgePort.clear() }

  private def verdictLine(delegateId: String): Option[String] =
    ChainStore.listSync().flatMap(_.entries).filter(_.kind == "terminal-verdict").flatMap { e =>
      e.payload.hcursor.get[String]("delegateId").toOption.filter(_ == delegateId).map(_ =>
        e.payload.hcursor.get[String]("verdict").toOption.getOrElse("")
      )
    }.lastOption

  test("① fallback chain: judge absent + no self-report → completed (legacy); self-report finish → completed; self-report blocked → blocked(detail)") {
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
      assert(r3.isInstanceOf[DelegateTerminal.Outcome.Release] && r3.toString.contains("blocked"), "self-report blocked → blocked verdict")
      assertEquals(v3, Some("blocked"), "blocked verdict line lands with the reason channel")
  }

  test("② judge says ready-to-merge: task status becomes ready-to-merge (completedAt anchored), the entry is enqueued (FIFO), outcome = HoldForMerge, verdict line = ready-to-merge") {
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
        _ = seedRecord("delegate-nebflow-hold", Some("51"), branch = Some("delegate/delegate-nebflow-hold"))
        _ <- res.subAgentTaskStore.recordTask(
          nebflow.shared.SubAgentTask(
            taskId = "delegate-nebflow-hold", parentSessionId = "nebula-root", agentName = "nebflow",
            prompt = "brief", description = "t", status = "running", retryCount = 0,
            spawnedAt = 1L, completedAt = None, lastError = None, source = "delegate"
          )
        )
        outcome <- DelegateTerminal.onCompleted(res, "delegate-nebflow-hold", "nebula-root", "t", "brief", "commits ready", child, sup)
        tasks <- res.subAgentTaskStore.findByTaskId("delegate-nebflow-hold")
        queued = MergeQueue.find("delegate-nebflow-hold")
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome == DelegateTerminal.Outcome.HoldForMerge, "ready-to-merge must HOLD (no parent notify, no actor stop)")
        assertEquals(tasks.map(_.status), Some("ready-to-merge"))
        assertEquals(tasks.flatMap(_.completedAt).isDefined, true, "completedAt = the FIFO rank anchor")
        assert(queued.isDefined, "the entry must be enqueued for the merge round")
        assertEquals(queued.map(_.branch), Some("delegate/delegate-nebflow-hold"))
        assertEquals(verdictLine("delegate-nebflow-hold"), Some("ready-to-merge"))
        // 审计行：enqueued 1 行；空队列 pump 立即单飞行注入 ⇒ reminder-sent 1 行
        val mqLines = ChainStore.listSync().flatMap(_.entries).filter(_.kind == MergeQueue.EntryKind)
        assertEquals(mqLines.count(l => l.payload.hcursor.get[String]("action").toOption.contains("enqueued")), 1, "the enqueued audit line lands")
        assertEquals(mqLines.count(l => l.payload.hcursor.get[String]("action").toOption.contains("reminder-sent")), 1, "the empty-queue pump fires the merge reminder immediately (single-flight holder)")
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
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
        outcome <- DelegateTerminal.onCompleted(res, "delegate-nebflow-nobranch", "nebula-root", "t", "brief", "done", child, sup)
        _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
      yield
        assert(outcome.isInstanceOf[DelegateTerminal.Outcome.Release], s"got: $outcome")
        assertEquals(verdictLine("delegate-nebflow-nobranch"), Some("completed"))
      end for
    finally DelegateJudge.DelegateJudgePort.clear()
  }

  test("④ cleanup: failed/cancelled terminals write the engine blocked line and dequeue; the completed leg does NOT double-write") {
    val system = ActorSystem(s"dt-4-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new SilentLlm)
      (child, sup) <- noopRefs(system)
      _ = seedRecord("delegate-nebflow-fail", Some("71"), branch = Some("delegate/delegate-nebflow-fail"))
      _ = MergeQueue.enqueue(
        MergeQueue.Entry(
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
          childRef = child,
          supervisorRef = sup
        )
      )
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-fail", "nebula-root", "failed", "failed")
      // completed 腿（onCompleted 已写 judge 行）→ cleanup 不得再补 engine 行
      before = ChainStore.listSync().flatMap(_.entries).count(e => e.kind == "terminal-verdict" && e.payload.hcursor.get[String]("source").toOption.contains("engine"))
      _ <- DelegateTerminal.onTerminalCleanup(res, "delegate-nebflow-fail", "nebula-root", "completed", "completed")
      after = ChainStore.listSync().flatMap(_.entries).count(e => e.kind == "terminal-verdict" && e.payload.hcursor.get[String]("source").toOption.contains("engine"))
      _ <- IO(system.stopAll.handleErrorWith(_ => IO.unit))
    yield
      assert(MergeQueue.find("delegate-nebflow-fail").isEmpty, "any terminal releases the queue entry")
      assertEquals(before, 1, "the failed terminal wrote exactly one engine blocked line")
      assertEquals(after, before, "the completed leg must not double-write an engine verdict line")
    end for
  }

end DelegateTerminalSpec
