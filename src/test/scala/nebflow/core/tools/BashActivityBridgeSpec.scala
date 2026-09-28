package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import nebflow.agent.{AgentKind, AgentRecord, SharedResources}
import nebflow.llm.{ProviderHealthMonitor, ThinkingConfig}

import java.io.File
import scala.concurrent.duration.*

/**
 * #391 机制 D（2026-08-25）：活动桥接 CPU 判断对齐 CpuActiveThresholdNanos
 * （10ms/采样窗口）。
 *
 * 2026-09-10 卡死判据换轴（取证 20260910_130621_flow-node-activity-signal-forensics.md）：
 * 活动桥接写目标从 `AgentRecord.lastActivityMs`（agent 侧，卡死判据的数据源）
 * 改为 `AgentRecord.processActivityMs`（进程侧，**不参与卡死判据**）——事故实证：
 * 前台常驻 dev server 的 CPU 微动经此桥每 30s 刷新 agent 侧戳 ⇒ TaskStuckWatcher
 * 判据失明 2h50m。本 spec 的断言随之改为「进程侧戳动 / agent 侧戳不动」。
 *
 * 验收：
 * - D-1 输出零增长 + CPU 增量 <10ms/窗口 → 两戳都不动
 * - D-2 CPU 增量 ≥10ms/窗口 → processActivityMs 被 touch，lastActivityMs 不动
 *   （进程活性 ≠ agent 活动——换轴的核心契约）
 */
class BashActivityBridgeSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 90.seconds

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
      sessionModelOverrides = Ref.unsafe[IO, Map[String, nebflow.llm.ModelCandidate]](Map.empty),
      providerRegistry = null,
      healthMonitor = null.asInstanceOf[ProviderHealthMonitor],
      actorSystem = null,
      voiceMutedRef = Ref.unsafe[IO, Boolean](false),
      agentRegistry = registry
    )

  /** 启动真实进程，返回 proc 供 processRef 手动注入。 */
  private def startProc(cmd: String): IO[Process] =
    IO.blocking {
      val pb = new ProcessBuilder("bash", "-c", cmd)
      pb.redirectInput(new File("/dev/null"))
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.start()
    }

  /** 纯 sleep 进程（不经 bash——bash 启动开销 CPU 累计可能 ≥10ms，首轮采样
    * lastCpu=0 时误判 cpuActive，污染「零 CPU 不 touch」断言）。sleep 是原生
    * C 程序，启动开销 <1ms。 */
  private def startSleepProc(secs: Int): IO[Process] =
    IO.blocking {
      val pb = new ProcessBuilder("sleep", secs.toString)
      pb.redirectInput(new File("/dev/null"))
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.start()
    }

  private def runBridge(
    proc: Process,
    ctx: ToolContext,
    bridgeTicks: Int = 2
  ): IO[Unit] =
    for
      shell <- ShellSession.forSession("bridge-session")
      health <- IO {
        val h = new JobHealth()
        h.processRef.set(proc)
        h
      }
      fiber <- BashTool.startActivityBridge(shell, health, "python3 busy", ctx, checkInterval = 1.second)
      // 跑 bridgeTicks 个采样窗口（1s each）让 bridge 有机会 touch
      _ <- IO.sleep((bridgeTicks + 1).seconds)
      _ <- fiber.cancel
      _ <- ShellSession.destroySession("bridge-session")
    yield ()

  private def lastActivityOf(registry: Ref[IO, Map[String, AgentRecord]]): IO[Long] =
    registry.get.map(_.get("bridge-session").map(_.lastActivityMs).getOrElse(-1L))

  private def processActivityOf(registry: Ref[IO, Map[String, AgentRecord]]): IO[Long] =
    registry.get.map(_.get("bridge-session").map(_.processActivityMs).getOrElse(-1L))

  test("D-1: CPU delta < 10ms/窗口 + 零输出 → 两戳都不被 touch（卡死不误判有进展）") {
    for
      registry <- Ref.of[IO, Map[String, AgentRecord]](
        Map("bridge-session" -> AgentRecord(
          sessionId = "bridge-session",
          ref = null.asInstanceOf[nebflow.actor.ActorRef[nebflow.agent.AgentCommand]],
          kind = nebflow.agent.AgentKind.Root,
          lastActivityMs = 123456789L,
          rootSessionId = "root"
        ))
      )
      resources = mkResources(registry)
      ctx = ToolContext(projectRoot = "/tmp", sessionId = Some("bridge-session"), sharedResources = Some(resources))
      // 零 CPU 进程：sleep（<10ms/1s 采样窗口；原生 sleep 启动开销 <1ms）
      proc <- startSleepProc(30)
      // 异常路径销毁守卫：断言失败/中断/超时也杀进程（残留治理 2026-09-05——
      // 尾部 destroyForcibly 只覆盖 happy path，中途抛错即泄漏）
      _ <- runBridge(proc, ctx).guarantee(IO(proc.destroyForcibly()))
      last <- lastActivityOf(registry)
      process <- processActivityOf(registry)
      _ <- IO(assert(last == 123456789L, s"lastActivityMs must NOT be touched: $last"))
      _ <- IO(assert(process == 0L, s"processActivityMs must NOT be touched either: $process"))
    yield ()
  }

  test("D-2: CPU delta ≥ 10ms/窗口 → processActivityMs touched，lastActivityMs 不动") {
    for
      registry <- Ref.of[IO, Map[String, AgentRecord]](
        Map("bridge-session" -> AgentRecord(
          sessionId = "bridge-session",
          ref = null.asInstanceOf[nebflow.actor.ActorRef[nebflow.agent.AgentCommand]],
          kind = nebflow.agent.AgentKind.Root,
          lastActivityMs = 123456789L,
          rootSessionId = "root"
        ))
      )
      resources = mkResources(registry)
      ctx = ToolContext(projectRoot = "/tmp", sessionId = Some("bridge-session"), sharedResources = Some(resources))
      // CPU 忙进程：python busy loop（每 1s 窗口 CPU 增量 >> 10ms）——
      // 永不自终止，断言失败/中断路径必须守卫销毁（作者手清的残留形态）
      proc <- startProc("python3 -c 'while True: pass'")
      _ <- runBridge(proc, ctx).guarantee(IO(proc.destroyForcibly()))
      last <- lastActivityOf(registry)
      process <- processActivityOf(registry)
      _ <- IO(assert(process != 0L, s"processActivityMs must be touched for busy process: $process"))
      // 换轴铁律：进程活性绝不写 agent 侧戳（否则卡死判据再次被进程 CPU 微动解除）
      _ <- IO(assert(last == 123456789L, s"lastActivityMs must NOT be touched by the process bridge: $last"))
    yield ()
  }

end BashActivityBridgeSpec
