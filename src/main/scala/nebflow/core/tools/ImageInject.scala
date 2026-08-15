package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.ContentBlock

import java.nio.file.{Files, Paths}
import java.util.Base64

/**
 * Shared image-preparation pipeline for LLM injection.
 *
 * Two consumers:
 *  - ReadTool — image files read via Read (G6 compression lives here since G3)
 *  - Mail/Delegate/SubTask `images` attachment channel (G3) — send-time
 *    validation + queue-drain re-resolution
 *
 * All path guards mirror ReadTool exactly: absolute path, exists, not a
 * directory, extension whitelist, 10MB pre-compress cap. Compression matches
 * the frontend upload policy (input.js compressImage).
 */
object ImageInject:

  val MAX_IMAGE_BYTES = 10 * 1024 * 1024 // 10MB — images can be larger than text files

  // Image compression — mirrors the frontend policy (input.js compressImage):
  // long edge <= 1920, JPEG quality 0.8. Applied when the long edge exceeds
  // 1920px OR the raw bytes exceed 2MB (covers small-but-heavy images).
  val COMPRESS_MAX_EDGE = 1920
  val COMPRESS_TRIGGER_BYTES = 2 * 1024 * 1024
  // Post-compression hard cap (GLM single-image limit). Above this even after
  // downsampling, reading is rejected with guidance instead of sending a
  // request the provider will refuse.
  val POST_COMPRESS_MAX_BYTES = 5 * 1024 * 1024

  /** Max images per Mail/Delegate/SubTask call (token budget: 5 × ~1500 tok). */
  val MAX_ATTACHMENTS = 5

  /** Supported image extensions → MIME type mapping. */
  val imageExtensions: Map[String, String] = Map(
    ".png" -> "image/png",
    ".jpg" -> "image/jpeg",
    ".jpeg" -> "image/jpeg",
    ".gif" -> "image/gif",
    ".webp" -> "image/webp",
    ".bmp" -> "image/bmp"
  )

  /** Check if a file path has a supported image extension. Returns the MIME type if so. */
  def imageMimeType(fileName: String): Option[String] =
    val lower = fileName.toLowerCase
    imageExtensions.collectFirst {
      case (ext, mime) if lower.endsWith(ext) => mime
    }

  /** Result of preparing an image for LLM injection. */
  sealed trait ImagePrep
  /** Bytes ready to send — either the originals or a compressed JPEG re-encode. */
  final case class Prepared(
    bytes: Array[Byte],
    mediaType: String,
    note: Option[String]
  ) extends ImagePrep
  /** Still above the post-compression cap — caller must surface an error. */
  final case class TooLarge(detail: String) extends ImagePrep

  /**
   * Compress an image for LLM injection when oversized (long edge > 1920 or
   * bytes > 2MB), matching the frontend upload policy. Falls back to the
   * original bytes when the image cannot be decoded — a failed compression
   * must never block a read. GIF is exempt (JPEG re-encode would drop
   * animation); PNG transparency is flattened onto white (JPEG has no alpha).
   */
  def prepareImage(bytes: Array[Byte], mediaType: String, fileName: String): ImagePrep =
    if mediaType == "image/gif" then Prepared(bytes, mediaType, None) // GIF: JPEG re-encode loses animation
    else
      try
        val src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))
        if src == null then
          // Undecodable by ImageIO (e.g. webp without a plugin) — inject original
          Prepared(bytes, mediaType, None)
        else
          val longSide = math.max(src.getWidth, src.getHeight)
          val needByDim = longSide > COMPRESS_MAX_EDGE
          val needByBytes = bytes.length > COMPRESS_TRIGGER_BYTES
          if !needByDim && !needByBytes then Prepared(bytes, mediaType, None)
          else
            // Never upscale: a small-but-heavy image only needs the JPEG re-encode
            val scale = math.min(1.0, COMPRESS_MAX_EDGE.toDouble / longSide)
            val w = math.max(1, (src.getWidth * scale).round.toInt)
            val h = math.max(1, (src.getHeight * scale).round.toInt)
            // TYPE_INT_RGB + white fill: JPEG has no alpha channel; a PNG with
            // transparency would otherwise render its transparent pixels black
            val out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            try
              g.setColor(java.awt.Color.WHITE)
              g.fillRect(0, 0, w, h)
              g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
              )
              g.drawImage(src, 0, 0, w, h, null)
            finally g.dispose()
            val encoded = encodeJpeg(out, 0.8f)
            if encoded.length > POST_COMPRESS_MAX_BYTES then
              TooLarge(
                s"$fileName is still ${f"${encoded.length / 1024.0 / 1024.0}%.1f"}MB after compression " +
                  s"(${longSide}px → ${math.max(w, h)}px JPEG). Limit is ${POST_COMPRESS_MAX_BYTES / 1024 / 1024}MB. " +
                  s"Please downscale or re-save the image manually, then Read it again."
              )
            else
              val note = s"compressed ${bytes.length / 1024}KB→${encoded.length / 1024}KB, ${longSide}px→${math.max(w, h)}px"
              Prepared(encoded, "image/jpeg", Some(note))
      catch case _: Exception =>
        // Compression failed for any reason — inject the original bytes
        Prepared(bytes, mediaType, None)

  /** Encode a BufferedImage as JPEG at the given quality (0..1). */
  private def encodeJpeg(img: java.awt.image.BufferedImage, quality: Float): Array[Byte] =
    val baos = new java.io.ByteArrayOutputStream()
    val writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next()
    try
      val param = writer.getDefaultWriteParam()
      param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT)
      param.setCompressionQuality(quality)
      writer.setOutput(new javax.imageio.stream.MemoryCacheImageOutputStream(baos))
      writer.write(null, new javax.imageio.IIOImage(img, null, null), param)
    finally writer.dispose()
    baos.toByteArray

  // ============================================================
  // G3: Mail/Delegate/SubTask attachment pipeline
  // ============================================================

  /** Dual-channel path label — aligned with the frontend's [用户附加图片: ...] convention. */
  def attachmentLabel(path: String): String = s"[Mail 附件图片: $path]"

  /** Windows drive-letter path (C:\... or C:/...) — a remote-device file, never local-absolute on the sender side. */
  private val WindowsDrivePath = "^[A-Za-z]:[\\\\/]".r

  /**
   * Path that references another machine's filesystem (Windows drive letter,
   * UNC share, or device URL). On a Windows sender such paths may be local —
   * the flag only enriches the not-found error with guidance, never blocks.
   */
  private def looksRemote(path: String): Boolean =
    WindowsDrivePath.findFirstIn(path).isDefined || path.startsWith("\\\\") || path.contains("://")

  /**
   * Parse and sanity-check the `images` parameter shared by Mail/Delegate/SubTask.
   * Fails fast on >MAX_ATTACHMENTS entries before any file IO. Entries must be
   * strings; blank entries are dropped.
   */
  def parseImagesParam(input: io.circe.JsonObject): Either[ToolError, List[String]] =
    val raw = input("images") match
      case Some(arr) =>
        arr.asArray match
          case Some(items) =>
            items.flatMap(_.asString) match
              case strings if strings.size == items.size => Right(strings.filter(_.nonEmpty).toList)
              case _ => Left(ToolError("images must be an array of file path strings."))
          case None => Left(ToolError("images must be an array of file path strings."))
      case None => Right(Nil)
    raw.flatMap(paths =>
      if paths.size > MAX_ATTACHMENTS then
        Left(ToolError(s"Too many image attachments: ${paths.size} (max $MAX_ATTACHMENTS per message)."))
      else Right(paths)
    )

  /**
   * Resolve image attachment paths at send time. Fail-fast: any invalid path
   * aborts the whole call (nothing is sent), mirroring ReadTool's guards.
   */
  def resolveImages(paths: List[String]): IO[Either[ToolError, List[ContentBlock]]] =
    IO.blocking(
      paths.traverse(loadOne).map(_.flatten)
    )

  /** Load one attachment into dual-channel blocks, or a descriptive error. */
  private def loadOne(path: String): Either[ToolError, List[ContentBlock]] =
    if !nebflow.core.PathUtil.isAbsolute(path) then
      Left(ToolError(s"Attachment path must be absolute, got: '$path'."))
    else
      val filePath = Paths.get(path)
      // Existence/directory first — a missing or wrong-kind path is the more
      // fundamental error than its extension. A remote-looking path that does
      // not resolve locally gets D2's explicit guidance.
      if !Files.exists(filePath) then
        val hint =
          if looksRemote(path) then
            " — remote device paths are not supported as attachments. Copy the file to this machine first (e.g. TransferFile), or reference the path in your message text."
          else ""
        Left(ToolError(s"Attachment does not exist: $path$hint"))
      else if Files.isDirectory(filePath) then Left(ToolError(s"Attachment is a directory, not a file: $path"))
      else
        val fileName = filePath.getFileName.toString
        imageMimeType(fileName) match
          case None =>
            Left(
              ToolError(
                s"Unsupported attachment type for '$fileName' — images only (PNG/JPG/JPEG/GIF/WEBP/BMP). " +
                  "For other files, reference the path in your message text."
              )
            )
          case Some(mediaType) =>
            if Files.size(filePath) > MAX_IMAGE_BYTES then
              val sizeMb = Files.size(filePath).toDouble / 1024 / 1024
              Left(ToolError(s"Attachment too large: $fileName (${f"$sizeMb%.1f"}MB, limit ${MAX_IMAGE_BYTES / 1024 / 1024}MB)."))
            else
              try
                val bytes = Files.readAllBytes(filePath)
                prepareImage(bytes, mediaType, fileName) match
                  case TooLarge(detail) => Left(ToolError(detail))
                  case Prepared(prepared, preparedMime, _) =>
                    Right(List(
                      ContentBlock.Text(attachmentLabel(path)),
                      ContentBlock.Image(Base64.getEncoder.encodeToString(prepared), preparedMime)
                    ))
              catch case e: Exception =>
                Left(ToolError(s"Failed to read attachment '$path': ${e.getMessage}"))

  /**
   * Re-resolve persisted attachment paths at queue drain time (D6: the queue
   * persists paths, not base64). Failures degrade to a placeholder — the mail
   * itself must still be delivered (send-time validation already ran).
   */
  def drainImagePaths(paths: List[String]): IO[List[ContentBlock]] =
    IO.blocking(paths.flatMap(p => loadOne(p).getOrElse(List(ContentBlock.Text(s"[attachment lost: $p]")))))

  /**
   * Build the blocks for a message with attachments. CRITICAL: UserInput drops
   * the `text` parameter when blocks are present (AgentActor builds
   * Message(User, Right(blocks))) — the message text MUST be the first Text
   * block or it is silently lost. Returns None when there are no attachments
   * (plain string message, zero behavior change).
   */
  def messageBlocks(message: String, attachments: List[ContentBlock]): Option[List[ContentBlock]] =
    if attachments.isEmpty then None else Some(ContentBlock.Text(message) :: attachments)

end ImageInject
