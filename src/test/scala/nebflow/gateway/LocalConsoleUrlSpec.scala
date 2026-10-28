package nebflow.gateway

import com.sun.net.httpserver.HttpServer
import munit.FunSuite
import java.net.InetSocketAddress

class LocalConsoleUrlSpec extends FunSuite:

  private def withServer(status: Int, body: String)(f: (String, () => String) => Unit): Unit =
    var received = ""
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/api/auth/console-ticket",
      ex =>
        received = s"${ex.getRequestMethod} ${ex.getRequestURI} ${ex.getRequestHeaders.getFirst("Authorization")}"
        val bytes = body.getBytes("UTF-8")
        ex.sendResponseHeaders(status, bytes.length)
        ex.getResponseBody.write(bytes)
        ex.close()
    )
    server.start()
    try f(s"http://127.0.0.1:${server.getAddress.getPort}", () => received)
    finally server.stop(0)

  end withServer

  test("local session uses a POST Bearer header and only a one-time ticket in its URL") {
    withServer(200, """{"ticket":"one/time ticket","expiresInSec":120}""") { (base, received) =>
      assertEquals(LocalConsoleUrl.issue(base, "file-secret"), Some(s"$base/?ticket=one%2Ftime+ticket"))
      assertEquals(received(), "POST /api/auth/console-ticket Bearer file-secret")
    }
  }

  test("failed or malformed mint leaves the caller's local sign-in fallback") {
    for (status, body) <- List((403, "{}"), (200, "{}"), (200, "not JSON")) do
      withServer(status, body) { (base, _) => assertEquals(LocalConsoleUrl.issue(base, "secret"), None) }
  }

  test("redirects are refused, without forwarding the file credential") {
    withServer(302, "redirect") { (base, _) => assertEquals(LocalConsoleUrl.issue(base, "secret"), None) }
  }

  test("remote URLs and embedded credentials cannot receive the file credential") {
    for url <- List(
        "https://mystand.space",
        "http://example.com",
        "http://user@localhost:1234",
        "http://localhost:1234/?token=x"
      )
    do intercept[IllegalArgumentException](LocalConsoleUrl.issue(url, "secret"))
  }
end LocalConsoleUrlSpec
