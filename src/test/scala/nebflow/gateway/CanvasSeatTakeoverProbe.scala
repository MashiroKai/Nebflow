package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.*
import io.circe.Json
import io.circe.syntax.*
import nebflow.shared.{NebflowLogger, NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

/**
 * Canvas 单写者席位 · **静默接管真端口探针**（静默接管批 2026-11-19；取代
 * 「席位活性判定真端口探针」）。真实现场读数 = 真 socket、真路由、真响应状态码。
 * 本探针把**真** `RestApiRoutes`（含 `ConfigRoutes` 的 `/canvas-writer` 三臂与
 * `/canvas-tabs` PUT）挂在 `Router("/api" -> …)`（= `GatewayMain` 的生产挂法）上，
 * 绑 127.0.0.1 **临时端口**（port 0），用 JDK HttpClient 走真 HTTP；ws 连接生命周期
 * 由同一个 `ConsoleTakeover` 实例的 `onConnect`/`onDisconnect` 直接驱动。
 *
 * 三臂：
 *   A 静默接管：活写者在位 → 他窗申请 → **200 + epoch 自增**（旧口径 409）；旧 epoch
 *     写穿 409、新 epoch 写穿 200、GET 角色随翻位。
 *   B 定向通知：被顶下的**活**写者连接收到既有帧型 `canvasWriterChanged`（新 epoch）
 *     —— 这是前端落「未合并草稿」的触发点，零用户可见文案帧。
 *   C 死端：占位者 socket 走掉后他窗**同刻**即 200（不再等任何活性窗），死端零连接
 *     ⇒ 无接收方、零出帧。
 *
 * 生命周期/端口纪律：只绑 loopback 临时端口、自带 `.use` 回收、退出即零残留；数据根换到
 * 临时目录（写穿腿会落 `canvas_tabs.json`），**绝不碰**作者的真实数据根与生产实例。
 *
 * RUN（前台、自退出）：
 *   sbt --batch 'Test/runMain nebflow.gateway.CanvasSeatTakeoverProbe'
 */
object CanvasSeatTakeoverProbe:

  private val token = "canvas-seat-takeover-probe-token"
  private val Ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/151.0 Safari/537.36"
  private val RivalUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1"

  private def say(line: String): Unit = println(s"[seat-takeover-probe] $line")

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

  private def frameType(body: String): String =
    io.circe.parser.parse(body).toOption.flatMap(_.hcursor.get[String]("type").toOption).getOrElse("-")

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

  private def mkTakeover(sent: Ref[IO, Vector[(String, Json)]]) =
    new ConsoleTakeover(
      (id, frame) => sent.update(_ :+ (id -> frame)),
      NebflowLogger.forName("nebflow.canvas-seat-takeover-probe")
    )

  private def meta(client: String, ua: String = Ua) =
    ConsoleTakeover.ClientMeta(clientId = Some(client), ua = Some(ua), ip = Some("127.0.0.1"))

  private def withServer[A](tk: ConsoleTakeover)(f: String => IO[A]): IO[A] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"127.0.0.1")
      .withPort(port"0") // ephemeral —— 绝不碰 6138/8080/8097
      .withHttpApp(Router("/api" -> mkRoutes(tk)).orNotFound)
      .build
      .use(srv => f(s"http://127.0.0.1:${srv.address.getPort}"))

  // ── 臂 A：静默接管（活写者在位，他窗申请即授予）─────────────────────────────

  private def armTakeover(): IO[Unit] =
    val sent = Ref.unsafe[IO, Vector[(String, Json)]](Vector.empty)
    val tk = mkTakeover(sent)
    withServer(tk) { base =>
      for
        _ <- IO(say(s"[A] base=$base"))
        _ <- tk.onConnect("s-live", "conn-live", meta("live-client"))
        r1 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("live-client", "tab-live"))
        _ <- IO(say(s"[A1] 活写者 claim -> ${r1._1} ${reduce(r1._2, List("role", "epoch"))}"))
        e1 = epochOf(r1._2)
        r2 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-client", "tab-rival"))
        _ <- IO(say(s"[A2] 他窗申请（活写者在位）-> ${r2._1} ${reduce(r2._2, List("role", "epoch", "error"))}"))
        e2 = epochOf(r2._2)
        r3 <- httpIO("PUT", s"$base/api/canvas-tabs", putBody("live-client", "tab-live", e1))
        _ <- IO(say(s"[A3] 旧写者拿旧 epoch=$e1 写穿 -> ${r3._1} ${reduce(r3._2, List("error", "role"))}"))
        r4 <- httpIO("PUT", s"$base/api/canvas-tabs", putBody("rival-client", "tab-rival", e2))
        _ <- IO(say(s"[A4] 新写者写穿（epoch=$e2）-> ${r4._1} ${reduce(r4._2, List("ok", "error"))}"))
        r5 <- httpIO("GET", s"$base/api/canvas-writer?clientId=live-client&tabInstanceId=tab-live", "")
        _ <- IO(say(s"[A5] GET role(旧写者) -> ${r5._1} ${reduce(r5._2, List("role", "epoch"))}"))
        _ <- IO(
          say(
            s"[A-verdict] 他窗申请=${r2._1} 旧写者写=${r3._1} 新写者写=${r4._1} ⇒ " +
              (if r2._1 == 200 && e2 == e1 + 1 && r3._1 == 409 && r4._1 == 200 then
                 "SILENT_TAKEOVER=instant(申请即授予，旧 epoch 当场失效)"
               else "SILENT_TAKEOVER=VIOLATED")
          )
        )
      yield ()
    }

  // ── 臂 B：定向静默通知（被顶下的活写者收到既有帧型的席位变更帧）────────────

  private def armNotify(): IO[Unit] =
    val sent = Ref.unsafe[IO, Vector[(String, Json)]](Vector.empty)
    val tk = mkTakeover(sent)
    withServer(tk) { base =>
      for
        _ <- IO(say(s"[B] base=$base"))
        _ <- tk.onConnect("s-live", "conn-live", meta("live-client"))
        _ <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("live-client", "tab-live"))
        _ <- sent.set(Vector.empty)
        r2 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-client", "tab-rival"))
        e2 = epochOf(r2._2)
        frames <- sent.get
        _ <- IO(say(s"[B1] 接管出帧 -> ${frames.map { case (id, j) => s"$id:${frameType(j.noSpaces)}" }.mkString(", ")}"))
        _ <- IO(
          say(
            "[B-verdict] " +
              (if frames.nonEmpty && frames.forall(_._1 == "conn-live") && frames.forall(f => frameType(f._2.noSpaces) == "canvasWriterChanged")
               then s"NOTIFY=delivered(被顶下写者收到 canvasWriterChanged, epoch=$e2)"
               else "NOTIFY=MISSING")
          )
        )
      yield ()
    }

  // ── 臂 C：死端（占位者消失后同刻即授予；死端无接收方、零出帧）──────────────

  private def armDeadEnd(): IO[Unit] =
    val sent = Ref.unsafe[IO, Vector[(String, Json)]](Vector.empty)
    val tk = mkTakeover(sent)
    withServer(tk) { base =>
      for
        _ <- IO(say(s"[C] base=$base"))
        _ <- tk.onConnect("s-ghost", "conn-ghost", meta("ghost-client"))
        r1 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("ghost-client", "tab-ghost"))
        e1 = epochOf(r1._2)
        _ <- tk.onDisconnect("conn-ghost")
        _ <- sent.set(Vector.empty)
        _ <- IO(say("[C1] 占位者 socket 走掉（best-effort release 丢失）—— **不等**任何活性窗，同刻申请"))
        r2 <- httpIO("POST", s"$base/api/canvas-writer/claim", claimBody("rival-client", "tab-rival"))
        e2 = epochOf(r2._2)
        _ <- IO(say(s"[C2] 他窗申请 -> ${r2._1} ${reduce(r2._2, List("role", "epoch"))}"))
        frames <- sent.get
        _ <- IO(say(s"[C3] 死端出帧 -> ${if frames.isEmpty then "(none)" else frames.toString}"))
        _ <- IO(
          say(
            s"[C-verdict] 同刻申请=${r2._1} epoch=${e1}->${e2} 死端出帧=${frames.size} ⇒ " +
              (if r2._1 == 200 && e2 == e1 + 1 && frames.isEmpty then
                 "DEAD_END=instant-takeover(旧活性腿已移除，死端零接收方)"
               else "DEAD_END=VIOLATED")
          )
        )
      yield ()
    }

  def main(argv: Array[String]): Unit =
    val isolated = os.temp.dir(prefix = "canvas-seat-takeover-probe-")
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(isolated)
    try
      say("== Canvas 席位静默接管探针（loopback 临时端口；生产挂法 Router(/api)）==")
      say(s"dataRoot=$isolated")
      armTakeover().unsafeRunSync()
      armNotify().unsafeRunSync()
      armDeadEnd().unsafeRunSync()
      say("== end (exit 0) ==")
    finally
      PathUtil.setDataRoot(original)
      try os.remove.all(isolated) catch case _: Throwable => ()
    sys.exit(0)
  end main
end CanvasSeatTakeoverProbe
