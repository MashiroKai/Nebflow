> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 默认 Preset 语义收口 ——"全局默认模型"概念退役

> 任务：落实 2026-08-19 23:22 用户裁定——**不存在"全局默认模型"，只存在"全局默认 preset"**。
> 基线：archive/scala @ 872ec7bc（#311 已合入）。
> 性质：设计文档（待批准后派发实施）。本文档只读仓库、不改产品代码。

---

## 1. 现状梳理（代码事实，全部经只读核实）

### 1.1 Preset 解析链（#311 后已是 3 级 terminal）

`src/main/scala/nebflow/core/presets/PresetStore.scala`
- 解析优先级 `resolve()`（:152-180）：**1. 显式 preset**（agent.json `"preset"`）> **2. legacy model**（agent.json `"model"`）> **3. 默认 preset（TERMINAL）**。旧 level-4 全局链回退已删（类注释 :83-90）。
- `ensureDefaultPreset()`（:111）= `load()`：文件不存在/空/损坏时创建或修复；健康文件不写（幂等）。
- **种子源**：构造参数 `globalChainProvider` 默认 `readGlobalChainDefault()`（:98, :251-271）——**读 nebflow.json 的 `llm.model.default + fallbacks`**。即：preset 种子目前完全依赖 llm.model 存在。
- 种子产物 `initFromFile()`（:203-211）：单一 preset `general`，preferred=链头、fallbacks=链尾，description="默认方案：跟随全局模型链"。
- `repair()`（:225-241）：悬空/无链默认 → 指向首个有链 preset → 否则从全局链重播种。
- 解析调用点：agent 加载时 `AgentLibrary.scala:154`、实体层 `EntityTypes.scala:91`（都是 `PresetStore().resolve(j.preset, j.model)`）。

### 1.2 "配置完第一个 provider"的确切钩子点（任务 a 的落点）

**前端**（设置页与引导向导共用一条路径）：
1. 向导入口 `web/js/onboarding.js:120` → `openProviderWizard`（`web/js/sidebar.js:1270`）
2. **`saveNewProvider(name, data)` `sidebar.js:1247-1264`** — 持久化新 provider，并在 :1253-1261 **自动写 `llm.model.default = provider/首个模型`**（当 default 为空或指向不存在的 provider 时）
3. `flushConfigToServer()` `sidebar.js:1071-1075` → WS `updateConfig`

**后端**：
4. **WS `case "updateConfig"` `WebSocketRoutes.scala:2905-2930`** → `ConfigService.updateConfig`（`ConfigService.scala:107`，深合并落盘）
5. 保存成功后 **`PresetStore().ensureDefaultPreset()` `WebSocketRoutes.scala:2915-2921`**（#311 加的种子钩子）
6. 启动兜底：**`GatewayMain.scala:231-237`** boot 时同样调 `ensureDefaultPreset()`

结论：**钩子时机已存在且正确（保存时 + 启动时），本方案复用之；需要改的是它的种子源**（见 D-a）。

### 1.3 启动横幅（现状读 llm.model）

`GatewayMain.scala`
- :258 `isConfigured = config.llm.providers.nonEmpty`
- :259-269 计算 `contextWindow`：解析 `config.llm.model.default` → 查 provider 模型表 → 取 `contextWindow`
- :286 打印 `Context window: $contextWindow tokens (from ${config.llm.model.default})`
- 该 `contextWindow` 同时注入 `sharedResources.contextWindow`（:305），供压缩触发阈值（`protocol.scala:1066` 等）使用。

### 1.4 llm.model 的全部存活消费者（退役影响面）

| # | 消费者 | 位置 | 性质 |
|---|---|---|---|
| 1 | 启动横幅 contextWindow | `GatewayMain.scala:263,286` | live，需改（D-c） |
| 2 | `AgentLibrary.globalMaxTokens` | `AgentLibrary.scala:33-40`，**每次 LLM 请求都调**（`AgentCore.scala:453`） | live，需改（§3.2） |
| 3 | `AgentLibrary.globalContextWindow` | `AgentLibrary.scala:24-31` | **无任何调用方（死代码）**，删除 |
| 4 | `registry.getCandidates()` 全局链 | `llm/registry.scala:72-74`，经 `getCandidatesForAgent(None/空)`（:151-162）被 `LlmHandle.send/sendStream`（`interface.scala:243,357`）调用；**onboarding 探针 `probeLlm`（`OnboardingService.scala:122-135`）不带 agentModel，走的就是这条全局链** | live，需改（§3.3） |
| 5 | preset 种子源 | `PresetStore.scala:251-271` | SEED ONLY（#311 设计），本方案转为迁移优先源 |
| 6 | 配置校验/清理/改名改写 | `ConfigService.scala:84-101`（校验）、`:225-265`（scrubGlobalChain）、rename 改写 | 随字段退役删减 |
| 7 | CLI `nebflow model set <ref>` | `cli/ModelCommand.scala:74-83` 直接写 `{"llm":{"model":{"default":...}}}` | live 写入面，需改指向（§3.4） |
| 8 | 前端写/清理 | `sidebar.js:1253-1261`（首配自动写）、`:1027-1051`（删 provider 清链）、`:1054-1069`（改名改写） | 需删（§3.5） |

### 1.5 llm.model 的 schema

`llm/config.scala`
- `ModelChainConfig`（:63-86）：`default: String`（必填，容忍 legacy 别名 `primary`）+ `fallbacks`
- `ServiceLlmConfig`（:112-118）：`model: ModelChainConfig` **必填字段**
- `defaultServiceConfig`（:215-221）：无配置文件时的占位 default = `"anthropic/claude-sonnet-4-6"`

### 1.6 默认 preset 的 UI 可见性（任务 e 现状）

**已可看、可切，无需新建入口**：
- 设置页 preset 卡片 `sidebar.js:545-560` `renderPresetCard`：渲染默认徽标（`isDefault`）+ "设为默认" 按钮（:602-606）
- API 层 `web/js/presets.js:96-101` `setDefaultPreset` → `PUT /api/presets/default`（`RestApiRoutes.scala:1730-1739`）
- `GET /api/presets`（`RestApiRoutes.scala:1695-1708`）返回 `{defaultPreset, presets, agents}`
- 本机实况：`~/.nebflow/model-presets.json` 已有 general（默认）/Vision/LowCost 三 preset；nebflow.json 残留 `llm.model.default="107/glm-5.2-107"`。

### 1.7 provider 级 fallback（约束 3 涉及，与 preset 解析链正交）

请求内候选链构造：`LlmHandle.send/sendStream`（`interface.scala:243-248, 357-361`）= 会话级 override ++ `getCandidatesForAgent(agentModel)` → `healthMonitor.filterCandidates` → 按序尝试（失败换下一个，`fallback.scala`）。**本方案只改"全局链的来源"（llm.model → 默认 preset），候选排序、健康过滤、逐个尝试机制一字不动。**

---

## 2. 目标语义总览

![冷启动/迁移/解析链总览](/Users/dev/.nebflow/docs/Nebflow/assets/default-preset-semantics-flow.svg)

一句话：**"默认"只有一个来源——model-presets.json 的 defaultPreset。** 冷启动由首配 provider 自动生成；老配置的 llm.model 启动时迁移成 preset 后从 nebflow.json 删除；横幅、maxTokens、探针、CLI 全部改读默认 preset。

---

## 3. 设计决策点

### D-a 冷启动建 preset：复用 `ensureDefaultPreset()`，只换种子源

**决策**：钩子（`WebSocketRoutes.scala:2916` 保存时 + `GatewayMain.scala:236` 启动时）原样复用，**不新增钩子**；把 `PresetStore` 的默认种子源从"读 llm.model"改为：

```
seedChain = readGlobalChainDefault()  非空则用之（迁移优先，服务存量 nebflow.json）
           否则 readFromProviders()   首个含模型的 provider 的首个模型（单元素链）
```

- **命名**：沿用 `"general"`。理由：#311 已量产该名字（存量用户文件、`PresetStoreInvariantSpec` 断言都是 general），换名徒增一次无意义迁移。
- **内容**：**只含默认模型**（preferred=种子模型），**fallbacks 留空**。理由：
  1. 与现状行为等价——今天前端首配也只设 default 不设 fallbacks（`sidebar.js:1256-1261`），冷启动体验零变化；
  2. 自动把 provider 全部模型塞进 fallbacks = 重新制造"静默路由到用户未选择的模型"——正是 #311 事故的病灶，方向相反；
  3. fallback 是用户显式决策，preset 编辑器（已有 UI）就是为此存在。
- **description 更新**：现文案"默认方案：跟随全局模型链"引用了将死概念，改为"默认方案：初始配置时自动创建"（或类似），一次性顺带修正。
- **为何不调整而非重写 ensureDefaultPreset**：它已同时覆盖"首配保存时"与"boot 兜底"两个时机，且幂等/修复逻辑（load-repair）与种子源正交——只需把 `globalChainProvider` 默认实现（:98）换掉，测试注入接口不变（`PresetStoreSpec.scala:15` 的 `tempStore(suffix, globalChain)` 签名不动）。

### D-b llm.model.default 退役：一次性"迁移后删除"（推荐），decoder 保持容忍

**决策**：三件套：
1. **schema 容忍**：`ServiceLlmConfig.model` 改为 `Option[ModelChainConfig] = None`（`config.scala:112-118`），存量文件里的 llm.model 节可解析但被忽略；`defaultServiceConfig` 删掉占位 default。
2. **启动一次性迁移**（GatewayMain boot，紧邻现有 ensure 调用）：
   ```
   if nebflow.json 含 llm.model 节:
     ensureDefaultPreset()            // 种子源此时仍优先读 llm.model（D-a）
     if 默认 preset 可用（存在且有链）:
       从 nebflow.json 剥离 llm.model 节（原子写：tmp + rename）
       日志一行 "migrated llm.model → default preset 'general'; field removed"
     else: 跳过剥离，下次启动重试（幂等）
   ```
3. **前端/CLI 停写**（§3.4/§3.5），`ConfigService` 的 scrub/rewrite/validate llm.model 分支删除（preset 引用清理 `scrubPresetRefs`/`rewritePresetRefs` `ConfigService.scala:155-156` 已存在，保留）。

**推荐"删字段"而非"保留兼容读一轮"的理由**：
- #311 事故的根因就是"同一语义两处存放、一处陈旧"（llm.model=107/glm-5.2-107 vs 设置页 general/GLM-5.3）。保留 live 读取 = 保留温床；哪怕只作种子源，每次启动都会拿陈旧值与新 preset 并存，人读配置文件仍会困惑。
- 迁移是**信息转移**而非删除：链被搬进 general preset 后才剥字段，顺序保证不丢数据；任一步失败即中止剥离、下次重试。
- 已知代价（记录在案）：**降级回滚限制**——旧版本（< 本次改动）的 decoder 要求 `llm.model.default` 必填，若用户迁回旧版需手动补 `"model": {"default": "provider/model"}`。beta 期（v1.4.1-beta）可接受；文档/changelog 注明。
- 防误删：`readGlobalChainDefault` 读的是磁盘文件（`PresetStore.scala:252`）而非内存默认值，全新安装（无 nebflow.json）天然返回 Nil；再加一道"剥离仅当 llm.model 节实际存在且（providers 非空 或 preset 已健康）"防呆。

### D-c 启动横幅改读默认 preset

**决策**：`GatewayMain.scala:259-269` 的 contextWindow 计算改为——加载 `PresetStore`，取默认 preset 链**首个能解析到 provider 模型表的 ref**的 `contextWindow`（preferred 优先，逐个试 fallbacks；全失败 → `Defaults.ContextWindow` + warn 日志，保持现有容错风格）。:286 文案：

```
Context window: 200000 tokens (default preset "general": zhipu/GLM-5.3)
```

理由：横幅同时暴露 preset 名 + 实际模型 ref，与设置页 preset 卡片逐字可对上——#311 事故里"横幅说 107、设置页说 GLM-5.3"的对不上的问题从表达层根除。`sharedResources.contextWindow` 同源受益，无需另行改。

### D-d legacy model 级（解析链第 2 级）：保留，不随本次退役

**决策**：保留 `resolve()` 第 2 级（agent.json `"model"`，`PresetStore.scala:165-168`）。

理由：
1. 裁定打击的是**隐藏的全局默认**（用户看不见、不记得、与 UI 展示脱节）；legacy model 是 agent 本地显式配置，在 agent 编辑 UI 可见可改——不具同类危害。
2. 退役它 = 迁移全体用户的 agent.json（standalone/teams/flows 三层，`ConfigService.allAgentJsonFiles` 的范围），破坏面大、用户可见收益为零。
3. 迁移杠杆已存在：`PUT /agents/:name/preset`（`RestApiRoutes.scala:1496`）+ agent 详情页 preset 选择器，鼓励自然迁移即可。
4. 处置：代码注释标注 legacy、文档声明"推荐 preset 引用"，将来若要退役另开方案。

### D-e 默认 preset 的用户可见性：现状已够，不加入口（可选润色记为后续项）

**决策**：设置页已展示默认徽标 + 一键切换（§1.6 证据），冷启动自动生成的 general 会自然出现在该列表。**本次不新增 UI**。可选润色（后续项，不阻塞）：首配保存成功后 toast"已创建默认方案 general"；向导完成文案提一句默认 preset。agent 详情 API 的 `resolvedFrom`（`RestApiRoutes.scala:1430-1451`）已能区分 preset/legacy-model/default-preset，排查工具链现成。

---

## 4. 其余 llm.model 消费者归宿（§1.4 表逐项，必须一并改，否则"退役"是半吊子）

1. **`AgentLibrary.globalMaxTokens`（AgentCore.scala:453 每请求调用）**：改为默认 preset preferred 模型的 `maxTokens`（经 PresetStore resolve + provider 模型表，解析失败回 `Defaults.MaxTokens` 不抛异常——现状 `:30/:38` 会 throw，顺手加固）。`globalContextWindow` 无调用方，删除。
   - *标注既有债（不在本次范围）*：AgentCore:453 对**所有** agent 用同一个全局 maxTokens，而非各 agent 自己解析到的模型的 maxTokens——本次仅换数据源保持行为等价，按模型区分留作后续项。
2. **`registry.getCandidates()`（:72-74）**：全局链来源改为默认 preset（`PresetStore().resolve(None, None)` 的链），保留"链全空 → 首 provider 首模型"的既有兜底（:89-102）。效果：**onboarding 探针 `probeLlm` 自动跟随默认 preset**——首配后探针测的正是刚配置的模型，语义自洽。registry 引入 PresetStore 文件读（小文件、每次读新，成本可忽略，与 PresetStore 现有设计一致）。
3. **CLI `nebflow model set <ref>`（ModelCommand.scala:74-83）**：无 `-s` 分支从"写 llm.model.default"改为"设默认 preset 的 preferred"：GET /api/presets 取 defaultPreset 名 → PUT /api/presets/{name}（保 fallbacks 换 preferred）。语义对旧用户不变（"改全局默认模型"），落点变 preset。
4. **`ConfigService`**：validate 的 model 链校验段（:84-101）、`scrubGlobalChain`（:225-265）、provider rename 的 llm.model 改写，全部删除（字段已不存在，updateConfig 深合并本就不删未知键、迁移剥离在 boot 做）。
5. **前端 sidebar.js**：`saveNewProvider` 删 :1253-1261 的 llm.model 自动写（provider 落盘后后端自动建 preset，前端零参与）；`cleanModelChainForProvider`（:1027-1051）与 `renameProviderInModelChain`（:1054-1069）删除——preset 引用清理后端已有（ConfigService:155-156）；`saveNewProvider` 里的 `model:{default:''}` 初始化形状（:1248-1251）一并清理。

---

## 5. 改动清单（文件级）

| 文件 | 改动 |
|---|---|
| `src/main/scala/nebflow/core/presets/PresetStore.scala` | 种子源默认实现改为"llm.model 迁移优先，否则 providers 推导"；description 文案；类注释更新 |
| `src/main/scala/nebflow/llm/config.scala` | `ServiceLlmConfig.model` → `Option[ModelChainConfig] = None`；`defaultServiceConfig` 去占位 default |
| `src/main/scala/nebflow/gateway/GatewayMain.scala` | boot 迁移步骤（seed→verify→strip llm.model，:231-237 附近）；横幅块（:258-269, :286）改读默认 preset |
| `src/main/scala/nebflow/agent/AgentLibrary.scala` | `globalMaxTokens` 改读默认 preset 且容错；删 `globalContextWindow` |
| `src/main/scala/nebflow/llm/registry.scala` | `getCandidates()` 全局链来源 → 默认 preset 链（保留首 provider 兜底） |
| `src/main/scala/nebflow/cli/ModelCommand.scala` | `model set` 无 `-s` 分支 → 改默认 preset preferred |
| `src/main/scala/nebflow/service/ConfigService.scala` | 删 llm.model 校验/scrub/rename 改写段 |
| `src/main/resources/web/js/sidebar.js` | 删 llm.model 写入与清理三处（§4.5） |
| `src/test/scala/nebflow/core/presets/PresetStoreSpec.scala`、`PresetStoreInvariantSpec.scala` | 种子源新语义用例（providers 推导 / llm.model 迁移优先）；既有注入式用例签名不变 |
| 新增 spec（建议 `MigrationSpec` 或并入 InvariantSpec） | llm.model 迁移三态：无 preset 建后剥离 / 有 preset 直接剥离 / 剥离失败幂等重试 |
| `src/main/scala/nebflow/shared/AgentModelConfig.scala` | 注释更新（:16-17 仍宣称全局链兜底，已过时） |

---

## 6. 迁移策略（对现有用户）

| 用户态 | 行为 | 感知 |
|---|---|---|
| 已有健康 preset + llm.model 残留（本机即此态） | ensure 不触碰 preset 文件；剥离 llm.model + 一行日志 | 无感（配置文件少一个死字段） |
| 有 llm.model、无 model-presets.json（降级恢复/手删 preset） | 以 llm.model 链播种 general → 验证 → 剥离 | 默认模型选择保留 |
| 全新安装 | 无 llm.model；首配 provider 时以"首 provider 首模型"播种 general | 引导流程结束即有默认 preset |
| 剥离失败（IO 错误等） | 中止剥离，字段留存但 decoder 忽略；下次启动重试 | 无感 |
| 降级回滚到旧版本 | 需手动补 `"llm":{"model":{"default":"..."}}` | changelog 注明 |

顺序硬约束：**先播种验证、后剥离**；剥离用原子写（tmp + rename，同 PresetStore.save 风格）。

---

## 7. 验收条件（二值可自动化）

1. **冒烟测试（硬性，第一项）**：隔离 `NEBFLOW_HOME=/tmp/nf-smoke-$$` 启动真实服务（`sbt run` 或 release jar，带 `--port` 参数后台起）→ `curl -s "http://localhost:$PORT/?token=$TOKEN"` 断言 200 + HTML → `curl -s -H "Authorization: Bearer $TOKEN" http://localhost:$PORT/api/presets` 断言 200 且 JSON 含 `defaultPreset`。服务不可启动 = 其余全无效。
2. **全新安装首配 → preset 自动出现**：继续冒烟实例，经 WS 发送 `updateConfig`（等效 `saveNewProvider` 载荷：1 个 provider + 1 个模型，**载荷不含 llm.model**）→ `GET /api/presets` 断言：`defaultPreset == "general"`，general 的 `preferred == "provider/model"`，`fallbacks == []`。
3. **启动横幅不再引用 llm.model.default**：冒烟实例启动日志断言：不含字符串 `from 107/` 或 `(from `（旧文案签名）；含 `(default preset "general"`；contextWindow 数值 == 所配模型 `contextWindow` 字段。
4. **老配置迁移路径**：预制 nebflow.json（providers 含 107 + `llm.model.default="107/glm-5.2-107"`），无 model-presets.json → 启动 → 断言：(a) `GET /api/presets` 的 general.preferred == `"107/glm-5.2-107"`；(b) 磁盘 nebflow.json 中 `llm.model` 节已不存在；(c) 再次启动无迁移日志（幂等）。
5. **已有 preset 用户无感**：预制健康 model-presets.json（general+Vision）+ llm.model 残留 → 启动 → 断言：preset 文件内容逐字节不变（`diff` 或 hash）；llm.model 节被剥离。
6. **probe 链路**（e2e，本地 mock provider）：预置本地 OpenAI 协议 mock server 作为 provider → onboarding `probeLlm`（WS）断言 `ok==true` 且 `provider` 指向 mock——证明探针走默认 preset 候选而非全局链。
7. **CLI**：`nebflow model set zhipu/GLM-5.3` → `GET /api/presets` 断言默认 preset preferred 变为 `zhipu/GLM-5.3` 且 fallbacks 保留。
8. **编译 + 测试**：`sbt compile` 通过；`sbt test` 全绿（含改造后的 PresetStoreSpec/InvariantSpec 与新增迁移用例）。
9. **provider 级 fallback 不破坏**：既有 `fallback.scala`/registry 相关测试全绿即视为机制未动（本次零改动该文件的行为路径）。

---

## 8. 派发摘要（Manager 可直接引用）

- 决策定案：D-a 复用钩子换种子源（general / 仅 preferred / 空 fallbacks）；D-b Option 化 + 启动"播种→验证→剥离"；D-c 横幅 `(default preset "general": ref)`；D-d legacy 第 2 级保留；D-e UI 现状已够零改动。
- 涉及 8 个产品文件 + 2 个测试文件（§5 表）；核心顺序约束：迁移先播种后剥离、原子写。
- 建议拆分：后端（PresetStore/config/GatewayMain/AgentLibrary/registry/ConfigService）→ CLI+前端（ModelCommand/sidebar.js）→ 测试与验收脚本。
- 明确不做：legacy model 级退役、per-agent maxTokens 按模型区分（既有债，另立后续项）、默认 preset 切换 UI（已存在）。

*配图源文件：`assets/default-preset-flow.svg`（graphviz，dot 源在图注可复现：`/tmp/default-preset-flow.dot`）。*
