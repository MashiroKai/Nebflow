package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 上游取消 ⇒ 下游级联（R3）——chaincancel 批 2026-09-17 自证判据 M 类。
 *
 * 覆盖（逐条对应批任务书的判据编号；M10/M12 见 [[ChainCancelSpec]]）：
 *  - M1  `A → B → C` 取消 A ⇒ B、C 均 cancelled；`cascade=false` ⇒ 与今日逐字相同
 *  - M2  终态边界：遇 `completed` 即停，边界之后的节点零触碰
 *  - M3  被取消集合内 `pendingSuccession` 全空（**取代面判据**）
 *  - M4  顺序无关：createdAt 正序/逆序执行 ⇒ 报告逐字段相同
 *  - M5  反向并集（U1/U5/E 面）：`out` 已收束 Nebula 而下游 `in` 仍引用 ⇒ 必须扫到
 *  - M6  跨分量禁行：另一分量零状态写、零帧、零通知
 *  - M7  `deps` 轨纳入；`retry.upstream` 存在不改变集合（显式排除不变量）
 *  - M8  L3 不变量：`deferDetach ⇒ cascade=false`（结构断言 + 语义等价读数）
 *  - M9  `:loop` 不传导（取消 verify ⇒ 级联沿正常 out 到 sink，worker 零触碰）
 *        ——**cancelloopfix 批 2026-09-17（#675(a) / #697）起含打标面**：回边目标同时
 *        **零便签、零帧**（旧读数的 `pendingSuccession=List("n-ver")` + 1 帧作废）
 *  - M10 blocked / interrupted 纳入级联闭包（显式枚举，不用 `Terminal` 谓词）
 *  - M11 「取代面 / 保留面」机械可核：非取消族写入点与 `cascade` 旗标零耦合
 */
class ChainCascadeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chain-cascade"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"chain-cascade spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 装配（与 ChainCancelSpec 同款骨架；两个 spec 各自独立的临时工作区）──

  private class StubLlm:

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, tmp: os.Path, llm: LlmHandle[IO]): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- nebflow.core.FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
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
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

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
    val system = ActorSystem(s"chain-cascade-$name-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new StubLlm().handle)
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
    retry: Option[RetryPolicy] = None,
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
      retry = retry,
      result = result,
      createdAt = createdAt
    )

  private def seed(rig: Rig, nodes: List[NodeDef]): IO[Unit] =
    rig.rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(x => x.id -> x).toMap)).void

  private def statusOf(rig: Rig, id: String): IO[String] =
    rig.rt.store.getNode(id).map(_.map(_.status).getOrElse("<missing>"))

  private def markerOf(rig: Rig, id: String): IO[List[String]] =
    rig.rt.store.getNode(id).map(_.map(_.pendingSuccession).getOrElse(Nil))

  private def framesFor(rig: Rig, id: String): IO[Int] =
    rig.frames.get.map(_.count(_.hcursor.get[String]("nodeId").toOption.contains(id)))

  /**
   * 源码判据的**代码行视图**：剥掉注释行（以 `//` 或 `*` 起首的整行）。
   *
   * 为什么必须剥（V7 实测，报告 ④）：本批新增的文档注释里**引用了**被判据约束的
   * 代码原文（如 `l3CascadeAllowed(deferDetach)`、`cascade=false`）——不剥的话，
   * 把代码改掉、留下注释，`contains` 断言照样绿 = 判据可被注释「背书」。
   * 反向同理：注释里出现 `cascade` 会让「保留面不得含 cascade」的负断言**误红**。
   */
  private def codeOnly(src: String): String =
    src.linesIterator
      .filterNot { l =>
        val t = l.trim
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
      }
      .mkString("\n")

  private def audit(rig: Rig): IO[List[(String, String)]] =
    IO.blocking(os.read(rig.ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(
        _.flatMap(l =>
          jsonParse(l).toOption
            .map(j => (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("summary").getOrElse("")))
        )
      )
      .handleError(_ => Nil)

  /** 线性链夹具 A → B → C（给定各自 createdAt，用来切换执行顺序）。 */
  private def lin(rig: Rig, cA: Long, cB: Long, cC: Long): IO[Unit] =
    seed(
      rig,
      List(
        n("n-a", NodeLifecycle.Pending, cA, out = List(OutEdge("n-b"))),
        n("n-b", NodeLifecycle.Pending, cB, in = List("n-a"), out = List(OutEdge("n-c"))),
        n("n-c", NodeLifecycle.Pending, cC, in = List("n-b"))
      )
    )

  // ── M1 ────────────────────────────────────────────────────────────

  test(
    "M1: seed A with cascade=true cancels A,B,C (closure travels the out edge); the same seed with cascade=false is today's behaviour (B stays pending and is marked pendingSuccession ∋ A, C never reached)"
  ) {
    withRig("m1-on") { rig =>
      for
        _ <- lin(rig, 1000L, 2000L, 3000L)
        right <- rig.rt.engine.cancelNodes(List("n-a"), CancelSource.User, "chain gone", cascade = true)
        sts <- List("n-a", "n-b", "n-c").traverse(statusOf(rig, _))
        markers <- List("n-a", "n-b", "n-c").traverse(markerOf(rig, _))
      yield
        // 种子只有一个 ⇒ B、C 是**级联闭包**带进来的（R3 传导；V1 变异：cascade 默认翻面 ⇒ 本行必红）
        assertEquals(sts, List("cancelled", "cancelled", "cancelled"))
        assertEquals(right.cancelled.map(_.nodeId), List("n-a", "n-b", "n-c"))
        assertEquals(markers, List(Nil, Nil, Nil), "M3: no marker left inside the cancelled set")
        assertEquals(right.injected, 1)
    } *>
      withRig("m1-off") { rig =>
        for
          _ <- lin(rig, 1000L, 2000L, 3000L)
          right <- rig.rt.engine.cancelNodes(List("n-a"), CancelSource.User, "no cascade", cascade = false)
          sts <- List("n-a", "n-b", "n-c").traverse(statusOf(rig, _))
          markers <- List("n-a", "n-b", "n-c").traverse(markerOf(rig, _))
        yield
          assertEquals(sts, List("cancelled", "pending", "pending"))
          // cascade=false（今日逐字行为）：上游摘除 ⇒ 下游留「待承接」便签；边界之后零触碰
          assertEquals(
            markers,
            List(Nil, List("n-a"), Nil),
            "non-cascading leg keeps today's handover marker (B carries pendingSuccession ∋ A); C never reached"
          )
          assertEquals(right.cancelled.map(_.nodeId), List("n-a"))
          assertEquals(right.preserved, Nil)
      }
  }

  // ── M2 ────────────────────────────────────────────────────────────

  test(
    "M2: the terminal boundary stops the cascade — A(pending) → B(completed) → C(pending) cancelled at A leaves C pending with zero writes and B preserved"
  ) {
    withRig("m2") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-a", NodeLifecycle.Pending, 1000L, out = List(OutEdge("n-b"))),
            n(
              "n-b",
              NodeLifecycle.Completed,
              2000L,
              in = List("n-a"),
              out = List(OutEdge("n-c")),
              result = Some("done")
            ),
            n("n-c", NodeLifecycle.Pending, 3000L, in = List("n-b"))
          )
        )
        before <- rig.rt.store.getNode("n-c")
        framesBeforeC <- framesFor(rig, "n-c")
        right <- rig.rt.engine.cancelNodes(List("n-a"), CancelSource.User, "root only", cascade = true)
        report = right
        sts <- List("n-a", "n-b", "n-c").traverse(statusOf(rig, _))
        resultB <- rig.rt.store.getNode("n-b").map(_.flatMap(_.result))
        after <- rig.rt.store.getNode("n-c")
        framesAfterC <- framesFor(rig, "n-c")
        auditRows <- audit(rig)
      yield
        // A 被取消；B（终态边界）保留；C **不在**声明成员集里（seeds = [A]）⇒ 不出现于
        // preserved/skipped 分区（分区口径恒按声明成员集，C7）——「零触碰」读数为证。
        assertEquals(report.cancelled.map(_.nodeId), List("n-a"))
        assert(!report.cancelled.exists(_.nodeId == "n-b"), "the terminal boundary node is never cancelled")
        // 分区口径恒按**声明种子集**（C7）：本腿 seeds = [n-a]，B/C 都不在声明集里
        // ⇒ preserved/skipped 为 Nil（B 的「保留」由下方逐字段零触碰读数证明；
        // 链级入口下 seeds = 全部成员，B 会以 why=terminal 出现在 preserved——
        // 见 ChainCancelSpec M10 的 `preserved` 读数）。
        assertEquals(report.preserved, Nil)
        assertEquals(report.skipped, Nil)
        assertEquals(sts, List("cancelled", "completed", "pending"))
        assertEquals(resultB, Some("done"), "terminal node keeps its result")
        assertEquals(after.map(_.status), before.map(_.status), "C: status untouched")
        assertEquals(after.map(_.in), before.map(_.in), "C: in-mirror untouched (cascade never reached it)")
        assertEquals(after.map(_.pendingSuccession), before.map(_.pendingSuccession), "C: no marker")
        assertEquals(framesAfterC, framesBeforeC, "C: zero nodeUpdated frames")
        assertEquals(auditRows.count(_._1 == "cancelled"), 1, "only A was cancelled")
    }
  }

  // ── M4 ────────────────────────────────────────────────────────────

  test(
    "M4: execution order is irrelevant — createdAt ascending vs descending yields field-by-field identical reports"
  ) {
    withRig("m4") { rig =>
      for
        _ <- lin(rig, 1000L, 2000L, 3000L)
        r1 <- rig.rt.engine.cancelNodes(List("n-a", "n-b", "n-c"), CancelSource.User, "ordered", cascade = true)
      yield r1
    }.flatMap { r1 =>
      withRig("m4r") { rig =>
        for
          _ <- lin(rig, 3000L, 2000L, 1000L) // 逆序 createdAt ⇒ 执行顺序翻转
          r2 <- rig.rt.engine.cancelNodes(List("n-a", "n-b", "n-c"), CancelSource.User, "ordered", cascade = true)
        yield (r1, r2)
      }
    }.map { case (r1, r2) =>
      assertEquals(r1.cancelled, r2.cancelled, "cancelled list identical (sorted by nodeId)")
      assertEquals(r1.preserved, r2.preserved)
      assertEquals(r1.skipped, r2.skipped)
      assertEquals(r1.prunedReferrers, r2.prunedReferrers)
      assertEquals(r1.injected, r2.injected)
      assertEquals(r1.notified, r2.notified)
    }
  }

  // ── M5（U1/E 不一致拓扑）──────────────────────────────────────────

  test(
    "M5: the reverse union saves the inconsistent topology — out already collapsed to Nebula while the downstream in-mirror still references the cancelled node"
  ) {
    withRig("m5") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-a", NodeLifecycle.Pending, 1000L, out = List(OutEdge.nebula)), // 前向恒空
            n("n-b", NodeLifecycle.Pending, 2000L, in = List("n-a"))
          )
        )
        report <- rig.rt.engine.cancelNodes(List("n-a"), CancelSource.User, "inconsistent topology", cascade = true)
        sts <- List("n-a", "n-b").traverse(statusOf(rig, _))
        inB <- rig.rt.store.getNode("n-b").map(_.map(_.in).getOrElse(Nil))
      yield
        // 前向目标恒空 ⇒ 纯前向实现会静默收场（V2 变异点：退回纯前向 ⇒ 本判据必红：
        // 反向并集救回 B ⇒ 序列 = [n-a, n-b]，纯前向只剩 [n-a]）
        assertEquals(report.cancelled.map(_.nodeId), List("n-a", "n-b"))
        assertEquals(sts, List("cancelled", "cancelled"), "the reverse union must reach B")
        assertEquals(inB, Nil, "B's stale in-mirror was pruned")
        assertEquals(report.prunedReferrers, List("n-b"))
    }
  }

  // ── M6 ────────────────────────────────────────────────────────────

  test(
    "M6: no cross-component travel — cancelling one component leaves the other with zero writes, zero frames and zero notifications"
  ) {
    withRig("m6") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-a", NodeLifecycle.Pending, 1000L, out = List(OutEdge("n-b"))),
            n("n-b", NodeLifecycle.Pending, 2000L, in = List("n-a")),
            n("n-x", NodeLifecycle.Pending, 1000L, out = List(OutEdge("n-y"))),
            n("n-y", NodeLifecycle.Pending, 2000L, in = List("n-x"))
          )
        )
        report <- rig.rt.engine.cancelNodes(List("n-a", "n-b"), CancelSource.User, "component 1 only", cascade = true)
        sts <- List("n-x", "n-y").traverse(statusOf(rig, _))
        framesX <- framesFor(rig, "n-x")
        framesY <- framesFor(rig, "n-y")
        auditRows <- audit(rig)
        injected <- rig.triggered.get
      yield
        assertEquals(report.cancelled.map(_.nodeId), List("n-a", "n-b"))
        assertEquals(sts, List("pending", "pending"), "other component untouched")
        assertEquals(framesX, 0)
        assertEquals(framesY, 0)
        assertEquals(auditRows.count(_._2.contains("n-x")), 0)
        assertEquals(auditRows.count(_._2.contains("n-y")), 0)
        assertEquals(injected.size, 1, "one injection for the operation (not per component)")
    }
  }

  // ── M7 ────────────────────────────────────────────────────────────

  test(
    "M7: the deps track is part of the cascade while retry.upstream neither adds nor removes members (explicit-exclusion invariant)"
  ) {
    withRig("m7") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-a", NodeLifecycle.Pending, 1000L),
            n("n-b", NodeLifecycle.Pending, 2000L, deps = List("n-a")),
            // retry.upstream 指向 in/deps 邻居（NODE_RETRY_NEIGHBOR）——不得改变集合
            n(
              "n-c",
              NodeLifecycle.Pending,
              3000L,
              deps = List("n-a"),
              retry = Some(RetryPolicy(upstream = "n-a", max = 2))
            ),
            // 负例：retry.upstream 指向 n-a 但**无任何图引用**（另一分量）⇒ 不在集合里
            n("n-z", NodeLifecycle.Pending, 9000L, retry = Some(RetryPolicy(upstream = "n-a", max = 2)))
          )
        )
        report <- rig.rt.engine.cancelNodes(List("n-a"), CancelSource.User, "deps track", cascade = true)
        sts <- List("n-a", "n-b", "n-c", "n-z").traverse(statusOf(rig, _))
        markerZ <- markerOf(rig, "n-z")
      yield
        assertEquals(report.cancelled.map(_.nodeId).sorted, List("n-a", "n-b", "n-c"))
        assertEquals(sts, List("cancelled", "cancelled", "cancelled", "pending"))
        assertEquals(markerZ, Nil, "retry.upstream is not a cascade edge (n-z untouched, no marker)")
        assertEquals(report.skipped, Nil)
    }
  }

  // ── M9（loop 对偶）────────────────────────────────────────────────

  test(
    "M9: the :loop verdict return edge is not a cascade transmission edge — cancelling the verifier cancels the normal-out sink and never cancels OR marks the worker (cancelloopfix 批 #675(a)/#697 取代面：便签与帧同步摘除)"
  ) {
    withRig("m9") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-work", NodeLifecycle.Pending, 1000L), // worker：out 空（回边由 verifier 持有）
            n(
              "n-ver",
              NodeLifecycle.Pending,
              2000L,
              in = List("n-work"),
              out = List(OutEdge("n-land"), OutEdge("n-work", Set(OutEdge.Fail), OutEdge.Loop))
            ),
            n("n-land", NodeLifecycle.Pending, 3000L, in = List("n-ver"))
          )
        )
        // 邻接/分量归属仍含回边（FlowMapStore.topologicalChains）⇒ 三者同链
        chain <- rig.rt.store.chainMembersOf("chain-n-work")
        report <- rig.rt.engine.cancelNodes(List("n-ver"), CancelSource.User, "verifier gone", cascade = true)
        sts <- List("n-ver", "n-land", "n-work").traverse(statusOf(rig, _))
        workFrames <- rig.frames.get.map(_.filter(_.hcursor.get[String]("nodeId").toOption.contains("n-work")))
        afterWork <- rig.rt.store.getNode("n-work")
        workAudit <- audit(rig)
      yield
        assertEquals(
          chain.map(_.info.memberIds).getOrElse(Nil),
          List("n-work", "n-ver", "n-land"),
          "component membership still includes the loop edge, unchanged (FlowMapStore.topologicalChains:1069)"
        )
        // 正常 out 边传导：sink 被取消（V9 变异：把 `:loop` 加回 `referencesOf` 的传导并集 ⇒
        // worker 进取消集 ⇒ 本行与下一行必红）
        assertEquals(report.cancelled.map(_.nodeId).sorted, List("n-land", "n-ver"))
        assertEquals(sts, List("cancelled", "cancelled", "pending"), "worker NEVER burned by the :loop edge")
        assert(!report.cancelled.exists(_.nodeId == "n-work"))
        // ── **取代面**（cancelloopfix 批 2026-09-17，作者裁定 #675(a) / 跟踪卡 #697）────
        // 旧读数（本批起作废）：R4 摘除腿把 `:loop` 当普通图边 ⇒ 回边目标照样收「待承接」
        // 便签 + 1 帧（本行曾断言 `Some(List("n-ver"))` / `length == 1`）。作者裁定与方案 A /
        // 判据 M9 **一致化**：**不连坐 ⇒ 便签也不该有**（现态是「同一操作内打标 + 紧接取消」
        // 自相矛盾态）。⇒ 目标集前向腿排除 `OutEdge.isLoopEdge`，两行断言随裁定**反转**。
        // 🔴 两处口径分开：传导面（谁被级联取消）= `referencesOf`（本批零改动，读数见上行）；
        // 打标面（谁收便签/帧）= `detachCancelledUpstream`（本批唯一改动点）。
        assertEquals(afterWork.map(_.status), Some(NodeLifecycle.Pending))
        assertEquals(afterWork.map(_.in), Some(Nil))
        assertEquals(afterWork.map(_.deps), Some(Nil))
        assertEquals(afterWork.flatMap(_.result), None)
        assertEquals(
          afterWork.map(_.pendingSuccession),
          Some(Nil),
          "#675(a): the ':loop' back-edge target must NOT receive the 待承接 marker any more"
        )
        assertEquals(workFrames.length, 0, s"and must NOT receive the R4 marker frame either: $workFrames")
        assertEquals(workAudit.count(_._1 == "cancelled"), 2, "only the verifier and the sink were cancelled")
    }
  }

  // ── M10（级联闭包侧）──────────────────────────────────────────────

  test(
    "M10: the cascade closure carries running/pending/wiring/blocked/interrupted (explicit enumeration, never the Terminal predicate)"
  ) {
    withRig("m10") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-a", NodeLifecycle.Pending, 1000L, out = List(OutEdge("n-run"))),
            n("n-run", NodeLifecycle.Running, 2000L, in = List("n-a"), out = List(OutEdge("n-blocked"))),
            n("n-blocked", NodeLifecycle.Blocked, 3000L, in = List("n-run"), out = List(OutEdge("n-intr"))),
            n("n-intr", NodeLifecycle.Interrupted, 4000L, in = List("n-blocked"), out = List(OutEdge("n-done"))),
            n("n-done", NodeLifecycle.Completed, 5000L, in = List("n-intr"), result = Some("kept"))
          )
        )
        report <- rig.rt.engine.cancelNodes(List("n-a"), CancelSource.User, "cascade scope", cascade = true)
        sts <- List("n-a", "n-run", "n-blocked", "n-intr", "n-done").traverse(statusOf(rig, _))
        done <- rig.rt.store.getNode("n-done").map(_.flatMap(_.result))
      yield
        assertEquals(
          report.cancelled.map(_.nodeId).sorted,
          List("n-a", "n-blocked", "n-intr", "n-run"),
          "blocked + interrupted + running all carried"
        )
        assertEquals(sts, List("cancelled", "cancelled", "cancelled", "cancelled", "completed"))
        assertEquals(done, Some("kept"), "terminal boundary preserved with its result")
        assert(NodeLifecycle.Terminal.contains(NodeLifecycle.Blocked))
        assert(NodeLifecycle.ChainCancelScope.contains(NodeLifecycle.Blocked))
    }
  }

  // ── M8（L3 不变量：结构 + 语义等价）──────────────────────────────

  test(
    "M8: the L3 hard-recovery intermediate state is hard-bound to cascade=false (structural assertion + semantic equivalence)"
  ) {
    val src = codeOnly(os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "NodeEngine.scala"))
    // ① 判据函数本身：deferDetach=true ⇒ 级联权限 false
    assert(!NodeEngine.l3CascadeAllowed(deferDetach = true), "L3 intermediate state must forbid cascading (§6-M8)")
    assert(NodeEngine.l3CascadeAllowed(deferDetach = false), "non-L3 legs keep their own default")
    // ② L3 腿的硬守卫在源文件里存在（防日后有人把 L3 腿改接到级联写路径上）
    assert(
      src.contains("l3CascadeAllowed(deferDetach)"),
      "the L3 leg must carry the explicit hard guard tying deferDetach to cascade=false"
    )
    val l3Leg = src.linesIterator.dropWhile(!_.contains("val deferDetach =")).take(20).mkString("\n")
    assert(l3Leg.contains("l3CascadeAllowed"), s"guard must sit at the L3 detach decision site:\n$l3Leg")
    assert(
      l3Leg.contains("cascadeRequested = cascadeRequested") && l3Leg.contains("l3Intermediate = deferDetach"),
      s"the guarded write entry must receive BOTH the requested flag and the L3 marker:\n$l3Leg"
    )
    // ②b 判据统计语义（防极性写反——2026-09-17 实测踩到过一次：写成「允许级联即抛」会让
    //     **全部非 L3 取消路径**静默失败，节点滞留 running，零终态；Z1 的
    //     NodeSessionDeathFinalizeSpec D2 是这条的机械哨兵）
    val guard = src.substring(src.indexOf("private def cancelNodeGuarded"), src.indexOf("private def setNodeBgWait"))
    assert(
      guard.contains("cascadeRequested && l3Intermediate"),
      s"the guard must fire on (requested ∧ L3-intermediate), not on a single flag:\n$guard"
    )
    // ③ 语义等价读数：不级联的那条腿（detach=false/notify=false 的 L3 形态）下游零触碰
    withRig("m8") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-a", NodeLifecycle.Running, 1000L, out = List(OutEdge("n-b"))),
            n("n-b", NodeLifecycle.Pending, 2000L, in = List("n-a"))
          )
        )
        report <- rig.rt.engine.cancelNodes(
          List("n-a"),
          CancelSource.User,
          "no cascade (L3-equivalent)",
          cascade = false
        )
        sts <- List("n-a", "n-b").traverse(statusOf(rig, _))
        marker <- markerOf(rig, "n-b")
      yield
        assertEquals(report.cancelled.map(_.nodeId), List("n-a"))
        assertEquals(sts, List("cancelled", "pending"))
        assertEquals(marker, List("n-a"), "non-cascading leg keeps today's pendingSuccession marker")
    }
  }

  // ── M11（取代面 / 保留面）─────────────────────────────────────────

  test("M11: the non-cancel-family pendingSuccession writers are structurally decoupled from the cascade flag") {
    val src = codeOnly(os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "NodeEngine.scala"))
    // 源码窗口提取（**有界**：从签名行起取 N 行——本文件里方法之间相隔数百行，用
    // `substring(签名A, 签名B)` 会把无关方法与新取消族一起圈进来，判据随即失真）。
    def window(sig: String, n: Int): String =
      val lines = src.linesIterator.toList
      val start = lines.indexWhere(_.contains(sig))
      assert(start >= 0, s"anchor not found in NodeEngine.scala: $sig")
      lines.slice(start, math.min(start + n, lines.size)).mkString("\n")
    // ① 保留面：反向 prune 腿与 abandon 摘边腿**零** cascade 耦合（源码级机械判据）
    val reversePrune = window("private def reversePruneReferences", 20)
    assert(!reversePrune.contains("cascade"), "reversePruneReferences must stay cascade-agnostic")
    assert(!reversePrune.contains("suppressTargets"), "reversePruneReferences must not take suppressTargets")
    val abandon = window("def detachAbandonedNode", 60)
    assert(!abandon.contains("cascade"), "detachAbandonedNode must stay cascade-agnostic")
    assert(!abandon.contains("suppressTargets"), "detachAbandonedNode must not take suppressTargets")
    assert(!abandon.contains("cascadeCancelledIds"), "detachAbandonedNode must not consult the cascade suppress set")
    // ② 取代面：取消族写点**确实**由 cascade 决定（否则取代面无从谈起）
    val detachCancelled = window("private def detachCancelledUpstream", 70)
    assert(detachCancelled.contains("suppressTargets"), "the cancel-family detach takes suppressTargets (取代面)")
    assert(detachCancelled.contains("cascadeCancelledIds"), "and consults the cross-call suppress set")
    // ③ 行为读数：abandon 摘边腿在 cascade 前后**逐字相同**
    withRig("m11a") { rig =>
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
        // 先做一次真实级联（让 cascadeCancelledIds 非空）
        _ <- rig.rt.engine.cancelNodes(List("n-a"), CancelSource.User, "prime the cascade set", cascade = true)
        d <- rig.rt.engine.detachAbandonedNode("n-z", NodeLifecycle.Cancelled)
        marker <- markerOf(rig, "n-y")
      yield
        assert(d.referrers.nonEmpty, s"abandon leg still rewrites referrers: $d")
        assertEquals(marker, List("n-z"), "abandon leg writes today's marker even after a cascade ran")
    }
  }

  // ── Z5（归档区零 diff）─────────────────────────────────────────────

  test("Z5: the cancel legs never write the archive region (state equality + source-level archive-write ban)") {
    withRig("z5") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-a", NodeLifecycle.Pending, 1000L, out = List(OutEdge("n-b"))),
            n("n-b", NodeLifecycle.Pending, 2000L, in = List("n-a"))
          )
        )
        archBefore <- rig.rt.store.archiveSnapshot
        right <- rig.rt.engine.cancelChain("chain-n-a", CancelSource.User, "z5 archive check")
        report = right.getOrElse(fail(s"chain cancel must succeed: $right"))
        archAfter <- rig.rt.store.archiveSnapshot
        archiveFileAfter <- IO.blocking(os.exists(rig.ws / ".nebflow" / "flow-map-archive.json"))
      yield
        assertEquals(report.cancelled.map(_.nodeId), List("n-a", "n-b"))
        assertEquals(archAfter, archBefore, "archive region state identical (Z5)")
        // 源码级：取消族四条腿**零** archive 写面（归档只由 sweep/TTL 与显式归档入口驱动）
        val src =
          codeOnly(os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "project" / "NodeEngine.scala"))
        def window(sig: String, n: Int): String =
          val lines = src.linesIterator.toList
          val start = lines.indexWhere(_.contains(sig))
          assert(start >= 0, s"anchor not found: $sig")
          lines.slice(start, math.min(start + n, lines.size)).mkString("\n")
        // 2026-09-24:锚点更新为 scalafmt 重排后的签名形态(签名折行;窗口语义不变)。
        val legs = window("private def cancelNodes(", 130) +
          window("private def cancelNode(", 40) +
          window("private def detachCancelledUpstream", 70)
        assert(
          !legs.contains("mutateArchive") && !legs.contains("mutateArchiveWithResult"),
          "no archive write API may appear on the cancel legs (Z5)"
        )
        assert(archiveFileAfter || true, "archive file presence is optional (only sweeps create it)")
    }
  }

  // ── V8 守卫（链解析单点；本批补的**结构判据**，设计 §6.4 V8）────────────

  test(
    "V8-guard: the gateway chainCancel leg never re-derives chain membership — the single point stays FlowMapStore.chainMembersOf"
  ) {
    val ws = codeOnly(os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "gateway" / "WebSocketRoutes.scala"))
    val lines = ws.linesIterator.toList
    val start = lines.indexWhere(_.contains("case \"chainCancel\""))
    assert(start >= 0, "the panel entry leg must exist in WebSocketRoutes.scala")
    val end = lines.indexWhere(_.contains("case \"parentRestart\""), start)
    assert(end > start, "chainCancel block must be bounded by the next case arm")
    val block = lines.slice(start, end).mkString("\n")
    // 🔴 二次派生禁令（V8）：网关只发 chainId，成员/状态/闭包全在后端单点解析
    for forbidden <- List("topologicalChains", "combinedNodes", "chainAttrsOf", "memberIds", "ChainInfo") do
      assert(
        !block.contains(forbidden),
        s"the gateway must NOT re-derive chain membership (found '$forbidden') — resolution is a single point (FlowMapStore.chainMembersOf); V8 mutation turns this red"
      )
    assert(block.contains("cancelChain"), "the leg must funnel into the engine primitive (NodeEngine.cancelChain)")
  }
end ChainCascadeSpec
