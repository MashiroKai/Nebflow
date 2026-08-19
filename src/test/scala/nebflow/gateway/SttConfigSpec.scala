package nebflow.gateway

import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil

/** #295 STT 前端可配置——setSttConfig 校验/归一 + 配置 round-trip + serverConfig
  * 脱敏。语义（用户 2026-08-18 拍板）：三字段可选、全空=清空（回退浏览器 Web
  * Speech）、endpoint 须 http(s)://、apiKey 原样存（trim 粘贴空格）、永不回传。 */
class SttConfigSpec extends FunSuite:

  private def cfg(json: String): io.circe.Json =
    parse(json).toOption.get

  // ── validateConfig：校验与归一 ────────────────────────────

  test("validateConfig: full valid payload keeps all three fields") {
    val v = SttService.validateConfig(
      cfg("""{"endpoint":"https://stt.example.com/v1/audio/transcriptions","apiKey":"sk-test-12345678","model":"mock-asr"}""")
    )
    assertEquals(
      v,
      Right(
        Some(
          cfg("""{"endpoint":"https://stt.example.com/v1/audio/transcriptions","apiKey":"sk-test-12345678","model":"mock-asr"}""")
        )
      )
    )
  }

  test("validateConfig: http:// endpoint accepted (not just https)") {
    assert(SttService.validateConfig(cfg("""{"endpoint":"http://localhost:18090/transcriptions","apiKey":"k"}""")).isRight)
  }

  test("validateConfig: non-http scheme rejected with message") {
    assertEquals(
      SttService.validateConfig(cfg("""{"endpoint":"ftp://stt.example.com/x","apiKey":"k"}""")),
      Left("STT endpoint must start with http:// or https:// (got: ftp://stt.example.com/x)")
    )
    assert(
      SttService.validateConfig(cfg("""{"endpoint":"stt.example.com","apiKey":"k"}""")).isLeft
    )
  }

  test("validateConfig: empty object = clear (Right(None))") {
    assertEquals(SttService.validateConfig(cfg("{}")), Right(None))
  }

  test("validateConfig: all-empty fields = clear (Right(None))") {
    assertEquals(
      SttService.validateConfig(cfg("""{"endpoint":"","apiKey":"","model":"   "}""")),
      Right(None)
    )
  }

  test("validateConfig: apiKey-only payload keeps apiKey alone (endpoint/model fall back to Defaults on read)") {
    assertEquals(
      SttService.validateConfig(cfg("""{"apiKey":"sk-only-key"}""")),
      Right(Some(cfg("""{"apiKey":"sk-only-key"}""")))
    )
  }

  test("validateConfig: whitespace trimmed (paste hygiene)") {
    assertEquals(
      SttService.validateConfig(cfg("""{"endpoint":"  https://x.example.com/t  ","apiKey":" key "}""")),
      Right(Some(cfg("""{"endpoint":"https://x.example.com/t","apiKey":"key"}""")))
    )
  }

  // ── serverConfigNode：脱敏（apiKey 零出现）────────────────

  test("serverConfigNode: configured service exposes endpoint+model, NEVER apiKey") {
    val svc = new SttService("sk-super-secret-999", "mock-asr", "https://stt.example.com/t")
    val node = SttService.serverConfigNode(Some(svc))
    val rendered = node.noSpaces
    assert(rendered.contains(""""sttConfigured":true"""), rendered)
    assert(rendered.contains(""""endpoint":"https://stt.example.com/t""""), rendered)
    assert(rendered.contains(""""model":"mock-asr""""), rendered)
    // 铁律：apiKey 任何形态零出现
    assert(!rendered.contains("apiKey"), rendered)
    assert(!rendered.contains("sk-super-secret"), rendered)
    // 反序列化字段级断言
    assertEquals(node.hcursor.downField("apiKey").as[Option[String]], Right(None))
    assertEquals(node.hcursor.downField("sttConfigured").as[Boolean], Right(true))
  }

  test("serverConfigNode: None service = sttConfigured:false only") {
    val node = SttService.serverConfigNode(None)
    assertEquals(node.hcursor.downField("sttConfigured").as[Boolean], Right(false))
    assertEquals(node.hcursor.downField("endpoint").as[Option[String]], Right(None))
    assertEquals(node.hcursor.downField("model").as[Option[String]], Right(None))
    assertEquals(node.hcursor.downField("apiKey").as[Option[String]], Right(None))
  }

  // ── 配置 round-trip：原子写 + create() 热重建 ─────────────

  test("config round-trip: AtomicJson write then create() picks it up (hot reload core)") {
    val tmp = os.pwd / "target" / s"stt-spec-rt-${System.nanoTime().toHexString}"
    os.makeDir.all(tmp)
    PathUtil.setDataRoot(tmp)
    try
      val cfgJson = SttService.validateConfig(
        cfg("""{"endpoint":"https://stt.example.com/t","apiKey":"sk-rt-key","model":"mock-asr"}""")
      ).toOption.get.get
      nebflow.core.AtomicJson.writeSync(SttService.configPath, cfgJson.noSpaces)
      // 热更路径：setSttConfig 写盘后 SttService.create() 重建
      SttService.create().unsafeRunSync() match
        case Some(svc) =>
          assertEquals(svc.endpoint, "https://stt.example.com/t")
          assertEquals(svc.model, "mock-asr")
        case None => fail("expected Some(SttService) after config write")
    finally
      PathUtil.setDataRoot(os.Path("/tmp"))
      os.remove.all(tmp)
  }

  test("config round-trip: missing fields fall back to Defaults") {
    val tmp = os.pwd / "target" / s"stt-spec-dflt-${System.nanoTime().toHexString}"
    os.makeDir.all(tmp)
    PathUtil.setDataRoot(tmp)
    try
      nebflow.core.AtomicJson.writeSync(
        SttService.configPath,
        cfg("""{"apiKey":"sk-defaults-key"}""").noSpaces
      )
      SttService.create().unsafeRunSync() match
        case Some(svc) =>
          assertEquals(svc.model, nebflow.shared.Defaults.SttDefaultModel)
          assertEquals(svc.endpoint, nebflow.shared.Defaults.SttDefaultEndpoint)
        case None => fail("expected Some(SttService)")
    finally
      PathUtil.setDataRoot(os.Path("/tmp"))
      os.remove.all(tmp)
  }

  test("config round-trip: file deleted (clear semantics) → create() = None") {
    val tmp = os.pwd / "target" / s"stt-spec-clear-${System.nanoTime().toHexString}"
    os.makeDir.all(tmp)
    PathUtil.setDataRoot(tmp)
    try
      nebflow.core.AtomicJson.writeSync(
        SttService.configPath,
        cfg("""{"apiKey":"sk-clear-key"}""").noSpaces
      )
      assert(SttService.create().unsafeRunSync().isDefined)
      os.remove(SttService.configPath) // 清空语义：删文件
      assert(SttService.create().unsafeRunSync().isEmpty)
    finally
      PathUtil.setDataRoot(os.Path("/tmp"))
      os.remove.all(tmp)
  }

end SttConfigSpec
