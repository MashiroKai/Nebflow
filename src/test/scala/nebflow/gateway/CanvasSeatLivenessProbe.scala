package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

import scala.concurrent.duration.*

/**
 * Canvas 单写者席位 · **活性判定真端口探针**（seat-liveness 批 2026-11-18）。
 *
 * 为什么有它：席位活性判据的**真实现场读数**必须是真 socket、真路由、真响应状态码。
 * 本探针把**真** `RestApiRoutes`（含 `ConfigRoutes` 的 `/canvas-writer` 三臂与
 * `/canvas-tabs` PUT）挂在 `Router("/api" -> …)`（= `GatewayMain` 的生产挂法，
 * `src/main/scala/nebflow/gateway/GatewayMain.scala:2374`）上，绑 127.0.0.1 **临时端口**
 * （port 0），用 JDK HttpClient 走真 HTTP；ws 连接生命周期由同一个 `ConsoleTakeover` 实例的
 * `onConnect`/`onDisconnect` 直接驱动（探针里没有 ws hub，这一腿是进程内注入）。
 *
 * 三臂（各起各的实例/端口，互不串扰）：
 *   A 占位者非正常消失（无活连接腿）：A 端拿席位 → B 端（页面**早就开着**，不再触发
 *     `onConnect` ⇒ 等不到 `sweepStale`）申请被拒 409 → A 端 socket 走掉（pagehide 的
 *     best-effort release 丢了）→ 过宽限后 B 端申请/写穿；
 *   B 僵尸连接（连接但静默腿）：连接还在、零入站帧，过「明确超时」后他窗可申请；
 *   C 非抢占：活着的写者（新鲜活动）被连申请两次一律 409，且他窗拿旧 epoch 写不进。
 *
 * 窗口三档（**只注入本类既有两枚窗**，与生产同一条码路）：
 *   `--windows=prod`（缺省）生产缺省值 `activityTtlMs=120s` / `staleTtlMs=600s`；
 *   `--windows=fast` 压到 1500ms/1500ms（臂 B 只在这档跑得动）；
 *   `--windows=off`  两枚窗设为天文值 ⇒ 判据**永不成立** = 与修复前同码路的对照读数。
 *
 * 生命周期/端口纪律：只绑 loopback 临时端口、自带 `.use` 回收、退出即零残留；数据根换到
 * 临时目录（写穿腿会落 `canvas_tabs.json`），**绝不碰** `~/.stand` 与生产实例。
 *
 * RUN（前台、自退出；一次一档）：
 *   sbt --batch 'Test/runMain nebflow.gateway.CanvasSeatLivenessProbe --windows=prod'
 */
object CanvasSeatLivenessProbe:

  private val token = "canvas-seat-liveness-probe-token"
  private val Ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
  private val Max = Long.MaxValue / 8

  private def say(line: String): Unit = println(s"[seat-liveness-probe] $line")

  /** 真 HTTP（JDK HttpClient，进程内阻塞；loopback 临时端口）。 */
  private def httpIO(method: String, url: String, body: String): IO[(Int, String)] = IO {
    val client = java.net.http.HttpClient.newHttpClient()
    val b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("Authorization", s"Bearer $token")
    val req =
      if method == "GET" then b.GET().build()
      else
        b.header("Content-Type", "application/json")
          .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body))
          .build()
    val res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
    (res.statusCode(), res.body())
  }

  private def claimBody(client: String, tab: String): String =
    Json.obj("clientId" -> client.asJson, "tabInstanceId" -> tab.asJson).noSpaces

  private def putBody(client: String, tab: String, epoch: Long): String =
    Json
      .obj("clientId" -> client.asJson, "tabInstanceId" -> tab.asJson, "epoch" -> epoch.asJson)
      .deepMerge(Json.obj("v" -> 2.asJson, "tabs" -> Json.arr()))
      .noSpaces

  private def reduce(body: String, keys: List[String]): String =
    val j = io.circe.parser.parse(body).getOrElse(Json.Null)
    val parts = keys.flatMap(k => j.hcursor.get[Json](k).toOption.map(v => s"$k=${v.noSpaces}"))
    if parts.isEmpty then body else s"{${parts.mkString(", ")}}"

  private def epochOf(body: String): Long =
    io.circe.parser.parse(body).toOption.flatMap(_.hcursor.get[Long]("epoch").toOption).getOrElse(-1L)

  private def mkRoutes(tk: ConsoleTakeover) =
    new RestApiRoutes(
      tokenRef = Ref.unsafe[IO, String](token),
      configRef = Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null,
      canvasTakeover = tk
    ).routes

  private def mkTakeover(goneGraceMs: Long, silentTtlMs: Long) =
    new ConsoleTakeover(
      (_, _) => IO.unit,
      NebflowLogger.forName("nebflow.canvas-seat-liveness-probe"),
      staleTtlMs = silentTtlMs,
      activityTtlMs = goneGraceMs
    )

  private def meta(client: String, ua: String = Ua) =
    ConsoleTakeover.ClientMeta(clientId = Some(client), ua = Some(ua), ip = Some("127.0.0.1"))

  private val RivalUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1"

  /** 真端口（loopback 临时端口）+ 生产挂法 `Router("/api")`；`.use` 退出即回收。 */
  private def withServer[A](tk: ConsoleTakeover)(f: String => IO[A]): IO[A] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0") // ephemeral —— 绝不碰 6138/8080/8097
      .withHttpApp(Router("/api" -> mkRoutes(tk)).orNotFound)
      .build
      .use(srv => f(s"http://127.0.0.1:${srv.address.getPort}"))

  private def windowNote(v: Long): String = if v == Max then "MAX(判据永不成立)" else s"${v}ms"

  // ── 臂 A：占位者非正常消失（无活连接腿）─────────────────────────────────────

  private def armVanished(goneGraceMs: Long, waitMs: Long): IO[Unit] =
    val tk = mkTakeover(goneGraceMs, Max)
    withServer(tk) { base =>
      for
        _ <- IO(say(s"[A] base=$base"))
        _ <- tk.onConnect("s-ghost", "conn-ghost", meta("ghost-client"))
        r1 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("ghost-client", "tab-ghost"))
        _ <- IO(say(s"[A1] A端(占位者) claim -> ${r1._1} ${reduce(r1._2, List("role", "epoch"))}"))
        e1 = epochOf(r1._2)
        _ <- tk.onConnect("s-rival", "conn-rival", meta("rival-client", RivalUa))
        _ <- IO(say("[A2] B端(另一设备) ws 入账 —— 它的控制台页面早就开着（不再触发 onConnect ⇒ 等不到 sweepStale）"))
        r2 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-client", "tab-rival"))
        _ <- IO(say(s"[A3] 占位者消失前 · B端 claim -> ${r2._1} ${reduce(r2._2, List("error", "role", "message"))}"))
        _ <- tk.onDisconnect("conn-ghost")
        _ <- IO(say("[A4] A端 socket 走掉（pagehide 的 best-effort release POST 丢失：全程无 release 调用）"))
        _ <-
          if waitMs > 0 then IO(say(s"[A5] 等 ${waitMs}ms（宽限 $goneGraceMs + 余量）")) *> IO.sleep(waitMs.millis)
          else IO(say("[A5] 本档窗口为天文值（判据永不成立）—— 不等"))
        r3 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-client", "tab-rival"))
        _ <- IO(say(s"[A6] 消失后 · B端重新申请 -> ${r3._1} ${reduce(r3._2, List("role", "epoch", "error"))}"))
        e2 = epochOf(r3._2)
        _ <-
          if e2 > 0 then
            for
              r4 <- httpIO("PUT", s"$base/api/canvas-tabs", putBody("rival-client", "tab-rival", e2))
              _ <- IO(say(s"[A7] B端写入（新 epoch=$e2）-> ${r4._1} ${reduce(r4._2, List("ok", "error"))}"))
              r5 <- httpIO("GET", s"$base/api/canvas-writer?clientId=rival-client&tabInstanceId=tab-rival", "")
              _ <- IO(say(s"[A8] GET /api/canvas-writer(rival) -> ${r5._1} ${reduce(r5._2, List("role", "epoch"))}"))
              r6 <- httpIO("PUT", s"$base/api/canvas-tabs", putBody("ghost-client", "tab-ghost", e1))
              _ <- IO(say(s"[A9] 幽灵旧 epoch=$e1 写穿 -> ${r6._1} ${reduce(r6._2, List("error", "role"))}"))
            yield ()
          else
            httpIO("PUT", s"$base/api/canvas-tabs", putBody("rival-client", "tab-rival", e1)).flatMap { r4 =>
              IO(say(s"[A7] B端写入（只拿得到旧 epoch=$e1）-> ${r4._1} ${reduce(r4._2, List("error", "role"))}"))
            }
        _ <- IO(
          say(
            s"[A-verdict] 消失前=${r2._1} 消失后=${r3._1} ⇒ " +
              (if r3._1 == 200 then "LIVENESS=recovered(席位可重新授予)" else "LIVENESS=wedged(席位被永久占住)")
          )
        )
      yield ()
    }

  // ── 臂 B：连接但静默（僵尸连接腿）──────────────────────────────────────────

  private def armZombie(silentTtlMs: Long, waitMs: Long): IO[Unit] =
    val tk = mkTakeover(Max, silentTtlMs)
    withServer(tk) { base =>
      for
        _ <- IO(say(s"[B] base=$base  silentTtl=${windowNote(silentTtlMs)}"))
        _ <- tk.onConnect("s-zombie", "conn-zombie", meta("zombie-client"))
        r1 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("zombie-client", "tab-zombie"))
        _ <- IO(say(s"[B1] 僵尸端 claim -> ${r1._1} ${reduce(r1._2, List("role", "epoch"))}"))
        r2 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-client", "tab-rival"))
        _ <- IO(say(s"[B2] 静默前 · 他窗 claim -> ${r2._1} ${reduce(r2._2, List("error", "role"))}"))
        _ <-
          if waitMs > 0 then IO(say(s"[B3] 连接仍挂在账上、零入站帧，等 ${waitMs}ms")) *> IO.sleep(waitMs.millis)
          else IO(say("[B3] 本档窗口为天文值 —— 不等"))
        r3 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-client", "tab-rival"))
        _ <- IO(say(s"[B4] 静默后 · 他窗 claim -> ${r3._1} ${reduce(r3._2, List("role", "epoch", "error"))}"))
        _ <- IO(
          say(
            s"[B-verdict] 静默前=${r2._1} 静默后=${r3._1} ⇒ " +
              (if r3._1 == 200 then "LIVENESS=recovered(僵尸连接不再永久占位)" else "LIVENESS=wedged")
          )
        )
      yield ()
    }

  // ── 臂 C：非抢占（活着的写者不被抢）────────────────────────────────────────

  private def armNonPreemption(): IO[Unit] =
    val tk = mkTakeover(Max, Max)
    withServer(tk) { base =>
      for
        _ <- IO(say(s"[C] base=$base"))
        _ <- tk.onConnect("s-live", "conn-live", meta("live-client"))
        r1 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("live-client", "tab-live"))
        _ <- IO(say(s"[C1] 活写者 claim -> ${r1._1} ${reduce(r1._2, List("role", "epoch"))}"))
        e1 = epochOf(r1._2)
        r2 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-a", "tab-a"))
        r3 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-b", "tab-b"))
        _ <- IO(
          say(
            s"[C2] 他窗连申两次 -> ${r2._1} ${reduce(r2._2, List("error", "role"))} / " +
              s"${r3._1} ${reduce(r3._2, List("error", "role"))}"
          )
        )
        r4 <- httpIO("PUT", s"$base/api/canvas-tabs", putBody("rival-a", "tab-a", e1))
        _ <- IO(say(s"[C3] 他窗拿旧 epoch=$e1 写穿 -> ${r4._1} ${reduce(r4._2, List("error", "role"))}"))
        r5 <- httpIO("PUT", s"$base/api/canvas-tabs", putBody("live-client", "tab-live", e1))
        _ <- IO(say(s"[C4] 写者自己照常写穿 -> ${r5._1} ${reduce(r5._2, List("ok", "error"))}"))
        _ <- IO(
          say(
            s"[C-verdict] 他窗申请=${r2._1}/${r3._1} 他窗写=${r4._1} 写者写=${r5._1} ⇒ " +
              (if List(r2._1, r3._1, r4._1).forall(_ == 409) && r5._1 == 200 then
                 "NON_PREEMPTION=held(活写者未被抢)"
               else "NON_PREEMPTION=VIOLATED")
          )
        )
      yield ()
    }

  def main(argv: Array[String]): Unit =
    val mode = argv.find(_.startsWith("--windows=")).map(_.drop("--windows=".length)).getOrElse("prod")
    val (goneGrace, silentTtl) = mode match
      case "fast" => (1500L, 1500L)
      case "off"  => (Max, Max)
      case _      => (ConsoleTakeover.ActivityTtlMs, ConsoleTakeover.StaleTtlMs)

    val isolated = os.temp.dir(prefix = "canvas-seat-liveness-probe-")
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(isolated)
    try
      say("== Canvas 席位活性判定探针（loopback 临时端口；生产挂法 Router(/api)）==")
      say(s"mode=$mode windows: goneGrace=${windowNote(goneGrace)} silentTtl=${windowNote(silentTtl)} dataRoot=$isolated")
      val waitA = if mode == "fast" then 2000L else if mode == "off" then 0L else goneGrace + 2000L
      armVanished(goneGrace, waitA).unsafeRunSync()
      if mode == "prod" then
        say("[B] 跳过：静默腿的生产窗是 600s，在本探针里太慢 —— 用 --windows=fast 读同一条码路（闸 spec A2 也钉了它）")
      else armZombie(silentTtl, if mode == "off" then 0L else 2000L).unsafeRunSync()
      armNonPreemption().unsafeRunSync()
      say("== end (exit 0) ==")
    finally
      PathUtil.setDataRoot(original)
      try os.remove.all(isolated) catch case _: Throwable => ()
    sys.exit(0)
  end main
end CanvasSeatLivenessProbe
