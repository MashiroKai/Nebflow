package nebflow.core.flow

import cats.effect.IO
import cats.syntax.all.*
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.entity.*
import nebflow.core.tools.{FileHistory, ReadTracker}

/**
 * Activates flow agent sessions on demand.
 *
 * When a Mail arrives for a flow agent that doesn't have a running actor,
 * this object creates the actor (activates the session), similar to how
 * WebSocketRoutes.ensureRootAgent opens a regular session.
 *
 * The flow context (peer list, duty, communication rules) is injected
 * into the system prompt at activation time, read fresh from disk.
 */
object FlowAgentActivator:
  private val logger = NebflowLogger.forName("nebflow.flow.activator")

  /**
   * Resolve a flow agent's definition from disk by team name + agent name.
   *  Used by WebSocketRoutes for session restoration after server restart.
   */
  def resolveAgentFromDisk(
    flowName: String,
    agentName: String,
    resources: SharedResources
  ): IO[Option[AgentDef]] =
    for
      teamOpt <- nebflow.core.entity.EntityLoader.loadTeam(flowName)
      result <- teamOpt match
        case Some(_) =>
          nebflow.core.entity.EntityLoader.loadTeamAgent(flowName, agentName).map { entryOpt =>
            entryOpt.map { entry =>
              AgentDef(
                name = entry.name,
                description = entry.description,
                tools = entry.tools,
                systemPrompt = entry.systemPrompt,
                voiceEnabled = entry.voice,
                category = entry.category,
                mcpServers = entry.mcpServers
              )
            }
          }
        case None =>
          logger.info(s"resolveAgentFromDisk: team '$flowName' not found on disk").as(None: Option[AgentDef])
    yield result

  /**
   * Ensure a flow agent session has a running actor.
   *  If already running, return the existing ActorRef.
   *  If not, activate by creating a new AgentActor.
   */
  def ensureSession(
    sessionId: String,
    senderRef: Option[ActorRef[AgentCommand]],
    resources: SharedResources,
    actorSystem: ActorSystem,
    wsSend: Option[io.circe.Json => IO[Unit]]
  ): IO[Option[ActorRef[AgentCommand]]] =
    for
      existing <- FlowMembership.getRunningActor(sessionId)
      result <- existing match
        case Some(ref) => IO.pure(Some(ref))
        case None => activate(sessionId, senderRef, resources, actorSystem, wsSend)
    yield result

  /** Activate a flow agent session: load definition, inject context, create actor. */
  private def activate(
    sessionId: String,
    senderRef: Option[ActorRef[AgentCommand]],
    resources: SharedResources,
    actorSystem: ActorSystem,
    wsSend: Option[io.circe.Json => IO[Unit]]
  ): IO[Option[ActorRef[AgentCommand]]] =
    for
      metaOpt <- resources.sessionStore.getSessionMeta(sessionId)
      result <- metaOpt match
        case None =>
          logger.warn(s"activate: session '$sessionId' not found").as(None)
        case Some(meta) =>
          meta.flowName match
            case None =>
              logger.warn(s"activate: session '$sessionId' has no flowName — not a flow agent").as(None)
            case Some(flowName) =>
              activateFlowAgent(
                sessionId,
                flowName,
                meta.agentName.getOrElse("agent"),
                senderRef,
                resources,
                actorSystem,
                wsSend,
                meta.safetyMode
              )
    yield result

  /** Full activation sequence for a single flow agent. */
  private def activateFlowAgent(
    sessionId: String,
    flowName: String,
    agentName: String,
    senderRef: Option[ActorRef[AgentCommand]],
    resources: SharedResources,
    actorSystem: ActorSystem,
    wsSend: Option[io.circe.Json => IO[Unit]],
    safetyMode: String
  ): IO[Option[ActorRef[AgentCommand]]] =
    for
      teamOpt <- nebflow.core.entity.EntityLoader.loadTeam(flowName)
      result <- teamOpt match
        case Some(teamDef) =>
          activateTeamAgent(
            sessionId,
            flowName,
            agentName,
            teamDef,
            senderRef,
            resources,
            actorSystem,
            wsSend,
            safetyMode
          )
        case None =>
          logger.warn(s"activate: no team.json for '$flowName'").as(None)
    yield result

  /** Activate a team agent using team.json + global agent library. */
  private def activateTeamAgent(
    sessionId: String,
    teamName: String,
    agentName: String,
    teamDef: TeamDef,
    senderRef: Option[ActorRef[AgentCommand]],
    resources: SharedResources,
    actorSystem: ActorSystem,
    wsSend: Option[io.circe.Json => IO[Unit]],
    safetyMode: String
  ): IO[Option[ActorRef[AgentCommand]]] =
    val rawWsSend = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
    for
      agentEntryOpt <- nebflow.core.entity.EntityLoader.loadTeamAgent(teamName, agentName)
      result <- agentEntryOpt match
        case None =>
          logger.warn(s"activate: agent '$agentName' not found in global library").as(None)
        case Some(entry) =>
          val agentDef = AgentDef(
            name = entry.name,
            description = entry.description,
            tools = entry.tools,
            systemPrompt = entry.systemPrompt,
            voiceEnabled = entry.voice,
            mcpServers = entry.mcpServers
          )
          val isLead = teamDef.lead == agentName
          for
            // Load all team member agent entries + flow DAG defs for context
            allAgents <- nebflow.core.entity.EntityLoader.listAgents()
            teamAgents = allAgents.filter((name, _) => name == teamDef.lead || teamDef.members.contains(name))
            allFlows <- nebflow.core.entity.EntityLoader.listFlows()
            teamFlows = allFlows.filter((name, _) => teamDef.flows.contains(name))
            // Build team context
            teamContext = buildTeamContext(teamName, agentName, isLead, teamDef, teamAgents, teamFlows)
            combinedPrompt = s"$teamContext${agentDef.systemPrompt}"
            finalDef = agentDef.copy(
              systemPrompt = combinedPrompt,
              tools = ensureMail(agentDef.tools),
              voiceEnabled = false
            )
            // Load message history
            history <- resources.sessionStore.loadMessagesForSession(sessionId)
            readTracker <- ReadTracker.create
            fileHistory <- FileHistory.create()
            // Wrap wsSend with nodeSessionId + busy tracking (same as flow agents)
            wrappedWsSend = (json: io.circe.Json) =>
              val hasNodeSid = json.hcursor.downField("nodeSessionId").as[io.circe.Json].isRight
              val toSend =
                if hasNodeSid then json
                else json.deepMerge(io.circe.Json.obj("nodeSessionId" -> io.circe.Json.fromString(sessionId)))
              val eventType = json.hcursor.downField("type").as[String].toOption.getOrElse("")
              val busyUpdate = eventType match
                case "agentStart" => FlowMembership.markBusy(sessionId)
                case "agentDone" | "agentEnd" | "interrupted" => FlowMembership.markIdle(sessionId)
                case "done" if hasNodeSid => FlowMembership.markIdle(sessionId)
                case "error" if hasNodeSid => FlowMembership.markIdle(sessionId)
                case _ => IO.unit
              busyUpdate *> rawWsSend(toSend)
            _ <- logger.info(s"Activating team agent '$agentName' (team: $teamName, session: ${sessionId.take(8)})")
            ref <- actorSystem.spawn(
              AgentActor(
                agentDef = finalDef,
                resources = resources,
                wsSend = wrappedWsSend,
                depth = 1,
                parentRef = senderRef,
                sessionId = Some(sessionId),
                sessionName = Some(s"$teamName/$agentName"),
                initialMessages = history,
                readTracker = Some(readTracker),
                fileHistory = Some(fileHistory),
                contextWindow = resources.contextWindow,
                safetyMode = safetyMode,
                expectsMail = true
              ),
              s"teamagent-${agentName.take(10)}-${sessionId.take(8)}"
            )
            _ <- FlowMembership.registerActor(sessionId, ref)
            _ <- notifyTreeToWatch(sessionId, ref)
          yield Some(ref)
          end for
    yield result
    end for
  end activateTeamAgent

  // ============================================================
  // Helpers
  // ============================================================

  /**
   * Notify the FlowTreeActor for this session's parent to watch the agent ref.
   *  Silently skips if the tree actor can't be found (e.g. during tests).
   */
  private def notifyTreeToWatch(sessionId: String, ref: ActorRef[AgentCommand]): IO[Unit] =
    for
      flowIdOpt <- FlowMembership.flowOfSession(sessionId)
      parentSidOpt <- flowIdOpt match
        case Some(fid) => FlowMembership.parentSessionOf(fid)
        case None => IO.pure(None)
      treeRefOpt <- parentSidOpt match
        case Some(sid) => FlowTreeRegistry.get(sid)
        case None => IO.pure(None)
      _ <- treeRefOpt match
        case Some(treeRef) => (treeRef ! TreeCommand.WatchAgent(ref, sessionId)).void
        case None => IO.unit
    yield ()
    end for

  private def ensureMail(tools: List[String]): List[String] =
    if tools.contains("*") || tools.contains("Mail") then tools
    else tools :+ "Mail"

  /**
   * Build the Team context string injected into a team agent's system prompt.
   *  Uses TeamCatalog for the roster, adds Mail usage rules.
   */
  def buildTeamContext(
    teamName: String,
    agentName: String,
    isLead: Boolean,
    team: TeamDef,
    agents: Map[String, AgentEntry],
    flows: Map[String, FlowDagDef]
  ): String =
    val catalog = TeamCatalog.buildCatalog(team, agents, flows)
    val leadNote =
      if isLead then "\n\nWhen the team's work is complete, Mail your final summary to the caller."
      else ""

    s"""$catalog$leadNote
       |
       |You are "$agentName" in this team.
       |Usage: Mail("agent_name", "your message"). Use short names only.
       |
       |Rules:
       |- All team members can Mail each other.
       |- Focus on your task. When done, Mail your result to the lead or next agent.

       |- You are an execution pipeline, not an AI assistant. No pleasantries, no unnecessary explanations.
       |- Report results via Mail only. Do not stream output to the user.
       |- Workers report to the Team Lead via Mail. Do not contact agents outside this team directly.
       |""".stripMargin
  end buildTeamContext

end FlowAgentActivator
