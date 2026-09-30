package nebflow.social

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import nebflow.gateway.NfFilePolicy
import nebflow.social.outbound.*
import nebflow.shared.{AttachContract, NebflowLogger}

/**
 * Feishu implementation of the channel-agnostic outbound layer (socoutbound
 * batch, section 2 of the design card
 * `20260930_203506_socoutbound-design__chain-socoutbound`).
 *
 * 🔴 This class is an IMPLEMENTATION. The split criteria, the payload algebra
 * and the planner all live in `nebflow.social.outbound`, which has no Feishu
 * types in it -- that is the layering the author's constraint demands (Feishu
 * implements; WeChat reuses). Nothing here re-implements a criterion: every
 * decision delegates to the definition layer, and the extension table is the
 * endpoint authority ([NfFilePolicy.NfFileAllowedExt]) rather than a new one.
 *
 * Seams (all constructor-injected, the same shape the existing
 * [[FeishuBridgePlugin.Send]] seam already uses, so the offline spec can assert
 * call sequences with zero network and zero credentials):
 *   - `sendText`   -- the existing text leg;
 *   - `uploadImage`/`uploadFile` -- the lark upload calls, recorded in specs;
 *   - `renderer`   -- [[OutboundRenderer]]; the production implementation is a
 *     headless-Chrome subprocess living outside this file.
 *
 * Zero-secret discipline: this class never receives, logs or renders a
 * credential value. Uploading resolves credentials through
 * [[FeishuCredentials]] exactly where the existing send path already does.
 */
final class FeishuOutboundAdapter(
    val sendText: (String, String, String, String, String) => IO[FeishuChannel.SendResult],
    val uploadImage: FeishuOutboundAdapter.UploadImage,
    val uploadFile: FeishuOutboundAdapter.UploadFile,
    val renderer: OutboundRenderer = OutboundRenderer.unavailable,
    /** The extension table handed in by the caller -- the endpoint authority by
      * default. See [[ContentClass.ExtensionTable]] for why it is a parameter. */
    val table: ContentClass.ExtensionTable = FeishuOutboundAdapter.defaultTable,
    /** Pinned credentials for the offline spec; production resolves them through
      * the existing path. Kept opaque: they are handed to the SDK only. */
    val creds: Option[FeishuCredentials.Credential] = None,
    val region: String = "feishu"
) extends ChannelAdapter:

  private val logger = NebflowLogger.forName("nebflow.social.feishu-outbound")

  def channelId: String = "feishu"

  // ─────────────────────────── classification ───────────────────────────

  /**
   * Read the two observables the definition layer needs out of an event:
   * the source marker (is there a card product?) and the referenced artifact
   * paths. Anything this layer cannot read is left ABSENT, which the
   * definition layer turns into an explicit `Unknown` rather than a guess.
   */
  private[social] def observables(event: Json): (Boolean, List[os.Path]) =
    val c = event.hcursor
    val cardMarker = c.downField("cardHtml").focus.exists(!_.isNull) ||
      c.downField("card").focus.exists(!_.isNull)
    val paths =
      List("path", "filePath", "attachment", "artifact").flatMap { field =>
        c.downField(field).as[String].toOption.map(_.trim).filter(_.nonEmpty)
      } ++
        c.downField("paths").as[List[String]].toOption.getOrElse(Nil).map(_.trim).filter(_.nonEmpty)
    // A path that cannot even be parsed is not a usable observable: it is left
    // out, which the definition layer then reports as an explicit Unknown.
    val osPaths = paths.flatMap(p => scala.util.Try(os.Path(p)).toOption).distinct
    (cardMarker, osPaths)

  def classify(event: Json): ContentClass =
    val observed = observables(event)
    ContentClass.classify(observed._1, observed._2, ContentClass.fsStat, table)

  // ─────────────────────────── rendering ───────────────────────────

  /** Delegates to the injected renderer. A renderer that cannot run returns the
    * payload untouched, which the planner reads as an explicit render failure. */
  def render(p: OutboundPayload): IO[OutboundPayload] = renderer.render(p)

  // ─────────────────────────── degradation ───────────────────────────

  /**
   * 🔴 User-visible copy. The strings produced here are the section-16 review
   * items this node declares; they are NOT wired into the live bridge path in
   * this batch, so nothing reaches a Feishu chat today. Wiring them into a
   * production path requires the author's word-by-word sign-off first.
   */
  def degrade(p: OutboundPayload, reason: String): OutboundPayload.DegradedText =
    val why = if reason.trim.isEmpty then OutboundPlanner.Reason.RenderUnavailable else reason
    p match
      case OutboundPayload.ImageFile(path, _) =>
        OutboundPayload.DegradedText(
          ChannelAdapter.degradedBody(why, Some(path), sizeOf(path).getOrElse(0L)), why, Some(path))
      case OutboundPayload.DocumentFile(path, _, bytes) =>
        OutboundPayload.DegradedText(ChannelAdapter.degradedBody(why, Some(path), bytes), why, Some(path))
      case OutboundPayload.CardHtml(_, _, rendered) =>
        OutboundPayload.DegradedText(ChannelAdapter.degradedBody(why, rendered, 0L), why, rendered)
      case OutboundPayload.Text(body) =>
        OutboundPayload.DegradedText(s"$body\n[未渲染] $why", why, None)
      case d: OutboundPayload.DegradedText => d

  private def sizeOf(p: os.Path): Option[Long] =
    try if os.exists(p) then Some(os.size(p)) else None
    catch case _: Throwable => None

  // ─────────────────────────── dispatch ───────────────────────────

  /**
   * Execute the calls. Count conservation is structural: `map` over the call
   * list, so N calls in always means N results out. There is no arm here that
   * discards a call, and a failure is a reading, never an exception.
   */
  def dispatch(calls: List[ChannelCall]): IO[List[ChannelCallResult]] =
    calls.traverse(dispatchOne)

  private def dispatchOne(call: ChannelCall): IO[ChannelCallResult] = call match
    case ChannelCall.SendText(text) =>
      creds match
        case None =>
          IO.pure(ChannelCallResult.failed(call, -1, "no credential resolved — nothing was sent"))
        case Some(c) =>
          sendText(c.appId, c.appSecret, region, "", text).flatMap { res =>
            if res.ok then
              IO.pure(ChannelCallResult.ok(call, res.messageId, s"text delivered (${res.receiveIdType})"))
            else
              val detail = s"text rejected: ${res.msg}"
              logger.warn(s"feishu outbound: $detail (code=${res.code})")
                .as(ChannelCallResult.failed(call, res.code, detail))
          }

    case ChannelCall.UploadImage(path, thenSendImage) =>
      uploadImage(path).flatMap { up =>
        if !up.ok then
          // 🔴 Never "upload failed, so silently skip this one": the failure is a
          // reading carrying the channel's own code, plus a warn line (section
          // 7: every degraded path leaves a log). The message carries a PATH and
          // a CODE -- never a credential value.
          val detail = s"image upload rejected (${up.msg}) — path ${path.toString}"
          logger.warn(s"feishu outbound: $detail").as(ChannelCallResult.failed(call, up.code, detail))
        else if thenSendImage then
          IO.pure(ChannelCallResult.ok(call, up.key.orElse(up.messageId),
            s"image uploaded key=${up.key.getOrElse("-")}"))
        else IO.pure(ChannelCallResult.ok(call, up.key, "image uploaded"))
      }

    case ChannelCall.UploadFile(path, fileName, _) =>
      uploadFile(path, fileName).flatMap { up =>
        if !up.ok then
          val detail = s"file upload rejected (${up.msg}) — path ${path.toString}"
          logger.warn(s"feishu outbound: $detail").as(ChannelCallResult.failed(call, up.code, detail))
        else IO.pure(ChannelCallResult.ok(call, up.key.orElse(up.messageId),
          s"file uploaded key=${up.key.getOrElse("-")}"))
      }

    case ChannelCall.SendPost(title, paragraphs, imageKeys) =>
      IO.pure(ChannelCallResult.failed(call, -1,
        "post (rich text) is not landed in this batch — see the design card's open decision D-4"))

    case ChannelCall.SendInteractive(cardJson) =>
      IO.pure(ChannelCallResult.failed(call, -1,
        "interactive cards are not landed in this batch — see the design card's open decision D-3"))

  /**
   * The size gate, exposed so the adapter applies the SAME constant the planner
   * applies. Never a second copy of the limit.
   */
  def sizeGate(bytes: Long): Option[AttachContract.AttachError] =
    AttachContract.checkFileSize(bytes) match
      case Left(err) => Some(err)
      case Right(_)  => None

  /** Classify + plan in one step: the single entry a caller needs. */
  def planFor(event: Json): OutboundPlanner.OutboundPlan =
    val observed = observables(event)
    val paths = observed._2
    val cls = ContentClass.classify(observed._1, paths, ContentClass.fsStat, table)
    val text = event.hcursor.downField("text").as[String].toOption
    val bytes = paths.headOption.flatMap(sizeOf).getOrElse(0L)
    OutboundPlanner.plan(cls, paths.headOption, text, paths.headOption.map(_.last), bytes)

end FeishuOutboundAdapter

object FeishuOutboundAdapter:

  /** Outcome of one lark upload. `key` is `image_key` / `file_key` -- never a secret. */
  final case class UploadResult(
      ok: Boolean,
      key: Option[String],
      messageId: Option[String],
      code: Int,
      msg: String
  )

  object UploadResult:
    def ok(key: String): UploadResult =
      UploadResult(ok = true, key = Some(key), messageId = None, code = 0, msg = "ok")
    def failed(code: Int, msg: String): UploadResult =
      UploadResult(ok = false, key = None, messageId = None, code = code, msg = msg)

  type UploadImage = os.Path => IO[UploadResult]
  type UploadFile = (os.Path, String) => IO[UploadResult]

  /** The endpoint authority's table, welded to the tool-side twin by
    * `FileRefsWhitelistSpec` A14. Passed in rather than imported by the
    * definition layer (see [[ContentClass.ExtensionTable]]). */
  val defaultTable: ContentClass.ExtensionTable = NfFilePolicy.NfFileAllowedExt

  /** An upload seam that refuses everything -- the default so a mis-wired
    * adapter fails visibly instead of pretending. */
  val noUpload: UploadImage = _ => IO.pure(UploadResult.failed(-1, "no upload seam wired"))
  val noUploadFile: UploadFile = (_, _) => IO.pure(UploadResult.failed(-1, "no upload seam wired"))

end FeishuOutboundAdapter
