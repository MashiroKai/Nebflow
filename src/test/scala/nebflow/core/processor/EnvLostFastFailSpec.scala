package nebflow.core.processor

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.project.NodeEngine
import nebflow.gateway.WsHub
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}

import scala.concurrent.duration.*

/**
 * #159/#176（wtsurv 批，2026-09-14）：**类③ 环境失效（cwd 消失）快速失败** +
 * **恢复路径失锚语义**的验收 spec。
 *
 * 事故形态（取证件 `20260913_100008_worktree-vanish-forensics.md` §1.1/§2.1/§4.4）：
 * 节点 worktree 目录中途消失 ⇒ 会话 cwd 失效 ⇒ 之后所有命令无法 spawn ⇒ 会话静默
 * ⇒ `agent idle Ns (no LLM/tool event)` ⇒ **671s / 683s** 才被判死。
 *
 * 本 spec 钉三条契约（每条都在改造前为红、改造后为绿）：
 *   ① **快速失败**：`cwd 消失 ∧ 静默满 EnvLostGraceMs` ⇒ 在 **600s 判死阈值之前**
 *      就产出一条 `taskStuck(action=failed)` + **明确错误文本**（含判据 J1）；
 *   ② **负控三态**：cwd 实存（`Some(true)`）/ 未知（`None` fail-safe）/ 有新鲜正信号
 *      （工具仍在推进）⇒ **一律不得**判类③，不得有任何终态化动作；
 *   ③ **恢复路径语义**：A3 锚不可用时的 resume 说明必须讲清「失锚续跑」（cwd 回落
 *      workspace + 上轮产物不可信），且 A3 可用时不得出现该措辞（负控）。
 */
class EnvLostFastFailSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  // 本 spec 会驱动真实 `TaskStuckWatcher.scan`（写 stuck-fire / stuck-detected）⇒
  // 看门狗事件日志必须落到 spec 自己的临时目录，不得写进真实 ~/.nebflow。
  private val watchdogLogTmp = os.temp.dir(prefix = "wtsurv-env-lost-events")
  override def beforeAll(): Unit =
    nebflow.core.processor.WatchdogEventLog.setLogDirForTest(watchdogLogTmp.toNIO)
  override def afterAll(): Unit = nebflow.core.processor.WatchdogEventLog.resetLogDirForTest()

  private val Sid = "node-envlost-0001"
  /** 生产判死阈值（600s）——本 spec 的会话静默 **90s**（< 阈值）⇒ 只有类③ 判据能开火。 */
  private val StuckThresholdMs = 10 * 60 * 1000L
  private val Silence90s = 90_000L

  private def mkResources(registry: Ref[IO, Map[String, AgentRecord]]): SharedResources =
    SharedResources(
      llm = null,
      dispatcher = null,
      sessionStore = null,
      projectRoot = os.pwd,
      thinkingConfigRef = Ref.unsafe[IO, ThinkingConfig](ThinkingConfig()),
      rateLimiter = null,
      fileChangeTracker = null,
      contextWindow = 0,
      agentLibrary = null,
      taskStore = null,
      historyArchiver = null,
      fileLockManager = null,
      sessionModelOverrides = Ref.unsafe[IO, Map[String, ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null.asInstanceOf[ProviderHealthMonitor],
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false),
      agentRegistry = registry
    )

  private def record(
    now: Long,
    silenceMs: Long = Silence90s,
    withTool: Boolean = false,
    progressAgoMs: Long = -1L
  ): AgentRecord =
    AgentRecord(
      sessionId = Sid,
      ref = null.asInstanceOf[ActorRef[AgentCommand]],
      kind = AgentKind.Flow,
      rootSessionId = "root-1",
      status = AgentStatus.Processing,
      lastActivityMs = now - silenceMs,
      currentToolName = if withTool then Some("Bash") else None,
      currentToolStartedAt = if withTool then now - silenceMs else 0L,
      lastProgressSignalAt = if progressAgoMs >= 0 then now - progressAgoMs else 0L
    )

  private def assessmentOf(rec: AgentRecord, now: Long): TaskStuckWatcher.StuckAssessment =
    val idleMs = now - rec.lastActivityMs
    val toolPhaseMs = if rec.currentToolStartedAt > 0 then now - rec.currentToolStartedAt else 0L
    TaskStuckWatcher.StuckAssessment(
      secs = idleMs / 1000,
      reason = s"agent idle ${idleMs / 1000}s (no LLM/tool event)",
      branch = TaskStuckWatcher.BranchAgentStale,
      agentIdleMs = idleMs,
      toolPhaseMs = toolPhaseMs,
      toolName = rec.currentToolName
    )

  /** 事件面读取（本 spec 的 watchdog 临时目录，按 type 过滤）。 */
  private def eventsOfType(tpe: String): IO[List[Json]] =
    IO.blocking {
      val f = watchdogLogTmp / s"${java.time.LocalDate.now()}_events.jsonl"
      if !os.exists(f) then Nil
      else os.read.lines(f).toList.flatMap(l => io.circe.parser.parse(l).toOption)
    }.map(_.filter(_.hcursor.get[String]("type").toOption.contains(tpe)))

  /** 本用例**新增**的事件（ts ≥ t0）——事件文件是**按日共享**的，跨用例必须按 ts
    * 切片，否则前一个用例的行会污染后一个用例的否定断言。 */
  private def eventsOfTypeSince(tpe: String, sinceMs: Long): IO[List[Json]] =
    eventsOfType(tpe).map(_.filter(_.hcursor.get[Long]("ts").toOption.exists(_ >= sinceMs)))

  private def stuckFrames(events: List[Json]): List[Json] =
    events.filter(_.hcursor.get[String]("type").toOption.contains("taskStuck"))

  // ── ① 判据层（纯函数正负控）──────────────────────────────────────────────

  test("②(判据正控) cwd 已消失 ⇒ 类③ env-lost（可恢复、非破坏档），note 含判据 J1") {
    val now = System.currentTimeMillis()
    val r = record(now)
    val c = TaskStuckWatcher.classify(r, assessmentOf(r, now), now, inflight = 0, cwdAlive = Some(false))
    assertEquals(c.cls, TaskStuckWatcher.ClassEnvLost)
    assert(c.note.contains("GONE"), c.note)
    assert(c.note.contains("J1"), c.note)
    assert(c.note.contains("class 3"), c.note)
    assertEquals(c.recoverable, true)
    assertEquals(c.destructiveAllowed, false, "类③ 必须是**非破坏档**（不得静默获得进程 kill 能力）")
  }

  test("②(判据负控) cwd 实存 / 未知（fail-safe）⇒ 一律不判类③") {
    val now = System.currentTimeMillis()
    val r = record(now)
    val alive = TaskStuckWatcher.classify(r, assessmentOf(r, now), now, inflight = 0, cwdAlive = Some(true))
    assertEquals(alive.cls, TaskStuckWatcher.ClassTrueStuck, "cwd 实存 ⇒ 不得判环境失效（负控）")
    val unknown = TaskStuckWatcher.classify(r, assessmentOf(r, now), now, inflight = 0, cwdAlive = None)
    assertEquals(unknown.cls, TaskStuckWatcher.ClassTrueStuck, "探针未知 ⇒ 不得据此判死（fail-safe）")
    // 缺省（既有调用点）：逐字保持旧行为
    val legacy = TaskStuckWatcher.classify(r, assessmentOf(r, now), now, inflight = 0)
    assertEquals(legacy.cls, TaskStuckWatcher.ClassTrueStuck)
  }

  test("②(判据负控) 正信号新鲜（工具仍在推进）+ cwd 消失 ⇒ 仍归类②，绝不判死") {
    val now = System.currentTimeMillis()
    val r = record(now, withTool = true, progressAgoMs = 1_000L)
    val c = TaskStuckWatcher.classify(r, assessmentOf(r, now), now, inflight = 0, cwdAlive = Some(false))
    assertEquals(c.cls, TaskStuckWatcher.ClassFalsePositive,
      "红线：有正信号不得促成判死——目录被删不会让**已启动**的进程停下")
    assertEquals(c.recoverable, false)
  }

  // ── ② 动作层（端到端正负控）─────────────────────────────────────────────

  test("②(动作正控) cwd 消失 + 静默 90s（< 600s 阈值）⇒ 立即 action=failed + 明确错误（快速失败）") {
    val system = ActorSystem("wtsurv-env-lost-fire")
    val body: IO[Unit] =
      for
        _ <- IO(system)
        registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
        resources = mkResources(registry)
        wsHub = new WsHub()
        wsEvents <- Ref.of[IO, List[Json]](Nil)
        _ <- wsHub.register(json => wsEvents.update(_ :+ json))
        now = System.currentTimeMillis()
        _ <- resources.agentRegistry.set(Map(Sid -> record(now)))
        t0 = System.currentTimeMillis()
        _ <- TaskStuckWatcher.scan(
          resources, wsHub, StuckThresholdMs,
          cwdProbe = _ => IO.pure(Some(false)))
        _ <- IO.sleep(300.millis)
        events <- wsEvents.get
        fires <- eventsOfTypeSince("stuck-fire", t0)
        detected <- eventsOfTypeSince(TaskStuckWatcher.StuckDetectedType, t0)
      yield
        val stuck = stuckFrames(events)
        assertEquals(stuck.size, 1, s"类③ 必须广播恰好一帧 taskStuck，实得 $stuck")
        val f = stuck.head
        assertEquals(f.hcursor.get[String]("action").toOption, Some("failed"))
        val reason = f.hcursor.get[String]("reason").toOption.getOrElse("")
        assert(reason.contains("env-lost"), s"明确错误必须自报类别：$reason")
        assert(reason.contains("J1"), s"明确错误必须含判据号（J1）：$reason")
        assert(reason.contains("NodeEdit"), s"明确错误必须给处置（NodeEdit 重激活）：$reason")
        val idle = f.hcursor.get[Long]("idleSecs").toOption.getOrElse(-1L)
        assert(idle < StuckThresholdMs / 1000,
          s"快速失败：不得等到 600s 判死阈值才动作（实得 ${idle}s）")
        assertEquals(
          fires.count(_.hcursor.get[String]("level").toOption.contains("env-lost")), 1,
          s"开火面留痕必须恰好一行 level=env-lost，实得 ${fires.map(_.noSpaces)}")
        assertEquals(
          detected.count(_.hcursor.get[String]("class").toOption.contains(TaskStuckWatcher.ClassEnvLost)), 1,
          "检出面必须留一行 class=env-lost")
    body.guarantee(system.stopAll.attempt.void)
  }

  test("②(动作负控) cwd 实存 ⇒ 零动作（不广播、不留痕）") {
    val system = ActorSystem("wtsurv-env-lost-alive")
    val body: IO[Unit] =
      for
        _ <- IO(system)
        registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
        resources = mkResources(registry)
        wsHub = new WsHub()
        wsEvents <- Ref.of[IO, List[Json]](Nil)
        _ <- wsHub.register(json => wsEvents.update(_ :+ json))
        now = System.currentTimeMillis()
        _ <- resources.agentRegistry.set(Map(Sid -> record(now)))
        t0 = System.currentTimeMillis()
        _ <- TaskStuckWatcher.scan(
          resources, wsHub, StuckThresholdMs,
          cwdProbe = _ => IO.pure(Some(true)))
        _ <- IO.sleep(300.millis)
        events <- wsEvents.get
        detected <- eventsOfTypeSince(TaskStuckWatcher.StuckDetectedType, t0)
      yield
        assert(stuckFrames(events).isEmpty, s"cwd 实存 ⇒ 不得广播 taskStuck，实得 $events")
        assert(detected.isEmpty, s"未命中判据 ⇒ 不得留 stuck-detected，实得 $detected")
    body.guarantee(system.stopAll.attempt.void)
  }

  test("②(动作负控 fail-safe) 探针未知（None）⇒ 零动作（绝不因探针故障终态化会话）") {
    val system = ActorSystem("wtsurv-env-lost-unknown")
    val body: IO[Unit] =
      for
        _ <- IO(system)
        registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
        resources = mkResources(registry)
        wsHub = new WsHub()
        wsEvents <- Ref.of[IO, List[Json]](Nil)
        _ <- wsHub.register(json => wsEvents.update(_ :+ json))
        now = System.currentTimeMillis()
        _ <- resources.agentRegistry.set(Map(Sid -> record(now)))
        _ <- TaskStuckWatcher.scan(
          resources, wsHub, StuckThresholdMs,
          cwdProbe = _ => IO.pure(None))
        _ <- IO.sleep(300.millis)
        events <- wsEvents.get
      yield assert(stuckFrames(events).isEmpty, s"探针未知 ⇒ 零动作，实得 $events")
    body.guarantee(system.stopAll.attempt.void)
  }

  // ── ③ 恢复路径语义（失锚续跑）────────────────────────────────────────────

  test("③(语义正控) A3 锚不可用 ⇒ resume 说明必须讲清「失锚续跑」（cwd 回落 + 产物不可信 + 判据 J1）") {
    val anchor = NodeEngine.RecoveryAnchor(
      worktreeAvailable = false,
      probeDir = "/tmp/wtsurv-gone-worktree",
      hasOutput = false,
      outputEvidence = "git status --porcelain = clean, commits since node start = 0")
    val note = NodeEngine.stuckResumeNote(Some(anchor))
    assert(note.contains("UNAVAILABLE"), note)
    assert(note.contains("/tmp/wtsurv-gone-worktree"), s"必须写出消失的目录原文：$note")
    assert(note.contains("J1"), s"必须给判据号（J1，机制类）：$note")
    assert(note.contains("UNRELIABLE"), s"必须明确上轮产物不可信：$note")
    assert(note.contains("workspace"), s"必须说明 cwd 回落（workspace）：$note")
  }

  test("③(语义负控) A3 锚可用 ⇒ 不得出现失锚措辞") {
    val anchor = NodeEngine.RecoveryAnchor(
      worktreeAvailable = true,
      probeDir = "/tmp/wtsurv-live-worktree",
      hasOutput = true,
      outputEvidence = "git status --porcelain = non-empty, commits since node start = 2")
    val note = NodeEngine.stuckResumeNote(Some(anchor))
    assert(note.contains("Output exists"), note)
    assert(!note.contains("UNAVAILABLE"), note)
    assert(!note.contains("UNRELIABLE"), note)
    assert(!note.contains("J1"), note)
  }
