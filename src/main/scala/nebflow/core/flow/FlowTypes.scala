package nebflow.core.flow

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json, JsonObject}

import scala.concurrent.duration.{FiniteDuration, *}

// ============================================================
// Flow Definition (parsed from ExecuteFlow tool input)
// ============================================================

/** A complete flow definition submitted by the main agent. */
case class FlowDef(
  name: String,
  description: String,
  steps: List[FlowStep],
  verify: VerifyStep,
  loop: Option[LoopDef] = None,
  maxConcurrency: Int = 5
)

/** A single work step in the flow DAG. */
case class FlowStep(
  id: String,
  agent: String,
  prompt: String,
  dependsOn: Set[String] = Set.empty,
  retry: Int = 2,
  timeout: FiniteDuration = 30.minutes
)

/** Mandatory verification step. Produces PASS/FAIL + summary. */
case class VerifyStep(
  agent: String = "Explorer",
  prompt: String,
  timeout: FiniteDuration = 30.minutes
)

/** Optional loop: on verify FAIL, run fix step then re-verify. */
case class LoopDef(
  fix: FlowStep,
  maxIterations: Int = 3
)

// ============================================================
// JSON Codecs (for persistence)
// ============================================================

given Encoder[FlowStep] = Encoder.instance { s =>
  Json.obj(
    "id" -> s.id.asJson,
    "agent" -> s.agent.asJson,
    "prompt" -> s.prompt.asJson,
    "dependsOn" -> s.dependsOn.toList.asJson,
    "retry" -> s.retry.asJson,
    "timeoutSeconds" -> s.timeout.toSeconds.asJson
  )
}

given Decoder[FlowStep] = Decoder.instance { c =>
  for
    id <- c.downField("id").as[String]
    agent <- c.downField("agent").as[String]
    prompt <- c.downField("prompt").as[String]
    deps <- c.downField("dependsOn").as[Option[List[String]]]
    retry <- c.downField("retry").as[Option[Int]]
    timeoutS <- c.downField("timeoutSeconds").as[Option[Int]]
  yield FlowStep(
    id,
    agent,
    prompt,
    deps.getOrElse(Nil).toSet,
    retry.getOrElse(2),
    timeoutS.getOrElse(1800).seconds
  )
}

given Encoder[VerifyStep] = Encoder.instance { v =>
  Json.obj(
    "agent" -> v.agent.asJson,
    "prompt" -> v.prompt.asJson,
    "timeoutSeconds" -> v.timeout.toSeconds.asJson
  )
}

given Decoder[VerifyStep] = Decoder.instance { c =>
  for
    agent <- c.downField("agent").as[Option[String]]
    prompt <- c.downField("prompt").as[String]
    timeoutS <- c.downField("timeoutSeconds").as[Option[Int]]
  yield VerifyStep(agent.getOrElse("Explorer"), prompt, timeoutS.getOrElse(1800).seconds)
}

given Encoder[LoopDef] = Encoder.instance { l =>
  Json.obj(
    "fix" -> l.fix.asJson,
    "maxIterations" -> l.maxIterations.asJson
  )
}

given Decoder[LoopDef] = Decoder.instance { c =>
  for
    fix <- c.downField("fix").as[FlowStep]
    maxIter <- c.downField("maxIterations").as[Option[Int]]
  yield LoopDef(fix, maxIter.getOrElse(3))
}

given Encoder[FlowDef] = Encoder.instance { f =>
  Json.obj(
    "name" -> f.name.asJson,
    "description" -> f.description.asJson,
    "steps" -> f.steps.asJson,
    "verify" -> f.verify.asJson,
    "loop" -> f.loop.asJson,
    "maxConcurrency" -> f.maxConcurrency.asJson
  )
}

given Decoder[FlowDef] = Decoder.instance { c =>
  for
    name <- c.downField("name").as[String]
    description <- c.downField("description").as[String]
    steps <- c.downField("steps").as[List[FlowStep]]
    verify <- c.downField("verify").as[VerifyStep]
    loop <- c.downField("loop").as[Option[LoopDef]]
    maxConv <- c.downField("maxConcurrency").as[Option[Int]]
  yield FlowDef(name, description, steps, verify, loop, maxConv.getOrElse(5))
}

// ============================================================
// FlowSnapshot (serializable persisted state)
// ============================================================

/** Serializable snapshot of a flow's state, excluding runtime ActorRefs. */
case class FlowSnapshot(
  flowDef: FlowDef,
  flowId: String,
  phase: String,
  stepStatus: Map[String, String],
  results: Map[String, String],
  failedReasons: Map[String, String],
  retryLeft: Map[String, Int],
  verifyResult: Option[String],
  iteration: Int
)

object FlowSnapshot:

  given Encoder[FlowSnapshot] = Encoder.instance { s =>
    Json.obj(
      "flowDef" -> s.flowDef.asJson,
      "flowId" -> s.flowId.asJson,
      "phase" -> s.phase.asJson,
      "stepStatus" -> s.stepStatus.asJson,
      "results" -> s.results.asJson,
      "failedReasons" -> s.failedReasons.asJson,
      "retryLeft" -> s.retryLeft.asJson,
      "verifyResult" -> s.verifyResult.asJson,
      "iteration" -> s.iteration.asJson
    )
  }

  given Decoder[FlowSnapshot] = Decoder.instance { c =>
    for
      flowDef <- c.downField("flowDef").as[FlowDef]
      flowId <- c.downField("flowId").as[String]
      phase <- c.downField("phase").as[String]
      stepStatus <- c.downField("stepStatus").as[Option[Map[String, String]]]
      results <- c.downField("results").as[Option[Map[String, String]]]
      failedReasons <- c.downField("failedReasons").as[Option[Map[String, String]]]
      retryLeft <- c.downField("retryLeft").as[Option[Map[String, Int]]]
      verifyResult <- c.downField("verifyResult").as[Option[String]]
      iteration <- c.downField("iteration").as[Option[Int]]
    yield FlowSnapshot(
      flowDef,
      flowId,
      phase,
      stepStatus.getOrElse(Map.empty),
      results.getOrElse(Map.empty),
      failedReasons.getOrElse(Map.empty),
      retryLeft.getOrElse(Map.empty),
      verifyResult,
      iteration.getOrElse(0)
    )
  }
end FlowSnapshot

// ============================================================
// Flow Result (returned to main agent via ExternalEvent)
// ============================================================

sealed trait FlowResult extends Product with Serializable

object FlowResult:
  case class Pass(summary: String) extends FlowResult
  case class Fail(summary: String) extends FlowResult
  case class Cancelled(reason: String) extends FlowResult

  def resultType(fr: FlowResult): String = fr match
    case Pass(_) => "pass"
    case Fail(_) => "fail"
    case Cancelled(_) => "cancelled"

  def summary(fr: FlowResult): String = fr match
    case Pass(s) => s
    case Fail(s) => s
    case Cancelled(s) => s
end FlowResult

// ============================================================
// Internal State
// ============================================================

enum StepStatus:
  case Pending, Running, Done, Failed

enum FlowPhase:
  case Working, VerifyRunning, LoopFixing, Completed, Failed

case class VerifyResult(pass: Boolean, summary: String)

// ============================================================
// JSON Parsing — JsonObject → FlowDef
// ============================================================

object FlowDefParser:

  /**
   * Parse a JsonObject (from ExecuteFlow tool input) into FlowDef.
   * Returns Right(FlowDef) on success, Left(error message) on failure.
   */
  def parse(input: JsonObject): Either[String, FlowDef] =
    for
      name <- reqStr(input, "name")
      description <- reqStr(input, "description")
      stepsRaw <- reqArray(input, "steps")
      steps <- parseSteps(stepsRaw)
      verifyRaw <- reqObject(input, "verify")
      verify <- parseVerify(verifyRaw)
      loop <- parseLoop(input("loop").flatMap(_.asObject))
      maxConv = input("maxConcurrency").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(5)
    yield FlowDef(name, description, steps, verify, loop, maxConv)

  // ---------- step parsing ----------

  private def parseSteps(arr: Vector[Json]): Either[String, List[FlowStep]] =
    if arr.isEmpty then Left("steps cannot be empty")
    else arr.toList.traverse(parseStep)

  private def parseStep(json: Json): Either[String, FlowStep] =
    for
      obj <- json.asObject.toRight(s"step must be an object: $json")
      id <- reqStr(obj, "id")
      _ <- if id.isBlank then Left("step id cannot be blank") else Right(())
      agent <- reqStr(obj, "agent")
      prompt <- reqStr(obj, "prompt")
      deps = obj("dependsOn")
        .flatMap(_.asArray)
        .getOrElse(Vector.empty)
        .flatMap(_.asString)
        .toSet
      retry = obj("retry").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(2)
      timeoutS = obj("timeoutSeconds").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(1800)
    yield FlowStep(id, agent, prompt, deps, retry, timeoutS.seconds)

  // ---------- verify parsing ----------

  private def parseVerify(obj: JsonObject): Either[String, VerifyStep] =
    for
      prompt <- reqStr(obj, "prompt")
      agent = obj("agent").flatMap(_.asString).getOrElse("Explorer")
      timeoutS = obj("timeoutSeconds").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(1800)
    yield VerifyStep(agent, prompt, timeoutS.seconds)

  // ---------- loop parsing ----------

  private def parseLoop(loopObj: Option[JsonObject]): Either[String, Option[LoopDef]] =
    loopObj match
      case None => Right(None)
      case Some(obj) =>
        obj("fix").flatMap(_.asObject) match
          case None => Left("loop.fix must be an object")
          case Some(fixObj) =>
            parseStep(fixObj.asJson).map { fixStep =>
              val maxIter = obj("maxIterations").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(3)
              Some(LoopDef(fixStep, maxIter))
            }

  // ---------- helpers ----------

  private def reqStr(obj: JsonObject, key: String): Either[String, String] =
    obj(key).flatMap(_.asString) match
      case Some(s) if s.nonEmpty => Right(s)
      case _ => Left(s"Missing required field: $key")

  private def reqArray(obj: JsonObject, key: String): Either[String, Vector[Json]] =
    obj(key).flatMap(_.asArray) match
      case Some(arr) if arr.nonEmpty => Right(arr)
      case Some(_) => Left(s"$key cannot be empty")
      case None => Left(s"Missing required field: $key")

  private def reqObject(obj: JsonObject, key: String): Either[String, JsonObject] =
    obj(key).flatMap(_.asObject) match
      case Some(o) => Right(o)
      case None => Left(s"Missing required field: $key")

end FlowDefParser

// ============================================================
// Validation
// ============================================================

object FlowValidator:

  /** Validate a parsed FlowDef. Returns Right(()) on success, Left(errors) on failure. */
  def validate(flow: FlowDef): Either[String, Unit] =
    val errors = List.newBuilder[String]

    // Check step IDs are unique and non-blank
    val stepIds = flow.steps.map(_.id)
    flow.steps.filter(_.id.isBlank).foreach(_ => errors += "Step ID cannot be blank")
    val dupIds = stepIds.groupBy(identity).filter(_._2.size > 1).keys
    if dupIds.nonEmpty then errors += s"Duplicate step IDs: ${dupIds.mkString(", ")}"

    // Check no self-dependencies
    flow.steps
      .filter(s => s.dependsOn.contains(s.id))
      .foreach(s => errors += s"Step '${s.id}' cannot depend on itself")

    // Check all dependsOn references exist
    val idSet = stepIds.toSet
    flow.steps.foreach { s =>
      val missing = s.dependsOn -- idSet
      if missing.nonEmpty then errors += s"Step '${s.id}' depends on unknown IDs: ${missing.mkString(", ")}"
    }

    // Check fix step ID doesn't collide with work step IDs
    flow.loop.foreach { loop =>
      if idSet.contains(loop.fix.id) then errors += s"Loop fix step ID '${loop.fix.id}' collides with a work step ID"
    }

    // Check DAG (no cycles)
    checkDag(flow.steps) match
      case Left(cycle) => errors += s"Circular dependency detected: ${cycle.mkString(" -> ")}"
      case _ => ()

    val errs = errors.result()
    if errs.isEmpty then Right(()) else Left(errs.mkString("; "))

  end validate

  /** Detect cycles in the step dependency graph. Returns Left(cycle) if found. */
  private def checkDag(steps: List[FlowStep]): Either[List[String], Unit] =
    val deps = steps.map(s => s.id -> s.dependsOn).toMap
    val visited = scala.collection.mutable.Set.empty[String]
    val inStack = scala.collection.mutable.Set.empty[String]
    val path = scala.collection.mutable.ListBuffer.empty[String]

    def hasCycle(id: String): Option[List[String]] =
      if inStack.contains(id) then
        val cycleStart = path.indexOf(id)
        Some(path.slice(cycleStart, path.length).toList :+ id)
      else if visited.contains(id) then None
      else
        visited += id
        inStack += id
        path += id
        val found = deps.getOrElse(id, Set.empty).toList.flatMap(hasCycle)
        path -= id
        inStack -= id
        found.headOption

    val cycle = steps.flatMap(s => hasCycle(s.id)).headOption
    cycle.toLeft(())
  end checkDag

end FlowValidator

// ============================================================
// FlowVerifyRegistry — bridges FlowVerifyTool ↔ FlowActor adapter
// ============================================================

/**
 * Global registry mapping verify agent paths to their pending Deferred.
 * FlowActor registers a Deferred before spawning a verify agent.
 * FlowVerifyTool completes it when the verify agent calls the tool.
 * The adapter checks it on agent completion.
 */
object FlowVerifyRegistry:
  private val pending = Ref.unsafe[IO, Map[String, Deferred[IO, VerifyResult]]](Map.empty)

  def register(agentPath: String, d: Deferred[IO, VerifyResult]): IO[Unit] =
    pending.update(_ + (agentPath -> d))

  def tryGet(agentPath: String): IO[Option[Deferred[IO, VerifyResult]]] =
    pending.get.map(_.get(agentPath))

  def complete(agentPath: String, result: VerifyResult): IO[Boolean] =
    pending.get.flatMap { m =>
      m.get(agentPath) match
        case Some(d) => d.complete(result).as(true)
        case None => IO.pure(false)
    }

  def remove(agentPath: String): IO[Unit] =
    pending.update(_ - agentPath)
end FlowVerifyRegistry
