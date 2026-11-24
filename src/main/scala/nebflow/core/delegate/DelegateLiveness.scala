package nebflow.core.delegate

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.actor.*

/** Instance-local ownership is an incarnation identity, not a retained reference or activity timestamp. */
object DelegateLiveness:

  final case class Verdict(running: Boolean):
    def json: Json = Json.obj("status" -> (if running then "running" else "interrupted").asJson)

  /** Take one actor-system snapshot for the whole row set. Stale same-path refs do not match. */
  def adopted(system: ActorSystem, agents: Map[String, AgentRecord]): IO[Set[String]] =
    system.ownedActors.map { owned =>
      agents.iterator.collect {
        case (id, agent)
            if agent.ref != null &&
              owned.get(agent.ref.path.toString).exists(_ eq agent.ref) =>
          id
      }.toSet
    }

  def verdictOf(agent: Option[AgentRecord], adopted: Boolean): Verdict =
    Verdict(adopted && agent.exists(a => a.status == AgentStatus.Processing || a.status == AgentStatus.WaitingForUser))
end DelegateLiveness
