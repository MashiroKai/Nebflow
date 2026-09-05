> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Onboarding 重新引导 交互规格书（v1.0 可实施终稿）

| 字段 | 值 |
|---|---|
| 状态 | draft v1.0 · 待用户确认冻结（任务 #307 续写至可实施终稿） |
| 作者 | design-engineer |
| 日期 | 2026-08-20 |
| 用户裁定 | 2026-08-18 20:07（旧「固定代码/固定问题」设计做错，重定向四要素：① 模拟 LLM 流式引导 ② 心理测评式 dependsOn 问题树 ③ 回答真实落盘 ④ 动画克制专业） |
| 实现约束 | 只设计不写代码；本文档为实现唯一依据 |
| 关键契约 | **preset 语义重构 @ecaf0fcc（#339）**：`llm.model.default` 已退役，首配 provider 后端自动创建 `general` 默认 preset——本文档**不出现任何 llm.model 写入** |

## 版本日志

- v1.0（2026-08-20）：任务 #307 续写骨架至可实施终稿。新增 §4–§13；修正 §3.2/§3.3/§3.4 与 #339 preset 重构对齐（Q2 服务商改为国产厂商为主 + 不确定分支，映射表移除 llm.model 写入）；§2 补充不确定分支两段式；配图 `assets/onboarding-question-tree.svg` 重新生成。

## 检索记录

> ⚠ 2026-08-18 20:12-20:13 在线检索受限：Sogou/DuckDuckGo 对本机 IP 触发验证码（同 askuser-canvas-integration-spec §13 已记录的教训）。以下范式条目均给出**官方/权威来源 URL**，内容基于既有成文规范知识与产品行为观察；未能抓取正文处显式标注，不静默当作已核实引用。服务商预设值（baseUrl/模型名）为 v1.0 快照，实施前按本文档 §13 官方链接复核一次。

## 0 一句话目标

首次启动（未配置 LLM）不再弹模态向导、不依赖任何真实 LLM——Nebula 以**前端模拟的流式回复**在聊天主区打招呼，随即用心理测评式 dependsOn 问题卡逐层引导；用户关于 provider / baseUrl / apiKey / 模型的回答**真实写入 nebflow.json**（经既有 `saveNewProvider` 通道，preset 由后端自动创建），并经既有 probeLlm 硬门探活后才标记 `done`，配置完成即可真实使用。

## 1 参考与依据

| # | 范式 | 来源 | 提炼规则 | Nebflow 借鉴点 |
|---|---|---|---|---|
| P1 | Typeform / 心理测评问卷的分支逻辑（Logic Jump） | https://www.typeform.com/help/a/logic-jumps-conditional-branching-360051711554/ ⚠未能抓取正文，基于产品行为观察 | 一次只呈现当前可达问题；前一答案决定后序问题；已答项可回读不回改 | 一张 AskUserQuestion 卡内用 dependsOn 逐层揭示（showOptions 既有能力），不一次全抛、不多卡轰炸 |
| P2 | Slack 首次运行向导（工作区创建分步） | https://slack.com/help/articles/212675257-Create-a-Slack-workspace ⚠产品行为观察 | 每步单一职责；随时可退出；进度感来自「问题逐层出现」而非进度条 | 每题只问一件事；退出（pending）与跳过（skipped）语义分离 |
| P3 | Notion onboarding（角色/用途选择题个性化） | https://www.notion.com/help ⚠产品行为观察 | 开局用低门槛选择题（非表单）建立对话感；答案即时影响后续内容 | 首问是选择题「你想怎么开始？」而非配置表单；配置项藏在分支后 |
| P4 | CLI/平台配置向导（回答问题 → 真实写配置文件） | Vercel CLI 通例：https://vercel.com/docs/cli ⚠ | 向导答案即配置真值；写盘前校验；写盘后验证（真实调用）；失败归因到具体字段 | answers → nebflow.json 直写（§5）；probeLlm 真实探活为收口验证，失败归因 auth/model/endpoint（OnboardingService.probeFailure 既有） |
| P5 | 会话式 onboarding 的打字机模拟（Intercom bot / Replika 类产品通例） | https://www.intercom.com ⚠产品行为观察 | 打字机节奏建立「在对话」的心理模型；**必须可跳过**；节奏模拟真实流式（标点停顿） | §4 流式模拟：同构真实 AI 气泡 DOM + 可跳过 + reduced-motion 瞬时渲染 |
| P6 | Apple HIG · Onboarding | https://developer.apple.com/design/human-interface-guidelines/onboarding ⚠JS-gated 未抓正文 | 引导应简短、可选、聚焦价值；避免一开机就要权限/表单 | 模态向导废弃，引导搬进聊天主区；「先看看」「跳过」始终在场 |
| P7 | WAI-ARIA APG · Radio Group | https://www.w3.org/WAI/ARIA/apg/patterns/radio/ | 单选语义、方向键、选中态非颜色唯一编码 | §10 无障碍直接依据（沿用既有 .option-btn 语义） |
| P8 | Nebflow visual-style 铁律（最高优先级） | `~/.nebflow/skills/nebflow/visual-style/SKILL.md` | 玻璃控件统一、字重方案 B、克制专业感、不造新轮子 | 模拟气泡/问题卡全部复用既有类与 token；零新视觉体系（§8） |
| P9 | AskUser×Canvas 方向 C（内部规格） | `~/.nebflow/docs/Nebflow/askuser-canvas-integration-spec.md`（draft v1.0，用户 20:07 确认） | 选项 preview + Canvas 内 `_nfAskAnswer` 直答；canvas 字段自动 Pop | onboarding 与方向 C 共享 showOptions 通道；v1.0 零 preview 消费（§6 声明依赖与降级） |
| P10 | DeepSeek 官方 API 文档（baseUrl/模型） | https://api-docs.deepseek.com/ ⚠未能抓取正文 | base_url 默认 `https://api.deepseek.com`，兼容 `/v1`；模型 `deepseek-chat`/`deepseek-reasoner` | §3.3 预设表 deepseek 行 |
| P11 | 智谱开放平台 / 月之暗面 Moonshot 文档（baseUrl） | https://open.bigmodel.cn/dev/api ⚠ · https://platform.moonshot.cn/docs/ ⚠ | OpenAI 兼容端点；模型 glm 系列 / moonshot-v1 系列 | §3.3 预设表 zhipu/kimi 行 |

**冲突取舍**：P1 Typeform 的「全屏一题一屏」与 P8「聊天内克制」冲突 → 采用聊天内单卡 dependsOn 逐层揭示（视觉零新载体，逐层感由揭示动画承担，§7 A-3）。P5 打字机的「拟人表演欲」与 P8 克制基调冲突 → 打字节奏收敛（30–45ms/字，无表情无拟人语气词），可一键跳过。

## 2 用户旅程

```
首次启动（configData: configured=false, onboarding ∈ {null, pending}）
  │
  ├─ S1  Nebula 模拟流式打招呼（聊天主区 AI 气泡，打字机，可跳过）
  ├─ S2  主卡 Q1「你想怎么开始？」
  │        ├─ 配置模型 ──→ Q2 服务商 ──→ 副卡 C（配置细节）→ 摘要确认 → 落盘 → probeLlm
  │        │               → ok: setOnboardingState(done) → 完成页（provider + general preset）→ 真实使用
  │        │               → fail: 归因错误行 → 返回修改（重开副卡 C）或退出
  │        ├─ Q2=不确定 ──→ U1「有 API 地址吗？」→ 有 → 副卡 A（兼容填写）
  │        │               → 没有 → 副卡 B「本地免费够用吗？」→ 够 → 副卡 C（ollama 预设注入）
  │        │               → 不够 → 告别语（pending，引导去设置）
  │        ├─ 先看看 ────→ 告别语（模拟流式）→ 状态保持 pending（下次启动再出现，不打扰）
  │        └─ 跳过引导 ──→ setOnboardingState(skipped)（服务端豁免 probe 门槛）
  │
回归用户（configured=true, 无 onboarding 标记）
  └─ 保留轻量提示气泡（returnTitle/greet/noThanks）；
     「打个招呼」改为模拟流式自我介绍 + 偏好问题卡，**移除 probe 前置门槛**（决策 D2）

重放：设置里「重新运行新手引导」→ setOnboardingState(pending) → 下次启动重走；
/onboarding 斜杠命令保留（重放固定招呼语入口，行为同首次）。
```

**设计决策记录**

- **D1**：模态向导（`.onboarding-overlay` welcome/provider/done 三步）**废弃**。引导载体从「盖住界面的弹窗」改为「聊天主区的一段对话」——这本身就是 onboarding 要传达的产品形态（P6：聚焦价值）。overlay CSS 类与回归用户轻量提示若保留也改为气泡形态，首启向导不再使用。
- **D2**：模拟打招呼不依赖 LLM（用户 20:07 裁定），因此回归用户提示里的 probe 前置门槛同步移除——模拟不需要探活。服务端 `setOnboardingState(done)` 的 probeOkAt 硬门**保留不动**（真实配置完成仍须探活，防绕过语义不变）。
- **D3**：主路径一卡到底：主卡 Q1–Q2–U1 同一张卡 dependsOn 逐层揭示，不是多卡堆叠（P1 取舍）。**例外**：不确定分支与配置细节（baseUrl/apiKey/model）放独立副卡——showOptions 的 dependsOn 只支持单条件（ref+equals，chat.js L1455-1464），「Q2=不确定 且 U1=有地址」这类多条件组合在单卡内不可表达，两段式是能力边界内的最简实现（§3.2）。
- **D4**：配置字段收集不走 `openProviderWizard`（既有 provider modal），由问题卡直接收集后调 `saveNewProvider(name, data)` 落盘——问题卡与 modal 是两套入口，但**写盘路径唯一**（复用 sidebar.js `saveNewProvider` → `flushConfigToServer` → `updateConfig` WS 帧，§5）。
- **D5**：中断恢复策略 = **从 S1 重新开始，不续档**。进行中状态不写服务端（onboarding 保持 pending/null），关窗即对话结束；无「配置一半」的脏状态，重启后重新打招呼 + 问题卡从 Q1 起。理由：模拟对话的语义是「一段对话」，且问题卡是最后一步一次性确认，答一半无落盘意义（§3.6 状态机 S3）。

## 3 问题树全图

![问题树](assets/onboarding-question-tree.svg)

### 3.1 首屏打招呼文案（模拟流式，固定文案，不靠 LLM 自由发挥）

| 段 | i18n key | 中文文案（zh-CN） | 流式节奏 |
|---|---|---|---|
| G1 | `onboarding.sim.greet1` | 你好，我是 Nebula——你的本地 AI 工作台助手。 | 常速 |
| G2 | `onboarding.sim.greet2` | 现在还没有配置模型，所以我先用一段「预录」的话跟你打招呼。配置完成后，我就会真正活过来。 | 常速 |
| G3 | `onboarding.sim.greet3` | 只需要回答我几个问题，一分钟就能完成。 | 常速 |

三句连播为一个气泡内的三个段落（段间停顿 400ms）；**必须诚实声明自己是模拟**（G2 明示「预录」）——克制专业感不允许假装真 AI 被拆穿的尴尬。

告别语（完成 / 稍后再说 / 不确定-云端两条）：

| 场景 | i18n key | 文案 |
|---|---|---|
| 配置完成 | `onboarding.sim.done` | 连接验证成功，我已经就位。试试对我说点什么吧。 |
| 先看看 | `onboarding.sim.later` | 没问题，随时在设置里点「重新运行新手引导」就能找到我。 |
| 回归用户 | `onboarding.sim.returnGreet` | 欢迎回来。我是 Nebula，简单了解几个问题，好让我更顺手。 |
| 不确定-推荐云端 | `onboarding.sim.tryCloud` | 好的，建议先注册一家服务商（如智谱 GLM 或 DeepSeek）拿到 API Key，再来完成配置。我会等你。 |

### 3.2 问题树定义（3 卡 11 问：主卡 3 + 副卡 C 3 + 副卡 A 4 + 副卡 B 1）

**卡 1 · 主卡**（首问「你想怎么开始？」，dependsOn 逐层揭示，confirm 后才锁定）

| ID | i18n key | 问题文案 | 类型 | 选项（label → i18n key） | dependsOn | 说明 |
|---|---|---|---|---|---|---|
| Q1 | `onboarding.q.start` | 你想怎么开始？ | 单选 | 配置模型（推荐）→ `onboarding.q.start.config` · 先看看 → `onboarding.q.start.browse` · 跳过引导 → `onboarding.q.start.skip` | — | 首问是选择题不是表单（P3） |
| Q2 | `onboarding.q.provider` | 你打算用哪家 LLM 服务？ | 单选 | 智谱 GLM → `onboarding.q.provider.zhipu` · DeepSeek → `onboarding.q.provider.deepseek` · Kimi（月之暗面）→ `onboarding.q.provider.kimi` · OpenAI 兼容（自定义）→ `onboarding.q.provider.compat` · 中转站 → `onboarding.q.provider.relay` · 本地模型（免费）→ `onboarding.q.provider.local` · 不确定，帮我判断 → `onboarding.q.provider.unsure` | Q1=配置模型 | 每项 desc 一句话说明；预设表见 §3.3 |
| U1 | `onboarding.q.unsureUrl` | 你手上有服务商提供的 API 地址（Base URL）吗？ | 单选 | 有 → `onboarding.q.unsureUrl.yes` · 没有 → `onboarding.q.unsureUrl.no` | Q2=不确定 | 只有 Q2=不确定 时出现；答完主卡可确认 |

主卡确认（confirm 按钮，doneLabel=`onboarding.btn.continue`）分派（onConfirm 回调按 answers 路由）：
- Q1=先看看 → 告别语 `sim.later`，状态保持 `pending`（分支终止）
- Q1=跳过引导 → `setOnboardingState('skipped')`，无告别语（分支终止）
- Q2∈{zhipu, deepseek, kimi, compat, relay, local} → 打开**副卡 C**（intent 按 Q2 解析，§3.4）
- Q2=不确定 且 U1=有 → 打开**副卡 A**；U1=没有 → 打开**副卡 B**

**卡 2 · 副卡 C（配置细节卡，主路径共用）**

| ID | i18n key | 问题文案 | 类型 | 选项 | dependsOn | 说明 |
|---|---|---|---|---|---|---|
| C1 | `onboarding.q.baseurl` | API 地址（Base URL） | 单选+Other | 预设值（按 intent 注入，见 §3.3）+ Other 自定义输入 | —（本卡独立） | 预设值即推荐项，Other 承接自建网关；答案 trim，无尾斜杠则补 `/`（与 showProviderModal 行为一致，sidebar.js L1330-1333） |
| C2 | `onboarding.q.apikey` | API Key | Other 自由输入 | 无预设选项；仅 intent=local 时提供「无需 Key」选项 → `onboarding.q.apikey.none` | C1 已答 | 输入即脱敏显示（type=password 语义，§10） |
| C3 | `onboarding.q.model` | 默认模型 | 单选+Other | 预设模型 2–3 个（按 intent 注入）+ Other | C2 已答（或选「无需 Key」） | 答案写入 models[0]；**不写 llm.model**（#339，§5） |

副卡 C 确认（doneLabel=`onboarding.btn.summary`）→ 渲染**摘要确认气泡**（非 showOptions）：行内回读 provider 名 / baseUrl / apiKey（掩码 `sk-…•••`）/ 模型，两个玻璃按钮 [确认并测试 → `onboarding.btn.testAndSave` / 返回修改 → `onboarding.btn.back`]。「返回修改」→ 重开副卡 C（旧卡保留在聊天流中作为记录）；「确认并测试」→ §5 落盘链路。

**卡 3 · 副卡 A（不确定→有地址，兼容填写卡）**

| ID | i18n key | 问题文案 | 类型 | 选项 | dependsOn | 说明 |
|---|---|---|---|---|---|---|
| A1 | `onboarding.q.relayBase` | 服务商给你的 API 地址 | Other 自由输入（必填） | 无预设 | — | 引导填 baseUrl 的核心场景 |
| A2 | `onboarding.q.providerName` | 给这个服务商起个名字（可选） | Other | 默认值 `custom` | A1 已答 | 无空格（与 provider modal 校验一致，sidebar.js L1329）；留空用 `custom` |
| A3 | `onboarding.q.apikey` | API Key | Other 自由输入（必填） | 无预设 | A2 已答 | 脱敏显示 |
| A4 | `onboarding.q.model` | 默认模型 | Other 自由输入（必填） | 无预设 | A3 已答 | 写 models[0]；protocol 固定 `openai` |

副卡 A 确认 → 摘要气泡（同上）→ 落盘。

**卡 4 · 副卡 B（不确定→没有地址，本地/云端引导卡）**

| ID | i18n key | 问题文案 | 类型 | 选项 | dependsOn | 说明 |
|---|---|---|---|---|---|---|
| B1 | `onboarding.q.localEnough` | 要不要先试试免费的本地模型？ | 单选 | 本地免费（推荐）→ `onboarding.q.localEnough.yes` · 我倾向云端服务 → `onboarding.q.localEnough.no` | — | 本地 = Ollama 预设注入（§3.3），无需 Key |

- B1=本地免费 → 打开**副卡 C**（intent=local，C2 出现「无需 Key」）→ 摘要 → 落盘
- B1=云端服务 → 告别语 `sim.tryCloud`，状态保持 `pending`（分支终止，下次启动再引导）

**分支终止汇总**：Q1=先看看 / Q1=跳过 / B1=云端 三个终止出口；其余全部收敛到副卡 C 或 A → 摘要 → 落盘。

### 3.3 服务商预设表（前端内置常量，随 intent 注入 C1/C2/C3）

| intent | provider name（写 nebflow.json 的 key） | baseUrl 预设（C1 推荐项） | protocol | apiKey（C2） | 模型预设（C3，v1.0 快照） |
|---|---|---|---|---|---|
| zhipu | `zhipu` | `https://open.bigmodel.cn/api/paas/v4/` | openai | 必填 | `glm-4.5` · `glm-4.5-air` |
| deepseek | `deepseek` | `https://api.deepseek.com/v1/` | openai | 必填 | `deepseek-chat` · `deepseek-reasoner` |
| kimi | `kimi` | `https://api.moonshot.cn/v1/` | openai | 必填 | `moonshot-v1-32k` · `moonshot-v1-8k` |
| compat（OpenAI 兼容自定义） | Other 输入，默认 `custom` | 无预设，Other 必填（desc 注明「OpenAI 官方 `https://api.openai.com/v1/` 或自建网关均可」） | openai | 必填 | Other 必填 |
| relay（中转站） | Other 输入，默认 `custom` | 无预设，Other 必填（desc：「第三方转发服务给的地址」） | openai | 必填 | Other 必填 |
| local（Ollama） | `ollama` | `http://localhost:11434/v1/` | openai | 可选（「无需 Key」→ `""`） | `qwen3:8b` · `llama3.1:8b` + Other |
| unsure→有地址 | Other 输入，默认 `custom` | 无预设（A1 直接填） | openai | 必填 | Other 必填 |

> 模型名/地址为 v1.0 快照（P10/P11 官方文档核对一次），随服务商发布迭代由维护者更新——预设是「默认值」不是「唯一值」，Other 永远兜底。

**Anthropic 协议范围声明**：向导 v1.0 不提供 Anthropic 协议直连分支（protocol 固定 openai）；需要 Claude 的用户走设置 → 模型 → 添加服务商（既有 provider modal 支持 `protocol: anthropic`，sidebar.js L1318）。在 §9 边界显式记录，不静默遗漏。

### 3.4 答案 → provider data 映射（intent 解析）

问题卡 answers → `{name, baseUrl, apiKey, protocol, models}` 组装：

| 来源 | provider data 字段 | 写入值 |
|---|---|---|
| intent（Q2/B1 解析） | `name` | 预设 key（zhipu/deepseek/kimi/ollama）或自定义输入（compat/relay/unsure→`custom` 或 A2） |
| C1 / A1 | `baseUrl` | 答案原文 trim；无尾斜杠补 `/` |
| C2 / A3 | `apiKey` | 答案原文 trim；「无需 Key」→ `""` |
| C3 / A4 | `models` | `[{ id: <模型> }]`（maxTokens/contextWindow 等不填，后端默认） |
| 全部 | `protocol` | `openai`（v1.0 固定，Anthropic 范围声明见 §3.3） |

**落盘**：`saveNewProvider(name, data)`（sidebar.js L1284）→ mutate `state.parsedConfig.llm.providers[name]` + `flushConfigToServer()`（`updateConfig` WS 帧）。**前端不写 llm.model / preset chain**——后端在收到新 provider 后经 `PresetStore.ensureDefaultPreset()`（PresetStore.scala L113）自动创建 `general` 默认 preset 并指向新模型（#339 @ecaf0fcc 语义，实证：sidebar.js L1280-1291 注释 + PresetStore.scala L208-214）。

**校验（落盘前，前端）**：baseUrl 非空且形如 URL（`/^https?:\/\//i`）；apiKey 非空（local 除外）；模型非空；自定义 provider 名无空白（`/\s/` 拒绝，同 sidebar.js L1329）。任一不满足 → 摘要气泡内红字归因到具体字段，不落盘。

### 3.5 完成页（落盘 + 探活成功后展示）

配置成功后的完成气泡（模拟流式，`.bubble.ai`）：展示创建结果——

> 配置成功。服务商 **deepseek** 已就位，默认模型 **deepseek-chat**，默认预设 **general** 已创建。连接验证通过，我已经就位。试试对我说点什么吧。

文案结构（i18n）：`onboarding.done.summary`（服务商/模型/预设三槽位 + 引导语）。**「general」为必现字样**（可断言点 §11 A8）。随后 `setOnboardingState('done')`。

### 3.6 状态机（进入 / 跳过 / 中断恢复 / 已完成）

| 状态 | 进入条件 | 行为 | 出口 |
|---|---|---|---|
| S0 未触发 | `configured=true` 且 onboarding ∈ {done, skipped} | 无任何引导 | —（终态；已完成不再弹） |
| S1 模拟打招呼 | `configured=false` 且 onboarding ∈ {null, pending}（initOnboarding 分派） | 打字机播 G1–G3；可跳过（点气泡 / Esc / 跳过按钮 → 瞬时播完） | 播完/跳过 → S2 |
| S2 主卡 | S1 完成 | 渲染主卡 Q1→Q2（→U1），dependsOn 逐层揭示 | 确认 → 分支：先看看→S3a / 跳过→S3b / 配置→S3c |
| S3a 稍后配置 | Q1=先看看 | 告别语 sim.later（打字机） | 状态保持 pending → 下次启动回 S1 |
| S3b 跳过引导 | Q1=跳过 | `setOnboardingState('skipped')` | 不再出现（服务端豁免 probe 门槛） |
| S3c 配置细节 | Q1=配置 | 按 Q2/U1 分派副卡 C/A/B → 摘要确认气泡 | 确认并测试 → S4；返回修改 → 重开副卡 |
| S4 落盘+探活 | 摘要确认 | `saveNewProvider` + probeLlm（20s 前端超时，既有） | ok → S5；fail → S4e |
| S4e 探活失败 | probeResult.ok=false | 错误气泡展示归因（§5.3），摘要气泡旁提供 [返回修改 / 稍后再说] | 返回修改 → 重开副卡；稍后再说 → 保持 pending |
| S5 完成 | probe ok | 完成气泡（provider + general preset）→ `setOnboardingState('done')` | 真实使用（终态） |
| S6 中断 | 任意时刻关窗/刷新/切会话 | **不写任何进行中状态**（onboarding 保持 pending/null） | 下次启动回 S1（D5 不续档） |
| S7 回归用户 | `configured=true` 且 onboarding=null（升级场景） | 轻量提示气泡 returnGreet + 偏好问题卡（移除 probe 前置，D2） | 答完/不用了 → 不再出现（本次 boot） |

## 4 流式模拟交互设计（打字机规格）

### 4.1 载体与结构

- 模拟气泡复用既有 AI 气泡 DOM：`<div class="row ai"><div class="bubble ai">…</div></div>`（chat.js `renderAskUser` 同构），**零新视觉容器**。
- 顶栏来源标注 `NEBULA`（复用注入消息的来源标注规则，visual-style 铁律 4：蓝色气泡内容须显示来源）——与 G2「预录」声明呼应，杜绝「假装实时 AI」的误导。
- 一段气泡 = 一个模拟段落（G1/G2/G3 为三段落一个气泡；告别语/完成语各为一个气泡）。段落间停顿 400ms。
- 打字机引擎：纯前端 `setInterval`/`requestAnimationFrame` 逐字 append 文本节点，**不经过 LLM、不产生 WS 帧**；仅渲染层行为。

### 4.2 打字机参数（v1.0 冻结值）

| 参数 | 值 | 说明 |
|---|---|---|
| 逐字间隔 | 30–45ms/字（常量建议 36ms，中文语境） | P5 冲突取舍收敛值：比真实流式略快，不拖沓 |
| 标点停顿 | `，` `。` `？` 后 +150ms；`——` 后 +250ms | 模拟真实流式的断句节奏（P5） |
| 段落间停顿 | 400ms | G1→G2→G3 之间 |
| 光标 | 打字期间段尾显示 `▍`（inline-block，`var(--color-text)`，CSS `animation: blink 1s steps(2) infinite`）；段落播完光标消失 | 光标是「在说话」的唯一动态标识 |
| 换页节奏 | 全部段落播完 → 停顿 600ms → 主卡淡入（§7 A-1） | 问题卡在「话说完」之后才出现 |
| 可跳过 | 点气泡本身 / 按 Esc / 气泡旁「跳过」小按钮（`.glass-control`）→ 立即播完当前段落 + 剩余段落瞬时渲染 → 直接出问题卡 | P5「必须可跳过」 |
| prefers-reduced-motion | `@media (prefers-reduced-motion: reduce)` 下：**瞬时渲染全文**（无逐字、无光标、无段落停顿），问题卡直接出现 | 全部动效退化 |

### 4.3 与问题切换的衔接（回答后打字下一题）

| 时机 | 行为 |
|---|---|
| S1 播完 | 主卡淡入（§7 A-1），打字机不再参与 |
| 分支终止（先看看/跳过/B1=云端） | 告别语**打字机播放**（sim.later / sim.tryCloud），播完该分支结束 |
| 摘要确认气泡 | 用打字机播摘要文本（含掩码 apiKey），播完才出现 [确认并测试 / 返回修改] 两按钮——防止用户没看完就点 |
| 探活进行中 | 摘要气泡内显示「正在验证连接…」（复用 `onboarding.probing`，i18n 既有）替换按钮行；**禁止**假进度条（克制） |
| S5 完成 | 完成气泡打字机播放（provider + general preset），播完 `setOnboardingState('done')` |

### 4.4 视觉一致性（P8）

- 打字机文本样式 = `.bubble.ai` 既有文本样式（字号/行高/颜色 token 全继承），仅逐字出现，无额外动画。
- 光标与文本同基线，不改变行高（`position: relative; top: 1px` 级微调，禁整行跳动）。
- 跳过按钮：小尺寸 `.glass-control`（铁律 2），置于气泡右上角（绝对定位），hover 有既有玻璃态反馈。

## 5 配置落盘链路

### 5.1 链路总览（写盘路径唯一）

```
摘要确认 [确认并测试]
  → 前端校验（§3.4：baseUrl/apiKey/model/name 四查，失败红字归因，不落盘）
  → saveNewProvider(name, data)          // sidebar.js L1284：mutate parsedConfig.llm.providers[name]
  → flushConfigToServer()                // sidebar.js L1105：sendWs({type:'updateConfig', config})
  → 后端 updateConfig 校验 + 存盘
  → 后端 PresetStore.ensureDefaultPreset()  // 自动创建/修复 general 默认 preset，指向新 provider 模型（#339）
  → probeLlm()                           // onboarding.js L58：sendWs({type:'probeLlm'})，20s 前端超时
  → probeResult {ok, provider, error}
      ok   → setOnboardingState('done') → S5 完成气泡
      fail → S4e 错误归因气泡
```

**唯一性约束**：所有答案一律经 `saveNewProvider` → `flushConfigToServer` → `updateConfig` WS 帧写入，不新建配置通道、不直改 nebflow.json 文件、**不写 llm.model / preset chain**（#339 @ecaf0fcc：global default-model 退役，后端 auto-create general preset；实证 sidebar.js L1280-1291 注释 + PresetStore.scala L208-214 `PresetFile(defaultPreset="general", presets=Map("general"→general))`）。

### 5.2 provider data schema（与既有 provider modal 输出对齐）

```js
{
  baseUrl: "https://api.deepseek.com/v1/",   // trim + 补尾斜杠（同 sidebar.js L1330-1333）
  apiKey: "sk-…",                             // local 分支可为 ""
  protocol: "openai",                          // v1.0 固定；Anthropic 范围声明 §3.3
  models: [{ id: "deepseek-chat" }],           // maxTokens/contextWindow 不填，后端默认
  // maxConcurrency/rpm/queueTimeoutMs 不收集（向导 v1.0 用服务端默认；设置页可改）
}
```

### 5.3 探活失败归因（S4e）

- 展示载体：错误气泡（`.bubble.ai` + 错误色 token），文案 = `onboarding.probeFailed`（既有 i18n，`{error}` 槽位填充 probeResult.error 原文）。
- 归因辅助行（前端按 error 字符串粗分类，非精确诊断）：
  - 含 `401/403/unauthorized/Invalid API key` → 「检查 API Key 是否完整、前后无空格」（i18n `onboarding.err.auth`）
  - 含 `404/ECONNREFUSED/ENOTFOUND/timeout/connect` → 「检查 Base URL 是否正确、网络是否可达」（i18n `onboarding.err.endpoint`）
  - 含 `model not found/Model not exist/400` → 「换一个模型试试（模型名需与服务商文档一致）」（i18n `onboarding.err.model`）
  - 其他 → 仅展示 error 原文（i18n `onboarding.err.other`）
- 气泡内按钮：[返回修改]（重开副卡 C/A，旧值不保留——D5 不续档）/[稍后再说]（告别语，保持 pending，下次启动重引导）。
- **硬门纪律**：失败路径**绝不**调用 `setOnboardingState('done')`（服务端 probeOkAt 硬门兜底，WebSocketRoutes L2870-2891）；`skipped` 仅由 Q1=跳过 触发。

### 5.4 完成页契约

- 落盘 + probe ok 后：完成气泡（打字机，§3.5）展示 provider 名 / 默认模型 / **general 预设字样**，随后 `setOnboardingState('done')`。
- 完成页数据来源：answers 本地内存 + probeResult.provider（不依赖再次 getConfig）；若 probeResult 缺 provider 则用本地 name 兜底。

## 6 引导动画设计（与 AskUser×Canvas 方向 C 的消费关系）

### 6.1 关系声明（方向 C 规格 §5.1 称 onboarding 为首个消费场景）

- **共享通道**：onboarding 的问题卡与方向 C 走同一条 `showOptions` 渲染通道（chat.js L1446），方向 C 的 `preview`/`canvas` 字段为**可选向后兼容扩展**（方向 C §2.1 schema，纯新增）。
- **v1.0 零 preview 消费**：onboarding 的问题全部是纯文本选项（服务商/模型/地址），无视觉对比需求，**不使用** `preview`/`canvas` 字段。零消费 = 渲染与现状逐像素一致，方向 C 未实现也不影响 onboarding（降级天然成立，方向 C §2 无 preview 即现状）。
- **联动价值兑现点（v1.1+ 预留）**：回归用户的「偏好问题卡」（S7）与后续主题/布局类视觉选择题，是方向 C `preview` 与 Canvas 直答的真实消费场景；本规格 v1.0 不承诺。
- **依赖顺序**：两规格**可并行实施**（onboarding 不依赖方向 C 任何新功能；方向 C 的验收场景由自身视觉类问题承担，不占 onboarding 验收点）。

### 6.2 onboarding 自有动效清单（克制基调，P8 铁律 6）

| 动效 | 触发 | 时长 | 缓动 | 说明 |
|---|---|---|---|---|
| 打字机逐字 | S1/告别语/摘要/完成气泡 | 36ms/字 | 线性 | §4 全部参数 |
| 光标闪烁 | 打字期间 | 1s steps(2) 循环 | 线性 | `var(--color-text)` |
| 主卡淡入 | S1 播完 + 600ms | 200ms | ease-out | opacity 0→1 + translateY 4px→0（§7 A-1 同款） |
| 副卡出现 | 主卡确认后 | 200ms | ease-out | 同主卡淡入 |
| 摘要按钮出现 | 摘要播完 | 150ms | ease-out | 按钮行淡入 |
| 探活等待 | — | — | — | 无动画（禁假进度条） |
| reduced-motion | `prefers-reduced-motion: reduce` | — | — | 上述全部退化：打字瞬时、卡片直接显示、无位移 |

## 7 AskUserQuestion 卡片动画优化清单

> 范围：仅 onboarding 用到的问题卡能力（主卡/副卡共用 showOptions）。以下 A-1/A-2 为 onboarding 必做；A-3/A-4 为 showOptions 通用增强（实现方按最小改动原则评估，可后置但需在 §11 验收点中注明启用状态）。

| # | 优化 | 规格 | 优先级 |
|---|---|---|---|
| A-1 | 问题卡整体淡入 | 卡片插入 DOM 后 200ms ease-out，opacity 0→1 + translateY 4px→0（无位移缩放，克制） | 必做（§6.2 主卡/副卡） |
| A-2 | dependsOn 揭示动画 | U1 出现（dependsOn Q2=不确定）：从 `display:none` → 显示时复用 A-1 淡入（150ms）；隐藏时**无动画**直接隐藏（清答案逻辑不变，chat.js L1466-1477） | 必做（心理测评「逐层感」的载体，P1） |
| A-3 | 已答问题折叠 | 已答且确认的问题区轻微压暗（opacity 0.6，无位移），保留回读性；不折叠成一行（避免「卡片缩小」的突兀） | 后置（P1） |
| A-4 | 选项选中反馈强化 | 选中态现有 `.picked`（边框/勾选）之上不加新动效——现状已满足视觉-style 铁律 2；若实现方发现点击无任何过渡可加 120ms ease-out 的背景过渡 | 后置（P2） |
| A-5 | confirm 按钮启用过渡 | 全答解锁时按钮从 disabled → enabled，120ms ease-out 透明度过渡（复用既有 `.option-confirm` 禁用态样式） | 必做 |

**与打字机的衔接定序**（可断言，§11 A4）：S1 打字播完 → 600ms 停顿 → A-1 主卡淡入 → 用户点选 Q1 → Q2 区域 A-2 淡入 → … → 主卡确认 → 副卡 A-1 淡入。

## 8 视觉规格

**纪律：零新视觉体系、零新颜色 token。** 全部复用既有类与 `var(--*)` token（visual-style 铁律 6 + 案例 001「零新 token」纪律）；唯一新增为打字机光标与跳过按钮的**尺寸/动画类**（§8.4 显式说明，颜色仍用既有 token）。

### 8.1 模拟气泡（打招呼/告别/摘要/完成）

| 属性 | 值 | 来源 |
|---|---|---|
| 容器 | `.row.ai > .bubble.ai` | 既有（chat.js renderAskUser 同构） |
| 顶栏来源 | `NEBULA` 标注（SOURCE · AGENT · EVENT 规则） | visual-style 铁律 4 |
| 文本样式 | 继承 `.bubble.ai` 既有字号/行高/颜色 | 既有 |
| 打字光标 | `var(--color-text)`，1px 宽 inline-block，blink 动画 | 既有 token + 新增动画类（§8.4） |
| 跳过按钮 | 小尺寸 `.glass-control`（气泡右上角绝对定位） | 铁律 2 玻璃控件 |

### 8.2 问题卡（主卡/副卡 A/B/C）

| 属性 | 值 | 来源 |
|---|---|---|
| 容器 | `.option-box` 既有 | 既有 |
| 问题标题 | `.option-q` 既有（标题 600，方案 B） | 铁律 3 |
| 选项按钮 | `.option-btn` 既有（label 400 / desc 12px muted） | 既有 + 铁律 3 |
| 选中态 | `.picked` 既有（边框+勾选，非颜色唯一编码） | 既有（方向 C §9 同源） |
| 自由输入 | `.option-custom-input` 既有（textarea，玻璃控件体系） | 既有 |
| 确认/取消 | `.option-confirm` / `.option-cancel` 既有 | 既有 |
| 摘要气泡按钮 | 复用 `.glass-control`（铁律 2 参数：淡绿玻璃 rgba(7,193,96,0.42) + blur 8px 参照发送按钮） | 铁律 2 |

### 8.3 错误态

- 错误气泡文本用既有错误色 token（与 `cfg-toast-error` 同源色，实现方 Grep 定位），背景仍为 `.bubble.ai`（不新增红色气泡样式）。
- 摘要内字段级红字（校验失败归因）：复用既有 muted/error 文本 token，字号 12px。

### 8.4 新增 CSS 说明（仅此两处，颜色零新增）

| 新增 | 内容 | 理由 |
|---|---|---|
| `.ob-cursor` 动画类 | `@keyframes ob-blink { 0%,100%{opacity:1} 50%{opacity:0} }`，1s steps(2) | 打字光标需要闪烁动画；颜色用 `var(--color-text)`，无新色 |
| `.ob-skip` 尺寸类 | 跳过按钮 24×24px 圆角 6px（内容为已有 skip 图标/文本），定位在气泡内 | 现有 CSS 无此小尺寸玻璃按钮抽象；视觉参数继承 `.glass-control` |

> 若实现方发现既有类可复用（如已有小号 glass 按钮），回填本节替换新增项——新增是兜底不是强制。

## 9 边界与异常

| # | 场景 | 行为（二值可验） |
|---|---|---|
| E1 | 首启但 configData 未到达（WS 尚未推送） | initOnboarding 等待 configData（现状逻辑，main.js L1814 每帧调用）；不提前渲染 |
| E2 | configData 在引导中途再次推送（配置保存后重推） | `started` 标志防重复触发（onboarding.js L33 既有）；进行中的引导不被打断 |
| E3 | 中途关窗/刷新/切会话（S6 中断） | 不写任何进行中状态；onboarding 保持 pending/null；下次启动从 S1 重新打招呼，问题卡从 Q1 起（D5，§3.6） |
| E4 | onboarding=done / skipped 启动 | S0：无任何引导气泡/卡片（已完成不再弹） |
| E5 | 探活失败 | S4e 归因气泡（§5.3）；「返回修改」重开副卡；「稍后再说」保持 pending；绝不写 done |
| E6 | probeLlm 超时（20s 前端兜底） | 归因气泡显示 `onboarding.probeTimeout`（既有 i18n），同 E5 处理 |
| E7 | 自定义 provider 名含空格/为空 | 摘要内红字归因「服务商名不能包含空格」（同 sidebar.js L1329 校验），不落盘 |
| E8 | baseUrl 非 http(s)（如手滑输入中文/漏协议） | 校验失败红字归因「Base URL 需以 http:// 或 https:// 开头」，不落盘 |
| E9 | 中转站地址带路径/尾斜杠差异 | 统一 trim + 补尾斜杠（同 showProviderModal L1330-1333）；不做 URL 规范化（服务商兼容性各异，交给 probe 验证） |
| E10 | 超长 apiKey/baseUrl（粘贴 500+ 字符） | `.option-custom-input` 既有换行/滚动行为；摘要掩码显示（`sk-…•••`）；不截断不省略（除掩码） |
| E11 | 375px 窄视口 | 问题卡/气泡不横向溢出；选项按钮换行（既有 flex 行为）；增量口径：打开引导的 scrollWidth 与关闭态基线一致（案例 001 踩坑③口径） |
| E12 | 多语言（zh-CN / en） | 全部文案走 i18n key（§3.1/§3.2 已列），两语言文件同步新增；断言比对运行时 locale 输出（案例 001 踩坑①） |
| E13 | Anthropic 协议用户 | 向导 v1.0 无 Anthropic 分支（§3.3 范围声明）；引导文案不出现「Anthropic 在此配置」，用户走设置 → 模型手动添加 |
| E14 | 本地（Ollama）未安装 | probe fail（connection refused）→ 归因 endpoint：「检查本地 Ollama 是否已启动」（i18n `onboarding.err.ollama`），返回修改或稍后再说 |
| E15 | 回归用户提示（S7）已答 | 本次 boot 不再出现（现状 showReturningPrompt 语义）；不写 skipped（无 probeOkAt 亦可——D2 已移除 probe 前置） |
| E16 | 设置「重新运行新手引导」 | setOnboardingState('pending') + reload（sidebar.js L1032 既有），下次启动走 S1 全流程；已配置的 provider 不被清除（引导只加不减） |
| E17 | 重复点击确认/摘要按钮（防抖） | 落盘链路加锁：probe 在途时按钮 disabled（复用 onboarding.js L135-136 既有禁用模式）；WS 帧恰好一帧 |
| E18 | 完成页后立即使用 | done 状态 + probe 已过 → 发送消息即走真实 LLM（无需二次探活）；用户可直接对话 |

## 10 无障碍

| 项 | 规范 | 依据 |
|---|---|---|
| 键盘导航 | Tab 顺序：打字跳过按钮 → 问题卡选项按钮 → Other 输入框 → 确认/取消；dependsOn 隐藏题 `display:none` 自然移出 Tab 序（现状行为，无需额外处理） | WAI-ARIA APG Radio（P7） |
| 点选 | 选项为原生 `<button>`，Enter/Space 激活（现状 `.option-btn`）；Other 为 textarea 可键入 | 现状 |
| focus ring | 沿用既有 `.option-btn:focus-visible` / `.glass-control` focus 样式，不新增 | visual-style 铁律 2 玻璃控件体系 |
| ARIA | 单选题组语义沿用现状（`.option-btn` + `.picked`）；打字机文本 `aria-live="polite"`（读屏逐段播报，避免逐字轰炸） | P7 |
| apiKey 脱敏 | Other 输入框 `type="password"` 语义（text 内容打点显示，实际值存内存）；摘要掩码 `sk-…•••` 展示 | §3.2 C2 说明 |
| 对比度 | 问题卡/气泡文本用既有 token（≥4.5:1 由既有体系保证，回归验证）；错误红字 ≥4.5:1（用既有 error 文本 token 非纯红） | 任务要求 ≥4.5:1 |
| 非鼠标可达 | 全程键盘可完成：跳过打字（Esc 或 Tab 到按钮）、全部问题、摘要确认按钮；无悬停依赖（hover 只是装饰） | P7 |
| reduced-motion | `prefers-reduced-motion: reduce`：打字瞬时、卡片直接显示、无位移（§4.2/§6.2 已冻结） | §4.2 参数表 |
| 读屏跳过路径 | 打字机对读屏 = aria-live 播报整段；跳过按钮有 aria-label（`onboarding.sim.skip`）；光标元素 `aria-hidden="true"`（装饰） | — |

## 11 可断言验收点（≥8 条二值断言，供 qa-frontend 转 Playwright）

> 断言口径纪律（案例 001 共性提炼）：凡文案一律比对 i18n key 运行时输出；凡布局一律用增量口径（开 vs 关基线）；凡顺序/状态一律显式语义。以下每条标注是否需截图。

| # | 断言（二值） | 需截图 |
|---|---|---|
| A1 | 首启（seed: configured=false, onboarding=null）：聊天主区出现模拟 AI 气泡（`.row.ai .bubble.ai`）且**不存在** `.onboarding-overlay` 元素（旧模态废弃，D1） | 是（亮/暗） |
| A2 | 打字机逐字：气泡出现后 t1 时刻文本长度 < 最终长度（取两个采样点）；reduced-motion 下首帧即全文 | 否 |
| A3 | 跳过打字：点击跳过/Esc → 全文瞬时出现，主卡在 ≤800ms 内进入 DOM（A-1 淡入） | 是 |
| A4 | 衔接定序（§7）：主卡淡入在打字播完 +600ms 后；点 Q1=配置模型 → Q2 区域淡入（A-2），Q2 在 Q1 已答前**不可见** | 否（时序断言） |
| A5 | Q2 选项 label 恰为 7 项：智谱 GLM / DeepSeek / Kimi（月之暗面）/ OpenAI 兼容（自定义）/ 中转站 / 本地模型（免费）/ 不确定，帮我判断 | 是 |
| A6 | 选 zhipu 分支 → 副卡 C 的 C1 预设含 `https://open.bigmodel.cn/api/paas/v4/`；C2 无「无需 Key」选项；C3 预设含 `glm-4.5` | 是 |
| A7 | 选 本地模型 分支 → C2 出现「无需 Key」选项；选「无需 Key」后确认按钮解锁；落盘 apiKey 为空串 | 否（WS 帧） |
| A8 | 摘要确认后 WS 发出**恰好一帧** `updateConfig`，payload JSON 的 `llm.providers.<name>` 含 baseUrl/apiKey/models，且**全文不含 `llm.model` 键**（#339 契约） | 否（WS 帧断言） |
| A9 | probe ok → 发出 `setOnboardingState(done)` 帧；完成气泡文本含 **general** 字样 + provider 名 + 模型名（i18n `onboarding.done.summary` 渲染） | 是 |
| A10 | probe fail（seed 非法 apiKey）→ 错误气泡含归因文案（`onboarding.err.auth`），**未**发出 done 帧；「返回修改」重开副卡 C | 是 |
| A11 | 中断恢复（D5）：答到副卡 C 中途 reload → onboarding 仍 pending/null → 重启后重新出现打招呼气泡，主卡从 Q1 开始（无任何已答残留） | 是 |
| A12 | onboarding=done 启动：DOM 中无任何引导气泡/问题卡（S0） | 否 |
| A13 | 375px 视口增量口径：打开引导的 scrollWidth ≤ 关闭态基线 + 引导子树无溢出（案例 001 踩坑③口径） | 是 |
| A14 | 键盘全程：Tab 聚焦跳过按钮 → 主卡选项 → Other 输入框 → 确认按钮；Enter 选中 Q1/Q2；Esc 跳过打字；隐藏题不在 Tab 序 | 否（focus 断言） |
| A15 | 不确定分支：Q2=不确定 → U1 出现；U1=有 → 副卡 A 从 A1 地址题开始；U1=没有 → B1 出现；B1=本地免费 → 副卡 C 且 C1 预设为 `http://localhost:11434/v1/` | 是 |

**qa 转 Playwright 前置**（沿用案例 001 附录基建经验）：
- onboarding 异步遮罩坑：新设计中 `.onboarding-overlay` 已废弃，但引导仍随 configData 异步出现——断言等待条件用「`.bubble.ai` 出现 + 文本前缀 G1」，不用固定 sleep。
- 隔离实例 seed：`onboarding.json` 可写 pending/null（测首启）或 done（测 S0），配合 Playwright 注入。禁止 seed 真实 apiKey。

## 12 实现路径（最小改动 + 旧 onboarding.js 复用/替换清单）

### 12.1 旧 onboarding.js（web/js/onboarding.js）逐项处置

| 现有代码（行号 @ 本规格撰写时点） | 处置 | 说明 |
|---|---|---|
| `initOnboarding(msg)`（L32-44） | **复用**（入口逻辑保留） | configured=false → 新引导；configured=true 且无标记 → S7 回归提示；done/skipped → 返回 |
| `GREETING_MSG`（L22）+ `injectUserMessage` 调用（L161） | **废弃** | 不再注入真实用户消息；改前端打字机气泡（§4） |
| `showWizard()` / `renderWelcomeStep` / `renderProviderStep` / `renderDoneStep`（L76-163） | **废弃** | overlay 三步模态废除（D1）；由 打字机 + 问题卡 + 摘要/完成气泡 取代 |
| `showReturningPrompt()`（L167-205） | **重构** | 保留轻量提示，但改气泡形态；「打个招呼」→ 打字机自我介绍 + 偏好问题卡；**移除 probe 前置**（D2，L188 的 probeLlm 调用删除） |
| `probeLlm()` + `pendingProbe`（L58-66）+ `onMessage('probeResult')`（L50-56） | **复用** | Promise 封装原样保留，S4 落盘后调用；超时文案沿用 |
| `setOnboardingState(next)`（L70-72） | **复用** | WS 封装不变 |
| `started` 标志（L33） | **复用** | 一次 boot 一次；中断重启后由 reload 重置（D5） |
| `.onboarding-overlay` CSS 类 | **废弃**（首启） | 引导不再盖界面；回归提示也不再用 overlay（改气泡）——CSS 若无处引用可后续清理（另立任务，本规格不强制） |

### 12.2 新增模块（最小面）

| 模块 | 内容 | 量级 |
|---|---|---|
| 打字机引擎 | 逐字/标点停顿/段落停顿/光标/跳过/reduced-motion（§4），可放 onboarding.js 或独立 `simTyping.js` | ~60 行 |
| 问题树定义 + intent 解析 | 主卡/副卡 questions 数组（i18n key 引用）+ §3.4 映射函数（answers → provider data）+ §3.3 预设表常量 | ~100 行 |
| 引导编排 | S1→S2→副卡→摘要→落盘→probe→完成 状态驱动（§3.6），复用 showOptions/renderAskUser 同构渲染 | ~120 行 |
| 摘要/完成/错误气泡 | 三个气泡模板 + 按钮行（§5.3/§3.5/§4.3） | ~60 行 |

### 12.3 i18n key 清单（新增，zh-CN + en 同步）

- 打招呼：`onboarding.sim.greet1/2/3`、`onboarding.sim.skip`
- 告别/完成：`onboarding.sim.done/later/returnGreet/tryCloud`
- 问题：`onboarding.q.start(.config/.browse/.skip)`、`onboarding.q.provider(.zhipu/.deepseek/.kimi/.compat/.relay/.local/.unsure)`、`onboarding.q.unsureUrl(.yes/.no)`、`onboarding.q.baseurl/.apikey(.none)/.model`、`onboarding.q.relayBase/.providerName/.localEnough(.yes/.no)`
- 按钮：`onboarding.btn.continue/.summary/.testAndSave/.back`
- 完成/错误：`onboarding.done.summary`、`onboarding.err.auth/.endpoint/.model/.other/.ollama`
- 废弃（删除或保留兼容）：welcomeTitle/welcomeSubtitle/start/providerTitle/providerHint/addProvider/doneTitle/doneNote/finish/returnTitle/returnHint/greet（S7 重构后 returnGreet 替换 greet）；保留 probing/probeFailed/probeTimeout/later/noThanks

### 12.4 涉及文件（全部前端，后端零改动）

| 文件 | 改动 |
|---|---|
| `web/js/onboarding.js` | 主体重写（§12.1 复用/废弃清单） |
| `web/js/sidebar.js` | 零改动（saveNewProvider/flushConfigToServer 既有复用）；仅确认导出可用 |
| `web/js/chat.js` | 零改动（showOptions 既有能力足够）；若 A-1/A-2 揭示动画需挂 showOptions 则最小侵入（wrap 插入动画类，评估后定） |
| `web/js/locales/zh-CN.js` + `en.js` | 新增 §12.3 key |
| `web/css/*` | 新增 `.ob-cursor`/`.ob-skip` 两处（§8.4） |
| 后端 | **零改动**（updateConfig/probeLlm/setOnboardingState/PresetStore 全部既有） |

### 12.5 验收路径

1. 实现方按 §11 逐条自测 → 交 qa-frontend 转 Playwright（A1-A15，15 条）。
2. 视觉评审（design-engineer）：隔离实例亮/暗主题 + 375px 截图，对照 §8 逐项核对，与 qa DOM 断言交叉验证（防裸看图误报）。

## 13 参考链接

**平台规范 / 行业范式**
- Apple HIG · Onboarding: https://developer.apple.com/design/human-interface-guidelines/onboarding（⚠ JS-gated 未抓正文）
- WAI-ARIA APG · Radio Group: https://www.w3.org/WAI/ARIA/apg/patterns/radio/
- Typeform Logic Jumps: https://www.typeform.com/help/a/logic-jumps-conditional-branching-360051711554/（⚠ 未抓正文）
- Slack 工作区创建向导: https://slack.com/help/articles/212675257-Create-a-Slack-workspace（⚠ 产品行为观察）
- Notion Help: https://www.notion.com/help（⚠ 产品行为观察）
- Intercom（会话式引导范式）: https://www.intercom.com（⚠ 产品行为观察）
- Vercel CLI（配置写盘范式）: https://vercel.com/docs/cli（⚠ 产品行为观察）

**服务商官方（预设表核对源，v1.0 快照）**
- DeepSeek API Docs: https://api-docs.deepseek.com/（⚠ 未抓正文；baseUrl `https://api.deepseek.com` 兼容 `/v1`）
- 智谱开放平台: https://open.bigmodel.cn/dev/api（⚠ 未抓正文）
- 月之暗面 Moonshot 文档: https://platform.moonshot.cn/docs/（⚠ 未抓正文）

**Nebflow 内部（铁律 / 案例库 / 关联规格 / 源码实证）**
- visual-style 铁律: `~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- design-system 案例库: `~/.nebflow/skills/design-system/SKILL.md`（案例 001 消息搜索弹窗——零新 token / i18n key 先行 / 增量断言口径三条教训直接引用）
- AskUser×Canvas 方向 C 规格: `~/.nebflow/docs/Nebflow/askuser-canvas-integration-spec.md`
- 源码实证：`web/js/onboarding.js`（旧入口 L32/L50/L58/L70/L76-205）· `web/js/chat.js`（showOptions L1446、dependsOn L1454-1477、renderAskUser L1699、askUserAnswer L1726）· `web/js/sidebar.js`（saveNewProvider L1284、flushConfigToServer L1105、openProviderWizard L1297、provider onConfirm 校验 L1329-1376）· `src/main/scala/nebflow/core/OnboardingService.scala`（probeFailure L138、probeLlm L122）· `src/main/scala/nebflow/core/presets/PresetStore.scala`（ensureDefaultPreset L113、general 初始化 L208-214）· `src/main/scala/nebflow/gateway/WebSocketRoutes.scala`（setOnboardingState 硬门 L2870-2891）
- preset 语义重构（#339）: 提交 `@ecaf0fcc`（Merge 'feat/preset-fe'，llm.model.default 退役）
