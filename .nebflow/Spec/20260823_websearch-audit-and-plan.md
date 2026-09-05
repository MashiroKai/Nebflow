> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 网络搜索能力审计与产品级优化方案

| | |
|---|---|
| **日期** | 2026-08-23（审计于 08-22 深夜完成） |
| **类型** | 阶段文档：审计取证 + 方案设计（完成即冻结） |
| **范围** | 只分析不改代码；覆盖 P0/P1/P2 分期建议 |
| **红线** | 产品面向广大用户，**绝不依赖无 key 蹭网页端实现搜索**（不稳定/验证码/反爬） |

---

## TL;DR

**现状一句话**：`WebSearch` 工具是全链路里唯一的搜索路径，而它完全依赖无 key 网页爬取（7 引擎竞速 + 正则抽取），用户已付费的 API 资产（kimi/qwen/zhipu/deepseek provider、已配置但停用的 zai MCP）一个都没接进来；`SearchConfig` 配置模型是零消费的死代码；Sogou 反爬页被实证当作"成功结果"透传给 agent——静默质量失败。

**方案核心一行**：新增 `SearchProviderResolver` 解析层，按 **MCP 搜索工具 → 当前 agent provider 内置搜索 → 内置多引擎聚合（标注非保证）** 三级优先级解析实际执行者，让搜索落在用户已配置、已付费、有 SLA 的资产上。

**P0 范围三行**：
1. Zhipu（`tools:[{type:web_search}]`）/ qwen（`enable_search`）/ kimi（`$web_search` builtin_function，需关 thinking）三家 provider 搜索参数注入 Adapter 请求构造层；
2. `WebSearch` 工具在 `AgentCore.executeTool` 拦截点检测当前 provider 能力并路由——有内置搜索则透传，否则走现有聚合；
3. 内置聚合降级为 fallback 并在结果中标注"非保证"，配套冒烟测试（真实 `sbt run` + curl 断言）与 E2E（真实搜索返回带来源结果）。

---

## Part A — 现状审计（取证）

### A1 代码级现状

**执行链**：`Agent/LLM → tool_call: WebSearch → AgentCore.executeTool → WebSearchTool.scala → 7 引擎 HTML 直抓 + 正则抽取`

| 组件 | 位置 | 现状 |
|---|---|---|
| WebSearchTool | `src/main/scala/nebflow/core/tools/WebSearchTool.scala` | 7 引擎（Sogou/360/DuckDuckGo/Baidu/WeChat/arXiv/Crossref）。通用引擎全部**无 key HTML 直抓 + 正则抽取**；BATCH_SIZE=3 批内竞速、批间降级。description 自称 *"no API key required"*——把无鉴权爬取当卖点 |
| 兜底抽取 | 同上，`extractGenericLinks` + `stripHtmlTags` | 对 HTML 结构零校验：反爬页的 JS 脚本文本也被当作"结果摘要"返回。**这是 Sogou 铁证（A2）的直接成因** |
| 学术引擎 | arXiv / Crossref | 正规官方 API，keyless，质量可靠。今晚日志中 arXiv 报 "No results or API error"（查询式问题）、Semantic Scholar 429——接口本身合规，但竞速轮次里成败混杂 |
| WebFetchTool | `src/main/scala/nebflow/core/tools/WebFetchTool.scala` | Layer1 直抓（403/挑战标题 → NeedBrowser）→ Layer2 `BrowserManager.fetch`（Obscura CLI → Playwright 动态 classpath）。抓取侧已有反爬降级意识，**搜索侧没有** |
| SearchConfig | `src/main/scala/nebflow/llm/config.scala` L102-110，挂 `NebflowServiceConfig.search`（L151） | `provider/apiKey/engine/model` 四字段**零消费——死代码**。配置模型已经预留，但没有任何代码读取它构造搜索请求 |
| MCP 层 | `core/mcp/McpManager.scala`（Stdio/Http 双 transport，工具注册名 `mcp__<serverId>__<tool>`）；`AgentMcpLoader.scala`（agent 级 `tools/mcp/*.json`）；`AgentCore.scala` L1131-1180 buildAllowedToolSet | MCP 基础设施完备且已生产可用。**但搜索工具解析层从不把 MCP 纳入候选**——agent 只能靠 system prompt 自觉去调 `mcp__zai__...`，WebSearch 工具与之互不知晓 |
| Adapter 层 | `llm/adapter.scala` L5-19 SendMessageParams；AnthropicAdapter / OpenAiAdapter 请求体均为 `Json.obj → deepMerge` 链（system→tools→thinking→metadata） | **provider 内置搜索参数的天然注入点**：在请求构造处 merge `web_search` 类工具/参数即可，无需改消息协议 |
| 拦截点 | `AgentCore.executeTool`（原 `core/handlers.scala` L24 已合并） | WebSearch 的路由分发就在这里——三优先级链的挂载点 |

**架构现状图（AS-IS）**：

![AS-IS 架构](assets/websearch-as-is.svg)

图中两个断裂点：`SearchConfig`（死代码）与 `user's paid API assets`（kimi/deepseek/qwen/zhipu + 停用的 zai MCP）都通过虚线连向核心——**存在但未接线**。

### A2 运行时证据（今晚 21:00–23:30 research flow 实测）

| 证据 | 位置 | 内容 |
|---|---|---|
| **Sogou 铁证（静默质量失败）** | `~/.nebflow/sessions/delegate-Explorer-426be9df.json` | 引擎成功计数里有 "Search engine: Sogou" 的"成功"结果，内容是 `checkSNUID` 反爬 cookie 脚本——反爬页被正则抽取层当成功结果透传给 agent，**用户视角无任何异常提示** |
| 引擎成败分布 | 同一会话 | 成功计数：DuckDuckGo 最多，其次 arXiv / 360 / Baidu / Sogou / WeChat / Crossref——成败全靠运气，无 SLA |
| 日志级失败 | `~/.nebflow/logs/nebflow.log` | arXiv "No results or API error"、Semantic Scholar HTTP 429（限流） |
| 用户配置实况 | `~/.nebflow/nebflow.json` | llm providers = kimi / deepseek / 107 / qwen / zhipu（**四家都有内置搜索能力，见 Part B**）；mcpServers 已有 `zai`（`npx @z_ai/mcp-server`，Z_AI_MODE=ZHIPU，**enabled:false**）和 `opendataloader-pdf`；**无 `search` 节**（写了也没人读） |

### A3 风险定性

1. **红线违规**：产品唯一搜索路径依赖无 key 网页爬取。今晚已实证 Sogou 验证码/反爬拦截；引擎侧随时可以加码（UA 指纹、JS 挑战、IP 限流），Nebflow 无法控制，也没有任何合同保障。
2. **静默质量失败**：反爬垃圾内容被当成功结果注入 agent 上下文，污染下游推理，且用户无感知。这比"搜索失败"更糟——失败至少可见。
3. **资产浪费**：用户付费的 provider（kimi/qwen/zhipu 均含搜索能力）与已配置的 zai MCP 就在手边，基础设施（MCP 管理、Adapter 注入点、SearchConfig 占位）全部就绪，唯独缺一根接线。
4. **配置欺诈**：`SearchConfig` 存在于配置 schema 中——用户若配置了它，会合理以为生效，实际零消费。

## Part B — Provider 内置搜索能力盘点

以下能力均经官方文档核实（链接见附录）。**前四行是用户当前实际配置的 provider**。

| Provider（用户已配？） | 机制 | 注入方式（Nebflow OpenAI/Anthropic 兼容协议） | 费用 | 关键限制 |
|---|---|---|---|---|
| **Zhipu GLM**（已配） | 两条路：① Chat completions 搜索工具（搜索融入回答 + 来源标注）② 独立 Web Search API（`/web_search` 端点，`search_engine=search_pro`，结构化结果） | ① `tools:[{"type":"web_search","web_search":{...}}]` merge 进请求体；② 独立 HTTP 调用，可复用 provider 的 apiKey | ① 按模型计费 ② 搜索 API 独立计价 | 用户已配的 zai MCP 走同一套能力——Tier 1 / Tier 2 在 zhipu 上殊途同归 |
| **Moonshot Kimi**（已配） | builtin_function 搜索工具，round-trip 语义：模型发 `arguments` → 调用方**原样 echo 回 tool result** → 模型带搜索结果继续生成 | `tools:[{"type":"builtin_function","function":{"name":"$web_search"}}]`；**必须关 thinking** | $0.005/次 | round-trip 需要 Adapter 层识别 `$web_search` 的 tool_call 并回灌——与 Anthropic server tool 的"服务端执行"不同，Nebflow 需实现 echo 逻辑；thinking 与之互斥 |
| **DashScope qwen**（已配） | `enable_search` 请求级开关；新版有 `search_options` / `enable_citation` | `enable_search:true` merge 进请求体 | 按次计费（约 ¥0.01/千次量级） | **DashScope 原生协议字段**；qwen 的 OpenAI compatible-mode（Nebflow 当前走的协议）实测报告可能忽略该字段——P0 实施时必须冒烟验证，必要时切换 DashScope 原生 endpoint 或降级到 Tier 3 并记录原因 |
| **DeepSeek**（已配） | 官方 API **无内置搜索** | — | — | 落到 Tier 3（或用户自配 MCP）。不阻塞方案：解析链按能力探测，无能力自动降级 |
| Anthropic | server tool `web_search_20250305`：服务端执行搜索，结果带 citation block | `tools:[{"type":"web_search_20250305","name":"web_search","max_uses":N,"allowed_domains":[...]}]` merge 进 AnthropicAdapter 请求体 | $10/1000 次搜索 | 服务端执行，Nebflow 只需透传——实现成本最低的一家；P2 批量接入 |
| Gemini | grounding：`tools:[{"google_search":{}}]`，回答内嵌 `url_citation` 注解 | merge 进 Gemini 请求体 | 免费层有限额，付费按 grounding 次数计 | P2 接入 |
| OpenAI | Responses API `tools:[{"type":"web_search"}]`（filters: strict/medium/low） | **Chat Completions 协议（Nebflow 的 OpenAiAdapter）不支持工具注入**——需专用搜索模型（`gpt-4o-search-preview`）或迁移 Responses API | 按次 + 搜索 preview 模型单独计价 | P2 视用户需求决定（ Responses API 迁移是独立大工程，本方案不动） |
| 107 | 未核实到内置搜索 | — | — | 落 Tier 3 |

**结论**：用户当前四家主力 provider 中，**zhipu / kimi / qwen 三家可立即透传**（qwen 有协议 nuance 需冒烟确认），deepseek 无能力走降级——P0 覆盖面已足够兑现红线。

## Part C — 方案设计

### C1 架构：三优先级解析链

**TO-BE 架构图**：

![TO-BE 架构](assets/websearch-to-be.svg)

新增 `SearchProviderResolver`（单例，随 registry 初始化）：每次 `WebSearch` tool_call 时解析出本次实际执行者 `SearchExecutor`，按以下优先级链降级：

| 优先级 | 执行者 | 判定条件 | 结果质量 |
|---|---|---|---|
| **Tier 1** | 用户搜索类 MCP（如 zai） | `McpManager` 已连接的 server 中存在工具名匹配 `search` / `web_search` 的工具，且该 agent 的 buildAllowedToolSet 放行了它 | 用户自己的资产，结构化结果 |
| **Tier 2** | 当前 agent provider 内置搜索 | agent 当前 provider ∈ 能力表（zhipu/kimi/qwen/anthropic/gemini）且该能力未被用户关闭 | provider SLA 保障，带来源标注 |
| **Tier 3** | 内置多引擎聚合（现有 WebSearchTool） | 以上皆 miss | **结果标注 `non-guaranteed`**——保留现有 7 引擎竞速作为零配置兜底，但不再假装它是产品级搜索 |

**解析顺序为何是 MCP → provider → 内置**：MCP 是用户显式安装的，意图最强（用户装 zai 就是想用它搜）；provider 内置是用户已付费的隐性资产，零额外配置即可激活；内置聚合是零配置的最后兜底。**任何一级命中即短路**，不叠加执行。

### C2 集成点（文件 + 行号级）

| 改动点 | 文件 | 内容 |
|---|---|---|
| ① 拦截层 | `core/AgentCore.scala` `executeTool`（原 handlers.scala L24 合并处） | tool name == `WebSearch` 时先问 `SearchProviderResolver.resolve()`；命中 Tier 1 直接代理调用 `mcp__<server>__<search>` 并把结构化结果归一化为 SearchResult；命中 Tier 2 则**放行本次 tool_call 到 provider**（见②③）；miss 才走现有 WebSearchTool 逻辑并加 non-guaranteed 标注 |
| ② 请求构造层（zhipu/qwen） | `llm/OpenAiAdapter.scala` 请求体 `Json.obj→deepMerge` 链 | 按 SendMessageParams 新增字段（见 C3）merge：zhipu `tools += {"type":"web_search",...}`、qwen `enable_search:true`。注意 deepMerge 顺序：搜索工具追加到现有 tools 数组，不覆盖 agent 工具定义 |
| ③ 请求构造层（kimi round-trip） | `llm/OpenAiAdapter.scala` 响应处理 + `AgentCore` tool_call 分发 | 识别 `function.name == "$web_search"` 的 tool_call：**不执行**，将 `arguments` 原样作为 tool result 回灌（kimi 官方语义：调用方 echo → 模型自带搜索结果继续）；同时该请求必须 `thinking: disabled` |
| ④ 能力注册表 | 新文件 `core/tools/SearchProviderResolver.scala` | `Map[ProviderId, ProviderSearchCapability]`；capability 声明注入函数 + 限制（kimi 关 thinking 等）。AgentMcpLoader / McpManager 提供已连接搜索工具查询接口 |
| ⑤ 结果归一化 | `WebSearchTool` 现有结果 case class 扩展 | 统一 SearchResult：`title / url / snippet / source: Mcp("zai") \| Provider("zhipu") \| Aggregated(engine, nonGuaranteed=true)`——provenance 落到每条结果，agent 与前端都能感知来源 |

**行为变更（用户视角）**：
- 现在：agent 调 WebSearch → 拿到的可能是 Sogou 反爬脚本垃圾，无任何标注。
- 改完后：agent 调 WebSearch → 若用户配了 zai MCP（启用态）则直接用 zai 搜索；否则 zhipu/kimi/qwen 会话中，模型的搜索请求由 provider 官方搜索服务执行，结果自带来源标注；两者皆无时才用内置聚合，且结果明确标注"非保证"。
- **配置了 provider 但未启用搜索的用户完全无感知**——默认行为不变，只是多了能力上限。

### C3 配置模型（激活死代码）

`SearchConfig`（config.scala L102-110）从死代码激活为解析链的声明式覆盖：

```jsonc
// nebflow.json（全部可选，缺省 = 按能力自动探测）
"search": {
  "mode": "auto",            // auto | mcp_only | provider_only | builtin_only | off
  "providers": { "zhipu": true, "kimi": true, "qwen": true },  // 逐 provider 开关，缺省 true
  "builtinFallback": true    // Tier 3 兜底开关，缺省 true
}
```

- `auto`（默认）：三级链自动降级——**绝大多数用户零配置即得产品级搜索**；
- 显式 mode：满足强制审计/离线等场景；
- 现有字段 `provider/apiKey/engine` 语义收窄为"独立搜索 API 直连"（如 zhipu `/web_search` 端点），P2 再决定是否实现直连模式——P0 不动，避免范围膨胀。

### C4 结果质量护栏（针对 A2 铁证）

内置聚合路径（Tier 3）加两道廉价断言，不再让反爬垃圾静默透传：
1. `extractGenericLinks` 产物做垃圾检测：结果 snippet 命中已知反爬指纹（`checkSNUID`、`document.cookie`、`location.href` 等）→ 该条丢弃并记 WARN 日志；
2. 单引擎整批结果全部被丢弃时，视为该引擎本轮失败，走批间降级——**现状是"解析出东西就算成功"，改完后"解析出可信的东西才算成功"**。

## Part D — 分期实施

### P0 — provider 内置搜索透传（zhipu / qwen / kimi）

**目标**：用户现有四家 provider 配置零改动，三家自动获得产品级搜索。

| # | 内容 | 落点 |
|---|---|---|
| P0-1 | `SearchProviderResolver` 骨架 + zhipu/kimi/qwen 能力表 | 新文件 `core/tools/SearchProviderResolver.scala` |
| P0-2 | zhipu `tools:[{type:web_search}]` 注入 | `OpenAiAdapter` 请求构造（zhipu 分支） |
| P0-3 | qwen `enable_search:true` 注入 + **compatible-mode 冒烟验证**（字段被忽略则记录降级路径，不硬上 DashScope 原生协议） | 同上（qwen 分支） |
| P0-4 | kimi `$web_search` round-trip（echo 回灌 + 强制关 thinking） | `OpenAiAdapter` + `AgentCore` tool_call 分发 |
| P0-5 | `executeTool` 拦截路由 + Tier 3 non-guaranteed 标注 | `AgentCore.scala` |
| P0-6 | 反爬垃圾指纹检测（C4 两道断言） | `WebSearchTool.scala` |

**不包含**：MCP 路由、配置覆盖、anthropic/gemini/openai、前端 UI。

### P1 — MCP 搜索工具检测路由

| # | 内容 |
|---|---|
| P1-1 | `McpManager` 提供"已连接且名字匹配 search 的工具"查询接口 |
| P1-2 | Resolver Tier 1 接线：命中时代理调用 `mcp__<server>__<search>`，结果归一化 SearchResult |
| P1-3 | 与 buildAllowedToolSet（AgentCore L1131-1180）协同：agent 未放行该 MCP 工具时 Tier 1 视为 miss |
| P1-4 | 用户的 `zai` server 作为天然验收载体（启用后 WebSearch 应路由到 zai） |

### P2 — 配置覆盖 + provider 扩面 + 前端

| # | 内容 |
|---|---|
| P2-1 | 激活 `SearchConfig`：`mode` / `providers` / `builtinFallback` 三字段，声明式覆盖解析链 |
| P2-2 | Anthropic `web_search_20250305` + Gemini `google_search` grounding 接入（Adapter 注入，实现成本低） |
| P2-3 | 前端设置页：搜索来源面板（当前生效 tier、逐 provider 开关、费用提示） |
| P2-4 | 决策点：zhipu 独立 `/web_search` API 直连模式是否值得实现（结构化结果 vs 融入回答的权衡） |

**分期逻辑**：P0 用最小改动（两个文件 + 一个新文件）兑现红线——用户付费资产立即承载搜索；P1 把用户显式安装的 MCP 纳入；P2 才是完整产品化（配置面 + 全 provider + UI）。每期独立可发布、可回滚。

## Part E — 验收条件

所有条件均为二值断言，可自动化执行。P0 验收如下：

### E1 冒烟测试（硬性第一项）

```bash
sbt run &            # 真实启动，非 oneshot/mock
sleep 15
curl -s http://localhost:8080/api/health | grep -q '"status":"ok"'   # 服务存活
# 通过 WebSearch 触发一次 zhipu 会话搜索，断言：
# 1) 响应结果 source 字段 == "provider:zhipu"（非 Aggregated）
# 2) 结果携带 url（来源标注）
```

- [ ] 真实 `sbt run` 启动成功，`/api/health` 返回 ok
- [ ] zhipu provider 会话内 WebSearch 路由到 Tier 2，结果带来源
- [ ] qwen 冒烟：若 compatible-mode 忽略 `enable_search`（响应无搜索痕迹），**记录证据并断言降级到 Tier 3 + non-guaranteed 标注**——降级路径本身是验收通过态
- [ ] kimi 冒烟：`$web_search` round-trip 完成，最终回答包含搜索来源；thinking 字段确认为 disabled

### E2 单元测试

- [ ] zhipu 分支：构造请求体断言 `tools` 数组含 `{"type":"web_search"}` 且原有 agent 工具未被覆盖（deepMerge 顺序回归）
- [ ] qwen 分支：请求体含 `enable_search:true`
- [ ] kimi 分支：`$web_search` tool_call 到达时 echo 回灌的 tool result 与模型 arguments 逐字节一致；请求 thinking 被强制关闭
- [ ] Resolver：zhipu/kimi/qwen/deepseek/unknown 五种 provider 的 resolve() 输出符合预期表（deepseek/unknown → Tier 3）
- [ ] 垃圾指纹检测：喂入 Sogou checkSNUID 样本（取自 `delegate-Explorer-426be9df.json`）断言被丢弃 + WARN 日志产生

### E3 端到端（真实链路，不 mock LLM）

- [ ] research flow 复跑今晚场景：agent 发起搜索 → provider 搜索执行 → 结果带 url 来源 → 写入最终报告的引用可点击
- [ ] **回归铁证场景**：仅 Tier 3 可用（deepseek 会话）时，Sogou 反爬页不再出现在任何结果中（垃圾检测生效）

### E4 兼容与回滚

- [ ] 未配置任何 provider 搜索能力的存量用户：行为与现状 byte-level 等价（仅多一条 non-guaranteed 标注）
- [ ] 回滚 = revert 单一 PR；`SearchProviderResolver` 为纯新增文件，Adapter 注入受 capability 表门控，表为空即回到现状

### P1 / P2 增量验收（届时细化，先立框架）

- P1：启用 zai MCP 后，WebSearch 调用日志显示路由到 `mcp__zai__*`；禁用 zai 后回落 Tier 2/3
- P2：`search.mode: mcp_only` 时 provider 注入不再发生（请求体 diff 断言）；前端设置页读写三字段并即时生效

## 附录 — 参考链接

| 主题 | 官方文档 |
|---|---|
| Zhipu web_search 工具与独立搜索 API | https://open.bigmodel.cn/dev/api/search-tool/web-search |
| Moonshot Kimi $web_search（round-trip + 关 thinking） | https://platform.moonshot.cn/docs/guide/tool-use/web-search |
| Anthropic web_search_20250305 server tool | https://docs.anthropic.com/en/docs/build-with-claude/tools/web-search-tool |
| Gemini grounding with Google Search | https://ai.google.dev/gemini-api/docs/google-search |
| OpenAI web search tool（Responses API / preview 模型） | https://platform.openai.com/docs/guides/tools-web-search |
| DashScope qwen enable_search | https://help.aliyun.com/zh/model-studio/enable-web-search |

**审计证据文件**：
- 反爬垃圾透传铁证：`~/.nebflow/sessions/delegate-Explorer-426be9df.json`（"Search engine: Sogou" 成功条目内容为 checkSNUID 脚本）
- 引擎成败日志：`~/.nebflow/logs/nebflow.log`（2026-08-22 21:00–23:30 research flow 时段）
- 用户配置实况：`~/.nebflow/nebflow.json`（providers: kimi/deepseek/107/qwen/zhipu；mcpServers: zai[disabled]/opendataloader-pdf；无 search 节）
