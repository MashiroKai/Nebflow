---
type: design
status: draft
---

# 意图预判设计稿：从 Cursor Tab 的补全机制到 Stand 的「预填充输入框」与「聊天任务提议」

作者令（2026-11-02 08:25）逐字：「我记得 cursor 有一个代码自动补齐功能，我想基于它相同的原理，来做意图识别。比如预填充输入框，预测用户想要继续追问的话。以及在群/好友聊天的时候，自动识别对话中的任务，弹出气泡问是否需要让我帮你干嘛。能给我个方案吗？」

本稿 = **只设计不实施**：零代码改动、零配置改动、零面板新增。上游概念沿用 [[glossary]]，人面子体例见 [[20261030_human-readable-presentation.zh-CN]]。

```human
**一句话**：把 Cursor Tab 的"下一个动作预测"机制搬到对话侧——输入框用浅色残影预测你下一句要问什么，聊天里用一颗 chip 提示"这活儿可以让我干"，两者都只看、不做、不打断。

**场景 A（输入框残影）**：你停手 0.7 秒，框里浮出一句浅色的下一问，Tab 收下、打字即消。省的是"每次都要重新组织一句话"的那十秒。最大风险：预测得不对，反而碍眼——所以它必须随时可忽略。

**场景 B（聊天里的任务 chip）**：群聊/好友那边有人让你做事，消息下一颗低调 chip 问"建待办"还是"派 agent"。省的是"看到一句要办的事，回头忘了办"。最大风险：干扰社交——所以它只在有明确执行者时出，且绝不用弹窗。

**关键数字**（来源见 §7）：Cursor Tab Fusion 服务端 p50 260ms、上下文 13k tokens；Zed 同族目标 p50 <200ms / p90 <500ms。⇒ 本设计把 P1 目标定在 P50 ≤400ms、P95 ≤900ms，超预算一律不出。

**成本量级**：输入框残影约 ¥1.6 / 千次调用（日均 200 次 ⇒ 月约 ¥10）；聊天提议约 ¥0.9 / 千条消息。

**未决**（等您拍板，见 §9）：① 群消息目前只有拉取、没有推送，提议腿挂哪里；② 是否允许这两个场景调用云端模型（默认允许、敏感会话除外）；③ 默认可否开启，还是先默认关、您手动开。

![[assets/20261102_prefill-trigger-decision-tree__delegate-nebflow-18a4e786.png]]
```

---

## 0. 摘要（人话，一屏读完）

作者想要的是两个东西的同一个内核：**「在你动作之前，先把最可能的下一动作摆在那儿；你收下就省一步，你不理它就走」**。Cursor Tab 这套东西之所以好用，不是因为它模型大，而是因为它把三件工程约束做到了位：**足够快**（百毫秒级）、**错了不碍事**（残影可忽略、一键收下）、**上下文花得起**（不是每键全量送）。本稿的主张是：**搬这三条约束，不搬它的专用模型**——Stand 没有自训练补全模型，但有一个现成的低延迟判定面（`jev` 判定腿与 `judge.utilityModel` 快档），足以撑起这两个场景。

两个场景共用一条流水线，只是触发源与出口不同：

| | 场景 A 输入框残影 | 场景 B 聊天任务 chip |
|---|---|---|
| 触发源 | 用户停笔 / 回合结束 / 粘贴完成 | 入站消息落地（好友推送帧；群为拉取落地） |
| 预测对象 | 用户自己的下一句 | 别人交办的事 + 执行者是谁 |
| 用户可见形态 | 输入框内浅色内联残影 | 消息下方一颗 chip，点开才展开确认 |
| 收下动作 | Tab / 右滑 / 点接受条 | 点 chip → 二次确认 → 建待办 或 派 agent |
| 失败形态 | 残影消失，无事发生 | chip 不出现，无事发生 |
| 一期归属 | P1 | P2 |

两棵触发决策树见 **图 2**（上方人面子内嵌），请求时序与延迟预算见 **图 1**：

![[assets/20261102_prefill-request-sequence__delegate-nebflow-18a4e786.png]]

图 1 的读法：横向七条泳道是请求经过的七个环节，纵向是时间；红色虚线是取消路径（继续打字即取消在飞请求），底部时间条给出延迟预算的分解——**唯一的外部延迟来源是黄色那段模型调用**，其余（快照、回程、渲染）都在本地。

---

## 1. 第一部分：把 Cursor Tab 的原理讲清楚

### 1.1 六个环节的解剖

Cursor 官方文档把 Tab 描述为「在你打字时给出建议，依据是最近的编辑、周围代码与 linter 报错」<sup>[S1]</sup>；其 Fusion 模型公告披露了两个关键设计——**它预测的不只是光标处的文本插入，而是「光标附近的编辑 + 接下来该跳到哪里」**，并把这类能力称为 in-flow Next Action Prediction <sup>[S2]</sup>。把它拆成可移植的六个环节：

| # | 环节 | 具体做什么 | 工程要点 | 搬到 Stand 的可搬性 |
|---|---|---|---|---|
| 1 | 上下文采集 | 当前文件 + 光标邻域 + 最近若干次编辑 + 检索片段 + linter 诊断 | 采集成本必须低于模型成本；上下文按「最近性 + 相关性」排序而非全量 | **可搬**：对话侧对应「最近 K 轮 + 会话身份 + 在飞任务」 |
| 2 | 低延迟生成 | 专用稀疏小模型（2024-03 起即自训），不是通用大模型 | 补全模型与对话模型是两个物种：前者小、专用、成本敏感 | **不可搬**（无自训模型）→ 改走「现成快档 + 强约束输出」 |
| 3 | 编辑预测而非 token 补全 | 输出是「一段改写 diff」，可跨多行、可加 import、可跨文件 | 输出形态决定了输出闸的写法：要能判「这段改写是否越界」 | **部分可搬**：对话侧输出是「一句话」，越界判据换成「是否含事实性实体」 |
| 4 | 呈现与接受 | 灰色残影出现在光标前；Tab 收下、Esc 或继续输入即拒绝；Cmd/Ctrl+→ 逐词收下 | 残影必须零副作用、零焦点抢占 | **完全可搬** |
| 5 | 信号回收 | 收下/拒绝/忽略都成为后续训练与门控信号 | 信号是产品改进的燃料，不是遥测报表 | **可搬（只落盘，不训练）** |
| 6 | 防抖与取消 | 每键一请求会被频控；靠 debounce + 在飞请求取消 + KV/prefix 缓存复用 | 「继续打字」是最高优先级的取消信号 | **完全可搬** |

### 1.2 三条硬约束（本设计的全部取舍都从这三条推出来）

**约束一 · 延迟预算**。补全的价值随延迟指数衰减：用户打完那句话的时刻，残影就一文不值。公开读数给了量级参考——Cursor Fusion 服务端 p50 260ms（上一代 475ms）<sup>[S2]</sup>；Zed 的 Zeta 把目标写成「p50 < 200ms、p90 < 500ms」<sup>[S3]</sup>。**结论：整个链路（含本地往返）必须压在数百毫秒级；超过约 1 秒就不该出现**——不是降级显示，而是**根本不显示**。

**约束二 · 误报代价**。补全错了不能打断思路，所以它在形态上必须「可忽略」：灰字、无焦点、一键消失、不落盘、不进对话历史。Cursor 同族的做法是把 Tab 变成「魔法键」但仍保留 Esc/继续输入的退路，并允许逐词收下 <sup>[S1]</sup>。**结论：任何"替你决定"的动作都是设计缺陷**——残影不许自动发送，chip 不许自动建待办。

**约束三 · 上下文窗口经济性**。补全的调用频率比对话高两个数量级，因此每键送全量上下文在经济上不成立。Cursor 的解法是把上下文从 5.5k tokens 扩到 13k 的同时靠投机解码与推理工程把延迟压下去 <sup>[S2]</sup>；Supermaven 走的是另一条极端——用超大窗口（30 万 tokens）换精度，代价是首次进入仓库要先花 10-20 秒建索引 <sup>[S4]</sup>。**结论：Stand 走中间路线——固定小上下文（≤1.4k tokens）+ 会话级快照缓存，不做仓库级索引**。

### 1.3 公开读数对照（本稿的量化锚）

| 项目 | 读数 | 出处 |
|---|---|---|
| Cursor Tab Fusion 服务端延迟 | p50 260ms（上一代 475ms） | Cursor 官方公告 <sup>[S2]</sup> |
| Cursor Tab Fusion 上下文长度 | 13,000 tokens（上一代 5,500） | 同上 |
| Cursor Tab 编辑预测能力提升 | 每行多预测 25% 的困难编辑；改变长度提升 10 倍以上 | 同上 |
| Zed Zeta 延迟目标 | p50 < 200ms、p90 < 500ms | Zed 官方博客 <sup>[S3]</sup> |
| Zed 对「编辑预测」的模型选择 | 从「fill in the middle」改为「让模型重写一段代码」；prompt 工程撑不住后转 SFT + DPO | 同上 |
| Supermaven 上下文/延迟 | 30 万 tokens；250ms（自测表，帧数 15） | Supermaven 公告 <sup>[S4]</sup> |
| Cursor Tab 交互面 | Tab 收下、Esc/继续输入拒绝、逐词收下、多行编辑、跨文件跳转、按语言/扩展名开关 | Cursor 文档 <sup>[S1]</sup> |

> 引用口径说明：S1-S4 均为官方一手文档/公告（2026-11-02 现读）；本仓无自训练模型的评测数据，因此上表只用外部读数定**量级**，不把它当成本项目的验收基线。

### 1.4 三条可判定的移植结论

1. **不搬模型，搬「输出形态 + 出口闸」**。Cursor 的编辑预测之所以安全，是因为输出被限制成「一段可被肉眼一票否决的 diff」。对话侧的等价物是：**输出被硬限制成「一行、≤48 字、问句或指令、不含事实性实体」**；越界即降级或丢弃（§2.1.4）。
2. **不搬「每键触发」，搬「停笔触发」**。代码补全的触发是打字流本身；对话输入的触发必须是**停顿**——否则就会在用户组织语言时不停刷出错误的残影。
3. **不搬「专用模型」，搬「专用小提示词 + 批量一问」**。本仓已有同形先例：邮件重要性判定腿用**一次固定提示词、一次调用、只答一个词**的形态把成本压到可忽略（见 `perception/MailImportanceJudge.scala:28-49`）。两个新场景沿用这个形态，而不是新造一套 agent。

---

## 2. 第二部分：映射到 Stand 的两个场景

### 2.1 场景 A —— 输入框预填充（预测你接下来想问什么）

#### 2.1.1 触发时机（只在三个时刻考虑，且一秒内最多一次）

| 触发源 | 延迟 | 为什么是它 | 不这么做会怎样 |
|---|---|---|---|
| 停笔 N 毫秒 | 建议 **700ms**（可配 400-1500） | 停顿 = 正在想下一句，是残影价值最高的时刻 | 边打边出 ⇒ 抢输入法/抢视线，必须关闭 |
| 上一条回复刚结束（回合终态帧） | 立即 + 150ms | 此刻用户 100% 要接着说话，最可预测 | 只在停笔时触发会错过最佳窗口 |
| 粘贴完成 / 附件落条后 | 400ms | 上下文刚变化，用户的下一问通常与粘贴物有关 | 等于白白丢掉最明确的意图信号 |

三个时刻共用同一个去抖器：任一时刻起算，700ms 内用户有任何输入按键即取消。

#### 2.1.2 上下文（固定小上下文，≤1.4k tokens）

| 级别 | 内容 | 预算 | 理由 |
|---|---|---|---|
| 必送 | 当前会话最近 K 轮的用户消息与回复摘要（K≤4） | ≈600 tok | 决定「接着问什么」的唯一真信号 |
| 必送 | 当前 agent 身份 + 项目名（若有挂载） | ≈120 tok | 同一句话在不同 agent 上应预测出不同措辞 |
| 必送 | 当前草稿（截断 200 字）+ 光标位置标记 | ≤200 tok | 用户已打了一半时，残影是"续写"而不是"重写" |
| 可送 | 在飞任务/后台任务的一句话清单（最多 3 条） | ≈150 tok | 「刚派出去的那个活干完了吗」是最常见的追问 |
| 可送 | 用户历史提问风格（最近 20 条用户消息的**长度与句式统计**，非原文） | ≈80 tok | P3 才启用；一期只送统计量，不送原文 |
| 禁送 | 完整工具输出、文件正文、diff、日志 | — | 成本失控 + 无助于预测下一句 |

**只读不写**：这条腿对会话存储是**纯读**（`core/SessionStore.scala:1860 getHistoryPage`），不产生任何新的会话行。

#### 2.1.3 呈现与交互

- **桌面**：输入框内**浅色内联残影**（ghost text），字号与正文一致、颜色为弱化档；不覆盖用户已输入内容，不移动光标，不抢焦点。
- **接受**：`Tab`；`→`（光标在行尾时）；移动端键盘上方一条「接受」pill（高度与既有 dock 胶囊同档）。
- **拒绝**：任意字符输入（含输入法组字）、`Esc`、失焦、切会话、30 秒无操作自动过期。
- **输入法纪律**：组字期间**任何键都不拦**——复用既有唯一组字判定（见 `web/js/imeGuard.js` 与 `web/js/input/composer.js:74` 的前置短路），本设计**不得**在残影腿里自读组字标志（禁第二份判定）。
- **Tab 冲突**：Tab 默认用于焦点环。**仅当残影在场时**拦 Tab 并 `preventDefault`；残影不在场时 Tab 语义逐字节不变。
- **绝不做的四件事**：不自动发送、不覆盖草稿、不在等答复时抢焦点、不进入对话历史。

#### 2.1.4 输出闸（残影可上屏的唯一判据）

| 判据 | 规则 | 不满足时 |
|---|---|---|
| 长度 | ≤1 行、≤48 字（中文按字计） | 丢弃 |
| 形态 | 必须是问句或指令句（含「？」「帮」「查」「改」「跑」类动词或祈使结构） | 丢弃 |
| 无事实性实体 | **含数字、commit 短码、文件路径、URL、版本号、token 数 ⇒ 一律不许原样上屏** | 降级为**结构化占位**（见下） |
| 与草稿一致 | 必须能接在现有草稿之后（草稿非空时，残影是续写） | 丢弃 |
| 语言一致 | 与当前界面语言一致 | 丢弃 |

**结构化占位**：当模型给出的候选含实体时，只保留其**句式骨架**，把实体替换为固定占位符，例如把「帮我把 3 个 commit 压成一个」降级为「帮我把这批改动整理成一次提交」。占位符是固定词表（这批改动 / 那个文件 / 上次的任务），**不是**模型自由发挥。

> 这一条是整份设计里最容易被"优化掉"的一条。它的存在理由：残影一旦吐出错误的数字或路径，用户可能直接 Tab 收下并发送 ⇒ 从"建议错了"升级成"执行错了"。**宁可少提示，不许提示错**。

#### 2.1.5 落点与代码锚点

| 面 | 现有落点 | 本设计的关系 |
|---|---|---|
| 输入框 DOM | `src/main/resources/web/index.html:884` `<textarea id="input">` | 残影层挂在 `#input-wrap`（`:879`）内，与既有 indicator 同级 |
| 输入行为单点 | `src/main/resources/web/js/input/composer.js:74 bindComposerKeys` | 残影的键判定作为 `cascade` 的一个分支接入，**不新写 keydown** |
| 输入监听 | `src/main/resources/web/js/input.js:1596`（auto-resize / 闸同步） | 去抖器挂在此监听之后 |
| 组字判定 | `src/main/resources/web/js/imeGuard.js`（单点） | 只调用，不复制 |
| ask 输入面 | `src/main/resources/web/js/askDock.js`（composer 包第三消费方） | P1 一并接入（同一 `bindComposerKeys`） |
| 设置页 | `src/main/resources/web/js/sidebar.js:859 renderSettings` 的「运行时」分区 | 开关落此，零新面板 |
| WS 入站分发 | `gateway/WsSessionChatHandlers.scala:39` / `WsSystemHandlers.scala:39` 的 handler 表 | 新帧在此登记（两个新 type） |

---

### 2.2 场景 B —— 群/好友聊天里的任务识别（chip 提议）

#### 2.2.1 识别面（只看用户可见、且用户有权处理的内容）

| 会话类型 | 数据到达方式（本仓现状） | 提议腿挂载点 | 诚实边界 |
|---|---|---|---|
| 好友单聊 | 服务端推送 `message_new` / `message_new_self` 经隧道进 `FriendService.onFriendEvent`（`neblink/FriendService.scala:321`），由 `GatewayMain.scala:1427` 转成 `friend_event` 帧广播给控制台 | **帧落库之后**（同一条 `pullConversation` 之后，`FriendService.scala:582 dispatchPulled` 附近） | 推送是 at-least-once，**必须按 messageId 幂等**，否则重放会重复判定 |
| 群聊 | **本仓群消息没有推送事件**（`FriendService` 只定义了 `event:message_new*` 两个触发前缀）；群消息靠 `GET /api/groups`（会话列表）+ 会话消息读取落地 | 挂在「群消息读取落地」之后（前端 `messages.js:962 openConversation` 的群分支；`messages.js:4847 frameMessage` 只覆盖好友帧，不含群） | 见 §9 未决①：若要「人不在群里也提示」，必须补一条群推送腿（跨仓契约），一期**不做** |
| 自己发的消息 | `message_new_self` | **不判定** | 自己说的事不需要被提醒 |

**权限闸**：只处理「用户在 UI 里可见的会话」。被封禁/静音/未加入的群、他人私聊，一律不入判定面。

#### 2.2.2 判定三段（先闸后模型）

**第一段 · 可执行意图分类**（纯规则 + 模型二判）：

| 意图类 | 例子 | 出口建议 |
|---|---|---|
| 要做某事（交办） | 「你把那个接口文档补一下」 | 执行者是用户 → 建待办 |
| 约定时间 | 「周四下午对一下」 | 建待办（含时间占位，不自动建日程日历项） |
| 要交付物 | 「下班前给我一版」 | 建待办 + 截止时间占位 |
| 要查资料 | 「帮我查下 X 现在多少钱」 | 派 agent（只读任务） |
| 要建日程 | 「下周三提醒我」 | 建待办（kind=todo，不碰外部日历） |
| 寒暄 / 表情 / 纯陈述 / 吐槽 | 「哈哈哈哈」「今天好累」 | **不出** |

**第二段 · 执行者判定**（这是决定"出哪种 chip"的唯一分叉）：

| 判定 | 判据 | chip 文案 | 落地动作 |
|---|---|---|---|
| 用户自己 | 消息主语是"你"，且事项在用户能力域内（文件、流程、人际） | 「建待办」 | `Schedule` 工具 `kind="todo"`（`core/tools/ScheduleTool.scala:179`；`core/scheduler/ScheduledTaskModel.scala:97 KindTodo`） |
| Stand 能干 | 事项是检索/整理/写码/跑命令类 | 「派 agent」 | `Delegate` 工具（`core/tools/DelegateTool.scala:71`），**必须经二次确认** |
| 不明确 | 两者皆可或皆不可 | **不出** | — |

**第三段 · 置信度**：判定模型输出 `P(可执行)` 与 `executor ∈ {user, stand, none}`。`P ≥ 0.72` 且 `executor ≠ none` 才进入呈现闸。

#### 2.2.3 呈现（低调、可忽略、绝不弹窗）

- **形态**：消息气泡下方一行**单颗 chip**（与既有 chip 族同视觉语言，参考 `web/js/chat/msgTokenChip.js` 的玻璃小窗与 `messages.js:2983 bubbleEl` 的插入位）。
- **点击才展开**：第一次点击 = 展开一句确认（「把这条建成待办？」[确认][不用]）；确认后才调用工具。**chip 本身不执行任何写操作**。
- **绝不**：全屏弹窗、自动滚动、自动标已读、自动建待办、自动派 agent、在会话列表加角标（角标口径由既有 `actionable.js:10 registerActionableProvider` 决定，一期**不接**）。
- **视觉铁律**：展开的确认气泡走既有毛玻璃、无 overlay 遮罩（与作者既有视觉裁定一致，参照 [[glossary#^mockup-sample]] 的设计稿体例）。

#### 2.2.4 冷却与去重（参数表）

| 参数 | 建议值 | 作用 |
|---|---|---|
| 话题去重窗口 | 10 分钟 | 同一会话同一意图只提一次 |
| 话题键 | `会话 id + 意图指纹（动作动词 + 对象名词 + 时间短语）` | **不按消息 id**：一件事常常连发三四轮 |
| 同会话冷却 | 5 分钟内最多 1 次 | 群聊里高频发言时不刷屏 |
| 每日上限 | 每会话 20 次、全实例 100 次 | 成本与打扰的双闸 |
| 同类降权 | 同类提议被拒 ≥3 次 ⇒ 该类阈值上调 0.1（封顶 0.9） | 「你说过不要」必须被记住 |
| 全局静默 | 用户关掉场景开关 ⇒ 零请求 | 与 A 场景各自独立 |

#### 2.2.5 隐私红线（硬闸，先于任何模型调用）

1. **判定只在本实例内发起**：模型调用从本机引擎发起，不经过任何第三方转发面（不写第三方审计、不落第三方日志）。
2. **敏感会话自动关闭提议**，命中任一即整会话静默：
   - 会话被用户标记静音 / 归档；
   - 消息命中凭据形态（密钥前缀、`sk-` / `ghp_` 一类、验证码 4-8 位数字 + 语境词、密码、身份证/银行卡号形态）；
   - 会话标题或成员名命中「私密 / 财务 / 医疗」等既有敏感词表；
   - 单条消息长度 > 4000 字（长文多为粘贴的文档，判定价值低、成本高）。
3. **端到端可审计**：每次「不出」都记一行 `skip(reason=…)`，用户可以问「为什么这条没提示我」并得到确切答案（不是"模型觉得不重要"这种废话）。
4. **凭据永不上送**：命中凭据形态的消息**整条跳过**（不是脱敏后送），避免任何形态的凭据离开本机。
5. **本地模式**：开关 `chatProposal.allowRemoteModel=false` 时，本场景整体关闭（不降级为规则版——规则版误报率高，宁可不出）。

> 注：本设计的诚实边界——**只要用了云端模型，消息正文就离开了本机**。因此这一条不是"技术上做不到外发"，而是"用显式开关 + 敏感闸把外发面收到最小"。是否允许，请作者裁（§9 未决②）。

#### 2.2.6 与既有体系的对接缝

| 既有面 | 锚点 | 本设计怎么用 |
|---|---|---|
| 待办（用户收件箱） | `core/scheduler/ScheduledTaskStore.scala:166`（`getAllTodos`）/ `:232`（`upsertTaskByName`）；`ScheduledTask.KindTodo` | 建待办 = 走既有 `Schedule` 工具语义，`name` 用**幂等键**（话题指纹哈希）防重复 |
| Ask→待办镜像 | `core/scheduler/AskTodoBridge.scala:42 attach` | 提议产物与 Ask 镜像是**同一种行**（kind=todo），因此待办面板无需改动；命名前缀避开 `ask-` / `perm-` 两个保留前缀 |
| 会话内任务树 | `core/task/TaskStore.scala:16 create` | **不用**：TaskStore 是 agent 自用的进展面，用户待办走 ScheduledTask（避免两套待办语义并存） |
| 派活 | `core/tools/DelegateTool.scala:71` | chip 确认后按既有 Delegate 语义派发；**不新造**「轻量派活」通道 |
| 前端事件面 | `web/js/messages.js:4840 EV_MESSAGE_NEW` / `:5310 onMessage('friend_event')` | 好友腿的事件名与帧形状**只读**，不新增事件名 |
| 徽标可行动聚合 | `web/js/actionable.js:10` | 一期不接（提议不是"需要行动"的量，接了会污染角标语义） |

---

## 3. 第三部分：引擎落地形态（架构，不写代码）

### 3.1 三个新组件与它们的边界

```
                    ┌─────────────────────────────────────────────┐
  输入面事件 ───────►│ PrefillService（引擎侧单点）                  │
  （停笔/终态/粘贴）  │  · 去抖归并（每会话一个）                      │
                    │  · 会话快照缓存（指纹键）                       │
                    │  · 预算闸（每分钟/每小时）                      │
                    │  · 调 SuggestionJudge → 输出闸 → 下发          │
                    └───────┬──────────────────┬───────────────────┘
                            │                  │
                    ┌───────▼───────┐  ┌───────▼────────┐
                    │SuggestionJudge│  │ ProposalJudge  │
                    │（残影生成）    │  │（任务判定）     │
                    └───────┬───────┘  └───────┬────────┘
                            └────────┬─────────┘
                                     ▼
                    ┌────────────────────────────────┐
                    │ FastLlmPort（唯一模型出口）      │
                    │ jev 判定腿 → judge.utilityModel │
                    │  → 全局候选链首个可用；硬超时 800ms│
                    └────────────────────────────────┘
```

三条纪律：

1. **一个模型出口**。两个判定器都只经一个 `FastLlmPort` 出网，与 `MailImportanceJudge.judgeViaLlm`（`perception/MailImportanceJudge.scala:86`）同形——这保证了「限流、超时、降级、开关」只需实现一次。
2. **一个判定器一件事**。残影生成与任务判定共用提示词骨架但**不共用调用**：两者的输入预算、输出形态、验收指标完全不同，混在一次调用里会让任何一方的调参都反噬另一方。
3. **禁第二份语义**。输入面触发、组字判定、待办工具语义、派活工具语义——全部调用既有单点，不许在提议腿里各写一遍（与仓内既有「两处不许各写一遍逻辑」纪律同源，见 `web/js/input/composer.js:2-12`）。

### 3.2 模型选择与降级链（补全模型 ≠ 主对话模型）

| 顺位 | 候选 | 理由 | 不可用时 |
|---|---|---|---|
| 1 | `jev` 判定腿（`typesafe-jev` / `laya`，`core/jev/JevGate.scala:75 KnownProviders`） | 已是"一次批量判定"的既有形态，延迟与成本都为判定场景设计 | 落第 2 位 |
| 2 | `judge.utilityModel`（`shared/config.scala:484`；装配范式见 `llm/decision/DelegateJudgeMachine.scala:231`） | 作者已为判定机指定的小模型位，语义匹配 | 落第 3 位 |
| 3 | 全局候选链首个可用（`llm/decision/LlmAllocator.candidatesFrom` 同款） | 兜底；此时延迟可能超预算，靠超时静默 | 整腿静默不出 |

**硬约束**：

- 请求必须 `thinking=None`、`tools=None`、`searchAllowed=false`（`shared/protocol.scala:96 LlmRequest` 的对应字段）——判定场景不需要任何一样，打开就是白烧钱。
- `systemStable` 固定（提示词逐字不变），`systemDynamic` 只放本次会话摘要 ⇒ 命中上游 prompt cache（口径见 `llm/AdaptiveThrottle.scala` 同族的缓存纪律）。
- **硬超时 800ms**（`IO.timeout`），超时即放弃本次残影/提议，**不重试**。
- 失败梯度：`超时 → 静默`、`429 → 静默并退避到下个窗口`、`解析失败 → 静默`。**任何失败都不许 toast、不许写对话行、不许进错误汇总条**。

### 3.3 缓存与投机

| 机制 | 做法 | 收益 |
|---|---|---|
| 会话级上下文快照 | 键 = `会话 id + 末轮消息 id + 任务版本号`；快照含已渲染好的上下文串 | 命中即 0 网络重建，< 10ms |
| 结果短缓存 | 键 = 快照指纹 + 草稿哈希；同一输入不重复调模型（TTL 60s） | 反复删改时几乎零成本 |
| prefix 复用 | 提示词前缀逐字固定（含示例），动态内容放末尾 | 上游 prompt cache 命中 |
| 请求取消 | 前端每次输入发出 `cancel(sessionId)`；引擎按会话保留一个 `Fiber` 句柄，新请求先 `cancel` 旧请求 | 不给过时请求付费 |
| 在飞上限 | A 场景每会话 1、全局 4；B 场景每会话 1、全局 2 | 防雪崩 |
| 并发闸 | 复用既有 provider 级 FIFO 动态闸（`llm/AdaptiveThrottle.scala`） | 与主对话共享 429 保护，不额外开洞 |

### 3.4 请求预算与成本估算

**预算表**（全部可配，热读）：

| 参数 | 默认 | 说明 |
|---|---|---|
| `prefill.minIntervalMs` | 700 | 停笔去抖 |
| `prefill.perSessionPerMinute` | 6 | 会话内每分钟上限 |
| `prefill.globalPerHour` | 120 | 全实例每小时上限 |
| `chatProposal.perSessionPerDay` | 20 | 见 §2.2.4 |
| `chatProposal.globalPerDay` | 100 | 同上 |
| `timeoutMs` | 800 | 硬超时 |

**成本公式**（单次调用）：

```
C_call = (T_in × p_in + T_out × p_out) / 1e6
T_in  = S_sys + S_ctx + S_draft
```

| 场景 | `S_sys` | `S_ctx` | `S_draft` | `T_in` | `T_out` |
|---|---|---|---|---|---|
| A 残影 | ≈280 | ≤900 | ≤200 | ≈1,380 | ≤64 |
| B 提议 | ≈320 | ≤300（单条消息 + 会话摘要） | 0 | ≈620 | ≤48 |

**量级算例**（按 `p_in = ¥1 / M tok`、`p_out = ¥4 / M tok` 作示例单价，实施时按实际 provider 定价替换）：

- A：`C_call ≈ (1380×1 + 64×4)/1e6 ≈ ¥0.00164` ⇒ **每千次 ¥1.64**；日均 200 次 ⇒ **月约 ¥10**。
- B：`C_call ≈ (620×1 + 48×4)/1e6 ≈ ¥0.00081` ⇒ **每千条 ¥0.81**；日均 100 条 ⇒ **月约 ¥2.4**。

> 公式里的 `p_in/p_out` 只是量级示例。真正要守住的是**调用次数**（去抖 + 预算闸），不是单价——次数翻十倍时，任何单价都救不回来。

### 3.5 失败处理与开关

**配置块**（写进既有 `nebflow.json`，热读；前缀 cache 纪律：改值即刻生效，无需重启）：

```json
{
  "intentSuggestion": {
    "prefill": {
      "enabled": false,
      "minIntervalMs": 700,
      "perSessionPerMinute": 6,
      "globalPerHour": 120,
      "timeoutMs": 800,
      "maxChars": 48
    },
    "chatProposal": {
      "enabled": false,
      "allowRemoteModel": true,
      "minConfidence": 0.72,
      "perSessionPerDay": 20,
      "globalPerDay": 100,
      "sensitiveAutoOff": true
    }
  }
}
```

- **默认关闭**（`enabled=false`）：新机制先关着上，作者手动打开——与仓内既有「新增能力默认关、零行为变化」口径一致（参 `llm/adaptiveThrottle.enabled` 默认 false 的先例）。
- **热读口径**：每次判定前读配置（同 `core/jev/JevConfigReader.scala:28 snapshot` 的每调度热读先例），关开关 = 下一个动作为零请求。
- **设置页**：不加新面板，开关落「运行时」分区（`web/js/sidebar.js:859 renderSettings` 内、`settings-section` 体例），两个场景各一把；写路径走既有 `PATCH /api/config`（`gateway/ConfigRoutes.scala:282`）。
- **失败即静默**，无论原因；WARN 只进日志（`LlmLogWriter.scala:146` 同族），用户侧零提示。

### 3.6 信号回收（落盘，不训练）

一次建议的完整生命周期产生四类事件，全部本地落盘（`<dataRoot>/intent-suggestion/events.jsonl`，按日滚动，30 天 TTL）：

| 事件 | 字段 | 用途 |
|---|---|---|
| `shown` | `ts, scene, sessionId, latencyMs, textHash, model, tokens` | 延迟分布、成本核算 |
| `accepted` | `ts, scene, sessionId, latencyMs, chars, editedAfterAccept` | 接受率、接受后编辑率 |
| `rejected` | `ts, scene, sessionId, reason ∈ {key, esc, blur, expire, type`}` | 打扰度归因 |
| `skipped` | `ts, scene, sessionId, gate, reason` | 「为什么没出」的可解释性（§2.2.5 闸 3） |

- **不进模型训练**（作者未另裁前一律不训练，也不外发）。
- 便携读数：新增一个只读端点复用既有聚合面形态（`core/UsageRecordStore.scala:129` 同族），P1 阶段**甚至可以不做端点**，先落 JSONL 供人工核算。

### 3.7 面板：零新增

不新增任何面板/标签页。可观测面全部复用：
- 成本与延迟 → 既有 token 用量面板（`web/js/usageDashboard.js`，数据集独立统计）；
- 开关 → 设置页既有分区；
- 「为什么没提示」→ `skipped` 事件 + 日志，不建 UI。

---

## 4. 第四部分：分期与验收

### 4.1 分期

| 期 | 范围 | 交付物 | 明确不做 |
|---|---|---|---|
| **P1** | 输入框残影：本机、单会话、ghost text、两把开关、事件落盘 | 1 个 `PrefillService` + 1 个判定器 + 1 个前端残影层 + 2 个 WS 帧 + 1 段设置行 | 不做个性化、不做聊天提议、不做群腿 |
| **P2** | 聊天任务 chip：好友腿（推送）优先，群腿依 §9 未决① 定 | 1 个 `ProposalJudge` + chip 渲染层 + 幂等键 + 冷却去重 + 隐私闸 | 不做自动化执行（一律二次确认） |
| **P3** | 个性化：风格学习（只学长度/句式统计）+ 阈值自适应（按接受率调 `minConfidence`） | 阈值调参器 + 风格统计器 | 不做模型微调、不做跨实例画像同步 |

### 4.2 可量化验收指标（每期都必须给读数，不给"感觉好多了"）

| 指标 | 定义 | 采集点 | P1 门槛 | P2 门槛 |
|---|---|---|---|---|
| 延迟 P50 | `shown.ts - request.ts` 中位数 | `shown` 事件 | **≤ 400ms** | ≤ 600ms |
| 延迟 P95 | 同上，95 分位 | 同上 | **≤ 900ms** | ≤ 1200ms |
| 超时率 | `skipped(gate=timeout)` / 总请求 | `skipped` | ≤ 3% | ≤ 3% |
| 接受率 | `accepted / shown` | 两事件配比 | ≥ 15%（两周内），成熟期 ≥ 25% | chip 点击率 ≥ 6% |
| 误报率 | 接受后 10 秒内被整段删除的比例 | `accepted.editedAfterAccept` | **≤ 8%** | 点开确认后取消 ≤ 30% |
| 打扰度 | 场景开关被关闭的周频 | 配置变更事件 | ≤ 5%/周 | ≤ 3%/周 |
| 成本 | 每千次（千条）调用单价 | `shown.tokens` | ≤ ¥2 / 千次 | ≤ ¥1.5 / 千条 |
| 预算合规 | 任一窗口超限次数 | 预算闸计数 | **0**（硬断言） | 0 |

### 4.3 P1 验收清单（二值判断，可机械断言）

1. 关开关 ⇒ 打 200 字、停笔 5 秒，**零 WS 出站帧**（网络面板可证）。
2. 残影在场时按 Tab ⇒ 文本进入输入框且**不插入制表符**；残影不在场时 Tab 行为逐字节不变。
3. 中文输入法组字中按任意键 ⇒ 残影消失且**输入法候选不受影响**（复用组字单点，不新增判定）。
4. 模型 800ms 超时 ⇒ 无 toast、无对话行、无错误条；仅日志一行 WARN。
5. 模型返回含数字/路径的候选 ⇒ 上屏内容是**占位句**，或干脆不出。
6. 残影在场时继续打字 ⇒ 残影消失，且引擎侧在飞请求被取消（`cancel` 计数 +1）。
7. 30 秒无操作 ⇒ 残影自动消失，`rejected(reason=expire)` 记一行。
8. 同一会话每分钟第 7 次触发 ⇒ 不发请求，`skipped(gate=budget)` 记一行。
9. 移动端：残影出现时键盘不被收起、焦点不被夺走；接受条可点。

### 4.4 P2 验收清单

1. 关 `chatProposal.enabled` ⇒ 收 50 条入站消息，**零模型调用**。
2. 消息含 `sk-` 形态密钥 ⇒ `skipped(gate=sensitive)`，**模型调用计数 +0**。
3. 同一话题连发 4 条消息 ⇒ 最多 1 颗 chip。
4. 同会话 5 分钟内第 2 次可提议 ⇒ 不出（冷却）。
5. 点 chip ⇒ **只展开确认**，待办数量不变；点确认后才 +1。
6. 拒绝同类提议 3 次后 ⇒ 阈值上调生效，第 4 次不再出该类。
7. chip 出现前后 ⇒ 会话已读态、列表角标、滚动位置**均不变**。
8. 推送重放（同一 messageId 两次）⇒ 不重复判定、不重复出 chip。

---

## 5. 风险清单

| # | 风险 | 触发条件 | 缓解 | 兜底 |
|---|---|---|---|---|
| R1 | 延迟不达标 | 上游快档抖动 / 网络 RTT 高 | 硬超时 800ms + 不重试 + 候选链降级 | 超预算即不出（宁缺勿滥） |
| R2 | 预填内容出错误导 | 模型吐出具体数字/路径/结论 | §2.1.4 输出闸 + 结构化占位 | 命中即降级或丢弃；残余风险靠「残影必须 Tab 才生效」 |
| R3 | 聊天提议打扰 | 群聊高频、阈值过低 | 冷却/去重/降级三闸 + 默认关闭 | 关开关 = 零请求 |
| R4 | 隐私与合规 | 消息含凭据、私密会话 | 敏感闸（先于模型）+ 整条跳过 + 可关远程模型 | 「本地模式」整场景关闭 |
| R5 | 成本失控 | 去抖失效、请求风暴 | 预算闸（会话/全局双层）+ 在飞上限 | 超限静默退避，不补偿 |
| R6 | 误伤既有交互 | Tab 语义、输入法、队列/排空链 | 只在残影在场时拦 Tab；组字走单点；不碰 `input.js` 的发送链 | 差异以「残影不在场 = 逐字节不变」为红线 |
| R7 | 两套语义并存 | 用 TaskStore 建待办 | 明令：用户待办只走 `Schedule kind=todo` | 代码评审项 |

---

## 6. 明确不做（边界申报）

- 不做自训练补全模型（P1-P3 都不做）。
- 不做仓库级/文件级索引（Supermaven 那条路，成本与收益都不匹配当前场景）。
- 不做「自动发送」「自动建待办」「自动派 agent」——三个"自动"是本设计的三条红线。
- 不接外部日历/提醒（待办只落 Stand 自己的待办面，与既有边界一致）。
- 不在 P1/P2 做跨实例画像同步（P3 也不做同步，只做本机统计）。
- 不改任何既有帧形状、不改发送链、不改角标口径。

---

## 7. 来源

- **[S1]** Cursor Docs · Tab completion — <https://cursor.com/docs/tab/overview>（2026-11-02 现读：收下/拒绝键位、多行编辑、跨文件跳转、按语言开关）。
- **[S2]** Cursor Blog · *A new Tab model*（2025-01-13）— <https://cursor.com/blog/tab-update>（Fusion；p50 260ms / 上一代 475ms；上下文 5,500 → 13,000 tokens；每行多预测 25% 困难编辑、改动长度 10 倍以上；Next Action Prediction）。
- **[S3]** Zed Blog · *Zed now predicts your next edit with Zeta*（2025-02-13）— <https://zed.dev/blog/edit-prediction>（p50 <200ms / p90 <500ms 目标；FIM → 重写式编辑预测；SFT + DPO；投机解码）。
- **[S4]** Supermaven Blog · *Introducing Supermaven*（2024-02-22）— <https://supermaven.com/blog/introducing-supermaven>（30 万 tokens 上下文；自测延迟表：Supermaven 250ms / 15 帧）。
- 仓内既有形态先例：`perception/MailImportanceJudge.scala`（一次调用、固定提示词、三值裁决）、`core/jev/JevGate.scala`（纯函数闸）、`llm/AdaptiveThrottle.scala`（provider 级 FIFO 闸）。

---

## 8. 代码锚点总表（实施批直接照此下钻）

| # | 锚点 | 用途 |
|---|---|---|
| A1 | `src/main/resources/web/index.html:884`（`<textarea id="input">`）、`:879`（`#input-wrap`） | 残影层挂载位 |
| A2 | `src/main/resources/web/js/input/composer.js:74`（`bindComposerKeys`）、`:117`（`createTurnToggle`） | 键判定接入点（`cascade` 分支） |
| A3 | `src/main/resources/web/js/input.js:1596`（input 监听）、`:197 send` | 去抖器挂载位；发送链**不动** |
| A4 | `src/main/resources/web/js/imeGuard.js` | 组字判定单点（只调用） |
| A5 | `src/main/resources/web/js/askDock.js`（composer 第三消费方） | P1 一并接入 |
| A6 | `src/main/resources/web/js/sidebar.js:859`（`renderSettings`） | 开关落「运行时」分区 |
| A7 | `src/main/scala/nebflow/gateway/WsSessionChatHandlers.scala:39`、`WsSystemHandlers.scala:39` | 新增两帧的登记处 |
| A8 | `src/main/scala/nebflow/core/SessionStore.scala:1860`（`getHistoryPage`） | 上下文快照的唯一读口 |
| A9 | `src/main/scala/nebflow/shared/protocol.scala:96`（`LlmRequest`） | 判定请求形态（`thinking/tools/searchAllowed` 全关） |
| A10 | `src/main/scala/nebflow/shared/config.scala:484`（`judge.utilityModel`） | 快档候选来源 |
| A11 | `src/main/scala/nebflow/core/jev/JevGate.scala:75`（`KnownProviders`）、`core/jev/JevConfigReader.scala:28` | 顺位 1 候选 + 配置热读范式 |
| A12 | `src/main/scala/nebflow/perception/MailImportanceJudge.scala:28/31/72/86` | 「一次调用、固定提示词、三值裁决」的形态范本 |
| A13 | `src/main/scala/nebflow/llm/AdaptiveThrottle.scala` | provider 级并发闸（复用，不新造） |
| A14 | `src/main/scala/nebflow/neblink/FriendService.scala:321`（`onFriendEvent`）、`:389`（`handleEvent`）、`:582`（`dispatchPulled`） | B 场景好友腿挂载点 + 幂等键来源 |
| A15 | `src/main/scala/nebflow/gateway/GatewayMain.scala:1427`（friend_event 广播） | 帧已存在的证据（不新增事件名） |
| A16 | `src/main/resources/web/js/messages.js:4840/4847/5310`（事件名与帧映射）、`:962 openConversation`、`:2983 bubbleEl` | 前端消费面与 chip 插入位 |
| A17 | `src/main/resources/web/js/friendGroups.js:131 refreshGroups`、`web/js/friendsApi.js:666 getMessages` | 群消息落地腿（B 场景群分支现状） |
| A18 | `src/main/scala/nebflow/core/tools/ScheduleTool.scala:179-182`（`kind` 枚举 task/todo）、`core/scheduler/ScheduledTaskModel.scala:94/97`（`KindTask`/`KindTodo`）、`core/scheduler/ScheduledTaskStore.scala:232`（`upsertTaskByName` 幂等键） | 建待办出口（幂等 upsert） |
| A19 | `src/main/scala/nebflow/core/scheduler/AskTodoBridge.scala:42` | 待办命名前缀保留位（`ask-` / `perm-` 禁用） |
| A20 | `src/main/scala/nebflow/core/tools/DelegateTool.scala:71` | 派 agent 出口 |
| A21 | `src/main/scala/nebflow/gateway/ConfigRoutes.scala:282`（`PATCH /config`） | 开关写路径 |
| A22 | `src/main/scala/nebflow/core/UsageRecordStore.scala:129`、`web/js/usageDashboard.js` | 成本读数复用面（P1 可先不做端点） |

---

## 9. 开放问题（等作者拍板，拍板后另派实施批）

1. **群消息的提议腿挂哪里**。本仓现状：好友消息有隧道推送（`event:message_new*`），**群消息只有拉取**（`GET /api/groups` + 会话消息读取），没有群推送事件。三种选法：
   - (a) 只在用户打开群窗时判定（零跨仓改动，覆盖窄）；
   - (b) 增加定时轻拉（client 侧，30-60s 一次，仅针对有未读的群）；
   - (c) 跨仓给 neblink-server 补一条群消息推送事件（覆盖最全，但要动契约）。
2. **是否允许这两个场景调用云端模型**。默认 `allowRemoteModel=true` 但敏感会话硬关；若要求"任何聊天内容不出本机"，则 P2 需整体改为本地小模型（本仓当前无此能力）或直接砍掉 P2。
3. **默认开关状态**。本稿建议**默认关**（与既有新增能力默认关的口径一致），作者手动开；若希望"装上就有效果"，则改为默认开但把阈值调到保守档（`minConfidence=0.8`、`perSessionPerDay=8`）。
4. **移动端接受条的位置**。本稿按"键盘上方 pill"设计；若与既有 dock 胶囊冲突，可退化为键盘工具栏内一键。
5. **是否要"逐词收下"**。Cursor 有逐词接受（Cmd/Ctrl+→）；对话残影通常只有一行，逐词收益有限，本稿一期不做。
