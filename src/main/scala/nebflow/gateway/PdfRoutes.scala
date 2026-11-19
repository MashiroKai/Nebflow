/* PDF note/ask 批（2026-11-18）：PDF 页锚备注 + 提问取页域。
 *
 * 端点（全部经 `withAuth`，与 /api 域现行会话 rail 一致）：
 *   POST /api/pdf/open     —— 打开/预览语义。**永不解析**：只回内容摘要（sha256）、
 *                             缓存命中与否、以及该文档已有备注页。前端打开 PDF 走这里。
 *   GET  /api/pdf/note     —— 读一页的备注（+ 页界判定，若已有 manifest）。
 *   POST /api/pdf/note     —— 写/改一页的备注（条件写；**永不解析**）。
 *   POST /api/pdf/ask      —— **唯一**触发解析的入口 = 发一条提问消息。异步起解析，
 *                             立即回 `parsing`；解析完成后再问一次即回 `ready`
 *                             （含引用页码与逐页摘录，永不给整篇）。
 *   GET  /api/pdf/page     —— 按需读缓存里某一页的 Markdown（不解析）。
 *
 * 懒加载的判据面（可断言、可取证）：`open` / `note` / `page` 三条腿**都没有**
 * 到 [[nebflow.core.pdf.PdfCache.ensure]] 的路径；只有 `ask` 调用它。缓存目录
 * `<dataRoot>/pdf-cache` 在一次「打开 + 写备注」之后必须仍然为空，在 `ask`
 * 之后才出现条目 —— 这就是「打开不解析」可复算的证据。
 */
package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.pdf.{PdfAsk, PdfCache, PdfManifest, PdfNotes, PdfParser}
import nebflow.shared.NebflowLogger
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

import scala.concurrent.duration.*

private[gateway] object PdfRoutes:

  private val logger = NebflowLogger.forName("nebflow.pdf.routes")

  /**
   * How long an ask response waits for a first-parse to finish before answering
   * `202 parsing`. A small budget keeps a large PDF from blocking the ask message
   * (parsing is background work by contract) while still answering immediately in
   * the common case of a document that is already cached or tiny.
   */
  val AskHeadStartMs: Long = 1500

  /** Body -> `(path, sha256)`, either of which may name the document. */
  private def docOf(body: Json): (Option[String], Option[String]) =
    val path = body.hcursor.downField("path").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    val sha = body.hcursor.downField("sha256").as[String].toOption.map(_.trim).filter(_.nonEmpty)
    (path, sha)

  private def textOf(body: Json, field: String): String =
    body.hcursor.downField(field).as[String].toOption.getOrElse("")

  private def badRequest(message: String): IO[Response[IO]] =
    BadRequest(Json.obj("error" -> message.asJson))

  /**
   * Resolve the document's manifest WITHOUT parsing: a digest is enough; a path
   * costs one streaming sha256 (the same identity the parser would compute) and
   * then a cache lookup. A miss is reported as a miss — never by parsing.
   */
  private def manifestFor(cache: PdfCache, path: Option[String], sha: Option[String]): IO[Either[String, (String, Option[PdfManifest])]] =
    (path, sha) match
      case (_, Some(s)) if PdfNotes.validDigest(s) =>
        cache.lookup(s).map(m => Right((s, m)))
      case (_, Some(s)) => IO.pure(Left(s"sha256 must be 64 lowercase hex characters, got `${s.take(20)}`"))
      case (Some(p), None) =>
        val file = os.Path(p, os.pwd)
        if !os.exists(file) then IO.pure(Left(s"no such file: $p"))
        else
          PdfParser.sha256OfFile(file).flatMap(s => cache.lookup(s).map(m => Right((s, m))))
      case (None, None) => IO.pure(Left("either `path` or `sha256` is required"))

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      // ── open / preview: identity and notes, never a parse ────────────────
      case req @ POST -> Root / "pdf" / "open" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val (path, sha) = docOf(body)
            val cache = PdfCache.sharedForDataRoot(nebflow.shared.PathUtil.dataRoot)
            manifestFor(cache, path, sha).flatMap {
              case Left(err) => badRequest(err)
              case Right((digest, manifest)) =>
                // `parsed=false` is the headline fact: opening a PDF the instance
                // has never been asked about must not have started anything.
                PdfNotes
                  .pages(nebflow.shared.PathUtil.dataRoot, digest)
                  .flatMap { notePages =>
                    Ok(
                      Json.obj(
                        "state" -> "open".asJson,
                        "sha256" -> digest.asJson,
                        "parsed" -> manifest.isDefined.asJson,
                        "manifest" -> manifest.map(_.summaryJson).getOrElse(Json.Null),
                        "note_pages" -> notePages.asJson,
                        "notes_available" -> notePages.nonEmpty.asJson
                      )
                    )
                  }
            }
          }
        }

      // ── read one page's note ────────────────────────────────────────────
      case req @ GET -> Root / "pdf" / "note" =>
        withAuth(req) {
          val q = req.uri.query.params
          val cache = PdfCache.sharedForDataRoot(nebflow.shared.PathUtil.dataRoot)
          manifestFor(cache, q.get("path"), q.get("sha256")).flatMap {
            case Left(err) => badRequest(err)
            case Right((digest, manifest)) =>
              q.get("page").flatMap(_.toIntOption) match
                case None => badRequest("`page` (1-based integer) is required")
                case Some(page) if page < 1 => badRequest(s"`page` must be >= 1, got $page")
                case Some(page) =>
                  PdfNotes.json(nebflow.shared.PathUtil.dataRoot, digest, page, manifest).flatMap(Ok(_))
          }
        }

      // ── write one page's note (never parses) ────────────────────────────
      case req @ POST -> Root / "pdf" / "note" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val (path, sha) = docOf(body)
            val cache = PdfCache.sharedForDataRoot(nebflow.shared.PathUtil.dataRoot)
            val page = body.hcursor.downField("page").as[Int].toOption
            val expected = body.hcursor.downField("expected_version").as[Long].toOption
            manifestFor(cache, path, sha).flatMap {
              case Left(err) => badRequest(err)
              case Right((digest, manifest)) =>
                page match
                  case None => badRequest("`page` (1-based integer) is required")
                  case Some(p) =>
                    PdfNotes
                      .put(
                        nebflow.shared.PathUtil.dataRoot,
                        digest,
                        p,
                        textOf(body, "text"),
                        textOf(body, "snippet"),
                        expected
                      )
                      .flatMap {
                        case PdfNotes.WriteResult.Ok(v) =>
                          PdfNotes.json(nebflow.shared.PathUtil.dataRoot, digest, p, manifest).map(_.deepMerge(Json.obj("state" -> "ok".asJson, "version" -> v.asJson))).flatMap(Ok(_))
                        case PdfNotes.WriteResult.TooLarge(limit, field) =>
                          badRequest(s"`$field` exceeds $limit UTF-8 bytes")
                        case PdfNotes.WriteResult.BadDigest(s) => badRequest(s"invalid sha256: $s")
                        case PdfNotes.WriteResult.BadPage(p)   => badRequest(s"invalid page: $p")
                        case PdfNotes.WriteResult.Conflict(current) =>
                          Conflict(
                            Json.obj(
                              "state" -> "conflict".asJson,
                              "error" -> s"the note changed since it was read (current version $current) — reload before saving".asJson,
                              "current_version" -> current.asJson
                            )
                          )
                        case PdfNotes.WriteResult.Failed(msg) => InternalServerError(Json.obj("state" -> "failed".asJson, "error" -> msg.asJson))
                      }
            }
          }
        }

      // ── the ask: the ONLY entry point that may parse ────────────────────
      case req @ POST -> Root / "pdf" / "ask" =>
        withAuth(req) {
          req.as[Json].flatMap { body =>
            val (path, sha) = docOf(body)
            val question = textOf(body, "question")
            val cache = PdfCache.sharedForDataRoot(nebflow.shared.PathUtil.dataRoot)
            val dataRoot = nebflow.shared.PathUtil.dataRoot
            (path, sha) match
              case (_, None) if path.isEmpty => badRequest("either `path` or `sha256` is required to ask about a PDF")
              case (Some(p), _) =>
                val file = os.Path(p, os.pwd)
                if !os.exists(file) then badRequest(s"no such file: $p")
                else askAsync(cache, dataRoot, file, Option(p).map(f => os.Path(f, os.pwd).last).getOrElse("document.pdf"), question)
              case _ =>
                // Asking by digest alone is only answerable when the entry (and
                // therefore the source) is already there: without a path there is
                // nothing to parse. Explicit 409 instead of a silent no-op.
                cache.lookup(sha.get).flatMap {
                  case Some(_) =>
                    InternalServerError(
                      Json.obj("error" -> "a cache entry exists but no source path was supplied — send `path` so the ask can be answered".asJson)
                    )
                  case None =>
                    Conflict(
                      Json.obj(
                        "state" -> "needs_path".asJson,
                        "error" -> "this document has not been parsed yet and no `path` was supplied, so nothing can be parsed".asJson
                      )
                    )
                }
          }
        }

      // ── on-demand page read (never parses) ──────────────────────────────
      case req @ GET -> Root / "pdf" / "page" =>
        withAuth(req) {
          val q = req.uri.query.params
          val cache = PdfCache.sharedForDataRoot(nebflow.shared.PathUtil.dataRoot)
          manifestFor(cache, q.get("path"), q.get("sha256")).flatMap {
            case Left(err) => badRequest(err)
            case Right((digest, None)) =>
              NotFound(Json.obj("state" -> "not_parsed".asJson, "sha256" -> digest.asJson, "error" -> "no cache entry for this document (send an ask to parse it)".asJson))
            case Right((digest, Some(manifest))) =>
              q.get("page").flatMap(_.toIntOption) match
                case None => badRequest("`page` (1-based integer) is required")
                case Some(page) if page < 1 || page > manifest.pageCount =>
                  NotFound(Json.obj("error" -> s"page $page is outside 1..${manifest.pageCount}".asJson))
                case Some(page) =>
                  cache.readPage(digest, page).flatMap {
                    case Left(err) => NotFound(Json.obj("error" -> err.asJson))
                    case Right(md) =>
                      Ok(
                        Json.obj(
                          "state" -> "ok".asJson,
                          "sha256" -> digest.asJson,
                          "page" -> page.asJson,
                          "citation" -> s"p.$page".asJson,
                          "markdown" -> md.asJson
                        )
                      )
                  }
          }
        }
    }

  /**
   * Start (or join) the parse for one document and answer the ask.
   *
   * First call for a document: the parse runs in the BACKGROUND and the response
   * is `202 parsing` — an ask message must not block on a big PDF. Later calls
   * (same ask retried, or a second ask) find the entry and answer with cited
   * excerpts. The join case (a concurrent ask) waits on the shared parse instead
   * of starting a second one.
   */
  private def askAsync(
    cache: PdfCache,
    dataRoot: os.Path,
    file: os.Path,
    displayName: String,
    question: String
  ): IO[Response[IO]] =
    IO.defer {
      for
        sha <- PdfParser.sha256OfFile(file)
        cached <- cache.lookup(sha)
        response <- cached match
          case Some(_) => answer(cache, dataRoot, file, displayName, question)
          case None =>
            PdfAsk
              .ask(cache, dataRoot, file, displayName, question)
              .start
              .flatMap { fiber =>
                IO.race(fiber.joinWithNever, IO.sleep(PdfRoutes.AskHeadStartMs.millis))
              }
              .flatMap {
                case Left(verdict) => answerFrom(verdict, sha, question)
                case Right(()) =>
                  logger.infoSync(s"pdf-ask parsing in background sha=${sha.take(12)}")
                  Accepted(
                    Json.obj(
                      "state" -> "parsing".asJson,
                      "sha256" -> sha.asJson,
                      "question" -> question.asJson,
                      "message" -> "the document is being parsed in the background; ask again (or retry) for cited pages".asJson
                    )
                  )
              }
      yield response
    }

  private def answer(cache: PdfCache, dataRoot: os.Path, file: os.Path, displayName: String, question: String): IO[Response[IO]] =
    PdfAsk.ask(cache, dataRoot, file, displayName, question).flatMap { r =>
      r match
        case Right(json) => Ok(json)
        case Left(f)     => UnprocessableEntity(Json.obj("state" -> "failed".asJson, "error" -> f.code.asJson, "message" -> f.message.asJson))
    }

  private def answerFrom(
    verdict: Either[PdfParser.PdfFailure, Json],
    sha: String,
    question: String
  ): IO[Response[IO]] =
    verdict match
      case Right(json) => Ok(json)
      case Left(f) =>
        UnprocessableEntity(
          Json.obj(
            "state" -> "failed".asJson,
            "sha256" -> sha.asJson,
            "question" -> question.asJson,
            "error" -> f.code.asJson,
            "message" -> f.message.asJson
          )
        )
