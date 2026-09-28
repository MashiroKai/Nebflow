package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.shared.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeTools}
import nebflow.core.{RateLimiter, SessionStore}
// W1 shim: main's `OutEdge.root` constructor was renamed PR-side to `OutEdge.root`
// (same legacy {pass,failed}/result Nebula edge); usages below point at the new name.
import nebflow.llm.ModelCandidate
import nebflow.shared.ThinkingConfig // W1 shim: main had nebflow.llm.ThinkingConfig; PR moved it to shared
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * **Verdict-gate visibility batch** (mergeverdictvis, 2026-09-23) engine/payload regression
 * family.
 *
 * The defect this family pins: a merge position held by the verdict gate produces **no**
 * `mergeQueue` slot at all — [[NodeEngine.mergeQueueHolders]] drops candidates that are
 * themselves verdict-held (the admission narrowing recorded in [[MergeMutexPolicy]]), and
 * [[NodeEngine.mergeQueueSlotsBatch]] keeps non-empty entries only. The payload therefore
 * showed `mergeQueuePos.position = 1` with **nobody listed anywhere**, which reads as "first
 * in line and nobody blocks me" while an upstream verifier carrying a non-pass verdict is the
 * real cause. Live shape (read 2026-09-23 from the `nebflow` project flow-map):
 * `n-b65fe196` `mergeQueue` absent, `mergeQueuePos.position = 1 / total = 3`, held by
 * `n-47e4b7d4` (verifier, `blocked`, `lastVerdict` undeclared).
 *
 * This batch lifts that state onto the payload as the conditional read-only key
 * `mergeVerdictGate = {blocked, heldBy:[{id,name,role,lastVerdict}], reason}`.
 *
 * 🔴 The three hard requirements from the ruling, one case each:
 *   ① **zero behaviour change** — the gate predicate, the FIFO order and the semantics of
 *      `mergeQueue` / `mergeQueuePos` are byte-level untouched (cases ⑤ + ⑥);
 *   ② a held position **can be read as "held by whom"** (cases ①/②/④);
 *   ③ **a no-false-positive criterion** — a normally queued position MUST NOT be marked as
 *      verdict-held, i.e. the key MUST be ABSENT there (case ③ + the shared assertion
 *      `assertNotVerdictHeld`).
 *
 * 🔴 Judging authority discipline (verbatim same as the queue families): the authoritative
 * source is the engine single point (`mergeVerdictGateBatch` → `staleVerdictUps`, the very
 * list the gate acts on) — never re-derived by the frontend/dispatcher, never read from the
 * file ticket layer (`.nebflow/locks/main-merge.queue`), never replayed from the event stream.
 *
 * Mutation arm (self-proof, mandatory): reverse the non-pass predicate in
 * `NodeEngine.staleVerdictUps` (`!u.lastVerdict.exists(_.trim.equalsIgnoreCase("pass"))` →
 * `u.lastVerdict.exists(_.trim.equalsIgnoreCase("pass"))`) ⇒ the key MUST disappear from the
 * position that should carry it ⇒ cases ①/②/④ MUST go red.
 */
class MergeVerdictGateVisibilitySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-merge-verdict-gate-vis"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge verdict gate visibility spec agent","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** This family starts NO node (every judgement is pure state derivation): the LLM leg is
    * only an assembly placeholder and fails if it is ever called. */
  private val deadLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("no node may be started by this spec"))
    def sendStream(req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      Stream.eval(IO.raiseError(new RuntimeException("no node may be started by this spec")))

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = deadLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  private def mountProject(name: String, ws: os.Path, system: ActorSystem,
      res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store, system, res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  /** Payload read (the **real entry point** = the serialization face shared by NodeList /
    * REST flow-map). */
  private def payload(rt: ProjectRuntime): IO[Json] = NodeTools.buildNodeListPayload(rt)

  private def nodeJson(p: Json, id: String): Json =
    p.hcursor.downField("nodes").as[List[Json]].getOrElse(Nil)
      .find(_.hcursor.get[String]("id").contains(id))
      .getOrElse(fail(s"node '$id' missing from payload"))

  // ── mergeVerdictGate reads ────────────────────────────────────────────────

  private def vgField(p: Json, id: String): Option[Json] =
    nodeJson(p, id).hcursor.downField("mergeVerdictGate").focus

  private def vgKeys(p: Json, id: String): Set[String] =
    vgField(p, id).flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def vgBlocked(p: Json, id: String): Option[Boolean] =
    vgField(p, id).flatMap(_.hcursor.get[Boolean]("blocked").toOption)

  private def vgReason(p: Json, id: String): Option[String] =
    vgField(p, id).flatMap(_.hcursor.get[String]("reason").toOption)

  private def vgHeld(p: Json, id: String): List[Json] =
    vgField(p, id).flatMap(_.hcursor.downField("heldBy").as[List[Json]].toOption).getOrElse(Nil)

  private def vgHeldIds(p: Json, id: String): List[String] =
    vgHeld(p, id).flatMap(_.hcursor.get[String]("id").toOption)

  private def vgHeldField(p: Json, subject: String, holderId: String, field: String): Option[String] =
    vgHeld(p, subject)
      .find(_.hcursor.get[String]("id").contains(holderId))
      .flatMap(_.hcursor.get[String](field).toOption)

  private def vgHeldKeys(p: Json, subject: String, holderId: String): Set[String] =
    vgHeld(p, subject)
      .find(_.hcursor.get[String]("id").contains(holderId))
      .flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  // ── the frozen keys, read side by side (zero-drift reading) ───────────────

  private def mqField(p: Json, id: String): Option[Json] =
    nodeJson(p, id).hcursor.downField("mergeQueue").focus

  private def posField(p: Json, id: String): Option[Json] =
    nodeJson(p, id).hcursor.downField("mergeQueuePos").focus

  private def posPair(p: Json, id: String): Option[(Int, Int)] =
    posField(p, id).flatMap { j =>
      for
        a <- j.hcursor.get[Int]("position").toOption
        b <- j.hcursor.get[Int]("total").toOption
      yield (a, b)
    }

  /** **The no-false-positive assertion (requirement ③), machine-recomputable**: a position
    * that is merely queued (its own upstream verifiers all carry `pass`, or it has no
    * verifier upstream at all) MUST NOT carry the verdict-gate key. */
  private def assertNotVerdictHeld(p: Json, id: String, why: String): Unit =
    assertEquals(vgField(p, id), None,
      s"$why — a normally queued position MUST NOT be marked as verdict-held: ${nodeJson(p, id)}")

  // ── fixtures ──────────────────────────────────────────────────────────────

  /** Merge node (entry form: no in and no deps ⇒ arrival time readyAt = createdAt). */
  private def mergeNode(id: String, name: String, status: String, createdAt: Long): NodeDef =
    NodeDef(id = id, name = name, agent = "general", merge = true, task = Some(s"landing $name"),
      status = status, out = List(OutEdge.root), createdAt = createdAt)

  /** A plain (non-merge) executor node, still running ⇒ its downstream merge peers have not
    * arrived (rank primary = MaxValue) ⇒ they are queue contenders that hold nobody. */
  private def runningImpl(id: String, name: String, createdAt: Long): NodeDef =
    NodeDef(id = id, name = name, agent = "coder", task = Some("work"),
      status = NodeLifecycle.Running, out = List(OutEdge.root), createdAt = createdAt,
      startedAt = Some(createdAt + 10L))

  /** A running merge node inside the critical section (always the top-priority holder). */
  private def runningHolder(id: String, name: String, createdAt: Long): NodeDef =
    NodeDef(id = id, name = name, agent = "general", merge = true, task = Some(s"landing $name"),
      status = NodeLifecycle.Running, out = List(OutEdge.root),
      startedAt = Some(createdAt + 10L), createdAt = createdAt)

  /** Upstream verifier: `status` + a verdict in `lastVerdict`. */
  private def verifier(id: String, name: String, status: String, verdict: Option[String],
      createdAt: Long, completedAt: Option[Long] = None): NodeDef =
    NodeDef(id = id, name = name, agent = "general", task = Some("verify"), role = NodeRoles.Verifier,
      status = status, lastVerdict = verdict, out = Nil, createdAt = createdAt,
      completedAt = completedAt.orElse(Some(createdAt + 1000L)))

  /** The **live shape** fixture: one merge position held by a non-pass upstream verifier,
    * with two sibling merge peers that have not arrived yet (their upstream still runs).
    * The held position is therefore rank 1 of 3 while holding **nobody** — its `mergeQueue`
    * key is absent, which is exactly what made the state unreadable. */
  private def liveShape(now: Long): List[NodeDef] =
    List(
      verifier("n-ver", "chatmsgops-verify2", NodeLifecycle.Blocked, None,
        now - 200_000L, completedAt = Some(now - 100_000L)),
      runningImpl("n-live", "still-working", now - 150_000L),
      mergeNode("n-sink", "chatmsgops-sink", NodeLifecycle.Wiring, now - 80_000L)
        .copy(in = List("n-ver")),
      mergeNode("n-late1", "visionfix-sink", NodeLifecycle.Wiring, now - 70_000L)
        .copy(in = List("n-live")),
      mergeNode("n-late2", "mergeverdictvis-sink", NodeLifecycle.Wiring, now - 60_000L)
        .copy(in = List("n-live")))

  // ── ① the held position can be read as "held by whom" (live shape) ───────

  test("① held position (live shape): the merge node kept by a non-pass upstream verifier carries mergeVerdictGate{blocked=true,heldBy[verifier],reason} while mergeQueue stays ABSENT and mergeQueuePos still says rank 1 of 3 — the state is now readable") {
    val ws = tempRoot / "ws-hold"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-hold-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mvg-hold", ws, system, res)
      _ <- seed(rt, liveShape(now)*)
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // precondition: this is exactly the shape that was unreadable
      assertEquals(mqField(p, "n-sink"), None,
        s"precondition: the verdict-held position produces no mergeQueue slot at all: ${nodeJson(p, "n-sink")}")
      assertEquals(posPair(p, "n-sink"), Some((1, 3)),
        s"precondition: and the queue place still reads 'first of three': ${posField(p, "n-sink")}")
      // the new key says who holds the position and why
      assertEquals(vgBlocked(p, "n-sink"), Some(true),
        s"a verdict-held position must carry blocked=true: ${nodeJson(p, "n-sink")}")
      assertEquals(vgReason(p, "n-sink"), Some("verdict-not-pass"), "the reason is the frozen literal")
      assertEquals(vgHeldIds(p, "n-sink"), List("n-ver"),
        s"heldBy must name the blocking upstream verifier: ${nodeJson(p, "n-sink")}")
      assertEquals(vgHeldField(p, "n-sink", "n-ver", "name"), Some("chatmsgops-verify2"),
        "heldBy must carry the verifier NAME (the human-readable face)")
      assertEquals(vgHeldField(p, "n-sink", "n-ver", "role"), Some(NodeRoles.Verifier),
        "heldBy must carry the upstream role verbatim")
      assertEquals(vgHeldField(p, "n-sink", "n-ver", "lastVerdict"), Some("none"),
        "an undeclared verdict renders as the literal none (same wording as the gate hold message)")

  }

  // ── ② lastVerdict rendering: verbatim, with the shared "none" fallback ───

  test("② lastVerdict rendering: the upstream verdict is carried verbatim, and an undeclared / blank one renders as the literal none (no third vocabulary)") {
    val ws = tempRoot / "ws-verdicts"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-vd-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mvg-vd", ws, system, res)
      _ <- seed(rt,
        verifier("n-fail", "verify-fail", NodeLifecycle.Completed, Some("fail"), now - 200_000L),
        verifier("n-none", "verify-none", NodeLifecycle.Blocked, None, now - 190_000L),
        verifier("n-blank", "verify-blank", NodeLifecycle.Completed, Some("   "), now - 180_000L),
        runningImpl("n-live", "still-working", now - 150_000L),
        mergeNode("n-subject", "docs-merge", NodeLifecycle.Wiring, now - 80_000L)
          .copy(in = List("n-fail", "n-none", "n-blank")))
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(vgHeldIds(p, "n-subject"), List("n-fail", "n-none", "n-blank"),
        s"every non-pass verifier must be listed (declaration order preserved): ${nodeJson(p, "n-subject")}")
      assertEquals(vgHeldField(p, "n-subject", "n-fail", "lastVerdict"), Some("fail"),
        "a declared verdict is carried verbatim")
      assertEquals(vgHeldField(p, "n-subject", "n-none", "lastVerdict"), Some("none"),
        "an undeclared verdict (None) renders as none")
      assertEquals(vgHeldField(p, "n-subject", "n-blank", "lastVerdict"), Some("none"),
        "a blank verdict renders as none (the same conservative reading the gate itself applies)")

  }

  // ── ③ the no-false-positive criterion (requirement ③) ────────────────────

  test("③ no false positive: a position whose upstream verifiers all carry pass, a position with no verifier upstream, and an entry-form position all keep the key ABSENT — while the frozen key still shows them genuinely queued") {
    val ws = tempRoot / "ws-nofp"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-nofp-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mvg-nofp", ws, system, res)
      _ <- seed(rt,
        // upstream verifier that DID pass ⇒ not a holder
        verifier("n-pass", "verify-pass", NodeLifecycle.Completed, Some("pass"), now - 300_000L),
        // upstream executor (no verifier at all) ⇒ not a holder
        NodeDef(id = "n-up", name = "impl", agent = "coder", task = Some("work"),
          status = NodeLifecycle.Completed, out = List(OutEdge.root), createdAt = now - 290_000L,
          completedAt = Some(now - 200_000L)),
        runningHolder("n-holder", "attach-merge", now - 90_000L),
        // (a) all-pass verifier upstream ⇒ merely queued
        mergeNode("n-after-pass", "merge-after-pass", NodeLifecycle.Wiring, now - 80_000L)
          .copy(in = List("n-pass")),
        // (b) no verifier upstream ⇒ merely queued
        mergeNode("n-after-impl", "merge-after-impl", NodeLifecycle.Wiring, now - 70_000L)
          .copy(in = List("n-up")),
        // (c) entry-form merge node (no upstream at all) ⇒ merely queued
        mergeNode("n-entry", "merge-entry", NodeLifecycle.Wiring, now - 60_000L))
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertNotVerdictHeld(p, "n-after-pass",
        "its upstream verifier carries pass")
      assertNotVerdictHeld(p, "n-after-impl",
        "it has no verifier upstream")
      assertNotVerdictHeld(p, "n-entry",
        "an entry-form merge node has no upstream at all")
      assertNotVerdictHeld(p, "n-holder",
        "the merge node inside the critical section is not held by the verdict gate")
      // …and the counter-face inside the same payload: those nodes ARE genuinely queued, so
      // the frozen key is present — the absence above is specific to the new key, not a
      // "no key anywhere" artefact of the fixture.
      for id <- List("n-after-pass", "n-after-impl", "n-entry") do
        assert(mqField(p, id).isDefined,
          s"fixture sanity: '$id' must be genuinely queued on the frozen key: ${nodeJson(p, id)}")

  }

  // ── ④ upstream set: verbatim same source as the gate (in ∪ deps) ─────────

  test("④ upstream set is the gate's own (in ∪ deps expansion): a verifier reached through deps holds the position exactly as one reached through in does") {
    val ws = tempRoot / "ws-deps"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-deps-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mvg-deps", ws, system, res)
      _ <- seed(rt,
        verifier("n-verdep", "verify-via-deps", NodeLifecycle.Blocked, None, now - 200_000L),
        runningImpl("n-live", "still-working", now - 150_000L),
        // upstream reached ONLY through deps (no in edge at all)
        mergeNode("n-via-deps", "merge-via-deps", NodeLifecycle.Wiring, now - 80_000L)
          .copy(deps = List("n-verdep")),
        mergeNode("n-peer", "peer-sink", NodeLifecycle.Wiring, now - 70_000L)
          .copy(in = List("n-live")))
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(vgHeldIds(p, "n-via-deps"), List("n-verdep"),
        s"the upstream set must expand deps exactly as the gate does (verbatim same source): ${nodeJson(p, "n-via-deps")}")
      assertEquals(vgReason(p, "n-via-deps"), Some("verdict-not-pass"))
      assertEquals(vgBlocked(p, "n-via-deps"), Some(true))
      assertEquals(mqField(p, "n-via-deps"), None,
        "and the mergeQueue slot stays absent for this shape too")
      assertNotVerdictHeld(p, "n-peer",
        "a deps-free peer with a running (non-verifier) upstream is merely queued")

  }

  // ── ⑤ field sets exact + zero drift on the frozen keys ───────────────────

  test("⑤ field sets and zero drift: the new key carries exactly {blocked,heldBy,reason} with entries exactly {id,name,role,lastVerdict}, and mergeQueue/mergeQueuePos keep their literal values") {
    val ws = tempRoot / "ws-fields"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-fields-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mvg-fields", ws, system, res)
      _ <- seed(rt, liveShape(now)*)
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(vgKeys(p, "n-sink"), Set("blocked", "heldBy", "reason"),
        s"the verdict-gate key must carry exactly the declared fields: ${vgField(p, "n-sink")}")
      assertEquals(vgHeldKeys(p, "n-sink", "n-ver"), Set("id", "name", "role", "lastVerdict"),
        s"heldBy entries must carry exactly the declared fields: ${vgHeld(p, "n-sink")}")
      // frozen keys byte-level untouched: same field sets, same literal values
      assertEquals(mqField(p, "n-sink"), None,
        "the frozen mergeQueue key stays absent for a verdict-held position (unchanged semantics)")
      assertEquals(posField(p, "n-sink").flatMap(_.asObject).map(_.keys.toSet - "sameKeyProjects"),
        Some(Set("position", "total", "queue", "arrived", "readyAt", "createdAt", "rank")),
        s"mergeQueuePos keeps its field set untouched: ${posField(p, "n-sink")}")
      assertEquals(posPair(p, "n-sink"), Some((1, 3)), "mergeQueuePos keeps its literal values")
      // the arriving peers keep exactly the pre-existing behaviour: no verdict key (their
      // upstream is a running executor, not a verifier)
      for id <- List("n-late1", "n-late2") do assertNotVerdictHeld(p, id, "its upstream is a running executor")

  }

  // ── ⑥ derived only: no persisted field, no WS-event key ──────────────────

  test("⑥ derived only: the key is never persisted on NodeDef, and the WS event serialization point (no snapshot injection) never carries it") {
    val ws = tempRoot / "ws-derived"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-der-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    val nodes = liveShape(now)
    val subject = nodes.find(_.id == "n-sink").getOrElse(fail("fixture must contain n-sink"))
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mvg-der", ws, system, res)
      _ <- seed(rt, nodes*)
      p <- payload(rt)
      stored <- rt.store.getNode("n-sink")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(vgBlocked(p, "n-sink"), Some(true), "precondition: the position is verdict-held")
      // pure derived quantity: no NodeDef field, so it cannot enter flow-map.json / the archive batch
      val persisted = stored.getOrElse(fail("n-sink must exist")).asJson
      assertEquals(persisted.hcursor.downField("mergeVerdictGate").focus, None,
        s"mergeVerdictGate must never be persisted on NodeDef: $persisted")
      // WS event single serialization point (no snapshot injection) keeps a zero-drift field set
      val evt = NodePayload.buildNodeJson(subject, System.currentTimeMillis())
      assertEquals(evt.hcursor.downField("mergeVerdictGate").focus, None,
        s"the WS event payload must not carry the snapshot-only key: $evt")
      assertEquals(evt.asObject.map(_.keys.toSet).exists(_.contains("mergeVerdictGate")), false,
        "no injection ⇒ the key is structurally absent")

  }

  // ── the mutation arm's anchor (kept as a case so the red run is reproducible) ──

  test("mutation anchor: the non-pass predicate is the ONLY source of the held list — reversing it must empty the key for a non-pass upstream") {
    val ws = tempRoot / "ws-mut"; os.makeDir.all(ws)
    val system = ActorSystem(s"mvg-mut-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mvg-mut", ws, system, res)
      _ <- seed(rt, liveShape(now)*)
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // Under the shipped predicate the non-pass upstream IS held. The mutation arm reverses
      // the predicate inside NodeEngine.staleVerdictUps, which must empty this list (the
      // mutation is applied in this worktree only, never in the shared workspace).
      assertEquals(vgHeldIds(p, "n-sink"), List("n-ver"),
        s"the held list is produced by the gate's own non-pass predicate: ${nodeJson(p, "n-sink")}")

  }

end MergeVerdictGateVisibilitySpec
