package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite
import nebflow.core.{FileChangeTracker, RateLimiter, SessionStore}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.{DelegateRegistry, MergeQueue}
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{Message, MessageRole, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 重启/崩溃**手动恢复**面 spec（重启手动恢复批，作者令 2026-11-06 20:36 逐字
 * 「由于重启/软件崩溃导致的错误，不用向上通知，不自动恢复，保存状态后，由用户
 * 手动恢复，粒度可以是逐个点击，也可以是子agent面板一键全部恢复，把状态保存做好
 * 就行」）。
 *
 * 本 spec 钉住三件事，逐条对应用户令的判据（实现面见
 * [[SubAgentStartupRecovery.sweep]] 的 manual 腿、[[ManualRevival]]、
 * [[nebflow.gateway.ManualRevivalHost]]、`Defaults.RestartRecoveryManual`）：
 *
 *   - **保存状态不判死**（MR1/MR2/MR7）：manual = true 的 boot sweep 把在飞孤儿与
 *     无队列成员的 ready-to-merge 僵尸标 `pending-recovery`——**不复活、不收殓、
 *     零向上通知**，现场四件（任务行/registry 记录/转录/座位）原样在盘；重复 boot
 *     幂等（第二次 sweep 不重复处理、不落终态）。
 *   - **用户手动恢复**（MR3/MR4）：一键全部 / 逐个两条入口都能把 `pending-recovery`
 *     行续回 running（同 id 续原转录，不是重派）；非可恢复行与不存在的 id 各给
 *     可直显面板的 Left 文案。
 *   - **可恢复资格面**（MR5）：pending-recovery / frozen / 重启指纹 failed 行可恢复，
 *     自身失败的 failed 行与 completed 行**不在**恢复视野（诚实终态不是重启致损）。
 *
 * 负向用例的 Deps 构造：`restoreOne` 在「行不可恢复」分支前不触 deps（源序
 * find → isRestorableRow 闸先返回），故 `Deps(null, null, None)` 是诚实缺省；正向
 * 用例一律用完整 fx。
 */
class ManualRevivalSpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit = PathUtil.setDataRoot(originalRoot)

  /** 应答固定文本的 stub LLM（续跑完成链的最小回声面，同 DelegateRevivalSpec）。 */
  private val echoLlm = new nebflow.shared.LlmHandle[IO]:
    def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: nebflow.shared.LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ) =
      fs2.Stream(
        StreamChunk.TextDelta("MANUALLY_REVIVED_DONE"),
        StreamChunk.Done(None, None)
      )

  private def mkTask(
    id: String,
    parent: String,
    status: String = "running",
    lastError: Option[String] = None
  ): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "do the thing",
      description = s"manual-revival-$id",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = lastError,
      source = "delegate"
    )

  private def mkRecord(id: String, parent: String, cwd: String) =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = cwd,
      project = None,
      taskId = None,
      parentSessionId = parent,
      title = s"manual-revival record $id",
      createdAt = System.currentTimeMillis() - 120_000L
    )

  private final case class Fx(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    resources: SharedResources,
    deps: DelegateRevival.Deps,
    system: nebflow.actor.ActorSystem,
    tmp: os.Path,
    seat: os.Path
  )

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = os.temp.dir(prefix = s"manual-revival-$name")
    val seat = os.temp.dir(prefix = s"manual-revival-seat-$name")
    PathUtil.setDataRoot(tmp / "data")
    MergeQueue.reset()
    nebflow.gateway.ManualRevivalHost.reset()
    val system = nebflow.actor.ActorSystem(s"manual-revival-$name-${java.util.UUID.randomUUID().toString.take(6)}")
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
        deps = DelegateRevival.Deps(resources, system, None)
        _ <- body(Fx(store, sessionStore, resources, deps, system, tmp, seat))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      nebflow.gateway.ManualRevivalHost.reset()
      MergeQueue.reset()
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
      os.remove.all(seat)

  end withFx

  /** 手动模式 boot sweep（生产装配形态：缺省 manual = true）。 */
  private def manualSweep(fx: Fx) =
    SubAgentStartupRecovery.sweep(
      fx.store,
      fx.sessionStore,
      Some(SubAgentStartupRecovery.RevivalDeps(fx.resources, fx.system, None)),
      manual = true
    )

  /** 邮箱读面（delegate 轨唯一投递面）：零通知 ⇒ pending ∪ taken 里没有该孩子的条目。 */
  private def mailboxEvents(fx: Fx, parentId: String): IO[List[nebflow.actor.AgentCommand.ExternalEvent]] =
    for
      pending <- SessionMailbox.scanPending(parentId)
      taken <- SessionMailbox.scanTaken(parentId)
    yield (pending ++ taken).map(_.event)

  private def waitUntil(timeout: FiniteDuration)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then IO.raiseError(new AssertionError("waitUntil: not met in time"))
          else IO.sleep(100.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  test(
    "MR1: manual boot sweep saves state — in-flight orphan + queue-less ready-to-merge zombie → pending-recovery, zero revive, zero terminalize, zero upward notify"
  ) {
    withFx("mr1") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        running = "delegate-nebflow-mr1-run"
        zombie = "delegate-nebflow-mr1-rtm"
        _ <- fx.store.recordTask(mkTask(running, parent.id, "running"))
        _ <- fx.store.recordTask(mkTask(zombie, parent.id, "ready-to-merge"))
        _ <- IO(DelegateRegistry.put(mkRecord(running, parent.id, fx.seat.toString)))
        _ <- IO(DelegateRegistry.put(mkRecord(zombie, parent.id, fx.seat.toString)))
        // 崩溃前转录现场（保存状态 ≠ 丢证据）。
        _ <- fx.sessionStore.saveMessagesForSession(
          running,
          List(Message(MessageRole.User, Left("original brief")), Message(MessageRole.Assistant, Left("partial work")))
        )
        report <- manualSweep(fx)
        rows <- fx.store.findAllTasks().map(_.filter(_.taskId.startsWith("delegate-nebflow-mr1")))
        events <- mailboxEvents(fx, parent.id)
      yield
        assertEquals(report.terminalized, Nil, s"manual mode never terminalizes: ${report.summary}")
        assertEquals(report.revived, Nil, s"manual mode never auto-revives: ${report.summary}")
        assertEquals(report.pended.sorted, List(running, zombie).sorted, s"both rows must be pended: ${report.summary}")
        rows.foreach { r =>
          assertEquals(
            r.status,
            SubAgentStartupRecovery.PendingRecoveryStatus,
            s"row ${r.taskId} must be saved as pending-recovery"
          )
          assertEquals(r.completedAt, None, s"row ${r.taskId} must not be terminalized (no completedAt)")
          assert(
            !r.lastError.exists(_.startsWith(SubAgentStartupRecovery.RestartLossFingerprint)),
            s"row ${r.taskId} must not carry the terminal restart-loss fingerprint: ${r.lastError}"
          )
        }
        assertEquals(events.filter(e => e.correlationId.exists(c => c.contains("mr1"))), Nil, "zero upward notify")
        // 现场四件仍在盘上：registry 记录（未判死、未落重启打断旗）。
        assert(DelegateRegistry.find(running).isDefined, "registry record survives")
        assert(DelegateRegistry.find(running).get.restartInterruptedAt.isEmpty, "pending row is not judged dead")
    }
  }

  test(
    "MR2: a second boot does not disturb pending rows (idempotent save; never auto-terminalizes behind the user's back)"
  ) {
    withFx("mr2") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        id = "delegate-nebflow-mr2"
        _ <- fx.store.recordTask(mkTask(id, parent.id, "running"))
        first <- manualSweep(fx)
        second <- manualSweep(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == id).get)
        events <- mailboxEvents(fx, parent.id)
      yield
        assertEquals(first.pended, List(id), s"first boot pends: ${first.summary}")
        assertEquals(second.terminalized, Nil, "second boot must not terminalize a pending row")
        assertEquals(second.revived, Nil, "second boot must not auto-revive")
        assertEquals(
          row.status,
          SubAgentStartupRecovery.PendingRecoveryStatus,
          "the saved state is stable across boots"
        )
        assertEquals(row.completedAt, None, "still not terminal after the second boot")
        assertEquals(events, Nil, "still zero upward notify")
    }
  }

  test(
    "MR3: restoreAll (panel 一键全部恢复) revives every eligible pending row in place — same id, resumed transcript, row back to running"
  ) {
    withFx("mr3") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        a = "delegate-nebflow-mr3-a"
        b = "delegate-nebflow-mr3-b"
        _ <- fx.store.recordTask(mkTask(a, parent.id, "running"))
        _ <- fx.store.recordTask(mkTask(b, parent.id, "running"))
        _ <- IO(DelegateRegistry.put(mkRecord(a, parent.id, fx.seat.toString)))
        _ <- IO(DelegateRegistry.put(mkRecord(b, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(
          a,
          List(Message(MessageRole.User, Left("brief a")), Message(MessageRole.Assistant, Left("partial a")))
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          b,
          List(Message(MessageRole.User, Left("brief b")), Message(MessageRole.Assistant, Left("partial b")))
        )
        pended <- manualSweep(fx)
        _ = assertEquals(pended.pended.sorted, List(a, b).sorted, s"precondition: both pended (${pended.summary})")
        restorable <- ManualRevival.restorableRows(fx.store)
        _ = assertEquals(restorable.map(_.taskId).sorted, List(a, b).sorted, "the panel's scan face sees both rows")
        restored <- ManualRevival.restoreAll(fx.deps, fx.store)
        (revived, skipped) = restored
        rows <- fx.store.findAllTasks().map(_.filter(_.taskId.startsWith("delegate-nebflow-mr3")))
        transcripts <- fx.sessionStore.loadMessagesForSession(a)
      yield
        assertEquals(skipped, Nil, s"both rows are eligible: $skipped")
        assertEquals(revived.sorted, List(a, b).sorted, "panel one-click restores every eligible row")
        rows.foreach(r => assertEquals(r.status, "running", s"${r.taskId} must be running again after restore"))
        assert(
          transcripts.exists(_.textContent.contains("partial a")),
          "restore continues the ORIGINAL transcript (not a re-dispatch)"
        )
    }
  }

  test("manual recovery refuses missing history and unknown tool outcomes before starting or changing the task row") {
    withFx("unverified-context") { fx =>
      val id = "delegate-unverified-context"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(id, parent.id, "pending-recovery"))
        _ <- IO(DelegateRegistry.put(mkRecord(id, parent.id, fx.seat.toString)))
        missing <- ManualRevival.restoreOne(fx.deps, fx.store, id)
        _ = assert(missing.left.toOption.exists(_.contains("RECOVERY_UNVERIFIED")))
        unknown = List(
          Message(MessageRole.User, Left("saved task")),
          Message(
            MessageRole.Assistant,
            Right(List(nebflow.shared.ContentBlock.ToolUse("unknown-write", "Bash", io.circe.JsonObject.empty)))
          )
        )
        _ <- fx.sessionStore.durableSwapMessages(id, unknown)
        result <- ManualRevival.restoreOne(fx.deps, fx.store, id)
        _ = assert(result.left.toOption.exists(_.contains("RECOVERY_UNVERIFIED")))
        row <- fx.store.findByTaskId(id)
        _ = assertEquals(row.map(_.status), Some("pending-recovery"))
        _ = assertEquals(row.flatMap(_.revivalCount), None)
        live <- fx.resources.agentRegistry.get
        _ = assert(!live.contains(id))
        saved <- fx.sessionStore.loadMessagesForSession(id)
        _ = assertEquals(saved, unknown)
      yield ()
      end for
    }
  }

  test(
    "MR4: restoreOne — unknown id and non-restorable rows are refused with panel-readable reasons (no spawn attempted)"
  ) {
    // 这些分支在 deps 被解引用之前返回 ⇒ null Deps 是诚实缺省（源序：find → 状态闸）。
    val noDeps = DelegateRevival.Deps(null, null, None)
    val tmp = os.temp.dir(prefix = "manual-revival-mr4")
    PathUtil.setDataRoot(tmp / "data")
    val store = new SubAgentTaskStore(tmp / "subagent-tasks")
    try
      val io = for
        missing <- ManualRevival.restoreOne(noDeps, store, "delegate-nope")
        _ <- store.recordTask(mkTask("delegate-done", "parent-mr4", "completed"))
        done <- ManualRevival.restoreOne(noDeps, store, "delegate-done")
        _ <- store.recordTask(mkTask("delegate-running", "parent-mr4", "running"))
        running <- ManualRevival.restoreOne(noDeps, store, "delegate-running")
        all <- ManualRevival.restoreAll(noDeps, store)
      yield (missing, done, running, all)
      val (missing, done, running, all) = io.unsafeRunSync()
      assert(missing.isLeft && missing.left.exists(_.contains("no task row")), s"unknown id: $missing")
      assert(
        done.isLeft && done.left.exists(_.contains("not restorable")),
        s"a completed row is an honest terminal state, not restart damage: $done"
      )
      assert(
        running.isLeft && running.left.exists(_.contains("not restorable")),
        s"a running row is not in the recovery view: $running"
      )
      // restoreAll 的空集快路（无 deps 解引用）：没有可恢复行 ⇒ (Nil, Nil)，绝不抛。
      assertEquals(all, (Nil, Nil), "empty recovery view is a no-op")
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
    end try
  }

  test(
    "MR5: recovery view = pending-recovery / frozen / restart-fingerprint failed rows; honest failed and completed rows stay out"
  ) {
    val fp = SubAgentStartupRecovery.RestartLossFingerprint
    assert(
      ManualRevival.isRestorableRow(mkTask("d1", "p", "pending-recovery")),
      "manual-mode saved state is restorable"
    )
    assert(ManualRevival.isRestorableRow(mkTask("d2", "p", "frozen")), "model-invalid frozen row is restorable")
    assert(
      ManualRevival.isRestorableRow(mkTask("d3", "p", "failed", Some(s"$fp — result lost"))),
      "a restart-lost failed row (legacy auto mode) is restorable"
    )
    assert(
      !ManualRevival.isRestorableRow(mkTask("d4", "p", "failed", Some("Error: compile failed"))),
      "a genuine self-failure is an honest terminal state — not restart damage"
    )
    assert(!ManualRevival.isRestorableRow(mkTask("d5", "p", "completed")), "completed rows are done")
    assert(!ManualRevival.isRestorableRow(mkTask("d6", "p", "running")), "running rows are not in the recovery view")
    assert(!ManualRevival.isRestorableRow(mkTask("d7", "p", "pending")), "a queued row was never started")
  }

  test(
    "MR6: ManualRevivalHost is a one-slot assembly holder — install/current/reset, absent deps is a readable None (never an NPE)"
  ) {
    nebflow.gateway.ManualRevivalHost.reset()
    assertEquals(nebflow.gateway.ManualRevivalHost.current, None, "before boot assembly the holder is empty")
    val deps = DelegateRevival.Deps(null, null, None)
    nebflow.gateway.ManualRevivalHost.install(deps)
    assertEquals(nebflow.gateway.ManualRevivalHost.current.isDefined, true, "boot installs the deps once")
    nebflow.gateway.ManualRevivalHost.reset()
    assertEquals(nebflow.gateway.ManualRevivalHost.current, None, "test reset clears it")
  }

  test("MR7: Defaults.RestartRecoveryManual defaults to manual (true); the system prop is the single rollback switch") {
    val prop = "nebflow.startup.recovery.manual"
    def withProp(value: Option[String])(body: => Unit): Unit =
      val before = Option(System.getProperty(prop))
      try
        value match
          case Some(v) => System.setProperty(prop, v)
          case None => System.clearProperty(prop)
        body
      finally
        before match
          case Some(v) => System.setProperty(prop, v)
          case None => System.clearProperty(prop)
    withProp(None)(
      assertEquals(nebflow.shared.Defaults.RestartRecoveryManual, true, "production default = manual recovery")
    )
    withProp(Some("false"))(
      assertEquals(nebflow.shared.Defaults.RestartRecoveryManual, false, "rollback switch off = legacy auto revival")
    )
    withProp(Some("true"))(assertEquals(nebflow.shared.Defaults.RestartRecoveryManual, true))
  }

end ManualRevivalSpec
