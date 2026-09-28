package nebflow.core.tools

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * #391 机制 E（2026-08-25，#391 第一部分）：restart/Stop 联动 killProcessTree——
 * AgentControl restart → Stop → cancelCurrentTurn（只取消 fiber）→ killSessionProcesses
 * （杀该 session 全部 OS 进程树：前台 activeProcesses 注册表 + 后台任务进程）。
 * 修复 B9 残留根因链：IO.blocking 取消不中断线程，bracket release 永不执行，
 * bash/Chrome/helpers 进程树残留需手动 pkill。
 *
 * 验收（设计文档 §6.4）：
 * - E-1 前台 `sleep 120 & wait` 挂起 → killSessionProcesses → 进程树消失
 * - E-2 后台 run_in_background + 前台卡死 → 两者进程都消失；BgTaskRegistry 注销
 * - E-3 正常完成的前台命令 → killSessionProcesses no-op 无异常
 * - E-4 其他 session 的进程不受本 session kill 影响（隔离性）
 */
class ShellKillOnRestartSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 90.seconds

  private def procAlive(pid: Long): IO[Boolean] =
    IO {
      val opt = ProcessHandle.of(pid)
      opt.isPresent && opt.get().isAlive
    }

  private def waitFor(cond: IO[Boolean], deadlineMs: Long, what: String): IO[Unit] =
    def loop: IO[Unit] =
      cond.flatMap {
        case true => IO.unit
        case false => IO.sleep(100.millis) *> loop
      }
    loop.timeout(deadlineMs.millis).handleErrorWith { e =>
      IO(fail(s"timed out waiting for $what: ${e.getMessage}"))
    }

  /** 启动 `cmd` 并等待 proc 注册到 health（runProcess 的 processRef 被填）。 */
  private def startCmd(
    shell: ShellSession,
    cmd: String
  ): IO[(JobHealth, cats.effect.FiberIO[Either[Throwable, ProcessResult]])] =
    for
      health <- IO(new JobHealth())
      fiber <- shell.execute(cmd, 365.days, Some(health)).attempt.start
      _ <- waitFor(IO(health.processRef.get() != null && health.processRef.get().isAlive), 10000, s"process start: $cmd")
    yield (health, fiber)

  test("E-1: killSessionProcesses kills a hung foreground process tree") {
    for
      shell <- ShellSession.forSession("e1-session")
      // 异常路径销毁守卫（残留治理 2026-09-05）：waitFor 失败/中断时 sleep 120 也要被杀
      _ <- (for
        (health, fiber) <- startCmd(shell, "sleep 120 & wait")
        pid = health.processRef.get().pid()
        _ <- waitFor(procAlive(pid), 3000, "pid alive before kill")
        _ <- ShellSession.killSessionProcesses(Some("e1-session"))
        _ <- waitFor(procAlive(pid).map(!_), 10000, s"pid $pid dead after kill")
        _ <- fiber.join.timeout(5.seconds).attempt.void
      yield ())
        .guarantee(
          ShellSession.killSessionProcesses(Some("e1-session")).attempt.void *>
            ShellSession.destroySession("e1-session").attempt.void
        )
    yield ()
  }

  test("E-2: background job process + foreground process both killed; BgTaskRegistry unregistered") {
    for
      shell <- ShellSession.forSession("e2-session")
      // 异常路径销毁守卫（残留治理 2026-09-05）：sleep 300 后台任务不能漏到测试外
      _ <- (for
        // 后台任务（run_in_background 等价）：sleep 300 零输出零 CPU
        _ <- shell.executeBackground(
          "sleep 300",
          jobIdOverride = Some("e2-bg"),
          healthCheckIntervalSec = 1
        )
        _ <- BgTaskRegistry.register("e2-bg", "e2-session", "e2-bg-desc", "local")
        // 前台卡死进程
        (health, fiber) <- startCmd(shell, "sleep 120 & wait")
        fgPid = health.processRef.get().pid()
        _ <- waitFor(procAlive(fgPid), 3000, "fg pid alive")
        _ <- ShellSession.killSessionProcesses(Some("e2-session"))
        _ <- waitFor(procAlive(fgPid).map(!_), 10000, s"fg pid $fgPid dead")
        _ <- fiber.join.timeout(5.seconds).attempt.void
        // BgTaskRegistry 注销（killSessionShellProcesses 的职责，此处验证 unregisterSession 契约）
        removed <- BgTaskRegistry.unregisterSession(Some("e2-session"))
        _ <- IO(assert(removed.map(_.jobId).contains("e2-bg"), s"bg task should be unregistered: $removed"))
      yield ())
        .guarantee(
          shell.cancelBackgroundJob("e2-bg").attempt.void *>
            ShellSession.killSessionProcesses(Some("e2-session")).attempt.void *>
            ShellSession.destroySession("e2-session").attempt.void
        )
    yield ()
  }

  test("E-3: killSessionProcesses is a no-op after a completed foreground command") {
    for
      shell <- ShellSession.forSession("e3-session")
      result <- shell.execute("echo e3-done", 10.seconds)
      _ <- IO(assert(result.stdout.contains("e3-done")))
      _ <- ShellSession.killSessionProcesses(Some("e3-session")) // 不应抛错、无残留可杀
      _ <- ShellSession.destroySession("e3-session")
    yield ()
  }

  test("E-4: other session's processes are not touched (isolation)") {
    for
      shellA <- ShellSession.forSession("e4-a")
      shellB <- ShellSession.forSession("e4-b")
      // 异常路径销毁守卫（残留治理 2026-09-05）：两个 session 的 sleep 60 都不能漏到测试外
      _ <- (for
        (hA, fA) <- startCmd(shellA, "sleep 60")
        (hB, fB) <- startCmd(shellB, "sleep 60")
        pidA = hA.processRef.get().pid()
        pidB = hB.processRef.get().pid()
        _ <- waitFor(procAlive(pidA), 3000, "A alive")
        _ <- waitFor(procAlive(pidB), 3000, "B alive")
        _ <- ShellSession.killSessionProcesses(Some("e4-a"))
        _ <- waitFor(procAlive(pidA).map(!_), 10000, s"pid A $pidA dead")
        _ <- procAlive(pidB).flatMap(b => IO(assert(b, s"session B pid $pidB must survive")))
        // 清理：B 的前台进程
        _ <- ShellSession.killSessionProcesses(Some("e4-b"))
        _ <- waitFor(procAlive(pidB).map(!_), 10000, s"pid B $pidB dead (cleanup)")
        _ <- fA.join.timeout(5.seconds).attempt.void
        _ <- fB.join.timeout(5.seconds).attempt.void
      yield ())
        .guarantee(
          ShellSession.killSessionProcesses(Some("e4-b")).attempt.void *>
            ShellSession.destroySession("e4-a").attempt.void *>
            ShellSession.destroySession("e4-b").attempt.void
        )
    yield ()
  }

  /**
   * 孤儿后台任务收割 D1 主钩子（2026-09-06）：BgTaskRegistry.reclaimSession =
   * killSessionProcesses（杀进程树）+ unregisterSession（注销 registry）+
   * WS cancelled 帧——三件事合一，供 NodeEngine failed/cancelled/zombie 终态出口
   * 与 Agent Stop 路径共用。E-5 验证它经 killSessionProcesses 切实杀死真实进程。
   */
  test("E-5: reclaimSession kills the process tree + clears registry + sends WS cancelled frame") {
    for
      wsFrames <- Ref.of[IO, List[io.circe.Json]](Nil)
      shell <- ShellSession.forSession("e5-session")
      // 异常路径销毁守卫（残留治理 2026-09-05）：sleep 120 不能漏到测试外
      _ <- (for
        // 后台任务（run_in_background 等价）+ registry 登记
        _ <- shell.executeBackground(
          "sleep 300",
          jobIdOverride = Some("e5-bg"),
          healthCheckIntervalSec = 1
        )
        _ <- BgTaskRegistry.register("e5-bg", "e5-session", "e5-bg-desc", "local")
        // 前台卡死进程（killSessionProcesses 要杀的目标）
        (health, fiber) <- startCmd(shell, "sleep 120 & wait")
        pid = health.processRef.get().pid()
        _ <- waitFor(procAlive(pid), 3000, "fg pid alive")
        _ <- waitFor(BgTaskRegistry.waitingFor("e5-session").map(_.nonEmpty), 5000, "bg task registered")
        // D1 收殓：杀进程树 + 注销 + WS 帧（reclaimSession 单点）
        _ <- BgTaskRegistry.reclaimSession(
          Some("e5-session"),
          (j: io.circe.Json) => wsFrames.update(_ :+ j),
          "nebula-root"
        )
        _ <- waitFor(procAlive(pid).map(!_), 10000, s"fg pid $pid dead after reclaimSession")
        _ <- fiber.join.timeout(5.seconds).attempt.void
        stillWaiting <- BgTaskRegistry.waitingFor("e5-session")
        frames <- wsFrames.get
        _ <- shell.cancelBackgroundJob("e5-bg").attempt.void
      yield
        assertEquals(stillWaiting, Nil, "bg task must be cleared from registry by reclaimSession")
        assert(frames.exists(f =>
          f.hcursor.get[String]("type").contains("backgroundTaskUpdate") &&
            f.hcursor.get[String]("status").contains("cancelled") &&
            f.hcursor.get[String]("taskId").contains("e5-bg")
        ), s"WS cancelled backgroundTaskUpdate frame expected, got: ${frames.map(_.noSpaces.take(120)).mkString("|")}")
      )
        .guarantee(
          shell.cancelBackgroundJob("e5-bg").attempt.void *>
            ShellSession.killSessionProcesses(Some("e5-session")).attempt.void *>
            ShellSession.destroySession("e5-session").attempt.void
        )
    yield ()
  }

end ShellKillOnRestartSpec
