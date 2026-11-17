package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.actor.{AgentCommand, AgentKind, AgentRecord, status}
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{AgentControlTool, FileLockManager, ToolContext}
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{MessageRole, PathUtil, ThinkingConfig, SubAgentTask}

import scala.concurrent.duration.*

/**
 * V2 (2026-09-03, 结果投递链丢失向量修复): 孤儿 delegate 启动收殓。
 *
 * 丢失形态（审计 V2）：进程崩溃时在飞的 Delegate/SubTask 任务——
 * findRunningTasks 注释承诺 startup recovery 但全仓无启动调用；重启后
 * registry 清空，AgentControl list 按 registry 行 join 连孤儿行都不显示；
 * 父会话恢复后「You will be notified when it completes」永不兑现。
 *
 * 修复：SubAgentStartupRecovery.recoverOrphans 挂 GatewayMain 启动装配点——
 * 部分结果抢救（子会话磁盘转录）+ 父会话 F2 队列通知（correlationId 幂等）
 * + 任务记录终态化；AgentControl list 补 registry-free 孤儿行。
 *
 * 用例：
 *  - R1 收殓：running 任务 → failed + 父 F2 队列通知事件（含抢救的部分结果）
 *  - R2 幂等：连跑两次 → 不重复通知、不重复终态化
 *  - R3 父会话已删：只终态化、不写队列（不产生无人读的垃圾文件）
 *  - R4 AgentControl list 显示孤儿行（不依赖 registry join）
 *  - R5 验红基线（绕过收殓）→ 丢失形态复现：任务滞留 running、无通知
 */
class OrphanDelegateRecoverySpec extends FunSuite:

  private val originalRoot = PathUtil.dataRoot

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  nebflow.core.LlmLogWriter.setEnabled(false)

  private def mkTask(
    id: String,
    parent: String,
    status: String = "running",
    prompt: String = "do the thing"
  ): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "Worker",
      prompt = prompt,
      description = s"e2e-$id",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  private def withFixture(name: String)(body: (SubAgentTaskStore, SessionStore, os.Path) => Unit): Unit =
    val tmp = os.temp.dir(prefix = s"v2-$name")
    PathUtil.setDataRoot(tmp / "data")
    try
      val store = new SubAgentTaskStore(tmp / "subagent-tasks")
      os.makeDir.all(tmp / "sessions")
      val sessionStore = SessionStore(tmp / "sessions", tmp / "tasks")
      body(store, sessionStore, tmp)
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)

  // 重写（fallback 废除批，作者令 2026-11-06 20:36「不用向上通知……由用户手动恢复」）：
  // 收殓链的四条父投递腿（跨设备待发区/持久邮箱/F2 注入队/持久提醒账）全部摘除 ⇒
  // 零通知事件；任务行 = failed + 重启打断指纹 + 「parent NOT notified」诚实文案。
  // 原「one notification event per orphan + payload 断言」面随批退役（原断言逐字见
  // git 历史）；部分结果仍在子会话转录里（面板/弹窗可读）——零通知不等于丢证据。
  test("R1(重写): orphan sweep terminalizes with zero upward notify; partial result stays readable in the transcript") {
    withFixture("r1") { (store, sessionStore, tmp) =>
      val io = for
        parent <- sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        childA = "delegate-explorer-a1"
        childB = "delegate-explorer-b2"
        _ <- store.recordTask(mkTask(childA, parent.id))
        _ <- store.recordTask(mkTask(childB, parent.id))
        // Child A left a partial transcript on disk; child B left nothing.
        _ <- sessionStore.saveMessagesForSession(
          childA,
          List(
            nebflow.shared.Message(MessageRole.User, Left("start work")),
            nebflow.shared.Message(MessageRole.Assistant, Left("V2_PARTIAL_RESULT_TEXT"))
          )
        )
        recovered <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore)
        running <- store.findRunningTasks
        events <- CompactionQueueStore.load(parent.id)
        partialSalvaged <- sessionStore.loadMessagesForSession(childA).map(
          _.exists(_.textContent.contains("V2_PARTIAL_RESULT_TEXT"))
        )
      yield (parent.id, recovered, running, events, childA, childB, partialSalvaged)

      val (parentSid, recovered, running, events, childA, childB, partialSalvaged) = io.unsafeRunSync()
      assertEquals(clue(recovered.sorted), List(childA, childB), "both orphans recovered")
      assert(clue(running).isEmpty, "no running tasks remain after the sweep")
      val evts = clue(events.map(_.events).getOrElse(Nil))
      assertEquals(evts.size, 0, "zero-notify: the sweep delivers nothing upward (was: one event per orphan)")
      assert(clue(partialSalvaged), "the partial result stays readable in the child transcript (panel face)")
      // Terminalized with the restart-loss fingerprint + the zero-notify honesty line.
      val tasks = store.loadTasks(parentSid).unsafeRunSync()
      assert(
        tasks.forall(t => t.status == "failed" && t.lastError.exists(_.contains("process restart during in-flight task"))),
        clue(tasks).toString
      )
      assert(
        tasks.forall(t => t.lastError.exists(_.contains("parent NOT notified"))),
        clue(tasks).toString
      )
    }
  }

  test("startup discovery preserves a protected task after authority reopen") {
    withFixture("protected-startup") { (store, sessionStore, tmp) =>
      val child = "delegate-protected"
      val admission = nebflow.shared.ExecutionAdmission(
        child, "run", "commit", "request", "attempt", "incarnation", "owner", "business", "audit",
        _root_.io.circe.Json.fromString("initial"), "input"
      )
      val io = for
        _ <- IO.blocking(os.makeDir.all(tmp / "sessions"))
        _ <- store.recordTask(mkTask(child, "parent"))
        _ <- ExecutionCommitStore.resource(sessionStore.executionAuthorityRoot).use(_.admit(admission))
        recovered <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore)
        row <- store.findByTaskId(child)
        reopened <- ExecutionCommitStore.resource(sessionStore.executionAuthorityRoot).use(_.protection(child))
      yield (recovered, row, reopened)
      val (recovered, row, protection) = io.unsafeRunSync()
      assertEquals(recovered, Nil)
      assertEquals(row.map(_.status), Some("running"))
      assertEquals(protection, nebflow.shared.ExecutionProtection.Protected(List("run")))
    }
  }

  test("R2(重写): sweep is idempotent across repeated runs (zero notify both times, no re-terminalize)") {
    withFixture("r2") { (store, sessionStore, tmp) =>
      val io = for
        parent <- sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-explorer-c3"
        _ <- store.recordTask(mkTask(child, parent.id))
        _ <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore)
        eventsAfterFirst <- CompactionQueueStore.load(parent.id)
        _ <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore) // "second boot"
        eventsAfterSecond <- CompactionQueueStore.load(parent.id)
        running <- store.findRunningTasks
      yield (eventsAfterFirst, eventsAfterSecond, running)

      val (first, second, running) = io.unsafeRunSync()
      // 零通知 ⇒ 投递队条目根本不会创建（load 可能返回 None）——None 与空 events 同读数。
      val firstN = first.map(_.events).map(_.size).getOrElse(0)
      val secondN = second.map(_.events).map(_.size).getOrElse(0)
      assertEquals(firstN, 0, "zero-notify on the first sweep")
      assertEquals(secondN, 0, "second sweep: still zero (no duplicate, none at all)")
      assert(clue(running).isEmpty, "terminalized tasks are not re-found")
    }
  }

  test("R3: orphan whose parent session was deleted is terminalized without notification") {
    withFixture("r3") { (store, sessionStore, tmp) =>
      val io = for
        // No session ever created for this parent id (deleted while child ran).
        _ <- store.recordTask(mkTask("delegate-explorer-d4", "deleted-parent-sid"))
        recovered <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore)
        running <- store.findRunningTasks
        // The queue file must NOT be created for a non-existent parent.
        queueFileExists <- IO(
          os.exists(PathUtil.dataRoot / "sessions" / "deleted-parent-sid" / "injection-queues.json")
        )
      yield (recovered, running, queueFileExists)

      val (recovered, running, queueFileExists) = io.unsafeRunSync()
      assertEquals(clue(recovered), List("delegate-explorer-d4"))
      assert(clue(running).isEmpty)
      assert(!clue(queueFileExists), "no queue file may be written for a deleted parent session")
    }
  }

  /** 自引用 idle behavior（root-bucket caller 占位 ref）。 */
  private def idleBehavior: nebflow.actor.Behavior[AgentCommand] =
    lazy val b: nebflow.actor.Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](_ => IO.pure(b))
    b

  test("R4: AgentControl list shows orphan rows without a registry entry (join-free path)") {
    withFixture("r4") { (store, sessionStore, tmp) =>
      val system = ActorSystem(s"v2-r4-${java.util.UUID.randomUUID().toString.take(6)}")
      val io = for
        dispatcher <- cats.effect.std.Dispatcher.parallel[IO].allocated.map(_._1)
        rateLimiter <- RateLimiter.create()
        tracker <- FileChangeTracker.create(os.pwd.toString)
        fileLocks <- FileLockManager.create
        thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
        modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
        voiceMuted <- Ref.of[IO, Boolean](false)
        llmRef <- Ref.of[IO, Int](0)
        llm = new nebflow.shared.LlmHandle[IO]:
          def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
            IO.raiseError(new RuntimeException("not expected"))
          def sendStream(
            req: nebflow.shared.LlmRequest,
            onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
          ) =
            fs2.Stream.eval(llmRef.update(_ + 1)) >> fs2.Stream(
              nebflow.shared.StreamChunk.TextDelta("ok"),
              nebflow.shared.StreamChunk.Done(None, None)
            )
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
        // Root-bucket caller (empty registry otherwise — the crash shape).
        callerRef <- system.spawn(idleBehavior, "v2-caller")
        callerSid = "v2-caller-sid"
        _ <- resources.agentRegistry.update(
          _ + (callerSid -> AgentRecord(callerSid, callerRef, AgentKind.Root, callerSid))
        )
        // Orphan: running task, NO registry row (previous process crash).
        _ <- store.recordTask(mkTask("delegate-explorer-e5", callerSid))
        listRes <- AgentControlTool.call(
          JsonObject("action" -> "list".asJson),
          ToolContext(projectRoot = os.pwd.toString, sessionId = Some(callerSid), sharedResources = Some(resources))
        )
      yield listRes

      val listRes = io.unsafeRunSync()
      system.stopAll.attempt.void.unsafeRunSync()
      val out = listRes match
        case Right(text) => text
        case Left(err) => fail(s"list failed: $err")
      assert(clue(out).contains("delegate-explorer-e5"), s"orphan task must appear in list:\n$out")
      assert(clue(out).contains("orphan(running)"), s"orphan row must be labeled:\n$out")
    }
  }

  test(
    "R5 RED BASELINE: with the sweep bypassed, the audited loss shape reproduces (task stuck running, no notification)"
  ) {
    withFixture("r5") { (store, sessionStore, tmp) =>
      val io = for
        parent <- sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-explorer-f6"
        _ <- store.recordTask(mkTask(child, parent.id))
        // Pre-fix behavior: findRunningTasks existed but nothing called it at
        // startup — the mutation bypass stands in for the missing wiring.
        _ <- IO.unit // (mutation point: recoverOrphans not called)
        running <- store.findRunningTasks
        events <- CompactionQueueStore.load(parent.id)
      yield (child, running, events)

      val (child, running, events) = io.unsafeRunSync()
      assertEquals(clue(running.map(_.taskId)), List(child), "task stuck in running forever (loss shape)")
      assertEquals(clue(events.map(_.events).getOrElse(Nil).size), 0, "parent never notified (loss shape)")
    }
  }

  /** registry 记录夹具（重启打断标记腿的行面）。 */
  private def mkRecord(id: String, createdAt: Long): nebflow.core.delegate.DelegateRegistry.Record =
    nebflow.core.delegate.DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = "/tmp/spec-cwd",
      project = None,
      taskId = None,
      parentSessionId = "spec-root",
      title = "spec interrupted row",
      createdAt = createdAt
    )

  test(
    "R6 (重启对账): a 'restarting' (supervisor backoff) orphan is terminalized AND its registry row gets the restart-interrupted flag"
  ) {
    withFixture("r6") { (store, sessionStore, tmp) =>
      val io = for
        parent <- sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        child = "delegate-explorer-g7"
        _ <- store.recordTask(mkTask(child, parent.id, status = "restarting")) // 旧 sweep 只认 running——漏收形态
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(child, createdAt = 1L)))
        _ <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore)
        running <- store.findRunningTasks
        tasks <- store.loadTasks(parent.id)
        rec <- nebflow.core.delegate.DelegateRegistry.find(child) match
          case Some(r) => IO.pure(r)
          case None    => IO.raiseError[nebflow.core.delegate.DelegateRegistry.Record](new AssertionError("registry row missing"))
      yield (running, tasks, rec)

      val (running, tasks, rec) = io.unsafeRunSync()
      assert(clue(running).isEmpty, "restarting-state orphans are in-flight too — terminalized")
      assert(clue(tasks).forall(_.status == "failed"), "restarting → failed terminalize")
      assert(
        clue(rec.restartInterruptedAt).isDefined,
        "the restart-interrupted flag lands on the registry row (badge/gray-state basis)"
      )
    }
  }

  test(
    "R7 (重启对账): fingerprint backfill flags historical restart-lost rows — skipped for reported/archived rows, idempotent on re-run"
  ) {
    withFixture("r7") { (store, sessionStore, tmp) =>
      val fp = SubAgentStartupRecovery.RestartLossFingerprint
      val lostTask = mkTask("delegate-explorer-h8", "p-h8").copy(
        status = "failed",
        lastError = Some(s"$fp — partial result salvaged and delivered to parent (startup recovery V2)")
      )
      val doneTask = mkTask("delegate-explorer-i9", "p-i9").copy(
        status = "failed",
        lastError = Some("ordinary failure — not a restart loss")
      )
      store.saveTasks("p-h8", List(lostTask)).unsafeRunSync()
      store.saveTasks("p-i9", List(doneTask)).unsafeRunSync()
      val io = for
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord("delegate-explorer-h8", createdAt = 1L))) // 重启丢失
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord("delegate-explorer-i9", createdAt = 2L))) // 常规失败
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord("delegate-explorer-j10", createdAt = 3L).copy(reportStatus = Some("finish"))))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord("delegate-explorer-k11", createdAt = 4L).copy(archivedAt = Some(5L))))
        flagged0 <- IO(nebflow.core.delegate.DelegateRegistry.find("delegate-explorer-h8").flatMap(_.restartInterruptedAt))
        _ <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore) // 无 running 孤儿——只跑标记回溯腿
        h8 <- IO(nebflow.core.delegate.DelegateRegistry.find("delegate-explorer-h8").flatMap(_.restartInterruptedAt))
        i9 <- IO(nebflow.core.delegate.DelegateRegistry.find("delegate-explorer-i9").flatMap(_.restartInterruptedAt))
        j10 <- IO(nebflow.core.delegate.DelegateRegistry.find("delegate-explorer-j10").flatMap(_.restartInterruptedAt))
        k11 <- IO(nebflow.core.delegate.DelegateRegistry.find("delegate-explorer-k11").flatMap(_.restartInterruptedAt))
        _ <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore) // "second boot" 幂等
        h8again <- IO(nebflow.core.delegate.DelegateRegistry.find("delegate-explorer-h8").flatMap(_.restartInterruptedAt))
      yield (flagged0, h8, h8again, i9, j10, k11)

      val (flagged0, h8, h8again, i9, j10, k11) = io.unsafeRunSync()
      assert(clue(flagged0).isEmpty, "no flag before the sweep")
      assert(clue(h8).isDefined, "fingerprint task ⇒ registry row flagged (historical backfill)")
      assertEquals(clue(h8again), h8, "idempotent: second boot never rewrites the flag")
      assert(clue(i9).isEmpty, "ordinary failure without the fingerprint stays unflagged")
      assert(clue(j10).isEmpty, "a reported (finish) row is never flagged — true completion wins")
      assert(clue(k11).isEmpty, "archived rows are untouched (zero-touch archive discipline)")
    }
  }

end OrphanDelegateRecoverySpec
