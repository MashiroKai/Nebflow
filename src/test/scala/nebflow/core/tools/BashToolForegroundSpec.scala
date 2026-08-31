package nebflow.core.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.JsonObject
import io.circe.syntax.*
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/**
 * #319 (2026-08-19) + #26 (2026-08-30): BashTool 前台直跑语义——foreground
 * commands run to completion (or explicit timeout), never auto-background.
 *
 * #26 用户裁定「Bash 工具不再自动转后台，依赖卡死检测就行了，不设超时」——
 * #391 机制 A（300s 自动转后台）已删除：前台命令一直跑到完成，返回真实输出，
 * 无「[moved to background]」占位。卡死兜底 = TaskStuckWatcher（turn 级
 * restart）+ 前台 no-progress ceiling（命令级停滞杀）+ 显式 timeout（若有）。
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

  test("foreground command running >60s still completes — mechanism A fully removed (#26)") {
    // 旧 #391 机制 A：300s 阈值，此用例无法在单测时限内证明；#26 删除机制 A
    // 后无转后台路径（静态可证），此用例验证 65s 前台长命令仍直跑完成——
    // 覆盖「任何时长都不转后台」语义的可测下限。
    runBash("sleep 65 && echo long-done-65").map {
      case Right(out) =>
        assert(out.contains("long-done-65"), s"should complete with real output: $out")
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
