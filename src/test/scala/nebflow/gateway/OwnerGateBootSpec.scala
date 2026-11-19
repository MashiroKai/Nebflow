package nebflow.gateway

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

import nebflow.bridge.OwnerAnchor
import nebflow.core.root.RootLayer

/**
 * 三层数据根批 · b1-guards（tenant-full-flow-batch2）—— **T2 的启动闸接线 spec**：
 * 真实 boot 面（`GatewayMain.runGateway` 第一句）的 owner 闸
 * `ownerGate(RootLayer, IO[Option[OwnerAnchor]])`。
 *
 * == 断言原文（派单 T2）==
 * 「owner 不一致启动必须拒」—— 本引擎服务的账号（账号档 `RootLayer.Account.accountSub`，
 * 即 `RootLadder.current` 的定档读数）vs 凭据里的 owner（`OwnerIdentity.localAnchor`：
 * 凭据文件里 TLS 信任链落盘的 Logto id_token 的 `sub`）不等 ⇒ 拒启（raise 浮出 = IOApp
 * 退出码 ≠ 0），文案双点名 + `stand logout` 行动建议（`OwnerGuard.check` 逐字模板，
 * 稳定码 `owner-account-mismatch`）。不得静默降级。
 *
 * == 红绿态登记（测试纪律：spec 先写、能出红态的先红后绿）==
 * 改前（现状）：runGateway 无 owner 闸、`GatewayMain.ownerGate` 符号不存在 ⇒ 本件引用即
 * `Test/compile` 红（缺符号，输出原样留证于提交信息）=「现状照常启动」的可编译证明；
 * 接线后转绿。
 *
 * == 契约面（判定单点 + fail-open）==
 * 等值判定只在 `OwnerGuard.check`（core/root/Guards.scala —— 守卫单点铁律）；ownerGate 是
 * **调用点胶水**：取层、取锚、按层路由（平铺档/未登录层没有「本引擎服务的账号」可对
 * ⇒ 跳过不拒 = 不新增拒绝面）；`credentialSub = None` ⇒ fail-open（`OwnerIdentity`
 * 的「读不到锚 ⇒ 不冒认 owner」同一条纪律）。纯函数判定臂已在 `OwnerGuardSpec` 覆盖，
 * 本件覆盖的是**启动闸行为**（拒 ⇒ raise 浮出；放行/跳过 ⇒ 正常完成）。
 *
 * 隔离纪律：纯注入面（层与锚都注入），零真实凭据、零磁盘、零端口；层对象用字面路径构造
 * （OwnerGuardSpec 同口径）。
 */
class OwnerGateBootSpec extends munit.FunSuite:

  private val ServedSub = "logto|user-A"
  private val OwnerSub = "logto|user-B"

  private def accountLayer(sub: String): RootLayer.Account =
    RootLayer.Account(os.Path("/tmp/b1-gate-owner-a"), sub, os.Path("/tmp/b1-gate-owner-a/accounts/x"))

  // ── 拒绝臂（T2 本体）：账号档 + 凭据 owner ≠ 服务账号 ⇒ 拒启 ────────────────

  test("T2: 账号档 + 凭据 owner 不等 ⇒ 拒启（raise 浮出，owner-account-mismatch，双点名 + stand logout 建议）"):
    val err = intercept[IllegalStateException]:
      GatewayMain
        .ownerGate(accountLayer(ServedSub), IO.pure(Some(OwnerAnchor(OwnerSub, "B"))))
        .unsafeRunSync()
    assert(err.getMessage.contains("owner-account-mismatch"), s"稳定码随件: ${err.getMessage}")
    assert(err.getMessage.contains(ServedSub), s"双点名·本引擎服务的账号: ${err.getMessage}")
    assert(err.getMessage.contains(OwnerSub), s"双点名·凭据 owner: ${err.getMessage}")
    assert(err.getMessage.contains("stand logout"), s"行动建议必须可行动: ${err.getMessage}")

  // ── 放行臂：等值 / 无锚 fail-open / 非账号档跳过 ─────────────────────────────

  test("T2: 等值 ⇒ 放行（正常完成，不 raise）"):
    GatewayMain.ownerGate(accountLayer(ServedSub), IO.pure(Some(OwnerAnchor(ServedSub, "A")))).unsafeRunSync()

  test("T2: 凭据无锚（None）⇒ fail-open 放行（读不到锚 ⇒ 不冒认 owner，不新增拒绝面）"):
    GatewayMain.ownerGate(accountLayer(ServedSub), IO.pure(None)).unsafeRunSync()

  test("T2: 平铺档 / 未登录层 ⇒ 跳过 owner 判定（没有可对的账号，锚在场也不拒）"):
    GatewayMain
      .ownerGate(
        RootLayer.Flat(os.Path("/tmp/b1-gate-flat"), os.Path("/tmp/b1-gate-flat")),
        IO.pure(Some(OwnerAnchor(OwnerSub, "B")))
      )
      .unsafeRunSync()
    GatewayMain
      .ownerGate(
        RootLayer.Unauthenticated(os.Path("/tmp/b1-gate-unauth"), os.Path("/tmp/b1-gate-unauth/unauthenticated")),
        IO.pure(Some(OwnerAnchor(OwnerSub, "B")))
      )
      .unsafeRunSync()

end OwnerGateBootSpec
