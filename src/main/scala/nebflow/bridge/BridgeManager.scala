package nebflow.bridge

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import nebflow.shared.NebflowLogger

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

  /** Stop one plugin (if present) and remove it from the map (feishubridge
    * batch). Stop errors are logged, never swallowed into silence, and the
    * removal happens regardless — a plugin whose stop failed must not stay
    * registered. Absent name = no-op, so callers can resync unconditionally. */
  def unregister(name: String): IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      plugins.get(name).traverse_ { p =>
        p.stop.handleErrorWith { e =>
          logger.warn(s"Bridge plugin '$name' stop error during unregister: ${e.getMessage}")
        }
      } *> pluginsRef.update(_ - name)
    }

  /** Start one registered plugin by name (feishubridge batch). Errors are
    * contained and logged, mirroring [[startAll]]'s per-plugin discipline. */
  def startOne(name: String): IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      plugins.get(name).traverse_ { p =>
        p.start(ctx).handleErrorWith { e =>
          logger.error(s"Bridge plugin '$name' failed to start: ${e.getMessage}")
        } *> logger.info(s"Bridge plugin '$name' started")
      }
    }

  /** The currently registered plugin names — the read side the REST probe face
    * answers `adapterRegistered` from (feishubridge batch). */
  def registeredNames: IO[Set[String]] =
    pluginsRef.get.map(_.keySet)

  /** The registered plugin INSTANCE by name — the read side the feishu panel
    * connection face uses to ask the live adapter what it actually connected
    * to (feishu-bind batch, 2026-09-27). None = not registered. */
  def plugin(name: String): IO[Option[BridgePlugin]] =
    pluginsRef.get.map(_.get(name))

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

  /** Tell all plugins to refresh their routing tables. */
  def refreshRoutes: IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      plugins.values.toList.traverse_(_.refreshRoutes)
    }

end BridgeManager

object BridgeManager:

  def create(ctx: BridgeContext): IO[BridgeManager] =
    Ref.of[IO, Map[String, BridgePlugin]](Map.empty).map(new BridgeManager(ctx, _))
