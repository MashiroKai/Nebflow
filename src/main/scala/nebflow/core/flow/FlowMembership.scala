package nebflow.core.flow

import cats.effect.{IO, Ref}
import nebflow.actor.ActorRef
import nebflow.agent.AgentCommand

/**
 * Global registry tracking which agents belong to which flows.
 *
 * Session model: (flowId, agentName) → sessionId → ActorRef (on demand).
 * Mounting creates sessions; Mail activates actors.
 */
object FlowMembership:

  // (flowId, agentName) → sessionId
  private val sessionRegistry: Ref[IO, Map[(String, String), String]] =
    Ref.unsafe[IO, Map[(String, String), String]](Map.empty)

  // sessionId → flowId (which flow this session belongs to)
  private val sessionFlows: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  // flowId → manager sessionId (for Nebula to Mail flow managers by flow name)
  private val flowManagers: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  // sessionId → ActorRef (running actors; None means session exists but not activated)
  private val runningActors: Ref[IO, Map[String, ActorRef[AgentCommand]]] =
    Ref.unsafe[IO, Map[String, ActorRef[AgentCommand]]](Map.empty)

  // Sessions currently processing a turn (agentStart fired, agentDone/End not yet)
  private val busySessions: Ref[IO, Set[String]] =
    Ref.unsafe[IO, Set[String]](Set.empty)

  // flowId → parent session ID (the Nebula session that mounted this flow)
  private val flowParentSessions: Ref[IO, Map[String, String]] =
    Ref.unsafe[IO, Map[String, String]](Map.empty)

  // parent session ID → ActorRef (for delivering flow results back to Nebula).
  // Separate from runningActors to avoid flow cancel stopping Nebula's actor.
  private val parentActors: Ref[IO, Map[String, ActorRef[AgentCommand]]] =
    Ref.unsafe[IO, Map[String, ActorRef[AgentCommand]]](Map.empty)

  /** Register a parent actor for result delivery (e.g. Nebula's AgentActor). */
  def registerParentActor(sessionId: String, ref: ActorRef[AgentCommand]): IO[Unit] =
    parentActors.update(_.updated(sessionId, ref))

  /** Get a parent actor for direct delivery (fallback when session is not a flow agent). */
  def getParentActor(sessionId: String): IO[Option[ActorRef[AgentCommand]]] =
    parentActors.get.map(_.get(sessionId))

  /** Register the parent session (Nebula session) for a flow instance. */
  def registerParentSession(flowId: String, parentSessionId: String): IO[Unit] =
    flowParentSessions.update(_.updated(flowId, parentSessionId))

  /** Get the parent session ID for a flow instance. */
  def parentSessionOf(flowId: String): IO[Option[String]] =
    flowParentSessions.get.map(_.get(flowId))

  /** Register a flow agent's session at mount time.
   *  Called by FlowTreeActor when mounting a flow. */
  def registerSession(flowId: String, agentName: String, sessionId: String): IO[Unit] =
    sessionRegistry.update(_.updated((flowId, agentName), sessionId)) *>
      sessionFlows.update(_.updated(sessionId, flowId))

  /** Register an alias entry: makes `aliasName` in `flowId` point to an existing
   *  session, WITHOUT changing the session's flow ownership in sessionFlows.
   *  Used for pipeline mounting: the pipeline manager's session gets an alias
   *  in the parent project so the project manager can Mail the pipeline by its alias name. */
  def registerAlias(flowId: String, aliasName: String, sessionId: String): IO[Unit] =
    sessionRegistry.update(_.updated((flowId, aliasName), sessionId))

  /** Register a flow's manager session (for Mail-based triggering by flow name). */
  def registerFlowManager(flowId: String, managerSessionId: String): IO[Unit] =
    flowManagers.update(_.updated(flowId, managerSessionId))

  /** Resolve a team agent by exact (instanceName, agentName) lookup.
   *  Use when the caller knows the team name — avoids ambiguity when
   *  multiple teams share the same agent names (e.g., all teams have "Manager"). */
  def resolveTeamAgent(teamName: String, agentName: String): IO[Option[String]] =
    sessionRegistry.get.map(_.get((teamName, agentName)))

  /** Resolve a short name to a sessionId.
   *  Resolution order:
   *  1. Sender's flow scope (same flow agent)
   *  2. Cross-flow matches — ONLY for Nebula (no flowId). Team agents
   *     are restricted to their own flow scope to prevent cross-team leaks.
   *  3. Flow manager name — ONLY for Nebula (flow trigger by name)
   *  4. Nebula escalation — team managers can reach their parent session
   */
  def resolveSessionId(senderSessionId: String, name: String): IO[Option[String]] =
    for
      sf <- sessionFlows.get
      sr <- sessionRegistry.get
      fm <- flowManagers.get
      fps <- flowParentSessions.get
      flowId = sf.get(senderSessionId)
      isManager = fm.values.exists(_ == senderSessionId)
      // Step 1: try sender's own flow
      ownFlow = flowId.flatMap(fid => sr.get((fid, name)))
      // Step 2: cross-flow matches — only for Nebula (flowId is None)
      crossFlowMatches = if flowId.isEmpty then
        sr.collect { case ((_, agentName), sid) if agentName == name => sid }
      else Nil
      // Step 3: flow manager lookup — only for Nebula
      flowMgrLookup = if flowId.isEmpty then fm.get(name) else None
    yield
      ownFlow
        .orElse(crossFlowMatches.headOption)
        .orElse(flowMgrLookup)
        .orElse(if name == "Nebula" && isManager then flowId.flatMap(fid => fps.get(fid)) else None)

  /** Check if a session is a flow/team manager (team lead). */
  def isManager(sessionId: String): IO[Boolean] =
    flowManagers.get.map(_.values.exists(_ == sessionId))

  /** Get a running actor for a session, if activated. */
  def getRunningActor(sessionId: String): IO[Option[ActorRef[AgentCommand]]] =
    runningActors.get.map(_.get(sessionId))

  /** Register a running actor for a session (when activated). */
  def registerActor(sessionId: String, ref: ActorRef[AgentCommand]): IO[Unit] =
    runningActors.update(_.updated(sessionId, ref))

  /** Unregister a running actor (when actor stops). */
  def unregisterActor(sessionId: String): IO[Unit] =
    runningActors.update(_ - sessionId) *> busySessions.update(_ - sessionId)

  /** Mark a session as busy (currently processing a turn). */
  def markBusy(sessionId: String): IO[Unit] =
    busySessions.update(_ + sessionId)

  /** Mark a session as idle (finished processing). */
  def markIdle(sessionId: String): IO[Unit] =
    busySessions.update(_ - sessionId)

  /** Check if a session is currently processing a turn. */
  def isBusy(sessionId: String): IO[Boolean] =
    busySessions.get.map(_.contains(sessionId))

  /** Reverse lookup: sessionId → (flowId, agentName). */
  def agentOfSession(sessionId: String): IO[Option[(String, String)]] =
    for
      sf <- sessionFlows.get
      sr <- sessionRegistry.get
      flowId = sf.get(sessionId)
    yield flowId.flatMap { fid =>
      sr.collectFirst { case ((`fid`, name), `sessionId`) => (fid, name) }
    }

  /** Remove a flow's session registrations (unmount).
   *  Also removes nested sub-flows (e.g. `flowId/sub-flow`). */
  def unregisterFlowSessions(flowId: String): IO[Unit] =
    val matches = (fid: String) => fid == flowId || fid.startsWith(s"$flowId/")
    sessionRegistry.update(_.filterNot { case ((fid, _), _) => matches(fid) }) *>
      sessionFlows.update(_.filterNot { case (_, fid) => matches(fid) }) *>
      flowManagers.update(_.filterNot { (fid, _) => matches(fid) }) *>
      flowParentSessions.update(_.filterNot { (fid, _) => matches(fid) })

  /** Unregister a single agent session (for hot reload — removing one member).
   *  Only removes from the top-level flowId, NOT sub-flows. */
  def unregisterAgent(instanceName: String, agentName: String, sessionId: String): IO[Unit] =
    sessionRegistry.update(_ - ((instanceName, agentName))) *>
      sessionFlows.update(_ - sessionId) *>
      flowManagers.update(_.filterNot { case (_, sid) => sid == sessionId }) *>
      busySessions.update(_ - sessionId)

  /** Get top-level agents (lead + members) for a team instance.
   *  Excludes sub-flow agents (those with `/` in flowId). */
  def agentsOfInstance(instanceName: String): IO[List[(String, String)]] =
    sessionRegistry.get.map { reg =>
      reg.collect { case ((`instanceName`, name), sid) => (name, sid) }.toList.sortBy(_._1)
    }

  /** List mounted sub-flow names for a team instance.
   *  E.g. instanceName="nebflow-project" → ["code-review", "release-beta"] */
  def subFlowsOf(instanceName: String): IO[List[String]] =
    val prefix = s"$instanceName/"
    sessionRegistry.get.map { reg =>
      reg.keys.collect {
        case (fid, _) if fid.startsWith(prefix) =>
          fid.substring(prefix.length).takeWhile(_ != '/') // handle nested
      }.toSet.toList.sorted
    }

  /** Get all session IDs for a flow, including nested sub-flows. */
  def sessionIdsOf(flowId: String): IO[List[String]] =
    val matches = (fid: String) => fid == flowId || fid.startsWith(s"$flowId/")
    sessionRegistry.get.map(_.collect {
      case ((fid, _), sid) if matches(fid) => sid
    }.toList)

  /** List all mounted flows with their agent names.
   *  Sub-flows (compound flowIds containing `/`) are hidden — their agents
   *  are accessible as aliases in the parent flow.
   *  Returns Map[flowId -> List[(agentName, sessionId)]]. */
  def listMountedFlows: IO[Map[String, List[(String, String)]]] =
    sessionRegistry.get.map { reg =>
      reg.groupBy { case ((flowId, _), _) => flowId }
        .filterNot { (flowId, _) => flowId.contains("/") }
        .map { (flowId, entries) =>
          flowId -> entries.toList.map { case ((_, agentName), sid) => (agentName, sid) }
            .sortBy(_._1)
        }
    }

  /** Get flow name for a session. */
  def flowOfSession(sessionId: String): IO[Option[String]] =
    sessionFlows.get.map(_.get(sessionId))

  /** Check if a name is a mounted flow manager (for Mail-based triggering). */
  def isFlowManager(flowId: String, managerName: String): IO[Option[String]] =
    sessionRegistry.get.map(_.get((flowId, managerName)))

  /** Clear all state (tests only). */
  def clear: IO[Unit] =
    sessionRegistry.set(Map.empty) *>
      sessionFlows.set(Map.empty) *> flowManagers.set(Map.empty) *>
      runningActors.set(Map.empty) *> flowParentSessions.set(Map.empty) *>
      busySessions.set(Set.empty)

end FlowMembership
