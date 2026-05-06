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

  /**
   * Two-step matching: exact match first, then quote-normalized fallback.
   * Returns the actual substring from `content` that matches `search`,
   * or None if no match is found.
   *
   * Note: the substring extraction on line below relies on normalizeQuotes
   * being a strict 1:1 character mapping (each curly quote maps to exactly
   * one straight quote), so `search.length` equals the matched span in the
   * normalized string. If a future normalization breaks this invariant
   * (e.g. ellipsis "…" → "..."), the extraction logic must be updated.
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
      else None

  /**
   * When old_string matched via quote normalization, apply the file's
   * curly-quote style onto new_string so the replacement stays consistent.
   */
  def preserveQuoteStyle(oldRaw: String, oldActual: String, newStr: String): String =
    if oldRaw == oldActual then newStr
    else applyCurlyQuotes(newStr, oldActual)

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
