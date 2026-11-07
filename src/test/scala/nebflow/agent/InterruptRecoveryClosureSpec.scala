/* 中断恢复收口批 spec（2026-11-04 · 9.19 版中断恢复批 C-1）
 *
 * 覆盖三件本批新增/修复的机械判据（判词节点可独立复跑）：
 *  ① V4 断点台账：复活必须留下**机器可读**的「已完成到哪」（`RevivalCheckpoint`），
 *     并把读数拼进复活 nudge（模型据此不重做已完成步骤）；
 *  ② V5 收尾腿：`completed ∧ registry.workReport 非空 ∧ reportStatus 缺席` 的会话由
 *     boot 腿自动收尾（workReport 投父邮箱 + 记录置 finish），且**不误吞在飞工作**
 *     （没干完的仍走复活腿，绝不投「已完成」通知）；
 *  ③ D1 收殓回执重感知：收殓通知的持久提醒账（`RestartLossLedger`）在上下文压缩处
 *     重述事实，且**有界**（每条至多 2 次、24h 自清、同 taskId 重投即重置）。
 *
 * 安全：纯 spec 缝（本 JVM 内），不启动引擎进程、不触碰生产实例与 ~/.stand。
 */
package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.actor.AgentCommand
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.delegate.DelegateRegistry
import nebflow.core.executor.ExecutorRegistry
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{ContentBlock, Message, MessageRole, PathUtil, StreamChunk, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

class InterruptRecoveryClosureSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 90.seconds

  private val originalRoot = PathUtil.dataRoot
  nebflow.core.LlmLogWriter.setEnabled(false)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    nebflow.core.LlmLogWriter.setEnabled(true)

  /** 回声 LLM（续跑回合有响应即可；断言面只看落盘）。 */
  private val echoLlm = new nebflow.shared.LlmHandle[IO]:
    def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: nebflow.shared.LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ) =
      fs2.Stream(StreamChunk.TextDelta("IR_CLOSURE_STUB_TURN"), StreamChunk.Done(None, None))

  private final case class Fx(
      store: SubAgentTaskStore,
      sessionStore: SessionStore,
      resources: SharedResources,
      system: nebflow.actor.ActorSystem,
      tmp: os.Path,
      seat: os.Path
  )

  private def withFx(name: String)(body: Fx => IO[Unit]): Unit =
    val tmp = os.temp.dir(prefix = s"ir-closure-$name")
    val seat = os.temp.dir(prefix = s"ir-closure-seat-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = nebflow.actor.ActorSystem(s"ir-closure-$name-${java.util.UUID.randomUUID().toString.take(6)}")
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

  private def mkTask(id: String, parent: String, status: String = "running", revivalCount: Option[Int] = None) =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "do the thing",
      description = s"closure-$id",
      status = status,
      retryCount = 0,
      spawnedAt = System.currentTimeMillis() - 60_000L,
      completedAt = if status == "completed" then Some(System.currentTimeMillis() - 30_000L) else None,
      lastError = None,
      source = "delegate",
      revivalCount = revivalCount
    )

  private def mkRecord(id: String, parent: String, cwd: String) =
    DelegateRegistry.Record(
      id = id,
      executor = ExecutorRegistry.DefaultId,
      cwd = cwd,
      project = None,
      taskId = None,
      parentSessionId = parent,
      title = s"closure record $id",
      createdAt = System.currentTimeMillis() - 120_000L
    )

  private def sweepWith(fx: Fx) =
    SubAgentStartupRecovery.sweep(
      fx.store,
      fx.sessionStore,
      Some(SubAgentStartupRecovery.RevivalDeps(fx.resources, fx.system, None))
    )

  private def waitUntil(timeout: FiniteDuration, what: String)(cond: IO[Boolean]): IO[Unit] =
    def go(deadline: Long): IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false =>
          if System.currentTimeMillis() >= deadline then
            IO.raiseError(new AssertionError(s"[waitUntil] $what — not met within ${timeout.toSeconds}s"))
          else IO.sleep(80.millis) >> go(deadline)
      }
    go(System.currentTimeMillis() + timeout.toMillis)

  private def mailboxEvents(fx: Fx, parentId: String): IO[List[AgentCommand.ExternalEvent]] =
    for
      pending <- SessionMailbox.scanPending(parentId)
      taken <- SessionMailbox.scanTaken(parentId)
    yield (pending ++ taken).map(_.event)

  // ══════════════════════════════════════════════════════════════════════════
  // ① V4 — 断点台账（机器可读「已完成到哪」+ 随 nudge 交付）
  // ══════════════════════════════════════════════════════════════════════════

  test("V4/checkpoint: revival writes a machine-readable completed-work ledger AND carries its readout into the revival nudge") {
    withFx("v4") { fx =>
      val child = "delegate-nebflow-cp"
      val preCrash = List(
        Message(MessageRole.User, Left("CP_BRIEF: three steps")),
        Message(MessageRole.User, Right(List(ContentBlock.ToolUse("t1", "Bash", io.circe.JsonObject.empty)))),
        Message(MessageRole.Assistant, Left("CP_ROUND_1_DONE wrote file A")),
        Message(MessageRole.User, Right(List[ContentBlock](ContentBlock.ToolResult("t1", "ok")))),
        Message(MessageRole.Assistant, Left("CP_ROUND_2_DONE wrote file B")),
        Message(MessageRole.Assistant, Left("CP_ROUND_3_DONE wrote file C"))
      )
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id))
        _ <- IO(DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(child, preCrash)
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, List(child), s"fixture must revive: ${report.summary} / ${report.revivalSkipped}")
        // 台账落盘面（判据：<dataRoot>/sessions/<sid>/revival-checkpoint.json）。
        ledgerPath = RevivalCheckpoint.pathFor(child)
        _ = assert(os.exists(ledgerPath), s"a machine-readable checkpoint ledger must exist at $ledgerPath")
        raw = if os.exists(ledgerPath) then os.read(ledgerPath) else ""
        // 台账读数（机械核对：游标 = 6 条、已完成 assistant 回合 = 3、工具回合 = 1）。
        facts = RevivalCheckpoint.factsOf(preCrash)
        _ = assertEquals(facts.cursor, 6)
        _ = assertEquals(facts.completedTurns, 3)
        _ = assertEquals(facts.completedToolRounds, 1)
        _ = assert(raw.contains("\"completedTurns\":3"), s"ledger must persist completedTurns=3; got: $raw")
        _ = assert(raw.contains("CP_ROUND_3_DONE"), s"ledger must list completed steps; got: $raw")
        // nudge 面（模型可见）：断点读数必须随复活注入。
        _ <- waitUntil(20.seconds, "revival nudge") {
          fx.sessionStore.loadMessagesForSession(child).map(_.exists(_.textContent.contains("engine process restarted")))
        }
        transcript <- fx.sessionStore.loadMessagesForSession(child)
        nudge = transcript.find(_.textContent.contains("engine process restarted")).map(_.textContent).getOrElse("")
        _ = assert(
          nudge.contains("Completed-work checkpoint") && nudge.contains("3 completed assistant turn(s)"),
          s"the revival nudge must hand the model the machine-readable checkpoint; got:\n$nudge"
        )
        _ = assert(nudge.contains("must NOT be redone"), "the nudge must say the completed steps are not to be redone")
      yield ()
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // ② V5 — 完成未报告收尾腿（cb580112 形态）正反两用例
  // ══════════════════════════════════════════════════════════════════════════

  test("V5/finalize: completed ∧ workReport ∧ reportStatus 缺席 → boot leg closes it (work report to the parent mailbox) + idempotent") {
    withFx("v5") { fx =>
      val child = "delegate-nebflow-fin"
      val marker = "FIN_WORKREPORT_MARKER: branch merged, all checks green"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id, status = "completed"))
        _ <- IO(
          DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString)
              .copy(workReport = Some(marker), reportStatus = None, archivedAt = Some(System.currentTimeMillis() - 10_000L))
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("FIN_BRIEF")), Message(MessageRole.Assistant, Left(s"done; $marker")))
        )
        report <- sweepWith(fx)
        events1 <- mailboxEvents(fx, parent.id).map(_.filter(_.correlationId.contains(child)))
        rec1 <- IO(DelegateRegistry.find(child))
        // 二次 boot（幂等）：不产生第二条通知、状态不再变化。
        report2 <- sweepWith(fx)
        events2 <- mailboxEvents(fx, parent.id).map(_.filter(_.correlationId.contains(child)))
        rec2 <- IO(DelegateRegistry.find(child))
        _ = assert(events1.nonEmpty, s"the finished-but-unreported delegate must be announced to the parent: ${events1.size}")
        _ = assert(
          events1.exists(_.payload.contains("FIN_WORKREPORT_MARKER")),
          "the notification must carry the recorded work report verbatim"
        )
        _ = assertEquals(rec1.flatMap(_.reportStatus), Some("finish"), "the record lands the delivered state (reportStatus=finish)")
        _ = assertEquals(rec1.flatMap(_.workReport), Some(marker), "the work report is preserved (never overwritten)")
        _ = assertEquals(events2.size, events1.size, "second boot must not duplicate the notice (idempotent)")
        _ = assertEquals(rec2.flatMap(_.reportStatus), Some("finish"))
        _ = assertEquals(report2.terminalized, Nil, "an already-closed row is never touched again")
      yield ()
    }
  }

  test("V5/negative: work NOT finished (in-flight row) → revives, never finalized, no 'completed' notice to the parent") {
    withFx("v5n") { fx =>
      val child = "delegate-nebflow-open"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(child, parent.id, status = "running"))
        _ <- IO(
          DelegateRegistry.put(
            mkRecord(child, parent.id, fx.seat.toString)
              .copy(workReport = Some("PARTIAL_NOTE_MUST_NOT_BE_FINALIZED"), reportStatus = None)
          )
        )
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("opening brief")), Message(MessageRole.Assistant, Left("started")))
        )
        report <- sweepWith(fx)
        events <- mailboxEvents(fx, parent.id).map(_.filter(_.correlationId.contains(child)))
        rec <- IO(DelegateRegistry.find(child))
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        _ = assertEquals(report.revived, List(child), s"in-flight work still goes through the revival leg: ${report.summary}")
        _ = assertEquals(row.status, "running", "the revived row is Processing, not finalized")
        _ = assertEquals(rec.flatMap(_.reportStatus), None, "no delivered-report state may be invented for unfinished work")
        _ = assertEquals(events, Nil, s"no 'completed' notice may reach the parent for unfinished work: $events")
      yield ()
    }
  }

  // ══════════════════════════════════════════════════════════════════════════
  // ③ D1 — 收殓回执重感知（持久提醒账 + 压缩处重述，有界）
  // ══════════════════════════════════════════════════════════════════════════

  test("D1/recap: a delivered restart-loss notice is restated AFTER compaction, and the retelling is bounded (≤2, then silent)") {
    withFx("d1") { fx =>
      val lostId = "delegate-nebflow-d1-lost"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        _ <- RestartLossLedger.record(parent.id, lostId, "Delegate was still in flight when the process restarted")
        // 压缩完成处（AgentProcessing / AgentPingpong 两处同源调用）。
        first = RestartLossLedger.restatementSync(Some(parent.id))
        second = RestartLossLedger.restatementSync(Some(parent.id))
        third = RestartLossLedger.restatementSync(Some(parent.id))
        entries = RestartLossLedger.readSync(parent.id)
        _ = assert(first.isDefined, "the first compaction after delivery must restate the notice")
        _ = assert(first.exists(_.textContent.contains(lostId)), "the restatement must name the lost delegate")
        _ = assert(first.exists(_.textContent.contains("restart-loss recap")))
        _ = assert(second.isDefined, "a second compaction still restates it (bounded budget = 2)")
        _ = assertEquals(third, None, "budget exhausted ⇒ silent forever (no infinite re-injection)")
        _ = assertEquals(entries, Nil, "exhausted entries are pruned from the ledger (self-clearing)")
        _ = assertEquals(RestartLossLedger.restatementSync(Some("some-other-session")), None)
        _ = assertEquals(RestartLossLedger.restatementSync(None), None)
        // 尾部安全闸（provider 侧 tool_use/tool_result 必须相邻）：不安全的尾 ⇒ 不重述、
        // 且**不消耗**重述预算（下一拍压缩仍可重述）。
        _ = assert(
          !RestartLossLedger.appendSafeAtTail(
            List(Message(MessageRole.Assistant, Right(List[ContentBlock](ContentBlock.ToolUse("t9", "Bash", io.circe.JsonObject.empty)))))
          ),
          "an assistant tail carrying a pending tool_use must not take an appended user message"
        )
        _ = assert(RestartLossLedger.appendSafeAtTail(List(Message(MessageRole.Assistant, Left("plain text tail")))))
      yield ()
    }
  }

  // 重写（fallback 废除批，作者令 2026-11-06 20:36「不用向上通知……由用户手动恢复」）：
  // 收殓链的父投递腿 + 持久提醒账（RestartLossLedger.record）随零通知语义整体摘除——
  // 父邮箱零条目、台账零记录；呈现面 = 任务行（failed + 指纹 + 「parent NOT notified」
  // 诚实文案）。原「delivered notice + ledger 记录」断言随批退役（原断言逐字见 git 历史）。
  test("D1/receipt(重写): the salvage leg terminalizes honestly — zero parent notify, zero ledger notice, the row says so") {
    withFx("d1r") { fx =>
      val child = "delegate-nebflow-d1-salvage"
      val lost = s"${SubAgentStartupRecovery.RestartLossFingerprint} — partial result salvaged"
      for
        parent <- fx.sessionStore.createSession("Nebula", agentName = Some("Nebula"))
        // 收殓轨（不做复活）：预算耗尽 ⇒ 走 recoverOne（终态化 + 零通知）。
        _ <- fx.store.recordTask(mkTask(child, parent.id, revivalCount = Some(2)).copy(lastError = Some(lost)))
        _ <- IO(DelegateRegistry.put(mkRecord(child, parent.id, fx.seat.toString)))
        _ <- fx.sessionStore.saveMessagesForSession(
          child,
          List(Message(MessageRole.User, Left("brief")), Message(MessageRole.Assistant, Left("partial work")))
        )
        report <- sweepWith(fx)
        row <- fx.store.loadTasks(parent.id).map(_.find(_.taskId == child).get)
        events <- mailboxEvents(fx, parent.id).map(_.filter(_.correlationId.contains(child)))
        ledger = RestartLossLedger.readSync(parent.id)
        _ = assert(report.terminalized.contains(child), s"budget exhausted ⇒ salvage: ${report.summary}")
        _ = assertEquals(events, Nil, "zero-notify: the parent mailbox carries nothing (was: the loss notice)")
        _ = assertEquals(
          ledger,
          Nil,
          "the re-perception ledger leg is retired together with the notify legs"
        )
        _ = assert(
          row.lastError.exists(_.contains(SubAgentStartupRecovery.RestartLossFingerprint)),
          s"the row keeps the restart-loss fingerprint: ${row.lastError}"
        )
        _ = assert(
          row.lastError.exists(_.contains("parent NOT notified")),
          s"the row states the zero-notify ruling honestly (D1 文案与事实同源): ${row.lastError}"
        )
        _ = assert(
          !row.lastError.exists(_.contains("delivered to parent")),
          s"the row must NOT claim a delivery that no longer happens: ${row.lastError}"
        )
      yield ()
    }
  }

end InterruptRecoveryClosureSpec
