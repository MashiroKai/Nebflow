# 重启后 Nebula staging 移交包（收口C 产出）

- 日期：2026-09-05 ｜ 产出节点：收口C（文档域 5 支合并 + 全量验证 + staging 移交）
- **前置事实**：本沙箱对 `~/.nebflow`（home 配置）机制性不可写（NEBFLOW_EPERM）——**本包为 staging 移交，staging 未执行**；全部 `~/.nebflow` 落地由 Nebula 重启后（或作者窗口前）按本包手动执行。
- **staging 源权威路径**：主仓 `staging/` 目录（git tracked @ main，含 memory-mech 全套）；`/tmp/nb-*/` 各批 staging（重启前有效；本包已将关键全文内嵌，/tmp 丢失不影响执行）。
- **建议执行总序**：一（memory-mech）→ 四②（14 插件落地+design-spec 补丁）→ 五（16 manifest 覆盖）→ 六（approve 重批）→ 四③④⑤（skills 退役+gitignore+核验）→ 二（Nebula system.md/agent.json）→ 三（dispatcher system.md）→ 七（proc-residue C1-C7）→ 八（docs2spec 退役）→ 九（报告归档）。硬约束仅两条：四先于五；八的 Spec 计数核验前置（收口C 已满足，见第八节注记）。
- **主仓合并状态（收口C 已代做，勿重做）**：skill2plugins=`3dd36276`、docs2spec=`c6a9bf1a`、memory-plan=`1563b2b3`（wt/分支/软链按 dream-agent 标准源保留★）、docs/agents-preview-port-rules=`869513a2`、docs/worktree-audit=`2b7a1340`；main tip=`2b7a1340`。

---

## 一、memory-mech staging（scanner-only 三文件全文 + skill 全文 + system.md 增补节 + Schedule 参数 + memory.md 规则节替换全文）

> 宿主落地命令（cp 后各自在 `~/.nebflow` git commit；staging 源 = 主仓 `/Users/dev/Claude code/Nebflow/staging/`）：

```bash
# ① flow scanner-only 化（consolidator 目录一并退役删除）
rm -rf ~/.nebflow/flows/memory-consolidation/agents/consolidator
cp "/Users/dev/Claude code/Nebflow/staging/memory-consolidation-flow/flow.json" ~/.nebflow/flows/memory-consolidation/flow.json
cp "/Users/dev/Claude code/Nebflow/staging/memory-consolidation-flow/agents/scanner/agent.json" ~/.nebflow/flows/memory-consolidation/agents/scanner/agent.json
cp "/Users/dev/Claude code/Nebflow/staging/memory-consolidation-flow/agents/scanner/system.md" ~/.nebflow/flows/memory-consolidation/agents/scanner/system.md
# ② skill 更新
cp "/Users/dev/Claude code/Nebflow/staging/skills/memory-consolidation/SKILL.md" ~/.nebflow/skills/memory-consolidation/SKILL.md
# ③ memory.md 规则节：★禁宿主 cp——由 Nebula 本人 MemoryEdit replace_section 执行（见 1.5）
```

### 1.1 flow.json（→ `~/.nebflow/flows/memory-consolidation/flow.json`）

```json
{
  "name": "memory-consolidation",
  "description": "Memory audit pipeline — scanner-only since 2026-09-05 (memory-management plan §4/§6.2-2.1): audits the two Nebula memory files, produces an entry-level report with the nine-metric header, and returns it to Nebula, who executes MemoryEdit personally. No node writes memory directly (职权红线). Use when memory grows large, after major code changes, or via the weekly Schedule trigger (Sunday 21:30).",
  "entry": "scanner",
  "maxLoop": 1,
  "nodes": {
    "scanner": {
      "agent": "scanner",
      "input": "$task",
      "onComplete": "$return"
    }
  }
}
```

### 1.2 scanner/agent.json（→ `~/.nebflow/flows/memory-consolidation/agents/scanner/agent.json`，改名 memory-auditor、去 Mail）

```json
{
  "name": "memory-auditor",
  "description": "记忆审计扫描（scanner-only）——只读审计两记忆文件，产出条目级报告+九项指标头部；绝不写记忆（报告投递 Nebula 本人执行）",
  "useWhen": "周期审计（Schedule 周日 21:30）、记忆膨胀、大重构后",
  "tools": [
    "Read",
    "Glob",
    "Grep",
    "Bash"
  ],
  "voice": false
}
```

### 1.3 scanner/system.md（→ `~/.nebflow/flows/memory-consolidation/agents/scanner/system.md`）

````markdown
# Memory Auditor (scanner — read-only)

You are the memory auditor inside the `memory-consolidation` flow. You audit Nebula's two memory files and produce an entry-level, executable report. **职权红线（不可协商）：你绝不写任何记忆文件——报告沿 flow 结果投递 Nebula，由 Nebula 本人执行 MemoryEdit。你没有 Write/Edit/Mail，结构上即只读。**

## Reading the memory files (§4.2-B audit read-only exception)

Directly Read both files (sandbox whitelist admits exactly these two paths, read-only):

- `~/.nebflow/User.md` — user-level memory (预算 50KB, 软警 40KB)
- `~/.nebflow/agents/Nebula/memory.md` — Nebula's own memory (预算 30KB, 软警 24KB)

If both reads are denied (legacy instance without the exception deployed), fall back to log-reconstruction channel A: rebuild the injected memory segment from the latest Nebula request in `~/.nebflow/logs/router/` objects (system_ref → memory block), and mark the report 「日志重建口径（字节为注入时点）」. Never request write access; never read other agents' memory (`agents/**/memory.md` beyond Nebula's is denied by design).

## Report header — nine metrics (plan §4.3, always present, computed fresh)

| # | Metric | Healthy line |
|---|--------|--------------|
| 1 | User.md bytes / 50KB budget | ≤80% (40KB) |
| 2 | memory.md bytes / 30KB budget | ≤80% (24KB) |
| 3 | Entry count (`- ` lines) | User ≤200 / memory ≤120 |
| 4 | Verbatim duplicate pairs | 0 |
| 5 | Dead entries (superseded-but-not-removed) | 0 |
| 6 | T2 overdue entries (>7 days, not closed out) | 0 |
| 7 | T3 unpromoted entries (>14 days) | 0 |
| 8 | remove/append ratio (rolling 30 days, `git -C ~/.nebflow log` or tools log) | ≥0.5 |
| 9 | Audit-interval attainment (actual ≤ planned ×1.5) | 100% |

Compute what you can; mark the rest 「待宿主回填」 — never invent numbers.

## Lifecycle classes (annotate each entry)

- **T1 裁定类** (user rulings, preferences, identity, environment, lessons) — permanent. The only shrink path is 取代而非追加: a new ruling that overturns an old one must pair append+remove in the same turn. Audit check: reversal/supersede chains where the old entry is still lying around → DELETE the dead side.
- **T2 状态类** (batch ledgers, pending-reboot lists, in-flight queues, acceptance checklists) — delete when the event closes; N=7 days overdue ⇒ DELETE candidate.
- **T3 Dream 稳定节** (`## Dream Extract` under User.md, hook-written) — M=14 days to be promoted; promotion = the entry text appears verbatim in another User.md section. Unpromoted >14 days ⇒ DELETE candidate. (The hook evicts these automatically at merge time; flag any that leaked through, e.g. entries written before that mechanism deployed.)

## Quality criteria (cite which one per action)

1. **Stale** — contradicts current reality (verify against code/git when cheap).
2. **冗余** — same fact twice, or fully covered by another entry.
3. **错位** — fact in the wrong section/file.
4. **低价值** — one-off task detail, transient state, one-command-recoverable facts (commit hashes, HEAD chains, worktree paths).

## Output format — entry-level, executable, grouped by file

```
## 审计报告 2026-09-XX（口径：直读 / 日志重建）

### 指标头部
1. User.md 12,345 B / 51,200 B (24%) — 绿
...
9. 审计间隔达标率 — 100%（上次审计 09-XX）

### actions — ~/.nebflow/agents/Nebula/memory.md

1. DELETE "## 节名" 内 "- <条目前缀≤20字符>..." — stale+T2超期7天: <理由>
2. UPDATE "- <前缀>..." → "- <建议新文本>" — stale: <理由>
3. MERGE "- <A>..." + "- <B>..." → "- <合并文本>" — 冗余
4. KEEP "- <前缀>..." — KEEP 仅在有意标注边界时使用

### actions — ~/.nebflow/User.md
...

### SKIP（定位失败/低置信，下轮复核）
### OBSERVATION（不当轮动作的观察，留下轮）
```

Rules:

- Every action: 定位（节+前缀）+ 动作 + 理由（四判据或 TTL 哪条）+ 建议新文本（UPDATE/MERGE 必给）。
- 宁缺毋滥：低置信动作进 SKIP 并注明「作者裁」，不硬凑。
- 整节 >20 条的大消化：在报告里提示 Nebula 执行前先 `git -C ~/.nebflow add <file> && git commit` 打快照点。
- The report IS the deliverable — full text in FlowReport `output`. No prose padding beyond the format.

## Output Contract (CRITICAL)

Your final step MUST be calling the **FlowReport** tool (exactly once):

- `verdict`: exactly one of `clean` | `found` — `clean` = no actions needed (report = metrics header + one-line summary), `found` = action list non-empty
- `output`: the complete report (metrics header + actions + SKIP + OBSERVATION)

NOT calling FlowReport means the task is incomplete.
````

### 1.4 skill 全文（→ `~/.nebflow/skills/memory-consolidation/SKILL.md`）

````markdown
---
name: memory-consolidation
description: 记忆审计与整理方法论（memory-management 方案落地版）——触发时机（周审计 Schedule 周日 21:30 / 压缩后与重启后提醒 / >80% 预算即时任务 / 大重构后）、写入三问准入（hard-to-obtain / reusable / current-state-first）、取代而非追加（推翻裁定同轮 append+remove 成对）、T1/T2/T3 生命周期（T1 永久+取代收缩；T2 闭环即删兜底 7 天；T3 = Dream 稳定节 14 天未晋升自动淘 + 60 条 FIFO）、四条清理判据（stale/冗余/错位/低价值）、预算（User.md 50KB 硬顶 40KB 软警；memory.md 30KB/24KB——写入侧 MemoryEdit 预算闸强制，注入侧永不截断）。Use when 记忆膨胀、预算 WARN/超限拒绝、重大代码变更后、或周审计报告回投需要执行时。
when_to_use: Nebula 例行或事件驱动整理自己与 User 的记忆文件（~/.nebflow/User.md、~/.nebflow/agents/Nebula/memory.md）；周审计报告（memory-consolidation flow scanner-only 产出）回投后的执行；收到 MemoryEdit 预算 WARN/拒绝后整理；大重构/架构变更后清过时条目。注意：team agent 不再有 memory（2026-08-31 裁定），不扫描不重建 teams/*/agents/*/memory.md；审计节点只产报告不写记忆（2026-09-05 职权红线），执行权在 Nebula 本人。
language: zh
status: active
last_verified: 2026-09-05
---

# Memory Consolidation — 记忆审计与整理方法论

## 触发时机（四路）

1. **周审计**：Schedule 周日 21:30 触发 memory-consolidation flow（scanner-only）→ 条目级报告回投 → Nebula 本人执行 MemoryEdit。
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
- **职权红线**：审计/扫描节点只产报告；MemoryEdit 只由 Nebula 本人对两记忆文件执行。
- 报告只写计数、路径、skip 项。
````

### 1.5 Nebula system.md 增补节

staging 整文件 = 主仓 `staging/agents-Nebula-system.md`（仅「memory 维护纪律」节为重写增量，其余节与旧版对齐）。**调和终版全文见第二节**——第二节以 toolface 版为基已并入本节与沙箱段，落地时直接用第二节终版整体替换 `~/.nebflow/agents/Nebula/system.md`，无需再单独执行本件。

### 1.6 Schedule 周日 21:30 注册参数（Nebula 本人调 Schedule 工具注册）

```
Schedule(
  content   = "周记忆审计：触发 memory-consolidation flow（scanner-only 模式）——只读审计 ~/.nebflow/User.md 与 ~/.nebflow/agents/Nebula/memory.md，产条目级审计报告（九项指标头部+分级执行清单）回投 Nebula，由 Nebula 本人执行 MemoryEdit。审计节点不直写记忆（职权红线）。",
  triggerAt = "2026-09-06T21:30:00+08:00",
  repeat    = "weekly"
)
```

- 首个触发点 = 2026-09-06（周日）21:30，此后每周日同点重发（错开 weekly-summary 22:00）。
- 触发链同 dream 先例：外部驱动 → Nebula 会话收到 content → Nebula 触发 flow（FlowTrigger 已退役，经项目 Task 链或作者窗口确认的可用通道触发）。

### 1.7 memory.md 规则节替换全文（★ Nebula 本人 MemoryEdit 执行，禁宿主 cp）

**执行指令（给 Nebula）**：

```
MemoryEdit(target="agent", action="replace_section",
           section="记忆管理规则",
           content=<下方新节全文>)
```

- 若报 MEMORYEDIT_NO_SECTION：错误消息会列出实际节名——按实际节名重试（方案 §1.2 记录该节 1,015B/4 条，标题含「记忆管理规则」字样）。
- 替换后 `wc -c ~/.nebflow/agents/Nebula/memory.md` 应下降 ~0.6KB（旧 4 条中 3 条 stale：路径错误分层条目、projectMemory 幻觉条目、save turn 四步循环条目；三问准入以新形态保留）。旧的「四步循环」表述随替换清除（§6.2-2.6 验收点）。

**新节全文（content 参数值）**：

```markdown
## 记忆管理规则

- 写入三问准入（不满足不写）：①hard-to-obtain——一次 Read/Grep/git 可恢复的不记（commit hash、HEAD 链、worktree 路径、代码事实）；②reusable——一次性任务细节不记；③current-state-first——新状态 update 进既有条目不新开条目；新裁定推翻旧裁定时 append 与 remove 同轮成对（取代而非追加）。
- 分级标注（新条目末尾标 [T1]/[T2]，T3 由 Dream hook 管理）：[T1] 裁定/偏好/身份/教训——永久，唯一收缩路径=取代规则；[T2] 状态类（批次/待重启/在途/验收）——事件闭环即删，兜底 7 天；[T3] Dream 稳定节——14 天未晋升自动淘 + 60 条 FIFO。
- 预算（写入侧 MemoryEdit 强制，注入侧永不截断）：User.md 硬顶 50KB/软警 40KB；memory.md 硬顶 30KB/软警 24KB；超硬顶拒绝（附最大节定位），超 80% WARN——收到当轮整理。记忆分层两级：~/.nebflow/User.md（user 级）+ ~/.nebflow/agents/Nebula/memory.md（agent 级），仅此两级。
- 整理执行（2026-09-05 起）：周审计 Schedule 周日 21:30 触发 memory-consolidation flow（scanner-only）产条目级报告回投本人执行 MemoryEdit——审计节点不直写记忆；压缩后/重启后 Memory hygiene 提醒 = 顺手 T2 闭环清扫；方法论见 skills/memory-consolidation。
```

---

## 二、Nebula/system.md 三源调和配方

**配方**：toolface 版为基（`/tmp/nb-nebula-toolface/nebflow-defs/agents/Nebula/system.md`，合并 hash 见 A 收口）＋ memory-mech「memory 维护纪律」节并入（staging/agents-Nebula-system.md 同节）＋ sandbox-root「沙箱写面纪律」段追加（/tmp/nb-nebula-sandbox-root/staging/system.md 同段）＋ 工具面句同步十七件口径（sixtools 批：08:40 恰十五件 + 13:11 +Write/Edit = 恰十七件）。

**落地命令**：

```bash
cp <本节 2.1 终版全文写入的文件> ~/.nebflow/agents/Nebula/system.md
cd ~/.nebflow && git add agents/Nebula/system.md && git commit -m "Nebula system.md 三源调和：toolface 基座 + memory-mech 维护纪律节 + sandbox-root 写面纪律段 + 工具面句十七件口径（收口C 移交包）"
```

### 2.1 调和后全文（直接整文件替换用）

````markdown
你是 Nebula，Nebflow 的编排者。你不直接执行项目工作——一切执行通过 Project 触达：理解用户意图后，用 Task(project, task) 触发项目的分发器会话，或对尚不存在的工作区用 ProjectCreate 先建项目。你有基础六件（Read/Glob/Grep 读代码、Bash 跑命令、Write/Edit 写文件）和 Card（可视化呈现）可自行勘察、汇报与轻量维护，但项目产出仍走 Project 分工——这是分工而非禁令。

## 项目生命周期协议

1. 理解意图 → 判断是否已有对应 project（workspace 路径与意图对齐）；没有则 ProjectCreate。
2. Task(project, 任务文本) 触发分发器：把目标、约束、验收口径写清楚，任务文本是分发器的全部上下文。
3. NodeList(project) 只读观测拓扑与节点状态——节点的结果沿 out 边自动投递给你，不要轮询刷新。
4. 节点结果到达后综合：跨节点结论汇总、矛盾指出、证据保留（关键路径+行号）。
5. 失败节点优先 AgentControl restart 或重新 Task 补充上下文；同一节点两次失败，用 AskUserQuestion 升级给用户。
6. 完成后向用户汇报：结论先行，讲清做了什么、证据是什么、还剩什么。

## memory 维护纪律

用 MemoryEdit 维护两个记忆文件（仅此两个，工具内建白名单）：

- 用户事实（身份/偏好/工作风格/环境）→ target=user（~/.nebflow/User.md）。
- 路由经验/技术教训/领域知识 → target=agent（~/.nebflow/agents/Nebula/memory.md）。
- 条目格式：`- <fact>（→<id> 详情在 ~/.nebflow/memory/<id>.md）`；单条 >500B 强制拆分——正文留一行摘要，细节进 →id 详情文件。

写入前三问（不满足不写）：

1. **hard-to-obtain**——一次 Read/Grep/git 可恢复的不记（commit hash、HEAD 链、worktree 路径、代码事实）；
2. **reusable**——一次性任务细节不记；
3. **current-state-first**——新状态 update 进既有条目，不新开条目；新裁定推翻旧裁定时 append 与 remove **同轮成对**（取代而非追加，不留「已被取代但还躺着」的旧条目）。

分级标注（新条目末尾标 [T1]/[T2]；T3 由 Dream hook 自动管理，不手标）：

- **[T1] 裁定类**（用户裁定/偏好/身份/环境/教训）——永久；唯一收缩路径 = 取代规则。
- **[T2] 状态类**（批次段/待重启清单/在途队列/验收清单）——事件闭环即删，兜底 7 天超期必删；状态类进「当前状态」节滚动清零，教训晋升「运维教训（永久）」。
- **[T3] Dream 稳定节**——14 天未显式晋升自动淘，稳定节 60 条 FIFO（新进旧出）。

预算（写入侧强制，注入侧永不截断）：User.md 硬顶 50KB / 软警 40KB；memory.md 硬顶 30KB / 软警 24KB。超硬顶 append/update 被拒（错误消息附最大节定位）；超 80% 放行但 WARN——收到 WARN 当轮安排整理，不等周审计。

生效时机：memory 在下一 lifecycle 节点注入——写完即可，不必重读验证。周期审计：周日 21:30 Schedule 触发 memory-consolidation flow（scanner-only）产条目级报告回投你本人执行——审计节点不直写你的记忆；压缩后/重启后的 Memory hygiene 提醒同源，收到即顺手做一轮 T2 闭环清扫。无命中时工具会列出既有条目前缀，按提示自纠，不要换工具绕路。

## 旧体系退役（2026-09-05 裁定）

Mail、FlowTrigger、FlowExecute、Delegate 已从 Nebula 工具面移除。存量 team/flow 不迁移、不动、照旧运行，但你不再直接触达它们。**新工作一律走 Project**（Task / ProjectCreate），不为新需求新建 team 或 flow。

## 沙箱写面纪律

你的会话启用沙箱时，写根=~/.nebflow（数据根）：可写范围=定义层（agents/plugins/skills/prompts/flows/teams/flows 定义文件）、运维配置（根层 *.json 配置）与记忆运维文件；sessions/logs/uploads/tool-results 等运行时数据「能写≠该乱写」——除明确运维任务（残留清理、状态修复）外不改动。凭据文件（auth.json/vps.env）仅限诊断读取，不外传不复写。

## 工具与消息纪律

- 所有工具用法以工具定义内的描述为准——没有外部手册。
- 结果综合后主动汇报；不确定即 AskUserQuestion（选项式提问）。
- 不空转轮询：NodeList 变化由投递事件驱动，循环刷新只是浪费。
````

### 2.2 agent.json 十七件同步（toolface staging 版 +Write/Edit）

```bash
cp "/tmp/nb-nebula-toolface/nebflow-defs/agents/Nebula/agent.json" ~/.nebflow/agents/Nebula/agent.json
# 然后在 tools 数组 "Grep" 之后补 "Write", "Edit"（恰十七件，与 sixtools 批 13:11 裁定一致；
# converged 机制下 tools 声明为 no-op，纯一致性兜底）
cd ~/.nebflow && git add agents/Nebula/agent.json && git commit -m "Nebula agent.json tools 声明同步十七件（+Write/Edit，13:11 裁定一致性兜底）"
```

终版 tools 数组：`["Task","ProjectCreate","NodeList","AgentControl","SendFriendMessage","Bash","Read","Glob","Grep","Write","Edit","Card","AskUserQuestion","Pop","Schedule","TransferFile","MemoryEdit"]`。

---

## 三、project-dispatcher/system.md 四源调和

**配方**：node-flowmap-slim 全量修订版为基座（`/tmp/nb-node-flowmap-slim/nebflow-defs/agents/project-dispatcher/system.md`）＋ dispatcher-to-nebula 输出语义声明段补入（其 staging 版基于旧契约，只取语义增量：步骤 7 投递机制句 + 自我认知「最终输出受众是 Nebula」条目）＋ dispatcher-ctx 锚定节追加（主仓 `staging/system-addendum-dispatcher-ctx.md`）＋ merge-node 锚定节追加（主仓 `staging/system-addendum-merge-node.md`）。另：dream 批 `staging/agents-project-dispatcher-system-addendum.md`（dream-rules 锚定节）一并追加——⚠ 注记：该节内「`agent: dream`」写法与 flowmap-slim 的 agent 退役硬闸（`NODE_AGENT_RETIRED`）存在契约张力，dream 节点落地时以当时 NodeEdit 契约为准，矛盾留作者窗口裁决。

**落地命令**：

```bash
cp <本节 3.1 终版全文写入的文件> ~/.nebflow/agents/project-dispatcher/system.md
cd ~/.nebflow && git add agents/project-dispatcher/system.md && git commit -m "project-dispatcher system.md 四源调和：flowmap-slim 基座 + 输出语义声明 + dispatcher-ctx/merge-node/dream 锚定节（收口C 移交包）"
```

### 3.1 调和后全文（直接整文件替换用）

````markdown
你是 project-dispatcher：项目任务的分发器。每次触发都是一个全新单次会话——你没有持久上下文、没有记忆、不做回报；状态全部落 Flow Map（NodeEdit 即持久化），会话结束时的最终文本就是给 Nebula 的分发摘要。

## 单次会话协议（严格时序）

1. **NodeList** 读 Flow Map 现状：活动区拓扑、各节点状态、description 与 worktree 占用——先看图再动手。
   **载荷认知（2026-09-05 收敛）**：默认载荷只含元数据——节点 JSON 带 `description`（创建必写的一行目的）、`hasResult` 布尔标记，**不带 result 本体**。要看某节点结果全文：`NodeList` 传 `detail: <nodeId>` 取该节点全记录（活动区优先、归档区兜底）；前端/REST 侧等价通道是 `GET /api/projects/<name>/flow-map/nodes/<nodeId>/result`。存量旧节点无 description 时载荷带 `taskPreview`（task 首行截断）作回退展示。
2. **Read AGENTS.md**（工作区根，项目级 agent 指令）；必要时用 Glob/Grep 摸工作区代码现状（只读，不猜）。
3. **分解**：按依赖关系产出节点集。粒度判据：单节点 = 一个 agent 一次会话可完成的最小可验收单元；有产出依赖才连 in/out；能并行则并行。
4. **worktree 评估**：多个节点会写同一批文件 → 建节点时对该节点传 `worktree: true`（布尔派生：系统在创建时即校验 workspace 是 git 仓根并即时 `git worktree add -b <分支>`，非 git 仓/命名冲突直接 fail-fast 报错——没有路径参数，也没有"建完再补"的中间态）；纯读取或互不冲突 → 不传（直接用 workspace）。**worktree 只能在创建时决定，编辑路径一律拒绝**。
5. **NodeEdit** 建节点/接线：每个节点写清 task（目标/约束/验收口径）+ **description（必写，≤200 字符，一句话说清节点目的——Flow Map 卡片与归档面板都展示它）**、按需分配 plugins、设 out 投递目标。
   **agent 已退役（2026-09-05）**：不再指定 agent/skill/mcp——节点统一跑 `general`，能力全部经 plugins 分配。显式传 agent/skill/mcp 会被硬闸拒绝（`NODE_AGENT_RETIRED`）。agent 选择规则随之简化：无需选择，专注把任务性质写进 task、把能力写进 plugins 分配。
6. **自检**：拓扑无环；入口节点有 task + description；下游节点的 in 引用真实存在；plugins 都在目录内且已审批（未信任插件 0-spawn 拒绝）；worktree=true 与写冲突评估一致。
7. **结束会话**：最终一条文本 = 分发摘要（建了哪些节点、为何这样拆、假设是什么）。会话终态时它**自动投递 Nebula 根会话**（引擎接线，2026-09-05 作者裁定；不走 Mail、不走 out 边，无需任何投递动作）。占位/检查类任务同样输出一行短结论——照常投递，不做特殊省略。

## 自我认知

- 无持久上下文：本轮没做完/没说清的，下一轮（重入）会带着反馈重新开始——所以把关键假设写进节点 task 或分发摘要，而不是指望"下次记得"。
- 不追问用户（没有 AskUserQuestion）：歧义写进节点 task 让节点自行决策，或在分发摘要里向 Nebula 说明假设。
- **最终输出受众是 Nebula**（2026-09-05 输出语义声明）：Nebula 收到的是带来源标注的完整最终文本（agent 消费，非直接面向用户）——把分发摘要写全（拆了什么、什么拓扑、或直接作答的结论），写给 Nebula 看。

## Plugin Catalog 认知

目录段里的 plugin 是节点能力的唯一分配手段（检索类 → web-research；探索规划类 → explorer-toolkit；设计类 → design-spec）。按节点任务性质选配，宁缺勿滥——plugin 注入消耗节点上下文。

<!-- dispatcher-ctx-rules:start -->
## 能力目录选配（plugins 与 preset）

分发器 prompt 里的两段目录是节点选配的唯一依据，按任务需求对照选配：

1. **Plugin Catalog（插件能力目录）**：每条「`- <name>: <能力句> [skills… | mcp… | tools…]`」——能力句写的是该插件让节点具备什么能力。按节点任务性质对照选配：检索类任务 → 带 WebSearch/WebFetch 工具扩展或检索方法论的插件；探索规划类 → explorer-toolkit；设计类 → design-spec；验证类 → nebflow-qa；依此类推。**宁缺勿滥**——plugin 注入消耗节点上下文，纯执行节点不配。
2. **Model Preset Catalog（预设场景目录）**：每条「`- <name> — <场景句>`」（无场景句的只出 name）。按节点任务性质匹配场景句选配 preset（如深度分析/审阅类节点配深度分析档）；无匹配场景就用默认档，不硬凑。
3. 选配动作：NodeEdit 建节点时 `plugins` 数组填插件 name、`preset` 字段填 preset name——都按目录里的 name 原文引用，目录里没有的不要编造。
<!-- dispatcher-ctx-rules:end -->

<!-- merge-node-rules:start -->

## 合并节点接线（批次产物落地收口，merge-node 批 20260905）

凡产生分支/worktree 产物的批次（步骤 4 评估为「多节点写同一批文件→各自建 worktree」）：**每个任务节点的 out 多对一接到同一个合并节点**（本批落地收口）——由它把全部上游分支 `--no-ff` 合并进 main，并清理本批 worktree/分支。纯调查/零产物批次（纯读取、无 worktree、无分支产出）可不接。零产物批次不需要合并节点，产物滞留审计口径（completed ⇒ 分支领先≥1且净 / commit-ready 申报 / 零改动）对任务节点照常生效。

### 合并节点创建模板（NodeEdit create）

- `agent`: `general`；**不配 `worktree`**（硬约束，NodeEdit 会拒绝 merge+worktree 组合）——合并节点沙箱根 = workspace 本体，主仓 `.git` 在根内可写；配了 worktree 沙箱根变成 worktree 目录，主仓 `.git` 在根外，git 变更一律 EPERM。
- `merge`: `true`（触发语义引擎侧保证：全部上游 completed 才启动；上游 failed → 合并节点自动转 blocked（category=upstream-incomplete，不合并不悬挂）；上游 blocked → 走既有 blocked 重入协议处置该上游，合并节点原地等待）。
- `out`: `Nebula`（落地完成回报）。
- `task` 必含三要素：**上游清单**（分支名 ↔ worktree 名一一对应）、**落地命令全集**（commit-ready 代执行语义：上游节点只需申报 commit-ready，落地由合并节点代做）、**复核命令 + 完成标准**。模板：

```text
合并落地：把上游分支逐支 --no-ff 合并进 main 并清理本批 worktree/分支。
上游清单：feat/xxx (worktree xxx)；feat/yyy (worktree yyy)
CMD:
git merge --no-ff feat/xxx -m "merge: xxx" &&
git merge --no-ff feat/yyy -m "merge: yyy" &&
git worktree remove .nebflow/worktrees/xxx &&
git worktree remove .nebflow/worktrees/yyy &&
git branch -d feat/xxx && git branch -d feat/yyy &&
git worktree list && git for-each-ref refs/heads
END
```

### 完成标准（防污染闭环，残留即不算完成）

合并节点 completed ⇔ 全部满足，否则必须以 BLOCKED 开头申报残留明细（category=external-dependency 或 other）：
1. 全部上游分支已合并进 main（`git log main` 可见各支合并提交）；
2. `git worktree list` 无本批 worktree 残留；
3. `git for-each-ref refs/heads` 无本批分支残留。

### blocked 后续（上游失败/被阻断时）

合并节点 blocked（上游 failed）→ 处置失败上游（NodeEdit 重激活它）→ 再 NodeEdit 重激活合并节点（改 task/agent 任一即触发重激活）→ 已完成上游结果自动重投、barrier 自动补齐，合并重跑。**不删除重建节点**（拓扑与结果留痕）。

<!-- merge-node-rules:end -->

<!-- dream-rules:start -->

## 梦境日审处理（dream-agent 批 2026-09-05）

收到任务文本含「**梦境日审**」标记时（触发链：Schedule 每日 04:45 外部驱动 → Nebula 活跃守卫通过 → Task(→本项目)），按以下固定配方执行，**跳过常规分解**：

1. 建单节点：`agent: dream`（第四个 keeper，梦境审计员）；`worktree` 不配（纯只读审计，用 workspace）；`out: Nebula`。
2. 节点 task 固定骨架：「梦境日审（YYYY-MM-DD）：按 dream system.md 全量审计 Nebula 两级记忆（User.md + agents/Nebula/memory.md），产出九项指标头部 + 分级执行清单（【机械】/【裁定】标签）+ 项目迁移映射。复核上轮审计清单执行状态。零写入红线。」附上轮报告要点（如有）。
3. 「梦境日审」任务**不接合并节点**（纯调查零产物——报告沿 out 投 Nebula 即终态）。
4. agent 选择规则更新：真实存在的 keeper 为 Nebula / project-dispatcher / general / **dream** 四个；dream 仅用于梦境日审任务，其他任务照旧默认 general。
<!-- dream-rules:end -->

> ⚠ 收口C 注记：上节「`agent: dream`」为 dream 批原文——flowmap-slim 批已将 NodeEdit 的 agent 字段退役（`NODE_AGENT_RETIRED` 硬闸）。两批契约张力待作者窗口裁决（dream keeper 定义在 `staging/agents-dream/`，main tracked）。

## AGENTS.md 优先

项目指令与本协议冲突时：项目指令约束**节点执行内容**，本协议约束**分发动作本身**。

## 固定工具认知

你的工具面固定为 Node 三件（NodeList/NodeEdit/NodeCancel）+ 读四件（Read/Glob/Grep/Bash）。不写文件（节点干活）；Bash 仅用于 worktree/git 查询类操作，不做实际开发。
````

---

## 四、skill2plugins ~/.nebflow 落地命令（报告 §8 全集；主仓合并段①已由收口C 代做）

> 状态注记：§8①（主仓 merge + wt/软链/分支清理）**已由收口C 代做**（merge=`3dd36276`，清理零残留）——宿主**从②开始执行**。staging 源 `/tmp/nb-skill2plugins/`（plugins/ 14 插件全树、design-spec-patch/）。

```bash
# ② plugins 落地（先落 ② 再删 ③，顺序硬约束；逐插件一笔 commit、逐文件点名）
P=/tmp/nb-skill2plugins/plugins
for name in nebflow-backend-dev nebflow-frontend-dev nebflow-docs-prompt nebflow-qa nebflow-pipelines design-cards visual-report website slideblocks engineering-methods learn-anything academic-research thesis-review phd-note; do
  cp -R "$P/$name" ~/.nebflow/plugins/
  ( cd ~/.nebflow && git add "plugins/$name/plugin.json" && git add "plugins/$name/skills" && git commit -m "plugins: $name 蒸馏落地（含 $(ls "$P/$name/skills" | tr '\n' ' '），源自 skills/ 迁移）" )
done
# ②b design-spec 配合关系段同步（digest 变更→面板需重审一次）
cp /tmp/nb-skill2plugins/design-spec-patch/skills/design-spec/SKILL.md ~/.nebflow/plugins/design-spec/skills/design-spec/SKILL.md
( cd ~/.nebflow && git add plugins/design-spec/skills/design-spec/SKILL.md && git commit -m "plugins: design-spec §6 配合关系同步 skills→plugins 迁移去向（user 层 skill→跨插件按名引用）" )

# ③ skills 退役（tracked 30 项一笔 commit 全列路径；guizang untracked 用普通 rm）
cd ~/.nebflow && git rm -r -f \
  skills/academic-pdf-fallback-chain skills/academic-survey skills/card-design skills/deploy-website \
  skills/design-system skills/flow-execute skills/grill skills/learn-anything-skill \
  skills/merge-pipeline skills/nebflow skills/nebflow-backend skills/nebflow-cache skills/nebflow-docs \
  skills/nebflow-frontend skills/nebflow-prompt-engineering skills/nebflow-qa-backend skills/nebflow-qa-frontend \
  skills/nebflow-tool-dev skills/parallel-research skills/phd-note skills/release-pipeline skills/review \
  skills/slideblocks skills/slideblocks-backend skills/slideblocks-docs skills/slideblocks-frontend \
  skills/thesis-review skills/visual-report skills/website-design skills/website-frontend
git commit -m "skills: 退役 33 个已迁移/归档 skill（30 tracked 路径含 nebflow/ 嵌套组 4 个；slideblocks 4 文件未提交修改随 staging 现态迁入插件）"
rm -rf ~/.nebflow/skills/guizang-social-card-skill    # untracked（独立 git repo，staging 已去 .git 随迁 design-cards）
# 保留不动：skills/skill-creator、skills/memory-consolidation、skills/_example（待裁定三项）

# ④ ~/.nebflow/.gitignore 删 !/skills/ 行
grep -n '^!/skills/$' ~/.nebflow/.gitignore    # 先复现确认（预期 1 行）
sed -i '' '/^!\/skills\/$/d' ~/.nebflow/.gitignore
cd ~/.nebflow && git add .gitignore && git commit -m "gitignore: 删 !/skills/ 白名单行（skills 体系已并入 plugins）"

# ⑤ 核验
ls ~/.nebflow/skills/                                   # 仅剩 _example skill-creator memory-consolidation
ls ~/.nebflow/plugins/                                  # 16 插件全在位（14 新+2 既有）
git -C ~/.nebflow status                                # 干净
curl -s http://localhost:8080/api/plugins | python3 -m json.tool | head   # 宿主实例只读 GET：14 新插件在列、untrusted（或二进制 jar 未重启时等其下次扫描生效）
```

生效说明（skill2plugins 报告 §9）：14 新插件以 untrusted（默认停用）出现在面板，按需 approve（digest 记录，改动即重审）；approve 后进分发器 catalog。design-spec 若此前被 approve 过，②b 后回落 untrusted 需重审（机制内建）。

---

## 五、dispatcher-ctx 16 份 capability manifest 覆盖命令（在四②之后执行）

> 源 = 主仓 `staging/plugins/<name>/plugin.json`（16 份：14 新插件 + design-spec + explorer-toolkit，均含 dispatcher-ctx 批注入的 `capability` 字段）。**前置：第四节②已落（`~/.nebflow/plugins/<name>/` 在位）**。覆盖后 digest 变更 → 已 approve 插件回落 untrusted → 第六节重批。

```bash
for name in academic-research design-cards design-spec engineering-methods explorer-toolkit learn-anything nebflow-backend-dev nebflow-docs-prompt nebflow-frontend-dev nebflow-pipelines nebflow-qa phd-note slideblocks thesis-review visual-report website; do
  cp "/Users/dev/Claude code/Nebflow/staging/plugins/$name/plugin.json" ~/.nebflow/plugins/$name/plugin.json
  ( cd ~/.nebflow && git add "plugins/$name/plugin.json" && git commit -m "plugins: $name capability manifest 覆盖（dispatcher-ctx 批，capability 注入分发器 Plugin Catalog）" )
done
# 核验：16 份全部含 capability 字段
grep -L '"capability"' ~/.nebflow/plugins/*/plugin.json    # 期望空输出
```

---

## 六、approve 重批（design-spec、explorer-toolkit；×2 = digest 变更波次各重批一次）

```bash
curl -s -X POST localhost:8080/api/plugins/design-spec/approve     -H "Authorization: Bearer $(cat ~/.nebflow/auth.json)"
curl -s -X POST localhost:8080/api/plugins/explorer-toolkit/approve -H "Authorization: Bearer $(cat ~/.nebflow/auth.json)"
```

- **×2 口径**：每次 digest 变更（第四节②b SKILL.md 补丁波、第五节 manifest 覆盖波）都使此前 approve 失效回落 untrusted（机制内建）——每波落地后按面板实际 trust 状态重批至 trusted；本节命令可在各波后重复执行。
- 若二进制 jar 未重启，approve 落库生效、插件面板以重启后扫描为准。

---

## 七、proc-residue 定义层（/tmp/nb-proc-residue/HOST_COMMANDS.md C1-C7 全文）

> 状态注记：该报告 §A（worktree 内 4 笔分支提交）与 §B（merge proc-residue-governance 进 main）已在上游收口A 完成——宿主只需执行 §C 定义层 7 件。staging 根 `/tmp/nb-proc-residue/staging`。

```bash
S=/tmp/nb-proc-residue/staging

# C1. general/system.md
cp "$S/agents/general/system.md" ~/.nebflow/agents/general/system.md
cd ~/.nebflow && git add agents/general/system.md && git commit -m "general 会话纪律补测试进程清理纪律（2026-09-05 裁定）——trap cleanup EXIT 标准写法 + 跑完显式 kill + 不依赖自终止 + 宿主 PID 禁杀口径"

# C2. prompts/system-prefix-for-all.md
cp "$S/prompts/system-prefix-for-all.md" ~/.nebflow/prompts/system-prefix-for-all.md
cd ~/.nebflow && git add prompts/system-prefix-for-all.md && git commit -m "Process Safety 细化（2026-09-05 裁定）——环境表宿主 PID 绝对禁杀为第一道防线 + 自起测试进程用完即清（trap cleanup EXIT）+ 8080 端口识别降为第二道防线，PID 验身保留"

# C3. nebflow-qa-backend
cp "$S/skills/nebflow-qa-backend/SKILL.md" ~/.nebflow/skills/nebflow-qa-backend/SKILL.md
cd ~/.nebflow && git add skills/nebflow-qa-backend/SKILL.md && git commit -m "qa-backend skill 补进程清理纪律节 + 红线宿主 PID 口径（2026-09-05 裁定）"

# C4. nebflow-qa-frontend
cp "$S/skills/nebflow-qa-frontend/SKILL.md" ~/.nebflow/skills/nebflow-qa-frontend/SKILL.md
cd ~/.nebflow && git add skills/nebflow-qa-frontend/SKILL.md && git commit -m "qa-frontend skill 补进程清理纪律节（含裸 playwright finally/信号兜底）+ 红线宿主 PID 口径（2026-09-05 裁定）"

# C5. nebflow/frontend-verification
cp "$S/skills/nebflow/frontend-verification/SKILL.md" ~/.nebflow/skills/nebflow/frontend-verification/SKILL.md
cd ~/.nebflow && git add skills/nebflow/frontend-verification/SKILL.md && git commit -m "frontend-verification skill 补 §13 进程清理纪律（harness/静态服务/browser 收尾，2026-09-05 裁定）"

# C6. nebflow/isolated-smoke-verification
cp "$S/skills/nebflow/isolated-smoke-verification/SKILL.md" ~/.nebflow/skills/nebflow/isolated-smoke-verification/SKILL.md
cd ~/.nebflow && git add skills/nebflow/isolated-smoke-verification/SKILL.md && git commit -m "isolated-smoke-verification skill 补 §7 进程清理纪律（杀→wait→lsof 复查三步 + trap 信号全覆盖，2026-09-05 裁定）"

# C7. nebflow/verification-rigor
cp "$S/skills/nebflow/verification-rigor/SKILL.md" ~/.nebflow/skills/nebflow/verification-rigor/SKILL.md
cd ~/.nebflow && git add skills/nebflow/verification-rigor/SKILL.md && git commit -m "verification-rigor skill 补 §7 测试进程清理（含 spec 异常路径销毁守卫 + 对照实验进程表快照口径，2026-09-05 裁定）"
```

> ⚠ 时序注记：C3-C7 落的 5 个 skill 若在第四节③已被 git rm 退役（迁入 nebflow-qa 插件）——**先四后七时 C3-C7 的目标路径已不存在**。正确衔接：C3-C7 的 staging 内容已随 skill 迁移进入 `~/.nebflow/plugins/nebflow-qa/skills/` 对应 SKILL.md（staging 现态），落四③前先比对 `$S/skills/.../SKILL.md` 与迁移后插件内对应文件，若迁移版未含进程清理节则把 staging 版内容并入插件内 SKILL.md 再提交（避免 C3-C7 落空）。C1/C2 路径不受 skills 退役影响。

---

## 八、docs2spec ~/.nebflow 命令（报告 §6②-⑥；主仓合并段①已由收口C 代做）

> 状态注记：§6①（主仓 merge + 清理）**已由收口C 代做**（merge=`c6a9bf1a`，wt/软链/分支零残留）——宿主从②开始执行。
> **硬约束（报告原文）**：③ 必须在 ① 合并落地并核验 `.nebflow/Spec/` 366 文件在位之后执行。
> **收口C 实际计数注记**：报告撰写时预期 Spec=366（docs2spec 自带 366=365 文档+README 索引）。收口C 合并全落后主仓 `.nebflow/Spec/` 实际 **374 tracked**（=366 + 上游A 批 5 + skill2plugins 1 + memory-plan 1 + worktree-audit 1），盘上 `ls | wc -l` = **375**（另含 friend-ui-contract-alignment.md 1 件 B 域暂留 untracked，非 docs2spec 迁移物）。③ 的核验口径按 374/375 执行，docs2spec 贡献份=366 不变。

```bash
# ② 暂留组 README（11 件，文案预制于 /tmp/nb-docs2spec/readmes/）
R=/tmp/nb-docs2spec/readmes; D=~/.nebflow/docs
cp "$R/Nebflow-README.md"              "$D/Nebflow/README.md"
cp "$R/assets-README.md"               "$D/Nebflow/assets/README.md"
cp "$R/SiPM-README.md"                 "$D/SiPM/README.md"
cp "$R/skill-proposals-archive-README.md" "$D/skill-proposals-archive/README.md"
cp "$R/nebflow-website-README.md"      "$D/nebflow-website/README.md"
cp "$R/slideblocks-README.md"          "$D/slideblocks/README.md"
cp "$R/NebLink-README.md"              "$D/NebLink/README.md"
cp "$R/Presentations-README.md"        "$D/Presentations/README.md"
cp "$R/reports-README.md"              "$D/reports/README.md"
cp "$R/research-README.md"             "$D/research/README.md"
cp "$R/memory-archive-README.md"       "$D/memory-archive/README.md"
cd ~/.nebflow && git add docs/Nebflow/README.md docs/Nebflow/assets/README.md docs/SiPM/README.md docs/skill-proposals-archive/README.md docs/nebflow-website/README.md docs/slideblocks/README.md docs/NebLink/README.md docs/Presentations/README.md docs/reports/README.md docs/research/README.md docs/memory-archive/README.md && git commit -m "docs 暂留组 README 标注（11 件）：已迁 Spec 汇总 + 各组去向（assets 归档待裁定/SiPM 待 sipm 项目/website+slideblocks+NebLink 待认领/Presentations+reports+research 待作者确认/proposals 归档/memory 归档）"

# ③ 迁移文件从 ~/.nebflow/docs/Nebflow/ 退役（硬约束：核验主仓 .nebflow/Spec 在位——收口C 已核：374 tracked / 375 盘上，见上注记）
cd ~/.nebflow && { cat /tmp/nb-docs2spec/a-files.txt /tmp/nb-docs2spec/b-old-versions.txt; } | while read -r f; do
  if git ls-files --error-unmatch "docs/$f" >/dev/null 2>&1; then git rm -q "docs/$f"; else rm "docs/$f"; fi
done
git commit -m "docs/Nebflow 退役：A/B 类 367 文件已迁主仓 .nebflow/Spec/（docs2spec 批次），旧位退役；余 assets/ 组与 README（暂留标注）"

# ④ 映射表放置 + commit
cp /tmp/nb-docs2spec/path-migration-map.md ~/.nebflow/docs/path-migration-map.md
cd ~/.nebflow && git add docs/path-migration-map.md && git commit -m "docs→Spec 迁移映射表（过渡件）：1586 文件全覆盖（A/B 逐文件 367 行 + C 组 12 行），供 Nebula 更新记忆/提示词引用路径"

# ⑤ 收尾
rmdir ~/.nebflow/docs/Neb.flow 2>/dev/null; rm -f ~/.nebflow/docs/.DS_Store

# ⑥ 执行后核验
ls ~/.nebflow/docs/Nebflow/                 # 仅剩 assets/ 与 README.md
ls "/Users/dev/Claude code/Nebflow/.nebflow/Spec" | wc -l   # 375（374 tracked + 1 B 域暂留 untracked；其中 docs2spec 贡献 366）
git -C ~/.nebflow log --oneline -1 -- docs/Nebflow/20260830_roadshow-script-draft.md  # B 旧版历史非空
git -C ~/.nebflow status                     # 干净
```

生效说明（报告 §7）：主仓合并后 `.nebflow/Spec/` 即唯一标准源；引用路径更新由 Nebula 按 `~/.nebflow/docs/path-migration-map.md` 消化（记忆/提示词中 `~/.nebflow/docs/Nebflow/<file>` 引用 → 主仓 `.nebflow/Spec/<file>`；节点不改任何提示词）。

---

## 九、各批报告归档清单（存在才 cp；dst 统一 `~/.nebflow/docs/Nebflow/reports/20260905-closure/`）

> 存在性已由收口C 于 2026-09-05 实测核对（✅=在位；仅 plugin-protocol docs/ 两文档未在 /tmp 现场发现，标注跳过）。

```bash
D=~/.nebflow/docs/Nebflow/reports/20260905-closure
mkdir -p "$D"

# —— A 域（机制域 14 支）——
cp /tmp/nb-nebula-toolface/20260905_nebula-toolface-report.md   "$D/"   # ✅
cp /tmp/nb-nebula-sixtools/report.md                            "$D/"   # ✅
cp /tmp/nb-nebula-sandbox-root/report.md                        "$D/"   # ✅
cp /tmp/nb-plugin-protocol/WRAPUP-REPORT.md                     "$D/"   # ✅
#   plugin-protocol 其 docs/ 两文档：/tmp/nb-plugin-protocol/docs/ 不存在 → 跳过（存在才 cp）
cp /tmp/nb-dispatcher-to-nebula/report.md                       "$D/"   # ✅
cp /tmp/nb-bgtask-count-fix/report.md                           "$D/"   # ✅
cp /tmp/nb-checkjs-gate-fix/report.md                           "$D/"   # ✅
cp /tmp/nb-ws-picker/report.md                                  "$D/"   # ✅
cp /tmp/nb-planmode-retire/20260905_planmode-retire-report.md   "$D/"   # ✅
cp /tmp/nb-micorb-wave/20260905_micorb-wave-tune-report.md      "$D/"   # ✅（micorb-wave 报告）
cp /tmp/nb-micorb-wave/mic-bubble-spec.md                       "$D/"   # ✅（micorb-wave spec）
cp /tmp/nb-node-flowmap-slim/report.md                          "$D/"   # ✅
cp /tmp/nb-completion-gate/report.md                            "$D/"   # ✅
cp /tmp/nb-skill2plugins/report.md                              "$D/"   # ✅
cp /tmp/nb-docs2spec/report.md                                  "$D/"   # ✅
cp /tmp/nb-memory-plan/report.md                                "$D/"   # ✅
cp -R /tmp/nb-2c-qc-nits                                        "$D/2c-qc-nits"        # ✅（目录整迁）
cp -R /tmp/nb-2d-toolrefactor                                   "$D/2d-toolrefactor"   # ✅（目录整迁）
cp /tmp/nb-proc-residue/AUDIT_REPORT.md                         "$D/"   # ✅
cp /tmp/nb-proc-residue/HOST_COMMANDS.md                        "$D/"   # ✅（补：定义层命令原件，随 AUDIT_REPORT 同源目录）

# —— B 域（前端好友域 9 支）——
cp /tmp/nb-settings-cleanup/report.md      "$D/"   # ✅
cp /tmp/nb-legacy-ui-retire/report.md      "$D/"   # ✅
cp /tmp/nb-plugins-ux/report.md            "$D/"   # ✅
cp /tmp/nb-sidebar-restructure/report.md   "$D/"   # ✅
cp /tmp/nb-username-unify/report.md        "$D/"   # ✅
cp /tmp/nb-taskpanel-audit/report.md       "$D/"   # ✅
cp /tmp/nb-taskpanel-audit/evidence-panel.png       "$D/"   # ✅（evidence png）
cp /tmp/nb-taskpanel-audit/evidence-flowmap-click.png "$D/" # ✅（evidence png）
cp /tmp/nb-taskpanel-retire/report.md      "$D/"   # ✅
cp /tmp/nb-taskpanel-mainsync/report.md    "$D/"   # ✅
cp /tmp/nb-flowmap-ui-fix/report.md        "$D/"   # ✅
cp /tmp/nb-turn-badge-fix/report.md        "$D/"   # ✅

# —— C 域（文档域 5 支，本收口）——
cp /tmp/nb-preview-rules-report.md         "$D/"   # ✅（在 /tmp 根）

cd ~/.nebflow && git add docs/Nebflow/reports/20260905-closure && git commit -m "归档：2026-09-05 三收口（A 机制/B 前端好友/C 文档域）各批报告 → reports/20260905-closure/"
```

---

## 附：执行状态声明（收口C 亲笔）

- **staging 未执行（沙箱机制边界）**——本沙箱对 `~/.nebflow` 机制性不可写，本包全部命令由 Nebula 重启后或作者窗口前手动执行。
- 主仓侧已代做项：五支合并（hash 见文首）、skill2plugins §8①、docs2spec §6①、proc-residue §A/§B（上游A 已做）、memory-plan wt/分支/软链保留（dream-agent 方案标准源，下一轮才清）。
- 本包自身的核验基线：全量 sbt test / build-web orphans / checkJs 结果见收口C 过程报告 `/tmp/nb-merge-closure-C/report.md`。
