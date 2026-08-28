package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, ToolCall}

import scala.concurrent.duration.*

/**
  * Save-turn guards (P0-1 + P1-4 + P0-2, 2026-08-22 Write-only loop batch).
  *
  * Production shape (session a5431750): the save-phase toolset [W/E/R] vs an
  * in-flight task needing Bash — the model re-wrote the SAME file with the
  * SAME content 24× (tool result "(no changes)") while every round's thinking
  * planned a Bash execution it had no tool for; toolCalls-never-empty starved
  * the Save→Compact transition and the context grew until deadlock.
  *
  * This spec drives the REAL AgentActor (harness pattern of
  * SavePhaseZeroToolTurnSpec) and pins:
  *  - P0-1 zero-progress: 3 identical (path, hash) Writes force the transition
  *  - P0-1 round budget: 10 varying (non-drift) save rounds exhaust the budget
  *  - P1-4 drift two-strike: off-target write → reinforced reminder → second
  *    off-target write → forced transition (reminder content asserted in the
  *    next request's messages)
  *  - saveTurnTools whitelist NOT regressed: save-phase requests carry exactly
  *    [Write, Edit, Read] (anti-divergence design untouched — guards cap the
  *    loop, not the toolset)
  *  - P0-2 hard guard: an oversized pre-seeded session (estimate > 0.95×
  *    window) goes straight to emergencyClean on dispatch — no save turn, no
  *    LLM-dependent compaction call with the full history
  */
class SaveTurnGuardSpec extends CatsEffectSuite:

  override val munitIOTimeout = 120.seconds

  /** Scripted LLM: records every request, answers by call index. */
  private class RecordingLlm(scripts: List[Stream[IO, StreamChunk]]) extends LlmHandle[IO]:
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream
        .eval(requests.modify { list => (req :: list, list.size) })
        .flatMap { idx =>
          scripts
            .lift(idx)
            .getOrElse(Stream.raiseError[IO](new RuntimeException(s"unexpected LLM call #$idx")))
        }

  private def text(s: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(s), StreamChunk.Done(None, None))

  private def writeCall(id: String, path: String, content: String): Stream[IO, StreamChunk] =
    Stream(
      StreamChunk.ToolCallChunk(
        ToolCall(id, "Write", JsonObject("file_path" -> path.asJson, "content" -> content.asJson))
      ),
      StreamChunk.Done(None, None)
    )

  private val summaryScript: Stream[IO, StreamChunk] =
    text("<analysis>scratch</analysis><summary>the task so far</summary>")

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
      // Permission decision points read the root-session policy bucket
      // (dynamic inheritance) — default ConfirmEdits makes overwrite-class
      // Writes park on askPermission (nobody approves in tests). Pre-seed
      // AutoAll for this harness's root session.
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

  private def nebulaDef: AgentDef =
    AgentDef(
      name = "Nebula", // shouldInjectSaveReminder: name == "Nebula" → save turn
      description = "root",
      tools = List("Read", "Write", "Edit", "Bash"),
      systemPrompt = ""
    )

  /** Shared driver: spawn agent, finish a first turn, trigger compaction,
    * wait for compactComplete, return (requests, wsEvents). */
  private def driveCycle(
      scripts: List[Stream[IO, StreamChunk]],
      sessionId: String,
      actorName: String
  ): (List[LlmRequest], List[io.circe.Json]) =
    val system = ActorSystem(s"save-turn-guard-$actorName")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val llm = new RecordingLlm(text("working on it") :: scripts)
      val program = for
        resources <- mkResources(system, tmp, llm, sessionId)
        wsEvents <- IO.ref(List.empty[io.circe.Json])
        agent <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = j => wsEvents.update(_ :+ j),
            depth = 0,
            parentRef = None,
            sessionId = Some(sessionId),
            sessionName = Some("Nebula"),
            // Write toolcalls must execute unattended in tests — ConfirmEdits
            // would park on a permission Deferred nobody approves (the 30s
            // timeouts of the first run).
            safetyMode = "auto-all"
          ),
          actorName
        )
        _ <- agent ! AgentCommand.UserInput("start a task")
        _ <- waitFor(wsEvents, j => (j \\ "busy").exists(!_.asBoolean.getOrElse(true)),
          "first turn did not finish", 15000)
        _ <- agent ! AgentCommand.TriggerCompaction("full", None, None)
        _ <- waitFor(wsEvents, j => j.hcursor.get[String]("type").exists(_.contains("compactComplete")),
          "compaction did not complete", 30000)
        reqs <- llm.requests.get.map(_.reverse)
        evs <- wsEvents.get
      yield (reqs, evs)
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
  end driveCycle

  private def waitFor(
      ref: cats.effect.Ref[IO, List[io.circe.Json]],
      pred: io.circe.Json => Boolean,
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

  private def toolNames(req: LlmRequest): List[String] =
    req.tools.map(_.map(_.name).sorted).getOrElse(Nil)

  // ── P0-1: zero-progress — 3 identical Writes force Save→Compact ────────

  test("P0-1 zero-progress: three identical (path, hash) Writes force the transition") {
    // Path inside the memory domain — isolates the ZERO-PROGRESS guard (an
    // off-target path would let the drift guard escalate first, at round 2).
    val same = writeCall("w1", "/tmp/data/memory.md", "entry: fixed content")
    val (reqs, evs) = driveCycle(
      List(
        same, // save round 1 — history [(path,hash)]
        same, // save round 2 — history [(p,h),(p,h)]
        same, // save round 3 — 3 identical → zero-progress escalate
        summaryScript // compact turn
      ),
      "guard-zero-progress",
      "guard-zero-progress-actor"
    )
    // call 0 = first user turn; calls 1..3 = save rounds; call 4 = compact
    assertEquals(reqs.size, 5, s"expected 5 LLM calls, got ${reqs.size}")
    // whitelist not regressed: save-phase requests carry exactly [Edit, Read, Write]
    assertEquals(toolNames(reqs(1)), List("Edit", "Read", "Write"))
    assertEquals(toolNames(reqs(3)), List("Edit", "Read", "Write"))
    // compact turn: tools disabled
    assertEquals(reqs(4).tools, Some(Nil))
    // WebSearch P0: housekeeping turns (save/compact) opt out of provider
    // search injection; the user turn opts in.
    assert(reqs(0).searchAllowed, "user turn must allow provider search injection")
    assert(!reqs(4).searchAllowed, "compact turn must opt out of provider search injection")
    assert(!reqs(1).searchAllowed, "save turn must opt out of provider search injection")
    // compaction actually shrank messages
    val done = evs.find(j => j.hcursor.get[String]("type").exists(_.contains("compactComplete"))).get
    assert(done.hcursor.get[Int]("before").getOrElse(0) > done.hcursor.get[Int]("after").getOrElse(0))
  }

  // ── P0-1: round budget — 10 varying on-target rounds exhaust the cap ────

  test("P0-1 round budget: varying memory-file writes exhaust at round 10 (no drift, no zero-progress)") {
    // 10 rounds writing DISTINCT content to a legit memory path: hash always
    // changes (zero-progress cannot fire), path is on-target (drift cannot
    // fire) — only the budget can stop this loop.
    // 11 rounds: the budget fires when rounds EXCEEDS 10 (round 11 → 11 > 10);
    // with only 10 rounds the natural-stop path would consume the summary
    // script and the compact turn would find no script (first-run failure).
    val rounds = (1 to 11).map(i => writeCall(s"w$i", "/tmp/data/memory.md", s"entry $i")).toList
    val (reqs, _) = driveCycle(
      rounds :+ summaryScript,
      "guard-budget",
      "guard-budget-actor"
    )
    // 1 (first turn) + 11 (save rounds) + 1 (forced compact) = 13
    assertEquals(reqs.size, 13, s"expected 13 LLM calls, got ${reqs.size}")
    assertEquals(reqs(12).tools, Some(Nil), "13th call must be the forced compact turn (tools disabled)")
  }

  // ── P1-4: drift two-strike — reminder, then forced transition ───────────

  test("P1-4 drift: first off-target write injects reminder, second forces transition") {
    val (reqs, _) = driveCycle(
      List(
        writeCall("w1", "/tmp/task-a.mjs", "console.log('a')"), // drift strike 1 → reminder
        writeCall("w2", "/tmp/task-b.mjs", "console.log('b')"), // drift strike 2 → forced
        summaryScript // compact turn
      ),
      "guard-drift",
      "guard-drift-actor"
    )
    // 1 (first turn) + 2 (save rounds) + 1 (forced compact) = 4
    assertEquals(reqs.size, 4, s"expected 4 LLM calls, got ${reqs.size}")
    // The second save-round request must carry the reinforced reminder
    val secondRoundMsgs = reqs(2).messages
    val reminderPresent = secondRoundMsgs.exists { m =>
      m.role == MessageRole.User &&
        m.content.fold(identity, _ => "").contains("OUTSIDE your memory/skill paths")
    }
    assert(reminderPresent, "reinforced drift reminder must be injected after the first strike")
    assertEquals(reqs(3).tools, Some(Nil), "final call must be the forced compact turn")
  }

  // ── P0-2: hard guard — oversized session goes straight to emergencyClean ─

  test("P0-2 hard guard: estimate > 0.95×window dispatches emergencyClean, no save turn, no full-history LLM compaction") {
    val system = ActorSystem("save-turn-p02-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val llm = new RecordingLlm(List(text("ok after emergency")))
      // Long bodies: TokenEstimator is char-weighted — tiny "user 1" bodies
      // stay under 0.95×2000 even at 300 messages (first-run miss).
      // ~10 weighted tokens each: 300 msgs ≈ 3000 > hardLimit (fires P0-2),
      // but the retained tail of 20 ≈ 200 lands UNDER the 0.8×2000 threshold
      // (no follow-up auto-compact — a 500-char body kept the cleaned tail
      // above the limit and re-triggered compaction paths).
      val body = "x" * 40
      val seeded = (1 to 300).toList.map(i =>
        if i % 2 == 0 then Message(MessageRole.User, Left(s"user $i $body"))
        else Message(MessageRole.Assistant, Left(s"assistant $i $body"))
      )
      val program = for
        resources <- mkResources(system, tmp, llm, "p02-root")
        // Ordinary UserInput dispatch does NOT load persisted history (only
        // Restart(Full) does) — seed via initialMessages so maybeAutoCompact
        // sees the bloated list on the very first dispatch.
        meta <- IO.pure("p02-bloated-seed")
        wsEvents <- IO.ref(List.empty[io.circe.Json])
        agent <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = j => wsEvents.update(_ :+ j),
            depth = 0,
            parentRef = None,
            sessionId = Some(meta),
            sessionName = Some("Nebula"),
            initialMessages = seeded,
            safetyMode = "auto-all",
            // Small window so the seeded history's character-weighted
            // estimate lands above 0.95×window — TokenEstimator is char-based
            // (NOT 500/msg), short seeded messages never reach 0.95×100k.
            contextWindow = 2000
          ),
          "p02-actor"
        )
        _ <- agent ! AgentCommand.UserInput("resume")
        _ <- waitFor(wsEvents, j => (j \\ "busy").exists(!_.asBoolean.getOrElse(true)),
          "post-emergency turn did not finish", 20000)
        reqs <- llm.requests.get.map(_.reverse)
        evs <- wsEvents.get
      yield (reqs, evs)
      val (reqs, evs) = program.unsafeRunSync()

      // Emergency compaction fired and shrank the session…
      val doneOpt = evs.find(j => j.hcursor.get[String]("type").exists(_.contains("compactComplete")))
      assert(doneOpt.isDefined, s"expected emergency compactComplete, wsEvents tail=${evs.takeRight(5)}")
      val done = doneOpt.get
      assert(done.hcursor.get[Int]("before").getOrElse(0) > done.hcursor.get[Int]("after").getOrElse(0))
      // …and the dispatch LLM call went out with the TRUNCATED history, not
      // the bloated one: one call only (no save turn, no compact-turn attempt
      // with full history), and its messages are far below the seed count.
      assertEquals(reqs.size, 1, s"expected exactly 1 LLM call after emergency, got ${reqs.size}")
      assert(reqs.head.messages.size < 300,
        s"dispatch must run on truncated history, got ${reqs.head.messages.size} messages")
      // Not a save turn: a broad toolset, NOT the save whitelist. (Exact set
      // varies: with an empty agents dir the per-turn def reload falls back
      // to Seeds.Nebula's full registry — known isolated-instance behavior.)
      val names = toolNames(reqs.head)
      assert(names != List("Edit", "Read", "Write"), s"dispatch must NOT be a save turn, got $names")
      assert(names.size > 3, s"expected a broad toolset, got $names")
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
  }

end SaveTurnGuardSpec
