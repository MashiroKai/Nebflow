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
    * in [[bareMarkers]], which distinguishes delimiters from prose. */
  val markers: List[String] =
    List("`", "*", "~~", "# ", "> ", "```")

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
      s = s.replace("**", "").replace("__", "")
      s = s.replace("*", "").replace("~~", "").replace("~", "")
      s

  /**
   * Does this text still carry a bare markdown syntax marker? This is the
   * machine predicate behind the text-leg acceptance criterion.
   */
  def hasBareMarkers(s: String): Boolean = bareMarkers(s).nonEmpty

  /**
   * Every bare marker found -- for the failure reading, so a red check names
   * exactly what it tripped on instead of just saying "red".
   *
   * Two families, because a wide sweep would flag ordinary prose:
   *   - `anywhere`: a character that is only ever markdown in this context;
   *   - `atLineStart`: a genuine markdown prefix (a `>` comparison in prose is
   *     not a blockquote).
   */
  def bareMarkers(s: String): List[String] =
    val anywhere = List("`", "*", "~~", "```").filter(s.contains)
    val atLineStart = List("> ", "# ").filter(p => s.linesIterator.exists(_.startsWith(p)))
    (anywhere ++ atLineStart).distinct

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
