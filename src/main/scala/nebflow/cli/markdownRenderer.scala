package nebflow.cli

import nebflow.shared.TerminalUtils.*

object MarkdownRenderer:
  def render(md: String): String =
    val lines = md.split("\\r?\\n").toList
    val result = scala.collection.mutable.ListBuffer.empty[String]
    var inCode = false
    var codeLang = ""
    var codeBuffer = scala.collection.mutable.ListBuffer.empty[String]

    lines.foreach { line =>
      if line.startsWith("```") then
        if inCode then
          // End code block
          result += s"$Dim┌${"─" * 40}┐$Reset"
          if codeLang.nonEmpty then result += s"$Dim│ $codeLang${" " * (39 - codeLang.length)}│$Reset"
          codeBuffer.foreach { l =>
            result += s"$Dim│$Reset $Yellow${escapeAnsi(l)}${" " * Math.max(0, 40 - 3 - visibleWidth(l))}$Dim│$Reset"
          }
          result += s"$Dim└${"─" * 40}┘$Reset"
          codeBuffer.clear()
          codeLang = ""
          inCode = false
        else
          // Start code block
          codeLang = line.drop(3).trim
          inCode = true
      else if inCode then
        codeBuffer += line
      else
        val rendered = renderLine(line)
        if rendered.nonEmpty then result += rendered
    }

    // Handle unclosed code block
    if inCode then
      result += s"$Dim┌${"─" * 40}┐$Reset"
      if codeLang.nonEmpty then result += s"$Dim│ $codeLang${" " * (39 - codeLang.length)}│$Reset"
      codeBuffer.foreach { l =>
        result += s"$Dim│$Reset $Yellow${escapeAnsi(l)}${" " * Math.max(0, 40 - 3 - visibleWidth(l))}$Dim│$Reset"
      }
      result += s"$Dim└${"─" * 40}┘$Reset"

    result.mkString("\n")

  private def renderLine(line: String): String =
    if line.startsWith("# ") then
      s"$Bold$White${renderInline(line.drop(2))}$Reset"
    else if line.startsWith("## ") then
      s"$Bold${renderInline(line.drop(3))}$Reset"
    else if line.startsWith("### ") then
      s"$Bold$Dim${renderInline(line.drop(4))}$Reset"
    else if line.startsWith("> ") then
      s"$Dim│$Reset ${renderInline(line.drop(2))}"
    else if line.startsWith("- ") || line.startsWith("* ") then
      s"$Cyan•$Reset ${renderInline(line.drop(2))}"
    else if "^\\d+\\.\\s".r.findPrefixOf(line).isDefined then
      val m = "^(\\d+)\\.\\s".r.findPrefixMatchOf(line).get
      s"$Cyan${m.group(1)}.$Reset ${renderInline(line.drop(m.end))}"
    else if line.startsWith("---") || line.startsWith("***") then
      s"$Dim${"─" * 40}$Reset"
    else if line.trim.nonEmpty then
      renderInline(line)
    else
      ""

  private def renderInline(text: String): String =
    var result = text
    // Bold **text**
    result = "\\*\\*(.+?)\\*\\*".r.replaceAllIn(result, m => s"$Bold${m.group(1)}$Reset")
    // Italic *text* (but not **)
    result = "(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)".r.replaceAllIn(result, m => s"$Italic${m.group(1)}$Reset")
    // Inline code `text`
    result = "`([^`]+)`".r.replaceAllIn(result, m => s"$Yellow${m.group(1)}$Reset")
    // Links [text](url)
    result = "\\[([^\\]]+)\\]\\(([^\\)]+)\\)".r.replaceAllIn(result, m => s"$Cyan${m.group(1)}$Reset $Dim(${m.group(2)})$Reset")
    result

  private def escapeAnsi(s: String): String =
    s.replace("\u001b", "^[")

  private def visibleWidth(s: String): Int =
    // Strip ANSI codes and count visible chars
    val stripped = s.replaceAll("\\u001b\\[[0-9;]*m", "")
    nebflow.shared.CjkWidth.displayWidth(stripped)
