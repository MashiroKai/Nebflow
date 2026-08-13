package nebflow.core.flow

import cats.effect.{IO, Ref}
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * Tracks running flow DAG instances for the frontend.
 *
 * Each entry represents a one-shot flow triggered via Mail.
 * The frontend polls or receives WS events to render the DAG + progress.
 */
object RunningFlowRegistry:

  case class NodeState(
    nodeId: String,
    agent: String,
    status: NodeStatus, // lifecycle status — wire value via .wire (pending/running/completed/failed/cancelled)
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
    edges: List[(String, String, Option[String])], // (from, to, condition)
    status: NodeStatus, // running | completed | failed | cancelled
    startedAt: Long,
    completedAt: Option[Long] = None,
    sessionId: Option[String] = None // parent session for WS routing
  )

  private val flows: Ref[IO, Map[String, RunningFlow]] = Ref.unsafe(Map.empty)
  private val cancelledFlows: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)

  /** Mark a flow as cancelled. The DAG executor checks this between nodes. */
  def cancel(instanceId: String): IO[Unit] =
    cancelledFlows.update(_ + instanceId) *>
      update(instanceId)(_.copy(status = NodeStatus.Cancelled))

  /** Check if a flow has been cancelled. */
  def isCancelled(instanceId: String): IO[Boolean] =
    cancelledFlows.get.map(_.contains(instanceId))

  /** Clear the cancel flag (called after flow execution ends). */
  def clearCancelled(instanceId: String): IO[Unit] =
    cancelledFlows.update(_ - instanceId)

  def register(flow: RunningFlow): IO[Unit] =
    flows.update(_ + (flow.instanceId -> flow))

  def update(instanceId: String)(f: RunningFlow => RunningFlow): IO[Unit] =
    flows.update(m => m.get(instanceId).map(f).map(rf => m + (instanceId -> rf)).getOrElse(m))

  def setNodeStatus(
    instanceId: String,
    nodeId: String,
    status: NodeStatus,
    output: String = "",
    error: String = ""
  ): IO[Unit] =
    update(instanceId) { rf =>
      val now = System.currentTimeMillis()
      val updatedNodes = rf.nodes.get(nodeId) match
        case Some(ns) =>
          rf.nodes + (nodeId -> ns.copy(
            status = status,
            output = if output.nonEmpty then output else ns.output,
            error = if error.nonEmpty then error else ns.error,
            startedAt = if status == NodeStatus.Running then Some(now) else ns.startedAt,
            completedAt =
              if status == NodeStatus.Completed || status == NodeStatus.Failed then Some(now) else ns.completedAt
          ))
        case None => rf.nodes
      val newStatus = status match
        case NodeStatus.Failed => NodeStatus.Failed
        case NodeStatus.Completed if nodeId == rf.entry => NodeStatus.Completed
        case _ => rf.status
      rf.copy(
        nodes = updatedNodes,
        status = newStatus,
        completedAt =
          if newStatus == NodeStatus.Completed || newStatus == NodeStatus.Failed then Some(now) else rf.completedAt
      )
    }

  def list: IO[List[RunningFlow]] =
    flows.get.map(_.values.toList.sortBy(_.startedAt))

  /** Remove a completed/failed flow instance. */
  def remove(instanceId: String): IO[Unit] =
    flows.update(_ - instanceId)

  /**
   * Remove flows that completed more than 5 minutes ago.
   * Called periodically to prevent unbounded memory growth.
   */
  def cleanupStale(retentionMs: Long = 5 * 60 * 1000): IO[Unit] =
    flows.update { m =>
      val now = System.currentTimeMillis()
      m.filterNot { (_, rf) =>
        (rf.status == NodeStatus.Completed || rf.status == NodeStatus.Failed) &&
        rf.completedAt.exists(now - _ > retentionMs)
      }
    }

  def toJson(flow: RunningFlow): JsonObject = JsonObject.fromIterable(
    List(
      "instanceId" -> flow.instanceId.asJson,
      "flowName" -> flow.flowName.asJson,
      "description" -> flow.description.asJson,
      "entry" -> flow.entry.asJson,
      "status" -> flow.status.wire.asJson,
      "startedAt" -> flow.startedAt.asJson,
      "completedAt" -> flow.completedAt.asJson,
      "nodes" -> flow.nodes.values.toList.map { ns =>
        JsonObject
          .fromIterable(
            List(
              "nodeId" -> ns.nodeId.asJson,
              "agent" -> ns.agent.asJson,
              "status" -> ns.status.wire.asJson,
              "output" -> (if ns.output.length > 200 then ns.output.take(197) + "..." else ns.output).asJson,
              "error" -> ns.error.asJson,
              "startedAt" -> ns.startedAt.asJson,
              "completedAt" -> ns.completedAt.asJson
            )
          )
          .asJson
      }.asJson,
      "edges" -> flow.edges.map { case (from, to, cond) =>
        Json.obj("from" -> from.asJson, "to" -> to.asJson, "condition" -> cond.asJson)
      }.asJson
    )
  )

  def listJson: IO[Json] =
    list.map(flows => Json.obj("flows" -> flows.map(toJson).asJson))

end RunningFlowRegistry
