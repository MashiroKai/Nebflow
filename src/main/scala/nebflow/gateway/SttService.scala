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

  /**
   * setSttConfig WS 命令的校验/归一入口（#295，纯函数）。
   *
   * 语义（用户 2026-08-18 拍板）：三字段均可选；全空/空对象 = 清空配置
   * （Right(None)——删配置文件，回退免费浏览器 Web Speech）。endpoint 提供
   * 时须 http(s):// 开头；model 非空字符串；apiKey 原样存（空串视为未提供）。
   * 只写提供的字段——读取侧（create）对缺省字段回 Defaults。
   */
  def validateConfig(payload: Json): Either[String, Option[Json]] =
    def field(name: String): Option[String] =
      payload.hcursor.downField(name).as[String].toOption.map(_.trim).filter(_.nonEmpty)
    val endpoint = field("endpoint")
    val apiKey = field("apiKey")
    val model = field("model")
    endpoint match
      case Some(e) if !e.startsWith("http://") && !e.startsWith("https://") =>
        Left(s"STT endpoint must start with http:// or https:// (got: ${e.take(60)})")
      case _ =>
        val fields = List(
          endpoint.map(e => "endpoint" -> e.asJson),
          apiKey.map(k => "apiKey" -> k.asJson),
          model.map(m => "model" -> m.asJson)
        ).flatten
        if fields.isEmpty then Right(None)
        else Right(Some(Json.fromJsonObject(JsonObject.fromIterable(fields))))

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
