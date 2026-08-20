package nebflow.gateway

import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.PathUtil

/** #295 STT 前端可配置——setSttConfig 校验/解析 + A2 部分更新 merge + 配置
  * round-trip + serverConfig 脱敏。语义（2026-08-18 拍板 → 08-20 A2 对齐）：
  * 字段省略=保留旧值（merge）、字段显式空=清除该字段、合并后空=删文件（回退
  * 浏览器 Web Speech）、endpoint 设值须 http(s)://、apiKey 原样存（trim 粘贴
  * 空格）、永不回传。旧「全空=清空 + 整文件替换」语义已废——它让二次保存
  * （前端空 key 省略字段）覆盖丢失已存 apiKey（#295 A2 根因）。 */
class SttConfigSpec extends FunSuite:

  private def cfg(json: String): io.circe.Json =
    parse(json).toOption.get

  // ── parsePatch：三态解析与校验 ────────────────────────────

  private def patchOf(json: String): SttService.SttConfigPatch =
    SttService.parsePatch(cfg(json)).toOption.get

  test("parsePatch: full valid payload sets all three fields") {
    val p = patchOf(
      """{"endpoint":"https://stt.example.com/v1/audio/transcriptions","apiKey":"sk-test-12345678","model":"mock-asr"}"""
    )
    assertEquals(p.endpoint, Some(Some("https://stt.example.com/v1/audio/transcriptions")))
    assertEquals(p.apiKey, Some(Some("sk-test-12345678")))
    assertEquals(p.model, Some(Some("mock-asr")))
  }

  test("parsePatch: http:// endpoint accepted (not just https)") {
    assert(SttService.parsePatch(cfg("""{"endpoint":"http://localhost:18090/transcriptions","apiKey":"k"}""")).isRight)
  }

  test("parsePatch: non-http scheme rejected with message") {
    assertEquals(
      SttService.parsePatch(cfg("""{"endpoint":"ftp://stt.example.com/x","apiKey":"k"}""")),
      Left("STT endpoint must start with http:// or https:// (got: ftp://stt.example.com/x)")
    )
    assert(
      SttService.parsePatch(cfg("""{"endpoint":"stt.example.com","apiKey":"k"}""")).isLeft
    )
  }

  test("parsePatch: non-string field rejected") {
    assert(SttService.parsePatch(cfg("""{"apiKey": 42}""")) == Left("STT field 'apiKey' must be a string if present"))
  }

  test("parsePatch: omitted field → None (keep old), explicit empty → Some(None) (clear)") {
    val p = patchOf("""{"endpoint":"https://x.example.com/t","apiKey":""}""")
    assertEquals(p.endpoint, Some(Some("https://x.example.com/t")))
    assertEquals(p.apiKey, Some(None)) // explicit empty — CLEAR
    assertEquals(p.model, None) // omitted — KEEP OLD
  }

  test("parsePatch: whitespace-only counts as explicit clear (paste hygiene preserved)") {
    val p = patchOf("""{"model":"   "}""")
    assertEquals(p.model, Some(None))
  }

  test("parsePatch: whitespace trimmed on set values") {
    val p = patchOf("""{"endpoint":"  https://x.example.com/t  ","apiKey":" key "}""")
    assertEquals(p.endpoint, Some(Some("https://x.example.com/t")))
    assertEquals(p.apiKey, Some(Some("key")))
  }

  // ── mergeConfig：A2 部分更新核心 ──────────────────────────

  test("mergeConfig A2-1 首次保存（无旧文件）：提供的字段落盘") {
    val merged = SttService.mergeConfig(
      None,
      patchOf("""{"endpoint":"https://stt.example.com/t","apiKey":"sk-first","model":"mock-asr"}""")
    )
    assertEquals(merged, Some(cfg("""{"endpoint":"https://stt.example.com/t","apiKey":"sk-first","model":"mock-asr"}""")))
  }

  test("mergeConfig A2-2 二次保存省略 apiKey（前端空 key 省略字段）：旧 key 保留——根因回归钉死") {
    val old = Some(cfg("""{"endpoint":"https://old.example.com/t","apiKey":"sk-precious-key","model":"old-asr"}"""))
    val merged = SttService.mergeConfig(
      old,
      patchOf("""{"endpoint":"https://new.example.com/t","model":"new-asr"}""") // apiKey omitted
    )
    assertEquals(
      merged,
      Some(cfg("""{"endpoint":"https://new.example.com/t","apiKey":"sk-precious-key","model":"new-asr"}"""))
    )
  }

  test("mergeConfig A2-3 显式清除（三字段显式空）：回到未配置态（None）") {
    val old = Some(cfg("""{"endpoint":"https://old.example.com/t","apiKey":"sk-old","model":"old-asr"}"""))
    val merged = SttService.mergeConfig(old, patchOf("""{"endpoint":"","apiKey":"","model":""}"""))
    assertEquals(merged, None)
    assert(patchOf("""{"endpoint":"","apiKey":"","model":""}""").clearsEverything)
  }

  test("mergeConfig 空对象 payload：全字段省略=原样保留（不再是清空——语义翻转点）") {
    val old = Some(cfg("""{"endpoint":"https://old.example.com/t","apiKey":"sk-old","model":"old-asr"}"""))
    assertEquals(SttService.mergeConfig(old, patchOf("{}")), old)
  }

  test("mergeConfig 混合：清除单字段+省略其余 → 其余保留旧值") {
    val old = Some(cfg("""{"endpoint":"https://old.example.com/t","apiKey":"sk-old","model":"old-asr"}"""))
    val merged = SttService.mergeConfig(old, patchOf("""{"endpoint":""}"""))
    assertEquals(merged, Some(cfg("""{"apiKey":"sk-old","model":"old-asr"}""")))
  }

  test("mergeConfig 显式清 apiKey 后（无 key 无服务）：其余字段留盘待 key 回填") {
    val old = Some(cfg("""{"endpoint":"https://old.example.com/t","apiKey":"sk-old","model":"old-asr"}"""))
    val merged = SttService.mergeConfig(old, patchOf("""{"apiKey":""}"""))
    assertEquals(merged, Some(cfg("""{"endpoint":"https://old.example.com/t","model":"old-asr"}""")))
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
      val cfgJson = SttService.mergeConfig(
        None,
        patchOf("""{"endpoint":"https://stt.example.com/t","apiKey":"sk-rt-key","model":"mock-asr"}""")
      ).get
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
