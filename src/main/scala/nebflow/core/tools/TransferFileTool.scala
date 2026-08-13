package nebflow.core.tools

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.neblink.{NeblinkClient, PeerInfo}
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Transfers files between devices without routing file content through the LLM context.
 *
 * Supports four modes:
 *   - Local → Remote: read local file, push to remote device
 *   - Remote → Local: pull file from remote device, write locally
 *   - Remote → Remote: pull from source, push to target
 *   - Local → Local: simple file copy
 */
object TransferFileTool extends Tool:
  val name = "TransferFile"

  val description =
    """Transfer a file between two NebLink-connected devices without routing file content through the LLM context.
       Supports: local → remote, remote → local, remote → remote, local → local.
       Use this when you need to move a file (code, data, binary) between devices quickly."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        "sourceDevice" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Source device name. Omit or set to 'local' for the current device.".asJson
        ),
        "sourcePath" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Path to the file on the source device, relative to the project root.".asJson
        ),
        "targetDevice" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Target device name. Omit or set to 'local' for the current device.".asJson
        ),
        "targetPath" -> Json.obj(
          "type" -> "string".asJson,
          "description" -> "Path for the file on the target device. Defaults to sourcePath if omitted.".asJson
        ),
        "overwrite" -> Json.obj(
          "type" -> "boolean".asJson,
          "description" -> "Overwrite the target file if it already exists. Default: false.".asJson
        )
      ),
      "required" -> Json.arr("sourcePath".asJson, "targetDevice".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val src = input("sourceDevice").flatMap(_.asString).filter(_.nonEmpty).getOrElse("local")
    val srcPath = input("sourcePath").flatMap(_.asString).getOrElse("?")
    val tgt = input("targetDevice").flatMap(_.asString).getOrElse("local")
    val tgtPath = input("targetPath").flatMap(_.asString).getOrElse(srcPath)
    s"TransferFile($src:$srcPath → $tgt:$tgtPath)"

  def summarizeResult(input: JsonObject, result: String): String =
    val preview = if result.length > 120 then result.take(117) + "..." else result
    preview

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val sourceDevice = input("sourceDevice").flatMap(_.asString).filter(_.nonEmpty).getOrElse("local")
    val sourcePath = input("sourcePath").flatMap(_.asString).getOrElse("")
    val targetDevice = input("targetDevice").flatMap(_.asString).getOrElse("local")
    val targetPath = input("targetPath").flatMap(_.asString).filter(_.nonEmpty).getOrElse(sourcePath)
    val overwrite = input("overwrite").flatMap(_.asBoolean).getOrElse(false)

    if sourcePath.isEmpty then IO.pure(Left(ToolError("sourcePath is required")))
    else if targetPath.isEmpty then IO.pure(Left(ToolError("targetPath cannot be empty after resolving defaults")))
    else
      ctx.sharedResources.flatMap(_.neblinkService) match
        case None =>
          IO.pure(
            Left(ToolError("NebLink not available — start nebflow on both devices and ensure they are connected"))
          )
        case Some(ns) =>
          ns.peers.flatMap { peers =>
            val isLocal = (s: String) => s.equalsIgnoreCase("local")
            val srcIsLocal = isLocal(sourceDevice)
            val tgtIsLocal = isLocal(targetDevice)

            (srcIsLocal, tgtIsLocal) match
              case (true, true) =>
                // Local → Local: simple file copy
                transferLocalToLocal(sourcePath, targetPath, overwrite, ns)

              case (true, false) =>
                // Local → Remote: read file, push to remote
                resolveDevice(targetDevice, peers) match
                  case Left(err) => IO.pure(Left(err))
                  case Right(peer) => transferLocalToRemote(sourcePath, targetPath, overwrite, peer, ns)

              case (false, true) =>
                // Remote → Local: pull from remote, write locally
                resolveDevice(sourceDevice, peers) match
                  case Left(err) => IO.pure(Left(err))
                  case Right(peer) => transferRemoteToLocal(sourcePath, targetPath, overwrite, peer, ns)

              case (false, false) =>
                // Remote → Remote: pull from source, push to target
                (resolveDevice(sourceDevice, peers), resolveDevice(targetDevice, peers)) match
                  case (Left(err), _) => IO.pure(Left(err))
                  case (_, Left(err)) => IO.pure(Left(err))
                  case (Right(srcPeer), Right(tgtPeer)) =>
                    transferRemoteToRemote(sourcePath, targetPath, overwrite, srcPeer, tgtPeer, ns)
            end match
          }
    end if

  end call

  // ===== Transfer modes =====

  private def transferLocalToLocal(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      if srcPath == tgtPath then
        // Same path — this is a no-op (can't copy a file to itself unless overwrite is explicitly different)
        Left(ToolError("Source and target path are the same on the same device — nothing to do"))
      else
        val src = os.pwd / os.RelPath(srcPath)
        if !os.exists(src) then Left(ToolError(s"Source file not found: $srcPath"))
        else if !os.isFile(src) then Left(ToolError(s"Not a file: $srcPath"))
        else
          val tgt = os.pwd / os.RelPath(tgtPath)
          if !overwrite && os.exists(tgt) then
            Left(ToolError(s"Target file already exists: $tgtPath (use overwrite=true to replace)"))
          else
            os.copy(src, tgt, replaceExisting = overwrite, createFolders = true)
            Right(s"Copied $srcPath → $tgtPath (${os.size(src)} bytes)")
    }.handleErrorWith { e =>
      IO.pure(Left(ToolError(s"Local copy failed: ${e.getMessage}")))
    }

  private def transferLocalToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    p2pTransferLocalToRemote(srcPath, tgtPath, overwrite, peer, ns).flatMap {
      case r @ Right(_) => IO.pure(r)
      case Left(p2pErr) =>
        ns.relayClientOpt match
          case Some(client) =>
            relayTransferLocalToRemote(srcPath, tgtPath, overwrite, peer, client)
          case None => IO.pure(Left(p2pErr))
    }

  private def p2pTransferLocalToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val src = os.pwd / os.RelPath(srcPath)
      if !os.exists(src) then Left(ToolError(s"Source file not found on local device: $srcPath"))
      else if !os.isFile(src) then Left(ToolError(s"Not a file: $srcPath"))
      else
        val content = os.read.bytes(src)
        val b64 = java.util.Base64.getEncoder.encodeToString(content)
        val size = content.length

        val body = Json.obj(
          "path" -> tgtPath.asJson,
          "content" -> b64.asJson,
          "overwrite" -> overwrite.asJson
        )

        val resp = basicRequest
          .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/transfer"))
          .contentType("application/json")
          .body(body.noSpaces)
          .readTimeout(60.seconds)
          .response(asStringAlways)
          .send(ns.httpBackend)

        if !resp.code.isSuccess then
          Left(ToolError(s"Remote device returned HTTP ${resp.code}: ${resp.body.take(200)}"))
        else
          io.circe.parser.parse(resp.body) match
            case Right(json) =>
              val ok = json.hcursor.downField("ok").as[Boolean].getOrElse(false)
              if ok then
                val rmtSize = json.hcursor.downField("size").as[Long].getOrElse(size.toLong)
                Right(s"Transferred $srcPath ($rmtSize bytes) → ${peer.deviceName}:$tgtPath")
              else
                val err = json.hcursor.downField("error").as[String].getOrElse("unknown error")
                Left(ToolError(s"Remote transfer failed: $err"))
            case Left(err) =>
              Left(ToolError(s"Invalid response from remote: ${err.getMessage}"))
      end if
    }.handleErrorWith { e =>
      IO.pure(Left(ToolError(s"Cannot transfer to ${peer.deviceName}: ${e.getMessage}")))
    }

  private def transferRemoteToLocal(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    p2pTransferRemoteToLocal(srcPath, tgtPath, overwrite, peer, ns).flatMap {
      case r @ Right(_) => IO.pure(r)
      case Left(p2pErr) =>
        ns.relayClientOpt match
          case Some(client) =>
            relayTransferRemoteToLocal(srcPath, tgtPath, overwrite, peer, client)
          case None => IO.pure(Left(p2pErr))
    }

  private def p2pTransferRemoteToLocal(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val encodedPath = java.net.URLEncoder.encode(srcPath, "UTF-8")
      val resp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${peer.address}/api/neblink/transfer?path=$encodedPath"))
        .readTimeout(60.seconds)
        .response(asStringAlways)
        .send(ns.httpBackend)

      if resp.code.isSuccess then
        io.circe.parser.parse(resp.body) match
          case Right(json) =>
            val contentB64 = json.hcursor.downField("content").as[String].getOrElse("")
            val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
            if contentB64.isEmpty then Left(ToolError(s"Empty response from ${peer.deviceName} for $srcPath"))
            else
              val content = java.util.Base64.getDecoder.decode(contentB64)
              val tgt = os.pwd / os.RelPath(tgtPath)
              if !overwrite && os.exists(tgt) then
                Left(ToolError(s"Target file already exists: $tgtPath (use overwrite=true to replace)"))
              else
                os.write(tgt, content, createFolders = true)
                Right(s"Transferred ${peer.deviceName}:$srcPath ($size bytes) → $tgtPath")
          case Left(err) =>
            Left(ToolError(s"Invalid response from ${peer.deviceName}: ${err.getMessage}"))
      else if resp.code.code == 404 then Left(ToolError(s"File not found on ${peer.deviceName}: $srcPath"))
      else Left(ToolError(s"Remote device returned HTTP ${resp.code}: ${resp.body.take(200)}"))
      end if
    }.handleErrorWith { e =>
      IO.pure(Left(ToolError(s"Cannot fetch from ${peer.deviceName}: ${e.getMessage}")))
    }

  private def transferRemoteToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    srcPeer: PeerInfo,
    tgtPeer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    p2pTransferRemoteToRemote(srcPath, tgtPath, overwrite, srcPeer, tgtPeer, ns).flatMap {
      case r @ Right(_) => IO.pure(r)
      case Left(p2pErr) =>
        ns.relayClientOpt match
          case Some(client) =>
            relayTransferRemoteToRemote(srcPath, tgtPath, overwrite, srcPeer, tgtPeer, client)
          case None => IO.pure(Left(p2pErr))
    }

  private def p2pTransferRemoteToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    srcPeer: PeerInfo,
    tgtPeer: PeerInfo,
    ns: nebflow.neblink.NeblinkService
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val encodedSrc = java.net.URLEncoder.encode(srcPath, "UTF-8")
      // 1. Pull from source
      val getResp = basicRequest
        .get(sttp.model.Uri.unsafeParse(s"${srcPeer.address}/api/neblink/transfer?path=$encodedSrc"))
        .readTimeout(60.seconds)
        .response(asStringAlways)
        .send(ns.httpBackend)

      if !getResp.code.isSuccess then
        Left(ToolError(s"Source device returned HTTP ${getResp.code}: ${getResp.body.take(200)}"))
      else
        val json = io.circe.parser.parse(getResp.body).toOption.getOrElse(Json.Null)
        val contentB64 = json.hcursor.downField("content").as[String].getOrElse("")
        if contentB64.isEmpty then Left(ToolError(s"Empty response from source ${srcPeer.deviceName}"))
        else
          // 2. Push to target
          val pushBody = Json.obj(
            "path" -> tgtPath.asJson,
            "content" -> contentB64.asJson,
            "overwrite" -> overwrite.asJson
          )

          val pushResp = basicRequest
            .post(sttp.model.Uri.unsafeParse(s"${tgtPeer.address}/api/neblink/transfer"))
            .contentType("application/json")
            .body(pushBody.noSpaces)
            .readTimeout(60.seconds)
            .response(asStringAlways)
            .send(ns.httpBackend)

          if !pushResp.code.isSuccess then
            Left(ToolError(s"Target device returned HTTP ${pushResp.code}: ${pushResp.body.take(200)}"))
          else
            val pushJson = io.circe.parser.parse(pushResp.body).toOption.getOrElse(Json.Null)
            val ok = pushJson.hcursor.downField("ok").as[Boolean].getOrElse(false)
            if ok then
              val size = json.hcursor.downField("size").as[Long].getOrElse(0L)
              Right(s"Transferred ${srcPeer.deviceName}:$srcPath ($size bytes) → ${tgtPeer.deviceName}:$tgtPath")
            else
              val err = pushJson.hcursor.downField("error").as[String].getOrElse("unknown error")
              Left(ToolError(s"Target transfer failed: $err"))
        end if
      end if
    }.handleErrorWith { e =>
      IO.pure(Left(ToolError(s"Remote-to-remote transfer failed: ${e.getMessage}")))
    }

  // ===== Relay fallback methods =====

  private def relayTransferLocalToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    client: NeblinkClient
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val src = os.pwd / os.RelPath(srcPath)
      if !os.exists(src) then Left(ToolError(s"Source file not found: $srcPath"))
      else
        val content = os.read.bytes(src)
        val b64 = java.util.Base64.getEncoder.encodeToString(content)
        Right((b64, content.length))
    }.flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right((b64, size)) =>
        client.relayTransferPut(peer.deviceId, tgtPath, b64, overwrite).map {
          case Right(remoteSize) =>
            Right(s"Transferred $srcPath ($remoteSize bytes) → ${peer.deviceName}:$tgtPath (via relay)")
          case Left(err) =>
            Left(ToolError(s"P2P and relay both failed. Relay: $err"))
        }
    }

  private def relayTransferRemoteToLocal(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    peer: PeerInfo,
    client: NeblinkClient
  ): IO[Either[ToolError, String]] =
    client.relayTransferGet(peer.deviceId, srcPath).flatMap {
      case Right((contentB64, size)) =>
        IO.blocking {
          val content = java.util.Base64.getDecoder.decode(contentB64)
          val tgt = os.pwd / os.RelPath(tgtPath)
          if !overwrite && os.exists(tgt) then
            Left(ToolError(s"Target file already exists: $tgtPath (use overwrite=true to replace)"))
          else
            os.write(tgt, content, createFolders = true)
            Right(s"Transferred ${peer.deviceName}:$srcPath ($size bytes) → $tgtPath (via relay)")
        }.handleErrorWith(e => IO.pure(Left(ToolError(s"Relay write failed: ${e.getMessage}"))))
      case Left(err) => IO.pure(Left(ToolError(s"P2P and relay both failed. Relay: $err")))
    }

  private def relayTransferRemoteToRemote(
    srcPath: String,
    tgtPath: String,
    overwrite: Boolean,
    srcPeer: PeerInfo,
    tgtPeer: PeerInfo,
    client: NeblinkClient
  ): IO[Either[ToolError, String]] =
    client.relayTransferGet(srcPeer.deviceId, srcPath).flatMap {
      case Right((contentB64, size)) =>
        client.relayTransferPut(tgtPeer.deviceId, tgtPath, contentB64, overwrite).map {
          case Right(remoteSize) =>
            Right(
              s"Transferred ${srcPeer.deviceName}:$srcPath ($size bytes) → ${tgtPeer.deviceName}:$tgtPath (via relay)"
            )
          case Left(err) =>
            Left(ToolError(s"Relay put to target failed: $err"))
        }
      case Left(err) => IO.pure(Left(ToolError(s"Relay get from source failed: $err")))
    }

  // ===== Helpers =====

  private def resolveDevice(deviceName: String, peers: List[PeerInfo]): Either[ToolError, PeerInfo] =
    peers.find(p =>
      p.deviceName.equalsIgnoreCase(deviceName) ||
        p.deviceId.startsWith(deviceName) ||
        p.deviceName.toLowerCase.contains(deviceName.toLowerCase)
    ) match
      case Some(p) => Right(p)
      case None =>
        val available = peers.map(_.deviceName)
        Left(
          ToolError(
            if peers.isEmpty then
              s"No peer devices discovered. Ensure NebLink Server is configured on both machines and both Nebflow instances are connected."
            else s"Device '$deviceName' not found among ${peers.size} peer(s). Available: ${available.mkString(", ")}"
          )
        )

end TransferFileTool
