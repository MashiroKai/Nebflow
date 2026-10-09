package nebflow.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.bridge.{BridgeContext, BridgeManager, BridgePlugin}
import nebflow.shared.NebflowLogger
import nebflow.social.outbound.{AskPromptCodec, PromptAction, PromptView, RichKind, RichProduct, WebPageShot}

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

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
 *     WARN line, no retry, no queue — design §0.4). weixin-media batch
 *     (2026-10-08): `toolEnd` additionally remembers the turn's rich product
 *     (a Pop payload's image/file item) and `done` flushes it ADDITIVELY
 *     through the media seam (`image_path` / `file_path`), degrading to a
 *     one-line text notice on failure — the text reply is never suppressed;
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
  /**
   * Test seam for the gate: None = read the stored card at start (production),
   * Some(list) = pinned (offline spec).
   */
  pinnedAllowedIlinkUserIds: Option[List[String]] = None,
  /**
   * Outbound send seam factory (weixin-outbound batch, 2026-10-07). Production
   * = [[WeixinOutboundAdapter.sendLive]] (the authenticated side-car `POST
   * /send` face); the offline spec injects a recorder so no test opens a
   * socket. Takes the root so the production leg cold-reads the stored card
   * and the shared token per send.
   */
  send: (os.Path => WeixinIlinkBridgePlugin.Send) = WeixinOutboundAdapter.sendLive,
  /**
   * Outbound MEDIA send seam factory (weixin-media batch, 2026-10-08).
   * Production = [[WeixinOutboundAdapter.sendMediaLive]] (the same side-car
   * face, the body carrying `image_path` / `file_path`); the offline spec
   * injects a recorder. Same root-passing shape as [[send]].
   */
  sendMedia: (os.Path => WeixinIlinkBridgePlugin.SendMedia) = WeixinOutboundAdapter.sendMediaLive,
  /**
   * The DOCUMENT extension authority for the rich classification — the
   * single-authority rule ([[RichKind]]'s file header): the table is NEVER
   * copied into this channel; the default surfaces the one repo table the
   * feishu bridge already surfaces ([[FeishuOutboundAdapter.defaultTable]]),
   * and the wiring layer may pin another.
   */
  documentExtensions: Set[String] = FeishuOutboundAdapter.defaultTable,
  /**
   * 网页类产物（html/htm）的整页截图缝（social-webshot batch, 2026-10-09）：
   * 同日用户裁定修正——html/htm 原件先走既有 file_path 文件腿直发（不再自动
   * 截图），本缝降为兜底：仅当文件腿发送失败（渠道拒绝/超时）才截成整页 PNG
   * 走既有 image_path 媒体腿。生产 = [[WebPageShot.NodePlaywright]]；
   * 离线 spec 注入 fake / [[WebPageShot.Unavailable]]（脚本缺失/超时 ⇒ v1 边界：
   * 日志一行、正文腿带回合）。
   */
  webshot: WebPageShot.Renderer = WebPageShot.NodePlaywright()
) extends BridgePlugin:

  import WeixinIlinkBridgePlugin.*

  private val logger = NebflowLogger.forName("nebflow.social.weixin-ilink-bridge")

  /**
   * The outbound send leg resolved for THIS root (the injected factory, or
   * the production side-car face).
   */
  private val outbound: Send = send(root)

  /** The outbound MEDIA leg resolved for THIS root (weixin-media batch). */
  private val outboundMedia: SendMedia = sendMedia(root)

  def name: String = Name

  // ── runtime state (mutation is confined to start/stop and the Refs) ──
  /** sender id → session id, rebuilt from `SessionMeta.bridges`. */
  private[social] val routesRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)

  /**
   * session id → accumulated delta text for the turn in flight (the outbound
   * reply buffer — the same shape [[FeishuBridgePlugin]] flushes on `done`).
   * Cleared on stop/interrupt/retry: a torn turn is never a reply.
   */
  private[social] val pendingRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)

  /**
   * session id → EVERY rich product observed in the turn in flight, in toolEnd
   * order (weixin-media batch, 2026-10-08; accumulated-list form from the pop
   * multi-group batch, 2026-10-09): the same per-session remember/clear shape
   * [[FeishuBridgePlugin]] ships for its rich leg. Only `toolEnd` carries tool
   * `content`, so the decision has to be remembered between the two root
   * events; cleared exactly like the buffer (`done` reads + removes,
   * `interrupted`/`retryStatus`/`stop` drop — a torn turn is never a reply,
   * also never a stale media).
   *
   * 🔴 Why a LIST (pop multi-group fix): the previous single-slot memory held
   * only the LAST product, so a turn that popped a multi-image group and then
   * any further Pop lost every earlier group's artifacts on this leg (incident
   * 2026-10-09 22:2x: the four-image group never attempted, only the later
   * single image did). Each Pop call is one 组卡 and every one of them is
   * delivered, in the order the tool results arrived.
   */
  private[social] val richRef =
    cats.effect.Ref.unsafe[IO, Map[String, List[RichProduct]]](Map.empty)

  /**
   * 逐段即时推送批（2026-10-10）：段级推送的节流队列——与飞书腿同一语义面
   * （[[nebflow.social.outbound.SegmentPacer]]）：桌面端一个文本段提交
   * （`toolStart` | `roundComplete` | `done` 边界）即推一条独立消息，段间
   * ≥1s 按序出网；done 腿 await 自己的排队任务 ⇒ 先于它的段必然全部出网
   * （轮终不重发的结构保证）。既有频控处理不变：ret=-2 退避在 side-car 侧，
   * 失败 WARN 语义原样（见 [[noteSegmentFailure]] 的注明腿）。
   */
  private[social] val pacer = nebflow.social.outbound.SegmentPacer.unsafe()

  /**
   * 逐段即时推送批：失败段的「注明」闸（sessionId 已注明 ⇒ true）。连续失败
   * 只注明一次，任一次发送成功即复位。轮终尾段的失败保持既有语义（仅 WARN）。
   */
  private[social] val segmentFailNoted =
    cats.effect.Ref.unsafe[IO, Set[String]](Set.empty)

  /**
   * The allowlist in force; empty = unrestricted (the shipped default). The
   * pinned value is the INITIAL state so the gate is armed from construction;
   * `start` re-reads the stored card over it.
   */
  private[social] val allowedRef =
    cats.effect.Ref.unsafe[IO, List[String]](pinnedAllowedIlinkUserIds.getOrElse(Nil))

  /**
   * askuser-remote P1: the per-sender ask ledger (B-3 接缝面, research 卡 B) —
   * ONE active ask slot per sender (routeKey → entry). The hub stays the ask
   * AUTHORITY (the answer is forwarded, never consumed here); this ledger
   * exists because the WEIXIN channel needs the per-sender mapping the hub
   * does not know (which sender is being asked) and the B-1 numbering
   * judgement (a reply's numbers mean the ACTIVE set). A successfully
   * answered entry stays as an ANSWERED tombstone so a late repeat read of
   * the same numbers is refused (「该问题已作答」, never a second injection);
   * askUserClosed removes the entry; a new ask displaces the old one
   * (CloseRequest — B-3 新问顶旧问). Cleared on stop: a torn ask is never
   * answered across a restart.
   */
  private[social] val askLedgerRef =
    cats.effect.Ref.unsafe[IO, Map[String, AskEntry]](Map.empty)

  /**
   * askuser-remote P1 (B-4 案甲): the per-sender full-text pull slot — the
   * text a 「全文」 reply will receive, segmented on demand. One slot per
   * sender; a newer long text displaces the older (latest wins). Cleared on
   * stop.
   */
  private[social] val pullRef =
    cats.effect.Ref.unsafe[IO, Map[String, PullEntry]](Map.empty)
  private val ctxRef = cats.effect.Ref.unsafe[IO, Option[BridgeContext]](None)

  /**
   * Double-start guard: boot-time sync AND `startAll` both call start — the
   * second call must be a no-op.
   */
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
          _ <- WeixinIngressToken
            .ensure(root)
            .handleErrorWith(e =>
              logger.error(
                s"weixin-ilink bridge: ingress token mint failed (${e.getClass.getSimpleName}: ${e.getMessage}) — " +
                  "shim ingress stays 403 until a later seam-up succeeds"
              )
            )
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

  /**
   * Idempotent teardown — the single exit path; safe to call twice. A torn
   * turn is never a reply, also across restarts (the feishu rule); neither is
   * a torn ask (askuser-remote P1) nor a stale full-text slot.
   */
  val stop: IO[Unit] =
    IO {
      started = false
      starting.set(false)
    } *> pendingRef.set(Map.empty)
      *> richRef.set(Map.empty)
      *> askLedgerRef.set(Map.empty)
      *> pullRef.set(Map.empty)

  /**
   * Whether the seam is up. There is no connection to report: the seam holds no
   * socket, so "up" means it is registered and the gate is armed.
   */
  def connected: Boolean = started

  // ───────────────────────────── inbound ─────────────────────────────
  /**
   * The HTTP ingest leg: inject one side-car message using the context captured
   * at `start`. Errors are contained ([[intake]] is total), so the route can
   * answer with the verdict instead of failing the request.
   */
  def deliver(in: Inbound): IO[Verdict] =
    ctxRef.get.flatMap {
      case Some(ctx) => intake(ctx, in)
      case None => IO.pure(Verdict.Dropped(ReasonNotStarted))
    }

  /**
   * The gate + routing + injection leg. TOTAL by construction: every arm
   * answers a [[Verdict]] and a raising `injectMessage` is contained into
   * `Dropped(inject-failed)` — one inbound message can never take the seam (or
   * any other Nebflow leg) down with it.
   */
  private[social] def intake(ctx: BridgeContext, in: Inbound): IO[Verdict] =
    val body =
      for
        allowed <- allowedRef.get
        routes <- routesRef.get
        gateOpen = allowed.isEmpty || in.senderId.exists(allowed.contains)
        // askuser-remote P1: the ask-answer / full-text pull legs sit BETWEEN
        // the allowlist gate and the routing/injection arms — the gate rules
        // them exactly as it rules the injection arm (same ordering argument
        // as the original first arm), and a message they do not claim falls
        // through to the pre-existing arms byte-for-byte unchanged.
        askLeg <- if gateOpen then askLegs(ctx, in) else IO.pure(None)
        verdict <- askLeg match
          case Some(v) => IO.pure(v)
          case None =>
            // weixin-inbound-media batch (2026-10-08): `in.mediaLanded` joins
            // the selector so the MEDIA ARM sits ahead of both text arms —
            // a landed media path injects the 「[图片] 已存至 …」 line (the
            // shim's placeholder label in `text` is replaced); without one,
            // every arm below is byte-identical to the pre-media behaviour.
            (gateOpen, routes.get(in.routeKey), in.mediaLanded, in.text) match
              case (false, _, _, _) =>
                // Fail-closed when the list is non-empty: an absent/unresolvable
                // sender identity is as good as a non-member. Ordered FIRST so the
                // gate also rules the default-session arm below.
                logger.warn(
                  s"weixin-ilink bridge: inbound message${in.messageId.fold("")(id => s" $id")} dropped " +
                    s"[$ReasonNotOnAllowlist] — sender identity is " +
                    s"${if in.senderId.isEmpty then "unresolvable" else "not"} on the allowed_ilink_user_ids list"
                ) *> IO.pure(Verdict.Dropped(ReasonNotOnAllowlist))
              case (_, None, _, _) =>
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
                      mediaArmOrText(ctx, in, sessionId)
                    else autoBind(ctx, in, sessionId)
                  case None =>
                    logger.info(
                      s"weixin-ilink bridge: dropped [$ReasonNoSession] — no session is bound to sender " +
                        s"${in.routeKey} and no default session is configured"
                    ) *> IO.pure(Verdict.Dropped(ReasonNoSession))
                }
              case (_, Some(sessionId), true, _) =>
                // The MEDIA ARM: the item landed on disk before delivery. The
                // path is checked fail-closed HERE ([[mediaArm]]) — a file that
                // died between the side-car's landing and this injection
                // degrades to the bare placeholder label, the message is still
                // delivered (the side-car never holds its cursor for media).
                mediaArm(ctx, in, sessionId)
              case (_, Some(_), false, None) =>
                logger.info(
                  s"weixin-ilink bridge: dropped [$ReasonNoText] — inbound message" +
                    s"${in.messageId.fold("")(id => s" $id")} has no text body, nothing injected"
                ) *> IO.pure(Verdict.Dropped(ReasonNoText))
              case (_, Some(sessionId), false, Some(text)) =>
                inject(ctx, in, sessionId)
      yield verdict
    body.handleErrorWith(e =>
      logger.warn(
        s"weixin-ilink bridge: dropped [$ReasonInjectFailed] — inbound injection failed " +
          s"(${e.getClass.getSimpleName}: ${e.getMessage}), contained, the seam stays up"
      ) *> IO.pure(Verdict.Dropped(ReasonInjectFailed))
    )
  end intake

  // ───────── weixin-inbound-media batch: the media arm (intake 前置臂) ─────────

  /**
   * The MEDIA ARM (weixin-inbound-media batch, 2026-10-08): a message whose
   * item landed on disk injects [[mediaLine]] through the SAME text injection
   * path ([[inject]] — the agent reads the file itself; nothing is uploaded
   * or re-fetched here). Fail-closed on the path: a file that died between
   * the side-car's landing and this injection logs a WARN and degrades to
   * the bare placeholder label — the message is STILL delivered either way
   * (the side-car's cursor is never held for a media leg, so a drop here
   * would silently lose the media; the degrade keeps it visible).
   */
  private def mediaArm(ctx: BridgeContext, in: Inbound, sessionId: String): IO[Verdict] =
    val p = in.mediaPath.map(_.trim).filter(_.nonEmpty).getOrElse("")
    mediaPathExists(p).flatMap {
      case true => inject(ctx, in.copy(text = Some(mediaLine(p, in.mediaKind, in.mediaName))), sessionId)
      case false =>
        logger.warn(
          s"weixin-ilink bridge: media${in.messageId.fold("")(id => s" $id")} path missing at injection ($p) — " +
            "fail-closed degrade to the placeholder label, still delivered"
        ) *> inject(ctx, in.copy(text = Some(mediaLabel(in.mediaKind))), sessionId)
    }

  /**
   * The unresolvable-sender tail of the default-session arm (was the inline
   * text match): a landed media path takes the media arm; plain text injects;
   * neither ⇒ the pre-existing no-text drop.
   */
  private def mediaArmOrText(ctx: BridgeContext, in: Inbound, sessionId: String): IO[Verdict] =
    if in.mediaLanded then mediaArm(ctx, in, sessionId)
    else
      in.text match
        case Some(text) => inject(ctx, in, sessionId)
        case None => IO.pure(Verdict.Dropped(ReasonNoText))

  /**
   * Existence probe for the media arm — contained and total: an unreadable
   * or malformed path counts as missing (fail-closed degrade, never a raise).
   */
  private def mediaPathExists(p: String): IO[Boolean] =
    IO.blocking(os.exists(os.Path(p, os.pwd))).handleError(_ => false)

  // ─────────── askuser-remote P1: the ask-answer / pull legs (intake 前置臂) ───────────

  /**
   * The two consumed-message legs, tried in order. `None` = this text is
   * nobody's ask reply: the caller falls through to the pre-existing arms
   * (normal chat injection, untouched).
   *
   *   ① 案甲 pull arm — the sender has a stashed full text AND the reply is
   *     exactly the pull keyword: push the segments, clear the slot.
   *   ② ask arm — the sender has an ACTIVE ask and the text parses as a
   *     numbered/explicit reply (B-1) or an exact option label (C2, case/
   *     whitespace tolerant): validate → slots → forward the SAME
   *     `Answered` command the feishu card leg forwards → tombstone. An
   *     answered tombstone repeats are refused with the S-8 line, never
   *     re-injected; range errors answer ONE readable refusal line (超界拒不
   *     钳制). Anything else — `Right(None)` from the parser, no ask on
   *     file — is chat: falls through.
   */
  private def askLegs(ctx: BridgeContext, in: Inbound): IO[Option[Verdict]] =
    val text = in.text.map(_.trim).filter(_.nonEmpty)
    val sender = Option(in.routeKey).filter(_.nonEmpty)
    (text, sender) match
      case (Some(t), Some(s)) =>
        pullRef.get.flatMap { pulls =>
          pulls.get(s).filter(p => t == WeixinAskLeg.FullTextKeyword) match
            case Some(pending) =>
              pushFullText(s, pending).as(Some(Verdict.Pulled(pending.segments)))
            case None =>
              askLedgerRef.get.flatMap { ledger =>
                ledger.get(s) match
                  case Some(entry) if entry.answered.isEmpty =>
                    // askucancel 批（2026-10-08）：取消关键字臂（先于编号解析——
                    // 「取消问题」不是编号答复）。判据 = 精确匹配 + 在册 open 问
                    // （B-3 恒单问不变量 ⇒ 无歧义）：按 entry.requestId 精确取消，
                    // 回执一行，台账随撤即清（后续 askUserClosed 广播的二次清理幂等）。
                    if t == WeixinAskLeg.CancelKeyword then cancelAsk(ctx, s, entry).map(Some(_))
                    else
                      WeixinAskLeg.parseReply(t, entry.views) match
                        case Right(Some(actions)) => answerAsk(ctx, s, entry, actions).map(Some(_))
                        case Right(None) => IO.pure(None) // chat passthrough — B-2 拍板
                        case Left(reject) =>
                          outbound(s, reject.line).flatMap { res =>
                            if res.ok then IO.unit
                            else
                              logger.warn(
                                s"weixin-ilink bridge: ask refusal line send failed code=${res.code} reason=${res.reason}"
                              )
                          } *>
                            IO.pure(Some(Verdict.Dropped(ReasonAskRange)))
                  case Some(entry) =>
                    // Answered tombstone: a repeat read of the numbers is refused
                    // (S-8 frozen copy reused verbatim), never injected twice.
                    WeixinAskLeg.parseReply(t, entry.views) match
                      case Right(None) => IO.pure(None) // not ask-shaped at all — chat
                      case _ =>
                        outbound(s, FeishuAskCard.ToastAlreadyAnswered).flatMap { res =>
                          if res.ok then IO.unit
                          else logger.warn(s"weixin-ilink bridge: already-answered line send failed code=${res.code}")
                        } *> IO.pure(Some(Verdict.Dropped(ReasonAskAnswered)))
                  case None => IO.pure(None)
              }
        }
      case _ => IO.pure(None)

    end match

  end askLegs

  /**
   * askucancel 批（2026-10-08）：取消关键字腿——按在册 entry 的 requestId 精确
   * 取消（hub CancelRequest：发起方收到终结通知、卡片所属会话落 `__cancelled__`
   * 行、前端关卡广播在途），回执一行，台账条目随撤即清（askUserClosed 广播到来
   * 时 onAskClosedFrame 的清理幂等为 no-op）。取消失败不回滚台账：hub 裁决面
   * （fire-and-forget）之下，晚到的 closed 帧仍会兜底清台。
   */
  private def cancelAsk(ctx: BridgeContext, sender: String, entry: AskEntry): IO[Verdict] =
    ctx.cancelInteractionRequest(entry.requestId, "channel-cancel-keyword", Name) *>
      askLedgerRef.update(_ - sender) *>
      outbound(sender, WeixinAskLeg.CancelledLine).flatMap { res =>
        if res.ok then IO.unit
        else logger.warn(s"weixin-ilink bridge: cancel receipt line send failed code=${res.code} reason=${res.reason}")
      } *>
      logger.info(
        s"weixin-ilink bridge: ask cancelled by keyword requestId=${entry.requestId} root=${entry.rootSessionId} sender=$sender"
      ) *>
      IO.pure(Verdict.Cancelled(entry.requestId))

  /**
   * Validate → fixed-length slots (expand over the ORIGINAL set — a dropped
   * question stays `""`) → the hub Answered convergence → tombstone.
   */
  private def answerAsk(ctx: BridgeContext, sender: String, entry: AskEntry, actions: List[PromptAction]): IO[Verdict] =
    AskPromptCodec.validate(entry.views, actions) match
      case Left(why) =>
        logger.warn(s"weixin-ilink bridge: ask reply failed validation ($why) — refused") *> IO.pure(
          Verdict.Dropped(ReasonAskRange)
        )
      case Right(_) =>
        val kept = AskPromptCodec.answers(entry.views, actions)
        val answers = entry.expand(kept)
        ctx.forwardInteractionAnswer(entry.requestId, entry.rootSessionId, Json.obj("answers" -> answers.asJson)) *>
          askLedgerRef.update(_.updated(sender, entry.copy(answered = Some(answers)))) *>
          logger.info(
            s"weixin-ilink bridge: ask answer forwarded requestId=${entry.requestId} root=${entry.rootSessionId} " +
              s"answers=${answers.size}"
          ) *>
          IO.pure(Verdict.Answered(entry.requestId))

  // ─────────── askuser-remote P1: the outbound ask card + 案甲 legs ───────────

  /**
   * The `askUser` frame leg (L0): flatten (D-1) → B-3 新问顶旧问 (the previous
   * OPEN ask on this sender is retired by precise requestId, never the
   * session-wide sweep) → ledger slot → attachments ride the MEDIA seam FIRST
   * (askuser-media-split batch: 媒体先单独发出，文本随后) → numbered text card
   * through the SAME deliver/dedup path as a chat reply, carrying path lines
   * ONLY for attachments the media leg did NOT deliver. A session with no
   * bound sender has no return address — the frame passes through (the hub
   * card still shows in the web window; the reply leg's own rule).
   */
  private def onAskUserFrame(sessionId: String, c: io.circe.HCursor): IO[Unit] =
    ctxRef.get.flatMap {
      case None => IO.unit // never started — nothing to close, nowhere to send
      case Some(ctx) =>
        routesRef.get.flatMap { routes =>
          routes.collectFirst { case (sender, sid) if sid == sessionId => sender } match
            case None => IO.unit // not a weixin-bound session — pass through
            case Some(sender) =>
              val requestId = c.downField("requestId").as[String].toOption.map(_.trim).filter(_.nonEmpty)
              val items = c.downField("items").as[List[Json]].toOption.getOrElse(Nil)
              val attachments =
                c.downField("attachments").as[List[String]].toOption.getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
              val views = AskPromptCodec.viewsFromItemsJson(items)
              (requestId, views) match
                case (None, _) =>
                  // 失败必打日志批（2026-10-08）: these two drop arms were dead
                  // `IO(logger.warn(...))` wrappers — the inner logback call
                  // never executed (the WeixinIlinkBridgeSpec header's own
                  // named hazard). Unwrapped so the drop actually logs.
                  logger.warn(s"weixin-ilink bridge: askUser frame without requestId — dropped")
                case (_, Nil) =>
                  logger.warn(s"weixin-ilink bridge: askUser frame without a usable question — dropped")
                case (Some(rid), all) =>
                  val flat = AskPromptCodec.flatten(all)
                  askLedgerRef.get.flatMap { ledger =>
                    val previous = ledger.get(sender)
                    // B-3 拍板 (无超时·新问顶旧问): the previous OPEN ask retires the
                    // moment the new one takes the slot — precise CloseRequest, the
                    // InteractionHub CloseRequest 先例 (single-slot, never the
                    // session-wide sweep). An answered tombstone is already consumed
                    // at the hub; nothing to close.
                    val closeOld = previous
                      .filter(p => p.requestId != rid && p.answered.isEmpty)
                      .map(p =>
                        ctx.closeInteractionRequest(p.requestId) *>
                          logger.info(
                            s"weixin-ilink bridge: new ask displaces requestId=${p.requestId} for sender $sender (B-3 top-old)"
                          )
                      )
                      .getOrElse(IO.unit)
                    closeOld *>
                      askLedgerRef.update(
                        _ + (sender -> AskEntry(
                          requestId = rid,
                          rootSessionId = sessionId,
                          views = flat.views,
                          originalIndices = flat.originalIndices,
                          originalSize = flat.originalSize
                        ))
                      ) *>
                      deliverAskAttachments(sender, attachments).flatMap { undelivered =>
                        sendAskCard(sender, flat.views, undelivered, requestId = rid)
                      }
                  }
              end match
        }
    }

  /**
   * The ask-attachment MEDIA leg (askuser-media-split batch, 2026-10-09): each
   * attachment goes out through the side-car media seam BEFORE the ask text —
   * image-table extensions ([[isImagePath]], the SAME authority
   * [[RichKind.of]] reads) via `image_path`, everything else via `file_path`.
   * Returns the UNDELIVERED paths: a path missing on disk, or whose send
   * failed, comes back so the text card keeps its 附件路径 line (the A6 line
   * is the visible degrade — the recipient always learns where the file
   * lives; no extra failure notice is sent on top of it). Sequential in list
   * order; the tool schema caps the list at 9.
   */
  private def deliverAskAttachments(sender: String, attachments: List[String]): IO[List[String]] =
    attachments.foldLeft(IO.pure(List.empty[String])) { (acc, raw) =>
      acc.flatMap { failed =>
        val p = raw.trim
        val image = isImagePath(p)
        mediaPathExists(p).flatMap {
          case false =>
            logger
              .warn(s"weixin-ilink bridge: ask attachment missing on disk — keeping the path line: $p")
              .as(failed :+ p)
          case true =>
            val media =
              if image then WeixinOutboundAdapter.WeixinMedia.image(p)
              else WeixinOutboundAdapter.WeixinMedia.file(p)
            outboundMedia(sender, media).flatMap { res =>
              if res.ok then
                logger
                  .info(
                    s"weixin-ilink bridge: ask attachment delivered kind=${if image then "image" else "file"} " +
                      s"(id=${res.messageId.getOrElse("-")})"
                  )
                  .as(failed)
              else
                logger
                  .warn(
                    s"weixin-ilink bridge: ask attachment send failed code=${res.code} reason=${res.reason} " +
                      "— keeping the path line"
                  )
                  .as(failed :+ p)
            }
        }
      }
    }

  /**
   * The image-table check for the attachment split — the SAME authority the
   * rich classification reads ([[nebflow.core.tools.ImageInject.imageExtensions]]),
   * never a second table.
   */
  private def isImagePath(p: String): Boolean =
    nebflow.core.tools.ImageInject.imageExtensions.keys.exists(p.toLowerCase.endsWith)

  /**
   * The ask card send: text card, 案甲-degraded when oversized (the numbering
   * and footer stay intact — only the question TEXT is cut; the full text
   * rides the pull slot). `attachments` carries the UNDELIVERED paths only
   * (the media-split contract — delivered media must not repeat as path
   * lines). The degraded branch sends DIRECTLY (it already did the 案甲 work
   * itself — deliverText would re-stash).
   */
  private def sendAskCard(
    sender: String,
    views: List[PromptView],
    attachments: List[String],
    requestId: String = ""
  ): IO[Unit] =
    // askuser-secret 批：requestId 透传进渲染面（secret 题的桌面引导文案括注用；
    // 无 secret 题 ⇒ 渲染输出与改前逐字节一致）。
    val (text, _) = WeixinAskLeg.renderAskText(views, attachments, requestId)
    if text.length <= WeixinAskLeg.FullTextThreshold then deliverText(sender, text).void
    else
      val (short, _) = WeixinAskLeg.renderAskTextTruncated(
        views,
        attachments,
        questionBudget = math.max(WeixinAskLeg.FullTextThreshold - 800, 0),
        requestId = requestId
      )
      val segs = WeixinAskLeg.segments(text)
      pullRef.update(_.updated(sender, PullEntry(text, segs.size))) *>
        outbound(sender, short + "\n\n" + WeixinAskLeg.fullTextHint(text.length, segs.size)).void

  end sendAskCard

  /**
   * `askUserClosed` (P1 form): the hub slot is gone (withdraw / cleanup /
   * restart) — the ledger entry dies with it. A later numbered reply then
   * falls through as normal chat (there is nothing left to answer).
   */
  private def onAskClosedFrame(c: io.circe.HCursor): IO[Unit] =
    val rid = c.downField("requestId").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    rid.fold(IO.unit)(r =>
      askLedgerRef.update { m =>
        val victim = m.collectFirst { case (s, e) if e.requestId == r => s }
        victim.fold(m)(s => m - s)
      }
    )

  /**
   * The 案甲 reply leg (B-4 拍板, 案甲): text at/below the first tier goes out
   * as-is; longer text stashes the full body in the sender's pull slot and
   * sends 摘要（首 4000 字）+ 拉取指令. A newer long text displaces the older
   * slot (latest wins).
   */
  private def deliverText(sender: String, text: String): IO[WeixinOutboundAdapter.SendResult] =
    val plain = nebflow.social.outbound.OutboundText.toPlain(text)
    if plain.length <= WeixinAskLeg.FullTextThreshold then outbound(sender, plain)
    else
      val segs = WeixinAskLeg.segments(plain)
      pullRef.update(_.updated(sender, PullEntry(plain, segs.size))) *>
        outbound(
          sender,
          plain.take(WeixinAskLeg.FullTextThreshold) + "\n\n" +
            WeixinAskLeg.fullTextHint(plain.length, segs.size)
        )

  /**
   * Push the stashed full text in segments (each order-marked, each within
   * the first tier). The slot clears only when EVERY segment was accepted —
   * a failed push keeps the slot so a repeat 「全文」 can retry.
   */
  private def pushFullText(sender: String, pending: PullEntry): IO[Unit] =
    val segs = WeixinAskLeg.segments(pending.text)
    segs
      .foldLeft(IO.pure(true)) { (acc, seg) =>
        acc.flatMap {
          case false => IO.pure(false) // stop on first failure — the slot stays for a retry
          case true =>
            outbound(sender, seg).flatMap { res =>
              if res.ok then IO.pure(true)
              else
                logger
                  .warn(
                    s"weixin-ilink bridge: full-text segment send failed code=${res.code} reason=${res.reason} — pull slot kept"
                  )
                  .as(false)
            }
        }
      }
      .flatMap {
        case true => pullRef.update(_ - sender)
        case false => IO.unit
      }

  end pushFullText

  /**
   * Auto-bind (weixin-autobind batch, 2026-10-07) — the feishu C4 form
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
   * for the NEXT message even when this one carries nothing injectable.
   */
  private def autoBind(ctx: BridgeContext, in: Inbound, sessionId: String): IO[Verdict] =
    val cfg = Json.obj(
      RouteKey -> in.routeKey.asJson,
      "source" -> "auto".asJson,
      "boundAt" -> System.currentTimeMillis().asJson
    )
    ctx.updateBridgeConfig(sessionId, Name, Some(cfg)) *>
      routesRef.update(_ + (in.routeKey -> sessionId)) *>
      logger.info(s"weixin-ilink bridge: sender ${in.routeKey} auto-bound to default session $sessionId") *>
      // The tail: a landed media path injects the media line (weixin-inbound-
      // media batch) — the BINDING semantics above are unchanged (a media
      // message binds exactly like any other first message); only what gets
      // injected for a no-text media message grew.
      mediaArmOrText(ctx, in, sessionId)

  private def inject(ctx: BridgeContext, in: Inbound, sessionId: String): IO[Verdict] =
    logger.info(s"weixin-ilink bridge: sender ${in.routeKey} -> session $sessionId") *>
      ctx.injectMessage(sessionId, in.text.getOrElse(""), in.senderId, originOf(in)).as(Verdict.Injected(sessionId))

  /**
   * The channel-agnostic origin descriptor this bridge contributes. The channel
   * id is the bridge's own stable name and the display name is this channel's
   * human label — supplied by the channel itself, never copied from another
   * channel's label. The iLink protocol has no conversation reference of its
   * own (the routing key is the sender id), so `chatRef` is `None` and the
   * marker renders the neutral `-`. Pure data — the template lives on the
   * channel-agnostic side ([[nebflow.bridge.BridgeOrigin]]).
   */
  private def originOf(in: Inbound): Option[nebflow.bridge.BridgeOrigin] =
    Some(
      nebflow.bridge
        .BridgeOrigin(channelId = WeixinIlinkBridgePlugin.Name, channelDisplay = WeixinIlinkBridgePlugin.ChannelDisplay)
    )

  // ───────────────────────────── routing ─────────────────────────────
  override def refreshRoutes: IO[Unit] =
    ctxRef.get.flatMap {
      case Some(ctx) => rebuildRoutes(ctx)
      case None => IO.unit
    }

  private[social] def rebuildRoutes(ctx: BridgeContext): IO[Unit] =
    ctx.listSessions.flatMap { metas =>
      val pairs = metas.flatMap { s =>
        s.bridges
          .get(Name)
          .flatMap(b => b.hcursor.downField(RouteKey).as[String].toOption.map(_.trim).filter(_.nonEmpty))
          .map(_ -> s.id)
      }
      val bound = pairs.toMap // duplicate sender ids: last session wins
      val dupes = pairs.groupBy(_._1).count((_, v) => v.sizeIs > 1)
      val warn =
        if dupes > 0 then
          logger.warn(
            s"weixin-ilink bridge: $dupes sender id(s) bound to more than one session — the last binding wins"
          )
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
   * `toolEnd` (weixin-media batch, 2026-10-08): the formerly no-op arm grew
   * the SAME product memory the feishu leg ships — a tool result is only a
   * rich product when it was NOT an error, and a Pop payload's `items[]`
   * carry the resolved artifact paths — ALL carriable ones, in payload order
   * (pop batch-media fix; the channel-agnostic parser's authority). On `done`
   * the remembered product rides [[deliverRichMedia]] (image → `image_path`,
   * document → `file_path` through the side-car's media leg), ADDITIVELY: the
   * text reply is never suppressed.
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
          case "" => IO.unit
          case delta => pendingRef.update(m => m.updated(sessionId, m.getOrElse(sessionId, "") + delta))
      case "toolEnd" =>
        // A tool result is only a rich product when it was NOT an error; an
        // errored tool's content is an error string, never an artifact.
        if c.downField("isError").as[Boolean].getOrElse(false) then IO.unit
        else
          RichProduct.of(
            content = c.downField("content").as[String].getOrElse(""),
            input = c.downField("input").focus,
            label = c.downField("label").as[String].getOrElse(""),
            documentExtensions = documentExtensions
          ) match
            case None => IO.unit
            // pop multi-group fix: APPEND — one turn may carry several Pop 组卡,
            // and every one of them is delivered (the old single-slot memory
            // let a later Pop silently replace an earlier group on the wire).
            case Some(rich) =>
              richRef.update(m => m.updated(sessionId, m.getOrElse(sessionId, Nil) :+ rich))
      case "done" =>
        (
          pendingRef.modify { m => (m - sessionId, m.getOrElse(sessionId, "")) },
          richRef.modify { m => (m - sessionId, m.getOrElse(sessionId, Nil)) }
        ).tupled.flatMap { case (text, richs) =>
          // 逐段即时批（2026-10-10）：轮终只补发「最后一段尾巴」（边界已把
          // 前段逐条推走并清空缓冲 ⇒ 天然无整轮重发）。媒体腿进同一节流队列、
          // 排在尾段之前：媒体先行、正文殿后的轮终秩序保持。pop multi-group
          // fix：同一 pacer 槽内把本轮记住的每一张组卡都发完（组序 = toolEnd
          // 序，组内载荷序），再发尾段。
          val richLeg = richs match
            case Nil => IO.unit
            case rs  => pacer.enqueue(deliverRichAll(sessionId, rs))
          val textLeg = if text.trim.isEmpty then IO.unit else pacer.enqueue(reply(sessionId, text))
          richLeg *> textLeg
        }
      // 逐段即时批：接桌面端的分段提交源（SessionRecorder 在 toolStart /
      // roundComplete 帧 flush textBuf 成一条气泡）——缓冲段即推即清，不等
      // 轮终；空段（纯工具过程）不推；`.start` 后台排队，不阻塞流式事件面。
      case "toolStart" | "roundComplete" => flushSegment(sessionId)
      // A retried or interrupted turn re-streams its text; the partial buffer
      // must never flush as a duplicated/garbled reply — and neither may the
      // turn's remembered media (weixin-media batch).
      case "interrupted" | "retryStatus" =>
        pendingRef.update(_ - sessionId) *> richRef.update(_ - sessionId)
      // askuser-remote P1 (L0, D-2 拍板: each bridge grows its own branch —
      // no second mechanism). askUser → the per-sender numbered card (with
      // B-3 top-old and the ask ledger); askUserClosed → ledger clear.
      case "askUser" => onAskUserFrame(sessionId, c)
      case "askUserClosed" => onAskClosedFrame(c)
      case _ => IO.unit

    end match

  end onAgentEvent

  /**
   * 逐段即时推送批：把该会话当前缓冲的文本段作为一条独立消息推出。空段
   * （纯工具过程、无正文）不推；推送经 [[pacer]] 排队（段间 ≥1s 按序发）且
   * `.start` 后台跑——绝不阻塞流式事件面（wsHub 广播监听是内联执行）。
   */
  private def flushSegment(sessionId: String): IO[Unit] =
    pendingRef.modify { m => (m - sessionId, m.getOrElse(sessionId, "")) }.flatMap { text =>
      if text.trim.isEmpty then IO.unit
      else pacer.enqueueAsync(reply(sessionId, text, isSegment = true))
    }

  /**
   * 逐段即时推送批：段级失败的「注明」腿。既有频控/重发边界不动——ret=-2
   * 退避在 side-car 侧、失败 WARN 语义原样、失败段**不补发**（超时可能已
   * 送达，design §0.4）——但段不再静默丢：一条一行提示经同一队列按序出网。
   * 同一会话连续失败只注明一次（[[segmentFailNoted]] 闸），任一次发送成功即
   * 复位。提示必在其主题段（失败那条）的发送尝试之后；它从失败段的后台
   * runner 里注册，相对其后注册段的插队位置不保证（失败检测是异步的；生产
   * 段间隔秒级，实际总在下一段之前）。提示自身走 `isSegment = false` 的
   * [[reply]]：再失败只有 WARN，无提示链级联。
   */
  private def noteSegmentFailure(sessionId: String, res: WeixinOutboundAdapter.SendResult): IO[Unit] =
    segmentFailNoted.modify { s =>
      if s.contains(sessionId) then (s, IO.unit)
      else
        (
          s + sessionId,
          pacer.enqueueAsync(reply(sessionId, s"[上一条推送失败 code=${res.code}] ${res.reason}"))
        )
    }.flatten

  private[social] def reply(sessionId: String, text: String, isSegment: Boolean = false): IO[Unit] =
    routesRef.get.flatMap { routes =>
      routes.collectFirst { case (sender, sid) if sid == sessionId => sender } match
        case None =>
          // 失败必打日志批（2026-10-08）: was `IO(logger.warn(...))` — a nested
          // IO[IO[Unit]] whose inner logback call NEVER executed (the exact
          // built-but-discarded hazard WeixinIlinkBridgeSpec's header names),
          // which is why the 2026-10-08 21:52–22:51 incident (9 replies dead
          // on the shim's ret=-2 502) produced zero engine logs. Sequenced
          // directly now — the drop actually logs.
          logger.warn(
            s"weixin-ilink bridge: reply for session $sessionId dropped [$ReasonNoReturnAddress] — " +
              s"no sender id is bound to this session, so the reply has no iLink target"
          )
        case Some(sender) =>
          // askuser-remote P1 (B-4 案甲): the flush goes through the deliver
          // path, which applies the 4000-char first tier — at/below it the
          // send is byte-identical to the pre-P1 behaviour; above it the full
          // text rides the pull slot and 摘要+拉取指令 goes out.
          deliverText(sender, text).flatMap { res =>
            if res.ok then
              // 成功路径零日志（拍板 2026-10-08，避免噪音）— the delivered id
              // stays visible in the side-car's own out.log. The old
              // `IO(logger.info(...))` here was a DEAD nested IO (never ran);
              // it is replaced by the honest no-op rather than unwrapped.
              // 逐段即时批：成功同时复位失败注明闸（下一段失败再注明）。
              segmentFailNoted.update(_ - sessionId)
            else
              // 失败必打日志: was `IO(logger.warn(...))` — a nested IO[IO[Unit]]
              // whose inner logback call NEVER executed (the exact
              // built-but-discarded hazard WeixinIlinkBridgeSpec's header
              // names) — the reason the 2026-10-08 21:52–22:51 incident (9
              // replies dead on the shim's ret=-2 502) produced zero engine
              // logs. Sequenced directly now — the drop actually logs.
              logger.warn(s"weixin-ilink bridge: reply send failed code=${res.code} reason=${res.reason}") *>
                // 逐段即时批：段级失败不补发（重发边界不变），注明一条。
                (if isSegment then noteSegmentFailure(sessionId, res) else IO.unit)
          }
    }

  // ───────────── weixin-media batch: the rich-media flush leg ─────────────

  /**
   * The rich-media leg (weixin-media batch, 2026-10-08): the remembered
   * product's items are classified through the channel-agnostic [[RichKind.of]]
   * and carried image → `image_path`, document → `file_path` — every failure
   * degrades to a ONE-line text notice ([[deliverMedia]], the 保底 rule), the
   * turn's text reply is NEVER suppressed (the additive rich-leg rule).
   *
   * Batch carrying (pop batch-media fix, 2026-10-08): a Pop of N artifacts is
   * remembered as N media items, and this leg sends ALL of them, in list order
   * (媒体先行、正文殿后 — the media-first / caption-after order the `done` arm
   * fixes). The 保底 degrade stays PER ITEM: one failed send puts out its own
   * notice and the loop moves on to the next item, so a single failure never
   * swallows the rest of the batch.
   *
   * The other kinds are the stated v1 boundary, each answered with a log line
   * (never a silent drop, never a garbage send):
   *
   *   - CARD — the card payload is HTML for the feishu renderer; the weixin
   *     leg cannot transcode it, so nothing goes out but the text reply;
   *   - UNKNOWN — since the social-webshot batch (2026-10-09) an EXISTING
   *     html/htm item is no longer here; per the SAME-DAY author ruling
   *     (2026-10-09 14:0x「社交 html 可以以文件的形式发过来 不用自动转图」) it
   *     FIRST rides the file_path leg AS THE ORIGINAL FILE, and only a FAILED
   *     file send falls back to the full-page screenshot ([[deliverWebPageMedia]]).
   *     The rest (non-existent path, extension in neither authority table,
   *     video-only payload…) stays log-only — the text reply carries the turn,
   *     and no mechanism wording ever reached a weixin user.
   *
   * 🔴 A session with no bound sender has no return address — same rule as the
   * text leg; the text leg's own WARN covers the turn, so this leg passes.
   *
   * 🔴 pop multi-group fix (2026-10-09): the per-item loop is now ALSO
   * raise-contained at this layer — an unexpected throw inside one item's arm
   * degrades THAT item to one visible `[未送达]` line through the bridge's own
   * text seam ([[outbound]]) and the loop moves on. Before this, a raise
   * escaped the fold and killed the remaining items (and, via the `done` arm's
   * `richLeg *> textLeg`, the turn's text tail with them).
   */
  private[social] def deliverRichMedia(sessionId: String, rich: RichProduct): IO[Unit] =
    routesRef.get.flatMap { routes =>
      routes.collectFirst { case (sender, sid) if sid == sessionId => sender } match
        case None => IO.unit // the text reply logs the same WARN for this session
        case Some(sender) =>
          if RichKind.isCardContent(rich.content) then
            IO(
              logger.info(
                "weixin-ilink bridge: rich CARD observed — the weixin v1 media leg carries image/file only " +
                  "(a card payload is not transcodable); the text reply carries the turn"
              )
            )
          else
            rich.media.foldLeft(IO.unit) { (acc, p) =>
              acc.flatMap { _ =>
                val kind = RichKind.of(
                  content = "",
                  path = Some(p.toString),
                  exists = Try(os.isFile(p)).getOrElse(false),
                  documentExtensions = documentExtensions
                )
                val leg = kind match
                  case RichKind.Image =>
                    deliverMedia(sender, WeixinOutboundAdapter.WeixinMedia.image(p.toString), "image")
                  case RichKind.Document =>
                    deliverMedia(sender, WeixinOutboundAdapter.WeixinMedia.file(p.toString), "file")
                  case RichKind.Unknown(reason) =>
                    // 2026-10-09 同日裁定修正：可渲染网页类（html/htm 且原件在
                    // 盘上）先走 file_path 腿直发原件，文件腿失败才截整页 PNG
                    // 走 image_path 兜底（[[deliverWebPageMedia]]）。其余保持
                    // v1 边界（日志一行，正文腿带回合——机制词汇从未到过微信
                    // 用户眼前）。
                    if Try(os.isFile(p)).getOrElse(false) && WebPageShot.isWebPage(p) then
                      deliverWebPageMedia(sender, p)
                    else
                      // 逐字一行即 IO（pop multi-group fix 顺手修正）：原
                      // `IO(logger.info(...))` 是 IO[IO[Unit]] 死日志（内层
                      // logback 永不执行 —— WeixinIlinkBridgeSpec 头注点名的
                      // 同款 built-but-discarded 隐患），靠期望类型 unit-giving
                      // 才过编译；绑定 val leg 后类型现形，直落 IO[Unit]。
                      logger.info(
                        s"weixin-ilink bridge: rich item not carriable ($reason) — the text reply carries the turn"
                      )
                  case RichKind.Card =>
                    // Unreachable on this branch (the content is not a card
                    // here, and the per-item classification reads no content) —
                    // the total-function backstop.
                    IO.unit
                // pop multi-group fix: one item's RAISE is contained to that
                // item (visible line + WARN); the remaining items keep going.
                leg.handleErrorWith { e =>
                  val name = p.last
                  for
                    _ <- IO(logger.warnSync(
                      s"weixin-ilink bridge: rich item delivery raised for $p " +
                        s"(${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}) — degrading to a visible line"
                    ))
                    r2 <- outbound(sender, s"[未送达] $name — 这一项发送时出现异常，其余内容照常")
                    _ <- if r2.ok then IO.unit
                         else
                           IO(logger.warnSync(
                             s"weixin-ilink bridge: degrade notice send failed code=${r2.code} reason=${r2.reason}"
                           ))
                  yield ()
                }
              }
            }
    }

  /**
   * pop multi-group fix (2026-10-09): send EVERY 组卡 the turn remembered, in
   * toolEnd order — one pacer slot for the whole list, so the text tail still
   * follows all media. A raise inside one group is contained by that group's
   * own per-item guards, never by re-raising here.
   */
  private def deliverRichAll(sessionId: String, richs: List[RichProduct]): IO[Unit] =
    richs.foldLeft(IO.unit)((acc, rich) => acc *> deliverRichMedia(sessionId, rich))

  /**
   * 网页产物腿（social-webshot batch；用户裁定 2026-10-09 14:0x 修正）：
   * html/htm 原件先走既有 file_path 媒体缝直发（① 文件腿——原件本体即消息，
   * 不再自动截图）；文件腿发送失败（渠道拒绝/超时）才退整页截图
   * （[[WebPageShot]]，30s 预算，产物进本数据根的临时面）→ 既有 image_path 腿
   * （② 兜底——其发送失败的保底文案由 [[deliverMedia]] 原样承担）；截图也不可
   * 用 ⇒ v1 边界（③：日志一行、正文腿带回合——微信侧从未把机制报错发给用户，
   * b 层在微信侧由回合正文承担）。文件腿经 [[outboundMedia]] 裸发而非
   * [[deliverMedia]]：失败不得先发 `[文件发送失败]` 文案——兜底链先于一切
   * 用户可见字。分类层零改动：html 仍判 Unknown，本腿只在 Unknown 臂被读取。
   */
  private def deliverWebPageMedia(sender: String, html: os.Path): IO[Unit] =
    outboundMedia(sender, WeixinOutboundAdapter.WeixinMedia.file(html.toString)).flatMap {
      case res if res.ok =>
        IO(logger.info(
          s"weixin-ilink bridge: web page delivered as the original file to sender $sender " +
            s"(id=${res.messageId.getOrElse("-")})"
        ))
      case res =>
        val out = root / "tmp" / "outbound-render" /
          s"page-${java.util.UUID.randomUUID().toString.take(12)}.png"
        logger.warn(
          s"weixin-ilink bridge: web page file send failed code=${res.code} reason=${res.reason} " +
            "— falling back to the full-page shot"
        ) *>
          webshot.shot(html, out).flatMap {
            case Left(reason) =>
              IO(logger.info(
                s"weixin-ilink bridge: web page shot unavailable for $html ($reason) — the text reply carries the turn"
              ))
            case Right(png) =>
              deliverMedia(sender, WeixinOutboundAdapter.WeixinMedia.image(png.toString), "image")
          }
    }

  /**
   * One media send with the 保底 degrade: a FAILED media send puts out ONE
   * text notice through the SAME text seam (contained, never a raise, never a
   * retry — the outbound leg's own containment rule; the notice names the
   * stable code + reason vocabulary so the recipient can act on it).
   */
  private def deliverMedia(sender: String, media: WeixinOutboundAdapter.WeixinMedia, kindLabel: String): IO[Unit] =
    outboundMedia(sender, media).flatMap { res =>
      if res.ok then
        IO(
          logger.info(
            s"weixin-ilink bridge: rich media delivered to sender $sender kind=$kindLabel (id=${res.messageId.getOrElse("-")})"
          )
        )
      else
        val name = media.imagePath.orElse(media.filePath).map(_.split('/').last).getOrElse(kindLabel)
        val line =
          if kindLabel == "image" then s"[图片发送失败 code=${res.code}] ${res.reason}"
          else s"[文件「$name」发送失败 code=${res.code}] ${res.reason}"
        logger.warn(
          s"weixin-ilink bridge: rich media send failed kind=$kindLabel code=${res.code} reason=${res.reason} " +
            "— degrading to a text notice"
        ) *>
          outbound(sender, line).flatMap { r2 =>
            if r2.ok then IO.unit
            else logger.warn(s"weixin-ilink bridge: degrade notice send failed code=${r2.code} reason=${r2.reason}")
          }
    }

end WeixinIlinkBridgePlugin

object WeixinIlinkBridgePlugin:

  /** BridgePlugin.name — also the platform key in `SessionMeta.bridges`. */
  val Name = "weixin-ilink"

  /** The bridge-config field carrying the sender id in a binding. */
  val RouteKey = "ilink_user_id"

  /**
   * The outbound send seam: `(to, text) → reading` (weixin-outbound batch).
   * The reading is [[WeixinOutboundAdapter.SendResult]] — one contained
   * outcome per flush, never a raise.
   */
  type Send = (String, String) => IO[WeixinOutboundAdapter.SendResult]

  /**
   * The outbound MEDIA send seam (weixin-media batch): `(to, media) →
   * reading` — the same contained reading; the payload is
   * [[WeixinOutboundAdapter.WeixinMedia]] (exactly one of image/file).
   */
  type SendMedia = (String, WeixinOutboundAdapter.WeixinMedia) => IO[WeixinOutboundAdapter.SendResult]

  /**
   * The outbound drop reason for a session with no bound sender: stable
   * string, safe to reconcile from a log line.
   */
  val ReasonNoReturnAddress = "no-return-address"

  /**
   * The human-readable label this channel announces in the source-marker block
   * (soc483 batch). Same value as the card's own name copy
   * (`social.weixinIlink.name`) — the label belongs to THIS channel; it is
   * never copied from another channel's label. Data only: the marker template
   * lives on the channel-agnostic side ([[nebflow.bridge.BridgeOrigin.marker]]).
   */
  val ChannelDisplay = "微信（iLink）"

  /** Companion-side logger (the sync leg lives here, outside any instance). */
  private val log = NebflowLogger.forName("nebflow.social.weixin-ilink-bridge")

  /**
   * One inbound message handed over by the side-car. The four `media*`
   * fields (weixin-inbound-media batch, 2026-10-08) ride ONLY when the
   * side-car landed the item on local disk before delivery; the media arm
   * ([[WeixinIlinkBridgePlugin.mediaLine]]) turns them into the injection
   * line the agent reads.
   */
  final case class Inbound(
    senderId: Option[String],
    text: Option[String],
    messageId: Option[String] = None,
    mediaPath: Option[String] = None,
    mediaKind: Option[String] = None,
    mediaName: Option[String] = None,
    mediaMime: Option[String] = None
  ):
    /** The routing key: the sender id, empty when unresolvable. */
    def routeKey: String = senderId.map(_.trim).filter(_.nonEmpty).getOrElse("")

    /**
     * The media arm's trigger: a media path the side-car actually landed
     * (trimmed non-empty). Everything else stays a text-shaped message.
     */
    def mediaLanded: Boolean = mediaPath.exists(_.trim.nonEmpty)

  end Inbound

  /**
   * The observable outcome of one ingest — the reading the side-car, the logs
   * and the offline spec all see. askuser-remote P1 adds two CONSUMED
   * outcomes that are neither injected nor dropped: the message was an ask
   * reply routed to the hub ([[Verdict.Answered]]) or a 案甲 full-text pull
   * ([[Verdict.Pulled]]).
   */
  enum Verdict:
    case Injected(sessionId: String)
    case Dropped(reason: String)
    case Answered(requestId: String)
    case Pulled(segments: Int)

    /** askucancel 批（2026-10-08）：取消关键字消费的出站（消息既未注入也未丢弃）。 */
    case Cancelled(requestId: String)

    def json: Json = this match
      case Injected(sessionId) => Json.obj("verdict" -> "injected".asJson, "sessionId" -> sessionId.asJson)
      case Dropped(reason) => Json.obj("verdict" -> "dropped".asJson, "reason" -> reason.asJson)
      case Answered(requestId) => Json.obj("verdict" -> "answered".asJson, "requestId" -> requestId.asJson)
      case Pulled(segments) => Json.obj("verdict" -> "full-text".asJson, "segments" -> segments.asJson)
      case Cancelled(requestId) => Json.obj("verdict" -> "cancelled".asJson, "requestId" -> requestId.asJson)
  end Verdict

  // Drop reasons — stable strings, safe to read from a log or a response body.
  val ReasonNotOnAllowlist = "not-on-allowlist"
  val ReasonNoSession = "no-session-bound"
  val ReasonNoText = "no-text-body"
  val ReasonInjectFailed = "inject-failed"
  val ReasonNotStarted = "adapter-not-started"
  val ReasonMalformed = "malformed-body"
  // askuser-remote P1 additions (the ask legs' consumed outcomes).
  val ReasonAskRange = "ask-index-out-of-range"
  val ReasonAskAnswered = "ask-already-answered"

  // ───────── weixin-inbound-media batch: the media arm's pure face ─────────

  /**
   * The 中文 media label per kind (the shim's MEDIA_LABEL family, engine
   * side). An absent/unrecognized kind degrades to the file label — the
   * conservative, file-ish default for a kind we cannot name.
   */
  def mediaLabel(kind: Option[String]): String =
    kind.map(_.trim.toLowerCase) match
      case Some("image") => "[图片]"
      case Some("video") => "[视频]"
      case _ => "[文件]"

  /**
   * The media arm's injection line (按 kind 中文标签): the landed path —
   * what the agent Reads — plus the original file name when the wire carried
   * one. Pure, so the offline spec pins the exact wording.
   */
  def mediaLine(path: String, kind: Option[String], name: Option[String]): String =
    name.map(_.trim).filter(_.nonEmpty) match
      case Some(n) => s"${mediaLabel(kind)} 已存至 $path（原始文件名 $n），可用 Read 查看"
      case None => s"${mediaLabel(kind)} 已存至 $path，可用 Read 查看"

  /**
   * One sender's active (or answered) ask slot — askuser-remote P1. The
   * views are the FLATTENED, renumbered set (D-1); `originalIndices` /
   * `originalSize` re-expand the kept slots into the engine's fixed-length
   * answer array. `answered` = Some → a tombstone (the numbers now read
   * 「已作答」, never re-injected); None → the ask is open.
   */
  final case class AskEntry(
    requestId: String,
    rootSessionId: String,
    views: List[PromptView],
    originalIndices: List[Int],
    originalSize: Int,
    askedAt: Long = System.currentTimeMillis(),
    answered: Option[List[String]] = None
  ):

    /**
     * Kept slots → the engine's fixed-length answer list (`""` at dropped
     * positions). Same rule as `AskPromptCodec.FlatSet.expandAnswerSlots`;
     * spelled here so the entry is self-contained.
     */
    def expand(keptSlots: List[String]): List[String] =
      val slots = Array.fill(originalSize)("")
      keptSlots.zip(originalIndices).foreach { (a, orig) =>
        if orig >= 0 && orig < originalSize then slots(orig) = a
      }
      slots.toList
  end AskEntry

  /** One sender's stashed full text (B-4 案甲 pull slot). */
  final case class PullEntry(text: String, segments: Int, stashedAt: Long = System.currentTimeMillis())

  /**
   * The ingest body → [[Inbound]]. `senderId` is the sender identity (the
   * `from_user_id` of the iLink message; that spelling is accepted as an alias
   * because it is the field name on the side-car side). A body without the key
   * is NOT an error: an unresolvable sender is exactly the case the gate must
   * rule on, so it is carried through as `None`. The four `media*` fields
   * (weixin-inbound-media batch) ride the same way: absent ⇒ `None`, and any
   * OTHER unknown field is still silently ignored (the pre-media contract).
   */
  def parseInbound(body: Json): Either[String, Inbound] =
    if !body.isObject then Left("request body must be a JSON object")
    else
      val c = body.hcursor
      def str(keys: String*): Option[String] =
        keys.toList.flatMap(k => c.downField(k).as[String].toOption).headOption.map(_.trim).filter(_.nonEmpty)
      Right(
        Inbound(
          senderId = str("senderId", "from_user_id"),
          text = c.downField("text").as[String].toOption,
          messageId = str("messageId", "message_id"),
          mediaPath = str("mediaPath"),
          mediaKind = str("mediaKind"),
          mediaName = str("mediaName"),
          mediaMime = str("mediaMime")
        )
      )

  /**
   * The card's sender gate: `allowed_ilink_user_ids`, comma/space separated,
   * empty = unrestricted (the shipped default).
   */
  final case class ChannelConfig(allowedIlinkUserIds: List[String])

  def readChannelConfig(root: os.Path): ChannelConfig =
    val fields = SocialChannels
      .readChannels(root)
      .hcursor
      .downField("channels")
      .downField(Name)
      .downField("fields")
      .focus
      .getOrElse(Json.obj())
    val allowed = fields.hcursor
      .downField("allowed_ilink_user_ids")
      .as[String]
      .toOption
      .getOrElse("")
      .split("[,;\\s]+")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
    ChannelConfig(allowed)

  end readChannelConfig

  /**
   * The activation point of the closed loop `enabled ∧ verified ⇒ the seam is
   * registered (and started); otherwise ⇒ it is not` — the same shape as
   * [[FeishuBridgePlugin.sync]]. Boot (`GatewayMain`) and the channel save
   * endpoint (`PresenceRoutes`) both route through here, so the runtime state
   * can never be assembled two different ways: a save that fills
   * `allowed_ilink_user_ids` re-arms the gate without a restart. `factory` is
   * the offline spec's seam (a fake instead of a real plugin).
   */
  def sync(
    manager: BridgeManager,
    root: os.Path,
    factory: os.Path => BridgePlugin = (r => new WeixinIlinkBridgePlugin(r))
  ): IO[Unit] =
    val want = IO.blocking(SocialChannels.isEnabled(root, Name) && SocialChannels.verified(root, Name))
    want.flatMap {
      case true =>
        val plugin = factory(root)
        manager.unregister(Name) *> manager.register(plugin) *> manager.startOne(Name)
      case false =>
        manager.unregister(Name)
    }

end WeixinIlinkBridgePlugin
