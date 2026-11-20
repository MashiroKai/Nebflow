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

/**
 * Canvas 单写者席位 · **静默接管闸**（静默接管批 2026-11-19，作者令「直接静默吧
 * 交给主会话实现」）。本套件取代「席位活性判定」闸（[[CanvasSeatLivenessSpec]]
 * 已随该语义一并退役）：那条惰性活性腿的整体移除由本套件行为级证明。
 *
 * 作者裁定的语义（服务端面）：**申请即授予**——无写者、就是本标签、或已有**别的**
 * 写者时一律授予；已有别的写者时**静默接管**（翻位 + epoch 自增，旧写者当场失权）。
 * 「不丢改动」由**失权端**的自动落草稿承接（纯前端行为，见 js 面），本类只保证：
 *   ① 抢占是**瞬时**的（不再等任何时间窗/活性判据 ⇒ 「永久占住」结构上不可表达）；
 *   ② 被顶下的写者**收到**一条既有的静默席位变更帧（`canvasWriterChanged`，
 *      复用既有帧型与载荷，无第二套协议）——这是前端落草稿的触发点；
 *   ③ 死端（零活连接）收不到帧（收不到就没接收方，设计如实承认）；掉线路径零出帧；
 *   ④ 写穿栅栏不变：旧 epoch 一律 409 `stale_writer_epoch`。
 *
 * 全是**路线级**：`RestApiRoutes` 真路由 + 真席位状态机 + 真响应体，ws 连接生命周期
 * 用同一个 `ConsoleTakeover` 实例的 `onConnect`/`onDisconnect` 直接驱动。
 * 数据根隔离：写穿腿会落 `canvas_tabs.json`。
 */
class CanvasSeatTakeoverSpec extends CatsEffectSuite:

  private val originalRoot: os.Path = PathUtil.dataRoot
  private val isolatedRoot: os.Path = os.temp.dir(prefix = "canvas-seat-takeover-spec-")

  override def beforeAll(): Unit = PathUtil.setDataRoot(isolatedRoot)
  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(isolatedRoot) catch case _: Throwable => ()

  private val token = "canvas-seat-takeover-token"

  private def mkRoutes(tk: ConsoleTakeover): HttpRoutes[IO] =
    new RestApiRoutes(
      tokenRef = Ref.unsafe[IO, String](token),
      configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null,
      canvasTakeover = tk
    ).routes

  /** 生产同码路：唯一差别是推送面记进 `sent`（真 wsHub 在生产只是另一个 `sendTo`）。 */
  private def mkTakeover(sent: Ref[IO, Vector[(String, Json)]]): ConsoleTakeover =
    new ConsoleTakeover(
      (id, frame) => sent.update(_ :+ (id -> frame)),
      NebflowLogger.forName("nebflow.canvas-seat-takeover-spec")
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

  private def role(routes: HttpRoutes[IO], client: String, tab: String): IO[(String, Option[Long])] =
    call(routes, Method.GET, s"/canvas-writer?clientId=$client&tabInstanceId=$tab", Json.obj()).flatMap { r =>
      r.as[Json].map { j =>
        (j.hcursor.get[String]("role").toOption.getOrElse("?"), j.hcursor.get[Long]("epoch").toOption)
      }
    }

  private def epochOf(res: Response[IO]): IO[Long] = res.as[Json].map(_.hcursor.get[Long]("epoch").toOption.get)

  private val Ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Safari/604.1"

  // ── 闸 A：申请即授予（静默接管）——「永久占住」结构上不可表达 ────────────────

  test("A 静默接管：活写者在位时他窗申请仍 200（epoch 自增），旧 epoch 当场失效") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      tk = mkTakeover(sent)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-a", "conn-a", ConsoleTakeover.ClientMeta(clientId = Some("client-a"), ua = Some(Ua)))
      first <- claim(routes, "client-a", "tab-a")
      _ = assertEquals(first.status, Status.Ok)
      e1 <- epochOf(first)
      _ = assertEquals(e1, 1L)
      // 第二个窗口申请 ⇒ 静默接管（不是 409）：Ok + epoch 自增
      second <- claim(routes, "client-b", "tab-b")
      _ = assertEquals(second.status, Status.Ok, "静默抢占：已有写者不再阻挡第二个窗口")
      e2 <- epochOf(second)
      _ = assertEquals(e2, e1 + 1L)
      // 旧写者当场失权：旧 epoch 写穿一律 409
      oldPut <- put(routes, "client-a", "tab-a", e1)
      _ = assertEquals(oldPut.status, Status.Conflict)
      oldBody <- oldPut.as[Json]
      _ = assertEquals(oldBody.hcursor.get[String]("error").toOption, Some("stale_writer_epoch"))
      // 新写者写穿 200
      newPut <- put(routes, "client-b", "tab-b", e2)
      _ = assertEquals(newPut.status, Status.Ok)
      // 角色读数：新写者 writer/epoch，旧写者 reader
      newRole <- role(routes, "client-b", "tab-b")
      _ = assertEquals(newRole._1, "writer")
      _ = assertEquals(newRole._2, Some(e2))
      oldRole <- role(routes, "client-a", "tab-a")
      _ = assertEquals(oldRole._1, "reader")
    yield ()
  }

  // ── 闸 B：被顶下的写者收到静默席位变更帧（前端落草稿的触发点）──────────────

  test("B 被顶下的活写者收到定向的静默席位变更帧（既有帧型，零用户可见文案帧）") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      tk = mkTakeover(sent)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-a", "conn-a", ConsoleTakeover.ClientMeta(clientId = Some("client-a"), ua = Some(Ua)))
      first <- claim(routes, "client-a", "tab-a")
      e1 <- epochOf(first)
      _ <- sent.set(Vector.empty) // 只看接管那一下的出帧
      second <- claim(routes, "client-b", "tab-b")
      e2 <- epochOf(second)
      frames <- sent.get
      seatFrames = frames.filter { case (_, j) => j.hcursor.get[String]("type").toOption.contains("canvasWriterChanged") }
      _ = assertEquals(seatFrames.map(_._1).distinct.toList, List("conn-a"), "席位变更帧必须定向到被顶下的写者连接")
      _ = assertEquals(seatFrames.map(_._2.hcursor.get[Long]("epoch").toOption).toList, List(Some(e2)))
      // 没有别的帧型（尤其没有任何接管卡片/提示帧）：零用户可见文案面
      _ = assertEquals(frames.map(_._2.hcursor.get[String]("type").toOption).distinct.toList, List(Some("canvasWriterChanged")))
      // 被顶下的写者此后写穿一律 409（它必须走草稿这条路，而不是悄悄写穿）
      oldPut <- put(routes, "client-a", "tab-a", e1)
      _ = assertEquals(oldPut.status, Status.Conflict)
    yield ()
  }

  // ── 闸 C：自续位与掉线都不产生位移通知（既有零出帧语义）────────────────────

  test("C 同标签自续位/掉线路径零出帧；死端收不到席位变更帧（无接收方）") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      tk = mkTakeover(sent)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-a", "conn-a", ConsoleTakeover.ClientMeta(clientId = Some("client-a"), ua = Some(Ua)))
      r1 <- claim(routes, "client-a", "tab-a")
      e1 <- epochOf(r1)
      _ <- sent.set(Vector.empty)
      // 同标签再申请 = 自我续位（刷新/重连）：epoch 自增但**无被顶下的写者** ⇒ 零出帧
      r2 <- claim(routes, "client-a", "tab-a")
      e2 <- epochOf(r2)
      _ = assertEquals(e2, e1 + 1L)
      selfFrames <- sent.get
      _ = assertEquals(selfFrames, Vector.empty[(String, Json)], "自我续位不该通知任何人")
      // 掉线：零出帧、零判定（掉线 ≠ 让位逐字保住）
      _ <- tk.onDisconnect("conn-a")
      deadFrames <- sent.get
      _ = assertEquals(deadFrames, Vector.empty[(String, Json)])
      // 死端仍持有席位 ⇒ 他窗申请**仍需**瞬时接管，但死端零连接 ⇒ 无接收方零出帧
      r3 <- claim(routes, "client-b", "tab-b")
      _ = assertEquals(r3.status, Status.Ok)
      e3 <- epochOf(r3)
      afterDead <- sent.get
      _ = assertEquals(afterDead, Vector.empty[(String, Json)], "死端零连接 ⇒ 收不到也发不出")
      _ = assertEquals(e3, e2 + 1L)
    yield ()
  }

  // ── 闸 D：抢占是瞬时的——活性惰性腿已整体移除（同刻即得，不再等任何窗）──────

  test("D 活性惰性腿已移除：占位者消失的**同一刻**他窗即 200（不再有 120s/600s 等待）") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      tk = mkTakeover(sent)
      routes = mkRoutes(tk)
      _ <- tk.onConnect("s-ghost", "conn-ghost", ConsoleTakeover.ClientMeta(clientId = Some("ghost-client"), ua = Some(Ua)))
      first <- claim(routes, "ghost-client", "tab-ghost")
      _ = assertEquals(first.status, Status.Ok)
      e1 <- epochOf(first)
      // 占位者非正常消失（pagehide 的 best-effort release 丢了）
      _ <- tk.onDisconnect("conn-ghost")
      // **不等**任何窗：下一秒申请即 Ok（旧活性语义下这里要 409 直到 120s/600s）
      after <- claim(routes, "rival-client", "tab-rival")
      _ = assertEquals(after.status, Status.Ok, "抢占瞬时：不存在需要等待的活性判据")
      e2 <- epochOf(after)
      _ = assertEquals(e2, e1 + 1L)
      w <- put(routes, "rival-client", "tab-rival", e2)
      _ = assertEquals(w.status, Status.Ok)
      ghostPut <- put(routes, "ghost-client", "tab-ghost", e1)
      _ = assertEquals(ghostPut.status, Status.Conflict)
    yield ()
  }

  // ── 闸 E：expectedEpoch 仍是严格 compare-and-set（并发同 epoch 只一个赢家）──

  test("E 同 observed epoch 的并发申请仍只一个赢家（CAS 不变）") {
    val routes = mkRoutes(mkTakeover(Ref.unsafe(Vector.empty[(String, Json)])))
    def casClaim(tab: String): IO[Status] =
      call(
        routes,
        Method.POST,
        "/canvas-writer/claim",
        identity("browser-a", tab).deepMerge(Json.obj("expectedEpoch" -> 0.asJson))
      ).map(_.status)
    (casClaim("a"), casClaim("b")).parTupled.map { case (a, b) =>
      assertEquals(List(a, b).count(_ == Status.Ok), 1)
      assertEquals(List(a, b).count(_ == Status.Conflict), 1)
    }
  }
