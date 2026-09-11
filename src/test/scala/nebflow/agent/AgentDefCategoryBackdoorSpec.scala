package nebflow.agent

import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite

/**
 * agentdef-tidy 批（2026-09-11）安全回归：**收敛名无视 agent.json `category`**。
 *
 * 后门形态（批前结构开放，今天无人走）：给任一 keeper（全局层
 * `agents/<name>/agent.json`，name ∈ [[AgentCore.ConvergedAgentNames]]）写
 * `"category":"team"` 后，per-turn 重载路径（ContextRefresher.loadCurrentDef →
 * AgentLibrary.get → loadFromDir）会把它带进两处消费者：
 *   ① 工具面 —— AgentCore.fixedToolsFor 首分支 `case "team" | "flow" =>
 *      legacyFixedTools` ⇒ Mail / SubTask / TeamTask 三件；
 *   ② 身份段 —— PromptContext.agentCategory（AgentCore.scala:730）⇒
 *      PromptSections order-395 段的 `agentCategory == "team"` 分支
 *      ⇒ team 成员身份块。
 *
 * 本 spec 对**两处消费者**各自断言，并另加非收敛面逐字节 parity 对照
 * （team / flow / legacy standalone 的推断、工具面、身份段零变化）。
 *
 * 锚点均为符号名（行号会随 main 前移漂移）：`AgentLibrary.loadFromDir`、
 * `AgentCore.fixedToolsFor` / `.legacyFixedTools` / `.ConvergedAgentNames`、
 * `PromptSections.all`（order 395）。
 */
class AgentDefCategoryBackdoorSpec extends CatsEffectSuite:

  /** 收敛名 → 机制固定集（**只引 AgentCore 常量**，零裸清单 / 零裸数字）。 */
  private val MechanismFixed: Map[String, Set[String]] = Map(
    "Nebula" -> AgentCore.NebulaOrchestrationTools,
    "project-dispatcher" -> AgentCore.DispatcherFixedTools,
    "general" -> AgentCore.GeneralFixedTools,
    "kernel" -> AgentCore.KernelFixedTools,
    // 2026-09-12 记忆改造批：记忆整理 agent（压缩双轨第二轨）——与内核同集合恰七件
    AgentCore.MemoryConsolidatorName -> AgentCore.KernelFixedTools
  )

  /** legacyFixedTools 的 category=team 分支产物（逐字抄自实现：BaseTools + Mail
    *  + SubTask ++ TeamTaskTools）——用常量拼装，不写裸清单。 */
  private val LegacyTeamFace: Set[String] = AgentCore.BaseTools + "Mail" + "SubTask" ++ AgentCore.TeamTaskTools

  /** team 面**独有**件（相对 BaseTools 的增量：Mail / SubTask / TeamTask 三件）。
    * 收敛名的机制固定集与 BaseTools 有正当交集（Read/Glob/Grep 等），故「遗留件
    * 零出现」只能按 team 面独有件判定，不能拿整集求交。 */
  private val LegacyTeamOnlyTools: Set[String] = Set("Mail", "SubTask") ++ AgentCore.TeamTaskTools

  private def fixture(name: String, json: String): os.Path =
    val dir = os.temp.dir(prefix = "agentdef-cat-spec-") / name
    os.makeDir.all(dir)
    os.write.over(dir / "agent.json", json)
    dir

  private def loadFromDisk(name: String, json: String): AgentDef =
    val dir = fixture(name, json)
    try new AgentLibrary(dir / os.up, None).loadFromDir(dir).getOrElse(
      fail(s"fixture for $name failed to load")
    )
    finally os.remove.all(dir / os.up)

  /** 收敛名 keeper：显式声明 JSON category 的后门形态。 */
  private def keeper(name: String, category: String): AgentDef =
    loadFromDisk(name, s"""{"name":"$name","description":"fixture","category":"$category","tools":[]}""")

  /** order-395 段（身份段载体）在 buildConditionalBlocks 同款 filter+render 下的产物。
    * 条件 `guardrailsOn && !isSubTaskWorker`（:281）；渲染体内层 `agentCategory == "team"`（:284）。 */
  private def identitySectionOutput(defn: AgentDef, guardrailsOn: Boolean = true, isTeamLead: Boolean = false): String =
    val ctx = PromptSections.PromptContext(
      agentCategory = defn.category,
      agentName = defn.name,
      guardrailsOn = guardrailsOn,
      isTeamLead = isTeamLead
    )
    PromptSections.all
      .filter(_.order == 395)
      .map(s => if s.shouldInclude(ctx) then s.render(ctx) else "")
      .mkString

  // ===== ① 收敛名 × JSON category 后门：工具面 =====

  test("收敛名 keeper 的 JSON category=team 被无视 —— fixedToolsFor 恒为机制固定集（零 Mail/SubTask/TeamTask*）"):
    AgentCore.ConvergedAgentNames.toList.sorted.foreach { name =>
      val defn = keeper(name, "team")
      assertEquals(defn.category, "standalone", s"$name: 收敛名 category 必须恒 standalone（无视 JSON）")
      val expected = MechanismFixed.getOrElse(name, fail(s"$name 无机制固定集常量登记"))
      val fixed = AgentCore.fixedToolsFor(defn)
      assertEquals(fixed, expected, s"$name: 工具面必须等于机制固定集常量（不得走 legacyFixedTools）")
      assertEquals(fixed.intersect(LegacyTeamOnlyTools), Set.empty[String], s"$name: team 面独有件必须零出现")
    }

  test("收敛名 keeper 的 JSON category=flow 同样被无视（同一后门第二形态）"):
    AgentCore.ConvergedAgentNames.toList.sorted.foreach { name =>
      val defn = keeper(name, "flow")
      assertEquals(defn.category, "standalone", s"$name: flow category 也必须被无视")
      assertEquals(AgentCore.fixedToolsFor(defn), MechanismFixed(name), s"$name: 工具面恒为机制固定集")
    }

  test("收敛名 keeper 的 JSON category 为未知值 / 显式 standalone 时同样收敛"):
    List("standalone", "something-unknown").foreach { cat =>
      val defn = keeper("Nebula", cat)
      assertEquals(defn.category, "standalone", s"category=$cat 时收敛名须恒 standalone")
      assertEquals(AgentCore.fixedToolsFor(defn), MechanismFixed("Nebula"))
    }

  // ===== ② 收敛名 × JSON category 后门：身份段 =====

  test("收敛名 keeper 的 JSON category=team 不再触发 team 成员身份段（order 395 渲染为空）"):
    AgentCore.ConvergedAgentNames.toList.sorted.foreach { name =>
      val defn = keeper(name, "team")
      assertEquals(identitySectionOutput(defn), "", s"$name: team 成员身份段不得渲染")
      // 同一渲染器在真 team 成员上必须照常产出（证明断言有载力，不是「渲染器恒空」）
      val teamMember = AgentDef(name = "SomeMember", description = "", category = "team")
      assert(identitySectionOutput(teamMember).nonEmpty, "真 team 成员的 order-395 段必须非空（对照）")
    }

  // ===== ③ 非收敛面逐字节 parity =====

  test("非收敛 agent 的 category=team 逐字节 parity（legacyFixedTools 分支 + 身份段仍渲染）"):
    val defn = keeper("LegacyTeamThing", "team")
    assertEquals(defn.category, "team", "非收敛 agent 的 JSON category 必须原样保留")
    assertEquals(AgentCore.fixedToolsFor(defn), LegacyTeamFace, "非收敛 team agent 仍走 legacyFixedTools(team)")
    assert(identitySectionOutput(defn).nonEmpty, "非收敛 team agent 的 team 成员身份段必须仍渲染")

  test("非收敛 agent 的 category=flow 逐字节 parity（BaseTools + 无 Mail）"):
    val defn = keeper("LegacyFlowThing", "flow")
    assertEquals(defn.category, "flow")
    assertEquals(AgentCore.fixedToolsFor(defn), AgentCore.BaseTools, "非收敛 flow agent 仍走 legacyFixedTools(flow)")

  test("非收敛 standalone parity：无 category 键 → 默认 standalone；显式 standalone 同值"):
    val absent = loadFromDisk("LegacyPlain", """{"name":"LegacyPlain","description":"fixture"}""")
    assertEquals(absent.category, "standalone", "缺 category 键 → getOrElse(\"standalone\") 不变")
    assertEquals(AgentCore.fixedToolsFor(absent), AgentCore.BaseTools, "legacy standalone 仍走 BaseTools catch-all")
    val explicit = keeper("LegacyPlain2", "standalone")
    assertEquals(explicit.category, "standalone")
    assertEquals(AgentCore.fixedToolsFor(explicit), AgentCore.BaseTools)

  test("JSON category 解析能力保留：非收敛名读到 JSON 值（含未知值原样透传，不做白名单收窄）"):
    val defn = keeper("LegacyWeird", "some-future-category")
    assertEquals(defn.category, "some-future-category", "解析能力不得因本批收窄（EntityTypes/EntityLoader/面板回显面保持）")
    assertEquals(AgentCore.fixedToolsFor(defn), AgentCore.BaseTools, "未知 category → legacyFixedTools catch-all 不变")

  // ===== ④ 观测面形变（记录，非行为断言）=====

  test("解码默认值不变：agent.json 缺 tools 键 → AgentLibrary 默认 List(\"*\")（删键后的观测面形变来源）"):
    val defn = loadFromDisk("LegacyPlain3", """{"name":"LegacyPlain3","description":"fixture"}""")
    assertEquals(defn.tools, List("*"), "tools 键缺席走 AgentJson 解码默认 List(\"*\")")

  // ===== ⑤ 收敛集与 name 分支固定集映射同集合守卫 =====

  test("ConvergedAgentNames 与机制固定集常量登记必须同集合（防新增收敛名漏补分支）"):
    assertEquals(
      AgentCore.ConvergedAgentNames,
      MechanismFixed.keySet,
      "新增收敛名时必须同步登记机制固定集常量（fixedToolsFor 的 name 分支 + 本 spec 的 MechanismFixed）"
    )
    AgentCore.ConvergedAgentNames.toList.sorted.foreach { name =>
      val asStandalone = AgentDef(name = name, description = "", category = "standalone")
      val asTeam = AgentDef(name = name, description = "", category = "team")
      assertEquals(
        AgentCore.fixedToolsFor(asStandalone),
        AgentCore.fixedToolsFor(asTeam),
        s"$name: 固定面必须与 category 无关（收敛名 category 短路）"
      )
      assertEquals(AgentCore.fixedToolsFor(asStandalone), MechanismFixed(name))
    }

end AgentDefCategoryBackdoorSpec
