package nebflow.shared

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.{Header, Method, Request, Uri}
import org.typelevel.ci.CIString

/**
 * The process-self-signed tunnel-origin mark (stage C): pins the arm-once
 * lifecycle, the brand-derived header name, and the three-state `isMarked`
 * verdict (absent / garbage / armed value) that GatewayAuth's origin gate
 * consumes. The mark matrix over full requests (remote × mark × rail) lives
 * in [[nebflow.gateway.GatewayAuthSpec]]; here the subject is the mark
 * surface itself.
 */
class TunnelOriginMarkSpec extends CatsEffectSuite:

  // arm() is idempotent and process-global: whatever this spec observes is
  // the same value GatewayAuthSpec and the bridge specs observe in this JVM.
  TunnelOriginMark.arm()

  private def req(headers: (String, String)*): Request[IO] =
    headers.foldLeft(Request[IO](Method.GET, Uri.unsafeFromString("/x"))) { (r, h) =>
      r.putHeaders(Header.Raw(CIString(h._1), h._2))
    }

  test("header name is brand-derived, matching the CredentialHeader discipline"):
    assertEquals(TunnelOriginMark.headerName, s"x-${Branding.lowerName}-tunnel-mark")

  test("arm() is idempotent: the first armed value wins, later calls never rotate it"):
    val before = TunnelOriginMark.armedValue
    TunnelOriginMark.arm()
    val after = TunnelOriginMark.armedValue
    assertEquals(before, after, "re-arming must be a no-op once armed")
    assert(after.isDefined, "the mark must be armed for the matrix consumers")

  test("the armed value is >=256 bits of hex entropy (64 hex chars)"):
    val v = TunnelOriginMark.armedValue.getOrElse(sys.error("mark failed to arm"))
    assertEquals(v.length, 64)
    assert(v.forall(c => c.isDigit || ('a' <= c && c <= 'f')), s"non-hex char in: $v")

  test("isMarked three states: absent and garbage read unmarked; only the armed value marks"):
    val markName = TunnelOriginMark.headerName
    val armed = TunnelOriginMark.armedValue.getOrElse(sys.error("mark failed to arm"))
    assertEquals(TunnelOriginMark.isMarked(req()), false, "no header at all must read unmarked")
    assertEquals(
      TunnelOriginMark.isMarked(req(markName -> "definitely-not-the-value")),
      false,
      "a garbage value must read unmarked"
    )
    assertEquals(TunnelOriginMark.isMarked(req(markName -> armed)), true, "the armed value must mark")
    // Case-insensitive name match (the wire is case-insensitive), exact value match.
    assertEquals(
      TunnelOriginMark.isMarked(req(markName.toUpperCase -> armed)),
      true,
      "the header NAME must match case-insensitively"
    )

  test("injectHeader carries the armed (name, value) pair"):
    val armed = TunnelOriginMark.armedValue.getOrElse(sys.error("mark failed to arm"))
    assertEquals(TunnelOriginMark.injectHeader, Some((TunnelOriginMark.headerName, armed)))

  test("a request whose headers carry the mark under a DIFFERENT name never marks"):
    val armed = TunnelOriginMark.armedValue.getOrElse(sys.error("mark failed to arm"))
    assertEquals(TunnelOriginMark.isMarked(req("x-other-tunnel-mark" -> armed)), false)

end TunnelOriginMarkSpec
