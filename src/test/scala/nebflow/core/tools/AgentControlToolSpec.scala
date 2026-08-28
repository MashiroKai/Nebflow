package nebflow.core.tools

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.agent.*
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * AgentControl spec §4 / §6 B2：守卫矩阵 + list/status 渲染 + cancel/restart 分支。
 *
 * 真实 agentRegistry（Ref）+ 真实 SubAgentTaskStore + probe actor（记录命令的
 * 最小 behavior）——不 spawn 真 AgentActor（全链路由 AgentControlE2ESpec 覆盖）。
 */
class AgentControlToolSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 30.seconds

  private def mkRecordingCmd(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def mkRecordingEvt(record: Ref[IO, List[AgentEvent]]): Behavior[AgentEvent] =
    def loop: Behavior[AgentEvent] =
      Behaviors.receiveMessage(evt => record.update(_ :+ evt).as(loop))
    loop

  private def mkResources(system: ActorSystem, tmp: os.Path): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = null,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def mkTask(parentSid: String, childSid: String, agent: String = "Worker"): SubAgentTask =
    SubAgentTask(
      taskId = childSid,
      parentSessionId = parentSid,
      agentName = agent,
      prompt = "some long running prompt",
      description = "guard test task",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def ctx(resources: SharedResources, sessionId: String): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(sessionId),
      sharedResources = Some(resources)
    )

  private def call(resources: SharedResources, sessionId: String, action: String, target: String = "", reason: String = "", confirm: Boolean = false): Either[ToolError, String] =
    val input = io.circe.JsonObject(
      "action" -> action.asJson,
      "sessionId" -> target.asJson,
      "reason" -> reason.asJson,
      "confirm" -> confirm.asJson
    )
    AgentControlTool.call(input, ctx(resources, sessionId)).unsafeRunSync()

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  // ── list / status 渲染 ─────────────────────────────────────

  test("list renders rows (kind/status/agent/task) and marks read-only kinds") {
    val system = ActorSystem("ac-list")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask("root-1", "delegate-Explorer-aaaa1111", "Explorer"))
      delRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "list-del")
      teamRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "list-team")
      now = System.currentTimeMillis()
      _ <- resources.agentRegistry.set(Map(
        "delegate-Explorer-aaaa1111" -> AgentRecord(
          "delegate-Explorer-aaaa1111", delRef, AgentKind.Delegate, "root-1",
          startedAt = now - 120_000, status = AgentStatus.Processing, lastActivityMs = now - 45_000
        ),
        "team-nebflow-project-Backend" -> AgentRecord(
          "team-nebflow-project-Backend", teamRef, AgentKind.Team, "team-nebflow-project-Backend"
        )
      ))
      res <- AgentControlTool.call(
        io.circe.JsonObject("action" -> "list".asJson), ctx(resources, "root-1")
      )
    yield
      val out = res.toOption.get
      assert(out.contains("delegate-Explorer-aaaa1111"), s"row must render sessionId:\n$out")
      assert(out.contains("Delegate"), s"kind column:\n$out")
      assert(out.contains("Team"), s"team row:\n$out")
      // Block 2（§C1）：root 桶调用者可全局管 Team——Team 行不再标 read-only
      val teamLine = out.linesIterator.find(_.contains("team-nebflow-project-Backend")).get
      assert(!teamLine.contains("read-only"), s"Team row must not be read-only for a root caller:\n$teamLine")
      assert(out.contains("Explorer"), s"agent name from task record:\n$out")
      assert(out.contains("guard test task"), s"task description column:\n$out")
      assert(out.contains("2m0s"), s"up column from startedAt (120s → 2m0s):\n$out")
      assert(out.contains("45s"), s"idle column from lastActivityMs (45s):\n$out")
    program.guarantee(system.stopAll.attempt.void)
  }

  test("status renders detail card for a live record") {
    val system = ActorSystem("ac-status")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask("root-2", "delegate-Worker-bbbb2222"))
      delRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "status-del")
      _ <- resources.agentRegistry.set(Map(
        "delegate-Worker-bbbb2222" -> AgentRecord(
          "delegate-Worker-bbbb2222", delRef, AgentKind.Delegate, "root-2",
          startedAt = System.currentTimeMillis() - 30_000, status = AgentStatus.Processing,
          lastActivityMs = System.currentTimeMillis() - 5_000, parentSessionId = "root-2"
        )
      ))
      res <- IO(call(resources, "root-2", "status", target = "delegate-Worker-bbbb2222"))
    yield
      val out = res.toOption.get
      assert(out.contains("kind: Delegate"), s"$out")
      assert(out.contains("status: Processing"), s"$out")
      assert(out.contains("task.status: running"), s"$out")
      assert(out.contains("manageable: cancel / restart"), s"$out")
    program.guarantee(system.stopAll.attempt.void)
  }

  test("status Team member for a root-bucket caller shows manageable (Block 2 §C3 — not read-only)") {
    val system = ActorSystem("ac-status-team")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      memRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "status-team-mem")
      _ <- resources.agentRegistry.set(Map(
        "team-member-x" -> AgentRecord(
          "team-member-x", memRef, AgentKind.Team, "root-t1",
          startedAt = System.currentTimeMillis() - 30_000, status = AgentStatus.Processing,
          lastActivityMs = System.currentTimeMillis() - 5_000, parentSessionId = "mgr-inst"
        )
      ))
      res <- IO(call(resources, "root-t1", "status", target = "team-member-x"))
    yield
      val out = res.toOption.get
      assert(out.contains("kind: Team"), s"$out")
      // v2：root 全局可管 Team——manage 行不得沿用 v1 的恒 read-only
      assert(out.contains("manageable: cancel / restart"), s"$out")
      assert(!out.contains("read-only kind"), s"Team must not render read-only for a root caller:\n$out")
    program.guarantee(system.stopAll.attempt.void)
  }

  test("status detects orphan task files (registry empty, task still running)") {
    val system = ActorSystem("ac-orphan")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask("root-3", "delegate-Ghost-cccc3333", "Ghost"))
      res <- IO(call(resources, "root-3", "status", target = "delegate-Ghost-cccc3333"))
    yield
      val out = res.toOption.get
      assert(out.contains("Orphan task"), s"$out")
      assert(out.contains("status=running"), s"$out")
    program.guarantee(system.stopAll.attempt.void)
  }

  // ── cancel 守卫（§4 矩阵）───────────────────────────────────

  test("cancel on unknown sessionId → error listing manageable sessions") {
    val system = ActorSystem("ac-guard-unknown")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      res <- IO(call(resources, "root-x", "cancel", target = "delegate-nobody"))
    yield
      assert(res.isLeft)
      val msg = res.fold(e => e.message, _ => "")
      assert(msg.contains("No live agent"), msg)
      assert(msg.contains("(none currently)"), msg)
    program.guarantee(system.stopAll.attempt.void)
  }

  test("cancel self / Root / Flow / cross-root are rejected; Team by a root-bucket caller now proceeds (Block 2)") {
    val system = ActorSystem("ac-guard-kinds")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      rootRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "g-root")
      teamRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "g-team")
      flowRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "g-flow")
      delRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "g-del")
      _ <- resources.agentRegistry.set(Map(
        "self-session" -> AgentRecord("self-session", rootRef, AgentKind.Root, "self-session"),
        // 同桶（root=self-session）但 kind=Root —— 走 kind 拒绝而非权限拒绝
        "another-root" -> AgentRecord("another-root", rootRef, AgentKind.Root, "self-session"),
        // Block 2：同桶 Team、非注册 Manager（TeamSessionRegistry 空）→ root 桶调用者放行
        "team-nebflow-project-Backend" -> AgentRecord("team-nebflow-project-Backend", teamRef, AgentKind.Team, "self-session"),
        // 同桶 Flow —— cancelFlow 指引
        "dag-step1" -> AgentRecord("dag-step1", flowRef, AgentKind.Flow, "self-session"),
        // 跨桶 Delegate —— Permission denied（在 kind 检查之前）
        "delegate-Other-hhhh0000" -> AgentRecord("delegate-Other-hhhh0000", delRef, AgentKind.Delegate, "other-root")
      ))
      selfRes <- IO(call(resources, "self-session", "cancel", target = "self-session"))
      rootRes <- IO(call(resources, "self-session", "cancel", target = "another-root"))
      teamRes <- IO(call(resources, "self-session", "cancel", target = "team-nebflow-project-Backend"))
      flowRes <- IO(call(resources, "self-session", "cancel", target = "dag-step1"))
      crossRes <- IO(call(resources, "self-session", "cancel", target = "delegate-Other-hhhh0000"))
    yield
      assert(selfRes.left.exists(_.message.contains("Self-guard")), selfRes.toString)
      assert(rootRes.left.exists(_.message.contains("cannot be managed")), rootRes.toString)
      // Block 2：root 桶调用者对 Team 全局 cancel 放行（v1.0 旧断言「read-only 拒绝」作废）
      assert(teamRes.isRight, s"root-bucket caller must be able to cancel a Team member (Block 2 §C3), got: $teamRes")
      assert(flowRes.left.exists(_.message.contains("cancelFlow")), flowRes.toString)
      assert(crossRes.left.exists(_.message.contains("Permission denied")), crossRes.toString)
    program.guarantee(system.stopAll.attempt.void)
  }

  // ── cancel 路径 ────────────────────────────────────────────

  test("cancel via supervisorRef sends AgentEvent.Cancelled to the supervisor") {
    val system = ActorSystem("ac-cancel-sup")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      supEvents <- Ref.of[IO, List[AgentEvent]](Nil)
      supRef <- system.spawn(mkRecordingEvt(supEvents), "sup-evt")
      delRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "cancel-sup-del")
      _ <- resources.agentRegistry.set(Map(
        "delegate-Worker-dddd4444" -> AgentRecord(
          "delegate-Worker-dddd4444", delRef, AgentKind.Delegate, "root-9",
          supervisorRef = Some(supRef), parentSessionId = "root-9"
        )
      ))
      res <- IO(call(resources, "root-9", "cancel", target = "delegate-Worker-dddd4444", reason = "obsolete"))
      _ <- waitUntil(3.seconds)(supEvents.get.map(_.nonEmpty))
      events <- supEvents.get
    yield
      assert(res.isRight, res.toString)
      val cancelled = events.collect { case c: AgentEvent.Cancelled => c }
      assertEquals(cancelled.size, 1, s"supervisor must receive exactly one Cancelled, got: $events")
      assertEquals(cancelled.head.sessionId, "delegate-Worker-dddd4444")
      assertEquals(cancelled.head.reason, "obsolete")
    program.guarantee(system.stopAll.attempt.void)
  }

  test("cancel degraded path (no supervisor): parent notified, task cancelled, registry removed, child stopped") {
    val system = ActorSystem("ac-cancel-deg")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask("root-5", "subtask-Worker-eeee5555"))
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "deg-parent")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkRecordingCmd(childEvents), "deg-child")
      _ <- resources.agentRegistry.set(Map(
        "subtask-Worker-eeee5555" -> AgentRecord(
          "subtask-Worker-eeee5555", childRef, AgentKind.SubTask, "root-5",
          parentRef = Some(parentRef), parentSessionId = "root-5"
        )
      ))
      res <- IO(call(resources, "root-5", "cancel", target = "subtask-Worker-eeee5555", reason = "fallback"))
      _ <- waitUntil(3.seconds)(
        resources.subAgentTaskStore.loadTasks("root-5").map(_.exists(t => t.taskId == "subtask-Worker-eeee5555" && t.status == "cancelled"))
      )
      _ <- waitUntil(3.seconds)(resources.agentRegistry.get.map(!_.contains("subtask-Worker-eeee5555")))
      _ <- waitUntil(3.seconds)(childEvents.get.map(_.exists(_.isInstanceOf[AgentCommand.Stop])))
      tasks <- resources.subAgentTaskStore.loadTasks("root-5")
      childCmds <- childEvents.get
      parentCmds <- parentEvents.get
    yield
      assert(res.isRight, res.toString)
      assert(res.toOption.get.contains("fallback path"), res.toOption.get)
      val ext = parentCmds.collectFirst { case e: AgentCommand.ExternalEvent => e }
      assert(ext.isDefined, s"parent must be notified, got: $parentCmds")
      assertEquals(ext.get.source, "subtask")
      assertEquals(ext.get.eventType, "cancelled")
      assertEquals(ext.get.metadata("cancelled").flatMap(_.asBoolean), Some(true))
      val task = tasks.find(_.taskId == "subtask-Worker-eeee5555").get
      assertEquals(task.status, "cancelled")
      // Block 2（§C3-4）：取消留痕带调用者署名（ctx 无 agentDef → callerLabel 回退值）
      assert(task.lastError.exists(_.contains("cancelled by AgentControl caller")), s"${task.lastError}")
      assert(childCmds.exists(_.isInstanceOf[AgentCommand.Stop]), s"child must receive Stop: $childCmds")
    program.guarantee(system.stopAll.attempt.void)
  }

  // ── restart 守卫与路径 ─────────────────────────────────────

  test("restart on Ephemeral kind is rejected with cancel guidance") {
    val system = ActorSystem("ac-restart-eph")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      ephRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "eph-child")
      _ <- resources.agentRegistry.set(Map(
        "ephemeral-Mailer-ffff6666" -> AgentRecord("ephemeral-Mailer-ffff6666", ephRef, AgentKind.Ephemeral, "root-7")
      ))
      res <- IO(call(resources, "root-7", "restart", target = "ephemeral-Mailer-ffff6666"))
    yield
      assert(res.isLeft)
      val msg = res.fold(e => e.message, _ => "")
      assert(msg.contains("restart is not supported"), msg)
      assert(msg.contains("Cancel"), msg)
    program.guarantee(system.stopAll.attempt.void)
  }

  test("restart happy path: task → restarting + Stop sent to the child (supervisor untouched)") {
    val system = ActorSystem("ac-restart-happy")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      _ <- resources.subAgentTaskStore.recordTask(mkTask("root-8", "delegate-Worker-77778888"))
      supEvents <- Ref.of[IO, List[AgentEvent]](Nil)
      supRef <- system.spawn(mkRecordingEvt(supEvents), "rh-sup")
      childEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      childRef <- system.spawn(mkRecordingCmd(childEvents), "rh-child")
      _ <- resources.agentRegistry.set(Map(
        "delegate-Worker-77778888" -> AgentRecord(
          "delegate-Worker-77778888", childRef, AgentKind.Delegate, "root-8",
          supervisorRef = Some(supRef), parentSessionId = "root-8"
        )
      ))
      res <- IO(call(resources, "root-8", "restart", target = "delegate-Worker-77778888", reason = "stuck turn"))
      _ <- waitUntil(3.seconds)(
        resources.subAgentTaskStore.loadTasks("root-8").map(_.exists(t => t.taskId == "delegate-Worker-77778888" && t.status == "restarting"))
      )
      _ <- waitUntil(3.seconds)(childEvents.get.map(_.exists {
        case AgentCommand.Stop(r) => r.contains("agent-control-restart")
        case _ => false
      }))
      tasks <- resources.subAgentTaskStore.loadTasks("root-8")
      childCmds <- childEvents.get
      supEvts <- supEvents.get
    yield
      assert(res.isRight, res.toString)
      assert(res.toOption.get.contains("resume from the last persisted checkpoint"), res.toOption.get)
      val task = tasks.find(_.taskId == "delegate-Worker-77778888").get
      assertEquals(task.status, "restarting")
      assert(childCmds.exists { case AgentCommand.Stop(r) => r.contains("stuck turn"); case _ => false },
        s"reason must ride the Stop: $childCmds")
      assert(supEvts.isEmpty, s"restart must not message the supervisor directly (death-watch drives respawn): $supEvts")
    program.guarantee(system.stopAll.attempt.void)
  }

  test("restart without a task record (persistent delegate) is rejected") {
    val system = ActorSystem("ac-restart-persist")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      supRef <- system.spawn(mkRecordingEvt(Ref.unsafe(Nil)), "rp-sup")
      childRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "rp-child")
      _ <- resources.agentRegistry.set(Map(
        "delegate-Worker-99990000" -> AgentRecord(
          "delegate-Worker-99990000", childRef, AgentKind.Delegate, "root-a",
          supervisorRef = Some(supRef), parentSessionId = "root-a"
        )
      ))
      // 无 taskStore 记录（persistent Delegate 从不 recordTask）
      res <- IO(call(resources, "root-a", "restart", target = "delegate-Worker-99990000"))
    yield
      assert(res.isLeft)
      val msg = res.fold(e => e.message, _ => "")
      assert(msg.contains("Persistent delegates do not support restart"), msg)
    program.guarantee(system.stopAll.attempt.void)
  }

  // ── Block 1（supervision trio §B3/§C2）：Manager 子树管控 ──

  /** Instance A: Manager(mgr-a) + member(mem-a); instance B: Manager(mgr-b) +
    * member(mem-b). Both mounted under the SAME root bucket (root-mount) —
    * the exact shape that made same-bucket too broad for a Manager caller. */
  private def setupDualTeam(resources: SharedResources, mgrRef: nebflow.actor.ActorRef[AgentCommand], memRef: nebflow.actor.ActorRef[AgentCommand], subRef: nebflow.actor.ActorRef[AgentCommand], bRef: nebflow.actor.ActorRef[AgentCommand], now: Long): IO[Unit] =
    for
      _ <- nebflow.core.flow.TeamSessionRegistry.clear
      _ <- nebflow.core.flow.TeamSessionRegistry.registerSession("inst-a", "Manager", "mgr-a")
      _ <- nebflow.core.flow.TeamSessionRegistry.registerSession("inst-a", "member", "mem-a")
      _ <- nebflow.core.flow.TeamSessionRegistry.registerManager("inst-a", "mgr-a")
      _ <- nebflow.core.flow.TeamSessionRegistry.registerParentSession("inst-a", "root-mount")
      _ <- nebflow.core.flow.TeamSessionRegistry.registerSession("inst-b", "Manager", "mgr-b")
      _ <- nebflow.core.flow.TeamSessionRegistry.registerSession("inst-b", "member", "mem-b")
      _ <- nebflow.core.flow.TeamSessionRegistry.registerManager("inst-b", "mgr-b")
      _ <- nebflow.core.flow.TeamSessionRegistry.registerParentSession("inst-b", "root-mount")
      _ <- resources.agentRegistry.set(Map(
        "mgr-a" -> AgentRecord("mgr-a", mgrRef, AgentKind.Team, "root-mount", parentSessionId = "root-mount",
          startedAt = now, lastActivityMs = now),
        "mem-a" -> AgentRecord("mem-a", memRef, AgentKind.Team, "root-mount", parentSessionId = "mgr-a",
          startedAt = now, lastActivityMs = now),
        "subtask-1" -> AgentRecord("subtask-1", subRef, AgentKind.SubTask, "root-mount", parentSessionId = "mem-a",
          startedAt = now, lastActivityMs = now),
        "mem-b" -> AgentRecord("mem-b", bRef, AgentKind.Team, "root-mount", parentSessionId = "mgr-b",
          startedAt = now, lastActivityMs = now),
        "mgr-b" -> AgentRecord("mgr-b", bRef, AgentKind.Team, "root-mount", parentSessionId = "root-mount",
          startedAt = now, lastActivityMs = now),
        "root-mount" -> AgentRecord("root-mount", mgrRef, AgentKind.Root, "root-mount",
          startedAt = now, lastActivityMs = now)
      ))
    yield ()

  test("Block 1: Manager cancels own member (direct parent after registration chain) — guard passes, degraded Stop fires") {
    val system = ActorSystem("ac-b1-member")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      mgrRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1-mgr")
      memReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      memRef <- system.spawn(mkRecordingCmd(memReceived), "b1-mem")
      subRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1-sub")
      bRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1-b")
      _ <- setupDualTeam(resources, mgrRef, memRef, subRef, bRef, System.currentTimeMillis())
      res <- IO(call(resources, "mgr-a", "cancel", target = "mem-a", reason = "loop guard"))
      _ <- waitUntil(3.seconds)(memReceived.get.map(_.exists(_.isInstanceOf[AgentCommand.Stop])))
      registryAfter <- resources.agentRegistry.get
      _ <- IO(nebflow.core.flow.TeamSessionRegistry.clear.void)
    yield (res, memReceived, registryAfter)
    val (res, memReceived, registryAfter) = program.guarantee(system.stopAll.attempt.void).unsafeRunSync()
    assert(res.isRight, s"Manager must be able to cancel its own member (direct parent), got: $res")
    assert(
      memReceived.get.unsafeRunSync().exists(_.isInstanceOf[AgentCommand.Stop]),
      "degraded cancel path must Stop the member actor"
    )
    assert(!registryAfter.contains("mem-a"), "registry entry must be removed after degraded cancel")
  }

  test("Block 1: Manager cancels a member's SubTask via the parentSessionId chain (subtree, not just direct children)") {
    val system = ActorSystem("ac-b1-subtask")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      mgrRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1s-mgr")
      memRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1s-mem")
      subReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      subRef <- system.spawn(mkRecordingCmd(subReceived), "b1s-sub")
      bRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1s-b")
      _ <- setupDualTeam(resources, mgrRef, memRef, subRef, bRef, System.currentTimeMillis())
      res <- IO(call(resources, "mgr-a", "cancel", target = "subtask-1"))
      _ <- IO(nebflow.core.flow.TeamSessionRegistry.clear.void)
    yield res
    val res = program.guarantee(system.stopAll.attempt.void).unsafeRunSync()
    assert(res.isRight, s"SubTask of own member is inside the Manager subtree, got: $res")
  }

  test("Block 1: Manager denied another team's member (same bucket, different subtree)") {
    val system = ActorSystem("ac-b1-cross")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      mgrRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1c-mgr")
      memRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1c-mem")
      subRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1c-sub")
      bRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1c-b")
      _ <- setupDualTeam(resources, mgrRef, memRef, subRef, bRef, System.currentTimeMillis())
      res <- IO(call(resources, "mgr-a", "cancel", target = "mem-b"))
      resB <- IO(call(resources, "mgr-a", "cancel", target = "mgr-b"))
      rootRes <- IO(call(resources, "mgr-a", "cancel", target = "root-mount"))
      _ <- IO(nebflow.core.flow.TeamSessionRegistry.clear.void)
    yield (res, resB, rootRes)
    val (res, resB, rootRes) = program.guarantee(system.stopAll.attempt.void).unsafeRunSync()
    assert(
      res.left.exists(_.message.contains("outside your team subtree")),
      s"other team's member must be subtree-denied, got: $res"
    )
    assert(
      resB.left.exists(_.message.contains("outside your team subtree")),
      s"other team's Manager must be subtree-denied, got: $resB"
    )
    assert(
      rootRes.left.exists(_.message.contains("outside your team subtree")),
      s"root session must be subtree-denied for a Manager caller, got: $rootRes"
    )
  }

  test("Block 1: Manager list is scoped to its subtree (own members visible, other team hidden)") {
    val system = ActorSystem("ac-b1-list")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      mgrRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1l-mgr")
      memRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1l-mem")
      subRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1l-sub")
      bRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b1l-b")
      _ <- setupDualTeam(resources, mgrRef, memRef, subRef, bRef, System.currentTimeMillis())
      res <- AgentControlTool.call(
        io.circe.JsonObject("action" -> "list".asJson), ctx(resources, "mgr-a")
      )
      rootRes <- AgentControlTool.call(
        io.circe.JsonObject("action" -> "list".asJson), ctx(resources, "root-mount")
      )
      _ <- IO(nebflow.core.flow.TeamSessionRegistry.clear.void)
    yield (res, rootRes)
    val (res, rootRes) = program.guarantee(system.stopAll.attempt.void).unsafeRunSync()
    val out = res.toOption.get
    assert(out.contains("mem-a"), s"own member row must be visible:\n$out")
    assert(out.contains("subtask-1"), s"member's subagent row must be visible:\n$out")
    assert(out.contains("mgr-a"), s"the Manager's own row must be visible:\n$out")
    assert(!out.contains("mem-b"), s"other team's member must be hidden:\n$out")
    assert(out.contains("your team subtree"), s"scope note must be present:\n$out")
    val rootOut = rootRes.toOption.get
    assert(rootOut.contains("mem-b"), s"root-bucket caller still sees everything:\n$rootOut")
  }

  // ── Block 2（supervision trio §C3-4）：Nebula 全局权 + 杀 Manager 强确认 ──

  test("Block 2: root-bucket caller cancels a regular Team member — proceeds, audit line fired") {
    val system = ActorSystem("ac-b2-member")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      mgrRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b2-mgr")
      memReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      memRef <- system.spawn(mkRecordingCmd(memReceived), "b2-mem")
      subRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b2-sub")
      bRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b2-b")
      _ <- setupDualTeam(resources, mgrRef, memRef, subRef, bRef, System.currentTimeMillis())
      // audit capture
      lbLogger = org.slf4j.LoggerFactory.getLogger("nebflow.audit").asInstanceOf[ch.qos.logback.classic.Logger]
      appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      _ <- IO { appender.start(); lbLogger.addAppender(appender) }
      res <- IO(call(resources, "root-mount", "cancel", target = "mem-a", reason = "loop guard"))
      _ <- waitUntil(3.seconds)(memReceived.get.map(_.exists(_.isInstanceOf[AgentCommand.Stop])))
      auditLines = appender.list.asScala.map(_.getFormattedMessage).toList
      _ <- IO { lbLogger.detachAppender(appender); appender.stop() }
      _ <- IO(nebflow.core.flow.TeamSessionRegistry.clear.void)
    yield (res, memReceived, auditLines)
    val (res, memReceived, auditLines) = program.guarantee(system.stopAll.attempt.void).unsafeRunSync()
    assert(res.isRight, s"root-bucket caller must cancel a Team member globally, got: $res")
    assert(
      memReceived.get.unsafeRunSync().exists(_.isInstanceOf[AgentCommand.Stop]),
      "member actor must be stopped"
    )
    // §C3-4 audit: built-not-run IO would log nothing — the appender is the proof
    val audit = auditLines.find(l => l.contains("action=cancel") && l.contains("mem-a"))
    assert(audit.isDefined, s"nebflow.audit line must exist, got: $auditLines")
    assert(audit.get.contains("targetKind=Team"), audit.get)
    assert(audit.get.contains("reason=\"loop guard\""), audit.get)
  }

  test("Block 2: killing a team MANAGER — double confirm gate (no confirm / no reason) then confirm+reason proceeds with audit") {
    val system = ActorSystem("ac-b2-mgr")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      mgrReceived <- Ref.of[IO, List[AgentCommand]](Nil)
      mgrRef <- system.spawn(mkRecordingCmd(mgrReceived), "b2m-mgr")
      memRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b2m-mem")
      subRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b2m-sub")
      bRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "b2m-b")
      _ <- setupDualTeam(resources, mgrRef, memRef, subRef, bRef, System.currentTimeMillis())
      lbLogger = org.slf4j.LoggerFactory.getLogger("nebflow.audit").asInstanceOf[ch.qos.logback.classic.Logger]
      appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      _ <- IO { appender.start(); lbLogger.addAppender(appender) }
      // 门 1：无 confirm → 拒绝 + 后果说明
      noConfirm <- IO(call(resources, "root-mount", "cancel", target = "mgr-a"))
      // 门 2：confirm=true 无 reason → 拒绝
      noReason <- IO(call(resources, "root-mount", "cancel", target = "mgr-a", confirm = true))
      auditMidway = appender.list.asScala.map(_.getFormattedMessage).toList
      // 门 3：confirm=true + reason → 放行 + 审计含 reason
      okRes <- IO(call(resources, "root-mount", "cancel", target = "mgr-a", reason = "manager wedged, rebuild team", confirm = true))
      _ <- waitUntil(3.seconds)(mgrReceived.get.map(_.exists(_.isInstanceOf[AgentCommand.Stop])))
      auditLines = appender.list.asScala.map(_.getFormattedMessage).toList
      _ <- IO { lbLogger.detachAppender(appender); appender.stop() }
      _ <- IO(nebflow.core.flow.TeamSessionRegistry.clear.void)
    yield (noConfirm, noReason, auditMidway, okRes, mgrReceived, auditLines)
    val (noConfirm, noReason, auditMidway, okRes, mgrReceived, auditLines) =
      program.guarantee(system.stopAll.attempt.void).unsafeRunSync()
    // 门 1：拒绝并返回后果说明
    val m1 = noConfirm.fold(e => e.message, _ => "")
    assert(m1.contains("confirm=true"), s"gate 1 must demand confirm, got: $m1")
    assert(m1.contains("MANAGER"), s"gate 1 must name the target role, got: $m1")
    assert(m1.contains("members keep running"), s"gate 1 must explain the consequence, got: $m1")
    // 门 2：confirm=true 无 reason → 拒绝
    val m2 = noReason.fold(e => e.message, _ => "")
    assert(m2.contains("non-empty reason"), s"gate 2 must demand a reason, got: $m2")
    // 两次被拒不得产生审计行（审计只在放行时落）
    assert(auditMidway.isEmpty, s"rejected attempts must not audit, got: $auditMidway")
    // 门 3：放行
    assert(okRes.isRight, s"confirm=true + reason must proceed, got: $okRes")
    assert(
      mgrReceived.get.unsafeRunSync().exists(_.isInstanceOf[AgentCommand.Stop]),
      "manager actor must be stopped"
    )
    val audit = auditLines.find(l => l.contains("action=cancel") && l.contains("mgr-a"))
    assert(audit.isDefined, s"audit line must exist after the kill, got: $auditLines")
    assert(audit.get.contains("confirm=true"), audit.get)
    assert(audit.get.contains("reason=\"manager wedged, rebuild team\""), audit.get)
  }

end AgentControlToolSpec
