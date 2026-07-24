package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
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
              val defaultPath = PathUtil.dataRoot / "agents" / agentName / "projects" / folderName
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
      if content.startsWith(RefPrefix) then
        Some(GitInfo(content.substring(RefPrefix.length), isWorktree))
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
    }

  // ============================================================
  // Memory block builder
  // ============================================================

  /**
   * Build a memory block string for system prompt injection.
   *
   * Reads all four memory levels (User, Agent, Folder, Session) and formats
   * them into a single Markdown block. Only levels that exist on disk are
   * included. This replaces the old MemoryAutoRead synthetic Read messages.
   */
  def buildMemoryBlock(
    agentName: String,
    folderId: Option[String],
    sessionId: Option[String],
    currentDelegateCount: Int = 0
  ): String =
    val sections = List(
      MemoryStore.loadUserMemory
        .map(content => filterByStrength(content, currentDelegateCount))
        .map(content => s"## User Memory\n\n$content"),
      MemoryStore
        .loadAgentMemory(agentName)
        .map(content => filterByStrength(content, currentDelegateCount))
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
      // Preserve runtime tools list — only refresh configuration content from disk.
      freshDef = freshDefOpt.map(_.copy(tools = agentDef.tools)).getOrElse(agentDef)
      systemPrefix <- systemPrefixSource.get
      projectRoot <- resolveProjectRoot(state.folderId, resources, freshDef.name)
      rulesMd = resolveRules(state, resources)
      thinkingConfig <- resources.thinkingConfigRef.get
      (branchReminder, currentBranch) <- checkBranchChange(projectRoot, state.gitBranch)
      skillCatalog <- SkillService.buildSkillCatalog(state.execution.delegateCount)
      flowCatalog <- nebflow.core.flow.FlowDefLoader.buildFlowCatalog()
      memoryBlock = buildMemoryBlock(freshDef.name, state.folderId, state.sessionId, state.execution.delegateCount)
    yield TurnContext(
      freshDef,
      systemPrefix,
      projectRoot,
      rulesMd,
      thinkingConfig,
      branchReminder,
      currentBranch,
      skillCatalog,
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

end ContextRefresher
