package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.parse
import munit.FunSuite

/** Regression nails for the 401 silent re-login anti-loop gate (qa-backend
  * 打回 2026-08-28: the single-shot guarantee shipped with zero test coverage
  * — a refactor flipping `allowRelogin` on the retry call would loop forever,
  * re-sending the login and re-running the refresh-token rotation, burning
  * provider tokens).
  *
  * Two independent layers, because each catches a different regression vector:
  *  - pure gate quadrants (`NeblinkClient.reloginAllowed` / `reloginOutcome`) —
  *    catches edits to the gate predicate itself;
  *  - full-chain single-shot (stub transport subclass driving the REAL
  *    doLogin path) — catches edits to the `allowRelogin = false` call-site
  *    constant that the pure layer cannot see.
  */
class NeblinkClientReloginSpec extends FunSuite:

  private val cfg = NeblinkServerConfig(
    url = "http://127.0.0.1:1", // never contacted — the transport is stubbed
    networkId = "net",
    secret = "s",
    deviceToken = Some("stale-token")
  )

  // ===== Pure gate quadrants =====

  test("gate Q1: 401 + relogin budget left -> allowed") {
    assert(NeblinkClient.reloginAllowed("HTTP 401: device token rejected", allowRelogin = true))
  }

  test("gate Q2 (loop nail): 401 + budget spent -> closed even though a hook could run") {
    assert(!NeblinkClient.reloginAllowed("HTTP 401: device token rejected", allowRelogin = false))
  }

  test("gate Q3: non-401 error -> closed regardless of budget") {
    assert(!NeblinkClient.reloginAllowed("HTTP 500: boom", allowRelogin = true))
    assert(!NeblinkClient.reloginAllowed("HTTP 500: boom", allowRelogin = false))
  }

  test("gate Q4: only the HTTP 401 error prefix passes the gate") {
    assert(NeblinkClient.reloginAllowed("HTTP 401", allowRelogin = true))
    assert(!NeblinkClient.reloginAllowed("connection refused", allowRelogin = true))
    assert(!NeblinkClient.reloginAllowed("HTTP 401 Forbidden-ish noise", allowRelogin = false))
  }

  test("outcome: fresh token retries; None surfaces the ORIGINAL error") {
    assertEquals(NeblinkClient.reloginOutcome(Some("fresh"), "HTTP 401: rejected"), Right("fresh"))
    assertEquals(NeblinkClient.reloginOutcome(None, "HTTP 401: rejected"), Left("HTTP 401: rejected"))
  }

  // ===== Full chain: single-shot through the real doLogin path =====

  /** Real NeblinkClient with a canned transport; counts hook entries and
    * records every request body so the retry's token swap is observable. */
  private class ChainClient(reply: Either[String, String], hookToken: Option[String]):
    var transportCalls = 0
    var hookCalls      = 0
    var requestBodies  = List.empty[String]

    val client = new NeblinkClient(
      cfg,
      serverPort = 1,
      onDeviceTokenRejected = Some(IO(hookToken).flatMap { t =>
        hookCalls += 1
        IO.pure(t)
      })
    ):
      override protected def sendRequest(
        method: String,
        url: String,
        body: String,
        token: Option[String]
      ): IO[Either[String, String]] =
        IO { transportCalls += 1; requestBodies = requestBodies :+ body } *> IO.pure(reply)

  test("chain: 401 -> hook exactly ONCE -> one retry carrying the fresh token -> original error surfaced") {
    val c   = new ChainClient(Left("HTTP 401: device token rejected"), hookToken = Some("fresh-token"))
    val out = c.client
      .login("dev", "name", "platform", List(NeblinkEndpoint("10.0.0.5", 1, "lan")))
      .unsafeRunSync()

    assertEquals(out, Left("HTTP 401: device token rejected"))
    assertEquals(c.hookCalls, 1, "hook must run exactly once — more = retry loop")
    assertEquals(c.transportCalls, 2, "initial attempt + exactly one retry")
    assert(c.requestBodies.size == 2)
    val retryBody = parse(c.requestBodies(1)) match
      case Right(json) => json
      case Left(e)     => fail(s"retry body not JSON: ${c.requestBodies(1)} ($e)")
    assertEquals(
      retryBody.hcursor.downField("deviceToken").as[String].toOption,
      Some("fresh-token"),
      "retry must carry the hook's fresh token, not the stale one"
    )
    assert(!c.requestBodies(1).contains("stale-token"))
  }

  test("chain: hook returns None -> no retry, original error surfaced") {
    val c   = new ChainClient(Left("HTTP 401: device token rejected"), hookToken = None)
    val out = c.client
      .login("dev", "name", "platform", List(NeblinkEndpoint("10.0.0.5", 1, "lan")))
      .unsafeRunSync()

    assertEquals(out, Left("HTTP 401: device token rejected"))
    assertEquals(c.hookCalls, 1)
    assertEquals(c.transportCalls, 1, "no fresh token -> no retry")
  }

  test("chain: non-401 error never reaches the hook") {
    val c   = new ChainClient(Left("HTTP 500: boom"), hookToken = Some("fresh-token"))
    val out = c.client
      .login("dev", "name", "platform", List(NeblinkEndpoint("10.0.0.5", 1, "lan")))
      .unsafeRunSync()

    assertEquals(out, Left("HTTP 500: boom"))
    assertEquals(c.hookCalls, 0, "gate must close on non-401 before the hook is touched")
    assertEquals(c.transportCalls, 1)
  }

end NeblinkClientReloginSpec
