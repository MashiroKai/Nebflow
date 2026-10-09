package nebflow.bridge

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
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

  /**
   * Inject a user message into a session's agent.
   *
   * `origin` (source-marker batch, 2026-10-01) describes WHERE the message came
   * from, so the unified injection point can label it. It is optional and
   * defaults to `None` — every pre-existing call site keeps its exact
   * behaviour, and a channel that does not announce itself injects an unlabelled
   * message rather than a guessed one. The template itself lives on the
   * channel-agnostic side ([[BridgeOrigin]]), never in a channel's own code.
   */
  def injectMessage(
    sessionId: String,
    content: String,
    senderId: Option[String],
    origin: Option[BridgeOrigin] = None
  ): IO[Unit]

  /** Interrupt the agent's current turn for a session. */
  def interruptAgent(sessionId: String): IO[Unit]

  /** Look up a session's metadata. */
  def sessionMeta(sessionId: String): IO[Option[SessionMeta]]

  /** List all sessions. */
  def listSessions: IO[List[SessionMeta]]

  /** Update (or remove) the bridge config for a specific platform on a session. */
  def updateBridgeConfig(sessionId: String, platform: String, config: Option[Json]): IO[Unit]

  // ─────────── askuser-remote P1 (2026-10-07): the interaction face ───────────
  // Three additions, each with a NO-OP default so every pre-existing
  // implementor (and every offline fake) keeps compiling and behaving exactly
  // as before. The production implementor is the gateway's BridgeContext
  // (GatewayMain), which routes to the InteractionHub — the single ask
  // authority (A-5 拍板: 禁第二份状态) and the single answer convergence point.

  /**
   * The hub's global pending-AskUser snapshot (`ListAllPendingAsks` frames,
   * oldest first — each carries requestId / sessionId=root / items /
   * attachments). The bridges' open-ask AUTHORITY: a bridge keeps at most a
   * routing cache (requestId → chat), never a second ledger. Default = no
   * hub reachable (offline fakes) → empty.
   */
  def listPendingAsks: IO[List[Json]] = IO.pure(Nil)

  /**
   * Forward a bridge-sourced answer into the hub as
   * `InteractionAnswered(requestId, rootSessionId, payload)` — the SAME
   * command the frontend answer leg sends, entered through a DEDICATED
   * bridge method (never mixed with the frontend WS leg). Default no-op.
   */
  def forwardInteractionAnswer(requestId: String, rootSessionId: String, payload: Json): IO[Unit] = IO.unit

  /**
   * Ask the hub to close one pending slot by requestId (`CloseRequest`) —
   * the B-3 新问顶旧问 primitive: a new ask on a channel that carries one
   * active ask per sender retires the previous one precisely (never the
   * session-wide sweep). Default no-op.
   */
  def closeInteractionRequest(requestId: String): IO[Unit] = IO.unit

  /**
   * askucancel 批（2026-10-08）：**用户发起的取消**——按 requestId 取消一个
   * 仍在等待作答的 AskUser 问题（hub `CancelRequest`：kind 闸 + 发起方终结
   * 通知 + 幂等裁决；引擎侧共享执行器还会在卡片所属会话追加 `__cancelled__`
   * 落盘行）。与 [[closeInteractionRequest]] 的分工：那条是渠道机器语义
   * （B-3 新问顶旧问的内部回收，逐字节保留），这条是**人**的取消动作
   * （渠道关键字 / 渠道卡按钮 / 任何渠道层 UI）。`reason`/`by` 进日志与
   * 审计行。Default no-op（离线 fake 保持编译与行为不变）。
   */
  def cancelInteractionRequest(requestId: String, reason: String, by: String): IO[Unit] = IO.unit
end BridgeContext

/**
 * Where an inbound message came from, expressed channel-agnostically: a stable
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

  /**
   * Render the source-marker block. The line's fixed wording is the frozen
   * template; only the four braces are filled. An absent chat reference renders
   * as `-` (neutral, and never a guessed conversation name).
   */
  def marker(origin: BridgeOrigin, senderId: Option[String]): String =
    val sender = senderId.map(_.trim).filter(_.nonEmpty).getOrElse(UnknownSender)
    val chat = origin.chatRef.map(_.trim).filter(_.nonEmpty).getOrElse("-")
    s"""$MarkerOpen
       |以下消息来自${origin.channelDisplay}（社交接口 · 渠道 ${origin.channelId}）· 发送方：$sender · 会话：$chat
       |$MarkerClose""".stripMargin

  /**
   * Compose the **model-context** string: **marker block (1) then content (2/3)**,
   * one blank line between them — the composition order the inbound-parse design
   * card fixes at §10.4. `None` origin leaves the content byte-identical to the
   * pre-batch behaviour.
   *
   * 🔴 **This is the MODEL face, never the session-display face** (source-marker
   * relocation batch, 2026-10-01). The rendered value carries the marker block
   * and is only ever allowed to reach the agent's context (the `ExternalEvent`
   * payload). The string a session record or a `bridgeUser` frame carries is
   * [[BridgeMessageFaces.sessionDisplay]] — the bare content. An earlier revision
   * fed this same value to both faces, which is the reported defect (the author
   * saw the marker rendered inside the chat stream: 「现在飞书消息的 system
   * reminder 会在会话消息中显示，system reminder 永远不显示在会话中」). Callers
   * must go through [[faces]], which keeps the two apart by construction.
   */
  def compose(origin: Option[BridgeOrigin], senderId: Option[String], content: String): String =
    origin.map(o => marker(o, senderId) + "\n\n" + content).getOrElse(content)

  /**
   * The structured origin fields that ride along in the event `metadata`. The
   * `senderId` key is the pre-existing contract and is emitted in both modes
   * (including `None` values), so an `origin = None` injection produces a
   * byte-identical metadata object. An announced origin adds the channel id, the
   * human display label and — only when it is non-blank — the conversation
   * reference; a blank reference adds no key rather than a guessed name.
   */
  def originMetadata(origin: Option[BridgeOrigin], senderId: Option[String]): JsonObject =
    val base = JsonObject("senderId" -> senderId.asJson)
    origin.fold(base) { o =>
      val withChannel =
        base.add("channelId", o.channelId.asJson).add("channelDisplay", o.channelDisplay.asJson)
      o.chatRef
        .map(_.trim)
        .filter(_.nonEmpty)
        .fold(withChannel)(chat => withChannel.add("chatRef", chat.asJson))
    }

  /**
   * One inbound message split into the faces it is allowed to appear on.
   *
   *   - [[sessionDisplay]] — the session display face: the `.ui.json` row and the
   *     `bridgeUser` broadcast. Bare content, marker-free by construction.
   *   - [[modelPayload]] — the model-context face: the `ExternalEvent` payload,
   *     marker retained so the model keeps its source attribution.
   *   - [[metadata]] — the structured origin, for later faces to consume.
   *
   * `origin = None` (the default, and every channel that does not announce
   * itself) leaves all three byte-identical to the pre-batch values.
   */
  final case class BridgeMessageFaces(
    sessionDisplay: String,
    modelPayload: String,
    metadata: JsonObject
  )

  /**
   * Split one injected message at the single seam, so the two faces can never be
   * conflated again: the marker goes to the model and only to the model.
   */
  def faces(origin: Option[BridgeOrigin], senderId: Option[String], content: String): BridgeMessageFaces =
    BridgeMessageFaces(
      sessionDisplay = content,
      modelPayload = compose(origin, senderId, content),
      metadata = originMetadata(origin, senderId)
    )

end BridgeOrigin
