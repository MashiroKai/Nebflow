package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.{NebflowLogger, NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization

import scala.concurrent.duration.*

/**
 * Canvas 单写者席位 · **活性判定闸**（seat-liveness 批 2026-11-18，作者令）。
 *
 * 缺口（`reports/20261118_*_canvas-seat-liveness`，本套件即其可执行面）：席位的
 * 授予（[[ConsoleTakeover.claimCanvas]]）与清除（[[ConsoleTakeover.releaseCanvas]]）
 * 之间没有任何**活性**判据——持有窗口非正常退出（强杀 / 后台冻结 / 多设备）时，
 * `pagehide` 的 best-effort release 一丢，席位就被永久占住：其他窗口写入一律
 * 409 `writer_already_held`，且**无自愈**（[[ConsoleTakeover.sweepStale]] 只在
 * 后来一次无关的 `onConnect` 上跑，且要求「零连接 + 超 10 分钟」）。
 *
 * 本套件钉三件事（**全是路线级**：`RestApiRoutes` 真路由 + 真席位状态机 + 真
 * 响应体，只把 ws 连接生命周期用同一个 `ConsoleTakeover` 实例的
 * `onConnect`/`onDisconnect`/`noteActivity` 直接驱动）：
 *   A. 持有者**已不在** ⇒ 席位失效、他窗能拿到并**写穿**（不再是 409）；
 *   B. 持有者**还活着** ⇒ 他窗一律 409（非抢占语义，一个字都不许放宽）；
 *   C. 席位随持有者账户被摘（[[ConsoleTakeover.sweepStale]]）一并失效——即便
 *      控制台所有权当时已经翻了别人（席位与 owner 解耦的后果）。
 *
 * == 红绿态登记（测试纪律：先写闸、能出红的先红后绿） ==
 * 修复前（feat/base `41420bed8`）跑本套件：A1/A2/B2 尾段/C 的红线断言全红
 * （期望 `Ok`，实得 `Conflict`）——那就是「席位被永久占住」的可执行证明；
 * 落地活性判定后转绿。两次退出码见批报告。
 *
 * 窗口注入：活性窗复用本类**既有**两枚可注入窗（`activityTtlMs` = 无连接腿宽限，
 * `staleTtlMs` = 连接但静默腿的「明确超时」），故本套件与生产同一条码路，
 * 只把时长压到毫秒级。数据根隔离（写穿腿会落 `canvas_tabs.json`）。
 */
class CanvasSeatLivenessSpec extends CatsEffectSuite:

  private val originalRoot: os.Path = PathUtil.dataRoot
  private val isolatedRoot: os.Path = os.temp.dir(prefix = "canvas-seat-liveness-spec-")

  override def beforeAll(): Unit = PathUtil.setDataRoot(isolatedRoot)
  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(isolatedRoot) catch case _: Throwable => ()

  private val token = "canvas-seat-liveness-token"

  private def mkRoutes(tk: ConsoleTakeover): HttpRoutes[IO] =
    new RestApiRoutes(
      tokenRef = Ref.unsafe[IO, String](token),
      configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null,
      canvasTakeover = tk
    ).routes

  /** 生产线同上，只把两枚既有窗压到毫秒（缺省即生产值 120s / 600s）。 */
  private def mkTakeover(
    sent: Ref[IO, Vector[(String, Json)]],
    goneGraceMs: Long,
    silentTtlMs: Long
  ): ConsoleTakeover =
    new ConsoleTakeover(
      (id, frame) => sent.update(_ :+ (id -> frame)),
      NebflowLogger.forName("nebflow.canvas-seat-liveness-spec"),
      staleTtlMs = silentTtlMs,
      activityTtlMs = goneGraceMs
    )

  private def identity(client: String, tab: String): Json =
    Json.obj("clientId" -> client.asJson, "tabInstanceId" -> tab.asJson)

  private def call(routes: HttpRoutes[IO], method: Method, path: String, body: Json): IO[Response[IO]] =
    val req = Request[IO](method, Uri.unsafeFromString(path))
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(body)
    routes(req).value.map(_.getOrElse(fail(s"route fell through: $path")))

  private def claim(routes: HttpRoutes[IO], client: String, tab: String): IO[Response[IO]] =
    call(routes, Method.POST, "/canvas-writer/claim", identity(client, tab))

  private def put(routes: HttpRoutes[IO], client: String, tab: String, epoch: Long): IO[Response[IO]] =
    call(
      routes,
      Method.PUT,
      "/canvas-tabs",
      identity(client, tab).deepMerge(Json.obj("v" -> 2.asJson, "tabs" -> Json.arr(), "epoch" -> epoch.asJson))
    )

  private def epochOf(res: Response[IO]): IO[Long] = res.as[Json].map(_.hcursor.get[Long]("epoch").toOption.get)

  private val GhostUa = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Safari/604.1"
  private def meta(client: String): ConsoleTakeover.ClientMeta =
    ConsoleTakeover.ClientMeta(clientId = Some(client), ua = Some(GhostUa))

  // ── 闸 A 腿 1：无活连接（持有者最后一个连接也走了）──────────────────────────

  test("A1 无活连接腿：过宽限即失效 ⇒ 他窗重新获得席位并写穿；宽限内绝不夺") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      tk = mkTakeover(sent, goneGraceMs = 1200L, silentTtlMs = 60000L)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-ghost", "conn-ghost", meta("ghost-client"))
      // 占位者拿到席位（epoch=1）
      first <- claim(routes, "ghost-client", "tab-ghost")
      _ = assertEquals(first.status, Status.Ok)
      e1 <- epochOf(first)
      _ = assertEquals(e1, 1L)
      // 它此刻**活着**：第二个窗口申请 ⇒ 409（非抢占；这条两边都必须成立）
      live <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(live.status, Status.Conflict)
      liveBody <- live.as[Json]
      _ = assertEquals(liveBody.hcursor.get[String]("error").toOption, Some("writer_already_held"))
      // 占位窗口非正常消失：最后一个连接走掉（pagehide release 丢了）
      _ <- tk.onDisconnect("conn-ghost")
      // 宽限内：仍然不许夺（宁可多等）
      within <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(within.status, Status.Conflict)
      // 过宽限 ⇒ 席位失效（修复前这里恒 409 = 永久占住）
      _ <- IO.sleep(1500.millis)
      after <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(after.status, Status.Ok)
      e2 <- epochOf(after)
      _ = assertEquals(e2, 2L)
      // 写入成功（不再是 409），且旧 epoch 仍被栅栏挡住
      w <- put(routes, "rival-client", "tab-rival", e2)
      _ = assertEquals(w.status, Status.Ok)
      ghostPut <- put(routes, "ghost-client", "tab-ghost", e1)
      _ = assertEquals(ghostPut.status, Status.Conflict)
      frames <- sent.get
      // 消失的持有者不是「活着被踢」：零出帧（掉线 ≠ 让位 的既有语义保住）
      _ = assertEquals(frames, Vector.empty[(String, Json)])
    yield ()
  }

  // ── 闸 A 腿 2：连接还在但彻底静默（冻结/僵尸连接）───────────────────────────

  test("A2 连接但静默腿：超「明确超时」即失效（僵尸连接不再永久占位）") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      tk = mkTakeover(sent, goneGraceMs = 60000L, silentTtlMs = 1200L)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-zombie", "conn-zombie", meta("zombie-client"))
      first <- claim(routes, "zombie-client", "tab-zombie")
      _ = assertEquals(first.status, Status.Ok)
      e1 <- epochOf(first)
      live <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(live.status, Status.Conflict)
      // 连接仍挂在账上，但一条入站帧都没有（页冻结：心跳根本没发）
      _ <- IO.sleep(1500.millis)
      after <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(after.status, Status.Ok)
      e2 <- epochOf(after)
      w <- put(routes, "rival-client", "tab-rival", e2)
      _ = assertEquals(w.status, Status.Ok)
      ghostPut <- put(routes, "zombie-client", "tab-zombie", e1)
      _ = assertEquals(ghostPut.status, Status.Conflict)
    yield ()
  }

  // ── 闸 B：活着的写者不被抢（非抢占的正面证据）───────────────────────────────

  test("B 活着的写者不被抢：新鲜活动的持有者，第二个窗口恒 409") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      tk = mkTakeover(sent, goneGraceMs = 1500L, silentTtlMs = 1500L)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-live", "conn-live", meta("live-client"))
      first <- claim(routes, "live-client", "tab-live")
      _ = assertEquals(first.status, Status.Ok)
      e1 <- epochOf(first)
      // 宽限/超时窗内连来三次申请：一律 409，零授予
      s1 <- claim(routes, "rival-a", "tab-a")
      s2 <- claim(routes, "rival-b", "tab-b")
      s3 <- claim(routes, "rival-a", "tab-a")
      _ = assertEquals(List(s1.status, s2.status, s3.status), List(Status.Conflict, Status.Conflict, Status.Conflict))
      // 只读可看：他窗拿旧 epoch 也写不进
      rput <- put(routes, "rival-a", "tab-a", e1)
      _ = assertEquals(rput.status, Status.Conflict)
      // 持有者照常写穿（席位没被任何申请动过）
      wput <- put(routes, "live-client", "tab-live", e1)
      _ = assertEquals(wput.status, Status.Ok)
    yield ()
  }

  test("B2 心跳中的写者跨越「明确超时」窗仍不被抢（静默判据是时间而非永久豁免）") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      tk = mkTakeover(sent, goneGraceMs = 60000L, silentTtlMs = 400L)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-hb", "conn-hb", meta("hb-client"))
      first <- claim(routes, "hb-client", "tab-hb")
      _ = assertEquals(first.status, Status.Ok)
      e1 <- epochOf(first)
      // 心跳（ws 入站帧同款落点：noteActivity）持续刷新 ⇒ 累计时长已过窗仍活着
      _ <- (1 to 4).toList.traverse_(_ => IO.sleep(150.millis) *> tk.noteActivity("conn-hb"))
      hb <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(hb.status, Status.Conflict)
      hbBody <- hb.as[Json]
      _ = assertEquals(hbBody.hcursor.get[String]("error").toOption, Some("writer_already_held"))
      // 心跳停了 ⇒ 同一条腿上「静默」成立，席位才失效（证明上面那次 409 来自活性，不是永久豁免）
      _ <- IO.sleep(600.millis)
      gone <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(gone.status, Status.Ok)
      e2 <- epochOf(gone)
      _ = assertEquals(e2, e1 + 1L)
    yield ()
  }

  // ── 闸 C：席位随持有者账户被摘失效（owner 已翻别人时也必须解）───────────────

  test("C 席位随持有者账户被摘而失效（控制台所有权已翻别人时仍可解）") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      // 宽限放到 60s（关掉无连接腿）、陈旧窗 400ms（让 sweepStale 摘账）
      tk = mkTakeover(sent, goneGraceMs = 60000L, silentTtlMs = 400L)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-ghost", "conn-ghost", meta("ghost-client"))
      first <- claim(routes, "ghost-client", "tab-ghost")
      _ = assertEquals(first.status, Status.Ok)
      // 第三台进来把**控制台所有权**翻走（画布席位按作者令与 owner 解耦 ⇒ 席位仍在 ghost 手上）
      _ <- tk.onConnect("s-third", "conn-third", meta("third-client"))
      ownerAfterThird <- tk.owner
      _ = assertEquals(ownerAfterThird, Some("c:third-client"))
      // 占位者消失（连接走掉），账户零连接
      _ <- tk.onDisconnect("conn-ghost")
      _ <- IO.sleep(600.millis)
      // 后来一次**无关**的连接触发机会式清扫：ghost 的零连接陈旧账被摘
      _ <- tk.onConnect("s-fourth", "conn-fourth", meta("fourth-client"))
      after <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(after.status, Status.Ok)
      e2 <- epochOf(after)
      w <- put(routes, "rival-client", "tab-rival", e2)
      _ = assertEquals(w.status, Status.Ok)
      ghostPut <- put(routes, "ghost-client", "tab-ghost", 1L)
      _ = assertEquals(ghostPut.status, Status.Conflict)
    yield ()
  }
