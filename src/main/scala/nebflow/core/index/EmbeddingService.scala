package nebflow.core.index

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser
import io.circe.syntax.*
import nebflow.shared.{Config, NebflowLogger, PathUtil, SharedBackend}
import sttp.client4.*

import scala.concurrent.duration.*

/**
 * EmbeddingService —— OpenAI 兼容 embeddings 端点客户端（task-rag 批 #1227 /
 * §Batch 7）。
 *
 * 配置源 = nebflow.json 顶层 `embedding` 块（见 `nebflow.shared.EmbeddingConfig`，
 * 全字段可选、形照 `JevConfig`）：`enabled` + `baseUrl` + `model` + `keyRef`
 * （+ `timeoutMs`）。`keyRef` 是 `~/.nebflow/secrets/` 下的**名**（指针形态，
 * 配置永不存凭据值；解析形态照 `JevSettings.readSecretToken`）。
 *
 * ==Graceful degrade==
 * `resolveSettings` 对一切残缺形态（块缺/未启用/baseUrl·model 缺/keyRef 无 secret）
 * 返回 `None` —— 调用方据此走词法降级（任务检索面同一结果形状，零迁移）。
 *
 * ==Credential discipline==
 * token 只进 `Authorization: Bearer` 头：不打日志、不进诊断、不进任何返回
 * JSON。失败分类只携响应体摘录（≤200 字符），绝不携凭据。
 *
 * ==零新增重依赖==
 * HTTP 走仓内既有 sttp + `SharedBackend` 连接池（WebFetchTool /
 * DecisionHttpTransport 同款形态）；向量落盘用 circe + AtomicJson。零新库。
 */
object EmbeddingService:

  private val logger = NebflowLogger.forName("nebflow.index.embedding")

  /** Resolved, ready-to-call settings (`None` = the whole face is off). */
  final case class EmbeddingSettings(
    baseUrl: String,
    model: String,
    token: Option[String],
    timeoutMs: Long
  )

  /** Per-call text cap (chars) — a single oversized note must not blow the request. */
  val MaxTextChars: Int = 8000

  /** Per-call batch cap (texts) — the brute-force index is千级, batches stay small. */
  val MaxBatch: Int = 64

  val DefaultTimeoutMs: Long = 20000L

  // ── 配置热读（embedding 节）────────────────────────────────────────────

  /**
   * Resolve the embedding face from the config block. Hot read (per call),
   * matching the `JevSettings.fromConfig` / `PluginsConfig.enabled` precedent.
   *
   * `None` = "the face is off" — the ZERO-MIGRATION path; the caller falls
   * back to lexical search. Every malformed shape degrades to `None`, never
   * throws (a broken config must not wedge search).
   */
  def resolveSettings(configPath: Option[String] = None): Option[EmbeddingSettings] =
    try
      val cfg = Config.loadServiceConfig(configPath).embedding
      cfg.flatMap { c =>
        if !c.isEnabled then None
        else
          val baseUrl = c.baseUrl.map(_.trim).filter(_.nonEmpty)
          val model = c.model.map(_.trim).filter(_.nonEmpty)
          (baseUrl, model) match
            case (None, _) =>
              logger.warnSync("embedding.baseUrl missing or empty — embedding face off (lexical fallback)")
              None
            case (_, None) =>
              logger.warnSync("embedding.model missing or empty — embedding face off (lexical fallback)")
              None
            case (Some(url), Some(m)) =>
              Some(
                EmbeddingSettings(
                  baseUrl = url,
                  model = m,
                  token = readSecretToken(c.keyRef),
                  timeoutMs = c.timeoutMs.filter(_ > 0).getOrElse(DefaultTimeoutMs)
                )
              )
          end match
      }
    catch
      case e: Exception =>
        logger.warnSync(s"embedding config unreadable (${e.getClass.getSimpleName}) — embedding face off")
        None

  /**
   * Read the named secret's value, if it exists. Failures are non-fatal and
   * credential-free: a missing/unreadable/empty file yields `None`, and the
   * log line names the secret NAME only — never the content.
   */
  private[index] def readSecretToken(keyRef: Option[String]): Option[String] =
    keyRef.filter(_.trim.nonEmpty).flatMap { name =>
      val path = PathUtil.dataRoot / "secrets" / name.trim
      try
        if !os.exists(path) then
          logger.warnSync(s"embedding.keyRef '$name' has no secret file under secrets/ — requests will fail auth")
          None
        else
          val v = os.read(path).trim
          if v.isEmpty then
            logger.warnSync(s"embedding.keyRef '$name' resolves to an empty secret — requests will fail auth")
            None
          else Some(v)
      catch
        case e: Exception =>
          logger.warnSync(s"embedding.keyRef '$name' is unreadable (${e.getClass.getSimpleName}) — embedding face off")
          None
    }

  // ── 请求/响应（纯函数，spec 可驱动）────────────────────────────────────

  /** `POST {baseUrl}/embeddings` — join with exactly one slash. */
  private[index] def endpointOf(baseUrl: String): String =
    baseUrl.replaceAll("/+$", "") + "/embeddings"

  /** OpenAI-compatible embeddings request body: `{"model":…, "input":[…]}`. */
  private[index] def buildRequestJson(model: String, texts: List[String]): Json =
    Json.obj(
      "model" -> model.asJson,
      "input" -> texts.asJson
    )

  /**
   * Parse an OpenAI-compatible embeddings response: `{"data":[{"index":0,
   * "embedding":[…]}, …]}`. Vectors are returned ordered by `index` (a
   * server may permute `data`); any malformed shape yields `None`.
   */
  private[index] def parseResponse(body: String): Option[List[List[Float]]] =
    for
      json <- parser.parse(body).toOption
      cursor = json.hcursor
      dataArray <- cursor.downField("data").as[List[Json]].toOption
      pairs <- dataArray.traverse { item =>
        for
          idx <- item.hcursor.downField("index").as[Int].toOption
          vec <- item.hcursor.downField("embedding").as[List[Float]].toOption
        yield (idx, vec)
      }
      sorted = pairs.sortBy(_._1).map(_._2)
      _ <- Option.unless(sorted.isEmpty)(())
    yield sorted

  // ── 调用 ──────────────────────────────────────────────────────────────

  /**
   * Embed a batch of texts. Any failure (config off, HTTP error, timeout,
   * malformed body, dimension mismatch within the batch) ⇒ `None` — the
   * caller falls back; nothing throws out of this face (JevAllocator's
   * fail-open posture, same reason: search must never wedge on this leg).
   *
   * Batch/text caps are applied here (truncate + slice), so callers may hand
   * over arbitrary lists.
   */
  def embed(settings: EmbeddingSettings, texts: List[String]): IO[Option[List[List[Float]]]] =
    val capped = texts.map(_.take(MaxTextChars)).take(MaxBatch)
    if capped.isEmpty then IO.pure(None)
    else
      IO.blocking {
        try
          val request = basicRequest
            .post(uri"${endpointOf(settings.baseUrl)}")
            .header("Content-Type", "application/json")
            .header("Authorization", settings.token.map(t => s"Bearer $t").getOrElse(""))
            .body(buildRequestJson(settings.model, capped).noSpaces)
            .readTimeout(settings.timeoutMs.millis)
            .response(asStringAlways)
          val resp = request.send(SharedBackend.instance)
          if resp.code.isSuccess then
            parseResponse(resp.body) match
              case Some(vectors) if vectors.size == capped.size => Some(vectors)
              case Some(vectors) =>
                logger.warnSync(
                  s"embedding response arity mismatch (${vectors.size} vectors for ${capped.size} inputs) — treated as failure"
                )
                None
              case None =>
                logger.warnSync("embedding response malformed — treated as failure (lexical fallback)")
                None
          else
            logger.warnSync(s"embedding HTTP ${resp.code.code} — treated as failure (lexical fallback)")
            None
        catch
          case e: Exception =>
            logger.warnSync(
              s"embedding call failed (${e.getClass.getSimpleName}) — treated as failure (lexical fallback)"
            )
            None
      }

    end if

  end embed

  /** The common call shape: resolve hot, then embed; off/failure ⇒ `None`. */
  def embedResolved(texts: List[String], configPath: Option[String] = None): IO[Option[List[List[Float]]]] =
    resolveSettings(configPath) match
      case None => IO.pure(None)
      case Some(st) => embed(st, texts)

end EmbeddingService
