package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.plugin.PluginRegistry

import java.nio.file.Files

/**
 * SeedService cold-start 播种引擎验证（cold-start seed 批 2026-09-07）。
 *
 * 覆盖定稿四项：① fresh home 完整播种（3 manifest agents + 4 默认预装插件，Nebula 由
 * manifest 种子树下发，govmemory 批 2026-09-25 起 memory agent 退役；projects/general
 * is NO LONGER seeded since the kernelgen batch 2026-09-26 — the manifest carries
 * zero `project:` items）、
 * ② 幂等 / 不覆盖用户编辑、
 * ③ fresh-home 守卫（已有用户数据 → 只写 marker 不播种）、
 * ④ 升级 add-only（低版本 marker + 已有文件 → 只补缺失，不重写）；
 * ⑤ project-face contract (FLIPPED by the kernelgen batch 2026-09-26, superseding the
 * author's 2026-09-17 rulings ①② seeding posture): the manifest declares ZERO
 * `project:` items ⇒ a cold start does not create projects/general; an existing home
 * missing general is NOT backfilled (reconcileProjects dormant); the
 * `seed/projects/general/` template tree stays in the repo as a dormant piece
 * (rollback-friendly).
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

  // ── ① fresh home：完整播种（agent + plugin + project）├────────
  test("fresh home seeds three agents + four plugins, and NO project (general retired from cold start)"):
    ensure()

    // 三个 manifest agent：Nebula / project-dispatcher / general 全部由种子树下发
    val pdAgentJson = home / "agents" / "project-dispatcher" / "agent.json"
    val genAgentJson = home / "agents" / "general" / "agent.json"
    val nbAgentJson = home / "agents" / "Nebula" / "agent.json"
    assert(os.exists(pdAgentJson), "project-dispatcher/agent.json seeded")
    assert(os.exists(home / "agents" / "project-dispatcher" / "system.md"), "project-dispatcher/system.md seeded")
    assert(os.exists(genAgentJson), "general/agent.json seeded")
    assert(os.exists(home / "agents" / "general" / "system.md"), "general/system.md seeded")
    assert(os.exists(nbAgentJson), "Nebula/agent.json seeded")
    assert(os.exists(home / "agents" / "Nebula" / "system.md"), "Nebula/system.md seeded")

    // agent.json 基线锚定（TB #20 基线对齐 2026-09-09）：种子以 runtime trusted 形态为准，
    // preset/skills 字段合法入 seed——project-dispatcher（**可设两类之一**）: preset=general, skills=[]。
    // panelscheme 批（2026-09-21）：可设性收敛为 Nebula + 任务分发器两类，种子只在这两类
    // 下发自有 preset；general 见下（改钉缺键）。
    val pd = io.circe.parser.parse(os.read(pdAgentJson)).toOption.get
    assert(pd.hcursor.downField("preset").as[String].toOption.contains("general"),
      "project-dispatcher preset=general")
    assert(pd.hcursor.downField("skills").as[List[String]].toOption.exists(_.isEmpty),
      "project-dispatcher skills=[]")
    assert(pd.hcursor.downField("name").as[String].toOption.contains("project-dispatcher"))

    val gen = io.circe.parser.parse(os.read(genAgentJson)).toOption.get
    // panelscheme 批（2026-09-21，作者令：节点无自有模型方案）：`general` 是节点
    // worker/verify 的唯一执行 agent，模型方案由引擎动态继承**任务分发器当前方案**
    // （SchemePolicy），种子不再下发自有 preset——下发即死键（引擎忽略），故此处改钉
    // 「缺键」契约：谁把 general 的自有方案写回种子，本条即时红。
    assert(gen.hcursor.downField("preset").as[String].toOption.isEmpty,
      "general must ship no self-owned preset (panelscheme 2026-09-21: nodes inherit the dispatcher's scheme)")
    assert(gen.hcursor.downField("name").as[String].toOption.contains("general"))

    // 现行默认插件集 = 3 包（manifest.json:8-10，作者 2026-09-12 裁定回退）：
    // c7501470 收缩为 2 包 → nebflow-plugin-creator 批扩为 3 包（09-10）→ seed7 批再扩 5 包
    // （09-11，非预期扩张）→ 本批回退为 3 包。目录就位 + trusted
    for name <- List(
        "visual-report",
        "slideblocks",
        "nebflow-plugin-creator")
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
    // Marker count = items actually written this run = **7** = the full current
    // manifest: 3 agents (project-dispatcher / general / Nebula) + 4 default plugins
    // (visual-report / slideblocks / nebflow-plugin-creator / web-search-toolkit),
    // ZERO project items (kernelgen 2026-09-26: the general `project:` entry was
    // removed; pre-kernelgen = 8 with it).
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.size == 7),
      "marker records 7 items (3 agents + 4 plugins, zero project items)")

  // ── ② 幂等 / 不覆盖用户编辑 ───────────────────────────────
  test("re-seed is idempotent and never overwrites user edits"):
    ensure()  // first seed
    // 用户编辑 general/agent.json（写一个自定义标记）
    val agentPath = home / "agents" / "general" / "agent.json"
    val custom = """{"name":"general","description":"用户改写","customMarker":true}"""
    os.write.over(agentPath, custom)
    ensure()  // re-seed
    assert(os.read(agentPath).contains("customMarker"), "user edit preserved across re-seed")

    // Project face (kernelgen 2026-09-26): projects/general no longer exists on a
    // cold start, so the old "re-seed never silently overwrites an existing
    // project.json" subject is gone with the retired manifest item — the contract
    // here flips to the negative: a re-seed on a marker-current home still creates
    // nothing. The user-edit-preservation invariant stays pinned on the agent face
    // above (the general AGENT is not retired; its seed tree still ships).
    assert(!os.exists(home / "projects" / "general"),
      "re-seed still does not create projects/general (zero backfill, kernelgen 2026-09-26)")

  // ── ③ fresh-home 守卫：已有用户数据 → 不完整播种（但默认集 agent + 项目自愈）──
  test("existing user data skips full seeding, add-only self-heals the default set (agents); general is NOT backfilled"):
    // 模拟已有项目（非 fresh home）
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    val myprojJson = myproj / "project.json"
    os.write.over(myprojJson, """{"name":"myproj"}""")
    val myprojBefore = os.read(myprojJson) // ③ 零覆盖断言的基准（逐字比对）

    // 清掉上一次的 marker 与一般项，让本次判定只由「已有项目」触发
    os.remove.all(home / ".seed-state.json")
    os.remove.all(home / "agents")
    os.remove.all(home / "projects" / "general")

    ensure()

    // 守卫语义（不变）：不完整播种 ⇒ marker.items 为空表。**本行保持不动**：
    // 既有 home 分支仍走 marker-only（`.seed-state.json` 的 items 恒空）——补种判据是
    // 「manifest 声明 + 存在性守卫」，不是 marker 状态机。
    val marker = home / ".seed-state.json"
    assert(os.exists(marker), "marker recorded")
    val state = io.circe.parser.parse(os.read(marker)).toOption.get
    assert(state.hcursor.downField("items").as[List[String]].toOption.contains(Nil), "no items for existing-user-data run")
    // 2026-09-13 语义变更（作者令「改成缺失自愈」，取代 D-8「缺失不新装」）：默认集 agent
    // 在既有 home 也要自愈补装。原断言「no dispatcher seeded when user data present」已按
    // 新口径改写（这是预期的判红样例：改测试，不改守卫）。govmemory 批（2026-09-25）：
    // memory 消费 agent 退役，默认集第三席 = Nebula（manifest 种子树下发）。
    for name <- List("project-dispatcher", "Nebula")
    do assert(os.exists(home / "agents" / name / "agent.json"), s"default-set agent '$name' self-healed under the guard")

    // ── Project face (kernelgen 2026-09-26, flipped again — full circle): the manifest
    // carries zero `project:` items ⇒ reconcileProjects is dormant ⇒ an existing home
    // missing general is NOT backfilled. This re-asserts the negative that the
    // 2026-09-17 ruling ② had flipped to positive, but this time the flip lives in the
    // manifest (data), not in a guard change. Non-vacuous: the baseline manifest
    // (with its general `project:` item) makes this RED (reconcileProjects backfills
    // the directory) — covered by the C4 red-proof run.
    assert(!os.exists(home / "projects" / "general"),
      "missing default project 'general' is NOT backfilled in an existing home (reconcileProjects dormant: zero project: manifest items, kernelgen 2026-09-26)")

    // ③ 零覆盖：既有 `projects/myproj/project.json` 内容逐字不变（既有内容零覆盖/零搬移/零删除）
    val myprojAfter = os.read(myprojJson)
    assert(myprojAfter == myprojBefore, "existing project file byte-identical after the add-only reconcile")
    // (2) no new directory under `projects/` (kernelgen 2026-09-26: nothing is backfilled, so the
    // listing is exactly the pre-existing 'myproj')
    assert(os.list(home / "projects").map(_.last).sorted == List("myproj"),
      "no project directory beyond the pre-existing 'myproj' (general is not backfilled)")
    println(s"[DIAG-ZERO-OVERWRITE] projects/myproj/project.json sha256 before=${sha256(myprojBefore)} " +
      s"after=${sha256(myprojAfter)} byteIdentical=${myprojAfter == myprojBefore}")

    // (2) Idempotence (kernelgen 2026-09-26 reshaped this check: with general gone from the
    // project face there is no general mtime left to pin ⇒ the idempotence criterion folds
    // onto the pre-existing myproj — the second boot adds zero directories and performs
    // zero writes to existing files)
    val myprojMtime = os.mtime(myprojJson)
    ensure()
    assert(os.list(home / "projects").map(_.last).sorted == List("myproj"),
      "second boot still creates no project directory (no backfilled 'general')")
    assert(os.mtime(myprojJson) == myprojMtime,
      "second boot leaves the existing project file untouched (mtime unchanged ⇒ zero writes)")
    assert(os.read(myprojJson) == myprojBefore, "existing project file still byte-identical after the second boot")
    println(s"[DIAG-IDEMPOTENT] projects/myproj/project.json mtime before=$myprojMtime " +
      s"after=${os.mtime(myprojJson)} unchanged=${os.mtime(myprojJson) == myprojMtime}")

  // ── ④ 升级 add-only：低版本 marker + 已有文件 → 只补缺失 ──
  test("upgrade run is add-only: fills missing files, does not rewrite existing"):
    // 重置到 fresh-ish 状态（无任何项目，但 marker 为低版本 + general/agent.json 已存在）
    os.remove.all(home / "projects")
    os.remove.all(home / "agents")
    os.remove.all(home / "plugins")
    os.remove.all(home / ".seed-state.json")
    // 用户已有的 general/agent.json（升级前就在）
    os.makeDir.all(home / "agents" / "general")
    os.write.over(home / "agents" / "general" / "agent.json", """{"name":"general","preUpgrade":true}""")
    // 低版本 marker
    os.write.over(home / ".seed-state.json",
      """{"version":"0.5.0","seededAt":123,"items":[]}""")

    ensure()

    // 已有文件不被覆盖（用户胜出）
    val agentContent = os.read(home / "agents" / "general" / "agent.json")
    assert(agentContent.contains("preUpgrade"), "pre-existing agent.json not rewritten (user wins)")
    // 缺失的补齐：project-dispatcher、插件（补种集 = 现行 manifest 默认集，manifest 驱动 SeedService.runSeed）
    assert(os.exists(home / "agents" / "project-dispatcher" / "agent.json"), "missing dispatcher added")
    assert(os.exists(home / "plugins" / "visual-report" / "plugin.json"), "missing plugin added")
    assert(os.exists(home / "plugins" / "slideblocks" / "plugin.json"), "missing plugin added")
    assert(os.exists(home / "plugins" / "nebflow-plugin-creator" / "plugin.json"), "missing plugin added")
    // 升级 add-only 的语义是「补齐 manifest 全集」，不是「冻结旧集」——但「全集」= 现行
    // **默认预装集**（3 包），不是种子树可手动装全集：seed7 批新入 manifest 的 5 包
    // （nebflow-qa / nebflow-frontend-dev / engineering-methods / explorer-toolkit /
    // design-spec）随本批回退默认集 ⇒ 升级 run 后**不再**被补种（负向断言，抽验两包）。
    // 非默认集种子包的手动安装面由 `SeedManifestCoverageSpec` 的 b/c 两条锚定（文件保留）。
    for name <- List("explorer-toolkit", "design-spec")
    do
      assert(!os.exists(home / "plugins" / name / "plugin.json"),
        s"non-default seed plugin '$name' NOT replanted on upgrade run (out of default set)")
    // Project face (kernelgen 2026-09-26, flipped): the upgrade add-only run no longer
    // replants any project — the manifest has zero `project:` items, so after the
    // pre-run `os.remove.all(home / "projects")` NOTHING is recreated (the old
    // positive "missing project added" assertion is superseded together with the
    // retired manifest item). Non-vacuous: the baseline manifest makes this RED
    // (runSeed would replant general) — covered by the C4 red-proof.
    assert(!os.exists(home / "projects" / "general"),
      "upgrade run does NOT replant projects/general (zero project: manifest items, kernelgen 2026-09-26)")
    // marker 升级到当前版本
    val marker = io.circe.parser.parse(os.read(home / ".seed-state.json")).toOption.get
    assert(marker.hcursor.downField("version").as[String].toOption.contains("1.0.0"), "marker bumped to 1.0.0")

  // ── ⑤ Project-face manifest contract (kernelgen 2026-09-26: zero project items;
  //    the dormant scaffold tree stays in the repo) ────
  test("manifest declares ZERO project items (general retired from cold start) and the dormant seed tree stays in the repo"):
    // 判据 = 真值读取（classpath 上的同一份资源），**禁恒真**：任一方向漂移即红——
    // putting a `project:*` item back into the manifest ⇒ this test goes red (re-entering
    // seeding is a product decision that needs the author's explicit sign-off); deleting
    // the dormant template tree ⇒ this test goes red all the same (the mechanical face is
    // kept, not deleted — a rollback-friendly contract).
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
    // Dormant scaffold tree stays on the classpath (the mechanical face is kept, not deleted:
    // the seed-tree template does not travel with the manifest item — rollback-friendly).
    // Criterion matches the sibling spec = classpath
    // （sbt test = target/classes 拷贝，assembly = jar）。
    assert(getClass.getClassLoader.getResource("seed/projects/general/AGENTS.md") != null,
      "dormant seed project resource tree (seed/projects/general/AGENTS.md) is still on the classpath (kept, not deleted)")

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
    println("[DIAG-HANDMADE-GENERAL] " + handFiles.map { case (name, text) =>
      val after = os.read(handGeneral / name)
      s"$name sha256 before=${shasBefore(name)} after=${sha256(after)} identical=${sha256(after) == shasBefore(name)}"
    }.mkString("; "))

  private def sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

end SeedServiceSpec
