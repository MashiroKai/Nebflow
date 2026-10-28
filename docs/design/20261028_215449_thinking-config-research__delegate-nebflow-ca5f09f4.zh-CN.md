---
type: design-note
status: draft
---

# 思考模式（thinking）配置调研 + 优化设计提案

批：thinking-config-research 调研批（2026-10-28 21:36 作者令：「调研一下目前的这些模型支持哪些思考模式配置？我们的思考模式配置好像有些过时了。优化一下。」）。
性质：**调研报告 + 优化设计提案，不含代码实施**（提案过目后另派实施批）。
对象：实例 `~/.stand/nebflow.json` 现配四家——zhipu（glm-5.3-flash）、kimi（kimi-for-coding / kimi-for-coding-highspeed / k3.1-pro-highspeed）、cmdcode（deepseek/deepseek-v4.1-flash）、jev（typesafe-jev）。
方法：引擎侧行号级考古（grep 消费点逐点核读）+ 供应商官方文档直连抓取（每条结论带 URL + 抓取日期）。**全程零真实推理调用**——未向任何模型端点发送推理请求；对 `api.typesafe.ai` 的两次探测为无凭据 GET/POST（451，零配额消耗）。

## 1. 引擎现状考古（行号级出处）

### 1.1 配置 schema：单一全局旋钮

- `src/main/scala/nebflow/shared/config.scala:380-393`：`ThinkingConfig(enabled: Boolean = true, budgetTokens: Int = 32000)`。解码缺省同值（`:390-391`）。全引擎**仅此一处思考配置**，没有 provider/model 级形态。
- `config.scala:428`：`NebflowServiceConfig.thinkingConfig: Option[ThinkingConfig]` —— 顶层全局节。
- 实例现值（`~/.stand/nebflow.json`）：`{"enabled": true, "budgetTokens": 32000}`。

### 1.2 配置 → 线上 JSON 的唯一翻译点

- `config.scala:396-402`（`toLlmJson`）：enabled → `{"type":"enabled","budget_tokens":N}`；disabled → `io.circe.Json.Null`。

### 1.3 注入链路（每请求）

- `src/main/scala/nebflow/agent/AgentSessionExecution.scala:1006-1015`：每个 `LlmRequest` 都带 `thinking = Some(toLlmJson(workerThinking))`——注意 disabled 时也是 `Some(Json.Null)`（不省略）。
- `AgentSessionExecution.scala:1000-1008` + `config.scala:412-415`：worker 帽——depth>0 派生会话预算钳到 `Defaults.SubagentThinkingBudgetCap`（`src/main/scala/nebflow/shared/Defaults.scala:93`，=32000）。
- `src/main/scala/nebflow/llm/interface.scala:504-522`（非流）/ `:761-770`（流）：全局内部顶——预算 > `Defaults.MaxThinkingBudget`（`Defaults.scala:80`，=32768）钳到 32768。产品可产生的最高预算恰为 32768（UI high 档），故正常配置不被钳；该钳是遗留手改值防线。

### 1.4 协议面：anthropic 直通、openai 映射

- **anthropic 协议**（zhipu / kimi / cmdcode 三家现配全走此协议，`registry.scala:39-51` 构造 `AnthropicAdapter`）：
  - `src/main/scala/nebflow/llm/providers/AnthropicAdapter.scala:358-373`（非流）/ `:467-469`（流）：`bodyWithTools.deepMerge(Json.obj("thinking" -> t))` —— 上文翻译出的 JSON **原样上线**，无按模型改写。
  - `AnthropicAdapter.scala:236-247`（`effectiveThinking`）：唯一按条件改写——跨 provider 历史重放保护：`requireThinkingPassback` 为真且历史缺 thinking 块时整请求剥离 thinking（20260903 审计④，deepseek 400 风暴的修复）。`requireThinkingPassback` 取值 = provider 配置显式值，否则 `providerId == "deepseek"`（`registry.scala:50`）。
  - `AnthropicAdapter.scala:291-304`（`maxTokensFor`）：`max_tokens = max(128000, budget+128000)` → glm 族帽（`:315-320`，`FamilyOutputCaps` glm→32768）→ 窗口钳 → 硬地板 `budget+1`（API 不变式 budget_tokens < max_tokens）。
  - **关闭路径的线上形态**：disabled → `Some(Json.Null)` → `deepMerge` 后线上多出 `"thinking": null` 键（两发送面同形态；`AnthropicThinkingReplaySpec` 只钉重放剥离腿，未钉 null 形态）。
- **openai 协议**（现配无此协议 provider，机制在册）：`src/main/scala/nebflow/llm/providers/OpenAiAdapter.scala:53-84`——budget→`reasoning_effort` 档位映射（≤2048 low / ≤8192 medium / ≤32768 high / >32768 xhigh）；模型名含 glm 时改发 GLM 原生 `thinking:{type:"enabled"}`（`:74-84`）；kimi 内置搜索互斥时强制 `thinking:{type:"disabled"}`（`:225-233`）。

### 1.5 各家现配实际收到什么（按现 nebflow.json 推演，全为 anthropic 协议）

| provider/model | thinking 参数（逐字） | max_tokens 推演 |
|---|---|---|
| zhipu / glm-5.3-flash | `{"type":"enabled","budget_tokens":32000}` | 32768（glm 族帽钳后，32000+地板保住） |
| kimi / kimi-for-coding 等 3 模型 | 同上 | 160000（=budget+128000；窗口不触） |
| cmdcode / deepseek-v4.1-flash | 同上 | 160000 |

即：**三家三种思考面迥异的模型，收到的是同一份 Anthropic 语义的 budget 数字参数**。

### 1.6 配置读写面（UI / 快照 / CLI）

- `src/main/scala/nebflow/gateway/WsConfigHandlers.scala:89-105`：`setThinking` 动作——读 `enabled`/`budgetTokens`（缺省 32000）→ Ref + 落盘（`WebSocketRoutes.scala:1594-1610` 定向写）。
- `WebSocketRoutes.scala:1188/1208、1551/1563`：`serverConfig` 连接初态与广播带 `thinking` 节。
- `src/main/resources/web/js/sidebar.js:1209-1225`：设置页「思考 effort」下拉（#345，2026-08-20 作者裁定）——off/low/medium/high 四档映射 budget `{off, 2048, 8192, 32768}`，保存写全局节；`:762` 读数缺省 32000。
- `src/main/scala/nebflow/cli/ModelCommand.scala:120,156-179`：CLI `model thinking` 面，同 schema。

### 1.7 JeV 面：天然不适用

- `src/main/scala/nebflow/llm/decision/DecisionProvider.scala:8-21`：JeV 线协议是结构化问答（`POST /v1/systemone`，顶层恰好 `state`/`model`/`questions` 三字段），**与 chat 形（messages+tools+thinking）在类型层不同构**，独立文件不接 `ProviderAdapter`。`thinkingConfig` 从不过 JeV 通道——外调结论（§2.5）与此互证。

### 1.8 旁支发现：cmdcode 的 requireThinkingPassback

- `registry.scala:50`：启发式只认 `providerId == "deepseek"`。现配 provider id 是 `cmdcode`（服务 deepseek 模型族），且 nebflow.json 未显式配 `requireThinkingPassback` ⇒ **false**。DeepSeek 官方 anthropic 面在 thinking 模式要求回传 thinking 块（外调 §2.4 + 引擎 `AnthropicAdapter.scala:218-234` 的 20260903 风暴记录）——cmdcode 链条若遇跨 provider 历史回退存在同型 400 隐患。列入 §6 裁定单。

## 2. 外部调研（官方文档，逐家带出处）

抓取时间统一 **2026-10-28 21:45–22:05（本机时区）**；原始页面存档 `/tmp/thinking-research/*.txt`（HTML 剥文本）。直连可达；仅 Anthropic 两页走本机代理 `127.0.0.1:7890`（直连被区域页拦下）。

### 2.1 Anthropic 原生（我们 anthropic 适配的基准语义）

来源：
- https://platform.claude.com/docs/en/docs/build-with-claude/extended-thinking （抓取 2026-10-28，代理）
- https://platform.claude.com/docs/en/build-with-claude/effort （同上）

结论：
1. **手动扩展思考** `thinking: {type: "enabled", budget_tokens: N}`——`budget_tokens` 下限 **1,024**（更小 400 拒）；必须 **< max_tokens**（manual interleaved 例外）；budget 是**目标非硬帽**；>32k 官方建议改走 batch（长请求易触系统超时）。
2. **该形态已被下一代取代**：Claude 4.6 系上 **deprecated（请求仍成功）**；**Claude 4.7+ 直接 400**（报错样例 `"thinking.type.enabled" is not supported`）。后继 = **自适应思考**：`thinking: {type: "adaptive"}` + `output_config: {effort: ...}`；仅支持扩展思考的模型（4.5 及更早）传 `type:"adaptive"` 反而 400。
3. **effort 五档**：`low / medium / high / xhigh / max`（max/xhigh 仅部分旗舰支持）；effort 是**行为信号非 token 预算**——低档在难题上仍会思考只是更省；多数模型默认 high（Opus 5.5 / Haiku 5.5 默认 medium）。

### 2.2 智谱 GLM（含现配 glm-5.3-flash）

来源：
- https://docs.bigmodel.cn/cn/guide/capabilities/thinking-mode （思考模式页，抓取 2026-10-28）
- https://docs.bigmodel.cn/cn/guide/capabilities/thinking （深度思考页，同上）
- https://docs.bigmodel.cn/cn/guide/models/vlm/glm-5.3-flash （模型页，同上）
- https://docs.bigmodel.cn/cn/guide/develop/claude/introduction （Claude 兼容端点页，同上）

结论：
1. **GLM-5.3 / GLM-5.3-FLASH 强制思考、不可关闭**——深度思考页原文：「API 请求中 thinking.type 传 disabled 将会报错」；模型页：「thinking.type 仅支持 enabled，不支持关闭思考」。GLM-5.2/5.1/5/4.7 默认开（可关，`{"thinking":{"type":"disabled"}}`）。
2. **`reasoning_effort`（GLM-5.2+ 支持）**：GLM-5.3/5.3-FLASH/5.3-FLASHX 在标准 API 请求中**仅收 `max` / `high` / `low`，其余输入报错**；GLM-5.2 收 max/xhigh/high/medium/low/minimal/none（low/medium→high、xhigh→max、none/minimal=放弃思考）；Coding Plan 请求面对全档有映射（none/minimal/low→low，medium/high→high，xhigh/max→max）。**默认且推荐 max**。模型页推荐设置原文：`temperature:1、top_p:0.95、reasoning_effort:max；thinking.clear_thinking:false`。
3. **保留式思考**：`clear_thinking: false` 开启（Coding Plan 端点默认开、标准 API 默认关），要求完整未修改回传 reasoning content——与引擎已有的「thinking 块原样回传」契约（`AnthropicAdapter.scala:229-231`）方向一致。
4. glm-5.3-flash 规格：**上下文 1M、最大输出 128K**（模型页「上下文窗口 1M / 最大输出 128K」）——注意引擎 glm 族帽 32768 是保守地板（`AnthropicAdapter.scala:315-320` 自注「CONSERVATIVE floors」），远低于真实 128K。
5. **Claude 兼容端点**（= 我们配置的 `https://open.bigmodel.cn/api/anthropic`）：官方页只说「Anthropic SDK 直用、换 base_url 与密钥」，**未见任何 thinking 参数适配/差异说明**——该兼容面对 `budget_tokens` / `reasoning_effort` / `output_config` 的真实处置**未能核实**（见 §6-1）。

### 2.3 Kimi / Moonshot（k3.1 系与编码面）

来源：
- https://platform.kimi.com/docs/guide/use-thinking-models （思考模型页，抓取 2026-10-28）
- https://platform.kimi.com/docs/guide/use-reasoning-effort （推理强度页，同上）
- https://platform.kimi.com/docs/guide/claude-code-kimi （Claude Code / Anthropic 兼容端点页，同上）

结论：
1. **kimi-k3.1-pro**（现配 `k3.1-pro-highspeed` 同家族）：**始终推理、保留式思考恒开**；深度控制**只有顶层 `reasoning_effort`：`"low"/"high"/"max"`（默认 max）**；参数表明确 `thinking.type` 一栏为「—」（不适用）。官方迁移指引原文：「从 K2.x 迁移到 K3 时，**移除 K2.x 的 thinking 配置**，并按需使用顶层 reasoning_effort」。
2. **kimi-k2.7-code**（现配 `kimi-for-coding` 家族的行为参照）：**恒思考、thinking.type 仅 `enabled`，传 `disabled` 报错**；`thinking.keep` 不传或 `all` 均按恒开处理，**其他非法值报错**；`reasoning_effort` 不支持。
3. kimi-k2.6：`thinking.type` enabled（默认）/disabled + `thinking.keep` null（默认）/all——可关的代表。
4. **Anthropic 兼容面**（官方 Claude Code 接入页）：base `https://api.moonshot.cn/anthropic`；k2.7-code 未开思考被拒样例原文：**`400 invalid thinking: only type=enabled is allowed for this model`**；强度经客户端 env `CLAUDE_CODE_EFFORT_LEVEL`（建议 max）表达——即该面的「effort」目前是客户端约定，非请求参数。
5. **我们配置的 `api.kimi.com/coding/`（Kimi For Coding 端点）**：platform.kimi.com 全站 sitemap（261 页，已核对）**无该主机/端点的文档页** ⇒ 该端点的请求参数面**未能核实**；模型行为按同家族推断（kimi-for-coding≈k2.7-code 系强制思考、k3.1-pro-highspeed≈k3.1-pro 系 effort 制）。列入 §6。

### 2.4 DeepSeek（v4.1-flash 族）

来源：
- https://api-docs.deepseek.com/guides/thinking_mode （Thinking Mode 指南，抓取 2026-10-28；参数表 colspan 已逐格核对）
- https://api-docs.deepseek.com/guides/anthropic_api （Anthropic API 字段支持表，同上）
- https://api-docs.deepseek.com/quick_start/pricing （模型与定价页，同上）

结论：
1. **现役模型名**：`deepseek-flash`（版本 DeepSeek-V4.1-Flash-1017）与 `deepseek-v4-pro`；legacy 名 `deepseek-v4-flash` 已退役但请求由 V4.1-Flash-1017 承接——现配 `deepseek/deepseek-v4.1-flash` 即 **V4.1-Flash 族**。
2. **参数面（官方表格原文，三格式分列）**：
   - 开关：OpenAI 格式 **与 Anthropic 格式通用** `{"thinking": {"type": "enabled/disabled"}}`（表格中该格 colspan=2，跨两列）；Responses 格式 `{"reasoning": {"effort": "none/low/high/max"}}`（none=关）。
   - 深度：OpenAI 格式 `{"reasoning_effort": "low/high/max"}`；**Anthropic 格式 `{"output_config": {"effort": "low/high/max"}}`**。
   - 默认：**thinking 默认开启、默认 effort=high**；档位映射 minimal/low→low、medium/high/xhigh→high、max/ultra→max。
   - thinking 模式忽略 temperature/presence/frequency（收下不报错不生效）。
3. **Anthropic 面字段支持表（与我们协议直接相关）**：`thinking`——**Supported（budget_tokens is ignored）**，即官方 anthropic 兼容面**显式忽略 budget_tokens**；`output_config`——**仅 effort 支持**；消息 `type:"thinking"` 块 Supported、`redacted_thinking` Not Supported；端点 `https://api.deepseek.com/anthropic`。
4. **cmdcode（`api.commandcode.ai/provider/`）是第三方聚合端点**：无公开参数文档 ⇒ 其真实面**未能核实**；上列官方语义是模型族的最强参照（官方明示 budget_tokens 被忽略 ⇒ 我们现在每天发的 32000 大概率在 cmdcode 链上同样无效）。

### 2.5 typesafe / JeV

来源：
- https://docs.typesafe.ai/api/system-one （System One API 参考页，抓取 2026-10-28）
- https://docs.typesafe.ai/sitemap.xml （全站索引）

结论：请求面 = `model` + `state` + `questions`（+ 图像 `assets`），问答型结构；**请求面不存在任何思考模式参数**（无 thinking/reasoning/effort/budget 字段）。与引擎判读（§1.7）互证：**thinkingConfig 对 JeV 天然不适用，零动作**。另：`api.typesafe.ai/` 与 `/v1/systemone` 直连探测返回 451（无凭据，未发推理内容）。

## 3. 差距表：现配置 vs 各模型真实支持面

| # | provider/model | 现在上线的参数 | 该模型文档真实支持面 | 判定 |
|---|---|---|---|---|
| 1 | zhipu / glm-5.3-flash | `thinking:{type:enabled, budget_tokens:32000}` | `type` 仅 enabled（disabled 报错）；深度走 `reasoning_effort`（仅 max/high/low，其余报错）；`clear_thinking:false` 保留式思考 | **budget_tokens 处置未核实**（兼容面无文档）；**effort 档位能力未暴露**（现在无法调 max/high/low）；关闭档对该模型是**错误路径**（传 disabled 报错，现发 null 形态未核实） |
| 2 | kimi / kimi-for-coding(±highspeed) | 同上 | 家族参照 k2.7-code：`type` 仅 enabled（disabled→400 `invalid thinking`）；keep 恒 all；**无 effort 参数** | budget_tokens 未核实；**无深度可调**；**关闭路径是潜在 400**（现发 `thinking:null`，形态未核实但同属「非 enabled」） |
| 3 | kimi / k3.1-pro-highspeed | 同上 | 家族参照 k3.1-pro：**只有顶层 `reasoning_effort`（low/high/max，默认 max）**，官方明示 K3 应**移除 thinking 配置** | **发的是官方明示要移除的参数形态**；effort 能力未暴露 |
| 4 | cmdcode / deepseek-v4.1-flash | 同上 | 官方 anthropic 面：`thinking` 支持**但 budget_tokens 显式忽略**；深度走 `output_config.effort`（low/high/max）；默认开、high | **budget_tokens = 官方文档白纸黑字的无效参数**；effort 未暴露 |
| 5 | jev / typesafe-jev | 不适用（通道类型不同构） | 请求面无思考参数 | **无差距、无动作** |
| 6 | （未配置）anthropic 原生 | 若配置将发 budget 型 | budget 型 4.6 deprecated / **4.7+ 400**；新形态 `type:"adaptive"` + `output_config.effort` | schema 应预留 adaptive/effort，防未来接入即过时 |

横向共性结论：**行业已从「固定 token 预算」迁往「effort 档位（+自适应）」**——Anthropic（output_config.effort 五档）、DeepSeek（output_config.effort 三档）、Kimi K3（reasoning_effort 三档）、GLM（reasoning_effort 三档）全部如此；我们 2026-08 定型的 budget 单旋钮与四家现役面全部错位（或无效、或未暴露真实能力）。

## 4. 新 schema 设计提案

### 4.1 形态：三层继承（model > provider > 全局）

```jsonc
// ~/.stand/nebflow.json（示意，全部新键可选）
{
  "thinkingConfig": {                    // 旧键原样保留 = 全局缺省（向后兼容基线）
    "enabled": true,
    "budgetTokens": 32000,               // 旧字段语义不变：budget 型线上的数值来源
    "effort": "high"                     // 新（可选）：全局缺省档位
  },
  "llm": {
    "providers": {
      "zhipu": {
        "protocol": "anthropic",
        "thinking": {                    // 新（可选）：provider 级
          "enabled": true,
          "effort": "max",
          "clearThinking": false         // 新（可选）：仅 GLM 保留式思考消费
        },
        "models": [
          {
            "id": "glm-5.3-flash",
            "contextWindow": 1000000,
            "thinking": { "enabled": true, "effort": "max" }   // 新（可选）：model 级，最优先
          }
        ]
      },
      "kimi": {
        "protocol": "anthropic",
        "models": [
          { "id": "k3.1-pro-highspeed", "contextWindow": 1048576,
            "thinking": { "effort": "high" } },
          { "id": "kimi-for-coding", "contextWindow": 1000000,
            "thinking": { "enabled": true } }                  // 开关型：无 effort 可调
        ]
      },
      "cmdcode": {
        "protocol": "anthropic",
        "requireThinkingPassback": true,                       // 旁支发现（§1.8）顺手补
        "models": [
          { "id": "deepseek/deepseek-v4.1-flash", "contextWindow": 1000000,
            "thinking": { "enabled": true, "effort": "high" } }
        ]
      }
    }
  }
}
```

字段与语义：

- `ThinkingConfig` 扩展为 `{enabled, budgetTokens, effort?, clearThinking?}`（`ProviderConfig`/`ModelConfig` 各挂一个可选 `thinking` 节）。
- **继承按字段就近**（model > provider > 全局；未设的字段逐项上溯，不整块覆盖）——例如 model 级只写 `effort` 时，`enabled` 沿用全局 true。
- `effort` 产品档位：`off | low | medium | high | max`（对齐 Anthropic 五档与各家交集）。各家翻译：
  - 真正有 medium 档的只有 Anthropic 原生；GLM/DeepSeek/Kimi 三档面把 `medium` 映射 `high`（DeepSeek 官方映射表即如此；GLM/Kimi 同型，Kimi medium→high 还是 low **需作者裁定**，见 §6-7）。
  - `off` 只对「可关」模型真实生效（GLM-5.2、kimi-k2.6 面）；**强制思考模型（glm-5.3-flash、kimi-for-coding 家族）的 off 在 UI 置灰**，引擎侧兜底按 enabled 发。
- `budgetTokens` 保留双职：budget 型 wire（Anthropic ≤4.5 系）直发；其余面仅作旧值兼容输入（见 4.2 翻译表），不再是唯一旋钮。

### 4.2 wire 翻译表（实施时收敛在 adapter 单点）

| provider 能力类 | wire 形态（enabled） | wire 形态（off） | 现状差异 |
|---|---|---|---|
| budget 型（Anthropic ≤4.5、现缺省兜底） | `thinking:{type:enabled, budget_tokens:N}` | **省略 thinking 键**（修掉 `thinking:null`） | off 形态变化；enabled 不变 |
| effort 型 anthropic 面（DeepSeek 官方面） | `thinking:{type:enabled}` + `output_config:{effort}` | `thinking:{type:disabled}` | **不再发 budget_tokens**（官方明示忽略） |
| 开关型（GLM-5.3-flash、kimi-forcoding 家族） | `thinking:{type:enabled}`（+GLM `clear_thinking`） | 禁用 off（UI 置灰 + 引擎兜底） | 不再发 budget_tokens |
| effort 型顶层（kimi k3.1 面若证实收） | 顶层 `reasoning_effort` | 省略 | 未核实前**不发**，见 §6-1 |
| openai 协议（机制在册） | `reasoning_effort` 直发档位（budgetToEffort 边界保留，旧 budget 值仍可换算） | 省略 | 档位语义对齐 |

实施要点：翻译表落点 = `AnthropicAdapter` 新增 `thinkingBodyFor(provider, model)`（仿 `OpenAiAdapter.thinkingBody` 的既有形态，`OpenAiAdapter.scala:74-84`），能力类判据优先读 provider/model 配置的显式 `thinkingKind`（若给），缺省按模型名家族启发（glm/kimi/deepseek 前缀表）+ anthropic 兜底 budget 型——**启发式只影响缺省，显式配置永远赢**。

### 4.3 迁移设计

1. **旧键零破坏**：`thinkingConfig` 键名、字段、缺省、UI 写路（`WsConfigHandlers.scala:89-105`）与 CLI 面全部不动，语义降格为「全局缺省层」。decoder 本就容忍缺字段（`config.scala:388-393`），新字段全部 Option 缺省 ⇒ 旧配置文件解析行为逐字节不变。
2. **行为分两段点亮**：
   - 第一段（无新配置时）：仅修两处错误形态——off 时省略 thinking 键（替代 `thinking:null`）+ budget_tokens 只对 budget 型发送（= 现配三家立即从「发未知参数」变为「发最小合法参数」，行为风险单调下降）。
   - 第二段（配置点亮）：逐 provider 配 `effort`，adapter 按翻译表发 output_config/reasoning_effort。
3. **UI 迁移**：现有 effort 下拉继续写全局节（值域不变）；per-model 控件为第二期（§5）。
4. **不需要数据迁移**：nebflow.json 无需改写，新键按需手加或由设置面板二期写入。

## 5. 设置面板呈现建议（供设置面板批 #1032 或后续批衔接）

- 现设置页「思考 effort」单下拉保留为**全局行**（不动）。
- 其下新增「按模型」分组：每 provider 一个折叠区，区内每模型一行：`模型名 · effort 下拉(继承/low/medium/high/max/off)`；「继承」项右侧灰字显示**实际生效值**（解析继承链后的结果，所见即所得）。
- 强制思考模型（能力表判定）：`off` 档置灰 + 行尾徽标「模型强制开启思考」（GLM-5.3-flash / kimi-for-coding 家族）。
- 三档面模型（GLM/Kimi/DeepSeek）下拉里 medium 项可显示映射提示（`medium → high`）。
- 写路：扩展现有 WS `setThinking` 通道为可选带 `provider`/`model` 键（缺省 = 全局，老前端零感知）；持久化沿用 `persistThinkingConfig` 的定向写形态（`WebSocketRoutes.scala:1594-1610`）。

## 6. 风险与开放问题（需作者裁定）

1. **兼容面 effort 接受度未核实**（核心不确定性）：三家 anthropic 兼容面（zhipu / api.kimi.com / cmdcode）对 `output_config.effort` / 顶层 `reasoning_effort` 是否接受，官方文档均未覆盖（文档只写原生面）。建议实施批第一步用**空请求报错探测**（无凭据或极小请求看 4xx 参数报错，零配额）逐家核定后再点亮 effort 腿；核定前第二段不生效，第一段（去无效参数）无此依赖。
2. **glm 现网输出饥饿疑点（建议尽快定性）**：现网每次 glm-5.3-flash 调用都带 `budget_tokens:32000` 且 `max_tokens` 被 glm 族帽钳到 32768——若 zhipu 兼容面按 Anthropic 语义把 budget 计入 max_tokens，**可见输出理论上限只剩 768 token**；若它像 DeepSeek 一样忽略 budget_tokens 则无害。一次探测即可定性；在定性前族帽 32768 不建议动。
3. **`thinking:null` 关闭路径**：全局 enabled=false 时三家线上都带 `"thinking": null`——对强制思考模型（kimi-for-coding 家族官方报 `400 invalid thinking`）是潜在 400。第一段迁移顺手修掉（省略键），在修掉前**请勿把全局开关拨 off**。
4. **cmdcode `requireThinkingPassback` 缺省 false**（§1.8）：providerId 启发式只认 `"deepseek"`。建议二选一：nebflow.json 显式补 `"requireThinkingPassback": true`（现 schema 即支持），或实施批把启发式改模型族判定。**裁定项**。
5. **MaxThinkingBudget=32768 顶**：对真 Anthropic，32k 是「>32k 建议 batch」的分界线附近，且 effort 制迁移后 budget 语义弱化——保留还是放宽随真 Anthropic 接入再裁，现配置无此 provider，不紧迫。
6. **adaptive（Anthropic 4.6+）**：schema 预留 `effort` 字段已覆盖其深度表达；`thinking.type:"adaptive"` 不进本期实现（无此 provider），未来接入时在能力表加一行即可。
7. **产品 medium 档在 Kimi 三档面的映射**（high 或 low）需裁定；GLM/DeepSeek 有官方映射（medium→high）可循。
8. **api.kimi.com/coding/ 与 cmdcode 端点文档缺失**：两家的参数行为目前只能按官方家族语义推断；实施批的探测结果应回填本文档 §2.3/§2.4，作为能力表初始化依据。

## 7. 附：逐家一句话结论清单

- **zhipu glm-5.3-flash**：思考强制开启不可关，深度走 `reasoning_effort`（仅 max/high/low），budget_tokens 未知且 effort 未暴露。
- **kimi kimi-for-coding(±highspeed)**：家族行为=恒思考仅收 `type:enabled`（disabled 400），无可调深度，budget_tokens 未知。
- **kimi k3.1-pro-highspeed**：家族行为=只收顶层 `reasoning_effort`（low/high/max，默认 max），官方明示应移除 thinking 配置。
- **cmdcode deepseek-v4.1-flash**：官方 anthropic 面支持 thinking 但**明示忽略 budget_tokens**，深度走 `output_config.effort`（low/high/max，默认开/high）。
- **jev typesafe-jev**：结构化问答 API 无思考参数，配置天然不适用，零动作。
- **（基准）Anthropic 原生**：budget 型已 deprecated（4.6）/4.7+ 400，行业新基准=adaptive + `output_config.effort` 五档。

（外调原始页面存档：`/tmp/thinking-research/*.txt`，抓取 2026-10-28 21:45–22:05；报告路径 `docs/design/20261028_215449_thinking-config-research__delegate-nebflow-ca5f09f4.zh-CN.md`。）
