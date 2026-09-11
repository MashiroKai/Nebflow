package nebflow.agent

import io.circe.{Json, parser}
import nebflow.core.PathUtil

/**
 * Conditional system prompt sections.
 *
 * Each section is a self-describing block that knows its own inclusion condition
 * and sort order. All conditional content in the system prompt — tool guides,
 * feature-flag instructions, environment info, device/session/language blocks —
 * lives here as a registered section. The caller builds a [[PromptContext]] from
 * runtime state, then calls [[buildConditionalBlocks]] to get the concatenated
 * text. Adding a new section is purely additive: drop it into `all` with an
 * `order` value and a `condition` — no changes to `buildSystemPrompt` needed.
 *
 * == Adding a new conditional section ==
 *
 * 1. If the condition depends on a new field, add it to [[PromptContext]]
 * 2. Add the section to [[PromptSections.all]] with an appropriate order value
 * 3. That's it — `buildSystemPrompt` picks it up automatically
 *
 * == Order ranges ==
 *
 *   100-199  — fixed foundational sections (env info)
 *   350      — 文档溯源规范（唯一权威面，always）
 *   400-499  — tool-dependent sections（阶段 2d §D.2：条件工具指南段已全部
 *              下迁进工具 description——AskUserQuestion/Read/Pop/TeamTask 三件，
 *              提示词层不再按「是否有该工具」注入用法段落）
 *   500-599  — feature-flag sections (voice)
 *   600-699  — runtime-state sections (devices, sessions, language, mounted projects)
 *   800-899  — catalog sections (skills)
 *   900+     — project rules and fallback
 */
object PromptSections:

  // ============================================================
  // PromptContext — runtime state that determines which sections
  // are included and provides data for dynamic content.
  //
  // Lightweight flags and pre-rendered strings. "Heavy" dynamic content
  // (device list, agent sessions, env info) is rendered by the caller
  // *before* constructing the context — sections just read from it.
  // ============================================================

  case class PromptContext(
    availableTools: Set[String] = Set.empty,
    depth: Int = 0,
    voiceEnabled: Boolean = true,
    hasDevices: Boolean = false,
    hasActiveSessions: Boolean = false,
    language: Option[String] = None,
    chatWidth: Int = 0,
    /** Agent category ("standalone", "team", "flow"). */
    agentCategory: String = "standalone",
    /** Agent name (e.g. "Nebula", "Manager", "Backend"). */
    agentName: String = "",
    /** Pre-rendered environment info table (legacy — kept for test compatibility). */
    envInfo: String = "",
    /** Pre-rendered device info block (from AgentCore.deviceInfoBlock). */
    deviceInfo: String = "",
    /** Pre-rendered skill catalog (per-agent, from SkillService.buildPerAgentCatalog). */
    skillCatalog: String = "",
    /** Pre-rendered flow catalog (per-agent, from SkillService.buildPerAgentFlowCatalog). */
    flowCatalog: String = "",
    /** Pre-rendered team catalog (from TeamCatalog.buildCatalog). */
    teamCatalog: String = "",
    /** Pre-rendered memory block (from ContextRefresher.buildMemoryBlock). */
    memoryBlock: String = "",
    /** Pre-rendered active-sessions block (from formatAgentSessions). */
    agentSessionsText: String = "",
    /** Pre-rendered task list block (from TaskStore.renderForPrompt). */
    taskListText: String = "",
    /** Inherited project rules text (from folder chain). */
    rulesMd: Option[String] = None,
    /** Workspace-root AGENTS.md (§E.2; project dispatcher + node sessions only,
      * resolved per turn in ContextRefresher.resolveAgentsMd). */
    agentsMd: Option[String] = None,
    /** True when this agent is a SubTask worker (leaf execution pipeline). */
    isSubTaskWorker: Boolean = false,
    /** 轨道二 #5: dedicatedAgents guardrails flag (hot-read per turn). */
    guardrailsOn: Boolean = false,
    /** 轨道二 #5: true when this agent is a flow DAG node (#406). */
    isFlowNode: Boolean = false,
    /** 轨道二 #5: this flow node's userFacing whitelist declaration. */
    userFacingNode: Boolean = false,
    /** Whether this session's agent is the team lead (Manager). */
    isTeamLead: Boolean = false,
    /** Whether this agent is the root Nebula agent (Progressive disclosure:
      * mounted-project list is Nebula-only — dispatcher/node sessions must not
      * see it, they already carry AGENTS.md / project memory). */
    isRootAgent: Boolean = false,
    /** Pre-rendered mounted-project list body (sorted bullet lines, or the
      * "当前无挂载项目" placeholder). From AgentCore via ProjectRuntimeRegistry. */
    mountedProjectsText: String = ""
  )

  object PromptContext:
    val empty: PromptContext = PromptContext()

  // ============================================================
  // PromptSection — a self-describing conditional prompt block
  // ============================================================

  /**
   * A single section of the system prompt that knows its own inclusion
   * condition and ordering.
   *
   * Static sections override [[content]]. Dynamic sections (whose text
   * depends on runtime data) override [[render]].
   */
  trait PromptSection:
    /** Sort order in the final prompt (smaller = earlier). */
    def order: Int

    /** Whether this section should be included given the current context. */
    def shouldInclude(ctx: PromptContext): Boolean

    /** Static content. Override [[render]] for dynamic content. */
    def content: String = ""

    /** Render the section text, possibly using runtime context. */
    def render(ctx: PromptContext): String = content

  object PromptSection:

    /** Create a static conditional section. */
    def apply(
      ord: Int,
      condition: PromptContext => Boolean,
      body: String
    ): PromptSection = new PromptSection:
      val order = ord
      def shouldInclude(ctx: PromptContext) = condition(ctx)
      override val content = body

    /** Create a section that is always included. */
    def fixed(ord: Int, body: String): PromptSection =
      apply(ord, _ => true, body)

    /** Create a dynamic section whose text depends on runtime context. */
    def dynamic(
      ord: Int,
      condition: PromptContext => Boolean,
      renderer: PromptContext => String
    ): PromptSection = new PromptSection:
      val order = ord
      def shouldInclude(ctx: PromptContext) = condition(ctx)
      override def render(ctx: PromptContext) = renderer(ctx)

  end PromptSection

  /** Quick helper: condition that requires one or more tools to be available. */
  def requiresTools(names: String*): PromptContext => Boolean =
    ctx => names.forall(ctx.availableTools.contains)

  // ============================================================
  // Built-in section text constants
  //
  // These are core product sections that must be available in every
  // environment (including CI). Users can OVERRIDE them by placing a
  // .md file with the same Order value in ~/.nebflow/prompts/sections/.
  //
  // 阶段 2d（§D.2）：工具用法类常量（askUserSection / readLiveSection /
  // visualReportingSection / tasksGuideSection / workerBlock）已删除——内容
  // 下迁进对应工具 description（自包含原则：功能+用法+反模式全部进工具定义）。
  // ============================================================

  // 轨道二 #5 identity clauses（设计基线 20260827_dedicated-agents-taxonomy-design.md §B2/§B3）。
  // 注入条件由 order-395 动态 section 控制；文本固定一段，避免每份定义手写漂移。

  /** T1 flow worker 条款。userFacing=false：受众=编排器与下游节点（严格版）。 */
  def flowWorkerIdentityBlock(userFacing: Boolean): String =
    if userFacing then
      """## 身份与受众（不可协商）
        |
        |- 你是流水线节点（用户终审环节）。你的产出两头都要喂：编排器与下游
        |  节点通过 $x.output 与 slots 字段机器消费；终端用户只阅读你标为交付的部分。
        |- 中间产物一律 Write 落盘并在 FlowReport 给出绝对路径；「写文件给下游」
        |  永远优于「渲染给人看」。
        |- 需求歧义时不要等待提问：在 outputs 中标注 assumption 字段并继续，
        |  由编排器路由裁决。
        |- 最终轮输出 ≤ 800 tokens：结论与交付说明为主，不堆背景叙述。
        |""".stripMargin
    else
      """## 身份与受众（不可协商）
        |
        |- 你是流水线节点。你的受众是编排器与下游节点——它们通过 $x.output 与
        |  slots 字段机器消费你的产出；终端用户不直接阅读你的任何文本。
        |- 禁止面向用户的展示类动作：不调用 Pop，不制作「给人看」的可视化包装页、
        |  汇总美化稿。证据用截图落盘文件 + 路径引用代替。
        |- 中间产物一律 Write 落盘并在 FlowReport 给出绝对路径；「写文件给下游」
        |  永远优于「渲染给人看」。
        |- 需求歧义时不要等待提问：你没有对话对象——在 outputs 中标注 assumption
        |  字段并继续，由编排器路由裁决。
        |- 最终轮输出 ≤ 500 tokens：只写结论、状态与下游模板需要的字段，
        |  不写背景叙述、不写给用户看的总结语。
        |""".stripMargin

  /** T2 team member 条款变体：通道 Mail、[RESULT] 收口、[ASSUMPTION] 行。 */
  val teamMemberIdentityBlock: String =
    """## 身份与受众（不可协商）
      |
      |- 你是团队成员。你的受众是 Team Lead——它汇总你的 [RESULT] 后才会传递
      |  给终端用户；终端用户不直接阅读你的任何文本。你不与用户对话，
      |  用户通过 Lead 与你交互。
      |- 默认禁用面向用户的展示类动作：证据用截图落盘文件 + 路径引用代替；
      |  仅当成员定义里显式声明并注明触发条件时才允许 Pop 类工具。
      |- 交付以文件为准：中间产物一律 Write 落盘，Mail 报告给出绝对路径。
      |- 需求歧义时在 Mail 里写显式 [ASSUMPTION] 行并继续，由 Lead 裁决或升级。
      |- Mail 回 Lead 的 [RESULT] ≤ 300 tokens：只写状态、关键产物路径与下一步建议。
      |""".stripMargin

  /** Injected after the agent prompt when voice output is enabled. */
  val voiceSection: String =
    """## Voice Output
      |
      |Think of yourself as a teacher giving a lecture. Your markdown, code, and cards are the **blackboard** — they show structure, details, and reference material. Your voice is the **narration** — it explains what's on the board, why it matters, and how the pieces connect.
      |
      |Wrap spoken text in `<voice></voice>` tags. The content will be played as audio, stripped from the visual display, and shown as a clickable replay link.
      |
      |**Use voice proactively — it is your primary communication channel, not an afterthought:**
      |- When presenting results, conclusions, or analysis after completing work
      |- When explaining a concept, reasoning, or trade-off
      |- When introducing what the user is about to see — set the stage before showing details
      |- When summarizing findings from investigation or research
      |- When walking through a decision or recommendation
      |- Greetings, check-ins, and task completion overviews
      |- Warnings about problems, or asking for the user's decision
      |
      |**Voice and board are complementary — never duplicate:**
      |- The board holds the details: code, tables, diagrams, step-by-step lists.
      |- Voice holds the narrative: what this means, why it matters, what to focus on.
      |- Do NOT read your markdown aloud. Say something different and complementary.
      |
      |**Rules:**
      |- Voice can be several sentences to a full paragraph. Match the depth of what you're explaining.
      |- Never include code, file paths, tool outputs, or technical identifiers in voice tags — those belong on the board.
      |- Multiple `<voice>` blocks in one response are encouraged — narrate section by section, placing voice before and after key content blocks.
      |- Only your visible output is spoken; your internal thinking is not affected.
      |
      |**Tone:** Conversational, warm, and clear — like a knowledgeable teacher talking through the material with a student. You care about the user beyond tasks: check in on their wellbeing, notice when they seem stressed, and be genuinely supportive.""".stripMargin

  // ============================================================
  // 文档溯源规范（order 350，always）
  //
  // 作者 2026-09-11 裁定：「直接加到系统提示词里。作为规范。记住要简要，
  // 保证提示词精简」；落地方案见 #165。系统提示词是唯一权威面——运行时
  // （~/.nebflow/**）的本地重述由并行节点删除，存量文档
  // （~/.nebflow/docs/CONVENTIONS.md）按作者裁定冻结不改。
  // 文本独立成常量，便于 spec 引用与后续修订。
  // ============================================================

  val traceSection: String =
    """## 文档溯源
      |
      |- 溯源只进文件名尾：阶段文档 `<YYYYMMDD>_<HHMMSS>_<topic>__<chainId>.md`（无归属不带尾段）；正文零元数据头。""".stripMargin

  // ============================================================
  // Dynamic section registry
  // ============================================================

  private val dynamicSections: List[PromptSection] = List(
    // --- 文档溯源规范（#165，作者 2026-09-11 裁定）：always 注入、无动态
    // 字段、静态体，故落在稳定前缀（systemStable）内——env(100) 之后、
    // 首个 dynamic 段 395 之前。运行时 9 处本地重述删除由并行节点负责。
    PromptSection(
      350,
      condition = _ => true,
      body = traceSection
    ),

    // --- 轨道二 #5 identity clauses (before tool guides — who reads your
    // output comes first). Only when dedicatedAgents guardrails are enabled:
    // T1 flow nodes get the strict machine-consumer clause (or its
    // userFacing dual-audience variant); T2 team members (non-lead,
    // non-fork) get the Mail-report variant. SubTask workers keep their own
    // Worker Identity Block (order 999).
    PromptSection.dynamic(
      395,
      condition = ctx => ctx.guardrailsOn && !ctx.isSubTaskWorker,
      renderer = ctx =>
        if ctx.isFlowNode then flowWorkerIdentityBlock(ctx.userFacingNode)
        else if !ctx.isTeamLead && ctx.agentCategory == "team" then teamMemberIdentityBlock
        else ""
    ),

    // --- 阶段 2d（§D.1-14/§D.2）：原 order 400/410/415 条件工具指南段已删除
    // ——内容下迁进 AskUserQuestion / Read / Pop 工具 description（自包含）。
    // 判据：从未见过旧段的 agent 行为不退化——工具定义随工具本身注入，
    // 有工具必有用法指南。

    // --- Feature-flag sections ---
    PromptSection(
      500,
      condition = _.voiceEnabled,
      body = voiceSection
    ),

    // --- Runtime-state sections ---
    PromptSection.dynamic(
      600,
      condition = _.hasDevices,
      renderer = ctx => s"# Devices\n\n${ctx.deviceInfo}"
    ),
    PromptSection.dynamic(
      610,
      condition = _.hasActiveSessions,
      renderer = _.agentSessionsText
    ),
    PromptSection.dynamic(
      620,
      condition = _.language.isDefined,
      renderer = ctx => languageBlock(ctx.language.get)
    ),
    // --- Mounted projects (progressive disclosure, 2026-09-07) ---
    // 根 Nebula 会话可见当前挂载项目清单（project.json name/description）。
    // 分发器/节点会话不注入（isRootAgent=false）——它们已有 AGENTS.md / 项目
    // memory。mountedProjectsText 对根代理恒非空（无项目时渲染为占位
    // 「当前无挂载项目」），空清单也显式呈现。mid-session 挂载/卸载/新建由
    // SystemReminders.projectsReminder 增量通报（不改 systemStable 缓存）。
    PromptSection.dynamic(
      630,
      condition = ctx => ctx.isRootAgent && ctx.mountedProjectsText.nonEmpty,
      renderer = ctx => s"# Mounted Projects\n\n${ctx.mountedProjectsText}"
    ),
    // --- 阶段 2d（§D.1-14/§D.2）：原 order 630 Task List Protocol 段已删除
    // ——内容下迁进 TeamTask 三件 description（双轨期语义，阶段 3 随 team
    // 退役一并拆除）。team 成员人手三件，协议文本随工具定义必达。
    // 注：630 槽位现被上方的 Mounted Projects 段复用（原协议段已不存在）。

    // --- Catalog sections ---
    PromptSection.dynamic(
      800,
      condition = _.skillCatalog.nonEmpty,
      renderer = _.skillCatalog
    ),
    PromptSection.dynamic(
      808,
      condition = _.flowCatalog.nonEmpty,
      renderer = _.flowCatalog
    ),
    PromptSection.dynamic(
      816,
      condition = _.teamCatalog.nonEmpty,
      renderer = _.teamCatalog
    ),
    PromptSection.dynamic(
      810,
      condition = _.memoryBlock.nonEmpty,
      renderer = _.memoryBlock
    ),

    // --- Project instructions (§E.2, order 895) ---
    // AGENTS.md 是工作指令、NEBFLOW.md/rules.md 是平台规则——工作指令在前，
    // 规则优先级更高故靠后（order 900）。内容每 turn 由 ContextRefresher 重读盘。
    PromptSection.dynamic(
      895,
      condition = _.agentsMd.isDefined,
      renderer = ctx => s"# Project Instructions (AGENTS.md)\n\n${ctx.agentsMd.get}"
    ),

    // --- Project rules ---
    PromptSection.dynamic(
      900,
      condition = _.rulesMd.isDefined,
      renderer = ctx => s"## Project Rules\n\n${ctx.rulesMd.get}"
    )
    // --- 阶段 2d（§D.1-14/§D.2）：原 order 999 Worker Identity Block 注入已
    // 删除——worker 身份由 general 模版 §C.4 第 4 节（无团队上下文）取代；
    // 新模型 node 会话自带该节，legacy SubTask worker 的硬边界仍由
    // buildAllowedToolSet 的 isSubTaskWorker 机制剥离保证（提示词段仅余冗余）。
  )

  // ============================================================
  // File-based static sections (~/.nebflow/prompts/sections/*.md)
  // ============================================================

  private var cachedSections: (Long, List[PromptSection]) = (0L, Nil)

  /**
   * Load static sections from files, with mtime-based caching.
   * Files are re-read only when the directory's max mtime changes.
   */
  private def loadFileSectionsCached(): List[PromptSection] =
    val sectionsDir = PathUtil.dataRoot / "prompts" / "sections"
    val currentMtime =
      if os.exists(sectionsDir) then os.list(sectionsDir).map(p => os.mtime(p)).maxOption.getOrElse(0L)
      else 0L
    if currentMtime == cachedSections._1 then cachedSections._2
    else
      val fresh = loadFileSections()
      cachedSections = (currentMtime, fresh)
      fresh

  /** Parse all sections from the sections directory: .md files + subdirectories. */
  private def loadFileSections(): List[PromptSection] =
    val sectionsDir = PathUtil.dataRoot / "prompts" / "sections"
    if !os.exists(sectionsDir) then Nil
    else
      val entries = os.list(sectionsDir).toList
      // Plain .md files
      val mdSections = entries
        .filter(f => f.last.endsWith(".md") && os.isFile(f))
        .flatMap { file =>
          val raw = os.read(file)
          val order = parseOrder(raw).getOrElse(999)
          val condition = parseCondition(raw)
          val body = raw.linesIterator.filterNot(l => l.trim.startsWith("<!--")).mkString("\n").trim
          if body.nonEmpty then Some(PromptSection(order, condition, body)) else None
        }
      // Subdirectories with condition.json + prompt.md [+ data.sh]
      val dirSections = entries
        .filter(d => os.isDir(d))
        .flatMap { dir => loadDirSection(dir).toList }
      mdSections ++ dirSections

    end if

  end loadFileSections

  /** Parse the Condition comment to build a context predicate. */
  private def parseCondition(content: String): PromptContext => Boolean =
    val conditionLine = content.linesIterator.find(_.trim.startsWith("<!-- Condition:")).getOrElse("")
    val cond = conditionLine.replaceAll(".*<!-- Condition:", "").replaceAll("-->.*", "").trim
    cond match
      case s if s.startsWith("agent has") =>
        val toolName = s.replace("agent has", "").replace("tool", "").trim
        requiresTools(toolName)
      case "voiceEnabled is true" => _.voiceEnabled
      case "always" => _ => true
      case _ => _ => true

  /** Parse the Order comment to get the sort order integer. */
  private def parseOrder(content: String): Option[Int] =
    content.linesIterator
      .find(_.trim.startsWith("<!-- Order:"))
      .flatMap(_.replaceAll(".*<!-- Order:", "").replaceAll("-->.*", "").trim.toIntOption)

  /**
   * Load a section from a subdirectory containing condition.json + prompt.md [+ data.sh].
   * The data.sh script (if present) is executed at render time, its JSON output
   * is used to replace {{variables}} in the prompt template.
   */
  private def loadDirSection(dir: os.Path): Option[PromptSection] =
    val conditionFile = dir / "condition.json"
    val promptFile = dir / "prompt.md"
    val dataScript = dir / "data.sh"

    if !os.exists(conditionFile) || !os.exists(promptFile) then None
    else
      val conditionJson = parser.parse(os.read(conditionFile)).toOption.getOrElse(Json.Null)
      val order = conditionJson.hcursor.downField("order").as[Int].getOrElse(999)
      val condition = parseJsonCondition(conditionJson)
      val template = os.read(promptFile).trim

      if os.exists(dataScript) then
        Some(PromptSection.dynamic(order, condition, ctx => renderWithScript(template, dataScript, ctx)))
      else Some(PromptSection(order, condition, template))

  end loadDirSection

  /** Parse a JSON condition field into a context predicate. */
  private def parseJsonCondition(json: Json): PromptContext => Boolean =
    val condVal = json.hcursor.downField("condition").focus.getOrElse(Json.fromString("always"))
    parseConditionValue(condVal)

  /** Parse a condition value: either a string ("always") or an object ({tool/flag/and/or/...}). */
  private def parseConditionValue(cond: Json): PromptContext => Boolean =
    cond.asString match
      case Some(s) =>
        parseConditionObject(Json.obj("x" -> Json.fromString(s)).hcursor.downField("x").focus.getOrElse(Json.Null))
      case None => parseConditionObject(cond)

  /** Parse a condition object with and/or/not/tool/flag/category/name operators. */
  private def parseConditionObject(cond: Json): PromptContext => Boolean =
    import io.circe.JsonObject
    val obj = cond.asObject.getOrElse(JsonObject.empty)
    obj("and")
      .map { arr =>
        val subs = arr.asArray.getOrElse(Nil).map(parseConditionValue)
        (ctx: PromptContext) => subs.forall(_(ctx))
      }
      .orElse(
        obj("or").map { arr =>
          val subs = arr.asArray.getOrElse(Nil).map(parseConditionValue)
          (ctx: PromptContext) => subs.exists(_(ctx))
        }
      )
      .orElse(
        obj("not").map { inner =>
          val sub = parseConditionValue(inner)
          (ctx: PromptContext) => !sub(ctx)
        }
      )
      .orElse(
        obj("tool").map { v =>
          val toolName = v.asString.getOrElse("")
          (ctx: PromptContext) => ctx.availableTools.contains(toolName)
        }
      )
      .orElse(
        obj("flag").map { v =>
          val flagName = v.asString.getOrElse("")
          (ctx: PromptContext) =>
            flagName match
              case "voiceEnabled" => ctx.voiceEnabled
              case "hasDevices" => ctx.hasDevices
              case "hasActiveSessions" => ctx.hasActiveSessions
              case _ => false
        }
      )
      .orElse(
        obj("category").map { v =>
          val cat = v.asString.getOrElse("")
          (ctx: PromptContext) => ctx.agentCategory == cat
        }
      )
      .orElse(
        obj("name").map { v =>
          val name = v.asString.getOrElse("")
          (ctx: PromptContext) => ctx.agentName == name
        }
      )
      .getOrElse(_ => true)

  end parseConditionObject

  /**
   * Execute data.sh and use its JSON output to replace {{variables}} in the template.
   * Falls back to the raw template if the script fails or output is invalid JSON.
   */
  private def renderWithScript(template: String, script: os.Path, ctx: PromptContext): String =
    val envVars = Map(
      "CHAT_WIDTH" -> ctx.chatWidth.toString,
      "NEBFLOW_VERSION" -> nebflow.Version.string,
      "NEBFLOW_PID" -> sys.props.getOrElse("nebflow.gateway.pid", java.lang.ProcessHandle.current().pid().toString),
      "NEBFLOW_GATEWAY_PORT" -> sys.props.getOrElse("nebflow.gateway.port", "8080")
    )
    val result =
      try
        os.proc("bash", script.toString)
          .call(cwd = os.pwd, env = envVars, check = false)
          .out
          .text()
          .trim
      catch case _: Exception => return template
    parser.parse(result).toOption match
      case Some(json) =>
        json.asObject
          .map(_.toMap)
          .getOrElse(Map.empty)
          .foldLeft(template) { case (t, (key, value)) =>
            val strValue = value.asString.getOrElse(value.noSpaces)
            t.replace(s"{{$key}}", strValue)
          }
      case None => template

  end renderWithScript

  /**
   * All sections: built-in (code-defined) + file-based (user-editable).
   * When a file section has the same Order as a built-in, the file version
   * overrides the built-in — this lets users customise built-in sections
   * without creating duplicates.
   */
  def all: List[PromptSection] =
    val fileSections = loadFileSectionsCached()
    val fileOrders = fileSections.map(_.order).toSet
    dynamicSections.filterNot(s => fileOrders.contains(s.order)) ++ fileSections

  /**
   * Render just the file-based Environment section (order < 200) — cache
   * optimization v2 change detection. The env block stays in systemStable
   * (initial injection + lifecycle-node rebuild); mid-session changes (chat
   * width, PID, ...) are reported via a system reminder instead of rebuilding
   * the whole system prompt. Order < 200 captures the environment directory
   * (order 100) and nothing else (tool guides are 400+, voice 500).
   */
  def envInfoSection(ctx: PromptContext): String =
    loadFileSectionsCached()
      .filter(s => s.order < 200 && s.shouldInclude(ctx))
      .map(_.render(ctx))
      .filter(_.nonEmpty)
      .mkString("\n\n")

  /** Render the language instruction block for the given language. */
  private def languageBlock(lang: String): String =
    s"# Language\n" +
      s"- Respond in $lang.\n" +
      s"- When creating tasks (TeamTaskCreate), the `subject` and `activeForm` fields MUST be in $lang.\n" +
      s"- When writing to memory files (Agent/Session/User memory), all content MUST be in $lang.\n" +
      s"- All user-visible text must be in $lang."

  /**
   * Build the conditional blocks string from the registry.
   * Filters by shouldInclude, sorts by order, renders each section,
   * and joins with double newlines.
   */
  def buildConditionalBlocks(ctx: PromptContext): String =
    all
      .filter(_.shouldInclude(ctx))
      .sortBy(_.order)
      .map(_.render(ctx))
      .filter(_.nonEmpty)
      .mkString("\n\n")

  /**
   * Assemble the final system prompt: the agent system.md FIRST, then
   * conditional blocks.
   *
   * The leading stable segment is the provider prefix-cache anchor — the
   * shared system.md base (identical across sessions of the same template)
   * must stay at the very front or the common prefix is lost and every
   * session's prompt cache is invalidated. Phase-2 batch A (2026-09) retired
   * the shared system-prefix layer; the stable system.md base now serves
   * that role. Pinned by PromptSectionsSpec.
   */
  def assembleSystemPrompt(agentPrompt: String, conditionalBlocks: String): String =
    val separator = if conditionalBlocks.nonEmpty then "\n\n" else ""
    s"$agentPrompt$separator$conditionalBlocks"

  /**
   * Remove a `## Section` block from a prompt string.
   * Matches from the header line up to (but not including) the next `## ` header
   * or end of string. Handles backward compatibility: existing system.md files
   * may still contain sections that are now conditionally injected.
   */
  def stripSection(prompt: String, header: String): String =
    val marker = s"## $header"
    prompt.split("(?=\n## )").filterNot(_.trim.startsWith(marker)).mkString

  /** Strip all sections that have been migrated to conditional injection. */
  def stripAllMigrated(prompt: String): String =
    stripSection(prompt, "Voice Output")

end PromptSections

/**
 * Mounted-project list rendering + change detection (cache v2, 2026-09-07).
 *
 * Pure helpers so both AgentCore (wiring) and the unit specs can share one
 * source of truth for the mounted-projects section body and its +/- delta.
 */
object MountedProjectList:

  /** Placeholder rendered when the registry holds no mounted projects — the
    * root Nebula session always sees an explicit state, empty included. */
  val EmptyText = "当前无挂载项目"

  /** Render registry entries as (name, description) into sorted bullet lines.
    * Empty list → [[EmptyText]]; description is omitted when empty/None. */
  def renderLines(projects: List[(String, Option[String])]): String =
    if projects.isEmpty then EmptyText
    else
      projects
        .sortBy(_._1)
        .map { case (name, desc) =>
          desc.filter(_.nonEmpty) match
            case Some(d) => s"- $name: $d"
            case None    => s"- $name"
        }
        .mkString("\n")

  /** Line-level +/- delta between the snapshot text and the current text
    * (same semantics as AgentCore.devicesDeltaLines). Each bullet entry is
    * stripped of its leading "- " so the +/− marker reads cleanly ("+ name:
    * desc" / "- name: desc") instead of the colliding "+- name". Returns ""
    * when the entry sets are identical (only ordering/whitespace changed —
    * no real change). */
  def delta(old: String, current: String): String =
    def entries(s: String): Vector[String] =
      s.split("\n").map(_.trim).filter(_.nonEmpty).toVector
    def bare(e: String): String = e.stripPrefix("- ").trim
    val oldE = entries(old).map(bare)
    val newE = entries(current).map(bare)
    val added = newE.filterNot(oldE.contains)
    val removed = oldE.filterNot(newE.contains)
    if added.isEmpty && removed.isEmpty then ""
    else (added.map("+ " + _) ++ removed.map("- " + _)).mkString("\n")

end MountedProjectList

/**
 * Prompt helpers for SubTask workers (Delegate split, 方案 A).
 *
 * A worker inherits its parent's system.md for domain knowledge, but team
 * interaction content must be stripped — the worker has no team, no Mail,
 * no reporting chain. Stripping is heuristic (text level); the hard
 * behavioral guarantees come from the tool-set filtering (AgentCore) and
 * the Worker Identity Block (order 999).
 */
object SubTaskPrompt:

  /** Line-level patterns (lowercased match, case-insensitive) that remove a line. */
  private val stripPatterns: List[String] = List(
    "mail(",
    "mail the",
    "mail \"",
    "mail '",
    "mail 队友",
    "notify manager",
    "report to manager",
    "escalate to manager",
    "manager 汇报",
    "通知 manager",
    "报告 manager",
    "向 manager",
    "联系 manager",
    "团队汇报",
    "团队成员",
    "你的团队",
    "团队协作",
    "delegate(",
    "subtask(" // 子 agent 不需要再委派
  )

  /**
   * Strip team interaction content from a team agent's system.md, keeping its
   * domain knowledge. 1) removes whole `## Section` blocks (Teams & Flows /
   * Team Catalog); 2) removes individual lines matching team-interaction
   * patterns (case-insensitive).
   */
  def stripTeamContent(prompt: String): String =
    val s1 = PromptSections.stripSection(prompt, "Teams & Flows")
    val s2 = PromptSections.stripSection(s1, "Team Catalog")
    s2.linesIterator
      .filterNot { line =>
        val low = line.toLowerCase
        stripPatterns.exists(p => low.contains(p))
      }
      .mkString("\n")

end SubTaskPrompt
