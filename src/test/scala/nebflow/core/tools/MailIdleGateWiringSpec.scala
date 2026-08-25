package nebflow.core.tools

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import fs2.Stream
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
 *  - 按发送者区分：Nebula（root）→ Manager 等全 team；team 内→目标自身子树；
 *  - Q3：RunningFlow.sessionId 关联（flow 在飞检测完整）；
 *  - 不做 force 兜底（无 MailQueueDrainer）。
 *
 * 覆盖：AC-6（root→team 全停）/ AC-7（team 内→自身子树）/ AC-8（flow 在飞）/
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

  // ---------- AC-6：规则① root（Nebula）→ Manager queue：等全 team 空闲 ----------

  test("AC-6 root→Manager queue mail deferred while any team member subtree busy, drains when idle"):
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
      // member 子树忙（Delegate 在飞）→ 全 team 不空闲
      memberRef <- resources.agentRegistry.get.map(_.get(memberMeta.id).map(_.ref))
      _ <- injectChild(resources, "member-child", memberRef)
      // root → boss queue mail（等全 team 空闲——member 忙 → 不投递）
      _ <- MailTool.queueToSession(
        bossMeta.id, "boss", "A6_TEAM_BUSY_MARKER", "INFO", Nil,
        ctxFor(resources, system, "root-sid"), system, "root-sid"
      )
      _ <- IO.sleep(600.millis) // let any (wrong) drain happen
      _ <- llm.requests.update(_ => Nil) // 清零基线（若被错误投递会再出现）
      queueBusy <- MailQueueStore.load(bossMeta.id)
      // member 子树空闲 → 重发 MailQueued → gate 通过 → 投递
      _ <- removeChild(resources, "member-child")
      bossRef <- resources.agentRegistry.get.map(_.get(bossMeta.id).map(_.ref))
      head <- MailQueueStore.load(bossMeta.id).map(_.headOption)
      _ <- head.traverse_(h => bossRef.traverse_(ref => ref ! AgentCommand.MailQueued(h, "root-sid")))
      _ <- waitUntil(20.seconds)(llm.requests.get.map(_.nonEmpty))
      reqs <- llm.requests.get
      queueAfter <- MailQueueStore.load(bossMeta.id)
    yield (queueBusy.nonEmpty, reqs, queueAfter)

    val (busyKept, reqs, queueAfter) = io.unsafeRunSync()
    assert(clue(busyKept), "queue mail should be deferred while a team member's subtree is busy")
    val texts = reqs.flatMap(_.messages.map(_.content.fold(identity, _.mkString)))
    assert(clue(texts).exists(_.contains("A6_TEAM_BUSY_MARKER")), "queue mail never drained after team idle")
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

end MailIdleGateWiringSpec
