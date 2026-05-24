package nebflow.agent

import cats.effect.IO
import nebflow.core.{SystemReminder, SystemReminders}
import nebflow.service.{MemoryStore, RulesStore}

/**
 * Unified context refresh for all session-scoped resources.
 *
 * All resources are EveryTurn — re-resolved at the start of each pipeLlmCall via [[refreshTurn]].
 *   • agentDef      — AgentLibrary.get (mtime-cached directory)
 *   • projectRoot   — folder chain → SessionStore.resolveProjectRoot
 *   • rulesMd       — folder chain → RulesStore.resolveInheritedRules
 *   • memoryBlock   — 3-level files → MemoryStore (mtime-cached)
 *   • thinkingConfig — global Ref[IO, ThinkingConfig]
 *   • fileChanges   — FileChangeTracker (5s debounce)
 */
object ContextRefresher:

  /** Build the full memory block: file contents + guide + file paths.
   *  Uses mtime-cached reads — cheap to call every turn (3 stat() syscalls if unchanged).
   */
  def buildMemoryBlock(agentDef: AgentDef, sessionId: Option[String]): String =
    val userMemory = MemoryStore.loadUserMemory
    val agentMemory = MemoryStore.loadAgentMemory(agentDef.name)
    val sessionMemory = sessionId.flatMap(MemoryStore.loadSessionMemory)
    val memorySections = List(
      sessionMemory.map(c => s"# Memory — Session Context\n$c"),
      agentMemory.map(c => s"# Memory — Agent\n$c"),
      userMemory.map(c => s"# Memory — User Preferences\n$c")
    ).flatten

    val memoryFiles = sessionId match
      case Some(sid) =>
        s"""# Memory Files
           |- Session context (auto-approved): ${MemoryStore.sessionMemoryPath(sid)}
           |- Agent knowledge (requires approval): ${MemoryStore.agentMemoryPath(agentDef.name)}
           |- User preferences (requires approval): ${MemoryStore.userMemoryPath}""".stripMargin
      case None =>
        s"""# Memory Files
           |- Agent knowledge (requires approval): ${MemoryStore.agentMemoryPath(agentDef.name)}
           |- User preferences (requires approval): ${MemoryStore.userMemoryPath}""".stripMargin

    (memorySections :+ memoryFiles).mkString("\n\n")
  end buildMemoryBlock

  /** Resolve projectRoot and rulesMd from the session's folder chain.
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
      // memory from files (mtime-cached — 3 cheap stat() calls per turn)
      memoryBlock = buildMemoryBlock(freshDef, state.sessionId)
      // thinking config from global ref
      thinkingConfig <- resources.thinkingConfigRef.get
      // file change detection (5s debounce)
      fileChanges <- resources.fileChangeTracker.checkChanges()
    yield TurnContext(freshDef, projectRoot, rulesMd, memoryBlock, thinkingConfig, fileChanges)
  end refreshTurn

end ContextRefresher
