package nebflow.core.tools

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent, AgentKind, AgentRecord, AgentStatus, sessionId, status}
import nebflow.agent.*
import nebflow.actor.{
  AgentCommand,
  AgentDef,
  AgentKind,
  AgentRecord
} // W1 shim: main exported these from nebflow.agent (protocol/AgentState); the merge moved them to actor
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{SubAgentTask, ThinkingConfig}

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

  private def call(
    resources: SharedResources,
    sessionId: String,
    action: String,
    target: String = "",
    reason: String = "",
    confirm: Boolean = false,
    scope: String = "",
    jobId: String = ""
  ): Either[ToolError, String] =
    val input = io.circe.JsonObject(
      "action" -> action.asJson,
      "sessionId" -> target.asJson,
      "reason" -> reason.asJson,
      "confirm" -> confirm.asJson,
      "scope" -> scope.asJson,
      "jobId" -> jobId.asJson
    )
    AgentControlTool.call(input, ctx(resources, sessionId)).unsafeRunSync()

  end call

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
      _ <- resources.agentRegistry.set(
        Map(
          "delegate-Explorer-aaaa1111" -> AgentRecord(
            "delegate-Explorer-aaaa1111",
            delRef,
            AgentKind.Delegate,
            "root-1",
            startedAt = now - 120_000,
            status = AgentStatus.Processing,
            lastActivityMs = now - 45_000
          ),
          "team-nebflow-project-Backend" -> AgentRecord(
            "team-nebflow-project-Backend",
            teamRef,
            AgentKind.Team,
            "team-nebflow-project-Backend"
          )
        )
      )
      res <- AgentControlTool.call(
        io.circe.JsonObject("action" -> "list".asJson),
        ctx(resources, "root-1")
      )
    yield
      val out = res.toOption.get
      assert(out.contains("delegate-Explorer-aaaa1111"), s"row must render sessionId:\n$out")
      assert(out.contains("Delegate"), s"kind column:\n$out")
      // 团队功能删除批（team-feature-deletion-20261104）阶段 3：Team 目标不可管理
      // （team scope 整段删除）⇒ 行照常呈现，但按普通只读 kind 标注。
      assert(out.contains("Team"), s"team row:\n$out")
      val teamLine = out.linesIterator.find(_.contains("team-nebflow-project-Backend")).get
      assert(teamLine.contains("read-only"), s"Team row must be read-only now:\n$teamLine")
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
      _ <- resources.agentRegistry.set(
        Map(
          "delegate-Worker-bbbb2222" -> AgentRecord(
            "delegate-Worker-bbbb2222",
            delRef,
            AgentKind.Delegate,
            "root-2",
            startedAt = System.currentTimeMillis() - 30_000,
            status = AgentStatus.Processing,
            lastActivityMs = System.currentTimeMillis() - 5_000,
            parentSessionId = "root-2"
          )
        )
      )
      res <- IO(call(resources, "root-2", "status", target = "delegate-Worker-bbbb2222"))
    yield
      val out = res.toOption.get
      assert(out.contains("kind: Delegate"), s"$out")
      assert(out.contains("status: Processing"), s"$out")
      assert(out.contains("task.status: running"), s"$out")
      assert(out.contains("manageable: cancel / restart"), s"$out")
    program.guarantee(system.stopAll.attempt.void)
  }

  test("status on a Team target — 详情卡仍可读，但 manage 行恒不可管（team scope 已退役）") {
    val system = ActorSystem("ac-status-team")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      memRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "status-team-mem")
      _ <- resources.agentRegistry.set(
        Map(
          "team-member-x" -> AgentRecord(
            "team-member-x",
            memRef,
            AgentKind.Team,
            "root-t1",
            startedAt = System.currentTimeMillis() - 30_000,
            status = AgentStatus.Processing,
            lastActivityMs = System.currentTimeMillis() - 5_000,
            parentSessionId = "mgr-inst"
          )
        )
      )
      res <- IO(call(resources, "root-t1", "status", target = "team-member-x"))
    yield res
    val res = program.guarantee(system.stopAll.attempt.void).unsafeRunSync()
    // 阶段 3 口径：status 是**只读**面（诊断可见性保留，便于看残留记录），
    // 但可管理性判定经同一 `kindRejection` ⇒ Team 恒 "manageable: no (read-only kind)"。
    val out = res.toOption.getOrElse(fail(s"status must still render for a Team record, got: $res"))
    assert(out.contains("kind: Team"), out)
    assert(out.contains("manageable: no (read-only kind)"), out)
    assert(!out.contains("manageable: cancel"), s"Team target must not advertise cancel/restart:\n$out")
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
      _ <- resources.agentRegistry.set(
        Map(
          "self-session" -> AgentRecord("self-session", rootRef, AgentKind.Root, "self-session"),
          // 同桶（root=self-session）但 kind=Root —— 走 kind 拒绝而非权限拒绝
          "another-root" -> AgentRecord("another-root", rootRef, AgentKind.Root, "self-session"),
          // 同桶 Team —— 阶段 3 起恒 kind 拒绝（team scope 已退役）
          "team-nebflow-project-Backend" -> AgentRecord(
            "team-nebflow-project-Backend",
            teamRef,
            AgentKind.Team,
            "self-session"
          ),
          // 同桶 Flow —— cancelFlow 指引
          "dag-step1" -> AgentRecord("dag-step1", flowRef, AgentKind.Flow, "self-session"),
          // 跨桶 Delegate —— Permission denied（在 kind 检查之前）
          "delegate-Other-hhhh0000" -> AgentRecord("delegate-Other-hhhh0000", delRef, AgentKind.Delegate, "other-root")
        )
      )
      selfRes <- IO(call(resources, "self-session", "cancel", target = "self-session"))
      rootRes <- IO(call(resources, "self-session", "cancel", target = "another-root"))
      teamRes <- IO(call(resources, "self-session", "cancel", target = "team-nebflow-project-Backend"))
      flowRes <- IO(call(resources, "self-session", "cancel", target = "dag-step1"))
      crossRes <- IO(call(resources, "self-session", "cancel", target = "delegate-Other-hhhh0000"))
    yield
      assert(selfRes.left.exists(_.message.contains("Self-guard")), selfRes.toString)
      assert(rootRes.left.exists(_.message.contains("cannot be managed")), rootRes.toString)
      // 团队功能删除批阶段 3：Team 目标恒显式拒绝（team scope 整段删除；Block 2
      // §C3 的「root 桶全局可管 Team」随之作废）。
      assert(
        teamRes.left.exists(_.message.contains("Team targets are not manageable")),
        s"Team target must be refused for every caller, got: $teamRes"
      )
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
      _ <- resources.agentRegistry.set(
        Map(
          "delegate-Worker-dddd4444" -> AgentRecord(
            "delegate-Worker-dddd4444",
            delRef,
            AgentKind.Delegate,
            "root-9",
            supervisorRef = Some(supRef),
            parentSessionId = "root-9"
          )
        )
      )
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
      _ <- resources.agentRegistry.set(
        Map(
          "subtask-Worker-eeee5555" -> AgentRecord(
            "subtask-Worker-eeee5555",
            childRef,
            AgentKind.SubTask,
            "root-5",
            parentRef = Some(parentRef),
            parentSessionId = "root-5"
          )
        )
      )
      res <- IO(call(resources, "root-5", "cancel", target = "subtask-Worker-eeee5555", reason = "fallback"))
      _ <- waitUntil(3.seconds)(
        resources.subAgentTaskStore
          .loadTasks("root-5")
          .map(_.exists(t => t.taskId == "subtask-Worker-eeee5555" && t.status == "cancelled"))
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

  // 裁定5 删除 `AgentKind.Ephemeral` 之后，本用例改挂到裁定4 新增的
  // `AgentKind.External` 上——判据不变：**没有 supervisor/checkpoint 载体的 kind，
  // restart 必须被显式拒绝并指引 cancel**（旧用例断言的 Ephemeral 已不存在）。
  test("restart on External kind is rejected with cancel guidance") {
    val system = ActorSystem("ac-restart-ext")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      _ <- resources.agentRegistry.set(
        Map(
          "external-codex-aaaa1111" -> AgentRecord(
            "external-codex-aaaa1111",
            // 外部 CLI 腿进程内无 actor —— 裁定4 唯一允许 null ref 的记录
            null,
            AgentKind.External,
            "root-7"
          )
        )
      )
      res <- IO(call(resources, "root-7", "restart", target = "external-codex-aaaa1111"))
    yield
      assert(res.isLeft)
      val msg = res.fold(e => e.message, _ => "")
      assert(msg.contains("Restart is not supported"), msg)
      assert(msg.contains("cancel"), msg)
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
      _ <- resources.agentRegistry.set(
        Map(
          "delegate-Worker-77778888" -> AgentRecord(
            "delegate-Worker-77778888",
            childRef,
            AgentKind.Delegate,
            "root-8",
            supervisorRef = Some(supRef),
            parentSessionId = "root-8"
          )
        )
      )
      res <- IO(call(resources, "root-8", "restart", target = "delegate-Worker-77778888", reason = "stuck turn"))
      _ <- waitUntil(3.seconds)(
        resources.subAgentTaskStore
          .loadTasks("root-8")
          .map(_.exists(t => t.taskId == "delegate-Worker-77778888" && t.status == "restarting"))
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
      assert(
        childCmds.exists { case AgentCommand.Stop(r) => r.contains("stuck turn"); case _ => false },
        s"reason must ride the Stop: $childCmds"
      )
      assert(
        supEvts.isEmpty,
        s"restart must not message the supervisor directly (death-watch drives respawn): $supEvts"
      )
    program.guarantee(system.stopAll.attempt.void)
  }

  // F3(b)「Team restart success text is honest」—— 团队功能删除批阶段 3 删除：
  // 断言对象 `AgentControlTool.teamRestartSuccessText` 随 Team 成员 restart 腿整段
  // 删除（作者裁定 team scope 整段删除）。删因：**Team restart 能力已退役**。

  test("restart without a task record (persistent delegate) is rejected") {
    val system = ActorSystem("ac-restart-persist")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      supRef <- system.spawn(mkRecordingEvt(Ref.unsafe(Nil)), "rp-sup")
      childRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "rp-child")
      _ <- resources.agentRegistry.set(
        Map(
          "delegate-Worker-99990000" -> AgentRecord(
            "delegate-Worker-99990000",
            childRef,
            AgentKind.Delegate,
            "root-a",
            supervisorRef = Some(supRef),
            parentSessionId = "root-a"
          )
        )
      )
      // 无 taskStore 记录（persistent Delegate 从不 recordTask）
      res <- IO(call(resources, "root-a", "restart", target = "delegate-Worker-99990000"))
    yield
      assert(res.isLeft)
      val msg = res.fold(e => e.message, _ => "")
      assert(msg.contains("Persistent delegates do not support restart"), msg)
    program.guarantee(system.stopAll.attempt.void)
  }

  // ═══ Block 1 / Block 2（supervision trio §B3/§C2/§C3-4：团队 Manager 子树管控、
  // Nebula 全局可管 Team、杀 Manager 的 confirm+reason 强确认门与审计行）——
  // 团队功能删除批（team-feature-deletion-20261104）阶段 3 **整段删除**（作者裁定
  // 「AgentControl 的 team scope 整段删除，只留 session / bgTask」）。
  // 连带删除的夹具：`setupDualTeam`（inst-a/inst-b 双团队 + managerMap +
  // parentSessionMap 全经团队会话登记表写入）与 6 条用例：
  //   1. Manager cancels own member（degraded Stop）
  //   2. Manager cancels a member's SubTask（parentSessionId 链）
  //   3. Manager denied another team's member（子树门）
  //   4. Manager list scoped to its subtree
  //   5. root-bucket caller cancels a Team member（audit 行）
  //   6. killing a team MANAGER — double confirm gate + audit
  // 删因：**team scope 能力已退役**（不是「跑不过」）。Manager 侧的同位替代面 =
  // Nebula 用 Delegate/SubTask/Project 会话管理，团队内不再有受管对象。

  // ── Project flow 会话（node-/dispatcher-）管理通道 ──────────

  test("cancel node-/dispatcher- 会话（观察桥 supervisor）→ 放行，桥收 Cancelled；list 行不再标 read-only") {
    val system = ActorSystem("ac-cancel-flow")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      bridgeEvents <- Ref.of[IO, List[AgentEvent]](Nil)
      bridgeRef <- system.spawn(mkRecordingEvt(bridgeEvents), "flow-bridge")
      nodeRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "flow-node")
      dispatcherRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "flow-dispatcher")
      _ <- resources.agentRegistry.set(
        Map(
          "node-aaaa1111" -> AgentRecord(
            "node-aaaa1111",
            nodeRef,
            AgentKind.Flow,
            "root-9",
            startedAt = System.currentTimeMillis(),
            lastActivityMs = System.currentTimeMillis(),
            supervisorRef = Some(bridgeRef)
          ),
          "dispatcher-bbbb2222" -> AgentRecord(
            "dispatcher-bbbb2222",
            dispatcherRef,
            AgentKind.Flow,
            "root-9",
            startedAt = System.currentTimeMillis(),
            lastActivityMs = System.currentTimeMillis(),
            supervisorRef = Some(bridgeRef)
          )
        )
      )
      listRes <- IO(call(resources, "root-9", "list"))
      nodeRes <- IO(call(resources, "root-9", "cancel", target = "node-aaaa1111", reason = "hung node"))
      dispatcherRes <- IO(call(resources, "root-9", "cancel", target = "dispatcher-bbbb2222"))
      _ <- waitUntil(3.seconds)(bridgeEvents.get.map(_.size >= 2))
      events <- bridgeEvents.get
    yield
      // list：新 Project flow 会话有取消通道 → 不再是 read-only 行
      val nodeLine = listRes.fold(_.message, identity).split("\n").find(_.contains("node-aaaa1111"))
      assert(nodeLine.exists(!_.contains("read-only")), s"node row must not be read-only:\n${nodeLine.getOrElse("?")}")
      // cancel 放行（观察桥 Cancelled 通道）
      assert(nodeRes.isRight, s"node session cancel must proceed, got: $nodeRes")
      assert(dispatcherRes.isRight, s"dispatcher session cancel must proceed, got: $dispatcherRes")
      val cancelled = events.collect { case c: AgentEvent.Cancelled => c }
      assertEquals(cancelled.map(_.sessionId).sorted, List("dispatcher-bbbb2222", "node-aaaa1111"))
      // node 会话的 cancel 文案指向 node 终态语义（不提父通知/任务文件）
      assert(nodeRes.exists(_.contains("status=cancelled")), nodeRes.toString)
    program.guarantee(system.stopAll.attempt.void)
  }

  test("restart node-/dispatcher- 会话 → 拒绝（single-shot 语义，指路 cancel + 重新触发）") {
    val system = ActorSystem("ac-restart-flow")
    val tmp = os.temp.dir()
    val program = for
      resources <- mkResources(system, tmp)
      bridgeRef <- system.spawn(mkRecordingEvt(Ref.unsafe(Nil)), "flow-bridge-r")
      nodeRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "flow-node-r")
      _ <- resources.agentRegistry.set(
        Map(
          "node-cccc3333" -> AgentRecord(
            "node-cccc3333",
            nodeRef,
            AgentKind.Flow,
            "root-9",
            supervisorRef = Some(bridgeRef)
          )
        )
      )
      res <- IO(call(resources, "root-9", "restart", target = "node-cccc3333"))
    yield
      assert(res.left.exists(_.message.contains("single-shot")), res.toString)
      assert(res.left.exists(_.message.contains("Cancel")), res.toString)
    program.guarantee(system.stopAll.attempt.void)
  }

  // ── P2 scope=bgTask（后台任务生命周批 · 设计稿 §4.1-b：三守卫复用 + 任务归属校验）──

  test("P2 scope=bgTask：1/N 精确取消——目标会话恰好收到一条 cancelled 事件，其余任务存活") {
    val system = ActorSystem("ac-bg-task")
    val tmp = os.temp.dir()
    val sid = "delegate-bgTask-1"
    val program = for
      resources <- mkResources(system, tmp)
      record <- Ref.of[IO, List[AgentCommand]](Nil)
      delRef <- system.spawn(mkRecordingCmd(record), "bg-task-del")
      shell <- ShellSession.forSession(sid)
      result <- (
        for
          // 两条真实后台任务（属主 = 目标会话）
          _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some("g1"))
          _ <- shell.executeBackground("sleep 3600", jobIdOverride = Some("g2"))
          _ <- BgTaskRegistry.register("g1", sid, "task-g1", "local", rootSessionId = "root-p2")
          _ <- BgTaskRegistry.register("g2", sid, "task-g2", "local", rootSessionId = "root-p2")
          _ <- resources.agentRegistry.set(
            Map(
              sid -> AgentRecord(sid, delRef, AgentKind.Delegate, "root-p2", supervisorRef = None)
            )
          )
          res <- IO(call(resources, "root-p2", "cancel", target = sid, scope = "bgTask", jobId = "g1"))
          _ <- IO {
            assert(res.isRight, s"scope=bgTask cancel must succeed: $res")
            assert(res.toOption.get.contains("Cancelled background task g1"))
          }
          // 1/N：g1 出册、g2 存活
          rows <- BgTaskRegistry.allActiveTasks
          _ <- IO(assertEquals(rows.map(_.jobId), List("g2"), "只杀指定的一条"))
          h2 <- shell.getBackgroundJobHealth("g2")
          _ <- IO(assert(h2.exists(_.isAlive), "g2 进程必须存活"))
          // 目标会话恰好收到一条 cancelled 事件（correlationId = jobId）
          cmds <- record.get
          evts = cmds.collect { case e: AgentCommand.ExternalEvent => e }
          _ <- IO {
            assertEquals(evts.size, 1, s"恰一条取消事件: $evts")
            assertEquals(evts.head.eventType, "cancelled")
            assertEquals(evts.head.correlationId, Some("g1"))
            assert(evts.head.payload.contains("Cancelled"), evts.head.payload)
          }
        yield ()
      ).guarantee(
        shell.cancelBackgroundJob("g2").attempt.void *>
          List("g1", "g2").traverse_(j => BgTaskRegistry.unregister(j).attempt.void) *>
          ShellSession.destroySession(sid).attempt.void
      )
    yield result
    program.guarantee(system.stopAll.attempt.void)
  }

  test("P2 scope=bgTask：缺 jobId / 任务不属于目标会话 / 未知任务 ⇒ 拒绝（守卫与归属校验）") {
    val system = ActorSystem("ac-bg-task-guard")
    val tmp = os.temp.dir()
    val sid = "delegate-bgTask-2"
    val program = for
      resources <- mkResources(system, tmp)
      delRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "bg-task-del2")
      _ <- resources.agentRegistry.set(
        Map(sid -> AgentRecord(sid, delRef, AgentKind.Delegate, "root-p2g", supervisorRef = None))
      )
      // 缺 jobId
      r1 <- IO(call(resources, "root-p2g", "cancel", target = sid, scope = "bgTask"))
      // 未知任务
      r2 <- IO(call(resources, "root-p2g", "cancel", target = sid, scope = "bgTask", jobId = "ghost"))
      // 归属校验：任务挂在**别的**会话名下
      _ <- BgTaskRegistry.register("other-1", "someone-else", "task-x", "local")
      r3 <- IO(call(resources, "root-p2g", "cancel", target = sid, scope = "bgTask", jobId = "other-1"))
      _ <- BgTaskRegistry.unregister("other-1")
      _ <- IO {
        assert(r1.left.exists(_.message.contains("jobId")), r1.toString)
        assert(r2.left.exists(_.message.contains("No active background task")), r2.toString)
        assert(r3.left.exists(_.message.contains("does not belong")), r3.toString)
      }
    yield ()
    program.guarantee(system.stopAll.attempt.void)
  }

  test("P2 scope=bgTask：会话取消语义零漂移——不带 scope 的 cancel 仍走整会话 doCancel") {
    val system = ActorSystem("ac-bg-task-session")
    val tmp = os.temp.dir()
    val sid = "delegate-bgTask-3"
    val program = for
      resources <- mkResources(system, tmp)
      record <- Ref.of[IO, List[AgentEvent]](Nil)
      supRefEvt <- system.spawn(mkRecordingEvt(record), "bg-task-sup")
      delRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "bg-task-del3")
      rootRef <- system.spawn(mkRecordingCmd(Ref.unsafe(Nil)), "bg-task-root")
      _ <- resources.subAgentTaskStore.recordTask(mkTask("root-p2s", sid))
      _ <- resources.agentRegistry.set(
        Map(
          sid -> AgentRecord(sid, delRef, AgentKind.Delegate, "root-p2s", supervisorRef = Some(supRefEvt)),
          "root-p2s" -> AgentRecord("root-p2s", rootRef, AgentKind.Delegate, "root-p2s", supervisorRef = None)
        )
      )
      res <- IO(call(resources, "root-p2s", "cancel", target = sid))
      _ <- IO(assert(res.isRight, s"session cancel unchanged: $res"))
      evts <- record.get
      _ <- IO(assert(evts.exists {
        case AgentEvent.Cancelled(id, _) => id == sid
        case _                           => false
      }, s"supervisor got Cancelled: $evts"))
    yield ()
    program.guarantee(system.stopAll.attempt.void)
  }

end AgentControlToolSpec
