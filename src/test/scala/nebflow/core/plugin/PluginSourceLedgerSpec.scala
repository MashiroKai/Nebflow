package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

import scala.concurrent.duration.*

/**
 * provenance 账本（远程发布源设计稿 §2.1/§2.4，P1 验收）：
 *  - **安装腿落账**：本地/git 安装 → 账本字段齐全 + digest 与 `computeDigest` 一致；
 *  - **digest 硬不变量**：账本在包目录外——先装→记账→重算 digest 前后相等，包目录文件集
 *    不增不减；
 *  - **unknown-source**：缺账本 ⇒ 明确 unknown-source 不猜；单包账本损坏 ⇒ 该包降级、
 *    他包零影响；
 *  - **存量回填**：seed 树内 ⇒ via=seed + 以当前 digest 为基线 pin；seed 外 ⇒ via=local；
 *    幂等（二次回填零改写，mtime 断言）；回填绝不改包目录字节；
 *  - **首查挂钩**：scan() 首查触发一次回填（一次性闸），二次扫描不再回填。
 */
class PluginSourceLedgerSpec extends FunSuite:

  // 宿主共享机器 + git 子进程冷启动 ⇒ 留足余量（断言强度不变）
  override val munitTimeout: FiniteDuration = Duration(180, "s")

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path     = os.Path("/tmp")
  private val fixtureRoot: os.Path = os.pwd / "target" / "plugin-source-ledger"

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-source-ledger"))
    PathUtil.setDataRoot(home)
    os.makeDir.all(home / "plugins")
    os.remove.all(fixtureRoot)
    os.makeDir.all(fixtureRoot)
    PluginRegistry.invalidateCache()
    PluginRegistry.resetProvenanceBackfillGate()
    PluginRegistry.setProvenanceBackfillForTest(None)

  override def afterAll(): Unit =
    PluginRegistry.setProvenanceBackfillForTest(None)
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()
    try os.remove.all(fixtureRoot)
    catch case _: Exception => ()

  // ── 夹具 ──────────────────────────────────────────────────

  private def manifestFor(name: String): String =
    s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$name fixture"}"""

  private def mkPlugin(dir: os.Path, name: String): os.Path =
    os.makeDir.all(dir / "skills" / "s")
    os.write.over(dir / "plugin.json", manifestFor(name))
    os.write.over(dir / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: fixture\n---\nbody\n")
    dir

  /** 本地目录安装（install 腿真实现），随后可清掉账本目录——给回填/unknown-source 测试一个干净的「前 P1 形态」。 */
  private def installLocal(name: String): os.Path =
    val src = mkPlugin(fixtureRoot / "src" / name, name)
    val res = PluginRegistry.installFrom(src.toString).unsafeRunSync()
    assert(res.isRight, s"install fixture '$name' must succeed, got: $res")
    src

  private def dropLedgers(): Unit = os.remove.all(PluginSourceLedger.ledgerDir)

  private def ledgerFile(name: String): os.Path = PluginSourceLedger.ledgerDir / s"$name.json"

  private def fileSet(d: os.Path): Set[String] =
    os.walk(d).filter(os.isFile).map(_.relativeTo(d).toString).toSet

  private def pluginDef(name: String, dir: os.Path, digest: String): PluginRegistry.PluginDef =
    PluginRegistry.PluginDef(
      name = name,
      version = "1.0.0",
      description = "",
      author = "",
      skills = Nil,
      mcpServers = Map.empty,
      toolsExtension = Nil,
      digest = digest,
      fileCount = 2,
      warnings = Nil,
      trust = PluginRegistry.TrustStatus.Trusted(0, digest),
      dir = dir.toString
    )

  // ── 安装腿落账 + digest 不变量 ─────────────────────────────

  test("安装腿(本地): 落账字段齐全 + digest 不变量（先装→记账→重算相等）+ 包目录零新增字节"):
    PluginRegistry.invalidateCache()
    val name = "local-ledger-fixture"
    val src = installLocal(name)
    val installed = home / "plugins" / name

    // 包目录文件集 = 恰好夹具两文件（账本没有写进包目录——硬不变量的字节面）
    assertEquals(fileSet(installed), Set("plugin.json", "skills/s/SKILL.md"))

    // digest 不变量：安装(含记账)之后重算 == 记账值；且与源目录一致（拷贝语义未变）
    val onDisk = PluginRegistry.computeDigest(installed).toOption.get._1
    val fromSrc = PluginRegistry.computeDigest(src).toOption.get._1
    assertEquals(onDisk, fromSrc, "安装（含账本落账）前后包 digest 必须相等")

    PluginSourceLedger.query(name) match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.name, name)
        assertEquals(e.kind, PluginSourceLedger.Kind.Local)
        assertEquals(e.via, PluginSourceLedger.Via.Local)
        assertEquals(e.url, Some(src.toString), "local 腿 url = 解析后的绝对源路径")
        assertEquals(e.pin, None)
        assertEquals(e.version, "1.0.0")
        assertEquals(e.digest, onDisk, "账本 digest == computeDigest 现算值")
        assert(e.installedAt > 0L, "installedAt 必须落值")
      case PluginSourceLedger.UnknownSource(reason) => fail(s"expected Known, got unknown-source: $reason")

    // 账本文件在包目录**外**（<dataRoot>/plugin-sources/，plugins/ 的兄弟目录）
    assert(
      !PluginSourceLedger.ledgerDir.toString.startsWith((home / "plugins").toString),
      "账本根必须在 plugins/ 之外（provenance 不入包）"
    )
    assert(os.exists(ledgerFile(name)))

    // 落账后的原始 JSON 携带 schema 版本位
    val raw = os.read(ledgerFile(name))
    assert(raw.contains("\"schema\":1"), s"账本应携带 schema 版本位, got: $raw")

  test("安装腿(git): pin clone 落账，pin = Commit(tag, 解引用 commit)（零网络；P2 起浮动 HEAD 退役，#tag 必填）"):
    PluginRegistry.invalidateCache()
    val name = "git-ledger-fixture"
    val repo = mkPlugin(fixtureRoot / "repos" / name, name)
    def git(args: String*): String =
      val res = os.proc(Seq("git") ++ args).call(check = false, cwd = repo)
      assert(res.exitCode == 0, s"git ${args.mkString(" ")} failed: ${res.err.text()}")
      res.out.text().trim
    git("init")
    git("config", "user.email", "spec@example.com")
    git("config", "user.name", "spec")
    git("add", "-A")
    git("commit", "-q", "-m", "fixture")
    git("tag", "v1")
    val head = git("rev-parse", "HEAD")
    assertEquals(head.length, 40, "fixture repo HEAD must be a 40-hex commit")

    // P2 起 git 源 pin 必填（设计 §4.1「去掉浮动 HEAD语义」+ §2.2B）：<url>.git#<tag> 形态
    val source = s"$repo/.git#v1" // 以 .git 结尾 ⇒ git 源；本地路径 clone，零网络
    val res = PluginRegistry.installFrom(source).unsafeRunSync()
    assert(res.isRight, s"local git clone install must succeed, got: $res")

    PluginSourceLedger.query(name) match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.kind, PluginSourceLedger.Kind.Git)
        assertEquals(e.via, PluginSourceLedger.Via.Git)
        assertEquals(e.url, Some(s"$repo/.git"), "url 记仓库地址（tag 在 pin 字段承载——来源可回指且不可变锚分离）")
        assertEquals(e.pin, Some(PluginSourceLedger.Pin.Commit(Some("v1"), head)), "pin 必须记录 tag + 安装时刻解引用 commit")
        assertEquals(e.digest, PluginRegistry.computeDigest(home / "plugins" / name).toOption.get._1)
      case PluginSourceLedger.UnknownSource(reason) => fail(s"expected Known, got unknown-source: $reason")

  // ── unknown-source / 损坏降级 ──────────────────────────────

  test("缺账本 ⇒ 明确 unknown-source（不猜）"):
    PluginRegistry.invalidateCache()
    installLocal("no-ledger-fixture")
    dropLedgers()
    PluginSourceLedger.query("no-ledger-fixture") match
      case PluginSourceLedger.UnknownSource(reason) =>
        assert(reason.contains("unknown-source"), s"拒绝文案必须点名 unknown-source, got: $reason")
      case PluginSourceLedger.Known(e) => fail(s"expected UnknownSource, got: $e")

  test("损坏账本降级：单包坏 = 该包 unknown-source，不影响他包"):
    PluginRegistry.invalidateCache()
    val bad  = "corrupt-x"
    val good = "still-known-fixture"
    installLocal(bad)
    installLocal(good)
    dropLedgers()
    os.makeDir.all(PluginSourceLedger.ledgerDir) // 损坏注入需手写账本文件（write.over 不建父目录）
    val _ = PluginSourceLedger.recordInstall(
      good, PluginSourceLedger.Kind.Local, None, None, "1.0.0", "d" * 64, PluginSourceLedger.Via.Local
    )

    val validEntry =
      s"""{"schema":1,"name":"$bad","kind":"git","pin":{"commit":"${"a" * 40}"},"version":"1.0.0","digest":"${"b" * 64}","installedAt":1793264715,"via":"git"}"""
    // 损坏注入走 circe 对象变异（字符串 replace 对引号拼写脆弱——曾整段漏改导致注入失效）
    val validObj = io.circe.parser.parse(validEntry) match
      case Right(json) => json.asObject.getOrElse(munit.Assertions.fail("fixture entry must be an object"))
      case Left(err)   => munit.Assertions.fail(s"fixture entry unparseable: ${err.message}")
    def variant(patch: io.circe.JsonObject => io.circe.JsonObject): String =
      io.circe.Json.fromJsonObject(patch(validObj)).noSpaces
    val corruptions: List[(String, String)] = List(
      ("not json{", "unparseable"),
      ("[]", "not an object"),
      (variant(_.add("digest", io.circe.Json.fromString("short"))) , "digest 64-hex"),
      (variant(_.add("via", io.circe.Json.fromString("aliens"))) , "via 枚举外"),
      (variant(_.add("kind", io.circe.Json.fromString("ftp"))) , "kind 枚举外"),
      (variant(_.add("installedAt", io.circe.Json.fromString("yesterday"))) , "installedAt 类型"),
      (variant(o => o.remove("pin").add("pin", io.circe.Json.obj("commit" -> io.circe.Json.fromString("abc")))) , "pin.commit 40-hex"),
      (variant(_.add("name", io.circe.Json.fromString("someone-else"))) , "名实不符")
    )
    corruptions.foreach { (body, label) =>
      os.write.over(ledgerFile(bad), body)
      PluginSourceLedger.query(bad) match
        case PluginSourceLedger.UnknownSource(reason) =>
          assert(reason.nonEmpty, s"损坏形态[$label] 必须给出原因")
        case PluginSourceLedger.Known(e) => fail(s"损坏形态[$label] 必须降级 unknown-source, got: $e")
    }
    // 他包零影响
    PluginSourceLedger.query(good) match
      case PluginSourceLedger.Known(e)          => assertEquals(e.digest, "d" * 64)
      case PluginSourceLedger.UnknownSource(reason) => fail(s"他包不得受坏账本牵连, got: $reason")

  // ── 读写单点往返 ──────────────────────────────────────────

  test("写读往返：recordInstall → query 逐字段相等（pin 两形态）+ queryAll 逐包"):
    val commit40 = "e" * 40
    val _ = PluginSourceLedger.recordInstall(
      "roundtrip-fixture", PluginSourceLedger.Kind.Git, Some("https://github.com/o/r.git"),
      Some(PluginSourceLedger.Pin.Commit(Some("v1.6.0"), commit40)), "1.6.0", "f" * 64, PluginSourceLedger.Via.Git
    )
    PluginSourceLedger.query("roundtrip-fixture") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.kind, PluginSourceLedger.Kind.Git)
        assertEquals(e.via, PluginSourceLedger.Via.Git)
        assertEquals(e.url, Some("https://github.com/o/r.git"))
        assertEquals(e.pin, Some(PluginSourceLedger.Pin.Commit(Some("v1.6.0"), commit40)))
        assertEquals(e.version, "1.6.0")
        assertEquals(e.digest, "f" * 64)
      case PluginSourceLedger.UnknownSource(reason) => fail(s"expected Known, got: $reason")

    // 同名重写（唯一写点的覆盖语义——update 腿 P2/P3 复用）：digest 基线 pin 形态
    val _2 = PluginSourceLedger.recordInstall(
      "roundtrip-fixture", PluginSourceLedger.Kind.Official, None,
      Some(PluginSourceLedger.Pin.DigestBasis("9" * 64)), "1.7.0", "8" * 64, PluginSourceLedger.Via.Official
    )
    PluginSourceLedger.query("roundtrip-fixture") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.pin, Some(PluginSourceLedger.Pin.DigestBasis("9" * 64)))
        assertEquals(e.version, "1.7.0")
        assertEquals(e.via, PluginSourceLedger.Via.Official)
      case PluginSourceLedger.UnknownSource(reason) => fail(s"expected Known, got: $reason")

    val all = PluginSourceLedger.queryAll(List("roundtrip-fixture", "never-installed-fixture"))
    assert(all("roundtrip-fixture").isInstanceOf[PluginSourceLedger.Known])
    assert(all("never-installed-fixture").isInstanceOf[PluginSourceLedger.UnknownSource])

  // ── 存量回填（分类 / 幂等 / digest 不变量）───────────────────

  test("回填分类: seed 树内 ⇒ via=seed + 以当前 digest 为基线 pin；seed 外 ⇒ via=local"):
    PluginRegistry.invalidateCache()
    val seedName = "visual-report" // seed 树真实成员（src/main/resources/seed/plugins/）
    installLocal(seedName)
    installLocal("bf-local-fixture")
    dropLedgers() // 模拟存量：账本出现之前安装的包

    val installedSeed  = home / "plugins" / seedName
    val installedLocal = home / "plugins" / "bf-local-fixture"
    val seedDigest     = PluginRegistry.computeDigest(installedSeed).toOption.get._1
    val localDigest    = PluginRegistry.computeDigest(installedLocal).toOption.get._1

    val report = PluginSourceLedger.backfill(
      List(pluginDef(seedName, installedSeed, seedDigest), pluginDef("bf-local-fixture", installedLocal, localDigest))
    )
    assertEquals(report.written.sorted, List("bf-local-fixture", seedName))
    assertEquals(report.failed, Nil)

    PluginSourceLedger.query(seedName) match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.via, PluginSourceLedger.Via.Seed, "seed 树成员 ⇒ via=seed")
        assertEquals(e.kind, PluginSourceLedger.Kind.Official)
        assertEquals(e.pin, Some(PluginSourceLedger.Pin.DigestBasis(seedDigest)), "基线 pin = 当前盘上 digest（非 seed 树 digest）")
        assertEquals(e.digest, seedDigest)
        assertEquals(e.url, None, "回填不猜 url")
      case PluginSourceLedger.UnknownSource(reason) => fail(s"expected Known, got: $reason")
    // 基线 pin 的确是「本机现值」：与 seed 树官方 digest 不同（夹具内容 ≠ 出厂内容）
    val seedTreeDigest = OfficialPackages.allowlist().get(seedName)
    assert(seedTreeDigest.isDefined, "seed 树必须含该包（分类前提自检）")
    assert(seedTreeDigest.get != seedDigest, "夹具 digest 与出厂 digest 不同才有区分度")

    PluginSourceLedger.query("bf-local-fixture") match
      case PluginSourceLedger.Known(e) =>
        assertEquals(e.via, PluginSourceLedger.Via.Local)
        assertEquals(e.kind, PluginSourceLedger.Kind.Local)
        assertEquals(e.pin, None)
        assertEquals(e.url, None, "local 回填不猜 url")
        assertEquals(e.digest, localDigest)
      case PluginSourceLedger.UnknownSource(reason) => fail(s"expected Known, got: $reason")

  test("回填幂等: 二次回填零改写（mtime + 字节不变）且绝不改包目录字节"):
    PluginRegistry.invalidateCache()
    val defs = os.list(home / "plugins").filter(os.isDir).toList.flatMap { d =>
      PluginRegistry.computeDigest(d).toOption.map(dg => pluginDef(d.last, d, dg._1))
    }
    assert(defs.nonEmpty, "回填幂等测试需要已装夹具")
    dropLedgers()
    val first = PluginSourceLedger.backfill(defs)
    assert(first.written.nonEmpty, "首轮回填必须落账")

    val before = os.list(PluginSourceLedger.ledgerDir).filter(_.ext == "json").map { f =>
      f.last -> (os.mtime(f), new String(os.read.bytes(f), UTF_8))
    }.toMap
    val pkgDigestsBefore = defs.map(p => p.name -> PluginRegistry.computeDigest(os.Path(p.dir)).toOption.get._1).toMap
    val pkgFilesBefore   = defs.map(p => p.name -> fileSet(os.Path(p.dir))).toMap

    // 二次 boot（同实现重放）：全部 skip，零写入
    val second = PluginSourceLedger.backfill(defs)
    assertEquals(second.written, Nil, "已记账的包二次回填必须零改写")
    assertEquals(second.alreadyRecorded.sorted, defs.map(_.name).sorted)
    assertEquals(second.failed, Nil)

    val after = os.list(PluginSourceLedger.ledgerDir).filter(_.ext == "json").map { f =>
      f.last -> (os.mtime(f), new String(os.read.bytes(f), UTF_8))
    }.toMap
    assertEquals(after, before, "二次回填后账本文件 mtime 与字节必须逐项不变（零改写）")

    // digest 不变量（回填面）：包 digest 与文件集前后相等
    val pkgDigestsAfter = defs.map(p => p.name -> PluginRegistry.computeDigest(os.Path(p.dir)).toOption.get._1).toMap
    assertEquals(pkgDigestsAfter, pkgDigestsBefore, "回填绝不改包 digest")
    val pkgFilesAfter = defs.map(p => p.name -> fileSet(os.Path(p.dir))).toMap
    assertEquals(pkgFilesAfter, pkgFilesBefore, "回填绝不增删包目录文件")

  // ── 首查挂钩（scan 一次性闸）────────────────────────────────

  test("scan() 首查触发一次回填；二次扫描零回填（一次性闸）"):
    PluginRegistry.invalidateCache()
    var invocations = 0
    PluginRegistry.setProvenanceBackfillForTest(Some { defs =>
      invocations += 1
      PluginSourceLedger.backfill(defs)
    })
    try
      PluginRegistry.resetProvenanceBackfillGate()
      PluginRegistry.invalidateCache()
      val _ = PluginRegistry.scan().unsafeRunSync()
      assertEquals(invocations, 1, "首查必须恰好触发一次回填")
      val _ = PluginRegistry.scan().unsafeRunSync()
      assertEquals(invocations, 1, "一次性闸：二次扫描不得再回填")
    finally PluginRegistry.setProvenanceBackfillForTest(None)

end PluginSourceLedgerSpec
