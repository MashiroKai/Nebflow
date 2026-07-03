package nebflow.core.tools

import cats.effect.IO
import cats.effect.std.Dispatcher
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import nebflow.agent.AgentCommand
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
class RemoteExecutor(meshService: MeshService, dispatcher: Dispatcher[IO]):

  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  /** Expose MeshService for system prompt generation (device list). */
  def meshServiceOpt: Option[MeshService] = Some(meshService)

  def execute(
    deviceName: String,
    toolName: String,
    params: JsonObject,
    ctxOpt: Option[ToolContext] = None
  ): IO[Either[ToolError, String]] =
    val isBackground = params("run_in_background").flatMap(_.asBoolean).getOrElse(false)

    def runOnPeer(peer: PeerInfo): IO[Either[ToolError, String]] =
      if peer.address.isEmpty then IO.pure(Left(ToolError(s"Device '${peer.deviceName}' has no address.")))
      else if isBackground && ctxOpt.isDefined then executeRemoteBackground(peer, toolName, params, ctxOpt.get)
      else p2pExecute(peer, toolName, params, 60.seconds)

    meshService.peers.flatMap { peers =>
      resolvePeer(deviceName, peers) match
        case Right(peer) => runOnPeer(peer)
        case Left(_) =>
          // Device not in peer list — the list might be stale. Trigger one immediate
          // discovery scan before giving up, so transient gaps don't cause false errors.
          logger.info(s"Device '$deviceName' not found in ${peers.size} peer(s), triggering discovery scan") *>
            meshService.scanNow.flatMap { refreshedPeers =>
              resolvePeer(deviceName, refreshedPeers) match
                case Right(peer) => runOnPeer(peer)
                case Left(err) => IO.pure(Left(err))
            }
    }
  end execute

  // ---- Remote background task: Mac manages lifecycle locally ----

  /**
   * When the LLM requests a remote background task, Mac handles the lifecycle:
   * 1. Strip `run_in_background` so KAI executes synchronously
   * 2. Emit "running" indicator to frontend
   * 3. Start a detached fiber that does the synchronous HTTP call to KAI
   * 4. Return "[Background job started]" to the LLM immediately
   * 5. When the HTTP call returns, notify agent (ExternalEvent) + frontend (WS)
   */
  private def executeRemoteBackground(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    ctx: ToolContext
  ): IO[Either[ToolError, String]] =
    val remoteParams = params.remove("run_in_background")
    val commandStr = params("command").flatMap(_.asString).getOrElse(toolName)
    val firstLine = commandStr.split('\n').headOption.getOrElse(commandStr).take(80)
    val description = s"[${peer.deviceName}] ${params("description").flatMap(_.asString).getOrElse(firstLine)}"

    for
      jobId <- IO.randomUUID.map(_.toString.take(8))
      _ <- logger.info(s"Remote background task $jobId started on ${peer.deviceName}: $firstLine")
      // 1. Emit "running" to frontend
      _ <- emitBgTaskStarted(ctx, jobId, description)
      // 2. Start detached fiber — synchronous HTTP to KAI, then notify on completion
      _ <- IO(startRemoteBgFiber(peer, toolName, remoteParams, ctx, jobId, description))
    yield Right(
      s"[Background job started] Job ID: $jobId\nThe command is running in the background on ${peer.deviceName}. You will be automatically notified when it finishes — continue with other work or finish your turn."
    )

  end executeRemoteBackground

  /** Launch the HTTP call in a detached fiber; notify agent + frontend when done. */
  private def startRemoteBgFiber(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    ctx: ToolContext,
    jobId: String,
    description: String
  ): Unit =
    val completionIO =
      for
        result <- p2pExecute(peer, toolName, params, 3600.seconds)
        _ <- result match
          case Right(output) =>
            // Notify agent so the result is injected into conversation
            ctx.agentActorRef.fold(IO.unit)(ref =>
              ref ! AgentCommand.ExternalEvent(
                source = "background-task",
                eventType = "completed",
                payload = s"[Background task completed] \"$description\":\n$output",
                metadata = JsonObject(
                  "description" -> description.asJson,
                  "output" -> output.asJson
                )
              )
            ) *>
              // Notify frontend to dismiss the indicator
              emitBgTaskFinished(ctx, jobId, description, "completed") *>
              logger.info(s"Remote background task $jobId completed on ${peer.deviceName}")
          case Left(err) =>
            ctx.agentActorRef.fold(IO.unit)(ref =>
              ref ! AgentCommand.ExternalEvent(
                source = "background-task",
                eventType = "failed",
                payload = s"[Background task failed] \"$description\":\n${err.message}",
                metadata = JsonObject("description" -> description.asJson)
              )
            ) *>
              emitBgTaskFinished(ctx, jobId, description, "failed") *>
              logger.warn(s"Remote background task $jobId failed on ${peer.deviceName}: ${err.message}")
      yield ()

    dispatcher.unsafeRunAndForget(
      completionIO.handleErrorWith(e =>
        logger.warn(s"Remote background fiber $jobId crashed: ${e.getMessage}") *>
          emitBgTaskFinished(ctx, jobId, description, "failed")
      )
    )
  end startRemoteBgFiber

  // ---- WS helpers (match BashTool's backgroundTaskUpdate format) ----

  private def emitBgTaskStarted(ctx: ToolContext, jobId: String, description: String): IO[Unit] =
    ctx.wsSend.fold(
      logger.debug(s"Cannot notify frontend for remote background job $jobId: no wsSend")
    )(send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> "running".asJson,
          "startedAt" -> System.currentTimeMillis().asJson
        )
      ).handleErrorWith(e => logger.warn(s"WS send failed for remote job $jobId: ${e.getMessage}"))
    )

  private def emitBgTaskFinished(
    ctx: ToolContext,
    jobId: String,
    description: String,
    status: String
  ): IO[Unit] =
    ctx.wsSend.fold(IO.unit)(send =>
      send(
        io.circe.Json.obj(
          "type" -> "backgroundTaskUpdate".asJson,
          "sessionId" -> ctx.sessionId.asJson,
          "taskId" -> jobId.asJson,
          "description" -> description.asJson,
          "status" -> status.asJson
        )
      ).handleErrorWith(_ => IO.unit)
    )

  // ---- P2P Direct ----

  private def p2pExecute(
    peer: PeerInfo,
    toolName: String,
    params: JsonObject,
    timeout: FiniteDuration
  ): IO[Either[ToolError, String]] =
    IO.blocking {
      val body = io.circe.Json.obj(
        "action" -> toolName.asJson,
        "params" -> params.asJson
      )
      val resp = basicRequest
        .post(sttp.model.Uri.unsafeParse(s"${peer.address}/api/mesh/remote-exec"))
        .contentType("application/json")
        .body(body.noSpaces)
        .readTimeout(timeout)
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
  end p2pExecute

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
              s"No peer devices discovered after scan. Check: (1) Tailscale is running on both machines, (2) Nebflow is running on '$deviceName', (3) both devices are logged into the same Mesh account."
            else s"Device '$deviceName' not found among ${peers.size} peer(s). Available: ${available.mkString(", ")}"
          )
        )

end RemoteExecutor

object RemoteExecutor:
  private val logger = NebflowLogger.forName("nebflow.remote-executor")

  @volatile private var instance: Option[RemoteExecutor] = None

  /** Wire the RemoteExecutor with a MeshService and Dispatcher. Called on startup. */
  def initialize(meshService: MeshService, dispatcher: Dispatcher[IO]): Unit =
    instance = Some(new RemoteExecutor(meshService, dispatcher))

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
