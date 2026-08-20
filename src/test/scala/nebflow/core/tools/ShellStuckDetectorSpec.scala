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

  test("#17 background job with redirected stdout + busy child survives the stuck detector") {
    val io = for
      session <- ShellSession.forSession(s"stuck17-${java.util.UUID.randomUUID().toString.take(6)}")
      logFile = s"/tmp/issue17-shape-${java.util.UUID.randomUUID().toString.take(6)}.log"
      // Outer bash: idle waiter. Inner bash: burns CPU 45s (> 30s grace +
      // sample window) with ALL output redirected to a file — the pipe the
      // detector watches stays empty the whole time.
      cmd =
        s"""bash -c 'bash -c "end=$$((SECONDS+45)); while [ $$SECONDS -lt $$end ]; do :; done" > $logFile 2>&1; echo SHAPE_DONE_MARKER'"""
      // isBackground=true arms the stuck detector; timeout is the safety net
      result <- session.execute(cmd, 90.seconds, isBackground = true).attempt
      _ <- IO(os.remove.all(os.Path(logFile))).handleError(_ => ())
    yield result

    val result = io.unsafeRunSync()
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
