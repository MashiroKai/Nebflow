package nebflow.core.flow

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

/**
 * Tracks running flow DAG instances for the frontend.
 *
 * Each entry represents a one-shot flow triggered via the FlowTrigger tool.
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

  // Per-instance cancel signal: completed by cancel(), raced against by the
  // DAG executor's executeAgent so a user cancel PIERCES the running node
  // (Stop + actor stop) instead of waiting for it to finish on its own.
  // The cancelledFlows flag stays: it covers the between-nodes check.
  private val cancelSignals: Ref[IO, Map[String, Deferred[IO, Unit]]] = Ref.unsafe(Map.empty)

  // R8-P2 internal fail-fast signal: a parallel branch failed under
  // onFail=abort — pierce sibling agents exactly like a user cancel, but the
  // flow's final status is Failed (NOT Cancelled), so it is tracked separately
  // from the user-cancel flag.
  private val abortedFlows: Ref[IO, Set[String]] = Ref.unsafe(Set.empty)
  private val abortSignals: Ref[IO, Map[String, Deferred[IO, Unit]]] = Ref.unsafe(Map.empty)

  /** Internal fail-fast: stop sibling branch agents (idempotent). */
  def failFast(instanceId: String): IO[Unit] =
    abortedFlows.update(_ + instanceId) *>
      abortSignals.get.flatMap(_.get(instanceId).traverse_(_.complete(())))

  /** Check whether fail-fast fired for this instance. */
  def isAborted(instanceId: String): IO[Boolean] =
    abortedFlows.get.map(_.contains(instanceId))

  /** The internal fail-fast signal (created on demand like cancelSignal). */
  def abortSignal(instanceId: String): IO[Deferred[IO, Unit]] =
    Deferred[IO, Unit].flatMap { fresh =>
      abortSignals.modify { m =>
        m.get(instanceId) match
          case Some(existing) => (m, existing)
          case None           => (m + (instanceId -> fresh), fresh)
      }
    }

  /**
   * Mark a flow as cancelled. Sets the between-nodes flag and completes the
   * per-instance cancel signal (idempotent — Deferred.complete on an
   * already-completed signal is a no-op, so repeated cancels are harmless).
   */
  def cancel(instanceId: String): IO[Unit] =
    cancelledFlows.update(_ + instanceId) *>
      update(instanceId)(_.copy(status = NodeStatus.Cancelled)) *>
      cancelSignals.get.flatMap(_.get(instanceId).traverse_(_.complete(())))

  /** Check if a flow has been cancelled. */
  def isCancelled(instanceId: String): IO[Boolean] =
    cancelledFlows.get.map(_.contains(instanceId))

  /**
   * Clear the cancel flag and drop the cancel signal (called after flow
   * execution ends so the signal map doesn't grow unboundedly). Also clears
   * the internal fail-fast state.
   */
  def clearCancelled(instanceId: String): IO[Unit] =
    cancelledFlows.update(_ - instanceId) *>
      cancelSignals.update(_ - instanceId) *>
      abortedFlows.update(_ - instanceId) *>
      abortSignals.update(_ - instanceId)

  /**
   * The per-instance cancel signal. Creates one if absent (register normally
   * already did), so a caller can always race on a real Deferred.
   */
  def cancelSignal(instanceId: String): IO[Deferred[IO, Unit]] =
    Deferred[IO, Unit].flatMap { fresh =>
      cancelSignals.modify { m =>
        m.get(instanceId) match
          case Some(existing) => (m, existing)
          case None           => (m + (instanceId -> fresh), fresh)
      }
    }

  def register(flow: RunningFlow): IO[Unit] =
    flows.update(_ + (flow.instanceId -> flow)) *>
      cancelSignal(flow.instanceId).void *>
      abortSignal(flow.instanceId).void

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
    flows.update(_ - instanceId) *>
      cancelSignals.update(_ - instanceId)

  /**
   * Remove flows that completed more than 5 minutes ago.
   * Called periodically to prevent unbounded memory growth.
   */
  def cleanupStale(retentionMs: Long = 5 * 60 * 1000): IO[Unit] =
    flows.modify { m =>
      val now = System.currentTimeMillis()
      val kept = m.filterNot { (_, rf) =>
        (rf.status == NodeStatus.Completed || rf.status == NodeStatus.Failed) &&
        rf.completedAt.exists(now - _ > retentionMs)
      }
      (kept, kept)
    }.flatMap(kept => cancelSignals.update(sig => sig.filter((id, _) => kept.contains(id))))

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
