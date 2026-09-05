package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.entity.{EntityLoader, TeamCatalog}
import nebflow.core.skill.SkillService
import nebflow.core.{HeadlessMode, PathUtil, SystemReminder, SystemReminders}
import nebflow.service.{MemoryStore, RulesStore}

/**
 * Unified context refresh for session-scoped resources.
 *
 * All sources are re-resolved every turn (EveryTurn). MtimeCache ensures
 * unchanged files cost only a stat() syscall — no re-read, no rebuild.
 *
 * Sources:
 *   • system-prefix  — FileInjectionSource with mtime cache
 *   • agentDef       — AgentLibrary.get (reads system.md from disk)
 *   • rulesMd        — folder chain → RulesStore.resolveInheritedRules (mtime-cached)
 *   • projectRoot    — folder chain → SessionStore.resolveProjectRoot
 *   • thinkingConfig — global Ref[IO, ThinkingConfig]
 *   • gitBranch      — read .git/HEAD directly (supports worktrees)
 *   • memory files   — built into memoryBlock string, injected into system prompt
 */
object ContextRefresher:

  /** Registered InjectionSources — for documentation and future management. */
  val promptSources: List[InjectionSource] = List(
    systemPrefixForAll,
    systemPrefixForTeams,
    systemPrefixForFlows,
    managerPrefixSource
  )

  /**
   * System prefix for ALL agents: ~/.nebflow/prompts/system-prefix-for-all.md
   *  with JAR fallback (/system-prefix-for-all.md).
   */
  val systemPrefixForAll: FileInjectionSource =
    val jarFallback =
      val is = getClass.getResourceAsStream("/system-prefix-for-all.md")
      if is != null then
        try scala.io.Source.fromInputStream(is)(scala.io.Codec.UTF8).mkString.trim
        finally is.close()
      else ""
    // Backward compat: if new file missing, try old system-prefix.md
    val legacyFallback =
      if jarFallback.nonEmpty then jarFallback + "\n\n"
      else
        val is2 = getClass.getResourceAsStream("/system-prefix.md")
        if is2 != null then
          try scala.io.Source.fromInputStream(is2)(scala.io.Codec.UTF8).mkString.trim + "\n\n"
          finally is2.close()
        else ""
    new FileInjectionSource(
      "system-prefix-for-all",
      PathUtil.dataRoot / "prompts" / "system-prefix-for-all.md",
      fallback = legacyFallback
    )

  end systemPrefixForAll

  /**
   * System prefix for TEAM agents only: ~/.nebflow/prompts/system-prefix-for-teams.md
   *  No JAR fallback — empty if file doesn't exist.
   */
  val systemPrefixForTeams: FileInjectionSource =
    new FileInjectionSource(
      "system-prefix-for-teams",
      PathUtil.dataRoot / "prompts" / "system-prefix-for-teams.md"
    )

  /**
   * System prefix for FLOW agents only: ~/.nebflow/prompts/system-prefix-for-flows.md
   *  No JAR fallback — empty if file doesn't exist.
   */
  val systemPrefixForFlows: FileInjectionSource =
    new FileInjectionSource(
      "system-prefix-for-flows",
      PathUtil.dataRoot / "prompts" / "system-prefix-for-flows.md"
    )

  /**
   * Manager prefix: ~/.nebflow/prompts/manager-prefix.md
   *  Injected only for Team Manager agents (name == "Manager").
   *  No JAR fallback — empty if file doesn't exist.
   */
  val managerPrefixSource: FileInjectionSource =
    new FileInjectionSource(
      "manager-prefix",
      PathUtil.dataRoot / "prompts" / "manager-prefix.md"
    )

  // ============================================================
  // Resolution helpers
  // ============================================================

  /** Resolve inherited rules.md from folder chain. Pure — mtime-cached per file. */
  private def resolveRules(state: AgentState, resources: SharedResources): Option[String] =
    state.folderId.flatMap { fid =>
      RulesStore.resolveInheritedRules(fid, id => resources.sessionStore.getFolderParentId(id))
    }

  /** Merge project rules (from projects dir) and folder rules (personal) into a single block. */
  private def mergeRules(project: Option[String], folder: Option[String]): Option[String] =
    (project, folder) match
      case (None, None) => None
      case (Some(p), None) => Some(p)
      case (None, Some(f)) => Some(f)
      case (Some(p), Some(f)) => Some(s"$p\n\n---\n\n$f")

  // ============================================================
  // AGENTS.md 注入（§E.2，project-architecture phase2 设计 §E）
  // ============================================================

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.agent")

  /** E.2 长度护栏：>16KB（16*1024 字节）截断 + 尾注（提示词膨胀防护）。 */
  val AgentsMdMaxBytes: Int = 16 * 1024

  /** E.2 接收面 gating：注入对象 = project 分发器 + 全部 node 会话；Nebula 与
    * 双轨 team/flow 会话不注入。
    *
    * 基准取舍（任务书建议 projectRoot.isDefined ± converged 名单，均否决留档）：
    *  - projectRoot.isDefined 不可作主基准：WebSocketRoutes.doSpawnRootAgent 把
    *    全部 WS 根会话（含 Nebula）的 projectRoot fallback 到 ~/.nebflow/projects
    *    ——Nebula 恒 Some，单基准会误注入。
    *  - converged 名单（project-dispatcher + general）不可作叠加/主基准：node
    *    会话 agent 名 = NodeDef.agent 任意声明值（NodeEngine loadAgent(node.agent)），
    *    名单覆盖不了「全部 node 会话」。
    *  - 采用 sandboxEnabled（spawn 形态信号）：全仓唯二置位点 NodeEngine（节点
    *    spawn）+ ProjectActor（分发器 spawn）＝接收面本身（2a §A.6 spec 钉死
    *    「仅 project 节点/分发器置位」）；Nebula/team/flow/Delegate/SubTask 默认
    *    false → 不注入。projectRoot 非空作读取源 + 空串护栏。
    * 公开供 spec 断言（参照 skillCatalogEnabledFor 同文件先例）。 */
  def agentsMdEnabledFor(sandboxEnabled: Boolean, projectRoot: Option[String]): Boolean =
    sandboxEnabled && projectRoot.exists(_.nonEmpty)

  /** E.2 读取：`<projectRoot>/AGENTS.md`，每 turn 重读盘（对齐 rulesMd 同机制）。
    * 旧位 `.nebflow/Agent.md` 残留 → 仅 WARN + 回落读根文件——迁移动作本体在
    * ProjectStore.load（E.3），此处置严禁文件搬家（每 turn 读路径带写副作用会
    * 放大竞态面）；挂载即迁移，残留只出现在 load 不可达的异常态，WARN 暴露即可。
    * >16KB 按字节截断 + 尾注 `[AGENTS.md truncated]`（截断切点若落在多字节
    * UTF-8 序列内，残码由解码器替换，不回写盘面）。路径解析失败（脏 projectRoot
    * 字符串）→ None，不中断 turn。 */
  def resolveAgentsMd(projectRoot: Option[String]): IO[Option[String]] =
    projectRoot.filter(_.trim.nonEmpty) match
      case None => IO.pure(None)
      case Some(root) =>
        IO.blocking {
          scala.util.Try(os.Path(root)).toOption.flatMap { base =>
            val legacy = base / ".nebflow" / "Agent.md"
            if os.exists(legacy) then
              logger.warnSync(
                s"[agents-md] '$legacy' still present (migration runs at ProjectStore.load) — reading workspace-root AGENTS.md"
              )
            val p = base / "AGENTS.md"
            if os.exists(p) then
              val bytes = java.nio.file.Files.readAllBytes(p.toNIO)
              val text =
                if bytes.length > AgentsMdMaxBytes then
                  new String(bytes, 0, AgentsMdMaxBytes, java.nio.charset.StandardCharsets.UTF_8) +
                    "\n\n[AGENTS.md truncated]"
                else new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
              Option(text.trim).filter(_.nonEmpty)
            else None
          }
        }

  /**
   * Always returns the projects directory: ~/.nebflow/projects/<folderName>/
   *  This is where project-level config lives (NEBFLOW.md, agents/, flows/).
   *  Independent of agent — shared across all agents working on the same project.
   */
  private def resolveProjectsDir(
    folderId: Option[String],
    resources: SharedResources,
    agentName: String
  ): IO[Option[os.Path]] =
    folderId match
      case Some(fid) =>
        val folderName = resources.sessionStore.getFolderName(fid).getOrElse(fid.take(8))
        val path = PathUtil.dataRoot / "projects" / folderName
        IO.blocking {
          if !os.exists(path) then os.makeDir.all(path)
          Some(path)
        }
      case None => IO.pure(None)

  /** Resolve projectRoot from folder chain. */
  private def resolveProjectRoot(
    folderId: Option[String],
    resources: SharedResources,
    agentName: String
  ): IO[Option[String]] =
    folderId match
      case Some(fid) =>
        for
          resolvedRoot <- resources.sessionStore.resolveProjectRoot(Some(fid))
          effectiveRoot <- resolvedRoot match
            case Some(pr) => IO.pure(Some(pr))
            case None =>
              val folderName = resources.sessionStore.getFolderName(fid).getOrElse(fid.take(8))
              val defaultPath = PathUtil.dataRoot / "projects" / folderName
              IO.blocking {
                if !os.exists(defaultPath) then os.makeDir.all(defaultPath)
                Some(defaultPath.toString)
              }
        yield effectiveRoot
      case None => IO.pure(None)

  // ============================================================
  // Git branch detection (file-based, zero subprocess overhead)
  // ============================================================

  /** Git info: branch name + worktree flag. */
  case class GitInfo(branch: String, isWorktree: Boolean)

  private val RefPrefix = "ref: refs/heads/"
  private val GitdirPrefix = "gitdir:"

  /**
   * Detect current git branch by reading .git/HEAD directly.
   * Zero subprocess overhead — pure file I/O.
   * Supports worktrees (where .git is a file, not a directory).
   * Returns None if the directory is not a git repo.
   */
  def detectGitBranch(projectRoot: Option[String]): IO[Option[GitInfo]] =
    projectRoot match
      case Some(root) =>
        IO.blocking {
          try
            val dotGit = java.nio.file.Paths.get(root, ".git")
            if !java.nio.file.Files.exists(dotGit) then None
            else if java.nio.file.Files.isDirectory(dotGit) then
              // Standard repo: .git/HEAD
              readHeadFile(dotGit.resolve("HEAD"), isWorktree = false)
            else if java.nio.file.Files.isRegularFile(dotGit) then
              // Worktree or submodule: .git is a file containing "gitdir: <path>"
              val content = java.nio.file.Files.readString(dotGit).trim
              if content.startsWith(GitdirPrefix) then
                val gitdirPath = java.nio.file.Paths.get(content.substring(GitdirPrefix.length).trim)
                val headFile = gitdirPath.resolve("HEAD")
                readHeadFile(headFile, isWorktree = true)
              else None
            else None
          catch case _: Exception => None
        }
      case None => IO.pure(None)

  /** Parse a HEAD file and extract branch name (or detached HEAD hash). */
  private def readHeadFile(headPath: java.nio.file.Path, isWorktree: Boolean): Option[GitInfo] =
    try
      val content = java.nio.file.Files.readString(headPath).trim
      if content.startsWith(RefPrefix) then Some(GitInfo(content.substring(RefPrefix.length), isWorktree))
      else if content.length >= 7 then
        // Detached HEAD — show short hash
        Some(GitInfo(s"(${content.substring(0, 7)})", isWorktree))
      else None
    catch case _: Exception => None

  /**
   * Check for git branch change and produce a reminder if the branch has changed.
   * Returns (reminder, currentGitInfo).
   *
   * Notification policy:
   *   - First detection (None → Some):  silent
   *   - Branch changed (Some → Some):   notify
   *   - Detection lost (Some → None):   silent (likely transient I/O issue)
   *   - No git (None → None):           silent
   */
  private def checkBranchChange(
    projectRoot: Option[String],
    lastBranch: Option[String]
  ): IO[(Option[SystemReminder], Option[String])] =
    detectGitBranch(projectRoot).map { currentInfo =>
      val currentBranch = currentInfo.map(_.branch)
      if currentBranch != lastBranch then
        val reminder = (lastBranch, currentInfo) match
          case (Some(old), Some(info)) =>
            val wtNote = if info.isWorktree then " (in worktree)" else ""
            Some(
              SystemReminder(
                "gitBranch",
                s"Git branch changed from \"$old\" to \"${info.branch}\"$wtNote. " +
                  "All subsequent file operations now apply to the new branch."
              )
            )
          // First detection — silent
          case (None, _) => None
          // Detection lost — silent (transient I/O, not worth alarming)
          case (Some(_), None) => None
        (reminder, currentBranch)
      else (None, currentBranch)
      end if
    }

  // ============================================================
  // Memory block builder
  // ============================================================

  /**
   * Memory injection gate — pure so both branches are spec-covered
   * (HeadlessModeSpec); the refreshTurn touchpoint binds HeadlessMode.enabled.
   *
   * 2026-08-31 memory-system redesign (裁定①): ONLY Nebula carries memory.
   * Team agents have no memory anymore (User.md + team memory.md injection
   * removed); standalone agents never did. Headless (NEBFLOW_HEADLESS=1,
   * benchmark mode) skips memory entirely — previous-run state must not leak
   * into a fresh benchmark session.
   */
  def shouldInjectMemory(
    isWorker: Boolean,
    agentName: String,
    headless: Boolean = HeadlessMode.enabled
  ): Boolean =
    !headless && !isWorker && agentName == "Nebula"

  /**
   * Build a memory block string for system prompt injection.
   *
   * Reads memory levels and formats them into a single Markdown block:
   *   - User memory    (~/.nebflow/User.md)                 — global
   *   - Agent memory   (~/.nebflow/agents/Nebula/memory.md)  — Nebula
   *
   * 2026-08-31 裁定①: team agents no longer carry memory — the gate
   * (shouldInjectMemory) only admits Nebula, so teamName is always None here.
   *
   * Only levels that exist on disk are included.
   *
   * §6.2-2.5（2026-09-05 批次二机制五）：构建时顺带消费生命周期信号并按已加载
   * 内容计算字节（零额外 IO）——命中触发源或任一文件超 80% 软线时在记忆块尾部
   * 追加整理提醒（>80% 为即时任务措辞；纯渲染见 renderMemoryBlock）。
   * 注入侧仍不加截断（§3.3 裁定）——提醒是提示，不是截断。
   */
  def buildMemoryBlock(
    agentName: String,
    teamName: Option[String] = None
  ): String =
    val agentMemory = teamName match
      case Some(tn) => MemoryStore.loadTeamAgentMemory(tn, agentName)
      case None => MemoryStore.loadAgentMemory(agentName)
    renderMemoryBlock(
      MemoryStore.loadUserMemory,
      agentMemory,
      MemoryHygieneSignal.takePending()
    )
  end buildMemoryBlock

  /** 纯渲染（spec 直测面）：两记忆内容 + (restartPending, compactPending) 信号 →
    * 记忆块全文。文件字节直接由已加载内容计算（无第二次读盘）。 */
  def renderMemoryBlock(
    userContent: Option[String],
    agentContent: Option[String],
    lifecycleSignal: (Boolean, Boolean)
  ): String =
    val sections = List(
      userContent.map(content => s"## User Memory\n\n$content"),
      agentContent.map(content => s"## Agent Memory\n\n$content")
    ).flatten

    val base =
      if sections.isEmpty then ""
      else
        s"""# Memory
           |
           |Your memory is below. Entries with →id have detail files at `~/.nebflow/memory/{id}.md` — read them when the scenario matches.
           |
           |${sections.mkString("\n\n")}""".stripMargin

    val notice = memoryHygieneNotice(userContent, agentContent, lifecycleSignal)
    if notice.isEmpty then base
    else if base.isEmpty then notice
    else base + "\n\n---\n\n" + notice
  end renderMemoryBlock

  /** 生命周期整理提醒（§6.2-2.5）：任一文件 >80% 软线 → 即时任务措辞（当轮安排
    * 整理，不等周日）；否则重启/压缩事件命中 → 轻量清扫提示；两者皆无 → 空串。 */
  def memoryHygieneNotice(
    userContent: Option[String],
    agentContent: Option[String],
    lifecycleSignal: (Boolean, Boolean)
  ): String =
    val bytesOf = (s: Option[String]) => s.map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong).getOrElse(0L)
    val userBytes = bytesOf(userContent)
    val agentBytes = bytesOf(agentContent)
    val (restartPending, compactPending) = lifecycleSignal

    val userOver = userBytes > nebflow.service.MemoryBudget.UserSoftBytes
    val agentOver = agentBytes > nebflow.service.MemoryBudget.AgentSoftBytes

    if userOver || agentOver then
      val lines = List(
        Option.when(userOver)(
          s"- ~/.nebflow/User.md: $userBytes bytes (soft line ${nebflow.service.MemoryBudget.UserSoftBytes}, hard ${nebflow.service.MemoryBudget.UserHardBytes})"),
        Option.when(agentOver)(
          s"- ~/.nebflow/agents/Nebula/memory.md: $agentBytes bytes (soft line ${nebflow.service.MemoryBudget.AgentSoftBytes}, hard ${nebflow.service.MemoryBudget.AgentHardBytes})")
      ).flatten
      s"""## Memory hygiene — IMMEDIATE TASK
         |
         |A memory file is over the 80% budget line:
         |${lines.mkString("\n")}
         |
         |Schedule a consolidation pass THIS TURN (memory-consolidation skill), do not wait for the weekly audit: over-budget memory taxes every future session, and the write-side gate will start rejecting appends at the hard line. Trim stale T2 batch sections, superseded rulings and unpromoted Dream entries first (取代而非追加; replace_section for section-level cleanup).""".stripMargin
    else if restartPending || compactPending then
      val cause = (restartPending, compactPending) match
        case (true, true)  => "The host just restarted AND your memory was just compacted"
        case (true, false) => "The host just restarted"
        case _             => "Your memory was just compacted"
      s"""## Memory hygiene
         |
         |$cause. Restart/compaction closes out T2 status entries (pending-reboot lists, batch ledgers) and ages T3 Dream entries. If you noticed stale state while resuming, run a quick consolidation pass (memory-consolidation skill) — trim closed-out entries instead of letting them accumulate to the budget line.""".stripMargin
    else ""
  end memoryHygieneNotice

  // ============================================================
  // Main entry point
  // ============================================================

  /**
   * Refresh context for the current turn.
   *
   * All sources are resolved fresh from disk (mtime-cached so unchanged
   * files cost only a stat() syscall). Returns TurnContext with current values.
   */
  /**
   * Load the CURRENT AgentDef for a running actor — the single refresh
   * source shared by the schema layer (refreshTurn → request.tools) and the
   * executor gate (AgentCore.pipeToolExecutions → call filtering +
   * ToolContext.agentDef). Keeping both layers on this one source is what
   * makes panel edits (flows whitelist, tools) take effect on the running
   * actor mid-session.
   *
   * Team agents resolve from the team dir, global agents from the agents
   * dir; presentation fields (avatar/displayName/voiceEnabled) are carried
   * over from the actor-startup def because AgentEntry doesn't carry them.
   * Returns None when neither disk source has the agent — callers fall back
   * to the actor-startup snapshot.
   */
  def loadCurrentDef(
    teamNameOpt: Option[String],
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[Option[AgentDef]] =
    // Team agents reload their def from the team dir every turn so panel
    // edits (e.g. PUT /api/agents/:name/model) take effect on the running
    // actor. agentLibrary.get only scans the GLOBAL agents dir — for team
    // agents it returns None and the code would silently keep using the
    // actor-startup snapshot (MailTool/FlowTreeActor loadTeamAgent result).
    // loadTeamAgent checks teams/<team>/agents/<name>/ first, then global.
    teamNameOpt match
      case Some(teamName) =>
        EntityLoader.loadTeamAgent(teamName, agentDef.name).map { entryOpt =>
          entryOpt.map(_.toAgentDef.copy(
            // AgentEntry doesn't carry presentation fields — keep whatever
            // the running actor already resolved (avatar from panel config).
            avatar = agentDef.avatar,
            displayName = agentDef.displayName,
            voiceEnabled = agentDef.voiceEnabled
          )).map(applyRuntimeOverrides(agentDef, _))
        }
      case None => resources.agentLibrary.get(agentDef.name).map(_.map(applyRuntimeOverrides(agentDef, _)))

  /**
   * Re-apply ALL runtime injections made at spawn time onto the freshly
   * reloaded disk def. The per-turn reload (panel edits take effect on the
   * running actor) must not silently drop spawn-time overrides:
   *
   *  - modelOverride (#291: Delegate/SubTask `preset` param) — wins over
   *    disk/panel edits for the actor's lifetime;
   *  - flowContract + the FlowReport tool append (#406: FlowDagExecutor
   *    injects both per node via `baseDef.copy(...)`). FlowReport is only
   *    fixed-injected for category=flow agents; dynamic flows reuse
   *    standalone/team agents (e.g. Explorer) whose disk def lacks it, so a
   *    bare reload silently strips the verdict tool and the node can never
   *    report a structured verdict (strictVerdict switch nodes then FAIL).
   */
  private def applyRuntimeOverrides(running: AgentDef, fresh: AgentDef): AgentDef =
    val withModel = running.modelOverride match
      case Some(cfg) => fresh.copy(model = Some(cfg), preset = running.preset, modelOverride = Some(cfg))
      case None => fresh
    val tools =
      if running.tools.contains("FlowReport") && !withModel.tools.contains("FlowReport") then
        withModel.tools :+ "FlowReport"
      else withModel.tools
    withModel.copy(
      tools = tools,
      flowContract = if running.flowContract.nonEmpty then running.flowContract else withModel.flowContract,
      // 阶段 2b Plugins（§B.4 第 3/4 步）：node 分配是 spawn 时运行时注入
      // （NodeEngine 写入 pluginMcpServers/pluginTools，不落 agent.json）——
      // 每 turn 热重载不得冲掉（flowContract 同款保活先例）。
      pluginMcpServers = if running.pluginMcpServers.nonEmpty then running.pluginMcpServers else withModel.pluginMcpServers,
      pluginTools = if running.pluginTools.nonEmpty then running.pluginTools else withModel.pluginTools
    )

  def refreshTurn(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[TurnContext] =
    for
      // Detect team membership once — used for both the def refresh source
      // and the memory block below.
      teamNameOpt <- state.sessionId match
        case Some(sid) => nebflow.core.flow.TeamSessionRegistry.teamOfSession(sid)
        case None => IO.pure(None)
      freshDefOpt <- loadCurrentDef(teamNameOpt, resources, agentDef)
      globalDef = freshDefOpt.getOrElse(agentDef)
      // SubTask workers are leaf task-execution pipelines: strip all team /
      // manager / memory context — the prompt is their only context source.
      isWorker = state.isSubTaskWorker
      // Load all-agent prefix + category-specific prefix
      allPrefixRaw <- systemPrefixForAll.get
      categoryPrefix <-
        if isWorker then IO.pure("")
        else
          globalDef.category match
            case "team" => systemPrefixForTeams.get
            case "flow" => systemPrefixForFlows.get
            case _ => IO.pure("")
      // Manager prefix: only for Team Manager agents
      managerPrefix <-
        if isWorker || globalDef.name != "Manager" then IO.pure("")
        else managerPrefixSource.get
      systemPrefixRaw = allPrefixRaw + categoryPrefix + managerPrefix
      systemPrefix = systemPrefixRaw
      projectRoot <- resolveProjectRoot(state.folderId, resources, globalDef.name)
      // Projects directory: ~/.nebflow/projects/<folderName>/
      projectsDir <- resolveProjectsDir(state.folderId, resources, globalDef.name)
      // Load project rules from projects dir + folder rules (personal)
      projectRules <- projectsDir match
        case Some(dir) =>
          IO.blocking {
            val p = dir / "NEBFLOW.md"
            if os.exists(p) then Some(os.read(p).trim).filter(_.nonEmpty) else None
          }
        case None => IO.pure(None)
      folderRules = resolveRules(state, resources)
      rulesMd = mergeRules(projectRules, folderRules)
      // E.2 AGENTS.md 注入（每 turn 重读盘，对齐 rulesMd 同机制）。读取基准 =
      // SessionContext.projectRoot（NodeEngine/ProjectActor spawn 写入的
      // workspace/worktree 路径，§A.6 唯一权威）——node/分发器会话无 folderId，
      // 上方 folderId 派生的 projectRoot 对它们恒 None，不可作基准（gating 取舍
      // 见 agentsMdEnabledFor）。随 systemStable 在 lifecycle 节点生效。
      agentsMd <-
        if agentsMdEnabledFor(state.sandboxEnabled, state.projectRoot) then
          resolveAgentsMd(state.projectRoot)
        else IO.pure(None)
      thinkingConfig <- resources.thinkingConfigRef.get
      (branchReminder, currentBranch) <- checkBranchChange(projectRoot, state.gitBranch)
      // D.1-12（阶段 2d）：skill 目录注入新模型停注——node/general 会话不再
      // 注入 skill 目录段（§B.4 plugin 全文注入取代「目录+自读」，裁定 8/9）。
      // Nebula 保留（skill-creator alwaysVisible：Nebula 用目录造 skill）；
      // legacy 会话保留至阶段 3。refreshTurn 是每轮（含首条消息）的唯一生产点，
      // 置空后 order 800 section（condition=nonEmpty）自然不渲染。
      skillCatalog <-
        if skillCatalogEnabledFor(globalDef.name) then SkillService.buildPerAgentCatalog(globalDef.skills)
        else IO.pure("")
      flowCatalog <-
        if isWorker then IO.pure("")
        else SkillService.buildPerAgentFlowCatalog(globalDef.flows)
      teamCatalog <-
        if isWorker then IO.pure("")
        else buildTeamCatalogForSession(state.sessionId)
      // Memory: only Nebula (2026-08-31 裁定①). Team agents, standalone
      // agents and SubTask workers get no memory block — clean context.
      // Headless (NEBFLOW_HEADLESS=1): none at all — benchmark determinism.
      memoryBlock =
        if shouldInjectMemory(isWorker, globalDef.name) then buildMemoryBlock(globalDef.name, teamNameOpt)
        else ""
    yield TurnContext(
      globalDef,
      systemPrefix,
      projectRoot,
      rulesMd,
      agentsMd,
      thinkingConfig,
      branchReminder,
      currentBranch,
      skillCatalog,
      teamCatalog,
      flowCatalog,
      memoryBlock
    )

  /** Resolve projectRoot for ToolContext (called from buildToolContext). */
  def resolveProjectRootForTool(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[Option[String]] =
    resolveProjectRoot(state.folderId, resources, agentDef.name)

  /** D.1-12（阶段 2d）：skill 目录注入开关——收敛三角色中仅 Nebula 保留
    * （skill-creator alwaysVisible，Nebula 用它造 skill）；general（node 会话
    * 模版）与 project-dispatcher 停注（plugin 全文注入取代「目录+自读」，
    * 裁定 8/9）；legacy agent 保留至阶段 3。公开供 spec 断言。 */
  def skillCatalogEnabledFor(agentName: String): Boolean =
    !AgentCore.ConvergedAgentNames.contains(agentName) || agentName == "Nebula"

  /**
   * Build Team catalog for system prompt injection.
   *  Detects team membership from the session's FlowMembership registration.
   *  - Team agents: see their own team's members + flows (progressive disclosure)
   *  - Nebula / standalone: see global Teams & Flows overview
   */
  private def buildTeamCatalogForSession(sessionId: Option[String]): IO[String] =
    sessionId match
      case Some(sid) =>
        for
          teamNameOpt <- nebflow.core.flow.TeamSessionRegistry.teamOfSession(sid)
          result <- teamNameOpt match
            case Some(teamName) =>
              // Team agent: show team-specific catalog. The agents map MUST
              // cover team-local definitions (teams/<name>/agents/): lead and
              // members live there, and listAgents() only scans the GLOBAL
              // agents dir — passing it alone rendered "(agent not found)"
              // leads and empty member lists, which team Managers read as
              // "my members don't exist" and reported activation failures
              // without ever trying to Mail them. Mirror the runtime
              // resolution order (loadTeamAgent: team-local first, global
              // fallback) so the catalog shows exactly what Mail can reach.
              for
                team <- EntityLoader.loadTeam(teamName)
                globalAgents <- EntityLoader.listAgents()
                teamAgents <- EntityLoader.listTeamAgents(teamName)
                flows <- EntityLoader.listFlows()
                rules <- EntityLoader.loadTeamRules(teamName)
              yield team
                .map { t =>
                  val catalog = TeamCatalog.buildCatalog(t, globalAgents ++ teamAgents, flows)
                  if rules.nonEmpty then s"$catalog\n\n=== Team Rules: ${t.name} ===\n$rules\n=== End Team Rules ==="
                  else catalog
                }
                .getOrElse("")
            case None =>
              // Nebula or standalone: show global catalog
              for
                teams <- EntityLoader.listTeams()
                flows <- EntityLoader.listFlows()
                agents <- EntityLoader.listAgents()
              yield TeamCatalog.buildGlobalCatalog(teams, flows, agents)
        yield result
      case None =>
        // No session (e.g. very early init): show global catalog
        for
          teams <- EntityLoader.listTeams()
          flows <- EntityLoader.listFlows()
          agents <- EntityLoader.listAgents()
        yield TeamCatalog.buildGlobalCatalog(teams, flows, agents)

end ContextRefresher
