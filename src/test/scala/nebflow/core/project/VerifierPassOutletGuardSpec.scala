package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.core.tools.{NodeEditTool, NodeTools, ToolContext}
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * Zero-consumer pass verdict — case A **warning tier** (a), red-verification anchor.
 *
 * One sentence of semantics: a verifier whose declaration face carries **no pass outlet**
 * (its positive verdict has nowhere to go) gets one ⚠ line on the tail of a SUCCESS
 * receipt and one `verifier-pass-unconsumed` audit line. This is the PASS face; it sits
 * **beside** — never instead of — the fail face family (`NODE_VERIFIER_NEEDS_ROUTE` /
 * `verifierRouteInvalid` / `verifier-route-lost`): both judgements can hold at once.
 *
 * 🔴 Zero rejection paths: nothing here can turn a successful NodeEdit into a refusal
 * (the design card's governance ruling picked the warning tier precisely because the
 * rejection tier had four recorded false-positive incidents).
 *
 * Mechanical acceptance list covered here (task brief §2.5):
 *  - **R1** create leg: verifier with a fail route but no pass outlet ⇒ the receipt tail
 *    carries the ⚠ line (semantics "pass" + "unconsumed / no pass outlet") AND the audit
 *    carries exactly one `verifier-pass-unconsumed` whose subject is the verifier;
 *  - **R1b** exemption (two-phase token): an empty-`out` verifier created through
 *    `verifierRoutePending=true` is NOT flagged by the pass face (that window belongs to
 *    the existing fail-face token) — and the fail-face token warning is still there;
 *  - **R2** rewire clears: declaring `(pass)<landing>` removes the ⚠ line with zero extra
 *    action and adds zero events (purely derived); a second identical edit is idempotent;
 *  - **R3** no false positive: a verifier declaring both legs is silent;
 *  - **R4** `pendingOut` boundary: a fail edge queued behind a RUNNING target (B5 deferral)
 *    must NOT be reported (false-positive guard inside the auto-wiring window);
 *  - **R10** payload key set: the night-`buildNodeJson` snapshot face gains no key for
 *    either shape (a V-0 form and a healthy verifier).
 *
 * Mutation arm (a): set `NodeTools.passOutlet` to constant `true` ⇒ R1 must turn red.
 *
 * Harness discipline (same as `VerifierRouteGuardSpec` / `MergeVerdictGateSpec`):
 * store/engine mounted directly, no `ProjectActor`, so the assertion windows are
 * deterministic (no background tick races). The spec writes only under
 * `os.pwd/target/...` and never touches a real home.
 */
class VerifierPassOutletGuardSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-verifier-pass-outlet-guard"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"verifier pass outlet guard spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── harness ──────────────────────────────────────────────────────

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources,
    frames: Ref[IO, List[Json]]
  ): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (j: Json) => frames.update(_ :+ j),
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (typ: String, nodeId: String, payload: Json) =>
          frames.update(
            _ :+ payload.deepMerge(Json.obj("type" -> Json.fromString(typ), "nodeId" -> Json.fromString(nodeId)))
          ),
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

  private def seed(store: FlowMapStore, n: NodeDef*): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes ++ n.map(x => x.id -> x).toMap)).void

  private def node(rt: ProjectRuntime, id: String): IO[NodeDef] =
    rt.store.getNode(id).map(_.getOrElse(fail(s"node '$id' must exist")))

  /** Created node id by display name (the create leg allocates a random id). */
  private def nodeIdByName(rt: ProjectRuntime, nm: String): IO[String] =
    rt.store.snapshot.map { s =>
      s.nodes.values.find(_.name == nm).map(_.id).getOrElse(fail(s"a node named '$nm' must exist"))
    }

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("verifier-pass-outlet-guard-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  /** Audit reading (type, nodeId, summary). */
  private def readAudit(ws: os.Path): IO[List[(String, String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(
        _.flatMap(l =>
          jsonParse(l).toOption.map(j =>
            (
              j.hcursor.get[String]("type").getOrElse(""),
              j.hcursor.get[String]("nodeId").getOrElse(""),
              j.hcursor.get[String]("summary").getOrElse("")
            )
          )
        )
      )
      .handleError(_ => Nil)

  private def passEvents(ws: os.Path): IO[List[(String, String, String)]] =
    readAudit(ws).map(_.filter(_._1 == FlowMapEventLog.VerifierPassUnconsumedType))

  /** Payload reading single point: the NodeList snapshot payload (tool / REST shared point). */
  private def payloadOf(rt: ProjectRuntime, id: String): IO[Json] =
    NodeTools.buildNodeListPayload(rt).map { j =>
      j.hcursor
        .downField("nodes")
        .as[List[Json]]
        .getOrElse(Nil)
        .find(_.hcursor.get[String]("id").toOption.contains(id))
        .getOrElse(fail(s"node '$id' must appear in the NodeList payload"))
    }

  private def keysOf(rt: ProjectRuntime, id: String): IO[List[String]] =
    payloadOf(rt, id).map(_.asObject.map(_.keys.toList.sorted).getOrElse(Nil))

  private val PassOutletMarker = "verifier-pass-unconsumed"

  // ── R1: create leg ─────────────────────────────────────────────────

  test(
    "R1 (a) create leg: a verifier declared with a fail route but no pass outlet carries the ⚠ line and exactly one verifier-pass-unconsumed line whose subject is the verifier"
  ) {
    val ws = tempRoot / "ws-r1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vpg-r1-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vpg-r1", ws, system, res, frames)
      // pre-wired worker (the fail leg target) — stays pending so the new verifier keeps
      // waiting on its in-barrier (no background start race inside the assertion window)
      _ <- seed(
        rt.store,
        NodeDef(id = "n-w", name = "WORK", agent = "general", task = Some("work"), status = NodeLifecycle.Pending, createdAt = now - 90_000)
      )
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(
        nodeInput(
          "vpg-r1",
          "VER",
          "task" -> Json.fromString("judge the artifact"),
          "description" -> Json.fromString("judge the artifact"),
          "plugins" -> Json.arr(),
          "role" -> Json.fromString("verifier"),
          "in" -> Json.fromString("n-w"),
          "out" -> Json.fromString("(fail)WORK:loop")
        ),
        ctx
      )
      verId <- nodeIdByName(rt, "VER")
      ver <- node(rt, verId)
      evs <- passEvents(ws)
      keys <- keysOf(rt, verId)
      _ <- IO(
        println(
          s"[spec] R1 receipt=$r\n[spec] R1 node out=${ver.out} pendingOut=${ver.pendingOut} role=${ver.role}\n" +
            s"[spec] R1 verifier-pass-unconsumed=${evs.map(e => s"[nodeId=${e._2}] ${e._3}")}\n" +
            s"[spec] R1 payload keys=${keys.mkString(",")}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val receipt = r.getOrElse(fail(s"the create must succeed (warning tier = zero rejection paths), got: $r"))
      assert(r.isRight, s"the warning tier must never refuse a create, got: $r")
      assert(receipt.contains("⚠"), s"the receipt tail must carry the ⚠ line, got: $receipt")
      assert(receipt.contains("pass"), s"the ⚠ line must talk about the pass face, got $receipt")
      assert(
        receipt.contains("unconsumed") || receipt.contains("no pass outlet"),
        s"the ⚠ line must carry the unconsumed / no-pass-outlet semantics, got $receipt"
      )
      assert(receipt.contains(PassOutletMarker), s"the ⚠ line must name the judgement code, got $receipt")
      assert(
        !receipt.contains("NODE_VERIFIER_NEEDS_ROUTE"),
        s"the fail face is healthy here ⇒ the two families are parallel (never merged), got $receipt"
      )
      assertEquals(evs.map(_._2).distinct, List(verId), s"exactly one event, subject = the verifier itself, got $evs")
      assert(
        evs.head._3.contains("without a pass outlet"),
        s"the audit line must carry the actionable wording, got $evs"
      )
      assert(evs.head._3.contains("'(pass)<landing>'"), s"the audit line must name the repair form, got $evs")
      assert(!keys.contains("verifierPassRoute"), s"no payload key may be invented for the pass face, got $keys")
    end for
  }

  // ── R1b: two-phase token exemption (empty out) ─────────────────────

  test(
    "R1b (a) exemption: an empty-out verifier created through the verifierRoutePending token is NOT flagged by the pass face (that window belongs to the existing fail-face token)"
  ) {
    val ws = tempRoot / "ws-r1b"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vpg-r1b-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vpg-r1b", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(id = "n-w", name = "WORK", agent = "general", task = Some("work"), status = NodeLifecycle.Pending, createdAt = now - 90_000)
      )
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(
        nodeInput(
          "vpg-r1b",
          "VER",
          "task" -> Json.fromString("judge the artifact"),
          "description" -> Json.fromString("judge the artifact"),
          "plugins" -> Json.arr(),
          "role" -> Json.fromString("verifier"),
          "in" -> Json.fromString("n-w"),
          "verifierRoutePending" -> Json.fromBoolean(true)
        ),
        ctx
      )
      verId <- nodeIdByName(rt, "VER")
      ver <- node(rt, verId)
      evs <- passEvents(ws)
      audit <- readAudit(ws)
      _ <- IO(
        println(
          s"[spec] R1b receipt=$r\n[spec] R1b out=${ver.out} pendingOut=${ver.pendingOut}\n" +
            s"[spec] R1b pass-face events=${evs.size} ; audit types=${audit.map(_._1).distinct.mkString(",")}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val receipt = r.getOrElse(fail(s"the two-phase token create must succeed, got: $r"))
      assertEquals(ver.out, Nil, "precondition: the token create lands with an empty declared out")
      assert(
        receipt.contains("verifierRoutePending"),
        s"the FAIL-face token warning must still be there (that family is untouched), got $receipt"
      )
      assert(
        !receipt.contains(PassOutletMarker),
        s"boundary: an empty declared out belongs to the two-phase token face ⇒ the pass face must stay silent, got $receipt"
      )
      assertEquals(evs, Nil, s"zero verifier-pass-unconsumed lines for the token form, got $evs")
      assert(
        audit.exists(_._1 == "verifier-route-deferred"),
        s"the fail-face deferred trace is unaffected, got ${audit.map(_._1).distinct}"
      )
    end for
  }

  // ── R2: rewire clears (purely derived) ─────────────────────────────

  test(
    "R2 (a) rewire clears: declaring '(pass)<landing>' removes the ⚠ line with zero extra action, adds zero events, and a repeated identical edit stays idempotent"
  ) {
    val ws = tempRoot / "ws-r2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vpg-r2-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vpg-r2", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(id = "n-w", name = "WORK", agent = "general", task = Some("work"), status = NodeLifecycle.Pending, createdAt = now - 90_000),
        NodeDef(id = "n-land", name = "LAND", agent = "general", task = Some("land"), status = NodeLifecycle.Pending, createdAt = now - 80_000)
      )
      ctx = mkCtx(res, system, ws.toString)
      created <- nodeEdit(
        nodeInput(
          "vpg-r2",
          "VER",
          "task" -> Json.fromString("judge the artifact"),
          "description" -> Json.fromString("judge the artifact"),
          "plugins" -> Json.arr(),
          "role" -> Json.fromString("verifier"),
          "in" -> Json.fromString("n-w"),
          "out" -> Json.fromString("(fail)WORK:loop")
        ),
        ctx
      )
      afterCreate <- passEvents(ws)
      rewired <- nodeEdit(nodeInput("vpg-r2", "VER", "out" -> Json.fromString("(pass)LAND, (fail)WORK:loop")), ctx)
      verRewired <- nodeIdByName(rt, "VER").flatMap(node(rt, _))
      afterRewire <- passEvents(ws)
      _ <- nodeEdit(nodeInput("vpg-r2", "VER", "out" -> Json.fromString("(pass)LAND, (fail)WORK:loop")), ctx)
      _ <- IO.sleep(300.millis)
      afterRepeat <- passEvents(ws)
      _ <- IO(
        println(
          s"[spec] R2 create=$created\n[spec] R2 rewire=$rewired\n" +
            s"[spec] R2 out after rewire=${verRewired.out} ; events create=${afterCreate.size} rewire=${afterRewire.size} repeat=${afterRepeat.size}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val createReceipt = created.getOrElse(fail(s"the create must succeed, got: $created"))
      assert(createReceipt.contains(PassOutletMarker), s"precondition: the create warned, got $createReceipt")
      assertEquals(afterCreate.size, 1, s"precondition: exactly one create-side event, got $afterCreate")
      val rewireReceipt = rewired.getOrElse(fail(s"the rewire must succeed, got: $rewired"))
      assert(
        !rewireReceipt.contains(PassOutletMarker),
        s"the ⚠ line MUST vanish by itself once the pass leg is declared (purely derived), got $rewireReceipt"
      )
      assert(
        verRewired.out.exists(e => e.on.contains(OutEdge.Pass) && !OutEdge.isLoopEdge(e)),
        s"the pass leg must be wired into out, got ${verRewired.out}"
      )
      assertEquals(afterRewire.size, 1, s"rewiring adds ZERO further events (derived ⇒ no bookkeeping), got $afterRewire")
      assertEquals(afterRepeat.size, 1, s"a repeated identical edit stays idempotent, got $afterRepeat")
    end for
  }

  // ── R3: no false positive on a healthy verifier ────────────────────

  test("R3 (a) no false positive: a verifier created with both legs (pass landing + fail route) is silent on the pass face") {
    val ws = tempRoot / "ws-r3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vpg-r3-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vpg-r3", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(id = "n-w", name = "WORK", agent = "general", task = Some("work"), status = NodeLifecycle.Pending, createdAt = now - 90_000),
        NodeDef(id = "n-land", name = "LAND", agent = "general", task = Some("land"), status = NodeLifecycle.Pending, createdAt = now - 80_000)
      )
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(
        nodeInput(
          "vpg-r3",
          "VER",
          "task" -> Json.fromString("judge the artifact"),
          "description" -> Json.fromString("judge the artifact"),
          "plugins" -> Json.arr(),
          "role" -> Json.fromString("verifier"),
          "in" -> Json.fromString("n-w"),
          "out" -> Json.fromString("(pass)LAND, (fail)WORK:loop")
        ),
        ctx
      )
      verId <- nodeIdByName(rt, "VER")
      evs <- passEvents(ws)
      _ <- IO(println(s"[spec] R3 receipt=$r\n[spec] R3 pass-face events=${evs.size}"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      val receipt = r.getOrElse(fail(s"the healthy verifier create must succeed, got: $r"))
      assert(!receipt.contains(PassOutletMarker), s"a healthy verifier must stay silent, got $receipt")
      assert(!receipt.contains("no pass outlet"), s"no pass-face wording for a healthy verifier, got $receipt")
      assertEquals(evs, Nil, s"zero pass-face events for a healthy verifier, got $evs")
      assert(verId.nonEmpty, "the healthy verifier exists")
    end for
  }

  // ── R4: pendingOut boundary (deferral window) ──────────────────────

  test(
    "R4 (a) pendingOut boundary: a fail edge queued behind a RUNNING target (B5 deferral) is NOT reported — and the standing condition after auto-wiring is still silent until the next decision moment"
  ) {
    val ws = tempRoot / "ws-r4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vpg-r4-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vpg-r4", ws, system, res, frames)
      // the verifier starts with an empty declared out (direct seed: the write-path token
      // is not this spec's subject) and the fail target is RUNNING ⇒ the new control edge
      // is queued in pendingOut instead of being wired
      _ <- seed(
        rt.store,
        NodeDef(
          id = "n-ver",
          name = "VER",
          agent = "general",
          task = Some("judge"),
          status = NodeLifecycle.Wiring,
          role = NodeRoles.Verifier,
          out = Nil,
          createdAt = now - 60_000
        ),
        NodeDef(
          id = "n-q",
          name = "WORKQ",
          agent = "general",
          task = Some("work"),
          status = NodeLifecycle.Running,
          startedAt = Some(now - 10_000),
          createdAt = now - 50_000
        )
      )
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("vpg-r4", "VER", "out" -> Json.fromString("(fail)WORKQ:loop")), ctx)
      verQueued <- node(rt, "n-ver")
      evsQueued <- passEvents(ws)
      _ <- IO(
        println(
          s"[spec] R4 receipt=$r\n[spec] R4 out=${verQueued.out} pendingOut=${verQueued.pendingOut}\n" +
            s"[spec] R4 pass-face events=${evsQueued.size}"
        )
      )
      // target leaves running ⇒ the queued control edge is auto-wired
      _ <- rt.store
        .mutate(s => s.copy(nodes = s.nodes.updated("n-q", s.nodes("n-q").copy(status = NodeLifecycle.Pending))))
        .void
      _ <- rt.engine.applyDeferredWiring()
      verWired <- node(rt, "n-ver")
      nodesWired <- rt.store.snapshot.map(_.nodes)
      standingGap = NodeTools.verifierPassOutletMissing(verWired, nodesWired)
      evsAfter <- passEvents(ws)
      _ <- IO(
        println(
          s"[spec] R4 after auto-wiring — out=${verWired.out} pendingOut=${verWired.pendingOut} " +
            s"judged-in-gap=$standingGap ; pass-face events=${evsAfter.size}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"declaring a fail route onto a running target must be accepted (deferred), got: $r")
      assert(
        verQueued.pendingOut.exists(e => e.on.contains(OutEdge.Fail) && OutEdge.isLoopEdge(e)),
        s"precondition: the control edge is queued in pendingOut, got ${verQueued.pendingOut}"
      )
      assertEquals(verQueued.out, Nil, "precondition: the deferred edge is NOT in out")
      val receipt = r.getOrElse("")
      assert(
        !receipt.contains(PassOutletMarker),
        s"pendingOut counts as declared ⇒ no ⚠ inside the wiring window (false-positive guard), got $receipt"
      )
      assertEquals(evsQueued, Nil, s"zero pass-face events while the edge sits in the queue, got $evsQueued")
      // documented boundary: the (a) face is a DECISION-MOMENT warning (create/rewire
      // receipts). Once the queue auto-wires, the standing state is in the gap and stays
      // silent until the next NodeEdit — the archive face (b) is what catches it later.
      assert(
        verWired.out.exists(e => e.on.contains(OutEdge.Fail) && OutEdge.isLoopEdge(e)),
        s"the deferred edge must be auto-wired into out, got ${verWired.out}"
      )
      assert(verWired.pendingOut.isEmpty, s"the queue must be drained, got ${verWired.pendingOut}")
      assert(standingGap, "the standing state after auto-wiring is indeed gap-shaped (reading only, no write)")
      assertEquals(evsAfter, Nil, "no write point fires on auto-wiring ⇒ the trace stays empty (boundary reading)")
    end for
  }

  // ── R10: payload key set (zero drift) ─────────────────────────────

  test(
    "R10 payload key set: injecting the snapshot judgement face adds NO key for either shape (V-0 form and healthy verifier) — the pass-face predicate stays a visibility carrier"
  ) {
    val ws = tempRoot / "ws-r10"
    os.makeDir.all(ws)
    val system = ActorSystem(s"vpg-r10-${scala.util.Random.nextInt(100000)}")
    val now = System.currentTimeMillis()
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      frames <- Ref.of[IO, List[Json]](Nil)
      rt <- mountProject("vpg-r10", ws, system, res, frames)
      _ <- seed(
        rt.store,
        NodeDef(id = "n-w", name = "WORK", agent = "general", task = Some("work"), status = NodeLifecycle.Completed, createdAt = now - 90_000, completedAt = Some(now - 80_000)),
        NodeDef(id = "n-land", name = "LAND", agent = "general", task = Some("land"), status = NodeLifecycle.Pending, createdAt = now - 85_000),
        // the V-0 shape on record (35 live instances): verdict written, fail route only
        NodeDef(
          id = "n-v0",
          name = "V0",
          agent = "general",
          task = Some("judge"),
          status = NodeLifecycle.Completed,
          role = NodeRoles.Verifier,
          lastVerdict = Some("pass"),
          out = List(OutEdge("n-w", Set(OutEdge.Fail), OutEdge.Loop)),
          createdAt = now - 60_000,
          completedAt = Some(now - 50_000)
        ),
        // the healthy shape: both legs declared
        NodeDef(
          id = "n-ok",
          name = "OK",
          agent = "general",
          task = Some("judge"),
          status = NodeLifecycle.Completed,
          role = NodeRoles.Verifier,
          lastVerdict = Some("pass"),
          out = List(OutEdge("n-land"), OutEdge("n-w", Set(OutEdge.Fail), OutEdge.Loop)),
          createdAt = now - 40_000,
          completedAt = Some(now - 30_000)
        )
      )
      snap <- rt.store.snapshot
      v0 = snap.nodes("n-v0")
      ok = snap.nodes("n-ok")
      v0With = NodePayload.buildNodeJson(v0, now, nodes = Some(snap.nodes))
      v0Without = NodePayload.buildNodeJson(v0, now)
      okWith = NodePayload.buildNodeJson(ok, now, nodes = Some(snap.nodes))
      okWithout = NodePayload.buildNodeJson(ok, now)
      listKeysV0 <- keysOf(rt, "n-v0")
      listKeysOk <- keysOf(rt, "n-ok")
      _ <- IO(
        println(
          "[spec] R10 key sets —\n" +
            s"  v0 injected=${v0With.asObject.map(_.keys.toList.sorted).getOrElse(Nil).mkString(",")}\n" +
            s"  v0 plain   =${v0Without.asObject.map(_.keys.toList.sorted).getOrElse(Nil).mkString(",")}\n" +
            s"  ok injected=${okWith.asObject.map(_.keys.toList.sorted).getOrElse(Nil).mkString(",")}\n" +
            s"  ok plain   =${okWithout.asObject.map(_.keys.toList.sorted).getOrElse(Nil).mkString(",")}\n" +
            s"  NodeList payload v0=${listKeysV0.mkString(",")}\n" +
            s"  NodeList payload ok=${listKeysOk.mkString(",")}\n" +
            s"  predicate v0=${NodePayload.verifierPassUnconsumed(v0, snap.nodes)} ok=${NodePayload.verifierPassUnconsumed(ok, snap.nodes)} " +
            s"valueToken=${NodePayload.VerifierPassUnconsumed}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // byte-level zero drift: the snapshot judgement face contributes no key to either shape
      assertEquals(
        v0With.asObject.map(_.keys.toList.sorted),
        v0Without.asObject.map(_.keys.toList.sorted),
        "the injected snapshot face must add no key for the V-0 shape"
      )
      assertEquals(
        okWith.asObject.map(_.keys.toList.sorted),
        okWithout.asObject.map(_.keys.toList.sorted),
        "the injected snapshot face must add no key for the healthy verifier"
      )
      for (label, ks) <- List("v0-payload" -> listKeysV0, "ok-payload" -> listKeysOk) do
        assert(!ks.contains("verifierPassRoute"), s"$label: no pass-face key may appear, got $ks")
        assert(!ks.contains("verifierRoute"), s"$label: the fail-face key must stay absent here (healthy fail route), got $ks")
      // the visibility carrier exists as a predicate (deliberately NOT wired into the
      // payload: the brief's §2.1 file table lists no payload key for this batch)
      assert(NodePayload.verifierPassUnconsumed(v0, snap.nodes), "the V-0 predicate must read true")
      assert(!NodePayload.verifierPassUnconsumed(ok, snap.nodes), "the healthy shape must read false")
      assertEquals(NodePayload.VerifierPassUnconsumed, "unconsumed", "the value token is registered for later batches")
    end for
  }

end VerifierPassOutletGuardSpec
