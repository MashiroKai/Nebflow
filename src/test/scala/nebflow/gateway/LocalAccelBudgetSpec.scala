package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.CatsEffectSuite
import nebflow.neblink.{ConsoleTicketStore, PortalHostFaces}
import org.http4s.HttpApp
import org.http4s.server.Router
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * 同机近路性能预算 spec（本地加速 M1 批 ③；量级表 = v2 §4，分段法 = #538
 * `evidence/20261019_082500_imgslow538-segmented-timing.md`：全链 = 铸票段 +
 * 兑换段 + 取数段，逐段计时、p95/中位并报）。
 *
 * **靶面**：近路全链（铸 local 票 → `/?ticket` 303 落会话 → 会话 REST 回环）
 * 走**真实回环 socket**（Ember 绑 127.0.0.1 临时端口 + JDK HttpClient，非进程
 * 内直调——wire 形态才算数）。本链同时是 ① 会话绑定的端到端活体：local-ticket
 * 会话在真环回 socket 上必须坐得进会话轨（204），否则预算链直接断裂。
 *
 * **v2 §4.2 三靶心与本 spec 分段的映射**（同机近路后全部塌缩到同一组回环腿，
 * #538 分段法口径——每面 = 分段之和）：
 *   - 历史页（未命中全页 10-500KB）→ 兑换段 + 会话 REST 段（页载荷回环
 *     10-50ms 锚）⇒ 预算 = 近路全链闸（50ms）；
 *   - token 首帧 → WS 回环首帧（量级表 1-5ms），与近路全链同闸 ⇒ 50ms；
 *   - AskUser 全程（下行+上行两段，量级表 5-10ms）⇒ 近路全链闸（50ms）。
 * 三个靶心在近路下共享同一组回环分段（会话 cookie 常驻、无逐载荷铸票），
 * 故以近路全链 p95 一条闸统一钉住；分段中位数逐段打印供 #538 式比对。
 *
 * **预算常量**（v2 §4.3 两条，补 Q6 同族）：
 *   - 近路全链 p95 < 50ms（本 spec 断言；量级表 12-300× 的「设计后」列）；
 *   - 304 条件对账 p95 < 10ms（本 spec 断言；nf-file ETag 腿代表既有 304
 *     机制，v1 §A.1 实测锚 1.6ms）。
 *
 * 预算对回环 HTTP 是 10-50× 量级裕度（单程实测 1-3ms），慢 CI 只在系统性
 * 劣化时才会击穿——击穿即回归信号，不是噪声。
 */
class LocalAccelBudgetSpec extends CatsEffectSuite:

  /** v2 §4.3 预算线 1：近路全链 p95（ms）。 */
  val BudgetNearPathChainP95Ms: Double = 50.0

  /** v2 §4.3 预算线 2：304 条件对账 p95（ms）。 */
  val BudgetConditional304P95Ms: Double = 10.0

  private val WarmupIters = 30
  private val MeasuredIters = 240

  private val FileToken = "budget-spec-file-token"
  private val logger = nebflow.shared.NebflowLogger.forName("budget-spec")

  private def median(xs: Vector[Double]): Double =
    if xs.isEmpty then 0.0
    else
      val s = xs.sorted
      if s.size % 2 == 1 then s(s.size / 2) else (s(s.size / 2 - 1) + s(s.size / 2)) / 2.0

  private def p95(xs: Vector[Double]): Double =
    if xs.isEmpty then 0.0
    else xs.sorted(math.Ordering.Double.TotalOrdering)(math.min(xs.size - 1, (xs.size * 0.95).toInt))

  // ── 生产同款切片装配 ──────────────────────────────────────────────────────

  private def mkFaces(store: ConsoleTicketStore): PortalHostFaces =
    val faces = new PortalHostFaces(gatewayPort = 0)
    faces.bindConsoleTicketFace(
      PortalHostFaces.ConsoleTicketFace(
        isUnbound = IO.pure(false),
        verifyAndConsume = t => store.verifyAndConsume(t),
        mintLocalTicket = store
          .issue(
            ConsoleTicketStore.LocalAccountSub,
            "budget-spec-device",
            ConsoleTicketStore.OriginLocalTicket,
            Some(ConsoleTicketStore.LocalTicketTtlSeconds)
          )
          .map(Right(_))
      )
    )
    faces

  end mkFaces

  /** 预算 face 的注入式裁定（同 NfFileRoutesSpec：主体是链路不是策略）。 */
  private val nfPolicy = NfFilePolicy.NfPathPolicy(
    java.nio.file.Paths.get("/nonexistent-nebflow-data-root"),
    java.nio.file.Paths.get("/nonexistent-nebflow-workspace"),
    Set.empty
  )

  /**
   * 生产同款挂载（GatewayMain / WebSocketRoutes:886 的形状）：AuthAdmin 铸票
   * 在 /api 槽；兑换 + 会话 REST + nf-file 在 / 槽（nf 路由自带 api 段字面量）。
   */
  private def buildApp(nfStore: NfTicketStore): HttpApp[IO] =
    val sessions = GatewaySessionStore.unsafeCreate()
    val faces = mkFaces(ConsoleTicketStore.unsafeCreate())
    val tokenRef = cats.effect.Ref.unsafe[IO, String](FileToken)
    Router(
      "/api" -> AuthAdminRoutes.routes(tokenRef, sessions, faces, logger),
      "/" -> (ConsoleTicketRoutes.ticketRoutes(sessions, faces, IO.pure("net-budget"), logger)
        <+> NfFileRoutes.nfAuthcheckRoutes(tokenRef, sessions)
        <+> NfFileRoutes.nfFileRoutes(nfStore, nfPolicy))
    ).orNotFound

  /** 服务器 + 测量同生命周期（use 窗口内测量，读完才关）。 */
  private def serveOn[A](app: HttpApp[IO])(measure: String => IO[A]): IO[A] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0") // 临时端口——不碰保留端口，环回 only
      .withHttpApp(app)
      // JDK HttpClient 持 keep-alive 连接；默认 30s 排空窗会拖死整个 spec
      // （测量本身 <2s）。1s 窗足够——预算读数在 release 前已完成。
      .withShutdownTimeout(scala.concurrent.duration.DurationInt(1).second)
      .build
      .use(srv => measure(s"http://127.0.0.1:${srv.address.getPort}"))

  private val httpClient: HttpClient =
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

  private def timed(req: HttpRequest): (Double, HttpResponse[String]) =
    val t0 = System.nanoTime()
    val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    ((System.nanoTime() - t0) / 1e6, resp)

  private def get(url: String, headers: (String, String)*): (Double, HttpResponse[String]) =
    val b = HttpRequest.newBuilder(URI.create(url)).GET()
    headers.foreach((n, v) => b.header(n, v))
    timed(b.build())

  private def post(url: String, token: String, body: String): (Double, HttpResponse[String]) =
    timed(
      HttpRequest
        .newBuilder(URI.create(url))
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .header("Authorization", s"Bearer $token")
        .header("Content-Type", "application/json")
        .build()
    )

  /**
   * 一轮近路全链（#538 分段）：铸票段 → 兑换段（303+cookie）→ 会话 REST 段
   * （204）。返回 (铸票段, 全链, 兑换段, 会话REST段) 毫秒。
   */
  private def chainOnce(base: String): (Double, Double, Double, Double) =
    val (mintMs, mintResp) = post(s"$base/api/auth/console-ticket", FileToken, "{}")
    assert(mintResp.statusCode() == 200, s"mint leg must seat: ${mintResp.statusCode()} ${mintResp.body()}")
    val ticket = io.circe.parser
      .parse(mintResp.body())
      .toOption
      .flatMap(_.hcursor.get[String]("ticket").toOption)
      .getOrElse(sys.error("mint leg returned no ticket"))
    val (redeemMs, redeemResp) = get(s"$base/?ticket=$ticket")
    assert(redeemResp.statusCode() == 303, s"redeem leg must 303: ${redeemResp.statusCode()}")
    val cookie = redeemResp
      .headers()
      .allValues("set-cookie")
      .asScala
      .headOption
      .map(_.split(";").head.trim)
      .getOrElse(sys.error("redeem leg returned no session cookie"))
    val (restMs, restResp) = get(s"$base/api/nf-authcheck", "Cookie" -> cookie)
    assert(
      restResp.statusCode() == 204,
      s"session REST leg must seat via the loopback-bound local session: ${restResp.statusCode()}"
    )
    (mintMs, mintMs + redeemMs + restMs, redeemMs, restMs)
  end chainOnce

  // ── 预算线 1：近路全链 ─────────────────────────────────────────────────────

  test("near-path full chain (mint -> redeem -> session REST) p95 < 50ms over a real loopback socket"):
    serveOn(buildApp(NfTicketStore.unsafeCreate(1800))) { base =>
      IO.blocking {
        // warmup：JIT / 连接池 / 会话簿走进稳态（读数不参加统计）。
        (0 until WarmupIters).foreach(_ => chainOnce(base))
        val mint = Vector.newBuilder[Double]
        val redeem = Vector.newBuilder[Double]
        val rest = Vector.newBuilder[Double]
        val chain = Vector.newBuilder[Double]
        (0 until MeasuredIters).foreach { _ =>
          val (m, whole, rd, rst) = chainOnce(base)
          mint += m; chain += whole; redeem += rd; rest += rst
        }
        (mint.result(), redeem.result(), rest.result(), chain.result())
      }.map { case (mint, redeem, rest, chain) =>
        val chainP95 = p95(chain)
        println(
          f"[local-accel budget] near-path chain n=$MeasuredIters: " +
            f"p95=$chainP95%.2fms (budget=$BudgetNearPathChainP95Ms%.0f) | " +
            f"segments median: mint=${median(mint)}%.2fms redeem=${median(redeem)}%.2fms sessionREST=${median(rest)}%.2fms"
        )
        assert(
          chainP95 < BudgetNearPathChainP95Ms,
          f"near-path chain p95=$chainP95%.2fms exceeded budget ${BudgetNearPathChainP95Ms}ms (v2 §4.3 line 1)"
        )
      }
    }

  // ── 预算线 2：304 条件对账 ─────────────────────────────────────────────────

  test("conditional 304 revalidation p95 < 10ms (nf-file ETag leg, v1 §A.1 anchor 1.6ms)"):
    val acquire = IO.blocking {
      val tmp = Files.createTempDirectory("nebflow-budget-304")
      val file = tmp.resolve("budget-page.js")
      Files.write(file, ("window.PAGE=\"" + ("x" * 65536) + "\";").getBytes(StandardCharsets.UTF_8))
      (tmp, file)
    }
    val cleanup: ((Path, Path)) => IO[Unit] =
      (tmp, file) => IO.blocking { Files.deleteIfExists(file); Files.deleteIfExists(tmp) }.void
    acquire.flatMap { paths =>
      val (tmp, file) = paths
      val run = for
        nfStore <- IO(NfTicketStore.unsafeCreate(1800))
        ticket <- IO.blocking(file.toRealPath().toString).flatMap(p => nfStore.issue("budget", p)).map(_.token)
        p304 <- serveOn(buildApp(nfStore)) { base =>
          IO.blocking {
            val url = s"$base/api/nf-file?path=${file.toRealPath()}&ticket=$ticket"
            val (firstMs, first) = get(url)
            assert(first.statusCode() == 200, s"first fetch must serve: ${first.statusCode()}")
            val etag = Option(first.headers().firstValue("etag").orElse(null))
              .getOrElse(sys.error("nf-file 200 must carry an ETag (StaticFile default)"))
            (0 until WarmupIters).foreach(_ => get(url, "If-None-Match" -> etag))
            val cond = Vector.newBuilder[Double]
            (0 until MeasuredIters).foreach { _ =>
              val (ms, resp) = get(url, "If-None-Match" -> etag)
              assert(resp.statusCode() == 304, s"conditional fetch must 304: ${resp.statusCode()}")
              cond += ms
            }
            val cs = cond.result()
            val p = p95(cs)
            println(
              f"[local-accel budget] 304 revalidation n=$MeasuredIters: " +
                f"p95=$p%.2fms (budget=$BudgetConditional304P95Ms%.0f) | median=${median(cs)}%.2fms | first200=${firstMs}%.2fms"
            )
            p
          }
        }
      yield p304
      run.guarantee(cleanup(paths)).map { p304 =>
        assert(
          p304 < BudgetConditional304P95Ms,
          f"304 revalidation p95=$p304%.2fms exceeded budget ${BudgetConditional304P95Ms}ms (v2 §4.3 line 2)"
        )
      }
    }
end LocalAccelBudgetSpec
