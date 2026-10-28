package nebflow.llm.imagegen

import io.circe.syntax.*
import io.circe.{Json, JsonObject}
import nebflow.llm.imagegen.ImageGenTypes.*
import nebflow.llm.imagegen.ImageGenTypes.ImageGenError as E

/**
 * 生图适配器集（imagegen 双层批 2026-10-24）。
 *
 * 全部请求构造与响应解析函数均为纯函数——spec 以 canned JSON 直测，真连验证走
 * scripts/imagegen-smoke（cogview-3-flash 免费档）。
 *
 * 尺寸口径（各家不通用，归一化在此单点）：
 *  - 输入 `size` 一律 "WxH"（如 "1024x1024"）；缺省 1024x1024。
 *  - cogview：cogview-4 任意分辨率原样传；其余（flash/plus 系枚举值口径）
 *    吸附到最近枚举（1024x1024 / 768x1344 / 1344x768）。
 *  - openai-images：gpt-image-1 仅三档（1024x1024 / 1536x1024 / 1024x1536），
 *    按长宽比吸附；显式 "WxH" 原样传（Seedream/火山兼容模式接受显式分辨率）。
 *  - imagen：换算 aspectRatio（"1:1" / "4:3" / "3:4" / "16:9" / "9:16"）。
 */
object ImageGenAdapters:

  // ── 尺寸工具 ──────────────────────────────────────────────

  private val WxH = """^(\d{3,5})x(\d{3,5})$""".r

  final case class Dim(w: Int, h: Int)

  /** 解析 "WxH"；非法/缺失 ⇒ 缺省 1024x1024（可行动错误留给上层参数校验）。 */
  def parseSize(size: Option[String]): Dim =
    size.map(_.trim).collect { case WxH(w, h) => Dim(w.toInt, h.toInt) }.getOrElse(Dim(1024, 1024))

  /** 按长宽比吸附到候选枚举中最接近的一档（同差时取面积近者）。 */
  private def snapByAspect(d: Dim, candidates: List[Dim]): Dim =
    val target = d.w.toDouble / d.h
    candidates.minBy(c => math.abs(c.w.toDouble / c.h - target) -> math.abs(c.w * c.h - d.w * d.h))

  val CogViewEnumSizes: List[Dim] = List(Dim(1024, 1024), Dim(768, 1344), Dim(1344, 768))
  val GptImageSizes: List[Dim] = List(Dim(1024, 1024), Dim(1536, 1024), Dim(1024, 1536))

  def cogviewSize(model: String, d: Dim): String =
    if model.startsWith("cogview-4") then s"${d.w}x${d.h}"
    else
      val s = snapByAspect(d, CogViewEnumSizes); s"${s.w}x${s.h}"

  def openAiImagesSize(d: Dim): String =
    if GptImageSizes.contains(d) then s"${d.w}x${d.h}"
    else
      val s = snapByAspect(d, GptImageSizes); s"${s.w}x${s.h}"

  def imagenAspect(d: Dim): String =
    val r = d.w.toDouble / d.h
    if r > 1.7 then "16:9"
    else if r >= 1.2 then "4:3"
    else if r > 0.83 then "1:1"
    else if r >= 0.55 then "3:4"
    else "9:16"

  // ── OpenAI images 兼容协议（gpt-image-1 / Seedream / 一切兼容端点）─────────
  // POST {baseUrl}/images/generations · Bearer · data[0].url|b64_json

  object OpenAiImages extends ImageGenAdapter:
    val `type` = "openai-images"
    val defaultBaseUrl = "https://api.openai.com/v1"

    def authHeader(token: String): (String, String) = "Authorization" -> s"Bearer $token"

    def buildBody(model: String, req: ImageGenRequest): Json =
      val d = parseSize(req.size)
      Json
        .obj(
          "model" -> model.asJson,
          "prompt" -> req.prompt.asJson,
          "n" -> 1.asJson,
          "size" -> openAiImagesSize(d).asJson,
          // gpt-image-1 只收 b64_json；兼容端点两种都给时 url 省一次下载——
          // 声明 url 对只认 b64 的端点是安全忽略（实测 gpt-image-1 忽略多余键）。
          "response_format" -> "url".asJson
        )
        .deepDropNullValues

    def parseResult(json: Json): Either[ImageGenError, ImageRef] =
      val cursor = json.hcursor.downField("data").downArray
      lazy val b64 = cursor.downField("b64_json").as[String].toOption
      lazy val url = cursor.downField("url").as[String].toOption.filter(_.nonEmpty)
      b64
        .map(ImageRef.B64(_))
        .orElse(url.map(ImageRef.Url(_)))
        .toRight(E.BadResponse(`type`, "data[0] carries neither b64_json nor url"))
  end OpenAiImages

  // ── 智谱 CogView（OpenAI 兼容层 + 自家 size 枚举/鉴权）─────────────────────
  // POST {baseUrl}/images/generations（v4 起 Bearer 直用 API Key）· data[0].url|b64_json

  object CogView extends ImageGenAdapter:
    val `type` = "cogview"
    val defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4"

    def authHeader(token: String): (String, String) = "Authorization" -> s"Bearer $token"

    def buildBody(model: String, req: ImageGenRequest): Json =
      val d = parseSize(req.size)
      Json.obj(
        "model" -> model.asJson,
        "prompt" -> req.prompt.asJson,
        "size" -> cogviewSize(model, d).asJson,
        "response_format" -> "url".asJson
      )

    def parseResult(json: Json): Either[ImageGenError, ImageRef] =
      val cursor = json.hcursor.downField("data").downArray
      lazy val url = cursor.downField("url").as[String].toOption.filter(_.nonEmpty)
      lazy val b64 = cursor.downField("b64_json").as[String].toOption
      url
        .map(ImageRef.Url(_))
        .orElse(b64.map(ImageRef.B64(_)))
        .toRight(E.BadResponse(`type`, "data[0] carries neither url nor b64_json"))
  end CogView

  // ── Google Imagen（预留位——Gemini generateImages 协议，未验证）────────────
  // ⚠️ 调试状态：imagen-3.0-generate-002 已被 Google 标记 2026-06-30 停服；
  // 本适配器是「预留位」语义——字段口径按 imagen-3/generateImages 实现（可跑），
  // 但属实验面，默认配置不指向它；迁移目标（gemini-2.5-flash-image）后续批接。

  object Imagen extends ImageGenAdapter:
    val `type` = "imagen"
    val defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta"

    def authHeader(token: String): (String, String) = "x-goog-api-key" -> token

    def buildBody(model: String, req: ImageGenRequest): Json =
      val d = parseSize(req.size)
      Json.obj(
        "instances" -> Json.arr(Json.obj("prompt" -> req.prompt.asJson)),
        "parameters" -> Json.obj("sampleCount" -> 1.asJson, "aspectRatio" -> imagenAspect(d).asJson)
      )

    def parseResult(json: Json): Either[ImageGenError, ImageRef] =
      json.hcursor
        .downField("predictions")
        .downArray
        .downField("bytesBase64Encoded")
        .as[String]
        .toOption
        .map(ImageRef.B64(_))
        .toRight(E.BadResponse(`type`, "predictions[0].bytesBase64Encoded missing"))
  end Imagen

  val All: List[ImageGenAdapter] = List(CogView, OpenAiImages, Imagen)

  def byType(tpe: String): Option[ImageGenAdapter] = All.find(a => a.`type` == tpe)

  /** 请求路径（服务层用）：openai-images/cogview = {base}/images/generations；imagen = {base}/models/{model}:generateImages。 */
  def endpointFor(adapter: ImageGenAdapter, baseUrl: String, model: String): String =
    adapter match
      case Imagen => s"$baseUrl/models/$model:generateImages"
      case _ => s"$baseUrl/images/generations"
end ImageGenAdapters
