package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.project.{
  DelegateFlowHosts,
  DelegateFlowRuntime,
  FlowMapStore,
  NodeDef,
  NodeLifecycle,
  OutEdge,
  ProjectDef,
  ProjectRuntime,
  ProjectRuntimeRegistry
}
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk, ThinkingConfig}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * S6=C Mail `node:` 子地址验收（N1 刀3 · 设计 v3 §7.2；候裁无异议面照落）：
 *
 *  - M1..M4 delegate 作用域**双寻址四态**（A5 序，与 NodeEdit/NodeList/NodeCancel
 *    同一判据源 NodeTools.resolveNodeRefIO）：id 精确命中 / name 唯一命中 / 歧义列
 *    候选（CHAIN_ADDR_AMBIGUOUS，不猜）/ 零命中（CHAIN_NODE_NOT_FOUND）。
 *  - M5 引擎侧**三态**经 delegate 腿逐字保留（v3 §7.2「底座红利」零改动钉）：
 *    running 注入 / pending 追加 brief / 终态显式拒。
 *  - M6 root 授权腿（v3 §7.2 ①授权面扩）：跨已挂载 delegate 作用域解析——唯一命中
 *    投属主域；跨域歧义 = scope 限定候选；零命中 = 显式未找到。
 *  - M7 分发器 per-project 腿逐字节不变面 + 同一双寻址单点（id 与 name 皆达）。
 *  - M8 teamish 越界面保留（既有闸零放宽）。
 *  - M9 挂载面：delegate 会话工具面含 Mail（S6=C 授权扩）；非 delegate 的 nebflow
 *    面不含（零 over-grant）。
 */
class MailNodeSubAddressSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-mail-node-subaddress"
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
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

  override def afterEach(context: munit.AfterEach): Unit =
    (ProjectRuntimeRegistry.clear *> DelegateFlowHosts.clear).unsafeRunSync()

  // ── 基建（DelegateFlowHostsSpec 同款骨架）────────────────────────────────

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

  private class ScriptLlm(respond: String => String):

    def handle: LlmHandle[IO] = new LlmHandle[IO]:
      def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
      def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]]
      ): Stream[IO, StreamChunk] =
        Stream(StreamChunk.TextDelta(respond("")), StreamChunk.Done(None, None))

  private def registerRecorder(
    res: SharedResources,
    system: ActorSystem,
    sid: String,
    kind: AgentKind
  ): IO[Ref[IO, List[AgentCommand]]] =
    for
      recorded <- Ref.of[IO, List[AgentCommand]](Nil)
      ref <- system.spawn(recorderBehavior(recorded), s"rec-$sid-${scala.util.Random.nextInt(1000000)}")
      _ <- res.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, kind, sid)))
    yield recorded

  private def recorderBehavior(recorded: Ref[IO, List[AgentCommand]]): nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](msg => recorded.update(_ :+ msg).as(b))
    b

  /** delegate 作用域宿主挂载（确定性腿显式关）。 */
  private def mount(
    ws: os.Path,
    delegateId: String,
    system: ActorSystem,
    res: SharedResources
  ): IO[DelegateFlowRuntime] =
    DelegateFlowHosts
      .mount(
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
      .map(_.getOrElse(fail("host must mount")))

  /** 作用域内播种一个节点（状态/名字/sessionRef 可配）。 */
  private def seedNode(
    h: DelegateFlowRuntime,
    id: String,
    name: String,
    status: String,
    sessionRef: Option[String] = None
  ): IO[Unit] =
    h.store.mutate { s =>
      s.copy(nodes =
        s.nodes ++ Map(
          id -> NodeDef(
            id = id,
            name = name,
            agent = "general",
            task = Some(s"task of $name"),
            out = List(OutEdge.root),
            status = status,
            sessionRef = sessionRef,
            createdAt = System.currentTimeMillis()
          )
        )
      )
    }.void

  private def nodeOf(h: DelegateFlowRuntime, id: String): IO[NodeDef] =
    h.store.snapshot.map(_.nodes.get(id)).map {
      case Some(n) => n
      case None => fail(s"node '$id' must exist in scope ${h.delegateId}")
    }

  private def delegateCtx(system: ActorSystem, res: SharedResources, sid: String): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(sid),
      sharedResources = Some(res),
      actorSystem = Some(system)
    )

  private def mail(address: String, ctx: ToolContext): IO[Either[nebflow.core.tools.ToolError, String]] =
    MailTool.call(
      io.circe.JsonObject(
        "address" -> io.circe.Json.fromString(address),
        "message" -> io.circe.Json.fromString("supplement text")
      ),
      ctx
    )

  // ── M1..M5 delegate 作用域腿（双寻址四态 + 三态）──────────────────────────

  test(
    "M1..M4: the delegate leg resolves id-first, unique name, explicit ambiguity, explicit miss (A5 order on its own scope)"
  ) {
    val ws = os.temp.dir(prefix = "nb-mns-m1-", deleteOnExit = false)
    val system = ActorSystem(s"mns-m1-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-11aa22bb"
    val nodeSid = "node-m1aa11bb"
    val llm = ScriptLlm(_ => "UNUSED")
    for
      res <- mkResources(system, llm.handle)
      h <- mount(ws, delegateId, system, res)
      live <- registerRecorder(res, system, nodeSid, AgentKind.Flow)
      _ <- seedNode(h, "n-run", "runner", NodeLifecycle.Running, sessionRef = Some(nodeSid))
      _ <- seedNode(h, "n-b1", "dup", NodeLifecycle.Pending)
      _ <- seedNode(h, "n-b2", "dup", NodeLifecycle.Pending)
      ctx = delegateCtx(system, res, delegateId)
      byId <- mail("node:n-run", ctx)
      byName <- mail("node:runner", ctx)
      ambiguous <- mail("node:dup", ctx)
      miss <- mail("node:ghost", ctx)
      injects <- live.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(byId.isRight, s"id hit must deliver, got: ${byId.left.map(_.message)}")
      assert(
        byId.toOption.get.contains("running") || byId.toOption.get.toLowerCase.contains("injected"),
        s"id-hit receipt names the running inject: ${byId.toOption.get}"
      )
      assertEquals(byName, byId, "unique name hit rides the same leg as the id hit")
      assertEquals(
        injects.count(_.text.contains("[NODE-MESSAGE]")),
        2,
        "exactly two live injections (id + name addressing of the same node)"
      )
      assert(
        ambiguous.left.exists(_.message.contains("CHAIN_ADDR_AMBIGUOUS")),
        s"ambiguous name must list candidates: ${ambiguous.left.map(_.message)}"
      )
      assert(
        ambiguous.left.exists(m => m.message.contains("n-b1") && m.message.contains("n-b2")),
        "the ambiguity error lists both candidates"
      )
      assert(
        miss.left.exists(_.message.contains("CHAIN_NODE_NOT_FOUND")),
        s"zero hit is explicit: ${miss.left.map(_.message)}"
      )
    end for
  }

  test(
    "M5: the engine three states survive verbatim through the delegate leg (running inject / pending brief append / terminal refuse)"
  ) {
    val ws = os.temp.dir(prefix = "nb-mns-m5-", deleteOnExit = false)
    val system = ActorSystem(s"mns-m5-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-55ee66ff"
    val nodeSid = "node-m5cc77dd"
    val llm = ScriptLlm(_ => "UNUSED")
    for
      res <- mkResources(system, llm.handle)
      h <- mount(ws, delegateId, system, res)
      live <- registerRecorder(res, system, nodeSid, AgentKind.Flow)
      _ <- seedNode(h, "n-run", "runner", NodeLifecycle.Running, sessionRef = Some(nodeSid))
      _ <- seedNode(h, "n-pend", "waiter", NodeLifecycle.Pending)
      _ <- seedNode(h, "n-done", "finisher", NodeLifecycle.Completed)
      ctx = delegateCtx(system, res, delegateId)
      toRunning <- mail("node:runner", ctx)
      toPending <- mail("node:waiter", ctx)
      toTerminal <- mail("node:finisher", ctx)
      injects <- live.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      pend <- nodeOf(h, "n-pend")
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(toRunning.isRight)
      assertEquals(injects.count(_.text.contains("[NODE-MESSAGE]")), 1, "running ⇒ turn-boundary injection")
      assert(
        toPending.toOption.get.contains("Message appended"),
        s"pending ⇒ brief append receipt: ${toPending.toOption.get}"
      )
      assert(pend.task.exists(_.contains("supplement text")), "pending append lands the message in the node brief")
      assert(pend.task.exists(_.contains("NodeMessage")), "append uses the existing「分发器补充（NodeMessage）」section")
      assert(
        toTerminal.left.exists(_.message.contains("NODE_TERMINAL_NO_MESSAGE")),
        s"terminal ⇒ explicit refuse: ${toTerminal.left.map(_.message)}"
      )
    end for
  }

  // ── M6 root 跨作用域腿 ──────────────────────────────────────────────────

  test(
    "M6: the root leg resolves across mounted delegate scopes — unique hit routes, cross-scope ambiguity lists scope-qualified candidates, miss is explicit"
  ) {
    val ws = os.temp.dir(prefix = "nb-mns-m6-", deleteOnExit = false)
    val system = ActorSystem(s"mns-m6-${scala.util.Random.nextInt(1000000)}")
    val scopeA = "delegate-general-66ff77aa"
    val scopeB = "delegate-general-77aa88bb"
    val sidA = "node-m6aa88ee"
    val sidB = "node-m6bb99ff"
    val llm = ScriptLlm(_ => "UNUSED")
    for
      res <- mkResources(system, llm.handle)
      hA <- mount(ws, scopeA, system, res)
      hB <- mount(ws, scopeB, system, res)
      liveA <- registerRecorder(res, system, sidA, AgentKind.Flow)
      _ <- seedNode(hA, "n-a", "lonely", NodeLifecycle.Running, sessionRef = Some(sidA))
      _ <- seedNode(hA, "n-clash-a", "clash", NodeLifecycle.Pending)
      _ <- seedNode(hB, "n-clash-b", "clash", NodeLifecycle.Pending)
      rootCtx = delegateCtx(system, res, "").copy(agentDef =
        Some(nebflow.actor.AgentDef(name = "Nebula", description = "spec fixture"))
      )
      unique <- mail("node:lonely", rootCtx)
      injects <- liveA.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      ambiguous <- mail("node:clash", rootCtx)
      miss <- mail("node:nowhere", rootCtx)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(unique.isRight, s"unique cross-scope hit must deliver: ${unique.left.map(_.message)}")
      assertEquals(injects.count(_.text.contains("[NODE-MESSAGE]")), 1, "delivered to the owning scope's live node")
      assert(ambiguous.left.exists(_.message.contains("CHAIN_ADDR_AMBIGUOUS")), "cross-scope ambiguity is explicit")
      assert(
        ambiguous.left.exists(m => m.message.contains(scopeA) && m.message.contains(scopeB)),
        "candidates are scope-qualified"
      )
      assert(miss.left.exists(_.message.contains("CHAIN_NODE_NOT_FOUND")), "cross-scope miss is explicit")
    end for
  }

  // ── M7 分发器 per-project 腿（双寻址 + 行为零漂移）────────────────────────

  test("M7: the dispatcher per-project leg keeps its face and gains the same dual-addressing single point") {
    val ws = os.temp.dir(prefix = "nb-mns-m7-", deleteOnExit = false)
    val system = ActorSystem(s"mns-m7-${scala.util.Random.nextInt(1000000)}")
    val nodeSid = "node-m7cc00aa"
    val llm = ScriptLlm(_ => "UNUSED")
    for
      res <- mkResources(system, llm.handle)
      store <- FlowMapStore.open("mns-proj", ws.toString)
      engine = new nebflow.core.project.NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: io.circe.Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = "mns-proj",
        emitEvent = (_, _, _) => IO.unit,
        reportGateHold = Some(false)
      )
      pd = ProjectDef(
        name = "mns-proj",
        workspace = ws.toString,
        agentFile = (ws / "AGENTS.md").toString,
        createdAt = 0L
      )
      rt = ProjectRuntime(pd, store, engine, system, res, None)
      _ <- ProjectRuntimeRegistry.register(rt)
      live <- registerRecorder(res, system, nodeSid, AgentKind.Flow)
      _ <- store.mutate { s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "n-x" -> NodeDef(
              id = "n-x",
              name = "worker",
              agent = "general",
              task = Some("t"),
              out = List(OutEdge.root),
              status = NodeLifecycle.Running,
              sessionRef = Some(nodeSid),
              createdAt = System.currentTimeMillis()
            )
          )
        )
      }.void
      dispCtx = ToolContext(
        projectRoot = os.pwd.toString,
        sessionId = Some("disp-sid"),
        sharedResources = Some(res),
        actorSystem = Some(system),
        isDispatcher = true,
        projectName = Some("mns-proj")
      )
      byId <- mail("node:n-x", dispCtx)
      byName <- mail("node:worker", dispCtx)
      injects <- live.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      _ <- ProjectRuntimeRegistry.clear
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(byId.isRight, s"per-project id hit unchanged: ${byId.left.map(_.message)}")
      assert(byName.isRight, s"per-project name hit via the same single point: ${byName.left.map(_.message)}")
      assertEquals(injects.count(_.text.contains("[NODE-MESSAGE]")), 2)
    end for
  }

  // ── M8 teamish 越界面保留 ────────────────────────────────────────────────

  test("M8: teamish senders keep the explicit out-of-face refusal on node: (no widening)") {
    val system = ActorSystem(s"mns-m8-${scala.util.Random.nextInt(1000000)}")
    val res = mkResources(system, ScriptLlm(_ => "UNUSED").handle).unsafeRunSync()
    try
      val ctx = ToolContext(
        projectRoot = os.pwd.toString,
        sessionId = Some("worker-sid"),
        sharedResources = Some(res),
        actorSystem = Some(system)
      )
      val r = mail("node:n-1", ctx).unsafeRunSync()
      assert(r.left.exists(_.message.contains("outside your address face")), s"${r.left.map(_.message)}")
      assert(r.left.exists(_.message.contains("team/agent")), "the error names the teamish legal face")
    finally system.stopAll.handleErrorWith(_ => IO.unit).unsafeRunSync()
  }

  // ── M9 挂载面（授权扩＝创建者 delegate；零 over-grant）────────────────────
  // buildAllowedToolSet 是 AgentCore 的 protected 面（private[agent]）——挂载面
  // 断言（delegateSession=true ⇒ +Mail；缺省 false 零增量）钉在
  // AllowedToolSetSpec（nebflow.agent 包，CoreProbe 同款探针），与本 spec 的
  // M1..M8 工具内地址面验收互补成双保险的两段。

end MailNodeSubAddressSpec
