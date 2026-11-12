package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentCommand, AgentKind}
import nebflow.core.FileChangeTracker
import nebflow.core.project.ScopeHandover
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

  /** LLM stream that never ends: the revived turn stays in flight (the row stays running). */
  private val hangLlm = new nebflow.shared.LlmHandle[IO]:
    def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: nebflow.shared.LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ) =
      fs2.Stream.never[IO]

  private def mkTask(
    id: String,
    parent: String,
    status: String = "running",
    revivalCount: Option[Int] = None
  ): SubAgentTask =
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

  private def withFx(name: String, llm: nebflow.shared.LlmHandle[IO] = echoLlm)(body: Fx => IO[Unit]): Unit =
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

  test(
    "V1: eligible orphan revives from transcript checkpoint; the continued turn's end is the completion delivery (pass-through): the parent's mailbox gets the final text exactly once and the row completes"
  ) {
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
          fx.sessionStore
            .loadMessagesForSession(child)
            .map(transcript =>
              transcript.exists(m => m.role == MessageRole.User && m.textContent.contains("engine process restarted"))
            )
        }
        // Pass-through completion: the continued turn (stub echo final text) ends the delegate. Its final
        // text is mailed to the parent exactly once, the task row completes and the actor is released.
        // The revival fork runs the spawn -> echo -> finish chain asynchronously, so wait for the mail.
        _ <- waitUntil(10.seconds)(mailboxEvents(fx, parent.id).map(_.exists(_.correlationId.contains(child))))
        events <- mailboxEvents(fx, parent.id)
        delivered = events.filter(_.correlationId.contains(child))
        _ = assertEquals(delivered.size, 1, s"the continued turn's final text reaches the parent exactly once: $events")
        _ = assert(delivered.head.payload.contains("REVIVED_WORK_DONE"), delivered.head.payload)
        _ <- waitUntil(10.seconds)(
          fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).exists(_.status == "completed"))
        )
        stillLive <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assert(!stillLive, "the completion releases the revived actor")
      yield ()
    }
  }

  // 重写（fallback 废除批，作者令 2026-11-06 20:36「不用向上通知……由用户手动恢复」）：
  // 收殓链的四条投递腿（跨设备待发区/持久邮箱/F2 注入队/持久提醒账）全部摘除——
  // 预算耗尽的收殓面 = 任务行 failed + 指纹 + 「parent NOT notified」诚实文案；
  // 复活入口 = ManualRevival（面板逐个/一键）。原「parent notified via the persistent
  // mailbox」断言随批退役（原断言逐字见 git 历史）。
  test("V2(重写): revival budget exhausted → salvage chain (failed, zero notify), zero spawn") {
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
        _ = assertEquals(
          events.filter(e => e.correlationId.contains(child)),
          Nil,
          "zero-notify: the parent mailbox carries nothing (panel + ManualRevival are the only faces)"
        )
        _ = assert(
          row.lastError.exists(_.contains("parent NOT notified")),
          s"the row states the zero-notify ruling honestly: ${row.lastError}"
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
    withFx("v5", hangLlm) { fx =>
      def boot(task: SubAgentTask): IO[Unit] =
        // 模拟崩溃现场：行在飞、无活体（本 Fx 从未 spawn 过该会话的 actor——
        // registerAgent 只在复活腿内发生；此处先清 registry 再现「进程死亡」）。
        fx.system.stopAll *> fx.resources.agentRegistry.update(_ - task.taskId) *>
          sweepWith(fx).void *>
          // Let the revival settle between boots: the revived session is registered by an
          // asynchronous fork, and its turn never ends (hang stub), so the row stays running.
          IO.sleep(800.millis)

      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v5"
        base = mkTask(child, parent.id)
        _ <- fx.store.recordTask(base)
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.durableSwapMessages(
          child,
          List(Message(MessageRole.User, Left("original persisted task")))
        )
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
      end for
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

  test(
    "V8: author's fake-interrupt scenario — salvaged row flagged interrupted folds once the ledger adjudicates completed"
  ) {
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

  // ══════════════════════════════════════════════════════════════════════════
  // standby 分型（中断恢复批 2026-11-04 · 审计 D2；作者 2026-11-04 11:27 裁定
  // 「链等待形态必须自动复活」）——band 正反用例：
  //  V10 反向①：**纯待命**（无任何在飞旁证）⇒ 仍不复活，落收殓（诚实 failed+
  //      父通知），Mail 可续。案A 批（`f62a1795c`）语义逐字保留。
  //  V11 正向①：待命 + 作用域图仍有非终态节点（BB1 形态，等自家链）⇒ 自动复活。
  //  V12 正向②：待命 + worktree 脏（手上有未提交工作）⇒ 自动复活。
  //  V13 正向③：待命 + 分支领先基分支（有未合并交付提交）⇒ 自动复活。
  //  V14 反向②：待命 + 在飞旁证 + **cancel 墓碑** ⇒ 仍不复活（墓碑压过旁证）。
  //
  // 三分法结论（arch919 考古，可直接核对）：9.19 所指 = annotated tag `2026.9.19`
  // → commit `249a83958`（恢复面与 `949ce9159` 逐字节一致）；**能力退化：无**；
  // **触发条件收紧：仅 standby 闸这一项**（案A 批 `f62a1795c` 新增）——V10–V14
  // 即对这一项的分型修正，其余资格判据（kill-switch / 座位 / 父会话 / 预算 /
  // 年龄 / 并发 cap）9.19 起无一收紧、本批零改动。
  // ══════════════════════════════════════════════════════════════════════════

  test(
    "V10: PURE standby (zero in-flight evidence) at engine restart → still refused, salvage settles failed — 案A semantics preserved"
  ) {
    withFx("v10") { fx =>
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-nebflow-v10"
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        // 待命停靠形态：record.standbySince 在场（回合收尾采纳 standby 的落盘），
        // 且**零在飞旁证**：无作用域图、座位非 git 仓、不在合并队列。
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString)
              .copy(standbySince = Some(System.currentTimeMillis()), reportStatus = Some("standby"))
          )
        )
        report <- sweepWith(fx)
        _ = assertEquals(
          report.revived,
          Nil,
          "a PURE parked standby delegate must NOT be revived into a working session"
        )
        _ = assertEquals(report.terminalized, List(child))
        _ = assert(
          report.revivalSkipped.exists((id, r) => id == child && r.contains("standby at engine restart")),
          s"standby skip reason recorded: ${report.revivalSkipped}"
        )
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(
          row.status,
          "failed",
          "salvage settles the honest terminal (never a completed fake, never left running)"
        )
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assert(!live, "pure standby orphan must not be spawned")
      yield ()
    }
  }

  test(
    "V11: standby WHILE its own chain is still in flight (non-terminal scope node) → auto-revived (BB1 form, author ruling 2026-11-04 11:27)"
  ) {
    withFx("v11") { fx =>
      val child = "delegate-nebflow-v11"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString)
              .copy(standbySince = Some(System.currentTimeMillis()), reportStatus = Some("standby"))
          )
        )
        // 作用域图：一个仍在跑的节点（= 等自家链，不是在等作者）。
        scopeDir = ScopeHandover.scopeDir(fx.seat.toString, child)
        _ <- IO(os.makeDir.all(scopeDir))
        _ <- IO(
          os.write(
            scopeDir / "flow-map.json",
            """{"nodes":{"n-inflight":{"id":"n-inflight","name":"n-inflight","agent":"general","status":"running"}}}"""
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("brief")), Message(MessageRole.Assistant, Left("spawned n-inflight")))
        )
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assertEquals(
          report.revived,
          List(child),
          s"chain-wait standby must be auto-revived (report=${report.summary}, skip=${report.revivalSkipped})"
        )
        _ = assertEquals(row.status, "running", "revived row keeps running (Processing)")
        _ = assertEquals(row.revivalCount, Some(1))
        _ = assert(live, "revived chain-wait standby must be registered live")
      yield ()
      end for
    }
  }

  test("V12: standby + DIRTY worktree (uncommitted work in the seat) → auto-revived") {
    withFx("v12") { fx =>
      val child = "delegate-nebflow-v12"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(gitFixture(fx.seat))
        _ <- IO(os.write(fx.seat / "work-in-progress.txt", "uncommitted deliverable"))
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString)
              .copy(standbySince = Some(System.currentTimeMillis()), reportStatus = Some("standby"))
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("brief")), Message(MessageRole.Assistant, Left("wrote work-in-progress")))
        )
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(
          report.revived,
          List(child),
          s"standby with a dirty worktree = unfinished work in hand ⇒ revivable (skip=${report.revivalSkipped})"
        )
        _ = assertEquals(row.status, "running")
      yield ()
      end for
    }
  }

  test("V13: standby + branch ahead of its base (unmerged deliverable commits) → auto-revived") {
    withFx("v13") { fx =>
      val child = "delegate-nebflow-v13"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        base <- IO(gitFixture(fx.seat))
        _ <- IO {
          os.proc("git", "-C", fx.seat.toString, "checkout", "-q", "-b", "delegate/spec-v13")
            .call(cwd = fx.seat, check = true)
          os.write(fx.seat / "delivered.txt", "committed deliverable")
          os.proc("git", "-C", fx.seat.toString, "add", "-A").call(cwd = fx.seat, check = true)
          os.proc("git", "-C", fx.seat.toString, "commit", "-q", "-m", "deliverable").call(cwd = fx.seat, check = true)
        }
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString).copy(
              standbySince = Some(System.currentTimeMillis()),
              reportStatus = Some("standby"),
              branch = Some("delegate/spec-v13"),
              baseBranch = Some(base)
            )
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("brief")), Message(MessageRole.Assistant, Left("committed deliverable")))
        )
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(
          report.revived,
          List(child),
          s"standby with unmerged deliverable commits ⇒ revivable (skip=${report.revivalSkipped})"
        )
        _ = assertEquals(row.status, "running")
      yield ()
      end for
    }
  }

  test(
    "V14: cancel tombstone WINS over in-flight evidence — a cancelled delegate is never revived (author: only manual cancel/close stay dead)"
  ) {
    withFx("v14") { fx =>
      val child = "delegate-nebflow-v14"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString)
              .copy(standbySince = Some(System.currentTimeMillis()), reportStatus = Some("standby"))
          )
        )
        _ <- IO(nebflow.core.delegate.DelegateRegistry.markCancelRequested(child, "the user panel"))
        // 在飞旁证齐备（作用域图有非终态节点）——墓碑仍须压过旁证。
        scopeDir = ScopeHandover.scopeDir(fx.seat.toString, child)
        _ <- IO(os.makeDir.all(scopeDir))
        _ <- IO(
          os.write(
            scopeDir / "flow-map.json",
            """{"nodes":{"n1":{"id":"n1","name":"n1","agent":"general","status":"running"}}}"""
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(child, List(Message(MessageRole.User, Left("brief"))))
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assertEquals(report.revived, Nil, "cancel tombstone must veto the in-flight exemption")
        _ = assert(
          report.revivalSkipped.exists((id, r) => id == child && r.contains("cancel tombstone")),
          s"refusal attributed to the tombstone gate: ${report.revivalSkipped}"
        )
        _ = assertEquals(row.status, "failed")
        _ = assert(!live, "no actor may be spawned for a cancelled delegate")
      yield ()
      end for
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // failed 行复活腿（中断恢复批 2026-11-04 · 任务②；作者原话「只有人工 cancel/
  // close 的才不复活」）：
  //  V15 正向：failed ∧ RestartLossFingerprint ∧ 无墓碑 ⇒ 下一 boot 复活续跑。
  //  V16 反向：failed ∧ **自然失败**（LLM 网络错等，无指纹）⇒ 永不被复活腿捞起。
  //  V17 有界：failed ∧ 指纹 ∧ 预算耗尽 ⇒ 不复活（只幂等重走收殓，不无限 spawn）。
  // ══════════════════════════════════════════════════════════════════════════

  test("V15: failed row carrying the restart-loss fingerprint → revivable on the next boot (no manual Mail needed)") {
    withFx("v15") { fx =>
      val child = "delegate-nebflow-v15"
      val lost =
        s"${SubAgentStartupRecovery.RestartLossFingerprint} — partial result salvaged and delivered to parent (startup recovery V2)"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(
          mkTask(child, parent.id, status = "failed")
            .copy(lastError = Some(lost), completedAt = Some(System.currentTimeMillis() - 5_000L))
        )
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("brief")), Message(MessageRole.Assistant, Left("halfway done")))
        )
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assertEquals(
          report.revived,
          List(child),
          s"a restart-lost failed row must get a second chance (report=${report.summary}, skip=${report.revivalSkipped})"
        )
        _ = assertEquals(row.status, "running", "revival flips the row back to running")
        _ = assertEquals(row.revivalCount, Some(1), "budget accounted exactly once")
        _ = assertEquals(row.lastError, None, "the stale restart-loss error is cleared by the new execution window")
        _ = assert(live, "the revived failed row is spawned live")
      yield ()
      end for
    }
  }

  test("V16: failed row from a NATURAL failure (no restart fingerprint) → never picked up by the revival leg") {
    withFx("v16") { fx =>
      val child = "delegate-nebflow-v16"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(
          mkTask(child, parent.id, status = "failed").copy(
            lastError = Some("LLM stream error: chunked transfer encoding"),
            completedAt = Some(System.currentTimeMillis() - 5_000L)
          )
        )
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(child, List(Message(MessageRole.User, Left("brief"))))
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assertEquals(report.revived, Nil, "an honest natural failure is NOT an orphan of the restart")
        _ = assertEquals(report.terminalized, Nil, "and it is not re-terminalized either (zero action)")
        _ = assertEquals(row.status, "failed")
        _ = assertEquals(
          row.lastError,
          Some("LLM stream error: chunked transfer encoding"),
          "the honest error is untouched"
        )
        _ = assert(!live, "no spawn for a genuinely failed delegate")
      yield ()
      end for
    }
  }

  test("V17: failed row with the fingerprint but an EXHAUSTED budget → no revival (bounded, no infinite loop)") {
    withFx("v17") { fx =>
      val child = "delegate-nebflow-v17"
      val lost =
        s"${SubAgentStartupRecovery.RestartLossFingerprint} — result lost, parent notified (startup recovery V2)"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(
          mkTask(child, parent.id, status = "failed", revivalCount = Some(2)).copy(lastError = Some(lost))
        )
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(child, List(Message(MessageRole.User, Left("brief"))))
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        live <- fx.resources.agentRegistry.get.map(_.contains(child))
        _ = assertEquals(report.revived, Nil, "budget exhausted ⇒ no spawn (maxPerTask bound holds)")
        _ = assert(
          report.revivalSkipped.exists((id, r) => id == child && r.contains("revival budget exhausted")),
          s"skip reason = budget: ${report.revivalSkipped}"
        )
        _ = assertEquals(row.status, "failed")
        _ = assertEquals(row.revivalCount, Some(2), "no extra budget consumption on the refused attempt")
        _ = assert(!live)
      yield ()
      end for
    }
  }

  // ── git 夹具（V12/V13）：临时座位建成真 git 仓（只写仓库本地 config，绝不碰全局）。 ──
  private def gitFixture(ws: os.Path): String =
    def git(args: String*): String =
      os.proc(Seq("git", "-C", ws.toString) ++ args).call(cwd = ws, check = true).out.trim()
    git("init", "-q")
    git("config", "user.email", "spec@nebflow.local")
    git("config", "user.name", "spec")
    os.write(ws / "seed.txt", "seed")
    git("add", "-A")
    git("commit", "-q", "-m", "seed")
    git("rev-parse", "--abbrev-ref", "HEAD")

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
