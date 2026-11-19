package nebflow.core.wiki

/* ═══════════════════════════════════════════════════════════════════════════
 * md/html 类型硬约束闸 · 「作用面收缩」的唯一判据单点（词条系统第三批）
 *
 * 作者口径（2026-10-29 11:46 自裁，调研稿
 * docs/design/20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md
 * §5.6/§9-1/§9-7，设计稿 docs/design/20261028_canvas-ask-mode-design.zh-CN.md
 * §2「类型硬约束闸」行 + §8 词条同闸回写）：
 *   「作用面收缩」= **仅 md/html 文件**启用词条引用与 ask mode；
 *   白名单四扩展名 = `.md` / `.markdown` / `.html` / `.htm`。
 *
 * 本对象是后端唯一的判据面（禁写第二份）：ask 侧（`AskSessions.isAskable`
 * 一行委托本节）与词条侧（`WikiService.term` / `WikiRoutes`）全部经
 * `isMdHtml`。前端跨语言同闸单点 = `web/js/mdHtmlGate.js`（枚举逐字对齐）。
 *
 * 存量口径（§9-8）：本闸**只拦请求**，不清理既有数据——已存在的非 md/html
 * ask 绑定原样保留在 `<dataRoot>/ask/index.json`，仅在请求路径上被拒（自然
 * 衰减）。
 *
 * 路径归一（本批显式要求）：入参可能携带 `?query` 与 `#fragment`（词条深链
 * `/api/wiki/term?doc=…%23anchor`、前端 `absPath` 带定位锚等），判定前先剥掉
 * query/fragment，再按「最后一个路径分隔符之后的最后一个点」取扩展名——目录
 * 名里的点（`/a.b/note`）不会被误当扩展名。大小写不敏感（`README.MD` 放行）。
 * ═══════════════════════════════════════════════════════════════════════════ */

object MdHtmlGate:

  /**
   * 白名单封闭枚举（四扩展名，小写存储）。禁写第二份——ask 侧与词条侧一律
   * 经 [[isMdHtml]]，前端 `web/js/mdHtmlGate.js` 的 `MD_HTML_EXTENSIONS` 与
   * 本集逐字对齐（spec 双侧钉死）。
   */
  val Extensions: Set[String] = Set(".md", ".markdown", ".html", ".htm")

  /** 拒绝原因的标准前缀（三处接入点共用同一措辞；日志与 HTTP 响应同源）。 */
  val RejectPrefix: String = "not askable: not md/html"

  /**
   * 剥掉 `?query` 与 `#fragment`（两者取更靠前者截断），并 trim。
   * 纯字符串操作，不触盘。
   */
  def stripQueryAndFragment(path: String): String =
    if path == null then ""
    else
      val p = path.trim
      val q = p.indexOf('?')
      val h = p.indexOf('#')
      val cut =
        if q < 0 && h < 0 then p.length
        else if q < 0 then h
        else if h < 0 then q
        else math.min(q, h)
      p.substring(0, cut)

  /**
   * 扩展名（小写，含前导点）：取归一化路径中最后一个路径分隔符之后的最后
   * 一个点。无扩展名 / 点只出现在目录名里 → 空串。
   */
  def extOf(path: String): String =
    val p = stripQueryAndFragment(path).toLowerCase
    if p.isEmpty then ""
    else
      val slash = math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'))
      val dot = p.lastIndexOf('.')
      if dot <= slash then "" else p.substring(dot)

  /**
   * 判据单点：路径扩展名 ∈ 四扩展名白名单（大小写不敏感，`?query`/`#frag`
   * 先剥离）。null / 空 / 无扩展名 / 白名单外一律 false。
   */
  def isMdHtml(path: String): Boolean =
    Extensions.contains(extOf(path))

  /**
   * 统一拒绝原因（三处接入点同措辞 + 明确扩展名，便于日志定位且不刷屏——
   * 调用方每条拒绝只落一行）。
   */
  def rejectReason(path: String): String =
    val ext = extOf(path) match
      case ""    => "<none>"
      case other => other
    s"$RejectPrefix (ext=$ext, allowed=${Extensions.toList.sorted.mkString(",")})"

end MdHtmlGate
