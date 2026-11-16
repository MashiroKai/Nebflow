package nebflow.core.plugin

import munit.FunSuite

/**
 * 分发信任模型（插件分发与生命周期协议升级草案 §12.1；A 期 §12.7 嵌入点）：
 *  - **publisherClass 只认目录签名条目**：manifest 自称（含 extensions 内自报）无效；
 *    无有效目录断言 ⇒ 不赋值（None）——A 期无目录 ⇒ 恒 None；
 *  - **官方徽章双条件**：官方根键签名 ∧ publisherClass=official，任一缺失 ⇒ 不显示；
 *  - **legacy via → §12.1 迁移映射**：A 期事实单一来源（安装期默认与存量 schema 1
 *    升档共用同一函数——单一事实源，禁两套映射）。
 */
class PluginDistributionTrustSpec extends FunSuite:

  import PluginDistributionTrust.*
  import PluginSourceLedger.Via

  // ── 红绿②：publisherClass 只认目录签名条目 ────────────────────

  test("红绿② publisherClass 只认目录签名条目：manifest 自称无效，无目录断言 ⇒ 不赋值"):
    assertEquals(
      assignPublisherClass(
        catalogAssertion = None,
        manifestSelfClaim = Some(PublisherClass.Official)
      ),
      None,
      "manifest 自称 official 不得产生 publisherClass（§12.1：只能由通过验证的目录签名条目赋予）"
    )

  test("红绿② 目录断言胜出：manifest 反向自称不改写、无自称照常赋值"):
    assertEquals(
      assignPublisherClass(
        catalogAssertion = Some(VerifiedCatalogAssertion(PublisherClass.Official)),
        manifestSelfClaim = Some(PublisherClass.Community)
      ),
      Some(PublisherClass.Official),
      "目录签名条目与 manifest 自称冲突时以目录为准"
    )
    assertEquals(
      assignPublisherClass(
        catalogAssertion = Some(VerifiedCatalogAssertion(PublisherClass.Verified)),
        manifestSelfClaim = None
      ),
      Some(PublisherClass.Verified)
    )

  // ── 红绿③：官方徽章双条件 ────────────────────────────────────

  test("红绿③ 官方徽章双条件：官方根键签名 ∧ publisherClass=official，任一缺失 ⇒ false"):
    assert(
      officialBadge(signedByOfficialRootKey = true, publisherClass = Some(PublisherClass.Official)),
      "双条件齐备 ⇒ 显示"
    )
    assert(
      !officialBadge(signedByOfficialRootKey = false, publisherClass = Some(PublisherClass.Official)),
      "publisherClass=official 但非官方根键签名 ⇒ 不得显示"
    )
    assert(
      !officialBadge(signedByOfficialRootKey = true, publisherClass = Some(PublisherClass.Verified)),
      "官方根键签名但 publisherClass=verified ⇒ 不得显示（verified ≠ official）"
    )
    assert(
      !officialBadge(signedByOfficialRootKey = true, publisherClass = None),
      "无目录断言（A 期常态）⇒ 恒 false"
    )

  // ── 迁移映射（支撑红绿①的默认值单一事实源）─────────────────────

  test("legacy via → §12.1 迁移映射：厂方渠道 / git / 显式导入三桶 + 预留空值族"):
    // 厂方渠道两条腿（seed=随引擎分发字节；official=厂方索引+双值校验）⇒ origin=seed、
    // owner=seed、official-verified（seed 信任根 = 分发载体签名，见 OfficialPackages；
    // C 期签名目录落地后该通道断言才升格 publisherClass——两轴分立）
    for via <- List(Via.Seed, Via.Official) do
      val f = factsForLegacyVia(via)
      assertEquals(f.origin, Origin.Seed, s"via=${via.label} ⇒ origin=seed")
      assertEquals(f.distributionOwner, DistributionOwner.Seed)
      assertEquals(f.trustLevel, TrustLevel.OfficialVerified)
      assertEquals(f.publisherClass, None, "A 期无目录签名 ⇒ publisherClass 恒不赋值（trustLevel 是独立轴）")

    // git 腿 ⇒ git-pinned（P2 起安装必带 commit pin）、本地管理、显式导入级信任
    val g = factsForLegacyVia(Via.Git)
    assertEquals(g.origin, Origin.GitPinned)
    assertEquals(g.distributionOwner, DistributionOwner.Local)
    assertEquals(g.trustLevel, TrustLevel.LocalImport)

    // local / https ⇒ local-import（显式一次性导入固定字节桶：无目录断言、无更新通道）
    for via <- List(Via.Local, Via.Https) do
      val f = factsForLegacyVia(via)
      assertEquals(f.origin, Origin.LocalImport, s"via=${via.label} ⇒ origin=local-import")
      assertEquals(f.distributionOwner, DistributionOwner.Local)
      assertEquals(f.trustLevel, TrustLevel.LocalImport)

    // §12.1 预留空值族默认（尚无 Hub 数据时允许为空）
    val d = factsForLegacyVia(Via.Local)
    assertEquals(d.hubReviewId, None)
    assertEquals(d.submissionStatus, None)
    assertEquals(d.lastCheckedAt, None)

end PluginDistributionTrustSpec
