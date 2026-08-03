package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.{EntityLoader, TeamDef}
import nebflow.shared.{Message, MessageRole}

// ============================================================
// FlowTreeActor — manages flow mounting and session lifecycle
// ============================================================
//
// In the new model, mounting a flow creates sessions for all agents
// and registers them in FlowMembership. Agents are activated on demand
// when Mail arrives (via FlowAgentActivator).
//
// Responsibilities:
//   1. Mount/unmount: create/remove agent sessions + register in FlowMembership
//   2. Restore: on startup, recreate sessions from MountedFlowStore
//   3. Hot reload: file changes → notify agent (agent calls Flow update)
//   4. Cancel: stop all running actors for a flow

object FlowTreeActor:
  private val logger = NebflowLogger.forName("nebflow.flow.tree")

  case class TreeConfig(
    parentAgentRef: ActorRef[AgentCommand],
    wsSend: Option[Json => IO[Unit]],
    sessionId: Option[String],
    resources: SharedResources,
    projectRoot: String,
    safetyMode: String,
    gatewayPort: Int = 8080,
    disableFileWatcher: Boolean = false,
    folderId: Option[String] = None,
    projectsDir: Option[String] = None
  )

  def apply(config: TreeConfig): Behavior[TreeCommand] =
    Behaviors.setup { ctx =>
      given ActorContext[TreeCommand] = ctx

      val flowNamesRef = Ref.unsafe[IO, Map[String, String]](Map.empty) // instanceName → flowName
      // sessionId → ActorRef for watched flow agents. Used to reverse-lookup
      // which session a Terminated signal corresponds to.
      val watchedAgentsRef = Ref.unsafe[IO, Map[String, ActorRef[AgentCommand]]](Map.empty)

      for
        _ <- logger.info(s"FlowTreeActor started for session ${config.sessionId}")
        _ <- restoreFlows(flowNamesRef, config).handleErrorWith(e =>
          logger.error(s"restoreFlows failed: ${e.getMessage}\n${e.getStackTrace.take(5).map(_.toString).mkString("\n")}").void
        )
        _ <- restoreInterruptedTurns(flowNamesRef, config).handleErrorWith(e =>
          logger.error(s"restoreInterruptedTurns failed: ${e.getMessage}").void
        )
        // Notify frontend that flows are now available — eliminates the
        // "empty at first, appears later" delay caused by the async restore
        // racing with the frontend's initial REST fetch.
        restoredNames <- flowNamesRef.get
        _ <- restoredNames.toList.traverse_ { (name, _) =>
          emit(config, "treeBranchMounted", "name" -> name.asJson, "type" -> "team".asJson)
        }
        // Signal that restore is complete so /api/flows stops waiting
        _ <- FlowTreeRegistry.signalRestoreComplete
        _ <- if !config.disableFileWatcher then
          startFileWatcher(ctx, config).handleErrorWith(e =>
            logger.error(s"startFileWatcher failed: ${e.getMessage}").void
          )
        else IO.unit
      yield running(flowNamesRef, watchedAgentsRef, config, ctx)
    }

  // ============================================================
  // Running behavior
  // ============================================================

  private def running(
    flowNamesRef: Ref[IO, Map[String, String]],
    watchedAgentsRef: Ref[IO, Map[String, ActorRef[AgentCommand]]],
    cfg: TreeConfig,
    setupCtx: ActorContext[TreeCommand]
  ): Behavior[TreeCommand] =

    new Behavior[TreeCommand]:
      override def onError(ctx: ActorContext[TreeCommand], err: Throwable): IO[Behavior[TreeCommand]] =
        logger
          .error(s"FlowTreeActor error: ${err.getMessage}\n${err.getStackTrace.take(10).map(_.toString).mkString("\n")}")
          .as(this)

      override def onSignal(ctx: ActorContext[TreeCommand], signal: SystemSignal): IO[Behavior[TreeCommand]] =
        signal match
          case SystemSignal.Terminated(deadRef) =>
            // A watched flow agent terminated. Reverse-lookup its sessionId,
            // clean the dead ref, and resume its turn if it was in progress.
            for
              watched <- watchedAgentsRef.get
              sidOpt = watched.find(_._2 == deadRef).map(_._1)
              _ <- sidOpt.traverse_ { sid =>
                for
                  _ <- FlowMembership.unregisterActor(sid)
                  _ <- watchedAgentsRef.update(_ - sid)
                  // Check for interrupted turn
                  turnStateOpt <- TurnStateStore.load(sid)
                  _ <- turnStateOpt.filter(_.inProgress).traverse_ { ts =>
                    logger.info(s"Agent '${deadRef.path.name}' terminated with in-progress turn, scheduling resume")
                    (ctx.self ! TreeCommand.ResumeAgent(sid, ts.turnStartMessageCount, ts.turnIdx)).void
                  }
                yield ()
              }
            yield this

      def receive(ctx: ActorContext[TreeCommand], msg: TreeCommand): IO[Behavior[TreeCommand]] =
        given ActorContext[TreeCommand] = ctx
        msg match
          case TreeCommand.MountTeam(teamDef, replyTo) =>
            handleMountTeam(flowNamesRef, cfg, teamDef, replyTo).as(this)

          case TreeCommand.UnmountBranch(name) =>
            handleUnmount(flowNamesRef, cfg, name).as(this)

          case TreeCommand.ReloadDefinition(flowName) =>
            handleReload(flowNamesRef, cfg, flowName).as(this)

          case TreeCommand.CancelPipeline(name) =>
            handleCancel(cfg, name).as(this)

          case TreeCommand.WatchAgent(ref, sid) =>
            ctx.watch(ref) *>
              watchedAgentsRef.update(_ + (sid -> ref)).as(this)

          case TreeCommand.ResumeAgent(sid, turnStart, turnIdx) =>
            resumeInterruptedAgent(cfg, sid, turnStart, turnIdx).as(this)

          case TreeCommand.Shutdown =>
            logger.info("FlowTreeActor shutdown").as(Behaviors.stopped[TreeCommand])
        end match

      end receive
  end running

  // ============================================================
  // Team mount (team.json format)
  // ============================================================

  private def handleMountTeam(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    teamDef: TeamDef,
    replyTo: Option[ActorRef[MountResult]]
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      names <- flowNamesRef.get
      baseName = teamDef.name
      alreadyMounted = names.contains(baseName)
      _ <-
        if alreadyMounted then
          // Hot reload: diff-based update, preserves existing sessions
          hotReloadTeam(flowNamesRef, cfg, baseName, teamDef)
        else
          // Fresh mount
          for
            _ <- createTeamAgentSessions(baseName, teamDef, cfg)
            _ <- cfg.sessionId.traverse_(sid =>
              FlowMembership.registerParentSession(baseName, sid) *>
              FlowMembership.registerParentActor(sid, cfg.parentAgentRef)
            )
            _ <- teamDef.flows.traverse_(flowName => mountFlowDag(baseName, flowName, cfg))
            _ <- flowNamesRef.update(_ + (baseName -> teamDef.name))
            _ <- persistFlows(flowNamesRef, cfg)
            _ <- emit(cfg, "treeBranchMounted", "name" -> baseName.asJson, "type" -> "team".asJson)
            _ <- logger.info(s"Team '$baseName' mounted (team: ${teamDef.name})")
          yield ()
      _ <- replyTo.traverse_(_ ! MountResult.Mounted(baseName, baseName))
    yield ()

  /** Hot reload: diff new team definition against current mounted state.
   *  Preserves unchanged agents' sessions and conversation history.
   *  Only creates sessions for new agents, removes sessions for deleted agents. */
  private def hotReloadTeam(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    instanceName: String,
    newTeamDef: TeamDef
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      // Diff agents
      currentAgents <- FlowMembership.agentsOfInstance(instanceName)
      currentNames = currentAgents.map(_._1).toSet
      newNames = (newTeamDef.lead :: newTeamDef.members).toSet
      added = newNames -- currentNames
      removed = currentNames -- newNames

      // Remove agents no longer in the team
      _ <- removed.toList.traverse_ { name =>
        currentAgents.find(_._1 == name) match
          case Some((_, sid)) =>
            for
              _ <- FlowMembership.getRunningActor(sid).flatMap {
                case Some(ref) => cfg.resources.actorSystem.stop(ref).handleErrorWith(_ => IO.unit)
                case None => IO.unit
              }
              _ <- FlowMembership.unregisterAgent(instanceName, name, sid)
              _ <- cfg.resources.sessionStore.deleteSession(sid).handleErrorWith(_ => IO.unit)
              _ <- logger.info(s"Hot reload: removed agent '$name' from '$instanceName'")
            yield ()
          case None => IO.unit
      }

      // Add new agents
      _ <- added.toList.traverse_ { name =>
        val isManager = name == newTeamDef.lead
        createSingleTeamSession(instanceName, name, newTeamDef.name, isManager, cfg) *>
          logger.info(s"Hot reload: added agent '$name' to '$instanceName'")
      }

      // Diff flows
      currentFlows <- FlowMembership.subFlowsOf(instanceName)
      newFlows = newTeamDef.flows.toSet
      addedFlows = newFlows -- currentFlows.toSet
      removedFlows = currentFlows.toSet -- newFlows

      _ <- removedFlows.toList.traverse_ { flowName =>
        val subFlowId = s"$instanceName/$flowName"
        for
          subSids <- FlowMembership.sessionIdsOf(subFlowId)
          _ <- subSids.traverse_(sid => FlowMembership.getRunningActor(sid).flatMap {
            case Some(ref) => cfg.resources.actorSystem.stop(ref).handleErrorWith(_ => IO.unit)
            case None => IO.unit
          })
          _ <- FlowMembership.unregisterFlowSessions(subFlowId)
          _ <- logger.info(s"Hot reload: removed flow '$flowName' from '$instanceName'")
        yield ()
      }

      _ <- addedFlows.toList.traverse_(flowName =>
        mountFlowDag(instanceName, flowName, cfg) *>
          logger.info(s"Hot reload: added flow '$flowName' to '$instanceName'")
      )

      _ <- emit(cfg, "treeBranchUpdated", "name" -> instanceName.asJson)
      _ <- logger.info(s"Hot reload '$instanceName': agents +${added.size} -${removed.size}, flows +${addedFlows.size} -${removedFlows.size}")
    yield ()

  /** Stop actors + delete sessions + unregister for a team instance. */
  private def cleanupTeamInstance(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    name: String
  ): IO[Unit] =
    for
      _ <- handleCancel(cfg, name)
      sids <- FlowMembership.sessionIdsOf(name)
      _ <- FlowMembership.unregisterFlowSessions(name)
      _ <- sids.traverse_(sid => cfg.resources.sessionStore.deleteSession(sid).handleErrorWith(_ => IO.unit))
      _ <- flowNamesRef.update(_ - name)
      _ <- logger.info(s"Replaced existing team instance '$name'")
    yield ()

  private def createTeamAgentSessions(
    instanceName: String,
    teamDef: TeamDef,
    cfg: TreeConfig
  ): IO[Unit] =
    for
      _ <- createSingleTeamSession(instanceName, teamDef.lead, teamDef.name, isManager = true, cfg)
      _ <- teamDef.members.traverse_(member =>
        createSingleTeamSession(instanceName, member, teamDef.name, isManager = false, cfg)
      )
    yield ()

  /** Create a session for a team agent using the global agent name directly. */
  private def createSingleTeamSession(
    instanceName: String,
    agentName: String,
    teamName: String,
    isManager: Boolean,
    cfg: TreeConfig
  ): IO[Unit] =
    val sessionName = s"$instanceName/$agentName"
    for
      existing <- cfg.resources.sessionStore.findSessionByName(sessionName, teamName)
      sessionMeta <- existing match
        case Some(meta) => IO.pure(meta)
        case None =>
          cfg.resources.sessionStore.createSession(
            sessionName,
            agentName = Some(agentName),
            flowName = Some(teamName),
            safetyMode = cfg.safetyMode
          )
      _ <- FlowMembership.registerSession(instanceName, agentName, sessionMeta.id)
      _ <- if isManager then
        FlowMembership.registerFlowManager(instanceName, sessionMeta.id)
      else IO.unit
    yield ()

  /** Mount a flow.json DAG as a sub-flow under the team. */
  private def mountFlowDag(
    teamInstance: String,
    flowName: String,
    cfg: TreeConfig
  ): IO[Unit] =
    val subFlowId = s"$teamInstance/$flowName"
    for
      flowOpt <- EntityLoader.loadFlow(flowName)
      _ <- flowOpt match
        case Some(flowDef) =>
          val nodeAgents = flowDef.nodes.values.map(_.agent).toSet
          for
            _ <- nodeAgents.toList.traverse_(agentName =>
              createSingleTeamSession(subFlowId, agentName, flowDef.name, isManager = false, cfg)
            )
            entryAgent = flowDef.nodes(flowDef.entry).agent
            entrySidOpt <- FlowMembership.isFlowManager(subFlowId, entryAgent)
            _ <- entrySidOpt.traverse_(sid =>
              FlowMembership.registerAlias(teamInstance, flowName, sid) *>
                logger.info(s"Flow DAG '$flowName' mounted as alias '$flowName' in '$teamInstance'")
            )
          yield ()
        case None =>
          logger.warn(s"Flow '$flowName' not found (no flow.json)")
    yield ()

  private def handleUnmount(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    name: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      names <- flowNamesRef.get
      _ <- if names.contains(name) then
        for
          _ <- handleCancel(cfg, name) // stop running actors
          sids <- FlowMembership.sessionIdsOf(name)
          _ <- FlowMembership.unregisterFlowSessions(name)
          _ <- sids.traverse_(sid => cfg.resources.sessionStore.deleteSession(sid).handleErrorWith(_ => IO.unit))
          _ <- flowNamesRef.update(_ - name)
          _ <- persistFlows(flowNamesRef, cfg)
          _ <- emit(cfg, "treeBranchUnmounted", "name" -> name.asJson)
          _ <- logger.info(s"Flow '$name' unmounted (cleaned ${sids.size} sessions)")
        yield ()
      else
        logger.warn(s"Cannot unmount '$name': not found")
    yield ()

  // ============================================================
  // Cancel — stop all running actors for a flow
  // ============================================================

  private def handleCancel(cfg: TreeConfig, name: String): IO[Unit] =
    for
      sessionIds <- FlowMembership.sessionIdsOf(name)
      _ <- sessionIds.traverse_ { sid =>
        FlowMembership.getRunningActor(sid).flatMap {
          case Some(ref) =>
            cfg.resources.actorSystem.stop(ref) *> FlowMembership.unregisterActor(sid)
          case None => IO.unit
        }
      }
      _ <- logger.info(s"Cancelled flow '$name' — stopped ${sessionIds.size} agent(s)")
    yield ()

  // ============================================================
  // Reload — hot reload via diff (preserves existing sessions)
  // ============================================================

  private def handleReload(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    flowName: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      teamOpt <- EntityLoader.loadTeam(flowName)
      _ <- teamOpt match
        case None =>
          for
            names <- flowNamesRef.get.map(_.collect { case (name, fn) if fn == flowName => name }.toList)
            _ <- names.traverse_(name => cleanupTeamInstance(flowNamesRef, cfg, name))
            _ <- logger.info(s"Reload: team '$flowName' removed from disk, unmounted")
          yield ()
        case Some(teamDef) =>
          for
            names <- flowNamesRef.get.map(_.collect { case (name, fn) if fn == flowName => name }.toList)
            _ <- names.traverse_(name => hotReloadTeam(flowNamesRef, cfg, name, teamDef))
          yield ()
    yield ()

  // ============================================================
  // Session creation
  // ============================================================

  // ============================================================
  // Restore from MountedFlowStore on startup
  // ============================================================

  private def restoreFlows(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig
  ): IO[Unit] =
    cfg.sessionId match
      case Some(sid) if sid.nonEmpty =>
        for
          rawEntries <- MountedFlowStore.load(sid)
          // Deduplicate by flowName: keep one entry per definition (prefer name==flowName).
          // This cleans up stale suffixed duplicates (e.g. "nebflow-project-2") from old bugs.
          deduped = deduplicateEntries(rawEntries)
          _ <- if deduped.size != rawEntries.size then
            MountedFlowStore.save(sid, deduped) *> logger.info(s"Deduplicated flows.json: ${rawEntries.size} → ${deduped.size} entries")
          else IO.unit
          // Track only successfully restored teams — failed entries must NOT
          // block auto-mount of the same team from disk.
          successfulNames <- Ref.of[IO, Set[String]](Set.empty)
          _ <- deduped.traverse_ { entry =>
            EntityLoader.loadTeam(entry.flowName).flatMap {
              case Some(teamDef) =>
                for
                  _ <- createTeamAgentSessions(entry.name, teamDef, cfg)
                  _ <- teamDef.flows.traverse_(flowName => mountFlowDag(entry.name, flowName, cfg))
                  _ <- FlowMembership.registerParentSession(entry.name, sid)
                  _ <- FlowMembership.registerParentActor(sid, cfg.parentAgentRef)
                  _ <- flowNamesRef.update(_ + (entry.name -> entry.flowName))
                  _ <- successfulNames.update(_ + entry.name)
                  _ <- logger.info(s"Restored team '${entry.name}' (team: ${entry.flowName})")
                yield ()
              case None =>
                logger.warn(s"Restore: team '${entry.flowName}' not found for instance '${entry.name}'")
            }
          }
          _ <- if deduped.nonEmpty then logger.info(s"Restored ${deduped.size} flow(s)") else IO.unit
          // Auto-mount all teams from disk that aren't already mounted.
          // Teams are persistent project definitions — they should always be
          // visible and ready after startup, regardless of MountedFlowStore state.
          existing <- successfulNames.get
          allTeams <- EntityLoader.listTeams()
          unmounted = allTeams.values.toList.filterNot(td => existing.contains(td.name))
          _ <- unmounted.traverse_ { teamDef =>
            (for
              _ <- createTeamAgentSessions(teamDef.name, teamDef, cfg)
              _ <- teamDef.flows.traverse_(flowName => mountFlowDag(teamDef.name, flowName, cfg))
              _ <- FlowMembership.registerParentSession(teamDef.name, sid)
              _ <- FlowMembership.registerParentActor(sid, cfg.parentAgentRef)
              _ <- flowNamesRef.update(_ + (teamDef.name -> teamDef.name))
              _ <- logger.info(s"Auto-mounted team '${teamDef.name}'")
            yield ()).handleErrorWith(e =>
              logger.error(s"Failed to auto-mount team '${teamDef.name}': ${e.getMessage}").void
            )
          }
          // Persist auto-mounted teams to MountedFlowStore for crash recovery.
          _ <- if unmounted.nonEmpty then
            for
              allNames <- flowNamesRef.get
              allEntries = allNames.toList.map((name, defName) => MountedFlowStore.MountedFlowEntry(name, defName))
              _ <- MountedFlowStore.save(sid, allEntries)
            yield ()
          else IO.unit
          _ <- if unmounted.nonEmpty then logger.info(s"Auto-mounted ${unmounted.size} team(s) from disk") else IO.unit
        yield ()
      case _ => IO.unit

  /** For each unique flowName, keep only one entry — prefer name == flowName (no suffix). */
  private def deduplicateEntries(entries: List[MountedFlowStore.MountedFlowEntry]): List[MountedFlowStore.MountedFlowEntry] =
    entries
      .sortBy(e => if e.name == e.flowName then 0 else 1) // canonical name first
      .distinctBy(_.flowName)

  // ============================================================
  // Crash recovery — restore interrupted turns on startup
  // ============================================================

  private def restoreInterruptedTurns(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig
  )(using ActorContext[?]): IO[Unit] =
    for
      flowNames <- flowNamesRef.get
      _ <- flowNames.toList.traverse_ { (instanceName, _) =>
        for
          sessionIds <- FlowMembership.sessionIdsOf(instanceName)
          _ <- sessionIds.traverse_ { sid =>
            for
              turnStateOpt <- TurnStateStore.load(sid)
              _ <- turnStateOpt.filter(_.inProgress).traverse_ { ts =>
                for
                  messages <- cfg.resources.sessionStore.loadMessagesForSession(sid)
                  lastRole = messages.lastOption.map(_.role)
                  _ <- lastRole match
                    case Some(MessageRole.Assistant) =>
                      // Turn actually completed (finishTurn persisted the final
                      // assistant message but crashed before clearing the marker).
                      TurnStateStore.clear(sid)
                    case Some(_) =>
                      // Turn was interrupted during an LLM call — resume it.
                      logger.info(s"Restoring interrupted turn for session ${sid.take(8)} (msgs=${messages.size} turnStart=${ts.turnStartMessageCount})")
                      resumeInterruptedAgent(cfg, sid, ts.turnStartMessageCount, ts.turnIdx)
                    case None =>
                      // No messages at all — stale marker, clear it.
                      TurnStateStore.clear(sid)
                yield ()
              }
            yield ()
          }
        yield ()
      }
    yield ()

  /** Activate a flow agent session and send it a ResumeTurn command. */
  private def resumeInterruptedAgent(
    cfg: TreeConfig,
    sessionId: String,
    turnStartMessageCount: Int,
    turnIdx: Int
  )(using ActorContext[?]): IO[Unit] =
    FlowAgentActivator.ensureSession(
      sessionId,
      Some(cfg.parentAgentRef),
      cfg.resources,
      cfg.resources.actorSystem,
      cfg.wsSend
    ).flatMap {
      case Some(ref) =>
        (ref ! AgentCommand.ResumeTurn(turnStartMessageCount, turnIdx)).void *>
          emit(cfg, "flowResumed", "sessionId" -> sessionId.asJson)
      case None =>
        logger.warn(s"Cannot resume session ${sessionId.take(8)} — activation failed").void
    }

  // ============================================================
  // File watcher — notify agent of stale changes
  // ============================================================

  private def startFileWatcher(ctx: ActorContext[TreeCommand], cfg: TreeConfig): IO[Unit] =
    val flowsDir = (nebflow.core.PathUtil.dataRoot / "flows").toIO.toPath
    val agentsDir = (nebflow.core.PathUtil.dataRoot / "agents").toIO.toPath
    val teamsDir = (nebflow.core.PathUtil.dataRoot / "teams").toIO.toPath
    ctx.forkTurn(
      IO.blocking {
        if !java.nio.file.Files.exists(flowsDir) then java.nio.file.Files.createDirectories(flowsDir)
        val watcher = java.nio.file.FileSystems.getDefault.newWatchService()

        def registerRecursive(dir: java.nio.file.Path): Unit =
          if java.nio.file.Files.isDirectory(dir) then
            dir.register(
              watcher,
              java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY,
              java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
              java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
            )
            java.nio.file.Files
              .list(dir)
              .filter(java.nio.file.Files.isDirectory(_))
              .forEach(registerRecursive)

        registerRecursive(flowsDir)
        if java.nio.file.Files.exists(agentsDir) then registerRecursive(agentsDir)
        if java.nio.file.Files.exists(teamsDir) then registerRecursive(teamsDir)

        while true do
          val key = watcher.take()
          val watchDir = key.watchable().asInstanceOf[java.nio.file.Path]
          val events = key.pollEvents()
          events.forEach { event =>
            val fileName = event.context().toString
            val fullPath = watchDir.resolve(fileName)

            if watchDir == flowsDir || flowsDir.relativize(watchDir).getName(0).toString == "flows" then
              val flowName: Option[String] =
                if watchDir == flowsDir then
                  if fileName.endsWith(".json") then Some(fileName.stripSuffix(".json"))
                  else if fileName.endsWith(".memory.md") then Some(fileName.stripSuffix(".memory.md"))
                  else if java.nio.file.Files.isDirectory(fullPath) then
                    registerRecursive(fullPath)
                    Some(fileName)
                  else None
                else
                  val relPath = flowsDir.relativize(watchDir)
                  val fName = relPath.getName(0).toString
                  if event.kind() == java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
                    && java.nio.file.Files.isDirectory(fullPath)
                  then registerRecursive(fullPath)
                  Some(fName)

              flowName.foreach { name =>
                cfg.parentAgentRef ! AgentCommand.ImmediateInput(
                  s"Flow '$name' has disk changes not yet applied. Use Flow(action: \"update\", name: \"$name\") to apply."
                )
              }
            else if watchDir == agentsDir || (agentsDir.getRoot == watchDir.getRoot && watchDir.startsWith(agentsDir)) then
              cfg.parentAgentRef ! AgentCommand.ImmediateInput(
                s"Agent definition '$fileName' has disk changes. The change will take effect on next activation."
              )
            else if watchDir == teamsDir || (teamsDir.getRoot == watchDir.getRoot && watchDir.startsWith(teamsDir)) then
              val teamName = teamsDir.relativize(watchDir).getName(0).toString
              cfg.parentAgentRef ! AgentCommand.ImmediateInput(
                s"Team '$teamName' has disk changes. Reload to apply."
              )
          }
          key.reset()
        end while
      }.void
        .handleErrorWith(e => logger.warn(s"File watcher error: ${e.getMessage}").void)
    )

  // ============================================================
  // Helpers
  // ============================================================

  private def persistFlows(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig
  ): IO[Unit] =
    cfg.sessionId match
      case Some(sid) if sid.nonEmpty =>
        for
          flowNames <- flowNamesRef.get
          entries = flowNames.map { case (name, flowName) =>
            MountedFlowStore.MountedFlowEntry(name, flowName)
          }.toList
          _ <- MountedFlowStore.save(sid, entries)
        yield ()
      case _ => IO.unit

  private def emit(
    cfg: TreeConfig,
    eventName: String,
    fields: (String, Json)*
  )(using ctx: ActorContext[?]): IO[Unit] =
    ctx.forkTurn(
      cfg.wsSend match
        case Some(send) =>
          val all = ("type", eventName.asJson) :: ("sessionId", cfg.sessionId.getOrElse("").asJson) :: fields.toList
          val json = Json.fromJsonObject(JsonObject.fromIterable(all))
          send(json)
        case None => IO.unit
    )

end FlowTreeActor
