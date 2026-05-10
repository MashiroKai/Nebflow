package nebflow.bridge

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import nebflow.core.NebflowLogger

/**
 * Manages the lifecycle of all bridge plugins.
 *
 * - Registers as a WsHub listener to forward agent events to all plugins.
 * - Provides BridgeContext to each plugin so they can inject messages.
 * - Removing all plugins = this manager becomes a no-op, zero impact on core.
 */
class BridgeManager private (
  ctx: BridgeContext,
  pluginsRef: Ref[IO, Map[String, BridgePlugin]]
):
  private val logger = NebflowLogger.forName("nebflow.bridge")

  def register(plugin: BridgePlugin): IO[Unit] =
    pluginsRef.update(_ + (plugin.name -> plugin))

  def startAll: IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      if plugins.isEmpty then logger.info("No bridge plugins configured")
      else
        plugins.values.toList.traverse_ { p =>
          p.start(ctx).handleErrorWith { e =>
            logger.error(s"Bridge plugin '${p.name}' failed to start: ${e.getMessage}")
          } *> logger.info(s"Bridge plugin '${p.name}' started")
        }
    }

  def stopAll: IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      plugins.values.toList.traverse_ { p =>
        p.stop.handleErrorWith { e =>
          logger.warn(s"Bridge plugin '${p.name}' stop error: ${e.getMessage}")
        }
      }
    }

  /** Dispatch an agent event to all plugins. Called from WsHub broadcast. */
  def dispatchAgentEvent(sessionId: String, event: Json): IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      plugins.values.toList.traverse_(_.onAgentEvent(sessionId, event))
    }

end BridgeManager

object BridgeManager:

  def create(ctx: BridgeContext): IO[BridgeManager] =
    Ref.of[IO, Map[String, BridgePlugin]](Map.empty).map(new BridgeManager(ctx, _))
