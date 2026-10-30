package nebflow.cli

import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import munit.FunSuite

import java.net.{InetSocketAddress, ServerSocket}
import java.util.concurrent.Executors

/**
 * Single-instance guard classifier (#guard, 2026-08-27 hard requirement).
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
    val port = freePort()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    val executor = Executors.newCachedThreadPool()
    server.setExecutor(executor)
    server.createContext(
      "/api/health",
      (ex: com.sun.net.httpserver.HttpExchange) =>
        val bytes = body.getBytes("UTF-8")
        ex.getResponseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.length)
        ex.getResponseBody.write(bytes)
        ex.close()
    )
    server.start()
    try f(port)
    finally
      server.stop(0)
      executor.shutdownNow()

  end withHttpServer

  private def withSilentSocket(f: Int => Unit): Unit =
    val port = freePort()
    val ss = new ServerSocket()
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
        case other => fail(s"expected NebflowInstance, got $other")
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

  test("stand health is recognized after rename") {
    withHttpServer("""{"product":"stand","status":"ok","version":"2026.10.18"}""") { p =>
      assert(
        SingleInstanceGuard
          .checkPort("127.0.0.1", p)
          .unsafeRunSync()
          .isInstanceOf[SingleInstanceGuard.NebflowInstance]
      )
    }
  }

  test("unrelated product/version fields must not hijack startup focus") {
    for body <- List(
        """{"version":"1.0"}""",
        """<html>"product" and "version"</html>"""
      )
    do
      withHttpServer(body) { p =>
        assert(
          SingleInstanceGuard
            .checkPort("127.0.0.1", p)
            .unsafeRunSync()
            .isInstanceOf[SingleInstanceGuard.ForeignOccupant]
        )
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

  // ============================================================
  // R1 (2026-08-30): TIME_WAIT is NOT a foreign occupant.
  // 20260830_restart-script-stability.md — a killed instance leaves its
  // closed connections in kernel TIME_WAIT (macOS 2×MSL=30s); the old strict
  // bind probe reported "port held by another program" and the restart died.
  // Fix: connect probe disambiguates — refused → TW only → drain & boot.
  // ============================================================

  /**
   * Build a real TIME_WAIT socket on an ephemeral port with NO listener:
   * server accepts, closes its side FIRST (server side → TIME_WAIT on the
   * local port), then the listener itself is closed — exactly the state a
   * kill leaves behind (TW sockets, nothing listening).
   */
  private def withTimeWaitSocket(f: Int => Unit): Unit =
    val ss = new ServerSocket()
    ss.bind(new InetSocketAddress("127.0.0.1", 0), 1)
    val p = ss.getLocalPort
    val client = new java.net.Socket("127.0.0.1", p)
    val accepted = ss.accept()
    accepted.close() // server-side close first → this 4-tuple's TIME_WAIT lives on port p
    client.close()
    ss.close() // listener gone → only TIME_WAIT remains
    try f(p)
    finally () // nothing to close — both sockets are gone; TW expires on its own

  test("R1: TIME_WAIT sockets classify as draining (probeOnce Left), not ForeignOccupant"):
    withTimeWaitSocket { p =>
      val r = SingleInstanceGuard.probeOnce("127.0.0.1", p)
      assert(r.isLeft, s"TIME_WAIT must classify Left(draining), got $r")
    }

  test("R1: checkPort boots through TIME_WAIT (drainWaitMs=0 fail-open → PortFree)"):
    withTimeWaitSocket { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p, drainWaitMs = 0).unsafeRunSync()
      assertEquals(st, SingleInstanceGuard.PortFree, "kill-and-restart must boot, not refuse")
    }

  test("R1: checkPort waits out TIME_WAIT and classifies PortFree once the bind frees"):
    withTimeWaitSocket { p =>
      // drainWaitMs slightly above one poll tick: the loop re-probes and
      // either frees mid-loop (TW expired) or fails open at the ceiling —
      // both paths end PortFree; the contract is "never ForeignOccupant".
      val start = System.currentTimeMillis()
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p, drainWaitMs = 1200).unsafeRunSync()
      val elapsed = System.currentTimeMillis() - start
      assertEquals(st, SingleInstanceGuard.PortFree)
      // DrainPollMs = 1s — the loop must have polled at least once before
      // failing open at the 1200ms ceiling (or the TW expired mid-loop and
      // the strict bind freed; either way the wait actually happened).
      assert(elapsed >= 900, s"must actually poll at least once (elapsed=$elapsed)")
    }

  test("R1 RED LINE: a live listener is NEVER drained past — real second instance still refused fast"):
    // The single-instance guarantee: a real occupant must classify instantly
    // (no drain wait, no fail-open) even though TIME_WAIT handling exists.
    withSilentSocket { p =>
      val start = System.currentTimeMillis()
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p, drainWaitMs = 60_000).unsafeRunSync()
      val elapsed = System.currentTimeMillis() - start
      assert(st.isInstanceOf[SingleInstanceGuard.ForeignOccupant], s"live occupant must be refused, got $st")
      assert(elapsed < 5_000, s"live occupant must classify instantly, not drain-wait (elapsed=$elapsed)")
    }

  test("R1 RED LINE: a live nebflow instance is focused, not drained past"):
    withHttpServer("""{"product":"nebflow","status":"ok","version":"1.4.1"}""") { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p, drainWaitMs = 60_000).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.NebflowInstance], s"got $st")
    }

  // ── 双实例并存批 2026-10-30：health 探测的品牌集 ────────────────────
  // 判据 = **路径归属**（命令行里的品牌数据根），不是品牌词（理由见 probeOnce 注释——
  // rename 兼容：改前构建的本产品实例健康面仍写 `nebflow`，必须照旧 focus）。因此
  // 带品牌词的 health 面：能读出命令行品牌根证据才升级 OtherInstance；否则按自家/兼容
  // 命中即 NebflowInstance。品牌词本身只是「读数 + 拒绝文案」的主键，也决定「认不认识」。

  test("health probe accepts both command brands as known products (stand + nebflow)") {
    // 「认不认识」这条主键钉在这：两个已知品牌都进 known 集，异品牌字面才算出来。
    // （路径证据是另一层，见 probeOnce 注释 + StaleProcessGuardSpec 的归属判据用例。）
    assertEquals(SingleInstanceGuard.OtherBrands, Set("nebflow"), "已知对家品牌集（本批钉）")
    assertEquals(SingleInstanceGuard.OwnBrand, "stand")
  }

  test("health probe classifies same-brand and legacy-health products as NebflowInstance (focusable)") {
    withHttpServer("""{"product":"stand","status":"ok","version":"2026.10.26"}""") { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p, drainWaitMs = 0).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.NebflowInstance], s"same-brand stays focusable, got $st")
    }
    // 无路径证据的 nebflow-品牌词：rename 前的本产品实例 —— 兼容，照旧 focus。
    withHttpServer("""{"product":"nebflow","status":"ok","version":"1.4.1"}""") { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p, drainWaitMs = 0).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.NebflowInstance], s"rename-boundary compat: focus, not refuse, got $st")
    }
    // 无 product 字段的 legacy 形态（老构建）：也 focus。
    withHttpServer("""{"status":"ok","version":"1.4.0-beta.48","providers":{}}""") { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p, drainWaitMs = 0).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.NebflowInstance], s"legacy shape stays focusable, got $st")
    }
  }

  test("foreign health whose product is an unrelated product name classifies ForeignOccupant") {
    withHttpServer("""{"product":"other","status":"ok","version":"1.0"}""") { p =>
      val st = SingleInstanceGuard.checkPort("127.0.0.1", p, drainWaitMs = 0).unsafeRunSync()
      assert(st.isInstanceOf[SingleInstanceGuard.ForeignOccupant], s"got $st")
    }
  }

  test("health JSON carries the product marker") {
    // Contract side: /api/health must include "product":<lowerName> so the
    // guard's primary identification marker actually exists. The health
    // payload's single source is HealthPayload.build (hotupdate 批 2 G3
    // 单一来源化, nebflow/core/hotrestart/HealthProbe.scala) — the route
    // (RestApiRoutes) delegates to it and no longer carries the literal.
    // 重启窗热修批 2026-10-18: the marker is brand-DERIVED
    // (`Branding.lowerName`, i.e. "stand" today; the bare "nebflow" literal
    // died with the stand rename) — the guard dual-accepts legacy spellings
    // (Set("nebflow", "stand", Branding.lowerName)), so old + new instances
    // still identify each other across the rename boundary.
    // Pinned here textually against that single source to survive
    // refactorings; the route itself is exercised E2E.
    // 双实例并存批 2026-10-30：健康面 `product` 的识别集与拒绝文案的对家品牌名同源于此
    // (`Branding.lowerName` + `OtherBrands`) —— 这两条是本 spec 的重钉面，防重构改丢。
    val source = scala.io.Source
      .fromFile(
        java.nio.file.Path.of("src/main/scala/nebflow/core/hotrestart/HealthProbe.scala").toFile
      )
      .mkString
    assert(
      source.contains("\"product\" -> Branding.lowerName.asJson"),
      "health product marker (brand-derived) missing"
    )
  }

end SingleInstanceGuardSpec
