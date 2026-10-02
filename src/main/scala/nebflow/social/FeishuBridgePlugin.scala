package nebflow.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.bridge.{BridgeContext, BridgeManager, BridgePlugin}
import nebflow.shared.NebflowLogger
import nebflow.shared.SessionMeta

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feishu bridge adapter (feishubridge batch, 2026-09-25) — the consumer side the
 * upstream plan card (`20260925_195206_socchannel-plan2`, §3/§4) names
 * `FeishuBridgePlugin`: a thin wrapper that connects the existing live half
 * ([[FeishuChannel]], feishu-chan) to the bridge skeleton that was already on
 * main ([[BridgePlugin]] / [[BridgeManager]] / the `handleBridgeMessage`
 * injection path). No new mechanism is invented anywhere:
 *
 *   - inbound:  Feishu `im.message.receive_v1` → member-allowlist gate → the
 *     session bound to the chat ([[nebflow.shared.SessionMeta.bridges]] key
 *     `feishu` → `{"chat_id": ...}`) → `ctx.injectMessage`, which is the
 *     existing rate-limited `handleBridgeMessage` path (UiMessage record +
 *     `bridgeUser` broadcast + ExternalEvent injection);
 *   - outbound: root-agent `textDelta` events are accumulated per session and
 *     flushed as ONE text message on `done` — a chat reply is a message, not a
 *     delta stream. `interrupted` / `retryStatus` clear the buffer (a torn turn
 *     is never a reply); send failures are logged, never raised.
 *
 * 🔴 Zero-secret discipline (inherited from the feishu-chan batch): the
 * credential pair is resolved through [[FeishuCredentials]] and never logged,
 * never rendered, never written — only masked readings leave this class.
 *
 * 🔒 Security boundary (must be stated on every deliverable of this batch):
 * Feishu is a TENANT model. The long connection only ever speaks to the tenant
 * the app belongs to, so there is no public inbound — but EVERY member of that
 * tenant can reach the bound session while the allowlist field
 * (`allowed_open_ids`) is empty, which is the shipped default. Filling the
 * field turns the gate fail-closed (sender open_id must be on the list; a
 * message with no usable sender identity is dropped). Enabling it is the
 * author's call; this batch ships the slot, not the decision.
 *
 * Architecture note (actors vs cats-effect): this plugin is pure IO
 * orchestration — the SDK callback thread is bridged into IO in exactly one
 * place ([[intake]] via `unsafeRunSync`, error-contained upstream by
 * [[FeishuChannel]]'s dispatcher), which is the explicit-boundary shape the
 * repo's architecture rule prescribes.
 */
final class FeishuBridgePlugin(
  root: os.Path,
  /** Outbound send seam (production = [[FeishuBridgePlugin.defaultSend]]); the
    * offline spec injects a recording function so no test touches the network. */
  send: FeishuBridgePlugin.Send = FeishuBridgePlugin.defaultSend,
  /** Test seam for the allowlist: None = read from the stored card config at
    * start (production), Some(list) = pinned (offline spec). */
  pinnedAllowedOpenIds: Option[List[String]] = None,
  /** Test seam for the credential pair: None = resolve from disk at start
    * (production), Some(c) = pinned (offline spec; the recording send never
    * sees the values — its signature keeps them only for the SDK path). */
  pinnedCreds: Option[FeishuCredentials.Credential] = None,
  /** The auto-bind default session (feishu-bind batch, C4). Production re-reads
    * the stored config at each unbound-chat intake — a REST PUT can flip it
    * while the bridge runs, and the auto-bind path is cold (once per new chat),
    * so a fresh read is both always-correct and free. The offline spec pins a
    * function to stay off the real config. */
  readDefaultSession: os.Path => Option[String] =
    root => SocialChannels.defaultSessionId(root, FeishuBridgePlugin.Name)
) extends BridgePlugin:

  private val logger = NebflowLogger.forName("nebflow.social.feishu-bridge")

  def name: String = FeishuBridgePlugin.Name

  // ── runtime state (all mutation behind IO; one start/stop cycle at a time) ──
  /** chat_id → session id, the routing table rebuilt from SessionMeta.bridges. */
  private[social] val routesRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)
  /** session id → accumulated delta text for the turn in flight. */
  private[social] val pendingRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)
  /** The member allowlist in force; empty = unrestricted (shipped default).
    * The pinned value is the INITIAL state so the gate is armed from
    * construction; start() re-reads the stored card config over it. */
  private[social] val allowedRef =
    cats.effect.Ref.unsafe[IO, List[String]](pinnedAllowedOpenIds.getOrElse(Nil))
  private val ctxRef = cats.effect.Ref.unsafe[IO, Option[BridgeContext]](None)
  @volatile private var listener: Option[FeishuChannel.Listener] = None
  @volatile private var creds: Option[FeishuCredentials.Credential] = pinnedCreds
  @volatile private var region: String = "feishu" // hardcoded default (lark hidden, author 2026-09-25 ④)
  /** Double-start guard: boot-time sync AND `startAll` both call start — the
    * second call must be a no-op, or two WebSocket listeners would open. */
  private val starting = new AtomicBoolean(false)

  // ───────────────────────────── lifecycle ─────────────────────────────
  def start(ctx: BridgeContext): IO[Unit] =
    if !starting.compareAndSet(false, true) then IO.unit
    else
      val body =
        for
          _ <- ctxRef.set(Some(ctx))
          cfg <- IO.blocking(FeishuBridgePlugin.readChannelConfig(root))
          _ = region = cfg.region
          _ <- allowedRef.set(pinnedAllowedOpenIds.getOrElse(cfg.allowedOpenIds))
          _ <- rebuildRoutes(ctx)
          resolved <- IO.blocking(FeishuCredentials.resolve(root))
          _ <- resolved match
            case Right(c) => IO(openListener(ctx, c))
            case Left(reason) =>
              // The config gate (verified) should have caught this; defense in
              // depth — stay registered but unconnected, never throw.
              IO(logger.warn(s"feishu bridge: no usable credential (${FeishuBridgePlugin.render(reason)}) — registered but unconnected"))
        yield ()
      body.handleErrorWith(e => IO(logger.error(s"feishu bridge: start failed: ${e.getClass.getSimpleName}: ${e.getMessage}"))) *>
        IO(if listener.isEmpty then starting.set(false)) // failed start ⇒ a later sync may retry

  /** Idempotent teardown — the single exit path (D-2②: the caller owns the
    * exit; here that means the BridgeManager, and stop is safe to call twice). */
  val stop: IO[Unit] =
    IO {
      listener.foreach { l =>
        try l.stop()
        catch case _: Exception => () // Listener.stop is itself idempotent and contained
      }
      listener = None
      creds = pinnedCreds
      starting.set(false)
    } *> pendingRef.set(Map.empty) // a torn turn is never a reply, also across restarts

  // ───────────────────────────── inbound ─────────────────────────────
  /** The ONE sync→IO boundary: runs on the SDK's receive thread. Exceptions are
    * contained by the dispatcher in [[FeishuChannel]] (they only mark
    * `lastError`), so a throwing injection cannot kill the socket. */
  private def runIntake(ctx: BridgeContext, in: FeishuMessage.Inbound): Unit =
    intake(ctx, in).unsafeRunSync()

  private[social] def intake(ctx: BridgeContext, in: FeishuMessage.Inbound): IO[Unit] =
    for
      allowed <- allowedRef.get
      routes <- routesRef.get
      // inbound-parse batch (2026-10-01): the third element is the RENDERED
      // body, not `in.text`. `text` is only ever set for `message_type=text`, so
      // routing on it silently dropped every post/image/file message. `parsed`
      // carries the readable body for all four inbound classes, and a
      // non-text type now produces the explicit unrecognised-type placeholder
      // instead of vanishing. The `None` arm below is therefore reachable only
      // when the message genuinely carries nothing at all.
      body = renderedBody(in)
      _ <- (allowed.isEmpty || in.senderId.exists(allowed.contains), routes.get(in.chatId), body) match
        case (false, _, _) =>
          // Fail-closed when the list is non-empty: an absent/unresolvable
          // sender identity is as good as a non-member. Ordered FIRST so the
          // allowlist also rules the auto-bind arm below (C6: only a listed
          // member can mint a binding).
          IO(logger.warn(s"feishu bridge: message from chat ${in.chatId} dropped — sender is not on the member allowlist"))
        case (_, None, _) =>
          autoBind(ctx, in)
        case (_, _, None) =>
          IO(logger.info(s"feishu bridge: message ${in.messageId} (${in.messageType}) has no readable body — nothing injected"))
        case (_, Some(sessionId), Some(text)) =>
          logger.info(s"feishu bridge: chat ${in.chatId} -> session $sessionId") *>
            ctx.injectMessage(sessionId, text, in.senderId, originOf(in))
    yield ()

  /** The bridge-side readable body: quote段 then body, in the composition order
    * the design card fixes (§10.4). The source-marker block is NOT prepended
    * here — that belongs to the unified injection point, which is the only place
    * that knows the channel identity. */
  private[social] def renderedBody(in: FeishuMessage.Inbound): Option[String] =
    InboundParse.compose(
      quote = InboundParse.quoteSection(in.quotedMessageId, quotedSender = None, quotedText = None),
      body = in.parsed
    )

  /** The channel-agnostic origin descriptor this bridge contributes. The channel
    * id is the bridge's own stable name and the display name is the human label;
    * the chat is the conversation reference. Pure data — the template lives on
    * the channel-agnostic side ([[nebflow.bridge.BridgeOrigin]]). */
  private def originOf(in: FeishuMessage.Inbound): Option[nebflow.bridge.BridgeOrigin] =
    Some(nebflow.bridge.BridgeOrigin(
      channelId = FeishuBridgePlugin.Name,
      channelDisplay = FeishuBridgePlugin.ChannelDisplay,
      chatRef = Option(in.chatId).map(_.trim).filter(_.nonEmpty)))

  /** P1 auto-bind (feishu-bind batch, 2026-09-27): the first message from a
    * not-yet-bound chat binds that chat to the configured default session and
    * injects the message there — zero commands, the lightweight equivalent of a
    * /bind command for the single-user trust model (the 2026-09-27 diagnosis:
    * the repo had ZERO bindings, so even a correctly-connected bridge dropped
    * every first message). The binding persists through
    * [[BridgeContext.updateBridgeConfig]] (SessionMeta.bridges, key `feishu`)
    * carrying `source=auto` + `boundAt`, so the bindings face can tell
    * auto-bindings from manual ones and the mapping survives a restart. The
    * local routes table is updated in the same step so the turn in flight can
    * already be replied to. No default session configured ⇒ the pre-existing
    * behaviour, unchanged: drop + log. */
  private def autoBind(ctx: BridgeContext, in: FeishuMessage.Inbound): IO[Unit] =
    IO.blocking(readDefaultSession(root)).flatMap {
      case Some(sessionId) =>
        val cfg = Json.obj(
          "chat_id" -> in.chatId.asJson,
          "source" -> "auto".asJson,
          "boundAt" -> System.currentTimeMillis().asJson
        )
        ctx.updateBridgeConfig(sessionId, FeishuBridgePlugin.Name, Some(cfg)) *>
          routesRef.update(_ + (in.chatId -> sessionId)) *>
          IO(logger.info(s"feishu bridge: chat ${in.chatId} auto-bound to default session $sessionId")) *>
          (renderedBody(in) match
            case Some(text) => ctx.injectMessage(sessionId, text, in.senderId, originOf(in))
            case None       => IO.unit)
      case None =>
        IO(logger.info(
          s"feishu bridge: no session is bound to chat ${in.chatId} and no default session is configured — message dropped"))
    }

  // ───────────────── panel probe face (feishu-bind batch, C1) ─────────────────
  /** Whether the long connection is currently up. */
  def connected: Boolean = listener.isDefined

  /** The app_id the bridge ACTUALLY resolved and connected with, if any. This
    * is an identifier, not a credential (same face as the scan-bind done
    * payload) — the secret never leaves this class. This is the half of the
    * fingerprint verdict the stored config cannot answer. */
  def liveAppId: Option[String] = creds.map(_.appId)

  private def openListener(ctx: BridgeContext, c: FeishuCredentials.Credential): Unit =
    val l = new FeishuChannel.Listener(c.appId, c.appSecret, region = region,
      handler = in => runIntake(ctx, in))
    val res = l.start()
    if res.ok then
      creds = Some(c)
      listener = Some(l)
      logger.info(s"feishu bridge: long connection up (${res.detail})")
    else
      l.stop() // a failed handshake still owns a socket attempt — never leak it
      logger.warn(s"feishu bridge: handshake failed (${res.detail}) — registered but unconnected")

  // ───────────────────────────── routing ─────────────────────────────
  override def refreshRoutes: IO[Unit] =
    ctxRef.get.flatMap {
      case Some(ctx) => rebuildRoutes(ctx)
      case None      => IO.unit
    }

  private[social] def rebuildRoutes(ctx: BridgeContext): IO[Unit] =
    ctx.listSessions.flatMap { metas =>
      val pairs = metas.flatMap { s =>
        s.bridges.get(FeishuBridgePlugin.Name)
          .flatMap(b => b.hcursor.downField("chat_id").as[String].toOption.map(_.trim).filter(_.nonEmpty))
          .map(_ -> s.id)
      }
      val bound = pairs.toMap // duplicate chat ids: last session wins
      val dupes = pairs.groupBy(_._1).count((_, v) => v.sizeIs > 1)
      val warn = if dupes > 0 then logger.warn(s"feishu bridge: $dupes chat(s) bound to more than one session — the last binding wins") else IO.unit
      warn *> routesRef.set(bound) *>
        logger.info(s"feishu bridge: routing table rebuilt — ${bound.size} chat(s) bound to sessions")
    }

  // ───────────────────────────── outbound ─────────────────────────────
  def onAgentEvent(sessionId: String, event: Json): IO[Unit] =
    val c = event.hcursor
    c.downField("type").as[String].getOrElse("") match
      case "textDelta" =>
        c.downField("delta").as[String].getOrElse("") match
          case ""   => IO.unit
          case delta => pendingRef.update(m => m.updated(sessionId, m.getOrElse(sessionId, "") + delta))
      case "done" =>
        pendingRef.modify { m => (m - sessionId, m.getOrElse(sessionId, "")) }.flatMap { text =>
          if text.trim.isEmpty then IO.unit else reply(sessionId, text)
        }
      // A retried or interrupted turn re-streams its text; the partial buffer
      // must never flush as a duplicated/garbled reply.
      case "interrupted" | "retryStatus" => pendingRef.update(_ - sessionId)
      case _                             => IO.unit

  private[social] def reply(sessionId: String, text: String): IO[Unit] =
    routesRef.get.flatMap { routes =>
      routes.collectFirst { case (chatId, sid) if sid == sessionId => chatId } match
        case None => IO.unit // not a feishu-bound session — other events simply pass through
        case Some(chatId) =>
          creds match
            case None => IO(logger.warn(s"feishu bridge: reply for chat $chatId dropped — no credential resolved"))
            case Some(c) =>
              // Channel-adapter-side text conversion (socoutbound batch, author
              // ruling #299): the OUTBOUND copy is de-marked-up so Feishu shows
              // no bare markdown syntax and keeps links alive as bare URLs. The
              // in-session original above is never rewritten — only this copy is.
              val outbound = nebflow.social.outbound.OutboundText.toPlain(text)
              send(c.appId, c.appSecret, region, chatId, outbound).flatMap { res =>
                if res.ok then
                  IO(logger.info(s"feishu bridge: reply delivered to chat $chatId (${res.messageId.getOrElse("-")})"))
                else
                  IO(logger.warn(s"feishu bridge: send rejected code=${res.code} msg=${res.msg}"))
              }
    }

end FeishuBridgePlugin

object FeishuBridgePlugin:

  /** BridgePlugin.name — also the platform key in SessionMeta.bridges. */
  val Name = "feishu"

  /** The human-readable label this channel announces in the source-marker block
    * (source-marker batch, 2026-10-01). Data only: the template lives on the
    * channel-agnostic side ([[nebflow.bridge.BridgeOrigin.marker]]), so a second
    * channel reuses it by supplying its own label — never by copying the text. */
  val ChannelDisplay = "飞书"

  /** Companion-side logger (the sync/guard legs live here, outside any instance). */
  private val log = NebflowLogger.forName("nebflow.social.feishu-bridge")

  /** Outbound send seam. appId/appSecret are handed to the SDK only — they must
    * never be logged (the implementations inherit the zero-secret discipline). */
  type Send = (String, String, String, String, String) => IO[FeishuChannel.SendResult]

  def defaultSend(appId: String, appSecret: String, region: String, receiveId: String, text: String): IO[FeishuChannel.SendResult] =
    IO.blocking(FeishuChannel.sendText(appId, appSecret, region, "chat_id", receiveId, text))

  final case class ChannelConfig(region: String, allowedOpenIds: List[String])

  /** The feishu card's stored fields, with the shipped defaults filled in:
    * region `feishu` (hardcoded default — the lark option is hidden, not
    * deleted), allowlist empty = unrestricted. */
  def readChannelConfig(root: os.Path): ChannelConfig =
    val fields = SocialChannels.readChannels(root).hcursor
      .downField("channels").downField(Name).downField("fields").focus.getOrElse(Json.obj())
    val region = fields.hcursor.downField("region").as[String].toOption
      .map(_.trim).filter(_.nonEmpty).getOrElse("feishu")
    val allowed = fields.hcursor.downField("allowed_open_ids").as[String].toOption
      .getOrElse("").split("[,;\\s]+").map(_.trim).filter(_.nonEmpty).toList
    ChannelConfig(region, allowed)

  /** Never leaks a value — the Failure cases carry reasons and paths only. */
  private def render(f: FeishuCredentials.Failure): String = f match
    case FeishuCredentials.Failure.Missing(r)      => s"missing: $r"
    case FeishuCredentials.Failure.Unreadable(r)   => s"unreadable: $r"
    case FeishuCredentials.Failure.Incomplete(r)   => s"incomplete: $r"

  /**
   * The ONE activation point of the config→verify→activate closed loop (plan
   * card v2 §4): `enabled ∧ verified ⇒ the adapter is registered (and started);
   * otherwise ⇒ it is not`. Boot (GatewayMain) and the save endpoint
   * (RestApiRoutes) both route through here, so runtime state can never be
   * assembled two different ways. Restart-on-every-save is deliberate: the
   * saved fields may BE the credentials, and [[FeishuChannel.Listener.stop]] is
   * an idempotent teardown. `factory` is the offline spec's seam (recording
   * fake instead of a real socket-opening plugin).
   *
   * feishu-bind batch (P0-2): after the adapter (re)started, the
   * [[guardFingerprint]] leg runs ONCE on the same instance — what the bridge
   * actually connected with must match what was just persisted (C2), and any
   * divergence is logged loudly AND answered by the connection probe face
   * (`fingerprintMatch != match`) instead of a green light.
   */
  def sync(manager: BridgeManager, root: os.Path,
      factory: os.Path => BridgePlugin = (r => new FeishuBridgePlugin(r))): IO[Unit] =
    val want = IO.blocking(SocialChannels.isEnabled(root, Name) && SocialChannels.verified(root, Name))
    want.flatMap {
      case true =>
        val plugin = factory(root)
        manager.unregister(Name) *> manager.register(plugin) *> manager.startOne(Name) *> {
          plugin match
            case fb: FeishuBridgePlugin => guardFingerprint(fb, root)
            case _                      => IO.unit // foreign plugin (offline spec fakes): nothing to guard
        }
      case false =>
        manager.unregister(Name)
    }

  // ───────────────── fingerprint guard + panel faces (feishu-bind batch) ─────────────────

  /** The schema's stored app_id — the inventory side of the verdict (C1). */
  def storedAppId(root: os.Path): Option[String] =
    SocialChannels.readChannels(root).hcursor.downField("channels").downField(Name)
      .downField("fields").downField("app_id").as[String].toOption.map(_.trim).filter(_.nonEmpty)

  /** C1/C2 verdict: does the live connection's app match the stored app_id?
    * `match` / `mismatch` / `unknown` (either side absent). Compared through
    * [[FeishuCredentials.fingerprint]] (stable, non-invertible), so the verdict
    * is safe to log and to surface — and no value ever needs to be written
    * down. */
  def fingerprintVerdict(storedAppId: Option[String], liveAppId: Option[String]): String =
    (storedAppId.map(_.trim).filter(_.nonEmpty), liveAppId.map(_.trim).filter(_.nonEmpty)) match
      case (Some(s), Some(l)) =>
        if FeishuCredentials.fingerprint(s) == FeishuCredentials.fingerprint(l) then "match" else "mismatch"
      case _ => "unknown"

  /** P0-2 (feishu-bind batch): the live-connection fingerprint guard, fired by
    * [[sync]] AFTER the adapter (re)started. The credential the bridge actually
    * resolved must fingerprint-match the app_id just persisted in the schema;
    * any other outcome is said out loud here and answered by the connection
    * probe face with `fingerprintMatch != "match"` — killing the "stored A,
    * connected B, panel green" lie the 2026-09-27 diagnosis nailed. A
    * non-match verdict warns and moves on (never throws): a dead bridge helps
    * nobody, the thing that must stop is the green light, not the bridge. */
  private def guardFingerprint(fb: FeishuBridgePlugin, root: os.Path): IO[Unit] =
    val stored = storedAppId(root)
    val verdict = fingerprintVerdict(stored, fb.liveAppId)
    verdict match
      case "match" =>
        IO(log.info(
          s"feishu bridge: fingerprint guard ok — the live connection matches the stored app_id (fp=${FeishuCredentials.fingerprint(stored.getOrElse(""))})"))
      case other =>
        IO(log.warn(
          s"feishu bridge: FINGERPRINT GUARD $other — the live connection's app does not correspond to the stored app_id " +
            s"(storedFp=${stored.map(FeishuCredentials.fingerprint).getOrElse("-")} " +
            s"liveFp=${fb.liveAppId.map(FeishuCredentials.fingerprint).getOrElse("-")}); " +
            s"the connection face answers fingerprintMatch=$other"))

  /** The C1 connection payload (GET /api/social/channels/feishu/connection):
    * bridge enabled state, adapter registration, live connection + connected
    * app, stored app_id, and the fingerprint verdict (C2: `mismatch` ⇒ the
    * panel renders a Failed/warn state, never a green light). `liveAppName` is
    * null today — no stored state carries an app display name (the SDK
    * registration result exposes none); the key exists so the contract is
    * stable when a source appears. Identifiers only: no secret ever enters
    * this payload. */
  def connectionJson(root: os.Path, plugin: Option[FeishuBridgePlugin]): Json =
    val stored = storedAppId(root)
    val liveId = plugin.flatMap(_.liveAppId)
    Json.obj(
      "channel" -> Name.asJson,
      "enabled" -> SocialChannels.isEnabled(root, Name).asJson,
      "adapterRegistered" -> plugin.isDefined.asJson,
      "connected" -> plugin.exists(_.connected).asJson,
      "liveAppId" -> liveId.asJson,
      "liveAppIdFp" -> liveId.map(FeishuCredentials.fingerprint).asJson,
      "liveAppName" -> Json.Null,
      "storedAppId" -> stored.asJson,
      "storedAppIdFp" -> stored.map(FeishuCredentials.fingerprint).asJson,
      "fingerprintMatch" -> fingerprintVerdict(stored, liveId).asJson
    )

  /** One chat→session binding for the C3 face. */
  final case class Binding(sessionId: String, sessionName: String, chatId: String,
      source: String, boundAt: Option[Long])

  /** The chat→session bindings (C3), read off the session metas: one entry per
    * session carrying a feishu bridge config with a chat_id. `source` is
    * `auto` only when the binding says so ([[FeishuBridgePlugin.autoBind]]
    * writes it); everything else — REST bind-route writes, pre-auto-bind
    * bindings, hand-edited configs — reads `manual`. */
  def bindingsOf(metas: List[SessionMeta]): List[Binding] =
    metas.flatMap { s =>
      s.bridges.get(Name).flatMap { b =>
        b.hcursor.downField("chat_id").as[String].toOption.map(_.trim).filter(_.nonEmpty).map { chatId =>
          val source = b.hcursor.downField("source").as[String].toOption.map(_.trim)
            .filter(_ == "auto").getOrElse("manual")
          Binding(s.id, s.name, chatId, source, b.hcursor.downField("boundAt").as[Long].toOption)
        }
      }
    }

  /** The C3 payload (GET /api/social/channels/feishu/bindings). */
  def bindingsJson(metas: List[SessionMeta]): Json =
    Json.obj("bindings" -> bindingsOf(metas).map { b =>
      Json.obj(
        "sessionId" -> b.sessionId.asJson,
        "sessionName" -> b.sessionName.asJson,
        "chatId" -> b.chatId.asJson,
        "source" -> b.source.asJson,
        "boundAt" -> b.boundAt.asJson
      )
    }.asJson)

end FeishuBridgePlugin
