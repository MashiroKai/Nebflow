package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.shared.PathUtil // W1 shim: main had nebflow.core.PathUtil; PR moved it to shared
import nebflow.core.plugin.PluginRegistry

import java.nio.file.Files

/**
 * SeedService cold-start 播种引擎验证（cold-start seed 批 2026-09-07）。
 *
 * 覆盖定稿四项：① fresh home 完整播种（7 默认预装插件）、
 * ② 幂等 / 不覆盖用户编辑、
 * ③ fresh-home 守卫（已有用户数据 → 只写 marker 不播种）、
 * ④ 升级 add-only（低版本 marker + 已有文件 → 只补缺失，不重写）；
 * ⑤ manifest-face contract：`project:` 条目为零（kernelgen 批 2026-09-26 起 general
 * 项目退出冷启动），**`agents:` 条目亦为零且 seed/agents 资源树不在 classpath**
 * （builtin-def 批 2026-10-03 作者令①「四个 agent 全部代码硬编码，不扫盘，唯一标准
 * 源就是代码，插件面板只能做只读查看」——Nebula / project-dispatcher / general /
 * kernel 的 def 面整体上收进 `nebflow.core.entity.BuiltinAgents`，agent 播种腿、
 * reconcileAgents 自愈腿、`SeedService.readAgentSeedPrompt` 与 seed/agents 资源树
 * 同批退役；把任何 `agents:*` 条目写回 manifest ⇒ 本 spec 即红）。
 *
 * 史实（归档，均被 builtin-def 批取代）：cold-start 批的 3-agent 播种、kernelgen-ext
 * 批（2026-09-26）的 prompt-only `agents:kernel`（marker 7 → 8）、kernelgen-manifestfix
 * 批的 11 条并集 manifest（4 agents + 7 plugins）。
 *
 * classpath 资源（src/main/resources/seed/）在 sbt test classpath 上，`getResourceAsStream`
 * 直接命中——即「载体 A = 资源打包」在测试环境下被真实走通。
 */
class SeedServiceSpec extends FunSuite:

  private var prevRoot: os.Path = os.Path("/tmp")
  private var home: os.Path = os.Path("/tmp")

  override def beforeAll(): Unit =
    prevRoot = PathUtil.dataRoot
    home = os.Path(Files.createTempDirectory("nb-seed-spec"))
    PathUtil.setDataRoot(home)

  override def afterAll(): Unit =
    PathUtil.setDataRoot(prevRoot)
    os.remove.all(home)

  private def ensure(): Unit = SeedService.ensureSeeded().unsafeRunSync()

  private def dataRoot: os.Path = PathUtil.dataRoot

  private val DefaultPlugins = List(
    "visual-report",
    "slideblocks",
    "nebflow-plugin-creator",
    "web-search-toolkit",
    "browser-use",
    "computer-use",
    "document-production")

  // ── ① fresh home：完整播种（plugin）+ agent 面零播种 ────────
  test("fresh home seeds the seven default plugins and NOTHING on the agent face (four agents are code-defined, builtin-def 2026-10-03)"):
    ensure()

    // builtin-def 批：四件收敛 agent 的 def 面在代码（BuiltinAgents）——种子对
    // agents/ 树**零写入**（全负向钉死；史实的 3-agent + prompt-only kernel 播种
    // 均已退役）。任何 agent 目录/文件落盘 ⇒ 本条即红。
    assert(!os.exists(home / "agents"),
      "fresh home must NOT create the agents/ tree at all (agent seeding retired, builtin-def 2026-10-03 — the four agents are code-defined)")

    // Default preinstall set = the manifest's seven plugin items. Directory in place + trusted.
    for name <- DefaultPlugins
    do
      assert(os.exists(home / "plugins" / name / "plugin.json"), s"plugin '$name'/plugin.json present")
      assert(PluginRegistry.resolve(name).unsafeRunSync().isRight, s"plugin '$name' trusted")
    // 负向守卫（新口径，2026-09-12）：播种的唯一驱动源是 manifest.items（SeedService.runSeed
    // 逐条遍历），而默认预装集与种子树可手动装全集已解耦（K9 断言见 SeedManifestCoverageSpec）⇒
    // ① 名字既不在 manifest 也不在种子树者绝不落 home；② **在种子树但不在默认集**的 5 包
    // 也绝不落 home（默认集收缩不删种子文件，但也不预装——既有 home 面积不扩张到 8 条）。
    for name <- List("no-such-plugin-in-manifest", "design-cards")
    do
      assert(!os.exists(home / "plugins" / name),
        s"plugin '$name' NOT seeded (absent from manifest + seed/plugins tree)")
    for name <- List(
        "nebflow-qa",
        "nebflow-frontend-dev",
        "engineering-methods",
        "explorer-toolkit",
        "design-spec")
    do
      assert(!os.exists(home / "plugins" / name),
        s"plugin '$name' NOT seeded (in seed tree but not in default preinstall set)")
    // skill 包实际复制实证（默认集中抽验两包，plugin.json 锚点 + 整目录递归）
    assert(os.exists(home / "plugins" / "visual-report" / "skills" / "visual-report" / "SKILL.md"),
      "visual-report skill copied")
    assert(os.exists(home / "plugins" / "slideblocks" / "skills" / "slideblocks" / "SKILL.md"),
      "slideblocks skill copied")

    // ── Project face (kernelgen 2026-09-26, flipped contract): a fresh home does NOT
    // scaffold projects/general — the manifest carries zero `project:` items, so
    // neither the full-seeding pass nor reconcileProjects ever creates it. Non-vacuous:
    // under the baseline manifest (one `project:` item for general) this assertion goes RED
    // (seedProject would scaffold the directory) — that red run is the C4 red-proof.
    assert(!os.exists(home / "projects" / "general"),
      "fresh home must NOT scaffold projects/general (general retired from cold start, kernelgen 2026-09-26)")

    // marker
    val marker = home / ".seed-state.json"
    assert(os.exists(marker), ".seed-state.json written")
    val state = io.circe.parser.parse(os.read(marker)).toOption.get
    assert(state.hcursor.downField("version").as[String].toOption.contains("1.0.0"))
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.nonEmpty), "items recorded")
    // Marker count = items actually written this run = **7** = the seven default
    // plugins (builtin-def batch 2026-10-03: the four agents are code-defined, the
    // manifest carries zero agents:/project: items). History: 7 (pre-kernelgen, with
    // the project:general entry) -> 8 (ext: +agents:kernel) -> 11 (manifestfix union)
    // -> 7 (builtin-def: all agents: items retired).
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.size == 7),
      "marker records 7 items (the seven default plugins, zero agent/project items)")
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(items =>
      DefaultPlugins.map("plugins:" + _).forall(items.contains)),
      "marker records the seven plugin items")
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(!_.exists(_.startsWith("agents:"))),
      "marker records ZERO agents: items (agent seeding retired, builtin-def 2026-10-03)")

  // ── ② 幂等 / 不覆盖用户编辑 ───────────────────────────────
  test("re-seed is idempotent and never touches the agents/ tree or user edits"):
    ensure()  // first seed
    // 用户在 agents/ 树的任何文件（这里是手工放的 general/agent.json）都必须
    // 逐字保留——播种面对 agents/ **零写入、零删除**（builtin-def 批：四件收敛
    // agent 代码定义，磁盘是其死信；种子绝不碰它们，也不清理它们）。
    val agentPath = home / "agents" / "general" / "agent.json"
    val custom = """{"name":"general","description":"用户改写","customMarker":true}"""
    os.makeDir.all(home / "agents" / "general")
    os.write.over(agentPath, custom)
    ensure()  // re-seed
    assert(os.read(agentPath).contains("customMarker"), "user file under agents/ preserved across re-seed")

    // Project face (kernelgen 2026-09-26): projects/general no longer exists on a
    // cold start, so the old "re-seed never silently overwrites an existing
    // project.json" subject is gone with the retired manifest item — the contract
    // here flips to the negative: a re-seed on a marker-current home still creates
    // nothing.
    assert(!os.exists(home / "projects" / "general"),
      "re-seed still does not create projects/general (zero backfill, kernelgen 2026-09-26)")

  // ── ③ fresh-home 守卫：已有用户数据 → 不完整播种（agent 面零自愈）──
  test("existing user data skips full seeding; the agent self-heal leg is retired (nothing appears under agents/)"):
    // 模拟已有项目（非 fresh home）
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    val myprojJson = myproj / "project.json"
    os.write.over(myprojJson, """{"name":"myproj"}""")
    val myprojBefore = os.read(myprojJson) // ③ 零覆盖断言的基准（逐字比对）

    // 清掉上一次的 marker 与一般项，让本次判定只由「已有项目」触发
    os.remove.all(home / ".seed-state.json")
    os.remove.all(home / "agents") // ② 的用户文件不留在场——本用例钉「零自愈」全负向
    os.remove.all(home / "projects" / "general")

    ensure()

    // 守卫语义（不变）：不完整播种 ⇒ marker.items 为空表。
    val marker = home / ".seed-state.json"
    assert(os.exists(marker), "marker recorded")
    val state = io.circe.parser.parse(os.read(marker)).toOption.get
    assert(state.hcursor.downField("items").as[List[String]].toOption.contains(Nil), "no items for existing-user-data run")
    // builtin-def 批：agents 自愈腿（reconcileAgents，2026-09-13「缺失自愈」批）
    // 随 agent 播种面一并退役——四件收敛 agent 是代码定义，不存在「缺件态」；
    // 史实的 dispatcher/Nebula 自愈正控与 kernel 的 prompt-only 负控一并作废，
    // 本批改为**全负向**：agents/ 树零出现（本 home 未手工放置任何 agent 文件）。
    assert(!os.exists(home / "agents"),
      "no agent self-heal under the existing-user-data guard (agent reconcile leg retired, builtin-def 2026-10-03)")

    // ── Project face (kernelgen 2026-09-26, flipped again — full circle): the manifest
    // carries zero `project:` items ⇒ reconcileProjects is dormant ⇒ an existing home
    // missing general is NOT backfilled.
    assert(!os.exists(home / "projects" / "general"),
      "missing default project 'general' is NOT backfilled in an existing home (reconcileProjects dormant: zero project: manifest items, kernelgen 2026-09-26)")

    // ③ 零覆盖：既有 `projects/myproj/project.json` 内容逐字不变（既有内容零覆盖/零搬移/零删除）
    val myprojAfter = os.read(myprojJson)
    assert(myprojAfter == myprojBefore, "existing project file byte-identical after the add-only reconcile")
    assert(os.list(home / "projects").map(_.last).sorted == List("myproj"),
      "no project directory beyond the pre-existing 'myproj' (general is not backfilled)")

    // Idempotence (kernelgen 2026-09-26 shape): the second boot adds zero directories
    // and performs zero writes to existing files.
    val myprojMtime = os.mtime(myprojJson)
    ensure()
    assert(os.list(home / "projects").map(_.last).sorted == List("myproj"),
      "second boot still creates no project directory (no backfilled 'general')")
    assert(os.mtime(myprojJson) == myprojMtime,
      "second boot leaves the existing project file untouched (mtime unchanged ⇒ zero writes)")
    assert(os.read(myprojJson) == myprojBefore, "existing project file still byte-identical after the second boot")

  // ── ④ 升级 add-only：低版本 marker + 已有文件 → 只补缺失 ──
  test("upgrade run is add-only: fills missing plugins, never writes the agent face"):
    // 重置到 fresh-ish 状态（无任何项目，但 marker 为低版本 + 用户 agent 文件已存在）
    os.remove.all(home / "projects")
    os.remove.all(home / "agents")
    os.remove.all(home / "plugins")
    os.remove.all(home / ".seed-state.json")
    // 用户已有的 general/agent.json（升级前就在——史实播种的遗留形态）
    os.makeDir.all(home / "agents" / "general")
    os.write.over(home / "agents" / "general" / "agent.json", """{"name":"general","preUpgrade":true}""")
    // 低版本 marker
    os.write.over(home / ".seed-state.json",
      """{"version":"0.5.0","seededAt":123,"items":[]}""")

    ensure()

    // 已有文件不被覆盖（用户胜出）；**不再补种任何 agent 文件**（agent 面零播种）
    val agentContent = os.read(home / "agents" / "general" / "agent.json")
    assert(agentContent.contains("preUpgrade"), "pre-existing agent.json not rewritten (user wins)")
    assert(!os.exists(home / "agents" / "project-dispatcher"),
      "no dispatcher replant on upgrade (agent seeding retired, builtin-def 2026-10-03)")
    assert(!os.exists(home / "agents" / "kernel"),
      "no kernel mirror replant on upgrade (prompt-only seed item retired, builtin-def 2026-10-03)")
    assert(os.list(home / "agents").map(_.last).sorted == List("general"),
      "the agents/ tree keeps exactly the user's pre-existing entry — nothing added")
    // Upgrade add-only replants the full current default set = seven plugins.
    for name <- DefaultPlugins
    do assert(os.exists(home / "plugins" / name / "plugin.json"), s"missing plugin '$name' added")
    for name <- List("explorer-toolkit", "design-spec")
    do
      assert(!os.exists(home / "plugins" / name / "plugin.json"),
        s"non-default seed plugin '$name' NOT replanted on upgrade run (out of default set)")
    assert(!os.exists(home / "projects" / "general"),
      "upgrade run does NOT replant projects/general (zero project: manifest items, kernelgen 2026-09-26)")
    // marker 升级到当前版本
    val marker = io.circe.parser.parse(os.read(home / ".seed-state.json")).toOption.get
    assert(marker.hcursor.downField("version").as[String].toOption.contains("1.0.0"), "marker bumped to 1.0.0")

  // ── ⑤ Manifest-face contract (kernelgen 2026-09-26: zero project items;
  //    builtin-def 2026-10-03: zero agents items; the dormant scaffold tree stays) ────
  test("manifest declares ZERO project items and ZERO agents items (agents are code-defined); the dormant project seed tree stays in the repo"):
    // 判据 = 真值读取（classpath 上的同一份资源），**禁恒真**：任一方向漂移即红——
    // putting a `project:*` or `agents:*` item back into the manifest ⇒ this test goes
    // red (re-entering agent seeding is a product decision that needs the author's
    // explicit sign-off, builtin-def 2026-10-03).
    val manifestText = {
      val in = Option(getClass.getClassLoader.getResourceAsStream("seed/manifest.json")).getOrElse(
        fail("classpath resource 'seed/manifest.json' not found")
      )
      try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally in.close()
    }
    val items = io.circe.parser.parse(manifestText).toOption
      .flatMap(_.hcursor.downField("items").as[List[String]].toOption).getOrElse(fail("manifest.items unreadable"))
    val projectItems = items.filter(_.startsWith("project:"))
    assert(projectItems.isEmpty,
      s"seed manifest must declare ZERO 'project:' items (got ${projectItems.mkString(", ")} of " +
        s"${items.size} item(s)) — kernelgen batch 2026-09-26 retired the general project from " +
        "cold start (kernel replaces general); re-adding a project item is a product decision")
    val agentItems = items.filter(_.startsWith("agents:"))
    assert(agentItems.isEmpty,
      s"seed manifest must declare ZERO 'agents:' items (got ${agentItems.mkString(", ")}) — " +
        "builtin-def batch 2026-10-03: the four converged agents are code-defined " +
        "(nebflow.core.entity.BuiltinAgents); re-adding an agents: item is a product decision")
    // Dormant scaffold tree stays on the classpath (the mechanical face is kept, not deleted:
    // the seed-tree template does not travel with the manifest item — rollback-friendly).
    // Criterion matches the sibling spec = classpath
    // （sbt test = target/classes 拷贝，assembly = jar）。
    assert(getClass.getClassLoader.getResource("seed/projects/general/AGENTS.md") != null,
      "dormant seed project resource tree (seed/projects/general/AGENTS.md) is still on the classpath (kept, not deleted)")
    // builtin-def 批：seed/agents 资源树**删除**（代码即权威，种子镜像的第二份拷贝
    // 是漂移源）——与 project 面的「休眠保留」不同，agent 面零残留。回滚 = revert。
    assert(getClass.getClassLoader.getResource("seed/agents/kernel/system.md") == null,
      "seed/agents resource tree is retired from the classpath (builtin-def 2026-10-03; rollback = revert this batch)")

  // ── ⑥ 零覆盖负控：既有（手工版）projects/general 逐字节不变 ──────────
  test("a pre-existing hand-made projects/general survives boot byte-identical (add-only, zero overwrite)"):
    // 本用例是「补种波及既有 home」这条新口径的**零覆盖负控**：手工建的 general 内容与
    // 种子文本**故意不同**（project.json 的 workspace 指向别处 + customMarker；AGENTS.md
    // 为用户自持文本；.gitignore 为用户自持内容）⇒ 覆盖面三文件（项目定义 / agent 指令 /
    // .gitignore）逐字节钉住。判红面 = 任何「对齐种子 / 镜像覆盖 / 按 workspace 纠正既有定义 /
    // 经 `ProjectStore.ensureScaffold` 打补丁（该路径会**追加** `.gitignore`）」的实现
    // ——本批禁：不得 seed→runtime 覆写既有 general 的任何文件。
    // kernelgen 2026-09-26: this negative control now doubles as the SEALED-FAMILY
    // guarantee — with reconcileProjects dormant (zero `project:` manifest items), a
    // pre-existing hand-made projects/general is untouched on every boot (no seed
    // pass even iterates it; it is likewise never deleted).
    os.remove.all(home / "projects")
    os.remove.all(home / ".seed-state.json")
    val handGeneral = home / "projects" / "general"
    os.makeDir.all(handGeneral)
    val handJson = """{"name":"general","workspace":"/tmp/somewhere-else","customMarker":true}"""
    val handAgents = "# general — 手工版 AGENTS.md（用户自持，非种子文本）\n"
    val handGitignore = "# 手工版 .gitignore（用户自持）\nbuild/\n.local/\n"
    val handFiles = List("project.json" -> handJson, "AGENTS.md" -> handAgents, ".gitignore" -> handGitignore)
    handFiles.foreach { case (name, text) => os.write.over(handGeneral / name, text) }
    // 另一个既有项目 ⇒ home 判定为「已有用户数据」（reconcileProjects 与门无关，此处仅为场景真实性）
    os.makeDir.all(home / "projects" / "other")
    os.write.over(home / "projects" / "other" / "project.json", """{"name":"other"}""")
    val shasBefore = handFiles.map { case (name, text) => name -> sha256(text) }.toMap
    val projectsBefore = os.list(home / "projects").map(_.last).sorted

    ensure()
    ensure() // 连续两次 boot（幂等面一并覆盖）

    for case (name, text) <- handFiles do
      assert(os.read(handGeneral / name) == text,
        s"hand-made projects/general/$name byte-identical across boots (directory-level guard ⇒ zero action)")
    assert(os.list(home / "projects").map(_.last).sorted == projectsBefore,
      "add-only reconcile creates no extra project directory")

  private def sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

end SeedServiceSpec
