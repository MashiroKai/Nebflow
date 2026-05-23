package nebflow.bridge

import cats.effect.IO
import io.circe.Json
import nebflow.shared.SessionMeta

/**
 * A remote-bridge plugin that connects Nebflow sessions to an external platform.
 *
 * Implementations: Feishu (WebSocket long-connection), Telegram, Discord, etc.
 * Plugins are loaded by BridgeManager and are fully self-contained —
 * removing a plugin's code and its config file should not break Nebflow.
 */
trait BridgePlugin:
  /** Unique identifier, e.g. "feishu", "telegram". */
  def name: String

  /** Start the plugin (connect to platform, register listeners, etc.). */
  def start(ctx: BridgeContext): IO[Unit]

  /** Graceful shutdown. */
  def stop: IO[Unit]

  /**
   * Called when a Nebflow agent emits an event (AI response, tool call, etc.).
   * The plugin should forward relevant events to the bound remote chat.
   */
  def onAgentEvent(sessionId: String, event: Json): IO[Unit]

  /** Called when bridge configs change (e.g. session binds/unbinds a chat). Rebuild routing tables. */
  def refreshRoutes: IO[Unit] = IO.unit

end BridgePlugin

/**
 * Core APIs that a bridge plugin can call.
 * Provided by Nebflow core; plugins must not hold hard references to
 * gateway internals beyond this trait.
 */
trait BridgeContext:
  /** Inject a user message into a session's agent. */
  def injectMessage(sessionId: String, content: String, senderId: Option[String]): IO[Unit]

  /** Interrupt the agent's current turn for a session. */
  def interruptAgent(sessionId: String): IO[Unit]

  /** Look up a session's metadata. */
  def sessionMeta(sessionId: String): IO[Option[SessionMeta]]

  /** List all sessions. */
  def listSessions: IO[List[SessionMeta]]

  /** Update (or remove) the bridge config for a specific platform on a session. */
  def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit]
end BridgeContext
