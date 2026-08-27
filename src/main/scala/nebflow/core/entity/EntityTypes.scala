package nebflow.core.entity

import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import nebflow.agent.AgentDef
import nebflow.core.presets.PresetStore
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

  /**
   * Single-source AgentEntry → AgentDef conversion (2026-08-15 FlowTrigger
   * outage fix). Every spawn path (WebSocketRoutes root agents, MailTool
   * activations, FlowTreeActor, FlowDagExecutor nodes, ContextRefresher
   * per-turn refresh, EntityLoader.findAgentByName) must go through this
   * method — hand-copied field maps at spawn sites drifted out of sync with
   * this decoder (six copies, only two carried `flows`/`skills`), which made
   * the LLM schema advertise FlowTrigger while the executor's snapshot
   * AgentDef lacked it ("Tool not available: FlowTrigger").
   *
   * Site-specific extras are applied via `.copy(...)` at the call site
   * (flowContract for flow nodes, avatar/displayName/voiceEnabled carried
   * over from the running actor in ContextRefresher).
   */
  extension (a: AgentEntry)
    def toAgentDef: AgentDef =
      val (resolvedModel, _) = PresetStore().resolve(a.preset, a.model)
      AgentDef(
        name = a.name,
        description = a.description,
        tools = a.tools,
        systemPrompt = a.systemPrompt,
        voiceEnabled = a.voice,
        category = a.category,
        mcpServers = a.mcpServers,
        model = Some(resolvedModel),
        preset = a.preset,
        skills = a.skills,
        flows = a.flows
      )
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

  /**
   * Dynamic parallel fan-out: after the owner node completes, read its
   * `slotField` array slot → N = min(len, maxFanout) → instantiate N runtime
   * nodes from `template` (as `template#1` … `template#N`), run them
   * concurrently. The template node is a normal FlowNode declared in
   * flow.json; only this route references it for instantiation. Its input
   * supports `{{item}}` (the array element) and `{{index}}` (1-based).
   */
  case class ParallelDynamic(
    slotField: String,
    template: String,
    onFail: OnFailMode = OnFailMode.Abort
  ) extends NodeRoute

  /** Terminate the flow, return result. */
  case object Return extends NodeRoute

  /**
   * Parse NodeRoute from JSON. Supports four formats:
   *  - string: "reviewer" -> Goto("reviewer"), "$return" -> Return
   *  - switch object: { "switch": "...", "cases": { ... } } -> Switch
   *    (case values recursively accept all three forms — a case may fan out)
   *  - parallel object with array value: { "parallel": ["r1","r2"], "onFail": "collect" } -> Parallel
   *  - parallel object with object value: { "parallel": { "slots": "topics", "template": "researcher" } } -> ParallelDynamic
   */
  given Decoder[NodeRoute] = Decoder.instance { c =>
    c.as[String] match
      case Right(s) =>
        if s == "$return" then Right(Return)
        else Right(Goto(s))
      case Left(_) =>
        c.downField("parallel").as[Option[Json]] match
          case Right(Some(pJson)) =>
            pJson.asArray match
              case Some(fan) =>
                for
                  ids <- fan.toList.traverse(_.as[String])
                  onFail <- c.downField("onFail").as[Option[OnFailMode]]
                yield Parallel(ids, onFail.getOrElse(OnFailMode.Abort))
              case None =>
                for
                  slotField <- pJson.hcursor.downField("slots").as[String]
                  template <- pJson.hcursor.downField("template").as[String]
                  // onFail sits OUTSIDE the parallel object — same placement as
                  // the static array form, so the two shapes stay uniform
                  // ({ "parallel": ... , "onFail": ... }).
                  onFail <- c.downField("onFail").as[Option[OnFailMode]]
                yield ParallelDynamic(slotField, template, onFail.getOrElse(OnFailMode.Abort))
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
    case ParallelDynamic(slotField, template, onFail) =>
      Json.obj(
        "parallel" -> Json.obj(
          "slots" -> Json.fromString(slotField),
          "template" -> Json.fromString(template)
        ),
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
  outputs: Map[String, String] = Map.empty,
  // 轨道二 #5（专用化护栏）：节点级白名单——显式要回面向用户的展示类工具
  // （Pop/AskUserQuestion）并让身份条款切换为「受众=用户+下游」变体。默认
  // false；只应在「哪个环节天然面向用户」的终审型节点上开启。
  userFacing: Boolean = false
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
      userFacing <- c.downField("userFacing").as[Option[Boolean]]
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
    yield FlowNode(agent, input, onComplete, onError, maxRetries.getOrElse(0), outputs.getOrElse(Map.empty), userFacing.getOrElse(false))
  }

  given Encoder[FlowNode] = Encoder.instance { n =>
    Json.obj(
      "agent" -> n.agent.asJson,
      "input" -> n.input.asJson,
      "onComplete" -> n.onComplete.asJson,
      "onError" -> n.onError.asJson,
      "maxRetries" -> n.maxRetries.asJson,
      "outputs" -> (if n.outputs.isEmpty then Json.Null else n.outputs.asJson),
      // Default is false — omit so legacy round-trips stay byte-compatible.
      "userFacing" -> (if n.userFacing then Json.True else Json.Null)
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
  maxFanout: Int = 4,
  // Structured trigger parameters: name → spec. FlowTrigger callers may pass
  // `params` (validated against type/range); node inputs reference them via
  // $params.<name> (defaults apply, missing → "[param <name> not provided]").
  params: Map[String, FlowParamSpec] = Map.empty
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
      params <- c.downField("params").as[Option[Map[String, FlowParamSpec]]]
    yield FlowDagDef(
      name, description, nodes, entry,
      maxLoop.getOrElse(10), strictVerdict.getOrElse(false), maxFanout.getOrElse(4),
      params.getOrElse(Map.empty)
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
      "maxFanout" -> (if f.maxFanout != 4 then f.maxFanout.asJson else Json.Null),
      "params" -> (if f.params.isEmpty then Json.Null else f.params.asJson)
    )
  }
end FlowDagDef

/** Declared trigger parameter of a flow (flow.json top-level "params"). */
case class FlowParamSpec(
  `type`: String, // "int" | "string" | "bool"
  default: Option[Json] = None,
  min: Option[Int] = None,
  max: Option[Int] = None,
  description: String = ""
)

object FlowParamSpec:

  private val validTypes = Set("int", "string", "bool")

  given Decoder[FlowParamSpec] = Decoder.instance { c =>
    for
      t <- c.downField("type").as[String]
      _ <-
        if validTypes.contains(t) then Right(())
        else Left(DecodingFailure(s"param type must be one of ${validTypes.toList.sorted.mkString(" | ")} (got \"$t\")", c.history))
      default <- c.downField("default").as[Option[Json]]
      min <- c.downField("min").as[Option[Int]]
      max <- c.downField("max").as[Option[Int]]
      _ <-
        if t == "int" || (min.isEmpty && max.isEmpty) then Right(())
        else Left(DecodingFailure("min/max are only valid for int params", c.history))
      description <- c.downField("description").as[Option[String]]
    yield FlowParamSpec(t, default, min, max, description.getOrElse(""))
  }

  given Encoder[FlowParamSpec] = Encoder.instance { p =>
    Json.obj(
      "type" -> p.`type`.asJson,
      "default" -> p.default.getOrElse(Json.Null),
      "min" -> p.min.asJson,
      "max" -> p.max.asJson,
      "description" -> (if p.description.isEmpty then Json.Null else p.description.asJson)
    )
  }
end FlowParamSpec

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
  slots: Map[String, Json] = Map.empty,
  /** Flow-node supervision P2 (2026-08-26): the failed agent reported its
    * failure as agent-turn-retryable (AgentError.retryable — LLM stall /
    * overload). The executor's Restart path uses checkpoint recovery
    * (loadMessagesForSession) instead of a fresh re-run when this is set. */
  retryable: Boolean = false
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
