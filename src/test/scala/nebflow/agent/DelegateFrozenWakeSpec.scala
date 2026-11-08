package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import fs2.Stream
import munit.CatsEffectSuite
import nebflow.actor.{AgentCommand, AgentKind}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.{ChainStore, DelegateFreeze, DelegateRegistry}
import nebflow.core.flow.MailIdleGate
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * 错误冻结批（2026-11-01 · 作者令「冻结态 方便被唤醒 接着做」「别丢上下文」）——
 * **唤醒腿**判红锚（同一会话续跑，绝不新建会话）。
 *
 * 钉死：
 *  W1 `MailIdleGate` 把 `Frozen` 视同可投递（冻结会话零在飞工作 ⇒ 投给它的 Mail
 *     正是唤醒载体；把 Frozen 当忙 = 冻结即失联）；
 *  W2 冻结回执**不是**子代理结果（`TurnBoundaryDrains.isSubagentResult`）——否则
 *     父会话的屏障槽被提前归还，批次语义错位；
 *  W3 `DelegateRevival.wakeFrozen` 只接**本批冻结线值**的记录（非冻结 / 已归档 /
 *     外部执行器一律显式拒绝，绝不静默起新会话）；
 *  W4 唤醒 = **同 id** 复活：registry 活体 session id 不变、冻结槽清空、台账行回
 *     running、转录（冻结前的现场）仍在且首条注入带唤醒横幅（「已完成的不重做」）。
 */
class DelegateFrozenWakeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val originalRoot = PathUtil.dataRoot
  private var base: os.Path = os.pwd / "target" / "test-frozen-wake"

  override def beforeAll(): Unit =
    base = os.temp.dir(prefix = "nb-frozen-wake-")
    PathUtil.setDataRoot(base / "data")
    nebflow.core.LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    nebflow.core.LlmLogWriter.setEnabled(true)
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(base)
    catch case _: Throwable => ()

  private val echoLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ) = Stream(StreamChunk.TextDelta("WOKE_AND_CONTINUED"), StreamChunk.Done(None, None))

  private final case class Fx(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    resources: SharedResources,
    system: nebflow.actor.ActorSystem,
    seat: os.Path,
    tmp: os.Path
  )

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = base / name
    val seat = os.temp.dir(prefix = s"wake-seat-$name")
    val system = nebflow.actor.ActorSystem(s"wake-$name-${java.util.UUID.randomUUID().toString.take(6)}")
    val io =
      for
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
          llm = echoLlm,
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
        _ <- body(Fx(store, sessionStore, resources, system, seat, tmp))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(seat)

  end withFx

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

  private def mkTask(id: String, parent: String): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "wire up the image pipeline",
      description = s"frozen-wake $id",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def mkRecord(id: String, parent: String, seat: String, executor: String = "nebflow") =
    DelegateRegistry.Record(
      id = id,
      executor = executor,
      cwd = seat,
      project = None,
      taskId = None,
      parentSessionId = parent,
      title = s"frozen wake record $id",
      createdAt = System.currentTimeMillis() - 120_000L
    )

  // ── W1 冻结会话可投递 ──────────────────────────────────────────────────

  test(
    "MailIdleGate: a Frozen session is deliverable (context may be retained, but Mail does not release the manual recovery gate)"
  ) {
    val sid = "delegate-frozen-gate"
    val ref = null.asInstanceOf[nebflow.actor.ActorRef[AgentCommand]]
    def rec(status: nebflow.actor.AgentStatus, outstanding: Int) =
      nebflow.actor.AgentRecord(
        sessionId = sid,
        ref = ref,
        kind = AgentKind.Delegate,
        rootSessionId = "root",
        status = status,
        outstandingSubagents = outstanding
      )
    assert(
      MailIdleGate.isAgentTreeIdle(sid, Map(sid -> rec(nebflow.actor.AgentStatus.Frozen, 0))),
      "Frozen must be deliverable (else a frozen delegate can never be woken by mail)"
    )
    assert(
      MailIdleGate.isAgentTreeIdle(sid, Map(sid -> rec(nebflow.actor.AgentStatus.Frozen, 3))),
      "a stale in-flight counter must not lock a frozen session out of delivery"
    )
    assert(
      !MailIdleGate.isAgentTreeIdle(sid, Map(sid -> rec(nebflow.actor.AgentStatus.Processing, 0))),
      "a processing session stays busy (unchanged)"
    )
    assert(
      !MailIdleGate.isAgentTreeIdle(sid, Map(sid -> rec(nebflow.actor.AgentStatus.Idle, 2))),
      "an idle session with in-flight sub-agents stays gated (unchanged)"
    )
  }

  // ── W2 冻结回执不算子代理结果 ──────────────────────────────────────────

  test(
    "TurnBoundaryDrains: a frozen receipt is NOT a sub-agent result (the parent's barrier slot must not be returned early)"
  ) {
    val frozen = AgentCommand.ExternalEvent("delegate", "frozen", "receipt", io.circe.JsonObject.empty, Some("x"))
    val completed = AgentCommand.ExternalEvent("delegate", "completed", "done", io.circe.JsonObject.empty, Some("y"))
    val failed = AgentCommand.ExternalEvent("delegate", "failed", "boom", io.circe.JsonObject.empty, Some("z"))
    assert(TurnBoundaryDrains.isFreezeReceipt(frozen))
    assert(!TurnBoundaryDrains.isSubagentResult(frozen), "a freeze is not a result — it may freeze again later")
    assert(TurnBoundaryDrains.isSubagentResult(completed))
    assert(TurnBoundaryDrains.isSubagentResult(failed))
  }

  test(
    "visibleExternalEventSource: a frozen receipt renders on its own `delegate-frozen` source (not as a completion report)"
  ) {
    assertEquals(AgentActor.visibleExternalEventSource("delegate", "frozen"), Some("delegate-frozen"))
    assertEquals(AgentActor.visibleExternalEventSource("delegate", "completed"), Some("delegate"))
  }

  // ── W3/W4 唤醒腿 ───────────────────────────────────────────────────────

  test(
    "wakeFrozen refuses records that are not in the delegate-error frozen state (never starts a fresh session silently)"
  ) {
    withFx("refuse") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        id = "delegate-frozen-refuse"
        _ <- fx.store.recordTask(mkTask(id, parent.id))
        _ <- IO(DelegateRegistry.put(mkRecord(id, parent.id, fx.seat.toString)))
        notFrozen <- DelegateRevival.wakeFrozen(
          DelegateRevival.Deps(fx.resources, fx.system, None),
          id,
          "please continue"
        )
        _ = assert(notFrozen.isLeft, "a non-frozen record is refused")
        _ = assert(notFrozen.left.exists(_.contains("not in the delegate-error frozen state")), s"got: $notFrozen")
        // 冻结但不 live ⇒ 走同 id 复活（本用例只判拒绝面，故用外部执行器反例）。
        _ <- IO(
          DelegateRegistry.parkFrozen(id, DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure), "provider 500")
        )
        _ <- IO(
          DelegateRegistry.put(
            mkRecord(id, parent.id, fx.seat.toString, executor = "claude-cli").copy(
              frozenSince = Some(System.currentTimeMillis()),
              frozenReason = Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)),
              frozenDetail = Some("provider 500")
            )
          )
        )
        external <- DelegateRevival.wakeFrozen(DelegateRevival.Deps(fx.resources, fx.system, None), id, "go")
        _ = assert(external.isLeft, "an external-executor record is refused (its continuation leg owns the resume)")
        missing <- DelegateRevival.wakeFrozen(
          DelegateRevival.Deps(fx.resources, fx.system, None),
          "delegate-frozen-nope",
          "go"
        )
        _ = assert(missing.isLeft, "an unknown id is refused")
        live <- fx.resources.agentRegistry.get.map(_.contains(id))
        _ = assert(!live, "no actor may be spawned by a refused wake")
      yield ()
    }
  }

  test(
    "wakeFrozen resumes the SAME session id from the persisted transcript: history in place, wake banner injected (finished work is not redone), frozen slot cleared, task row back to running"
  ) {
    withFx("same-id") { fx =>
      val parentId = "frozen-wake-parent"
      val childId = "delegate-frozen-wake-1"
      for
        _ <- fx.sessionStore.getOrCreateSession(parentId, "freeze spec parent", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(childId, parentId))
        // 冻结前的现场：转录在盘（崩溃/故障前每轮持久化）。
        _ <- fx.sessionStore.saveMessagesForSession(
          childId,
          List(
            Message(MessageRole.User, Left("original brief: wire up the image pipeline")),
            Message(MessageRole.Assistant, Left("halfway: the provider adapter is done, the retry path still open"))
          )
        )
        _ <- IO(
          DelegateRegistry.put(
            mkRecord(childId, parentId, fx.seat.toString).copy(
              frozenSince = Some(System.currentTimeMillis() - 3600_000L),
              frozenReason = Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)),
              frozenDetail = Some("provider anthropic/claude-x: 529 overloaded")
            )
          )
        )
        _ <- fx.store.updateStatus(
          parentId,
          childId,
          DelegateFreeze.TaskStatus,
          lastError = Some("[frozen:llm-failure] 529")
        )
        result <- DelegateRevival.wakeFrozen(DelegateRevival.Deps(fx.resources, fx.system, None), childId, "继续把重试路径补完")
        _ = assert(result.isRight, s"the frozen record must wake: $result")
        // 同 id 活体（不新建会话 = 唤醒的同义反复）。
        _ <- waitUntil(20.seconds)(fx.resources.agentRegistry.get.map(_.contains(childId)))
        live <- fx.resources.agentRegistry.get.map(_.get(childId))
        _ = assertEquals(live.map(_.kind), Some(AgentKind.Delegate))
        _ = assert(
          live.exists(_.supervisorRef.isDefined),
          "the supervision chain is rebuilt (wake must be watched again)"
        )
        // 冻结槽清空（唤醒 = 冻结生命周期的唯一出口）。
        rec <- IO(DelegateRegistry.find(childId).get)
        _ = assertEquals(rec.frozenSince, None, "frozenSince cleared after the wake")
        _ = assertEquals(rec.frozenReason, None, "frozenReason cleared after the wake")
        // 台账行回 running（唤醒 = 新执行窗口，行必须诚实地在飞）。
        row <- fx.store.findByTaskId(childId)
        _ = assertEquals(row.map(_.status), Some("running"))
        // 历史在现场 + 唤醒横幅 + 唤醒者指令（同 id 续跑，不重头再来）。
        _ <- waitUntil(20.seconds) {
          fx.sessionStore.loadMessagesForSession(childId).map { transcript =>
            val texts = transcript.map(_.textContent)
            transcript.size >= 3 &&
            texts.exists(_.contains("original brief: wire up the image pipeline")) &&
            texts.exists(t => t.contains("frozen-wake") && t.contains("do NOT redo")) &&
            texts.exists(_.contains("继续把重试路径补完"))
          }
        }
        // 审计行（链视图可见「冻结 → 唤醒」）。
        _ = assert(
          ChainStore
            .listSync()
            .flatMap(_.entries)
            .exists(e => e.kind == "delegate-frozen-revived" || e.kind == "delegate-frozen-wake"),
          s"a chain audit row records the frozen wake, got: ${ChainStore.listSync().flatMap(_.entries).map(_.kind).distinct}"
        )
      yield ()
      end for
    }
  }
end DelegateFrozenWakeSpec
