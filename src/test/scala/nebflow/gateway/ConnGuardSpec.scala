package nebflow.gateway

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite

/**
 * R-1b conn-guard 单元测试（纯 Ref 逻辑，零网络）。
 *
 * 为什么这些断言而不是「能编译就行」：
 *   · per-IP / 总量上限是拨号风暴复发时唯一的进程内防线——判错方向
 *     （过松 = 防线失效；过紧 = 误杀合法前端）都直接落在可用性上。
 *   · release 按凭据回「正确的桶」（tracked / overflow）——错桶 = 计数
 *     单向泄漏 = 最终全员误拒，这是守护自身「灌表」的形态。
 *   · 追踪表封顶（maxTrackedIps + overflow 并桶）= 伪造源 IP 洪峰无法把
 *     守护自己撑到无界（本件的防自灌纪律）。
 */
class ConnGuardSpec extends CatsEffectSuite:

  private val logger = nebflow.core.NebflowLogger.forName("test.conn-guard")

  private def guard(cfg: ConnGuardConfig): IO[ConnGuard] = ConnGuard.forConfig(cfg, logger)

  // ── per-IP 上限：超限拒、释放后复纳 ────────────────────────────────────

  test("per-ip cap: over cap rejected, release re-admits"):
    for
      g <- guard(ConnGuardConfig(wsPerIpCap = 2, wsTotalCap = 100))
      h1 <- g.acquireWs("9.9.9.1")
      _ <- g.acquireWs("9.9.9.1")
      before <- g.checkWs("9.9.9.1")
      other <- g.checkWs("9.9.9.2") // 其他 IP 不受牵连
      _ <- g.releaseWs(h1)
      after <- g.checkWs("9.9.9.1")
      rejected <- g.snapshot.map(_.rejectedTotal)
    yield
      assertEquals(before, Some("per-ip-cap"))
      assertEquals(other, None)
      assertEquals(after, None)
      assertEquals(rejected, 1L)

  // ── 总量上限：单 IP 未超但总量到顶 ⇒ total-cap ──────────────────────────

  test("total cap: total-cap wins once the table is full even for a fresh ip"):
    for
      g <- guard(ConnGuardConfig(wsPerIpCap = 2, wsTotalCap = 3))
      _ <- g.acquireWs("9.9.9.1")
      _ <- g.acquireWs("9.9.9.1")
      _ <- g.acquireWs("9.9.9.2")
      fresh <- g.checkWs("9.9.9.3")
      sameIp <- g.checkWs("9.9.9.2") // per-ip 判据先看总量，还是总量先到顶
    yield
      assertEquals(fresh, Some("total-cap"))
      assertEquals(sameIp, Some("total-cap"))

  // ── 受信旁路：环回/名单内永不拒（本地管理通道在风暴中必须可达）─────────

  test("trusted ips bypass per-ip cap but still count toward the snapshot"):
    for
      g <- guard(
        ConnGuardConfig(wsPerIpCap = 1, wsTotalCap = 100, extraTrustedIps = Set("9.9.9.9"))
      )
      _ <- g.acquireWs("9.9.9.9")
      _ <- g.acquireWs("9.9.9.9")
      loopback <- g.checkWs("127.0.0.1")
      trusted <- g.checkWs("9.9.9.9")
      total <- g.snapshot.map(_.wsTotal)
    yield
      assertEquals(loopback, None)
      assertEquals(trusted, None)
      assertEquals(total, 2)

  // ── disabled：全放行短路 ────────────────────────────────────────────────

  test("disabled guard admits everything and keeps counters untouched"):
    for
      g <- guard(ConnGuardConfig(enabled = false))
      d = ConnGuard.disabled
      r1 <- g.checkWs("9.9.9.1")
      r2 <- d.checkWs("9.9.9.1")
      h <- d.acquireWs("9.9.9.1")
      _ <- d.releaseWs(h)
      s <- d.snapshot
    yield
      assertEquals(r1, None)
      assertEquals(r2, None)
      assertEquals(s.wsTotal, 0)
      assertEquals(s.enabled, false)

  // ── 防自灌：追踪表封顶，溢出 IP 并桶（仍计总量），回减回对桶 ────────────

  test("bounded table: ips beyond maxTrackedIps lump into overflow and release drains the right bucket"):
    val cfg = ConnGuardConfig(wsPerIpCap = 10, wsTotalCap = 100, maxTrackedIps = 3)
    for
      g <- guard(cfg)
      h1 <- g.acquireWs("9.0.0.1")
      _ <- g.acquireWs("9.0.0.2")
      _ <- g.acquireWs("9.0.0.3")
      h4 <- g.acquireWs("9.0.0.4") // 表满 ⇒ overflow 并桶
      mid <- g.snapshot
      _ <- g.releaseWs(h4)
      _ <- g.releaseWs(h1)
      end <- g.snapshot
    yield
      assertEquals(mid.trackedIps, 3)
      assertEquals(mid.overflowConns, 1)
      assertEquals(mid.wsTotal, 4)
      assertEquals(end.overflowConns, 0)
      assertEquals(end.wsTotal, 2)
      assertEquals(end.trackedIps, 3)

  // ── 请求观测：只计数不拦截 ──────────────────────────────────────────────

  test("request tap accounting: reqTotal and topReq update without touching ws caps"):
    for
      g <- guard(ConnGuardConfig())
      _ <- g.observeRequest("9.9.9.1")
      _ <- g.observeRequest("9.9.9.1")
      _ <- g.observeRequest("9.9.9.2")
      s <- g.snapshot
      verdict <- g.checkWs("9.9.9.1")
    yield
      assertEquals(s.reqTotal, 3L) // 两次 .1 + 一次 .2
      assertEquals(s.topReq.headOption.map(_._1), Some("9.9.9.1"))
      assertEquals(verdict, None)

  // ── IP 归一化：ip4s toString 形态 → 裸 IP ───────────────────────────────

  test("normalizeIpStr: v4:vport, leading slash, bracketed v6, bare ip"):
    assertEquals(ConnGuard.normalizeIpStr("1.2.3.4:5678"), "1.2.3.4")
    assertEquals(ConnGuard.normalizeIpStr("/1.2.3.4:5678"), "1.2.3.4")
    assertEquals(ConnGuard.normalizeIpStr("[::1]:8080"), "::1")
    assertEquals(ConnGuard.normalizeIpStr("127.0.0.1"), "127.0.0.1")

  // ── env 解析：非法值回落默认，不 crash ──────────────────────────────────

  test("fromEnv: defaults, overrides, off-switch, trusted list, junk falls back"):
    val d = ConnGuardConfig.fromEnv(_ => None)
    assertEquals(d.wsPerIpCap, 64)
    assertEquals(d.wsTotalCap, 1024)
    assertEquals(d.warnPct, 80)
    assert(d.trustedIps.contains("127.0.0.1"))

    val o = ConnGuardConfig.fromEnv {
      case "GATEWAY_CONN_GUARD_WS_PER_IP_CAP" => Some("32")
      case "GATEWAY_CONN_GUARD_WS_TOTAL_CAP"  => Some("512")
      case "GATEWAY_CONN_GUARD"               => Some("off")
      case "GATEWAY_CONN_GUARD_TRUSTED_IPS"   => Some(" 100.91.165.120 , 10.0.0.7 ")
      case "GATEWAY_CONN_GUARD_WARN_PCT"      => Some("not-a-number")
      case _                  => None // lookup 是全函数：未提及的名字一律「未设置」
    }
    assertEquals(o.wsPerIpCap, 32)
    assertEquals(o.wsTotalCap, 512)
    assertEquals(o.enabled, false)
    assertEquals(o.warnPct, 80) // 非法值回落
    assert(o.trustedIps.contains("100.91.165.120"))
    assert(o.trustedIps.contains("10.0.0.7"))
    assert(o.trustedIps.contains("::1")) // 环回恒在

  // ── 快照/预警行：格式与 warnPct 门槛 ────────────────────────────────────

  test("warnLine trips at warnPct and stays quiet below it"):
    def snap(wsTotal: Int) = ConnGuard.Snapshot(
      enabled = true,
      wsTotal = wsTotal,
      trackedIps = 1,
      overflowConns = 0,
      acceptedTotal = wsTotal.toLong,
      rejectedTotal = 0,
      evictedTotal = 0,
      reqTotal = 0,
      wsPerIpCap = 64,
      wsTotalCap = 1024,
      warnPct = 80,
      topWs = Vector(("9.9.9.1", wsTotal)),
      topReq = Vector.empty,
    )
    assertEquals(ConnGuard.warnLine(snap(819)), None) // 79%
    assert(ConnGuard.warnLine(snap(820)).isDefined) // 80%
    assert(ConnGuard.warnLine(snap(820)).exists(_.contains("80%")))
    assert(ConnGuard.snapshotLine(snap(12)).contains("ws=12/1024"))
    assert(ConnGuard.snapshotLine(snap(12)).contains("top=[9.9.9.1:12]"))
end ConnGuardSpec
