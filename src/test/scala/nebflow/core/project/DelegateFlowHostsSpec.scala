package nebflow.core.project

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentKind, AgentRecord, Behaviors}
import nebflow.agent.SharedResources
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.core.tools.FileLockManager
import nebflow.llm.ModelCandidate
import nebflow.shared.{
  Defaults,
  LlmHandle,
  LlmRequest,
  LlmResponse,
  Message,
  MessageRole,
  PathUtil,
  StreamChunk,
  ThinkingConfig
}
import nebflow.shared.given

import scala.concurrent.duration.*

/**
 * N1 刀1 · delegate 作用域宿主验收（设计 v3 §6/A3 + A2 + 候裁⑷；委托链状态机零改动
 * ——全部走既有 NodeEngine 机制，本 spec 只验「宿主化」的正确性）：
 *
 *  - H1 挂载：spawn 腿语义 = 作用域目录落盘 + 自根引擎（rootSessionId = delegate 自己）
 *    + 幂等；非法作用域 id 拒挂（fail-soft）。
 *  - H2 卸载即存：终态卸载 = 引擎实例灭 + 盘面原样（write-through）+ 重挂恢复往返。
 *  - H3 boot sweep 认领续跑（A2）：崩溃残留 running（transcript 在盘）→ 重挂 → 同判据
 *    认领（boot-recovery 事件落基工作区事件流）→ rehydrate 完成；幂等二刷零动作。
 *  - H4 ⑷ creator 不 live＝账本滞留不旁路：delegate 会话不在 ⇒ 节点照常完成、结果
 *    Parked 滞留（nebulaDeliveredAt 空、零旁路投递、零分发器通知）——物理背书 =
 *    状态本来就在盘上。
 *  - H5 creator live：节点完成 → ImmediateInput 投 delegate 会话恰一次 + 记账置位
 *    （自根投递面）。
 *  - H6 孤儿收割：会话不在 ∧ 图内全终态 ⇒ 宿主实例灭；会话在 ⇒ 宿主在。
 *  - H7 终态清理接线：DelegateTerminal.onTerminalCleanup → 宿主卸载（生命周期闭环）。
 *  - 存量归档不迁移（v3-⑧）的 sweep 全链证据：recoverDelegateScopes 只扫 flow-maps/
 *    作用域族——同工作区并存一份 per-project flow-map.json 全程逐字节不动（store 层
 *    证据在 FlowMapScopeStoreSpec S4，此处验 sweep 全链同读数）。
 */
class DelegateFlowHostsSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val tempRoot: os.Path = os.pwd / "target" / "test-delegate-flow-hosts"
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

  // ── 基建（BootCrashRecoverySpec 同款骨架）────────────────────────────────

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

  /** sweep 的 ActorSystem/resources 载体（boot 序 = startupMount 先行的 spec 形态）。 */
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

  /** 播种磁盘 transcript（崩溃前已逐轮落盘的节点会话历史）。 */
  private def seedTranscript(sid: String, msgs: List[Message]): Unit =
    os.write.over(tempRoot / "sessions" / s"$sid.json", msgs.asJson.noSpaces)

  private def msg(role: MessageRole, text: String): Message = Message(role = role, content = Left(text))

  private def hostNode(h: DelegateFlowRuntime, id: String): IO[NodeDef] =
    h.store.snapshot.map(_.nodes.get(id)).map {
      case Some(n) => n
      case None => fail(s"node '$id' must exist in scope ${h.delegateId}")
    }

  private def readEvents(ws: os.Path): IO[List[String]] =
    IO.blocking {
      val f = ws / ".nebflow" / "flow-map-events.jsonl"
      if os.exists(f) then os.read(f).linesIterator.toList else Nil
    }

  /** delegate 宿主挂载（spec 缺省参数定版：确定性腿显式关）。 */
  private def mount(
    ws: os.Path,
    delegateId: String,
    system: ActorSystem,
    res: SharedResources,
    cwd: os.Path = null
  ): IO[Option[DelegateFlowRuntime]] =
    DelegateFlowHosts.mount(
      delegateId = delegateId,
      project = None,
      baseWorkspace = ws.toString,
      engineCwd = (if cwd == null then ws else cwd).toString,
      system = system,
      resources = res,
      wsSend = None,
      reportGateHold = Some(false),
      rootNotifyQuietMs = Some(0),
      destroyWindowMs = Some(0L)
    )

  /** 播种崩溃残留：挂载 → 写入 running 节点（sessionRef 持久）→ 卸载（模拟进程死亡）。 */
  private def seedResidue(
    ws: os.Path,
    delegateId: String,
    nodeId: String,
    sessionRef: Option[String],
    system: ActorSystem,
    res: SharedResources
  ): IO[os.Path] =
    for
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("host must mount for seeding"))
      _ <- h.store.mutate { s =>
        s.copy(nodes =
          s.nodes.updated(
            nodeId,
            NodeDef(
              id = nodeId,
              name = s"node-$nodeId",
              agent = "general",
              task = Some("scope residue task"),
              out = List(OutEdge.root),
              status = NodeLifecycle.Running,
              startedAt = Some(System.currentTimeMillis() - 3_600_000),
              createdAt = System.currentTimeMillis() - 3_600_000,
              sessionRef = sessionRef,
              deliveredTo = Nil
            )
          )
        )
      }.void
      _ <- DelegateFlowHosts.unmount(delegateId)
    yield ws / ".nebflow" / "flow-maps" / delegateId

  // ── H1 挂载 ─────────────────────────────────────────────────────────────

  test("H1: mount lays the scope map on disk, roots the engine at the delegate itself, and is idempotent") {
    val ws = os.temp.dir(prefix = "nb-dfh-h1-", deleteOnExit = false)
    val system = ActorSystem(s"dfh-h1-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-1a2b3c4d"
    for
      res <- mkResources(system, ScriptLlm(_ => "UNUSED").handle)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("mount must succeed for a valid scope id"))
      again <- mount(ws, delegateId, system, res)
      snap <- h.store.snapshot
      mapThere <- IO.blocking(os.exists(ws / ".nebflow" / "flow-maps" / delegateId / "flow-map.json"))
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(h.delegateId, delegateId)
      assertEquals(h.engine.rootSessionId, delegateId, "scope engine is self-rooted (delegate = its own scope root)")
      assertEquals(snap.nodes.size, 0, "fresh scope starts empty")
      assert(mapThere, "scope map first-write lands in flow-maps/<scope>/")
      assert(again.isDefined && (again.get eq h), "second mount returns the same instance (idempotent)")
    end for
  }

  test("H1b: invalid scope id refused fail-soft (delegate continues without a scope map)") {
    val ws = os.temp.dir(prefix = "nb-dfh-h1b-", deleteOnExit = false)
    val system = ActorSystem(s"dfh-h1b-${scala.util.Random.nextInt(1000000)}")
    for
      res <- mkResources(system, ScriptLlm(_ => "UNUSED").handle)
      mo <- mount(ws, "../escape", system, res)
      mounted <- DelegateFlowHosts.isMounted("../escape")
      escaped <- IO.blocking(os.exists(ws / ".nebflow" / "escape"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(mo, None, "invalid id = no host")
      assert(!mounted, "nothing registered")
      assert(!escaped, "no directory escape")
    end for
  }

  // ── H2 卸载即存 ─────────────────────────────────────────────────────────

  test("H2: unmount kills the instance but disk state survives; remount restores the graph (write-through)") {
    val ws = os.temp.dir(prefix = "nb-dfh-h2-", deleteOnExit = false)
    val system = ActorSystem(s"dfh-h2-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-2b3c4d5e"
    for
      res <- mkResources(system, ScriptLlm(_ => "UNUSED").handle)
      mo <- mount(ws, delegateId, system, res)
      h = mo.getOrElse(fail("mount must succeed"))
      _ <- h.store.mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "done-1" -> NodeDef(
              id = "done-1",
              name = "wired",
              agent = "general",
              status = NodeLifecycle.Completed,
              result = Some("wired ok"),
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      _ <- DelegateFlowHosts.unmount(delegateId)
      afterUnmount <- DelegateFlowHosts.isMounted(delegateId)
      mapStillThere <- IO.blocking(os.exists(ws / ".nebflow" / "flow-maps" / delegateId / "flow-map.json"))
      remounted <- FlowMapStore.openScope(delegateId, "proj", ws.toString)
      snap <- remounted.snapshot
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(!afterUnmount, "instance gone after unmount")
      assert(mapStillThere, "disk state kept (write-through)")
      assertEquals(snap.nodes("done-1").result, Some("wired ok"), "remount restores the persisted graph")
    end for
  }

  // ── H3+H4 boot sweep：认领续跑 + creator 不 live＝滞留不旁路 ─────────────

  test(
    "H3/H4: sweep rehydrates a crashed scope node to completion; with the creator dead the result is RETAINED (Parked, no bypass, no dispatcher notify), and a second pass is a no-op"
  ) {
    val ws = os.temp.dir(prefix = "nb-dfh-h3-", deleteOnExit = false)
    val system = ActorSystem(s"dfh-h3-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-3c4d5e6f"
    val sid = "node-h3aa11bb"
    val llm =
      ScriptLlm(text => if text.contains("crashed during your last turn") then "SCOPE-RECOVERED" else "UNEXPECTED")
    for
      res <- mkResources(system, llm.handle)
      _ <- mountCarrier("dfh-carrier-h3", ws, system, res) // sweep 载体（ActorSystem/resources）
      legacy <- FlowMapStore.open("dfh-carrier-h3", ws.toString) // v3-⑧ 对照面：存量 per-project flow-map
      _ <- legacy.mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "legacy-1" -> NodeDef(
              id = "legacy-1",
              name = "legacy",
              agent = "general",
              status = NodeLifecycle.Wiring,
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      legacyBefore <- IO.blocking(os.read(ws / ".nebflow" / "flow-map.json"))
      scopeDir <- seedResidue(ws, delegateId, "n-h3", Some(sid), system, res)
      _ = seedTranscript(sid, List(msg(MessageRole.User, "原始任务"), msg(MessageRole.Assistant, "WIP-HALF")))
      actions <- ProjectCrashRecovery.recoverDelegateScopes(reportGateHold = Some(false), rootNotifyQuietMs = Some(0))
      h0 <- DelegateFlowHosts.get(delegateId)
      h = h0.getOrElse(fail("sweep must leave the residue scope mounted for its rehydrating nodes"))
      _ <- waitUntil(30.seconds)(hostNode(h, "n-h3").map(n => NodeLifecycle.Terminal.contains(n.status)))
      a <- hostNode(h, "n-h3")
      reqs <- llm.fullReqs.get
      events <- readEvents(ws)
      actions2 <- ProjectCrashRecovery.recoverDelegateScopes(reportGateHold = Some(false), rootNotifyQuietMs = Some(0))
      legacyAfter <- IO.blocking(os.read(ws / ".nebflow" / "flow-map.json"))
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(actions, 1, "exactly one scope with recovery actions")
      assertEquals(a.status, NodeLifecycle.Completed, "rehydrated scope node completes")
      assertEquals(a.result, Some("SCOPE-RECOVERED"))
      assert(reqs.exists(_.contains("WIP-HALF")), "resume must hydrate the on-disk transcript")
      assert(
        events.exists(e => e.contains("boot-recovery") && e.contains("n-h3")),
        "claim must be audited (no silent self-healing); event stream = base workspace family"
      )
      // ⑷ creator 不 live：零旁路认账——结果滞留主图，等 delegate 侧消费面（不投 Nebula、
      // 不建第二上送、无分发器汇总）。
      assert(a.nebulaDeliveredAt.isEmpty, "dead creator ⇒ result PARKED (ledger retention, no bypass)")
      assertEquals(actions2, 0, "second sweep pass: no residues left (idempotent)")
      assertEquals(legacyAfter, legacyBefore, "legacy per-project flow-map.json untouched by the scope sweep (v3-⑧)")
      assert(os.exists(scopeDir / "flow-map.json"), "scope map persists under flow-maps/")
    end for
  }

  test("H3b: class-c residue (no sessionRef) fails through the existing deliverFailed chain inside the scope") {
    val ws = os.temp.dir(prefix = "nb-dfh-h3b-", deleteOnExit = false)
    val system = ActorSystem(s"dfh-h3b-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-4d5e6f7a"
    val llm = ScriptLlm(_ => "SHOULD-NOT-RUN")
    for
      res <- mkResources(system, llm.handle)
      _ <- mountCarrier("dfh-carrier-h3b", ws, system, res)
      _ <- seedResidue(ws, delegateId, "n-h3b", None, system, res) // sessionRef 缺失 = (c) 类
      actions <- ProjectCrashRecovery.recoverDelegateScopes(reportGateHold = Some(false), rootNotifyQuietMs = Some(0))
      h0 <- DelegateFlowHosts.get(delegateId)
      h = h0.getOrElse(fail("(c)-claim keeps the host mounted for its failed-notify face"))
      _ <- waitUntil(20.seconds)(hostNode(h, "n-h3b").map(n => NodeLifecycle.Terminal.contains(n.status)))
      a <- hostNode(h, "n-h3b")
      events <- readEvents(ws)
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(actions, 1, "(c)-class handling counts as one action")
      assertEquals(a.status, NodeLifecycle.Failed, "lost transcript ⇒ failed via the existing chain")
      assert(a.result.exists(_.contains("crash recovery")), s"honest failure reason, got: ${a.result}")
      assert(
        events.exists(e => e.contains("boot-recovery") && e.contains("failed (class c)") && e.contains("n-h3b")),
        "class-c failure audited"
      )
    end for
  }

  // ── H5 creator live：自根投递 ───────────────────────────────────────────

  test(
    "H5: with the delegate session live, the completed node delivers to the delegate itself exactly once (self-rooted routing)"
  ) {
    val ws = os.temp.dir(prefix = "nb-dfh-h5-", deleteOnExit = false)
    val system = ActorSystem(s"dfh-h5-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-5e6f7a8b"
    val sid = "node-h5bb22cc"
    val llm = ScriptLlm(_ => "LIVE-DELIVERY")
    for
      res <- mkResources(system, llm.handle)
      _ <- mountCarrier("dfh-carrier-h5", ws, system, res)
      recorded <- registerRecorder(res, system, delegateId, AgentKind.Delegate)
      scopeDir <- seedResidue(ws, delegateId, "n-h5", Some(sid), system, res)
      _ = seedTranscript(sid, List(msg(MessageRole.User, "任务"), msg(MessageRole.Assistant, "half")))
      _ <- ProjectCrashRecovery.recoverDelegateScopes(reportGateHold = Some(false), rootNotifyQuietMs = Some(0))
      h0 <- DelegateFlowHosts.get(delegateId)
      h = h0.getOrElse(fail("host mounted"))
      _ <- waitUntil(30.seconds)(hostNode(h, "n-h5").map(n => NodeLifecycle.Terminal.contains(n.status)))
      a <- hostNode(h, "n-h5")
      imms <- recorded.get.map(_.collect { case m: AgentCommand.ImmediateInput => m })
      _ <- DelegateFlowHosts.unmount(delegateId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(a.status, NodeLifecycle.Completed)
      assertEquals(
        imms.count(_.text.contains("[Node 'node-n-h5' completed]")),
        1,
        "exactly one delivery to the delegate session"
      )
      assert(a.nebulaDeliveredAt.isDefined, "live creator ⇒ ledger marked (delivery fact)")
      val _ = scopeDir
    end for
  }

  // ── H6 孤儿收割 ─────────────────────────────────────────────────────────

  test("H6: orphan reap keeps hosts with live sessions or active graphs; reaps dead+all-terminal hosts") {
    val ws = os.temp.dir(prefix = "nb-dfh-h6-", deleteOnExit = false)
    val system = ActorSystem(s"dfh-h6-${scala.util.Random.nextInt(1000000)}")
    val liveId = "delegate-general-6f7a8b9c"
    val deadId = "delegate-general-7a8b9c0d"
    for
      res <- mkResources(system, ScriptLlm(_ => "UNUSED").handle)
      _ <- registerRecorder(res, system, liveId, AgentKind.Delegate) // live 会话在册
      mL <- mount(ws, liveId, system, res)
      hL = mL.getOrElse(fail("live host must mount"))
      _ <- hL.store.mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "lt" -> NodeDef(
              id = "lt",
              name = "live-terminal",
              agent = "general",
              status = NodeLifecycle.Completed,
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      mD <- mount(ws, deadId, system, res)
      hD = mD.getOrElse(fail("dead host must mount"))
      _ <- hD.store.mutate(s =>
        s.copy(nodes =
          s.nodes ++ Map(
            "dt" -> NodeDef(
              id = "dt",
              name = "dead-terminal",
              agent = "general",
              status = NodeLifecycle.Cancelled,
              createdAt = System.currentTimeMillis()
            )
          )
        )
      )
      reaped <- DelegateFlowHosts.reapOrphans
      liveKept <- DelegateFlowHosts.isMounted(liveId)
      deadKept <- DelegateFlowHosts.isMounted(deadId)
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assertEquals(reaped, 1, "exactly the dead+all-terminal host is reaped")
      assert(liveKept, "live session ⇒ host stays (会话在→宿主在)")
      assert(!deadKept, "dead session ∧ all-terminal graph ⇒ instance gone (disk state kept)")
    end for
  }

  // ── H7 终态清理接线 ─────────────────────────────────────────────────────

  test("H7: DelegateTerminal.onTerminalCleanup unmounts the scope host (lifecycle close-out wiring)") {
    val ws = os.temp.dir(prefix = "nb-dfh-h7-", deleteOnExit = false)
    val system = ActorSystem(s"dfh-h7-${scala.util.Random.nextInt(1000000)}")
    val delegateId = "delegate-general-8b9c0d1e"
    for
      res <- mkResources(system, ScriptLlm(_ => "UNUSED").handle)
      _ <- mount(ws, delegateId, system, res)
      before <- DelegateFlowHosts.isMounted(delegateId)
      _ <- nebflow.core.delegate.DelegateTerminal.onTerminalCleanup(
        res,
        delegateId,
        "parent-1",
        "completed",
        "completed"
      )
      after <- DelegateFlowHosts.isMounted(delegateId)
      mapKept <- IO.blocking(os.exists(ws / ".nebflow" / "flow-maps" / delegateId / "flow-map.json"))
      _ <- system.stopAll.handleErrorWith(_ => IO.unit)
    yield
      assert(before, "host mounted before terminal cleanup")
      assert(!after, "terminal cleanup unmounts the host (engine instance gone)")
      assert(mapKept, "disk state kept after the terminal unmount")
    end for
  }

end DelegateFlowHostsSpec
