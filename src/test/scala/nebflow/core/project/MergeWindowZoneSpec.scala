package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeTools}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * **merge-window zone-caliber** regression suite (engdef-mergewindow batch).
 *
 * Subject: the merge-window predicate reads its node map from **one source** — the two-zone
 * combined map (`active ∪ archive`), the same source `NodeEngine.depsSatisfied` uses. The
 * FIFO rank primary key is `readyAt = max(upstream completedAt)`; `MergeMutexPolicy.upsOf`
 * resolves upstreams through `all.get`, so a **single-zone** (active-only) map makes the
 * lookup miss every upstream that a chain-level sweep has since archived — `readyAt` then
 * collapses onto the entry-node branch (`n.createdAt`) and the primary key stops tracking
 * arrival order. Fingerprint of the miss: `readyAt == createdAt`.
 *
 * Direction matters: when such an upstream completes **later** than the sink was created the
 * collapsed key comes out **smaller** than the truth, so the sink outranks an earlier arrival
 * and can enter the critical section ahead of it (the "narrow branch"; a behaviour-face
 * defect). When it completes **earlier** the key comes out larger — a display-face ranking
 * error only. This fixture is built on the **narrow branch** (archived upstreams completing
 * after the sink's `createdAt`), which is the only shape that reproduces the queue jump.
 *
 * Three frozen equations (pre-change RED / post-change GREEN), all on the same fixture:
 *   ① pure predicate: `rankOf(A).head == A.createdAt` and `< rankOf(B).head` (RED)
 *      vs `rankOf(A).head == T0+1000` and `> rankOf(B).head` (GREEN);
 *   ② payload face (`mergeQueuePos`): `position(A) < position(B)` (RED)
 *      vs `position(A) > position(B)` with `rankAt(A) == T0+1000` (GREEN);
 *   ③ behaviour face (mutex gate): `holders(A)` does NOT contain B (RED — A is not blocked by
 *      the earlier arrival) vs the gate's holder set contains B (GREEN).
 * Plus a **mutation anchor**: the engine-level gate entry point itself must return the
 * two-zone holder set — reverting the gate's data source to the active-zone snapshot turns
 * this assertion red (see the batch report for the executed mutation run).
 *
 * 🔴 Sharpness: the `isContender` three-state caliber, the `rank.primary` name (`readyAt`),
 * the tiebreak keys (`createdAt` / `id`) and every payload key set are **unchanged** — only
 * the **value semantics** of `readyAt` change. Key-set assertions below pin that.
 *
 * In-process fixture (`GatedLlm` stand-in + `FlowMapStore.open` + `NodeEngine`): 🔴 no
 * instance is started, no live port is touched, nothing is served over HTTP. No node is ever
 * started, so the LLM leg is a dead placeholder that fails if called.
 */
class MergeWindowZoneSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  /** Unique scratch root (the fixed-path + `os.remove.all` shape races a late-async writer). */
  private val tempRoot: os.Path =
    val scratchBase = os.pwd / "target"
    os.makeDir.all(scratchBase)
    os.temp.dir(dir = scratchBase, prefix = "test-merge-window-zone-")
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"merge window zone spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** No node is started by this suite: the LLM leg is a placeholder that fails when used. */
  private val deadLlm: LlmHandle[IO] = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("no node may be started by this spec"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
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

  private def mountProject(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = name,
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def seed(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  /** Archived-zone injection (the sweep equivalent): `mutateArchive` only. */
  private def seedArchive(rt: ProjectRuntime, nodes: NodeDef*): IO[Unit] =
    rt.store.mutateArchive(a => a.copy(nodes = a.nodes ++ nodes.map(n => n.id -> n).toMap)).void

  /** merge sink (open state; `in` = declared upstream set). */
  private def mergeSink(id: String, name: String, in: List[String], createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      merge = true,
      task = Some(s"landing $name"),
      status = NodeLifecycle.Pending,
      in = in,
      out = List(OutEdge.root),
      createdAt = createdAt
    )

  /** Completed plain upstream: its `completedAt` IS the downstream sink's arrival time. */
  private def doneUpstream(
    id: String,
    name: String,
    out: List[String],
    createdAt: Long,
    completedAt: Long
  ): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"produce $name"),
      status = NodeLifecycle.Completed,
      result = Some(s"$name artifact"),
      out = out.map(OutEdge(_)),
      createdAt = createdAt,
      completedAt = Some(completedAt)
    )

  // ── fixture ───────────────────────────────────────────────────────────────────────────
  //
  // T0 = now.
  //   active zone: B = merge, in=[U3], createdAt = T0-8000
  //                U3 = completed, completedAt = T0-5000
  //                A = merge, in=[U1,U2], createdAt = T0-9000   (A was created EARLIER than B)
  //   archive zone: U1, U2 = completed, completedAt = T0+1000   (later than A's createdAt)
  //   truth:        readyAt(A) = max(T0+1000, T0+1000) = T0+1000 > readyAt(B) = T0-5000
  //                 => A must queue BEHIND B.
  //   pre-change:   upsOf(A) = Nil over the active zone only
  //                 => readyAt(A) = A.createdAt = T0-9000 < T0-5000 => A jumps ahead of B.
  private def fixture(rt: ProjectRuntime, name: String, t0: Long): IO[Unit] =
    seed(
      rt,
      mergeSink("n-b", s"$name-merge-b", List("n-u3"), t0 - 8000L),
      doneUpstream("n-u3", s"$name-up-3", List("n-b"), t0 - 20_000L, t0 - 5000L),
      mergeSink("n-a", s"$name-merge-a", List("n-u1", "n-u2"), t0 - 9000L)
    ) *> seedArchive(
      rt,
      doneUpstream("n-u1", s"$name-up-1", List("n-a"), t0 - 30_000L, t0 + 1000L),
      doneUpstream("n-u2", s"$name-up-2", List("n-a"), t0 - 30_000L, t0 + 1000L)
    )

  private def payload(rt: ProjectRuntime): IO[Json] = NodeTools.buildNodeListPayload(rt)

  private def nodeJson(p: Json, id: String): Json =
    p.hcursor
      .downField("nodes")
      .as[List[Json]]
      .getOrElse(Nil)
      .find(_.hcursor.get[String]("id").contains(id))
      .getOrElse(fail(s"node '$id' missing from payload"))

  private def posField(p: Json, id: String): Option[Json] =
    nodeJson(p, id).hcursor.downField("mergeQueuePos").focus

  private def posInt(p: Json, id: String, field: String): Option[Int] =
    posField(p, id).flatMap(_.hcursor.get[Int](field).toOption)

  private def posLong(p: Json, id: String, field: String): Option[Long] =
    posField(p, id).flatMap(_.hcursor.get[Long](field).toOption)

  private def posKeys(p: Json, id: String): Set[String] =
    posField(p, id).flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def mqField(p: Json, id: String): Option[Json] =
    nodeJson(p, id).hcursor.downField("mergeQueue").focus

  private def mqKeys(p: Json, id: String): Set[String] =
    mqField(p, id).flatMap(_.asObject).map(_.keys.toSet).getOrElse(Set.empty)

  private def holderIds(p: Json, id: String): List[String] =
    mqField(p, id)
      .flatMap(_.hcursor.downField("holders").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("id").toOption)
      .sorted

  /**
   * PRE-CHANGE reconstruction at the very same single points: the pre-change callers handed
   * `MergeMutexPolicy` the **active-zone node map** (`store.snapshot.nodes`). Passing that
   * exact argument to the same entry points reproduces the pre-change readings without a
   * source revert — the red/green pair is then readable within one run, and the mutation arm
   * (a real source revert) is executed out of band and reported.
   */
  private def activeOnly(rt: ProjectRuntime): IO[Map[String, NodeDef]] = rt.store.snapshot.map(_.nodes)

  // ── ① pure predicate level ────────────────────────────────────────────────────────────

  test(
    "① pure predicate: over the ACTIVE-ONLY map `rankOf(A).head` collapses onto `A.createdAt` and outranks B (RED); over the COMBINED map it is `readyAt(A) = T0+1000` and ranks behind B (GREEN)"
  ) {
    val ws = tempRoot / "ws-eq1"; os.makeDir.all(ws)
    val system = ActorSystem(s"mwz-eq1-${scala.util.Random.nextInt(100000)}")
    val t0 = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mwz-eq1", ws, system, res)
      _ <- fixture(rt, "eq1", t0)
      live <- activeOnly(rt)
      arch <- rt.store.archiveSnapshot
      combined = live ++ arch.nodes
      a <- rt.store.getNode("n-a").map(_.getOrElse(fail("n-a must exist")))
      b <- rt.store.getNode("n-b").map(_.getOrElse(fail("n-b must exist")))
      // Engine binding: the pure-predicate pair below is only an anchor for THIS criterion if
      // the engine really feeds it the two-zone map. Read the gate's own store-backed source
      // (it reads the store itself) rather than trusting the test's own concatenation — this
      // is the assertion that turns red if the caller's data source is reverted.
      gateHolders <- rt.engine.mergeMutexHoldersOf(a)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // fixture sanity: the archived upstreams really are OUTSIDE the active zone
      assert(!live.contains("n-u1") && !live.contains("n-u2"), "fixture sanity: U1/U2 must live in the archive zone only")
      assert(live.contains("n-a") && live.contains("n-b"), "fixture sanity: both sinks are active")
      assertEquals(a.createdAt, t0 - 9000L, "fixture sanity: A is created EARLIER than B")
      assertEquals(b.createdAt, t0 - 8000L, "fixture sanity: B is created later than A")
      assert(
        gateHolders.exists(_.id == "n-b"),
        s"engine binding: the gate must see A held by B (its own store-backed source must be the two-zone map), got ${gateHolders.map(_.id)}"
      )

      // ── RED readings (active-zone-only data source = the pre-change caliber) ──
      val redRankA = MergeMutexPolicy.rankOf(a, live)._1
      val redRankB = MergeMutexPolicy.rankOf(b, live)._1
      assertEquals(
        redRankA,
        a.createdAt,
        s"pre-change (RED): with no archived upstream visible `upsOf(A)` is empty, so `readyAt` takes the entry-node branch and returns `createdAt` (the miss fingerprint)"
      )
      assertEquals(
        MergeMutexPolicy.upsOf(a, live),
        Nil,
        s"pre-change (RED): the upstream lookup misses both archived upstreams, got ${MergeMutexPolicy.upsOf(a, live).map(_.id)}"
      )
      assert(
        redRankA < redRankB,
        s"pre-change (RED): the collapsed key ($redRankA) is SMALLER than B's arrival ($redRankB) => A can jump the queue"
      )
      assert(
        redRankA < a.createdAt + 1,
        "pre-change (RED): the collapsed key is unrelated to arrival order (that is the defect, not a display quirk)"
      )

      // ── GREEN readings (two-zone combined map = the post-change caliber) ──
      val greenRankA = MergeMutexPolicy.rankOf(a, combined)._1
      val greenRankB = MergeMutexPolicy.rankOf(b, combined)._1
      assertEquals(
        MergeMutexPolicy.upsOf(a, combined).map(_.id).sorted,
        List("n-u1", "n-u2"),
        "post-change (GREEN): the archived upstreams are visible to the predicate"
      )
      assertEquals(
        greenRankA,
        t0 + 1000L,
        "post-change (GREEN): `readyAt(A)` is `max(upstream completedAt)` — the true arrival time"
      )
      assertEquals(greenRankB, t0 - 5000L, "post-change (GREEN): B's arrival is its own upstream's completion")
      assert(
        greenRankA > greenRankB,
        s"post-change (GREEN): A now ranks BEHIND the earlier arrival B ($greenRankA > $greenRankB)"
      )

      // the only thing that changed is the VALUE semantics — the key shape is frozen
      assertEquals(
        MergeMutexPolicy.rankOf(a, combined).productArity,
        3,
        "the rank key stays a 3-tuple"
      )
      assertEquals(MergeMutexPolicy.RankPrimary, "readyAt", "the rank primary key name must not drift")
      assertEquals(MergeMutexPolicy.RankTiebreaks, List("createdAt", "id"), "the tiebreak keys must not drift")
    end for
  }

  // ── ② payload face (mergeQueuePos) ────────────────────────────────────────────────────

  test(
    "② payload face: the real NodeList payload ranks A behind B with `rankAt == T0+1000` (GREEN); recomputing the same batch over the active-only map reproduces the pre-change inversion (RED)"
  ) {
    val ws = tempRoot / "ws-eq2"; os.makeDir.all(ws)
    val system = ActorSystem(s"mwz-eq2-${scala.util.Random.nextInt(100000)}")
    val t0 = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mwz-eq2", ws, system, res)
      _ <- fixture(rt, "eq2", t0)
      live <- activeOnly(rt)
      // pre-change payload reading: the identical batch entry point, given the identical
      // argument the pre-change caller passed (the active-zone node map).
      redPos <- IO.pure(rt.engine.mergeQueuePositionsBatch(live))
      redSlots <- IO.pure(rt.engine.mergeQueueSlotsBatch(live))
      p <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ── RED readings (payload face, reconstructed at the same entry point) ──
      val redA = redPos.getOrElse("n-a", fail(s"n-a must be queued pre-change, got ${redPos.keySet}"))
      val redB = redPos.getOrElse("n-b", fail(s"n-b must be queued pre-change, got ${redPos.keySet}"))
      assert(
        redA.position < redB.position,
        s"pre-change (RED): the payload showed A ahead of B (${redA.position} < ${redB.position}) — the queue-jump read out as a rank"
      )
      assertEquals(
        redA.rankAt,
        redA.node.createdAt,
        "pre-change (RED): the display rank carried `createdAt` instead of an arrival time (fingerprint `readyAt == createdAt`)"
      )
      assert(
        !redSlots.getOrElse("n-a", Nil).exists(_.node.id == "n-b"),
        "pre-change (RED): the mutex slot face did not name B as a holder of A either"
      )

      // ── GREEN readings (the real payload through the real entry point) ──
      assertEquals(
        (posInt(p, "n-a", "position"), posInt(p, "n-b", "position")),
        (Some(2), Some(1)),
        s"post-change (GREEN): the payload ranks the earlier arrival (B) first and A second: ${posField(p, "n-a")}"
      )
      assert(
        posInt(p, "n-a", "position").getOrElse(0) > posInt(p, "n-b", "position").getOrElse(0),
        "post-change (GREEN): A must queue behind B"
      )
      assertEquals(
        posLong(p, "n-a", "readyAt"),
        Some(t0 + 1000L),
        "post-change (GREEN): the display rank is recomputable — `readyAt` is the true arrival time"
      )
      assertEquals(posInt(p, "n-a", "total"), Some(2), "the contender count is unchanged (both sinks are open)")
      assertEquals(
        posField(p, "n-a").flatMap(_.hcursor.get[Boolean]("arrived").toOption),
        Some(true),
        "post-change (GREEN): A is arrived (its upstreams are all terminal)"
      )
      assertEquals(
        posField(p, "n-a").flatMap(_.hcursor.get[String]("queue").toOption),
        Some(MergeMutexPolicy.QueueName),
        "the queue token is unchanged"
      )

      // 🔴 Sharpness: the payload KEY SET is byte-frozen (only the values moved).
      //
      // `sameKeyProjects` is the pre-existing O-1 degradation key (present when another
      // registered runtime resolves to the same git dir). This fixture root lives inside the
      // enclosing repo, so every project mounted under it resolves to that repo's key and
      // that key shows up once a sibling test has mounted its own project — the same reason
      // the existing merge position/visibility specs subtract this one key before pinning
      // their field set. It is orthogonal to this batch and unchanged by it.
      assertEquals(
        posKeys(p, "n-a") - "sameKeyProjects",
        Set("position", "total", "queue", "arrived", "readyAt", "createdAt", "rank"),
        s"the `mergeQueuePos` key set must not drift: ${posField(p, "n-a")}"
      )
      assertEquals(
        mqKeys(p, "n-a") - "sameKeyProjects",
        Set("ahead", "inSection", "rank", "holders"),
        s"the `mergeQueue` key set must not drift: ${mqField(p, "n-a")}"
      )
      assert(
        posKeys(p, "n-a").subsetOf(
          Set("position", "total", "queue", "arrived", "readyAt", "createdAt", "rank", "sameKeyProjects")
        ),
        s"the two-zone map must not smuggle in a new payload key: ${posField(p, "n-a")}"
      )
      assertEquals(
        mqField(p, "n-a").flatMap(_.hcursor.get[Int]("ahead").toOption),
        Some(1),
        "`ahead` keeps its literal meaning (the size of the blocking set)"
      )
    end for
  }

  // ── ③ behaviour face (the mutex gate itself) ──────────────────────────────────────────

  test(
    "③ behaviour face: the ENGINE gate's holder set for A gains B once the gate reads the two-zone map — A is blocked by the earlier arrival instead of entering the critical section"
  ) {
    val ws = tempRoot / "ws-eq3"; os.makeDir.all(ws)
    val system = ActorSystem(s"mwz-eq3-${scala.util.Random.nextInt(100000)}")
    val t0 = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mwz-eq3", ws, system, res)
      _ <- fixture(rt, "eq3", t0)
      live <- activeOnly(rt)
      arch <- rt.store.archiveSnapshot
      combined = live ++ arch.nodes
      a <- rt.store.getNode("n-a").map(_.getOrElse(fail("n-a must exist")))
      // pre-change hold set: the same single point, given the pre-change argument
      redHolders <- IO.pure(rt.engine.mergeQueueHolders(a, live))
      // post-change hold set: the REAL gate entry point (reads the store itself)
      gateHolders <- rt.engine.mergeMutexHoldersOf(a)
      combinedHolders <- IO.pure(rt.engine.mergeQueueHolders(a, combined))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ── RED reading: A is not blocked by the earlier arrival ──
      assertEquals(
        redHolders.map(_.id),
        Nil,
        "pre-change (RED): nothing blocks A — the collapsed rank made A look like the earlier arrival, so it would enter the critical section first"
      )
      // ── GREEN readings: the gate blocks A on B ──
      assert(
        combinedHolders.exists(_.id == "n-b"),
        s"post-change (GREEN): B blocks A, got ${combinedHolders.map(_.id)}"
      )
      assert(
        gateHolders.exists(_.id == "n-b"),
        s"post-change (GREEN): the ENGINE gate entry point itself (`mergeMutexHoldersOf`) must return B as a holder of A — this is the assertion the mutation arm turns red, got ${gateHolders.map(_.id)}"
      )
      assertEquals(
        gateHolders.map(_.id),
        combinedHolders.map(_.id),
        "the gate and the display face must resolve to the very same holder set (single point, no second predicate)"
      )
      // the holder's own rank is the true arrival time, not a collapsed creation stamp
      assertEquals(
        gateHolders.headOption.map(h => MergeMutexPolicy.rankOf(h, combined)._1),
        Some(t0 - 5000L),
        "the holder ranks by its own genuine arrival time"
      )
    end for
  }

  // ── mutation anchor + non-merge neutrality ────────────────────────────────────────────

  test(
    "mutation anchor: the gate's holder set, the payload position and the predicate rank all read from ONE map — reverting any of them to the active-zone snapshot reverses every reading above"
  ) {
    val ws = tempRoot / "ws-mut"; os.makeDir.all(ws)
    val system = ActorSystem(s"mwz-mut-${scala.util.Random.nextInt(100000)}")
    val t0 = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mwz-mut", ws, system, res)
      _ <- fixture(rt, "mut", t0)
      live <- activeOnly(rt)
      arch <- rt.store.archiveSnapshot
      combined = live ++ arch.nodes
      a <- rt.store.getNode("n-a").map(_.getOrElse(fail("n-a must exist")))
      b <- rt.store.getNode("n-b").map(_.getOrElse(fail("n-b must exist")))
      // ── ① bound to the engine: the gate reads the store itself ──
      engineGate <- rt.engine.mergeMutexHoldersOf(a)
      // ── ② bound to the engine: the real NodeList payload ──
      pl <- payload(rt)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ✅ These three anchors are co-failing cells: each is bound to the engine's store-backed
      // entry point at its tail, so flipping the caller's data source moves all three together
      // — that is what makes them anchors for this criterion rather than for a side effect.
      // (The bare pure-function pair cannot see a caller-side revert by construction: it is
      // handed the caliber it evaluates. The engine bindings below are what the mutation arm
      // turns red.)
      val gateLive = rt.engine.mergeQueueHolders(a, live)
      val gateCombined = rt.engine.mergeQueueHolders(a, combined)
      val payloadLive = rt.engine.mergeQueuePositionsBatch(live)
      val payloadCombined = rt.engine.mergeQueuePositionsBatch(combined)
      val rankLive = MergeMutexPolicy.rankOf(a, live)._1
      val rankCombined = MergeMutexPolicy.rankOf(a, combined)._1

      assert(
        engineGate.exists(_.id == "n-b"),
        s"mutation anchor ①: the ENGINE gate must block A on B, got ${engineGate.map(_.id)}"
      )
      assert(
        posInt(pl, "n-a", "position").getOrElse(0) > posInt(pl, "n-b", "position").getOrElse(0),
        s"mutation anchor ②: the real payload must rank A behind B, got ${posField(pl, "n-a")} / ${posField(pl, "n-b")}"
      )
      assert(!gateLive.exists(_.id == "n-b"), "mutation anchor ③: one-zone gate does NOT block A")
      assert(gateCombined.exists(_.id == "n-b"), "mutation anchor ③: two-zone gate DOES block A")
      assert(
        payloadLive("n-a").position < payloadLive("n-b").position,
        "mutation anchor: one-zone payload inverts the order"
      )
      assert(
        payloadCombined("n-a").position > payloadCombined("n-b").position,
        "mutation anchor: two-zone payload restores the order"
      )
      assert(rankLive == a.createdAt, "mutation anchor: one-zone rank collapses onto createdAt")
      assert(rankCombined == t0 + 1000L, "mutation anchor: two-zone rank is the true arrival time")

      // 🔴 The archived upstreams are NOT contenders: taking the archive zone into the map
      // must not add phantom queue members (nothing terminal becomes a competitor).
      assertEquals(
        payloadCombined.keySet,
        payloadLive.keySet,
        s"the competitor SET must not change — only the rank values: ${payloadCombined.keySet} vs ${payloadLive.keySet}"
      )
      assertEquals(
        MergeMutexPolicy.isContender(b),
        true,
        "isContender's three-state caliber is untouched (an open merge sink is a contender)"
      )
    end for
  }
  // ── ⑤ declaration: the verdict admission filter tightens with the same source ─────────

  test(
    "⑤ declaration (behaviour face tightened): once the gate reads the two-zone map, a candidate whose VERIFIER upstream sits in the archive zone becomes verdict-held and is dropped from the holder set — the affected position is RELEASED, and the two gates converge on one upstream caliber"
  ) {
    val ws = tempRoot / "ws-verdict"; os.makeDir.all(ws)
    val system = ActorSystem(s"mwz-verdict-${scala.util.Random.nextInt(100000)}")
    val t0 = System.currentTimeMillis()
    for
      res <- mkResources(system, tempRoot)
      rt <- mountProject("mwz-verdict", ws, system, res)
      // The verifier upstream lives in the ARCHIVE zone and carries a non-pass verdict.
      // `n-vblock` declares it via `in`; `n-vsink` (created later) asks who blocks it.
      _ <- seed(
        rt,
        // candidate: earlier arrival, so it outranks the sink before admission
        mergeSink("n-vblock", "verdict-merge-block", List("n-varch"), t0 - 9000L),
        mergeSink("n-vsink", "verdict-merge-sink", Nil, t0 - 7000L)
      )
      _ <- seedArchive(
        rt,
        NodeDef(
          id = "n-varch",
          name = "verdict-verifier-archived",
          agent = "general",
          task = Some("verify the block candidate"),
          status = NodeLifecycle.Completed,
          role = NodeRoles.Verifier,
          lastVerdict = Some(rt.engine.VerdictFail),
          result = Some("fail verdict"),
          out = Nil,
          createdAt = t0 - 20_000L,
          // completes BEFORE the sink's createdAt: the candidate's rank stays below the
          // sink's either way, so the ONLY thing the two calibers change is the filter.
          completedAt = Some(t0 - 8500L)
        )
      )
      live <- activeOnly(rt)
      arch <- rt.store.archiveSnapshot
      combined = live ++ arch.nodes
      sink <- rt.store.getNode("n-vsink").map(_.getOrElse(fail("n-vsink must exist")))
      block <- rt.store.getNode("n-vblock").map(_.getOrElse(fail("n-vblock must exist")))
      gateHolders <- rt.engine.mergeMutexHoldersOf(sink)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // ── precondition: the candidate is a holder candidate under BOTH calibers ──
      assert(
        MergeMutexPolicy.holders(sink, live).exists(_.id == "n-vblock"),
        "precondition: `n-vblock` outranks the sink on the rank key under the active-only map"
      )
      assert(
        MergeMutexPolicy.holders(sink, combined).exists(_.id == "n-vblock"),
        "precondition: the rank change must NOT be what removes it — the candidate set is unchanged and only the FILTER differs"
      )
      // ── the filter itself, on both calibers ──
      assertEquals(
        MergeMutexPolicy.upsOf(block, live).map(_.id),
        Nil,
        "pre-change: the archived verifier is invisible, so the candidate looks verdict-clear"
      )
      assertEquals(
        MergeMutexPolicy.upsOf(block, combined).map(_.id),
        List("n-varch"),
        "post-change: the archived verifier is visible to the same single point"
      )
      assert(
        rt.engine.mergeQueueHolders(sink, live).exists(_.id == "n-vblock"),
        "pre-change: the filter admits the candidate and the sink is HELD"
      )
      assertEquals(
        rt.engine.mergeQueueHolders(sink, combined).map(_.id),
        Nil,
        "post-change: the filter now drops the candidate (it is verdict-held) and the sink is RELEASED"
      )
      assertEquals(
        gateHolders.map(_.id),
        Nil,
        s"the ENGINE gate agrees with the post-change caliber (one upstream source for the mutex gate and the verdict gate), got ${gateHolders.map(_.id)}"
      )
      // The direction is one-way: `upsOf` only ever gains members with the combined map, so
      // the admission filter can only drop MORE candidates — the holder set strictly shrinks
      // and never grows through this path. The caliber difference between the two gates
      // narrows to zero; it is not widened.
      assert(
        MergeMutexPolicy.upsOf(block, live).map(_.id).toSet
          .subsetOf(MergeMutexPolicy.upsOf(block, combined).map(_.id).toSet),
        "the upstream resolution is monotone (active-only ⊂ combined) — that is why the filter can only tighten"
      )
    end for
  }
end MergeWindowZoneSpec
