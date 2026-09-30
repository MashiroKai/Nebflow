package nebflow.social.outbound

import nebflow.shared.AttachContract

/**
 * Call plan for the FEISHU outbound leg of rich content (richcontent batch,
 * 2026-09-30). Pure data + pure builders: zero IO, zero SDK — this is the half
 * the offline spec can pin exhaustively.
 *
 * ⚠️ Naming: the sibling batch `socoutbound-impl-a-r2` owns the
 * channel-agnostic definition layer in THIS SAME package (design card §2:
 * `ContentClass` / `OutboundPayload` / `ChannelCall` / `ChannelCallResult` /
 * `ChannelAdapter` / `OutboundPlanner`). This file must therefore not claim
 * those names, so the whole surface here is `Rich`-prefixed:
 * `RichCall` / `RichCallResult` / `RichDegrade` / `RichPlanner`. The two legs
 * are merged into one package by the sink, so a shared name would be a compile
 * error at merge time, not a style question. See the overlap declaration in the
 * node result.
 *
 * `thenSend*` is kept explicit rather than implied so a plan reads as an intent
 * list.
 */
enum RichCall:
  case SendText(text: String)
  case UploadImage(path: os.Path, thenSendImage: Boolean)
  case UploadFile(path: os.Path, fileName: String, thenSendFile: Boolean)

/** Reading for one attempted call. `detail` is non-empty on every failure —
  *  that is the machine-checkable half of "never drop silently" (design card
  *  §7.3 #2). */
final case class RichCallResult(
    call: RichCall,
    ok: Boolean,
    code: Int,
    messageId: Option[String],
    detail: String
)

/**
 * The degrade text builders, verbatim from the design card §7.1/§7.2.
 *
 * 🔴 These strings are the user-visible half of the fail-closed contract: a
 * caller that cannot send the rich product still has to say WHAT could not be
 * sent and WHY. The reason vocabulary is CLOSED (design card §7.1) so the
 * downstream reader can match on it instead of parsing prose.
 */
object RichDegrade:

  /** The closed reason vocabulary of design card §7.1. */
  object Reason:
    val EngineUnavailable = "渲染件不可用"
    val RenderTimeout = "渲染超时"
    val RenderInvalid = "渲染输出非法(PNG 校验失败)"
    val SizeGateRejected = "尺寸闸拒绝"
    val FileUnreadable = "原件不可读"
    def channelRejected(code: Int): String = s"渠道拒绝(code=$code)"

  /** §7.1: `[未渲染] <reason> — 原件：<path>（<bytes> 字节）`. */
  def renderFailed(reason: String, path: os.Path, bytes: Long): String =
    s"[未渲染] $reason — 原件：$path（$bytes 字节）"

  /** §7.2: `[未知类型] <reason> — 原件：<path>`. `path` may be absent (an event
    *  with no product path at all still gets a line, never a silence). */
  def unknown(reason: String, path: Option[os.Path]): String =
    val r = if reason.trim.isEmpty then "unspecified" else reason.trim
    path match
      case Some(p) => s"[未知类型] $r — 原件：$p"
      case None    => s"[未知类型] $r"

  /** §6.2 step 4: the oversize reading also becomes the user-visible tail.
    *  `code` / `actual` / `limit` come from the EXISTING gate error, not from a
    *  second set of constants. */
  def tooLarge(err: AttachContract.AttachError, path: os.Path): String =
    val actual = err.actual.map(_.toString).getOrElse("?")
    val limit = err.limit.map(_.toString).getOrElse(AttachContract.MaxFileBytes.toString)
    s"[未渲染] ${Reason.SizeGateRejected} — 原件：$path（$actual 字节，上限 $limit 字节，${err.code}）"

/** Fail-closed ledger reading: one per attempted call, so the count of intents
  *  and the count of readings are equal by construction (§7.3 #1). */
object RichLedger:
  def of(call: RichCall, ok: Boolean, code: Int, messageId: Option[String], detail: String): RichCallResult =
    RichCallResult(call, ok, code, messageId, if ok then detail else if detail.trim.isEmpty then "unspecified failure" else detail)

/**
 * The pure planner: one decision in, one intent list out. No IO anywhere, so
 * every row of the design card's §5.3 red-verification table can be asserted
 * offline.
 */
object RichPlanner:

  /**
   * The card-degrade note (author ruling 2026-09-30, verbatim). A locally
   * rendered card is an interactive front-end artefact, while what Feishu can
   * carry is a static image — so the recipient must be told where the live
   * version lives. 🔴 This line is MANDATORY whenever a card product is sent as
   * an image; treating an interactive product as a plain picture with no note
   * is the red line (design card §4, task brief §二.2 item 4).
   */
  val InteractivePanelNote: String = "交互版见本地面板"

  /** Card, rendered successfully: the static screenshot first, then the note
    *  that points at the live version. Two calls, in this order — the order is
    *  part of the contract, not an implementation detail. */
  def planCard(rendered: os.Path): List[RichCall] =
    List(RichCall.UploadImage(rendered, thenSendImage = true), RichCall.SendText(InteractivePanelNote))

  /** Card, NOT rendered: a single degraded text carrying both the §7.1
    *  placeholder and the note, so the recipient learns that the static product
    *  failed AND that a live version exists. Zero upload, zero image send. */
  def degradeCard(reason: String, path: os.Path, bytes: Long): List[RichCall] =
    List(RichCall.SendText(RichDegrade.renderFailed(reason, path, bytes) + "\n" + InteractivePanelNote))

  /** Image product: sent as-is (route D). Real bytes, no re-encode, no render —
    *  handing a PNG to a renderer would be pure loss and would risk violating
    *  the "no mock image" rule. */
  def planImage(path: os.Path): List[RichCall] =
    List(RichCall.UploadImage(path, thenSendImage = true))

  /** Document product: sent as the original file (route E for PDFs — no
    *  rasterisation at all, the file IS the deliverable). */
  def planDocument(path: os.Path, fileName: String): List[RichCall] =
    List(RichCall.UploadFile(path, fileName, thenSendFile = true))

  /** Unknown kind: fail-closed to text, never a silent drop. */
  def planUnknown(reason: String, path: Option[os.Path]): List[RichCall] =
    List(RichCall.SendText(RichDegrade.unknown(reason, path)))

  /** Size-gate rejection: zero upload, zero send of the product; one explicit
    *  text reading with the actual byte count and the limit. */
  def planTooLarge(err: AttachContract.AttachError, path: os.Path): List[RichCall] =
    List(RichCall.SendText(RichDegrade.tooLarge(err, path)))
