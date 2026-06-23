package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.mesh.{CloudSessionSync, MeshService}
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * MeshTool — remote device operations for agents.
 *
 * Actions:
 *   list_peers                          — list peer devices
 *   Bash(device, command)               — run bash on remote device
 *   QueryJob(device, job_id)            — query background job on remote device
 *   Read(device, path)                  — read file from remote device
 *   Write(device, path, content)        — write file to remote device
 *   Edit(device, path, old, new)        — edit file on remote device
 *   Glob(device, pattern)               — list files on remote device
 *   Grep(device, pattern)               — search files on remote device
 *
 * Direct P2P is tried first. If the remote device is unreachable (NAT, firewall),
 * cloud relay is used as fallback (requires Mesh login).
 *
 * Bash supports run_in_background for long-running tasks. Use QueryJob to poll status.
 */
class MeshTool private (meshService: MeshService) extends Tool:

  def name: String = "Mesh"

  def description: String =
    """Execute operations on remote Nebflow peer devices.
Use list_peers to discover available devices, then use Bash/Read/Write/Edit/Glob/Grep to operate on them.
Each operation verifies the target device is reachable before executing.
Available devices are listed in the environment info table.""".stripMargin

  def inputSchema: JsonObject =
    JsonObject(
      "type" -> "object".asJson,
      "properties" -> Map(
        "action" -> Map(
          "type" -> "string".asJson,
          "enum" -> List("list_peers", "Bash", "QueryJob", "Read", "Write", "Edit", "Glob", "Grep").asJson,
          "description" -> "The mesh action to perform. Use the Nebflow tool name directly.".asJson
        ).asJson,
        "device" -> Map(
          "type" -> "string".asJson,
          "description" -> "Target device name (for all actions except list_peers).".asJson
        ).asJson,
        "command" -> Map("type" -> "string".asJson, "description" -> "Bash command (for Bash).".asJson).asJson,
        "run_in_background" -> Map(
          "type" -> "boolean".asJson,
          "description" -> "Run bash in background (for Bash). Returns job_id. Use QueryJob to check status.".asJson
        ).asJson,
        "job_id" -> Map(
          "type" -> "string".asJson,
          "description" -> "Background job ID (for QueryJob).".asJson
        ).asJson,
        "cancel_background_job" -> Map(
          "type" -> "boolean".asJson,
          "description" -> "Cancel a background job (for QueryJob).".asJson
        ).asJson,
        "path" -> Map("type" -> "string".asJson, "description" -> "File path (for Read/Write/Edit).".asJson).asJson,
        "content" -> Map("type" -> "string".asJson, "description" -> "File content (for Write).".asJson).asJson,
        "old_string" -> Map("type" -> "string".asJson, "description" -> "Text to find (for Edit).".asJson).asJson,
        "new_string" -> Map("type" -> "string".asJson, "description" -> "Replacement text (for Edit).".asJson).asJson,
        "pattern" -> Map(
          "type" -> "string".asJson,
          "description" -> "Glob or search pattern (for Glob/Grep).".asJson
        ).asJson,
        "glob" -> Map("type" -> "string".asJson, "description" -> "File filter for Grep.".asJson).asJson
      ).asJson,
      "required" -> List("action").asJson
    )

  def summarize(input: JsonObject): String =
    val action = input("action").flatMap(_.asString).getOrElse("")
    val device = input("device").flatMap(_.asString).getOrElse("")
    action match
      case "list_peers" => "[Mesh] list_peers"
      case "Bash" => s"[Mesh] Bash on $device\n  ${input("command").flatMap(_.asString).getOrElse("").take(60)}"
      case "QueryJob" => s"[Mesh] QueryJob on $device\n  ${input("job_id").flatMap(_.asString).getOrElse("")}"
      case "Read" => s"[Mesh] Read from $device\n  ${input("path").flatMap(_.asString).getOrElse("")}"
      case "Write" => s"[Mesh] Write to $device\n  ${input("path").flatMap(_.asString).getOrElse("")}"
      case "Edit" => s"[Mesh] Edit on $device\n  ${input("path").flatMap(_.asString).getOrElse("")}"
      case "Glob" => s"[Mesh] Glob on $device\n  ${input("pattern").flatMap(_.asString).getOrElse("")}"
      case "Grep" => s"[Mesh] Grep on $device\n  ${input("pattern").flatMap(_.asString).getOrElse("")}"
      case _ => s"[Mesh] $action"

  def summarizeResult(input: JsonObject, result: String): String =
    val action = input("action").flatMap(_.asString).getOrElse("")
    if action == "list_peers" then result.take(200)
    else s"${result.length} chars"

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    val action = input("action").flatMap(_.asString).getOrElse("")
    action match
      case "list_peers" => listPeers
      case "QueryJob" =>
        val device = input("device").flatMap(_.asString).getOrElse("")
        if device.isEmpty then IO.pure(Left(ToolError("Missing device parameter for QueryJob.")))
        else remoteExec(device, "QueryJob", input)
      case "Bash" | "Read" | "Write" | "Edit" | "Glob" | "Grep" =>
        val device = input("device").flatMap(_.asString).getOrElse("")
        if device.isEmpty then
          IO.pure(Left(ToolError("Missing device parameter. Use list_peers to find available devices.")))
        else remoteExec(device, action, input)
      case _ =>
        IO.pure(
          Left(ToolError(s"Unknown action: $action. Use list_peers, Bash, QueryJob, Read, Write, Edit, Glob, or Grep."))
        )
    end match
  end call

  // ---- Actions ----

  private def listPeers: IO[Either[ToolError, String]] =
    for
      loggedIn <- meshService.isLoggedIn
      id <- meshService.identity
      peers <- meshService.peers
    yield
      if !loggedIn then Left(ToolError("Not logged in — use the Mesh panel to login first."))
      else if peers.isEmpty then Right("No peer devices. Only this device is in the group.\nCurrent: " + id.deviceName)
      else
        val current = s"- ${id.deviceName} (${id.platform}) [THIS DEVICE]"
        val peerLines = peers.map { p =>
          val caps = p.userDescription match
            case desc if desc.nonEmpty => s" — $desc"
            case _ => ""
          s"- ${p.deviceName} (${p.platform})$caps"
        }
        Right(s"Devices in group:\n$current\n${peerLines.mkString("\n")}")

  private def remoteExec(deviceName: String, action: String, input: JsonObject): IO[Either[ToolError, String]] =
    for
      peers <- meshService.peers
      loggedIn <- meshService.isLoggedIn
      peer <- resolvePeer(deviceName, peers)
      result <- peer match
        case Left(err) => IO.pure(Left(err))
        case Right(p) =>
          val addr = p.address
          if addr.isEmpty then IO.pure(Left(ToolError(s"Device '${p.deviceName}' has no registered address.")))
          else if !loggedIn then IO.pure(Left(ToolError("Not logged in")))
          else
            checkReachable(addr).flatMap {
              case Left(p2pErr) =>
                // P2P failed — try cloud relay fallback
                relayFallback(p, action, input, p2pErr)
              case Right(_) =>
                val params = buildParams(action, input)
                meshService.peerAuthToken.flatMap(token => callRemote(addr, action, params, token))
            }
    yield result

  // ---- Relay Fallback ----

  /** Try cloud relay when direct P2P connection fails. */
  private def relayFallback(
    peer: nebflow.mesh.PeerInfo,
    action: String,
    input: JsonObject,
    p2pError: ToolError
  ): IO[Either[ToolError, String]] =
    MeshTool.cloudSessionSyncOpt match
      case None =>
        IO.pure(Left(p2pError))
      case Some(css) =>
        // For background jobs, just submit and return relayId — don't block
        val isBackground = input("run_in_background").flatMap(_.asBoolean).getOrElse(false)
        if isBackground then
          for relayId <- css
              .relaySubmit(peer.deviceId, action, buildParams(action, input).asJson)
              .handleErrorWith(e => IO.pure(""))
          yield
            if relayId.nonEmpty then
              Right(
                s"Submitted to ${peer.deviceName} via cloud relay (relayId: $relayId). The command is running in background on the remote device. Use Mesh action=QueryJob to check status."
              )
            else
              Left(ToolError(s"P2P failed (${p2pError.message}) and relay submit also failed. Device may be offline."))
        else
          for
            relayId <- css
              .relaySubmit(peer.deviceId, action, buildParams(action, input).asJson)
              .handleErrorWith(e => IO.pure(""))
            result <-
              if relayId.isEmpty then
                IO.pure(
                  Left(
                    ToolError(s"P2P failed (${p2pError.message}) and relay submit also failed. Device may be offline.")
                  )
                )
              else
                css.relayFetchResultBlocking(relayId, timeout = 180.seconds).map {
                  case Right(output) => Right(output)
                  case Left(err) => Left(ToolError(s"Relay error: $err"))
                }
          yield result
        end if

  // ---- Helpers ----

  private def resolvePeer(
    deviceName: String,
    peers: List[nebflow.mesh.PeerInfo]
  ): IO[Either[ToolError, nebflow.mesh.PeerInfo]] =
    val peer = peers.find(p =>
      p.deviceName.equalsIgnoreCase(deviceName) ||
        p.deviceId.startsWith(deviceName) ||
        p.deviceName.toLowerCase.contains(deviceName.toLowerCase)
    )
    IO.pure(peer match
      case Some(p) => Right(p)
      case None =>
        val available = peers.map(p => s"${p.deviceName}")
        Left(
          ToolError(
            if peers.isEmpty then "No peer devices found."
            else s"Device '$deviceName' not found. Available: ${available.mkString(", ")}"
          )
        ))

  end resolvePeer

  /** Check if the remote device is reachable via /api/health. */
  private def checkReachable(address: String): IO[Either[ToolError, Unit]] =
    IO.blocking {
      try
        val resp = basicRequest
          .get(sttp.model.Uri.unsafeParse(s"$address/api/health"))
          .readTimeout(5.seconds)
          .response(asStringAlways)
          .send(meshService.httpBackend)
        if resp.code.isSuccess then Right(())
        else Left(ToolError(s"Device at $address returned HTTP ${resp.code}. It may be offline."))
      catch
        case e: Exception =>
          Left(ToolError(s"Cannot reach device at $address: ${e.getMessage}. Will try cloud relay."))
    }

  /** Build tool-specific params from MeshTool input. */
  private def buildParams(action: String, input: JsonObject): JsonObject =
    action match
      case "Bash" =>
        val base = JsonObject("command" -> input("command").getOrElse("".asJson))
        // Pass through run_in_background for background execution
        input("run_in_background").map(bg => base.add("run_in_background", bg)).getOrElse(base)
      case "QueryJob" =>
        val base = JsonObject("background_job_id" -> input("job_id").getOrElse("".asJson))
        input("cancel_background_job").map(c => base.add("cancel_background_job", c)).getOrElse(base)
      case "Read" =>
        JsonObject("file_path" -> input("path").getOrElse("".asJson))
      case "Write" =>
        JsonObject(
          "file_path" -> input("path").getOrElse("".asJson),
          "content" -> input("content").getOrElse("".asJson)
        )
      case "Edit" =>
        JsonObject(
          "file_path" -> input("path").getOrElse("".asJson),
          "old_string" -> input("old_string").getOrElse("".asJson),
          "new_string" -> input("new_string").getOrElse("".asJson),
          "replace_all" -> input("replace_all").getOrElse(false.asJson)
        )
      case "Glob" =>
        JsonObject("pattern" -> input("pattern").getOrElse("".asJson))
      case "Grep" =>
        val base = JsonObject("pattern" -> input("pattern").getOrElse("".asJson))
        input("glob").map(g => base.add("glob", g)).getOrElse(base)
      case _ => JsonObject.empty

  /** Call remote device's /api/mesh/remote-exec directly (P2P). */
  private def callRemote(
    address: String,
    toolName: String,
    params: JsonObject,
    token: String
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val body = io.circe.Json.obj("action" -> toolName.asJson, "params" -> params.asJson)
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(s"$address/api/mesh/remote-exec"))
        .auth
        .bearer(token)
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
    }

end MeshTool

object MeshTool:

  @volatile private var meshServiceOpt: Option[MeshService] = None
  @volatile private var cloudSessionSyncOpt: Option[CloudSessionSync] = None

  /** Expose current MeshService for env info injection. */
  def currentService: Option[MeshService] = meshServiceOpt

  /** Expose current CloudSessionSync for busy lock integration. */
  def currentCloudSessionSync: Option[CloudSessionSync] = cloudSessionSyncOpt

  /** Wire CloudSessionSync for busy lock integration. */
  def setCloudSessionSync(css: CloudSessionSync): Unit = cloudSessionSyncOpt = Some(css)

  /** Wire MeshService for companion state without registering MeshTool as a tool.
   * Used by GatewayMain for backward compat (RestApiRoutes/WebSocketRoutes access MeshTool.currentService).
   * MeshTool as an agent-facing tool is replaced by RemoteExecutor + device parameter on all tools.
   */
  def wire(ms: MeshService): Unit = meshServiceOpt = Some(ms)

  /** Create and register the mesh tool with the tool registry. Deprecated — use RemoteExecutor instead. */
  def register(meshService: MeshService): Unit =
    meshServiceOpt = Some(meshService)
    ToolRegistry.registerTool(new MeshTool(meshService))
end MeshTool
