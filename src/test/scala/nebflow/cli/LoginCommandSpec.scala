package nebflow.cli

import io.circe.Json
import munit.FunSuite

/**
 * headless 登录三命令的判据面（§16 文案逐字节 + 设备码流三出口 + 稳定码闭集）。
 *
 * 本件是**逐字节比对仪器**：H1–H5 的期望值直接取自作者批件逐字（与实现件里的常量
 * 是两份独立副本 —— 实现漂移即红），故 H 系「逐字含空格对齐」不靠人眼。
 *
 * 全部断言为纯函数面：不碰网络、不碰 home、不起进程。
 */
class LoginCommandSpec extends FunSuite:

  private def json(s: String): Json = io.circe.parser.parse(s).fold(e => throw e, identity)

  // ── A. H1 / H4 / H5：三命令帮助行逐字（含空格对齐） ────────────────────────

  test("H1/H4/H5 逐字节 == 批件逐字（期望值独立于实现件的第二份副本）") {
    assertEquals(LoginCopy.H1LoginHelp, "nebflow login                        Sign in to your Nebflow account")
    assertEquals(
      LoginCopy.H4LogoutHelp,
      "nebflow logout                       Sign out and remove the stored device credential"
    )
    assertEquals(
      LoginCopy.H5WhoamiHelp,
      "nebflow whoami                        Show the signed-in account and device status"
    )
  }

  test("H 行 = `nebflow <cmd>` + 批件对齐空格 + 描述位（列 37/37/38，批件原文）") {
    assertEquals(LoginCopy.H1LoginHelp, "nebflow login" + " " * 24 + LoginCopy.SignInDesc)
    assertEquals(LoginCopy.H4LogoutHelp, "nebflow logout" + " " * 23 + LoginCopy.SignOutDesc)
    assertEquals(LoginCopy.H5WhoamiHelp, "nebflow whoami" + " " * 24 + LoginCopy.WhoamiDesc)
    // H5 比 H1/H4 多一空格 = 批件原文如此，此前已按「逐字不校正」保留并在报告单列呈报
    assertEquals(LoginCopy.H1LoginHelp.length - LoginCopy.SignInDesc.length, 37)
    assertEquals(LoginCopy.H4LogoutHelp.length - LoginCopy.SignOutDesc.length, 37)
    assertEquals(LoginCopy.H5WhoamiHelp.length - LoginCopy.WhoamiDesc.length, 38)
  }

  test("描述位无首尾空白、非空（防把对齐空格串进 description）") {
    for d <- List(LoginCopy.SignInDesc, LoginCopy.SignOutDesc, LoginCopy.WhoamiDesc) do
      assertEquals(d.trim, d)
      assert(d.nonEmpty)
  }

  // ── B. 注册表：三命令在册、描述即已批描述位 ────────────────────────────────

  test("CommandRegistry 三命令在册且 description == 已批描述位") {
    assertEquals(CommandRegistry.get("login").map(_.description), Some(LoginCopy.SignInDesc))
    assertEquals(CommandRegistry.get("logout").map(_.description), Some(LoginCopy.SignOutDesc))
    assertEquals(CommandRegistry.get("whoami").map(_.description), Some(LoginCopy.WhoamiDesc))
  }

  test("三命令各恰一个子命令（裸 `nebflow login` 因此可直接执行，不需子命令名）") {
    for n <- List("login", "logout", "whoami") do assertEquals(CommandRegistry.get(n).map(_.subcommands.size), Some(1))
  }

  // ── C. H2：设备码提示三行逐字 ────────────────────────────────────────────

  test("H2 三行逐字（地址位 = 静态品牌域，非响应值）") {
    assertEquals(
      LoginCopy.promptLines("ABCD-EFGH"),
      List(
        "Open https://nebflow.space/device on any device with a browser, then enter the code below.",
        "Code: ABCD-EFGH",
        "Waiting for approval... (Ctrl+C to cancel)"
      )
    )
    assertEquals(LoginCopy.DevicePromptCodePrefix, "Code: ")
  }

  test("H2 地址行硬编码品牌域（不随响应变化）且不含任何码字段名") {
    assert(LoginCopy.DevicePromptAddress.contains("https://nebflow.space/device"))
    for line <- LoginCopy.promptLines("WXYZ-1234") do
      assert(!line.contains("deviceCode"), s"H2 不得出现 deviceCode 字样: $line")
      assert(!line.contains("verificationUri"), s"H2 不得出现 verificationUri 字样: $line")
  }

  // ── D. H3：成功 / 失败行逐字（em dash U+2014） ─────────────────────────────

  test("H3 成功行逐字") {
    assertEquals(LoginCopy.signedInLine("kai@example.com"), "Signed in as kai@example.com.")
  }

  test("H3 失败行逐字（含稳定码位）") {
    assertEquals(
      LoginCopy.signInFailedLine(SignInFailure.ExpiredToken.reason, SignInFailure.ExpiredToken.code),
      "Sign-in failed: the code expired before it was approved (code: expired_token) — run 'nebflow login' to retry."
    )
    val line = LoginCopy.signInFailedLine("x", "y")
    assertEquals(line, "Sign-in failed: x (code: y) — run 'nebflow login' to retry.")
    assert(line.contains("\u2014"), "em dash 必须是 U+2014")
  }

  // ── E. 稳定码闭集 ───────────────────────────────────────────────────────

  test("稳定码闭集：码非空、唯一、reason 非空、全为 snake_case") {
    val codes = SignInFailure.all.map(_.code)
    assertEquals(codes.distinct.size, codes.size, "码不得重复")
    assertEquals(SignInFailure.all.map(_.reason).distinct.size, SignInFailure.all.size, "reason 不得重复")
    for f <- SignInFailure.all do
      assert(f.code.nonEmpty && f.reason.nonEmpty)
      assert(f.code.matches("[a-z0-9_]+"), s"码须为 snake_case: ${f.code}")
  }

  // ── F. 启动响应解析与夹紧 ────────────────────────────────────────────────

  test("parseStart：既有契约形状 → 句柄，interval/expiresIn 原值保留") {
    val got = LoginFlow.parseStart(
      json(
        """{"deviceCode":"dc-1","userCode":"ABCD-EFGH","verificationUri":"https://x/device","interval":5,"expiresIn":900}"""
      )
    )
    assertEquals(got.map(_.deviceCode), Some("dc-1"))
    assertEquals(got.map(_.userCode), Some("ABCD-EFGH"))
    assertEquals(got.map(_.verificationUri), Some("https://x/device"))
    assertEquals(got.map(_.intervalSec), Some(5))
    assertEquals(got.map(_.expiresInSec), Some(900))
  }

  test("parseStart：缺 deviceCode 或 userCode（或空串）⇒ None") {
    assertEquals(LoginFlow.parseStart(json("""{"userCode":"A-B"}""")), None)
    assertEquals(LoginFlow.parseStart(json("""{"deviceCode":"dc"}""")), None)
    assertEquals(LoginFlow.parseStart(json("""{"deviceCode":"","userCode":"A-B"}""")), None)
    assertEquals(LoginFlow.parseStart(json("""{"deviceCode":"dc","userCode":""}""")), None)
  }

  test("parseStart：退化 interval/expiresIn 被夹紧（0/负数走默认，超限走上限）") {
    assertEquals(LoginFlow.clampInterval(0), 5)
    assertEquals(LoginFlow.clampInterval(-3), 5)
    assertEquals(LoginFlow.clampInterval(1), 1)
    assertEquals(LoginFlow.clampInterval(9999), 60)
    assertEquals(LoginFlow.clampExpiresIn(0), 900)
    assertEquals(LoginFlow.clampExpiresIn(-1), 900)
    assertEquals(LoginFlow.clampExpiresIn(30), 30)
    assertEquals(LoginFlow.clampExpiresIn(99999), 3600)
    // 缺字段 ⇒ 契约默认值
    val got = LoginFlow.parseStart(json("""{"deviceCode":"dc","userCode":"A-B"}""")).get
    assertEquals(got.intervalSec, LoginFlow.DefaultIntervalSec)
    assertEquals(got.expiresInSec, LoginFlow.DefaultExpiresInSec)
  }

  // ── G. poll 三出口 ──────────────────────────────────────────────────────

  /**
   * 用实现件自己的包装器造报文 —— 若 `GatewayClient.requestError` 的形态变了，
   * 本 spec 会同时暴露契约漂移（不是只测一个手抄字符串）。
   */
  private def wrapped(code: Int, body: String): Throwable =
    new RuntimeException(GatewayClient.requestError(code, body))

  test("pollStep：2xx（ok/networkId）⇒ Approved") {
    assertEquals(LoginFlow.pollStep(Right(json("""{"ok":true,"networkId":"net-1"}"""))), PollStep.Approved)
    assertEquals(LoginFlow.pollStep(Right(json("""{"networkId":"net-1"}"""))), PollStep.Approved)
  }

  test("pollStep：2xx 但带 error ⇒ Failed(poll_failed)（防御性出口）") {
    assertEquals(LoginFlow.pollStep(Right(json("""{"error":"boom"}"""))), PollStep.Failed(SignInFailure.PollFailed))
  }

  test("pollStep：authorization_pending / slow_down ⇒ Pending（继续等）") {
    assertEquals(LoginFlow.pollStep(Left(wrapped(400, """{"error":"authorization_pending"}"""))), PollStep.Pending)
    assertEquals(LoginFlow.pollStep(Left(wrapped(400, """{"error":"slow_down"}"""))), PollStep.Pending)
  }

  test("pollStep：expired_token / access_denied ⇒ Failed（终态，稳定码取 RFC 8628 原名）") {
    assertEquals(
      LoginFlow.pollStep(Left(wrapped(400, """{"error":"expired_token"}"""))),
      PollStep.Failed(SignInFailure.ExpiredToken)
    )
    assertEquals(
      LoginFlow.pollStep(Left(wrapped(400, """{"error":"access_denied"}"""))),
      PollStep.Failed(SignInFailure.AccessDenied)
    )
  }

  test("pollStep：NebLink 未启用 ⇒ Failed(neblink_unavailable)") {
    assertEquals(
      LoginFlow.pollStep(Left(wrapped(404, """{"error":"NebLink not enabled"}"""))),
      PollStep.Failed(SignInFailure.NeblinkUnavailable)
    )
    assertEquals(
      LoginFlow.pollStep(Left(wrapped(400, """{"error":"NebLink service not initialized"}"""))),
      PollStep.Failed(SignInFailure.NeblinkUnavailable)
    )
  }

  test("pollStep：未识别的网关错误 ⇒ Failed(poll_failed)；无包装（传输层）⇒ network_error") {
    assertEquals(
      LoginFlow.pollStep(Left(wrapped(500, """{"error":"weird"}"""))),
      PollStep.Failed(SignInFailure.PollFailed)
    )
    assertEquals(
      LoginFlow.pollStep(Left(new java.net.ConnectException("Connection refused"))),
      PollStep.Failed(SignInFailure.NetworkError)
    )
  }

  // ── H. 分类面（网关包装剥离 + 语境兜底码） ─────────────────────────────────

  test("gatewayDetail：剥掉 GatewayClient 包装；非包装形态 ⇒ None") {
    assertEquals(LoginFlow.gatewayDetail("Gateway request failed (HTTP 400): expired_token"), Some("expired_token"))
    assertEquals(LoginFlow.gatewayDetail("Connection refused"), None)
    assertEquals(LoginFlow.gatewayDetail(""), None)
  }

  test("failureOfMessage：有包装按 detail 分类，未识别落调用面兜底码；无包装落 network_error") {
    val startWrapped = LoginFlow.gatewayDetail(GatewayClient.requestError(400, """{"error":"something new"}"""))
    assertEquals(startWrapped, Some("something new"))
    assertEquals(
      LoginFlow.failureOfMessage(
        Some(GatewayClient.requestError(400, """{"error":"something new"}""")),
        SignInFailure.FlowStartFailed
      ),
      SignInFailure.FlowStartFailed
    )
    assertEquals(
      LoginFlow.failureOfMessage(
        Some(GatewayClient.requestError(400, """{"error":"something new"}""")),
        SignInFailure.PollFailed
      ),
      SignInFailure.PollFailed
    )
    assertEquals(
      LoginFlow.failureOfMessage(Some("Connection refused"), SignInFailure.FlowStartFailed),
      SignInFailure.NetworkError
    )
    assertEquals(LoginFlow.failureOfMessage(None, SignInFailure.FlowStartFailed), SignInFailure.NetworkError)
  }

  // ── I. 状态面：解析 + 账号标签降级链 ───────────────────────────────────────

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

  test("账号标签降级链：email → displayName → deviceId（自建通路无 id_token 时落 deviceId）") {
    def st(email: String, name: String, id: String) =
      AccountStatus(true, email, name, id, "n", "p")
    assertEquals(LoginFlow.accountLabel(st("e@x", "Kai", "d1")), "e@x")
    assertEquals(LoginFlow.accountLabel(st("", "Kai", "d1")), "Kai")
    assertEquals(LoginFlow.accountLabel(st("", "", "d1")), "d1")
  }

  // ── J. 其余两个命令的行面 ────────────────────────────────────────────────

  test("whoami 未登录文案可行动（含 `nebflow login`）、logout 行非空、设备行有空值兜底") {
    assert(LoginCopy.NotSignedIn.contains("nebflow login"))
    assert(LoginCopy.SignedOut.nonEmpty)
    assertEquals(LoginCopy.deviceLine("host-a", "linux"), "Device: host-a (linux)")
    assertEquals(LoginCopy.deviceLine("", ""), "Device: unknown (unknown)")
  }

  test("非登录面的失败行也带稳定码（同一闭集词汇）") {
    assert(LoginCopy.statusFailedLine("r", "c").contains("(code: c)"))
    assert(LoginCopy.signOutFailedLine("r", "c").contains("(code: c)"))
  }

  test("心跳行带进度与上限（供等待腿 ≤30s 一行）") {
    assertEquals(LoginCopy.heartbeatLine(60, 900), "[login] waiting for approval — 60s / 900s")
  }

end LoginCommandSpec
