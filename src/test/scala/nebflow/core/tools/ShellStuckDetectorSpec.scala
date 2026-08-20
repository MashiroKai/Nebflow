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

end ShellStuckDetectorSpec
