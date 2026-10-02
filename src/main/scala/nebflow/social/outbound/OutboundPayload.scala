package nebflow.social.outbound

/**
 * Channel-agnostic outbound payloads and call intents (design card section 2).
 *
 * Pure data: no IO, no SDK, no logger. `path` is always an absolute local path
 * -- the same convention `MailTool`'s `images` parameter already uses.
 */
enum OutboundPayload:
  case Text(body: String)
  case ImageFile(path: os.Path, mimeType: String)
  case DocumentFile(path: os.Path, fileName: String, bytes: Long)
  case CardHtml(html: String, title: Option[String], rendered: Option[os.Path])
  /** section 7 degraded carrier: unknown/failed => text plus a placeholder note. */
  case DegradedText(body: String, reason: String, originalPath: Option[os.Path])

/** One outbound call intent. Pure data -- nothing here performs network work. */
enum ChannelCall:
  case SendText(text: String)
  case UploadImage(path: os.Path, thenSendImage: Boolean)
  case UploadFile(path: os.Path, fileName: String, thenSendFile: Boolean)
  case SendPost(title: Option[String], paragraphs: List[String], imageKeys: List[String])
  case SendInteractive(cardJson: String)

/** Outcome of one dispatched call. Every call yields one of these -- count
  * conservation is an invariant (design card section 7.3, criterion 1). */
final case class ChannelCallResult(
    call: ChannelCall,
    ok: Boolean,
    code: Int,
    messageId: Option[String],
    detail: String
)

object ChannelCallResult:

  def ok(call: ChannelCall, messageId: Option[String], detail: String = "delivered"): ChannelCallResult =
    ChannelCallResult(call = call, ok = true, code = 0, messageId = messageId, detail = detail)

  /** A failure reading is an evidence artifact: `detail` must never be empty. */
  def failed(call: ChannelCall, code: Int, detail: String): ChannelCallResult =
    ChannelCallResult(call = call, ok = false, code = code, messageId = None,
      detail = if detail.trim.isEmpty then "unspecified failure" else detail)
