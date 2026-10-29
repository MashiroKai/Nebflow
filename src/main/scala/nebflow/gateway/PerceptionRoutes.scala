package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

/**
 * 感知域 REST 面（统一 daemon 批 2026-10-29 起 = **代理腿**）。
 *
 * 面板 API 的权威实现自本批起整体迁入统一 daemon 管理进程
 * （`nebflow.perception.PerceptionPanelRoutes` + `PerceptionDaemonRoutes`），
 * 引擎侧本文件只做逐字转发：method/path/query/body 原样 → daemon
 * （`127.0.0.1:<daemonPort>`，X-Nf-Perception-Token 门），status/body 原样
 * 回relay——**单源实现，零第二形状可漂移**。会话门（withAuth ⇒ 403
 * X-Nf-Reason: credential-required）与既有路径面逐字保持（前端零触碰）。
 *
 * 例外：`GET /perception/mail/preset` 保持引擎本地（纯函数、无 daemon 依赖
 * ——daemon 停机窗内两问配置向导仍可用）。
 *
 * daemon 停机窗 ⇒ 503 `daemon-unavailable`（[[PerceptionClient.call]] None
 * 路径；autoStart/restartOnExit 家族语义秒级拉回）。
 */
private[gateway] object PerceptionRoutes:

  /** 代理覆盖的面板路径（相对 /perception 前缀；与 daemon 权威表一一对应）。
    * 草稿族（GET/PUT /mail/drafts/:id、POST /mail/drafts/:id/abandon）带 id
    * 段，在 [[isProxied]] 内单独判。 */
  private val ProxiedSubpaths: List[(Method, List[String])] =
    List(
      (GET, List("entries")),
      (POST, List("mail", "connect")),
      (POST, List("mail", "toggle")),
      (POST, List("mail", "remove")),
      (POST, List("otp", "window", "open")),
      (GET, List("otp", "consume")),
      (POST, List("mail", "send"))
    )

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {

      // 域 → 服务商自动识别（保持引擎本地：纯函数，daemon 停机时向导仍可用）
      case req @ GET -> Root / "perception" / "mail" / "preset" =>
        withAuth(req) {
          val address = req.params.getOrElse("address", "")
          val preset = nebflow.perception.MailboxPresets.forAddress(address)
          Ok(Json.obj(
            "recognized" -> preset.isDefined.asJson,
            "provider" -> preset.map(_.provider).getOrElse("").asJson,
            "authCodeHint" -> preset.exists(_.authCodeHint).asJson
          ))
        }

      // ── 代理腿：/perception 下全部其余端点逐字转发统一 daemon ──────────
      case req if isProxied(req) =>
        withAuth(req) { proxy(req) }
    }

  /** 代理判据（面板路径族；preset 除外——见类注）。 */
  private[gateway] def isProxied(req: Request[IO]): Boolean = {
    // renderString 分段（版本无感的取段法；感知域段均为 URL 安全字符面）。
    val segs = req.pathInfo.renderString.split("/").filter(_.nonEmpty).toList
    // pathInfo 已剥离挂载前缀（/api）——首段应为 perception。
    segs match
      case "perception" :: rest =>
        rest match
          case List("mail", "preset") => false
          case List("mail", "drafts", _*) =>
            req.method == Method.GET || req.method == Method.PUT ||
              (req.method == Method.POST && (segs.last == "abandon"))
          case _ => ProxiedSubpaths.exists((m, p) => m == req.method && p == rest)
      case _ => false
  }

  /** 逐字转发：method/path/query/body 原样；status/body 原样回 relay。
    * daemon 面板面挂 `/api/perception/…`  全路径（本侧 pathInfo 已剥离 /api
    * 挂载前缀——转发时补回）。 */
  private def proxy(req: Request[IO]): IO[Response[IO]] =
    val query = req.uri.query.renderString
    val path = s"/api${req.pathInfo.renderString}" + (if query.isEmpty then "" else s"?$query")
    req.bodyText.compile.string.flatMap { bodyText =>
      val body = if bodyText.isEmpty then None else Some(bodyText)
      nebflow.perception.PerceptionClient.call(req.method.renderString, path, body, timeoutSec = None).flatMap {
        case Some((status, respBody)) =>
          val ct = io.circe.parser.parse(respBody) match
            case Right(_) => headers.`Content-Type`(org.http4s.MediaType.application.json)
            case Left(_)  => headers.`Content-Type`(org.http4s.MediaType.text.plain)
          Response[IO](org.http4s.Status.fromInt(status).getOrElse(Status.InternalServerError))
            .withEntity(respBody)
            .putHeaders(ct)
            .pure[IO]
        case None => daemonUnavailable
      }
    }

  private def daemonUnavailable: IO[Response[IO]] =
    ServiceUnavailable(Json.obj(
      "ok" -> false.asJson,
      "error" -> "感知守护进程未运行 · 稍候自动恢复（daemon-unavailable）".asJson
    ))

end PerceptionRoutes
