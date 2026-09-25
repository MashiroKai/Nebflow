package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources, SpecResources, StubLlm}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * cancelloopfix 批（2026-09-17，作者裁定 **#675(a)** / 跟踪卡 **#697**）：取消族摘除腿
 * [[NodeEngine.detachCancelledUpstream]] 的**目标集排除 `:loop` 回边目标**。
 *
 * 语义一句：与方案 A / 判据 M9（**`:loop` 不作级联传导**，具名钉在
 * `NodeEngine.referencesOf`）**一致化**——**不连坐 ⇒ 便签也不该有**。回边目标不再收
 * `pendingSuccession`（「待承接」）便签、不再收那 1 帧 `nodeUpdated`。两处口径**分开**：
 *   - **传导面**（谁被级联取消）= `referencesOf`，本批**零改动**（机械核见 L6）；
 *   - **打标面**（谁收便签/帧）= `detachCancelledUpstream`，本批**只改这里**。
 *
 * 判据（逐条对应任务书编号）：
 *  - P1 正面核心：取消 verifier ⇒ 回边目标（worker）**零便签 + 零帧**（三段入口读数：
 *    `cascade=true` 链级/级联腿 · `cascade=false` 单节点腿 · stale-running reap 腿）。
 *  - P1′ 保留面对照：同一操作里**非回边**下游（正常 out 目标）照旧 prune + 便签 + 1 帧。
 *  - P1″ 声明的连带面（#697 范围外，见批报告 ⑥/⑨）：out 只剩回边的被取消节点 ⇒
 *    早退零写 ⇒ 其 `:loop` 声明边保留（不再改接 Nebula）。
 *  - P2 负向零回归：`detachAbandonedNode` / `reversePruneReferences` 两窗口内**零**
 *    `cascade*` / `suppressTargets` / `cascadeCancelledIds`（**剥注释后**判）＋行为核
 *    （abandon 腿在级联跑过之后仍写今日 marker）。
 *  - L6 传导面保留：`referencesOf` 的 `:loop` 排除两面俱在（本批未动它）。
 */
class CancelLoopTargetNoMarkerSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-cancel-loop-target"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"cancel-loop-target spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 装配（与 ChainCascadeSpec 同款骨架；独立临时工作区，无周期 sweep ⇒ 帧读数确定）──

  private final case class Rig(
    rt: ProjectRuntime,
    ws: os.Path,
    triggered: Ref[IO, List[String]],
    frames: Ref[IO, List[Json]]
  )

  private def mount(name: String, system: ActorSystem, res: SharedResources): IO[Rig] =
    val ws = tempRoot / s"ws-$name-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open(name, ws.toString)
      triggered <- Ref.of[IO, List[String]](Nil)
      frames <- Ref.of[IO, List[Json]](Nil)
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
        notifyTriggerOverride = Some((text: String) => triggered.update(_ :+ text)),
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
    yield Rig(rt, ws, triggered, frames)

    end for

  end mount

  private def withRig[A](name: String)(f: Rig => IO[A]): IO[A] =
    val system = ActorSystem(s"cancel-loop-target-$name-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      rig <- mount(name, system, res)
      a <- f(rig)
    yield a

  private def n(
    id: String,
    status: String,
    createdAt: Long,
    in: List[String] = Nil,
    out: List[OutEdge] = Nil,
    deps: List[String] = Nil,
    pendingSuccession: List[String] = Nil,
    role: String = NodeRoles.Task,
    lastVerdict: Option[String] = None,
    result: Option[String] = None
  ): NodeDef =
    NodeDef(
      id = id,
      name = id,
      agent = "general",
      status = status,
      in = in,
      out = out,
      deps = deps,
      pendingSuccession = pendingSuccession,
      role = role,
      lastVerdict = lastVerdict,
      result = result,
      createdAt = createdAt
    )

  private def seed(rig: Rig, nodes: List[NodeDef]): IO[Unit] =
    rig.rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(x => x.id -> x).toMap)).void

  private def statusOf(rig: Rig, id: String): IO[String] =
    rig.rt.store.getNode(id).map(_.map(_.status).getOrElse("<missing>"))

  private def markerOf(rig: Rig, id: String): IO[List[String]] =
    rig.rt.store.getNode(id).map(_.map(_.pendingSuccession).getOrElse(Nil))

  private def outOf(rig: Rig, id: String): IO[List[OutEdge]] =
    rig.rt.store.getNode(id).map(_.map(_.out).getOrElse(Nil))

  private def framesFor(rig: Rig, id: String): IO[List[Json]] =
    rig.frames.get.map(_.filter(_.hcursor.get[String]("nodeId").toOption.contains(id)))

  private def audit(rig: Rig): IO[List[(String, String, String)]] =
    IO.blocking(os.read(rig.ws / ".nebflow" / FlowMapEventLog.FileName))
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

  /**
   * 源码判据的**代码行视图**：剥掉注释行（以行注释符 / `*` / 块注释起首符 开头的整行）
   * ——文档注释里逐字引用了被判据约束的代码原文，不剥则「改掉代码、留下注释」照样绿
   * （M8/M11 同款手法）。
   */
  private def codeOnly(src: String): String =
    src.linesIterator
      .filterNot { l =>
        val t = l.trim
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
      }
      .mkString("\n")

  // 2026-09-25 D 步重钉:reversePruneReferences 随投递段迁至 NodeDelivery(self-type trait,
  // 行为保持重构)——P2 的该窗口改读新文件(锚文本不变),本读数器随之成对提供。
  private def nodeDeliverySrc: String =
    codeOnly(os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "NodeDelivery.scala"))

  // 2026-09-25 G 步重钉:detachCancelledUpstream / detachAbandonedNode / referencesOf 随终态
  // 化簇自 NodeEngine 迁至 NodeCompletion(self-type trait,行为保持重构)——P2/L6 的相关
  // 窗口改读新文件(锚文本不变),本读数器随之成对提供。
  private def nodeCompletionSrc: String =
    codeOnly(os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "NodeCompletion.scala"))

  /** 源码窗口（**有界**：签名行起 N 行——方法间相隔数百行，`substring(A, B)` 会圈进无关腿）。 */
  private def window(src: String, sig: String, n: Int): String =
    val lines = src.linesIterator.toList
    val start = lines.indexWhere(_.contains(sig))
    assert(start >= 0, s"anchor not found: $sig")
    lines.slice(start, math.min(start + n, lines.size)).mkString("\n")

  /**
   * 判据拓扑（与 [[ChainCascadeSpec]] M9 逐字同形）：
   *   n-work(worker) ← `(fail)n-work:loop` ← n-ver(verifier) → n-land(正常 out sink)。
   * 🔴 worker 的 `in` 为空——**回边不写 `in` 镜像**（`NodeTools` 三处写点同款过滤）⇒
   * 它只可能经**前向**扫描进目标集（这正是本批排除面的唯一入口）。
   */
  private def loopTopology(
    rig: Rig,
    verStatus: String = NodeLifecycle.Pending,
    verLastVerdict: Option[String] = None
  ): IO[Unit] =
    seed(
      rig,
      List(
        n("n-work", NodeLifecycle.Pending, 1000L),
        n(
          "n-ver",
          verStatus,
          2000L,
          in = List("n-work"),
          role = NodeRoles.Verifier,
          lastVerdict = verLastVerdict,
          out = List(OutEdge("n-land"), OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop))
        ),
        n("n-land", NodeLifecycle.Pending, 3000L, in = List("n-ver"))
      )
    )

  /** P1 核心读数（三段入口共用）：worker 零便签 + 零帧；verifier 落 cancelled。 */
  private def assertLoopTargetUntouched(rig: Rig, leg: String): IO[Unit] =
    for
      workStatus <- statusOf(rig, "n-work")
      workMarker <- markerOf(rig, "n-work")
      workFrames <- framesFor(rig, "n-work")
      verStatus <- statusOf(rig, "n-ver")
      _ <- IO(
        println(
          s"[spec] $leg — worker(status=$workStatus, pendingSuccession=$workMarker, " +
            s"frames=${workFrames.length} ${workFrames.map(_.noSpaces.take(160))}); verifier=$verStatus"
        )
      )
    yield
      assertEquals(verStatus, NodeLifecycle.Cancelled, s"$leg: the verifier itself must be cancelled")
      assertEquals(workStatus, NodeLifecycle.Pending, s"$leg: the loop target keeps its status (never burned)")
      assertEquals(workMarker, Nil, s"$leg: #675(a) — the ':loop' target must NOT receive the 待承接 marker")
      assertEquals(workFrames.length, 0, s"$leg: and must NOT receive the R4 nodeUpdated frame either")
      assertEquals(workStatus, NodeLifecycle.Pending)

  // ── P1（三段入口：级联腿 / 单节点腿 / reap 腿）─────────────────────────

  test(
    "P1 cascade leg: cancelling the verifier (cascade=true) never marks/bumps the ':loop' back-edge target — while the normal-out sink is still cancelled as today"
  ) {
    withRig("p1-cascade") { rig =>
      for
        _ <- loopTopology(rig)
        chain <- rig.rt.store.chainMembersOf("chain-n-work")
        report <- rig.rt.engine.cancelNodes(List("n-ver"), CancelSource.User, "verifier gone", cascade = true)
        _ <- assertLoopTargetUntouched(rig, "P1/cascade")
        sts <- List("n-ver", "n-land", "n-work").traverse(statusOf(rig, _))
        landMarker <- markerOf(rig, "n-land")
        landFrames <- framesFor(rig, "n-land")
        _ <- IO(
          println(s"[spec] P1/cascade 保留面对照 — n-land(pendingSuccession=$landMarker, frames=${landFrames.length})")
        )
      yield
        assertEquals(
          chain.map(_.info.memberIds).getOrElse(Nil),
          List("n-work", "n-ver", "n-land"),
          "precondition: component membership still includes the loop edge (FlowMapStore.topologicalChains)"
        )
        assertEquals(
          report.cancelled.map(_.nodeId).sorted,
          List("n-land", "n-ver"),
          "the conduction face is untouched: the normal-out sink still cascades, the worker never enters the cancel set"
        )
        assertEquals(sts, List("cancelled", "cancelled", "pending"))
        // 保留面：非回边下游照旧被 prune（in 镜像）+ 打标；其标记为 suppressTargets 命中（取消集内）⇒ 无便签
        assertEquals(
          landMarker,
          Nil,
          "the sink is inside the cancelled set ⇒ suppressTargets keeps its marker empty (unchanged)"
        )
        assert(landFrames.nonEmpty, "the sink is still touched by the cancel family (its frames are unchanged)")
    }
  }

  test(
    "P1 single-node leg (NodeCancel口径, cascade=false): the loop target still gets zero marker / zero frame, while the non-loop downstream keeps today's marker + exactly one frame"
  ) {
    withRig("p1-single") { rig =>
      for
        _ <- loopTopology(rig)
        _ <- rig.rt.engine.cancelNodes(List("n-ver"), CancelSource.User, "NodeCancel single-node leg", cascade = false)
        _ <- assertLoopTargetUntouched(rig, "P1/single-node")
        landStatus <- statusOf(rig, "n-land")
        landMarker <- markerOf(rig, "n-land")
        landFrames <- framesFor(rig, "n-land")
        verOut <- outOf(rig, "n-ver")
        _ <- IO(
          println(
            s"[spec] P1/single-node 保留面对照 — n-land(status=$landStatus, " +
              s"pendingSuccession=$landMarker, frames=${landFrames.length}); n-ver.out=$verOut"
          )
        )
      yield
        assertEquals(landStatus, NodeLifecycle.Pending, "no cascade ⇒ the sink is not cancelled")
        assertEquals(landMarker, List("n-ver"), "保留面：非回边前向目标照旧收「待承接」便签（逐字今日行为）")
        assertEquals(landFrames.length, 1, "保留面：非回边目标照旧收那 1 帧 nodeUpdated")
        assertEquals(verOut, List(OutEdge.root), "保留面：被取消节点 out 改接 Nebula（该节点 out 里还有非回边目标 ⇒ 仍走改接腿）")
    }
  }

  test(
    "P1 reap leg: a dead-session (stale running) verifier reaped through the cancel family leaves the ':loop' target unmarked and frameless"
  ) {
    withRig("p1-reap") { rig =>
      for
        _ <- loopTopology(rig, verStatus = NodeLifecycle.Running)
        _ <- rig.rt.engine.cancelNodes(List("n-ver"), CancelSource.Engine, "dead-session reap leg", cascade = false)
        _ <- assertLoopTargetUntouched(rig, "P1/reap")
      yield ()
    }
  }

  // ── P1″ 声明的连带面（#697 范围外；本批在报告 ⑥/⑨ 逐条申报）────────────

  test(
    "P1'' declared collateral: when the cancelled node's out carries ONLY a ':loop' edge (no node target), the target set is empty ⇒ zero-write early exit also skips the out→Nebula rewrite (the ':loop' declaration edge is retained)"
  ) {
    withRig("p1-looponly") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-work", NodeLifecycle.Pending, 1000L),
            n(
              "n-ver",
              NodeLifecycle.Pending,
              2000L,
              in = List("n-work"),
              role = NodeRoles.Verifier,
              out = List(OutEdge.root, OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop))
            )
          )
        )
        _ <- rig.rt.engine.cancelNodes(List("n-ver"), CancelSource.User, "loop-only out", cascade = false)
        _ <- assertLoopTargetUntouched(rig, "P1''/loop-only")
        verOut <- outOf(rig, "n-ver")
        verAudit <- audit(rig)
        cancelledSummaries = verAudit.filter(_._1 == "cancelled").map(_._3)
        _ <- IO(println(s"[spec] P1'' 连带面读数 — n-ver.out=$verOut; cancelled summaries=$cancelledSummaries"))
      yield
        assertEquals(
          verOut,
          List(OutEdge.root, OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop)),
          "declared collateral (#697 范围外)：targets 全被排除 ⇒ 早退零写 ⇒ ':loop' 声明边保留"
        )
        assert(
          cancelledSummaries.forall(!_.contains("awaiting handover")),
          s"no phantom handover successor may be named for a pure ':loop' target: $cancelledSummaries"
        )
    }
  }

  // ── P2（负向零回归：机械核 + 行为核）─────────────────────────────────

  test(
    "P2 machine: the non-cancel-family marker legs stay free of cascade flags (comment-stripped source windows), and the cancel-family window keeps the exclusion + its 取代面"
  ) {
    // 2026-09-25 G 步重钉:detachCancelledUpstream / detachAbandonedNode 随终态化簇自
    // NodeEngine 迁至 NodeCompletion(self-type trait,行为保持重构)——两窗口改读新
    // 文件,锚文本不变(trait 内保持原可见性 private def)。判据语义不变。
    val detachCancelled = window(nodeCompletionSrc, "private def detachCancelledUpstream", 60)
    val abandon = window(nodeCompletionSrc, "def detachAbandonedNode", 60)
    // 2026-09-25 D 步重钉:reversePruneReferences 已随投递段迁至 NodeDelivery——窗口改读
    // 新文件,锚文本不变(private def 留 trait 内私有),窗口语义不变。
    val reversePrune = window(nodeDeliverySrc, "private def reversePruneReferences", 20)
    println(
      s"[spec] P2 machine windows — detachCancelledUpstream=${detachCancelled.linesIterator.size} lines, " +
        s"detachAbandonedNode=${abandon.linesIterator.size}, reversePruneReferences=${reversePrune.linesIterator.size} lines"
    )
    // ① 保留面：非取消族两条腿与 cascade 族**零耦合**
    assert(!abandon.contains("cascade"), "detachAbandonedNode must stay cascade-agnostic")
    assert(!abandon.contains("suppressTargets"), "detachAbandonedNode must not take suppressTargets")
    assert(!abandon.contains("cascadeCancelledIds"), "detachAbandonedNode must not consult the cascade suppress set")
    assert(!reversePrune.contains("cascade"), "reversePruneReferences must stay cascade-agnostic")
    assert(!reversePrune.contains("suppressTargets"), "reversePruneReferences must not take suppressTargets")
    // ② 取代面：取消族写点仍由 suppressTargets / cascadeCancelledIds 决定（本批未动）
    assert(detachCancelled.contains("suppressTargets"), "the cancel-family detach keeps its suppressTargets 取代面")
    assert(detachCancelled.contains("cascadeCancelledIds"), "and keeps consulting the cross-call suppress set")
    // ③ 本批判据（#675(a)）：目标集前向腿排除 `:loop`
    // 2026-09-24:钉死文本更新为 scalafmt 重排后的两行形态(判据语义不变)。
    assert(
      detachCancelled.contains("val forward = from.out\n                .filterNot(OutEdge.isLoopEdge)"),
      "the marking leg's forward scan must skip ':loop' back-edges (the whole point of #675(a))"
    )
  }

  test(
    "P2 behaviour: the abandon detach leg still writes today's 待承接 marker even after a cascade ran (non-cancel leg untouched)"
  ) {
    withRig("p2-behaviour") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-a", NodeLifecycle.Pending, 1000L, out = List(OutEdge("n-b"))),
            n("n-b", NodeLifecycle.Pending, 2000L, in = List("n-a")),
            n("n-z", NodeLifecycle.Cancelled, 3000L, out = List(OutEdge("n-y"))),
            n("n-y", NodeLifecycle.Pending, 4000L, in = List("n-z"))
          )
        )
        _ <- rig.rt.engine.cancelNodes(List("n-a"), CancelSource.User, "prime the cascade set", cascade = true)
        d <- rig.rt.engine.detachAbandonedNode("n-z", NodeLifecycle.Cancelled)
        marker <- markerOf(rig, "n-y")
      yield
        assert(d.referrers.nonEmpty, s"abandon leg still rewrites referrers: $d")
        assertEquals(marker, List("n-z"), "abandon leg writes today's marker even after a cascade ran")
    }
  }

  // ── L6（传导面保留：本批零改动的具名钉点）────────────────────────────

  test(
    "L6: the conduction exclusion stays pinned at referencesOf — both :loop scan faces are present there and absent as a *behaviour* change in this batch"
  ) {
    // 2026-09-25 B 步重钉:referencesOf 曾留守 NodeEngine,仅因取消/销毁窗簇迁出的
    // NodeCanceller(self-type trait)经 cascadeClosure 引用它而加宽 private[project]。
    // 2026-09-25 G 步重钉:referencesOf 随终态化簇自 NodeEngine 迁至 NodeCompletion
    // (self-type trait,行为保持重构)——窗口改读新文件,锚文本不变。判据语义不变。
    val refs = window(nodeCompletionSrc, "private[project] def referencesOf", 30)
    assert(
      refs.contains("filterNot(OutEdge.isLoopEdge)"),
      "the forward conduction scan must keep skipping ':loop' back-edges (pinned at referencesOf, untouched)"
    )
    assert(
      refs.contains("!OutEdge.isLoopEdge(e)"),
      "the reverse conduction scan must keep skipping ':loop' back-edges (pinned at referencesOf, untouched)"
    )
    withRig("l6") { rig =>
      for
        _ <- loopTopology(rig)
        report <- rig.rt.engine.cancelNodes(List("n-ver"), CancelSource.User, "conduction face check", cascade = true)
      yield assertEquals(
        report.cancelled.map(_.nodeId).sorted,
        List("n-land", "n-ver"),
        "conduction face reading is unchanged: the worker is never carried into the cancel set by the ':loop' edge"
      )
    }
  }
end CancelLoopTargetNoMarkerSpec
