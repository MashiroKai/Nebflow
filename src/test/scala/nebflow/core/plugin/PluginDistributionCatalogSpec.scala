package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.file.Files
import java.security.KeyPair
import java.util.Base64

import scala.concurrent.duration.*

/**
 * 目录 schema v1（C 阶段 · 设计稿 §5.3/§12.2）验收面：
 *  - **解码宽严**：未知字段宽容忽略（前向兼容）；已知安全字段（protocolVersion /
 *    sha256 / size / yanked / publisherClass 闭集 / publisherKeyId）违规即整体拒绝；
 *    可选字段缺失不得推断官方身份或审核通过（publisherClass None = 未断言）。
 *  - **Ed25519 验签**：签名绑定目录规范字节；正确根键通过；篡改 / 错键 / 键不在
 *    信任根 / 算法非 ed25519 / 签名值非 base64 全拒。
 *  - **有效期**：validUntil 过期 ⇒ 拒绝（离线显式 pin 留 D/F，本批不降级验证）。
 *  - **防回滚**：catalogRevision 只进不退（状态文件）；相等幂等；旧序号拒绝。
 *  - **白名单取回**：未显式注册的目录源拒绝（URL 与 dir 同判）；目录文件大小上限。
 *  - **resolve**：完整 id+版本+sha256 与已验目录条目三比对；版本缺席 / sha 不一致 /
 *    yanked / 未知 id ⇒ 拒绝。
 *
 * 全程零真网络：URL 腿以 file:// 夹具驱动（openConnection 同代码路径）。
 */
class PluginDistributionCatalogSpec extends FunSuite:

  override val munitTimeout: FiniteDuration = Duration(120, "s")

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-distro-catalog"))
    PathUtil.setDataRoot(home)
    os.makeDir.all(home / "plugins")
    writeConfig(Json.obj())

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  // ── 夹具：Ed25519 键对 + 签名 ───────────────────────────────

  private def genKey(): KeyPair =
    java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

  /** raw 32 字节公钥 hex（X.509 DER 编码 = 12 字节固定前缀 + raw；信任根配置即此形态）。 */
  private def pubHex(kp: KeyPair): String =
    kp.getPublic.getEncoded.takeRight(32).map("%02x".format(_)).mkString

  private def signBytes(priv: java.security.PrivateKey, data: Array[Byte]): String =
    val sig = java.security.Signature.getInstance("Ed25519")
    sig.initSign(priv)
    sig.update(data)
    Base64.getEncoder.encodeToString(sig.sign())

  private def signatureJson(keyId: String, value: String, algorithm: String = "ed25519"): Json =
    Json.obj("algorithm" -> algorithm.asJson, "keyId" -> keyId.asJson, "value" -> value.asJson)

  private def signatureValueOf(raw: String): String =
    io.circe.parser.parse(raw).toOption.get.hcursor.downField("signature").downField("value").as[String].toOption.get

  // ── 夹具：目录文件 ─────────────────────────────────────────

  private def verJson(
    v: String,
    sha: String,
    size: Long,
    yanked: Boolean = false,
    artifact: String = "artifacts/demo-kit.zip"
  ): Json =
    Json.obj(
      "version" -> v.asJson,
      "channel" -> "stable".asJson,
      "platform" -> "any".asJson,
      "artifact" -> artifact.asJson,
      "sha256" -> sha.asJson,
      "size" -> size.asJson,
      "yanked" -> yanked.asJson
    )

  private def pkgJson(
    id: String,
    keyId: String,
    versions: List[Json],
    publisherClass: Option[String] = None
  ): Json =
    val base = List(
      "id" -> id.asJson,
      "publisherKeyId" -> keyId.asJson,
      "versions" -> versions.asJson
    )
    Json.obj((publisherClass.fold(base)(c => base :+ ("publisherClass" -> c.asJson)))*)

  /** 构建签名目录信封并落盘 `<dir>/catalog.json`；返回 noSpaces 原文。 */
  private def writeCatalog(
    dir: os.Path,
    revision: Long,
    packages: List[Json],
    keyPair: KeyPair,
    keyId: String,
    validUntil: Option[Long] = None,
    catalogExtras: List[(String, Json)] = Nil,
    signatureOverride: Option[Json] = None,
    protocolVersion: Int = 1
  ): String =
    val catalogJson = Json.obj(
      (
        List("protocolVersion" -> protocolVersion.asJson, "catalogRevision" -> revision.asJson) ++
          validUntil.map(u => "validUntil" -> u.asJson).toList ++
          List("packages" -> packages.asJson) ++ catalogExtras
      )*
    )
    val signature = signatureOverride.getOrElse(
      signatureJson(keyId, signBytes(keyPair.getPrivate, PluginDistributionCatalog.canonicalSigningBytes(catalogJson)))
    )
    val envelope = Json.obj("catalog" -> catalogJson, "signature" -> signature)
    val raw = envelope.noSpaces
    os.makeDir.all(dir)
    os.write.over(dir / "catalog.json", raw)
    raw

  end writeCatalog

  /** 配置一个 dir 目录源 + 信任根（键 id → raw hex 公钥）。 */
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

  private val ShaA = "a".repeat(64)
  private val ShaB = "b".repeat(64)

  // ── 解码宽严面 ─────────────────────────────────────────────

  test("decode: valid envelope decodes with all schema v1 fields") {
    val raw = writeCatalog(
      home / "cat-tmp",
      42L,
      List(
        pkgJson(
          "acme.demo-kit",
          "acme-release-1",
          List(verJson("1.5.0", ShaA, 123L)),
          publisherClass = Some("verified")
        )
      ),
      genKey(),
      "acme-release-1"
    )
    PluginDistributionCatalog.decodeSigned(raw) match
      case Left(err) => fail(s"valid catalog rejected: $err")
      case Right(signed) =>
        assertEquals(signed.doc.protocolVersion, 1)
        assertEquals(signed.doc.catalogRevision, 42L)
        val pkg = signed.doc.packages.head
        assertEquals(pkg.id, "acme.demo-kit")
        assertEquals(pkg.publisherKeyId, "acme-release-1")
        assertEquals(pkg.publisherClass, Some(PluginDistributionTrust.PublisherClass.Verified))
        val v = pkg.versions.head
        assertEquals(v.version, "1.5.0")
        assertEquals(v.sha256, ShaA)
        assertEquals(v.size, 123L)
        assertEquals(v.yanked, false)
  }

  test("decode: unknown fields tolerated (§12.2 forward compat), publisherClass absent = no assertion") {
    val withExtras = pkgJson("acme.demo-kit", "acme-release-1", List(verJson("1.5.0", ShaA, 1L)))
      .deepMerge(Json.obj("futureField" -> Json.obj("x" -> 1.asJson)))
    val raw = writeCatalog(
      home / "cat-tmp",
      1L,
      List(withExtras),
      genKey(),
      "acme-release-1",
      catalogExtras = List("hubProvenance" -> "later-phase".asJson)
    )
    PluginDistributionCatalog.decodeSigned(raw) match
      case Left(err) => fail(s"unknown fields must be tolerated: $err")
      case Right(signed) =>
        assertEquals(signed.doc.packages.head.publisherClass, None, "absent optional field must not imply identity")
        assertEquals(signed.doc.packages.head.reviewId, None)
  }

  test("decode: known security field violations reject the whole catalog") {
    val kp = genKey()
    val good = pkgJson("acme.demo-kit", "acme-release-1", List(verJson("1.5.0", ShaA, 1L)))
    def rawWith(pkg: Json): String = writeCatalog(home / "cat-tmp", 1L, List(pkg), kp, "acme-release-1")
    val cases: List[(String, String)] = List(
      ("sha256 not hex", rawWith(pkgJson("acme.demo-kit", "acme-release-1", List(verJson("1.5.0", "short", 1L))))),
      ("negative size", rawWith(pkgJson("acme.demo-kit", "acme-release-1", List(verJson("1.5.0", ShaA, -1L))))),
      (
        "yanked non-bool",
        rawWith(
          pkgJson(
            "acme.demo-kit",
            "acme-release-1",
            List(verJson("1.5.0", ShaA, 1L).deepMerge(Json.obj("yanked" -> "yes".asJson)))
          )
        )
      ),
      (
        "publisherClass outside closed set",
        rawWith(
          pkgJson("acme.demo-kit", "acme-release-1", List(verJson("1.5.0", ShaA, 1L)), publisherClass = Some("wizard"))
        )
      ),
      ("missing publisherKeyId", rawWith(good.deepMerge(Json.obj("publisherKeyId" -> Json.Null)))),
      (
        "protocolVersion != 1",
        writeCatalog(home / "cat-tmp", 1L, List(good), kp, "acme-release-1", protocolVersion = 2)
      )
    )
    cases.foreach { (label, raw) =>
      PluginDistributionCatalog.decodeSigned(raw) match
        case Left(_) => () // expected
        case Right(_) => fail(s"$label must be rejected")
    }
  }

  // ── Ed25519 验签面 ─────────────────────────────────────────

  test(
    "signature: correct root verifies; tampered / wrong key / unknown keyId / bad algorithm / bad base64 all reject"
  ) {
    val dir = home / "cat-sig"
    val kpGood = genKey()
    val kpBad = genKey()
    val raw = writeCatalog(
      dir,
      7L,
      List(pkgJson("acme.demo-kit", "acme-release-1", List(verJson("1.5.0", ShaA, 1L)))),
      kpGood,
      "acme-release-1"
    )
    val signed = PluginDistributionCatalog.decodeSigned(raw).toOption.get
    val rootsGood = Map("acme-release-1" -> pubHex(kpGood))

    // 正确根键
    PluginDistributionCatalog.verifySignature(signed, rootsGood) match
      case Right(doc) => assertEquals(doc.catalogRevision, 7L)
      case Left(err) => fail(s"correct root must verify: $err")

    // 错键（信任根是另一把）
    PluginDistributionCatalog.verifySignature(signed, Map("acme-release-1" -> pubHex(kpBad))) match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogUntrusted))
      case Right(_) => fail("wrong root key must be rejected")

    // keyId 不在信任根
    PluginDistributionCatalog.verifySignature(signed, Map("other-key" -> pubHex(kpGood))) match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogUntrusted))
      case Right(_) => fail("keyId absent from trust roots must be rejected")

    val prevSig = signatureJson("acme-release-1", signatureValueOf(raw))

    // 篡改：目录内容与签名时的字节不一致（同键同 keyId）
    val tamperedRaw = writeCatalog(
      dir,
      8L,
      List(pkgJson("acme.demo-kit", "acme-release-1", List(verJson("9.9.9", ShaA, 1L)))),
      kpGood,
      "acme-release-1",
      signatureOverride = Some(prevSig)
    )
    PluginDistributionCatalog
      .decodeSigned(tamperedRaw)
      .flatMap(PluginDistributionCatalog.verifySignature(_, rootsGood)) match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogUntrusted))
      case Right(_) => fail("tampered catalog must be rejected")

    // 算法非 ed25519
    val badAlgoRaw = writeCatalog(
      dir,
      8L,
      Nil,
      kpGood,
      "acme-release-1",
      signatureOverride = Some(prevSig.deepMerge(Json.obj("algorithm" -> "rsa-4096".asJson)))
    )
    PluginDistributionCatalog
      .decodeSigned(badAlgoRaw)
      .flatMap(PluginDistributionCatalog.verifySignature(_, rootsGood)) match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogUntrusted))
      case Right(_) => fail("non-ed25519 algorithm must be rejected")

    // 签名值非 base64
    val badB64Raw = writeCatalog(
      dir,
      8L,
      Nil,
      kpGood,
      "acme-release-1",
      signatureOverride = Some(prevSig.deepMerge(Json.obj("value" -> "!!!not-base64!!!".asJson)))
    )
    PluginDistributionCatalog
      .decodeSigned(badB64Raw)
      .flatMap(PluginDistributionCatalog.verifySignature(_, rootsGood)) match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogUntrusted))
      case Right(_) => fail("non-base64 signature value must be rejected")
  }

  // ── 取回面：白名单 / 过期 / 防回滚 / 大小上限 ───────────────

  test("loadVerified: unregistered source is refused (whitelist)") {
    val dir = home / "cat-src"
    writeCatalog(dir, 1L, Nil, genKey(), "acme-release-1")
    configureDirSource("known", dir, "acme-release-1", pubHex(genKey()))
    PluginDistributionCatalog.loadVerified("not-configured").unsafeRunSync() match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogSourceUnknown))
      case Right(_) => fail("unregistered catalog source must be refused")
  }

  test("loadVerified: expired validUntil is refused (no silent downgrade)") {
    val dir = home / "cat-src"
    val kp = genKey()
    writeCatalog(dir, 1L, Nil, kp, "acme-release-1", validUntil = Some(System.currentTimeMillis() / 1000L - 60))
    configureDirSource("acme-expired", dir, "acme-release-1", pubHex(kp))
    PluginDistributionCatalog.loadVerified("acme-expired").unsafeRunSync() match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogExpired))
      case Right(_) => fail("expired catalog must be refused")
  }

  test("loadVerified: catalogRevision never rolls back; equal is idempotent") {
    val dir = home / "cat-src"
    val kp = genKey()
    configureDirSource("acme", dir, "acme-release-1", pubHex(kp))

    writeCatalog(dir, 42L, Nil, kp, "acme-release-1")
    PluginDistributionCatalog.loadVerified("acme").unsafeRunSync() match
      case Right(vc) => assertEquals(vc.doc.catalogRevision, 42L)
      case Left(err) => fail(s"revision 42 must load: $err")

    writeCatalog(dir, 41L, Nil, kp, "acme-release-1")
    PluginDistributionCatalog.loadVerified("acme").unsafeRunSync() match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogStale))
      case Right(_) => fail("rollback to an older catalogRevision must be rejected")

    writeCatalog(dir, 42L, Nil, kp, "acme-release-1")
    PluginDistributionCatalog.loadVerified("acme").unsafeRunSync() match
      case Right(vc) => assertEquals(vc.doc.catalogRevision, 42L)
      case Left(err) => fail(s"equal revision must be idempotent: $err")

    writeCatalog(dir, 43L, Nil, kp, "acme-release-1")
    PluginDistributionCatalog.loadVerified("acme").unsafeRunSync() match
      case Right(vc) => assertEquals(vc.doc.catalogRevision, 43L)
      case Left(err) => fail(s"newer revision must advance: $err")
  }

  test("read-only verified load does not advance revision; advancing load records it") {
    val dir = home / "cat-preview"
    val kp = genKey()
    writeCatalog(dir, 17L, Nil, kp, "acme-release-1")
    configureDirSource("preview", dir, "acme-release-1", pubHex(kp))
    val state = home / "plugin-catalog-state" / "preview.json"
    assert(PluginDistributionCatalog.loadVerified("preview", advance = false).unsafeRunSync().isRight)
    assert(!os.exists(state), "preview must not write revision state")
    assert(PluginDistributionCatalog.loadVerified("preview", advance = true).unsafeRunSync().isRight)
    assert(os.exists(state), "install load must record revision state")
  }

  test("loadVerified: catalog file above the size cap is refused") {
    val dir = home / "cat-src"
    val kp = genKey()
    writeCatalog(
      dir,
      1L,
      Nil,
      kp,
      "acme-release-1",
      catalogExtras = List("pad" -> ("x" * (PluginDistributionCatalog.MaxCatalogBytes.toInt + 1024)).asJson)
    )
    configureDirSource("acme-big", dir, "acme-release-1", pubHex(kp))
    PluginDistributionCatalog.loadVerified("acme-big").unsafeRunSync() match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogOversize))
      case Right(_) => fail("oversize catalog must be refused")
  }

  test("loadVerified: url-form source fetches through the same code path (file:// fixture, zero real network)") {
    val dir = home / "cat-url-src"
    val kp = genKey()
    writeCatalog(dir, 5L, Nil, kp, "acme-release-1")
    writeConfig(
      Json.obj(
        "plugins" -> Json.obj(
          "catalogs" -> Json.obj(
            "remote" -> Json.obj("url" -> s"file://${dir / "catalog.json"}".asJson)
          ),
          "catalogTrust" -> Json.obj("roots" -> Json.obj("acme-release-1" -> pubHex(kp).asJson))
        )
      )
    )
    PluginDistributionCatalog.loadVerified("remote").unsafeRunSync() match
      case Right(vc) => assertEquals(vc.sourceId, "remote")
      case Left(err) => fail(s"url-form catalog must load via fetch path: $err")
  }

  test("loadVerified: wrongly-signed catalog is refused before any state write") {
    val dir = home / "cat-src"
    val kp = genKey()
    writeCatalog(dir, 1L, Nil, kp, "acme-release-1")
    // keyId 与信任根登记不一致 ⇒ 验签拒绝（状态文件不得写出）
    configureDirSource("acme-mis", dir, "someone-else", pubHex(kp))
    PluginDistributionCatalog.loadVerified("acme-mis").unsafeRunSync() match
      case Left(err) => assert(err.contains(PluginDistributionCatalog.CodeCatalogUntrusted))
      case Right(_) => fail("signature failure must refuse the catalog")
    assertEquals(os.exists(home / "plugin-catalog-state" / "acme-mis.json"), false, "no state write on refusal")
  }

  // ── resolve（完整身份三比对）────────────────────────────────

  test("resolve: exact id+version+sha matches; version / sha / yanked / unknown-id mismatch rejects") {
    val dir = home / "cat-src"
    val kp = genKey()
    writeCatalog(
      dir,
      1L,
      List(
        pkgJson(
          "acme.demo-kit",
          "acme-release-1",
          List(
            verJson("1.5.0", ShaA, 123L),
            verJson("1.4.0", ShaB, 100L, yanked = true)
          )
        )
      ),
      kp,
      "acme-release-1"
    )
    configureDirSource("acme-res", dir, "acme-release-1", pubHex(kp))
    val doc = PluginDistributionCatalog.loadVerified("acme-res").unsafeRunSync().toOption.get.doc

    PluginDistributionCatalog.resolve(doc, "acme.demo-kit", "1.5.0", ShaA) match
      case Right(r) =>
        assertEquals(r.pkg.id, "acme.demo-kit")
        assertEquals(r.v.version, "1.5.0")
        assertEquals(r.v.sha256, ShaA)
      case Left(err) => fail(s"exact match must resolve: $err")

    PluginDistributionCatalog.resolve(doc, "acme.demo-kit", "9.9.9", ShaA) match
      case Left(err) => assert(err.contains("9.9.9"))
      case Right(_) => fail("version not in catalog must be rejected")

    PluginDistributionCatalog.resolve(doc, "acme.demo-kit", "1.5.0", "c".repeat(64)) match
      case Left(_) => ()
      case Right(_) => fail("sha256 mismatch must be rejected")

    PluginDistributionCatalog.resolve(doc, "acme.demo-kit", "1.4.0", ShaB) match
      case Left(_) => () // yanked ⇒ 拒绝新分发
      case Right(_) => fail("yanked version must be rejected")

    PluginDistributionCatalog.resolve(doc, "other.unknown", "1.5.0", ShaA) match
      case Left(_) => ()
      case Right(_) => fail("unknown id must be rejected")
  }

end PluginDistributionCatalogSpec
