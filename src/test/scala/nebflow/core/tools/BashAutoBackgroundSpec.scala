package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * bashautobg batch (2026-09-25 author order: "the Bash tool's hard cap should
 * not exist; auto-move to background at 60min; backgrounded commands have no
 * limit").
 *
 * Legs:
 *  - A-1 explicit wait budget: a still-running foreground command auto-backgrounds
 *    at the budget (receipt with job id, process NOT killed, result delivered on
 *    completion through the background query face). The explicit `timeout` and the
 *    default 60min threshold share the single waitBudgetMs path, so compressing
 *    the budget via the explicit parameter exercises the identical conversion
 *    machinery without touching the global prop (zero cross-suite interference).
 *  - A-5 default-threshold arm plumbing: MAX_TIMEOUT reads the compressible prop
 *    (`nebflow.bash.maxTimeoutMs`) at every call, so the default arm (no explicit
 *    timeout) gets its budget from the same adjustable source.
 *  - A-2 background no time limit: a waiting-type background task survives past
 *    the old 30min/4h hard cap (BashResilienceConfig with compressed values is
 *    injected into the ToolContext; under the old wiring the task would be killed
 *    at cap + stuck window with a TimeoutException).
 *  - A-3/A-4 adoption mechanism at the ShellSession level: deferred + on_complete
 *    fire with the real output; explicit cancel kills the still-running tree.
 */
class BashAutoBackgroundSpec extends CatsEffectSuite:

  override def munitIOTimeout: Duration = 120.seconds

  private val ThresholdProp = "nebflow.bash.maxTimeoutMs"

  /** Set the threshold prop for the duration of `io`, always cleared after. */
  private def withThreshold[A](ms: Long)(io: IO[A]): IO[A] =
    IO(System.setProperty(ThresholdProp, ms.toString)).flatMap(_ => io.guarantee(IO(System.clearProperty(ThresholdProp))))

  private def jobIdOf(receipt: String): String =
    """Job ID: (\w+)""".r.findFirstMatchIn(receipt).map(_.group(1)).getOrElse(fail(s"no job id in receipt: $receipt"))

  /** Query a job through the tool face (same face the model uses). */
  private def query(jobId: String): IO[String] =
    BashTool.call(JsonObject("background_job_id" -> jobId.asJson), ToolContext(projectRoot = "/tmp")).map {
      case Right(out) => out
      case Left(e)    => fail(s"background query failed: ${e.message}")
    }

  /** Poll the query face until `want` appears or the deadline passes. */
  private def pollUntil(jobId: String, want: String, deadline: FiniteDuration): IO[String] = {
    def loop: IO[String] =
      query(jobId).flatMap { out =>
        if out.contains(want) then IO.pure(out) else IO.sleep(400.millis) *> loop
      }
    loop.timeout(deadline)
  }

  /** Poll the session face until the job completes (consumes the result). */
  private def pollCompleted(shell: ShellSession, jobId: String, deadlineMs: Long): IO[Either[Throwable, ProcessResult]] = {
    def loop: IO[Either[Throwable, ProcessResult]] =
      shell.getBackgroundResult(jobId).flatMap {
        case Some(res) => IO.pure(res)
        case None      => IO.sleep(300.millis) *> loop
      }
    loop.timeout(deadlineMs.millis)
  }

  test("A-1 explicit wait budget auto-backgrounds a still-running command (receipt + job id, process alive, result delivered on completion)") {
    val input = JsonObject(
      "command" -> "sleep 10 && echo auto-bg-done".asJson,
      "description" -> "auto-bg-budget".asJson,
      "timeout" -> 2000.asJson
    )
    (for
      result <- BashTool.call(input, ToolContext(projectRoot = "/tmp"))
      receipt <- IO {
        result match
          case Right(out) =>
            assert(out.contains("[Background job started]"), s"receipt face at the budget: $out")
            assert(out.contains("Auto-backgrounded"), s"auto-background note: $out")
            out
          case Left(e) => fail(s"the budget must NOT kill anymore, got error: ${e.message}")
      }
      jobId = jobIdOf(receipt)
      _ <- (for
        health <- query(jobId)
        _ <- IO(assert(health.contains("[Background job running]"), s"process must still be alive right after conversion: $health"))
        completed <- pollUntil(jobId, "[Background job completed]", 30.seconds)
        _ <- IO(assert(completed.contains("auto-bg-done"), s"output retrievable after completion: $completed"))
      yield ()).guarantee(BgTaskRegistry.unregister(jobId).attempt.void)
    yield ())
  }

  test("A-5 default threshold arm: MAX_TIMEOUT reads the compressible prop per call (default-arm plumbing)") {
    withThreshold(2000L) {
      IO(assert(BashTool.MAX_TIMEOUT == 2000L, s"MAX_TIMEOUT must read the prop per call, got ${BashTool.MAX_TIMEOUT}"))
    } *> IO(assert(BashTool.MAX_TIMEOUT == nebflow.shared.Defaults.BashMaxTimeoutMs, "cleared prop must fall back to the default"))
  }

  test("A-2 waiting-type background task survives past the old 30min/4h hard cap (compressed config proves the wiring exemption)") {
    val bgCtx = ToolContext(
      projectRoot = "/tmp",
      bashConfig = nebflow.shared.BashResilienceConfig(hardTimeoutMs = 1000, stuckWindowSec = 1, healthCheckIntervalSec = 1)
    )
    val input = JsonObject(
      "command" -> "sleep 8 && echo bg-unlimited-done".asJson,
      "description" -> "bg-no-cap".asJson,
      "run_in_background" -> true.asJson
    )
    (for
      result <- BashTool.call(input, bgCtx)
      receipt <- IO {
        result match
          case Right(out) => out
          case Left(e)    => fail(s"background start failed: ${e.message}")
      }
      jobId = jobIdOf(receipt)
      _ <- (for
        // Old wiring: killed at ~cap(1s) + stuck window(1s) with a TimeoutException.
        // New wiring: still running well past that point.
        _ <- IO.sleep(4.seconds)
        health <- query(jobId)
        _ <- IO(assert(health.contains("[Background job running]"), s"bg task must survive past the old cap: $health"))
        completed <- pollUntil(jobId, "[Background job completed]", 30.seconds)
        _ <- IO(assert(completed.contains("bg-unlimited-done"), s"ran to completion, output retrievable: $completed"))
      yield ()).guarantee(BgTaskRegistry.unregister(jobId).attempt.void)
    yield ())
  }

  test("A-3 adopted foreground execution: deferred + on_complete fire with the real output; health face same as native bg") {
    val got = new AtomicReference[Option[Either[Throwable, ProcessResult]]](None)
    for
      shell <- ShellSession.forSession("auto-bg-adopt")
      _ <- (for
        health <- IO(new JobHealth())
        fiber <- shell.execute("sleep 2 && echo adopted-done", 365.days, Some(health)).attempt.start
        _ <- IO.sleep(300.millis) // adopt while the command is still running
        _ <- shell.adoptAsBackgroundJob(
          "adopt1",
          fiber,
          health,
          "sleep 2 && echo adopted-done",
          on_complete = Some(res => IO(got.set(Some(res))))
        )
        h0 <- shell.getBackgroundJobHealth("adopt1")
        _ <- IO(assert(h0.exists(_.isAlive), s"adopted job must be alive while running: $h0"))
        res <- pollCompleted(shell, "adopt1", 20000)
        _ <- IO {
          res match
            case Right(pr) => assert(pr.stdout.contains("adopted-done"), s"output retrievable via the query face: ${pr.stdout}")
            case Left(e)   => fail(s"expected success, got: ${e.getMessage}")
          assert(got.get().exists(_.isRight), s"on_complete must fire with the real result: ${got.get()}")
        }
      yield ()).guarantee(ShellSession.destroySession("auto-bg-adopt").attempt.void)
    yield ()
  }

  test("A-4 adopted job explicit cancel kills the still-running process tree") {
    for
      shell <- ShellSession.forSession("auto-bg-cancel")
      _ <- (for
        health <- IO(new JobHealth())
        fiber <- shell.execute("sleep 30", 365.days, Some(health)).attempt.start
        _ <- IO.sleep(300.millis)
        _ <- shell.adoptAsBackgroundJob("adoptc", fiber, health, "sleep 30")
        h0 <- shell.getBackgroundJobHealth("adoptc")
        _ <- IO(assert(h0.exists(_.isAlive), s"alive before cancel: $h0"))
        cancelled <- shell.cancelBackgroundJob("adoptc")
        _ <- IO(assert(cancelled, "cancel must find and cancel the adopted job"))
        h1 <- shell.getBackgroundJobHealth("adoptc")
        _ <- IO(assert(h1.isEmpty, s"job removed after cancel: $h1"))
        proc = health.processRef.get()
        _ <- IO.sleep(500.millis)
        _ <- IO(assert(proc == null || !proc.isAlive, s"process tree must be killed by cancel: alive=${proc != null && proc.isAlive}"))
      yield ()).guarantee(ShellSession.destroySession("auto-bg-cancel").attempt.void)
    yield ()
  }

end BashAutoBackgroundSpec
