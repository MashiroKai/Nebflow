package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.core.presets.PresetStore
import nebflow.llm.{Config, NebflowServiceConfig}
import nebflow.shared.{AgentModelConfig, Defaults}

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

  /**
   * 全局 maxTokens 上限（AgentCore 每请求调用）。#339：数据源从 llm.model
   * 改为**默认 preset** 链首个能解析到 provider 模型表的 ref（preferred 优先
   * 逐个试 fallbacks）。任何解析失败回 Defaults.MaxTokens，**不再抛异常**
   * （旧实现对未知 provider 会 throw——本次顺手加固；agent 路径不该因
   * 配置漂移而炸请求）。
   *
   * 既有债（不在本次范围）：所有 agent 共用这一个全局值，而非各 agent 解析
   * 到的模型的 maxTokens——按模型区分留作后续项。
   */
  def globalMaxTokens: Int =
    serviceConfig match
      case None => Defaults.MaxTokens
      case Some(cfg) =>
        try
          val (resolved, _) = nebflow.core.presets.PresetStore().resolve(None, None)
          val chain = resolved.preferred.toList ++ resolved.fallbacks
          chain.flatMap { ref =>
            try
              val (providerId, modelId) = Config.parseModelRef(ref)
              cfg.llm.providers
                .get(providerId)
                .flatMap(_.models.find(_.id == modelId))
                .map(_.maxTokens)
            catch case _: Exception => None
          }.headOption.getOrElse(Defaults.MaxTokens)
        catch case _: Exception => Defaults.MaxTokens

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
      logger.warn("Nebula not found on disk — using code fallback")
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
          val (resolvedModel, _) = PresetStore().resolve(j.preset, j.model)
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
              preset = j.preset,
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

  val Nebula = SeedAgent(
    "Nebula",
    Some("Nebula"),
    "Orchestrator — delegates all execution to specialized Teams and Flows",
    List(
      "AskUserQuestion",
      "Bash",
      "Curl",
      "Glob",
      "Grep",
      "Load",
      "Mail",
      "Pop",
      "Read",
      "Schedule",
      "TaskCreate",
      "TaskUpdate",
      "TransferFile",
      "WebFetch",
      "WebSearch"
    ),
    """你是 Nebula，Nebflow 的编排者：理解用户意图，把工作派发给项目，监管执行，综合结果向用户汇报。不直接执行项目工作。

## 工具面

- 编排：Task(project, task) 触发项目分发器；ProjectCreate 为新意图建项目；AgentControl 监管项目会话（重启/终止）。
- 任务与记忆：TaskList 记任务（建立/查询/闭环）——任务状态归 TaskList，不进记忆；MemoryEdit 维护两级长期记忆（target=user 用户事实 / target=agent 路由经验与教训），条目一行一条，细节拆详情文件；memory-consolidation 审计报告到达时按报告执行整理。
- 勘察：Read / Glob / Grep 读代码与文件。
- 呈现与交互：Card 可视化、Pop 打开文件/URL、AskUserQuestion 选项式提问、SendMessage 消息、Schedule 定时任务、TransferFile 传文件。

## 生命周期协议

1. 理解意图 → 已有对应 project（workspace 路径与意图对齐）则 Task 派发；没有则 ProjectCreate 先建。
1b. 交付物制作类任务（PPT/deck/视频/音频/图片集/文档排版/成稿报告）必须落项目：先按 workspace 路径找匹配项目；没有则 ProjectCreate 先建再 Task——禁止派给 Delegate（内核无项目面、无插件能力，成品无处归档）。派发文中写明该交付物所需的插件能力（如演示稿/文档排版/视频）；插件由项目侧挂载，你只需声明意图。
1c. 交付物制作类任务必须落"有对应能力的项目"，且派发时点名所需插件：PPT/演示/deck/slides ⇒ slideblocks；HTML 卡片/社交图 ⇒ design-cards；设计规格书/视觉评审 ⇒ design-spec；前端与视觉铁律 ⇒ nebflow-frontend-dev；文档产出 ⇒ nebflow-docs-prompt；独立复核 ⇒ nebflow-qa。插件由项目侧为节点挂载，你只需点名；实例内没有该项目时先 ProjectCreate 再 Task。
2. Task(project, 任务文本)：写清目标、约束、验收口径——任务文本是分发器的全部上下文。
3. 节点结果沿 out 边自动投递给你，不轮询不刷新。
4. 结果到达后综合：跨节点结论汇总、矛盾指出、证据保留（关键路径+行号）。
5. 失败先 AgentControl 重启或重新 Task 补充上下文；同一节点两次失败，AskUserQuestion 升级给用户。
6. 汇报：结论先行——做了什么、证据是什么、还剩什么。

## Git 纪律

- ~/.nebflow 与各项目 repo：任何改动同任务内 commit（按文件 add，message 写目的），禁止裸改。
- 一项目一 repo；禁止把改动提交进别的项目的 repo。

## 纪律

- 沙箱写根 = ~/.nebflow：可写定义层/运维配置/记忆文件；sessions/logs/uploads 等运行时数据除明确运维任务不动；凭据文件仅诊断读取，不外传不复写。
- 所有工具用法以工具定义内的描述为准。
- 不确定即 AskUserQuestion；结果综合后主动汇报。"""
  )

  /** Seeds for initial installation — Nebula only (F.3 convergence, 2026-09-05).
    * Nebula is both the only seed and the runtime fallback; every other agent
    * is defined on disk only (git-tracked definitions, restorable outside the
    * code). Archived agent dirs (agent.json renamed *.archived) must NOT be
    * resurrected by seeding — seedDefaults() rewrites any in-list dir missing
    * agent.json, so keeping retired names out of this list is what keeps them
    * retired across restarts (GatewayMain calls seedDefaults() on startup). */
  val all = List(Nebula)

end Seeds

object AgentLibrary:
  def defaultDir: os.Path = PathUtil.dataRoot / "agents"
end AgentLibrary
