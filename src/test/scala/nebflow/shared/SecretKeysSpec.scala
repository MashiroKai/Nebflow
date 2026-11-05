package nebflow.shared

import munit.FunSuite
import nebflow.core.{GateRedact, LlmLogWriter}

/**
 * 凭据键单一真源 [[SecretKeys]] 的契约钉（2026-11-04 两小件批 · 件一）。
 *
 * 两半判据：
 *  1. **覆盖**：八个键全族（含下划线 / 短横线 / 大小写变体）在真源里都是凭据位，
 *     文本腿把值换成掩码（正向：真源自证）；
 *  2. **不误伤**：一段真实形态的普通日志（键名不含敏感词根、含 `maxTokens` 这类
 *     配额键、含 sha 十六进制串、含已掩码的 `Bearer <redacted>`）过一遍文本腿必须
 *     **逐字不变**，并打印 `diff=0` 的读数（本 spec 的 println 就是证据读数，
 *     落进 sbt 日志即取证）。
 *
 * 四条腿（日志 / 隧道 / CLI / MCP 卡面）各自的覆盖断言在
 * `SecretMaskingCoverageSpec` / `DeviceTokenMaskingSpec` / `ConfigSecretKeySpec`；
 * 本 spec 只钉真源本身 + 两条同包可及腿的一致性。
 */
class SecretKeysSpec extends FunSuite:

  private val FakeDeviceToken = "9f2c4e7a1b3d5068ca29e4f7b60d1385aa7c2e9f"
  private val FakeApiKey = "sk-fake0000000000000000000000000000"

  private val AllKeys = List(
    "deviceToken",
    "accessToken",
    "refreshToken",
    "token",
    "clientSecret",
    "apiKey",
    "password",
    "authorization"
  )

  private val Variants = List(
    "deviceToken" -> "device_token",
    "deviceToken" -> "device-token",
    "deviceToken" -> "DEVICE_TOKEN",
    "deviceToken" -> "DeviceToken",
    "apiKey" -> "x-api-key",
    "clientSecret" -> "client_secret",
    "authorization" -> "Authorization"
  )

  test("canonical list: the eight credential keys are all sensitive") {
    val misses = AllKeys.filterNot(SecretKeys.isSensitive)
    assertEquals(misses, Nil, s"真源漏了这些键：$misses")
  }

  test("canonical list: underscore / hyphen / case variants normalize to the same root") {
    val misses = Variants.collect { case (a, b) if !SecretKeys.isSensitive(b) => (a, b) }
    assertEquals(misses, Nil, s"这些变体没归一：$misses")
  }

  test("quota exemption: maxTokens / budgetTokens / tokensUsed are NOT credentials") {
    val quotas = List("maxTokens", "budgetTokens", "tokensUsed", "promptTokens", "maxOutputTokens", "contextTokens")
    val hits = quotas.filter(SecretKeys.isSensitive)
    assertEquals(hits, Nil, s"这些配额键被误判成凭据：$hits")
  }

  test("text leg: a fake deviceToken is masked (the leak shape from the avatar batch)") {
    val config = s"""{"neblinkServer":{"url":"https://link.example.space","deviceToken":"$FakeDeviceToken"}}"""
    val out = SecretKeys.redactText(config)
    assert(!out.contains(FakeDeviceToken), s"deviceToken 值仍可见：$out")
    assert(out.contains("<redacted>"), s"必须留下掩码痕迹：$out")
    assert(out.contains("link.example.space"), s"非凭据字段必须逐字保留：$out")
    println(s"[item1] fake-deviceToken masked: raw-visible=false masked-visible=true len ${config.length} -> ${out.length}")
  }

  test("text leg: fake apiKey / clientSecret / password / authorization are masked") {
    val body =
      s"""{"apiKey":"$FakeApiKey","clientSecret":"$FakeDeviceToken","password":"$FakeDeviceToken"}"""
    val out = SecretKeys.redactText(body)
    assert(!out.contains(FakeDeviceToken), s"clientSecret/password 值仍可见：$out")
    assert(!out.contains(FakeApiKey), s"apiKey 值仍可见：$out")
    val hdr = SecretKeys.redactText(s"Authorization: Bearer $FakeDeviceToken")
    assert(!hdr.contains(FakeDeviceToken), s"Authorization 头值仍可见：$hdr")
  }

  test("no over-mask: an ordinary log must be byte-for-byte unchanged (diff = 0)") {
    val ordinary =
      """2026-11-04T13:25:41.002 INFO  nebflow.neblink.relay - relay tunnel up: endpoint wss://link.example.space/api/device/relay-ws
        |2026-11-04T13:25:41.140 DEBUG nebflow.gateway.avatar - avatar sha256=3f79c1a2e5b84d6091c7fa3e2b8d64a1f09c7e5d2b3a68f4c1e0d9b7a5c3e2f1 bytes=8123
        |2026-11-04T13:25:41.500 INFO  nebflow.llm - request budget: maxTokens=4096 budgetTokens=12000 tokensUsed=317
        |2026-11-04T13:25:42.010 WARN  nebflow.neblink.credential - [credential-missing] read at attempt 3 of 5, backoff 1500ms
        |2026-11-04T13:25:42.700 INFO  nebflow.gateway - Header already sanitized upstream: Authorization: <redacted> <redacted>
        |""".stripMargin
    val out = SecretKeys.redactText(ordinary)
    val diff = ordinary.zip(out).count { case (a, b) => a != b } + (ordinary.length - out.length).abs
    assertEquals(out, ordinary, s"普通日志被误伤（diff=$diff）")
    println(s"[item1] ordinary-log diff=$diff chars=${ordinary.length}")
  }

  test("legs agree with the single source (text leg + MCP face predicate)") {
    for k <- AllKeys do
      assertEquals(LlmLogWriter.redactSecrets(s"""{"$k":"$FakeDeviceToken"}""").contains(FakeDeviceToken), false, k)
      assertEquals(GateRedact.isSecretKey(k), SecretKeys.isSensitive(k), s"腿与真源不一致：$k")
  }

  test("idempotent: redacting twice equals redacting once") {
    val once = SecretKeys.redactText(s"""{"deviceToken":"$FakeDeviceToken"} Authorization: Bearer $FakeDeviceToken""")
    assertEquals(SecretKeys.redactText(once), once)
  }

end SecretKeysSpec
