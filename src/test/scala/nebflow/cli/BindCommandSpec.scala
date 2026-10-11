package nebflow.cli

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

/**
 * `nebflow bind confirm` (phase 3c plan §8 component ①) — the testable
 * faces:
 *
 *  - registry entry + classification: the command exists and is NEVER
 *    offline-classified (the minting object is the live gateway and its
 *    relay tunnel, same shape as `token print`);
 *  - the pure render matrix: the three gateway `kind` branches (pending /
 *    offline / send_failed) and the malformed guards (missing code, non-6
 *    -digit code, unknown kind).
 *
 * Deliberately NOT run here: the ONLINE half (GatewayClient.create probes
 * the real gateway port); the gateway face is covered by the route-level
 * store/frame specs.
 */
class BindCommandSpec extends FunSuite:

  // ── A. registry + classification ────────────────────────────────────────

  test("bind is registered with confirm as its only subcommand"):
    assertEquals(CommandRegistry.get("bind").map(_.name), Some("bind"))
    assertEquals(CommandRegistry.get("bind").map(_.subcommands.map(_.name)), Some(List("confirm")))

  test("bind confirm is gateway-required (never offline-classified)"):
    assertEquals(CliRouter.isOffline("bind", "confirm"), false, "the live gateway + relay tunnel is the minting object")

  // ── B. the render matrix (pure) ─────────────────────────────────────────

  private def body(kind: String, fields: (String, Json)*): Json =
    Json.obj(("kind" -> Json.fromString(kind)) +: fields*)

  test("kind=pending: the code is printed as the single payload, with validity + invalidation note"):
    val r = BindConfirm.render(body("pending", "code" -> Json.fromString("012345"), "expiresInSec" -> Json.fromLong(900L)))
    r match
      case CliResult.Text(lines) =>
        assert(lines.contains("012345"), "the code itself is the human payload")
        assert(lines.exists(_.contains("900s")), "the validity window rides along")
        assert(lines.exists(_.toLowerCase.contains("invalidates")), "a re-run invalidates the printed code")
        // Zero-echo discipline: nothing else secret-like appears.
        assert(lines.forall(!_.toLowerCase.contains("token")))
      case other => fail(s"expected Text, got $other")

  test("kind=pending: the copy names the delivery boundary instead of promising acceptance"):
    // Review round 3: between the guard landing and the phase-5 staging face,
    // a printed code can never match — the copy must say so, not promise the
    // portal will take it.
    val r = BindConfirm.render(body("pending", "code" -> Json.fromString("012345"), "expiresInSec" -> Json.fromLong(900L)))
    r match
      case CliResult.Text(lines) =>
        val joined = lines.mkString("\n")
        assert(joined.contains("confirmation-staging face"), "the server-side dependency is named")
        assert(joined.contains("mismatch"), "the observable failure mode at the portal is named")
        assert(joined.contains("grace-period path"), "the working fallback (grace arm) is named")
        assert(joined.contains("no code can be accepted"), "the window state is stated, not glossed")
      case other => fail(s"expected Text, got $other")

  test("kind=pending with a missing or malformed code => error (never a guessed success)"):
    assert(BindConfirm.render(body("pending")).isInstanceOf[CliResult.Error])
    assert(
      BindConfirm
        .render(body("pending", "code" -> Json.fromString("12345"), "expiresInSec" -> Json.fromLong(900L)))
        .isInstanceOf[CliResult.Error],
      "5 digits is malformed"
    )
    assert(
      BindConfirm
        .render(body("pending", "code" -> Json.fromString("12a456"), "expiresInSec" -> Json.fromLong(900L)))
        .isInstanceOf[CliResult.Error],
      "non-digit content is malformed"
    )

  test("kind=offline: routes the operator to the portal (the 72h grace branch); no code anywhere"):
    val r = BindConfirm.render(body("offline", "reason" -> Json.fromString("no live relay tunnel to the NebLink server")))
    r match
      case CliResult.Text(lines) =>
        val joined = lines.mkString("\n")
        assert(joined.contains("portal"), "the operator must be sent to the portal")
        assert(joined.contains("72h"), "the grace period is named")
        assert(joined.contains("no code was minted"), "the operator must know no code exists")
      case other => fail(s"expected Text, got $other")

  test("kind=send_failed: error with the reason — nothing became pending"):
    val r = BindConfirm.render(body("send_failed", "reason" -> Json.fromString("write failed")))
    assert(r.isInstanceOf[CliResult.Error])
    assert(r.toString.contains("write failed"))

  test("unknown or missing kind => error"):
    assert(BindConfirm.render(body("surprise")).isInstanceOf[CliResult.Error])
    assert(BindConfirm.render(parse("{}").getOrElse(Json.Null)).isInstanceOf[CliResult.Error])

end BindCommandSpec
