# Nebula 循环调用事故分析与提示词约束方案（2026-08-28 00:47 重启）

> Explorer 诊断报告 · 2026-08-28 · 状态：待用户确认后交 prompt-engineer 实施
> 取证源：`sessions/5cc7590a-6dcb-4dba-8979-0ce5eb0a14fe.json`（Nebula 主会话，工具调用序列逐条核对）

## 0. 先修正一个任务前提

任务描述基于重启时点（00:47）称「六件工具已按 #438 收走」。**实际最终态已反转**：00:54:39 用户裁定「还是把你的六类工具加回来吧，否则维护记忆都没办法」，六件工具已加回 `agents/Nebula/agent.json`（当前 tools 含 Read/Glob/Edit/Write/Grep/Bash + CheckIssues，commit 8684acd）。因此缺口 3 的规则不能写死「六件工具已收」，而应写为通用规则：**工具集以当前 system prompt 实际注入为准，变化后按实际清单行动**。

## 1. 事故还原（会话日志实锤）

| 时间 | 动作 |
|---|---|
| 00:47 | 宿主重启（桌面 app 新 dmg），会话恢复，六件工具因 #438 未随恢复 |
| 00:48:02–00:48:54 | **8 个 turn 连续 AgentControl list/status 共 16 次调用**（多数 turn 同 turn 双发 list+status），期间 loop guard streak 从 3 warn 到 10 无一止损 |
| 00:49:24 | 用户第一次点破「直接mail激活不行吗」→ 一轮内 4×Mail(INTERRUPT)（sipm-paper/html-deck-studio/nebflow-website/nebflow-project）——**证明正确动作 Nebula 一直会做，只是被「先确认状态」的自查冲动挡住** |
| 00:50:07–00:50:25 | 纠正后仍出现第二波 6 次 AgentControl（查单个 session 状态）——核查冲动残留 |
| 00:51–00:52 | Delegate Coder 更新记忆、Delegate Explorer 分析（本任务） |
| 00:54:39 | 用户裁定反转 #438，Delegate Coder 加回六件工具 |

## 2. 根因分析

### 缺口 1：重启恢复无协议 —— 成立，且找到直接诱因

system.md 无恢复动作协议。更关键的是 **memory.md 的过时条目是本次循环的直接诱因**：

- 「2026-08-26 Backend 重启失活事故」条目（memory.md L238）教的是「**重启后主动核查各 team 成员激活状态（AgentControl list + 日志活动）**」——Nebula 重启恢复后忠实执行了这条：去 AgentControl list/status 核查。但该条目写于六件工具还在的时期，「日志活动核查」已不可用，且「先核查再重激活」的行为导向本身就是错的。
- 正确逻辑（00:49 被点破后才执行）：**Mail(INTERRUPT) 重激活发出且对方开工 = 活性验证本身**，核查是冗余前置步骤，信息不足时（AgentControl 输出 agent 名截断）核查退化为无限补全冲动。

### 缺口 2：循环熔断缺失 —— 成立

AgentControl 输出中的 loop guard（warn@streak=3，本次一路涨到 10）是系统层观测信号，**prompt 层没有任何规则定义它的语义和应对动作**。模型把它当背景噪音。memory L128 有 Write-only 循环教训（「同命令 10min>3 次停下报告」），但那是 worker 场景的记录，未上升为 Nebula 自身的行为规则。

### 缺口 3：工具消失未内化 —— 成立，但机制与假设不同

事故中 Nebula 并未尝试调 Bash（它知道没了）。真实机制是：**system.md 的活跃指令与实际工具集脱节，造成行为指引真空**——

- system.md L23「快速路由判断 — Read/Grep 少量文件」、memory L39/L90 路由框架「只可用 Read/Grep」、L222「1-2 个 Grep 级取证」：重启后这些指令引用的工具全不存在，取证冲动无处安放，只剩 AgentControl 可用 → 过度依赖 AgentControl 做状态取证 → 循环。
- memory L217 六件工具条目是事实记录（「已收走」），不是行为规则（「工具没了该怎么办」），且该条目本身已被 00:54 反转再度过时（说「现已无」但已加回）。

**通用化结论**：规则不应绑定「六件工具」这个具体配置（它已经反转一次了），而应绑定机制——「工具集以 system prompt 实际注入为准；not available / 报错 → 立即改道 Delegate/Mail，禁止重试」。

## 3. 修改方案（精简克制：system.md 零新增分区，memory 两处追加标注）

### 3.1 system.md ——「派发优先」分区内新增一小节（唯一新增内容）

**文件**：`~/.nebflow/agents/Nebula/system.md`
**位置**：「决策矩阵」表格与「包括 bug 排查」段之后、「### 路由纪律」之前（约 L39）
**插入文案（原文照抄可执行）**：

```markdown
### 恢复与熔断——三条硬规则

1. **恢复即派发** — 宿主重启/会话恢复后，第一动作是按上下文已知任务清单直接 Mail(INTERRUPT) 重激活各 team，禁止先核查状态、试工具、读文件取证。重激活 Mail 发出且对方开工 = 活性验证本身，无需前置确认。
2. **循环熔断** — 同一工具连续调用 ≥3 次且无新信息（工具输出出现 loop guard warn 即已到线）→ 下一 turn 强制切换：换路径、Delegate、或向用户汇报。查两次还缺的信息第三次也查不出来——带残缺信息行动，或问用户。
3. **工具改道** — 工具集以当前 system prompt 实际注入为准（重启/配置变更后会变）。工具不存在或报错 → 立即改道 Delegate/Mail，禁止换参数重试。
```

三条合并为一个 5 行小节，挂在本就是第一性原则的「派发优先」分区下（三条的本质都是「自查冲动 → 立即回头派发/汇报」），不新增顶层分区。

### 3.2 system.md —— 修正「最小职责」第 3 点（半句）

**位置**：L23
**原文**：`3. **快速路由判断** — Read/Grep 少量文件确认"该派给谁"，不做深挖`
**改为**：`3. **快速路由判断** — Read/Grep 少量文件确认"该派给谁"，不做深挖（工具不可用时 Delegate(Explorer) 代查）`

消除「指令引用可能不存在的工具」的真空：工具在就用，不在就 Delegate，一层兜底覆盖所有工具集变化场景。

### 3.3 memory.md —— 两处追加修正标注（不改写历史，current-state-first）

**a) 六件工具条目（L217，⚠ 六件工具重启即失）末尾追加**：

```
**【08-28 00:54 反转】用户裁定「还是把你的六类工具加回来吧，否则维护记忆都没办法」——六件已加回 agent.json。沉淀规则：工具集以 system prompt 实际注入为准，not available 即 Delegate 改道不重试（已固化进 system.md「恢复与熔断」三条硬规则）**
```

**b) 08-26 Backend 重启失活条目（L238）教训句后追加**：

```
**【08-28 修正】「主动核查激活状态」动作废弃：AgentControl 输出信息不足（agent 名截断）诱发 08-28 00:48 八轮 list/status 循环——重启恢复一律直接 Mail(INTERRUPT) 重激活，不前置核查**
```

L238 条目是本次事故的直接诱因，不追加修正标注则下个会话仍会被它误导。其余条目（L39/L90/L222 的 Read/Grep 表述）随工具加回已重新有效，**不动**。

## 4. 验收条件

| # | 类型 | 内容 | 判定 |
|---|---|---|---|
| A1 | 结构 | `grep -c "恢复即派发\|循环熔断\|工具改道" ~/.nebflow/agents/Nebula/system.md` = 3，且位于「派发优先」分区内（决策矩阵之后、路由纪律之前） | 二值 |
| A2 | 结构 | `grep` memory.md 含「08-28 00:54 反转」与「08-28 修正」各 ≥1 次 | 二值 |
| A3 | 注入 | 下次重启后解析 Nebula 新会话 json，首条 system 消息含「循环熔断」「恢复即派发」文本 | 二值 |
| A4 | 行为 | 下次重启后对 Nebula 会话跑审计脚本（下方），断言：①恢复类指令后的首个编排工具调用是 Mail；②恢复阶段（前 3 turn）AgentControl streak ≤2；③全会话任一工具连续调用 streak ≤3 | 二值 |
| A5 | 负例 | 用户明确要求查状态时 AgentControl 正常可用（熔断规则以「无新信息」为前提，不误伤合法查询） | 二值 |

**A4 审计脚本**（可入库 `~/.nebflow/bin/audit-loop-guard.py`，复用本报告 §1 的解析逻辑）：

```python
# 用法: python3 audit-loop-guard.py <session.json>
# 断言: ①任一工具连续调用 streak ≤3  ②输出 streak>3 的工具名与次数（人工复核）
import json, sys, itertools
msgs = json.load(open(sys.argv[1]))
seq = [b['name'] for m in msgs if isinstance(m.get('content'), list)
       for b in m['content'] if isinstance(b, dict) and b.get('type') in ('tool_use','tool_call')]
for name, grp in itertools.groupby(seq):
    n = len(list(grp))
    if n > 3: print(f'VIOLATION: {name} streak={n}')
print('PASS' if all(len(list(g))<=3 for _,g in itertools.groupby(seq)) else 'FAIL')
```

## 5. 附注：系统层观察（不属本方案范围，建议报 Issue）

1. **AgentControl list/status 输出 agent 名截断**是循环的直接放大器——「再查一次就能补全」的错觉来源。修复截断或在工具描述中写明「输出截断为设计行为，勿重复调用」。
2. **loop guard warn 文案可行动化**：裸 streak 数字对模型无语义，改为「同一工具已连续调用 N 次且无新信息，必须切换动作」可显著提高止损率（与 3.1 第 2 条规则呼应）。
