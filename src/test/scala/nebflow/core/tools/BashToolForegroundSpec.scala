package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * #319 (2026-08-19) + #26 (2026-08-30) + bashautobg (2026-09-25 author order):
 * foreground semantics. Historical note: #26 removed the old 300s auto-background
 * ("mechanism A") and made explicit timeouts kill. The 2026-09-25 author order
 * supersedes the time-kill half: there is NO time-based kill anymore — the
 * explicit `timeout` and the default 60min threshold are a foreground WAIT
 * BUDGET; a still-running command crossing it is auto-backgrounded (receipt with
 * a job id) and runs to completion (see BashAutoBackgroundSpec). Short commands
 * still complete in the foreground exactly as before; the only foreground kill
 * remains the no-progress ceiling (stall watchdog).
 */
class BashToolForegroundSpec extends CatsEffectSuite:

  /** sleep 65 用例需要 >65s 的框架超时（munit 默认 30s）。 */
  override def munitIOTimeout: Duration = 120.seconds

  private def runBash(cmd: String): IO[Either[ToolError, String]] =
    val input = JsonObject(
      "command" -> cmd.asJson,
      "description" -> "BashToolForegroundSpec".asJson
    )
    BashTool.call(input, ToolContext(projectRoot = "/tmp")).timeout(110.seconds)

  /** Query a background job through the tool face (same face the model uses). */
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

  test("short foreground command returns real output (no conversion below the budget)") {
    runBash("sleep 2 && echo hello-foreground").map {
      case Right(out) =>
        assert(out.contains("hello-foreground"), s"should contain command output: $out")
        assert(!out.contains("moved to background"), s"must not auto-background: $out")
      case Left(err) => fail(s"bash failed: ${err.message}")
    }
  }

  test("foreground command running >30s completes in the foreground (well below the 60min budget)") {
    runBash("sleep 35 && echo long-done").map {
      case Right(out) =>
        assert(out.contains("long-done"), s"should complete with real output: $out")
        assert(!out.contains("moved to background"), s"must not auto-background: $out")
      case Left(err) => fail(s"bash failed: ${err.message}")
    }
  }

  test("foreground command running >60s completes in the foreground (budget default 60min far above; no time kill)") {
    runBash("sleep 65 && echo long-done-65").map {
      case Right(out) =>
        assert(out.contains("long-done-65"), s"should complete with real output: $out")
        assert(!out.contains("moved to background"), s"must not auto-background: $out")
      case Left(err) => fail(s"bash failed: ${err.message}")
    }
  }

  test("explicit timeout is a wait budget — long command auto-backgrounds instead of being killed (bashautobg)") {
    val input = JsonObject(
      "command" -> "sleep 10 && echo budget-done".asJson,
      "description" -> "timeout-test".asJson,
      "timeout" -> 3000.asJson
    )
    (for
      result <- BashTool.call(input, ToolContext(projectRoot = "/tmp"))
      receipt <- IO {
        result match
          case Right(out) =>
            assert(out.contains("[Background job started]"), s"budget hit must auto-background: $out")
            assert(out.contains("Job ID:"), s"receipt carries the job id: $out")
            out
          case Left(e) => fail(s"the explicit timeout must NOT kill anymore, got error: ${e.message}")
      }
      jobId = """Job ID: (\w+)""".r.findFirstMatchIn(receipt).map(_.group(1)).getOrElse(fail(s"no job id: $receipt"))
      _ <- (for
        health <- query(jobId)
        _ <- IO(assert(health.contains("[Background job running]"), s"process must be alive past the budget: $health"))
        completed <- pollUntil(jobId, "[Background job completed]", 30.seconds)
        _ <- IO(assert(completed.contains("budget-done"), s"result delivered on completion: $completed"))
      yield ()).guarantee(BgTaskRegistry.unregister(jobId).attempt.void)
    yield ())
  }

  // ── #22 (2026-08-19 20:35 incident): zombie timeout class ────────────────
  // IO.timeout over IO.blocking stream readers is SOFT — it cannot interrupt
  // a blocked read; the TimeoutException only surfaces once every pipe holder
  // exits. A 10-min timeout ran 37 minutes in production because orphaned
  // grandchildren kept the pipes open. The poll-based reader + exit-grace
  // bound the return time: orphan pipe → prompt normal result (parent
  // exited). (bashautobg batch: there is no watchdog time kill anymore —
  // a live tree crossing the budget is auto-backgrounded, not killed.)

  test("orphaned pipe-holder no longer stalls the tool return (#22)") {
    val input = JsonObject(
      "command" -> "sleep 120 & exit 7".asJson, // parent exits; orphan holds stdout
      "description" -> "orphan-pipe-timeout".asJson,
      "timeout" -> 3000.asJson
    )
    val start = System.currentTimeMillis()
    BashTool.call(input, ToolContext(projectRoot = "/tmp")).map { result =>
      // Either outcome is correct — the invariant is BOUNDEDNESS: the tool
      // must not wait for the 120s orphan (old behavior: blocked reads).
      result match
        case Right(out) =>
          assert(out.contains("exit 7"), s"should carry the real exit code: $out")
        case Left(ToolError(msg)) =>
          assert(msg.contains("timed out"), s"unexpected error: $msg")
      val elapsed = (System.currentTimeMillis() - start) / 1000
      assert(elapsed < 30, s"tool must return promptly, took ${elapsed}s (zombie class)")
    }
  }

  test("wait budget keeps the tree alive past the budget; explicit cancel kills it promptly (bashautobg)") {
    val input = JsonObject(
      "command" -> "sleep 30 & wait".asJson, // parent stays alive waiting on the child
      "description" -> "tree-adopt-test".asJson,
      "timeout" -> 2000.asJson
    )
    val start = System.currentTimeMillis()
    (for
      result <- BashTool.call(input, ToolContext(projectRoot = "/tmp"))
      receipt <- IO {
        val elapsed = (System.currentTimeMillis() - start) / 1000
        result match
          case Right(out) =>
            assert(out.contains("[Background job started]"), s"budget hit must auto-background: $out")
            assert(elapsed < 30, s"tool must return at the budget, took ${elapsed}s")
            out
          case Left(e) => fail(s"the budget must NOT kill the tree anymore, got error: ${e.message}")
      }
      jobId = """Job ID: (\w+)""".r.findFirstMatchIn(receipt).map(_.group(1)).getOrElse(fail(s"no job id: $receipt"))
      _ <- (for
        health <- query(jobId)
        _ <- IO(assert(health.contains("[Background job running]"), s"tree must still be alive past the budget: $health"))
        // The agent-side kill handle: explicit cancel must promptly tear the tree down.
        cancel <- BashTool
          .call(
            JsonObject("background_job_id" -> jobId.asJson, "cancel_background_job" -> true.asJson),
            ToolContext(projectRoot = "/tmp")
          )
          .timeout(20.seconds)
        _ <- IO {
          cancel match
            case Right(out) => assert(out.contains("[Background job cancelled]"), s"cancel face: $out")
            case Left(e)    => fail(s"cancel failed: ${e.message}")
        }
      yield ()).guarantee(BgTaskRegistry.unregister(jobId).attempt.void)
    yield ())
  }

end BashToolForegroundSpec
