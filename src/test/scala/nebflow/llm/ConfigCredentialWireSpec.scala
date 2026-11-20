package nebflow.llm

import cats.effect.{IO, Ref}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.FunSuite
import nebflow.core.secrets.ConfigCredentialStore
import nebflow.shared.*
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class ConfigCredentialWireSpec extends FunSuite:

  test("configured reference reaches the real HTTP request and failures stay readable") {
    val root = os.temp.dir(prefix = "credential-wire-")
    val previous = PathUtil.dataRoot
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val observed = new AtomicReference[String]("")
    val secret = "synthetic-credential-wire-fixture"
    def digest(s: String): String = MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString
    server.createContext(
      "/chat/completions",
      (ex: HttpExchange) =>
        val header = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
        observed.set(digest(header))
        val _ = ex.getRequestBody.readAllBytes()
        val response =
          """{"id":"c","object":"chat.completion","model":"fixture","choices":[{"index":0,"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
            .getBytes(StandardCharsets.UTF_8)
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, response.length.toLong)
        ex.getResponseBody.write(response)
        ex.close()
    )
    server.start()
    try
      PathUtil.setDataRoot(root)
      val ref = ConfigCredentialStore.mint(root, secret)
      val provider = ProviderConfig(
        s"http://127.0.0.1:${server.getAddress.getPort}/",
        ref,
        LlmProtocol.OpenAI,
        List(ModelConfig("fixture"))
      )
      val config = NebflowServiceConfig(llm = ServiceLlmConfig(providers = Map("fixture" -> provider)))
      val run = Dispatcher.parallel[IO].use { dispatcher =>
        val client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()
        val backend = sttp.client4.httpclient.fs2.HttpClientFs2Backend.usingClient[IO](client, dispatcher)
        for
          configRef <- Ref.of[IO, NebflowServiceConfig](config)
          adapter <- new ProviderRegistry(configRef, backend).getAdapter("fixture")
          result <- adapter.sendMessage(SendMessageParams(List(Message(MessageRole.User, Left("hello"))), "fixture"))
        yield result
      }
      run.unsafeRunSync()
      assertEquals(observed.get(), digest("Bearer " + secret))
      println(s"stub Authorization: *** sha256=${observed.get()} match=true response=200")
      val missing = "secret:config-" + "0" * 32
      val missingError = intercept[IllegalStateException] {
        ConfigCredentialStore.resolveManaged(root, missing, "wire-test")
      }
      assertEquals(missingError.getMessage, "Credential reference is missing")
      val file = (root / "secrets" / ref.stripPrefix("secret:")).toNIO
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
      val denied = intercept[IllegalStateException] {
        ConfigCredentialStore.resolveManaged(root, ref, "wire-test")
      }
      assertEquals(denied.getMessage, "Credential reference is unreadable")
      println(s"missing: ${missingError.getMessage}; unreadable: ${denied.getMessage}")
    finally
      server.stop(0)
      PathUtil.setDataRoot(previous)
      os.remove.all(root)
    end try
  }
end ConfigCredentialWireSpec
