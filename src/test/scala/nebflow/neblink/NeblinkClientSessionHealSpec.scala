package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import munit.FunSuite

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** F2 of the 2026-09-10 friend-search incident: API-level session self-heal.
  *
  * Failure being nailed: the server's one-live-session-per-(device, network)
  * policy kicks the current session on every fresh login/enrollment on this
  * device; the kicked session then 403s "Missing or invalid token" on every
  * withSession call with NO recovery path (the discover() heartbeat re-login
  * loop only runs on the discovery-held client) — search folded to a 502 and
  * lists to silent empty states until a gateway restart.
  *
  * Stub state machine mirrors that incident exactly: the client HAS a session
  * (startup login), the server kicks it (kick flag), the next API call 403s
  * with the auth-rejection shape, and a fresh login (silent re-login) clears
  * the kick — the heal either restores the request or surfaces the error.
  *
  * Layers, mirroring NeblinkClientReloginSpec:
  *  - pure gate quadrants (`NeblinkClient.sessionRecoverable`) — the trigger
  *    must be NARROW: business 403s ("not_blocker") must never trigger a
  *    re-login, because a re-login kicks our own previous session server-side;
  *  - full chain (stub transport driving the REAL withSession path) —
  *    heal → replay, per-request single-shot, concurrent single-flight.
  */
class NeblinkClientSessionHealSpec extends FunSuite:

  private val AuthRej403 = "HTTP 403: {\"error\":\"Missing or invalid token\"}"
  private val Business403 = "HTTP 403: {\"error\":\"not_blocker\"}"

  private val cfg = NeblinkServerConfig(
    url = "http://127.0.0.1:1", // never contacted — the transport is stubbed
    networkId = "net",
    secret = "s",
    deviceToken = Some("device-token")
  )

  private val ident = DeviceIdentity(
    deviceId = "qa-device",
    deviceName = "qa-host",
    platform = "macos"
  )

  private val SessionOk =
    """{"token":"fresh-sess","networkId":"net","deviceId":"qa-device","peers":[]}"""
  private val SearchOk = """{"found":false}"""

  /** Stub transport state machine:
    *  - session endpoint: succeeds (clearing the kick) unless `failHealLogin`
    *    was armed after the startup login — then it 403s (heal login fails);
    *  - API endpoint: 403 auth-reject while kicked, else per `apiMode`
    *    ("ok" | "always403" — auth-reject even with a live session, for the
    *    replay-fails shape | "business403" — non-token 403 that must NEVER
    *    trigger a heal). */
  private class HealClient(
    withIdentity: Boolean,
    apiMode: String = "ok"
  ):
    val logins        = new AtomicInteger(0)
    val apiCalls      = new AtomicInteger(0)
    val kicked        = new AtomicBoolean(false)
    val failHealLogin = new AtomicBoolean(false)

    val client = new NeblinkClient(
      cfg,
      serverPort = 1,
      identity = if withIdentity then Some(IO.pure(ident)) else None
    ):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        if url.endsWith("/api/device/session") then
          IO {
            val n = logins.incrementAndGet()
            if n > 1 && failHealLogin.get() then () // heal login fails
            else kicked.set(false)                  // fresh session = kick cleared
          }.flatMap { _ =>
            val n = logins.get()
            if n > 1 && failHealLogin.get() then IO.pure(Left(AuthRej403))
            else IO.pure(Right(SessionOk))
          }
        else
          IO(apiCalls.incrementAndGet()).flatMap { _ =>
            if apiMode == "business403" then IO.pure(Left(Business403))
            else if apiMode == "always403" || kicked.get() then IO.pure(Left(AuthRej403))
            else IO.pure(Right(SearchOk))
          }

    /** Test-entry: the gateway's startup login (live session), then the
      * server kicks that session — the incident's pre-request state. */
    def loginThenKick(): Unit =
      client.login("qa-device", "qa-host", "macos", Nil).unsafeRunSync()
      kicked.set(true)
  end HealClient

  // ===== Pure gate quadrants =====

  test("gate Q1: 401 -> recoverable") {
    assert(NeblinkClient.sessionRecoverable("HTTP 401: unauthorized"))
  }

  test("gate Q2: server auth-rejection 403 shape -> recoverable") {
    assert(NeblinkClient.sessionRecoverable(AuthRej403))
    assert(NeblinkClient.sessionRecoverable("HTTP 403: Missing or invalid token"))
  }

  test("gate Q3 (kick-safety nail): business 403 never triggers a re-login") {
    // A re-login kicks our own previous session server-side (one-live-session
    // policy) — a business 403 entering the heal path would churn sessions.
    assert(!NeblinkClient.sessionRecoverable(Business403))
    assert(!NeblinkClient.sessionRecoverable("HTTP 403: {\"error\":\"not_friend\"}"))
    assert(!NeblinkClient.sessionRecoverable("HTTP 422: invalid_query"))
    assert(!NeblinkClient.sessionRecoverable("HTTP 500: boom"))
    assert(!NeblinkClient.sessionRecoverable("connection refused"))
  }

  // ===== Full chain: real withSession path over the kick state machine =====

  test("chain: kicked session -> ONE silent re-login -> replay succeeds (search restored)") {
    val c = HealClient(withIdentity = true)
    c.loginThenKick()

    val out = c.client.searchUser("alice").unsafeRunSync()

    assert(out.isRight, s"expected the heal to restore the search, got $out")
    assertEquals(c.logins.get(), 2, "startup login + exactly ONE silent re-login")
    assertEquals(c.apiCalls.get(), 2, "kicked attempt + exactly one replay")
  }

  test("chain: replay failing again does NOT re-enter the heal (per-request single shot)") {
    val c = HealClient(withIdentity = true, apiMode = "always403")
    c.loginThenKick()

    val out = c.client.searchUser("alice").unsafeRunSync()

    assertEquals(out, Left(AuthRej403), "original auth error surfaces after a failed heal")
    assertEquals(c.logins.get(), 2, "startup + one heal attempt per request — more = retry loop")
    assertEquals(c.apiCalls.get(), 2, "kicked attempt + one replay, nothing more")
  }

  test("chain: business 403 passes through untouched (no login, no replay)") {
    val c = HealClient(withIdentity = true, apiMode = "business403")
    c.loginThenKick()

    val out = c.client.searchUser("someone").unsafeRunSync()
    assertEquals(out, Left(Business403), "business 403 surfaces as-is")
    assertEquals(c.logins.get(), 1, "startup login only — the heal must NOT fire")
    assertEquals(c.apiCalls.get(), 1, "no replay attempt")
  }

  test("chain: no identity source -> heal disabled, error surfaces as-is") {
    val c = HealClient(withIdentity = false)
    c.loginThenKick()

    val out = c.client.searchUser("alice").unsafeRunSync()
    assertEquals(out, Left(AuthRej403))
    assertEquals(c.logins.get(), 1, "startup login only — no heal without an identity source")
    assertEquals(c.apiCalls.get(), 1)
  }

  test("chain: heal login itself fails -> original error, no replay") {
    val c = HealClient(withIdentity = true)
    c.loginThenKick()
    c.failHealLogin.set(true)

    val out = c.client.searchUser("alice").unsafeRunSync()
    assertEquals(out, Left(AuthRej403))
    assertEquals(c.logins.get(), 2, "startup + the (failed) heal login")
    assertEquals(c.apiCalls.get(), 1, "failed heal -> no replay attempt")
  }

  test("chain: 8 concurrent kicked requests -> exactly ONE re-login, all succeed (single-flight)") {
    val c = HealClient(withIdentity = true)
    c.loginThenKick()

    val outs = IO.parSequenceN(8)(List.fill(8)(c.client.searchUser("alice"))).unsafeRunSync()
    assert(outs.forall(_.isRight), s"all requests must succeed after the heal: $outs")
    assertEquals(
      c.logins.get(),
      2,
      "startup + N concurrent auth rejections must produce exactly ONE re-login — more would kick sessions in a loop"
    )
  }

end NeblinkClientSessionHealSpec
