package nebflow.core.delegate

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.tools.ToolError
import nebflow.shared.{AttachContract, NebflowLogger, PathUtil}

import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest

/**
 * DelegateAttachments —— `Delegate` 工具的 `attachments` 参数面（附件传输批
 * 2026-11-05，作者令逐字：「你的delegate支持附件传输吗？不支持的话要补充上，因为我
 * 发给你的这些图，你要能传给delegate，才不会丢失信息。」）。
 *
 * 解决的正是「把绝对路径手写进 brief 文本」这条土办法的四个静默失败面：
 *   ① **无清单**（作者发 4 张、brief 里只提 2 张 ⇒ 剩下静默丢失）；
 *   ② **无校验**（路径打错/文件被清理/被移动 ⇒ delegate 只能「打不开就算了」）；
 *   ③ **生命周期**（`<dataRoot>/uploads/` 是临时上传区，delegate 开工时可能已不在）；
 *   ④ **无类型/尺寸语义**（分不清哪些是图片 = 该走视觉通道）。
 *
 * 本单点分四段，判定顺序即失败顺序（任一不过 ⇒ **显式失败并指名路径**，绝不带病发出）：
 *   1. [[parse]]       形态 + 件数（件数闸只引用 [[AttachContract.MaxAttachmentsPerMessage]]）；
 *   2. [[validate]]    绝对路径 / 存在 / **是普通文件** / 可读 / 单件大小
 *                      （大小闸只引用 [[AttachContract.MaxFileBytes]]）；
 *   3. [[stage]]       **落地稳定副本**到 `<dataRoot>/delegate-attachments/<id>/` 并逐件
 *                      校验「副本 sha256 == 原件 sha256」（临时上传区被清理也不丢）；
 *   4. [[mirrorToSeat]] 座位树存在时再复制一份进 `<worktree>/.nebflow/attachments/<id>/`
 *                      （fail-soft：座位树合并后会删，dataRoot 副本才是长期锚）。
 *
 * 🔴 两条硬纪律：
 *   - **只读复制**：原作（作者上传区）只被读，绝不移动/删除/改写；
 *   - **清单与作者手写内容分离**：author 在 `task` 里手写的路径**逐字保留**，清单是
 *     brief 末尾的固定段落（[[ManifestHeader]]），两者不混。
 *
 * 视觉通道（本批实测结论）：delegate 的 `Read` 原生把 PNG/JPG/JPEG/GIF/WEBP/BMP 作为
 * **vision-ready 图像内容**返回（[[nebflow.core.tools.ReadTool]] 的图像分支），所以
 * 图片附件**不需要**额外的图像注入通道 —— 清单里标 `image=yes` 并给副本路径即可。
 * 唯一的降级面 = 单图超过 Read 的视觉上限 10 MiB（`ReadTool.MAX_IMAGE_BYTES`）：
 * Read 会**显式拒绝**（`Image too large`），故清单逐件标注该上限，并要求 delegate
 * 读不到就**报出来**、禁止假装看过。
 */
object DelegateAttachments:

  private val logger = NebflowLogger(getClass)

  /** 清单段固定标题（brief 末尾；author 手写正文与它的分界线）。 */
  val ManifestHeader = "[附件清单 · attachments manifest]"

  /** 纪律段标题（清单之后；「附件不进上下文，必须 Read」+ 读不到要报出来）。 */
  val DisciplineHeader = "[attachment discipline]"

  /** 稳定副本根目录名（挂在 `<dataRoot>` 下）。 */
  val StagingDirName = "delegate-attachments"

  /** 座位树副本的相对段（`<worktree>/.nebflow/attachments/<id>/`）。 */
  val SeatDirSegments: List[String] = List(".nebflow", "attachments")

  /** 图片后缀集合/MIME 的单点权威沿用 [[nebflow.core.tools.ImageInject]]（Read 同源）。 */

  /** 一件附件的读数（清单的一项；所有字段都是**派发时实测**，非推测）。 */
  final case class Entry(
    /** 作者/调用方给的原始绝对路径（逐字，不清洗）。 */
    original: String,
    /** 稳定副本（`<dataRoot>/delegate-attachments/<id>/…`）——**长期锚**。 */
    stableCopy: String,
    /** 座位树副本（`<worktree>/.nebflow/attachments/<id>/…`）；无座位树 = None。 */
    seatCopy: Option[String],
    /** 原件字节数（派发时 `stat` 实测）。 */
    sizeBytes: Long,
    /** 原件 sha256（hex；副本与之**必须相等**才算落地成功）。 */
    sha256: String,
    /** MIME 类型（按扩展名；未知 = application/octet-stream）。 */
    mime: String,
    /** 是否图片（= 走 Read 的视觉通道）。 */
    isImage: Boolean
  ):
    /** 清单里让模型 Read 的那条路径（座位树优先——delegate 就在那棵树里干活）。 */
    def readPath: String = seatCopy.getOrElse(stableCopy)

    /** 单图超过 Read 的视觉上限 ⇒ 清单必须逐件告知（否则「假装看过」）。 */
    def overVisionCap: Boolean =
      isImage && sizeBytes > nebflow.core.tools.ReadTool.MAX_IMAGE_BYTES

  end Entry

  /** 一次派发的附件计划（空 = 本次未带附件，全链路逐字节零改动）。 */
  final case class Plan(entries: List[Entry], copyDir: String):
    def isEmpty: Boolean = entries.isEmpty
    def size: Int = entries.size
    def names: List[String] = entries.map(e => fileNameOf(e.readPath))

  def empty: Plan = Plan(Nil, "")

  // ============================================================
  // 1) 形态 + 件数
  // ============================================================

  /**
   * `attachments` 参数的**形态解析**（纯函数；与 `MailTool.parseAttachments` 同族口径）：
   * 缺键 ⇒ `Nil`（本次不带附件）；非数组 / 含非字符串项 ⇒ 显式错误；空白项丢弃；
   * **件数闸** = [[AttachContract.checkAttachmentCount]]（上限数单点，禁第二份常量）。
   */
  def parse(input: JsonObject): Either[ToolError, List[String]] =
    input("attachments") match
      case None => Right(Nil)
      case Some(json) =>
        json.asArray match
          case None => Left(ToolError("attachments must be an array of absolute file path strings."))
          case Some(items) =>
            val strings = items.flatMap(_.asString)
            if strings.size != items.size then
              Left(ToolError("attachments must be an array of absolute file path strings."))
            else
              val paths = strings.map(_.trim).filter(_.nonEmpty).toList
              AttachContract.checkAttachmentCount(paths.size) match
                case Left(e) =>
                  Left(
                    ToolError(
                      s"${e.render} — pass at most ${AttachContract.MaxAttachmentsPerMessage} files " +
                        s"(got ${paths.size}). Nothing was dispatched."
                    )
                  )
                case Right(_) => Right(paths)

  // ============================================================
  // 2) 投递前判据（任一不过 = 显式失败 + 指名路径）
  // ============================================================

  /**
   * `attachments` 的**派发前判据**——在**任何副作用之前**跑（台账条目都不落）。
   * 判据序与错误文案一一对应，**不合并成一句**（模型/作者据此知道到底哪个路径错）：
   *   ① 绝对路径（对**原始串**判：`Paths.get` 会把相对段绝对化 ⇒ 构造后再判恒真）；
   *   ② 存在；
   *   ③ **是普通文件**（目录 / 设备 / 管道 / 符号链接指向目录 ⇒ 拒）；
   *   ④ 可读（`Files.isReadable` + 真开一次流，权限位骗人的情况兜住）；
   *   ⑤ 单件大小 = [[AttachContract.checkFileSize]]（>1 GiB ⇒ `ATTACH_TOO_LARGE` + actual/limit）。
   */
  def validate(paths: List[String]): IO[Either[ToolError, List[String]]] =
    if paths.isEmpty then IO.pure(Right(Nil))
    else
      IO.blocking {
        val relative = paths.filterNot(p => PathUtil.isAbsolute(p))
        if relative.nonEmpty then
          Left(
            ToolError(
              "attachments paths must be absolute, got: " + relative.map(p => s"'$p'").mkString(", ") +
                ". The delegate resolves these paths as-is on this machine, so pass ABSOLUTE paths of files " +
                "on this machine. Nothing was dispatched."
            )
          )
        else
          val missing = paths.filterNot(p => Files.exists(java.nio.file.Paths.get(p)))
          val notFile = paths.filter { p =>
            val jp = java.nio.file.Paths.get(p)
            Files.exists(jp) && !Files.isRegularFile(jp)
          }
          val unreadable = paths.filter { p =>
            val jp = java.nio.file.Paths.get(p)
            Files.exists(jp) && Files.isRegularFile(jp) && !canOpen(jp)
          }
          if missing.nonEmpty then
            Left(
              ToolError(
                "attachments does not exist: " + missing.map(p => s"'$p'").mkString(", ") +
                  ". Nothing was dispatched — check the path (a file must still exist at dispatch time; " +
                  "the upload area is a temporary directory and may already be cleaned up)."
              )
            )
          else if notFile.nonEmpty then
            Left(
              ToolError(
                "attachments is not a regular file: " + notFile.map(p => s"'$p'").mkString(", ") +
                  ". Attach individual files (directories, devices and pipes are not supported). " +
                  "Nothing was dispatched."
              )
            )
          else if unreadable.nonEmpty then
            Left(
              ToolError(
                "attachments is not readable: " + unreadable.map(p => s"'$p'").mkString(", ") +
                  ". Check the file mode — the delegate could never open it. Nothing was dispatched."
              )
            )
          else
            val sizes = paths.map(p => Files.size(java.nio.file.Paths.get(p)))
            // 大小闸：件数与逐件上限都只引用 [[AttachContract]]；**超限的逐个点名**
            // （「哪个文件太大、大了多少」——只说「太大了」等于没说）。
            val oversized = paths.zip(sizes).filter { case (_, s) => AttachContract.checkFileSize(s).isLeft }
            if oversized.nonEmpty then
              val detail = oversized
                .map { case (p, s) =>
                  s"'$p' ($s bytes)"
                }
                .mkString(", ")
              Left(
                ToolError(
                  s"attachments too large: $detail — the limit is ${AttachContract.MaxFileBytesLabel} per file " +
                    s"and ${AttachContract.MaxAttachmentsPerMessage} files per dispatch. Nothing was dispatched."
                )
              )
            else
              AttachContract.checkMessage(sizes) match
                case Left(e) =>
                  Left(
                    ToolError(
                      s"${e.render} — the limit is ${AttachContract.MaxFileBytesLabel} per file and " +
                        s"${AttachContract.MaxAttachmentsPerMessage} files per dispatch. Nothing was dispatched."
                    )
                  )
                case Right(_) => Right(paths)
            end if
          end if
        end if
      }

  // ============================================================
  // 3) 落地稳定副本（只读复制 + 逐件 sha256 等值断言）
  // ============================================================

  /** 稳定副本目录：`<dataRoot>/delegate-attachments/<id>/`。 */
  def stagingDirFor(dataRoot: Path, dispatchId: String): Path =
    dataRoot.resolve(StagingDirName).resolve(safeSegment(dispatchId))

  /** 座位树副本目录：`<worktree>/.nebflow/attachments/<id>/`。 */
  def seatDirFor(worktree: String, dispatchId: String): Path =
    SeatDirSegments
      .foldLeft(java.nio.file.Paths.get(worktree))((acc, seg) => acc.resolve(seg))
      .resolve(safeSegment(dispatchId))

  /**
   * 把附件**复制**成稳定副本并逐件校验。
   *
   * 幂等：同名副本已存在且 sha256 已经等于原件 ⇒ 复用（不重写）。校验不过即**删除
   * 坏副本并显式失败**（绝不让 delegate 拿到与清单 sha256 不符的字节）。
   * 原件永远只读：本方法对原路径只做 `read`，不移动、不删除、不改写。
   */
  def stage(paths: List[String], dispatchId: String, dataRoot: Path): IO[Either[ToolError, Plan]] =
    if paths.isEmpty then IO.pure(Right(empty))
    else
      IO.blocking {
        val dir = stagingDirFor(dataRoot, dispatchId)
        Files.createDirectories(dir)
        paths.zipWithIndex.foldLeft[Either[ToolError, List[Entry]]](Right(Nil)) {
          case (Left(err), _) => Left(err)
          case (Right(acc), (p, idx)) =>
            makeEntry(p, idx, dir).map(acc :+ _)
        } match
          case Left(err) => Left(err)
          case Right(entries) => Right(Plan(entries, dir.toString))
      }.handleErrorWith(e =>
        IO.pure(
          Left(
            ToolError(
              s"Delegate: attachment staging FAILED (${e.getClass.getSimpleName}): ${e.getMessage}. Nothing was dispatched."
            )
          )
        )
      )

  private def makeEntry(original: String, idx: Int, dir: Path): Either[ToolError, Entry] =
    val src = java.nio.file.Paths.get(original)
    try
      val name = fileNameOf(original)
      val target = dir.resolve(f"${idx + 1}%02d-$name")
      val srcSha = sha256File(src)
      val reused =
        Files.exists(target) && Files.isRegularFile(target) && Files.size(target) == Files.size(src) &&
          sha256File(target) == srcSha
      if !reused then Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING)
      val copySha = sha256File(target)
      val copySize = Files.size(target)
      if copySize != Files.size(src) || copySha != srcSha then
        try Files.deleteIfExists(target)
        catch case _: Exception => ()
        Left(
          ToolError(
            s"Delegate: attachment copy VERIFICATION FAILED for '$original' → '$target' " +
              s"(size ${copySize} vs ${Files.size(src)}, sha256 ${copySha} vs ${srcSha}). " +
              "Nothing was dispatched — the delegate must never receive bytes that differ from the manifest."
          )
        )
      else
        Right(
          Entry(
            original = original,
            stableCopy = target.toString,
            seatCopy = None,
            sizeBytes = copySize,
            sha256 = copySha,
            mime = mimeOf(name),
            isImage = isImageName(name)
          )
        )
      end if
    catch
      case e: Exception =>
        Left(
          ToolError(
            s"Delegate: attachment staging FAILED for '$original': ${e.getClass.getSimpleName}: ${e.getMessage}. " +
              "Nothing was dispatched."
          )
        )
    end try
  end makeEntry

  // ============================================================
  // 4) 座位树镜像（fail-soft：座位树是短命的，长期锚是 dataRoot 副本）
  // ============================================================

  /**
   * 座位树存在时把稳定副本再复制一份进 `<worktree>/.nebflow/attachments/<id>/`
   * （delegate 的 cwd 就在那棵树里，清单给相对近的路径更好读）。`<id>` 沿用稳定副本
   * 目录名 ⇒ 两处副本永远同名可对账，不需要第二条 id 参数。
   *
   * **fail-soft 合同**：任何异常（无写权限 / 树被并发删除 / 磁盘满）只 WARN，清单退回
   * dataRoot 副本——绝不因为一个便利副本而拒绝整个派发（原件可读性已在 [[validate]]
   * 判过、稳定副本已在 [[stage]] 落地并校验过）。
   */
  def mirrorToSeat(plan: Plan, worktree: Option[String]): IO[Plan] =
    (worktree, plan.isEmpty) match
      case (_, true) => IO.pure(plan)
      case (None, false) => IO.pure(plan)
      case (Some(wt), false) =>
        IO.blocking {
          val dir = seatDirFor(wt, fileNameOf(plan.copyDir))
          Files.createDirectories(dir)
          val entries = plan.entries.map { e =>
            val target = dir.resolve(fileNameOf(e.stableCopy))
            Files.copy(java.nio.file.Paths.get(e.stableCopy), target, StandardCopyOption.REPLACE_EXISTING)
            // 座位副本同样逐件校验：不符 = 不给清单（退回落 dataRoot 副本），绝不指向坏字节。
            e.copy(seatCopy = if sha256File(target) == e.sha256 then Some(target.toString) else None)
          }
          plan.copy(entries = entries)
        }.handleErrorWith { e =>
          logger
            .warn(
              s"DelegateAttachments.mirrorToSeat failed (seat copies skipped, stable copies still authoritative): ${e.getMessage}"
            )
            .as(plan)
        }

  // ============================================================
  // 清单渲染（brief 末尾固定段落）
  // ============================================================

  /**
   * 渲染「附件清单」段。`Nil` ⇒ 空串（brief 逐字节不变——既有调用方零漂移）。
   *
   * 每项给验收要求的五读数：**副本绝对路径 + 字节数 + sha256 + MIME/类型 + 是否图片**，
   * 外加原路径（追溯）。段首一句把「这不是作者手写正文」说清。
   */
  def render(plan: Plan): String =
    if plan.isEmpty then ""
    else
      val lines = plan.entries.zipWithIndex.map { case (e, i) =>
        val flags = List(
          e.mime,
          if e.isImage then "image=yes" else "image=no",
          s"${e.sizeBytes} B",
          s"sha256 ${e.sha256}"
        ).mkString(" · ")
        val overCap =
          if e.overVisionCap then
            s"\n     ! ${e.sizeBytes} B is above the Read vision cap (${nebflow.core.tools.ReadTool.MAX_IMAGE_BYTES} B = 10 MiB): Read refuses it explicitly — report that, do not pretend to have seen it"
          else ""
        val stable =
          if e.seatCopy.isEmpty then ""
          else s"\n     stable: ${e.stableCopy}"
        s"""  ${i + 1}. copy: ${e.readPath}
     orig: ${e.original}
     $flags$stable$overCap"""
      }
      val discipline =
        s"""$DisciplineHeader Attachment bytes are NOT in your context — open each `copy` path with Read before using it.
Read returns PNG/JPG/JPEG/GIF/WEBP/BMP as vision-ready image content (you see the picture, not base64), so `image=yes` entries are directly visible to you; if Read answers with text/binary or an error (or a `copy` path is missing, or its sha256 differs from the value above), say so in your final report — never pretend you looked at it.
In your final report, state which attachments you actually opened, and flag any you did not use (an attachment nobody read is the "delivered but unused" failure this manifest exists to prevent)."""
      s"""$ManifestHeader The dispatcher attached ${plan.size} file(s) to this brief. They are NOT part of the hand-written task text — hand-written paths in the task above are preserved verbatim and are not rewritten by this section. Each entry: the copy path to Read + its original path + type + image flag + byte count + sha256.
${lines.mkString("\n")}
$discipline"""

  /**
   * 派发回执的附件行（可观测面·工具侧）：件数 + 逐件「名字（类型，字节数）」+ 稳定副本
   * 目录。空计划 ⇒ 空串（未带附件的回执逐字节不变）。面板/台账另有 `attachments` 摘要
   * （见 `DelegateTool` 的 delegate-spawn 链行）。
   */
  def receiptLine(plan: Plan): String =
    if plan.isEmpty then ""
    else
      val items = plan.entries.map(e => s"${fileNameOf(e.readPath)} (${e.mime}, ${e.sizeBytes} B)")
      s"Attachments: ${plan.size} file(s) — ${items.mkString(", ")}; stable copies in ${plan.copyDir} " +
        "(sha256 verified against the originals; the brief carries the manifest section)."

  /**
   * 台账/面板可观测面的 JSON 摘要（`delegate-spawn` 链行的 `attachments` 键）：逐件
   * 「名字 / 字节数 / sha256 / MIME / 是否图片 / 副本路径」。读面（面板、ledger 查询）
   * 不需要再读盘——清单本身在 brief 里给模型。
   */
  def summaryJson(plan: Plan): Json =
    Json.arr(
      plan.entries.map { e =>
        Json.obj(
          "name" -> fileNameOf(e.readPath).asJson,
          "bytes" -> e.sizeBytes.asJson,
          "sha256" -> e.sha256.asJson,
          "mime" -> e.mime.asJson,
          "image" -> e.isImage.asJson,
          "copy" -> e.readPath.asJson,
          "original" -> e.original.asJson
        )
      }*
    )

  // ============================================================
  // 小工具（单点）
  // ============================================================
  /** 文件名（跨平台取最后一段；`\` 与 `/` 都算分隔符——Windows 盘路径也要正确）。 */
  def fileNameOf(p: String): String =
    val idx = math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'))
    val name = if idx >= 0 then p.substring(idx + 1) else p
    if name.isEmpty then "attachment" else name

  /** 目录名安全化（dispatchId 可能来自 taskId/会话 id；剔除分隔符与控制字符）。 */
  private def safeSegment(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '_' || c == '.' then c else '-').mkString
    cleaned.trim match
      case "" => "dispatch"
      case s => if s.length > 96 then s.take(96) else s

  /** 能否真开一次流（权限位可能骗人：ACL/只读卷）。 */
  private def canOpen(p: Path): Boolean =
    try
      val in = Files.newInputStream(p)
      try in.read()
      finally in.close()
      true
    catch case _: Exception => false

  /** 逐件 sha256（hex）——流式，1 GiB 附件也不整件进内存。 */
  def sha256File(path: Path): String =
    val md = MessageDigest.getInstance("SHA-256")
    val in = Files.newInputStream(path)
    try
      val buf = new Array[Byte](1 << 20)
      var n = in.read(buf)
      while n >= 0 do
        if n > 0 then md.update(buf, 0, n)
        n = in.read(buf)
    finally in.close()
    md.digest().map("%02x".format(_)).mkString

  /** 图片判定（与 Read 的图像分支同一后缀表 ⇒ 清单说 image=yes 就一定走视觉通道）。 */
  def isImageName(name: String): Boolean = nebflow.core.tools.ImageInject.imageMimeType(name).isDefined

  /**
   * MIME（按扩展名；未知 = application/octet-stream）。图片档沿用
   * [[nebflow.core.tools.ImageInject.imageMimeType]]（单点，不第二份后缀表）。
   */
  def mimeOf(name: String): String =
    nebflow.core.tools.ImageInject.imageMimeType(name).getOrElse {
      val lower = name.toLowerCase
      val dot = lower.lastIndexOf('.')
      val ext = if dot >= 0 then lower.substring(dot + 1) else ""
      ext match
        case "txt" => "text/plain"
        case "md" => "text/markdown"
        case "json" => "application/json"
        case "csv" => "text/csv"
        case "pdf" => "application/pdf"
        case "html" | "htm" => "text/html"
        case "xml" => "application/xml"
        case "yaml" | "yml" => "application/yaml"
        case "zip" => "application/zip"
        case "gz" | "tgz" => "application/gzip"
        case "mp4" => "video/mp4"
        case "mov" => "video/quicktime"
        case "mp3" => "audio/mpeg"
        case "wav" => "audio/wav"
        case "log" | "scala" | "java" | "py" | "js" | "ts" | "sh" | "css" | "conf" =>
          "text/plain"
        case _ => "application/octet-stream"
      end match
    }

end DelegateAttachments
