package nebflow.social.outbound

import cats.effect.IO
import com.lark.oapi.Client as LarkClient
import com.lark.oapi.service.im.v1.model.{
  CreateFileReq,
  CreateFileReqBody,
  CreateImageReq,
  CreateImageReqBody,
  CreateMessageReq,
  CreateMessageReqBody
}
import nebflow.shared.{AttachContract, NebflowLogger, PathUtil}
import nebflow.social.{FeishuChannel, FeishuCredentials}

import java.io.File
import java.nio.file.Files

/**
 * Feishu outbound leg for rich content (richcontent batch, 2026-09-30).
 *
 * Three kinds, three SDK legs, named explicitly so no reader has to guess
 * "which upload API":
 *
 *   - image  → `com.lark.oapi.service.im.v1.resource.Image.create(CreateImageReq)`
 *              with a `CreateImageReqBody` (`imageType("message")` + `image`),
 *              taking `CreateImageRespBody.getImageKey()`, then an
 *              `im/v1/messages` send with `msg_type="image"` and content
 *              `{"image_key": …}`.
 *   - file   → `com.lark.oapi.service.im.v1.resource.File.create(CreateFileReq)`
 *              with a `CreateFileReqBody` (`fileType` + `fileName` + `file`),
 *              taking `CreateFileRespBody.getFileKey()`, then a send with
 *              `msg_type="file"` and content `{"file_key": …}`.
 *   - text   → the existing [[FeishuChannel.sendText]] path (unchanged).
 *
 * Fail-closed is the whole point of this file (design card §7.1–§7.3):
 *
 *   - a render that did not produce a verified PNG never reaches the upload leg;
 *     it becomes the `[未渲染] …` text plus a warn line;
 *   - a product over the EXISTING size gate (`AttachContract.checkFileSize`,
 *     whose constant is `AttachContract.MaxFileBytes`) is refused BEFORE any
 *     upload, with the actual byte count echoed back;
 *   - an upload the channel refuses produces BOTH a reading carrying the
 *     channel's code AND a degrade text, so the recipient is never left with
 *     silence;
 *   - every intended call yields exactly one [[RichCallResult]], and every
 *     failure carries a non-empty `detail` (enforced by [[RichLedger.of]]). The
 *     three swallow idioms (`case _ => ()`, `case _ => IO.unit`, "only
 *     `logger.warn`") appear nowhere: a warn line accompanies a reading, it
 *     never replaces one.
 *
 * 🔴 Zero-secret discipline: the credential VALUE passes only into the SDK
 * client constructor. Nothing here logs, echoes, or records it — the logs carry
 * a reason and a path and nothing else.
 *
 * 🔴 Testability is a first-class requirement (task brief §二.2 item 8: "no real
 * network in tests"). EVERY outbound call this class makes goes through one of
 * the four constructor-injected seams — including the message send that follows
 * an upload. There is deliberately no production-only path left inline, because
 * a single un-seamed call would make the offline spec reach the network and
 * thereby stop being offline.
 */
final class RichFeishuAdapter(
  uploadImageFn: RichFeishuAdapter.UploadImage,
  uploadFileFn: RichFeishuAdapter.UploadFile,
  sendMsgFn: RichFeishuAdapter.SendMsg,
  sendTextFn: RichFeishuAdapter.SendText,
  renderer: RichRenderer.Renderer,
  renderOutputDir: () => os.Path = RichFeishuAdapter.defaultRenderOutputDir,
  /**
   * 文件类产物（md/markdown/html/htm）的单张长图转换缝（pop-social-files
   * batch, 2026-10-20；作者令：文件类投递社交腿=转长图，转不动降文本卡）。
   * 生产 = [[FileLongShot.NodeShot]]（仓内 node playwright，md 走 marked 同款
   * 渲染）；离线 spec 注入 fake / [[FileLongShot.Unavailable]]，不启动 node。
   * 原 social-webshot batch 的 `webshot` 缝（html 文件直发、截图兜底）随其
   * 文件腿一并退役——2026-10-20 裁定接管了 2026-10-09 14:0x 的同名裁定面。
   */
  longshot: FileLongShot.Renderer = FileLongShot.NodeShot()
):

  private val logger = NebflowLogger.forName("nebflow.social.outbound.feishu")

  import RichFeishuAdapter.*

  /**
   * Deliver one rich product.
   *
   * @param kind    the classification, already decided by [[RichKind.of]]
   * @param path    the product on disk
   * @param content the product's leading content — carries the card payload for
   *                a card product; ignored by the other kinds
   */
  def deliver(kind: RichKind, path: Option[os.Path], content: String = ""): IO[List[RichCallResult]] =
    kind match
      case RichKind.Image => path.fold(degradeOnly("image product with no path"))(deliverImage)
      case RichKind.Document => path.fold(degradeOnly("document product with no path"))(deliverDocument)
      case RichKind.Card => deliverCard(content)
      case RichKind.Unknown(reason) =>
        // 机制词汇（分类原因）只进日志；用户行走两层（pop-social-files batch
        // 2026-10-20 作者令，接管 2026-10-09 的三层链）：
        //   a) 文件类可转换形态（md/markdown/html/htm 且原件在盘上）→ 单张长图
        //      PNG → 图片腿 + 原件路径伴行注；同字节重复 Pop 去重抑制；
        //   b) 转不动 / 其余形态 → 「文件名+本地路径+一句话摘要」文本卡
        //      （[[RichDegrade.unconverted]]）或 `[未送达]` 友好一行
        //      （[[RichDegrade.unknown]]），绝不透英文机制报错。
        logger.warnSync(s"outbound: product not carriable by this channel — $reason")
        val onDisk = path.filter(p => Files.isRegularFile(p.toNIO))
        onDisk.filter(FileLongShot.isConvertible) match
          case Some(p) => deliverLongShot(p)
          case None =>
            // onDisk 为空 = 原件不在盘上（路径不存在/非普通文件）⇒ [未送达] 面。
            sendDegrade(RichDegrade.unknown(path, missing = onDisk.isEmpty), okDetail = "unknown-kind notice delivered")

  /**
   * An event that classified into a rich kind but carried no usable path still
   *  has to say so — the same fail-closed rule, one step earlier.
   */
  private def degradeOnly(reason: String): IO[List[RichCallResult]] =
    logger.warnSync(s"outbound: rich product with no usable path — $reason")
    sendDegrade(RichDegrade.unknown(None, missing = true), okDetail = "missing-path notice delivered")

  // ────────────────── file long-shot leg（pop-social-files batch, 2026-10-20）──────────────────

  /**
   * 文件类产物（md/markdown/html/htm，原件在盘上）出站 —— 作者裁定
   * 2026-10-20（「Pop 的文件类产物投递社交腿=md/html→单张长图 PNG，转不动的
   * 形态降级为『文件名+本地路径+一句话摘要』文本卡；去重防轰炸」）：
   *
   *   ⓪ 去重 —— 同字节指纹（路径+size+mtime）已在账（前次推送已成功落账）⇒
   *      本次零发送，一行日志（防轰炸；失败不落账 ⇒ 下次 Pop 照常重试）；
   *   ① 转换主腿 —— 原件经仓内 node playwright 转**单张长图 PNG**
   *      （[[FileLongShot]]，30s 预算；md 走 marked 渲染——与聊天同一份 vendor
   *      库）→ 图片腿发出 → 文字腿附原件绝对路径（[[RichDegrade.originalNote]]，
   *      桌面端可打开原件）；发送成功才落去重账；
   *   ② 转不动（playwright 不可用/超时/产物非法）⇒ 友好文本卡收尾
   *      （[[RichDegrade.unconverted]]），绝不透机制报错。
   *
   * 分类层（[[RichKind.of]]）零改动：md/html 仍判 Unknown，本腿只在 Unknown 臂
   * 被读取。与 card 腿同构：转换产物不走尺寸闸（HC-3 判定 + 上传拒绝面兜底）；
   * 产物输出进数据根临时面（HC-4）。原 social-webshot 的「文件直发、截图兜底」
   * 腿（2026-10-09 14:0x 裁定）随本腿整体退役——同字节 html 文件消息在微信侧
   * 实测零到达（2026-10-20 实况），长图是作者点名的可看形态。
   */
  private def deliverLongShot(original: os.Path): IO[List[RichCallResult]] =
    IO(FileLongShot.Dedup.keyOf(original)).flatMap {
      case Some(key) if FileLongShot.Dedup.seen(key) =>
        // 零发送；调用方看到空读数即「本次没有可投递项」（warnSync 是同步
        // 日志——返回 Unit，直包 IO 提升为读数）。
        IO {
          logger.warnSync(
            s"outbound: long-shot duplicate suppressed for $original — same bytes already pushed (dedup)"
          )
          Nil
        }
      case keyOpt =>
        prepareRenderOutput("file").flatMap { out =>
          longshot.shot(original, out).flatMap {
            case Left(reason) =>
              logger.warnSync(
                s"outbound: file long-shot unavailable for $original ($reason) — degrading to the text card"
              )
              IO(FileLongShot.summarize(original)).flatMap(s =>
                sendDegrade(
                  RichDegrade.unconverted(original, s),
                  okDetail = "unconverted file card delivered"
                ))
            case Right(png) =>
              uploadImageFn(png).flatMap { up =>
                if !up.ok then
                  val pngBytes = if Files.exists(png.toNIO) then Files.size(png.toNIO) else 0L
                  val line = RichDegrade.renderFailed(RichDegrade.Reason.channelRejected(up.code), png, pngBytes)
                  logger.warnSync(s"outbound: long-shot upload refused (code=${up.code})")
                  sendDegrade(
                    s"$line\n${RichDegrade.originalNote(original)}",
                    okDetail = s"long-shot upload refused (code=${up.code})"
                  )
                else
                  for
                    imgSent <- sendMsgFn("image", imageKeyBody(up.key))
                    _ <- IO(if imgSent.ok then keyOpt.foreach(FileLongShot.Dedup.record) else ())
                    noteSent <- sendTextFn(RichDegrade.originalNote(original))
                  yield List(
                    RichLedger.of(
                      RichCall.UploadImage(png, thenSendImage = true),
                      imgSent.ok,
                      imgSent.code,
                      imgSent.messageId,
                      if imgSent.ok then "file long-shot sent"
                      else s"long-shot image message rejected: ${imgSent.detail}"
                    ),
                    RichLedger.of(
                      RichCall.SendText(RichDegrade.originalNote(original)),
                      noteSent.ok,
                      noteSent.code,
                      noteSent.messageId,
                      if noteSent.ok then "file original-path note sent"
                      else s"note send rejected: ${noteSent.detail}"
                    )
                  )
              }
          }
        }
    }

  // ─────────────────────────── image leg ───────────────────────────

  /**
   * An image product is sent with its REAL bytes (design card §3.6 route D): no
   *  re-encode, no synthesis, and the renderer is never consulted. The size gate
   *  runs first, so an oversize file is refused with zero upload.
   */
  private def deliverImage(p: os.Path): IO[List[RichCallResult]] =
    withSizeGate(p) { bytes =>
      uploadImageFn(p).flatMap { up =>
        if !up.ok then
          logger.warn(s"outbound: image upload refused for $p (code=${up.code})") *>
            sendDegrade(
              RichDegrade.renderFailed(RichDegrade.Reason.channelRejected(up.code), p, bytes),
              okDetail = s"image upload refused (code=${up.code})"
            )
        else
          sendMsgFn("image", imageKeyBody(up.key)).map { sent =>
            List(
              RichLedger.of(
                RichCall.UploadImage(p, thenSendImage = true),
                sent.ok,
                sent.code,
                sent.messageId,
                if sent.ok then s"image sent (${bytes} bytes)" else s"image message rejected: ${sent.detail}"
              )
            )
          }
      }
    }

  // ────────────────────────── document leg ──────────────────────────

  /**
   * A document product is uploaded as the ORIGINAL file (route E: a PDF is
   *  never rasterised — the file is the deliverable).
   */
  private def deliverDocument(p: os.Path): IO[List[RichCallResult]] =
    withSizeGate(p) { bytes =>
      val fileName = p.last
      uploadFileFn(p, fileName).flatMap { up =>
        if !up.ok then
          logger.warn(s"outbound: file upload refused for $p (code=${up.code})") *>
            sendDegrade(
              RichDegrade.renderFailed(RichDegrade.Reason.channelRejected(up.code), p, bytes),
              okDetail = s"file upload refused (code=${up.code})"
            )
        else
          sendMsgFn("file", fileKeyBody(up.key)).map { sent =>
            List(
              RichLedger.of(
                RichCall.UploadFile(p, fileName, thenSendFile = true),
                sent.ok,
                sent.code,
                sent.messageId,
                if sent.ok then s"file sent (${bytes} bytes)" else s"file message rejected: ${sent.detail}"
              )
            )
          }
      }
    }

  // ─────────────────────────── card leg ────────────────────────────

  /**
   * A card's visible product is HTML **plus JavaScript** — an interactive
   * front-end artefact. What Feishu can carry is a static screenshot, so the
   * recipient MUST additionally be told where the live version lives (author
   * ruling 2026-09-30: the note is verbatim
   * [[RichPlanner.InteractivePanelNote]]).
   *
   * 🔴 Product selection: the card's HTML is rendered here through route A. On a
   * successful render the screenshot is uploaded and sent, THEN the note — two
   * calls, in that order. On a failed render the leg degrades to ONE text
   * carrying both the §7.1 placeholder and the note. Treating an interactive
   * product as a plain picture with no note is the red line.
   */
  private def deliverCard(content: String): IO[List[RichCallResult]] =
    renderCardPng(content).flatMap {
      case Left(reason) =>
        val line = RichDegrade.cardNotRendered(reason, content.getBytes("UTF-8").length.toLong)
        logger.warn(s"outbound: card render failed — $reason") *>
          sendDegrade(s"$line\n${RichPlanner.InteractivePanelNote}", okDetail = "card degrade text delivered")
      case Right(png) =>
        uploadImageFn(png).flatMap { up =>
          if !up.ok then
            val pngBytes = if Files.exists(png.toNIO) then Files.size(png.toNIO) else 0L
            val line = RichDegrade.renderFailed(RichDegrade.Reason.channelRejected(up.code), png, pngBytes)
            logger.warn(s"outbound: card screenshot upload refused (code=${up.code})") *>
              sendDegrade(
                s"$line\n${RichPlanner.InteractivePanelNote}",
                okDetail = s"card screenshot upload refused (code=${up.code})"
              )
          else
            for
              imgSent <- sendMsgFn("image", imageKeyBody(up.key))
              noteSent <- sendTextFn(RichPlanner.InteractivePanelNote)
            yield List(
              RichLedger.of(
                RichCall.UploadImage(png, thenSendImage = true),
                imgSent.ok,
                imgSent.code,
                imgSent.messageId,
                if imgSent.ok then "card screenshot sent" else s"card image message rejected: ${imgSent.detail}"
              ),
              RichLedger.of(
                RichCall.SendText(RichPlanner.InteractivePanelNote),
                noteSent.ok,
                noteSent.code,
                noteSent.messageId,
                if noteSent.ok then "interactive-panel note sent" else s"note send rejected: ${noteSent.detail}"
              )
            )
        }
    }

  // ──────────────────────────── plumbing ────────────────────────────

  /**
   * Report a degrade line: the reading records the SEND outcome, and its detail
   *  names what the recipient was told, so the evidence shows both halves.
   */
  private def sendDegrade(text: String, okDetail: String): IO[List[RichCallResult]] =
    sendTextFn(text).map { sent =>
      List(
        RichLedger.of(
          RichCall.SendText(text),
          sent.ok,
          sent.code,
          sent.messageId,
          if sent.ok then okDetail else s"degrade text send rejected: ${sent.detail}"
        )
      )
    }

  /**
   * Runs the EXISTING size gate before any upload. `Left` ⇒ one explicit text
   *  reading, zero upload, zero send — design card §6.2's hard order. The gate
   *  error itself carries `code` / `actual` / `limit`, and all three are echoed
   *  into the user-visible line.
   */
  private def withSizeGate(p: os.Path)(body: Long => IO[List[RichCallResult]]): IO[List[RichCallResult]] =
    IO.blocking {
      if !Files.exists(p.toNIO) then Left(s"product does not exist: $p")
      else if !Files.isRegularFile(p.toNIO) then Left(s"product is not a regular file: $p")
      else
        val size = Files.size(p.toNIO)
        AttachContract.checkFileSize(size).left.map(err => RichDegrade.tooLarge(err, p)).map(_ => size)
    }.flatMap {
      case Left(text) =>
        logger.warn(s"outbound: product refused before upload — $text") *>
          sendDegrade(text, okDetail = "size/unreadable refusal delivered")
      case Right(bytes) => body(bytes)
    }

  /**
   * Route A entry point. The output lands under the data root's temporary face
   * (HC-4: never inside the repository). A render failure comes back as
   * `Left(reason)` — never thrown, so the caller can degrade instead of losing
   * the turn.
   *
   * 🔴 The returned string is the renderer's CLOSED-vocabulary reason (design
   * card §7.1), i.e. exactly what the recipient should read; the renderer has
   * already logged the technical detail under that reason, so the user-visible
   * line stays matchable while the operator still gets the specifics.
   */
  private def renderCardPng(content: String): IO[Either[String, os.Path]] =
    prepareRenderOutput("card").flatMap { out =>
      renderer.render(htmlOf(content), out, DefaultWidth, DefaultHeight).map {
        // The renderer reports the path it actually produced; uploading anything
        // else would send a file we never verified. `out` is only the fallback for
        // a renderer that reports no path (the stubs in the offline spec).
        case r if r.ok => Right(r.path.getOrElse(out))
        case r => Left(if r.reason.trim.isEmpty then RichDegrade.Reason.EngineUnavailable else r.reason)
      }
    }

  /**
   * Where a render product lands (card 腿 / web page 腿): under the data root's
   *  temporary face, never in the repository (HC-4). Resolved through an injected
   *  thunk so the offline spec can point it at a temp directory instead of the
   *  process-global data root, which other suites mutate. `prefix` separates the
   *  two producers in the same directory.
   */
  private def prepareRenderOutput(prefix: String): IO[os.Path] =
    val out = renderOutputDir() / s"$prefix-${java.util.UUID.randomUUID().toString.take(12)}.png"
    // 只建父目录：对产物路径本身 createDirectories 会在 PNG 的位置造出一个目录，
    // 之后渲染/写入该路径必然失败（social-webshot batch 顺手修正的潜伏问题）。
    IO.blocking(Files.createDirectories(out.toNIO.getParent)).as(out)

  /**
   * The card's page source. A card product is the sentinel followed by a JSON
   * payload carrying the HTML in its `html` field (`CardTool` builds exactly that
   * shape), so the field is extracted here. Feeding the raw payload to a browser
   * would screenshot a wall of JSON instead of the card.
   *
   * The fallback is deliberate: a payload that is not JSON, or whose `html` field
   * is empty, is used as-is rather than refused — a page that renders something
   * the user recognises beats a hard failure on a shape we did not anticipate.
   * HC-3 still rejects the result if what comes back is not a real PNG.
   */
  private def htmlOf(content: String): String =
    val payload =
      if RichKind.isCardContent(content) then content.stripPrefix(RichKind.CardSentinel) else content
    io.circe.parser
      .parse(payload)
      .toOption
      .flatMap(_.hcursor.downField("html").as[String].toOption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(if payload.trim.isEmpty then content else payload)

end RichFeishuAdapter

object RichFeishuAdapter:

  /** Default render canvas for a card (the design card's probe geometry). */
  val DefaultWidth: Int = 600
  val DefaultHeight: Int = 320

  /**
   * The production renderer choice: real system Chrome when this host has one,
   *  the explicit "unavailable" carrier otherwise (HC-5 — a host without Chrome
   *  degrades loudly, it does not fake a screenshot).
   */
  def defaultRenderer: RichRenderer.Renderer =
    val chrome = new RichRenderer.ChromeRenderer()
    if chrome.available then chrome else RichRenderer.Unavailable

  /**
   * HC-4: cards render into the data root's temporary face, never the repo. A
   *  fresh thunk per call so a `--home` change after construction is honoured.
   */
  def defaultRenderOutputDir(): os.Path = PathUtil.dataRoot / "tmp" / "outbound-render"

  /**
   * Outcome of an upload leg. `key` = `image_key` / `file_key`. Never carries a
   *  credential or a signed URL.
   */
  final case class UploadResult(ok: Boolean, key: String, code: Int, detail: String)

  /** Outcome of a message send. `code` is the channel's, safe to record. */
  final case class SendResult(ok: Boolean, messageId: Option[String], code: Int, detail: String)

  /**
   * The four injectable seams. Production = the live implementations below; the
   * offline spec substitutes recorders, so an assertion can prove that an
   * oversize product never triggered an upload — and that no socket is opened —
   * without any network access.
   */
  type UploadImage = os.Path => IO[UploadResult]
  type UploadFile = (os.Path, String) => IO[UploadResult]

  /** `(msgType, contentJson)` → send reading. `msgType` is `image` or `file`. */
  type SendMsg = (String, String) => IO[SendResult]
  type SendText = String => IO[SendResult]

  /**
   * The `im/v1/messages` body for a `msg_type=image` send. `content` must be the
   *  JSON STRING form, not a JSON object — passing the object itself is the
   *  classic mistake on this endpoint.
   */
  def imageKeyBody(imageKey: String): String =
    io.circe.Json.obj("image_key" -> io.circe.Json.fromString(imageKey)).noSpaces

  /** The `im/v1/messages` body for a `msg_type=file` send. */
  def fileKeyBody(fileKey: String): String =
    io.circe.Json.obj("file_key" -> io.circe.Json.fromString(fileKey)).noSpaces

  /**
   * Build a channel client. Kept in one place so every leg reaches the same
   *  region face; the credential values are used only as constructor arguments
   *  and are never returned or logged.
   */
  private def client(appId: String, appSecret: String, region: String): LarkClient =
    LarkClient.newBuilder(appId, appSecret).openBaseUrl(FeishuChannel.baseUrlFor(region)).build()

  /**
   * Translate the SDK's `BaseResponse` shape into a reading. A null response and
   *  a thrown exception both become explicit failures — neither is swallowed.
   */
  private def reading(resp: com.lark.oapi.core.response.BaseResponse[?], messageId: Option[String]): SendResult =
    if resp == null then SendResult(ok = false, None, -1, "SDK returned a null response")
    else if resp.success() then SendResult(ok = true, messageId, resp.getCode, Option(resp.getMsg).getOrElse(""))
    else SendResult(ok = false, None, resp.getCode, Option(resp.getMsg).getOrElse(""))

  /**
   * Production image upload —
   * `com.lark.oapi.service.im.v1.resource.Image.create(CreateImageReq)` with a
   * `CreateImageReqBody` whose `imageType` is the SDK's own
   * `CreateImageCreateImageImageTypeEnum.MESSAGE` and whose file is the render
   * product, reading `CreateImageRespBody.getImageKey()`.
   */
  def liveUploadImage(appId: String, appSecret: String, region: String): UploadImage = path =>
    IO.blocking {
      val body = CreateImageReqBody
        .newBuilder()
        .imageType(com.lark.oapi.service.im.v1.enums.CreateImageCreateImageImageTypeEnum.MESSAGE)
        .image(new File(path.toString))
        .build()
      val resp = client(appId, appSecret, region)
        .im()
        .v1()
        .image()
        .create(CreateImageReq.newBuilder().createImageReqBody(body).build())
      if resp != null && resp.success() then
        val key = Option(resp.getData).flatMap(d => Option(d.getImageKey)).getOrElse("")
        if key.isEmpty then UploadResult(false, "", resp.getCode, "image upload succeeded but returned no image_key")
        else UploadResult(true, key, resp.getCode, "image uploaded")
      else if resp != null then UploadResult(false, "", resp.getCode, Option(resp.getMsg).getOrElse(""))
      else UploadResult(false, "", -1, "SDK returned a null response")
    }.handleErrorWith(e =>
      IO.pure(UploadResult(false, "", -1, s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"))
    )

  /**
   * Production file upload —
   * `com.lark.oapi.service.im.v1.resource.File.create(CreateFileReq)` with a
   * `CreateFileReqBody` carrying `fileType` (mapped through the SDK's
   * `CreateFileCreateFileFileTypeEnum`), `fileName` and `file`, reading
   * `CreateFileRespBody.getFileKey()`.
   */
  def liveUploadFile(appId: String, appSecret: String, region: String): UploadFile = (path, fileName) =>
    IO.blocking {
      val body = CreateFileReqBody
        .newBuilder()
        .fileType(fileTypeOf(fileName))
        .fileName(fileName)
        .file(new File(path.toString))
        .build()
      val resp = client(appId, appSecret, region)
        .im()
        .v1()
        .file()
        .create(CreateFileReq.newBuilder().createFileReqBody(body).build())
      if resp != null && resp.success() then
        val key = Option(resp.getData).flatMap(d => Option(d.getFileKey)).getOrElse("")
        if key.isEmpty then UploadResult(false, "", resp.getCode, "file upload succeeded but returned no file_key")
        else UploadResult(true, key, resp.getCode, "file uploaded")
      else if resp != null then UploadResult(false, "", resp.getCode, Option(resp.getMsg).getOrElse(""))
      else UploadResult(false, "", -1, "SDK returned a null response")
    }.handleErrorWith(e =>
      IO.pure(UploadResult(false, "", -1, s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"))
    )

  /**
   * Production message send for the image / file legs (`im/v1/messages` with the
   * given `msg_type`). The text leg keeps its own seam so it can reuse
   * [[FeishuChannel.sendText]] unchanged.
   */
  def liveSendMsg(
    appId: String,
    appSecret: String,
    region: String,
    receiveIdType: String,
    receiveId: String
  ): SendMsg = (msgType, contentJson) =>
    IO.blocking {
      val body = CreateMessageReqBody
        .newBuilder()
        .receiveId(receiveId)
        .msgType(msgType)
        .content(contentJson)
        .build()
      val req = CreateMessageReq
        .newBuilder()
        .receiveIdType(receiveIdType)
        .createMessageReqBody(body)
        .build()
      val resp = client(appId, appSecret, region).im().v1().message().create(req)
      reading(resp, Option(resp).flatMap(r => Option(r.getData)).flatMap(d => Option(d.getMessageId)))
    }.handleErrorWith(e =>
      IO.pure(SendResult(ok = false, None, -1, s"${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("")}"))
    )

  /** Production text send — reuses the existing channel path. */
  def liveSendText(
    appId: String,
    appSecret: String,
    region: String,
    receiveIdType: String,
    receiveId: String
  ): SendText = text =>
    IO.blocking(FeishuChannel.sendText(appId, appSecret, region, receiveIdType, receiveId, text)).map { r =>
      SendResult(r.ok, r.messageId, r.code, r.msg)
    }

  /**
   * Map a file name onto the SDK's own file-type enum — one authority, no second
   * table. `STREAM` covers everything the enum does not name, which is exactly
   * the "we do not know the type, send it as bytes" case; refusing there would
   * drop a legitimate document. PDF is named explicitly because route E sends
   * PDFs as files.
   */
  def fileTypeOf(fileName: String): String =
    import com.lark.oapi.service.im.v1.enums.CreateFileCreateFileFileTypeEnum as T
    val lower = fileName.toLowerCase
    val ext = lower.lastIndexOf('.') match
      case -1 => ""
      case i => lower.substring(i + 1)
    val t = ext match
      case "opus" => T.OPUS
      case "mp4" => T.MP4
      case "pdf" => T.PDF
      case "doc" | "docx" => T.DOC
      case "xls" | "xlsx" => T.XLS
      case "ppt" | "pptx" => T.PPT
      case _ => T.STREAM
    t.getValue

  end fileTypeOf

  /**
   * Assemble the production adapter from a resolved credential and the configured
   * destination. The credential VALUE is handed to the SDK only — nothing here
   * writes it anywhere.
   *
   * `cred` is deliberately typed as the existing [[FeishuCredentials.Credential]]
   * so a call site cannot pass a raw string pair and bypass the zero-secret
   * surface.
   */
  def live(
    cred: FeishuCredentials.Credential,
    region: String,
    receiveIdType: String,
    receiveId: String
  ): RichFeishuAdapter =
    new RichFeishuAdapter(
      liveUploadImage(cred.appId, cred.appSecret, region),
      liveUploadFile(cred.appId, cred.appSecret, region),
      liveSendMsg(cred.appId, cred.appSecret, region, receiveIdType, receiveId),
      liveSendText(cred.appId, cred.appSecret, region, receiveIdType, receiveId),
      defaultRenderer
    )

end RichFeishuAdapter
