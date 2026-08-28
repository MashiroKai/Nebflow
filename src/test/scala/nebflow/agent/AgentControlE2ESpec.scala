package nebflow.agent

import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, ActorRef, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{AgentControlTool, FileLockManager, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.{ContentBlock, FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, StreamChunk}

import scala.concurrent.duration.*

/**
 * AgentControl spec §6 C1/C2：cancel / restart 全链路 E2E。
 *
 * 真 parent AgentActor（name="Nebula"——AgentControl/Delegate 是
 * NebulaExclusiveTools，非 Nebula 名字会被 buildAllowedToolSet 剥离）驱动真
 * DelegateTool spawn 真 child + BackoffSupervisor；mock LLM 按 req.sessionId
 * 路由：
 *  - parent：#1 Delegate toolcall / #2 text / #3+ ExternalEvent 唤醒轮
 *  - child（cancel 版）：恒 Stream.never（挂死）
 *  - child（restart 版）：#1 Read toolcall+Done（产生持久化断点）/ #2 never
 *    （挂死）/ #3+ 延迟 text+Done（断点续跑后完成）
 *
 * cancel 断言：list 含 sid → cancel → 父收 cancelled（barrier 释放=parent 第 3
 * 次 LLM 请求携带 cancelled payload）→ list 无 sid → task cancelled。
 * restart 断言：task restarting ≤3s → registry 回写重现 ≤15s（新 ref）→ child
 * 续跑请求含恢复历史 ToolResult + continue 指令 → 最终 completed。
 */
class AgentControlE2ESpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  /**
   * 会话路由 mock：parentSid 计数制；delegate-* 会话按 mode 分行为。
   * 计数与请求按 sessionId 分别记录（requests 供断言消息内容）。
   */
  private class RoutingLlm(
    parentSid: String,
    counters: Ref[IO, Map[String, Int]],
    requests: Ref[IO, List[LlmRequest]],
    mode: String, // "cancel" | "restart"
    readFile: String
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(
        counters.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, 0) + 1)) *>
          requests.update(_ :+ req)
      ) >>
        Stream.eval(counters.get.map(_.getOrElse(req.sessionId, 0))).flatMap { n =>
          if req.sessionId == parentSid then parentTurn(n)
          else if req.sessionId.startsWith("delegate-") then childTurn(n)
          else Stream(StreamChunk.TextDelta("unexpected session"), StreamChunk.Done(None, None))
        }

    private def parentTurn(n: Int): Stream[IO, StreamChunk] =
      n match
        case 1 =>
          Stream(
            StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
              id = "tc-delegate-1",
              name = "Delegate",
              // #28: agent 参数必填——self-clone 已封禁，显式指向 seed 的 Worker
              input = JsonObject(
                "prompt" -> "work on the background item".asJson,
                "description" -> s"e2e-$mode target".asJson,
                "agent" -> "Worker".asJson
              )
            )),
            StreamChunk.Done(None, None)
          )
        case _ =>
          Stream(StreamChunk.TextDelta(s"parent turn $n done"), StreamChunk.Done(None, None))

    private def childTurn(n: Int): Stream[IO, StreamChunk] =
      mode match
        case "cancel" =>
          Stream.never[IO] // 挂死，等 AgentControl cancel
        case "restart" =>
          n match
            case 1 =>
              // 产生持久化断点：Read 真实临时文件 → ToolResult 落 sessionStore
              Stream(
                StreamChunk.ToolCallChunk(nebflow.shared.ToolCall(
                  id = "tc-read-1",
                  name = "Read",
                  input = JsonObject("file_path" -> readFile.asJson)
                )),
                StreamChunk.Done(None, None)
              )
            case 2 =>
              Stream.never[IO] // 挂死，等 AgentControl restart
            case _ =>
              // 断点续跑后的完成轮：延迟制造 registry 观察窗口
              Stream.sleep[IO](600.millis).drain ++
                Stream(StreamChunk.TextDelta("resumed and finished"), StreamChunk.Done(None, None))
          end match
      end match
  end RoutingLlm

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
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
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  /** parent def：name 必须是 Nebula（AgentControl/Delegate 为 Nebula 专属）。 */
  private val nebulaDef: AgentDef =
    AgentDef(
      name = "Nebula",
      description = "root scheduler under test",
      tools = List("Read", "Delegate", "AgentControl"),
      systemPrompt = ""
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 100.millis)(
    cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true  => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def acCtx(resources: SharedResources, sid: String): ToolContext =
    ToolContext(projectRoot = os.pwd.toString, sessionId = Some(sid), sharedResources = Some(resources))

  private def acCall(resources: SharedResources, sid: String, action: String, target: String): Either[nebflow.core.tools.ToolError, String] =
    AgentControlTool.call(
      JsonObject("action" -> action.asJson, "sessionId" -> target.asJson, "reason" -> s"e2e-$action".asJson),
      acCtx(resources, sid)
    ).unsafeRunSync()

  private def childSidIn(registry: Map[String, AgentRecord]): Option[String] =
    registry.keys.find(_.startsWith("delegate-"))

  /**
   * loadCurrentDef 每 turn 从 agentLibrary 磁盘重载 "Nebula"——空目录时回落到
   * Seeds.Nebula（tools 无 Delegate/AgentControl），toolcall 会被 allowed-set
   * 过滤掉。写入显式 agent.json 钉住测试所需的工具集。
   */
  private def seedNebula(tmp: os.Path): Unit =
    val dir = tmp / "agents" / "Nebula"
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json",
      """{"name":"Nebula","displayName":"Nebula","description":"e2e root","tools":["Read","Delegate","AgentControl"]}"""
    )

  /**
   * #28：Delegate 必须显式指定 standalone 目标（self-clone 已封禁）。
   * Worker 是子代理 def——restart 模式下 child 会发起 Read toolcall，
   * tools 必须含 Read。category 缺省即 "standalone"。
   */
  private def seedWorker(tmp: os.Path): Unit =
    val dir = tmp / "agents" / "Worker"
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json",
      """{"name":"Worker","displayName":"Worker","description":"e2e delegate target","tools":["Read"]}"""
    )

  // ── C1: cancel 全链路 ──────────────────────────────────────

  test("C1: cancel — list→cancel→parent notified (barrier released)→registry cleaned→task cancelled") {
    val system = ActorSystem("ac-e2e-cancel")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    seedWorker(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val parentSid = "e2e-cancel-parent"
      val program = for
        counters <- IO.ref(Map.empty[String, Int])
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, RoutingLlm(parentSid, counters, requests, "cancel", ""))
        parentRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(parentSid),
            sessionName = Some("e2e-cancel")
          ),
          parentSid
        )
        _ <- resources.agentRegistry.update(
          _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
        )
        _ <- parentRef ! AgentCommand.UserInput("kick off the delegation", None, Some("e2e-c1-1"))
        // child spawn + registry 注册（Delegate tool 执行内完成）
        _ <- waitUntil(10.seconds)(
          resources.agentRegistry.get.map(_.keys.exists(_.startsWith("delegate-")))
        )
        registry1 <- resources.agentRegistry.get
        sid = childSidIn(registry1).get
        // list 可见
        listRes <- IO(acCall(resources, parentSid, "list", ""))
        _ = assert(listRes.toOption.get.contains(sid), s"list must show the delegate:\n${listRes.toOption.get}")
        // cancel（supervisor 路径）
        cancelRes <- IO(acCall(resources, parentSid, "cancel", sid))
        _ = assert(cancelRes.isRight, cancelRes.toString)
        _ = assert(cancelRes.toOption.get.contains("Cancel sent to supervisor"), cancelRes.toOption.get)
        // registry 清理 + task cancelled
        _ <- waitUntil(5.seconds)(resources.agentRegistry.get.map(m => !m.contains(sid)))
        _ <- waitUntil(5.seconds)(
          resources.subAgentTaskStore.findRunningTasks.map(!_.exists(_.taskId == sid))
        )
        taskAfter <- resources.subAgentTaskStore.findByTaskId(sid)
        _ = assertEquals(taskAfter.map(_.status), Some("cancelled"), s"task must be cancelled: $taskAfter")
        _ = assert(taskAfter.flatMap(_.lastError).exists(_.contains("e2e-cancel")), s"reason must be recorded: $taskAfter")
        // barrier 释放：parent 被 cancelled 事件唤醒，产生携带 payload 的 LLM 请求
        _ <- waitUntil(15.seconds)(
          requests.get.map(_.exists(_.messages.exists(_.textContent.contains("cancelled by Nebula via AgentControl"))))
        )
        reqs <- requests.get
        _ = assert(
          reqs.exists(_.messages.exists(_.textContent.contains("e2e-cancel"))),
          "the wake request must carry the cancelled notification (with reason)"
        )
        // list 不再含 sid
        listRes2 <- IO(acCall(resources, parentSid, "list", ""))
        _ = assert(!listRes2.toOption.get.contains(sid), s"sid must be gone from list:\n${listRes2.toOption.get}")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── C2: restart 全链路（断点续跑）──────────────────────────

  test("C2: restart — restarting≤3s → registry respawn 回写≤15s → 续跑含恢复历史 → completed") {
    val system = ActorSystem("ac-e2e-restart")
    val tmp = os.temp.dir()
    seedNebula(tmp)
    seedWorker(tmp)
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    val checkpointFile = tmp / "checkpoint.txt"
    os.write.over(checkpointFile, "restart-checkpoint-content")
    try
      val parentSid = "e2e-restart-parent"
      val program = for
        counters <- IO.ref(Map.empty[String, Int])
        requests <- IO.ref(List.empty[LlmRequest])
        resources <- mkResources(system, tmp, RoutingLlm(parentSid, counters, requests, "restart", checkpointFile.toString))
        parentRef <- system.spawn(
          AgentActor(
            agentDef = nebulaDef,
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 0,
            sessionId = Some(parentSid),
            sessionName = Some("e2e-restart")
          ),
          parentSid
        )
        _ <- resources.agentRegistry.update(
          _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
        )
        _ <- parentRef ! AgentCommand.UserInput("kick off the delegation", None, Some("e2e-c2-1"))
        _ <- waitUntil(10.seconds)(
          resources.agentRegistry.get.map(_.keys.exists(_.startsWith("delegate-")))
        )
        registry1 <- resources.agentRegistry.get
        sid = childSidIn(registry1).get
        oldRef = registry1(sid).ref
        // child #1：Read 断点 → #2：挂死
        _ <- waitUntil(15.seconds)(
          counters.get.map(_.getOrElse(sid, 0) >= 2)
        )
        _ <- IO.sleep(500.millis) // 确保 #2 已进入挂起流
        // restart：task → restarting（≤3s）
        t0 <- IO(System.currentTimeMillis())
        restartRes <- IO(acCall(resources, parentSid, "restart", sid))
        _ = assert(restartRes.isRight, restartRes.toString)
        _ <- waitUntil(3.seconds)(
          resources.subAgentTaskStore.findByTaskId(sid).map(_.exists(_.status == "restarting"))
        )
        t1 <- IO(System.currentTimeMillis())
        _ = assert(t1 - t0 <= 3000, s"restarting must settle ≤3s, took ${t1 - t0}ms")
        // supervisor backoff（5s+jitter）→ respawn → registry 回写（新 ref）
        _ <- waitUntil(15.seconds)(
          resources.agentRegistry.get.map(m => m.get(sid).exists(r => (r.ref ne oldRef) && r.supervisorRef.isDefined))
        )
        registry2 <- resources.agentRegistry.get
        rec2 = registry2(sid)
        _ = assert(rec2.supervisorRef.isDefined, "respawned record must keep the supervisor channel (C2 registry 刷新)")
        _ = assert(rec2.lastActivityMs > 0, "respawned record must refresh lastActivityMs")
        // 续跑请求：恢复历史 ToolResult + continue 指令
        _ <- waitUntil(15.seconds)(
          requests.get.map(reqs =>
            val childReqs = reqs.filter(_.sessionId == sid)
            childReqs.size >= 3 && {
              val third = childReqs(2)
              third.messages.exists(_.textContent.contains("continue your task")) &&
              third.messages.exists(m =>
                m.content.fold(_ => false, bs => bs.exists(_.isInstanceOf[ContentBlock.ToolResult]))
              )
            }
          )
        )
        // 最终：task completed + retryCount ≥1 + parent 收 completed 唤醒
        _ <- waitUntil(20.seconds)(
          resources.subAgentTaskStore.findByTaskId(sid).map(_.exists(t => t.status == "completed" && t.retryCount >= 1))
        )
        _ <- waitUntil(15.seconds)(
          requests.get.map(_.exists(r => r.sessionId == parentSid &&
            r.messages.exists(_.textContent.contains("resumed and finished"))))
        )
        taskFinal <- resources.subAgentTaskStore.findByTaskId(sid)
      yield
        val t = taskFinal.get
        assertEquals(t.status, "completed")
        assert(t.retryCount >= 1, s"manual restart must consume the circuit-breaker budget: $t")
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  // ── AC-T: v2 升级链权限分级（§5.3.3 / §7 验收 8）────────────
  // 分级：Nebula/root 同桶全权不变 → 直接父（调用者==rec.parentSessionId）对
  // Team 成员 restart/cancel 放行 → 其他调用者 Team 只读拒绝；跨 rootSessionId 拒。

  test("AC-T1: non-parent caller restarting a Team member is rejected (Block 1 subtree guard)") {
    val system = ActorSystem("ac-t1")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        _ <- nebflow.core.flow.TeamSessionRegistry.clear
        resources <- mkResources(system, tmp, null)
        // 同 root 桶：caller 不是 target 的直接父，也不是注册 Manager
        // （Block 1 §B3：非 root 调用者走 managerAnchors 子树门 → 拒绝）
        callerSid = "caller-agent"
        teamSid = "team-member-agent"
        _ <- resources.agentRegistry.update(_ ++ Map(
          callerSid -> AgentRecord(callerSid, null, AgentKind.Team, "rootA", None),
          teamSid -> AgentRecord(teamSid, null, AgentKind.Team, "rootA", None, parentSessionId = "manager-agent")
        ))
        res <- AgentControlTool.call(
          JsonObject("action" -> "restart".asJson, "sessionId" -> teamSid.asJson, "reason" -> "e2e".asJson),
          acCtx(resources, callerSid)
        )
      yield
        assert(res.isLeft, s"non-parent restart of Team member must be rejected, got $res")
        assert(
          res.left.toOption.exists(_.message.contains("Permission denied")),
          s"rejection must be the subtree denial, got ${res.left.toOption.map(_.message)}"
        )
      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("AC-T2: direct parent (caller == parentSessionId) is allowed to restart a Team member") {
    val system = ActorSystem("ac-t2")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        _ <- nebflow.core.flow.TeamSessionRegistry.clear
        resources <- mkResources(system, tmp, null)
        managerSid = "manager-agent"
        teamSid = "team-member-agent"
        // Block 0 注册链（生产形态）：Manager 注册进 TeamSessionRegistry——
        // Block 1 子树门经 managerAnchors 放行，v2 直接父语义继续成立。
        _ <- nebflow.core.flow.TeamSessionRegistry.registerSession("inst-t2", "Manager", managerSid)
        _ <- nebflow.core.flow.TeamSessionRegistry.registerSession("inst-t2", "member", teamSid)
        _ <- nebflow.core.flow.TeamSessionRegistry.registerManager("inst-t2", managerSid)
        // probe ref 充当 Team 成员 actor（Stop 被记录；isAlive 会超时 → 执行层
        // 报「did not stop」——但权限层必须放行：错误不得含 read-only/denied）
        probeRef <- system.spawn(
          {
            def loop: Behavior[AgentCommand] =
              Behaviors.receiveMessage[AgentCommand](cmd => IO.pure(loop))
            loop
          },
          teamSid
        )
        _ <- resources.agentRegistry.update(_ ++ Map(
          managerSid -> AgentRecord(managerSid, null, AgentKind.Team, "rootA", None),
          teamSid -> AgentRecord(teamSid, probeRef, AgentKind.Team, "rootA", None, parentSessionId = managerSid)
        ))
        res <- AgentControlTool.call(
          JsonObject("action" -> "restart".asJson, "sessionId" -> teamSid.asJson, "reason" -> "parent-restart".asJson),
          acCtx(resources, managerSid)
        )
      yield
        assert(
          res.isLeft && !res.left.toOption.exists(m =>
            m.message.contains("read-only") || m.message.contains("Permission denied") || m.message.contains("Self-guard")
          ),
          s"direct parent must pass the permission layer (execution-layer failure is fine), got $res"
        )
      program.unsafeRunSync()
    finally
      nebflow.core.flow.TeamSessionRegistry.clear.void.unsafeRunSync()
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

  test("AC-T3: cross-rootSessionId restart is rejected") {
    val system = ActorSystem("ac-t3")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        resources <- mkResources(system, tmp, null)
        callerSid = "caller-other-root"
        teamSid = "team-member-agent"
        _ <- resources.agentRegistry.update(_ ++ Map(
          callerSid -> AgentRecord(callerSid, null, AgentKind.Team, "rootB", None),
          teamSid -> AgentRecord(teamSid, null, AgentKind.Team, "rootA", None, parentSessionId = "manager-agent")
        ))
        res <- AgentControlTool.call(
          JsonObject("action" -> "restart".asJson, "sessionId" -> teamSid.asJson, "reason" -> "e2e".asJson),
          acCtx(resources, callerSid)
        )
      yield
        assert(
          res.left.toOption.exists(_.message.contains("Permission denied")),
          s"cross-root restart must be rejected with Permission denied, got $res"
        )
      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
  }

end AgentControlE2ESpec
