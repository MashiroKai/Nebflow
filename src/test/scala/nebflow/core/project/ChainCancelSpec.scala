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
 * 链级取消（R2）——chaincancel 批 2026-09-17 自证判据 C 类。
 *
 * 覆盖（逐条对应批任务书的判据编号）：
 *  - C1 `cancelled` 审计事件数 == N（无多余）
 *  - C2 `trigger` 注入次数 == 1（N=1 与 N=20 同值；N≥5 风暴算术结构性消失）
 *  - C3 全成员 `notifySentAt.isDefined` 且 `redeliver` cancelled 候选 == 0
 *  - C4 `budgetUsedFor(Cancelled)` 增量 == 1 且窗口/cooldown 零变化
 *  - C5 `chain-cancelled` 审计事件 == 1 条（含 chainId/成员清单/source/reason）
 *  - C6 `nodeUpdated` 帧数 == N + |prunedReferrers|；第二次调用 == 0（幂等）
 *  - C7 `cancelled ⊎ preserved ⊎ skipped` == 全部 memberIds（无交叠无遗漏）
 *  - M10（本 spec 覆盖 shell 面）blocked / interrupted 均纳入取消集，且取消集
 *    **不**用 `Terminal` 谓词（`Terminal` 含 blocked ⇒ 若误用会漏 blocked）
 *  - M12 单成员链 / 查无 ⇒ **可行动错误码**，无降级为单节点取消
 *  - 链解析单点：`FlowMapStore.chainMembersOf` 与载荷口径同源（分量 = 弱连通分量）
 */
class ChainCancelSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chain-cancel"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"chain-cancel spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 装配（与 CancelDeadlockFixSpec 同款骨架）──────────────────────────

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

  /** 挂载真实 NodeEngine（`notifyTriggerOverride` = 注入捕获接缝，窗内不模拟时序）。 */
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
        // 本 spec 主题非 node_report 语义（与 CancelDeadlockFixSpec 同款显式关腿）
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

  /** 起一条真实 NodeEngine（不带项目注册语义的轻装配）。 */
  private def withRig[A](name: String)(f: Rig => IO[A]): IO[A] =
    val system = ActorSystem(s"chain-cancel-$name-${scala.util.Random.nextInt(100000)}")
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
      result = result,
      createdAt = createdAt
    )

  private def seed(rig: Rig, nodes: List[NodeDef]): IO[Unit] =
    rig.rt.store.mutate(s => s.copy(nodes = s.nodes ++ nodes.map(x => x.id -> x).toMap)).void

  private def audit(rig: Rig): IO[List[(String, String, String, String)]] =
    IO.blocking(os.read(rig.ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(
        _.flatMap(l =>
          jsonParse(l).toOption.map(j =>
            (
              j.hcursor.get[String]("type").getOrElse(""),
              j.hcursor.get[String]("nodeId").getOrElse(""),
              j.hcursor.get[String]("summary").getOrElse(""),
              j.hcursor.get[String]("chainId").getOrElse("")
            )
          )
        )
      )
      .handleError(_ => Nil)

  private def framesOfType(rig: Rig, typ: String): IO[Int] =
    rig.frames.get.map(_.count(_.hcursor.get[String]("type").toOption.contains(typ)))

  private def statusOf(rig: Rig, id: String): IO[String] =
    rig.rt.store.getNode(id).map(_.map(_.status).getOrElse("<missing>"))

  /** A → B → C 三节点线性链（全部 pending）：chainId = `chain-n-a`（createdAt 最早）。 */
  private def linearChain(rig: Rig): IO[Unit] =
    seed(
      rig,
      List(
        n("n-a", NodeLifecycle.Pending, 1000L, out = List(OutEdge("n-b"))),
        n("n-b", NodeLifecycle.Pending, 2000L, in = List("n-a"), out = List(OutEdge("n-c"))),
        n("n-c", NodeLifecycle.Pending, 3000L, in = List("n-b"))
      )
    )

  // ── 链解析单点 ────────────────────────────────────────────────────

  test(
    "chainMembersOf: resolves the weakly-connected component of the merged set, id = chain-<earliest createdAt member>, members ascending by createdAt"
  ) {
    withRig("resolve") { rig =>
      for
        _ <- linearChain(rig)
        cm <- rig.rt.store.chainMembersOf("chain-n-a")
        members = cm.map(_.info.memberIds).getOrElse(Nil)
        miss <- rig.rt.store.chainMembersOf("chain-n-zzz")
      yield
        assertEquals(members, List("n-a", "n-b", "n-c"))
        assertEquals(cm.map(_.title).exists(_.nonEmpty), true)
        assertEquals(miss, None)
    }
  }

  // ── C1 / C3 / C4 / C5 / C6 / C7 ──────────────────────────────────

  test(
    "C1+C3+C4+C5+C6+C7: chain cancel of A→B→C writes 3 cancelled + 1 chain-cancelled audit, ONE injection, budget +1 with window/cooldown untouched, all members notifySentAt set, pruned referrers accounted, partition exact"
  ) {
    withRig("c-main") { rig =>
      val chainId = "chain-n-a"
      for
        _ <- linearChain(rig)
        ledgerBefore <- rig.rt.engine.dispatchNotify.cancelledLedger
        right <- rig.rt.engine.cancelChain(chainId, CancelSource.User, "user wants the track gone")
        report = right.getOrElse(fail(s"cancelChain must succeed: $right"))
        rows <- audit(rig)
        cancelledEvents = rows.count(_._1 == "cancelled")
        chainEvents = rows.filter(_._1 == "chain-cancelled")
        injected <- rig.triggered.get
        ledgerAfter <- rig.rt.engine.dispatchNotify.cancelledLedger
        markers <- List("n-a", "n-b", "n-c").traverse(id =>
          rig.rt.store.getNode(id).map(_.exists(_.notifySentAt.isDefined))
        )
        redelivered <- rig.rt.engine.dispatchNotify.redeliver()
        updatedFrames <- framesOfType(rig, "nodeUpdated")
        // 幂等第二次（走**节点级**原语、显式种子集）：链级原语在取消后已解析不到原
        // chainId（R4 摘除把 out 改接 Nebula 且 in 镜像被 prune ⇒ 原分量碎裂成单成员
        // 分量）——这是本批实测到的语义，见下方 `chainAfter` 读数与报告 ⑧。
        second <- rig.rt.engine.cancelNodes(List("n-a", "n-b", "n-c"), CancelSource.User, "again", cascade = true)
        secondReport = second
        framesAfterSecond <- framesOfType(rig, "nodeUpdated")
        auditAfterSecond <- audit(rig)
        chainAfter <- rig.rt.store.chainMembersOf(chainId)
        chainAfterAgain <- rig.rt.engine.cancelChain(chainId, CancelSource.User, "again")
        _ <- IO {
          // C1
          assertEquals(cancelledEvents, 3, "exactly N cancelled audit events")
          // C5
          assertEquals(chainEvents.size, 1, "exactly one chain-cancelled audit event")
          assert(chainEvents.head._3.contains(s"chain=$chainId"), chainEvents.head._3)
          assert(chainEvents.head._3.contains("cancelled=3"), chainEvents.head._3)
          assert(chainEvents.head._3.contains("source=user"), chainEvents.head._3)
          assert(chainEvents.head._3.contains("reason="), chainEvents.head._3)
          assertEquals(chainEvents.head._4, chainId, "top-level chainId carries the chain (C5)")
          assert(chainEvents.head._3.contains("members=n-a,n-b,n-c"), chainEvents.head._3)
          // C2（单操作恰一次注入）
          assertEquals(injected.size, 1, "one chain-level injection regardless of N")
          assert(injected.head.contains(chainId), injected.head)
          assert(injected.head.contains("不可重激活"), "cancelled-only guidance, never the failed recipe")
          // C4：预算 +1，窗口/cooldown 零变化
          assertEquals(ledgerAfter._1 - ledgerBefore._1, 1, "one budget unit for the whole chain")
          assertEquals(ledgerAfter._2, ledgerBefore._2, "cancelled window timestamps untouched")
          assertEquals(ledgerAfter._3, ledgerBefore._3, "cancelled cooldown untouched")
          // C3
          assertEquals(markers, List(true, true, true), "every member marked notified")
          assertEquals(redelivered, 0, "no redeliver candidate left (all members marked)")
          // C6：帧数 == N + |prunedReferrers|（A→B→C 线性链的摘除面 = {n-b, n-c}）
          assertEquals(report.prunedReferrers, List("n-b", "n-c"))
          assertEquals(updatedFrames, 3 + report.prunedReferrers.size)
          // C7：分区不变量
          assertEquals(report.cancelled.map(_.nodeId), List("n-a", "n-b", "n-c"))
          assertEquals(report.preserved, Nil)
          assertEquals(report.skipped, Nil)
          val partition = (report.cancelled ++ report.preserved ++ report.skipped).map(_.nodeId).sorted
          assertEquals(partition, List("n-a", "n-b", "n-c"))
          // 幂等：第二次零注入、零帧、零新审计
          assertEquals(secondReport.cancelled, Nil)
          assertEquals(secondReport.injected, 0)
          assertEquals(secondReport.notified, false)
          assertEquals(framesAfterSecond, updatedFrames, "idempotent second call emits zero nodeUpdated frames")
          assertEquals(auditAfterSecond.count(_._1 == "chain-cancelled"), 1, "no second chain-cancelled event")
          assertEquals(auditAfterSecond.count(_._1 == "cancelled"), 3, "no extra cancelled events")
          // 取消后链 id 已碎裂（R4 摘除的既有语义，非本批引入）：原 chainId 解析成单成员
          // 分量 ⇒ 链级原语按 M12 回 CHAIN_SINGLE_MEMBER（绝不静默降级为单节点取消）。
          assertEquals(
            chainAfter.map(_.info.memberIds),
            Some(List("n-a")),
            "the cancel detach dissolves the chain (out→Nebula + in-mirror pruned) — chain ids are derived, not stored"
          )
          assert(chainAfterAgain.left.exists(_.startsWith(ChainCancelErrors.SingleMember)), chainAfterAgain.toString)
        }
        // 终态 + 取消来源文本（① 节点 result 文本承载链级信息）
        st <- List("n-a", "n-b", "n-c").traverse(statusOf(rig, _))
        results <- List("n-a", "n-b", "n-c").traverse(id => rig.rt.store.getNode(id).map(_.flatMap(_.result)))
      yield
        assertEquals(st, List("cancelled", "cancelled", "cancelled"))
        results.foreach { r =>
          assert(r.exists(_.contains(s"chain-cancel chain=$chainId")), r.toString)
          assert(r.exists(_.contains("cancelled[source=user]")), r.toString)
          // 面板/网关腿**省略** `cascade` ⇒ 本断言钉住 cancelChain 的默认值（V1 变异点：
          // 默认翻面 ⇒ 本行必红）
          assert(r.exists(_.contains("cascade=true")), r.toString)
        }
      end for
    }
  }

  test("C2: injection count is 1 for N=1 and for N=20 alike (the N>=5 storm arithmetic is structurally gone)") {
    withRig("c-count") { rig =>
      // createdAt 严格最小 ⇒ chainId 无平局（= `chain-n-root`；id 派生自分量内
      // createdAt 最早的成员，平局会换 id——见链解析单点）。
      val fan = (0 until 20).map(i => n(f"n-f$i%02d", NodeLifecycle.Pending, 1000L + i, in = List("n-root"))).toList
      for
        _ <- seed(rig, n("n-root", NodeLifecycle.Pending, 100L, out = fan.map(e => OutEdge(e.id))) :: fan)
        r1 <- rig.rt.engine.cancelChain("chain-n-root", CancelSource.User, "20-node fan")
        big = r1.getOrElse(fail(s"fan cancel failed: $r1"))
        t1 <- rig.triggered.get
        _ <- seed(
          rig,
          List(n("n-solo", NodeLifecycle.Pending, 100L), n("n-solo2", NodeLifecycle.Pending, 200L, in = List("n-solo")))
        )
        r2 <- rig.rt.engine.cancelChain("chain-n-solo", CancelSource.User, "2-node chain")
        small = r2.getOrElse(fail(s"small cancel failed: $r2"))
        t2 <- rig.triggered.get
      yield
        assertEquals(big.cancelled.size, 21, "root + 20 fan members all cancelled")
        assertEquals(small.cancelled.size, 2)
        assertEquals(t1.size, 1, "N=21 ⇒ exactly one injection")
        assertEquals(t2.size - t1.size, 1, "N=2 ⇒ exactly one more injection")
      end for
    }
  }

  // ── M10：blocked / interrupted 均纳入；取消集不依赖 Terminal 谓词 ──

  test(
    "M10: cancelled set explicitly enumerates running/pending/wiring/blocked/interrupted and never uses the Terminal predicate (which contains blocked)"
  ) {
    withRig("m10") { rig =>
      for
        _ <- seed(
          rig,
          List(
            n("n-run", NodeLifecycle.Running, 1000L, out = List(OutEdge("n-pend"))), // stale running（无在飞 fiber）
            n("n-pend", NodeLifecycle.Pending, 2000L, in = List("n-run"), out = List(OutEdge("n-wire"))),
            n("n-wire", NodeLifecycle.Wiring, 3000L, in = List("n-pend"), out = List(OutEdge("n-blocked"))),
            n("n-blocked", NodeLifecycle.Blocked, 4000L, in = List("n-wire"), out = List(OutEdge("n-intr"))),
            n("n-intr", NodeLifecycle.Interrupted, 5000L, in = List("n-blocked"), out = List(OutEdge("n-done"))),
            n("n-done", NodeLifecycle.Completed, 6000L, in = List("n-intr"), result = Some("kept")),
            n("n-fail", NodeLifecycle.Failed, 7000L, out = List(OutEdge("n-done"), OutEdge("n-cancel"))),
            n("n-cancel", NodeLifecycle.Cancelled, 8000L, in = List("n-fail"), result = Some("already gone"))
          )
        )
        // 取消集判据的机械负例：Terminal 谓词含 blocked ⇒ 不可用作取消集判据
        _ <- IO {
          assert(
            NodeLifecycle.Terminal.contains(NodeLifecycle.Blocked),
            "Terminal contains blocked — hence unusable as the cancel-set judge"
          )
          assert(
            NodeLifecycle.ChainCancelScope.contains(NodeLifecycle.Blocked),
            "blocked IS in the explicit cancel scope (ChainCancelScope)"
          )
        }
        right <- rig.rt.engine.cancelChain("chain-n-run", CancelSource.User, "whole track gone")
        report = right.getOrElse(fail(s"chain cancel failed: $right"))
        sts <- List("n-run", "n-pend", "n-wire", "n-blocked", "n-intr", "n-done", "n-fail", "n-cancel")
          .traverse(id => statusOf(rig, id))
        done <- rig.rt.store.getNode("n-done").map(_.flatMap(_.result))
        failed <- rig.rt.store.getNode("n-fail").map(_.flatMap(_.result))
        cancelledAlready <- rig.rt.store.getNode("n-cancel").map(_.flatMap(_.result))
      yield
        assertEquals(report.cancelled.map(_.nodeId).sorted, List("n-blocked", "n-intr", "n-pend", "n-run", "n-wire"))
        assert(report.cancelled.forall(_.why == "cancelled"))
        assertEquals(report.preserved.map(_.nodeId).sorted, List("n-cancel", "n-done", "n-fail"))
        assert(
          report.preserved.forall(_.why == "terminal"),
          s"all three preserved members are terminal: ${report.preserved}"
        )
        assertEquals(report.skipped, Nil)
        // 零触碰：终态节点状态与结果逐字保留
        assertEquals(
          sts,
          List("cancelled", "cancelled", "cancelled", "cancelled", "cancelled", "completed", "failed", "cancelled")
        )
        assertEquals(done, Some("kept"))
        assertEquals(failed, None)
        assertEquals(cancelledAlready, Some("already gone"))
        // blocked / interrupted 成员确实被取消（M10 的正向要求）
        val picked = report.cancelled.map(e => e.nodeId -> e.status).toMap
        assertEquals(picked.get("n-blocked"), Some(NodeLifecycle.Blocked))
        assertEquals(picked.get("n-intr"), Some(NodeLifecycle.Interrupted))
    }
  }

  // ── M12：单成员链 / 查无 ⇒ 可行动错误，无降级 ─────────────────────

  test(
    "M12: single-member chain and unknown chainId return actionable error codes and NEVER degrade into a single-node cancel"
  ) {
    withRig("m12") { rig =>
      for
        _ <- seed(rig, List(n("n-lonely", NodeLifecycle.Pending, 1000L)))
        notFound <- rig.rt.engine.cancelChain("chain-n-nothing", CancelSource.User, "x")
        single <- rig.rt.engine.cancelChain("chain-n-lonely", CancelSource.User, "x")
        lonelyStatus <- statusOf(rig, "n-lonely")
        injected <- rig.triggered.get
      yield
        assert(notFound.left.exists(_.startsWith(ChainCancelErrors.NotFound)), notFound.toString)
        assert(single.left.exists(_.startsWith(ChainCancelErrors.SingleMember)), single.toString)
        assertEquals(lonelyStatus, NodeLifecycle.Pending, "no silent degradation into a single-node cancel")
        assertEquals(injected, Nil, "no dispatcher injection on a refused chain cancel")
    }
  }
end ChainCancelSpec
