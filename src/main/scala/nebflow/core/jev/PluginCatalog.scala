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
/**
 * One capability catalog entry as the allocation face sees it.
 *
 * `name` is the package name written back into `NodeDef.plugins`; `description`
 * is what the MODEL reads — the implementation renders it into the prompt of
 * the allocation call, so dropping it would leave the model judging packages
 * by name alone. Both fields are therefore load-bearing inputs, not metadata:
 * `name` keys the membership answer, `description` carries the capability
 * statement. Deliberately a small record rather than a plugin type:
 * `core.plugin.PluginRegistry` holds the authority for what is installed and
 * trusted, and this is only the rendering input.
 *
 * 双模式批（2026-10-28 · 设计 §7 插件三层规范）增量键：`keywords`（检索词簇）
 * 与 `useWhen`（「何时该用」一句）随 description 同为模型读判据输入——**容忍
 * 缺席**（缺省空/None = 旧行为逐字节；带键 = 分配问对面行增量渲染）。
 *
 * （jev-retire 批：自 JevAllocatorPort.scala 原文迁入——JeV 端口已退役，
 * CatalogEntry 是共享基础设施，保留。）
 */
final case class CatalogEntry(
  name: String,
  description: String,
  keywords: List[String] = Nil,
  useWhen: Option[String] = None
)

object PluginCatalog:

  /** 当前可分配目录（catalog 序 = 注册表扫描序，稳定可复现）。 */
  def entries: IO[List[CatalogEntry]] =
    PluginRegistry.scan().map { all =>
      all.collect {
        case p if p.trust.trusted && PluginDispatchPolicy.effective(p.name, p.trust.trusted) =>
          // 双模式批（2026-10-28 · 设计 §7）：description 增量键随行（keywords/useWhen
          // 容忍缺席——旧行为逐字节）。
          CatalogEntry(p.name, p.description, p.keywords, p.useWhen)
      }
    }

end PluginCatalog
