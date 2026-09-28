package nebflow.core.project

import io.circe.parser

/** verify 判定文法解析器（LoopNode 批 2026-09-06，主设计 20260902_flowmap-engine-evolution-design.md §2.3；
  * 与 BlockedReader :1730 同构、同注释风格，独立 object 便于单测）。
  *
  * - **锚定**：verify 最终输出（extractLastAssistantText 同款提取）trim 后**首个非空行**
  *   必须整体匹配 `^VERDICT:\s*(PASS|FAIL)$`（大小写不敏感，归一化为大写；行内除
  *   PASS/FAIL 外不得有其他内容——防 `VERDICT: PASS (mostly)` 这类模糊判定）。
  * - **FAIL JSON 体**：首个 `{` 到末个 `}`（jsonBody 同款），circe 解析
  *   `{ "issues": ["<问题 1>", "<问题 2>"], "requirements": "<通过标准，可空>" }`——
  *   issues 必须为非空字符串数组；requirements 可选字符串。
  * - **四态判定表**（§2.3，与 BlockedReader 的「锚定保留 + 畸形降级」对称设计）：
  *
  *   | verify 输出形态 | 判定 | 动作 |
  *   |---|---|---|
  *   | 锚定 `VERDICT: PASS` | Pass | completeNode |
  *   | 锚定 `VERDICT: FAIL` + JSON 合法（issues 非空） | Fail(issues, requirements) | 打回 worker |
  *   | 锚定 `VERDICT: FAIL` + JSON 缺失/畸形/issues 空 | Fail(占位意见, "") | 打回 worker（FAIL 锚定保留） |
  *   | 无锚定行（文法畸形） | **降级 = Pass 直通** | completeNode |
  *
  * - **降级哲学**（与 BLOCKED ①号方案的无损降级同构对称）：文法只用于收紧特殊语义，
  *   畸形输出永远回落到默认正常路径，绝不因格式问题硬失败——① verify 是通用全局
  *   agent，文法遵循度不完美，「验证员不会写 JSON」不应摧毁整个 Loop；② Loop 无上限，
  *   畸形即 FAIL 会制造「verify 与文法搏斗」的新循环面；③ 产出质量最终可被下游消费者
  *   与人工复核（result 是产出原文，透明）。代价（verify 意图 FAIL 但忘写锚定 → 坏产出被
  *   放行）由 verifyTask 末尾单点注入的 VerifyVerdictFootnote 缓解。
  */
object VerdictReader:

  sealed trait Verdict
  object Verdict:
    /** 通过：verify 判定产出合格 → 走 completeNode（worker 最终产出原文）。 */
    case object Pass extends Verdict
    /** 打回：verify 判定不合格 → worker 按 issues + requirements 返工。 */
    final case class Fail(issues: List[String], requirements: String) extends Verdict

  /** FAIL 锚定但 JSON 缺失/畸形/issues 空时的占位意见（§2.3 第四行→第三行）。 */
  val PlaceholderIssues: String = "verify 未给出结构化意见（文法畸形）"

  private val VerdictRe = "(?i)^VERDICT:\\s*(PASS|FAIL)\\s*$".r

  /** 解析 verify 最终输出：pass/fail 判定（§2.3 四态表）。 */
  def parse(resultText: String): Verdict =
    val firstLine = resultText.trim.linesIterator.find(_.trim.nonEmpty).map(_.trim).getOrElse("")
    firstLine match
      case VerdictRe("PASS") => Verdict.Pass
      case VerdictRe("FAIL") =>
        jsonBody(resultText) match
          case Some(body) =>
            parser.parse(body).toOption.flatMap { json =>
              val cursor = json.hcursor
              val issues = cursor.get[List[String]]("issues").toOption.getOrElse(Nil)
              if issues.nonEmpty then
                val requirements = cursor.get[String]("requirements").toOption.getOrElse("")
                Some(Verdict.Fail(issues.map(_.trim).filter(_.nonEmpty), requirements))
              else None
            }.getOrElse(Verdict.Fail(List(PlaceholderIssues), ""))
          case None => Verdict.Fail(List(PlaceholderIssues), "")
      case _ => Verdict.Pass // 无锚定行 → 文法畸形降级 = Pass 直通

  /** 首个 `{` 到末个 `}` 之间（可嵌于说明文字之后；与 BlockedReader.jsonBody 同款）。 */
  private def jsonBody(trimmed: String): Option[String] =
    val i = trimmed.indexOf('{')
    val j = trimmed.lastIndexOf('}')
    if i >= 0 && j > i then Some(trimmed.substring(i, j + 1)) else None

  /** FAIL 渲染摘要（≤200 字符，loopLastVerdict 展示用——前端卡片最近打回原因）。 */
  def renderFailSummary(f: Verdict.Fail): String =
    val issues = f.issues.map(i => if i.length > 80 then i.take(77) + "…" else i)
    val summary = issues.mkString("; ")
    if summary.length > 200 then summary.take(197) + "…" else summary
