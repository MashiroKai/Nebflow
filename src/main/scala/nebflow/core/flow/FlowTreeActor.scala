package nebflow.core.flow

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.core.AgentRuntimePort
import nebflow.core.entity.{EntityLoader, TeamDef}
import nebflow.shared.NebflowLogger

// 严格DAG第⑥步第三批A裁定(dwfq-5c7a31ea-1,M1/M6):定位器参数窄化,AgentActor 构造改经工厂镜像
// ============================================================
// FlowTreeActor — manages TEAM mounting and session lifecycle
// ============================================================
//
// In the unified model, this actor only manages Team agent sessions.
// (The Flow DAG engine face — FlowTrigger/FlowExecute tools + FlowDagRunner —
// is retired: tools 2026-09-06, runner residual removed in the R4 hygiene
// batch 2026-10-29.)
//
// Responsibilities:
//   1. Mount: create team agent sessions (Load tool; restore on startup)
//   2. Restore: on startup, recreate sessions from MountedFlowStore
//   3. Hot reload: def file changes → diff-based team update (incl. removal)
//   4. Cancel: stop all running actors for a team
//
// R4 legacy-hygiene batch (2026-10-29): removed the dead crash-recovery
// subtree — TurnStateStore.save had no production writer (the restore path
// read files nothing ever wrote) and UnmountBranch/WatchAgent/ResumeAgent/
// Shutdown had zero senders repo-wide.

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
   *
   * Block 0 registration chain (supervision trio §B2): also stamps
   * parentSessionId — a member's parent is its team Manager, the Manager's
   * parent is the mounting root (parentForRecord). Fallback = rootSessionId
   * so the chain is never empty for team records.
   */
  def registerActor(
    sid: String,
    ref: ActorRef[AgentCommand],
    resources: AgentRuntimePort,
    rootSessionId: String
  ): IO[Unit] =
    actorMap.update(_ + (sid -> ref)) *>
      parentForRecord(sid).flatMap { parentOpt =>
        resources.agentRegistry.update(
          _ + (sid ->
            AgentRecord(sid, ref, AgentKind.Team, rootSessionId, parentSessionId = parentOpt.getOrElse(rootSessionId)))
        )
      }

  def unregisterActor(sid: String): IO[Unit] =
    actorMap.update(_ - sid)

  /** P1: also remove from the unified AgentRegistry. */
  def unregisterActor(sid: String, resources: AgentRuntimePort): IO[Unit] =
    actorMap.update(_ - sid) *> resources.agentRegistry.update(_ - sid)

  def getRunningActor(sid: String): IO[Option[ActorRef[AgentCommand]]] =
    actorMap.get.map(_.get(sid))

  def registerManager(instance: String, sid: String): IO[Unit] =
    managerMap.update(_ + (instance -> sid))

  def isManager(sid: String): IO[Boolean] =
    managerMap.get.map(_.values.toSet.contains(sid))

  /**
   * Block 0 registration chain (supervision trio §B2): the Manager sessionId
   * for a team instance, if registered.
   */
  def managerOf(instance: String): IO[Option[String]] =
    managerMap.get.map(_.get(instance))

  /**
   * Block 0 registration chain: resolve the parent session for a team agent
   * record — a MEMBER's parent is its team Manager; the MANAGER's parent is
   * the mounting root session (parentSessionMap). Unknown session → None
   * (callers fall back to the activating/mounting session id).
   */
  def parentForRecord(sid: String): IO[Option[String]] =
    teamOfSession(sid).flatMap {
      case Some(inst) =>
        managerOf(inst).flatMap {
          case Some(mgr) if mgr != sid => IO.pure(Some(mgr))
          case _ => parentSessionOf(inst)
        }
      case None => IO.pure(None)
    }

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
    sessionStore: nebflow.core.SessionStorePort
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
            if agent == RootAgentIdentity.Name then Right(None)
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
            if address == RootAgentIdentity.Name then IO.pure(Right(None))
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
    resources: AgentRuntimePort,
    projectRoot: String,
    safetyMode: String,
    gatewayPort: Int = nebflow.shared.Defaults.brandDefaultPort,
    disableFileWatcher: Boolean = false,
    folderId: Option[String] = None,
    projectsDir: Option[String] = None
  )

  def apply(config: TreeConfig): Behavior[TreeCommand] =
    Behaviors.setup { ctx =>
      given ActorContext[TreeCommand] = ctx

      val flowNamesRef = Ref.unsafe[IO, Map[String, String]](Map.empty) // instanceName → teamName

      for
        _ <- logger.info(s"FlowTreeActor started for session ${config.sessionId}")
        _ <- restoreTeams(flowNamesRef, config).handleErrorWith(e =>
          logger
            .error(s"restoreTeams failed: ${e.getMessage}\n${e.getStackTrace.take(5).map(_.toString).mkString("\n")}")
            .void
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
      yield running(flowNamesRef, config, ctx)
      end for
    }

  // ============================================================
  // Running behavior
  // ============================================================

  private def running(
    flowNamesRef: Ref[IO, Map[String, String]],
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

      def receive(ctx: ActorContext[TreeCommand], msg: TreeCommand): IO[Behavior[TreeCommand]] =
        given ActorContext[TreeCommand] = ctx
        msg match
          case TreeCommand.MountTeam(teamDef) =>
            handleMountTeam(flowNamesRef, cfg, teamDef).as(this)

          case TreeCommand.ReloadDefinition(flowName) =>
            handleReload(flowNamesRef, cfg, flowName).as(this)

          case TreeCommand.CancelPipeline(name) =>
            handleCancel(cfg, name).as(this)
        end match

      end receive
  end running

  // ============================================================
  // Team mount (team.json format)
  // ============================================================

  private def handleMountTeam(
    flowNamesRef: Ref[IO, Map[String, String]],
    cfg: TreeConfig,
    teamDef: TeamDef
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
              // F2-1 (#433): auto-mount does NOT registerParentSession — the
              // parentSessionMap is a global single-writer registry and any
              // short-lived session (e2e, tests, an accidentally opened empty
              // session) running restoreTeams would otherwise hijack the
              // team's root-session ownership (last-writer-wins), rerouting
              // permission buckets, InteractionHub cards and mailbox Mail into
              // a zombie session (incident 2026-08-26). Only EXPLICIT mounts
              // (flows.json entries, branch above) claim parentship.
              _ <- TeamSessionRegistry.registerParentActor(sid, cfg.parentAgentRef)
              _ <- flowNamesRef.update(_ + (teamDef.name -> teamDef.name))
              _ <- logger.info(s"Auto-mounted team '${teamDef.name}' (no parent registration, F2-1)")
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
  // File watcher — notify agent of stale changes
  // ============================================================

  private def startFileWatcher(ctx: ActorContext[TreeCommand], cfg: TreeConfig): IO[Unit] =
    val flowsDir = (nebflow.shared.PathUtil.dataRoot / "flows").toIO.toPath
    val agentsDir = (nebflow.shared.PathUtil.dataRoot / "agents").toIO.toPath
    val teamsDir = (nebflow.shared.PathUtil.dataRoot / "teams").toIO.toPath
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
                  s"Flow '$name' has disk changes not yet applied. Use Flow(action: \"update\", name: \"$name\") to apply.",
                  fromUser = false // ② 文件监视器注入（服务端），不是真人输入
                )
              }
            else if watchDir == agentsDir || (agentsDir.getRoot == watchDir.getRoot && watchDir.startsWith(agentsDir))
            then
              cfg.parentAgentRef ! AgentCommand.ImmediateInput(
                s"Agent definition '$fileName' has disk changes. The change will take effect on next activation.",
                fromUser = false
              )
            else if watchDir == teamsDir || (teamsDir.getRoot == watchDir.getRoot && watchDir.startsWith(teamsDir)) then
              val teamName = teamsDir.relativize(watchDir).getName(0).toString
              cfg.parentAgentRef ! AgentCommand.ImmediateInput(
                s"Team '$teamName' has disk changes. Reload to apply.",
                fromUser = false
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
