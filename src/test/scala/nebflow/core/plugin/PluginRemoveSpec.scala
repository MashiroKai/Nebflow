package nebflow.core.plugin

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import nebflow.core.seed.SeedService
import nebflow.shared.PathUtil

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * 插件**卸载面** spec（2026-10-29 卸载批：作者令「除了 toggle，我们应该还要多一个
 * 卸载」）。
 *
 * 判据六条：
 *  ① remove（默认腿 deleteDir=true）⇒ 目录删除 + `plugins.removed.<name>` 在位 +
 *     注册表面整体消失（plugins ∧ rejected 双清——出册 ≠ 拒载 ≠ 封禁）+ 审计行落
 *     `logs/plugin-remove.jsonl`
 *  ② remove（仅停用腿 deleteDir=false）⇒ 目录保留 + 同样出册
 *  ③ 卸载不存在的包 ⇒ Left（零静默：面板/CLI 能区分「确实卸载了」与「本来就不在」）
 *  ④ 卸载 ≠ 封禁：`plugins.revoked` 零触碰（语义三分的负控）
 *  ⑤ 台账联动：remove + `SeedService.markUserRemoved` ⇒ 下一次 boot 种子自愈**不装回**
 *     （墓碑独立成立——卸载不被种子静默复活）
 *  ⑥ 复职：`installFrom` 重装同名包 ⇒ `plugins.removed` 标记清除 + 注册表面恢复
 *     （显式重装 = 显式复职，出册过滤不得把刚装好的包藏住）
 */
class PluginRemoveSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-plugin-remove"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  private def pluginDir(name: String): os.Path = home / "plugins" / name

  /** 最小合法包（canonical $schema + skills 其一——§5.2/裁定 12）。 */
  private def writePlugin(name: String): os.Path =
    val d = pluginDir(name)
    os.makeDir.all(d / "skills" / "hello")
    os.write.over(
      d / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"$name","version":"1.0.0","description":"$name fixture"}"""
    )
    os.write.over(
      d / "skills" / "hello" / "SKILL.md",
      "---\nname: hello\ndescription: hello skill\n---\n# hello\n"
    )
    d

  private def removedTable: Map[String, Json] =
    val cfg = PathUtil.configJsonReadPath(home)
    if !os.exists(cfg) then Map.empty
    else
      io.circe.parser
        .parse(os.read(cfg))
        .toOption
        .flatMap(_.hcursor.downField("plugins").downField("removed").as[Map[String, Json]].toOption)
        .getOrElse(Map.empty)

  private def auditLines: List[String] =
    val p = home / "logs" / "plugin-remove.jsonl"
    if !os.exists(p) then Nil
    else os.read.lines(p).toList

  private def registryFace: (List[String], List[String]) =
    val (plugins, rejected) = PluginRegistry.listWithRejected().unsafeRunSync()
    (plugins.map(_.name).sorted, rejected.map(_._1).sorted)

  override def beforeEach(): Unit =
    os.remove.all(home / "plugins")
    os.remove.all(home / "logs")
    if os.exists(PathUtil.configJsonWritePath(home)) then os.remove(PathUtil.configJsonWritePath(home))
    PluginRegistry.invalidateCache()

  // ── ① 默认腿：删目录 + 出册 + 审计 ─────────────────────────
  test("① remove 默认腿：目录删除 + 出册（plugins∧rejected 双清）+ 审计行"):
    writePlugin("gx-a")
    PluginRegistry.invalidateCache()
    val (_, rejectedBefore) = registryFace
    assert(rejectedBefore.isEmpty, "前置：合法包不被拒载（rejected 面为空）")

    val res = PluginRemovePolicy.remove("gx-a", "spec", "test", deleteDir = true).unsafeRunSync()
    assert(res.isRight, s"① remove 成功：$res")
    assert(!os.exists(pluginDir("gx-a")), "① 目录已删除")
    assert(removedTable.contains("gx-a"), "① plugins.removed.<name> 在位")
    assert(removedTable("gx-a").hcursor.downField("deleteDir").as[Boolean].toOption.contains(true), "① 记录 deleteDir 腿")
    assert(auditLines.exists(_.contains("\"removed\"") && _.contains("gx-a")), "① 审计行落 plugin-remove.jsonl")

    val (plugins, rejected) = registryFace
    assert(!plugins.contains("gx-a"), "① 出册：不在 plugins")
    assert(!rejected.contains("gx-a"), "① 出册：不在 rejected（卸载不是拒载）")

  // ── ② 仅停用腿：目录保留 + 出册 ────────────────────────────
  test("② remove 仅停用腿：目录保留、出册不变"):
    writePlugin("gx-b")
    PluginRegistry.invalidateCache()

    val res = PluginRemovePolicy.remove("gx-b", "spec", "test", deleteDir = false).unsafeRunSync()
    assert(res.isRight, s"② remove 成功：$res")
    assert(os.exists(pluginDir("gx-b") / "plugin.json"), "② 目录保留（仅停用）")
    assert(removedTable.contains("gx-b"), "② 出册标记在位")

    val (plugins, rejected) = registryFace
    assert(!plugins.contains("gx-b"), "② 出册：不在 plugins（目录在 ≠ 注册表可见）")
    assert(!rejected.contains("gx-b"), "② 出册：不在 rejected")

  // ── ③ 卸载不存在的包 ⇒ Left ────────────────────────────────
  test("③ 卸载不存在的包 ⇒ Left（零静默）"):
    val res = PluginRemovePolicy.remove("no-such-plugin", "", "test", deleteDir = true).unsafeRunSync()
    assert(res.isLeft, "③ 不存在的包 ⇒ Left，不静默成功")

  // ── ④ 卸载 ≠ 封禁（revoked 零触碰负控）─────────────────────
  test("④ 卸载不写 plugins.revoked（语义三分负控）"):
    writePlugin("gx-c")
    PluginRegistry.invalidateCache()
    PluginRemovePolicy.remove("gx-c", "", "test", deleteDir = true).unsafeRunSync()
    val cfg = PathUtil.configJsonReadPath(home)
    val json = io.circe.parser.parse(os.read(cfg)).toOption.get
    assert(
      json.hcursor.downField("plugins").downField("revoked").as[Map[String, Json]].toOption.getOrElse(Map.empty).isEmpty,
      "④ revoked 表零触碰（封禁语义归 PluginBlockPolicy，卸载不越界）"
    )

  // ── ⑤ 台账联动：卸载后种子自愈不装回 ───────────────────────
  test("⑤ remove + markUserRemoved ⇒ 种子 reconcile 不装回（墓碑独立成立）"):
    // 默认集包（nebflow-plugin-creator）：拷贝到盘上模拟在位，卸载 + 落墓碑，
    // 下一次 ensureSeeded 必须不装回。
    val target = "nebflow-plugin-creator"
    val url = getClass.getClassLoader.getResource(s"seed/plugins/$target/plugin.json")
    val seedDir = os.Path(java.nio.file.Paths.get(url.toURI)) / os.up
    os.walk(seedDir).filter(os.isFile).foreach { f =>
      val t = pluginDir(target) / os.SubPath(f.relativeTo(seedDir).toString)
      os.makeDir.all(t / os.up)
      os.copy.over(f, t)
    }
    PluginRegistry.invalidateCache()

    val res = PluginRemovePolicy.remove(target, "uninstall flow", "test", deleteDir = true).unsafeRunSync()
    assert(res.isRight, s"⑤ 卸载成功：$res")
    SeedService.markUserRemoved(target).unsafeRunSync()

    val ledger = io.circe.parser
      .parse(os.read(SeedService.pluginLedgerPath(home)))
      .toOption.get
    assert(
      ledger.hcursor.downField("userRemoved").downField(target).isSucceeded,
      "⑤ 台账墓碑在位（markUserRemoved 落盘）"
    )

    SeedService.ensureSeeded().unsafeRunSync() // 下一次 boot：reconcile 读墓碑
    assert(!os.exists(pluginDir(target)), "⑤ 种子自愈不装回（卸载不被静默复活）")

  // ── ⑥ 复职：重装清出册标记 ─────────────────────────────────
  test("⑥ installFrom 重装同名包 ⇒ 出册标记清除 + 注册表面恢复"):
    // 先卸载（删目录腿），再以本地目录源重装同名包 → reinstate。
    writePlugin("gx-d")
    PluginRegistry.invalidateCache()
    PluginRemovePolicy.remove("gx-d", "", "test", deleteDir = true).unsafeRunSync()
    assert(removedTable.contains("gx-d"), "前置：卸载标记在位")

    val src = os.Path(Files.createTempDirectory("nb-remove-reinstall")) / "gx-d"
    os.makeDir.all(src / "skills" / "hello")
    os.write.over(
      src / "plugin.json",
      s"""{"$$schema":"${PluginRegistry.CanonicalSchema}","name":"gx-d","version":"1.1.0","description":"reinstalled"}"""
    )
    os.write.over(src / "skills" / "hello" / "SKILL.md", "---\nname: hello\ndescription: hello\n---\n# hi\n")

    val installed = PluginRegistry.installFrom(src.toString).unsafeRunSync()
    assert(installed.isRight, s"⑥ 重装成功：$installed")
    assert(!removedTable.contains("gx-d"), "⑥ 出册标记被重装清除（显式复职）")

    val (plugins, _) = registryFace
    assert(plugins.contains("gx-d"), "⑥ 注册表面恢复（重装不被出册过滤藏住）")
    try os.remove.all(src / os.up)
    catch case _: Exception => ()

end PluginRemoveSpec
