package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.{Json, JsonObject}
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, AgentCommand, AgentDef, AgentKind, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.{DelegateFreeze, DelegateRegistry}
import nebflow.core.mail.SessionMailbox
import nebflow.core.node.NodeRunner
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{FileLockManager, MailTool, ToolContext}
import nebflow.core.{LlmLogWriter, RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * 错误冻结批（2026-11-01 · 作者令逐字「所有因错误状态的 delegate 应该上报之后 要能
 * 保留状态 冻结态 方便被唤醒 接着做」）—— **引擎级端到端实测**（真 AgentActor +
 * 真 BackoffSupervisor + 真 Mail 工具；只把 LLM 换成可控 stub）。
 *
 * 一条链走完三件事（验收 ②「造 provider 故障 → 冻结回执 → 唤醒 → 续做」）：
 *
 *  1. **造 provider 故障**：子会话首轮 LLM 抛错 ⇒ AgentProcessing 发
 *     `AgentEvent.Failed` ⇒ 监督者冻结腿（不是 failed 终态）：registry 冻结槽落盘
 *     + 台账行 `frozen` + 父持久邮箱一条 `eventType="frozen"` 回执 + 面板
 *     `delegateFrozen` 帧；**零** failed 终态。
 *  2. **唤醒**：父会话调 Mail `delegate:<id>`（作者面入口）⇒ live 腿向**原 actor**
 *     注入带 `[frozen-wake]` 横幅的 UserInput ⇒ 触发下一回合（第二轮 LLM）。
 *  3. **续做 = 别丢上下文**（本件最关键断言）：第二轮 LLM 请求的 messages 里
 *     必须带**冻结前**的原 brief，且首条新输入带唤醒横幅 + 唤醒者指令；session id
 *     全程逐字不变（同 id、同 actor、同历史——绝不是新建会话）。
 */
class DelegateFreezeE2ESpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 120.seconds

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd / "target" / "test-freeze-e2e"

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-freeze-e2e-")
    PathUtil.setDataRoot(base / "data")
    LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    LlmLogWriter.setEnabled(true)
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(base)
    catch case _: Throwable => ()

  /**
   * 会话路由 LLM：
   *   - 子会话**第 1 次**调用 ⇒ 抛 provider 故障（鉴权类永久失败：provider 侧拒绝，
   *     不是 agent 的错）；
   *   - 第 2 次起 ⇒ 正常回文字（唤醒后的续做轮）；
   *   - 其它会话（父）⇒ 不应被调用（父在此用例里只是 mailbox 收件人）。
   * 每个请求都记进 `requests`（续做轮的历史断言素材）。
   *
   * **为什么挑「永久」类而不是 529 overloaded（重要，语义边界）**：agent 层既有
   * 的 v2 错误冻结（[[nebflow.agent.AgentFrozen.enterErrorFrozen]]，`llm-transient`
   * 族）会**自己接走** transient/overload 类 LLM 失败——退避到期自动续跑，不发
   * `AgentEvent.Failed`，因此监督者根本看不到事件。那条腿是「等条件消失后自愈」，
   * 与本批「异常就停住、等作者唤醒」的 delegate 冻结族语义不同、且**不冲突**：
   * 只有真正升级到 supervisor 的失败事件（永久类 LLM 失败 / 工具层不可恢复 /
   * 引擎侧异常 / 配额取消）才是本批冻结腿的入口。本用例因此用 401 鉴权类永久失败
   * ——provider 侧拒绝、真的会升级成 `AgentEvent.Failed`，冻结腿才会被触发。
   */
  private class RoutingLlm(
    childSid: String,
    counters: Ref[IO, Map[String, Int]],
    requests: Ref[IO, List[LlmRequest]]
  ) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this spec"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(
        counters.update(m => m.updated(req.sessionId, m.getOrElse(req.sessionId, 0) + 1)) *>
          requests.update(_ :+ req)
      ) >>
        Stream.eval(counters.get.map(_.getOrElse(req.sessionId, 0))).flatMap { n =>
          // 首轮 = provider 侧永久拒绝（鉴权失败）⇒ 无自愈退避路径 ⇒ 升级为
          // AgentEvent.Failed ⇒ 监督者冻结腿。第 2 次起 = 唤醒后的续做轮，正常回文字。
          if req.sessionId == childSid && n == 1 then
            Stream.raiseError[IO](
              new RuntimeException("provider anthropic/claude-x: 401 unauthorized - invalid_api_key (rejected by the provider)")
            )
          else Stream(StreamChunk.TextDelta("RESUMED_AFTER_FREEZE"), StreamChunk.Done(None, None))
        }

  private final case class Fx(
    resources: SharedResources,
    system: ActorSystem,
    tmp: os.Path,
    seat: os.Path,
    frames: Ref[IO, List[Json]],
    requests: Ref[IO, List[LlmRequest]]
  )

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = base / name
    val seat = os.temp.dir(prefix = s"freeze-e2e-seat-$name")
    val system = ActorSystem(s"freeze-e2e-$name-${java.util.UUID.randomUUID().toString.take(6)}")
    val io =
      for
        counters <- Ref.of[IO, Map[String, Int]](Map.empty)
        requests <- Ref.of[IO, List[LlmRequest]](Nil)
        frames <- Ref.of[IO, List[Json]](Nil)
        dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        sessionStore = SessionStore(tmp / "sessions", tmp / "tasks")
        store = new SubAgentTaskStore(tmp / "subagent-tasks")
        resources = SharedResources(
          llm = new RoutingLlm("delegate-freeze-e2e", counters, requests),
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
          subAgentTaskStore = store,
          voiceMutedRef = voiceMuted
        )
        _ <- body(Fx(resources, system, tmp, seat, frames, requests))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(seat)

  private def mkRecordingParent(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] = Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
    loop

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 100.millis)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError("waitUntil: condition not met in time"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def mailboxEvents(parentSid: String): IO[List[AgentCommand.ExternalEvent]] =
    for
      pending <- SessionMailbox.scanPending(parentSid)
      taken <- SessionMailbox.scanTaken(parentSid)
    yield (pending ++ taken).map(_.event)

  private def mkTask(id: String, parent: String): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "build the widget: the frame first, then the wiring",
      description = s"freeze e2e $id",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  /** 父会话上下文（Mail 工具的调用面——Nebula 是 RootExclusive 工具宿主）。 */
  private def parentCtx(res: SharedResources, system: ActorSystem, sid: String): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(sid),
      agentDef = Some(AgentDef(name = "Nebula", description = "freeze e2e root")),
      actorSystem = Some(system),
      sharedResources = Some(res)
    )

  test(
    "e2e: provider failure freezes the delegate (frozen receipt, never a failure verdict) → Mail `delegate:<id>` wakes it in the SAME session → the continuation turn still carries the pre-freeze history"
  ) {
    withFx("main") { fx =>
      val parentSid = "freeze-e2e-parent"
      val childSid = "delegate-freeze-e2e"
      val seatCwd = fx.seat.toString
      val childDef = AgentDef(name = "Worker", description = "freeze e2e worker", tools = List("Read"))
      for
        // 父会话：mailbox 收件人（冻结回执的投递目标）。
        _ <- fx.resources.sessionStore.getOrCreateSession(parentSid, "freeze e2e parent", agentName = Some("Nebula"))
        _ <- fx.resources.subAgentTaskStore.recordTask(mkTask(childSid, parentSid))
        _ <- IO(
          DelegateRegistry.put(
            DelegateRegistry.Record(
              id = childSid,
              executor = nebflow.core.executor.ExecutorRegistry.DefaultId,
              cwd = seatCwd,
              project = None,
              taskId = None,
              parentSessionId = parentSid,
              title = "freeze e2e delegate",
              createdAt = System.currentTimeMillis()
            )
          )
        )
        // 冻结前的现场（首轮已由父派活）：brief 落到 transcript。
        brief = Message(MessageRole.User, Left("build the widget: the frame first, then the wiring"))
        _ <- fx.resources.sessionStore.saveMessagesForSession(childSid, List(brief))
        parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- fx.system.spawn(mkRecordingParent(parentEvents), "freeze-e2e-parent-rec")
        params = NodeRunner.SpawnParams(
          agentDef = childDef,
          resources = fx.resources,
          sessionId = childSid,
          sessionName = "freeze e2e delegate",
          depth = 1,
          parentRef = Some(parentRef),
          wsSend = (j: Json) => fx.frames.update(_ :+ j),
          projectRoot = Some(seatCwd),
          rootSessionId = parentSid,
          initialMessages = List(brief)
        )
        childRef <- fx.resources.spawnAgentActor(fx.system, params)
        adapterRef <- fx.resources.spawnSupervisedAdapter(
          system = fx.system,
          params = params,
          childRef = childRef,
          childName = childSid,
          description = "freeze e2e delegate",
          agentName = "Worker",
          subagentId = childSid,
          parentSessionId = parentSid,
          initialPrompt = "build the widget: the frame first, then the wiring",
          source = "delegate",
          wsSend = Some((j: Json) => fx.frames.update(_ :+ j))
        )
        _ <- NodeRunner.registerAgent(
          fx.resources,
          id = childSid,
          ref = childRef,
          kind = AgentKind.Delegate,
          rootSessionId = parentSid,
          parentRef = Some(parentRef),
          startedAt = System.currentTimeMillis(),
          supervisorRef = Some(adapterRef),
          parentSessionId = parentSid
        )
        // ① 派活 ⇒ 首轮 LLM 抛 provider 故障 ⇒ 监督者冻结腿。
        _ <- childRef ! AgentCommand.UserInput("start: build the widget", Some(adapterRef))
        _ <- waitUntil(30.seconds)(
          fx.resources.subAgentTaskStore
            .findByTaskId(childSid)
            .map(_.exists(_.status == DelegateFreeze.TaskStatus))
        )
        // fallback 废除批（作者令 2026-11-06 20:36「只在子agent面板里显示……不再向上
        // 通知」）：冻结回执不再投父邮箱——面板帧（delegateFrozen）是唯一即时呈现面。
        // 原「等父邮箱出现 frozen 回执」面随批退役（原断言逐字见 git 历史）。
        _ <- waitUntil(30.seconds)(
          fx.frames.get.map(
            _.exists(_.hcursor.get[String]("type").toOption.contains("delegateFrozen"))
          )
        )
        _ <- IO.sleep(300.millis) // 沉降：确认没有第二件终态悄悄落地
        frozenRecord <- IO(DelegateRegistry.find(childSid).get)
        frozenRow <- fx.resources.subAgentTaskStore.findByTaskId(childSid)
        frozenReg <- fx.resources.agentRegistry.get.map(_.get(childSid))
        frozenMail <- mailboxEvents(parentSid)
        frozenFrames <- fx.frames.get
        // ② 唤醒（作者面入口：Mail delegate:<id>）——live 腿注入原 actor。
        wakeRes <- MailTool.call(
          JsonObject(
            "address" -> s"delegate:$childSid".asJson,
            "message" -> "继续把 wiring 补完（provider 已恢复）".asJson
          ),
          parentCtx(fx.resources, fx.system, parentSid)
        )
        _ = assert(wakeRes.isRight, s"the wake mail must be accepted: $wakeRes")
        wakeReceipt = wakeRes.toOption.getOrElse("")
        _ = assert(
          wakeReceipt.toLowerCase.contains("same session"),
          s"the receipt must say it woke the SAME session (not a fresh one): $wakeReceipt"
        )
        // ③ 续做轮：第二轮 LLM 请求必须带冻结前的 brief（上下文没丢）。
        _ <- waitUntil(30.seconds) {
          fx.requests.get.map(rs => rs.count(_.sessionId == childSid) >= 2)
        }
        resumedRequests <- fx.requests.get.map(_.filter(_.sessionId == childSid))
        resumedTurn = resumedRequests.last
        wakeTranscript <- fx.resources.sessionStore.loadMessagesForSession(childSid)
        // 唤醒收口：冻结槽清空 + registry 状态回 Idle + 台账行回 running。
        wokeRecord <- IO(DelegateRegistry.find(childSid))
        wokeRow <- fx.resources.subAgentTaskStore.findByTaskId(childSid)
        wokeReg <- fx.resources.agentRegistry.get.map(_.get(childSid))
        frames <- fx.frames.get
        childEvents <- parentEvents.get
        _ <- fx.system.stopAll.attempt.void
      yield
        // ── ① 冻结面：状态保留 + 上报诚实 ─────────────────────────────────
        assert(frozenRecord.frozenSince.isDefined, "frozenSince persisted on the registry record")
        assertEquals(
          frozenRecord.frozenReason,
          Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)),
          "the reason code is written into the state (llm-failure)"
        )
        assert(
          frozenRecord.frozenDetail.exists(_.contains("401")),
          s"the provider error context is kept: ${frozenRecord.frozenDetail}"
        )
        assertEquals(frozenRow.map(_.status), Some(DelegateFreeze.TaskStatus), "the ledger row is `frozen`, not `failed`")
        assertEquals(
          frozenReg.map(_.status),
          Some(nebflow.actor.AgentStatus.Frozen),
          "the registry shows Frozen (the panel badge source)"
        )
        val terminals = frozenMail.map(_.eventType).filter(t => Set("failed", "completed", "cancelled").contains(t))
        assertEquals(terminals, Nil, s"a frozen delegate must never produce a failure terminal, got: ${frozenMail.map(_.eventType)}")
        // fallback 废除批：父邮箱零条目——冻结回执不再投递（面板帧是唯一即时面）。
        assertEquals(
          frozenMail.map(_.eventType),
          Nil,
          s"zero-notify: no frozen receipt in the parent mailbox, got: ${frozenMail.map(_.eventType)}"
        )
        assert(
          frozenFrames.exists(_.hcursor.get[String]("type").toOption.contains("delegateFrozen")),
          s"a delegateFrozen panel frame must be emitted, got: ${frozenFrames.map(_.hcursor.get[String]("type").toOption)}"
        )
        // ── ② 唤醒：同一 actor、同一 session id、冻结槽清空、行回 running ──
        assertEquals(DelegateRegistry.find(childSid).flatMap(_.frozenSince), None, "the frozen slot is cleared on wake")
        assertEquals(wokeReg.map(_.status), Some(nebflow.actor.AgentStatus.Idle), "registry back to Idle after the wake")
        assertEquals(wokeRow.map(_.status), Some("running"), "the ledger row is honestly in-flight again")
        // ── ③ 续做 = 别丢上下文（本件硬断言）────────────────────────────
        assertEquals(resumedTurn.sessionId, childSid, "the continuation runs under the SAME session id (no fresh session)")
        val allText = resumedTurn.messages.map(_.textContent).mkString("\n---\n")
        assert(
          allText.contains("build the widget: the frame first, then the wiring"),
          s"the pre-freeze brief must still be in the continuation request:\n${allText.take(1200)}"
        )
        assert(
          allText.contains("frozen-wake") && allText.contains("do NOT redo"),
          s"the wake banner must lead the new input:\n${allText.take(1200)}"
        )
        assert(
          allText.contains("继续把 wiring 补完"),
          s"the waker's instruction must reach the session:\n${allText.take(1200)}"
        )
        assert(
          resumedTurn.messages.size >= 3,
          s"history + wake input + instruction must all be present, got ${resumedTurn.messages.size} messages"
        )
        assert(
          wakeTranscript.exists(m => m.textContent.contains("继续把 wiring 补完")),
          "the wake input is persisted in the same transcript"
        )
        assert(
          wokeRecord.map(_.id).contains(childSid),
          "the registry record is kept (wake address anchor)"
        )
        // 唤醒后父会话没有额外终态（本回合还没走完判词链）——零噪声。
        assertEquals(
          childEvents.collect { case e: AgentCommand.ExternalEvent => e.eventType }.filter(t => t == "failed"),
          Nil,
          s"no failure push on the legacy chain either, got: $childEvents"
        )
    }
  }
end DelegateFreezeE2ESpec
