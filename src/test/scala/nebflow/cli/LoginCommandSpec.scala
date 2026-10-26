package nebflow.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import scala.collection.mutable.ListBuffer

/**
 * headless 登录三命令的判据面（#924 统一登录批：无头密码登录 + 稳定码闭集 + 文案
 * 逐字节 + 整链注入直测）。
 *
 * 本件是**逐字节比对仪器**：H1/H3/H4/H5 的期望值直接取自作者批件逐字（与实现件里
 * 的常量是两份独立副本 —— 实现漂移即红）。全部断言为纯函数面/注入面：不碰网络、
 * 不碰 home、不起进程、**不读真实 stdin**。
 */
class LoginCommandSpec extends FunSuite:

  private def json(s: String): Json = io.circe.parser.parse(s).fold(e => throw e, identity)

  // ── A. H1 / H4 / H5：三命令帮助行逐字（含空格对齐） ────────────────────────

  test("H1/H4/H5 逐字节 == 批件逐字（期望值独立于实现件的第二份副本）") {
    // 2026-09 stand 微批：H1 描述位品牌词 Nebflow → Branding.productName（brand.conf
    // 现值 stand）渲染后的逐字；命令位/对齐空格不变。#924 批：命令语义换无头密码
    // 登录，帮助行逐字**不变**（描述位本就与登录方式无关）。
    assertEquals(LoginCopy.H1LoginHelp, "stand login                        Sign in to your stand account")
    assertEquals(
      LoginCopy.H4LogoutHelp,
      "stand logout                       Sign out and remove the stored device credential"
    )
    assertEquals(
      LoginCopy.H5WhoamiHelp,
      "stand whoami                        Show the signed-in account and device status"
    )
  }

  test("H 行 = `stand <cmd>` + 批件对齐空格 + 描述位（2026-09 stand 微批后列 35/35/36）") {
    assertEquals(LoginCopy.H1LoginHelp, "stand login" + " " * 24 + LoginCopy.SignInDesc)
    assertEquals(LoginCopy.H4LogoutHelp, "stand logout" + " " * 23 + LoginCopy.SignOutDesc)
    assertEquals(LoginCopy.H5WhoamiHelp, "stand whoami" + " " * 24 + LoginCopy.WhoamiDesc)
    assertEquals(LoginCopy.H1LoginHelp.length - LoginCopy.SignInDesc.length, 35)
    assertEquals(LoginCopy.H4LogoutHelp.length - LoginCopy.SignOutDesc.length, 35)
    assertEquals(LoginCopy.H5WhoamiHelp.length - LoginCopy.WhoamiDesc.length, 36)
  }

  test("描述位无首尾空白、非空（防把对齐空格串进 description）") {
    for d <- List(LoginCopy.SignInDesc, LoginCopy.SignOutDesc, LoginCopy.WhoamiDesc) do
      assertEquals(d.trim, d)
      assert(d.nonEmpty)
  }

  // ── B. 注册表：三命令在册、描述即已批描述位、login 无设备码子命令 ──────────

  test("CommandRegistry 三命令在册且 description == 已批描述位") {
    assertEquals(CommandRegistry.get("login").map(_.description), Some(LoginCopy.SignInDesc))
    assertEquals(CommandRegistry.get("logout").map(_.description), Some(LoginCopy.SignOutDesc))
    assertEquals(CommandRegistry.get("whoami").map(_.description), Some(LoginCopy.WhoamiDesc))
  }

  test("三命令各恰一个子命令（裸 `stand login` 因此可直接执行）；login 参数面为空（--device-code 已退役）") {
    for n <- List("login", "logout", "whoami") do assertEquals(CommandRegistry.get(n).map(_.subcommands.size), Some(1))
    assertEquals(CommandRegistry.get("login").map(_.subcommands.flatMap(_.params)), Some(Nil))
    assertEquals(CommandRegistry.get("login").map(_.examples), Some(List("stand login")))
  }

  // ── C. 交互提示文案（#924 新增；stderr 通道） ────────────────────────────

  test("提示文案：账号/密码提示非空、以空格收尾；无终端警告如实（不谎称隐藏）") {
    assert(LoginCopy.PromptAccount.nonEmpty && LoginCopy.PromptAccount.endsWith(" "))
    assert(LoginCopy.PromptPassword.nonEmpty && LoginCopy.PromptPassword.endsWith(" "))
    assert(LoginCopy.PromptNonInteractiveWarning.contains("will not be hidden"))
    // 退役面：提示文案族里不得再有任何设备码字样
    val all = List(LoginCopy.PromptAccount, LoginCopy.PromptPassword, LoginCopy.PromptNonInteractiveWarning)
    for s <- all do
      assert(!s.toLowerCase.contains("code"), s"提示文案不得出现 code 字样: $s")
  }

  // ── D. H3：成功 / 失败行逐字（em dash U+2014） ─────────────────────────────

  test("H3 成功行逐字") {
    assertEquals(LoginCopy.signedInLine("kai@example.com"), "Signed in as kai@example.com.")
  }

  test("H3 失败行逐字（含稳定码位）") {
    assertEquals(
      LoginCopy.signInFailedLine(SignInFailure.InvalidCredentials.reason, SignInFailure.InvalidCredentials.code),
      "Sign-in failed: wrong account or password (code: invalid_credentials) — run 'stand login' to retry."
    )
    val line = LoginCopy.signInFailedLine("x", "y")
    assertEquals(line, "Sign-in failed: x (code: y) — run 'stand login' to retry.")
    assert(line.contains("\u2014"), "em dash 必须是 U+2014")
  }

  // ── E. 稳定码闭集（新集 = 无头密码登录出口；设备码码位已退役） ──────────────

  test("稳定码闭集：码非空、唯一、reason 非空、全为 snake_case") {
    val codes = SignInFailure.all.map(_.code)
    assertEquals(codes.distinct.size, codes.size, "码不得重复")
    assertEquals(SignInFailure.all.map(_.reason).distinct.size, SignInFailure.all.size, "reason 不得重复")
    for f <- SignInFailure.all do
      assert(f.code.nonEmpty && f.reason.nonEmpty)
      assert(f.code.matches("[a-z0-9_]+"), s"码须为 snake_case: ${f.code}")
  }

  test("闭集关键成员在位；设备码时代码位已退役（不再出现在闭集里）") {
    val codes = SignInFailure.all.map(_.code).toSet
    for expected <- List("invalid_credentials", "rate_limited", "network_error", "neblink_unavailable", "login_failed", "status_unreadable", "sign_out_failed") do
      assert(codes.contains(expected), s"缺 $expected")
    for retired <- List("expired_token", "access_denied", "flow_start_failed", "poll_failed", "timeout") do
      assert(!codes.contains(retired), s"设备码码位 $retired 应已退役")
  }

  // ── F. 分类面（网关包装剥离 + 语境兜底码） ─────────────────────────────────

  /**
   * 用实现件自己的包装器造报文 —— 若 `GatewayClient.requestError` 的形态变了，
   * 本 spec 会同时暴露契约漂移（不是只测一个手抄字符串）。
   */
  private def wrapped(code: Int, body: String): Throwable =
    new RuntimeException(GatewayClient.requestError(code, body))

  test("failureOfDetail：网关结构化 code → 闭集（invalid_credentials / rate_limited / logto-unavailable）") {
    assertEquals(
      LoginFlow.failureOfDetail("""{"error":"Invalid account or password","code":"invalid_credentials"}"""),
      SignInFailure.InvalidCredentials
    )
    assertEquals(LoginFlow.failureOfDetail("""{"error":"Too many attempts","code":"rate_limited"}"""), SignInFailure.RateLimited)
    assertEquals(LoginFlow.failureOfDetail("logto-not-configured"), SignInFailure.LogtoUnavailable)
    assertEquals(LoginFlow.failureOfDetail("""{"code":"transport_failed"}"""), SignInFailure.TransportFailed)
    assertEquals(LoginFlow.failureOfDetail("""{"code":"protocol_error","step":"submit"}"""), SignInFailure.ProtocolError)
  }

  test("failureOfDetail：register 机器闸 reason → register_failed；NebLink 未启用 → neblink_unavailable；未识别 → 兜底码") {
    assertEquals(LoginFlow.failureOfDetail("""{"code":"device_bound_other_account"}"""), SignInFailure.RegisterFailed)
    assertEquals(LoginFlow.failureOfDetail("""{"code":"device_already_logged_in"}"""), SignInFailure.RegisterFailed)
    assertEquals(LoginFlow.failureOfDetail("""{"code":"register_failed"}"""), SignInFailure.RegisterFailed)
    assertEquals(LoginFlow.failureOfDetail("NebLink not enabled"), SignInFailure.NeblinkUnavailable)
    assertEquals(LoginFlow.failureOfDetail("NebLink service not initialized"), SignInFailure.NeblinkUnavailable)
    assertEquals(LoginFlow.failureOfDetail("something new"), SignInFailure.LoginFailed)
  }

  test("requestError 包装带稳定码后缀（#924 契约：机器面不丢）且无 code 时与旧形态逐字一致") {
    val w = GatewayClient.requestError(401, """{"error":"Invalid account or password","code":"invalid_credentials"}""")
    assert(w.contains("Invalid account or password"), w)
    assert(w.contains("(code: invalid_credentials)"), w)
    assertEquals(GatewayClient.requestError(404, """{"error":"boom"}"""), "Gateway request failed (HTTP 404): boom")
  }

  test("failureOfMessage：有包装按 detail 分类，未识别落调用面兜底码；无包装落 network_error") {
    assertEquals(
      LoginFlow.failureOfMessage(
        Some(GatewayClient.requestError(401, """{"error":"Invalid account or password","code":"invalid_credentials"}""")),
        SignInFailure.LoginFailed
      ),
      SignInFailure.InvalidCredentials
    )
    assertEquals(
      LoginFlow.failureOfMessage(
        Some(GatewayClient.requestError(400, """{"error":"something new"}""")),
        SignInFailure.LoginFailed
      ),
      SignInFailure.LoginFailed
    )
    assertEquals(
      LoginFlow.failureOfMessage(Some("Connection refused"), SignInFailure.LoginFailed),
      SignInFailure.NetworkError
    )
    assertEquals(LoginFlow.failureOfMessage(None, SignInFailure.LoginFailed), SignInFailure.NetworkError)
  }

  // ── G. 状态面：解析 + 账号标签降级链 ───────────────────────────────────────

  private val statusJson =
    """{"loggedIn":true,"device":{"id":"dev-1","name":"host-a","platform":"linux",
      "email":"kai@example.com","displayName":"Kai"}}"""

  test("parseStatus：既有 status 形状 → 读数子集") {
    val got = LoginFlow.parseStatus(json(statusJson)).toOption.get
    assertEquals(got.loggedIn, true)
    assertEquals(got.email, "kai@example.com")
    assertEquals(got.displayName, "Kai")
    assertEquals(got.deviceId, "dev-1")
    assertEquals(got.deviceName, "host-a")
    assertEquals(got.platform, "linux")
  }

  test("parseStatus：缺 loggedIn（或非对象）⇒ Left(status_unreadable)，不读成「未登录」") {
    assertEquals(LoginFlow.parseStatus(json("""{"device":{"id":"d"}}""")), Left(SignInFailure.StatusUnreadable))
    assertEquals(LoginFlow.parseStatus(json("""[]""")), Left(SignInFailure.StatusUnreadable))
    assertEquals(LoginFlow.parseStatus(Json.Null), Left(SignInFailure.StatusUnreadable))
  }

  test("parseStatus：loggedIn=false 保留（未登录是合法读数，不是错误）") {
    val got = LoginFlow.parseStatus(json("""{"loggedIn":false}""")).toOption.get
    assertEquals(got.loggedIn, false)
    assertEquals(got.email, "")
  }

  test("账号标签降级链：email → displayName → deviceId") {
    def st(email: String, name: String, id: String) =
      AccountStatus(true, email, name, id, "n", "p")
    assertEquals(LoginFlow.accountLabel(st("e@x", "Kai", "d1")), "e@x")
    assertEquals(LoginFlow.accountLabel(st("", "Kai", "d1")), "Kai")
    assertEquals(LoginFlow.accountLabel(st("", "", "d1")), "d1")
  }

  // ── H. signInWith 整链（注入面直测：输入腿 + 出站腿） ─────────────────────

  private val PostPath = LoginFlow.LoginPath
  private val password = "pw-s3cret-Ø-zero-echo"

  /** Injection bundle: captured requests + scripted replies. */
  private class PostFake(replies: PartialFunction[(String, Json), Either[Throwable, Json]]):
    val calls = ListBuffer.empty[(String, Json)]
    def post: (String, Json) => IO[Either[Throwable, Json]] = (path, body) =>
      IO {
        calls += ((path, body))
        replies.lift((path, body)).getOrElse(Left(new RuntimeException("no script for " + path)))
      }

  test("signInWith happy path：POST 既有登录面（账号+密码进体）→ 读状态 → H3 成功行逐字") {
    val fake = PostFake {
      case (p, _) if p == PostPath => Right(json("""{"ok":true,"networkId":"net-1"}"""))
      case (p, _) if p == LoginFlow.StatusPath => Right(json(statusJson))
    }
    val result = LoginFlow.signInWith(
      CliContext.offline(),
      fake.post,
      IO.pure(Some("kai@example.com")),
      IO.pure(Some(password))
    ).unsafeRunSync()
    assertEquals(fake.calls.map(_._1).toList, List(PostPath, LoginFlow.StatusPath))
    val body = fake.calls.head._2
    assertEquals(body.hcursor.downField("account").as[String].toOption, Some("kai@example.com"))
    assertEquals(body.hcursor.downField("password").as[String].toOption, Some(password))
    result match
      case CliResult.Text(lines) => assertEquals(lines, List("Signed in as kai@example.com."))
      case other => fail(s"expected H3 text, got $other")
  }

  test("signInWith --json 成功面：{signedIn:true,account}") {
    val fake = PostFake {
      case (p, _) if p == PostPath => Right(json("""{"ok":true}"""))
      case (p, _) if p == LoginFlow.StatusPath => Right(json(statusJson))
    }
    val ctx = CliContext.offline().copy(json = true)
    LoginFlow.signInWith(ctx, fake.post, IO.pure(Some("kai@example.com")), IO.pure(Some(password)))
      .unsafeRunSync() match
      case CliResult.Json(j) =>
        assertEquals(j.hcursor.downField("signedIn").as[Boolean].toOption, Some(true))
        assertEquals(j.hcursor.downField("account").as[String].toOption, Some("kai@example.com"))
      case other => fail(s"expected json face, got $other")
  }

  test("signInWith：invalid_credentials / rate_limited / 网关不可达 ⇒ 各自稳定码（H3 失败行）") {
    def run(reply: Either[Throwable, Json]): CliResult =
      val fake = PostFake { case _ => reply }
      LoginFlow.signInWith(CliContext.offline(), fake.post, IO.pure(Some("a@b.c")), IO.pure(Some(password)))
        .unsafeRunSync()

    run(Left(wrapped(401, """{"error":"Invalid account or password","code":"invalid_credentials"}"""))) match
      case CliResult.Error(msg, 1) =>
        assert(msg.contains("(code: invalid_credentials)"), msg)
        assert(!msg.contains(password), "失败行不得回显密码")
      case other => fail(s"expected H3 failure, got $other")

    run(Left(wrapped(429, """{"error":"Too many attempts, retry later","code":"rate_limited"}"""))) match
      case CliResult.Error(msg, 1) => assert(msg.contains("(code: rate_limited)"), msg)
      case other => fail(s"expected rate_limited, got $other")

    run(Left(new java.net.ConnectException("Connection refused"))) match
      case CliResult.Error(msg, 1) => assert(msg.contains("(code: network_error)"), msg)
      case other => fail(s"expected network_error, got $other")
  }

  test("signInWith：空账号 / 空密码 / EOF ⇒ no_input 且**零出站**（POST 不发生）") {
    val fake = PostFake { case _ => Right(json("""{"ok":true}""")) }
    def run(account: Option[String], pw: Option[String]): CliResult =
      LoginFlow.signInWith(CliContext.offline(), fake.post, IO.pure(account), IO.pure(pw)).unsafeRunSync()
    for (a, p) <- List[(Option[String], Option[String])](
      (Some(""), Some(password)),
      (None, Some(password)),
      (Some("a@b.c"), Some("")),
      (Some("a@b.c"), None)
    ) do
      run(a, p) match
        case CliResult.Error(msg, 1) => assert(msg.contains("(code: no_input)"), msg)
        case other => fail(s"expected no_input, got $other")
    assertEquals(fake.calls.size, 0, "no_input 出口必须零出站")
  }

  test("signInWith：register 机器闸 409 透传分类 ⇒ register_failed 稳定码") {
    val fake = PostFake { case _ =>
      Left(wrapped(409, """{"error":"device bound to another account","code":"device_bound_other_account"}"""))
    }
    LoginFlow.signInWith(CliContext.offline(), fake.post, IO.pure(Some("a@b.c")), IO.pure(Some(password)))
      .unsafeRunSync() match
      case CliResult.Error(msg, 1) => assert(msg.contains("(code: register_failed)"), msg)
      case other => fail(s"expected register_failed, got $other")
  }

  // ── I. 其余两个命令的行面 ────────────────────────────────────────────────

  test("whoami 未登录文案可行动（含 `stand login`）、logout 行非空、设备行有空值兜底") {
    assert(LoginCopy.NotSignedIn.contains("stand login"))
    assert(LoginCopy.SignedOut.nonEmpty)
    assertEquals(LoginCopy.deviceLine("host-a", "linux"), "Device: host-a (linux)")
    assertEquals(LoginCopy.deviceLine("", ""), "Device: unknown (unknown)")
  }

  test("非登录面的失败行也带稳定码（同一闭集词汇）") {
    assert(LoginCopy.statusFailedLine("r", "c").contains("(code: c)"))
    assert(LoginCopy.signOutFailedLine("r", "c").contains("(code: c)"))
  }

end LoginCommandSpec
