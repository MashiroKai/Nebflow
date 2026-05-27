package nebflow.agent

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.{SystemReminder, SystemReminders}
import nebflow.service.{MemoryStore, RulesStore}

/**
 * Unified context refresh for all session-scoped resources.
 *
 * == Injection sources ==
 * Every piece of prompt content is tracked as an [[InjectionSource]] with
 * per-source mtime caching. All sources are currently [[EveryTurn]] — meaning
 * file changes take effect on the next turn. Classification into [[Lifecycle]]
 * (session-lifetime) will follow.
 *
 *   • system-prefix  — FileInjectionSource with mtime cache (NEW)
 *   • agentDef       — AgentLibrary.get (mtime-cached directory)
 *   • projectRoot    — folder chain → SessionStore.resolveProjectRoot
 *   • rulesMd        — folder chain → RulesStore.resolveInheritedRules (mtime-cached)
 *   • memoryBlock    — 3-level memory files → MemoryStore (mtime-cached)
 *   • thinkingConfig — global Ref[IO, ThinkingConfig]
 *   • fileChanges    — FileChangeTracker (5s debounce)
 *
 * Detail files live at ~/.nebflow/memory/{hash}.md. Agents read them on demand
 * when they see →hash references in memory entries.
 */
object ContextRefresher:

  /** Registered InjectionSources — for documentation and future Lifecycle management. */
  val promptSources: List[InjectionSource] = List(
    systemPrefixSource
  )

  /**
   * System prefix: ~/.nebflow/system-prefix.md with JAR fallback.
   *  Mtime-cached — only re-reads when file changes.
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
      InjectionMode.EveryTurn,
      os.home / ".nebflow" / "system-prefix.md",
      fallback = if jarFallback.nonEmpty then jarFallback + "\n\n" else ""
    )

  /**
   * Build the memory block for system prompt injection.
   * Uses mtime-cached reads — cheap to call every turn.
   */
  def buildMemoryBlock(agentDef: AgentDef, folderId: Option[String] = None): String =
    val userMemory = MemoryStore.loadUserMemory
    val agentMemory = MemoryStore.loadAgentMemory(agentDef.name)
    val folderMemory = folderId.flatMap(MemoryStore.loadFolderMemory)
    val memorySections = List(
      folderMemory.map(c => s"# Memory — Folder\n$c"),
      agentMemory.map(c => s"# Memory — Agent\n$c"),
      userMemory.map(c => s"# Memory — User\n$c")
    ).flatten
    memorySections.mkString("\n\n")
  end buildMemoryBlock

  /**
   * Resolve projectRoot and rulesMd from the session's folder chain.
   *  Called every turn so folder-level config changes take effect immediately.
   */
  def refreshFolderContext(
    state: AgentState,
    resources: SharedResources,
    agentName: String
  ): IO[(Option[String], Option[String])] =
    state.folderId match
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
          resolvedRules = RulesStore.resolveInheritedRules(
            fid,
            id => resources.sessionStore.getFolderParentId(id)
          )
        yield (effectiveRoot, resolvedRules)
      case None => IO.pure((None, None))
  end refreshFolderContext

  /** Refresh all EveryTurn resources — called at the start of each pipeLlmCall. */
  def refreshTurn(
    state: AgentState,
    resources: SharedResources,
    agentDef: AgentDef
  ): IO[TurnContext] =
    for
      // agentDef from library (mtime-cached — 1 stat() on agents dir)
      freshDefOpt <- resources.agentLibrary.get(agentDef.name)
      freshDef = freshDefOpt.getOrElse(agentDef)
      // projectRoot + rulesMd from folder chain
      (projectRoot, rulesMd) <- refreshFolderContext(state, resources, freshDef.name)
      // system prefix (mtime-cached — 1 stat() if file unchanged)
      systemPrefix <- systemPrefixSource.get
      // memory from files (mtime-cached — 3-4 cheap stat() calls per turn)
      memoryBlock = buildMemoryBlock(freshDef, state.folderId)
      // thinking config from global ref
      thinkingConfig <- resources.thinkingConfigRef.get
      // file change detection (5s debounce)
      fileChanges <- resources.fileChangeTracker.checkChanges()
    yield TurnContext(freshDef, systemPrefix, projectRoot, rulesMd, memoryBlock, thinkingConfig, fileChanges)
  end refreshTurn

end ContextRefresher
