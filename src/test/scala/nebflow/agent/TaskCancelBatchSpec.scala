package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * Cancel-batch (user ruling 2026-08-26 08:33): task cancellations no longer
 * wake the idle root — they buffer and ride the NEXT activity as ONE packaged
 * block; while processing they merge through a debounce window flushed via
 * FlushCancelNotices. Non-root agents keep the immediate injection.
 *
 * Acceptance (user phrasing):
 *   - 连取消 N 任务 → 空闲时零轮次、下次活动一条打包
 *   - 工作中 N 任务一窗口一条（test drives FlushCancelNotices directly —
 *     the 45s wall-clock timer is exercised as a message, not slept on）
 */
class TaskCancelBatchSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val RootSid = "cancel-batch-root"
  private val CoderSid = "cancel-batch-coder"

  /** Recording mock: every request appends to `requests`, replies text done.
    * Turn 1 on the root can be held on a gate (processing-window test). */
  private class CancelLlm(
    requests: Ref[IO, List[LlmRequest]],
    gate: Option[cats.effect.Deferred[IO, Unit]]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)) >>
        (gate match
          case Some(g) if requests_holds_first_root_turn(req) => Stream.eval(g.get)
          case _ => Stream.unit
        ) >>
        Stream(StreamChunk.TextDelta(s"ack-${req.sessionId}"), StreamChunk.Done(None, None))

    private def requests_holds_first_root_turn(req: LlmRequest): Boolean =
      // hold only the FIRST root request (the gating turn); later requests
      // (the drain-injected package turn) pass through freely.
      req.sessionId == RootSid
  end CancelLlm

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private val nebulaDef: AgentDef =
    AgentDef(name = "Nebula", description = "root under test", tools = List("Read"), systemPrompt = "")

  private val coderDef: AgentDef =
    AgentDef(name = "Coder", description = "non-root under test", tools = List("Read"), systemPrompt = "")

  /** loadCurrentDef reloads from disk every turn — pin the tool sets. */
  private def seedAgents(tmp: os.Path): Unit =
    for (name, sid) <- List(("Nebula", RootSid), ("Coder", CoderSid)) do
      val dir = tmp / "agents" / name
      os.makeDir.all(dir)
      os.write.over(dir / "agent.json",
        s"""{"name":"$name","displayName":"$name","description":"cancel-batch fixture","tools":["Read"]}""")

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 100.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def notice(id: String, subject: String, reason: String): AgentCommand.TaskCancelNotice =
    AgentCommand.TaskCancelNotice(id, subject, s"desc of $subject", reason, System.currentTimeMillis())

  // ── pure packaging ──

  test("package: Nil is empty; single keeps the detailed pre-batch shape; N≥2 lists in order") {
    assertEquals(AgentActor.packageCancelNotices(Nil), "")
    val single = AgentActor.packageCancelNotices(List(notice("7", "修复登录", "方向错了")))
    assert(single.startsWith("[任务取消 #7: 修复登录]"))
    assert(single.contains("任务描述: desc of 修复登录"))
    assert(single.contains("取消原因: 方向错了"))
    assert(single.contains("请停止与该任务相关的工作"))
    val batch = AgentActor.packageCancelNotices(List(
      notice("419", "A", "r1"), notice("420", "B", "r2"), notice("421", "C", "r3")
    ))
    assert(batch.startsWith("[任务取消 ×3]（按取消时间排序）"))
    // chronological (append) order preserved
    val i1 = batch.indexOf("#419 A"); val i2 = batch.indexOf("#420 B"); val i3 = batch.indexOf("#421 C")
    assert(i1 >= 0 && i2 > i1 && i3 > i2, s"order broken: $i1/$i2/$i3 in [$batch]")
    assert(batch.contains("原因：r1") && batch.contains("原因：r2") && batch.contains("原因：r3"))
    assert(batch.contains("请停止与这些任务相关的工作"))
  }

  // ── wiring: idle buffering + activity flush ──

  test("idle root: N cancellations fire ZERO turns; the next activity carries ONE packaged block") {
    val system = ActorSystem("cancel-batch-idle")
    val tmp = os.temp.dir()
    seedAgents(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CancelLlm(requests, None))
        rootRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(RootSid),
            sessionName = Some("cancel-batch-idle")
          ),
          RootSid
        )
        _ <- resources.agentRegistry.update(
          _ + (RootSid -> AgentRecord(RootSid, rootRef, AgentKind.Root, RootSid, None))
        )
        _ <- rootRef ! notice("419", "任务甲", "不做了")
        _ <- rootRef ! notice("420", "任务乙", "改需求")
        _ <- rootRef ! notice("421", "任务丙", "重复了")
        _ <- IO.sleep(1.second)
        zeroTurns <- requests.get.map(_.isEmpty)
        _ <- IO(assert(zeroTurns, s"idle root must fire ZERO turns, got ${requests.get.map(_.size)}"))
        _ <- rootRef ! AgentCommand.UserInput("现在做点别的", None, Some("cid-act-1"))
        _ <- waitUntil(15.seconds)(
          requests.get.map(_.nonEmpty)
        )
        reqs <- requests.get
        _ <- IO({
          // exactly ONE turn for the activity
          assertEquals(reqs.size, 1, s"expected exactly 1 request, got ${reqs.size}")
          val texts = reqs.head.messages.map(_.textContent).mkString("\n")
          assert(texts.contains("现在做点别的"), "original activity text must survive the enrich")
          assert(texts.contains("[任务取消 ×3]"), s"packaged block missing: [$texts]")
          assert(texts.contains("#419 任务甲 — 原因：不做了"))
          assert(texts.contains("#421 任务丙 — 原因：重复了"))
        })
      yield ()
      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      system.stopAll.attempt.void.unsafeRunSync()
  }

  // ── wiring: non-root keeps the immediate injection ──

  test("non-root: cancellation still injects immediately (single detailed block, one turn)") {
    val system = ActorSystem("cancel-batch-nonroot")
    val tmp = os.temp.dir()
    seedAgents(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CancelLlm(requests, None))
        coderRef <- system.spawn(
          AgentActor(
            agentDef = coderDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(CoderSid),
            sessionName = Some("cancel-batch-nonroot")
          ),
          CoderSid
        )
        _ <- resources.agentRegistry.update(
          _ + (CoderSid -> AgentRecord(CoderSid, coderRef, AgentKind.Root, CoderSid, None))
        )
        _ <- coderRef ! notice("7", "修复登录", "方向错了")
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        reqs <- requests.get
        _ <- IO({
          assertEquals(reqs.size, 1, s"immediate single turn expected, got ${reqs.size}")
          val texts = reqs.head.messages.map(_.textContent).mkString("\n")
          assert(texts.contains("[任务取消 #7: 修复登录]"), s"single-block shape missing: [$texts]")
        })
      yield ()
      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      system.stopAll.attempt.void.unsafeRunSync()
  }

  // ── wiring: processing debounce window merges into one package ──

  test("processing root: window cancels merge; FlushCancelNotices queues ONE package for the turn-end drain") {
    val system = ActorSystem("cancel-batch-busy")
    val tmp = os.temp.dir()
    seedAgents(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        gate <- cats.effect.Deferred[IO, Unit]
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, new CancelLlm(requests, Some(gate)))
        rootRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(RootSid),
            sessionName = Some("cancel-batch-busy")
          ),
          RootSid
        )
        _ <- resources.agentRegistry.update(
          _ + (RootSid -> AgentRecord(RootSid, rootRef, AgentKind.Root, RootSid, None))
        )
        // start a turn; the first LLM request parks on the gate
        _ <- rootRef ! AgentCommand.UserInput("开始干活", None, Some("cid-busy-1"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 1))
        // two cancellations arrive mid-turn (buffer; the real 45s timer is
        // armed via forkTurn — the test drives the flush as a message)
        _ <- rootRef ! notice("501", "工作中任务A", "取消A")
        _ <- rootRef ! notice("502", "工作中任务B", "取消B")
        _ <- IO.sleep(300.millis)
        oneTurnOnly <- requests.get.map(_.size == 1)
        _ <- IO(assert(oneTurnOnly, "mid-turn cancellations must NOT fire extra turns"))
        // debounce expiry (simulated): flush → queued for the turn-end drain
        _ <- rootRef ! AgentCommand.FlushCancelNotices()
        _ <- IO.sleep(200.millis)
        stillOne <- requests.get.map(_.size == 1)
        _ <- IO(assert(stillOne, "flush must not interrupt the in-flight turn"))
        // release turn 1 → finishTurn drains the queued package as turn 2
        _ <- gate.complete(())
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 2))
        reqs <- requests.get
        _ <- IO({
          assertEquals(reqs.size, 2, s"expected turn1 + packaged turn2, got ${reqs.size}")
          val texts = reqs(1).messages.map(_.textContent).mkString("\n")
          assert(texts.contains("[任务取消 ×2]"), s"merged package missing: [$texts]")
          assert(texts.contains("#501 工作中任务A — 原因：取消A"))
          assert(texts.contains("#502 工作中任务B — 原因：取消B"))
        })
      yield ()
      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      system.stopAll.attempt.void.unsafeRunSync()
  }

end TaskCancelBatchSpec
