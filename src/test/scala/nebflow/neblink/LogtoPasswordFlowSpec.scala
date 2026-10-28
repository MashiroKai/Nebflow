package nebflow.neblink

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/**
 * 无头第一方密码登录（路径②, 台账 #924）的判据面：全部协议判定走注入 transport，
 * 不碰网络。方言 = 部署租户的 Experience-API v1-beta 形（2026-10-26 实探）：
 * `PUT /api/experience {interactionEvent}` → `verification/password
 * {identifier:{type,value}, password}` → `identification` → `submit {redirectTo}`
 * → 回调 Location 取 code（state 校验）→ `/oidc/token` 换票。
 *
 * 🔴 零回显断言：password 只进 verification 请求体；任何 Failure 载荷、任何后续
 * 请求都不得携带它。
 */
class LogtoPasswordFlowSpec extends CatsEffectSuite:

  private val endpoint = "https://auth.example"
  private val clientId = "ac-app-1"
  private val redirectUri = "http://127.0.0.1:8080/auth/callback"
  private val account = "kai@example.com"
  private val password = "s3cret-Ø-not-in-logs"

  /** Scripted fake: request + cookie-header -> (status, setCookies, location, body). */
  private type Fake = (LogtoTransport.Request, String) => LogtoPasswordFlow.Resp

  private def sendOf(script: ListBuffer[(String, LogtoTransport.Request, String, Fake)])(fake: Fake): LogtoPasswordFlow.Send =
    (req, jar) =>
      IO.delay {
        val step = script.size
        script += ((s"step-$step", req, jar, fake))
        fake(req, jar)
      }

  // ── A. 纯面：cookie 合并 / redirect 解析 / identifier 推导 ────────────────

  test("mergeCookies: 空 jar 起步、Set-Cookie 属性剥离、同名覆盖、空值删除、确定性序") {
    assertEquals(LogtoPasswordFlow.mergeCookies("", List("_interaction=abc; Path=/; HttpOnly")), "_interaction=abc")
    val grown = LogtoPasswordFlow.mergeCookies("_interaction=abc", List("_logto=xyz; Path=/; HttpOnly", "_interaction=def; Path=/"))
    // 同名覆盖 = 「删旧 + 尾插」⇒ 后写者排后（插入序确定性）。
    assertEquals(grown, "_logto=xyz; _interaction=def")
    assertEquals(LogtoPasswordFlow.mergeCookies(grown, List("_logto=; Expires=Thu, 01 Jan 1970")), "_interaction=def")
    assertEquals(LogtoPasswordFlow.mergeCookies("", List("malformed")), "")
  }

  test("parseRedirectQuery: code+state 提取、url-decode、缺 code/state 即 Left") {
    assertEquals(
      LogtoPasswordFlow.parseRedirectQuery(s"$redirectUri?code=abc%2Bdef&state=st-1"),
      Right(("abc+def", "st-1"))
    )
    assert(LogtoPasswordFlow.parseRedirectQuery("$redirectUri").isLeft)
    assert(LogtoPasswordFlow.parseRedirectQuery(s"$redirectUri?state=st-1").isLeft)
    assert(LogtoPasswordFlow.parseRedirectQuery(s"$redirectUri?code=abc").isLeft)
  }

  test("interactionAuthorizeUrl: AC+PKCE 参数齐全、无 prompt 位（与浏览器路径的差异点）") {
    val url = LogtoPasswordFlow.interactionAuthorizeUrl(endpoint, clientId, redirectUri, "chal-1", "st-1", "n-1")
    assert(url.startsWith(s"$endpoint/oidc/auth?"))
    assert(url.contains("client_id=ac-app-1"))
    assert(url.contains("response_type=code"))
    assert(url.contains("scope=openid+email+profile"))
    assert(url.contains("code_challenge=chal-1"))
    assert(url.contains("code_challenge_method=S256"))
    assert(url.contains("state=st-1"))
    assert(url.contains("nonce=n-1"))
    assert(!url.contains("prompt="), "无头舞步没有可渲染的 consent 页，不带 prompt")
  }

  test("identifierTypeOf: 含 @ ⇒ email，否则 username") {
    assertEquals(LogtoPasswordFlow.identifierTypeOf("kai@example.com"), "email")
    assertEquals(LogtoPasswordFlow.identifierTypeOf("kai"), "username")
  }

  // ── B. 失败分类（闭集） ─────────────────────────────────────────────────

  test("classifyFailure: invalid_credentials / rate / transport / 其余落 Protocol（不带凭据）") {
    assertEquals(
      LogtoPasswordFlow.classifyFailure("verification/password", 422, """{"code":"session.invalid_credentials","message":"Incorrect account or password. Please check your input."}"""),
      LogtoPasswordFlow.Failure.InvalidCredentials
    )
    assertEquals(
      LogtoPasswordFlow.classifyFailure("verification/password", 422, """{"code":"session.user_not_found"}"""),
      LogtoPasswordFlow.Failure.InvalidCredentials
    )
    assertEquals(
      LogtoPasswordFlow.classifyFailure("submit", 429, """{"code":"session.too_many_attempts"}"""),
      LogtoPasswordFlow.Failure.RateLimited
    )
    assertEquals(
      LogtoPasswordFlow.classifyFailure("authorize", 0, "connect timed out"),
      LogtoPasswordFlow.Failure.Transport("connect timed out")
    )
    LogtoPasswordFlow.classifyFailure("experience", 405, "Method Not Allowed") match
      case LogtoPasswordFlow.Failure.Protocol(step, code, _) =>
        assertEquals(step, "experience")
        assertEquals(code, "")
      case other => fail(s"expected Protocol, got $other")
  }

  test("parseVerification / parseSubmit: 契约字段缺失即 Protocol") {
    assertEquals(LogtoPasswordFlow.parseVerification("""{"verificationId":"v-1"}"""), Right("v-1"))
    assert(LogtoPasswordFlow.parseVerification("""{"other":1}""").isLeft)
    assertEquals(LogtoPasswordFlow.parseSubmit("""{"redirectTo":"https://auth.example/oidc/auth/resume"}""").isRight, true)
    assert(LogtoPasswordFlow.parseSubmit("""{"redirectTo":""}""").isLeft)
  }

  // ── C. 整链 happy path（fake 脚本 = 实探的逐腿形态） ─────────────────────

  test("signIn happy path: 交互挂载→密码识别→identification→submit→取码→换票；cookie 全程携带") {
    val script = ListBuffer.empty[(String, LogtoTransport.Request, String, Fake)]
    var capturedTokenBody = ""
    val fake: Fake = (req, jar) =>
      if req.url.endsWith("/oidc/auth?") || req.url.contains("/oidc/auth?") then
        LogtoPasswordFlow.Resp(303, List("_logto=L1; Path=/", "_interaction=I1; Path=/"), Some("/sign-in?app_id=x"), "")
      else if req.url.endsWith("/api/experience") && req.method == "PUT" then
        LogtoPasswordFlow.Resp(204, Nil, None, "")
      else if req.url.endsWith("/api/experience/verification/password") then
        // 请求体必须带 identifier:{type,value} + password；cookie jar 必须已带上交互 cookie
        assert(req.body.contains("\"type\":\"email\""), req.body)
        assert(req.body.contains("kai@example.com"), req.body)
        assert(req.body.contains(password), "verification 请求体必须携带密码（且仅此处）")
        assert(jar.contains("_interaction=I1"), s"jar 应携带交互 cookie: $jar")
        LogtoPasswordFlow.Resp(200, Nil, None, """{"verificationId":"v-9"}""")
      else if req.url.endsWith("/api/experience/identification") then
        assert(req.body.contains("\"verificationId\":\"v-9\""), req.body)
        LogtoPasswordFlow.Resp(200, Nil, None, "")
      else if req.url.endsWith("/api/experience/submit") then
        LogtoPasswordFlow.Resp(200, Nil, None, """{"redirectTo":"https://auth.example/oidc/auth/resume?xyz"}""")
      else if req.url.contains("/oidc/auth/resume") then
        // 302/303 的 Location 由外层 send2 回读 state 后构造（state 由实现随机生成）。
        LogtoPasswordFlow.Resp(303, Nil, None, "")
      else if req.url.endsWith("/oidc/token") then
        capturedTokenBody = req.body
        LogtoPasswordFlow.Resp(200, Nil, None, """{"access_token":"at-1","token_type":"Bearer","id_token":"eyJhbGciOiJIUzI1NiJ9.eyJwaWN0dXJlIjoiaHR0cHM6Ly9hdi9wLmpwZyJ9.sig"}""")
      else LogtoPasswordFlow.Resp(500, Nil, None, "unexpected")

    // state 由实现随机生成 —— 外层 send2 从 authorize URL 回读 state，回调 Location
    // 携带同一 state（等价 provider 行为），使 happy path 的 state 校验可通过。
    val seenState = new StringBuilder
    val send2: LogtoPasswordFlow.Send = (req, jar) =>
      IO.delay {
        script += (("step", req, jar, fake))
        if req.url.contains("/oidc/auth?client_id=") && req.url.contains("state=") then
          val st = req.url.split("state=")(1).takeWhile(_ != '&')
          seenState.clear(); seenState.append(st)
          fake(req, jar)
        else if req.url.contains("/oidc/auth/resume") then
          LogtoPasswordFlow.Resp(303, Nil, Some(s"$redirectUri?code=the-code&state=$seenState"), "")
        else fake(req, jar)
      }

    val result = LogtoPasswordFlow.signIn(send2)(endpoint, clientId, redirectUri, "email", account, password).unsafeRunSync()
    result match
      case Right(tokens) =>
        assertEquals(tokens.accessToken, "at-1")
        assert(capturedTokenBody.contains("grant_type=authorization_code"))
        assert(capturedTokenBody.contains("code=the-code"))
        assert(capturedTokenBody.contains("redirect_uri="))
        assert(capturedTokenBody.contains("code_verifier="), "token 交换必须带 PKCE verifier")
      case Left(f) => fail(s"expected success, got $f")
    // 六腿齐全：authorize → PUT experience → verification → identification → submit → resume → token
    val urls = script.map(_._2.url).toList
    assert(urls.exists(_.contains("/oidc/auth?")), urls.toString)
    assert(urls.exists(u => u.endsWith("/api/experience")), urls.toString)
    assert(urls.exists(_.endsWith("/api/experience/verification/password")), urls.toString)
    assert(urls.exists(_.endsWith("/api/experience/identification")), urls.toString)
    assert(urls.exists(_.endsWith("/api/experience/submit")), urls.toString)
    assert(urls.exists(_.contains("/oidc/auth/resume")), urls.toString)
    assert(urls.exists(_.endsWith("/oidc/token")), urls.toString)
  }

  // ── D. 失败链路 ────────────────────────────────────────────────────────

  test("signIn: 密码错 ⇒ InvalidCredentials；任何 Failure 载荷零回显（不含密码）") {
    val send: LogtoPasswordFlow.Send = (req, jar) =>
      IO.delay {
        if req.url.contains("/oidc/auth?client_id=") then
          LogtoPasswordFlow.Resp(303, List("_interaction=I1"), Some("/sign-in"), "")
        else if req.url.endsWith("/api/experience") then LogtoPasswordFlow.Resp(204, Nil, None, "")
        else if req.url.endsWith("/api/experience/verification/password") then
          LogtoPasswordFlow.Resp(422, Nil, None, """{"code":"session.invalid_credentials","message":"Incorrect account or password. Please check your input."}""")
        else LogtoPasswordFlow.Resp(500, Nil, None, "unexpected")
      }
    val result = LogtoPasswordFlow.signIn(send)(endpoint, clientId, redirectUri, "email", account, password).unsafeRunSync()
    assertEquals(result, Left(LogtoPasswordFlow.Failure.InvalidCredentials))
    // 零回显：Failure 的全部可打印面都不含密码。
    val rendered = result.left.toOption.map(_.toString).getOrElse("")
    assert(!rendered.contains(password), s"Failure 载荷不得回显密码: $rendered")
  }

  test("signIn: state 不匹配（CSRF 判据）⇒ Protocol(redirect)；回调无 code ⇒ Protocol") {
    def sendWithLocation(loc: Option[String]): LogtoPasswordFlow.Send = (req, jar) =>
      IO.delay {
        if req.url.contains("/oidc/auth?client_id=") then LogtoPasswordFlow.Resp(303, List("_interaction=I1"), Some("/sign-in"), "")
        else if req.url.endsWith("/api/experience") then LogtoPasswordFlow.Resp(204, Nil, None, "")
        else if req.url.endsWith("/api/experience/verification/password") then LogtoPasswordFlow.Resp(200, Nil, None, """{"verificationId":"v-9"}""")
        else if req.url.endsWith("/api/experience/identification") then LogtoPasswordFlow.Resp(200, Nil, None, "")
        else if req.url.endsWith("/api/experience/submit") then LogtoPasswordFlow.Resp(200, Nil, None, """{"redirectTo":"https://auth.example/oidc/auth/resume?x=1"}""")
        else if req.url.contains("/oidc/auth/resume") then LogtoPasswordFlow.Resp(303, Nil, loc, "")
        else LogtoPasswordFlow.Resp(500, Nil, None, "unexpected")
      }
    val mismatch = LogtoPasswordFlow.signIn(sendWithLocation(Some(s"$redirectUri?code=c&state=WRONG")))(endpoint, clientId, redirectUri, "email", account, password).unsafeRunSync()
    mismatch match
      case Left(LogtoPasswordFlow.Failure.Protocol("redirect", _, m)) => assert(m.contains("state"))
      case other => fail(s"expected redirect Protocol failure, got $other")
    val noCode = LogtoPasswordFlow.signIn(sendWithLocation(Some(s"$redirectUri?state=whatever")))(endpoint, clientId, redirectUri, "email", account, password).unsafeRunSync()
    assert(noCode.isLeft)
    val noLocation = LogtoPasswordFlow.signIn(sendWithLocation(None))(endpoint, clientId, redirectUri, "email", account, password).unsafeRunSync()
    noLocation match
      case Left(LogtoPasswordFlow.Failure.Protocol("redirect", _, m)) => assert(m.contains("Location"))
      case other => fail(s"expected missing-Location failure, got $other")
  }

  test("signIn: authorize 腿失败（非 2xx/3xx）⇒ classify 落 Protocol；传输层 0 ⇒ Transport") {
    val sendFail: LogtoPasswordFlow.Send = (_, _) => IO.pure(LogtoPasswordFlow.Resp(503, Nil, None, "down"))
    LogtoPasswordFlow.signIn(sendFail)(endpoint, clientId, redirectUri, "email", account, password).unsafeRunSync() match
      case Left(LogtoPasswordFlow.Failure.Protocol("authorize", _, _)) => ()
      case other => fail(s"expected authorize Protocol failure, got $other")
    val sendDead: LogtoPasswordFlow.Send = (_, _) => IO.pure(LogtoPasswordFlow.Resp(0, Nil, None, "connection refused"))
    LogtoPasswordFlow.signIn(sendDead)(endpoint, clientId, redirectUri, "email", account, password).unsafeRunSync() match
      case Left(LogtoPasswordFlow.Failure.Transport("connection refused")) => ()
      case other => fail(s"expected Transport failure, got $other")
  }

end LogtoPasswordFlowSpec
