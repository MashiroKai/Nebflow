package nebflow.agent

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.*
import nebflow.agent.AgentCommand.*
import nebflow.core.NebflowLogger
import nebflow.core.tools.{FileHistory, ReadTracker}
import nebflow.shared.{Message, MessageRole}

/**
 * PlanAgent — spawns a read-only sub-agent (using the existing Planner agent
 * definition from the agent library) that produces an implementation plan,
 * displayed on a canvas for user approval.
 *
 * The plan agent supports multi-turn interaction: after each turn, the user
 * can send feedback to refine the plan, approve it, or cancel.
 *
 * Architecture:
 *   - Plan agent: full AgentActor with the Planner AgentDef (read-only tools)
 *   - Adapter: thin bridge that converts AgentEvent → AgentCommand messages
 *     back to the main agent. Stays alive across turns (unlike Delegate's
 *     adapter which stops after one completion).
 */
object PlanAgent:
  private val logger = NebflowLogger.forName("nebflow.agent.plan")

  /** Planning prompt prepended to the planning agent's system prompt. */
  val PlanningPrompt: String =
    """## Planning Mode
      |
      |You are in planning mode. Your job is to analyze the task and produce a clear implementation plan.
      |
      |- Read and understand the relevant code before planning.
      |- Use Read, Grep, Glob, Bash (read-only) to investigate.
      |- Do NOT modify any files — you have no write tools.
      |- Break down the task into clear, ordered steps with specific file paths.
      |- For each step: what to do, which files to touch, potential risks.
      |- End with a structured plan that can be directly executed.""".stripMargin

  /**
   * Spawn a plan agent and its adapter.
   *
   * @param agentDef  the Planner agent definition from the library
   * @return the plan agent's ActorRef (for forwarding feedback)
   */
  def spawn(
    agentDef: AgentDef,
    task: String,
    mainAgentRef: ActorRef[AgentCommand],
    system: ActorSystem,
    resources: nebflow.agent.SharedResources,
    parentDepth: Int,
    wsSend: Json => IO[Unit],
    projectRoot: String,
    parentSessionId: Option[String]
  ): IO[ActorRef[AgentCommand]] =
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      childDepth = parentDepth + 1
      planAgentId = s"plan-agent-${java.util.UUID.randomUUID().toString.take(8)}"
      childWsSend = routeWsSend(wsSend, parentSessionId)
      planAgentRef <- system.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = childWsSend,
          depth = childDepth,
          parentRef = Some(mainAgentRef),
          sessionId = parentSessionId,
          sessionName = Some("Plan"),
          initialMessages = Nil,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          projectRoot = Some(projectRoot),
          // P2: the plan agent shares the main agent's session — it inherits
          // the same root-session policy bucket.
          rootSessionId = parentSessionId.getOrElse("")
        ),
        planAgentId
      )
      adapterRef <- system.spawn(
        planAdapter(planAgentRef, mainAgentRef),
        s"$planAgentId-adapter"
      )
      _ = logger.info(s"Spawned plan agent: $planAgentId (depth=$childDepth, agent=${agentDef.name})")
      // Send planStart event to frontend so it opens the canvas
      _ <- wsSend(
        Json.obj(
          "type" -> "planStart".asJson,
          "sessionId" -> parentSessionId.asJson,
          "agentId" -> planAgentId.asJson,
          "task" -> task.asJson
        )
      )
      // Kick off the plan agent with the user's task
      _ <- planAgentRef ! AgentCommand.UserInput(task, Some(adapterRef))
    yield planAgentRef

  /**
   * Adapter: converts plan agent's AgentEvent messages into AgentCommand
   * messages on the main agent. Stays alive across multiple turns to support
   * the feedback loop.
   */
  private def planAdapter(
    planAgentRef: ActorRef[AgentCommand],
    mainAgentRef: ActorRef[AgentCommand]
  ): Behavior[AgentEvent] =
    Behaviors.setup { ctx =>
      given system: ActorSystem = ctx.system
      ctx.watch(planAgentRef)

      IO.pure(
        new Behavior[AgentEvent]:
          def receive(ctx: ActorContext[AgentEvent], event: AgentEvent): IO[Behavior[AgentEvent]] =
            event match
              case AgentEvent.Completed(_, messages) =>
                val text = extractLastAssistantText(messages)
                val planText = if text.nonEmpty then text else "(plan agent produced no text output)"
                (mainAgentRef ! AgentCommand.PlanTurnComplete(planText)) *>
                  IO.pure(this)
              case AgentEvent.Failed(_, error) =>
                (mainAgentRef ! AgentCommand.PlanFailed(error.message)) *>
                  IO.pure(Behaviors.stopped[AgentEvent])

          override def onSignal(ctx: ActorContext[AgentEvent], signal: SystemSignal): IO[Behavior[AgentEvent]] =
            signal match
              case SystemSignal.Terminated(_) =>
                (mainAgentRef ! AgentCommand.PlanFailed("Plan agent terminated unexpectedly")) *>
                  IO.pure(Behaviors.stopped[AgentEvent])
      )
    }

  /** Inject parent sessionId into event JSON so frontend can route events. */
  private def routeWsSend(
    wsSend: Json => IO[Unit],
    parentSessionId: Option[String]
  ): Json => IO[Unit] =
    parentSessionId match
      case Some(sid) => json => wsSend(json.deepMerge(Json.obj("sessionId" -> sid.asJson)))
      case None => wsSend

  /** Extract the last assistant message text from a list of messages. */
  private def extractLastAssistantText(messages: List[Message]): String =
    messages.reverse
      .collectFirst {
        case msg if msg.role == MessageRole.Assistant => msg.textContent
      }
      .filter(_.nonEmpty)
      .getOrElse("")

end PlanAgent
