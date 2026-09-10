package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.plugin.PluginRegistry

import java.nio.file.Files

/**
 * SeedService cold-start 播种引擎验证（cold-start seed 批 2026-09-07）。
 *
 * 覆盖定稿四项：① fresh home 完整播种（三 keeper + 3 插件 + projects/general）、
 * ② 幂等 / 不覆盖用户编辑、③ fresh-home 守卫（已有用户数据 → 只写 marker 不播种）、
 * ④ 升级 add-only（低版本 marker + 已有文件 → 只补缺失，不重写）。
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

  // ── ① fresh home：完整播种 ├────────────────────────────────
  test("fresh home seeds three keepers + plugins + project:general"):
    ensure()

    // 三 keeper：Nebula 由 seedDefaults 管（本测不触发）；补的两个在此断言
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

    // 现行默认插件集 = {visual-report, slideblocks, nebflow-plugin-creator}
    // （c7501470 收缩后经 nebflow-plugin-creator 批扩为 3 包，2026-09-10）：目录就位 + trusted
    for name <- List("visual-report", "slideblocks", "nebflow-plugin-creator")
    do
      assert(os.exists(home / "plugins" / name / "plugin.json"), s"plugin '$name'/plugin.json present")
      assert(PluginRegistry.resolve(name).unsafeRunSync().isRight, s"plugin '$name' trusted")
    // 收缩语义负断言：explorer-toolkit / design-spec 已移出 manifest → 不得播种
    for name <- List("explorer-toolkit", "design-spec")
    do
      assert(!os.exists(home / "plugins" / name), s"plugin '$name' NOT seeded (removed from default set)")
    // skill 包实际复制实证（3 包默认集中抽验两包，plugin.json 锚点 + 整目录递归）
    assert(os.exists(home / "plugins" / "visual-report" / "skills" / "visual-report" / "SKILL.md"),
      "visual-report skill copied")
    assert(os.exists(home / "plugins" / "slideblocks" / "skills" / "slideblocks" / "SKILL.md"),
      "slideblocks skill copied")

    // projects/general 脚手架
    val projectJson = home / "projects" / "general" / "project.json"
    assert(os.exists(projectJson), "project:general scaffolded")
    val proj = io.circe.parser.parse(os.read(projectJson)).toOption.get
    assert(proj.hcursor.downField("name").as[String].toOption.contains("general"))
    assert(proj.hcursor.downField("workspace").as[String].toOption.contains((home / "projects" / "general").toString),
      "workspace points at projects/general")
    assert(os.exists(home / "projects" / "general" / "AGENTS.md"), "AGENTS.md scaffolded")
    assert(os.read(home / "projects" / "general" / "AGENTS.md").contains((home / "projects" / "general").toString),
      "AGENTS.md references dataRoot path (placeholder substituted)")

    // marker
    val marker = home / ".seed-state.json"
    assert(os.exists(marker), ".seed-state.json written")
    val state = io.circe.parser.parse(os.read(marker)).toOption.get
    assert(state.hcursor.downField("version").as[String].toOption.contains("1.0.0"))
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.nonEmpty), "items recorded")
    // 2 agents + 3 plugins（visual-report / slideblocks / nebflow-plugin-creator） + 1 project = 6
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.size == 6),
      "marker records 6 items (2 agents + 3 plugins + 1 project)")

  // ── ② 幂等 / 不覆盖用户编辑 ───────────────────────────────
  test("re-seed is idempotent and never overwrites user edits"):
    ensure()  // first seed
    // 用户编辑 general/agent.json（写一个自定义标记）
    val agentPath = home / "agents" / "general" / "agent.json"
    val custom = """{"name":"general","description":"用户改写","customMarker":true}"""
    os.write.over(agentPath, custom)
    ensure()  // re-seed
    assert(os.read(agentPath).contains("customMarker"), "user edit preserved across re-seed")

    // 项目也原样保留（不重复 create）
    ensure()
    assert(os.exists(home / "projects" / "general" / "project.json"))

  // ── ③ fresh-home 守卫：已有用户数据 → 只写 marker ────────
  test("existing user data skips full seeding, only records marker"):
    // 模拟已有项目（非 fresh home）
    val myproj = home / "projects" / "myproj"
    os.makeDir.all(myproj)
    os.write.over(myproj / "project.json", """{"name":"myproj"}""")

    // 清掉上一次的 marker 与一般项，让本次判定只由「已有项目」触发
    os.remove.all(home / ".seed-state.json")
    os.remove.all(home / "agents")
    os.remove.all(home / "projects" / "general")

    ensure()

    assert(!os.exists(home / "agents" / "project-dispatcher"), "no dispatcher seeded when user data present")
    assert(!os.exists(home / "projects" / "general"), "no general project seeded when user data present")
    val marker = home / ".seed-state.json"
    assert(os.exists(marker), "marker recorded")
    val state = io.circe.parser.parse(os.read(marker)).toOption.get
    assert(state.hcursor.downField("items").as[List[String]].toOption.contains(Nil), "no items for existing-user-data run")

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
    // 缺失的补齐：project-dispatcher、插件（补种集 = 现行 manifest 默认集，manifest 驱动 SeedService.runSeed）、项目
    assert(os.exists(home / "agents" / "project-dispatcher" / "agent.json"), "missing dispatcher added")
    assert(os.exists(home / "plugins" / "visual-report" / "plugin.json"), "missing plugin added")
    assert(os.exists(home / "plugins" / "slideblocks" / "plugin.json"), "missing plugin added")
    assert(os.exists(home / "plugins" / "nebflow-plugin-creator" / "plugin.json"), "missing plugin added")
    assert(!os.exists(home / "plugins" / "explorer-toolkit"), "shrink-removed plugin not replanted on upgrade")
    assert(os.exists(home / "projects" / "general" / "project.json"), "missing project added")
    // marker 升级到当前版本
    val marker = io.circe.parser.parse(os.read(home / ".seed-state.json")).toOption.get
    assert(marker.hcursor.downField("version").as[String].toOption.contains("1.0.0"), "marker bumped to 1.0.0")

end SeedServiceSpec
