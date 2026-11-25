package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.*
import nebflow.core.ask.*
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, ShellSession}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*
import scala.concurrent.duration.*

/** Same workspace and ordinary pipeline; Ask differs only in its session definition and extras. */
class AskStandaloneParitySpec extends FunSuite:
  private def compare(tool: String, command: String = "printf parity-bash", allowError: Boolean = false, sandboxEnabled: Boolean = false): Unit =
    val root = os.temp.dir(prefix = "ask-ordinary-parity-")
    val oldRoot = PathUtil.dataRoot
    val oldLog = nebflow.core.LlmLogWriter.isEnabled
    val system = ActorSystem("ask-standalone-parity")
    PathUtil.setDataRoot(root / "data")
    nebflow.core.LlmLogWriter.setEnabled(false)
    val file = root / "notes.md"
    os.write(file, "alpha marker\n")
    if tool == "Bash" && command.startsWith("git ") then
      os.proc("git", "init", "-q", root.toString).call()
      os.proc("git", "-C", root.toString, "-c", "user.name=Parity", "-c", "user.email=parity@example.invalid", "commit", "-q", "--allow-empty", "-m", "parity-baseline").call()
    val registry = new AskSessionRegistry(root / "data")
    val binding = registry.bind(file.toString, file.last).unsafeRunSync()
    val sources = new AskContextSources:
      def fileStat(p: String) = IO.pure(None)
      def fileText(p: String) = IO.pure(None)
      def graphNeighbors(p: String) = IO.pure(Nil)
      def recallHits(q: String) = IO.pure(Nil)
      def recallDbMtime = IO.pure(0L)
      def graphVersion = IO.pure("")
    val services = AskServices(registry, sources, new AskPromptCache)
    AskGatewayServices.install(services)
    def input = tool match
      case "Read" => JsonObject("file_path" -> file.toString.asJson, "device" -> "local".asJson)
      case "Write" => JsonObject("file_path" -> file.toString.asJson, "content" -> "changed\n".asJson, "device" -> "local".asJson)
      case "Edit" => JsonObject("file_path" -> file.toString.asJson, "old_string" -> "alpha".asJson, "new_string" -> "changed".asJson, "device" -> "local".asJson)
      case "Bash" => JsonObject("command" -> command.asJson, "device" -> "local".asJson, "timeout" -> 5000.asJson)
      case "Glob" => JsonObject("pattern" -> "*.md".asJson, "path" -> root.toString.asJson, "device" -> "local".asJson)
      case "Grep" => JsonObject("pattern" -> "marker".asJson, "path" -> file.toString.asJson, "device" -> "local".asJson)
    def run(sid: String, definition: AgentDef): IO[(ContentBlock.ToolResult, String, Option[nebflow.shared.ToolDefinition])] =
      Dispatcher.parallel[IO].use { dispatcher =>
        for
          requests <- Ref.of[IO, List[LlmRequest]](Nil)
          llm = new LlmHandle[IO]:
            def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new Exception("unexpected send"))
            def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
              Stream.eval(requests.modify(xs => (xs :+ req, xs.isEmpty))).flatMap { first =>
                if first then Stream(StreamChunk.ToolCallChunk(ToolCall("parity-id", tool, input)), StreamChunk.Done(None, None))
                else Stream(StreamChunk.TextDelta("done"), StreamChunk.Done(None, None))
              }
          limiter <- RateLimiter.create()
          tracker <- nebflow.core.FileChangeTracker.create(root.toString)
          locks <- FileLockManager.create
          thinking <- IO.ref(ThinkingConfig())
          overrides <- IO.ref(Map.empty[String, ModelCandidate])
          muted <- IO.ref(false)
          resources = SharedResources(llm = llm, dispatcher = dispatcher, sessionStore = SessionStore(root / "sessions", root / "tasks-ui"), projectRoot = root, thinkingConfigRef = thinking, rateLimiter = limiter, fileChangeTracker = tracker, contextWindow = 100000, agentLibrary = new AgentLibrary(root / "agents"), taskStore = FileTaskStore, historyArchiver = HistoryArchiver.fileSystem(root / "archives"), fileLockManager = locks, sessionModelOverrides = overrides, providerRegistry = null, healthMonitor = ProviderHealthMonitor(null), actorSystem = system, subAgentTaskStore = new SubAgentTaskStore(root / "subagent-tasks"), voiceMutedRef = muted)
          actor <- system.spawn(AgentActor(definition, resources, _ => IO.unit, 0, sessionId = Some(sid), projectRoot = Some(root.toString), sessionCwd = Some(root.toString), sandboxEnabled = sandboxEnabled), sid)
          _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, actor, AgentKind.Root, sid, None)))
          _ <- actor ! AgentCommand.ImmediateInput("run", fromUser = true)
          _ <- Stream.repeatEval(requests.get).filter(_.size >= 2).head.compile.drain.timeout(30.seconds)
          all <- requests.get
          _ = assert(all.head.tools.toList.flatten.exists(_.name == tool), s"$tool missing from actual wire face")
          result = all(1).messages.flatMap(_.content.toOption.toList.flatten).collectFirst { case r: ContentBlock.ToolResult if r.toolUseId == "parity-id" => r }.get
          content <- IO.blocking(os.read(file))
          _ <- actor ! AgentCommand.Stop("parity-complete")
          _ <- ShellSession.destroySession(sid)
        yield (result, content, all.head.tools.toList.flatten.find(_.name == tool))
      }
    try
      val askDef = services.agentDefFor(binding.id).unsafeRunSync()
      val ordinaryDef = AgentDef(name = "ordinary-standalone", description = "", tools = Nil)
      val askResult = run(binding.id, askDef).unsafeRunSync()
      os.write.over(file, "alpha marker\n")
      val ordinaryResult = run("ordinary-parity", ordinaryDef).unsafeRunSync()
      assertEquals(askResult._1.isError, ordinaryResult._1.isError)
      assertEquals(askResult._1.content, ordinaryResult._1.content)
      assertEquals(askResult._2, ordinaryResult._2)
      assertEquals(askResult._3, ordinaryResult._3)
      if !allowError then assert(!askResult._1.isError.contains(true), askResult._1.content)
    finally
      AskGatewayServices.uninstall()
      system.stopAll.attempt.void.unsafeRunSync()
      nebflow.core.LlmLogWriter.setEnabled(oldLog)
      PathUtil.setDataRoot(oldRoot)
      try os.remove.all(root) catch case _: java.nio.file.DirectoryNotEmptyException => ()

  test("Read matches standalone") { compare("Read") }
  test("Write matches standalone") { compare("Write") }
  test("Edit matches standalone") { compare("Edit") }
  test("Bash matches standalone") { compare("Bash") }
  test("Bash python3 execution matches standalone") { compare("Bash", "python3 -c 'print(42)'") }
  test("Bash git -C log matches standalone") { compare("Bash", "git -C . log -1 --format=%s") }
  test("Bash sandbox-enabled result matches standalone") { compare("Bash", "printf sandbox-parity", sandboxEnabled = true) }
  test("Bash dangerous-command guard matches standalone") { compare("Bash", "rm -rf /", allowError = true) }
  test("Bash directory listing matches standalone") { compare("Bash", "printf '%s\\n' *.md") }
  test("Glob matches standalone") { compare("Glob") }
  test("Grep matches standalone") { compare("Grep") }
