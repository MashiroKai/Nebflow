package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.*
import nebflow.core.ask.*
import nebflow.core.compact.HistoryArchiver
import nebflow.core.hooks.{HookEngine, HooksConfigLoader}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{ContentBlock, FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig, ToolCall}
import fs2.Stream
import scala.concurrent.duration.*

class AskDelegationIntegrationSpec extends FunSuite:
  private class OneCall(call: ToolCall, requests: Ref[IO, List[LlmRequest]]) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new Exception("unexpected send"))
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      Stream.eval(requests.modify(xs => (xs :+ req, xs.isEmpty))).flatMap { first =>
        if first then Stream(StreamChunk.ToolCallChunk(call), StreamChunk.Done(None, None))
        else Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
      }

  private def runCase(remote: Boolean, bound: Boolean, edit: Boolean = false, approval: Boolean = false, isolateReview: Boolean = false): Unit =
    val system = ActorSystem("ask-delegation-integration")
    val root = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLog = nebflow.core.LlmLogWriter.isEnabled
    PathUtil.setDataRoot(root / "data")
    nebflow.core.LlmLogWriter.setEnabled(false)
    try
      val file = root / "bound.md"
      val other = root / "other.md"
      os.write(file, "alpha")
      os.write(other, "alpha")
      val registry = new AskSessionRegistry(root / "data")
      val binding = registry.bind(file.toString, "bound.md").unsafeRunSync()
      AskEditReviews.resetForTest(binding.id)
      if isolateReview then
        val change = AskEditReviews.applyChange(binding.id, binding, binding.file, "ask_write", _ => Right("bound-changed")).unsafeRunSync()
        assert(change.isRight)
      val sources = new AskContextSources:
        def fileStat(p: String) = IO.pure(None)
        def fileText(p: String) = IO.pure(None)
        def graphNeighbors(p: String) = IO.pure(Nil)
        def recallHits(q: String) = IO.pure(Nil)
        def recallDbMtime = IO.pure(0L)
        def graphVersion = IO.pure("")
      AskGatewayServices.install(AskServices(registry, sources, new AskPromptCache))
      val hookConfig = root / nebflow.shared.Branding.configFileName
      os.write(hookConfig, """{"hooks":{"PostToolUse":[{"matcher":"Write|Edit","hooks":[{"type":"command","command":"echo '{\"additional_context\":\"WRITE-HOOK-PROBE\"}'","timeout":10}]}]}}""")
      if approval then os.write.over(PathUtil.configJsonWritePath(PathUtil.dataRoot), """{"safety":{"defaultMode":"confirm-edits"}}""", createFolders = true)
      val target = if bound || remote then file else other
      val device = if remote then "peer-unavailable" else "local"
      val input = if edit then JsonObject("path" -> target.toString.asJson, "old_str" -> "alpha".asJson, "new_str" -> "changed".asJson, "device" -> device.asJson)
        else JsonObject("path" -> target.toString.asJson, "content" -> "changed".asJson, "device" -> device.asJson)
      val call = ToolCall("ask-call-1", if edit then "ask_edit" else "ask_write", input)
      val program = for
        requests <- IO.ref(List.empty[LlmRequest])
        events <- IO.ref(List.empty[Json])
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        limiter <- RateLimiter.create()
        tracker <- nebflow.core.FileChangeTracker.create(root.toString)
        locks <- FileLockManager.create
        thinking <- IO.ref(ThinkingConfig())
        overrides <- IO.ref(Map.empty[String, ModelCandidate])
        muted <- IO.ref(false)
        resources = SharedResources(
          llm = OneCall(call, requests), dispatcher = dispatcher,
          sessionStore = SessionStore(root / "sessions", root / "tasks-ui"), projectRoot = root,
          thinkingConfigRef = thinking, rateLimiter = limiter, fileChangeTracker = tracker,
          contextWindow = 100000, agentLibrary = new AgentLibrary(root / "agents"), taskStore = FileTaskStore,
          historyArchiver = HistoryArchiver.fileSystem(root / "archives"), fileLockManager = locks,
          sessionModelOverrides = overrides, providerRegistry = null, healthMonitor = ProviderHealthMonitor(null),
          actorSystem = system, subAgentTaskStore = new SubAgentTaskStore(root / "subagent-tasks"),
          voiceMutedRef = muted, hookEngine = HookEngine(HooksConfigLoader.load(root))
        )
        definition = nebflow.actor.AgentDef(name = "ask", description = "test", tools = Nil, category = "standalone")
        card <- Deferred[IO, Json]
        hub <- system.spawn(InteractionHub(), "test-hub")
        _ <- resources.interactionHubRef.set(Some(hub))
        _ <- hub ! InteractionHubCommand.RegisterRoot(binding.id, (j: Json) =>
          if j.hcursor.downField("type").as[String].contains("askPermission") then card.complete(j).void else IO.unit)
        actor <- system.spawn(AgentActor(definition, resources, j => events.update(_ :+ j), 0, sessionId = Some(binding.id), projectRoot = Some(root.toString)), binding.id)
        _ <- resources.agentRegistry.update(_ + (binding.id -> AgentRecord(binding.id, actor, AgentKind.Root, binding.id, None)))
        _ <- actor ! AgentCommand.ImmediateInput("execute", fromUser = true)
        _ <- if approval then for
          frame <- card.get.timeout(20.seconds)
          _ = assertEquals(frame.hcursor.get[String]("toolName").toOption, Some(if edit then "Edit" else "Write"))
          _ = assertEquals(os.read(target), "alpha")
          requestId <- IO.fromEither(frame.hcursor.get[String]("requestId"))
          _ <- hub ! InteractionHubCommand.Answered(InteractionAnswered(requestId, binding.id, Json.obj("approved" -> true.asJson)))
        yield () else IO.unit
        _ <- Stream.repeatEval(requests.get).filter(_.size >= 2).head.compile.drain.timeout(30.seconds)
        reqs <- requests.get
        frames <- events.get
        _ <- resources.sessionStore.flushPendingMessages
        persisted <- SessionStore(root / "sessions", root / "tasks-ui").loadMessagesForSession(binding.id)
      yield
        val blocks = reqs(1).messages.flatMap(_.content.toOption.toList.flatten)
        val use = blocks.collectFirst { case u: ContentBlock.ToolUse if u.id == call.id => u }
        assert(use.nonEmpty, s"missing original ToolUse: $blocks")
        assertEquals(use.get.name, call.name)
        val savedUse = persisted.flatMap(_.content.toOption.toList.flatten).collectFirst { case u: ContentBlock.ToolUse if u.id == call.id => u }
        assertEquals(savedUse, use)
        if !bound || remote then
          assertEquals(use.get.delegatedTo.flatMap(_.hcursor.downField("tool").as[String].toOption), Some(if edit then "Edit" else "Write"))
          if isolateReview then
            assertEquals(AskEditReviews.reviewFor(binding.id).map(_.path), Some(file.toNIO.toRealPath().toString))
            assertEquals(AskEditReviews.reviewFor(binding.id).map(_.changes.size), Some(1))
            assert(AskEditReviews.reject(binding.id, None).unsafeRunSync().isRight)
            assertEquals(os.read(file), "alpha")
            assertEquals(os.read(other), "changed")
          else assertEquals(AskEditReviews.reviewFor(binding.id), None)
          if remote then assertEquals(os.read(file), "alpha") else assertEquals(os.read(other), "changed")
        else
          assertEquals(use.get.delegatedTo, None)
          assert(AskEditReviews.reviewFor(binding.id).nonEmpty)
          assertEquals(os.read(file), "changed")
        val result = blocks.collectFirst { case r: ContentBlock.ToolResult if r.toolUseId == call.id => r }
        assert(result.nonEmpty)
        if remote then assert(result.get.content.contains("remote executor is unavailable"))
        if !bound && !remote then assert(result.get.content.contains("WRITE-HOOK-PROBE"), result.get.content)
      program.unsafeRunSync()
    finally
      AskGatewayServices.uninstall()
      system.stopAll.attempt.void.unsafeRunSync()
      nebflow.core.LlmLogWriter.setEnabled(prevLog)
      PathUtil.setDataRoot(prevRoot)
      try os.remove.all(root)
      catch case _: java.nio.file.DirectoryNotEmptyException => ()

  test("拒绝绑定审阅不回滚非绑定文件") { runCase(remote = false, bound = false, isolateReview = true) }
  test("非绑定 ask_edit 经普通 Edit 审批和 hook") { runCase(remote = false, bound = false, edit = true, approval = true) }
  test("本机非绑定写入走普通路径") { runCase(remote = false, bound = false) }
  test("远端未证明绑定走普通路径") { runCase(remote = true, bound = false) }
  test("本机绑定文件保留审阅") { runCase(remote = false, bound = true) }
