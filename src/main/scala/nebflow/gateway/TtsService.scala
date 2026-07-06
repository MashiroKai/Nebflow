package nebflow.gateway

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import nebflow.core.{NebflowLogger, PathUtil}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.Base64

object TtsService:
  private val logger = NebflowLogger.forName("nebflow.tts")
  private val configPath = PathUtil.dataRoot / "tts-config.json"

  def create(): IO[Option[TtsService]] =
    IO.blocking {
      if !os.exists(configPath) then None
      else
        parse(os.read(configPath)).toOption.flatMap { json =>
          val hc = json.hcursor
          val apiKeyOpt = hc.downField("apiKey").as[String].toOption.filter(_.nonEmpty)
          apiKeyOpt.flatMap { apiKey =>
            val model = hc.downField("model").as[String].getOrElse("mimo-v2.5-tts")
            val endpoint = hc
              .downField("endpoint")
              .as[String]
              .getOrElse("https://api.xiaomimimo.com/v1/chat/completions")
            // 读取中英文音色配置
            val voices = hc.downField("voices").as[Map[String, String]].getOrElse(Map.empty)
            val zhVoice = voices.getOrElse("zh", "冰糖")
            val enVoice = voices.getOrElse("en", "Mia")
            Some(new TtsService(apiKey, model, endpoint, zhVoice, enVoice))
          }
        }
    }.flatMap {
      case Some(svc) => logger.info("TTS service initialized").as(Some(svc))
      case None => IO.pure(None)
    }
  end create
end TtsService

/**
 * TTS 语音合成服务。根据文本语言自动选择音色。
 *
 * @param apiKey   MiMo API 密钥
 * @param model    TTS 模型名称
 * @param endpoint API 端点 URL
 * @param zhVoice  中文音色名称
 * @param enVoice  英文音色名称
 */
class TtsService private[gateway] (
  apiKey: String,
  model: String,
  endpoint: String,
  zhVoice: String,
  enVoice: String
):
  private val logger = NebflowLogger.forName("nebflow.tts")

  private val client = HttpClient
    .newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .build()

  /** 检测文本语言：包含 CJK 字符则为中文，否则英文。 */
  private def detectLanguage(text: String): String =
    if text.exists(c => c >= '\u4e00' && c <= '\u9fff') then "zh" else "en"

  /** 合成语音，返回 WAV 字节数组。失败时返回 None（静默降级）。 */
  def synthesize(text: String): IO[Option[Array[Byte]]] =
    if text.isEmpty then IO.pure(None)
    else
      val lang = detectLanguage(text)
      val voice = if lang == "zh" then zhVoice else enVoice
      val requestBody = Json
        .obj(
          "model" -> Json.fromString(model),
          "messages" -> Json.arr(
            Json.obj("role" -> Json.fromString("user"), "content" -> Json.fromString("")),
            Json.obj("role" -> Json.fromString("assistant"), "content" -> Json.fromString(text))
          ),
          "audio" -> Json.obj(
            "format" -> Json.fromString("wav"),
            "voice" -> Json.fromString(voice)
          )
        )
        .noSpaces

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(endpoint))
        .header("api-key", apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .timeout(java.time.Duration.ofSeconds(30))
        .build()

      IO.blocking {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if response.statusCode() != 200 then
          logger.warn(s"TTS API error ${response.statusCode()}: ${response.body().take(200)}")
          None
        else
          parse(response.body()).toOption.flatMap { json =>
            json.hcursor
              .downField("choices")
              .downArray
              .downField("message")
              .downField("audio")
              .downField("data")
              .as[String]
              .toOption
              .map(data => Base64.getDecoder.decode(data))
          }
        end if
      }.handleErrorWith { e =>
        logger.warn(s"TTS synthesis failed: ${e.getMessage}").as(None)
      }
  end synthesize
end TtsService
