package nebflow.bridge

import cats.effect.IO
import nebflow.shared.ContentBlock

import java.nio.file.{Files, Paths}
import java.util.Base64

/**
 * 社交通道入站媒体统一适配（weixin-inbound-media-unify batch, 2026-10-20）——
 * 桥接缝的**统一登记/构块纯面**。渠道（微信腿先飞，飞书腿接口就位）只递
 * [[BridgeAttachment]] 指针；这里按**控制台 uploads 管线同款判据**完成三件事：
 *
 *   ① 分类（`WebSocketRoutes.handleMessage` :1770 的判据逐字：mime
 *      `startsWith("image/")` ⇒ "image"，否则 "file"）；
 *   ② attJson 形状（控制台落盘行同形 `[{name,type,path}]`）；
 *   ③ vision 构块（控制台 images 同管线：`ImageInject.prepareImage` 压缩管线
 *      （1920/0.8、2MB 触发、5MB 硬帽、10MB 前置帽）⇒ `ContentBlock.Image`），
 *      标签线与控制台逐字同款（`[用户附加图片: <absPath>]` /
 *      `[用户附加文件: <absPath>]`）。
 *
 * 🔴 fail-closed 哲学与 mediaArm 一致：构块失败（超帽/读失败/非具体 mime）⇒
 * 块缺省、标签线仍在——消息永远以可读文本送达，从不 hold、从不静默丢。
 */
object UnifiedMedia:

  /** Image sniff-fallback mime the weixin sidecar uses for an unrecognized
    * image container — NOT concrete, so it can never ride a provider block. */
  val WildcardImageMime = "image/*"

  /**
   * ① 分类（控制台 uploads 管线同款判据，逐字）：`image/` 前缀 ⇒ image。
   * 通配嗅探形态（WildcardImageMime，`image` 斜杠加星）按**文件**收口——非具体 mime 进不了 provider 的
   * image 块，与其让请求 4xx 不如诚实地按文件路径送达（fail-closed 降级）。
   */
  def attachmentTypeOf(mime: Option[String]): String =
    mime.exists(m => m.trim.nonEmpty && m.trim.startsWith("image/")) &&
      !mime.exists(_.trim == WildcardImageMime) match
      case true => "image"
      case false => "file"

  /** The attJson `name` fallback: the path basename. */
  def nameOf(a: BridgeAttachment): String =
    a.name.map(_.trim).filter(_.nonEmpty).getOrElse(
      a.path.replaceAll("\\\\", "/").split("/").lastOption.filter(_.nonEmpty).getOrElse("file")
    )

  /**
   * ② 控制台同形 attJson 行：`{name, type, path}`（`WebSocketRoutes` :1764-1773
   * 的形状；path 恒在——指针登记的本体）。
   */
  def attJsonOf(attachments: List[BridgeAttachment]): List[io.circe.Json] =
    attachments.filter(_.path.trim.nonEmpty).map { a =>
      io.circe.Json.obj(
        "name" -> io.circe.Json.fromString(nameOf(a)),
        "type" -> io.circe.Json.fromString(attachmentTypeOf(a.mime)),
        "path" -> io.circe.Json.fromString(a.path)
      )
    }

  /**
   * ③-a 标签线（控制台逐字同款；`UnifiedMedia` 是组合单点，渠道不再自拼第二套）。
   * image ⇒ `[用户附加图片: <path>]`；file ⇒ `[用户附加文件: <path>]`。
   */
  def labelLineOf(a: BridgeAttachment): String =
    if attachmentTypeOf(a.mime) == "image" then s"[用户附加图片: ${a.path}]"
    else s"[用户附加文件: ${a.path}]"

  /** ③-a 全部附件的标签线，`\n` 连接（多件 = 飞书相册形态的接口预留）。 */
  def labelLinesOf(attachments: List[BridgeAttachment]): String =
    attachments.filter(_.path.trim.nonEmpty).map(labelLineOf).mkString("\n")

  /**
   * ③-b vision 构块（控制台 images 同管线）：image ⇒ 读盘 →
   * `nebflow.core.tools.ImageInject.prepareImage` ⇒ `ContentBlock.Image(b64, mime)`；
   * file ⇒ 无块（路径文本线就是它的读取面——ReadTool 同一扇面）。任何失败
   * （缺盘/超 10MB 帽/读失败/压缩后仍超 5MB）⇒ `Nil`，**永不 raise**。
   */
  def imageBlocksOf(a: BridgeAttachment): IO[List[ContentBlock.Image]] =
    if attachmentTypeOf(a.mime) != "image" then IO.pure(Nil)
    else
      IO.blocking {
        val p = Paths.get(a.path)
        if !Files.isRegularFile(p) then Nil
        else
          val bytes = Files.readAllBytes(p)
          if bytes.length > nebflow.core.tools.ImageInject.MAX_IMAGE_BYTES then Nil
          else
            // Concrete mime only at the block face: the provider refuses a
            // wildcard mediaType, so the wildcard sniff degrades to file (the
            // label line already rode the model face either way).
            val mime = a.mime.map(_.trim).filter(_.nonEmpty).getOrElse("")
            if mime == WildcardImageMime || mime.isEmpty then Nil
            else
              nebflow.core.tools.ImageInject.prepareImage(bytes, mime, nameOf(a)) match
                case nebflow.core.tools.ImageInject.TooLarge(_) => Nil
                case nebflow.core.tools.ImageInject.Prepared(prepared, preparedMime, _) =>
                  List(ContentBlock.Image(Base64.getEncoder.encodeToString(prepared), preparedMime))
      }.handleError(_ => Nil)

end UnifiedMedia
