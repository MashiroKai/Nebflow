---
type: design-note
status: draft（呈作者拍板 2026-10-30；只研究+设计，零产品代码）
---

# 记忆系统升级——维度画像库 + hook 式主动注入设计稿

> 结论先行：本稿把记忆从「单一对话检索库」升级为**双库双层**——**维度画像库**（`profile-db/`：按维度切分的结构化画像，人可读文件 + 机器可读 frontmatter 字段）+ **hook 式主动注入**（`hooks/`：写入时把触发条件写清，触发时以 `<system-reminder>` 随用户消息隐藏注入）。画像库与检索库互补不替代：检索库=事件考古（stand-recall），画像库=结构化画像（stand-profile）。hook 走**双层触发**：代码第一层（确定性事件匹配）+ JeV/LLM 第二层（自然语言条件兜底），防双注靠触发日志幂等。分期推荐：**维度库先行**（纯约定零代码即可起步）→ hook MVP（先代码层后 LLM 层）→ 与认知方案档案（[[20261030_human-readable-presentation.zh-CN]] §6，#1132 二期）汇合。记忆伦理（可查/可删/可审计）进 V1 不做二期。同类产品要点：Mem0 Profiles 是我们的直接参照，Zep 的双时态事实失效与 Context Block 注入形态最值得抄，Letta 的 block 限长与「常驻 vs 按需」分层是 prompt 预算的护栏，Muse/instinct 证明「画像驱动主动行为」是产品级方向且隐私反噬真实存在——我们取其机制、避其黑箱。

## 词条

- 维度画像库（profile-db）— 按维度切分的结构化记忆：每维一人可读 Markdown 文件，frontmatter 承载机器可读字段（编码值/置信度/来源/时间戳），新目录 `~/.stand/profile-db/`。 ^term-profile-db
- 维度（dimension）— 画像的最小切分单位，如「审美基色」「汇报风格」「红线集」。维度 ≠ 记忆条目：一个维度是一个**有模式的小文件**，内含当前值＋证据＋置信度。 ^term-dimension
- 紧凑码（compact code）— MBTI 式短编码（枚举值域＋单 token），只用于高稳枚举维度；是 hook 代码层可判定的前提。 ^term-compact-code
- hook（主动注入钩子）— 一份「条件 → 要求文本」的定义文件，触发后经 `<system-reminder>` 随用户消息隐藏注入 agent 上下文。 ^term-hook
- 双层触发 — hook 触发判定两级：代码第一层（确定性事件/字段匹配）+ JeV（LLM）第二层（自然语言条件兜底）；两层均可独立否决，日志幂等防双注。 ^term-two-layer-trigger
- 主动注入（proactive injection）— 不依赖当轮检索、由事件/条件驱动把记忆要求喂给 agent；与 stand-recall 的「缺上下文先检索」（被动拉取）正交。 ^term-proactive-injection

## 1. 问题与依据

作者 2026-10-30 15:09 令（要点转写，原意保留）：

1. 记忆应像数据库按维度划分——MBTI 四字母式紧凑编码只是示例，要设计几十上百指标维度的记忆块切分，做**人的画像建模**与**人对 Agent 要求的画像建模**双建模；
2. 好处 = 记忆数据更好管更好检索 + 主动式记忆注入的落脚点；
3. 调研现状与同类产品（点名 Muse、instinct；补 Mem0/Zep/Letta 主流记忆层）；
4. 主动记忆落点 = **hook 式记忆注入**（示例：每次发布新版本 → 帮写一份 XHS 发布用 change log）——写入时把注入条件用代码写清，触发时以随用户消息隐藏注入的 system reminder 把要求喂给 agent；
5. 触发双层：**代码第一层触发，JeV（LLM）第二层兜底**适配复杂条件。

现状盘点（本节每条均可按锚点复核）：

| 件 | 现状 | 角色定界 |
|---|---|---|
| `~/.stand/memory-db/`（SQLite FTS5，CJK bigram + BM25 + 近因衰减） | 2026-10-21 建成，只收主 agent 对话；197 收录会话、670 附件（`index-manifest.json`） | **事件考古**：回答「当时怎么裁定的」 |
| `tools/stand-recall`（`tools/stand-recall:1`） | 检索 CLI：`stand-recall "关键词"`，±邻句、时间窗、τ 衰减；引擎 `MemoryRecallDaemon` 守护自动 `--update --source auto` | 被动拉取通道 |
| `~/.stand/User.md` | Kai 的形象（身份/审美/决策风格/渠道），不收事件（`User.md:1-23`） | 单文件画像的**现状形态**——本稿要结构化的就是它 |
| `~/.stand/Soul.md` | 我的形象+行为红线（`Soul.md:5-35`） | 「人对 Agent 要求」的**现状形态** |
| skills/memory-consolidation（`~/.stand/skills/memory-consolidation/SKILL.md`） | 写入三问准入、取代纪律、T1/T2/T3 生命周期、预算闸 | 写入侧纪律——画像库**继承**其准入与取代思想 |
| 认知方案档案体系（[[20261030_human-readable-presentation.zh-CN]] §6） | 已批五档呈现参数档，持久化裁定落 `~/.stand` 独立 JSON（该稿 §9-11），#1132 二期产品化 | **消费方**：画像库喂它匹配与校准（§2.3） |
| 注入通道 `<system-reminder>` | 引擎既有腿：`SystemReminders.collectAll`（`src/main/scala/nebflow/core/reminders.scala:41`）按轮收集；time reminder 经 `AgentCore.pipeLlmCall` 持久化为 User 消息，其余类型非持久随轮注入；微信/飞书渠道壳同机制包裹（`tools/stand-recall:122-133` 解包佐证） | hook 注入**直接复用**，零新机制（§5.2） |
| 守护先例 | `MemoryRecallDaemon` 节流窗自动入库（`tools/stand-recall:62-66`）；Schedule kind=todo 条件触发/空转降频 | hook 触发的事件源样板（§5.3） |

三点诊断支撑升级必要性：

1. **User.md/Soul.md 是「单文件全维度混装」**——维度间无结构、无置信度、无证据链，检索只能靠全文读，更新靠整段改写；维度一旦上量（几十上百指标）必然失控，这正是作者令①的痛点。
2. **检索库只答「发生过什么」，答不了「他是谁」**——「Kai 喜欢怎么批复」「他对 Agent 的红线」这类高复用事实，每次靠 stand-recall 现场考古是浪费：考古一次应固化成维度值，之后直读（Mem0 文档原话：search 回答「他说过什么」，profile 一次读回答「他是谁」——置信度高，官方文档核到）。
3. **记忆消费只有被动拉取，没有主动注入**——现行注入全是「缺上下文先检索」（Soul.md 红线）的拉取式；没有任何机制让记忆在「发布新版本」这类事件上**主动**变成当轮要求。hook 层补这个缺。

## 2. 双建模定界与与既有体系的关系

### 2.1 双建模的分工

| | 人的画像（Kai 是谁） | 人对 Agent 的要求（要我怎么做） |
|---|---|---|
| 回答的问题 | 身份/专业/审美/节奏/目标 | 自主性边界/汇报风格/渠道/红线/称呼 |
| 稳定性 | 中—高（审美高稳，状态类低稳） | 高（红线/渠道），中（汇报风格随方案档案换档） |
| 消费方 | 任务派发 brief、检索查询构造、方案档案匹配 | 我的 system prompt 组装、hook 注入、权限判定 |
| 现状载体 | User.md | Soul.md |

两库**共享同一套维度模式与文件格式**，只按命名空间分目录（`profile-db/kai/`、`profile-db/agent-requirements/`）。双建模不是两套系统，是一套系统的两个命名空间。

### 2.2 与检索库的关系：互补而非替代

- **检索库（memory-db）= 事件层**：原始对话语料，回答「什么时候谁说了什么」，证据链的根。
- **画像库（profile-db）= 综合层**：从事件层提炼出的**当前**维度值，回答「现在他是什么样」。
- 数据流：**检索库 →（推断/裁定）→ 画像库 →（注入/拼装）→ 当轮上下文**。证据指针（会话 id + 时间戳）留在维度文件里，任何维度值可回溯到检索库原文——这条可追溯链借 Zep 的 episode→fact 设计（置信度高）。
- 互相不可替代：画像库不能取代检索库（裁定的来龙去脉只在事件层）；检索库不能取代画像库（每次「他是谁」都考古 = 把综合成本摊给每一轮）。
- stand-recall 的收录语义**不变**：hook 注入与画像快照同属引擎注入件，与 `<system-reminder>` 一样被排除在检索语料外（`tools/stand-recall:106-112` SKIP 前缀与 INJECTED_SOURCES 已有机制，hook 提醒按同口径登记）。

### 2.3 与认知方案档案体系的关系（feeding / 被 feeding）

认知方案档案（下称「方案档」）= **呈现参数**（人面子长度/折叠/图密度/提醒节奏/监督颗粒度，五档），回答「这轮话怎么说」；维度画像库 = **认知参数**（他是谁、要我怎样），回答「对谁说、为何这么说」。两者方向正交，接口是单向喂养：

- **画像库 → 方案档（被 feeding）**：方案档的显式选档与抱怨校准（该稿 §6.3）需要判定输入——「Kai 现在处于备考冲刺态」这类状态维度值就是选档依据；「他说『别烦我』」既是校准信号，也是「提醒节奏偏好」维度的一次观测。没有画像库，方案档匹配只能靠裸 prompt 判断。
- **方案档 → 画像库（不直接 feeding）**：方案档不反向写画像；校准信号以**证据**形态进入维度文件的证据链，经准入（memory-consolidation 三问）后才可能改写维度值。防止两个系统互相自动改写造成漂移。
- 落点衔接：方案档已批落 `~/.stand` 独立 JSON（该稿 §9-11）；画像库落 `~/.stand/profile-db/`——同数据根、同「独立 JSON/文件与引擎配置分离」哲学，实施批可共用一套文件服务习惯。

## 3. 维度模式（分类法草案）

> 结论先行：分类法先给 **8+8 个一级维度**（每建模各 8），下分二级指标（合计 60+ 个写盘点，留待实施批扩到「几十上百」）；每个维度按四件套定义：**编码形态 / 更新机制 / 置信度 / 时态**。MBTI 式紧凑码**只用于高稳枚举维度**（预计占比 <20%），理由见 §3.3——敢不用到处用，是因为紧凑码的收益来自「代码可判定 + 注入廉价」，这两个收益只在值域封闭且稳定的维度上成立。

### 3.1 人的画像（`kai/` 命名空间）

| 一级维度 | 二级指标示例 | 编码形态 | 更新机制 | 稳定性 |
|---|---|---|---|---|
| K1 身份与角色 | 称呼、日常身份、在研方向、活跃课题线 | 紧凑码（枚举）+ 短文本注 | 显式裁定为主 | 高 |
| K2 专业画像 | 领域知识域、工具链熟练度、学习阶段（备考/常态） | 自由文本 + 熟练度紧凑码 | 对话推断校准 + 显式裁定 | 中 |
| K3 审美与口味 | 配色基色、UI 密度偏好、动效容忍 | **紧凑码**（值域封闭） | 推断校准（swatch 测试类）+ 裁定 | 高 |
| K4 决策与沟通风格 | 批复习惯（打包/逐次）、嫌啰嗦阈值、翻旧账容忍 | 紧凑码 + 文本 | 推断校准 | 中—高 |
| K5 渠道习惯 | 主渠道、各渠道形态（纯文本/卡片）、多设备分工 | 紧凑码 + 文本 | 裁定 + 推断 | 高 |
| K6 目标与项目 | 在途目标（健身/备考/课题）、阶段、截止时间 | 结构化记录（类方案档条目） | 裁定（目标入账） | 中（目标会闭环） |
| K7 节奏与状态 | 忙/闲、冲刺/常态、工作时段、攒批习惯 | 紧凑码（**短 TTL**） | 推断校准，随轮刷新 | **低（会话级）** |
| K8 关系画像 | 协作人（师弟等）、外部代理（Zcode）、各关系的颗粒度授权 | 紧凑码 + 文本 + 授权级 | 裁定为主（谨慎，见 §3.4） | 高 |

### 3.2 人对 Agent 的要求（`agent-requirements/` 命名空间）

| 一级维度 | 二级指标示例 | 编码形态 | 更新机制 | 稳定性 |
|---|---|---|---|---|
| A1 红线集 | 禁自重启、零超时、禁 sleep 轮询、发布纪律、派活纪律 | **紧凑码**（每条红线一个码） | 只随作者令增删（裁定唯一入口） | 高（只增不改语义） |
| A2 自主性刻度 | 何事自主、何事逐次批准、何事呈报待批 | 三档紧凑码 + 例外清单文本 | 裁定 | 高 |
| A3 汇报与呈现 | 声音人格（贾维斯令）、结论先行、人面子纪律 | 紧凑码 + 引用方案档档名 | 裁定（随方案档换档联动） | 中—高 |
| A4 渠道纪律 | 微信纯文本、飞书开放题、附件 vs images 分工 | 紧凑码 + 文本 | 裁定 | 高 |
| A5 称呼与身份 | 叫我什么、我是谁（Spider）、头像 | 短文本 | 裁定 | 高 |
| A6 记忆伦理 | 允许记什么、禁记什么、遗忘请求处理 | 紧凑码 + 文本 | 裁定 | 高 |
| A7 协作模式 | 批处理习惯、攒批窗口、验收口味 | 紧凑码 + 文本 | 推断校准 | 中 |
| A8 对注入的容忍 | 主动建议欢迎度、打扰阈值、hook 总开关 | 紧凑码（**hook 层的直接输入**） | 裁定 + 抱怨校准 | 中—高 |

### 3.3 编码形态三选一（紧凑码 / 自由文本 / 混合）的判据

| 形态 | 收益 | 代价 | 适用判据（全部满足才用） |
|---|---|---|---|
| **紧凑码** | ① hook 代码层可直接判定（`dim.aesthetics.base_color == blue`）② 注入时单 token 廉价 ③ 值域封闭可校验非法值 | 表达力低；维度漂移时码表维护成本；过度压缩丢失语境 | 值域**封闭枚举** + 稳定性**高** + 有代码判定/注入需求（A1/A3/K3/K5/K7 属此） |
| **自由文本** | 表达力全；人读友好 | 代码判不了；注入按字节付费；长文本挤 prompt | 值域开放 或 低稳 或 无代码判定需求（K2 领域知识、A5 称呼属此） |
| **混合（码 + 注 + 证据）** | 码给机器/注给人/证据给审计 | 文件稍大 | 默认推荐——凡用紧凑码的维度都带一句文本注与证据指针 |

**为什么 MBTI 式短码不能到处用**（正面回答敢不敢）：MBTI 本身在心理学界效度有争议（二分压缩连续量，置信度中——书内常识，未联网核），它好在**传播与自描述**，不是好在信息保真。我们的紧凑码服务对象不是传播而是**代码判定与注入预算**——低稳维度压成码 = 码表追着状态跑（K7 若全枚举「忙/闲/冲刺」会一天三变）；开放维度压成码 = 丢信息。所以码只给「封、稳、要用」三者交集，预计占比小，这是设计选择不是偷懒。

### 3.4 每维四件套：置信度、更新机制、时态、证据

维度文件的每个值必带（frontmatter 字段，机器可读）：

| 字段 | 取值 | 说明 |
|---|---|---|
| `confidence` | `high / medium / low`（显式裁定默认 high；推断默认 medium；三次以上推断互证可升 high） | 低置信值**可注入但标注**——注入文本带「（推断，待确认）」，agent 不得当既定事实执行高风险动作 |
| `source` | `explicit（裁定）/ inferred（推断）` | 推断值必须挂证据 |
| `evidence` | 检索库指针（session_id + ts）或裁定令日期 | 可追溯链（Zep episode→fact 同款，置信度高） |
| `valid_at / updated_at` | ISO 时间戳 | 双时态简化版：`updated_at` 改写即旧值失效进归档（借 Zep 事实失效思想；不做完整四时间戳——个人 agent 量级用不上，判「重」不借，见 §6） |
| `ttl` | 仅 K7 类短稳维度 | 过期未刷新则值降级为 `stale`，注入时跳过并提醒整理 |

更新机制两路分工（作者令①「对话推断校准 vs 显式裁定」的落法）：

- **显式裁定路**：作者令、swatch 测试拍板、目标入账——直写，`source=explicit`。红线集（A1）**只走此路**。
- **推断校准路**：对话中观测到信号（抱怨词表、行为模式）→ 先进**证据池**（文件尾「待裁定观测」节，记忆三问的 reusable/hard-to-obtain 准入照用）→ 攒批呈作者确认或达自动升级阈值（同维度 ≥3 独立证据且互不矛盾）才转正。LLM 不得静默改已裁定值——与 memory-consolidation「取代纪律」（append+remove 成对）同构。一期增补（附录 C）：证据池观测带四值证书；裁定转正走 `stand-profile decide`，必须带 quote，D 号进决策轴。

**K8 关系画像单列伦理护栏**（Muse 教训，置信度高）：关系维度只记「与 Kai 协作相关的事实」（师弟提供粒子谱数据、Zcode 是外部编码代理），**不推断未说出口的目标、不建非协作关系的第三方档案**——Meta 因关系画像按小时更新被 WIRED 点名「监视胜于帮助」，这条红线直接写进 A6 记忆伦理维度。

## 4. 存储与检索

### 4.1 落点：新目录 `~/.stand/profile-db/`（不扩 memory-db）

| 方案 | 判定 | 理由 |
|---|---|---|
| **A. 新目录 `~/.stand/profile-db/`（推荐）** | ✅ | 与 memory-db 并列同级、职责正交；每维一 Markdown 文件（frontmatter+正文），人可读可手编——延续 User.md/Soul.md 人可读哲学与 Muse「画像即文件」验证（置信度高）；frontmatter 即机器可读层，零依赖可解析；与方案档 JSON 同数据根，服务习惯一致 |
| B. 扩 memory-db（同 SQLite 加表） | ❌ | 画像不是消息语料，FTS5 对它无意义；检索 CLI 单一职责被稀释；维度文件不可人读直接编辑 |
| C. 单一 YAML/JSON 大文件 | ❌ | 回到 User.md 混装老路；并发整文件改写易冲突；git diff 不可读 |

目录形态：

```
~/.stand/profile-db/
  kai/                     # 人的画像命名空间
    K1-identity.md
    K3-aesthetics.md       # frontmatter: base_color: blue (compact code) …
    …
  agent-requirements/      # 对 Agent 要求命名空间
    A1-redlines.md         # 每条红线一个码：A1-01 禁自重启 …
    …
  _archive/                # 被取代值归档（同刻落盘，查回照 memory-archive 口径）
  INDEX.md                 # 维度总目（人读导航 + 每维一行机器摘要）
```

### 4.2 检索 API 形态：`tools/stand-profile`（stand-recall 的姊妹 CLI）

零第三方依赖、同 stand-recall 的 Python3 标准库路线（`tools/stand-recall:68` 同款约束）：

```
stand-profile get kai.K3                 # 单维全量（frontmatter+正文+证据）
stand-profile get kai.K3 --field base_color   # 单字段（代码层判定用）
stand-profile list [--ns kai|agent-requirements]
stand-profile match redline=A1-01        # 反查：哪些维度带此码
stand-profile evidence kai.K3.base_color # 证据链 → stand-recall 指针
stand-profile calibrate                  # 待裁定观测批一览（推断校准路入口）
```

引擎侧（实施批）：画像快照拼装器按轮组装**常驻小节**——只装「高稳 + 本轮相关」维度（Letta 常驻 vs 按需分层思想：system/ 常驻、长尾按需 read），默认注入预算硬顶（如 1KB），超出按维度优先级裁剪；K7 类短稳维度随轮刷新。注入侧永不截断原则（memory-consolidation 预算纪律）在画像快照这里反过来：**写入侧（落盘）不截，注入侧（进 prompt）必裁**——检索库原文永不裁，画像摘要按需给。

## 5. hook 式主动注入

> 结论先行：hook = `~/.stand/hooks/` 下一文件一 hook（frontmatter 定义 + 正文要求文本）。注入通道**零新机制**——复用既有 `<system-reminder>` 随用户消息注入腿（`reminders.scala:41` 收集、`pipeLlmCall` 挂载，渠道壳同机制）。触发双层：代码第一层管「确定性事件匹配」，JeV 第二层管「代码写不清的复杂条件」；两层互相可否决，触发日志 `(hook_id, event_id)` 幂等防双注。写入权限三档（裁定 / 提议批准 / 受限自写），用户可查可删可审计——记忆伦理借 instinct 反面教材进 V1。

### 5.1 hook 定义格式（代码条件 + 自然条件两栏）

```yaml
---
id: xhs-changelog-on-release            # 稳定键，日志/审计引用
description: 发 beta 后写 XHS 发布用 change log
when_code: |                            # 第一层：确定性条件（代码判定）
  event.type == "release-published" and event.channel == "beta"
when_llm: |                             # 第二层：自然语言兜底条件（JeV 判定）
  本次发布含用户可见的功能改动，且不是纯修复补丁
inject: |                               # 注入文本（要求喂给 agent）
  用户要求：为本版本写一份小红书发布用 change log（人面子 ≤8 行、
  结论先行、配图 1 张），呈用户确认后再发。
priority: normal                        # normal | high（同轮多条时排序）
cooldown: 7d                            # 冷却窗：同 hook 同条件去抖
author: kai@2026-10-30                  # 写入者（伦理审计字段）
---
（正文可写更长要求全文、示例、边界）
```

两栏语义分工：

- `when_code` 写得动 ⇒ 代码层判定，**LLM 层不跑**（省 token、零误判面）。
- `when_code` 为空或代码层判「可能命中但不确定」⇒ 事件上下文打包送 JeV 判 `when_llm`。
- `when_llm` 也空 ⇒ 纯代码 hook。

### 5.2 注入通道：既有机制盘点与复用

仓内**已有**这条腿，盘点如下（呈作者时强调「零新机制」）：

| 既有件 | 锚点 | hook 复用点 |
|---|---|---|
| `SystemReminders.collectAll` | `src/main/scala/nebflow/core/reminders.scala:41` | hook 提醒作为新 reminder 类型进收集器 |
| 随用户消息挂载 | `AgentCore.pipeLlmCall`（time reminder 持久化先例，`reminders.scala:35-39`） | hook 提醒**非持久**（同 schedule/plugin-surface 提醒口径），防会话膨胀 |
| 渠道壳隐藏注入 | `<system-reminder>` 包裹微信/飞书消息（`tools/stand-recall:122-133`） | 同款包裹即「随用户消息隐藏注入」——用户看到的是自己的消息，要求只进 agent 上下文 |
| 提醒类型节流 | `reminders.scala:59-80`（schedule/delta/change-detection 各带节流） | hook 按 `cooldown` 与 change-detection 同款节流 |
| 语料排除 | `SKIP_TEXT_PREFIXES` / `INJECTED_SOURCES`（`tools/stand-recall:106-112`） | hook 提醒登记同口径，不进检索语料 |
| Schedule 条件触发 | `ScheduledTaskModel.scala:31-71`（条件触发/空转降频，引自认知方案稿 §6.4） | hook 的时间/周期类事件源 |

前端呈现：hook 提醒属引擎注入件，UI 不展示（隐藏注入成立）；但**触发留痕**——`hook fire log`（`~/.stand/hooks/_log.jsonl`，append-only：hook_id/event_id/两层判定结果/注入时间），作者问「你刚才为什么提醒我写 change log」必须能一句话答回（可追溯链思维与画像证据链同构）。

### 5.3 双层触发的分工、失败传递与幂等

**事件源**（代码第一层可匹配的对象，全部事件驱动——**零 wall-clock 轮询**，守零超时红线）：`release-published`（发布管线事件）、`schedule-fire`（Schedule 触发）、`user-message`（正则/紧凑码匹配——如消息命中 A1 红线码的反模式）、`session-lifecycle`（重启后首会话）、`file-change`（工作区落盘事件，FileChangeLedger 既有件）、`profile-change`（维度值改写）。

**分工与判定真值表**：

| 代码层 | JeV 层 | 结果 | 说明 |
|---|---|---|---|
| 未命中 | 不跑 | 不注入 | 常态，零成本 |
| 命中 | 通过 | **注入** | 两栏齐备时双确认 |
| 命中 | 否决 | 不注入＋日志记 veto | **LLM 可否决代码**——防代码条件过宽（如纯修复补丁被 release 事件误命中） |
| 未命中/不确定 | 通过 | **注入** | LLM 兜底捞回代码写不清的复杂条件（作者令⑤的落点） |
| 未命中/不确定 | 否决/不跑 | 不注入 | |

**失败传递**（每行都守「网络不可达→退避等待恢复而非判死」，#692 同款）：代码层异常 → 该 hook 本轮跳过记日志，不拖其他 hook；JeV 层异常/不可用 → **默认不注入**（fail-closed：主动注入是打扰面，宁漏勿滥——与 instinct 的信任飞轮反向 lesson 一致），记 `llm-unavailable`，事件保留待复判；JeV 判超时（真卡死无进展才按 Soul.md 恢复条款处理）→ 同 fail-closed。

**防双注幂等**：触发日志键 `(hook_id, event_id)`——同一事件同一 hook 全程只注入一次（crash 重启后按日志去重）；`cooldown` 窗内同 hook 不重复触发；LLM 否决不消耗 cooldown（下次事件重新评估）。

### 5.4 写入权限与可见性（记忆伦理）

| 档 | 谁能写 | 约束 |
|---|---|---|
| L1 作者裁定 | Kai 本人（面板/命令/一句话令 agent 落盘） | 无约束；`author` 记 kai |
| L2 agent 提议 + 批准 | 我/任何 delegate | 先进「hook 提议池」呈作者批准才生效（同 §3.4 推断校准路的批准门禁）；`author` 记提议者 |
| L3 受限自写 | 我（运行中 agent） | 仅限「提醒级」hook（inject 文本是建议/要求，不触发外部动作）；**禁自写「动作级」hook**（会写文件/发消息/调 API 的）；全部 L3 集中一区、作者可一键全审 |

用户侧权利（V1 即做，不二期——instinct 因删除权缺失被 TechCrunch 点名，置信度高）：

- **查**：`stand-hook list` / `stand-hook show <id>`；面板 hooks 区（与方案档选档器同 #1132 二期 UI 批）。
- **删**：删文件即失效；已 fire 的注入不可撤回（已在历史上下文里），但 `_log.jsonl` 永久可查「它何时影响过我」。
- **总闸**：A8 维度「对注入的容忍」的总开关进画像库——作者说「别主动提醒我」⇒ 一键全局静默（LLM 兜底层同闸）。
- **审计**：每次注入留痕可答回（§5.2）；每月记忆审计（memory-consolidation 流）把 hooks 目录纳入扫描面——僵尸 hook（N 月未 fire 且作者未点名）呈清理建议。

## 6. 同类产品调研（借什么 / 不借什么）

> 调研时点 2026-10-30；Muse/instinct 经联网核（多源交叉），Mem0/Zep/Letta 核官方文档+论文（一手）。逐条置信度附注；未官方确认者标中。

### 6.1 Muse（Meta，2026-09 发布，Nat Friedman 主导；置信度高）

- **是什么**：消费级个人 AI agent，「世界上第一个个人 AI agent」定位，跨 WhatsApp/网页/眼镜端（Meta 官方发布文，about.fb.com 核到）。
- **记忆机制**：文件化三件套——`Memory.md`（用户画像）/`Soul.md`（人格）/`Identity.md`（身份），App 内可直接编辑删除；**forget skill**（遗忘是一等操作）；记忆导入（竞品聊天导出 zip 冷启动）；夜间「dream」复盘写记忆（filesystem dump 经 The Verge 报道，Meta 未官方确认——**置信度中**）；**关系画像**按小时级推断更新（Magica/Cybernews 调查，Meta 未否认）。
- **借**：①画像即人可编辑文件（我们的 Markdown+frontmatter 同路，独立验证）；②forget 与写入对等（我们的删除权 V1）；③dream 式后台复盘（对应我们的推断校准路——已有 memory-consolidation 压缩双轨，可复用而非新造）。
- **不借**：关系画像的激进推断（未说出口的目标也建档案）——隐私反噬已被 WIRED 实锤，A6/K8 红线直接来自这条（§3.4）。

### 6.2 instinct（Spear Street / Noah Shinn，Reflexion 一作；置信度高）

- **是什么**：邀请制个人 agent，经 iMessage/WhatsApp/电话触达，$350M 融资 $2.5B 估值（TechCrunch/Yahoo 多源）；主打 **proactivity**——「跟进你丢下的线程、提醒忘掉的事」（评测站 assistantbenchmark 15 维中 Proactive 8/10、Memory 9/10）。
- **记忆/主动机制**：持久云电脑持有全上下文；主动打电话发短信（CEO 访谈一手）；动作经解耦 watchdog 审查可否决；信任北极星 =「交出敏感数据的时间」。
- **借**：①主动触达作为产品形态（hook 是它的注入式轻量版）；②watchdog 解耦审查（对应我们的 LLM 层否决权 + 动作级 hook 禁自写）；③算力成本自觉——ambient proactive 的算力消耗是 CEO 自认核心挑战，我们的事件驱动触发（非轮询）恰是该问题的正解（设计论据 +1）。
- **不借**：邮件明文长期留存、删除权缺失、ToS 过宽——大量负面报道的来源；「收集 keystrokes/cursor/精确位置」级别的数据胃口。全部列入反面清单。

### 6.3 Mem0（记忆层中间件事实标准；置信度高——官方文档+arXiv:2504.19413）

- **数据模型**：ADD-only 抽取事实 + `user_id/agent_id/app_id/run_id` 四作用域 + metadata 过滤；v3 平台原生 Graph Memory（共现实体、无类型化关系边）。
- **借**：①**Profiles（beta）= 我们的直接参照**——用户定义 JSON Schema、每实体一个结构化增量更新画像对象、约每 10 条消息自动折叠新记忆进画像、读时带状态机（succeeded/pending/insufficient_data）；官方定位「search 答他说过什么，profile 一次读答他是谁」与本稿 §2.2 分工逐字同构；②四作用域+metadata = 我们命名空间+frontmatter 字段的同款；③ADD-only + 显式 supersede = memory-consolidation 取代纪律的同款。
- **不借**：①无 proactive 注入（query-driven，注入全靠应用拼 prompt）——作者令④正是补它不做的事；②ADD-only 的陈旧事实需 Dream 后台机制补，自建成本高（我们直接走「改写即归档」）；③图记忆无语义关系边，对维度间关系表达力不足；④Profiles 仍 beta 需申请——成熟度风险，自建不等它。

### 6.4 Zep（时序知识图谱派；置信度高——官方 v4 文档+arXiv:2501.13956）

- **数据模型**：episode 摄入 → 实体/fact 抽取 → 实体解析 → 摘要；**双时态四时间戳**（created_at/valid_at/invalid_at/expired_at），事实失效一等公民（「不再爱 Adidas 改爱 Puma」自动作废旧边）。
- **借**：①**事实失效机制**——我们简化为「改写即归档 + updated_at」（§3.4），思想照抄、完整四时间判「重」不借；②**Context Block 注入形态**——一次调用拿回拼好的 prompt-ready 块、模板占位符控格式、recall-first 默认策略——hook 的 inject 文本形态与快照拼装器直接对标；③episode→fact 可追溯链 = 我们的 evidence 字段；④Context Block 不进 system message 而作为 data 传入（防第三方数据污染指令层）——hook 注入文本同口径：走 reminder/data 通道，不进 system prompt 指令区。
- **不借**：企业级 Context Lake/Konig/RBAC 治理全家桶（个人 agent 用不上）；LLM 抽取+实体解析整条 pipeline 的 per-write 成本。

### 6.5 Letta / MemGPT（自我管理记忆派；经典设计置信度高=arXiv:2310.08560，当前 MemFS 形态置信度中高）

- **数据模型**：memory block（label + token 上限，agent 函数调用读写）；core memory 常驻 system vs archival 按需取读；memory pressure 协议（70% 警告→agent 自主整理→flush 递归摘要）；当前形态演化为 git 版本化 MemFS（`system/` 常驻，其余按需 read）。
- **借**：①**block 限长 = prompt 预算护栏**——我们画像快照 1KB 硬顶的直接理论来源；②常驻 vs 按需分层（§4.2 注入策略）；③dreaming 后台复盘（与 Muse 同路，我们走压缩双轨复用）；④`/remember` 显式教学通道 = 我们的显式裁定路低摩擦入口。
- **不借**：①self-editing 全量依赖模型自律（弱模型下记忆质量不稳——我们「推断进证据池、裁定才转正」正是对此的收紧）；②FIFO 逐出+递归摘要（画像库是结构化小文件，无队列压力问题）；③MemFS/git 架构偏重且与 harness 强绑定。

### 6.6 汇总：五家对照我们五件事

| 我们要做的事 | 最佳参照 | 一句话 |
|---|---|---|
| 维度作用域+字段过滤 | Mem0 | 命名空间+frontmatter 同其 user_id/metadata |
| schema 化画像对象 | Mem0 Profiles | 直接同构（beta 风险=自建理由） |
| 事实失效/纠错 | Zep | 改写即归档（简化版双时态） |
| hook 注入 API 形态 | Zep Context Block | prompt-ready 块+模板占位符+recall-first |
| prompt 预算护栏 | Letta block 限长 | 快照硬顶+常驻/按需分层 |
| 后台复盘整合 | Letta dreaming / Muse dream | 压缩双轨复用，零新机制 |
| 主动行为产品形态 | instinct / Muse | 事件驱动触发是 ambient 算力问题的正解 |
| 记忆伦理（查/删/审） | instinct 反面教材 | V1 就做，不做二期 |

## 7. 分期与验收

> 结论先行：**维度库先行**。理由三条：①hook 需要落脚点——维度值既是注入内容又是代码层的判定变量（`when_code` 匹配的就是紧凑码），没有画像库的 hook 只会注入无治理的散装文本；②维度库是纯约定零代码（Markdown+frontmatter 即日可写），与认知方案一期同路数，起步成本 ≈0；③画像库独立就有价值（检索前置成本消失），hook 晚一期不亏。反之 hook 先行 = 在沙地上起楼。

### 一期：维度画像库（纯约定零代码，即时可起步）

| 项 | 内容 | 验收 |
|---|---|---|
| 目录与格式落地 | `~/.stand/profile-db/` 建命名空间+首批维度文件（K1-K5、A1-A5 先行；K6-K8、A6-A8 随用随补）；`INDEX.md` 总目 | 每维文件四件套齐全（confidence/source/evidence/updated_at）；check 脚本/人工抽 3 维可回溯到检索库原文 |
| User.md/Soul.md 迁移 | 两文件现有内容逐条归入维度文件；两文件降级为「指向 profile-db 的导航页」（保留作注入锚，内容改一行引用） | User.md/Soul.md 无散落事实，全部维度化；迁移零丢失（逐条对账） |
| 写入纪律 | memory-consolidation 三问+取代纪律平移进维度写入；推断校准路（证据池→批准/阈值转正）进派发 brief | 新维度值 100% 带 source 标注；抽查无静默改写裁定值 |
| stand-profile CLI | `tools/stand-profile` 五命令（get/list/match/evidence/calibrate），零依赖 | 每个命令 --help 可跑；`get --field` 返回值与文件一致 |
| 检索联动 | 认知稿「不知道先自己查」红线扩展：查「他是谁/要我怎样」先 stand-profile，查「当时怎么定的」再 stand-recall | 派发 brief 更新；抽查两轮对话按序执行 |

### 二期：hook MVP（先代码层，后 LLM 层）

| 项 | 内容 | 验收 |
|---|---|---|
| hook 定义与目录 | `~/.stand/hooks/` + frontmatter 两栏格式 + `_log.jsonl` | XHS change log 示例 hook 落盘可解析 |
| 注入通道接线 | hook reminder 类型进 `SystemReminders.collectAll`，非持久随用户消息注入 | e2e：发布 beta 事件后下一轮用户消息携带 hook 要求；前端不可见；检索库不收（语料排除口径生效） |
| 代码第一层 | 事件源五类接线（release/schedule/user-message/lifecycle/file-change）；`when_code` 判定器 | 每类事件源一个 spec 测试；误判率 0（确定性条件） |
| 幂等与节流 | `(hook_id,event_id)` 日志去重 + cooldown | 重启后同事件不双注（日志对账）；cooldown 内不重复 |
| 权限三档 | L1/L2/L3 写入路径 + L3 仅限提醒级 | L3 动作级 hook 写入被拒并记审计 |
| 伦理面 | stand-hook list/show/delete；A8 总开关维度进画像库 | 删文件即失效；总开关静默后两层都不注 |

### 三期：JeV 兜底层 + 与 #1132 二期汇合

| 项 | 内容 | 验收 |
|---|---|---|
| LLM 第二层 | `when_llm` 判定器（事件上下文打包→JeV→通过/否决）；fail-closed 默认 | 复杂条件 hook（「用户可见改动」类）命中正确；JeV 不可用时不注入且记 llm-unavailable |
| 快照拼装器 | 画像快照常驻小节（高稳+本轮相关，1KB 硬顶，K7 随轮刷新） | 多轮对话快照字节稳定不超顶；维度优先级裁剪可审计 |
| 方案档汇合（#1132 二期） | 选档器/角标批接入画像库：状态维度（K7）作选档输入；A3 汇报维度与档参数联动；hooks 面板进同批 UI | 切档即时生效；hook 总开关在面板可见可操作 |

**集成顺序说明**：认知方案档案二期（选档器/角标/打卡 UI/周报生成器）已入台账（认知稿 §8 二期表），它与画像库的汇合放三期而非抢二期——因为二期 hook MVP 期间画像库已能提供选档判定输入的原始形态（K7 文件值），方案档产品化时再接不迟；强行并行两批会让 #1132 二期批范围膨胀。

## 8. 未决项（带默认建议，呈作者裁定）

1. **维度总量级**：先 16 一级维度/60+ 指标，扩到「几十上百」的节奏？→ 建议：随用随补不设上限，INDEX.md 滚动登记；一级维度结构（8+8）请作者裁定是否照单。
2. **紧凑码码表形态**：frontmatter 内联枚举 vs 中央码表文件？→ 建议：中央码表 `profile-db/CODES.md`（单点防漂移，双真源教训见认知稿 §3.3 备选 C）。
3. **快照注入预算**：1KB 硬顶是否照办？→ 建议：照办（Letta block 限长同源），数值实施批可调。
4. **K7 状态维度的刷新责任**：随轮由引擎刷 vs 会话开头一次？→ 建议：会话开头一次+事件触发补刷（零轮询）。
5. **hook 事件源范围**：五类事件源是否照单？`user-message` 正则匹配有性能/误判面。→ 建议：一期先 release/schedule/lifecycle 三类，user-message/file-change 后进。
6. **LLM 层判定预算**：每次判定一次小调用，事件密集时成本？→ 建议：JeV 判定复用当轮主模型链，不单独开小模型（省链路费，延迟并入轮内）；事件密集期按 Schedule 空转降频同款节流。
7. **K8 关系画像开不开**？→ 建议：开但只收协作事实+颗粒度授权（§3.4 护栏）；若作者有顾虑，一期只建空目录不建条目。
8. **维度库与任务台账的边界**：目标类维度（K6）与任务台账（tasks-v2）有重叠。→ 建议：K6 只存「目标画像」（方向/阶段/截止），执行条目归台账；台账状态不反写画像（单向）。
9. **方案档 JSON 与 profile-db 是否合并为一个文件服务**？→ 建议：不合——呈现参数与认知参数生命周期不同（档随令换，维度随观测漂移）；同数据根即可。

## 附录 A：维度文件实例（K3 审美）

```markdown
---
dim: kai.K3
name: 审美与口味
updated_at: 2026-10-30T15:30:00+08:00
fields:
  base_color:
    value: blue            # 紧凑码：blue | green | other
    confidence: high
    source: explicit       # 2026-10-07 swatch 测试裁定
    evidence: "swatch 测试现场；检索指针 session d7c3cb54 @ 2026-10-07"
  ui_density:
    value: compact         # compact | comfortable | spacious
    confidence: medium
    source: inferred       # 证据×2（10-03 UI 令、动效克制反馈）
    evidence: "session d7c3cb54 @ 2026-10-03; @ 2026-10-07"
  motion_tolerance:
    value: restrained      # full | main-only | restrained
    confidence: high
    source: explicit       # 2026-10-03 裁定
---
## 文本注（给人读）
蓝色系优先，绿色也顺眼。动效仅主窗口，subagent 窗口克制省资源；
动画纯文本风格。AskUserQuestion 口味：swatch 内嵌预览、dependsOn 条件分支。
## 待裁定观测（推断校准证据池）
- [低置信] 报告类文档似乎偏好「先图后文」——证据 1 条，未达转正阈值。
```

## 附录 B：hook 文件实例（XHS change log，作者令示例原样落格式）

见 §5.1 定义块——`when_code` 锁 beta 渠道发布事件，`when_llm` 兜「用户可见改动」的语义判断，cooldown 7d 防连发双注。该 hook 建议作者以 L1 档亲自落盘作首个实例。

## 附录 C：张安齐借鉴吸收（2026-10-30 作者批随行，一期已落地）

> 来源：DOSTEL 仓 `docs/20261030_161826_zaq-memory-retrieval-recon.md`（dllm 双时态记忆体系 + knowledge-retriever 裁决内核侦察）。未决项 1-9 作者已按推荐默认全批（其中 #2 中央码表落 `profile-db/CODES.md`）。三条借鉴已工具化进一期：

1. **置信纪律（替代/细化原「改写即归档」表述）**：维度值置信**只升不降不隐**——收紧（升级）直写；放松（降级）不静默改写，旧值同刻进 `_archive/` 尸档留痕后重裁（与 dllm `knowledge/` 策展「降级走尸档」一致）。「改写即归档」只管**值被取代**（双时态简化版原判不变）；**置信降级**另有尸档通道——两者并行不悖，本条替代原稿把两者混说的表述。工具闸：`stand-profile decide` 对降置信写入直接拒（退出码 1 + 指路尸档）。
2. **证据四值证书 + judge 协议**：verified / supported / contradicted / insufficient（kr kernel 同款）。推断（source=inferred）**封顶 supported**；contradicted **永不覆盖现行值**（只留决策轴；旧值被推翻走尸档 + ⇢ 新裁）。**入库裁决必须带 quote**（原文摘录）——无 quote 拒入；证据缺口显式标「证据待补」（insufficient），不许静默（一期实证：`kai.K3` 待裁定观测「先图后文」检索库未核到原话，标 insufficient）。
3. **号分配器（tl_next 式）**：决策轴 `profile-db/_decisions.md` append-only（`D-xxx | 日期 | 类型 | 内容 | 影响`，取代指针 ⇢）；D 号由 `stand-profile decide` 分配——**flock 内「重读 → 全文件正则 max+1 → 写占位行」**（dllm `tools/tl_next.py` 同款；其 58 行 docstring 记载的病史——stale-tail 撞号、双会话同号——用锁内重读根治）。

**一期落点**：数据根 `~/.stand/profile-db/`（INDEX.md 总目 + CODES.md 中央码表 + `kai/K1-K5` + `agent-requirements/A1-A5` + `_decisions.md` + `_archive/` 尸档）；CLI `tools/stand-profile`（get/list/match/evidence/calibrate/decide/check 七命令，零依赖）；User.md/Soul.md 已降级为导航页（原文件尸档 `_archive/nav-migration-20261030/`，逐条对账零丢失）。

## 接入图谱

- 认知方案档案（消费方）：[[20261030_human-readable-presentation.zh-CN]] §6 五档 profile · 后台通知面 [[20261030_background-task-lifecycle.zh-CN]]
- 记忆底座：memory-consolidation skill（三问准入/取代纪律）· `tools/stand-recall`（事件考古）· `~/.stand/User.md` · `~/.stand/Soul.md`
- 注入通道：`src/main/scala/nebflow/core/reminders.scala` SystemReminders · `AgentCore.pipeLlmCall` · `tools/stand-recall:106-133`（语料排除+渠道壳）
- 事件源：`src/main/scala/nebflow/core/scheduler/ScheduledTaskModel.scala` · FileChangeLedger · MemoryRecallDaemon（守护样板）
- 词条承接：[[glossary#^concept-first-use]] · [[index]]
