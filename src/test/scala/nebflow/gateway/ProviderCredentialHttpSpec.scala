package nebflow.gateway

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.*
import munit.FunSuite
import nebflow.core.secrets.ConfigCredentialStore
import nebflow.shared.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
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
      val configRef = Ref.unsafe[IO, NebflowServiceConfig](NebflowServiceConfig(llm = ServiceLlmConfig(
        providers = Map("fixture" -> ProviderConfig("http://127.0.0.1:1", key, LlmProtocol.OpenAI, List(ModelConfig("fixture")))))))
      val routes = new RestApiRoutes(tokenRef = Ref.unsafe[IO, String]("fixture-gateway-auth"), configRef = configRef,
        sharedResources = null, sessionStore = null, wsRoutes = null)
      val server = EmberServerBuilder.default[IO].withHost(ipv4"127.0.0.1").withPort(port"0")
        .withHttpWebSocketApp(wsb => routes.presenceWsRoutes(wsb).orNotFound)
      server.build.use { s =>
        def post(): IO[(Int, String)] = IO.blocking {
          val conn = URI.create(s"http://127.0.0.1:${s.address.getPort}/provider/test").toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
          conn.setRequestMethod("POST")
          conn.setRequestProperty("Authorization", "Bearer fixture-gateway-auth")
          conn.setRequestProperty("Content-Type", "application/json")
          conn.setRequestProperty("Connection", "close")
          conn.setDoOutput(true)
          try
            val out = conn.getOutputStream
            try out.write("""{"name":"fixture","model":"fixture"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            finally out.close()
            val status = conn.getResponseCode
            val input = if status >= 400 then conn.getErrorStream else conn.getInputStream
            val body = try new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally input.close()
            (status, body)
          finally conn.disconnect()
        }
        val file = (root / "secrets" / key.stripPrefix("secret:")).toNIO
        for
          _ <- IO.blocking(Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------")))
          unreadable <- post()
          _ <- IO { assertEquals(unreadable, (400, """{"error":"Credential reference is unreadable"}""")) }
          _ <- IO.blocking(Files.delete(file))
          missing <- post()
          _ <- IO { assertEquals(missing, (400, """{"error":"Credential reference is missing"}""")) }
          _ <- IO.println(s"unreadable: HTTP ${unreadable._1} ${unreadable._2}; missing: HTTP ${missing._1} ${missing._2}")
        yield ()
      }.unsafeRunSync()
    finally
      PathUtil.setDataRoot(original)
      os.remove.all(root)
  }
