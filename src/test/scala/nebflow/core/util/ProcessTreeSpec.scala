package nebflow.core.util

import cats.effect.IO
import munit.CatsEffectSuite

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*

/**
 * bash overhaul T6 红相（作者令 2026-11-04「组织优化 bash，用业界成熟做法完整
 * 实现，一步到位」）：**进程树 kill 的平台分路必须显式化**，Windows 腿补
 * `taskkill /F /T`。
 *
 * 事实源：`docs/20261104_110500_our-bash-tool-defects.md` 缺陷 D8 ——
 * `ProcessTree.scala:85-115` 的「双枚举」在 Windows 退化为单快照（`ps` 不存在 ⇒
 * `descendantsViaPs` 抛异常被吞成 `Nil`；`destroyForcibly()` 在 Windows 是
 * `TerminateProcess` 不带 `/T`，被 reparent 的孙进程逃逸）；调研件 §3.4
 * （pi「Use the trusted System32 executable so cleanup does not depend on PATH」、
 * Cline `taskkill.exe` 同款；macOS 先 SIGSTOP 组再 SIGKILL——pi issue #11252 竞态）。
 *
 * 验收面（平台计划纯函数 + 参数单点 + POSIX 活体树）：
 *   1. `platformPlan` 按平台名分三路：Windows→TaskKill、Mac OS X→SignalStopThenKill、
 *      Linux→TermThenKill（macOS 的 SIGSTOP-first 对齐 pi #11252 的缓解）；
 *   2. `taskkillArgs(pid)` = Seq(<System32 绝对路径>/taskkill.exe, "/F", "/T", "/PID",
 *      "<pid>")——**绝对路径**（System32 信任面，不依赖 PATH）+ `/T`（树杀）；
 *   3. 活体：`bash -c 'sleep 30 & sleep 30 & wait'` 起的进程树在
 *      `killProcessTree` 后 3s 内后代全死（不留孤儿）。
 *
 * 本文件在旧代码下**整体编译红**：`platformPlan` / `Plan` / `taskkillArgs` 三个
 * 符号不存在（`Not found`）= 预期红证据；活体腿是既有 `killProcessTree` 的
 * 回归臂（防新分路把 POSIX 腿改坏）。
 */
class ProcessTreeSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private val isWindows = System.getProperty("os.name", "").toLowerCase.contains("windows")

  test("T6: platformPlan routes Windows→TaskKill, Mac OS X→SignalStopThenKill, Linux→TermThenKill") {
    IO {
      import ProcessTree.Plan
      assertEquals(ProcessTree.platformPlan("Windows 11"), Plan.TaskKill, "Windows must use taskkill /T")
      assertEquals(ProcessTree.platformPlan("Windows 10"), Plan.TaskKill, "Windows 10 same leg")
      assertEquals(
        ProcessTree.platformPlan("Mac OS X"),
        Plan.SignalStopThenKill,
        "macOS must freeze the process group before killing (pi #11252 race mitigation)"
      )
      assertEquals(ProcessTree.platformPlan("Linux"), Plan.TermThenKill, "Linux keeps TERM→KILL")
    }
  }

  test("T6: taskkillArgs(4242) is the System32-absolute trust face with /F /T /PID <pid>") {
    IO {
      val args = ProcessTree.taskkillArgs(4242)
      assertEquals(args.length, 5, s"shape = (taskkill, /F, /T, /PID, <pid>), got: $args")
      assert(
        args.head.endsWith("taskkill.exe") && args.head.contains("System32"),
        s"first arg must be the ABSOLUTE System32 taskkill.exe (no PATH reliance), got: ${args.head}"
      )
      assertEquals(args(1), "/F")
      assertEquals(args(2), "/T", "/T (tree) is the whole point — reparented descendants must die")
      assertEquals(args(3), "/PID")
      assertEquals(args(4), "4242")
    }
  }

  test("T6 live tree: killProcessTree leaves zero descendants of a bash&wait tree alive within 3s") {
    assume(!isWindows, "Windows leg is the taskkill plan (pinned by the pure-function tests above)")
    (for
      proc <- IO.blocking {
        val pb = new java.lang.ProcessBuilder("/bin/bash", "-c", "sleep 30 & sleep 30 & wait")
        pb.redirectInput(new java.io.File("/dev/null"))
        pb.redirectErrorStream(true)
        pb.start()
      }
      // 等两个 sleep 后代都被 fork 出来。
      _ <- IO.sleep(700.millis)
      before <- IO.blocking(proc.toHandle.descendants().count())
      _ <- IO(assert(before >= 2, s"the tree must have >=2 live descendants before the kill, got $before"))
      t0 <- IO.monotonic
      _ <- ProcessTree.killProcessTree(proc.toHandle)
      alive <- {
        val deadline = 3.seconds
        def loop: IO[Long] =
          IO.blocking(proc.toHandle.descendants().count()).flatMap { n =>
            if n == 0L then IO.pure(0L) else IO.sleep(100.millis) *> loop
          }
        loop.timeoutTo(deadline, IO.blocking(proc.toHandle.descendants().count()))
      }
      elapsed <- IO.monotonic.map(d => (d - t0).toMillis)
      _ <- IO.blocking(proc.destroyForcibly()).attempt.void
    yield assertEquals(
      alive,
      0L,
      s"all descendants must be dead within 3s of the kill (took ${elapsed}ms, $alive still alive)"
    ))
  }

end ProcessTreeSpec
