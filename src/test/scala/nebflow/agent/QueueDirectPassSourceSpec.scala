package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import fs2.Stream

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*

/**
 * 队列直通/TOOL 卡诊断（`~/.nebflow/docs/Nebflow/
 * 20260911_102427_queue-direct-pass-tool-card-diagnosis__chain-n-cacccb07.md`）
 * ② 根因修法的 WIRING pins —— 真实 AgentActor + mock LLM：
 *
 *   ① 真人文本走 `ImmediateInput(fromUser = true)` 到 **idle** agent
 *      ⇒ 落成一条无 `source` 的 user 消息（不再进 `clientMessageId=None ⇒
 *      source="tool"` 兜底），且**不产** `{type:"user",injected:true}` 帧
 *      （= 不产 TOOL 卡），且该轮仍是 real user turn（time 提醒照常注入）。
 *   ② 负对照（既有语义不许被改坏）：同一路径 `fromUser = false` 且无 source
 *      的服务端注入 ⇒ 仍标 `source="tool"` + 仍发注入帧。
 *   ③ 回归：Mail / Node / Dispatcher 类**带 source** 的注入（fromUser=false）
 *      ⇒ 消息仍带该 source，且仍发注入气泡帧。
 *
 * 配套：`CompactionQueueStoreSpec`（入队↔恢复保真人位）、以及
 * `WebSocketRoutes` 三个入口的显式表态（编译期强制，无默认值可蒙混）。
 */
class QueueDirectPassSourceSpec extends CatsEffectSuite:

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

  /** Pin the Nebula def on disk (empty agents dir falls back to Seeds.Nebula). */
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
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def textOf(req: LlmRequest): String =
    req.messages.map(_.textContent).mkString("\n")

  /** `{type:"user",injected:true,...}` frames captured from the actor's wsSend. */
  private def injectedUserFrames(frames: List[Json]): List[Json] =
    frames.filter { j =>
      val hc = j.hcursor
      hc.downField("type").as[String].toOption.contains("user") &&
      hc.downField("injected").as[Boolean].toOption.contains(true)
    }

  private def lastUserSource(req: LlmRequest): Option[String] =
    req.messages.filter(_.role == nebflow.shared.MessageRole.User).lastOption.flatMap(_.source)

  test("① 真人文本走 ImmediateInput(fromUser=true) 到 idle agent：无 source、无 TOOL 注入帧、仍是 real user turn"):
    val system = ActorSystem("qdp-fromuser")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "qdp-human"
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        frames <- IO.ref(List.empty[Json])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
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
            wsSend = j => frames.update(_ :+ j),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("qdp-fromuser")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        // 真人腿：dispatchUserText 产出的正是这一形态（fromUser=true, source=None）
        _ <- actorRef ! AgentCommand.ImmediateInput("审查一下，官网，github 文档", fromUser = true)
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        req <- requests.get.map(_.head)
        fs <- frames.get
      yield
        assertEquals(lastUserSource(req), None, s"真人输入不得带 source（TOOL 兜底未修？）: ${lastUserSource(req)}")
        assert(
          injectedUserFrames(fs).isEmpty,
          s"真人输入不得产 {type:user,injected:true} 帧（= TOOL 卡）: ${injectedUserFrames(fs).mkString(" | ")}"
        )
        // real user turn 语义（isRealUserTurn = isUserTurn && source.isEmpty）：
        // time 提醒照常注入（诊断 §3「Reminder 同源」）
        assert(textOf(req).contains("Current time"), s"真人轮仍应注入 time 提醒:\n${textOf(req)}")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  test("② 负对照：idle 下 fromUser=false 且无 source 的服务端注入仍落 tool 兜底 + 注入帧"):
    val system = ActorSystem("qdp-tool-fallback")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "qdp-tool"
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        frames <- IO.ref(List.empty[Json])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
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
            wsSend = j => frames.update(_ :+ j),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("qdp-tool-fallback")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        _ <- actorRef ! AgentCommand.ImmediateInput("legacy tool-originated text", fromUser = false)
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        req <- requests.get.map(_.head)
        fs <- frames.get
      yield
        assertEquals(lastUserSource(req), Some("tool"), "无 source 的非真人注入必须仍落 tool 兜底（既有语义）")
        val srcs = injectedUserFrames(fs).flatMap(_.hcursor.downField("source").as[String].toOption)
        assertEquals(srcs, List("tool"), "既有注入气泡行为必须保持（蓝卡仍在）")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

  test("③ 回归：带 source 的服务端注入（Mail / Node / Dispatcher 形态）仍带 source 且仍发注入气泡"):
    val system = ActorSystem("qdp-inject-regression")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "qdp-inject"
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        frames <- IO.ref(List.empty[Json])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
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
            wsSend = j => frames.update(_ :+ j),
            depth = 0,
            sessionId = Some(sid),
            sessionName = Some("qdp-inject-regression")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        // Mail 形态（MailTool.scala 的投递形态：source=mail + delivery=immediate）
        _ <- actorRef ! AgentCommand.ImmediateInput(
          "mail-delivered task",
          source = Some("mail"),
          eventType = Some("task"),
          sender = Some("Backend"),
          delivery = Some("immediate"),
          fromUser = false
        )
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        req <- requests.get.map(_.head)
        fs <- frames.get
      yield
        assertEquals(lastUserSource(req), Some("mail"), "Mail 注入必须仍带 source（回归：注入气泡语义不变）")
        val srcs = injectedUserFrames(fs).flatMap(_.hcursor.downField("source").as[String].toOption)
        assertEquals(srcs, List("mail"), "Mail 注入必须仍发注入气泡帧")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

end QueueDirectPassSourceSpec
