package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.shared.ContentBlock

import java.nio.file.{Files, Path, Paths}
import java.util.Base64

object ReadTool extends Tool:
  val MAX_LINE_COUNT = 2000
  val MAX_FILE_BYTES = 512 * 1024 // 512KB — approx 128k tokens, well within context
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

  /** Supported image extensions → MIME type mapping. */
  private val imageExtensions: Map[String, String] = Map(
    ".png" -> "image/png",
    ".jpg" -> "image/jpeg",
    ".jpeg" -> "image/jpeg",
    ".gif" -> "image/gif",
    ".webp" -> "image/webp",
    ".bmp" -> "image/bmp"
  )

  val name = "Read"

  /** Read controls its own output via the limit parameter — exempt from guard. */
  override val maxResultSizeChars: Int = Int.MaxValue

  val description =
    """Reads a file from the local filesystem. Reading a non-existent file returns an error, which is fine.

You can access any file on the machine. If the user provides a path, assume it is valid.

Parameters:
- file_path (required): Absolute path to the file.
- offset: Line number to start reading from (1-based). Defaults to 1.
- limit: Number of lines to read. Defaults to 2000 (the whole file if smaller).
- filter: Regex pattern to extract matching lines from large files (e.g. logs). Only matching lines are returned with their original line numbers preserved. offset/limit paginate the filtered results.

Image files (PNG, JPG, JPEG, GIF, WEBP, BMP) are returned as vision-ready image content — the LLM sees the actual image, not base64 text. SVG files are returned as text.

Output format (text files): cat -n style, with line numbers starting at 1, followed by a tab, then the line content.

Guidelines:
- Always read a file before editing it.
- For reasonably sized files, read the whole file rather than partial sections.
- Use filter for large log files instead of reading the entire file.
- Prefer Read over Bash (cat/head/tail) for all file reading."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> io.circe.Json.fromString("object"),
      "properties" -> io.circe.Json.obj(
        "file_path" -> io.circe.Json
          .obj("type" -> "string".asJson, "description" -> "The absolute path to the file to read".asJson),
        "offset" -> io.circe.Json
          .obj("type" -> "number".asJson, "description" -> "The line number to start reading from (1-based)".asJson),
        "limit" -> io.circe.Json.obj("type" -> "number".asJson, "description" -> "The number of lines to read".asJson),
        "filter" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Regex pattern to filter lines. Only matching lines are returned (with original line numbers). Useful for large log files. offset/limit paginate the filtered results.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("file_path".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val path = input("file_path").flatMap(_.asString).getOrElse("")
    val short = path.split("/").lastOption.getOrElse(path)
    val offset = input("offset").flatMap(_.asNumber).flatMap(_.toInt)
    val limit = input("limit").flatMap(_.asNumber).flatMap(_.toInt)
    val filter = input("filter").flatMap(_.asString)
    val params = List(
      offset.map(o => s"offset=$o"),
      limit.map(l => s"limit=$l"),
      filter.map(f => s"filter=$f")
    ).flatten.mkString(", ")
    val paramStr = if params.nonEmpty then s", $params" else ""
    s"""Read($short$paramStr)\n  ("$path")"""

  def summarizeResult(input: JsonObject, result: String): String =
    if result.startsWith("File does not exist") || result.startsWith("Error") then result
    else if result.startsWith("[image:") then result.linesIterator.nextOption().getOrElse(result)
    else if result.contains("showing") then
      val m = "showing (\\d+) of (\\d+) lines".r.findFirstMatchIn(result)
      m.map(m => s"${m.group(1)} of ${m.group(2)} lines").getOrElse(s"${result.split("\\n").length} lines")
    else s"${result.split("\\n").length} lines"

  /** Check if a file path has a supported image extension. Returns the MIME type if so. */
  private def imageMimeType(fileName: String): Option[String] =
    val lower = fileName.toLowerCase
    imageExtensions.collectFirst {
      case (ext, mime) if lower.endsWith(ext) => mime
    }

  /** Result of preparing an image for LLM injection. */
  private[tools] sealed trait ImagePrep
  /** Bytes ready to send — either the originals or a compressed JPEG re-encode. */
  private[tools] final case class Prepared(
    bytes: Array[Byte],
    mediaType: String,
    note: Option[String]
  ) extends ImagePrep
  /** Still above the post-compression cap — caller must surface an error. */
  private[tools] final case class TooLarge(detail: String) extends ImagePrep

  /**
   * Compress an image for LLM injection when oversized (long edge > 1920 or
   * bytes > 2MB), matching the frontend upload policy. Falls back to the
   * original bytes when the image cannot be decoded — a failed compression
   * must never block a read. GIF is exempt (JPEG re-encode would drop
   * animation); PNG transparency is flattened onto white (JPEG has no alpha).
   */
  private[tools] def prepareImage(bytes: Array[Byte], mediaType: String, fileName: String): ImagePrep =
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

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
    if !nebflow.core.PathUtil.isAbsolute(filePathStr) then
      IO.pure(Left(ToolError(s"Path must be absolute, got: $filePathStr")))
    else
      val filePath = Paths.get(filePathStr)
      val fileName = filePath.getFileName.toString

      imageMimeType(fileName) match
        case Some(mediaType) =>
          // Image file — return description; actual image bytes extracted by extractImages
          IO.blocking {
            if !Files.exists(filePath) then Left(ToolError(s"File does not exist: $filePath"))
            else if Files.isDirectory(filePath) then
              Left(
                ToolError(s"Path is a directory, not a file: $filePath. Use Bash with ls to list directory contents.")
              )
            else if Files.size(filePath) > MAX_IMAGE_BYTES then
              val sizeMb = Files.size(filePath).toDouble / 1024 / 1024
              Left(
                ToolError(
                  s"Image too large: $fileName (${f"$sizeMb%.1f"}MB, limit ${MAX_IMAGE_BYTES / 1024 / 1024}MB)."
                )
              )
            else
              val bytes = Files.readAllBytes(filePath)
              prepareImage(bytes, mediaType, fileName) match
                case TooLarge(detail) => Left(ToolError(detail))
                case Prepared(_, preparedMime, note) =>
                  val sizeKb = bytes.length.toDouble / 1024
                  val noteStr = note.fold("")(n => s" | $n")
                  Right(s"[image: $fileName | $preparedMime | ${f"$sizeKb%.0f"}KB$noteStr]")
          }.flatMap {
            case Right(desc) =>
              for _ <- ctx.readTracker.traverse_(_.recordRead(filePath, false))
              yield Right(desc)
            case Left(err) => IO.pure(Left(err))
          }
        case None =>
          // Text file (including SVG) — existing behavior
          readTextFile(input, filePath)
      end match
    end if
  end call

  private def readTextFile(input: JsonObject, filePath: Path): IO[Either[ToolError, String]] =
    IO.blocking {
      if !Files.exists(filePath) then Left(ToolError(s"File does not exist: $filePath"))
      else if Files.isDirectory(filePath) then
        Left(ToolError(s"Path is a directory, not a file: $filePath. Use Bash with ls to list directory contents."))
      else if Files.size(filePath) > MAX_FILE_BYTES then
        val sizeMb = Files.size(filePath).toDouble / 1024 / 1024
        val shortName = filePath.getFileName.toString
        Left(
          ToolError(
            s"File too large to read safely: $shortName (${f"$sizeMb%.1f"}MB, limit ${MAX_FILE_BYTES / 1024 / 1024}MB). " +
              s"Use offset/limit to read specific sections, or Bash with head/tail."
          )
        )
      else
        try
          val content = new String(Files.readAllBytes(filePath), java.nio.charset.StandardCharsets.UTF_8)
          val allLines = content.split("\\r?\\n").toList
          val filterOpt = input("filter").flatMap(_.asString).filter(_.nonEmpty)

          // If filter is provided, narrow to matching lines (preserving original line numbers)
          val (workingLines, workingIndices, filterInfo) = filterOpt match
            case Some(pattern) =>
              val regex = java.util.regex.Pattern.compile(pattern)
              val matched = allLines.zipWithIndex.filter { case (line, _) => regex.matcher(line).find() }
              (
                matched.map(_._1),
                matched.map(_._2),
                s", filter: \"$pattern\" — ${matched.length} match(es) in ${allLines.length} lines"
              )
            case None =>
              (allLines, allLines.indices.toList, "")

          val start = input("offset").flatMap(_.asNumber).flatMap(_.toInt).map(_ - 1).getOrElse(0)
          val end = input("limit").flatMap(_.asNumber).flatMap(_.toInt) match
            case Some(limit) => start + limit
            case None => Math.min(workingLines.length, start + MAX_LINE_COUNT)
          val selected = workingLines.slice(start, end)
          val selectedIndices = workingIndices.slice(start, end)

          val result = selected
            .zip(selectedIndices)
            .map { case (line, originalIdx) =>
              s"${originalIdx + 1}\t$line"
            }
            .mkString("\n")

          val totalLines = workingLines.length
          val showedLines = selected.length
          val isPartialView =
            start > 0 || (filterOpt.isEmpty && showedLines < allLines.length) || (filterOpt.isDefined && showedLines < workingLines.length)
          val suffix =
            if filterOpt.isDefined && showedLines < workingLines.length then
              s"\n\n(showing $showedLines of $totalLines matched lines$filterInfo)"
            else if filterOpt.isDefined then s"\n\n($totalLines matched lines$filterInfo)"
            else if showedLines < allLines.length then s"\n\n(showing $showedLines of ${allLines.length} lines)"
            else ""
          Right((result + suffix, isPartialView))
        catch case e: Exception => Left(ToolError(s"Error reading file: ${e.getMessage}"))
    }.flatMap {
      case Right((output, isPartialView)) =>
        IO.pure(Right(output))
      case Left(err) => IO.pure(Left(err))
    }

  /**
   * Extract image content blocks when the Read result is an image file.
   * Called by the execution pipeline after a successful call().
   */
  override def extractImages(input: JsonObject, result: String): Option[List[ContentBlock.Image]] =
    if !result.startsWith("[image:") then None
    else
      val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
      val filePath = Paths.get(filePathStr)
      val fileName = filePath.getFileName.toString
      imageMimeType(fileName).flatMap { mediaType =>
        try
          val bytes = Files.readAllBytes(filePath)
          prepareImage(bytes, mediaType, fileName) match
            // Mirror of call()'s decision — call() has already surfaced the
            // TooLarge error, so here it degrades to no image blocks.
            case TooLarge(_) => None
            case Prepared(prepared, preparedMime, _) =>
              val base64Data = Base64.getEncoder.encodeToString(prepared)
              Some(List(ContentBlock.Image(base64Data, preparedMime)))
        catch case _: Exception => None
      }

end ReadTool
