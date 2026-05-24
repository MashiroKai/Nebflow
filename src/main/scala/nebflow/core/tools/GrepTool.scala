package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

object GrepTool extends Tool:
  val DEFAULT_HEAD_LIMIT = 250

  val name = "Grep"

  val description = """A powerful search tool built on ripgrep.

Usage:
- ALWAYS use Grep for search tasks. Do not use Bash with grep/rg.
- Supports full regex syntax (e.g. "log.*Error", "function\\s+\\w+")
- Filter files with glob parameter (e.g. "*.js", "*.{ts,tsx}") or type parameter (e.g. "js", "py", "rust")
- Output modes: "content" shows matching lines (supports -A/-B/-C context), "files_with_matches" shows only file paths, "count" shows match counts
- Use -i for case insensitive search"""

  private val VCS_DIRS = List(".git", ".svn", ".hg", ".bzr", ".jj")

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "pattern" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "The regular expression pattern to search for in file contents".asJson
        ),
        "path" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "File or directory to search in. Defaults to current working directory.".asJson
        ),
        "glob" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\")".asJson
        ),
        "type" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "File type to search (rg --type). Common types: js, py, rust, go, java, etc.".asJson
        ),
        "output_mode" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "enum" -> io.circe.Json.arr("content".asJson, "files_with_matches".asJson, "count".asJson),
          "description" -> "Output mode. Defaults to \"files_with_matches\". \"content\" supports -A/-B/-C context lines.".asJson
        ),
        "-i" -> io.circe.Json.obj("type" -> "boolean".asJson, "description" -> "Case insensitive search".asJson),
        "-A" -> io.circe.Json.obj(
          "type" -> "number".asJson,
          "description" -> "Number of lines to show after each match. Requires output_mode: \"content\".".asJson
        ),
        "-B" -> io.circe.Json.obj(
          "type" -> "number".asJson,
          "description" -> "Number of lines to show before each match. Requires output_mode: \"content\".".asJson
        ),
        "-C" -> io.circe.Json.obj(
          "type" -> "number".asJson,
          "description" -> "Number of lines to show before and after each match. Requires output_mode: \"content\".".asJson
        ),
        "head_limit" -> io.circe.Json.obj(
          "type" -> "number".asJson,
          "description" -> "Limit output to first N results. Defaults to 250. Pass 0 for unlimited.".asJson
        ),
        "offset" -> io.circe.Json.obj(
          "type" -> "number".asJson,
          "description" -> "Skip first N results before applying head_limit. Defaults to 0.".asJson
        ),
        "multiline" -> io.circe.Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Enable multiline mode where . matches newlines. Default: false.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("pattern".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val pattern = input("pattern").flatMap(_.asString).getOrElse("")
    val path = input("path").flatMap(_.asString)
    path match
      case Some(p) => s"""Grep("$pattern")\n  (path="$p")"""
      case None => s"""Grep("$pattern")"""

  def summarizeResult(input: JsonObject, result: String): String =
    if result == "No matches found." then "No matches"
    else
      val lines = result.split("\\n").filter(_.nonEmpty)
      val mode = input("output_mode").flatMap(_.asString).getOrElse("files_with_matches")
      if mode == "count" then s"${lines.length} files with matches"
      else if mode == "files_with_matches" then s"${lines.length} files matched"
      else s"${lines.length} matches"

  private def toRelativePath(absPath: String, root: os.Path): String =
    try
      val p = os.Path(absPath)
      if p.startsWith(root) then p.relativeTo(root).toString
      else absPath
    catch case _: Exception => absPath

  private def relativizeLine(line: String, root: os.Path, hasColon: Boolean): String =
    if hasColon then
      val colonIdx = line.indexOf(':')
      if colonIdx > 0 then toRelativePath(line.substring(0, colonIdx), root) + line.substring(colonIdx)
      else line
    else toRelativePath(line, root)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val pattern = input("pattern").flatMap(_.asString).getOrElse("")
    val pathOpt = input("path").flatMap(_.asString)
    val searchRoot = pathOpt match
      case Some(p) if p.startsWith("/") || (p.length >= 2 && p.charAt(1) == ':') => p
      case Some(p) => nebflow.core.PathUtil.resolvePath(p, os.Path(ctx.projectRoot)).toString
      case None => ctx.projectRoot

    val mode = input("output_mode").flatMap(_.asString).getOrElse("files_with_matches")
    val limit = input("head_limit").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(DEFAULT_HEAD_LIMIT)
    val offset = input("offset").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
    val effectiveLimit = if limit == 0 then Int.MaxValue else limit
    val projectRootPath = os.Path(ctx.projectRoot)

    val args = scala.collection.mutable.ListBuffer[String](
      "--color=never",
      "--hidden",
      "--max-columns",
      "500"
    )

    for dir <- VCS_DIRS do args ++= List("--glob", s"!$dir")

    if input("-i").flatMap(_.asBoolean).contains(true) then args += "--ignore-case"

    if input("multiline").flatMap(_.asBoolean).contains(true) then args ++= List("-U", "--multiline-dotall")

    mode match
      case "files_with_matches" => args += "--files-with-matches"
      case "count" => args += "--count"
      case "content" => args += "--line-number"
      case _ => args += "--files-with-matches"

    if mode == "content" then
      input("-C").flatMap(_.asNumber).flatMap(_.toInt) match
        case Some(c) => args ++= List("-C", c.toString)
        case None =>
          input("-B").flatMap(_.asNumber).flatMap(_.toInt).foreach(b => args ++= List("-B", b.toString))
          input("-A").flatMap(_.asNumber).flatMap(_.toInt).foreach(a => args ++= List("-A", a.toString))

    input("glob").flatMap(_.asString).foreach(g => args ++= List("--glob", g))
    input("type").flatMap(_.asString).foreach(t => args ++= List("--type", t))

    if pattern.startsWith("-") then args ++= List("-e", pattern)
    else args += pattern

    args += searchRoot

    RgHelper.runRg(args.toList, ctx.projectRoot) match
      case Left(err) => Left(err)
      case Right((stdoutStr, stderrStr, exitCode)) =>
        if exitCode == 2 then
          Left(
            ToolError(s"Error: ${if stderrStr.trim.nonEmpty then stderrStr.trim else s"rg exited with code $exitCode"}")
          )
        else if stdoutStr.trim.isEmpty then Right("No matches found.")
        else
          val allLines = stdoutStr.trim.split("\n")
          val needsColon = mode == "content" || mode == "count"

          mode match
            case "content" =>
              val sliced = allLines.slice(offset, offset + effectiveLimit)
              val result = sliced.map(relativizeLine(_, projectRootPath, hasColon = true)).mkString("\n")
              val pagination = formatPagination(allLines.length, offset, effectiveLimit)
              Right(result + pagination)

            case "files_with_matches" =>
              val sliced = allLines.slice(offset, offset + effectiveLimit)
              val result = sliced.map(relativizeLine(_, projectRootPath, hasColon = false)).mkString("\n")
              val pagination = formatPagination(allLines.length, offset, effectiveLimit)
              Right(result + pagination)

            case "count" =>
              val sliced = allLines.slice(offset, offset + effectiveLimit)
              val result = sliced.map(relativizeLine(_, projectRootPath, hasColon = true)).mkString("\n")
              val pagination = formatPagination(allLines.length, offset, effectiveLimit)
              var totalMatches = 0
              var fileCount = 0
              for line <- sliced do
                val colonIdx = line.lastIndexOf(':')
                if colonIdx > 0 then
                  line.substring(colonIdx + 1).trim.toIntOption.foreach { c =>
                    totalMatches += c
                    fileCount += 1
                  }
              val summary = s"\n\nFound $totalMatches total ${
                  if totalMatches == 1 then "occurrence" else "occurrences"
                } across $fileCount ${if fileCount == 1 then "file" else "files"}."
              Right(result + summary + pagination)

            case _ =>
              Right(stdoutStr.trim)
          end match
    end match
  }

  private def formatPagination(totalLines: Int, offset: Int, limit: Int): String =
    val truncated = totalLines > offset + limit
    if !truncated && offset == 0 then ""
    else
      val parts = scala.collection.mutable.ListBuffer[String]()
      if truncated then parts += s"limit: $limit"
      if offset > 0 then parts += s"offset: $offset"
      s"\n\n[Showing results with pagination = ${parts.mkString(", ")}]"

end GrepTool
