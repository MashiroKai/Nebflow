package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.entity.EntityLoader
import nebflow.core.flow.{FlowTreeRegistry, TreeCommand}
import nebflow.llm.NebflowServiceConfig
import nebflow.neblink.{NeblinkConfig, NeblinkServerConfig, NeblinkService}
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
            case _ => ""
          Ok(Json.obj("scope" -> scope.asJson, "content" -> content.asJson))
        }
      }

    // ===== NebLink P2P Discovery (no gateway auth — used by other Nebflow instances) =====

    // Return local device info for NebLink discovery probes
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
          if !ms.isTrustedPeer(remoteIp) then Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
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

    // NebLink scan — trigger NebLink discovery immediately, return updated peers
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
                  "userDescription" -> id.userDescription.asJson,
                  "avatarUrl" -> id.avatarUrl.asJson
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
    // Guarded by peer IP check (same as /neblink/announce) — only network members can reach this.
    case req @ POST -> Root / "neblink" / "handshake" =>
      neblinkService match
        case None => NotFound(Json.obj("error" -> "NebLink not enabled".asJson))
        case Some(ms) =>
          val callerIp = req.remoteAddr.fold("")(a => a.toString)
          if !ms.isTrustedPeer(callerIp) then Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
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

    // Pair device — save NebLink Server config received from nebflow.space/connect redirect
    case req @ POST -> Root / "neblink" / "pair" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else
        req.as[Json].flatMap { body =>
          val serverOpt = body.hcursor.downField("server").as[Option[String]].toOption.flatten
          val networkIdOpt = body.hcursor.downField("networkId").as[Option[String]].toOption.flatten
          val secretOpt = body.hcursor.downField("secret").as[Option[String]].toOption.flatten
          // Optional account avatar URL returned by nebflow.space on pairing.
          val avatarOpt = body.hcursor.downField("avatar").as[Option[String]].toOption.flatten
          (serverOpt, networkIdOpt, secretOpt) match
            case (Some(server), Some(networkId), Some(secret)) =>
              val newConfig = NeblinkServerConfig(url = server, networkId = networkId, secret = secret)
              for
                current <- NeblinkConfig.load
                updated = current.copy(enabled = true, neblinkServer = Some(newConfig))
                _ <- NeblinkConfig.save(updated)
                // Persist the account avatar (if nebflow.space provided one) onto
                // the device identity so the UI can show it after login.
                _ <- neblinkService.fold(IO.unit)(_.updateDeviceInfo(avatarUrl = avatarOpt))
                resp <- Ok(
                  Json.obj(
                    "ok" -> true.asJson,
                    "message" -> "Configuration saved. Please restart Nebflow to apply.".asJson
                  )
                )
              yield resp
            case _ =>
              BadRequest(Json.obj("error" -> "Missing server, networkId, or secret".asJson))
          end match
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
    // The caller must be a trusted peer (verified by IP).
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

    // ===== NebLink File Transfer (P2P — peer IP auth) =====

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

    // Peer pushes a file via HTTP (peer IP auth)
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
   * Accepts incoming WS presence connections from NebLink peers.
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
          if !ms.isTrustedPeer(remoteIp) then Forbidden(Json.obj("error" -> "Not a trusted peer".asJson))
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

    // GET /teams/mounted — return all mounted teams with agent status
    // NOTE: Router mounts this under /api prefix, so full path is /api/teams/mounted
    case GET -> Root / "teams" / "mounted" =>
      for
        _ <- nebflow.core.flow.FlowTreeRegistry.awaitRestore(3000L)
        teamsJson <- buildMountedTeamsJson()
        result <- Ok(Json.obj("teams" -> teamsJson))
      yield result

    // GET /running-flows — list currently running DAG flow instances
    case GET -> Root / "running-flows" =>
      nebflow.core.flow.RunningFlowRegistry.listJson.flatMap(json => Ok(json))

    // GET /flow/dag/:name — return flow DAG structure (nodes, edges, routing)
    case GET -> Root / "flow" / "dag" / flowName =>
      if !isValidFlowName(flowName) then
        BadRequest(Json.obj("error" -> "Invalid flow name".asJson))
      else
        nebflow.core.entity.EntityLoader.loadFlow(flowName).flatMap {
          case Some(dag) =>
            val nodesJson = dag.nodes.map { (nodeId, node) =>
              val route: Json = node.onComplete match
                case nebflow.core.entity.NodeRoute.Goto(t) => Json.fromString(t)
                case nebflow.core.entity.NodeRoute.Return => Json.fromString("$return")
                case nebflow.core.entity.NodeRoute.Switch(expr, cases) =>
                  val casesObj = io.circe.JsonObject.fromIterable(cases.map { (k, v) =>
                    val target: String = v match
                      case nebflow.core.entity.NodeRoute.Goto(t) => t
                      case nebflow.core.entity.NodeRoute.Return => "$return"
                      case _ => "?"
                    k -> Json.fromString(target)
                  })
                  Json.obj("switch" -> Json.fromString(expr), "cases" -> casesObj.asJson)
              nodeId -> Json.obj(
                "agent" -> node.agent.asJson,
                "input" -> node.input.asJson,
                "onComplete" -> route,
                "onError" -> node.onError.map(_.toString.toLowerCase).asJson,
                "maxRetries" -> node.maxRetries.asJson
              )
            }
            Ok(Json.obj(
              "name" -> dag.name.asJson,
              "description" -> dag.description.asJson,
              "entry" -> dag.entry.asJson,
              "maxLoop" -> dag.maxLoop.asJson,
              "nodes" -> nodesJson.asJson
            ))
          case None =>
            NotFound(Json.obj("error" -> s"Flow '$flowName' not found".asJson))
        }

    // GET /teams — list all defined teams (from team.json files)
    case GET -> Root / "teams" =>
      for
        teams <- nebflow.core.entity.EntityLoader.listTeams()
        teamsJson = teams.values.toList.sortBy(_.name).map { t =>
          io.circe.Json.obj(
            "name" -> t.name.asJson,
            "description" -> t.description.asJson,
            "lead" -> t.lead.asJson,
            "members" -> t.members.asJson,
            "flows" -> t.flows.asJson
          )
        }
        result <- Ok(io.circe.Json.obj("teams" -> teamsJson.asJson))
      yield result

    // GET /teams/status/:sessionId — return mounted teams and agent status for frontend
    // NOTE: Router mounts this under /api prefix, so full path is /api/teams/status/:sessionId
    case GET -> Root / "teams" / "status" / sessionId =>
      // Validate sessionId: only UUID format (hex + dashes), no path traversal
      if sessionId.isEmpty || !sessionId.matches("^[a-fA-F0-9-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          teamsJson <- buildMountedTeamsJson()
          result <- Ok(Json.obj("sessionId" -> sessionId.asJson, "teams" -> teamsJson))
        yield result

    // GET /teams/mailbox/:sessionId/:teamName — mail history for a team
    case GET -> Root / "teams" / "mailbox" / sessionId / flowName =>
      if sessionId.isEmpty || !sessionId.matches("^[a-fA-F0-9-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          records <- nebflow.core.flow.FlowMailStore.load(sessionId, flowName)
          result <- Ok(Json.obj("records" -> records.asJson))
        yield result

    // DELETE /teams/mailbox/:sessionId/:teamName — clear mail history
    case DELETE -> Root / "teams" / "mailbox" / sessionId / flowName =>
      if sessionId.isEmpty || !sessionId.matches("^[a-fA-F0-9-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          _ <- nebflow.core.flow.FlowMailStore.clear(sessionId, flowName)
          result <- Ok(Json.obj("cleared" -> true.asJson))
        yield result

    // ===== Flow Editor APIs =====

    // GET /teams/def/:name — return team definition for the editor
    case GET -> Root / "teams" / "def" / flowName =>
      if !isValidFlowName(flowName) then
        BadRequest(Json.obj("error" -> "Invalid name".asJson))
      else
        for
          teamOpt <- EntityLoader.loadTeam(flowName)
          agents <- EntityLoader.listAgents()
          result <- teamOpt match
            case None => NotFound(Json.obj("error" -> s"Team '$flowName' not found".asJson))
            case Some(team) =>
              val leadEntry = agents.get(team.lead)
              val memberEntries = team.members.flatMap(agents.get)
              val allEntries = (leadEntry.toList ++ memberEntries)
              val agentsJson = allEntries.map { entry =>
                Json.obj(
                  "name" -> entry.name.asJson,
                  "description" -> entry.description.asJson,
                  "tools" -> entry.tools.asJson,
                  "systemPrompt" -> entry.systemPrompt.asJson
                )
              }
              Ok(Json.obj(
                "name" -> team.name.asJson,
                "manager" -> team.lead.asJson,
                "description" -> team.description.asJson,
                "agents" -> agentsJson.asJson,
                "type" -> "team".asJson
              ))
        yield result

    // GET /agents/list — list all global agents (for extends dropdown)
    case GET -> Root / "agents" / "list" =>
      for
        agents <- sharedResources.agentLibrary.loadAll()
        entries = agents.toList.sortBy(_._1).map { (name, defn) =>
          Json.obj(
            "name" -> name.asJson,
            "description" -> defn.description.asJson,
            "tools" -> defn.tools.asJson,
            "displayName" -> defn.displayName.asJson,
            "systemPrompt" -> defn.systemPrompt.asJson
          )
        }
        result <- Ok(Json.obj("agents" -> entries.asJson))
      yield result

    // GET /agents/:name — get global agent detail (system.md + tools)
    case GET -> Root / "agents" / agentName =>
      if !isValidAgentName(agentName) then
        BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
      else
        for
          agents <- sharedResources.agentLibrary.loadAll()
          result <- agents.get(agentName) match
            case None => NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
            case Some(defn) => Ok(Json.obj(
              "name" -> defn.name.asJson,
              "description" -> defn.description.asJson,
              "tools" -> defn.tools.asJson,
              "systemPrompt" -> defn.systemPrompt.asJson,
              "displayName" -> defn.displayName.asJson
            ))
        yield result

    // ===== Entity API (Team/Flow/Agent management) =====

    // GET /teams/:name — team detail
    case GET -> Root / "teams" / teamName =>
      if !isValidAgentName(teamName) then
        BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          teamOpt <- EntityLoader.loadTeam(teamName)
          result <- teamOpt match
            case None => NotFound(Json.obj("error" -> s"Team '$teamName' not found".asJson))
            case Some(team) => Ok(Json.obj(
              "name" -> team.name.asJson,
              "description" -> team.description.asJson,
              "lead" -> team.lead.asJson,
              "members" -> team.members.asJson,
              "flows" -> team.flows.asJson
            ))
        yield result

    // GET /team/rules/:name — read team rules.md
    case GET -> Root / "team" / "rules" / teamName =>
      if !isValidAgentName(teamName) then
        BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          rules <- EntityLoader.loadTeamRules(teamName)
          result <- Ok(Json.obj("content" -> rules.asJson))
        yield result

    // POST /team/rules/:name — save team rules.md + trigger reload
    case req @ POST -> Root / "team" / "rules" / teamName =>
      if !isValidAgentName(teamName) then
        BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          body <- req.as[Json]
          content = body.hcursor.downField("content").as[String].getOrElse("")
          rulesDir = PathUtil.dataRoot / "teams" / teamName
          _ <- IO.blocking {
            os.makeDir.all(rulesDir)
            val tmp = rulesDir / ".rules.md.tmp"
            os.write(tmp, content)
            os.move.over(tmp, rulesDir / "rules.md")
          }
          // Trigger reload so changes take effect on next activation
          _ <- FlowTreeRegistry.treesRef.get.flatMap { treeMap =>
            treeMap.values.toList.traverse_ { treeRef =>
              treeRef ! TreeCommand.ReloadDefinition(teamName)
            }
          }
          result <- Ok(Json.obj("saved" -> true.asJson))
        yield result

    // GET /entity-agents — list all agents (entity format, with useWhen)
    case GET -> Root / "entity-agents" =>
      for
        agents <- EntityLoader.listAgents()
        entries = agents.values.toList.sortBy(_.name).map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "useWhen" -> a.useWhen.asJson,
            "tools" -> a.tools.asJson,
            "voice" -> a.voice.asJson
          )
        }
        result <- Ok(Json.obj("agents" -> entries.asJson))
      yield result
  }

  /** Build mounted teams JSON for the frontend (GET /api/teams/mounted).
   *  Reads from live FlowMembership runtime state. Each team is a card with
   *  agent tiles showing status. */
  private def buildMountedTeamsJson(): IO[Json] =
    for
      flowsMap <- nebflow.core.flow.FlowMembership.listMountedFlows
      teams <- EntityLoader.listTeams()
      flows <- flowsMap.toList.sortBy(_._1).traverse { (instanceName, agents) =>
        val teamDefOpt = teams.get(instanceName)
        agents.traverse { (agentName, sid) =>
          nebflow.core.flow.FlowMembership.isBusy(sid).map { busy =>
            Json.obj(
              "name" -> agentName.asJson,
              "sessionId" -> sid.asJson,
              "status" -> (if busy then "running" else "idle").asJson,
              "manager" -> teamDefOpt.exists(_.lead == agentName).asJson
            )
          }
        }.map { agentsJson =>
          Json.obj(
            "name" -> instanceName.asJson,
            "type" -> "team".asJson,
            "agents" -> agentsJson.asJson,
            "flows" -> teamDefOpt.map(_.flows).getOrElse(List.empty[String]).asJson
          )
        }
      }
    yield flows.asJson

  // ── Flow editor helpers ──────────────────────────────────

  private def isValidFlowName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

  private def isValidAgentName(name: String): Boolean =
    name.nonEmpty && name.matches("^[a-zA-Z0-9][a-zA-Z0-9._-]*$") && !name.contains("..")

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
   * Verify peer-to-peer access via peer IP check.
   * Only requests from trusted peer IPs (discovered via NebLink Server) are accepted.
   * NebLink Server is the trust boundary — devices must be on the same network.
   */
  private def verifyPeerAccess(req: Request[IO]): IO[Either[Response[IO], NeblinkService]] =
    neblinkService match
      case None =>
        IO.pure(Left(Response[IO](Status.NotFound).withEntity(Json.obj("error" -> "NebLink not enabled".asJson))))
      case Some(ms) =>
        val remoteIp = req.remoteAddr.fold("")(a => a.toString)
        if ms.isTrustedPeer(remoteIp) then IO.pure(Right(ms))
        else
          IO.pure(
            Left(
              Response[IO](Status.Forbidden)
                .withEntity(Json.obj("error" -> s"Not a trusted peer (from $remoteIp)".asJson))
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
