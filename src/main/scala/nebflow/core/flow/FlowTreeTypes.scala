package nebflow.core.flow

import io.circe.*
import io.circe.syntax.*

import scala.concurrent.duration.*

// ============================================================
// Restart Policy (for Source scripts, persistent daemons)
// ============================================================

enum RestartPolicy:
  case Permanent, Transient, Temporary

object RestartPolicy:
  given Encoder[RestartPolicy] = Encoder.encodeString.contramap(_.toString.toLowerCase)

  given Decoder[RestartPolicy] = Decoder.decodeString.emap {
    case "permanent" => Right(Permanent)
    case "transient" => Right(Transient)
    case "temporary" => Right(Temporary)
    case other => Left(s"Unknown restart policy: $other")
  }

// ============================================================
// Pipeline Step (replaces FlowStep — supports nesting)
// ============================================================

/** A step in a pipeline. Either an atomic agent task or a nested flow reference. */
case class PipelineStep(
  id: String,
  agent: Option[String] = None, // atomic: agent name
  prompt: Option[String] = None, // atomic: task prompt
  flow: Option[String] = None, // nested: flow definition name
  dependsOn: Set[String] = Set.empty,
  retry: Int = 2,
  timeoutSeconds: Int = 1800
):

  require(
    (agent.isDefined && prompt.isDefined && flow.isEmpty) ||
      (agent.isEmpty && prompt.isEmpty && flow.isDefined),
    s"Step '$id' must have either (agent + prompt) or flow, not both/neither"
  )

  def isNested: Boolean = flow.isDefined
  def timeout: FiniteDuration = timeoutSeconds.seconds

end PipelineStep

object PipelineStep:

  given Encoder[PipelineStep] = Encoder.instance { s =>
    val base = Json.obj(
      "id" -> s.id.asJson,
      "dependsOn" -> s.dependsOn.toList.asJson,
      "retry" -> s.retry.asJson,
      "timeoutSeconds" -> s.timeoutSeconds.asJson
    )
    val agentPart = s.agent.map(a => Json.obj("agent" -> a.asJson)).getOrElse(Json.obj())
    val promptPart = s.prompt.map(p => Json.obj("prompt" -> p.asJson)).getOrElse(Json.obj())
    val flowPart = s.flow.map(f => Json.obj("flow" -> f.asJson)).getOrElse(Json.obj())
    base.deepMerge(agentPart).deepMerge(promptPart).deepMerge(flowPart)
  }

  given Decoder[PipelineStep] = Decoder.instance { c =>
    for
      id <- c.downField("id").as[String]
      agent <- c.downField("agent").as[Option[String]]
      prompt <- c.downField("prompt").as[Option[String]]
      flow <- c.downField("flow").as[Option[String]]
      deps <- c.downField("dependsOn").as[Option[List[String]]]
      retry <- c.downField("retry").as[Option[Int]]
      timeoutS <- c.downField("timeoutSeconds").as[Option[Int]]
    yield
      val validated = PipelineStep(
        id,
        agent,
        prompt,
        flow,
        deps.getOrElse(Nil).toSet,
        retry.getOrElse(2),
        timeoutS.getOrElse(1800)
      )
      validated
  }
end PipelineStep

// ============================================================
// Verify Step (reused from existing — standalone, no FlowStep dependency)
// ============================================================

case class VerifyStep(
  agent: String = "Explorer",
  prompt: String,
  timeoutSeconds: Int = 1800
):
  def timeout: FiniteDuration = timeoutSeconds.seconds

object VerifyStep:

  given Encoder[VerifyStep] = Encoder.instance { v =>
    Json.obj(
      "agent" -> v.agent.asJson,
      "prompt" -> v.prompt.asJson,
      "timeoutSeconds" -> v.timeoutSeconds.asJson
    )
  }

  given Decoder[VerifyStep] = Decoder.instance { c =>
    for
      agent <- c.downField("agent").as[Option[String]]
      prompt <- c.downField("prompt").as[String]
      timeoutS <- c.downField("timeoutSeconds").as[Option[Int]]
    yield VerifyStep(agent.getOrElse("Explorer"), prompt, timeoutS.getOrElse(1800))
  }
end VerifyStep

// ============================================================
// Loop Config (replaces LoopDef — uses PipelineStep for fix)
// ============================================================

case class LoopConfig(
  fix: PipelineStep,
  maxIterations: Int = 3
)

object LoopConfig:

  given Encoder[LoopConfig] = Encoder.instance { l =>
    Json.obj(
      "fix" -> l.fix.asJson,
      "maxIterations" -> l.maxIterations.asJson
    )
  }

  given Decoder[LoopConfig] = Decoder.instance { c =>
    for
      fix <- c.downField("fix").as[PipelineStep]
      maxIter <- c.downField("maxIterations").as[Option[Int]]
    yield LoopConfig(fix, maxIter.getOrElse(3))
  }

// ============================================================
// Reactor Action
// ============================================================

sealed trait ReactorAction

object ReactorAction:
  case class RunAgent(agent: String, prompt: String) extends ReactorAction
  case class MountFlow(flowName: String) extends ReactorAction

  given Encoder[ReactorAction] = Encoder.instance {
    case RunAgent(agent, prompt) =>
      Json.obj(
        "type" -> "runAgent".asJson,
        "agent" -> agent.asJson,
        "prompt" -> prompt.asJson
      )
    case MountFlow(flowName) =>
      Json.obj(
        "type" -> "mountFlow".asJson,
        "flowName" -> flowName.asJson
      )
  }

  given Decoder[ReactorAction] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "runAgent" =>
        for
          agent <- c.downField("agent").as[String]
          prompt <- c.downField("prompt").as[String]
        yield RunAgent(agent, prompt)
      case "mountFlow" =>
        c.downField("flowName").as[String].map(MountFlow(_))
      case other => Left(io.circe.DecodingFailure(s"Unknown reactor action type: $other", c.history))
    }
  }
end ReactorAction

// ============================================================
// Branch Type (discriminated union for the four branch kinds)
// ============================================================

sealed trait BranchType:
  def typeName: String

object BranchType:

  case class Daemon(agent: String, prompt: String, persistent: Boolean = false) extends BranchType:
    def typeName = "daemon"

  case class Pipeline(
    steps: List[PipelineStep],
    verify: VerifyStep,
    loop: Option[LoopConfig] = None,
    maxConcurrency: Int = 5
  ) extends BranchType:
    def typeName = "pipeline"

  case class Reactor(
    subscribe: Set[String],
    filter: Map[String, String] = Map.empty,
    action: ReactorAction
  ) extends BranchType:
    def typeName = "reactor"

  case class Source(command: String, restart: RestartPolicy = RestartPolicy.Permanent) extends BranchType:
    def typeName = "source"

  given Encoder[BranchType] = Encoder.instance {
    case d: Daemon =>
      Json.obj(
        "type" -> "daemon".asJson,
        "agent" -> d.agent.asJson,
        "prompt" -> d.prompt.asJson,
        "persistent" -> d.persistent.asJson
      )
    case p: Pipeline =>
      Json.obj(
        "type" -> "pipeline".asJson,
        "steps" -> p.steps.asJson,
        "verify" -> p.verify.asJson,
        "loop" -> p.loop.asJson,
        "maxConcurrency" -> p.maxConcurrency.asJson
      )
    case r: Reactor =>
      Json.obj(
        "type" -> "reactor".asJson,
        "subscribe" -> r.subscribe.toList.asJson,
        "filter" -> r.filter.asJson,
        "action" -> r.action.asJson
      )
    case s: Source =>
      Json.obj(
        "type" -> "source".asJson,
        "command" -> s.command.asJson,
        "restart" -> s.restart.asJson
      )
  }

  given Decoder[BranchType] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "daemon" =>
        for
          agent <- c.downField("agent").as[String]
          prompt <- c.downField("prompt").as[String]
          persistent <- c.downField("persistent").as[Option[Boolean]]
        yield Daemon(agent, prompt, persistent.getOrElse(false))
      case "pipeline" =>
        for
          steps <- c.downField("steps").as[List[PipelineStep]]
          verify <- c.downField("verify").as[VerifyStep]
          loop <- c.downField("loop").as[Option[LoopConfig]]
          maxConc <- c.downField("maxConcurrency").as[Option[Int]]
        yield Pipeline(steps, verify, loop, maxConc.getOrElse(5))
      case "reactor" =>
        for
          subscribe <- c.downField("subscribe").as[List[String]]
          filter <- c.downField("filter").as[Option[Map[String, String]]]
          action <- c.downField("action").as[ReactorAction]
        yield Reactor(subscribe.toSet, filter.getOrElse(Map.empty), action)
      case "source" =>
        for
          command <- c.downField("command").as[String]
          restart <- c.downField("restart").as[Option[RestartPolicy]]
        yield Source(command, restart.getOrElse(RestartPolicy.Permanent))
      case other => Left(io.circe.DecodingFailure(s"Unknown branch type: $other", c.history))
    }
  }
end BranchType

// ============================================================
// Flow Definition (top-level YAML definition)
// ============================================================

case class FlowDef(
  name: String,
  branchType: BranchType,
  maxDepth: Int = 5
)

object FlowDef:

  given Encoder[FlowDef] = Encoder.instance { f =>
    Json
      .obj(
        "name" -> f.name.asJson,
        "maxDepth" -> f.maxDepth.asJson
      )
      .deepMerge(f.branchType.asJson.asObject.fold(Json.obj())(jo => Json.obj("branch" -> jo.toJson)))
  }

  // FlowDef is parsed from YAML, not persisted directly.
  // The loader handles the YAML → FlowDef mapping.

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

/** Persistable branch state (excludes runtime ActorRef / Process refs). */
case class BranchState(
  name: String, // unique instance name
  address: String, // ActorRef path for Mail routing
  branchType: BranchType,
  phase: BranchPhase,
  parentBranch: Option[String] = None,
  // Pipeline runtime state
  stepStatus: Map[String, String] = Map.empty, // stepId → "Pending"/"Running"/"Done"/"Failed"
  results: Map[String, String] = Map.empty, // stepId → output text
  failedReasons: Map[String, String] = Map.empty,
  retryLeft: Map[String, Int] = Map.empty,
  verifyResult: Option[String] = None,
  iteration: Int = 0,
  // Tree structure
  children: Map[String, String] = Map.empty // childName → childBranchName
)

object BranchState:

  given Encoder[BranchState] = Encoder.instance { s =>
    Json.obj(
      "name" -> s.name.asJson,
      "address" -> s.address.asJson,
      "branchType" -> s.branchType.asJson,
      "phase" -> s.phase.asJson,
      "parentBranch" -> s.parentBranch.asJson,
      "stepStatus" -> s.stepStatus.asJson,
      "results" -> s.results.asJson,
      "failedReasons" -> s.failedReasons.asJson,
      "retryLeft" -> s.retryLeft.asJson,
      "verifyResult" -> s.verifyResult.asJson,
      "iteration" -> s.iteration.asJson,
      "children" -> s.children.asJson
    )
  }

  given Decoder[BranchState] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      address <- c.downField("address").as[String]
      branchType <- c.downField("branchType").as[BranchType]
      phase <- c.downField("phase").as[BranchPhase]
      parentBranch <- c.downField("parentBranch").as[Option[String]]
      stepStatus <- c.downField("stepStatus").as[Option[Map[String, String]]]
      results <- c.downField("results").as[Option[Map[String, String]]]
      failedReasons <- c.downField("failedReasons").as[Option[Map[String, String]]]
      retryLeft <- c.downField("retryLeft").as[Option[Map[String, Int]]]
      verifyResult <- c.downField("verifyResult").as[Option[String]]
      iteration <- c.downField("iteration").as[Option[Int]]
      children <- c.downField("children").as[Option[Map[String, String]]]
    yield BranchState(
      name,
      address,
      branchType,
      phase,
      parentBranch,
      stepStatus.getOrElse(Map.empty),
      results.getOrElse(Map.empty),
      failedReasons.getOrElse(Map.empty),
      retryLeft.getOrElse(Map.empty),
      verifyResult,
      iteration.getOrElse(0),
      children.getOrElse(Map.empty)
    )
  }
end BranchState

// ============================================================
// Flow Tree Snapshot (whole-tree persistence)
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

// ============================================================
// Mount Result (returned by FlowTreeActor to MountFlowTool)
// ============================================================

sealed trait MountResult

object MountResult:
  case class Mounted(name: String, address: String, typeName: String) extends MountResult
  case class Unmounted(name: String) extends MountResult
  case class Retriggered(name: String) extends MountResult
  case class Error(message: String) extends MountResult

// ============================================================
// Tree Commands (messages for FlowTreeActor)
// ============================================================

sealed trait TreeCommand

object TreeCommand:

  // Mount management
  case class MountBranch(
    defn: FlowDef,
    instanceName: Option[String],
    replyTo: Option[nebflow.actor.ActorRef[MountResult]]
  ) extends TreeCommand
  case class UnmountBranch(name: String) extends TreeCommand
  case class RetriggerPipeline(name: String) extends TreeCommand

  // Pipeline internal messages
  case class StepCompleted(branchName: String, stepId: String, output: String) extends TreeCommand
  case class StepFailed(branchName: String, stepId: String, error: String) extends TreeCommand
  case class VerifyCompleted(branchName: String, passed: Boolean, summary: String) extends TreeCommand

  // Daemon mail routing
  case class MailForBranch(address: String, message: String) extends TreeCommand

  // Event bus (Source external events + system internal events)
  case class EventFired(eventType: String, data: JsonObject) extends TreeCommand

  // Source script process exited
  case class SourceExited(branchName: String, exitCode: Int) extends TreeCommand

  // Hot reload
  case class ReloadDefinition(flowName: String) extends TreeCommand

  // Lifecycle
  case object Shutdown extends TreeCommand

end TreeCommand
