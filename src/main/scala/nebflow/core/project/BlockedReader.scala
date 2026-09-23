package nebflow.core.project

/** blocked 声明解析器（设计 §1.3 文法）。独立 object 便于单测。
  *
  * - 锚定：trim 后以 BLOCKED 开头（后跟 `:` 或换行/空白/串尾）——只查开头，正文含词不误判。
  * - JSON 体：首个 `{` 到末个 `}` 之间尝试 circe 解析；category 不在枚举 → other。
  * - JSON 缺失/畸形 → BlockedFeedback("other", 其余全文截断, "")。
  * - 非 BLOCKED 开头 → None（调用方走 completed 原路径无损降级）。 */
object BlockedReader:
  val Categories: Set[String] =
    Set("upstream-incomplete", "task-underspecified", "agent-mismatch", "external-dependency", "needs-split", "other")

  private val Marker = "BLOCKED"
  private val DetailCap = 500
  // 观测面上下文经济学批（20260907 裁定②）：JSON body 分支的 detail/suggestion
  // 此前原样入库（实测最大 detail 1,511ch，审计锚点 9）——与 fallback 分支
  // DetailCap 同域单点常量收口；300/150 配平载荷侧「仅 blocked 态携带」后的预算
  // （NodePayload.buildNodeJson 单点序列化同步门控）。
  private val JsonDetailCap = 300
  private val JsonSuggestionCap = 150

  /** 解析节点最终输出：命中 blocked → Some(BlockedFeedback)；否则 None。 */
  def parse(resultText: String): Option[BlockedFeedback] =
    val trimmed = resultText.trim
    if !anchored(trimmed) then None
    else
      jsonBody(trimmed) match
        case Some(body) =>
          io.circe.parser.parse(body) match
            case Right(json) =>
              val cursor = json.hcursor
              val category = cursor.get[String]("category").toOption.filter(Categories.contains).getOrElse("other")
              val detail = capped(cursor.get[String]("detail").toOption.getOrElse(""), JsonDetailCap)
              val suggestion = capped(cursor.get[String]("suggestion").toOption.getOrElse(""), JsonSuggestionCap)
              Some(BlockedFeedback(category, detail, suggestion))
            case Left(_) => Some(fallback(trimmed))
        case None => Some(fallback(trimmed))

  /** 落库渲染串（设计 §1.3：result 字段人类可读形态，NodeList 摘要与详情窗共用）。 */
  def render(f: BlockedFeedback): String =
    s"[blocked:${f.category}] ${f.detail} — 建议: ${f.suggestion}"

  /** 锚定判定：BLOCKED 开头且后跟 `:` / 空白 / 串尾（"BLOCKEDxx" 不算）。 */
  private def anchored(trimmed: String): Boolean =
    trimmed.startsWith(Marker) && {
      val rest = trimmed.substring(Marker.length)
      rest.isEmpty || rest.head == ':' || rest.head.isWhitespace
    }

  /** 首个 `{` 到末个 `}` 之间（可嵌于说明文字之后）。 */
  private def jsonBody(trimmed: String): Option[String] =
    val i = trimmed.indexOf('{')
    val j = trimmed.lastIndexOf('}')
    if i >= 0 && j > i then Some(trimmed.substring(i, j + 1)) else None

  /** 截断 helper（尾部省略号标记；fallback 与 JSON 分支共用，封顶值单点常量）。 */
  private def capped(s: String, cap: Int): String =
    if s.length > cap then s.take(cap) + "…" else s

  /** JSON 缺失/畸形降级：去掉 BLOCKED 标记行后的其余全文截断为 detail。 */
  private def fallback(trimmed: String): BlockedFeedback =
    val rest = trimmed.replaceFirst("^BLOCKED\\s*:?\\s*", "").trim
    BlockedFeedback("other", capped(rest, DetailCap), "")
