package nebflow.core.plugin

/**
 * PluginPackagingSpec —— 打包规范正字（插件分发与生命周期协议升级草案 §10「包根五件
 * 约束」；§9 A 行「确认打包规范」）。
 *
 * 包根已知条目闭合清单（装载器 known-set 的公共单源形态——§9 A 行「常量单源化」；
 * 与包 validator 的 KNOWN_ROOT_ENTRIES/C11 正字清单逐字同源）：
 *  - `plugin.json`：manifest（必需，§5.1）；
 *  - `mcp.json`：MCP 服务器声明（§7.2.1）；
 *  - `skills/`：技能目录（§7.1）；
 *  - `org.nebflow/`：引擎扩展（tools.json / viewers.json，canvas-media 批 OD-3=K-1）；
 *  - `Plugin.md`：包根第五件（协议正字，卫生批 2026-10-28）。
 *
 * 未知条目仍走 §B.8-5 宽容告警（报告且忽略、继续装载）——本常量只把「正字清单」从
 * 装载器私有字面量提升为公共常量，行为零变化。
 */
object PluginPackagingSpec:

  /** 包根已知五件（§10 正字清单；未知条目宽容告警，不拒载）。 */
  val KnownRootEntries: Set[String] = Set("plugin.json", "mcp.json", "skills", "org.nebflow", "Plugin.md")

end PluginPackagingSpec
