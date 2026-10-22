/* 从 RestApiRoutes 迁出(行为保持重构,2026-09-24)。 */
package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import nebflow.core.hotrestart.HealthPayload
import nebflow.shared.Branding
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/**
 * 健康域(health,F 步 2026-09-24):原 RestApiRoutes.routes 巨型 match 的
 * /health、/health/conn、/usage/aggregate、/tts、/command case 逐字迁入,
 * 行为保持;经 RestApiRoutes.routes 级联首位挂载,各臂首段路径字面量与
 * 其余域互不重叠。
 *
 * 本地加速 M1 批 ②/④(v2 §3.3):/health 增「同机近路探测」面 —— 环回腿
 * 并 `deviceId` 键(门户探测身份核对锚)+ 门户 origin 白名单 CORS 应答
 * (GET/OPTIONS 预检);非环回/非白名单 = 载荷与响应头逐字不变(现状即
 * 降级兜底)。判据与应答助手见 companion 末段。
 */
private[gateway] object HealthRoutes:

  def routes(ctx: RestApiCtx): HttpRoutes[IO] =
    import ctx.*
    HttpRoutes.of[IO] {
      // Health check (P2-6 layered, 2026-08-25): `providers` = per-model health
      // (up / down:<reason>), `search` = Tier 2a standalone search API health
      // (unconfigured/up/down) — INDEPENDENT of model health, so "模型配额 DOWN
      // 但搜索 API 正常" is visible at a glance. `status` stays "ok" while the
      // gateway serves (the watchdog keys on HTTP 200).
      case req @ GET -> Root / "health" =>
        // Payload construction is SINGLE-SOURCED in
        // `nebflow.core.hotrestart.HealthPayload.build` (hotupdate batch 2, G3): the
        // hot-restart door self-check (tier 3 "port-serving" / tier 4 "version-match")
        // reads the very same payload over HTTP, so the version a successor
        // advertises can never drift from what this endpoint serves. Fields,
        // field order and value semantics are unchanged (byte-compatible body).
        // 统一更新收敛闭环（M3 ③）：`update` 键 = 最近挂账/决策读数，**仅当有状态
        // 可报时出现**（无键 = 默认载荷逐字节不变，探针契约零回归）。
        // 本地加速批 ②（v2 §3.3 步骤1-2）：环回腿额外并 `deviceId` 键 —— 门户页
        // 同机探测的身份核对锚（与 URL `?device=` 一致才允许近路切换）。键只在
        // 「环回腿 ∧ 本机已铸身份」时出现，与 `update` 键同一「有状态才出现」
        // 纪律；非环回腿/未铸身份 = 默认载荷逐字节不变。CORS 白名单应答只发生
        // 在环回腿上（见 probeCorsHeaders）——LAN/隧道腿照旧零 CORS 头。
        sharedResources.updateConvergence
          .traverse(_.healthJson)
          .map(_.flatten)
          .flatMap { extra =>
            val updateKey = extra.map(u => Json.obj("update" -> u)).getOrElse(Json.obj())
            HealthPayload.build(sharedResources.healthMonitor, updateKey)
          }
          .flatMap(nearPathProbePayload(req, _))
          .flatMap(Ok(_).map(_.putHeaders(probeCorsHeaders(req)*)))

      // 同机近路探测的预检腿（本地加速批 ②，v2 §3.3）：门户页（公网 https）
      // 探本机回环 /api/health 属 Private-Network 请求，Chromium 先发 OPTIONS
      // 预检；此前该路径落空（/api 树 404）⇒ 探测必败。应答同 probeCorsHeaders
      // 白名单闸 —— 非白名单 origin / 非环回腿 = 裸 204 无 CORS 头（浏览器不可
      // 读 ⇒ 门户停留现状，批 ④ 的降级语义）。
      case req @ OPTIONS -> Root / "health" =>
        NoContent().map(_.putHeaders(probeCorsHeaders(req)*))

      // 同机近路探测的「端口锚」腿（本地回路短路批，2026-10-22 诊断修复批——
      // 「本地点大文件触发重连」：控制台页经隧道加载时，本机文件字节全走
      // 隧道把心跳 pong 饿死 ⇒ 前端 3-miss 自我逐出。修法 = 控制台页自检
      // 近路跳回 http://127.0.0.1:<port>，本地文件从此零隧道）。
      // 经 withAuth 门（会话凭据）：端口不是身份面，但只服务已入座会话；
      // 身份核对锚仍由环回 /health 的 deviceId 键承担——本端点给端口、
      // 环回探测给身份，两键合起来才构成「同机确认」。无缓存（no-store 由
      // 调用方带，此处载荷小、读数随配置）。
      case req @ GET -> Root / "health" / "loopback-info" =>
        withAuth(req) {
          Ok(Json.obj("port" -> ctx.gatewayPort.asJson))
        }

      // 连接面只读读数（R-1b conn-guard 批，设计件 §C④-4）：零凭据面（仅
      // 对端 IP + 计数 + 上限/超时口径 + fd 代理）。与 /health 同级无令牌门
      // （watchdog/作者探视用）；不含任何 header/body/token 内容。
      //
      // perf-481 A2：并入 `outbound` 段 = 出站队列溢出的**机械读数面**。
      // 判据「溢出计数非零 ∧ 队列长度有界」在返工前**零读点**（计数只有定义、
      // 无任何消费者）⇒ 结构性不可观测。本段把它挂到既有只读健康面上（不新开
      // 端点、不新增凭据/令牌面）：`capacity` 是每连接出站队列上界（有界性本身
      // 的读数），`overflowDrops` / `overflowCloses` 是两个只增计数。
      // 实例级读数：`ctx.wsHub` 就是 GatewayMain 里那个 hub（同一实例），
      // 也在 WebSocketRoutes 里接收溢出事件 ⇒ 计数与生产事件流同一来源。
      case GET -> Root / "health" / "conn" =>
        connGuard.snapshot.flatMap { s =>
          Ok(
            ConnGuard
              .healthJson(s, ConnGuard.fdCount())
              .deepMerge(Json.obj("outbound" -> wsHub.outboundStatsJson(wsRoutes.OutboundQueueCapacity)))
          )
        }

      // Token consumption dashboard aggregate (2026-08-18): structured LLM usage
      // telemetry with dimension slicing.
      //   dim=provider|model|agent|hour|day (absent = totals only)
      //   from/to = epoch millis, inclusive lower / exclusive upper (both optional)
      //   provider/model/agent = exact-match filters, applied before grouping
      //     (dashboard D1: orthogonal to dim — all absent = unfiltered, and an
      //     empty value is treated as absent for backward compatibility)
      case req @ GET -> Root / "usage" / "aggregate" =>
        withAuth(req) {
          val params = req.uri.multiParams
          val dim = params.get("dim").flatMap(_.headOption)
          val from = params.get("from").flatMap(_.headOption).flatMap(_.toLongOption)
          val to = params.get("to").flatMap(_.headOption).flatMap(_.toLongOption)
          val provider = params.get("provider").flatMap(_.headOption).filter(_.nonEmpty)
          val model = params.get("model").flatMap(_.headOption).filter(_.nonEmpty)
          val agent = params.get("agent").flatMap(_.headOption).filter(_.nonEmpty)
          sharedResources.usageRecordStore
            .aggregate(dim, from, to, provider, model, agent)
            // M2 缓存语义小刀（v2 §3.1 ① · 用量聚合行）：ETag = 载荷内容寻址——窗口
            // 与过滤参数决定聚合输入，已完结小时的聚合输出不可变（UsageAggCache 小时
            // 粒度 cell），窗口不变 + 未进新数据 ⇒ 载荷逐字节稳定 ⇒ 内容 hash 命中
            // 304（条件往返 ≈2ms，v1 §A.1 实测锚）。`private`：withAuth 之后（#538 同规）。
            .flatMap(agg => HttpCache.conditionalOk(req, agg.asJson, HttpCache.ofJson(agg.asJson)))
        }

      // TTS 语音合成。门控（2026-09-20 收尾批）：原注记「无需 auth，内部调用」**已作废** ——
      // 唯一调用方是登录态 webui（`web/js/chat.js` `VoicePlayer._fetchTts`，本批已补带
      // `Authorization`）。与其余 REST 面同门：无令牌 403、带令牌照走业务体（`withAuth`
      // 纯套壳，业务体逐行保留）。门的判据在业务判据（`ttsService` 是否配置）**之前**。
      case req @ POST -> Root / "tts" =>
        withAuth(req) {
          ttsService match
            case None => NotFound(Json.obj("error" -> "TTS not configured".asJson))
            case Some(svc) =>
              req.as[Json].flatMap { body =>
                val text = body.hcursor.downField("text").as[String].getOrElse("")
                svc.synthesize(text).flatMap {
                  case Some(bytes) =>
                    IO.pure(
                      Response[IO](
                        status = Status.Ok,
                        headers = Headers(`Content-Type`(MediaType.audio.wav)),
                        body = Stream.emits(bytes).covary[IO]
                      )
                    )
                  case None => NotFound(Json.obj("error" -> "TTS synthesis failed".asJson))
                }
              }
        }

      // Generic command endpoint — mirrors WS messages
      case req @ POST -> Root / "command" =>
        withAuth(req) {
          req.as[Json].flatMap { payload =>
            val responseRef = cats.effect.Ref.unsafe[IO, Option[Json]](None)
            val wsSend = (json: Json) => responseRef.set(Some(json))
            wsRoutes.handleMessagePublic(payload.noSpaces, wsSend).flatMap { _ =>
              responseRef.get.flatMap {
                case Some(resp) => Ok(resp)
                case None => Ok(Json.obj("status" -> "ok".asJson))
              }
            }
          }
        }

    }
  end routes

  // ── 同机近路探测面（本地加速 M1 批 ②/④，接口契约 = v2 §3.3 步骤1-2/5）─────

  /**
   * `deviceId` 并键（身份核对锚）：只在**环回腿**上读本机身份（零写盘、零铸造
   * 的 `readDeviceIdOnly`；PortalUrl.startupUrl 同一读法先例）。LAN/隧道腿不并
   * ——`/health` 无令牌门（watchdog 用），deviceId 不该因本批而变成 LAN 可枚举
   * 量；跨设备本无近路，探测面只服务本机门户页。未铸身份 ⇒ 载荷原样（门户
   * 探测读不到 deviceId ⇒ 不切换 = 停留门户，批 ④ 降级语义）。
   */
  private def nearPathProbePayload(req: Request[IO], base: Json): IO[Json] =
    if !LoopbackLeg.isLoopback(req) then IO.pure(base)
    else
      IO.blocking(nebflow.neblink.DeviceIdentity.readDeviceIdOnly).flatMap {
        case None => IO.pure(base)
        case Some(id) => IO.pure(base.deepMerge(Json.obj("deviceId" -> id.asJson)))
      }

  /** 预检缓存窗（探测是低频动作，600s 与 nf-reach 同族量级）。 */
  private val ProbeCorsMaxAge: String = "600"

  /**
   * CORS 白名单（v2 §3.3 步骤1「网关对本机健康端点回 CORS 白名单，新增一小刀」）。
   *
   * 白名单源 = 门户 URL 的 origin（brand.conf `portalUrl` + 门户 env 覆盖
   * （`Branding.env("PORTAL_URL")` 双前缀，同 PortalUrl.livePortalEnv 口径））
   * **＋ 设备中继 origin**（brand.conf `serverUrl`，本地回路短路批 2026-10-22）：
   * 控制台页本身托管在中继 origin 下，它发起的环回近路探测与中继 consoleUrl
   * 同族（同为我们控制的 origin）——Mac 本机场景正是这一页需要读到环回探测的
   * deviceId 才能跳回本地直连接口。
   * **不是 `*`**：载荷含 deviceId/version（本机指纹面），只有门户 origin 配读；
   * 其他一切 origin（含任意 localhost 端口页面）零 CORS 头 ⇒ 浏览器读不到 ⇒
   * 近路判定不成立 = 停留门户（批 ④）。
   *
   * 且整个应答**只在环回腿上发生**（`LoopbackLeg`）：门户页探的正是本机网关，
   * 跨设备场景不存在近路，不该给「从另一台设备探到的本机实例」开 CORS。
   * PNA 头（Allow-Private-Network）与 nf-reach 同款：公网页面 → 回环目标的
   * Chromium 私网预检必须由服务端应答。
   */
  def probeCorsHeaders(req: Request[IO]): List[Header.ToRaw] =
    val reqOrigin = req.headers.headers
      .find(h => h.name.toString.equalsIgnoreCase("Origin"))
      .map(_.value.trim)
      .filter(_.nonEmpty)
    val allowed =
      LoopbackLeg.isLoopback(req) &&
        reqOrigin.exists(o => canonicalOrigin(o).exists(allowedProbeOrigins.contains))
    if !allowed then Nil
    else
      val origin = reqOrigin.getOrElse("")
      List(
        Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Origin"), origin),
        Header.Raw(org.typelevel.ci.CIString("Vary"), "Origin"),
        Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Methods"), "GET, OPTIONS"),
        Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Headers"), "content-type"),
        Header.Raw(org.typelevel.ci.CIString("Access-Control-Allow-Private-Network"), "true"),
        Header.Raw(org.typelevel.ci.CIString("Access-Control-Max-Age"), ProbeCorsMaxAge)
      )

  end probeCorsHeaders

  /** 白名单 origin 集（纯函数，spec 直读）：门户 base + 门户 env 覆盖 + 中继 origin。 */
  private[gateway] def allowedProbeOrigins: Set[String] =
    List(
      Option(Branding.portalUrl),
      Branding.env("PORTAL_URL"),
      Option(Branding.serverUrl)
    ).flatten
      .flatMap(u => canonicalOrigin(u))
      .toSet

  /**
   * origin 归一（scheme+host 小写 + 缺省端口抹除）：`https://x:443/a` 与
   * `https://x` 同一 origin。非 http(s)/无 host（坏值、`about:…`）⇒ None。
   */
  private[gateway] def canonicalOrigin(url: String): Option[String] =
    try
      val u = new java.net.URI(url)
      val scheme = Option(u.getScheme).map(_.toLowerCase)
      val host = Option(u.getHost).map(_.toLowerCase)
      (scheme, host) match
        case (Some(s), Some(h)) =>
          val explicit = Option(u.getPort).filter(p => p != -1)
          val port = explicit.filterNot(p => (s == "https" && p == 443) || (s == "http" && p == 80))
          Some(port.fold(s"$s://$h")(p => s"$s://$h:$p"))
        case _ => None
    catch case _: Exception => None

end HealthRoutes
