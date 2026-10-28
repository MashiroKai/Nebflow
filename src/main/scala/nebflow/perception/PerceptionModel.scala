package nebflow.perception

import io.circe.generic.semiauto.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}

/**
 * 感知域模型（r2 规格条目化面板 §9 + 状态机 §4.5 四色投影 + r3 极简修订）。
 *
 * 面板契约（§9.2）：条目 = id/icon/name/state/summary/detail；本批邮件 =
 * 首个条目，文件/日历为预留位（面板前端渲染，后端 entries 只出真实条目）。
 */
object PerceptionModel:

  /** 邮件条目状态（状态机八态 → 面板四色投影由 `colorOf` 承担）。 */
  enum EntryState:
    case Unconfigured, Connecting, Idle, Degraded, Paused, AuthFailed, Backoff, Error

  object EntryState:
    given Encoder[EntryState] = Encoder.encodeString.contramap(_.toString)
    given Decoder[EntryState] = Decoder.decodeString.emap(s =>
      EntryState.values.find(_.toString.equalsIgnoreCase(s)).toRight(s"unknown entry state: $s")
    )

    /** 四色投影：ok / warn / bad / dim（面板只认这四色，语义色只上载体）。 */
    def colorOf(s: EntryState): String = s match
      case Idle                              => "ok"
      case Degraded | Backoff                => "warn"
      case AuthFailed | Error                => "bad"
      case Unconfigured | Connecting | Paused => "dim"

  /** 一个邮箱账户的配置（授权码不入此结构——授权码只进 secrets）。 */
  case class MailAccount(
    id: String,
    address: String,
    provider: String = "",
    imapHost: String = "",
    imapPort: Int = 993,
    smtpHost: String = "",
    smtpPort: Int = 465,
    smtpImplicitTls: Boolean = true,
    displayName: Option[String] = None,
    trustedSenders: List[String] = Nil,
    enabled: Boolean = true,
    /** secrets 存储里的授权码文件名（值永不落此文件）。 */
    authCodeSecret: String = "",
    /** 连接失败后的自动恢复：next attempt epoch-ms（0 = 无）。 */
    nextAttemptAt: Long = 0L,
    backoffAttempt: Int = 0,
    createdAt: Long = 0L
  ):
    def secretName: String = MailAccount.secretNameFor(address)

  object MailAccount:
    def secretNameFor(address: String): String =
      val safe = address.toLowerCase.replaceAll("[^a-z0-9._-]", "-").take(40).stripSuffix("-")
      // SecretsStore 命名 ^[a-z0-9][a-z0-9._-]{0,63}$
      s"perception-$safe"
    given Encoder[MailAccount] = deriveEncoder
    given Decoder[MailAccount] = deriveDecoder

  /** 面板条目（mail 条目的 wire 形态）。 */
  case class MailEntry(
    id: String,
    icon: String,
    name: String,
    state: EntryState,
    summary: String,
    /** 异常 banner 文案（人话 + 动作）；正常为空。 */
    notice: String = "",
    /** 展开区动作位（前端按 id 渲染既有按钮组）。 */
    actions: List[String] = Nil
  )

  object MailEntry:
    given Encoder[MailEntry] = deriveEncoder
    given Decoder[MailEntry] = deriveDecoder

  /** GET /api/perception/entries 的载荷（邮件条目 + 预留位由前端补）。 */
  case class EntriesPayload(entries: List[MailEntry], serverTime: Long)

  object EntriesPayload:
    given Encoder[EntriesPayload] = deriveEncoder
    given Decoder[EntriesPayload] = deriveDecoder

  /** 一封已收新邮件（进程内传递 + 审计摘要 + 投递信封原料）。 */
  case class IncomingMail(
    uid: Long,
    from: String,
    fromDomain: String,
    to: List[String],
    subject: String,
    date: String,
    messageId: String,
    bodyText: String
  ):
    def maskedCodeFree: String = this.copy(bodyText = "[omitted]").bodyText

  /** 验证码窗口（单码槽）：窗开于 ask 卡弹出，窗内检测到的码只存进程内存。 */
  case class OtpWindow(
    windowId: String,
    requestId: String,
    openedAt: Long,
    expiresAt: Long,
    /** 当前槽内码（单槽覆盖模型：窗内第二码覆盖第一码）。 */
    hit: Option[OtpHit] = None,
    /** consumed = 码已被前端取走（槽清空，窗口仍开着等新码）。 */
    consumed: Boolean = false
  )

  case class OtpHit(code: String, from: String, fromDomain: String, receivedAt: Long)

  object OtpHit:
    given Encoder[OtpHit] = deriveEncoder
    given Decoder[OtpHit] = deriveDecoder

  object OtpWindow:
    given Encoder[OtpWindow] = deriveEncoder
    given Decoder[OtpWindow] = deriveDecoder

  /** 草稿注册表行（drafts.json；正文在草稿文件本身）。 */
  case class DraftRecord(
    draftId: String,
    path: String,
    createdAt: Long,
    /** 快照卡生成时刻（一致性守则：mtime > snapshotAt ⇒ 快照发送 409）。 */
    snapshotAt: Long,
    state: String = "pending", // pending | sent | abandoned | expired
    sentAt: Long = 0L,
    messageId: String = "",
    /** ask 卡 requestId（回执/关闭联动）。 */
    requestId: String = "",
    /** 草稿生成时的身份（回执显示）。 */
    toSummary: String = "",
    subject: String = "",
    attachmentCount: Int = 0
  )

  object DraftRecord:
    given Encoder[DraftRecord] = deriveEncoder
    given Decoder[DraftRecord] = deriveDecoder

  /** 审计行（audit.jsonl；不落全文/明码——纪律沿用 r2 §11）。 */
  case class AuditLine(
    ts: Long,
    stage: String, // L0 | deliver | otp | send | digest | state
    reason: String,
    detail: String = ""
  )

  object AuditLine:
    given Encoder[AuditLine] = deriveEncoder
    given Decoder[AuditLine] = deriveDecoder

  /** 投递信封（注入主代理的文本 + 优先级）。 */
  case class DeliveryEnvelope(priority: String, text: String)

  object DeliveryEnvelope:
    given Encoder[DeliveryEnvelope] = deriveEncoder
    given Decoder[DeliveryEnvelope] = deriveDecoder

  /** 发送请求结果（REST 面的语义化失败，回执卡直接可用）。 */
  enum SendFailure:
    case AuthFailed
    case Network(msg: String)
    case Rejected(msg: String) // 收件方拒收（5xx）
    case Stale // 快照过期（409）
    case MissingAttachment(paths: List[String])
    case NoAccount
    case Invalid(msg: String)

  object SendFailure:
    def readable(f: SendFailure): String = f match
      case AuthFailed              => "授权码已失效 · 需重新连接邮箱"
      case Network(m)              => s"网络异常，稍后自动重试（$m）"
      case Rejected(_)             => "收件方拒收 · 地址可能已失效"
      case Stale                   => "草稿已在 Canvas 中修改，请以画布内容发送"
      case MissingAttachment(paths) => s"附件不存在：${paths.mkString("、")}"
      case NoAccount               => "尚未连接邮箱"
      case Invalid(m)              => m
