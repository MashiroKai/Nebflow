package nebflow.cli

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import munit.FunSuite

import java.net.{InetSocketAddress, ServerSocket}
import java.util.concurrent.Executors

/** Single-instance guard classifier (#guard, 2026-08-27 hard requirement).
  * The classifier decides between boot / focus-existing / fail-loud — these
  * tests pin each branch against a real socket / HTTP occupant:
  *  - free port                     -> PortFree
  *  - nebflow-style /api/health     -> NebflowInstance (product marker)
  *  - legacy health (version only)  -> NebflowInstance (older build compat —
  *    a pre-product-field instance must still be FOCUSED, not fought)
  *  - foreign JSON / silent socket  -> ForeignOccupant
  */
class SingleInstanceGuardSpec extends FunSuite:

  private def freePort(): Int =
    val ss = new ServerSocket()
    ss.bind(new InetSocketAddress("127.0.0.1", 0))
    val p = ss.getLocalPort
    ss.close()
    p

  private def withHttpServer(body: String)(f: Int => Unit): Unit =
    val port  = freePort()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    server.createContext(
      "/api/health",
      (ex: com.sun.net.httpserver.HttpExchange) => {
        val bytes = body.getBytes("UTF-8")
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length)
        ex.getResponseBody.write(bytes)
        ex.close()
      }
    )
    server.start()
    try f(port)
    finally server.stop(0)

  private def withSilentSocket(f: Int => Unit): Unit =
    val port = freePort()
    val ss   = new ServerSocket()
    ss.bind(new InetSocketAddress("127.0.0.1", port), 1)
    try f(port)
    finally ss.close()

  test("free port classifies PortFree") {
    val p = freePort()
    val st = SingleInstanceGuard.checkPort("127.0.0.1", p).unsafeRunSync()
    assertEquals(st, SingleInstanceGuard.PortFree)
  }

  test("nebflow health (product marker) classifies NebflowInstance") {
    withHttpServer("""{"product":"nebflow","status":"ok","version":"1.4.1"}""") { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p).unsafeRunSync()
      st match
        case SingleInstanceGuard.NebflowInstance(url) => assertEquals(url, s"http://localhost:$p")
        case other                                     => fail(s"expected NebflowInstance, got $other")
    }
  }

  test("legacy health (version field only, pre-product build) still classifies NebflowInstance") {
    withHttpServer("""{"status":"ok","version":"1.4.0-beta.48","providers":{}}""") { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.NebflowInstance])
    }
  }

  test("foreign JSON health (no version/product) classifies ForeignOccupant") {
    withHttpServer("""{"status":"fine","service":"something-else"}""") { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.ForeignOccupant])
    }
  }

  test("silent socket (no HTTP) classifies ForeignOccupant") {
    withSilentSocket { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.ForeignOccupant])
    }
  }

  test("wildcard probe must NOT pass over a specific-address listener (SO_REUSEADDR hole)") {
    // E2E-verified bug: JDK ServerSocket defaults SO_REUSEADDR=true on macOS;
    // a 0.0.0.0 probe bind then succeeds over a live 127.0.0.1 listener that
    // also set reuse (python http.server, and any rebinding server) — the
    // guard declared "free" and the gateway half-booted beside the occupant.
    // The probe must bind strictly.
    withSilentSocket { p =>
      val st = SingleInstanceGuard.checkPort("0.0.0.0", p).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.ForeignOccupant])
    }
  }

  test("health JSON carries the product marker") {
    // Contract side: /api/health must include "product":"nebflow" so the
    // guard's primary identification marker actually exists (RestApiRoutes).
    // Pinned here textually to survive refactorings of the route; the route
    // itself is exercised E2E.
    val route = scala.io.Source.fromFile(
      java.nio.file.Path.of("src/main/scala/nebflow/gateway/RestApiRoutes.scala").toFile
    ).mkString
    assert(route.contains("\"product\" -> \"nebflow\""), "health product marker missing")
  }

end SingleInstanceGuardSpec
