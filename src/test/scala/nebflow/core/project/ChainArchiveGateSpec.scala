package nebflow.core.project

import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SpecResources
import nebflow.core.tools.NodeTools
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk}

import scala.concurrent.duration.*

/**
 * Chain archive gate — case A **(b) release + warn**, red-verification anchor.
 *
 * One sentence of semantics: a chain whose members are all terminal is archived **exactly
 * as before**; when the component additionally carries a verifier whose `pass` verdict has
 * neither an outlet nor a consumer (the strict `V-0` form), the conclusion is carried
 * outward as one extra `chain-archive-held` line. 🔴 The archive behaviour itself is
 * untouched — a blocking gate would manufacture "the chain never archives" (the #1180
 * "waiting ≠ no progress" family error).
 *
 * Mechanical acceptance list covered here (task brief §2.5):
 *  - **R5** release + warn: form B (verdict written, fail leg only, **no referrer**)
 *    archives as usual (`chain-archived` exactly 1) AND `chain-archive-held` exactly 1,
 *    whose summary carries `unconsumedVerdicts=n-v2` plus both manual exits;
 *  - **R5b** the carried conclusion: the sweep's own return value lists the strict V-0 id
 *    for the consumer-less component and nothing for the component that has a referrer;
 *  - **R6** no false positive: form A (a downstream merge holds an `in` reference to the
 *    verifier — the 15 on-record landing-position shapes) archives as usual with **zero**
 *    `chain-archive-held`;
 *  - **R7** recovery: declaring a pass outlet before the sweep releases the chain with no
 *    held line at all (purely derived judgement, zero bookkeeping);
 *  - **R8** zero back-fill: loading archive batch files shaped like the 35 on-record V-0
 *    records leaves every batch file byte- and mtime-identical and writes no audit line;
 *  - **R9** single-shot ledger: three consecutive production ticks still yield exactly ONE
 *    held line (the 30 s sweep re-judges the same unsolved component every pass);
 *  - **R10** release contract: the members really move to the archive and stay addressable
 *    through `findNode` / `chainMembersOf`, and no new field rides into the batch file.
 *
 * Mutation arm (b): strip the `V-0` derivation out of the archive path (`unconsumedVerdicts
 * = Nil`) ⇒ R5 must turn red.
 *
 * Harness discipline: the production call path is driven end to end (`ProjectActor.
 * ProjectCommand.TtlTick` → `sweepCompletedChainsDetailed` → audit → index flip), never a
 * direct call into the internals — the same wiring `ChainArchivedEventSpec` pins.
 * `PathUtil.dataRoot` is nailed to a temporary home; the real home is never written.
 */
class ChainArchiveGateSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chain-archive-gate"

  private var prevRoot: os.Path = null
  private var home: os.Path = null

  /**
   * Deterministic time base (the same pinned-offset device `ChainArchivedEventSpec` uses):
   * the archive partition heading renders through `ZoneId.systemDefault()`, so the fixture
   * pins a FIXED-OFFSET zone (`GMT+08:00`; a bare `+08:00` silently falls back to GMT) and
   * then asserts the pin took effect by reading the offset back. Restored unconditionally.
   */
  private val PinnedTzId = "GMT+08:00"
  private val PinnedTzOffsetSeconds = 8 * 3600
  private var prevTz: java.util.TimeZone = null

  override def beforeEach(context: munit.BeforeEach): Unit =
    prevTz = java.util.TimeZone.getDefault
    super.beforeEach(context)

  override def afterEach(context: munit.AfterEach): Unit =
    if prevTz != null then java.util.TimeZone.setDefault(prevTz)
    prevTz = null
    super.afterEach(context)

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(java.nio.file.Files.createTempDirectory("nb-chain-archive-gate-home"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private class NoopLlm extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil: timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

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

  private def ofType(ws: os.Path, typ: String): IO[List[(String, String, String)]] =
    readAudit(ws).map(_.filter(_._1 == typ))

  // ── node shapes (the brief's §2.4 fixture forms, never hand-rolled) ──

  private def verifierNode(id: String, name: String, verdict: Option[String], out: List[OutEdge], createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name verify task"),
      status = NodeLifecycle.Completed,
      role = NodeRoles.Verifier,
      lastVerdict = verdict,
      result = Some(s"verdict report for $name"),
      out = out,
      createdAt = createdAt,
      completedAt = Some(createdAt + 1000)
    )

  private def workerNode(id: String, name: String, createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      task = Some(s"$name work task"),
      status = NodeLifecycle.Completed,
      result = Some(s"$name artifact"),
      out = List(OutEdge.root),
      createdAt = createdAt,
      completedAt = Some(createdAt + 1000)
    )

  private def mergeNode(id: String, name: String, in: List[String], createdAt: Long): NodeDef =
    NodeDef(
      id = id,
      name = name,
      agent = "general",
      merge = true,
      task = Some(s"$name landing task"),
      status = NodeLifecycle.Completed,
      in = in,
      out = List(OutEdge.root),
      createdAt = createdAt,
      completedAt = Some(createdAt + 1000)
    )

  /**
   * **form B** (brief §2.4, the strict `V-0` shape on record 35 times): a verifier with a
   * written `pass` verdict and a fail leg only, with **no referrer anywhere** — its own
   * component `chain-n-v2` (the verifier is the earliest member, so the derived chain id is
   * anchored on it).
   */
  private def formB(base: Long): Map[String, NodeDef] =
    Map(
      "n-v2" -> verifierNode("n-v2", "V2", Some("pass"), List(OutEdge("n-w2", Set(OutEdge.Fail), OutEdge.Loop)), base - 300_000),
      "n-w2" -> workerNode("n-w2", "W2", base - 200_000)
    )

  /**
   * **form A** (brief §2.4, the `V-0prime` shape on record 15 times): the same verifier
   * shape plus a downstream merge whose `in` ledger names the judged worker **and the
   * verifier** — the settled consumer that makes the strict `V-0` judgement false.
   * Component `chain-n-v1` (verifier is the earliest member).
   */
  private def formA(base: Long): Map[String, NodeDef] =
    Map(
      "n-v1" -> verifierNode("n-v1", "V1", Some("pass"), List(OutEdge("n-w1", Set(OutEdge.Fail), OutEdge.Loop)), base - 500_000),
      "n-w1" -> workerNode("n-w1", "W1", base - 400_000),
      "n-s1" -> mergeNode("n-s1", "S1", List("n-w1", "n-v1"), base - 100_000)
    )

  private def seed(store: FlowMapStore, nodes: Map[String, NodeDef]): IO[Unit] =
    store.mutate(s => s.copy(nodes = s.nodes ++ nodes)).void

  private def fixture(tag: String): IO[(os.Path, ActorSystem, nebflow.actor.ActorRef[ProjectActor.ProjectCommand], ProjectRuntime)] =
    val ws = tempRoot / s"ws-$tag-${System.nanoTime()}"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cag-$tag-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new NoopLlm)
      store <- FlowMapStore.open("cag", ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = "cag",
        emitEvent = (_: String, _: String, _: Json) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = "cag",
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = System.currentTimeMillis()
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
      ref <- system.spawn(
        ProjectActor(ProjectActor.ProjectConfig(rt.project, rt.engine, system, res, "nebula-root")),
        s"proj-cag-$tag-${scala.util.Random.nextInt(100000)}"
      )
    yield (ws, system, ref, rt)

  /** One production tick plus a bounded wait for the resulting audit line. */
  private def tickAndWait(ref: nebflow.actor.ActorRef[ProjectActor.ProjectCommand], ws: os.Path, typ: String): IO[Unit] =
    val events = ws / ".nebflow" / FlowMapEventLog.FileName
    (ref ! ProjectActor.ProjectCommand.TtlTick).void *>
      waitUntil(30.seconds)(IO.blocking(os.exists(events) && os.read(events).contains(typ))) *>
      IO.sleep(400.millis)

  /** A tick plus a settle window (used where nothing new is expected). */
  private def tick(ref: nebflow.actor.ActorRef[ProjectActor.ProjectCommand]): IO[Unit] =
    (ref ! ProjectActor.ProjectCommand.TtlTick).void *> IO.sleep(2000.millis)

  private def pinTz: IO[Unit] =
    IO(java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(PinnedTzId))) *>
      IO(
        assertEquals(
          java.time.ZoneId.systemDefault().getRules.getOffset(java.time.Instant.now()).getTotalSeconds,
          PinnedTzOffsetSeconds,
          "the fixture-pinned TZ must be in force (otherwise the heading shape drifts with the host)"
        )
      )

  // ── R5: the strict V-0 component is released WITH the warn line ────

  test(
    "R5 (b) release + warn: a fully terminal component holding the strict V-0 shape archives as usual (chain-archived exactly 1) AND emits exactly one chain-archive-held naming the unconsumed verifier and both manual exits"
  ) {
    for
      _ <- pinTz
      (ws, system, ref, rt) <- fixture("r5")
      _ <- seed(rt.store, formB(System.currentTimeMillis()))
      _ <- tickAndWait(ref, ws, FlowMapEventLog.ChainArchiveHeldType)
      arch <- ofType(ws, FlowMapEventLog.ChainArchivedType)
      held <- ofType(ws, FlowMapEventLog.ChainArchiveHeldType)
      active <- rt.store.snapshot.map(_.nodes.keySet.toList.sorted)
      archNodes <- rt.store.archiveSnapshot.map(_.nodes.keySet.toList.sorted)
      batches <- IO.blocking(os.list(ws / ".nebflow" / FlowMapStore.ArchiveDirName).map(_.last).toList.sorted)
      _ <- IO(
        println(
          s"[spec] R5 chain-archived=${arch.map(a => s"[${a._2}] ${a._3}")}\n" +
            s"[spec] R5 chain-archive-held=${held.map(a => s"[${a._2}] ${a._3}")}\n" +
            s"[spec] R5 active=${active.mkString(",")} archived=${archNodes.mkString(",")} batches=${batches.mkString(",")}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(arch.size, 1, s"the component must archive exactly as before (release), got $arch")
      assert(arch.forall(_._3.contains("unconsumedVerdicts") == false), s"chain-archived summaries stay untouched, got $arch")
      assertEquals(held.size, 1, s"exactly one chain-archive-held line, got $held")
      assertEquals(held.head._2, "n-v2", "nodeId = the component's earliest createdAt node (same source as chain-archived)")
      val summary = held.head._3
      assert(summary.startsWith("chain=chain-n-v2 "), s"the summary must carry the component chain id, got $summary")
      assert(summary.contains("members=2"), s"members = the component member count, got $summary")
      assert(summary.contains("unconsumedVerdicts=n-v2"), s"the summary must name the unconsumed verifier, got $summary")
      assert(summary.contains("reason=pass-verdict-unconsumed"), s"the reason token must ride along, got $summary")
      assert(summary.contains("'(pass)<landing>'"), s"manual exit 1 must ride along, got $summary")
      assert(summary.contains("abandon=true"), s"manual exit 2 must ride along, got $summary")
      assert(summary.contains("archived as usual"), s"the summary must state the archive was NOT blocked, got $summary")
      // the release really happened for every member of the component
      assertEquals(active, Nil, s"the component left the active area, got $active")
      assertEquals(archNodes, List("n-v2", "n-w2"), s"both members landed in the archive, got $archNodes")
      assertEquals(batches, List("chain-n-v2.json"), s"one batch file per chain id, got $batches")
    end for
  }

  // ── R5b: the sweep's own return carrier ───────────────────────────

  test(
    "R5b (b) carried conclusion: the sweep's return value lists the strict V-0 id for the consumer-less component and nothing for the component that has a referrer"
  ) {
    val ws = tempRoot / s"ws-r5b-${System.nanoTime()}"
    os.makeDir.all(ws)
    for
      _ <- pinTz
      store <- FlowMapStore.open("cag-r5b", ws.toString)
      base = System.currentTimeMillis()
      _ <- seed(store, formA(base))
      _ <- seed(store, formB(base))
      swept <- store.sweepCompletedChainsDetailed(base)
      byId = swept.map(c => c.chainId -> c).toMap
      activeAfter <- store.snapshot.map(_.nodes.keySet.toList.sorted)
      archivedAfter <- store.archiveSnapshot.map(_.nodes.keySet.toList.sorted)
      _ <- IO(
        println(
          "[spec] R5b swept=" +
            swept.map(c => s"${c.chainId}(nodeId=${c.nodeId},members=${c.members},unconsumed=${c.unconsumedVerdicts})").mkString(" ") +
            "\n[spec] R5b active=" + activeAfter.mkString(",") +
            "\n[spec] R5b archived=" + archivedAfter.mkString(",")
        )
      )
    yield
      assertEquals(swept.map(_.chainId).sorted, List("chain-n-v1", "chain-n-v2"), s"two components, got ${swept.map(_.chainId)}")
      val withReferrer = byId.getOrElse("chain-n-v1", fail("chain-n-v1 must be swept"))
      val consumerless = byId.getOrElse("chain-n-v2", fail("chain-n-v2 must be swept"))
      assertEquals(withReferrer.members, 3, "the referrer component holds three members")
      assertEquals(withReferrer.nodeId, "n-v1", "chain id anchors on the earliest createdAt member")
      assertEquals(
        withReferrer.unconsumedVerdicts,
        Nil,
        "the referrer form (15 on record) must carry NO unconsumed verdict"
      )
      assertEquals(consumerless.members, 2, "the consumer-less component holds two members")
      assertEquals(
        consumerless.unconsumedVerdicts,
        List("n-v2"),
        "the carried conclusion names the strict V-0 verifier"
      )
      assertEquals(activeAfter, Nil, "the release moved every member out of the active area")
      assertEquals(archivedAfter, List("n-s1", "n-v1", "n-v2", "n-w1", "n-w2"), "all members landed in the archive")
    end for
  }

  // ── R6: the referrer (15-form) is NOT flagged ──────────────────────

  test(
    "R6 (b) no false positive: the same shape WITH an in-referrer (the 15 on-record landing-position forms) archives as usual and stays silent on the archive warn"
  ) {
    for
      _ <- pinTz
      (ws, system, ref, rt) <- fixture("r6")
      _ <- seed(rt.store, formA(System.currentTimeMillis()))
      snap <- rt.store.snapshot
      referrers = NodeTools.inConsumersOf(snap.nodes)
      _ <- IO(
        println(
          s"[spec] R6 inConsumers=${referrers.toList.sorted.mkString(",")} ; " +
            s"V-0prime(n-v1)=${NodePayload.verifierPassUnconsumed(snap.nodes("n-v1"), snap.nodes)} " +
            "(the referrer clause is exactly what keeps it out of the archive warn)"
        )
      )
      _ <- tickAndWait(ref, ws, FlowMapEventLog.ChainArchivedType)
      arch <- ofType(ws, FlowMapEventLog.ChainArchivedType)
      held <- ofType(ws, FlowMapEventLog.ChainArchiveHeldType)
      active <- rt.store.snapshot.map(_.nodes.keySet.toList)
      _ <- IO(
        println(
          s"[spec] R6 chain-archived=${arch.map(_._3)} ; chain-archive-held=${held.size} ; active=${active.mkString(",")}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(referrers.contains("n-w1"), s"precondition: the sink references the judged worker, got ${referrers.toList.sorted}")
      assert(referrers.contains("n-v1"), s"precondition: the sink's in ledger names the verifier itself, got ${referrers.toList.sorted}")
      assert(
        NodePayload.verifierPassUnconsumed(snap.nodes("n-v1"), snap.nodes),
        "the visibility carrier (V-0prime) still reads true — the asymmetry between the two tiers is deliberate"
      )
      assertEquals(arch.size, 1, s"the component still archives as usual, got $arch")
      assertEquals(held, Nil, s"a verifier with a settled consumer must NOT be reported (false-positive guard), got $held")
      assertEquals(active, Nil, "the whole component left the active area")
    end for
  }

  // ── R7: the condition is purely derived ────────────────────────────

  test(
    "R7 (b) recovery: declaring a pass outlet before the sweep releases the chain with zero held line and no extra action (purely derived, zero bookkeeping)"
  ) {
    for
      _ <- pinTz
      (ws, system, ref, rt) <- fixture("r7")
      base = System.currentTimeMillis()
      _ <- seed(rt.store, formB(base))
      _ <- seed(rt.store, Map("n-land" -> workerNode("n-land", "LAND", base - 900_000)))
      // the repair: the verifier now declares a pass outlet onto an existing node (the
      // condition is purely derived ⇒ this single edit is the whole fix)
      _ <- rt.store
        .mutate(s =>
          s.copy(nodes =
            s.nodes.updated(
              "n-v2",
              s.nodes("n-v2").copy(out = List(OutEdge("n-land"), OutEdge("n-w2", Set(OutEdge.Fail), OutEdge.Loop)))
            )
          )
        )
        .void
      snapAfterFix <- rt.store.snapshot
      _ <- IO(
        println(
          s"[spec] R7 after the repair — n-v2.out=${snapAfterFix.nodes("n-v2").out} ; " +
            s"V-0prime=${NodePayload.verifierPassUnconsumed(snapAfterFix.nodes("n-v2"), snapAfterFix.nodes)} ; " +
            s"passOutlet=${NodeTools.passOutlet(snapAfterFix.nodes("n-v2"), snapAfterFix.nodes)}"
        )
      )
      _ <- tickAndWait(ref, ws, FlowMapEventLog.ChainArchivedType)
      arch <- ofType(ws, FlowMapEventLog.ChainArchivedType)
      held <- ofType(ws, FlowMapEventLog.ChainArchiveHeldType)
      active <- rt.store.snapshot.map(_.nodes.keySet.toList)
      _ <- IO(println(s"[spec] R7 chain-archived=${arch.size} chain-archive-held=${held.size} active=${active.mkString(",")}"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        !NodePayload.verifierPassUnconsumed(snapAfterFix.nodes("n-v2"), snapAfterFix.nodes),
        "the repaired verifier no longer reads as gap-shaped"
      )
      assert(
        NodeTools.passOutlet(snapAfterFix.nodes("n-v2"), snapAfterFix.nodes),
        "the pass outlet resolution must see the newly declared leg"
      )
      assertEquals(
        arch.size,
        1,
        s"the repaired verifier's component archives as usual while the landing node's own chain carries no verdict ⇒ no second announcement, got $arch"
      )
      assertEquals(held, Nil, s"the condition disappeared by itself ⇒ nothing to report, got $held")
      assertEquals(active, Nil, "the whole active area is drained")
    end for
  }

  // ── R8: zero back-fill on load ─────────────────────────────────────

  test(
    "R8 (b) zero back-fill: loading archive batch files shaped like the 35 on-record V-0 records leaves every batch file byte- and mtime-identical and writes no audit line"
  ) {
    val ws = tempRoot / s"ws-r8-${System.nanoTime()}"
    os.makeDir.all(ws)
    val base = System.currentTimeMillis()
    val batchDir = ws / ".nebflow" / FlowMapStore.ArchiveDirName
    // 35 legacy records on disk: each one a verifier with a written verdict and a fail leg
    // only. Archive batch files carry NO task (the archive writer strips it), exactly as
    // the 1558 on-disk batch files do — anything else would exercise the legacy-migration
    // path instead of the load path under test.
    val records: List[(String, Map[String, NodeDef])] =
      (1 to 35).toList.map { i =>
        val vid = f"n-v0-$i%02d"
        val wid = f"n-w0-$i%02d"
        val verifier = verifierNode(vid, s"V0$i", Some("pass"), List(OutEdge(wid, Set(OutEdge.Fail), OutEdge.Loop)), base - i * 10_000L)
          .copy(task = None)
        val worker = workerNode(wid, s"W0$i", base - i * 10_000L - 1000).copy(task = None)
        s"chain-$vid" -> Map(vid -> verifier, wid -> worker)
      }
    for
      _ <- IO.blocking(os.makeDir.all(batchDir))
      _ <- IO.blocking {
        records.foreach { case (batch, nodes) =>
          val file = FlowMapArchiveBatch(project = "cag-r8", batch = batch, archivedAt = base, nodes = nodes)
          os.write(batchDir / s"$batch.json", file.asJson.noSpaces)
        }
      }
      beforeFiles <- IO.blocking(os.list(batchDir).map(f => f.last -> (os.read(f) -> os.mtime(f))).toMap)
      reopened <- FlowMapStore.open("cag-r8", ws.toString)
      active <- reopened.snapshot.map(_.nodes.keySet.toList.sorted)
      archived <- reopened.archiveSnapshot.map(_.nodes.keySet.toList.sorted)
      verdicts <- reopened.archiveSnapshot.map(_.nodes.values.count(_.lastVerdict.exists(_.trim.nonEmpty)))
      afterFiles <- IO.blocking(os.list(batchDir).map(f => f.last -> (os.read(f) -> os.mtime(f))).toMap)
      eventsExist <- IO.blocking(os.exists(ws / ".nebflow" / FlowMapEventLog.FileName))
      // the derivation this batch introduced reads true on every loaded record, but the
      // load path never consumes it (only the sweep does)
      loadedV0 <- reopened.archiveSnapshot.map { a =>
        val referrers = NodeTools.inConsumersOf(a.nodes)
        a.nodes.values
          .filter(n =>
            n.role == NodeRoles.Verifier && n.lastVerdict.exists(_.trim.nonEmpty) &&
              !NodeTools.passOutlet(n, a.nodes) && !referrers.contains(n.id)
          )
          .map(_.id)
          .toList
          .sorted
      }
      _ <- IO(
        println(
          s"[spec] R8 active=${active.mkString(",")} ; archived nodes=${archived.size} ; verdicts=${verdicts}\n" +
            s"[spec] R8 batch files added=${afterFiles.keySet.diff(beforeFiles.keySet).mkString(",")} removed=" +
            s"${beforeFiles.keySet.diff(afterFiles.keySet).mkString(",")} contentDiff=" +
            s"${beforeFiles.filter { case (k, v) => afterFiles.get(k).exists(_ != v) }.keys.mkString(",")}\n" +
            s"[spec] R8 readable V-0 set (size=${loadedV0.size}) head=${loadedV0.take(3).mkString(",")}\n" +
            s"[spec] R8 events file exists=$eventsExist"
        )
      )
    yield
      assertEquals(archived.size, 70, s"all 35 legacy records load (verifier + worker each), got ${archived.size}")
      assertEquals(active, Nil, "loading never populates the active area")
      assertEquals(verdicts, 35, "all 35 verdicts survive the load")
      assert(loadedV0.size == 35, s"every legacy record reads as the strict V-0 shape (35 on record), got ${loadedV0.size}")
      assertEquals(afterFiles.keySet, beforeFiles.keySet, "the load writes no new batch file")
      assertEquals(afterFiles, beforeFiles, "every batch file stays byte- and mtime-identical (zero back-fill)")
      assertEquals(eventsExist, false, "the load path writes no audit line at all (zero trace, zero migration)")
    end for
  }

  // ── R9: single-shot ledger ─────────────────────────────────────────

  test(
    "R9 (b) single-shot ledger: three consecutive production ticks still yield exactly ONE chain-archive-held line (the 30 s sweep re-judges the same unsolved component every pass)"
  ) {
    for
      _ <- pinTz
      (ws, system, ref, rt) <- fixture("r9")
      _ <- seed(rt.store, formB(System.currentTimeMillis()))
      _ <- tickAndWait(ref, ws, FlowMapEventLog.ChainArchiveHeldType)
      first <- ofType(ws, FlowMapEventLog.ChainArchiveHeldType)
      firstArch <- ofType(ws, FlowMapEventLog.ChainArchivedType)
      _ <- tick(ref)
      second <- ofType(ws, FlowMapEventLog.ChainArchiveHeldType)
      _ <- tick(ref)
      third <- ofType(ws, FlowMapEventLog.ChainArchiveHeldType)
      arch <- ofType(ws, FlowMapEventLog.ChainArchivedType)
      _ <- IO(
        println(
          s"[spec] R9 held lines after tick1=${first.size} tick2=${second.size} tick3=${third.size} ; " +
            s"chain-archived tick1=${firstArch.size} total=${arch.size}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(first.size, 1, s"the first tick reports the component once, got $first")
      assertEquals(second.size, 1, s"the second tick must not append a duplicate, got $second")
      assertEquals(third.size, 1, s"the third tick must not append a duplicate, got $third")
      assertEquals(arch.size, 1, s"the archive announcement is a one-off too (the chain is gone after tick 1), got $arch")
    end for
  }

  // ── R10: release contract ──────────────────────────────────────────

  test(
    "R10 (b) release contract: the members really move to the archive and stay addressable (findNode / chainMembersOf), and the gate persists no new field"
  ) {
    for
      _ <- pinTz
      (ws, system, ref, rt) <- fixture("r10")
      _ <- seed(rt.store, formB(System.currentTimeMillis()))
      _ <- tickAndWait(ref, ws, FlowMapEventLog.ChainArchiveHeldType)
      active <- rt.store.snapshot.map(_.nodes.keySet.toList.sorted)
      // archived nodes remain addressable (the cross-region delivery contract is untouched)
      found <- rt.store.findNode("n-v2").map(_.map(_.id).toList)
      foundWorker <- rt.store.findNode("n-w2").map(_.map(_.id).toList)
      // the chain resolution single point still derives the archived component
      members <- rt.store.chainMembersOf("chain-n-v2").map(_.map(_.members.map(_.id).sorted).getOrElse(Nil))
      unknown <- rt.store.chainMembersOf("chain-does-not-exist").map(_.map(_.members.map(_.id).sorted).getOrElse(Nil))
      // the archived records carry no added persisted field: the judgement is derived, the
      // node documents are exactly the ones the release writer produced
      rawBatch <- IO.blocking(os.read(ws / ".nebflow" / FlowMapStore.ArchiveDirName / "chain-n-v2.json"))
      decoded <- IO.fromEither(jsonParse(rawBatch))
      nodeKeys <- IO(
        decoded.hcursor
          .downField("nodes")
          .downField("n-v2")
          .keys
          .map(_.toList.sorted)
          .getOrElse(Nil)
      )
      _ <- IO(
        println(
          s"[spec] R10 active=${active.mkString(",")} ; findNode(v2)=$found ; findNode(w2)=$foundWorker ; " +
            s"members(chain-n-v2)=${members.mkString(",")} ; unknown=${unknown.mkString(",")}\n" +
            s"[spec] R10 persisted batch node keys=${nodeKeys.mkString(",")}"
        )
      )
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(active, Nil, "the component left the active area (release)")
      assertEquals(found, List("n-v2"), "an archived node is still reachable through findNode (cross-region contract untouched)")
      assertEquals(foundWorker, List("n-w2"), "the archived worker is reachable too")
      assertEquals(members, List("n-v2", "n-w2"), "the chain resolution single point still derives the archived component")
      assertEquals(unknown, Nil, "an unknown chain id degrades to an empty member list (never fabricates members)")
      for k <- List("verifierPassRoute", "unconsumedVerdicts", "unconsumed") do
        assert(!nodeKeys.contains(k), s"no new persisted node field may ride along, got '$k' in $nodeKeys")
    end for
  }

end ChainArchiveGateSpec
