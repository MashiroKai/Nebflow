package nebflow.agent

import cats.effect.std.{Dispatcher, Semaphore}
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
  * P1 (no-op `true` commands during the save/compact cycle, 2026-08-16):
  * pins the MECHANISM half of the fix. The save-memory preamble used to say
  * "END THIS TURN" — an imperative the model cannot execute (its action
  * space is: output text / call tools / stop), which GLM-family models
  * "solved" by running a no-op `true` command to gesture completion.
  *
  * The wording is fixed in CompactService (stop = the signal, no-op
  * explicitly forbidden); this spec pins the underlying contract that makes
  * the new wording truthful: a save turn that calls ZERO tools — the agent
  * just stops — transitions straight to the compact phase and completes the
  * whole cycle. No tool call is required or consulted for the transition
  * (AgentActor handleLlmCompleteBranch: phase==Save && toolCalls.isEmpty
  * → handleSavePhaseComplete).
  *
  * Kept as its own spec (not appended to AgentActorCompactionSpec) because
  * that file is pure state-level tests; this one drives the REAL AgentActor
  * in a real ActorSystem with a scripted LlmHandle (same harness pattern as
  * TeamMemberFailureNotifySpec).
  */
class SavePhaseZeroToolTurnSpec extends CatsEffectSuite:

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

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
      askSem <- Semaphore[IO](4)
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
      askSemaphore = askSem,
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

  private def textOf(m: nebflow.shared.Message): String =
    m.content.fold(identity, _ => "")

  test("save turn with ZERO tool calls — stopping alone — completes the two-stage cycle") {
    val system = ActorSystem("save-phase-zero-tools-test")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    // LlmLogWriter hardcodes <user.home>/.nebflow/logs/router (NOT dataRoot):
    // in a test JVM its daily retention prune scans the real log tree and
    // once stalled the turn tail for ~9s / 94% GC. Disable it for this spec.
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val llm = new RecordingLlm(
        List(
          // call 0 — normal user turn: text answer, no tools
          Stream(StreamChunk.TextDelta("working on it"), StreamChunk.Done(None, None)),
          // call 1 — SAVE turn: text only, ZERO tool calls (the agent just stops)
          Stream(StreamChunk.TextDelta("memory entries saved"), StreamChunk.Done(None, None)),
          // call 2 — COMPACT turn: text-only summary
          Stream(
            StreamChunk.TextDelta("<analysis>scratch</analysis><summary>the task so far</summary>"),
            StreamChunk.Done(None, None)
          )
        )
      )
      val program = for
        resources <- mkResources(system, tmp, llm)
        wsEvents <- IO.ref(List.empty[io.circe.Json])
        nebulaDef = AgentDef(
          name = "Nebula", // shouldInjectSaveReminder: name == "Nebula" → save turn
          description = "root",
          tools = List("Read", "Write", "Edit", "Bash"),
          systemPrompt = ""
        )
        agent <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = j => wsEvents.update(_ :+ j),
            depth = 0,
            parentRef = None,
            sessionId = Some("nebula-zero-tools-sess"),
            sessionName = Some("Nebula")
          ),
          "nebula-zero-tools"
        )
        _ <- agent ! AgentCommand.UserInput("start a task")
        // Wait for the first turn to fully finish (sessionBusy=false) before
        // triggering compaction: TriggerCompaction is handled by the idle
        // behavior only — a copy arriving mid-processing is dropped, and the
        // turn tail can be slow (llm logger retention pruning) in real runs.
        _ <- {
          def go(deadline: Long): IO[Unit] =
            wsEvents.get
              .map(_.exists(j => (j \\ "busy").exists(!_.asBoolean.getOrElse(true))))
              .flatMap {
                case true  => IO.unit
                case false =>
                  if System.currentTimeMillis() >= deadline then
                    IO.raiseError(new AssertionError("first turn did not finish in time"))
                  else IO.sleep(100.millis) >> go(deadline)
              }
          go(System.currentTimeMillis() + 15000)
        }
        deferred <- cats.effect.Deferred[IO, Either[String, CompactionResult]]
        _ <- agent ! AgentCommand.TriggerCompaction("full", Some(deferred), None)
        // Completion signal: the compactComplete ws event (the same channel
        // production uses — TriggerCompaction's replyDeferred is NEVER
        // completed on the success path, a latent API wart noted in the
        // task report; only failure paths complete it Left).
        _ <- {
          def waitCompact(deadline: Long): IO[Unit] =
            wsEvents.get
              .map(_.exists(j => j.hcursor.get[String]("type").exists(_.contains("compactComplete"))))
              .flatMap {
                case true  => IO.unit
                case false =>
                  if System.currentTimeMillis() >= deadline then
                    IO.raiseError(new AssertionError("compaction did not complete in time"))
                  else IO.sleep(100.millis) >> waitCompact(deadline)
              }
          waitCompact(System.currentTimeMillis() + 20000)
        }
        reqs <- llm.requests.get.map(_.reverse)
        evs <- wsEvents.get
      yield (reqs, evs)

      val (reqs, evs) = program.unsafeRunSync()

      // Diagnostic aid when this regresses: the ws event stream shows how far
      // the two-stage cycle got (compactStart / save-phase events / failed).
      def compactDone = evs.find(j => j.hcursor.get[String]("type").exists(_.contains("compactComplete")))
      assert(compactDone.isDefined, s"no compactComplete event; wsEvents=${evs.map(_.toString.take(80)).mkString(" | ")}")
      val doneJson = compactDone.get

      // ── The cycle completed, driven purely by the agent STOPPING ──
      // Messages were actually replaced (before > after proves the summary
      // landed, not just the event fired).
      assert(
        doneJson.hcursor.get[Int]("before").getOrElse(0) > doneJson.hcursor.get[Int]("after").getOrElse(0),
        s"compaction should shrink messages, got: $doneJson"
      )

      // Exactly 3 LLM calls: user turn + save turn + compact turn. A fourth
      // would mean the zero-tool save turn went through a tool loop or an
      // extra round-trip — i.e. stopping alone was NOT accepted as the
      // completion signal.
      assertEquals(reqs.size, 3, s"expected exactly 3 LLM calls, got ${reqs.size}")

      // Save turn (call 1): save-memory reminder injected, tools AVAILABLE.
      // (exists, not messages.last: turn-boundary reminders like the
      // off-peak time-context can be appended after the save reminder.)
      val saveReq = reqs(1)
      assert(
        saveReq.messages.exists(m => textOf(m).contains("MEMORY MAINTENANCE CYCLE")),
        "save reminder not injected"
      )
      assert(
        saveReq.tools.exists(_.nonEmpty),
        "save turn must keep tools available (Write/Edit for memory)"
      )

      // Compact turn (call 2): compact reminder injected, tools DISABLED
      val compactReq = reqs(2)
      assert(
        compactReq.messages.exists(m => textOf(m).contains("Context compaction required")),
        "compact reminder not injected"
      )
      assert(
        compactReq.tools.forall(_.isEmpty),
        "compact turn must have tools disabled"
      )
    finally
      PathUtil.setDataRoot(prevRoot)
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
  }
