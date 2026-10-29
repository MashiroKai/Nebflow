package nebflow.perception

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.NebflowLogger

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.*

/**
 * 引擎 → 统一 daemon 管理进程的客户端腿（统一 daemon 批 2026-10-29）。
 *
 * 端点经 [[PerceptionDaemonDock]] 的 dock 腿登记（`bind`：port + token）；
 * 未 docked（boot 窗 / daemon 停机窗）⇒ 请求腿返回 `None`/降级——调用方语义：
 *  - 面板代理（PerceptionRoutes）→ 503 daemon-unavailable（面板统一停机窗面）；
 *  - EmailSend 工具 → 既有「感知守护进程未启动」错误文案；
 *  - AskUser 开窗腿 → 静默跳过（WARN；验证码走 L1/白名单/spool 兜底路径）。
 *
 * 超时口径（零超时设计红线）：面板代理逐字转发**无人工超时**（daemon 侧操作
 * 自持既有界）；工具腿（开窗/hasAccount/createDraft/attachRequest——daemon
 * 内存级操作）5s 有界，传输失败即降级不阻塞 agent 轮。
 */
object PerceptionClient:

  private val logger = NebflowLogger.forName("nebflow.perception.client")

  final case class Endpoint(port: Int, token: String):
    def base: String = s"http://127.0.0.1:$port"

  private val endpoint: Ref[IO, Option[Endpoint]] = Ref.unsafe(None)

  /** dock 腿登记（DaemonService 拉起成功 + 握手 200 后）。 */
  def bind(ep: Endpoint): IO[Unit] = endpoint.set(Some(ep))

  /** 测试/引擎停机腿解绑。 */
  def unbind(): IO[Unit] = endpoint.set(None)

  def current: IO[Option[Endpoint]] = endpoint.get

  private val http: HttpClient = HttpClient.newBuilder
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(3))
    .build()

  /** 裸健康探针（dock 握手腿用——不依赖 endpoint 登记）。true = /health 200。 */
  def probeHealth(daemonPort: Int, token: String, timeoutSec: Long): IO[Boolean] =
    IO.blocking {
      val req = HttpRequest.newBuilder
        .uri(URI.create(s"http://127.0.0.1:$daemonPort/health"))
        .timeout(Duration.ofSeconds(timeoutSec))
        .header("X-Nf-Perception-Token", token)
        .GET()
        .build()
      http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200
    }.attempt.map(_.getOrElse(false))

  /** 裸调用：Some = daemon 应答（statusCode+body）；None = 未 docked/传输失败。 */
  def call(
    method: String,
    path: String,
    body: Option[String],
    timeoutSec: Option[Long]
  ): IO[Option[(Int, String)]] =
    endpoint.get.flatMap {
      case None => IO.pure(None)
      case Some(ep) =>
        IO.blocking {
          val b = HttpRequest.newBuilder
            .uri(URI.create(s"${ep.base}$path"))
            .header("X-Nf-Perception-Token", ep.token)
          body.foreach(bb => { b.header("Content-Type", "application/json"); () })
          timeoutSec.foreach(s => b.timeout(Duration.ofSeconds(s)))
          b.method(
            method,
            body.map(bb => HttpRequest.BodyPublishers.ofString(bb)).getOrElse(HttpRequest.BodyPublishers.noBody())
          )
          val resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
          (resp.statusCode(), resp.body())
        }.attempt.map {
          case Right(ok)                      => Some(ok)
          case Left(e: InterruptedException)  => None
          case Left(_)                        =>
            // 传输失败 = daemon 停机窗（watchdog 秒级拉回）——调用方降级。
            None
        }
    }

  // ── 工具腿（引擎 agent 工具面；5s 有界 + 降级）────────────────────────

  /** AskUser 发射点开窗（幂等 by requestId，daemon 侧收敛）。降级 = 静默 no-op。 */
  def openOtpWindow(requestId: String): IO[Unit] =
    call("POST", "/api/perception/otp/window/open",
      Some(Json.obj("requestId" -> requestId.asJson).noSpaces), timeoutSec = Some(5)).void

  /** EmailSend 工具腿：是否有已连接账户。降级 None ⇒ 调用方报未连接。 */
  def hasAccount: IO[Option[Boolean]] =
    call("POST", "/internal/perception/has-account", None, timeoutSec = Some(5))
      .map(_.map((code, body) =>
        code == 200 && io.circe.parser.parse(body).toOption
          .exists(_.hcursor.downField("hasAccount").as[Boolean].toOption.contains(true))))

  /** EmailSend 工具腿：落草稿。Some(Right(rec)) = 成功；Some(Left(err)) = daemon 业务拒绝；None = 未 docked。 */
  def createDraft(doc: nebflow.perception.DraftCodec.DraftDoc): IO[Option[Either[String, PerceptionModel.DraftRecord]]] =
    call("POST", "/internal/perception/create-draft", Some(docJson(doc).noSpaces), timeoutSec = Some(5)).map {
      case None => None
      case Some((200, body)) =>
        Some(io.circe.parser.decode[PerceptionModel.DraftRecord](body) match
          case Right(rec) => Right(rec)
          case Left(e)    => Left(s"daemon 草稿应答不可解析: ${e.getMessage.take(80)}")
        )
      case Some((_, body)) =>
        Some(Left(io.circe.parser.parse(body).toOption
          .flatMap(j => j.hcursor.downField("error").as[String].toOption)
          .getOrElse("daemon 拒绝（见 daemon 日志）")))
    }

  /** EmailSend 工具腿：草稿↔ask 卡绑定（快照/画布回执路由判据）。降级静默。 */
  def attachRequest(draftId: String, requestId: String): IO[Unit] =
    call("POST", "/internal/perception/attach-request",
      Some(Json.obj("draftId" -> draftId.asJson, "requestId" -> requestId.asJson).noSpaces),
      timeoutSec = Some(5)).void

  private def docJson(doc: DraftCodec.DraftDoc): Json =
    Json.obj(
      "to" -> doc.to.asJson,
      "cc" -> doc.cc.asJson,
      "bcc" -> doc.bcc.asJson,
      "subject" -> doc.subject.asJson,
      "bodyHtml" -> doc.bodyHtml.asJson,
      "attachments" -> doc.attachments.asJson,
      "replyToMessageId" -> doc.replyToMessageId.asJson,
      "quoteHtml" -> doc.quoteHtml.asJson
    )

end PerceptionClient
