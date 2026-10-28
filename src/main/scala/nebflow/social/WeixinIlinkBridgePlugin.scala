package nebflow.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.bridge.{AskAnswerReceipt, BridgeContext, BridgeManager, BridgePlugin}
import nebflow.shared.NebflowLogger
import nebflow.social.outbound.{
  AskPromptCodec,
  FileLeg,
  FileLongShot,
  PromptAction,
  PromptView,
  RichDegrade,
  RichKind,
  RichProduct
}

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*
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
 *     (a Pop payload's image/file item) and `done` flushes it ADDITIVELY,
 *     degrading to a one-line text notice on failure — the text reply is never
 *     suppressed. 媒体合并批 (2026-10-22, 台账 #756): the turn-end order is
 *     text FIRST (the media must never outrun the text it belongs to — 作者令
 *     「等一下文字再一起发 不要空发图片和文件」), a short tail rides the first
 *     image group as its caption (图文同发), and consecutive images deliver as
 *     ONE group message ([[WeixinOutboundAdapter.sendMediaGroupLive]] — 作者令
 *     「一起发图片…组合一起发送」); the side-car probes the native multi-image
 *     sendmessage and falls back to the official per-item sequence on any
 *     refusal;
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
   * Outbound image-GROUP send seam factory (媒体合并批, 2026-10-22, 台账 #756).
   * Production = [[WeixinOutboundAdapter.sendMediaGroupLive]] (the side-car
   * group face: N images + an optional caption → one group message, automatic
   * per-item fallback server-side); the offline spec injects a recorder. Same
   * root-passing shape as [[send]].
   */
  sendMediaGroup: (os.Path => WeixinIlinkBridgePlugin.SendGroup) = WeixinOutboundAdapter.sendMediaGroupLive,
  /**
   * The DOCUMENT extension authority for the rich classification — the
   * single-authority rule ([[RichKind]]'s file header): the table is NEVER
   * copied into this channel; the default surfaces the one repo table the
   * feishu bridge already surfaces ([[FeishuOutboundAdapter.defaultTable]]),
   * and the wiring layer may pin another.
   */
  documentExtensions: Set[String] = FeishuOutboundAdapter.defaultTable,
  /**
   * 文件类产物（md/markdown/html/htm）的单张长图转换缝（pop-social-files
   * batch, 2026-10-20；作者令：Pop 的文件类产物投递社交腿=转单张长图 PNG，
   * 转不动降「文件名+本地路径+一句话摘要」文本卡，去重防轰炸）。生产 =
   * [[FileLongShot.NodeShot]]（仓内 node playwright，md 走 marked 同款渲染）；
   * 离线 spec 注入 fake / [[FileLongShot.Unavailable]]（脚本缺失/超时 ⇒ 文本卡
   * 降级）。原 social-webshot batch 的 `webshot` 缝（html 文件直发、截图兜底）
   * 随其文件腿一并退役——2026-10-20 裁定接管 2026-10-09 14:0x 的同名裁定面
   * （同字节 html 文件消息微信侧实测零到达，长图是作者点名的可看形态）。
   */
  longshot: FileLongShot.Renderer = FileLongShot.NodeShot()
) extends BridgePlugin:

  import WeixinIlinkBridgePlugin.*

  private val logger = NebflowLogger.forName("nebflow.social.weixin-ilink-bridge")

  /**
   * The outbound send leg resolved for THIS root (the injected factory, or
   * the production side-car face).
   */
  private val outbound: Send =
    // WRAPPED by the bridge-dead detector （入站图片必达批，2026-10-24）: every
    // text send feeds noteSendHealth, so the 2026-10-24 incident shape (hundreds
    // of replies dead on the shim's ret=-2 502 for 18h, per-reply WARN only)
    // escalates after BridgeDeadThreshold consecutive refusals instead of never.
    val seam = send(root)
    (sender, text) => seam(sender, text).flatMap(res => noteSendHealth(sender, res, mediaLeg = false).as(res))

  /**
   * The outbound MEDIA leg resolved for THIS root (weixin-media batch) —
   *  health-wrapped exactly like [[outbound]] (a media-only bridge death is
   *  still a death).
   */
  private val outboundMedia: SendMedia =
    val seam = sendMedia(root)
    (sender, media) => seam(sender, media).flatMap(res => noteSendHealth(sender, res, mediaLeg = true).as(res))

  /**
   * The outbound image-GROUP leg resolved for THIS root (媒体合并批) —
   *  health-wrapped exactly like [[outbound]].
   */
  private val outboundGroup: SendGroup =
    val seam = sendMediaGroup(root)
    (sender, paths, caption) =>
      seam(sender, paths, caption).flatMap(res => noteSendHealth(sender, res, mediaLeg = true).as(res))

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

  // ───────── bind-transactional 批 (2026-10-20): the no-return-address face ─────────

  /**
   * Sessions THIS seam served in this process lifetime (recorded at
   * [[inject]]). The escalation discriminator: `dispatchAgentEvent` fans EVERY
   * session's events to EVERY plugin, so a reply-leg drop for a never-served
   * session is not weixin traffic at all (debug log, unchanged quiet); a drop
   * for a SERVED session is a user-visible message loss — the 2026-10-20
   * incident shape (the default-session fallback injected the message, the
   * reply had no route back). Cleared on stop.
   */
  private[social] val servedRef =
    cats.effect.Ref.unsafe[IO, Set[String]](Set.empty)

  /**
   * 断桥预警降级批（2026-10-25，作者令）: one open ret=-2 flap window —
   * when it opened, how many refusals it has accumulated, and the last
   * refusal's code/reason — so the RECOVERY log line can carry the ret code
   * and the recovery time (the structured transient log the ruling asks
   * for). Opens on the first 0→N increment, extends on every further
   * increment, closes exactly when both streaks return to zero. Pure data;
   * the decision stays in [[noteSendHealth]].
   */
  private[social] final case class RetFlap(startMs: Long, refusals: Int, lastCode: Int, lastReason: String)

  /**
   * 入站图片必达批（2026-10-24）: the bridge-dead detector state —
   * ((text-streak, media-streak), alert-fired). Streak decision per leg is
   * the pure companion [[WeixinIlinkBridgePlugin.bridgeDeadStreak]].
   * revive-p0 批（2026-10-25，WI-BD3 收口）：streak **按腿分开**——
   * 「媒体腿单死也算死」要求媒体连拒不被穿插的文本成功清零（ask 卡 = 附件
   * 失败 + 卡文本成功交替，单一共享 streak 永远到不了阈值）；告警闸共享
   * （一次死亡一次升级），双 streak 同时归零才算全愈（re-arm + 清 strike 回
   * 绿——健康判定 = 桥整体回活，不是任一腿独活）。
   * 断桥预警降级批（2026-10-25，作者令）：第三槽 [[RetFlap]] 记录当前
   * ret=-2 抖动窗（起点/累计拒数/末次 ret 码与 reason），供恢复时的结构化
   * INFO（带 ret 码与恢复时间）——瞬态抖动只落 log，绝不通知主会话。
   */
  private val bridgeDeadRef =
    cats.effect.Ref.unsafe[IO, ((Int, Int), Boolean, Option[RetFlap])](((0, 0), false, None))

  /**
   * The dead-letter slot: the LAST reply each served session dropped on
   * [no-return-address], stashed so the sender's FIRST registration can
   * redeliver it (补投 — 若可行；the send seam is the feasibility bound). One
   * letter per session (latest wins), TTL [[WeixinIlinkBridgePlugin.DroppedReplyTtl]]
   * judged at redelivery time. Cleared on stop: a torn turn is never a reply,
   * also across restarts.
   */
  private[social] val droppedRef =
    cats.effect.Ref.unsafe[IO, Map[String, DroppedReply]](Map.empty)

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
      *> servedRef.set(Set.empty)
      *> droppedRef.set(Map.empty)

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
            // a landed media path rides the unified media seam as a (path,
            // name, mime) POINTER (媒体统一适配批 2026-10-20：登记/显示/vision
            // 构块全在网关注入点按控制台 uploads 管线完成); without one,
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
      yield (verdict, gateOpen)
    body
      .flatMap { case (verdict, gateOpen) =>
        // 入站图片必达批（2026-10-24）: media that did NOT arrive gets a WARN
        // regardless of the gate; the author receipt rides only when the gate
        // passed (fail-closed: no talking to non-allowlisted senders).
        // WI-M12 收口（revive-p0 批 2026-10-25）：WARN 与回执的闸位分离——
        // gate 关死时 WARN 仍打（运维审计不缺席），只是不回执（fail-closed）。
        mediaFailureNotice(in, receiptAllowed = gateOpen).as(verdict)
      }
      .handleErrorWith(e =>
        logger.warn(
          s"weixin-ilink bridge: dropped [$ReasonInjectFailed] — inbound injection failed " +
            s"(${e.getClass.getSimpleName}: ${e.getMessage}), contained, the seam stays up"
        ) *> IO.pure(Verdict.Dropped(ReasonInjectFailed))
      )
  end intake

  // ───────── weixin-inbound-media batch: the media arm (intake 前置臂) ─────────

  /**
   * The MEDIA ARM (weixin-inbound-media batch, 2026-10-08; 媒体统一适配批
   * 2026-10-20 改走统一媒体缝): a message whose item landed on disk hands the
   * (path,name,mime) POINTER to the unified injection seam
   * ([[BridgeContext.injectMessageWithMedia]]) — 登记/显示/vision 构块全在网关
   * 唯一注入点按**控制台 uploads 管线同款判据**完成（类型识别 mime `image/` 前缀、
   * attJson 同形、`ImageInject.prepareImage` 同管线）；本臂不再自拼「已存至」文本线
   * （该线保留为降级面与飞书腿既有语义）。Fail-closed on the path: a file that
   * died between the side-car's landing and this injection logs a WARN and
   * degrades to the bare placeholder label — the message is STILL delivered
   * either way (the side-car's cursor is never held for a media leg, so a drop
   * here would silently lose the media; the degrade keeps it visible).
   */
  private def mediaArm(ctx: BridgeContext, in: Inbound, sessionId: String): IO[Verdict] =
    val p = in.mediaPath.map(_.trim).filter(_.nonEmpty).getOrElse("")
    mediaPathExists(p).flatMap {
      case true =>
        // 统一媒体缝（2026-10-20）：指针（path+原始名+mime）交网关统一登记/构块。
        // 入站图片必达批（2026-10-24）：随文（真 caption，非 sidecar 占位标签）
        // 不再被丢弃——图文同发此前只送图、caption 无声消失；纯媒体消息的
        // 注入文本保持 "" 原状（占位标签 == mediaLabel(kind) 时被滤掉）。
        val caption = in.text.map(_.trim).filter(t => t.nonEmpty && t != mediaLabel(in.mediaKind))
        inject(
          ctx,
          in.copy(text = caption),
          sessionId,
          List(nebflow.bridge.BridgeAttachment(p, in.mediaName, in.mediaMime))
        )
      case false =>
        logger.warn(
          s"weixin-ilink bridge: media${in.messageId.fold("")(id => s" $id")} path missing at injection ($p) — " +
            "fail-closed degrade to the placeholder label, still delivered"
        ) *> inject(ctx, in.copy(text = Some(mediaLabel(in.mediaKind))), sessionId)
    }

  end mediaArm

  /**
   * 入站图片必达批（2026-10-24）· 媒体未送达的「有声」腿。side-car 的
   * `mediaError`（下载/解密/超时/超体积/无 CDN 引用/无 key/无账号）与
   * `mediaExtra`（v1 单件契约未投递的其余媒体项）在此变成：① 一条 WARN
   * （入站媒体丢失不再只是 side-car 私志，**无论闸门开闭恒打**——WI-M12
   * 收口，revive-p0 批 2026-10-25：运维审计不随 gate 缺席）；② 经出站缝
   * 回执作者一句话——哪类媒体、为什么、还剩几项没送进来、怎么走替代路径
   * ——**仅 `receiptAllowed`（= gate 通过）时发送**（fail-closed：不对白名
   * 单外发送者说话）。回执发送失败再降级为一条 WARN（绝不翻案、绝不级
   * 联）。TOTAL：无条件不抛。
   */
  private def mediaFailureNotice(in: Inbound, receiptAllowed: Boolean): IO[Unit] =
    val problem = in.mediaError.map(_.trim).filter(_.nonEmpty)
    val extra = math.max(0, in.mediaExtra)
    if problem.isEmpty && extra == 0 then IO.unit
    else
      val id = in.messageId.fold("")(i => s" msg=$i")
      val reason = problem.fold("")(r => s"：${r.take(120)}")
      val rest = if extra > 0 then s"；这条消息共 ${extra + 1} 个媒体项，其余 $extra 个也未送达" else ""
      val line =
        s"⚠️ 你发来的${mediaLabel(in.mediaKind)}没有送达$reason$rest。请重发；若反复失败，请改走文件传输助手或网页端。"
      logger.warn(
        s"weixin-ilink bridge: inbound media NOT delivered$id (kind=${in.mediaKind.getOrElse("?")})$reason" +
          s"$rest${if receiptAllowed then " — receipt follows" else " — gated out, no receipt (fail-closed)"}"
      ) *>
        (if receiptAllowed then
           Option(in.routeKey)
             .filter(_.nonEmpty)
             .fold(IO.unit)(sender =>
               outbound(sender, line).flatMap { res =>
                 if res.ok then IO.unit
                 else
                   logger.warn(
                     s"weixin-ilink bridge: media-failure receipt send failed code=${res.code} reason=${res.reason}"
                   )
               }
             )
         else IO.unit)

    end if

  end mediaFailureNotice

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
   *
   * answer-landing 实施批（2026-10-21，任务令缺陷②「回答区混入无关内容」守卫）：
   * **媒体消息永不进 ask 臂**——`mediaLanded` 在场 ⇒ 整个前置臂跳过（拉取/取消/
   * 编号解析/墓碑全部让路）。理由：媒体消息的 `text` 是 sidecar 合成的占位标签
   * （`[图片]`/`[视频]`…，见 msgloop `MEDIA_LABEL`），不是作者键入的映射文本；
   * 它一旦落进解析臂，占位标签与选项标签的**规范化碰撞**（label 臂大小写/空白
   * 容忍）会把「发了一张图」消费成「选项作答」——入站通知被渲染进问题卡回答区
   * = 缺陷本体。媒体腿先于一切 ask 语义（既有序不变：媒体臂本就在文本臂之前），
   * 媒体消息恒走媒体臂/聊天流，零混入。
   */
  private def askLegs(ctx: BridgeContext, in: Inbound): IO[Option[Verdict]] =
    if in.mediaLanded then IO.pure(None) else askTextLegs(ctx, in)

  /** The text-shaped tail of [[askLegs]] — media messages never reach it. */
  private def askTextLegs(ctx: BridgeContext, in: Inbound): IO[Option[Verdict]] =
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

  end askTextLegs

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
      // answer-landing 批：问题退役 ⇒ 回执反向通道随撤（幂等 no-op 先例同 askUserClosed）。
      AskAnswerReceipt.drop(entry.requestId) *>
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
        // answer-landing 实施批：**回执反向通道登记**（设计开放问题 #2 推荐默认采纳
        // 「渠道回执」）——转发的同一时刻渠道地址在手，闭包登记后，landing 面失败
        // 出口（AskAnswerLanding）按 requestId 取用，把「问题已作废+原因」显式送回
        // 本渠道；重入成功臂则回「答案已转交引擎重入」确认行。发送闭包 contained
        // （失败只 WARN，绝不 raise——回执是 best-effort 增量面）。
        AskAnswerReceipt.record(
          entry.requestId,
          AskAnswerReceipt.Entry(
            channel = Name,
            routeKey = sender,
            send = line => outbound(sender, line).void
          )
        ) *>
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
              // answer-landing 实施批（表1 ★）：卡头批名 = 提问载荷归因（project
              // 优先，回落 nodeName——分发器提问取「dispatcher」标）——标签非寻址键。
              val batch =
                c.downField("project")
                  .as[String]
                  .toOption
                  .map(_.trim)
                  .filter(_.nonEmpty)
                  .orElse(c.downField("nodeName").as[String].toOption.map(_.trim).filter(_.nonEmpty))
                  .getOrElse("")
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
                          // answer-landing 批：旧问退役 ⇒ 其在飞答案的回执通道随撤。
                          AskAnswerReceipt.drop(p.requestId) *>
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
                        sendAskCard(sender, flat.views, undelivered, requestId = rid, batch = batch)
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
    requestId: String = "",
    batch: String = ""
  ): IO[Unit] =
    // askuser-secret 批：requestId 透传进渲染面（secret 题的桌面引导文案括注用；
    // 无 secret 题 ⇒ 渲染输出与改前逐字节一致）。answer-landing 批：batch 透传
    // （卡头批名行；空批名 ⇒ 逐字节一致）。
    val (text, _) = WeixinAskLeg.renderAskText(views, attachments, requestId, batch)
    if text.length <= WeixinAskLeg.FullTextThreshold then deliverText(sender, text).void
    else
      val (short, _) = WeixinAskLeg.renderAskTextTruncated(
        views,
        attachments,
        questionBudget = math.max(WeixinAskLeg.FullTextThreshold - 800, 0),
        requestId = requestId,
        batch = batch
      )
      val segs = WeixinAskLeg.segments(text)
      pullRef.update(_.updated(sender, PullEntry(text, segs.size))) *>
        outbound(sender, short + "\n\n" + WeixinAskLeg.fullTextHint(text.length, segs.size)).void

  end sendAskCard

  /**
   * `askUserClosed` (P1 form): the hub slot is gone (withdraw / cleanup /
   * restart) — the ledger entry dies with it. A later numbered reply then
   * falls through as normal chat (there is nothing left to answer).
   * answer-landing 批：该问在飞答案的回执反向通道随撤（幂等）。
   */
  private def onAskClosedFrame(c: io.circe.HCursor): IO[Unit] =
    val rid = c.downField("requestId").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    rid.fold(IO.unit)(r =>
      askLedgerRef.update { m =>
        val victim = m.collectFirst { case (s, e) if e.requestId == r => s }
        victim.fold(m)(s => m - s)
      } *> AskAnswerReceipt.drop(r)
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
      // bind-transactional 批: the auto-bind IS the sender's first registration
      // — if this session stashed a [no-return-address] dead letter, it goes
      // out now (the 2026-10-20 incident's closure arm).
      redeliverDropped(in.routeKey, sessionId) *>
      // The tail: a landed media path injects the media line (weixin-inbound-
      // media batch) — the BINDING semantics above are unchanged (a media
      // message binds exactly like any other first message); only what gets
      // injected for a no-text media message grew.
      mediaArmOrText(ctx, in, sessionId)

  end autoBind

  /**
   * The text injection (with its SERVED accounting and log line). 媒体统一适配批
   * （2026-10-20）：`media` 指针在场 ⇒ 改走统一媒体缝
   * [[BridgeContext.injectMessageWithMedia]]（登记/显示/vision 构块在网关统一注入点
   * 按控制台 uploads 管线完成）；`content` = 随文（iLink 媒体无随文 ⇒ ""）。
   * 缺省（Nil）= 纯文本注入逐字现状。Verdict 口径不变（Injected）。
   */
  private def inject(
    ctx: BridgeContext,
    in: Inbound,
    sessionId: String,
    media: List[nebflow.bridge.BridgeAttachment] = Nil
  ): IO[Verdict] =
    // bind-transactional 批: the session counts as SERVED from the first
    // injection — the discriminator the reply leg's escalation reads.
    servedRef.update(_ + sessionId) *>
      logger.info(s"weixin-ilink bridge: sender ${in.routeKey} -> session $sessionId") *>
      (if media.isEmpty then ctx.injectMessage(sessionId, in.text.getOrElse(""), in.senderId, originOf(in))
       else ctx.injectMessageWithMedia(sessionId, in.text.getOrElse(""), in.senderId, originOf(in), media))
        .as(Verdict.Injected(sessionId))

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
      warn *>
        routesRef.getAndSet(bound).flatMap { previous =>
          // bind-transactional 批: every (sender → session) pair that CHANGED
          // is a first registration for that session's return address — the
          // redelivery leg fires for a session that carries a stashed
          // [no-return-address] dead letter (auto-bind lands here too via its
          // own routes update; this leg covers the save/REST face).
          logger.info(s"weixin-ilink bridge: routing table rebuilt — ${bound.size} sender id(s) bound to sessions") *>
            bound
              .collect {
                case (sender, sid) if previous.get(sender) != Some(sid) => redeliverDropped(sender, sid)
              }
              .toList
              .foldLeft(IO.unit)((acc, leg) => acc *> leg)
        }
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
   * the remembered products ride [[flushTurnEnd]] (媒体合并批, 2026-10-22:
   * text first, consecutive images grouped into one group send, a short tail
   * riding the first group as its caption — ADDITIVELY: the text reply is
   * never suppressed).
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
          // 媒体合并批（2026-10-22，台账 #756）：轮终合流收口进 [[flushTurnEnd]]
          // ——文字先行、短尾文字依附首个图片组作 caption、连续图片并组成组
          // （作者令「等一下文字再一起发 不要空发图片和文件」「一起发图片…
          // 组合一起发送」）。逐段即时批（2026-10-10）的原状保持：轮终只补发
          // 「最后一段尾巴」，边界已把前段逐条推走 ⇒ 天然无整轮重发；pop
          // multi-group fix 的「每张组卡都发完、组序 = toolEnd 序」由 runs 的
          // payload 序接管。
          flushTurnEnd(sessionId, text, richs)
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
          // bind-transactional 批 (2026-10-20): the drop is no longer one quiet
          // line for every session. The discriminator is [[servedRef]]:
          //   - a SERVED session (this seam injected into it — the incident
          //     shape: default-session fallback injected, no route back) lost a
          //     USER-VISIBLE reply: ERROR-level escalation + a health-face
          //     strike (channels[].health → the panel marks the channel) + the
          //     text stashed for redelivery at the sender's first registration.
          //   - a never-served session is not weixin traffic at all
          //     (dispatchAgentEvent fans every session to every plugin) — debug,
          //     exactly as quiet as the noise deserves.
          servedRef.get.flatMap { served =>
            if served.contains(sessionId) then
              noteNoReturnAddress(sessionId, text) *>
                logger.error(
                  s"weixin-ilink bridge: reply for session $sessionId dropped [$ReasonNoReturnAddress] — " +
                    "no sender id is bound to this session, so the reply has no iLink target; " +
                    "escalated: user-visible reply loss (health strike recorded, text stashed for redelivery)"
                )
            else
              logger.debug(
                s"weixin-ilink bridge: reply for session $sessionId dropped [$ReasonNoReturnAddress] — " +
                  "session was never served by this channel, no iLink target"
              )
          }
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
              // bind-transactional 批：成功同时清除该会话的健康告警（回绿）。
              segmentFailNoted.update(_ - sessionId) *> clearNoReturnStrike(sessionId)
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

  // ───────── bind-transactional 批: the strike / dead-letter / redelivery legs ─────────

  /**
   * The escalated [no-return-address] drop: ONE health strike written to the
   * channel-agnostic face (`<root>/tmp/social-health/weixin-ilink.json`,
   * surfaced as `channels[].health` by `SocialChannels.channelsJson` — the
   * panel marks the channel) and the text stashed as the session's dead letter
   * (latest wins). TOTAL: a strike write that fails is a WARN — the stash and
   * the escalated log line are the load-bearing parts.
   */
  private def noteNoReturnAddress(sessionId: String, text: String): IO[Unit] =
    droppedRef.update(m => m.updated(sessionId, DroppedReply(text))) *>
      writeStrike(sessionId).handleErrorWith(e =>
        logger.warn(s"weixin-ilink bridge: health strike write failed (${e.getClass.getSimpleName}) — contained")
      )

  /**
   * Read-modify-write of the channel health face; contained, never raises.
   * `reason` defaults to [no-return-address]; the bridge-dead detector
   * （入站图片必达批，2026-10-24）reuses the same face under [bridge-session-dead]
   * (keyed by the SENDER id — there is no one session to blame).
   */
  private def writeStrike(sessionId: String, reason: String = ReasonNoReturnAddress): IO[Unit] =
    IO.blocking {
      val now = System.currentTimeMillis()
      val previous = SocialChannels
        .channelHealth(root, Name)
        .flatMap(_.hcursor.downField("strikes").as[List[Json]].toOption)
        .getOrElse(Nil)
      val (matching, rest) = previous.partition(s =>
        s.hcursor.downField("sessionId").as[String].toOption.contains(sessionId) &&
          s.hcursor.downField("reason").as[String].toOption.contains(reason)
      )
      val count = matching.headOption
        .flatMap(_.hcursor.downField("count").as[Long].toOption)
        .getOrElse(0L) + 1L
      val firstAt = matching.headOption
        .flatMap(_.hcursor.downField("firstAt").as[Long].toOption)
        .getOrElse(now)
      val strike = Json.obj(
        "reason" -> reason.asJson,
        "sessionId" -> sessionId.asJson,
        "count" -> count.asJson,
        "firstAt" -> firstAt.asJson,
        "lastAt" -> now.asJson
      )
      val health = Json.obj(
        "channel" -> Name.asJson,
        "updatedAt" -> now.asJson,
        "strikes" -> (strike :: rest).asJson
      )
      SocialChannels.writeChannelHealth(root, Name, health)
    }.flatMap {
      case true => IO.unit
      case false => logger.warn("weixin-ilink bridge: health strike could not be written (face degraded)")
    }

  /**
   * The session replied successfully — its strike of the given reason clears
   * (the channel face returns to green for this key). Contained. The
   * bridge-dead detector uses the same leg to clear [bridge-session-dead] on
   * recovery (sender-keyed).
   */
  private def clearNoReturnStrike(sessionId: String, reason: String = ReasonNoReturnAddress): IO[Unit] =
    IO.blocking {
      val now = System.currentTimeMillis()
      val previous = SocialChannels
        .channelHealth(root, Name)
        .flatMap(_.hcursor.downField("strikes").as[List[Json]].toOption)
        .getOrElse(Nil)
      val rest = previous.filterNot(s =>
        s.hcursor.downField("sessionId").as[String].toOption.contains(sessionId) &&
          s.hcursor.downField("reason").as[String].toOption.contains(reason)
      )
      if rest == previous then false // nothing to clear — no write
      else
        SocialChannels.writeChannelHealth(
          root,
          Name,
          Json.obj("channel" -> Name.asJson, "updatedAt" -> now.asJson, "strikes" -> rest.asJson)
        )
    }.handleError(_ => false)
      .flatMap {
        case true => IO.unit
        case false => IO.unit // nothing cleared, or the face degraded — either way quiet
      }

  /**
   * 入站图片必达批（2026-10-24）· 桥会话失效探测器（出站面）。包裹在
   * [[outbound]] 上：每次文本发送的结果喂给纯判定
   * [[WeixinIlinkBridgePlugin.bridgeDeadStreak]]——成功回零（双 streak 归零时
   * 复位告警闸、清 [bridge-session-dead] strike ⇒ 通道面回绿）；502+ret=-2 累
   * 进；阈值穿越且未告警过 ⇒ 一次性升级（[[alertBridgeDead]]）。其它失败中
   * 立不动。自身 TOTAL：任何一步失败都被包含，绝不改变发送结果语义。
   *
   * 断桥预警降级批（2026-10-25，作者令）：瞬态拒绝（抖动窗内恢复）只落
   * 结构化 log——逐条拒绝已有逐条 WARN（code/reason），此处补齐「恢复时间」：
   * 全愈瞬间一条 INFO（累计拒数 + 抖动窗时长 + 末次 ret 码）。任何路径都
   * 不再向主会话注入通知（[[alertBridgeDead]] 的注入腿已整个退役）；作者
   * 面只有通道卡自身的健康 strike（状态自解释）。
   */
  private def noteSendHealth(sender: String, res: WeixinOutboundAdapter.SendResult, mediaLeg: Boolean): IO[Unit] =
    val fire =
      bridgeDeadRef.modify { case ((tStreak, mStreak), alerted, flap) =>
        val next =
          WeixinIlinkBridgePlugin.bridgeDeadStreak(if mediaLeg then mStreak else tStreak, res.ok, res.code, res.reason)
        val (tNext, mNext) = if mediaLeg then (tStreak, next) else (next, mStreak)
        val shouldFire =
          (tNext >= WeixinIlinkBridgePlugin.BridgeDeadThreshold || mNext >= WeixinIlinkBridgePlugin.BridgeDeadThreshold) &&
            !alerted
        // 全愈 = 双 streak 同时归零（桥整体回活）：此时告警闸 re-arm。
        val bothGreen = tNext == 0 && mNext == 0
        val newAlerted = if bothGreen then false else alerted || shouldFire
        // 抖动窗账本：ret=-2 累进（0→1 开窗）开/续窗；全愈即关窗（关窗时把
        // 刚关的窗带回给日志腿）。中立失败不动窗。
        val incremented = tNext > tStreak || mNext > mStreak
        val flapNext =
          if incremented then
            Some(
              flap.fold(RetFlap(System.currentTimeMillis(), 1, res.code, res.reason))(f =>
                f.copy(refusals = f.refusals + 1, lastCode = res.code, lastReason = res.reason)
              )
            )
          else if bothGreen then None
          else flap
        val closedFlap = if bothGreen then flap else None
        (
          ((tNext, mNext), newAlerted, flapNext),
          (if shouldFire then Some(math.max(tNext, mNext)) else None, alerted, bothGreen, closedFlap)
        )
      }
    fire.flatMap { case (alert, wasAlerted, bothGreen, closedFlap) =>
      alert.fold(IO.unit)(streak => alertBridgeDead(sender, streak)) *>
        closedFlap.fold(IO.unit) { f =>
          val durSec = math.max(0, (System.currentTimeMillis() - f.startMs) / 1000)
          logger.info(
            s"weixin-ilink bridge: ret=-2 flap recovered — ${f.refusals} refused send(s) over ${durSec}s " +
              s"(last code=${f.lastCode} reason=${f.lastReason.take(160)}) — transient; logged only, the main session is not notified"
          )
        } *>
        (if res.ok && wasAlerted && bothGreen then clearNoReturnStrike(sender, WeixinIlinkBridgePlugin.ReasonBridgeDead)
         else IO.unit)
    }

  end noteSendHealth

  /**
   * 断桥预警降级批（2026-10-25，作者令）: the ONE-TIME sustained-hard-failure
   * escalation — WARN-level log (downgraded from the 2026-10-24 ERROR: both
   * of that night's firings were transient-flap false alarms) + the
   * [bridge-session-dead] health strike on the channel face (the card
   * explains itself — the ONLY author-facing surface). 🔴 NO session
   * injection of any kind: the ⚠️「桥会话疑似失效」re-bind notice
   * ([[WeixinIlinkBridgePlugin.bridgeDeadNotice]], retired with this batch)
   * fired on transient flaps and interrupted the main session twice for
   * nothing. A transient flap recovers on its own and must stay invisible to
   * the session; even a sustained death is a log + card-strike matter — the
   * author watching the channel card sees the strike and re-binds when he
   * chooses. TRUE re-login still needs one QR scan; the detector keeps the
   * card truthful, it does not interrupt. Every leg contained.
   */
  private def alertBridgeDead(sender: String, streak: Int): IO[Unit] =
    logger.warn(
      s"weixin-ilink bridge: bridge session DEAD — $streak consecutive sends refused (502 ret=-2) with no recovery " +
        "beyond the " + WeixinIlinkBridgePlugin.BridgeDeadThreshold + "-send window — inbound is likely equally stalled; " +
        "a QR re-bind on the channel card is required (health strike written to the card face; the main session is NOT notified)"
    ) *>
      writeStrike(sender, WeixinIlinkBridgePlugin.ReasonBridgeDead).handleErrorWith(e =>
        logger.warn(
          s"weixin-ilink bridge: bridge-dead health strike write failed (${e.getClass.getSimpleName}) — contained"
        )
      )

  /**
   * The redelivery leg: the sender's FIRST registration for this session (the
   * auto-bind arm and the rebuilt routing table both land here) sends the
   * stashed dead letter with the [[RedeliveryPrefix]] mark. The letter is
   * consumed on the attempt — a failed send keeps the escalation face (the
   * strike) and drops the letter (no retry loop; the user just messaged in,
   * the conversation carries). TTL-judged at consumption: an expired letter
   * dies quietly with one INFO.
   */
  private def redeliverDropped(sender: String, sessionId: String): IO[Unit] =
    droppedRef.modify(m => (m - sessionId, m.get(sessionId))).flatMap {
      case None => IO.unit
      case Some(letter) =>
        val age = System.currentTimeMillis() - letter.droppedAt
        if age > DroppedReplyTtl.toMillis then
          logger.info(
            s"weixin-ilink bridge: stale dead letter for session $sessionId (${age / 1000}s old) — dropped, not redelivered"
          )
        else
          logger.info(
            s"weixin-ilink bridge: redelivering a [${ReasonNoReturnAddress}] dead letter to sender $sender " +
              s"(session $sessionId regained a return address)"
          ) *>
            outbound(sender, RedeliveryPrefix + letter.text).flatMap { res =>
              if res.ok then IO.unit
              else logger.warn(s"weixin-ilink bridge: redelivery send failed code=${res.code} reason=${res.reason}")
            }
    }

  // ───────────── 媒体合并批 (2026-10-22): the turn-end flush + runs ─────────────

  /**
   * One classified deliverable of the turn's remembered media, in payload
   * order (媒体合并批): consecutive image items fold into an [[ImageRun]] —
   * ONE group send per contiguous image stretch (作者令「一起发图片…组合一起
   * 发送」) — and every other classified item stays its own [[ItemRun]] on its
   * pre-existing per-item leg (Document → the file leg, Unknown → the
   * long-shot / archive / log faces, the pop-social-files + pop-file-leg
   * faces verbatim).
   */
  private sealed trait MediaRun
  private final case class ImageRun(paths: List[os.Path]) extends MediaRun
  private final case class ItemRun(path: os.Path, kind: RichKind) extends MediaRun

  /**
   * Classify every remembered item ONCE — the SAME [[RichKind.of]] rule the
   * pre-group per-item loop applied (same content-less read, same exists
   * probe, same authority tables) — and fold consecutive images into runs.
   * The fold is PER 组卡: two Pop cards never merge into one group even when
   * adjacent (the card boundary is the author's own grouping unit — 组卡序 =
   * toolEnd 序 stays visible on the wire); runs of different cards concatenate.
   */
  private def mediaRuns(richs: List[RichProduct]): IO[List[MediaRun]] =
    IO {
      richs.flatMap { rich =>
        rich.media
          .foldLeft(List.empty[MediaRun]) { (runs, p) =>
            val kind = RichKind.of(
              content = "",
              path = Some(p.toString),
              exists = Try(os.isFile(p)).getOrElse(false),
              documentExtensions = documentExtensions
            )
            (runs.lastOption, kind) match
              case (Some(ImageRun(ps)), RichKind.Image) => runs.init :+ ImageRun(ps :+ p)
              case (_, RichKind.Image) => runs :+ ImageRun(List(p))
              case (_, other) => runs :+ ItemRun(p, other)
          }
      }
    }

  /**
   * The turn-end flush (媒体合并批, 2026-10-22, 台账 #756 — 作者令「改动一下
   * 微信上发文件和图片 如果没有文字内容 要等一下文字再一起发 不要空发图片和
   * 文件」「微信上一起发图片好像不会组合一起发送 而是分开发送」). The old
   * 媒体先行、正文殿后 order is REVERSED, and the media itself is grouped.
   * Three faces on one pacer chain (the chain order IS the wire order):
   *
   *   - 文字先行 (sequential form): the tail text goes out FIRST, the media
   *     follows — the text never lags the media it describes;
   *   - 图文同发 (caption form): a SHORT tail (≤ [[WeixinAskLeg.FullTextThreshold]],
   *     the same first tier the text leg delivers under) rides INSIDE the
   *     first image group as its caption — one WeChat message carrying
   *     text+images — when the turn's first deliverable is a ≥2-image group.
   *     A long-text tail keeps its own 摘要+拉取 flow and goes out first,
   *     caption-less;
   *   - 成组 (group form): each [[ImageRun]] of ≥2 delivers as ONE group call;
   *     single images and non-image items keep their exact per-item legs.
   *
   * The merge window itself needs NO timer: media is remembered at `toolEnd`
   * and only ever flushed here at `done` — the turn end IS the window's close
   * (媒体等满整轮；纯媒体轮照发，无文字可等也不空等). A session with no bound
   * sender keeps the text leg (its [no-return-address] machinery) and drops
   * the media exactly as the pre-group leg did.
   */
  private def flushTurnEnd(sessionId: String, text: String, richs: List[RichProduct]): IO[Unit] =
    if richs.isEmpty then
      val textLeg = if text.trim.isEmpty then IO.unit else pacer.enqueue(reply(sessionId, text))
      textLeg
    else
      routesRef.get.flatMap { routes =>
        routes.collectFirst { case (sender, sid) if sid == sessionId => sender } match
          case None =>
            // No return address: the text leg owns the WARN/strike/redelivery
            // machinery; the media passes (the pre-group leg's own rule — the
            // text reply logs the same WARN for this session).
            val textLeg = if text.trim.isEmpty then IO.unit else pacer.enqueue(reply(sessionId, text))
            textLeg
          case Some(sender) =>
            // CARD 产品的 v1 边界日志（内容承载卡 HTML，媒体腿不可转码——正文
            // 腿带回合）：runs 收口后空 media 的卡产品不再进逐 item 循环，日志
            // 就地保留一行。
            richs.foreach { rich =>
              if RichKind.isCardContent(rich.content) then
                logger.info(
                  "weixin-ilink bridge: rich CARD observed — the weixin v1 media leg carries image/file only " +
                    "(a card payload is not transcodable); the text reply carries the turn"
                )
            }
            val plain = nebflow.social.outbound.OutboundText.toPlain(text)
            mediaRuns(richs).flatMap { runs =>
              val captionMode =
                plain.trim.nonEmpty && plain.length <= WeixinAskLeg.FullTextThreshold &&
                  runs.headOption.exists {
                    case ImageRun(ps) => ps.size >= 2
                    case ItemRun(_, _) => false
                  }
              val textLeg =
                if captionMode || text.trim.isEmpty then IO.unit
                else pacer.enqueue(reply(sessionId, text))
              val mediaLeg =
                pacer.enqueue(deliverRuns(sender, runs, caption = if captionMode then Some(plain) else None))
              textLeg *> mediaLeg
            }
      }

  /**
   * The turn's media runs, in payload order, inside ONE pacer slot — the whole
   * sequence sits between the text leg and whatever the next turn enqueues,
   * so a multi-run turn can never interleave with another flush. The caption
   * (caption mode) rides the FIRST [[ImageRun]] and is spent there. Every run
   * leg is raise-contained (the pop multi-group fix's per-item guard,
   * promoted to per-run): one run's unexpected fault degrades to its own
   * visible line and the remaining runs keep going.
   */
  private def deliverRuns(sender: String, runs: List[MediaRun], caption: Option[String]): IO[Unit] =
    runs
      .foldLeft((IO.unit: IO[Unit], caption)) { case ((acc, cap), run) =>
        val leg = run match
          case ImageRun(single :: Nil) =>
            // A single image keeps the pre-group face byte-identical (the
            // [图片发送失败 code=…] 保底 wording included) — grouping is for
            // stretches, not for solos.
            deliverMedia(sender, WeixinOutboundAdapter.WeixinMedia.image(single.toString), "image").void
          case ImageRun(ps) => deliverImageGroup(sender, ps, cap)
          case ItemRun(p, kind) => deliverClassifiedItem(sender, p, kind)
        val spent = run match
          case ImageRun(single :: Nil) => cap
          case ImageRun(_) => None
          case ItemRun(_, _) => cap
        (acc *> leg.handleErrorWith(e => deliverRunFault(sender, runLabel(run), e)), spent)
      }
      ._1

  /**
   * The visible label a faulted run degrades under: a group shows its size,
   * a solo item its file name (the [未送达] line's subject).
   */
  private def runLabel(run: MediaRun): String = run match
    case ImageRun(ps) => if ps.sizeIs == 1 then ps.head.last else s"图片组(${ps.size}张)"
    case ItemRun(p, _) => p.last

  /**
   * One run's unexpected RAISE degrades to ONE visible line (the pop
   * multi-group fix's containment arm, promoted to per-run); never rethrows —
   * the remaining runs and the turn keep going.
   */
  private def deliverRunFault(sender: String, label: String, e: Throwable): IO[Unit] =
    for
      _ <- IO(
        logger.warnSync(
          s"weixin-ilink bridge: rich run delivery raised for $label " +
            s"(${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}) — degrading to a visible line"
        )
      )
      r2 <- outbound(sender, s"[未送达] $label — 这一项发送时出现异常，其余内容照常")
      _ <-
        if r2.ok then IO.unit
        else
          IO(
            logger.warnSync(
              s"weixin-ilink bridge: degrade notice send failed code=${r2.code} reason=${r2.reason}"
            )
          )
    yield ()

  /**
   * The non-image per-item legs, verbatim from the pre-group per-item loop
   * (the pop-social-files + pop-file-leg faces): Document → the file leg
   * ([[deliverFileMedia]]: dedup + size gate + `file_path`); Unknown → the
   * long-shot leg for convertible files ([[deliverLongShotMedia]]), the file
   * leg for pushable archives, else the v1 log-only boundary. The Image and
   * Card arms are unreachable here ([[mediaRuns]] folds images into
   * [[ImageRun]]; per-item classification reads no content, so no Card ever
   * arises) — the total-function backstops stay explicit.
   */
  private def deliverClassifiedItem(sender: String, p: os.Path, kind: RichKind): IO[Unit] =
    kind match
      case RichKind.Document =>
        // pop-file-leg batch（2026-10-21 作者令「pop 把这个文件发给我，
        // 就自动传过来了」）：文件腿直推，前置去重 + 体积闸
        // （[[deliverFileMedia]]）。PDF/办公件本就在文档权威表——
        // 2026-10-09 实测的「文件消息零到达」被本批作者令接管（当日
        // 手动 curl 文件腿实达），直推为口径。
        deliverFileMedia(sender, p)
      case RichKind.Unknown(reason) =>
        // pop-social-files batch（2026-10-20 作者令）：文件类可转换形态
        // （md/markdown/html/htm 且原件在盘上）→ 单张长图 PNG 走 image_path 腿
        // + 原件路径伴行注（[[deliverLongShotMedia]]，同字节重复 Pop 去重抑制；
        // 转不动降文本卡）。pop-file-leg batch（2026-10-21 作者令）：压缩包类
        // （[[FileLeg.ArchiveExtensions]]）→ 文件腿直推。其余保持 v1 边界。
        if Try(os.isFile(p)).getOrElse(false) && FileLongShot.isConvertible(p) then deliverLongShotMedia(sender, p)
        else if Try(os.isFile(p)).getOrElse(false) && FileLeg.isPushable(p) then deliverFileMedia(sender, p)
        else
          logger.info(
            s"weixin-ilink bridge: rich item not carriable ($reason) — the text reply carries the turn"
          )
      case RichKind.Card => IO.unit // unreachable on this branch — the total-function backstop
      case RichKind.Image => IO.unit // unreachable: images fold into ImageRun

  /**
   * One image-RUN send (媒体合并批): the whole contiguous image stretch rides
   * ONE group call — N images + the optional caption → ONE WeChat message
   * (the side-car probes the native multi-image sendmessage and falls back to
   * the official per-item sequence on any refusal, so a delivered reading
   * needs no engine-side retry and no engine-side fallback logic). Failure
   * containment: the turn text never rides a media failure — a FAILED
   * captioned group recovers its caption through the proven text seam FIRST
   * (作者令：文字不许被媒体失败连坐), then ONE count-aware 保底 notice goes
   * out; an uncaptioned failure puts out the notice alone. Never a raise,
   * never an engine retry (design §0.4 unchanged).
   */
  private def deliverImageGroup(sender: String, paths: List[os.Path], caption: Option[String]): IO[Unit] =
    outboundGroup(sender, paths.map(_.toString), caption)
      // The group leg is TOTAL: an unexpected raise becomes a synthetic failed
      // reading so the caption-recovery + notice arm below always runs (the
      // turn text must never die with a media fault, raise or refusal alike).
      .handleErrorWith(e =>
        IO(
          logger.warnSync(
            s"weixin-ilink bridge: image group delivery raised (${e.getClass.getSimpleName}: " +
              s"${Option(e.getMessage).getOrElse("")}) — degrading to the notice face"
          )
        ).as(
          WeixinOutboundAdapter.SendResult(false, false, None, -1, s"contained raise: ${e.getClass.getSimpleName}")
        )
      )
      .flatMap {
        case res if res.ok =>
          IO(
            logger.info(
              s"weixin-ilink bridge: image group delivered to sender $sender (${paths.size} images, id=${res.messageId.getOrElse("-")})"
            )
          )
        case res =>
          val recover = caption.fold(IO.unit) { t =>
            deliverText(sender, t).flatMap { r =>
              if r.ok then IO.unit
              else
                IO(
                  logger.warn(
                    s"weixin-ilink bridge: caption recovery send failed code=${r.code} reason=${r.reason}"
                  )
                )
            }
          }
          val line = s"[${paths.size} 张图片发送失败 code=${res.code}] ${res.reason}"
          logger.warn(
            s"weixin-ilink bridge: image group send failed (${paths.size} images) code=${res.code} reason=${res.reason} " +
              "— recovering the caption through the text seam, then the text notice"
          ) *> recover *>
            outbound(sender, line).flatMap { r2 =>
              if r2.ok then IO.unit
              else
                logger.warn(
                  s"weixin-ilink bridge: degrade notice send failed code=${r2.code} reason=${r2.reason}"
                )
            }
      }

  /**
   * 文件类长图腿（pop-social-files batch, 2026-10-20；作者裁定「文件类产物投递
   * 社交腿=md/html→单张长图 PNG，转不动降文本卡，去重防轰炸」）：
   *
   *   ⓪ 去重 —— 同字节指纹（路径+size+mtime）已在账 ⇒ 零发送、一行日志（前次
   *      推送成功才落账，失败不落账 ⇒ 下次 Pop 照常重试）；
   *   ① 转换主腿 —— 原件经仓内 node playwright 转单张长图 PNG（[[FileLongShot]]，
   *      30s 预算，产物进本数据根的临时面）→ 既有 image_path 腿裸发
   *      （[[outboundMedia]]，失败不得先发用户可见字）→ 成功后伴行注
   *      （[[RichDegrade.originalNote]]，桌面端可打开原件）+ 落去重账；
   *   ② 渠道拒绝 —— 与 [[deliverMedia]] 的图片保底同款文案（`[图片发送失败
   *      code=…]`），不落账；
   *   ③ 转不动（渲染件不可用/超时/产物非法）⇒「文件名+本地路径+一句话摘要」
   *      文本卡（[[RichDegrade.unconverted]]）。
   *
   * 分类层零改动：md/html 仍判 Unknown，本腿只在 Unknown 臂被读取。原
   * deliverWebPageMedia（html 文件直发、截图兜底）随本腿整体退役。
   */
  private def deliverLongShotMedia(sender: String, p: os.Path): IO[Unit] =
    IO(FileLongShot.Dedup.keyOf(p)).flatMap {
      case Some(key) if FileLongShot.Dedup.seen(key) =>
        IO(
          logger.info(
            s"weixin-ilink bridge: long-shot duplicate suppressed for $p — same bytes already pushed (dedup)"
          )
        )
      case keyOpt =>
        val out = root / "tmp" / "outbound-render" /
          s"file-${java.util.UUID.randomUUID().toString.take(12)}.png"
        longshot.shot(p, out).flatMap {
          case Right(png) =>
            outboundMedia(sender, WeixinOutboundAdapter.WeixinMedia.image(png.toString)).flatMap {
              case res if res.ok =>
                keyOpt.foreach(FileLongShot.Dedup.record)
                outbound(sender, RichDegrade.originalNote(p)).flatMap { r2 =>
                  if r2.ok then IO.unit
                  else
                    IO(
                      logger.warnSync(
                        s"weixin-ilink bridge: long-shot note send failed code=${r2.code} reason=${r2.reason}"
                      )
                    )
                } *> IO(
                  logger.info(
                    s"weixin-ilink bridge: file long-shot delivered to sender $sender (id=${res.messageId.getOrElse("-")})"
                  )
                )
              case res =>
                val line = s"[图片发送失败 code=${res.code}] ${res.reason}"
                logger.warn(
                  s"weixin-ilink bridge: long-shot image send failed code=${res.code} reason=${res.reason} " +
                    "— degrading to a text notice"
                ) *>
                  outbound(sender, line).flatMap { r2 =>
                    if r2.ok then IO.unit
                    else
                      logger.warn(
                        s"weixin-ilink bridge: degrade notice send failed code=${r2.code} reason=${r2.reason}"
                      )
                  }
            }
          case Left(reason) =>
            logger.info(
              s"weixin-ilink bridge: file long-shot unavailable for $p ($reason) — degrading to the text card"
            ) *>
              IO(FileLongShot.summarize(p)).flatMap { summary =>
                outbound(sender, RichDegrade.unconverted(p, summary)).flatMap { r2 =>
                  if r2.ok then IO.unit
                  else
                    logger.warn(
                      s"weixin-ilink bridge: text card send failed code=${r2.code} reason=${r2.reason}"
                    )
                }
              }
        }
    }

  /**
   * 文件腿直推（pop-file-leg batch, 2026-10-21 作者令「Pop 文件卡自动过桥」）：
   * Document 臂（PDF/办公件——文档权威表）与 Unknown 臂的压缩包分支
   * （[[FileLeg.ArchiveExtensions]]）共用的投递面，三道闸依序：
   *
   *   ⓪ 去重 —— [[FileLongShot.Dedup]] 同一进程级台账（键 = 路径+size+mtime，
   *      与长图腿共用一个指纹面）：已在账 ⇒ 零发送、一行日志；**发送成功才落
   *      账**（超限文本卡发成功也算交付义务已了，落账——同件再 Pop 不再重复
   *      轰炸；文件重生成 ⇒ 新键照推）。
   *   ① 体积闸 —— 超过 [[FileLeg.MaxBytes]]（与 sidecar 发送腿 sanity 默认
   *      25MB 对齐；iLink 文件消息无公开权威上限，依据见 [[FileLeg]] 文件头）
   *      ⇒ 不发起必被 400 拒绝的发送，降级为「[文件过大] + 文件名 + 原件路径
   *      + 控制台取件指引」文本卡（[[RichDegrade.oversizeFile]]）。
   *   ② 发送 —— 既有 file_path 腿（[[outboundMedia]]）+ [[deliverMedia]] 同款
   *      保底（`[文件「x」发送失败 code=…]`），失败不落账（下次 Pop 照常重试）。
   */
  private def deliverFileMedia(sender: String, p: os.Path): IO[Unit] =
    IO(FileLongShot.Dedup.keyOf(p)).flatMap {
      case Some(key) if FileLongShot.Dedup.seen(key) =>
        IO(
          logger.info(
            s"weixin-ilink bridge: file-leg duplicate suppressed for $p — same bytes already pushed (dedup)"
          )
        )
      case keyOpt =>
        val oversize: Option[String] = FileLeg.sizeOf(p) match
          case Some(n) if n > FileLeg.MaxBytes =>
            Some(RichDegrade.oversizeFile(p, Some(n), FileLeg.MaxBytes))
          case _ => None
        oversize match
          case Some(card) =>
            logger.info(
              s"weixin-ilink bridge: file-leg oversize gate for $p (cap ${FileLeg.MaxBytes}B) — degrading to the text card"
            ) *>
              outbound(sender, card).flatMap { r =>
                // 文本卡发成功 = 交付义务已了（取件指引已到）⇒ 落账防重复轰炸；
                // 发失败不落账（下次照常告知）。
                if r.ok then
                  keyOpt.foreach(FileLongShot.Dedup.record)
                  IO.unit
                else
                  logger.warn(
                    s"weixin-ilink bridge: oversize text card send failed code=${r.code} reason=${r.reason}"
                  )
              }
          case None =>
            deliverMedia(sender, WeixinOutboundAdapter.WeixinMedia.file(p.toString), "file").flatMap {
              case res if res.ok =>
                keyOpt.foreach(FileLongShot.Dedup.record)
                IO.unit
              case _ => IO.unit // deliverMedia already logged + put out the 保底 notice
            }
        end match
    }

  /**
   * One media send with the 保底 degrade: a FAILED media send puts out ONE
   * text notice through the SAME text seam (contained, never a raise, never a
   * retry — the outbound leg's own containment rule; the notice names the
   * stable code + reason vocabulary so the recipient can act on it). Returns
   * the reading so [[deliverFileMedia]] can record dedup on success only.
   */
  private def deliverMedia(
    sender: String,
    media: WeixinOutboundAdapter.WeixinMedia,
    kindLabel: String
  ): IO[WeixinOutboundAdapter.SendResult] =
    outboundMedia(sender, media).flatMap { res =>
      if res.ok then
        IO(
          logger.info(
            s"weixin-ilink bridge: rich media delivered to sender $sender kind=$kindLabel (id=${res.messageId.getOrElse("-")})"
          )
        ).as(res)
      else
        // 通知面文件名取段前先归一反斜杠：Windows 原生路径 split('/') 切不动
        // （通知会带全路径），归一后两种检出形态同取末段。
        val name = media.imagePath.orElse(media.filePath).map(_.replace('\\', '/').split('/').last).getOrElse(kindLabel)
        val line =
          if kindLabel == "image" then s"[图片发送失败 code=${res.code}] ${res.reason}"
          else s"[文件「$name」发送失败 code=${res.code}] ${res.reason}"
        logger.warn(
          s"weixin-ilink bridge: rich media send failed kind=$kindLabel code=${res.code} reason=${res.reason} " +
            "— degrading to a text notice"
        ) *>
          outbound(sender, line)
            .flatMap { r2 =>
              if r2.ok then IO.unit
              else logger.warn(s"weixin-ilink bridge: degrade notice send failed code=${r2.code} reason=${r2.reason}")
            }
            .as(res)
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
   * The outbound image-GROUP send seam (媒体合并批, 2026-10-22): `(to, images,
   * caption) → reading` — N absolute image paths + the optional caption text,
   * one contained reading for the whole batch (the side-car owns the
   * group-probe + official per-item fallback, so `delivered` needs no
   * caller-side fallback).
   */
  type SendGroup = (String, List[String], Option[String]) => IO[WeixinOutboundAdapter.SendResult]

  /**
   * The outbound drop reason for a session with no bound sender: stable
   * string, safe to reconcile from a log line.
   */
  val ReasonNoReturnAddress = "no-return-address"

  // ───────── bind-transactional 批 (2026-10-20): the escalation face ─────────

  /** One stashed reply that died on [no-return-address] (the dead letter). */
  final case class DroppedReply(text: String, droppedAt: Long = System.currentTimeMillis())

  /** The redelivery mark prepended to a stashed reply (补投). */
  val RedeliveryPrefix = "[补投] "

  /**
   * A dead letter older than this is dropped at redelivery time, not sent —
   * a half-hour-old reply has a good chance of being stale noise.
   */
  val DroppedReplyTtl: FiniteDuration = 30.minutes

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
   * hands the pointer to the unified seam (媒体统一适配批 2026-10-20) — the
   * 「已存至」 wording ([[WeixinIlinkBridgePlugin.mediaLine]]) survives as the
   * Feishu leg's injection line and the frontend's legacy-row recognizer.
   */
  final case class Inbound(
    senderId: Option[String],
    text: Option[String],
    messageId: Option[String] = None,
    mediaPath: Option[String] = None,
    mediaKind: Option[String] = None,
    mediaName: Option[String] = None,
    mediaMime: Option[String] = None,
    /**
     * 入站图片必达批（2026-10-24）: the side-car's AUDIT fields. `mediaError`
     * = an in-scope media item EXISTED but did NOT land (download/decrypt/
     * timeout/oversize/no-CDN-ref/no-key/no-account; ≤240B reason) — the
     * engine WARNs and receipts the author instead of letting a placeholder
     * label ride as if nothing happened. `mediaExtra` = further in-scope
     * items the side-car's single-item contract did NOT deliver. Both are
     * OPTIONAL on the wire (old side-cars omit them ⇒ nothing to receipt).
     */
    mediaError: Option[String] = None,
    mediaExtra: Int = 0
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

  // ───────── 入站图片必达批（2026-10-24）: the bridge-dead detector ─────────
  //
  // 2026-10-24 事故面：桥会话态服务器侧损坏（sendmessage 一律 502
  // ret=-2「prepare failed」，18 小时数百条回复全灭），引擎侧只有逐条 WARN、
  // 无任何升级/自愈提示——作者毫不知情。此处把「连续被拒」本身变成一等信号：
  // 纯 streak 判定（下方）+ 阈值穿越一次性升级。
  // 断桥预警降级批（2026-10-25，作者令）：升级面改为 WARN 级 log + 通道卡
  // 健康 strike（状态自解释）——不再向主会话注入任何通知。当晚两次「⚠️ 桥
  // 会话疑似失效」均为短暂抖动误报（连续拒收在窗内自愈）；瞬态抖动只落
  // 结构化 log（逐条 WARN + 恢复时带 ret 码与恢复时间的 INFO），持续硬故障
  // 也只升 log + 卡面 strike。真正的「重登」仍须作者扫一次码（QR 在通道卡
  // 上）——卡面 strike 把作者带到扫码动作前，而不是静默吞到地老天荒。

  /** The health-face strike reason for a dead bridge session. */
  val ReasonBridgeDead = "bridge-session-dead"

  /**
   * 连续 ret=-2 拒绝达到该数 ⇒ 持续硬故障判定（设定窗），一次性升级：
   * WARN 级 log + 通道卡健康 strike（不通知主会话——断桥预警降级批）。
   * 窗内恢复（含窗内任意成功复位 streak）= 瞬态抖动：只落结构化 log
   * （恢复 INFO 带累计拒数、抖动时长与末次 ret 码）。side-car 侧每次发送
   * 自带重试，5 连拒已是「不是频控、是态坏」的充分证据（频控短爆在成功
   * 复位语义下达不到这个长度）。
   */
  val BridgeDeadThreshold = 5

  /**
   * The pure streak decision (spec-pinned, PER LEG — revive-p0 批 2026-10-25:
   * text 腿与媒体腿各持 streak，媒体单死不被文本成功清零): a success resets
   * to 0; a 502 whose reason carries `ret=-2` (the iLink server-side refusal —
   * "prepare failed" session-dead shape OR sustained frequency control)
   * increments; any other failure is NEUTRAL (neither proves health nor
   * death — leave the streak).
   */
  def bridgeDeadStreak(prev: Int, ok: Boolean, code: Int, reason: String): Int =
    if ok then 0
    else if code == 502 && Option(reason).exists(_.contains("ret=-2")) then prev + 1
    else prev

  // （断桥预警降级批 2026-10-25：`bridgeDeadNotice`——注入主会话的「⚠️ 桥会话
  // 疑似失效」重绑提示——随注入腿一并退役。作者实证当晚两次触发均为短暂抖动
  // 误报；作者面改为通道卡健康 strike 自解释，主会话零打扰。）

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
          mediaMime = str("mediaMime"),
          mediaError = str("mediaError"),
          mediaExtra = (c.downField("mediaExtra").as[Int].getOrElse(0)).max(0)
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
