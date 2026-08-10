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
    rulesMd: Option[String] = None
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
  // Dynamic section registry
  // ============================================================

  private val dynamicSections: List[PromptSection] = List(
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

    // --- Task list (always visible to agent) ---
    PromptSection.dynamic(
      630,
      condition = _.taskListText.nonEmpty,
      renderer = _.taskListText
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
    )
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
      if os.exists(sectionsDir) then
        os.list(sectionsDir).map(p => os.mtime(p)).maxOption.getOrElse(0L)
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

  /** Parse the Condition comment to build a context predicate. */
  private def parseCondition(content: String): PromptContext => Boolean =
    val conditionLine = content.linesIterator.find(_.trim.startsWith("<!-- Condition:")).getOrElse("")
    val cond = conditionLine.replaceAll(".*<!-- Condition:", "").replaceAll("-->.*", "").trim
    cond match
      case s if s.startsWith("agent has") =>
        val toolName = s.replace("agent has", "").replace("tool", "").trim
        requiresTools(toolName)
      case "voiceEnabled is true" => _.voiceEnabled
      case "always"               => _ => true
      case _                      => _ => true

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
      else
        Some(PromptSection(order, condition, template))

  /** Parse a JSON condition field into a context predicate. */
  private def parseJsonCondition(json: Json): PromptContext => Boolean =
    val condVal = json.hcursor.downField("condition").focus.getOrElse(Json.fromString("always"))
    parseConditionValue(condVal)

  /** Parse a condition value: either a string ("always") or an object ({tool/flag/and/or/...}). */
  private def parseConditionValue(cond: Json): PromptContext => Boolean =
    cond.asString match
      case Some(s) => parseConditionObject(Json.obj("x" -> Json.fromString(s)).hcursor.downField("x").focus.getOrElse(Json.Null))
      case None    => parseConditionObject(cond)

  /** Parse a condition object with and/or/not/tool/flag/category/name operators. */
  private def parseConditionObject(cond: Json): PromptContext => Boolean =
    import io.circe.JsonObject
    val obj = cond.asObject.getOrElse(JsonObject.empty)
    obj("and").map { arr =>
      val subs = arr.asArray.getOrElse(Nil).map(parseConditionValue)
      (ctx: PromptContext) => subs.forall(_(ctx))
    }.orElse(
      obj("or").map { arr =>
        val subs = arr.asArray.getOrElse(Nil).map(parseConditionValue)
        (ctx: PromptContext) => subs.exists(_(ctx))
      }
    ).orElse(
      obj("not").map { inner =>
        val sub = parseConditionValue(inner)
        (ctx: PromptContext) => !sub(ctx)
      }
    ).orElse(
      obj("tool").map { v =>
        val toolName = v.asString.getOrElse("")
        (ctx: PromptContext) => ctx.availableTools.contains(toolName)
      }
    ).orElse(
      obj("flag").map { v =>
        val flagName = v.asString.getOrElse("")
        (ctx: PromptContext) => flagName match
          case "voiceEnabled"      => ctx.voiceEnabled
          case "hasDevices"        => ctx.hasDevices
          case "hasActiveSessions" => ctx.hasActiveSessions
          case _                   => false
      }
    ).orElse(
      obj("category").map { v =>
        val cat = v.asString.getOrElse("")
        (ctx: PromptContext) => ctx.agentCategory == cat
      }
    ).orElse(
      obj("name").map { v =>
        val name = v.asString.getOrElse("")
        (ctx: PromptContext) => ctx.agentName == name
      }
    ).getOrElse(_ => true)

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
          .out.text().trim
      catch case _: Exception => return template
    parser.parse(result).toOption match
      case Some(json) =>
        json.asObject.map(_.toMap).getOrElse(Map.empty)
          .foldLeft(template) { case (t, (key, value)) =>
            val strValue = value.asString.getOrElse(value.noSpaces)
            t.replace(s"{{$key}}", strValue)
          }
      case None => template

  /** All sections: dynamic (code-defined) + file-based (user-editable). */
  def all: List[PromptSection] =
    dynamicSections ++ loadFileSectionsCached()

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
