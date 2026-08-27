package nebflow.core.entity

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.given
import nebflow.actor.*
import nebflow.agent.*
import nebflow.core.NebflowLogger
import nebflow.core.flow.{NodeStatus, VerdictFamily}
import nebflow.core.tools.{FileHistory, FlowReportStore, ReadTracker}
import nebflow.shared.{Message, MessageRole}

import scala.concurrent.duration.*

/**
 * Executes a Flow DAG deterministically.
 *
 *  Unlike the Mail-driven flow model where agents coordinate emergently,
 *  the DAG executor follows explicit routing rules defined in flow.json.
 *  Each node runs its agent, produces output, and the executor routes to the
 *  next node based on onComplete rules.
 *
 *  Error handling follows Actor Supervision model:
 *  - resume: log warning, continue routing
 *  - restart: re-execute node (up to maxRetries)
 *  - stop: abort flow, return error
 *
 *  R8-P2 execution model: runNode is reused as a branch WALKER. A Parallel
 *  route forks one fiber per fan target; all walkers share the Ref-backed
 *  execution state (outputs/slots/loop counts merged via Ref.update).
 *  Multi-in-edge nodes are counting barriers: each parallel round arms every
 *  join reachable from its branches with the number of expected arrivals;
 *  each arrival decrements, the last one activates the join exactly once
 *  (dynamic indegree — a revise back-edge re-arms the barriers per round).
 */
object FlowDagExecutor:
  private val logger = NebflowLogger.forName("nebflow.entity.executor")

  /** Error text marking a failure that is merely a SYMPTOM of fail-fast (not a root cause). */
  private val AbortSentinel = "Flow aborted: sibling parallel branch failed"

  /**
   * Terminal outcome of one branch walk. Serial flows only ever produce
   * Returned; parallel branch fibers may end Converged (their barrier
   * contribution is done — the join's activation walk continues on the
   * last-arriving fiber) or Failed.
   */
  private enum WalkEnd:
    case Returned(output: String)
    case Converged
    case Failed(nodeId: String, error: String)

  /** Barrier arrival classification (see advance). */
  private enum Arrival:
    case Serial // target not barrier-armed — run it directly
    case Wait // decremented; siblings still pending — this fiber ends
    case Activate // last arrival — this fiber runs the join node

  /**
   * Merge parallel branch-fiber outcomes into the dispatch result:
   * root-cause failure (a fail-fast sibling reports "Flow aborted: ..." —
   * prefer the original branch failure when one exists), else the first
   * $return reached by an activation walk, else a barrier deadlock error.
   */
  private def mergeOutcomes(
    from: String,
    rawOutcomes: List[Either[Throwable, Either[WalkEnd.Failed, WalkEnd]]]
  ): Either[WalkEnd.Failed, WalkEnd] =
    val outcomes: List[Either[WalkEnd.Failed, WalkEnd]] = rawOutcomes.map {
      case Right(w)  => w
      case Left(err) => Left(WalkEnd.Failed(from, s"branch fiber crashed: ${err.getMessage}"))
    }
    val failures: List[WalkEnd.Failed] = outcomes.collect { case Left(f) => f }
    val rootCause: Option[WalkEnd.Failed] =
      failures.find(f => !f.error.contains(AbortSentinel)).orElse(failures.headOption)
    rootCause match
      case Some(f) => Left(f)
      case None =>
        val firstReturn: Option[String] = outcomes.collectFirst { case Right(WalkEnd.Returned(o)) => o }
        firstReturn match
          case Some(output) =>
            val returns = outcomes.count {
              case Right(WalkEnd.Returned(_)) => true
              case _                          => false
            }
            if returns > 1 then
              logger.warnSync(s"parallel round from '$from' produced $returns returns — taking the first")
            Right(WalkEnd.Returned(output))
          case None =>
            Left(
              WalkEnd.Failed(
                from,
                s"parallel branches of '$from' all converged but no join activated — barrier deadlock"
              )
            )

  /** Per-execution shared state. Ref.unsafe is fine: refs are local to one execute() run. */
  private final class ExecState:
    val outputs: Ref[IO, Map[String, String]] = Ref.unsafe(Map.empty)
    val slots: Ref[IO, Map[String, Map[String, Json]]] = Ref.unsafe(Map.empty)
    val loopCounts: Ref[IO, Map[String, Int]] = Ref.unsafe(Map.empty)
    val pendingJoins: Ref[IO, Map[String, Int]] = Ref.unsafe(Map.empty)
    /** Dynamic fan instances: template node id → instantiated ids in creation order (template#1 … template#N). */
    val instances: Ref[IO, Map[String, List[String]]] = Ref.unsafe(Map.empty)
    /** Instantiated node definitions (input substituted): instance id → FlowNode. */
    val instanceNodes: Ref[IO, Map[String, FlowNode]] = Ref.unsafe(Map.empty)
    /** Flow-node supervision P2 (2026-08-26): nodeId → stable dag session id.
      * Owned here (not inside executeAgent) so a Restart retries the SAME
      * session — checkpoint recovery loads the persisted messages instead of
      * re-running the node from scratch (20min of planner context survives a
      * 60s upstream stall). */
    val nodeSessions: Ref[IO, Map[String, String]] = Ref.unsafe(Map.empty)

  /**
   * Execute a flow DAG.
   *
   *  @param flow The DAG definition
   *  @param taskInput The initial task string (replaces "$task" in entry node input)
   *  @param resources Shared resources (for agent activation)
   *  @param actorSystem Actor system (for spawning agent actors)
   *  @param wsSend WebSocket send (for streaming output)
   *  @return Either error message or final output string
   */
  def execute(
    flow: FlowDagDef,
    taskInput: String,
    resources: SharedResources,
    actorSystem: ActorSystem,
    wsSend: Option[Json => IO[Unit]],
    instanceId: String,
    parentAgentRef: Option[ActorRef[AgentCommand]] = None,
    rootSessionId: String = "",
    // Structured trigger parameters (validated by FlowTriggerTool against
    // flow.params schema). Resolved into node inputs via $params.<name>;
    // a missing key (no default, not provided) inserts a visible placeholder.
    params: Map[String, Json] = Map.empty,
    // #406: true when this flow was defined inline by FlowExecute (one-shot,
    // no flows/ directory). Dynamic flows resolve node agents from the GLOBAL
    // library only — never from flows/<name>/agents/ (a dynamic flow named
    // "research" must not accidentally pick up the predefined research flow's
    // specialized agents). FlowReport is injected by execution context.
    dynamic: Boolean = false,
    // #407 (Q3): triggering agent's own sessionId — recorded on the RunningFlow
    // so the Mail idle gate can associate a running flow with its owner.
    callerSessionId: String = ""
  ): IO[Either[String, String]] =

    val emitWs = wsSend.getOrElse((_: Json) => IO.unit)

    /** Emit a flowProgress WS event. Status serializes via NodeStatus.wire (stable wire values). */
    def emitProgress(nodeId: String, status: NodeStatus, extra: (String, Json)*): IO[Unit] =
      val fields = List(
        "type" -> "flowProgress".asJson,
        "instanceId" -> instanceId.asJson,
        "flowName" -> flow.name.asJson,
        "nodeId" -> nodeId.asJson,
        "status" -> status.wire.asJson
      ) ++ extra
      emitWs(Json.obj(fields*))

    val st = new ExecState

    // ---- static graph analysis (immutable per execution) ----
    // Dynamic fans introduce join nodes the static in-degree analysis misses:
    // N runtime instances converge on the template's onComplete target, which
    // the dispatch arms with N at runtime.
    val joins: Set[String] = FlowStructure.joinNodes(flow) ++ FlowStructure.dynamicJoinNodes(flow)
    // For each node: the barrier joins reachable from it WITHOUT crossing
    // another join — i.e. the joins a walker from that node is expected to
    // arrive at. Arms barriers when a parallel fan fires; settles them when
    // a collect-mode branch dies before arriving.
    val expectedJoins: Map[String, Set[String]] =
      flow.nodes.keys
        .map(n => n -> FlowStructure.reachFrom(flow, n, joins)._1.filter(joins.contains))
        .toMap

    /**
     * Resolve template variables: $task -> task input, $<nodeId>.output -> node
     * output, $<nodeId>.slots.<field> -> structured slot value (string slots
     * insert directly; array slots insert compact JSON — deterministic and
     * structure-preserving).
     *  Uses quoteReplacement to prevent $ and \ in agent output from being
     * interpreted as regex group references (Illegal group reference error).
     */
    def resolveInput(template: String): IO[String] =
      for
        outs <- st.outputs.get
        slotsAll <- st.slots.get
        instsAll <- st.instances.get
      yield
        // $params.<name> first — structured trigger parameters (string values
        // insert directly; others compact JSON; missing → visible placeholder
        // so the planner prompt's fallback wording kicks in).
        val paramsPattern = "\\$params\\.([a-zA-Z0-9_-]+)".r
        val withParams = paramsPattern.replaceAllIn(
          template,
          m =>
            java.util.regex.Matcher.quoteReplacement(
              params.get(m.group(1)) match
                case Some(json) if json.isString => json.asString.getOrElse("")
                case Some(json)                  => json.noSpaces
                case None                        => s"[param ${m.group(1)} not provided]"
            )
        )
        val withTask = withParams.replace("$task", taskInput)
        // Dynamic-fan aggregates: $<template>.all.output (instances' outputs in
        // index order, each with a "=== Track {{index}} ===" header) and
        // $<template>.all.slots.<field> (same order, joined text / compact JSON).
        def aggregateAllOutputs(t: String): String =
          instsAll
            .getOrElse(t, Nil)
            .map { id =>
              val idx = id.split("#").last
              s"=== Track $idx ===\n${outs.getOrElse(id, s"[output of $id not found]")}"
            }
            .mkString("\n\n")
        def aggregateAllSlots(t: String, field: String): String =
          instsAll
            .getOrElse(t, Nil)
            .map { id =>
              slotsAll.get(id).flatMap(_.get(field)) match
                case Some(json) if json.isString => json.asString.getOrElse("")
                case Some(json)                  => json.noSpaces
                case None => s"[slots.$field of $id not found]"
            }
            .mkString("\n")
        val allSlotsPattern = "\\$([a-zA-Z0-9_-]+)\\.all\\.slots\\.([a-zA-Z0-9_-]+)".r
        val withAllSlots = allSlotsPattern.replaceAllIn(
          withTask,
          m =>
            java.util.regex.Matcher.quoteReplacement(aggregateAllSlots(m.group(1), m.group(2)))
        )
        val allOutputPattern = "\\$([a-zA-Z0-9_-]+)\\.all\\.output".r
        val withAll = allOutputPattern.replaceAllIn(
          withAllSlots,
          m => java.util.regex.Matcher.quoteReplacement(aggregateAllOutputs(m.group(1)))
        )
        val slotsPattern = "\\$([a-zA-Z0-9_-]+)\\.slots\\.([a-zA-Z0-9_-]+)".r
        val withSlots = slotsPattern.replaceAllIn(
          withAll,
          m =>
            java.util.regex.Matcher.quoteReplacement(
              slotsAll.get(m.group(1)).flatMap(_.get(m.group(2))) match
                case Some(json) if json.isString => json.asString.getOrElse("")
                case Some(json)                  => json.noSpaces
                case None => s"[slots.${m.group(2)} of ${m.group(1)} not found]"
            )
        )
        val pattern = "\\$([a-zA-Z0-9_-]+)\\.output".r
        pattern.replaceAllIn(
          withSlots,
          m =>
            java.util.regex.Matcher.quoteReplacement(
              outs.getOrElse(m.group(1), s"[output of ${m.group(1)} not found]")
            )
        )

    /**
     * The node's output contract (R8-P1): Switch case keys + outputs slot
     * schema. Injected into the spawned agent so FlowReport validates the
     * verdict enum and slot types at call time.
     */
    def nodeContract(node: FlowNode): Option[FlowNodeContract] =
      val caseKeys = node.onComplete match
        case NodeRoute.Switch(_, cases, _, _) => cases.keySet
        case _                                => Set.empty[String]
      if caseKeys.isEmpty && node.outputs.isEmpty then None
      else Some(FlowNodeContract(caseKeys, node.outputs))

    /** Resolve a node definition: static flow nodes, or dynamic instances. */
    def nodeDefOf(nodeId: String): IO[Option[FlowNode]] =
      flow.nodes.get(nodeId) match
        case Some(node) => IO.pure(Some(node))
        case None       => st.instanceNodes.get.map(_.get(nodeId))

    /** P2 supervision: the node's dag session id. Only a checkpoint-resume
      * re-entry (handleResult Restart after a retryable failure) reuses the
      * preserved session; every other dispatch (first attempt, loop re-entry
      * via a revise back-edge, non-retryable Restart) gets a FRESH session —
      * a revise round is a new review, not a crash recovery. */
    def nodeSession(nodeId: String, resumeCheckpoint: Boolean): IO[(Boolean, String)] =
      st.nodeSessions.get.flatMap { m =>
        m.get(nodeId) match
          case Some(existing) if resumeCheckpoint => IO.pure((true, existing))
          case _ =>
            val fresh = s"dag-${flow.name.take(10)}-$nodeId-${System.currentTimeMillis().toString.takeRight(6)}"
            st.nodeSessions.update(mm => mm + (nodeId -> fresh)).as((false, fresh))
      }

    /** Execute a single DAG node: spawn agent, send input, collect output.
      * P2 supervision: resumeCheckpoint=true (Restart after a retryable LLM
      * failure) resumes the node's preserved session from its persisted
      * checkpoint; every other dispatch runs fresh. */
    def executeNode(nodeId: String, resumeCheckpoint: Boolean = false): IO[NodeResult] =
      nodeDefOf(nodeId).flatMap {
        case None =>
          IO.pure(NodeResult(nodeId, "", false, Some(s"Unknown node: $nodeId")))
        case Some(node) =>
          for
            inputText0 <- resolveInput(node.input)
            // #414 fix 4b：未知占位符（{{...}} 残留）进节点前校验拦截——模板
            // 变量替换后仍含 {{ 说明 DAG 使用了引擎不认识的占位符（如未实现
            // 的变量），明确报错而非把坏模板静默喂给 agent（实测 redo-qa 带着
            // 未替换的 {{index}}/{{len}} 运行，产出误导性结果）。
            placeholderError =
              if inputText0.contains("{{") then
                Some(
                  s"Unknown template placeholder(s) in node '$nodeId' input (unresolved '{{...}}'): " +
                    s"'${inputText0.take(160)}' — check {{item}}/{{index}}/{{len}} usage"
                )
              else None
            result <- placeholderError match
              case Some(err) => IO.pure(NodeResult(nodeId, "", false, Some(err)))
              case None =>
                for
                  agentEntryOpt <-
                    if dynamic then nebflow.core.entity.EntityLoader.loadAgent(node.agent)
                    else nebflow.core.entity.EntityLoader.loadFlowAgent(flow.name, node.agent)
                  result <- agentEntryOpt match
                    case None =>
                      IO.pure(
                        NodeResult(nodeId, "", false, Some(s"Agent '${node.agent}' not found in flow or global library"))
                      )
                    case Some(entry) =>
                      // ── P2 supervision: stable session + checkpoint resume ──
                      // (get-then-put is safe: the same nodeId re-enters
                      // executeNode only serially via handleResult's Restart
                      // recursion; dynamic fan instances use distinct node ids)
                      nodeSession(nodeId, resumeCheckpoint).flatMap { (isRetry, sid) =>
                        val recoveredIO =
                          if isRetry then
                            resources.sessionStore
                              .loadMessagesForSession(sid)
                              .handleErrorWith(e =>
                                logger.warn(s"Flow '$flow.name' node '$nodeId': checkpoint load failed: ${e.getMessage}")
                                  .as(Nil)
                              )
                          else IO.pure(Nil: List[Message])
                        recoveredIO.flatMap { recoveredMsgs =>
                          // #406: FlowReport is injected by EXECUTION CONTEXT — every
                          // flow node (predefined OR dynamic) gets the verdict tool
                          // regardless of the agent's category. Predefined flow agents
                          // (category=flow) already receive it via fixedToolsFor; dynamic
                          // flows reuse standalone/team agents which would otherwise lack
                          // it. The tools list is appended (survives "*" — FlowReport is
                          // already in ALL_TOOLS there; survives explicit lists — appended
                          // after the agent's own names).
                          val baseDef = entry.toAgentDef
                          val agentDef = baseDef.copy(
                            flowContract = nodeContract(node),
                            tools = if baseDef.tools.contains("FlowReport") then baseDef.tools else baseDef.tools :+ "FlowReport"
                          )
                          // Resume semantics (BackoffSupervisor :267-274 pattern, prod-
                          // verified): recovered messages already contain the original
                          // instruction — inject a continue prompt, not the original
                          // input. No checkpoint (fresh attempt OR empty session) →
                          // original input, initialMessages=Nil.
                          val resuming = recoveredMsgs.nonEmpty
                          val effectiveInput =
                            if resuming then
                              "[system] Your previous turn was interrupted by an upstream failure. " +
                                "Please continue your task from where you left off."
                            else inputText0
                          val resumeLog =
                            if resuming then
                              logger.info(
                                s"Flow '$flow.name' node '$nodeId': checkpoint restart — resuming session $sid " +
                                  s"with ${recoveredMsgs.size} recovered messages"
                              )
                            else IO.unit
                          // Session preserved on retryable failure only when the node
                          // actually allows a Restart that could use the checkpoint.
                          val restartAllowed = node.onError.contains(OnError.Restart) && node.maxRetries > 0
                          resumeLog *> executeAgent(
                            nodeId,
                            node.agent,
                            agentDef,
                            effectiveInput,
                            resources,
                            actorSystem,
                            wsSend,
                            flow.name,
                            instanceId,
                            parentAgentRef,
                            rootSessionId,
                            sessionId = sid,
                            initialMessages = recoveredMsgs,
                            keepSessionOnFailure = restartAllowed
                          )
                        }
                      }
                  _ <- st.outputs.update(_ + (nodeId -> result.output))
                  _ <- st.slots.update(_ + (nodeId -> result.slots))
                yield result
          yield result
          end for
      }

    /** Terminal bookkeeping for a node's dag session (P2 supervision, updated
      * P1 deck-v6): drop the FlowReport payload and the executor's
      * nodeSessions map entry on every terminal outcome — but PRESERVE the
      * session files. The flow-run panel opens a node's conversation from
      * these files after the node (or the whole flow) has finished; deleting
      * them left completed nodes unopenable.
      *
      * A preserved checkpoint between a retryable failure and its Restart is
      * unaffected: the session simply stays until the retry reuses it. */
    def cleanupNodeSession(nodeId: String): IO[Unit] =
      st.nodeSessions.get.flatMap(_.get(nodeId).traverse_ { sid =>
        FlowReportStore.remove(sid)
      }) *> st.nodeSessions.update(m => m - nodeId)

    /** Error handling + retry logic.
      * P2 supervision: the Restart branch retries the SAME node session with
      * checkpoint recovery (executeNode loads persisted messages + continue
      * instruction) — not a fresh re-run. attemptCount accounting unchanged
      * (node.maxRetries remains the single budget). */
    def handleResult(nodeId: String, result: NodeResult): IO[Either[WalkEnd.Failed, NodeResult]] =
      if result.success then cleanupNodeSession(nodeId).map(_ => Right(result))
      else
        nodeDefOf(nodeId).flatMap {
          case None => IO.pure(Left(WalkEnd.Failed(nodeId, s"Unknown node '$nodeId' in error handler")))
          case Some(node) =>
            node.onError.getOrElse(OnError.Stop) match
              case OnError.Resume =>
                cleanupNodeSession(nodeId) *> logger.warn(s"Node '$nodeId' failed, resuming").map(_ => Right(result))
              case OnError.Restart =>
                val retryCount = result.attemptCount
                if retryCount <= node.maxRetries then
                  for
                    _ <- logger.info(
                      s"Node '$nodeId' retry ${retryCount}/${node.maxRetries}" +
                        (if result.retryable then " (checkpoint resume — retryable LLM failure)"
                         else " (fresh re-run)")
                    )
                    _ <- st.nodeSessions.get.flatMap { m =>
                      val retryEvent = Json.obj(
                        "type" -> "subagentRetry".asJson,
                        "agentName" -> node.agent.asJson,
                        "childSessionId" -> m.getOrElse(nodeId, "").asJson,
                        "restartCount" -> retryCount.asJson,
                        "maxRestarts" -> node.maxRetries.asJson,
                        "backoffMs" -> 0.asJson,
                        "description" -> s"flow node '$nodeId'".asJson
                      )
                      emitWs(retryEvent).handleErrorWith(_ => IO.unit)
                    }
                    newResult <- executeNode(nodeId, resumeCheckpoint = result.retryable)
                    handled <- handleResult(nodeId, newResult.copy(attemptCount = retryCount + 1))
                  yield handled
                else
                  cleanupNodeSession(nodeId) *> IO.pure(
                    Left(
                      WalkEnd.Failed(
                        nodeId,
                        s"Node '$nodeId' failed after $retryCount retries: ${result.error.getOrElse("unknown")}"
                      )
                    )
                  )
              case OnError.Stop =>
                cleanupNodeSession(nodeId) *> IO.pure(
                  Left(WalkEnd.Failed(nodeId, s"Node '$nodeId' failed: ${result.error.getOrElse("unknown")}"))
                )
        }

    /**
     * R8-P1 strict verdict, second line of defense: a switch node that ended
     * WITHOUT a FlowReport verdict fails the NODE (onError chain applies —
     * Stop aborts, Restart re-runs the agent, Resume falls through to the
     * route() backstop). Guess extraction is opt-in via the switch's lenient
     * flag; strictVerdict=false keeps the legacy behavior completely unchanged.
     */
    def strictVerdictFailure(node: Option[FlowNode], result: NodeResult): Option[String] =
      if !(flow.strictVerdict && result.success && result.verdict.isEmpty) then None
      else
        node.flatMap { n =>
          n.onComplete match
            case NodeRoute.Switch(expr, cases, _, lenient) if !lenient =>
              Some(
                s"Switch '$expr' received no FlowReport verdict (flow '${flow.name}' " +
                  s"strictVerdict=true, switch lenient=false). Expected verdict one of: " +
                  s"${cases.keys.toList.sorted.mkString(", ")}"
              )
            case _ => None
        }

    /** Count one traversal of an edge; Left when the loop cap is exceeded. */
    def tickEdge(from: String, to: String): IO[Either[String, Unit]] =
      st.loopCounts.modify { m =>
        val key = s"$from->$to"
        val n = m.getOrElse(key, 0) + 1
        if n > flow.maxLoop then (m, Left(s"Max loop (${flow.maxLoop}) exceeded at edge $key"))
        else (m + (key -> n), Right(()))
      }

    /** Run a node: execute -> handleResult -> route (one step of a branch walk). */
    def walk(nodeId: String): IO[Either[WalkEnd.Failed, WalkEnd]] =
      for
        // Check cancellation (user) and internal fail-fast (sibling branch
        // failure under onFail=abort) before executing each node.
        cancelled <- nebflow.core.flow.RunningFlowRegistry.isCancelled(instanceId)
        aborted <- nebflow.core.flow.RunningFlowRegistry.isAborted(instanceId)
        result <-
          if cancelled then IO.pure(Left[WalkEnd.Failed, WalkEnd](WalkEnd.Failed(nodeId, "Flow cancelled by user")))
          else if aborted then
            IO.pure(Left[WalkEnd.Failed, WalkEnd](WalkEnd.Failed(nodeId, AbortSentinel)))
          else
            for
              _ <- logger.info(s"Flow '${flow.name}': executing node '$nodeId'")
              nodeDef <- nodeDefOf(nodeId)
              _ <- nebflow.core.flow.RunningFlowRegistry.setNodeStatus(instanceId, nodeId, NodeStatus.Running)
              _ <- emitProgress(nodeId, NodeStatus.Running)
              result0 <- executeNode(nodeId)
              result = strictVerdictFailure(nodeDef, result0) match
                case Some(msg) => result0.copy(success = false, error = Some(msg))
                case None      => result0
              // Cancel may have pierced the node mid-run (executeAgent races
              // the cancel signal) — re-check so the node's terminal status
              // reflects "cancelled" instead of a misleading "failed".
              cancelledNow <- nebflow.core.flow.RunningFlowRegistry.isCancelled(instanceId)
              status = NodeStatus.toNodeStatus(result, cancelled = cancelledNow)
              _ <-
                if status == NodeStatus.Completed then
                  nebflow.core.flow.RunningFlowRegistry.setNodeStatus(
                    instanceId,
                    nodeId,
                    NodeStatus.Completed,
                    result.output
                  ) *>
                    emitProgress(
                      nodeId,
                      NodeStatus.Completed,
                      "output" -> (if result.output.length > 200 then result.output.take(197) + "..."
                                   else result.output).asJson
                    )
                else
                  nebflow.core.flow.RunningFlowRegistry.setNodeStatus(
                    instanceId,
                    nodeId,
                    status,
                    "",
                    result.error.getOrElse("unknown")
                  ) *>
                    emitProgress(nodeId, status, "error" -> result.error.getOrElse("unknown").asJson)
              handled <- handleResult(nodeId, result)
              finalResult <- handled match
                case Left(f)  => IO.pure(Left(f))
                case Right(nr) => route(nr)
            yield finalResult
      yield result

    /** Loop-check the edge, then arrive at the target (barrier-aware). */
    def advance(from: String, target: String): IO[Either[WalkEnd.Failed, WalkEnd]] =
      tickEdge(from, target).flatMap {
        case Left(err) => IO.pure(Left(WalkEnd.Failed(from, err)))
        case Right(()) =>
          if !joins.contains(target) then walk(target)
          else
            st.pendingJoins
              .modify { m =>
                m.get(target) match
                  case Some(n) if n > 1 => (m + (target -> (n - 1)), Arrival.Wait)
                  case Some(_)          => (m - target, Arrival.Activate)
                  case None             => (m, Arrival.Serial)
              }
              .flatMap {
                case Arrival.Serial =>
                  walk(target)
                case Arrival.Wait =>
                  logger.info(s"Flow '${flow.name}': barrier '$target' waiting for remaining arrivals") *>
                    IO.pure(Right(WalkEnd.Converged))
                case Arrival.Activate =>
                  logger.info(s"Flow '${flow.name}': barrier '$target' released — activating join") *>
                    walk(target)
              }
      }

    /** Route to next node based on onComplete rules. */
    def route(result: NodeResult): IO[Either[WalkEnd.Failed, WalkEnd]] =
      nodeDefOf(result.nodeId).flatMap {
        case None =>
          IO.pure(Left(WalkEnd.Failed(result.nodeId, s"Unknown node '${result.nodeId}' in routing")))
        case Some(node) =>
          def routeTo(r: NodeRoute): IO[Either[WalkEnd.Failed, WalkEnd]] =
            r match
              case NodeRoute.Return        => IO.pure(Right(WalkEnd.Returned(result.output)))
              case NodeRoute.Goto(target)  => advance(result.nodeId, target)
              case p: NodeRoute.Parallel   => parallelDispatch(result.nodeId, p.fan, p.onFail)
              case pd: NodeRoute.ParallelDynamic => parallelDispatchDynamic(result.nodeId, pd)
              case NodeRoute.Switch(_, _, _, _) =>
                IO.pure(Left(WalkEnd.Failed(result.nodeId, "Nested switch not supported in routing")))
          node.onComplete match
            case NodeRoute.Return       => IO.pure(Right(WalkEnd.Returned(result.output)))
            case NodeRoute.Goto(target) => advance(result.nodeId, target)
            case p: NodeRoute.Parallel  => parallelDispatch(result.nodeId, p.fan, p.onFail)
            case pd: NodeRoute.ParallelDynamic => parallelDispatchDynamic(result.nodeId, pd)
            case NodeRoute.Switch(switchExpr, cases, default, lenient) =>
              // Verdict priority: FlowReport verdict (structured, trustworthy) →
              // extract from output via the legacy pipeline — but ONLY when the
              // flow is not strict, or this switch explicitly opts back in via
              // lenient. strictVerdict=true + no verdict = routing failure
              // (normally already caught at the node level in walk; this is
              // the backstop for onError=resume paths).
              st.outputs.get.flatMap { outs =>
                val rawValueOpt: Option[String] = result.verdict.orElse {
                  if flow.strictVerdict && !lenient then None
                  else Some(extractSwitchValue(switchExpr, result.output, outs, cases))
                }
                rawValueOpt match
                  case None =>
                    IO.pure(
                      Left(
                        WalkEnd.Failed(
                          result.nodeId,
                          s"Switch '$switchExpr' received no FlowReport verdict (flow '${flow.name}' " +
                            s"strictVerdict=true, switch lenient=false). Expected verdict one of: " +
                            s"${cases.keys.toList.sorted.mkString(", ")}"
                        )
                      )
                    )
                  case Some(rawValue) =>
                    // Normalize: exact → same-family → substring (warn). flow.json
                    // declares only standard keys; aliases (pass/success/done → ok,
                    // fail/error → error) are the engine's responsibility.
                    val matchedKey = VerdictFamily.matchCase(rawValue, cases.keySet)
                    def routeTo(r: NodeRoute): IO[Either[WalkEnd.Failed, WalkEnd]] =
                      r match
                        case NodeRoute.Return       => IO.pure(Right(WalkEnd.Returned(result.output)))
                        case NodeRoute.Goto(target) => advance(result.nodeId, target)
                        case p: NodeRoute.Parallel  => parallelDispatch(result.nodeId, p.fan, p.onFail)
                        case pd: NodeRoute.ParallelDynamic => parallelDispatchDynamic(result.nodeId, pd)
                        case NodeRoute.Switch(_, _, _, _) =>
                          IO.pure(Left(WalkEnd.Failed(result.nodeId, "Nested switch not supported in routing")))
                    matchedKey match
                      case Some(key) => routeTo(cases(key))
                      case None =>
                        // No case matched — conservative `default` route, else a clear
                        // error listing the available cases.
                        default match
                          case Some(r) => routeTo(r)
                          case None =>
                            IO.pure(
                              Left(
                                WalkEnd.Failed(
                                  result.nodeId,
                                  s"Switch '$switchExpr' value '$rawValue' matched no case. " +
                                    s"Available cases: ${cases.keys.toList.sorted.mkString(", ")}"
                                )
                              )
                            )
                  end match
              }
      }

    /** Settle barriers a dead branch will never arrive at; returns joins to activate now. */
    def settleJoins(expected: Set[String]): IO[List[String]] =
      expected.toList.foldLeft(IO.pure(List.empty[String])) { (acc, j) =>
        acc.flatMap { fired =>
          st.pendingJoins
            .modify { m =>
              m.get(j) match
                case Some(n) if n > 1 => (m + (j -> (n - 1)), Nil)
                case Some(_)          => (m - j, j :: Nil)
                case None             => (m, Nil) // never armed — nothing owed
            }
            .map(fired ++ _)
        }
      }

    /** Run fired activations sequentially on this fiber; first failure/return wins. */
    def activateAll(activations: List[String]): IO[Either[WalkEnd.Failed, WalkEnd]] =
      activations.foldLeftM[IO, Either[WalkEnd.Failed, WalkEnd]](Right(WalkEnd.Converged)) { (acc, j) =>
        acc match
          case Left(_)                  => IO.pure(acc)
          case Right(WalkEnd.Returned(_)) => IO.pure(acc)
          case Right(_)                 => walk(j)
      }

    /**
     * Fork one fiber per fan target, all walking the DAG concurrently; arm
     * counting barriers for every join reachable from the branches; wait for
     * all fibers to end and merge their outcomes.
     *
     *  - abort (default): the first branch failure fires RunningFlowRegistry
     *    .failFast — sibling agents are pierced exactly like a user cancel —
     *    and the failure propagates as the flow's failure.
     *  - collect: a failed branch writes a placeholder output for the failed
     *    node, settles the barriers it will never arrive at (the join still
     *    releases once every expected arrival is accounted for), and the
     *    round continues; the join/verifier sees annotated partial results
     *    and decides via its verdict.
     *
     * The join's activation walk runs on the LAST-ARRIVING fiber (advance),
     * so this dispatch's merged outcome carries the continuation result.
     */
    def parallelDispatch(
      from: String,
      fan: List[String],
      onFail: NodeRoute.OnFailMode
    ): IO[Either[WalkEnd.Failed, WalkEnd]] =

      /** A branch walk failed — apply the fan's on-fail policy (in the failing fiber). */
      def onBranchFailure(failed: WalkEnd.Failed, branch: String): IO[Either[WalkEnd.Failed, WalkEnd]] =
        nebflow.core.flow.RunningFlowRegistry.isCancelled(instanceId).flatMap { userCancelled =>
          if userCancelled then IO.pure(Left(failed)) // user cancel outranks the policy
          else
            onFail match
              case NodeRoute.OnFailMode.Abort =>
                for
                  _ <- logger.warn(
                    s"Flow '${flow.name}': parallel branch '$branch' failed at '${failed.nodeId}' " +
                      s"(${failed.error.take(200)}) — fail-fast, stopping sibling branches"
                  )
                  _ <- nebflow.core.flow.RunningFlowRegistry.failFast(instanceId)
                yield Left(failed)
              case NodeRoute.OnFailMode.Collect =>
                for
                  _ <- logger.warn(
                    s"Flow '${flow.name}': parallel branch '$branch' failed at '${failed.nodeId}' — " +
                      s"collecting placeholder into outputs"
                  )
                  _ <- st.outputs.update(_ + (failed.nodeId -> s"[node ${failed.nodeId} failed: ${failed.error}]"))
                  activations <- settleJoins(expectedJoins.getOrElse(branch, Set.empty))
                  result <- activateAll(activations)
                yield result
        }

      /** Fork one fiber per fan target, join them all, merge outcomes. */
      def forkJoinMerge(): IO[Either[WalkEnd.Failed, WalkEnd]] =
        fan.traverse(t => tickEdge(from, t)).flatMap { ticks =>
          ticks.collectFirst { case Left(err) => err } match
            case Some(err) =>
              IO.pure(Left[WalkEnd.Failed, WalkEnd](WalkEnd.Failed(from, err)))
            case None =>
              // Arm barriers: each join gets += (number of branches expected to arrive)
              val armCounts: Map[String, Int] =
                fan.flatMap(b => expectedJoins.getOrElse(b, Set.empty).toList)
                  .groupBy(identity)
                  .view.mapValues(_.size).toMap
              st.pendingJoins
                .update(m => armCounts.foldLeft(m) { case (mm, (j2, c)) => mm.updated(j2, mm.getOrElse(j2, 0) + c) })
                .flatMap { _ =>
                  logger
                    .info(
                      s"Flow '${flow.name}': parallel fan-out from '$from' → ${fan.mkString(", ")}" +
                        (if armCounts.isEmpty then ""
                         else s" (barriers armed: ${armCounts.map((j2, c) => s"$j2=$c").mkString(", ")})")
                    )
                    .flatMap { _ =>
                      fan
                        .traverse { target =>
                          walk(target)
                            .flatMap {
                              case Left(failed) => onBranchFailure(failed, target)
                              case ok           => IO.pure(ok)
                            }
                            .start
                        }
                        .flatMap { fibers =>
                          fibers.traverse(f => f.joinWithNever.attempt).map { rawOutcomes =>
                            mergeOutcomes(from, rawOutcomes)
                          }
                        }
                    }
                }
        }

      forkJoinMerge()

    /**
     * Dynamic fan-out: read the owner's slot array, instantiate N copies of
     * the template node ({{item}}/{{index}} substituted), run them all
     * concurrently, and merge outcomes.
     *
     * N = min(len, maxFanout) with a warn when clamped (maxFanout is a cost
     * cap, not a target). A missing/non-array slot or len==0 follows onFail:
     * abort → clear error; collect → the template's downstream join runs with
     * an empty aggregate. Instances converge on the template's onComplete
     * target, which acts as a counting barrier armed with N (the target is in
     * `joins` via FlowStructure.dynamicJoinNodes).
     */
    def parallelDispatchDynamic(
      from: String,
      pd: NodeRoute.ParallelDynamic
    ): IO[Either[WalkEnd.Failed, WalkEnd]] =
      val template = pd.template
      val dynJoin: Option[String] =
        flow.nodes.get(template).flatMap { t =>
          FlowStructure.routeTargets(t.onComplete).headOption.collect {
            case (target, _) if target != FlowStructure.ReturnNode => target
          }
        }

      /** A branch walk failed — apply the fan's on-fail policy (in the failing fiber). */
      def onBranchFailure(failed: WalkEnd.Failed, branch: String): IO[Either[WalkEnd.Failed, WalkEnd]] =
        nebflow.core.flow.RunningFlowRegistry.isCancelled(instanceId).flatMap { userCancelled =>
          if userCancelled then IO.pure(Left(failed)) // user cancel outranks the policy
          else
            pd.onFail match
              case NodeRoute.OnFailMode.Abort =>
                for
                  _ <- logger.warn(
                    s"Flow '${flow.name}': dynamic instance '$branch' failed at '${failed.nodeId}' " +
                      s"(${failed.error.take(200)}) — fail-fast, stopping sibling instances"
                  )
                  _ <- nebflow.core.flow.RunningFlowRegistry.failFast(instanceId)
                yield Left(failed)
              case NodeRoute.OnFailMode.Collect =>
                for
                  _ <- logger.warn(
                    s"Flow '${flow.name}': dynamic instance '$branch' failed at '${failed.nodeId}' — " +
                      s"collecting placeholder into outputs"
                  )
                  _ <- st.outputs.update(_ + (branch -> s"[node $branch failed: ${failed.error}]"))
                  activations <- settleJoins(expectedJoins.getOrElse(template, Set.empty))
                  result <- activateAll(activations)
                yield result
        }

      /** No valid instances (missing/non-array slot, or empty) — per onFail. */
      def noInstances(reason: String): IO[Either[WalkEnd.Failed, WalkEnd]] =
        pd.onFail match
          case NodeRoute.OnFailMode.Abort =>
            IO.pure(Left[WalkEnd.Failed, WalkEnd](WalkEnd.Failed(from, reason)))
          case NodeRoute.OnFailMode.Collect =>
            logger.warn(
              s"Flow '${flow.name}': dynamic fanout from '$from' has no instances ($reason) — continuing with empty aggregate"
            ) *> (dynJoin match
              case Some(j) => walk(j)
              case None    => IO.pure(Right(WalkEnd.Converged)))

      st.slots.get.flatMap { slotsAll =>
        // #414 fix 4a：slotField 支持 `$node.slots.field` 全引用语法（resolveInput
        // 同款正则）——Manager 曾写 "slots": "$planner.slots.blocks" 被当字面量
        // key 查不到 → 0 实例 + onFail=collect 静默空聚合（零校验零告警）。
        // 引用语法与字面量 key 的「未命中」都是 DAG 引用错误：无论 onFail 都
        // 明确报错（区别于「key 存在但数组为空」的正常降级语义）。
        val refMatch = "\\$([a-zA-Z0-9_-]+)\\.slots\\.([a-zA-Z0-9_-]+)".r.findFirstMatchIn(pd.slotField)
        val (srcNode, field) = refMatch match
          case Some(m) => (m.group(1), m.group(2))
          case None    => (from, pd.slotField)
        slotsAll.get(srcNode).flatMap(_.get(field)) match
          case None =>
            val declared = slotsAll.get(srcNode).map(_.keys.mkString(", ")).getOrElse("(node has no slots)")
            val detail =
              if refMatch.isDefined then
                s"slot reference '${pd.slotField}' not found — node '$srcNode' has no slots field '$field' (declared: $declared)"
              else
                s"slot '${pd.slotField}' not found on node '$from' (declared: $declared) — check the DAG slots reference"
            IO.pure(Left[WalkEnd.Failed, WalkEnd](WalkEnd.Failed(from, s"dynamic fanout: $detail")))
          case Some(json) if !json.isArray =>
            noInstances(s"slot '${pd.slotField}' on node '$srcNode' is not an array")
          case Some(json) =>
            val items = json.asArray.get
            val clamped = items.size > flow.maxFanout
            val n = math.min(items.size, flow.maxFanout)
            for
              _ <-
                if clamped then
                  logger.warn(
                    s"Flow '${flow.name}': dynamic fanout from '$from' produced ${items.size} items — clamped to maxFanout ${flow.maxFanout}"
                  )
                else IO.unit
              result <-
                if n == 0 then
                  noInstances(s"slot '${pd.slotField}' on node '$from' is empty (planner produced no topics)")
                else
                  val instanceIds = (1 to n).map(i => s"$template#$i").toList
                  flow.nodes.get(template) match
                    case None =>
                      IO.pure(Left[WalkEnd.Failed, WalkEnd](WalkEnd.Failed(from, s"dynamic fan template '$template' not found in flow")))
                    case Some(templateNode) =>
                      for
                        _ <- st.instances.update(m => m.updated(template, m.getOrElse(template, Nil) ++ instanceIds))
                        _ <- st.instanceNodes.update { m =>
                          instanceIds.zipWithIndex.foldLeft(m) { case (mm, (id, idx0)) =>
                            val idx = idx0 + 1
                            val item = items(idx0)
                            val itemStr = if item.isString then item.asString.getOrElse("") else item.noSpaces
                            val input = templateNode.input
                              .replace("{{item}}", itemStr)
                              .replace("{{index}}", idx.toString)
                              // #414 fix 1: {{len}} 模板变量（SKILL.md 已承诺，
                              // 引擎从未实现）——模板实例总数，供提示词引用规模。
                              .replace("{{len}}", n.toString)
                            mm.updated(id, templateNode.copy(input = input))
                          }
                        }
                        // Register the N runtime node states first (setNodeStatus
                        // only updates existing entries) + notify the frontend.
                        _ <- nebflow.core.flow.RunningFlowRegistry.update(instanceId)(rf =>
                          rf.copy(
                            nodes = rf.nodes ++ instanceIds.map { id =>
                              id -> nebflow.core.flow.RunningFlowRegistry.NodeState(
                                nodeId = id,
                                agent = templateNode.agent,
                                status = NodeStatus.Pending
                              )
                            }.toMap
                          )
                        )
                        _ <- emitWs(
                          Json.obj(
                            "type" -> "flowNodesAdded".asJson,
                            "instanceId" -> instanceId.asJson,
                            "flowName" -> flow.name.asJson,
                            "nodes" -> instanceIds
                              .map(id =>
                                Json.obj(
                                  "nodeId" -> id.asJson,
                                  "agent" -> templateNode.agent.asJson,
                                  "status" -> "pending".asJson
                                )
                              )
                              .asJson,
                            "edges" -> instanceIds
                              .flatMap(id =>
                                dynJoin.map(j =>
                                  Json.obj(
                                    "from" -> id.asJson,
                                    "to" -> j.asJson,
                                    "condition" -> Json.Null
                                  )
                                )
                              )
                              .asJson
                          )
                        )
                        // Arm the template's downstream joins for N arrivals.
                        armCounts = expectedJoins
                          .getOrElse(template, Set.empty)
                          .toList
                          .map(j => j -> n)
                          .toMap
                        _ <- st.pendingJoins.update(m =>
                          armCounts.foldLeft(m) { case (mm, (j, c)) => mm.updated(j, mm.getOrElse(j, 0) + c) }
                        )
                        _ <- logger.info(
                          s"Flow '${flow.name}': dynamic fan-out from '$from' → ${instanceIds.mkString(", ")}" +
                            (if armCounts.isEmpty then ""
                             else s" (barriers armed: ${armCounts.map((j, c) => s"$j=$c").mkString(", ")})")
                        )
                        outcomes <- instanceIds
                          .traverse { id =>
                            walk(id)
                              .flatMap {
                                case Left(failed) => onBranchFailure(failed, id)
                                case ok           => IO.pure(ok)
                              }
                              .start
                          }
                          .flatMap(fibers => fibers.traverse(f => f.joinWithNever.attempt))
                      yield mergeOutcomes(from, outcomes)
            yield result
      }
    end parallelDispatchDynamic

    // Register the running flow, execute, then clean up
    for
      _ <- registerFlow(instanceId, flow, callerSessionId, rootSessionId)
      // Emit flowStarted so the frontend can render the full DAG immediately
      _ <- emitWs(
        Json.obj(
          "type" -> "flowStarted".asJson,
          "instanceId" -> instanceId.asJson,
          "flowName" -> flow.name.asJson,
          // #412: badge ownership — sessionId = triggering agent's own session
          // (consistent with RunningFlow.sessionId), rootSessionId = outermost
          // root (window routing). Empty string = unknown/unset.
          "sessionId" -> callerSessionId.asJson,
          "rootSessionId" -> rootSessionId.asJson,
          "description" -> flow.description.asJson,
          "entry" -> flow.entry.asJson,
          "nodes" -> flow.nodes.toList
            .sortBy(_._1)
            .map { (nodeId, node) =>
              Json.obj(
                "nodeId" -> nodeId.asJson,
                "agent" -> node.agent.asJson,
                "status" -> "pending".asJson
              )
            }
            .asJson,
          "edges" -> FlowStructure
            .displayEdges(flow)
            .map { (from, to, cond) =>
              Json.obj("from" -> from.asJson, "to" -> to.asJson, "condition" -> cond.asJson)
            }
            .asJson
        )
      )
      // No global wall-clock timeout: flow termination is event-driven —
      // routing to $return, onError Stop, or user cancel. The routing loop
      // itself is bounded by maxLoop. Node lifetime is governed by agent-
      // internal timeouts (LLM first-token / stream-idle, per-tool timeouts).
      walkResult <- walk(flow.entry)
      finalResult = walkResult match
        case Right(WalkEnd.Returned(output)) => Right(output)
        case Right(WalkEnd.Converged) =>
          Left(s"Flow '${flow.name}' ended at a join barrier without reaching $$return (barrier deadlock)")
        case Right(WalkEnd.Failed(n, e)) => Left(s"Node '$n' failed: $e") // defensive — Failed is a Left
        case Left(f)                      => Left(f.error)
      // Update final status (preserve "cancelled" if it was cancelled)
      cancelled <- nebflow.core.flow.RunningFlowRegistry.isCancelled(instanceId)
      _ <-
        if cancelled then IO.unit
        else
          nebflow.core.flow.RunningFlowRegistry.update(instanceId)(rf =>
            rf.copy(
              status = if finalResult.isRight then NodeStatus.Completed else NodeStatus.Failed,
              completedAt = Some(System.currentTimeMillis())
            )
          )
      _ <- nebflow.core.flow.RunningFlowRegistry.clearCancelled(instanceId)
      _ <- emitWs(
        Json.obj(
          "type" -> "flowCompleted".asJson,
          "instanceId" -> instanceId.asJson,
          "flowName" -> flow.name.asJson,
          // #412: same ownership fields as flowStarted (see above).
          "sessionId" -> callerSessionId.asJson,
          "rootSessionId" -> rootSessionId.asJson,
          "success" -> finalResult.isRight.asJson
        )
      )
    yield finalResult
    end for

  end execute

  /** Register a running flow instance from its DAG definition. */
  private def registerFlow(
    instanceId: String,
    flow: FlowDagDef,
    callerSessionId: String = "",
    rootSessionId: String = ""
  ): IO[Unit] =
    val nodes = flow.nodes.map { (nodeId, node) =>
      nodeId -> nebflow.core.flow.RunningFlowRegistry.NodeState(
        nodeId = nodeId,
        agent = node.agent,
        status = NodeStatus.Pending
      )
    }.toMap

    // Build edges from onComplete routing (parallel fan edges included)
    val edges = FlowStructure.displayEdges(flow)

    nebflow.core.flow.RunningFlowRegistry.register(
      nebflow.core.flow.RunningFlowRegistry.RunningFlow(
        instanceId = instanceId,
        flowName = flow.name,
        description = flow.description,
        entry = flow.entry,
        nodes = nodes,
        edges = edges,
        status = NodeStatus.Running,
        startedAt = System.currentTimeMillis(),
        // #407 (Q3): associate the running flow with its triggering agent so
        // the Mail idle gate can detect "flow in flight" (node-gap coverage).
        sessionId = Option(callerSessionId).filter(_.nonEmpty),
        // #412: outermost root session — distinct from sessionId (which stays
        // the triggering agent for #407 gate semantics). Populated from the
        // execute parameter; root ownership survives team/flow-node triggers.
        rootSessionId = Option(rootSessionId).filter(_.nonEmpty)
      )
    )

  end registerFlow

  /**
   * Spawn an AgentActor, send input, wait for completion via event-driven callback.
   *
   *  Uses a Deferred + bridge actor pattern instead of ask-with-timeout:
   *  - A temporary actor receives the AgentEvent (Completed/Failed) from the agent
   *  - The bridge completes a Deferred, unblocking executeAgent
   *  - Deliberately NO wall-clock timeout: node lifetime is event-driven —
   *    every finishTurn path guarantees the replyTo gets Completed/Failed,
   *    and actor onError recovery covers exceptional exits. Liveness is the
   *    agent's own responsibility (LLM first-token/stream-idle timeouts,
   *    per-tool timeouts). A 5-minute node timeout was tried (09f4be58) and
   *    killed legitimate long work (8-minute sbt compiles), so it was removed.
   *  - The only early exit is the user's cancel signal: cancelFlow pierces
   *    the RUNNING node (Stop the agent's turn, stop the actor) instead of
   *    waiting for the node to finish on its own.
   */
  private def executeAgent(
    nodeId: String,
    agentName: String,
    agentDef: AgentDef,
    inputText: String,
    resources: SharedResources,
    actorSystem: ActorSystem,
    wsSend: Option[Json => IO[Unit]],
    flowName: String,
    instanceId: String,
    parentAgentRef: Option[ActorRef[AgentCommand]] = None,
    rootSessionId: String = "",
    // ── Flow-node supervision P2 (2026-08-26) ──────────────────────────
    // Stable per-node session id, owned by executeNode — retries reuse the
    // SAME session so checkpoint recovery can load the persisted messages.
    sessionId: String,
    // Checkpoint recovery: previously persisted messages for this node's
    // session (Nil on a fresh first attempt).
    initialMessages: List[Message] = Nil,
    // Preserve the failed node's session (AgentActor already persisted its
    // history before failing) so a Restart resumes from the checkpoint
    // instead of a fresh re-run. Terminal outcomes always clean up.
    keepSessionOnFailure: Boolean = false
  ): IO[NodeResult] =
    val rawWsSend = wsSend.getOrElse((_: Json) => IO.unit)
    // Failure outcome carried through the deferred: message + retryable flag
    // (AgentError.retryable — LLM stall / overload class).
    final case class FailOutcome(message: String, retryable: Boolean)
    // P2: flow nodes inherit the triggering agent's root session so their
    // InteractionRequests render in the Nebula window and share its policy.
    val effectiveRootSessionId = if rootSessionId.nonEmpty then rootSessionId else sessionId
    // Route flow agent events with nodeSessionId = this flow node's session id.
    // Injected only when absent: AgentStreamEvent.toJson already stamps
    // nodeSessionId for subagent events (protocol.scala withNodeSession), and
    // an unconditional merge would clobber the nodeSessionId of a Delegate
    // sub-agent spawned inside this flow agent (which must keep its
    // "delegate-*" prefix so the frontend routes it to the delegate popup).
    val routedWsSend: Json => IO[Unit] = (json: Json) =>
      val withNode =
        if json.hcursor.downField("nodeSessionId").as[String].isRight then json
        else json.deepMerge(Json.obj("nodeSessionId" -> sessionId.asJson))
      rawWsSend(withNode)
    for
      readTracker <- ReadTracker.create
      fileHistory <- FileHistory.create()
      resultDeferred <- Deferred[IO, Either[FailOutcome, List[Message]]]
      // Bridge actor: receives AgentEvent, completes the Deferred, self-stops.
      // The deferred MUST be completed synchronously inside the receive action:
      // forkTurn here races with returning Behaviors.stopped — the actor loop
      // exits and its guarantee cancels the current turn before the forked
      // fiber runs, leaving the deferred forever incomplete and the executor
      // hanging (the intermittent "flow freeze" this executor was blamed for).
      // The receive IO runs to completion before the loop observes
      // Behaviors.stopped, so a direct complete has no such window.
      bridgeRef <- actorSystem.spawn(
        Behaviors.receive[AgentEvent] { (_, event) =>
          event match
            case AgentEvent.Completed(_, messages) =>
              resultDeferred.complete(Right(messages)).void.as(Behaviors.stopped)
            case AgentEvent.Failed(_, err) =>
              // P2: surface the retryable flag (single source
              // AgentActor.llmFailureRetryable) so the executor's Restart
              // path can pick checkpoint recovery over a fresh re-run.
              resultDeferred
                .complete(Left(FailOutcome(err.message, err.retryable.getOrElse(false))))
                .void
                .as(Behaviors.stopped)
            case AgentEvent.Cancelled(_, reason) =>
              resultDeferred.complete(Left(FailOutcome(s"cancelled: $reason", retryable = false))).void.as(Behaviors.stopped)
        },
        s"bridge-${nodeId.take(10)}-${sessionId.take(8)}"
      )
      ref <- actorSystem.spawn(
        AgentActor(
          agentDef = agentDef,
          resources = resources,
          wsSend = routedWsSend,
          depth = 1,
          parentRef = parentAgentRef,
          sessionId = Some(sessionId),
          sessionName = Some(s"$flowName/$nodeId"),
          initialMessages = initialMessages,
          readTracker = Some(readTracker),
          fileHistory = Some(fileHistory),
          contextWindow = resources.contextWindow,
          expectsMail = false,
          rootSessionId = effectiveRootSessionId,
          // #406: one-shot flow nodes are leaves — FlowExecute/FlowTrigger/
          // SubTask/Delegate stripped by buildAllowedToolSet (isFlowNode rule).
          isFlowNode = true
        ),
        s"dagnode-${nodeId.take(10)}-${sessionId.take(8)}"
      )
      // P1: register flow node agents in the unified AgentRegistry — this was
      // previously missing entirely, so permission answers routed to a ghost
      // root agent and were silently dropped (D2 for dag-* sessions).
      _ <- resources.agentRegistry.update(
        _ + (
          sessionId -> AgentRecord(sessionId, ref, AgentKind.Flow, effectiveRootSessionId, parentAgentRef)
        )
      )
      // Send input with the bridge actor as replyTo
      _ <- (ref ! AgentCommand.UserInput(
        text = inputText,
        replyTo = Some(bridgeRef)
      )).void
      // Wait for completion — no wall-clock timeout (see method doc). The only
      // competing events are the user's cancel signal and the internal
      // fail-fast signal (a sibling parallel branch failed under onFail=abort).
      cancelSig <- nebflow.core.flow.RunningFlowRegistry.cancelSignal(instanceId)
      abortSig <- nebflow.core.flow.RunningFlowRegistry.abortSignal(instanceId)
      raceResult <- IO.race(resultDeferred.get, IO.race(cancelSig.get, abortSig.get))
      // Pierce: tell the agent to abandon its in-flight turn first
      // (Stop → cancelCurrentTurn aborts the turn fiber), then fall through
      // to the shared cleanup below — stop(ref)'s guarantee cancels whatever
      // remains. A cancelled node never retries: the completed signal makes
      // any Restart retry of this node fail instantly with the same error.
      _ <- raceResult match
        case Right(Left(_)) =>
          logger.info(s"Flow '$flowName' node '$nodeId' cancelled by user — stopping agent")
          (ref ! AgentCommand.Stop("Flow cancelled by user")).void
        case Right(Right(_)) =>
          logger.info(s"Flow '$flowName' node '$nodeId' aborted (sibling branch failed) — stopping agent")
          (ref ! AgentCommand.Stop(AbortSentinel)).void
        case Left(_) => IO.unit
      eventResult = raceResult match
        case Left(r)         => r
        case Right(Left(_))  => Left(FailOutcome("Flow cancelled by user", retryable = false))
        case Right(Right(_)) => Left(FailOutcome(AbortSentinel, retryable = false))
      _ <- resources.agentRegistry.update(_ - sessionId)
      _ <- actorSystem.stop(ref).handleErrorWith(_ => IO.unit)
      _ <- actorSystem.stop(bridgeRef).handleErrorWith(_ => IO.unit)
      // ── Session retention (P1 #deck-v6: flow-run node observability) ────
      // Node dag-* sessions are PERSISTED for every terminal outcome — the
      // flow-run panel resolves a node's sessionId by scanning these files
      // (GET /api/sessions?includeUnindexed=1 → getHistory), so deleting them
      // on success/cancel/abort left every completed node unopenable ("该节点还
      // 没有会话记录"). Only the FlowReport payload (internal verdict data) is
      // removed; the conversation itself stays as the audit trail.
      // Failure + keepSessionOnFailure: same preservation, plus the executor's
      // Restart path resumes from this checkpoint instead of a fresh re-run
      // (the incident lost 20min/86k-token context here).
      reportOpt <- FlowReportStore.get(sessionId)
      sessionCleanup <- eventResult match
        case Right(_) => FlowReportStore.remove(sessionId)
        case Left(fo) if fo.retryable && keepSessionOnFailure =>
          logger.info(
            s"Flow '$flowName' node '$nodeId' failed retryable — preserving session $sessionId for checkpoint restart"
          )
        case Left(_) => FlowReportStore.remove(sessionId)
      nodeResult <- eventResult match
        case Right(messages) =>
          val textOutput = extractLastAssistantOutput(messages)
          reportOpt match
            case Some(data) =>
              IO.pure(
                NodeResult(
                  nodeId = nodeId,
                  output = if data.output.nonEmpty then data.output else textOutput,
                  success = true,
                  verdict = Some(data.verdict),
                  slots = data.slots.toMap
                )
              )
            case None =>
              // Backward compat: agent didn't call FlowReport, use text output
              IO.pure(
                NodeResult(
                  nodeId = nodeId,
                  output = textOutput,
                  success = true
                )
              )
          end match
        case Left(fo) =>
          IO.pure(
            NodeResult(
              nodeId = nodeId,
              output = "",
              success = false,
              error = Some(s"Agent '$agentName' failed: ${fo.message}"),
              retryable = fo.retryable
            )
          )
    yield nodeResult

    end for

  end executeAgent

  /** Extract the text content of the last assistant message. */
  private def extractLastAssistantOutput(messages: List[Message]): String =
    messages.reverse.find(_.role == MessageRole.Assistant) match
      case Some(msg) =>
        msg.content match
          case Left(text) => text
          case Right(blocks) =>
            blocks
              .collect { case nebflow.shared.ContentBlock.Text(t) =>
                t
              }
              .mkString("\n")
      case None => ""

  /**
   * Extract a field value from node output for switch routing.
   *  switchExpr format: "$reviewer.verdict"
   *  -> look up reviewer's output, try to parse as JSON and extract "verdict" field
   *  -> fallback: text regex `(?i)\b(?:verdict|status)\s*[:：]\s*(\w+)` (covers
   *     plain-text "VERDICT: pass" output, e.g. entity-creator)
   *  -> fallback: case-insensitive substring containment against case keys (warn)
   *  -> "unknown" (the route layer then applies default / explicit error)
   *
   *  FlowReport verdict (when the agent called the tool) is the stage-1 source,
   *  already consumed by route() before this pipeline runs.
   */
  private def extractSwitchValue(
    switchExpr: String,
    currentNodeOutput: String,
    outputs: Map[String, String],
    cases: Map[String, NodeRoute]
  ): String =
    val pattern = "\\$([a-zA-Z0-9_-]+)\\.([a-zA-Z0-9_-]+)".r
    switchExpr match
      case pattern(nodeId, field) =>
        val output = outputs.getOrElse(nodeId, currentNodeOutput)
        // ① JSON field extraction
        val fromJson = io.circe.parser
          .parse(output)
          .toOption
          .flatMap(_.hcursor.downField(field).as[String].toOption)
          .map(_.trim)
          .filter(_.nonEmpty)
        fromJson.getOrElse {
          // ② Text regex: "VERDICT: pass" / "status: ok"
          val regex = "(?i)\\b(?:verdict|status)\\s*[:：]\\s*(\\w+)".r
          val fromRegex = regex.findFirstMatchIn(output).map(_.group(1))
          fromRegex.getOrElse {
            // ③ Case-insensitive substring containment (legacy fallback, warns)
            cases.keys.find(k => output.toLowerCase.contains(k.toLowerCase)) match
              case Some(hit) =>
                logger.warnSync(
                  s"Switch '$switchExpr': matched case '$hit' by substring containment (legacy fallback — prefer JSON/FlowReport verdicts)"
                )
                hit
              case None => "unknown"
          }
        }
      case _ => "unknown"
    end match
  end extractSwitchValue

end FlowDagExecutor
