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
  flows: List[String] = Nil // flow names this agent can trigger via FlowTrigger (drives tool injection)
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

  /** Partial-failure policy of a parallel fan-out. */
  sealed trait OnFailMode
  object OnFailMode:
    /** One branch fails → pierce sibling agents (fail-fast), flow fails. Default. */
    case object Abort extends OnFailMode
    /** Failed branch yields a placeholder result; the barrier still releases. */
    case object Collect extends OnFailMode

    given Decoder[OnFailMode] = Decoder.decodeString.emap {
      case "abort"   => Right(Abort)
      case "collect" => Right(Collect)
      case other     => Left(s"Unknown onFail: '$other' (expected \"abort\" or \"collect\")")
    }
    given Encoder[OnFailMode] = Encoder.instance {
      case Abort   => Json.fromString("abort")
      case Collect => Json.fromString("collect")
    }
  end OnFailMode

  /** Conditional branch: match switch expression against cases. */
  case class Switch(
    switchExpr: String,
    cases: Map[String, NodeRoute],
    default: Option[NodeRoute] = None, // conservative route when no case matches (e.g. $return)
    // R8-P1: legacy guess fallback (JSON field → regex → substring) only runs
    // when the flow has strictVerdict=true AND this switch opts in via
    // "lenient": true. strictVerdict=false flows keep the legacy pipeline
    // regardless (backward compat).
    lenient: Boolean = false
  ) extends NodeRoute

  /**
   * Parallel fan-out: every fan target starts concurrently (branch fibers
   * walking the existing runNode recursion). Multi-in-edge nodes downstream
   * are counting barriers — each upstream arrival decrements; the last one
   * activates the join exactly once. Fan targets converge at a join before
   * $return (enforced at load time).
   */
  case class Parallel(
    fan: List[String],
    onFail: OnFailMode = OnFailMode.Abort
  ) extends NodeRoute

  /** Terminate the flow, return result. */
  case object Return extends NodeRoute

  /**
   * Parse NodeRoute from JSON. Supports three formats:
   *  - string: "reviewer" -> Goto("reviewer"), "$return" -> Return
   *  - switch object: { "switch": "...", "cases": { ... } } -> Switch
   *    (case values recursively accept all three forms — a case may fan out)
   *  - parallel object: { "parallel": ["r1","r2"], "onFail": "collect" } -> Parallel
   */
  given Decoder[NodeRoute] = Decoder.instance { c =>
    c.as[String] match
      case Right(s) =>
        if s == "$return" then Right(Return)
        else Right(Goto(s))
      case Left(_) =>
        c.downField("parallel").as[Option[List[String]]] match
          case Right(Some(fan)) =>
            for onFail <- c.downField("onFail").as[Option[OnFailMode]]
            yield Parallel(fan, onFail.getOrElse(OnFailMode.Abort))
          case _ =>
            for
              switchExpr <- c.downField("switch").as[String]
              casesRaw <- c.downField("cases").as[Map[String, Json]]
              cases <- casesRaw.toList.traverse { (k, v) => v.as[NodeRoute].map(k -> _) }
              default <- c.downField("default").as[Option[NodeRoute]]
              lenient <- c.downField("lenient").as[Option[Boolean]]
            yield Switch(switchExpr, cases.toMap, default, lenient.getOrElse(false))
  }

  given Encoder[NodeRoute] = Encoder.instance {
    case Goto(id) => Json.fromString(id)
    case Return => Json.fromString("$return")
    case Parallel(fan, onFail) =>
      Json.obj(
        "parallel" -> fan.asJson,
        "onFail" -> (if onFail == OnFailMode.Collect then Json.fromString("collect") else Json.Null)
      )
    case Switch(expr, cases, default, lenient) =>
      Json.obj(
        "switch" -> Json.fromString(expr),
        "cases" -> cases.asJson,
        "default" -> default.asJson,
        "lenient" -> (if lenient then Json.fromBoolean(true) else Json.Null)
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
  maxRetries: Int = 0, // max retry count
  // R8-P1: structured output contract — slot name → "string" | "array".
  // Flow agents report values via FlowReport's `slots` parameter; downstream
  // nodes reference them with $<nodeId>.slots.<field>.
  outputs: Map[String, String] = Map.empty
)

object FlowNode:

  given Decoder[FlowNode] = Decoder.instance { c =>
    for
      agent <- c.downField("agent").as[String]
      input <- c.downField("input").as[String]
      onComplete <- c.downField("onComplete").as[NodeRoute]
      onError <- c.downField("onError").as[Option[OnError]]
      maxRetries <- c.downField("maxRetries").as[Option[Int]]
      outputs <- c.downField("outputs").as[Option[Map[String, String]]]
      _ <-
        // Reject unknown slot types at parse time — a typo'd "strng" would
        // otherwise silently pass every runtime check.
        outputs match
          case Some(m) =>
            m.values.filterNot(t => t == "string" || t == "array") match
              case Nil => Right(())
              case bad =>
                Left(DecodingFailure(s"outputs slot types must be \"string\" or \"array\", got: ${bad.mkString(", ")}", c.history))
          case None => Right(())
    yield FlowNode(agent, input, onComplete, onError, maxRetries.getOrElse(0), outputs.getOrElse(Map.empty))
  }

  given Encoder[FlowNode] = Encoder.instance { n =>
    Json.obj(
      "agent" -> n.agent.asJson,
      "input" -> n.input.asJson,
      "onComplete" -> n.onComplete.asJson,
      "onError" -> n.onError.asJson,
      "maxRetries" -> n.maxRetries.asJson,
      "outputs" -> (if n.outputs.isEmpty then Json.Null else n.outputs.asJson)
    )
  }

end FlowNode

/** Flow DAG definition. Corresponds to ~/.nebflow/flows/<name>.json */
case class FlowDagDef(
  name: String,
  description: String,
  nodes: Map[String, FlowNode],
  entry: String, // entry node ID
  maxLoop: Int = 10, // loop protection (no timeout)
  // R8-P1: require structured FlowReport verdicts on switch nodes. Default
  // false (transition period — legacy flows keep the guess pipeline until
  // migrated; flipped to true by explicit "strictVerdict": true).
  strictVerdict: Boolean = false,
  // R8-P2: parallel fan-out cap (cost guardrail now that timeouts are gone).
  maxFanout: Int = 4
)

object FlowDagDef:

  given Decoder[FlowDagDef] = Decoder.instance { c =>
    for
      name <- c.downField("name").as[String]
      description <- c.downField("description").as[String]
      nodes <- c.downField("nodes").as[Map[String, FlowNode]]
      entry <- c.downField("entry").as[String]
      maxLoop <- c.downField("maxLoop").as[Option[Int]]
      strictVerdict <- c.downField("strictVerdict").as[Option[Boolean]]
      maxFanout <- c.downField("maxFanout").as[Option[Int]]
    yield FlowDagDef(
      name, description, nodes, entry,
      maxLoop.getOrElse(10), strictVerdict.getOrElse(false), maxFanout.getOrElse(4)
    )
  }

  given Encoder[FlowDagDef] = Encoder.instance { f =>
    Json.obj(
      "name" -> f.name.asJson,
      "description" -> f.description.asJson,
      "nodes" -> f.nodes.asJson,
      "entry" -> f.entry.asJson,
      "maxLoop" -> f.maxLoop.asJson,
      "strictVerdict" -> (if f.strictVerdict then Json.fromBoolean(true) else Json.Null),
      "maxFanout" -> (if f.maxFanout != 4 then f.maxFanout.asJson else Json.Null)
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
  verdict: Option[String] = None,
  // R8-P1: structured slot values reported via FlowReport (per the node's
  // `outputs` declaration). Referenced downstream via $<nodeId>.slots.<field>.
  slots: Map[String, Json] = Map.empty
)

/** Runtime context for a flow execution. */
case class FlowExecContext(
  flowName: String,
  taskInput: String,
  nodeOutputs: Map[String, String] = Map.empty, // nodeId -> output
  nodeSlots: Map[String, Map[String, Json]] = Map.empty, // nodeId -> slot name -> value
  loopCounts: Map[String, Int] = Map.empty, // edge -> traversal count
  totalLoops: Int = 0
)
