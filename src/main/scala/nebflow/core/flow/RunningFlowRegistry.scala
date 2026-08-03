package nebflow.core.flow

import cats.effect.{IO, Ref}
import io.circe.{Json, JsonObject}
import io.circe.syntax.*

/** Tracks running flow DAG instances for the frontend.
 *
  * Each entry represents a one-shot flow triggered via Mail.
  * The frontend polls or receives WS events to render the DAG + progress.
  */
object RunningFlowRegistry:

  case class NodeState(
    nodeId: String,
    agent: String,
    status: String,         // "pending" | "running" | "completed" | "failed"
    output: String = "",
    error: String = "",
    startedAt: Option[Long] = None,
    completedAt: Option[Long] = None
  )

  case class RunningFlow(
    instanceId: String,
    flowName: String,
    description: String,
    entry: String,
    nodes: Map[String, NodeState],
    edges: List[(String, String, Option[String])],  // (from, to, condition)
    status: String,         // "running" | "completed" | "failed"
    startedAt: Long,
    completedAt: Option[Long] = None,
    sessionId: Option[String] = None  // parent session for WS routing
  )

  private val flows: Ref[IO, Map[String, RunningFlow]] = Ref.unsafe(Map.empty)

  def register(flow: RunningFlow): IO[Unit] =
    flows.update(_ + (flow.instanceId -> flow))

  def update(instanceId: String)(f: RunningFlow => RunningFlow): IO[Unit] =
    flows.update(m => m.get(instanceId).map(f).map(rf => m + (instanceId -> rf)).getOrElse(m))

  def setNodeStatus(instanceId: String, nodeId: String, status: String, output: String = "", error: String = ""): IO[Unit] =
    update(instanceId) { rf =>
      val now = System.currentTimeMillis()
      val updatedNodes = rf.nodes.get(nodeId) match
        case Some(ns) =>
          rf.nodes + (nodeId -> ns.copy(
            status = status,
            output = if output.nonEmpty then output else ns.output,
            error = if error.nonEmpty then error else ns.error,
            startedAt = if status == "running" then Some(now) else ns.startedAt,
            completedAt = if status == "completed" || status == "failed" then Some(now) else ns.completedAt
          ))
        case None => rf.nodes
      val newStatus = status match
        case "failed" => "failed"
        case _ if nodeId == rf.entry && status == "completed" => "completed"
        case _ => rf.status
      rf.copy(nodes = updatedNodes, status = newStatus,
        completedAt = if newStatus == "completed" || newStatus == "failed" then Some(now) else rf.completedAt)
    }

  def list: IO[List[RunningFlow]] =
    flows.get.map(_.values.toList.sortBy(_.startedAt))

  /** Remove a completed/failed flow instance. */
  def remove(instanceId: String): IO[Unit] =
    flows.update(_ - instanceId)

  /** Remove flows that completed more than 5 minutes ago.
    * Called periodically to prevent unbounded memory growth. */
  def cleanupStale(retentionMs: Long = 5 * 60 * 1000): IO[Unit] =
    flows.update { m =>
      val now = System.currentTimeMillis()
      m.filterNot { (_, rf) =>
        (rf.status == "completed" || rf.status == "failed") &&
        rf.completedAt.exists(now - _ > retentionMs)
      }
    }

  def toJson(flow: RunningFlow): JsonObject = JsonObject.fromIterable(List(
    "instanceId" -> flow.instanceId.asJson,
    "flowName" -> flow.flowName.asJson,
    "description" -> flow.description.asJson,
    "entry" -> flow.entry.asJson,
    "status" -> flow.status.asJson,
    "startedAt" -> flow.startedAt.asJson,
    "completedAt" -> flow.completedAt.asJson,
    "nodes" -> flow.nodes.values.toList.map { ns =>
      JsonObject.fromIterable(List(
        "nodeId" -> ns.nodeId.asJson,
        "agent" -> ns.agent.asJson,
        "status" -> ns.status.asJson,
        "output" -> (if ns.output.length > 200 then ns.output.take(197) + "..." else ns.output).asJson,
        "error" -> ns.error.asJson,
        "startedAt" -> ns.startedAt.asJson,
        "completedAt" -> ns.completedAt.asJson
      )).asJson
    }.asJson,
    "edges" -> flow.edges.map { case (from, to, cond) =>
      Json.obj("from" -> from.asJson, "to" -> to.asJson, "condition" -> cond.asJson)
    }.asJson
  ))

  def listJson: IO[Json] =
    list.map(flows => Json.obj("flows" -> flows.map(toJson).asJson))

end RunningFlowRegistry
