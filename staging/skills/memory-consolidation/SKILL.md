---
name: memory-consolidation
description: 记忆审计与整理方法论（memory-management 方案落地版）——触发时机（周审计 Schedule 周日 21:30 / 压缩后与重启后提醒 / >80% 预算即时任务 / 大重构后）、写入三问准入（hard-to-obtain / reusable / current-state-first）、取代而非追加（推翻裁定同轮 append+remove 成对）、T1/T2/T3 生命周期（T1 永久+取代收缩；T2 闭环即删兜底 7 天；T3 = Dream 稳定节 14 天未晋升自动淘 + 60 条 FIFO）、四条清理判据（stale/冗余/错位/低价值）、预算（User.md 50KB 硬顶 40KB 软警；memory.md 30KB/24KB——写入侧 MemoryEdit 预算闸强制，注入侧永不截断）。Use when 记忆膨胀、预算 WARN/超限拒绝、重大代码变更后、或周审计报告回投需要执行时。
when_to_use: Nebula 例行或事件驱动整理自己与 User 的记忆文件（~/.nebflow/User.md、~/.nebflow/agents/Nebula/memory.md）；周审计报告（memory-consolidation flow scanner-only 产出）回投后的执行；收到 MemoryEdit 预算 WARN/拒绝后整理；大重构/架构变更后清过时条目。注意：team agent 不再有 memory（2026-08-31 裁定），不扫描不重建 teams/*/agents/*/memory.md；审计节点只产报告不写记忆（2026-09-05 职权红线，2026-09-12 局部取代）；执行权归**记忆整理 agent**（`memory-consolidator`，压缩双轨第二轨）——Nebula 的 MemoryEdit 已退化为**纯记账**（`queued q-… (applied at next compaction)`），不再由 Nebula 本人逐条执行。
language: zh
status: active
last_verified: 2026-09-05
---

# Memory Consolidation — 记忆审计与整理方法论

## 触发时机（四路）

1. **周审计**：Schedule 周日 21:30 触发 memory-consolidation flow（scanner-only）→ 条目级报告回投 → 发现以队列条目投入，由下一次压缩的记忆整理 agent（`memory-consolidator`）消费执行（Nebula 只记账）。
2. **生命周期**：压缩完成后 / 宿主重启后首会话的 Memory hygiene 提醒 → 顺手做一轮 T2 闭环清扫。
3. **阈值**：任一文件 >80% 软警线（User 40KB / memory 24KB）→ MemoryEdit 结果带 WARN 或注入 IMMEDIATE 提醒 → **当轮安排整理，不等周日**。
4. **事件驱动**：重大代码/架构变更后（旧事实失效风险高）。

## 扫描对象

- `~/.nebflow/User.md` — 用户级记忆（硬顶 50KB / 软警 40KB）
- `~/.nebflow/agents/Nebula/memory.md` — Nebula 自身记忆（硬顶 30KB / 软警 24KB）

team agent 记忆已删除（内容归档于 docs/memory-archive/），不要扫描或重建。

## 写入准入（三问，不满足不写）

1. **hard-to-obtain** — 一次 Read/Grep/git 就能恢复的不记（commit hash、HEAD 链、worktree 路径、代码事实）。
2. **reusable** — 一次性任务细节不记（某批 deck 页数、某次 PR 状态）。
3. **current-state-first** — 以现状为准：新状态 **update** 进既有条目，不新开条目；新裁定推翻旧裁定时 **append 与 remove 同轮成对**（取代而非追加，不留「已被取代还躺着」的旧条目）。

**分级标注**（新条目末尾标 [T1] / [T2]；T3 由 Dream hook 自动管理不手标）：

- **T1 裁定类**（用户裁定/偏好/身份/环境/教训）— 永久；唯一收缩路径 = 取代规则。
- **T2 状态类**（批次段/待重启清单/在途队列/验收清单）— 事件闭环即删；兜底 **N=7 天** 超期必删。memory.md 不再开「日期批次段」形态——状态类统一进「当前状态」节滚动清零，教训晋升进「运维教训（永久）」。
- **T3 Dream 稳定节** — **M=14 天** 未显式晋升（正文节出现同事实）即被 hook 自动淘；稳定节 **60 条 FIFO**（新进旧出）。

**预算纪律**：写入侧 MemoryEdit 预算闸强制——append/update 落盘前校验新文件字节，超硬顶拒绝（附最大节定位+整理指引），超 80% 放行但 WARN。收到 WARN = 当轮排整理；被拒绝 = 先删后写。注入侧永不截断（裁定 2026-09-05）。

## 清理判据（对每条 entry 检查）

1. **Stale** — 与当前代码/现状矛盾。现状是权威；拿不准就对照实际文件/代码库验证。
2. **冗余** — 同一事实记了两遍，或一条完全被另一条覆盖。
3. **错位** — 事实放错章节/文件（用户级 vs agent 级错置）。
4. **低价值** — 一次性任务细节、瞬态状态、一条命令就能从代码读出的东西（行号、API 签名、commit hash）。

## 操作步骤

### 第一步：审计（只读；周期轮由 flow 的 scanner 节点代劳）

逐节扫描两文件，按四判据 + T2/T3 TTL 产出条目级动作清单：

```
1. DELETE <file>, "## 节名" 内 "- <条目前缀≤20字符>..." — stale+T2超期: <原因>
2. UPDATE <file>, "- <前缀>..." → "- <新文本>" — <原因>
3. MERGE <file>, "- <A>..." + "- <B>..." → "- <合并文本>" — 冗余对
4. KEEP — 仅在有意标注边界时使用
```

多文件按文件分组。无需改动时输出一行核查摘要。九项指标头部（字节/预算、条目数、重复对、死条目、T2 超期、T3 未晋升、删除比、审计间隔）见 flow scanner system.md。

### 第二步：执行（Nebula 调 MemoryEdit：replace_section / remove / update）

1. 按清单顺序执行；一个动作一次外科手术式编辑——用引语定位确切条目，不许近似匹配。
2. 定位不到的条目（扫描后文件已变、或匹配歧义）→ **SKIP 并记录 `SKIPPED #n <原因>`**——绝不猜删相邻文本。
3. 不发明清单外动作；编辑中发现新问题 → 记 `OBSERVATION:` 留下轮。
4. 整节 >20 条的大消化：执行前先 `git -C ~/.nebflow add <file> && git commit` 打快照点（回溯语义锚）。
5. 每次编辑后确认被编辑区域符合预期再进行下一动作。

### 第三步：报告（事实，非散文）

按文件分组：`<绝对路径>` → N executed / M skipped（#编号 原因）/ K observations + 整理后字节/预算百分比。

## 纪律

- **整理以减法为主**：stale 与冗余条目对每个未来会话都是税；去留拿不准时按判据裁，倾向删（但低置信动作标「作者裁」不硬删）。
- **现状是权威**：文件实际状态与清单矛盾时（条目已不在、章节已移动），以文件为准标 SKIP。
- **职权红线**：审计/扫描节点只产报告、**零记忆写权**；记忆文件写权归**记忆整理 agent**（`memory-consolidator`，压缩双轨第二轨，消费 `~/.nebflow/memory/queue.jsonl` 的记账条目）——Nebula 不再逐条执行 `MemoryEdit`（其已是纯记账）。
- 报告只写计数、路径、skip 项。
