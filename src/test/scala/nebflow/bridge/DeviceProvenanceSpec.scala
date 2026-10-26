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
