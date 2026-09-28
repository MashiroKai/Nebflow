package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import scala.concurrent.duration.*

/**
 * Issue #17 regression: the background stuck detector must not false-kill a
 * redirected-output job whose work happens in a CHILD process.
 *
 * Shape (sbt run > log 2>&1 in miniature): the direct bash process is idle
 * (waiting on its child) and the PIPE carries zero output (everything goes
 * to a file) — but the process TREE is burning CPU in the grandchild.
 * Pre-fix the detector sampled only the direct process → "no output AND no
 * CPU" → false kill at ~30s+sample. Post-fix sampleProcessCpuTime sums the
 * whole tree (ProcessHandle descendants + ps fallback) → busy tree survives.
 *
 * Observed live on the pre-fix host (2026-08-20 10:04): the exact issue #17
 * error — "Command produced no output within 30 seconds and no CPU activity
 * was detected" — while /tmp/issue17.log showed the gateway booting fine.
 */
class ShellStuckDetectorSpec extends FunSuite:

  // The burner runs ~45s by design; munit's default per-test timeout is 30s.
  override def munitTimeout: FiniteDuration = 120.seconds

  test("#17 background job with redirected stdout + busy child survives the stuck detector") {
    val io = for
      session <- ShellSession.forSession(s"stuck17-${java.util.UUID.randomUUID().toString.take(6)}")
      logFile = s"/tmp/issue17-shape-${java.util.UUID.randomUUID().toString.take(6)}.log"
      // Shape: execute's own bash (DIRECT process) waits idle on its child;
      // the burner is a GRANDCHILD burning CPU ~45s (> 30s grace + sample
      // window) with ALL output redirected to a file — the pipe the detector
      // watches stays empty until the final MARKER echo.
      //
      // Burner = perl time-bounded busy loop (qa 打回实录): shell-variable
      // loops died here twice — `$end`/`$SECONDS` pre-expand at every
      // double-quoted parsing layer (middle bash expands the unset var →
      // grandchild exits ~30ms on `[ N -lt ]` → spec passes vacuously on ANY
      // detector). A single-quoted `perl -e` body has NO shell layer between
      // the quotes and the $ — structurally immune to that class. The
      // elapsed guard below fails loud if the burner ever degenerates again.
      cmd =
        s"""perl -e 'my $$t=time()+45; while(time()<$$t){}' > $logFile 2>&1; echo SHAPE_DONE_MARKER"""
      // isBackground=true arms the stuck detector; timeout is the safety net
      t0 <- IO(System.currentTimeMillis())
      result <- session.execute(cmd, 90.seconds, isBackground = true).attempt
      elapsedMs <- IO(System.currentTimeMillis() - t0)
      _ <- IO(os.remove.all(os.Path(logFile))).handleError(_ => ())
    yield (result, elapsedMs)

    val (result, elapsedMs) = io.unsafeRunSync()
    // Self-guard: the busy loop MUST actually burn (~45s). A faster completion
    // means the quoting degenerated and the loop never ran — fail loud.
    assert(
      clue(elapsedMs) >= 40_000L,
      s"job finished in ${elapsedMs}ms (<40s) — either quoting degenerated or the detector killed the tree; result=${result.toString.take(200)}"
    )
    result match
      case Right(pr) =>
        assert(
          clue(pr.stdout).contains("SHAPE_DONE_MARKER"),
          s"job should have run to completion, stdout=${pr.stdout.take(100)}"
        )
      case Left(e) =>
        fail(
          s"background job was killed by the stuck detector (issue #17 false positive): ${e.getClass.getSimpleName}: ${e.getMessage}"
        )
  }

  // ── killruling 批（2026-09-23 裁定 #3）：M3 探测杀**已整块移除** ────────────────
  //
  // 三态判据（任务书 §二.5）：
  //   改前红 = `grep -n "Command produced no output within" shell.scala` 命中 1 行
  //            （M3 的 30s 杀因文案支）——本断言在 baseline `2a0a918b7` 上必红；
  //   改后绿 = 同命令零命中 + detector fiber 源码面消失（下方三条断言）；
  //   变异复红 = 把 M3 块（grace 30s + 2s CPU 采样 → `killProcessTree`）注回 ⇒ 第 1 条转红。
  //
  // 手段 = 源码面静态读（免构建、与上面的行为用例互补）：行为用例证「不误杀」，
  // 本条证「杀器本体不存在」——两者不可互相代替（杀器删掉后，行为用例的绿**变得
  // 平凡**：没有探测器自然不会误杀 ⇒ 需要一条钉住「探测器确实不在」的断言）。
  test("killruling #3: the 30s background stuck detector is GONE (no detector fiber, no 30s kill wording, no orphan prop)") {
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "tools" / "shell.scala")
    assert(
      !src.contains("Command produced no output within"),
      "M3 的 30s 杀因文案支必须已删（改前红：baseline 上本断言命中该串）"
    )
    assert(
      !src.contains("StuckDetectionGracePeriod") && !src.contains("CpuSampleInterval"),
      "M3 的两个专属量（grace 30s / 2s CPU 采样窗）必须随之同删（删 M3 后零消费点）"
    )
    assert(
      !src.contains("stuckFiber"),
      "探测器 fiber 本体必须已删（改前红：baseline 的 `stuckFiber <- (...).start` 与 `stuckFiber.cancel`）"
    )
    // 保留面对照（**非空读**——同时证上三条不是读失败导致的恒真）：
    assert(
      src.contains("CpuActiveThresholdNanos") && src.contains("wasStuck"),
      "保留项必须仍在册：`CpuActiveThresholdNanos`（B1/B2 双条件 + 活动桥消费）与 `wasStuck`（前台 ceiling 消费）"
    )
  }

end ShellStuckDetectorSpec
