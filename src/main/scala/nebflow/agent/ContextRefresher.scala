package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.entity.{EntityLoader, TeamCatalog}
import nebflow.core.skill.SkillService
import nebflow.core.{PathUtil, SystemReminder, SystemReminders}
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
   * Build a memory block string for system prompt injection.
   *
   * Reads memory levels and formats them into a single Markdown block:
   *   - User memory    (~/.nebflow/User.md)                              — global
   *   - Agent memory   (~/.nebflow/agents/<name>/memory.md)               — Nebula
   *     or (~/.nebflow/teams/<team>/agents/<name>/memory.md)              — Team agents
   *
   * Only levels that exist on disk are included.
   */
  def buildMemoryBlock(
    agentName: String,
    teamName: Option[String] = None
  ): String =
    val agentMemory = teamName match
      case Some(tn) => MemoryStore.loadTeamAgentMemory(tn, agentName)
      case None => MemoryStore.loadAgentMemory(agentName)

    val sections = List(
      MemoryStore.loadUserMemory
        .map(content => s"## User Memory\n\n$content"),
      agentMemory
        .map(content => s"## Agent Memory\n\n$content")
    ).flatten

    if sections.isEmpty then ""
    else
      s"""# Memory
         |
         |Your memory is below. Entries with →id have detail files at `~/.nebflow/memory/{id}.md` — read them when the scenario matches.
         |
         |${sections.mkString("\n\n")}""".stripMargin
  end buildMemoryBlock

  // ============================================================
  // Main entry point
  // ============================================================

  /**
   * Refresh context for the current turn.
   *
   * All sources are resolved fresh from disk (mtime-cached so unchanged
   * files cost only a stat() syscall). Returns TurnContext with current values.
   */
  def refreshTurn(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[TurnContext] =
    for
      freshDefOpt <- resources.agentLibrary.get(agentDef.name)
      globalDef = freshDefOpt.getOrElse(agentDef)
      // Load all-agent prefix + category-specific prefix
      allPrefixRaw <- systemPrefixForAll.get
      categoryPrefix <- globalDef.category match
        case "team" => systemPrefixForTeams.get
        case "flow" => systemPrefixForFlows.get
        case _ => IO.pure("")
      // Manager prefix: only for Team Manager agents
      managerPrefix <- if globalDef.name == "Manager" then managerPrefixSource.get
                       else IO.pure("")
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
      thinkingConfig <- resources.thinkingConfigRef.get
      (branchReminder, currentBranch) <- checkBranchChange(projectRoot, state.gitBranch)
      skillCatalog <- SkillService.buildPerAgentCatalog(globalDef.skills)
      flowCatalog <- SkillService.buildPerAgentFlowCatalog(globalDef.flows)
      teamCatalog <- buildTeamCatalogForSession(state.sessionId)
      // Memory: only Nebula (standalone, name="Nebula") and team agents get memory.
      // Team agents get memory; standalone agents (Coder/Explorer/etc) don't.
      teamNameForMemory <- state.sessionId match
        case Some(sid) => nebflow.core.flow.TeamSessionRegistry.teamOfSession(sid)
        case None => IO.pure(None)
      isTeamAgent = teamNameForMemory.isDefined
      memoryBlock =
        if isTeamAgent || globalDef.name == "Nebula" then
          buildMemoryBlock(globalDef.name, teamNameForMemory)
        else ""
    yield TurnContext(
      globalDef,
      systemPrefix,
      projectRoot,
      rulesMd,
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
              // Team agent: show team-specific catalog
              for
                team <- EntityLoader.loadTeam(teamName)
                agents <- EntityLoader.listAgents()
                flows <- EntityLoader.listFlows()
                rules <- EntityLoader.loadTeamRules(teamName)
              yield team
                .map { t =>
                  val catalog = TeamCatalog.buildCatalog(t, agents, flows)
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
