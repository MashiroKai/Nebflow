package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.plugin.PluginRegistry

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.collection.immutable.SortedMap

/**
 * SeedService 插件一致性 reconcile（「始终保持一致」机制，2026-09-09 批）定向验证。
 *
 * 覆盖 digest 仲裁行为三分支 + 守卫集成 + 保守分支：
 *  ① 干净运行时（runtime digest == trusted digest）+ seed 演进 → 种子镜像覆盖 + 自动重审
 *  ② 用户改过（runtime digest ≠ trusted digest）→ 跳过，用户编辑保留、信任记录不动
 *  ③ seed == runtime（一致）→ 无动作（内容与信任记录零变化）
 *  ④ hasExistingProjects 守卫下 reconcile 仍生效（断点核心锁死点回归——既有 home
 *     不完整播种，但干净旧插件仍被刷新；2026-09-09 author home 冻结问题的机制解）
 *  ⑤ 无信任记录 + seed 差异 → 保守跳过（无仲裁基准不覆盖）
 *
 * classpath 资源（src/main/resources/seed/）在 sbt test classpath 上，种子读取走真实链路。
 */
class SeedPluginReconcileSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-seed-reconcile-spec"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def ensure(): Unit = SeedService.ensureSeeded().unsafeRunSync()
  private def pluginDir: os.Path = home / "plugins" / "slideblocks"
  private def rm(p: os.Path): Unit = if os.exists(p) then os.remove.all(p)

  // ── fixtures ───────────────────────────────────────────────
  /** seed 资源字节面（rel → bytes），直接从 test classpath 读（与 SeedService 同源）。 */
  private def seedDir: os.Path =
    val url = getClass.getClassLoader.getResource("seed/plugins/slideblocks/plugin.json")
    os.Path(java.nio.file.Paths.get(url.toURI)) / os.up

  private def seedMap: SortedMap[String, Array[Byte]] =
    SortedMap.from(os.walk(seedDir).filter(os.isFile).map(p =>
      p.relativeTo(seedDir).toString -> os.read.bytes(p)))

  private def seedText: Map[String, String] = asText(seedMap.toMap)

  private def asText(m: Map[String, Array[Byte]]): Map[String, String] =
    m.view.map { (k, v) => k -> new String(v, UTF_8) }.toMap

  /** 旧形态 runtime：seed 内容 + version 字段改写（保证与 seed 有差异且仍是合法 manifest）。 */
  private def mutatedOldRuntime: SortedMap[String, Array[Byte]] =
    val json = io.circe.parser.parse(new String(seedMap("plugin.json"), UTF_8)).toOption.get
    val old = json.hcursor.downField("version").withFocus(_.mapString(_ => "0.0.9-legacy")).top.get
    seedMap.updated("plugin.json", old.noSpaces.getBytes(UTF_8))

  private def writePlugin(files: SortedMap[String, Array[Byte]]): Unit =
    rm(pluginDir)
    files.foreach { (rel, bytes) =>
      val t = pluginDir / os.SubPath(rel)
      os.makeDir.all(t / os.up)
      os.write.over(t, bytes)
    }

  private def approveRuntime(): String =
    PluginRegistry.approve("slideblocks").unsafeRunSync()
    recordSha.get

  /** 信任记录落库 sha256（approve 时刻基准；目录漂移不影响记录本身）。
    * 注意不用 TrustStatus/resolve——漂移即 untrusted，取不到基准。 */
  private def recordSha: Option[String] =
    val cfg = PathUtil.configJsonReadPath(home)
    if !os.exists(cfg) then None
    else io.circe.parser.parse(os.read(cfg)).toOption.flatMap(
      _.hcursor.downField("plugins").downField("trust").downField("slideblocks")
        .downField("sha256").as[String].toOption)

  private def dirDigest(d: os.Path): String = PluginRegistry.computeDigest(d).toOption.get._1

  /** manifest 当前版本（marker 预写成同版本 ⇒ marker 分支 no-op，隔离出 reconcile 行为）。 */
  private def manifestVersion: String =
    val in = getClass.getClassLoader.getResourceAsStream("seed/manifest.json")
    try
      io.circe.parser.parse(new String(in.readAllBytes(), UTF_8)).toOption.get
        .hcursor.downField("seedVersion").as[String].toOption.get
    finally in.close()

  /** 隔离基线：无 agents/projects、marker=当前版本（marker 分支 no-op）、插件目录清空、
    * 信任表清空（config json 删除——否则前序用例的 approve 记录会污染仲裁分支）。 */
  private def makeIsolatedHome(): Unit =
    rm(home / "plugins"); rm(home / "agents"); rm(home / "projects"); rm(home / ".seed-state.json")
    rm(PathUtil.configJsonReadPath(home))
    os.write.over(home / ".seed-state.json",
      s"""{"version":"$manifestVersion","seededAt":1,"items":[]}""")

  // ── ① 干净运行时 → 种子刷新 + 自动重审 ├────────────────────
  test("clean runtime (digest==trusted) is refreshed from seed and re-approved"):
    makeIsolatedHome()
    writePlugin(mutatedOldRuntime)
    val trustedOld = approveRuntime()
    assert(trustedOld == dirDigest(pluginDir), "precondition: approve recorded runtime digest")

    ensure()

    assert(treeAsText(pluginDir) == seedText, "runtime mirrored to seed exactly (byte-level)")
    assert(recordSha.get != trustedOld, "trust record moved to new digest (auto re-approve)")
    assert(recordSha.get == dirDigest(pluginDir), "re-approved digest == mirrored runtime digest")

  // ── ② 用户改过 → 跳过 + 用户编辑保留 ├─────────────────────
  test("user-modified runtime (digest!=trusted) is skipped, user edits preserved"):
    makeIsolatedHome()
    writePlugin(seedMap)
    val pristine = approveRuntime()
    val skillPath = pluginDir / "skills" / "slideblocks" / "SKILL.md"
    os.write.append(skillPath, "\n<!-- user edit marker -->")
    val userDigest = dirDigest(pluginDir)
    assert(userDigest != pristine, "precondition: user edit diverges from trusted digest")

    ensure()

    assert(os.read(skillPath).contains("user edit marker"), "user edit preserved (用户编辑 > 种子)")
    assert(recordSha.contains(pristine), "trust record untouched (no re-approve)")
    assert(dirDigest(pluginDir) == userDigest, "runtime content untouched by seed")

  // ── ③ seed == runtime 一致 → 无动作 ├──────────────────────
  test("consistent runtime (seed==runtime) results in no action"):
    makeIsolatedHome()
    writePlugin(seedMap)
    val pristine = approveRuntime()

    ensure()

    assert(recordSha.contains(pristine), "no re-approve on consistent")
    assert(treeAsText(pluginDir) == seedText, "content untouched on consistent")

  // ── ④ hasExistingProjects 守卫 + reconcile 集成（断点回归）─
  test("existing-user-data home: full seeding still skipped, clean stale plugin still refreshed"):
    makeIsolatedHome()
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")
    writePlugin(mutatedOldRuntime)
    val trustedOld = approveRuntime()

    ensure()

    // 守卫语义保持：不完整播种（防误建 general / 不补 agent）
    assert(!os.exists(home / "agents" / "project-dispatcher"), "full seeding still skipped under guard")
    assert(!os.exists(home / "projects" / "general"), "no general project planted under guard")
    // reconcile 穿透守卫：干净旧插件刷新为 seed 形态（2026-09-09 断点的机制解）
    assert(treeAsText(pluginDir) == seedText, "clean stale plugin refreshed even under guard")
    assert(recordSha.get != trustedOld, "re-approved under guard")

  // ── ⑤ 无信任记录 + 差异 → 保守跳过 ├───────────────────────
  test("runtime without trust record is conservatively skipped on seed divergence"):
    makeIsolatedHome()
    writePlugin(mutatedOldRuntime) // 不 approve → 无仲裁基准

    ensure()

    assert(treeAsText(pluginDir) == asText(mutatedOldRuntime.toMap), "runtime preserved (no overwrite)")
    assert(recordSha.isEmpty, "still untrusted (seed never force-approves)")

  private def treeAsText(d: os.Path): Map[String, String] =
    os.walk(d).filter(os.isFile).map(p =>
      p.relativeTo(d).toString -> new String(os.read.bytes(p), UTF_8)).toMap

end SeedPluginReconcileSpec
