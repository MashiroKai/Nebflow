package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.actor.{InjectionAttribution, RootAgentIdentity}
import nebflow.core.project.{ProjectActor, ProjectRuntimeRegistry, TaskLedgerHistory, TaskLedgerStore}

/**
 * AgentFlow — the Nebula root's **dispatch tool**: hand one self-contained brief to a
 * project; the brief IS the dispatcher session's entire context.
 *
 * Relation to the existing name family (one thing, one name):
 *  - `Mail` = the message primitive (a different thing: AgentFlow does not deliver a
 *    message, it dispatches a brief);
 *  - `NodeEdit` = the **dispatcher's** counterpart duty (build / edit nodes from the
 *    brief the root hands over);
 *  - `Task` / `TaskInfo` = the task ledger's write / read face (a different thing);
 *  - `SendMessage` = friend / device messaging (a different thing).
 *
 * **Authorization face (mechanism-fixed, single point)**: the name is carried by
 * `AgentCore.RootOrchestrationTools` alone; `DispatcherFixedTools` / `BaseTools` /
 * every other agent face do not carry it, and an `agent.json` declaration (including
 * `"*"`) grants nothing — the name lives in `AgentCore.RootExclusiveTools`, the
 * declaration-escape-prevention single point (which also covers the `AgentLibrary`
 * definition-save-side strip). The plugin allowlist
 * (`PluginRegistry.BuiltinToolWhitelist`) and the remote-executable face
 * (`RemoteExecutor.remoteableTools`) do NOT carry it (a dispatch tool has zero
 * `device` semantics and must never be `augmentSchema`-augmented).
 *
 * **Runtime identity gate (second safeguard, fail-closed)**: [[ToolContext.isNebulaRoot]]
 * — a delegated single point; a non-root session, a direct REST call or a spec harness
 * (`agentDef = None`) is refused with `AGENTFLOW_NEBULA_ONLY`. Never a silent
 * no-op, never a fallback to another face.
 *
 * **Execute path** = the engine's existing project trigger entry
 * (`ProjectActor.TriggerDispatcher`, the same core the Mail `project:` leg uses) —
 * reusing the engine single point rather than shipping a parallel dispatch path. The
 * ledger attribution mirrors the Mail project leg (auto-create without `task`;
 * continuation with `task`; the terminal-revive branch is inherited unchanged), so the
 * task number and the note timeline stay one mechanism, not two.
 */
object AgentFlowTool extends Tool:

  val name = "AgentFlow"

  /**
   * Model-visible description (the nine-anchor brief contract). Verbatim from the
   * design pack's W-10 answer face (`20261001_140737_agentflow-s16-pack__chain-dispatch-taskface.md`
   * §十 P2 §1 fenced block): English, zero process text, no "see some document".
   */
  val description =
    """Dispatch one self-contained brief to a project; the brief IS the dispatcher's entire context.
Write the brief in the nine anchored sections below, each label on its own line:
OBJECT, CHAIN, POSITION, ACCEPTANCE, BOUNDARY, PREMISE-BASE, PREMISE-UPSTREAM, PREMISE-RULING, RESOURCE.
The brief must stand alone: it may cite only persistent artifacts (a task file path, a spec path, a design card path, a repo path with line numbers) and must never refer to an earlier message, an earlier dispatch, or an unstated convention.
ACCEPTANCE entries must be mechanically checkable - each one carries a witness command or a recomputable count.
BOUNDARY entries are the forbidden surfaces: paths, ports, command families.
PREMISE-BASE carries the main tip read at creation time; PREMISE-UPSTREAM names each upstream node with its status and verdict; PREMISE-RULING names the governing decision by its on-disk location.
CHAIN names the chain this position belongs to; POSITION names the node and its role and kind; RESOURCE names the ports, the isolated-instance flag and the time slot.
A brief missing any anchor, or one leaning on relative references instead of the artifacts above, is a defect: rewrite it before the position starts."""

  /**
   * Input schema (the three parameters). The nine anchor lines are written **inside
   * the `message` body**, not as schema fields, so the schema needs only
   * `message` / `project` / `task` — the anchor-duty is carried by
   * `message.description`.
   *
   * Shape = the repo's existing tool family (`def name` carries the tool name and the
   * schema object is the parameter face — the pack's `{"name": …, "parameters": …}`
   * wrapper is the pack's presentation form, not this repo's `Tool.inputSchema`
   * contract); the parameter names, types and descriptions are verbatim from the pack
   * §十 P2 §2 fenced block.
   */
  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "message" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> ("The self-contained brief. Required. It must carry the nine anchored sections (OBJECT, CHAIN, POSITION, ACCEPTANCE, BOUNDARY, PREMISE-BASE, PREMISE-UPSTREAM, PREMISE-RULING, RESOURCE), one label per line. " +
          "Relative references (\"same as above\", \"as last time\", \"the usual convention\", \"earlier in this session\") are defects: cite a persistent artifact instead.").asJson
      ),
      "project" -> Json.obj(
        "type" -> "string".asJson,
        "description" -> "The mounted project to dispatch into. Required. The dispatcher of that project receives the brief.".asJson
      ),
      "task" -> Json.obj(
        "type" -> "number".asJson,
        "description" -> "Optional. The task number this dispatch continues, when it reuses an existing task instead of creating one.".asJson
      )
    ),
    "required" -> Json.arr("message".asJson, "project".asJson)
  )

  override def summarize(input: JsonObject): String =
    val proj = input("project").flatMap(_.asString).getOrElse("?")
    val brief = input("message").flatMap(_.asString).getOrElse("").take(60).replace("\n", " ")
    s"AgentFlow(project=$proj: $brief)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 200 then result.take(197) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    IO.blocking {
      val brief = input("message").flatMap(_.asString).map(_.trim).getOrElse("")
      val project = input("project").flatMap(_.asString).map(_.trim).getOrElse("")
      val task = input("task").flatMap(_.asNumber).map(_.toLong.toString)
      (brief, project, task)
    }.flatMap { case (brief, project, task) =>
      // Runtime identity gate (the second safeguard; the mount face is the first):
      // engine-side identity via the delegated single point, never a client parameter.
      if !ctx.isNebulaRoot then
        IO.pure(
          Left(
            ToolError(
              "AgentFlow: permission denied — this tool is exclusive to the Nebula root session (a project dispatcher " +
                "builds nodes with NodeEdit; a node session has no dispatch duty). (AGENTFLOW_NEBULA_ONLY)"
            )
          )
        )
      else if project.isEmpty then
        IO.pure(Left(ToolError("AgentFlow: the `project` parameter is required — name the mounted project to dispatch into. (AGENTFLOW_PROJECT_REQUIRED)")))
      else if brief.isEmpty then
        IO.pure(Left(ToolError("AgentFlow: the `message` parameter is required — it must carry the nine anchored sections (OBJECT, CHAIN, POSITION, ACCEPTANCE, BOUNDARY, PREMISE-BASE, PREMISE-UPSTREAM, PREMISE-RULING, RESOURCE), one label per line. (AGENTFLOW_MESSAGE_REQUIRED)")))
      else dispatch(project, brief, task, ctx)
    }

  /**
   * Reuse the engine's project trigger entry (`ProjectActor.TriggerDispatcher`) — the
   * same core as the Mail `project:` leg, not a parallel dispatch path. The ledger
   * attribution (auto-create / continue / revive) mirrors that leg so the task number
   * and the note timeline stay one mechanism.
   */
  private def dispatch(
    projectName: String,
    brief: String,
    task: Option[String],
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    ProjectRuntimeRegistry.get(projectName).flatMap {
      case None =>
        IO.pure(
          Left(
            ToolError(
              s"Project '$projectName' is not mounted — AgentFlow dispatches into a mounted project (the same entry as Mail to a project: " +
                "ProjectActor.TriggerDispatcher). Mounted projects mount at gateway startup; re-mount / restart, or check the exact name. (AGENTFLOW_PROJECT_UNMOUNTED)"
            )
          )
        )
      case Some(rt) =>
        rt.actorRef match
          case None =>
            IO.pure(Left(ToolError(s"Project '$projectName' has no mounted ProjectActor — re-mount it. (AGENTFLOW_NO_ACTOR)")))
          case Some(ref) =>
            val rootSid = ctx.rootSessionId.orElse(ctx.sessionId).getOrElse("")
            val ledger = TaskLedgerStore.open()
            val resolved: IO[Either[ToolError, (String, Option[String])]] = task match
              case None =>
                IO.blocking(
                  ledger.createSyncReturningId(
                    title = s"$projectName — ${brief.take(120).replace("\n", " ")}",
                    actor = TaskLedgerHistory.Actors.Dispatcher
                  )
                ).map(_.map(id => (id, Option.empty[String])))
              case Some(tid) =>
                IO.blocking(ledger.findSync(tid.stripPrefix("#").trim)).flatMap {
                  case None =>
                    IO.pure(
                      Left(
                        ToolError(
                          s"AgentFlow: no task '#${tid.stripPrefix("#")}' in the ledger — it was never created (or it was pruned after reaching a terminal state). " +
                            s"Omit `task` to have the engine create a new one and return its number. (${TaskLedgerStore.Codes.NotFound})"
                        )
                      )
                    )
                  case Some(entry) if entry.status != TaskLedgerStore.Status.Open =>
                    IO.blocking(ledger.reviveSync(entry.id)).map(_.map(_ => (entry.id, Option(entry.status))))
                  case Some(entry) => IO.pure(Right((entry.id, Option.empty[String])))
                }
            resolved.flatMap {
              case Left(err) => IO.pure(Left(err))
              case Right((taskIdStr, revivedPrior)) =>
                // Attribution carries the sender + eventType only. 🔴 `intake` is
                // deliberately left NULL: the intake marker is the Mail channel's
                // presentation identity (`IntakeMail` = the "Mail" badge, pinned to
                // MailTool leg ① by `InjectionIntakeContractSpec`), and this tool is a
                // different thing -- borrowing that marker would make the dispatcher's
                // intake bubble claim "Mail". Giving AgentFlow its own badge means a new
                // marker value + frontend registration (`INJECTED_SOURCE_LABELS` +
                // the two contract specs) -- a face outside this batch (the brief's
                // landing scope covers zero `web/**` and zero frontend specs). Flagged
                // as an open item in the delivery report, pending an author ruling.
                val attribution = InjectionAttribution(
                  sender = ctx.agentDef.map(_.name).orElse(Some(RootAgentIdentity.Name)),
                  eventType = Some("info"),
                  project = ctx.projectName
                )
                (ref ! ProjectActor.ProjectCommand.TriggerDispatcher(
                  brief,
                  rootSid,
                  ProjectActor.SourceTask,
                  Some(attribution),
                  Some(taskIdStr),
                  revive = revivedPrior.isDefined
                )).void *>
                  IO.blocking(
                    ledger.appendNoteSync(
                      taskIdStr,
                      brief,
                      from = TaskLedgerHistory.Origins.Nebula,
                      actor = TaskLedgerHistory.Actors.Nebula
                    )
                  ) *>
                  IO.pure(
                    Right(
                      s"[task #$taskIdStr] Project '$projectName' dispatcher triggered with the brief (AgentFlow)"
                    )
                  )
            }
    }

end AgentFlowTool
