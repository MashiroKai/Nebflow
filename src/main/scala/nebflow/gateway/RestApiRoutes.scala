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
    case GET -> Root / "api" / "health" =>
      Ok(Json.obj("status" -> "ok".asJson, "version" -> nebflow.Version.string.asJson))

    // Generic command endpoint — mirrors WS messages
    case req @ POST -> Root / "api" / "command" =>
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
    case req @ GET -> Root / "api" / "sessions" =>
      withAuth(req) {
        sessionStore.listSessions.flatMap { sessions =>
          sessionStore.getActiveId.flatMap { activeId =>
            Ok(Json.obj("sessions" -> sessions.asJson, "activeId" -> activeId.asJson))
          }
        }
      }

    // Session history
    case req @ GET -> Root / "api" / "sessions" / sessionId / "history" =>
      withAuth(req) {
        sessionStore.getUiMessages(sessionId, 0, 0).flatMap { case (messages, total) =>
          Ok(Json.obj("messages" -> messages.asJson, "total" -> total.asJson, "sessionId" -> sessionId.asJson))
        }
      }

    // Create session
    case req @ POST -> Root / "api" / "sessions" =>
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
    case req @ DELETE -> Root / "api" / "sessions" / sessionId =>
      withAuth(req) {
        sessionStore.deleteSession(sessionId) *> Ok(Json.obj("deleted" -> true.asJson))
      }

    // Config
    case req @ GET -> Root / "api" / "config" =>
      withAuth(req) {
        ConfigService.getConfig.flatMap { cfg =>
          ConfigService.isConfigured.flatMap { configured =>
            Ok(Json.obj("config" -> cfg.asJson, "configured" -> configured.asJson))
          }
        }
      }

    // Update config
    case req @ PATCH -> Root / "api" / "config" =>
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
    case req @ GET -> Root / "api" / "models" =>
      withAuth(req) {
        sharedResources.providerRegistry.getAllModels().flatMap { models =>
          Ok(Json.obj("models" -> models.map { case (ref, label) =>
            Json.obj("ref" -> ref.asJson, "label" -> label.asJson)
          }.asJson))
        }
      }

    // Agents
    case req @ GET -> Root / "api" / "agents" =>
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
    case req @ GET -> Root / "api" / "folders" =>
      withAuth(req) {
        val agentName = req.params.get("agent").getOrElse("Nebula")
        sessionStore.listFolders(agentName).flatMap { folders =>
          Ok(Json.obj("folders" -> folders.asJson))
        }
      }

    // MCP servers
    case req @ GET -> Root / "api" / "mcp" =>
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
    case req @ GET -> Root / "api" / "memory" =>
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

    // Mesh status — identity, pairing state, peers
    case req @ GET -> Root / "api" / "mesh" / "status" =>
      withMesh(req) { ms =>
        ms.identity.flatMap { id =>
          ms.isPaired.flatMap { paired =>
            ms.peers.flatMap { peersList =>
              Ok(
                Json.obj(
                  "paired" -> paired.asJson,
                  "device" -> Json.obj(
                    "id" -> id.deviceId.asJson,
                    "name" -> id.deviceName.asJson,
                    "platform" -> id.platform.asJson,
                    "groupId" -> id.groupId
                      .map(g => s"${g.take(4)}${"*".repeat(Math.max(g.length - 4, 0))}")
                      .asJson
                  ),
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

    // Pair with a shared token
    case req @ POST -> Root / "api" / "mesh" / "pair" =>
      withMesh(req) { ms =>
        req.as[Json].flatMap { body =>
          val tokenResult = body.hcursor.downField("token").as[String]
          tokenResult match
            case Right(token) =>
              ms.pair(token)
                .flatMap { _ =>
                  Ok(Json.obj("ok" -> true.asJson))
                }
                .handleErrorWith { e =>
                  BadRequest(Json.obj("error" -> e.getMessage.asJson))
                }
            case Left(_) =>
              BadRequest(Json.obj("error" -> "Missing token".asJson))
        }
      }

    // Handshake — called by a discovered peer to establish trust
    case req @ POST -> Root / "api" / "mesh" / "handshake" =>
      meshService match
        case None => NotFound(Json.obj("error" -> "Mesh not enabled".asJson))
        case Some(ms) =>
          // Extract Bearer token from Authorization header
          val callerToken = req.headers
            .get[Authorization]
            .collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
              t
            }
            .getOrElse("")
          if callerToken.isEmpty then Forbidden(Json.obj("error" -> "Missing token".asJson))
          else
            // Extract caller IP from the request remote address
            val callerIp = req.remoteAddr.fold("unknown")(_.toString)
            req.as[Json].flatMap { body =>
              val hc = body.hcursor
              val deviceId = hc.downField("deviceId").as[String].getOrElse("")
              val deviceName = hc.downField("deviceName").as[String].getOrElse("Unknown")
              val platform = hc.downField("platform").as[String].getOrElse("")
              val port = hc.downField("port").as[Int].getOrElse(8080)
              if deviceId.isEmpty then BadRequest(Json.obj("error" -> "Missing deviceId".asJson))
              else
                ms.handleHandshake(callerToken, deviceId, deviceName, platform, callerIp, port)
                  .flatMap { _ =>
                    // Respond with own identity so the initiator also adds us
                    ms.identity
                      .map { id =>
                        Json.obj(
                          "deviceId" -> id.deviceId.asJson,
                          "deviceName" -> id.deviceName.asJson,
                          "platform" -> id.platform.asJson
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

    // Leave group — clear token, stop discovery
    case req @ POST -> Root / "api" / "mesh" / "leave" =>
      withMesh(req) { ms =>
        ms.leaveGroup *> Ok(Json.obj("ok" -> true.asJson))
      }

    // Trigger sync with all peers
    case req @ POST -> Root / "api" / "mesh" / "sync" =>
      withMesh(req) { ms =>
        ms.syncAll *> Ok(Json.obj("synced" -> true.asJson))
      }

    // Fingerprints — returns local file fingerprints for peer sync
    // Note: uses Bearer token auth (peer trust), not Nebflow auth cookie
    case req @ GET -> Root / "api" / "mesh" / "fingerprints" =>
      meshService match
        case None => NotFound(Json.obj("error" -> "Mesh not enabled".asJson))
        case Some(ms) =>
          val callerToken = req.headers
            .get[Authorization]
            .collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
              t
            }
            .getOrElse("")
          ms.groupId.flatMap {
            case None => Forbidden(Json.obj("error" -> "Not paired".asJson))
            case Some(localToken) =>
              if callerToken != localToken then Forbidden(Json.obj("error" -> "Token mismatch".asJson))
              else
                ms.computeLocalFingerprints.flatMap { fps =>
                  Ok(io.circe.Json.fromFields(fps.map { case (path, fp) =>
                    path -> fp.asJson
                  }))
                }
          }

    // File download — returns file content for peer sync
    case req @ GET -> Root / "api" / "mesh" / "file" =>
      meshService match
        case None => NotFound(Json.obj("error" -> "Mesh not enabled".asJson))
        case Some(ms) =>
          val callerToken = req.headers
            .get[Authorization]
            .collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
              t
            }
            .getOrElse("")
          ms.groupId.flatMap {
            case None => Forbidden(Json.obj("error" -> "Not paired".asJson))
            case Some(localToken) =>
              if callerToken != localToken then Forbidden(Json.obj("error" -> "Token mismatch".asJson))
              else
                val relPath = req.params.getOrElse("path", "")
                if relPath.isEmpty then BadRequest(Json.obj("error" -> "Missing path".asJson))
                else
                  ms.readLocalFile(relPath).flatMap {
                    case None => NotFound(Json.obj("error" -> s"File not found: $relPath".asJson))
                    case Some((bytes, fp)) =>
                      val encoded = java.util.Base64.getEncoder.encodeToString(bytes)
                      Ok(
                        Json.obj(
                          "path" -> relPath.asJson,
                          "content" -> encoded.asJson,
                          "mtime" -> fp.mtime.asJson,
                          "hash" -> fp.hash.asJson
                        )
                      )
                  }
                end if
          }

    // File upload — receives file content from a peer
    case req @ PUT -> Root / "api" / "mesh" / "file" =>
      meshService match
        case None => NotFound(Json.obj("error" -> "Mesh not enabled".asJson))
        case Some(ms) =>
          val callerToken = req.headers
            .get[Authorization]
            .collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
              t
            }
            .getOrElse("")
          ms.groupId.flatMap {
            case None => Forbidden(Json.obj("error" -> "Not paired".asJson))
            case Some(localToken) =>
              if callerToken != localToken then Forbidden(Json.obj("error" -> "Token mismatch".asJson))
              else
                req.as[Json].flatMap { body =>
                  val hc = body.hcursor
                  val relPath = hc.downField("path").as[String].getOrElse("")
                  val contentB64 = hc.downField("content").as[String].getOrElse("")
                  if relPath.isEmpty || contentB64.isEmpty
                  then BadRequest(Json.obj("error" -> "Missing path or content".asJson))
                  else
                    val bytes = java.util.Base64.getDecoder.decode(contentB64)
                    ms.writeLocalFile(relPath, bytes) *> Ok(Json.obj("ok" -> true.asJson))
                }
          }

    // Remote tool execution — called by peer Nebflow devices
    case req @ POST -> Root / "api" / "mesh" / "remote-exec" =>
      withMesh(req) { ms =>
        ms.groupId.flatMap { localGroupId =>
          // Verify caller's token matches ours (peer trust)
          val callerToken = req.headers
            .get[Authorization]
            .collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
              t
            }
            .getOrElse("")
          if localGroupId.isEmpty || callerToken != localGroupId.getOrElse("")
          then Forbidden(Json.obj("error" -> "Token mismatch".asJson))
          else
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
          end if
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

  private def checkAuth(req: Request[IO]): Boolean =
    req.headers.get[Authorization].collectFirst { case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
      t
    } match
      case Some(t) => Auth.validateToken(t, token)
      case None =>
        req.params.get("token").exists(t => Auth.validateToken(t, token))
end RestApiRoutes
