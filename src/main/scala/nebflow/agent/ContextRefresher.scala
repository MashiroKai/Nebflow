package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.entity.{EntityLoader, TeamCatalog}
import nebflow.core.skill.SkillService
import nebflow.core.{PathUtil, SystemReminder, SystemReminders}
import nebflow.service.{MemoryStore, RulesStore, StrengthStore}

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
    systemPrefixSource
  )

  private val detailRefPattern = "→([a-zA-Z0-9]{4,12})".r

  /** System prefix: ~/.nebflow/system-prefix.md with JAR fallback. */
  val systemPrefixSource: FileInjectionSource =
    val jarFallback =
      val is = getClass.getResourceAsStream("/system-prefix.md")
      if is != null then
        try scala.io.Source.fromInputStream(is)(scala.io.Codec.UTF8).mkString.trim
        finally is.close()
      else ""
    new FileInjectionSource(
      "system-prefix",
      PathUtil.dataRoot / "system-prefix.md",
      fallback = if jarFallback.nonEmpty then jarFallback + "\n\n" else ""
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
   * Reads all memory levels and formats them into a single Markdown block:
   *   - User memory    (~/.nebflow/NEBFLOW.md)           — global, all agents
   *   - Agent memory   (~/.nebflow/agents/<name>/memory.md) — global, per agent
   *   - Project memory (~/.nebflow/projects/<proj>/memory/<name>.md) — per project+agent
   *
   * Only levels that exist on disk are included.
   */
  def buildMemoryBlock(
    agentName: String,
    folderId: Option[String],
    sessionId: Option[String],
    currentDelegateCount: Int = 0,
    projectMemory: Option[String] = None
  ): String =
    val sections = List(
      MemoryStore.loadUserMemory
        .map(content => filterByStrength(content, currentDelegateCount))
        .map(content => s"## User Memory\n\n$content"),
      MemoryStore
        .loadAgentMemory(agentName)
        .map(content => filterByStrength(content, currentDelegateCount))
        .map(content => s"## Agent Memory\n\n$content"),
      projectMemory
        .map(content => filterByStrength(content, currentDelegateCount))
        .map(content => s"## Project Memory\n\n$content")
    ).flatten

    if sections.isEmpty then ""
    else
      s"""# Memory
         |
         |Your memory is below. Entries with →id have detail files at `~/.nebflow/memory/{id}.md` — read them when the scenario matches.
         |
         |${sections.mkString("\n\n")}""".stripMargin
  end buildMemoryBlock

  /**
   * Filter memory content by strength. Lines with →id references are checked
   * against StrengthStore — entries below threshold are removed.
   * Lines without →id (short memory) are always kept.
   */
  private def filterByStrength(content: String, currentDelegateCount: Int): String =
    content
      .split("\n")
      .filter { line =>
        if line.trim.startsWith("- ") then
          detailRefPattern.findFirstMatchIn(line) match
            case Some(m) =>
              val id = m.group(1)
              StrengthStore.shouldInclude(s"memory.$id", currentDelegateCount)
            case None => true // Short memory, always keep
        else true // Non-entry lines (headers, blank lines), always keep
      }
      .mkString("\n")
  end filterByStrength

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
      systemPrefixRaw <- systemPrefixSource.get
      // Trim Memory writing guide for worker agents (depth >= 2) — they don't write memory
      systemPrefix = if state.depth >= 2 then
        val memStart = systemPrefixRaw.indexOf("## Memory")
        val memEnd = systemPrefixRaw.indexOf("## Session Management")
        if memStart >= 0 && memEnd > memStart then
          val before = systemPrefixRaw.substring(0, memStart)
          val after = systemPrefixRaw.substring(memEnd)
          before + "## Memory\n\nYour memory is injected into your system prompt every turn. It is persistent knowledge from past sessions.\n\n" + after
        else systemPrefixRaw
      else systemPrefixRaw
      projectRoot <- resolveProjectRoot(state.folderId, resources, globalDef.name)
      // Projects directory: ~/.nebflow/projects/<folderName>/
      projectsDir <- resolveProjectsDir(state.folderId, resources, globalDef.name)
      // Load project-level agent memory (supplements global agent memory)
      projectMem <- projectsDir match
        case Some(dir) =>
          IO.blocking {
            val p = dir / "memory" / s"${globalDef.name}.md"
            if os.exists(p) then Some(os.read(p).trim).filter(_.nonEmpty) else None
          }
        case None => IO.pure(None)
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
      skillCatalog <- SkillService.buildSkillCatalog(state.execution.delegateCount)
      teamCatalog <- buildTeamCatalogForSession(state.sessionId)
      memoryBlock = buildMemoryBlock(
        globalDef.name,
        state.folderId,
        state.sessionId,
        state.execution.delegateCount,
        projectMem
      )
      // Universal prompt: ~/.nebflow/prompts/universal.md
      universalPrompt <- IO.blocking {
        val p = PathUtil.dataRoot / "prompts" / "universal.md"
        if os.exists(p) then os.read(p).trim else ""
      }.handleErrorWith(_ => IO.pure(""))
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
      memoryBlock,
      universalPrompt
    )

  /** Resolve projectRoot for ToolContext (called from buildToolContext). */
  def resolveProjectRootForTool(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[Option[String]] =
    resolveProjectRoot(state.folderId, resources, agentDef.name)

  /** Build Team catalog for system prompt injection.
   *  Detects team membership from the session's FlowMembership registration.
   *  - Team agents: see their own team's members + flows (progressive disclosure)
   *  - Nebula / standalone: see global Teams & Flows overview */
  private def buildTeamCatalogForSession(sessionId: Option[String]): IO[String] =
    sessionId match
      case Some(sid) =>
        for
          teamNameOpt <- nebflow.core.flow.FlowMembership.flowOfSession(sid)
          result <- teamNameOpt match
            case Some(teamName) =>
              // Team agent: show team-specific catalog
              for
                team <- EntityLoader.loadTeam(teamName)
                agents <- EntityLoader.listAgents()
                flows <- EntityLoader.listFlows()
                rules <- EntityLoader.loadTeamRules(teamName)
              yield team.map { t =>
                val catalog = TeamCatalog.buildCatalog(t, agents, flows)
                if rules.nonEmpty then
                  s"$catalog\n\n=== Team Rules: ${t.name} ===\n$rules\n=== End Team Rules ==="
                else catalog
              }.getOrElse("")
            case None =>
              // Nebula or standalone: show global catalog
              for
                teams <- EntityLoader.listTeams()
                flows <- EntityLoader.listFlows()
              yield TeamCatalog.buildGlobalCatalog(teams, flows)
        yield result
      case None =>
        // No session (e.g. very early init): show global catalog
        for
          teams <- EntityLoader.listTeams()
          flows <- EntityLoader.listFlows()
        yield TeamCatalog.buildGlobalCatalog(teams, flows)

end ContextRefresher
