package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.delegate.DelegateNotify
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, PathUtil, StreamChunk, ThinkingConfig}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * S19① 通知改向验收（N1 刀3 · 设计 v3 §7.1 + §10.3 刀3「改向送达（creator 不 live＝
 * 滞留不旁路回归）」＋候裁 v3-⑨ 分发器代裁照落）。委托链状态机零改动——全部走既有
 * NodeEngine root 通道机制，本 spec 只验「改向」的正确性与落真面：
 *
 *  - R1 改向送达：creator live ＋ 显式门集 `(pass)Nebula` 边（ChainWrite 末端自动补的
 *    形态）→ ImmediateInput 投创建者 delegate 恰一次 ＋ 记账置位 ＋ `notify-redirected`
 *    审计行落**基工作区**事件流（target=creator-delegate＝NotifyRule.Target 单点消费、
 *    outcome=offered）。
 *  - R2 bare "Nebula"＝零投递标记照旧（v3-⑨ 不破例）：signal 出口标记 → 零注入、只
 *    记账（既有 R1-a 分支零触碰的行为钉）。
 *  - R3 creator 不 live＝滞留不旁路（⑷⑬A）：delegate 不在册 ⇒ Parked（nebulaDeliveredAt
 *    空、**旁观者会话零注入**＝零旁路、审计 outcome=parked）；复活后补投恰一次。
 *  - R4 per-project 面零触碰：ProjectRoot 引擎的通知零 `notify-redirected` 审计行。
 */
class DelegateNotifyRedirectSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-notify-redirect"
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

  /** 捕获型 LLM：终答剧本。 */
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

  /** sweep 的 ActorSystem/resources 载体（per-project 引擎 = ProjectRoot 形态对照）。 */
  private def mountCarrier(name: String, ws: os.Path, system: ActorSystem, res: SharedResources): IO[ProjectRuntime] =
    for
      store <- FlowMapStore.open(name, ws.toString)
      engine = new NodeEngine(
        store,
        system,
        res,
        wsSendFn = (_: io.circe.Json) => IO.unit,
        workspace = ws.toString,
        rootSessionId = "nebula-root",
        projectName = name,
        emitEvent = (_, _, _) => IO.unit,
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
    yield rt

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

  private def seedTranscript(sid: String, msgs: List[Message]): Unit =
    os.write.over(tempRoot / "sessions" / s"$sid.json", msgs.asJson.noSpaces)

  private def msg(role: MessageRole, text: String): Message = Message(role = role, content = Left(text))

  private def hostNode(h: DelegateFlowRuntime, id: String): IO[NodeDef] =
    h.store.snapshot.map(_.nodes.get(id)).map {
      case Some(n) => n
      case None    => fail(s"node '$id' must exist in scope ${h.delegateId}")
    }

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  /** delegate 宿主挂载（生产形态：S19① CreatorDelegate 引擎；确定性腿显式关）。 */
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

  /** 播种运行中节点（sessionRef 持久＋transcript 在盘 = 重水化可完成），out 边可配。 */
  private def seedRunningNode(
      ws: os.Path,
      delegateId: String,
      nodeId: String,
      sessionRef: Option[String],
      out: List[OutEdge],
      system: ActorSystem,
      res: SharedResources
  ): IO[os.Path] =
    for
      h <- mount(ws, delegateId, system, res)
      _ <- h.store.mutate { s =>
        s.copy(nodes =
          s.nodes.updated(
            nodeId,
            NodeDef(
              id = nodeId,
              name = s"node-$nodeId",
              agent = "general",
              task = Some("redirect probe task"),
              out = out,
              status = NodeLifecycle.Running,
              startedAt = Some(System.currentTimeMillis() - 3_600_000),
              createdAt = System.currentTimeMillis() - 3_600_000,
              sessionRef = sessionRef,
              deliveredTo = Nil
            )
          )
        )
      }.void
      _ <- DelegateFlowHosts.unmount(delegateId) // 模拟进程死亡（重挂由 sweep 认领）
    yield ws / ".nebflow" / "flow-maps" / delegateId

  private def rehydrateViaSweep(ws: os.Path, delegateId: String, nodeId: String, sid: String): IO[DelegateFlowRuntime] =
    for
      _ <- IO(seedTranscript(sid, List(msg(MessageRole.User, "原始任务"), msg(MessageRole.Assistant, "WIP-HALF"))))
      _ <- ProjectCrashRecovery.recoverDelegateScopes(reportGateHold = Some(false), rootNotifyQuietMs = Some(0))
      h0 <- DelegateFlowHosts.get(delegateId)
      h = h0.getOrElse(fail("sweep must re-mount the scope for its rehydrating node"))
      _ <- waitUntil(30.seconds)(hostNode(h, nodeId).map(n => NodeLifecycle.Terminal.contains(n.status)))
    yield h

  // ── R1 改向送达 ─────────────────────────────────────────────────────────

  test("R1: explicit (pass)Nebula edge delivers to the live creator delegate exactly once, with the notify-redirected audit landing on the base workspace (NotifyRule consumed)") {
    val ws = os.temp.dir(prefix = "nb-dnr-r1-", deleteOnExit = false)
    val system = ActorSystem(s"dnr-r1-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-11aa22bb"
    val sid = "node-r1cc33dd"
    val llm = ScriptLlm(_ => "REDIRECT-DELIVERED")
    for
      res <- mkResources(system, llm.handle)
      _ <- mountCarrier("dnr-carrier-r1", ws, system, res)
      recorded <- registerRecorder(res, system, delegateId, AgentKind.Delegate)
      _ <- seedRunningNode(ws, delegateId, "n-r1", Some(sid), out = List(OutEdge.root), system, res)
      h <- rehydrateViaSweep(ws, delegateId, "n-r1", sid)
      a <- hostNode(h, "n-r1")
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      events <- readEvents(ws)
      redirects = events.filter(_.contains(NodeEngine.NotifyRedirectedEventType))
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Completed)
      assertEquals(imms.count(_.text.contains("[Node 'node-n-r1' completed]")), 1,
        "v3-⑨: the explicit gate-set Nebula edge remaps to the creator delegate (S19①, no exception)")
      assert(a.nebulaDeliveredAt.isDefined, "delivered ⇒ ledger marked (tell-then-mark unchanged)")
      assertEquals(redirects.size, 1, "exactly one redirect audit line for the one root-channel offer")
      assert(redirects.head.contains(s"target=${DelegateNotify.NotifyRule.Target}"),
        "audit carries NotifyRule.Target=creator-delegate (the constant is consumed, not decorative)")
      assert(redirects.head.contains(s"channel=${DelegateNotify.NotifyRule.Channel}"), "audit carries the ImmediateInput channel")
      assert(redirects.head.contains("outcome=offered"), "audit records the delivery fact")
      assert(redirects.head.contains(delegateId), "audit names the creator scope")
    end for
  }

  // ── R2 bare "Nebula" = 零投递标记照旧 ────────────────────────────────────

  test("R2: bare 'Nebula' (implicit gate = signal exit marker) delivers NOTHING even with the creator live — the ledger-only branch is untouched") {
    val ws = os.temp.dir(prefix = "nb-dnr-r2-", deleteOnExit = false)
    val system = ActorSystem(s"dnr-r2-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-22bb33cc"
    val sid = "node-r2dd44ee"
    val llm = ScriptLlm(_ => "BARE-MARKER")
    for
      res <- mkResources(system, llm.handle)
      _ <- mountCarrier("dnr-carrier-r2", ws, system, res)
      recorded <- registerRecorder(res, system, delegateId, AgentKind.Delegate)
      // bare "Nebula" 形态 = parseOutSegment 隐式门分支的产物：on={pass} + mode=signal。
      _ <- seedRunningNode(
        ws,
        delegateId,
        "n-r2",
        Some(sid),
        out = List(OutEdge(OutEdge.RootTarget, OutEdge.DefaultOn, OutEdge.Signal)),
        system,
        res
      )
      h <- rehydrateViaSweep(ws, delegateId, "n-r2", sid)
      a <- hostNode(h, "n-r2")
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      events <- readEvents(ws)
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Completed, "the node itself completes normally")
      assertEquals(imms.size, 0, "zero-delivery marker: bare Nebula never notifies (R1-a branch byte-identical)")
      assert(a.nebulaDeliveredAt.isDefined, "signal branch still marks the ledger (existing semantics)")
      assert(
        !events.exists(_.contains(NodeEngine.NotifyRedirectedEventType)),
        "no redirect audit for a leg that never offers the root channel"
      )
    end for
  }

  // ── R3 creator 不 live＝滞留不旁路 ＋ 复活补投 ───────────────────────────

  test("R3: dead creator ⇒ PARKED with zero bystander delivery and outcome=parked audit; revival redelivers exactly once") {
    val ws = os.temp.dir(prefix = "nb-dnr-r3-", deleteOnExit = false)
    val system = ActorSystem(s"dnr-r3-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-33cc44dd"
    val sid = "node-r3ee55ff"
    val llm = ScriptLlm(_ => "PARKED-THEN-REVIVED")
    for
      res <- mkResources(system, llm.handle)
      _ <- mountCarrier("dnr-carrier-r3", ws, system, res)
      // 旁观者 = 顶层 root 会话：改向的世界里它必须一事不知（零旁路）。
      bystander <- registerRecorder(res, system, "nebula-root", AgentKind.Root)
      _ <- seedRunningNode(ws, delegateId, "n-r3", Some(sid), out = List(OutEdge.root), system, res)
      h <- rehydrateViaSweep(ws, delegateId, "n-r3", sid)
      a <- hostNode(h, "n-r3")
      bystanderBefore <- bystander.get
      events <- readEvents(ws)
      parkedAudit = events.filter(l => l.contains(NodeEngine.NotifyRedirectedEventType) && l.contains("outcome=parked"))
      // 复活：delegate 会话回注册表 → 补投扫描补投。
      revived <- registerRecorder(res, system, delegateId, AgentKind.Delegate)
      redelivered <- h.engine.redeliverUnconsumedRootResults()
      _ <- waitUntil(10.seconds)(revived.get.map(_.exists {
            case m: AgentCommand.ImmediateInput => m.text.contains("[Node 'node-n-r3' completed]")
            case _                              => false
          }))
      imms <- revived.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      a2 <- hostNode(h, "n-r3")
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Completed, "the node completes regardless of the creator's liveness")
      assert(a.nebulaDeliveredAt.isEmpty, "dead creator ⇒ ledger NOT marked (Parked retention, redelivery candidate kept)")
      assertEquals(bystanderBefore.size, 0, "zero bypass: the bystander root session receives nothing (⑬A)")
      assertEquals(parkedAudit.size, 1, "the parked offer is audited (outcome=parked)")
      // 复活补投：恰一次 ＋ 记账置位。
      assertEquals(redelivered, 1, "the redelivery scan picks up exactly the parked result")
      assertEquals(imms.count(_.text.contains("[Node 'node-n-r3' completed]")), 1, "revival ⇒ delivered exactly once")
      assert(a2.nebulaDeliveredAt.isDefined, "post-revival ledger marked")
    end for
  }

  // ── R4 per-project 面零触碰 ──────────────────────────────────────────────

  test("R4: the per-project engine (ProjectRoot) never writes the notify-redirected audit line") {
    val ws = os.temp.dir(prefix = "nb-dnr-r4-", deleteOnExit = false)
    val system = ActorSystem(s"dnr-r4-${scala.util.Random.nextInt(1000000)}")
    for
      res <- mkResources(system, ScriptLlm(_ => "UNUSED").handle)
      rt <- mountCarrier("dnr-carrier-r4", ws, system, res)
      // 直接驱动 root 通道 offer 单点（Parked 形态：无任何在册会话）——per-project
      // 引擎必须零审计零行为变化。
      _ <- rt.engine.offerRootNotify("probe", "n-r4", "completed", Some("n-r4"))
      events <- readEvents(ws)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(
        events.count(_.contains(NodeEngine.NotifyRedirectedEventType)),
        0,
        "per-project face: zero notify-redirected audit (the redirect is delegate-scope-only)"
      )
    end for
  }

end DelegateNotifyRedirectSpec
