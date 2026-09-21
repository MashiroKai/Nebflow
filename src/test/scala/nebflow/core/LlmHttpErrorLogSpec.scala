package nebflow.core

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * 案① A5 红验锚点（LLM 硬杀波 · `chain-llmstall-fix`，2026-09-21 作者令「随案① 落地
 * 带上」）——**4xx provider 响应体的常驻取证面**。
 *
 * 动因（定谳报告 `20260921_182544` 核查 3）：事故当日 11,102 条
 * `permanent error (Format)` 的**响应体原文缺失**（LLM 请求/响应日志器默认关，
 * `LlmLogWriter.DefaultEnabled = false`）⇒「`enable_search` 被端点拒绝 / 模型名不存在 /
 * 请求体超端点限制」三条成因无法区分。本 spec 钉三件事：
 *
 *   ① **豁免 `enabled`**：`enabled = false`（默认关）时 4xx 仍落盘——否则取证面等于没有；
 *   ② **只对 4xx**：2xx / 5xx 一行不写（本腿只负责「请求形状 vs 契约」类故障）；
 *   ③ **隐私面**：响应体里的凭据形态（Bearer / apiKey 回显 / sk- key）必须被抹除——
 *      且 `logHttpError` 的签名里**没有**请求头 / 凭据参数（结构性保证）。
 */
class LlmHttpErrorLogSpec extends CatsEffectSuite:

  private var tmp: Path = null
  // 快照**必须**在进场时（beforeAll）取，禁用懒初始化：`lazy val` 的求值点最早是
  // afterAll，那时 T2 已把开关置 true ⇒ 快照取到「跑完后的值」⇒ 出场「恢复」成
  // true，把 enabled=true 泄漏给同 JVM 的后续 suite（`Test / parallelExecution :=
  // false` ⇒ 顺序执行，泄漏直达下游）。与 LogWriterHomeIsolationSpec:41-49 同款。
  private var wasEnabled: Boolean = true

  override def beforeAll(): Unit =
    // 豁免门 ⇒ 本腿在 enabled=false 下也写盘 ⇒ 必须重定向，否则 sbt test 会把 400
    // 响应体写进生产 home（与 TaskStuckWatcherSpec / LogWriterHomeIsolationSpec 同款纪律）。
    wasEnabled = LlmLogWriter.isEnabled
    tmp = Files.createTempDirectory("llm-httperror-log-")
    LlmLogWriter.setLogDirForTest(tmp)

  override def afterAll(): Unit =
    // 本 spec 改过进程级 enabled 开关——不恢复会污染同 JVM 内的后续 spec。
    LlmLogWriter.setEnabled(wasEnabled)
    LlmLogWriter.resetLogDirForTest()
    if tmp != null then
      Files.walk(tmp).iterator().asScala.toList.sortBy(_.getNameCount).reverse.foreach(p => Files.deleteIfExists(p))

  private def lines(): List[String] =
    Files.list(tmp).iterator().asScala.toList
      .filter(_.getFileName.toString.endsWith("_httperror.jsonl"))
      .flatMap(p => Files.readAllLines(p).asScala.toList)

  test("T1: enabled=false 时 4xx 响应体仍落盘（豁免门），且带状态码 / 关联 id") {
    LlmLogWriter.setEnabled(false)
    val body = """{"type":"error","error":{"message":"enable_search is not supported by this endpoint"}}"""
    for
      _ <- LlmLogWriter.logHttpError(
        statusCode = 400,
        body = body,
        requestId = "req-1",
        sessionId = "node-t1",
        agentId = "a1",
        providerId = "qwen",
        model = "qwen3.8-max"
      )
      ls <- IO(lines())
    yield
      assertEquals(ls.size, 1, s"4xx 必须落一行（豁免 enabled=false），实际 ${ls.size} 行")
      val line = ls.head
      assert(line.contains("\"status_code\":400"), line)
      assert(line.contains("enable_search is not supported"), s"响应体原文必须保留：$line")
      assert(line.contains("\"provider\":\"qwen\""), line)
      assert(line.contains("\"request_id\":\"req-1\""), line)
  }

  test("T2: 非 4xx（2xx / 5xx）一行不写") {
    LlmLogWriter.setEnabled(true) // 即使开关打开也不写——本腿只对 4xx 负责
    for
      _ <- LlmLogWriter.logHttpError(200, "ok", "req-2", "s", "a", "p", "m")
      _ <- LlmLogWriter.logHttpError(500, "server exploded", "req-3", "s", "a", "p", "m")
      ls <- IO(lines())
    yield assertEquals(ls.size, 1, s"2xx/5xx 不得新增行，实际 ${ls.size} 行")
  }

  test("T3: 隐私面——响应体里的凭据形态必须被抹除（禁落 apiKey / Authorization）") {
    val dirty =
      """{"error":"invalid key: sk-abcdefghijklmnop1234","sent":{"apiKey":"sk-live-SECRETSECRETSECRET",""" +
        """"Authorization":"Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"}}"""
    for
      _ <- LlmLogWriter.logHttpError(401, dirty, "req-4", "s", "a", "p", "m")
      ls <- IO(lines())
    yield
      val line = ls.mkString("\n")
      assert(!line.contains("sk-abcdefghijklmnop1234"), s"sk- key 未抹除：$line")
      assert(!line.contains("sk-live-SECRETSECRETSECRET"), s"apiKey 回显未抹除：$line")
      assert(!line.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"), s"Bearer token 未抹除：$line")
      assert(line.contains("<redacted>"), s"必须留下抹除痕迹（可读性）：$line")
  }

  test("T4: 纯函数 redactSecrets 的二值读数") {
    assertEquals(LlmLogWriter.redactSecrets("""{"apiKey":"sk-1234567890abcd"}"""), """{"apiKey":"<redacted>"}""")
    assertEquals(LlmLogWriter.redactSecrets("Authorization: Bearer abcdefghijklmn"), "Authorization: Bearer <redacted>")
    assertEquals(LlmLogWriter.redactSecrets("plain error text"), "plain error text")
    assertEquals(LlmLogWriter.redactSecrets(""), "")
  }
end LlmHttpErrorLogSpec
