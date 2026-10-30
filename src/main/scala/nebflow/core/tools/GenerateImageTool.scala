package nebflow.core.tools

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import io.circe.JsonObject
import nebflow.llm.imagegen.{ImageGenJobs, ImageGenService}
import nebflow.llm.imagegen.ImageGenTypes.ImageGenRequest
import nebflow.shared.NebflowLogger

import java.nio.file.Files
import java.util.UUID

/**
 * 内置生图工具（imagegen 双层批 2026-10-24；功能开放批 2026-10-30 正式产品面；
 * 交互修正批 2026-10-30 作者令：非阻塞 job → **阻塞语义**——照 Codex 玩法定案）。
 *
 * == 阻塞生命周期（交互修正批定案） ==
 *  1. `call` **挂起至生成完成**——回合不并发推进后续内容（目标形态①）；调用
 *     期间前端消息流原位显示标准 pending 工具卡（spinner + 「生图」标签，
 *     纯文本风格动效纪律）；
 *  2. 成功 = 工具结果**原位直出单张图**（目标形态②③）：结果载荷 = Pop 升级批的
 *     `___POP_JSON___` 契约（票据腿，单 image item）——前端既有 isPopPayload
 *     渲染腿原样接管（单图平铺、点击 Canvas 打开、扇卡那套保留），**不自动调
 *     Pop 工具**、模型也不需再 Pop 这张图；
 *  3. 整个生命周期图只出现一次——修双渲染病灶：旧非阻塞形态两条渲染腿（WS
 *     imagegenUpdate 占位卡替换 + wakeAgent 唤醒模型后续回合再 Pop 一张缩略
 *     卡），本批整腿拆除（WS 帧推送与 ExternalEvent 唤醒均退役，imagegen.js
 *     占位模块同步删除）；
 *  4. 失败 = `Left(ToolError)` 原位翻错误工具卡，模型回合内直接看到原因。
 *
 * ImageGenJobs 台账保留（register/complete/fail）——REST `/api/imagegen` 路由面
 * 与 sidebar 状态投影仍消费它；仅 WS 推送与 agent 唤醒两回填腿退役。
 *
 * 授权面：Nebula 专属——`AgentCore.RootOrchestrationTools` 单点授能 +
 * `RootExclusiveTools` 防声明逃逸 + 本件 `ctx.isRootAgent` 身份闸双保险
 * （PopTool POP_NEBULA_ONLY 同款纪律）。
 */
object GenerateImageTool extends Tool:
  private val logger = NebflowLogger.forName("nebflow.imagegen.tool")

  val name = "generate_image"

  /** Pop 升级批的载荷哨兵（与 [[PopTool.Sentinel]] 同字面——前端 isPopPayload 单点解析）。 */
  private val PopSentinel = "___POP_JSON___"

  val description = """Generate an image from a text prompt (BLOCKING).

- BLOCKING: the call does not return until generation completes (seconds to a minute depending on the provider). The turn waits for the image — do not batch other work against this call.
- On success the image is rendered DIRECTLY in the chat as a media card (click to open in Canvas) and saved under the instance data root (imagegen/ dir, format sniffed from real bytes). The result carries the saved absolute path.
- The image is shown to the user automatically as part of this call — do NOT call Pop on it afterwards.
- On failure the call returns an error carrying the provider's message.
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
    if result.startsWith(PopSentinel) then "image shown in chat"
    else result.split("\n").headOption.getOrElse(result)

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] = IO.defer {
    if !ctx.isRootAgent then
      IO.pure(Left(ToolError("generate_image is Nebula-only (IMAGEGEN_ROOT_ONLY) — delegates use the imagegen plugin instead.")))
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
            // 阻塞语义：job 体在当前调用内 awaited——回合零并发推进（旧形态此处为
            // run.start 立即返回句柄，双渲染病灶之源，整腿退役）。
            runJobBlocking(jobId, req, ctx, prompt)
  }

  /**
   * 阻塞 job 体：生成 → 落盘 → 台账 → Pop 载荷结果。失败统一收束为
   * `Left(ToolError)`（台账同步翻 Failed）——模型在回合内直接看到原因。
   */
  private def runJobBlocking(jobId: String, req: ImageGenRequest, ctx: ToolContext, prompt: String): IO[Either[ToolError, String]] =
    IO.delay(ImageGenJobs.register(jobId, ctx.sessionId, prompt)) *>
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
                    logger.info(s"imagegen job $jobId completed -> $path", "sessionId" -> ctx.sessionId.getOrElse("")) *>
                    IO.pure(Right(popResult(jobId, path)))
                case Left(e) =>
                  val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                  failJob(jobId, ctx, s"save failed: $msg")
              }
          case Right(Left(err)) => failJob(jobId, ctx, err.message)
          case Left(e)          => failJob(jobId, ctx, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }

  private def failJob(jobId: String, ctx: ToolContext, message: String): IO[Either[ToolError, String]] =
    IO.delay(ImageGenJobs.fail(jobId, message.take(300))) *>
      logger.warn(s"imagegen job $jobId failed: ${message.take(200)}", "sessionId" -> ctx.sessionId.getOrElse("")) *>
      IO.pure(Left(ToolError(s"generate_image failed: ${message.take(300)} (IMAGEGEN_JOB)")))

  /**
   * 成功载荷：`___POP_JSON___` + Pop 升级批契约（票据腿单 image item）。
   * 与 PopTool.payloadJson 同形（fileRefs 计数打头、warnings/notes/items）——
   * 前端 isPopPayload/buildPopArtifactRow 单点消费，不另造第二张卡的面。
   * 内联腿不走：字节刚落盘，nf-file 票据链按路径取数（Pop 票据腿同款）。
   */
  private def popResult(jobId: String, path: String): String =
    val name = path.split('/').lastOption.getOrElse(jobId)
    val ext = name.split('.').lastOption.getOrElse("png")
    val size = try Files.size(java.nio.file.Paths.get(path)) catch case _: Exception => -1L
    val payload = Json.obj(
      "fileRefs" -> Json.obj(
        "inlined" -> 0.asJson,
        "referenced" -> 1.asJson,
        "failed" -> 0.asJson,
        "omitted" -> 0.asJson
      ),
      "warnings" -> Json.arr(),
      "notes" -> Json.arr(),
      "items" -> Json.arr(
        Json.obj(
          "kind" -> "image".asJson,
          "name" -> name.asJson,
          "path" -> path.asJson,
          "size" -> size.asJson,
          "ext" -> ext.asJson
        )
      )
    )
    s"$PopSentinel${payload.noSpaces}"
  end popResult

  /**
   * 模型面投影（PopTool.modelFacingResult 同款纪律）：sentinel/载荷是浏览器食料，
   * 模型只需「已展示 + 落盘路径」。永不携带 sentinel。
   */
  override def modelFacingResult(result: String): String =
    if !result.startsWith(PopSentinel) then result
    else
      io.circe.parser
        .parse(result.substring(PopSentinel.length))
        .toOption
        .flatMap { p =>
          val cursor = p.hcursor
          for
            path <- cursor.downField("items").downArray.downField("path").as[String].toOption
            name <- cursor.downField("items").downArray.downField("name").as[String].toOption
          yield Json
            .obj(
              "generate_image" -> "shown".asJson,
              "path" -> path.asJson,
              "name" -> name.asJson,
              "note" -> "The image is rendered to the user in chat as a media card (do NOT Pop it again); `path` is the saved file.".asJson
            )
            .noSpaces
        }
        .getOrElse(result)
  end modelFacingResult

end GenerateImageTool
