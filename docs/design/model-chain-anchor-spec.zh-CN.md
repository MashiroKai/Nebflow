---
type: design-note
status: done
---

# /model 链 = 运行时 fallback 锚 spec（model-chain anchor 批 2026-10-29）

作者令逐字：「检查一下，模型的fallback链，有按我们/model里设置的来吗」。

结论：**修复前不一致（缺口）；本批修完一致**。缺口根因不是触发面，而是**锚点派生面**——`/model` 写入的链没进运行时 `AgentDef.model`，fallback 链的**起点**另有一套（preset 名 / 默认 preset / provider seed）。触发面（何时换 provider）本批**零改动**。

相关概念：候选链尾的追加层见本文 [[#^reserve-tier]]；文档库纪律见 [[glossary#^vault]]。

## 1. 现行规则成文（修复后）

### 1.1 锚点：一条链，单点解析

运行时模型链的**唯一**解析点 = `nebflow.core.SchemePolicy.resolveModel(name, own)`（`src/main/scala/nebflow/core/SchemePolicy.scala:103-114`），优先级：

```
own chain（仅 SettableAgents：Nebula / project-dispatcher / 内置执行 agent）
  > Nebula 主链（nebulaChain()，其余 agent 每 turn 现读）
    > seed 链（nebflow.json 的 legacy llm.model；无则首个 provider 的首个模型）
```

`SettableAgents` 见 `SchemePolicy.scala:48`；`/model` 的读/写面 = `GET|PUT /api/agents/:name/model`（`src/main/scala/nebflow/gateway/PresenceRoutes.scala:1153` / `:1193`），两者与运行时用**同一个** `resolveModel`，所以面板读数与运行时锚同源。

### 1.2 候选链形状

`ProviderRegistry.getCandidatesForAgent(agentModel)`（`src/main/scala/nebflow/llm/registry.scala:240-257`）：

```
[preferred, ...fallbacks]  ++  reserveTier（配置里已存在但链未引用的 provider/model，
                                           按 providerId → model id 字典序追加）
```

储备层（reserve tier）— 链尾追加层：把配置里**已存在但当前链未引用**的 provider/model 按确定序补到候选链末尾，只追加不重排（`registry.scala:271-301`）；事故教训是候选链曾退化为单点（「有效链深 1」），储备层让该形态结构上不可达；主用模型面仍取链首，故不影响 `/model` 面板的「当前模型」显示。 ^reserve-tier

### 1.3 触发条件面（本批未改）

| 触发 | 行为 | 锚 |
|---|---|---|
| Transient 错误 | 同 provider 重试 1 次 | `Fallback.MaxRetries`（`llm/fallback.scala:50`） |
| 首 token / 中途停顿看门狗超时 | 直接跳下一根 | `llm/interface.scala:1159-1182` + `shared/Defaults.scala:241`（45s 软回避窗） |
| 配额（429-1308 / 403 insufficient） | 300s 软回避窗，不 markDown | `llm/interface.scala:1059-1087` |
| Auth / 404 / EmptyStream 等确证死亡 | `markDown` 进探测集 | `llm/interface.scala:1069-1087` |
| 整链走完仍未成功 | 最多再走 2 轮 | `Fallback.MaxChainRounds`（`fallback.scala:69`）→ `interface.scala:772-777` |
| 已吐内容后 | 不再换 provider（缝合守卫） | `fallback.scala:299-309` |

## 2. 一致性判定

### 2.1 修复前 = 缺口

`/model` 写入 `agents/<role>/agent.json` 的 `model` 链，但运行时 `AgentDef.model` 由**另一套**策略派生：

- `core/entity/EntityTypes.scala` 的 `toAgentDef` 与 `agent/AgentLibrary.scala` 的 `loadCustomFromDir` 走 preset 名派生（`presets/SchemePolicy` + `PresetStore.resolve`）——modelcfg 批（`0d714ef66`）引入 `core.SchemePolicy` 后两套策略并存，reapply/checkpoint 又把旧派生带回装载链；
- `llm/registry.scala` 的 **全局腿** `getCandidates()` 以 `resolveModel(NebulaName, None)` 取链 ⇒ 恒短路到 seed 链（"Nebula has no one to follow"），`/model` 设置被静默丢弃；同样问题在 `llm/SearchProviderResolver.modelChainRefs` 的兜底腿。

实测证据：新 spec `src/test/scala/nebflow/core/ModelChainAnchorSpec.scala` 修复前 **3/3 红**，断言到运行时候选链首位 = `legacy/first-model`（seed 链）而非 `/model` 的 `zhipu/glm-primary`。⇒ 面板显示一套、真正跑的是另一套。

### 2.2 修复后 = 一致

同一 spec 3/3 绿：

1. 内置执行 agent 的 `AgentDef.model` = `/model` 链（且 dispatcher 私有链不越权劫持）；
2. `/model` 写后**下一 turn**（每 turn 现读，无需重启）锚点随变；
3. 磁盘自定义 agent 跟随 Nebula 主链，其存储 model 降级为审计面。

实盘核对（`~/.stand/agents/Nebula/agent.json`）：`model = zhipu/glm-5.3-flash + kimi/kimi-for-coding + cmdcode/…`，`project-dispatcher` 无 model/preset（跟随 Nebula）——修复后运行时与面板读数一致。

## 3. 改动面（只动派生，不动触发）

| 文件 | 改动 |
|---|---|
| `core/entity/EntityTypes.scala` `toAgentDef` | 改用 `core.SchemePolicy.resolveModel`；`preset` 仅对可设角色保留 |
| `agent/AgentLibrary.scala` `loadCustomFromDir` | 同款两行 |
| `llm/registry.scala:79-105` `getCandidates()` | 全局腿改传 `nebulaChain()` |
| `llm/SearchProviderResolver.scala:207-227` | 同款 nebulaChain() 输入 |
| `gateway/GatewayMain.scala:505-535` | contextWindow 改读 `/model` 主链（原读默认 preset 目录）；横幅改 `(model chain $source: $ref)` |
| `gateway/GatewayMain.scala:429-435` | 注释申报：`ModelChainMigration.migrateLegacyPresetRefs()` 仍**故意不接启动腿**（它封存 `model-presets.json`，而 `SubTaskTool` 的 preset 参数面仍读该文件），留作者裁决 |
| 注释同步 | `NodeStarter`（3 处）/`NodeLoopRunner:54`/`NodeEditTool:556-570`/`OnboardingService` |
| 删除死码 | `core/presets/SchemePolicy.scala` + `SchemePolicySpec.scala`（162 行） |
| 新 spec | `core/ModelChainAnchorSpec.scala`（3 例，修复前 3/3 红） |
| 重钉旧 spec | `core/plugin/NodePluginChainSpec` 夹具改写入 Nebula `/model` 链 |

读数：`Test/compile` 成功；`sbt test` 全量绿（见本批报告）。

## 4. 实况佐证：2026-10-29 18:40–18:49 zhipu 限流风暴（修复前代码的在产实例）

`~/.stand/logs/stand.log` 实录，作者问「链上下一跳为什么没接住」，答案分三层：

**配置面（根因，= 本批修的缺口）**：在产 `model-presets.json` 的 `defaultPreset=general`
= **`zhipu/glm-5.3-flash` 单腿、fallbacks 空**；`/model` sidecar（Nebula）却是三腿
（zhipu → kimi/kimi-for-coding → cmdcode/deepseek-v4.1-flash）。修复前运行时锚走 preset
派生 ⇒ 真实候选链 = `[zhipu（preset 首选）] ++ reserveTier（配置内其余 provider/model，
providerId→model 字典序：cmdcode/deepseek-v4.1-flash → kimi/k3.1-pro-highspeed →
kimi/kimi-for-coding → kimi/kimi-for-coding-highspeed）`。**/model 钦定的 kimi-for-coding
被字典序压到第 4 位，风暴中从未被够到**——这就是「/model 设置的链没生效」的实况形态。

**触发面（机制在动，非死面）**：zhipu 429 不带 1308 配额码 ⇒ `FailoverReason.RateLimit`
（`llm/fallback.scala:252`；只有 1308 进 300s 软回避集 `QuotaUpstreamCodes`，`:106`）→
**同 provider 重试 1 次**（`Transient`，`interface.scala:1140` 的 `Stream retry … (1 left,
60000ms)`；重试仍败才 `retries exhausted` 跳下一根，`:1150-1153`）。窗口内
`Stream retry zhipu ×22`；zhipu 是**间歇性**限流（564 次成功吐字，health `marked DOWN →
recovered` 最短 16–46ms 即被并发成功请求翻回），绝大多数请求的重试落回活着的 zhipu，故
表面只见「zhipu 自身重试」。真正耗尽重试的 4 次确实发生了下一跳：先 cmdcode（字典序 #2，
连接异常/死 key，`retries exhausted` ×2 后 markDown——**毒腿**，白耗一轮重试）再
kimi/k3.1-pro-highspeed（`Stream retry kimi/k3.1-pro-highspeed: RateLimit` ×1，且它自身
也在限流）。已吐内容后的缝合守卫照常生效（会话内不中途换 provider）。

**三问直答**：①会话锚定不漂移？否——链按 turn/请求现读，粘滞是 RateLimit 重试类 +
间歇恢复的表象；②kimi 不在链里？在，但走的是储备层追加的 k3.1-pro-highspeed（字典序），
**不是 /model 钦定的 kimi-for-coding**——运行时锚无视 /model 的缺口实锤；③failover 只在
boot 期？否——全窗口内逐请求都在 failover（retry/exhausted/下一跳日志为证）。

修复后预期形态：运行时锚 = /model 三腿 ⇒ zhipu 限流重试耗尽 → **kimi-for-coding 第一顺位
接住** → cmdcode 殿后；cmdcode 死 key 建议作者裁决摘除或换 key（本批不动触发面与配置面）。

## 5. 未做项

- 触发条件面零改动（作者令「修复范围克制」）；
- preset → model 启动迁移腿未接（理由见上表，需作者裁决是否整面退役 preset）；
- 未重启在产实例（改动随下次重启窗生效，见 [[glossary#^restart-window]]）；
- 未动 `main`、未 push。
