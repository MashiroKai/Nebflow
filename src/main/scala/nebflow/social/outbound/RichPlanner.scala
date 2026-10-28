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

/**
 * Reading for one attempted call. `detail` is non-empty on every failure —
 *  that is the machine-checkable half of "never drop silently" (design card
 *  §7.3 #2).
 */
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

  /**
   * 机器可匹配的占位符（social-webshot batch）：原 `[未知类型]` 前缀随英文机制
   * 原因一并退役（机制词泄漏进用户聊天的实况见 [[unknown]] 的文档）。
   */
  val UnsupportedPrefix: String = "[暂不支持]"
  val UnreachablePrefix: String = "[未送达]"

  /** The closed reason vocabulary of design card §7.1. */
  object Reason:
    val EngineUnavailable = "渲染件不可用"
    val RenderTimeout = "渲染超时"
    val RenderInvalid = "渲染输出非法(PNG 校验失败)"
    val SizeGateRejected = "尺寸闸拒绝"
    val FileUnreadable = "原件不可读"

    /** 文件腿体积闸（pop-file-leg batch, 2026-10-21）：超引擎闸（sidecar 发送腿同款 25MB）。 */
    val FileOversize = "文件超出社交腿上限"
    def channelRejected(code: Int): String = s"渠道拒绝(code=$code)"

  /** §7.1: `[未渲染] <reason> — 原件：<path>（<bytes> 字节）`. */
  def renderFailed(reason: String, path: os.Path, bytes: Long): String =
    s"[未渲染] $reason — 原件：$path（$bytes 字节）"

  /**
   * §7.1 for a CARD, whose "original" is not a file on disk but the HTML payload
   * the product arrived as. The template shape is kept (`[未渲染]` + reason +
   * `原件：…` + byte count) so §7.3 #3's machine-checkable placeholder still
   * fires and a reader sees the same shape it sees for the file kinds — the
   * `原件` slot simply names the payload instead of a path.
   *
   * 🔴 Without this the card degrade path emitted the bare reason, which contains
   * no `[未渲染]` and would therefore have slipped past the "never drop silently"
   * check. The offline spec pins it (R-5/R-5b).
   */
  def cardNotRendered(reason: String, payloadBytes: Long): String =
    s"[未渲染] $reason — 原件：卡片内容（HTML 载荷，$payloadBytes 字节）"

  /**
   * b 层：渠道既带不了也渲不了的类型，给用户的一行友好中文（social-webshot
   * batch, 2026-10-09）。原 §7.2 模板 `[未知类型] <reason> — 原件：<path>` 退役：
   * `reason` 是分类器的机制词汇（如 `extension is in neither authority table:
   * html`），用户实况证明它会被原样透到聊天里——机制词现在只进日志（调用方
   * WARN），本行只回答用户关心的事：什么看不了、原件在哪。
   *
   * 两个占位符保持机器可匹配（§7.3 #2 的精神不变，只是中文化）：
   *   - `[暂不支持]` —— 原件在盘上、但类型带不出去（本次实况的形状）；
   *   - `[未送达]` —— 原件本身已不可达（路径不存在/非普通文件/事件没带路径）。
   *
   * @param path    产物的绝对路径（有则附上，桌面端可打开）
   * @param missing 原件已不在盘上（分类时 exists=false，或事件根本没带路径）
   */
  def unknown(path: Option[os.Path], missing: Boolean = false): String =
    if missing then
      path match
        case Some(p) => s"$UnreachablePrefix 原件没找到（可能已被清理）：$p"
        case None => s"$UnreachablePrefix 这条消息没有可发送的文件"
    else
      val name = path.fold("该文件")(_.last)
      path match
        case Some(p) => s"$UnsupportedPrefix 这个格式在聊天里看不了（$name），原件在桌面端：$p"
        case None => s"$UnsupportedPrefix 这个格式在聊天里看不了（$name）"

  /**
   * 文件类长图腿的伴行注（pop-social-files batch, 2026-10-20）：md/html 以
   * 派生单张长图发出时，文字腿附原件绝对路径——聊天里看到的是转换产物，原件
   * （交互/原始形态）只有桌面端有。原 `webPageNote`（「网页原件…」，html 文件
   * 腿的截图兜底面）随该腿整体退役——本注对 md/html 同一口径，不再叫「网页」。
   */
  def originalNote(original: os.Path): String =
    s"原件（桌面端可打开）：$original"

  /**
   * 转不动 ⇒「文件名+本地路径+一句话摘要」文本卡（pop-social-files batch，
   * 2026-10-20 作者令逐字口径）。占位符 `[未转图]` 机器可匹配（§7.3 #2 的精神）：
   * 长图转换未成功（渲染件不可用/超时/产物非法），先给原件信息，不静默。
   *
   * @param p       原件绝对路径
   * @param summary 一句话摘要（[[FileLongShot.summarize]]；None ⇒ 省略摘要行）
   */
  def unconverted(p: os.Path, summary: Option[String]): String =
    val head = s"$UnconvertedPrefix ${p.last} — 聊天里转不出长图，先给你原件信息"
    val tail = s"原件在桌面端：$p"
    summary.fold(s"$head\n$tail")(s => s"$head\n内容摘要：$s\n$tail")

  /** `[[unconverted]]` 的机器可匹配占位符。 */
  val UnconvertedPrefix: String = "[未转图]"

  /**
   * 文件腿体积闸降级（pop-file-leg batch, 2026-10-21 作者令「超限降级为文本
   * 说明+控制台指引」）：超限件不发起发送（sidecar 必 400），改发文本说明——
   * 文件名 + 实际大小 + 上限 + 原件桌面端路径 + 控制台取件指引。占位符
   * `[文件过大]` 机器可匹配（§7.3 #2 的精神）。大小用 MB 一位小数（人类读数）。
   *
   * @param p      原件绝对路径
   * @param bytes  实际字节数（读不到 ⇒ 省略大小行）
   * @param limit  上限字节（[[FileLeg.MaxBytes]]）
   */
  def oversizeFile(p: os.Path, bytes: Option[Long], limit: Long): String =
    // 十进制 MB（10^6）：上限本体是 25_000_000 字节（sidecar 发送腿同款字节闸），
    // 显示口径与闸值严格一致（MiB 除数会把 25MB 显示成 23.8MB，两套口径打架）。
    val mb = (n: Long) => f"${n / 1_000_000.0}%.1fMB"
    val head = s"$OversizePrefix ${p.last}（${Reason.FileOversize}：上限 ${mb(limit)}" +
      bytes.fold("")(b => s"，本件 ${mb(b)}") + "）"
    s"$head\n原件在桌面端：$p\n微信里收不了这么大的文件，请到 Stand 控制台该会话取件（卡片可下载）。"

  /** `[[oversizeFile]]` 的机器可匹配占位符。 */
  val OversizePrefix: String = "[文件过大]"

  /**
   * §6.2 step 4: the oversize reading also becomes the user-visible tail.
   *  `code` / `actual` / `limit` come from the EXISTING gate error, not from a
   *  second set of constants.
   */
  def tooLarge(err: AttachContract.AttachError, path: os.Path): String =
    val actual = err.actual.map(_.toString).getOrElse("?")
    val limit = err.limit.map(_.toString).getOrElse(AttachContract.MaxFileBytes.toString)
    s"[未渲染] ${Reason.SizeGateRejected} — 原件：$path（$actual 字节，上限 $limit 字节，${err.code}）"

end RichDegrade

/**
 * Fail-closed ledger reading: one per attempted call, so the count of intents
 *  and the count of readings are equal by construction (§7.3 #1).
 */
object RichLedger:

  def of(call: RichCall, ok: Boolean, code: Int, messageId: Option[String], detail: String): RichCallResult =
    RichCallResult(
      call,
      ok,
      code,
      messageId,
      if ok then detail else if detail.trim.isEmpty then "unspecified failure" else detail
    )

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

  /**
   * Card, rendered successfully: the static screenshot first, then the note
   *  that points at the live version. Two calls, in this order — the order is
   *  part of the contract, not an implementation detail.
   */
  def planCard(rendered: os.Path): List[RichCall] =
    List(RichCall.UploadImage(rendered, thenSendImage = true), RichCall.SendText(InteractivePanelNote))

  /**
   * Card, NOT rendered: a single degraded text carrying both the §7.1
   *  placeholder and the note, so the recipient learns that the static product
   *  failed AND that a live version exists. Zero upload, zero image send.
   */
  def degradeCard(reason: String, path: os.Path, bytes: Long): List[RichCall] =
    List(RichCall.SendText(RichDegrade.renderFailed(reason, path, bytes) + "\n" + InteractivePanelNote))

  /**
   * Image product: sent as-is (route D). Real bytes, no re-encode, no render —
   *  handing a PNG to a renderer would be pure loss and would risk violating
   *  the "no mock image" rule.
   */
  def planImage(path: os.Path): List[RichCall] =
    List(RichCall.UploadImage(path, thenSendImage = true))

  /**
   * Document product: sent as the original file (route E for PDFs — no
   *  rasterisation at all, the file IS the deliverable).
   */
  def planDocument(path: os.Path, fileName: String): List[RichCall] =
    List(RichCall.UploadFile(path, fileName, thenSendFile = true))

  /**
   * Unknown kind: fail-closed to text, never a silent drop. The mechanism
   * `reason` is NOT rendered (see [[RichDegrade.unknown]]): it belongs to the
   * caller's WARN log, the recipient gets the friendly one-liner.
   */
  def planUnknown(path: Option[os.Path], missing: Boolean = false): List[RichCall] =
    List(RichCall.SendText(RichDegrade.unknown(path, missing)))

  /**
   * Size-gate rejection: zero upload, zero send of the product; one explicit
   *  text reading with the actual byte count and the limit.
   */
  def planTooLarge(err: AttachContract.AttachError, path: os.Path): List[RichCall] =
    List(RichCall.SendText(RichDegrade.tooLarge(err, path)))
end RichPlanner
