package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import nebflow.shared.{NebflowServiceConfig, ServiceLlmConfig}
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization

class CanvasWriterLegacyPutSpec extends CatsEffectSuite:
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
      }
    }
  }

  test("two tabs of one browser compete and a reader PUT is rejected") {
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
      second <- call(Method.POST, "/canvas-writer/claim", identity("tab-b"))
      _ = assertEquals(second.status, Status.Ok)
      secondBody <- second.as[Json]
      secondEpoch = secondBody.hcursor.get[Long]("epoch").toOption.get
      _ = assertEquals(secondBody.hcursor.get[String]("role").toOption, Some("writer"))
      _ = assertEquals(secondEpoch, firstEpoch + 1L)
      reader <- call(Method.PUT, "/canvas-tabs", identity("tab-a").deepMerge(
        Json.obj("v" -> 2.asJson, "tabs" -> Json.arr(), "epoch" -> firstEpoch.asJson)
      ))
      _ = assertEquals(reader.status, Status.Conflict)
      rejection <- reader.as[Json]
      _ = assertEquals(rejection.hcursor.get[String]("error").toOption, Some("stale_writer_epoch"))
      writer <- call(Method.PUT, "/canvas-tabs", identity("tab-b").deepMerge(
        Json.obj("v" -> 2.asJson, "tabs" -> Json.arr(), "epoch" -> secondEpoch.asJson)
      ))
      _ = assertEquals(writer.status, Status.Ok)
      stored <- call(Method.GET, "/canvas-tabs", Json.obj())
      _ = assertEquals(stored.status, Status.Ok)
      storedBody <- stored.as[Json]
      _ = assertEquals(storedBody.hcursor.get[Int]("v").toOption, Some(2))
      third <- call(Method.POST, "/canvas-writer/claim", identity("tab-a"))
      thirdBody <- third.as[Json]
      _ = assertEquals(thirdBody.hcursor.get[Long]("epoch").toOption, Some(secondEpoch + 1L))
    yield ()
  }
