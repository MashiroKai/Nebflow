package nebflow.perception

import PerceptionModel.*
import cats.effect.IO
import io.circe.syntax.*
import nebflow.shared.{LlmRequest, Message, MessageRole}

import scala.concurrent.duration.*

/**
 * L1 LLM 重要性判定腿（r2 §5.2「一次调用定生死」，r3 未推翻；作者构想 1 逐字
 * 「新邮件先用代码级自动过滤……然后一次 LLM 调用判断这封邮件是否需要投递」）。
 *
 * 纪律（全部 r2 §5.2 逐字 + §11 裁决 3）：
 *   - **数据面锁定最小化**：输入 = 头部（from/to/subject/date）+ 正文截断
 *     4KB（[[BodyCapChars]]）——超长只发截断注记，永不发全文。
 *   - **一次调用三值**：deliver-full / drop（deliver-digest 为 P3 预留不实现）。
 *   - **降级**：LLM 不可用（无 key / 网络失败 / 超时 [[TimeoutSec]]s / 不可解析）
 *     ⇒ Unavailable——调用方搁置 spool 待恢复，不臆测投递也不臆测丢弃。
 *   - 并发预算「同一时刻最多 1 个在途判定」由调用方单 pollLoop 串行保证。
 *
 * 纯函数面（buildUserContent / parseVerdict / promptFor）全部可 spec；socket/LLM
 * 腿集中在 [[judgeViaLlm]]（PerceptionService 装配）。
 */
object MailImportanceJudge:

  /** 数据面正文截断（r2 §5.2「正文截断 4KB」；按字符计，UTF-8 下 ≤4KB 语义）。 */
  val BodyCapChars: Int = 4096

  /** 有界判定超时（r2 §5.2「超时 30s」）。 */
  val TimeoutSec: Int = 30

  /** LLM 会话/代理身份（维护型调用面；不命中任何 sessionModelOverrides）。 */
  val JudgeSessionId: String = "perception"
  val JudgeAgentId: String = "perception"

  /** 判定提示词（固定；「用户可选个性化判定提示词」的 UI 入口 r3 未定 → 开放问题）。 */
  val SystemPrompt: String =
    "你是邮件过滤判定器。根据给出的邮件头部与正文节选，判断这封邮件是否值得投递给" +
      "用户的个人助理代理（用于自动化处理）。判定基准：真人来信、需要用户或代理行动的" +
      "事务（账单提醒、预约、账户通知、工作往来等）投递；纯营销、广告、订阅推广、" +
      "社交网络群发通知、无行动价值的自动通告丢弃。只回答一个词：DELIVER 或 DROP。"

  /** 用户侧内容（纯函数，spec 直测；头部 + 截断正文）。 */
  def buildUserContent(mail: IncomingMail): String =
    val body = Option(mail.bodyText).getOrElse("")
    val clipped =
      if body.length > BodyCapChars then body.take(BodyCapChars) + s"\n[正文超长，已截断：原文 ${body.length} 字符]"
      else body
    s"""发件人: ${mail.from}
收件人: ${mail.to.mkString(", ")}
主题: ${mail.subject}
日期: ${mail.date}
——— 正文节选 ———
$clipped"""

  /** LLM 请求组装（维护型调用：searchAllowed=false，见 LlmRequest 纪律注）。 */
  def promptFor(mail: IncomingMail): LlmRequest =
    LlmRequest(
      messages = List(Message(MessageRole.User, Left(buildUserContent(mail)))),
      sessionId = JudgeSessionId,
      agentId = JudgeAgentId,
      systemStable = Some(SystemPrompt),
      searchAllowed = false
    )

  /**
   * 回复 → 三值裁决（纯函数）。判据：回复中 DELIVER / DROP 词形（大小写不敏感，
   * 容忍模型附带说明）；两者皆无或两者皆有（病态回复）= Unavailable（按降级
   * 处理，不臆测）。
   */
  def parseVerdict(reply: String): L1Verdict =
    val r = Option(reply).getOrElse("").toUpperCase
    val hasD = r.contains("DELIVER")
    val hasX = r.contains("DROP")
    (hasD, hasX) match
      case (true, false) => L1Verdict.Deliver
      case (false, true) => L1Verdict.Drop
      case _             => L1Verdict.Unavailable

  /**
   * 生产判定腿：resources.llm.send 一次调用（AgentPingpong `.send.attempt` 同形）
   * + 有界超时。任何失败（传输/超时/解析）⇒ Unavailable——语义由调用方落
   * （搁置 spool）。挂在 PerceptionCore 的 judge seam 后面。
   */
  def judgeViaLlm(
      send: LlmRequest => IO[LlmRespLike]
  )(mail: IncomingMail): IO[L1Verdict] =
    send(promptFor(mail))
      .timeout(TimeoutSec.seconds)
      .attempt
      .map {
        case Right(resp) => parseVerdict(resp.reply)
        case Left(_)     => L1Verdict.Unavailable
      }

  /** 结构最小面（避开 shared LlmResponse 的重依赖，spec 桩可直构）。 */
  trait LlmRespLike:
    def reply: String
