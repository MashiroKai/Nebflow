package nebflow.gateway

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import nebflow.core.{NebflowLogger, PathUtil}
import nebflow.shared.Defaults

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64

object SttService:
  private val logger = NebflowLogger.forName("nebflow.stt")

  /** STT 配置文件路径。def（非 val）——PathUtil.dataRoot 可被测试 setDataRoot
    * 重定向，val 会在类加载时冻结（#295）。 */
  def configPath: os.Path = PathUtil.dataRoot / "stt-config.json"

  def create(): IO[Option[SttService]] =
    IO.blocking {
      if !os.exists(configPath) then None
      else
        parse(os.read(configPath)).toOption.flatMap { json =>
          val hc = json.hcursor
          val apiKeyOpt = hc.downField("apiKey").as[String].toOption.filter(_.nonEmpty)
          apiKeyOpt.flatMap { apiKey =>
            val model = hc.downField("model").as[String].getOrElse(Defaults.SttDefaultModel)
            val endpoint = hc
              .downField("endpoint")
              .as[String]
              .getOrElse(Defaults.SttDefaultEndpoint)
            Some(new SttService(apiKey, model, endpoint))
          }
        }
    }.flatMap {
      case Some(svc) => logger.info("STT service initialized").as(Some(svc))
      case None => IO.pure(None)
    }
  end create

  /** #295 A2：字段级三态。None=字段省略（保留旧值）；Some(None)=显式空串
    * （清除该字段）；Some(Some(v))=设值（已 trim、非空）。 */
  type SttField = Option[Option[String]]

  final case class SttConfigPatch(endpoint: SttField, apiKey: SttField, model: SttField):
    /** 全部字段显式清除——等价于清空整个配置。 */
    def clearsEverything: Boolean =
      List(endpoint, apiKey, model).forall(_.contains(None))

  /**
   * setSttConfig WS 命令的校验/解析入口（#295 → A2 部分更新语义，纯函数）。
   *
   * 语义（2026-08-20 A2 对齐，前端契约：空 key 输入框省略字段）：
   * - 字段省略 → 保留旧值（merge 侧处理，本次不触碰）
   * - 字段显式空串/纯空白 → 清除该字段
   * - 字段设值 → 校验后写入（endpoint 须 http(s)://；model/apiKey 非空即可）
   *
   * 旧 validateConfig 把「省略」与「显式空」折叠成同一路径且整文件替换，
   * 导致二次保存（前端 apiKey 恒空、省略该字段）时旧 key 被覆盖丢失。
   */
  def parsePatch(payload: Json): Either[String, SttConfigPatch] =
    def field(name: String): Either[String, SttField] =
      payload.hcursor.downField(name).as[Option[String]] match
        case Left(_) => Left(s"STT field '$name' must be a string if present")
        case Right(None) => Right(None) // field absent from JSON — keep old value
        case Right(Some(raw)) =>
          val v = raw.trim
          if v.isEmpty then Right(Some(None)) // explicit empty — clear this field
          else Right(Some(Some(v)))
    for
      endpoint <- field("endpoint")
      apiKey <- field("apiKey")
      model <- field("model")
      _ <- endpoint.flatten match
        case Some(e) if !e.startsWith("http://") && !e.startsWith("https://") =>
          Left(s"STT endpoint must start with http:// or https:// (got: ${e.take(60)})")
        case _ => Right(())
    yield SttConfigPatch(endpoint, apiKey, model)

  /**
   * patch 叠加旧配置（#295 A2 纯函数）。省略=保留旧值；显式空=删字段；
   * 设值=覆写。返回 None=合并后无任何字段（未配置态——调用方删文件，
   * 服务回退浏览器 Web Speech）。部分字段存在但缺 apiKey 时 create()
   * 返回 None（无代理服务），字段保留在盘上待 key 回填。
   */
  def mergeConfig(old: Option[Json], patch: SttConfigPatch): Option[Json] =
    def oldField(name: String): Option[String] =
      old.flatMap(_.hcursor.downField(name).as[String].toOption).filter(_.nonEmpty)
    def apply(name: String, f: SttField): Option[(String, Json)] =
      f match
        case None         => oldField(name).map(v => name -> v.asJson) // omitted — keep
        case Some(None)   => None // explicit clear
        case Some(Some(v)) => Some(name -> v.asJson)
    val fields = List(
      apply("endpoint", patch.endpoint),
      apply("apiKey", patch.apiKey),
      apply("model", patch.model)
    ).flatten
    if fields.isEmpty then None
    else Some(Json.fromJsonObject(JsonObject.fromIterable(fields)))

  /**
   * serverConfig 广播的 stt 节（#295）：{sttConfigured, endpoint?, model?}——
   * **apiKey 永不外露**（key 不落地前端铁律）。未配置时仅 sttConfigured:false。
   */
  def serverConfigNode(svc: Option[SttService]): Json =
    svc match
      case Some(s) =>
        Json.obj(
          "sttConfigured" -> true.asJson,
          "endpoint" -> s.endpoint.asJson,
          "model" -> s.model.asJson
        )
      case None =>
        Json.obj("sttConfigured" -> false.asJson)
end SttService

/**
 * STT 语音识别服务。使用 OpenAI 兼容的音频转录 API。
 *
 * 配置文件: ~/.nebflow/stt-config.json
 * {
 *   "apiKey": "your-key",
 *   "model": "glm-asr-2512",
 *   "endpoint": "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"
 * }
 *
 * 支持任何兼容 OpenAI 音频转录格式的 API 提供商。
 */
class SttService private[gateway] (
  apiKey: String,
  /** 转录模型名（serverConfig 广播可见；apiKey 永不外露）。 */
  val model: String,
  /** 转录端点（serverConfig 广播可见；apiKey 永不外露）。 */
  val endpoint: String
):
  private val logger = NebflowLogger.forName("nebflow.stt")

  private val client = HttpClient
    .newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .build()

  /**
   * 转录 WAV 音频数据，返回识别文本。
   *
   * @param wavBytes WAV 格式的音频字节数组
   * @param language 语言代码 (zh, en 等)，可选
   * @return 识别到的文本，失败时返回 None
   */
  def transcribe(wavBytes: Array[Byte], language: Option[String]): IO[Either[String, String]] =
    if wavBytes.isEmpty then IO.pure(Left("Empty audio data"))
    else
      // Build multipart/form-data body
      val boundary = "----nebflow-stt-" + System.currentTimeMillis().toHexString
      val header = s"--$boundary\r\n".getBytes(StandardCharsets.UTF_8)
      val footer = s"\r\n--$boundary--\r\n".getBytes(StandardCharsets.UTF_8)

      // file part
      val filePartHeader =
        s"Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
          "Content-Type: audio/wav\r\n\r\n"
      val filePartHeaderBytes = filePartHeader.getBytes(StandardCharsets.UTF_8)

      // model part
      val modelPartHeader =
        s"--$boundary\r\n" +
          "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
          s"$model\r\n"
      val modelPartBytes = modelPartHeader.getBytes(StandardCharsets.UTF_8)

      // language part (optional)
      val langBytes = language match
        case Some(lang) =>
          (s"--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"language\"\r\n\r\n" +
            s"$lang\r\n").getBytes(StandardCharsets.UTF_8)
        case None => Array.emptyByteArray

      // Combine all parts
      val bodyBytes: Array[Byte] =
        header ++ modelPartBytes ++ langBytes ++ filePartHeaderBytes ++ wavBytes ++ footer

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(endpoint))
        .header("Authorization", s"Bearer $apiKey")
        .header("Content-Type", s"multipart/form-data; boundary=$boundary")
        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
        .timeout(java.time.Duration.ofSeconds(30))
        .build()

      IO.blocking {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if response.statusCode() != 200 then
          logger.warn(s"STT API error ${response.statusCode()}: ${response.body().take(300)}")
          Left(s"STT API error: ${response.statusCode()}")
        else
          parse(response.body()).toOption match
            case Some(json) =>
              json.hcursor.downField("text").as[String] match
                case Right(text) => Right(text.trim)
                case Left(_) => Left("STT API returned unexpected format")
            case None =>
              // Some APIs return plain text
              val text = response.body().trim
              if text.nonEmpty then Right(text) else Left("Empty response from STT API")
      }.handleErrorWith { e =>
        logger.warn(s"STT transcription failed: ${e.getMessage}").as(Left(e.getMessage))
      }
  end transcribe
end SttService
