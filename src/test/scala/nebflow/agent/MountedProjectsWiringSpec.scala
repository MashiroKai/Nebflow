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
import nebflow.core.project.{FlowMapStore, NodeEngine, ProjectDef, ProjectRuntime, ProjectRuntimeRegistry}
import nebflow.core.task.{FileTaskStore, TaskCreateInput}
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import fs2.Stream

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.*

/**
 * Mounted-projects injection wiring (progressive disclosure 2026-09-07).
 *
 * Pins AgentCore's actual wiring on a real AgentActor (Nebula def) + mock LLM:
 *  ① first (lifecycle) turn → systemStable carries "# Mounted Projects" with
 *     the sorted registry entries (empty list rendered as 当前无挂载项目);
 *  ② a mid-session mount triggers the non-lifecycle snapshot delta → the next
 *     request's messages carry a "projects" reminder with a "+ <project>" line.
 *
 * The `mountedProjectsText` read is gated on `isRootAgent` (AgentCore), so the
 * section/reminder never reaches dispatcher / node sessions.
 */
class MountedProjectsWiringSpec extends CatsEffectSuite:

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

  /** 一次性挂载项目 runtime（NodeAcceptanceSpec mountProject 模式；各项目独立
    * workspace，避免 FlowMapStore.open 的 <ws>/.nebflow/flow-map.json 互相覆盖）。 */
  private def mountProject(
    name: String,
    description: Option[String],
    tmp: os.Path,
    system: ActorSystem,
    res: SharedResources
  ): IO[ProjectRuntime] =
    val ws = tmp / s"ws-$name"
    for
      _ <- IO.blocking(os.makeDir.all(ws))
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, description = description, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

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

  test("root Nebula sees mounted projects in systemStable; a mid-session mount produces a projects reminder"):
    val system = ActorSystem("mounted-projects-wiring")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val sid = "mounted-projects-root"
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, CaptureLlm(requests))
        _ <- mountProject("nebflow", Some("Scala version"), tmp, system, resources)
        _ <- mountProject("czt-project", None, tmp, system, resources)
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
            sessionName = Some("mounted-projects-wiring")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(
          _ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None))
        )
        // ── ① first turn = lifecycle rebuild → systemStable carries the list ──
        _ <- actorRef ! AgentCommand.UserInput("hello", None, Some("c1"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.nonEmpty))
        req1 <- requests.get.map(_.head)
        // ── ② mount a new project mid-session → next turn delta reminder ──
        _ <- mountProject("voice-recognition-test", Some("STT"), tmp, system, resources)
        _ <- actorRef ! AgentCommand.UserInput("mount", None, Some("c2"))
        _ <- waitUntil(15.seconds)(requests.get.map(_.size >= 2))
        req2 <- requests.get.map(_.apply(1))
      yield
        val stable1 = req1.systemStable.getOrElse("")
        assert(stable1.contains("# Mounted Projects"), s"root systemStable must carry the section:\n$stable1")
        assert(stable1.contains("- nebflow: Scala version"), s"mounted project w/ description present:\n$stable1")
        assert(stable1.contains("- czt-project"), s"mounted project w/o description present:\n$stable1")
        assert(!stable1.contains("voice-recognition-test"), s"project mounted later must not be in the first systemStable:\n$stable1")
        // Mid-session mount → non-lifecycle delta → projects reminder in request messages
        assert(textOf(req2).contains("Mounted projects changed"), s"expected projects reminder:\n${textOf(req2)}")
        assert(textOf(req2).contains("+ voice-recognition-test: STT"), s"expected + delta line:\n${textOf(req2)}")
      program.unsafeRunSync()
    finally
      ProjectRuntimeRegistry.clear.unsafeRunSync()
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)

end MountedProjectsWiringSpec
