/* imagegen 双层批（2026-10-24）——腿②（插件 MCP 模式）的本机调用面。 */
package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.ImageGenSeal
import nebflow.llm.imagegen.{ImageGenJobs, ImageGenService}
import nebflow.llm.imagegen.ImageGenTypes.ImageGenRequest
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 生图 REST 面（imagegen 双层批 2026-10-24，调试状态）——腿②（delegate 等下层
 * 走插件 MCP 模式）与腿①共用 [[ImageGenService]] provider 层的本机入口：
 * 插件包的 stdio MCP server 把工具调用翻译成对本路由的**阻塞** HTTP 调用
 * （路由侧阻塞等生成完、PNG 落盘、一次性返回路径——delegate 无前端语义，
 * 「前端不显示、可阻塞」裁定）。
 *
 * 🔴 鉴权面 = **环回地址门，零凭据**（与 /health/conn 同级零凭据先例同向）：
 * MCP server 是引擎拉起的本机子进程，不经 withAuth（无会话凭据）。比例原则：
 * 能调本路由的本机进程同样能直读 nebflow.json/secrets——环回门与凭据面暴露
 * 等价，不造第二把假尺。非环回一律 403，与鉴权失败不可区分。
 *
 * 本路由整体只在编译期 seal 开（`STAND_IMAGEGEN=1`）时挂进级联；release 构建
 * 级联位是 `HttpRoutes.empty`——「release build 零痕迹」的 REST 锚。
 */
private[gateway] object ImageGenRoutes:

  private val logger = nebflow.shared.NebflowLogger.forName("nebflow.imagegen.routes")

  private def isLoopback(req: Request[IO]): Boolean =
    req.remoteAddr.exists(ip => ip.toInetAddress.isLoopbackAddress || ip.toInetAddress.isAnyLocalAddress)

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    HttpRoutes.of[IO] {
      // 阻塞生图一击（腿②主面）：{prompt, size?, provider?, quality?} →
      // {path, bytes, provider}；面关/错参给可行动 4xx，provider 失败 502。
      case req @ POST -> Root / "imagegen" =>
        if !isLoopback(req) then
          Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else
          req.as[Json].attempt.flatMap {
            case Left(_) => BadRequest(Json.obj("error" -> "invalid_body".asJson, "reason" -> "JSON object body required".asJson))
            case Right(body) =>
              val prompt = body.hcursor.downField("prompt").as[String].toOption.map(_.trim).getOrElse("")
              if prompt.isEmpty then
                BadRequest(Json.obj("error" -> "invalid_prompt".asJson, "reason" -> "non-empty 'prompt' string required".asJson))
              else
                val reqModel = ImageGenRequest(
                  prompt = prompt,
                  size = body.hcursor.downField("size").as[String].toOption,
                  provider = body.hcursor.downField("provider").as[String].toOption,
                  quality = body.hcursor.downField("quality").as[String].toOption
                )
                ImageGenService.generate(reqModel).flatMap {
                  case Left(err) =>
                    logger.warn(s"imagegen route generate failed: ${err.message}").flatMap { _ =>
                      BadGateway(Json.obj("error" -> "generate_failed".asJson, "reason" -> err.message.asJson))
                    }
                  case Right(bytes) =>
                    val name = s"mcp-${System.currentTimeMillis()}-${bytes.length}"
                    ImageGenService.saveImage(name, bytes).attempt.flatMap {
                      case Right(path) =>
                        ImageGenService.resolve(reqModel) match
                          case Right(resolved) =>
                            Ok(
                              Json.obj(
                                "path" -> path.asJson,
                                "bytes" -> bytes.length.asJson,
                                "provider" -> resolved.name.asJson,
                                "debug" -> ImageGenSeal.DebugLabel.asJson
                              )
                            )
                          case Left(_) =>
                            Ok(Json.obj("path" -> path.asJson, "bytes" -> bytes.length.asJson))
                      case Left(e) =>
                        InternalServerError(
                          Json.obj(
                            "error" -> "save_failed".asJson,
                            "reason" -> Option(e.getMessage).getOrElse(e.getClass.getSimpleName).asJson
                          )
                        )
                    }
                }
          }

      // job 状态读面（两腿通用调试面）：腿①非阻塞 job 的查询腿。
      case req @ GET -> Root / "imagegen" / "job" / jobId =>
        if !isLoopback(req) then Forbidden(Json.obj("error" -> "Unauthorized".asJson))
        else
          ImageGenJobs.get(jobId) match
            case Some(job) =>
              Ok(
                Json.obj(
                  "jobId" -> job.jobId.asJson,
                  "status" -> job.status.wire.asJson,
                  "prompt" -> job.prompt.asJson,
                  "path" -> job.path.asJson,
                  "error" -> job.error.asJson,
                  "createdAtMs" -> job.createdAtMs.asJson,
                  "updatedAtMs" -> job.updatedAtMs.asJson
                )
              )
            case None => NotFound(Json.obj("error" -> "unknown_job".asJson, "jobId" -> jobId.asJson))
    }
end ImageGenRoutes
