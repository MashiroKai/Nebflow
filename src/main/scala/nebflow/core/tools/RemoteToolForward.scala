package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import io.circe.parser.decode
import nebflow.mesh.MeshService
import sttp.client4.*

/**
 * Helper for forwarding tool calls to remote Nebflow devices.
 * Used by Bash/Read/Write/Edit/Glob/Grep when `device` parameter is set.
 */
object RemoteToolForward:

  private var meshServiceOpt: Option[MeshService] = None
  private lazy val backend = DefaultSyncBackend()

  def init(ms: MeshService): Unit = meshServiceOpt = Some(ms)

  /** Expose current MeshService for env info injection. */
  def currentService: Option[MeshService] = meshServiceOpt

  /** Check if the tool call targets a remote device. */
  def isRemote(input: JsonObject): Boolean =
    input("device").flatMap(_.asString).exists(_.nonEmpty)

  /**
   * Forward a tool call to a remote Nebflow device.
   * @param toolName  e.g. "Bash", "Read", "Write"
   * @param input     the original tool input (must contain "device" field)
   * @return          the remote tool's output or an error
   */
  def forward(toolName: String, input: JsonObject): IO[Either[ToolError, String]] =
    meshServiceOpt match
      case None =>
        IO.pure(Left(ToolError("Mesh not available — device parameter requires Mesh to be configured")))
      case Some(ms) =>
        val deviceName = input("device").flatMap(_.asString).getOrElse("")
        for
          peers <- ms.peers
          gid <- ms.groupId
          result <- resolveAndCall(deviceName, toolName, input, peers, gid)
        yield result

  private def resolveAndCall(
    deviceName: String,
    toolName: String,
    input: JsonObject,
    peers: List[nebflow.mesh.PeerInfo],
    groupId: Option[String]
  ): IO[Either[ToolError, String]] =
    // Match by deviceName (case-insensitive) or deviceId prefix
    val peer = peers.find(p =>
      p.deviceName.equalsIgnoreCase(deviceName) ||
      p.deviceId.startsWith(deviceName) ||
      p.deviceName.toLowerCase.contains(deviceName.toLowerCase)
    )

    peer match
      case None =>
        val available = peers.map(p => s"${p.deviceName} (${p.platform})")
        IO.pure(Left(ToolError(
          if peers.isEmpty then s"No peer devices found. Available devices appear in the environment info."
          else s"Device '$deviceName' not found. Available: ${available.mkString(", ")}"
        )))
      case Some(p) =>
        p.address match
          case Some(addr) if addr.nonEmpty =>
            callRemote(addr, toolName, input, groupId.getOrElse(""))
          case _ =>
            IO.pure(Left(ToolError(
              s"Device '${p.deviceName}' has no registered address. Ensure both devices are on the same network."
            )))

  private def callRemote(
    address: String,
    toolName: String,
    input: JsonObject,
    groupId: String
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      // Strip "device" from params — remote doesn't need it
      val params = input.filter((k, _) => k != "device")
      val body = io.circe.Json.obj(
        "action" -> toolName.asJson,
        "params" -> params.asJson
      )
      val uri = s"$address/api/mesh/remote-exec"
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(uri))
        .auth.bearer(groupId)
        .contentType("application/json")
        .body(body.noSpaces)
        .response(asStringAlways)
        .send(backend)

      if !resp.code.isSuccess then
        Left(ToolError(s"Remote device returned HTTP ${resp.code}: ${resp.body.take(200)}"))
      else
        decode[io.circe.Json](resp.body) match
          case Right(json) =>
            val output = json.hcursor.downField("output").as[String].getOrElse("")
            val error = json.hcursor.downField("error").as[String].getOrElse("")
            if error.nonEmpty then Left(ToolError(s"Remote error: $error"))
            else Right(output)
          case Left(err) =>
            Left(ToolError(s"Invalid response from remote: ${err.getMessage}"))
    }
