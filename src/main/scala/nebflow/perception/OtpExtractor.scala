package nebflow.perception

import scala.util.matching.Regex

/**
 * OTP（验证码）提取：新邮件正文/主题 → 单码片数据面（r2 §6 / r3 裁决 2）。
 *
 * 纯函数层，无 IO：PerceptionService 对每封新邮件调用 [[extract]]，命中 =
 * [[OtpCandidate]]（码值 + 置信度 + 证据上下文）。启发式两把尺：
 *
 * ① 意图词（邮件是「在发验证码」的信）：验证码 / 校验码 / verification code /
 *    one-time / OTP / 安全码 / 登录码 …
 * ② 码形：独立 4-8 位数字段（前后非数字），或字母数字混合 6-8 位大写段
 *    （`G7K2QX` 形，需更强意图词共现）。
 *
 * 置信序：主题意图+数字段 > 正文意图+数字段 > 混合段。
 * 同封多命中取首高置信（单码槽模型：一个窗口时刻只有一个码）。
 */
object OtpExtractor:

  case class OtpCandidate(code: String, confidence: Int, context: String)

  /** 意图词（中英），命中面加权：subject 命中比正文命中更可信。 */
  private val intentWords: List[String] = List(
    "验证码",
    "校验码",
    "确认码",
    "动态码",
    "安全码",
    "登录码",
    "一次性密码",
    "动态密码",
    "verification code",
    "verify code",
    "security code",
    "one-time password",
    "one time password",
    "one-time",
    "otp",
    "login code",
    "confirmation code",
    "passcode"
  )

  /** 独立 4-8 位数字段：前后不是数字/字母（排除订单号、金额、年份串）。 */
  private val numeric: Regex = """(?<![0-9A-Za-z])([0-9]{4,8})(?![0-9])""".r

  /** 3 位一组、空格分隔的 6-8 位段（Google 式「993 121」/「123 456」）。 */
  private val spaced: Regex = """(?<![0-9A-Za-z])([0-9]{3}(?: [0-9]{3}){1,2})(?![0-9])""".r

  /** 6-8 位大写字母数字混合段（至少一字母一数字）。 */
  private val alnum: Regex = """(?<![0-9A-Za-z])([A-Z0-9]{6,8})(?![0-9A-Za-z])""".r

  private def hasIntent(text: String): Boolean =
    val lower = text.toLowerCase
    intentWords.exists(lower.contains)

  private def alnumLooksLikeCode(s: String): Boolean =
    s.exists(_.isDigit) && s.exists(_.isLetter)

  /**
   * 提取验证码。`subject` 命中意图词时数字段整体置信 +2（标题比正文更「点名」）。
   * 返回按置信降序的候选，调用方（单码槽）取 head。
   */
  def extract(subject: String, body: String): List[OtpCandidate] =
    val s = Option(subject).getOrElse("")
    val b = Option(body).getOrElse("")
    val subjectIntent = hasIntent(s)
    val bodyIntent = hasIntent(b)
    if !subjectIntent && !bodyIntent then Nil
    else
      val candidates = scala.collection.mutable.ArrayBuffer.empty[OtpCandidate]
      numeric.findAllMatchIn(b).foreach { m =>
        val base = 5 + (if subjectIntent then 2 else 0)
        candidates += OtpCandidate(m.group(1), base, s"numeric · …${ctxAround(b, m.start)}…")
      }
      numeric.findAllMatchIn(s).foreach { m =>
        candidates += OtpCandidate(m.group(1), 8, "numeric-in-subject")
      }
      spaced.findAllMatchIn(b).foreach { m =>
        val joined = m.group(1).replace(" ", "")
        candidates += OtpCandidate(joined, 4 + (if subjectIntent then 2 else 0), s"spaced · …${ctxAround(b, m.start)}…")
      }
      // 混合段：仅在强意图下认（subject 命中意图词，或正文含 OTP/one-time 类词）
      if subjectIntent || b.toLowerCase.contains("otp") || b.toLowerCase.contains("one-time") then
        alnum.findAllMatchIn(b).foreach { m =>
          val v = m.group(1)
          if alnumLooksLikeCode(v) then
            candidates += OtpCandidate(v, 4 + (if subjectIntent then 2 else 0), s"alnum · …${ctxAround(b, m.start)}…")
        }
      candidates.toList
        .filterNot(c => c.code.length == 4 && c.code.forall(_.isDigit) && looksLikeYear(c.code))
        .sortBy(-_.confidence)
        .take(3)

    end if

  end extract

  private def looksLikeYear(s: String): Boolean =
    val n = s.toIntOption.getOrElse(0)
    n >= 1900 && n <= 2100

  /** 取命中点前后 ~16 字符做证据上下文（audit 用，不进 UI）。 */
  private def ctxAround(text: String, idx: Int): String =
    val from = math.max(0, idx - 16)
    val to = math.min(text.length, idx + 16)
    text.substring(from, to).replaceAll("\\s+", " ")

  /** 掩码显示（audit / 会话日志纪律：只落 `824***`，码值不进任何日志面）。 */
  def mask(code: String): String =
    if code.length <= 3 then "***"
    else code.take(3) + "***"
end OtpExtractor
