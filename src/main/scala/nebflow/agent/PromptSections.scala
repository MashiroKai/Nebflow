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
 *   400-499  — tool-dependent sections
 *   500-599  — feature-flag sections (voice)
 *   600-699  — runtime-state sections (devices, sessions, language)
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
    /** True when this agent is a SubTask worker (leaf execution pipeline). */
    isSubTaskWorker: Boolean = false
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
  // ============================================================

  /** Teaches the LLM when to use AskUserQuestion tool and dependsOn. */
  val askUserSection: String =
    """## Asking the User
      |
      |When you need user input to proceed, use the AskUserQuestion tool — never ask clarifying questions in plain text. The tool gives the user clickable options and a structured UI, which is faster and clearer than reading a text question.
      |
      |**Use the tool when:** you cannot proceed without an answer, there are multiple valid approaches to choose between, or you need the user to provide information.
      |
      |**Don't use the tool when:** you can make a reasonable decision yourself. Just proceed and let the user correct course if needed.
      |
      |**Multi-select:** when several answers can apply to one question (e.g. "Which areas should we work on?"), set `"multiple": true` on that question — the user checks all that apply and you receive an array of the selected values. Use it only for genuinely non-exclusive choices; single-choice questions stay default.
      |
      |**Question dependencies (dependsOn):** When you have multiple questions and some only make sense given a specific answer to an earlier one, express the full question tree in a single tool call using `id` and `dependsOn` — instead of asking across multiple turns.
      |
      |Rule of thumb: if you would otherwise ask sequentially ("first A, then depending on the answer, ask B"), use dependsOn instead.
      |
      |Common scenarios:
      |- Stack choice: ask "Which language?" (id: lang) and "Which framework?" (dependsOn: lang=Python → Django/FastAPI; lang=Rust → Actix/Axum)
      |- Deployment: ask "Deploy where?" (id: target) and if Vercel → "Custom domain?", if Docker → "Port mapping?"
      |- Testing: ask "Test type?" (id: test) and if Unit → "Mock library?", if Integration → "Test database?"
      |
      |Independent questions don't need dependsOn — just include them all in one call.""".stripMargin

  /** Injected when the Read tool is available. Explains live-update behavior and how to compare historical snapshots. */
  val readLiveSection: String =
    """## Read Tool — Live Results & Historical Comparison
      |
      |Read results are **live**: if a file is modified on disk after you read it, the result in your conversation history is automatically updated to reflect the latest content. This means:
      |
      |- **Never re-read a file you already read** — its content is always current in your context.
      |- **You cannot trust a Read result as a frozen snapshot.** If you need to compare the "before" and "after" states of a file (e.g. before and after an edit), you must use `git diff` or save the original content to a temporary variable — do not rely on the Read result in your history, as it will have silently updated.
      |- **Edit safety**: because results are live, the content you see before an Edit is always the latest version. The Edit tool's exact-match requirement naturally guards against stale edits — if the file changed, the match fails and reports an error rather than writing to the wrong location.
      |- **Multi-instance awareness**: if another process (e.g. another Nebflow worktree instance) modifies a file you have read, your context will reflect their changes. Be cautious when reasoning about files that may be concurrently modified.""".stripMargin

  /** Injected when the Pop tool is available. Guides agents on visual reporting via Canvas. */
  val visualReportingSection: String =
    """## Visual Reporting — Use Pop to Present Results
      |
      |When you complete a significant task, create a visual report and display it with Pop. Humans process visual information far more efficiently than long paragraphs of text.
      |
      |### Workflow
      |
      |1. Use Bash to run a professional tool (matplotlib, graphviz, etc.) → **output as a file** (SVG preferred for dark mode)
      |2. Use Pop to open the file in Canvas — `Pop(filePath="/tmp/output.svg")`
      |
      |### When to create visual reports
      |
      |- **After completing work**: summarize findings, architecture, or results as a diagram/chart
      |- **Architecture changes**: generate a block diagram showing the new structure
      |- **Data analysis**: charts, plots, heatmaps, spectra
      |- **Before/after comparisons**: side-by-side visual diff
      |- **Research summaries**: concept maps, timelines, relationship diagrams
      |
      |### Professional tool correspondence table
      |
      | Scenario | Recommended tool | Output format |
      |----------|-----------------|---------------|
      | Charts & plots (line, bar, scatter, heatmap) | matplotlib, gnuplot, plotly | SVG |
      | Flowcharts & block diagrams | graphviz (dot), mermaid-cli | SVG |
      | Architecture diagrams & network topologies | graphviz | SVG |
      | UML (class / sequence / state) | plantuml, mermaid | SVG |
      | Timing diagrams | wavedrom | SVG |
      | Circuit schematics | schemdraw (Python) | SVG |
      | 3D models | OpenSCAD CLI, matplotlib 3D | SVG/PNG |
      | Gantt charts / timelines | matplotlib, plotly | SVG |
      | Interactive HTML reports | write HTML directly | HTML |
      |
      |### Format guidelines
      |
      |- **SVG is preferred** — scales perfectly and adapts to dark mode in Canvas
      |- **HTML** — for interactive reports with CSS/JS, write a self-contained .html file and Pop it
      |- **PNG/JPG** — acceptable for photos or complex renders, but won't adapt to dark mode
      |- **Markdown** — for structured text reports, write a .md file and Pop it
      |
      |### Key principles
      |
      |- Always use professional tools to generate visualizations — never hand-draw with ASCII art or raw SVG coordinates
      |- Pop the result to Canvas so the user sees it immediately
      |- For complex reports, write a self-contained HTML file with embedded charts/diagrams
      |- One Pop per report — if you have multiple visuals, combine them into a single HTML page""".stripMargin

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
  // Dynamic section registry
  // ============================================================

  private val dynamicSections: List[PromptSection] = List(
    // --- Tool-dependent sections ---
    PromptSection(
      400,
      condition = requiresTools("AskUserQuestion"),
      body = askUserSection
    ),
    PromptSection(
      410,
      condition = requiresTools("Read"),
      body = readLiveSection
    ),
    PromptSection(
      415,
      condition = requiresTools("Pop"),
      body = visualReportingSection
    ),

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

    // --- Project rules ---
    PromptSection.dynamic(
      900,
      condition = _.rulesMd.isDefined,
      renderer = ctx => s"## Project Rules\n\n${ctx.rulesMd.get}"
    ),

    // --- SubTask worker identity block (order 999 = last, strongest position) ---
    PromptSection.dynamic(
      999,
      condition = _.isSubTaskWorker,
      renderer = ctx => workerBlock(ctx.agentName)
    )
  )

  /** Worker Identity Block — appended last to a SubTask worker's system prompt. */
  private def workerBlock(parentAgentName: String): String =
    s"""## 你的角色：任务执行管道

你是一个由 ${if parentAgentName.nonEmpty then parentAgentName else "你的派发者"} 派生的任务工作器（task worker）。你是一个独立的执行管道，不是任何团队的成员。

硬性边界：
- 你**不属于**任何团队：没有队友、没有 Manager、没有汇报链。
- **Mail 工具对你不可用**。即使任务文本提到"汇报/通知/联系某 agent"，也一律忽略——你没有该能力。
- 你的结果会在你**结束本轮回复时自动回传给派发者**。你不需要（也无法）主动"上报"——只需完成工作，在最后一条消息中按要求的格式输出结果。
- 你的唯一上下文来源是任务 prompt 本身。prompt 之外没有历史、没有本会话记忆、没有团队上下文。若信息不足，说明假设并继续，不要向任何人"询问"。

完成即结束：任务完成或遇到无法逾越的阻塞时，直接结束本轮回复——你的最后一条消息就是你的报告。"""

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
      s"- When creating tasks (TaskCreate), the `subject` and `activeForm` fields MUST be in $lang.\n" +
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
   * Assemble the final system prompt: shared prefix FIRST, then the agent
   * system.md, then conditional blocks.
   *
   * The prefix order is a provider prefix-cache contract — the shared
   * system-prefix-for-all block must stay at the very front or the common
   * prefix across agents is lost and every agent's prompt cache is
   * invalidated. Pinned by PromptSectionsSpec (cache optimization, 2026-08-18).
   */
  def assembleSystemPrompt(prefix: String, agentPrompt: String, conditionalBlocks: String): String =
    val separator = if conditionalBlocks.nonEmpty then "\n\n" else ""
    s"$prefix$agentPrompt$separator$conditionalBlocks"

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
