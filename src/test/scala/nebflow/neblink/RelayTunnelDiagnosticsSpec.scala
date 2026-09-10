package nebflow.neblink

import io.circe.Json
import munit.FunSuite

import java.net.URI
import java.net.http.{HttpClient, HttpHeaders, HttpResponse, WebSocketHandshakeException}
import java.util.concurrent.{CompletionException, ExecutionException, TimeoutException}

/**
 * Pure-layer nails for the 2026-09-10 隧道鉴权自愈批 (report §3 U1, §4 F3/F7).
 *
 * Three layers, each independently falsifiable:
 *  1. `RelayTunnelDiagnostics.describe` — the status code must survive the JDK's
 *     wrapping chain and the log line must never be `null`. This is exactly the
 *     evidence the incident lost (702 + 22 lines with no status).
 *  2. `RelayTunnelDiagnostics.redact` — a credential must never reach the log,
 *     while the server's auth-rejection body must stay recognizable.
 *  3. `NeblinkRelayTunnel.shouldHealAuthFailure` / `statusJson` — the anti-loop
 *     bound and the F7 status surface.
 */
class RelayTunnelDiagnosticsSpec extends FunSuite:

  private def response(code: Int, bodyText: String): HttpResponse[String] =
    new HttpResponse[String]:
      def statusCode(): Int = code
      def request(): java.net.http.HttpRequest = null
      def previousResponse(): java.util.Optional[HttpResponse[String]] = java.util.Optional.empty()
      def headers(): HttpHeaders = HttpHeaders.of(java.util.Map.of(), (_, _) => true)
      def body(): String = bodyText
      def sslSession(): java.util.Optional[javax.net.ssl.SSLSession] = java.util.Optional.empty()
      def uri(): URI = URI.create("ws://127.0.0.1:1/api/device/relay-ws")
      def version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

  private def handshake(code: Int, bodyText: String): WebSocketHandshakeException =
    new WebSocketHandshakeException(response(code, bodyText))

  private val AuthBody = """{"error":"Missing or invalid token"}"""

  // ── 1. status extraction ────────────────────────────────

  test("describe: bare WebSocketHandshakeException carries the HTTP status (U1)") {
    val f = RelayTunnelDiagnostics.describe(handshake(403, AuthBody))
    assertEquals(f.statusCode, Some(403))
    assertEquals(f.bodySnippet, Some(AuthBody))
    assert(f.summary.startsWith("HTTP 403"), f.summary)
    assert(f.summary.contains("Missing or invalid token"), "the auth-rejection body must survive for attribution")
    assert(f.authRejected, "403 is an auth rejection")
  }

  test("describe: ExecutionException wrapping (CompletableFuture.get) is unwrapped") {
    val e = new ExecutionException(handshake(401, AuthBody))
    val f = RelayTunnelDiagnostics.describe(e)
    assertEquals(f.statusCode, Some(401))
    assert(f.authRejected)
    assert(f.summary.startsWith("HTTP 401"), f.summary)
  }

  test("describe: deeper CompletionException -> ExecutionException -> handshake chain is unwrapped") {
    val e = new CompletionException(new ExecutionException(handshake(502, """{"error":"bad gateway"}""")))
    val f = RelayTunnelDiagnostics.describe(e)
    assertEquals(f.statusCode, Some(502))
    assert(!f.authRejected, "5xx is a server-side rejection — never an auth rejection")
  }

  test("describe: a null-message failure still yields a non-null summary (the 22 'error: null' lines)") {
    // java.util.concurrent.TimeoutException from CompletableFuture.get(timeout)
    // has a null message — the pre-fix log printed `Relay tunnel error: null`.
    val f = RelayTunnelDiagnostics.describe(new TimeoutException())
    assertEquals(f.statusCode, None)
    assert(!f.summary.contains("null"), s"a null-message throwable must not produce a null-looking summary: ${f.summary}")
    assert(f.summary.contains("TimeoutException"), f.summary)
  }

  test("describe: 5xx status is distinguishable from 401/403 (cross-project discriminator, report §6)") {
    assertEquals(RelayTunnelDiagnostics.describe(handshake(500, "{}")).statusCode, Some(500))
    assert(RelayTunnelDiagnostics.describe(handshake(500, "{}")).summary.contains("HTTP 500"))
    assert(!RelayTunnelDiagnostics.describe(handshake(500, "{}")).authRejected)
  }

  // ── 2. body hygiene ─────────────────────────────────────

  test("redact: credentials are masked but the auth-rejection body survives") {
    assertEquals(RelayTunnelDiagnostics.redact(AuthBody), AuthBody)
    assert(!RelayTunnelDiagnostics.redact("""{"error":"bad","token":"tok-1"}""").contains("tok-1"))
    assert(!RelayTunnelDiagnostics.redact("Authorization: Bearer tok-1").contains("tok-1"))
    val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJxYS1kZXZpY2UifQ.abc123signature"
    assert(!RelayTunnelDiagnostics.redact(s"upgrade failed for $jwt").contains(jwt))
  }

  test("describe: an over-long body is truncated") {
    val huge = "x" * 5000
    val f = RelayTunnelDiagnostics.describe(handshake(403, huge))
    assert(f.bodySnippet.exists(_.endsWith("…(truncated)")), f.bodySnippet.map(_.take(40)))
    assert(f.bodySnippet.exists(_.length <= RelayTunnelDiagnostics.MaxBodyChars + 20), "bounded log line")
  }

  // ── 3. anti-loop gate ───────────────────────────────────

  test("shouldHealAuthFailure: first rejection of a streak heals; repeats are bounded by the cooldown") {
    val now = 1_000_000L
    assert(NeblinkRelayTunnel.shouldHealAuthFailure(streak = 1, now, lastHealAtMs = 0L), "first rejection heals")
    assert(
      !NeblinkRelayTunnel.shouldHealAuthFailure(streak = 2, now, lastHealAtMs = now - 1_000L),
      "a second rejection inside the cooldown must NOT re-login (no unbounded loop)"
    )
    assert(
      NeblinkRelayTunnel.shouldHealAuthFailure(streak = 7, now, lastHealAtMs = now - NeblinkRelayTunnel.HealCooldownMs),
      "after the cooldown the tunnel may retry the heal (a long-lived process still recovers)"
    )
    assert(
      !NeblinkRelayTunnel.shouldHealAuthFailure(streak = 3, now, lastHealAtMs = 0L),
      "no heal timestamp + a later streak member = never healed yet should still respect the streak rule"
    )
  }

  // ── 4. F7 status surface ────────────────────────────────

  test("statusJson: no rejection yet -> available + authRejected=false + nulls") {
    val j = NeblinkRelayTunnel.statusJson(available = true, None)
    assertEquals(j.hcursor.downField("available").as[Boolean].toOption, Some(true))
    assertEquals(j.hcursor.downField("authRejected").as[Boolean].toOption, Some(false))
    assertEquals(j.hcursor.downField("lastRejectedStatusCode").focus, Some(Json.Null))
    assertEquals(j.hcursor.downField("selfHeal").focus, Some(Json.Null))
  }

  test("statusJson: an ACTIVE 403 with a failed heal is its own state (F7)") {
    val st = NeblinkRelayTunnel.TunnelAuthStatus(403, 1700L, healAttempted = true, healSucceeded = false, active = true)
    val j = NeblinkRelayTunnel.statusJson(available = false, Some(st))
    assertEquals(j.hcursor.downField("authRejected").as[Boolean].toOption, Some(true))
    assertEquals(j.hcursor.downField("lastRejectedStatusCode").as[Int].toOption, Some(403))
    assertEquals(j.hcursor.downField("lastRejectedAt").as[Long].toOption, Some(1700L))
    assertEquals(j.hcursor.downField("selfHeal").as[String].toOption, Some("failed"))
  }

  test("statusJson: healed and connected -> historical code kept, authRejected cleared") {
    val st = NeblinkRelayTunnel.TunnelAuthStatus(401, 42L, healAttempted = true, healSucceeded = true, active = false)
    val j = NeblinkRelayTunnel.statusJson(available = true, Some(st))
    assertEquals(j.hcursor.downField("authRejected").as[Boolean].toOption, Some(false))
    assertEquals(j.hcursor.downField("lastRejectedStatusCode").as[Int].toOption, Some(401))
    assertEquals(j.hcursor.downField("selfHeal").as[String].toOption, Some("ok"))
  }

  test("statusJson: a reported-only rejection (5xx path never heals) reads selfHeal=not-attempted") {
    val st = NeblinkRelayTunnel.TunnelAuthStatus(0, 7L, healAttempted = false, healSucceeded = false, active = true)
    val j = NeblinkRelayTunnel.statusJson(available = false, Some(st))
    assertEquals(j.hcursor.downField("selfHeal").as[String].toOption, Some("not-attempted"))
    assertEquals(j.hcursor.downField("authRejected").as[Boolean].toOption, Some(true))
  }

end RelayTunnelDiagnosticsSpec
