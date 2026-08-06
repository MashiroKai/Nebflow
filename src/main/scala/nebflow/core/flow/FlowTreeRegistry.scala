package nebflow.core.flow

import cats.effect.{IO, Ref}
import nebflow.actor.{ActorRef, ActorSystem}
import nebflow.core.NebflowLogger
import nebflow.core.tools.ToolContext

import scala.concurrent.duration.*

/**
 * Global registry mapping session IDs to their FlowTreeActor.
 *
 * LoadTool uses this to find or create the FlowTreeActor
 * for the current session. The FlowTreeActor registers itself on
 * creation and unregisters on shutdown.
 */
object FlowTreeRegistry:
  private val trees = Ref.unsafe[IO, Map[String, ActorRef[TreeCommand]]](Map.empty)
  private val logger = NebflowLogger.forName("nebflow.flow.registry")

  /**
   * Signal: completed after the first FlowTreeActor finishes restoreFlows.
   *  /api/flows awaits this so the frontend doesn't get an empty list on first fetch.
   */
  private val restoreDone: cats.effect.Deferred[IO, Unit] = cats.effect.Deferred.unsafe[IO, Unit]

  def treesRef: cats.effect.Ref[IO, Map[String, ActorRef[TreeCommand]]] = trees

  /** Signal that restoreFlows has completed (idempotent — only first call matters). */
  def signalRestoreComplete: IO[Unit] = restoreDone.complete(()).void.handleErrorWith(_ => IO.unit)

  /** Wait up to `timeoutMs` for restoreFlows to complete. Non-blocking if already done. */
  def awaitRestore(timeoutMs: Long = 3000L): IO[Unit] =
    restoreDone.get.timeoutTo(timeoutMs.millis, IO.unit).void

  def register(sessionId: String, ref: ActorRef[TreeCommand]): IO[Unit] =
    trees.update(_ + (sessionId -> ref))

  def get(sessionId: String): IO[Option[ActorRef[TreeCommand]]] =
    trees.get.map(_.get(sessionId))

  def unregister(sessionId: String): IO[Unit] =
    trees.update(_ - sessionId)

  def clear: IO[Unit] = trees.set(Map.empty)

  /** Get an existing FlowTreeActor for the session, or create one if needed. */
  def getOrCreate(ctx: ToolContext): IO[ActorRef[TreeCommand]] =
    val sessionId = ctx.sessionId.getOrElse("default")

    get(sessionId).flatMap {
      case Some(ref) => IO.pure(ref)
      case None =>
        (ctx.actorSystem, ctx.sharedResources, ctx.agentActorRef) match
          case (Some(system), Some(resources), Some(parentRef)) =>
            val safetyModeIO = ctx.sessionStore match
              case Some(store) => store.getSafetyMode(sessionId)
              case None => IO.pure("confirm-edits")

            safetyModeIO.flatMap { safetyMode =>
              // Compute projectsDir from folderId
              val projectsDir = (ctx.sessionStore, ctx.folderId) match
                case (Some(store), Some(fid)) =>
                  val folderName = store.getFolderName(fid).getOrElse(fid.take(8))
                  Some((nebflow.core.PathUtil.dataRoot / "projects" / folderName).toString)
                case _ => None

              val config = FlowTreeActor.TreeConfig(
                parentAgentRef = parentRef,
                wsSend = ctx.wsSend,
                sessionId = ctx.sessionId,
                resources = resources,
                projectRoot = ctx.projectRoot,
                safetyMode = safetyMode,
                folderId = ctx.folderId,
                projectsDir = projectsDir
              )
              for
                ref <- system.spawn(FlowTreeActor(config), s"flow-tree-$sessionId")
                _ <- register(sessionId, ref)
                _ <- logger.info(s"Created FlowTreeActor for session $sessionId")
              yield ref
            }
          case _ =>
            IO.raiseError(
              new RuntimeException(
                "FlowTreeRegistry.getOrCreate requires ActorSystem, SharedResources, and agentActorRef"
              )
            )
    }
  end getOrCreate

end FlowTreeRegistry
