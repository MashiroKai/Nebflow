package nebflow.core

import munit.FunSuite

/**
 * 凭据脱敏**键覆盖**回归闸（2026-11-04 两小件批 · 件一 · 日志/卡面腿）。
 *
 * 缺陷：仓库里同一件事（「哪些键算凭据」）有四份各自的键表 ——
 * `LlmLogWriter.redactSecrets`（日志文本腿）、`RelayTunnelDiagnostics.redact`
 * （隧道腿）、`ConfigCommand.isSecretKey`（CLI 配置回显腿）、
 * `McpToolGate.GateRedact`（MCP 卡面腿）。四份都不含 `deviceToken` 全族：
 * 头像批读 `~/.stand/neblink/config.json` 做诊断时，文本腿把该键的值原样
 * 回显进了会话（未落盘、未进提交，已 grep 核验）。
 *
 * 本 spec 钉两件事（键表的单一真源 = `nebflow.shared.SecretKeys`）：
 *  1. **覆盖**：`deviceToken` / `accessToken` / `refreshToken` / `token` /
 *     `clientSecret` / `apiKey` / `password` / `authorization` 全族在本腿被掩码，
 *     且键名的下划线 / 短横线 / 大小写变体同效；
 *  2. **不误伤**：不含这些键的普通日志**逐字**不变（全等断言，不是「包含」）。
 */
class SecretMaskingCoverageSpec extends FunSuite:

  /** 高熵假值（32 hex）—— 绝不是真凭据，只为「回显里还能不能看见原文」判读。 */
  private val FakeValue = "4f9a2c7e1b8d3056aa47e2c9f60b1d38"

  private def jsonBody(key: String, value: String = FakeValue): String = s"""{"$key":"$value"}"""

  private val AllKeys =
    List("deviceToken", "accessToken", "refreshToken", "token", "clientSecret", "apiKey", "password", "authorization")

  test("text leg (LlmLogWriter.redactSecrets): every credential key is masked") {
    val leaks = AllKeys.filter(k => LlmLogWriter.redactSecrets(jsonBody(k)).contains(FakeValue))
    assertEquals(leaks, Nil, s"这些键的值被原样回显（未掩码）：$leaks")
  }

  test("text leg: underscore / hyphen / case variants are masked too") {
    val variants = List("device_token", "device-token", "DEVICE_TOKEN", "DeviceToken", "x-api-key", "client_secret")
    val leaks = variants.filter(k => LlmLogWriter.redactSecrets(s"""{"$k": "$FakeValue"}""").contains(FakeValue))
    assertEquals(leaks, Nil, s"变体键未掩码：$leaks")
  }

  test("key predicate (GateRedact.isSecretKey): every credential key is sensitive") {
    val misses = AllKeys.filterNot(GateRedact.isSecretKey)
    assertEquals(misses, Nil, s"这些键没被当成凭据：$misses")
  }

  // ── 不误伤：两个读数（键表放宽 / 收窄都不许让普通行变化）───────────────

  test("no over-mask: an ordinary log line is byte-for-byte unchanged") {
    val plain = """[WARN] nebflow.neblink.credential: [credential-missing] login state read at """ +
      """2026-11-04T13:25:00Z — cause: attempt 3 of 5, backoff 1500ms, peer deviceId dev-7c3dd022"""
    assertEquals(LlmLogWriter.redactSecrets(plain), plain)
  }

  test("no over-mask: quota keys are NOT credentials (maxTokens / budgetTokens)") {
    val quota = """{"maxTokens":4096,"budgetTokens":12000}"""
    assertEquals(LlmLogWriter.redactSecrets(quota), quota)
  }

end SecretMaskingCoverageSpec
