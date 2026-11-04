package nebflow.core.dataroot

import nebflow.core.root.{LoginGuard, OwnerGuard}

/**
 * 三层数据根批 · B1 第二腿 —— **验收 V2（owner 拒启）的红证据 spec**。
 *
 * == 编号口径（勿混）==
 * 本文件用**派单验收编号 V2** = 「指针=A、`credential.accountSub`=B ⇒ 拒启且文案点名两个 sub
 * + 行动建议，不得静默降级」。契约里的「V2 登录链」是**实现腿分组**，不是验收编号。
 *
 * == 断言原文（派单）==
 * 「V2（指针=A、credential.accountSub=B ⇒ 拒启且文案点名两个 sub + 行动建议，不得静默降级）」
 *
 * == 契约权威（design-api.md §5，符号名逐字，不得自创）==
 * - `OwnerGuard.check(accountSub, credentialSub): Either[Refusal, Unit]`；
 *   `Some(sub)` 且 `sub != accountSub` ⇒ `Left(Refusal("owner-account-mismatch", …))`，文案模板逐字
 *   （两个值 + `stand logout` / `stand login` 建议）；
 *   `credentialSub == None` ⇒ `Right(())`（fail-open：与 `OwnerIdentity` 的「读不到锚 ⇒ 不冒认 owner」
 *   同一条纪律 `OwnerIdentity.scala:20-22`，无锚时不新增拒绝面）。
 * - `LoginGuard.checkHotSwitch(previous, next)`：§0 总则 2「进程级单值，禁热切」的判定面；
 *   `previous != next` ⇒ `Left(Refusal("hot-switch-forbidden", …))`。
 *   生产调用点 = `Main` 启动序 + `LoginCommand` 的根切换路径，**不在** `PathUtil` 里（测试要反复换根）。
 *
 * == 未在这里断言的一条（归属别的腿）==
 * `LoginGuard.checkLoginAllowed`（`stand login` 前置门）的**拒绝臂**判据 = 「端口上有活实例 + 该实例
 * 账号 ≠ 目标」，没有活实例就恒 `Right(())`；本腿硬约束「不许起实例、不联网」，而它在无指针时是否
 * 先短路探端口属实现腿自由 —— 直接调用有命中本机真实实例（6138）的风险，故整条留给 A8/A9 的隔离实例腿，
 * 本 spec 不碰（也不碰任何端口）。
 *
 * 隔离纪律：本 spec 是纯函数面，除字面路径外零磁盘 IO，不造样本根、不碰 `~/.stand`。
 */
class OwnerGuardSpec extends munit.FunSuite:

  private val ServedSub = "logto|user-A"
  private val OwnerSub = "logto|user-B"

  test("V2: 本引擎服务的账号 ≠ 凭据 owner ⇒ 拒启（owner-account-mismatch），文案点名两个 sub + 行动建议"):
    val res = OwnerGuard.check(ServedSub, Some(OwnerSub))
    res match
      case Left(refusal) =>
        assertEquals(
          refusal.code,
          "owner-account-mismatch",
          "稳定码逐字（§5；沿用 LoginCommand.SignInFailure 闭集风格）"
        )
        assert(refusal.message.contains(ServedSub), s"文案必须点名本引擎服务的账号：${refusal.message}")
        assert(refusal.message.contains(OwnerSub), s"文案必须点名凭据里的 owner：${refusal.message}")
        assert(
          refusal.message.contains("stand logout"),
          s"行动建议必须给出 'stand logout'（§5 逐字模板）：${refusal.message}"
        )
        assert(
          refusal.message.contains("stand login"),
          s"行动建议必须给出 'stand login'（§5 逐字模板）：${refusal.message}"
        )
        assert(
          refusal.message.contains("owner-account-mismatch"),
          s"稳定码同时出现在文案尾部（§5 逐字模板）：${refusal.message}"
        )
      case Right(_) =>
        fail("账号与凭据 owner 不等时必须拒启 —— 静默降级 = 用 A 的凭据跑在 B 的账号根上")

  test("V2: 凭据里没有 owner 锚（None）⇒ fail-open 放行，不新增拒绝面"):
    assertEquals(
      OwnerGuard.check(ServedSub, None),
      Right(()),
      "credentialSub = None ⇒ Right(())（§5 fail-open：读不到锚 ⇒ 不冒认 owner）"
    )

  test("V2: 账号与 owner 相同 ⇒ 放行（等值判定，不多拒）"):
    assertEquals(OwnerGuard.check(ServedSub, Some(ServedSub)), Right(()))

  test("禁热切（§0 总则 2）：同一根 ⇒ 放行；进程内二次换根 ⇒ hot-switch-forbidden"):
    val a = os.Path("/tmp/b1-red-hotswitch-a")
    val b = os.Path("/tmp/b1-red-hotswitch-b")
    assertEquals(
      LoginGuard.checkHotSwitch(a, a),
      Right(()),
      "同一根（启动序前后各取一次的同值情况）必须放行"
    )
    LoginGuard.checkHotSwitch(a, b) match
      case Left(refusal) =>
        assertEquals(refusal.code, "hot-switch-forbidden")
        assert(refusal.message.nonEmpty, "禁热切的拒绝必须带可行动文案（Refusal 的契约：message 必须可行动）")
      case Right(_) =>
        fail("previous != next 必须拒（§0 总则 2：进程级单值是裁定，登录后写指针 + 重启生效）")

end OwnerGuardSpec
