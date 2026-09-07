package nebflow.core.project

import cats.effect.{IO, Ref}
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse as jsonParse
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeCancelTool, NodeEditTool, NodeTools, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * 清场工具缺口修复回归（fix c，20260903 03:04 清场误杀事故复盘三缺口）：
 *
 * - c-① liveness：NodeList 快照 running 节点带 liveness 字段——活会话（有在飞
 *   执行 fiber）true、死会话残留（无在飞 fiber，实例重启泄漏形态）false；非
 *   running 节点不带键（wiring/pending 无会话存活概念、终态无存活可言）。
 * - c-② abandon 接受死会话 running（收殓 cancelled + TTL + 审计）；活 running
 *   绝对拒绝（误杀防护硬约束——与 NodeDepsSpec T8 互锚）。
 * - c-③ NodeCancel 对死会话 running 真实落终态（修复假成功）；活 running 走
 *   正常取消信号（原语义）；幂等：已终态 → no-op 不重复终态化。
 *
 * 死会话构造：单测层直接播种 status=running 且 engine 在飞表无此节点的 NodeDef
 * ——与实例重启后「磁盘 running 滞留、内存注册表清空」的真实形态同构（实证
 * n-4f749677 / n-2a1524ab）。活会话构造：真实 spawn + 延迟 LLM 保持运行窗口。
 *
 * 误杀防护变异验红（真实执行，证据见节点结果报告）：反向变异「abandon 接受
 * 全部 running」（去掉 isRunning 预检）→ 活 running abandon 用例红——健康
 * running 的行为与现状一致的回归锚。
 */
class NodeCleanupLivenessSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-cleanup-liveness"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"cleanup-liveness regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private class CaptureLlm(delayOf: String => FiniteDuration = _ => 0.millis):
    val inputs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)
    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
          req: LlmRequest,
          onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n")
        Stream
          .eval(inputs.update(_ :+ text) >> IO.sleep(delayOf(text)))
          .flatMap(_ => Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None)))

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
      sessionId = Some("cln-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeCancel(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeCancelTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

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

  private def cancelInput(project: String, nodeId: String): Json =
    Json.obj("project" -> Json.fromString(project), "node-id" -> Json.fromString(nodeId))

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
    waitUntil(15.seconds) {
      rt.store.snapshot.map(_.nodes.values.find(_.name == name)).flatMap {
        case Some(n) => IO.pure(statuses.contains(n.status))
        case None    => IO.pure(false)
      }
    }

  /** NodeList 快照中指定节点的 payload JSON（NodeList 工具 / REST 同源）。 */
  private def payloadOf(rt: ProjectRuntime, id: String): IO[Json] =
    NodeTools.buildNodeListPayload(rt).map { payload =>
      val nodes = payload.hcursor.downField("nodes").focus.flatMap(_.asArray).getOrElse(Vector.empty)
      nodes.find(_.hcursor.get[String]("id").toOption.contains(id))
        .getOrElse(fail(s"node $id missing from NodeList payload"))
    }

  /** 播种「死会话 running」节点：status=running 且 engine 在飞表无此节点——
    * 与实例重启后磁盘滞留形态同构。 */
  private def seedDeadRunning(rt: ProjectRuntime, id: String, name: String): IO[Unit] =
    rt.store.mutate { s =>
      s.copy(nodes = s.nodes + (id -> NodeDef(
        id = id,
        name = name,
        agent = "test-agent",
        status = NodeLifecycle.Running,
        startedAt = Some(System.currentTimeMillis() - 3600_000),
        createdAt = System.currentTimeMillis() - 3600_000)))
    }.void

  private def readAuditTypes(ws: os.Path): IO[List[(String, String)]] =
    IO.blocking(os.read(ws / ".nebflow" / FlowMapEventLog.FileName))
      .map(_.linesIterator.toList.filter(_.trim.nonEmpty))
      .map(lines => lines.flatMap(l => jsonParse(l).toOption.map(j =>
        (j.hcursor.get[String]("type").getOrElse(""), j.hcursor.get[String]("nodeId").getOrElse("")))))
      .handleError(_ => Nil)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  // ── c-① + c-③：死会话 running 的 liveness 识别与 NodeCancel 真实收殓 ──

  test("c1+c3 dead running: liveness=false in NodeList; NodeCancel reaps to cancelled (real terminal, not fake success); idempotent after") {
    val ws = tempRoot / "ws-dead"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cln-dead-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("cln-dead", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedDeadRunning(rt, "n-deadseed", "dead-running")
      deadId = "n-deadseed"
      // c-①：NodeList liveness=false（死会话残留可判别——分发器/作者不再无从下手）
      payDead <- payloadOf(rt, deadId)
      aliveFlag <- rt.engine.isRunning(deadId)
      // c-③：NodeCancel 真实收殓（修复前：Right("cancel signal sent") 但状态滞留 running）
      r1 <- nodeCancel(cancelInput("cln-dead", deadId), ctx)
      after1 <- nodeById(rt, deadId).map(_.getOrElse(fail("node must exist")))
      audit1 <- readAuditTypes(ws)
      // 幂等：已终态再 cancel → no-op，不重复终态化
      r2 <- nodeCancel(cancelInput("cln-dead", deadId), ctx)
      after2 <- nodeById(rt, deadId).map(_.getOrElse(fail("node must exist")))
      // 终态节点 payload 不带 liveness 键（语义明确）
      payAfter <- payloadOf(rt, deadId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(payDead.hcursor.get[Boolean]("liveness").toOption, Some(false),
        s"dead running must report liveness=false, got ${payDead.noSpaces.take(200)}")
      assertEquals(aliveFlag, false, "engine in-flight map must not hold the seeded node")
      assert(r1.isRight && r1.exists(_.contains("reaped")), s"NodeCancel must reap dead running, got: $r1")
      assertEquals(after1.status, NodeLifecycle.Cancelled, "dead running must be terminalized by NodeCancel")
      assert(after1.ttlExpireAt.isEmpty,
        "reaped node must NOT get a display TTL (2026-09-07 ruling: cancelled retained on map, no forced cleanup)")
      assert(audit1.exists((t, id) => t == "reaped" && id == deadId), s"reap must be audit-logged, got: $audit1")
      assert(r2.isRight && r2.exists(_.contains("no-op")), s"second cancel must be a no-op, got: $r2")
      assertEquals(after2.status, NodeLifecycle.Cancelled, "status stays cancelled (idempotent)")
      assert(payAfter.hcursor.get[Boolean]("liveness").toOption.isEmpty,
        "terminal node payload must NOT carry a liveness key (N/A semantics)")
  }

  // ── c-①：活 running liveness=true；非 running 不带键 ─────

  test("c1 live running: liveness=true; wiring node and terminal node carry NO liveness key") {
    val ws = tempRoot / "ws-live"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cln-live-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm(text => if text.contains("slow-live") then 1500.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("cln-live", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      // wiring 节点（无存活概念）。w-wire store 直种（20260903 创建必带 out 新规范下
      // out-only wiring 节点不可经 NodeEdit 创建）
      _ <- rt.store.mutate(s => s.copy(nodes = s.nodes ++ Map(
        "n-w-wire" -> NodeDef(id = "n-w-wire", name = "w-wire", agent = "test-agent",
          status = NodeLifecycle.Wiring, out = Some("Nebula"), createdAt = System.currentTimeMillis()))))
      wiringId <- idOf(rt, "w-wire")
      payWiring <- payloadOf(rt, wiringId)
      // 真实活 running（延迟 LLM 保持窗口）
      _ <- nodeEdit(nodeInput("cln-live", "slow-live", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-live"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "slow-live", Set(NodeLifecycle.Running))
      liveId <- idOf(rt, "slow-live")
      engineAlive <- rt.engine.isRunning(liveId)
      payLive <- payloadOf(rt, liveId)
      _ <- waitStatus(rt, "slow-live", Set(NodeLifecycle.Completed))
      payDone <- payloadOf(rt, liveId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(payWiring.hcursor.get[Boolean]("liveness").toOption.isEmpty,
        "wiring node must NOT carry liveness key")
      assertEquals(engineAlive, true, "live running must be in the engine in-flight map")
      assertEquals(payLive.hcursor.get[Boolean]("liveness").toOption, Some(true),
        s"live running must report liveness=true, got ${payLive.noSpaces.take(200)}")
      assert(payDone.hcursor.get[Boolean]("liveness").toOption.isEmpty,
        "terminal node must NOT carry liveness key")
  }

  // ── 误杀防护（硬约束）：活 running 不可被 abandon 收殓 ────

  test("kill-protection: abandon on LIVE running refused (node untouched); NodeCancel on live uses the normal signal chain") {
    val ws = tempRoot / "ws-protect"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cln-prot-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm(text => if text.contains("slow-p") then 2500.millis else 0.millis)
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("cln-protect", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- nodeEdit(nodeInput("cln-protect", "slow-p", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("slow-p"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- waitStatus(rt, "slow-p", Set(NodeLifecycle.Running))
      liveId <- idOf(rt, "slow-p")
      // abandon 活 running → 拒绝（误杀防护）
      rAbandon <- nodeEdit(nodeInput("cln-protect", "slow-p", "abandon" -> Json.fromBoolean(true)), ctx)
      afterAbandon <- nodeById(rt, liveId).map(_.getOrElse(fail("node must exist")))
      // NodeCancel 活 running → 正常取消信号链（原语义不变）
      rCancel <- nodeCancel(cancelInput("cln-protect", liveId), ctx)
      _ <- waitStatus(rt, "slow-p", Set(NodeLifecycle.Cancelled))
      afterCancel <- nodeById(rt, liveId).map(_.getOrElse(fail("node must exist")))
      audit <- readAuditTypes(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(rAbandon.isLeft, s"abandon on live running must be refused, got: $rAbandon")
      assert(rAbandon.left.exists(m => m.contains("running") && m.contains("NodeCancel")),
        s"refusal must point to NodeCancel, got: $rAbandon")
      assertEquals(afterAbandon.status, NodeLifecycle.Running, "live running must be untouched by refused abandon")
      assert(rCancel.isRight && rCancel.exists(_.contains("cancel signal sent")),
        s"NodeCancel on live running must use the normal signal path, got: $rCancel")
      assertEquals(afterCancel.status, NodeLifecycle.Cancelled, "normal cancel chain must finalize cancelled")
      assert(!audit.exists((t, id) => t == "reaped" && id == liveId),
        "live running must NOT be reaped (no fake-reap audit)")
  }

  // ── c-②：死会话 running 经 abandon 收殓 + 幂等 ──────────

  test("c2 abandon dead running: accepted, cancelled + TTL + audit; re-abandon on terminal is idempotent") {
    val ws = tempRoot / "ws-abandon-dead"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cln-abd-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("cln-abandon-dead", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedDeadRunning(rt, "n-deadabd", "dead-abandon")
      deadId = "n-deadabd"
      r1 <- nodeEdit(nodeInput("cln-abandon-dead", "dead-abandon", "abandon" -> Json.fromBoolean(true)), ctx)
      after1 <- nodeById(rt, deadId).map(_.getOrElse(fail("node must exist")))
      audit1 <- readAuditTypes(ws)
      // 再 abandon（现为终态 cancelled——接受域内）→ 幂等，仍 cancelled
      r2 <- nodeEdit(nodeInput("cln-abandon-dead", "dead-abandon", "abandon" -> Json.fromBoolean(true)), ctx)
      after2 <- nodeById(rt, deadId).map(_.getOrElse(fail("node must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r1.isRight, s"dead running must be abandonable, got: $r1")
      assertEquals(after1.status, NodeLifecycle.Cancelled, "abandoned dead running becomes cancelled")
      assert(after1.ttlExpireAt.isEmpty,
        "abandoned dead running must NOT get a display TTL (2026-09-07 ruling: cancelled retained on map)")
      assert(audit1.exists((t, id) => t == "abandoned" && id == deadId), s"abandon must be audit-logged, got: $audit1")
      assert(r2.isRight, s"terminal re-abandon is in-domain, got: $r2")
      assertEquals(after2.status, NodeLifecycle.Cancelled, "stays cancelled (idempotent)")
  }

  // ── 收殓精确性：只收目标，不误伤同项目其他节点 ──────────

  test("reap precision: reaping a dead running leaves other nodes untouched") {
    val ws = tempRoot / "ws-precision"
    os.makeDir.all(ws)
    val system = ActorSystem(s"cln-prec-${scala.util.Random.nextInt(100000)}")
    val llm = CaptureLlm()
    for
      res <- mkResources(system, tempRoot, llm.handle)
      rt <- mountProject("cln-precision", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      _ <- seedDeadRunning(rt, "n-deadp", "dead-target")
      _ <- seedDeadRunning(rt, "n-keep", "keep-running")
      _ <- nodeCancel(cancelInput("cln-precision", "n-deadp"), ctx)
      target <- nodeById(rt, "n-deadp").map(_.getOrElse(fail("target must exist")))
      keep <- nodeById(rt, "n-keep").map(_.getOrElse(fail("keep must exist")))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(target.status, NodeLifecycle.Cancelled, "target must be reaped")
      assertEquals(keep.status, NodeLifecycle.Running, "other nodes must be untouched")
  }

end NodeCleanupLivenessSpec
