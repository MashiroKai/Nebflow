package nebflow.core.plugin

import nebflow.core.plugin.PluginSourceLedger.Via

/**
 * PluginDistributionTrust —— 分发信任模型（插件分发与生命周期协议升级草案 §12.1；A 期 §12.7 嵌入）。
 *
 * 四轴分立，禁止混轴：
 *  - **origin**：本世代字节的安装事件事实（seed=随引擎分发 / hub=C 期目录 / local-import=
 *    显式一次性导入 / git-pinned=commit 钉住的 clone）。随每次安装事件重记；
 *  - **publisherClass**：发布者身份断言（official / verified / community）。**只能由通过
 *    验证的目录签名条目赋予**（[[assignPublisherClass]]）——manifest 自称（含 extensions
 *    内自报）恒无效；A 期无目录 ⇒ 恒 None；
 *  - **trustLevel**：信任级四档（独立轴，与 publisherClass 不互相推导）。A 期由安装渠道
 *    决定：seed 信任根 = 分发载体签名（[[OfficialPackages]]），故 seed/official 渠道 ⇒
 *    official-verified；C 期签名目录落地后该通道才升格 publisherClass 断言；
 *  - **distributionOwner**：分发所有者（seed / hub / local），§12.1 唯一所有者——只由作者
 *    显式切换，**不因目录出现或收到推送（update 腿重记）自动转移**。
 *
 * 官方徽章（[[officialBadge]]）双条件：官方根键签名 ∧ publisherClass=official，任一缺失 ⇒
 * 不显示（A 期无目录 ⇒ 恒 false）。
 *
 * 预留空值族（hubReviewId / submissionStatus / lastCheckedAt）：§12.1 允许为空（尚无 Hub
 * 数据），序列化时缺席不落 null（缺席 ≠ null）。
 */
object PluginDistributionTrust:

  /** 本世代字节的来源事实（§12.1 origin；随安装事件重记）。 */
  enum Origin(val label: String):
    case Seed extends Origin("seed")
    case Hub extends Origin("hub")
    case LocalImport extends Origin("local-import")
    case GitPinned extends Origin("git-pinned")

  object Origin:
    def fromLabel(s: String): Option[Origin] = values.find(_.label == s)

  /** 发布者身份断言（只能来自通过验证的目录签名条目；manifest 自称恒无效）。 */
  enum PublisherClass(val label: String):
    case Official extends PublisherClass("official")
    case Verified extends PublisherClass("verified")
    case Community extends PublisherClass("community")

  object PublisherClass:
    def fromLabel(s: String): Option[PublisherClass] = values.find(_.label == s)

  /** 信任级四档（§12.1；独立于 publisherClass 的轴）。 */
  enum TrustLevel(val label: String):
    case OfficialVerified extends TrustLevel("official-verified")
    case CatalogSigned extends TrustLevel("catalog-signed")
    case LocalImport extends TrustLevel("local-import")
    case Unknown extends TrustLevel("unknown")

  object TrustLevel:
    def fromLabel(s: String): Option[TrustLevel] = values.find(_.label == s)

  /** 分发所有者（§12.1 唯一所有者；作者显式切换，不因更新事件隐式转移）。 */
  enum DistributionOwner(val label: String):
    case Seed extends DistributionOwner("seed")
    case Hub extends DistributionOwner("hub")
    case Local extends DistributionOwner("local")

  object DistributionOwner:
    def fromLabel(s: String): Option[DistributionOwner] = values.find(_.label == s)

  /** 逐包更新策略（§4：默认 check——检查不自动替换；作者选择，重记不回落默认）。 */
  enum UpdatePolicy(val label: String):
    case Check extends UpdatePolicy("check")
    case Minor extends UpdatePolicy("minor")
    case All extends UpdatePolicy("all")

  object UpdatePolicy:
    def fromLabel(s: String): Option[UpdatePolicy] = values.find(_.label == s)

  /** Hub 投稿状态（§12.1 预留空值族；A 期无 Hub ⇒ 恒 None）。 */
  enum SubmissionStatus(val label: String):
    case Published extends SubmissionStatus("published")
    case PendingReview extends SubmissionStatus("pending-review")
    case Rejected extends SubmissionStatus("rejected")

  object SubmissionStatus:
    def fromLabel(s: String): Option[SubmissionStatus] = values.find(_.label == s)

  /** 通过验证的目录签名条目（publisherClass 的唯一合法来源；A 期无构造点）。 */
  final case class VerifiedCatalogAssertion(publisherClass: PublisherClass)

  /**
   * 分发面七字段的成组形态（账本 Entry.distribution 与 legacy via 迁移映射共用）。
   * publisherClass 与预留空值族在 A 期恒 None/空。
   */
  final case class Distribution(
    origin: Origin,
    trustLevel: TrustLevel,
    distributionOwner: DistributionOwner,
    publisherClass: Option[PublisherClass] = None,
    hubReviewId: Option[String] = None,
    submissionStatus: Option[SubmissionStatus] = None,
    lastCheckedAt: Option[Long] = None
  )

  /**
   * publisherClass 赋值（红绿②）：**只认目录签名条目**——无目录断言 ⇒ None（manifest
   * 自称不产生任何效果）；目录断言胜出（与自称冲突时以目录为准）。
   */
  def assignPublisherClass(
    catalogAssertion: Option[VerifiedCatalogAssertion],
    manifestSelfClaim: Option[PublisherClass]
  ): Option[PublisherClass] =
    catalogAssertion.map(_.publisherClass)

  /**
   * 官方徽章判定（红绿③）：官方根键签名 ∧ publisherClass=official。任一缺失 ⇒ false
   * （A 期无目录断言 ⇒ 恒 false）。
   */
  def officialBadge(signedByOfficialRootKey: Boolean, publisherClass: Option[PublisherClass]): Boolean =
    signedByOfficialRootKey && publisherClass.contains(PublisherClass.Official)

  /**
   * legacy via → §12.1 迁移映射（红绿①默认值单一事实源）：安装期默认与存量 schema 1
   * 升档共用本函数——**禁两套映射**。
   *  - seed / official（厂方渠道：seed=随引擎分发字节；official=厂方索引+双值校验）⇒
   *    origin=seed、owner=seed、official-verified（seed 信任根=分发载体签名；C 期目录
   *    上线后新装才记 hub）；
   *  - git（P2 起安装必带 commit pin）⇒ git-pinned、local、local-import；
   *  - local / https（显式一次性导入固定字节桶）⇒ local-import、local、local-import。
   */
  def factsForLegacyVia(via: Via): Distribution = via match
    case Via.Seed | Via.Official =>
      Distribution(
        origin = Origin.Seed,
        trustLevel = TrustLevel.OfficialVerified,
        distributionOwner = DistributionOwner.Seed
      )
    case Via.Hub =>
      Distribution(
        origin = Origin.Hub,
        trustLevel = TrustLevel.CatalogSigned,
        distributionOwner = DistributionOwner.Hub
      )
    case Via.Git =>
      Distribution(
        origin = Origin.GitPinned,
        trustLevel = TrustLevel.LocalImport,
        distributionOwner = DistributionOwner.Local
      )
    case Via.Local | Via.Https =>
      Distribution(
        origin = Origin.LocalImport,
        trustLevel = TrustLevel.LocalImport,
        distributionOwner = DistributionOwner.Local
      )

end PluginDistributionTrust
