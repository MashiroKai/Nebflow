package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import munit.CatsEffectSuite
import nebflow.actor.{ActorSystem, Behavior, Behaviors}
import nebflow.actor.{AgentCommand, AgentEvent, AgentKind, AgentRecord}
import nebflow.actor.AgentDef // W1 shim: main had nebflow.agent.AgentDef; moved to actor
import nebflow.agent.{AgentLibrary, DelegateBudget, SharedResources, SubAgentTaskStore}
import nebflow.core.FileChangeTracker
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.task.FileTaskStore
import nebflow.core.{LlmLogWriter, RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.PathUtil
import nebflow.shared.{LlmHandle, LlmRequest, LlmResponse, Message, MessageRole, StreamChunk, SubAgentTask, ThinkingConfig}

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * Mail 续接（`delegate:<id>` → 终态实例续起）结果回传回归（mail-continuation
 * delivery 修 2026-10-09）。
 *
 * 实测 bug（父会话视角，两起）：Mail 续接一个已终态的 delegate ⇒ 续起会话正常
 * 跑完、工作实际已完成，但 (a) 续起 spawn 恒传 `parentRef = None` ⇒ 监督者
 * `notifyParentAndStop` 的 None 分支是裸 `IO.unit`，终态 ExternalEvent 无投递对象，
 * 结果静默蒸发；(b) registry 行随后移除 ⇒ 会话从 AgentControl list 消失，父会话
 * 零消息、零告警。
 *
 * 本 spec 钉住修后的四条契约：
 *  1. **wiring**：续起会话的监督者拿到原父会话（registry 记录 `parentSessionId`）
 *     的**活引用**（`AgentRecord.parentRef = Some(parentRef)`）——终态回传链有锚点；
 *  2. **e2e 回传**：续起会话（真 AgentActor + stub LLM）完成后，父会话 actor 收到
 *     `ExternalEvent(source="delegate", eventType="completed")`——结果必达；
 *  3. **父不 live 面**：父会话不在注册表 ⇒ 续起照常进行（不阻断），但 Mail 回执
 *     **当场说破**（可见 NOTE），且终态仍落 taskStore（面板可见，不因父缺席而
 *     半途而废）；
 *  4. **监督者兜底（绝静默面）**：`parentRef = None` 的终态不再是裸 no-op ——
 *     wsSend 在场时发出 `delegateResultUndelivered` 显式终态帧；正向对照 =
 *     `parentRef = Some` 时父收到 ExternalEvent 且**无**兜底帧（兜底只在 None
 *     分支触发，零误报噪声）。
 *
 * Harness = BackoffSupervisorCancelSpec（SharedResources 装配）＋ MailTerminalReviveSpec
 * （temp dataRoot / StubLlm）同款拼装。
 */
class MailDelegateContinuationDeliverySpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val tempRoot: os.Path = os.Path(Files.createTempDirectory("nb-mail-cont-delivery"))
  private val originalRoot: os.Path = PathUtil.dataRoot

  override def beforeAll(): Unit =
    PathUtil.setDataRoot(tempRoot)
    LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    LlmLogWriter.setEnabled(true)
    PathUtil.setDataRoot(originalRoot)
    os.remove.all(tempRoot)

  // ── fixtures ──────────────────────────────────────────────────────────────

  /** hang=true ⇒ LLM 流永挂（钉 wiring 用的静止窗口）；hang=false ⇒ 正常完成。 */
  private class StubLlm(hang: Boolean) extends LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
        req: LlmRequest,
        onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      if hang then Stream.never[IO]
      else Stream(StreamChunk.TextDelta("continuation work done"), StreamChunk.Done(None, None))

  private def mkResources(system: ActorSystem, hang: Boolean): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = new StubLlm(hang),
      dispatcher = dispatcher,
      sessionStore = SessionStore(tempRoot / "sessions", tempRoot / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new AgentLibrary(tempRoot / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = null,
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new SubAgentTaskStore(tempRoot / s"subagent-tasks-${java.util.UUID.randomUUID().toString.take(6)}"),
      voiceMutedRef = voiceMuted
    )

  /** 记录收到的全部命令的 probe actor。 */
  private def mkRecordingCmd(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage(cmd => record.update(_ :+ cmd).as(loop))
    loop

  /** 永续存在、吞掉一切命令的 probe actor（backstop/正控的 child 座位）。 */
  private def sinkBehavior: Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage(_ => IO.pure(loop))
    loop

  /** delegate 台账里的终态旧实例（Mail 续接的对象）。 */
  private def seedDeadDelegate(id: String, parentSid: String): IO[Unit] =
    IO.blocking(
      DelegateRegistry.put(
        DelegateRegistry.Record(
          id = id,
          executor = nebflow.core.executor.ExecutorRegistry.DefaultId,
          cwd = (tempRoot / "ws").toString,
          project = None,
          taskId = None,
          parentSessionId = parentSid,
          title = "dead instance awaiting continuation",
          createdAt = System.currentTimeMillis()
        )
      )
    )

  private def rootCtx(resources: SharedResources, system: ActorSystem, sid: String): ToolContext =
    ToolContext(
      projectRoot = os.pwd.toString,
      sessionId = Some(sid),
      agentDef = Some(AgentDef(name = "Nebula", description = "spec fixture")),
      actorSystem = Some(system),
      sharedResources = Some(resources)
    )

  private def waitUntil(timeout: FiniteDuration, every: FiniteDuration = 50.millis)(
      cond: IO[Boolean]
  ): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"waitUntil: condition not met within $timeout"))
          else IO.sleep(every) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def continuationMail(resources: SharedResources, system: ActorSystem, parentSid: String, deadId: String) =
    MailTool.call(
      JsonObject("address" -> s"delegate:$deadId".asJson, "message" -> "supplement: also clean the worktree".asJson),
      rootCtx(resources, system, parentSid)
    )

  // ── (1) wiring：续起会话的监督者锚定原父会话的活引用 ─────────────────────

  test("(1) continuation wiring: the spawned session's registry record carries parentRef = the original parent's live ref"):
    val deadId = "delegate-nebflow-wire0001"
    val parentSid = "wire-parent-1"
    val system = ActorSystem("mail-cont-wire")
    for
      resources <- mkResources(system, hang = true) // 永挂流 ⇒ 新会话不终态 ⇒ registry 行稳定可读
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-wire")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedDeadDelegate(deadId, parentSid)
      res <- continuationMail(resources, system, parentSid, deadId)
      // 等新 delegate 会话出现在注册表（registerAgent 行）
      recOpt <- waitUntil(15.seconds)(
        resources.agentRegistry.get.map(_.keys.exists(k => k.startsWith("delegate-") && k != deadId))
      ) *> resources.agentRegistry.get.map(_.find((k, _) => k.startsWith("delegate-") && k != deadId))
      _ <- recOpt.map((_, v) => DelegateBudget.release(v.sessionId)).sequence.void
      _ <- system.stopAll
    yield
      val receipt = res match
        case Right(r) => r
        case Left(e)  => fail(s"the continuation must proceed, got error: ${e.message}")
      assert(receipt.contains("CONTINUED the delegate as a fresh session"), receipt)
      assert(!receipt.contains("not live right now"), "the parent IS live — the undeliverable NOTE must be absent")
      val (_, recVal) = recOpt.getOrElse(fail("the continuation session must be registered"))
      assertEquals(recVal.parentRef, Some(parentRef),
        "the continuation supervisor must be anchored to the original parent session's live ref (result-return anchor)")
      assertEquals(recVal.parentSessionId, parentSid)

  // ── (2) e2e：续起会话跑完 ⇒ 父会话收到 completed ExternalEvent ────────────

  test("(2) e2e: the continuation session completes and the result REACHES the parent session"):
    val deadId = "delegate-nebflow-e2e00001"
    val parentSid = "e2e-parent-1"
    val system = ActorSystem("mail-cont-e2e")
    for
      resources <- mkResources(system, hang = false)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-e2e")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedDeadDelegate(deadId, parentSid)
      res <- continuationMail(resources, system, parentSid, deadId)
      _ = assert(res.isRight, s"continuation must proceed: ${res.swap.map(_.message).getOrElse("")}")
      // 续起会话（真 AgentActor + stub LLM）自然跑完 ⇒ 监督者终态 ⇒ 父收到回传
      _ <- waitUntil(30.seconds)(parentEvents.get.map(_.exists(_.isInstanceOf[AgentCommand.ExternalEvent])))
      events <- parentEvents.get
      _ <- system.stopAll
    yield
      val ext = events.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(ext.size, 1, s"exactly one terminal ExternalEvent expected, got: $events")
      assertEquals(ext.head.source, "delegate", "source must stay delegate")
      assertEquals(ext.head.eventType, "completed", "the continuation finished successfully — completed must reach the parent")
      assert(ext.head.payload.nonEmpty, "the result payload must not be empty")
      assert(ext.head.metadata("description").flatMap(_.asString).exists(_.startsWith("continuation:")),
        s"the description must mark the continuation, got: ${ext.head.metadata}")
      assertEquals(ext.head.correlationId.map(_.startsWith("delegate-")), Some(true))

  // ── (3) 父不 live 面：续起照常 + 回执当场说破 + 终态仍落 taskStore ─────────

  test("(3) parent not live: continuation still proceeds, the receipt says so up front, terminal state still lands in the task store"):
    val deadId = "delegate-nebflow-gone0001"
    val parentSid = "gone-parent-1" // NOT registered in the registry — the parent is gone
    val system = ActorSystem("mail-cont-gone")
    for
      resources <- mkResources(system, hang = false)
      _ <- seedDeadDelegate(deadId, parentSid)
      res <- continuationMail(resources, system, parentSid, deadId)
      newSidOpt <- waitUntil(15.seconds)(
        resources.agentRegistry.get.map(_.keys.exists(k => k.startsWith("delegate-") && k != deadId))
      ) *> resources.agentRegistry.get.map(_.keys.find(k => k.startsWith("delegate-") && k != deadId))
      // 续起会话自然跑完（无父可通知——监督者走 undelivered 兜底）⇒ 终态仍落 taskStore
      _ <- waitUntil(30.seconds)(
        resources.subAgentTaskStore.loadTasks(parentSid).map(_.exists(t => t.status == "completed"))
      )
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      stored <- newSidOpt.traverse(sid => IO.blocking(DelegateRegistry.find(sid)))
      _ <- newSidOpt.traverse_(DelegateBudget.release)
      _ <- system.stopAll
    yield
      val receipt = res match
        case Right(r) => r
        case Left(e)  => fail(s"a dead parent must NOT block the continuation, got error: ${e.message}")
      assert(receipt.contains("CONTINUED the delegate as a fresh session"), receipt)
      assert(receipt.contains("is not live right now"),
        "the receipt must say up front that the result cannot be delivered back (never silent)")
      assert(receipt.contains(parentSid), "the NOTE must name the unreachable parent session")
      assert(newSidOpt.isDefined, s"the continuation session must be registered")
      assert(stored.exists(_.isDefined), s"the continuation record must persist: $newSidOpt")
      assert(tasks.exists(t => t.status == "completed"),
        s"the terminal state must still land in the task store keyed by the (dead) parent session id, got: $tasks")

  // ── (4) 监督者兜底：parentRef=None 的终态发出显式 WS 帧，绝不静默 ──────────

  test("(4) supervisor backstop: parentRef=None + completed ⇒ delegateResultUndelivered WS frame + task store completed"):
    val system = ActorSystem("mail-cont-backstop")
    val parentSid = "backstop-parent"
    val childSid = "delegate-nebflow-backsto1"
    for
      resources <- mkResources(system, hang = false)
      _ <- seedDeadDelegate(childSid, parentSid)
      _ <- resources.subAgentTaskStore.recordTask(SubAgentTask(
        taskId = childSid,
        parentSessionId = parentSid,
        agentName = "nebflow",
        prompt = "backstop probe",
        description = "backstop probe",
        status = "running",
        retryCount = 0,
        spawnedAt = System.currentTimeMillis(),
        completedAt = None,
        lastError = None,
        source = "delegate"
      ))
      wsFrames <- Ref.of[IO, List[Json]](Nil)
      childRef <- system.spawn(sinkBehavior, "backstop-child")
      supervisor <- system.spawn(
        nebflow.agent.BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen here")),
          childName = childSid,
          parentRef = None, // 🔴 the bug face: no delivery channel at all
          description = "backstop probe",
          agentName = "nebflow",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "backstop probe",
          source = "delegate",
          wsSend = Some(json => wsFrames.update(_ :+ json))
        ),
        "backstop-supervisor"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(Message(MessageRole.Assistant, Left("work actually done"))))
      _ <- waitUntil(15.seconds)(
        resources.subAgentTaskStore.loadTasks(parentSid).map(_.exists(t => t.taskId == childSid && t.status == "completed"))
      )
      frames <- wsFrames.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      registryAfter <- resources.agentRegistry.get
      _ <- DelegateBudget.release(childSid)
      _ <- system.stopAll
    yield
      val undelivered = frames.filter(json => json.hcursor.get[String]("type").toOption.contains("delegateResultUndelivered"))
      assertEquals(undelivered.size, 1, s"exactly one undelivered-terminal frame expected, got: $frames")
      assertEquals(undelivered.head.hcursor.get[String]("sessionId").toOption, Some(childSid))
      assertEquals(undelivered.head.hcursor.get[String]("eventType").toOption, Some("completed"))
      assertEquals(undelivered.head.hcursor.get[String]("source").toOption, Some("delegate"))
      assert(tasks.find(_.taskId == childSid).exists(_.status == "completed"),
        "the task store terminal write still lands (panel-visible state)")
      assert(!registryAfter.contains(childSid), "the registry row is still cleaned up")

  // ── (5) 正向对照：parentRef=Some ⇒ 父收到 ExternalEvent，无兜底帧 ──────────

  test("(5) positive control: parentRef=Some ⇒ ExternalEvent delivered, NO undelivered frame (backstop never false-fires)"):
    val system = ActorSystem("mail-cont-posctl")
    val parentSid = "posctl-parent"
    val childSid = "delegate-nebflow-posctl1"
    for
      resources <- mkResources(system, hang = false)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "posctl-parent-rec")
      _ <- seedDeadDelegate(childSid, parentSid)
      wsFrames <- Ref.of[IO, List[Json]](Nil)
      childRef <- system.spawn(sinkBehavior, "posctl-child")
      supervisor <- system.spawn(
        nebflow.agent.BackoffSupervisor(
          childRef = childRef,
          childSpawnFn = (_, _) => IO.raiseError(new RuntimeException("respawn must not happen here")),
          childName = childSid,
          parentRef = Some(parentRef),
          description = "positive control",
          agentName = "nebflow",
          subagentId = childSid,
          parentSessionId = parentSid,
          resources = resources,
          initialPrompt = "positive control",
          source = "delegate",
          wsSend = Some(json => wsFrames.update(_ :+ json))
        ),
        "posctl-supervisor"
      )
      _ <- supervisor ! AgentEvent.Completed(childSid, List(Message(MessageRole.Assistant, Left("done"))))
      _ <- waitUntil(15.seconds)(parentEvents.get.map(_.exists(_.isInstanceOf[AgentCommand.ExternalEvent])))
      events <- parentEvents.get
      frames <- wsFrames.get
      _ <- DelegateBudget.release(childSid)
      _ <- system.stopAll
    yield
      val ext = events.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(ext.size, 1, s"the parent must receive exactly one ExternalEvent, got: $events")
      assertEquals(ext.head.eventType, "completed")
      val undelivered = frames.filter(json => json.hcursor.get[String]("type").toOption.contains("delegateResultUndelivered"))
      assertEquals(undelivered, Nil, "the backstop must NOT fire when a parent ref exists")

end MailDelegateContinuationDeliverySpec
