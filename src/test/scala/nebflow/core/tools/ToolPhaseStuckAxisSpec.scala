package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.CatsEffectSuite
import nebflow.actor.{ActorRef, ActorSystem, Behavior, Behaviors}
import nebflow.agent.{AgentCommand, AgentKind, AgentRecord, AgentStatus, SharedResources}
import nebflow.core.processor.TaskStuckWatcher
import nebflow.gateway.WsHub
import nebflow.llm.{ModelCandidate, ProviderHealthMonitor, ThinkingConfig}

import java.io.File
import scala.concurrent.duration.*

/**
 * 2026-09-10 卡死判据换轴 —— **验红用例**（取证
 * `20260910_130621_flow-node-activity-signal-forensics.md`）。
 *
 * 事故形态（n-5c69c793，2h50m 零告警）：一条前台常驻命令（dev server）
 * **持续输出为零**、进程 CPU 持续微动（实测 1.786 ms/s = 10ms 阈值的 5.4 倍），
 * agent 侧早已停摆（零 LLM turn、零 chunk）。旧实现里 BashTool 活动桥把这份
 * 「进程活性」写进 `AgentRecord.lastActivityMs`（agent 侧判据的唯一数据源）
 * ⇒ TaskStuckWatcher / SessionKick / AgentControl 全部失明。
 *
 * 本 spec 钉两条契约（两条都在「修复前」代码上实测为红，修复后为绿）：
 *   ① 写侧分离：前台命令的进程活性只写 `processActivityMs`，**绝不**写
 *      `lastActivityMs`（判据数据源）。用 sleep-like 命令（`sleep N`）驱动
 *      桥的 hasProgress 分支——确定性，且不依赖 CPU 采样（本沙箱 `/bin/ps`
 *      EPERM，CPU 采样恒 0，见结果正文「基线红归因」）。
 *   ② 换轴判据：agent 侧戳**新鲜**（进程还在动）但同一 turn 内单个工具调用
 *      已超阈（`Defaults.ToolPhaseStuckMs`，prop 缩短为 1.5s 复现窗口）且 turn
 *      未完成 ⇒ 必须判卡死并广播 taskStuck（旧判据只认 lastActivityMs → 永不触发）。
 *      现场含真实零输出长跑进程 + 真实活动桥（生产写点本体）。
 */
class ToolPhaseStuckAxisSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  // R8 方向①：本 spec 会驱动真实开火（TaskStuckWatcher.scan）⇒ 看门狗事件日志
  // （默认 <dataRoot>/logs/watchdog/）必须落到 spec 自己的临时目录，不得写进
  // 真实 ~/.nebflow（与 LlmLogWriter.setLogDirForTest 同款测试缝）。
  private val watchdogLogTmp = os.temp.dir(prefix = "stuck-axis-watchdog-events")
  override def beforeAll(): Unit =
    nebflow.core.processor.WatchdogEventLog.setLogDirForTest(watchdogLogTmp.toNIO)
  override def afterAll(): Unit = nebflow.core.processor.WatchdogEventLog.resetLogDirForTest()

  private val Sid = "node-phase-axis-0001"
  private val ToolPhaseProp = "nebflow.stuck.toolPhaseMs"

  // ── 夹具 ────────────────────────────────────────────────────────────────

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

  private def mkRecordingActor(record: Ref[IO, List[AgentCommand]]): Behavior[AgentCommand] =
    def loop: Behavior[AgentCommand] =
      Behaviors.receiveMessage[AgentCommand](cmd => record.update(_ :+ cmd).as(loop))
    loop

  /** 起一个真实进程（前台长跑命令的进程本体）。 */
  private def startProc(cmd: String): IO[Process] =
    IO.blocking {
      val pb = new ProcessBuilder("bash", "-c", cmd)
      pb.redirectInput(new File("/dev/null"))
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.start()
    }

  /** 真活动桥（生产写点：BashTool.startActivityBridge）跑 n 个 1s 采样窗。 */
  private def runBridge(
    proc: Process,
    ctx: ToolContext,
    sessionKey: String,
    command: String,
    ticks: Int
  ): IO[Unit] =
    for
      shell <- ShellSession.forSession(sessionKey)
      health <- IO {
        val h = new JobHealth()
        h.processRef.set(proc)
        h
      }
      fiber <- BashTool.startActivityBridge(shell, health, command, ctx, checkInterval = 1.second)
      _ <- IO.sleep((ticks + 1).seconds)
      _ <- fiber.cancel
      _ <- ShellSession.destroySession(sessionKey)
    yield ()

  private def recordOf(registry: Ref[IO, Map[String, AgentRecord]]): IO[AgentRecord] =
    registry.get.map(_.getOrElse(Sid, fail(s"registry lost $Sid")))

  /** 在测试期内覆盖 toolPhase 阈值 prop（Defaults 每次调用现读），退出时还原。 */
  private def withToolPhaseProp[A](ms: String)(body: IO[A]): IO[A] =
    IO {
      val prev = sys.props.get(ToolPhaseProp)
      sys.props.update(ToolPhaseProp, ms)
      prev
    }.flatMap { prev =>
      body.guarantee(IO {
        prev match
          case Some(v) => sys.props.update(ToolPhaseProp, v)
          case None    => sys.props.remove(ToolPhaseProp)
      })
    }

  // ── ① 写侧分离 ──────────────────────────────────────────────────────────

  test("① 前台命令的进程活性只写 processActivityMs，绝不写 lastActivityMs（判据数据源）") {
    val sessionKey = "axis-write-side"
    for
      registry <- Ref.of[IO, Map[String, AgentRecord]](
        Map(Sid -> AgentRecord(
          sessionId = Sid,
          ref = null.asInstanceOf[ActorRef[AgentCommand]],
          kind = AgentKind.Flow,
          rootSessionId = "root-1",
          status = AgentStatus.Processing,
          lastActivityMs = 123456789L
        ))
      )
      resources = mkResources(registry)
      ctx = ToolContext(projectRoot = "/tmp", sessionId = Some(Sid), sharedResources = Some(resources))
      // sleep-like 前台命令：驱动桥的 hasProgress 分支（确定性，不依赖 CPU 采样）
      proc <- startProc("sleep 30")
      _ <- runBridge(proc, ctx, sessionKey, "sleep 30", ticks = 2)
        .guarantee(IO(proc.destroyForcibly()))
      after <- recordOf(registry)
      _ <- IO(
        assert(
          after.lastActivityMs == 123456789L,
          s"进程活性不得写 agent 侧判据字段 lastActivityMs（换轴铁律）：${after.lastActivityMs}"
        )
      )
      _ <- IO(
        assert(
          after.processActivityMs != 0L,
          "前台进程有进展时必须落 processActivityMs（旁证字段）"
        )
      )
    yield ()
  }

  // ── ② 换轴判据（事故形态） ───────────────────────────────────────────────

  test("② 零输出长跑前台命令 + agent 侧戳新鲜 + 工具相位超阈 → 判卡死并广播 taskStuck（修复前：永不判）") {
    val sessionKey = "axis-tool-phase"
    val system = ActorSystem("axis-tool-phase")
    withToolPhaseProp("1500") {
      for
        _ <- IO(system)
        registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
        resources = mkResources(registry)
        received <- Ref.of[IO, List[AgentCommand]](Nil)
        agentRef <- system.spawn(mkRecordingActor(received), "axis-agent")
        parentCmds <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingActor(parentCmds), "axis-parent")
        wsHub = new WsHub()
        wsEvents <- Ref.of[IO, List[Json]](Nil)
        _ <- wsHub.register(json => wsEvents.update(_ :+ json))
        now = System.currentTimeMillis()
        // 事故现场字段状态：status=Processing、agent 侧戳新鲜（进程侧还在动）、
        // 同一 turn 内单个工具调用已超阈（prop 缩短为 1.5s）。
        // 说明：工具相位的生产写点是 AgentCore.pipeToolExecutions（写路径由
        // AgentActivityWritePathSpec 覆盖），此处按 TaskStuckWatcherSpec 的
        // 既有惯例直接构造 registry 快照。
        _ <- resources.agentRegistry.set(
          Map(Sid -> AgentRecord(
            sessionId = Sid,
            ref = agentRef,
            kind = AgentKind.Delegate,
            rootSessionId = "root-1",
            parentRef = Some(parentRef),
            startedAt = now - 20 * 60 * 1000L,
            status = AgentStatus.Processing,
            lastActivityMs = now,
            turnStartedAt = now - 3_000L,
            currentToolName = Some("Bash"),
            currentToolStartedAt = now - 3_000L
          ))
        )
        // 真进程：零输出、CPU 持续动（事故同象限：dev server 形态）
        proc <- startProc("python3 -c 'while True: pass'")
        _ <- runBridge(proc, ctx = ToolContext(
          projectRoot = "/tmp",
          sessionId = Some(Sid),
          sharedResources = Some(resources)
        ), sessionKey, "python3 -c 'while True: pass'", ticks = 2)
          .guarantee(IO(proc.destroyForcibly()))
        liveAfterBridge <- recordOf(registry)
        // agent 侧判据轴（10min）刻意保持不动：只有换轴后的工具相位判据能命中
        _ <- TaskStuckWatcher.scan(resources, wsHub, thresholdMs = 10 * 60 * 1000L)
        _ <- IO.sleep(300.millis) // 等 tell 到达
        events <- wsEvents.get
        stopMsgs <- received.get
      yield
        // 活动桥不得污染 agent 侧判据字段（测试进程 CPU 采样可能为 0，此处为
        // 契约断言；写侧红证据见用例 ①）
        assert(
          liveAfterBridge.lastActivityMs == now,
          s"活动桥必须保持 agent 侧判据字段不变：${liveAfterBridge.lastActivityMs} != $now"
        )
        assert(
          events.size == 1,
          s"期望恰好 1 条 taskStuck 广播（工具相位超阈），实得 ${events.size}: $events"
        )
        val ev = events.head
        assertEquals(ev.hcursor.get[String]("type").toOption, Some("taskStuck"))
        assertEquals(ev.hcursor.get[String]("sessionId").toOption, Some(Sid))
        assertEquals(ev.hcursor.get[String]("kind").toOption, Some("Delegate"))
        assertEquals(ev.hcursor.get[String]("action").toOption, Some("restart"))
        assert(
          ev.hcursor.get[Long]("idleSecs").toOption.exists(_ >= 3L),
          s"idleSecs 必须反映工具已运行时长（≥3s），实得 ${ev.hcursor.get[Long]("idleSecs").toOption}"
        )
        assert(
          ev.hcursor.get[String]("reason").toOption.exists(r => r.contains("Bash") && r.contains("unfinished turn")),
          s"reason 必须指明工具相位判据（工具名 + turn 未完成），实得 ${ev.hcursor.get[String]("reason").toOption}"
        )
        assert(
          stopMsgs.count(_.isInstanceOf[AgentCommand.Stop]) == 1,
          s"子 agent 恢复动作保持：必须发 1 次 Stop，实得 $stopMsgs"
        )
    }.guarantee(system.stopAll.attempt.void)
  }

  // ── ③ 反向：阈值内不判（换轴不得引入新误杀） ─────────────────────────────

  test("③ 工具相位在阈值内（1.5s prop / 1.0s 已运行）→ 不判卡死，零广播") {
    val system = ActorSystem("axis-tool-phase-neg")
    withToolPhaseProp("1500") {
      for
        _ <- IO(system)
        registry <- Ref.of[IO, Map[String, AgentRecord]](Map.empty)
        resources = mkResources(registry)
        received <- Ref.of[IO, List[AgentCommand]](Nil)
        agentRef <- system.spawn(mkRecordingActor(received), "axis-neg-agent")
        parentCmds <- Ref.of[IO, List[AgentCommand]](Nil)
        parentRef <- system.spawn(mkRecordingActor(parentCmds), "axis-neg-parent")
        wsHub = new WsHub()
        wsEvents <- Ref.of[IO, List[Json]](Nil)
        _ <- wsHub.register(json => wsEvents.update(_ :+ json))
        now = System.currentTimeMillis()
        _ <- resources.agentRegistry.set(
          Map(Sid -> AgentRecord(
            sessionId = Sid,
            ref = agentRef,
            kind = AgentKind.Delegate,
            rootSessionId = "root-1",
            parentRef = Some(parentRef),
            status = AgentStatus.Processing,
            lastActivityMs = now,
            currentToolName = Some("Bash"),
            currentToolStartedAt = now - 1_000L
          ))
        )
        _ <- TaskStuckWatcher.scan(resources, wsHub, thresholdMs = 10 * 60 * 1000L)
        _ <- IO.sleep(200.millis)
        events <- wsEvents.get
        stopMsgs <- received.get
      yield
        assert(events.isEmpty, s"阈值内不得广播 taskStuck：$events")
        assert(stopMsgs.isEmpty, s"阈值内不得发 Stop：$stopMsgs")
    }.guarantee(system.stopAll.attempt.void)
  }

end ToolPhaseStuckAxisSpec
