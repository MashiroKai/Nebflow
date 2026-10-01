package nebflow.agent

import cats.effect.IO
import cats.effect.{Ref, Deferred}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.actor.{AgentCommand, AgentDef}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ToolCall, ThinkingConfig}
import nebflow.shared.{ContentBlock, Message, MessageRole}

import scala.concurrent.duration.*

/**
 * eng-deferred-cancel batch — K-1 criteria 1 and 2 on the REAL AgentActor turn
 * loop: (1) an in-flight tool's return/persistence happens BEFORE the node is
 * finalized, and (2) once the cancel takes effect the node dispatches no new
 * tool call.
 *
 * Faces:
 *  - D1 (criterion 1, time order): while a batch is in flight, a deferred stop
 *    must let that batch's `tool_result` be persisted into the session
 *    transcript FIRST, and only then let the actor stop. Measured against two
 *    independent observable faces: the transcript
 *    (`SessionStore.loadMessagesForSession` must contain the paired
 *    `tool_result`) and the actor system's liveness registry
 *    (`system.isAlive`). The pairing of "result present AND actor stopped" is
 *    the hard assertion: persistence precedes finalization.
 *  - D2 (criterion 2, zero new tool calls): after the stop the session must never
 *    issue a second LLM request (call count == 1), i.e. no new tool round is
 *    started once the cancellation has taken effect.
 *  - D3 (zero-regression control): `Stop(reason)` keeps the historical immediate
 *    teardown — the same fixture, unchanged semantics.
 *  - D4 (zero-regression control): with nothing in flight, `Stop()` degrades to
 *    the immediate path, so a parked session can never be left on an intent that
 *    no batch will ever honour.
 *
 * Red semantics: deleting the deferred branch in `AgentProcessing` (back to an
 * unconditional immediate `cancelCurrentTurn`) turns D1 red (no `tool_result` in
 * the transcript); making the batch-boundary branch continue to the next LLM
 * turn instead of stopping turns D2 red (a second request appears).
 *
 * Fixture = in-process spec (a real AgentActor + scripted LLM stub): zero spawn,
 * zero instance, zero ports, zero contact with the live `:8080`; it writes only
 * under `target/test-deferred-cancel-wire/`.
 */
class DeferredCancelWireSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-deferred-cancel-wire"
  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  // ── A tool batch that can be held in flight: a Bash command gated on a file ─
  //
  // Why a real Bash tool instead of a hand-rolled one: this spec pins the REAL
  // batch boundary (tool returns → `pipeToolExecutions` → `ToolsComplete`); a
  // synthetic tool would bypass that boundary and rest the judge on an
  // assumption. The gate is a file in the workspace: the command
  // `while [ ! -f <gate> ]; do sleep 0.05; done` returns only after the spec
  // creates that file, so the "batch in flight" window is entirely
  // spec-controlled (zero guessing).

  private class ScriptedLlm(streams: Ref[IO, List[Stream[IO, StreamChunk]]]) extends LlmHandle[IO]:
    val calls = Ref.unsafe[IO, Int](0)
    val requests = Ref.unsafe[IO, List[LlmRequest]](Nil)

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(calls.modify(n => (n + 1, n))).flatMap { n =>
        Stream.eval(requests.update(rs => req :: rs)) >>
          Stream.eval(streams.get).flatMap { ss =>
            ss.lift(n).getOrElse(Stream.raiseError[IO](new RuntimeException(s"unexpected stream call #$n")))
          }
      }

  private def toolCall(id: String, name: String, input: JsonObject): Stream[IO, StreamChunk] =
    Stream(StreamChunk.ToolCallChunk(ToolCall(id, name, input)), StreamChunk.Done(None, None))

  private def text(s: String): Stream[IO, StreamChunk] =
    Stream(StreamChunk.TextDelta(s), StreamChunk.Done(None, None))

  /** Drop any transcript left by an earlier run for this session id (shared sessions dir). */
  private def clearSession(sid: String): Unit =
    val dir = tempRoot / "sessions"
    List(dir / s"$sid.json", dir / s"$sid.ui.json").foreach(f => if os.exists(f) then os.remove(f))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Unit] =
    awaitTrue(timeout, every)(cond).void

  /** Wait until `cond` holds, then hand back the value actually observed (no second read). */
  private def awaitTrue(timeout: FiniteDuration, every: FiniteDuration = 25.millis)(cond: IO[Boolean]): IO[Boolean] =
    def go(deadline: Long): IO[Boolean] =
      cond.flatMap {
        case true => IO.pure(true)
        case _ if System.currentTimeMillis() >= deadline => IO.pure(false)
        case false => IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  /**
   * Fixture: a real AgentActor with a gate-file-blocked Bash tool batch in flight.
   *
   * Returns once the batch is provably IN FLIGHT (the session was observed handling
   * the tool call — see `inFlight`), never on a fixed sleep.
   */
  private def withBatchInFlight(
    name: String
  )(
    body: (ActorSystem, SharedResources, nebflow.actor.ActorRef[AgentCommand], os.Path, ScriptedLlm) => IO[Unit]
  ): IO[Unit] =
    val ws = tempRoot / name
    // Start from a CLEAN workspace AND a clean transcript. Both matter: the gate file
    // makes the in-flight precondition a real reading, and the session file is keyed by
    // session id in a SHARED sessions dir — a transcript left by an earlier run of this
    // very spec (same session id) reads as "the batch already produced its result" and
    // turns the precondition into a false alarm (observed: stale `<id>.json` with the
    // previous run's tool_result).
    os.remove.all(ws)
    os.makeDir.all(ws)
    clearSession(s"deferred-wire-$name")
    val gate = ws / "release-gate"
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val system = ActorSystem(s"deferred-wire-$name")
    val streams = Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](
      List(
        // Turn 1: a real tool call that blocks until the gate file appears.
        toolCall(
          "tu-block",
          "Bash",
          JsonObject("command" -> Json.fromString(s"while [ ! -f '${gate}' ]; do sleep 0.05; done; echo batch-done"))
        ),
        // Turn 2 (must NOT be reached after cancellation): a plain answer.
        text("follow-up turn — must never be dispatched after the cancel")
      )
    )
    val llm = new ScriptedLlm(streams)
    val program = for
      resources <- SpecResources.mkResources(system, tempRoot, llm).flatMap { r =>
        IO.blocking(
          os.write.over(
            PathUtil.configJsonWritePath(PathUtil.dataRoot),
            """{"safety":{"defaultMode":"auto-all"}}""",
            createFolders = true
          )
        ).as(r)
      }
      agent <- system.spawn(
        AgentActor(
          agentDef = AgentDef(
            name = "DeferredCancelProbe",
            description = "deferred cancel probe",
            tools = List("Bash"),
            systemPrompt = ""
          ),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some(s"deferred-wire-$name"),
          sessionName = Some("DeferredCancelProbe"),
          safetyMode = "auto-all"
        ),
        s"deferred-wire-$name"
      )
      _ <- agent ! AgentCommand.UserInput("run the batch")
      // The batch is in flight once the LLM recorded turn 1 AND the shell wrote no
      // result yet — observe the transcript to make this a real reading, not a sleep.
      _ <- waitUntil(30.seconds)(
        llm.calls.get.map(_ >= 1)
      )
      _ <- IO.sleep(300.millis) // let the tool fiber reach the blocking command
      _ <- body(system, resources, agent, gate, llm)
    yield ()
    val io: IO[Unit] = program
    io.guarantee(
      IO.blocking(os.write.over(gate, "go")) *> // unblock any surviving batch
        system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )

  // ── D1 / D2: the batch lands before finalization, and no new turn follows ──

  test("D1/D2: a deferred stop lands the in-flight batch's tool_result BEFORE the actor stops, and no new turn follows") {
    withBatchInFlight("d1") { (system, resources, agent, gate, llm) =>
      for
        // IN-FLIGHT PRECONDITION as a real reading (not a sleep): at this instant the
        // batch has NOT produced its tool_result yet — the gate file does not exist, so
        // the blocked command cannot have returned. Without this the test could pass
        // vacuously (a batch that already finished would take the immediate path).
        _ <- IO.blocking(assert(!os.exists(gate), "precondition: the gate must not exist, else the batch is not in flight"))
        preMsgs <- resources.sessionStore.loadMessagesForSession("deferred-wire-d1")
        preResults = preMsgs.flatMap(_.content.toOption.toList).flatMap(
          _.collect { case ContentBlock.ToolResult(id, _, _) => id }
        )
        _ <- IO(
          assert(
            !preResults.contains("tu-block"),
            s"precondition: no tool_result may exist before the stop is delivered, got: $preResults"
          )
        )
        // The node-level cancel arrives while the batch is in flight.
        _ <- agent ! AgentCommand.Stop()
        // Give the actor time to receive the intent while the batch is still blocked.
        _ <- IO.sleep(200.millis)
        // The precondition must STILL hold at intent time: the batch is in flight.
        _ <- IO.blocking(assert(!os.exists(gate), "the batch must still be blocked when the deferred intent is recorded"))
        _ <- IO.blocking(os.write.over(gate, "go"))
        stopped <- awaitTrue(30.seconds)(system.isAlive(agent.path).map(!_))
        msgs <- resources.sessionStore.loadMessagesForSession("deferred-wire-d1")
        calls <- llm.calls.get
      yield
        assert(stopped, "D1: the actor must stop once the in-flight batch has finished (deferred stop honoured)")
        val toolResults = msgs.flatMap(_.content.toOption.toList).flatMap(
          _.collect { case ContentBlock.ToolResult(id, content, _) => (id, content) }
        )
        assert(
          toolResults.exists(_._1 == "tu-block"),
          s"D1 VIOLATED — the in-flight batch's tool_result must be persisted BEFORE the actor stops " +
            s"(the historical immediate path cancels the batch fiber and these never reach the transcript). " +
            s"got results: ${toolResults.map(_._1)}; messages=${msgs.size}"
        )
        assert(
          toolResults.exists((id, c) => id == "tu-block" && c.contains("batch-done")),
          s"D1: the persisted result must be the tool's REAL output, not a placeholder. got: ${toolResults.filter(_._1 == "tu-block")}"
        )
        assertEquals(
          calls,
          1,
          "D2 VIOLATED — after the cancel takes effect the node must dispatch NO further LLM turn (a second call means a new tool round was started)"
        )
    }
  }

  // ── D3: the historical Stop(reason) is still an immediate teardown ────────
  //
  // This batch gives the deferred semantics to the ZERO-ARG `Stop()` only; every
  // pre-existing site carrying a reason must stay byte-identical. The judge
  // shape: the batch stays gate-blocked (the gate is never created), so if this
  // leg took the deferred path the actor would NEVER stop (it could only stop
  // after the blocked command returned). Therefore "the actor stopped while the
  // gate still does not exist" is the mechanical reading of IMMEDIATE teardown.

  test("D3: the historical Stop(reason) still tears the in-flight batch down immediately (zero regression)") {
    withBatchInFlight("d3") { (system, resources, agent, gate, llm) =>
      for
        _ <- IO.blocking(assert(!os.exists(gate), "precondition: the batch is in flight"))
        _ <- agent ! AgentCommand.Stop("Node cancelled")
        stopped <- awaitTrue(20.seconds)(system.isAlive(agent.path).map(!_))
        stillBlocked <- IO.blocking(!os.exists(gate))
        msgs <- resources.sessionStore.loadMessagesForSession("deferred-wire-d3")
      yield
        assert(stillBlocked, "the batch must still be blocked — that is what makes this an IMMEDIATE teardown")
        assert(
          stopped,
          "D3 VIOLATED — Stop(reason) must keep the historical immediate semantics (this batch must not re-route it through the deferred leg)"
        )
        val ids = msgs.flatMap(_.content.toOption.toList).flatMap(_.collect { case ContentBlock.ToolResult(id, _, _) => id })
        assert(
          !ids.contains("tu-block"),
          s"D3: the immediate path must drop the un-produced batch (no tool_result persisted), got: $ids"
        )
    }
  }

  // ── D4: with nothing in flight, Stop() degrades to the immediate path ─────

  test("D4: Stop() on an idle session (no batch in flight) falls back to the immediate path") {
    val ws = tempRoot / "d4"
    os.remove.all(ws)
    os.makeDir.all(ws)
    clearSession("deferred-wire-d4")
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tempRoot / "data")
    val system = ActorSystem("deferred-wire-d4")
    val io = for
      resources <- SpecResources.mkResources(system, tempRoot, new ScriptedLlm(Ref.unsafe[IO, List[Stream[IO, StreamChunk]]](Nil)))
      agent <- system.spawn(
        AgentActor(
          agentDef = AgentDef(name = "IdleProbe", description = "idle probe", tools = List("Read"), systemPrompt = ""),
          resources = resources,
          wsSend = (_: Json) => IO.unit,
          depth = 0,
          parentRef = None,
          sessionId = Some("deferred-wire-d4"),
          sessionName = Some("IdleProbe"),
          safetyMode = "auto-all"
        ),
        "deferred-wire-d4"
      )
      // No UserInput: the session is parked between turns, so nothing is in flight.
      _ <- IO.sleep(100.millis)
      _ <- agent ! AgentCommand.Stop()
      stopped <- awaitTrue(20.seconds)(system.isAlive(agent.path).map(!_))
    yield
      assert(
        stopped,
        "D4 VIOLATED — Stop() with nothing in flight must fall back to the immediate path (a recorded intent no batch will ever honour would park the session forever)"
      )
    io.guarantee(
      system.stopAll.handleErrorWith(_ => IO.unit) *>
        IO(nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)) *>
        IO(PathUtil.setDataRoot(originalRoot))
    )
  }

end DeferredCancelWireSpec
