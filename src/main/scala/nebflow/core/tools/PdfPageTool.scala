package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.ask.{AskGatewayServices, AskSessions}
import nebflow.core.pdf.{PdfAsk, PdfCache, PdfManifest}
import nebflow.shared.PathUtil

/* ═══════════════════════════════════════════════════════════════════════════
 * PDF note/ask 批（2026-11-18）· ask 专属取页工具（PdfPage）
 *
 * 作者令「让 ask 可以自己按需去读取」的智能体侧执行件：ask 会话绑定 PDF 时，
 * 模型按页号取该页的 Markdown（解析产物），不整篇载入。身份双保险（send_
 * message_to_main_chat 先例）：挂载面 = buildAllowedToolSet 的 ask 早退集
 * （AskSessions.AllowedTools）；工具内侧 = ctx.sessionId 同判据 + 绑定表回查
 * （绑定文件必须是 .pdf）。非 ask 会话 / 未绑定 / 绑定非 PDF 一律拒绝。
 *
 * 解析语义（与 PdfRoutes 的 ask 腿同一份契约）：ensure = 单飞/join——条目已在
 * 则即时返回；askQuestion 已在后台起的解析则本调用 join 同一飞行（不会第二遍
 * 解析）。失败态可读：加密/损坏等以明确错误文案回给模型，不静默。
 * ═══════════════════════════════════════════════════════════════════════════ */
object PdfPageTool extends Tool:

  val Name: String = "PdfPage"

  override def name: String = Name

  override def description: String =
    """Read ONE page of the PDF this ask session is bound to, as Markdown (parsed text/tables; images are referenced by relative link). Input: {"page": <1-based integer>}. Parsing is lazy — it starts with the first question and the first call may wait for it. ALWAYS cite the page number in the answer (e.g. "第 3 页" / "p.3"). Do NOT read the PDF with Read (binary); this tool is the only page source."""

  override def inputSchema: JsonObject = JsonObject(
    "type" -> "object".asJson,
    "properties" -> Json.obj(
      "page" -> Json.obj(
        "type" -> "integer".asJson,
        "description" -> "1-based page number to read. Required.".asJson,
        "minimum" -> 1.asJson
      )
    ),
    "required" -> Json.arr("page".asJson)
  )

  override def summarize(input: JsonObject): String =
    val p = input("page").flatMap(_.asNumber).flatMap(_.toInt).getOrElse("?")
    s"PdfPage(p.$p)"

  override def summarizeResult(input: JsonObject, result: String): String =
    if result.length > 160 then result.take(157) + "..." else result

  override def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sid = ctx.sessionId.getOrElse("")
    val page = input("page").flatMap(_.asNumber).flatMap(_.toInt)

    // 身份双保险之工具内侧：非 ask 会话（含 REST 直调 / 其余一切身份）拒绝。
    if !AskSessions.isAskSession(sid) then
      IO.pure(
        Left(ToolError("PdfPage: permission denied — this tool is exclusive to file-ask sessions bound to a PDF. (PDF_PAGE_FORBIDDEN)"))
      )
    else
      page match
        case None => IO.pure(Left(ToolError("PdfPage: `page` (1-based integer) is required.")))
        case Some(p) if p < 1 => IO.pure(Left(ToolError(s"PdfPage: `page` must be >= 1, got $p.")))
        case Some(p) =>
          // 绑定面回查：本 ask 会话绑定的文件（askOpen 落账；单源 = 绑定表）。
          AskGatewayServices.instance.map(_.registry) match
            case None =>
              IO.pure(Left(ToolError("PdfPage: ask services unavailable on this instance. (PDF_PAGE_FORBIDDEN)")))
            case Some(registry) =>
              registry.byId(sid).flatMap {
                case None =>
                  IO.pure(Left(ToolError(s"PdfPage: no file binding for this session. (PDF_PAGE_FORBIDDEN)")))
                case Some(binding) =>
                  if !AskSessions.isPdf(binding.file) then
                    IO.pure(
                      Left(ToolError(s"PdfPage: the bound file is not a PDF: ${binding.fileName}. (PDF_PAGE_FORBIDDEN)"))
                    )
                  else
                    val file = os.Path(binding.file, os.pwd)
                    if !os.exists(file) then
                      IO.pure(Left(ToolError(s"PdfPage: the bound PDF no longer exists: ${binding.file}.")))
                    else
                      val cache = PdfCache.sharedForDataRoot(PathUtil.dataRoot)
                      // Join-or-instant: the ask message started the parse; this
                      // call joins the same flight (or finds the cached entry).
                      cache.ensure(file, binding.fileName).flatMap {
                        case Left(f) =>
                          IO.pure(
                            Left(ToolError(s"PdfPage: the PDF could not be parsed (${f.code}): ${f.message}. Answer honestly that the document is unreadable."))
                          )
                        case Right(manifest: PdfManifest) =>
                          if p > manifest.pageCount then
                            IO.pure(
                              Left(ToolError(s"PdfPage: page $p is outside 1..${manifest.pageCount} (the document has ${manifest.pageCount} pages)."))
                            )
                          else
                            cache.readPage(manifest.sourceSha256, p).map {
                              case Left(err) =>
                                Left(ToolError(s"PdfPage: the parsed page exists but cannot be read: $err."))
                              case Right(md) =>
                                val (body, truncated) =
                                  if md.length <= PdfAsk.MaxCharsPerPage then (md, false)
                                  else (md.take(PdfAsk.MaxCharsPerPage), true)
                                val head =
                                  s"[PDF ${binding.fileName} · 第 $p 页 / p.$p of ${manifest.pageCount} · sha256 ${manifest.sourceSha256.take(12)}…]" +
                                    (if truncated then s"\n[page truncated at ${PdfAsk.MaxCharsPerPage} characters]" else "")
                                Right(head + "\n" + body)
                            }
                      }
              }
end PdfPageTool
