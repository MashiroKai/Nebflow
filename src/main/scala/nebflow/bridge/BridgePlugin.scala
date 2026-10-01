package nebflow.bridge

import cats.effect.IO
import io.circe.Json
import nebflow.shared.SessionMeta

/**
 * A remote-bridge plugin that connects Nebflow sessions to an external platform.
 *
 * Implementations: Telegram, Discord, etc.
 * Plugins are loaded by BridgeManager and are fully self-contained —
 * removing a plugin's code and its config file should not break Nebflow.
 */
trait BridgePlugin:
  /** Unique identifier, e.g. "telegram". */
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
  /** Inject a user message into a session's agent.
    *
    * `origin` (source-marker batch, 2026-10-01) describes WHERE the message came
    * from, so the unified injection point can label it. It is optional and
    * defaults to `None` — every pre-existing call site keeps its exact
    * behaviour, and a channel that does not announce itself injects an unlabelled
    * message rather than a guessed one. The template itself lives on the
    * channel-agnostic side ([[BridgeOrigin]]), never in a channel's own code.
    */
  def injectMessage(sessionId: String, content: String, senderId: Option[String],
      origin: Option[BridgeOrigin] = None): IO[Unit]

  /** Interrupt the agent's current turn for a session. */
  def interruptAgent(sessionId: String): IO[Unit]

  /** Look up a session's metadata. */
  def sessionMeta(sessionId: String): IO[Option[SessionMeta]]

  /** List all sessions. */
  def listSessions: IO[List[SessionMeta]]

  /** Update (or remove) the bridge config for a specific platform on a session. */
  def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit]
end BridgeContext

/** Where an inbound message came from, expressed channel-agnostically: a stable
  * id (the bridge's own `BridgePlugin.name`), a human display label, and an
  * optional conversation reference. Pure data — a channel supplies its values,
  * never its own copy of the template.
  *
  * The rendered form is a `<system-reminder>` block, deliberately the same
  * shape the author asked for ("我们要对消息进行区分，比如 system reminder 标记消息
  * 来自飞书（社交接口）"). 🔴 The two template lines below are §16 copy: they were
  * reviewed verbatim by the author and must not be reworded here.
  */
final case class BridgeOrigin(channelId: String, channelDisplay: String, chatRef: Option[String] = None)

object BridgeOrigin:

  val MarkerOpen = "<system-reminder>"
  val MarkerClose = "</system-reminder>"

  /** `{sender_display}` when the event carried no usable sender identity. */
  val UnknownSender = "未知发送方"

  /** Render the source-marker block. The line's fixed wording is the frozen
    * template; only the four braces are filled. An absent chat reference renders
    * as `-` (neutral, and never a guessed conversation name). */
  def marker(origin: BridgeOrigin, senderId: Option[String]): String =
    val sender = senderId.map(_.trim).filter(_.nonEmpty).getOrElse(UnknownSender)
    val chat = origin.chatRef.map(_.trim).filter(_.nonEmpty).getOrElse("-")
    s"""$MarkerOpen
       |以下消息来自${origin.channelDisplay}（社交接口 · 渠道 ${origin.channelId}）· 发送方：$sender · 会话：$chat
       |$MarkerClose""".stripMargin

  /** Compose the injected string: **marker block (1) then content (2/3)**, one
    * blank line between them — the composition order the inbound-parse design
    * card fixes at §10.4. `None` origin leaves the content byte-identical to the
    * pre-batch behaviour. */
  def compose(origin: Option[BridgeOrigin], senderId: Option[String], content: String): String =
    origin.map(o => marker(o, senderId) + "\n\n" + content).getOrElse(content)

end BridgeOrigin
