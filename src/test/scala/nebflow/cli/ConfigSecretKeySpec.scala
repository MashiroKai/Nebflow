package nebflow.cli

import munit.FunSuite

/**
 * `stand config show` 的凭据键谓词覆盖（2026-11-04 两小件批 · 件一）。
 *
 * D6 的本意是「配置文件整树回显时把凭据位打码」，但旧谓词是
 * `apikey|secret|password|passwd|credential` + 单数 token 首尾判定 ——
 * `authorization` / `deviceToken` 之外的 `clientSecret` 变体（`client_secret`
 * 归一后含 secret，命中）/ `Authorization` 头名 / `refreshToken` 家族各有漏项，
 * 而**同一个语义**在日志腿、隧道腿、MCP 卡面腿又各写了一份。本 spec 把八个键
 * 全族钉在这一腿上，并与配额类键（`maxTokens` / `budgetTokens`）划清界限。
 */
class ConfigSecretKeySpec extends FunSuite:

  private val AllKeys =
    List("deviceToken", "accessToken", "refreshToken", "token", "clientSecret", "apiKey", "password", "authorization")

  test("ConfigCommand.isSecretKey: every credential key is sensitive") {
    val misses = AllKeys.filterNot(ConfigCommand.isSecretKey)
    assertEquals(misses, Nil, s"这些键没被当成凭据：$misses")
  }

  test("ConfigCommand.isSecretKey: underscore / hyphen / case variants are sensitive") {
    val variants = List("device_token", "device-token", "DEVICE_TOKEN", "X-Api-Key", "client_secret", "Authorization")
    val misses = variants.filterNot(ConfigCommand.isSecretKey)
    assertEquals(misses, Nil, s"这些变体键没被当成凭据：$misses")
  }

  test("ConfigCommand.isSecretKey: quota keys stay visible (not credentials)") {
    val quotas = List("maxTokens", "budgetTokens", "thinkingConfig.budgetTokens", "contextWindow", "syncIntervalSec")
    val hits = quotas.filter(ConfigCommand.isSecretKey)
    assertEquals(hits, Nil, s"这些非凭据键被误当成凭据：$hits")
  }

end ConfigSecretKeySpec
