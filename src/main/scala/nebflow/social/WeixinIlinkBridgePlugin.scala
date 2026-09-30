package nebflow.social

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.bridge.{BridgeContext, BridgeManager, BridgePlugin}
import nebflow.shared.NebflowLogger

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Weixin iLink bridge adapter — the side-car seam (wechat iLink batch).
 *
 * The iLink protocol is deliberately NOT implemented here: long polling
 * (`getUpdates`), the auth headers, AES-128-ECB media decryption, the update
 * cursor and the `-14` backoff all belong to the official ClawBot plugin
 * (`@tencent-weixin/openclaw-weixin`), which runs attached to a host. This class
 * is the seam facing that side-car process: the official plugin keeps running
 * as it is, and this repo only grows the seam.
 *
 * Three things live here, and nothing else:
 *
 *   - inbound: a side-car hands over one inbound message through [[deliver]]
 *     (HTTP face `POST /api/social/channels/weixin-ilink/ingress`) → the sender
 *     gate (`allowed_ilink_user_ids`, fail-closed) → the session bound to that
 *     sender (`SessionMeta.bridges` key `weixin-ilink`, field `ilink_user_id`),
 *     else the channel's configured default session → `ctx.injectMessage`, the
 *     existing rate-limited `handleBridgeMessage` path;
 *   - outbound: nothing. Sending back into Weixin belongs to the host that owns
 *     the plugin session; this seam only injects (the README section states the
 *     boundary);
 *   - lifecycle: `start` / `stop` / `refreshRoutes` plus the [[WeixinIlinkBridgePlugin.sync]]
 *     companion — the same activation shape [[FeishuBridgePlugin]] uses.
 *
 * 🔴 Sender gate — the one piece of new logic, fail-closed: an empty
 * `allowed_ilink_user_ids` means unrestricted (the shipped default); once the
 * field is filled, only senders on the list pass, and an UNRESOLVABLE sender
 * identity counts as a non-member. That arm is ordered FIRST (the
 * [[FeishuBridgePlugin.intake]] ordering) so it rules the default-session arm
 * too. The official plugin v2.4.9 registers no pairing adapter, so this gate is
 * the admission control on the Nebflow side.
 *
 * 🔴 Crash isolation: a peer-side `-14` (the official monitor pauses that
 * account session for one hour) never lands here as state — the seam keeps no
 * cursor, opens no socket and polls nothing, so a paused peer merely sends
 * nothing while every other Nebflow leg keeps running. The only peer material
 * this class touches is one message at a time, and every failure on that path is
 * contained into a [[WeixinIlinkBridgePlugin.Verdict]] plus one log line: never
 * an exception, never a torn-down bridge.
 *
 * 🔴 Zero credentials: the card keeps `bot_token` behind its `_ref` path and the
 * official plugin keeps its own token under the host state directory. This class
 * reads no credential at all (only the allowlist string) and logs no value.
 */
final class WeixinIlinkBridgePlugin(
  root: os.Path,
  /** Test seam for the gate: None = read the stored card at start (production),
    * Some(list) = pinned (offline spec). */
  pinnedAllowedIlinkUserIds: Option[List[String]] = None
) extends BridgePlugin:

  import WeixinIlinkBridgePlugin.*

  private val logger = NebflowLogger.forName("nebflow.social.weixin-ilink-bridge")

  def name: String = Name

  // ── runtime state (mutation is confined to start/stop and the Refs) ──
  /** sender id → session id, rebuilt from `SessionMeta.bridges`. */
  private[social] val routesRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)
  /** The allowlist in force; empty = unrestricted (the shipped default). The
    * pinned value is the INITIAL state so the gate is armed from construction;
    * `start` re-reads the stored card over it. */
  private[social] val allowedRef =
    cats.effect.Ref.unsafe[IO, List[String]](pinnedAllowedIlinkUserIds.getOrElse(Nil))
  private val ctxRef = cats.effect.Ref.unsafe[IO, Option[BridgeContext]](None)
  /** Double-start guard: boot-time sync AND `startAll` both call start — the
    * second call must be a no-op. */
  private val starting = new AtomicBoolean(false)
  @volatile private var started = false

  // ───────────────────────────── lifecycle ─────────────────────────────
  def start(ctx: BridgeContext): IO[Unit] =
    if !starting.compareAndSet(false, true) then IO.unit
    else
      val body =
        for
          _ <- ctxRef.set(Some(ctx))
          cfg <- IO.blocking(readChannelConfig(root))
          _ <- allowedRef.set(pinnedAllowedIlinkUserIds.getOrElse(cfg.allowedIlinkUserIds))
          _ <- rebuildRoutes(ctx)
          _ <- IO { started = true }
          gate <- allowedRef.get
          _ <- logger.info(
            s"weixin-ilink bridge: seam up — routing table rebuilt, sender gate " +
              (if gate.isEmpty then "unrestricted (allowed_ilink_user_ids is empty)"
               else s"fail-closed (${gate.size} allowed sender id(s))")
          )
        yield ()
      body.handleErrorWith(e =>
        logger.error(s"weixin-ilink bridge: start failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
      ) *> IO(if !started then starting.set(false)) // failed start ⇒ a later sync may retry

  /** Idempotent teardown — the single exit path; safe to call twice. */
  val stop: IO[Unit] =
    IO {
      started = false
      starting.set(false)
    }

  /** Whether the seam is up. There is no connection to report: the seam holds no
    * socket, so "up" means it is registered and the gate is armed. */
  def connected: Boolean = started

  // ───────────────────────────── inbound ─────────────────────────────
  /** The HTTP ingest leg: inject one side-car message using the context captured
    * at `start`. Errors are contained ([[intake]] is total), so the route can
    * answer with the verdict instead of failing the request. */
  def deliver(in: Inbound): IO[Verdict] =
    ctxRef.get.flatMap {
      case Some(ctx) => intake(ctx, in)
      case None      => IO.pure(Verdict.Dropped(ReasonNotStarted))
    }

  /** The gate + routing + injection leg. TOTAL by construction: every arm
    * answers a [[Verdict]] and a raising `injectMessage` is contained into
    * `Dropped(inject-failed)` — one inbound message can never take the seam (or
    * any other Nebflow leg) down with it. */
  private[social] def intake(ctx: BridgeContext, in: Inbound): IO[Verdict] =
    val body =
      for
        allowed <- allowedRef.get
        routes <- routesRef.get
        verdict <- (allowed.isEmpty || in.senderId.exists(allowed.contains), routes.get(in.routeKey), in.text) match
          case (false, _, _) =>
            // Fail-closed when the list is non-empty: an absent/unresolvable
            // sender identity is as good as a non-member. Ordered FIRST so the
            // gate also rules the default-session arm below.
            logger.warn(
              s"weixin-ilink bridge: inbound message${in.messageId.fold("")(id => s" $id")} dropped " +
                s"[$ReasonNotOnAllowlist] — sender identity is " +
                s"${if in.senderId.isEmpty then "unresolvable" else "not"} on the allowed_ilink_user_ids list"
            ) *> IO.pure(Verdict.Dropped(ReasonNotOnAllowlist))
          case (_, None, _) =>
            // No explicit binding for this sender: fall back to the channel's
            // default session when one is configured (cold read — a REST save can
            // flip it while the seam runs, and this path is once per new sender).
            IO.blocking(SocialChannels.defaultSessionId(root, Name)).flatMap {
              case Some(sessionId) =>
                in.text match
                  case Some(text) => inject(ctx, in, sessionId)
                  case None       => IO.pure(Verdict.Dropped(ReasonNoText))
              case None =>
                logger.info(
                  s"weixin-ilink bridge: dropped [$ReasonNoSession] — no session is bound to sender " +
                    s"${in.routeKey} and no default session is configured"
                ) *> IO.pure(Verdict.Dropped(ReasonNoSession))
            }
          case (_, _, None) =>
            logger.info(
              s"weixin-ilink bridge: dropped [$ReasonNoText] — inbound message" +
                s"${in.messageId.fold("")(id => s" $id")} has no text body, nothing injected"
            ) *> IO.pure(Verdict.Dropped(ReasonNoText))
          case (_, Some(sessionId), Some(text)) =>
            inject(ctx, in, sessionId)
      yield verdict
    body.handleErrorWith(e =>
      logger.warn(
        s"weixin-ilink bridge: dropped [$ReasonInjectFailed] — inbound injection failed " +
          s"(${e.getClass.getSimpleName}: ${e.getMessage}), contained, the seam stays up"
      ) *> IO.pure(Verdict.Dropped(ReasonInjectFailed))
    )

  private def inject(ctx: BridgeContext, in: Inbound, sessionId: String): IO[Verdict] =
    logger.info(s"weixin-ilink bridge: sender ${in.routeKey} -> session $sessionId") *>
      ctx.injectMessage(sessionId, in.text.getOrElse(""), in.senderId).as(Verdict.Injected(sessionId))

  // ───────────────────────────── routing ─────────────────────────────
  override def refreshRoutes: IO[Unit] =
    ctxRef.get.flatMap {
      case Some(ctx) => rebuildRoutes(ctx)
      case None      => IO.unit
    }

  private[social] def rebuildRoutes(ctx: BridgeContext): IO[Unit] =
    ctx.listSessions.flatMap { metas =>
      val pairs = metas.flatMap { s =>
        s.bridges.get(Name)
          .flatMap(b => b.hcursor.downField(RouteKey).as[String].toOption.map(_.trim).filter(_.nonEmpty))
          .map(_ -> s.id)
      }
      val bound = pairs.toMap // duplicate sender ids: last session wins
      val dupes = pairs.groupBy(_._1).count((_, v) => v.sizeIs > 1)
      val warn =
        if dupes > 0 then
          logger.warn(s"weixin-ilink bridge: $dupes sender id(s) bound to more than one session — the last binding wins")
        else IO.unit
      warn *> routesRef.set(bound) *>
        logger.info(s"weixin-ilink bridge: routing table rebuilt — ${bound.size} sender id(s) bound to sessions")
    }

  // ───────────────────────────── outbound ─────────────────────────────
  /** Nothing to do: replies to Weixin are owned by the host that runs the
    * official plugin (this seam implements the inbound leg only). Kept as an
    * explicit no-op so the boundary is stated in code rather than implied. */
  def onAgentEvent(sessionId: String, event: Json): IO[Unit] = IO.unit

end WeixinIlinkBridgePlugin

object WeixinIlinkBridgePlugin:

  /** BridgePlugin.name — also the platform key in `SessionMeta.bridges`. */
  val Name = "weixin-ilink"

  /** The bridge-config field carrying the sender id in a binding. */
  val RouteKey = "ilink_user_id"

  /** Companion-side logger (the sync leg lives here, outside any instance). */
  private val log = NebflowLogger.forName("nebflow.social.weixin-ilink-bridge")

  /** One inbound message handed over by the side-car. */
  final case class Inbound(senderId: Option[String], text: Option[String], messageId: Option[String] = None):
    /** The routing key: the sender id, empty when unresolvable. */
    def routeKey: String = senderId.map(_.trim).filter(_.nonEmpty).getOrElse("")

  /** The observable outcome of one ingest — the reading the side-car, the logs
    * and the offline spec all see. */
  enum Verdict:
    case Injected(sessionId: String)
    case Dropped(reason: String)

    def json: Json = this match
      case Injected(sessionId) => Json.obj("verdict" -> "injected".asJson, "sessionId" -> sessionId.asJson)
      case Dropped(reason)     => Json.obj("verdict" -> "dropped".asJson, "reason" -> reason.asJson)

  // Drop reasons — stable strings, safe to read from a log or a response body.
  val ReasonNotOnAllowlist = "not-on-allowlist"
  val ReasonNoSession = "no-session-bound"
  val ReasonNoText = "no-text-body"
  val ReasonInjectFailed = "inject-failed"
  val ReasonNotStarted = "adapter-not-started"
  val ReasonMalformed = "malformed-body"

  /**
   * The ingest body → [[Inbound]]. `senderId` is the sender identity (the
   * `from_user_id` of the iLink message; that spelling is accepted as an alias
   * because it is the field name on the side-car side). A body without the key
   * is NOT an error: an unresolvable sender is exactly the case the gate must
   * rule on, so it is carried through as `None`.
   */
  def parseInbound(body: Json): Either[String, Inbound] =
    if !body.isObject then Left("request body must be a JSON object")
    else
      val c = body.hcursor
      def str(keys: String*): Option[String] =
        keys.toList.flatMap(k => c.downField(k).as[String].toOption).headOption.map(_.trim).filter(_.nonEmpty)
      Right(Inbound(
        senderId = str("senderId", "from_user_id"),
        text = c.downField("text").as[String].toOption,
        messageId = str("messageId", "message_id")
      ))

  /** The card's sender gate: `allowed_ilink_user_ids`, comma/space separated,
    * empty = unrestricted (the shipped default). */
  final case class ChannelConfig(allowedIlinkUserIds: List[String])

  def readChannelConfig(root: os.Path): ChannelConfig =
    val fields = SocialChannels.readChannels(root).hcursor
      .downField("channels").downField(Name).downField("fields").focus.getOrElse(Json.obj())
    val allowed = fields.hcursor.downField("allowed_ilink_user_ids").as[String].toOption
      .getOrElse("").split("[,;\\s]+").map(_.trim).filter(_.nonEmpty).toList
    ChannelConfig(allowed)

  /**
   * The activation point of the closed loop `enabled ∧ verified ⇒ the seam is
   * registered (and started); otherwise ⇒ it is not` — the same shape as
   * [[FeishuBridgePlugin.sync]]. Boot (`GatewayMain`) and the channel save
   * endpoint (`PresenceRoutes`) both route through here, so the runtime state
   * can never be assembled two different ways: a save that fills
   * `allowed_ilink_user_ids` re-arms the gate without a restart. `factory` is
   * the offline spec's seam (a fake instead of a real plugin).
   */
  def sync(manager: BridgeManager, root: os.Path,
      factory: os.Path => BridgePlugin = (r => new WeixinIlinkBridgePlugin(r))): IO[Unit] =
    val want = IO.blocking(SocialChannels.isEnabled(root, Name) && SocialChannels.verified(root, Name))
    want.flatMap {
      case true =>
        val plugin = factory(root)
        manager.unregister(Name) *> manager.register(plugin) *> manager.startOne(Name)
      case false =>
        manager.unregister(Name)
    }

end WeixinIlinkBridgePlugin
