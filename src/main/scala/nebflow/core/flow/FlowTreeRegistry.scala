package nebflow.core.flow

import cats.effect.{IO, Ref}
import nebflow.actor.ActorRef

/**
 * Global registry mapping session IDs to their FlowTreeActor.
 * 
 * The MountFlowTool uses this to find or create the FlowTreeActor
 * for the current session. The FlowTreeActor registers itself on
 * creation and unregisters on shutdown.
 */
object FlowTreeRegistry:
  private val trees = Ref.unsafe[IO, Map[String, ActorRef[TreeCommand]]](Map.empty)

  def register(sessionId: String, ref: ActorRef[TreeCommand]): IO[Unit] =
    trees.update(_ + (sessionId -> ref))

  def get(sessionId: String): IO[Option[ActorRef[TreeCommand]]] =
    trees.get.map(_.get(sessionId))

  def unregister(sessionId: String): IO[Unit] =
    trees.update(_ - sessionId)

  def clear: IO[Unit] = trees.set(Map.empty)

end FlowTreeRegistry
