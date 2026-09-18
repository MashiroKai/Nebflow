package nebflow.core.seed

import cats.effect.unsafe.implicits.global
import munit.FunSuite
import nebflow.core.PathUtil
import nebflow.core.plugin.PluginRegistry

import java.nio.file.Files

/**
 * SeedService cold-start 播种引擎验证（cold-start seed 批 2026-09-07）。
 *
 * 覆盖定稿四项：① fresh home 完整播种（3 keeper agent + 4 默认预装插件 + projects/general，
 * Nebula 由 seedDefaults 管、不在本 spec 断言面）、② 幂等 / 不覆盖用户编辑、
 * ③ fresh-home 守卫（已有用户数据 → 只写 marker 不播种）、
 * ④ 升级 add-only（低版本 marker + 已有文件 → 只补缺失，不重写）；
 * ⑤ 项目面种子在位不变量（作者 2026-09-17 裁定①恢复内置 general 项目播种：
 * manifest 恰一条 `project:general` + 种子资源树在位）。
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
  test("fresh home seeds four keepers + plugins + project:general"):
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

    // ── 项目面：默认通用项目 general 恢复播种（作者 2026-09-17 裁定①，撤销 09-16 摘除令）──
    // 正向断言（本批改写面）：干净 home 建 projects/general 脚手架——S1 前是同一位置的
    // 路径级负向断言（`!os.exists(home / "projects" / "general")`）。**禁恒真**：逐级读
    // 真值（文件存在 + project.json 的 name/workspace + AGENTS.md 存在且为 **0 字节**——
    // 作者 2026-09-18 裁定 (A)：种子文本面置空，不再预填/不再带占位），播种面
    // 被摘掉即红（S5① 变异红证已实测）。
    val projectJson = home / "projects" / "general" / "project.json"
    assert(os.exists(projectJson), "project:general scaffolded")
    val proj = io.circe.parser.parse(os.read(projectJson)).toOption.get
    assert(proj.hcursor.downField("name").as[String].toOption.contains("general"))
    assert(proj.hcursor.downField("workspace").as[String].toOption.contains((home / "projects" / "general").toString),
      "workspace points at projects/general")
    assert(os.exists(home / "projects" / "general" / "AGENTS.md"), "AGENTS.md scaffolded")
    // 2026-09-18 作者裁定 (A)（promptopt W3 · L2 = 种子文本置空，取代原「含本工作区路径 /
    // <DATA_ROOT> 已替换」口径）：种子面不再预填任何内容 ⇒ 判据 = 文件**存在且 0 字节**。
    // **禁恒真**：把种子资源回填旧预填文本（非空）⇒ 本条必红（回填红轮已实测，报告 round-2 §3）。
    assert(os.size(home / "projects" / "general" / "AGENTS.md") == 0,
      "seed project AGENTS.md is empty (0 bytes = no pre-filled template content)")

    // marker
    val marker = home / ".seed-state.json"
    assert(os.exists(marker), ".seed-state.json written")
    val state = io.circe.parser.parse(os.read(marker)).toOption.get
    assert(state.hcursor.downField("version").as[String].toOption.contains("1.0.0"))
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.nonEmpty), "items recorded")
    // marker 记录 = 本轮实际写入 item 数 = **8** = 现行 manifest items 全量：3 agents
    // （project-dispatcher / general / memory-consolidator）+ 4 默认插件（visual-report /
    // slideblocks / nebflow-plugin-creator / web-search-toolkit）+ 1 project（general，
    // 作者 2026-09-17 裁定①恢复播种）。S1 前 = 7（同集去 project 条目）。
    assert(state.hcursor.downField("items").as[List[String]].toOption.exists(_.size == 8),
      "marker records 8 items (3 agents + 4 plugins + 1 project:general)")

  // ── ② 幂等 / 不覆盖用户编辑 ───────────────────────────────
  test("re-seed is idempotent and never overwrites user edits"):
    ensure()  // first seed
    // 用户编辑 general/agent.json（写一个自定义标记）
    val agentPath = home / "agents" / "general" / "agent.json"
    val custom = """{"name":"general","description":"用户改写","customMarker":true}"""
    os.write.over(agentPath, custom)
    ensure()  // re-seed
    assert(os.read(agentPath).contains("customMarker"), "user edit preserved across re-seed")

    // 项目面（本批改写的第二处 + S5② 补钉）：重播既**不重复 create**也**不静默覆盖**
    // 既有 project.json —— 用户改写的内容逐字存活。本行钉的是**项目面「无静默覆盖」不变量**
    // （三重守卫：既有 home 门 + marker 门 + seedProject 的「已存在」分支；红证 = 三处全中和
    // 的朴素播种面，见报告 S5② —— 单独变异 seedProject 分支不可达、恒绿，理由同报告）。
    val projPath = home / "projects" / "general" / "project.json"
    assert(os.exists(projPath), "project:general scaffolded on a fresh home")
    os.write.over(
      projPath,
      io.circe.parser.parse(os.read(projPath)).toOption.get
        .deepMerge(io.circe.Json.obj("customMarker" -> io.circe.Json.True)).noSpaces
    )
    ensure()
    assert(os.read(projPath).contains("customMarker"),
      "user-edited project.json kept across re-seed (no silent overwrite of an existing project definition)")

  // ── ③ fresh-home 守卫：已有用户数据 → 不完整播种（但默认集 agent + 项目自愈）──
  test("existing user data skips full seeding, add-only self-heals the default set (agents + project:general)"):
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
    // 在既有 home 也要自愈补装——否则消费链（memory-consolidator）在既有 home 永不可能
    // 就位，记忆队列只进不出。原断言「no dispatcher seeded when user data present」已按
    // 新口径改写（这是预期的判红样例：改测试，不改守卫）。
    for name <- List("project-dispatcher", "memory-consolidator")
    do assert(os.exists(home / "agents" / name / "agent.json"), s"default-set agent '$name' self-healed under the guard")

    // ── 项目面（本批新口径，作者 2026-09-17 裁定②：既有 home 亦 add-only 补种）──
    // 改前口径为「不补种既有 home」（原断言 = 路径级负向 `!os.exists(home/projects/general)`
    // + 同块注释「不建 general 项目脚手架」）；后令治前论 ⇒ 该负面陈述作废，随口径翻转为
    // 正向。判据非恒真：**改前树跑本用例必红**（无 reconcileProjects ⇒ general 不被补出），
    // 改后绿；且既有一切内容逐字节不变（下两条）。
    val genDir = home / "projects" / "general"
    assert(os.exists(genDir / "project.json"),
      "missing default project 'general' is backfilled in an existing home (add-only reconcile)")
    assert(os.exists(genDir / "AGENTS.md"), "general/AGENTS.md backfilled in an existing home")
    val genAgentsText = os.read(genDir / "AGENTS.md")
    // 同口径改写（2026-09-18 作者裁定 (A)）：原两条同源断言 = 「文本含本工作区绝对路径
    // （<DATA_ROOT> 占位已替换）」+「无未替换 <DATA_ROOT> 残留」——种子文本置空后二者在空文本上
    // **恒真**，按变异纪律（断言禁恒真）折入本条的 0 字节读数：回填旧预填文本（非空）⇒ 本条必红。
    assert(genAgentsText.isEmpty && os.size(genDir / "AGENTS.md") == 0,
      "seed project AGENTS.md is empty (0 bytes) after add-only backfill — no pre-filled template content")
    val genProj = io.circe.parser.parse(os.read(genDir / "project.json")).toOption.get
    assert(genProj.hcursor.downField("workspace").as[String].toOption.contains(genDir.toString),
      "backfilled project.json points its workspace at projects/general")

    // ③ 零覆盖：既有 `projects/myproj/project.json` 内容逐字不变（既有内容零覆盖/零搬移/零删除）
    val myprojAfter = os.read(myprojJson)
    assert(myprojAfter == myprojBefore, "existing project file byte-identical after the add-only reconcile")
    // ② `projects/` 除预期补种的 `general` 之外零新增目录
    assert(os.list(home / "projects").map(_.last).sorted == List("general", "myproj"),
      "no project directory beyond the expected backfilled 'general'")
    println(s"[DIAG-ZERO-OVERWRITE] projects/myproj/project.json sha256 before=${sha256(myprojBefore)} " +
      s"after=${sha256(myprojAfter)} byteIdentical=${myprojAfter == myprojBefore}")

    // ② 幂等：第二次 boot 对该面零写盘（mtime 逐字不变 ⇒ 无写入）
    val genProjJson = genDir / "project.json"
    val genProjMtime = os.mtime(genProjJson)
    val genAgentsMtime = os.mtime(genDir / "AGENTS.md")
    ensure()
    assert(os.mtime(genProjJson) == genProjMtime,
      "second boot leaves projects/general/project.json untouched (mtime unchanged ⇒ zero writes)")
    assert(os.mtime(genDir / "AGENTS.md") == genAgentsMtime,
      "second boot leaves projects/general/AGENTS.md untouched (mtime unchanged ⇒ zero writes)")
    assert(os.read(myprojJson) == myprojBefore, "existing project file still byte-identical after the second boot")
    println(s"[DIAG-IDEMPOTENT] projects/general/project.json mtime before=$genProjMtime " +
      s"after=${os.mtime(genProjJson)} unchanged=${os.mtime(genProjJson) == genProjMtime}")

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
    // 项目面（本批改写）：升级 add-only run 同样补齐缺失的默认集项目——本用例在 run 前
    // `os.remove.all(home / "projects")`，故此处是「缺失 ⇒ 补建」的正面断言
    // （S1 前是路径级负向断言「no project scaffold added by the upgrade run」）
    assert(os.exists(home / "projects" / "general" / "project.json"), "missing project added")
    // marker 升级到当前版本
    val marker = io.circe.parser.parse(os.read(home / ".seed-state.json")).toOption.get
    assert(marker.hcursor.downField("version").as[String].toOption.contains("1.0.0"), "marker bumped to 1.0.0")

  // ── ⑤ 项目面种子在位不变量（作者 2026-09-17 裁定①，撤销 09-16 摘除令）────
  test("built-in project seed is in place: manifest declares exactly one 'project:general' item and the seed tree ships with it"):
    // 判据 = 真值读取（classpath 上的同一份资源），**禁恒真**：条目/资源树任一消失，本测红。
    val manifestText = {
      val in = Option(getClass.getClassLoader.getResourceAsStream("seed/manifest.json")).getOrElse(
        fail("classpath resource 'seed/manifest.json' not found")
      )
      try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally in.close()
    }
    val items = io.circe.parser.parse(manifestText).toOption
      .flatMap(_.hcursor.downField("items").as[List[String]].toOption).getOrElse(fail("manifest.items unreadable"))
    val projectItems = items.filter(_.startsWith("project:"))
    assert(projectItems == List("project:general"),
      s"seed manifest must declare exactly one 'project:general' item (got ${projectItems.mkString(", ")} of " +
        s"${items.size} item(s)) — the built-in project seed was restored by the author on 2026-09-17 " +
        "(reversing the 2026-09-16 removal); dropping it again is a product decision")
    // 种子资源树在位（缺 `seed/projects/general/AGENTS.md` ⇒ seedProject 只剩 defaultAgentTemplate
    // 短模板兜底，真实项目指令文本到不了新 home）。判据与兄弟 spec 同口径 = classpath
    // （sbt test = target/classes 拷贝，assembly = jar）。
    assert(getClass.getClassLoader.getResource("seed/projects/general/AGENTS.md") != null,
      "seed project resource tree (seed/projects/general/AGENTS.md) is on the classpath")

  // ── ⑥ 零覆盖负控：既有（手工版）projects/general 逐字节不变 ──────────
  test("a pre-existing hand-made projects/general survives boot byte-identical (add-only, zero overwrite)"):
    // 本用例是「补种波及既有 home」这条新口径的**零覆盖负控**：手工建的 general 内容与
    // 种子文本**故意不同**（project.json 的 workspace 指向别处 + customMarker；AGENTS.md
    // 为用户自持文本；.gitignore 为用户自持内容）⇒ 覆盖面三文件（项目定义 / agent 指令 /
    // .gitignore）逐字节钉住。判红面 = 任何「对齐种子 / 镜像覆盖 / 按 workspace 纠正既有定义 /
    // 经 `ProjectStore.ensureScaffold` 打补丁（该路径会**追加** `.gitignore`）」的实现
    // ——本批禁：不得 seed→runtime 覆写既有 general 的任何文件。
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
