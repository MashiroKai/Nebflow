package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.{NebflowServiceConfig, PathUtil, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization

/**
 * Canvas 单写者席位 + 拒写文案的路线级契约（2026-11-18 作者令）。
 *
 * 数据根隔离：成功写穿腿会落到 `PathUtil.dataRoot / canvas_tabs.json`——若不换根，
 * 跑一次 spec 就会改写真实 `~/.stand`（生产实例的数据根）。故本套件全程换到临时根，
 * 结束后还原。
 */
class CanvasWriterLegacyPutSpec extends CatsEffectSuite:

  private val originalRoot: os.Path = PathUtil.dataRoot
  private val isolatedRoot: os.Path = os.temp.dir(prefix = "canvas-writer-spec-")

  override def beforeAll(): Unit = PathUtil.setDataRoot(isolatedRoot)
  override def afterAll(): Unit =
    PathUtil.setDataRoot(originalRoot)
    try os.remove.all(isolatedRoot) catch case _: Throwable => ()
  test("concurrent claims with the same observed epoch have one winner") {
    val token = "canvas-writer-test-token"
    val routes = new RestApiRoutes(
      tokenRef = Ref.unsafe[IO, String](token),
      configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))),
      sharedResources = null, sessionStore = null, wsRoutes = null
    ).routes
    def claim(tab: String): IO[Status] =
      val body = Json.obj("clientId" -> "browser-a".asJson, "tabInstanceId" -> tab.asJson, "expectedEpoch" -> 0.asJson)
      val req = Request[IO](Method.POST, Uri.unsafeFromString("/canvas-writer/claim"))
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token))).withEntity(body)
      routes(req).value.map(_.getOrElse(fail("route fell through")).status)
    (claim("a"), claim("b")).parTupled.map { case (a, b) =>
      assertEquals(List(a, b).count(_ == Status.Ok), 1)
      assertEquals(List(a, b).count(_ == Status.Conflict), 1)
    }
  }
  test("a single browser instance never triggers a console takeover prompt (zero false positives)") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      takeover = new ConsoleTakeover((id, frame) => sent.update(_ :+ (id -> frame)), nebflow.shared.NebflowLogger.forName("test"))
      meta = ConsoleTakeover.ClientMeta(
        clientId = Some("client-a"),
        ua = Some("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Safari/604.1"),
        ip = Some("1.2.3.4")
      )
      _ <- takeover.onConnect("session-a", "conn-a", meta)
      // 同一实例的第二条连接（刷新 / 第二标签 / 静默重座）+ 在带声明腿：恒同 holder。
      _ <- takeover.onConnect("session-a", "conn-b", meta)
      _ <- takeover.onIdentifyDeclared("session-a", "conn-b", Some(meta))
      frames <- sent.get
      _ = assertEquals(frames, Vector.empty[(String, Json)])
      owner <- takeover.owner
      _ = assertEquals(owner, Some("c:client-a"))
    yield ()
  }

  test("a second tab cannot claim while a writer is held and its PUT stays rejected") {
    val token = "canvas-writer-test-token"
    val routes = new RestApiRoutes(
      tokenRef = Ref.unsafe[IO, String](token),
      configRef = Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null
    ).routes
    def call(method: Method, path: String, body: Json): IO[Response[IO]] =
      val req = Request[IO](method, Uri.unsafeFromString(path))
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
        .withEntity(body)
      routes(req).value.map(_.getOrElse(fail(s"route fell through: $path")))
    def identity(tab: String): Json = Json.obj("clientId" -> "browser-a".asJson, "tabInstanceId" -> tab.asJson)
    for
      first <- call(Method.POST, "/canvas-writer/claim", identity("tab-a"))
      _ = assertEquals(first.status, Status.Ok)
      firstBody <- first.as[Json]
      _ = assertEquals(firstBody.hcursor.get[String]("role").toOption, Some("writer"))
      firstEpoch = firstBody.hcursor.get[Long]("epoch").toOption.get
      _ = assertEquals(firstEpoch, 1L)
      // 真多开：第二个窗口的席位申请被拒（非抢占），明确原因 + 真实保存失败文案。
      second <- call(Method.POST, "/canvas-writer/claim", identity("tab-b"))
      _ = assertEquals(second.status, Status.Conflict)
      secondBody <- second.as[Json]
      _ = assertEquals(secondBody.hcursor.get[String]("error").toOption, Some("writer_already_held"))
      _ = assertEquals(secondBody.hcursor.get[String]("message").toOption, Some("保存失败，本次修改未保存到服务器。"))
      _ = assertEquals(secondBody.hcursor.get[String]("role").toOption, Some("reader"))
      // 单实例正常写：持位写者照常写穿（并把权威存档落到隔离根）。
      writer <- call(Method.PUT, "/canvas-tabs", identity("tab-a").deepMerge(
        Json.obj("v" -> 2.asJson, "tabs" -> Json.arr(), "epoch" -> firstEpoch.asJson)
      ))
      _ = assertEquals(writer.status, Status.Ok)
      // 只读可看：GET 照常返回权威存档。
      stored <- call(Method.GET, "/canvas-tabs", Json.obj())
      _ = assertEquals(stored.status, Status.Ok)
      // 服务端拒写：第二窗口的 PUT 落栅栏外。
      readerPut <- call(Method.PUT, "/canvas-tabs", identity("tab-b").deepMerge(
        Json.obj("v" -> 2.asJson, "tabs" -> Json.arr(), "epoch" -> firstEpoch.asJson)
      ))
      _ = assertEquals(readerPut.status, Status.Conflict)
      readerBody <- readerPut.as[Json]
      _ = assertEquals(readerBody.hcursor.get[String]("error").toOption, Some("stale_writer_epoch"))
      _ = assertEquals(readerBody.hcursor.get[String]("message").toOption, Some("保存失败，本次修改未保存到服务器。"))
    yield ()
  }

  test("Canvas owner change uses console takeover notification") {
    for
      sent <- Ref.of[IO, Vector[(String, Json)]](Vector.empty)
      takeover = new ConsoleTakeover((id, frame) => sent.update(_ :+ (id -> frame)), nebflow.shared.NebflowLogger.forName("test"))
      _ <- takeover.onConnect("session-a", "conn-a", Some("client-a"))
      _ <- takeover.claimCanvas("principal-b", "client-b", "tab-b")
      frames <- sent.get
      _ = assert(frames.exists { case (id, json) => id == "conn-a" && json.hcursor.get[String]("type").toOption.contains("takeover") })
      owner <- takeover.owner
      _ = assertEquals(owner, Some("c:client-b"))
    yield ()
  }

  test("authenticated legacy canvas PUT without writer identity is rejected") {
    val token = "canvas-writer-test-token"
    val routes = new RestApiRoutes(
      tokenRef = Ref.unsafe[IO, String](token),
      configRef = Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null
    ).routes
    val body = Json.obj("v" -> 2.asJson, "tabs" -> Json.arr())
    val request = Request[IO](Method.PUT, Uri.unsafeFromString("/canvas-tabs"))
      .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(body)
    routes(request).value.map(_.getOrElse(fail("route fell through"))).flatMap { response =>
      IO(assertEquals(response.status, Status.Conflict)) *> response.as[Json].map { json =>
        assertEquals(json.hcursor.get[String]("error").toOption, Some("canvas_read_only"))
        assertEquals(json.hcursor.get[String]("message").toOption, Some("保存失败，本次修改未保存到服务器。"))
      }
    }
  }

  test("concurrent claims for the empty writer slot leave exactly one writer") {
    val token = "canvas-writer-test-token"
    val routes = new RestApiRoutes(
      tokenRef = Ref.unsafe[IO, String](token),
      configRef = Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map.empty))
      ),
      sharedResources = null,
      sessionStore = null,
      wsRoutes = null
    ).routes
    def call(method: Method, path: String, body: Json): IO[Response[IO]] =
      val req = Request[IO](method, Uri.unsafeFromString(path))
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
        .withEntity(body)
      routes(req).value.map(_.getOrElse(fail(s"route fell through: $path")))
    def identity(tab: String): Json = Json.obj("clientId" -> "browser-a".asJson, "tabInstanceId" -> tab.asJson)
    def claim(tab: String): IO[Response[IO]] = call(Method.POST, "/canvas-writer/claim", identity(tab))
    for
      pair <- (claim("tab-a"), claim("tab-b")).parTupled
      statuses = List(pair._1.status, pair._2.status)
      _ = assertEquals(statuses.count(_ == Status.Ok), 1)
      _ = assertEquals(statuses.count(_ == Status.Conflict), 1)
      winner <- if pair._1.status == Status.Ok then IO.pure(pair._1) else IO.pure(pair._2)
      winnerBody <- winner.as[Json]
      winnerEpoch = winnerBody.hcursor.get[Long]("epoch").toOption.get
      loserTab = if pair._1.status == Status.Ok then "tab-b" else "tab-a"
      loserPut <- call(Method.PUT, "/canvas-tabs", identity(loserTab).deepMerge(
        Json.obj("v" -> 2.asJson, "tabs" -> Json.arr(), "epoch" -> winnerEpoch.asJson)
      ))
      _ = assertEquals(loserPut.status, Status.Conflict)
      winnerTab = if pair._1.status == Status.Ok then "tab-a" else "tab-b"
      winnerPut <- call(Method.PUT, "/canvas-tabs", identity(winnerTab).deepMerge(
        Json.obj("v" -> 2.asJson, "tabs" -> Json.arr(), "epoch" -> winnerEpoch.asJson)
      ))
      _ = assertEquals(winnerPut.status, Status.Ok)
    yield ()
  }
