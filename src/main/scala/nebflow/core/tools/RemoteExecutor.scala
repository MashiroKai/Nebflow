package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.mesh.{MeshService, PeerInfo}
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Executes tool calls on remote devices via direct P2P over Tailscale.
 *
 * When a tool call specifies device="desktop-v7eucht", this executor routes
 * the call to that device's gateway via HTTP (POST /api/mesh/remote-exec).
 *
 * Tailscale provides the connectivity layer — no relay server needed.
 */
class RemoteExecutor(meshService: MeshService):

  /** Expose MeshService for system prompt generation (device list). */
  def meshServiceOpt: Option[MeshService] = Some(meshService)

  def execute(deviceName: String, toolName: String, params: JsonObject): IO[Either[ToolError, String]] =
    for
      peers <- meshService.peers
      result <- resolvePeer(deviceName, peers) match
        case Left(err) => IO.pure(Left(err))
        case Right(peer) =>
          if peer.address.isEmpty then IO.pure(Left(ToolError(s"Device '${peer.deviceName}' has no address.")))
          else p2pExecute(peer, toolName, params)
    yield result

  // ---- P2P Direct ----

  private def p2pExecute(peer: PeerInfo, toolName: String, params: JsonObject): IO[Either[ToolError, String]] =
    val projectRoot = System.getProperty("user.dir", "")
    IO.blocking {
      val body = io.circe.Json.obj(
        "action" -> toolName.asJson,
        "params" -> params.asJson,
        "projectRoot" -> projectRoot.asJson
      )
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/remote-exec"))
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(60.seconds)
        .response(asStringAlways)
        .send(meshService.httpBackend)

      if !resp.code.isSuccess then Left(ToolError(s"Remote device returned HTTP ${resp.code}: ${resp.body.take(200)}"))
      else
        decode[io.circe.Json](resp.body) match
          case Right(json) =>
            val output = json.hcursor.downField("output").as[String].getOrElse("")
            val error = json.hcursor.downField("error").as[String].getOrElse("")
            if error.nonEmpty then Left(ToolError(s"Remote error: $error"))
            else Right(output)
          case Left(err) =>
            Left(ToolError(s"Invalid response from remote: ${err.getMessage}"))
    }.handleErrorWith { e =>
      IO.pure(Left(ToolError(s"Cannot reach ${peer.deviceName} at ${peer.address}: ${e.getMessage}")))
    }

  // ---- Helpers ----

  private def resolvePeer(deviceName: String, peers: List[PeerInfo]): Either[ToolError, PeerInfo] =
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
              "No peer devices found. Ensure Tailscale is running and other devices have Nebflow started."
            else s"Device '$deviceName' not found. Available: ${available.mkString(", ")}"
          )
        )

end RemoteExecutor

object RemoteExecutor:
  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  @volatile private var instance: Option[RemoteExecutor] = None

  /** Wire the RemoteExecutor with a MeshService. Called on startup. */
  def initialize(meshService: MeshService): Unit =
    instance = Some(new RemoteExecutor(meshService))

  /** Get the current instance, or None if mesh is not initialized. */
  def current: Option[RemoteExecutor] = instance

  /**
   * Tools that support remote execution. Only these tools get the `device` parameter
   * in their schema. Other tools (Card, AskUser, Delegate, etc.) always run locally.
   */
  val remoteableTools: Set[String] = Set("Bash", "Read", "Write", "Edit", "Glob", "Grep")

  /**
   * Add `device` parameter to a tool's input schema if it's a remoteable tool.
   * Returns the modified schema, or the original if the tool is not remoteable.
   */
  def augmentSchema(toolName: String, schema: JsonObject): JsonObject =
    if !remoteableTools.contains(toolName) then schema
    else
      val props = schema("properties")
        .flatMap(_.asObject)
        .getOrElse(JsonObject.empty)
      props.add(
        "device",
        io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Target device name. Use the device's name from the available devices list. Defaults to local device if omitted.".asJson
        )
      ) match
        case newProps =>
          schema.add("properties", newProps.asJson)

end RemoteExecutor
