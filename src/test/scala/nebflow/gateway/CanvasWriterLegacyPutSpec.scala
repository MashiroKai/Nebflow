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
      firstEpoch = firstBody.hcursor.get[Long]("epoch").toOption.get
      second <- call(Method.POST, "/canvas-writer/claim", identity("tab-b"))
      _ = assertEquals(second.status, Status.Ok)
      secondBody <- second.as[Json]
      secondEpoch = secondBody.hcursor.get[Long]("epoch").toOption.get
      _ = assert(secondEpoch > firstEpoch)
      reader <- call(Method.PUT, "/canvas-tabs", identity("tab-a").deepMerge(
        Json.obj("v" -> 2.asJson, "tabs" -> Json.arr(), "epoch" -> firstEpoch.asJson)
      ))
      _ = assertEquals(reader.status, Status.Conflict)
      rejection <- reader.as[Json]
      _ = assert(Set("canvas_read_only", "stale_writer_epoch").contains(rejection.hcursor.get[String]("error").getOrElse("")))
    yield ()
  }
