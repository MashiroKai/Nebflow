package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import munit.FunSuite
import nebflow.actor.{ActorSystem, Behaviors}
import nebflow.core.PathUtil
import nebflow.core.compact.HistoryArchiver
import nebflow.core.FileChangeTracker
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.{AgentControlTool, FileLockManager, ToolContext}
import nebflow.gateway.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}
import nebflow.shared.MessageRole

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

  private def mkTask(id: String, parent: String, status: String = "running", prompt: String = "do the thing"): SubAgentTask =
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
      val sessionStore = SessionStore(tmp / "sessions", tmp / "tasks")
      body(store, sessionStore, tmp)
    finally
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)

  test("R1: orphan sweep terminalizes + notifies parent with salvaged partial result") {
    withFixture("r1") { (store, sessionStore, tmp) =>
      val io = for
        parent <- sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        childA = "delegate-explorer-a1"
        childB = "delegate-explorer-b2"
        _ <- store.recordTask(mkTask(childA, parent.id))
        _ <- store.recordTask(mkTask(childB, parent.id))
        // Child A left a partial transcript on disk; child B left nothing.
        _ <- sessionStore.saveMessagesForSession(childA, List(
          nebflow.shared.Message(MessageRole.User, Left("start work")),
          nebflow.shared.Message(MessageRole.Assistant, Left("V2_PARTIAL_RESULT_TEXT"))
        ))
        recovered <- SubAgentStartupRecovery.recoverOrphans(store, sessionStore)
        running <- store.findRunningTasks
        events <- CompactionQueueStore.load(parent.id)
      yield (parent.id, recovered, running, events, childA, childB)

      val (parentSid, recovered, running, events, childA, childB) = io.unsafeRunSync()
      assertEquals(clue(recovered.sorted), List(childA, childB), "both orphans recovered")
      assert(clue(running).isEmpty, "no running tasks remain after the sweep")
      val evts = clue(events.map(_.events).getOrElse(Nil))
      assertEquals(evts.size, 2, "one notification event per orphan")
      val evtA = evts.find(_.correlationId.contains(childA)).getOrElse(fail(s"no event for $childA"))
      assertEquals(evtA.eventType, "failed")
      assert(evtA.payload.contains("task lost: crash"), s"payload must carry the loss note: ${evtA.payload}")
      assert(evtA.payload.contains("V2_PARTIAL_RESULT_TEXT"), s"partial result must be salvaged: ${evtA.payload}")
      val evtB = evts.find(_.correlationId.contains(childB)).getOrElse(fail(s"no event for $childB"))
      assert(evtB.payload.contains("No recoverable result"), s"unsalvageable task must say so: ${evtB.payload}")
      // Terminalized with a loss note.
      val tasks = store.loadTasks(parentSid).unsafeRunSync()
      assert(tasks.forall(t => t.status == "failed" && t.lastError.exists(_.contains("startup recovery"))), clue(tasks).toString)
    }
  }

  test("R2: sweep is idempotent across repeated runs (no duplicate notify, no re-terminalize)") {
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
      assertEquals(first.map(_.events).map(_.size), Some(1))
      assertEquals(second.map(_.events).map(_.size), Some(1), "second sweep must NOT append a duplicate notification")
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
        queueFileExists <- IO(os.exists(PathUtil.dataRoot / "sessions" / "deleted-parent-sid" / "injection-queues.json"))
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
          def sendStream(req: nebflow.shared.LlmRequest, onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None) =
            fs2.Stream.eval(llmRef.update(_ + 1)) >> fs2.Stream(
              nebflow.shared.StreamChunk.TextDelta("ok"), nebflow.shared.StreamChunk.Done(None, None))
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
        _ <- resources.agentRegistry.update(_ + (callerSid -> AgentRecord(callerSid, callerRef, AgentKind.Root, callerSid)))
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
        case Left(err)   => fail(s"list failed: $err")
      assert(clue(out).contains("delegate-explorer-e5"), s"orphan task must appear in list:\n$out")
      assert(clue(out).contains("orphan(running)"), s"orphan row must be labeled:\n$out")
    }
  }

  test("R5 RED BASELINE: with the sweep bypassed, the audited loss shape reproduces (task stuck running, no notification)") {
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

end OrphanDelegateRecoverySpec
