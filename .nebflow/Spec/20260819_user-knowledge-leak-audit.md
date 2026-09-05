# 用户知识泄漏审计 + "Context window (from 107)" 日志溯源

- 日期：2026-08-19 23:04
- 分支：`feat/cold-activate-fix`（含外部 agent 未提交修复，20 文件 +732/-483）
- 性质：只读审计，未修改任何产品代码
- 范围：`src/main/scala` 全量 + `~/.nebflow/` 状态文件 + 未提交 diff + git 历史

---

## 1. 结论速览

### 日志定性（一句话）

**`Context window: 1000000 tokens (from 107/glm-5.2-107)` 是正常配置读取（场景 a），不是代码残留**：`GatewayMain.scala:286` 启动横幅打印的是 `~/.nebflow/nebflow.json` 里 `llm.model.default = "107/glm-5.2-107"`（用户自己在设置 UI 选过的全局默认模型），1000000 来自同一文件 `providers["107"].models[glm-5.2-107].contextWindow`。删除 ColdStartRouter 不影响这条日志——两者无任何关联；只要全局默认模型还是 107，重启后它必然再次出现。

### 泄漏计数

| 级别 | 数量 | 说明 |
|------|-----|------|
| 功能性泄漏（发给 LLM 的 tool 描述） | **2** | DelegateTool / SubTaskTool 描述里的 "LowCost … free 107 gateway" |
| 注释泄漏（用户私有网关/型号/USTC 写进代码注释） | **8** | 107/USTC/glm-5.2-107 等 |
| 用户路径示例（`C:\Users\Kai`、`C:/Users/dev` 出现在注释） | **3** | paths.scala |
| 争议（公共厂商名硬编码为产品默认，非隐私但应配置化） | **2** | SttService 默认模型、deepseek 启发式 |
| 仓库卫生（src/main 之外） | **6** | 3 个未跟踪个人文件 + 2 条 commit message + HEAD 中的 ColdStartRouter |

**功能性泄漏 0 条存在于外部 agent 的未提交 diff**——该 diff 只在注释/测试里引用 107 记录事故，未引入新的功能性引用。

---

## 2. 日志传递链分析

### 2.1 完整链路

![107 日志传递链](/tmp/logchain.svg)

### 2.2 代码证据

1. **打印点**：`src/main/scala/nebflow/gateway/GatewayMain.scala:286`
   ```scala
   logger.info(s"Context window: $contextWindow tokens (from ${config.llm.model.default})")
   ```
2. **contextWindow 计算**：`GatewayMain.scala:259-269` — 把 `config.llm.model.default` 按 `providerId/modelId` 拆开，去 `config.llm.providers` 里查该模型的 `contextWindow`，查不到用 `Defaults.ContextWindow`。
3. **config 来源**：`llm/config.scala:189-198` `loadServiceConfig()` → `PathUtil.configJsonReadPath(~/.nebflow)`（`core/paths.scala:188-196`）→ **`~/.nebflow/nebflow.json`**。
4. **用户配置实测内容**（本机 `~/.nebflow/nebflow.json`，密钥已截断）：
   ```json
   "llm": {
     "model": { "default": "107/glm-5.2-107",
                "fallbacks": ["deepseek/deepseek-v4-flash", "zhipu/GLM-5.2"] },
     "providers": {
       "107": {
         "baseUrl": "https://llm.example.com/",
         "apiKey": "sk--z9O9…(截断)",
         "models": [ { "id": "glm-5.2-107", "contextWindow": 1000000, … } ]
       }
     }
   }
   ```
   → `default = 107/glm-5.2-107` + `contextWindow = 1000000`，与日志逐字吻合。

### 2.3 三种可能性的排除/证实

| 假设 | 判定 | 证据 |
|------|------|------|
| a. 正常配置读取 | **证实** | 值逐字来自 `~/.nebflow/nebflow.json`（用户运行时配置层，红线允许的位置）。`git log --all -S 'default = "107'` 与 `-S '"default" : "107'` 均为空——**历史上没有任何产品代码把 107 写进 `llm.model.default`**，该值是用户在设置 UI 配置 107 provider / 选默认模型时经 updateConfig 持久化的 |
| b. 代码残留 | **排除（对这条日志而言）** | 日志链路上没有任何硬编码 107。被删的 ColdStartRouter（HEAD 版）是**纯 per-request 路由器**，从不写配置文件；它的硬编码 BuiltinChain（`107/deepseek-v4-flash-ascend` 等）只影响闲置 agent 的唤醒请求，与启动横幅无关 |
| c. 状态文件残留 | **部分证实但属正常** | `~/.nebflow/nebflow.json` 确实存了 107，但它是**用户自己配置的数据**（provider 块 + 密钥 + 默认模型），正是红线指定的合法存放层。`models.json`（zhipu 能力缓存）、`usage-pattern.json`（无 107）、各 `agents/*/agent.json`（仅 design-engineer 有 `"preset": "Vision"`，无任何 agent 挂 LowCost）均无违规写入 |

### 2.4 为什么删了 ColdStartRouter 后 107 还会出现

两个独立的"107 来源"被合并在了同一次事故里：

1. **agent 实际用 107 跑请求**（用户感知的"为什么在用 107"）——根因是 `PresetStore` 解析链的第 4 级静默回退到全局链（nebflow.json `llm.model`）：preset UI 显示 general/GLM-5.3，实际请求却走了 `llm.model.default = 107/glm-5.2-107`。ColdStartRouter 叠加了第二重（闲置唤醒也改道 107）。外部 agent 未提交 diff 已同时处理：删 ColdStartRouter + `PresetStore` #311 把第 3 级 default preset 设为 terminal、移除第 4 级全局链回退。
2. **启动横幅打印 107**（22:57:09.404 这条）——只反映 `nebflow.json` 的全局默认模型，与 ColdStartRouter 从来无关。**修复 #311 落地后这条日志仍会打印 107/glm-5.2-107**，直到用户在设置里把全局默认模型改掉（或手工编辑 `llm.model.default`）。这是预期行为：横幅报告的就是全局链。

### 2.5 未提交 diff 检查（任务 A.4）

- `AgentLibrary.scala`：仅删除 `coldStartConfig` 访问器与 ColdStartConfig import——干净，无 107 引入。
- `llm/config.scala`：删除 `ColdStartConfig` 整个 case class（含 `DefaultPreset = "LowCost"`）——干净。
- `GatewayMain.scala`/`WebSocketRoutes.scala`：新增 `ensureDefaultPreset()`（#311 boot/首存种子），无 107 功能引用。
- diff 中 107 命中仅 3 处：`PresetStore.scala` 注释（事故记录）、`AgentCore.scala:446` 注释、新测试 `PresetStoreInvariantSpec` 用 `"107/glm-5.2-107"` 作 fixture（测试数据，可接受，建议改为中性假名如 `provider-x/model-y`）。

---

## 3. 审计清单

判定标准：**泄漏** = 用户私有信息（107 网关/USTC/私有型号/用户名路径）出现在产品代码；**争议** = 公共厂商信息但硬编码方式不当；**正常** = 合理工程实践。

### 3.1 功能性泄漏（LLM 可见，最高优先级）

| 文件:行号 | 内容 | 判定 | 建议 |
|-----------|------|------|------|
| `core/tools/DelegateTool.scala:153` | tool description: `"e.g. 'LowCost' for the free 107 gateway … Use 'LowCost' when …"` | **泄漏** | 该描述随每次 delegate 调用发给 LLM，对所有 OSS 用户可见，但 'LowCost'/107 是本机用户私有 preset。改为通用措辞："a preset name from your model-presets.json, e.g. a low-cost or vision preset"，不举具体名 |
| `core/tools/SubTaskTool.scala:88` | 同上（SubTask 版） | **泄漏** | 同上 |

### 3.2 注释泄漏（进入开源仓库的源码文本）

| 文件:行号 | 内容 | 判定 | 建议 |
|-----------|------|------|------|
| `core/tools/PresetResolver.scala:13` | `(e.g. LowCost → 107 free gateway)` | **泄漏** | 改为 `(e.g. a low-cost preset)` |
| `core/UsageRecordStore.scala:26` | `// provider id, e.g. "107", "deepseek"` | **泄漏** | 示例改 `"openai"` 等通用 id |
| `core/UsageRecordStore.scala:27` | `// model id, e.g. "glm-5.2-107"` | **泄漏** | 改 `"gpt-4o"` 等 |
| `core/presets/PresetStore.scala:85-86` | 事故注释 `global default was 107/glm-5.2-107 …` | **泄漏（轻）** | 事故文档应放 docs/，源码注释改中性："global default was a stale model the user no longer preferred (#311)" |
| `agent/AgentCore.scala:446` | `把闲置唤醒…改道到 LowCost(107)` | **泄漏（轻）** | 去掉 "(107)"，保留机制描述 |
| `agent/AgentCore.scala:1072` | `default model (107/deepseek) turns into…` | **泄漏（轻）** | 改 "(a weak default model)" |
| `llm/registry.scala:117` | `(e.g. USTC and deepseek both have deepseek-v4-pro)` | **泄漏（轻）** | USTC 是用户私有网关。改 "(e.g. two providers both expose the same model id)" |
| `llm/interface.scala:186` | `session resumption clashes with the USTC gateway reverse proxy` | **泄漏（轻）** | 改 "a campus reverse-proxy gateway"或"some reverse proxies"；行为结论保留 |

### 3.3 用户路径示例（注释）

| 文件:行号 | 内容 | 判定 | 建议 |
|-----------|------|------|------|
| `core/paths.scala:31` | `C:\Users\Kai/Desktop` | **泄漏（轻）** | 用户名示例改 `C:\Users\name/Desktop` |
| `core/paths.scala:44-45` | `C:\Users\Kai\Desktop` | **泄漏（轻）** | 同上 |
| `core/paths.scala:65` | `C:/Users/dev/x` | **泄漏（轻）** | 改 `C:/users/example/x` |

### 3.4 争议项（公共厂商，非隐私泄漏）

| 文件:行号 | 内容 | 判定 | 说明 |
|-----------|------|------|------|
| `gateway/SttService.scala:26` | `.getOrElse("glm-asr-2512")` | **争议** | 公共商业模型名硬编码为产品默认 STT 模型；应来自 stt-config.json 默认或常量表，不该埋在解析 fallback 里 |
| `llm/registry.scala:55` | `providerId == "deepseek"` 启发式 | **争议** | 公共厂商 id 硬编码推断 thinkingPassback；用户若把 provider 命名为其他名字则失效——设计脆弱而非泄漏 |
| 多处（HealthMonitor/OpenAiAdapter/AnthropicAdapter/ToolInputJson/Defaults/interface 等） | GLM/DeepSeek/Zhipu/Kimi 行为注释 | **正常** | 公开厂商的互操作 quirks 记录，标准工程实践 |
| `llm/config.scala:219` | 默认 `anthropic/claude-sonnet-4-6` | **正常** | 通用公共默认 |
| `gateway/WebSocketRoutes.scala:2628` | `api.github.com/repos/MashiroKai/Nebflow` | **正常** | 本项目自己的 release 检查地址 |
| `src/main` 全量密钥扫描 | 无 `sk-` 真实密钥、无 `Bearer <token>`、无 `apiKey = "…"` | **正常** | "sk-"命中均为 "task-specific" 类词内巧合 |

### 3.5 仓库卫生（src/main 之外）

| 位置 | 内容 | 判定 | 建议 |
|------|------|------|------|
| 仓根未跟踪文件 | `received-from-kai.txt`、`test-transfer.txt`（传输测试）、`不要`（9.6KB 个人文件） | **风险** | 移出仓库或加入 .gitignore，防误提交 |
| git 历史 `ccd76aa8` | commit message 含 `107 free gateway`、`'107 免费多用'` | **泄漏（历史）** | 历史改写代价大；开源前可考虑 squash/filter-repo，或在发布说明中声明历史含个人配置描述 |
| git 历史 `38af6554` | `on the default model (107/glm-5.2-107)` | **泄漏（历史）** | 同上 |
| HEAD 中 `ColdStartRouter.scala` | `BuiltinChain = 107/deepseek-v4-flash-ascend…`、注释含 "107 免费多用" | **泄漏（HEAD）** | 工作区已删，**尽快提交删除**即可离开 tip（历史仍在） |

### 3.6 状态文件核对（任务 A.3）

| 文件 | 107 内容 | 定性 |
|------|---------|------|
| `~/.nebflow/nebflow.json` | provider "107" + 默认模型 + 密钥 | **正常**——用户运行时配置层，红线指定的合法位置 |
| `~/.nebflow/model-presets.json` | LowCost preset → `107/glm-5.2-107` | **正常**——用户自建 preset（contextWindow 1000000 与 nebflow.json provider 定义一致，链路自洽） |
| `~/.nebflow/models.json` | 仅 `zhipu/GLM-5V-Turbo` 能力缓存 | 正常 |
| `~/.nebflow/usage-pattern.json` | 无 107 | 正常 |
| `~/.nebflow/agents/*/agent.json` | 无任何 agent 挂 LowCost/107（仅 design-engineer 挂 Vision） | 正常——#311 修复后无 agent 会因显式 preset 走 107 |

---

## 4. 修复方案（只列不实施）

### 4.1 用户侧动作（无需改码）

1. **想让启动横幅不再显示 107**：设置 UI 把全局默认模型改为 `zhipu/GLM-5.3`（或直接编辑 `nebflow.json` 的 `llm.model.default`）。这是横幅的唯一来源。
2. **确认外部 agent 修复生效**：提交当前工作区（删除 ColdStartRouter + #311），重启后 agent 请求应跟随 default preset（general → zhipu/GLM-5.3），仅横幅仍按全局链打印。

### 4.2 代码侧待办（按优先级）

| 优先级 | 动作 | 位置 |
|-------|------|------|
| P0 | tool description 去用户化：删 "LowCost … free 107 gateway" 示例，改通用措辞 | `DelegateTool.scala:153`、`SubTaskTool.scala:88` |
| P1 | 提交 ColdStartRouter 删除（让 tip 干净） | 工作区 `D ColdStartRouter.scala` |
| P1 | 注释去用户化（8 处，见 §3.2） | PresetResolver / UsageRecordStore×2 / PresetStore / AgentCore×2 / registry / interface |
| P2 | 路径示例改名（3 处） | `paths.scala:31,44-45,65` |
| P2 | STT 默认模型配置化 | `SttService.scala:26` |
| P3 | 清理仓根个人文件 + 评估 git 历史处理 | `received-from-kai.txt` 等 3 个 |
| P3 | 新测试 fixture 改中性假名 | `PresetStoreInvariantSpec`（未提交） |

### 4.3 防回归建议

- CI 加一条 grep 门禁：`src/main` 禁止出现 `107/`、`ustc`、`kaiyu`、`sk-[A-Za-z0-9]{8}` 等模式（白名单豁免测试）。
- Tool description 类 LLM 可见文案统一从"用户可配置项"生成（如读取 model-presets.json 的 preset 名动态拼示例），从根上杜绝把某台机器的私有配置写进通用提示词。

---

## 附：审计方法备忘

- 日志链路：Read + Grep 逐层追（打印点 → config 加载 → 文件路径解析 → 实配文件）。
- "是否代码写入 107"：`git log --all -S` 双模式搜索（Scala 源 `default = "107` 与 JSON `"default" : "107`）均零命中。
- 全仓扫描：词边界 + 引号包裹的 `107`/`107/`、`ustc`、`glm-`、`deepseek`、`/Users/dev`、`C:\Users\Kai`、`sk-`、`Bearer `、设备名；纯数字巧合（行号/端口/字面量 107）已人工剔除。
