package nebflow.core.index

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser
import munit.CatsEffectSuite
import nebflow.service.ConfigService
import nebflow.shared.PathUtil

import scala.concurrent.duration.*

/**
 * task-rag 批（#1227 / §Batch 7）验收：EmbeddingService 配置解析与线格式。
 *
 * 🔴 凭据纪律：本 spec **零真实外网请求、零密钥落盘**——只驱动配置解析、
 * 请求体构造与响应解析的**纯函数面**（fixture 形态），及 keyRef→secrets 指针
 * 解析（临时 dataRoot + 临时 secret 文件，值 = 无意义占位串）。
 */
class EmbeddingServiceSpec extends CatsEffectSuite:

  override val munitIOTimeout = 60.seconds

  private def withTempDataRoot[A](f: os.Path => IO[A]): IO[A] =
    val original = PathUtil.dataRoot
    val tmp = os.temp.dir(prefix = "nb-embed-")
    PathUtil.setDataRoot(tmp)
    f(tmp).guarantee(IO(PathUtil.setDataRoot(original)) *> IO(os.remove.all(tmp)))

  private def writeConfig(tmp: os.Path, json: String): Unit =
    os.write.over(PathUtil.configJsonReadPath(tmp), json, createFolders = true)

  // ── 配置解析（graceful degrade）──────────────────────────────────────

  test("unconfigured / disabled / partial blocks resolve to None (zero-migration off path)") {
    withTempDataRoot { tmp =>
      IO {
        assertEquals(EmbeddingService.resolveSettings(), None, "无配置 = 面 off")
        writeConfig(tmp, """{"embedding": {"enabled": false, "baseUrl": "https://x/v1", "model": "m"}}""")
        assertEquals(EmbeddingService.resolveSettings(), None, "enabled=false = 面 off")
        writeConfig(tmp, """{"embedding": {"enabled": true}}""")
        assertEquals(EmbeddingService.resolveSettings(), None, "缺 baseUrl = 面 off（半节解码为缺省而非抛）")
        writeConfig(tmp, """{"embedding": {"enabled": true, "baseUrl": "https://x/v1"}}""")
        assertEquals(EmbeddingService.resolveSettings(), None, "缺 model = 面 off")
        writeConfig(tmp, """{"embedding": {"noSuchBlock": 1}}""")
        assertEquals(EmbeddingService.resolveSettings(), None, "未知键容忍（块级宽容解码）")
      }
    }.unsafeRunSync()
  }

  test("enabled + baseUrl + model resolves; keyRef resolves via secrets/ NAME pointer") {
    withTempDataRoot { tmp =>
      IO {
        writeConfig(
          tmp,
          """{"embedding": {"enabled": true, "baseUrl": "https://api.example.com/v1/", "model": "emb-1", "keyRef": "emb-key", "timeoutMs": 5000}}"""
        )
        // keyRef 无 secret 文件 ⇒ 仍建 settings（token=None，调用时显式失败——
        // JevSettings 同口径），词法降级不因 keyRef 缺席提前触发。
        val noSecret = EmbeddingService.resolveSettings()
        assert(noSecret.isDefined)
        assertEquals(noSecret.get.baseUrl, "https://api.example.com/v1/")
        assertEquals(noSecret.get.model, "emb-1")
        assertEquals(noSecret.get.token, None)
        assertEquals(noSecret.get.timeoutMs, 5000L)

        // secret 落盘（占位值，非真实密钥）⇒ token = 指针指向的值
        os.makeDir.all(tmp / "secrets")
        os.write.over(tmp / "secrets" / "emb-key", "fixture-placeholder-value")
        val withSecret = EmbeddingService.resolveSettings()
        assertEquals(withSecret.flatMap(_.token), Some("fixture-placeholder-value"))
      }
    }.unsafeRunSync()
  }

  // ── 线格式（纯函数）───────────────────────────────────────────────────

  test("endpointOf joins exactly one slash onto the baseUrl") {
    assertEquals(EmbeddingService.endpointOf("https://x/v1"), "https://x/v1/embeddings")
    assertEquals(EmbeddingService.endpointOf("https://x/v1/"), "https://x/v1/embeddings")
    assertEquals(EmbeddingService.endpointOf("https://x/v1///"), "https://x/v1/embeddings")
  }

  test("buildRequestJson: OpenAI-compatible {model, input:[…]}") {
    val body = EmbeddingService.buildRequestJson("emb-1", List("a", "b"))
    val cursor = parser.parse(body.noSpaces).fold(throw _, identity).hcursor
    assertEquals(cursor.downField("model").as[String], Right("emb-1"))
    assertEquals(cursor.downField("input").as[List[String]], Right(List("a", "b")))
  }

  test("parseResponse: vectors ordered by data[].index; malformed → None") {
    val ok =
      """{"data":[{"index":1,"embedding":[0.4,0.5]},{"index":0,"embedding":[0.1,0.2]}]}"""
    assertEquals(EmbeddingService.parseResponse(ok), Some(List(List(0.1f, 0.2f), List(0.4f, 0.5f))))
    assertEquals(EmbeddingService.parseResponse("""{"data":[]}"""), None, "空 data = None")
    assertEquals(EmbeddingService.parseResponse("""{"data":[{"embedding":[1.0]}]}"""), None, "缺 index = None")
    assertEquals(EmbeddingService.parseResponse("""{"data":[{"index":0}]}"""), None, "缺 embedding = None")
    assertEquals(EmbeddingService.parseResponse("not json"), None)
  }

  // ── 设置区写入（ConfigService 定向写，形态照 jev 节）─────────────────

  test("setEmbeddingSection: whitelist enforced, unknown/empty refused, valid keys land") {
    withTempDataRoot { tmp =>
      IO {
        // 越界键拒绝
        val bad = ConfigService.setEmbeddingSection(Map("apiKey" -> io.circe.Json.fromString("x"))).unsafeRunSync()
        assert(bad.isLeft, "值型凭据键不在册（机械表达：本面不收凭据值）")
        // 空体拒绝
        assert(ConfigService.setEmbeddingSection(Map.empty).unsafeRunSync().isLeft)
        // 合法键落盘
        ConfigService
          .setEmbeddingSection(
            Map(
              "enabled" -> io.circe.Json.fromBoolean(true),
              "baseUrl" -> io.circe.Json.fromString("https://api.example.com/v1"),
              "model" -> io.circe.Json.fromString("emb-1"),
              "keyRef" -> io.circe.Json.fromString("emb-key")
            )
          )
          .unsafeRunSync()
          .fold(e => fail(s"write failed: $e"), _ => ())
        val cursor = parser.parse(os.read(PathUtil.configJsonReadPath(tmp))).fold(throw _, identity).hcursor
        assertEquals(cursor.downField("embedding").downField("model").as[String], Right("emb-1"))
        assertEquals(cursor.downField("embedding").downField("keyRef").as[String], Right("emb-key"))
        // 定向覆盖：只动给出的键
        ConfigService
          .setEmbeddingSection(Map("model" -> io.circe.Json.fromString("emb-2")))
          .unsafeRunSync()
          .fold(e => fail(s"write failed: $e"), _ => ())
        val cursor2 = parser.parse(os.read(PathUtil.configJsonReadPath(tmp))).fold(throw _, identity).hcursor
        assertEquals(cursor2.downField("embedding").downField("model").as[String], Right("emb-2"))
        assertEquals(
          cursor2.downField("embedding").downField("baseUrl").as[String],
          Right("https://api.example.com/v1"),
          "未提及键原样保留"
        )
      }
    }.unsafeRunSync()
  }

end EmbeddingServiceSpec
