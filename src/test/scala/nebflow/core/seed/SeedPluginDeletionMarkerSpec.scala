package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import io.circe.Json
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.plugin.PluginRegistry

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/**
 * 插件「用户主动删除」持久标记（#105 P-1，2026-09-13 批）定向验证。
 *
 * 说明（marker 位置/格式/生命周期/判据映射/为何不改既有语义）见
 * `.nebflow/evidence/20260913_101638_pluginguard-impl/01-rangeA-design-用户主动删除标记.md`。
 *
 * 判据四条（各一条独立用例，正负控分开）：
 *  ① 用户删过（标记在位 + 目录缺失）⇒ **不装回、不 approve**（负控）
 *  ② 因别的原因缺失（无标记、无历史）⇒ **仍装回**（正控；现行自愈语义逐字不变）
 *  ③ 标记在位但插件被手动装回 ⇒ 标记清理（谁清/何时清/依据信号）+ 再删重护
 *  ④ 从没装过的插件（fresh home）⇒ 按现行首装口径（正控）
 * 另：⑤ 幂等（第二次 boot 零动作）⑥ 台账损坏 fail-soft ⑦ 台账面积 = manifest 默认集
 *     ⑧ 容器规则（存储整体缺失 ⇒ 推断历史作废、显式删除意图保留）
 *
 * classpath 资源（src/main/resources/seed/）在 sbt test classpath 上，种子读取走真实链路。
 */
class SeedPluginDeletionMarkerSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  /** 默认集里的一个具体插件（#105 的生产实例就是它）。 */
  private val Target = "nebflow-plugin-creator"

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-seed-plugin-deletion"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    try os.remove.all(home)
    catch case _: Exception => ()

  // ── fixtures ───────────────────────────────────────────────
  private def ensure(): Unit = SeedService.ensureSeeded().unsafeRunSync()
  private def pluginDir(name: String): os.Path = home / "plugins" / name
  private def rm(p: os.Path): Unit = if os.exists(p) then os.remove.all(p)
  private def ledgerPath: os.Path = SeedService.pluginLedgerPath(home)

  private def ledgerJson: Json =
    if !os.exists(ledgerPath) then Json.obj()
    else io.circe.parser.parse(os.read(ledgerPath)).toOption.getOrElse(Json.obj())

  private def seen: Set[String] =
    ledgerJson.hcursor.downField("seen").as[List[String]].toOption.getOrElse(Nil).toSet

  private def userRemoved: Set[String] =
    ledgerJson.hcursor.downField("userRemoved").as[Map[String, Json]].toOption.getOrElse(Map.empty).keySet

  private def manifestField[A](f: io.circe.ACursor => io.circe.Decoder.Result[A]): A =
    val in = getClass.getClassLoader.getResourceAsStream("seed/manifest.json")
    try f(io.circe.parser.parse(new String(in.readAllBytes(), UTF_8)).toOption.get.hcursor).toOption.get
    finally in.close()

  private def manifestPluginNames: List[String] =
    manifestField(_.downField("items").as[List[String]]).collect {
      case id if id.startsWith("plugins:") => id.stripPrefix("plugins:")
    }

  private def manifestVersion: String = manifestField(_.downField("seedVersion").as[String])

  /** 信任表落库 sha256（approve 记录；不写记录 = 未 approve）。 */
  private def trustSha(name: String): Option[String] =
    val cfg = PathUtil.configJsonReadPath(home)
    if !os.exists(cfg) then None
    else
      io.circe.parser.parse(os.read(cfg)).toOption.flatMap(
        _.hcursor.downField("plugins").downField("trust").downField(name).downField("sha256").as[String].toOption)

  private def seedDirOf(name: String): os.Path =
    val url = getClass.getClassLoader.getResource(s"seed/plugins/$name/plugin.json")
    os.Path(java.nio.file.Paths.get(url.toURI)) / os.up

  private def treeAsText(d: os.Path): Map[String, String] =
    os.walk(d).filter(os.isFile).map(p =>
      p.relativeTo(d).toString -> new String(os.read.bytes(p), UTF_8)).toMap

  private def copySeedPlugin(name: String): Unit =
    val src = seedDirOf(name)
    val dst = pluginDir(name)
    os.walk(src).filter(os.isFile).foreach { f =>
      val t = dst / os.SubPath(f.relativeTo(src).toString)
      os.makeDir.all(t / os.up)
      os.copy.over(f, t)
    }

  /** 既有 home 形态：有项目（守卫命中 ⇒ 不完整播种）+ marker 同版本 + 插件/台账/信任表清空。 */
  private def existingHome(): Unit =
    rm(home / "plugins"); rm(home / "agents"); rm(home / "projects")
    rm(home / ".seed-state.json"); rm(ledgerPath); rm(PathUtil.configJsonReadPath(home))
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")
    os.write.over(home / ".seed-state.json", s"""{"version":"$manifestVersion","seededAt":1,"items":[]}""")
    PluginRegistry.invalidateCache()

  // ── ① 用户删过 ⇒ 不装回（负控）─────────────────────────────
  test("① 用户删过（标记在位 + 目录缺失）⇒ 不装回、不 approve（负控）"):
    existingHome()
    ensure() // boot #1：无历史 ⇒ 现行自愈口径装回（前置）
    assert(os.exists(pluginDir(Target) / "plugin.json"), "前置：boot #1 按现行口径装回")
    val shaBefore = trustSha(Target)
    assert(shaBefore.isDefined, "前置：boot #1 已 approve（信任记录在位）")
    assert(seen.contains(Target), "前置：台账记录「曾就位」")

    os.remove.all(pluginDir(Target)) // 用户主动删除（等价 rm -rf ~/.nebflow/plugins/<name>）
    ensure()                         // boot #2：检出「曾就位 → 已消失」⇒ 落标记 + 跳过自愈
    assert(!os.exists(pluginDir(Target)), "① 不装回（目录仍缺失）")
    assert(userRemoved.contains(Target), "① 用户删除标记已落盘（userRemoved 记名）")
    assertEquals(trustSha(Target), shaBefore, "① 不得 approve（信任记录零变化）")

    ensure() // boot #3：标记在位 ⇒ 跨重启仍不装回
    assert(!os.exists(pluginDir(Target)), "① 标记在位 ⇒ 重启后仍不装回")
    assertEquals(trustSha(Target), shaBefore, "① 不得 approve（第二轮同样零变化）")

    // 加强（防「靠 seen 巧合绿」）：把台账改成**只有标记、没有 seen** 的形态 ⇒ 此时若标记
    // 读不回来，缺失就会被现行口径装回 ⇒ 本用例必须判红。标记必须**独立成立**。
    os.write.over(ledgerPath,
      s"""{"version":1,"seen":[],"userRemoved":{"$Target":{"at":${System.currentTimeMillis() / 1000L}}}}""")
    ensure() // boot #4：唯一依据 = userRemoved 标记
    assert(!os.exists(pluginDir(Target)), "① 只有标记（无 seen）⇒ 仍不装回（标记独立成立）")
    assertEquals(trustSha(Target), shaBefore, "① 不得 approve（第三轮同样零变化）")
    assert(userRemoved.contains(Target), "① 标记读回（写/读两侧同形）")

    // 逐包判据：其余默认集插件不受影响
    for name <- manifestPluginNames.filterNot(_ == Target) do
      assert(os.exists(pluginDir(name) / "plugin.json"), s"逐包判定：'$name' 不受影响")

  // ── ② 因别的原因缺失（无标记）⇒ 仍装回（正控）──────────────
  test("② 因别的原因缺失（无标记、无历史）⇒ 仍装回 + approve（正控）"):
    existingHome()
    assert(!os.exists(pluginDir(Target)), "前置：缺失")
    assert(!os.exists(ledgerPath), "前置：无台账（= 无标记、无历史）")

    ensure()

    assert(os.exists(pluginDir(Target) / "plugin.json"), "② 仍自愈装回（现行语义逐字不变）")
    assertEquals(treeAsText(pluginDir(Target)), treeAsText(seedDirOf(Target)), "② 整目录 == 种子树（逐字节）")
    assert(trustSha(Target).isDefined, "② 仍自动 approve（现行语义逐字不变）")
    assert(PluginRegistry.resolve(Target).unsafeRunSync().isRight, "② 装回即 trusted")
    assert(!userRemoved.contains(Target), "② 不得写删除标记")

  // ── ③ 标记清理语义（手动装回）⇒ 清标记 ────────────────────
  test("③ 标记在位但插件被手动装回 ⇒ 标记清理（谁清/何时清/依据信号）+ 再删重护"):
    existingHome()
    ensure()                                    // boot #1：装回
    os.remove.all(pluginDir(Target))            // 用户删除
    ensure()                                    // boot #2：落标记
    assert(userRemoved.contains(Target), "前置：标记在位")

    copySeedPlugin(Target)                      // 用户手动装回（整目录拷回）
    os.write.over(pluginDir(Target) / "USER-NOTES.md", "user note\n") // 用户自己的文件，须保留
    val before = treeAsText(pluginDir(Target))

    ensure()                                    // boot #3：目录再次存在 ⇒ 清标记

    assert(!userRemoved.contains(Target), "③ 标记被清（依据信号 = <root>/plugins/<name> 再次存在）")
    assert(seen.contains(Target), "③ 台账回到「在位」态")
    assertEquals(treeAsText(pluginDir(Target)), before, "③ 清理不改写内容（用户版本保留）")

    os.remove.all(pluginDir(Target))            // 再删一次（生命周期闭合）
    ensure()
    assert(!os.exists(pluginDir(Target)), "③ 再删 ⇒ 仍不装回（重护）")
    assert(userRemoved.contains(Target), "③ 标记重生（seen 仍在 ⇒ 判据再次命中）")

  // ── ④ 从没装过的插件 ⇒ 现行首装口径（正控）─────────────────
  test("④ 从没装过的插件（fresh home）⇒ 按现行首装口径播种 + approve（正控）"):
    rm(home / "plugins"); rm(home / "agents"); rm(home / "projects")
    rm(home / ".seed-state.json"); rm(ledgerPath); rm(PathUtil.configJsonReadPath(home))
    PluginRegistry.invalidateCache()

    ensure()

    assert(os.exists(pluginDir(Target) / "plugin.json"), "④ fresh home 首装（现行 seedPlugin 口径）")
    assert(trustSha(Target).isDefined, "④ 首装即 approve（现行口径）")
    assert(!userRemoved.contains(Target), "④ 首装不写删除标记")
    assert(seen.contains(Target), "④ 台账记录「在位」（此后删除才能被判出）")

  // ── ⑤ 幂等 ────────────────────────────────────────────────
  test("⑤ 幂等：第二次 boot 零动作（台账字节不变、插件字节不变、不重复 approve）"):
    existingHome()
    ensure()
    val ledgerBefore = os.read(ledgerPath)
    val treeBefore = treeAsText(pluginDir(Target))
    val shasBefore = manifestPluginNames.map(n => n -> trustSha(n)).toMap

    ensure()

    assertEquals(os.read(ledgerPath), ledgerBefore, "⑤ 台账逐字节不变（状态未变不写盘）")
    assertEquals(treeAsText(pluginDir(Target)), treeBefore, "⑤ 插件目录逐字节不变")
    assertEquals(manifestPluginNames.map(n => n -> trustSha(n)).toMap, shasBefore, "⑤ 信任记录零变化")

  // ── ⑥ 台账损坏 ⇒ fail-soft ────────────────────────────────
  test("⑥ 台账损坏 ⇒ fail-soft（当空台账、不阻断、缺失仍按现行口径装回）"):
    existingHome()
    os.write.over(ledgerPath, "{ this is not json")

    ensure()

    assert(os.exists(pluginDir(Target) / "plugin.json"), "⑥ 损坏 ⇒ 回到「无标记」口径（装回，不崩）")
    assert(!userRemoved.contains(Target), "⑥ 损坏台账不产生删除标记")

  // ── ⑦ 台账面积 = manifest 默认集 ─────────────────────────
  test("⑦ 台账只记 manifest 默认集（面积不扩张到种子树全集）"):
    existingHome()

    ensure()

    assertEquals(seen, manifestPluginNames.toSet, "⑦ seen == manifest plugins:* 全集（逐包判定面）")
    for name <- List("nebflow-qa", "nebflow-frontend-dev", "engineering-methods", "explorer-toolkit", "design-spec")
    do assert(!seen.contains(name), s"⑦ 非默认集种子包 '$name' 不入台账（面积不扩张）")

  // ── ⑧ 容器规则（存储级重置）──────────────────────────────
  test("⑧ 插件存储整体缺失 ⇒ 推断历史作废、显式删除意图保留（粗粒度手势保持旧语义）"):
    existingHome()
    ensure()
    os.remove.all(pluginDir(Target)) // 定向删除 ⇒ 标记
    ensure()
    assert(userRemoved.contains(Target), "前置：定向删除标记在位")

    os.remove.all(home / "plugins") // 用户把整个存储删掉（rm -rf plugins/）
    ensure()

    assert(!os.exists(pluginDir(Target)), "⑧ 显式删除标记不随存储重置作废（仍不装回）")
    assert(userRemoved.contains(Target), "⑧ 标记保留")
    for name <- manifestPluginNames.filterNot(_ == Target) do
      assert(os.exists(pluginDir(name) / "plugin.json"),
        s"⑧ 存储级重置 ⇒ 无标记的 '$name' 按现行口径补回（粗粒度手势语义不变）")

end SeedPluginDeletionMarkerSpec
