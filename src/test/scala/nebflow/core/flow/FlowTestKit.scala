package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.effect.std.{Dispatcher, Semaphore}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.Json
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.*
import nebflow.core.compact.HistoryArchiver
import nebflow.core.tools.FileLockManager
import nebflow.core.task.{Task, TaskCreateInput, TaskStore, TaskUpdateInput}
import nebflow.gateway.{RateLimiter, SessionStore}
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend
import nebflow.llm.{
  ThinkingConfig,
  NebflowServiceConfig,
  ServiceLlmConfig,
  ModelChainConfig,
  ModelCandidate,
  ProviderRegistry,
  ProviderHealthMonitor
}
import nebflow.shared.*

import scala.concurrent.duration.*

// ============================================================
// FlowTestKit — lightweight test infrastructure for flow tests
// ============================================================

object FlowTestKit:

  def create(llm: LlmHandle[IO] = FakeLlm.passing): IO[FlowTestKit] =
    Dispatcher.parallel[IO].allocated.flatMap { (dispatcher, _) =>
      IO(os.temp.dir(prefix = "nebflow-test")).flatMap { tempDir =>
        PathUtil.setDataRoot(tempDir)
        // Disable LLM log writing in tests: it uses a fake LLM, so the log is
        // useless, and pruneOldLogs reads stale real-world log files line-by-line
        // which OOMs the 1GB test heap. This is environmental, not flow logic.
        nebflow.core.LlmLogWriter.setEnabled(false)
        val setup: IO[Unit] = IO.blocking {
          os.makeDir.all(tempDir / "agents" / "Explorer")
          os.makeDir.all(tempDir / "agents" / "Nebula")
          os.makeDir.all(tempDir / "sessions")
          os.makeDir.all(tempDir / "tasks")
        } *> writeAgentJson(tempDir, "Explorer", List("Read", "Glob", "Grep"))
          *> writeAgentJson(tempDir, "Nebula", List("*"))

        setup.flatMap { _ =>
          for
            rateLimiter <- RateLimiter.create(1000, 60_000)
            fileChangeTracker <- FileChangeTracker.create(tempDir.toString)
            fileLockManager <- FileLockManager.create
            askSemaphore <- Semaphore[IO](1)
            thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
            modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
            voiceMuted <- Ref.of[IO, Boolean](false)
          yield
            val agentLibrary = AgentLibrary(tempDir / "agents")
            val sessionStore = SessionStore(tempDir / "sessions", tempDir / "tasks")
            val taskStore = new InMemoryTaskStore
            val historyArchiver = HistoryArchiver.fileSystem(tempDir / "archive")
            val actorSystem = ActorSystem("test-device")
            val dummyConfig = NebflowServiceConfig(
              llm = ServiceLlmConfig(
                providers = Map.empty,
                model = ModelChainConfig(default = "test/model")
              )
            )
            val configRef = Ref.unsafe[IO, NebflowServiceConfig](dummyConfig)
            val providerRegistry = new ProviderRegistry(
              configRef,
              null.asInstanceOf[StreamBackend[IO, Fs2Streams[IO]]]
            )
            val healthMonitor = ProviderHealthMonitor(providerRegistry)
            val resources = SharedResources(
              llm,
              dispatcher,
              sessionStore,
              tempDir,
              thinkingRef,
              rateLimiter,
              fileChangeTracker,
              Defaults.ContextWindow,
              agentLibrary,
              askSemaphore,
              taskStore,
              historyArchiver,
              fileLockManager,
              modelOverrides,
              providerRegistry,
              healthMonitor,
              actorSystem,
              voiceMutedRef = voiceMuted
            )
            FlowTestKit(tempDir, resources, actorSystem)
        }
      }
    }

  private def writeAgentJson(root: os.Path, name: String, tools: List[String]): IO[Unit] =
    IO.blocking {
      val dir = root / "agents" / name
      os.write.over(dir / "agent.json", Json.obj("name" -> name.asJson, "tools" -> tools.asJson).noSpaces)
      os.write.over(dir / "system.md", s"You are $name. Follow instructions precisely.")
    }.void

end FlowTestKit

final class FlowTestKit(
  val tempDir: os.Path,
  val resources: SharedResources,
  val actorSystem: ActorSystem
):

  /** Spawn a FlowTreeActor — the full mount/trigger path. */
  def spawnTreeActor(parentAgentRef: ActorRef[AgentCommand]): IO[ActorRef[TreeCommand]] =
    val treeConfig = FlowTreeActor.TreeConfig(
      parentAgentRef = parentAgentRef,
      wsSend = None,
      sessionId = Some("test-session"),
      resources = resources,
      projectRoot = tempDir.toString,
      safetyMode = "yolo",
      disableFileWatcher = true
    )
    actorSystem.spawn(
      FlowTreeActor(treeConfig),
      s"flow-tree-${java.util.UUID.randomUUID().toString.take(8)}"
    )

  /** Write a flow YAML definition to the temp flows directory. */
  def writeFlowYaml(flowName: String, yaml: String): IO[Unit] =
    IO.blocking {
      val dir = tempDir / "flows"
      if !os.exists(dir) then os.makeDir.all(dir)
      os.write.over(dir / s"$flowName.yaml", yaml)
    }.void

  /** Mount a flow through FlowTreeActor (E2E path). Returns the pipeline name. */
  def mountFlow(treeRef: ActorRef[TreeCommand], flowName: String): IO[Unit] =
    FlowDefLoader.load(flowName).flatMap {
      case Some(defn) => treeRef ! TreeCommand.MountBranch(defn, None, None)
      case None => IO.raiseError(new RuntimeException(s"Flow '$flowName' not found"))
    }

  /** Trigger a mounted flow through FlowTreeActor (E2E path). */
  def triggerFlow(treeRef: ActorRef[TreeCommand], name: String, input: String): IO[Unit] =
    treeRef ! TreeCommand.TriggerPipeline(name, input, None)

  /** Spawn a PipelineActor directly (for low-level tests). */
  def spawnPipeline(
    name: String,
    flowDef: FlowDef,
    parentAgentRef: ActorRef[AgentCommand]
  ): IO[ActorRef[PipelineActor.PipelineCommand]] =
    actorSystem.spawn(
      PipelineActor(
        PipelineActor.PipelineConfig(
          name = name,
          flowName = flowDef.name,
          flowDef = flowDef,
          parentAgentRef = parentAgentRef,
          wsSend = None,
          sessionId = Some("test-session"),
          resources = resources,
          projectRoot = tempDir.toString,
          safetyMode = "yolo",
          expectsMail = false
        )
      ),
      s"pipe-$name-${java.util.UUID.randomUUID().toString.take(8)}"
    )

  def spawnParentAgent(): IO[ActorRef[AgentCommand]] =
    def ignore: Behavior[AgentCommand] =
      Behaviors.receiveMessage(_ => IO.pure(ignore))
    actorSystem.spawn(ignore, s"parent-${java.util.UUID.randomUUID().toString.take(8)}")

  /** Spawn a parent agent that records ExternalEvents it receives. Lets a test
   *  assert on the flow's completion notification (e.g. pass/fail, payload). */
  def spawnCapturingParent(): IO[(ActorRef[AgentCommand], IO[List[AgentCommand.ExternalEvent]])] =
    val received = Ref.unsafe[IO, List[AgentCommand.ExternalEvent]](Nil)
    def behavior: Behavior[AgentCommand] =
      Behaviors.receiveMessage {
        case ev: AgentCommand.ExternalEvent =>
          received.update(_ :+ ev).as(behavior)
        case _ => IO.pure(behavior)
      }
    actorSystem.spawn(behavior, s"capture-${java.util.UUID.randomUUID().toString.take(8)}")
      .map(ref => (ref, received.get))

  def writeMemory(flowName: String, content: String): IO[Unit] =
    FlowMemoryStore.save(flowName, content)

  def readMemory(flowName: String): IO[String] =
    FlowMemoryStore.load(flowName)

  /** Read all pipeline states for this session. */
  def pipelineStates: IO[List[PipelineStateStore.PipelineState]] =
    PipelineStateStore.loadAll("test-session")

  def cleanup(): IO[Unit] =
    actorSystem.stopAll *> IO.blocking(os.remove.all(tempDir)).void

end FlowTestKit

// ============================================================
// FakeLlm — canned LLM responses for testing
// ============================================================

object FakeLlm:

  private def meta(agentId: String) = LlmMeta(
    sessionId = "test",
    agentId = agentId,
    providerId = "test",
    model = "test/model",
    durationMs = 0,
    fallbackChain = None,
    contextWindow = Some(Defaults.ContextWindow)
  )

  private def resp(reply: String, agentId: String) =
    LlmResponse(reply, Nil, None, meta(agentId))

  private def streamFrom(reply: String, agentId: String): fs2.Stream[IO, StreamChunk] =
    fs2.Stream(
      StreamChunk.TextDelta(reply),
      StreamChunk.Done(Some("stop"), None, Some(meta(agentId)))
    )

  /** All steps reply briefly; the verify node reports PASS via Mail (the single
   *  verdict source) by completing the pending Deferred; reflect returns memory. */
  val passing: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      val allText = req.messages.map(_.textContent).mkString("\n")
      val isVerify = allText.contains("Verification Criteria")
      val isReflect = allText.contains("updated memory")
      val isEvolve = allText.contains("flow optimization") || allText.contains("propose specific improvements")
      IO {
        println(
          s"[FakeLlm.passing] agentId=${req.agentId} msgCount=${req.messages.size} isVerify=$isVerify isReflect=$isReflect isEvolve=$isEvolve first200=${allText.take(200)}"
        )
      } *>
        (
          if isVerify then
            // Simulate the verify agent calling Mail(type=verify, passed=true):
            // complete the Deferred registered by PipelineActor.
            FlowVerifyRegistry.completeAllForTest(VerifyResult(pass = true, "All checks passed.")).void
          else IO.unit
        ) *>
        IO.pure(
          if isVerify then resp("Analysis complete. Reported PASS via Mail.", req.agentId)
          else if isReflect then resp("# Flow Memory: test\n\n## Patterns\n- Test pattern learned.\n", req.agentId)
          else if isEvolve then
            // Return the YAML unchanged (extract from prompt)
            val yamlStart = allText.indexOf("=== Current Flow Definition ===")
            val yamlEnd = allText.indexOf("=== End Definition ===")
            if yamlStart >= 0 && yamlEnd > yamlStart then
              resp(allText.substring(yamlStart + 31, yamlEnd).trim, req.agentId)
            else resp("NO_CHANGE", req.agentId)
          else resp("Task completed.", req.agentId)
        )
    end send
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.eval(send(req)).flatMap { r => streamFrom(r.reply, req.agentId) }

  /** Verify node reports FAIL via Mail (single verdict source). */
  val failing: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      val allText = req.messages.map(_.textContent).mkString("\n")
      val isVerify = allText.contains("Verification Criteria")
      (
        if isVerify then
          FlowVerifyRegistry.completeAllForTest(VerifyResult(pass = false, "output too brief")).void
        else IO.unit
      ) *>
        IO.pure(
          if isVerify then resp("Issues found. Reported FAIL via Mail.", req.agentId)
          else if allText.contains("updated memory") then
            resp("# Flow Memory: test\n\n## Pitfalls\n- Brief outputs fail.\n", req.agentId)
          else resp("ok", req.agentId)
        )
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.eval(send(req)).flatMap { r => streamFrom(r.reply, req.agentId) }

  /** Verify node never calls Mail — simulates an agent ignoring instructions.
   *  With Mail as the single source, this yields a FAIL verdict (no Mail = fail). */
  val noVerdict: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.pure(resp("Found some interesting patterns in the code.", req.agentId))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): fs2.Stream[IO, StreamChunk] =
      fs2.Stream.eval(send(req)).flatMap { r => streamFrom(r.reply, req.agentId) }

end FakeLlm

// ============================================================
// InMemoryTaskStore — trivial TaskStore for tests
// ============================================================

private class InMemoryTaskStore extends TaskStore:
  private val tasks = Ref.unsafe[IO, Map[(String, String), Task]](Map.empty)
  private var counter = 0

  def create(sessionId: String, input: TaskCreateInput): IO[String] = IO { counter += 1; s"task-$counter" }
  def get(sessionId: String, taskId: String): IO[Option[Task]] = tasks.get.map(_.get((sessionId, taskId)))
  def list(sessionId: String): IO[List[Task]] = tasks.get.map(_.view.filterKeys(_._1 == sessionId).values.toList)
  def listActive(sessionId: String): IO[List[Task]] = list(sessionId)
  def update(sessionId: String, taskId: String, updates: TaskUpdateInput): IO[Option[Task]] = IO.pure(None)
  def delete(sessionId: String, taskId: String): IO[Boolean] = tasks.update(_ - ((sessionId, taskId))).as(true)
  def deleteAll(sessionId: String): IO[Unit] = tasks.update(_.view.filterKeys(_._1 != sessionId).toMap).void
