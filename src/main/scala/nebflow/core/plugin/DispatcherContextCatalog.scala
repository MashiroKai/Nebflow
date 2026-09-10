package nebflow.core.plugin

import cats.effect.IO
import cats.syntax.all.*
import nebflow.core.presets.PresetStore

/**
 * DispatcherContextCatalog —— 分发器上下文双目录渲染（dispatcher-ctx 批
 * 2026-09-05；描述单源批 2026-09-10 起插件段收敛为单点委托）。
 *
 * 分发器 spawn/重入 prompt 组装（ProjectActor.pluginCatalogText）注入两段目录，
 * 让分发器「按任务需求为节点选配 plugins 与 preset」时不用猜：
 *
 * 1. **Plugin 能力目录**——段头/过滤链/行渲染/缺席注记单点全部在 [[PluginRegistry.renderCatalog]]
 *    （`PluginsConfig.enabled` 总闸 + `scan().filter(_.trust.trusted)`），本类只委托。
 *    描述单源批（作者 2026-09-10 09:30 裁定：人审与分发器目录渲染同一份内容）：
 *    manifest `description` 是唯一描述源，`capability` 键退役为 deprecated——存量包
 *    仍带该键不报错（KnownManifestKeys 登记），渲染层忽略。creator spec D10 双渲染器
 *    收敛随本批一并落地（调试 REST GET /plugins/catalog 与注入段同字节输出）。
 *    停用/未信任插件不出现（§B.3 默认拒绝）。兼容注：plugin-panel-redesign 批
 *    （a89a1e7e，未落）引入每插件开关——落地后本过滤链须衔接（trusted 且
 *    per-plugin enabled 才出现），衔接点=PluginRegistry.renderCatalog 的 filter 链。
 *
 * 2. **Model Preset 场景目录**——全部 preset（不经 plugins 总闸），每条
 *    "name — description" 一行、无 description 只出 name；数据源复用
 *    [[PresetStore.catalogLines]]（08-20 preset description 动态注入先例，
 *    read-fresh：每次渲染现读 model-presets.json，改后即时生效）。
 *
 * 机制选型（对齐 skillCatalog order 800 / phase2b 先例）：**注入目录段**而非
 * 新增查询工具——两目录是参考数据（非操作指令），分发器单次会话、目录规模小
 * （当前 16 插件 + 数 preset，量化见 .nebflow/Spec/dispatcher-context-catalog.md），
 * 多造工具徒增一次往返；反方考量（目录膨胀挤占上下文）由行格式精简
 * （每条 1-2 行）压制，超限再议按需查询。
 *
 * 渲染规则：两段都空 → ""（调用方不注入空段，先例同 renderCatalog）。可见性批
 * （2026-09-10 P1 静默缩容）：插件段段尾可带缺席注记（「另有 N 个插件未载入（装载
 * 失败 x / 信任未批准 y / digest 漂移 z）」）——目录缩容不再无声；注记只出计数，
 * 包名+原因清单在启动健康摘要日志（PluginRegistry.healthSummary）。
 */
object DispatcherContextCatalog:

  /** Plugin 能力目录段：单点委托 [[PluginRegistry.renderCatalog]]（描述单源批
    * 2026-09-10 收敛——段头/行格式/过滤链与调试预览同字节输出，无本地重复实现）。 */
  def pluginSection(): IO[String] = PluginRegistry.renderCatalog()

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

  private val PresetHeader =
    "# Model Preset Catalog（模型预设场景目录，NodeEdit/agent 定义的 preset 参数按 name 引用；场景句 = 该 preset 适配的任务性质）"

end DispatcherContextCatalog
