package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import nebflow.shared.PathUtil

/**
 * Plugins feature flag（§G.2 回滚约定）：`nebflow.json` 顶层 `plugins.enabled`。
 *
 * - 默认 true（合并后生效）；false = 一键回旧行为：NodeEdit 忽略 plugins 参数
 *   （不校验不存储）、分发器目录不注入、节点 spawn 不注入不启动 MCP、信任门
 *   重验旁路（NodeDef.plugins 字段留存无害）。
 * - 热读（Guardrails.enabled 同款先例）：每次判定现读配置文件——改 nebflow.json
 *   即时生效，无需重启；absent / 非法 / garbage → 默认 true（fail-safe 方向 =
 *   新能力开启，与 sandbox flag 缺省方向一致）。
 *
 * #523 补齐批（Jev/小模型自动分配 delegate 插件）：`plugins.autoAllocate` ——
 * **自动分配专用闸**（与 `enabled` 总闸正交）。`enabled=true ∧ autoAllocate=false`
 * = 「仅声明、不问模型」态：节点/链步的声明集照常解析注入，但空声明不再触发
 * Jev/LLM 分配腿（零模型调用、零分配审计行——`PluginInjection` mode=auto-off）。
 * 缺省 true = 现行为逐字（#1226 批分配面默认在场——翻缺省为关会静默禁用已交付
 * 能力；取舍在报告注明，作者可一键翻转）。
 */
object PluginsConfig:

  /** 热读 plugins.enabled。IO 包裹文件读取（blocking pool）。 */
  def enabled: IO[Boolean] =
    IO.blocking(readBool("enabled", default = true))

  /** 热读 plugins.autoAllocate（#523）：缺省 true（现行为逐字），同 enabled 的 fail-safe 方向。 */
  def autoAllocate: IO[Boolean] =
    IO.blocking(readBool("autoAllocate", default = true))

  /** plugins.<key> 单布尔热读单点（absent / 非法 / 文件缺失 → default）。 */
  private def readBool(key: String, default: Boolean): Boolean =
    val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
    val parsed: Option[Boolean] =
      if !os.exists(configPath) then None
      else
        io.circe.parser
          .parse(os.read(configPath))
          .toOption
          .flatMap(_.hcursor.downField("plugins").downField(key).as[Option[Boolean]].toOption)
          .flatten
    parsed.getOrElse(default)
end PluginsConfig
