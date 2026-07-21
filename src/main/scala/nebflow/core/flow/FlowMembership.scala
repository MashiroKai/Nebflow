package nebflow.core.flow

import cats.effect.{IO, Ref}

/**
 * Global registry tracking which agents belong to which flows.
 *
 * Communication rule: two agents can Mail each other if they share at least
 * one flow membership. Non-flow agents (standalone, Delegate-spawned) are
 * unrestricted — the flow scoping is an additional constraint on flow agents,
 * not a global restriction.
 *
 * The flow creator (main agent) is automatically registered as a member,
 * giving it the ability to communicate with all agents in flows it created.
 */
object FlowMembership:

  private val membership: Ref[IO, Map[String, Set[String]]] =
    Ref.unsafe[IO, Map[String, Set[String]]](Map.empty)

  /** Register an agent as a member of a flow. */
  def join(agentPath: String, flowId: String): IO[Unit] =
    membership.update(m => m.updated(agentPath, m.getOrElse(agentPath, Set.empty) + flowId))

  /** Remove an agent from a specific flow. */
  def leave(agentPath: String, flowId: String): IO[Unit] =
    membership.update { m =>
      val updated = m.getOrElse(agentPath, Set.empty) - flowId
      if updated.isEmpty then m - agentPath
      else m.updated(agentPath, updated)
    }

  /** Remove an agent from all flows (used on agent shutdown). */
  def leaveAll(agentPath: String): IO[Unit] =
    membership.update(_ - agentPath)

  /** Get all flow IDs an agent belongs to. */
  def flowsOf(agentPath: String): IO[Set[String]] =
    membership.get.map(_.getOrElse(agentPath, Set.empty))

  /** Get all member paths in a flow. */
  def membersOf(flowId: String): IO[List[String]] =
    membership.get.map(_.collect {
      case (path, flowIds) if flowIds.contains(flowId) => path
    }.toList)

  /**
   * Check if two agents can communicate via Mail.
   *
   * Rules:
   * - Non-flow senders: unrestricted (backward compat for Delegate, standalone)
   * - Flow agent → non-flow agent: allowed (reaching outside is OK)
   * - Flow agent → flow agent: must share at least one flow
   */
  def canCommunicate(senderPath: String, recipientPath: String): IO[Boolean] =
    membership.get.map { m =>
      val senderFlows = m.getOrElse(senderPath, Set.empty)
      val recipientFlows = m.getOrElse(recipientPath, Set.empty)
      if senderFlows.isEmpty then true
      else if recipientFlows.isEmpty then true
      else (senderFlows & recipientFlows).nonEmpty
    }

  /** Clear all membership (used in tests). */
  def clear: IO[Unit] = membership.set(Map.empty)

end FlowMembership
