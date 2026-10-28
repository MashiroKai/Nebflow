package nebflow.core.tools

import cats.effect.IO
import cats.syntax.all.*
import io.circe.JsonObject
import io.circe.syntax.*
import nebflow.core.ImageGenSeal
import nebflow.llm.imagegen.{ImageGenJobs, ImageGenService}
import nebflow.llm.imagegen.ImageGenTypes.ImageGenRequest
import nebflow.shared.NebflowLogger

import java.util.UUID

/**
 * 腿① 内置生图工具（imagegen 双层批 2026-10-24，作者裁定：「主 agent 使用内置工具
 * …生图的时候有个占位动画…非阻塞」）。**调试状态（experimental）**——发版经
 * [[ImageGenSeal]] 编译期剔除。
 *
 * 非阻塞 job 生命周期（与 Bash run_in_background 同款三段通知 BashTool 先例）：
 *  1. `call` 立即返回 job 句柄 `{jobId, status:"running"}`——**回合零阻塞**；
 *  2. 生成在后台 fiber 跑（腿②与本服共用 [[ImageGenService]] provider 层）；
 *  3. 完成三段回填：PNG 落盘 `<dataRoot>/imagegen/<jobId>.png`（+ 台账翻转）→
 *     WS `imagegenUpdate` 帧推前端（占位卡片原位替换为真实图片，nf-file 票据链）→
 *     [[BgNotifyDelivery]] 唤醒 agent（ExternalEvent 进新回合，模型自取结果路径）。
 *
 * 授权面：Nebula 专属——`AgentCore.RootOrchestrationTools` 单点授能 +
 * `RootExclusiveTools` 防声明逃逸 + 本件 `ctx.isRootAgent` 身份闸双保险
 * （PopTool POP_NEBULA_ONLY 同款纪律）。
 */
object GenerateImageTool extends Tool:
  private val logger = NebflowLogger.forName("nebflow.imagegen.tool")

  val name = "generate_image"

  val description = s"""Generate an image from a text prompt (${ImageGenSeal.DebugLabel} / experimental — non-blocking job).

- NON-BLOCKING: the call returns immediately with a job handle {jobId, status:"running"}; generation runs in the background and the frontend placeholder card is replaced in place when the image is ready (pushed over WS).
- The completed image is saved under the instance data root (imagegen/ dir, format sniffed from real bytes) and displayed via the nf-file ticket chain.
- When the job completes, the session receives an external event carrying the file path — you will see it in a subsequent turn.
- Configure providers in nebflow.json top-level `imagegen` section (providers: cogview / openai-images / imagen; credentials via keyRef pointers into the secrets dir, never inline).
- Use this tool when the user asks for an image, picture, diagram mock, or visual concept."""

  val inputSchema = JsonObject.fromIterable(
    List(
      "type" -> "object".asJson,
      "properties" -> io.circe.Json.obj(
        "prompt" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Text description of the image to generate. Be specific about subject, style, and composition.".asJson
        ),
        "size" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional, \"WxH\" form (default 1024x1024). Snapps to the nearest size the selected model supports.".asJson
        ),
        "provider" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional provider name from the imagegen.providers config map. Defaults to imagegen.defaultProvider.".asJson
        ),
        "quality" -> io.circe.Json.obj(
          "type" -> "string".asJson,
          "description" -> "Optional quality hint for OpenAI-images providers (low|medium|high); ignored by other adapters.".asJson
        )
      ),
      "required" -> io.circe.Json.arr("prompt".asJson)
    )
  )

  def summarize(input: JsonObject): String =
    val prompt = input("prompt").flatMap(_.asString).getOrElse("")
    s"""generate_image("${prompt.take(60)}${if prompt.length > 60 then "…" else ""}")"""

  def summarizeResult(input: JsonObject, result: String): String =
    if result.contains("\"running\"") then "image job started"
    else result.split("\n").headOption.getOrElse(result)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.defer {
    if !ctx.isRootAgent then
      IO.pure(
        Left(
          ToolError("generate_image is Nebula-only (IMAGEGEN_ROOT_ONLY) — delegates use the imagegen plugin instead.")
        )
      )
    else
      val prompt = input("prompt").flatMap(_.asString).map(_.trim).getOrElse("")
      if prompt.isEmpty then IO.pure(Left(ToolError("prompt must be a non-empty string (IMAGEGEN_PROMPT)")))
      else
        val req = ImageGenRequest(
          prompt = prompt,
          size = input("size").flatMap(_.asString),
          provider = input("provider").flatMap(_.asString),
          quality = input("quality").flatMap(_.asString)
        )
        // 面级 fail-fast：配置缺失/凭据缺席在提交前给可行动文案（不白起 job）。
        ImageGenService.resolve(req) match
          case Left(err) => IO.pure(Left(ToolError(s"${err.message} (IMAGEGEN_CONFIG)")))
          case Right(_) =>
            val jobId = s"img-${UUID.randomUUID().toString.take(8)}"
            val run = runJob(jobId, req, ctx, prompt)
            run.start.as(
              Right(
                io.circe.Json
                  .obj(
                    "jobId" -> jobId.asJson,
                    "status" -> "running".asJson,
                    "note" -> s"${ImageGenSeal.DebugLabel}: 生成中，完成经 WS 推送回填占位卡片；结果落盘 imagegen/ 目录".asJson
                  )
                  .noSpaces
              )
            )
        end match
      end if
  }

  /** 后台 fiber 体：生成 → 落盘 → 台账 → WS → agent 唤醒（三段互相独立，各带自愈）。 */
  private def runJob(jobId: String, req: ImageGenRequest, ctx: ToolContext, prompt: String): IO[Unit] =
    val registered = IO.delay(ImageGenJobs.register(jobId, ctx.sessionId, prompt))
    val pipeline =
      ImageGenService
        .generate(req)
        .attempt
        .flatMap {
          case Right(Right(bytes)) =>
            ImageGenService
              .saveImage(jobId, bytes)
              .attempt
              .flatMap {
                case Right(path) =>
                  IO.delay(ImageGenJobs.complete(jobId, path)) *>
                    pushFrontend(ctx, jobId, "completed", Some(path), None) *>
                    wakeAgent(ctx, jobId, "completed", path) *>
                    logger.info(s"imagegen job $jobId completed -> $path", "sessionId" -> ctx.sessionId.getOrElse(""))
                case Left(e) =>
                  val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                  failJob(jobId, ctx, s"save failed: $msg")
              }
          case Right(Left(err)) => failJob(jobId, ctx, err.message)
          case Left(e) => failJob(jobId, ctx, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }
    registered *> pipeline.handleErrorWith(e => logger.warn(s"imagegen job $jobId pipeline error: ${e.getMessage}"))

  end runJob

  private def failJob(jobId: String, ctx: ToolContext, message: String): IO[Unit] =
    IO.delay(ImageGenJobs.fail(jobId, message.take(300))) *>
      pushFrontend(ctx, jobId, "failed", None, Some(message.take(300))) *>
      wakeAgent(ctx, jobId, "failed", s"job $jobId failed: ${message.take(200)}") *>
      logger.warn(s"imagegen job $jobId failed: ${message.take(200)}", "sessionId" -> ctx.sessionId.getOrElse(""))

  /** WS 帧：前端占位卡片按 jobId 原位替换（BashTool backgroundTaskUpdate 同款内联帧先例）。 */
  private def pushFrontend(
    ctx: ToolContext,
    jobId: String,
    status: String,
    path: Option[String],
    error: Option[String]
  ): IO[Unit] =
    ctx.wsSend.fold(IO.unit) { send =>
      send(
        io.circe.Json
          .obj(
            "type" -> "imagegenUpdate".asJson,
            "sessionId" -> ctx.sessionId.asJson,
            "rootSessionId" -> ctx.rootSessionId.orElse(ctx.sessionId).asJson,
            "jobId" -> jobId.asJson,
            "status" -> status.asJson
          )
          .deepMerge(path.fold(io.circe.Json.obj())(p => io.circe.Json.obj("path" -> p.asJson)))
          .deepMerge(error.fold(io.circe.Json.obj())(e => io.circe.Json.obj("error" -> e.asJson)))
      ).handleErrorWith(e => logger.warn(s"WS send failed for imagegen job $jobId: ${e.getMessage}"))
    }

  /** agent 唤醒：完成/失败以 ExternalEvent 进目标会话新回合（BgNotifyDelivery 交付面）。 */
  private def wakeAgent(ctx: ToolContext, jobId: String, eventType: String, payload: String): IO[Unit] =
    ctx.agentActorRef.fold(IO.unit) { ref =>
      BgNotifyDelivery.deliverAgentEvent(
        targetRef = Some(ref),
        targetSessionId = ctx.sessionId,
        system = ctx.actorSystem,
        registry = ctx.sharedResources.map(_.agentRegistry),
        projectName = ctx.projectName,
        flowNodeId = ctx.flowNodeId,
        jobId = jobId,
        source = "imagegen",
        eventType = eventType,
        payload = payload,
        metadata = JsonObject("jobId" -> jobId.asJson)
      )
    }
end GenerateImageTool
