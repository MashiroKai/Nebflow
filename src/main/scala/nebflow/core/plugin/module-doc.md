# core / plugin

> 模块向导（自动生成 2026-11-06；职责为人工定稿，文件说明取各自头注首句）。

## 功能

插件注册表、信任状态、目录扫描。

**入口建议**：先读 [`PluginRegistry.scala`](PluginRegistry.scala)（本模块最大文件）。

## 文件（13）

| 文件 | 说明 |
|---|---|
| [`DispatcherContextCatalog.scala`](DispatcherContextCatalog.scala) | DispatcherContextCatalog —— 分发器上下文目录渲染（描述单源起插件段收敛为单点委托）。 分发器 spawn/重入 prompt 组装（ProjectActor.pluginCatalogText |
| [`OfficialPackages.scala`](OfficialPackages.scala) | OfficialPackages —— 「官方」身份的机制化（基线 §2.4；roadmap §5.2 P0-3 行）。 基线 §2.4 逐字口径（本件 = 其装载层实现，验收 = BU A3「冒名拒载」）： |
| [`PluginBlockPolicy.scala`](PluginBlockPolicy.scala) | 插件**封禁面**（deny-list，2026-09-13 作者令「装了就是信任」）——default-allow + explicit deny。 |
| [`PluginDispatchPolicy.scala`](PluginDispatchPolicy.scala) | 插件**派发许可面**（令 1「插件开关语义」单一策略点，2026-09-12 作者 14:14 原话： 「插件的开关，应该只影响任务分发器对未来节点的派发，而不能影响目前的」）。 |
| [`PluginMcpManager.scala`](PluginMcpManager.scala) | PluginMcpManager —— plugin 级 MCP 生命周期（阶段 2b §B.5）。 - **引用计数**：同一 plugin 被多个并发 node 分配 → server 只起一份；最后一个 |
| [`PluginRegistry.scala`](PluginRegistry.scala) | PluginRegistry —— 插件注册表与信任门（阶段 2b §B.2/§B.3，蓝图唯一权威； 20260904 协议符合度批对齐 Agent Plugins 1.0.0 官方 spec 全文）。 |
| [`PluginRemovePolicy.scala`](PluginRemovePolicy.scala) | PluginRemovePolicy —— 插件**卸载面**（2026-10-29 作者令「除了 toggle，我们应该还要 多一个卸载」）。 |
| [`PluginSourceCheck.scala`](PluginSourceCheck.scala) | PluginSourceCheck —— 官方源读取面（远程发布源设计稿 §3.2/§4.1，P2 批）。 读序（对齐既有双跳先例 [[nebflow.core.hotupdate.VersionCheck]]，禁第二套 |
| [`PluginSourceInstall.scala`](PluginSourceInstall.scala) | PluginSourceInstall —— 从源安装流（远程发布源设计稿 §4，P2 批）。 管线（三态同构，§4.2 状态机）：source spec 解析（P1 类型面）→ 暂存获取 |
| [`PluginSourceLedger.scala`](PluginSourceLedger.scala) | PluginSourceLedger —— provenance 账本（远程发布源设计稿 §2.1/§2.4，P1 源引用批）。 权威来源层 = `<dataRoot>/plugin-sources/<name>.jso |
| [`PluginSourceSpec.scala`](PluginSourceSpec.scala) | PluginSourceSpec —— 三态源引用模型（远程发布源设计稿 §2.2，P1 源引用批类型面）。 三态（§2.2 逐字）： |
| [`PluginsConfig.scala`](PluginsConfig.scala) | Plugins feature flag（§G.2 回滚约定）：`nebflow.json` 顶层 `plugins.enabled`。 - 默认 true（合并后生效）；false = 一键回旧行为：NodeEdit  |
| [`SecretEnvPointer.scala`](SecretEnvPointer.scala) | mcp.json env 的 **secret 指针形态**（安全批 2026-10-29 ④a，审计报告 §7 缝隙 1 / §8 建议的落地）：env 值 = `${secret:<name>}` —— 值本体只存  |

## 依赖的兄弟模块

[`shared/`](../../shared/module-doc.md)、[`core/`](../module-doc.md)、[`core/mcp/`](../mcp/module-doc.md)
