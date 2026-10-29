package nebflow.perception

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger
import PerceptionModel.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*

/**
 * 统一 daemon 管理进程 → 引擎进程的出域腿（统一 daemon 批 2026-10-29）。
 *
 * daemon 进程对引擎的全部依赖收敛在这三条窄端口上（引擎侧由
 * [[nebflow.gateway.PerceptionIngestRoutes]] 承接，X-Nf-Perception-Token 门）：
 *
 *  - `deliver`：投递信封出域——引擎侧 resolveRoots + ImmediateInput 注入根
 *    会话，返回 `delivered`；false ⇒ 调用方（PerceptionCore.injectEnvelope）
 *    落本地 spool，「引擎停机不丢信」语义原样。
 *  - `broadcast`：WS 事件帧回注引擎 wsHub（推送面；3 次退避重试后放弃——
 *    命中状态 daemon 自持，hasHit 补渲染腿兜底，丢帧只影响推送时延）。
 *  - `llmJudge`：L1 判定 transport——邮件面（IncomingMail，现成 codec）出域，
 *    引擎侧组装 `MailImportanceJudge.promptFor` + 现有 LLM 栈代答；**裁决解析
 *    （parseVerdict/三值/降级/30s 界）留在 daemon**（判定逻辑在 daemon 进程，
 *    引擎只是 transport；传输界 40s > 判定 30s 界，judge 超时先到语义不变）。
 *
 * 传输 = JDK HttpClient（NeblinkClient 先例同款，零新依赖）；调用全走
 * `127.0.0.1:<enginePort>`；token 每请求附 `X-Nf-Perception-Token` 头。
 */
final class PerceptionEngineLeg(enginePort: Int, token: String):

  private val logger = NebflowLogger.forName("nebflow.perception.engine-leg")

  private val http: HttpClient = HttpClient.newBuilder
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(3))
    .build()

  /** 统一 POST 腿。None = 非 200 / 传输失败 / body 不可解析（调用方自行降级）。 */
  private def call(path: String, body: Json, timeoutSec: Long): IO[Option[Json]] =
    IO.blocking {
      val req = HttpRequest.newBuilder
        .uri(URI.create(s"http://127.0.0.1:$enginePort$path"))
        .timeout(Duration.ofSeconds(timeoutSec))
        .header("Content-Type", "application/json")
        .header("X-Nf-Perception-Token", token)
        .POST(HttpRequest.BodyPublishers.ofString(body.noSpaces))
        .build()
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() == 200 then io.circe.parser.parse(resp.body()).toOption
      else None
    }.attempt.map(_.toOption.flatten)

  /** 投递出域（见类注）。永不抛——任何传输失败都按「引擎不可达」返回 false。 */
  def deliver(env: DeliveryEnvelope, dedupKey: String): IO[Boolean] =
    call("/api/perception/internal/deliver",
      Json.obj(
        "text" -> env.text.asJson,
        "priority" -> env.priority.asJson,
        "dedupKey" -> dedupKey.asJson
      ),
      timeoutSec = 20
    ).map(ok => ok.exists(_.hcursor.downField("delivered").as[Boolean].toOption.contains(true)))

  /** WS 事件帧回注（3 次退避后放弃，只 WARN——见类注；递归腿 IO.defer 包住）。 */
  def broadcast(json: Json): IO[Unit] =
    def attempt(n: Int): IO[Unit] =
      IO.defer {
        call("/api/perception/internal/broadcast", json, timeoutSec = 5).flatMap {
          case Some(_) => IO.unit
          case None =>
            if n >= 3 then
              logger.warn(
                s"perception broadcast dropped after 3 attempts: " +
                  s"${json.hcursor.downField("type").as[String].getOrElse("?")}"
              ).void
            else IO.sleep((1L << (n - 1)).seconds) *> attempt(n + 1)
        }
      }
    attempt(1)

  /** L1 判定 transport（见类注）。None = 引擎不可达（judge 侧按 Unavailable 搁置 spool）。 */
  def llmJudge(mail: IncomingMail): IO[Option[String]] =
    call("/api/perception/internal/llm", Json.obj("mail" -> mail.asJson), timeoutSec = 40)
      .map(_.flatMap(j => j.hcursor.downField("reply").as[String].toOption))

end PerceptionEngineLeg

object PerceptionEngineLeg:

  /**
   * 判定 seam 组装：裁决逻辑（parseVerdict 三值 + 30s 有界 + 降级 Unavailable）
   * 全在 daemon；kill-switch `nebflow.perception.llm.enabled` 在 daemon 进程读
   * （引擎 spawn 时以 `-D` 原值透传，两进程一致）。None = L1 整层关。
   */
  def judgeViaEngine(leg: PerceptionEngineLeg): MailIncomingJudge =
    if PerceptionService.LlmJudgeEnabled then
      Some { (mail: IncomingMail) =>
        leg.llmJudge(mail)
          .timeout(MailImportanceJudge.TimeoutSec.seconds)
          .attempt
          .map {
            case Right(Some(reply)) => MailImportanceJudge.parseVerdict(reply)
            case _                  => L1Verdict.Unavailable
          }
      }
    else None

end PerceptionEngineLeg
