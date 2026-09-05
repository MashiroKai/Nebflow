> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# System Reminder 机制审计报告（2026-08-20）

- 日期：2026-08-20
- 审计人：Explorer（用户 12:10 催收补交）
- 性质：只读审计，不改代码
- 基准：2026-08-12 缓存优化定案（下称「定案」）
- 状态：完成即冻结（阶段文档）

## 0. 结论摘要

| 项 | 结论 |
|----|------|
| **漂移条数** | **6 条**（3 重大 / 2 中 / 1 小），另有 1 条已修复的历史漂移 |
| **最大成本项** | **tasks reminder 全量注入**：无变更检测、无截断、无条数上限，20+ 中文任务 ≈ 700 token/turn × 每 user turn 一次；今日实测 39 次注入 ≈ 27K token |
| 第二成本项 | **Environment 差分粒度 = 全块**：chatWidth 抖动（用户 resize 窗口）驱动，每变一次注入完整 9 字段表格 ~1.9KB；今日 35 次 ≈ 17K token |
| 定案核心目标 | **前缀缓存稳定已达成**（reminder 追加在消息尾部，不破坏 systemStable 前缀；systemStable 仅生命周期重建） |
| 设计好的部分 | snapshot 差分框架、schedule 摘要化（take 5 + take 60）、time 持久化 + prune 20 条，均符合定案 |
| 今日合计浪费 | request-only reminder ≈ **44K token 全额计费**（且因不持久化，永远无法被后续 turn 缓存复用） |

![审计图表](assets/20260820_system-reminder-audit.svg)

## 1. 现状取证（代码 + 日志）

### 1.1 注入点与管线

**构造**：`src/main/scala/nebflow/core/reminders.scala`（`SystemReminders.collectAllIO`，L70-92）
**调用**：`src/main/scala/nebflow/agent/AgentCore.scala` pipeLlmCall（L331-468）

每 turn 组装 `LlmRequest` 时，若为 user turn（最后一条消息为 User 且非 tool result，L395-397）：

```
request.messages = state.messages ++ contextMsg ++ branchMsg ++ maintenanceMsg   (L459-460)
```

- **追加在消息列表尾部**（User 角色）→ 不破坏 system prompt 前缀缓存 ✅
- **request-only 不持久化**（contextMsg 不进 session history）→ 每 turn 都是全新 input token，**永远无法被后续 turn 的缓存复用**——这是「不修改原始上下文」设计（定案原文）的直接代价，定案已接受，但体积失控（见 §3）
- 例外：time reminder 持久化为真实 User 消息 + `pruneTimeReminders` 保留最近 20 条（L159-178），防历史膨胀 ✅

### 1.2 reminder 类型清单（触发条件 / 体积实测）

| 类型 | 触发条件 | 变更检测 | 体积（实测） | 今日注入次数 |
|------|---------|---------|-------------|------------|
| time | 每 user turn 必带 | 无（必然注入） | 39B 固定 | 2,307（含所有 agent） |
| **tasks** | user turn ∧ depth=0 ∧ active 任务非空 | **无——每 turn 全量** | 0.5-2.2KB（随任务数增长，20+ 任务实测 2,223B） | **39** |
| **environment** | envInfo 与 snapshot 不同 | 有（快照差分），**但粒度=全块** | 1.7-1.9KB（9 字段全表） | **35** |
| devices | 同上 | 有（快照差分），全块 | ~1-2KB | 今日 0 |
| sessions | 同上 | 有（快照差分），全块 | ~1-2KB | 今日 0 |
| language | 快照差分 | 有 | 一句话 | 今日 0 |
| schedule | pending 定时任务非空 | 无（非空即注入），**但已摘要化**：take 5 条 × content.take(60) | ~1.5KB 封顶 | 14 对象（3 日累计） |

数据源：`~/.nebflow/logs/nebflow.log`（nebflow.reminders logger，logAndReturn 每次注入打点）+ `logs/router/objects/`（内容寻址消息对象）。

### 1.3 tasks reminder 渲染（漂移核心证据）

`TaskStore.renderForPrompt`（TaskStore.scala L369-409）：

```scala
val active = allTasks.filter(t => t.status == Pending || InProgress || NeedsConfirmation)
// 无条数上限；subject / activeForm 全文渲染；树形递归全部展开
sb.append("## Current Tasks\n\n")
sb.append("Your task list is below. ... needs_confirmation ... [打回任务] ...\n\n")  // ~300B 指令文本
sb.append(s"$indent#${t.id} $statusIcon ${t.subject}$activeStr\n")
```

实测样本（08-19，2,284B）——20+ 任务完整中文标题 + activeForm：

```
#277 [in_progress] 待办区功能：Reminders 风格任务面板（agent 写/用户点完成） — 实施待办区浮窗
  #300 [in_progress] 107 API 稳定性测试：摸清限流/并发/错误模式，产出稳定使用方案 — 摸底 107 API 稳定性特征
  #303 [in_progress] 文件浏览器拖拽功能：聊天框/移动/浏览器打开/桌面保存 — 设计并实现文件浏览器拖拽功能
  ...（共 20+ 行）
```

AgentCore.scala L333-338 注释自认：「the task list is fetched every turn ... travels as a user-turn reminder」——**取了全量、无 diff、无摘要**。

### 1.4 environment 差分的实际触发根因：chatWidth 抖动

`envInfoSection`（PromptSections.scala L557-562）渲染 order<200 的文件 sections（即 Environment 表）。变化输入中，version/PID/port 来自 sys props（会话中不变），**唯一 mid-session 可变字段 = chatWidth**（AgentCore.scala L340-344 注释自认）。

objects 中全部 env reminder 对象的 chatWidth 值序列（内容寻址去重后按首次写入时间）：

```
08-17: 385→405→601 | 481→532→490→547→356→543→320→495×11→629×5   （一天 12 种取值）
08-18: 908→594 | 664→528→445→642 | 649→533                        （窗口反复调整）
08-19: 664→664→383→664→664                                        （664→383→664 来回抖动）
08-20: 664×3 + 当前会话 ~0px
```

**用户 resize 聊天窗口 = chatWidth 变 = envInfo 字符串变 = 差分触发 = 注入全量 9 字段表**。今日 10:00 后用户活跃时段几乎每条 user turn 都触发（35 次）。9 个字段中 8 个从未变化，注入的是冗余信息。

且 systemStable 中的 Environment 表保持 lifecycle 时的旧值不更新——reminder 承担「纠正」职责，但同一字段被反复纠正（664→383→664），说明部分抖动毫无信息量。

## 2. 定案 vs 实现逐条对照（漂移清单）

定案原文：「system prompt 中只放稳定内容（prefix + agent.md + catalog + memory），动态信息（Environment/Devices/Sessions/Language）初始注入后**变更走 system reminder 告知**（不修改原始上下文），生命周期节点（压缩/重启）更新。Task List 不进 system prompt 只在用户输入时 reminder。Memory 只在生命周期节点更新。」

| # | 定案条款 | 实现现状 | 判定 |
|---|---------|---------|------|
| 1 | system prompt 只放稳定内容 | systemStable 仅 lifecycle 重建（新会话/压缩/重启，AgentCore L347-350），byte-for-byte 复用 | ✅ 符合 |
| 2 | 动态信息「变更走 reminder **告知**」 | 差分框架存在（SystemStableSnapshot），但触发后注入**全量块**而非变更 delta——「告警」变「全文重述」 | ⚠️ **漂移 D1（重大）**：粒度错位 |
| 3 | 同上——Environment | chatWidth 抖动驱动差分频繁触发；该字段仅影响 Markdown 表格渲染宽度建议，价值极低而触发频率最高 | ⚠️ **漂移 D2（重大）**：低价值字段 × 高触发频率，最差性价比 |
| 4 | Task List「只在用户输入时 reminder」 | 字面符合（user turn 注入、不进 system prompt）；但**全量、无变更检测、无截断、无条数上限、含 ~300B 重复指令文本**，与定案「system prompt 精简」的整体精神相悖 | ⚠️ **漂移 D3（重大）**：符合字面，违背精神 |
| 5 | Devices/Sessions/Language 变更告知 | 快照差分 + 全块注入；实测今日 0 次触发（这三类确实低频） | ⚠️ 漂移 D4（中）：粒度同 D1，但因低频实际影响小 |
| 6 | Memory 生命周期节点更新 | memoryBlock 仅 lifecycle rebuild 时消费（AgentCore L370-373 注释） | ✅ 符合 |
| 7 | （定案隐含）reminder 应轻量 | schedule reminder 已摘要化（take 5 × take 60）——**唯一符合「告警式」设计的类型**，可作范式 | ✅ 范式存在但未推广 → 漂移 D5（中） |
| 8 | （隐含）time 每轮告知 | 39B 固定 + 持久化 + prune 20 条 | ✅ 符合（设计最优的一类） |
| — | — | 补充发现：指令文本（needs_confirmation 说明 ~300B）随每条 tasks reminder 重复注入，属稳定内容却走 reminder 通道 | ⚠️ 漂移 D6（小） |

**已修复的历史漂移**（git 378c0ad0，08-19 局部审计）：time-context（Off-peak hours 高峰/闲时提醒）被用户标记为噪音（00:00 实测）后整体移除；tasks reminder 加 depth 门控（subagent 不注入，工作来自父 agent 的 Delegate 指令）。objects 中 08-18 及之前的 tasks reminder 均带 "Off-peak hours." 前缀，08-19 起消失——修复已生效。

## 3. 成本量化

### 3.1 单块 token 实测估算

| 块类型 | 字节 | token 估算 | 说明 |
|--------|------|-----------|------|
| tasks（20+ 任务） | 2,284B | **~700 tok** | 中文标题 ~0.65 tok/字 + 英文指令 ~4B/tok |
| tasks（3 任务） | ~350B | ~110 tok | 线性缩放，差 6 倍+ |
| environment（9 字段） | 1,900B | **~480 tok** | 英文表格 ~4B/tok |
| environment（若只注入 delta） | ~50B | ~15 tok | "Chat width: 664px → 528px" |
| time | 39B | ~12 tok | 持久化，可被缓存 ✅ |

### 3.2 今日（08-20 00:00-12:12）实际注入成本

| 类别 | 次数 | 单次 token | 合计 | 计费方式 |
|------|------|-----------|------|---------|
| tasks | 39 | ~700 | **~27K** | 全额（request-only，不可缓存复用） |
| environment | 35 | ~480 | **~17K** | 全额（同上） |
| time | 2,307 | ~12 | ~28K | 持久化 → 后续 turn 可缓存命中，边际成本 ≈ 0.1x |
| **合计浪费（tasks+env）** | | | **~44K token/半天** | 按全天活跃外推 ~80-100K/天 |

### 3.3 对 prompt cache 的影响（定性）

- ✅ **不破坏前缀**：reminder 追加消息尾部，systemStable 与历史前缀 byte 稳定——定案核心目标达成
- ⚠️ **纯增量成本**：request-only 意味着这些 token 每 turn 全额计费一次，且因不留在历史中，**下一 turn 无法命中缓存**（下一 turn 的请求不含上一 turn 的 contextMsg）
- ⚠️ 放大场景：主会话（Nebula，20+ 任务）每条用户输入固定 +700 tok；若并发 agent 各自会话有 active 任务，成本按 agent 数乘开
- 对比：time reminder 虽注入 2,307 次，但持久化设计使其边际成本趋近于零——**同一机制下持久化 vs request-only 的成本差 ~10 倍**，这是设计范式的差距

### 3.4 20 任务 vs 3 任务场景

- 20 任务：~700 tok/turn → 一个 50 turn 活跃会话 = 35K token 纯任务列表
- 3 任务：~110 tok/turn → 同会话 5.5K token
- 差值 6 倍+，且任务列表**多数 turn 之间并未变化**（任务增删是离散事件，通常间隔多轮）——全量重发的是同一份内容

## 4. 历史包袱（git 演进）

| commit | 日期 | 内容 | 与本审计关系 |
|--------|------|------|-------------|
| c966e7c7 | ~08-10 | 任务 O：time 持久化 + 附件漏注入修复 | time 类设计定型的起点 |
| **68705340** | 08-12 | **cache v2：systemStable lifecycle 重建 + change reminders** | **定案的实现**——D1-D6 漂移即诞生于此：差分框架按「全块」粒度实现 |
| 3ab917e5/ccd76aa8 | 08-13~14 | 冷启动路由（后续 08-19 被用户裁撤） | 无关 reminder 机制 |
| e32d78ba | 08-18 | FastMicroCompact TTL 2h→30min | 缓存优化另一支（见 20260818_cache-optimization-plan.md） |
| **378c0ad0** | 08-19 | remove time-context reminder + gate tasks by depth | **局部审计**：off-peak 噪音移除、subagent 门控——即任务描述中「之前要求过」的部分交付；系统性对照审计（本报告）此前未做 |

结论：定案实现后 reminder 机制无大改，漂移属**实现粒度先天不足**（68705340 全块渲染），非后续回归。08-19 的局部修复方向正确但不触及 D1-D3。

## 5. 优化建议（按收益排序）

| # | 建议 | 预期收益 | 实现成本 | 风险 |
|---|------|---------|---------|------|
| **1** | **tasks 变更检测 + delta 注入**：snapshot 增存上次任务列表文本，逐 turn diff——未变则跳过（或注入一句 "Tasks unchanged (20 active)"），变了只注入增删改行（`+ #325 [pending] Mail 前端显示 delivery 参数`、`~ #300 ...`、`- #289 ...`） | **省 60-80% tasks token**（任务列表多数 turn 不变）；20 任务会话从 35K/50turn → 8-14K | 低：AgentCore 已有 snapshot 模式，TaskStore 加 diff 渲染函数 | 任务变更跨 turn 遗漏感知——缓解：首 turn 全量 + 后续 delta，压缩/重启（lifecycle）重置回全量 |
| **2** | **tasks 摘要化**：subject 截断 ~30 字；activeForm 仅 in_progress 保留；pending 超过 8 条折叠为计数（`…另有 12 条 pending`）；~300B 指令文本（needs_confirmation 说明）迁入 systemStable 稳定段 | 20 任务 700 tok → ~250 tok（-64%）；与建议 1 叠加 | 低：renderForPrompt 局部改 | 截断丢关键词——缓解：截断保头去尾，agent 可用 TaskList 工具查全文 |
| **3** | **environment 真 delta 注入**：差分触发时只注入变更字段行（`Chat width: 664px → 528px`），非全表 | env 480 tok → ~15 tok（-97%）；今日场景省 ~16K | 低：reminders.scala envReminder 改为接收 diff 行 | 无实质风险 |
| **4** | **chatWidth 降噪**（与建议 3 二选一或叠加）：chatWidth 移出 Environment 表/差分比较（价值：仅影响表格渲染宽度建议），或量化到 50px 桶（664/649 视为相同）+ 抑制回弹（A→B→A 视为未变） | env reminder 触发频率 -90%+ | 低 | agent 排版建议略钝化——本就是建议性信息 |
| **5** | schedule 范式推广：devices/sessions 全块注入同样改 delta（漂移 D4；当前低频、收益有限，顺手做） | 小 | 低 | 无 |
| **6** | （观察项）time 2,307 次/半天的打点日志本身可降采样——非 token 成本，是日志噪音 | — | 极低 | 无 |

组合预期：今日 44K 浪费 token → **~8-12K（-75%）**；主会话每 turn 固定开销从 ~1.2K tok 降至 ~300-400 tok。

## 6. 附录：证据文件

- 代码：`reminders.scala`（L70-133 构造）、`AgentCore.scala`（L331-468 注入管线、L374-388 差分）、`TaskStore.scala`（L369-409 全量渲染）、`PromptSections.scala`（L557-562 envInfoSection）
- 日志：`~/.nebflow/logs/nebflow.log`（nebflow.reminders 打点：time 2307 / tasks 39 / environment 35）
- 对象：`logs/router/objects/953007828cb6593b`（08-19 tasks 块 2,284B）、`objects/2fffa85b61e12b31`（08-18 off-peak+tasks 2,349B）、env 对象序列（§1.4 chatWidth 抖动表）
- 局限：router full.jsonl 对大请求跳过 message_refs 落盘，注入次数以应用日志打点为准（精确）；objects 为内容寻址去重，体积分布取自唯一内容样本
