package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * #319 (2026-08-19): BashTool 取消自动超时转后台 — foreground commands run to
 * completion (or explicit timeout), no auto-background at 30s/300s.
 *
 * 旧行为：`sleep 35` 前台跑到 30s 会被 "moved to background"，返回占位消息。
 * 新行为：前台命令一直跑到完成，返回真实输出。
 */
class BashToolForegroundSpec extends CatsEffectSuite:

  /** sleep 35 用例需要 >30s 的框架超时（munit 默认 30s）。 */
  override def munitIOTimeout: Duration = 90.seconds

  private def runBash(cmd: String): IO[Either[ToolError, String]] =
    val input = JsonObject(
      "command" -> cmd.asJson,
      "description" -> "BashToolForegroundSpec".asJson
    )
    BashTool.call(input, ToolContext(projectRoot = "/tmp")).timeout(80.seconds)

  test("foreground command returns real output (no auto-background)") {
    runBash("sleep 2 && echo hello-foreground").map {
      case Right(out) =>
        assert(out.contains("hello-foreground"), s"should contain command output: $out")
        assert(!out.contains("moved to background"), s"must not auto-background: $out")
      case Left(err) => fail(s"bash failed: ${err.message}")
    }
  }

  test("foreground command running >30s completes instead of moving to background (#319)") {
    runBash("sleep 35 && echo long-done").map {
      case Right(out) =>
        assert(out.contains("long-done"), s"should complete with real output: $out")
        assert(!out.contains("moved to background"), s"must not auto-background: $out")
      case Left(err) => fail(s"bash failed: ${err.message}")
    }
  }

  test("explicit timeout still kills a long command") {
    val input = JsonObject(
      "command" -> "sleep 10".asJson,
      "description" -> "timeout-test".asJson,
      "timeout" -> 3000.asJson
    )
    BashTool.call(input, ToolContext(projectRoot = "/tmp")).map {
      case Left(ToolError(msg)) =>
        assert(msg.contains("timed out"), s"should report timeout: $msg")
      case Right(out) => fail(s"expected timeout error, got: $out")
    }
  }

  // ── #22 (2026-08-19 20:35 incident): zombie timeout class ────────────────
  // IO.timeout over IO.blocking stream readers is SOFT — it cannot interrupt
  // a blocked read; the TimeoutException only surfaces once every pipe holder
  // exits. A 10-min timeout ran 37 minutes in production because orphaned
  // grandchildren kept the pipes open. The poll-based reader + watchdog kill
  // now bound the return time: orphan pipe → prompt normal result (parent
  // exited); live tree → prompt timeout (watchdog kill).

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

  test("timeout kills the whole process tree — parent waiting on child (#22)") {
    val input = JsonObject(
      "command" -> "sleep 120 & wait".asJson, // parent stays alive waiting on the child
      "description" -> "tree-kill-timeout".asJson,
      "timeout" -> 3000.asJson
    )
    val start = System.currentTimeMillis()
    BashTool.call(input, ToolContext(projectRoot = "/tmp")).map {
      case Left(ToolError(msg)) =>
        assert(msg.contains("timed out"), s"should report timeout: $msg")
        val elapsed = (System.currentTimeMillis() - start) / 1000
        assert(elapsed < 30, s"tree kill must surface promptly, took ${elapsed}s")
      case Right(out) => fail(s"expected timeout error, got: $out")
    }
  }

end BashToolForegroundSpec
