# 阶段 4 · 团队数据归档（`teams/*` 全量 + `agents/` 零命中判据）— 证据

**本步零代码改动**：只有 `reports-evidence/` 与本证据目录，`src/` 一个字节未动（`git status --porcelain` 见 §8）。

- 批次：`team-feature-deletion-20261104`（五项边界判定 + 能力删除清单 + 阶段3逐条方案）
- 阶段：4 / 数据归档（前序：阶段 1 停生产能力 → 阶段 2 摘挂载入口 → 阶段 3 逐条方案）
- 归档日期：**2026-11-04**（22:51 `cp -a` / 22:52 `mv` / 22:55 `chmod -R a-w` 冻结）
- 作者依据：**2026-11-04 22:13「团队功能整体删掉」**，并明令「**一律归档，不许 rm —— 删除的是代码路径，不是别人写下的记录**」
- 归档根：`/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/`
  （阶段 1 建立并冻结，本步在其上**追加** `teams/` 与 `agents/`，未覆盖、未重排阶段 1 的任何东西）
- 工作区：`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-f97716ed`，
  分支 `delegate/delegate-nebflow-f97716ed`，基线 `2af513702c81ff0522c952fb7ea7a43b59b578b2`，**未提交 main、未重启 6138 生产实例（PID 78176）**

本目录 `commands.log` 是**全部命令原文 + 全部读数**的逐条流水（`$ 命令` + 实际输出），
本 README 是它的摘要与结论；两者冲突时以 `commands.log` 为准。

---

## 1. 三类归档对象 · 各自结果

| # | 类别 | 归档前读数 | 归档了什么 | 文件数 | 字节 | 现位置 |
|---|---|---|---|---|---|---|
| 1 | `~/.stand/teams/*` | `ls -d /Users/kaiyu/.stand/teams/*/ \| wc -l` = **9**；`find … -type f \| wc -l` = **103** | 9 个团队目录全树（`team.json` / `rules.md` / `agents/<n>/{agent.json,system.md}` / 根 `.DS_Store`） | **103** | **235,687** | 归档根 `teams/**` |
| 2 | `~/.stand/agents/*` 的**团队声明** | 精确判据 **零命中**（§3） | **什么都没搬**（源目录 99 文件一字节未动）；本槽位只放判据索引 + 6 份原始输出 | 7（均为本步**生成**的证据） | 13,023 | 归档根 `agents/**` |
| 3 | 遗留 `~/.stand/sessions/*/flow-tree.json` | `ls /Users/kaiyu/.stand/sessions/*/flow-tree.json \| wc -l` = **0** | **阶段 1 已归档，本步无需动作**（复核确认：源目录 `9d7638fb-…/` 已是空壳） | 0（本步新增） | 0 | 阶段 1 位置不变：`sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json` |

**归档根合计**：`find -type f` = **113 文件**（含 `MANIFEST.tsv` 自身）；
排除 `MANIFEST.tsv` 自身后 manifest 收录 **112 数据行**（分槽：`teams` 103 / 235,687 B · `agents` 7 / 13,023 B · `sessions` 1 / 862 B · `RESTORE.md` 1；
总字节 = manifest 数据行 `bytes` 之和，冻结时 261,365 B —— 本 README/RESTORE 自身的字节变化会带动该数，**以 `MANIFEST.tsv` 实测为准**）。

9 个团队目录实名（`~/.stand/teams/` 下）：`czt-project`、`html-deck-studio`、`nebflow-project`、`nebflow-rust`、
`nebflow-website`、`ReminderIsland`、`sipm-paper`、`slideblocks`、`voice-recognition-test`。
其中 **`nebflow-project/team.json` 在归档前已被作者手工改名为 `team.json.archived`** —— `cp -a` 原样保留，
归档内路径即 `teams/nebflow-project/team.json.archived`（**没有**被「修好」成 `team.json`）。

### 1.1 归档前后关键读数（同一条命令）

| 命令 | 归档前 | 归档后 |
|---|---|---|
| `ls -d /Users/kaiyu/.stand/teams/*/ \| wc -l` | **9** | **0** |
| `find /Users/kaiyu/.stand/teams -type f \| wc -l` | **103** | **0** |
| `find <归档根>/teams -type f \| wc -l` | 0（不存在） | **103** |
| `find <归档根>/teams -type f -exec stat -f '%z' {} \; \| awk '{s+=$1} END {print s}'` | — | **235687** |
| `ls /Users/kaiyu/.stand/sessions/*/flows.json \| wc -l` | **24** | **24**（**没动**） |
| `ls /Users/kaiyu/.stand/sessions/*/flow-tree.json \| wc -l` | **0** | **0** |

> `~/.stand/teams/` 归档后**目录本体存在但为空**（`ls -la` → `total 0`，无任何子项）：
> `mv` 把整棵树移走后 `mkdir -p` 重建了空容器，纯属保守（`EntityLoader.listTeams`
> `src/main/scala/nebflow/core/entity/EntityLoader.scala:45-54` 有 `if !os.exists(teamsDir) then Map.empty` 兜底，
> 空目录与不存在等价）。

---

## 2. 做法（顺序未颠倒：复制 → 校验 → 移走 → 再校验 → 冻结 → manifest）

1. **解冻**归档根（阶段 1 冻结过）：`chmod -R u+w <归档根>`（阶段 1 `RESTORE.md` §4 第 1 步规定的唯一破窗操作）。
2. **建归档子目录**：`mkdir -p <归档根>/teams`。
3. **复制**：`cp -a /Users/kaiyu/.stand/teams/. <归档根>/teams/`（`.` 结尾 ⇒ `.DS_Store` 一并过去；`-a` 保权限/时间戳/xattr）。
4. **逐文件 sha256 双跑比对**：
   - run1 = 源：`(cd /Users/kaiyu/.stand/teams && find . -type f -print0 | xargs -0 shasum -a 256) | sort -k2`
   - run2 = 归档：`(cd <归档根>/teams && find . -type f -print0 | xargs -0 shasum -a 256) | sort -k2`
   - `diff` ⇒ **0 差异行 / 103 行对 103 行 ⇒ `DIFF_RESULT=IDENTICAL`**
   - 另加一层：`bytes+sha256+mtime` 三元组 manifest 逐行 `diff` ⇒ **`MANIFEST_DIFF=IDENTICAL (103/103 rows incl. mtime)`**
5. **移走源**（`mv`，**不是 rm**）：同卷 `st_dev=16777232` ⇒ 原子 rename、不搬字节：
   `mv /Users/kaiyu/.stand/teams ~/.Trash/team-feature-deletion-20261104__teams-originals`
   （移走的是**冗余**：权威副本已在归档根，且已 103/103 校验全等；废纸篓被系统清空也不影响归档）
6. **移动后再核**：`diff /tmp/src.sha256 /tmp/arc-post.sha256` ⇒ **`POST_MOVE_DIFF=IDENTICAL`**；
   `POST_MOVE_MANIFEST=IDENTICAL (103/103, bytes+sha256+mtime)`；归档 `teams/` 仍 103 文件 / 235,687 字节。
7. **冻结**：`chmod -R a-w <归档根>`；复核 `find <归档根> -type f -perm +222 | wc -l` = **0**（无任何可写文件），
   目录 `dr-xr-xr-x`、文件 `-r--r--r--`。
8. **manifest**：`<归档根>/MANIFEST.tsv`（见 §4）。
9. **第三方交叉验证**（本步之外的独立快照）：`boundary/teams-manifest.json` 由**边界判定节点在 22:28**
   写入（早于本步任何 `cp`），其 103 条 `path→sha256+size` 与本步归档 **103/103 全等、0 差异、无独有项**
   ⇒ `VERDICT: IDENTICAL on 103 common files`（原始输出 `crosscheck-boundary-manifest.txt`）。

**MANIFEST 自洽性**（证明还原校验可信）：重新计算归档树再与 `MANIFEST.tsv` 逐行 `diff` ⇒
`MANIFEST_SELF_CONSISTENT=YES (112/112 rows)`；按 manifest 推导的期望 sha256 列表回打归档
`teams/` ⇒ `RESTORE_CHECK_DRYRUN_OK (103/103)`。

---

## 3. `agents/` 的「团队声明」判据与命中数 = **ZERO HITS（零命中）**

### 3.1 先弄清形态：「团队声明」在盘上**不存在**这种字段

| 可能形态 | 结论 | 判据 |
|---|---|---|
| `agent.json` 的 `team` / `teams` 字段 | **schema 无此字段** | `AgentJson` 全字段 `src/main/scala/nebflow/agent/AgentLibrary.scala:234-247`：`name/displayName/description/useWhen/tools/mcpServers/avatar/voice/model/preset/category/skills/flows` |
| `agent.json` 的 `tools` 声明 `Load`（删前唯一挂载入口） | **零命中** | `grep -rn '"Load"' /Users/kaiyu/.stand/agents` ⇒ 无匹配（EXIT=1）；`grep -rl … \| wc -l` ⇒ **0** |
| 目录路径 `teams/<t>/agents/<n>/` | **这才是团队归属的唯一真相，且属第 1 类（已随 103 文件归档）** | `EntityLoader.scala:141-146`：`if dirStr.contains("/teams/") then "team"`；`EntityTypes.scala:21`：`category … computed by EntityLoader from path, NOT read from JSON` |

⇒ 全局 agent 库（`~/.stand/agents/`）在 schema 上**无法**声明「我属于某团队」或「我挂载某团队」。
所以这一层的正确答案就是零命中 —— **不是「没人用所以不算」，是这种声明形式根本不存在**。

### 3.2 判据原文命令与读数

```bash
# J-A 盘上每个 agent.json* 的顶层 key（6 个文件：Coder/design-engineer/Explorer 的 .archived + Nebula 的 2 个 + project-dispatcher）
find /Users/kaiyu/.stand/agents -type f \( -name 'agent.json' -o -name 'agent.json.*' \) | sort
#   Coder/agent.json.archived      :: [avatar, category, description, displayName, name, tools]
#   design-engineer/…archived      :: [category, description, displayName, name, preset, skills, tools, useWhen]
#   Explorer/agent.json.archived   :: [category, description, name, skills, tools]
#   Nebula/agent.json              :: [displayName, identityUpdatedAt, model, name]
#   Nebula/agent.json.bak-20261030-cmdcode :: [displayName, identityUpdatedAt, model, name]
#   project-dispatcher/agent.json  :: [description, name, skills]        ← 无一个含 team/teams

# J-B Load 工具声明
grep -rn '"Load"' /Users/kaiyu/.stand/agents          # 无匹配 EXIT=1
grep -rl '"Load"' /Users/kaiyu/.stand/agents | wc -l  # 0

# J-C team / teams 字段
grep -rn '"team"'  /Users/kaiyu/.stand/agents | wc -l # 1 —— 且是 skill-proposal 的散文（见 §3.3）
grep -rn '"teams"' /Users/kaiyu/.stand/agents         # 无匹配 EXIT=1

# J-D 命名团队声明的文件
find /Users/kaiyu/.stand/agents -name 'team.json*' | wc -l   # 0

# J-E brief 里那次粗 grep，原文重跑（它为什么不是判据）
grep -rln 'Load\|"team"' /Users/kaiyu/.stand/agents/  # 3 个文件，全是散文/提案，无一 agent.json
```

**命中数 = 0**。原始输出 6 份存档于 `<归档根>/agents/raw/`（本目录 `agents/raw/` 有同名副本），
INDEX 正文见 `agents/JUDGE-INDEX.md`。

### 3.3 为什么粗 grep 的 3 条命中不算（逐条看过）

1. `Backend/skill-proposals/update-new (closest existing: …)-20260830-165524.md` —— skill 提案正文，
   描述某次审计走过 `AgentCore.fixedToolsFor "team"`；`"team"` 是**被审计的代码字符串**，不是 agent 字段。
2. `Explorer/system.md` —— 粗 grep 中 `Load` 的假阳（`loading` / `download` 类词形）。
3. `qa-frontend/skill-proposals/update-new（可挂入 qa-harness 或 qa-report 类）-20260830-032431.md` —— 文件名里的「挂入」。

（另：大小写不敏感 `grep -rli team` 命中 11 个文件，全部是 prose / memory / 设计文档，无结构化声明 —— 见 `agents/raw/judge-C-team-field.txt`。）

### 3.4 越界发现（**未动，仅记录**）

`/Users/kaiyu/.stand/archives/26d25e9d/flow-creator/team.json`（263 B，2026-08-15 19:19）——
注意是 `archives/`（复数，会话历史归档 `archives/<shortSid>/`），**不是** `~/.stand/teams/`。
内容是团队 `flow-creator` 的旧定义（lead `Manager`，成员 `EntityArchitect/EntityBuilder/EntityReviewer`，自述已被 entity-creator Flow 替代）。

- **不属于**本批三类范围（不是 `teams/*`、不是 `agents/*` 声明、不是 `sessions/*/flow-tree.json`）；
- **本来就躺在归档目录里**（2026-08-15 的会话归档快照）；
- 代码不读它：`<dataRoot>/teams` 的读者只有 `EntityLoader.scala:18`、`ToolLoader.scala:13,112,129`、
  `AgentMcpLoader.scala:36`、`MemoryStore.scala:60`、`PresenceRoutes.scala:2117`、`FlowTreeActor.scala:594`，
  **没有一处**读 `<dataRoot>/archives`；
- **保持原样**：未复制、未移动、未 chmod。后续若清，建议同样归档而非 rm。

---

## 4. MANIFEST

- 路径：`/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/MANIFEST.tsv`
- 仓库内副本：`reports-evidence/team-feature-deletion/data-archive/MANIFEST.tsv`（同字节，随本次 commit 提交）
- 行数：**113 行 = 1 行表头 + 112 数据行**（`wc -l` = 113）；表头行以 `#` 开头；`MANIFEST.tsv` **自身不入表**
- 列：`相对路径 <TAB> bytes <TAB> sha256 <TAB> mtime`（mtime = local `%Y-%m-%d %H:%M:%S`）
- 相对路径把**归档根**当根（即 `<dataRoot>` 视作根：`teams/…`、`agents/…`、`sessions/…`、`RESTORE.md`）
- 合计：113 行；`teams/` 103 文件 / 235,687 B（这两项稳定可引），其余行随证据文件自身修订而变，**以 manifest 实测为准**

```bash
head -2 /Users/kaiyu/.stand/archive/team-feature-deletion-20261104/MANIFEST.tsv
wc -l   /Users/kaiyu/.stand/archive/team-feature-deletion-20261104/MANIFEST.tsv     # 113
awk -F'\t' 'NR>1 {s+=$2} END {print s}' \
  /Users/kaiyu/.stand/archive/team-feature-deletion-20261104/MANIFEST.tsv           # 数据行 bytes 之和（不含 MANIFEST 自身）
```

---

## 5. 还原指引（完整命令见 `RESTORE.md` §9）

```bash
ARCH=/Users/kaiyu/.stand/archive/team-feature-deletion-20261104
chmod -R u+w "$ARCH"                                        # 1) 解冻
mkdir -p /Users/kaiyu/.stand/teams
cp -a "$ARCH/teams/." /Users/kaiyu/.stand/teams/            # 2) 拷回 103 文件
# 3) 校验（期望 diff 无输出 + RESTORE_OK；文件数 103）
cd "$ARCH" && awk -F'\t' 'NR>1 && $1 ~ /^teams\// {print $3"  "$1}' MANIFEST.tsv \
  | sed 's#  teams/#  #' | sort -k2 > /tmp/exp.sha
cd /Users/kaiyu/.stand/teams && find . -type f -print0 | xargs -0 shasum -a 256 \
  | sed 's#  \./#  #' | sort -k2 | diff /tmp/exp.sha - && echo RESTORE_OK
chmod -R a-w "$ARCH"                                        # 4) 还原后重新冻结
```

- **只读说明**：归档根整树已 `chmod -R a-w`（目录 `dr-xr-xr-x`、文件 `-r--r--r--`，`find -perm +222` = 0），
  唯一允许的破窗是上面的 `chmod -R u+w`，改完必须回到 `a-w`。
- **备份路径**：`mv` 移走的原件在 `~/.Trash/team-feature-deletion-20261104__teams-originals`
  （冗余副本，系统清空废纸篓不影响归档）。
- **还原 ≠ 恢复功能**：让 `teams/*` 重新生效的代码路径已在阶段 1/2 删除
  （`FlowTreeActor.restoreTeams` 无入口、`Load(type:"team")` 分支已摘、`FlowTreeRegistry.getOrCreate` 零调用方）。
- `agents/` 槽位零命中 ⇒ **无还原动作**。

---

## 6. 明确**不在**归档范围（本步未动，附实测）

| 未动对象 | 读数 | 理由 |
|---|---|---|
| `/Users/kaiyu/.stand/sessions/*/flows.json` | **24 个，全部原地**（归档前后同读数） | **活数据，`MountedFlowStore` 仍在用**；团队 ≠ 挂载记录 |
| `/Users/kaiyu/.stand/sessions/*/flow-tree.json` | **0 个残留**（阶段 1 已归档） | 本步只复核，不动 |
| `/Users/kaiyu/.stand/projects/*`、`~/.stand/chain*`、`~/.stand/flowmap*` | 全部未动 | 不在本批范围 |
| `~/.stand/agents/**`（99 文件） | **一字节未动** | 零命中，无声明可搬 |
| `~/.stand/archives/26d25e9d/flow-creator/team.json` | 未动（263 B） | §3.4，已在他人归档里 |

**生产实例安全**：未重启、未 signal、未用 `stand` CLI 改状态；本步全部操作是文件系统级 `cp/mv/chmod/shasum`，
只作用于 `~/.stand/{teams,agents,archive,sessions}` 下的路径。**零 `rm`**。

---

## 7. 本目录内容

```
reports-evidence/team-feature-deletion/data-archive/
├─ README.md                             # 本文件
├─ commands.log                          # 全部命令原文 + 全部读数（流水）
├─ MANIFEST.tsv                          # 归档根 MANIFEST 的同字节副本（113 行）
├─ RESTORE.md                            # 归档根 RESTORE.md 的同字节副本（含阶段 1 §1–§5 + 阶段 4 §6–§11）
├─ crosscheck-boundary-manifest.txt      # 与边界节点 22:28 快照的 103/103 交叉验证输出
└─ agents/
   ├─ JUDGE-INDEX.md                     # 「团队声明」判据与零命中结论
   └─ raw/                               # 6 份判据原始输出（A 键名 / B Load / C team 字段 / D-E 粗 grep / F 残留 / G 源码）
```

## 8. 变更面（零代码改动自证）

```bash
git status --porcelain     # 只有 reports-evidence/team-feature-deletion/data-archive/ 下的新增
git diff --stat            # 空（无已跟踪文件被改）
git diff --cached --stat   # 只有 evidence 目录
```
