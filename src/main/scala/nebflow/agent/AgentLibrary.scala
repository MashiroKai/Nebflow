package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.presets.{PresetStore, SchemePolicy}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.llm.NebflowServiceConfig
import nebflow.shared.AgentModelConfig

import scala.util.Try

// Agent definitions loaded from disk (~/.nebflow/agents/<name>/agent.json + system.md).
//
// Only Nebula is hardcoded as a fallback — if its disk files are missing or
// corrupted, the code definition keeps the system alive. All other agents
// are defined exclusively on disk; deleting their directory removes them.
class AgentLibrary(
  agentsDir: os.Path,
  serviceConfig: Option[NebflowServiceConfig] = None
):
  private val logger = NebflowLogger.forName("nebflow.agent.library")

  // globalMaxTokens was REMOVED here (maxcfg batch 2026-09-16): it read
  // `models[].maxTokens` from the model config, which is no longer a
  // user-configurable key. Its value only ever fed `LlmRequest.maxTokens`,
  // which no adapter ever honoured for request construction (the adapters read
  // the per-candidate value instead), so removing it changes no behaviour.

  // ============================================================
  // Public API
  // ============================================================

  /** Seed agent.json + system.md for default agents (first install only). */
  def seedDefaults(): IO[Unit] = IO.blocking {
    Seeds.all.foreach { agent =>
      val dir = agentsDir / agent.name
      os.makeDir.all(dir)
      // Write agent.json if it doesn't exist (don't overwrite user edits)
      val jsonPath = dir / "agent.json"
      if !os.exists(jsonPath) then
        os.write.over(jsonPath, agent.toJson)
        logger.info(s"Seeded agent.json for: ${agent.name}")
      // Write system.md if it doesn't exist
      if agent.systemPrompt.nonEmpty && !os.exists(dir / "system.md") then
        os.write.over(dir / "system.md", agent.systemPrompt)
        logger.info(s"Seeded system.md for: ${agent.name}")
    }
  }

  // Load all agents from disk. Scans all agent.json files in the agents directory.
  // Nebula is guaranteed to exist — falls back to code definition if
  // missing or corrupted on disk.
  def loadAll(): IO[Map[String, AgentDef]] = IO.blocking {
    val diskAgents = scanDisk()

    // Ensure Nebula always exists (system survival guarantee)
    if diskAgents.contains(Seeds.Nebula.name) then diskAgents
    else
      logger.warnSync("Nebula not found on disk — using code fallback")
      diskAgents + (Seeds.Nebula.name -> Seeds.Nebula.toAgentDef)
  }

  /** Get a single agent by name. */
  def get(name: String): IO[Option[AgentDef]] =
    loadAll().map(_.get(name))

  /** Write a system.md file for the given agent. */
  def updateSystemPrompt(name: String, content: String): IO[Unit] = IO.blocking {
    val dir = agentsDir / name
    os.makeDir.all(dir)
    os.write.over(dir / "system.md", content)
  }

  // updateTools (agent.json tools write-back) retired 2026-09-06 — the panel's
  // capability sections are gone and the WS/REST write channels now reject.
  // Per decision A① the tools/skills/flows fields in existing agent.json files
  // KEEP being parsed (legacy grants stay live until stage 3); only the
  // write-back path is retired.

  /**
   * Update the model configuration in agent.json. Reads the existing file,
   *  merges the "model" field, and writes it back.
   *
   * @return true if updated, false if agent.json not found.
   * @throws RuntimeException if the existing JSON is corrupt.
   */
  def updateModel(name: String, config: AgentModelConfig): IO[Boolean] = IO.blocking {
    val jsonPath = agentsDir / name / "agent.json"
    if !os.exists(jsonPath) then false
    else
      val json = os.read(jsonPath)
      io.circe.parser.parse(json) match
        case Right(parsed) =>
          val updated = parsed.deepMerge(io.circe.Json.obj("model" -> config.asJson))
          os.write.over(jsonPath, updated.noSpaces)
          true
        case Left(err) =>
          throw new RuntimeException(s"Failed to parse agent.json for '$name': ${err.getMessage}")
  }

  /** Read a system.md file. */
  def readSystemPrompt(name: String): IO[Option[String]] = IO.blocking {
    val p = agentsDir / name / "system.md"
    if os.exists(p) then Some(os.read(p)) else None
  }

  /** Reload from disk (no cache, so it's a no-op). */
  def refresh(): IO[Unit] = IO.unit

  // ============================================================
  // Disk scanning
  // ============================================================

  /**
   * Load a single agent from a directory containing agent.json + system.md.
   *  Shared between global scanning and project-level overrides.
   */
  def loadFromDir(dir: os.Path): Option[AgentDef] =
    val jsonPath = dir / "agent.json"
    if !os.exists(jsonPath) then None
    else
      parseAgentJson(jsonPath) match
        case Some(j) =>
          val prompt = readSystemMd(dir / "system.md")
          // Resolve preset/legacy model into the final AgentModelConfig.
          // Priority: explicit preset > legacy model > default preset > global chain.
          // panelscheme 批（2026-09-21）：输入先经 SchemePolicy 名称策略——可设
          // 两类（Nebula/project-dispatcher）原样；kernel 继承 Nebula 当前引用；
          // general 继承 project-dispatcher 当前引用；其余忽略存储引用回落默认。
          val (effPreset, effModel) = SchemePolicy.effectiveRefs(j.name, j.preset, j.model)
          val (resolvedModel, _) = PresetStore().resolve(effPreset, effModel)
          Some(
            AgentDef(
              name = j.name,
              description = j.description.getOrElse(""),
              tools = j.tools,
              systemPrompt = prompt,
              avatar = j.avatar,
              displayName = j.displayName,
              voiceEnabled = j.voice.getOrElse(false),
              model = Some(resolvedModel),
              preset = effPreset,
              // ── 收敛名 category 收敛（2026-09-11 agentdef-tidy 批，安全优先）──
              // 本行是全树**唯一**读 agent.json `category` 的位置（EntityLoader
              // 按路径推断、不读 JSON）。收敛集四定义（AgentCore.ConvergedAgentNames
              // = Nebula / project-dispatcher / general / kernel）无视 JSON 声明，
              // category 恒 "standalone"。
              //
              // 不堵即后门：给任一 keeper 写 `"category":"team"` 后，per-turn
              // 重载路径（ContextRefresher.loadCurrentDef → agentLibrary.get →
              // loadFromDir）会把 category 带进 ① AgentCore.fixedToolsFor 首分支
              // （`case "team" | "flow" => legacyFixedTools` → Mail/SubTask/TeamTask*）
              // 与 ② PromptContext.agentCategory → PromptSections order-395 身份段
              // （`agentCategory == "team"` ⇒ team 成员身份块）。今天无人走，结构上开着。
              //
              // 非收敛名逐字节 parity（team/flow/legacy standalone 的推断、工具面、
              // 身份段零变化）：`category` 解析能力本身保留（EntityTypes 解码 /
              // EntityLoader 路径推断 / 面板回显不受影响）。
              // 纵深 = AgentCore.fixedToolsFor 同款收敛名短路（唯一注入点兜底）。
              category =
                if AgentCore.ConvergedAgentNames.contains(j.name) then "standalone"
                else j.category.getOrElse("standalone"),
              mcpServers = j.mcpServers.getOrElse(Nil),
              skills = j.skills.getOrElse(Nil),
              flows = j.flows.getOrElse(Nil)
            )
          )
        case None =>
          logger.warn(s"Skipping invalid or unreadable: ${jsonPath.toString}")
          None

    end if

  end loadFromDir

  /**
   * Load a project-specific agent from a folder directory.
   *  Looks in `~/.nebflow/folders/<folderId>/agents/<name>/`.
   *  Returns None if no folder agent directory exists.
   */
  def loadFolderAgent(folderId: String, name: String): IO[Option[AgentDef]] = IO.blocking {
    val dir = PathUtil.dataRoot / "folders" / folderId / "agents" / name
    if !os.isDir(dir) then None
    else loadFromDir(dir)
  }

  private def scanDisk(): Map[String, AgentDef] =
    if !os.exists(agentsDir) then Map.empty
    else
      os.list(agentsDir)
        .filter(os.isDir)
        .flatMap { dir => loadFromDir(dir).map(d => d.name -> d) }
        .toMap

  private def parseAgentJson(path: os.Path): Option[AgentJson] =
    for
      content <- Try(os.read(path)).toOption
      agentJson <- io.circe.parser.decode[AgentJson](content).toOption
    yield
      logger.info(s"Loaded agent: ${agentJson.name}")
      agentJson

  private def readSystemMd(path: os.Path): String =
    if os.exists(path) then os.read(path) else ""

end AgentLibrary

// ============================================================
// agent.json schema
// ============================================================

private case class AgentJson(
  name: String,
  displayName: Option[String] = None,
  description: Option[String] = None,
  useWhen: Option[String] = None,
  tools: List[String] = List("*"),
  mcpServers: Option[List[String]] = None,
  avatar: Option[String] = None,
  voice: Option[Boolean] = None,
  model: Option[AgentModelConfig] = None,
  preset: Option[String] = None,
  category: Option[String] = None,
  skills: Option[List[String]] = None,
  flows: Option[List[String]] = None
)

private object AgentJson:

  given Decoder[AgentJson] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      displayName <- c.downField("displayName").as[Option[String]]
      description <- c.downField("description").as[Option[String]]
      useWhen <- c.downField("useWhen").as[Option[String]]
      tools <- c.downField("tools").as[Option[List[String]]]
      mcpServers <- c.downField("mcpServers").as[Option[List[String]]]
      avatar <- c.downField("avatar").as[Option[String]]
    yield AgentJson(
      name,
      displayName,
      description,
      useWhen,
      tools.getOrElse(List("*")),
      mcpServers,
      avatar,
      voice = c.downField("voice").as[Option[Boolean]].toOption.flatten,
      model = c.downField("model").as[Option[AgentModelConfig]].toOption.flatten,
      preset = c.downField("preset").as[Option[String]].toOption.flatten,
      category = c.downField("category").as[Option[String]].toOption.flatten,
      skills = c.downField("skills").as[Option[List[String]]].toOption.flatten,
      flows = c.downField("flows").as[Option[List[String]]].toOption.flatten
    )
  }

  given Encoder[AgentJson] = Encoder.instance { j =>
    Json
      .obj(
        "name" -> j.name.asJson,
        "displayName" -> j.displayName.asJson,
        "description" -> j.description.asJson,
        "useWhen" -> j.useWhen.asJson,
        "tools" -> j.tools.asJson,
        "mcpServers" -> j.mcpServers.asJson
      )
      .deepMerge(j.avatar.map(a => Json.obj("avatar" -> a.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.voice.map(v => Json.obj("voice" -> v.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.model.map(m => Json.obj("model" -> m.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.preset.map(p => Json.obj("preset" -> p.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.category.map(c => Json.obj("category" -> c.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.skills.map(s => Json.obj("skills" -> s.asJson)).getOrElse(Json.obj()))
      .deepMerge(j.flows.map(f => Json.obj("flows" -> f.asJson)).getOrElse(Json.obj()))
  }
end AgentJson

// ============================================================
// Seed definitions (for initial install + Nebula fallback)
// ============================================================

private case class SeedAgent(
  name: String,
  displayName: Option[String],
  description: String,
  tools: List[String],
  systemPrompt: String
):

  def toAgentDef: AgentDef = AgentDef(
    name = name,
    description = description,
    tools = tools,
    systemPrompt = systemPrompt,
    displayName = displayName,
    category = "standalone"
  )

  def toJson: String =
    val agentJson = AgentJson(name, displayName, Some(description), None, tools, None, None)
    agentJson.asJson.noSpaces

end SeedAgent

private object Seeds:

  // Tool surface: deliberately empty — this field is NOT the authoritative face.
  // Nebula is a converged agent name, so `buildToolList` short-circuits any
  // `tools` declaration to the empty set (AgentCore.ConvergedAgentNames branch)
  // and the field grants nothing. The single source of truth is
  // AgentCore.NebulaOrchestrationTools, auto-injected by AgentCore.fixedToolsFor.
  // Do not reintroduce a list here: it would read as authoritative while being
  // dead data that silently drifts from the real tool surface.
  val Nebula = SeedAgent(
    "Nebula",
    Some("Nebula"),
    "Orchestrator — delegates all execution to specialized Teams and Flows",
    Nil,
    """You are Nebula, the AI assistant in Nebflow. Your job is to understand the user's intent and help the user get the work done.

Tool duties: ProjectCreate creates projects; Mail dispatches a task to a project or a remote device, choosing a suitable target from the project's and the device's descriptions; AgentControl supervises the running state of project sessions; Read reads results; TaskList manages task state and task memory; MemoryNote records long-term memory.

Creating a project has three cases: (1) an old project whose folder you do not know the path of - leave it empty and let the user choose; (2) the user stated the project path explicitly in the conversation - create it directly; (3) a new project with no project folder - create it under {{data_root}}/projects/<project name> by preference. A project's description must be clear enough to say what the project is for. Create a project proactively to carry the work, unless it really is a single one-off execution task - those go to the general project.

If you need to know the current state before you can decide, have the general project summarize the current state for you. general is the project for simple general tasks; when the user needs a skill, an MCP server or a plugin created, route it to the general project.

Output: keep it terse, add a plain-language explanation when you use a technical term, no emoji. When relaying a task keep the user's original words, add no more than necessary, and stay on the task itself. Task results are shown to the user through Pop. Todos / questions / decisions always go through AskUserQuestion.

Visualization: use the card tool actively to visualize results - humans read visual content more easily; for material you cannot produce yourself, such as drawing an image, ask general for help. Never draw a block diagram, flowchart or architecture diagram out of ASCII characters (box-drawing glyphs, `+---+` borders, dash-and-pipe trees). For plain-text content do not use the card tool - output it directly.

Memory: record only what cannot be obtained from the project's code and helps future tasks, such as design preferences, design principles, the user's profile, the user's habits, the project's background.

""" + "\n"
  )

  /**
   * Seeds for initial installation — Nebula only (F.3 convergence, 2026-09-05).
   * Nebula is both the only seed and the runtime fallback; every other agent
   * is defined on disk only (git-tracked definitions, restorable outside the
   * code). Archived agent dirs (agent.json renamed *.archived) must NOT be
   * resurrected by seeding — seedDefaults() rewrites any in-list dir missing
   * agent.json, so keeping retired names out of this list is what keeps them
   * retired across restarts (GatewayMain calls seedDefaults() on startup).
   */
  val all = List(Nebula)

end Seeds

object AgentLibrary:
  def defaultDir: os.Path = PathUtil.dataRoot / "agents"
end AgentLibrary
