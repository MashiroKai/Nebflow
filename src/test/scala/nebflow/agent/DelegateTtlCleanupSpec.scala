package nebflow.agent

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.mail.SessionMailbox
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{Defaults, PathUtil, SubAgentTask, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * 死 delegate 清理机制：终态 7 天 TTL（本批 · 作者令 2026-11-06 22:39「我发现了
 * 非常久远的 delegate 任务也在里面。说明我们没有一个死 delegate 的一个清理机制。
 * 设置跟 work tree 一个 ttl，一周。」）。
 *
 * 三条**改前红**读数（旧现状 = 超期死亡行永远留在通知源视野里，重启收殓反复翻出）：
 *  - A 收殓零重播：超期重启丢失行（failed ∧ 指纹 ∧ completedAt 9d）不进复活/收殓
 *    视野——零复活、零收殓、零邮箱通知、行原样（旧现状会重走收殓链 = FAILED 再报）；
 *  - B 账面合成零重播：超期终态行落在邮箱 boot 对账窗内也不合成补投（旧现状合成
 *    FAILED 条目 = 重启再报）；
 *  - C 完成未报告收尾零重播：超期 completed 行不再被自动收尾腿投递（旧现状重投）。
 *
 * 改后绿（实现腿，Phase B）：TTL 边界三态、终态锚解析、在飞永不清理、
 * 保存态（standby/frozen）同属终态计时、清理 sweep（登记条目移除 + 任务行剪行 +
 * worktree 联动回收 + 在飞守卫 + git 真实例腿）。
 */
class DelegateTtlCleanupSpec extends CatsEffectSuite:

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

  private val Day = 24L * 3600L * 1000L
  private val NineDaysAgo = System.currentTimeMillis() - 9L * Day
  private val EightDaysHalfAgo = System.currentTimeMillis() - 8L * Day - Day / 2

  /** 应答固定文本的 stub LLM（复活腿不会走到它——本 spec 的行全部超期）。 */
  private val echoLlm = new nebflow.shared.LlmHandle[IO]:
    def send(req: nebflow.shared.LlmRequest): IO[nebflow.shared.LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected"))
    def sendStream(
      req: nebflow.shared.LlmRequest,
      onAttempt: Option[nebflow.shared.FallbackAttempt => IO[Unit]] = None
    ) =
      fs2.Stream.raiseError[IO](new RuntimeException("send not expected"))

  private def mkTask(
    id: String,
    parent: String,
    status: String,
    completedAt: Long,
    lastError: Option[String] = None,
    revivalCount: Option[Int] = None
  ): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "do the thing",
      description = s"ttl-$id",
      status = status,
      retryCount = 0,
      spawnedAt = NineDaysAgo - 60_000L,
      completedAt = Some(completedAt),
      lastError = lastError,
      source = "delegate",
      statusAt = Some(completedAt),
      revivalCount = revivalCount
    )

  private def mkRecord(id: String, parent: String, cwd: String) =
    nebflow.core.delegate.DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = cwd,
      project = None,
      taskId = None,
      parentSessionId = parent,
      title = s"ttl record $id",
      createdAt = NineDaysAgo - 120_000L
    )

  private final case class Fx(
    store: SubAgentTaskStore,
    sessionStore: SessionStore,
    resources: SharedResources,
    system: nebflow.actor.ActorSystem,
    tmp: os.Path,
    seat: os.Path
  )

  private def withFx(name: String, verifiedLegacy: Boolean = false)(body: Fx => IO[Unit]): Unit =
    val tmp = os.temp.dir(prefix = s"ttl-$name")
    val seat = os.temp.dir(prefix = s"ttl-seat-$name")
    PathUtil.setDataRoot(tmp / "data")
    val system = nebflow.actor.ActorSystem(s"ttl-$name-${java.util.UUID.randomUUID().toString.take(6)}")
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
        _ <- if verifiedLegacy then
          IO.blocking(os.makeDir.all(tmp / "sessions")) *>
            ExecutionCommitStore.resource((tmp / "sessions").toNIO).use { authority =>
              body(Fx(store, sessionStore, resources.copy(executionCommitPort = authority), system, tmp, seat))
            }
        else body(Fx(store, sessionStore, resources, system, tmp, seat))
      yield ()
    try io.unsafeRunSync()
    finally
      system.stopAll.attempt.void.unsafeRunSync()
      PathUtil.setDataRoot(originalRoot)
      os.remove.all(tmp)
      os.remove.all(seat)

  end withFx

  private def sweepWith(fx: Fx) =
    SubAgentStartupRecovery.sweep(
      fx.store,
      fx.sessionStore,
      Some(SubAgentStartupRecovery.RevivalDeps(fx.resources, fx.system, None))
    )

  /** 邮箱读面（delegate 轨唯一投递面）：pending ∪ taken 的事件流。 */
  private def mailboxEvents(parentId: String): IO[List[nebflow.actor.AgentCommand.ExternalEvent]] =
    for
      pending <- SessionMailbox.scanPending(parentId)
      taken <- SessionMailbox.scanTaken(parentId)
    yield (pending ++ taken).map(_.event)

  // ── 改前红 A：收殓零重播 ──────────────────────────────────────────────

  test(
    "A · 收殓零重播: an ancient restart-lost row (failed ∧ fingerprint ∧ 9d old) never re-enters the revival/salvage视野 — zero revival, zero salvage, zero mailbox notice, row untouched"
  ) {
    withFx("ancient-restart-lost") { fx =>
      val parent = "ttl-parent-a"
      val id = "delegate-nebflow-ttl-a"
      for
        _ <- fx.sessionStore.getOrCreateSession(parent, "ttl spec parent", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(
          mkTask(
            id,
            parent,
            status = "failed",
            completedAt = NineDaysAgo,
            lastError = Some(s"${SubAgentStartupRecovery.RestartLossFingerprint}: killed by the previous boot"),
            revivalCount = Some(Defaults.DelegateRevivalMaxPerTask)
          )
        )
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(id, parent, fx.seat.toString)))
        report <- sweepWith(fx)
        _ = assertEquals(report.revived, Nil, s"ancient row must not revive: ${report.summary}")
        _ = assertEquals(
          report.terminalized.filter(_ == id),
          Nil,
          s"ancient row must not be re-salvaged: ${report.summary}"
        )
        events <- mailboxEvents(parent)
        _ = assertEquals(events, Nil, "ancient row must not produce any mailbox notification")
        row <- fx.store.findByTaskId(id).map(_.get)
        _ = assertEquals(row.status, "failed", "the ancient row keeps its honest terminal state")
        _ = assertEquals(row.completedAt, Some(NineDaysAgo), "the ancient row's terminal timestamp is untouched")
      yield ()
      end for
    }
  }

  // ── 改前红 B：账面合成零重播 ──────────────────────────────────────────

  test(
    "B · 合成零重播: an ancient terminal row falling inside a long-downtime reconcile window is never synthesized into the parent mailbox"
  ) {
    withFx("ancient-mbox-synth") { fx =>
      val parent = "ttl-parent-b"
      val id = "delegate-nebflow-ttl-b"
      for
        _ <- fx.sessionStore.getOrCreateSession(parent, "ttl spec parent", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(
          mkTask(id, parent, status = "failed", completedAt = EightDaysHalfAgo, lastError = Some("boom long ago"))
        )
        // 手工落 boot 纪元文件：上一 boot = 9 天前 ⇒ 本 boot 对账窗 = (9d ago, now]，
        // completedAt 8.5 天前的行落在窗内**且**已超 7 天 TTL。
        epochPath = fx.tmp / "data" / "mailbox-reconcile.json"
        _ <- IO.blocking {
          os.makeDir.all(fx.tmp / "data")
          nebflow.core.AtomicJson.writeSync(
            epochPath,
            io.circe.Json
              .obj(
                "v" -> 1.asJson,
                "lastBootMs" -> 0L.asJson,
                "thisBootMs" -> (System.currentTimeMillis() - 9L * Day).asJson
              )
              .noSpaces
          )
        }
        report <- MailboxBootSweep.run(fx.store, fx.sessionStore)
        _ = assertEquals(report.synthesized, Nil, s"ancient row must not be synthesized: ${report.summary}")
        events <- mailboxEvents(parent)
        _ = assertEquals(events, Nil, "ancient row must not reach the parent mailbox")
      yield ()
      end for
    }
  }

  // ── 改前红 C：完成未报告收尾零重播 ────────────────────────────────────

  test("C · 收尾零重播: an ancient completed-but-unreported row is never auto-closed/delivered by the boot sweep") {
    withFx("ancient-finalize") { fx =>
      val parent = "ttl-parent-c"
      val id = "delegate-nebflow-ttl-c"
      for
        _ <- fx.sessionStore.getOrCreateSession(parent, "ttl spec parent", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(id, parent, status = "completed", completedAt = NineDaysAgo))
        _ <- IO {
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(id, parent, fx.seat.toString).copy(
              workReport = Some("ancient work result — must not be re-delivered"),
              reportStatus = None
            )
          )
        }
        report <- sweepWith(fx)
        _ = assertEquals(
          report.terminalized.filter(_ == id),
          Nil,
          s"ancient completed row must not be re-delivered: ${report.summary}"
        )
        events <- mailboxEvents(parent)
        _ = assertEquals(events, Nil, "ancient completion must not reach the parent mailbox")
        rec <- IO(nebflow.core.delegate.DelegateRegistry.find(id).get)
        _ = assertEquals(rec.reportStatus, None, "the record stays un-delivered (silence, not convergence)")
      yield ()
      end for
    }
  }

  // ── 改后绿 D：TTL 边界三态（机械边界，唯一比较点在 DelegateTerminalTtl.ageVerdict）──

  test(
    "D · 边界三态: 6d23h59m kept, exactly 7d expired, 7d+1m expired — and a 100d-old RUNNING row is NEVER expired (in-flight red line)"
  ) {
    val now = 1_000_000_000_000L
    import nebflow.core.delegate.DelegateTerminalTtl
    // 6天23h59m → WithinTtl
    assert(!DelegateTerminalTtl.ageVerdict(now - 7L * Day + 60_000L, "task-row", now).isExpired)
    // 7天整 → Expired（≥ TTL 到期）
    assert(DelegateTerminalTtl.ageVerdict(now - 7L * Day, "task-row", now).isExpired)
    // 7天+1m → Expired
    assert(DelegateTerminalTtl.ageVerdict(now - 7L * Day - 60_000L, "task-row", now).isExpired)
    // 在飞红线：running 100 天也不清（行级判读只对终态行给锚）
    val ancientRunning = mkTask("delegate-nebflow-ttl-d", "p", status = "running", completedAt = now - 100L * Day)
    assertEquals(DelegateTerminalTtl.rowVerdict(ancientRunning, now), DelegateTerminalTtl.InFlight)
    // ready-to-merge / restarting 同票（合并机/supervisor 在管）
    assertEquals(
      DelegateTerminalTtl.rowVerdict(mkTask("t2", "p", status = "ready-to-merge", completedAt = now - 100L * Day), now),
      DelegateTerminalTtl.InFlight
    )
    assertEquals(
      DelegateTerminalTtl.rowVerdict(mkTask("t3", "p", status = "restarting", completedAt = now - 100L * Day), now),
      DelegateTerminalTtl.InFlight
    )
    // ── 死行收口批（2026-11-21）：pending-recovery 是**终态**，不再永久在飞 ──
    // 三支既有修复（fix/agent-panel-stale / -v2 / feat/agent-panel-active-bit）只在呈现面
    // 补了这个档位（标「重启中断 · 待恢复」、摘运行时钟），收口面从没认它 ⇒ 行永久在飞。
    // 执行已停（写入时的原话 "Execution interrupted; manual recovery required"）⇒ 同享
    // 7 天 TTL，超期即清，不能是无限期待恢复。
    assertEquals(
      DelegateTerminalTtl.rowVerdict(
        mkTask("t4", "p", status = "pending-recovery", completedAt = now - 100L * Day), now).isExpired,
      true,
      "待恢复 100 天 ⇒ 到期（旧口径这里是 InFlight，一行永久挂着）"
    )
    assertEquals(
      DelegateTerminalTtl.rowVerdict(
        mkTask("t5", "p", status = "pending-recovery", completedAt = now - 60_000L), now).isExpired,
      false,
      "待恢复 1 分钟 ⇒ 未到期（刚重启中断的行要留给人决定）"
    )
  }

  // ── 改后绿 E：终态锚解析（保存态 > 任务行 > 登记兜底）─────────────────

  test(
    "E · 锚解析: saved-state anchors (frozen/standby) win over the task row; owner-gone falls back to cancel marker then createdAt"
  ) {
    val now = 1_000_000_000_000L
    import nebflow.core.delegate.{DelegateRegistry, DelegateTerminalTtl}
    val task = mkTask("delegate-nebflow-ttl-e", "p", status = "failed", completedAt = now - 1L * Day) // 新终态
    // 保存态锚胜过新任务行：frozenSince 8d 前 ⇒ Expired（frozen 锚，与 1d 前的 completedAt 无关）
    val frozenRec = mkRecord("delegate-nebflow-ttl-e", "p", "/tmp/seat").copy(frozenSince = Some(now - 8L * Day))
    DelegateTerminalTtl.verdict(frozenRec, Some(task), now) match
      case DelegateTerminalTtl.Expired(ms, name) =>
        assertEquals(name, "frozen")
        assertEquals(ms, now - 8L * Day)
      case other => fail(s"expected Expired(frozen), got $other")
    // standby 6天23h59m → WithinTtl（保存态同属终态计时，未满不清）
    val standbyRec = mkRecord("delegate-nebflow-ttl-e", "p", "/tmp/seat").copy(
      standbySince = Some(now - 7L * Day + 60_000L),
      reportStatus = Some("standby")
    )
    DelegateTerminalTtl.verdict(standbyRec, Some(task), now) match
      case DelegateTerminalTtl.WithinTtl(_, name) => assertEquals(name, "standby")
      case other => fail(s"expected WithinTtl(standby), got $other")
    // 无保存态 → 任务行锚（completedAt 1d 前 = WithinTtl）
    DelegateTerminalTtl.verdict(mkRecord("delegate-nebflow-ttl-e", "p", "/tmp/seat"), Some(task), now) match
      case DelegateTerminalTtl.WithinTtl(_, name) => assertEquals(name, "task-row")
      case other => fail(s"expected WithinTtl(task-row), got $other")
    // owner-gone（无任务行）→ 取消墓碑兜底
    val cancelled = mkRecord("delegate-nebflow-ttl-e", "p", "/tmp/seat").copy(cancelRequestedAt = Some(now - 8L * Day))
    DelegateTerminalTtl.verdict(cancelled, None, now) match
      case DelegateTerminalTtl.Expired(_, name) => assertEquals(name, "registry-cancel-marker")
      case other => fail(s"expected Expired(registry-cancel-marker), got $other")
    // owner-gone 无任何旗 → 登记时间兜底（createdAt 9d 前）
    val noMarker = mkRecord("delegate-nebflow-ttl-e", "p", "/tmp/seat").copy(createdAt = now - 9L * Day)
    DelegateTerminalTtl.verdict(noMarker, None, now) match
      case DelegateTerminalTtl.Expired(_, name) => assertEquals(name, "registry-created")
      case other => fail(s"expected Expired(registry-created), got $other")
  }

  // ── 改后绿 F：清理 sweep（登记移除 + 任务行剪行 + 在飞守卫 + 未满不动）──

  test(
    "F · 清理面: expired dead entries are cleaned (row pruned + record removed), fresh ones and in-flight sessions are never touched"
  ) {
    withFx("sweep-e2e", verifiedLegacy = true) { fx =>
      val parent = "ttl-parent-f"
      val deadId = "delegate-nebflow-ttl-dead"
      val freshId = "delegate-nebflow-ttl-fresh"
      val liveId = "delegate-nebflow-ttl-live"
      for
        _ <- fx.sessionStore.getOrCreateSession(parent, "ttl spec parent", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(
          mkTask(deadId, parent, status = "failed", completedAt = NineDaysAgo, lastError = Some("old boom"))
        )
        // 未满 TTL（2 天前终态）——保留腿对照。
        _ <- fx.store.recordTask(
          mkTask(freshId, parent, status = "failed", completedAt = System.currentTimeMillis() - 2L * Day)
        )
        _ <- fx.store.recordTask(mkTask(liveId, parent, status = "running", completedAt = NineDaysAgo))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(deadId, parent, fx.seat.toString)))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(freshId, parent, fx.seat.toString)))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(liveId, parent, fx.seat.toString)))
        // 在飞守卫：活体 actor 在册（红线——在飞绝不清理）。
        ref <- fx.system.spawn(
          nebflow.actor.Behaviors.receiveMessage[nebflow.actor.AgentCommand](_ =>
            cats.effect.IO.pure(nebflow.actor.Behaviors.stopped)
          ),
          s"ttl-live-${java.util.UUID.randomUUID().toString.take(6)}"
        )
        _ <- fx.resources.agentRegistry.update { m =>
          m + (liveId -> nebflow.actor.AgentRecord(
            sessionId = liveId,
            ref = ref,
            kind = nebflow.actor.AgentKind.Delegate,
            rootSessionId = parent
          ))
        }
        report <- DelegateTtlSweep.run(fx.resources)
        _ = assertEquals(report.expired, List(deadId), s"only the expired dead entry is cleaned: ${report.summary}")
        _ = assertEquals(report.taskRowsPruned, List(deadId), "the dead task row is pruned")
        _ = assertEquals(report.recordsRemoved, List(deadId), "the dead registry entry is removed")
        _ = assertEquals(report.inFlightKept, List(liveId), "the live session is guarded (zero touch)")
        _ <- fx.store.findByTaskId(deadId).map(opt => assertEquals(opt, None, "dead row pruned from the ledger"))
        freshRow <- fx.store.findByTaskId(freshId).map(_.get)
        _ = assertEquals(freshRow.status, "failed", "fresh (within-TTL) row untouched")
        freshRec <- IO(nebflow.core.delegate.DelegateRegistry.find(freshId).map(_.id))
        _ = assertEquals(freshRec, Some(freshId), "fresh (within-TTL) record untouched")
        liveRow <- fx.store.findByTaskId(liveId).map(_.get)
        _ = assertEquals(liveRow.status, "running", "in-flight row untouched (red line)")
        liveRec <- IO(nebflow.core.delegate.DelegateRegistry.find(liveId).map(_.id))
        _ = assertEquals(liveRec, Some(liveId), "in-flight record untouched (red line)")
      yield ()
      end for
    }
  }

  test("session cascade refuses an offline protected child without changing its task") {
    withFx("protected-cascade") { fx =>
      val id = "delegate-protected-cascade"
      val parent = "cascade-parent"
      val admission = nebflow.shared.ExecutionAdmission(
        id, "run", "commit", "request", "attempt", "incarnation", "owner", "business", "audit",
        io.circe.Json.fromString("initial"), "input"
      )
      for
        _ <- IO.blocking(os.makeDir.all(fx.tmp / "sessions"))
        _ <- fx.store.recordTask(mkTask(id, parent, "running", NineDaysAgo))
        _ <- ExecutionCommitStore.resource(fx.sessionStore.executionAuthorityRoot).use(_.admit(admission))
        stopped <- ExecutionCommitStore.resource(fx.sessionStore.executionAuthorityRoot).use { authority =>
          SessionChildCascade.stopChildDelegateActors(fx.resources.copy(executionCommitPort = authority), parent).attempt
        }
        row <- fx.store.findByTaskId(id)
      yield
        assert(stopped.isLeft)
        assertEquals(row.map(_.status), Some("running"))
    }
  }

  test("cascade refuses a discoverable admission without a task or registry projection") {
    withFx("orphan-candidate-cascade") { fx =>
      val id = "delegate-orphan-candidate"
      val admission = nebflow.shared.ExecutionAdmission(
        id, "run", "commit", "request", "attempt", "incarnation", "parent", "business", "audit",
        io.circe.Json.fromString("initial"), "input"
      )
      for
        _ <- IO.blocking(os.makeDir.all(fx.tmp / "sessions"))
        _ <- ExecutionCommitStore.resource(fx.sessionStore.executionAuthorityRoot).use(_.admit(admission))
        stopped <- ExecutionCommitStore.resource(fx.sessionStore.executionAuthorityRoot).use { authority =>
          SessionChildCascade.stopChildDelegateActors(fx.resources.copy(executionCommitPort = authority), "parent").attempt
        }
        row <- fx.store.findByTaskId(id)
      yield
        assert(stopped.isLeft)
        assertEquals(row, None)
    }
  }

  test("cascade does not reject an unrelated protected candidate") {
    withFx("unrelated-candidate-cascade") { fx =>
      val admission = nebflow.shared.ExecutionAdmission(
        "delegate-unrelated", "run", "commit", "request", "attempt", "incarnation", "other-parent", "business", "audit",
        io.circe.Json.fromString("initial"), "input"
      )
      for
        _ <- IO.blocking(os.makeDir.all(fx.tmp / "sessions"))
        _ <- ExecutionCommitStore.resource(fx.sessionStore.executionAuthorityRoot).use(_.admit(admission))
        stopped <- ExecutionCommitStore.resource(fx.sessionStore.executionAuthorityRoot).use { authority =>
          SessionChildCascade.stopChildDelegateActors(fx.resources.copy(executionCommitPort = authority), "parent").attempt
        }
      yield assertEquals(stopped, Right(Nil))
    }
  }

  test("TTL keeps an expired protected run when recovery carrier is off") {
    withFx("protected") { fx =>
      val id = "delegate-ttl-protected"
      val parent = "ttl-parent-protected"
      val admission = nebflow.shared.ExecutionAdmission(
        id, "run", "commit", "request", "attempt", "incarnation", "owner", "business", "audit",
        io.circe.Json.fromString("initial"), "input"
      )
      for
        _ <- IO.blocking(os.makeDir.all(fx.tmp / "sessions"))
        _ <- fx.store.recordTask(mkTask(id, parent, "failed", NineDaysAgo))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(id, parent, fx.seat.toString)))
        _ <- ExecutionCommitStore.resource(fx.sessionStore.executionAuthorityRoot).use(_.admit(admission))
        report <- ExecutionCommitStore.resource(fx.sessionStore.executionAuthorityRoot).use { authority =>
          DelegateTtlSweep.run(fx.resources.copy(executionCommitPort = authority, delegateRecoveryMode = Some("off")))
        }
        row <- fx.store.findByTaskId(id)
        record <- IO(nebflow.core.delegate.DelegateRegistry.find(id))
      yield
        assert(report.keptOnError.contains(id -> "protected-execution"))
        assert(row.nonEmpty)
        assert(record.nonEmpty)
    }
  }

  test("TTL rechecks protection at the serialized removal boundary") {
    withFx("admission-race", verifiedLegacy = true) { fx =>
      val id = "delegate-ttl-race"
      val parent = "ttl-race-parent"
      val admission = nebflow.shared.ExecutionAdmission(
        id, "run", "commit", "request", "attempt", "incarnation", "owner", "business", "audit",
        io.circe.Json.fromString("initial"), "input"
      )
      val authority = fx.resources.executionCommitPort
      for
        reached <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        _ <- fx.store.recordTask(mkTask(id, parent, "failed", NineDaysAgo))
        _ <- IO(nebflow.core.delegate.DelegateRegistry.put(mkRecord(id, parent, fx.seat.toString)))
        port = new nebflow.core.ExecutionCommitPort:
          def admit(a: nebflow.shared.ExecutionAdmission) = authority.admit(a)
          def observe(s: String, r: String) = authority.observe(s, r)
          def reconcileAdmission(a: nebflow.shared.ExecutionAdmission) = authority.reconcileAdmission(a)
          def transition(c: nebflow.shared.ExecutionTransition) = authority.transition(c)
          def reconcileTransition(c: nebflow.shared.ExecutionTransition) = authority.reconcileTransition(c)
          def observeChain(s: String, r: String) = authority.observeChain(s, r)
          override def protection(s: String) = authority.protection(s)
          override def withUnprotectedSession[A](s: String)(effect: IO[A]) =
            reached.complete(()).void *> release.get *> authority.withUnprotectedSession(s)(effect)
        sweep <- DelegateTtlSweep.run(fx.resources.copy(executionCommitPort = port)).start
        _ <- reached.get.timeout(5.seconds)
        admitted <- authority.admit(admission)
        _ = assert(admitted.isInstanceOf[nebflow.shared.ExecutionCommitResult.Committed])
        _ <- release.complete(())
        report <- sweep.joinWithNever.timeout(5.seconds)
        row <- fx.store.findByTaskId(id)
        record <- IO(nebflow.core.delegate.DelegateRegistry.find(id))
      yield
        assert(report.keptOnError.exists(_._1 == id))
        assert(row.nonEmpty)
        assert(record.nonEmpty)
    }
  }

  // ── 改后绿 G：保存态超期清理（作者 20:36 新规——保存态不能无限堆积）────

  test("G · 保存态清理: a frozen delegate parked 8d ago is swept at the record level; a fresh parked one is kept") {
    withFx("saved-state", verifiedLegacy = true) { fx =>
      val parent = "ttl-parent-g"
      val oldFrozenId = "delegate-nebflow-ttl-frozen-old"
      val freshStandbyId = "delegate-nebflow-ttl-standby"
      for
        _ <- fx.sessionStore.getOrCreateSession(parent, "ttl spec parent", agentName = Some("Nebula"))
        _ <- fx.store.recordTask(mkTask(oldFrozenId, parent, status = "frozen", completedAt = NineDaysAgo))
        _ <- fx.store.recordTask(mkTask(freshStandbyId, parent, status = "running", completedAt = NineDaysAgo))
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(oldFrozenId, parent, fx.seat.toString)
              .copy(frozenSince = Some(NineDaysAgo), frozenReason = Some("llm-failure"))
          )
        )
        _ <- IO(
          nebflow.core.delegate.DelegateRegistry.put(
            mkRecord(freshStandbyId, parent, fx.seat.toString).copy(
              // 未满 TTL（2 天前停靠）——保留腿对照（保存态同属终态计时）。
              standbySince = Some(System.currentTimeMillis() - 2L * Day),
              reportStatus = Some("standby")
            )
          )
        )
        report <- DelegateTtlSweep.run(fx.resources)
        _ = assertEquals(report.expired, List(oldFrozenId), s"expired saved-state cleaned: ${report.summary}")
        _ = assertEquals(
          report.taskRowsPruned,
          List(oldFrozenId),
          "the frozen row is pruned too (row status 'frozen' is dead, not in-flight)"
        )
        freshRec <- IO(nebflow.core.delegate.DelegateRegistry.find(freshStandbyId).map(_.id))
        _ = assertEquals(freshRec, Some(freshStandbyId), "fresh parked standby untouched (within TTL)")
      yield ()
      end for
    }
  }

  // ── 改后绿 H：worktree 联动回收（git 真实例腿；未领先提交绝不消失）─────

  private def gitRepo(prefix: String): os.Path =
    val repo = os.temp.dir(prefix = prefix)
    os.proc("git", "init", "-b", "main", repo).call(cwd = repo)
    os.proc("git", "-C", repo, "config", "user.email", "ttl@spec").call()
    os.proc("git", "-C", repo, "config", "user.name", "ttl-spec").call()
    os.write.over(repo / "README.md", "seed\n")
    os.proc("git", "-C", repo, "add", ".").call()
    os.proc("git", "-C", repo, "commit", "-m", "seed").call()
    repo

  // ── 改后绿 I：TTL 单源对齐 ────────────────────────────────────────────

  test(
    "I · 单源: DelegateTerminalTtl.TtlMs == Defaults.DelegateTerminalTtlMs == 7d; freeze retention derives from the same point"
  ) {
    import nebflow.core.delegate.DelegateTerminalTtl
    assertEquals(Defaults.DelegateTerminalTtlMs, 7L * 24 * 3600 * 1000)
    assertEquals(DelegateTerminalTtl.TtlMs, Defaults.DelegateTerminalTtlMs)
    assertEquals(nebflow.core.delegate.DelegateFreeze.retentionMs, Defaults.DelegateTerminalTtlMs)
    assertEquals(nebflow.core.delegate.DelegateFreeze.retentionMs, 7L * 24 * 3600 * 1000)
  }
end DelegateTtlCleanupSpec
