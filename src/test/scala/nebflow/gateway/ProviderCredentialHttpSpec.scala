package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.*
import munit.FunSuite
import nebflow.core.secrets.ConfigCredentialStore
import nebflow.shared.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import cats.syntax.semigroupk.*
import io.circe.Json
import io.circe.syntax.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class ProviderCredentialHttpSpec extends FunSuite:

  test("provider test returns safe HTTP errors for missing and unreadable credentials") {
    val root = os.temp.dir(prefix = "provider-http-")
    val original = PathUtil.dataRoot
    PathUtil.setDataRoot(root)
    try
      val key = ConfigCredentialStore.mint(root, "synthetic-http-secret")
      val disk = Json.obj("llm" -> Json.obj("providers" -> Json.obj("fixture" -> Json.obj("apiKey" -> key.asJson))))
      os.write(root / nebflow.shared.Branding.configFileName, disk.noSpaces)
      val configRef = Ref.unsafe[IO, NebflowServiceConfig](
        NebflowServiceConfig(llm =
          ServiceLlmConfig(
            providers = Map(
              "fixture" -> ProviderConfig("http://127.0.0.1:1", key, LlmProtocol.OpenAI, List(ModelConfig("fixture")))
            )
          )
        )
      )
      val routes = new RestApiRoutes(
        tokenRef = Ref.unsafe[IO, String]("fixture-gateway-auth"),
        configRef = configRef,
        sharedResources = null,
        sessionStore = null,
        wsRoutes = null
      )
      val server = EmberServerBuilder
        .default[IO]
        .withHost(ipv4"127.0.0.1")
        .withPort(port"0")
        .withHttpWebSocketApp(wsb => (routes.routes <+> routes.presenceWsRoutes(wsb)).orNotFound)
      server.build
        .use { s =>
          def request(path: String, body: Option[String]): IO[(Int, String)] = IO.blocking {
            val conn = URI
              .create(s"http://127.0.0.1:${s.address.getPort}$path")
              .toURL
              .openConnection()
              .asInstanceOf[java.net.HttpURLConnection]
            conn.setRequestMethod(if body.isDefined then "POST" else "GET")
            conn.setRequestProperty("Authorization", "Bearer fixture-gateway-auth")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Connection", "close")
            conn.setDoOutput(body.isDefined)
            try
              body.foreach { payload =>
                val out = conn.getOutputStream
                try out.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                finally out.close()
              }
              val status = conn.getResponseCode
              val input = if status >= 400 then conn.getErrorStream else conn.getInputStream
              val responseBody =
                try new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                finally input.close()
              (status, responseBody)
            finally conn.disconnect()
          }
          def post(): IO[(Int, String)] = request("/provider/test", Some("""{"name":"fixture","model":"fixture"}"""))
          def models(): IO[(Int, String)] =
            request("/provider/models", Some("""{"name":"fixture","baseUrl":"http://127.0.0.1:1"}"""))
          def keys(): IO[(Int, String)] = request("/config/provider-keys", None)
          val file = (root / "secrets" / key.stripPrefix("secret:")).toNIO
          for
            _ <- IO.blocking(Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------")))
            unreadable <- post()
            _ <- IO { assertEquals(unreadable, (400, """{"error":"Credential reference is unreadable"}""")) }
            modelsUnreadable <- models()
            keysUnreadable <- keys()
            _ <- IO { assertEquals(modelsUnreadable, unreadable); assertEquals(keysUnreadable, unreadable) }
            _ <- IO.blocking(Files.delete(file))
            missing <- post()
            _ <- IO { assertEquals(missing, (400, """{"error":"Credential reference is missing"}""")) }
            modelsMissing <- models()
            keysMissing <- keys()
            _ <- IO { assertEquals(modelsMissing, missing); assertEquals(keysMissing, missing) }
            _ <- IO.println(
              s"unreadable: HTTP ${unreadable._1} ${unreadable._2}; missing: HTTP ${missing._1} ${missing._2}"
            )
          yield ()
          end for
        }
        .unsafeRunSync()
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(root)
    end try
  }
end ProviderCredentialHttpSpec
