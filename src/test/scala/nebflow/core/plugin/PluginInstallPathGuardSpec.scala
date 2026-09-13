package nebflow.core.plugin

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * 点路径 / 祖先路径守卫（2026-09-13 批，#344 点路径）定向验证。
 *
 * 站点 = `PluginRegistry.installFromSync` 的本地路径分支（`os.Path(source, os.pwd)` →
 * `os.copy`）；判据 = **canonical 路径比对**（`toRealPath` 主判据 + `normalize` 回落；
 * 相对基准 = `os.pwd`）。站外面（CLI 入口 `PluginCommand.scala:77` 的 isEmpty 守卫、
 * CliRouter token 过滤）本批**零改动**。
 *
 * 负控（`.` / `./` / cwd 绝对形式 / cwd 祖先 / 符号链接指向 cwd / 空串）⇒ 拒绝 + 零拷贝 + 零落盘；
 * 正控（正常相对子目录、绝对路径（cwd 内外））⇒ 行为不变（正常安装）。
 * 夹具放 `target/pluginguard-path-guard/`（gitignore 面），收尾清理。
 */
class PluginInstallPathGuardSpec extends FunSuite:

  // 宿主共享机器（多会话并行）+ 系统临时目录条目多（installTmpResidue 会列举它）⇒
  // 给首轮冷启动留足余量（断言强度不变，仅超时阈值）
  override val munitTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.Duration(120, "s")

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")
  private val fixtureRoot: os.Path = os.pwd / "target" / "pluginguard-path-guard"

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-path-guard"))
    PathUtil.setDataRoot(home)
    os.makeDir.all(home / "plugins")
    println(s"[guard-spec] cwd = ${os.pwd}")
    println(s"[guard-spec] dataRoot = ${home}")

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home) catch case _: Exception => ()
    try os.remove.all(fixtureRoot) catch case _: Exception => ()

  private def mkPlugin(dir: os.Path, name: String): os.Path =
    os.makeDir.all(dir / "skills" / "s")
    os.write.over(dir / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0"}""")
    os.write.over(dir / "skills" / "s" / "SKILL.md", "---\nname: s\ndescription: fixture\n---\nbody\n")
    dir

  private def installedDir(name: String): os.Path = home / "plugins" / name

  private def treeAsText(d: os.Path): Map[String, String] =
    os.walk(d).filter(os.isFile).map(p =>
      p.relativeTo(d).toString -> new String(os.read.bytes(p), UTF_8)).toMap

  /** 临时安装目录残留（`os.temp.dir(prefix = "nb-plugin-install")` 落在 `java.io.tmpdir`）
    * —— 清理语义（非破坏性 + finally）的证据面。 */
  private def installTmpResidue(): List[String] =
    val tmp = os.Path(System.getProperty("java.io.tmpdir"))
    os.list(tmp).filter(_.last.startsWith("nb-plugin-install")).map(_.last).toList.sorted

  // ── 判据本体（canonical 比对，非字符串比对）───────────────
  test("判据: canonical 比对 —— `.`/`./.`/<cwd>/./ 与 cwd 祖先命中；子目录不命中"):
    assert(PluginRegistry.isCwdOrAncestor(os.Path(".", os.pwd)), "`.` 必须命中")
    assert(PluginRegistry.isCwdOrAncestor(os.Path("./.", os.pwd)), "`./.` 必须命中")
    assert(PluginRegistry.isCwdOrAncestor(os.Path(os.pwd.toString + "/./", os.pwd)),
      "`<cwd>/./` 必须命中（词法回落也能消除）")
    assert(PluginRegistry.isCwdOrAncestor(os.pwd / os.up), "cwd 祖先是必须命中")
    assert(!PluginRegistry.isCwdOrAncestor(os.pwd / "target"), "cwd 子目录不得命中（正控）")
    assert(!PluginRegistry.isCwdOrAncestor(os.Path(home.toString)), "home 目录不得命中（正控）")

  // ── 负控：点路径 / 祖先 / 符号链接 / 空串 ⇒ 拒绝，零拷贝 ────
  test("负控: `.`/`./`/cwd 绝对形式/cwd 祖先/空串 ⇒ 拒绝，且不触发递归拷贝"):
    PluginRegistry.invalidateCache()
    val residueBefore = installTmpResidue()
    val cases = List(
      "." -> "`.`",
      "./" -> "`./`",
      os.pwd.toString -> "cwd 的绝对形式"
    ) ++ (if os.isDir(os.pwd / os.up) then List((os.pwd / os.up).toString -> "cwd 的祖先") else Nil)
    for (source, label) <- cases do
      val t0 = System.nanoTime()
      val res = PluginRegistry.installFrom(source).unsafeRunSync()
      val ms = (System.nanoTime() - t0) / 1000000
      println(s"""[guard-spec] installFrom("$source") [$label] → ${res.fold(identity, identity)} (${ms}ms)""")
      res match
        case Left(err) =>
          assert(err.contains("current working directory"),
            s"$label 必须给出「为何拒绝」的可行动文案，got: $err")
          assert(err.contains("subdirectory") && err.contains("absolute path"),
            s"$label 文案必须给建议写法（子目录名 / 绝对路径），got: $err")
          // 旧形态（无守卫）会先 os.copy 整棵 cwd 再在 staged 里找 plugin.json ⇒ 报「no plugin.json」；
          // 本批守卫在 os.copy **之前**返回 ⇒ 报文里不出现该字面（= 拷贝步未执行的取证）
          assert(!err.contains("plugin.json"),
            s"$label 必须在拷贝步之前被拒（不得落到 manifest 检查），got: $err")
        case Right(msg) =>
          fail(s"$label 必须被拒绝（否则把 cwd 递归拷进临时目录），却成功：$msg")

    // 空串形态：CLI 侧既有守卫（PluginCommand.scala:77）判不可达 ⇒ 本批**不另加守卫**；
    // 同一 canonical 判据天然覆盖它（记录该覆盖面，不作独立防线）
    val empty = PluginRegistry.installFrom("").unsafeRunSync()
    println(s"""[guard-spec] installFrom("") [空串（同判据自然覆盖）] → ${empty.fold(identity, identity)}""")
    assert(empty.isLeft, "空串在 CLI 侧不可达，但同一判据仍会拒绝（自然覆盖面）")

    assertEquals(os.list(home / "plugins").toList, Nil, "负控路径零落盘（plugins/ 下无新目录）")
    assertEquals(installTmpResidue(), residueBefore, "负控路径零临时残留（非破坏性 + finally 清理语义保持）")

  test("负控: 符号链接指向 cwd ⇒ 按 canonical（toRealPath）判拒"):
    val link = fixtureRoot / "cwd-link"
    os.makeDir.all(fixtureRoot)
    try
      if os.exists(link) then os.remove(link)
      Files.createSymbolicLink(link.toNIO, os.pwd.toNIO)
      assert(PluginRegistry.isCwdOrAncestor(link), "指向 cwd 的符号链接必须命中（解链接后比对）")
      val res = PluginRegistry.installFrom(link.toString).unsafeRunSync()
      println(s"""[guard-spec] installFrom("$link") [symlink→cwd] → ${res.fold(identity, identity)}""")
      assert(res.isLeft, s"指向 cwd 的符号链接必须被拒，got: $res")
      assert(res.swap.toOption.getOrElse("").contains("current working directory"),
        s"拒绝文案须为同一条可行动文案，got: $res")
    catch case _: UnsupportedOperationException => println("[guard-spec] SKIP symlink case (FS unsupported)")

  // ── 正控：正常相对路径（cwd 子目录）⇒ 行为不变 ─────────────
  test("正控: 正常相对路径（cwd 子目录）⇒ 照旧安装（行为不变）"):
    PluginRegistry.invalidateCache()
    val src = mkPlugin(fixtureRoot / "src" / "rel-plugin", "rel-guard-plugin")
    val rel = src.relativeTo(os.pwd).toString
    val res = PluginRegistry.installFrom(rel).unsafeRunSync()
    println(s"""[guard-spec] installFrom("$rel") [正常相对子目录] → ${res.fold(identity, identity)}""")
    assert(res.isRight, s"正常相对子目录必须照旧安装，got: $res")
    assert(res.toOption.exists(_.contains("presence = trust")),
      "落盘语义：在位即信任（无审批待审步骤）——文案随无审批批更新，落盘行为不变")
    assert(os.exists(installedDir("rel-guard-plugin") / "plugin.json"), "落盘到 plugins/<manifest name>")
    assertEquals(treeAsText(installedDir("rel-guard-plugin")), treeAsText(src), "整目录字节一致（拷贝语义不变）")

  // ── 正控：绝对路径（cwd 外 / cwd 内）⇒ 行为不变 ────────────
  test("正控: 绝对路径（cwd 外与 cwd 内子目录）⇒ 照旧安装（行为不变）"):
    PluginRegistry.invalidateCache()
    val outside = mkPlugin(os.Path(Files.createTempDirectory("nb-guard-src")), "abs-guard-plugin")
    val resOutside = PluginRegistry.installFrom(outside.toString).unsafeRunSync()
    println(s"""[guard-spec] installFrom("$outside") [绝对路径（cwd 外）] → ${resOutside.fold(identity, identity)}""")
    assert(resOutside.isRight, s"绝对路径必须照旧安装，got: $resOutside")
    assert(os.exists(installedDir("abs-guard-plugin") / "plugin.json"), "落盘")

    val inside = mkPlugin(fixtureRoot / "src" / "abs-inside", "abs-inside-plugin")
    val resInside = PluginRegistry.installFrom(inside.toString).unsafeRunSync()
    println(s"""[guard-spec] installFrom("$inside") [绝对路径（cwd 内子目录）] → ${resInside.fold(identity, identity)}""")
    assert(resInside.isRight, s"cwd 内子目录的绝对形式必须照旧安装（判据是等于/祖先，不是「必须在 cwd 外」），got: $resInside")
    assert(os.exists(installedDir("abs-inside-plugin") / "plugin.json"), "落盘")

    os.remove.all(outside)

end PluginInstallPathGuardSpec
