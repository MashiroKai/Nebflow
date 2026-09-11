# staging — 宿主落地内容（memory-mech 批次二，2026-09-05）

本目录是 ~/.nebflow 定义层改动的 staging（工作区沙箱不可直写 ~/.nebflow，
宿主执行 cp + git commit）。对应方案 §6.2 表 2.1 / 2.3 / 2.6。

| staging 源 | 宿主目标 | 说明 |
|---|---|---|
| `memory-consolidation-flow/flow.json` | `~/.nebflow/flows/memory-consolidation/flow.json` | scanner-only 化：consolidator 节点退役，scanner 直接 $return（报告投递 Nebula） |
| `memory-consolidation-flow/agents/scanner/agent.json` | `~/.nebflow/flows/memory-consolidation/agents/scanner/agent.json` | 改名 memory-auditor；去 Mail（报告沿 flow 输出投递，不需要 Mail 直发） |
| `memory-consolidation-flow/agents/scanner/system.md` | `~/.nebflow/flows/memory-consolidation/agents/scanner/system.md` | 审计报告格式：九项指标头部 + 条目级动作 + T1/T2/T3 判据 + 职权红线（不直写；2026-09-12 局部取代：执行权归记忆整理 agent，发现以队列条目投入） |
| `skills/memory-consolidation/SKILL.md` | `~/.nebflow/skills/memory-consolidation/SKILL.md` | 触发四路（周审计/生命周期/阈值/事件）+ 三问准入 + 取代规则 + 分级 + 预算 |
| `agents-Nebula-system.md` | `~/.nebflow/agents/Nebula/system.md` | 整文件替换：仅「memory 维护纪律」节重写（三问/取代/分级/预算/审计回投），其余节原样保留 |
| `memory-md-rule-section-replacement.md` | （非 cp——**记忆整理 agent** 执行项 / Nebula 可记账入队） | memory.md「记忆管理规则」节替换文本 + 执行指令，结果文本中同步给出 |

宿主落地命令（cp 后各自 git commit，见结果文本「宿主落地命令全集」）：

```bash
# ① flow scanner-only 化（consolidator 目录一并退役删除）
rm -rf ~/.nebflow/flows/memory-consolidation/agents/consolidator
cp <worktree>/staging/memory-consolidation-flow/flow.json ~/.nebflow/flows/memory-consolidation/flow.json
cp <worktree>/staging/memory-consolidation-flow/agents/scanner/agent.json ~/.nebflow/flows/memory-consolidation/agents/scanner/agent.json
cp <worktree>/staging/memory-consolidation-flow/agents/scanner/system.md ~/.nebflow/flows/memory-consolidation/agents/scanner/system.md

# ② skill 更新
cp <worktree>/staging/skills/memory-consolidation/SKILL.md ~/.nebflow/skills/memory-consolidation/SKILL.md

# ③ Nebula system-prefix（整文件；仅 memory 维护纪律节有 diff）
cp <worktree>/staging/agents-Nebula-system.md ~/.nebflow/agents/Nebula/system.md

# ④ memory.md 规则节：禁 cp——路径二择一：
#    (a) Nebula 调 MemoryEdit(target="agent", action="replace_section", ...) = 记账入队，下一次压缩由 memory-consolidator 执行；
#    (b) 由 memory-consolidator 在压缩轮直接 Write/Edit 落盘（改前手动快照）。
#    （文本见 memory-md-rule-section-replacement.md 与结果文本）
```
