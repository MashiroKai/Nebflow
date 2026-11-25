package nebflow.gateway

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.workspace.FileTypeRegistry
import nebflow.gateway.ExternalOpenPolicy as P
import nebflow.gateway.ExternalOpenPolicy.Refusal
import nebflow.gateway.NfFilePolicy.*
import nebflow.shared.PathUtil
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.UUID

import scala.jdk.CollectionConverters.*

/**
 * `POST /api/nf-external/open` —— 「把外部文件拉进 canvas 打开」的单一入口
 * （external-open 批，2026-11-19）。
 *
 * 作者令（2026-11-19 18:46）：「再出一个批支持外部文件拉取进 canvas 打开」。
 *
 * ## 两条腿，各自复用既有通路，零新渲染面
 *
 * - `{"path": "/abs/..."}` —— **本机任意可读路径**。判据 = 既有 `pop.readFile`
 *   打开腿的同一套（绝对路径 → 存在 → 普通文件 → 大小闸），**再加**凭据判据
 *   （`NfFilePolicy.nfVerdictForRealLayer`：凭据身份两层 + R2 inode）。回
 *   `absPath`，前端走既有 `workspace-open-item` → `pop.readFile` → viewer 注册表
 *   （无 viewer 类型仍是既有下载兜底，本批零改动）。
 * - `{"url": "https://..."}` —— **公网 URL 拉取**。过 [[ExternalOpenPolicy]] 的
 *   SSRF 闸 → [[ExternalFetch]] 固定地址拉取 → 按内容嗅探类型 → 落盘
 *   `<dataRoot>/external-fetch/<sha256 前 16>/<安全名>` → 回同一形状的 `absPath`，
 *   前端仍是同一条打开通路。
 *
 * ## 凭据红线（本批零放宽，落实方式）
 *
 * 判据本身**不复制**：直接调 `NfFilePolicy.nfVerdictForRealLayer`（nf-file 票据腿
 * 与取字节腿的同一条函数），所以本入口能送的字节集合 ⊆ 现有 nf-file 能送的面。
 * 拒的时候：不读字节（判据在 `stat` 之后、任何 `Files.read` 之前）、不把路径写进
 * 日志（`target=<redacted>`），只回 `403 + X-Nf-Reason`。
 *
 * ## 鉴权
 *
 * 与 `POST /api/nf-ticket` 同一条：`GatewayAuth.authenticate(..., Rails.WsAuth)`
 * （会话 cookie 优先，Bearer 次之）。本入口不引入第二套凭据。
 */
object ExternalOpenRoutes:

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.nf-external")

  /** 可 grep 的判据拒日志（形状与 `nf-file denied:` 同族）。凭据面脱敏。 */
  private[gateway] def denyLogLine(status: Int, reason: String, target: String, remote: String): String =
    val shown = if reason.startsWith("credential") then "<redacted>" else target
    s"nf-external denied: status=$status reason=$reason target=$shown remote=$remote"

  private def plainText(status: Status, body: String): Response[IO] =
    Response[IO](status)
      .putHeaders(`Content-Type`(MediaType.text.plain, Charset.`UTF-8`))
      .withBodyStream(fs2.Stream.emit(body).through(fs2.text.utf8.encode))

  private def refuse(r: Refusal, target: String, req: Request[IO]): IO[Response[IO]] =
    val status = Status.fromInt(r.status).getOrElse(Status.BadRequest)
    logger
      .warn(denyLogLine(status.code, r.reason, target, req.remoteAddr.map(_.toString).getOrElse("unknown")))
      .as(
        plainText(status, s"${r.reason}: ${r.message}")
          .putHeaders(Header.Raw(CIString("X-Nf-Reason"), r.reason))
          .putHeaders(Header.Raw(CIString("Cache-Control"), "no-store"))
      )

  private def credentialRequired: Response[IO] =
    plainText(Status.Forbidden, "Invalid token")
      .putHeaders(Header.Raw(CIString("X-Nf-Reason"), "credential-required"))

  def externalOpenRoutes(
    tokenRef: Ref[IO, String],
    sessions: GatewaySessionStore,
    policy: NfPathPolicy = NfPathPolicy.memoized(),
    fetchImpl: (String, Path) => IO[Either[Refusal, ExternalFetch.Outcome]] = (u, tmp) => ExternalFetch.fetch(u, tmp)
  ): HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "api" / "nf-external" / "open" =>
    tokenRef.get
      .flatMap(t => GatewayAuth.authenticate(req, t, sessions, GatewayAuth.Rails.WsAuth))
      .flatMap {
        case None => IO.pure(credentialRequired)
        case Some(_) =>
          req.as[Json].attempt.flatMap {
            case Left(e) => IO.pure(plainText(Status.BadRequest, s"malformed body: ${e.getMessage}"))
            case Right(body) =>
              val rawPath = body.hcursor.downField("path").as[String].toOption.map(_.trim).filter(_.nonEmpty)
              val rawUrl = body.hcursor.downField("url").as[String].toOption.map(_.trim).filter(_.nonEmpty)
              (rawPath, rawUrl) match
                case (Some(p), None) => localLeg(p, policy, req)
                case (None, Some(u)) => urlLeg(u, req, fetchImpl)
                case _ =>
                  IO.pure(plainText(Status.BadRequest, "exactly one of 'path' / 'url' is required"))
          }
      }
  }

  // ────────────────────────────────────────────────────────────────────────
  // 本机腿
  // ────────────────────────────────────────────────────────────────────────

  private def localLeg(rawPath: String, policy: NfPathPolicy, req: Request[IO]): IO[Response[IO]] =
    val probed: IO[Either[Refusal, java.nio.file.Path]] = IO.blocking {
      val expanded = PathUtil.expandTilde(rawPath)
      if !PathUtil.isAbsolute(expanded) then Left(Refusal(400, "not-absolute", "path must be absolute"))
      else
        val lexical = PathUtil.resolvePath(expanded).toNIO
        if !Files.exists(lexical) || !Files.isRegularFile(lexical) then
          Left(Refusal(404, "not-found", s"no regular file at $expanded"))
        else
          try Right(lexical.toRealPath())
          catch case e: Throwable => Left(Refusal(404, "not-found", s"path could not be resolved: ${e.getMessage}"))
    }
    probed.flatMap {
      case Left(r) => refuse(r, rawPath, req)
      case Right(real) =>
        // 凭据判据：与 nf-file 票据腿 / 取字节腿**同一个函数**（单一权威，零复制）。
        NfFilePolicy.nfVerdictForRealLayer(real, policy) match
          case Some((_, denied)) =>
            refuse(Refusal(denied.status.code, denied.reason, denied.message), rawPath, req)
          case None =>
            IO.blocking[Either[Refusal, (String, FileTypeRegistry.Entry, Long)]] {
              val name = real.getFileName.toString
              val entry = FileTypeRegistry.detect(extOf(name))
              val size = Files.size(real)
              val limit = TextStreamSearch.popReadGateLimit(entry.binary)
              if size > limit then
                Left(
                  Refusal(
                    413,
                    "too-large",
                    s"the file is $size bytes; this entry point serves up to $limit bytes " +
                      s"(${limit / (1024 * 1024)}MB) for ${if entry.binary then "binary" else "text"} files"
                  )
                )
              else Right((name, entry, size))
            }.flatMap {
              case Left(r) => refuse(r, rawPath, req)
              case Right((name, entry, size)) =>
                Ok(
                  Json.obj(
                    "ok" -> true.asJson,
                    "kind" -> "local".asJson,
                    "absPath" -> real.toString.asJson,
                    "fileName" -> name.asJson,
                    "itemType" -> entry.itemType.asJson,
                    "binary" -> entry.binary.asJson,
                    "size" -> size.asJson
                  )
                ).map(_.putHeaders(Header.Raw(CIString("Cache-Control"), "no-store")))
            }
    }
  end localLeg

  // ────────────────────────────────────────────────────────────────────────
  // URL 腿
  // ────────────────────────────────────────────────────────────────────────

  private def urlLeg(
    rawUrl: String,
    req: Request[IO],
    fetchImpl: (String, Path) => IO[Either[Refusal, ExternalFetch.Outcome]]
  ): IO[Response[IO]] =
    // 数据根与落盘根分两个名字：`P.storagePath` 自己会拼 `external-fetch/<sha16>`
    // （外部-fetch 批 van 修复：把 storageDir 传进去会拼成 external-fetch/external-fetch）。
    val dataRootNio = PathUtil.dataRoot.toNIO
    val root = dataRootNio.resolve(P.StorageDirName)
    val tmp = root.resolve(s".tmp-${UUID.randomUUID().toString}")
    fetchImpl(rawUrl, tmp).flatMap {
      case Left(r) => refuse(r, rawUrl, req)
      case Right(outcome) =>
        val urlPath =
          try Option(new java.net.URI(rawUrl).getPath).getOrElse("")
          catch case _: Throwable => ""
        val ext = P.servedExtension(outcome.head, urlPath)
        val fileName = P.fileNameFor(urlPath, ext)
        val dest = P.storagePath(dataRootNio, outcome.sha256, fileName)
        val entry = FileTypeRegistry.detect(ext)
        for
          _ <- IO.blocking {
            Files.createDirectories(dest.getParent)
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            Files.setLastModifiedTime(dest.getParent, FileTime.fromMillis(System.currentTimeMillis()))
          }
          swept <- sweepExpired(root)
          _ <- IO.whenA(swept > 0)(logger.info(s"nf-external sweep: removed $swept expired fetch dirs"))
          resp <- Ok(
            Json.obj(
              "ok" -> true.asJson,
              "kind" -> "url".asJson,
              "absPath" -> dest.toString.asJson,
              "fileName" -> fileName.asJson,
              "itemType" -> entry.itemType.asJson,
              "binary" -> entry.binary.asJson,
              "size" -> outcome.size.asJson,
              "sha256" -> outcome.sha256.asJson,
              "finalUrl" -> outcome.finalUri.toString.asJson,
              "active" -> P.ActiveExtensions.contains(ext).asJson
            )
          )
        yield resp.putHeaders(Header.Raw(CIString("Cache-Control"), "no-store"))
        end for
    }

  end urlLeg

  /**
   * TTL 清账（best effort：失败只记一行 warn，绝不影响本次打开）。
   *
   * 顺序有坑：`Files.walk` 的流必须在**删除之前**关闭——边遍历边删同一个目录，
   * macOS 上会拿到 `DirectoryNotEmptyException`（懒遍历仍持着目录句柄）。所以先
   * 收成清单、关流，再深→浅删。
   */
  private def sweepExpired(root: Path): IO[Int] =
    IO.blocking {
      val now = System.currentTimeMillis()
      P.expiredDirs(root, now).count { dir =>
        try
          val stream = Files.walk(dir)
          val listed =
            try stream.iterator().asScala.toList
            finally stream.close()
          listed.sortBy(_.getNameCount).reverse.foreach(p => Files.deleteIfExists(p))
          true
        catch
          case e: Throwable =>
            logger.warn(s"nf-external sweep failed for ${dir.getFileName}: ${e.getClass.getSimpleName}")
            false
      }
    }.handleError(_ => 0)

  private def extOf(name: String): String =
    val i = name.lastIndexOf('.')
    if i < 0 then "" else name.substring(i + 1).toLowerCase

end ExternalOpenRoutes
