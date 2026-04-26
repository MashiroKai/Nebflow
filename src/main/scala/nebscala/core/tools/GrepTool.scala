package nebscala.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*

object GrepTool extends Tool:
  val DEFAULT_HEAD_LIMIT = 250

  val name = "Grep"

  val description = """A powerful search tool built on ripgrep

Usage:
- ALWAYS use Grep for search tasks.
- Supports full regex syntax (e.g. "log.*Error", "function\\s+\\w+")
- Filter files with glob parameter (e.g. "*.js", "*.{ts,tsx}")
- Output modes: "content" shows matching lines, "files_with_matches" shows only file paths, "count" shows match counts
- Use -i for case insensitive search"""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "pattern" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The regular expression pattern to search for in file contents".asJson),
      "path" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "File or directory to search in. Defaults to current working directory.".asJson),
      "glob" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\")".asJson),
      "output_mode" -> io.circe.Json.obj("type" -> "string".asJson, "enum" -> io.circe.Json.arr("content".asJson, "files_with_matches".asJson, "count".asJson), "description" -> "Output mode. Defaults to \"files_with_matches\".".asJson),
      "-i" -> io.circe.Json.obj("type" -> "boolean".asJson, "description" -> "Case insensitive search".asJson),
      "head_limit" -> io.circe.Json.obj("type" -> "number".asJson, "description" -> "Limit output to first N results. Defaults to 250.".asJson)
    ),
    "required" -> io.circe.Json.arr("pattern".asJson)
  ))

  def summarize(input: JsonObject): String =
    val pattern = input("pattern").flatMap(_.asString).getOrElse("")
    s"Grep($pattern)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result == "No matches found." then "No matches"
    else
      val lines = result.split("\\n").filter(_.nonEmpty)
      val mode = input("output_mode").flatMap(_.asString).getOrElse("files_with_matches")
      if mode == "count" then s"${lines.length} files with matches"
      else if mode == "files_with_matches" then s"${lines.length} files matched"
      else s"${lines.length} matches"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val pattern = input("pattern").flatMap(_.asString).getOrElse("")
    val pathOpt = input("path").flatMap(_.asString)
    val searchRoot = pathOpt match
      case Some(p) if p.startsWith("/") => p
      case Some(p) => os.Path(ctx.projectRoot) / p
      case None => ctx.projectRoot

    val args = scala.collection.mutable.ListBuffer("--color=never")
    if input("-i").flatMap(_.asBoolean).contains(true) then args += "--ignore-case"
    input("glob").flatMap(_.asString).foreach(g => args ++= List("--glob", g))

    val limit = input("head_limit").flatMap(_.asNumber).flatMap(_.toInt).getOrElse(DEFAULT_HEAD_LIMIT)
    val mode = input("output_mode").flatMap(_.asString).getOrElse("files_with_matches")

    mode match
      case "files_with_matches" => args += "--files-with-matches"
      case "count" => args += "--count"
      case "content" => args += "--line-number"
      case _ => args += "--files-with-matches"

    args ++= List("--max-count", Math.max(1, limit).toString)
    args ++= List("-g", "!node_modules/**")
    args ++= List("-g", "!.git/**")
    args += pattern
    args += searchRoot.toString

    try
      val proc = new ProcessBuilder(("rg" :: args.toList)*).directory(new java.io.File(ctx.projectRoot)).start()
      val stdout = scala.io.Source.fromInputStream(proc.getInputStream).mkString
      val stderr = scala.io.Source.fromInputStream(proc.getErrorStream).mkString
      val exitCode = proc.waitFor()

      if exitCode == 2 then
        Right(s"Error: ${if stderr.trim.nonEmpty then stderr.trim else s"rg exited with code $exitCode"}")
      else if stdout.trim.isEmpty then
        Right("No matches found.")
      else if mode == "content" then
        val lines = stdout.trim.split("\\n")
        val truncated = lines.length > limit
        val result = lines.take(limit).mkString("\\n")
        Right(if truncated then result + s"\\n... and ${lines.length - limit} more matches" else result)
      else
        Right(stdout.trim)
    catch
      case e: Exception => Right(s"Failed to spawn rg: ${e.getMessage}")
  }
