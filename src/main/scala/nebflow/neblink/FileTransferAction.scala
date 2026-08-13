package nebflow.neblink

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.core.PathUtil

/**
 * Handler for the "FileTransfer" action — direct file IO that bypasses ToolRegistry.
 *
 * Used by both the relay tunnel (NeblinkRelayTunnel.handleRelayRequest) and the
 * P2P remote-exec endpoint (RestApiRoutes) so cross-network file transfers work
 * via relay when the direct P2P HTTP endpoints are unreachable.
 *
 * Params:
 *   - direction: "get" (read file, return base64) or "put" (write base64 to file)
 *   - path: file path (relative to project root, or absolute after tilde expansion)
 *   - content: base64-encoded content (required for "put")
 *   - overwrite: whether to overwrite existing file (default false)
 */
object FileTransferAction:

  def handle(params: JsonObject): IO[Either[String, Json]] =
    val direction = params("direction").flatMap(_.asString).getOrElse("get")
    val pathStr = params("path").flatMap(_.asString).getOrElse("")
    val contentB64 = params("content").flatMap(_.asString).getOrElse("")
    val overwrite = params("overwrite").flatMap(_.asBoolean).getOrElse(false)

    if pathStr.isEmpty then IO.pure(Left("Missing path"))
    else
      val expanded = PathUtil.expandTilde(pathStr)
      val pathEither =
        if PathUtil.isAbsolute(expanded) then
          try Right(PathUtil.resolvePath(expanded))
          catch case e: Exception => Left(s"Invalid path: $expanded")
        else
          try Right(os.pwd / os.RelPath(expanded))
          catch case e: Exception => Left(s"Invalid path: $expanded")

      pathEither match
        case Left(err) => IO.pure(Left(err))
        case Right(path) =>
          IO.blocking {
            direction match
              case "get" =>
                if !os.exists(path) || !os.isFile(path) then Left(s"File not found: $pathStr")
                else
                  val content = os.read.bytes(path)
                  val b64 = java.util.Base64.getEncoder.encodeToString(content)
                  Right(Json.obj("content" -> b64.asJson, "size" -> content.length.asJson))
              case "put" =>
                if contentB64.isEmpty then Left("Missing content for put")
                else
                  val content = java.util.Base64.getDecoder.decode(contentB64)
                  if !overwrite && os.exists(path) then Left(s"File exists: $pathStr (use overwrite)")
                  else
                    os.write(path, content, createFolders = true)
                    Right(Json.obj("size" -> content.length.asJson))
              case other => Left(s"Unknown direction: $other")
          }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

end FileTransferAction
