package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.plugin.PluginRegistry

import java.nio.file.Files

/**
 * SeedService cold-start 播种引擎验证（cold-start seed 批 2026-09-07）。
 *
 * 覆盖定稿四项：① fresh home 完整播种（四 keeper + 3 个默认预装插件；**零项目**——
 * 内置 general 项目 2026-09-16 作者令摘除）、② 幂等 / 不覆盖用户编辑、
 * ③ fresh-home 守卫（已有用户数据 → 只写 marker 不播种）、
 * ④ 升级 add-only（低版本 marker + 已有文件 → 只补缺失，不重写）；
 * ⑤ 摘除面负向不变量（manifest 零 `project:` 条目 + 干净 home 零 `projects/general`）。
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

  // ── ① fresh home：完整播种（agent + plugin；项目面零播种）├────────
  test("fresh home seeds four keepers + plugins, and zero project (built-in general removed)"):
    ensure()

    // 四 keeper：Nebula 由 seedDefaults 管（本测不触发）；补的两个在此断言
    val pdAgentJson = home / "agents" / "project-dispatcher" / "agent.json"
    val genAgentJson = home / "agents" / "general" / "agent.json"
    assert(os.exists(pdAgentJson), "project-dispatcher/agent.json seeded")
    assert(os.exists(home / "agents" / "project-dispatcher" / "system.md"), "project-dispatcher/system.md seeded")
    assert(os.exists(genAgentJson), "general/agent.json seeded")
    assert(os.exists(home / "agents" / "general" / "system.md"), "general/system.md seeded")

    // agent.json 基线锚定（TB #20 基线对齐 2026-09-09）：种子以 runtime trusted 形态为准，
    // preset/skills 字段合法入 seed——project-dispatcher: preset=general, skills=[]
    val pd = io.circe.parser.parse(os.read(pdAgentJson)).toOption.get
    assert(pd.hcursor.downField("preset").as[String].toOption.contains("general"),
      "project-dispatcher preset=general")
    assert(pd.hcursor.downField("skills").as[List[String]].toOption.exists(_.isEmpty),
      "project-dispatcher skills=[]")
    assert(pd.hcursor.downField("name").as[String].toOption.contains("project-dispatcher"))

    val gen = io.circe.parser.parse(os.read(genAgentJson)).toOption.get
    assert(gen.hcursor.downField("preset").as[String].toOption.contains("general"),
      "general preset=general")
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

    // ── 项目面：**零播种**（2026-09-16 作者令「移除 general 这个内置项目」）────────
    // 负向断言（本批改写面）：干净 home 不再出现 projects/general 脚手架——原正向断言
    // （project.json 存在 + name/workspace/AGENTS.md 内容）已随 `project:` 种子条目与
    // `seed/projects/general/` 资源树摘除而失效。**禁恒真**：断言路径级不存在（若播种
    // 面被重新加回，本行立即红）。
    assert(!os.exists(home / "projects" / "general"),
      "no built-in 'general' project scaffolded on a fresh home (project seed removed 2026-09-16)")
    assert(!os.exists(home / "projects") || os.list(home / "projects").isEmpty,
      "the seed pass writes zero project scaffolds (projects/ stays absent or empty)")

    // marker
    val marker = home / ".seed-state.json"
    assert(os.exists(marker), ".seed-state.json written")
    val state = io.circe.parser.parse(os.read(marker)).toOption.get
    assert(state.hcursor.downField("version").as[String].toOption.contains("1.0.0"))
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.nonEmpty), "items recorded")
    // marker 记录 = 本轮实际写入 item 数：4 agents + 3 plugins = **7**（默认插件集全量）。
    // 本批前 = 8（4 agents + 3 plugins + 1 project）；项目条目随 2026-09-16 摘除面
    // 从 manifest 移除 ⇒ 计数同步收窄（`memory-consolidator` 由记忆队列批 2026-09-12
    // 纳入种子 manifest；默认插件集由插件面修复批 2026-09-12 回退为三条）。
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.size == 7),
      "marker records 7 items (4 agents + 3 plugins; project: item removed 2026-09-16)")

  // ── ② 幂等 / 不覆盖用户编辑 ───────────────────────────────
  test("re-seed is idempotent and never overwrites user edits"):
    ensure()  // first seed
    // 用户编辑 general/agent.json（写一个自定义标记）
    val agentPath = home / "agents" / "general" / "agent.json"
    val custom = """{"name":"general","description":"用户改写","customMarker":true}"""
    os.write.over(agentPath, custom)
    ensure()  // re-seed
    assert(os.read(agentPath).contains("customMarker"), "user edit preserved across re-seed")

    // 项目面（本批改写）：默认集零项目 ⇒ 重复播种不建任何项目脚手架
    // （原「general 项目原样保留（不重复 create）」断言随摘除面失效；幂等语义仍由
    // 上面的「用户改写保留」断言承载）。
    ensure()
    assert(!os.exists(home / "projects" / "general"),
      "no project scaffold across re-seed (project seed removed 2026-09-16)")

  // ── ③ fresh-home 守卫：已有用户数据 → 不完整播种（但默认集 agent 自愈）──
  test("existing user data skips full seeding (no project scaffold), self-heals default-set agents"):
    // 模拟已有项目（非 fresh home）
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")

    // 清掉上一次的 marker 与一般项，让本次判定只由「已有项目」触发
    os.remove.all(home / ".seed-state.json")
    os.remove.all(home / "agents")
    os.remove.all(home / "projects" / "general")

    ensure()

    // 守卫语义（不变）：不完整播种 ⇒ 不建 general 项目脚手架、marker.items 为空
    assert(!os.exists(home / "projects" / "general"), "no general project seeded when user data present")
    val marker = home / ".seed-state.json"
    assert(os.exists(marker), "marker recorded")
    val state = io.circe.parser.parse(os.read(marker)).toOption.get
    assert(state.hcursor.downField("items").as[List[String]].toOption.contains(Nil), "no items for existing-user-data run")
    // 2026-09-13 语义变更（作者令「改成缺失自愈」，取代 D-8「缺失不新装」）：默认集 agent
    // 在既有 home 也要自愈补装——否则消费链（memory-consolidator）在既有 home 永不可能
    // 就位，记忆队列只进不出。原断言「no dispatcher seeded when user data present」已按
    // 新口径改写（这是预期的判红样例：改测试，不改守卫）。
    for name <- List("project-dispatcher", "memory-consolidator")
    do assert(os.exists(home / "agents" / name / "agent.json"), s"default-set agent '$name' self-healed under the guard")

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
    // 项目面（本批改写）：升级 run 也**不补任何项目**（原「missing project added」正向断言
    // 随 `project:` 条目摘除失效 ⇒ 改为路径级负向断言，禁恒真）
    assert(!os.exists(home / "projects" / "general"),
      "no project scaffold added by the upgrade run (project seed removed 2026-09-16)")
    // marker 升级到当前版本
    val marker = io.circe.parser.parse(os.read(home / ".seed-state.json")).toOption.get
    assert(marker.hcursor.downField("version").as[String].toOption.contains("1.0.0"), "marker bumped to 1.0.0")

  // ── ⑤ 摘除面负向不变量（2026-09-16 作者令）────────────────
  test("removed project seed stays removed: manifest declares zero 'project:' items and carries no project seed tree"):
    // 判据 = 真值读取（classpath 上的同一份资源），**禁恒真**：条目/资源树任一回来，本测红。
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
      s"seed manifest declares ${projectItems.size} 'project:' item(s) (${projectItems.mkString(", ")}) — " +
        "the built-in project seed was removed by the author on 2026-09-16; re-adding one is a product decision")
    // 种子资源树同批摘除（无 `seed/projects/` 树 = 无从播种项目脚手架）。
    // 判据与兄弟 spec 同口径 = classpath（sbt test = target/classes 拷贝，assembly = jar）。
    assert(getClass.getClassLoader.getResource("seed/projects/general/AGENTS.md") == null,
      "seed project resource tree (seed/projects/) is gone from the classpath")

end SeedServiceSpec
