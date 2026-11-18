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
 *
 * Started-state tracking (2026-10-25 boot-OOM fix): the boot sequence starts
 * enabled plugins twice — FeishuBridgePlugin.sync / WeixinIlinkBridgePlugin.sync
 * register-and-start BEFORE the startAll fan-out (GatewayMain), and that
 * fan-out's comment has always claimed "start is idempotent at plugin level".
 * The claim was false: pluginsRef only tracked registration, so startAll
 * re-ran every plugin start, and the doubled bridge startup blew the 2G heap
 * at boot (4/4 boots OOM'd seconds after the duplicated `started` lines).
 * startOne/startAll now atomically claim the plugin name (Ref.modify) and
 * skip already-started plugins; unregister/stopAll release the claim so a
 * later re-register starts cleanly.
 */
class BridgeManager private (
  ctx: BridgeContext,
  pluginsRef: Ref[IO, Map[String, BridgePlugin]],
  startedRef: Ref[IO, Set[String]]
):
  private val logger = NebflowLogger.forName("nebflow.bridge")

  def register(plugin: BridgePlugin): IO[Unit] =
    pluginsRef.update(_ + (plugin.name -> plugin))

  /**
   * Stop one plugin (if present) and remove it from the map (feishubridge
   * batch). Stop errors are logged, never swallowed into silence, and the
   * removal happens regardless — a plugin whose stop failed must not stay
   * registered. Absent name = no-op, so callers can resync unconditionally.
   * The started-claim is released too, so a re-register + startOne is a real
   * start, not a skip.
   *
   * Completion is logged on the success / absent-name path too: the only
   * previous trace of a deactivation was the ABSENCE of a `started` line,
   * which cannot tell "never attempted" from "verdict false" from "start
   * failed". Every path through this method is now readable from the log.
   */
  def unregister(name: String): IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      plugins.get(name).traverse_ { p =>
        p.stop.handleErrorWith { e =>
          logger.warn(s"Bridge plugin '$name' stop error during unregister: ${e.getMessage}")
        }
      } *> pluginsRef.update(_ - name) *> startedRef.update(_ - name) *>
        logger.info(s"Bridge plugin '$name' unregistered")
    }

  /**
   * Start one registered plugin by name (feishubridge batch). Errors are
   * contained and logged, mirroring [[startAll]]'s per-plugin discipline.
   * Idempotent: a plugin already started (e.g. by a boot-time sync) is
   * skipped, not re-started.
   */
  def startOne(name: String): IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      plugins.get(name).traverse_(startPlugin)
    }

  /**
   * The currently registered plugin names — the read side the REST probe face
   * answers `adapterRegistered` from (feishubridge batch).
   */
  def registeredNames: IO[Set[String]] =
    pluginsRef.get.map(_.keySet)

  /**
   * The registered plugin INSTANCE by name — the read side the feishu panel
   * connection face uses to ask the live adapter what it actually connected
   * to (feishu-bind batch, 2026-09-27). None = not registered.
   */
  def plugin(name: String): IO[Option[BridgePlugin]] =
    pluginsRef.get.map(_.get(name))

  /**
   * Start every registered plugin not yet started. The atomic claim in
   * [[startPlugin]] makes this the no-op the boot comment always promised
   * for plugins already started by their sync pass — and safe under
   * concurrency.
   */
  def startAll: IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      if plugins.isEmpty then logger.info("No bridge plugins configured")
      else plugins.values.toList.traverse_(startPlugin)
    }

  // Atomic claim-then-start: the name is reserved BEFORE p.start runs, so a
  // second caller (sync + startAll fan-out) observes it as already started
  // instead of re-running the (state-heavy) plugin startup. A failed start
  // releases the claim so a later retry is possible.
  private def startPlugin(p: BridgePlugin): IO[Unit] =
    startedRef
      .modify { s =>
        if s.contains(p.name) then (s, false) else (s + p.name, true)
      }
      .flatMap {
        case false =>
          logger.info(s"Bridge plugin '${p.name}' already started — duplicate start skipped")
        case true =>
          p.start(ctx).attempt.flatMap {
            case Right(_) => logger.info(s"Bridge plugin '${p.name}' started")
            case Left(e) =>
              startedRef.update(_ - p.name) *>
                logger.error(s"Bridge plugin '${p.name}' failed to start: ${e.getMessage}")
          }
      }

  /**
   * Stop every plugin. Mirrors [[startAll]]'s per-plugin log discipline: the
   * success of each stop is readable, not only its failure — a shutdown that
   * logged nothing was indistinguishable from one that never ran. The
   * started-claim is released per plugin.
   */
  def stopAll: IO[Unit] =
    pluginsRef.get.flatMap { plugins =>
      plugins.values.toList.traverse_ { p =>
        p.stop.handleErrorWith { e =>
          logger.warn(s"Bridge plugin '${p.name}' stop error: ${e.getMessage}")
        } *> startedRef.update(_ - p.name) *> logger.info(s"Bridge plugin '${p.name}' stopped")
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
    Ref.of[IO, Map[String, BridgePlugin]](Map.empty).flatMap { plugins =>
      Ref.of[IO, Set[String]](Set.empty).map(new BridgeManager(ctx, plugins, _))
    }

end BridgeManager
