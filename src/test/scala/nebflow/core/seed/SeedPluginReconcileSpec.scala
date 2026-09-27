package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.plugin.PluginRegistry
import nebflow.shared.PathUtil

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
  private def seedDir: os.Path = seedDirOf("slideblocks")

  /** 任意种子插件的 classpath 目录（test classpath 上 `src/main/resources/seed/` 的实件）。 */
  private def seedDirOf(name: String): os.Path =
    val url = getClass.getClassLoader.getResource(s"seed/plugins/$name/plugin.json")
    os.Path(java.nio.file.Paths.get(url.toURI)) / os.up

  private def seedMap: SortedMap[String, Array[Byte]] =
    SortedMap.from(os.walk(seedDir).filter(os.isFile).map(p => p.relativeTo(seedDir).toString -> os.read.bytes(p)))

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

  /**
   * 信任记录落库 sha256（approve 时刻基准；目录漂移不影响记录本身）。
   * 注意不用 TrustStatus/resolve——漂移即 untrusted，取不到基准。
   */
  private def recordSha: Option[String] =
    val cfg = PathUtil.configJsonReadPath(home)
    if !os.exists(cfg) then None
    else
      io.circe.parser
        .parse(os.read(cfg))
        .toOption
        .flatMap(
          _.hcursor
            .downField("plugins")
            .downField("trust")
            .downField("slideblocks")
            .downField("sha256")
            .as[String]
            .toOption
        )
  end recordSha

  private def dirDigest(d: os.Path): String = PluginRegistry.computeDigest(d).toOption.get._1

  /** manifest 当前版本（marker 预写成同版本 ⇒ marker 分支 no-op，隔离出 reconcile 行为）。 */
  private def manifestVersion: String =
    val in = getClass.getClassLoader.getResourceAsStream("seed/manifest.json")
    try
      io.circe.parser
        .parse(new String(in.readAllBytes(), UTF_8))
        .toOption
        .get
        .hcursor
        .downField("seedVersion")
        .as[String]
        .toOption
        .get
    finally in.close()

  /**
   * 隔离基线：无 agents/projects、marker=当前版本（marker 分支 no-op）、插件目录清空、
   * 信任表清空（config json 删除——否则前序用例的 approve 记录会污染仲裁分支）、
   * 覆盖前备份树清空（`plugins-backups/`——备份件同样是 home 状态，留着会让「本次覆盖
   * 恰产生一件备份」的断言看到前序用例的残留）。
   *
   * 2026-09-13（#105 P-1 批）：**一并清插件存在台账**——本 fixture 把 home 重置成
   * 「没有这些插件」的形态，而台账（`.plugin-presence.json`）也是 home 状态的一部分；
   * 只清插件目录而留台账，语义上等价于「用户定向删除」（见 SeedPluginDeletionMarkerSpec），
   * 与本 fixture 的意图（无历史的首装/自愈态）不同。断言零改动。
   */
  private def makeIsolatedHome(): Unit =
    rm(home / "plugins"); rm(home / "agents"); rm(home / "projects"); rm(home / ".seed-state.json")
    rm(PathUtil.configJsonReadPath(home)); rm(SeedService.pluginLedgerPath(home))
    // 覆盖前备份树也是 home 状态的一部分（本批新增）：清掉，否则前序用例的备份件会
    // 污染「本次覆盖恰产生一件备份」的断言。
    rm(home / "plugins-backups")
    os.write.over(home / ".seed-state.json", s"""{"version":"$manifestVersion","seededAt":1,"items":[]}""")

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

    // ── 覆盖前备份（本批：插件覆盖路径与 agents 面对称，规范 = SeedService:502-518）──
    // 本用例即「trust == runtime ⇒ 覆盖」场景 ⇒ **覆盖前必须出现备份件**（负控：无备份即判红）。
    val backupRoot = home / "plugins-backups"
    assert(os.exists(backupRoot), "overwrite path left a pre-sync backup root (plugins-backups)")
    val backups = os.list(backupRoot).filter(os.isDir).toList
    assert(backups.size == 1, s"exactly one pre-sync backup dir, got: ${backups.map(_.last)}")
    val backup = backups.head
    assert(
      backup.last.endsWith("_pre-sync-slideblocks") && backup.last.length > "_pre-sync-slideblocks".length,
      s"backup name carries stamp + source face + package: ${backup.last}"
    )
    // 备份件 == 被覆盖前内容（逐字节）⇒ 覆盖结果与旧行为内容面等价（正控：逐件 blob 对照）。
    val backupFiles = os
      .walk(backup)
      .filter(os.isFile)
      .map(p => p.relativeTo(backup).toString -> os.read.bytes(p))
      .toMap
    assert(
      backupFiles.keySet - "PRE-SHA256.txt" == mutatedOldRuntime.keySet,
      s"backup holds exactly the pre-overwrite file set: ${backupFiles.keySet}"
    )
    mutatedOldRuntime.foreach { (rel, bytes) =>
      assert(
        java.util.Arrays.equals(backupFiles(rel), bytes),
        s"backup '$rel' is byte-identical to the pre-overwrite content"
      )
    }
    // 留痕件形态 = agents 面既有形态（仅来源面名词不同）。
    val manifest = os.read(backup / "PRE-SHA256.txt")
    assert(
      manifest.startsWith("# plugin=slideblocks  sampled="),
      "manifest header carries source face + package + stamp"
    )
    mutatedOldRuntime.foreach { (rel, bytes) =>
      assert(manifest.contains(s"${sha256(bytes)}  $rel"), s"manifest records the pre-overwrite sha256 of '$rel'")
    }

  // ── ①b 干净运行时载 runtime 独有件 → 镜像覆盖连带删除 ├─────
  /**
   * 覆盖**镜像删除分支**（`SeedService.mirrorSeed:429-431` 的「删 runtime 独有文件」步 +
   * `:433-435` 的「清删空目录」步，本批 2026-09-17 补）。
   *
   * 分支身份：镜像覆盖（[[mirrorSeed]]）在「写种子文件」之外还有**删除面**——runtime 独有件
   * 必须被移除，否则「镜像 = 逐字节等于种子」不成立。
   *
   * 可达性：插件面用**信任记录 digest 仲裁**（approve 时刻目录指纹），runtime 独有件
   * 已计入该指纹 ⇒ `digest == trusted` 成立 ⇒ 进镜像覆盖分支（不落「用户改过」跳过分支）。
   * （agents 面同一步不可达——`reconcileAgent` 的差集检查把 runtime 独有文件判为
   * REFUSING，`runtimeUniqueLines` 对无同 rel 的运行时文件取空种子行集 ⇒ 全行独有；
   * 该负控属既有行为，本批只登记、不覆盖。）
   *
   * 变异判据：注掉 `mirrorSeed` 中「删除运行面独有件」那一步 ⇒ 本例必红
   * （独有件残留 + `treeAsText != seedText`）。
   */
  test("clean runtime carrying a runtime-only file: the seed mirror deletes it and cleans the emptied dir"):
    makeIsolatedHome()
    val legacyRel = "skills/slideblocks/legacy/LEGACY-NOTE.md"
    val withExtra = mutatedOldRuntime.updated(legacyRel, "kept outside the seed tree\n".getBytes(UTF_8))
    writePlugin(withExtra)
    // 仲裁基准含 runtime 独有件 ⇒ 判为「干净运行时」（自 approve 后零漂移）
    val trusted = approveRuntime()
    assert(trusted == dirDigest(pluginDir), "precondition: approve recorded the digest incl. the runtime-only file")
    assert(
      os.exists(pluginDir / "skills" / "slideblocks" / "legacy" / "LEGACY-NOTE.md"),
      "precondition: the runtime-only file is on disk before reconcile"
    )

    ensure()

    // 镜像删除分支（负控：删除步被注掉即红）
    assert(
      !os.exists(pluginDir / "skills" / "slideblocks" / "legacy" / "LEGACY-NOTE.md"),
      "the runtime-only file is removed by the seed mirror (mirror-delete branch)"
    )
    // 清删空目录步：只装 runtime 独有件的目录一并消失
    assert(
      !os.exists(pluginDir / "skills" / "slideblocks" / "legacy"),
      "the directory emptied by that deletion is cleaned up"
    )
    // 删除面与写面同判据：镜像结果逐字节等于种子
    assert(treeAsText(pluginDir) == seedText, "mirrored runtime == seed exactly (byte-level)")
    assert(recordSha.get == dirDigest(pluginDir), "re-approved on the mirrored digest")
    // 删除不是静默丢弃：pre-sync 备份含被删的那一件（回滚材料完整）
    val backupDirs = os.list(home / "plugins-backups").filter(os.isDir)
    assert(backupDirs.size == 1, s"exactly one pre-sync backup dir, got: ${backupDirs.size}")
    assert(
      os.exists(backupDirs.head / "skills" / "slideblocks" / "legacy" / "LEGACY-NOTE.md"),
      "the pre-sync backup holds the deleted runtime-only file (rollback material)"
    )

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

    // 守卫语义保持：不完整播种（既有 home 走 marker-only 分支、不重播默认集）；
    // **默认集 agent 自愈补装**（2026-09-13 语义变更：作者令「改成缺失自愈」取代 D-8
    // 「缺失不新装」——原断言「no project-dispatcher agent under guard」已按新口径改写，
    // 预期判红样例）。项目面自作者 2026-09-17 裁定②（既有 home 亦 add-only 补种）起由
    // `reconcileProjects` 补**缺失**的内置项目 ⇒ 本条原负向断言「no general project
    // planted under guard」已随前令作废，翻转为正向。
    assert(
      os.exists(home / "projects" / "general" / "project.json"),
      "missing default project backfilled under the guard (add-only reconcile, author ruling ②)"
    )
    assert(
      os.exists(home / "agents" / "project-dispatcher" / "agent.json"),
      "default-set agent self-healed even under the guard (2026-09-13)"
    )
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

  // ── ⑥ 缺失默认集插件 → 既有 home 自愈安装 + approve（2026-09-12 批）──
  test("missing default-set plugin in existing home is self-healed from seed and approved"):
    makeIsolatedHome()
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")
    // 既有形态：装了一部分默认集（visual-report），另两包（含 nebflow-plugin-creator）缺失
    copySeedPlugin("visual-report")
    assert(!os.exists(home / "plugins" / "nebflow-plugin-creator"), "precondition: creator absent")
    assert(!os.exists(home / "plugins" / "slideblocks"), "precondition: slideblocks absent")

    ensure()

    // ① 缺失的默认集插件被自愈安装（整目录实件 + trusted）——author home 的缺口机制解
    for name <- List("nebflow-plugin-creator", "slideblocks")
    do
      assert(os.exists(home / "plugins" / name / "plugin.json"), s"default-set plugin '$name' self-healed")
      assert(PluginRegistry.resolve(name).unsafeRunSync().isRight, s"self-healed '$name' is trusted (approved)")
    assert(
      treeAsText(home / "plugins" / "nebflow-plugin-creator") == treeAsText(seedDirOf("nebflow-plugin-creator")),
      "self-healed content == seed tree (byte-level)"
    )
    // ② 幂等：第二次 ensure 对该目录零动作（digest 已与种子一致）
    val healed = dirDigest(home / "plugins" / "nebflow-plugin-creator")
    ensure()
    assert(dirDigest(home / "plugins" / "nebflow-plugin-creator") == healed, "second ensure is a no-op")

  // ── ⑦ 自愈面 = 默认集（不向种子树全集扩张）───────────────
  test("self-heal installs only the default set — never the whole seed tree"):
    makeIsolatedHome()
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")

    ensure()

    // 种子树里但不在默认集的 5 包：零安装（种子文件保留可手动装；
    // 默认集本批 3 → 4 = +web-search-toolkit）
    for name <- List("nebflow-qa", "nebflow-frontend-dev", "engineering-methods", "explorer-toolkit", "design-spec")
    do
      assert(
        !os.exists(home / "plugins" / name),
        s"non-default seed plugin '$name' NOT installed by self-heal (no area expansion)"
      )
    // 落盘面积恰为默认集四条（枚举目录，防「遍历种子树全集」式实现）
    val installed = os.list(home / "plugins").filter(os.isDir).map(_.last).toList.sorted
    assert(
      installed == List("nebflow-plugin-creator", "slideblocks", "visual-report", "web-search-toolkit"),
      s"existing-home plugin area == default preinstall set (4), got: ${installed.mkString(", ")}"
    )

  // ── ⑧ 已存在目录零覆盖（自愈不改既有目录）─────────────────
  test("self-heal never writes into an already-present plugin dir (user version kept)"):
    makeIsolatedHome()
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")
    copySeedPlugin("visual-report")
    val userFile = home / "plugins" / "visual-report" / "USER-NOTES.md"
    os.write.over(userFile, "user modification\n") // 目录漂移（≠ 种子 digest，且无信任记录）
    val before = dirDigest(home / "plugins" / "visual-report")

    ensure()

    assert(os.exists(userFile), "existing dir untouched (user file kept)")
    assert(dirDigest(home / "plugins" / "visual-report") == before, "existing dir bytes unchanged by self-heal")

  private def treeAsText(d: os.Path): Map[String, String] =
    os.walk(d).filter(os.isFile).map(p => p.relativeTo(d).toString -> new String(os.read.bytes(p), UTF_8)).toMap

  /** 与 SeedService.sha256File 同一算法（备份留痕件的逐文件 sha256 复算）。 */
  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  /** 既有 home 形态夹具：把种子树里的插件整目录复制到 `home/plugins/<name>`。 */
  private def copySeedPlugin(name: String): Unit =
    val src = seedDirOf(name)
    val dst = home / "plugins" / name
    os.walk(src).filter(os.isFile).foreach { f =>
      val t = dst / os.SubPath(f.relativeTo(src).toString)
      os.makeDir.all(t / os.up)
      os.copy.over(f, t)
    }

end SeedPluginReconcileSpec
