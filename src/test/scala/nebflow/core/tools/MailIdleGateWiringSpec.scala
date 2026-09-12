package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.agent.*
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.flow.{MailIdleGate, MailQueueStore, RunningFlowRegistry, TeamSessionRegistry}
import nebflow.core.task.FileTaskStore
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, StreamChunk}
import nebflow.core.FileChangeTracker

import scala.concurrent.duration.*

/**
 * #407 Mail queue 全空闲投递 —— AgentActor wiring 集成测试。
 *
 * 用户裁定（2026-08-25 19:53/19:57）：
 *  - queue Mail 投递时机 = 目标 agent 连同子树（子 agent/flow）全空闲；
 *  - 08-28 统一裁定：无论发送者，一律只等目标 agent 自身+子树（root→全 team 旧语义废除）；
 *  - Q3：RunningFlow.sessionId 关联（flow 在飞检测完整）；
 *  - 不做 force 兜底（无 MailQueueDrainer）。
 *
 * 覆盖：AC-6（08-28 翻转：root→Manager 只等 Manager 自身子树，兄弟忙不阻塞）/ AC-7（team 内→自身子树）/ AC-8（flow 在飞）/
 * AC-10（冷激活恢复立即投递）/ AC-11（immediate 不经 gate、FIFO 保持）。
 */
class MailIdleGateWiringSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private class RecordingLlm extends LlmHandle[IO]:
    val requests: Ref[IO, List[LlmRequest]] = Ref.unsafe(Nil)
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(requests.update(req :: _)) >>
        Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))

  private def mkResources(
      system: ActorSystem,
      tmp: os.Path,
      llm: LlmHandle[IO],
      sessionStore: SessionStore
  ): IO[SharedResources] =
    for
      dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = sessionStore,
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

  /** fixture team（lead=boss, member=member）on disk under tmp. teamName 每测试唯一——TeamSessionRegistry 是 object 单例，同 (instance,agent) key 会跨测试残留，污染 sessionIdsOf。 */
  private def fixtureTeam(tmp: os.Path, teamName: String): Unit =
    val data = tmp / "data"
    PathUtil.setDataRoot(data)
    val teamDir = data / "teams" / teamName
    os.makeDir.all(teamDir)
    os.write.over(
      teamDir / "team.json",
      s"""{"name": "${teamName}", "description": "mail idle gate fixture", "lead": "boss", "members": ["member"]}"""
    )
    for name <- List("boss", "member") do
      val adir = teamDir / "agents" / name
      os.makeDir.all(adir)
      os.write.over(adir / "agent.json", """{"description": "fixture", "useWhen": "tests"}""")

  private def ctxFor(resources: SharedResources, system: ActorSystem, senderSid: String): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(senderSid),
      sharedResources = Some(resources),
      actorSystem = Some(system)
    )

  private def queueItem(id: String, from: String, fromSession: String, message: String): MailQueueStore.MailQueueItem =
    MailQueueStore.MailQueueItem(
      id = id, from = from, fromSession = fromSession,
      message = message, `type` = "INFO",
      timestamp = System.currentTimeMillis(), imagePaths = Nil
    )

  /** 往 registry 注入任务型子记录（parentRef=目标），模拟「目标有子 agent 在飞」。 */
  private def injectChild(resources: SharedResources, childSid: String, parentRef: Option[nebflow.actor.ActorRef[AgentCommand]]): IO[Unit] =
    resources.agentRegistry.update { m =>
      m + (childSid -> AgentRecord(
        sessionId = childSid,
        ref = null,
        kind = AgentKind.Delegate,
        rootSessionId = childSid,
        parentRef = parentRef
      ))
    }

  private def removeChild(resources: SharedResources, childSid: String): IO[Unit] =
    resources.agentRegistry.update(_ - childSid)

  // ---------- AC-6（08-28 统一裁定翻转）：root（Nebula）→ Manager queue：
  // 只等 Manager 自身+子树，兄弟成员忙不再阻塞 ----------

  test("AC-6 root→Manager queue mail delivers by Manager's own subtree — sibling member busy no longer blocks"):
    val system = ActorSystem(s"cqi-a6-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a6")
    fixtureTeam(tmp, "cqi6")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession("cqi6/boss", agentName = Some("boss"), flowName = Some("cqi6"))
      memberMeta <- sessionStore.createSession("cqi6/member", agentName = Some("member"), flowName = Some("cqi6"))
      _ <- TeamSessionRegistry.registerSession("cqi6", "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerSession("cqi6", "member", memberMeta.id)
      _ <- TeamSessionRegistry.registerManager("cqi6", bossMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      // root sender record (Nebula)
      _ <- resources.agentRegistry.update(_ + ("root-sid" -> AgentRecord("root-sid", null, AgentKind.Root, "root-sid")))
      // 先激活 boss + member（确保 registry 记录与 live ref）
      _ <- MailTool.activateAgent(bossMeta.id, resources, system, ctxFor(resources, system, "root-sid"))
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, "root-sid"))
      // member 子树忙（Delegate 在飞）——08-28 起不再阻塞 Nebula→Manager 投递
      memberRef <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- injectChild(resources, "member-child", memberRef)
      // root → boss queue mail：Manager 自身+子树空闲 → 应当立即投递
      _ <- MailTool.queueToSession(
        bossMeta.id, "boss", "A6_TEAM_BUSY_MARKER", "INFO", Nil,
        ctxFor(resources, system, "root-sid"), system, "root-sid"
      )
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(bossMeta.id)
    yield (reqs, queueAfter)

    val (reqs, queueAfter) = io.unsafeRunSync()
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(clue(texts).exists(_.contains("A6_TEAM_BUSY_MARKER")),
      "Nebula→Manager queue mail must deliver on the Manager's own idle — a sibling member's busy subtree must not block (08-28 ruling)")
    assert(clue(queueAfter).isEmpty, s"queue not drained: $queueAfter")

  // ---------- AC-7：规则② team 内 queue：等目标自身子树，不管其他成员 ----------

  test("AC-7 team-internal queue mail deferred by target's own subtree; other member busy does not block"):
    val system = ActorSystem(s"cqi-a7-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a7")
    fixtureTeam(tmp, "cqi7")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession("cqi7/boss", agentName = Some("boss"), flowName = Some("cqi7"))
      memberMeta <- sessionStore.createSession("cqi7/member", agentName = Some("member"), flowName = Some("cqi7"))
      _ <- TeamSessionRegistry.registerSession("cqi7", "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerSession("cqi7", "member", memberMeta.id)
      _ <- TeamSessionRegistry.registerManager("cqi7", bossMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      // 激活 member + boss（registry 记录 + live ref；boss 作 sender 记录）
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, bossMeta.id))
      _ <- MailTool.activateAgent(bossMeta.id, resources, system, ctxFor(resources, system, bossMeta.id))
      // member 自身子树忙（Delegate 在飞）→ team 内发往 member：不投递
      memberRef <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- injectChild(resources, "member-child", memberRef)
      _ <- MailTool.queueToSession(
        memberMeta.id, "member", "A7_SELF_BUSY_MARKER", "INFO", Nil,
        ctxFor(resources, system, bossMeta.id), system, bossMeta.id
      )
      _ <- IO.sleep(600.millis)
      _ <- llm.requests.update(_ => Nil)
      busyKept <- MailQueueStore.load(memberMeta.id).map(_.nonEmpty)
      // member 子树空闲 → 重发 → 投递
      _ <- removeChild(resources, "member-child")
      memberRef2 <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      head <- MailQueueStore.load(memberMeta.id).map(_.headOption)
      _ <- head.traverse_(h => memberRef2.traverse_(ref => ref ! AgentCommand.MailQueued(h, bossMeta.id)))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(memberMeta.id)
    yield (busyKept, reqs, queueAfter)

    val (busyKept, reqs, queueAfter) = io.unsafeRunSync()
    assert(clue(busyKept), "team-internal queue mail should be deferred by target's own subtree")
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(clue(texts).exists(_.contains("A7_SELF_BUSY_MARKER")), "queue mail never drained after target idle")
    assert(clue(queueAfter).isEmpty, s"queue not drained: $queueAfter")

  test("AC-7b team-internal queue mail NOT blocked by a sibling's busy subtree (自身子树语义)"):
    val system = ActorSystem(s"cqi-a7b-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a7b")
    fixtureTeam(tmp, "cqi7b")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession("cqi7b/boss", agentName = Some("boss"), flowName = Some("cqi7b"))
      memberMeta <- sessionStore.createSession("cqi7b/member", agentName = Some("member"), flowName = Some("cqi7b"))
      _ <- TeamSessionRegistry.registerSession("cqi7b", "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerSession("cqi7b", "member", memberMeta.id)
      _ <- TeamSessionRegistry.registerManager("cqi7b", bossMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      // boss（目标）自身子树空闲；member（兄弟）子树忙——不应阻塞 team 内发往 boss
      memberRef <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- injectChild(resources, "member-child", memberRef)
      _ <- MailTool.queueToSession(
        bossMeta.id, "boss", "A7B_SIBLING_BUSY_OK", "INFO", Nil,
        ctxFor(resources, system, memberMeta.id), system, memberMeta.id
      )
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(bossMeta.id)
    yield (reqs, queueAfter)

    val (reqs, queueAfter) = io.unsafeRunSync()
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(
      clue(texts).exists(_.contains("A7B_SIBLING_BUSY_OK")),
      "team-internal mail to a free target must NOT wait for a sibling's subtree"
    )
    assert(clue(queueAfter).isEmpty, s"queue not drained: $queueAfter")

  // ---------- AC-8：flow 在飞（Q3 RunningFlow.sessionId 关联） ----------

  test("AC-8 queue mail deferred while target has a running flow, drains when flow completes"):
    val system = ActorSystem(s"cqi-a8-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a8")
    fixtureTeam(tmp, "cqi8")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession("cqi8/boss", agentName = Some("boss"), flowName = Some("cqi8"))
      _ <- TeamSessionRegistry.registerSession("cqi8", "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerManager("cqi8", bossMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      _ <- resources.agentRegistry.update(_ + ("root-sid" -> AgentRecord("root-sid", null, AgentKind.Root, "root-sid")))
      // 激活 boss（确保 registry 记录与 live ref）
      _ <- MailTool.activateAgent(bossMeta.id, resources, system, ctxFor(resources, system, "root-sid"))
      // running flow 关联 boss（Q3：sessionId 关联触发者）→ 不投递
      _ <- RunningFlowRegistry.register(RunningFlowRegistry.RunningFlow(
        instanceId = "flow-a8", flowName = "f8", description = "", entry = "n",
        nodes = Map.empty, edges = Nil,
        status = nebflow.core.flow.NodeStatus.Running,
        startedAt = System.currentTimeMillis(),
        sessionId = Some(bossMeta.id)
      ))
      _ <- MailTool.queueToSession(
        bossMeta.id, "boss", "A8_FLOW_BUSY_MARKER", "INFO", Nil,
        ctxFor(resources, system, "root-sid"), system, "root-sid"
      )
      _ <- IO.sleep(600.millis)
      _ <- llm.requests.update(_ => Nil)
      busyKept <- MailQueueStore.load(bossMeta.id).map(_.nonEmpty)
      // flow 完成 → 重发 → 投递
      _ <- RunningFlowRegistry.update("flow-a8")(_.copy(status = nebflow.core.flow.NodeStatus.Completed))
      bossRef <- resources.agentRegistry.get.map(_.get(bossMeta.id).map(_.ref))
      head <- MailQueueStore.load(bossMeta.id).map(_.headOption)
      _ <- head.traverse_(h => bossRef.traverse_(ref => ref ! AgentCommand.MailQueued(h, "root-sid")))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(bossMeta.id)
    yield (busyKept, reqs, queueAfter)

    val (busyKept, reqs, queueAfter) = io.unsafeRunSync()
    assert(clue(busyKept), "queue mail should be deferred while a running flow is associated with the target")
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(clue(texts).exists(_.contains("A8_FLOW_BUSY_MARKER")), "queue mail never drained after flow completed")
    assert(clue(queueAfter).isEmpty, s"queue not drained: $queueAfter")

  // ---------- AC-10：冷激活恢复立即投递 ----------

  test("AC-10 cold activation (activateAgent head recovery) drains immediately — no subtree"):
    val system = ActorSystem(s"cqi-a10-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a10")
    fixtureTeam(tmp, "cqi10")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession("cqi10/boss", agentName = Some("boss"), flowName = Some("cqi10"))
      _ <- TeamSessionRegistry.registerSession("cqi10", "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerManager("cqi10", bossMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      // 队列有货（磁盘）但 agent 冷（无 actor）——activateAgent 恢复 head 触发
      _ <- MailQueueStore.append(bossMeta.id, queueItem("mail-q-a10", "tester", "root-sid", "A10_COLD_MARKER"))
      refOpt <- MailTool.activateAgent(bossMeta.id, resources, system, ctxFor(resources, system, "root-sid"))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(bossMeta.id)
    yield (refOpt.isDefined, reqs, queueAfter)

    val (activated, reqs, queueAfter) = io.unsafeRunSync()
    assert(clue(activated), "activateAgent should return a live ref")
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(clue(texts).exists(_.contains("A10_COLD_MARKER")), "cold-activated agent never drained the queue head")
    assert(clue(queueAfter).isEmpty, s"queue not drained after cold activation: $queueAfter")

  // ---------- AC-11：回归——immediate Mail 不经 gate；FIFO 保持 ----------

  test("AC-11a immediate mail bypasses the idle gate (delivered while target subtree busy)"):
    val system = ActorSystem(s"cqi-a11a-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a11a")
    fixtureTeam(tmp, "cqi11a")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      memberMeta <- sessionStore.createSession("cqi11a/member", agentName = Some("member"), flowName = Some("cqi11a"))
      _ <- TeamSessionRegistry.registerSession("cqi11a", "member", memberMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      // 激活 member（确保 registry 记录与 live ref）
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, "boss-sid"))
      memberRef <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- injectChild(resources, "member-child", memberRef)
      // immediate 投递 = ImmediateInput 注入（sendMail 内部机制），不经 MailQueued/gate
      _ <- memberRef.traverse_(ref => ref ! AgentCommand.ImmediateInput(
        "A11A_IMMEDIATE_OK",
        source = Some("mail"),
        sender = Some("boss")
      ))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
    yield reqs

    val reqs = io.unsafeRunSync()
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(
      clue(texts).exists(_.contains("A11A_IMMEDIATE_OK")),
      "immediate mail must be delivered even while the target's subtree is busy"
    )

  test("AC-11b FIFO order preserved across gate deferrals"):
    val system = ActorSystem(s"cqi-a11b-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a11b")
    fixtureTeam(tmp, "cqi11b")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      memberMeta <- sessionStore.createSession("cqi11b/member", agentName = Some("member"), flowName = Some("cqi11b"))
      _ <- TeamSessionRegistry.registerSession("cqi11b", "member", memberMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      // 先入队两件（磁盘顺序 = 注入顺序）
      _ <- MailQueueStore.append(memberMeta.id, queueItem("mail-q-1st", "boss", "boss-sid", "FIRST_MARKER"))
      _ <- MailQueueStore.append(memberMeta.id, queueItem("mail-q-2nd", "boss", "boss-sid", "SECOND_MARKER"))
      // 目标子树忙 → activateAgent 冷激活后 gate 拦截（保留 count）——先验证延迟
      memberRef <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- injectChild(resources, "member-child", memberRef)
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, "boss-sid"))
      _ <- IO.sleep(600.millis)
      _ <- llm.requests.update(_ => Nil)
      // 子树空闲 → 重发 head → 按 FIFO 逐件投递
      _ <- removeChild(resources, "member-child")
      memberRef2 <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      head <- MailQueueStore.load(memberMeta.id).map(_.headOption)
      _ <- head.traverse_(h => memberRef2.traverse_(ref => ref ! AgentCommand.MailQueued(h, "boss-sid")))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      _ <- IO.sleep(1.seconds) // let the second item drain too
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(memberMeta.id)
    yield (reqs, queueAfter)

    val (reqs, queueAfter) = io.unsafeRunSync()
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    val firstIdx = texts.indexOf("FIRST_MARKER")
    val secondIdx = texts.indexOf("SECOND_MARKER")
    assert(clue(firstIdx) >= 0, "first item never injected")
    assert(clue(secondIdx) >= 0, "second item never injected")
    assert(clue(firstIdx) < clue(secondIdx), s"FIFO order broken: first=$firstIdx second=$secondIdx")
    assert(clue(queueAfter).isEmpty, s"queue not fully drained: $queueAfter")

  // ---------- AC-12：#10 mail-queue wedge——turn-end drain 的 gate 拦截走
  // returnToIdle 后计数必须存活（2026-08-27 生产积压实证：多会话 mail-queue.json
  // 有货但永不 drain，直至另一封 MailQueued 到达才续命）。根因=returnToIdle 用
  // ExecutionContext.idle 重建把 pendingMailQueueCount 归 0，下一次 finishTurnCont
  // 的 drain 分支条件 `pendingMailQueueCount > 0` 永久为 false → 磁盘队列楔死。
  // 回归=子 agent 完成事件驱动的新 turn 结束时（不重发 MailQueued）队列仍被 drain。

  test("AC-12 turn-end gate deferral preserves the count — later turn end drains without re-send"):
    val system = ActorSystem(s"cqi-a12-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a12")
    fixtureTeam(tmp, "cqi12")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession("cqi12/boss", agentName = Some("boss"), flowName = Some("cqi12"))
      memberMeta <- sessionStore.createSession("cqi12/member", agentName = Some("member"), flowName = Some("cqi12"))
      _ <- TeamSessionRegistry.registerSession("cqi12", "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerSession("cqi12", "member", memberMeta.id)
      _ <- TeamSessionRegistry.registerManager("cqi12", bossMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, bossMeta.id))
      // member 子树忙（Delegate 在飞）→ team 内 queue mail 被 idle gate 拦截
      memberRef <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- injectChild(resources, "member-child", memberRef)
      _ <- MailTool.queueToSession(
        memberMeta.id, "member", "A12_WEDGE_MARKER", "INFO", Nil,
        ctxFor(resources, system, bossMeta.id), system, bossMeta.id
      )
      _ <- IO.sleep(600.millis) // let the deferral land (count=1, queue retained)
      _ <- llm.requests.update(_ => Nil)
      // 触发一轮真实 turn（子 agent 完成事件驱动的等价物）：turn 结束走
      // finishTurnCont drain 分支 → gate 仍忙 → returnToIdle——计数必须存活
      memberRef2 <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- memberRef2.traverse_(ref => ref ! AgentCommand.ImmediateInput("A12_TURN1", source = Some("test")))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty)) // TURN1 已处理
      _ <- llm.requests.update(_ => Nil)
      // 子树空闲，但**不重发 MailQueued**——下一次 turn 结束应自动 drain
      _ <- removeChild(resources, "member-child")
      memberRef3 <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- memberRef3.traverse_(ref => ref ! AgentCommand.ImmediateInput("A12_TURN2", source = Some("test")))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.exists(r =>
        r.messages.map(_.content.fold(identity, _.mkString)).exists(_.contains("A12_WEDGE_MARKER"))
      )))
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(memberMeta.id)
    yield (reqs, queueAfter)

    val (reqs, queueAfter) = io.unsafeRunSync()
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(
      clue(texts).exists(_.contains("A12_WEDGE_MARKER")),
      "queue mail wedged: a later turn end must drain without re-sending MailQueued"
    )
    assert(clue(queueAfter).isEmpty, s"queue not drained: $queueAfter")

  // ---------- AC-13：#10 idle deferral 计数累加——多封 queue mail 在 idle+子树忙
  // 时到达，每封都走 idle gate 拦截；旧代码硬编码 `= 1` 丢计数 → 只投最后一封。
  // 回归=多封在子树空闲后逐封 drain（FIFO），不丢中间件。

  test("AC-13 multiple idle-gate deferrals accumulate — all items drain after subtree frees"):
    val system = ActorSystem(s"cqi-a13-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a13")
    fixtureTeam(tmp, "cqi13")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      bossMeta <- sessionStore.createSession("cqi13/boss", agentName = Some("boss"), flowName = Some("cqi13"))
      memberMeta <- sessionStore.createSession("cqi13/member", agentName = Some("member"), flowName = Some("cqi13"))
      _ <- TeamSessionRegistry.registerSession("cqi13", "boss", bossMeta.id)
      _ <- TeamSessionRegistry.registerSession("cqi13", "member", memberMeta.id)
      _ <- TeamSessionRegistry.registerManager("cqi13", bossMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      _ <- MailTool.activateAgent(memberMeta.id, resources, system, ctxFor(resources, system, bossMeta.id))
      memberRef <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- injectChild(resources, "member-child", memberRef)
      // 两封 queue mail 在 idle+子树忙时相继到达（各走一次 idle gate 拦截）
      _ <- MailTool.queueToSession(
        memberMeta.id, "member", "A13_FIRST_MARKER", "INFO", Nil,
        ctxFor(resources, system, bossMeta.id), system, bossMeta.id
      )
      _ <- IO.sleep(300.millis)
      _ <- MailTool.queueToSession(
        memberMeta.id, "member", "A13_SECOND_MARKER", "INFO", Nil,
        ctxFor(resources, system, bossMeta.id), system, bossMeta.id
      )
      _ <- IO.sleep(600.millis) // both deferrals land; count must be 2, not 1
      _ <- llm.requests.update(_ => Nil)
      // 子树空闲 → 单轮 turn 结束自动逐封 drain（FIFO）
      _ <- removeChild(resources, "member-child")
      memberRef2 <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- memberRef2.traverse_(ref => ref ! AgentCommand.ImmediateInput("A13_TURN", source = Some("test")))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.exists(r =>
        r.messages.map(_.content.fold(identity, _.mkString)).exists(_.contains("A13_SECOND_MARKER"))
      )))
      _ <- IO.sleep(1.seconds) // let the second item drain too
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(memberMeta.id)
    yield (reqs, queueAfter)

    val (reqs, queueAfter) = io.unsafeRunSync()
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    val firstIdx = texts.indexOf("A13_FIRST_MARKER")
    val secondIdx = texts.indexOf("A13_SECOND_MARKER")
    assert(clue(firstIdx) >= 0, "first item never drained (count lost by idle deferral clamp)")
    assert(clue(secondIdx) >= 0, "second item never drained")
    assert(clue(firstIdx) < clue(secondIdx), s"FIFO order broken: first=$firstIdx second=$secondIdx")
    assert(clue(queueAfter).isEmpty, s"queue not fully drained: $queueAfter")

  // ---------- AC-14（R2 2026-09-12）：node: 腿绕行本闸（结构性） ----------

  test("AC-14 R2：node:<id> 腿不经 idle gate —— 显式拒 queue、零队列落盘、零 turn") {
    val system = ActorSystem(s"cqi-a14-${java.util.UUID.randomUUID().toString.take(6)}")
    val tmp = os.temp.dir(prefix = "cqi-a14")
    fixtureTeam(tmp, "cqi14")
    val llm = new RecordingLlm

    val io = for
      sessionStore <- IO.pure(SessionStore(tmp / "sessions", tmp / "tasks"))
      memberMeta <- sessionStore.createSession("cqi14/member", agentName = Some("member"), flowName = Some("cqi14"))
      _ <- TeamSessionRegistry.registerSession("cqi14", "member", memberMeta.id)
      resources <- mkResources(system, tmp, llm, sessionStore)
      res <- MailTool.call(
        JsonObject(
          "address" -> Json.fromString("node:n-14"),
          "message" -> Json.fromString("节点补充"),
          "delivery" -> Json.fromString("queue")
        ),
        ctxFor(resources, system, "disp-sid").copy(isDispatcher = true, projectName = Some("p14"))
      )
      queueCount <- MailQueueStore.size(memberMeta.id)
      reqs <- llm.requests.get
    yield (res, queueCount, reqs)

    val (res, queueCount, reqs) = io.unsafeRunSync()
    res match
      case Left(err) => assert(clue(err.message).contains("always immediate"), "node: 腿必须显式拒 queue（不得落入本闸）")
      case Right(v)  => fail(s"node: + queue 不得成功，got: $v")
    assertEquals(queueCount, 0, "node: 腿不得落 MailQueueStore（idle gate 结构上不可达）")
    assert(clue(reqs).isEmpty, "node: 腿不得触发任何 turn（注入/追加由引擎三态决定）")
  }

end MailIdleGateWiringSpec
