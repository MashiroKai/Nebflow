package nebflow.core.mcp

import cats.effect.IO
import cats.effect.testkit.TestControl
import io.circe.{Json, JsonObject}
import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.llm.McpServerConfig

import scala.concurrent.duration.*

/**
 * R3（wait-timeout-fix，2026-09-03 作者裁定②）：MCP 工具超时与普通工具统一。
 *
 * 审计 20260903 根因：McpClient.callTool 一刀切 `.timeout(120.seconds)` 硬顶
 * 包住整个 tools/call——浏览器自动化/深度检索/长仿真 120s 必死，与内置工具
 * 「领域自治 + 会话级 TaskStuckWatcher 兜底」语义完全不同。
 *
 * 修复语义：
 *   - 未配上限 = 无超时（tools/call 跑到返回；tools/list 的 30s 基础设施
 *     探测保留不动）；
 *   - mcp.json per-server 可选 `timeoutMs`：配了 → 该 server 工具受控，
 *     到限报错语义与现状一致（TimeoutException → ToolError "Error: …"）。
 *
 * 时间模拟手法（零真实等待）：cats-effect TestControl 虚拟时钟——「工具跑
 * 125s（> 旧 120s 硬顶）」被虚拟推进到毫秒级真实时间。验红变异：还原
 * McpClient.callTool 的 `.timeout(120.seconds)` 硬顶 → 未配上限用例在虚拟
 * 120s 被掐断 → 红；恢复后绿。
 */
class McpCallTimeoutSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  /** 慢响应传输桩：send 固定 delay 后返回成功响应（虚拟时钟下 delay 可为
    * 分钟级，真实耗时毫秒级）。 */
  private class SlowTransport(delay: FiniteDuration) extends McpTransport:
    def send(request: JsonRpcRequest): IO[JsonRpcResponse] =
      IO.sleep(delay).as(
        JsonRpcResponse(
          id = request.id,
          result = Some(Json.obj("content" -> Json.arr(Json.obj("text" -> Json.fromString("slow-but-fine")))))
        )
      )
    def sendNotification(notification: JsonRpcNotification): IO[Unit] = IO.unit
    def onNotification(handler: JsonRpcNotification => IO[Unit]): IO[Unit] = IO.unit
    def close(): IO[Unit] = IO.unit

  private def callTool(client: McpClient): IO[String] =
    client.callTool("long_tool", JsonObject("target" -> Json.fromString("x").asJson))

  // ============================================================
  // 验收 3 前半：未配上限 → 跨过旧 120s 硬顶（虚拟 125s）正常完成不超时
  // ============================================================

  test("R3 验收3: 未配上限的 server，工具跑 125s（虚拟 > 旧 120s 硬顶）→ 正常返回不超时") {
    val client = McpClient("slow-server", SlowTransport(125.seconds), callTimeout = None)
    val program = callTool(client).timed
    TestControl.executeEmbed(program).map { case (elapsed, result) =>
      assertEquals(result, "slow-but-fine", "跨过旧 120s 硬顶的调用必须拿到真实结果（而非 Error: timeout）")
      assert(elapsed >= 125.seconds, s"虚拟耗时应 ≥125s（证明确实跑过了旧硬顶区间），got ${elapsed}")
    }
  }

  // ============================================================
  // 验收 3 后半：mcp.json 配了上限 → 仍受控，到限报错
  // ============================================================

  test("R3 验收3: 配了 timeoutMs=1s 的 server，工具跑 5s（虚拟）→ 到限报错（语义与现状一致）") {
    val client = McpClient("capped-server", SlowTransport(5.seconds), callTimeout = Some(1.second))
    val program = for
      start <- IO.monotonic
      result <- callTool(client).attempt
      end <- IO.monotonic
    yield (end - start, result)
    TestControl.executeEmbed(program).map { case (elapsed, result) =>
      result match
        case Right(tooLucky) => fail(s"配置了上限必须到限报错，却返回了: $tooLucky")
        case Left(_) =>
          assert(elapsed >= 1.second && elapsed < 5.seconds,
            s"到限即报（≈1s），不是等调用自然结束（5s），got ${elapsed}")
    }
  }

  test("R3 语义保留: tools/list 的 30s 基础设施探测超时不动（审计明确保留项）") {
    // listTools 走独立的固定 30s——与本修复无关，这里钉住它没被误删
    val src = os.read(os.pwd / "src" / "main" / "scala" / "nebflow" / "core" / "mcp" / "McpClient.scala")
    assert(src.contains(".timeout(30.seconds)"), "tools/list 30s 探测超时必须保留")
    assert(!src.contains(".timeout(120.seconds)"), "callTool 的一刀切 120s 硬顶必须已移除")
  }

  // ============================================================
  // mcp.json 字段设计：timeoutMs 可选、缺省 None、向后兼容
  // ============================================================

  test("R3 mcp.json 字段: timeoutMs 可选解码——配了生效、缺省 None、存量配置零改动") {
    val withTimeout = decode[McpServerConfig]("""{"command":"uvx","args":["foo"],"timeoutMs":45000}""")
    assertEquals(withTimeout.map(_.timeoutMs), Right(Some(45000L)), "配置 timeoutMs=45000 必须解码为 Some(45000)")

    val withoutTimeout = decode[McpServerConfig]("""{"command":"uvx","args":["foo"]}""")
    assertEquals(withoutTimeout.map(_.timeoutMs), Right(None), "未配置 timeoutMs 必须解码为 None（= 无超时）")

    val legacy = decode[McpServerConfig]("""{"url":"http://localhost:3000","headers":{"k":"v"},"enabled":false}""")
    assert(legacy.isRight, "存量 mcp.json 配置必须零改动继续解码")
    assertEquals(legacy.map(_.timeoutMs), Right(None))
  }

end McpCallTimeoutSpec
