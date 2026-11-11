package nebflow.core.delegate

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import munit.CatsEffectSuite
import nebflow.agent.SharedResources
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.*

import scala.concurrent.duration.*

/**
 * 错误冻结批（2026-11-01 作者令逐字「所有因错误状态的 delegate 应该上报之后 要能
 * 保留状态 冻结态 方便被唤醒 接着做」）——**冻结态核心语义**判红锚。
 *
 * 本件钉死五组判据（全在 [[DelegateFreeze]] 的纯函数/单写面）：
 *  1. **原因词表**：四类错误各一例的机器判读（`classify`）+ 线值 round-trip
 *     （`wireValue`/`parse`/`isFreezeWire`）——冻结原因必须**写进状态**且跨重启可读，
 *     与其它冻结族（`schedule` / `loop` / 错误族退避）零误判；
 *  2. **判词诚实**：冻结回执正文（`receiptText`）四段齐备（谁冻了/为什么/保留了什么/
 *     怎么唤醒）且逐字声明「这不是失败判词」+ 给唤醒地址；唤醒横幅（`wakeBanner`）
 *     逐字声明「历史在场、已完成的不重做」；
 *  3. **冻结落盘**：`park` 写 registry 冻结槽（frozenSince/frozenReason/frozenDetail）
 *     与台账行 `status=frozen`（**不进** boot 收殓视野的状态值）；
 *  4. **唤醒收口**：`onWoken` 清三槽 + registry 状态回 Idle + 台账行回 `running`
 *     （唤醒 = 新执行窗口，行必须诚实地在飞）；
 *  5. **兜底**：`staleDormant` 三阶段（Active/Dormant/Expired）纯函数判读——长期无人
 *     唤醒必须被提示/归档，不许无声堆积。
 */
class DelegateFreezeSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 60.seconds

  private val originalRoot = PathUtil.dataRoot
  private var tmp: os.Path = os.pwd / "target" / "test-delegate-freeze"

  override def beforeAll(): Unit =
    tmp = os.temp.dir(prefix = "nb-freeze-")
    PathUtil.setDataRoot(tmp / "data")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(tmp)
    catch case _: Throwable => ()

  // ── fixtures ───────────────────────────────────────────────────────────

  private val throwLlm = new LlmHandle[IO]:
    def send(req: LlmRequest): IO[LlmResponse] = IO.raiseError(new RuntimeException("not expected"))
    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ) = fs2.Stream.raiseError[IO](new RuntimeException("not expected"))

  private def mkResources(name: String): IO[SharedResources] =
    val system = nebflow.actor.ActorSystem(s"freeze-$name-${java.util.UUID.randomUUID().toString.take(6)}")
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- Ref.of[IO, ThinkingConfig](ThinkingConfig())
      modelOverrides <- Ref.of[IO, Map[String, ModelCandidate]](Map.empty)
      voiceMuted <- Ref.of[IO, Boolean](false)
    yield SharedResources(
      llm = throwLlm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
      projectRoot = os.pwd,
      thinkingConfigRef = thinkingRef,
      rateLimiter = rateLimiter,
      fileChangeTracker = tracker,
      contextWindow = 100_000,
      agentLibrary = new nebflow.agent.AgentLibrary(tmp / "agents"),
      taskStore = FileTaskStore,
      historyArchiver = HistoryArchiver.fileSystem(tmp / "archives"),
      fileLockManager = fileLocks,
      sessionModelOverrides = modelOverrides,
      providerRegistry = null,
      healthMonitor = ProviderHealthMonitor(null),
      actorSystem = system,
      subAgentTaskStore = new nebflow.agent.SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted
    )

  private def mkRecord(id: String, parent: String, cwd: String) =
    DelegateRegistry.Record(
      id = id,
      executor = "nebflow",
      cwd = cwd,
      project = None,
      taskId = None,
      parentSessionId = parent,
      title = s"freeze record $id",
      createdAt = System.currentTimeMillis()
    )

  private def mkTask(id: String, parent: String): SubAgentTask =
    SubAgentTask(
      taskId = id,
      parentSessionId = parent,
      agentName = "nebflow",
      prompt = "do the thing",
      description = s"freeze-$id",
      status = "running",
      retryCount = 0,
      spawnedAt = System.currentTimeMillis(),
      completedAt = None,
      lastError = None,
      source = "delegate"
    )

  // ── ① 原因词表：四类错误各一例 ─────────────────────────────────────────

  test("classify: one case per frozen reason (llm / tool / engine / quota) — the reason is machine-readable, never a fuzzy judgement") {
    assertEquals(
      DelegateFreeze.classify("LlmFailed", "provider anthropic/model claude-x 529 overloaded, all candidates exhausted"),
      DelegateFreeze.Reason.LlmFailure
    )
    assertEquals(
      DelegateFreeze.classify("ToolFailed", "tool execution crashed: BashTool pipeline abort"),
      DelegateFreeze.Reason.ToolFailure
    )
    assertEquals(
      DelegateFreeze.classify("Timeout", "turn exceeded the wall clock without progress"),
      DelegateFreeze.Reason.EngineFault
    )
    assertEquals(
      DelegateFreeze.classify("LlmFailed", "insufficient_quota: you exceeded your current quota"),
      DelegateFreeze.Reason.CancelledByQuota
    )
    // 工具管道标记但错误类型是 Unknown ⇒ 仍判工具层（消息面兜底判据）。
    assertEquals(
      DelegateFreeze.classify("Unknown", "ToolPipelineError: broken pipe"),
      DelegateFreeze.Reason.ToolFailure
    )
  }

  test("wireValue/parse/isFreezeWire: the four reasons round-trip; foreign freeze families are never misread") {
    DelegateFreeze.Reason.values.foreach { r =>
      val v = DelegateFreeze.wireValue(r)
      assert(v.startsWith(DelegateFreeze.WirePrefix), s"wire value carries the batch prefix: $v")
      assertEquals(DelegateFreeze.parse(v), Some(r), s"round-trip for $r")
      assert(DelegateFreeze.isFreezeWire(v))
    }
    // 其它冻结族（时间表 / loop / 错误族退避）零误判。
    List("schedule", "loop", "llm-transient", "network", "").foreach { foreign =>
      assertEquals(DelegateFreeze.parse(foreign), None, s"'$foreign' is not a delegate-error freeze")
      assert(!DelegateFreeze.isFreezeWire(foreign))
    }
    assertEquals(DelegateFreeze.parse("delegate-frozen:nonsense"), None)
  }

  test("reasonForTrigger: supervisor-internal triggers map onto engine-fault / quota (unknown ⇒ None, classify decides)") {
    assertEquals(DelegateFreeze.reasonForTrigger("supervision-exhausted"), Some(DelegateFreeze.Reason.EngineFault))
    assertEquals(DelegateFreeze.reasonForTrigger("respawn-failed"), Some(DelegateFreeze.Reason.EngineFault))
    assertEquals(DelegateFreeze.reasonForTrigger("cancelled-by-quota"), Some(DelegateFreeze.Reason.CancelledByQuota))
    assertEquals(DelegateFreeze.reasonForTrigger("completed"), None)
  }

  // ── ② 判词诚实 + 唤醒文案 ──────────────────────────────────────────────

  test("compactDetail: single line, controls stripped, bounded") {
    val d = DelegateFreeze.compactDetail("a\nb\tc\r\nd", max = 400)
    assertEquals(d, "a")
    assertEquals(
      DelegateFreeze.detailedWireValue(DelegateFreeze.Reason.EngineFault, "errorType=Timeout; turn stalled"),
      "delegate-frozen:engine-fault[errorType=Timeout; turn stalled]"
    )
    assertEquals(
      DelegateFreeze.parse("delegate-frozen:engine-fault[errorType=Timeout]"),
      Some(DelegateFreeze.Reason.EngineFault)
    )
    val long = DelegateFreeze.compactDetail("x" * 500, max = 20)
    assertEquals(long.length, 21) // 20 + ellipsis
    assert(long.endsWith("…"))
  }

  test("receiptText: four sections, honestly 'NOT a failure verdict', keeps-what list, and the wake address") {
    val txt = DelegateFreeze.receiptText(
      delegateId = "delegate-freeze-r1",
      description = "parrot bicycle loop",
      reason = DelegateFreeze.Reason.LlmFailure,
      detail = "provider anthropic/claude-x: 529 overloaded",
      workspace = "/tmp/seat"
    )
    List("WHO:", "WHY:", "WHAT IS KEPT", "HOW TO WAKE IT").foreach(sec =>
      assert(txt.contains(sec), s"receipt must carry the '$sec' section")
    )
    assert(txt.contains("NOT a failure verdict"), "the verdict must be honest: freezing is not failing")
    assert(txt.contains("delegate:delegate-freeze-r1"), "the wake address must be printed verbatim")
    assert(txt.contains("529 overloaded"), "the upstream error context must survive into the receipt")
    assert(txt.contains("/tmp/seat"), "the kept workspace must be named")
    assert(txt.contains("冻结"), "the zh summary line is present")
  }

  test("wakeBanner: names the freeze point + reason and forbids redoing finished work") {
    val b = DelegateFreeze.wakeBanner(
      "delegate-freeze-r2",
      DelegateFreeze.wireValue(DelegateFreeze.Reason.EngineFault),
      "supervisor respawn failed mid-restart"
    )
    assert(b.contains("FROZEN"), "banner states the freeze point")
    assert(b.contains("engine fault"), "banner names the reason in en")
    assert(b.contains("引擎侧故障"), "banner names the reason in zh")
    assert(b.contains("do NOT redo"), "banner forbids redoing finished work")
    assert(b.contains("已完成的不重做"), "zh same rule")
  }

  // ── ③ 冻结落盘 ─────────────────────────────────────────────────────────

  test("park: writes the registry frozen slot + the task row status=frozen (a status the boot salvage sweep never sees)") {
    mkResources("park").flatMap { res =>
      val parent = "freeze-parent-park"
      val id = "delegate-freeze-park"
      for
        _ <- res.sessionStore.getOrCreateSession(parent, "freeze spec parent", agentName = Some("Nebula"))
        _ <- res.subAgentTaskStore.recordTask(mkTask(id, parent))
        _ <- IO(DelegateRegistry.put(mkRecord(id, parent, tmp.toString)))
        ts <- DelegateFreeze.park(res, id, parent, DelegateFreeze.Reason.LlmFailure, "provider 401 unauthorized")
        _ = assert(ts.isDefined, "park returns the freeze timestamp for a real record")
        rec <- IO(DelegateRegistry.find(id).get)
        _ = assertEquals(rec.frozenSince, ts)
        _ = assertEquals(rec.frozenReason, Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure)))
        _ = assert(rec.frozenDetail.exists(_.contains("401")), "the last error context is persisted")
        _ = assert(DelegateRegistry.isFrozen(id))
        _ = assertEquals(
          DelegateRegistry.frozenOf(id).map(_._2),
          Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.LlmFailure))
        )
        row <- res.subAgentTaskStore.findByTaskId(id)
        _ = assertEquals(row.map(_.status), Some(DelegateFreeze.TaskStatus))
        _ = assertEquals(DelegateFreeze.TaskStatus, "frozen")
      yield ()
    }
  }

  test("park on an unknown record: fail-soft (no crash, no timestamp) — only real spawns freeze") {
    mkResources("park-missing").flatMap { res =>
      DelegateFreeze.park(res, "delegate-freeze-absent", "p", DelegateFreeze.Reason.EngineFault, "x").map { ts =>
        assertEquals(ts, None)
        assertEquals(DelegateRegistry.find("delegate-freeze-absent"), None)
      }
    }
  }

  // ── ④ 唤醒收口 ─────────────────────────────────────────────────────────

  test("onWoken: clears the frozen slot, resets the registry status to Idle and the task row to running (wake = a new execution window)") {
    mkResources("wake").flatMap { res =>
      val parent = "freeze-parent-wake"
      val id = "delegate-freeze-wake"
      for
        _ <- res.sessionStore.getOrCreateSession(parent, "freeze spec parent", agentName = Some("Nebula"))
        _ <- res.subAgentTaskStore.recordTask(mkTask(id, parent))
        _ <- IO(DelegateRegistry.put(mkRecord(id, parent, tmp.toString)))
        // 活体记录在册（registry 状态归位只写既有条目——无条目 = 无处可写，fail-soft）。
        ref <- res.actorSystem.spawn(
          nebflow.actor.Behaviors.receiveMessage[nebflow.actor.AgentCommand](_ =>
            cats.effect.IO.pure(nebflow.actor.Behaviors.stopped)
          ),
          s"freeze-wake-${java.util.UUID.randomUUID().toString.take(6)}"
        )
        _ <- res.agentRegistry.update { m =>
          m + (id -> nebflow.actor.AgentRecord(
            sessionId = id,
            ref = ref,
            kind = nebflow.actor.AgentKind.Delegate,
            rootSessionId = parent
          ))
        }
        _ <- DelegateFreeze.park(res, id, parent, DelegateFreeze.Reason.ToolFailure, "tool pipeline died")
        _ <- DelegateFreeze.markRegistryState(res, id, DelegateFreeze.Reason.ToolFailure)
        frozenRec <- res.agentRegistry.get
        _ = assertEquals(
          frozenRec.get(id).map(_.status),
          Some(nebflow.actor.AgentStatus.Frozen)
        )
        _ = assertEquals(
          frozenRec.get(id).flatMap(_.frozenReason),
          Some(DelegateFreeze.wireValue(DelegateFreeze.Reason.ToolFailure))
        )
        _ <- DelegateFreeze.onWoken(res, id, "spec")
        wokeReg <- res.agentRegistry.get
        _ = assertEquals(wokeReg.get(id).map(_.status), Some(nebflow.actor.AgentStatus.Idle), "registry status back to Idle")
        _ = assertEquals(wokeReg.get(id).flatMap(_.frozenReason), None, "registry frozen slot cleared")
        rec <- IO(DelegateRegistry.find(id).get)
        _ = assertEquals(rec.frozenSince, None, "frozenSince cleared")
        _ = assertEquals(rec.frozenReason, None, "frozenReason cleared")
        _ = assertEquals(rec.frozenDetail, None, "frozenDetail cleared")
        row <- res.subAgentTaskStore.findByTaskId(id)
        _ = assertEquals(row.map(_.status), Some("running"), "the ledger row is honestly in-flight again")
      yield ()
    }
  }

  test("onWoken leaves foreign freeze families alone (a schedule freeze is not this batch's to thaw)") {
    mkResources("wake-foreign").flatMap { res =>
      val id = "delegate-freeze-sched"
      val parent = "freeze-parent-sched"
      for
        _ <- res.sessionStore.getOrCreateSession(parent, "freeze spec parent", agentName = Some("Nebula"))
        _ <- res.subAgentTaskStore.recordTask(mkTask(id, parent))
        _ <- IO(DelegateRegistry.put(mkRecord(id, parent, tmp.toString)))
        ref <- res.actorSystem
          .spawn(
            nebflow.actor.Behaviors.receiveMessage[nebflow.actor.AgentCommand](_ =>
              cats.effect.IO.pure(nebflow.actor.Behaviors.stopped)
            ),
            s"sched-freeze-${java.util.UUID.randomUUID().toString.take(6)}"
          )
        _ <- res.agentRegistry.update { m =>
          m + (id -> nebflow.actor.AgentRecord(
            sessionId = id,
            ref = ref,
            kind = nebflow.actor.AgentKind.Delegate,
            rootSessionId = parent,
            status = nebflow.actor.AgentStatus.Frozen,
            frozenReason = Some("schedule")
          ))
        }
        _ <- DelegateFreeze.onWoken(res, id, "spec")
        reg <- res.agentRegistry.get
        _ = assertEquals(reg.get(id).map(_.status), Some(nebflow.actor.AgentStatus.Frozen), "schedule freeze untouched")
        _ = assertEquals(reg.get(id).flatMap(_.frozenReason), Some("schedule"))
      yield ()
    }
  }

  // ── ⑤ 兜底：长期无人唤醒 ───────────────────────────────────────────────

  test("staleDormant: Active → Dormant (panel prompt) → Expired (retention archive); open-ended retention keeps only the prompt") {
    val remind = 24L * 3600 * 1000
    val retain = 14L * 24 * 3600 * 1000
    val now = 1_000_000_000_000L
    assertEquals(DelegateFreeze.staleDormant(now, now, remind, retain), DelegateFreeze.Stage.Active)
    assertEquals(DelegateFreeze.staleDormant(now - remind + 1, now, remind, retain), DelegateFreeze.Stage.Active)
    assertEquals(DelegateFreeze.staleDormant(now - remind, now, remind, retain), DelegateFreeze.Stage.Dormant)
    assertEquals(DelegateFreeze.staleDormant(now - retain + 1, now, remind, retain), DelegateFreeze.Stage.Dormant)
    assertEquals(DelegateFreeze.staleDormant(now - retain, now, remind, retain), DelegateFreeze.Stage.Expired)
    // retention ≤ 0 = 归档关闭（只提示，永不静默删）。
    assertEquals(DelegateFreeze.staleDormant(now - 100 * retain, now, remind, 0L), DelegateFreeze.Stage.Dormant)
  }

  test("defaults: freeze gate on, 24h dormant prompt, 7d retention (= the unified terminal TTL single source)") {
    assert(Defaults.DelegateFreezeEnabled, "the freeze leg ships enabled")
    assertEquals(Defaults.DelegateFreezeDormantRemindMs, 24L * 3600 * 1000)
    // 终态 7 天 TTL 批（2026-11-06 · 作者令）：冻结保留期并入统一 TTL 单源
    // （`Defaults.DelegateTerminalTtlMs`）——保存态不能无限堆积，7 天一并清理。
    assertEquals(Defaults.DelegateTerminalTtlMs, 7L * 24 * 3600 * 1000)
    assertEquals(Defaults.DelegateFreezeRetentionDays, 7L)
    assertEquals(DelegateFreeze.retentionMs, 7L * 24 * 3600 * 1000)
  }
end DelegateFreezeSpec
