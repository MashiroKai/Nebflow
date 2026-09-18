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
 * Exception (2026-09-16 imgfix batch; cumulative cap added by the img-ticket
 * batch i): an image inside the shared inline policy (≤5MB, embeddable format)
 * is embedded in the payload as a `data:` URI and the viewer renders it with no
 * request — as long as the call's total inline size stays within
 * `FileRefs.MaxInlinePayloadChars` (40,000 characters of `data:` URI); the
 * ticket leg is reached by images outside that policy and by images past that
 * total.
 *
 * For HTTP/HTTPS URLs, the URL is sent directly — the frontend renders it
 * in an embedded iframe.
 */
object PopTool extends Tool:

  /** Shared local-reference policy (failure enum, file probe, app-route
    *  exemption, warning/counter JSON shapes) — the same module Card uses.
    *  Pop contributes only the resolution policy below (a Pop'd HTML file has
    *  a containing directory, a Card does not) and inlines instead of
    *  proxying. */
  import FileRefs.*

  private val logger = nebflow.core.NebflowLogger.forName("nebflow.tools.pop")

  /** Max text file size to send via WS (2 MB). Larger files are read by the frontend via /api/nf-file. */
  private val MaxTextSize = 2 * 1024 * 1024

  // The inline policy (`MaxEmbedImageSize` = 5 MB, `EmbeddableImageExtensions`,
  // `mimeFromExt`, `isInlineImage`, `readAsDataUri`) is ONE definition in
  // `FileRefs` — Card embeds images on its card face with exactly the same
  // rule, so the two faces cannot drift apart (imgfix batch, 2026-09-16).

  /** Matches the src attribute value of an <img> tag (single or double quoted). */
  private val ImgSrcPattern = """(?i)<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""".r

  // Binary-extension + itemType mapping now lives in a single source of
  // truth (F3): core/workspace/FileTypeRegistry — shared with the WS
  // readFile / pop.readFile routes. The local BinaryExtensions set and
  // detectItemType match were removed as duplicate #1/#2.

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

  /** One HTML image pass: the rewritten HTML plus what happened to every local
    *  reference (inlined / deferred / exempt / rejected). */
  private case class PopRefOutcome(
      html: String,
      inlined: Int,
      deferred: Int,
      exempt: Int,
      rejects: List[RejectedRef],
      /** Decoded-form disclosures (imgref batch 2026-09-18): a reference whose
        *  path was spelled with URL escapes / a bare `+` was resolved as the
        *  decoded form, and the user-facing result says so. */
      notes: List[String] = Nil
  )

  /**
   * Embed local images referenced by <img src="..."> as base64 data URIs so
   * they render inside the Canvas iframe without /api/nf-file or auth tokens.
   * Remote URLs are left unchanged.
   *
   * 2026-09-11 (toolfail batch): this pass used to answer `None` to every
   * question and let `replaced.getOrElse(m.group(0))` keep the raw value with
   * zero feedback — the Canvas iframe then fetched a path that does not exist
   * and the user saw an empty box with no explanation (the Card leg had the
   * same defect, fixed in the carderr batch, merge 52e2f58f). Every local
   * reference now gets an explicit verdict from the SAME probe Card uses
   * (`FileRefs.probeFile`):
   *
   *   - `Proxy` + embeddable extension + ≤5MB → inlined (counted `proxied`);
   *   - `Proxy` otherwise → `deferred`: the raw value stays and the Canvas
   *     HTML viewer's own rewrite serves it through /api/nf-file. Counted,
   *     never warned — a 5MB+ PNG that renders fine must not appear in an
   *     actionable defect list;
   *   - `Reject` whose cause is the endpoint's REACH layer only, on a file that
   *     is not a credential (`FileRefs.inlineMayTakeOver`, 返工 r2) → still
   *     inlined when the bytes fit (the shipped behaviour: such a file never had
   *     a working /api/nf-file leg, only working bytes); when they do not fit,
   *     it is warned rather than counted `deferred` (there is no fetch to win);
   *   - `Exempt` (an app route such as `/js/…`) → counted only;
   *   - `Reject` → structured warning (原始串 → 解析后路径 → 原因): the Canvas
   *     fallback cannot serve it either, so nothing would render it.
   */
  private def processLocalImages(html: String, htmlDir: Path): PopRefOutcome =
    val rejects = scala.collection.mutable.ListBuffer.empty[RejectedRef]
    val notes = scala.collection.mutable.ListBuffer.empty[String]
    var inlined = 0
    var deferred = 0
    var exempt = 0
    // ONE cumulative budget for this pass (= this tool call), spent in the order
    // the `<img src>` values appear (`replaceAllIn` walks the document left to
    // right): an image is embedded while the remaining balance covers its
    // `data:` URI, and left to the Canvas `/api/nf-file` rewrite once it does
    // not. See `FileRefs.MaxInlinePayloadChars` (img-ticket batch i, #687-C).
    val budget = InlineBudget()

    def record(decision: RefDecision): Unit = decision match
      case RefDecision.Exempt(_)        => exempt += 1
      case RefDecision.Reject(rejected) => rejects += rejected
      case _                            => ()

    /** The replacement src value, or None to keep the raw one. */
    def newValueFor(src: String): Option[String] =
      if isRemoteOrSpecialUrl(src) then None
      else
        // imgref batch (2026-09-18): a reference spelled with URL escapes
        // (`%20`) or a bare `+` does not name anything on disk — resolve it
        // through its candidate forms (raw first, then the decoded form,
        // hit-and-use, and disclose which form was used).
        val hit = FileRefs.resolveCandidates(
          src,
          v => resolveImgSrc(v, htmlDir),
          v => unresolvable(v, "the reference could not be resolved to a filesystem path")
        )
        hit.note.foreach(n => if !notes.contains(n) then notes += n)
        hit.path match
          case None =>
            record(applyAppRouteExemption(src, hit.decision))
            None
          case Some(p) =>
            applyAppRouteExemption(src, hit.decision) match
              case RefDecision.Proxy(_) =>
                // Servable — inline it when the iframe can be spared the fetch
                // AND this call's cumulative inline budget still covers the
                // bytes, else leave it for the Canvas viewer's /api/nf-file
                // rewrite. The rule itself is shared with Card
                // (`FileRefs.embedImage`); only the failure handling is
                // Pop-specific: an unreadable file is still reported as
                // `other`, a size/extension/budget miss is `deferred` — a
                // budget miss is NOT a defect (the reference leg still
                // renders), so it is counted, never warned.
                embedImage(p, budget) match
                  case Right(dataUri) =>
                    inlined += 1
                    Some(dataUri)
                  case Left(InlineSkip.Unreadable(detail)) =>
                    rejects += RejectedRef(src, Some(describe(p)), FileRefFailure.Other, detail)
                    None
                  case Left(_) =>
                    deferred += 1
                    None
              case RefDecision.Reject(rejected) if FileRefs.inlineMayTakeOver(p, rejected) =>
                // 返工 r2 (2026-09-18, 复核位 F1): the endpoint refuses this
                // reference for its REACH layer only, and the file's identity is
                // not a credential — the shipped Canvas pass embedded such
                // images (the /api/nf-file URL was the unretrievable part, not
                // the bytes), and the author's order is "let local files
                // succeed more often". Embed it, and never count it `deferred`:
                // there is no fetch this reference could win.
                embedImage(p, budget) match
                  case Right(dataUri) =>
                    inlined += 1
                    Some(dataUri)
                  case Left(_) =>
                    // Not embeddable / past the budget / unreadable: nothing can
                    // render it, so the endpoint's refusal stands and is
                    // reported with its fix hint (never a silent `deferred`).
                    rejects += rejected
                    None
              case other =>
                record(other)
                None

    val out = ImgSrcPattern.replaceAllIn(html, { m =>
      val src = m.group(1)
      newValueFor(src) match
        case Some(value) =>
          // Replace only the src value: the regex guarantees group(1) is
          // immediately before the closing quote at the end of the match.
          val full = m.group(0)
          full.dropRight(src.length + 1) + value + full.takeRight(1)
        case None => m.group(0)
    })

    if rejects.nonEmpty || deferred > 0 || exempt > 0 then
      logger.debug(
        s"Pop: ${rejects.size} image reference(s) not inlined (deferred=$deferred exempt=$exempt)"
      )
    PopRefOutcome(out, inlined, deferred, exempt, rejects.toList, notes.toList.distinct)
  end processLocalImages

  /** The counters + warning list for one Pop pass — the same objects Card puts
    *  in its payload, so both tools report identically shaped warnings. */
  private def refPayload(o: PopRefOutcome): (Json, Json) =
    val distinct = distinctRejections(o.rejects)
    val listed = distinct.take(MaxListedWarnings)
    (
      fileRefsJson(o.inlined, distinct.size, distinct.size - listed.size, o.exempt, List("deferred" -> o.deferred)),
      warningsJson(listed)
    )

  /** Tool result text: the summary line, then — only when something needs
    *  attention — the warnings array and the `fileRefs` counter line. A clean
    *  Pop is byte-identical to the pre-batch result. */
  private def describeResult(summary: String, o: PopRefOutcome, fileRefs: Json, warnings: Json): String =
    val failed = fileRefs.hcursor.get[Int]("failed").toOption.getOrElse(0)
    val sb = new StringBuilder(summary)
    if failed > 0 then
      sb.append("\n")
        .append(
          s"$failed local image reference(s) could NOT be inlined, and the Canvas fallback (/api/nf-file) cannot serve them either:"
        )
        .append("\nwarnings: ")
        .append(warnings.noSpaces)
    if failed > 0 || o.deferred > 0 || o.exempt > 0 then
      sb.append("\n").append(CountsMarker).append(fileRefs.noSpaces)
    // imgref batch: a decoded-form hit is DISCLOSED even on an otherwise clean
    // pass — the author's order is explicit that the tool result must say which
    // form of the path was used ("路径含空格，已自动改用解码形态").
    if o.notes.nonEmpty then
      sb.append("\nnotes: ").append(o.notes.mkString(" | "))
    sb.toString

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

Pop is Nebula-exclusive: only the Nebula root session may call it. Every other agent, node session, or sub-agent call is rejected with POP_NEBULA_ONLY. Nodes do not Pop — they hand the deliverable to the chain end / Nebula along the out edge, and Nebula decides whether it is shown. Pop cannot be granted back to any other identity (it left the builtin tool whitelist).

## When to use

- Nebula decides a finished result should be shown to the user in Canvas.
- The user asks to "open" or "show" a file.
- A node handed over a deliverable along the out edge and showing it is warranted.
- You want to show a web page (e.g. a deployed site, documentation) to the user.

The Canvas tab supports the same file types as the file explorer. The tab title defaults to the filename (or hostname for URLs); provide `title` to customize it.

## Image references and inlining

Local `<img src>` values that exist, are embeddable image formats and are ≤5MB are inlined as base64 data URIs, so the Canvas iframe renders them with no extra request. The same rule applies to an image you open DIRECTLY (`filePath` = a `png`/`jpg`/`jpeg`/`gif`/`webp`/`svg`/`bmp` ≤5MB): its bytes ride in the pop payload and the image viewer renders them with no request. Inlining is additionally capped in TOTAL per call — at most 40,000 characters of `data:` URI (≈30 KB of source bytes, counted in the order the images appear) go inline, and anything past that total keeps its `/api/nf-file` reference instead. Images outside the 5MB rule, images past that total, and every non-image asset are fetched by the frontend through `/api/nf-file`, which needs a per-path ticket the gateway mints only for paths its credential-namespace policy serves — the data directory serves `projects/**, uploads/**, plots/**, workspace-items/**, voice-models/**, docs/**` and the project `.nebflow/` serves `evidence*/**`. To show such a file, put it under one of the served locations above — `projects/**` is the usual route, but not the only one: a path outside the data directory and the project `.nebflow/` stays servable where it is (an absolute `/tmp/output.svg` renders), as long as it is not credential-shaped. Every local reference that could NOT be inlined is reported in this tool's result — `warnings` (`ref` → `resolvedPath` → `reason`: not-found / unresolvable / extension-not-allowed / size-exceeded / not-regular-file / not-readable / not-servable / other) plus a `fileRefs` counter line — and the same list is shown above the Canvas tab. References the Canvas can still serve through /api/nf-file (larger images, formats outside the inline set) are only counted (`fileRefs.deferred`); the app's own routes (`/js/…`, `/css/…`, `/assets/…`, `/logo.svg` …) are counted as `fileRefs.exempt`. Read `warnings` and fix the references before finishing. A path containing spaces is fine and needs no special spelling: write it as it is on disk (a bare `+` in a URL's `path=` parameter is read as a space, and `%20` also works); a `notes:` line in this result reports any reference that was resolved in its decoded form.

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
    * MemoryNoteTool DREAM_APPEND_DENIED）。 */
  private val NebulaOnlyError: ToolError = ToolError(
    "Pop: permission denied — Pop 已收归 Nebula 专属；交付物请沿 out 边交给链末端 / Nebula，由 Nebula 决定是否展示 (POP_NEBULA_ONLY)")

  /** 身份闸判据（2026-09-10 作者裁定）：两层同真才放行。
    *
    *  1. `ctx.agentDef.exists(_.name == "Nebula")` —— 身份来源 = ctx.agentDef
    *     （AgentCore toolCtx 构造处注入 effectiveDef）；
    *     MemoryNoteTool.scala:325-338 的 dream 闸先例同款，禁用全局状态猜身份。
    *  2. `ctx.depth == 0` —— 「Nebula 本体根会话」判据（depth==0 排除
    *     NodeDef.agent="Nebula" 的节点会话，它们的 depth=1）。子会话判定口径 =
    *     depth：Nebula 派生的 SubTask worker / 节点会话 / 子 agent 全部 depth≥1；
    *     depth==0 只有全仓唯一根会话 spawn 点（WebSocketRoutes.doSpawnRootAgent）
    *     ——「Nebula 自己」与「Nebula 派生的会话」由此分开。
    *
    * `ctx.agentDef == None`（REST 直调 / spec harness）→ **fail-closed**：非
    * Nebula 身份一律拒。实测无合法非 agent 调用面被误伤：Pop 不在
    * RemoteExecutor.remoteableTools（RemoteExecutor.scala:658 只有
    * Bash/Read/Write/Edit/Glob/Grep）⇒ 远程执行链永不带 Pop；remote-exec 接收侧
    * （RestApiRoutes.scala:1154-1158 / NeblinkRelayTunnel.scala:275）不传
    * agentDef 也不传 wsSend，那里的 Pop 本来只回声、不发送、无功能面。spec
    * harness 显式传 Nebula ctx（PopToolSpec.captureCtx）。
    *
    * **工具面按角色分化批（2026-09-13）**：谓词本体已上移为全仓唯一单点
    * [[nebflow.agent.AgentCore.isNebulaRoot]]（同批新增：定义期 schema 分组的
    * 分组依据 + AskUserQuestion 非阻塞兜底闸——三消费点一处实现）。本方法退化为
    * **纯委托**（行为逐字节不变，PopToolSpec 钉住）；此处**不得**重写
    * `name=="Nebula" && depth==0`（可判红：`AskUserDualModeSpec` 的 grep 级静态断言）。
    */
  private def isNebulaRootSession(ctx: ToolContext): Boolean =
    nebflow.agent.AgentCore.isNebulaRoot(ctx.agentDef, ctx.depth)

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
              // `refs` carries what happened to every local reference that was
              // NOT inlined (toolfail batch, 2026-09-11) — it feeds both the
              // tool result and the `popFile` item the Canvas viewer renders.
              val htmlDir = Option(path.getParent).getOrElse(Paths.get("."))
              val refs: Option[PopRefOutcome] =
                if itemType == "html" && rawContent.nonEmpty then Some(processLocalImages(rawContent, htmlDir))
                else None
              val content = refs.map(_.html).getOrElse(rawContent)

              // Directly-opened image (2026-09-16 imgfix batch): the Canvas image
              // viewer used to fetch the bytes through /api/nf-file, whose
              // per-path ticket the gateway mints only for paths its credential-
              // namespace policy serves — a PNG under `<dataRoot>/docs/**` was
              // refused, so the viewer got a credential-free URL, a 401, and
              // showed its "File may be corrupted or not a valid image format."
              // panel (author report: sha256 f94d0e04…, 512479 B, file intact).
              // The bytes ride in the payload instead (same rule as the HTML
              // `<img>` pass above, one definition in `FileRefs`), and the
              // viewer's existing `objectUrl` leg renders them with NO request.
              // An image outside the per-image policy — or one that does not fit
              // this call's cumulative inline budget (`fileRefs`-level rule:
              // 40,000 chars, `FileRefs.MaxInlinePayloadChars`, img-ticket batch
              // i / #687-C) — keeps metadata-only and is still fetched through
              // the ticket leg (which now serves `<dataRoot>/docs/**` too, so the
              // 2026-09-16 author case above renders either way).
              // A FRESH budget: the HTML `<img>` pass and this face are mutually
              // exclusive per call, so neither can spend the other's balance.
              val inlineImage: Option[String] =
                if entry.binary then embedImage(path, InlineBudget()).toOption else None

              // The refs keys are added only for HTML items (the only ones the
              // pass runs on) — a markdown Pop keeps its item shape unchanged.
              val refFields: List[(String, Json)] = refs match
                case Some(o) =>
                  val (counters, warnings) = refPayload(o)
                  List("fileRefs" -> counters, "warnings" -> warnings)
                case None => Nil

              // Build the WS message — dispatched as 'popFile' type. The two
              // extra keys carry the SAME shapes Card ships in its payload, so
              // the Canvas HTML viewer can render them with the same notice
              // component the chat uses.
              val msg = Json.obj(
                "type" -> "popFile".asJson,
                "item" -> Json.fromFields(
                  List(
                    "id" -> s"file:${path.toString}".asJson,
                    "itemType" -> itemType.asJson,
                    "title" -> tabTitle.asJson,
                    "content" -> content.asJson,
                    "absPath" -> path.toString.asJson,
                    "size" -> size.asJson,
                    "pinned" -> true.asJson
                  ) ++ inlineImage.map(u => "objectUrl" -> u.asJson).toList ++ refFields
                )
              )

              Right((msg, tabTitle, refs))
          }.flatMap {
            case Left(err) => IO.pure(Left(err))
            case Right((msg, tabTitle, refs)) =>
              val sendIO = ctx.wsSend.getOrElse((_: Json) => IO.unit)
              val summary = s"Opened $tabTitle in Canvas."
              val result = refs match
                case Some(o) =>
                  val (fileRefsJsonValue, warningsJsonValue) = refPayload(o)
                  describeResult(summary, o, fileRefsJsonValue, warningsJsonValue)
                case None => summary
              sendIO(msg) >> IO.pure(Right(result))
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
    // Visibility (toolfail batch, mirrors Card's summarizeResult): a Pop whose
    // local images were dropped used to look like a clean `Opened X in Canvas`.
    val failed = failedIn(result)
    if failed > 0 then s"Opened $fileName in Canvas — $failed image reference(s) NOT inlined"
    else s"Opened $fileName in Canvas"

end PopTool
