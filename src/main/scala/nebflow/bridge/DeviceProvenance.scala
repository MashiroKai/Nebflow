package nebflow.bridge

import nebflow.shared.ContentBlock

/**
 * 入站消息来源设备标记（device-provenance 批，2026-10-23 作者令）：
 *
 * 「我们现在能够标记消息是从微信还是飞书来的，让我们的消息也标记设备，比如手机端，
 * mac，windows，让你能知道是哪台设备来的，是否为我们设备列表里的设备。」
 *
 * 即：来源标记从**渠道级**（[[BridgeOrigin]] 的微信/飞书头，本文件不触碰）细化到
 * **设备级**——网页控制台的每条入站消息额外携带：①来源设备（手机/Mac/Windows，
 * 可读设备名）；②该设备是否在平台设备列表（NebLink 花名册 self + peers）里。
 *
 * 数据流（三段，各自由其属主面供给）：
 *
 *   ① 客户端声明 —— `web/js/deviceIdentity.js` 给每个浏览器会话一份稳定身份
 *      （localStorage 持久 clientId；平台/浏览器由 UA 辅助判定），随用户消息帧的
 *      `device` 键上行。**标注全自动批（2026-10-26 作者令「消息来源识别应该是
 *      自动的」）**：设置页手动命名/绑定面整区退役，绑定读数由客户端按花名册
 *      自动判定，三分支——登录会话→账号设备名（鉴权读花名册 self）、配对设备→
 *      登记名（环回匿名读 self）、匿名→帧不带 boundDeviceId ⇒ 默认标注；
 *   ② 网关解析 —— WebSocketRoutes 读帧 `device` 键（[[ClientDevice.fromFrame]]，
 *      防御解码），按 boundDeviceId 对照花名册（[[DeviceProvenance.resolve]]）判定
 *      已登记/未登记，未登记给可读默认名（「未登记-手机 Chrome」族）——
 *      手动命名分支已随设置区退役（未登记显示名恒为默认名）；
 *   ③ 注入 —— 与社交渠道头同一纪律：`<system-reminder>` 块**只进模型面**
 *      （source-marker relocation 批 2026-10-01 的两 face 分离原样适用），会话
 *      `.ui.json` 行与前端气泡保持裸正文。
 *
 * 模板与 [[BridgeOrigin]] 的渠道头同构（「以下消息来自 X（渠道 Y）· 标注段」同一句
 * 骨架），渲染常量复用 [[BridgeOrigin.MarkerOpen]]/[[BridgeOrigin.MarkerClose]]——
 * 扩展不重造。纯数据 + 纯函数面：零 IO、零副作用；花名册取数在网关侧完成。
 */

/** 客户端声明的设备身份（帧 `device` 键的解码形态）。 */
final case class ClientDevice(
  /** 稳定客户端 id：localStorage 持久、跨会话不变；本批的会话身份主键。 */
  clientId: String,
  /** 平台端别：phone / tablet / mac / windows / linux（UA 辅助判定）。 */
  platform: Option[String] = None,
  /** 浏览器名：Chrome / Safari / Edge / Firefox …（UA 辅助判定）。 */
  browser: Option[String] = None,
  /** 自动判定的花名册设备 id（客户端读 roster self；命中 ⇒ 已登记）。 */
  boundDeviceId: Option[String] = None
)

object ClientDevice:

  /** 平台端别的可读名；未知/缺席不猜具体端别。 */
  def platformDisplay(platform: Option[String]): String =
    platform.map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("") match
      case "phone"   => "手机"
      case "tablet"  => "平板"
      case "mac"     => "Mac"
      case "windows" => "Windows"
      case "linux"   => "Linux"
      case _         => "未知设备"

  /**
   * 未登记设备的默认名：`未登记-手机 Chrome`（作者令原文示例）。浏览器缺席省略
   * 尾段；平台缺席降级「未知设备」——名字恒可读，绝不出现空槽。
   */
  def defaultName(client: ClientDevice): String =
    val base = platformDisplay(client.platform)
    client.browser.map(_.trim).filter(_.nonEmpty) match
      case Some(b) => s"未登记-$base $b"
      case None    => s"未登记-$base"

  /**
   * 帧解析面：从消息帧的 `device` 键防御解码。`clientId` 是身份主键——键缺席、
   * 非串或全空白 ⇒ `None`（老客户端 / CLI 帧 / 伪造空声明一律**不标注**，与
   * `origin = None` 同一条兼容律：不声明不标注，不猜）。手动命名键 `name` 已随
   * 设置区退役（标注全自动批 2026-10-26）：帧里残留该键一律忽略，不进解码面。
   */
  def fromFrame(json: io.circe.Json): Option[ClientDevice] =
    val c = json.hcursor
    for
      device <- c.downField("device").focus
      if !device.isNull
      clientId <- device.hcursor
        .downField("clientId")
        .as[String]
        .toOption
        .map(_.trim)
        .filter(_.nonEmpty)
    yield ClientDevice(
      clientId = clientId,
      platform = str(device.hcursor, "platform"),
      browser = str(device.hcursor, "browser"),
      boundDeviceId = str(device.hcursor, "boundDeviceId")
    )

  /** 取一个 trim 后非空的字符串字段；缺席/非串/空白 = None。 */
  private def str(c: io.circe.HCursor, key: String): Option[String] =
    c.downField(key).as[String].toOption.map(_.trim).filter(_.nonEmpty)

end ClientDevice

/** 花名册行（网关侧由 self 身份 / peers 列表映射而来；纯结构，零 IO）。 */
final case class DeviceRosterEntry(deviceId: String, deviceName: String)

/** 解析读数：`display` = 标记里出现的设备名；`enrolled` = 是否在设备列表里。 */
final case class ResolvedDevice(display: String, enrolled: Boolean)

object DeviceProvenance:

  val MarkerOpen = BridgeOrigin.MarkerOpen
  val MarkerClose = BridgeOrigin.MarkerClose

  /** 渠道 id（与 weixin-ilink / feishu 同位；控制台腿的稳定名）。 */
  val ChannelId = "console"

  /** 渠道显示名（渠道头的 `${channelDisplay}（渠道 ${channelId}）` 同位）。 */
  val ChannelDisplay = "网页控制台"

  /**
   * 设备解析（自动标注三分支的网关侧半面）：**绑定键 = 花名册设备 id**（id 是
   * 配对真源；设备名可重名可伪造，不作匹配键——跨会话稳定优先）。绑定读数由
   * 客户端自动判定供给（登录会话/环回配对读得 roster self ⇒ boundDeviceId =
   * self.id）：命中（self 或 peers——peers 分支保留：花名册对照词表与近路面
   * 共用，老客户端残留 peer 绑定值仍按登记判定，不冒认不漏认）⇒ 已登记，显示
   * 名 = 花名册登记名（登记权威）；未命中/缺席 ⇒ 未登记，显示名 =
   * [[ClientDevice.defaultName]] 可读默认名（手动命名分支已随设置区退役——
   * 未登记显示名恒为默认名，不存在第二真源）。花名册不可得（neblink 未装配）
   * ⇒ self/peers 恒空 ⇒ 一律未登记（保守：读不到名册永不冒认已登记）。
   */
  def resolve(
    client: ClientDevice,
    self: Option[DeviceRosterEntry],
    peers: List[DeviceRosterEntry]
  ): ResolvedDevice =
    val bound = client.boundDeviceId.map(_.trim).filter(_.nonEmpty)
    val hit = bound.flatMap { id =>
      self.filter(_.deviceId == id).orElse(peers.find(_.deviceId == id))
    }
    hit match
      case Some(entry) => ResolvedDevice(display = entry.deviceName, enrolled = true)
      case None        => ResolvedDevice(display = ClientDevice.defaultName(client), enrolled = false)

  /**
   * 渲染来源设备标记块。骨架与社交渠道头同构（「以下消息来自 X（渠道 Y）· 标注段」）；
   * 登记状态恒显式（已登记/未登记）——「是否为设备列表里的设备」在标记里自答，
   * 不依赖模型对默认名的猜测。
   */
  def marker(resolved: ResolvedDevice): String =
    val status = if resolved.enrolled then "已登记" else "未登记"
    // 行尾显式 "\n" 拼接（勿改回三引号字面量）：CRLF checkout 会把源行尾编进
    // 字面量（marker 变 \r\n）⇒ 消费方按 \n 契约前缀匹配全红。同 BridgePlugin.marker。
    s"$MarkerOpen\n以下消息来自$ChannelDisplay（渠道 $ChannelId）· 设备：${resolved.display}（$status）\n$MarkerClose"

  /**
   * 模型面合成：标记块在前、空一行、正文随后——与 [[BridgeOrigin.compose]] 同一
   * 顺序纪律。⚠️ **模型面专用**：本返回值只允许进入 agent 上下文（UserInput /
   * ImmediateInput 的 text/blocks），会话显示面（`.ui.json` 行、前端气泡）必须
   * 保持裸正文（两 face 分离原律）。
   */
  def compose(resolved: ResolvedDevice, content: String): String =
    marker(resolved) + "\n\n" + content

  /**
   * 构块面合成：标记块作为**首个独立 Text 块**前插（正文块逐字不动——与社交渠道
   * 「标签线 + 随文」同一条「标记与正文可区分」纪律）。`blocks = Nil`（纯媒体帧）
   * 时标记块自成首块，设备来源仍随帧可达。
   */
  def prependMarker(resolved: ResolvedDevice, blocks: List[ContentBlock]): List[ContentBlock] =
    ContentBlock.Text(marker(resolved)) :: blocks

end DeviceProvenance
