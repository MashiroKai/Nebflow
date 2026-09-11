package nebflow.neblink

import io.circe.parser.decode
import io.circe.syntax.*
import munit.CatsEffectSuite

/**
 * C1 回归网（2026-09-11 P2P 直连修复批，方案 §2 选项 C1 / §3.2-3.3）。
 *
 * 两件事：
 *  ① [[EndpointPreference]] 的择优顺序是纯函数 ⇒ 直接钉
 *     「`100.64.0.0/10` 优先 → 本机同网段 → 其余」以及**稳定性**（同秩保持服务端顺序）；
 *  ② `PeerInfo.endpoints` 新字段的**解码向后兼容**（缺省 = `Nil`）+ 序列化往返——
 *     它是 REST/前端可见结构，默认值缺失会让所有既有持久化记录 / API payload 解不出来。
 *
 * 事故形态用第 1 条用例原样复现：服务端给 Mac 的 KAI 端点集是
 * `[192.168.1.145(LAN, 非直连), 100.91.165.120(Tailscale, 实测可达)]`，改前接收侧只取
 * `head` ⇒ 拨号必失败 ⇒ `directOnline=false` ⇒ 9/9 relay。择优后 head = Tailscale。
 */
class EndpointPreferenceSpec extends CatsEffectSuite:

  // ===== ① 择优顺序 =====

  test("C1: 事故形态 —— LAN 在首、Tailscale 在次 ⇒ 择优后 Tailscale 成为 head") {
    val serverOrder = List("http://192.168.1.145:8080", "http://100.91.165.120:8080")
    val ordered = EndpointPreference.order(serverOrder, Set("192.168.2."))
    assertEquals(
      ordered,
      List("http://100.91.165.120:8080", "http://192.168.1.145:8080"),
      "100.64.0.0/10 必须优先于不可达的 LAN 端点"
    )
    assertEquals(ordered.head, "http://100.91.165.120:8080", "head = 拨号首候选 = 新的 peer.address")
  }

  test("C1: 无 Tailscale 时，本机同网段优先于其余") {
    val ordered = EndpointPreference.order(
      List("http://10.0.0.5:8080", "http://192.168.2.44:8080", "http://172.16.9.9:8080"),
      Set("192.168.2.")
    )
    assertEquals(ordered.head, "http://192.168.2.44:8080")
    assertEquals(ordered.tail, List("http://10.0.0.5:8080", "http://172.16.9.9:8080"), "其余保持原顺序")
  }

  test("C1: 同秩保持服务端顺序（稳定排序——已排好的名册不被搅动）") {
    val asIs = List("http://100.1.1.1:8080", "http://100.2.2.2:8080", "http://100.3.3.3:8080")
    assertEquals(EndpointPreference.order(asIs, Set.empty), asIs)
    val rest = List("http://9.9.9.9:8080", "http://8.8.8.8:8080")
    assertEquals(EndpointPreference.order(rest, Set.empty), rest)
  }

  test("C1: 去重 + 空表安全") {
    assertEquals(
      EndpointPreference.order(List("http://100.1.1.1:8080", "http://100.1.1.1:8080"), Set.empty),
      List("http://100.1.1.1:8080")
    )
    assertEquals(EndpointPreference.order(Nil, Set.empty), Nil)
    assertEquals(EndpointPreference.order(List("", "http://a:1"), Set.empty), List("http://a:1"))
  }

  test("C1: rank 三档 + host 解析（含路径/无端口/IPv6 字面量）") {
    assertEquals(EndpointPreference.hostOf("http://100.127.149.106:8080"), "100.127.149.106")
    assertEquals(EndpointPreference.hostOf("https://192.168.2.101"), "192.168.2.101")
    assertEquals(EndpointPreference.hostOf("http://host.local:9000/x/y"), "host.local")
    assertEquals(EndpointPreference.hostOf("http://[fe80::1]:8080"), "fe80::1")

    assert(EndpointPreference.isTailscaleHost("100.64.0.1"), "CGNAT 下界")
    assert(EndpointPreference.isTailscaleHost("100.127.255.254"), "CGNAT 上界")
    assert(!EndpointPreference.isTailscaleHost("100.63.255.254"), "下界外")
    assert(!EndpointPreference.isTailscaleHost("100.128.0.1"), "上界外")
    assert(!EndpointPreference.isTailscaleHost("192.168.2.101"), "私网不是 CGNAT")
    assert(!EndpointPreference.isTailscaleHost("tailscale-host"), "非 IPv4 不误判")

    val local = Set("192.168.2.")
    assertEquals(EndpointPreference.rank("http://100.64.0.1:8080", local), 0, "CGNAT 下界 ⇒ Tailscale 档")
    assertEquals(EndpointPreference.rank("http://100.127.255.254:8080", local), 0, "CGNAT 上界 ⇒ Tailscale 档")
    assertEquals(
      EndpointPreference.rank("http://100.1.1.1:8080", local),
      2,
      "100.1.1.1 不在 100.64.0.0/10 内（是公网段）⇒ 不得误判为 Tailscale"
    )
    assertEquals(EndpointPreference.rank("http://192.168.2.9:8080", local), 1)
    assertEquals(EndpointPreference.rank("http://10.0.0.9:8080", local), 2)
  }

  test("C1: localPrefixesOf 取本机 /24（含 Tailscale 网卡自身）") {
    assertEquals(
      EndpointPreference.localPrefixesOf(List("192.168.2.101", "100.127.149.106", "not-an-ip")),
      Set("192.168.2.", "100.127.149.")
    )
    assertEquals(EndpointPreference.subnet24("10.1.2.3"), Some("10.1.2."))
    assertEquals(EndpointPreference.subnet24("nope"), None)
  }

  // ===== ② PeerInfo.endpoints 向后兼容 =====

  test("C1: 缺省 endpoints 的 PeerInfo 解码 ⇒ Nil（既有 payload / 持久化记录不回归）") {
    val legacy =
      """{"deviceId":"d1","deviceName":"KAI","platform":"macos",
        |"address":"http://192.168.1.145:8080","deviceSecret":"s","capabilities":{},
        |"userDescription":"","lastSeen":1789118377265}""".stripMargin
    val decoded = decode[PeerInfo](legacy)
    assert(decoded.isRight, s"legacy payload must still decode: $decoded")
    val p = decoded.toOption.get
    assertEquals(p.endpoints, Nil, "缺省 ⇒ Nil（= 单地址行为，pre-C1）")
    assertEquals(p.address, "http://192.168.1.145:8080")
    assertEquals(p.lastSeen, 1789118377265L)
  }

  test("C1: 带 endpoints 的 PeerInfo 解码保留全端点") {
    val payload =
      """{"deviceId":"d1","deviceName":"KAI","platform":"macos",
        |"address":"http://100.91.165.120:8080",
        |"endpoints":["http://100.91.165.120:8080","http://192.168.1.145:8080"],
        |"lastSeen":1}""".stripMargin
    val p = decode[PeerInfo](payload).toOption.get
    assertEquals(p.endpoints, List("http://100.91.165.120:8080", "http://192.168.1.145:8080"))
  }

  test("C1: PeerInfo 构造缺省 endpoints = Nil") {
    assertEquals(PeerInfo("d1", "KAI", "macos", "http://a:8080").endpoints, Nil)
  }

  test("C1: PeerInfo 编解码往返保留 endpoints（REST/前端可见结构）") {
    val p = PeerInfo(
      deviceId = "d1",
      deviceName = "KAI",
      platform = "macos",
      address = "http://100.91.165.120:8080",
      endpoints = List("http://100.91.165.120:8080", "http://192.168.1.145:8080")
    )
    assertEquals(decode[PeerInfo](p.asJson.noSpaces), Right(p))
  }

  // ===== ③ toNeblinkPeers：全端点保留 + 择优（无需网络——纯映射） =====

  test("C1: toNeblinkPeers 保留全部端点并择优（改前丢弃 endpoints.tail）") {
    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://unused.invalid", networkId = "n", secret = "s"),
      8080
    )
    val outgoing = NeblinkPeerInfo(
      deviceId = "kai-1",
      deviceName = "KAI",
      platform = "macos",
      endpoints = List(
        NeblinkEndpoint("192.168.1.145", 8080, "lan", "en0"),
        NeblinkEndpoint("100.91.165.120", 8080, "tailscale", "utun2")
      ),
      online = true
    )
    val peers = client.toNeblinkPeers(List(outgoing))
    assertEquals(peers.size, 1)
    val p = peers.head
    assertEquals(
      p.endpoints,
      List("http://100.91.165.120:8080", "http://192.168.1.145:8080"),
      "全端点保留 + 择优排序（这一条改前会退化成只剩 head）"
    )
    assertEquals(p.address, "http://100.91.165.120:8080", "address = 择优 head")
    assertEquals(p.endpoints.head, p.address, "address 与 candidates.head 必须一致")
  }

  test("C1: toNeblinkPeers 单端点 ⇒ 行为不回归（endpoints = List(address)）") {
    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://unused.invalid", networkId = "n", secret = "s"),
      8080
    )
    val peers = client.toNeblinkPeers(
      List(
        NeblinkPeerInfo("k", "KAI", "macos", List(NeblinkEndpoint("192.168.1.145", 8080, "lan", "en0")), online = true)
      )
    )
    assertEquals(peers.head.address, "http://192.168.1.145:8080")
    assertEquals(peers.head.endpoints, List("http://192.168.1.145:8080"))
  }

  test("C1: toNeblinkPeers 仍过滤无端点 peer（既有契约不变）") {
    val client = new NeblinkClient(
      NeblinkServerConfig(url = "http://unused.invalid", networkId = "n", secret = "s"),
      8080
    )
    assertEquals(client.toNeblinkPeers(List(NeblinkPeerInfo("k", "KAI", "macos", Nil, online = true))), Nil)
  }

end EndpointPreferenceSpec
