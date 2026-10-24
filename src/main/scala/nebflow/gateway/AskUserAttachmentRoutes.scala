/* askuser-dragdrop 实施批（2026-10-19，批 #551）：AskUser 卡片作用域的**答复附件上传域**。
 *
 * 端点（设计稿 §3.1，作者批样张 + 三修正后实施）：
 *   POST /api/askuser/attachments?requestId=asknb-xxxx  (multipart/form-data，单文件单请求)
 *
 * 闸（顺序即实现序，fail-closed）：
 *   ① GatewayAuth 会话 cookie（与 /api 域现行 rail 一致，先例 AskUserCancelRoutes）；
 *   ② hub `AttachGateOf`：卡必须在册且 kind=AskUser（否则 410 ASKUSER_CARD_NOT_LIVE）、
 *      卡不得携带 secret 题（否则 403 ASKUSER_ATTACH_SECRET_CARD —— 防凭据「另存为
 *      文件」旁路落盘；前端本就无入口，此处是纵深防御）；
 *   ③ 计数：每卡累计 ≤9（与答案侧终验同一把尺：`AttachContract.MaxAttachmentsPerMessage`）；
 *      并发 ≤3（超出 429 ASKUSER_ATTACH_BUSY）；
 *   ④ 尺寸：Content-Length 预检 + 流中逐块累计 ≤100MB
 *      （`AskUserQuestionTool.MaxAttachmentBytes` 同值常量，双向同源防口径漂移）。
 *
 * 落盘：`<dataRoot>/uploads/<rootSessionId>/ask-<requestId>/<seq>-<清洗后文件名>`
 * （在既有 uploads 基座 `StaticRoutes.scala` 之下分层；回读 = 既有 `GET /uploads/...`
 * 路由，本批随之扩段）。sha256 由服务端流式单遍计算；同卡同 sha ⇒ 去重（返回
 * 既有 path 不重复落盘，计数照加防刷）。
 *
 * 孤儿清扫（设计稿 §3.6「无新守护进程」）：惰性——每次上传后顺手扫一遍
 * `uploads/<sid>/ask-<rid>` 目录族：无 `.answered` 标记且目录 mtime 落后 >24h 的
 * 整目录删除（未被任何作答行引用的孤儿；已作答卡的文件带标记永久保留，随
 * .ui.json 引用）。24h = 设计稿推荐口径。
 */
package nebflow.gateway

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.multipart.{Multipart, Part}
import nebflow.actor.InteractionHubCommand
import nebflow.shared.{AttachContract, NebflowLogger, PathUtil}

import java.security.MessageDigest

private[gateway] object AskUserAttachmentRoutes:

  private val logger = NebflowLogger.forName("nebflow.ws")

  /** 单件上限（与工具出向面同值常量——设计稿 §3.6「上限尺共享」）。 */
  val MaxBytes: Long = nebflow.core.tools.AskUserQuestionTool.MaxAttachmentBytes

  /** 每卡累计上限（全仓单一数值权威面）。 */
  val MaxPerCard: Int = AttachContract.MaxAttachmentsPerMessage

  /** 单卡并发上传上限（超出 429；设计稿 §3.6）。 */
  val MaxConcurrentPerCard: Int = 3

  /** 孤儿清扫阈值（设计稿 §3.6 推荐口径：24h）。 */
  val OrphanTtlMs: Long = 24L * 60 * 60 * 1000

  /** 已作答卡的落盘标记文件名（存在 = 本卡的文件被某条作答行引用，禁清扫）。 */
  val AnsweredMarker = ".answered"

  /** 一件已受理附件的服务端记录（答案侧终验的 sha 比对源）。 */
  final case class Rec(path: String, seq: Int, size: Long, mime: String, sha256: String)

  /**
   * 每卡的受理台账（内存态：重启清零 = 去重/计数降级为宽松，不作安全闸——
   * 答案侧终验按路径前缀 + 真实文件状态独立把关）。
   */
  final case class CardEntry(count: Int, inFlight: Int, files: Map[String, Rec])

  object Store:
    import cats.effect.Ref
    val registry: Ref[IO, Map[String, CardEntry]] = Ref.unsafe(Map.empty)

    /** 目录名 = ask-<requestId>（单一拼接点）。 */
    def dirName(requestId: String): String = s"ask-$requestId"

    def dirOf(rootSessionId: String, requestId: String): os.Path =
      PathUtil.dataRoot / "uploads" / rootSessionId / dirName(requestId)

    /** 文件名清洗（设计稿 §3.2：去路径分隔符与控制字符，保留 unicode）。 */
    def sanitizeName(raw: String): String =
      val cleaned = raw.replaceAll("[/\\\\]", "_").replace("..", "_").replaceAll("[\\p{Cntrl}]", "")
      val trimmed = cleaned.trim
      if trimmed.isEmpty then "file" else trimmed

    /** seq 命名：`<seq>-<sanitized>`（seq 1 起，按受理台账递增）。 */
    def fileNameFor(seq: Int, sanitized: String): String = s"$seq-$sanitized"

    /** 出向记录行（.ui.json / 桥注入 / 前端 chips 同一形状族：path 必给，其余自描述）。 */
    def recJson(requestId: String, rec: Rec, name: String): Json =
      Json.obj(
        "id" -> s"att-$requestId-${rec.seq}".asJson,
        "seq" -> rec.seq.asJson,
        "name" -> name.asJson,
        "path" -> rec.path.asJson,
        "sha256" -> rec.sha256.asJson,
        "size" -> rec.size.asJson,
        "mime" -> rec.mime.asJson,
        "kind" -> (if rec.mime.startsWith("image/") then "image" else "file").asJson
      )
  end Store

  // ===== 闸与台账推进的纯判据（spec 直取；route 是薄壳）=====

  /** 计数闸（纯）：满 ⇒ Left。 */
  def countGate(entry: CardEntry): Either[String, Unit] =
    if entry.count >= MaxPerCard then Left("ASKUSER_ATTACH_TOO_MANY") else Right(())

  /** 并发闸（纯）：满 ⇒ Left。 */
  def concurrencyGate(entry: CardEntry): Either[String, Unit] =
    if entry.inFlight >= MaxConcurrentPerCard then Left("ASKUSER_ATTACH_BUSY") else Right(())

  /** 尺寸闸（纯）：超 ⇒ Left。 */
  def sizeGate(bytes: Long): Either[String, Unit] =
    if bytes > MaxBytes then Left("ASKUSER_ATTACH_TOO_LARGE") else Right(())

  /** 受理一件后的台账推进：count 恒增（去重也算数，防刷）；sha 索引只在**新落盘**时更新。 */
  def accept(entry: CardEntry, sha: String, rec: Rec): CardEntry =
    entry.copy(count = entry.count + 1, files = entry.files + (sha -> rec))

  // ===== 孤儿清扫（惰性，上传后顺手）=====

  /**
   * 扫一遍 `uploads/<sid>/ask-<rid>` 目录族：无 `.answered` 标记且 mtime 落后
   * >24h 的整目录删除。纯 best-effort：任何异常只留 WARN。
   * 返回删除目录数（诊断/测试读数）。
   */
  def sweepOrphans(nowMs: Long = System.currentTimeMillis()): IO[Int] =
    IO.blocking {
      val uploadsBase = PathUtil.dataRoot / "uploads"
      if !os.isDir(uploadsBase) then 0
      else
        var removed = 0
        val sidDirs =
          try os.list(uploadsBase).filter(os.isDir).toList
          catch case _: Throwable => Nil
        sidDirs.foreach { sidDir =>
          val askDirs =
            try os.list(sidDir).filter(p => os.isDir(p) && p.last.startsWith("ask-")).toList
            catch case _: Throwable => Nil
          askDirs.foreach { d =>
            val stale =
              try !os.exists(d / AnsweredMarker) && (nowMs - os.mtime(d)) > OrphanTtlMs
              catch case _: Throwable => false
            if stale then
              try
                os.remove.all(d)
                removed += 1
              catch case _: Throwable => ()
          }
        }
        removed
      end if
    }.handleErrorWith { e =>
      logger.warn(s"ask-attachment orphan sweep failed: ${e.getMessage}").as(0)
    }

  /** 落 `.answered` 标记（作答侧在附件终验通过后调用；best-effort）。 */
  def markAnswered(rootSessionId: String, requestId: String): IO[Unit] =
    IO.blocking {
      val d = Store.dirOf(rootSessionId, requestId)
      os.makeDir.all(d)
      os.write.over(d / AnsweredMarker, Array.emptyByteArray)
    }.handleErrorWith(e => logger.warn(s"ask-attachment answered-marker write failed: ${e.getMessage}").void)

  // ===== 流式接收：逐块计数 + 单遍 sha256 + 落盘临时件 =====

  private final case class AttachmentTooLarge() extends RuntimeException("attachment exceeds per-file limit")

  /** 流上限 / IO 失败 ⇒ Left(错误码)；成功 ⇒ Right((size, sha256))。临时件由调用方落位/清理。 */
  private def receiveTo(body: fs2.Stream[IO, Byte], target: os.Path): IO[Either[String, (Long, String)]] =
    Resource
      .make(IO(java.io.FileOutputStream(target.toString)))(fos => IO(fos.close()).handleError(_ => ()))
      .use { fos =>
        val md = MessageDigest.getInstance("SHA-256")
        body.chunks
          .evalMapAccumulate(0L) { (n: Long, chunk: fs2.Chunk[Byte]) =>
            IO {
              val next = n + chunk.size
              if next > MaxBytes then throw AttachmentTooLarge()
              val bytes = chunk.toArray[Byte]
              fos.write(bytes)
              md.update(bytes)
              (next, next) // O2 = 累计字节数（compile.last 取终值）
            }
          }
          .compile
          .last
          .map(count => Right((count.map(_._2).getOrElse(0L), md.digest().map("%02x".format(_)).mkString)))
          .handleErrorWith {
            case _: AttachmentTooLarge => IO.pure(Left("ASKUSER_ATTACH_TOO_LARGE"))
            case e =>
              logger.warn(s"ask-attachment receive failed: ${e.getMessage}").as(Left("ASKUSER_ATTACH_IO"))
          }
      }

  // ===== 路由 =====

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] { case req @ POST -> Root / "askuser" / "attachments" =>
      withAuth(req) {
        val requestId = req.params.getOrElse("requestId", "").trim
        if requestId.isEmpty then
          badRequest("ASKUSER_BAD_REQUEST", "requestId is required (the live pending asknb- id)")
        else
          // ② hub 闸：live pending AskUser 卡 + 非 secret 卡（fail-closed）。
          sharedResources.interactionHubRef.get.flatMap {
            case None => gone("ASKUSER_CARD_NOT_LIVE", "no interaction hub — card state unknown")
            case Some(hub) =>
              hub
                .?[Option[nebflow.actor.AttachGate]](reply => InteractionHubCommand.AttachGateOf(requestId, reply))
                .flatMap {
                  case None =>
                    gone(
                      "ASKUSER_CARD_NOT_LIVE",
                      s"no pending ask with requestId=$requestId (already answered, closed, or never existed)"
                    )
                  case Some(gate) if gate.hasSecret =>
                    forbidden("ASKUSER_ATTACH_SECRET_CARD", "secret questions never accept attachments (fail-closed)")
                  case Some(gate) =>
                    // Content-Length 预检（流中仍有逐块闸兜底——chunked 上传无长度头）。
                    val declared = req.contentLength.getOrElse(0L)
                    if declared > MaxBytes then tooLarge(declared)
                    else handleUpload(ctx, gate.rootSessionId, requestId, req)
                }
          }
        end if
      }
    }

  end routes

  private def handleUpload(
    ctx: RestApiCtx,
    rootSessionId: String,
    requestId: String,
    req: Request[IO]
  ): IO[Response[IO]] =
    req.decode[Multipart[IO]] { mp =>
      // 单文件单请求：取第一个带 filename 的 part（纯字节体次之），其余忽略。
      mp.parts
        .collectFirst { case p if p.filename.nonEmpty => p }
        .orElse(mp.parts.headOption) match
        case None =>
          badRequest("ASKUSER_BAD_REQUEST", "multipart body with one file part is required")
        case Some(part) =>
          val rawName = part.filename
            .map(_.trim)
            .filter(_.nonEmpty)
            .orElse(req.params.get("name").map(_.trim).filter(_.nonEmpty))
            .getOrElse("file")
          val mime = part.headers.headers
            .find(h => h.name.toString.equalsIgnoreCase("Content-Type"))
            .map(_.value)
            .getOrElse("application/octet-stream")
          val dir = Store.dirOf(rootSessionId, requestId)
          // 并发闸 + inFlight 占位（guarantee 归还）。
          Store.registry
            .modify { m =>
              val entry = m.getOrElse(requestId, CardEntry(0, 0, Map.empty))
              concurrencyGate(entry) match
                case Left(code) => (m, Left(code, entry))
                case Right(_) => (m.updated(requestId, entry.copy(inFlight = entry.inFlight + 1)), Right(entry))
            }
            .flatMap {
              case Left(("ASKUSER_ATTACH_BUSY", _)) => tooBusy()
              case Left((code, _)) => errorJson(Status.Conflict, code, "per-card limit reached")
              case Right(entrySnapshot) =>
                val temp = dir / s".tmp-${System.nanoTime()}"
                val receive =
                  IO.blocking(os.makeDir.all(dir)).handleError(_ => ()) *>
                    receiveTo(part.body, temp)
                receive
                  .flatMap {
                    case Left(code) =>
                      cleanup(temp) *>
                        (if code == "ASKUSER_ATTACH_TOO_LARGE" then tooLarge(0)
                         else errorJson(Status.InternalServerError, code, "upload failed"))
                    case Right((size, sha)) =>
                      settle(ctx, requestId, entrySnapshot, temp, dir, rawName, mime, size, sha)
                  }
                  .guarantee(
                    Store.registry.update { m =>
                      m.get(requestId) match
                        case Some(e) => m.updated(requestId, e.copy(inFlight = math.max(0, e.inFlight - 1)))
                        case None => m
                    }
                  )
            }
    }

  /** 受理落位：计数闸 → sha 去重 → move 落位 → 台账推进 → 惰性清扫 → 响应。 */
  private def settle(
    ctx: RestApiCtx,
    requestId: String,
    entrySnapshot: CardEntry,
    temp: os.Path,
    dir: os.Path,
    rawName: String,
    mime: String,
    size: Long,
    sha: String
  ): IO[Response[IO]] =
    IO.blocking {
      countGate(entrySnapshot) match
        case Left(code) => Left(code, None)
        case Right(_) =>
          entrySnapshot.files.get(sha) match
            case Some(existing) => Right((existing, false)) // 去重：返回既有 path，不重复落盘
            case None =>
              val seq = entrySnapshot.count + 1
              val target = dir / Store.fileNameFor(seq, Store.sanitizeName(rawName))
              os.move(temp, target, replaceExisting = true)
              Right((Rec(target.toString, seq, size, mime, sha), true))
    }.handleErrorWith { e =>
      logger.warn(s"ask-attachment settle failed: ${e.getMessage}").as(Left("ASKUSER_ATTACH_IO", None))
    }.flatMap {
      case Left((code, _)) =>
        cleanup(temp) *>
          (if code == "ASKUSER_ATTACH_TOO_MANY" then
             errorJson(Status.Conflict, code, s"at most $MaxPerCard files per card")
           else errorJson(Status.InternalServerError, code, "upload failed"))
      case Right((rec, isNew)) =>
        Store.registry.update { m =>
          val cur = m.getOrElse(requestId, CardEntry(0, 1, Map.empty))
          m.updated(requestId, accept(cur, rec.sha256, rec))
        } *>
          // 惰性孤儿清扫（后台 fiber，best-effort——绝不拖慢响应）。
          sweepOrphans().start.void *>
          Ok(
            Store
              .recJson(requestId, rec, rawName)
              .deepMerge(
                Json.obj("ok" -> true.asJson, "deduplicated" -> (!isNew).asJson)
              )
          )
    }

  private def cleanup(temp: os.Path): IO[Unit] =
    IO.blocking(if os.exists(temp) then os.remove(temp)).handleError(_ => ())

  // ===== 响应构造（错误码沿用 AskUser 出向面词表，设计稿 §3.1）=====

  private def errorJson(status: Status, code: String, message: String): IO[Response[IO]] =
    IO.pure(
      Response[IO](status).withEntity(
        Json.obj("ok" -> false.asJson, "error" -> code.asJson, "message" -> message.asJson)
      )
    )
  private def badRequest(code: String, message: String): IO[Response[IO]] = errorJson(Status.BadRequest, code, message)
  private def gone(code: String, message: String): IO[Response[IO]] = errorJson(Status.Gone, code, message)
  private def forbidden(code: String, message: String): IO[Response[IO]] = errorJson(Status.Forbidden, code, message)

  private def tooBusy(): IO[Response[IO]] =
    errorJson(
      Status.TooManyRequests,
      "ASKUSER_ATTACH_BUSY",
      s"at most $MaxConcurrentPerCard concurrent uploads per card"
    )

  private def tooLarge(size: Long): IO[Response[IO]] =
    errorJson(
      Status.PayloadTooLarge,
      "ASKUSER_ATTACH_TOO_LARGE",
      s"file exceeds the ${MaxBytes / (1024 * 1024)} MB per-file limit${if size > 0 then s" (got $size bytes)" else ""}"
    )

end AskUserAttachmentRoutes
