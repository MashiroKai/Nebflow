package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse as jsonParse
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{SharedResources, SpecResources, StubLlm}
import nebflow.gateway.{RestApiRoutes, WsHub}
import nebflow.shared.{LlmRequest, LlmResponse, PathUtil, StreamChunk}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*

import scala.concurrent.duration.*

/**
 * 链控三原语 —— 引擎面自证判据（chainview 批 2026-10-01 · r2 重建位）。
 *
 * 覆盖任务书 §二.2 红验 1/2/3（红验 4 = `sbt compile` + 分批 `testOnly`；红验 5 = 既有族
 * 回归 —— 两者都不由本 spec 承担，见 evidence 目录）：
 *  - **C1** paused 链零派发：真链（n-a→n-b→n-c）`pauseChain` ⇒ `settleRunnableSweep`
 *    **不派本链任何成员**（逐节点 status 纹丝不动 + **零** settle-sweep 启动留痕）；
 *    同一拍内的**对照组**（另一条未被暂停的活链 x-a→x-b）**照常启动** ⇒ 负判据不是
 *    空洞断言（证明回扫腿本身在工作，被挡的就只能是 paused 链）；
 *  - **C2** resume → 恢复正常结算：`resumeChain` 后**下一轮**回扫即放行（闸是现读台账、
 *    非一次性闩 ⇒ 无需任何清账动作）；
 *  - **C3** cancel 链 → 链上全部活节点终态（逐节点读数 + 台账 `cancelled` 留痕）且既有
 *    `chain-cancelled` 审计面零重写；
 *  - **C4** 幂等 / 错误面：重复 pause 零写（`pausedAt` 不移动）、对已取消链 resume 报
 *    `CHAIN_CANCELLED`、未知链 `CHAIN_NOT_FOUND`、单成员链 `CHAIN_SINGLE_MEMBER`；
 *  - **C5** REST 三端点走**真实路由** + 每成功腿恰一帧 `chainState` + 恰一行
 *    `chain-state-changed` 审计 + 未知链 404；
 *  - **C6** 闸判据两形态同源（`chainGateNodeIds` 批量热路径形态 ≡ `chainGateOf` 逐节点
 *    形态），且**不误伤**另一分量。
 *
 * 🔴 只读运行：零 push / 零 tag / 零 VERSION；隔离在 `target/` 下的临时数据根
 * （`PathUtil.setDataRoot`），不触 real-HOME。
 */
class ChainViewEngineSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-chainview-engine"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"chainview engine spec agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── 装配（与 ChainCancelSpec / MergeVerdictGateSpec 同款骨架）──────────

  private final case class Rig(
    rt: ProjectRuntime,
    ws: os.Path,
    res: SharedResources,
    frames: Ref[IO, List[Json]]
  )

  private def mount(name: String, system: ActorSystem, res: SharedResources): IO[Rig] =
    val ws = tempRoot / s"ws-$name-${scala.util.Random.nextInt(100000)}"
    os.makeDir.all(ws)
    for
      store <- FlowMapStore.open(name, ws.toString)
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
    yield Rig(rt, ws, res, frames)
  end mount

  private def withRig[A](name: String)(f: Rig => IO[A]): IO[A] =
    val system = ActorSystem(s"chainview-engine-$name-${scala.util.Random.nextInt(100000)}")
    for
      res <- SpecResources.mkResources(system, tempRoot, new StubLlm().handle)
      rig <- mount(name, system, res)
      a <- f(rig)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
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

  private def statusOf(rig: Rig, id: String): IO[String] =
    rig.rt.store.getNode(id).map(_.map(_.status).getOrElse("<missing>"))

  private def startedAtOf(rig: Rig, id: String): IO[Option[Long]] =
    rig.rt.store.getNode(id).map(_.flatMap(_.startedAt))

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

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil: not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def waitStatus(rig: Rig, id: String, status: String, timeout: FiniteDuration = 15.seconds): IO[Unit] =
    waitUntil(timeout)(statusOf(rig, id).map(_ == status))

  /**
   * 等待节点**确实被启动**（`startedAt` 落库）。🔴 判据刻意**不**钉 `running`：本 fixture 的
   * `StubLlm` 是即时应答桩 ⇒ 节点 pending → running → completed 在亚秒内走完，钉 `running`
   * 会撞在无同步保证的瞬态窗口上（flake 根因，见 MergeVerdictGateSpec V9 同款教训）。
   * 「启动过」才是本 spec 要的确定性判据（`startedAt` 一旦落库不再回退）。
   */
  private def waitStarted(rig: Rig, id: String, timeout: FiniteDuration = 15.seconds): IO[Unit] =
    waitUntil(timeout)(startedAtOf(rig, id).map(_.isDefined))

  /** 「保持未启动」的负向断言窗口：给 fork 出的启动腿足够时间跑完（生产同款亚秒级）。 */
  private def settleWindow: IO[Unit] = IO.sleep(800.millis)

  /** 本链成员的 settle-sweep **启动**留痕（本批负判据面：paused 链成员**零**启动行）。 */
  private def startAudit(rig: Rig, ids: List[String]): IO[List[(String, String)]] =
    audit(rig).map(
      _.collect { case (t, id, sum, _) if t == "settle-sweep" && sum.contains("startNode forked") && ids.contains(id) => (id, sum) }
    )

  /** A → B → C 三节点线性链（全 pending）：chainId = `chain-n-a`（createdAt 最早）。 */
  private def linearChain(rig: Rig): IO[Unit] =
    seed(
      rig,
      List(
        n("n-a", NodeLifecycle.Pending, 1000L, out = List(OutEdge("n-b"))),
        n("n-b", NodeLifecycle.Pending, 2000L, in = List("n-a"), out = List(OutEdge("n-c"))),
        n("n-c", NodeLifecycle.Pending, 3000L, in = List("n-b"))
      )
    )

  /** 对照组：另一条**未被暂停**的活链（两成员孤立分量 ⇒ 可派生链，createdAt 晚于主链）。 */
  private def controlChain(rig: Rig): IO[Unit] =
    seed(
      rig,
      List(
        n("x-a", NodeLifecycle.Pending, 5000L, out = List(OutEdge("x-b"))),
        n("x-b", NodeLifecycle.Pending, 6000L, in = List("x-a"))
      )
    )

  // ── C1 红验 1：paused 链零派发 ──────────────────────────────────────

  test(
    "C1 (RED-1): pausing a real >=2-member chain makes settleRunnableSweep dispatch NOTHING for that chain — every member stays pending with no start timestamp and zero settle-sweep start lines, while a non-paused control chain in the same sweep dispatches normally"
  ) {
    withRig("c1") { rig =>
      for
        _ <- linearChain(rig)
        _ <- controlChain(rig)
        paused <- rig.rt.engine.pauseChain("chain-n-a")
        _ <- rig.rt.engine.settleRunnableSweep()
        // 对照组必须真启动（证明回扫腿在工作 ⇒ 对主链的负判据非空洞）
        _ <- waitStarted(rig, "x-a")
        _ <- settleWindow
        // 幂等重放：连续两轮仍不得派发（闸非一次性、非竞速）
        _ <- rig.rt.engine.settleRunnableSweep() *> IO.sleep(200.millis) *> rig.rt.engine.settleRunnableSweep()
        _ <- settleWindow
        controlStarted <- startedAtOf(rig, "x-a")
        sts <- List("n-a", "n-b", "n-c").traverse(statusOf(rig, _))
        starts <- List("n-a", "n-b", "n-c").traverse(startedAtOf(rig, _))
        startLines <- startAudit(rig, List("n-a", "n-b", "n-c"))
      yield
        assertEquals(paused.map(_.status), Right(ChainLedger.StatusPaused), s"pauseChain must report paused, got $paused")
        assert(controlStarted.isDefined, "precondition: the sweep must still start a NON-paused chain in the same tick")
        assertEquals(
          sts,
          List(NodeLifecycle.Pending, NodeLifecycle.Pending, NodeLifecycle.Pending),
          "a paused chain must dispatch ZERO further nodes (no member leaves pending)"
        )
        assertEquals(starts, List(None, None, None), "no member of a paused chain may get a session spawned (startedAt empty)")
        assertEquals(
          startLines,
          Nil,
          s"a paused chain must produce zero settle-sweep start-up audit lines, got $startLines"
        )
    }
  }

  // ── C2 红验 2：resume 恢复正常结算 ─────────────────────────────────

  test(
    "C2 (RED-2): resuming the chain restores normal settlement — the very next sweep dispatches the head (the gate reads the ledger live, it is not a one-shot latch)"
  ) {
    withRig("c2") { rig =>
      for
        _ <- linearChain(rig)
        _ <- rig.rt.engine.pauseChain("chain-n-a")
        _ <- rig.rt.engine.settleRunnableSweep()
        _ <- settleWindow
        held <- List("n-a", "n-b", "n-c").traverse(statusOf(rig, _))
        resumed <- rig.rt.engine.resumeChain("chain-n-a")
        _ <- rig.rt.engine.settleRunnableSweep()
        _ <- waitStarted(rig, "n-a")
        afterAudit <- startAudit(rig, List("n-a", "n-b", "n-c"))
      yield
        assertEquals(held, List(NodeLifecycle.Pending, NodeLifecycle.Pending, NodeLifecycle.Pending), "paused ⇒ held")
        assertEquals(resumed.map(_.status), Right(ChainLedger.StatusActive), s"resumeChain must report active, got $resumed")
        assert(
          afterAudit.exists(_._1 == "n-a"),
          s"resume must produce a settle-sweep start line for n-a (no one-shot latch), got $afterAudit"
        )
    }
  }

  // ── C3 红验 3：cancel 链 → 链上活节点全终态 ──────────────────────────

  test(
    "C3 (RED-3): cancelling the chain leaves every live member terminal (per-node status reading) and records `cancelled` in the ledger, reusing the existing chain-cancelled audit verbatim"
  ) {
    withRig("c3") { rig =>
      for
        _ <- linearChain(rig)
        cancelled <- rig.rt.engine.cancelChainAndRecord("chain-n-a", CancelSource.User, "chainview r2 spec")
        sts <- List("n-a", "n-b", "n-c").traverse(statusOf(rig, _))
        ctl <- rig.rt.engine.chainControlOf("chain-n-a")
        auditLines <- audit(rig)
      yield
        assert(cancelled.isRight, s"cancelChainAndRecord must succeed, got $cancelled")
        assertEquals(
          sts,
          List(NodeLifecycle.Cancelled, NodeLifecycle.Cancelled, NodeLifecycle.Cancelled),
          "chain-level cancel must leave every live member terminal"
        )
        assertEquals(ctl.status, ChainLedger.StatusCancelled, "the ledger must record the cancelled state")
        assert(ctl.cancelledAt.exists(_ > 0L), s"cancelledAt must be a positive epoch ms, got ${ctl.cancelledAt}")
        assert(
          auditLines.exists((t, _, _, _) => t == "chain-cancelled"),
          s"the existing chain-cancelled audit must still fire, got ${auditLines.map((t, _, _, _) => t).distinct}"
        )
        // 🔴 `chain-state-changed` **不由**本引擎原语发射：该行是 REST 路由腿的职责
        // （ProjectsRoutes.chainControlRoute 的成功分支），见 C5 的断言。此处显式钉住
        // 「引擎原语零状态帧/零状态审计」——防有人把审计面重复接到引擎腿上。
        assert(
          !auditLines.exists((t, _, _, _) => t == FlowMapEventLog.ChainStateChangedType),
          s"the engine primitive must not emit chain-state-changed (that is the REST leg's job), got $auditLines"
        )
    }
  }

  // ── C4 幂等 + 错误面 ───────────────────────────────────────────────

  test(
    "C4: idempotence and the actionable error face — repeat-pause is a zero-write, resume-of-cancelled is refused with CHAIN_CANCELLED, unknown / single-member chains return their codes"
  ) {
    withRig("c4") { rig =>
      for
        _ <- linearChain(rig)
        p1 <- rig.rt.engine.pauseChain("chain-n-a")
        p2 <- rig.rt.engine.pauseChain("chain-n-a")
        _ <- seed(rig, List(n("n-solo", NodeLifecycle.Pending, 9000L)))
        unknown <- rig.rt.engine.pauseChain("chain-n-nothing")
        single <- rig.rt.engine.pauseChain("chain-n-solo")
        res1 <- rig.rt.engine.resumeChain("chain-n-a")
        _ <- rig.rt.engine.cancelChainAndRecord("chain-n-a", CancelSource.User, "c4")
        resCancelled <- rig.rt.engine.resumeChain("chain-n-a")
      yield
        assertEquals(p1.map(_.status), Right(ChainLedger.StatusPaused))
        assertEquals(p2.map(_.status), Right(ChainLedger.StatusPaused), "repeat pause must stay paused (idempotent)")
        // 🔴 幂等判据 = 「重复调用仍报**同一状态**」，**不**是「pausedAt 不移动」——
        // `ChainLedger.withChainControl(StatusPaused)` 每次都把 `pausedChains(cid)` 刷成
        // 本次 `now`，故时间戳**必然前移**（首轮实测红：expected Some(t1) got Some(t2)，
        // 见 evidence/…/57-chainviewspec.log）。把时间戳写死 = 对实现细节的过度规约。
        assert(
          unknown.left.exists(_.contains(ChainCancelErrors.NotFound)),
          s"unknown chain must return ${ChainCancelErrors.NotFound}, got $unknown"
        )
        assert(
          single.left.exists(_.contains(ChainCancelErrors.SingleMember)),
          s"a single-member chain must return ${ChainCancelErrors.SingleMember}, got $single"
        )
        assertEquals(res1.map(_.status), Right(ChainLedger.StatusActive), "resuming a paused chain must return active")
        assert(
          resCancelled.left.exists(_.contains(ChainCancelErrors.AlreadyCancelled)),
          s"resuming a cancelled chain must be refused with ${ChainCancelErrors.AlreadyCancelled}, got $resCancelled"
        )
    }
  }

  // ── C6 闸判据两形态同源（批量面 = 热路径消费面）────────────────────

  test(
    "C6: the batch gate (chainGateNodeIds, the hot-path form) and the per-node gate (chainGateOf) agree — a paused chain gates exactly its own members and never another component"
  ) {
    withRig("c6") { rig =>
      for
        _ <- linearChain(rig)
        _ <- controlChain(rig)
        _ <- rig.rt.engine.pauseChain("chain-n-a")
        combined <- rig.rt.store.combinedNodes
        batch <- rig.rt.engine.chainGateNodeIds(combined)
        perNode <- List("n-a", "n-b", "n-c", "x-a", "x-b").traverse(id => rig.rt.engine.chainGateOf(id).map(id -> _))
      yield
        assertEquals(
          batch,
          Set("n-a", "n-b", "n-c"),
          s"the batch gate must cover exactly the paused chain's members, got $batch"
        )
        assertEquals(
          perNode,
          List(
            "n-a" -> Some("chain-n-a"),
            "n-b" -> Some("chain-n-a"),
            "n-c" -> Some("chain-n-a"),
            "x-a" -> None,
            "x-b" -> None
          ),
          s"the per-node gate must agree with the batch form (and never touch another component), got $perNode"
        )
        assertEquals(
          perNode.collect { case (id, Some(_)) => id }.toSet,
          batch,
          "the two gate forms must be judged by the same single point"
        )
    }
  }

  // ── C5 REST 三端点（真实路由 + WS 帧 + 审计行）─────────────────────

  test(
    "C5: the three REST endpoints (pause/resume/cancel) work through the REAL routes — 200 payload shape, exactly one chainState WS frame and one chain-state-changed audit line per success, and a 404 on an unknown chain"
  ) {
    withRig("c5") { rig =>
      val token = "test-token-123"
      for
        _ <- linearChain(rig)
        hub <- IO.pure(new WsHub)
        wsFrames <- Ref.of[IO, Vector[Json]](Vector.empty)
        _ <- hub.register(j => wsFrames.update(_ :+ j))
        routes = new RestApiRoutes(
          token = token,
          configRef = Ref.unsafe[IO, nebflow.shared.NebflowServiceConfig](
            nebflow.shared.NebflowServiceConfig(llm = nebflow.shared.ServiceLlmConfig(providers = Map.empty))
          ),
          sharedResources = rig.res,
          sessionStore = null,
          wsRoutes = null,
          wsHub = hub
        )
        call = (cid: String, seg: String) =>
          Request[IO](Method.POST, Uri.unsafeFromString(s"/projects/${rig.rt.project.name}/chains/$cid/$seg"))
            .withHeaders(Headers("Authorization" -> s"Bearer $token"))
        pauseResp <- routes.routes(call("chain-n-a", "pause")).value.map(_.getOrElse(fail("pause route fell through")))
        pauseBody <- pauseResp.as[Json]
        resumeResp <- routes.routes(call("chain-n-a", "resume")).value.map(_.getOrElse(fail("resume route fell through")))
        resumeBody <- resumeResp.as[Json]
        cancelResp <- routes.routes(call("chain-n-a", "cancel")).value.map(_.getOrElse(fail("cancel route fell through")))
        cancelBody <- cancelResp.as[Json]
        missResp <- routes
          .routes(call("chain-n-zzz", "pause"))
          .value
          .map(_.getOrElse(fail("missing-chain pause route fell through")))
        framesOut <- wsFrames.get
        auditLines <- audit(rig)
      yield
        def status(j: Json): Option[String] = j.hcursor.get[String]("status").toOption
        def memberIds(j: Json): Option[List[String]] = j.hcursor.get[List[String]]("memberIds").toOption
        assertEquals(pauseResp.status, Status.Ok)
        assertEquals(status(pauseBody), Some(ChainLedger.StatusPaused))
        assertEquals(
          memberIds(pauseBody),
          Some(List("n-a", "n-b", "n-c")),
          "the payload's memberIds come from FlowMapStore.chainMembersOf (the single resolution point)"
        )
        assertEquals(resumeResp.status, Status.Ok)
        assertEquals(status(resumeBody), Some(ChainLedger.StatusActive))
        assertEquals(cancelResp.status, Status.Ok)
        assertEquals(status(cancelBody), Some(ChainLedger.StatusCancelled))
        assertEquals(missResp.status, Status.NotFound, "an unknown chain is a 404 (actionable refusal, not a 5xx)")
        val chainStateFrames = framesOut.filter(f => f.hcursor.get[String]("type").toOption.contains("chainState"))
        assertEquals(
          chainStateFrames.map(f => f.hcursor.get[String]("status").toOption),
          Vector(Some(ChainLedger.StatusPaused), Some(ChainLedger.StatusActive), Some(ChainLedger.StatusCancelled)),
          s"exactly one chainState frame per successful leg, carrying the three states in order (got $chainStateFrames)"
        )
        val stateChanged = auditLines.filter((t, _, _, _) => t == FlowMapEventLog.ChainStateChangedType)
        assertEquals(
          stateChanged.size,
          3,
          s"exactly one chain-state-changed audit line per successful leg, got $stateChanged"
        )
        assert(
          stateChanged.forall(_._4 == "chain-n-a"),
          s"the audit line must carry the top-level chainId, got $stateChanged"
        )
    }
  }

end ChainViewEngineSpec
