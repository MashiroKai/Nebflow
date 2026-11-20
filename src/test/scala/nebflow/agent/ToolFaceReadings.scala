package nebflow.agent

import nebflow.actor.AgentDef
import nebflow.core.tools.ToolRegistry

/**
 * Per-identity tool-face readings — the **reusable** enumerator (card-tool-revive
 * batch, 2026-11-18; author ruling: 逐身份工具面读数的枚举机制请做成可复用的一份).
 *
 * 一次装配、一份读数：对「会话形态 × 身份」的每一格，给出**两个面**的读数——
 *
 *   - `allowed`  = `AgentCore.buildAllowedToolSet` 的装配面（授权集；调用面判据）；
 *   - `llmFace`  = `AgentCore.buildToolList` 的结果（allowedSet ∩ 注册表 schema
 *                  ∩ 定义期变体）——**模型真正看得到的工具清单**。
 *
 * 两者必须分开读：名字进集但注册表无 schema ⇒ 模型看不到（例如复活前一个
 * `agent.json` 声明 `Card` 的 general agent）；只有两个面同时给，读数才可判。
 *
 * 🔴 位置说明（别的批要复用它）：本件是**唯一**一份枚举。禁在别处另写第二套
 * 身份表或用例表——需要新身份/新工具时改本件（`Identities` / `printReadings`）。
 *
 * 调用方式（两种，任选）：
 *   1. **命令行（推荐）**：`sbt -batch "testOnly nebflow.agent.ToolFaceReadingsSpec"`
 *      —— 打 `READING|<身份>|allowedHas<工具>=…|llmFaceHas<工具>=…|allowed=…|llmFace=…`
 *      行；要读别的工具名：`sbt -batch '-Dtoolface.readings=Card,Pop,ask_edit' "testOnly …"`
 *   2. **进程内调用**：`ToolFaceReadings.rows` / `ToolFaceReadings.has(label, tool)`
 *      / `ToolFaceReadings.printReadings(List("Card"))`。
 *
 * ⚠️ 为什么住 `nebflow.agent` 测试源：`AgentCore` 是 `private[agent] trait`、
 * `buildAllowedToolSet` / `buildToolList` 是 protected ⇒ 探针必须与先例
 * `AllowedToolSetSpec.CoreProbe` 同包。别把它挪进 `nebflow.core.tools`（编译不过）。
 */
object ToolFaceReadings:

  /** One 「会话形态 × 身份」格。`label` 是读数行的身份列（稳定文案，别的批引用它）。 */
  final case class Identity(
    label: String,
    defn: AgentDef,
    isFlowNode: Boolean = false,
    projectBoardSession: Boolean = false,
    isDispatcher: Boolean = false,
    delegateSession: Boolean = false,
    askSession: Boolean = false,
    delegateMode: Option[String] = None
  )

  /** 读取行：身份 → 两个面。 */
  final case class Row(label: String, allowed: Set[String], llmFace: Set[String]):
    def allowedHas(tool: String): Boolean = allowed.contains(tool)
    def llmFaceHas(tool: String): Boolean = llmFace.contains(tool)

  /** protected 装配面的探针（先例：`AllowedToolSetSpec.CoreProbe`）。 */
  private object CoreProbe extends AgentCore:

    def allowed(
      defn: AgentDef,
      isFlowNode: Boolean = false,
      projectBoardSession: Boolean = false,
      isDispatcher: Boolean = false,
      delegateSession: Boolean = false,
      askSession: Boolean = false,
      delegateMode: Option[String] = None
    ): Set[String] =
      buildAllowedToolSet(
        defn,
        depth = 0,
        isFlowNode = isFlowNode,
        isTeamLead = false,
        userFacingNode = false,
        guardrailsOn = false,
        projectBoardSession = projectBoardSession,
        flowNodeSession = false,
        isDispatcher = isDispatcher,
        delegateSession = delegateSession,
        askSession = askSession,
        delegateMode = delegateMode
      )

    def face(
      defn: AgentDef,
      isFlowNode: Boolean = false,
      projectBoardSession: Boolean = false,
      isDispatcher: Boolean = false,
      delegateSession: Boolean = false,
      askSession: Boolean = false,
      delegateMode: Option[String] = None
    ): List[String] =
      buildToolList(
        defn,
        depth = 0,
        isFlowNode = isFlowNode,
        projectBoardSession = projectBoardSession,
        isDispatcher = isDispatcher,
        delegateSession = delegateSession,
        askSession = askSession,
        delegateMode = delegateMode
      ).getOrElse(Nil).map(_.name)

  end CoreProbe

  private def mk(name: String, tools: List[String] = Nil, category: String = "standalone") =
    AgentDef(name = name, description = "", tools = tools, category = category)

  private val ModeDispatcher = nebflow.core.delegate.DualModeConfig.ModeDispatcher
  private val ModeKernel = nebflow.core.delegate.DualModeConfig.ModeKernel

  /**
   * 身份表（**唯一一份**）。覆盖：ask 会话腿 / Nebula root（含声明逃逸两形态）/
   * dream / general 通配 / standalone（显式声明逃逸）/ team / flow / 项目节点 /
   * 分发器 / subagent(SubTask worker) / delegate 两模式。
   */
  val Identities: List[Identity] = List(
    Identity("ask (Canvas Ask session leg)", mk("ask"), askSession = true),
    Identity("Nebula root (converged, decl=[])", mk("Nebula")),
    Identity("Nebula root (escape: decl=[Card])", mk("Nebula", List("Card"))),
    Identity("Nebula root (escape: decl=[*])", mk("Nebula", List("*"))),
    Identity("dream", mk("dream")),
    Identity("general (tools=[*])", mk("general", List("*"))),
    Identity("standalone (escape: decl=[Card])", mk("myagent", List("Card"))),
    Identity("team (tools=[*])", mk("backend", List("*"), category = "team")),
    Identity("flow (tools=[*])", mk("scanner", List("*"), category = "flow")),
    Identity(
      "project node (isFlowNode + projectBoardSession)",
      mk("nebflow", List("*")),
      isFlowNode = true,
      projectBoardSession = true
    ),
    Identity("dispatcher session", mk("nebflow", List("*")), isDispatcher = true),
    Identity("subagent", mk("subagent")),
    Identity(
      "delegate session (dispatcher mode)",
      mk("nebflow"),
      delegateSession = true,
      delegateMode = Some(ModeDispatcher)
    ),
    Identity("delegate session (kernel mode)", mk("nebflow"), delegateSession = true, delegateMode = Some(ModeKernel))
  )

  /** 单一读数行（身份 → 两个面）。 */
  private def rowOf(id: Identity): Row =
    Row(
      id.label,
      CoreProbe.allowed(
        id.defn,
        id.isFlowNode,
        id.projectBoardSession,
        id.isDispatcher,
        id.delegateSession,
        id.askSession,
        id.delegateMode
      ),
      CoreProbe
        .face(
          id.defn,
          id.isFlowNode,
          id.projectBoardSession,
          id.isDispatcher,
          id.delegateSession,
          id.askSession,
          id.delegateMode
        )
        .toSet
    )

  /** 全部身份的读数（顺序 = [[Identities]]）。 */
  lazy val rows: List[Row] = Identities.map(rowOf)

  /** 按身份标签取一行（标签见 [[Identities]]；未知标签抛显式异常，不静默）。 */
  def row(label: String): Row =
    rows
      .find(_.label == label)
      .getOrElse(
        throw new IllegalArgumentException(
          s"unknown identity label: '$label' — known: ${rows.map(_.label).mkString(" | ")}"
        )
      )

  /** 该身份是否两个面都带该工具（两个面分开可读见 [[Row]]）。 */
  def has(label: String, tool: String): Boolean =
    val r = row(label)
    r.allowedHas(tool) && r.llmFaceHas(tool)

  /**
   * 打机器可读读数行（`READING|…`）。`tools` = 要在行里逐条点名读数的工具名
   * （空 = 不打逐工具列，只打两个面的全清单）。默认清单 = `Card,Pop`。
   */
  def printReadings(tools: List[String] = List("Card", "Pop")): Unit =
    println(s"READING|registry|CardRegistered=${ToolRegistry.TOOL_MAP.contains("Card")}")
    tools.foreach { t =>
      println(s"READING|registry|${t}Registered=${ToolRegistry.TOOL_MAP.contains(t)}")
    }
    rows.foreach { r =>
      val cols = tools.map(t => s"allowedHas$t=${r.allowedHas(t)}|llmFaceHas$t=${r.llmFaceHas(t)}").mkString("|")
      println(
        s"READING|${r.label}|$cols|allowed=${r.allowed.toList.sorted.mkString(" ")}|llmFace=${r.llmFace.toList.sorted.mkString(" ")}"
      )
    }
    println(s"READING|ask.AllowedTools|${nebflow.core.ask.AskSessions.AllowedTools.toList.sorted.mkString(" ")}")
    println(
      s"READING|ask.AskExclusiveTools|${nebflow.core.ask.AskSessions.AskExclusiveTools.toList.sorted.mkString(" ")}"
    )
    println(
      s"READING|pluginWhitelist|${nebflow.core.plugin.PluginRegistry.BuiltinToolWhitelist.toList.sorted.mkString(" ")}"
    )
    println(s"READING|remoteable|${nebflow.core.tools.RemoteExecutor.remoteableTools.toList.sorted.mkString(" ")}")
    println(s"READING|rootExclusive|${AgentCore.RootExclusiveTools.toList.sorted.mkString(" ")}")
    println(s"READING|rootOrchestration|${AgentCore.RootOrchestrationTools.toList.sorted.mkString(" ")}")
    println(s"READING|retiredGuides|${AgentCore.RetiredToolGuides.keys.toList.sorted.mkString(" ")}")
  end printReadings

end ToolFaceReadings
