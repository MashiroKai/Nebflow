package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.*

import scala.concurrent.duration.*

/** WebSearch P0 wiring (E2-3 echo half + Tier 2 interception): drives the REAL
  * AgentActor turn loop and pins the three dispatch points that unit tests
  * cannot see:
  *
  *  1. kimi $web_search round-trip: the provider-native tool call passes the
  *     allowed-tool whitelist (it is not an agent tool), skips the permission
  *     gate (pure echo), and the tool result persisted into the conversation
  *     is BYTE-IDENTICAL to the model's raw arguments — the echo the Moonshot
  *     protocol requires. A filtered or re-serialized echo would show up here
  *     as "Tool not available" / mangled JSON.
  *  2. WebSearch interception: with a search-capable chain head (zhipu), the
  *     tool result comes from the Tier 2 provider executor (provenance header
  *     + URL), and the executor's sub-request was armed (tools=Some(Nil)).
  */
class WebSearchWiringSpec extends CatsEffectSuite:

  override val munitIOTimeout = 90.seconds

  /** Scripted handle: sendStream answers turn scripts by call index; send
    * answers the Tier 2 executor's non-streaming sub-requests by index. */
  private class RecordingLlm(
      streamScripts: List[Stream[IO, StreamChunk]],
      sendScripts: List[LlmResponse] = Nil
  ) extends LlmHandle[IO]:
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil)
    val sendRequests = Ref.unsafe[IO, List[LlmRequest]](Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      sendRequests.modify { list => (req :: list, list.size) }.flatMap { idx =>
        sendScripts.lift(idx) match
          case Some(resp) => IO.pure(resp)
          case None => IO.raiseError(new RuntimeException(s"unexpected send #$idx"))
      }
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream
        .eval(requests.modify { list => (req :: list, list.size) })
        .flatMap { idx =>
          streamScripts
            .lift(idx)
            .getOrElse(Stream.raiseError[IO](new RuntimeException(s"unexpected stream call #$idx")))
        }

  private def text(s: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(s), StreamChunk.Done(None, None))

  private def call(id: String, name: String, input: JsonObject, raw: Option[String] = None) =
    Stream(
      StreamChunk.ToolCallChunk(ToolCall(id, name, input, raw)),
      StreamChunk.Done(None, None)
    )

  private def mkResources(
      system: ActorSystem,
      tmp: os.Path,
      llm: LlmHandle[IO],
      rootSessionId: String
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
      policies <- Ref.of[IO, Map[String, PermissionPolicy]](
        Map(rootSessionId -> PermissionPolicy(safetyMode = nebflow.core.SafetyMode.AutoAll))
      )
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
      voiceMutedRef = voiceMuted,
      permissionPolicies = policies
    )

  /** NOT named Nebula — an empty agents dir makes the per-turn def reload
    * fall back to Seeds.Nebula for that name (different model/tools); a
    * non-seed name keeps OUR def (with model + WebSearch tool). */
  private def probeDef(model: Option[AgentModelConfig]): AgentDef =
    AgentDef(
      name = "WsProbe",
      description = "websearch wiring probe",
      tools = List("Read", "WebSearch"),
      systemPrompt = "",
      model = model
    )

  private def toolResultsOf(req: LlmRequest, toolCallId: String): List[String] =
    req.messages.flatMap { m =>
      m.content.toOption.toList.flatMap(
        _.collect { case ContentBlock.ToolResult(id, c, _) if id == toolCallId => c }
      )
    }

  private def toolUsesOf(req: LlmRequest): List[(String, String)] =
    req.messages.flatMap { m =>
      m.content.toOption.toList.flatMap(
        _.collect { case ContentBlock.ToolUse(id, name, _) => (id, name) }
      )
    }

  private def waitFor(
      ref: Ref[IO, List[Json]],
      pred: Json => Boolean,
      msg: String,
      timeoutMs: Long
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      ref.get.map(_.exists(pred)).flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"$msg in time"))
          else IO.sleep(100.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeoutMs)

  // ── 1. kimi $web_search echo round-trip ──────────────────────────────

  test("kimi $web_search: whitelist bypass + permission bypass + byte-identical echo fed back") {
    val system = ActorSystem("ws-kimi-echo-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      // Oddly-spaced raw args: a re-serialization would compact this — the
      // assertion pins BYTE identity, not just JSON equality.
      val rawArgs = """{  "query" : "scala 3.5 release notes"  }"""
      val parsed = io.circe.parser.parse(rawArgs).toOption.flatMap(_.asObject).getOrElse(JsonObject())
      val llm = new RecordingLlm(
        List(
          call("call_ws1", "$web_search", parsed, Some(rawArgs)), // turn 1: native search call
          text("Based on the search results: Scala 3.5 was released...") // turn 2: post-echo continuation
        )
      )
      val program = for
        resources <- mkResources(system, tmp, llm, "ws-kimi-root")
        wsEvents <- IO.ref(List.empty[Json])
        agent <- system.spawn(
          AgentActor(
            agentDef = probeDef(None),
            resources = resources,
            wsSend = j => wsEvents.update(_ :+ j),
            depth = 0,
            parentRef = None,
            sessionId = Some("ws-kimi-session"),
            sessionName = Some("WsProbe"),
            safetyMode = "auto-all"
          ),
          "ws-kimi-actor"
        )
        _ <- agent ! AgentCommand.UserInput("search for scala 3.5")
        _ <- waitFor(wsEvents, j => (j \\ "busy").exists(!_.asBoolean.getOrElse(true)),
          "turn did not finish", 30000)
        reqs <- llm.requests.get.map(_.reverse)
        evs <- wsEvents.get
      yield (reqs, evs)
      val (reqs, _) = program.unsafeRunSync()

      assertEquals(reqs.size, 2, s"expected 2 turn requests, got ${reqs.size}")
      // Turn-2 request must contain the echoed tool result — byte-identical.
      val echoed = toolResultsOf(reqs(1), "call_ws1")
      assertEquals(echoed, List(rawArgs), "echo tool result must be byte-identical to raw model arguments")
      // And the assistant ToolUse block for the native call round-tripped.
      val uses = toolUsesOf(reqs(1)).filter(_._2 == "$web_search")
      assertEquals(uses.map(_._1), List("call_ws1"))
      // No "Tool not available" error result anywhere (whitelist bypass works).
      val allResults = reqs.flatMap(r => toolResultsOf(r, "call_ws1"))
      assert(allResults.forall(!_.contains("not available")), s"filtered call would surface an error: $allResults")
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
  }

  // ── 2. WebSearch Tier 2 interception (zhipu chain head) ──────────────

  test("WebSearch call with zhipu chain head: result carries provider provenance + URL; sub-request armed") {
    val system = ActorSystem("ws-tier2-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val searchInfo = Json.arr(
        Json.obj(
          "title" -> "Scala 3.5.2 release notes".asJson,
          "url" -> "https://example.com/scala-3-5-2".asJson,
          "content" -> "Bugfix release.".asJson
        )
      )
      val sendResp = LlmResponse(
        reply = "searched",
        toolCalls = Nil,
        usage = None,
        meta = LlmMeta("ws-tier2", "WsProbe", "zhipu", "glm-5.3", 5)
      ).copy(searchInfo = Some(searchInfo))
      val llm = new RecordingLlm(
        List(
          call("call_search1", "WebSearch", JsonObject("query" -> "scala 3.5 release notes".asJson)),
          text("Summarized from provider search.")
        ),
        List(sendResp)
      )
      val program = for
        resources <- mkResources(system, tmp, llm, "ws-tier2-root")
        wsEvents <- IO.ref(List.empty[Json])
        agent <- system.spawn(
          AgentActor(
            agentDef = probeDef(Some(AgentModelConfig(Some("zhipu/glm-5.3"), Nil))),
            resources = resources,
            wsSend = j => wsEvents.update(_ :+ j),
            depth = 0,
            parentRef = None,
            sessionId = Some("ws-tier2-session"),
            sessionName = Some("WsProbe"),
            safetyMode = "auto-all"
          ),
          "ws-tier2-actor"
        )
        _ <- agent ! AgentCommand.UserInput("look up scala 3.5")
        _ <- waitFor(wsEvents, j => (j \\ "busy").exists(!_.asBoolean.getOrElse(true)),
          "turn did not finish", 30000)
        reqs <- llm.requests.get.map(_.reverse)
        sendReqs <- llm.sendRequests.get.map(_.reverse)
      yield (reqs, sendReqs)
      val (reqs, sendReqs) = program.unsafeRunSync()

      assertEquals(reqs.size, 2)
      assertEquals(sendReqs.size, 1, "exactly one provider search sub-request")
      // Sub-request armed for injection: tools=Some(Nil) (the interface gate).
      assertEquals(sendReqs.head.tools, Some(Nil))
      assert(sendReqs.head.messages.exists(_.textContent.contains("scala 3.5 release notes")))
      // Turn-2 messages carry the Tier 2 result with provenance + URL.
      val results = toolResultsOf(reqs(1), "call_search1")
      assertEquals(results.size, 1)
      assert(results.head.contains("Search source: provider:zhipu"), results.head.take(120))
      assert(results.head.contains("https://example.com/scala-3-5-2"))
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
  }

  // ── 3. #356: deepseek-headed chain routes the search to a capable fallback ──

  test("#356: deepseek chain head + kimi fallback → WebSearch routed to kimi (real result, not Tier 3)") {
    val system = ActorSystem("ws-route356-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val searchInfo = Json.arr(
        Json.obj(
          "title" -> "CZT pixel detector".asJson,
          "url" -> "https://example.com/czt-pixel".asJson,
          "content" -> "Sub-pixel readout with resistive networks.".asJson
        )
      )
      val sendResp = LlmResponse(
        reply = "searched",
        toolCalls = Nil,
        usage = None,
        meta = LlmMeta("ws-route356", "WsProbe", "kimi", "k3", 5)
      ).copy(searchInfo = Some(searchInfo))
      val llm = new RecordingLlm(
        List(
          call("call_ws356", "WebSearch", JsonObject("query" -> "CZT pixel detector sub-pixel readout".asJson)),
          text("Summarized from kimi provider search.")
        ),
        List(sendResp)
      )
      // The MAIN chain head is deepseek — no builtin search. The #356
      // reordering must route the Tier 2 sub-request to kimi (fallback).
      val mainModel = Some(AgentModelConfig(Some("deepseek/v4"), List("kimi/k3")))
      val program = for
        resources <- mkResources(system, tmp, llm, "ws-route356-root")
        wsEvents <- IO.ref(List.empty[Json])
        agent <- system.spawn(
          AgentActor(
            agentDef = probeDef(mainModel),
            resources = resources,
            wsSend = j => wsEvents.update(_ :+ j),
            depth = 0,
            parentRef = None,
            sessionId = Some("ws-route356-session"),
            sessionName = Some("WsProbe"),
            safetyMode = "auto-all"
          ),
          "ws-route356-actor"
        )
        _ <- agent ! AgentCommand.UserInput("search CZT pixel detector sub-pixel readout")
        _ <- waitFor(wsEvents, j => (j \\ "busy").exists(!_.asBoolean.getOrElse(true)),
          "turn did not finish", 30000)
        reqs <- llm.requests.get.map(_.reverse)
        sendReqs <- llm.sendRequests.get.map(_.reverse)
      yield (reqs, sendReqs)
      val (reqs, sendReqs) = program.unsafeRunSync()

      assertEquals(reqs.size, 2, "tool-calling turn: WebSearch call + post-tool summary")
      assertEquals(sendReqs.size, 1, "exactly one provider search sub-request — routed, not Tier 3")
      // The sub-request carries the REORDERED model config: kimi leads, so
      // LlmHandle's candidate pipeline lands on the search-capable provider.
      assertEquals(
        sendReqs.head.agentModel,
        Some(AgentModelConfig(Some("kimi/k3"), List("deepseek/v4")))
      )
      assert(sendReqs.head.messages.exists(_.textContent.contains("CZT pixel detector")))
      // Turn-2 messages carry the kimi-sourced result with provenance + URL —
      // NOT the "All search engines failed" Tier 3 degrade.
      val results = toolResultsOf(reqs(1), "call_ws356")
      assertEquals(results.size, 1)
      assert(results.head.contains("Search source: provider:kimi"), results.head.take(120))
      assert(results.head.contains("https://example.com/czt-pixel"))
      assert(!results.head.contains("All search engines failed"), "Tier 3 degrade must NOT happen when kimi is available")
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
  }

end WebSearchWiringSpec
