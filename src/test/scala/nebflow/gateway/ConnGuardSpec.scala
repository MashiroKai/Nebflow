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
      assertEquals(s.reqUntrackedTotal, 0L) // 表远未满 ⇒ 全部建档
      assertEquals(verdict, None)

  // ── D2 返工红验基线：请求面伪造源 IP 洪峰不得突破 maxTrackedIps 封顶 ──────
  // 🔴 旧交付树在此必红（observeRequest 无封顶、无淘汰 ⇒ 20000 个 IP 撑到 20000）。

  test("request-face self-flood is bounded by maxTrackedIps (no evictable entry needed)"):
    val cfg = ConnGuardConfig(wsPerIpCap = 64, wsTotalCap = 1024, maxTrackedIps = 64)
    val n = 5000
    for
      g <- guard(cfg)
      _ <- (0 until n).toList.traverse_(i => g.observeRequest(s"10.${i / 65536 % 256}.${i / 256 % 256}.${i % 256}"))
      s <- g.snapshot
    yield
      assertEquals(s.reqTotal, n.toLong) // 请求总数照实累加（不丢计）
      assert(s.trackedIps <= cfg.maxTrackedIps, s"trackedIps=${s.trackedIps} 超出封顶 ${cfg.maxTrackedIps}")
      assertEquals(s.trackedIps, cfg.maxTrackedIps) // 封顶打满即恒定（旧版此处 = 5000）
      assert(s.evictedTotal > 0L) // 换位（汰最老零会话）发生，非静默丢弃
      assertEquals(s.wsTotal, 0) // 请求面洪峰不产生会话

  test("ws + request faces share one bound; active sessions are never evicted"):
    val cfg = ConnGuardConfig(wsPerIpCap = 10, wsTotalCap = 100, maxTrackedIps = 4)
    for
      g <- guard(cfg)
      h1 <- g.acquireWs("9.0.0.1") // 4 条活跃会话占满全表
      _ <- g.acquireWs("9.0.0.2")
      _ <- g.acquireWs("9.0.0.3")
      _ <- g.acquireWs("9.0.0.4")
      _ <- g.observeRequest("10.0.0.1") // 无零会话可汰 ⇒ 只计总数不建档
      mid <- g.snapshot
      _ <- g.releaseWs(h1) // 腾出零会话条目 ⇒ 下个新 IP 可换位建档
      _ <- g.observeRequest("10.0.0.2")
      end <- g.snapshot
    yield
      assertEquals(mid.trackedIps, 4)
      assertEquals(mid.reqTotal, 1L)
      assertEquals(mid.reqUntrackedTotal, 1L) // 无位可换 ⇒ 未建档差额可观测
      assertEquals(mid.wsTotal, 4) // 活跃会话一条不丢（封顶不吞会话账）
      assert(end.trackedIps <= cfg.maxTrackedIps)
      assertEquals(end.wsTotal, 3) // release 已回减；换位未误伤活跃会话

  test("oldest idle entry yields its slot to a new peer (bounded + no starvation)"):
    val cfg = ConnGuardConfig(wsPerIpCap = 10, wsTotalCap = 100, maxTrackedIps = 2)
    for
      g <- guard(cfg)
      _ <- g.observeRequest("10.0.0.1")
      _ <- g.observeRequest("10.0.0.2")
      full <- g.snapshot
      _ <- g.observeRequest("10.0.0.3") // 两条皆零会话 ⇒ 汰最老（.1）腾位
      after <- g.snapshot
    yield
      assertEquals(full.trackedIps, 2)
      assertEquals(after.trackedIps, 2) // 恒 ≤ 封顶
      assert(after.evictedTotal >= 1L)
      assertEquals(after.reqTotal, 3L)

  test("trusted ip at a full all-active table: admitted, overflow-counted, no ghost count"):
    val cfg = ConnGuardConfig(wsPerIpCap = 10, wsTotalCap = 100, maxTrackedIps = 2)
    for
      g <- guard(cfg)
      h1 <- g.acquireWs("9.0.0.1")
      h2 <- g.acquireWs("9.0.0.2") // 封顶已满且全为活跃会话
      hT <- g.acquireWs("127.0.0.1") // 受信 IP 撞满表 ⇒ 走 overflow 腿（不淘汰活跃条目）
      mid <- g.snapshot
      verdict <- g.checkWs("127.0.0.1")
      _ <- g.releaseWs(hT)
      afterT <- g.snapshot
      _ <- g.releaseWs(h1)
      _ <- g.releaseWs(h2)
      end <- g.snapshot
    yield
      assertEquals(mid.trackedIps, 2) // 活跃条目零淘汰
      assertEquals(mid.overflowConns, 1)
      assertEquals(mid.wsTotal, 3)
      assertEquals(verdict, None) // 受信面照旧不被拒（可达性不损失）
      assertEquals(afterT.overflowConns, 0) // 凭据配对回正确的桶
      assertEquals(end.wsTotal, 0) // 全释放归零 ⇒ 零幽灵计数
      assertEquals(end.overflowConns, 0)

  // ── IP 归一化：socket 串兜底面（真 host:port 才截尾）─────────────────────

  test("normalizeIpStr: only true host:port forms are truncated; bare v6 untouched"):
    assertEquals(ConnGuard.normalizeIpStr("1.2.3.4:5678"), "1.2.3.4")
    assertEquals(ConnGuard.normalizeIpStr("/1.2.3.4:5678"), "1.2.3.4")
    assertEquals(ConnGuard.normalizeIpStr("[::1]:8080"), "::1")
    assertEquals(ConnGuard.normalizeIpStr("127.0.0.1"), "127.0.0.1")
    // 裸 IPv6（多冒号、无括号）= 不是 host:port ⇒ 一字不动（旧截尾规则在此剪坏）
    assertEquals(ConnGuard.normalizeIpStr("::1"), "::1")
    assertEquals(ConnGuard.normalizeIpStr("2001:db8::1"), "2001:db8::1")

  // ── D1 返工红验基线：生产真实输入（Option[IpAddress]，即 req.remoteAddr 实型）──
  // 🔴 这组断言在旧交付树上必红（旧 normalizeIp 走字符串截尾 ⇒ ::1 → ":"）；
  //    若把这套截尾逻辑注回 normalizeIp，本组立即验红 ⇒ 固定防止回归。

  private def ip(s: String): Option[com.comcast.ip4s.IpAddress] =
    com.comcast.ip4s.IpAddress.fromString(s)

  test("normalizeIp (production input Option[IpAddress]): v6 loopback stays trusted"):
    // ip4s toString 已裸；::1 与全展开形态必须同键且都落在环回白名单
    assertEquals(ConnGuard.normalizeIp(ip("::1")), "::1")
    assertEquals(ConnGuard.normalizeIp(ip("0:0:0:0:0:0:0:1")), "::1")
    assert(ConnGuardConfig.DefaultTrustedIps.contains(ConnGuard.normalizeIp(ip("::1"))))
    assert(ConnGuardConfig.DefaultTrustedIps.contains(ConnGuard.normalizeIp(ip("127.0.0.1"))))
    assertEquals(ConnGuard.normalizeIp(None), "unknown")

  test("normalizeIp: v4-mapped collapses to dotted v4 (mapped loopback is trusted)"):
    assertEquals(ConnGuard.normalizeIp(ip("::ffff:127.0.0.1")), "127.0.0.1")
    assert(ConnGuardConfig.DefaultTrustedIps.contains(ConnGuard.normalizeIp(ip("::ffff:127.0.0.1"))))

  test("normalizeIp: distinct adjacent v6 peers never share a bucket"):
    val a = ConnGuard.normalizeIp(ip("2001:db8::1"))
    val b = ConnGuard.normalizeIp(ip("2001:db8::2"))
    assertEquals(a, "2001:db8::1")
    assertEquals(b, "2001:db8::2")
    assertNotEquals(a, b)

  test("real-input loopback bypass: typed ::1 over per-ip cap is still admitted"):
    val loopKey = ConnGuard.normalizeIp(ip("::1"))
    for
      g <- guard(ConnGuardConfig(wsPerIpCap = 1, wsTotalCap = 100))
      _ <- g.acquireWs(loopKey)
      _ <- g.acquireWs(loopKey)
      over <- g.checkWs(loopKey)
      v4map <- g.checkWs(ConnGuard.normalizeIp(ip("::ffff:127.0.0.1")))
    yield
      assertEquals(over, None)
      assertEquals(v4map, None)

  // ── env 解析：非法值回落默认，不 crash ──────────────────────────────────

  test("fromEnv: defaults, overrides, off-switch, trusted list, junk falls back"):
    val d = ConnGuardConfig.fromEnv(_ => None)
    assertEquals(d.wsPerIpCap, 64)
    assertEquals(d.wsTotalCap, 1024)
    assertEquals(d.warnPct, 80)
    assertEquals(d.maxTrackedIps, 1024)
    assert(d.trustedIps.contains("127.0.0.1"))

    val o = ConnGuardConfig.fromEnv {
      case "GATEWAY_CONN_GUARD_WS_PER_IP_CAP"    => Some("32")
      case "GATEWAY_CONN_GUARD_WS_TOTAL_CAP"     => Some("512")
      case "GATEWAY_CONN_GUARD"                  => Some("off")
      case "GATEWAY_CONN_GUARD_TRUSTED_IPS"      => Some(" 100.91.165.120 , 10.0.0.7 ")
      case "GATEWAY_CONN_GUARD_WARN_PCT"         => Some("not-a-number")
      case "GATEWAY_CONN_GUARD_MAX_TRACKED_IPS"  => Some("128")
      case _                  => None // lookup 是全函数：未提及的名字一律「未设置」
    }
    assertEquals(o.wsPerIpCap, 32)
    assertEquals(o.wsTotalCap, 512)
    assertEquals(o.enabled, false)
    assertEquals(o.warnPct, 80) // 非法值回落
    assertEquals(o.maxTrackedIps, 128)
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
      reqUntrackedTotal = 0,
      wsPerIpCap = 64,
      wsTotalCap = 1024,
      maxTrackedIps = 1024,
      maxConnections = 4096,
      warnPct = 80,
      topWs = Vector(("9.9.9.1", wsTotal)),
      topReq = Vector.empty,
    )
    assertEquals(ConnGuard.warnLine(snap(819)), None) // 79%
    assert(ConnGuard.warnLine(snap(820)).isDefined) // 80%
    assert(ConnGuard.warnLine(snap(820)).exists(_.contains("80%")))
    assert(ConnGuard.snapshotLine(snap(12)).contains("ws=12/1024"))
    assert(ConnGuard.snapshotLine(snap(12)).contains("top=[9.9.9.1:12]"))
    assert(ConnGuard.snapshotLine(snap(12)).contains("trackedIps=1/1024"))

  // ── 预警腿②（判词项 (c) 口径对齐）：连接代理腿各自独立，不与 WS 腿同相 ──────

  test("connWarnLine (fd vs ember maxConnections) is independent of the ws leg"):
    val base = ConnGuard.Snapshot(
      enabled = true,
      wsTotal = 10, // WS 腿远未到 80%（10/1024）
      trackedIps = 1,
      overflowConns = 0,
      acceptedTotal = 10L,
      rejectedTotal = 0,
      evictedTotal = 0,
      reqTotal = 0,
      reqUntrackedTotal = 0,
      wsPerIpCap = 64,
      wsTotalCap = 1024,
      maxTrackedIps = 1024,
      maxConnections = 4096,
      warnPct = 80,
      topWs = Vector(("9.9.9.1", 10)),
      topReq = Vector.empty,
    )
    assertEquals(ConnGuard.warnLine(base), None) // WS 腿静默
    assertEquals(ConnGuard.connWarnLine(base, 3276L), None) // 79.98% fd ⇒ 静默
    assert(ConnGuard.connWarnLine(base, 3277L).isDefined) // ≥80% fd ⇒ 触发
    assert(ConnGuard.connWarnLine(base, 3277L).exists(_.contains("maxConnections")))
    assertEquals(ConnGuard.connWarnLine(base, -1L), None) // fd 不可得 ⇒ 不猜、不预警
    assertEquals(ConnGuard.connWarnLine(base.copy(enabled = false), 4096L), None)
    // 双向独立性：fd 腿静默而 WS 腿触发
    assertEquals(ConnGuard.connWarnLine(base.copy(wsTotal = 820), 100L), None)
    assert(ConnGuard.warnLine(base.copy(wsTotal = 820)).isDefined)

  test("healthJson exposes both warn calibers and the bounded-table readings"):
    val s = ConnGuard.Snapshot(
      enabled = true,
      wsTotal = 3,
      trackedIps = 7,
      overflowConns = 1,
      acceptedTotal = 4L,
      rejectedTotal = 2L,
      evictedTotal = 5L,
      reqTotal = 9L,
      reqUntrackedTotal = 4L,
      wsPerIpCap = 64,
      wsTotalCap = 1024,
      maxTrackedIps = 1024,
      maxConnections = 4096,
      warnPct = 80,
      topWs = Vector(("9.9.9.1", 3)),
      topReq = Vector(("9.9.9.1", 9L)),
    )
    val j = ConnGuard.healthJson(s, 42L)
    val caps = j.hcursor.downField("caps")
    assertEquals(caps.get[Int]("maxTrackedIps").toOption, Some(1024))
    assertEquals(caps.get[Int]("maxConnections").toOption, Some(4096))
    assertEquals(j.hcursor.downField("requests").get[Long]("untrackedTotal").toOption, Some(4L))
    assertEquals(j.hcursor.get[Long]("fdCount").toOption, Some(42L))
end ConnGuardSpec
