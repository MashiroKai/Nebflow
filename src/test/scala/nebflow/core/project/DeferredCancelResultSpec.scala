package nebflow.core.project

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*
import scala.util.Random

/**
 * eng-deferred-cancel batch — in-flight artifact preservation (K-2) and the
 * separation of the cancel reason from the produced artifact.
 *
 * A deferred cancel stops a node AFTER the tool batch it was running has
 * produced its results. Those results are the artifact; the cancel reason must
 * not overwrite them.
 *
 * Faces:
 *  - K1 an already-produced `result` is STILL readable after the cancel (the
 *    reason is PREPENDED, the artifact follows; neither overwrites the other).
 *  - K2 the cancel-reason prefix always stays at offset 0
 *    (`CancelSource.fromResult` parses with `startsWith`, so prepending is a
 *    hard contract, not a formatting choice) — with a control reading for the
 *    pre-batch overwriting form.
 *  - K3a a re-cancel through the ENTRY point is a zero-write no-op, the artifact
 *    intact (the `ChainCancelScope` gate skips a terminal node).
 *  - K3b the write point's second line of defence keeps exactly ONE reason when
 *    a cancel rendering is already there (no stacking).
 *  - K4 with no artifact the rendering is byte-identical to the historical form,
 *    i.e. this item only affects the already-produced face.
 *
 * Red semantics: reverting `preservedCancelResult` to "always return rendered"
 * (the pre-batch overwriting form) turns K1/K3a red; appending the reason AFTER
 * the artifact (the prefix contract broken) turns K2 red.
 */
class DeferredCancelResultSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-deferred-cancel-result"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"deferred-cancel result spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  /** Quiet LLM stub: this spec judges the terminal write point only; turns do not participate. */
  private class QuietLlm:
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mount(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
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

  private def mkNode(id: String, status: String, result: Option[String] = None): NodeDef =
    NodeDef(
      id = id,
      name = id.toUpperCase,
      agent = "general",
      task = Some(s"$id task"),
      status = status,
      result = result,
      createdAt = System.currentTimeMillis()
    )

  private def nodeOf(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.snapshot.map(_.nodes.getOrElse(id, fail(s"node '$id' must still exist")))

  /**
   * Count plain-substring occurrences of the cancel-reason rendering marker.
   * Deliberately NOT `String.split`: that takes a REGEX, and `cancelled[source=`
   * contains a character class opener — the first draft of this spec died with
   * `PatternSyntaxException` instead of measuring the thing under test.
   */
  private def reasonCount(s: String): Int =
    val marker = "cancelled[source="
    var idx = s.indexOf(marker)
    var n = 0
    while idx >= 0 do
      n += 1
      idx = s.indexOf(marker, idx + marker.length)
    n

  /** The real cancel write point (detach/notify off: this spec measures the `result` composition only). */
  private def cancel(rt: ProjectRuntime, id: String, reason: String): IO[Unit] =
    rt.engine.cancelNode(id, reason, CancelSource.User, detach = false, notify = false, emitNotify = false)

  private def withRig(name: String)(f: (ProjectRuntime, ActorSystem) => IO[Unit]): IO[Unit] =
    val ws = tempRoot / s"ws-$name"
    os.makeDir.all(ws)
    val system = ActorSystem(s"deferred-result-$name-${Random.nextInt(100000)}")
    val res = SpecResources.mkResources(system, tempRoot, new QuietLlm().handle)
    for
      resources <- res
      rt <- mount(name, ws, system, resources)
      _ <- f(rt, system).guarantee(system.stopAll.handleErrorWith(_ => IO.unit))
    yield ()

  // ── K1 / K2: the artifact survives and the reason prefix stays at offset 0 ──

  test("K1/K2: an already-produced result survives the cancel, with the reason still at offset 0") {
    val produced = "PRODUCED_ARTIFACT: 12 of 20 samples analysed; partial report written to out/report.md"
    withRig("k12") { (rt, _) =>
      for
        _ <- seed(rt, mkNode("n-k12", NodeLifecycle.Running, result = Some(produced)))
        before <- nodeOf(rt, "n-k12")
        _ <- cancel(rt, "n-k12", "chain cancel: upstream node failed")
        after <- nodeOf(rt, "n-k12")
      yield
        assertEquals(before.result, Some(produced), "precondition: the node has a produced result before the cancel")
        assertEquals(after.status, NodeLifecycle.Cancelled, "the cancel must still reach the cancelled terminal state")
        val res = after.result.getOrElse(fail("K-2: cancelled node must carry a result"))
        assert(
          res.contains(produced),
          s"K-2 VIOLATED — the produced artifact was overwritten by the cancel reason (pre-batch form). got: $res"
        )
        assert(
          res.startsWith(s"cancelled[source=${CancelSource.UserCode}]:"),
          s"K1/K2: the cancel-reason prefix must stay at offset 0 (fromResult parses with startsWith). got: $res"
        )
        assertEquals(
          CancelSource.fromResult(after.result),
          Some(CancelSource.User),
          "K2: the source must remain machine-readable after the preservation change"
        )
        assert(
          res.endsWith(produced),
          s"K1: the produced artifact must be the trailing payload, verbatim. got: $res"
        )
    }
  }

  // ── K3a: re-cancel through the entry point is a zero-write no-op ───────────
  //
  // Layer caveat, nailed down up front because getting it wrong writes a false
  // judge: the `ChainCancelScope` gate lives in **`cancelNodes`** (the chain and
  // node entry points), NOT in `NodeCompletion.cancelNode` itself. So a second
  // cancel through the entry point is unreachable from the terminal write point
  // (idempotent exit: zero write, zero signal, zero notify), while calling the
  // write point DIRECTLY writes once more. Hence this case drives the ENTRY
  // point to measure the upstream gate; the write point's own defence is K3b.
  // Precedent: `ChainCancelSpec` C6 already pins the full-chain form (a second
  // call yields 0 frames / 0 injections / 0 audit rows).

  test("K3a: re-cancelling an already-cancelled node through the entry point is a zero-write no-op") {
    val produced = "ARTIFACT-B: 3 findings persisted"
    withRig("k3a") { (rt, _) =>
      for
        _ <- seed(rt, mkNode("n-k3a", NodeLifecycle.Running, result = Some(produced)))
        first <- rt.engine.cancelNodes(List("n-k3a"), CancelSource.User, "chain cancel: first", cascade = false)
        once <- nodeOf(rt, "n-k3a")
        second <- rt.engine.cancelNodes(List("n-k3a"), CancelSource.User, "chain cancel: second", cascade = false)
        twice <- nodeOf(rt, "n-k3a")
      yield
        assertEquals(first.cancelled.map(_.nodeId), List("n-k3a"), "precondition: the first cancel terminalizes the node")
        assertEquals(once.status, NodeLifecycle.Cancelled, "precondition: the node is cancelled after the first call")
        assertEquals(second.cancelled, Nil, s"K3a: the entry point must skip a terminal node, got: ${second.cancelled}")
        assertEquals(
          second.preserved.map(_.nodeId),
          List("n-k3a"),
          "K3a: the already-cancelled node lands in `preserved` (terminal), not in `cancelled`"
        )
        assertEquals(
          twice.result,
          once.result,
          "K3a: a re-cancel through the entry point must write NOTHING (one reason + the artifact, byte-identical)"
        )
        assert(
          twice.result.exists(_.contains(produced)),
          s"K3a: the produced artifact stays readable, got: ${twice.result}"
        )
    }
  }

  // ── K3b: the write point's second line of defence (direct-call probe) ─────
  //
  // The no-stacking rule in `preservedCancelResult` is a DEFENCE IN DEPTH: after
  // the `ChainCancelScope` gate it is unreachable (see K3a), so this case calls
  // `cancelNode` directly with a node that is already cancelled and whose result
  // is already a cancel rendering. The judge = exactly one reason (not stacked)
  // and still machine-readable.
  // It does NOT claim the artifact is preserved on that path: there the artifact
  // was already replaced by the pre-existing overwriting form on the first
  // cancel. The reachable preservation face is K1/K2.

  test("K3b: the write-point's second line of defence keeps exactly one reason (no stacking)") {
    val alreadyCancelled = s"cancelled[source=${CancelSource.UserCode}]: reason=first"
    withRig("k3b") { (rt, _) =>
      for
        _ <- seed(rt, mkNode("n-k3b", NodeLifecycle.Cancelled, result = Some(alreadyCancelled)))
        _ <- cancel(rt, "n-k3b", "second")
        after <- nodeOf(rt, "n-k3b")
      yield
        assertEquals(
          reasonCount(after.result.getOrElse("")),
          1,
          s"K3b: a re-cancel must not stack a second reason rendering (un-doubling), got: ${after.result}"
        )
        assertEquals(
          CancelSource.fromResult(after.result),
          Some(CancelSource.User),
          "K3b: the single reason must still be machine-readable"
        )
    }
  }

  // ── K4: no artifact ⇒ byte-identical to the historical rendering ───────────

  test("K4: with no produced result the rendering is byte-identical to the historical form") {
    withRig("k4") { (rt, _) =>
      for
        _ <- seed(rt, mkNode("n-k4", NodeLifecycle.Running, result = None))
        _ <- cancel(rt, "n-k4", "chain cancel: nothing produced yet")
        after <- nodeOf(rt, "n-k4")
      yield
        assertEquals(
          after.result,
          Some(s"cancelled[source=${CancelSource.UserCode}]: reason=chain cancel: nothing produced yet"),
          "K4: no artifact ⇒ the result stays exactly the cancel reason (this item only changes the produced-artifact face)"
        )
    }
  }

end DeferredCancelResultSpec
