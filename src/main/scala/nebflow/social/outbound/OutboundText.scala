package nebflow.social.outbound

import scala.annotation.tailrec

/**
 * Channel-agnostic TEXT conversion (design card section 1 text class + the
 * author's judge criteria for the text leg, ruling #299).
 *
 * The criterion the author fixed is a READING, not an opinion: the outbound text
 * carries **no bare markdown syntax characters** and its **links stay alive**
 * (the URL survives as a clickable bare URL, the label survives as words).
 *
 * Discipline: this conversion lives on the CHANNEL ADAPTER side. The in-session
 * original text is never touched -- the transcript the local panel shows is the
 * model's own writing; only the outbound copy is re-shaped.
 *
 * It is a pure function, so it is offline-judgeable with no network and no
 * credentials, which is what makes the red check mechanical.
 */
object OutboundText:

  /** Every character class this conversion is responsible for. Exposed so the
    * acceptance reading can name what it looks for; the actual judgement lives
    * in [[bareMarkers]], which distinguishes delimiters from prose.
    *
    * The `_` family is listed here for the same reason as the rest: it is a
    * markdown emphasis delimiter (`_em_`, `__st__`), and leaving it out of this
    * list is exactly how the underscore leg stayed invisible to the reading
    * while the asterisk leg was covered. */
  val markers: List[String] =
    List("`", "*", "~~", "# ", "> ", "```", "_", "__")

  /**
   * Convert one markdown-ish line/block into Feishu-visible plain text.
   *
   * Order matters and each step is short-circuit-safe:
   *   0. `[label](url)` and reference links become `label (url)` -- the URL is
   *      kept verbatim so it stays alive, the label is kept so the meaning does
   *      not evaporate;
   *   1. fenced-code fences are removed (the code itself is content);
   *   2. heading / blockquote / list / rule prefixes are dropped;
   *   3. emphasis and inline-code delimiters are unwrapped, `~~` before `~`;
   *   4. image syntax is unwrapped to its alt text when it survived step 0.
   */
  def toPlain(md: String): String =
    val withLinks = unwrapLinks(md)
    val lines = withLinks.split("\n", -1).toList
    // Fenced blocks are handled LINE-WISE, before any inline reshaping: a fence
    // line is dropped, and the code between fences is passed through untouched
    // (the code is content; only the ``` delimiters are markdown syntax).
    val (out, _) = lines.foldLeft((List.empty[String], false)) {
      case ((acc, inFence), line) =>
        val trimmed = line.trim
        if trimmed.startsWith("```") then (acc, !inFence)
        else if inFence then (acc :+ line, inFence)
        else (acc :+ plainLine(line), inFence)
    }
    out.mkString("\n")

  /** `[label](url)` -> `label (url)`; `![alt](url)` -> `alt (url)`. The scan is
    * hand-rolled rather than regex-driven so nested parens in a URL cannot make
    * the whole block vanish (a dropped link is a dropped link). */
  private def unwrapLinks(s: String): String =
    val sb = new StringBuilder
    var i = 0
    while i < s.length do
      if s.charAt(i) == '[' then
        val closeBracket = s.indexOf(']', i + 1)
        if closeBracket > i && closeBracket + 1 < s.length && s.charAt(closeBracket + 1) == '(' then
          val closeParen = matchingParen(s, closeBracket + 1)
          if closeParen > closeBracket then
            val label = s.substring(i + 1, closeBracket)
            val url = s.substring(closeBracket + 2, closeParen)
            val bang = i > 0 && s.charAt(i - 1) == '!'
            if bang then sb.setLength(sb.length - 1) // drop the image `!`: it is not text
            sb.append(label)
            if url.trim.nonEmpty then sb.append(" (").append(url.trim).append(")")
            i = closeParen + 1
          else
            sb.append(s.charAt(i)); i += 1
        else
          sb.append(s.charAt(i)); i += 1
      else
        sb.append(s.charAt(i)); i += 1
    sb.toString

  /** Index of the `)` matching the `(` at `open`, or -1. */
  private def matchingParen(s: String, open: Int): Int =
    var depth = 0
    var j = open
    var found = -1
    while j < s.length && found < 0 do
      val c = s.charAt(j)
      if c == '(' then depth += 1
      else if c == ')' then
        depth -= 1
        if depth == 0 then found = j
      j += 1
    found

  /**
   * Inline reshaping of ONE line. Code spans (between backticks) are carried
   * through verbatim; everything else goes through [[stripSyntax]].
   *
   * Backticks are only treated as delimiters when they come in a PAIR: a lone
   * backtick is punctuation, not a code span, and swallowing it would lose the
   * rest of the line.
   */
  private def plainLine(line: String): String =
    val sb = new StringBuilder
    var rest = line
    var inCode = false
    var carryOn = true
    while carryOn do
      val tick = rest.indexOf('`')
      if tick < 0 then
        sb.append(if inCode then rest else stripSyntax(rest))
        carryOn = false
      else
        sb.append(if inCode then rest.substring(0, tick) else stripSyntax(rest.substring(0, tick)))
        val tail = rest.substring(tick + 1)
        if inCode then
          // This backtick CLOSES the span: drop the delimiter (the code between
          // the fences is content and has already been carried through verbatim).
          if tail.contains('`') then
            rest = tail
            inCode = false
          else
            // The span never closed: the text after it is prose again, not code.
            sb.append(stripSyntax(tail))
            carryOn = false
        else if tail.contains('`') then
          // This backtick OPENS a span: drop the delimiter and carry the code.
          rest = tail
          inCode = true
        else
          // A genuinely unmatched backtick is punctuation, not a delimiter:
          // keep it and finish the line rather than swallowing what follows.
          sb.append(stripSyntax("`"))
          sb.append(stripSyntax(tail))
          carryOn = false
    sb.toString

  /** The per-span reshaping for content OUTSIDE inline code. */
  private def stripSyntax(span: String): String =
    var s = span
    // blockquote marker (leading, possibly repeated)
    s = s.replaceAll("^\\s*>+\\s?", "")
    // heading markers
    s = s.replaceAll("^\\s{0,3}#{1,6}\\s+", "")
    // horizontal rule -> a single visible separator
    if s.trim.matches("(-{3,}|\\*{3,}|_{3,})") then "—"
    else
      // unordered list marker
      s = s.replaceAll("^\\s*[-*+]\\s+", "· ")
      // ordered list marker: keep the number (it is meaning), drop the dot-noise
      s = s.replaceAll("^\\s*(\\d+)\\.\\s+", "$1. ")
      // emphasis / strikethrough / leftover inline code delimiters
      s = s.replace("**", "")
      s = unwrapUnderscores(s)
      s = s.replace("*", "").replace("~~", "").replace("~", "")
      s

  /** Unwrap the underscore emphasis family (`_em_`, `__st__`), PAIR-wise.
    *
    * `_` is the only markdown delimiter that also occurs INSIDE ordinary words,
    * so a bare `.replace("_", "")` would destroy `snake_case` and `__name__`-ish
    * identifiers. The rule here is the same discipline [[plainLine]] already
    * applies to backticks -- a delimiter is a delimiter only when it comes in a
    * PAIR, a lone one is punctuation and stays -- with one extra condition for
    * this family: a run of underscores may only open (and a closing run may only
    * close) when it sits OUTSIDE a word. `_em_` is therefore unwrapped, while
    * `a_b_c`, `x__y` and `https://e.example/a_b_c` are left exactly as written.
    *
    * Known limit, stated rather than hidden: `__init__` and `__strong__` have
    * byte-identical local structure (word-char content between two double runs,
    * non-word neighbours outside), so no local rule can unwrap one and keep the
    * other. This conversion keeps the emphasis reading -- a bare `__` is markdown
    * syntax on the wire and is the case the acceptance criterion is about. */
  private def unwrapUnderscores(s: String): String =
    val sb = new StringBuilder
    var i = 0
    while i < s.length do
      if s.charAt(i) == '_' then
        val n = underscoreRun(s, i)
        val close = if opensDelimiter(s, i, n) then closingRun(s, i + n, n) else -1
        if close >= 0 then
          sb.append(s.substring(i + n, close)) // the delimiters go, the content stays
          i = close + n
        else
          sb.append(s.substring(i, i + n))
          i += n
      else
        sb.append(s.charAt(i))
        i += 1
    sb.toString

  private def isWordChar(c: Char): Boolean =
    Character.isLetterOrDigit(c) || c == '_'

  /** Length of the run of underscores starting at `i` (>= 1). */
  private def underscoreRun(s: String, i: Int): Int =
    var j = i
    while j < s.length && s.charAt(j) == '_' do j += 1
    j - i

  /** May the run at `i` (of length `n`) OPEN a delimiter pair? Only outside a
    * word, and only when content follows it. */
  private def opensDelimiter(s: String, i: Int, n: Int): Boolean =
    (i == 0 || !isWordChar(s.charAt(i - 1))) &&
      i + n < s.length && !s.charAt(i + n).isWhitespace

  /** Index of the run that CLOSES the pair opened before `from`, or -1 when
    * there is none. Pair means: at least as long, outside a word on its right,
    * and with non-empty content in between. */
  private def closingRun(s: String, from: Int, n: Int): Int =
    var j = from
    var found = -1
    while j < s.length && found < 0 do
      if s.charAt(j) == '_' then
        val m = underscoreRun(s, j)
        if j > from && m >= n && (j + m >= s.length || !isWordChar(s.charAt(j + m))) then found = j
        else j += m
      else j += 1
    found

  /**
   * Does this text still carry a bare markdown syntax marker? This is the
   * machine predicate behind the text-leg acceptance criterion.
   */
  def hasBareMarkers(s: String): Boolean = bareMarkers(s).nonEmpty

  /**
   * Every bare marker found -- for the failure reading, so a red check names
   * exactly what it tripped on instead of just saying "red".
   *
   * Three families, because a wide sweep would flag ordinary prose:
   *   - `anywhere`: a character that is only ever markdown in this context;
   *   - `atLineStart`: a genuine markdown prefix (a `>` comparison in prose is
   *     not a blockquote);
   *   - the underscore family, which is the one exception to `anywhere`: `_` is
   *     also ordinary punctuation inside words, so it is read PAIR-wise by the
   *     same rule the conversion applies.
   */
  def bareMarkers(s: String): List[String] =
    val anywhere = List("`", "*", "~~", "```").filter(s.contains)
    val atLineStart = List("> ", "# ").filter(p => s.linesIterator.exists(_.startsWith(p)))
    (anywhere ++ atLineStart ++ bareUnderscores(s)).distinct

  /** Paired underscore delimiters still on the wire, if any. Deliberately NOT a
    * bare `s.contains("_")`: that would flag `snake_case`, `a_b_c` in a URL and
    * every ordinary identifier -- a false-positive alarm, not a reading. The
    * scan reuses [[opensDelimiter]] / [[closingRun]], the same pair-plus-
    * word-boundary rule the conversion applies, so the judgement and the
    * conversion cannot drift apart on this family. */
  private def bareUnderscores(s: String): List[String] =
    val found = scala.collection.mutable.ListBuffer.empty[String]
    var i = 0
    while i < s.length do
      if s.charAt(i) == '_' then
        val n = underscoreRun(s, i)
        val close = if opensDelimiter(s, i, n) then closingRun(s, i + n, n) else -1
        if close >= 0 then
          found += (if n >= 2 then "__" else "_")
          i = close + n
        else i += n
      else i += 1
    found.toList.distinct

  /** URLs surviving in the text: `label (url)` outputs plus bare URLs. */
  def links(s: String): List[String] =
    val paren = """\(([a-zA-Z][a-zA-Z0-9+.\-]*://[^\s()]+)\)""".r
      .findAllMatchIn(s).map(_.group(1)).toList
    val bare = """[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s()]+""".r
      .findAllMatchIn(s).map(_.matched).toList
    (paren ++ bare).distinct

  // ── placeholders (design card section 7.1 / 7.2) ──────────────────────────
  // 🔴 User-visible copy: every string below is listed as a section-16
  //    review item in this node's result. Wiring them into a production
  //    outbound path requires the author's word-by-word sign-off first.

  /** A path that could not be rendered. */
  def unrendered(reason: String, path: os.Path, bytes: Long): String =
    s"[未渲染] $reason — 原件：${path.toString}（$bytes 字节）"

  /** A path whose class could not be decided. */
  def unknownType(reason: String, path: os.Path): String =
    s"[未知类型] $reason — 原件：${path.toString}"

  /** The size gate's explicit refusal, carrying the actual value and the limit. */
  def sizeRejected(bytes: Long, limit: Long): String =
    s"[尺寸闸拒绝] ${bytes} 字节超过上限 ${limit} 字节 — 原件未上传"
