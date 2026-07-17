package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.skill.SkillService
import nebflow.core.{PathUtil, SystemReminder, SystemReminders}
import nebflow.service.RulesStore

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
 *   • gitBranch      — git rev-parse --abbrev-ref HEAD
 *   • memory files   — injected via MemoryAutoRead synthetic Read calls,
 *                       kept fresh by LiveFileTracker
 */
object ContextRefresher:

  /** Registered InjectionSources — for documentation and future management. */
  val promptSources: List[InjectionSource] = List(
    systemPrefixSource
  )

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
  // Git branch detection
  // ============================================================

  /**
   * Detect current git branch for the given project root.
   * Returns None if the directory is not a git repo.
   */
  private def detectGitBranch(projectRoot: Option[String]): IO[Option[String]] =
    projectRoot match
      case Some(root) =>
        IO.blocking {
          try
            val pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            pb.directory(new java.io.File(root))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = scala.io.Source.fromInputStream(proc.getInputStream)(scala.io.Codec.UTF8).mkString.trim
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if proc.exitValue() == 0 && output.nonEmpty && !output.startsWith("fatal:") then Some(output)
            else None
          catch case _: Exception => None
        }
      case None => IO.pure(None)

  /**
   * Check for git branch change and produce a reminder if the branch has changed.
   * Returns (reminder, currentBranch).
   */
  private def checkBranchChange(
    projectRoot: Option[String],
    lastBranch: Option[String]
  ): IO[(Option[SystemReminder], Option[String])] =
    detectGitBranch(projectRoot).map { currentBranch =>
      if currentBranch != lastBranch then
        val reminder = (lastBranch, currentBranch) match
          case (Some(old), Some(current)) =>
            Some(
              SystemReminder(
                "gitBranch",
                s"Git branch changed from \"$old\" to \"$current\". All subsequent file operations now apply to the new branch. " +
                  "If you have uncommitted work, verify it is on the intended branch before making changes."
              )
            )
          case (None, Some(current)) =>
            // First detection — just record, no alarm
            None
          case (Some(old), None) =>
            // Was a git repo, now not — could be worrying
            Some(
              SystemReminder(
                "gitBranch",
                s"Git branch was \"$old\" but the project is no longer detected as a git repository. " +
                  "File operations will continue but version control tracking may be lost."
              )
            )
          case (None, None) => None
        (reminder, currentBranch)
      else (None, currentBranch)
    }

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
      freshDef = freshDefOpt.getOrElse(agentDef)
      systemPrefix <- systemPrefixSource.get
      projectRoot <- resolveProjectRoot(state.folderId, resources, freshDef.name)
      rulesMd = resolveRules(state, resources)
      thinkingConfig <- resources.thinkingConfigRef.get
      (branchReminder, currentBranch) <- checkBranchChange(projectRoot, state.gitBranch)
      skillCatalog <- SkillService.buildSkillCatalog()
    yield TurnContext(
      freshDef,
      systemPrefix,
      projectRoot,
      rulesMd,
      thinkingConfig,
      branchReminder,
      currentBranch,
      skillCatalog
    )

  /** Resolve projectRoot for ToolContext (called from buildToolContext). */
  def resolveProjectRootForTool(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[Option[String]] =
    resolveProjectRoot(state.folderId, resources, agentDef.name)

end ContextRefresher
