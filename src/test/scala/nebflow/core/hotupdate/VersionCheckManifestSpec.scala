package nebflow.core.hotupdate

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 统一更新系统 M3 ② 定向验收：**VersionCheck 换读源 + 回落**（DESIGN §5 兼容序）。
 *
 * 覆盖（质量闸 spec 清单 ①「换读源回落」）：
 *  - 读序第一跳 = 官网 manifest（fixture 经 `-Dnebflow.updates.manifestUrl` 注入）；
 *  - manifest 不可读 / schema 不识别 ⇒ 回落裸 txt 指针（`-Dnebflow.updates.pointerUrl`
 *    注入，只检不装）；
 *  - 两跳皆败 ⇒ None（既有失败静默语义）；
 *  - ReleaseManifest 解析容错（schema=1 门 / 未知字段忽略 / artifacts.jar 提取）；
 *  - compareVersions 与 link-server M2 `cmp_versions` 逐条同构（日历段数值序，
 *    字符串直比会反转 2026.10 vs 2026.9——禁用）。
 *
 * 全程 file:// fixture，**零真实网络触达**。
 */
class VersionCheckManifestSpec extends CatsEffectSuite:

  private val manifestProp = "nebflow.updates.manifestUrl"
  private val pointerProp = "nebflow.updates.pointerUrl"

  private def withProps(p: (String, String)*)(body: IO[?]): IO[?] =
    val saved = p.map((k, _) => k -> Option(System.getProperty(k)))
    IO(p.foreach((k, v) => System.setProperty(k, v))) *>
      body.guarantee(IO {
        saved.foreach {
          case (k, Some(v)) => System.setProperty(k, v)
          case (k, None) => System.clearProperty(k)
        }
      })

  private def writeFixture(name: String, content: String): String =
    val f = Files.createTempFile(name, ".tmp")
    Files.write(f, content.getBytes(StandardCharsets.UTF_8))
    f.toUri.toString

  // ── ReleaseManifest.parse ──────────────────────────────────────────

  test("parse: schema=1 manifest 全字段提取（jar 完整性面就位）") {
    val raw = """{
      "schema": 1, "channel": "stable", "version": "2026.10.20", "build": "71452cd2",
      "publishedAt": "2026-10-20T12:00:00Z",
      "releaseNotesUrl": "https://mystand.space/updates/2026.10.20",
      "artifacts": { "jar": { "url": "https://oss/jar", "sha256": "AABB", "sizeBytes": 123 }, "msi": {"url": "x"} },
      "minCompatibleEngine": "2026.9.1",
      "futureUnknownField": {"ignored": true}
    }"""
    val m = ReleaseManifest.parse(raw)
    assert(m.isDefined)
    val got = m.get
    assertEquals(got.version, "2026.10.20")
    assertEquals(got.build, Some("71452cd2"))
    assertEquals(got.channel, "stable")
    assertEquals(got.jarUrl, Some("https://oss/jar"))
    assertEquals(got.jarSha256, Some("aabb")) // 归一小写
    assertEquals(got.jarSizeBytes, Some(123L))
    assertEquals(got.minCompatibleEngine, Some("2026.9.1"))
  }

  test("parse: schema 不识别（演进位只认 1）⇒ 不可用") {
    val raw = """{"schema": 2, "channel": "stable", "version": "2026.10.20"}"""
    assertEquals(ReleaseManifest.parse(raw), None)
  }

  test("parse: version 缺失 / 非 JSON / 空串 ⇒ 不可用（失败静默语义）") {
    assertEquals(ReleaseManifest.parse("""{"schema": 1, "channel": "stable"}"""), None)
    assertEquals(ReleaseManifest.parse("not json at all"), None)
    assertEquals(ReleaseManifest.parse(""), None)
  }

  // ── 换读源回落（读序）────────────────────────────────────────────

  test("读序①: manifest 可读 ⇒ 取 manifest 版本，不触 txt 指针") {
    val manifestUrl = writeFixture("m-ok", """{"schema":1,"channel":"stable","version":"2026.10.20"}""")
    val pointerUrl = writeFixture("p-untouched", "1999.1.1")
    withProps(manifestProp -> manifestUrl, pointerProp -> pointerUrl) {
      VersionCheck.fetchLatestVersion().map(v => assertEquals(v, Some("2026.10.20")))
    }
  }

  test("读序②: manifest 不可读 ⇒ 回落裸 txt（只检不装）") {
    val manifestUrl = writeFixture("m-unreadable", "}{ broken")
    val pointerUrl = writeFixture("p-ok", "v2026.9.19")
    withProps(manifestProp -> manifestUrl, pointerProp -> pointerUrl) {
      VersionCheck.fetchLatestVersion().map(v => assertEquals(v, Some("2026.9.19")))
    }
  }

  test("读序②': manifest schema 不识别 ⇒ 同样回落 txt") {
    val manifestUrl = writeFixture("m-schema2", """{"schema":9,"version":"2099.1.1"}""")
    val pointerUrl = writeFixture("p-ok2", "2026.9.19")
    withProps(manifestProp -> manifestUrl, pointerProp -> pointerUrl) {
      VersionCheck.fetchLatestVersion().map(v => assertEquals(v, Some("2026.9.19")))
    }
  }

  test("读序③: 两跳皆败 ⇒ None（失败静默，绝不抛错）") {
    withProps(manifestProp -> "file:///nonexistent/manifest.json", pointerProp -> "file:///nonexistent/pointer.txt") {
      VersionCheck.fetchLatestVersion().map(v => assertEquals(v, None))
    }
  }

  test("hasUpdate 口径冻结：异值即有更新") {
    assert(VersionCheck.hasUpdate("2026.9.20", "2026.10.20"))
    assert(!VersionCheck.hasUpdate("2026.9.20", "2026.9.20"))
  }

  // ── compareVersions（link-server cmp_versions 同构）──────────────

  test("日历段数值序：2026.10 > 2026.9（字符串直比会反转——禁用面）") {
    assert(VersionCheck.compareVersions("2026.10.1", "2026.9.20") > 0)
    assert(VersionCheck.compareVersions("2026.9.20", "2026.10.1") < 0)
  }

  test("缺段 = 0：2026.10 == 2026.10.0") {
    assertEquals(VersionCheck.compareVersions("2026.10", "2026.10.0"), 0)
  }

  test("pre-release 排在裸发布之前（semver 规则）；两 pre 按字典序（与 server 同构）") {
    assert(VersionCheck.compareVersions("2026.10.20", "2026.10.20-beta.1") > 0)
    // server cmp_versions 对 pre 尾做字符串比较 —— 引擎逐字同构，不"修"它
    assert(VersionCheck.compareVersions("1.4.1-beta.9", "1.4.1-beta.58") > 0)
  }

  test("等值 ⇔ 0（前向-only 采纳闸的收敛判据）") {
    assertEquals(VersionCheck.compareVersions("2026.10.20", "2026.10.20"), 0)
  }
end VersionCheckManifestSpec
