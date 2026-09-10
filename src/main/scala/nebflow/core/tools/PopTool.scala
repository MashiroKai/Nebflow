package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}

import java.nio.file.{Files, Path, Paths}

/**
 * Pop tool — opens a file or URL in the Canvas panel as a new tab.
 *
 * 2026-09-10 作者裁定（「我觉得把pop工具给nebula专属吧」）：Pop = Nebula 专属
 * 工具——除 Nebula 本体根会话外，任何 agent / 节点会话 / 子 agent 一律不得调用。
 * 理由：Canvas 是用户面呈现通道，节点乱 Pop 是过程件污染入口——靠纪律不如靠
 * 工具面收口。收口两层（缺一即不完整）：
 *   ①定义/授能层：AgentCore.NebulaExclusiveTools 携带 Pop（非 Nebula 身份含
 *     "*" 声明一律剥离）、GeneralFixedTools 摘除、PluginRegistry.BuiltinToolWhitelist
 *     摘除（关闭插件再授予通道）；
 *   ②分发层（本文件）：call 最前的身份闸——先于任何副作用（路径解析 / 文件读 /
 *     HTML 图片内联 / WS 发送）。
 *
 * Sends a WebSocket message to the frontend, which dispatches a
 * `workspace-open-item` event that canvas.js picks up to render the file
 * using the existing file viewer registry (Monaco editor for code, markdown
 * renderer, image viewer, PDF viewer, etc.).
 *
 * For binary files (images, PDFs, Office docs), only metadata is sent — the
 * frontend fetches the content via /api/nf-file, same as the file explorer.
 *
 * For HTTP/HTTPS URLs, the URL is sent directly — the frontend renders it
 * in an embedded iframe.
 */
object PopTool extends Tool:

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.pop")

  /** Max text file size to send via WS (2 MB). Larger files are read by the frontend via /api/nf-file. */
  private val MaxTextSize = 2 * 1024 * 1024

  /** Max single image size to embed as base64 data URI (5 MB). Larger images keep their original src. */
  private val MaxEmbedImageSize = 5 * 1024 * 1024

  /** Image extensions that can be embedded as data URIs in HTML. */
  private val EmbeddableImageExtensions = Set("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp")

  /** Matches the src attribute value of an <img> tag (single or double quoted). */
  private val ImgSrcPattern = """(?i)<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""".r

  private def fileExtension(path: String): String =
    path.lastIndexOf('.') match
      case -1 => ""
      case i => path.substring(i + 1).toLowerCase

  // Binary-extension + itemType mapping now lives in a single source of
  // truth (F3): core/workspace/FileTypeRegistry — shared with the WS
  // readFile / pop.readFile routes. The local BinaryExtensions set and
  // detectItemType match were removed as duplicate #1/#2.

  /** Resolve a path string (supports ~ expansion) to a normalized Path. */
  private def resolvePath(s: String): Option[Path] =
    try
      val expanded = if s.startsWith("~") then sys.props("user.home") + s.substring(1) else s
      Some(Paths.get(expanded).normalize())
    catch case _: Exception => None

  /** Map image file extension to MIME type. */
  private def mimeFromExt(ext: String): String = ext.toLowerCase match
    case "png"         => "image/png"
    case "jpg" | "jpeg" => "image/jpeg"
    case "gif"         => "image/gif"
    case "webp"        => "image/webp"
    case "svg"         => "image/svg+xml"
    case "bmp"         => "image/bmp"
    case _             => "application/octet-stream"

  /** Check if a src value is a remote/special URL that should not be embedded. */
  private def isRemoteOrSpecialUrl(src: String): Boolean =
    val lower = src.toLowerCase
    lower.startsWith("http://") || lower.startsWith("https://") ||
      lower.startsWith("data:") || lower.startsWith("blob:") ||
      lower.startsWith("#") || lower.startsWith("javascript:") ||
      lower.startsWith("mailto:") || lower.startsWith("tel:")

  /** Resolve an img src (file://, /absolute, ~/, or relative) to a local file Path. */
  private def resolveImgSrc(src: String, htmlDir: Path): Option[Path] =
    val cleaned =
      if src.startsWith("file:///") then src.stripPrefix("file://")          // file:///path → /path
      else if src.startsWith("file://localhost/") then "/" + src.stripPrefix("file://localhost")
      else if src.startsWith("file://") then "/" + src.stripPrefix("file://") // file://path → /path
      else if src.startsWith("file:") then src.stripPrefix("file:")
      else src
    resolvePath(cleaned).map(p =>
      if p.isAbsolute then p
      else htmlDir.resolve(p).normalize()
    )

  /**
   * Embed local images referenced by <img src="..."> as base64 data URIs so
   * they render inside the Canvas iframe without /api/nf-file or auth tokens.
   * Remote URLs and unreadable files are left unchanged (silent skip).
   */
  private def embedLocalImages(html: String, htmlDir: Path): String =
    ImgSrcPattern.replaceAllIn(html, { m =>
      val src = m.group(1)
      if isRemoteOrSpecialUrl(src) then m.group(0)
      else
        val replaced: Option[String] = resolveImgSrc(src, htmlDir).flatMap { imgPath =>
          try
            val ext = fileExtension(imgPath.toString)
            if !Files.exists(imgPath) || !Files.isRegularFile(imgPath) ||
               !EmbeddableImageExtensions.contains(ext)
            then None
            else if Files.size(imgPath) > MaxEmbedImageSize then None
            else
              val bytes = Files.readAllBytes(imgPath)
              val b64 = java.util.Base64.getEncoder.encodeToString(bytes)
              val dataUri = s"data:${mimeFromExt(ext)};base64,$b64"
              // Replace only the src value: the regex guarantees group(1) is
              // immediately before the closing quote at the end of the match.
              val full = m.group(0)
              Some(full.dropRight(src.length + 1) + dataUri + full.takeRight(1))
          catch case _: Exception => None
        }
        replaced.getOrElse(m.group(0))
    })

  /** Extract hostname from a URL string. */
  private def extractHostname(url: String): String =
    try java.net.URI.create(url).getHost
    catch case _: Exception => url

  /** Check if a string is an HTTP/HTTPS URL. */
  private def isHttpUrl(s: String): Boolean =
    s.startsWith("http://") || s.startsWith("https://")

  val name = "Pop"

  val description: String =
    """Opens a file or URL in the Canvas panel as a new tab. Files are displayed using the appropriate viewer (Monaco editor for code, markdown renderer, image viewer, PDF viewer, etc.). URLs are displayed in an embedded iframe.

## Nebula-only (2026-09-10 author ruling)

Pop is Nebula-exclusive: only the Nebula root session may call it. Every other agent, node session, or sub-agent call is rejected with POP_NEBULA_ONLY. Nodes do not Pop — they hand the deliverable to the chain end / Nebula along the out edge, and Nebula decides whether it is shown. Plugins cannot grant Pop back (it left the builtin tool whitelist).

## When to use

- Nebula decides a finished result should be shown to the user in Canvas.
- The user asks to "open" or "show" a file.
- A node handed over a deliverable along the out edge and showing it is warranted.
- You want to show a web page (e.g. a deployed site, documentation) to the user.

The Canvas tab supports the same file types as the file explorer. The tab title defaults to the filename (or hostname for URLs); provide `title` to customize it.

## Parameters

- filePath (string, required): Absolute path to the file (supports `~` expansion), or an HTTP/HTTPS URL.
- title (string, optional): Custom tab title. Defaults to the filename or URL hostname.

Example: {"filePath": "/tmp/output.svg"}
Example: {"filePath": "~/projects/README.md", "title": "README"}
Example: {"filePath": "https://example.com"}"""

  val inputSchema: JsonObject = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "filePath" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Absolute path to the file to display (supports ~ expansion), or an HTTP/HTTPS URL".asJson
        ),
        "title" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Custom tab title (defaults to filename)".asJson
        )
      ),
      "required" -> Json.arr("filePath".asJson)
    )
  )

  /** 拒答文案（2026-09-10 作者裁定，作者原话逐字保留）：前置结构化前缀 + 错误码
    * （仓内 ToolError 惯例：`<Tool>: ... (CODE)`，同 TaskBoardTool.forbidden /
    * MemoryEditTool DREAM_APPEND_DENIED）。 */
  private val NebulaOnlyError: ToolError = ToolError(
    "Pop: permission denied — Pop 已收归 Nebula 专属；交付物请沿 out 边交给链末端 / Nebula，由 Nebula 决定是否展示 (POP_NEBULA_ONLY)")

  /** 身份闸判据（2026-09-10 作者裁定）：两层同真才放行。
    *
    *  1. `ctx.agentDef.exists(_.name == "Nebula")` —— 身份来源 = ctx.agentDef
    *     （AgentCore toolCtx 构造处注入 effectiveDef，AgentCore.scala:1131）；
    *     MemoryEditTool.scala:325-338 的 dream 闸先例同款，禁用全局状态猜身份。
    *  2. `ctx.depth == 0` —— 「Nebula 本体根会话」判据（SandboxPolicy.
    *     isNebulaRootSession 同款：depth==0 排除 NodeDef.agent="Nebula" 的节点
    *     会话，它们的 depth=1）。子会话判定口径 = depth：Nebula 派生的 SubTask
    *     worker / 节点会话 / 子 agent 全部 depth≥1；depth==0 只有全仓唯一
    *     根会话 spawn 点（WebSocketRoutes.doSpawnRootAgent）——「Nebula 自己」
    *     与「Nebula 派生的会话」由此分开。
    *
    * `ctx.agentDef == None`（REST 直调 / spec harness）→ **fail-closed**：非
    * Nebula 身份一律拒。实测无合法非 agent 调用面被误伤：Pop 不在
    * RemoteExecutor.remoteableTools（RemoteExecutor.scala:658 只有
    * Bash/Read/Write/Edit/Glob/Grep）⇒ 远程执行链永不带 Pop；remote-exec 接收侧
    * （RestApiRoutes.scala:1154-1158 / NeblinkRelayTunnel.scala:275）不传
    * agentDef 也不传 wsSend，那里的 Pop 本来只回声、不发送、无功能面。spec
    * harness 显式传 Nebula ctx（PopToolSpec.captureCtx）。
    */
  private def isNebulaRootSession(ctx: ToolContext): Boolean =
    ctx.agentDef.exists(_.name == "Nebula") && ctx.depth == 0

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // 身份闸最前——先于任何副作用（filePath 解析 / 文件读 / HTML 图片内联 /
    // WS 发送）。非 Nebula 身份（含 agentDef=None）在此短路，零副作用。
    if !isNebulaRootSession(ctx) then IO.pure(Left(NebulaOnlyError))
    else doCall(input, ctx)

  /** Nebula 本体根会话的 Pop 实现（身份已过闸；语义与本批前逐字节一致）。 */
  private def doCall(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("filePath").flatMap(_.asString).getOrElse("")
    val customTitle = input("title").flatMap(_.asString).getOrElse("")

    if filePathStr.isBlank then IO.pure(Left(ToolError("Pop tool requires a `filePath` parameter.")))
    else if isHttpUrl(filePathStr) then
      val hostname = extractHostname(filePathStr)
      val tabTitle = if customTitle.nonEmpty then customTitle else hostname
      val msg = Json.obj(
        "type" -> "popFile".asJson,
        "item" -> Json.obj(
          "id" -> s"url:$filePathStr".asJson,
          "itemType" -> "url".asJson,
          "title" -> tabTitle.asJson,
          "url" -> filePathStr.asJson,
          "pinned" -> true.asJson
        )
      )
      val sendIO = ctx.wsSend.getOrElse((_: Json) => IO.unit)
      sendIO(msg) >> IO.pure(Right(s"Opened $tabTitle in Canvas."))
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
              val entry = nebflow.core.workspace.FileTypeRegistry.detect(ext)
              val itemType = entry.itemType
              val fileName = path.getFileName.toString
              val tabTitle = if customTitle.nonEmpty then customTitle else fileName
              val size = Files.size(path)
              val isBinary = entry.binary

              // For text files under MaxTextSize: read content and send via WS.
              // For binary files or large text: send metadata only, frontend fetches via /api/nf-file.
              val rawContent =
                if isBinary || size > MaxTextSize then ""
                else new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8)

              // Embed local images as base64 data URIs so they render in the
              // Canvas iframe. Only for HTML files with non-empty content.
              val htmlDir = Option(path.getParent).getOrElse(Paths.get("."))
              val content =
                if itemType == "html" && rawContent.nonEmpty then embedLocalImages(rawContent, htmlDir)
                else rawContent

              // Build the WS message — dispatched as 'popFile' type.
              val msg = Json.obj(
                "type" -> "popFile".asJson,
                "item" -> Json.obj(
                  "id" -> s"file:${path.toString}".asJson,
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

  end doCall

  def summarize(input: JsonObject): String =
    val filePath = input("filePath").flatMap(_.asString).getOrElse("?")
    val title = input("title").flatMap(_.asString).getOrElse("")
    val label =
      if title.nonEmpty then title
      else if isHttpUrl(filePath) then extractHostname(filePath)
      else filePath.split('/').lastOption.getOrElse(filePath)
    s"Pop\n  ($label)"

  def summarizeResult(input: JsonObject, result: String): String =
    val filePath = input("filePath").flatMap(_.asString).getOrElse("?")
    val fileName =
      if isHttpUrl(filePath) then extractHostname(filePath)
      else filePath.split('/').lastOption.getOrElse(filePath)
    s"Opened $fileName in Canvas"

end PopTool
