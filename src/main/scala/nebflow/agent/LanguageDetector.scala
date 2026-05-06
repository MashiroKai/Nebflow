package nebflow.agent

/**
 * Simple Unicode-range based language detector.
 * Returns a human-readable language name suitable for system prompt injection
 * (e.g. "Chinese", "Japanese", "Korean", "Russian", "Arabic", "English").
 *
 * Detection is intentionally conservative: only returns a language when the
 * text contains enough characters from a non-Latin Unicode block. Falls back
 * to None (meaning English / default).
 */
object LanguageDetector:

  private val MinChars = 3

  def detect(text: String): Option[String] =
    val trimmed = text.trim
    if trimmed.isEmpty then None
    else detectNonEmpty(trimmed)

  private def detectNonEmpty(trimmed: String): Option[String] =
    // Count characters by Unicode category
    var cjkUnified = 0   // CJK Unified Ideographs (Chinese/Japanese kanji)
    var hiragana = 0
    var katakana = 0
    var hangul = 0
    var arabic = 0
    var cyrillic = 0
    var thai = 0
    var devanagari = 0

    var i = 0
    val len = trimmed.length
    while i < len do
      val cp = trimmed.codePointAt(i)
      if cp <= 0xFFFF then
        val ch = cp.toChar
        // CJK Unified Ideographs
        if ch >= '\u4E00' && ch <= '\u9FFF' then cjkUnified += 1
        // CJK Extension A
        else if ch >= '\u3400' && ch <= '\u4DBF' then cjkUnified += 1
        // Hiragana
        else if ch >= '\u3040' && ch <= '\u309F' then hiragana += 1
        // Katakana
        else if ch >= '\u30A0' && ch <= '\u30FF' then katakana += 1
        // Hangul Syllables
        else if ch >= '\uAC00' && ch <= '\uD7AF' then hangul += 1
        // Arabic
        else if ch >= '\u0600' && ch <= '\u06FF' then arabic += 1
        else if ch >= '\u0750' && ch <= '\u077F' then arabic += 1
        // Cyrillic
        else if ch >= '\u0400' && ch <= '\u04FF' then cyrillic += 1
        // Thai
        else if ch >= '\u0E00' && ch <= '\u0E7F' then thai += 1
        // Devanagari
        else if ch >= '\u0900' && ch <= '\u097F' then devanagari += 1
      i += 1
    end while

    // Japanese: CJK + Hiragana/Katakana. If hiragana or katakana present, it's Japanese.
    if (hiragana + katakana) >= MinChars then
      Some("Japanese")
    else if hangul >= MinChars then
      Some("Korean")
    else if cjkUnified >= MinChars then
      // Pure CJK with no kana — treat as Chinese
      Some("Chinese")
    else if arabic >= MinChars then
      Some("Arabic")
    else if cyrillic >= MinChars then
      Some("Russian")
    else if thai >= MinChars then
      Some("Thai")
    else if devanagari >= MinChars then
      Some("Hindi")
    else
      None
  end detectNonEmpty

end LanguageDetector
