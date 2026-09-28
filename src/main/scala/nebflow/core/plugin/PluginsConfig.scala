package nebflow.core.plugin

import cats.effect.IO
import io.circe.Json
import nebflow.core.PathUtil

/**
 * Plugins feature flag（§G.2 回滚约定）：`nebflow.json` 顶层 `plugins.enabled`。
 *
 * - 默认 true（合并后生效）；false = 一键回旧行为：NodeEdit 忽略 plugins 参数
 *   （不校验不存储）、分发器目录不注入、节点 spawn 不注入不启动 MCP、信任门
 *   重验旁路（NodeDef.plugins 字段留存无害）。
 * - 热读（Guardrails.enabled 同款先例）：每次判定现读配置文件——改 nebflow.json
 *   即时生效，无需重启；absent / 非法 / garbage → 默认 true（fail-safe 方向 =
 *   新能力开启，与 sandbox flag 缺省方向一致）。
 */
object PluginsConfig:

  /** 热读 plugins.enabled。IO 包裹文件读取（blocking pool）。 */
  def enabled: IO[Boolean] =
    IO.blocking {
      val configPath = PathUtil.configJsonReadPath(PathUtil.dataRoot)
      val parsed: Option[Boolean] =
        if !os.exists(configPath) then None
        else
          io.circe.parser
            .parse(os.read(configPath))
            .toOption
            .flatMap(_.hcursor.downField("plugins").downField("enabled").as[Option[Boolean]].toOption)
            .flatten
      parsed.getOrElse(true)
    }
end PluginsConfig
