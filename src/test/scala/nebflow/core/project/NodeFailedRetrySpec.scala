package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, InteractionHub, InteractionHubCommand, InteractionKind, InteractionReply, InteractionRequest, SharedResources}

import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 批E2 · P2 failed 回跳 retry 端到端回归（spec 20260908_semantic-gating-askuser
 * §2.3 + §3.4 CleanupForSession，验收①-⑤；NodeEdgeGatingSpec 同款基建）：
 *
 *  - ① retry 单跳生效端到端：B failed（gen 0<max）→ 引擎自动重激活 B（gen+1）+
 *    重跑 retry.upstream → 上游经 pass 边重投 → B 新代次跑通；payload 暴露
 *    retry/gen 字段（attempt N 徽标数据载体，渲染归 F3）；
 *  - ② cap 耗尽：gen 达 max 后再 failed → 终态 failed + FeedbackRouter
 *    RetryCap 升级（FlowMapEventLog "escalated" reason=RetryCap 留痕触达）；
 *  - ③ 已终态的其他消费者不被重跑意外重触发（deliverOut 幂等记账 + startNode
 *    终态幂等跳过——与今天人工重激活语义完全一致）；
 *  - ④ 风暴防护两校验：NODE_RETRY_NEIGHBOR（upstream 必须 in/deps 邻居）与
 *    NODE_RETRY_CYCLE（retry 链成环拒绝）各一断言（+NODE_RETRY_MAX_RANGE）；
 *  - ⑤ 节点 cancelled 级联清理 pending asks：hub CleanupForSession 移槽 +
 *    askUserClosed{requestId} 广播（前端关卡处理归 F2，验收到引擎广播为止）。
 */
class NodeFailedRetrySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 180.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-failed-retry"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  /** 脚本化 LLM 桩：按（marker, 该 marker 第 N 次出现）决策回复/失败——同 marker
    * 重复出现 = 重跑代次（重跑输入因上游新结果而文本不同，按全文计数会失序）。 */
  private class RetryLlm(
      markers: List[String],
      script: (String, Int) => Either[String, String]
  ) extends LlmHandle[IO]:
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    private val seen: Ref[IO, Map[String, Int]] = Ref.unsafe[IO, Map[String, Int]](Map.empty)
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      val text = req.messages.map(_.textContent).mkString("\n")
      Stream.eval {
        inputs.update(_ :+ text) *>
          seen.modify { m =>
            val marker = markers.find(text.contains).getOrElse(text)
            val n = m.getOrElse(marker, 0) + 1
            (m.updated(marker, n), script(marker, n))
          }
      }.flatMap {
        case Right(reply) => Stream(StreamChunk.TextDelta(reply), StreamChunk.Done(None, None))
        case Left(err)    => Stream.raiseError[IO](new RuntimeException(err))
      }

  /** 常驻延迟 LLM（⑤ cancel 用）：任务卡在飞行中，等取消信号。 */
  private class HangingLlm(delay: FiniteDuration) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(req: LlmRequest, onAttempt: Option[FallbackAttempt => IO[Unit]] = None): Stream[IO, StreamChunk] =
      Stream.eval(IO.sleep(delay)) >> Stream(
        StreamChunk.TextDelta("late"), StreamChunk.Done(None, None))

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

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("retry-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
      cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  private def mountProject(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
  ): IO[ProjectRuntime] =
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
        emitEvent = (_, _, _) => IO.unit
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield rt

  private def idOf(rt: ProjectRuntime, name: String): IO[String] =
    rt.store.snapshot.map(_.nodes.values.find(_.name == name)).map {
      case Some(n) => n.id
      case None    => fail(s"node '$name' must exist")
    }

  private def nodeById(rt: ProjectRuntime, id: String): IO[Option[NodeDef]] =
    rt.store.snapshot.map(_.nodes.get(id))

  private def waitStatus(rt: ProjectRuntime, name: String, statuses: Set[String]): IO[Unit] =
    waitUntil(20.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / FlowMapEventLog.FileName
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── ① retry 单跳生效端到端（B failed→A 重跑→B 新代次）────────

  test("① single-hop retry: B fails → auto-reactivate (gen+1) → upstream re-runs → B completes on the fresh delivery") {
    val ws = tempRoot / "ws-retry-ok"
    os.makeDir.all(ws)
    val system = ActorSystem(s"retry-ok-${scala.util.Random.nextInt(100000)}")
    // A：每次跑回复 a-result-run-<n>；B：第 1 次跑失败，第 2 次跑成功。
    val llm = new RetryLlm(List("seed-retry-a", "seed-retry-b"), {
      case ("seed-retry-a", n) => Right(s"a-result-run-$n")
      case ("seed-retry-b", 1) => Left("boom-attempt-1")
      case ("seed-retry-b", _) => Right("b-ok-now")
      case (_, _)              => Right("ok")
    })
    for
      res <- mkResources(system, tempRoot, llm)
      rt <- mountProject("retry-ok", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // A 入口（延迟 600ms 跑），B 随后建（in=[A] + retry 策略，创建期校验走 in 邻居）
      _ <- nodeEdit(nodeInput("retry-ok", "retry-a", "description" -> Json.fromString("retry upstream"),
        "task" -> Json.fromString("seed-retry-a"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- idOf(rt, "retry-a")
      created <- nodeEdit(nodeInput("retry-ok", "retry-b", "description" -> Json.fromString("retry holder"),
        "task" -> Json.fromString("seed-retry-b consumes upstream"),
        "in" -> Json.fromString(aId), "out" -> Json.fromString("Nebula"),
        "retry" -> Json.obj("upstream" -> Json.fromString(aId), "max" -> Json.fromInt(1))), ctx)
      _ <- assertIO_(IO(assert(created.isRight, s"B with retry must be created, got: $created")))
      bId <- idOf(rt, "retry-b")
      // 端态等待（带诊断信息；不设中间窗口等待——running/completed 的中间态窗口
      // 在重跑竞速下不可靠，唯一权威终态 = B 完成 + gen==1）
      _ <- waitUntil(45.seconds) {
        rt.store.snapshot.map(_.nodes.get(bId).exists(b =>
          b.status == NodeLifecycle.Completed && b.gen == 1))
      }.adaptError { case e => new AssertionError(s"[① final state B completed+gen1] ${e.getMessage}") }
      _ <- waitUntil(45.seconds) {
        rt.store.snapshot.map(_.nodes.get(aId).exists(_.status == NodeLifecycle.Completed))
      }.adaptError { case e => new AssertionError(s"[① A re-completed after rerun] ${e.getMessage}") }
      bAfter <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      aAfter <- nodeById(rt, aId).map(_.getOrElse(fail("A must exist")))
      aRuns <- llm.inputs.get.map(_.count(_.contains("seed-retry-a")))
      bRuns <- llm.inputs.get.map(_.count(_.contains("seed-retry-b")))
      bSecondInput <- llm.inputs.get.map(_.filter(_.contains("seed-retry-b")).lift(1))
      events <- readEvents(ws)
      // 载荷暴露（payload 单一序列化点）：retry 对象 + gen 条件键
      payload <- NodeTools.buildNodeListPayload(rt)
      nodes = payload.hcursor.downField("nodes").as[List[Json]].toOption.getOrElse(Nil)
      bPayload = nodes.find(_.hcursor.get[String]("id").toOption.contains(bId))
      aPayload = nodes.find(_.hcursor.get[String]("id").toOption.contains(aId))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 端到端：B 经自动回跳后完成；A 恰跑两代；B 第二次输入携带 A 第二代新结果
      assertEquals(bAfter.status, NodeLifecycle.Completed, "B must complete via the auto-retry hop")
      assertEquals(bAfter.gen, 1, "B must carry gen=1 (one auto-retry generation)")
      assertEquals(aAfter.status, NodeLifecycle.Completed, "A must have re-run to completion")
      assertEquals(aRuns, 2, s"A must run exactly twice (initial + retry rerun), got $aRuns")
      assertEquals(bRuns, 2, s"B must run exactly twice (gen 0 + gen 1), got $bRuns")
      assert(bSecondInput.exists(_.contains("a-result-run-2")),
        s"B's second attempt input must carry A's fresh (2nd-run) result, got: ${bSecondInput.map(_.take(300))}")
      assert(bAfter.deliveredTo.contains(aId), "B must re-record A's delivery in the new generation")
      // 审计留痕：FlowMapEventLog "retry" 事件
      assert(events.exists(l => l.contains("\"type\":\"retry\"") && l.contains(bId)),
        s"event log must carry a retry entry for B, got: ${events.filter(_.contains("retry")).take(3)}")
      // 载荷：B 带 retry {upstream, max} + gen；A（无 retry 配置、gen=0）两键零漂移
      assertEquals(bPayload.flatMap(_.hcursor.get[Int]("gen").toOption), Some(1), "B payload must expose gen")
      assertEquals(bPayload.flatMap(_.hcursor.downField("retry").downField("upstream").as[String].toOption), Some(aId),
        "B payload must expose retry.upstream")
      assertEquals(bPayload.flatMap(_.hcursor.downField("retry").downField("max").as[Int].toOption), Some(1),
        "B payload must expose retry.max")
      assert(aPayload.exists(p => !p.asObject.exists(_.contains("retry")) && !p.asObject.exists(_.contains("gen"))),
        "retry-free node payload must carry neither retry nor gen (zero field drift)")
  }

  // ── ② cap 耗尽 → failed + RetryCap 升级触达 ────────────────

  test("② retry cap exhausted: final failed at gen==max + FeedbackRouter RetryCap escalation reaches the event log") {
    val ws = tempRoot / "ws-retry-cap"
    os.makeDir.all(ws)
    val system = ActorSystem(s"retry-cap-${scala.util.Random.nextInt(100000)}")
    // B 每一代都失败；max=1 → gen 0<1 回跳一次，gen 1>=1 cap 耗尽。
    val llm = new RetryLlm(List("seed-cap-a", "seed-cap-b"), {
      case ("seed-cap-a", _) => Right("cap-a-result")
      case ("seed-cap-b", _) => Left("boom-every-generation")
      case (_, _)            => Right("ok")
    })
    for
      res <- mkResources(system, tempRoot, llm)
      rt <- mountProject("retry-cap", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("retry-cap", "cap-a", "description" -> Json.fromString("cap upstream"),
        "task" -> Json.fromString("seed-cap-a"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- idOf(rt, "cap-a")
      _ <- nodeEdit(nodeInput("retry-cap", "cap-b", "description" -> Json.fromString("doomed holder"),
        "task" -> Json.fromString("seed-cap-b consumes upstream"),
        "in" -> Json.fromString(aId), "out" -> Json.fromString("Nebula"),
        "retry" -> Json.obj("upstream" -> Json.fromString(aId), "max" -> Json.fromInt(1))), ctx)
      bId <- idOf(rt, "cap-b")
      // 等 cap 耗尽形态（首败即 failed 会提前返回——必须等 gen 钉到 max）
      _ <- waitUntil(25.seconds) {
        rt.store.snapshot.map(_.nodes.get(bId).exists(b => b.status == NodeLifecycle.Failed && b.gen == 1))
      }
      bAfter <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      aRuns <- llm.inputs.get.map(_.count(_.contains("seed-cap-a")))
      bRuns <- llm.inputs.get.map(_.count(_.contains("seed-cap-b")))
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // cap 耗尽形态：failed 是终态、gen 钉在 max、不再回跳（B/A 各恰两跑）
      assertEquals(bAfter.status, NodeLifecycle.Failed, "B must stay failed after the retry cap")
      assertEquals(bAfter.gen, 1, "gen must pin at max after cap exhaustion")
      assertEquals(aRuns, 2, "upstream must have run once per generation, no more")
      assertEquals(bRuns, 2, "B must run exactly max+1 times")
      assert(bAfter.result.exists(_.contains("boom-every-generation")),
        s"failed result must carry the last failure summary, got: ${bAfter.result.map(_.take(200))}")
      // RetryCap 升级触达（审计留痕单点：escalated 事件 + reason=RetryCap）
      assert(events.exists(l => l.contains("\"type\":\"escalated\"") && l.contains("reason=RetryCap") && l.contains(bId)),
        s"event log must carry the RetryCap escalation for B, got: ${events.filter(_.contains("escalated")).take(3)}")
  }

  // ── ③ 已终态消费者不被重跑意外重触发 ────────────────────────

  test("③ terminal consumers are NOT re-triggered by the retry rerun (deliverOut dedup + startNode terminal skip)") {
    val ws = tempRoot / "ws-retry-stay"
    os.makeDir.all(ws)
    val system = ActorSystem(s"retry-stay-${scala.util.Random.nextInt(100000)}")
    // A 延迟 500ms 完成 → C（快，立即完成）；B（延迟 900ms 后失败）→ retry → A 重跑
    // → A 完成时对 C 的重投被 deliveredTo 记账幂等吸收，startNode 终态幂等跳过。
    val llm = new RetryLlm(List("seed-stay-a", "seed-stay-b", "seed-stay-c"), {
      case ("seed-stay-a", _) => Right("a-stay-result")
      case ("seed-stay-b", _) => Left("boom-stay")
      case ("seed-stay-c", _) => Right("c-once")
      case (_, _)             => Right("ok")
    })
    for
      res <- mkResources(system, tempRoot, llm)
      rt <- mountProject("retry-stay", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("retry-stay", "stay-a", "description" -> Json.fromString("stay upstream"),
        "task" -> Json.fromString("seed-stay-a"), "out" -> Json.fromString("Nebula")), ctx)
      aId <- idOf(rt, "stay-a")
      _ <- nodeEdit(nodeInput("retry-stay", "stay-c", "description" -> Json.fromString("plain consumer"),
        "task" -> Json.fromString("seed-stay-c"), "in" -> Json.fromString(aId), "out" -> Json.fromString("Nebula")), ctx)
      cId <- idOf(rt, "stay-c")
      _ <- nodeEdit(nodeInput("retry-stay", "stay-b", "description" -> Json.fromString("retry holder"),
        "task" -> Json.fromString("seed-stay-b consumes upstream"),
        "in" -> Json.fromString(aId), "out" -> Json.fromString("Nebula"),
        "retry" -> Json.obj("upstream" -> Json.fromString(aId), "max" -> Json.fromInt(2))), ctx)
      bId <- idOf(rt, "stay-b")
      _ <- waitStatus(rt, "stay-c", Set(NodeLifecycle.Completed))
      cBefore <- nodeById(rt, cId).map(_.getOrElse(fail("C must exist")))
      // 等预算耗尽形态：max=2 且脚本恒败 → gen 0/1 两轮回跳后 gen==2 cap 终态
      //（首败即 failed 会提前返回——必须等 gen 钉到 max）
      _ <- waitUntil(30.seconds) {
        rt.store.snapshot.map(_.nodes.get(bId).exists(b => b.status == NodeLifecycle.Failed && b.gen == 2))
      }
      bFinal <- nodeById(rt, bId).map(_.getOrElse(fail("B must exist")))
      cAfter <- nodeById(rt, cId).map(_.getOrElse(fail("C must exist")))
      cRuns <- llm.inputs.get.map(_.count(_.contains("seed-stay-c")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(cRuns, 1, s"C must run exactly once — the retry rerun must not re-trigger it, got $cRuns")
      assertEquals(cAfter.status, NodeLifecycle.Completed, "C must stay completed")
      assertEquals(cAfter.deliveredTo, List(aId), "C's delivery bookkeeping must stay deduped to a single entry")
      assertEquals(cAfter.completedAt, cBefore.completedAt, "C's completion must not be touched by the retry")
      assertEquals(bFinal.gen, 2, "B must have consumed its full retry budget (max=2)")
      assertEquals(bFinal.status, NodeLifecycle.Failed, "B (always-fail script) must end failed at cap")
  }

  // ── ④ 风暴防护两校验：邻居限定 + retry 环 ───────────────────

  test("④a NODE_RETRY_NEIGHBOR / NODE_RETRY_MAX_RANGE: upstream outside in∪deps (or out-of-range max) rejected at create, 0 spawn") {
    val ws = tempRoot / "ws-retry-neighbor"
    os.makeDir.all(ws)
    val system = ActorSystem(s"retry-nb-${scala.util.Random.nextInt(100000)}")
    val llm = new RetryLlm(Nil, { case (_, _) => Right("ok") })
    for
      res <- mkResources(system, tempRoot, llm)
      rt <- mountProject("retry-nb", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("retry-nb", "nb-x", "description" -> Json.fromString("bystander"),
        "task" -> Json.fromString("nb-x-task"), "out" -> Json.fromString("Nebula")), ctx)
      xId <- idOf(rt, "nb-x")
      // D 与 X 无任何连接 → retry.upstream=X 非邻居 → 拒
      nonNeighbor <- nodeEdit(nodeInput("retry-nb", "nb-d", "description" -> Json.fromString("holder"),
        "task" -> Json.fromString("nb-d-task"), "out" -> Json.fromString("Nebula"),
        "retry" -> Json.obj("upstream" -> Json.fromString(xId), "max" -> Json.fromInt(1))), ctx)
      // max 越界（0 与 11）→ 拒
      zeroMax <- nodeEdit(nodeInput("retry-nb", "nb-d0", "description" -> Json.fromString("holder"),
        "task" -> Json.fromString("nb-d0-task"), "out" -> Json.fromString("Nebula"),
        "retry" -> Json.obj("upstream" -> Json.fromString(xId), "max" -> Json.fromInt(0))), ctx)
      bigMax <- nodeEdit(nodeInput("retry-nb", "nb-d11", "description" -> Json.fromString("holder"),
        "task" -> Json.fromString("nb-d11-task"), "out" -> Json.fromString("Nebula"),
        "retry" -> Json.obj("upstream" -> Json.fromString(xId), "max" -> Json.fromInt(11))), ctx)
      snap <- rt.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(nonNeighbor.isLeft, "non-neighbor retry.upstream must be rejected")
      assert(nonNeighbor.left.exists(_.contains("NODE_RETRY_NEIGHBOR")), s"error must carry the code, got: $nonNeighbor")
      assert(zeroMax.isLeft && zeroMax.left.exists(_.contains("NODE_RETRY_MAX_RANGE")), s"max=0 must be rejected, got: $zeroMax")
      assert(bigMax.isLeft && bigMax.left.exists(_.contains("NODE_RETRY_MAX_RANGE")), s"max=11 must be rejected, got: $bigMax")
      // 被拒创建零残留（0 spawn）
      assert(!snap.nodes.values.exists(n => List("nb-d", "nb-d0", "nb-d11").contains(n.name)),
        "rejected creates must not leave nodes behind")
  }

  test("④b NODE_RETRY_CYCLE: a retry chain closing back on itself is rejected (hand-edited data defense)") {
    val ws = tempRoot / "ws-retry-cycle"
    os.makeDir.all(ws)
    val system = ActorSystem(s"retry-cy-${scala.util.Random.nextInt(100000)}")
    val llm = new RetryLlm(Nil, { case (_, _) => Right("ok") })
    for
      res <- mkResources(system, tempRoot, llm)
      rt <- mountProject("retry-cy", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // C 入口；B 挂 in=[C]（单条交付边 C→B，DAG 合法，两节点先后跑完）
      _ <- nodeEdit(nodeInput("retry-cy", "cy-c", "description" -> Json.fromString("entry"),
        "task" -> Json.fromString("cy-c-task"), "out" -> Json.fromString("Nebula")), ctx)
      cId <- idOf(rt, "cy-c")
      _ <- nodeEdit(nodeInput("retry-cy", "cy-b", "description" -> Json.fromString("downstream"),
        "task" -> Json.fromString("cy-b-task"), "in" -> Json.fromString(cId), "out" -> Json.fromString("Nebula")), ctx)
      bId <- idOf(rt, "cy-b")
      // 种子：手改 store 给 C 写 C.retry→B——模拟手改 flow-map.json / 校验生效前
      // 落盘的脏数据。NodeEdit 可达拓扑（in/deps/out）全被 wouldCreateCycle 挡住、
      // 结构上造不出 retry 环——该校验正是为这类图外脏数据存在的纵深防御。
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes.updated(cId,
        s.nodes(cId).copy(retry = Some(RetryPolicy(bId, 1)))))).void
      // B.retry→C：C ∈ in(B) 邻居合法，但 C.retry 已指向 B → retry 链成环 → 拒
      cycle <- nodeEdit(nodeInput("retry-cy", "cy-b",
        "retry" -> Json.obj("upstream" -> Json.fromString(cId), "max" -> Json.fromInt(1))), ctx)
      snap <- rt.store.snapshot
      selfCycle <- NodeTools.retryCycleExists(rt, bId, bId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(cycle.isLeft, "B.retry→C closing the retry chain must be rejected")
      assert(cycle.left.exists(_.contains("NODE_RETRY_CYCLE")), s"error must carry the code, got: $cycle")
      // 被拒编辑零副作用：B.retry 未写入
      val bAfter = snap.nodes.get(bId).getOrElse(fail("B must exist"))
      assertEquals(bAfter.retry, None, "rejected retry edit must not write B.retry")
      // 自环（B.retry→B）判定：链走即回持有者
      assert(selfCycle, "self-retry must be detected as a cycle by the validator")
  }

  // ── ⑤ 节点 cancelled 级联清理 pending asks（hub CleanupForSession）──

  test("⑤ cancelNode cascades hub CleanupForSession: pending ask slot removed + askUserClosed broadcast to root") {
    val ws = tempRoot / "ws-retry-ask"
    os.makeDir.all(ws)
    val system = ActorSystem(s"retry-ask-${scala.util.Random.nextInt(100000)}")
    // 常驻延迟 LLM：节点停在 running 等取消
    val llm = new HangingLlm(60.seconds)
    for
      res <- mkResources(system, tempRoot, llm)
      rt <- mountProject("retry-ask", ws, system, res)
      // 真实 hub actor：注册进 SharedResources + root wsSend 帧捕获
      hub <- system.spawn(InteractionHub(), "retry-spec-hub")
      _ <- res.interactionHubRef.set(Some(hub))
      frames <- Ref.of[IO, List[Json]](Nil)
      _ <- hub ! InteractionHubCommand.RegisterRoot("nebula-root",
        j => frames.update(_ :+ j))
      ctx = mkCtx(res, system, ws.toString)
      // 入口节点跑长任务 → running
      _ <- nodeEdit(nodeInput("retry-ask", "ask-node", "description" -> Json.fromString("long runner"),
        "task" -> Json.fromString("seed-ask-node-long"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "ask-node", Set(NodeLifecycle.Running))
      nodeId <- idOf(rt, "ask-node")
      node <- nodeById(rt, nodeId).map(_.getOrElse(fail("node must exist")))
      sessionId = node.sessionRef.getOrElse(fail("running node must carry sessionRef"))
      // 模拟该节点会话的 AskUser（与 AgentActor 同一 hub 入口，sourceSession=节点会话）
      _ <- hub ! InteractionHubCommand.Request(InteractionRequest(
        requestId = "req-ask-e2e",
        kind = InteractionKind.AskUser,
        payload = Json.obj("items" -> Json.arr(Json.obj("q" -> Json.fromString("pick one")))),
        reply = InteractionReply.AskUserReply(None),
        rootSessionId = "nebula-root",
        sourceAgent = "ask-node",
        sourceSession = sessionId))
      _ <- waitUntil(10.seconds)(frames.get.map(_.exists(f =>
        f.hcursor.get[String]("type").toOption.contains("askUser") &&
          f.hcursor.get[String]("requestId").toOption.contains("req-ask-e2e"))))
      // 取消节点 → cancelNode 级联 CleanupForSession
      _ <- rt.engine.cancelNodeById(nodeId)
      _ <- waitUntil(15.seconds)(frames.get.map(_.exists(f =>
        f.hcursor.get[String]("type").toOption.contains("askUserClosed") &&
          f.hcursor.get[String]("requestId").toOption.contains("req-ask-e2e") &&
          f.hcursor.get[String]("sessionId").toOption.contains("nebula-root") &&
          f.hcursor.get[String]("sourceSession").toOption.contains(sessionId))))
      framesAfter <- frames.get
      nodeAfter <- nodeById(rt, nodeId).map(_.getOrElse(fail("node must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      // 验收⑤：引擎广播 askUserClosed{requestId}（前端关卡处理归 F2）
      val closed = framesAfter.find(f => f.hcursor.get[String]("type").toOption.contains("askUserClosed"))
      assert(closed.isDefined, s"askUserClosed must be broadcast on cancel, got frames: ${framesAfter.map(_.hcursor.get[String]("type").toOption)}")
      assertEquals(nodeAfter.status, NodeLifecycle.Cancelled, "node must be cancelled (cascade runs on the cancel funnel)")
  }

end NodeFailedRetrySpec
