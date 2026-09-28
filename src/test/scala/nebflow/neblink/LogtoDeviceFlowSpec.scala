package nebflow.neblink

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

import scala.collection.mutable.ListBuffer

class LogtoDeviceFlowSpec extends FunSuite:

  // ── form encoding ───────────────────────────────────────────────────────

  test("formEncode escapes values and joins with &") {
    val encoded = LogtoDeviceFlow.formEncode(
      "client_id" -> "native-app",
      "scope" -> "openid offline_access",
      "grant_type" -> "urn:ietf:params:oauth:grant-type:device_code"
    )
    assertEquals(
      encoded,
      "client_id=native-app&scope=openid+offline_access&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"
    )
  }

  // ── request shapes ──────────────────────────────────────────────────────

  test("startRequest targets the RFC 8628 device endpoint with client_id + scope") {
    val req = LogtoDeviceFlow.startRequest("https://auth.example/", "app-1")
    assertEquals(req.url, "https://auth.example/oidc/device/auth")
    assertEquals(req.contentType, "application/x-www-form-urlencoded")
    assert(req.body.contains("client_id=app-1"))
    assert(req.body.contains("scope=openid"))
    assert(req.bearer.isEmpty)
  }

  test("pollRequest carries the device_code grant and code") {
    val req = LogtoDeviceFlow.pollRequest("https://auth.example", "app-1", "dc-123")
    assertEquals(req.url, "https://auth.example/oidc/token")
    assert(req.body.contains("device_code=dc-123"))
    assert(req.body.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
  }

  test("registerRequest posts the identity with a Bearer token") {
    val req = LogtoDeviceFlow.registerRequest(
      "https://server.example/",
      "at-xyz",
      "device-1",
      "My Mac",
      "darwin"
    )
    assertEquals(req.url, "https://server.example/api/device/register")
    assertEquals(req.bearer, Some("at-xyz"))
    val body = parse(req.body).fold(e => fail(s"unparseable body: ${e.message}"), j => j).hcursor
    assertEquals(body.downField("deviceId").as[String], Right("device-1"))
    assertEquals(body.downField("deviceName").as[String], Right("My Mac"))
    assertEquals(body.downField("platform").as[String], Right("darwin"))
  }

  // ── start response mapping (frontend contract) ──────────────────────────

  test("parseStartResponse maps Logto snake_case onto the camelCase frontend contract") {
    val body =
      """{"device_code":"dc-1","user_code":"ABCD-EFGH","verification_uri":"https://auth.example/device",
        |"verification_uri_complete":"https://auth.example/device?code=ABCD-EFGH","expires_in":600,"interval":5}""".stripMargin
    val json = LogtoDeviceFlow.parseStartResponse(body).fold(e => fail(e), identity)
    val c = json.hcursor
    assertEquals(c.downField("deviceCode").as[String], Right("dc-1"))
    assertEquals(c.downField("userCode").as[String], Right("ABCD-EFGH"))
    // verification_uri_complete preferred over the bare uri
    assertEquals(
      c.downField("verificationUri").as[String],
      Right("https://auth.example/device?code=ABCD-EFGH")
    )
    assertEquals(c.downField("interval").as[Int], Right(5))
    assertEquals(c.downField("expiresIn").as[Int], Right(600))
  }

  test("parseStartResponse falls back to verification_uri and RFC defaults") {
    val body = """{"device_code":"dc-2","user_code":"ZZZZ"}"""
    val json = LogtoDeviceFlow.parseStartResponse(body).fold(e => fail(e), identity)
    val c = json.hcursor
    assertEquals(c.downField("verificationUri").as[String], Right(""))
    assertEquals(c.downField("interval").as[Int], Right(5))
    assertEquals(c.downField("expiresIn").as[Int], Right(900))
  }

  test("parseStartResponse rejects bodies without device_code/user_code") {
    assert(LogtoDeviceFlow.parseStartResponse("""{"device_code":"only"}""").isLeft)
    assert(LogtoDeviceFlow.parseStartResponse("not json").isLeft)
  }

  // ── poll classification ─────────────────────────────────────────────────

  test("classifyPoll: 200 with access_token is Success") {
    assertEquals(
      LogtoDeviceFlow.classifyPoll(200, """{"access_token":"at","token_type":"Bearer"}"""),
      LogtoDeviceFlow.PollOutcome.Success("at")
    )
  }

  test("classifyPoll: 200 without access_token is Failed") {
    assert(
      LogtoDeviceFlow.classifyPoll(200, """{"token_type":"Bearer"}""")
        .isInstanceOf[LogtoDeviceFlow.PollOutcome.Failed]
    )
  }

  test("classifyPoll: authorization_pending and slow_down normalize to Pending") {
    assertEquals(
      LogtoDeviceFlow.classifyPoll(400, """{"error":"authorization_pending"}"""),
      LogtoDeviceFlow.PollOutcome.Pending("authorization_pending")
    )
    // slow_down must NOT reach the frontend as a terminal error — the
    // frontend only understands authorization_pending.
    assertEquals(
      LogtoDeviceFlow.classifyPoll(400, """{"error":"slow_down"}"""),
      LogtoDeviceFlow.PollOutcome.Pending("authorization_pending")
    )
  }

  test("classifyPoll: expired_token and access_denied are terminal") {
    assertEquals(
      LogtoDeviceFlow.classifyPoll(400, """{"error":"expired_token"}"""),
      LogtoDeviceFlow.PollOutcome.Failed("expired_token")
    )
    assertEquals(
      LogtoDeviceFlow.classifyPoll(400, """{"error":"access_denied"}"""),
      LogtoDeviceFlow.PollOutcome.Failed("access_denied")
    )
  }

  test("classifyPoll: non-JSON error body degrades to HTTP status") {
    assertEquals(
      LogtoDeviceFlow.classifyPoll(503, "gateway down"),
      LogtoDeviceFlow.PollOutcome.Failed("HTTP 503")
    )
  }

  // ── flow steps over a fake transport ────────────────────────────────────

  test("start maps the provider response through the fake transport") {
    val send: LogtoDeviceFlow.Send = _ =>
      IO.pure(
        (
          200,
          """{"device_code":"dc","user_code":"UC","verification_uri_complete":"https://a/d?code=UC","interval":7}"""
        )
      )
    val json =
      LogtoDeviceFlow.start(send)("https://auth.example", "app-1").unsafeRunSync().fold(e => fail(e), identity)
    assertEquals(json.hcursor.downField("deviceCode").as[String], Right("dc"))
    assertEquals(json.hcursor.downField("interval").as[Int], Right(7))
  }

  test("start surfaces the provider error string on failure") {
    val send: LogtoDeviceFlow.Send = _ => IO.pure((400, """{"error":"invalid_client"}"""))
    val err =
      LogtoDeviceFlow.start(send)("https://auth.example", "app-1").unsafeRunSync().fold(identity, j => fail(s"expected Left, got $j"))
    assertEquals(err, "invalid_client")
  }

  test("full sequence: pending → pending → success → register exchange") {
    val requests = ListBuffer.empty[LogtoDeviceFlow.Request]
    // Two pending polls, then a granted token; the register call succeeds
    // with an EnrollResponse identical to the legacy shape.
    val send: LogtoDeviceFlow.Send = req =>
      requests += req
      IO.pure(
        if req.url.endsWith("/oidc/token") then
          if requests.count(_.url.endsWith("/oidc/token")) < 3 then
            (400, """{"error":"authorization_pending"}""")
          else (200, """{"access_token":"at-final"}""")
        else if req.url.endsWith("/api/device/register") then
          (
            200,
            """{"deviceToken":"dt-1","networkId":"net-1","deviceId":"device-1","avatarUrl":null}"""
          )
        else (500, """{"error":"unexpected"}""")
      )

    val pending1 =
      LogtoDeviceFlow.pollOnce(send)("https://auth.example", "app-1", "dc").unsafeRunSync()
    assertEquals(pending1, LogtoDeviceFlow.PollOutcome.Pending("authorization_pending"))
    val pending2 =
      LogtoDeviceFlow.pollOnce(send)("https://auth.example", "app-1", "dc").unsafeRunSync()
    assertEquals(pending2, LogtoDeviceFlow.PollOutcome.Pending("authorization_pending"))

    val success =
      LogtoDeviceFlow.pollOnce(send)("https://auth.example", "app-1", "dc").unsafeRunSync()
      match
        case LogtoDeviceFlow.PollOutcome.Success(at) => at
        case other                                   => fail(s"expected Success, got $other")
    assertEquals(success, "at-final")

    val enroll = LogtoDeviceFlow
      .register(send)("https://server.example", success, "device-1", "Mac", "darwin")
      .unsafeRunSync()
      .fold(e => fail(e), identity)
    assertEquals(enroll.hcursor.downField("deviceToken").as[String], Right("dt-1"))
    assertEquals(enroll.hcursor.downField("networkId").as[String], Right("net-1"))

    // The register request carried the provider token as Bearer.
    val registerReq = requests.last
    assertEquals(registerReq.bearer, Some("at-final"))
    assertEquals(registerReq.url, "https://server.example/api/device/register")
  }

  test("register surfaces the server error on rejection") {
    val send: LogtoDeviceFlow.Send = _ =>
      IO.pure((403, """{"error":"Invalid or expired token"}"""))
    val err = LogtoDeviceFlow
      .register(send)("https://server.example", "bad-token", "d", "n", "p")
      .unsafeRunSync()
      .fold(identity, j => fail(s"expected Left, got $j"))
    assertEquals(err, "Invalid or expired token")
  }

  test("transport failure (status 0) surfaces the exception message, not HTTP 0") {
    val send: LogtoDeviceFlow.Send = _ => IO.pure((0, "connect timed out"))
    val err =
      LogtoDeviceFlow.start(send)("https://auth.example", "app-1").unsafeRunSync().fold(identity, j => fail(s"expected Left, got $j"))
    assertEquals(err, "connect timed out")
    assertEquals(
      LogtoDeviceFlow.classifyPoll(0, "connect timed out"),
      LogtoDeviceFlow.PollOutcome.Failed("connect timed out")
    )
  }

end LogtoDeviceFlowSpec
