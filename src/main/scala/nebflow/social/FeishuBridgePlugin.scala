package nebflow.social

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.bridge.{BridgeContext, BridgeManager, BridgePlugin}
import nebflow.social.outbound.{
  AskPromptCodec,
  PromptAction,
  PromptFallback,
  PromptView,
  RichCall,
  RichFeishuAdapter,
  RichKind,
  RichProduct
}
import nebflow.shared.NebflowLogger
import nebflow.shared.SessionMeta

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Try

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
  /**
   * Outbound send seam (production = [[FeishuBridgePlugin.defaultSend]]); the
   * offline spec injects a recording function so no test touches the network.
   */
  send: FeishuBridgePlugin.Send = FeishuBridgePlugin.defaultSend,
  /**
   * Test seam for the allowlist: None = read from the stored card config at
   * start (production), Some(list) = pinned (offline spec).
   */
  pinnedAllowedOpenIds: Option[List[String]] = None,
  /**
   * Test seam for the credential pair: None = resolve from disk at start
   * (production), Some(c) = pinned (offline spec; the recording send never
   * sees the values — its signature keeps them only for the SDK path).
   */
  pinnedCreds: Option[FeishuCredentials.Credential] = None,
  /**
   * The auto-bind default session (feishu-bind batch, C4). Production re-reads
   * the stored config at each unbound-chat intake — a REST PUT can flip it
   * while the bridge runs, and the auto-bind path is cold (once per new chat),
   * so a fresh read is both always-correct and free. The offline spec pins a
   * function to stay off the real config.
   */
  readDefaultSession: os.Path => Option[String] = root =>
    SocialChannels.defaultSessionId(root, FeishuBridgePlugin.Name),
  /**
   * Rich-content factory seam (richcontent main-link batch, soc483). Production
   * builds the live adapter from the credential resolved for THIS chat; the
   * offline spec injects a recording adapter whose four outbound seams are
   * fakes, so no test ever opens a socket or reads a credential (R-2).
   */
  buildRichAdapter: (FeishuCredentials.Credential, String, String) => RichFeishuAdapter =
    FeishuBridgePlugin.defaultRichAdapter,
  /**
   * The document extension authority handed to [[RichKind.of]] — the endpoint
   * table reached through the existing same-package adapter
   * ([[FeishuOutboundAdapter.defaultTable]]), so no second table exists.
   */
  documentExtensions: Set[String] = FeishuBridgePlugin.defaultDocumentExtensions,
  /**
   * Interactive-card send seam (askuser-remote P1, A-3 拍板: the static 1.0
   * two-button card). Same shape as the text [[send]] seam — production =
   * [[FeishuBridgePlugin.defaultSendCard]] ([[FeishuChannel.sendInteractive]],
   * the 2026-10-01 A9 seam, first caller this batch); the offline spec
   * injects a recorder so no test touches the network.
   */
  sendCard: FeishuBridgePlugin.SendCard = FeishuBridgePlugin.defaultSendCard,
  /**
   * Inbound image fetch seam (feishu-inbound-image batch, 2026-10-11):
   * `(appId, appSecret, region, imageKey) → bytes | reason`. Production =
   * [[FeishuBridgePlugin.defaultFetchImage]] — the bot credential face
   * ([[FeishuChannel.getImage]], `im/v1/images/:image_key`), the SAME stored
   * pair the listener connects with, read through [[FeishuCredentials]]; the
   * SecretsStore storage structure is untouched (a read, never a write). The
   * offline spec injects a fake, so no test opens a socket. A `Left(reason)`
   * names why the bytes could not be obtained — the image arm turns it into
   * an honest session-visible failure line, never a silent drop.
   */
  fetchImage: FeishuBridgePlugin.FetchImage = FeishuBridgePlugin.defaultFetchImage
) extends BridgePlugin:

  private val logger = NebflowLogger.forName("nebflow.social.feishu-bridge")

  import FeishuBridgePlugin.*

  def name: String = FeishuBridgePlugin.Name

  // ── runtime state (all mutation behind IO; one start/stop cycle at a time) ──
  /** chat_id → session id, the routing table rebuilt from SessionMeta.bridges. */
  private[social] val routesRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)

  /** session id → accumulated delta text for the turn in flight. */
  private[social] val pendingRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)

  /**
   * session id → EVERY rich product observed in the turn in flight, in toolEnd
   * order (richcontent main-link batch, soc483; accumulated-list form from the
   * pop multi-group batch, 2026-10-09).
   *
   * The turn's text (`textDelta`) and its artifacts (`toolEnd`) are DIFFERENT
   * root events, and only `toolEnd` carries `content`; so the rich decision has
   * to be remembered between the two. Kept per session and cleared exactly like
   * the buffer — `done` reads + removes it, `interrupted`/`retryStatus` drop it
   * (a torn turn is never a reply). `Nil` means "no rich product this turn",
   * which is the pure-text path and must stay byte-identical.
   *
   * 🔴 Why a LIST (pop multi-group fix): the previous single-slot memory held
   * only the LAST product, so a turn that popped a multi-image group and then
   * any further Pop lost every earlier group's artifacts on the social legs
   * (the desktop stacked cards all rendered; 飞书/微信 zero arrival for the
   * dropped groups — incident 2026-10-09 22:2x). Each Pop call is one 组卡 and
   * every one of them is delivered, in the order the tool results arrived.
   */
  private[social] val richRef =
    cats.effect.Ref.unsafe[IO, Map[String, List[RichProduct]]](Map.empty)

  /**
   * 逐段即时推送批（2026-10-10）：段级推送的节流队列。桌面端一个文本段提交
   * （[[nebflow.gateway.SessionRecorder]] 的 `toolStart` | `roundComplete` |
   * `done` flush 点）即推一条独立渠道消息；段与段之间至少隔 1s、严格按序出网。
   * done 腿 await 自己的排队任务 ⇒ 先于它的段必然全部出网（结构保证轮终不重发）。
   */
  private[social] val pacer = nebflow.social.outbound.SegmentPacer.unsafe()

  /**
   * 逐段即时推送批：失败段的「注明」闸——sessionId 已注明 ⇒ true。同一段
   * 连续失败只注明一次（一条提示消息），任一次发送成功即复位（下一段失败
   * 再注明）。轮终尾段的失败保持既有语义（仅 WARN，不加注——成功用例
   * FB-R9 的单次调用面不变）。
   */
  private[social] val segmentFailNoted =
    cats.effect.Ref.unsafe[IO, Set[String]](Set.empty)

  /**
   * The member allowlist in force; empty = unrestricted (shipped default).
   * The pinned value is the INITIAL state so the gate is armed from
   * construction; start() re-reads the stored card config over it.
   */
  private[social] val allowedRef =
    cats.effect.Ref.unsafe[IO, List[String]](pinnedAllowedOpenIds.getOrElse(Nil))

  /**
   * askuser-remote P1 (A-5 拍板, the ONLY local ask state): requestId → the
   * chat the card was sent to — a routing CACHE, never a second ledger. The
   * ask AUTHORITY is the hub (`listPendingAsks`): an answered/closed/restarted
   * ask simply is not in the hub snapshot any more, so a stale click reads
   * 「已失效」 without this bridge holding a second copy of that truth. The
   * cache exists for one purpose only — the chat-mismatch judge needs the
   * chat the card was SENT to; after a bridge restart the cache is empty and
   * a callback for a still-open hub ask is answered with the judge skipped
   * (documented degrade: the allowlist gate still rules the clicker).
   * Cleared on stop: a torn ask is never answered from a stale cache.
   */
  private[social] val askChatRef =
    cats.effect.Ref.unsafe[IO, Map[String, String]](Map.empty)

  /**
   * Package-visible for the LIVE rig probe (FeishuAskLiveProbe): the rig must
   * arm the context WITHOUT start() — start would open a SECOND long
   * connection alongside the rig's own listener. Visibility only; production
   * arms it in start exactly as before.
   */
  private[social] val ctxRef = cats.effect.Ref.unsafe[IO, Option[BridgeContext]](None)
  @volatile private var listener: Option[FeishuChannel.Listener] = None
  @volatile private var creds: Option[FeishuCredentials.Credential] = pinnedCreds
  @volatile private var region: String = "feishu" // hardcoded default (lark hidden, author 2026-09-25 ④)
  /**
   * Double-start guard: boot-time sync AND `startAll` both call start — the
   * second call must be a no-op, or two WebSocket listeners would open.
   */
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
              IO(
                logger.warn(
                  s"feishu bridge: no usable credential (${FeishuBridgePlugin.render(reason)}) — registered but unconnected"
                )
              )
        yield ()
      body.handleErrorWith(e =>
        IO(logger.error(s"feishu bridge: start failed: ${e.getClass.getSimpleName}: ${e.getMessage}"))
      ) *>
        IO(if listener.isEmpty then starting.set(false)) // failed start ⇒ a later sync may retry

  /**
   * Idempotent teardown — the single exit path (D-2②: the caller owns the
   * exit; here that means the BridgeManager, and stop is safe to call twice).
   */
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
      *> richRef.set(Map.empty) // …and neither is a rich product from a torn turn
      *> askChatRef.set(Map.empty) // askuser-remote P1: a torn ask's routing cache dies with the bridge

  // ───────────────────────────── inbound ─────────────────────────────
  /**
   * The ONE sync→IO boundary: runs on the SDK's receive thread. Exceptions are
   * contained by the dispatcher in [[FeishuChannel]] (they only mark
   * `lastError`), so a throwing injection cannot kill the socket.
   */
  private def runIntake(ctx: BridgeContext, in: FeishuMessage.Inbound): Unit =
    intake(ctx, in).unsafeRunSync()

  private[social] def intake(ctx: BridgeContext, in: FeishuMessage.Inbound): IO[Unit] =
    for
      allowed <- allowedRef.get
      routes <- routesRef.get
      // inbound-parse batch (2026-10-01): the third element is the RENDERED
      // body, not `in.text`. `text` is only ever set for `message_type=text`, so
      // routing on it silently dropped every post/image/file message. `parsed`
      // carries the readable body for every inbound class that HAS a readable
      // body, and a genuinely unknown type still produces the explicit
      // unrecognised-type placeholder instead of vanishing.
      // feishu-inbound-image batch (2026-10-11): `image` left the placeholder
      // class — the parse layer answers `None` for it and the fourth selector
      // element routes it to the IMAGE ARM (fetch by image_key → land under
      // uploads → inject the media line), ahead of the no-body drop.
      body = renderedBody(in)
      _ <- (
        allowed.isEmpty || in.senderId.exists(allowed.contains),
        routes.get(in.chatId),
        body,
        in.messageType == "image"
      ) match
        case (false, _, _, _) =>
          // Fail-closed when the list is non-empty: an absent/unresolvable
          // sender identity is as good as a non-member. Ordered FIRST so the
          // allowlist also rules the auto-bind arm below (C6: only a listed
          // member can mint a binding).
          IO(
            logger.warn(
              s"feishu bridge: message from chat ${in.chatId} dropped — sender is not on the member allowlist"
            )
          )
        case (_, None, _, _) =>
          autoBind(ctx, in)
        case (_, Some(sessionId), None, true) =>
          // The IMAGE ARM (bound chat): the download → land → inject leg. The
          // unrecognised-type placeholder is unreachable here by construction —
          // every outcome is either the landed-file media line or an honest
          // failure line (never silence, never a fabricated body).
          imageArm(ctx, in, sessionId)
        case (_, _, None, _) =>
          IO(
            logger.info(
              s"feishu bridge: message ${in.messageId} (${in.messageType}) has no readable body — nothing injected"
            )
          )
        case (_, Some(sessionId), Some(text), _) =>
          // askucancel 批（2026-10-08）：取消关键字臂（精确匹配，先于注入）——
          // 在册零问 ⇒ 照旧注入（B-2 聊天透传，关键字不拦截不吞）；恰一问 ⇒ 按
          // requestId 精确取消；多问 ⇒ 可读拒绝线（绝不猜）。
          if text.trim == WeixinAskLeg.CancelKeyword then keywordCancel(ctx, sessionId, text, in)
          else
            // askuser-media-split 批：问句文本作答臂（编号 / 问.编 / 0他前缀 /
            // 精确选项 label）——消费则不再注入；非作答形态照旧注入，逐字节不变。
            matchTextAnswer(ctx, sessionId, text).flatMap {
              case true => IO.unit // consumed: an answer forwarded, or a refusal line answered
              case false =>
                logger.info(s"feishu bridge: chat ${in.chatId} -> session $sessionId") *>
                  ctx.injectMessage(sessionId, text, in.senderId, originOf(in))
            }
    yield ()

  /**
   * The ask TEXT answer arm (askuser-media-split batch): a text that answers
   * THIS session's open ask is consumed and forwarded to the hub — the SAME
   * convergence the card click ([[handleCardAction]]) and the weixin text
   * leg ([[WeixinIlinkBridgePlugin.askLegs]]) use. Rules, in order:
   *
   *   · the hub snapshot is the open-ask AUTHORITY: `askUser` frames whose
   *     `sessionId` is this session (mcp cards never match);
   *   · exactly ONE open ask → [[WeixinAskLeg.parseReply]] judges (bare
   *     numbers, `问.编`, the `0`/`他` prefix, and the exact-option-label arm
   *     with case/whitespace tolerance); a validated answer forwards; a
   *     number-shaped refusal answers ONE readable line and is NOT injected
   *     (「超界拒不钳制」);
   *   · SEVERAL open asks (cards coexist per rid — the card protocol is
   *     rid-addressed, so parallel asks are a legal shape here, unlike
   *     weixin's B-3 single slot) → a text that would answer ANY of them is
   *     refused with the ambiguous line pointing back at the buttons (never
   *     guessed, never swallowed); a text that answers NONE is chat;
   *   · ZERO open asks → chat, byte-identical to the pre-arm path.
   *
   * `true` = the text was consumed (an answer or a refusal went out instead
   * of the injection); `false` = fall through to the chat injection.
   */
  private[social] def matchTextAnswer(ctx: BridgeContext, sessionId: String, text: String): IO[Boolean] =
    ctx.listPendingAsks.flatMap { frames =>
      val mine = frames.flatMap { f =>
        val isAsk = f.hcursor.downField("type").as[String].toOption.contains("askUser")
        val inWindow = f.hcursor.downField("sessionId").as[String].toOption.contains(sessionId)
        if isAsk && inWindow then Some(f) else None
      }
      mine match
        case Nil => IO.pure(false)
        case frame :: Nil =>
          val rid = frame.hcursor.downField("requestId").as[String].toOption.map(_.trim).filter(_.nonEmpty)
          val flat = AskPromptCodec.flatten(
            AskPromptCodec.viewsFromItemsJson(frame.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil))
          )
          (rid, flat.views) match
            case (Some(r), vs) if vs.nonEmpty =>
              WeixinAskLeg.parseReply(text, vs) match
                case Right(None) => IO.pure(false) // not ask-shaped at all — chat (B-2 拍板)
                case Right(Some(actions)) =>
                  forwardTextAnswer(ctx, r, sessionId, flat, actions).as(true)
                case Left(reject) =>
                  logger.info(
                    s"feishu bridge: ask text reply refused (${reject.line.take(24)}…) — the ONE line answers it"
                  ) *>
                    reply(sessionId, reject.line).as(true)
            case _ => IO.pure(false) // a frame without a usable rid/question set is not answerable by text
        case many =>
          // 多问歧义定义：对任何一个在册问集构成作答形态（编号/label 命中）⇒ 拒绝线
          // 指回按钮；对全部问集都是聊天 ⇒ 透传注入。
          val answerShaped = many.exists { f =>
            val views = AskPromptCodec
              .flatten(
                AskPromptCodec.viewsFromItemsJson(f.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil))
              )
              .views
            WeixinAskLeg.parseReply(text, views) match
              case Right(None) => false
              case _ => true
          }
          if answerShaped then
            logger.info(
              s"feishu bridge: ask text reply ambiguous — ${many.size} open asks for session $sessionId, refusing"
            ) *>
              reply(sessionId, WeixinAskLeg.answerAmbiguousLine(many.size)).as(true)
          else IO.pure(false)
      end match
    }

  /**
   * The text-answer slot machinery — the SAME flatten → answers → expand chain
   * the card click runs ([[answerFromFrame]]), so a numbered/labelled text
   * reply and a button click land at the hub as byte-identical payloads.
   * `parseReply` has already range-checked against the same views, so the
   * validate guard below is belt-and-braces (a failure logs and forwards
   * nothing — never a guessed payload).
   */
  private def forwardTextAnswer(
    ctx: BridgeContext,
    rid: String,
    rootSid: String,
    flat: AskPromptCodec.FlatSet,
    actions: List[PromptAction]
  ): IO[Unit] =
    AskPromptCodec.validate(flat.views, actions) match
      case Left(why) =>
        logger.warn(s"feishu bridge: text answer failed validation ($why) — refused, nothing forwarded")
      case Right(_) =>
        val kept = AskPromptCodec.answers(flat.views, actions)
        val answers = flat.expandAnswerSlots(kept)
        ctx.forwardInteractionAnswer(rid, rootSid, Json.obj("answers" -> answers.asJson)) *>
          logger.info(s"feishu bridge: text answer forwarded requestId=$rid root=$rootSid answers=${answers.size}")

  /**
   * The keyword-cancel leg (askucancel 批)：hub 全局快照过滤出**本会话窗口**的
   * askUser 卡（frames 的 sessionId = rootSessionId；type="askUser" 排除
   * mcpPermission 卡）→ 恰一问则精确取消 + 回执行；多问则拒绝线；零问则回退
   * 普通注入（与改前逐字节同路径）。
   */
  private[social] def keywordCancel(
    ctx: BridgeContext,
    sessionId: String,
    text: String,
    in: FeishuMessage.Inbound
  ): IO[Unit] =
    ctx.listPendingAsks.flatMap { frames =>
      val mine = frames.flatMap { f =>
        val isAsk = f.hcursor.downField("type").as[String].toOption.contains("askUser")
        val inWindow = f.hcursor.downField("sessionId").as[String].toOption.contains(sessionId)
        if isAsk && inWindow then f.hcursor.downField("requestId").as[String].toOption else None
      }
      mine match
        case rid :: Nil =>
          ctx.cancelInteractionRequest(rid, "channel-cancel-keyword", FeishuBridgePlugin.Name) *>
            logger.info(s"feishu bridge: ask cancelled by keyword requestId=$rid session=$sessionId") *>
            reply(sessionId, WeixinAskLeg.CancelledLine)
        case Nil =>
          // 在册零问：取消关键字按聊天透传（B-2 既有裁定），路径与改前逐字节一致。
          logger.info(s"feishu bridge: chat ${in.chatId} -> session $sessionId") *>
            ctx.injectMessage(sessionId, text, in.senderId, originOf(in))
        case many =>
          logger.info(
            s"feishu bridge: cancel keyword ambiguous — ${many.size} open asks for session $sessionId, refusing"
          ) *>
            reply(sessionId, WeixinAskLeg.cancelAmbiguousLine(many.size))
    }

  // ───────── feishu-inbound-image batch (2026-10-11): the image arm (intake 前置臂) ─────────

  /**
   * The IMAGE ARM: a `message_type=image` event fetches its bytes by
   * `image_key` through the [[fetchImage]] seam (production = the bot
   * credential face [[FeishuChannel.getImage]]; the SecretsStore storage
   * structure is untouched — a read through [[FeishuCredentials]] only), lands
   * them under `<root>/uploads/<sessionId>/`, and injects the SAME one-line
   * form the weixin leg's media arm injects
   * ([[WeixinIlinkBridgePlugin.mediaLine]] — one authority, never a second
   * wording; the agent Reads the file itself, exactly like the weixin arm).
   *
   * Every failure has an HONEST session-visible face — the line names what
   * failed, the message id and the image key — plus a WARN log line:
   *
   *   · no credential resolved  → 「渠道凭据未就绪」
   *   · no image_key on the wire → 「事件未携带 image_key」
   *   · the fetch failed         → 「下载失败（code=… msg=…）」
   *   · over [[FeishuBridgePlugin.MaxInboundImageBytes]] → 「大小超限」，not landed
   *
   * The unrecognised-type placeholder is unreachable for image by construction
   * (the parse layer answers `None` for `image`): a failed download is never
   * silently swallowed, and never disguised as an unknown type.
   */
  private[social] def imageArm(ctx: BridgeContext, in: FeishuMessage.Inbound, sessionId: String): IO[Unit] =
    val inject: String => IO[Unit] = line => ctx.injectMessage(sessionId, line, in.senderId, originOf(in))
    val mid = in.messageId
    creds match
      case None =>
        logger.warn(s"feishu bridge: image $mid not fetched — no credential resolved") *>
          inject(s"[图片未取得] 渠道凭据未就绪，message_id=$mid")
      case Some(c) =>
        FeishuBridgePlugin.imageKeyOf(in.contentRaw) match
          case None =>
            logger.warn(s"feishu bridge: image $mid carries no image_key — nothing to fetch") *>
              inject(s"[图片未取得] 事件未携带 image_key，message_id=$mid")
          case Some(key) =>
            fetchImage(c.appId, c.appSecret, region, key).flatMap {
              case Left(reason) =>
                logger.warn(s"feishu bridge: image $mid download failed ($reason)") *>
                  inject(s"[图片未取得] 下载失败（$reason），message_id=$mid，image_key=$key")
              case Right(bytes) =>
                if bytes.length.toLong > FeishuBridgePlugin.MaxInboundImageBytes then
                  logger.warn(
                    s"feishu bridge: image $mid over the inbound cap " +
                      s"(${bytes.length} > ${FeishuBridgePlugin.MaxInboundImageBytes} bytes) — not landed"
                  ) *>
                    inject(
                      s"[图片未保存] 大小超限（${bytes.length} 字节 > 上限 ${FeishuBridgePlugin.MaxInboundImageBytes} 字节），" +
                        s"message_id=$mid，image_key=$key"
                    )
                else
                  IO.blocking(FeishuBridgePlugin.landInboundImage(root, sessionId, mid, bytes)).flatMap { path =>
                    logger.info(s"feishu bridge: image $mid landed at $path (${bytes.length} bytes)") *>
                      inject(WeixinIlinkBridgePlugin.mediaLine(path.toString, Some("image"), None))
                  }
            }
    end match
  end imageArm

  /**
   * The bridge-side readable body: quote段 then body, in the composition order
   * the design card fixes (§10.4). The source-marker block is NOT prepended
   * here — that belongs to the unified injection point, which is the only place
   * that knows the channel identity.
   */
  private[social] def renderedBody(in: FeishuMessage.Inbound): Option[String] =
    InboundParse.compose(
      quote = InboundParse.quoteSection(in.quotedMessageId, quotedSender = None, quotedText = None),
      body = in.parsed
    )

  /**
   * The channel-agnostic origin descriptor this bridge contributes. The channel
   * id is the bridge's own stable name and the display name is the human label;
   * the chat is the conversation reference. Pure data — the template lives on
   * the channel-agnostic side ([[nebflow.bridge.BridgeOrigin]]).
   */
  private def originOf(in: FeishuMessage.Inbound): Option[nebflow.bridge.BridgeOrigin] =
    Some(
      nebflow.bridge.BridgeOrigin(
        channelId = FeishuBridgePlugin.Name,
        channelDisplay = FeishuBridgePlugin.ChannelDisplay,
        chatRef = Option(in.chatId).map(_.trim).filter(_.nonEmpty)
      )
    )

  /**
   * P1 auto-bind (feishu-bind batch, 2026-09-27): the first message from a
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
   * behaviour, unchanged: drop + log.
   */
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
          // feishu-inbound-image batch: the bind tail grew the image arm (the
          // weixin C4 form — a first image BINDS exactly like a first text, and
          // what gets injected is the landed-file media line, never a
          // placeholder, never silence).
          (renderedBody(in) match
            case Some(text) => ctx.injectMessage(sessionId, text, in.senderId, originOf(in))
            case None if in.messageType == "image" => imageArm(ctx, in, sessionId)
            case None => IO.unit)
      case None =>
        IO(
          logger.info(
            s"feishu bridge: no session is bound to chat ${in.chatId} and no default session is configured — message dropped"
          )
        )
    }

  // ───────────────── panel probe face (feishu-bind batch, C1) ─────────────────
  /** Whether the long connection is currently up. */
  def connected: Boolean = listener.isDefined

  /**
   * The app_id the bridge ACTUALLY resolved and connected with, if any. This
   * is an identifier, not a credential (same face as the scan-bind done
   * payload) — the secret never leaves this class. This is the half of the
   * fingerprint verdict the stored config cannot answer.
   */
  def liveAppId: Option[String] = creds.map(_.appId)

  private def openListener(ctx: BridgeContext, c: FeishuCredentials.Credential): Unit =
    // askuser-remote P1: the callback credential pair rides the SAME
    // zero-leak resolution face; the VALUES go into the SDK builder only.
    val cb = FeishuCredentials.resolveCallbackCredentials(root)
    val l = new FeishuChannel.Listener(
      c.appId,
      c.appSecret,
      region = region,
      verificationToken = cb.verificationToken.getOrElse(""),
      encryptKey = cb.encryptKey.getOrElse(""),
      handler = in => runIntake(ctx, in),
      cardHandler = a => runCardAction(ctx, a)
    )
    val res = l.start()
    if res.ok then
      creds = Some(c)
      listener = Some(l)
      logger.info(s"feishu bridge: long connection up (${res.detail})")
    else
      l.stop() // a failed handshake still owns a socket attempt — never leak it
      logger.warn(s"feishu bridge: handshake failed (${res.detail}) — registered but unconnected")

  end openListener

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
          .get(FeishuBridgePlugin.Name)
          .flatMap(b => b.hcursor.downField("chat_id").as[String].toOption.map(_.trim).filter(_.nonEmpty))
          .map(_ -> s.id)
      }
      val bound = pairs.toMap // duplicate chat ids: last session wins
      val dupes = pairs.groupBy(_._1).count((_, v) => v.sizeIs > 1)
      val warn =
        if dupes > 0 then
          logger.warn(s"feishu bridge: $dupes chat(s) bound to more than one session — the last binding wins")
        else IO.unit
      warn *> routesRef.set(bound) *>
        logger.info(s"feishu bridge: routing table rebuilt — ${bound.size} chat(s) bound to sessions")
    }

  // ───────────────────────────── outbound ─────────────────────────────
  /**
   * The outbound decision (richcontent main-link batch, soc483).
   *
   * Three event kinds matter here, and they arrive as SEPARATE root events:
   *
   *   - `textDelta` — the turn's text, accumulated per session (unchanged);
   *   - `toolEnd`   — the ONLY root event that carries the tool `content`, and
   *     therefore the only place a rich product (a card payload or an artifact
   *     path) can be observed. A rich product is remembered for the turn; any
   *     other tool result leaves the memory as it was;
   *   - `done`      — the turn closed. With a rich product remembered, the reply
   *     goes down the rich leg ([[RichKind.of]] classification → the injected
   *     [[RichFeishuAdapter]], which plans/renders/delivers and degrades to text
   *     on every failure). With none, the reply is the accumulated text, through
   *     the EXISTING [[reply]] path, byte-for-byte as before.
   *
   * 🔴 The rich leg is ADDITIVE by construction: it never suppresses the text
   * reply (the accumulated text is still sent), and the no-product path never
   * touches the rich code at all. `interrupted` / `retryStatus` clear both the
   * buffer and the rich memory (a torn turn is never a reply).
   *
   * 逐段即时推送批（2026-10-10）：`toolStart` / `roundComplete` 不再是无名
   * 直落臂——它们正是桌面端一个文本段提交（一条气泡）的边界
   * （[[nebflow.gateway.SessionRecorder]] 同点 flush textBuf），渠道腿接同一
   * 源：边界即把该会话缓冲段作为一条独立消息推出（[[flushSegment]]，节流队列
   * 后台跑）。`done` 由此只补发最后一段尾巴——整轮聚合重发取消（去重本体），
   * rich 腿进同一队列、排在尾段之前（媒体先行、正文殿后保持）。
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
          // One pacer slot for the WHOLE rich leg: every remembered 组卡 is
          // delivered inside it, group order = toolEnd order, items in payload
          // order — then the text tail. Media first, 正文殿后 is preserved.
          val richLeg = richs match
            case Nil => IO.unit
            case rs  => pacer.enqueue(deliverRichAll(sessionId, rs))
          val textLeg = if text.trim.isEmpty then IO.unit else pacer.enqueue(reply(sessionId, text))
          richLeg *> textLeg
        }
      // 逐段即时批：分段提交边界——缓冲段即推即清（空段不推），后台排队不
      // 阻塞流式事件面。
      case "toolStart" | "roundComplete" => flushSegment(sessionId)
      // A retried or interrupted turn re-streams its text; the partial buffer
      // must never flush as a duplicated/garbled reply.
      case "interrupted" | "retryStatus" =>
        pendingRef.update(_ - sessionId) *> richRef.update(_ - sessionId)
      // askuser-remote P1 (L0, D-2 拍板: each bridge grows its own branch —
      // no second mechanism). The frame reached this bridge already (hub
      // render → rootWsSend → wsHub broadcast); it simply had no arm before.
      case "askUser" => onAskUserFrame(sessionId, c)
      case "askUserClosed" =>
        // 撤卡/台账清除 (P1 form): the hub slot is gone (withdraw / cleanup /
        // restart), so the routing cache entry dies with it. A later click on
        // the stale card then reads "no open prompt" → 「已失效」 — the 30-day
        // / zombie closure the design pins, with zero extra API calls (a
        // PATCH-based disable is a P2 candidate, not taken here).
        val rid = c.downField("requestId").as[String].toOption.map(_.trim).filter(_.nonEmpty)
        rid.fold(IO.unit)(r => askChatRef.update(_ - r))
      case _ => IO.unit
    end match
  end onAgentEvent

  // ───────────── askuser-remote P1: the askUser frame leg ─────────────

  /**
   * The outbound ask leg: hub `askUser` frame → flatten (D-1) → attachments
   * ride the MEDIA leg FIRST (askuser-media-split batch: 媒体先单独发出，卡片
   * 只带问题) → render → send. Degradations, in the frozen chain's order: a
   * question needing a local-panel affordance sends the NUMBERED TEXT card
   * instead (LocalOnly); a card the transport rejects sends the numbered text
   * card instead (SendRejected); a session with no feishu binding has no
   * addressee and the frame passes through untouched (the text leg's own
   * rule). Attachments the media leg did NOT deliver keep their 附件路径
   * lines in the card / degrade text.
   */
  private[social] def onAskUserFrame(sessionId: String, c: io.circe.HCursor): IO[Unit] =
    routesRef.get.flatMap { routes =>
      routes.collectFirst { case (chatId, sid) if sid == sessionId => chatId } match
        case None => IO.unit // not a feishu-bound session — other events simply pass through
        case Some(chatId) =>
          val requestId = c.downField("requestId").as[String].toOption.map(_.trim).filter(_.nonEmpty)
          val items = c.downField("items").as[List[Json]].toOption.getOrElse(Nil)
          val attachments =
            c.downField("attachments").as[List[String]].toOption.getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
          val allViews = AskPromptCodec.viewsFromItemsJson(items)
          (requestId, allViews) match
            case (None, _) =>
              IO(
                logger.warn(
                  s"feishu bridge: askUser frame without requestId — dropped (no answer could ever be routed)"
                )
              )
            case (_, Nil) =>
              IO(logger.warn(s"feishu bridge: askUser frame without a usable question — dropped"))
            case (Some(rid), views) =>
              // D-1 拍板: flatten ONCE — one AskUser = one question set sent in
              // one pass; a question whose dependsOn cannot name a surviving
              // sibling is not sent. Answer slots stay aligned with the FULL
              // original set via the FlatSet (the callback side flattens the
              // SAME frame items the same way, so the indices always agree).
              val flat = AskPromptCodec.flatten(views)
              askChatRef.update(_ + (rid -> chatId)).flatMap { _ =>
                creds match
                  case None =>
                    IO(logger.warn(s"feishu bridge: ask card for chat $chatId dropped — no credential resolved"))
                  case Some(cr) =>
                    deliverAskMedia(cr, chatId, attachments).flatMap { undelivered =>
                      if FeishuAskCard.needsTextFallback(flat.views) then
                        sendTextFallback(
                          cr,
                          chatId,
                          flat.views,
                          FeishuAskCard.fallbackReason(PromptFallback.LocalOnly(localOnlyReason(flat.views))),
                          undelivered
                        )
                      else sendAskCards(cr, chatId, rid, flat.views, undelivered)
                    }
              }
          end match
    }

  /**
   * WHICH local affordance forced the text fallback — the fact, named (S-5
   * interpolates it; the tokens are the field names, not new copy).
   */
  private def localOnlyReason(views: List[PromptView]): String =
    val reasons = List.newBuilder[String]
    if views.exists(_.dirPicker) then reasons += "dirPicker"
    if views.exists(_.canvas.nonEmpty) then reasons += "canvas"
    if views.exists(_.options.exists(_.hasPreview)) then reasons += "preview"
    reasons.result().mkString("/")

  /**
   * The card send leg (A-3: static 1.0 two-button cards) with the
   * SendRejected fallback: every card goes through the interactive seam; if
   * the transport rejected ALL of them, the whole set degrades to ONE
   * numbered text card. A partial failure (some cards out, some rejected) is
   * logged — the answers that DO arrive still route, and a second full text
   * card would duplicate the questions the user can already see.
   */
  private def sendAskCards(
    cr: FeishuCredentials.Credential,
    chatId: String,
    rid: String,
    views: List[PromptView],
    attachments: List[String]
  ): IO[Unit] =
    val cards = FeishuAskCard.renderFull(views, rid, attachments)
    cards
      .foldLeft(IO.pure(List.empty[FeishuChannel.SendResult])) { (acc, card) =>
        acc.flatMap(rs => sendCard(cr.appId, cr.appSecret, region, chatId, card).map(rs :+ _))
      }
      .flatMap { results =>
        val rejected = results.count(!_.ok)
        if rejected == 0 then
          IO(logger.info(s"feishu bridge: ask card(s) delivered to chat $chatId (cards=${results.size})"))
        else if rejected == results.size then
          sendTextFallback(
            cr,
            chatId,
            views,
            FeishuAskCard.fallbackReason(PromptFallback.SendRejected(results.head.code, results.head.msg))
          )
        else
          IO(
            logger.warn(
              s"feishu bridge: ask cards PARTIALLY delivered to chat $chatId " +
                s"(ok=${results.size - rejected} rejected=$rejected) — no duplicate text card sent"
            )
          )
        end if
      }

  end sendAskCards

  /**
   * The ask-attachment MEDIA leg (askuser-media-split batch): each attachment
   * is classified through the SAME authority the rich leg uses
   * ([[RichKind.of]]) and delivered through the SAME injected adapter face
   * ([[buildRichAdapter]] — image/file upload+send with the adapter's own
   * fail-closed degrades). Returns the UNDELIVERED paths: a path missing on
   * disk, of no carriable kind, or whose delivery failed keeps its 附件路径
   * line in the card / degrade text — the line is the visible fallback, no
   * extra notice is sent on top of it. Sequential in list order; the tool
   * schema caps the list at 9.
   */
  private def deliverAskMedia(
    cr: FeishuCredentials.Credential,
    chatId: String,
    attachments: List[String]
  ): IO[List[String]] =
    val adapter = buildRichAdapter(cr, region, chatId)
    attachments.foldLeft(IO.pure(List.empty[String])) { (acc, raw) =>
      acc.flatMap { failed =>
        val p = raw.trim
        val path = Try(os.Path(p, os.pwd)).toOption
        val exists = path.exists(os.isFile)
        RichKind.of(content = "", path = Some(p), exists = exists, documentExtensions = documentExtensions) match
          case RichKind.Image | RichKind.Document =>
            adapter
              .deliver(
                RichKind.of(content = "", path = Some(p), exists = exists, documentExtensions = documentExtensions),
                path
              )
              .flatMap { results =>
                // "delivered" means the MEDIA call carried it (upload+send) — a
                // degrade TEXT result (size gate / channel refusal) does not
                // count: the path line must stay so the recipient can reach the
                // file some other way.
                val delivered = results.exists(r =>
                  r.ok && (r.call match
                    case RichCall.SendText(_) => false
                    case _ => true)
                )
                if delivered then IO(failed)
                else
                  IO(
                    logger.warn(
                      s"feishu bridge: ask attachment media leg did not deliver (${results.count(_.ok)}/${results.size} calls ok) — keeping the path line: $p"
                    )
                  ).as(failed :+ p)
              }
          case RichKind.Unknown(reason) =>
            IO(
              logger.warn(
                s"feishu bridge: ask attachment not carriable by the media leg ($reason) — keeping the path line: $p"
              )
            ).as(failed :+ p)
          case other =>
            // Card cannot occur on an ask attachment (the classification reads
            // an empty content here) — this arm is the total-function backstop.
            IO(
              logger.warn(
                s"feishu bridge: ask attachment classified $other — keeping the path line: $p"
              )
            ).as(failed :+ p)
        end match
      }
    }

  end deliverAskMedia

  /**
   * The text fallback leg (S-5/S-6 frozen copy, the same slot machinery).
   * `attachments` carries the UNDELIVERED paths only (media-split contract);
   * `Nil` keeps every pre-P1 render byte-identical.
   */
  private def sendTextFallback(
    cr: FeishuCredentials.Credential,
    chatId: String,
    views: List[PromptView],
    reason: String,
    attachments: List[String] = Nil
  ): IO[Unit] =
    send(cr.appId, cr.appSecret, region, chatId, FeishuAskCard.renderText(views, reason, attachments)).flatMap { res =>
      if res.ok then IO(logger.info(s"feishu bridge: ask TEXT fallback delivered to chat $chatId"))
      else IO(logger.warn(s"feishu bridge: ask TEXT fallback rejected too code=${res.code} msg=${res.msg}"))
    }

  // ───────────── askuser-remote P1: the card.action.trigger leg ─────────────

  /**
   * The SDK-thread boundary — the intake shape ([[runIntake]]): the card
   * callback handler runs on the SDK's dispatch thread and must answer the
   * response synchronously (the 3-second window), so the whole chain runs
   * through one `unsafeRunSync` here, error-contained upstream by
   * [[FeishuChannel]]'s dispatcher.
   */
  private def runCardAction(ctx: BridgeContext, a: FeishuChannel.CardAction): FeishuChannel.CardActionResponse =
    handleCardAction(ctx, a).unsafeRunSync()

  /**
   * The callback chain (A-4/A-5 拍板): allowlist gate → hub snapshot (the
   * open-ask AUTHORITY — no second ledger) → [[FeishuAskCard.parse]] →
   * answers → `forwardInteractionAnswer` (the bridge answer leg) → the A4
   * updated card in the response. Every refusal is a NAMED toast (S-7/8/9),
   * never silence.
   */
  private[social] def handleCardAction(
    ctx: BridgeContext,
    a: FeishuChannel.CardAction
  ): IO[FeishuChannel.CardActionResponse] =
    val refuse: String => IO[FeishuChannel.CardActionResponse] =
      toast => IO.pure(FeishuChannel.CardActionResponse(toast = Some(toast), card = None))
    allowedRef.get.flatMap { allowed =>
      val gateOpen = allowed.isEmpty || a.openId.exists(allowed.contains)
      if !gateOpen then
        // Fail-closed, same judge as the text intake: a click from a sender we
        // cannot place on the member list is refused (and logged), never answered.
        IO(
          logger.warn(
            s"feishu bridge: card click from ${a.openId.getOrElse("<no-identity>")} refused — not on the member allowlist"
          )
        ) *>
          refuse(FeishuAskCard.ToastCannotHandle)
      else
        ctx.listPendingAsks.flatMap { frames =>
          val byRid = frames.flatMap(f => f.hcursor.downField("requestId").as[String].toOption.map(_ -> f)).toMap
          val known = byRid.keySet
          val ridRaw =
            a.value.hcursor.downField(FeishuAskCard.PayloadRequestId).as[String].toOption.map(_.trim).filter(_.nonEmpty)
          askChatRef.get.flatMap { cache =>
            val expected = ridRaw.flatMap(cache.get)
            FeishuAskCard.parse(
              a.value,
              knownRequestIds = known,
              expectedChatId = expected,
              callbackChatId = a.chatId,
              alreadyAnswered = Set.empty
            ) match
              case Left(reject) =>
                // chat-mismatch / expired / duplicate / malformed — each its own toast.
                IO(logger.info(s"feishu bridge: card click refused [${reject.reason}] ${reject.detail}")) *>
                  refuse(FeishuAskCard.toastFor(reject))
              case Right(action) =>
                val rid = ridRaw.getOrElse("")
                byRid.get(rid) match
                  case None =>
                    // parse accepted the rid (it was in the snapshot) — this arm is
                    // belt-and-braces against a torn frame.
                    refuse(FeishuAskCard.ToastExpired)
                  case Some(frame) =>
                    answerFromFrame(ctx, frame, rid, action, a).flatMap {
                      case None => refuse(FeishuAskCard.ToastCannotHandle)
                      case Some(cardBody) =>
                        // The click's chat re-pins the cache when a restart had
                        // emptied it (documented degrade: first click after a
                        // restart pins the chat; the allowlist gate still ruled it).
                        a.chatId
                          .traverse_(cid => askChatRef.update(_.updated(rid, cid)))
                          .as(FeishuChannel.CardActionResponse(toast = None, card = Some(cardBody)))
                    }
                end match
            end match
          }
        }
      end if
    }

  end handleCardAction

  /**
   * The happy path: frame items → the SAME flatten as the send leg → slots →
   * forward → A4 answered card. `None` = the frame could not be re-reduced
   * (items mutated under us — refuse rather than guess).
   */
  private def answerFromFrame(
    ctx: BridgeContext,
    frame: Json,
    rid: String,
    action: PromptAction,
    a: FeishuChannel.CardAction
  ): IO[Option[String]] =
    val rootSid = frame.hcursor.downField("sessionId").as[String].toOption.map(_.trim).filter(_.nonEmpty).getOrElse("")
    val items = frame.hcursor.downField("items").as[List[Json]].toOption.getOrElse(Nil)
    val attachments =
      frame.hcursor.downField("attachments").as[List[String]].toOption.getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
    val flat = AskPromptCodec.flatten(AskPromptCodec.viewsFromItemsJson(items))
    if flat.views.isEmpty || rootSid.isEmpty then IO.pure(None)
    else
      AskPromptCodec.validate(flat.views, List(action)) match
        case Left(why) =>
          IO(logger.warn(s"feishu bridge: card click produced an invalid action ($why) — refused")) *> IO.pure(None)
        case Right(_) =>
          val kept = AskPromptCodec.answers(flat.views, List(action))
          val answers = flat.expandAnswerSlots(kept)
          val display = action match
            case PromptAction.Pick(qi, oi) =>
              flat.views
                .find(_.idx == qi)
                .flatMap(_.options.lift(oi))
                .map(_.label)
                .getOrElse(FeishuAskCard.OtherCopy)
            case PromptAction.Other(_, text) =>
              if text.isEmpty then FeishuAskCard.OtherCopy else text
          ctx.forwardInteractionAnswer(rid, rootSid, Json.obj("answers" -> answers.asJson)) *>
            IO(
              logger.info(s"feishu bridge: card answer forwarded requestId=$rid root=$rootSid answers=${answers.size}")
            ) *>
            IO.pure(
              Some(
                FeishuAskCard.answeredCard(
                  flat.views,
                  rid,
                  Map(action match
                    case PromptAction.Pick(qi, _) => qi -> display
                    case PromptAction.Other(qi, _) => qi -> display),
                  attachments
                )
              )
            )

    end if

  end answerFromFrame

  /**
   * The rich leg: classify the remembered product and deliver it through the
   * injected adapter, degrading to text on every failure (the adapter's own
   * contract — see [[RichFeishuAdapter.deliver]]).
   *
   * Batch carrying (pop batch-media fix, 2026-10-08): a Pop of N artifacts is
   * remembered as N media items, and this leg delivers ALL of them, in list
   * order, each through the SAME classify → adapter face the single-product
   * read used. The per-item failure contract is the adapter's own (a refused
   * upload / oversize file / unknown kind becomes that item's visible degrade
   * line) — the loop moves on to the next item regardless, so ONE bad artifact
   * degrades on its own and never swallows the rest of the batch.
   *
   * 🔴 pop multi-group fix (2026-10-09): the per-item loop is now ALSO
   * raise-contained at this layer — an unexpected throw inside one item's
   * delivery (e.g. a filesystem error surfacing from the size gate's blocking
   * read) degrades THAT item to one visible `[未送达]` line through the bridge's
   * own text seam and the loop moves on. Before this, a raise escaped the fold,
   * killed the remaining items AND the text leg behind them
   * (`richLeg *> textLeg`) — one bad item silenced the whole turn tail.
   *
   * A CARD product is the `content` itself (no per-item media): delivered once,
   * exactly as the main-link batch shipped.
   *
   * 🔴 Zero-secret: the credential is resolved ONLY here, through the existing
   * [[FeishuCredentials.resolve]] path the text leg already uses, and is handed
   * straight to the adapter factory. It is never logged, echoed, or stored.
   */
  private[social] def deliverRich(sessionId: String, rich: RichProduct): IO[Unit] =
    routesRef.get.flatMap { routes =>
      routes.collectFirst { case (chatId, sid) if sid == sessionId => chatId } match
        case None => IO.unit // not a feishu-bound session — pass through, exactly as the text leg does
        case Some(chatId) =>
          creds match
            case None =>
              // 直落 IO（IO[IO[Unit]] 死日志修正，同 reply 腿）。
              logger.warn(s"feishu bridge: rich reply for chat $chatId dropped — no credential resolved")
            case Some(c) =>
              val adapter = buildRichAdapter(c, region, chatId)
              // 🔴 The adapter is called for EVERY classified item, `Unknown`
              // included: something WAS observed as a product this turn, so the
              // recipient must be told what happened to it（网页类 → 文件腿直发
              // 原件、文件腿失败才截图兜底；其余 → `[暂不支持]`/`[未送达]` 友好
              // 一行 —— social-webshot batch 2026-10-09 + 同日用户裁定修正，
              // 机制词汇只进日志）— a silent return here would be
              // exactly the drop this batch forbids.
              // 🔴 pop multi-group fix: one item's RAISE is contained to that
              // item (visible line + WARN), the remaining items keep going.
              def deliverOne(p: os.Path): IO[List[nebflow.social.outbound.RichCallResult]] =
                val kind = RichKind.of(
                  content = "",
                  path = Some(p.toString),
                  exists = Try(os.isFile(p)).getOrElse(false),
                  documentExtensions = documentExtensions
                )
                adapter
                  .deliver(kind, Some(p), "")
                  .handleErrorWith { e =>
                    val line =
                      s"[未送达] ${p.last} — 这一项发送时出现异常，其余内容照常（原件在桌面端：$p）"
                    logger.warn(
                      s"feishu bridge: rich item delivery raised for $p " +
                        s"(${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}) — degrading to a visible line"
                    ) *> sendVisibleLine(c, chatId, line)
                      .as(List(nebflow.social.outbound.RichLedger.of(
                        nebflow.social.outbound.RichCall.SendText(line),
                        ok = false,
                        -1,
                        None,
                        s"item delivery raised: ${e.getClass.getSimpleName}"
                      )))
                  }
              val legs =
                if RichKind.isCardContent(rich.content) then
                  adapter
                    .deliver(RichKind.Card, None, rich.content)
                    .handleErrorWith(e =>
                      // 直落 IO 语法（同 weixin 腿的 IO[IO[Unit]] 死日志修正）：
                      // `IO(logger.warn(...))` 的内层永不执行。
                      logger.warn(
                        s"feishu bridge: rich card delivery raised (${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")})"
                      ).as(Nil)
                    )
                else
                  rich.media.foldLeft(IO.pure(List.empty[nebflow.social.outbound.RichCallResult])) { (acc, p) =>
                    acc.flatMap(rs => deliverOne(p).map(rs ++ _))
                  }
              legs.flatMap { results =>
                if results.isEmpty then
                  IO(
                    logger.warn(
                      s"feishu bridge: rich reply for chat $chatId carried no deliverable media — " +
                        "the text reply carries the turn"
                    )
                  )
                else
                  val failed = results.count(!_.ok)
                  IO(
                    logger.info(
                      s"feishu bridge: rich reply to chat $chatId items=${rich.media.size} label=${rich.label} " +
                        s"calls=${results.size} failed=$failed"
                    )
                  )
              }
    }

  /**
   * pop multi-group fix (2026-10-09): deliver EVERY 组卡 the turn remembered,
   * in toolEnd order — one pacer slot for the whole list, so the text tail
   * still follows all media. Group boundaries stay visible in the per-group
   * logs of [[deliverRich]]; a raise inside one group is contained by that
   * group's own per-item guards, never by re-raising here.
   */
  private def deliverRichAll(sessionId: String, richs: List[RichProduct]): IO[Unit] =
    richs.foldLeft(IO.unit)((acc, rich) => acc *> deliverRich(sessionId, rich))

  /**
   * pop multi-group fix: the raise-containment degrade face — one visible line
   * through the SAME text seam [[reply]] uses (zero-secret: the credential only
   * reaches the seam). Contained: a failing notice degrades to a WARN.
   */
  private def sendVisibleLine(
    c: FeishuCredentials.Credential,
    chatId: String,
    line: String
  ): IO[Unit] =
    send(c.appId, c.appSecret, region, chatId, line).flatMap { res =>
      if res.ok then IO.unit
      // 直落 IO 语法（IO[IO[Unit]] 死日志修正——同下 reply 腿）。
      else logger.warn(s"feishu bridge: rich item degrade line send rejected code=${res.code} msg=${res.msg}")
    }

  /**
   * 逐段即时推送批：把该会话当前缓冲的文本段作为一条独立渠道消息推出。
   * 空段（纯工具过程、无正文）不推；推送经 [[pacer]] 排队（段间 ≥1s 按序发）
   * 且 `.start` 后台跑——绝不阻塞流式事件面（wsHub 广播监听是内联执行）。
   */
  private def flushSegment(sessionId: String): IO[Unit] =
    pendingRef.modify { m => (m - sessionId, m.getOrElse(sessionId, "")) }.flatMap { text =>
      if text.trim.isEmpty then IO.unit
      else pacer.enqueueAsync(reply(sessionId, text, isSegment = true))
    }

  /**
   * 逐段即时推送批：段级失败的「注明」腿——失败的段不补发（超时可能已送达，
   * WeixinOutbound 的重发边界同源），也不静默：一条一行提示经同一队列按序
   * 出网。同一会话连续失败只注明一次（[[segmentFailNoted]] 闸），任一次发送
   * 成功即复位。提示必在其主题段（失败那条）的发送尝试之后；它从失败段的
   * 后台 runner 里注册，相对其后注册段的插队位置不保证（失败检测是异步的）。
   * 提示自身走 `isSegment = false` 的 [[reply]]：再失败只有 WARN，无提示链
   * 级联。
   */
  private def noteSegmentFailure(sessionId: String, line: String): IO[Unit] =
    segmentFailNoted.modify { s =>
      if s.contains(sessionId) then (s, IO.unit)
      else (s + sessionId, pacer.enqueueAsync(reply(sessionId, line)))
    }.flatten

  private[social] def reply(sessionId: String, text: String, isSegment: Boolean = false): IO[Unit] =
    routesRef.get.flatMap { routes =>
      routes.collectFirst { case (chatId, sid) if sid == sessionId => chatId } match
        case None => IO.unit // not a feishu-bound session — other events simply pass through
        case Some(chatId) =>
          creds match
            // 直落 IO 语法（pop multi-group 批顺手修正）：原 `IO(logger.warn/info(...))`
            // 是 IO[IO[Unit]] 死日志——内层 logback 永不执行（同款隐患
            // WeixinIlinkBridgeSpec 头注 2026-10-08 已点名词退役），实况即
            // 2026-10-09 晚飞书腿整晚零出站日志、送达与否全盲的根因面。
            case None => logger.warn(s"feishu bridge: reply for chat $chatId dropped — no credential resolved")
            case Some(c) =>
              // Channel-adapter-side text conversion (socoutbound batch, author
              // ruling #299): the OUTBOUND copy is de-marked-up so Feishu shows
              // no bare markdown syntax and keeps links alive as bare URLs. The
              // in-session original above is never rewritten — only this copy is.
              val outbound = nebflow.social.outbound.OutboundText.toPlain(text)
              send(c.appId, c.appSecret, region, chatId, outbound).flatMap { res =>
                if res.ok then
                  segmentFailNoted.update(_ - sessionId) *>
                    logger.info(s"feishu bridge: reply delivered to chat $chatId (${res.messageId.getOrElse("-")})")
                else
                  logger.warn(s"feishu bridge: send rejected code=${res.code} msg=${res.msg}") *>
                    (if isSegment then
                       noteSegmentFailure(sessionId, s"[上一条推送失败 code=${res.code} ${res.msg}]")
                     else IO.unit)
              }
    }

end FeishuBridgePlugin

object FeishuBridgePlugin:

  /** BridgePlugin.name — also the platform key in SessionMeta.bridges. */
  val Name = "feishu"

  /**
   * Rich-content plumbing of the main link (richcontent main-link batch,
   * soc483) — PROMOTED to the channel-agnostic layer (weixin-media batch,
   * 2026-10-08): [[nebflow.social.outbound.RichProduct]] is now the single
   * definition (the weixin leg's toolEnd memory parses the same payloads
   * through it), and these aliases keep the historical spelling
   * (`FeishuBridgePlugin.RichProduct`, incl. the `PopSentinel` mirror guard the
   * offline spec pins) alive for its readers — the `val` carries the companion
   * (term positions), the `type` the type positions. Zero behaviour change on
   * the feishu path.
   */
  type RichProduct = nebflow.social.outbound.RichProduct
  val RichProduct: nebflow.social.outbound.RichProduct.type = nebflow.social.outbound.RichProduct

  /**
   * The human-readable label this channel announces in the source-marker block
   * (source-marker batch, 2026-10-01). Data only: the template lives on the
   * channel-agnostic side ([[nebflow.bridge.BridgeOrigin.marker]]), so a second
   * channel reuses it by supplying its own label — never by copying the text.
   */
  val ChannelDisplay = "飞书"

  /**
   * Production rich-adapter factory: the live adapter assembled from the
   * credential THIS bridge already resolved and the bound chat id. The
   * credential value is handed to the SDK seams only — never logged, echoed or
   * stored ([[RichFeishuAdapter]]'s own zero-secret contract).
   */
  def defaultRichAdapter(cred: FeishuCredentials.Credential, region: String, chatId: String): RichFeishuAdapter =
    RichFeishuAdapter.live(cred, region, "chat_id", chatId)

  /**
   * The document extension authority for the main link: the endpoint table the
   * existing same-package adapter already owns ([[FeishuOutboundAdapter.defaultTable]],
   * itself welded to `NfFilePolicy.NfFileAllowedExt` by `FileRefsWhitelistSpec`
   * A14). Referenced through that adapter rather than importing the gateway
   * package here, so the `social` package gains no new upward edge.
   */
  def defaultDocumentExtensions: Set[String] = FeishuOutboundAdapter.defaultTable

  /** Companion-side logger (the sync/guard legs live here, outside any instance). */
  private val log = NebflowLogger.forName("nebflow.social.feishu-bridge")

  /**
   * Outbound send seam. appId/appSecret are handed to the SDK only — they must
   * never be logged (the implementations inherit the zero-secret discipline).
   */
  type Send = (String, String, String, String, String) => IO[FeishuChannel.SendResult]

  def defaultSend(
    appId: String,
    appSecret: String,
    region: String,
    receiveId: String,
    text: String
  ): IO[FeishuChannel.SendResult] =
    IO.blocking(FeishuChannel.sendText(appId, appSecret, region, "chat_id", receiveId, text))

  /**
   * Interactive-card send seam (askuser-remote P1, A-3): the same shape with
   * the card JSON as the payload — production =
   * [[FeishuChannel.sendInteractive]] (the 2026-10-01 A9 seam, first caller
   * this batch); the offline spec injects a recorder.
   */
  type SendCard = (String, String, String, String, String) => IO[FeishuChannel.SendResult]

  def defaultSendCard(
    appId: String,
    appSecret: String,
    region: String,
    receiveId: String,
    cardJson: String
  ): IO[FeishuChannel.SendResult] =
    IO.blocking(FeishuChannel.sendInteractive(appId, appSecret, region, "chat_id", receiveId, cardJson))

  // ───────── feishu-inbound-image batch (2026-10-11): the image arm's pure face ─────────

  /**
   * The inbound image fetch seam. `Left(reason)` = the bytes could not be
   * obtained — the reason is mechanism detail for the log, and the arm
   * renders the honest session-visible line from it (the secret never enters
   * either: the pair feeds the SDK client only).
   */
  type FetchImage = (String, String, String, String) => IO[Either[String, Array[Byte]]]

  /**
   * Production fetch — [[FeishuChannel.getImage]] (the `im/v1/images/:image_key`
   * download leg) wrapped in IO. Every failure mode of the SDK leg already
   * arrives as an explicit reading; this wrapper only re-shapes it into
   * `Either[String, Array[Byte]]`.
   */
  def defaultFetchImage(appId: String, appSecret: String, region: String, imageKey: String): IO[Either[String, Array[Byte]]] =
    IO.blocking(FeishuChannel.getImage(appId, appSecret, region, imageKey)).map { r =>
      if r.ok then r.bytes.map(Right(_)).getOrElse(Left(s"code=${r.code} msg=${r.msg}"))
      else Left(s"code=${r.code} msg=${r.msg}")
    }

  /**
   * The `image_key` of an image event's content (`{"image_key": …}`). Absent,
   * blank or a non-JSON payload ⇒ `None` — the arm then says so out loud
   * instead of fetching nothing.
   */
  def imageKeyOf(contentRaw: String): Option[String] =
    io.circe.parser
      .parse(contentRaw)
      .toOption
      .flatMap(_.hcursor.downField("image_key").as[String].toOption)
      .map(_.trim)
      .filter(_.nonEmpty)

  /**
   * The single-inbound-image budget (feishu-inbound-image batch): 32 MiB,
   * comfortably above any legitimate chat screenshot (Feishu's own message
   * image upload cap is ~10 MB) and far below disk-abuse territory. An
   * over-budget download is NOT landed — the session reads the over-limit
   * line instead. Tunable here and nowhere else.
   */
  val MaxInboundImageBytes: Long = 32L * 1024 * 1024

  /**
   * The inbound-image landing directory: the data root's uploads face,
   * session-scoped — the same face the web leg's paste-save writes
   * (`uploads/<sid>/…`, WebSocketRoutes) and the uploads serve route reads
   * (StaticRoutes.uploadsRoutes), and the whole tree the egress whitelist
   * already covers ([[nebflow.neblink.EgressWhitelist.uploadsRoot]]).
   */
  def uploadsDir(root: os.Path, sessionId: String): os.Path = root / "uploads" / sessionId

  /**
   * Land one inbound image. The filename traces to the Feishu message id
   * (redelivery of the same event overwrites the same path = idempotent),
   * and the extension comes from the actual magic bytes — the agent's Read
   * maps MIME by extension (the WebSocketRoutes paste-save batch's own
   * note), so the landed name must not lie about the bytes.
   */
  def landInboundImage(root: os.Path, sessionId: String, messageId: String, bytes: Array[Byte]): os.Path =
    val dir = uploadsDir(root, sessionId)
    os.makeDir.all(dir)
    val stem = s"feishu-${messageId.replaceAll("[^A-Za-z0-9._-]", "_")}"
    val file = dir / s"$stem${imageExtOf(bytes)}"
    os.write.over(file, bytes)
    file

  /**
   * Extension by magic bytes. Unrecognised magic falls back to `.jpg`:
   * Feishu message images are stored as JPEG, and a wrong-guess extension
   * still opens — a missing one would not.
   */
  def imageExtOf(bytes: Array[Byte]): String =
    val has = (i: Int, v: Int) => bytes.length > i && (bytes(i) & 0xff) == v
    if has(0, 0xff) && has(1, 0xd8) then ".jpg" // FFD8 — JPEG
    else if has(0, 0x89) && has(1, 0x50) then ".png" // 89 50 4E 47
    else if has(0, 0x47) && has(1, 0x49) && has(2, 0x46) then ".gif" // GIF8
    else if has(0, 0x52) && has(1, 0x49) && has(2, 0x46) && has(8, 0x57) then ".webp" // RIFF…WEBP
    else if has(0, 0x42) && has(1, 0x4d) then ".bmp" // BM
    else ".jpg"

  end imageExtOf

  final case class ChannelConfig(region: String, allowedOpenIds: List[String])

  /**
   * The feishu card's stored fields, with the shipped defaults filled in:
   * region `feishu` (hardcoded default — the lark option is hidden, not
   * deleted), allowlist empty = unrestricted.
   */
  def readChannelConfig(root: os.Path): ChannelConfig =
    val fields = SocialChannels
      .readChannels(root)
      .hcursor
      .downField("channels")
      .downField(Name)
      .downField("fields")
      .focus
      .getOrElse(Json.obj())
    val region = fields.hcursor
      .downField("region")
      .as[String]
      .toOption
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("feishu")
    val allowed = fields.hcursor
      .downField("allowed_open_ids")
      .as[String]
      .toOption
      .getOrElse("")
      .split("[,;\\s]+")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList
    ChannelConfig(region, allowed)

  end readChannelConfig

  /** Never leaks a value — the Failure cases carry reasons and paths only. */
  private def render(f: FeishuCredentials.Failure): String = f match
    case FeishuCredentials.Failure.Missing(r) => s"missing: $r"
    case FeishuCredentials.Failure.Unreadable(r) => s"unreadable: $r"
    case FeishuCredentials.Failure.Incomplete(r) => s"incomplete: $r"

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
  def sync(
    manager: BridgeManager,
    root: os.Path,
    factory: os.Path => BridgePlugin = (r => new FeishuBridgePlugin(r))
  ): IO[Unit] =
    val want = IO.blocking(SocialChannels.isEnabled(root, Name) && SocialChannels.verified(root, Name))
    want.flatMap {
      case true =>
        val plugin = factory(root)
        manager.unregister(Name) *> manager.register(plugin) *> manager.startOne(Name) *> {
          plugin match
            case fb: FeishuBridgePlugin => guardFingerprint(fb, root)
            case _ => IO.unit // foreign plugin (offline spec fakes): nothing to guard
        }
      case false =>
        manager.unregister(Name)
    }
  end sync

  // ───────────────── fingerprint guard + panel faces (feishu-bind batch) ─────────────────

  /** The schema's stored app_id — the inventory side of the verdict (C1). */
  def storedAppId(root: os.Path): Option[String] =
    SocialChannels
      .readChannels(root)
      .hcursor
      .downField("channels")
      .downField(Name)
      .downField("fields")
      .downField("app_id")
      .as[String]
      .toOption
      .map(_.trim)
      .filter(_.nonEmpty)

  /**
   * C1/C2 verdict: does the live connection's app match the stored app_id?
   * `match` / `mismatch` / `unknown` (either side absent). Compared through
   * [[FeishuCredentials.fingerprint]] (stable, non-invertible), so the verdict
   * is safe to log and to surface — and no value ever needs to be written
   * down.
   */
  def fingerprintVerdict(storedAppId: Option[String], liveAppId: Option[String]): String =
    (storedAppId.map(_.trim).filter(_.nonEmpty), liveAppId.map(_.trim).filter(_.nonEmpty)) match
      case (Some(s), Some(l)) =>
        if FeishuCredentials.fingerprint(s) == FeishuCredentials.fingerprint(l) then "match" else "mismatch"
      case _ => "unknown"

  /**
   * P0-2 (feishu-bind batch): the live-connection fingerprint guard, fired by
   * [[sync]] AFTER the adapter (re)started. The credential the bridge actually
   * resolved must fingerprint-match the app_id just persisted in the schema;
   * any other outcome is said out loud here and answered by the connection
   * probe face with `fingerprintMatch != "match"` — killing the "stored A,
   * connected B, panel green" lie the 2026-09-27 diagnosis nailed. A
   * non-match verdict warns and moves on (never throws): a dead bridge helps
   * nobody, the thing that must stop is the green light, not the bridge.
   */
  private def guardFingerprint(fb: FeishuBridgePlugin, root: os.Path): IO[Unit] =
    val stored = storedAppId(root)
    val verdict = fingerprintVerdict(stored, fb.liveAppId)
    verdict match
      case "match" =>
        IO(
          log.info(
            s"feishu bridge: fingerprint guard ok — the live connection matches the stored app_id (fp=${FeishuCredentials.fingerprint(stored.getOrElse(""))})"
          )
        )
      case other =>
        IO(
          log.warn(
            s"feishu bridge: FINGERPRINT GUARD $other — the live connection's app does not correspond to the stored app_id " +
              s"(storedFp=${stored.map(FeishuCredentials.fingerprint).getOrElse("-")} " +
              s"liveFp=${fb.liveAppId.map(FeishuCredentials.fingerprint).getOrElse("-")}); " +
              s"the connection face answers fingerprintMatch=$other"
          )
        )

    end match

  end guardFingerprint

  /**
   * The C1 connection payload (GET /api/social/channels/feishu/connection):
   * bridge enabled state, adapter registration, live connection + connected
   * app, stored app_id, and the fingerprint verdict (C2: `mismatch` ⇒ the
   * panel renders a Failed/warn state, never a green light). `liveAppName` is
   * null today — no stored state carries an app display name (the SDK
   * registration result exposes none); the key exists so the contract is
   * stable when a source appears. Identifiers only: no secret ever enters
   * this payload.
   */
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

  end connectionJson

  /** One chat→session binding for the C3 face. */
  final case class Binding(
    sessionId: String,
    sessionName: String,
    chatId: String,
    source: String,
    boundAt: Option[Long]
  )

  /**
   * The chat→session bindings (C3), read off the session metas: one entry per
   * session carrying a feishu bridge config with a chat_id. `source` is
   * `auto` only when the binding says so ([[FeishuBridgePlugin.autoBind]]
   * writes it); everything else — REST bind-route writes, pre-auto-bind
   * bindings, hand-edited configs — reads `manual`.
   */
  def bindingsOf(metas: List[SessionMeta]): List[Binding] =
    metas.flatMap { s =>
      s.bridges.get(Name).flatMap { b =>
        b.hcursor.downField("chat_id").as[String].toOption.map(_.trim).filter(_.nonEmpty).map { chatId =>
          val source = b.hcursor
            .downField("source")
            .as[String]
            .toOption
            .map(_.trim)
            .filter(_ == "auto")
            .getOrElse("manual")
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
