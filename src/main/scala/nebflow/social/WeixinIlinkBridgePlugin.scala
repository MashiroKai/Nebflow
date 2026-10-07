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
 *     else the channel's configured default session — which an unbound
 *     RESOLVABLE sender is then AUTO-BOUND to (weixin-autobind batch,
 *     2026-10-07, the feishu C4 form: the binding written IS the session's
 *     return address, `source=auto` + `boundAt`) → `ctx.injectMessage`, the
 *     existing rate-limited `handleBridgeMessage` path;
 *   - outbound (weixin-outbound batch, 2026-10-07): accumulated `textDelta`
 *     flushes as ONE text reply on `done`, through the injected send seam —
 *     the authenticated side-car `POST /send` face ([[WeixinOutboundAdapter]]),
 *     from where the side-car hands the message to the iLink wire. The reply
 *     target is the reverse read of the SAME routing table the inbound leg
 *     builds (one binding source); a session with no binding has no return
 *     address and its flush says so (WARN). Send failures are contained (one
 *     WARN line, no retry, no queue — design §0.4);
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
 * 🔴 Credentials: the card keeps `bot_token` behind its `_ref` path and the
 * official plugin keeps its own token under the host state directory; this
 * class READS no credential at all (only the allowlist string) and logs no
 * value. weixin-inbound batch (2026-10-07): `start` additionally MINTS the
 * shim's ingress token ([[WeixinIngressToken]] — a generated secret at
 * `<root>/secrets/`, never a read of any stored credential), the track-2
 * credential of the ingest route's route-local gate (report §1).
 */
final class WeixinIlinkBridgePlugin(
  root: os.Path,
  /** Test seam for the gate: None = read the stored card at start (production),
    * Some(list) = pinned (offline spec). */
  pinnedAllowedIlinkUserIds: Option[List[String]] = None,
  /** Outbound send seam factory (weixin-outbound batch, 2026-10-07). Production
    * = [[WeixinOutboundAdapter.sendLive]] (the authenticated side-car `POST
    * /send` face); the offline spec injects a recorder so no test opens a
    * socket. Takes the root so the production leg cold-reads the stored card
    * and the shared token per send. */
  send: (os.Path => WeixinIlinkBridgePlugin.Send) = WeixinOutboundAdapter.sendLive
) extends BridgePlugin:

  import WeixinIlinkBridgePlugin.*

  private val logger = NebflowLogger.forName("nebflow.social.weixin-ilink-bridge")

  /** The outbound send leg resolved for THIS root (the injected factory, or
    * the production side-car face). */
  private val outbound: Send = send(root)

  def name: String = Name

  // ── runtime state (mutation is confined to start/stop and the Refs) ──
  /** sender id → session id, rebuilt from `SessionMeta.bridges`. */
  private[social] val routesRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)
  /** session id → accumulated delta text for the turn in flight (the outbound
    * reply buffer — the same shape [[FeishuBridgePlugin]] flushes on `done`).
    * Cleared on stop/interrupt/retry: a torn turn is never a reply. */
  private[social] val pendingRef =
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
          // weixin-outbound batch: the SHARED TOKEN is minted lazily HERE (the
          // enabled∧verified start path — design §0.2: a channel-less install
          // never gains a secret file). Reuse on an existing mint, never
          // rotate. A mint failure closes only the OUTBOUND leg (the send face
          // answers shim_token_unconfigured); the inbound seam stays up.
          _ <- WeixinOutboundAdapter.ensureToken(root).flatMap {
            case Right(_) => IO.unit // minted or reused — the value is never logged
            case Left(reason) =>
              logger.warn(s"weixin-ilink bridge: outbound leg stays closed — could not ensure the shim token: $reason")
          }
          _ <- IO { started = true }
          // weixin-inbound batch (2026-10-07): mint (or keep) the shim's ingress
          // token at seam-up — the credential plane of the ingest route's track-2
          // ([[WeixinIngressToken]]; design = batch report §1). Failure is logged
          // and NOT fatal: the seam itself is passive and the admin track still
          // serves the route; a missing token only means the shim's POSTs stay
          // 403 until a later seam-up mints one.
          _ <- WeixinIngressToken.ensure(root).handleErrorWith(e =>
            logger.error(
              s"weixin-ilink bridge: ingress token mint failed (${e.getClass.getSimpleName}: ${e.getMessage}) — " +
                "shim ingress stays 403 until a later seam-up succeeds"
            ))
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

  /** Idempotent teardown — the single exit path; safe to call twice. A torn
    * turn is never a reply, also across restarts (the feishu rule). */
  val stop: IO[Unit] =
    IO {
      started = false
      starting.set(false)
    } *> pendingRef.set(Map.empty)

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
                if in.routeKey.isEmpty then
                  // Unresolvable sender: nothing to BIND — a binding on the
                  // empty key would pin every anonymous sender onto one
                  // session. The pre-existing fallback (inject via the default
                  // session, nothing persisted) is kept byte-identical.
                  in.text match
                    case Some(text) => inject(ctx, in, sessionId)
                    case None       => IO.pure(Verdict.Dropped(ReasonNoText))
                else autoBind(ctx, in, sessionId)
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

  /** Auto-bind (weixin-autobind batch, 2026-10-07) — the feishu C4 form
    * ([[FeishuBridgePlugin.autoBind]]) for the weixin seam: the first message
    * from a not-yet-bound sender binds that sender to the default session and
    * injects the message there — zero commands. 🔴 The binding IS the return
    * address: it persists through [[BridgeContext.updateBridgeConfig]]
    * (`SessionMeta.bridges`, key `weixin-ilink`) carrying `source=auto` +
    * `boundAt` — the same JSON the outbound [[reply]] leg reverse-reads — and
    * the local routes table is updated in the same step, so the turn in flight
    * can already be replied to. Before this leg a fallback-served sender was
    * structurally un-answerable: the message was injected but no
    * `ilink_user_id` ever landed on the session, so every later flush died on
    * [[ReasonNoReturnAddress]]. No default session configured ⇒ the
    * pre-existing behaviour, unchanged: drop + log (that arm stays in
    * [[intake]]). A no-text (media) message binds too — the address must exist
    * for the NEXT message even when this one carries nothing injectable. */
  private def autoBind(ctx: BridgeContext, in: Inbound, sessionId: String): IO[Verdict] =
    val cfg = Json.obj(
      RouteKey -> in.routeKey.asJson,
      "source" -> "auto".asJson,
      "boundAt" -> System.currentTimeMillis().asJson
    )
    ctx.updateBridgeConfig(sessionId, Name, Some(cfg)) *>
      routesRef.update(_ + (in.routeKey -> sessionId)) *>
      logger.info(s"weixin-ilink bridge: sender ${in.routeKey} auto-bound to default session $sessionId") *>
      (in.text match
        case Some(text) => inject(ctx, in, sessionId)
        case None       => IO.pure(Verdict.Dropped(ReasonNoText)))

  private def inject(ctx: BridgeContext, in: Inbound, sessionId: String): IO[Verdict] =
    logger.info(s"weixin-ilink bridge: sender ${in.routeKey} -> session $sessionId") *>
      ctx.injectMessage(sessionId, in.text.getOrElse(""), in.senderId, originOf(in)).as(Verdict.Injected(sessionId))

  /** The channel-agnostic origin descriptor this bridge contributes. The channel
    * id is the bridge's own stable name and the display name is this channel's
    * human label — supplied by the channel itself, never copied from another
    * channel's label. The iLink protocol has no conversation reference of its
    * own (the routing key is the sender id), so `chatRef` is `None` and the
    * marker renders the neutral `-`. Pure data — the template lives on the
    * channel-agnostic side ([[nebflow.bridge.BridgeOrigin]]). */
  private def originOf(in: Inbound): Option[nebflow.bridge.BridgeOrigin] =
    Some(nebflow.bridge.BridgeOrigin(
      channelId = WeixinIlinkBridgePlugin.Name,
      channelDisplay = WeixinIlinkBridgePlugin.ChannelDisplay))

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
  /**
   * The outbound reply leg (weixin-outbound batch, 2026-10-07) — the same
   * shape [[FeishuBridgePlugin.onAgentEvent]] ships: `textDelta` events
   * accumulate per session and flush as ONE text message on `done` (a chat
   * reply is a message, not a delta stream); `interrupted` / `retryStatus`
   * clear the buffer (a torn turn is never a reply); the flush is contained —
   * a send failure is one WARN line, never a raise, and is NOT retried here
   * (design §0.4: an ambiguous timeout may already be a delivered message).
   *
   * `toolEnd` is deliberately a no-op arm: rich products (cards/artifacts)
   * are a feishu-leg concept; the weixin outbound v1 carries text only.
   *
   * 🔴 The reply target is the REVERSE read of the same routing table the
   * inbound leg builds (contract ③: one binding source): session → the bound
   * `ilink_user_id`. A session with NO binding has no return address — the
   * flush logs a WARN and drops (the inbound default-session fallback cannot
   * be answered to because no sender id is on file for it; the structural fix
   * would be an inbound auto-bind, which belongs to the inbound batch).
   *
   * 🔴 Zero-secret: the send seam carries the shared shim token in its
   * Authorization header only ([[WeixinOutboundAdapter]]); the bot_token never
   * reaches this class. The outbound copy is de-marked-up through
   * [[nebflow.social.outbound.OutboundText.toPlain]] (author ruling #299, the
   * feishu reply treatment); the in-session original is never rewritten.
   */
  def onAgentEvent(sessionId: String, event: Json): IO[Unit] =
    val c = event.hcursor
    c.downField("type").as[String].getOrElse("") match
      case "textDelta" =>
        c.downField("delta").as[String].getOrElse("") match
          case ""    => IO.unit
          case delta => pendingRef.update(m => m.updated(sessionId, m.getOrElse(sessionId, "") + delta))
      case "toolEnd" => IO.unit // text-only v1: rich products are not carried (stated boundary)
      case "done" =>
        pendingRef.modify { m => (m - sessionId, m.getOrElse(sessionId, "")) }.flatMap { text =>
          if text.trim.isEmpty then IO.unit else reply(sessionId, text)
        }
      // A retried or interrupted turn re-streams its text; the partial buffer
      // must never flush as a duplicated/garbled reply.
      case "interrupted" | "retryStatus" =>
        pendingRef.update(_ - sessionId)
      case _ => IO.unit

  private[social] def reply(sessionId: String, text: String): IO[Unit] =
    routesRef.get.flatMap { routes =>
      routes.collectFirst { case (sender, sid) if sid == sessionId => sender } match
        case None =>
          IO(logger.warn(
            s"weixin-ilink bridge: reply for session $sessionId dropped [$ReasonNoReturnAddress] — " +
              s"no sender id is bound to this session, so the reply has no iLink target"))
        case Some(sender) =>
          val outboundText = nebflow.social.outbound.OutboundText.toPlain(text)
          outbound(sender, outboundText).flatMap { res =>
            if res.ok then
              IO(logger.info(
                s"weixin-ilink bridge: reply delivered to sender $sender (id=${res.messageId.getOrElse("-")})"))
            else
              IO(logger.warn(
                s"weixin-ilink bridge: reply send failed code=${res.code} reason=${res.reason}"))
          }
    }

end WeixinIlinkBridgePlugin

object WeixinIlinkBridgePlugin:

  /** BridgePlugin.name — also the platform key in `SessionMeta.bridges`. */
  val Name = "weixin-ilink"

  /** The bridge-config field carrying the sender id in a binding. */
  val RouteKey = "ilink_user_id"

  /** The outbound send seam: `(to, text) → reading` (weixin-outbound batch).
    * The reading is [[WeixinOutboundAdapter.SendResult]] — one contained
    * outcome per flush, never a raise. */
  type Send = (String, String) => IO[WeixinOutboundAdapter.SendResult]

  /** The outbound drop reason for a session with no bound sender: stable
    * string, safe to reconcile from a log line. */
  val ReasonNoReturnAddress = "no-return-address"

  /** The human-readable label this channel announces in the source-marker block
    * (soc483 batch). Same value as the card's own name copy
    * (`social.weixinIlink.name`) — the label belongs to THIS channel; it is
    * never copied from another channel's label. Data only: the marker template
    * lives on the channel-agnostic side ([[nebflow.bridge.BridgeOrigin.marker]]). */
  val ChannelDisplay = "微信（iLink）"

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
