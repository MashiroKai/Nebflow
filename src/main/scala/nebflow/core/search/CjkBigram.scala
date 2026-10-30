package nebflow.core.search

/**
 * CJK bigram 变换（xerj CJKAnalyzer 同款；stand-recall 已趟平路线照抄——
 * 设计稿 §4.6 R2 与 §5.4）。FTS5 unicode61 对中文退化为整句 token，
 * 以「bigram 影子列 + 同变换查询」仿真：索引与查询两侧必须用同一变换
 * （口径单点）。本对象是两侧共同的唯一变换点。
 *
 * 语义（与 tools/stand-recall cjk_bigram_text 逐字对齐）：
 *   - CJK 连续串（长度 ≥ 2）→ 交叠 bigram（"检索引擎" → "检索 索引擎 引擎"）；
 *   - 单字 CJK 串 → 单字原样保留；
 *   - 非 CJK 字符 → 原样交还（unicode61 二次切分成词）。
 */
object CjkBigram:

  private val CjkRanges: Seq[(Int, Int)] = Seq(
    (0x4e00, 0x9fff),   // CJK 统一表意文字
    (0x3400, 0x4dbf),   // 扩展 A
    (0x20000, 0x2a6df), // 扩展 B
    (0xf900, 0xfaff),   // 兼容表意文字
  )

  def isCjk(c: Char): Boolean =
    val o = c.toInt
    CjkRanges.exists((lo, hi) => lo <= o && o <= hi)

  /**
   * 索引侧/查询侧同一变换：CJK 串→bigram 空格连接；其余字符原样交还。
   * 输出交给 FTS5 unicode61 做最终分词（非 CJK 部分）。
   */
  def transform(text: String): String =
    val out   = new StringBuilder()
    val run   = new StringBuilder()
    def flush(): Unit =
      if run.nonEmpty then
        if run.length == 1 then out.append(run.charAt(0))
        else
          var i = 0
          while i < run.length - 1 do
            if out.nonEmpty then out.append(' ')
            out.append(run.charAt(i)).append(run.charAt(i + 1))
            i += 1
        run.setLength(0)
    text.foreach { ch =>
      if isCjk(ch) then run.append(ch)
      else
        flush()
        out.append(ch)
    }
    flush()
    out.toString

  /**
   * 查询侧切分：返回 [(isCjkRun, payload)] 序列。
   * isCjkRun=true 的 payload 是 CJK 串（≥1 字），false 的是 ASCII 词（已 trim）。
   */
  def runs(query: String): List[(Boolean, String)] =
    val res = scala.collection.mutable.ListBuffer.empty[(Boolean, String)]
    val run = new StringBuilder()
    val buf = new StringBuilder()
    def flushAscii(): Unit =
      if buf.nonEmpty then res += ((false, buf.toString)); buf.setLength(0)
    def flushCjk(): Unit =
      if run.nonEmpty then res += ((true, run.toString)); run.setLength(0)
    query.foreach { ch =>
      if ch.isWhitespace then { flushAscii(); flushCjk() }
      else if isCjk(ch) then { flushAscii(); run.append(ch) }
      else { flushCjk(); buf.append(ch) }
    }
    flushAscii(); flushCjk()
    res.toList

  /**
   * 构造 FTS5 MATCH 表达式（渐进放宽：AND → OR，调用方两趟）。
    *   - CJK 串（≥2 字）→ 双引号 phrase（bigram 保序邻接，xerj positions 语义；
    *     实测 SQLite FTS5 对单引号 phrase 报 syntax error——字符串只用双引号）；
   *   - 单字 CJK → 索引无 unigram 谓词，可索引面丢弃（仅当整查询只剩单字时
   *     由调用方退化 LIKE）；
   *   - ASCII 词 → 整词单 term（unicode61 分词口径：索引侧 transform 原样交还
   *     非 CJK 串，unicode61 整词成 token ⇒ 查询侧整词单 term 即两侧同变换；
   *     词内非 alnum 字符 unicode61 两侧同弃——stand-recall 2026-10-27 盲区
   *     修复「两侧同变换」原则同构，但形态按 unicode61 词 token 对齐）。
   * 返回 (andExpr, orExpr, likePattern)：
   *   likePattern 非空 ⇔ 查询只含单字 CJK 且无其他可索引词。
   */
  def ftsQuery(query: String): (String, String, String) =
    val parts        = scala.collection.mutable.ListBuffer.empty[String]
    val singleChars  = scala.collection.mutable.ListBuffer.empty[String]
    runs(query).foreach { case (isCjk, payload) =>
      if isCjk then
        if payload.length == 1 then singleChars += payload
        else
          val toks = (0 until payload.length - 1).map(i => s"${payload.charAt(i)}${payload.charAt(i + 1)}")
          parts += ("\"" + toks.mkString(" ") + "\"")
      else
        val tok = payload.filter(c => c.isLetterOrDigit)
        if tok.nonEmpty then parts += tok
    }
    val andExpr = parts.mkString(" ")
    val orExpr  = if parts.length > 1 then parts.mkString(" OR ") else ""
    val likePattern =
      if singleChars.nonEmpty && parts.isEmpty then "%" + singleChars.mkString("%") + "%" else ""
    (andExpr, orExpr, likePattern)

end CjkBigram
