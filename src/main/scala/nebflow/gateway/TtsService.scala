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

  /** 加载 TTS 配置和声音样本，创建 TtsService 实例。配置或样本不存在时返回 None（静默降级）。 */
  def create(): IO[Option[TtsService]] =
    IO.blocking {
      if !os.exists(configPath) then None
      else
        parse(os.read(configPath)).toOption.flatMap { json =>
          val hc = json.hcursor
          val apiKeyOpt = hc.downField("apiKey").as[String].toOption.filter(_.nonEmpty)
          apiKeyOpt.flatMap { apiKey =>
            val model = hc
              .downField("model")
              .as[String]
              .getOrElse("mimo-v2.5-tts-voiceclone")
            val endpoint = hc
              .downField("endpoint")
              .as[String]
              .getOrElse("https://api.xiaomimimo.com/v1/chat/completions")
            val voiceSamplePath = hc
              .downField("voiceSample")
              .as[String]
              .getOrElse("voice-sample.wav")
            val sampleFile = PathUtil.resolvePath(voiceSamplePath, PathUtil.dataRoot)
            if !os.exists(sampleFile) then None
            else
              // 读取声音样本并 base64 编码（只读一次，后续请求复用缓存的 sampleB64）
              val sampleB64 = Base64.getEncoder.encodeToString(os.read.bytes(sampleFile))
              Some(new TtsService(apiKey, model, endpoint, sampleB64))
          }
        }
    }.flatMap {
      case Some(svc) => logger.info("TTS service initialized").as(Some(svc))
      case None => IO.pure(None)
    }
  end create
end TtsService

/**
 * TTS 语音合成服务。
 *
 * @param apiKey       MiMo API 密钥
 * @param model        TTS 模型名称
 * @param endpoint     API 端点 URL
 * @param voiceSampleB64 声音样本的 base64 编码（创建时缓存，不重复读文件）
 */
class TtsService private[gateway] (
  apiKey: String,
  model: String,
  endpoint: String,
  voiceSampleB64: String
):
  private val logger = NebflowLogger.forName("nebflow.tts")

  // JDK 内置 HttpClient，不引入新依赖
  private val client = HttpClient
    .newBuilder()
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .build()

  /** 合成语音，返回 WAV 字节数组。失败时返回 None（静默降级）。 */
  def synthesize(text: String): IO[Option[Array[Byte]]] =
    if text.isEmpty then IO.pure(None)
    else
      val requestBody = Json
        .obj(
          "model" -> Json.fromString(model),
          "messages" -> Json.arr(
            Json.obj("role" -> Json.fromString("user"), "content" -> Json.fromString("")),
            Json.obj("role" -> Json.fromString("assistant"), "content" -> Json.fromString(text))
          ),
          "audio" -> Json.obj(
            "format" -> Json.fromString("wav"),
            "voice" -> Json.fromString(s"data:audio/wav;base64,$voiceSampleB64")
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
          // 从响应中提取 choices[0].message.audio.data（base64），解码为 WAV 字节
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
