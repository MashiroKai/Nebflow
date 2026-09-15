package nebflow.neblink

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json

/**
 * 附件上传的**唯一**分块驱动（attachcl 批，2026-09-16）。
 *
 * ## 为什么本件存在（同源性硬钉）
 *
 * 附件腿现在有**两条**发起面，字节源不同、驱动必须同一份：
 *
 *  1. **桌面腿**（既有）：agent/工具发起，字节源 = 本机磁盘文件
 *     （[[FriendService]] `sendAsAgent` → `doSend` → 本对象）。
 *  2. **网页腿**（本批）：浏览器整件一次请求 → 网关把请求体流式落临时件 →
 *     **同一本对象**（`AttachUpload.pushFile`）驱动 E1 + E2×n。
 *
 * 🔴 本件是 `FriendService.uploadAll` 里那段 `pushChunks` 的**逐字搬移**（同一次
 * 提交内抽此件，桌面腿改为调用本件），不是第二套实现：分块计划仍由
 * [[nebflow.dropbox.AttachContract.plan]] 单一给定（块大小 4 MiB =
 * `AttachContract.ChunkSize`），单块读法仍是 [[NeblinkFiles.readRange]]，单块摘要
 * 仍由 [[nebflow.dropbox.ChunkedTransfer.sha256Hex]] 计算，失败重试仍是「同 offset
 * 幂等重发一次」。改本件 = 两条腿同时改 —— 这正是本批要的同源性。
 *
 * ## 断点续传同源
 *
 * 计划**恒从 offset 0 起**（与桌面腿逐字一致）：续传语义在**服务端**（E2 的
 * `offset <= receivedBytes` 截断重写），客户端不维护本地续传指针 ⇒ 无第二份状态机。
 *
 * ## 进度与取消（硬钉②：禁假进度 / 失败必须可见）
 *
 * [[Hooks.onChunk]] 只在**服务端确认**一块之后被调用（`uploadAttachmentChunk` 返回
 * `Right` 才推进）⇒ 进度是真实分块确认的产物，不存在「到点即 100%」。
 * [[Hooks.cancelled]] 在**下一块发出前**求值 ⇒ 取消 = 不再发出后续分块，且返回
 * [[Failure.Cancelled]]（**不是** `Right`）⇒ 终态诚实、不可能被读成完成。
 */
object AttachUpload:

  /** 上传失败的**可判读终态**（不折叠成裸串：取消与失败在调用侧必须可分）。 */
  enum Failure:
    /** 调用方取消：后续分块已停止，**未完成**（禁报成完成）。 */
    case Cancelled
    /** 真实失败：上游拒绝 / 传输失败 / 缺字段，原因逐字带出。 */
    case Failed(message: String)

    /** 人可读文案（两条腿共用同一渲染，禁各处另写一套）。 */
    def render: String = this match
      case Cancelled  => "Upload cancelled before completion — nothing was completed and no message was sent."
      case Failed(m)  => m

  /** 真实分块确认推进一条（`bytesSent` = 该块确认后服务端持有的前缀长度）。 */
  final case class Progress(chunkIndex: Int, bytesSent: Long, totalBytes: Long)

  /** 驱动钩子（两条腿都经此注入；缺省 = 零行为，桌面腿因此逐字不变）。 */
  final case class Hooks(
    onChunk: Progress => IO[Unit] = (_: Progress) => IO.unit,
    cancelled: IO[Boolean] = IO.pure(false)
  )

  object Hooks:
    val none: Hooks = Hooks()

  /** 一条上传的成功回执。 */
  final case class Uploaded(attachmentId: String, name: String, size: Long, sha256: String)

  /** 桌面腿入口：字节源 = 本机磁盘件（size 与整件摘要由本函数自盘上取）。 */
  def uploadFile(
    cli: NeblinkClient,
    conversationId: String,
    path: os.Path,
    displayName: String,
    hooks: Hooks = Hooks.none
  ): IO[Either[Failure, Uploaded]] =
    for
      size  <- IO.blocking(os.stat(path).size)
      whole <- IO.blocking(NeblinkFiles.sha256OfFile(path))
      out   <- pushFile(cli, conversationId, displayName, size, whole, path, hooks)
    yield out

  /**
   * E1 + E2×n（**两条腿的唯一上传链**）。
   *
   * `size`/`whole` 由调用方给定（桌面腿 = 盘上现算；网页腿 = 落临时件的同一遍流式
   * 写出顺带算），此后逐字同链：E1 建会话 → 取 `attachmentId` → 按
   * [[nebflow.dropbox.AttachContract.plan]] 逐块 E2。
   */
  def pushFile(
    cli: NeblinkClient,
    conversationId: String,
    displayName: String,
    size: Long,
    whole: String,
    path: os.Path,
    hooks: Hooks = Hooks.none
  ): IO[Either[Failure, Uploaded]] =
    cli.createAttachment(conversationId, displayName, size, whole).flatMap {
      case Left(err) =>
        IO.pure(Left(Failure.Failed(s"attachment upload failed for '$displayName' (create): $err")))
      case Right(json) =>
        json.hcursor.get[String]("attachmentId").toOption match
          case None =>
            IO.pure(
              Left(
                Failure.Failed(
                  s"attachment upload failed for '$displayName': server response has no attachmentId (${json.noSpaces})"
                )
              )
            )
          case Some(id) => pushChunks(cli, id, path, size, displayName, hooks).map(_.map(_ => Uploaded(id, displayName, size, whole)))
    }

  /** 分块推送（本批唯一一份；`sent` 只在**服务端确认后**推进）。 */
  def pushChunks(
    cli: NeblinkClient,
    id: String,
    path: os.Path,
    size: Long,
    displayName: String,
    hooks: Hooks = Hooks.none
  ): IO[Either[Failure, Unit]] =
    val plan = nebflow.dropbox.AttachContract.plan(size)
    def loop(rest: List[nebflow.dropbox.AttachContract.ChunkPlan]): IO[Either[Failure, Unit]] =
      rest match
        case Nil => IO.pure(Right(()))
        case chunk :: tail =>
          hooks.cancelled.flatMap {
            case true => IO.pure(Left(Failure.Cancelled))
            case false =>
              val sendOnce: IO[Either[String, Unit]] =
                IO.blocking {
                  val bytes = NeblinkFiles.readRange(path, chunk.offset, chunk.bytes)
                  bytes -> nebflow.dropbox.ChunkedTransfer.sha256Hex(bytes)
                }.flatMap { case (bytes, sha) =>
                  cli.uploadAttachmentChunk(id, chunk.offset, bytes, sha).map(_.map(_ => ()))
                }
              sendOnce
                .flatMap {
                  case Right(_)       => IO.pure[Either[String, Unit]](Right(()))
                  case Left(firstErr) => sendOnce.map(_.left.map(_ => firstErr)) // 单次重试（同 offset 幂等）
                }
                .flatMap {
                  case Left(err) =>
                    IO.pure(
                      Left(
                        Failure.Failed(
                          s"attachment upload failed for '$displayName' at offset ${chunk.offset}: $err"
                        )
                      )
                    )
                  case Right(_) => hooks.onChunk(Progress(chunk.index, chunk.offset + chunk.bytes, size)) *> loop(tail)
                }
          }
    loop(plan)

  /** 上传链失败 → `(code, 可判读文案)`（网关路由按 code 定状态码/前端按 code 分态）。
    *
    * `code` 取自失败文本里的上游 HTTP 码（[[AttachmentCapability.httpStatus]]，与
    * 能力探测**同一**解析点，禁第二套）：403 关系/成员闸、413/422 参数与上限、429
    * 限速、404 路由缺失（= 服务端不支持附件），其余 = 传输层/未知。 */
  def errorCode(f: Failure): String = f match
    case Failure.Cancelled => "cancelled"
    case Failure.Failed(msg) =>
      AttachmentCapability.httpStatus(msg) match
        case Some(404)           => "attachment_unsupported"
        case Some(403)           => "forbidden"
        case Some(413)           => "attach_too_large"
        case Some(422)           => "invalid_attachment"
        case Some(429)           => "rate_limited"
        case Some(503)           => "quota_exceeded"
        case Some(code) if code >= 500 => "upstream_error"
        case Some(_)             => "upstream_rejected"
        case None                => "upload_failed"
