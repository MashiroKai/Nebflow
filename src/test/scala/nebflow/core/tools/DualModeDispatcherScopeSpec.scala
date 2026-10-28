package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.ActorSystem
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.delegate.{DelegateRegistry, DualModeConfig}
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{Defaults, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * 双模式批（2026-10-28 · 设计 §5.2/§5.3/§5.7）分发器作用域闸 spec（ChainWrite
 * ToolSpec 骨架同款——stub LLM、真 host 挂载；总闸 sys prop 热读置 on）：
 *
 *  - D1 分发器链闸（all-or-nothing）：批内任一节点带 plugins 键 ⇒ 整链拒收
 *    （NODE_PLUGINS_DISPATCHER_ALLOCATED），零落盘零 spawn。
 *  - D2 同族封口：worktree/merge 键 ⇒ NODE_WORKTREE_UNSUPPORTED /
 *    NODE_MERGE_UNSUPPORTED（模式二同窗拒绝面）。
 *  - D3 无键分发器链：整链登记成功，ACK 携分发器串行化提醒；NodeDef.plugins =
 *    逐 node 分配物化（空目录短路口 = Nil，零模型调用）；入口放行。
 *  - D4 writeScope 软闸：无 deps 序 ∧ 写域相交的批内节点对 ⇒ write-scope-collision
 *    WARN 审计事件（只 WARN 不拒绝——链照常落库）。
 *  - D5 NodeEdit 分发器作用域：plugins 键在场 = 拒（NODE_PLUGINS_DISPATCHER_ALLOCATED）；
 *    缺键 = 引擎代行分配（allocatePreparation，node:create 阶段）建位成功。
 *  - D6 闸关对照组：同形 plugins 键链照常登记（行为分叉只在闸 on 的分发器链）。
 */
class DualModeDispatcherScopeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-dual-mode-dispatcher"
  private val originalRoot = PathUtil.dataRoot

  PathUtil.setDataRoot(tempRoot)
  os.remove.all(tempRoot)
  os.makeDir.all(tempRoot / "agents" / "general")

  os.write.over(
    tempRoot / "agents" / "general" / "agent.json",
    """{"name":"general","description":"general executor","tools":[],"category":"standalone"}"""
  )
  os.write.over(tempRoot / "agents" / "general" / "system.md", "# general\n")
  os.makeDir.all(tempRoot / "sessions")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)

  override def beforeEach(context: munit.BeforeEach): Unit =
    (nebflow.core.project.ProjectRuntimeRegistry.clear *> nebflow.core.project.DelegateFlowHosts.clear).unsafeRunSync()
    PathUtil.setDataRoot(tempRoot)
    System.setProperty(DualModeConfig.SysPropEnabled, "true")

  override def afterEach(context: munit.AfterEach): Unit =
    System.clearProperty(DualModeConfig.SysPropEnabled)
    (nebflow.core.project.ProjectRuntimeRegistry.clear *> nebflow.core.project.DelegateFlowHosts.clear).unsafeRunSync()

  // ── 基建（ChainWriteToolSpec 同款骨架）────────────────────────────────────

  private def mkResources(system: ActorSystem, llm: LlmHandle[IO]): IO[SharedResources] =
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
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tempRoot / "agents"),
      taskStore = nebflow.core.task.FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = null,
      actorSystem = null,
      voiceMutedRef = voiceMuted
    )

  /** 捕获型 LLM：按「最后一条用户消息」路由剧本回复。 */
  private class ScriptLlm(respond: String => String):
    val fullReqs: Ref[IO, List[String]] = Ref.unsafe[IO, List[String]](Nil)

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] =
        val text = req.messages.map(_.textContent).mkString("\n---\n")
        Stream
          .eval(fullReqs.update(_ :+ text))
          .flatMap(_ => Stream(StreamChunk.TextDelta(respond(text)), StreamChunk.Done(None, None)))

  end ScriptLlm

  private def mount(
    ws: os.Path,
    delegateId: String,
    system: ActorSystem,
    res: SharedResources
  ): IO[Option[nebflow.core.project.DelegateFlowRuntime]] =
    nebflow.core.project.DelegateFlowHosts.mount(
      delegateId = delegateId,
      project = None,
      baseWorkspace = ws.toString,
      engineCwd = ws.toString,
      system = system,
      resources = res,
      wsSend = None,
      reportGateHold = Some(false),
      rootNotifyQuietMs = Some(0),
      destroyWindowMs = Some(0L)
    )

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

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  private def delegateCtx(delegateId: String, ws: os.Path): ToolContext =
    ToolContext(projectRoot = ws.toString, sessionId = Some(delegateId))

  private def mkModeRecord(delegateId: String, mode: String): Unit =
    DelegateRegistry.put(
      DelegateRegistry.Record(
        id = delegateId,
        executor = "nebflow",
        cwd = tempRoot.toString,
        project = None,
        taskId = None,
        parentSessionId = "parent-scope",
        title = s"dispatcher scope $delegateId",
        createdAt = System.currentTimeMillis() - 60_000L,
        mode = Some(mode)
      )
    )

  private def nodesJson(items: JsonObject*) =
    JsonObject("nodes" -> io.circe.Json.arr(items.map(obj => io.circe.Json.fromJsonObject(obj))*))

  private def nodeJson(id: String, task: String, deps: List[String] = Nil, extra: (String, io.circe.Json)*): JsonObject =
    JsonObject.fromIterable(
      List[ (String, io.circe.Json)]("id" -> id.asJson, "task" -> task.asJson) ++
        (if deps.nonEmpty then List("deps" -> deps.map(_.asJson).asJson) else Nil) ++
        extra.toList
    )

  // ── D1/D2 分发器链闸（all-or-nothing）────────────────────────────────────

  test("D1: a dispatcher chain carrying a plugins key is rejected whole (zero nodes, zero spawn)") {
    val ws = os.temp.dir(prefix = "nb-dm-d1-", deleteOnExit = false)
    val system = ActorSystem(s"dm-d1-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d1a2b3c4"
    val llm = ScriptLlm(_ => "D1-DONE")
    mkModeRecord(delegateId, DualModeConfig.ModeDispatcher)
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      _ = mo.getOrElse(fail("host must mount"))
      res1 <- ChainWriteTool.call(
        nodesJson(
          nodeJson("a", "task a", Nil, "plugins" -> io.circe.Json.arr()),
          nodeJson("b", "task b", List("a"))
        ),
        delegateCtx(delegateId, ws)
      )
      snap <- mo.get.store.snapshot
      reqs <- llm.fullReqs.get
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        res1.left.exists(_.message.contains(ChainWriteTool.Codes.PluginsDispatcherAllocated)),
        s"the whole chain must be refused: $res1"
      )
      assertEquals(snap.nodes.size, 0, "all-or-nothing: zero nodes may land")
      assertEquals(reqs, Nil, "zero spawn: no LLM request may fire on a refused chain")
  }

  test("D2: worktree and merge keys are sealed chain-wide in dispatcher mode (same gate window)") {
    val ws = os.temp.dir(prefix = "nb-dm-d2-", deleteOnExit = false)
    val system = ActorSystem(s"dm-d2-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d2b3c4d5"
    val llm = ScriptLlm(_ => "D2-DONE")
    mkModeRecord(delegateId, DualModeConfig.ModeDispatcher)
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      _ = mo.getOrElse(fail("host must mount"))
      wt <- ChainWriteTool.call(
        nodesJson(nodeJson("a", "task a", Nil, "worktree" -> true.asJson)),
        delegateCtx(delegateId, ws)
      )
      mg <- ChainWriteTool.call(
        nodesJson(nodeJson("a", "task a", Nil, "merge" -> true.asJson)),
        delegateCtx(delegateId, ws)
      )
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(wt.left.exists(_.message.contains("NODE_WORKTREE_UNSUPPORTED")), wt.toString)
      assert(mg.left.exists(_.message.contains("NODE_MERGE_UNSUPPORTED")), mg.toString)
  }

  // ── D3 无键分发器链（登记 + 逐 node 分配物化 + 放行）──────────────────────

  test("D3: a keyless dispatcher chain registers, materializes per-node allocations, and releases the entry") {
    val ws = os.temp.dir(prefix = "nb-dm-d3-", deleteOnExit = false)
    val system = ActorSystem(s"dm-d3-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d3c4d5e6"
    val llm = ScriptLlm(_ => "D3-NODE-DONE")
    mkModeRecord(delegateId, DualModeConfig.ModeDispatcher)
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      ack <- ChainWriteTool
        .call(
          nodesJson(
            nodeJson("survey", "survey the ground", Nil),
            nodeJson("build", "build on the survey", List("survey"))
          ),
          delegateCtx(delegateId, ws)
        )
        .map(_.getOrElse(fail("keyless dispatcher chain must register")))
      entry <- h.store.getNode("survey").map(_.getOrElse(fail("entry must exist")))
      _ <- waitUntil(45.seconds)(
        h.store.getNode("survey").map(n => n.exists(x => nebflow.core.project.NodeLifecycle.Terminal.contains(x.status)))
      )
      done <- h.store.getNode("survey").map(_.getOrElse(fail("entry must exist")))
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(ack.contains("registered with 2 node(s)"), ack)
      assert(ack.contains("dispatcher mode, no worktrees"), s"the ACK carries the dispatcher serialization reminder: $ack")
      // 逐 node 分配物化（空目录短路口 = Nil——与声明集同字段）。
      assertEquals(entry.plugins, Nil, s"empty catalog short-circuit allocates an empty set: ${entry.plugins}")
      assert(
        nebflow.core.project.NodeLifecycle.Terminal.contains(done.status),
        s"the entry actually ran to a terminal state: ${done.status}"
      )
  }

  // ── D4 writeScope 软闸 ─────────────────────────────────────────────────────

  test("D4: unordered overlapping writeScope pairs land a write-scope-collision WARN (advisory only)") {
    val ws = os.temp.dir(prefix = "nb-dm-d4-", deleteOnExit = false)
    val system = ActorSystem(s"dm-d4-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d4d5e6f7"
    val llm = ScriptLlm(_ => "D4-NODE-DONE")
    mkModeRecord(delegateId, DualModeConfig.ModeDispatcher)
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      _ = mo.getOrElse(fail("host must mount"))
      ack <- ChainWriteTool
        .call(
          JsonObject.fromIterable(
            nodesJson(
              nodeJson("docs-a", "write the docs", Nil, "writeScope" -> "docs/".asJson),
              nodeJson("docs-b", "write the api notes", Nil, "writeScope" -> "docs/api".asJson),
              nodeJson("seq", "serialize the writers", List("docs-a"))
            ).toMap + ("chainName" -> "dm-d4-chain".asJson)
          ),
          delegateCtx(delegateId, ws)
        )
        .map(_.getOrElse(fail("the soft gate must not refuse the chain")))
      _ <- waitUntil(10.seconds)(readEvents(ws).map(_.exists(_.contains("write-scope-collision"))))
      events <- readEvents(ws)
      snap <- mo.get.store.snapshot
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(ack.contains("registered with 3 node(s)"), ack)
      assert(
        events.exists(_.contains("write-scope-collision")),
        s"the collision pair must be audited: ${events.filter(_.contains("collision"))}"
      )
      assertEquals(snap.nodes.size, 3, "advisory only — the chain still lands whole")
  }

  // ── D5 NodeEdit 分发器作用域三态 ───────────────────────────────────────────

  test("D5: NodeEdit in dispatcher scope — plugins key refused, keyless create goes through engine allocation") {
    val ws = os.temp.dir(prefix = "nb-dm-d5-", deleteOnExit = false)
    val system = ActorSystem(s"dm-d5-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d5e6f7a8"
    val llm = ScriptLlm(_ => "D5-NODE-DONE")
    mkModeRecord(delegateId, DualModeConfig.ModeDispatcher)
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      refused <- NodeEditTool.call(
        JsonObject(
          "nodename" -> "named-plugins".asJson,
          "task" -> "task with a plugins key".asJson,
          "plugins" -> io.circe.Json.arr()
        ),
        delegateCtx(delegateId, ws)
      )
      ok <- NodeEditTool
        .call(
          JsonObject(
            "nodename" -> "auto-plugins".asJson,
            "task" -> "task without a plugins key".asJson,
            "description" -> "keyless dispatcher create".asJson
          ),
          delegateCtx(delegateId, ws)
        )
        .flatMap {
          case Left(err) =>
            IO.raiseError(new AssertionError(s"keyless dispatcher create failed: ${err.message}"))
          case Right(v) => IO.pure(v)
        }
      node <- h.store.snapshot.map(_.nodes.values.find(_.name == "auto-plugins")).map(_.getOrElse(fail("node must exist")))
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(
        refused.left.exists(_.message.contains(ChainWriteTool.Codes.PluginsDispatcherAllocated)),
        refused.toString
      )
      assertEquals(node.status, nebflow.core.project.NodeLifecycle.Pending, "dispatcher create still lands PENDING (zero auto-start)")
      assertEquals(node.plugins, Nil, "engine-allocated empty set materializes as Nil")
  }

  // ── D6 闸关对照组（行为分叉只在闸 on 的分发器链）─────────────────────────

  test("D6: gate off (no mode on record) — the same plugins-key chain registers normally (current behaviour)") {
    val ws = os.temp.dir(prefix = "nb-dm-d6-", deleteOnExit = false)
    val system = ActorSystem(s"dm-d6-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d6f7a8b9"
    val llm = ScriptLlm(_ => "D6-NODE-DONE")
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      ack <- ChainWriteTool
        .call(
          nodesJson(
            nodeJson("a", "task a", Nil, "plugins" -> io.circe.Json.arr()),
            nodeJson("b", "task b", List("a"))
          ),
          delegateCtx(delegateId, ws)
        )
        .map(_.getOrElse(fail("gate-off chains keep the current behaviour")))
      a <- h.store.getNode("a").map(_.getOrElse(fail("node a must exist")))
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(ack.contains("registered with 2 node(s)"), ack)
      assertEquals(a.plugins, Nil, "explicit empty declaration = no capability face (unchanged)")
  }

  // ── D7 摘要先行（设计 §6 第 4 步 · 宿主存活窗内的链级 sweep + 审计落定）────

  test("D7: flushBeforeUnmount sweeps the terminal chain and lands chain-archived audits while the host is alive") {
    val ws = os.temp.dir(prefix = "nb-dm-d7-", deleteOnExit = false)
    val system = ActorSystem(s"dm-d7-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-d7a8b9c0"
    val llm = ScriptLlm(_ => "D7-NODE-DONE")
    mkModeRecord(delegateId, DualModeConfig.ModeDispatcher)
    for
      res <- mkResources(system, llm.handle)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount"))
      _ <- ChainWriteTool
        .call(
          nodesJson(
            nodeJson("solo", "a single-node chain", Nil)
          ),
          delegateCtx(delegateId, ws)
        )
        .map(_.getOrElse(fail("chain must register")))
      _ <- waitUntil(45.seconds)(
        h.store.getNode("solo").map(n => n.exists(x => nebflow.core.project.NodeLifecycle.Terminal.contains(x.status)))
      )
      before <- h.store.snapshot.map(_.nodes.contains("solo"))
      // 摘要先行：unmount 之前的出库腿（onTerminalCleanup 序列内同位）。
      _ <- nebflow.core.delegate.DelegateFlowMapLifecycle.flushBeforeUnmount(delegateId)
      events <- readEvents(ws)
      after <- h.store.snapshot.map(_.nodes.contains("solo"))
      _ <- nebflow.core.project.DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(before, true, "precondition: the terminal chain is still in the active region (no TtlTick in harness)")
      assert(events.exists(_.contains("chain-archived")), s"the chain-archived audit must land: ${events.mkString("\n")}")
      assertEquals(after, false, "the sweep removes the chain from the active region before the unmount")
  }
end DualModeDispatcherScopeSpec
