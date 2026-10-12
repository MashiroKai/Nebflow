package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Status, Uri}

/**
 * Phase 4 LAN leg pins for `GET /api/nf-reach` (plan §4.2): the portal page's
 * same-machine detector depends on the answer being browser-readable
 * cross-origin, so the CORS openness and the brand self-evidence header are
 * the contract here — a regression that drops either silently breaks the
 * detector into "never jump" without failing any auth test.
 */
class NfReachRoutesSpec extends CatsEffectSuite:

  private val routes = NfFileRoutes.nfReachRoutes.orNotFound

  test("GET /api/nf-reach answers 204 with the brand header and open CORS, zero credentials") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString("/api/nf-reach"))
    routes.run(req).map { resp =>
      assertEquals(resp.status, Status.NoContent)
      val names = resp.headers.headers.map(_.name.toString.toLowerCase).toSet
      assert(names(s"x-${nebflow.shared.Branding.lowerName}-gateway"), "brand self-evidence header missing")
      assert(names("access-control-allow-origin"), "CORS allow-origin missing")
      assert(
        resp.headers.headers.exists(h =>
          h.name.toString.equalsIgnoreCase("Access-Control-Allow-Origin") && h.value == "*"
        ),
        "allow-origin must be *"
      )
      assert(
        resp.headers.headers.exists(h =>
          h.name.toString.equalsIgnoreCase("Access-Control-Expose-Headers") &&
            h.value == s"x-${nebflow.shared.Branding.lowerName}-gateway"
        ),
        "the brand header must be EXPOSED (an unexposed header reads null cross-origin)"
      )
    }
  }

  test("OPTIONS /api/nf-reach answers the preflight with the PNA header") {
    val req = Request[IO](Method.OPTIONS, Uri.unsafeFromString("/api/nf-reach"))
    routes.run(req).map { resp =>
      assertEquals(resp.status, Status.NoContent)
      assert(
        resp.headers.headers.exists(h =>
          h.name.toString.equalsIgnoreCase("Access-Control-Allow-Private-Network") && h.value == "true"
        ),
        "PNA preflight header missing"
      )
    }
  }

  test("the probe carries no state: two calls answer identically") {
    val req = Request[IO](Method.GET, Uri.unsafeFromString("/api/nf-reach"))
    for
      a <- routes.run(req)
      b <- routes.run(req)
    yield assertEquals(a.headers.toString, b.headers.toString)
  }
end NfReachRoutesSpec
