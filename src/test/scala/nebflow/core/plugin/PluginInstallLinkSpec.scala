package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil
import PluginDistributionTrust.PublisherClass
import PluginSourceLedger.{Kind, Via}

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.security.KeyPair
import java.util.Base64
import java.util.zip.{ZipEntry, ZipOutputStream}

import scala.concurrent.duration.*

/**
 * stand:// 安装链接（C 阶段 · 设计稿 §12.6）验收面：
 *  - **解析**：`stand://plugin/install?id=<publisher.name>&v=<version>&s=<sha256>`；
 *    id/v/s 缺一即拒；s 非 64-hex 拒；非专有 scheme / 非 /plugin/install 路径拒；
 *    仅接受 id/v/s，未知或重复参数拒绝。
 *  - **反钓鱼 resolve**：完整 id+版本+sha256 与**已验签目录**条目比对；版本不在目录 /
 *    sha 不一致 / yanked ⇒ 警告并阻止（链接不是信任入口——无已验目录 ⇒ 拒）；
 *    短名拒绝，必须使用完整身份。
 *  - **安装腿汇流**：resolve → plan → apply 走与 official/https 同一条校验落盘路径
 *    （verifyAndStageZip + commitStaged）；账本记 via=hub / kind=catalog、分发面
 *    (hub, catalog-signed, hub)、publisherClass 由已验目录断言赋予、§5.4 meta 五字段。
 *  - **classifyArg**：stand:// 链接形态进 Link 分派，不再误判 git（`://` 标记优先序）。
 *
 * 全程零真网络（dir 目录源）。
 */
class PluginInstallLinkSpec extends FunSuite:

  override val munitTimeout: FiniteDuration = Duration(180, "s")

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path     = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-install-link"))
    PathUtil.setDataRoot(home)
    os.makeDir.all(home / "plugins")
    PluginRegistry.invalidateCache()
    PluginRegistry.resetProvenanceBackfillGate()
    PluginRegistry.setProvenanceBackfillForTest(None)
    writeConfig(Json.obj())

  override def afterAll(): Unit =
    PluginRegistry.setProvenanceBackfillForTest(None)
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  // ── 夹具 ───────────────────────────────────────────────────

  private def genKey(): KeyPair =
    java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

  private def pubHex(kp: KeyPair): String =
    kp.getPublic.getEncoded.takeRight(32).map("%02x".format(_)).mkString

  private def signBytes(priv: java.security.PrivateKey, data: Array[Byte]): String =
    val sig = java.security.Signature.getInstance("Ed25519")
    sig.initSign(priv)
    sig.update(data)
    Base64.getEncoder.encodeToString(sig.sign())

  private def zipBytes(entries: (String, String)*): Array[Byte] =
    val bos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(bos)
    entries.foreach { (name, content) =>
      zos.putNextEntry(new ZipEntry(name))
      zos.write(content.getBytes(UTF_8))
      zos.closeEntry()
    }
    zos.close()
    bos.toByteArray

  private def sha256Hex(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  /** 每个安装测试用独立包名（同一套件内 home 共享，防测试间状态串台）。 */
  private def pluginZip(name: String): Array[Byte] = zipBytes(
    "plugin.json" -> s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.5.0","description":"install-link fixture"}""",
    "skills/hello/skill.md" -> "---\nname: hello\n---\nhello body"
  )

  /** 目录源夹具：签名目录 + 真实 zip 制品；返回 (link, 目录源 dir)。 */
  private def materializeCatalog(sourceId: String, packages: List[Json]): (String, os.Path) =
    val dir  = home / s"cat-$sourceId"
    val kp   = genKey()
    val keyId = "acme-release-1"
    val catalogJson = Json.obj(
      "protocolVersion" -> 1.asJson,
      "catalogRevision" -> 1.asJson,
      "packages" -> packages.asJson
    )
    val signature = Json.obj(
      "algorithm" -> "ed25519".asJson,
      "keyId" -> keyId.asJson,
      "value" -> signBytes(kp.getPrivate, PluginDistributionCatalog.canonicalSigningBytes(catalogJson)).asJson
    )
    os.makeDir.all(dir / "artifacts")
    os.write.over(dir / "catalog.json", Json.obj("catalog" -> catalogJson, "signature" -> signature).noSpaces)
    configureDirSource(sourceId, dir, keyId, pubHex(kp))
    (sourceId, dir)

  private def pkgJson(id: String, versions: List[Json], publisherClass: Option[String] = None): Json =
    val base = List("id" -> id.asJson, "publisherKeyId" -> "acme-release-1".asJson, "versions" -> versions.asJson)
    Json.obj((publisherClass.fold(base)(c => base :+ ("publisherClass" -> c.asJson)))*)

  private def verJson(v: String, sha: String, size: Long, yanked: Boolean = false, artifact: String): Json =
    Json.obj(
      "version" -> v.asJson,
      "channel" -> "stable".asJson,
      "platform" -> "any".asJson,
      "artifact" -> artifact.asJson,
      "sha256" -> sha.asJson,
      "size" -> size.asJson,
      "yanked" -> yanked.asJson
    )

  private def configureDirSource(sourceId: String, dir: os.Path, keyId: String, hex: String): Unit =
    writeConfig(
      Json.obj(
        "plugins" -> Json.obj(
          "catalogs" -> Json.obj(
            sourceId -> Json.obj("dir" -> dir.toString.asJson, "enabled" -> true.asJson)
          ),
          "catalogTrust" -> Json.obj("roots" -> Json.obj(keyId -> hex.asJson))
        )
      )
    )

  private def writeConfig(cfg: Json): Unit =
    os.write.over(home / "stand.json", cfg.noSpaces)

  // ── 解析面 ─────────────────────────────────────────────────

  private val GoodSha = "a".repeat(64)

  test("parse: exact reserved form parses; missing params / bad sha / foreign scheme reject") {
    val raw = s"stand://plugin/install?id=acme.demo-kit&v=1.5.0&s=$GoodSha"
    PluginInstallLink.parse(raw) match
      case Right(link) =>
        assertEquals(link.id, "acme.demo-kit")
        assertEquals(link.version, "1.5.0")
        assertEquals(link.sha256, GoodSha)
      case Left(err) => fail(s"canonical link must parse: $err")

    val rejects: List[(String, String)] = List(
      ("missing id", s"stand://plugin/install?v=1.5.0&s=$GoodSha"),
      ("missing v", s"stand://plugin/install?id=acme.demo-kit&s=$GoodSha"),
      ("missing s", "stand://plugin/install?id=acme.demo-kit&v=1.5.0"),
      ("s not hex", s"stand://plugin/install?id=acme.demo-kit&v=1.5.0&s=short"),
      ("s not 64", s"stand://plugin/install?id=acme.demo-kit&v=1.5.0&s=${"a".repeat(63)}"),
      ("foreign scheme", s"https://plugin/install?id=acme.demo-kit&v=1.5.0&s=$GoodSha"),
      ("foreign path", s"stand://plugin/remove?id=acme.demo-kit&v=1.5.0&s=$GoodSha"),
      ("empty", "")
    )
    rejects.foreach { (label, r) =>
      PluginInstallLink.parse(r) match
        case Left(err) => assert(err.contains(PluginInstallLink.CodeLinkUnparseable), s"$label: $err")
        case Right(_)  => fail(s"$label must be rejected")
    }

    List(s"$raw&futureFlag=1", s"$raw&id=evil.demo-kit").foreach { invalid =>
      PluginInstallLink.parse(invalid) match
        case Left(err) => assert(err.contains(PluginInstallLink.CodeLinkUnparseable))
        case Right(_)  => fail("unknown and duplicate parameters must be rejected")
    }
  }

  // ── 反钓鱼 resolve 面 ──────────────────────────────────────

  private def singlePkgCatalog(
      sourceId: String,
      id: String,
      versions: List[Json],
      publisherClass: Option[String] = None
  ): os.Path =
    materializeCatalog(sourceId, List(pkgJson(id, versions, publisherClass)))._2

  private def loadDoc(sourceId: String): PluginDistributionCatalog.CatalogDoc =
    PluginDistributionCatalog.loadVerified(sourceId).unsafeRunSync() match
      case Right(vc) => vc.doc
      case Left(err) => fail(s"fixture catalog must load: $err")

  test("resolveLink: full identity match resolves; version/sha/yanked mismatch blocks") {
    singlePkgCatalog("acme", "acme.demo-kit", List(
      verJson("1.5.0", GoodSha, 1L, artifact = "artifacts/a.zip"),
      verJson("1.4.0", "b".repeat(64), 1L, yanked = true, artifact = "artifacts/b.zip")
    ))
    val vc = PluginDistributionCatalog.loadVerified("acme").unsafeRunSync().toOption.get

    PluginInstallLink.resolveLink(PluginInstallLink.InstallLink("acme.demo-kit", "1.5.0", GoodSha), vc) match
      case Right(r) => assertEquals(r.v.version, "1.5.0")
      case Left(err) => fail(s"exact link must resolve: $err")

    PluginInstallLink.resolveLink(PluginInstallLink.InstallLink("acme.demo-kit", "9.9.9", GoodSha), vc) match
      case Left(err) => assert(err.contains(PluginInstallLink.CodeLinkNotInCatalog))
      case Right(_)  => fail("version not in catalog must block")

    PluginInstallLink.resolveLink(PluginInstallLink.InstallLink("acme.demo-kit", "1.5.0", "c".repeat(64)), vc) match
      case Left(err) => assert(err.contains(PluginInstallLink.CodeLinkNotInCatalog))
      case Right(_)  => fail("sha mismatch must block")

    PluginInstallLink.resolveLink(PluginInstallLink.InstallLink("acme.demo-kit", "1.4.0", "b".repeat(64)), vc) match
      case Left(err) => assert(err.contains(PluginInstallLink.CodeLinkNotInCatalog))
      case Right(_)  => fail("yanked version must block")
  }

  test("resolveLink: short name is rejected even when unambiguous") {
    singlePkgCatalog("solo", "acme.demo-kit", List(verJson("1.5.0", GoodSha, 1L, artifact = "artifacts/a.zip")))
    val solo = PluginDistributionCatalog.loadVerified("solo").unsafeRunSync().toOption.get
    PluginInstallLink.resolveLink(PluginInstallLink.InstallLink("demo-kit", "1.5.0", GoodSha), solo) match
      case Left(_) => ()
      case Right(_) => fail("short names must be rejected")

    // 同一目录内两个发布者都用短名 demo-kit ⇒ 冲突
    materializeCatalog("clash", List(
      pkgJson("acme.demo-kit", List(verJson("1.5.0", GoodSha, 1L, artifact = "artifacts/a.zip"))),
      pkgJson("beta.demo-kit", List(verJson("1.5.0", GoodSha, 1L, artifact = "artifacts/a2.zip")))
    ))
    val clash = PluginDistributionCatalog.loadVerified("clash").unsafeRunSync().toOption.get
    PluginInstallLink.resolveLink(PluginInstallLink.InstallLink("demo-kit", "1.5.0", GoodSha), clash) match
      case Left(err) => assert(err.contains(PluginInstallLink.CodeLinkShortName))
      case Right(_)  => fail("short-name conflict must demand full identity")
  }

  test("resolveAcrossConfigured: no configured catalogs ⇒ refuse (link is not a trust entry)") {
    writeConfig(Json.obj())
    PluginInstallLink
      .resolveAcrossConfigured(PluginInstallLink.InstallLink("acme.demo-kit", "1.5.0", GoodSha))
      .unsafeRunSync() match
      case Left(err) => ()
      case Right(_)  => fail("with no verified catalog the link must not resolve")
  }

  // ── classifyArg：stand:// 不再误判 git ──────────────────────

  test("classifyArg: stand:// link routes to the Link leg (not git despite '://')") {
    PluginSourceInstall.classifyArg(s"stand://plugin/install?id=acme.demo-kit&v=1.5.0&s=$GoodSha") match
      case Right(PluginSourceInstall.ArgClass.Link(raw)) => assertEquals(raw, s"stand://plugin/install?id=acme.demo-kit&v=1.5.0&s=$GoodSha")
      case other => fail(s"stand:// link must classify as Link, got $other")
    // 既有三态零变化：git 标记仍优先生效
    PluginSourceInstall.classifyArg("https://github.com/o/r.git#v1.2.0") match
      case Right(PluginSourceInstall.ArgClass.Git(_, _)) => ()
      case other => fail(s"git URL classification must be unchanged, got $other")
  }

  // ── 安装腿汇流（resolve → plan → apply 同一条校验落盘路径）──

  test("installFromLink: verified catalog install lands via=hub with full provenance") {
    val zip = pluginZip("demo-kit")
    val sha  = sha256Hex(zip)
    val dir  = singlePkgCatalog("install-src", "acme.demo-kit", List(
      verJson("1.5.0", sha, zip.length.toLong, artifact = "artifacts/demo-kit-1.5.0.zip")
    ), publisherClass = Some("verified"))
    os.write.over(dir / "artifacts" / "demo-kit-1.5.0.zip", zip)

    PluginSourceInstall
      .installFromLink(s"stand://plugin/install?id=acme.demo-kit&v=1.5.0&s=$sha")
      .unsafeRunSync() match
      case Left(err) => fail(s"catalog install must succeed: $err")
      case Right(msg) =>
        assert(msg.contains("demo-kit"))

    // 落盘 + 在位即信任
    val installed = home / "plugins" / "demo-kit"
    assert(os.isfile(installed / "plugin.json"), "plugin must land under plugins/")

    // 账本：分发面 + §5.4 meta + publisherClass 由已验目录断言赋予
    PluginSourceLedger.query("demo-kit") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.via, Via.Hub)
        assertEquals(e.kind, Kind.Catalog)
        assertEquals(e.distribution.origin, PluginDistributionTrust.Origin.Hub)
        assertEquals(e.distribution.trustLevel, PluginDistributionTrust.TrustLevel.CatalogSigned)
        assertEquals(e.distribution.distributionOwner, PluginDistributionTrust.DistributionOwner.Hub)
        assertEquals(e.distribution.publisherClass, Some(PublisherClass.Verified))
        assertEquals(e.distributionId, Some("acme.demo-kit"))
        assertEquals(e.publisherKeyId, Some("acme-release-1"))
        assertEquals(e.artifactSha256, Some(sha))
        assertEquals(e.validationSummary, Some("catalog-zip-sha256@1"))
        assertEquals(e.version, "1.5.0")
        // digest 硬不变量：账本值 == 现算值
        PluginRegistry.computeDigest(installed) match
          case Right((d, _)) => assertEquals(e.digest, d)
          case Left(err)     => fail(s"digest recomputation failed: $err")
      case other => fail(s"ledger must record the catalog install, got $other")
  }

  test("installFromLink: anti-phishing rejection installs nothing (version not in catalog)") {
    val zip = pluginZip("block-kit")
    val sha  = sha256Hex(zip)
    val dir  = singlePkgCatalog("block-src", "acme.block-kit", List(
      verJson("1.5.0", sha, zip.length.toLong, artifact = "artifacts/block-kit.zip")
    ))
    os.write.over(dir / "artifacts" / "block-kit.zip", zip)

    PluginSourceInstall
      .installFromLink(s"stand://plugin/install?id=acme.block-kit&v=9.9.9&s=$sha")
      .unsafeRunSync() match
      case Left(err) => assert(err.contains(PluginInstallLink.CodeLinkNotInCatalog))
      case Right(_)  => fail("version not in catalog must block the install")
    assertEquals(os.exists(home / "plugins" / "block-kit"), false, "no plugin directory may appear on a blocked link")
  }

  test("installFromLink: unsigned catalog refuses before install (link is not a trust entry)") {
    // 目录内容合法但信任根登记的是另一把键 ⇒ 验签拒 ⇒ 安装拒
    val zip = pluginZip("ghost-kit")
    val sha  = sha256Hex(zip)
    val kp   = genKey()
    val dir  = home / "cat-unsigned"
    val catalogJson = Json.obj(
      "protocolVersion" -> 1.asJson,
      "catalogRevision" -> 1.asJson,
      "packages" -> List(pkgJson("acme.ghost-kit", List(
        verJson("1.5.0", sha, zip.length.toLong, artifact = "artifacts/ghost-kit.zip")
      ))).asJson
    )
    val signature = Json.obj(
      "algorithm" -> "ed25519".asJson,
      "keyId" -> "acme-release-1".asJson,
      "value" -> signBytes(kp.getPrivate, PluginDistributionCatalog.canonicalSigningBytes(catalogJson)).asJson
    )
    os.makeDir.all(dir / "artifacts")
    os.write.over(dir / "catalog.json", Json.obj("catalog" -> catalogJson, "signature" -> signature).noSpaces)
    os.write.over(dir / "artifacts" / "ghost-kit.zip", zip)
    writeConfig(
      Json.obj(
        "plugins" -> Json.obj(
          "catalogs" -> Json.obj("unsigned" -> Json.obj("dir" -> dir.toString.asJson, "enabled" -> true.asJson)),
          "catalogTrust" -> Json.obj("roots" -> Json.obj("acme-release-1" -> pubHex(genKey()).asJson))
        )
      )
    )

    PluginSourceInstall
      .installFromLink(s"stand://plugin/install?id=acme.ghost-kit&v=1.5.0&s=$sha")
      .unsafeRunSync() match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogUntrusted))
      case Right(_)  => fail("untrusted catalog must refuse the install")
    assertEquals(os.exists(home / "plugins" / "ghost-kit"), false, "untrusted catalog must not install anything")
  }

end PluginInstallLinkSpec
