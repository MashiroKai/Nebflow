package nebflow.core.tools

object StringMatcher:

  private val CurlyQuotes = Map(
    '\u2018' -> '\'', // '
    '\u2019' -> '\'', // '
    '\u201C' -> '"', // "
    '\u201D' -> '"' // "
  )

  /** Normalize curly quotes to straight quotes for comparison. */
  def normalizeQuotes(s: String): String =
    s.map(c => CurlyQuotes.getOrElse(c, c))

  // ---------------------------------------------------------------------------
  // Whitespace-insensitive matching
  // ---------------------------------------------------------------------------

  /**
   * Normalized form where each run of consecutive whitespace (spaces, tabs)
   * is collapsed into a single space. Non-whitespace characters pass through
   * unchanged.
   *
   * @param text     normalized string (one space per whitespace run)
   * @param origStarts  origStarts(i) = position in original string where the
   *                    span for normalized character i begins
   * @param origEnds    origEnds(i) = position in original string right after
   *                    the span for normalized character i
   */
  private case class WhitespaceNormalized(
    text: String,
    origStarts: Array[Int],
    origEnds: Array[Int]
  )

  /** Collapse whitespace runs and track original positions. */
  private def normalizeWhitespace(s: String): WhitespaceNormalized =
    val sb = new StringBuilder(s.length)
    val starts = Array.newBuilder[Int]
    val ends = Array.newBuilder[Int]

    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == ' ' || c == '\t' then
        // Collapse entire whitespace run into a single space
        val runStart = i
        i += 1
        while i < s.length && (s.charAt(i) == ' ' || s.charAt(i) == '\t') do i += 1
        sb.append(' ')
        starts += runStart
        ends += i // i is past the end of the whitespace run
      else
        sb.append(c)
        starts += i
        ends += i + 1
        i += 1
    end while

    WhitespaceNormalized(sb.toString, starts.result(), ends.result())

  end normalizeWhitespace

  /**
   * Given a whitespace-normalized match position, extract the corresponding
   * span from the original string.  The normalized form collapses runs of
   * whitespace but always produces a result, so we walk the position map to
   * find the true original boundaries.
   */
  private def extractOriginalSpan(
    content: String,
    wn: WhitespaceNormalized,
    normIdx: Int,
    normLen: Int
  ): String =
    val normEnd = normIdx + normLen - 1
    val origStart = wn.origStarts(normIdx)
    val origEnd = wn.origEnds(normEnd)
    content.substring(origStart, origEnd)

  /**
   * Three-step matching: exact match first, then quote-normalized fallback,
   * finally whitespace-insensitive fallback (which also handles quote
   * normalization internally).
   *
   * Returns the actual substring from `content` that matches `search`,
   * or None if no match is found.
   */
  def findActualString(content: String, search: String): Option[String] =
    // Step 1: exact match
    val exactIdx = content.indexOf(search)
    if exactIdx >= 0 then Some(search)
    else
      // Step 2: quote-normalized match
      val normContent = normalizeQuotes(content)
      val normSearch = normalizeQuotes(search)
      val idx = normContent.indexOf(normSearch)
      if idx >= 0 then Some(content.substring(idx, idx + search.length))
      else
        // Steps 3 & 4: whitespace-insensitive matching.
        // Compute wnContent once — its position map is identical whether
        // built from content or normContent (quote normalization is 1:1).
        val wnContent = normalizeWhitespace(content)

        // Step 3: whitespace-insensitive (no quote handling)
        val wnSearch = normalizeWhitespace(search)
        val wsIdx = wnContent.text.indexOf(wnSearch.text)
        if wsIdx >= 0 then Some(extractOriginalSpan(content, wnContent, wsIdx, wnSearch.text.length))
        else
          // Step 4: quote-normalized + whitespace-insensitive.
          // Search in wnContentQ (built from normContent) because the
          // normalized text may differ from wnContent.text (curly vs straight).
          // Extraction still uses wnContent (same position map).
          val wnContentQ = normalizeWhitespace(normContent)
          val wnSearchQ = normalizeWhitespace(normSearch)
          val wsQIdx = wnContentQ.text.indexOf(wnSearchQ.text)
          if wsQIdx >= 0 then Some(extractOriginalSpan(content, wnContent, wsQIdx, wnSearchQ.text.length))
          else None

      end if

    end if

  end findActualString

  /**
   * When old_string matched via quote normalization, apply the file's
   * curly-quote style onto new_string so the replacement stays consistent.
   */
  def preserveQuoteStyle(oldRaw: String, oldActual: String, newStr: String): String =
    if oldRaw == oldActual then newStr
    else applyCurlyQuotes(newStr, oldActual)

  // ---------------------------------------------------------------------------
  // Closest-candidate line hint (match-failure self-healing)
  // ---------------------------------------------------------------------------

  /** Hint about the content line most similar to a failed search string. */
  final case class LineHint(line: Int, similarityPct: Int, excerpt: String)

  /** Minimum similarity for reporting a hint — below this, silence beats noise. */
  private val MinHintSimilarity = 60

  /**
   * Heuristic: locate the content line most similar to the first non-blank
   * line of `search` (the anchor). Compares quote- and whitespace-normalized
   * forms with Levenshtein similarity; a content line containing the whole
   * anchor gets a floor of 85 (the anchor survives line-wrapping/extra text).
   *
   * Returns None when no line reaches [[MinHintSimilarity]] — a missing hint
   * is better than a misleading one.
   */
  def closestLineHint(content: String, search: String): Option[LineHint] =
    val anchorOpt = DiffUtil.splitLines(search).find(_.trim.nonEmpty)
    anchorOpt.flatMap { anchor =>
      val normAnchor = normalizeWhitespace(normalizeQuotes(anchor)).text
      if normAnchor.isBlank then None
      else
        var bestLine = 0
        var bestSim = -1
        var bestExcerpt = ""
        val lines = DiffUtil.splitLines(content)
        var i = 0
        while i < lines.length do
          val normLine = normalizeWhitespace(normalizeQuotes(lines(i))).text
          if normLine.nonEmpty then
            val lev = similarityPct(normLine, normAnchor)
            val sim =
              if normLine.contains(normAnchor) then math.max(lev, 85)
              else lev
            if sim > bestSim then
              bestSim = sim
              bestLine = i + 1 // 1-based
              bestExcerpt = lines(i).trim
          i += 1
        end while
        if bestSim >= MinHintSimilarity then Some(LineHint(bestLine, bestSim, bestExcerpt))
        else None
    }

  /** Levenshtein-based similarity in percent (100 = identical). */
  private def similarityPct(a: String, b: String): Int =
    if a.isEmpty && b.isEmpty then 100
    else if a.isEmpty || b.isEmpty then 0
    else
      val dist = levenshtein(a, b)
      (100.0 * (1.0 - dist.toDouble / math.max(a.length, b.length)).max(0.0)).round.toInt

  /** Compact two-row Levenshtein DP. Only called on match failure. */
  private def levenshtein(a: String, b: String): Int =
    var prev = Array.tabulate(b.length + 1)(identity)
    var cur = new Array[Int](b.length + 1)
    var i = 1
    while i <= a.length do
      cur(0) = i
      var j = 1
      while j <= b.length do
        val cost = if a.charAt(i - 1) == b.charAt(j - 1) then 0 else 1
        cur(j) = math.min(math.min(cur(j - 1) + 1, prev(j) + 1), prev(j - 1) + cost)
        j += 1
      val tmp = prev
      prev = cur
      cur = tmp
      i += 1
    end while
    prev(b.length)
  end levenshtein

  /**
   * Detect curly-quote pattern in reference text and apply the same
   * pattern to the target. Uses a simple open/close toggle heuristic.
   *
   * Limitation: when `ref` has unbalanced curly quotes (odd count), the
   * initial open/close state is guessed from open vs close counts. This
   * works well for typical short old_string snippets but may produce
   * incorrect pairing in pathological cases (e.g. ref starts mid-quote
   * with 3 opens and 1 close).
   */
  private def applyCurlyQuotes(target: String, ref: String): String =
    val hasCurlySingle = ref.contains('\u2018') || ref.contains('\u2019')
    val hasCurlyDouble = ref.contains('\u201C') || ref.contains('\u201D')

    if !hasCurlySingle && !hasCurlyDouble then target
    else
      val sb = new StringBuilder(target.length)
      var singleOpen = false
      var doubleOpen = false

      // Detect initial state from ref: count opens vs closes
      val refSingleOpens = ref.count(_ == '\u2018')
      val refSingleCloses = ref.count(_ == '\u2019')
      val refDoubleOpens = ref.count(_ == '\u201C')
      val refDoubleCloses = ref.count(_ == '\u201D')

      // If closes > opens, the ref likely starts mid-quote
      if hasCurlySingle && refSingleCloses > refSingleOpens then singleOpen = true
      if hasCurlyDouble && refDoubleCloses > refDoubleOpens then doubleOpen = true

      for c <- target do
        if c == '\'' && hasCurlySingle then
          if singleOpen then sb.append('\u2019') else sb.append('\u2018')
          singleOpen = !singleOpen
        else if c == '"' && hasCurlyDouble then
          if doubleOpen then sb.append('\u201D') else sb.append('\u201C')
          doubleOpen = !doubleOpen
        else sb.append(c)

      sb.toString
    end if
  end applyCurlyQuotes

end StringMatcher
