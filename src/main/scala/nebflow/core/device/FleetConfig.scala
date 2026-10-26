package nebflow.core.device

import io.circe.Json
import nebflow.shared.{NebflowLogger, PathUtil}

/**
 * Fleet（多设备）引擎配置 —— `nebflow.json` 顶层 `fleet` 块的热读单点（台账
 * #913 ③，2026-10-26）。
 *
 * 目前唯一开关 = `fleet.enforceExplicitDevice`（缺省 **false** = 现状零变化）：
 * 开启后六件远端工具（Bash/Read/Write/Edit/Glob/Grep）**缺省 `device` 的调用
 * 直接报错**，错误信息列出可用设备名引导显式选择（显式 `device="local"` 仍是
 * 合法的本机逃生门——被禁的是「不写」，不是「本机」）。
 *
 * 读纪律 = 仿 [[nebflow.core.executor.ExecutorRegistry.readSettings]] 的**热读
 * 先例**：每次现读盘（手工编辑即刻生效），坏形/缺失一律回落缺省、**绝不抛**。
 * 热路径（工具 schema 装配 / 每次六件调用）经一个 2s memo 削峰——文件读本身廉价，
 * memo 只为避免「每个工具 × 每次装配」的重复 IO；2s 内的手工编辑延迟可接受
 * （配置不是操控面，无实时性契约）。
 *
 * 本文件同时承载两段**策略文本**（单一权威，spec 钉住）：
 *   - [[explicitDeviceRefusal]]：强制模式下缺 `device` 调用的报错文案；
 *   - [[agentsMdSection]]：写入新项目 AGENTS.md 的「Fleet device discipline」
 *     模板节（开关关 = 空串 = 落盘字节零变化；见 ProjectCreateService.AgentMdTemplateFull）。
 */
object FleetConfig:

  private val logger = NebflowLogger.forName("nebflow.core.device.fleet")

  final case class Settings(enforceExplicitDevice: Boolean)

  /** 缺省设置（开关关 = 现状零变化——向后兼容的硬要求）。 */
  val Default: Settings = Settings(enforceExplicitDevice = false)

  /**
   * 六件远端工具的展示名单（强制模式报错文案用；与
   * `RemoteExecutor.remoteableTools` 同集合——此处按展示序手写，禁运行期依赖
   * tools 包以避免 core.device → core.tools 反向耦合）。
   */
  val RemoteableToolNames: List[String] = List("Bash", "Read", "Write", "Edit", "Glob", "Grep")

  // ── 热读（executor 节同款宽容读）────────────────────────────────────

  /** 块级宽容读：坏形/缺失一律 [[Default]]，绝不抛出。 */
  def readSettings: Settings =
    try
      val p = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      if !os.exists(p) then Default
      else
        io.circe.parser
          .parse(os.read(p))
          .toOption
          .flatMap(_.hcursor.downField("fleet").focus)
          .flatMap(_.asObject)
          .map(readBlock)
          .getOrElse(Default)
    catch case _: Throwable => Default

  private def readBlock(o: io.circe.JsonObject): Settings =
    val c = Json.fromJsonObject(o).hcursor
    Settings(
      enforceExplicitDevice = c.downField("enforceExplicitDevice").as[Boolean].toOption.getOrElse(false)
    )

  // ── 热路径 memo（2s；见文件头注）──────────────────────────────────

  private val memoLock = new Object
  private var memo: (Long, Boolean) = (0L, false)

  private def readEnforceWithMemo: Boolean =
    val now = System.currentTimeMillis()
    memoLock.synchronized {
      val (at, value) = memo
      if now - at < 2000 then value
      else
        val fresh = readSettings.enforceExplicitDevice
        memo = (now, fresh)
        fresh
    }

  /** 热路径便捷读（2s memo；手工编辑最迟 2s 生效）。 */
  def enforceExplicitDevice: Boolean = readEnforceWithMemo

  // ── 强制模式报错文案（纯函数，spec 直测）──────────────────────────

  /**
   * 六件工具缺显式 `device` 的拒绝文案。`localName` = 本机设备名（名册不可得 =
   * 空串，文案降级为「仅 local」）；`peerNames` = 在线对端名；`sessionDefault`
   * = 会话默认设备（设备项目会话在场时点名——语义仍强制显式，但告诉模型该写什么）。
   */
  def explicitDeviceRefusal(
    tool: String,
    localName: String,
    peerNames: List[String],
    sessionDefault: Option[String]
  ): String =
    val roster =
      if peerNames.isEmpty && localName.isEmpty then "local only (device roster unavailable — NebLink not configured)"
      else
        val self = if localName.nonEmpty then List(s"$localName (= local)") else List("local")
        (self ++ peerNames).mkString(", ")
    val defaultHint = sessionDefault.filter(_.nonEmpty).map(d =>
      s" This session's default device is \"$d\" — pass device=\"$d\" explicitly."
    ).getOrElse("")
    s"$tool refused: missing required `device` parameter (fleet.enforceExplicitDevice is ON — " +
      "no implicit local routing; every Bash/Read/Write/Edit/Glob/Grep call must name its device). " +
      s"Available devices: $roster. Pass device=\"<name>\" from this list (device=\"local\" is the " +
      "explicit escape hatch for this machine)." + defaultHint

  // ── AGENTS.md 模板节（dispatcher 纪律同步）────────────────────────

  /**
   * 「Fleet device discipline」模板节。开关关 = 空串 = 模板字节零变化
   * （向后兼容）；开关开 = 新脚手架的 AGENTS.md 带本节（缺件即补纪律下，
   * 既有工作区不回写——模板只管新件，硬约束由引擎侧报错承担）。
   */
  def agentsMdSection(on: Boolean): String =
    if !on then ""
    else
      """## Fleet device discipline (enforced)

`fleet.enforceExplicitDevice` is ON on this host: Bash / Read / Write / Edit / Glob / Grep REQUIRE an explicit `device` parameter on EVERY call — never rely on the implicit local default. Pick the target from the `# Devices` roster in your context (`"local"` names this machine; any other name routes the call to that device's disk over the device channel).

Projects carry their device: `Project(action="list")` annotates each project with its device — a project whose workspace lives on another device routes its file tools there (its paths are device-local absolute paths).
"""

  /** 现行开关下的模板节（装配点便捷形）。 */
  def agentsMdSection: String = agentsMdSection(enforceExplicitDevice)

end FleetConfig
