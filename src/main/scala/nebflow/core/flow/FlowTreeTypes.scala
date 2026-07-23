package nebflow.core.flow

import io.circe.*
import io.circe.syntax.*

import scala.concurrent.duration.*

// ============================================================
// Flow Node — unified node type for BranchFlow graphs
// ============================================================
// Replaces PipelineStep + VerifyStep + LoopConfig.
// A node is either:
//   - An agent task (agent + prompt)
//   - A nested flow reference (flow + optional flowInput)
//
// Special flags:
//   - verdict: true → output parsed for "VERDICT: PASS/FAIL"
//   - condition: "nodeId.fail" → only runs if nodeId's verdict was FAIL
//   - retry: { target, maxIterations } → after this node, re-trigger target

/** Retry target — creates a cycle back to an earlier node. */
case class RetryTarget(
  target: String,
  maxIterations: Int = 3
)

object RetryTarget:
  given Encoder[RetryTarget] = Encoder.instance { r =>
    Json.obj("target" -> r.target.asJson, "maxIterations" -> r.maxIterations.asJson)
  }
  given Decoder[RetryTarget] = Decoder.instance { c =>
    for
      target <- c.downField("target").as[String]
      maxIter <- c.downField("maxIterations").as[Option[Int]]
    yield RetryTarget(target, maxIter.getOrElse(3))
  }

/** A node in a BranchFlow graph. Either an agent task or a nested flow reference. */
case class FlowNode(
  id: String,
  agent: Option[String] = None,
  prompt: Option[String] = None,
  flow: Option[String] = None,
  flowInput: Option[String] = None,
  dependsOn: Set[String] = Set.empty,
  verdict: Boolean = false,
  condition: Option[String] = None,
  retry: Option[RetryTarget] = None,
  timeoutSeconds: Int = 1800
):
  // NOTE: structural validity (agent+prompt XOR flow) is validated by
  // FlowDefLoader.validate, NOT by a `require` here — a require would throw at
  // decode time and abort the entire nodes list, turning one bad node into
  // "flow has zero nodes" with no diagnostics.
  def isValid: Boolean =
    (agent.isDefined && prompt.isDefined && flow.isEmpty) ||
      (agent.isEmpty && prompt.isEmpty && flow.isDefined)

  def isNested: Boolean = flow.isDefined
  def isVerdict: Boolean = verdict
  def timeout: FiniteDuration = timeoutSeconds.seconds

end FlowNode

object FlowNode:

  given Encoder[FlowNode] = Encoder.instance { s =>
    val base = Json.obj(
      "id" -> s.id.asJson,
      "dependsOn" -> s.dependsOn.toList.asJson,
      "timeoutSeconds" -> s.timeoutSeconds.asJson
    )
    val agentPart = s.agent.map(a => Json.obj("agent" -> a.asJson)).getOrElse(Json.obj())
    val promptPart = s.prompt.map(p => Json.obj("prompt" -> p.asJson)).getOrElse(Json.obj())
    val flowPart = s.flow.map(f => Json.obj("flow" -> f.asJson)).getOrElse(Json.obj())
    val flowInputPart = s.flowInput.map(fi => Json.obj("flowInput" -> fi.asJson)).getOrElse(Json.obj())
    val verdictPart = if s.verdict then Json.obj("verdict" -> true.asJson) else Json.obj()
    val conditionPart = s.condition.map(c => Json.obj("condition" -> c.asJson)).getOrElse(Json.obj())
    val retryPart = s.retry.map(r => Json.obj("retry" -> r.asJson)).getOrElse(Json.obj())
    base.deepMerge(agentPart).deepMerge(promptPart).deepMerge(flowPart).deepMerge(flowInputPart)
      .deepMerge(verdictPart).deepMerge(conditionPart).deepMerge(retryPart)
  }

  given Decoder[FlowNode] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      agent <- c.downField("agent").as[Option[String]]
      prompt <- c.downField("prompt").as[Option[String]]
      flow <- c.downField("flow").as[Option[String]]
      flowInput <- c.downField("flowInput").as[Option[String]]
      deps <- c.downField("dependsOn").as[Option[List[String]]]
      verdict <- c.downField("verdict").as[Option[Boolean]]
      condition <- c.downField("condition").as[Option[String]]
      retry <- c.downField("retry").as[Option[RetryTarget]]
      timeoutS <- c.downField("timeoutSeconds").as[Option[Int]]
    yield
      val validated = FlowNode(
        id,
        agent,
        prompt,
        flow,
        flowInput,
        deps.getOrElse(Nil).toSet,
        verdict.getOrElse(false),
        condition,
        retry,
        timeoutS.getOrElse(1800)
      )
      validated
  }

end FlowNode

// ============================================================
// Flow Definition (top-level YAML)
// ============================================================

/**
 * A flow definition loaded from YAML.
 *
 * @param manager agent name that orchestrates this flow (e.g., "Nebula").
 *                The manager receives the task, oversees execution, and summarizes results.
 *                Must not be used as a step agent (use specialized agents like Explorer/Coder).
 * @param nodes the execution graph — each node is an agent task or nested flow.
 */
case class FlowDef(
  name: String,
  manager: Option[String] = None,
  description: String = "",
  nodes: List[FlowNode] = Nil,
  maxDepth: Int = 5
)

object FlowDef:

  given Encoder[FlowDef] = Encoder.instance { f =>
    Json.obj(
      "name" -> f.name.asJson,
      "manager" -> f.manager.asJson,
      "description" -> f.description.asJson,
      "nodes" -> f.nodes.asJson,
      "maxDepth" -> f.maxDepth.asJson
    )
  }

  // FlowDef is parsed from YAML by FlowDefLoader, not persisted directly.

// ============================================================
// Branch Phase & State
// ============================================================

enum BranchPhase:
  case Starting, Running, Completed, Stopped, Crashed

object BranchPhase:
  given Encoder[BranchPhase] = Encoder.encodeString.contramap(_.toString)
  given Decoder[BranchPhase] = Decoder.decodeString.emap { s =>
    BranchPhase.values.find(_.toString == s).toRight(s"Unknown phase: $s")
  }

/** Persistable branch state. */
case class BranchState(
  name: String,
  address: String,
  flowName: String,
  phase: BranchPhase,
  parentBranch: Option[String] = None,
  stepStatus: Map[String, String] = Map.empty,
  results: Map[String, String] = Map.empty,
  failedReasons: Map[String, String] = Map.empty,
  retryLeft: Map[String, Int] = Map.empty,
  verdicts: Map[String, Boolean] = Map.empty,
  iteration: Int = 0,
  children: Map[String, String] = Map.empty
)

object BranchState:

  given Encoder[BranchState] = Encoder.instance { s =>
    Json.obj(
      "name" -> s.name.asJson,
      "address" -> s.address.asJson,
      "flowName" -> s.flowName.asJson,
      "phase" -> s.phase.asJson,
      "parentBranch" -> s.parentBranch.asJson,
      "stepStatus" -> s.stepStatus.asJson,
      "results" -> s.results.asJson,
      "failedReasons" -> s.failedReasons.asJson,
      "retryLeft" -> s.retryLeft.asJson,
      "verdicts" -> s.verdicts.asJson,
      "iteration" -> s.iteration.asJson,
      "children" -> s.children.asJson
    )
  }

  given Decoder[BranchState] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      address <- c.downField("address").as[String]
      flowName <- c.downField("flowName").as[String]
      phase <- c.downField("phase").as[BranchPhase]
      parentBranch <- c.downField("parentBranch").as[Option[String]]
      stepStatus <- c.downField("stepStatus").as[Option[Map[String, String]]]
      results <- c.downField("results").as[Option[Map[String, String]]]
      failedReasons <- c.downField("failedReasons").as[Option[Map[String, String]]]
      retryLeft <- c.downField("retryLeft").as[Option[Map[String, Int]]]
      verdicts <- c.downField("verdicts").as[Option[Map[String, Boolean]]]
      iteration <- c.downField("iteration").as[Option[Int]]
      children <- c.downField("children").as[Option[Map[String, String]]]
    yield BranchState(
      name,
      address,
      flowName,
      phase,
      parentBranch,
      stepStatus.getOrElse(Map.empty),
      results.getOrElse(Map.empty),
      failedReasons.getOrElse(Map.empty),
      retryLeft.getOrElse(Map.empty),
      verdicts.getOrElse(Map.empty),
      iteration.getOrElse(0),
      children.getOrElse(Map.empty)
    )
  }
end BranchState

// ============================================================
// Flow Tree Snapshot
// ============================================================

case class FlowTreeSnapshot(
  sessionId: String,
  branches: Map[String, BranchState]
)

object FlowTreeSnapshot:

  given Encoder[FlowTreeSnapshot] = Encoder.instance { s =>
    Json.obj(
      "sessionId" -> s.sessionId.asJson,
      "branches" -> s.branches.asJson
    )
  }

  given Decoder[FlowTreeSnapshot] = Decoder.instance { c =>
    for
      sessionId <- c.downField("sessionId").as[String]
      branches <- c.downField("branches").as[Map[String, BranchState]]
    yield FlowTreeSnapshot(sessionId, branches)
  }
end FlowTreeSnapshot

// ============================================================
// Mount Result
// ============================================================

sealed trait MountResult

object MountResult:
  case class Mounted(name: String, address: String) extends MountResult
  case class Unmounted(name: String) extends MountResult
  case class Error(message: String) extends MountResult

// ============================================================
// Tree Commands (messages for TreeFlow)
// ============================================================

sealed trait TreeCommand

object TreeCommand:

  case class MountBranch(
    defn: FlowDef,
    instanceName: Option[String],
    replyTo: Option[nebflow.actor.ActorRef[MountResult]]
  ) extends TreeCommand

  case class UnmountBranch(name: String) extends TreeCommand

  case class TriggerPipeline(
    name: String,
    input: String = "",
    replyTo: Option[nebflow.actor.ActorRef[PipelineActor.PipelineEvent]] = None
  ) extends TreeCommand

  case class ReloadDefinition(flowName: String) extends TreeCommand

  case class CancelPipeline(name: String) extends TreeCommand

  case object Shutdown extends TreeCommand

end TreeCommand
