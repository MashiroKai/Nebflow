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
 * 双生分家批（2026-10-31，作者裁定）把「默认口」升格为**品牌感知**：
 * `Defaults.brandDefaultPortFor(lowerName)` 是纯函数单点 —— stand=6138、
 * nebflow=6140（其自有独立默认，考古：8080 年代它是全引擎唯一主默认、6138 年代
 * 跟着撞车，从无前例的独立默认），未知品牌回落原生默认 6138。本构建（stand）
 * 的 `GatewayConfig.DefaultPort` 与 `brandDefaultPort` 必须恒等。
 *
 * 端口解析链（`GatewayConfig.resolvePort`，与 CLI `GatewayClient.readPort` 同链
 * 同序）钉：--port ＞ GATEWAY_PORT env ＞ config `port` 键 ＞ 品牌缺省口；
 * 任一腿非法（非数字/越界）即跳过。`configFilePort` 读 `<dataRoot>/nebflow.json`
 * 顶层整数 `port`，文件缺失/空/非法一律 None（绝不抛错炸掉启动闸）。
 *
 * 其余引用点（CLI readPort / doctor / prompt 变量兜底 / Rollback / FlowTree /
 * RestApiRoutes / neblink 对端口兜底）为编译期引用，随常量走；运行时在此钉
 * 绑定面。沙箱不走此值（高位随机口，自有分配面），不在本钉范围。
 */
class GatewayPortDefaultSpec extends FunSuite:

  test("Defaults.GatewayPort is the 2026-10-23 fleet default 6138"):
    assertEquals(Defaults.GatewayPort, 6138, "作者令 2026-10-23：默认 6138（8080 让渡 9.19 自有根实例）")

  test("nebflow brand has its own independent default 6140, differing from the stand default"):
    // 双生分家批 2026-10-31：同机双产品各自独立启动，两品牌的引擎兜底口必须错开。
    // 6140 是 nebflow 的**第一个真正独立默认**（考古：8080 是对调前的全引擎主默认，
    // 不是独立默认；10-30 批曾选 8080，本批按作者裁定换 6140 —— 8080 自此只属
    // 9.19 自有根实例，经 NEBFLOW_GATEWAY_PORT/--port 管辖）。
    assertEquals(Defaults.NebflowBrandGatewayPort, 6140)
    assertNotEquals(Defaults.GatewayPort, Defaults.NebflowBrandGatewayPort)

  test("brandDefaultPortFor maps command brands; unknown brands fall back to the native default"):
    assertEquals(Defaults.brandDefaultPortFor("stand"), 6138)
    assertEquals(Defaults.brandDefaultPortFor("nebflow"), 6140)
    assertEquals(Defaults.brandDefaultPortFor("some-future-brand"), 6138)
    // 本构建 brand.conf = stand：live 读数 == 原生默认（nebflow 品牌构建才走到 6140，
    // 由纯函数行钉住，不受本构建 brand.conf 冻结限制）。
    assertEquals(Defaults.brandDefaultPort, Defaults.GatewayPort)

  test("GatewayConfig binding default resolves from the brand-aware single source"):
    // The ip4s Port the engine actually binds when --port / env / config all stay silent.
    assertEquals(GatewayConfig.DefaultPort.value, Defaults.brandDefaultPort)

  test("both brand defaults are legal bindable TCP ports (not privileged, not ephemeral-reserved)"):
    for p <- List(Defaults.GatewayPort, Defaults.NebflowBrandGatewayPort) do
      assert(p >= 1024 && p <= 65535, s"$p must be a user-range port")

  // ── 端口解析链纯核心钉（双生分家批 2026-10-31）：--port > env > config > 品牌默认 ──

  test("resolvePort chain: --port beats env beats config beats brand default"):
    val d = Defaults.brandDefaultPort
    assertEquals(GatewayConfig.resolvePort(Some(7001), Some("7002"), Some(7003)).value, 7001)
    assertEquals(GatewayConfig.resolvePort(None, Some("7002"), Some(7003)).value, 7002)
    assertEquals(GatewayConfig.resolvePort(None, None, Some(7003)).value, 7003)
    assertEquals(GatewayConfig.resolvePort(None, None, None).value, d)

  test("resolvePort skips illegal legs (non-numeric env, out-of-range/zero values) to the next leg"):
    val d = Defaults.brandDefaultPort
    assertEquals(GatewayConfig.resolvePort(None, Some("abc"), Some(7003)).value, 7003, "non-numeric env skips to config")
    assertEquals(GatewayConfig.resolvePort(Some(70000), None, Some(7003)).value, 7003, "out-of-range --port skips to config")
    assertEquals(GatewayConfig.resolvePort(None, None, Some(0)).value, d, "port 0 (ephemeral) is not bindable — falls to default")
    assertEquals(GatewayConfig.resolvePort(None, Some("0"), Some(7003)).value, 7003, "zero env skips to config")
    assertEquals(GatewayConfig.resolvePort(None, Some("-1"), None).value, d, "negative env falls to default")

  test("configFilePort reads the explicit 'port' key and tolerates missing/empty/garbage"):
    val tmp = os.temp.dir(prefix = "gw-port-spec")
    val savedRoot = nebflow.shared.PathUtil.dataRoot
    try
      nebflow.shared.PathUtil.setDataRoot(tmp)
      // 无配置文件 → None
      assertEquals(GatewayConfig.configFilePort(), None)
      // 空对象 → None
      os.write.over(tmp / "nebflow.json", "{}")
      assertEquals(GatewayConfig.configFilePort(), None)
      // 显式 port 键 → 命中
      os.write.over(tmp / "nebflow.json", """{"port": 6199, "llm": {}}""")
      assertEquals(GatewayConfig.configFilePort(), Some(6199))
      // 非法 JSON → None（绝不抛错炸掉启动闸）
      os.write.over(tmp / "nebflow.json", "{not json")
      assertEquals(GatewayConfig.configFilePort(), None)
      // 非整数 port → None
      os.write.over(tmp / "nebflow.json", """{"port": "6199"}""")
      assertEquals(GatewayConfig.configFilePort(), None)
    finally
      nebflow.shared.PathUtil.setDataRoot(savedRoot)
      os.remove.all(tmp)

end GatewayPortDefaultSpec
