package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.SchemePolicy
import nebflow.llm.NebflowServiceConfig
import nebflow.shared.AgentModelConfig

import scala.util.Try

// Agent definitions loaded from disk (~/.nebflow/agents/<name>/agent.json + system.md).
//
// All agents — Nebula included — are defined exclusively on disk; deleting their
// directory removes them. A missing manifest-declared agent is re-seeded from
// the seed tree (seed/agents/<name>/) by SeedService.ensureSeeded at boot.
// Since the govmemory batch (2026-09-25) the former in-code Nebula fallback
// definition (AgentLibrary.Seeds) is retired — its prompt text had drifted
// stale (retired mechanism names) and duplicated the tree seed. If Nebula's
// directory is missing before that self-heal runs, loadAll logs a loud WARN
// and Nebula is unavailable until the next boot's self-heal.
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

  // seedDefaults() was retired 2026-09-25 (govmemory batch): its only seed was
  // the in-code Nebula definition (AgentLibrary.Seeds), which is retired with
  // it. Cold-start / self-heal seeding now runs exclusively through
  // SeedService.ensureSeeded (manifest-declared tree seeds, guard #304).

  // Load all agents from disk. Scans all agent.json files in the agents directory.
  // Nebula is expected to exist on disk (seed self-heal at boot repairs a
  // missing directory from the seed tree).
  def loadAll(): IO[Map[String, AgentDef]] = IO.blocking {
    val diskAgents = scanDisk()

    // System survival note: Nebula missing on disk is no longer silently
    // substituted by an in-code definition (that path retired with Seeds).
    // Loud WARN so the gap is visible; SeedService.ensureSeeded self-heals it
    // from the tree seed on the next boot.
    if diskAgents.contains("Nebula") then diskAgents
    else
      logger.warnSync("Nebula not found on disk — unavailable until the seed self-heal restores it (next boot; see SeedService.ensureSeeded)")
      diskAgents
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
          // Resolve the final model chain through SchemePolicy (own chain >
          // Nebula primary chain > seed chain), fresh per load — a /model
          // edit takes effect on the next agent-def reload.
          val (resolvedModel, _) = SchemePolicy.resolveModel(j.name, j.model)
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

object AgentLibrary:
  def defaultDir: os.Path = PathUtil.dataRoot / "agents"
end AgentLibrary
