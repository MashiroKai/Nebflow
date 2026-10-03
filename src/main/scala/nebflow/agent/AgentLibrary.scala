package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.actor.AgentDef
import nebflow.core.AgentLibraryView
import nebflow.core.presets.{PresetStore, SchemePolicy}
import nebflow.shared.*

import scala.util.Try

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M3):原地混入 core 窄视图(消费面仅 get,签名镜像,行为保持)
// Agent definitions: the four CONVERGED names (Nebula / project-dispatcher /
// general / kernel) are CODE-DEFINED (builtin-def batch 2026-10-03, author
// directive ① "all four agents hardcoded in code, no disk scan, code is the
// single source of truth") — `BuiltinAgents` owns their defs; disk files under
// `~/.nebflow/agents/<builtin>/` are dead letters (never read for the def face,
// never deleted). Only NON-converged (custom) agents load from disk: deleting
// their directory removes them. The panel is read-only for builtins.
class AgentLibrary(
  agentsDir: os.Path,
  serviceConfig: Option[NebflowServiceConfig] = None
) extends AgentLibraryView:
  private val logger = NebflowLogger.forName("nebflow.agent.library")

  // globalMaxTokens was REMOVED here (maxcfg batch 2026-09-16): it read
  // `models[].maxTokens` from the model config, which is no longer a
  // user-configurable key. Its value only ever fed `LlmRequest.maxTokens`,
  // which no adapter ever honoured for request construction (the adapters read
  // the per-candidate value instead), so removing it changes no behaviour.

  // ============================================================
  // Public API
  // ============================================================

  // seedDefaults() RETIRED (builtin-def batch 2026-10-03, author directive ①):
  // the four converged agents are code-defined (BuiltinAgents) — there is
  // nothing left to seed and no disk mirror to maintain. The former Nebula-only
  // seeding (Seeds.all) is deleted with it; GatewayMain's startup call is gone.

  // Load all agents. The four converged names ALWAYS come from code
  // (BuiltinAgents — never from disk, never absent); the disk scan covers
  // custom (non-converged) agents only.
  def loadAll(): IO[Map[String, AgentDef]] = IO.blocking {
    val diskAgents = scanDisk()
    val builtins = nebflow.core.entity.BuiltinAgents.entries().map((k, e) => k -> e.toAgentDef)
    builtins ++ diskAgents
  }

  /** Get a single agent by name. Converged names resolve from code only. */
  def get(name: String): IO[Option[AgentDef]] =
    if nebflow.core.entity.BuiltinAgents.isBuiltin(name) then
      IO.blocking(nebflow.core.entity.BuiltinAgents.entry(name).map(_.toAgentDef))
    else loadAll().map(_.get(name))

  /** Write a system.md file for the given agent.
    * Builtins are read-only (author directive ①: the code prompt is the only
    * source) — a write attempt is refused loudly (WARN, no write, no error). */
  def updateSystemPrompt(name: String, content: String): IO[Unit] = IO.blocking {
    if nebflow.core.entity.BuiltinAgents.isBuiltin(name) then
      logger.warnSync(
        s"Refused updateSystemPrompt('$name') — built-in agents are code-defined (BuiltinAgents) and read-only"
      )
    else
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
   * builtin-def batch (2026-10-03): for the four code-defined agents agent.json
   * is a pure MODEL-CHAIN SIDECAR — when it does not exist (fresh home) it is
   * created with just the name + model keys (the def face stays in code).
   *
   * @return true if updated (created counts as updated), false only on a
   *         corrupt existing file.
   * @throws RuntimeException if the existing JSON is corrupt.
   */
  def updateModel(name: String, config: AgentModelConfig): IO[Boolean] = IO.blocking {
    val jsonPath = agentsDir / name / "agent.json"
    if !os.exists(jsonPath) then
      os.makeDir.all(agentsDir / name)
      os.write.over(
        jsonPath,
        io.circe.Json.obj("name" -> name.asJson, "model" -> config.asJson).noSpaces
      )
      true
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

  /** Read a system.md file. Builtins serve the CODE prompt (read-only face). */
  def readSystemPrompt(name: String): IO[Option[String]] = IO.blocking {
    if nebflow.core.entity.BuiltinAgents.isBuiltin(name) then
      nebflow.core.entity.BuiltinAgents.entry(name).map(_.systemPrompt)
    else
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
    // builtin-def 批（2026-10-03 作者令①）：收敛名的 def 面只在代码
    // （BuiltinAgents）——磁盘目录对这四个名是死信，读侧单点跳过（纵深：
    // scanDisk 已过滤，此处兜 loadFromDir 的其他调用方）。
    if nebflow.core.entity.BuiltinAgents.isBuiltin(dir.last) then None
    else loadCustomFromDir(dir)

  private def loadCustomFromDir(dir: os.Path): Option[AgentDef] =
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

  end loadCustomFromDir

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
        // builtin-def 批：收敛名不入扫盘面（代码单点，磁盘死信）
        .filter(dir => !nebflow.core.entity.BuiltinAgents.isBuiltin(dir.last))
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
