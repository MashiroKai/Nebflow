package nebflow.gateway.infra

import nebflow.gateway.*

import com.comcast.ip4s.{IpAddress, Port}
import munit.FunSuite
import nebflow.shared.Defaults

/**
 * 细粒度单元（infra 模块）：启动配置解析链 + 连接护栏的纯函数面 + 探针 origin
 * 归一 + netstat 行解析 + 目录选择器闩。
 *
 * 既有 `GatewayPortDefaultSpec` / `ConnGuardSpec` / `HealthRoutesSpec` /
 * `WindowsProcsSpec` 走的是路由或真实系统调用。这里只钉纯输入 → 纯输出。
 */
class InfraUnitSpec extends FunSuite:

  private def port(n: Int): Port = Port.fromInt(n).get

  // ── GatewayConfig：--port > env > config > 品牌缺省 ────────────────────

  test("resolvePort: CLI wins over env and config"):
    assertEquals(GatewayConfig.resolvePort(Some(7000), Some("8000"), Some(9000)), port(7000))

  test("resolvePort: an unusable leg falls through to the next one"):
    assertEquals(GatewayConfig.resolvePort(Some(0), Some("8000"), Some(9000)), port(8000), "port 0 is not bindable")
    assertEquals(GatewayConfig.resolvePort(None, Some("not-a-port"), Some(9000)), port(9000))
    assertEquals(GatewayConfig.resolvePort(None, Some("0"), Some(9000)), port(9000))

  test("resolvePort: all legs silent ⇒ brand default port"):
    assertEquals(GatewayConfig.resolvePort(None, None, None), GatewayConfig.DefaultPort)
    assertEquals(GatewayConfig.DefaultPort, port(Defaults.brandDefaultPort))
    assertEquals(GatewayConfig.resolvePort(None, Some("-1"), Some(-2)), GatewayConfig.DefaultPort)

  test("noBrowser: the CLI flag is readable back"):
    val saved = GatewayConfig.noBrowser
    try
      GatewayConfig.setNoBrowser(true)
      assertEquals(GatewayConfig.noBrowser, true)
      GatewayConfig.setNoBrowser(false)
      assertEquals(GatewayConfig.noBrowser, false)
    finally GatewayConfig.setNoBrowser(saved)

  // ── ConnGuardConfig.fromEnv：缺省、夹取、关闭值 ────────────────────────

  test("fromEnv: silent env yields the documented defaults"):
    val cfg = ConnGuardConfig.fromEnv(_ => None)
    assertEquals(cfg.enabled, true)
    assertEquals(cfg.wsPerIpCap, 64)
    assertEquals(cfg.wsTotalCap, 1024)
    assertEquals(cfg.warnPct, 80)
    assertEquals(cfg.maxTrackedIps, 1024)
    assertEquals(cfg.snapshotSec, 60)
    assertEquals(cfg.tunnelPerIpCap, ConnGuardConfig.DefaultTunnelPerIpCap)
    assertEquals(cfg.tunnelTotalCap, ConnGuardConfig.DefaultTunnelTotalCap)
    assertEquals(cfg.tunnelWindowSec, 60)
    assertEquals(cfg.extraTrustedIps, Set.empty[String])
    assertEquals(cfg.trustedIps, ConnGuardConfig.DefaultTrustedIps)

  test("fromEnv: overrides apply, values below the floor clamp, garbage keeps the default"):
    val env = Map(
      "GATEWAY_CONN_GUARD_WS_PER_IP_CAP" -> "0",
      "GATEWAY_CONN_GUARD_WS_TOTAL_CAP" -> "abc",
      "GATEWAY_CONN_GUARD_WARN_PCT" -> "95",
      "GATEWAY_CONN_GUARD_SNAPSHOT_SEC" -> "1",
      "GATEWAY_CONN_GUARD_TRUSTED_IPS" -> " 10.0.0.1 , 10.0.0.2 ,"
    )
    val cfg = ConnGuardConfig.fromEnv(env.get)
    assertEquals(cfg.wsPerIpCap, 1, "floor is 1")
    assertEquals(cfg.wsTotalCap, 1024, "non-numeric keeps the default")
    assertEquals(cfg.warnPct, 95)
    assertEquals(cfg.snapshotSec, 15, "floor is 15s")
    assertEquals(cfg.extraTrustedIps, Set("10.0.0.1", "10.0.0.2"))
    assert(cfg.trustedIps.contains("127.0.0.1"), "loopback stays trusted")

  test("fromEnv: off/0/false (case-insensitive) disable the guard; anything else keeps it on"):
    List("off", "OFF", "0", "false", "FALSE").foreach { v =>
      assertEquals(ConnGuardConfig.fromEnv(Map("GATEWAY_CONN_GUARD" -> v).get).enabled, false, v)
    }
    assertEquals(ConnGuardConfig.fromEnv(Map("GATEWAY_CONN_GUARD" -> "on").get).enabled, true)

  // ── ConnGuard：IP 归一 ────────────────────────────────────────────────

  test("normalizeIpStr: strips one host:port tail, never mangles a bare IPv6"):
    assertEquals(ConnGuard.normalizeIpStr("/127.0.0.1:6138"), "127.0.0.1")
    assertEquals(ConnGuard.normalizeIpStr("[::1]:6138"), "::1")
    assertEquals(ConnGuard.normalizeIpStr("127.0.0.1:6138"), "127.0.0.1")
    assertEquals(ConnGuard.normalizeIpStr("::1"), "::1")
    assertEquals(ConnGuard.normalizeIpStr("2001:db8::1"), "2001:db8::1")

  test("normalizeIp: v4-mapped collapses to dotted v4, bare v6 stays, None = unknown"):
    assertEquals(ConnGuard.normalizeIp(Some(IpAddress.fromString("::ffff:127.0.0.1").get)), "127.0.0.1")
    assertEquals(ConnGuard.normalizeIp(Some(IpAddress.fromString("::1").get)), "::1")
    assertEquals(ConnGuard.normalizeIp(Some(IpAddress.fromString("2001:db8::1").get)), "2001:db8::1")
    assertEquals(ConnGuard.normalizeIp(None), "unknown")

  // ── HealthRoutes.canonicalOrigin ──────────────────────────────────────

  test("canonicalOrigin: lowercases scheme+host, drops default ports, keeps the rest"):
    assertEquals(HealthRoutes.canonicalOrigin("https://Portal.Example:443/a"), Some("https://portal.example"))
    assertEquals(HealthRoutes.canonicalOrigin("http://h:80"), Some("http://h"))
    assertEquals(HealthRoutes.canonicalOrigin("http://h:8080/"), Some("http://h:8080"))
    assertEquals(HealthRoutes.canonicalOrigin("HTTPS://H"), Some("https://h"))

  test("canonicalOrigin: hostless or unparsable values are None"):
    assertEquals(HealthRoutes.canonicalOrigin("about:blank"), None)
    assertEquals(HealthRoutes.canonicalOrigin("not a url"), None)
    assertEquals(HealthRoutes.canonicalOrigin(""), None)

  // ── WindowsProcs：netstat 行解析 ──────────────────────────────────────

  private val NetstatRows = Vector(
    "  TCP    0.0.0.0:6138           0.0.0.0:0                LISTENING       9112",
    "  TCP    [::]:6138              [::]:0                   LISTENING       9112",
    "  TCP    127.0.0.1:5000          0.0.0.0:0                LISTENING       42",
    "  TCP    0.0.0.0:7000            0.0.0.0:0                ESTABLISHED     9112",
    "  UDP    0.0.0.0:53              *:*                                      1234",
    "  TCP    not-an-address          0.0.0.0:0                LISTENING       9112"
  )

  test("parseNetstatListenerPorts: LISTENING TCP rows of the pid, deduped, junk skipped"):
    assertEquals(WindowsProcs.parseNetstatListenerPorts(NetstatRows, 9112), List(6138))
    assertEquals(WindowsProcs.parseNetstatListenerPorts(NetstatRows, 42), List(5000))
    assertEquals(WindowsProcs.parseNetstatListenerPorts(NetstatRows, 7), Nil)

  test("parseNetstatListenerPids: owning pids on the port, deduped"):
    assertEquals(WindowsProcs.parseNetstatListenerPids(NetstatRows, 6138), List(9112L))
    assertEquals(WindowsProcs.parseNetstatListenerPids(NetstatRows, 5000), List(42L))
    assertEquals(WindowsProcs.parseNetstatListenerPids(NetstatRows, 7000), Nil)

  // ── WorkspaceDirPicker：单飞闩 ────────────────────────────────────────

  test("busy/resetForTest: the in-flight latch is resettable (test seam)"):
    WorkspaceDirPicker.resetForTest()
    assertEquals(WorkspaceDirPicker.busy, false)

end InfraUnitSpec

