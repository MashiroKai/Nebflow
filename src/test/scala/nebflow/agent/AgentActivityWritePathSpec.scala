package nebflow.agent

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}

/**
 * 2026-09-10 卡死判据换轴（取证 20260910_130621_flow-node-activity-signal-forensics.md）
 * —— **写侧**契约：AgentCore.touchRegistryActivity（唯一 agent 侧写入路径）在换轴
 * 后的字段语义。
 *
 * 覆盖：
 *   - 工具开始 → 记 `currentToolName/currentToolStartedAt`（新判据的主轴）+ agent 侧戳
 *   - turn 起点：首次进入 Processing 置 `turnStartedAt`，同 turn 内的多轮循环不重置
 *   - WaitingForUser（人机交互）不算新 turn，且清工具相位
 *   - 离开 Processing（Idle）清工具相位；clearToolPhase 显式清
 *
 * 访问方式：`AgentCore` 是 `private[agent] trait`，测试用 `ActivityWriteProbe`
 * （同包内 extends AgentCore）暴露受保护的写入函数——测的是**生产代码本体**，
 * 不是复刻。
 */
object ActivityWriteProbe extends AgentCore:
  /** 生产写入点的直通转发（参数与 AgentCore.touchRegistryActivity 同名同义）。 */
  def touch(
    resources: SharedResources,
    sessionId: Option[String],
    status: AgentStatus,
    now: Long,
    turnStart: Boolean = false,
    toolStarting: Option[(String, Long)] = None,
    clearToolPhase: Boolean = false
  ): IO[Unit] =
    touchRegistryActivity(resources, sessionId, status, now, turnStart, toolStarting, clearToolPhase)

class AgentActivityWritePathSpec extends CatsEffectSuite:

  private val Sid = "write-path-session"

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

  private def freshRegistry: IO[Ref[IO, Map[String, AgentRecord]]] =
    Ref.of[IO, Map[String, AgentRecord]](
      Map(
        Sid -> AgentRecord(
          sessionId = Sid,
          ref = null.asInstanceOf[nebflow.actor.ActorRef[AgentCommand]],
          kind = AgentKind.Flow,
          rootSessionId = "root-1",
          status = AgentStatus.Idle
        )
      )
    )

  private def rec(registry: Ref[IO, Map[String, AgentRecord]]): IO[AgentRecord] =
    registry.get.map(_.getOrElse(Sid, fail("record vanished")))

  test("工具开始 → 记工具相位（名字+起始时刻）并进入 Processing，首轮置 turnStartedAt") {
    val t0 = 1_000_000L
    for
      registry <- freshRegistry
      resources = mkResources(registry)
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.Processing, t0, turnStart = true)
      afterTurn <- rec(registry)
      _ <- IO(assert(afterTurn.status == AgentStatus.Processing, s"status: ${afterTurn.status}"))
      _ <- IO(assertEquals(afterTurn.turnStartedAt, t0, "turn start must be stamped on entering Processing"))
      _ <- IO(assertEquals(afterTurn.lastActivityMs, t0, "agent-side stamp must advance"))
      // 工具开始（同 turn 内第 1 轮）
      _ <- ActivityWriteProbe.touch(
        resources, Some(Sid), AgentStatus.Processing, t0 + 1000,
        toolStarting = Some(("Bash", t0 + 1000))
      )
      afterTool <- rec(registry)
      _ <- IO(assertEquals(afterTool.currentToolName, Some("Bash"), "current tool name must be recorded"))
      _ <- IO(assertEquals(afterTool.currentToolStartedAt, t0 + 1000, "tool start must be recorded"))
      _ <- IO(assertEquals(afterTool.turnStartedAt, t0, "same turn — turnStartedAt must NOT be reset"))
    yield ()
  }

  test("同 turn 多轮：后续 LLM 调用不重置 turnStartedAt；批次完成（clearToolPhase）清相位") {
    val t0 = 2_000_000L
    for
      registry <- freshRegistry
      resources = mkResources(registry)
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.Processing, t0, turnStart = true)
      _ <- ActivityWriteProbe.touch(
        resources, Some(Sid), AgentStatus.Processing, t0 + 500,
        toolStarting = Some(("Grep", t0 + 500))
      )
      // 第 2 轮 LLM 起点（同 turn）
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.Processing, t0 + 900, turnStart = true)
      round2 <- rec(registry)
      _ <- IO(assertEquals(round2.turnStartedAt, t0, "round 2 is the same turn — start unchanged"))
      _ <- IO(assertEquals(round2.currentToolName, Some("Grep"), "phase survives until explicitly cleared"))
      // 工具批次完成
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.Processing, t0 + 1200, clearToolPhase = true)
      afterBatch <- rec(registry)
      _ <- IO(assertEquals(afterBatch.currentToolName, None, "batch completion clears the tool phase"))
      _ <- IO(assertEquals(afterBatch.currentToolStartedAt, 0L, "batch completion zeroes the phase start"))
    yield ()
  }

  test("WaitingForUser：不算新 turn、清工具相位；答题恢复 Processing 仍不重置 turn 起点") {
    val t0 = 3_000_000L
    for
      registry <- freshRegistry
      resources = mkResources(registry)
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.Processing, t0, turnStart = true)
      _ <- ActivityWriteProbe.touch(
        resources, Some(Sid), AgentStatus.Processing, t0 + 100,
        toolStarting = Some(("AskUserQuestion", t0 + 100))
      )
      // 进入人机交互等待（生产：AgentCore.askUserPermission）
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.WaitingForUser, t0 + 200)
      waiting <- rec(registry)
      _ <- IO(assertEquals(waiting.status, AgentStatus.WaitingForUser, s"status: ${waiting.status}"))
      _ <- IO(assertEquals(waiting.currentToolName, None, "WaitingForUser clears the tool phase"))
      _ <- IO(assertEquals(waiting.turnStartedAt, t0, "WaitingForUser is inside the same turn"))
      // 用户答题后恢复 Processing（生产：AgentCore.askUserPermission 的恢复分支）
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.Processing, t0 + 600_000, turnStart = true)
      resumed <- rec(registry)
      _ <- IO(assertEquals(resumed.turnStartedAt, t0, "resume from WaitingForUser is NOT a new turn"))
    yield ()
  }

  test("回到 Idle：清工具相位（run_in_background 防误杀铁律的相位侧）") {
    val t0 = 4_000_000L
    for
      registry <- freshRegistry
      resources = mkResources(registry)
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.Processing, t0, turnStart = true)
      _ <- ActivityWriteProbe.touch(
        resources, Some(Sid), AgentStatus.Processing, t0 + 10,
        toolStarting = Some(("Bash", t0 + 10))
      )
      _ <- ActivityWriteProbe.touch(resources, Some(Sid), AgentStatus.Idle, t0 + 20)
      idle <- rec(registry)
      _ <- IO(assertEquals(idle.status, AgentStatus.Idle, s"status: ${idle.status}"))
      _ <- IO(assertEquals(idle.currentToolName, None, "Idle clears the tool phase"))
      _ <- IO(assertEquals(idle.currentToolStartedAt, 0L, "Idle zeroes the phase start"))
    yield ()
  }

  test("非 Processing 状态写入：phase 参数被忽略（相位只在 Processing 语义下有意义）") {
    val t0 = 5_000_000L
    for
      registry <- freshRegistry
      resources = mkResources(registry)
      // 即便调用方传了相位，WaitingForUser 落盘后仍必须是空相位
      _ <- ActivityWriteProbe.touch(
        resources, Some(Sid), AgentStatus.WaitingForUser, t0,
        toolStarting = Some(("Bash", t0))
      )
      r <- rec(registry)
      _ <- IO(assertEquals(r.currentToolName, None, "non-Processing write must not carry a tool phase"))
      _ <- IO(assertEquals(r.currentToolStartedAt, 0L, "non-Processing write must zero the phase start"))
    yield ()
  }

end AgentActivityWritePathSpec
