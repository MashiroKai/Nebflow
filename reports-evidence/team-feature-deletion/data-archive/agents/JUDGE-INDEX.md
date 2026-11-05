# agents/ 归档槽 — 「团队声明」判据与命中数：**ZERO HITS（零命中）**

本目录是阶段 4 归档的 **agents/ 槽位**。结论先说：`~/.stand/agents/`（全局 agent 库）**不存在任何团队声明**，
因此本槽位**没有任何 agent 声明文件被复制/移动**，只放这份判据索引 + `raw/` 里 6 份原始命令输出（原文，未编辑）。

- 判据执行时间：2026-11-04 22:52–22:53（local）
- 执行者：chain `team-feature-deletion-20261104` 阶段 4 节点
- 归档根：`/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/`
- 本槽位置放的是**证据**，不是数据搬迁目标：零命中 ⇒ 无事可搬。

---

## 1. 「团队声明」在盘上的实际形态（先弄清形态，再谈命中）

三条可能被判成「团队声明」的形态，逐条排除：

| 形态 | 是否存在 | 判据（原文命令 / 源码位置） | 读数 |
|---|---|---|---|
| **① `agent.json` 里的 `team`/`teams` 字段** | **不存在（schema 里根本没这个字段）** | `AgentJson` case class 全字段见 `src/main/scala/nebflow/agent/AgentLibrary.scala:234-247`：`name / displayName / description / useWhen / tools / mcpServers / avatar / voice / model / preset / category / skills / flows` —— **无 team、无 teams** | J-C：`grep -rn '"team"'` 仅 1 条散文命中（见 §4）；`grep -rn '"teams"'` = **0** |
| **② `agent.json` 的 `tools` 里声明 `Load`**（`Load` 是删前唯一能挂载团队的入口） | **不存在** | J-B：`grep -rn '"Load"' /Users/kaiyu/.stand/agents` | **0 命中**（`grep -rl … \| wc -l` = 0） |
| **③ 目录路径本身**（`teams/<team>/agents/<agent>/`） | **存在**，但它是**路径**而非「agents/ 下的声明」；且整棵树属于第 1 类归档槽 `teams/`，已随 103 文件归档 | `EntityLoader.loadAgentFromDir` `src/main/scala/nebflow/core/entity/EntityLoader.scala:141-146`：`if dirStr.contains("/teams/") then "team"`；`EntityTypes.scala:21` 注释 `category … computed by EntityLoader from path, NOT read from JSON` | 团队归属**只由路径决定**，不由 JSON 声明决定 |

**结论**：`~/.stand/agents/` 是「全局 agent 库」这一层，团队归属在 Nebflow 里是**路径事实**
（`<dataRoot>/teams/<team>/agents/<name>/agent.json`），**没有**任何 JSON 字段可以让一个全局 agent
「声明自己属于某团队」或「声明自己要挂载某团队」。所以这一层的正确答案就是零命中——不是「没人用所以不算」，
是**这种声明形式在 schema 上不存在**。

## 2. 复核用的精确判据（原文命令 + 原始输出见 `raw/`）

```bash
# J-A 盘上每一个 agent.json* 的顶层 key（6 个文件，无一是 team.json，无一含 team(s) key）
find /Users/kaiyu/.stand/agents -type f \( -name 'agent.json' -o -name 'agent.json.*' \) | sort
#   → Coder/agent.json.archived, design-engineer/agent.json.archived, Explorer/agent.json.archived,
#     Nebula/agent.json, Nebula/agent.json.bak-20261030-cmdcode, project-dispatcher/agent.json

# J-B Load 工具声明
grep -rn '"Load"' /Users/kaiyu/.stand/agents          # → 无匹配 (EXIT=1)
grep -rl '"Load"' /Users/kaiyu/.stand/agents | wc -l  # → 0

# J-C team / teams JSON 字段
grep -rn '"team"'  /Users/kaiyu/.stand/agents         # → 1 条，且是散文（见 §4）
grep -rn '"teams"' /Users/kaiyu/.stand/agents         # → 无匹配 (EXIT=1)

# J-D 名为 team.json 的声明文件
find /Users/kaiyu/.stand/agents -name 'team.json*' | wc -l   # → 0

# J-E brief 里那次粗 grep，原文重跑（说明它为何不是判据）
grep -rln 'Load\|"team"' /Users/kaiyu/.stand/agents/  # → 3 个文件，全是散文/提案（见 §4）

# J-F dataRoot 内 teams/ 之外的 team.json 残留（越界检查）
find /Users/kaiyu/.stand -maxdepth 4 -name 'team.json*' -not -path '*/archive/*'
#   → /Users/kaiyu/.stand/archives/26d25e9d/flow-creator/team.json   （见 §5，已在他处归档，未动）
```

## 3. agents/ 槽位的最终读数

| 项 | 值 |
|---|---|
| 命中「团队声明」的 agent 声明文件 | **0 个** |
| 从 `~/.stand/agents/` 复制/移动出去的文件 | **0 个**（源目录完全未动） |
| 本槽位内容 | `JUDGE-INDEX.md` + `raw/` 6 份判据原始输出 |

## 4. 那 3 个「提到 team」的文件为什么不是团队声明（逐个看完的结论）

J-E 粗 grep 命中的 3 个 + J-C2 大小写不敏感命中的 11 个，**全部**是散文/提案/memory，不含任何结构化声明：

- `Backend/skill-proposals/update-new (closest existing: …)-20260830-165524.md` —— 一份 skill 提案正文，
  描述某次审计走过 `AgentCore.fixedToolsFor "team"` 分支；`"team"` 是**被审计的代码字符串**，不是 agent 字段。
- `Explorer/system.md`、`qa-frontend/skill-proposals/update-new（可挂入 qa-harness 或 qa-report 类）…md` ——
  粗 grep 里 `Load` 的假阳（`loading` / `download` / 文件名中的「挂入」）。
- `design-engineer/system.md`、`Frontend/memory.md`、`Manager/memory.md`、
  `Backend/skill-proposals/proposal-20260830-014313.md`、`…-170341.md`、
  `Frontend/skill-proposals/proposal-20260825-225155.md`、`…-080155.md`、`…-232847.md`、`…-170808.md`、
  `Nebula/projects/nebflow-project/model-routing-design.md` —— 散文里出现「team」字样（讨论、历史记录、设计文档）。

判定规则：**声明 = `agent.json` 里的结构化字段**；散文里出现 "team" 不构成声明，故一律不计命中。

## 5. 越界发现（**未动，仅记录**）

`/Users/kaiyu/.stand/archives/26d25e9d/flow-creator/team.json`（263 B, mtime 2026-08-15 19:19）
—— 注意路径是 `archives/`（复数，会话历史归档目录 `archives/<shortSid>/`，由 `HistoryArchiver` 写入），
**不是** `~/.stand/teams/`。内容是团队 `flow-creator` 的旧定义（`lead: Manager`，成员
`EntityArchitect/EntityBuilder/EntityReviewer`，自述「已被 entity-creator Flow 替代」）。

- 它**不属于**本批三类归档范围（不是 `teams/*`、不是 `agents/*` 的声明、不是 `sessions/*/flow-tree.json`）；
- 它**本来就躺在归档目录里**（2026-08-15 的会话归档快照）；
- 代码不读它：`dataRoot/teams` 的读者只有 `EntityLoader.scala:18` / `ToolLoader.scala:13,112,129` /
  `AgentMcpLoader.scala:36` / `MemoryStore.scala:60` / `PresenceRoutes.scala:2117` / `FlowTreeActor.scala:594`，
  全部拼的是 `<dataRoot>/teams`，**没有一处**读 `<dataRoot>/archives`。
- 处置：**保持原样，未复制未移动未 chmod**。若后续批要清，属另一件事（建议同样归档而非 rm）。

## 6. 还原说明

本槽位没有搬迁任何原始数据 ⇒ **无需还原动作**。所有原始 `~/.stand/agents/**` 文件原地未动、权限未改，
判据可在任意时刻用 §2 的命令原文重跑复核。完整还原口径见归档根 `../RESTORE.md`。
