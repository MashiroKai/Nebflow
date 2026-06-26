package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.agent.SharedResources
import nebflow.llm.NebflowServiceConfig
import nebflow.mesh.MeshService
import nebflow.service.ConfigService
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.Authorization
import org.typelevel.ci.CIStringSyntax

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
  meshService: Option[MeshService] = None
):
  private val logger = nebflow.core.NebflowLogger.forName("nebflow.rest-api")

  def routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    // Health check
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> "ok".asJson, "version" -> nebflow.Version.string.asJson))

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

    // ===== Mesh API =====

    // Mesh status — identity, login state, peers
    case req @ GET -> Root / "mesh" / "status" =>
      withMesh(req) { ms =>
        ms.identity.flatMap { id =>
          ms.isLoggedIn.flatMap { loggedIn =>
            ms.account.flatMap { accOpt =>
              ms.meshConfig.flatMap { cfg =>
                ms.peers.flatMap { peersList =>
                  Ok(
                    Json.obj(
                      "loggedIn" -> loggedIn.asJson,
                      "username" -> accOpt.map(_.username).asJson,
                      "device" -> Json.obj(
                        "id" -> id.deviceId.asJson,
                        "name" -> id.deviceName.asJson,
                        "platform" -> id.platform.asJson
                      ),
                      "cloudUrl" -> cfg.cloudUrl.asJson,
                      "peers" -> peersList
                        .map(p =>
                          Json.obj(
                            "deviceId" -> p.deviceId.asJson,
                            "deviceName" -> p.deviceName.asJson,
                            "platform" -> p.platform.asJson,
                            "address" -> p.address.asJson,
                            "lastSeen" -> p.lastSeen.asJson
                          )
                        )
                        .asJson
                    )
                  )
                }
              }
            }
          }
        }
      }

    // Check username availability
    case req @ GET -> Root / "mesh" / "check-username" =>
      withMesh(req) { ms =>
        val username = req.params.getOrElse("username", "")
        ms.checkUsernameAvailable(username).flatMap { available =>
          Ok(Json.obj("available" -> available.asJson))
        }
      }

    // Register a new Nebflow account
    case req @ POST -> Root / "mesh" / "register" =>
      withMesh(req) { ms =>
        req.as[Json].flatMap { body =>
          val hc = body.hcursor
          val username = hc.downField("username").as[String].toOption.filter(_.nonEmpty)
          val password = hc.downField("password").as[String].toOption.filter(_.nonEmpty)
          (username, password) match
            case (Some(u), Some(p)) =>
              ms.register(u, p)
                .flatMap { acc =>
                  Ok(Json.obj("ok" -> true.asJson, "username" -> acc.username.asJson))
                }
                .handleErrorWith { e =>
                  BadRequest(Json.obj("error" -> e.getMessage.asJson))
                }
            case _ =>
              BadRequest(Json.obj("error" -> "Missing username or password".asJson))
        }
      }

    // Login to an existing account
    case req @ POST -> Root / "mesh" / "login" =>
      withMesh(req) { ms =>
        req.as[Json].flatMap { body =>
          val hc = body.hcursor
          val username = hc.downField("username").as[String].toOption.filter(_.nonEmpty)
          val password = hc.downField("password").as[String].toOption.filter(_.nonEmpty)
          (username, password) match
            case (Some(u), Some(p)) =>
              ms.login(u, p)
                .flatMap { acc =>
                  Ok(Json.obj("ok" -> true.asJson, "username" -> acc.username.asJson))
                }
                .handleErrorWith { e =>
                  BadRequest(Json.obj("error" -> e.getMessage.asJson))
                }
            case _ =>
              BadRequest(Json.obj("error" -> "Missing username or password".asJson))
        }
      }

    // Logout — clear account, stop discovery
    case req @ POST -> Root / "mesh" / "logout" =>
      withMesh(req) { ms =>
        ms.logout *> Ok(Json.obj("ok" -> true.asJson))
      }

    // Handshake — called by a discovered peer to establish trust and exchange device secrets
    case req @ POST -> Root / "mesh" / "handshake" =>
      meshService match
        case None => NotFound(Json.obj("error" -> "Mesh not enabled".asJson))
        case Some(ms) =>
          // Bearer format: userId:callerDeviceSecret
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
            val callerUserId = parts(0)
            val callerSecret = parts(1)
            val callerIp = req.remoteAddr.fold("unknown")(_.toString)
            req.as[Json].flatMap { body =>
              val hc = body.hcursor
              val deviceId = hc.downField("deviceId").as[String].getOrElse("")
              val deviceName = hc.downField("deviceName").as[String].getOrElse("Unknown")
              val platform = hc.downField("platform").as[String].getOrElse("")
              val port = hc.downField("port").as[Int].getOrElse(8080)
              if deviceId.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceId".asJson))
              else
                ms.handleHandshake(callerUserId, deviceId, deviceName, platform, callerIp, port, callerSecret)
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

    // Trigger sync — removed (file sync deleted)

    // Update mesh config (e.g. cloudUrl)
    case req @ PATCH -> Root / "mesh" / "config" =>
      withMesh(req) { ms =>
        req.as[Json].flatMap { body =>
          val cloudUrl = body.hcursor.downField("cloudUrl").as[Option[String]].toOption.flatten
          val syncInterval = body.hcursor.downField("syncIntervalSec").as[Option[Int]].toOption.flatten
          ms.updateConfig { cfg =>
            cfg.copy(
              cloudUrl = cloudUrl.orElse(cfg.cloudUrl),
              syncIntervalSec = syncInterval.getOrElse(cfg.syncIntervalSec)
            )
          } *> Ok(Json.obj("ok" -> true.asJson))
        }
      }

    // Cloud session sync toggle — removed (session sync deleted)

    // Update device capabilities / user description
    case req @ PUT -> Root / "mesh" / "device-info" =>
      withMesh(req) { ms =>
        req.as[Json].flatMap { body =>
          val userDesc = body.hcursor.downField("userDescription").as[Option[String]].toOption.flatten
          val caps = body.hcursor.downField("capabilities").as[Option[Map[String, String]]].toOption.flatten
          ms.updateDeviceInfo(userDescription = userDesc, capabilities = caps) *>
            Ok(Json.obj("ok" -> true.asJson))
        }
      }

    // File sync endpoints (fingerprints, file GET/PUT) — removed

    // Peer notification — lightweight ping to trigger immediate sync
    case req @ POST -> Root / "mesh" / "notify" =>
      verifyPeerAccess(req).flatMap {
        case Left(resp) => IO.pure(resp)
        case Right(ms) =>
          Ok(Json.obj("ok" -> true.asJson))
      }

    case req @ POST -> Root / "mesh" / "remote-exec" =>
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
                val ctx = nebflow.core.tools.ToolContext(
                  projectRoot = System.getProperty("user.dir", ".")
                )
                tool.call(params, ctx).flatMap {
                  case Right(result) => Ok(Json.obj("output" -> result.asJson))
                  case Left(err) => Ok(Json.obj("error" -> err.message.asJson, "output" -> "".asJson))
                }
              case None =>
                BadRequest(Json.obj("error" -> s"Unknown tool: $action".asJson))
          }
      }
  }

  private def withAuth(req: Request[IO])(f: => IO[Response[IO]]): IO[Response[IO]] =
    if checkAuth(req) then f
    else Forbidden(Json.obj("error" -> "Unauthorized".asJson))

  /** Run block only if MeshService is available and request is authenticated. */
  private def withMesh(req: Request[IO])(f: MeshService => IO[Response[IO]]): IO[Response[IO]] =
    if !checkAuth(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
    else
      meshService match
        case Some(ms) => f(ms)
        case None => NotFound(Json.obj("error" -> "Mesh not enabled".asJson))

  /** Verify peer-to-peer access: token format userId:deviceSecret, validated via MeshService.
   * Uses X-Peer-Token header because http4s's Authorization parser rejects colons in bearer tokens. */
  private def verifyPeerAccess(req: Request[IO]): IO[Either[Response[IO], MeshService]] =
    meshService match
      case None =>
        IO.pure(Left(Response[IO](Status.NotFound).withEntity(Json.obj("error" -> "Mesh not enabled".asJson))))
      case Some(ms) =>
        val token = req.headers.get(ci"X-Peer-Token").map(_.head.value).getOrElse("")
        if token.isEmpty then
          IO.pure(Left(Response[IO](Status.Forbidden).withEntity(Json.obj("error" -> "Missing auth".asJson))))
        else
          ms.verifyPeerToken(token).map {
            case true => Right(ms)
            case false =>
              Left(Response[IO](Status.Forbidden).withEntity(Json.obj("error" -> "Invalid peer credentials".asJson)))
          }

  private def checkAuth(req: Request[IO]): Boolean =
    req.headers.get[Authorization].collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
      t
    } match
      case Some(t) => Auth.validateToken(t, token)
      case None =>
        req.params.get("token").exists(t => Auth.validateToken(t, token))
end RestApiRoutes
