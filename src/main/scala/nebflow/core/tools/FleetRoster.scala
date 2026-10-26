package nebflow.core.tools

import io.circe.syntax.*
import nebflow.core.device.{DeviceEntry, DeviceRegistry}
import nebflow.shared.PeerInfo

/**
 * Fleet roster —— `# Devices` 段的花名册渲染器（台账 #913 ①，2026-10-26）。
 *
 * 纯函数件：输入 = 本机身份（deviceId/名称/platform/能力面/version）+ 在线对端
 * （presence 名册，`PeerInfo`）+ 配对登记表（`DeviceRegistry`，neblink 注册表）
 * + 设备画像（`DeviceProfile` store），输出 = 逐台一行的紧凑名册文本。装配点 =
 * `AgentSessionExecution.deviceInfoSnapshot`（system prompt 的 `# Devices` 段，
 * order 600）——设备增删/上下线自动反映沿**既有机制**（30s memo + devicesDelta
 * 差量提醒），本件不新增任何失效/提醒通道。
 *
 * 行格式（一行一台，**无列表符号**——devicesDelta 差量解析按 `[;\n]` 切行，
 * 条目行保持 `+<entry>` / `-<entry>` 净形；紧凑，不撑上下文）：
 *   - `KAI (40742cd4) [local · windows · v2026.10.26-beta.1] - 描述 · caps: node,git,java`
 *   - `Mac (dd8cfd22) [online · macos] - 描述 · caps: … [cwd=… | os=Darwin …]`
 *   - `OldBox (abcd1234) [offline · linux]`（登记表/画像在册但 presence 不在）
 *
 * 预算纪律（任务口径「表行数多时截断+指针」）：设备行数超过 [[MaxRows]] 时截断，
 * 尾行 `(+N more not shown — full roster: GET /api/neblink/status)` 指权威全量面。
 * capabilities 只列**键名**（值是绝对路径，逐字列出会撑爆上下文）。
 *
 * 版本诚实性：本机行带引擎 VERSION；对端 presence wire 不携带引擎版本 ⇒ 对端行
 * 不显示 version（不装已知）。
 */
object FleetRoster:

  /** 设备行上限（含本机行；超出截断 + 指针行）。 */
  val MaxRows: Int = 24

  /** 本机行的输入面（neblink.DeviceIdentity 经 core 窄视图 + 版本串）。 */
  final case class LocalDevice(
    deviceId: String,
    deviceName: String,
    userDescription: String,
    platform: String,
    capabilities: Map[String, String],
    version: String
  )

  /** 一台「已知但当前不在 presence 名册」的设备（离线行的数据源）。 */
  final case class KnownOffline(
    deviceId: String,
    deviceName: String,
    platform: String,
    capabilities: Map[String, String] = Map.empty
  )

  /** 登记表条目 → 离线行输入（core.device 注册表面；名称缺省 = id 原样）。 */
  def knownOfflineFromRegistry(entries: List[DeviceEntry]): List[KnownOffline] =
    entries.map(e => KnownOffline(e.deviceId, if e.deviceName.nonEmpty then e.deviceName else e.deviceId, ""))

  /** 画像条目 → 离线行输入（probeVersion 落后/负条目也照列——「已知」诚实优先）。 */
  def knownOfflineFromProfiles(profiles: Map[String, DeviceProfileEntry]): List[KnownOffline] =
    profiles.values.toList.map(e =>
      KnownOffline(e.deviceId, if e.deviceName.nonEmpty then e.deviceName else e.deviceId, e.platform)
    )

  /**
   * 渲染整个 `# Devices` 段体（不含 `# Devices` 标题本身——由 PromptSections
   * 段模板承担，与改前装配形状一致）。返回空串 = 名册全空（调用方据此不注入段）。
   *
   * @param enforce   fleet.enforceExplicitDevice 现值（提示行二态：缺省措辞 /
   *                  强制措辞——与引擎实际报错行为同源，禁两处漂移）
   */
  def render(
    local: Option[LocalDevice],
    peers: List[PeerInfo],
    offlineKnown: List[KnownOffline],
    profiles: Map[String, DeviceProfileEntry],
    now: Long,
    enforce: Boolean,
    maxRows: Int = MaxRows
  ): String =
    val rows = scala.collection.mutable.ListBuffer.empty[String]
    local.foreach(l => rows += localRow(l))
    peers.foreach(p => rows += peerRow(p, profiles, now))
    // 离线已知行：presence 在场的（对端表）与配对/画像去重后剩余的，按名排序。
    val onlineIds = peers.map(_.deviceId).toSet ++ local.map(_.deviceId).toSet
    offlineKnown
      .filterNot(k => onlineIds.contains(k.deviceId))
      .sortBy(_.deviceName)
      .foreach(k => rows += offlineRow(k))
    if rows.isEmpty then ""
    else
      val shown = rows.take(maxRows)
      val hidden = rows.length - shown.length
      val truncation =
        if hidden > 0 then s"\n(+${hidden} more device(s) not shown — full roster: GET /api/neblink/status)"
        else ""
      shown.mkString("\n") + truncation + hintLine(peers, enforce)

  end render

  // ── 行渲染 ─────────────────────────────────────────────────────────

  private def capsSegment(caps: Map[String, String]): String =
    val keys = caps.keys.toList.sorted
    if keys.isEmpty then "" else s" · caps: ${keys.mkString(",")}"

  private def descSegment(desc: String): String =
    if desc.trim.isEmpty then "" else s" - ${desc.trim}"

  private def localRow(l: LocalDevice): String =
    val version = if l.version.trim.isEmpty then "" else s" · v${l.version.trim}"
    s"${l.deviceName} (${shortId(l.deviceId)}) [local$version · ${l.platform}]${descSegment(l.userDescription)}${capsSegment(l.capabilities)}"

  private def peerRow(p: PeerInfo, profiles: Map[String, DeviceProfileEntry], now: Long): String =
    val base =
      s"${p.deviceName} (${shortId(p.deviceId)}) [online · ${p.platform}]${descSegment(p.userDescription)}${capsSegment(p.capabilities)}"
    // 画像摘要 + 回拉约定提示沿既有渲染面（DeviceProfileSpec 钉住的两函数，禁改）。
    base + DeviceProfile.renderSummary(p, profiles, now) + DeviceProfile.renderCaptureHint(p, profiles)

  private def offlineRow(k: KnownOffline): String =
    val platform = if k.platform.trim.isEmpty then "" else s" · ${k.platform.trim}"
    s"${k.deviceName} (${shortId(k.deviceId)}) [offline$platform]${capsSegment(k.capabilities)}"

  private def shortId(id: String): String = if id.length > 8 then id.take(8) else id

  /**
   * 尾部 `device` 参数提示行（改前逐字 = 开关关；开关开 = 强制措辞——与
   * [[nebflow.core.device.FleetConfig.explicitDeviceRefusal]] 的引擎行为同源）。
   * 前导 `\n` 与改前一致（贴在名册行尾）。
   */
  private def hintLine(peers: List[PeerInfo], enforce: Boolean): String =
    if peers.isEmpty then ""
    else if enforce then
      "\nfleet.enforceExplicitDevice is ON: Bash/Read/Write/Edit/Glob/Grep REQUIRE an explicit `device` parameter on EVERY call — pass the target device's name from this roster (\"local\" for this machine); every other tool always runs locally."
    else
      "\nRead/Write/Edit/Glob/Grep/Bash accept a `device` parameter to run on another machine; every other tool always runs locally."

  // ── 装配点便捷读（同步核；失败按空值继续——提示词装配路径绝不抛）────

  /** 配对登记表现读（DeviceRegistry.loadSync 的吞错包装；读不到 = 空表）。 */
  def registrySyncSafe(): List[DeviceEntry] =
    try DeviceRegistry.loadSync
    catch case _: Exception => Nil

end FleetRoster
