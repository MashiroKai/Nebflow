package nebflow.agent

import cats.effect.IO
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.actor.{AgentCommand, AgentDef, AgentKind, AgentRecord, AgentStatus, EscalationInfo}
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.core.FileChangeTracker
import nebflow.core.compact.HistoryArchiver
import nebflow.core.processor.FreezeScheduler
import nebflow.core.schedule.FreezeScheduleConfig
import nebflow.core.task.FileTaskStore
import nebflow.core.tools.FileLockManager
import nebflow.core.{RateLimiter, SessionStore}
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor}
import nebflow.shared.{FallbackAttempt, LlmHandle, LlmRequest, LlmResponse, PathUtil, StreamChunk, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * v2 反推送风暴批（2026-10-28 作者报障「什么东西一直重复推送」）四面验收。
 *
 * 根因实证（stand.log 2026-10-28 19:23-20:10）：provider-down 波及 root + 7 个
 * delegate 子会话，升级事件（source=team type=error-escalated）向父会话逐条重投
 * 21+ 次（L2→L5 各级反复），每次打断父会话一个完整回合；父已裁定 restart（生效）
 * 后事件仍不销账。缺陷面：①升级链无销账点（恢复/唤醒/重启/重派均不清
 * escalation）；②陈旧 Escalate 无丢弃闸 + 扫描器 30s 重发无节流；③父忙队列无
 * 同键合并；④总量无风暴闸。本 spec 钉死修复后的四个语义面。
 */
class EscalationNotifyStormSpec extends CatsEffectSuite:

  override def munitIOTimeout: FiniteDuration = 240.seconds

  // ── harness（仿 FreezeGateSpec ER 组：真实 AgentActor + 可控 mock LLM）──────

  /** 可控失败 mock：succeedRef=false 恒抛（provider down 场景），true 正常收尾。 */
  private class GatedFailLlm(counter: cats.effect.Ref[IO, Int], succeedRef: cats.effect.Ref[IO, Boolean])
      extends LlmHandle[IO]:

    def send(req: LlmRequest): IO[LlmResponse] =
      IO.raiseError(new RuntimeException("send not expected in this test"))

    def sendStream(
      req: LlmRequest,
      onAttempt: Option[FallbackAttempt => IO[Unit]] = None
    ): Stream[IO, StreamChunk] =
      Stream.eval(counter.update(_ + 1)) >> Stream.eval(succeedRef.get).flatMap {
        case true => Stream(StreamChunk.TextDelta("ok"), StreamChunk.Done(None, None))
        // 429（RateLimit → Transient + inline retry）：与 FreezeGateSpec ER 组同款
        // 节奏（失败1→retry→失败2→ErrorFrozen）。503/ServerError 不走 inline retry，
        // 会把「冻结前两次调用」的节奏改成单调用冻结，不可用。
        case false => Stream.raiseError[IO](new RuntimeException("HTTP 429 rate limit exceeded"))
      }

  end GatedFailLlm

  private def mkResources(
    system: ActorSystem,
    tmp: os.Path,
    llm: LlmHandle[IO]
  ): IO[SharedResources] =
    for
      dispatcher <- Dispatcher.parallel[IO].allocated.map(_._1)
      rateLimiter <- RateLimiter.create()
      tracker <- FileChangeTracker.create(os.pwd.toString)
      fileLocks <- FileLockManager.create
      thinkingRef <- IO.ref(ThinkingConfig())
      modelOverrides <- IO.ref(Map.empty[String, ModelCandidate])
      voiceMuted <- IO.ref(false)
      schedRef <- IO.ref(FreezeScheduleConfig(enabled = false))
    yield SharedResources(
      llm = llm,
      dispatcher = dispatcher,
      sessionStore = SessionStore(tmp / "sessions", tmp / "tasks"),
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
      subAgentTaskStore = new SubAgentTaskStore(tmp / "subagent-tasks"),
      voiceMutedRef = voiceMuted,
      freezeScheduleRef = schedRef
    )

  private def rootDef(name: String = "Nebula", category: String = ""): AgentDef =
    AgentDef(name = name, description = "test", tools = List("Read"), systemPrompt = "", category = category)

  private def waitUntil(deadlineMs: Long)(cond: IO[Boolean]): IO[Unit] =
    cond.flatMap {
      case true => IO.unit
      case false =>
        if System.currentTimeMillis() > deadlineMs then IO.raiseError(new RuntimeException("waitUntil timeout"))
        else IO.sleep(100.millis) *> waitUntil(deadlineMs)(cond)
    }

  private def parentProbe(events: cats.effect.Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    Behaviors.receiveMessage[AgentCommand](cmd => events.update(_ :+ cmd).as(parentProbe(events)))

  /** 父 probe 收到的 error-escalated 通知的 level 列表（时间序）。 */
  private def escalationLevels(got: List[AgentCommand]): List[Int] =
    got.collect {
      case AgentCommand.ExternalEvent(_, eventType, _, metadata, _, _) if eventType == "error-escalated" =>
        metadata("level").flatMap(_.as[Int].toOption).getOrElse(-1)
    }

  // ── ①销账 + ②同级去重（陈旧 Escalate 丢弃）：同一真实场景钉两面 ─────────────

  test("STORM-1/2 (重写): provider failures never enter or notify escalation; recovery still closes cleanly") {
    val system = ActorSystem("escalation-storm")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    val prevLlmLog = nebflow.core.LlmLogWriter.isEnabled
    nebflow.core.LlmLogWriter.setEnabled(false)
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        succeedRef <- IO.ref(false)
        parentEvents <- IO.ref(List.empty[AgentCommand])
        resources <- mkResources(system, tmp, GatedFailLlm(counter, succeedRef))
        parentRef <- system.spawn(parentProbe(parentEvents), "storm-parent")
        sid = "storm-agent"
        ref <- system.spawn(
          AgentActor(
            agentDef = rootDef("Backend", category = "team"),
            resources = resources,
            wsSend = _ => IO.unit,
            depth = 1,
            parentRef = Some(parentRef),
            sessionId = Some(sid),
            sessionName = Some("storm")
          ),
          sid
        )
        _ <- resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Team, sid, Some(parentRef))))
        // fallback 废除批（作者令 2026-11-06「不再向上通知」）：429 是 provider 家族
        // 冻结（llm-transient，呈现面 autoRecoverable）——冻结/退避循环照旧，但
        // **永不进升级链、永不向父会话发 error-escalated**。原「连续 ≥3 ⇒ L1 链 +
        // 恰好一条通知」面退役；陈旧 Escalate 丢弃与销账语义改按零链零通知判据重钉。
        _ <- ref ! AgentCommand.UserInput("keep failing")
        _ <- waitUntil(System.currentTimeMillis() + 30000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Frozen))
        )
        _ <- waitUntil(System.currentTimeMillis() + 30000)(counter.get.map(_ >= 2))
        _ <- IO.sleep(6500.millis)
        _ <- ref ! AgentCommand.CheckFreezeGate
        _ <- waitUntil(System.currentTimeMillis() + 30000)(counter.get.map(_ >= 3))
        _ <- IO.sleep(6500.millis)
        _ <- ref ! AgentCommand.CheckFreezeGate
        _ <- waitUntil(System.currentTimeMillis() + 30000)(counter.get.map(_ >= 4))
        // 升级链整体缺席：registry 无 escalation 快照、父零通知
        _ <- IO.sleep(1000.millis)
        escAfterWave <- resources.agentRegistry.get.map(_.get(sid).flatMap(_.escalation))
        _ = assert(escAfterWave.isEmpty, "provider family must never enter the escalation chain")
        levelsAfterWave <- parentEvents.get.map(escalationLevels(_))
        _ = assertEquals(levelsAfterWave, Nil, "provider family freezes must not notify the parent")

        // ②陈旧 Escalate 丢弃：无链状态下到达的 Escalate 不得凭空开链、不得通知
        _ <- ref ! AgentCommand.Escalate
        _ <- IO.sleep(800.millis)
        _ <- ref ! AgentCommand.Escalate
        _ <- IO.sleep(800.millis)
        levelsAfterStale <- parentEvents.get.map(escalationLevels(_))
        _ = assertEquals(levelsAfterStale, Nil, "stale Escalate without a chain must NOT notify")
        escAfterStale <- resources.agentRegistry.get.map(_.get(sid).flatMap(_.escalation))
        _ = assert(escAfterStale.isEmpty, "stale Escalate must not open a chain on a provider-family freeze")

        // ①销账：provider 恢复（succeedRef=true）→ 冻结退避到期后自动续跑完成回合；
        // 全程零升级通知
        _ <- succeedRef.set(true)
        _ <- ref ! AgentCommand.UserInput("parent decision: taking over", None, Some("storm-wake-1"))
        _ <- waitUntil(System.currentTimeMillis() + 30000)(
          resources.agentRegistry.get.map(_.get(sid).exists(_.status == AgentStatus.Idle))
        )
        escAfterWake <- resources.agentRegistry.get.map(_.get(sid).flatMap(_.escalation))
        _ = assert(escAfterWake.isEmpty, "recovery must leave the (absent) chain closed")
        levelsAfterWake <- parentEvents.get.map(escalationLevels(_))
        _ = assertEquals(levelsAfterWake, Nil, "recovery must not emit escalation notifications")
      yield ()
      program.unsafeRunSync()
    finally
      nebflow.core.LlmLogWriter.setEnabled(prevLlmLog)
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    end try
  }

  // ── ②重投节流：扫描器发 Escalate 后把 registry escalateAt 前推一个窗口 ─────

  test("STORM-2b: FreezeScheduler.scan throttles re-fire by bumping escalateAt forward") {
    val system = ActorSystem("storm-scan")
    val tmp = os.temp.dir()
    val prevRoot = PathUtil.dataRoot
    PathUtil.setDataRoot(tmp / "data")
    try
      val program = for
        counter <- IO.ref(0)
        succeedRef <- IO.ref(true)
        got <- IO.ref(List.empty[AgentCommand])
        probe <- system.spawn(parentProbe(got), "storm-scan-probe")
        resources <- mkResources(system, tmp, GatedFailLlm(counter, succeedRef))
        now <- IO(System.currentTimeMillis())
        expired = EscalationInfo(level = 2, escalateAt = now - 1000L, awaitedParentSessionId = "")
        rec = AgentRecord("scan-target", probe, AgentKind.Team, "scan-root", None).copy(escalation = Some(expired))
        _ <- resources.agentRegistry.update(_ + (rec.sessionId -> rec.copy(status = AgentStatus.Frozen)))
        _ <- FreezeScheduler.scan(resources)
        _ <- IO.sleep(300.millis)
        gotFirst <- got.get
        _ = assert(gotFirst.exists(_.isInstanceOf[AgentCommand.CheckFreezeGate.type]), "scan must ping frozen agents")
        _ = assert(
          gotFirst.exists {
            case AgentCommand.Escalate => true
            case _ => false
          },
          "expired escalation must fire Escalate"
        )
        bumped <- resources.agentRegistry.get.map(_.get("scan-target").flatMap(_.escalation))
        _ = assert(bumped.isDefined, "escalation snapshot must survive the scan")
        now2 <- IO(System.currentTimeMillis())
        _ = assert(
          bumped.exists(_.escalateAt >= now2 + (nebflow.shared.Defaults.ErrorEscalateAfterMs - 5000L)),
          s"scan must bump escalateAt a full window forward (re-fire throttle), got ${bumped.map(_.escalateAt)}"
        )
        _ <- FreezeScheduler.scan(resources)
        _ <- IO.sleep(200.millis)
        allGot <- got.get
        escalates = allGot.count {
          case AgentCommand.Escalate => true
          case _ => false
        }
        _ = assertEquals(escalates, 1, "second scan within the bumped window must NOT re-fire Escalate")
      yield ()
      program.unsafeRunSync()
    finally
      PathUtil.setDataRoot(prevRoot)
      system.stopAll.attempt.void.unsafeRunSync()
      os.remove.all(tmp)
    end try
  }

  // ── ③合并：pendingEvents 同键原位替换（纯函数） ─────────────────────────────

  private def escEvent(
    failedSessionId: String,
    level: Int,
    payload: String = "p"
  ): AgentCommand.ExternalEvent =
    AgentCommand.ExternalEvent(
      source = "team",
      eventType = "error-escalated",
      payload = payload,
      metadata = io.circe.JsonObject(
        "failedSessionId" -> failedSessionId.asJson,
        "level" -> level.asJson
      ),
      correlationId = Some(failedSessionId)
    )

  private def levelOf(e: AgentCommand.ExternalEvent): Int =
    e.metadata("level").flatMap(_.as[Int].toOption).getOrElse(-1)

  test("STORM-3: coalesce replaces same-key escalation in place; keeps others verbatim") {
    val a1 = escEvent("sess-a", 2, "lvl2")
    val b1 = escEvent("sess-b", 3, "lvl3-b")
    val plain = AgentCommand.ExternalEvent(
      source = "delegate",
      eventType = "completed",
      payload = "done",
      metadata = io.circe.JsonObject(),
      correlationId = Some("sess-c")
    )
    val a4 = escEvent("sess-a", 4, "lvl4")

    // 同键原位替换：a 的 L2 被 L4 原位顶替（位置不变），b 与普通事件逐字保留
    val merged = AgentActor.coalesceEscalationEvents(List(a1, plain, b1), a4)
    assertEquals(merged.size, 3, "same-key escalation must replace, not append")
    assertEquals(merged(0).payload, "lvl4", "latest level payload wins, position preserved")
    assertEquals(levelOf(merged(0)), 4)
    assertEquals(merged(1), plain, "non-escalation events untouched")
    assertEquals(merged(2), b1, "other sessions' escalations untouched")

    // 异键追加照旧；空队列照常追加
    assertEquals(AgentActor.coalesceEscalationEvents(List(a1), b1).size, 2, "different session must append")
    assertEquals(AgentActor.coalesceEscalationEvents(Nil, a4).size, 1)
  }

  // ── ④风暴总闸：滑窗 + 压制计数 + 放行摘要随行（单元） ────────────────────────

  test("STORM-4: gate admits up to cap per window, suppresses beyond, summary rides next admit") {
    EscalationNotifyGate.make(cap = 3, windowMs = 60_000L).flatMap { gate =>
      val t0 = 1_000_000L
      for
        v1 <- gate.admit("parent-x", t0)
        v2 <- gate.admit("parent-x", t0 + 1)
        v3 <- gate.admit("parent-x", t0 + 2)
        _ = assert(v1.allow && v1.suppressed == 0)
        _ = assert(v2.allow && v2.suppressed == 0)
        _ = assert(v3.allow && v3.suppressed == 0)
        v4 <- gate.admit("parent-x", t0 + 3)
        v5 <- gate.admit("parent-x", t0 + 4)
        _ = assert(!v4.allow, "beyond cap must suppress")
        _ = assertEquals(v4.suppressed, 1)
        _ = assert(!v5.allow)
        _ = assertEquals(v5.suppressed, 2)
        // 窗口滑动后首条放行：压制计数随行消费（摘要面）
        v6 <- gate.admit("parent-x", t0 + 60_001L)
        _ = assert(v6.allow, "after the window slides, the next notice is admitted")
        _ = assertEquals(v6.suppressed, 2, "suppressed count must ride the first admitted notice")
        // 消费后归零：随后再超限从 1 起算（窗内 v6/v7/v8 三条已占满 cap=3）
        v7 <- gate.admit("parent-x", t0 + 60_002L)
        v8 <- gate.admit("parent-x", t0 + 60_003L)
        _ = assert(v7.allow && v8.allow)
        v9 <- gate.admit("parent-x", t0 + 60_004L)
        _ = assert(!v9.allow, "window refills to cap after consumption")
        _ = assertEquals(v9.suppressed, 1, "suppression counter restarts from 1 after consumption")
        // 会话隔离：另一父会话不受影响
        other <- gate.admit("parent-y", t0)
        _ = assert(other.allow && other.suppressed == 0)
      yield ()
      end for
    }
  }
end EscalationNotifyStormSpec
