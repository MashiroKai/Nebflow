# qa-frontend Vision 能力实测核实报告

| 字段 | 值 |
|---|---|
| 日期 | 2026-08-23 |
| 执行 | cache-engineer（Nebula 排队件，纯分析+只读探测） |
| 任务 | qa-frontend vision 能力实测：日志取证 + 活体探测 + 方案建议 |
| 约束遵守 | 未改 nebflow.json / model-presets.json / 任何代码 ✅ |

## 0 · 一句话结论

**Vision preset 链首 qwen3.8-max 实测具备 vision 能力（双源铁证）——「qa-frontend 无 vision」的反馈与模型无关**；真正的问题是 fallback 第三档 GLM-5V-Turbo **当前套餐无权限（429）**，以及 qa-frontend 历史上（08-20 前）走的是无 vision 的 deepseek-v4-flash 旧链。无需换模型，建议轻量清理 fallback 链后让 slideblocks 复测。

## 1 · 三模型实测行为表

| 模型 | 日志取证（1309 带图请求配对样本） | 活体探测（64×64 纯红 PNG 直连） | 真实 vision | 配置标记 | 判定 |
|---|---|---|---|---|---|
| qwen/qwen3.8-max | 752 样本；html-builder 真实描述截图布局（「S17 卡片与权限条间大空白带；S25 下半页空」） | 200，回复 **"Red"** | ✅ 有 | true | 标记正确 |
| kimi/k3-256k | 309 样本；visual-reviewer 真实视觉审查（「V05 layout OK but Step 1 text renders garbled」「回边 clear of green bar」） | 200，回复 **"Red"** | ✅ 有 | true | 标记正确 |
| zhipu/GLM-5V-Turbo | **零样本**（三天从未被触发——qwen/kimi 一直可用，轮不到第三档） | **429：「当前订阅套餐暂未开放GLM-5V-Turbo权限」** | ❓ 无法测 | false | 标记颠倒嫌疑**已无法证实**——比标记更硬的约束是无权限：模型当前根本调不通 |
| deepseek/deepseek-v4-flash（参照） | 13 样本全部弃图（「The image didn't render in this context — I'll verify via a DOM probe」）——实为**网关剥图**：vision=absent 按 false 处理，PreSendChecker 将图替换为 `[image omitted: model does not support vision]`（interface.scala:306-314） | 直连（绕过网关）后图仍被 **deepseek 服务端静默丢弃**：thinking 自述 "user says unsupported image; no imag…"，input_tokens=100（图未进输入），回复 "unknown" | ❌ 无 | absent | 标记合理（保守正确），勿改 true |

## 2 · 「qa-frontend 无 vision」反馈的根因链

1. **qa-frontend 请求模型时间线**（日志 233 条 request）：08-20 走 deepseek-v4-flash 旧链 94 条（无 vision + 网关剥图）+ qwen 28 条；**08-21 起 Vision preset 生效，纯 qwen3.8-max 110 条**——链路已正确且具备 vision
2. **qa-frontend 三天零带图请求**：233 条请求无一含图；实际收图的视觉工作落在 html-deck-studio 的 visual-reviewer（149 带图，qwen 链，工作正常）与 nebflow-project 的 Frontend（399 qwen + 308 kimi 带图）
3. slideblocks Manager 的反馈时间点若在 08-20（deepseek 链时期）则为真实经验；若之后则为认知未更新。**存在一个未闭环 gap**：若 slideblocks 称曾发图给 qa-frontend，图未到达其 LLM 请求（零带图）——那将是 Mail 链路丢图问题，建议复测确认

## 3 · 机制链条（取证过程沉淀）

- 网关 vision 判定：`effectiveVision = candidate.vision && runtimeVision`（B3 三态，absent 按 false）→ false 且带图时 stripImages 剥图换占位文本（interface.scala:161-171, 306-314）
- 日志取证路径：full.jsonl 为引用去重格式（system_ref/message_refs → objects/ 目录）——162 个 base64 PNG 图对象反查 1309 个带图请求，按时间序配对 response.full
- api_type 装饰字段果然不可信：请求实际路由以 message 对象形态与响应内容为准
- 08-20 13:24-13:30 html-builder 样本中的「Tool not available」风暴是当时已知的 qwen 空碎片事故（5e109ef8 已修），与 vision 无关

## 4 · 方案建议（报 Nebula 批）

### 倾向：方向① 轻量修正 + 复测（不建议直接上方向②）

**理由**：链首 qwen3.8-max 实测有 vision，Vision preset 主链已具备能力；visual-reviewer（html-deck-studio）同为 Vision preset 且实测工作正常——「该 preset 链首无图」的前提不成立，方向②（加角色）在未验证现链的情况下属于重复建设。

**具体修正清单**（批准后改，本次未动）：

| # | 改动 | 理由 |
|---|---|---|
| 1 | Vision preset fallbacks `["kimi/k3-256k","zhipu/GLM-5V-Turbo"]` → **`["kimi/k3-256k"]`** | GLM-5V-Turbo 当前套餐 429 无权限，永远调不通；双 vision 档（qwen→kimi）冗余已足 |
| 2 | GLM-5V-Turbo vision:false 标记**暂不修正** | 无权限下改标记无意义；未来套餐开通时一并改 true（5V 系列是视觉模型）|
| 3 | deepseek-v4-flash vision 保持 absent | 直连实测服务端丢图；标 true 会引入幻觉风险（参照 GLM-5.3 幻觉先例） |
| 4 | slideblocks 用 qa-frontend 现链（qwen3.8-max）**复测一次带图验收** | 08-21 起链路正确但零带图——复测同时验证 Mail 链路是否丢图（gap 闭环） |
| 5 | （复测通过后）反馈闭环给 slideblocks Manager | 认知更新 |

**方向② 的触发条件**（记录备用）：若 #4 复测仍失败且根因在 qa-frontend 侧无法快速修复，再评估加 visual-reviewer 角色——届时直接参照 html-deck-studio 的配置（Vision preset 实测可用的同款）。

## 5 · 附：探测方法

- 1x1 PNG 判定力不足（deepseek 回复 unknown 无法定性），升级 64×64 纯红 PNG 后对照组（qwen/kimi 答 "Red"）与盲区（deepseek 空文本）立即分层——**视觉探测最小用例建议 64×64 起步**
- deepseek 空 text 回复系 thinking 耗尽小 max_tokens 所致，max_tokens=2048 后 thinking 暴露真实输入（"user says unsupported image"）——探测 thinking 模型需给足预算
