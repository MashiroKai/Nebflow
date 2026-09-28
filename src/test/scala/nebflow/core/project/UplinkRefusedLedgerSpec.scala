package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.agent.*
import nebflow.core.{FileChangeTracker, PathUtil}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * Uplink fail-closed refusal-face acceptance (taskunify merge batch 2026-09-24 ·
 * **ruling T** · implplan §8 criteria 8.3–8.6 / §10.4 criteria 10.4.1–10.4.6).
 *
 * **Semantics under test**: when a node's attribution cannot be resolved (no `taskId`
 * fingerprint), its **engine-side uplinks** (U1–U7) are **strictly refused fail-closed**
 * per Q2ⓐ, and the refusal must leave a **double trace**:
 *  ① **event face** = exactly one `uplink-refused` (`<ws>/.nebflow/flow-map-events.jsonl`);
 *  ② **the three text elements** = within that line **node id** ∧ **refusal reason**
 *     (`no-attribution`) ∧ **way out** (`register-attribution` / `node_report` / `Flow Map`);
 *  ③ **the landing/merge class is distinguishable** (`kind=landing`) — **U3's no-loss
 *     argument does not cover it**, hence it is listed separately;
 *  ④ **no merging, no suppression** (two in a row ⇒ two events);
 *  ⑤ **the fallback switch** (`Some(false)` ⇒ equivalent to `nebflow.taskledger.enabled=false`):
 *     byte-for-byte legacy behaviour, **zero** `uplink-refused` (criterion 10.3③ "after the
 *     fallback, grep -c on refusal events = 0").
 *
 * **Wiring discipline**: a real `NodeEngine` + real terminal write points + real delivery
 * seams (`deliverFailed` / `mergeBlockedByUpstreamFailure` are the engine's real entry
 * points, not a direct call into the function under test). The LLM is a recording stub
 * (this spec does not assert the LLM glue layer).
 *
 * **Mutation red-anchor (implplan §10.4)**: comment out `uplinkAllowed(bn, "landing")` inside
 * [[NodeEngine.mergeBlockedByUpstreamFailure]] ⇒ the U3 case of this spec **must go red** —
 * that is precisely the mechanical way to detect "the landing class being silenced".
 */
class UplinkRefusedLedgerSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** Silent recording LLM (zero tool calls; this spec never drives an LLM turn, it is only a
    * fallback). */
  private class EchoLlm:
    val handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    lazy val b: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  /** Engine assembly (same discipline as `FailedNotifySuppressionSpec.withFixture` /
    * `CancelSemanticsSourceSpec`). `failClosed` = the injected uplink-gate value
    * (`Some(false)` = fall back to legacy behaviour). */
  private def withFixture(name: String, failClosed: Option[Boolean])(
      body: (FlowMapStore, NodeEngine, os.Path) => Unit
  ): Unit =
    val tmp = os.temp.dir(prefix = s"uplinkref-$name")
    PathUtil.setDataRoot(tmp / "data")
    os.makeDir.all(tmp / "data" / "agents" / "test-agent")
    os.write.over(tmp / "data" / "agents" / "test-agent" / "agent.json",
      """{"name":"test-agent","description":"uplinkref spec agent","tools":[],"category":"standalone"}""")
    os.write.over(tmp / "data" / "agents" / "test-agent" / "system.md", "# test-agent\n")
    val system = ActorSystem(s"uplinkref-$name")
    try
      val ws = tmp / "ws"
      val io = for
        _ <- IO(os.makeDir.all(ws))
        store <- FlowMapStore.open(s"uplinkref-$name", ws.toString)
        dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        resources = SharedResources(
          llm = new EchoLlm().handle,
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
          voiceMutedRef = voiceMuted
        )
        rootSid = "uplinkref-root"
        rootRef <- system.spawn(recorderBehavior(Ref.unsafe[IO, List[AgentCommand]](Nil)), s"ur-root-${name.take(8)}")
        _ <- resources.agentRegistry.update(_ + (rootSid -> AgentRecord(rootSid, rootRef, AgentKind.Root, rootSid)))
        engine = new NodeEngine(
          store, system, resources, _ => IO.unit, ws.toString, rootSid, s"uplinkref-$name",
          FeedbackRouter.ModeAuto, (_, _, _) => IO.unit,
          taskLedgerUplinkFailClosed = failClosed)
      yield (store, engine, ws)
      val (store, engine, wsPath) = io.unsafeRunSync()
      body(store, engine, wsPath)
    finally
      PathUtil.setDataRoot(originalRoot)
      system.stopAll.attempt.void.unsafeRunSync()

  private def eventLines(ws: os.Path): List[String] =
    val f = ws / ".nebflow" / FlowMapEventLog.FileName
    if os.exists(f) then os.read.lines(f).toList else Nil

  private def put(store: FlowMapStore, n: NodeDef): Unit =
    store.mutate(s => s.copy(nodes = s.nodes + (n.id -> n))).void.unsafeRunSync()

  private val now: Long = System.currentTimeMillis()

  /** An ordinary node with no attribution (`taskId = None`). */
  private def unattributed(id: String, name: String, status: String = NodeLifecycle.Completed): NodeDef =
    NodeDef(id = id, name = name, agent = "general", task = Some(s"$name task"),
      status = status, result = Some(s"$name result"),
      out = List(OutEdge.nebula), createdAt = now - 1000L, completedAt = Some(now))

  /** An unattributed merge (landing) node: `merge=true` + `pending` ⇒ `haltsOnFailure` hits. */
  private def unattributedMerge(id: String, name: String, in: List[String]): NodeDef =
    NodeDef(id = id, name = name, agent = "general", merge = true, task = Some(s"$name landing task"),
      status = NodeLifecycle.Pending, in = in, out = List(OutEdge.nebula), createdAt = now - 1000L)

  /** Control-group node with attribution (carrying the fingerprint). */
  private def attributed(id: String, name: String, taskId: String): NodeDef =
    NodeDef(id = id, name = name, agent = "general", task = Some(s"$name task"),
      status = NodeLifecycle.Completed, result = Some(s"$name result"), taskId = Some(taskId),
      out = List(OutEdge.nebula), createdAt = now - 1000L, completedAt = Some(now))

  private def refusals(ws: os.Path): List[String] =
    eventLines(ws).filter(_.contains("\"type\":\"uplink-refused\""))

  // ── ① U3 landing/merge class: the refusal is not silent (criterion 10.4.3 — the core) ──

  test("(1) U3 landing/merge uplink refused ⇒ exactly one event with kind=landing (criterion 10.4.3: landing distinguishable)") {
    withFixture("u3", Some(true)) { (store, engine, ws) =>
      val fail = unattributed("n-u3-fail", "u3-worker", NodeLifecycle.Failed)
      val sink = unattributedMerge("n-u3-sink", "u3-landing-sink", List("n-u3-fail"))
      put(store, fail)
      put(store, sink)
      engine.mergeBlockedByUpstreamFailure(sink, fail, "upstream exploded").unsafeRunSync()
      val rs = refusals(ws)
      assertEquals(rs.size, 1, s"a refused U3 ⇒ exactly one uplink-refused, got:\n${eventLines(ws).mkString("\n")}")
      val line = rs.head
      assert(line.contains("kind=landing"), s"the landing/merge class must be distinguishable (kind=landing), got: $line")
      assert(line.contains("node=n-u3-sink"), s"must carry the node id, got: $line")
      assert(line.contains("reason=no-attribution"), s"must carry the refusal reason, got: $line")
      assert(line.contains("way="), s"must carry the way out, got: $line")
      assert(line.contains("way=register-attribution") || line.contains("node_report"),
        s"the way out must be one of the actionable ones, got: $line")
    }
  }

  // ── ② U1 failure uplink: refusal traced + all three elements present ──

  test("(2) U1 node-failed uplink refused ⇒ one event with all three elements (node id ∧ reason ∧ way out) (criterion 10.4.4)") {
    withFixture("u1", Some(true)) { (store, engine, ws) =>
      val n = unattributed("n-u1-a", "u1-worker", NodeLifecycle.Running)
        .copy(sessionRef = Some("u1-session-0001"))
      put(store, n)
      // real failure entry: failStuckRecovery → failNode → deliverFailed is the engine's real
      // failed leg (the node's sessionRef names it the owner of the failing session)
      engine.failStuckRecovery("u1-session-0001", "boom").unsafeRunSync()
      val rs = refusals(ws)
      assert(rs.nonEmpty, s"an unattributed node's uplink must be refused and traced, got:\n${eventLines(ws).mkString("\n")}")
      val line = rs.head
      assert(line.contains("node=n-u1-a"), s"must carry the node id, got: $line")
      assert(line.contains("reason=no-attribution"), s"must carry the refusal reason, got: $line")
      assert(line.contains("way="), s"must carry the way out, got: $line")
    }
  }

  // ── ③ no merging, no suppression (criterion 10.4.2) ──

  test("(3) two refusals in a row ⇒ two events (criterion 10.4.2: no merging, no suppression)") {
    withFixture("twice", Some(true)) { (store, engine, ws) =>
      val fail = unattributed("n-tw-fail", "tw-worker", NodeLifecycle.Failed)
      val sink = unattributedMerge("n-tw-sink", "tw-sink", List("n-tw-fail"))
      put(store, fail)
      put(store, sink)
      engine.mergeBlockedByUpstreamFailure(sink, fail, "boom-1").unsafeRunSync()
      val after1 = refusals(ws).size
      // reset the sink to wiring and trigger once more (the same node tries its uplink again)
      store.mutate { s =>
        s.nodes.get(sink.id) match
          case Some(cur) => s.copy(nodes = s.nodes.updated(sink.id, cur.copy(status = NodeLifecycle.Pending)))
          case None      => s
      }.void.unsafeRunSync()
      engine.mergeBlockedByUpstreamFailure(sink, fail, "boom-2").unsafeRunSync()
      val after2 = refusals(ws).size
      assertEquals(after1, 1, "first refusal ⇒ exactly one")
      assertEquals(after2, 2, "second refusal ⇒ a second event (**no merging, no suppression** — this is a hard criterion)")
    }
  }

  // ── ④ attributed ⇒ allowed (the gate is not a blanket refusal) ──

  test("(4) attribution fingerprint present (taskId) ⇒ allowed, zero uplink-refused (the gate only blocks the unattributed)") {
    withFixture("attributed", Some(true)) { (store, engine, ws) =>
      val a = attributed("n-ok-a", "ok-worker", "7")
      put(store, a)
      engine.deliverOutTo(a, OutEdge.NebulaTarget, "fine").unsafeRunSync()
      assertEquals(refusals(ws).size, 0,
        s"an attributed node must be allowed, got:\n${eventLines(ws).mkString("\n")}")
    }
  }

  // ── ⑤ fallback switch (criteria 10.3③ / 10.4.6) ──

  test("(5) fallback switch (failClosed=Some(false), equivalent to off) ⇒ byte-for-byte legacy behaviour, zero uplink-refused") {
    withFixture("off", Some(false)) { (store, engine, ws) =>
      val fail = unattributed("n-off-fail", "off-worker", NodeLifecycle.Failed)
      val sink = unattributedMerge("n-off-sink", "off-sink", List("n-off-fail"))
      put(store, fail)
      put(store, sink)
      engine.mergeBlockedByUpstreamFailure(sink, fail, "boom").unsafeRunSync()
      assertEquals(refusals(ws).size, 0,
        s"criterion 10.3③: after the fallback the refusal-event count must be 0, got:\n${eventLines(ws).mkString("\n")}")
      // and U3's merge-blocked audit still happens (the legacy visible face loses nothing)
      assert(eventLines(ws).exists(_.contains("\"type\":\"merge-blocked\"")),
        "in the fallback state the existing merge-blocked trace must be unchanged")
    }
  }

  // ── ⑥ U8 unaffected: the node_report channel does not go through this gate ──

  test("(6) U8 (node_report) unaffected: the gate only blocks the engine-side uplink circuit, the session-level declaration face is untouched") {
    withFixture("u8", Some(true)) { (store, engine, ws) =>
      // an unattributed node can still declare at the session level (this spec's mechanical face
      // = the gate's injection points live in the engine's delivery legs, NodeReportTool is not
      // among them — asserted against the source face, i.e. "node_report still accepted with the
      // gate on")
      val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "NodeEngine.scala")
      assert(src.contains("U8 (`node_report`) is unaffected"),
        "the gate's documentation face must explicitly state that U8 is unaffected (the discipline anchor of criterion 10.4.6)")
      val reportSrc = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "NodeReportTool.scala")
      assert(!reportSrc.contains("uplinkAllowed"),
        "the U8 implementation face must NOT call this gate (the session-level channel is unrelated to the taskId face — criterion 10.4.6)")
    }
  }
