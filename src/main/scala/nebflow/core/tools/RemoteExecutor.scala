package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.core.NebflowLogger
import nebflow.mesh.{MeshService, PeerInfo, RelayService}
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * Executes tool calls on remote devices via P2P or cloud relay.
 *
 * This is the routing engine that makes
 * every tool device-aware. When a tool call specifies device="win-build",
 * this executor routes the call to that device.
 *
 * Routing priority:
 *   1. P2P direct (POST /api/mesh/remote-exec) — fastest, LAN/Tailscale
 *   2. Cloud relay fallback (relay/submit → poll → result) — NAT traversal
 */
class RemoteExecutor(meshService: MeshService):
  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  /** Expose MeshService for system prompt generation (device list). */
  def meshServiceOpt: Option[MeshService] = Some(meshService)

  def execute(deviceName: String, toolName: String, params: JsonObject): IO[Either[ToolError, String]] =
    for
      peers <- meshService.peers
      loggedIn <- meshService.isLoggedIn
      result <- resolvePeer(deviceName, peers) match
        case Left(err) => IO.pure(Left(err))
        case Right(peer) =>
          if !loggedIn then IO.pure(Left(ToolError("Not logged in to mesh.")))
          else if peer.address.isEmpty then IO.pure(Left(ToolError(s"Device '${peer.deviceName}' has no address.")))
          else
            checkReachable(peer.address).flatMap {
              case Left(p2pErr) =>
                relayFallback(peer, toolName, params, p2pErr)
              case Right(_) =>
                p2pExecute(peer, toolName, params)
            }
    yield result

  // ---- P2P Direct ----

  private def p2pExecute(peer: PeerInfo, toolName: String, params: JsonObject): IO[Either[ToolError, String]] =
    meshService.peerAuthToken.flatMap { token =>
      IO.blocking {
        val body = io.circe.Json.obj("action" -> toolName.asJson, "params" -> params.asJson)
        val resp = basicRequest
          .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/remote-exec"))
          .header("X-Peer-Token", token)
          .contentType("application/json")
          .body(body.noSpaces)
          .readTimeout(60.seconds)
          .response(asStringAlways)
          .send(meshService.httpBackend)

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
    }

  // ---- Relay Fallback ----

  private def relayFallback(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    p2pError: ToolError
  ): IO[Either[ToolError, String]] =
    RemoteExecutor.relayServiceOpt match
      case None => IO.pure(Left(p2pError))
      case Some(rs) =>
        for
          relayId <- rs
            .submit(peer.deviceId, toolName, params.asJson)
            .handleErrorWith(e => IO.pure(""))
          result <-
            if relayId.isEmpty then
              IO.pure(Left(ToolError(
                s"P2P failed (${p2pError.message}) and relay submit also failed. Device may be offline."
              )))
            else
              rs.fetchResultBlocking(relayId, timeout = 180.seconds).map {
                case Right(output) => Right(output)
                case Left(err)     => Left(ToolError(s"Relay error: $err"))
              }
        yield result

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
        Left(ToolError(
          if peers.isEmpty then s"No peer devices found."
          else s"Device '$deviceName' not found. Available: ${available.mkString(", ")}"
        ))

  private def checkReachable(address: String): IO[Either[ToolError, Unit]] =
    IO.blocking {
      try
        val resp = basicRequest
          .get(sttp.model.Uri.unsafeParse(s"$address/api/health"))
          .readTimeout(5.seconds)
          .response(asStringAlways)
          .send(meshService.httpBackend)
        if resp.code.isSuccess then Right(())
        else Left(ToolError(s"Device at $address returned HTTP ${resp.code}."))
      catch
        case e: Exception =>
          Left(ToolError(s"Cannot reach $address: ${e.getMessage}. Will try relay."))
    }

end RemoteExecutor

object RemoteExecutor:
  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  @volatile private var instance: Option[RemoteExecutor] = None

  /** Wire the RemoteExecutor with a MeshService. Called on startup. */
  def initialize(meshService: MeshService): Unit =
    instance = Some(new RemoteExecutor(meshService))

  /** Get the current instance, or None if mesh is not initialized. */
  def current: Option[RemoteExecutor] = instance

  @volatile private var relayServiceOpt: Option[RelayService] = None

  /** Wire RelayService for relay fallback. */
  def setRelayService(rs: RelayService): Unit =
    relayServiceOpt = Some(rs)

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
      props.add("device", io.circe.Json.obj(
        "type" -> "string".asJson,
        "description" -> "Target device name. Use the device's name from the available devices list. Defaults to local device if omitted.".asJson
      )) match
        case newProps =>
          schema.add("properties", newProps.asJson)

end RemoteExecutor
