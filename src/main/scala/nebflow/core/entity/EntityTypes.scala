package nebflow.core.entity

import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import nebflow.shared.AgentModelConfig

// ============================================================
// Agent (global Agent library entry — corresponds to agent.json)
// ============================================================

/** Runtime representation of agent.json. */
case class AgentEntry(
  name: String,
  description: String,
  useWhen: String,
  tools: List[String] = List("*"),
  voice: Boolean = false,
  systemPrompt: String = "", // loaded from system.md, not in agent.json
  category: String = "standalone", // computed by EntityLoader from path, NOT read from JSON
  mcpServers: List[String] = Nil,
  model: Option[AgentModelConfig] = None,
  preset: Option[String] = None, // references a named preset in model-presets.json
  skills: List[String] = Nil, // skill names this agent can see (frontmatter injection)
  flows: List[String] = Nil // flow names this agent can trigger via Delegate(flow=...)
)

object AgentEntry:

  given Decoder[AgentEntry] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[Option[String]]
      description <- c.downField("description").as[String]
      useWhen <- c.downField("useWhen").as[Option[String]].map(_.getOrElse(""))
      tools <- c.downField("tools").as[Option[List[String]]]
      voice <- c.downField("voice").as[Option[Boolean]]
      mcpServers <- c.downField("mcpServers").as[Option[List[String]]]
      model <- c.downField("model").as[Option[AgentModelConfig]]
      preset <- c.downField("preset").as[Option[String]]
      skills <- c.downField("skills").as[Option[List[String]]]
      flows <- c.downField("flows").as[Option[List[String]]]
    yield AgentEntry(
      name.getOrElse(""),
      description,
      useWhen,
      tools.getOrElse(List("*")),
      voice.getOrElse(false),
      "",
      "standalone", // category computed by EntityLoader from path
      mcpServers.getOrElse(Nil),
      model,
      preset,
      skills.getOrElse(Nil),
      flows.getOrElse(Nil)
    )
  }

  given Encoder[AgentEntry] = Encoder.instance { a =>
    Json.obj(
      "name" -> a.name.asJson,
      "description" -> a.description.asJson,
      "useWhen" -> a.useWhen.asJson,
      "tools" -> a.tools.asJson,
      "voice" -> a.voice.asJson,
      "mcpServers" -> a.mcpServers.asJson,
      "model" -> a.model.asJson,
      "preset" -> a.preset.asJson,
      "skills" -> a.skills.asJson,
      "flows" -> a.flows.asJson
    )
  }
end AgentEntry

// ============================================================
// Team (corresponds to team.json)
// ============================================================

/** Team definition. Corresponds to ~/.nebflow/teams/<name>/team.json */
case class TeamDef(
  name: String,
  description: String,
  lead: String, // Agent name (in global Agent library)
  members: List[String] = Nil // Agent names
)

object TeamDef:

  given Decoder[TeamDef] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      description <- c.downField("description").as[String]
      lead <- c.downField("lead").as[String]
      members <- c.downField("members").as[Option[List[String]]]
    yield TeamDef(name, description, lead, members.getOrElse(Nil))
  }

  given Encoder[TeamDef] = Encoder.instance { t =>
    Json.obj(
      "name" -> t.name.asJson,
      "description" -> t.description.asJson,
      "lead" -> t.lead.asJson,
      "members" -> t.members.asJson
    )
  }
end TeamDef

// ============================================================
// Flow DAG (corresponds to flow.json)
// ============================================================

/** Routing target after a node completes. */
sealed trait NodeRoute

object NodeRoute:
  /** Route to a specific node. */
  case class Goto(nodeId: String) extends NodeRoute

  /** Conditional branch: match switch expression against cases. */
  case class Switch(
    switchExpr: String,
    cases: Map[String, NodeRoute],
    default: Option[NodeRoute] = None // conservative route when no case matches (e.g. $return)
  ) extends NodeRoute

  /** Terminate the flow, return result. */
  case object Return extends NodeRoute

  /**
   * Parse NodeRoute from JSON. Supports two formats:
   *  - string: "reviewer" -> Goto("reviewer"), "$return" -> Return
   *  - object: { "switch": "...", "cases": { ... } } -> Switch
   */
  given Decoder[NodeRoute] = Decoder.instance { c =>
    c.as[String] match
      case Right(s) =>
        if s == "$return" then Right(Return)
        else Right(Goto(s))
      case Left(_) =>
        for
          switchExpr <- c.downField("switch").as[String]
          casesRaw <- c.downField("cases").as[Map[String, Json]]
          cases <- casesRaw.toList.traverse { (k, v) => v.as[NodeRoute].map(k -> _) }
          default <- c.downField("default").as[Option[NodeRoute]]
        yield Switch(switchExpr, cases.toMap, default)
  }

  given Encoder[NodeRoute] = Encoder.instance {
    case Goto(id) => Json.fromString(id)
    case Return => Json.fromString("$return")
    case Switch(expr, cases, default) =>
      Json.obj(
        "switch" -> Json.fromString(expr),
        "cases" -> cases.asJson,
        "default" -> default.asJson
      )
  }
end NodeRoute

/** Error handling strategy. */
sealed trait OnError

object OnError:
  case object Resume extends OnError // log warning, continue routing
  case object Restart extends OnError // re-execute node
  case object Stop extends OnError // abort flow

  given Decoder[OnError] = Decoder.instance { c =>
    c.as[String].flatMap {
      case "resume" => Right(Resume)
      case "restart" => Right(Restart)
      case "stop" => Right(Stop)
      case other => Left(DecodingFailure(s"Unknown onError: $other", c.history))
    }
  }

  given Encoder[OnError] = Encoder.instance {
    case Resume => Json.fromString("resume")
    case Restart => Json.fromString("restart")
    case Stop => Json.fromString("stop")
  }
end OnError

/** A single node in the flow DAG. */
case class FlowNode(
  agent: String, // referenced global Agent name
  input: String, // input template: "$task", "$scanner.output"
  onComplete: NodeRoute, // completion route
  onError: Option[OnError] = None, // failure strategy (default stop)
  maxRetries: Int = 0 // max retry count
)

object FlowNode:

  given Decoder[FlowNode] = Decoder.instance { c =>
    for
      agent <- c.downField("agent").as[String]
      input <- c.downField("input").as[String]
      onComplete <- c.downField("onComplete").as[NodeRoute]
      onError <- c.downField("onError").as[Option[OnError]]
      maxRetries <- c.downField("maxRetries").as[Option[Int]]
    yield FlowNode(agent, input, onComplete, onError, maxRetries.getOrElse(0))
  }

  given Encoder[FlowNode] = Encoder.instance { n =>
    Json.obj(
      "agent" -> n.agent.asJson,
      "input" -> n.input.asJson,
      "onComplete" -> n.onComplete.asJson,
      "onError" -> n.onError.asJson,
      "maxRetries" -> n.maxRetries.asJson
    )
  }

end FlowNode

/** Flow DAG definition. Corresponds to ~/.nebflow/flows/<name>.json */
case class FlowDagDef(
  name: String,
  description: String,
  nodes: Map[String, FlowNode],
  entry: String, // entry node ID
  maxLoop: Int = 10 // loop protection (no timeout)
)

object FlowDagDef:

  given Decoder[FlowDagDef] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      description <- c.downField("description").as[String]
      nodes <- c.downField("nodes").as[Map[String, FlowNode]]
      entry <- c.downField("entry").as[String]
      maxLoop <- c.downField("maxLoop").as[Option[Int]]
    yield FlowDagDef(name, description, nodes, entry, maxLoop.getOrElse(10))
  }

  given Encoder[FlowDagDef] = Encoder.instance { f =>
    Json.obj(
      "name" -> f.name.asJson,
      "description" -> f.description.asJson,
      "nodes" -> f.nodes.asJson,
      "entry" -> f.entry.asJson,
      "maxLoop" -> f.maxLoop.asJson
    )
  }
end FlowDagDef

// ============================================================
// Runtime state (internal to DAG executor)
// ============================================================

/**
 * Execution result of a single node.
 *
 * Contract:
 *  - `success` drives the LIFECYCLE status (NodeStatus via
 *    NodeStatus.toNodeResult) — completed on true, failed on false.
 *  - `verdict` is a ROUTING value for switch nodes, decoupled from lifecycle:
 *    it feeds VerdictFamily.matchCase to select the next edge. When absent,
 *    the executor extracts a value from `output` (JSON field / text regex).
 */
case class NodeResult(
  nodeId: String,
  output: String,
  success: Boolean,
  error: Option[String] = None,
  attemptCount: Int = 1,
  verdict: Option[String] = None
)

/** Runtime context for a flow execution. */
case class FlowExecContext(
  flowName: String,
  taskInput: String,
  nodeOutputs: Map[String, String] = Map.empty, // nodeId -> output
  loopCounts: Map[String, Int] = Map.empty, // edge -> traversal count
  totalLoops: Int = 0
)
