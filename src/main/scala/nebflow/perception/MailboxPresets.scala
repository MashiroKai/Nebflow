package nebflow.perception

/** One mailbox provider preset: the TLS endpoints a domain maps to. */
case class MailboxPreset(
  domain: String,
  provider: String,
  imapHost: String,
  imapPort: Int,
  smtpHost: String,
  smtpPort: Int,
  /** smtp uses implicit TLS on connect (465) vs STARTTLS upgrade (587). */
  smtpImplicitTls: Boolean,
  /** hint shown in the config card: this provider uses an app-specific auth code. */
  authCodeHint: Boolean
)

/**
 * 常见邮箱域 → 服务器预设（r3 假设 4：两问配置的「自动识别」腿）。
 *
 * 值来自各服务商公开的 IMAP/SMTP 文档口径（授权码模式）：
 *   - QQ/腾讯企业邮: imap.qq.com:993 / smtp.qq.com:465
 *   - 网易 163/126:  imap.163.com:993 / smtp.163.com:465（授权码）
 *   - Gmail:        imap.gmail.com:993 / smtp.gmail.com:465（应用专用密码）
 *   - Outlook/Hotmail/Live: outlook.office365.com:993 / smtp.office365.com:587 (STARTTLS)
 *   - iCloud:       imap.mail.me.com:993 / smtp.mail.me.com:587 (STARTTLS)
 *   - 新浪/搜狐/Yahoo: 同口径收录。
 *
 * 识别不到的域 = `None`（配置卡展开「高级」手填）。
 */
object MailboxPresets:

  private val byDomain: Map[String, MailboxPreset] = List(
    preset("qq.com", "QQ 邮箱", "imap.qq.com", "smtp.qq.com"),
    preset("foxmail.com", "QQ 邮箱", "imap.qq.com", "smtp.qq.com"),
    preset("vip.qq.com", "QQ 邮箱", "imap.qq.com", "smtp.qq.com"),
    preset("163.com", "网易 163", "imap.163.com", "smtp.163.com"),
    preset("126.com", "网易 126", "imap.126.com", "smtp.126.com"),
    preset("vip.163.com", "网易 163", "imap.163.com", "smtp.163.com"),
    preset("gmail.com", "Gmail", "imap.gmail.com", "smtp.gmail.com"),
    preset("googlemail.com", "Gmail", "imap.gmail.com", "smtp.gmail.com"),
    preset(
      "outlook.com",
      "Outlook",
      "outlook.office365.com",
      "smtp.office365.com",
      smtpPort = 587,
      implicitTls = false
    ),
    preset(
      "hotmail.com",
      "Outlook",
      "outlook.office365.com",
      "smtp.office365.com",
      smtpPort = 587,
      implicitTls = false
    ),
    preset("live.com", "Outlook", "outlook.office365.com", "smtp.office365.com", smtpPort = 587, implicitTls = false),
    preset("icloud.com", "iCloud", "imap.mail.me.com", "smtp.mail.me.com", smtpPort = 587, implicitTls = false),
    preset("me.com", "iCloud", "imap.mail.me.com", "smtp.mail.me.com", smtpPort = 587, implicitTls = false),
    preset("sina.com", "新浪邮箱", "imap.sina.com", "smtp.sina.com"),
    preset("sohu.com", "搜狐邮箱", "imap.sohu.com", "smtp.sohu.com"),
    preset("yeah.net", "网易 yes", "imap.yeah.net", "smtp.yeah.net"),
    preset("aliyun.com", "阿里邮箱", "imap.aliyun.com", "smtp.aliyun.com", smtpPort = 465)
  ).map(p => p.domain -> p).toMap

  private def preset(
    domain: String,
    provider: String,
    imap: String,
    smtp: String,
    imapPort: Int = 993,
    smtpPort: Int = 465,
    implicitTls: Boolean = true
  ): MailboxPreset =
    MailboxPreset(domain, provider, imap, imapPort, smtp, smtpPort, implicitTls, authCodeHint = true)

  /** Extract the lowercased domain part of an email address (after the last '@'). */
  def domainOf(address: String): String =
    address.trim.toLowerCase.indexOf('@') match
      case -1 => ""
      case i => address.trim.toLowerCase.substring(i + 1)

  /** Preset lookup by email address. Unknown domain → None (needs the advanced fold). */
  def forAddress(address: String): Option[MailboxPreset] = byDomain.get(domainOf(address))

  /** Test/DI seam: full preset table. */
  def all: List[MailboxPreset] = byDomain.values.toList.sortBy(_.domain)
end MailboxPresets
