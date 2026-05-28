package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.{SystemReminder, SystemReminders}
import nebflow.service.{MemoryStore, RulesStore}

/**
 * Unified context refresh for session-scoped resources.
 *
 * == Lifecycle sources (resolved once, cached until reset) ==
 *   • system-prefix  — FileInjectionSource with mtime cache
 *   • agentDef       — AgentLibrary.get (mtime-cached directory)
 *   • memoryBlock    — 3-level index files → MemoryStore (mtime-cached)
 *   • rulesMd        — folder chain → RulesStore.resolveInheritedRules (mtime-cached)
 *   • projectRoot    — folder chain → SessionStore.resolveProjectRoot
 *
 * == EveryTurn sources (re-resolved every turn) ==
 *   • thinkingConfig — global Ref[IO, ThinkingConfig]
 *   • fileChanges    — FileChangeTracker (5s debounce)
 *
 * == Lifecycle reset triggers ==
 *   • /clear (ResetSession) — clears messages + lifecycle
 *   • Compaction complete   — lifecycle re-resolved on next turn
 *   • Model switch          — lifecycle re-resolved on next turn
 *
 * Memory injection uses progressive disclosure: only the index layer is injected.
 * Agents can Read the full file on demand to access the detail layer.
 */
object ContextRefresher:

  /** Registered InjectionSources — for documentation and future management. */
  val promptSources: List[InjectionSource] = List(
    systemPrefixSource
  )

  /**
   * System prefix: ~/.nebflow/system-prefix.md with JAR fallback.
   *  Lifecycle source — resolved once, changes only on lifecycle reset.
   */
  val systemPrefixSource: FileInjectionSource =
    val jarFallback =
      val is = getClass.getResourceAsStream("/system-prefix.md")
      if is != null then
        try scala.io.Source.fromInputStream(is)(scala.io.Codec.UTF8).mkString.trim
        finally is.close()
      else ""
    new FileInjectionSource(
      "system-prefix",
      InjectionMode.Lifecycle,
      os.home / ".nebflow" / "system-prefix.md",
      fallback = if jarFallback.nonEmpty then jarFallback + "\n\n" else ""
    )

  // ============================================================
  // Lifecycle resolution (first turn or after reset)
  // ============================================================

  /** Build memory block from index layers. Uses mtime-cached reads. */
  private def buildMemoryBlock(agentDef: AgentDef, folderId: Option[String]): String =
    val userMemory = MemoryStore.loadUserMemory
    val agentMemory = MemoryStore.loadAgentMemory(agentDef.name)
    val folderMemory = folderId.flatMap(MemoryStore.loadFolderMemory)
    List(
      folderMemory.map(c => s"# Memory — Folder\n$c"),
      agentMemory.map(c => s"# Memory — Agent\n$c"),
      userMemory.map(c => s"# Memory — User\n$c")
    ).flatten.mkString("\n\n")

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
              val defaultPath = os.home / ".nebflow" / "agents" / agentName / "projects" / folderName
              IO.blocking {
                if !os.exists(defaultPath) then os.makeDir.all(defaultPath)
                Some(defaultPath.toString)
              }
        yield effectiveRoot
      case None => IO.pure(None)

  /** Resolve all Lifecycle sources from disk. Called once per session lifecycle. */
  private def resolveLifecycle(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[LifecycleContext] =
    for
      freshDefOpt <- resources.agentLibrary.get(agentDef.name)
      freshDef = freshDefOpt.getOrElse(agentDef)
      systemPrefix <- systemPrefixSource.get
      projectRoot <- resolveProjectRoot(state.folderId, resources, freshDef.name)
      rulesMd = resolveRules(state, resources)
      memoryBlock = buildMemoryBlock(freshDef, state.folderId)
    yield LifecycleContext(systemPrefix, freshDef, memoryBlock, rulesMd, projectRoot)

  // ============================================================
  // Main entry point
  // ============================================================

  /**
   * Refresh context for the current turn.
   *
   * Returns (TurnContext, Option[LifecycleContext]):
   *   - TurnContext always contains current values.
   *   - Option[LifecycleContext] is Some only on first turn (to be cached by caller).
   *
   * Lifecycle sources use SessionContext.lifecycle if cached.
   * EveryTurn sources (thinkingConfig, fileChanges) are always fresh.
   */
  def refreshTurn(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[(TurnContext, Option[LifecycleContext])] =

    state.lifecycle match
      case Some(lc) =>
        for
          thinkingConfig <- resources.thinkingConfigRef.get
          fileChanges <- resources.fileChangeTracker.checkChanges()
        yield (
          TurnContext(
            lc.agentDef,
            lc.systemPrefix,
            lc.projectRoot,
            lc.rulesMd,
            lc.memoryBlock,
            thinkingConfig,
            fileChanges
          ),
          None
        )

      case None =>
        for
          lc <- resolveLifecycle(state, resources, agentDef)
          thinkingConfig <- resources.thinkingConfigRef.get
          fileChanges <- resources.fileChangeTracker.checkChanges()
        yield (
          TurnContext(
            lc.agentDef,
            lc.systemPrefix,
            lc.projectRoot,
            lc.rulesMd,
            lc.memoryBlock,
            thinkingConfig,
            fileChanges
          ),
          Some(lc)
        )
  end refreshTurn

  /**
   * Resolve projectRoot for ToolContext (called from buildToolContext).
   * Uses cached LifecycleContext if available, otherwise resolves from disk.
   */
  def resolveProjectRootForTool(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[Option[String]] =
    state.lifecycle match
      case Some(lc) => IO.pure(lc.projectRoot)
      case None => resolveProjectRoot(state.folderId, resources, agentDef.name)

end ContextRefresher
