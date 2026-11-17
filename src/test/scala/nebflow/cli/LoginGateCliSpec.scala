package nebflow.cli

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite

import nebflow.core.root.{LoginGuard, Refusal}

import scala.collection.mutable.ListBuffer

/**
 * 三层数据根批 · b1-guards（tenant-full-flow-batch2）—— **T1 的 CLI 接线 spec**：
 * `stand login` 的登录前置门（`LoginGuard.checkLoginAllowed`）挂在 `LoginFlow.signInWith`。
 *
 * == 断言原文（派单 T1）==
 * 「同机 A 在跑（端口活实例+指针=A）时 B 登录必须拒，refusal 含 account-switch-requires-logout」。
 * 本件断言的是 **CLI 装配面**：门拒 ⇒ 零出站（POST 不发生）+ 退出码 1 + 守卫文案全透传
 * （点名两账号 + 先登出建议 + 底层连字符码随件）；门放行 ⇒ 登录链逐字节不破；
 * 输入校验（no_input）先于前置门（端口探测有 1.5s 连接超时，绝不让它在输入都没给时白跑）。
 *
 * == 红绿态登记（测试纪律：spec 先写、能出红态的先红后绿）==
 * 改前（现状）：`signInWith` 无门参数、`SignInFailure` 闭集无 `account_switch_requires_logout`
 * ⇒ 本件引用即 `Test/compile` 红（缺符号/参数，输出原样留证于提交信息）=「现状登录照常放行」的
 * 可编译证明；接线（闭集加码位 + signInWith 挂门）后转绿。
 *
 * == 守卫判定语义不在本件（归属）==
 * 判定逻辑只在 core/root/Guards.scala（守卫单点铁律）；守卫拒绝臂的全链语义（指针+活实例
 * ⇒ 拒）由 `nebflow.core.dataroot.LoginGuardLoginGateSpec` 用环回假活实例直驱守卫覆盖。
 *
 * 隔离纪律：纯注入面（门/POST/输入全注入），零真实端口、零机器根、不碰 `~/.stand`。
 */
class LoginGateCliSpec extends FunSuite:

  private def json(s: String): Json = io.circe.parser.parse(s).fold(e => throw e, identity)

  private val CurrentSub = "logto|user-A"
  private val TargetAccount = "b@example.com"
  private val password = "pw-s3cret-Ø"

  /** 注入 bundle：捕获出站 + 脚本化应答（与 LoginCommandSpec 同款独立副本）。 */
  private class PostFake(replies: PartialFunction[(String, Json), Either[Throwable, Json]]):
    val calls = ListBuffer.empty[(String, Json)]
    def post: (String, Json) => IO[Either[Throwable, Json]] = (path, body) =>
      IO {
        calls += ((path, body))
        replies.lift((path, body)).getOrElse(Left(new RuntimeException("no script for " + path)))
      }

  private val happyReply: PartialFunction[(String, Json), Either[Throwable, Json]] = {
    case (p, _) if p == LoginFlow.LoginPath  => Right(json("""{"ok":true}"""))
  }

  private val statusJson =
    """{"loggedIn":true,"device":{"id":"dev-1","name":"host-a","platform":"mac",
      "email":"b@example.com","displayName":"B"}}"""

  /** 守卫拒绝面（文案形态 = Guards.scala 逐字模板的等价串：双点名 + 先登出建议 + 码尾）。 */
  private val gateRefusal: Either[Refusal, Unit] = Left(
    Refusal(
      LoginGuard.LoginCode,
      s"This machine is already signed in as '$CurrentSub' (single instance, single port) — " +
        s"run 'stand logout' first, then 'stand login' to sign in as '$TargetAccount'. " +
        s"(${LoginGuard.LoginCode})"
    )
  )

  test("T1: 前置门拒 ⇒ 零出站 + exit 1 + 守卫文案全透传（双点名 + stand logout + 底层连字符码随件）"):
    val fake = PostFake(happyReply) // 若被调用即说明门没挡住
    val gate: String => IO[Either[Refusal, Unit]] = _ => IO.pure(gateRefusal)
    val result = LoginFlow
      .signInWith(CliContext.offline(), fake.post, IO.pure(Some(TargetAccount)), IO.pure(Some(password)), gate)
      .unsafeRunSync()
    assertEquals(fake.calls.size, 0, "前置门拒 ⇒ 登录 POST 绝不发生（现状放行 = 出站照发，接线后必须为零）")
    result match
      case CliResult.Error(line, 1) =>
        assert(line.contains("account_switch_requires_logout"), s"闭集稳定码随件: $line")
        assert(line.contains("account-switch-requires-logout"), s"底层连字符码随文案透传: $line")
        assert(line.contains("stand logout"), s"先登出建议必须可行动: $line")
        assert(line.contains(CurrentSub), s"文案点名机器在跑的账号: $line")
        assert(line.contains(TargetAccount), s"文案点名目标账号: $line")
      case other => fail(s"expected H3 failure (exit 1), got $other")

  test("T1: --json 面 ⇒ exit 1 + {error, code: account_switch_requires_logout}"):
    val fake = PostFake(happyReply)
    val gate: String => IO[Either[Refusal, Unit]] = _ => IO.pure(gateRefusal)
    val ctx = CliContext.offline().copy(json = true)
    LoginFlow
      .signInWith(ctx, fake.post, IO.pure(Some(TargetAccount)), IO.pure(Some(password)), gate)
      .unsafeRunSync() match
      case CliResult.Exit(1, rendered) =>
        assertEquals(fake.calls.size, 0, "json 面同样零出站")
        val j = io.circe.parser.parse(rendered).fold(e => throw e, identity)
        assertEquals(j.hcursor.downField("code").as[String].toOption, Some("account_switch_requires_logout"))
        assert(j.hcursor.downField("error").as[String].toOption.exists(_.contains("stand logout")))
      case other => fail(s"expected exit-1 json face, got $other")

  test("T1: 前置门放行 ⇒ 登录链逐字节不破（POST 照发 + H3 成功行）"):
    val fake = PostFake(happyReply)
    val gate: String => IO[Either[Refusal, Unit]] = _ => IO.pure(Right(()))
    val result = LoginFlow
      .signInWith(CliContext.offline(), fake.post, IO.pure(Some(TargetAccount)), IO.pure(Some(password)), gate,
        () => IO.pure(Right(json(statusJson))))
      .unsafeRunSync()
    assertEquals(fake.calls.map(_._1).toList, List(LoginFlow.LoginPath), "放行 ⇒ 登录 POST 照常")
    result match
      case CliResult.Text(lines) => assertEquals(lines, List("Signed in as b@example.com."))
      case other                 => fail(s"expected H3 text, got $other")

  test("T1: 输入校验先于前置门（空账号/空密码 ⇒ no_input 且门零调用、零出站）"):
    val fake = PostFake(happyReply)
    var gateCalls = 0
    val gate: String => IO[Either[Refusal, Unit]] = { _ =>
      gateCalls += 1
      IO.pure(gateRefusal)
    }
    for (a, p) <- List[(Option[String], Option[String])](
      (Some(""), Some(password)),
      (None, Some(password)),
      (Some(TargetAccount), Some("")),
      (Some(TargetAccount), None)
    ) do
      LoginFlow.signInWith(CliContext.offline(), fake.post, IO.pure(a), IO.pure(p), gate).unsafeRunSync() match
        case CliResult.Error(msg, 1) => assert(msg.contains("(code: no_input)"), msg)
        case other                   => fail(s"expected no_input, got $other")
    assertEquals(gateCalls, 0, "输入没给绝不让端口探测白跑（1.5s 连接超时）")
    assertEquals(fake.calls.size, 0)

  test("T1: 闭集新码位在册（码/reason 唯一且 snake_case，随既有闭集断言同规则）"):
    val codes = SignInFailure.all.map(_.code)
    assert(codes.contains("account_switch_requires_logout"), s"闭集缺新码位: $codes")
    assertEquals(codes.count(_ == "account_switch_requires_logout"), 1, "码不得重复")

end LoginGateCliSpec
