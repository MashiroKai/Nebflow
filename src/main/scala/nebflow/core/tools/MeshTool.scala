package nebflow.core.tools

import cats.effect.IO
import io.circe.JsonObject
import io.circe.syntax.*
import io.circe.parser.decode
import nebflow.mesh.MeshService
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * MeshTool — remote device operations for agents.
 *
 * Actions:
 *   list_peers                          — list peer devices
 *   Bash(device, command)               — run bash on remote device
 *   Read(device, path)                  — read file from remote device
 *   Write(device, path, content)        — write file to remote device
 *   Edit(device, path, old, new)        — edit file on remote device
 *   Glob(device, pattern)               — list files on remote device
 *   Grep(device, pattern)               — search files on remote device
 *
 * All remote operations verify the target device is reachable before executing.
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
          "enum" -> List("list_peers", "Bash", "Read", "Write", "Edit", "Glob", "Grep").asJson,
          "description" -> "The mesh action to perform. Use the Nebflow tool name directly.".asJson
        ).asJson,
        "device" -> Map(
          "type" -> "string".asJson,
          "description" -> "Target device name (for all actions except list_peers).".asJson
        ).asJson,
        "command" -> Map("type" -> "string".asJson, "description" -> "Bash command (for Bash).".asJson).asJson,
        "path" -> Map("type" -> "string".asJson, "description" -> "File path (for Read/Write/Edit).".asJson).asJson,
        "content" -> Map("type" -> "string".asJson, "description" -> "File content (for Write).".asJson).asJson,
        "old_string" -> Map("type" -> "string".asJson, "description" -> "Text to find (for Edit).".asJson).asJson,
        "new_string" -> Map("type" -> "string".asJson, "description" -> "Replacement text (for Edit).".asJson).asJson,
        "pattern" -> Map("type" -> "string".asJson, "description" -> "Glob or search pattern (for Glob/Grep).".asJson).asJson,
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
      case "Bash" | "Read" | "Write" | "Edit" | "Glob" | "Grep" =>
        val device = input("device").flatMap(_.asString).getOrElse("")
        if device.isEmpty then IO.pure(Left(ToolError("Missing device parameter. Use list_peers to find available devices.")))
        else remoteExec(device, action, input)
      case _ =>
        IO.pure(Left(ToolError(s"Unknown action: $action. Use list_peers, Bash, Read, Write, Edit, Glob, or Grep.")))

  // ---- Actions ----

  private def listPeers: IO[Either[ToolError, String]] =
    for
      id <- meshService.identity
      peers <- meshService.peers
    yield
      if id.groupId.isEmpty then
        Left(ToolError("Not paired — no mesh group. Use the Mesh panel to pair devices first."))
      else if peers.isEmpty then
        Right("No peer devices. Only this device is in the group.\nCurrent: " + id.deviceName)
      else
        val current = s"- ${id.deviceName} (${id.platform}) [THIS DEVICE]"
        val peerLines = peers.map { p =>
          s"- ${p.deviceName} (${p.platform}) ${if p.address.nonEmpty then "" else "[no address]"}"
        }
        Right(s"Devices in group:\n$current\n${peerLines.mkString("\n")}")

  private def remoteExec(deviceName: String, action: String, input: JsonObject): IO[Either[ToolError, String]] =
    for
      peers <- meshService.peers
      gid <- meshService.groupId
      peer <- resolvePeer(deviceName, peers)
      result <- peer match
        case Left(err) => IO.pure(Left(err))
        case Right(p) =>
          p.address.filter(_.nonEmpty) match
            case None => IO.pure(Left(ToolError(s"Device '${p.deviceName}' has no registered address.")))
            case Some(addr) =>
              // Verify reachability first
              checkReachable(addr).flatMap {
                case Left(err) => IO.pure(Left(err))
                case Right(_) =>
                  // Build params for the target tool
                  val params = buildParams(action, input)
                  callRemote(addr, action, params, gid.getOrElse(""))
              }
    yield result

  // ---- Helpers ----

  private def resolvePeer(deviceName: String, peers: List[nebflow.mesh.PeerInfo]): IO[Either[ToolError, nebflow.mesh.PeerInfo]] =
    val peer = peers.find(p =>
      p.deviceName.equalsIgnoreCase(deviceName) ||
      p.deviceId.startsWith(deviceName) ||
      p.deviceName.toLowerCase.contains(deviceName.toLowerCase)
    )
    IO.pure(peer match
      case Some(p) => Right(p)
      case None =>
        val available = peers.map(p => s"${p.deviceName}")
        Left(ToolError(
          if peers.isEmpty then "No peer devices found."
          else s"Device '$deviceName' not found. Available: ${available.mkString(", ")}"
        ))
    )

  /** Check if the remote device is reachable via /api/health. */
  private def checkReachable(address: String): IO[Either[ToolError, Unit]] =
    IO.blocking {
      try
        val resp = basicRequest
          .get(sttp.model.Uri.unsafeParse(s"$address/api/health"))
          .readTimeout(5.seconds)
          .response(asStringAlways)
          .send(backend)
        if resp.code.isSuccess then Right(())
        else Left(ToolError(s"Device at $address returned HTTP ${resp.code}. It may be offline."))
      catch
        case e: Exception =>
          Left(ToolError(s"Cannot reach device at $address: ${e.getMessage}. Check network connectivity."))
    }

  /** Build tool-specific params from MeshTool input. */
  private def buildParams(action: String, input: JsonObject): JsonObject =
    action match
      case "Bash" =>
        JsonObject("command" -> input("command").getOrElse("".asJson))
      case "Read" =>
        JsonObject("file_path" -> input("path").getOrElse("".asJson))
      case "Write" =>
        JsonObject("file_path" -> input("path").getOrElse("".asJson), "content" -> input("content").getOrElse("".asJson))
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
        val builder = JsonObject("pattern" -> input("pattern").getOrElse("".asJson))
        input("glob").foreach(g => builder.add("glob", g))
        builder
      case _ => JsonObject.empty

  /** Call remote device's /api/mesh/remote-exec. */
  private def callRemote(address: String, toolName: String, params: JsonObject, groupId: String): IO[Either[ToolError, String]] =
    IO.blocking {
      val body = io.circe.Json.obj("action" -> toolName.asJson, "params" -> params.asJson)
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(s"$address/api/mesh/remote-exec"))
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

  private lazy val backend = DefaultSyncBackend()

end MeshTool

object MeshTool:
  /** Create and register the mesh tool with the tool registry. */
  def register(meshService: MeshService): Unit =
    ToolRegistry.registerTool(new MeshTool(meshService))
