package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import scala.collection.mutable.ListBuffer

/**
  * Logto Experience API client (selfdrawn login BFF, 2026-09-09 design §2.2)
  * — pure builders + scripted transport, mirroring `LogtoAuthCodeSpec`.
  * Route table is the DEPLOYED-bundle reverse-lookup (design §2.2), each
  * builder pins method + path + body shape.
  */
class LogtoExperienceSpec extends FunSuite:

  private val Ep = "https://auth.example"

  // ── scripted transport ───────────────────────────────────────────────────

  /** Queue-backed Send that records every request it sees. */
  private def scripted(resps: LogtoExperience.ExperienceResponse*)
      : (LogtoExperience.Send, ListBuffer[LogtoExperience.ExperienceRequest]) =
    val seen = ListBuffer.empty[LogtoExperience.ExperienceRequest]
    val it = resps.iterator
    val send: LogtoExperience.Send = req =>
      IO {
        seen += req
        if it.hasNext then it.next()
        else LogtoExperience.ExperienceResponse(599, """{"code":"test.exhausted"}""")
      }
    (send, seen)

  private def ok(body: String): LogtoExperience.ExperienceResponse =
    LogtoExperience.ExperienceResponse(200, body)

  // ── builders: the §2.2 route table ───────────────────────────────────────

  test("interactionStart is the plain GET /oidc/auth via authorizeUrl with prompt=consent") {
    val r = LogtoExperience.interactionStart(Ep, "pkce-app", "http://127.0.0.1:8080/auth/callback", "chal", "st-1")
    assertEquals(r.method, "GET")
    assertEquals(r.url, LogtoAuthCode.authorizeUrl(Ep, "pkce-app", "http://127.0.0.1:8080/auth/callback", "chal", "st-1"))
    // refresh-token invariant must survive the selfdrawn chain
    assert(r.url.contains("prompt=consent"), r.url)
    assert(r.url.contains("offline_access"), r.url)
    assert(r.body.isEmpty, "GET carries no body")
    // custom prompt passes through (switch-account entry keeps its semantics)
    val r2 = LogtoExperience.interactionStart(Ep, "c", "http://x/callback", "ch", "s", prompt = "login consent")
    assert(r2.url.contains("prompt=login+consent"), r2.url)
  }

  test("interactionPut sets the interaction event") {
    val r = LogtoExperience.interactionPut(Ep, "Register")
    assertEquals(r.method, "PUT")
    assertEquals(r.url, "https://auth.example/api/experience")
    assertEquals(io.circe.parser.parse(r.body.getOrElse("")), Right(Json.obj("interactionEvent" -> "Register".asJson)))
  }

  test("passwordVerify posts identifier + password to verification/password") {
    val r = LogtoExperience.passwordVerify(Ep, "username", "alice", "pw-1")
    assertEquals(r.method, "POST")
    assertEquals(r.url, "https://auth.example/api/experience/verification/password")
    val body = io.circe.parser.parse(r.body.getOrElse("")).getOrElse(Json.Null)
    assertEquals(body.hcursor.downField("identifier").downField("type").as[String].toOption, Some("username"))
    assertEquals(body.hcursor.downField("identifier").downField("value").as[String].toOption, Some("alice"))
    assertEquals(body.hcursor.downField("password").as[String].toOption, Some("pw-1"))
  }

  test("verificationCodeSend carries interactionEvent + identifier") {
    val r = LogtoExperience.verificationCodeSend(Ep, "ForgotPassword", "email", "a@b.c")
    assertEquals(r.url, "https://auth.example/api/experience/verification/verification-code")
    val body = io.circe.parser.parse(r.body.getOrElse("")).getOrElse(Json.Null)
    assertEquals(body.hcursor.downField("interactionEvent").as[String].toOption, Some("ForgotPassword"))
    assertEquals(body.hcursor.downField("identifier").downField("value").as[String].toOption, Some("a@b.c"))
  }

  test("verificationCodeVerify posts verificationId + code") {
    val r = LogtoExperience.verificationCodeVerify(Ep, "vid-9", "123456")
    assertEquals(r.url, "https://auth.example/api/experience/verification/verification-code/verify")
    val body = io.circe.parser.parse(r.body.getOrElse("")).getOrElse(Json.Null)
    assertEquals(body.hcursor.downField("verificationId").as[String].toOption, Some("vid-9"))
    assertEquals(body.hcursor.downField("code").as[String].toOption, Some("123456"))
  }

  test("socialAuthorizationUri carries state + the LOCAL social-callback redirect") {
    val r = LogtoExperience.socialAuthorizationUri(Ep, "github", "st-1", "http://127.0.0.1:8080/auth/social-callback")
    assertEquals(r.method, "POST")
    assertEquals(r.url, "https://auth.example/api/experience/verification/social/github/authorization-uri")
    val body = io.circe.parser.parse(r.body.getOrElse("")).getOrElse(Json.Null)
    assertEquals(body.hcursor.downField("state").as[String].toOption, Some("st-1"))
    assertEquals(body.hcursor.downField("redirectUri").as[String].toOption, Some("http://127.0.0.1:8080/auth/social-callback"))
  }

  test("socialVerify posts the connectorData verbatim to social/:target/verify") {
    val data = Json.obj("code" -> "gh-c".asJson, "state" -> "st-1".asJson)
    val r = LogtoExperience.socialVerify(Ep, "google", data)
    assertEquals(r.url, "https://auth.example/api/experience/verification/social/google/verify")
    assertEquals(io.circe.parser.parse(r.body.getOrElse("")), Right(data))
  }

  test("identification binds the verificationId; linkSocialIdentity is opt-in") {
    val plain = LogtoExperience.identification(Ep, "vid-1")
    val body = io.circe.parser.parse(plain.body.getOrElse("")).getOrElse(Json.Null)
    assertEquals(plain.url, "https://auth.example/api/experience/identification")
    assertEquals(body, Json.obj("verificationId" -> "vid-1".asJson))
    val link = LogtoExperience.identification(Ep, "vid-1", linkSocialIdentity = true)
    val lbody = io.circe.parser.parse(link.body.getOrElse("")).getOrElse(Json.Null)
    assertEquals(lbody.hcursor.downField("linkSocialIdentity").as[Boolean].toOption, Some(true))
  }

  test("profile passes the payload through verbatim") {
    val payload = Json.obj("type" -> "username".asJson, "value" -> "alice".asJson)
    val r = LogtoExperience.profile(Ep, payload)
    assertEquals(r.url, "https://auth.example/api/experience/profile")
    assertEquals(io.circe.parser.parse(r.body.getOrElse("")), Right(payload))
  }

  test("submit posts an empty object") {
    val r = LogtoExperience.submit(Ep)
    assertEquals(r.method, "POST")
    assertEquals(r.url, "https://auth.example/api/experience/submit")
    assertEquals(r.body, Some("{}"))
  }

  test("totpVerify carries code and optional verificationId") {
    val r = LogtoExperience.totpVerify(Ep, "654321", None)
    assertEquals(r.url, "https://auth.example/api/experience/verification/totp/verify")
    assertEquals(io.circe.parser.parse(r.body.getOrElse("")), Right(Json.obj("code" -> "654321".asJson)))
    val r2 = LogtoExperience.totpVerify(Ep, "654321", Some("vid-t"))
    assertEquals(
      io.circe.parser.parse(r2.body.getOrElse("")),
      Right(Json.obj("code" -> "654321".asJson, "verificationId" -> "vid-t".asJson)))
  }

  test("mfaBind and mfaSkipped hit the profile/mfa endpoints") {
    val bind = LogtoExperience.mfaBind(Ep, "vid-t")
    assertEquals(bind.url, "https://auth.example/api/experience/profile/mfa")
    assertEquals(
      io.circe.parser.parse(bind.body.getOrElse("")),
      Right(Json.obj("type" -> "totp".asJson, "verificationId" -> "vid-t".asJson)))
    val skip = LogtoExperience.mfaSkipped(Ep)
    assertEquals(skip.url, "https://auth.example/api/experience/profile/mfa-skipped")
  }

  test("consentInfo is a GET; consentApprove carries optional organizationIds") {
    val info = LogtoExperience.consentInfo(Ep)
    assertEquals(info.method, "GET")
    assertEquals(info.url, "https://auth.example/api/interaction/consent")
    assertEquals(info.body, None)
    val approve = LogtoExperience.consentApprove(Ep, Some(List("org-1", "org-2")))
    assertEquals(approve.url, "https://auth.example/api/interaction/consent")
    assertEquals(
      io.circe.parser.parse(approve.body.getOrElse("")),
      Right(Json.obj("organizationIds" -> List("org-1", "org-2").asJson)))
    val plain = LogtoExperience.consentApprove(Ep, None)
    assertEquals(plain.body, Some("{}"))
  }

  // ── cookie jar ───────────────────────────────────────────────────────────

  test("parseSetCookie extracts name=value from attributed headers") {
    assertEquals(
      LogtoExperience.parseSetCookie("_interaction=ixn-1; Path=/; HttpOnly; Max-Age=3600"),
      Some("_interaction" -> "ixn-1"))
    assertEquals(LogtoExperience.parseSetCookie("empty="), Some("empty" -> ""))
    assertEquals(LogtoExperience.parseSetCookie("   "), None)
    assertEquals(LogtoExperience.parseSetCookie(null), None)
  }

  test("CookieJar absorbs Set-Cookie and renders the Cookie header; clear empties it") {
    val jar = LogtoExperience.CookieJar.unsafe
    assertEquals(jar.cookieHeader.unsafeRunSync(), None)
    jar.absorb(List("_interaction=ixn-1; Path=/; HttpOnly", "_interaction.sig=sig-1; Path=/")).unsafeRunSync()
    assertEquals(jar.cookieHeader.unsafeRunSync(), Some("_interaction=ixn-1; _interaction.sig=sig-1"))
    assertEquals(jar.snapshot.unsafeRunSync(), Map("_interaction" -> "ixn-1", "_interaction.sig" -> "sig-1"))
    jar.absorb(List("_interaction=ixn-2")).unsafeRunSync() // refresh wins
    assertEquals(jar.snapshot.unsafeRunSync().get("_interaction"), Some("ixn-2"))
    jar.clear.unsafeRunSync()
    assertEquals(jar.cookieHeader.unsafeRunSync(), None)
  }

  // ── run(): attach + absorb + classify ────────────────────────────────────

  test("run attaches the jar cookie and absorbs the response Set-Cookie") {
    val jar = LogtoExperience.CookieJar.unsafe
    jar.absorb(List("a=1")).unsafeRunSync()
    val cookies = ListBuffer.empty[Option[String]]
    val send: LogtoExperience.Send = req =>
      IO {
        cookies += req.cookie
        LogtoExperience.ExperienceResponse(200, "{}", List("b=2; Path=/"))
      }
    LogtoExperience.run(send)(jar)(LogtoExperience.submit(Ep)).unsafeRunSync()
    LogtoExperience.run(send)(jar)(LogtoExperience.submit(Ep)).unsafeRunSync()
    assertEquals(cookies.toList.map(_.getOrElse("")), List("a=1", "a=1; b=2"))
  }

  test("run classifies 2xx JSON, 204-empty, and non-2xx {code,message}") {
    val (send1, _) = scripted(ok("""{"verificationId":"v1"}"""))
    assertEquals(
      LogtoExperience.run(send1)(LogtoExperience.CookieJar.unsafe)(LogtoExperience.submit(Ep)).unsafeRunSync(),
      Right(Json.obj("verificationId" -> "v1".asJson)))
    val (send2, _) = scripted(LogtoExperience.ExperienceResponse(204, ""))
    assertEquals(
      LogtoExperience.run(send2)(LogtoExperience.CookieJar.unsafe)(LogtoExperience.identification(Ep, "v")).unsafeRunSync(),
      Right(Json.obj()))
    val (send3, _) = scripted(LogtoExperience.ExperienceResponse(422, """{"code":"session.invalid_credentials","message":"bad"}"""))
    LogtoExperience.run(send3)(LogtoExperience.CookieJar.unsafe)(LogtoExperience.passwordVerify(Ep, "username", "u", "p"))
      .unsafeRunSync() match
      case Left(err) =>
        assertEquals(err.status, 422)
        assertEquals(err.code, Some("session.invalid_credentials"))
        assertEquals(err.message, Some("bad"))
      case other => fail(s"expected Left, got $other")
  }

  test("run maps transport failure (status 0) to an ExperienceError carrying the message") {
    val (send, _) = scripted(LogtoExperience.ExperienceResponse(0, "connect timed out"))
    LogtoExperience.run(send)(LogtoExperience.CookieJar.unsafe)(LogtoExperience.submit(Ep)).unsafeRunSync() match
      case Left(err) => assertEquals(err.describe, "connect timed out")
      case other     => fail(s"expected Left, got $other")
  }

  // ── error mapping ────────────────────────────────────────────────────────

  test("parseError understands both {code,message} and {error,error_description}") {
    val e1 = LogtoExperience.parseError(422, """{"code":"session.invalid_credentials","message":"x"}""")
    assertEquals(e1.code, Some("session.invalid_credentials"))
    assertEquals(e1.message, Some("x"))
    val e2 = LogtoExperience.parseError(400, """{"error":"invalid_grant","error_description":"expired"}""")
    assertEquals(e2.code, Some("invalid_grant"))
    assertEquals(e2.message, Some("expired"))
    val e3 = LogtoExperience.parseError(500, "not json at all")
    assertEquals(e3.code, None)
    assertEquals(e3.describe, "HTTP 500")
  }

  test("ExperienceError classifiers split MFA (wizard-driven) from consent (auto-resolved)") {
    val mfa = LogtoExperience.parseError(422, """{"code":"session.mfa_required"}""")
    assert(mfa.isMfaRequired && !mfa.isConsentRequired)
    val consent = LogtoExperience.parseError(422, """{"code":"session.consent_required"}""")
    assert(consent.isConsentRequired && !consent.isMfaRequired)
  }

  // ── redirect / consent helpers ───────────────────────────────────────────

  test("isConsentRedirect keys on the /consent path, not on the query") {
    assert(LogtoExperience.isConsentRedirect("https://auth.example/consent"))
    assert(LogtoExperience.isConsentRedirect("https://auth.example/consent?app_id=x"))
    assert(!LogtoExperience.isConsentRedirect("http://127.0.0.1:8080/auth/callback?code=c&state=s"))
    assert(LogtoExperience.isConsentRedirect("/consent"))
    assert(!LogtoExperience.isConsentRedirect("::::not-a-uri"))
  }

  test("parseRedirectCallback extracts code+state; missing either is None") {
    assertEquals(
      LogtoExperience.parseRedirectCallback("http://127.0.0.1:8080/auth/callback?code=c%20x&state=st-1"),
      Some(("c x", "st-1")))
    assertEquals(LogtoExperience.parseRedirectCallback("http://127.0.0.1:8080/auth/callback?state=only"), None)
    assertEquals(LogtoExperience.parseRedirectCallback("http://127.0.0.1:8080/auth/callback"), None)
    assertEquals(LogtoExperience.parseRedirectCallback("::::nope"), None)
  }

  test("connectorDataFromQuery maps every param to a string field") {
    assertEquals(
      LogtoExperience.connectorDataFromQuery(Map("code" -> "gh-1", "state" -> "st")),
      Json.obj("code" -> "gh-1".asJson, "state" -> "st".asJson))
    assertEquals(LogtoExperience.connectorDataFromQuery(Map.empty), Json.obj())
  }

  // ── submit → consent orchestration ───────────────────────────────────────

  test("submitWithConsent passes a direct final redirect through (no consent calls)") {
    val finalUrl = "http://127.0.0.1:8080/auth/callback?code=c1&state=s1"
    val (send, seen) = scripted(ok(s"""{"redirectTo":"$finalUrl"}"""))
    val r = LogtoExperience.submitWithConsent(send)(Ep, LogtoExperience.CookieJar.unsafe).unsafeRunSync()
    assertEquals(r, Right(finalUrl))
    assertEquals(seen.map(_.url).toList, List(s"$Ep/api/experience/submit"), "no consent endpoints on the direct path")
  }

  test("submitWithConsent resolves a /consent redirectTo via GET info + POST approve") {
    val postUrl = "http://127.0.0.1:8080/auth/callback?code=c2&state=s1"
    val (send, seen) = scripted(
      ok("""{"redirectTo":"https://auth.example/consent"}"""),
      ok("""{"scopes":["openid","offline_access"]}"""),
      ok(s"""{"redirectTo":"$postUrl"}""")
    )
    val r = LogtoExperience.submitWithConsent(send)(Ep, LogtoExperience.CookieJar.unsafe).unsafeRunSync()
    assertEquals(r, Right(postUrl))
    assertEquals(
      seen.map(req => s"${req.method} ${req.url}").toList,
      List(
        s"POST $Ep/api/experience/submit",
        s"GET $Ep/api/interaction/consent",
        s"POST $Ep/api/interaction/consent"))
  }

  test("submitWithConsent resolves a consent-required error code the same way") {
    val postUrl = "http://127.0.0.1:8080/auth/callback?code=c3&state=s1"
    val (send, seen) = scripted(
      LogtoExperience.ExperienceResponse(422, """{"code":"session.consent_required","message":"consent"}"""),
      ok("""{"scopes":[]}"""),
      ok(s"""{"redirectTo":"$postUrl"}""")
    )
    assertEquals(LogtoExperience.submitWithConsent(send)(Ep, LogtoExperience.CookieJar.unsafe).unsafeRunSync(), Right(postUrl))
    assertEquals(seen.size, 3)
  }

  test("submitWithConsent leaves non-consent errors (e.g. MFA) for the wizard") {
    val (send, seen) = scripted(
      LogtoExperience.ExperienceResponse(422, """{"code":"session.mfa_required","message":"totp"}"""))
    val r = LogtoExperience.submitWithConsent(send)(Ep, LogtoExperience.CookieJar.unsafe).unsafeRunSync()
    r match
      case Left(err) =>
        assertEquals(err.code, Some("session.mfa_required"))
        assert(err.isMfaRequired)
      case other => fail(s"expected Left, got $other")
    assertEquals(seen.size, 1, "no consent endpoints on the MFA path")
  }

  test("submitWithConsent answers 502 when submit omits redirectTo or consent omits it") {
    val (send1, _) = scripted(ok("""{"unexpected":1}"""))
    LogtoExperience.submitWithConsent(send1)(Ep, LogtoExperience.CookieJar.unsafe).unsafeRunSync() match
      case Left(err) => assertEquals(err.status, 502)
      case other     => fail(s"expected Left, got $other")
    val (send2, _) = scripted(
      ok("""{"redirectTo":"https://auth.example/consent"}"""),
      ok("""{"scopes":[]}"""),
      ok("""{"unexpected":1}""")
    )
    LogtoExperience.submitWithConsent(send2)(Ep, LogtoExperience.CookieJar.unsafe).unsafeRunSync() match
      case Left(err) => assertEquals(err.status, 502)
      case other     => fail(s"expected Left, got $other")
  }

end LogtoExperienceSpec
