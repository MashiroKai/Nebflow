package nebflow.core

import cats.effect.IO
import cats.syntax.all.*
import nebflow.shared.*

import java.nio.file.{Files, Paths}

object Repl:
  private val IMAGE_EXTENSIONS = List(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".svg")
  private val VIDEO_EXTENSIONS = List(".mp4", ".mov", ".avi", ".mkv", ".webm", ".flv", ".wmv", ".m4v")
  private val MEDIA_EXTENSIONS = IMAGE_EXTENSIONS ++ VIDEO_EXTENSIONS

  private val MEDIA_REGEX =
    s"(?:^|\\s)((?:/[^\\s]+|[~.][^\\s]+)\\.(?:${MEDIA_EXTENSIONS.map(_.drop(1)).mkString("|")}))".r

  private val MIME_MAP = Map(
    "png" -> "image/png",
    "jpg" -> "image/jpeg",
    "jpeg" -> "image/jpeg",
    "gif" -> "image/gif",
    "webp" -> "image/webp",
    "bmp" -> "image/bmp",
    "svg" -> "image/svg+xml",
    "mp4" -> "video/mp4",
    "mov" -> "video/quicktime",
    "avi" -> "video/x-msvideo",
    "mkv" -> "video/x-matroska",
    "webm" -> "video/webm",
    "flv" -> "video/x-flv",
    "wmv" -> "video/x-ms-wmv",
    "m4v" -> "video/x-m4v"
  )

  private val MAX_IMAGE_SIZE = 5 * 1024 * 1024 // 5MB

  def replaceMediaPaths(input: String): String =
    val matches = MEDIA_REGEX.findAllMatchIn(input).toList
    if matches.isEmpty then input
    else
      var result = ""
      var lastEnd = 0
      matches.zipWithIndex.foreach { case (m, i) =>
        val path = m.group(1)
        val fullMatch = m.matched
        val pathIndexInMatch = fullMatch.indexOf(path)
        val pathStart = m.start + pathIndexInMatch
        result += input.substring(lastEnd, pathStart)
        result += s"[media ${i + 1}]"
        lastEnd = pathStart + path.length
      }
      result + input.substring(lastEnd)

  end replaceMediaPaths

  def buildUserMessage(input: String): IO[Message] = IO.blocking { // public for AgentActor
    val matches = MEDIA_REGEX.findAllMatchIn(input).toList
    if matches.isEmpty then Message(MessageRole.User, Left(input))
    else
      val imageBlocks = scala.collection.mutable.ListBuffer.empty[ContentBlock.Image]
      val mediaItems = scala.collection.mutable.ListBuffer.empty[String]

      matches.foreach { m =>
        val filePathStr = m.group(1).replaceFirst("^~", sys.props.getOrElse("user.home", "~"))
        val ext = filePathStr.split("\\.").lastOption.map(_.toLowerCase).getOrElse("")
        val filePath = Paths.get(filePathStr)

        if !Files.exists(filePath) || !Files.isRegularFile(filePath) then mediaItems += s"Not a file: $filePathStr"
        else if VIDEO_EXTENSIONS.exists(filePathStr.toLowerCase.endsWith) then
          val sizeKb = (Files.size(filePath) / 1024).toInt
          mediaItems += s"Video: $filePathStr (${sizeKb}KB)"
        else if Files.size(filePath) > MAX_IMAGE_SIZE then
          val sizeMb = Files.size(filePath).toDouble / 1024 / 1024
          mediaItems += s"Image too large: $filePathStr (${sizeMb}%.1fMB > 5MB)"
        else
          val mediaType = MIME_MAP.getOrElse(ext, "application/octet-stream")
          val bytes = Files.readAllBytes(filePath)
          val base64 = java.util.Base64.getEncoder.encodeToString(bytes)
          val sizeKb = (bytes.length / 1024).toInt
          mediaItems += s"Image: $filePathStr (${sizeKb}KB, $mediaType)"
          imageBlocks += ContentBlock.Image(base64, mediaType)
      }

      val displayInput = replaceMediaPaths(input)
      val blocks = ContentBlock.Text(displayInput) :: imageBlocks.toList
      Message(MessageRole.User, Right(blocks))
    end if
  }

  def loadSystemPrompt(): String = // public for AgentActor
    val path = os.home / ".nebflow" / "agents" / "Nebula" / "system.md"
    if os.exists(path) then os.read(path) else ""

  /**
   * Static environment info — injected once into system prompt, never updated per-turn.
   * Git state is intentionally omitted; agents should use Bash to run git commands on demand.
   */
  def buildEnvInfo(projectRoot: String, chatWidth: Int = 0, meshInfo: String = ""): String =
    val sb = new StringBuilder
    sb.append("## Environment\n\n")
    sb.append("| Property | Value |\n")
    sb.append("|----------|-------|\n")
    sb.append(s"| Working directory | `$projectRoot` |\n")
    sb.append(s"| Platform | ${sys.props.getOrElse("os.name", "unknown").toLowerCase} |\n")
    sb.append(s"| Shell | ${sys.env.getOrElse("SHELL", "unknown")} |\n")
    sb.append(s"| OS Version | ${sys.props.getOrElse("os.name", "")} ${sys.props.getOrElse("os.version", "")} |\n")
    sb.append(s"| Nebflow version | v${nebflow.Version.string} |\n")
    // PID and port: expose identity so agents avoid killing themselves or sibling instances
    val pid = sys.props.getOrElse("nebflow.gateway.pid",
      java.lang.ProcessHandle.current().pid().toString)
    val port = sys.props.getOrElse("nebflow.gateway.port",
      sys.env.getOrElse("NEBFLOW_GATEWAY_PORT", "8080"))
    sb.append(s"| PID | $pid |\n")
    sb.append(s"| Gateway port | $port |\n")
    if chatWidth > 0 then sb.append(s"| Chat width | ~${chatWidth}px |\n")
    if meshInfo.nonEmpty then sb.append(meshInfo)
    sb.toString
  end buildEnvInfo
end Repl
