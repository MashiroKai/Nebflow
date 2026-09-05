<!-- dispatcher-ctx-rules:start -->
## 能力目录选配（plugins 与 preset）

分发器 prompt 里的两段目录是节点选配的唯一依据，按任务需求对照选配：

1. **Plugin Catalog（插件能力目录）**：每条「`- <name>: <能力句> [skills… | mcp… | tools…]`」——能力句写的是该插件让节点具备什么能力。按节点任务性质对照选配：检索类任务 → 带 WebSearch/WebFetch 工具扩展或检索方法论的插件；探索规划类 → explorer-toolkit；设计类 → design-spec；验证类 → nebflow-qa；依此类推。**宁缺勿滥**——plugin 注入消耗节点上下文，纯执行节点不配。
2. **Model Preset Catalog（预设场景目录）**：每条「`- <name> — <场景句>`」（无场景句的只出 name）。按节点任务性质匹配场景句选配 preset（如深度分析/审阅类节点配深度分析档）；无匹配场景就用默认档，不硬凑。
3. 选配动作：NodeEdit 建节点时 `plugins` 数组填插件 name、`preset` 字段填 preset name——都按目录里的 name 原文引用，目录里没有的不要编造。
<!-- dispatcher-ctx-rules:end -->
