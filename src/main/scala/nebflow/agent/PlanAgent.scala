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
 * PlanAgent — spawns a specialized read-only sub-agent that produces an
 * implementation plan, displayed on a canvas for user approval.
 *
 * The plan agent supports multi-turn interaction: after each turn, the user
 * can send feedback to refine the plan, approve it, or cancel.
 *
 * Architecture:
 *   - Plan agent: full AgentActor with read-only tools, custom system prompt
 *   - Adapter: thin bridge that converts AgentEvent → AgentCommand messages
 *     back to the main agent. Stays alive across turns (unlike Delegate's
 *     adapter which stops after one completion).
 */
object PlanAgent:
  private val logger = NebflowLogger.forName("nebflow.agent.plan")

  /** Read-only tools available to the plan agent. */
  val PlanTools: List[String] = List("Read", "Glob", "Grep", "WebSearch", "WebFetch", "RemoveUnnecessary")

  /** System prompt for the plan agent. */
  val SystemPrompt: String =
    """You are a planning agent. Your job is to analyze code and create a detailed implementation plan.

## Your Role

You investigate the codebase using read-only tools and produce a structured plan. You CANNOT modify files or execute commands — you only read, search, and analyze.

## Rules

- Use Read, Grep, Glob to explore the codebase thoroughly before planning.
- Use WebSearch/WebFetch for external documentation if needed.
- Do NOT attempt to write, edit, or execute anything.
- Produce your plan in a clear, structured format:

```
## Plan: <one-line summary>

### Step 1: <title>
- **What**: what to do
- **Files**: which files to modify or create
- **Details**: key implementation considerations

### Step 2: <title>
...

### Dependencies
- Step 2 depends on Step 1

### Risks
- Potential issues and mitigations
```

- Be specific: reference exact file paths, function names, and line numbers.
- Keep the plan actionable — each step should be independently executable.
- When the user gives feedback, adjust your plan and output the revised version.
- Do NOT create tasks (TaskCreate) — just output the plan as text."""

  /**
   * Spawn a plan agent and its adapter.
   *
   * @return the plan agent's ActorRef (for forwarding feedback)
   */
  def spawn(
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
      planDef = AgentDef(
        name = "Planner",
        description = "Plan agent (read-only analysis)",
        tools = PlanTools,
        systemPrompt = SystemPrompt
      )
      planAgentRef <- system.spawn(
        AgentActor(
          agentDef = planDef,
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
          projectRoot = Some(projectRoot)
        ),
        planAgentId
      )
      adapterRef <- system.spawn(
        planAdapter(planAgentRef, mainAgentRef),
        s"$planAgentId-adapter"
      )
      _ = logger.info(s"Spawned plan agent: $planAgentId (depth=$childDepth)")
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
   *
   * - Completed → PlanTurnComplete(text) — main agent stores text, sends planReady
   * - Failed → PlanFailed(error) — main agent exits plan mode
   * - Terminated → PlanFailed — safety net if plan agent crashes
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
