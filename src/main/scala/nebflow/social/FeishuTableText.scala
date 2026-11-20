package nebflow.social

/** Conservative Feishu-only GFM table degradation; ambiguous tables remain unchanged. */
private[social] object FeishuTableText:

  def render(text: String): String =
    val lines = text.split("\n", -1).toVector
    val out = Vector.newBuilder[String]
    var i = 0
    var fence: Option[(Char, Int)] = None
    while i < lines.size do
      val line = lines(i)
      val trimmed = line.trim
      val marker = trimmed.headOption.filter(c => c == '`' || c == '~')
      val count = marker.fold(0)(c => trimmed.takeWhile(_ == c).length)
      val opens = fence.isEmpty && count >= 3
      val closes = fence.exists { case (c, n) => marker.contains(c) && count >= n && trimmed.drop(count).trim.isEmpty }
      if opens || closes then
        fence = if closes then None else marker.map(_ -> count)
        out += line
        i += 1
      else if fence.isEmpty && i + 1 < lines.size then
        val header = cells(line)
        val separator = cells(lines(i + 1))
        if header.nonEmpty && separator.size == header.size &&
          separator.forall(_.matches(":?-{3,}:?"))
        then
          var end = i + 2
          while end < lines.size && lines(end).contains("|") && lines(end).trim.nonEmpty do end += 1
          val rows = lines.slice(i + 2, end).map(cells)
          if rows.forall(_.size == header.size) then
            if rows.isEmpty then out += header.mkString(" | ")
            else
              rows.zipWithIndex.foreach { case (row, index) =>
                if index > 0 then out += ""
                header.zip(row).zipWithIndex.foreach { case ((key, value), column) =>
                  val label = if key.nonEmpty then key else s"列 ${column + 1}"
                  out += s"$label: $value"
                }
              }
          else lines.slice(i, end).foreach(out += _)
          i = end
        else
          out += line
          i += 1
        end if
      else
        out += line
        i += 1
      end if
    end while
    out.result().mkString("\n")

  end render

  private def cells(line: String): Vector[String] =
    val s = line.trim
    // Escapes and inline code can contain literal pipes; leave them untouched.
    if !s.contains("|") || s.contains("\\") || s.contains("`") then Vector.empty
    else
      val inner = s.stripPrefix("|").stripSuffix("|")
      inner.split("\\|", -1).toVector.map(_.trim)
end FeishuTableText
