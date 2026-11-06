package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord, messages}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}
import fs2.Stream

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*

/**
 * 生产装配链契约（cache-warmup 批 2026-11-06，作者补充令 §二.1）——
 * **真 AgentActor + mock LLM**：AgentSessionExecution 的持久/临时边界
 * （`contextMsg/branchMsg/maintenanceMsg` = REQUEST-ONLY）→ `LlmRequest` 装配。
 *
 * 契约：
 *   C-CHAIN-1 REQUEST-ONLY 提醒（本规格用真实 schedule 提醒驱动：种 pending
 *      定时任务 ⇒ `collectAllIO` 产 schedule 类 contextMsg）**不得**出现在
 *      `LlmRequest.messages`（持久历史）里——它们要落在线上也只能走
 *      `requestOnlyContext`（适配器再排在持久历史断点之后）。
 *   C-CHAIN-2 持久历史不变量保持：最后一条 user 消息 = 本轮输入（既有
 *      lastUserSource 语义）、上一轮输入在本轮 messages 里（历史只追加）。
 *   C-CHAIN-3 请求级时间锚（systemDynamic）照常非空。
 *
 * 缺陷史：修复前这族提醒直接拼进 messages（尾断点落提醒上 ⇒ 缓存写到
 * H+R ⇒ 下一轮必 miss）；变异红线 = 把拼接改回 messages ⇒ 本规格当场红。
 *
 * 运行入口：`sbt "Test/testOnly nebflow.agent.RequestContextAssemblySpec"`
 * 归属 CI：既有 `sbt test` 家族。
 */
class RequestContextAssemblySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private class CaptureLlm(requests: Ref[IO, List[LlmRequest]]) extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(_ :+ req)).drain ++
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks-ui"),
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

  /** Pin the Nebula def on disk (empty agents dir falls back to Seeds.RootAgent). */
  private def seedNebula(tmp: os.Path): Unit =
    val dir = tmp / "agents" / "Nebula"
    os.makeDir.all(dir)
    os.write.over(
      dir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"wiring root","tools":["Read"]}"""
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 100.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def textOf(req: LlmRequest): String =
    req.messages.map(_.textContent).mkString("\n")

  private def lastUserText(req: LlmRequest): String =
    req.messages.filter(_.role == nebflow.shared.MessageRole.User).lastOption.map(_.textContent).getOrElse("")

  /** 种一个真实 pending 定时任务 ⇒ 真用户轮 `collectAllIO` 产 schedule 类提醒。 */
  private def seedPendingSchedule(resources: SharedResources, sid: String): IO[Unit] =
    resources.scheduledTaskStore.addTask(
      nebflow.core.scheduler.ScheduledTask(
        id = "cache-assembly-fixture-1",
        sessionId = sid,
        content = "cache-assembly-fixture task (report later)",
        triggerAt = System.currentTimeMillis() + 3600_000L,
        createdAt = System.currentTimeMillis()
      )
    )

  test("① REQUEST-ONLY schedule 提醒不进持久历史，只走 requestOnlyContext；持久历史不变量保持"):
    val system = ActorSystem("rca-request-only")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "rca-root"
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        _ <- seedPendingSchedule(resources, sid)
        nebulaDef = AgentDef(
          name = "Nebula",
          description = "root under test",
          tools = List("Read"),
          systemPrompt = ""
        )
        actorRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("rca-request-only")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        _ <- actorRef ! AgentCommand.ImmediateInput("first turn", fromUser = true)
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        req1 <- requests.get.map(_.head)
        _ <- actorRef ! AgentCommand.ImmediateInput("second turn", fromUser = true)
        _ <- waitUntil(20.seconds)(requests.get.map(_.length >= 2))
        reqs <- requests.get
      yield
        val req2 = reqs(1)
        // C-CHAIN-1: schedule 提醒绝不进持久历史（两轮）
        assert(!textOf(req1).contains("Pending schedules"),
          s"turn1: request-only schedule reminder leaked into persisted messages:\n${textOf(req1).take(800)}")
        assert(!textOf(req2).contains("Pending schedules"),
          s"turn2: request-only schedule reminder leaked into persisted messages:\n${textOf(req2).take(800)}")
        // C-CHAIN-1': 它必须走 requestOnlyContext（本轮真在场——种了 pending 任务）
        assert(
          req1.requestOnlyContext.map(_.textContent).mkString("\n").contains("Pending schedules"),
          s"turn1: schedule reminder must ride in requestOnlyContext, got: ${req1.requestOnlyContext.map(_.textContent).mkString(" | ").take(400)}"
        )
        assert(
          req2.requestOnlyContext.map(_.textContent).mkString("\n").contains("Pending schedules"),
          "turn2: per-turn reminder must be re-emitted in requestOnlyContext (still pending)"
        )
        // C-CHAIN-2: 持久历史不变量——最后一条 user = 本轮输入；上一轮输入仍在
        assertEquals(lastUserText(req1), "first turn", "turn1: last user message must be the trigger input")
        assertEquals(lastUserText(req2), "second turn", "turn2: last user message must be the trigger input")
        assert(textOf(req2).contains("first turn"), "turn2: persisted history must retain turn1 input")
        // C-CHAIN-3: 请求级时间锚照常
        assert(req1.systemDynamic.exists(_.contains("epoch_ms")), "turn1: request-level clock anchor must be present")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    end try

end RequestContextAssemblySpec
