/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Json, parser}
import nebflow.agent.RootAgentIdentity
import nebflow.core.entity.EntityLoader
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 注册表面板域(F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的
 * /models 全族、/agents(全局面板)、/folders、/mcp、/memory case 逐字迁入,
 * 行为保持;经 RestApiRoutes.routes 级联挂载,各臂首段路径字面量与其余域
 * 互不重叠(/agents/:name 参数族在 PresenceRoutes,与本域仅字面量单段
 * GET /agents 判然不同形)。
 */
private[gateway] object RegistryRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
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
                Json.obj(
                  "key" -> "vision".asJson,
                  "label" -> "图片理解".asJson,
                  "description" -> "支持 image_url 图片输入".asJson
                )
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
            // B3 Phase 2: vision is tri-state — absent in the request body keeps
            // the existing annotation instead of collapsing to false.
            val visionOpt = body.hcursor.downField("vision").as[Option[Boolean]].toOption.flatten
            val capabilities = body.hcursor.downField("capabilities").as[List[String]].getOrElse(Nil)
            if providerId.nonEmpty && modelId.nonEmpty then
              val key = s"$providerId/$modelId"
              val current = nebflow.llm.ModelRegistry.loadForApi
              val updatedEntry = current.models.get(key) match
                case Some(existing) =>
                  existing.copy(
                    vision = visionOpt.orElse(existing.vision),
                    capabilities = capabilities
                  )
                case None =>
                  nebflow.llm.ModelRegistry.ModelEntry(
                    vision = visionOpt,
                    capabilities = capabilities
                  )
              val updatedModels = current.models + (key -> updatedEntry)
              nebflow.llm.ModelRegistry.save(updatedModels)
              // B3 restore semantics: an explicit vision=true annotation is user
              // intent and outranks runtime auto-demotion — clear the in-memory
              // override (and counters) too, otherwise effectiveVision stays
              // false until restart (stripImages blocks any image-bearing
              // success, so resetOnSuccess can never lift it).
              val clearRuntime =
                if visionOpt.contains(true) then
                  nebflow.llm.EmptyCompletionTracker.shared.clearOverride(providerId, modelId)
                else IO.unit
              clearRuntime *> Ok(Json.obj("status" -> "ok".asJson))
            else BadRequest(Json.obj("error" -> "providerId and modelId required".asJson))
            end if
          }
        }

      // Agents — global layer only（2026-09-05 08:40 作者裁定：面板数据源收敛）。
      // 旧三层聚合（global+team+flow）的 team/flow 两层是面板污染源——team/flow
      // 入口已随 sidebar flag 封存，域 agent 不应出现在全局面板。global 层以
      // agent.json 存在为准（EntityLoader.loadAgentFromDir 无 agent.json 即 None
      // 丢弃），天然只回 keeper 定义；.archived / 惰性残留目录不出现（面板收敛
      // spec 钉死）。layer 字段保留恒 "global"（agentManager.js 的 layer 过滤
      // 兼容；scope 字段仅旧 team/flow 条目携带，随两层删除自然消失）。
      case req @ GET -> Root / "agents" =>
        withAuth(req) {
          for
            globalAgents <- EntityLoader.listAgents()
            globalList = globalAgents.values.toList.map { a =>
              Json.obj(
                "name" -> a.name.asJson,
                "description" -> a.description.asJson,
                "displayName" -> a.name.asJson,
                "category" -> a.category.asJson,
                "layer" -> "global".asJson
              )
            }
            result <- Ok(Json.obj("agents" -> globalList.asJson))
          yield result
        }

      // Folders
      case req @ GET -> Root / "folders" =>
        withAuth(req) {
          val agentName = req.params.get("agent").getOrElse(RootAgentIdentity.Name)
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
            val agentName = metaOpt.flatMap(_.agentName).getOrElse(RootAgentIdentity.Name)
            val folderId = metaOpt.flatMap(_.folderId).getOrElse("")
            val content = scope match
              case "user" => nebflow.service.MemoryStore.loadUserMemory.getOrElse("")
              case "agent" => nebflow.service.MemoryStore.loadAgentMemory(agentName).getOrElse("")
              case _ => ""
            Ok(Json.obj("scope" -> scope.asJson, "content" -> content.asJson))
          }
        }

    }
  end routes

end RegistryRoutes
