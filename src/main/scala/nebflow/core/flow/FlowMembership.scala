package nebflow.core.flow

import cats.effect.{IO, Ref}

/**
 * Global registry tracking which agents belong to which flows, and the
 * communication topology (DAG edges) within each flow.
 *
 * Communication rule: two flow agents can Mail each other if and only if
 * there is a DAG edge between their nodes in the flow graph. Edges come from:
 *   - dependsOn: if B depends on A, then A ↔ B
 *   - retry target: if node N has retry.target=T, then N ↔ T
 *
 * Non-flow agents (standalone, Delegate-spawned) are unrestricted — the flow
 * scoping is an additional constraint on flow agents, not a global restriction.
 */
object FlowMembership:

  // agentPath -> Set[(flowId, nodeId)]
  private val membership: Ref[IO, Map[String, Set[(String, String)]]] =
    Ref.unsafe[IO, Map[String, Set[(String, String)]]](Map.empty)

  // flowId -> Set of undirected edges, stored as lexicographically sorted pairs
  private val flowEdges: Ref[IO, Map[String, Set[(String, String)]]] =
    Ref.unsafe[IO, Map[String, Set[(String, String)]]](Map.empty)

  private def edgeKey(a: String, b: String): (String, String) =
    if a <= b then (a, b) else (b, a)

  /** Register a flow's communication topology (call once when flow starts). */
  def registerFlow(flowId: String, nodes: List[FlowNode]): IO[Unit] =
    val edges = nodes.flatMap { n =>
      n.dependsOn.map(dep => edgeKey(n.id, dep)) ++
      n.retry.toList.map(r => edgeKey(n.id, r.target))
    }.toSet
    flowEdges.update(_.updated(flowId, edges))

  /** Remove a flow's topology (call when flow completes). */
  def unregisterFlow(flowId: String): IO[Unit] =
    flowEdges.update(_ - flowId)

  /** Register an agent as a member of a flow with its node ID. */
  def join(agentPath: String, flowId: String, nodeId: String): IO[Unit] =
    membership.update(m => m.updated(agentPath, m.getOrElse(agentPath, Set.empty) + ((flowId, nodeId))))

  /** Remove an agent from a specific flow. */
  def leave(agentPath: String, flowId: String): IO[Unit] =
    membership.update { m =>
      val updated = m.getOrElse(agentPath, Set.empty).filterNot(_._1 == flowId)
      if updated.isEmpty then m - agentPath
      else m.updated(agentPath, updated)
    }

  /** Remove an agent from all flows (used on agent shutdown). */
  def leaveAll(agentPath: String): IO[Unit] =
    membership.update(_ - agentPath)

  /** Get all flow IDs an agent belongs to. */
  def flowsOf(agentPath: String): IO[Set[String]] =
    membership.get.map(_.getOrElse(agentPath, Set.empty).map(_._1))

  /** Get all member paths in a flow. */
  def membersOf(flowId: String): IO[List[String]] =
    membership.get.map(_.collect {
      case (path, entries) if entries.exists(_._1 == flowId) => path
    }.toList)

  /**
   * Check if two agents can communicate via Mail.
   *
   * Rules:
   * - Non-flow senders: unrestricted (backward compat for Delegate, standalone)
   * - Flow agent → non-flow agent: allowed (reaching outside is OK)
   * - Flow agent → flow agent: must share a flow AND have a DAG edge
   */
  def canCommunicate(senderPath: String, recipientPath: String): IO[Boolean] =
    for
      m <- membership.get
      edges <- flowEdges.get
      senderEntries = m.getOrElse(senderPath, Set.empty)
      recipientEntries = m.getOrElse(recipientPath, Set.empty)
    yield
      if senderEntries.isEmpty then true
      else if recipientEntries.isEmpty then true
      else
        senderEntries.exists { (sFlow, sNode) =>
          recipientEntries.exists { (rFlow, rNode) =>
            sFlow == rFlow && hasEdge(edges, sFlow, sNode, rNode)
          }
        }

  private def hasEdge(
    allEdges: Map[String, Set[(String, String)]],
    flowId: String,
    nodeA: String,
    nodeB: String
  ): Boolean =
    nodeA == nodeB || allEdges.get(flowId).exists(_.contains(edgeKey(nodeA, nodeB)))

  /** Clear all membership and edges (used in tests). */
  def clear: IO[Unit] =
    membership.set(Map.empty) *> flowEdges.set(Map.empty)

end FlowMembership
