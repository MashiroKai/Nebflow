package nebscala.shared

/** CJK 宽字符检测（供 TUI 和 Markdown 渲染复用） */
object CjkWidth:
  def isWideChar(code: Int): Boolean =
    (code >= 0x4e00 && code <= 0x9fff) ||   // CJK Unified Ideographs
    (code >= 0x3400 && code <= 0x4dbf) ||   // CJK Extension A
    (code >= 0x3000 && code <= 0x303f) ||   // CJK Symbols
    (code >= 0xff00 && code <= 0xffef) ||   // Fullwidth forms
    (code >= 0xac00 && code <= 0xd7af) ||   // Hangul
    (code >= 0x3040 && code <= 0x309f) ||   // Hiragana
    (code >= 0x30a0 && code <= 0x30ff)      // Katakana

  /** 计算字符串在终端中的显示宽度 */
  def displayWidth(s: String): Int =
    s.codePoints().toArray.toList.map { cp =>
      if isWideChar(cp) then 2 else 1
    }.sum
