package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.agent.SharedResources
import nebflow.core.flow.FlowTreeRegistry
import nebflow.llm.NebflowServiceConfig
import nebflow.neblink.NeblinkService
import nebflow.service.ConfigService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.CIStringSyntax

import scala.concurrent.duration.*

/**
 * REST API routes for CLI consumption.
 * These endpoints mirror the WebSocket message handlers but over HTTP.
 */
class RestApiRoutes(
  token: String,
  configRef: cats.effect.Ref[IO, NebflowServiceConfig],
  sharedResources: SharedResources,
  sessionStore: SessionStore,
  wsRoutes: WebSocketRoutes,
  neblinkService: Option[NeblinkService] = None,
  ttsService: Option[TtsService] = None
):
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.rest-api")

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // Health check
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> "ok".asJson, "version" -> nebflow.Version.string.asJson))

    // TTS 语音合成（无需 auth，内部调用）
    case req @ POST -> Root / "tts" =>
      ttsService match
        case None => NotFound(Json.obj("error" -> "TTS not configured".asJson))
        case Some(svc) =>
          req.as[Json].flatMap { body =>
            val text = body.hcursor.downField("text").as[String].getOrElse("")
            svc.synthesize(text).flatMap {
              case Some(bytes) =>
                IO.pure(
                  Response[IO](
                    status = Status.Ok,
                    headers = Headers(`Content-Type`(MediaType.audio.wav)),
                    body = Stream.emits(bytes).covary[IO]
                  )
                )
              case None => NotFound(Json.obj("error" -> "TTS synthesis failed".asJson))
            }
          }

    // Generic command endpoint — mirrors WS messages
    case req @ POST -> Root / "command" =>
      withAuth(req) {
        req.as[Json].flatMap { payload =>
          val responseRef = cats.effect.Ref.unsafe[IO, Option[Json]](None)
          val wsSend = (json: Json) => responseRef.set(Some(json))
          wsRoutes.handleMessagePublic(payload.noSpaces, wsSend).flatMap { _ =>
            responseRef.get.flatMap {
              case Some(resp) => Ok(resp)
              case None => Ok(Json.obj("status" -> "ok".asJson))
            }
          }
        }
      }

    // Session list
    case req @ GET -> Root / "sessions" =>
      withAuth(req) {
        sessionStore.listSessions.flatMap { sessions =>
          sessionStore.getActiveId.flatMap { activeId =>
            Ok(Json.obj("sessions" -> sessions.asJson, "activeId" -> activeId.asJson))
          }
        }
      }

    // Session history
    case req @ GET -> Root / "sessions" / sessionId / "history" =>
      withAuth(req) {
        sessionStore.getUiMessages(sessionId, 0, 0).flatMap { case (messages, total) =>
          Ok(Json.obj("messages" -> messages.asJson, "total" -> total.asJson, "sessionId" -> sessionId.asJson))
        }
      }

    // Create session
    case req @ POST -> Root / "sessions" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val name = body.hcursor.downField("name").as[String].getOrElse("New Session")
          val agentName = body.hcursor.downField("agentName").as[Option[String]].getOrElse(None)
          val folderId = body.hcursor.downField("folderId").as[Option[String]].getOrElse(None)
          sessionStore.createSession(name, agentName = agentName, folderId = folderId).flatMap { meta =>
            Ok(meta.asJson)
          }
        }
      }

    // Delete session
    case req @ DELETE -> Root / "sessions" / sessionId =>
      withAuth(req) {
        sessionStore.deleteSession(sessionId) *> Ok(Json.obj("deleted" -> true.asJson))
      }

    // Config
    case req @ GET -> Root / "config" =>
      withAuth(req) {
        ConfigService.getConfig.flatMap { cfg =>
          ConfigService.isConfigured.flatMap { configured =>
            Ok(Json.obj("config" -> cfg.asJson, "configured" -> configured.asJson))
          }
        }
      }

    // Update config
    case req @ PATCH -> Root / "config" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val cfgStr = body.hcursor.downField("config").as[String].getOrElse(body.noSpaces)
          ConfigService.updateConfig(cfgStr).flatMap {
            case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
            case Right(_) =>
              sharedResources.providerRegistry.reloadConfig().attempt.flatMap {
                case Right(_) => logger.info("Config hot-reloaded via REST")
                case Left(e) => logger.warn(s"Config hot-reload failed: ${e.getMessage}")
              } *> Ok(Json.obj("updated" -> true.asJson))
          }
        }
      }

    // Models
    case req @ GET -> Root / "models" =>
      withAuth(req) {
        sharedResources.providerRegistry.getAllModels().flatMap { models =>
          Ok(Json.obj("models" -> models.map { case (ref, label) =>
            Json.obj("ref" -> ref.asJson, "label" -> label.asJson)
          }.asJson))
        }
      }

    // Agents
    case req @ GET -> Root / "agents" =>
      withAuth(req) {
        sharedResources.agentLibrary.loadAll().flatMap { agents =>
          val list = agents.values.toList.map { a =>
            Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.displayName.getOrElse(a.name).asJson
            )
          }
          Ok(Json.obj("agents" -> list.asJson))
        }
      }

    // Folders
    case req @ GET -> Root / "folders" =>
      withAuth(req) {
        val agentName = req.params.get("agent").getOrElse("Nebula")
        sessionStore.listFolders(agentName).flatMap { folders =>
          Ok(Json.obj("folders" -> folders.asJson))
        }
      }

    // MCP servers
    case req @ GET -> Root / "mcp" =>
      withAuth(req) {
        IO.blocking {
          val configPath = nebflow.llm.Config.DefaultConfigPath
          if os.exists(configPath) then
            parser
              .parse(os.read(configPath))
              .toOption
              .flatMap(_.hcursor.downField("mcpServers").as[Map[String, Json]].toOption)
          else None
        }.flatMap {
          case Some(servers) => Ok(Json.obj("mcpServers" -> io.circe.Json.fromFields(servers)))
          case None => Ok(Json.obj("mcpServers" -> Json.obj()))
        }
      }

    // Memory
    case req @ GET -> Root / "memory" =>
      withAuth(req) {
        val scope = req.params.get("scope").getOrElse("agent")
        sessionStore.getActiveMeta.flatMap { metaOpt =>
          val agentName = metaOpt.flatMap(_.agentName).getOrElse("Nebula")
          val folderId = metaOpt.flatMap(_.folderId).getOrElse("")
          val content = scope match
            case "user" => nebflow.service.MemoryStore.loadUserMemory.getOrElse("")
            case "agent" => nebflow.service.MemoryStore.loadAgentMemory(agentName).getOrElse("")
            case "folder" =>
              if folderId.nonEmpty then nebflow.service.MemoryStore.loadFolderMemory(folderId).getOrElse("") else ""
            case _ => ""
          Ok(Json.obj("scope" -> scope.asJson, "content" -> content.asJson))
        }
      }

    // ===== NebLink P2P Discovery (no gateway auth — used by other Nebflow instances) =====

    // Return local device info for Tailscale discovery probes
    case GET -> Root / "neblink" / "discover" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          ms.identity.flatMap { id =>
            Ok(
              Json.obj(
                "deviceId" -> id.deviceId.asJson,
                "deviceName" -> id.deviceName.asJson,
                "platform" -> id.platform.asJson,
                "capabilities" -> id.capabilities.asJson
              )
            )
          }

    // Receive a peer's announcement ("I'm online, here's my info")
    case req @ POST -> Root / "neblink" / "announce" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          val remoteIp = req.remoteAddr.fold("")(a => a.toString)
          if !ms.isTailscalePeer(remoteIp) then Forbidden(Json.obj("error" -> "Not a Tailscale peer".asJson))
          else
            req.as[Json].flatMap { body =>
              io.circe.parser.decode[nebflow.neblink.DeviceDiscoveryInfo](body.noSpaces) match
                case Right(info) =>
                  val port = body.hcursor.downField("port").as[Int].getOrElse(8080)
                  ms.handleAnnounce(info, remoteIp, port) *>
                    Ok(Json.obj("ok" -> true.asJson))
                case Left(err) =>
                  BadRequest(Json.obj("error" -> s"Invalid device info: ${err.getMessage}".asJson))
            }

    // ===== NebLink API (gateway auth required — for frontend) =====

    // NebLink scan — trigger Tailscale discovery immediately, return updated peers
    case req @ POST -> Root / "neblink" / "scan" =>
      withNeblink(req) { ms =>
        ms.scanNow.flatMap { peersList =>
          Ok(
            Json.obj(
              "peers" -> peersList
                .map(p =>
                  Json.obj(
                    "deviceId" -> p.deviceId.asJson,
                    "deviceName" -> p.deviceName.asJson,
                    "platform" -> p.platform.asJson,
                    "capabilities" -> p.capabilities.asJson,
                    "userDescription" -> p.userDescription.asJson
                  )
                )
                .asJson,
              "peerCount" -> peersList.length.asJson
            )
          )
        }
      }

    // NebLink status — identity, peers
    case req @ GET -> Root / "neblink" / "status" =>
      withNeblink(req) { ms =>
        ms.identity.flatMap { id =>
          ms.peers.flatMap { peersList =>
            Ok(
              Json.obj(
                "device" -> Json.obj(
                  "id" -> id.deviceId.asJson,
                  "name" -> id.deviceName.asJson,
                  "platform" -> id.platform.asJson,
                  "capabilities" -> id.capabilities.asJson,
                  "userDescription" -> id.userDescription.asJson
                ),
                "peers" -> peersList
                  .map(p =>
                    Json.obj(
                      "deviceId" -> p.deviceId.asJson,
                      "deviceName" -> p.deviceName.asJson,
                      "platform" -> p.platform.asJson,
                      "address" -> p.address.asJson,
                      "capabilities" -> p.capabilities.asJson,
                      "userDescription" -> p.userDescription.asJson,
                      "lastSeen" -> p.lastSeen.asJson
                    )
                  )
                  .asJson
              )
            )
          }
        }
      }

    // Handshake — called by a discovered peer to establish trust and exchange device secrets.
    // Guarded by Tailscale IP check (same as /neblink/announce) — only tailnet members can reach this.
    case req @ POST -> Root / "neblink" / "handshake" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          val callerIp = req.remoteAddr.fold("")(a => a.toString)
          if !ms.isTailscalePeer(callerIp) then Forbidden(Json.obj("error" -> "Not a Tailscale peer".asJson))
          else
            // Bearer format: deviceId:callerDeviceSecret
            val bearer = req.headers
              .get[Authorization]
              .collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
                t
              }
              .getOrElse("")
            val parts = bearer.split(":", 2)
            if parts.length != 2 || parts(0).isEmpty then
              Forbidden(Json.obj("error" -> "Invalid peer auth format".asJson))
            else
              val callerSecret = parts(1)
              req.as[Json].flatMap { body =>
                val hc = body.hcursor
                val deviceId = hc.downField("deviceId").as[String].getOrElse("")
                val deviceName = hc.downField("deviceName").as[String].getOrElse("Unknown")
                val platform = hc.downField("platform").as[String].getOrElse("")
                val port = hc.downField("port").as[Int].getOrElse(8080)
                if deviceId.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceId".asJson))
                else
                  ms.handleHandshake(deviceId, deviceName, platform, callerIp, port, callerSecret)
                    .flatMap { _ =>
                      ms.identity
                        .map { id =>
                          Json.obj(
                            "deviceId" -> id.deviceId.asJson,
                            "deviceName" -> id.deviceName.asJson,
                            "platform" -> id.platform.asJson,
                            "deviceSecret" -> id.deviceSecret.asJson
                          )
                        }
                        .flatMap(Ok(_))
                    }
                    .handleErrorWith { e =>
                      Forbidden(Json.obj("error" -> e.getMessage.asJson))
                    }
                end if
              }
            end if
          end if

    // Update neblink config (e.g. syncIntervalSec)
    case req @ PATCH -> Root / "neblink" / "config" =>
      withNeblink(req) { ms =>
        req.as[Json].flatMap { body =>
          val syncInterval = body.hcursor.downField("syncIntervalSec").as[Option[Int]].toOption.flatten
          ms.updateConfig { cfg =>
            cfg.copy(
              syncIntervalSec = syncInterval.getOrElse(cfg.syncIntervalSec)
            )
          } *> Ok(Json.obj("ok" -> true.asJson))
        }
      }

    // Cloud session sync toggle — removed (session sync deleted)

    // Update device capabilities / user description
    case req @ PUT -> Root / "neblink" / "device-info" =>
      withNeblink(req) { ms =>
        req.as[Json].flatMap { body =>
          val userDesc = body.hcursor.downField("userDescription").as[Option[String]].toOption.flatten
          val caps = body.hcursor.downField("capabilities").as[Option[Map[String, String]]].toOption.flatten
          ms.updateDeviceInfo(userDescription = userDesc, capabilities = caps) *>
            Ok(Json.obj("ok" -> true.asJson))
        }
      }

    // Update a peer device's description (local override)
    case req @ PUT -> Root / "neblink" / "peer-description" =>
      withNeblink(req) { ms =>
        req.as[Json].flatMap { body =>
          val deviceId = body.hcursor.downField("deviceId").as[String].toOption
          val desc = body.hcursor.downField("userDescription").as[String].toOption.getOrElse("")
          deviceId match
            case Some(did) => ms.updatePeerDescription(did, desc) *> Ok(Json.obj("ok" -> true.asJson))
            case None => BadRequest(Json.obj("error" -> "missing deviceId".asJson))
        }
      }

    // File sync endpoints (fingerprints, file GET/PUT) — removed

    // Peer notification — lightweight ping to trigger immediate sync
    case req @ POST -> Root / "neblink" / "notify" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(ms) =>
          Ok(Json.obj("ok" -> true.asJson))
      }

    case req @ POST -> Root / "neblink" / "remote-exec" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(ms) =>
          req.as[Json].flatMap { body =>
            val hc = body.hcursor
            val action = hc.downField("action").as[String].getOrElse("")
            val params = hc.downField("params").as[io.circe.JsonObject].getOrElse(io.circe.JsonObject.empty)
            val toolOpt = nebflow.core.tools.ToolRegistry.TOOL_MAP.get(action)
            toolOpt match
              case Some(tool) =>
                val projectRoot = hc.downField("projectRoot").as[String].getOrElse(System.getProperty("user.dir", "."))
                val ctx = nebflow.core.tools.ToolContext(
                  projectRoot = projectRoot,
                  isRemoteExec = true
                )
                tool.call(params, ctx).attempt.flatMap {
                  case Right(Right(result)) => Ok(Json.obj("output" -> result.asJson))
                  case Right(Left(err)) => Ok(Json.obj("error" -> err.message.asJson, "output" -> "".asJson))
                  case Left(e) =>
                    Ok(Json.obj("error" -> s"Tool execution failed: ${e.getMessage}".asJson, "output" -> "".asJson))
                }
              case None =>
                BadRequest(Json.obj("error" -> s"Unknown tool: $action".asJson))
            end match
          }
      }

    // ===== Remote Update (P2P — triggered by another Nebflow instance) =====
    // Downloads and installs the latest JAR, then restarts Nebflow.
    // The caller must be a Tailscale peer (verified by IP).
    case req @ POST -> Root / "neblink" / "update" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(_) =>
          req.as[Json].flatMap { body =>
            val beta = body.hcursor.downField("beta").as[Boolean].getOrElse(false)
            val isWindows = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")
            val script =
              if beta then
                if isWindows then
                  """powershell -Command "$env:CHANNEL='beta'; iwr https://nebflow.space/install.ps1 | iex" """
                else "curl -fsSL https://nebflow.space/install.sh | sh -s -- --beta"
              else if isWindows then """powershell -Command "& { iwr https://nebflow.space/install.ps1 | iex }" """
              else "curl -fsSL https://nebflow.space/install.sh | sh"

            logger.info(s"[neblink] Remote update requested (beta=$beta), running install script...") *>
              IO.blocking {
                import sys.process.*
                val exitCode = script.!
                exitCode
              }.flatMap { exitCode =>
                if exitCode == 0 then
                  logger.info("[neblink] Install succeeded, spawning restart helper and shutting down...") *>
                    IO.blocking(nebflow.core.RestartHelper.spawnRestart()) *>
                    // Schedule JVM exit after 1 second (allows HTTP response to be sent)
                    IO.delay {
                      sharedResources.dispatcher.unsafeRunAndForget(
                        IO.sleep(1.second) *> IO(System.exit(0))
                      )
                    } *>
                    Ok(
                      Json.obj(
                        "ok" -> true.asJson,
                        "message" -> "Update installed, restarting...".asJson
                      )
                    )
                else
                  Ok(
                    Json.obj(
                      "ok" -> false.asJson,
                      "error" -> s"Install script failed (exit code: $exitCode)".asJson
                    )
                  )
              }
          }
      }

    // ===== NebLink File Transfer (P2P — Tailscale IP auth) =====

    // Push a file to this device
    case req @ POST -> Root / "neblink" / "transfer" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(ms) =>
          req.as[Json].flatMap { body =>
            val path = body.hcursor.downField("path").as[String].getOrElse("")
            val contentB64 = body.hcursor.downField("content").as[String].getOrElse("")
            val overwrite = body.hcursor.downField("overwrite").as[Boolean].getOrElse(false)
            if path.isEmpty then BadRequest(Json.obj("error" -> "Missing path".asJson))
            else if contentB64.isEmpty then BadRequest(Json.obj("error" -> "Missing content".asJson))
            else
              val content = java.util.Base64.getDecoder.decode(contentB64)
              ms.receiveFile(path, content, overwrite)
                .flatMap(size => Ok(Json.obj("ok" -> true.asJson, "path" -> path.asJson, "size" -> size.asJson)))
                .handleErrorWith(e => Ok(Json.obj("ok" -> false.asJson, "error" -> e.getMessage.asJson)))
          }
      }

    // Pull a file from this device
    case req @ GET -> Root / "neblink" / "transfer" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(ms) =>
          val path = req.params.getOrElse("path", "")
          if path.isEmpty then BadRequest(Json.obj("error" -> "Missing path parameter".asJson))
          else
            ms.sendFile(path).flatMap {
              case Some(content) =>
                val b64 = java.util.Base64.getEncoder.encodeToString(content)
                Ok(
                  Json.obj(
                    "path" -> path.asJson,
                    "content" -> b64.asJson,
                    "size" -> content.length.asJson
                  )
                )
              case None => NotFound(Json.obj("error" -> s"File not found: $path".asJson))
            }
      }

    // ===== Dropbox: cross-device file transfer =====

    // Frontend uploads a file to send to a peer (gateway token auth)
    case req @ POST -> Root / "neblink" / "dropbox" / "upload" / transferId =>
      withAuth(req) {
        sharedResources.dropboxService match
          case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
          case Some(svc) =>
            svc.uploadAndRelay(transferId, req.body).flatMap {
              case Right(_) => Ok(Json.obj("ok" -> true.asJson))
              case Left(err) => Ok(Json.obj("ok" -> false.asJson, "error" -> err.asJson))
            }
      }

    // Peer pushes a file via HTTP (Tailscale IP auth)
    case req @ POST -> Root / "neblink" / "dropbox" / "transfer" / transferId =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(_) =>
          sharedResources.dropboxService match
            case None => NotFound(Json.obj("error" -> "Dropbox not enabled".asJson))
            case Some(svc) =>
              svc.receiveFromPeer(transferId, req.body).flatMap {
                case Right(hash) => Ok(Json.obj("sha256" -> hash.asJson))
                case Left(err) =>
                  val status =
                    if err.contains("not accepted") || err.contains("not found")
                    then Status.NotFound
                    else Status.InternalServerError
                  Response[IO](status).withEntity(Json.obj("error" -> err.asJson)).pure[IO]
              }
      }
  }

  // ===== WebSocket Presence Server Endpoint =====

  /**
   * Accepts incoming WS presence connections from Tailscale peers.
   *
   * The peer's device info arrives as query params on the WS upgrade request.
   * Once the WS is established:
   *   - Server sends heartbeat pings every 10s.
   *   - Server responds to client pings with pongs.
   *   - On disconnect, the peer is removed from the peer list.
   *
   * This is a separate HttpRoutes value because it needs WebSocketBuilder2,
   * which is only available inside `withHttpWebSocketApp` in GatewayMain.
   */
  def presenceWsRoutes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "neblink" / "presence" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          val remoteIp = req.remoteAddr.fold("")(a => a.toString)
          if !ms.isTailscalePeer(remoteIp) then Forbidden(Json.obj("error" -> "Not a Tailscale peer".asJson))
          else
            val peerDeviceId = req.params.getOrElse("deviceId", "")
            if peerDeviceId.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceId".asJson))
            else
              val peerDeviceName = req.params.getOrElse("deviceName", "Unknown")
              val peerPlatform = req.params.getOrElse("platform", "")
              val peerPort = req.params.getOrElse("port", "8080").toIntOption.getOrElse(8080)
              val capsStr = req.params.getOrElse("capabilities", "{}")
              val capabilities = parser.decode[Map[String, String]](capsStr).getOrElse(Map.empty)
              val info = nebflow.neblink.DeviceDiscoveryInfo(
                peerDeviceId,
                peerDeviceName,
                peerPlatform,
                capabilities
              )
              // Silent upsert — the HTTP /neblink/announce endpoint already handles logging.
              // Calling handleAnnounce here too produces duplicate "Peer announced" logs.
              val peer = nebflow.neblink.PeerInfo(
                peerDeviceId,
                peerDeviceName,
                peerPlatform,
                s"http://$remoteIp:$peerPort",
                capabilities = capabilities
              )
              ms.upsertPeer(peer).flatMap { _ =>
                Queue.unbounded[IO, WebSocketFrame].flatMap { sendQueue =>
                  val heartbeat = Stream
                    .awakeEvery[IO](10.seconds)
                    .map(_ => WebSocketFrame.Text("""{"type":"ping"}"""))
                  val queued = Stream.fromQueueUnterminated(sendQueue)
                  val send = queued.merge(heartbeat)
                  val receive: Pipe[IO, WebSocketFrame, Unit] =
                    _.evalMap {
                      case WebSocketFrame.Text(text, _) =>
                        parser.parse(text).toOption match
                          case Some(json) =>
                            json.hcursor.downField("type").as[String].getOrElse("") match
                              case "ping" =>
                                sendQueue.offer(WebSocketFrame.Text("""{"type":"pong"}"""))
                              case "data" =>
                                val payload = json.hcursor.downField("payload").focus.getOrElse(Json.Null)
                                ms.handleDataMessage(payload)
                              case _ => IO.unit
                          case None => IO.unit
                      case _ => IO.unit
                    }.onFinalize(
                      ms.removePeer(peerDeviceId).handleErrorWith(_ => IO.unit)
                    )
                  wsb.build(send, receive)
                }
              }
            end if
          end if

    // POST /api/flow/event — Source scripts inject external events (no auth, local only)
    case req @ POST -> Root / "flow" / "event" =>
      req.as[Json].flatMap { body =>
        val sessionId = body.hcursor.downField("sessionId").as[String].getOrElse("")
        val eventType = body.hcursor.downField("type").as[String].getOrElse("")
        val data = body.hcursor.downField("data").as[JsonObject].getOrElse(JsonObject.empty)

        if sessionId.isEmpty || eventType.isEmpty then
          BadRequest(Json.obj("error" -> "Missing 'sessionId' or 'type'".asJson))
        else
          FlowTreeRegistry.get(sessionId).flatMap {
            case Some(_) =>
              // EventFired removed — pipelines don't subscribe to external events
              Ok(Json.obj("status" -> "ok".asJson, "event" -> eventType.asJson))
            case None =>
              NotFound(Json.obj("error" -> s"No FlowTree for session '$sessionId'".asJson))
          }
      }

    // GET /api/flow/status/:sessionId — return all pipeline states for frontend
    case GET -> Root / "api" / "flow" / "status" / sessionId =>
      if sessionId.isEmpty then BadRequest(Json.obj("error" -> "Missing sessionId".asJson))
      else
        for
          states <- nebflow.core.flow.PipelineStateStore.loadAll(sessionId)
          response = Json.obj(
            "sessionId" -> sessionId.asJson,
            "pipelines" -> states.asJson
          )
          result <- Ok(response)
        yield result
  }

  private def withAuth(req: Request[IO])(f: => IO[Response[IO]]): IO[Response[IO]] =
    if checkAuth(req) then f
    else Forbidden(Json.obj("error" -> "Unauthorized".asJson))

  /** Run block only if NeblinkService is available and request is authenticated. */
  private def withNeblink(req: Request[IO])(f: NeblinkService => IO[Response[IO]]): IO[Response[IO]] =
    if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
    else
      neblinkService match
        case Some(ms) => f(ms)
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))

  /**
   * Verify peer-to-peer access via Tailscale IP check.
   * Only requests from the Tailscale CGNAT range (100.64.0.0/10) are accepted.
   * Tailscale itself is the trust boundary — devices must be on the same tailnet.
   */
  private def verifyPeerAccess(req: Request[IO]): IO[Either[Response[IO], NeblinkService]] =
    neblinkService match
      case None =>
        IO.pure(Left(Response[IO](Status.NotFound).withEntity(Json.obj("error" -> "NebLink not enabled".asJson))))
      case Some(ms) =>
        val remoteIp = req.remoteAddr.fold("")(a => a.toString)
        if ms.isTailscalePeer(remoteIp) then IO.pure(Right(ms))
        else
          IO.pure(
            Left(
              Response[IO](Status.Forbidden)
                .withEntity(Json.obj("error" -> s"Not a Tailscale peer (from $remoteIp)".asJson))
            )
          )

  private def checkAuth(req: Request[IO]): Boolean =
    req.headers.get[Authorization].collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
      t
    } match
      case Some(t) => Auth.validateToken(t, token)
      case None =>
        req.params.get("token").exists(t => Auth.validateToken(t, token))
end RestApiRoutes
