package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import java.nio.file.{FileSystems, Files, Path, Paths}
import scala.jdk.StreamConverters.*

object GlobTool extends Tool:
  val MAX_RESULTS = 100

  val name = "Glob"

  val description = """- Fast file pattern matching tool that works with any codebase size
- Supports glob patterns like "**/*.js" or "src/**/*.ts"
- Returns matching file paths sorted by modification time
- Use this tool when you need to quickly find files by name patterns"""

  val inputSchema = JsonObject.fromIterable(List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "pattern" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The glob pattern to match files against (e.g. \"**/*.js\", \"src/**/*.ts\")".asJson),
      "path" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "The directory to search in. Defaults to current working directory.".asJson)
    ),
    "required" -> io.circe.Json.arr("pattern".asJson)
  ))

  def summarize(input: JsonObject): String =
    val pattern = input("pattern").flatMap(_.asString).getOrElse("")
    s"Glob($pattern)"

  def summarizeResult(input: JsonObject, result: String): String =
    if result == "No files found matching the pattern." then "No files found"
    else s"${result.split("\\n").length} files found"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.blocking {
    val pattern = input("pattern").flatMap(_.asString).getOrElse("")
    val searchDirStr = input("path").flatMap(_.asString)
    val searchDir = searchDirStr match
      case Some(p) if p.startsWith("/") => Paths.get(p)
      case Some(p) => Paths.get(ctx.projectRoot, p)
      case None => Paths.get(ctx.projectRoot)

    try
      val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$pattern")
      val results = Files.walk(searchDir).toScala(List)
        .filter(p => !p.toString.contains("node_modules") && !p.toString.contains(".git"))
        .filter(p => Files.isRegularFile(p) && matcher.matches(p.getFileName))
        .sortBy(p => -Files.getLastModifiedTime(p).toMillis)
        .map(p => searchDir.relativize(p).toString)

      if results.isEmpty then Right("No files found matching the pattern.")
      else
        val truncated = results.length >= MAX_RESULTS
        val output = results.take(MAX_RESULTS).mkString("\n")
        Right(if truncated then output + "\n\n(Results are truncated. Consider using a more specific path or pattern.)" else output)
    catch
      case e: Exception => Right(s"Error: ${e.getMessage}")
  }
