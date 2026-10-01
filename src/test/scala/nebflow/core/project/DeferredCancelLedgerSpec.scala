package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*
import scala.util.Random

/**
 * eng-deferred-cancel batch — the READABLE-CANCELLATION criterion (K-1 #3) and the
 * zero-payload-shape-change discipline, judged on the chain ledger.
 *
 * A deferred cancel must not read as a silent delay: while the deferred legs are
 * letting in-flight tools finish, "this chain is being cancelled" has to be
 * readable somewhere. This spec pins that face and, just as importantly, pins
 * what it must NOT change.
 *
 * Faces:
 *  - L1 the intent is readable: after `withChainCancelIntent`, `cancellingAtOf`
 *    reports the registration instant.
 *  - L2 it does NOT enter the three-state projection: while the intent is live,
 *    `statusOf` still reports `active`, and the REST/WS payload shape is
 *    untouched (the batch only adds an "in progress" readable face; the
 *    `cancelled > paused > active` priority is not touched).
 *  - L3 the terminal write clears it in the SAME state rewrite, so a chain can
 *    never read as both "cancelling" and "cancelled".
 *  - L4 the TTL filters on read (crash residue inside the narrow "intent
 *    written, cancel not run" window is swept), and the write path prunes.
 *  - L5 persistence: `setChainCancelIntent` really lands on disk (readable after
 *    a reopen), and `setChainControl(cancelled)` persists the clearing too,
 *    otherwise memory and disk silently disagree.
 *  - L6 the chain leg records the intent; a node-level cancel must never
 *    fabricate a chain-level one.
 *
 * Red semantics: each judge is meant to go red when its implementation point is
 * reverted to the pre-batch form — the intent write removed (L1/L5), the intent
 * folded into `statusOf` (L2), the terminal write not clearing (L3), the TTL
 * filter dropped (L4). Readings are recorded in the batch report; the mutation
 * arms live outside this file.
 *
 * This seat runs targeted `testOnly` batches only; the full `sbt test` suite is
 * out of scope for this batch.
 */
class DeferredCancelLedgerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val t0 = 1750000000000L

  private def stateWithChain(chainId: String): ChainLedger.State =
    ChainLedger.observe(
      ChainLedger.State(project = "p"),
      List(ChainInfo(id = chainId, memberIds = List("n-a", "n-b"))),
      t0
    ).state

  // ── L1 the intent is readable (the carrier of criterion 3) ────────────────

  test("L1: recording a cancel intent makes the chain readable as 'cancellation in progress'") {
    val st = stateWithChain("chain-l1")
    val after = ChainLedger.withChainCancelIntent(st, "chain-l1", t0 + 1000L)
    assertEquals(
      ChainLedger.cancellingAtOf(after, "chain-l1", t0 + 1000L),
      Some(t0 + 1000L),
      "the in-progress cancel intent must be readable (a deferred cancel must not read as a silent delay)"
    )
    assertEquals(
      ChainLedger.cancellingChainsAt(after, t0 + 1000L).keySet,
      Set("chain-l1"),
      "the in-progress face must be enumerable for the REST/WS read path"
    )
  }

  // ── L2 stays OUT of the three-state projection (zero payload shape change) ─

  test("L2: the in-progress intent does NOT enter the three-state projection (payload shape unchanged)") {
    val st = stateWithChain("chain-l2")
    val after = ChainLedger.withChainCancelIntent(st, "chain-l2", t0 + 1000L)
    assertEquals(
      ChainLedger.statusOf(after, "chain-l2"),
      ChainLedger.StatusActive,
      "an in-flight cancel intent is NOT a terminal state: the projection stays active (the cancelled > paused priority is untouched)"
    )
    assertEquals(ChainLedger.cancelledAtOf(after, "chain-l2"), None, "cancelledAt must stay empty while the cancel is in progress")
    assertEquals(ChainLedger.pausedAtOf(after, "chain-l2"), None, "the intent must not touch pausedChains")
    assert(
      !ChainLedger.blocksDispatch(after, "chain-l2"),
      "the in-progress intent must not block dispatch (that would silently change chain control semantics)"
    )
  }

  // ── L3 the terminal write clears it atomically (the two faces cannot coexist)

  test("L3: the terminal cancel write clears the in-progress intent in the SAME state rewrite") {
    val st = stateWithChain("chain-l3")
    val inFlight = ChainLedger.withChainCancelIntent(st, "chain-l3", t0 + 1000L)
    assertEquals(ChainLedger.cancellingAtOf(inFlight, "chain-l3", t0 + 1000L).isDefined, true, "precondition: intent readable")
    val landed = ChainLedger.withChainControl(inFlight, "chain-l3", ChainLedger.StatusCancelled, t0 + 2000L)
    assertEquals(
      ChainLedger.cancellingAtOf(landed, "chain-l3", t0 + 2000L),
      None,
      "a chain must never read as both 'cancelling' and 'cancelled' — the terminal write clears the intent atomically"
    )
    assertEquals(ChainLedger.cancelledAtOf(landed, "chain-l3"), Some(t0 + 2000L), "the terminal cancellation itself must still be recorded")
    assertEquals(ChainLedger.statusOf(landed, "chain-l3"), ChainLedger.StatusCancelled, "projection priority stays cancelled > paused > active")
  }

  // ── L4 read-time TTL filter + lazy prune on write ─────────────────────────

  test("L4: an intent older than the TTL reads as absent, and a new record prunes the stale entry") {
    val st = stateWithChain("chain-l4")
    val recorded = ChainLedger.withChainCancelIntent(st, "chain-l4", t0)
    val justInside = t0 + ChainLedger.CancellingTtlMs - 1L
    val past = t0 + ChainLedger.CancellingTtlMs
    assertEquals(
      ChainLedger.cancellingAtOf(recorded, "chain-l4", justInside),
      Some(t0),
      "inside the window the intent is readable (no false negative)"
    )
    assertEquals(
      ChainLedger.cancellingAtOf(recorded, "chain-l4", past),
      None,
      "past the window the intent reads as absent — a crash between intent and cancel must not leave a permanent 'cancelling' chain"
    )
    assertEquals(ChainLedger.cancellingChainsAt(recorded, past), Map.empty[String, Long], "the enumeration honours the same TTL")
    val pruned = ChainLedger.withChainCancelIntent(recorded, "chain-l4", past)
    assertEquals(
      pruned.cancellingChains.keySet,
      Set("chain-l4"),
      "the write path prunes lazily: only the fresh row survives (no second judge face)"
    )
  }

  // ── L5 the persisted face ─────────────────────────────────────────────────

  test("L5: the intent is persisted, and the terminal write persists its clearing too") {
    val dir = os.temp.dir(prefix = "nb-deferred-cancel-ledger-", deleteOnExit = false)
    val path = dir / ChainLedger.FileName
    val arch = dir / ChainLedger.ArchiveDirName
    val now = System.currentTimeMillis()
    for
      store <- ChainLedgerStore.open("deferred-ledger", path, arch)
      comps = List(ChainInfo(id = "chain-l5", memberIds = List("n-a", "n-b")))
      _ <- store.reconcile(comps, Set("n-a", "n-b"), Map.empty, Set.empty, now)
      _ <- store.setChainCancelIntent("chain-l5", now)
      onDisk <- IO.blocking(os.read(path))
      midSnap <- store.snapshot
      _ <- store.setChainControl("chain-l5", ChainLedger.StatusCancelled, now + 50L)
      finalDisk <- IO.blocking(os.read(path))
      reopened <- ChainLedgerStore.open("deferred-ledger", path, arch)
      reloaded <- reopened.snapshot
    yield
      assertEquals(
        ChainLedger.cancellingAtOf(midSnap, "chain-l5", now + 50L),
        Some(now),
        "the intent must be readable from the live store right after the chain-level write point"
      )
      assert(
        onDisk.contains("cancellingChains"),
        s"K-1③ requires a PERSISTED readable face (a silent in-memory flag would not survive / not be readable): ${onDisk.take(400)}"
      )
      assertEquals(
        ChainLedger.cancellingAtOf(reloaded, "chain-l5", now + 100L),
        None,
        "the terminal write clears the intent ON DISK as well (memory and disk must not silently disagree)"
      )
      assertEquals(
        ChainLedger.cancelledAtOf(reloaded, "chain-l5"),
        Some(now + 50L),
        "the terminal cancellation survives the reopen"
      )
      assert(!finalDisk.contains(s""""chain-l5":$now"""), "the stale intent row must not remain on disk after the terminal write")
  }

  // ── L6 division of labour: a node-level cancel must not fabricate a chain intent

  private val tempRoot: os.Path = os.pwd / "target" / "test-deferred-cancel-ledger"
  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** Real NodeEngine fixture (same shape as `ChainCascadeSpec`; zero spawn, zero ports). */
  private def mountRig(name: String): IO[(ProjectRuntime, os.Path, ActorSystem, SharedResources)] =
    PathUtil.setDataRoot(tempRoot)
    os.remove.all(tempRoot)
    os.makeDir.all(tempRoot / "agents" / "general")
    os.write.over(
      tempRoot / "agents" / "general" / "agent.json",
      """{"name":"general","description":"deferred ledger rig agent","tools":[],"category":"standalone"}"""
    )
    os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
    val ws = tempRoot / s"ws-$name"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deferred-ledger-$name-${Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
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
    yield (rt, ws, system, res)

  private def seedChain(rt: ProjectRuntime, chainId: String, a: String, b: String): IO[Unit] =
    // The weakly-connected component forms through a REAL out edge (same
    // convention as `ChainCancelSpec.linearChain`); seeding only `deps` does not
    // make a chain — measured: the component degenerated to a single member,
    // which the precondition assertion caught on the spot.
    rt.store
      .mutate(s =>
        s.copy(nodes = s.nodes ++ Map(
          a -> NodeDef(id = a, name = a, agent = "general", status = NodeLifecycle.Pending, out = List(OutEdge(b)), createdAt = 1L),
          b -> NodeDef(id = b, name = b, agent = "general", status = NodeLifecycle.Pending, in = List(a), createdAt = 2L)
        ))
      )
      .void

  test("L6: the chain-cancel leg records the intent on the persisted ledger (and a node-level cancel must not)") {
    val name = "deferred-l6"
    val program = for
      mounted <- mountRig(name)
      (rt, ws, system, res) = mounted
      _ <- seedChain(rt, "chain-n-l6", "n-l6a", "n-l6b")
      // Chain-id derivation: `chain-<earliest createdAt member of the component>`
      resolved <- rt.store.chainMembersOf("chain-n-l6a")
      ledgerPath = ws / ".nebflow" / ChainLedger.FileName
      // Precondition read from the PERSISTED face, not just memory
      before <- IO.blocking(if os.exists(ledgerPath) then os.read(ledgerPath) else "")
      _ <- IO(assert(!before.contains("cancellingChains"), s"precondition: no intent face before the chain cancel (file=${os.exists(ledgerPath)})"))
      // The chain leg must register the intent BEFORE any node is signalled
      rep <- rt.engine.cancelChain("chain-n-l6a", CancelSource.User, "user wants this track gone")
      snap <- rt.store.chainLedgerStore.snapshot
      afterIntent <- IO.blocking(if os.exists(ledgerPath) then os.read(ledgerPath) else "")
    yield
      assert(resolved.exists(_.info.memberIds.size >= 2), s"precondition: a 2-member chain must resolve, got: $resolved")
      assert(rep.isRight, s"the chain cancel must be accepted, got: $rep")
      assert(
        afterIntent.contains("cancellingChains"),
        s"K-1③: the chain-cancel leg must leave a PERSISTED in-progress face readable while the deferred legs run, " +
          s"got ledger file exists=${os.exists(ledgerPath)}: ${afterIntent.take(500)}"
      )
      assertEquals(
        ChainLedger.cancellingChainsAt(snap, System.currentTimeMillis()).keySet,
        Set("chain-n-l6a"),
        "K-1③: exactly the cancelled chain must read as in progress (a raw cancelChain has not landed the terminal state yet)"
      )
      assertEquals(
        ChainLedger.statusOf(snap, "chain-n-l6a"),
        ChainLedger.StatusActive,
        "K-1③: the in-progress face is NOT a state — the three-state projection is untouched"
      )
    program.guarantee(
      IO(PathUtil.setDataRoot(originalRoot))
    )
  }

  // ── L7 the settle probe must anchor on "the session really stopped" ───────
  //
  // The engine-side guard for K-1 criterion 1: after releasing the deferred stop,
  // the cancel leg must wait for the session to actually terminate before the
  // first-order engine teardown (`system.stop(ref)`); otherwise that teardown
  // races the batch persistence and re-cuts exactly the artifacts K-2 preserves.
  //
  // The probe choice is LOAD-BEARING (named in the implementation header):
  // `system.isAlive` is the actor system's own liveness registry, which the actor
  // loop removes itself in its `guarantee`; `resources.agentRegistry` is removed
  // by THIS very fiber, a few lines BELOW the wait — polling it would never
  // observe the transition and would always burn the full grace window.

  test("L7: settleDeferredCancel observes the real session death (isAlive probe), not the engine's own registry") {
    val name = "deferred-l7"
    val program = for
      mounted <- mountRig(name)
      (rt, _, system, res) = mounted
      sid = "deferred-l7-session"
      ref <- system.spawn(
        {
          def loop: nebflow.actor.Behavior[nebflow.actor.AgentCommand] =
            nebflow.actor.Behaviors.receiveMessage[nebflow.actor.AgentCommand](_ => IO.pure(loop))
          loop
        },
        sid
      )
      // The registry entry deliberately STAYS present: that is the probe face the
      // implementation must not use (this fiber removes it only later)
      _ <- res.agentRegistry.update(
        _ + (sid -> nebflow.actor.AgentRecord(sid, ref, nebflow.actor.AgentKind.Delegate, sid))
      )
      aliveBefore <- system.isAlive(ref.path)
      // Stop the session actor (the actor loop removes the isAlive entry itself;
      // the engine's own registry is left UNTOUCHED)
      _ <- system.stop(ref)
      stopped <- rt.engine.settleDeferredCancel(ref)
      stillRegistered <- res.agentRegistry.get.map(_.contains(sid))
      t0 <- IO(System.currentTimeMillis())
      // Control arm: the registry entry is still there, so a probe pointed at
      // the registry would necessarily burn the full grace window
      second <- rt.engine.settleDeferredCancel(ref)
      elapsed = System.currentTimeMillis() - t0
    yield
      assert(aliveBefore, "precondition: the freshly spawned session must be alive")
      assert(
        stopped,
        "L7 VIOLATED — settleDeferredCancel must observe the real session death (isAlive probe) and return true"
      )
      assert(
        stillRegistered,
        "L7 precondition: the engine's own agentRegistry entry must still be present (it is removed later by the caller fiber)"
      )
      assert(
        elapsed < NodeStarter.DeferredStopGraceMs / 2,
        s"L7 VIOLATED — the second settle returned in ${elapsed}ms, i.e. it did NOT burn the grace window; " +
          "a probe pointed at agentRegistry (still registered) would have slept the full ${NodeStarter.DeferredStopGraceMs}ms"
      )
      assert(second, "L7: a repeated settle on an already-dead session must still report stopped")
    program.guarantee(
      IO(PathUtil.setDataRoot(originalRoot))
    )
  }

end DeferredCancelLedgerSpec
