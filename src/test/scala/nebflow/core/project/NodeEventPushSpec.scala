package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.{AgentLibrary, SharedResources}
import nebflow.core.PathUtil
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, NodeEditTool, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * WS 事件推送覆盖回归（子任务：Flow Map 节点变化事件推送补全）。
 *
 * 断言前端 4 事件通道（nodeCreated / nodeUpdated / nodeCompleted / nodeRemoved）覆盖
 * 增/删/改/wiring 全部变化，且事件 payload 与 NodeList 同构（2026-09-05 载荷收敛后：
 * {id,name,agent,skill,mcp,preset,description,status,in,out,hasWorktree,worktree,
 * createdAt,completedAt,ttlLeftSec} 基集合 + 条件字段 hasResult/taskPreview/deps/
 * blockedFeedback/plugins；result 全文与摘要不进默认载荷——按需读取契约；
 * retries 键随 NodeDef 假语义字段删除移除，trigger-chain-fix 批）：
 *
 * 1. wiring 变更事件：create 带 in（barrier 合并）→ 上游 out 改指发 nodeUpdated；
 *    create 带 out → 目标 in 追加发 nodeUpdated（此前只有 nodeCreated，改写节点无事件）。
 * 2. edit out 改接：本节点 + 旧目标（in 移除）+ 新目标（in 追加）各发最终态 nodeUpdated。
 * 3. edit in 追加：本节点（in 含新上游）+ 上游（out 改指）各发最终态 nodeUpdated。
 * 4. TTL 移除：nodeRemoved payload 携带节点身份（此前空对象）。
 */
class NodeEventPushSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-node-event-push"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "test-agent")
  os.write.over(
    tempRoot / "agents" / "test-agent" / "agent.json",
    """{"name":"test-agent","description":"event-push regression agent","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "test-agent" / "system.md", "# test-agent\n")
  // 2026-09-05 agent 退役：新建节点执行统一 general——fixture 侧补 general agent
  os.makeDir.all(tempRoot / "agents" / "general")
  os.write.over(tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}""")
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  private class RecordingLlm extends LlmHandle[IO]:
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

  private def mkCtx(res: SharedResources, system: ActorSystem, ws: String): ToolContext =
    ToolContext(
      projectRoot = ws,
      sessionId = Some("event-push-sid"),
      rootSessionId = Some("nebula-root"),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def nodeEdit(input: Json, ctx: ToolContext): IO[Either[String, String]] =
    NodeEditTool.call(input.asObject.get, ctx).map(_.left.map(_.message))

  private def nodeInput(project: String, nodename: String, extra: (String, Json)*): Json =
    Json.obj(("project" -> Json.fromString(project)) :: ("nodename" -> Json.fromString(nodename)) :: extra.toList*)

  /** NodeList 载荷节点条目的字段集（事件 payload 必须同构——与 NodePayload.buildNodeJson
    * 单一序列化点对齐；skill/mcp/preset 为节点配置字段；description 恒带（存量无值 null）；
    * blockCount 恒带（§4.1）；**result 全文/摘要不进默认载荷**（2026-09-05 载荷收敛），
    * hasResult 仅 result 非空节点带（条件序列化）→ 不入本基集合，按断言场景合并。
    * blockedFeedback / deps / plugins 同为条件字段 → 不入基集合）。 */
  private val NodeListKeys: Set[String] =
    Set("id", "name", "agent", "skill", "mcp", "preset", "description", "status", "in", "out", "hasWorktree", "worktree", "blockCount", "createdAt", "completedAt", "ttlLeftSec")
  /** 有结果节点（终态）的载荷键集 = 基集合 + hasResult。 */
  private val NodeListKeysWithResult: Set[String] = NodeListKeys + "hasResult"

  /** 记录 (type, nodeId) 事件的挂载。 */
  private def mountRecording(
    name: String,
    ws: os.Path,
    system: ActorSystem,
    res: SharedResources
  ): IO[(ProjectRuntime, Ref[IO, List[(String, String, Json)]])] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      events <- Ref.of[IO, List[(String, String, Json)]](Nil)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (t, id, payload) => events.update((t, id, payload) :: _)
      )
      pd = ProjectDef(name = name, workspace = ws.toString, agentFile = (ws / "AGENTS.md").toString, createdAt = System.currentTimeMillis())
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
    yield (rt, events)

  override def beforeEach(context: munit.BeforeEach): Unit = ProjectRuntimeRegistry.clear
  override def afterEach(context: munit.AfterEach): Unit = ProjectRuntimeRegistry.clear

  private def seed(rt: ProjectRuntime, defs: (String, NodeDef)*): IO[Unit] =
    rt.store.mutate(s => s.copy(nodes = s.nodes ++ defs.toMap)).void

  // ── 1. create 带 in + out：barrier 合并接线事件 ──────────

  test("E① create with in+out: nodeCreated for new + nodeUpdated for rewired upstream and out target") {
    val ws = tempRoot / "ws-e1"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ev1-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      (rt, events) <- mountRecording("acc-ev1", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- seed(rt,
        "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent", status = NodeLifecycle.Wiring, createdAt = now),
        "n-b" -> NodeDef(id = "n-b", name = "B", agent = "test-agent", status = NodeLifecycle.Wiring, createdAt = now))
      r <- nodeEdit(nodeInput("acc-ev1", "M", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("merge"), "in" -> Json.arr(Json.fromString("n-a")),
        "out" -> Json.fromString("n-b")), ctx)
      evs <- events.get
      s <- rt.store.snapshot
      mId = s.nodes.values.find(_.name == "M").map(_.id).getOrElse("")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"create must succeed, got: $r")
      // nodeCreated 载荷：新节点最终态（in=[n-a], out=[n-b]）
      val created = evs.find((t, id, _) => t == "nodeCreated" && id == mId)
      assert(created.isDefined, s"nodeCreated for M must be emitted, got: ${evs.map((t, id, _) => (t, id))}")
      val cj = created.get._3
      assertEquals(cj.hcursor.get[String]("name").toOption, Some("M"))
      assertEquals(cj.hcursor.downField("in").as[List[String]].toOption, Some(List("n-a")), "created payload must carry final in")
      assertEquals(cj.hcursor.downField("out").as[Option[String]].toOption, Some(Some("n-b")), "created payload must carry final out")
      // wiring 变更事件：上游 A out 改指 M
      val updA = evs.find((t, id, p) => t == "nodeUpdated" && id == "n-a" && p.hcursor.get[String]("out").toOption.contains(mId))
      assert(updA.isDefined, s"upstream A must emit nodeUpdated with out=M, got: ${evs.map((t, id, _) => (t, id))}")
      // wiring 变更事件：out 目标 B in 追加 M
      val updB = evs.find((t, id, p) => t == "nodeUpdated" && id == "n-b" && p.hcursor.downField("in").as[List[String]].toOption.exists(_.contains(mId)))
      assert(updB.isDefined, s"out target B must emit nodeUpdated with in+=M, got: ${evs.map((t, id, _) => (t, id))}")
  }

  // ── 2. edit out 改接：本节点 + 旧目标 + 新目标 ──────────

  test("E② edit out rewire: nodeUpdated for node (out=new) + old target (in removed) + new target (in added)") {
    val ws = tempRoot / "ws-e2"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ev2-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      (rt, events) <- mountRecording("acc-ev2", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- seed(rt,
        "n-a" -> NodeDef(id = "n-a", name = "A", agent = "test-agent", status = NodeLifecycle.Completed,
          result = Some("buffered"), createdAt = now, completedAt = Some(now - 1000), ttlExpireAt = Some(now + 99999), out = Some("n-x")),
        "n-x" -> NodeDef(id = "n-x", name = "X", agent = "test-agent", status = NodeLifecycle.Wiring, in = List("n-a"), createdAt = now),
        "n-y" -> NodeDef(id = "n-y", name = "Y", agent = "test-agent", status = NodeLifecycle.Wiring, createdAt = now))
      r <- nodeEdit(nodeInput("acc-ev2", "A", "out" -> Json.fromString("n-y")), ctx)
      evs <- events.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"rewire must succeed, got: $r")
      // 本节点：out = n-y（最终态）
      val updA = evs.find((t, id, p) => t == "nodeUpdated" && id == "n-a" && p.hcursor.get[String]("out").toOption.contains("n-y"))
      assert(updA.isDefined, s"A must emit nodeUpdated with out=n-y, got: ${evs.map((t, id, _) => (t, id))}")
      // 旧目标 X：in 移除 A（最终态 in=[]）
      val updX = evs.find((t, id, p) => t == "nodeUpdated" && id == "n-x")
      assert(updX.isDefined, s"old target X must emit nodeUpdated, got: ${evs.map((t, id, _) => (t, id))}")
      assertEquals(updX.get._3.hcursor.downField("in").as[List[String]].toOption, Some(List.empty), "X.in must have A removed in payload")
      // 新目标 Y：in 追加 A
      val updY = evs.find((t, id, p) => t == "nodeUpdated" && id == "n-y" && p.hcursor.downField("in").as[List[String]].toOption.exists(_.contains("n-a")))
      assert(updY.isDefined, s"new target Y must emit nodeUpdated with in+=A, got: ${evs.map((t, id, _) => (t, id))}")
  }

  // ── 3. edit in 追加：本节点（in 含新上游）+ 上游（out 改指）──

  test("E③ edit in-add: nodeUpdated for node (in includes new upstream) + upstream (out rewired)") {
    val ws = tempRoot / "ws-e3"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ev3-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      (rt, events) <- mountRecording("acc-ev3", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      now = System.currentTimeMillis()
      _ <- seed(rt,
        "n-u" -> NodeDef(id = "n-u", name = "U", agent = "test-agent", status = NodeLifecycle.Wiring, createdAt = now),
        "n-m" -> NodeDef(id = "n-m", name = "M", agent = "test-agent", status = NodeLifecycle.Wiring, in = List.empty, createdAt = now))
      r <- nodeEdit(nodeInput("acc-ev3", "M", "in" -> Json.arr(Json.fromString("n-u"))), ctx)
      evs <- events.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"in-add must succeed, got: $r")
      // 本节点 M：in = [n-u]（此前 payload 含陈旧 in——回归点）
      val updM = evs.find((t, id, p) => t == "nodeUpdated" && id == "n-m" && p.hcursor.downField("in").as[List[String]].toOption.exists(_.contains("n-u")))
      assert(updM.isDefined, s"M must emit nodeUpdated with final in=[n-u], got: ${evs.map((t, id, p) => (t, id, p.hcursor.downField("in").as[List[String]].toOption))}")
      // 上游 U：out 改指 M
      val upduU = evs.find((t, id, p) => t == "nodeUpdated" && id == "n-u" && p.hcursor.get[String]("out").toOption.contains("n-m"))
      assert(upduU.isDefined, s"upstream U must emit nodeUpdated with out=n-m, got: ${evs.map((t, id, _) => (t, id))}")
  }

  // ── 4. payload 与 NodeList 同构 ─────────────────────────

  test("E④ event payloads are NodeList-homomorphic (same key set as NodeList node entry)") {
    val ws = tempRoot / "ws-e4"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ev4-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      (rt, events) <- mountRecording("acc-ev4", ws, system, res)
      ctx = mkCtx(res, system, ws.toString)
      r <- nodeEdit(nodeInput("acc-ev4", "调研-同构", "description" -> Json.fromString("test node purpose"),
        "task" -> Json.fromString("homomorphic"), "out" -> Json.fromString("Nebula")), ctx)
      _ <- IO.sleep(3.seconds)
      evs <- events.get
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(r.isRight, s"create must succeed, got: $r")
      val created = evs.find((t, _, _) => t == "nodeCreated").map(_._3)
      assert(created.isDefined, s"nodeCreated must be emitted, got: ${evs.map((t, id, _) => (t, id))}")
      assertEquals(created.get.asObject.map(_.keys.toSet), Some(NodeListKeys), "nodeCreated payload keys must equal NodeList keys")
      val updated = evs.find((t, _, p) => t == "nodeUpdated" && p.hcursor.get[String]("status").toOption.contains(NodeLifecycle.Running)).map(_._3)
      assert(updated.isDefined, s"nodeUpdated(running) must be emitted, got: ${evs.map((t, id, _) => (t, id))}")
      assertEquals(updated.get.asObject.map(_.keys.toSet), Some(NodeListKeys), "nodeUpdated payload keys must equal NodeList keys")
      val completed = evs.find((t, _, _) => t == "nodeCompleted").map(_._3)
      assert(completed.isDefined, s"nodeCompleted must be emitted, got: ${evs.map((t, id, _) => (t, id))}")
      // 2026-09-05 载荷收敛：completed 节点有结果 → 基集合 + hasResult；result 全文/摘要不进载荷
      assertEquals(completed.get.asObject.map(_.keys.toSet), Some(NodeListKeysWithResult), "nodeCompleted payload keys must equal NodeList keys + hasResult (no result text in payload)")
      assert(!completed.get.asObject.exists(obj => obj.keys.exists(_ == "result")), "completed payload must NOT carry result (slim payload contract)")
  }

  // ── 5. TTL 移除：nodeRemoved 携带节点身份（TtlTick 全路径）──

  test("E⑤ TTL sweep: ProjectActor TtlTick emits nodeRemoved with NodeList-homomorphic payload") {
    val ws = tempRoot / "ws-e5"
    os.makeDir.all(ws)
    val system = ActorSystem(s"ev5-${scala.util.Random.nextInt(100000)}")
    for
      res <- mkResources(system, tempRoot, new RecordingLlm)
      (rt0, events) <- mountRecording("acc-ev5", ws, system, res)
      now = System.currentTimeMillis()
      _ <- seed(rt0,
        // 裁定④链级即时归档口径：n-gone 自成一批（createdAt 回拨 >120s 批窗口）全终态
        // → 即时归档；n-stay blocked（待办非终态）→ 链未齐保留主图
        "n-gone" -> NodeDef(id = "n-gone", name = "已过期", agent = "test-agent", status = NodeLifecycle.Completed,
          result = Some("expired result"), in = List.empty, out = Some("Nebula"), createdAt = now - 300000,
          completedAt = Some(now - 60000), ttlExpireAt = Some(now - 1000)),
        "n-stay" -> NodeDef(id = "n-stay", name = "未到期", agent = "test-agent", status = NodeLifecycle.Blocked,
          result = Some("fresh"), createdAt = now, completedAt = None, ttlExpireAt = None))
      // TtlTick 全路径：ProjectActor → sweepCompletedChains → emitRemoved
      ref <- system.spawn(
        nebflow.core.project.ProjectActor(
          nebflow.core.project.ProjectActor.ProjectConfig(rt0.project, rt0.engine, system, res, "nebula-root")),
        s"proj-ev5-${scala.util.Random.nextInt(100000)}")
      _ <- (ref ! nebflow.core.project.ProjectActor.ProjectCommand.TtlTick).void
      _ <- IO.sleep(200.millis)
      evs <- events.get
      s <- rt0.store.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(!s.nodes.contains("n-gone"), "expired node must be swept from activity")
      val removed = evs.find((t, id, _) => t == "nodeRemoved" && id == "n-gone")
      assert(removed.isDefined, s"nodeRemoved for n-gone must be emitted, got: ${evs.map((t, id, _) => (t, id))}")
      val p = removed.get._3
      assertEquals(p.hcursor.get[String]("name").toOption, Some("已过期"), "removed payload must carry node identity (was empty obj)")
      assertEquals(p.hcursor.get[String]("agent").toOption, Some("test-agent"))
      assertEquals(p.hcursor.get[String]("status").toOption, Some(NodeLifecycle.Completed))
      assertEquals(p.hcursor.downField("out").as[Option[String]].toOption, Some(Some("Nebula")))
      // 有结果节点 → 基集合 + hasResult（2026-09-05 载荷收敛，result 本体不进载荷）
      assertEquals(p.asObject.map(_.keys.toSet), Some(NodeListKeysWithResult), "nodeRemoved payload keys must equal NodeList keys + hasResult")
      assert(!evs.exists((t, id, _) => t == "nodeRemoved" && id == "n-stay"), "non-expired node must NOT emit nodeRemoved")
  }

end NodeEventPushSpec
