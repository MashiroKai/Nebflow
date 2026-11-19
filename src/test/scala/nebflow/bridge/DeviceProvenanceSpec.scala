package nebflow.bridge

import io.circe.parser.parse
import munit.FunSuite
import nebflow.shared.ContentBlock

/**
 * Offline spec for the device provenance stamp (device-provenance batch,
 * 2026-10-23). Pure functions only — frame decode, roster resolve, marker
 * render; the gateway glue is pinned by the source-contract gate
 * (`gateway.DeviceProvenanceInjectionGateSpec`).
 *
 * The stamp refines the inbound source marker from channel-level (WeChat /
 * Feishu, [[BridgeOrigin]] — untouched here) to device-level: which device a
 * console message came from, and whether that device is in the platform device
 * roster. Same face discipline as the channel marker: model face only, session
 * display face stays bare content.
 */
class DeviceProvenanceSpec extends FunSuite:

  // ─────────── 帧解析面：ClientDevice.fromFrame ───────────

  test("fromFrame decodes the full device declaration (auto-tagging shape: no manual name)") {
    val frame = parse("""{"sessionId":"s1","content":"hi","device":{
        "clientId":"c-123","platform":"phone","browser":"Chrome","boundDeviceId":"dev-9"
      }}""").toOption.get
    val d = ClientDevice.fromFrame(frame).getOrElse(fail("expected a ClientDevice"))
    assertEquals(d, ClientDevice("c-123", Some("phone"), Some("Chrome"), Some("dev-9")))
  }

  test("fromFrame: absent device key / null device / absent or blank clientId all decode to None (never stamp)") {
    val noKey = parse("""{"sessionId":"s1","content":"hi"}""").toOption.get
    assertEquals(ClientDevice.fromFrame(noKey), None, "CLI userMessage / 旧前端帧不标注")
    val nullDevice = parse("""{"sessionId":"s1","device":null,"content":"hi"}""").toOption.get
    assertEquals(ClientDevice.fromFrame(nullDevice), None)
    val noClientId = parse("""{"device":{"platform":"phone"}}""").toOption.get
    assertEquals(ClientDevice.fromFrame(noClientId), None, "clientId 是身份主键，缺席不标注")
    val blankClientId = parse("""{"device":{"clientId":"   "}}""").toOption.get
    assertEquals(ClientDevice.fromFrame(blankClientId), None)
  }

  test("fromFrame trims and tolerates blank optional fields (they fold to None); the retired manual name key is ignored") {
    val frame = parse("""{"device":{"clientId":" c1 ","name":"  ","platform":" MAC ","browser":""}}""").toOption.get
    assertEquals(
      ClientDevice.fromFrame(frame),
      Some(ClientDevice("c1", Some("MAC"), None, None))
    )
    // 标注全自动批（2026-10-26）：设置页手动命名退役——旧前端/旧存储残留的
    // name 键不再进解码面（孤儿键零消费，绝不复活第二显示名真源）。
    val legacyNamed = parse("""{"device":{"clientId":"c1","name":"我的手机","platform":"mac"}}""").toOption.get
    assertEquals(
      ClientDevice.fromFrame(legacyNamed),
      Some(ClientDevice("c1", Some("mac"), None, None)),
      "手动命名键一律忽略"
    )
  }

  // ─────────── 可读默认名 ───────────

  test("platformDisplay maps the protocol values to readable ends; unknown stays honest") {
    assertEquals(ClientDevice.platformDisplay(Some("phone")), "手机")
    assertEquals(ClientDevice.platformDisplay(Some("tablet")), "平板")
    assertEquals(ClientDevice.platformDisplay(Some("mac")), "Mac")
    assertEquals(ClientDevice.platformDisplay(Some("windows")), "Windows")
    assertEquals(ClientDevice.platformDisplay(Some("linux")), "Linux")
    assertEquals(ClientDevice.platformDisplay(Some("IPHONE")), "未知设备", "值域外不猜")
    assertEquals(ClientDevice.platformDisplay(None), "未知设备")
    assertEquals(ClientDevice.platformDisplay(Some("  ")), "未知设备")
  }

  test("defaultName follows the author's verbatim example shape 未登记-手机 Chrome") {
    assertEquals(
      ClientDevice.defaultName(ClientDevice("c", Some("phone"), Some("Chrome"))),
      "未登记-手机 Chrome"
    )
    assertEquals(
      ClientDevice.defaultName(ClientDevice("c", Some("mac"), None)),
      "未登记-Mac",
      "浏览器缺席省略尾段"
    )
    assertEquals(
      ClientDevice.defaultName(ClientDevice("c", None, Some("Safari"))),
      "未登记-未知设备 Safari",
      "平台缺席降级未知设备，名字恒可读"
    )
  }

  // ─────────── 花名册解析面：resolve ───────────

  private val self = DeviceRosterEntry("self-1", "Mashiros-MacBook-Pro")
  private val peers = List(DeviceRosterEntry("win-1", "DESKTOP-IJBBEOT"), DeviceRosterEntry("phone-1", "Kaiyu-iPhone"))

  test("resolve: bound to self ⇒ enrolled with the roster device name (registration is authoritative)") {
    val r = DeviceProvenance.resolve(ClientDevice("c", Some("mac"), Some("Chrome"), Some("self-1")), Some(self), peers)
    assertEquals(r, ResolvedDevice("Mashiros-MacBook-Pro", enrolled = true))
  }

  test("resolve: bound to a peer ⇒ enrolled (phone / windows ends land the same way)") {
    assertEquals(
      DeviceProvenance.resolve(ClientDevice("c", Some("phone"), Some("Safari"), Some("phone-1")), Some(self), peers),
      ResolvedDevice("Kaiyu-iPhone", enrolled = true)
    )
    assertEquals(
      DeviceProvenance.resolve(ClientDevice("c", Some("windows"), Some("Edge"), Some("win-1")), Some(self), peers),
      ResolvedDevice("DESKTOP-IJBBEOT", enrolled = true)
    )
  }

  test("resolve: unbound / unknown binding ⇒ unenrolled with the readable default name (manual naming retired)") {
    val unbound = DeviceProvenance.resolve(ClientDevice("c", Some("phone"), Some("Chrome"), None), Some(self), peers)
    assertEquals(unbound, ResolvedDevice("未登记-手机 Chrome", enrolled = false))
    val stale = DeviceProvenance.resolve(ClientDevice("c", Some("mac"), Some("Chrome"), Some("gone-9")), Some(self), peers)
    assertEquals(stale, ResolvedDevice("未登记-Mac Chrome", enrolled = false), "花名册已消失的绑定 id 不冒认已登记")
    assertEquals(
      DeviceProvenance.resolve(ClientDevice("c", Some("mac"), Some("Chrome"), Some("  ")), Some(self), peers),
      ResolvedDevice("未登记-Mac Chrome", enrolled = false),
      "空白绑定等同未绑定"
    )
  }

  // ─────────── 自动标注三分支（标注全自动批 2026-10-26）───────────
  //
  // 客户端 deviceIdentity.js 按花名册自动判定绑定读数：登录会话 → 账号设备名
  // （roster self），配对设备 → 登记名（环回读 roster self），匿名 → 帧不带
  // boundDeviceId。网关侧按绑定命中与否落已登记/未登记——三分支在此钉死。

  test("auto branch ① login session: auto-bound to roster self ⇒ enrolled with the account device name") {
    val client = ClientDevice("c", Some("mac"), Some("Chrome"), Some(self.deviceId))
    assertEquals(
      DeviceProvenance.resolve(client, Some(self), peers),
      ResolvedDevice("Mashiros-MacBook-Pro", enrolled = true),
      "登录会话（鉴权读得 roster self）→ 账号登记设备名（已登记）"
    )
  }

  test("auto branch ② paired device: loopback-anonymous roster self lands the same enrolled registered name") {
    // 配对设备（无凭据环回读）与登录会话汇于同一绑定读数形态：self.id 命中。
    val client = ClientDevice("c", Some("windows"), Some("Edge"), Some("win-1"))
    assertEquals(
      DeviceProvenance.resolve(client, Some(self), peers),
      ResolvedDevice("DESKTOP-IJBBEOT", enrolled = true),
      "花名册命中即登记名（登记权威），与判定信号（登录/配对）无关"
    )
  }

  test("auto branch ③ anonymous: no auto binding ⇒ default 未登记-设备 浏览器 tag, never fake-enrolled") {
    val anon = DeviceProvenance.resolve(ClientDevice("c", Some("phone"), Some("Safari"), None), Some(self), peers)
    assertEquals(anon, ResolvedDevice("未登记-手机 Safari", enrolled = false))
    // 花名册整体不可得（未配对/取数失败）时即便带了绑定读数也一律未登记。
    val noRoster = DeviceProvenance.resolve(ClientDevice("c", Some("windows"), Some("Edge"), Some(self.deviceId)), None, Nil)
    assertEquals(noRoster, ResolvedDevice("未登记-Windows Edge", enrolled = false))
  }

  // ─────────── 标记渲染面：marker / compose / prependMarker ───────────

  test("marker names the console channel, the device and the enrollment state, in the channel-header skeleton") {
    val enrolled = DeviceProvenance.marker(ResolvedDevice("Mashiros-MacBook-Pro", enrolled = true))
    assertEquals(
      enrolled,
      """<system-reminder>
        |以下消息来自网页控制台（渠道 console）· 设备：Mashiros-MacBook-Pro（已登记）
        |</system-reminder>""".stripMargin
    )
    val unenrolled = DeviceProvenance.marker(ResolvedDevice("未登记-手机 Chrome", enrolled = false))
    assert(unenrolled.contains("设备：未登记-手机 Chrome（未登记）"), unenrolled)
  }

  test("marker reuses the BridgeOrigin fence constants (one fence pair repo-wide)") {
    val m = DeviceProvenance.marker(ResolvedDevice("x", enrolled = true))
    assert(m.startsWith(BridgeOrigin.MarkerOpen + "\n"), m)
    assert(m.endsWith("\n" + BridgeOrigin.MarkerClose), m)
  }

  test("compose places the marker block AHEAD of the content, one blank line apart") {
    val out = DeviceProvenance.compose(ResolvedDevice("Mac", enrolled = true), "hello")
    assert(out.startsWith(DeviceProvenance.MarkerOpen + "\n"), out)
    assert(out.endsWith(DeviceProvenance.MarkerClose + "\n\nhello"), out)
    assertEquals(out.split(DeviceProvenance.MarkerOpen, -1).length, 2, out)
  }

  test("prependMarker adds the marker as the FIRST standalone text block; content blocks stay untouched") {
    val content = List[ContentBlock](ContentBlock.Text("正文"), ContentBlock.Text("[用户附加图片: /tmp/a.png]"))
    val out = DeviceProvenance.prependMarker(ResolvedDevice("手机", enrolled = false), content)
    assertEquals(out.length, content.length + 1)
    assertEquals(out.head, ContentBlock.Text(DeviceProvenance.marker(ResolvedDevice("手机", enrolled = false))))
    assertEquals(out.tail, content, "正文块逐字不动")
  }

  test("prependMarker on an attachments-only frame: the marker becomes the only leading text block") {
    val out = DeviceProvenance.prependMarker(ResolvedDevice("手机", enrolled = false), Nil)
    assertEquals(out.length, 1)
    assert(out.head.isInstanceOf[ContentBlock.Text])
  }

  // ─────────── 门户身份透传批（方案 A，2026-10-28）：三级优先级 ───────────
  //
  // 作者拍板（五条裁定）：三级标记优先级 = boundDeviceId 花名册命中（已登记）
  // ＞ 会话 owner 等值（已认证，账号轨）＞ 默认名兜底逐字；状态词双轨（已登记/
  // 已认证并存）。每级一绿（该级命中时的期望形态）一红（不得越级/冒认的反例）。

  private val ownerAnchor = OwnerAnchor("sub-owner-1", "Mashiro")
  private val ownerSession = SessionIdentity("sub-owner-1", "relay-mint", "sess-1")
  private val otherSubSession = SessionIdentity("sub-someone-else", "relay-mint", "sess-2")

  test("tier1 green: boundDeviceId roster hit wins even when the session is also the owner (registered name is richer)") {
    val r = DeviceProvenance.resolve(
      ClientDevice("c", Some("mac"), Some("Safari"), Some(self.deviceId)),
      Some(self), peers, Some(ownerSession), Some(ownerAnchor)
    )
    assertEquals(r, ResolvedDevice("Mashiros-MacBook-Pro", enrolled = true), "第 1 级：登记名（隔离后缀已剥）")
    assertEquals(DeviceProvenance.marker(r).linesIterator.toList(1), "以下消息来自网页控制台（渠道 console）· 设备：Mashiros-MacBook-Pro（已登记）")
  }

  test("tier1 red: a frame that does NOT hit the roster must never render 已登记 (owner session cannot fake enrollment)") {
    val r = DeviceProvenance.resolve(
      ClientDevice("c", Some("mac"), Some("Safari"), Some("gone-9")), // 绑定 id 已不在册
      Some(self), peers, Some(ownerSession), Some(ownerAnchor)
    )
    assert(!r.enrolled, "花名册未命中不得冒认已登记")
    assert(r.sessionAuthenticated, "落第 2 级（会话 owner 等值仍在）")
  }

  test("tier2 green: owner sub equality ⇒ 'label · browser（经官网）' display + 已认证 status word") {
    val r = DeviceProvenance.resolve(
      ClientDevice("c", Some("mac"), Some("Safari"), None), // roster 读数缺席（隧道腿常态）
      Some(self), peers, Some(ownerSession), Some(ownerAnchor)
    )
    assertEquals(r, ResolvedDevice("Mashiro · Safari（经官网）", enrolled = false, sessionAuthenticated = true))
    assert(
      DeviceProvenance.marker(r).contains("设备：Mashiro · Safari（经官网）（已认证）"),
      DeviceProvenance.marker(r)
    )
  }

  test("tier2 red: non-owner sub (mismatch) must NOT be promoted to 已认证 — falls through to the default name") {
    val r = DeviceProvenance.resolve(
      ClientDevice("c", Some("mac"), Some("Safari"), None),
      Some(self), peers, Some(otherSubSession), Some(ownerAnchor)
    )
    assertEquals(r, ResolvedDevice("未登记-Mac Safari", enrolled = false), "sub 不等 ⇒ 不升已认证（不冒认 owner）")
  }

  test("tier2 variants: browser absent folds to label-only; local-ticket origin tags 本机票; unknown origin no tag") {
    val noBrowser = DeviceProvenance.resolve(
      ClientDevice("c", None, None, None), None, Nil, Some(ownerSession), Some(ownerAnchor)
    )
    assertEquals(noBrowser.display, "Mashiro（经官网）")
    val local = DeviceProvenance.resolve(
      ClientDevice("c", Some("mac"), Some("Chrome"), None), None, Nil,
      Some(SessionIdentity("sub-owner-1", "local-ticket", "s3")), Some(ownerAnchor)
    )
    assertEquals(local.display, "Mashiro · Chrome（本机票）", "本机票来源括注（不冒经官网）")
    val unknownOrigin = DeviceProvenance.resolve(
      ClientDevice("c", Some("mac"), Some("Chrome"), None), None, Nil,
      Some(SessionIdentity("sub-owner-1", "future-origin", "s4")), Some(ownerAnchor)
    )
    assertEquals(unknownOrigin.display, "Mashiro · Chrome", "未知来源不带括注")
  }

  test("tier3 green: no session (Bearer rail / CLI) ⇒ byte-identical default name (fail-open 纪律)") {
    val r = DeviceProvenance.resolve(
      ClientDevice("c", Some("mac"), Some("Safari"), None), Some(self), peers
    )
    assertEquals(r, ResolvedDevice("未登记-Mac Safari", enrolled = false), "会话身份缺席 ⇒ 现状逐字节")
  }

  test("tier3 red: owner session but the anchor is missing (no local credential / sub absent) ⇒ NOT 已认证") {
    val r = DeviceProvenance.resolve(
      ClientDevice("c", Some("mac"), Some("Safari"), None),
      Some(self), peers, Some(ownerSession), None
    )
    assertEquals(r, ResolvedDevice("未登记-Mac Safari", enrolled = false), "锚缺席 ⇒ 第 2 级整环缺席（不冒认）")
  }

  // ─────────── owner 等值判定三态（方案 A2；spec 独立于标记优先级钉死）───────

  test("owner equality three states: sub match ⇒ authenticated; mismatch ⇒ not; anchor missing ⇒ not") {
    val client = ClientDevice("c", Some("mac"), Some("Safari"), None)
    val matchR = DeviceProvenance.resolve(client, None, Nil, Some(ownerSession), Some(ownerAnchor))
    assertEquals(matchR.sessionAuthenticated, true, "sub 匹配 ⇒ 已认证")
    val mismatchR = DeviceProvenance.resolve(client, None, Nil, Some(otherSubSession), Some(ownerAnchor))
    assertEquals(mismatchR.sessionAuthenticated, false, "sub 不匹配 ⇒ 不升")
    assertEquals(mismatchR.display, "未登记-Mac Safari")
    val missingR = DeviceProvenance.resolve(client, None, Nil, Some(ownerSession), None)
    assertEquals(missingR.sessionAuthenticated, false, "锚缺失 ⇒ 不升")
    // local 票哨兵值 "local" 不可能等值真实 sub：local-ticket 会话永不冒认 owner。
    val sentinel = DeviceProvenance.resolve(
      client, None, Nil, Some(SessionIdentity("local", "local-ticket", "s5")), Some(ownerAnchor)
    )
    assertEquals(sentinel.sessionAuthenticated, false, "local 哨兵 ≠ owner sub")
  }

  // ─────────── 账号显示标签词表（方案 A4 P0）与隔离后缀剥离（A6）───────────

  test("accountLabel: name claim wins, then email local-part, then the 门户账号 fallback (sub/email never in the marker)") {
    assertEquals(DeviceProvenance.accountLabel(Map("name" -> "Mashiro", "email" -> "kaimashiro@qq.com", "sub" -> "sub-x")), "Mashiro")
    assertEquals(DeviceProvenance.accountLabel(Map("email" -> "kaimashiro@qq.com")), "kaimashiro", "name 空 ⇒ email 本地段")
    assertEquals(DeviceProvenance.accountLabel(Map("name" -> "  ", "email" -> "  ")), "门户账号", "全空 ⇒ 兜底词，恒可读")
    assertEquals(DeviceProvenance.accountLabel(Map.empty), "门户账号")
  }

  test("stripIsoNameSuffix: display face strips -iso-<hex8>, identity-safe fallback keeps the original when stripping empties it") {
    assertEquals(DeviceProvenance.stripIsoNameSuffix("Mashiros-MacBook-Pro-iso-a659864a"), "Mashiros-MacBook-Pro")
    assertEquals(DeviceProvenance.stripIsoNameSuffix("Mashiros-MacBook-Pro"), "Mashiros-MacBook-Pro", "无后缀原样")
    assertEquals(DeviceProvenance.stripIsoNameSuffix("-iso-a659864a"), "-iso-a659864a", "剥完为空 ⇒ 保原值（名字恒可读）")
    // 花名册命中路径的显示名也剥后缀（A6：标记面读数；身份/配对面保原值）。
    val isoSelf = DeviceRosterEntry("self-1", "Mashiros-MacBook-Pro-iso-a659864a")
    val r = DeviceProvenance.resolve(ClientDevice("c", Some("mac"), None, Some("self-1")), Some(isoSelf), Nil)
    assertEquals(r.display, "Mashiros-MacBook-Pro", "已登记显示名剥 -iso- 后缀")
  }

  test("origin constants stay verbatim-aligned with the session-store authority (single word-table across the trust chain)") {
    // bridge 面刻意不 import gateway（依赖方向纪律）；词表对齐在此机械钉死。
    assertEquals(DeviceProvenance.OriginRelayMint, nebflow.gateway.GatewaySessionStore.OriginRelayMint)
    assertEquals(DeviceProvenance.OriginLocalTicket, nebflow.gateway.GatewaySessionStore.OriginLocalTicket)
  }
