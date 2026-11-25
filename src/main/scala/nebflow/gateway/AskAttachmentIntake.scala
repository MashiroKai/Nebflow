package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.tools.ImageInject
import nebflow.shared.{AttachContract, Config, ContentBlock, NebflowLogger}

import java.io.ByteArrayInputStream
import java.util.Base64

/* ═══════════════════════════════════════════════════════════════════════════
 * canvas ask 附图批（2026-11-22）· 附图提问的**入站面**。
 *
 * 帧 `askQuestion.attachments[]` = 主会话 `userMessage.attachments` 同形（前端
 * 复用 input/attachments.js 的压缩管线，载荷 `{name, mimeType, data(base64), size}`）。
 * 本单元做三件事，形态与主会话同一条腿：
 *   ① parse   ：件数闸（= AttachContract.MaxAttachmentsPerMessage，全仓同值）、
 *               仅图片（ask 面是视觉输入，非图件不进本腿）、base64 解码、
 *               ImageInject.prepareImage 压缩管线 + 5MB 硬帽 ⇒ 模型侧 vision 块字节。
 *   ② save    ：落盘 `uploads/<askSid>/<file>`（与主会话 WebSocketRoutes 同一
 *               AttachmentCommitService + URL 形状 resourceId `<sid>/<key>`，读取面
 *               `GET /uploads/...` 的授权因此同源生效）。无 ownerAccess（Bearer/REST）
 *               ⇒ 不落盘、path=null（与主会话同律：模型面仍收到 vision 块）。
 *   ③ 产物    ：uiJson（落 .ui.json 的 attachments 行，`{name,type,path}`）与
 *               blocks（vision 块 + 「[用户附加图片: <path>]」标签线，无 path 则只有块）。
 * fail-open 纪律：落盘失败不阻断提问（模型照常收图，仅历史回显缺路径）；
 * 只有**形态不合法**（件数/非图/非法 base64/超帽）才整条拒绝（askError）。
 * ═══════════════════════════════════════════════════════════════════════════ */
private[gateway] object AskAttachmentIntake:

  private val logger = NebflowLogger.forName("nebflow.ask.attach")

  /** 件数上限（全仓单一数值权威面：AttachContract.MaxAttachmentsPerMessage）。 */
  val MaxFiles: Int = AttachContract.MaxAttachmentsPerMessage

  /** 解析后的一件（vision 字节已过压缩管线）。 */
  final case class Prepared(name: String, mediaType: String, bytes: Array[Byte])

  /** 一件已受理的产物：UI 行件 + 模型侧块。 */
  final case class Saved(uiJson: Json, blocks: List[ContentBlock])

  /** 纯解析：形态闸 + base64 + 压缩管线。Left = 整条拒绝（原因可读）。 */
  private def parseOne(att: Json): Either[String, Prepared] =
    val c = att.hcursor
    val name = c.downField("name").as[String].toOption.map(_.trim).filter(_.nonEmpty).getOrElse("image")
    val mime = c.downField("mimeType").as[String].getOrElse("")
    val data = c.downField("data").as[String].getOrElse("")
    if !mime.startsWith("image/") || mime == "image/*" then
      Left(s"attachment '$name' is not a concrete image type (ask intake takes images only)")
    else if data.isEmpty then Left(s"attachment '$name' carries no data")
    else
      val decoded: Either[String, Array[Byte]] =
        scala.util.Try(Base64.getDecoder.decode(data)).toEither.left.map(_ => s"attachment '$name' is not valid base64")
      decoded.flatMap { raw =>
        ImageInject.prepareImage(raw, mime, name) match
          case ImageInject.Prepared(bytes, preparedMime, _) => Right(Prepared(name, preparedMime, bytes))
          case ImageInject.TooLarge(detail) => Left(s"image '$name' too large after compression: $detail")
      }

  def parse(atts: List[Json]): Either[String, List[Prepared]] =
    if atts.size > MaxFiles then Left(s"${atts.size} attachments — at most $MaxFiles per message")
    else atts.traverse(parseOne)

  /** 文件名词干清洗（与主会话 stem 同式：去扩展名，非 [A-Za-z0-9._-] 替换）。 */
  private def stemOf(name: String): String =
    name.replaceAll("\\.[a-zA-Z0-9]+$", "").replaceAll("[^A-Za-z0-9._-]", "_") match
      case "" => "image"
      case s => s

  private def extOf(mime: String): String =
    if mime.contains("png") then "png"
    else if mime.contains("webp") then "webp"
    else if mime.contains("gif") then "gif"
    else "jpg"

  /**
   * 落盘 + 产物组装（逐件顺序执行，保持帧内件序）。`owner` = 已认证会话主体；
   * `askSid` 必须是单段安全路径（AttachmentScope.segmentOf），否则一律不落盘。
   */
  def save(askSid: String, items: List[Prepared], owner: Option[OwnerAccess]): IO[List[Saved]] =
    items.zipWithIndex.traverse { case (p, idx) =>
      val b64 = Base64.getEncoder.encodeToString(p.bytes)
      // vision 子集上限 = ImageInject.MAX_ATTACHMENTS（引擎单源：AskUserAnswerBridge「前 N 张」同律）。
      // 超出部分仍落盘、行件保留，只是不进模型块。
      val visionOk = idx < ImageInject.MAX_ATTACHMENTS
      val image = ContentBlock.Image(b64, p.mediaType)
      val imageBlocks = if visionOk then List(image) else Nil
      val scoped = AttachmentScope.segmentOf(askSid)
      val keyOpt =
        nebflow.gateway.ResourceKey.from(s"${System.nanoTime()}_${stemOf(p.name)}.${extOf(p.mediaType)}").toOption
      val uploadRoot = Config.NebflowHome.toNIO.resolve("uploads").resolve(askSid)
      (owner, scoped, keyOpt) match
        case (Some(access), Some(sid), Some(key)) =>
          AttachmentCommitService
            .at(uploadRoot)
            .commit(access, key, s"$sid/${key.value}", new ByteArrayInputStream(p.bytes))
            .attempt
            .flatMap {
              case Right(path) =>
                IO.pure(
                  Saved(
                    Json.obj(
                      "name" -> p.name.asJson,
                      "type" -> "image".asJson,
                      "path" -> path.toString.asJson
                    ),
                    imageBlocks ++ List(ContentBlock.Text(s"[用户附加图片: ${path.toString}]"))
                  )
                )
              case Left(e) =>
                logger.warn(s"ask image '${p.name}' not persisted (model still receives it): ${e.getMessage}") *>
                  IO.pure(Saved(uiRow(p.name, None), imageBlocks))
            }
        case _ =>
          logger.warn(s"ask image '${p.name}' not persisted: owner access or session segment unavailable") *>
            IO.pure(Saved(uiRow(p.name, None), imageBlocks))
    }

  /** UI 行件（.ui.json 的 attachments 形状；path 缺席 ⇒ null，与主会话同律）。 */
  private def uiRow(name: String, path: Option[String]): Json =
    Json.obj(
      "name" -> name.asJson,
      "type" -> "image".asJson,
      "path" -> path.fold(Json.Null)(_.asJson)
    )

end AskAttachmentIntake
