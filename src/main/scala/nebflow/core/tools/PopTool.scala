package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

import java.nio.file.{Files, Path, Paths}

/**
 * Pop tool — opens a file in the Canvas panel as a new tab.
 *
 * Sends a WebSocket message to the frontend, which dispatches a
 * `workspace-open-item` event that canvas.js picks up to render the file
 * using the existing file viewer registry (Monaco editor for code, markdown
 * renderer, image viewer, PDF viewer, etc.).
 *
 * For binary files (images, PDFs, Office docs), only metadata is sent — the
 * frontend fetches the content via /api/nf-file, same as the file explorer.
 */
object PopTool extends Tool:

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.pop")

  /** Max text file size to send via WS (2 MB). Larger files are read by the frontend via /api/nf-file. */
  private val MaxTextSize = 2 * 1024 * 1024

  /** Extensions that the frontend fetches via /api/nf-file (binary viewers). */
  private val BinaryExtensions = Set(
    "png",
    "jpg",
    "jpeg",
    "gif",
    "svg",
    "webp",
    "bmp",
    "ico",
    "avif",
    "tiff",
    "tif",
    "pdf",
    "doc",
    "docx",
    "xls",
    "xlsx",
    "xlsm",
    "ppt",
    "pptx",
    "epub"
  )

  private def fileExtension(path: String): String =
    path.lastIndexOf('.') match
      case -1 => ""
      case i => path.substring(i + 1).toLowerCase

  /** Map file extension to frontend viewer itemType. Mirrors WebSocketRoutes.readFile logic. */
  private def detectItemType(ext: String): String = ext match
    case "md" | "markdown" => "markdown"
    case "html" | "htm" => "html"
    case "json" => "json"
    case "yaml" | "yml" => "yaml"
    case "csv" | "tsv" => "csv"
    case "png" | "jpg" | "jpeg" | "gif" | "svg" | "webp" | "bmp" | "ico" | "avif" | "tiff" | "tif" => "image"
    case "pdf" => "pdf"
    case "doc" | "docx" => "docx"
    case "xls" | "xlsx" | "xlsm" => "xlsx"
    case "ppt" | "pptx" => "pptx"
    case "epub" => "epub"
    case _ => "code"

  /** Resolve a path string (supports ~ expansion) to a normalized Path. */
  private def resolvePath(s: String): Option[Path] =
    try
      val expanded = if s.startsWith("~") then sys.props("user.home") + s.substring(1) else s
      Some(Paths.get(expanded).normalize())
    catch case _: Exception => None

  val name = "Pop"

  val description: String =
    """Opens a file in the Canvas panel as a new tab. The file is displayed using the appropriate viewer (Monaco editor for code, markdown renderer, image viewer, PDF viewer, etc.).

## When to use

- You just created or modified a file and want to show it to the user.
- The user asks to "open" or "show" a file.
- You generated a plot, diagram, or document and want to present it.
- After completing work, generate a visual report (diagram, chart, HTML page) and Pop it to present results to the user.

The Canvas tab supports the same file types as the file explorer. The tab title defaults to the filename; provide `title` to customize it.

## Parameters

- filePath (string, required): Absolute path to the file (supports `~` expansion).
- title (string, optional): Custom tab title. Defaults to the filename.

Example: {"filePath": "/tmp/output.svg"}
Example: {"filePath": "~/projects/README.md", "title": "README"}"""

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "filePath" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Absolute path to the file to display (supports ~ expansion)".asJson
        ),
        "title" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Custom tab title (defaults to filename)".asJson
        )
      ),
      "required" -> Json.arr("filePath".asJson)
    )
  )

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("filePath").flatMap(_.asString).getOrElse("")
    val customTitle = input("title").flatMap(_.asString).getOrElse("")

    if filePathStr.isBlank then IO.pure(Left(ToolError("Pop tool requires a `filePath` parameter.")))
    else
      resolvePath(filePathStr) match
        case None =>
          IO.pure(Left(ToolError(s"Invalid path: $filePathStr")))

        case Some(path) =>
          IO.blocking {
            if !Files.exists(path) then Left(ToolError(s"File not found: $path"))
            else if !Files.isRegularFile(path) then Left(ToolError(s"Not a regular file: $path"))
            else
              val ext = fileExtension(path.toString)
              val itemType = detectItemType(ext)
              val fileName = path.getFileName.toString
              val tabTitle = if customTitle.nonEmpty then customTitle else fileName
              val size = Files.size(path)
              val isBinary = BinaryExtensions.contains(ext)

              // For text files under MaxTextSize: read content and send via WS.
              // For binary files or large text: send metadata only, frontend fetches via /api/nf-file.
              val content =
                if isBinary || size > MaxTextSize then ""
                else new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)

              // Build the WS message — dispatched as 'popFile' type.
              val msg = Json.obj(
                "type" -> "popFile".asJson,
                "item" -> Json.obj(
                  "id" -> s"pop:${path.toString}".asJson,
                  "itemType" -> itemType.asJson,
                  "title" -> tabTitle.asJson,
                  "content" -> content.asJson,
                  "absPath" -> path.toString.asJson,
                  "size" -> size.asJson,
                  "pinned" -> true.asJson
                )
              )

              Right((msg, tabTitle))
          }.flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right((msg, tabTitle)) =>
              val sendIO = ctx.wsSend.getOrElse((_: Json) => IO.unit)
              sendIO(msg) >> IO.pure(Right(s"Opened $tabTitle in Canvas."))
          }

    end if

  end call

  def summarize(input: JsonObject): String =
    val filePath = input("filePath").flatMap(_.asString).getOrElse("?")
    val title = input("title").flatMap(_.asString).getOrElse("")
    val label = if title.nonEmpty then title else filePath.split('/').lastOption.getOrElse(filePath)
    s"Pop\n  ($label)"

  def summarizeResult(input: JsonObject, result: String): String =
    val filePath = input("filePath").flatMap(_.asString).getOrElse("?")
    val fileName = filePath.split('/').lastOption.getOrElse(filePath)
    s"Opened $fileName in Canvas"

end PopTool
