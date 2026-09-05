package nebflow.core.plugin

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.presets.PresetStore

/**
 * DispatcherContextCatalog —— 分发器上下文双目录渲染（dispatcher-ctx 批，2026-09-05）。
 *
 * 分发器 spawn/重入 prompt 组装（ProjectActor.pluginCatalogText）注入两段目录，
 * 让分发器「按任务需求为节点选配 plugins 与 preset」时不用猜：
 *
 * 1. **Plugin 能力目录**——过滤链与 [[PluginRegistry.renderCatalog]] 完全同源
 *    （`PluginsConfig.enabled` 总闸 + `scan().filter(_.trust.trusted)`），
 *    行渲染改为能力向：manifest `capability` 字段优先，缺省回落 description。
 *    停用/未信任插件不出现（§B.3 默认拒绝）。兼容注：plugin-panel-redesign 批
 *    （a89a1e7e，未落）引入每插件开关——落地后本过滤链须衔接（trusted 且
 *    per-plugin enabled 才出现），衔接点=本文件 pluginSection 的 filter 链。
 *
 * 2. **Model Preset 场景目录**——全部 preset（不经 plugins 总闸），每条
 *    "name — description" 一行、无 description 只出 name；数据源复用
 *    [[PresetStore.catalogLines]]（08-20 preset description 动态注入先例，
 *    read-fresh：每次渲染现读 model-presets.json，改后即时生效）。
 *
 * 机制选型（对齐 skillCatalog order 800 / phase2b 先例）：**注入目录段**而非
 * 新增查询工具——两目录是参考数据（非操作指令），分发器单次会话、目录规模小
 * （当前 16 插件 + 数 preset，量化见 .nebflow/Spec/dispatcher-context-catalog.md），
 * 多造工具徒增一次往返；反方考量（目录膨胀挤占上下文）由格式精简
 * （每条 1-2 行）+ capability 单行句压制，超限再议按需查询。
 *
 * 渲染规则：两段都空 → ""（调用方不注入空段，先例同 renderCatalog）。
 */
object DispatcherContextCatalog:

  /** Plugin Catalog 段头——与 PluginRegistry.renderCatalog 保持同款（分发器
    * system.md「Plugin Catalog 认知」按此头部识别目录段）。 */
  private val PluginHeader =
    "# Plugin Catalog（可分配能力包，NodeEdit 的 plugins 参数按 name 引用；能力句 = 该插件让节点具备什么能力）"

  private val PresetHeader =
    "# Model Preset Catalog（模型预设场景目录，NodeEdit/agent 定义的 preset 参数按 name 引用；场景句 = 该 preset 适配的任务性质）"

  /** 单插件目录行：capability 优先、缺省回落 description（渲染规则单点）。
    * 尾缀保留 [skills | mcp | tools] 结构清单（与 renderCatalog 同款）——
    * 工具面（tools: WebSearch…）本身是能力信号。 */
  private def pluginLine(p: PluginRegistry.PluginDef): String =
    val cap = p.capability.getOrElse:
      if p.description.isEmpty then p.name else p.description
    val skills = if p.skills.isEmpty then "-" else p.skills.map(s => s.id.split('/')(1)).mkString(", ")
    val mcp = if p.mcpServers.isEmpty then "-" else p.mcpServers.keys.mkString(", ")
    val tools = if p.toolsExtension.isEmpty then "" else s" | tools: ${p.toolsExtension.mkString(", ")}"
    s"- ${p.name}: $cap [skills: $skills | mcp: $mcp$tools]"

  /** Plugin 能力目录段：enabled 总闸开 + 全部受信插件（停用/未信任不出现）。
    * 无受信插件 → ""。 */
  def pluginSection(): IO[String] =
    PluginsConfig.enabled.flatMap {
      case false => IO.pure("")
      case true =>
        PluginRegistry.scan().map { all =>
          val trusted = all.filter(_.trust.trusted).sortBy(_.name)
          if trusted.isEmpty then ""
          else PluginHeader + "\n" + trusted.map(pluginLine).mkString("\n")
        }
    }

  /** Model Preset 场景目录段：全部 preset，read-fresh（catalogLines 内部
    * Try 包裹——读失败降级 Nil = 段省略，不炸 prompt 组装）。行 = 既有渲染器
    * "name — description" 输出加列表前缀，与插件行格式对齐。 */
  def presetSection(): IO[String] =
    IO.blocking(PresetStore.catalogLines()).map {
      case Nil   => ""
      case lines => PresetHeader + "\n" + lines.map("- " + _).mkString("\n")
    }

  /** 双目录拼装入口（ProjectActor.pluginCatalogText 挂接点）：非空段以空行
    * 相接；全空 → ""。 */
  def render(): IO[String] =
    (pluginSection(), presetSection()).mapN { (pluginPart, presetPart) =>
      (pluginPart.nonEmpty, presetPart.nonEmpty) match
        case (true, true)   => pluginPart + "\n\n" + presetPart
        case (true, false)  => pluginPart
        case (false, true)  => presetPart
        case (false, false) => ""
    }

end DispatcherContextCatalog
