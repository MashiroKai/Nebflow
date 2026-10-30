package nebflow.gateway

import munit.FunSuite
import nebflow.shared.Defaults

/**
 * 引擎默认网关端口单一来源钉（端口对调落地批 2026-10-25；作者令 2026-10-23
 * 「默认改 6138——下次重启生效」的引擎代码面收口）。
 *
 * 事故链（2026-10-25）：作者手动 `sbt run` 起引擎走旧默认 8080，微信 side-car
 * 投递目标 `127.0.0.1:6138/.../weixin-ilink/ingress` 全部 tcp_refused —— 微信
 * 入站全断。10-23 的对调只改了 ops 显式传参，引擎代码默认值没跟。本 spec 钉住：
 * 默认口只有一个声明点（`Defaults.GatewayPort`），绑定解析（GatewayConfig）
 * 与它同源 —— 任何再对调只动 Defaults 一处。
 *
 * 其余引用点（CLI readPort / doctor / prompt 变量兜底 / Rollback / FlowTree /
 * RestApiRoutes / neblink 对端口兜底）为编译期引用，随常量走；运行时在此钉
 * 绑定面。沙箱不走此值（高位随机口，自有分配面），不在本钉范围。
 */
class GatewayPortDefaultSpec extends FunSuite:

  test("Defaults.GatewayPort is the 2026-10-23 fleet default 6138"):
    assertEquals(Defaults.GatewayPort, 6138, "作者令 2026-10-23：默认 6138（8080 让渡 9.19 自有根实例）")

  test("the legacy command brand default stays 8080 and differs from the engine default"):
    // 双实例并存批 2026-10-30：两个命令品牌（stand / nebflow）同机并存、缺省口必须错开。
    // 该 8080 只服务读数/报错文案，引擎自身绝不绑定 —— 见 Defaults.LegacyGatewayPort 注释。
    assertEquals(Defaults.LegacyGatewayPort, 8080)
    assertNotEquals(Defaults.GatewayPort, Defaults.LegacyGatewayPort)

  test("GatewayConfig binding default resolves from the single-source constant"):
    // The ip4s Port the engine actually binds when neither --port nor env speaks.
    assertEquals(GatewayConfig.DefaultPort.value, Defaults.GatewayPort)

  test("the default is a legal bindable TCP port (not privileged, not ephemeral-reserved)"):
    val p = Defaults.GatewayPort
    assert(p >= 1024 && p <= 65535, s"$p must be a user-range port")

end GatewayPortDefaultSpec
