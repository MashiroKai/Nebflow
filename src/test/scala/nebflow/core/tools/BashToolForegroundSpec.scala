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

end BashToolForegroundSpec
