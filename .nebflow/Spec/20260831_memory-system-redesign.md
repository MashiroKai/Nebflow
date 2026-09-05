# Nebflow 记忆系统重新设计方案

- 日期: 2026-08-31
- 类型: 方案设计（纯只读分析，未修改任何文件；本报告为唯一产出）
- 输入: 审计报告 `20260830_prompt-memory-audit.md` + 主仓源码走读（ContextRefresher / CompactService / AgentCore / DreamMode / PreCompactionHook / MemoryStore）+ 磁盘实测（~/.nebflow 记忆文件）
- 基线: 主仓 `/Users/dev/Claude code/Nebflow` @ main

---

## 0. 结论速览

| 裁定 | 核心方案 | 预计收益 | 风险等级 |
|---|---|---|---|
| ① Team agent 无记忆 | team memory.md 体系拆除；团队状态迁移到 5 类既有载体（git / rules.md / 项目 NEBFLOW.md / TeamTask / skills）；注入门控收窄为 Nebula-only | team agent 单请求 input 平均缩减 **~75-82%**（Backend 374KB→66KB），35 个记忆文件 + 35×112KB User.md 停止注入 | 中（依赖状态迁移先行） |
| ② 压缩只压缩 | 删除 save turn（两阶段→单阶段）；记忆更新移出压缩轮（事件时写入 + 周期整理）；NebulaMemoryHook 改为合并式写入 | 每次压缩少一次全量 LLM 调用（~200K input）；消除 qa-frontend 事故同族失败面；消灭 Dream Extract 追加式膨胀 | 低-中 |
| ③ 只记非代码事实 | 准入标准改写进系统提示词；User.md 112→45KB、Nebula memory 88→25KB；代码事实归 git、教训归 skills | 全注入面每轮省 **~20-25K tokens**（User.md 清理的乘数效应覆盖 73 个 agent） | 低 |

三裁定不是三个独立改动，而是**同一个目标架构的三面**：记忆收敛为 Nebula 专属、非代码事实、非压缩时机维护。实施顺序按依赖链排列（见 §6）。

**改造后规模预测**：Nebula system prompt 262KB→~130KB（记忆占比 76.7%→~54%）；team agent（Backend 为例）374KB→~66KB（记忆占比 82%→0）。

---

## 1. 现状盘点

![现状机制图](/tmp/mem-redesign/current.svg)

### 1.1 注入路径（谁注入什么）

组装链：`AgentCore.refreshTurn → ContextRefresher.refreshTurn → buildMemoryBlock`（AgentCore.scala:1752 / ContextRefresher.scala:371）。

**记忆注入门控**（ContextRefresher.scala:254-260）：
```scala
!headless && !isWorker && (isTeamAgent || agentName == "Nebula")
```
→ **Nebula + 全部 team agent**（51 个 agent.json，35 个有 memory.md）每轮注入；standalone agent（Explorer/Coder 等）、SubTask worker、headless 模式不注入。

**buildMemoryBlock**（ContextRefresher.scala:272-294）：User.md 全文 + agent memory.md 全文拼接，**无截断、无裁剪、无选择逻辑**。systemStable 缓存使记忆块只在生命周期节点（session 启动/压缩/重启）重建，turn 之间 byte-for-byte 复用。

### 1.2 压缩流程现状（两阶段模型）

```
auto-compact-trigger（input > threshold）
  → startDirectCompaction（AgentCore.scala:283）
     ├─ 1. PreCompactionHook（fire-and-forget）: NebulaMemoryHook（LLM 抽取→追加 Dream Extract）
     ├─ 2. Stage 1 Save turn: 注入 save-memory reminder，工具=[Write/Edit/Read]
     │      completion 信号 = toolCalls.isEmpty（AgentActor.scala:2686）
     │      drift 防护 = saveTurnDriftReminder + guardSaveTurn（AgentActor.scala:4099）
     ├─ 3. Stage 2 Compact turn: 注入 compact reminder，工具 = Nil
     │      <summary> 文本必须非空（F0 修复后）
     └─ 4. CompactionComplete → 队列合批注入续跑（F1） + 队列持久化（F2） + 窗口快照审计（F3）
```

- **save turn 只给记忆注入面 agent**（Nebula + team agent，镜像 shouldInjectMemory，AgentCore.scala:244）
- **F0-F3 注入屏蔽**（2026-08-30 已落地）是独立的可靠性层，与 save turn 无耦合，**保留不动**

### 1.3 记忆承载与用途（实测）

| 载体 | 规模 | 实际内容 | 用途判定 |
|---|---|---|---|
| `~/.nebflow/User.md` | 112,433 B / 406 行 | 个人信息 3KB + 工作风格 2KB + **产品决策 ~55KB** + 研究领域 3KB + 设计偏好 5KB + 工具环境 3KB + **24 段 Dream Extract 54KB** | 决策/偏好=非代码事实（保留）；Dream Extract=倾倒式日志（清理） |
| `agents/Nebula/memory.md` | 88,293 B / 273 行 | 记忆管理规则 4 条 + **日期批次段 ~20 段**（08-18 事故、08-25 晨间、08-26 夜间批次…）+ Scala/工具坑（→id 25 个）+ 路由决策 | 日期批次=一次性记录（清理）；坑→skills；路由决策=保留 |
| `teams/*/agents/*/memory.md`（35 个） | 943,812 B | Backend 210KB=工作日志（【✅ 已合并 main @hash…】commit/测试数/worktree 路径）；Manager=协调快照（当前快照/派发中/队列/教训）；qa=验收方法学 | 代码事实 95%（→git）；协调状态（→TeamTask+flow-mailbox）；方法学（→skills） |
| `memory/{id}.md`（218 个） | 254,917 B | 渐进披露详情文件 | 84 孤儿（39%）+ 28 broken ref；仅 Nebula 用 25 个 ref |
| `projects/<folder>/NEBFLOW.md` | **磁盘 0 个** | 代码机制就绪（rulesMd 块 ⑮）但从未启用 | 项目长期状态的现成载体 |
| `teams/<t>/rules.md`（9 个） | 3-12KB/个 | 团队行为规则 | **已注入**（teamCatalog 块 ⑬），可扩展为团队事实载体 |

### 1.4 审计报告之外的关键新发现（本次走读）

1. **Dream Extract 的直接来源 = NebulaMemoryHook**（compact/PreCompactionHook.scala + DreamMode.scala）：压缩前用 LLM 抽取最后 60 条消息的事实，`DreamMode.updateMemory` **每次追加一个 `## Dream Extract (timestamp)` 新 section**，从不合并进既有分区——这是 User.md 48% 膨胀的结构性根因（机制性追加，非模型行为）。
2. **`## 使用模式` section 也由 hook 每次重写**（U11 噪音的源头，DreamMode.scala:63-80）。
3. **MemoryAgent（Dream agent）定义存在但代码零引用**——`grep MemoryAgent src/main/scala` 无命中，处于休眠。其 system.md 设计的"observation 队列 + 24h 整理周期"从未接线，是"记忆更新移出压缩轮"的现成设计蓝本。
4. **team rules.md 已注入**（9 个 team）——裁定①的团队行为规则载体无需新建。
5. **Manager team memory 的协调快照**（当前快照/派发中/排队）与 TeamTask 工具（四态机+TTL+WS 事件）**功能重叠**——记忆里的协调快照是冗余副本。
6. 压缩期注入屏蔽（F0-F3）**与 save turn 无耦合**——单阶段化改造不会触碰已落地的可靠性层。

---

## 2. 新记忆体系设计（目标架构）

![目标架构](/tmp/mem-redesign/target.svg)

### 2.1 注入清单（改造后）

| 层 | 注入对象 | 改造 |
|---|---|---|
| 记忆块 810 | **仅 Nebula**（User.md 45KB + Nebula memory 25KB） | 门控收窄：`shouldInjectMemory = !headless && !isWorker && agentName == "Nebula"` |
| 记忆块 | team agent | **移除**（User.md + memory.md 均不注入） |
| 条件块 ⑬ teamCatalog | team agent | 保留（含 team rules.md） |
| 条件块 ⑮ rulesMd | 有 folder 的 agent | 保留；**启用 projects/<f>/NEBFLOW.md** 作为项目状态载体 |
| 条件块 800 skills | 声明 skills 的 agent | 保留；技术教训以 namespace skill 形态回归 |

### 2.2 记忆承载表（team 长期状态 → 新归属）

| 原 team memory 内容类型 | 占比（估） | 迁移目标 | 载体状态 |
|---|---|---|---|
| 代码事实（commit/测试数/worktree/实现细节） | ~60% | git 项目 repo（历史已含） | 无需迁移，直接删除 |
| 协调状态（派发中/排队/成员状态） | ~15% | TeamTask 工具（四态+TTL）+ flow-mailbox + 压缩 summary | 已存在，删除冗余副本 |
| 项目级长期决策/约定 | ~10% | `projects/<folder>/NEBFLOW.md` 或项目 repo docs/ | 机制就绪待启用 |
| 可复用技术教训（坑/方法学/工具行为） | ~10% | `skills/<namespace>/<name>/SKILL.md`（按 team namespace 订阅） | 需提取（skill-creator 流程） |
| 用户裁定（[USER-RULING]） | ~5% | 已存在 Mail 上报链 → Nebula 记 User.md | 链路已通，保留 |
| 团队行为规则 | 少量 | `teams/<t>/rules.md` | 已注入，保留 |

---

## 3. 三个裁定的落地方案

### 3.1 裁定①：Team agent 不该有记忆，只有 Nebula 有记忆

**改什么**：
1. `ContextRefresher.shouldInjectMemory`（scala 源）: `(isTeamAgent || agentName == "Nebula")` → `agentName == "Nebula"`。team agent 不再注入 User.md + memory.md。
2. `AgentCore.shouldInjectSaveReminder`（scala 源）: 随裁定②一并删除（见 3.2）。
3. 35 个 `~/.nebflow/teams/*/agents/*/memory.md`：**内容处置后再删除**（先提取教训→skills、确认代码事实已在 git，见迁移步骤）。
4. `~/.nebflow/agents/*/memory.md`（standalone 存档，不注入）：可保留为归档或随清理删除（Manager 35KB / Coder 2KB 等，无注入成本，建议保留 Nebula 之外不动）。
5. `system-prefix-for-teams.md` 的 Memory Writing 章节：改写为"团队状态承载"指引（写 rules.md / 项目 NEBFLOW.md / TeamTask，不写个人 memory）。

**Team 长期状态由谁承载**（三个候选的评估）：

| 候选 | 评估 | 结论 |
|---|---|---|
| Nebula 记忆承载 team 状态 | 88KB 已超载；team 状态 95% 是代码事实（违反裁定③）；12 个 team × 状态 = 必然爆炸 | **否决**——Nebula 只承载跨团队路由知识与用户事实 |
| 项目文档 NEBFLOW.md | 代码机制已就绪（rulesMd 块 ⑮ 自动注入，mtime 缓存）；磁盘零使用；天然按项目隔离 | **主载体**——启用 `projects/<folder>/NEBFLOW.md`，由 team Manager 维护 |
| TeamTask + flow-mailbox + 压缩 summary | 协调状态（派发/队列/成员忙闲）已是结构化载体；压缩 summary 承载单轮恢复 | **协调状态主载体**（现状已覆盖，删除记忆冗余副本） |

**风险**：
- **Manager 对成员状态的了解途径收窄**：原来可从成员 memory 读状态。缓解：① TeamTask 四态机是结构化的权威状态（比记忆可靠）；② flow-mailbox [RESULT] Mail 持久化在 sessions/；③ Manager 可在 rules.md 维护"团队状态页"（当前在途任务清单，低频率更新）；④ 成员汇报契约（完成回报契约）已强制 Mail [RESULT]。
- **压缩后恢复**：team agent 压缩后不再有 memory 兜底——恢复依赖压缩 summary。缓解：Manager/Worker compact reminder 的 resume 段已覆盖（派发中/当前任务/下一步），**但需验证**：单阶段化后 summary 质量是关键（见 3.2 强化点）。
- **一次性教训丢失风险**：改代码前必须完成教训→skills 提取（P0 先行），否则 git 历史只留 commit 不留"为什么"。

**收益量化**：
- 35 个 team memory.md（943.8KB）+ 35×User.md（3.9MB）停止注入
- Backend: 记忆块 307,788B→0，单请求 input ~374KB（≈125K tokens）→ ~66KB（≈22K tokens），**降 82%**
- Frontend 同量级（301KB→0）；html-builder 179KB→0；平均每个 team agent 降 ~75-82%
- 冷启动激活（provider 前缀缓存过期，team agent 常见场景）每轮省 40-120K tokens/agent

### 3.2 裁定②：上下文压缩时去掉记忆整理机制

**改什么**：
1. `AgentCore.startDirectCompaction`（scala 源）: 删除 save turn 分支——`shouldInjectSaveReminder` 恒 false，phase 恒为 Compact，直接注入 compact reminder。`CompactionJob.phase`/`CompactionPhase.Save` 枚举与 `guardSaveTurn`/`saveTurnDriftReminder`/`saveTurnTools` 白名单（AgentActor.scala:4099-4190、AgentCore.scala:1567-1587）整体退役。
2. `AgentActor.handleLlmCompleteBranch`（scala 源）: 删除 Save 相关两档（:2686/:2688），分支梯缩减为纯 Compact 判定。**保留 F0 档**（Compact thinking-only → handleCompactFailure）——这是 qa-frontend 事故修复，与 save turn 无耦合。
3. `CompactService.buildSaveMemoryReminder` + 三个 profile（Root/Manager/Worker SaveMemoryReminder，CompactService.scala:58-247）删除。
4. **Compact reminder 强化**（替代 save turn 的恢复保障）：Manager/Worker 的 `<summary>` 段已含"派发中/当前任务/下一步"——删除 save turn 后这些段成为唯一恢复载体，需在 WorkerCompactReminder 明确"未完成任务状态必须完整保留"（防止旧两阶段假设下 summary 依赖 memory 兜底的写法）。
5. **NebulaMemoryHook 改造**（不是删除）：抽取事实**合并写入既有 section**（按 CATEGORY 落到对应分区），不再追加 `## Dream Extract (timestamp)` 新 section；`## 使用模式` 停止自动重写。或更进一步：hook 输出改为写入 staging 文件 `~/.nebflow/memory/pending/`，由 memory-consolidation 流程合并——避免 hook 的无约束追加再次膨胀。
6. ManagerProgressHook / WorkerSkillHook：随裁定①（team 无记忆）退役。

**记忆更新时机（新制度，非压缩轮）**：
| 时机 | 触发 | 载体 |
|---|---|---|
| 用户显式告知/裁定 | 用户说"记下来/以后都这样"或给偏好陈述 | Nebula 当轮直接写 User.md（现行"例外条款"已允许） |
| 重要事件/里程碑 | 任务收官、团队回报、跨团队路由决策生效 | Nebula 主动写（不依赖压缩轮） |
| [USER-RULING] 上游 | team 成员 Mail 上报链（现行契约保留） | Nebula 收到后记 User.md |
| 周期整理 | memory-consolidation flow（已存在，**接线定时**：每周或 Nebula 手动） | 去重/清过期/压缩 |
| MemoryAgent 复活（可选 P2） | observation 队列 + 24h 整理周期（system.md 已写好，代码零引用） | 若接线，成为唯一自动化维护者 |

**风险**：
- **记忆新鲜度**：压缩轮是现状的主要写入时机，移出后依赖事件时写入。缓解：裁定③后记忆只含低频变化的"非代码事实+偏好"（产品决策/环境/身份——这些变化都发生在用户消息中，Nebula 实时可见）；高频的协调状态本就不该进记忆（已迁移到 TeamTask）。
- **压缩可靠性**：qa-frontend 事故根因（Compact thinking-only 滑落 mail-check）由 F0 修复，与 save turn 无关；但**单阶段化使压缩路径更短**（少一个阶段、少一套 drift/guard 防御面），整体失败面反而收窄。验收必须包含：单阶段压缩回归（正常压缩、thinking-only 响应、工具调用异常、注入屏蔽 F0-F3 全链复测）。
- **Dream Extract 停止后的事实丢失**：hook 合并式写入保留抽取能力，只是不再无约束追加。

**收益量化**：
- 每次压缩省一次全量 LLM 调用：200K 上下文的 agent 每压缩省 ~200K input tokens（save turn 重发全量历史）
- 消灭 save-turn 特有的两个失败面（drift 重写任务文件、guard 循环）
- 消灭 Dream Extract 结构性膨胀（User.md 中 54KB 的源头机制）

### 3.3 裁定③：记忆只记非代码事实和偏好

**准入标准（改写进 system-prefix-for-teams / Nebula system.md / save 相关提示）**：
```
记：用户身份 / 偏好（含裁定、纠正、默认选择）/ 环境事实（路径·端口·设备·代理）
    非代码领域知识（研究领域·学科背景）/ 跨会话的决策原因（"为什么"）
不记：行号 / API 签名 / 实现细节 / commit hash / 测试数 / worktree 路径
      批处理状态 / 一次性任务细节 / 可从一次 Read/Grep/git 恢复的任何东西
```

**现存记忆处置**：

| 文件 | 处置 |
|---|---|
| User.md Dream Extract 24 段（54KB） | 归档到 `~/.nebflow/docs/` 或直接删（git 可回溯）；仍有效的裁决已在上方正文 section 存在 |
| User.md 内部 11 处矛盾（审计 U1-U11） | 按审计表逐条修（以最新裁定为准） |
| User.md ↔ Nebula memory 10 组重复（X1-X10） | 保留一处权威，删其余 |
| Nebula memory 日期批次段（~20 段） | 删（一次性记录，终态已由 User.md 决策 + git 承载） |
| Nebula memory 内部问题（N1-N9） | 按审计表修 |
| Nebula memory Scala/工具坑（→id 条目） | 迁移到 skills（如已有 coding 相关 skill 则并入），memory 只留一行指针或删除 |
| team memory 工作日志 | 删（git 承载）；教训先提取到 namespace skills |
| memory/ 218 个详情文件 | 修 28 broken ref、删 84 孤儿、其余按引用保留 |

**收益量化**：
- User.md 112KB→45KB：73 个注入 agent 每轮省 ~22K tokens（**乘数效应最大的一步**）
- Nebula memory 88KB→25KB：Nebula 每轮省 ~21K tokens
- 与裁定①叠加后：User.md 的 45KB 仅注入 Nebula 一处（而非 73 处）

### 3.4 记忆承载与维护归属（Nebula 单点后的规模控制）

Nebula 是唯一记忆注入面后，其记忆块 = User.md + Nebula memory，目标 45+25 = 70KB（现 200.9KB）。规模控制四层：

1. **progressive disclosure 恢复**：长条目强制 `一行摘要 + →id`（system-prefix-for-teams 已有格式规范，但 0 执行——需在 Nebula memory 维护规则中制度化 + 定期核验 broken ref）
2. **memory-consolidation flow 定时接线**（周级）：审计 P1-6 建议，现无调度
3. **MemoryAgent 复活（可选）**：24h 整理周期，处理 staging 区合并
4. **硬上限**：User.md ≤50KB、Nebula memory ≤30KB，超出触发 consolidation（可在 consolidation flow 的验收条件里设二值检查）

---

## 4. 风险与权衡汇总

| 风险 | 等级 | 缓解 |
|---|---|---|
| team 无记忆后 Manager 失去成员状态视角 | 中 | TeamTask 结构化状态 + [RESULT] Mail 流 + Manager rules.md 团队状态页 |
| team agent 压缩后恢复能力下降 | 中 | compact reminder resume 段强化 + 单阶段化验收含压缩恢复 E2E |
| 记忆教训在迁移前丢失 | 中 | P0 先做教训→skills 提取，再删 memory 文件 |
| 移出压缩轮后记忆新鲜度下降 | 低 | 事件时写入制度 + 裁定③使记忆内容天然低频 |
| Nebula 记忆单点膨胀 | 中 | 渐进披露 + 周期 consolidation + 硬上限 |
| 单阶段压缩引入回归 | 低-中 | F0-F3 保留；save turn 相关 spec 改造 + 全量回归 + 压缩 E2E |
| 团队状态承载割裂（rules.md / NEBFLOW.md / TeamTask 多处） | 低 | 承载表明确"什么写哪里"；NEBFLOW.md 由 Manager 维护单一权威 |

---

## 5. 与审计报告的呼应（规模缩减预测）

| 指标 | 现状（审计 §5.2/§5.3） | 改造后预测 | 缩减 |
|---|---|---|---|
| Nebula system prompt | 262KB / ~87K tokens | ~130KB / ~43K tokens | **-50%** |
| Nebula 记忆块占比 | 200.9KB / 76.7% | 70KB / ~54% | -22pp |
| Backend 单请求 input | ~374KB / ~125K tokens | ~66KB / ~22K tokens | **-82%** |
| Frontend 单请求 input | ~368KB / ~123K tokens | ~66KB / ~22K tokens | **-82%** |
| 全部记忆文件总量 | 998KB（agents+teams） | ~75KB（User+Nebula+少量存档） | **-92%** |
| 每轮全系统记忆注入总量 | 73 注入点 × 各自记忆块 | 1 注入点 × 70KB | **-97%** |

审计 P0/P1 建议在本方案中的落点：
- P0-1（压缩 Backend/Frontend 记忆）→ 裁定①整体解决（不止压缩，直接移除）
- P0-2（User.md 大扫除）→ 裁定③ P0 阶段
- P1-3（Nebula memory 去重）→ 裁定③ P0 阶段
- P1-4（修 ref/孤儿）→ 裁定③ P0 阶段
- P1-5（team 记忆格式规范）→ **被裁定①取代**（不再需要）
- P1-6（memory-consolidation 定时）→ 裁定②新时机制度
- P2-7（User.md 分层/裁剪注入）→ 被裁定①取代（无 team 注入即无裁剪需求）
- P2-8（memory mtime 触发重建）→ 可选 P2 保留

---

## 6. 分阶段实施建议

### P0 — 记忆内容清理（裁定③，纯删减零机制改动，低风险高收益）

**范围**：
1. User.md 大扫除：归档/删除 24 段 Dream Extract、修 U1-U11 矛盾、合并 X1-X10 重复 → 112→45KB
2. Nebula memory 去重去旧：删日期批次段、修 N1-N9 → 88→30KB（为裁定①保留少量路由知识）
3. 教训提取（团队）：从 Backend/Frontend/qa-* 等 memory 提取可复用教训 → namespace skills（skill-creator 流程），**这一步是裁定①的前置**，不得跳过
4. 修 28 broken ref + 清 84 孤儿详情文件
5. 启用 `projects/<folder>/NEBFLOW.md`：先在 nebflow-project 试点（Manager 维护项目状态页），验证注入生效

**验收点**：
- [ ] User.md ≤50KB、Nebula memory ≤30KB（wc -c 二值判断）
- [ ] `grep -c "Dream Extract" User.md` = 0（或全部归档）
- [ ] 审计表 U1-U11 / X1-X10 / N1-N9 逐条核对：矛盾条目已按最新裁定修正
- [ ] broken ref = 0、孤儿详情文件 = 0
- [ ] 每个 >10KB 的 team memory 已完成教训→skills 提取（skill 订阅清单有记录）
- [ ] 全量测试通过 + 隔离实例冒烟（记忆注入仍按现状，无机制改动）

### P1 — 压缩单阶段化 + 记忆更新时机（裁定②）

**范围**：
1. 删除 save turn：`startDirectCompaction` 恒 Compact、退役 CompactionPhase.Save/guardSaveTurn/saveTurnTools、删三个 SaveMemoryReminder
2. `handleLlmCompleteBranch` 删 Save 两档，保留 F0（thinking-only → failure）
3. Compact reminder resume 段强化（Manager/Worker）
4. NebulaMemoryHook 改造：合并式写入（按 CATEGORY 落 section）或 staging 文件 + consolidation 合并；停用 `## 使用模式` 自动重写
5. ManagerProgressHook / WorkerSkillHook 退役
6. memory-consolidation flow 接线定时（周）

**验收点**：
- [ ] 单阶段压缩 E2E：正常压缩 → summary 生效 → 续跑恢复（含注入屏蔽 F0-F3 全链复测）
- [ ] 压缩回归 spec：thinking-only 响应走 failure 通道（F0 回归钉保留）；工具调用异常走 failure；无 Save 阶段日志
- [ ] save turn 相关测试改造后全量通过（spec 数对账：删 save-turn 用例数 = 预定数）
- [ ] 记忆更新新制度文档化（写入 Nebula system.md 记忆维护章节）
- [ ] 隔离实例冒烟：压缩后 agent 无 memory 兜底也能从 summary 恢复任务（模拟 Manager 长会话）

### P2 — Team 记忆移除 + 注入门控收窄（裁定①，架构级）

**范围**：
1. 确认 P0 教训提取与 P1 单阶段化完成后：
   - `ContextRefresher.shouldInjectMemory` 改为 `agentName == "Nebula"`
   - 删除 35 个 team memory.md（先归档备份）
   - `system-prefix-for-teams.md` Memory Writing 章节改写为状态承载指引
2. team 状态承载制度化：rules.md 团队状态页 + NEBFLOW.md 项目状态页 + TeamTask 协调状态，写入承载表到 `docs/`（活文档）
3. 记忆维护归属收口：Nebula 唯一注入面；progressive disclosure 制度化（长条目 →id 强制）；硬上限纳入 consolidation 验收

**验收点**：
- [ ] `shouldInjectMemory` 单测更新：team agent 断言不注入、Nebula 注入、worker/headless 不注入
- [ ] team agent 单请求 input 实测（日志 inputTokens）≤ 现状的 30%（Backend 基准：~374K→≤110K）
- [ ] team 状态承载表落地：每个 team 的 NEBFLOW.md 或 rules.md 有 Manager 维护的当前状态页
- [ ] 三周观察期：无"压缩后任务丢失"报障、无"Manager 不知成员状态"报障
- [ ] memory-consolidation 周报：ref 健康度（broken=0）、User.md/Nebula memory 大小不反弹

### 顺序依据

P0 → P1 → P2 是**依赖链**而非并行：裁定③清理先行（P0）让裁定①的迁移对象最小化；裁定①（P2）依赖 P0 的教训提取与 P1 的压缩恢复保障（team 无 memory 后压缩 summary 是唯一恢复载体，必须先验证单阶段压缩可靠）。裁定②（P1）与裁定①（P2）**相互强化**：team 无记忆后 save turn 本就无事可做，先删 save turn 再删记忆，每一步都是可独立验收的小步。

---

*方案完。本报告未修改任何系统文件；产出仅本报告。*
