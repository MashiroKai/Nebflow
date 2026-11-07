package nebflow.neblink

import nebflow.neblink.relay.PresenceCandidateProbe

import nebflow.neblink.core.NeblinkEndpoint

import nebflow.shared.PeerInfo

/**
 * `PresenceCandidateProbe` 的单测（纯函数，零网络、零副作用）。
 *
 * 覆盖 presence-direct 批（2026-11-04）代码侧仅有的两个实质改动：
 *  - **C-3 上报面卫生**：链路本地 / 未指定 / 组播 / 隧道网卡的污染地址不再进候选面，
 *    且**不误杀** `100.64/10`（tailnet 是首选直连面），过滤为空时原样返回；
 *  - **C-1 候选集保真**：入站观察记录（`endpoints = Nil`）不再把名册候选抹成单地址。
 *
 * 判据来源：`evidence/presence-direct-261104/audit/{audit,fix-spec}.md`。
 * 本 spec 不读任何进程/网络状态 ⇒ 在任何机器上确定性通过（宿主 tailnet 是否在线无关）。
 */
class PresenceCandidateProbeSpec extends munit.FunSuite:

  private def ep(address: String, port: Int = 6138, kind: String = "lan", label: String = ""): NeblinkEndpoint =
    NeblinkEndpoint(address, port, kind, label)

  private def peer(
    deviceId: String,
    address: String,
    endpoints: List[String] = Nil
  ): PeerInfo =
    PeerInfo(
      deviceId = deviceId,
      deviceName = s"dev-$deviceId",
      platform = "macos",
      address = address,
      endpoints = endpoints
    )

  // ===== 常数（防漂移：改值必须改注释里的依据）=====

  test("常数与拨号腿口径对齐：上报封顶 4 条 / TCP 建连上界 = 最贵档预算 3000ms") {
    assertEquals(PresenceCandidateProbe.MaxReportedEndpoints, 4)
    assertEquals(PresenceCandidateProbe.MaxConnectTimeoutMs, 3_000L)
  }

  // ===== isUnreportableAddress =====

  test("isUnreportableAddress：链路本地/未指定/回环/组播/广播 判为不可上报") {
    assert(PresenceCandidateProbe.isUnreportableAddress("169.254.12.7"), "APIPA 必须丢")
    assert(PresenceCandidateProbe.isUnreportableAddress("0.0.0.0"), "未指定地址必须丢")
    assert(PresenceCandidateProbe.isUnreportableAddress("127.0.0.1"), "回环必须丢（对端拨的是它自己）")
    assert(PresenceCandidateProbe.isUnreportableAddress("127.1.2.3"))
    assert(PresenceCandidateProbe.isUnreportableAddress("224.0.0.1"), "组播必须丢")
    assert(PresenceCandidateProbe.isUnreportableAddress("239.255.255.250"))
    assert(PresenceCandidateProbe.isUnreportableAddress("255.255.255.255"), "广播必须丢")
    assert(PresenceCandidateProbe.isUnreportableAddress(""), "空串必须丢")
    assert(PresenceCandidateProbe.isUnreportableAddress("   "))
  }

  test("isUnreportableAddress：tailnet / 私网 / 公网 一律放行（100.64/10 绝不能被丢）") {
    assert(!PresenceCandidateProbe.isUnreportableAddress("100.64.0.1"), "tailnet 段首地址必须保留")
    assert(!PresenceCandidateProbe.isUnreportableAddress("100.91.165.120"), "本批事故里的对端 tailnet IP")
    assert(!PresenceCandidateProbe.isUnreportableAddress("100.127.149.106"), "本机 tailnet IP")
    assert(!PresenceCandidateProbe.isUnreportableAddress("192.168.1.145"))
    assert(!PresenceCandidateProbe.isUnreportableAddress("10.0.0.5"))
    assert(!PresenceCandidateProbe.isUnreportableAddress("100.65.49.141"), "运营商 CGNAT 私网段（可上报，可达性归拨号）")
    assert(!PresenceCandidateProbe.isUnreportableAddress("203.0.113.9"))
    // 非 IPv4 字面量不在此判据范围内 ⇒ 放行，避免把主机名一刀切掉。
    assert(!PresenceCandidateProbe.isUnreportableAddress("kai.local"))
  }

  // ===== isTunnelNicLabel =====

  test("isTunnelNicLabel：旧 marker 表漏掉的隧道/虚拟适配器被识别") {
    assert(PresenceCandidateProbe.isTunnelNicLabel("TAP-Windows Adapter V9"))
    assert(PresenceCandidateProbe.isTunnelNicLabel("utun3"))
    assert(PresenceCandidateProbe.isTunnelNicLabel("ZeroTier One [8056c2e21c000001]"))
    assert(PresenceCandidateProbe.isTunnelNicLabel("Hamachi"))
    assert(PresenceCandidateProbe.isTunnelNicLabel("Npcap Loopback Adapter"))
    assert(PresenceCandidateProbe.isTunnelNicLabel("WireGuard Tunnel"))
    assert(PresenceCandidateProbe.isTunnelNicLabel("OpenVPN Wintun"))
  }

  test("isTunnelNicLabel：真实网卡不误判（含 tailscale 本身：不在 marker 表里）") {
    assert(!PresenceCandidateProbe.isTunnelNicLabel("Realtek PCIe GbE Family Controller"))
    assert(!PresenceCandidateProbe.isTunnelNicLabel("Wi-Fi"))
    assert(!PresenceCandidateProbe.isTunnelNicLabel("en0"))
    assert(!PresenceCandidateProbe.isTunnelNicLabel("Tailscale"))
    assert(!PresenceCandidateProbe.isTunnelNicLabel(""))
  }

  // ===== sanitizeReported（C-3）=====

  test("sanitizeReported：丢污染地址、保序、去重、封顶 4 条") {
    val raw = List(
      ep("169.254.12.7", label = "en7"), // APIPA ⇒ 丢
      ep("100.91.165.120", kind = "tailnet", label = "utun3"), // tailnet 挂在 utun 上也必须保留
      ep("192.168.1.145", label = "en0"),
      ep("192.168.1.145", label = "en0"), // 重复 ⇒ 只留一条
      ep("10.8.0.6", label = "TAP-Windows Adapter V9"), // 隧道网卡的非 tailnet ⇒ 丢
      ep("0.0.0.0", label = "en1"), // 未指定 ⇒ 丢
      ep("203.0.113.9", label = "en0"),
      ep("198.51.100.7", label = "en0") // 第 5 条存活候选 ⇒ 被 max 截断
    )
    val got = PresenceCandidateProbe.sanitizeReported(raw)
    assertEquals(
      got.map(_.address),
      List("100.91.165.120", "192.168.1.145", "203.0.113.9", "198.51.100.7"),
      "保序（只删不重排）+ 去重 + 封顶"
    )
    assert(!got.exists(_.address == "169.254.12.7"))
    assert(!got.exists(_.address == "10.8.0.6"), "隧道网卡的非 tailnet 地址必须丢")
    assert(got.size <= PresenceCandidateProbe.MaxReportedEndpoints)
  }

  test("sanitizeReported：同地址不同端口是合法候选（去重键 = address+port）") {
    val raw = List(ep("192.168.1.145", port = 6138), ep("192.168.1.145", port = 8097))
    assertEquals(PresenceCandidateProbe.sanitizeReported(raw).map(_.port), List(6138, 8097))
  }

  test("sanitizeReported：全是污染地址 ⇒ 原样返回入参（never lose all endpoints）") {
    val raw = List(ep("169.254.1.2"), ep("0.0.0.0"))
    val got = PresenceCandidateProbe.sanitizeReported(raw)
    assertEquals(got, raw, "过滤后为空必须回退到入参，宁可多报一条也不让对端无候选")
  }

  test("sanitizeReported：只有隧道网卡地址 ⇒ 退到 reportable 档（不返回空）") {
    val raw = List(ep("10.8.0.6", label = "utun2"), ep("10.9.0.3", label = "ZeroTier One"))
    assertEquals(PresenceCandidateProbe.sanitizeReported(raw).map(_.address), List("10.8.0.6", "10.9.0.3"))
  }

  test("sanitizeReported：空输入与 max 参数") {
    assertEquals(PresenceCandidateProbe.sanitizeReported(Nil), Nil)
    val raw = (1 to 6).toList.map(i => ep(s"192.168.1.$i"))
    assertEquals(
      PresenceCandidateProbe.sanitizeReported(raw, max = 2).map(_.address),
      List("192.168.1.1", "192.168.1.2")
    )
    assertEquals(PresenceCandidateProbe.sanitizeReported(raw).size, PresenceCandidateProbe.MaxReportedEndpoints)
  }

  // ===== mergeObserved（C-1）=====

  test("mergeObserved：入站观察记录（endpoints = Nil）不得抹掉名册候选") {
    val roster = peer(
      "kai",
      "http://100.91.165.120:6138",
      List("http://100.91.165.120:6138", "http://192.168.1.145:6138")
    )
    val observed = peer("kai", "http://100.65.49.141:6138") // 对端眼里的我的源地址（CGNAT 段，结构性不可达）
    val merged = PresenceCandidateProbe.mergeObserved(Some(roster), observed)
    assertEquals(merged.address, "http://100.65.49.141:6138", "此刻观察到的活地址排第一")
    assertEquals(
      merged.endpoints,
      List("http://100.65.49.141:6138", "http://100.91.165.120:6138", "http://192.168.1.145:6138")
    )
  }

  test("mergeObserved：改前行为对照——整记录替换会把候选集塌缩成 Nil（本 spec 的验红点）") {
    val observed = peer("kai", "http://100.65.49.141:6138")
    assertEquals(observed.endpoints, Nil, "生产者写 Nil 是缺陷形态本身")
    val merged = PresenceCandidateProbe.mergeObserved(
      Some(peer("kai", "http://a:1", List("http://a:1", "http://b:2"))),
      observed
    )
    assert(merged.endpoints.size >= 2, "改后候选数必须 ≥ 名册候选数")
  }

  test("mergeObserved：prev = None ⇒ 单条（与改前等价）") {
    val merged = PresenceCandidateProbe.mergeObserved(None, peer("kai", "http://192.168.1.145:6138"))
    assertEquals(merged.endpoints, List("http://192.168.1.145:6138"))
    assertEquals(merged.address, "http://192.168.1.145:6138")
  }

  test("mergeObserved：observed.endpoints 非空 ⇒ 原样返回（名册腿零行为变化）") {
    val roster = peer(
      "kai",
      "http://100.91.165.120:6138",
      List("http://100.91.165.120:6138", "http://192.168.1.145:6138")
    )
    val prev = peer("kai", "http://100.65.49.141:6138", List("http://100.65.49.141:6138"))
    assertEquals(PresenceCandidateProbe.mergeObserved(Some(prev), roster), roster)
  }

  test("mergeObserved：同一源地址重复到达是幂等的（不逐拍侵蚀名册候选）") {
    val roster = peer("kai", "http://100.0.0.1:6138", List("http://a:1", "http://b:2", "http://c:3", "http://d:4"))
    val obs = peer("kai", "http://x:1")
    val once = PresenceCandidateProbe.mergeObserved(Some(roster), obs)
    val twice = PresenceCandidateProbe.mergeObserved(Some(once), obs)
    assertEquals(twice, once, "同一观察地址重复合并 ⇒ 幂等")
    assertEquals(once.endpoints.head, "http://x:1")
    assertEquals(once.endpoints.size, PresenceCandidateProbe.MaxReportedEndpoints)
  }

  test("mergeObserved：address 恒等于 endpoints.head 不变量") {
    val cases = List(
      PresenceCandidateProbe.mergeObserved(None, peer("a", "http://192.168.1.5:6138")),
      PresenceCandidateProbe.mergeObserved(
        Some(peer("a", "http://1.1.1.1:1", List("http://1.1.1.1:1"))),
        peer("a", "http://2.2.2.2:2")
      ),
      PresenceCandidateProbe.mergeObserved(
        Some(peer("a", "http://1.1.1.1:1", List("http://1.1.1.1:1"))),
        peer("a", "")
      )
    )
    cases.foreach { p =>
      assert(p.endpoints.nonEmpty, s"候选集不得为空：$p")
      assertEquals(p.address, p.endpoints.head, s"address 必须是 endpoints.head：$p")
    }
  }

  test("mergeObserved：空地址退化输入不得产生空候选（回退到既有候选首条）") {
    val merged = PresenceCandidateProbe.mergeObserved(
      Some(peer("a", "http://1.1.1.1:1", List("http://1.1.1.1:1"))),
      peer("a", "")
    )
    assertEquals(merged.endpoints, List("http://1.1.1.1:1"))
    assertEquals(merged.address, "http://1.1.1.1:1")
  }

  test("mergeObserved：多重观察后候选数封顶 max，且不丢最新观察地址") {
    val roster = peer("kai", "http://a:1", List("http://a:1", "http://b:2", "http://c:3", "http://d:4"))
    val merged = PresenceCandidateProbe.mergeObserved(Some(roster), peer("kai", "http://z:9"))
    assertEquals(merged.endpoints.size, PresenceCandidateProbe.MaxReportedEndpoints)
    assertEquals(merged.endpoints.head, "http://z:9")
    assert(merged.endpoints.contains("http://a:1"), "名册首条候选必须还在")
  }

  // ===== IPv6 优先批（2026-11-04）：v6 进候选面，但只在能处理它的地方 =====

  test("v6: isUnreportableAddress 的 v6 语义与 v4 同族（链路本地/回环/未指定/组播 丢，全局与 ULA 留）") {
    assert(PresenceCandidateProbe.isUnreportableAddress("fe80::1"), "v6 链路本地 = v6 版 APIPA，必须丢")
    assert(PresenceCandidateProbe.isUnreportableAddress("fe80::1%en0"), "带 scope 的链路本地照样丢")
    assert(PresenceCandidateProbe.isUnreportableAddress("::1"), "v6 回环必须丢")
    assert(PresenceCandidateProbe.isUnreportableAddress("::"), "v6 未指定必须丢")
    assert(PresenceCandidateProbe.isUnreportableAddress("ff02::1"), "v6 组播必须丢")
    assert(!PresenceCandidateProbe.isUnreportableAddress("2001:db8::1"), "全局单播必须留")
    assert(
      !PresenceCandidateProbe.isUnreportableAddress("fd7a:115c:a1e0::1"),
      "Tailscale ULA 与 100.64/10 同权：它是首选直连面，绝不能丢"
    )
    assert(!PresenceCandidateProbe.isUnreportableAddress("2408:8207:1234::1"), "运营商全局 v6 必须留")
  }

  test("v6: preferV6 = v6 前置 + v4 必留槽位 + 无 v6 原样返回") {
    val v4a = ep("192.168.1.145")
    val v4b = ep("100.65.49.141")
    val v6a = ep("2408:8207:1234::1")
    val v6b = ep("2408:8207:1234::2")
    val v6c = ep("2408:8207:1234::3")
    // 无 v6 ⇒ 逐字节原样（无 v6 环境行为不变）。
    assertEquals(PresenceCandidateProbe.preferV6(List(v4a, v4b)), List(v4a, v4b))
    // 有 v6 ⇒ v6 前置，v4 紧随其后（不会被挤出 4 条上限）。
    assertEquals(PresenceCandidateProbe.preferV6(List(v4a, v6a, v4b, v6b)), List(v6a, v6b, v4a, v4b))
    // v6 超过槽位 ⇒ 溢出的 v6 排到 v4 之后（v4 必留槽位）。
    assertEquals(
      PresenceCandidateProbe.preferV6(List(v4a, v6a, v6b, v6c)),
      List(v6a, v6b, v4a, v6c),
      "3 条 v6 时只允许 2 条占前位，v4 必须留在前面"
    )
    // 与 sanitizeReported 串联后：4 条上限内一定含 v4（改前的「唯一可用 v4 被挤出」不发生）。
    val chosen = PresenceCandidateProbe.sanitizeReported(
      PresenceCandidateProbe.preferV6(List(v4a, v6a, v6b, v6c))
    )
    assert(chosen.exists(e => e.address == "192.168.1.145"), s"v4 候选必须在 4 条上限内：$chosen")
    assert(chosen.head.address.startsWith("2408:"), s"v6 优先：$chosen")
  }

  // ===== tailnet v6 豁免对称（本批修复的验真点）=====

  test("v6: 隧道网卡上的 tailnet v6 ULA 与 100.64/10 同权保留（豁免不得只认 v4）") {
    val tailnetV6 = ep("fd7a:115c:a1e0::1", kind = "tailscale", label = "utun3")
    val lanV4 = ep("192.168.1.145", label = "en0")
    val got = PresenceCandidateProbe.sanitizeReported(List(tailnetV6, lanV4))
    assert(
      got.exists(_.address == "fd7a:115c:a1e0::1"),
      s"macOS 上 tailnet 跑在 utun*，其 v6 ULA 是首选直连面——存在普通候选时也不得被结构性丢弃：$got"
    )
    assertEquals(got.map(_.address), List("fd7a:115c:a1e0::1", "192.168.1.145"), "保序：只删不重排")
  }

  test("v6: 隧道网卡上的非 tailnet 公网 v6 仍被丢（豁免只给 tailnet，不给所有 utun 地址）") {
    val publicV6 = ep("2001:db8::1", label = "utun3")
    val lanV4 = ep("192.168.1.145", label = "en0")
    val got = PresenceCandidateProbe.sanitizeReported(List(publicV6, lanV4))
    assertEquals(got.map(_.address), List("192.168.1.145"), "utun 上的普通公网 v6 属于隧道污染面，必须丢")
  }
