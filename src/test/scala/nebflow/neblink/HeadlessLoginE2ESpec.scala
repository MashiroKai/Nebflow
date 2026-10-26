package nebflow.neblink

import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * 无头登录 E2E（台账 #924 spec 面）：`LogtoPasswordFlow.signIn` 对**真实部署租户**
 * 的整链验证 —— 交互挂载 → 密码识别 → identification → submit → 取码 → 换票。
 *
 * 🔴 测试账号纪律（作者令）：**绝不允许**作者真实凭据出现在任何驱动面。凭据只从
 * 环境变量读入（作者以专用测试账号注入），缺省（未设环境变量）= skip 并留置说明
 * —— 本 spec 的默认形态是「就绪待账号」，不是「已验过」。
 *
 * 运行形态（作者侧）:
 *   STAND_E2E_LOGTO_ACCOUNT=... STAND_E2E_LOGTO_PASSWORD=... sbt 'testOnly *HeadlessLoginE2ESpec'
 * 可选: STAND_E2E_LOGTO_ENDPOINT（缺省 = 内嵌默认 https://auth.nebflow.space）
 *       STAND_E2E_LOGTO_CLIENT_ID（缺省 = 内嵌默认 AC app id）
 *
 * 注册腿（/api/device/register）不在本 spec 范围：它会向生产中继写设备记录 ——
 * E2E 只验到 token 换取为止；register+persist 由 LoginCommandSpec（注入面）与
 * 既有 RelayAuthFixtureServer 链覆盖。
 */
class HeadlessLoginE2ESpec extends FunSuite:

  private def env(n: String): Option[String] =
    Option(System.getenv(n)).map(_.trim).filter(_.nonEmpty)

  test("headless password login E2E against the deployed tenant (gated on test-account env)") {
    val account = env("STAND_E2E_LOGTO_ACCOUNT")
    val password = env("STAND_E2E_LOGTO_PASSWORD")
    // 就绪待账号形态：缺测试账号 = 显式 cancel（不是通过、不是失败）。
    assume(
      account.isDefined && password.isDefined,
      "[headless-login-e2e] SKIP — set STAND_E2E_LOGTO_ACCOUNT / STAND_E2E_LOGTO_PASSWORD " +
        "with a DEDICATED TEST ACCOUNT (never author credentials) to run the live chain."
    )
    val endpoint = env("STAND_E2E_LOGTO_ENDPOINT").getOrElse("https://auth.nebflow.space")
    val clientId = env("STAND_E2E_LOGTO_CLIENT_ID").getOrElse("csxh16cas0x03bgk6w7ej")
    // RFC 8252 loopback：端口无关（部署租户接受任意本机端口，2026-08-28 实证）。
    val redirectUri = "http://127.0.0.1:6138/auth/callback"
    val result = LogtoPasswordFlow
      .signIn(LogtoPasswordFlow.jdkSend)(
        endpoint,
        clientId,
        redirectUri,
        LogtoPasswordFlow.identifierTypeOf(account.get),
        account.get,
        password.get
      )
      .unsafeRunSync()
    result match
      case Right(tokens) =>
        assert(tokens.accessToken.nonEmpty, "E2E 应换到非空 access token")
        println("[headless-login-e2e] PASS — token exchange succeeded (register leg intentionally NOT exercised)")
      case Left(LogtoPasswordFlow.Failure.InvalidCredentials) =>
        fail("E2E 失败：测试账号凭据被拒（检查 STAND_E2E_LOGTO_ACCOUNT/PASSWORD 是否为专用测试账号）")
      case Left(f) => fail(s"E2E 失败：$f")
  }

end HeadlessLoginE2ESpec
