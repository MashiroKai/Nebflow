package nebflow.core.plugin

import munit.FunSuite
import nebflow.shared.PathUtil
import PluginDistributionTrust.*

import java.nio.file.Files

/**
 * provenance 账本 schema 2（插件分发与生命周期协议升级草案 §5.4 + §12.1；A 期 §12.7）：
 *  - **红绿①**：存量 schema 1 账本兼容升档（逐字段迁移默认）；新写面落 schema 2 并
 *    序列化往返逐字段相等；
 *  - **红绿④**：未知字段宽容忽略不报错（v2 与 v1 文件携带前瞻字段均照常装载）；
 *  - **作者所有字段跨世代保留**：distributionOwner / updatePolicy / pinned / hub 三字段
 *    不因 update 腿重记被冲掉（§12.1「不因目录出现或收到推送自动转移」的账本面）；
 *  - 已知新字段违规仍按损坏降级（闭合枚举纪律不放松）。
 */
class PluginLedgerV2Spec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-ledger-v2"))
    PathUtil.setDataRoot(home)
    os.makeDir.all(home / "plugins")
    PluginRegistry.invalidateCache()
    PluginRegistry.resetProvenanceBackfillGate()
    PluginRegistry.setProvenanceBackfillForTest(None)

  override def afterAll(): Unit =
    PluginRegistry.setProvenanceBackfillForTest(None)
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  private def ledgerFile(name: String): os.Path = PluginSourceLedger.ledgerDir / s"$name.json"

  private def writeRaw(name: String, body: String): Unit =
    os.makeDir.all(PluginSourceLedger.ledgerDir)
    os.write.over(ledgerFile(name), body)

  /** P1 形态存量账本（无任何 §12.1/§5.4 新字段）。 */
  private def v1Body(name: String, via: String, kind: String): String =
    s"""{"schema":1,"name":"$name","kind":"$kind","version":"1.4.0","digest":"${"a" * 64}","installedAt":1793264715,"via":"$via"}"""

  // ── 红绿①：存量兼容升档 + 新写 schema 2 ─────────────────────

  test("红绿①-迁移 存量 schema 1 账本兼容升档：照常装载 + §12.1/§5.4 迁移默认"):
    writeRaw("legacy-git", v1Body("legacy-git", via = "git", kind = "git"))
    PluginSourceLedger.query("legacy-git") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.distribution.origin, Origin.GitPinned)
        assertEquals(e.distribution.trustLevel, TrustLevel.LocalImport)
        assertEquals(e.distribution.distributionOwner, DistributionOwner.Local)
        assertEquals(e.distribution.publisherClass, None, "A 期无目录断言 ⇒ 不赋值")
        assertEquals(e.updatePolicy, UpdatePolicy.Check, "缺省策略 = check（§4 默认检查不自动替换）")
        assertEquals(e.pinned, false)
        assertEquals(e.distribution.hubReviewId, None)
        assertEquals(e.distribution.submissionStatus, None)
        assertEquals(e.distribution.lastCheckedAt, None)
        assertEquals(e.previousGeneration, None)
        assertEquals(e.transactionId, None)
        assertEquals(e.validationSummary, None)
      case PluginSourceLedger.UnknownSource(reason) =>
        fail(s"schema 1 存量必须兼容升档（兼容升档不改判 Known）, got: $reason")
    end match

    writeRaw("legacy-seed", v1Body("legacy-seed", via = "seed", kind = "official"))
    PluginSourceLedger.query("legacy-seed") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.distribution.origin, Origin.Seed)
        assertEquals(e.distribution.distributionOwner, DistributionOwner.Seed)
        assertEquals(e.distribution.trustLevel, TrustLevel.OfficialVerified)
      case PluginSourceLedger.UnknownSource(reason) => fail(s"got: $reason")

  test("红绿①-序列化 新写账本落 schema 2 + §12.1/§5.4 字段写读往返逐字段相等"):
    val _ = PluginSourceLedger.recordInstall(
      "fresh-v2",
      PluginSourceLedger.Kind.Local,
      Some("/src/x"),
      None,
      "1.0.0",
      "b" * 64,
      PluginSourceLedger.Via.Local,
      PluginSourceLedger.InstallMeta(validationSummary = Some("local-layout@1"))
    )
    val raw = os.read(ledgerFile("fresh-v2"))
    assert(raw.contains("\"schema\":2"), s"新写账本必须是 schema 2, got: $raw")
    assert(raw.contains("\"origin\":\"local-import\""))
    assert(raw.contains("\"trustLevel\":\"local-import\""))
    assert(raw.contains("\"distributionOwner\":\"local\""))
    assert(raw.contains("\"updatePolicy\":\"check\""))
    assert(raw.contains("\"pinned\":false"))
    assert(!raw.contains("\"publisherClass\""), "无目录断言 ⇒ publisherClass 不落字段（缺席 ≠ null）")
    assert(!raw.contains("\"hubReviewId\""), "预留空值族缺席不落 null")
    PluginSourceLedger.query("fresh-v2") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.validationSummary, Some("local-layout@1"))
        assertEquals(e.distributionId, None)
        assertEquals(e.publisherKeyId, None)
        assertEquals(e.artifactSha256, None)
      case PluginSourceLedger.UnknownSource(reason) => fail(s"got: $reason")

    // official 腿 meta（§5.4 A 期地基字段的满配形态）往返
    val meta = PluginSourceLedger.InstallMeta(
      distributionId = Some("stand.stand-dev"),
      publisherKeyId = Some("stand-release-1"),
      artifactSha256 = Some("c" * 64),
      validationSummary = Some("official-zip-dualvalue@1")
    )
    val _2 = PluginSourceLedger.recordInstall(
      "roundtrip-v2",
      PluginSourceLedger.Kind.Official,
      Some("https://oss/x.zip"),
      None,
      "1.5.0",
      "d" * 64,
      PluginSourceLedger.Via.Official,
      meta
    )
    PluginSourceLedger.query("roundtrip-v2") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.distribution.origin, Origin.Seed, "厂方索引腿 ⇒ origin=seed（C 期目录上线后新装才记 hub）")
        assertEquals(e.distribution.trustLevel, TrustLevel.OfficialVerified)
        assertEquals(e.distribution.distributionOwner, DistributionOwner.Seed)
        assertEquals(e.distributionId, Some("stand.stand-dev"))
        assertEquals(e.publisherKeyId, Some("stand-release-1"))
        assertEquals(e.artifactSha256, Some("c" * 64))
        assertEquals(e.validationSummary, Some("official-zip-dualvalue@1"))
      case PluginSourceLedger.UnknownSource(reason) => fail(s"got: $reason")

  // ── 红绿④：未知字段宽容忽略 ─────────────────────────────────

  test("红绿④ 未知字段忽略不报错：v2 与 v1 文件携带前瞻字段均照常装载"):
    // v2 文件带 P3 history / 任意前瞻字段（前向兼容纪律不因 schema 升档放松）
    writeRaw(
      "future-v2",
      s"""{"schema":2,"name":"future-v2","kind":"local","version":"1.0.0","digest":"${"e" * 64}",""" +
        s""""installedAt":1793264716,"via":"local","origin":"local-import","trustLevel":"local-import",""" +
        s""""distributionOwner":"local","updatePolicy":"check","pinned":false,"history":[1,2],"anythingElse":{"x":1}}"""
    )
    PluginSourceLedger.query("future-v2") match
      case PluginSourceLedger.Known(e) => assertEquals(e.name, "future-v2")
      case PluginSourceLedger.UnknownSource(reason) => fail(s"未知字段必须宽容忽略不报错, got: $reason")

    // v1 文件带 §12.1 前瞻字段（写端先于读端升级的中间态）也不炸
    writeRaw(
      "future-v1",
      s"""{"schema":1,"name":"future-v1","kind":"git","pin":{"commit":"${"f" * 40}"},"version":"1.0.0",""" +
        s""""digest":"${"1" * 64}","installedAt":1793264717,"via":"git","distribution":{"origin":"git-pinned"},"hubReviewId":"rev-x"}"""
    )
    PluginSourceLedger.query("future-v1") match
      case PluginSourceLedger.Known(e) => assertEquals(e.name, "future-v1")
      case PluginSourceLedger.UnknownSource(reason) => fail(s"v1+前瞻字段必须宽容, got: $reason")

  // ── 作者所有字段跨世代保留（§12.1 唯一所有者的账本面）─────────────

  test("作者所有字段跨世代保留：update 腿重记时 owner/policy/pinned/hub 面不被冲掉"):
    // 首记：模拟 D 期迁移落档 / 作者显式切换后的 author-owned 值（手写 v2 全配文件）
    writeRaw(
      "owner-keep",
      s"""{"schema":2,"name":"owner-keep","kind":"local","url":"/src","version":"1.0.0","digest":"${"2" * 64}",""" +
        s""""installedAt":1793264718,"via":"local","origin":"local-import","trustLevel":"local-import",""" +
        s""""distributionOwner":"seed","updatePolicy":"minor","pinned":true,""" +
        s""""hubReviewId":"rev-1","submissionStatus":"published","lastCheckedAt":1793264800}"""
    )
    // 更新腿重记（via=git——若不保留，owner 会被写成 local = 隐式转移）
    val _ = PluginSourceLedger.recordInstall(
      "owner-keep",
      PluginSourceLedger.Kind.Git,
      Some("https://g/r.git"),
      Some(PluginSourceLedger.Pin.Commit(None, "9" * 40)),
      "2.0.0",
      "3" * 64,
      PluginSourceLedger.Via.Git,
      PluginSourceLedger.InstallMeta(validationSummary = Some("sync-swap@1"))
    )
    PluginSourceLedger.query("owner-keep") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.distribution.distributionOwner, DistributionOwner.Seed, "§12.1：分发所有者由作者显式切换，不因更新事件隐式转移")
        assertEquals(e.updatePolicy, UpdatePolicy.Minor, "逐包更新策略是作者选择，重记不回落默认")
        assertEquals(e.pinned, true, "版本固定不被更新腿清除")
        assertEquals(e.distribution.hubReviewId, Some("rev-1"))
        assertEquals(e.distribution.submissionStatus, Some(SubmissionStatus.Published))
        assertEquals(e.distribution.lastCheckedAt, Some(1793264800L))
        assertEquals(e.distribution.origin, Origin.GitPinned, "origin = 本世代字节的安装事件事实（随事件重记）")
        assertEquals(e.version, "2.0.0")
        assertEquals(e.digest, "3" * 64)
      case PluginSourceLedger.UnknownSource(reason) => fail(s"got: $reason")

  // ── 闭合枚举纪律不放松 ──────────────────────────────────────

  test("v2 已知新字段违规仍按损坏降级（枚举外 origin / 类型违规 updatePolicy）"):
    writeRaw(
      "bad-origin",
      s"""{"schema":2,"name":"bad-origin","kind":"local","version":"1.0.0","digest":"${"4" * 64}",""" +
        s""""installedAt":1793264719,"via":"local","origin":"aliens"}"""
    )
    PluginSourceLedger.query("bad-origin") match
      case PluginSourceLedger.UnknownSource(reason) => assert(reason.contains("origin"), s"指名 origin, got: $reason")
      case k => fail(s"枚举外 origin 必须降级 unknown-source, got: $k")

    writeRaw(
      "bad-policy",
      s"""{"schema":2,"name":"bad-policy","kind":"local","version":"1.0.0","digest":"${"5" * 64}",""" +
        s""""installedAt":1793264720,"via":"local","origin":"local-import","trustLevel":"local-import",""" +
        s""""distributionOwner":"local","updatePolicy":"whenever"}"""
    )
    PluginSourceLedger.query("bad-policy") match
      case PluginSourceLedger.UnknownSource(reason) =>
        assert(reason.contains("updatePolicy"), s"指名 updatePolicy, got: $reason")
      case k => fail(s"枚举外 updatePolicy 必须降级, got: $k")

end PluginLedgerV2Spec
