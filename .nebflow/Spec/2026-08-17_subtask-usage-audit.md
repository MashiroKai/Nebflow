> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# SubTask 工具使用率审计与归因

> 状态：**阶段文档 · 已完成即冻结** ｜ 日期：2026-08-17 ｜ 作者：Explorer（Nebula 委派）
> 任务：审计 SubTask 实际使用率，归因设计问题。纯分析不改代码。
> 数据口径：`~/.nebflow/subagent-tasks/*.json`（任务登记）+ `~/.nebflow/sessions/`（1748 文件全量扫描 tool_use 块）+ worker 会话文件（`delegate-*` / `subtask-*` 磁盘存证）+ 12 个资格 agent 的 system.md / 3 份 rules.md。

---

## 0 · TL;DR

**使用率：极低——上线 5 天全系统仅 6 次调用（同一时段 Nebula 的 Delegate 91 次，差 15 倍）。**
归因主结论：**"有场景但没引导"**——工具本身跑得通（6 次全部实际交付，2 次误标 failed 是 status bug），但 12 个资格 agent 里 11 个的 system.md **零 SubTask 提及**，团队规则的"并行"话语权全部锚定在 Manager 层。次因：成员任务多为单线串行队列（并行机会已被 Manager 编排消费）+ 上线头 24 小时 status bug 挫伤。
一句话建议：**给成员 system.md 注入一节"何时拆 SubTask"（对齐 Nebula 已验证的引导密度），以 08-16 Frontend 双 worker 模式为 canonical 例子；预期使用率天花板本来就是每天 1-3 次，不必追平 Delegate。**

---

## 1 · 使用统计

### 1.1 四工具对比总表

| 工具 | 调用/worker 数 | 口径 | 调用者 | 备注 |
|---|---|---|---|---|
| **Mail** | **932** | 全历史 transcript 可见 tool_use（compaction 下界） | 全员 | 含 flow 内部步骤回传、跨成员通信 |
| **Delegate** | **139** 全历史 / **91** 拆分后 | 拆分后 = 磁盘 `delegate-*.json` worker 文件数 | **全部 Nebula** | 拆分后硬门禁 NebulaExclusiveTools，他人无法调用 |
| **SubTask** | **6** | 磁盘 `subtask-*.json` worker 文件数（4 条在 subagent-tasks 登记 + 2 条孤儿） | nebflow-project 的 Manager×2、Backend×2、Frontend×2 | transcript 中 tool_use 块 = **0**（全部被 compaction 吞掉，以磁盘存证为准） |
| **FlowTrigger** | **0** 实调用 | transcript + ui.json 双查 | — | flow 触发实际走 `Mail("flow-name")`；40 处文本提及全是设计讨论 |

![四工具使用对比](assets/subtask-chart1-compare.svg)

### 1.2 SubTask 全部 6 次调用明细

| 时间（本地） | 调用者 | 任务 | 结果 |
|---|---|---|---|
| 08-13 19:47 | np/Manager ×2（并行对） | 修 neblink-server device_session bug ＋ Nebflow Scala 两个 bug（OAuth 登录态 + NeblinkClient） | 标记 failed——**实为 status bug 误标**，worker 报告 "Both bugs fixed, compiled, and committed" |
| 08-13 20:47 | np/Backend | NebLink Relay Rust 侧实现（/tmp/neblink-server-standalone） | 同上，stale 误标 |
| 08-16 00:51 | np/Frontend ×2（并行对） | F1 文件浏览器多选+批量删除 ＋ Canvas 12 viewer 插件化拆分；**共享同一 worktree、非重叠文件集、worker 禁 commit 由 parent 整合** | completed，交付质量高 |
| 08-16 14:03 | np/Backend | NEBFLOW_HEADLESS 确定性开关（步 4：三触点+开关+测试） | completed |

![逐日时间线](assets/subtask-chart2-timeline.svg)

要点：
- **08-16 14:03 后零使用**（至今 31.5h）——期间 nebflow-project 恰好跑了最大批次（rebrand 4 批 + 消息搜索 v3.1 + teams-ghost 修复），高任务密度下仍无自发使用。
- 6 次中有 **2 组是并行对**（同一分钟双 spawn）——真用时用法正确，符合"一响应多调用并发"设计。
- 早期 3 次（08-13）全部撞上 status tracking bug（08-14 修复），虽工作实际完成但登记为 failed——头 24 小时体验是"坏了的"。

### 1.3 资格 vs 实际使用

| 团队 | 资格 agent | 实际使用 | 说明 |
|---|---|---|---|
| nebflow-project | Backend, Docs, Frontend, Manager, prompt-engineer, tool-engineer（6） | **3/6**（Manager/Backend/Frontend 各 2 次） | qa-backend/qa-frontend 无资格（验收角色，合理） |
| nebflow-website | Manager, Frontend, Designer（3） | **0/3** | 08-16 活跃过（四任务批次），**有机会没用**（见 §2.1） |
| nebflow-rust | Manager, Backend, Frontend（3） | 0/3 | 三 agent 最后活跃 08-11 14:59——**早于 SubTask 上线，从未有机会** |

---

## 2 · 归因分析

### 2.1 机会结构：并行机会确实存在，但大半被 Manager 层消费

**Manager 派单模式 = 跨成员并行 + 成员内串行队列**。08-17 全天 Manager 34 封派单 Mail 的实证模式：

- 并行发生在成员之间：Backend 拿 Scala 侧、Frontend 拿 web 侧，各占一个 worktree（"批3 web 侧派单——不等 Backend（其 Scala 侧排队中，契约已定死）"）
- 成员内部是纯串行队列："排队任务·注入编码修复之后"、"你的队列最后一项"、"批1 → 批2 → 批3 → 批4"
- rules.md「并行开发—Worktree 工作流」整节教的都是 **Manager 如何给多成员建 worktree**——"并行"的制度话语权完全锚定在 Manager 层

**但"成员内并行"机会真实存在，且有对错分明的双例对照**：

| 案例 | 机会 | 实际行为 |
|---|---|---|
| 08-16 Frontend 收到 F1+P1 两独立前端任务 | 同域双任务，文件不重叠 | ✅ spawn 2 个 worker 并行，共享 worktree 非重叠文件，parent 整合 commit——**教科书式用法** |
| 08-16 website Frontend 收到"Docs 六主题完善" | 6 个独立 mdx 主题，天然 6 路可拆 | ❌ 单 agent 串行写完 6 篇（7 commits 逐主题交付）；同晚任务 2（Hub P0 页）也是"排队送达，Docs [RESULT] 发出后开始" |

结论：机会结构解释了一部分（成员任务多数内聚单线），但 website 六主题这类明确机会的错失证明**不是没场景**。

### 2.2 Prompt 引导缺位（主因）

全量扫描 12 个资格 agent 的 system.md + 3 份 rules.md：

| 注入点 | SubTask 引导 | 并行引导 |
|---|---|---|
| 11 个成员 agent 的 system.md（Backend 38 行 / Frontend 180 行 / Designer 95 行…） | **0 处** | 0 处（Backend 工作流：读→写→编译→测试→Mail，纯串行） |
| 3 份 rules.md | 0 处 | 仅 Manager 侧 worktree 编排 |
| Manager system.md | **1 处**（"SubTask coaching"：成员任务可拆时建议其用 SubTask） | 2 处（均 Manager 侧派单） |
| 工具 description（SubTaskTool.scala L40-60） | 有完整 When to use / When NOT | 有 |

**对照 Nebula（91 次的使用者）**：system.md 195 行含——任务路由表（"纯分析/探索 → `Delegate(agent="Explorer")`"）＋ 工作流内嵌调用示范（L90）＋ **专门「### 并行任务」章节**（"多个独立子任务 → 用 PARALLEL Mail 或多个 Delegate 并行"）。

差距与引导密度完全同构。工具 description 里的 When-to-use 埋在 10 个工具的列表里，与 system prompt 级路由表的引导力不可比。
另注意历史包袱：拆分前 Delegate 对全员开放的年代，team agent 也几乎不用 Delegate（07-08 月 delegate worker 全来自用户主会话/Nebula）——**这个能力从 Universal 时代就没养成习惯**，拆分后也没有任何补课动作。

Manager 侧的 "SubTask coaching" 指令存在但**实证未被执行**：08-17 的 34 封派单 0 次建议成员拆 SubTask（对比其模板中"排队"话术高频出现）。派发者引导失灵与 docs 规范发现的教训一致（"核心教训是必须覆盖派发者"——Manager 派单 prompt 里写死的话术覆盖了 system.md 规则）。

### 2.3 工具设计摩擦（次因，部分是"按设计过滤"）

| 摩擦点 | 实证 |
|---|---|
| self-clone + clean context：prompt 必须自包含 5 要素 | 真实成本——写好 worker prompt ≈ 自己做一半的工作量。对内聚小任务不划算，**这其实是设计意图（过滤低价值并行），不全是缺陷** |
| status bug + 无重试（08-13~14） | 头 24h 的 3 次使用全部遭遇：登记 failed、结果回传不可信。早期挫伤真实存在，08-14 已修 |
| ephemeral 一次性回传 | 08-16 修复后 3 次使用均正常交付，无失败重试需求出现 |
| 一层限制（worker 剔除 Mail/SubTask/Delegate） | **无实证需求被卡**：6 次使用全是叶子任务，无递归拆分尝试 |
| worker 无 Mail 身份（三层防幻觉：Worker Identity Block + stripTeamContent + 工具剔除） | 零幻觉事故记录；08-16 双 worker 共享 worktree 互不干扰——设计达成目的 |

### 2.4 生态位剩余空间

| 并行需求 | 已被谁消费 |
|---|---|
| 多角色协作 / 结构化流水线 | Flow（code-review 等 DAG） |
| 跨成员任务分发 | Mail + Manager worktree 编排 |
| 调度者级任意委派（跨 agent targeting、fork、persistent） | Nebula 的 Delegate |
| **成员内部即时并行 + 深挖任务的上下文隔离** | **SubTask——唯一占有者，且无竞争者** |

生态位没被抢空：08-16 Frontend 双 worker 模式证明"同域多任务一个成员吃下"的场景只有 SubTask 能cover（Mail 给别的成员需要 Manager 中转+角色错配；Flow 太重）。

---

## 3 · 结论与建议

### 3.1 归因裁定

**主因：有场景但没引导**（prompt 注入缺位——11/12 资格 agent 零引导，Manager coaching 指令存在但派单实践未执行）。
**次因**：① 成员任务结构偏串行（并行被 Manager 层消费，剩余机会密度天然低）；② 上线头 24h status bug 的早期挫伤。
**排除**：工具语义问题——6 次使用全部实际交付、用法正确、零幻觉事故；一层限制无实证需求被卡。

### 3.2 建议（按性价比排序）

1. **成员 system.md 注入「并行子任务」小节**（最高性价比，纯 prompt 改动）：
   - 内容对齐 Nebula 已验证的引导密度：何时拆（任务含 ≥2 独立部分 / 深挖型子任务不污染主线 context）、何时别拆（依赖链、同文件、琐碎）、怎么写自包含 prompt
   - **以 08-16 Frontend 双 worker 为 canonical 例子**：共享 worktree、非重叠文件集、worker 禁 commit 由本体整合
   - 注入点：3 个团队的 11 个成员 system.md（模板统一，一次 entity-creator/手工批量）
2. **Manager 派单模板加一句**：多部分任务书尾部追加"此任务含 N 个独立部分，建议 SubTask 并行"——覆盖派发者（docs 规范的核心教训）
3. **不建议的改动**：给 SubTask 加 fork（破坏 clean-context 防幻觉设计，无实证需求）；追平 Delegate 使用率作为目标（角色不同，天花板就是每天 1-3 次量级）
4. **评判标准修正**：SubTask 健康度指标不该是绝对调用数，而是"**并行机会捕获率**"——类似 website Docs 六主题那种明确机会不再错失。可复样本审计口径（磁盘 worker 文件 + 派单文本对照）季度复查

### 3.3 数据可信度说明

- transcript 中 SubTask tool_use = 0 是 compaction 假象（三份父会话均被 800 条 UI 上限截断，决策上下文已不可恢复），磁盘 worker 文件是更可靠的存证
- subagent-tasks 登记数（4）< 磁盘 worker 数（6）：2 条孤儿记录的父会话任务文件已清理，不影响结论
- Delegate 91 次（拆分后）为磁盘 worker 文件精确计数；Mail 932 为 compaction 下界

---

*审计脚本与中间产物：/tmp/audit_tools2.py、/tmp/audit_rows.json（重启即清，无需保留）*
