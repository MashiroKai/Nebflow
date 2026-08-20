package nebflow.core.compact

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.agent.{AgentActor, AgentCommand, AgentDef, AgentKind, AgentLibrary, AgentRecord, SharedResources, SubAgentTaskStore}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * #341 工具结果 TTL 清理 — docs/Nebflow/20260820_tool-result-ttl.md
 *
 * 单元断言（§4 验收 1/2/4/5）+ wiring 断言（验收 3：会话文件三分——清理
 * 生效后落盘历史仍含完整原内容）。
 */
class ToolResultTtlSpec extends FunSuite:

  private val Enabled = ToolResultTtlConfig(enabled = true, ttlMinutes = 60, keepRecent = 2, minChars = 100)

  private def toolUse(id: String, name: String = "Read"): Message =
    Message(
      MessageRole.Assistant,
      Right(List(ContentBlock.ToolUse(id, name, io.circe.JsonObject()))),
      timestamp = 1L
    )

  private def toolResult(id: String, content: String, ts: Long): Message =
    Message(MessageRole.User, Right(List(ContentBlock.ToolResult(id, content))), timestamp = ts)

  private def assistant(ts: Long): Message =
    Message(MessageRole.Assistant, Left("done"), timestamp = ts)

  private val Now = System.currentTimeMillis()
  private val TwoHoursAgo = Now - 2 * 60 * 60 * 1000L
  private val Old = Now - 90 * 60 * 1000L // > ttlMinutes(60)
  private val Fresh = Now - 5 * 60 * 1000L // < ttlMinutes

  /** History with N old large Read results followed by a cold-gap assistant. */
  private def history(n: Int): List[Message] =
    (1 to n).toList.flatMap { i =>
      List(toolUse(s"tu-$i"), toolResult(s"tu-$i", "x" * 5000, Old))
    } ++ List(assistant(TwoHoursAgo), Message(MessageRole.User, Left("next turn"), timestamp = Now))

  // ── 验收 1：默认关 ──────────────────────────────────────────────

  test("disabled config → None regardless of content"):
    assertEquals(ToolResultTtl.cleanRequestMessages(history(5), ToolResultTtlConfig()), None)

  test("config load fail-safe: absent / garbage / invalid node → disabled"):
    assertEquals(ToolResultTtlConfig.load(None).enabled, false)
    assertEquals(ToolResultTtlConfig.load(Some(Json.Null)).enabled, false)
    assertEquals(ToolResultTtlConfig.load(Some(Json.obj("enabled" -> "yes".asJson))).enabled, false)

  test("config load: valid node decodes and sanitizes"):
    val cfg = ToolResultTtlConfig.load(Some(Json.obj(
      "enabled" -> true.asJson,
      "ttlMinutes" -> 30.asJson,
      "keepRecent" -> (-3).asJson,
      "minChars" -> 500.asJson
    )))
    assertEquals(cfg.enabled, true)
    assertEquals(cfg.ttlMinutes, 30)
    assertEquals(cfg.keepRecent, 0, "negative keepRecent sanitizes to 0")
    assertEquals(cfg.minChars, 500)

  // ── 验收 2：过期+超窗+超大 → 占位符 ──────────────────────────────

  test("old, beyond keepRecent, oversized result is replaced with a self-describing placeholder"):
    ToolResultTtl.cleanRequestMessages(history(4), Enabled, Now) match
      case Some(cleaned) =>
        val results = cleaned.collect {
          case Message(MessageRole.User, Right(blocks), _, _) =>
            blocks.collect { case tr: ContentBlock.ToolResult => tr }
        }.flatten
        // keepRecent=2 → tu-3/tu-4 kept, tu-1/tu-2 replaced
        val byId = results.map(tr => tr.toolUseId -> tr.content).toMap
        assert(byId("tu-1").startsWith("[Tool output archived:"), s"oldest replaced: ${byId("tu-1").take(60)}")
        assert(byId("tu-2").startsWith("[Tool output archived:"), s"second-oldest replaced")
        assertEquals(byId("tu-3"), "x" * 5000, "within keepRecent window stays full")
        assertEquals(byId("tu-4"), "x" * 5000, "most recent stays full")
        assert(byId("tu-1").contains("re-run the tool"), "placeholder must tell the model how to recover")
      case None => fail("expected cleanup to fire")

  test("original list is not mutated (request-only purity)"):
    val h = history(4)
    ToolResultTtl.cleanRequestMessages(h, Enabled, Now)
    val results = h.collect {
      case Message(MessageRole.User, Right(blocks), _, _) => blocks.collect { case tr: ContentBlock.ToolResult => tr.content }
    }.flatten
    assert(results.forall(_ == "x" * 5000), "input list must stay intact")

  // ── 验收 4：keepRecent 窗内不清理（含 turn 中途） ────────────────

  test("fewer candidates than keepRecent → None (mid-turn safety)"):
    assertEquals(ToolResultTtl.cleanRequestMessages(history(2), Enabled, Now), None)

  test("fresh results (within TTL) are kept even beyond keepRecent"):
    val h = List(
      toolUse("tu-1"), toolResult("tu-1", "x" * 5000, Old),
      toolUse("tu-2"), toolResult("tu-2", "x" * 5000, Fresh),
      toolUse("tu-3"), toolResult("tu-3", "x" * 5000, Fresh),
      toolUse("tu-4"), toolResult("tu-4", "x" * 5000, Fresh),
      assistant(TwoHoursAgo), Message(MessageRole.User, Left("go"), timestamp = Now)
    )
    // keepRecent=2 keeps tu-3/tu-4; tu-2 is beyond the window but fresh → not replaced;
    // tu-1 is old and beyond the window → replaced
    ToolResultTtl.cleanRequestMessages(h, Enabled, Now) match
      case Some(cleaned) =>
        val byId = cleaned.collect {
          case Message(MessageRole.User, Right(blocks), _, _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
        }.flatten.map(tr => tr.toolUseId -> tr.content).toMap
        assert(byId("tu-1").startsWith("[Tool output archived:"), "old beyond window replaced")
        assertEquals(byId("tu-2"), "x" * 5000, "fresh beyond window kept")
      case None => fail("expected tu-1 cleanup")

  // ── 验收 5：冷缓存门 ────────────────────────────────────────────

  test("hot cache (assistant within 30min) → None"):
    val h = history(4).filterNot(_.timestamp == TwoHoursAgo) :+ assistant(Now - 5 * 60 * 1000L)
    assertEquals(ToolResultTtl.cleanRequestMessages(h, Enabled, Now), None)

  // ── 过滤维度补充 ────────────────────────────────────────────────

  test("small results (below minChars) are kept"):
    val h = List(
      toolUse("tu-1"), toolResult("tu-1", "tiny", Old),
      toolUse("tu-2"), toolResult("tu-2", "x" * 5000, Old),
      toolUse("tu-3"), toolResult("tu-3", "x" * 5000, Old),
      assistant(TwoHoursAgo), Message(MessageRole.User, Left("go"), timestamp = Now)
    )
    ToolResultTtl.cleanRequestMessages(h, Enabled, Now).foreach { cleaned =>
      val byId = cleaned.collect {
        case Message(MessageRole.User, Right(blocks), _, _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
      }.flatten.map(tr => tr.toolUseId -> tr.content).toMap
      assertEquals(byId("tu-1"), "tiny", "small result kept")
    }

  test("non-compactable tool results (Mail) are out of scope"):
    val h = List(
      toolUse("tu-1", "Mail"), toolResult("tu-1", "x" * 5000, Old),
      toolUse("tu-2"), toolResult("tu-2", "x" * 5000, Old),
      toolUse("tu-3"), toolResult("tu-3", "x" * 5000, Old),
      assistant(TwoHoursAgo), Message(MessageRole.User, Left("go"), timestamp = Now)
    )
    val cleaned = ToolResultTtl.cleanRequestMessages(h, Enabled, Now)
    cleaned.foreach { c =>
      val byId = c.collect {
        case Message(MessageRole.User, Right(blocks), _, _) => blocks.collect { case tr: ContentBlock.ToolResult => tr }
      }.flatten.map(tr => tr.toolUseId -> tr.content).toMap
      assertEquals(byId("tu-1"), "x" * 5000, "Mail result untouched")
    }

  // ── 验收 3：会话文件三分（wiring） ──────────────────────────────

  test("wiring: request gets placeholders, persisted session keeps full content"):
    val system = nebflow.actor.ActorSystem("ttl-wiring")
    val tmp = os.temp.dir()
    // keepRecent=0: the single seeded old result is the only candidate — a
    // nonzero window would trivially keep it and the test would assert nothing.
    val wiringCfg = Enabled.copy(keepRecent = 0)
    val bigContent = "w" * 5000
    val oldTs = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
    val initial = List(
      Message(MessageRole.User, Left("kick"), timestamp = oldTs - 60000),
      Message(MessageRole.Assistant, Right(List(ContentBlock.ToolUse("tu-old", "Read", io.circe.JsonObject("file_path" -> "/tmp/x".asJson)))), timestamp = oldTs - 30000),
      Message(MessageRole.User, Right(List(ContentBlock.ToolResult("tu-old", bigContent))), timestamp = oldTs),
      Message(MessageRole.Assistant, Left("summarized"), timestamp = oldTs) // last assistant 2h ago → cold
    )
    class CaptureLlm(requests: Ref[IO, List[LlmRequest]]) extends LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
        Stream.eval(requests.update(_ :+ req)).drain ++ Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

    val program = for
      requests <- IO.ref(List.empty[LlmRequest])
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- nebflow.core.tools.FileLockManager.create
      thinkingRef <- IO.ref(nebflow.llm.ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, nebflow.llm.ModelCandidate])
      voiceMuted <- IO.ref(false)
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks-ui")
      // The session must exist in the store's index — saveMessagesForSession
      // updates (not creates) index entries; create it first and use its id.
      sessionMeta <- sessionStore.createSession("ttl-wiring")
      sid = sessionMeta.id
      resources <- IO.pure(
        SharedResources(
          llm = null,
          dispatcher = dispatcher,
          sessionStore = sessionStore,
          projectRoot = os.pwd,
          thinkingConfigRef = thinkingRef,
          rateLimiter = rateLimiter,
          fileChangeTracker = tracker,
          contextWindow = 100_000,
          agentLibrary = new AgentLibrary(tmp / "agents"),
          taskStore = nebflow.core.task.FileTaskStore,
          historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
          fileLockManager = fileLocks,
          sessionModelOverrides = modelOverrides,
          providerRegistry = null,
          healthMonitor = nebflow.llm.ProviderHealthMonitor(null),
          actorSystem = system,
          subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
          voiceMutedRef = voiceMuted,
          toolResultTtl = wiringCfg
        )
      )
      capture = new CaptureLlm(requests)
      resourcesWithLlm = resources.copy(llm = capture)
      actorRef <- system.spawn(
        AgentActor(
          agentDef = AgentDef(name = "Nebula", description = "ttl wiring", tools = List("Read"), systemPrompt = ""),
          resources = resourcesWithLlm,
          wsSend = _ => IO.unit,
          depth = 0,
          sessionId = Some(sid),
          sessionName = Some("ttl-wiring"),
          initialMessages = initial
        ),
        sid
      )
      _ <- resourcesWithLlm.agentRegistry.update(_ + (sid -> AgentRecord(sid, actorRef, AgentKind.Root, sid, None)))
      _ <- actorRef ! AgentCommand.UserInput("follow up", None, Some("cmid-1"))
      _ <- IO.sleep(3000.millis) // turn runs to completion (persist included)
      _ <- sessionStore.flushPendingMessages // durability point: force debounced writes
      reqs <- requests.get
      // The finish-turn persist is a forkTurn (async) — poll until the FULL
      // content lands (bounded), instead of a fixed sleep racing it.
      persisted <- {
        // textContent skips ToolResult blocks — check block content directly.
        def hasBig(msgs: List[Message]): Boolean = msgs.exists {
          case Message(_, Right(blocks), _, _) =>
            blocks.exists { case tr: ContentBlock.ToolResult => tr.content == bigContent; case _ => false }
          case _ => false
        }
        def poll(deadline: Long): IO[List[Message]] =
          resourcesWithLlm.sessionStore.loadMessagesForSession(sid).flatMap { msgs =>
            if hasBig(msgs) then IO.pure(msgs)
            else if System.currentTimeMillis() >= deadline then IO.pure(msgs) // assert below fails loudly
            else IO.sleep(200.millis) *> resourcesWithLlm.sessionStore.flushPendingMessages *> poll(deadline)
          }
        poll(System.currentTimeMillis() + 10_000L)
      }
    yield
      assert(reqs.nonEmpty, "at least one LLM request expected")
      val reqText = reqs.map(_.messages.map(_.textContent).mkString("\n")).mkString("\n---\n")
      val reqBlocks = reqs.flatMap(_.messages.flatMap(_.content.toOption.toList.flatten.collect { case tr: ContentBlock.ToolResult => tr.content }))
      // request plane: placeholder present, original gone
      assert(reqBlocks.exists(_.startsWith("[Tool output archived:")), s"request must carry the placeholder:\n$reqText")
      assert(!reqBlocks.contains(bigContent), s"request must NOT carry the full old result:\n$reqText")
      // session-file plane: FULL content persisted
      assert(
        persisted.exists {
          case Message(_, Right(blocks), _, _) =>
            blocks.exists { case tr: ContentBlock.ToolResult => tr.content == bigContent; case _ => false }
          case _ => false
        },
        s"persisted history must keep the full result (${persisted.size} msgs)"
      )
    program.unsafeRunSync()
    system.stopAll.attempt.void.unsafeRunSync()
    os.remove.all(tmp)

end ToolResultTtlSpec
