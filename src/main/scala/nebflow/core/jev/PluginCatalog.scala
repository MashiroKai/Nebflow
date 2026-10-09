package nebflow.core.jev

import cats.effect.IO
import nebflow.core.plugin.{PluginDispatchPolicy, PluginRegistry}

/**
 * PluginCatalog —— 分配面的 **CatalogEntry 目录构建单点**（jev-plugins 批 #1226）.
 *
 * 原文链条第②步「启用插件 → CatalogEntry 目录」：目录 = **可注入**的包集——
 * 内容面受信（`trust.trusted`）∧ 派发面放行（`PluginDispatchPolicy.effective`，
 * trusted 是它的第一项，显式重复只是可读性）的每个包一个条目（name + description）。
 * 被封禁 / 派发被作者关闭的包**不进目录**——分配面不可能选出它们（选中面与注入面
 * 同判据，杜绝「目录选了、resolve 拒了」的形态差）。
 *
 * `description` 是模型读的唯一能力陈述（见 [[CatalogEntry]]）——热读注册表扫描
 * （PluginRegistry 快照缓存），目录随安装/封禁/开关即时反映。
 */
object PluginCatalog:

  /** 当前可分配目录（catalog 序 = 注册表扫描序，稳定可复现）。 */
  def entries: IO[List[CatalogEntry]] =
    PluginRegistry.scan().map { all =>
      all.collect {
        case p if p.trust.trusted && PluginDispatchPolicy.effective(p.name, p.trust.trusted) =>
          CatalogEntry(p.name, p.description)
      }
    }

end PluginCatalog
