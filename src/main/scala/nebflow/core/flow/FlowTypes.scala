package nebflow.core.flow

import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

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
      loop = parseLoop(input("loop").flatMap(_.asObject))
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

  private def parseLoop(loopObj: Option[JsonObject]): Option[LoopDef] =
    loopObj.flatMap { obj =>
      obj("fix").flatMap(_.asObject).map { fixObj =>
        val fixStep = parseStep(fixObj.asJson) match
          case Right(s) => s
          case Left(_) => FlowStep("fix", "Nebula", "Fix issues", Set.empty)
        val maxIter = obj("maxIterations").flatMap(_.asNumber.flatMap(_.toInt)).getOrElse(3)
        LoopDef(fixStep, maxIter)
      }
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

  /** Topological sort to detect cycles. Returns Left(cycle) if found. */
  private def checkDag(steps: List[FlowStep]): Either[List[String], Unit] =
    val deps = steps.map(s => s.id -> s.dependsOn).toMap
    val visited = scala.collection.mutable.Set.empty[String]
    val inStack = scala.collection.mutable.Set.empty[String]
    val path = scala.collection.mutable.ListBuffer.empty[String]

    def dfs(id: String): Option[List[String]] =
      if inStack.contains(id) then
        val cycleStart = path.indexOf(id)
        Some(path.slice(cycleStart, path.length).toList :+ id)
      else if visited.contains(id) then None
      else
        visited += id
        inStack += id
        path += id
        val result = deps.get(id).toList.flatten.flatMap(f => dfs(f).toList)
        path -= id
        inStack -= id
        result.headOption

    steps.foreach(s => dfs(s.id))
    // If dfs found any cycle, it was returned but we need to check differently
    // Redo with proper propagation
    visited.clear()
    inStack.clear()
    path.clear()

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
