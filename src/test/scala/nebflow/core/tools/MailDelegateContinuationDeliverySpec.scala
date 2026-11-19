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
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.{LlmLogWriter, RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.PathUtil
import nebflow.shared.{
  LlmHandle,
  LlmRequest,
  LlmResponse,
  Message,
  MessageRole,
  StreamChunk,
  SubAgentTask,
  ThinkingConfig
}

import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * Mail 续接（`delegate:<id>` → 终态实例续命）结果回传回归（mail-continuation
 * delivery 修 2026-10-09；**会话续命名与位修正批 2026-11-03 重钉**）。
 *
 * 2026-10-09 实测 bug（父会话视角，两起）：Mail 续接一个已终态的 delegate ⇒ 续起
 * 会话正常跑完、工作实际已完成，但 (a) 续起 spawn 恒传 `parentRef = None` ⇒ 监督者
 * `notifyParentAndStop` 的 None 分支是裸 `IO.unit`，终态 ExternalEvent 无投递对象，
 * 结果静默蒸发；(b) registry 行随后移除 ⇒ 会话从 AgentControl list 消失。
 *
 * 2026-11-03 批（作者 16:48/16:51/16:53 三段报障）把续命语义改为**原位恢复优先**：
 * 历史完好的终态实例 ⇒ **同一 sessionId** 复活（转录重放追加、作用域图同 id 重挂
 * ⇒ 链节点归属与事件路由不变）；派生只留给 cancel 墓碑 / 转录损坏（断言迁
 * [[SessionResumptionSpec]]）。本 spec 钉住重钉后的契约：
 *  1. **wiring**：原位续后**同一会话 id** 在统一注册表复活为 Delegate 活体，且监督者
 *     拿到原父会话（registry 记录 `parentSessionId`）的**活引用**（回传链锚点不变）；
 *  2. **Pass-through leg**: a resumed turn that ends with plain text is the completion
 *     delivery — the final text is mailed to the parent exactly once and the resumed
 *     session is released;
 *  3. **父不 live 面**：父会话不在注册表 ⇒ 原位续照常进行（不阻断），但 Mail 回执
 *     **当场说破**（可见 NOTE）；
 *  4. 父会话无论是否 live，完成结果只入持久 SessionMailbox 一次，不叠 raw actor event。
 *  5. A completed workspace round hands over its final text and releases the owner actor
 *     without integrating; the workspace stays open and a later Mail round resumes the same
 *     session in place. A closed workspace never resumes; an unexpected exit needs manual recovery.
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
    // Teardown races the tail of async writers (session-store dirty flushes,
    // flow-map host writes) that can re-create entries between removal passes,
    // surfacing as DirectoryNotEmptyException. Retry with backoff; if the tree
    // still cannot be fully removed, say so and move on — a leftover temp tree
    // must not fail an otherwise green spec run.
    val maxAttempts = 5
    var cleanupError: Option[Throwable] = None
    for attempt <- 1 to maxAttempts do
      try os.remove.all(tempRoot)
      catch
        case e: java.io.IOException =>
          cleanupError = Some(e)
          if attempt < maxAttempts then Thread.sleep(200L * attempt)
    cleanupError.foreach(e =>
      System.err.println(s"[afterAll] temp data root cleanup incomplete after $maxAttempts attempts: $e")
    )
  end afterAll

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
      subAgentTaskStore =
        new SubAgentTaskStore(tempRoot / s"subagent-tasks-${java.util.UUID.randomUUID().toString.take(6)}"),
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
  private def seedDeadDelegate(resources: SharedResources, id: String, parentSid: String): IO[Unit] =
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
    ) *> resources.sessionStore.durableSwapMessages(
      id,
      List(Message(MessageRole.User, Left("original saved delegate task")))
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

  test("nonexistent external delegate mail returns a clear not-found error"):
    val system = ActorSystem("external-mail-not-found")
    for
      resources <- mkResources(system, hang = true)
      result <- continuationMail(resources, system, "not-found-parent", "external-codex-doesnotexist")
      _ <- system.stopAll
    yield
      assert(result.isLeft, result.toString)
      val error = result.swap.toOption.get.message
      assert(error.contains("DELEGATE_NOT_FOUND"), error)
      assert(!error.contains("null"), error)

  test("external registry entry without actor ref returns an explicit error, not a null dereference"):
    val id = "external-mail-null-ref-regression"
    val parent = "external-mail-parent"
    val system = ActorSystem("external-mail-null-ref")
    for
      resources <- mkResources(system, hang = true)
      _ <- seedDeadDelegate(resources, id, parent)
      _ <- IO.blocking(DelegateRegistry.put(DelegateRegistry.find(id).get.copy(executor = "unknown-external-adapter")))
      _ <- resources.agentRegistry.update(_ + (id -> nebflow.actor.ExternalAgentRecord.build(id, parent, parent, 0L)))
      result <- continuationMail(resources, system, parent, id)
      _ <- system.stopAll
    yield
      assert(result.isLeft, result.toString)
      assert(result.swap.toOption.get.message.contains("EXTERNAL_ADAPTER_MISSING"), result.toString)

  // ── (1) wiring：原位续后同一 id 复活，监督者锚定原父会话的活引用 ─────────

  test(
    "(1) continuation wiring: the SAME session id comes back live and its record keeps parentRef = the original parent's live ref"
  ):
    val deadId = "delegate-nebflow-wire0001"
    val parentSid = "wire-parent-1"
    val system = ActorSystem("mail-cont-wire")
    for
      resources <- mkResources(system, hang = true) // 永挂流 ⇒ 不终态 ⇒ registry 行稳定可读
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "parent-rec-wire")
      _ <- resources.agentRegistry.update(
        _ + (parentSid -> AgentRecord(parentSid, parentRef, AgentKind.Root, parentSid, None))
      )
      _ <- seedDeadDelegate(resources, deadId, parentSid)
      res <- continuationMail(resources, system, parentSid, deadId)
      // 原位续：**同一个** id 复活为活体（不再派生新会话）
      _ <- waitUntil(15.seconds)(resources.agentRegistry.get.map(_.contains(deadId)))
      recOpt <- resources.agentRegistry.get.map(_.get(deadId))
      _ <- recOpt.traverse_(rec => DelegateBudget.release(rec.sessionId))
      _ <- system.stopAll
    yield
      val receipt = res match
        case Right(r) => r
        case Left(e) => fail(s"the continuation must proceed, got error: ${e.message}")
      assert(receipt.contains("RESUMED IT IN PLACE"), receipt)
      assert(receipt.contains("same session id"), receipt)
      assert(!receipt.contains("not live right now"), "the parent IS live — the undeliverable NOTE must be absent")
      assert(!receipt.contains("resumedFrom="), "in-place resume carries no lineage (same identity)")
      val recVal = recOpt.getOrElse(fail("the resumed session must be registered under the SAME id"))
      assertEquals(recVal.kind, AgentKind.Delegate)
      assertEquals(
        recVal.parentRef,
        Some(parentRef),
        "the continuation supervisor must be anchored to the original parent session's live ref (result-return anchor)"
      )
      assertEquals(recVal.parentSessionId, parentSid)
    end for

  // ── (2) Pass-through leg: the resumed turn's end is the completion delivery (final text mailed to the parent, session released) ──

  test(
    "(2) resumed turn ending with plain text ⇒ completion delivery (pass-through): the final text reaches the parent's mailbox exactly once and the resumed session is released"
  ):
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
      _ <- seedDeadDelegate(resources, deadId, parentSid)
      res <- continuationMail(resources, system, parentSid, deadId)
      _ = assert(res.isRight, s"continuation must proceed: ${res.swap.map(_.message).getOrElse("")}")
      // The resumed turn (stub echo) ends naturally ⇒ pass-through completion; let it settle first.
      _ <- waitUntil(30.seconds)(
        resources.sessionStore
          .loadMessagesForSession(deadId)
          .map(_.map(_.textContent).mkString.contains("continuation work done"))
      )
      _ <- IO.sleep(800.millis)
      events <- parentEvents.get
      pending <- SessionMailbox.scanPending(parentSid)
      released <- waitUntil(10.seconds)(resources.agentRegistry.get.map(!_.contains(deadId))).attempt.map(_.isRight)
      _ <- DelegateBudget.release(deadId).attempt.void
      _ <- system.stopAll
    yield
      // Pass-through delivery: the resumed turn's end is the completion delivery. The final text is
      // mailed to the parent exactly once (the mailbox is the delivery channel); the parent actor
      // itself only receives the doorbell. The completion releases the resumed session; a later Mail
      // resumes it in place again.
      val delivered =
        pending.filter(e => e.event.eventType == "completed" && e.event.payload.contains("continuation work done"))
      assertEquals(
        delivered.size,
        1,
        s"the resumed turn's final text must reach the parent's mailbox exactly once, got pending=$pending events=$events"
      )
      assert(events.contains(AgentCommand.MailboxArrived), s"the parent is rung once the entry is mailed, got: $events")
      assert(released, "the completion releases the resumed session from the registry")
    end for

  // ── (3) 父不 live 面：原位续照常 + 回执当场说破 ─────────────────────────

  test("(3) parent not live: in-place resume still proceeds and the receipt says so up front (never silent)"):
    val deadId = "delegate-nebflow-gone0001"
    val parentSid = "gone-parent-1" // NOT registered in the registry — the parent is gone
    val system = ActorSystem("mail-cont-gone")
    for
      resources <- mkResources(system, hang = true)
      _ <- seedDeadDelegate(resources, deadId, parentSid)
      res <- continuationMail(resources, system, parentSid, deadId)
      _ <- waitUntil(15.seconds)(resources.agentRegistry.get.map(_.contains(deadId)))
      recOpt <- IO.blocking(DelegateRegistry.find(deadId))
      _ <- DelegateBudget.release(deadId).attempt.void
      _ <- system.stopAll
    yield
      val receipt = res match
        case Right(r) => r
        case Left(e) => fail(s"a dead parent must NOT block the continuation, got error: ${e.message}")
      assert(receipt.contains("RESUMED IT IN PLACE"), receipt)
      assert(
        receipt.contains("is not live right now"),
        "the receipt must say up front that the result cannot be delivered back (never silent)"
      )
      assert(receipt.contains(parentSid), "the NOTE must name the unreachable parent session")
      assert(recOpt.isDefined, "the registry record is kept (the same identity resumes)")
    end for

  // ── (4) 监督者兜底：parentRef=None 的终态发出显式 WS 帧，绝不静默 ──────────

  test("(4) parentRef=None: completion persists in the parent mailbox without a legacy backstop frame"):
    val system = ActorSystem("mail-cont-backstop")
    val parentSid = "backstop-parent"
    val childSid = "delegate-nebflow-backsto1"
    for
      resources <- mkResources(system, hang = false)
      _ <- seedDeadDelegate(resources, childSid, parentSid)
      _ <- resources.subAgentTaskStore.recordTask(
        SubAgentTask(
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
        )
      )
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
      _ <- resources.sessionStore.durableSwapMessages(
        parentSid,
        List(Message(MessageRole.User, Left("parent context")))
      )
      _ <- IO.blocking(DelegateRegistry.updateReport(childSid, "finish", "work complete", ""))
      _ <- supervisor ! AgentEvent.Completed(childSid, List(Message(MessageRole.Assistant, Left("work actually done"))))
      _ <- waitUntil(15.seconds)(
        resources.subAgentTaskStore
          .loadTasks(parentSid)
          .map(_.exists(t => t.taskId == childSid && t.status == "completed"))
      )
      frames <- wsFrames.get
      tasks <- resources.subAgentTaskStore.loadTasks(parentSid)
      registryAfter <- resources.agentRegistry.get
      _ <- DelegateBudget.release(childSid)
      _ <- system.stopAll
    yield
      val undelivered =
        frames.filter(json => json.hcursor.get[String]("type").toOption.contains("delegateResultUndelivered"))
      assertEquals(undelivered, Nil, "durable delegate delivery does not use the retired push backstop")
      val entries = SessionMailbox.scanPendingSync(parentSid)
      assertEquals(entries.count(_.event.eventType == "completed"), 1)
      assert(
        tasks.find(_.taskId == childSid).exists(_.status == "completed"),
        "the task store terminal write still lands (panel-visible state)"
      )
      assert(!registryAfter.contains(childSid), "the registry row is still cleaned up")
    end for

  // ── (5) 正向对照：parentRef=Some ⇒ 父收到 ExternalEvent，无兜底帧 ──────────

  test("(5) parentRef=Some: durable completion is not duplicated as a raw actor event"):
    val system = ActorSystem("mail-cont-posctl")
    val parentSid = "posctl-parent"
    val childSid = "delegate-nebflow-posctl1"
    for
      resources <- mkResources(system, hang = false)
      parentEvents <- Ref.of[IO, List[AgentCommand]](Nil)
      parentRef <- system.spawn(mkRecordingCmd(parentEvents), "posctl-parent-rec")
      _ <- seedDeadDelegate(resources, childSid, parentSid)
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
      _ <- resources.sessionStore.durableSwapMessages(
        parentSid,
        List(Message(MessageRole.User, Left("parent context")))
      )
      _ <- IO.blocking(DelegateRegistry.updateReport(childSid, "finish", "work complete", ""))
      _ <- supervisor ! AgentEvent.Completed(childSid, List(Message(MessageRole.Assistant, Left("done"))))
      _ <- waitUntil(15.seconds)(SessionMailbox.scanPending(parentSid).map(_.exists(_.event.eventType == "completed")))
      events <- parentEvents.get
      frames <- wsFrames.get
      _ <- DelegateBudget.release(childSid)
      _ <- system.stopAll
    yield
      val ext = events.collect { case e: AgentCommand.ExternalEvent => e }
      assertEquals(ext.size, 0, "the actor reference receives no duplicate raw event")
      assertEquals(SessionMailbox.scanPendingSync(parentSid).count(_.event.eventType == "completed"), 1)
      val undelivered =
        frames.filter(json => json.hcursor.get[String]("type").toOption.contains("delegateResultUndelivered"))
      assertEquals(undelivered, Nil, "the backstop must NOT fire when a parent ref exists")

    end for

  test(
    "completed workspace round hands the final text over once, releases the owner actor, and a later Mail round resumes the same session in place"
  ):
    val system = ActorSystem("workspace-round-retention")
    val id = "delegate-workspace-round"
    val parent = "round-parent"
    val seat = tempRoot / "round-seat"
    def git(args: String*) =
      os.proc(Seq("git") ++ args).call(cwd = seat, stdout = os.Pipe, stderr = os.Pipe).out.text().trim
    for
      resources <- mkResources(system, hang = true)
      _ <- IO.blocking {
        os.makeDir.all(seat)
        git("init", "-b", "main"); git("config", "user.name", "test");
        git("config", "user.email", "test@example.invalid")
        git("commit", "--allow-empty", "-m", "base"); git("checkout", "-b", "delegate/round")
        os.write(seat / "result.txt", "retained work")
        git("add", "result.txt"); git("commit", "-m", "work complete without merge")
      }
      baseline <- IO(git("rev-parse", "main"))
      _ <- resources.sessionStore.durableSwapMessages(parent, List(Message(MessageRole.User, Left("task"))))
      _ <- seedDeadDelegate(resources, id, parent)
      _ <- IO.blocking {
        val record =
          DelegateRegistry.find(id).get.copy(cwd = seat.toString, worktree = Some(seat.toString), mode = Some("kernel"))
        DelegateRegistry.put(record)
      }
      child <- system.spawn(sinkBehavior, "round-child")
      supervisor <- system.spawn(
        nebflow.agent.BackoffSupervisor(
          childRef = child,
          childSpawnFn = (_, _) => IO.raiseError(new Exception("must not respawn")),
          childName = id,
          parentRef = None,
          description = "round work",
          agentName = "nebflow",
          subagentId = id,
          parentSessionId = parent,
          resources = resources,
          initialPrompt = "task",
          source = "delegate"
        ),
        "round-supervisor"
      )
      _ <- nebflow.core.node.NodeRunner.registerAgent(
        resources,
        id,
        child,
        AgentKind.Delegate,
        parent,
        supervisorRef = Some(supervisor)
      )
      transcript = List(Message(MessageRole.Assistant, Left("round one complete")))
      _ <- supervisor ! AgentEvent.Completed(id, transcript)
      // The round's end is the completion delivery: the final text reaches the parent mailbox
      // exactly once and the owner actor is released.
      _ <- waitUntil(5.seconds)(SessionMailbox.scanPending(parent).map(_.size == 1))
      _ <- waitUntil(5.seconds)(system.isAlive(supervisor.path).map(!_))
      pending <- SessionMailbox.scanPending(parent)
      registryAfter <- resources.agentRegistry.get
      _ = assertEquals(pending.size, 1)
      _ = assert(pending.head.event.payload.contains("round one complete"), pending.toString)
      _ = assert(!registryAfter.contains(id), "the completion releases the owner from the registry")
      // Completion never integrates by itself: the base branch stays put for the next round.
      _ = assertEquals(git("rev-parse", "main"), baseline)
      mail <- continuationMail(resources, system, parent, id)
      _ = assert(mail.isRight, mail.toString)
      _ <- waitUntil(15.seconds)(resources.agentRegistry.get.map(_.contains(id)))
      resumed <- resources.agentRegistry.get.map(_.get(id))
      _ = assertEquals(resumed.map(_.kind), Some(AgentKind.Delegate))
      _ <- DelegateBudget.release(id).attempt.void
      _ <- system.stopAll
    yield ()
    end for

end MailDelegateContinuationDeliverySpec
