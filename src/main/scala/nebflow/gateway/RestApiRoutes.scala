package nebflow.gateway

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.syntax.*
import io.circe.{Json, JsonObject, parser}
import nebflow.agent.SharedResources
import nebflow.core.PathUtil
import nebflow.core.daemon.{DaemonConfig, DaemonService, DaemonStore}
import nebflow.core.entity.{EntityLoader, NodeRoute}
import nebflow.core.flow.{FlowTreeRegistry, TreeCommand}
import nebflow.core.skill.SkillService
import nebflow.llm.NebflowServiceConfig
import nebflow.neblink.*
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
  ttsService: Option[TtsService] = None,
  neblinkDiscovery: Option[nebflow.neblink.NeblinkDiscovery] = None,
  gatewayPort: Int = 8080
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

    // GET /models/capability-tags — list predefined capability tags
    case req @ GET -> Root / "models" / "capability-tags" =>
      withAuth(req) {
        Ok(
          Json.obj(
            "tags" -> List(
              Json.obj("key" -> "vision".asJson, "label" -> "图片理解".asJson, "description" -> "支持 image_url 图片输入".asJson)
            ).asJson
          )
        )
      }

    // GET /models/capabilities — list all models with their capability tags
    case req @ GET -> Root / "models" / "capabilities" =>
      withAuth(req) {
        Ok(nebflow.llm.ModelRegistry.loadForApi.asJson)
      }

    // PUT /models/capabilities — update a single model's capability tags
    case req @ PUT -> Root / "models" / "capabilities" =>
      withAuth(req) {
        req.as[Json].flatMap { body =>
          val providerId = body.hcursor.downField("providerId").as[String].getOrElse("")
          val modelId = body.hcursor.downField("modelId").as[String].getOrElse("")
          val vision = body.hcursor.downField("vision").as[Boolean].getOrElse(false)
          val capabilities = body.hcursor.downField("capabilities").as[List[String]].getOrElse(Nil)
          if providerId.nonEmpty && modelId.nonEmpty then
            val key = s"$providerId/$modelId"
            val current = nebflow.llm.ModelRegistry.loadForApi
            val updatedEntry = current.models.get(key) match
              case Some(existing) => existing.copy(vision = vision, capabilities = capabilities)
              case None => nebflow.llm.ModelRegistry.ModelEntry(vision = vision, capabilities = capabilities)
            val updatedModels = current.models + (key -> updatedEntry)
            nebflow.llm.ModelRegistry.save(updatedModels)
            Ok(Json.obj("status" -> "ok".asJson))
          else BadRequest(Json.obj("error" -> "providerId and modelId required".asJson))
        }
      }

    // Agents — three-layer aggregation (global + team + flow)
    case req @ GET -> Root / "agents" =>
      withAuth(req) {
        for
          globalAgents <- EntityLoader.listAgents()
          teams <- EntityLoader.listTeams()
          teamAgentEntries <- teams.toList.traverse { (teamName, _) =>
            IO.blocking {
              val dir = PathUtil.dataRoot / "teams" / teamName / "agents"
              if os.exists(dir) then
                os.list(dir)
                  .filter(os.isDir)
                  .flatMap(d => EntityLoader.loadAgentFromDir(d))
                  .map(a => (teamName, a))
                  .toList
              else Nil
            }
          }
          flows <- EntityLoader.listFlows()
          flowAgentEntries <- flows.toList.traverse { (flowName, _) =>
            IO.blocking {
              val dir = PathUtil.dataRoot / "flows" / flowName / "agents"
              if os.exists(dir) then
                os.list(dir)
                  .filter(os.isDir)
                  .flatMap(d => EntityLoader.loadAgentFromDir(d))
                  .map(a => (flowName, a))
                  .toList
              else Nil
            }
          }
          globalList = globalAgents.values.filter(_.name != "Nebula").toList.map { a =>
            Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.name.asJson,
              "category" -> a.category.asJson,
              "layer" -> "global".asJson
            )
          }
          teamList = teamAgentEntries.flatten.map { (scope, a) =>
            Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.name.asJson,
              "category" -> "team".asJson,
              "layer" -> "team".asJson,
              "scope" -> scope.asJson
            )
          }
          flowList = flowAgentEntries.flatten.map { (scope, a) =>
            Json.obj(
              "name" -> a.name.asJson,
              "description" -> a.description.asJson,
              "displayName" -> a.name.asJson,
              "category" -> "flow".asJson,
              "layer" -> "flow".asJson,
              "scope" -> scope.asJson
            )
          }
          all = globalList ++ teamList ++ flowList
          result <- Ok(Json.obj("agents" -> all.asJson))
        yield result
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

    // Enroll device via pairing code — calls the NebLink Server's
    // /api/device/enroll, receives a long-lived device credential, persists it,
    // and writes the neblink config so the device joins on next start.
    case req @ POST -> Root / "neblink" / "enroll" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else
        req.as[Json].flatMap { body =>
          val serverOpt = body.hcursor
            .downField("server")
            .as[Option[String]]
            .toOption
            .flatten
            .map(_.stripSuffix("/"))
          val pairCodeOpt = body.hcursor.downField("pairCode").as[Option[String]].toOption.flatten
          (serverOpt, pairCodeOpt) match
            case (Some(server), Some(pairCode)) =>
              neblinkService match
                case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
                case Some(ms) =>
                  for
                    identity <- ms.identity
                    enrollBody = Json
                      .obj(
                        "pairCode" -> pairCode.asJson,
                        "deviceId" -> identity.deviceId.asJson,
                        "deviceName" -> identity.deviceName.asJson,
                        "platform" -> identity.platform.asJson
                      )
                      .noSpaces
                    result <- enrollWithServer(server, enrollBody)
                    resp <- result match
                      case Right(credJson) =>
                        val deviceToken = credJson.hcursor.downField("deviceToken").as[String].toOption
                        val networkId = credJson.hcursor.downField("networkId").as[String].toOption.getOrElse("")
                        val deviceId =
                          credJson.hcursor.downField("deviceId").as[String].toOption.getOrElse(identity.deviceId)
                        deviceToken match
                          case Some(token) =>
                            val credential = DeviceCredential(server, networkId, deviceId, token)
                            val newConfig = NeblinkServerConfig(
                              url = server,
                              networkId = networkId,
                              secret = "",
                              deviceToken = Some(token)
                            )
                            for
                              _ <- DeviceCredential.save(credential)
                              current <- NeblinkConfig.load
                              updated = current.copy(enabled = true, neblinkServer = Some(newConfig))
                              _ <- NeblinkConfig.save(updated)
                              r <- Ok(
                                Json.obj(
                                  "ok" -> true.asJson,
                                  "message" -> "Enrolled. Please restart Nebflow to connect.".asJson,
                                  "networkId" -> networkId.asJson
                                )
                              )
                            yield r
                          case None =>
                            BadRequest(Json.obj("error" -> "Server did not return a device token".asJson))
                        end match
                      case Left(err) =>
                        BadRequest(Json.obj("error" -> s"Enrollment failed: $err".asJson))
                  yield resp
              end match
            case _ =>
              BadRequest(Json.obj("error" -> "Missing server or pairCode".asJson))
          end match
        }

    // ===== Device authorization flow (Tailscale-style) =====

    // Start device flow: proxy to neblink-server's /api/device/code.
    // Returns {deviceCode, userCode, verificationUri} for the frontend.
    case req @ POST -> Root / "neblink" / "device-flow" / "start" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else
        neblinkService match
          case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
          case Some(ms) =>
            for
              identity <- ms.identity
              // The neblink-server URL: read from existing config, or use the
              // public default. For device flow the server must be reachable
              // from both the browser (for OAuth) and the device (for polling).
              serverUrl <- neblinkServerUrl(None)
              body = Json
                .obj(
                  "deviceId" -> identity.deviceId.asJson,
                  "deviceName" -> identity.deviceName.asJson,
                  "platform" -> identity.platform.asJson
                )
                .noSpaces
              result <- proxyPost(serverUrl, "/api/device/code", body)
              resp <- result match
                case Right(json) => Ok(json)
                case Left(err) => BadRequest(Json.obj("error" -> s"Failed to start device flow: $err".asJson))
            yield resp

    // Poll device flow: proxy to neblink-server's /api/device/token.
    // On success, persist the device credential + update config + hot-swap client.
    case req @ POST -> Root / "neblink" / "device-flow" / "poll" =>
      if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
      else
        req.as[Json].flatMap { body =>
          val deviceCode = body.hcursor.downField("deviceCode").as[String].getOrElse("")
          val serverUrl = body.hcursor
            .downField("serverUrl")
            .as[Option[String]]
            .toOption
            .flatten
            .map(_.stripSuffix("/"))
          if deviceCode.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceCode".asJson))
          else
            neblinkService match
              case None => BadRequest(Json.obj("error" -> "NebLink service not initialized".asJson))
              case Some(ms) =>
                for
                  resolvedUrl <- neblinkServerUrl(serverUrl)
                  pollBody = Json.obj("deviceCode" -> deviceCode.asJson).noSpaces
                  result <- proxyPost(resolvedUrl, "/api/device/token", pollBody)
                  resp <- result match
                    case Right(json) =>
                      // Success — persist credential + update config + hot-swap.
                      val deviceToken = json.hcursor.downField("deviceToken").as[String].toOption
                      val networkId = json.hcursor.downField("networkId").as[String].toOption.getOrElse("")
                      deviceToken match
                        case Some(tok) =>
                          for
                            identity <- ms.identity
                            cred = DeviceCredential(resolvedUrl, networkId, identity.deviceId, tok)
                            _ <- DeviceCredential.save(cred)
                            newConfig = NeblinkServerConfig(
                              url = resolvedUrl,
                              networkId = networkId,
                              secret = "",
                              deviceToken = Some(tok)
                            )
                            current <- NeblinkConfig.load
                            updated = current.copy(enabled = true, neblinkServer = Some(newConfig))
                            _ <- NeblinkConfig.save(updated)
                            // Hot-swap the client in the discovery service.
                            _ <- neblinkDiscovery
                              .fold(IO.unit)(d => d.setClient(Some(new NeblinkClient(newConfig, gatewayPort))))
                            // Trigger immediate re-discovery.
                            _ <- ms.sendSync(nebflow.neblink.SyncCommand.PeerDiscovered)
                            r <- Ok(
                              Json.obj(
                                "ok" -> true.asJson,
                                "networkId" -> networkId.asJson
                              )
                            )
                          yield r
                        case None =>
                          BadRequest(Json.obj("error" -> "Server did not return a device token".asJson))
                      end match
                    case Left(err) =>
                      // authorization_pending is expected during polling — pass through.
                      BadRequest(Json.obj("error" -> err.asJson))
                yield resp
            end match
          end if
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
      if !isValidFlowName(flowName) then BadRequest(Json.obj("error" -> "Invalid flow name".asJson))
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
            Ok(
              Json.obj(
                "name" -> dag.name.asJson,
                "description" -> dag.description.asJson,
                "entry" -> dag.entry.asJson,
                "maxLoop" -> dag.maxLoop.asJson,
                "nodes" -> nodesJson.asJson
              )
            )
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
            "members" -> t.members.asJson
          )
        }
        result <- Ok(io.circe.Json.obj("teams" -> teamsJson.asJson))
      yield result

    // GET /teams/status/:sessionId — return mounted teams and agent status for frontend
    // NOTE: Router mounts this under /api prefix, so full path is /api/teams/status/:sessionId
    case GET -> Root / "teams" / "status" / sessionId =>
      // Validate sessionId: alphanumerics, dot, underscore, hyphen (covers UUID
      // and flow-node ids like dag-git-merge-scanner-405090), no path traversal
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          teamsJson <- buildMountedTeamsJson()
          result <- Ok(Json.obj("sessionId" -> sessionId.asJson, "teams" -> teamsJson))
        yield result

    // GET /teams/mailbox/:sessionId/:teamName — mail history for a team
    case GET -> Root / "teams" / "mailbox" / sessionId / flowName =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          records <- nebflow.core.flow.FlowMailStore.load(sessionId, flowName)
          result <- Ok(Json.obj("records" -> records.asJson))
        yield result

    // DELETE /teams/mailbox/:sessionId/:teamName — clear mail history
    case DELETE -> Root / "teams" / "mailbox" / sessionId / flowName =>
      if sessionId.isEmpty || !sessionId.matches("^[a-zA-Z0-9._-]{1,64}$") then
        BadRequest(Json.obj("error" -> "Invalid sessionId".asJson))
      else
        for
          _ <- nebflow.core.flow.FlowMailStore.clear(sessionId, flowName)
          result <- Ok(Json.obj("cleared" -> true.asJson))
        yield result

    // ===== Flow Editor APIs =====

    // GET /teams/def/:name — return team definition for the editor
    case GET -> Root / "teams" / "def" / flowName =>
      if !isValidFlowName(flowName) then BadRequest(Json.obj("error" -> "Invalid name".asJson))
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
              Ok(
                Json.obj(
                  "name" -> team.name.asJson,
                  "manager" -> team.lead.asJson,
                  "description" -> team.description.asJson,
                  "agents" -> agentsJson.asJson,
                  "type" -> "team".asJson
                )
              )
        yield result

    // GET /agents/list — list all global agents (for extends dropdown)
    // GET /agents/list — list all agents (all three layers)
    case GET -> Root / "agents" / "list" =>
      for
        globalAgents <- EntityLoader.listAgents()
        teams <- EntityLoader.listTeams()
        teamAgentEntries <- teams.toList.traverse { (teamName, _) =>
          IO.blocking {
            val dir = PathUtil.dataRoot / "teams" / teamName / "agents"
            if os.exists(dir) then
              os.list(dir)
                .filter(os.isDir)
                .flatMap(d => EntityLoader.loadAgentFromDir(d))
                .toList
            else Nil
          }
        }
        flows <- EntityLoader.listFlows()
        flowAgentEntries <- flows.toList.traverse { (flowName, _) =>
          IO.blocking {
            val dir = PathUtil.dataRoot / "flows" / flowName / "agents"
            if os.exists(dir) then
              os.list(dir)
                .filter(os.isDir)
                .flatMap(d => EntityLoader.loadAgentFromDir(d))
                .toList
            else Nil
          }
        }
        globalList = globalAgents.values.filter(_.name != "Nebula").map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> a.category.asJson
          )
        }
        teamList = teamAgentEntries.flatten.map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> "team".asJson
          )
        }
        flowList = flowAgentEntries.flatten.map { a =>
          Json.obj(
            "name" -> a.name.asJson,
            "description" -> a.description.asJson,
            "tools" -> a.tools.asJson,
            "displayName" -> a.name.asJson,
            "systemPrompt" -> a.systemPrompt.asJson,
            "category" -> "flow".asJson
          )
        }
        all = globalList ++ teamList ++ flowList
        result <- Ok(Json.obj("agents" -> all.asJson))
      yield result

    // GET /agents/:name — get agent detail (system.md + tools) — searches all three layers
    case GET -> Root / "agents" / agentName =>
      if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
      else
        for
          agentOpt <- EntityLoader.findAgentByName(agentName)
          result <- agentOpt match
            case None => NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
            case Some(defn) =>
              Ok(
                Json.obj(
                  "name" -> defn.name.asJson,
                  "description" -> defn.description.asJson,
                  "tools" -> defn.tools.asJson,
                  "systemPrompt" -> defn.systemPrompt.asJson,
                  "displayName" -> defn.displayName.getOrElse(defn.name).asJson,
                  "model" -> defn.model.asJson,
                  "skills" -> defn.skills.asJson,
                  "flows" -> defn.flows.asJson
                )
              )
        yield result

    // GET /agents/:name/model — get agent's model configuration — searches all three layers
    case GET -> Root / "agents" / agentName / "model" =>
      if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
      else
        for
          agentOpt <- EntityLoader.findAgentByName(agentName)
          result <- agentOpt match
            case None => NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
            case Some(defn) =>
              val modelConfig = defn.model.getOrElse(nebflow.shared.AgentModelConfig.empty)
              sharedResources.runtimeModels.get.flatMap { runtimeModels =>
                val current = runtimeModels.values.headOption
                Ok(
                  Json.obj(
                    "model" -> modelConfig.asJson,
                    "current" -> current.asJson,
                    "preferred" -> modelConfig.preferred.asJson,
                    "fallbacks" -> modelConfig.fallbacks.asJson,
                    "default" -> modelConfig.preferred.asJson
                  )
                )
              }
        yield result

    // PUT /agents/:name/model — update agent's model configuration
    case req @ PUT -> Root / "agents" / agentName / "model" =>
      if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
      else
        req.as[Json].flatMap { body =>
          // Parse the model config from request body
          io.circe.parser.decode[nebflow.shared.AgentModelConfig](body.noSpaces) match
            case Right(modelConfig) =>
              for
                dirOpt <- EntityLoader.findAgentDir(agentName)
                result <- dirOpt match
                  case Some(dir) =>
                    IO.blocking {
                      val jsonPath = dir / "agent.json"
                      val json = os.read(jsonPath)
                      io.circe.parser.parse(json) match
                        case Right(parsed) =>
                          val updated = parsed.deepMerge(Json.obj("model" -> modelConfig.asJson))
                          os.write.over(jsonPath, updated.noSpaces)
                          true
                        case Left(_) => false
                    }.flatMap {
                      case true =>
                        Ok(Json.obj("updated" -> true.asJson, "model" -> modelConfig.asJson))
                      case false =>
                        InternalServerError(Json.obj("error" -> "Failed to write agent.json".asJson))
                    }
                  case None =>
                    NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
              yield result
            case Left(err) =>
              BadRequest(Json.obj("error" -> s"Invalid model config: ${err.getMessage}".asJson))
        }

    // ===== Entity API (Team/Flow/Agent management) =====

    // PUT /agents/:name — update agent's skills/flows
    case req @ PUT -> Root / "agents" / agentName =>
      if !isValidAgentName(agentName) then BadRequest(Json.obj("error" -> "Invalid agent name".asJson))
      else
        req.as[Json].flatMap { body =>
          for
            dirOpt <- EntityLoader.findAgentDir(agentName)
            result <- dirOpt match
              case Some(dir) =>
                IO.blocking {
                  val jsonPath = dir / "agent.json"
                  val json = os.read(jsonPath)
                  io.circe.parser.parse(json) match
                    case Right(parsed) =>
                      // Extract skills/flows from request body
                      val skillsOpt = body.hcursor.downField("skills").as[Option[List[String]]]
                      val flowsOpt = body.hcursor.downField("flows").as[Option[List[String]]]
                      val merged = parsed
                        .deepMerge(skillsOpt.toOption.map(s => Json.obj("skills" -> s.asJson)).getOrElse(Json.obj()))
                        .deepMerge(flowsOpt.toOption.map(f => Json.obj("flows" -> f.asJson)).getOrElse(Json.obj()))
                      os.write.over(jsonPath, merged.noSpaces)
                      true
                    case Left(_) => false
                }.flatMap {
                  case true =>
                    Ok(Json.obj("updated" -> true.asJson))
                  case false =>
                    InternalServerError(Json.obj("error" -> "Failed to write agent.json".asJson))
                }
              case None =>
                NotFound(Json.obj("error" -> s"Agent '$agentName' not found".asJson))
          yield result
        }

    // GET /skills — list all available skills (name + description) for the Agent panel
    case GET -> Root / "skills" =>
      for
        skills <- SkillService.listSkills()
        entries = skills.sortBy(_.name).map { s =>
          Json.obj(
            "name" -> s.name.asJson,
            "description" -> s.description.asJson
          )
        }
        result <- Ok(Json.obj("skills" -> entries.asJson))
      yield result

    // GET /flows/list — list all flow definitions (name, description, node count, maxLoop)
    case GET -> Root / "flows" / "list" =>
      for
        flows <- EntityLoader.listFlows()
        entries = flows.values.toList.sortBy(_.name).map { f =>
          val edges = f.nodes.toList.flatMap { (nodeId, node) =>
            node.onComplete match
              case NodeRoute.Goto(target) => List((nodeId, target, None))
              case NodeRoute.Return => List((nodeId, "$return", None))
              case NodeRoute.Switch(_, cases) =>
                cases.toList.map { (cond, route) =>
                  val target = route match
                    case NodeRoute.Goto(t) => t
                    case NodeRoute.Return => "$return"
                    case _ => "?"
                  (nodeId, target, Some(cond))
                }
          }
          Json.obj(
            "name" -> f.name.asJson,
            "description" -> f.description.asJson,
            "entry" -> f.entry.asJson,
            "maxLoop" -> f.maxLoop.asJson,
            "nodeCount" -> f.nodes.size.asJson,
            "nodes" -> f.nodes.toList.sortBy(_._1).map { (nodeId, node) =>
              Json.obj(
                "nodeId" -> nodeId.asJson,
                "agent" -> node.agent.asJson
              )
            }.asJson,
            "edges" -> edges.map { (from, to, cond) =>
              Json.obj("from" -> from.asJson, "to" -> to.asJson, "condition" -> cond.asJson)
            }.asJson
          )
        }
        result <- Ok(Json.obj("flows" -> entries.asJson))
      yield result

    // GET /teams/:name — team detail
    case GET -> Root / "teams" / teamName =>
      if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          teamOpt <- EntityLoader.loadTeam(teamName)
          result <- teamOpt match
            case None => NotFound(Json.obj("error" -> s"Team '$teamName' not found".asJson))
            case Some(team) =>
              Ok(
                Json.obj(
                  "name" -> team.name.asJson,
                  "description" -> team.description.asJson,
                  "lead" -> team.lead.asJson,
                  "members" -> team.members.asJson
                )
              )
        yield result

    // GET /team/rules/:name — read team rules.md
    case GET -> Root / "team" / "rules" / teamName =>
      if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
      else
        for
          rules <- EntityLoader.loadTeamRules(teamName)
          result <- Ok(Json.obj("content" -> rules.asJson))
        yield result

    // POST /team/rules/:name — save team rules.md + trigger reload
    case req @ POST -> Root / "team" / "rules" / teamName =>
      if !isValidAgentName(teamName) then BadRequest(Json.obj("error" -> "Invalid team name".asJson))
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
            "voice" -> a.voice.asJson,
            "category" -> a.category.asJson
          )
        }
        result <- Ok(Json.obj("agents" -> entries.asJson))
      yield result

    // ===== Daemon Management =====

    // GET /daemons — list all daemons with runtime state
    case req @ GET -> Root / "daemons" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => Ok(Json.obj("daemons" -> List.empty[String].asJson))
          case Some(svc) =>
            val store = new DaemonStore()
            store.load().flatMap { configs =>
              svc.getStates(configs).flatMap { states =>
                val cfgById = configs.map(c => c.id -> c).toMap
                val statesJson = states.map { st =>
                  st.asJson.deepMerge(
                    Json.obj(
                      "autoStart" -> cfgById.get(st.id).exists(_.autoStart).asJson,
                      "restartOnExit" -> cfgById.get(st.id).exists(_.restartOnExit).asJson
                    )
                  )
                }
                Ok(Json.obj("daemons" -> statesJson.asJson))
              }
            }
      }

    // PUT /daemons/:id — update daemon config (autoStart / restartOnExit)
    case req @ PUT -> Root / "daemons" / daemonId =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(_) =>
            req.as[Json].flatMap { body =>
              val autoStartOpt = body.hcursor.downField("autoStart").as[Option[Boolean]].toOption.flatten
              val restartOnExitOpt = body.hcursor.downField("restartOnExit").as[Option[Boolean]].toOption.flatten
              if autoStartOpt.isEmpty && restartOnExitOpt.isEmpty then
                BadRequest(Json.obj("error" -> "No updatable fields provided (expected autoStart or restartOnExit)".asJson))
              else
                val store = new DaemonStore()
                store.update(
                  daemonId,
                  cfg =>
                    cfg.copy(
                      autoStart = autoStartOpt.getOrElse(cfg.autoStart),
                      restartOnExit = restartOnExitOpt.getOrElse(cfg.restartOnExit)
                    )
                ).flatMap {
                  case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
                  case Some(updated) => Ok(updated.asJson)
                }
            }
      }

    // POST /daemons — create a new daemon
    case req @ POST -> Root / "daemons" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            req.as[Json].flatMap { body =>
              val id = body.hcursor.downField("id").as[String].getOrElse("")
              val name = body.hcursor.downField("name").as[String].getOrElse("")
              val command = body.hcursor.downField("command").as[List[String]].getOrElse(List.empty)
              val cwd = body.hcursor.downField("cwd").as[Option[String]].toOption.flatten
              val env = body.hcursor.downField("env").as[Map[String, String]].getOrElse(Map.empty)
              val autoStart = body.hcursor.downField("autoStart").as[Boolean].getOrElse(false)
              val restartOnExit = body.hcursor.downField("restartOnExit").as[Boolean].getOrElse(false)

              if id.isEmpty || name.isEmpty || command.isEmpty then
                BadRequest(Json.obj("error" -> "Missing required fields: id, name, command".asJson))
              else
                val config = DaemonConfig(
                  id = id,
                  name = name,
                  command = command,
                  cwd = cwd,
                  env = env,
                  autoStart = autoStart,
                  restartOnExit = restartOnExit
                )
                val store = new DaemonStore()
                store.add(config).flatMap { _ =>
                  // Auto-start if requested
                  val startIO = if autoStart then svc.start(config).void.handleErrorWith(_ => IO.unit) else IO.unit
                  startIO *> svc.getState(id).flatMap {
                    case Some(state) => Ok(state.asJson)
                    case None => Ok(config.asJson)
                  }
                }
              end if
            }
      }

    // DELETE /daemons/:id — remove a daemon (stops if running)
    case req @ DELETE -> Root / "daemons" / daemonId =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            svc.stop(daemonId).flatMap { _ =>
              val store = new DaemonStore()
              store.remove(daemonId) *> Ok(Json.obj("deleted" -> true.asJson))
            }
      }

    // POST /daemons/:id/start — start a daemon
    case req @ POST -> Root / "daemons" / daemonId / "start" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            val store = new DaemonStore()
            svc.startById(store, daemonId).flatMap {
              case Some(state) => Ok(state.asJson)
              case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
            }
      }

    // POST /daemons/:id/stop — stop a daemon
    case req @ POST -> Root / "daemons" / daemonId / "stop" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            svc.stop(daemonId).flatMap {
              case Some(state) => Ok(state.asJson)
              case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
            }
      }

    // POST /daemons/:id/restart — restart a daemon
    case req @ POST -> Root / "daemons" / daemonId / "restart" =>
      withAuth(req) {
        sharedResources.daemonService match
          case None => NotFound(Json.obj("error" -> "Daemon service not available".asJson))
          case Some(svc) =>
            val store = new DaemonStore()
            svc.restart(store, daemonId).flatMap {
              case Some(state) => Ok(state.asJson)
              case None => NotFound(Json.obj("error" -> s"Daemon '$daemonId' not found".asJson))
            }
      }
  }

  /**
   * Build mounted teams JSON for the frontend (GET /api/teams/mounted).
   *  Reads from live TeamSessionRegistry runtime state. Each team is a card with
   *  agent tiles showing status.
   */
  private def buildMountedTeamsJson(): IO[Json] =
    for
      teamsMap <- nebflow.core.flow.TeamSessionRegistry.listMountedTeams
      teams <- EntityLoader.listTeams()
      teamsList <- teamsMap.toList.sortBy(_._1).traverse { (instanceName, agents) =>
        val teamDefOpt = teams.get(instanceName)
        agents
          .traverse { (agentName, sid) =>
            nebflow.core.flow.TeamSessionRegistry.isBusy(sid).map { busy =>
              Json.obj(
                "name" -> agentName.asJson,
                "sessionId" -> sid.asJson,
                "status" -> (if busy then "running" else "idle").asJson,
                "manager" -> teamDefOpt.exists(_.lead == agentName).asJson
              )
            }
          }
          .map { agentsJson =>
            Json.obj(
              "name" -> instanceName.asJson,
              "type" -> "team".asJson,
              "agents" -> agentsJson.asJson
            )
          }
      }
    yield teamsList.asJson

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

  /**
   * POST the enrollment body to the NebLink Server and parse the JSON reply.
   * Uses java.net.http directly (mirrors NeblinkClient) to avoid pulling an
   * http4s client dependency into this routes class. Bypasses the system proxy
   * so direct LAN access works.
   */
  private def enrollWithServer(serverUrl: String, body: String): IO[Either[String, Json]] =
    proxyPost(serverUrl, "/api/device/enroll", body)

  /**
   * Generic POST proxy to the NebLink Server. Returns the parsed JSON on
   * success (2xx) or an error message on failure. Bypasses the system proxy.
   */
  private def proxyPost(serverUrl: String, path: String, body: String): IO[Either[String, Json]] =
    IO.blocking {
      val client = java.net.http.HttpClient
        .newBuilder()
        .proxy(java.net.ProxySelector.of(null))
        .build()
      val request = java.net.http.HttpRequest
        .newBuilder()
        .uri(java.net.URI.create(s"${serverUrl.stripSuffix("/")}$path"))
        .timeout(java.time.Duration.ofSeconds(15))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
        .build()
      try
        val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val respBody = response.body()
        if status >= 200 && status < 300 then
          parser.parse(respBody) match
            case Right(json) => Right(json)
            case Left(err) => Left(s"invalid JSON from server: ${err.message}")
        else
          // Surface the server's error message if it's JSON (e.g. authorization_pending).
          parser.parse(respBody) match
            case Right(json) =>
              json.hcursor.downField("error").as[String].toOption match
                case Some(errMsg) => Left(errMsg)
                case None => Left(s"HTTP $status")
            case Left(_) => Left(s"HTTP $status")
      catch case e: Exception => Left(e.getMessage)
      end try
    }.handleErrorWith(e => IO.pure(Left(e.getMessage)))

  /**
   * Resolve the NebLink Server URL for device-flow requests. Priority:
   * 1. Explicitly provided URL (from the request body).
   * 2. URL from the current neblink config.
   * 3. The public default URL.
   */
  private def neblinkServerUrl(explicit: Option[String] = None): IO[String] =
    explicit match
      case Some(url) => IO.pure(url)
      case None =>
        neblinkService match
          case Some(ms) =>
            ms.neblinkConfig.map(_.neblinkServer.map(_.url)).flatMap {
              case Some(url) => IO.pure(url)
              case None => IO.pure("https://neblink.nebflow.space")
            }
          case None => IO.pure("https://neblink.nebflow.space")
end RestApiRoutes
