package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * V7 (2026-09-03, 结果投递链丢失向量修复): planWaiting 真缓冲。
 *
 * 丢失形态（审计 V7）：plan 模式等待期到达的 ExternalEvent/UserInput 被消费后
 * 直接丢弃——注释写「Buffer user messages while planning」但代码未 buffer。
 * 主会话 StartPlan 期间有 in-flight delegate 完成 → 结果静默蒸发。
 *
 * 修复：ExternalEvent → pendingEvents（F2 落盘）、ImmediateInput →
 * pendingImmediateInputs（F2 落盘）、UserInput → pendingUserInputs（与
 * processing 态同款内存排队）；计划窗口四个出口（approved/cancelled/failed/
 * interrupt）统一经 F1 共享排空（drainQueuesAfterCompaction）注入——不新造
 * 排空语义。
 *
 * 用例：
 *  - R1 修复后绿：planWaiting 期间到达的 delegate 结果 + 用户消息被缓冲
 *    （不触发父 LLM 轮），PlanCancelled 后按 F1 语义注入同一续轮（LLM 请求
 *    同时携带两个 marker）。
 *  - R2 验红基线（变异：planWaiting 恢复吞消息）→ 丢失形态复现：取消后
 *    LLM 请求不含 marker（内容已丢）。
 */
class PlanWaitingBufferSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  /** 会话路由 mock：parentSid 计数制；plan-agent-* 会话回计划文本。 */
  private class RoutingLlm(
      parentSid: String,
      requests: Ref[IO, List[LlmRequest]],
      planStream: Stream[IO, StreamChunk]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        (if req.sessionId == parentSid then
           Stream(StreamChunk.TextDelta("parent ack"), StreamChunk.Done(None, None))
         else if req.sessionId.startsWith("plan-agent-") then planStream
         else Stream(StreamChunk.TextDelta("unexpected"), StreamChunk.Done(None, None)))

  private def mkResources(
      system: ActorSystem,
      tmp: os.Path,
      llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
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

  private def seedAgents(tmp: os.Path): Unit =
    // StartPlan 需要 Explorer（read-only 规划代理）。
    val explorer = tmp / "agents" / "Explorer"
    os.makeDir.all(explorer)
    os.write.over(explorer / "agent.json",
      """{"name":"Explorer","displayName":"Explorer","description":"planner","tools":[]}"""
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
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

  private def runScenario(planStream: Stream[IO, StreamChunk]): (List[LlmRequest], List[Json]) =
    val system = ActorSystem(s"v7-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "v7-planwait")
    seedAgents(tmp)
    PathUtil.setDataRoot(tmp / "data")
    try
      val parentSid = "v7-planwait-parent"
      val program = for
        wsEvents <- Ref.of[IO, List[Json]](Nil)
        requests <- Ref.of[IO, List[LlmRequest]](Nil)
        resources <- mkResources(system, tmp, RoutingLlm(parentSid, requests, planStream))
        parentRef <- system.spawn(
          AgentActor(
            agentDef = AgentDef(name = "Nebula", description = "v7", tools = List(), systemPrompt = ""),
            resources = resources,
            wsSend = json => wsEvents.update(_ :+ json),
            depth = 0,
            sessionId = Some(parentSid),
            sessionName = Some("v7-planwait")
          ),
          parentSid
        )
        _ <- resources.agentRegistry.update(
          _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
        )
        // Turn 1: normal user turn → idle.
        _ <- parentRef ! AgentCommand.UserInput("kick off", None, Some("v7-1"))
        _ <- waitUntil(20.seconds)(requests.get.map(_.size == 1))
        // Enter plan mode: plan agent plans (mock returns plan text) → planReady.
        // 30s 预算：并行节点全量测试抢 CPU 时 mock LLM 调度的尾部延迟（实测可达 20s+）。
        _ <- parentRef ! AgentCommand.StartPlan("plan the work")
        _ <- waitUntil(30.seconds)(wsEvents.get.map(_.exists(_.hcursor.downField("type").as[String].toOption.contains("planReady"))))
        // ── planWaiting window: delegate result + user input arrive ──
        _ <- parentRef ! AgentCommand.ExternalEvent(
          source = "delegate",
          eventType = "completed",
          payload = "V7_DELEGATE_RESULT_MARKER",
          metadata = JsonObject("agentName" -> "Worker".asJson),
          correlationId = Some("delegate-x1")
        )
        _ <- parentRef ! AgentCommand.UserInput("V7_USER_MSG_MARKER", None, Some("v7-2"))
        // Buffered — must NOT wake the parent while planning.
        _ <- IO.sleep(1.second)
        // NOTE: the plan agent SHARES the parent sessionId (PlanAgent.spawn
        // sessionId=parentSessionId), so parent turns are identified by
        // content — the turn-1 user text "kick off" rides the history of
        // every PARENT turn but not the plan agent's planning request.
        preCount <- requests.get.map(_.count(_.messages.exists(_.textContent.contains("kick off"))))
        _ = assertEquals(preCount, 1, "buffered messages must not open parent turns during planWaiting")
        // Exit the plan window: cancel → F1 drain injects both markers.
        _ <- parentRef ! AgentCommand.PlanCancelled
        _ <- waitUntil(30.seconds)(requests.get.map(_.count(_.messages.exists(_.textContent.contains("kick off"))) >= 2))
        _ <- IO.sleep(500.millis)
        reqs <- requests.get
        evts <- wsEvents.get
      yield (
        reqs.filter(_.messages.exists(_.textContent.contains("kick off"))).reverse,
        evts.reverse
      )
      val result = program.unsafeRunSync()
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
      result
    finally
      PathUtil.setDataRoot(originalRoot)

  test("R1 GREEN: delegate result + user message buffered during planWaiting, injected on plan cancel (F1 drain)") {
    val (reqs, evts) = runScenario(
      Stream(StreamChunk.TextDelta("## Plan\nstep 1"), StreamChunk.Done(None, None))
    )
    assert(clue(reqs.size >= 2), s"post-plan drain must open a continuation turn (parent requests=${reqs.size})")
    val secondTurn = reqs(1)
    val texts = secondTurn.messages.map(_.textContent).mkString("\n")
    assert(clue(texts).contains("V7_DELEGATE_RESULT_MARKER"), s"delegate result must reach the post-plan turn:\n$texts")
    assert(clue(texts).contains("V7_USER_MSG_MARKER"), s"user message buffered during planning must reach the post-plan turn:\n$texts")
    assert(
      clue(evts).exists(_.hcursor.downField("type").as[String].toOption.contains("planEnd")),
      "planEnd must still be emitted (existing plan semantics untouched)"
    )
  }

end PlanWaitingBufferSpec
