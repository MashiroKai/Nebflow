package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentCommand, AgentKind}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{Message, MessageRole, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 监督链自动恢复批（2026-10-22 作者令）：delegate boot 复活腿（DelegateRevival）spec。
 * 设计 = docs/design/delegate-supervision-revival-design.zh-CN.md。
 *
 * 用例：
 *  - V1 冒烟：合格孤儿（delegate 轨 + registry 记录 + 座位在 + 父会话在 + 预算内）
 *    → sweep 复活：行翻 running/revivalCount=1、registry 活体回写、nudge 注入
 *    转录、stub LLM 续跑完成 → 父经**持久邮箱**（SessionMailbox——P2 邮箱化批
 *    起 delegate 轨唯一投递面；收件方盘上真源）收到 **completed** 结果。
 *  - V2 预算耗尽：revivalCount 达上限 → 不复活，落收殓链（failed + 父邮箱通知）。
 *  - V3 座位缺失：record.cwd 不存在 → 不复活（revivalSkipped 记录原因）+ 收殓。
 *  - V4 kill-switch off：全部落收殓链，零复活。
 *  - V5 链式复活-预算：复活 → 行 revivalCount=1；模拟再次崩溃（行 running 无活体）
 *    → 二次 boot 再复活（revivalCount=2）；第三次 boot 预算耗尽 → 收殓。
 *  - V6 非 delegate 源（subtask）：不复活、收殓（既有语义零变化）。
 *  - V7 外部执行器：不复活、收殓（续聊是运行期 Mail 腿的事）。
 */
class DelegateRevivalSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val originalRoot = PathUtil.dataRoot

  private def resetGlobals(): Unit =
    sys.props.remove("nebflow.delegate.revival.enabled")

  override def beforeEach(context: munit.BeforeEach): Unit = resetGlobals()
  override def afterEach(context: munit.AfterEach): Unit = resetGlobals()
  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  /** 应答固定文本的 stub LLM（V1 续跑完成链的最小回声面）。 */
  private val echoLlm = new nebflow.shared.LlmHandle[IO]:
    def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: nebflow.shared.LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ) =
      fs2.Stream(
        StreamChunk.TextDelta("REVIVED_WORK_DONE"),
        StreamChunk.Done(None, None)
      )

  private def mkTask(id: String, parent: String, status: String = "running", revivalCount: Option[Int] = None): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "do the thing",
      description = s"revival-$id",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = None,
      source = "delegate",
      revivalCount = revivalCount
    )

  private def mkRecord(id: String, parent: String, cwd: String, executor: String = "nebflow") =
    nebflow.core.delegate.DelegateRegistry.Record(
      id = id,
      executor = executor,
      cwd = cwd,
      project = None,
      taskId = None,
      parentSessionId = parent,
      title = s"revival record $id",
      createdAt = System.currentTimeMillis() - 120_000L
    )

  private final case class Fx(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    resources: SharedResources,
    system: nebflow.actor.ActorSystem,
    tmp: os.Path,
    seat: os.Path
  )

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = os.temp.dir(prefix = s"revival-$name")
    val seat = os.temp.dir(prefix = s"revival-seat-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = nebflow.actor.ActorSystem(s"revival-$name-${java.util.UUID.randomUUID().toString.take(6)}")
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
        _ <- body(Fx(store, sessionStore, resources, system, tmp, seat))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
      os.remove.all(seat)

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

  private def sweepWith(fx: Fx) =
    SubAgentStartupRecovery.sweep(
      fx.store,
      fx.sessionStore,
      Some(SubAgentStartupRecovery.RevivalDeps(fx.resources, fx.system, None))
    )

  /** 邮箱读面（P2 邮箱化批起 delegate 轨唯一投递面）：pending ∪ taken 的事件流。 */
  private def mailboxEvents(fx: Fx, parentId: String): IO[List[AgentCommand.ExternalEvent]] =
    for
      pending <- SessionMailbox.scanPending(parentId)
      taken <- SessionMailbox.scanTaken(parentId)
    yield (pending ++ taken).map(_.event)

  test("V1: eligible orphan revives from transcript checkpoint; the continued turn WITHOUT a declaration is REMINDED (matrix complete×idle), never auto-completed to the parent") {
    withFx("v1") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v1"
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        // 崩溃前转录（checkpoint 现场）。
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(
            Message(MessageRole.User, Left("original brief")),
            Message(MessageRole.Assistant, Left("work in progress partial"))
          )
        )
        report <- sweepWith(fx)
        // 复活面：行翻 running + 预算记账 + 旧错清空。
        _ = assertEquals(report.revived, List(child), s"revival report: ${report.summary}")
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row.status, "running")
        _ = assertEquals(row.revivalCount, Some(1))
        _ = assert(row.revivedAt.isDefined)
        // 活体面：registry 有 Delegate 级 actor + supervisor 接线。
        live <- fx.resources.agentRegistry.get.map(_.get(child))
        _ = assert(live.isDefined, "revived delegate must be live in the registry")
        _ = assertEquals(live.get.kind, AgentKind.Delegate)
        _ = assert(live.get.supervisorRef.isDefined)
        // nudge 已注入转录（续跑语义——checkpoint + 续跑指令，不是重派）；
        // 注入是复活 fork 内的异步写——waitUntil 等它落盘。
        _ <- waitUntil(10.seconds) {
          fx.sessionStore.loadMessagesForSession(child).map(transcript =>
            transcript.exists(m => m.role == MessageRole.User && m.textContent.contains("engine process restarted"))
          )
        }
        // 矩阵腿（状态矩阵批 2026-11-01）：续跑回合（stub 回声终文，无 delegate_report
        // 申报）收尾 ⇒ complete×idle = 提醒腿（NudgeIdle）——绝不自动 completed：
        // 零终态投父（邮箱零条目）、任务行诚实回 running、actor 不停（活体仍在，
        // 等下一回合真申报 finish/merge 或 standby）。先沉降回合（复活 fork 内的
        // spawn→回声→收尾链是异步的）。
        _ <- IO.sleep(800.millis)
        events <- mailboxEvents(fx, parent.id)
        _ = assertEquals(
          events.filter(_.correlationId.contains(child)),
          Nil,
          "no declaration ⇒ no auto terminal — the parent is NOT notified (matrix idle leg)"
        )
        row2 <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row2.status, "running", "the reminder leg keeps the row honestly in-flight (never completed)")
        stillLive <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assert(stillLive, "NudgeIdle does not stop the revived actor — it is reminded, not terminalized")
      yield ()
    }
  }

  test("V2: revival budget exhausted → salvage chain (failed + parent notified), zero spawn") {
    withFx("v2") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v2"
        _ <- fx.store.recordTask(mkTask(child, parent.id, revivalCount = Some(2)))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil)
        _ = assertEquals(report.terminalized, List(child))
        _ = assert(report.revivalSkipped.exists((id, _) => id == child), "skip reason recorded")
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row.status, "failed")
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assert(!live, "budget-exhausted orphan must not be spawned")
        events <- mailboxEvents(fx, parent.id)
        _ = assert(
          events.exists(e => e.correlationId.contains(child) && e.eventType == "failed"),
          "parent notified via the persistent mailbox (delegate-track sole delivery face)"
        )
      yield ()
    }
  }

  test("V3: missing seat directory → not revived (skip reason recorded), salvaged") {
    withFx("v3") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v3"
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, "/nonexistent/revival-seat")))
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil)
        _ = assertEquals(report.terminalized, List(child))
        _ = assert(
          report.revivalSkipped.exists((id, r) => id == child && r.contains("seat directory missing")),
          s"seat-missing skip reason recorded: ${report.revivalSkipped}"
        )
      yield ()
    }
  }

  test("V4: kill-switch off → all orphans salvaged, zero revival") {
    withFx("v4") { fx =>
      sys.props("nebflow.delegate.revival.enabled") = "false"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v4"
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil, "kill-switch off = legacy salvage-only behavior")
        _ = assertEquals(report.terminalized, List(child))
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row.status, "failed")
      yield ()
    }
  }

  test("V5: revival chain across successive crashes — revive, re-revive, then budget-exhausted salvage") {
    withFx("v5") { fx =>
      def boot(task: SubAgentTask): IO[Unit] =
        // 模拟崩溃现场：行在飞、无活体（本 Fx 从未 spawn 过该会话的 actor——
        // registerAgent 只在复活腿内发生；此处先清 registry 再现「进程死亡」）。
        fx.resources.agentRegistry.update(_ - task.taskId) *>
          sweepWith(fx).void *>
          // 回合沉降（矩阵批）：上一次复活所生的 actor 其回声回合是异步的——
          // 无申报收尾走 NudgeIdle 腿（行回 running）。不在 boot 间沉降会让
          // 上一回合的提醒腿与本 boot 的收殓腿竞写任务行。
          IO.sleep(800.millis)

      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v5"
        base = mkTask(child, parent.id)
        _ <- fx.store.recordTask(base)
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        // boot #2（崩溃 #1 之后）：第一次复活。
        _ <- boot(base)
        row1 <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row1.status, "running")
        _ = assertEquals(row1.revivalCount, Some(1))
        // boot #3（崩溃 #2 之后）：预算内第二次复活。
        _ <- boot(row1)
        row2 <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row2.status, "running")
        _ = assertEquals(row2.revivalCount, Some(2))
        // boot #4（崩溃 #3 之后）：预算耗尽 → 收殓 + 父通知。
        _ <- boot(row2)
        row3 <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row3.status, "failed")
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assert(!live)
      yield ()
    }
  }

  test("V6: subtask-source orphan is never revived (existing salvage semantics unchanged)") {
    withFx("v6") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "subtask-nebflow-v6"
        _ <- fx.store.recordTask(mkTask(child, parent.id).copy(source = "subtask"))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil)
        _ = assertEquals(report.terminalized, List(child))
        _ = assert(
          report.revivalSkipped.exists((id, r) => id == child && r.contains("subtask")),
          s"subtask skip reason recorded: ${report.revivalSkipped}"
        )
      yield ()
    }
  }

  test("V7: external-executor record is never revived (Mail continuation is the runtime leg)") {
    withFx("v7") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v7"
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString, executor = "zcode"))
        )
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil)
        _ = assertEquals(report.terminalized, List(child))
        _ = assert(
          report.revivalSkipped.exists((id, r) => id == child && r.contains("external executor")),
          s"external-executor skip reason recorded: ${report.revivalSkipped}"
        )
      yield ()
    }
  }

  test("V8: author's fake-interrupt scenario — salvaged row flagged interrupted folds once the ledger adjudicates completed") {
    withFx("v8") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v8"
        // 台账条目先行 complete（作者裁定「实际完成」——完成收口被重启杀死前已落地）。
        entry <- IO(
          nebflow.core.project.TaskLedgerStore
            .open()
            .createSyncReturningId("v8 adjudicated task")
            .getOrElse(fail("ledger create failed"))
        )
        ledger = nebflow.core.project.TaskLedgerStore.open()
        _ <- IO(ledger.completeSync(entry))
        // 在飞孤儿 + 记录（taskId 关联台账）+ 座位缺失 ⇒ 复活拒绝 → 收殓 → 打旗 →
        // 台账对账腿裁定 completed ⇒ 同 boot 内归档折叠（面板不再假中断）。
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(child, parent.id, "/nonexistent/revival-seat-v8").copy(taskId = Some(entry))
          )
        )
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil)
        _ = assertEquals(report.terminalized, List(child))
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row.status, "failed")
        rec <- IO(nebflow.core.delegate.DelegateRegistry.find(child))
        _ = assert(rec.flatMap(_.restartInterruptedAt).isDefined, "salvaged row carries the interruption flag")
        _ = assert(
          rec.flatMap(_.archivedAt).isDefined,
          "ledger-completed adjudication folds the flagged row in the SAME boot (author complaint #2 closed)"
        )
      yield ()
    }
  }

  test("V10: standby at engine restart (案A 批 2026-10-31 · 状态矩阵批收编) → sweep refuses revival (parked awaiting the author, not working), salvage settles failed — Mail continuation remains the wake path") {
    withFx("v10") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v10"
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        // 待命停靠形态：record.standbySince 在场（回合收尾采纳 standby 的落盘）。
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString).copy(standbySince = Some(System.currentTimeMillis()))
          )
        )
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil, "a parked standby delegate must NOT be revived into a working session")
        _ = assertEquals(report.terminalized, List(child))
        _ = assert(
          report.revivalSkipped.exists((id, r) => id == child && r.contains("standby at engine restart")),
          s"standby skip reason recorded: ${report.revivalSkipped}"
        )
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row.status, "failed", "salvage settles the honest terminal (never a completed fake, never left running)")
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assert(!live, "standby orphan must not be spawned")
      yield ()
    }
  }

  test("V9: cancel tombstone on record → sweep refuses revival, salvage settles failed (R1 三路径·boot sweep 腿)") {
    withFx("v9") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v9"
        // 形态 = 实证僵尸（e25cd0c2/b5646a58）：取消发生在引擎重启前、任务行仍
        // running——墓碑（DelegateRegistry.cancelRequestedAt）必须压过行状态。
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.markCancelRequested(child, "the user panel"))
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil, "tombstoned orphan must never revive through the sweep")
        _ = assertEquals(report.terminalized, List(child))
        _ = assert(
          report.revivalSkipped.exists((id, r) => id == child && r.contains("cancel tombstone")),
          s"tombstone skip reason recorded: ${report.revivalSkipped}"
        )
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(row.status, "failed", "salvage chain settles the honest terminal (never left running)")
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assert(!live, "tombstoned orphan must not be spawned into a live actor")
      yield ()
    }
  }

end DelegateRevivalSpec
