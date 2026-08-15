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
// FlowTreeActor — manages TEAM mounting and session lifecycle
// ============================================================
//
// In the unified model, this actor only manages Team agent sessions.
// Flow DAG execution is handled entirely by FlowDagExecutor/FlowDagRunner
// via the FlowTrigger tool.
//
// Responsibilities:
//   1. Mount/unmount: create/remove team agent sessions
//   2. Restore: on startup, recreate sessions from MountedFlowStore
//   3. Hot reload: file changes → diff-based team update
//   4. Cancel: stop all running actors for a team
//   5. Crash recovery: resume interrupted turns

/**
 * Global registry for team session lookups (replaces FlowMembership).
 * Tracks (instance, agent) → sessionId and running actors.
 */
object TeamSessionRegistry:
  private val logger = NebflowLogger.forName("nebflow.flow.registry")
  // (instanceName, agentName) → sessionId
  private val sessionMap = Ref.unsafe[IO, Map[(String, String), String]](Map.empty)
  // sessionId → ActorRef
  private val actorMap = Ref.unsafe[IO, Map[String, ActorRef[AgentCommand]]](Map.empty)
  // instanceName → manager sessionId
  private val managerMap = Ref.unsafe[IO, Map[String, String]](Map.empty)
  // instanceName → parent sessionId
  private val parentSessionMap = Ref.unsafe[IO, Map[String, String]](Map.empty)
  // sessionId → parent ActorRef
  private val parentActorMap = Ref.unsafe[IO, Map[String, ActorRef[AgentCommand]]](Map.empty)
  // sessionId → busy state
  private val busyMap = Ref.unsafe[IO, Set[String]](Set.empty)

  def registerSession(instance: String, agent: String, sid: String): IO[Unit] =
    sessionMap.update(_ + ((instance, agent) -> sid))

  /** Reset all registries (tests). */
  def clear: IO[Unit] =
    sessionMap.set(Map.empty) *> actorMap.set(Map.empty) *> managerMap.set(Map.empty) *>
      parentSessionMap.set(Map.empty) *> parentActorMap.set(Map.empty) *> busyMap.set(Set.empty)

  def registerActor(sid: String, ref: ActorRef[AgentCommand]): IO[Unit] =
    actorMap.update(_ + (sid -> ref))

  /**
   * P1: dual-write into the unified AgentRegistry so interaction answers
   * (permissionAnswer/askUserAnswer) route to Mail-activated team agents.
   * rootSessionId anchors the permission-policy bucket (P2 inheritance chain).
   * P3 removes this overload and the actorMap itself.
   */
  def registerActor(
    sid: String,
    ref: ActorRef[AgentCommand],
    resources: SharedResources,
    rootSessionId: String
  ): IO[Unit] =
    actorMap.update(_ + (sid -> ref)) *>
      resources.agentRegistry.update(_ + (sid -> AgentRecord(sid, ref, AgentKind.Team, rootSessionId)))

  def unregisterActor(sid: String): IO[Unit] =
    actorMap.update(_ - sid)

  /** P1: also remove from the unified AgentRegistry. */
  def unregisterActor(sid: String, resources: SharedResources): IO[Unit] =
    actorMap.update(_ - sid) *> resources.agentRegistry.update(_ - sid)

  def getRunningActor(sid: String): IO[Option[ActorRef[AgentCommand]]] =
    actorMap.get.map(_.get(sid))

  def registerManager(instance: String, sid: String): IO[Unit] =
    managerMap.update(_ + (instance -> sid))

  def isManager(sid: String): IO[Boolean] =
    managerMap.get.map(_.values.toSet.contains(sid))

  def registerParentSession(instance: String, sid: String): IO[Unit] =
    parentSessionMap.update(_ + (instance -> sid))

  def registerParentActor(sid: String, ref: ActorRef[AgentCommand]): IO[Unit] =
    parentActorMap.update(_ + (sid -> ref))

  def getParentActor(sid: String): IO[Option[ActorRef[AgentCommand]]] =
    parentActorMap.get.map(_.get(sid))

  def parentSessionOf(instance: String): IO[Option[String]] =
    parentSessionMap.get.map(_.get(instance))

  /**
   * Resolve a short agent name to a sessionId within a team instance.
   *
   * Address formats:
   *   - "team/agent" — exact scoped lookup (e.g. "nebflow-project/Backend").
   *   - "agent" — same-team first (when the sender belongs to a team), then a
   *     global lookup with ambiguity detection: if multiple teams have an
   *     agent with this short name, the call FAILS with the candidate list
   *     instead of silently picking one — Map iteration order is not
   *     deterministic, so collectFirst can hit any team.
   *
   * Returns:
   *   - Right(Some(sid)) — resolved
   *   - Right(None) — not found anywhere
   *   - Left(error) — ambiguous short name (candidates listed) or team not found
   */
  def resolveSessionId(
    senderSid: String,
    address: String,
    sessionStore: nebflow.gateway.SessionStore
  ): IO[Either[String, Option[String]]] =
    // "team/agent" scoped format — exact match, no ambiguity.
    val slashIdx = address.indexOf('/')
    if slashIdx > 0 then
      val (team, agent) = (address.substring(0, slashIdx), address.substring(slashIdx + 1))
      sessionMap.get.map { m =>
        m.get((team, agent)) match
          case Some(sid) => Right(Some(sid))
          case None =>
            // "team/Nebula" — Nebula is the top-level root agent, never a team
            // session; Right(None) lets the caller route it to Nebula directly.
            if agent == "Nebula" then Right(None)
            else if m.keys.exists(_._1 == team) then Right(None)
            else Left(s"Team '$team' not found or not mounted. Use Load(type: \"team\", name: \"$team\") first.")
      }
    else
      sessionMap.get.flatMap { m =>
        // Sender's own instance — resolve same-team agent first.
        val senderInstance = m.collectFirst { case ((inst, _), sid) if sid == senderSid => inst }
        senderInstance match
          case Some(inst) =>
            m.get((inst, address)) match
              case Some(sid) => IO.pure(Right(Some(sid)))
              case None =>
                // Same-team miss: fall back to any instance with that name
                // (e.g. a standalone session, or another team's manager),
                // with ambiguity detection.
                IO.pure(resolveGlobal(m, address))
          case None =>
            // Sender isn't in any team (Nebula root / standalone). Per the
            // routing rule, bare short names are NOT routable from outside a
            // team — a global pick is non-deterministic. Only team names
            // (handled by callers before reaching here), "Nebula", and
            // explicit "team/agent" are valid from outside.
            if address == "Nebula" then IO.pure(Right(None))
            else
              IO.pure(
                Left(
                  s"Agent '$address' cannot be mailed from outside a team. Mail a TEAM name (e.g. \"nebflow-project\") — the Manager dispatches to members. Team members use short names internally."
                )
              )
        end match
      }

    end if

  end resolveSessionId

  /** Global short-name lookup with ambiguity detection. */
  private def resolveGlobal(
    m: Map[(String, String), String],
    address: String
  ): Either[String, Option[String]] =
    m.toList.collect { case ((inst, `address`), sid) => (inst, sid) } match
      case Nil => Right(None)
      case (_, sid) :: Nil => Right(Some(sid))
      case many =>
        val teams = many.map(_._1).distinct.sorted.mkString(", ")
        Left(
          s"Agent '$address' is ambiguous: found in ${many.size} teams ($teams). " +
            s"Use \"team/agent\" format (e.g. \"${many.head._1}/$address\") to disambiguate."
        )

  /** Get the agent name for a session. */
  def agentOfSession(sid: String): IO[Option[String]] =
    sessionMap.get.map(_.collectFirst { case ((_, agent), s) if s == sid => agent })

  /** Get (instance, agent) for a session. */
  def instanceAndAgentOfSession(sid: String): IO[Option[(String, String)]] =
    sessionMap.get.flatMap { m =>
      m.collectFirst { case ((inst, agent), s) if s == sid => (inst, agent) } match
        case Some(t) => IO.pure(Some(t))
        case None => IO.pure(None)
    }

  /** Find the team/instance name for a session (replaces flowOfSession). */
  def teamOfSession(sid: String): IO[Option[String]] =
    sessionMap.get.map(_.collectFirst { case ((inst, _), s) if s == sid => inst })

  /** Find a specific agent's sessionId within a team instance. */
  def findTeamAgent(instance: String, agentName: String): IO[Option[String]] =
    sessionMap.get.map(_.get((instance, agentName)))

  /** List all sessionIds for an instance. */
  def sessionIdsOf(instance: String): IO[List[String]] =
    sessionMap.get.map(_.collect { case ((inst, _), sid) if inst == instance => sid }.toList)

  /** List all agents for an instance. */
  def agentsOfInstance(instance: String): IO[List[(String, String)]] =
    sessionMap.get.map(_.collect { case ((`instance`, agent), sid) => (agent, sid) }.toList)

  /** Remove all sessions for an instance. */
  def unregisterInstance(instance: String): IO[Unit] =
    for
      sids <- sessionIdsOf(instance)
      _ <- sessionMap.update(_.filterNot { case ((inst, _), _) => inst == instance })
      _ <- managerMap.update(_ - instance)
      _ <- parentSessionMap.update(_ - instance)
      _ <- parentActorMap.update(_ -- sids)
    yield ()

  /** Remove a specific agent from an instance. */
  def unregisterAgent(instance: String, agent: String, sid: String): IO[Unit] =
    sessionMap.update(_ - ((instance, agent))) *>
      parentActorMap.update(_ - sid)

  def markBusy(sid: String): IO[Unit] = busyMap.update(_ + sid)
  def markIdle(sid: String): IO[Unit] = busyMap.update(_ - sid)
  def isBusy(sid: String): IO[Boolean] = busyMap.get.map(_.contains(sid))

  /** List all mounted teams with their agents: instanceName → [(agentName, sessionId)]. */
  def listMountedTeams: IO[Map[String, List[(String, String)]]] =
    sessionMap.get.map { m =>
      m.groupBy { case ((inst, _), _) => inst }
        .view
        .mapValues(_.view.map { case ((_, agent), sid) => (agent, sid) }.toList)
        .toMap
    }

end TeamSessionRegistry

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

      val flowNamesRef = Ref.unsafe[IO, Map[String, String]](Map.empty) // instanceName → teamName
      val watchedAgentsRef = Ref.unsafe[IO, Map[String, ActorRef[AgentCommand]]](Map.empty)

      for
        _ <- logger.info(s"FlowTreeActor started for session ${config.sessionId}")
        _ <- restoreTeams(flowNamesRef, config).handleErrorWith(e =>
          logger
            .error(s"restoreTeams failed: ${e.getMessage}\n${e.getStackTrace.take(5).map(_.toString).mkString("\n")}")
            .void
        )
        _ <- restoreInterruptedTurns(flowNamesRef, config).handleErrorWith(e =>
          logger.error(s"restoreInterruptedTurns failed: ${e.getMessage}").void
        )
        // Notify frontend that teams are now available
        restoredNames <- flowNamesRef.get
        _ <- restoredNames.toList.traverse_ { (name, _) =>
          emit(config, "treeBranchMounted", "name" -> name.asJson, "type" -> "team".asJson)
        }
        _ <- FlowTreeRegistry.signalRestoreComplete
        _ <-
          if !config.disableFileWatcher then
            startFileWatcher(ctx, config)
              .handleErrorWith(e => logger.error(s"startFileWatcher failed: ${e.getMessage}").void)
          else IO.unit
      yield running(flowNamesRef, watchedAgentsRef, config, ctx)
      end for
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
          .error(
            s"FlowTreeActor error: ${err.getMessage}\n${err.getStackTrace.take(10).map(_.toString).mkString("\n")}"
          )
          .as(this)

      override def onSignal(ctx: ActorContext[TreeCommand], signal: SystemSignal): IO[Behavior[TreeCommand]] =
        signal match
          case SystemSignal.Terminated(deadRef) =>
            for
              watched <- watchedAgentsRef.get
              sidOpt = watched.find(_._2 == deadRef).map(_._1)
              _ <- sidOpt.traverse_ { sid =>
                for
                  _ <- TeamSessionRegistry.unregisterActor(sid, cfg.resources)
                  _ <- watchedAgentsRef.update(_ - sid)
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
        if alreadyMounted then hotReloadTeam(flowNamesRef, cfg, baseName, teamDef)
        else
          for
            _ <- createTeamAgentSessions(baseName, teamDef, cfg)
            _ <- cfg.sessionId.traverse_(sid =>
              TeamSessionRegistry.registerParentSession(baseName, sid) *>
                TeamSessionRegistry.registerParentActor(sid, cfg.parentAgentRef)
            )
            _ <- flowNamesRef.update(_ + (baseName -> teamDef.name))
            _ <- persistTeams(flowNamesRef, cfg)
            _ <- emit(cfg, "treeBranchMounted", "name" -> baseName.asJson, "type" -> "team".asJson)
            _ <- logger.info(s"Team '$baseName' mounted (team: ${teamDef.name})")
          yield ()
      _ <- replyTo.traverse_(_ ! MountResult.Mounted(baseName, baseName))
    yield ()

  private def hotReloadTeam(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    instanceName: String,
    newTeamDef: TeamDef
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      currentAgents <- TeamSessionRegistry.agentsOfInstance(instanceName)
      currentNames = currentAgents.map(_._1).toSet
      newNames = (newTeamDef.lead :: newTeamDef.members).toSet
      added = newNames -- currentNames
      removed = currentNames -- newNames

      _ <- removed.toList.traverse_ { name =>
        currentAgents.find(_._1 == name) match
          case Some((_, sid)) =>
            for
              _ <- TeamSessionRegistry.getRunningActor(sid).flatMap {
                case Some(ref) => cfg.resources.actorSystem.stop(ref).handleErrorWith(_ => IO.unit)
                case None => IO.unit
              }
              _ <- TeamSessionRegistry.unregisterAgent(instanceName, name, sid)
              _ <- cfg.resources.sessionStore.deleteSession(sid).handleErrorWith(_ => IO.unit)
              _ <- logger.info(s"Hot reload: removed agent '$name' from '$instanceName'")
            yield ()
          case None => IO.unit
      }

      _ <- added.toList.traverse_ { name =>
        val isManager = name == newTeamDef.lead
        createSingleTeamSession(instanceName, name, newTeamDef.name, isManager, cfg) *>
          logger.info(s"Hot reload: added agent '$name' to '$instanceName'")
      }

      _ <- emit(cfg, "treeBranchUpdated", "name" -> instanceName.asJson)
      _ <- logger.info(s"Hot reload '$instanceName': agents +${added.size} -${removed.size}")
    yield ()

  private def cleanupTeamInstance(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    name: String
  ): IO[Unit] =
    for
      _ <- handleCancel(cfg, name)
      sids <- TeamSessionRegistry.sessionIdsOf(name)
      _ <- TeamSessionRegistry.unregisterInstance(name)
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
      _ <- TeamSessionRegistry.registerSession(instanceName, agentName, sessionMeta.id)
      _ <- TeamSessionRegistry.registerParentActor(sessionMeta.id, cfg.parentAgentRef)
      _ <- if isManager then TeamSessionRegistry.registerManager(instanceName, sessionMeta.id) else IO.unit
    yield ()
    end for

  end createSingleTeamSession

  private def handleUnmount(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    name: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      names <- flowNamesRef.get
      _ <-
        if names.contains(name) then
          for
            _ <- handleCancel(cfg, name)
            sids <- TeamSessionRegistry.sessionIdsOf(name)
            _ <- TeamSessionRegistry.unregisterInstance(name)
            _ <- sids.traverse_(sid => cfg.resources.sessionStore.deleteSession(sid).handleErrorWith(_ => IO.unit))
            _ <- flowNamesRef.update(_ - name)
            _ <- persistTeams(flowNamesRef, cfg)
            _ <- emit(cfg, "treeBranchUnmounted", "name" -> name.asJson)
            _ <- logger.info(s"Team '$name' unmounted (cleaned ${sids.size} sessions)")
          yield ()
        else logger.warn(s"Cannot unmount '$name': not found")
    yield ()

  private def handleCancel(cfg: TreeConfig, name: String): IO[Unit] =
    for
      sessionIds <- TeamSessionRegistry.sessionIdsOf(name)
      _ <- sessionIds.traverse_ { sid =>
        TeamSessionRegistry.getRunningActor(sid).flatMap {
          case Some(ref) =>
            cfg.resources.actorSystem.stop(ref) *> TeamSessionRegistry.unregisterActor(sid, cfg.resources)
          case None => IO.unit
        }
      }
      _ <- logger.info(s"Cancelled team '$name' — stopped ${sessionIds.size} agent(s)")
    yield ()

  private def handleReload(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    teamName: String
  )(using ActorContext[TreeCommand]): IO[Unit] =
    for
      teamOpt <- EntityLoader.loadTeam(teamName)
      _ <- teamOpt match
        case None =>
          for
            names <- flowNamesRef.get.map(_.collect { case (name, fn) if fn == teamName => name }.toList)
            _ <- names.traverse_(name => cleanupTeamInstance(flowNamesRef, cfg, name))
            _ <- logger.info(s"Reload: team '$teamName' removed from disk, unmounted")
          yield ()
        case Some(teamDef) =>
          for
            names <- flowNamesRef.get.map(_.collect { case (name, fn) if fn == teamName => name }.toList)
            _ <- names.traverse_(name => hotReloadTeam(flowNamesRef, cfg, name, teamDef))
          yield ()
    yield ()

  // ============================================================
  // Restore from MountedFlowStore on startup
  // ============================================================

  private def restoreTeams(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig
  ): IO[Unit] =
    cfg.sessionId match
      case Some(sid) if sid.nonEmpty =>
        for
          rawEntries <- MountedFlowStore.load(sid)
          deduped = deduplicateEntries(rawEntries)
          _ <-
            if deduped.size != rawEntries.size then
              MountedFlowStore.save(sid, deduped) *> logger.info(
                s"Deduplicated flows.json: ${rawEntries.size} → ${deduped.size} entries"
              )
            else IO.unit
          successfulNames <- Ref.of[IO, Set[String]](Set.empty)
          _ <- deduped.traverse_ { entry =>
            EntityLoader.loadTeam(entry.flowName).flatMap {
              case Some(teamDef) =>
                for
                  _ <- createTeamAgentSessions(entry.name, teamDef, cfg)
                  _ <- TeamSessionRegistry.registerParentSession(entry.name, sid)
                  _ <- TeamSessionRegistry.registerParentActor(sid, cfg.parentAgentRef)
                  _ <- flowNamesRef.update(_ + (entry.name -> entry.flowName))
                  _ <- successfulNames.update(_ + entry.name)
                  _ <- logger.info(s"Restored team '${entry.name}' (team: ${entry.flowName})")
                yield ()
              case None =>
                logger.warn(s"Restore: team '${entry.flowName}' not found for instance '${entry.name}'")
            }
          }
          _ <- if deduped.nonEmpty then logger.info(s"Restored ${deduped.size} team(s)") else IO.unit
          existing <- successfulNames.get
          allTeams <- EntityLoader.listTeams()
          unmounted = allTeams.values.toList.filterNot(td => existing.contains(td.name))
          _ <- unmounted.traverse_ { teamDef =>
            (for
              _ <- createTeamAgentSessions(teamDef.name, teamDef, cfg)
              _ <- TeamSessionRegistry.registerParentSession(teamDef.name, sid)
              _ <- TeamSessionRegistry.registerParentActor(sid, cfg.parentAgentRef)
              _ <- flowNamesRef.update(_ + (teamDef.name -> teamDef.name))
              _ <- logger.info(s"Auto-mounted team '${teamDef.name}'")
            yield ()).handleErrorWith(e =>
              logger.error(s"Failed to auto-mount team '${teamDef.name}': ${e.getMessage}").void
            )
          }
          _ <-
            if unmounted.nonEmpty then
              for
                allNames <- flowNamesRef.get
                allEntries = allNames.toList.map((name, defName) => MountedFlowStore.MountedFlowEntry(name, defName))
                _ <- MountedFlowStore.save(sid, allEntries)
              yield ()
            else IO.unit
          _ <- if unmounted.nonEmpty then logger.info(s"Auto-mounted ${unmounted.size} team(s) from disk") else IO.unit
        yield ()
      case _ => IO.unit

  private def deduplicateEntries(
    entries: List[MountedFlowStore.MountedFlowEntry]
  ): List[MountedFlowStore.MountedFlowEntry] =
    entries
      .sortBy(e => if e.name == e.flowName then 0 else 1)
      .distinctBy(_.flowName)

  // ============================================================
  // Crash recovery — restore interrupted turns on startup
  // ============================================================

  private def restoreInterruptedTurns(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig
  )(using ActorContext[?]): IO[Unit] =
    for
      teamNames <- flowNamesRef.get
      _ <- teamNames.toList.traverse_ { (instanceName, _) =>
        for
          sessionIds <- TeamSessionRegistry.sessionIdsOf(instanceName)
          _ <- sessionIds.traverse_ { sid =>
            for
              turnStateOpt <- TurnStateStore.load(sid)
              _ <- turnStateOpt.filter(_.inProgress).traverse_ { ts =>
                for
                  messages <- cfg.resources.sessionStore.loadMessagesForSession(sid)
                  lastRole = messages.lastOption.map(_.role)
                  _ <- lastRole match
                    case Some(MessageRole.Assistant) =>
                      TurnStateStore.clear(sid)
                    case Some(_) =>
                      logger.info(
                        s"Restoring interrupted turn for session ${sid.take(8)} (msgs=${messages.size} turnStart=${ts.turnStartMessageCount})"
                      )
                      resumeInterruptedAgent(cfg, sid, ts.turnStartMessageCount, ts.turnIdx)
                    case None =>
                      TurnStateStore.clear(sid)
                yield ()
              }
            yield ()
          }
        yield ()
      }
    yield ()

  /** Reactivate a team agent session and send ResumeTurn. */
  private def resumeInterruptedAgent(
    cfg: TreeConfig,
    sessionId: String,
    turnStartMessageCount: Int,
    turnIdx: Int
  )(using ctx: ActorContext[?]): IO[Unit] =
    for
      sessionOpt <- cfg.resources.sessionStore.getSessionMeta(sessionId)
      _ <- sessionOpt.traverse_ { session =>
        val agentName = session.agentName.getOrElse("")
        for
          entryOpt <- session.flowName match
            case Some(teamName) => EntityLoader.loadTeamAgent(teamName, agentName)
            case None => EntityLoader.loadAgent(agentName)
          _ <- entryOpt.traverse_ { entry =>
            val agentDef = entry.toAgentDef
            val rootSid = cfg.sessionId.getOrElse(session.id)
            for
              policyOpt <- cfg.resources.permissionPolicies.get.map(_.get(rootSid))
              safetyMode =
                policyOpt.map(p => nebflow.core.SafetyMode.toString(p.safetyMode)).getOrElse(cfg.safetyMode)
              ref <- cfg.resources.actorSystem.spawn(
                AgentActor(
                  agentDef = agentDef,
                  resources = cfg.resources,
                  wsSend = cfg.wsSend.getOrElse(_ => IO.unit),
                  depth = 1,
                  parentRef = Some(cfg.parentAgentRef),
                  sessionId = Some(session.id),
                  sessionName = Some(session.name),
                  projectRoot = Some(cfg.projectRoot),
                  safetyMode = safetyMode,
                  rootSessionId = rootSid,
                  expectsMail = agentDef.name != "Manager"
                ),
                s"resume-${session.id.take(8)}"
              )
              _ <- TeamSessionRegistry.registerActor(session.id, ref, cfg.resources, rootSid)
              _ <- (ref ! AgentCommand.ResumeTurn(turnStartMessageCount, turnIdx)).void
              _ <- emit(cfg, "flowResumed", "sessionId" -> session.id.asJson)
            yield ()
            end for
          }
        yield ()
        end for
      }
    yield ()

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
            else if watchDir == agentsDir || (agentsDir.getRoot == watchDir.getRoot && watchDir.startsWith(agentsDir))
            then
              cfg.parentAgentRef ! AgentCommand.ImmediateInput(
                s"Agent definition '$fileName' has disk changes. The change will take effect on next activation."
              )
            else if watchDir == teamsDir || (teamsDir.getRoot == watchDir.getRoot && watchDir.startsWith(teamsDir)) then
              val teamName = teamsDir.relativize(watchDir).getName(0).toString
              cfg.parentAgentRef ! AgentCommand.ImmediateInput(
                s"Team '$teamName' has disk changes. Reload to apply."
              )
            end if
          }
          key.reset()
        end while
      }.void
        .handleErrorWith(e => logger.warn(s"File watcher error: ${e.getMessage}").void)
    )
  end startFileWatcher

  // ============================================================
  // Helpers
  // ============================================================

  private def persistTeams(
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
