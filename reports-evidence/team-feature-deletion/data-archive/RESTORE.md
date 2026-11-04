# RESTORE — 团队功能删除批（team-feature-deletion-20261104）阶段 1 归档

归档根：`/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/`
建立时间：2026-11-04（阶段 1：停生产能力）
批次：五项边界判定 + 能力删除清单 + 阶段3逐条方案（chain `team-feature-deletion-20261104`）

## 1. 归档内容 manifest

| # | 归档相对路径 | 原绝对路径 | 字节 | sha256 | mtime |
|---|--------------|-----------|------|--------|-------|
| 1 | `sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json` | `/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json` | 862 | `97f111f80222874f8476b8f2f1d74938078974800c5b6089cff3081c381bd74a` | 2026-07-19 08:13:14 |

- 权限：`-rw-r--r--`（`kaiyu:staff`），源码文件原样保留。
- xattr：`com.apple.provenance`（macOS 自动打的原生标记，`cp -a` 已随文件带过来；非业务数据）。
- 归档相对路径**保留原层级**（`sessions/<sid>/flow-tree.json`），即把 `<dataRoot>/sessions/` 当根。
- 阶段 4 会继续往本归档根写 `teams/`（9 个团队目录 / 103 文件），届时本 manifest 追加行。

校验命令（应打印上表同一 sha256）：

```bash
shasum -a 256 "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json"
```

## 2. 为什么这个文件是死的（双端已死）

- **写端已死**：`TurnStateStore.save` 在 2026-08 的假恢复链审计中被整段移除（`src/main/scala/nebflow/core/flow/TurnStateStore.scala:6-18` 头部自述「save 无生产调用点」），此后无任何代码路径再生成 `flow-tree.json`。
- **读端已死**：`grep -rn "flow-tree.json" src/` = **0 命中**（阶段 1 实测，改前改后同）。文件内容里的 `branches` / `phase` / `stepStatus` 属于旧 flow-canvas 面板的数据形态，该面板 2026-09-05 已退役。
- 该会话目录现在**只剩这一个文件**；归档后目录为空壳（`sessions/9d7638fb-…/` 目录本体**保留未删**——`sessions/_index.json` 仍有该 sid 的 1 条记录，删目录属另一件事，不在本批范围）。
- 内容为一条历史 pipeline 分支快照（`test-quick`，`parentBranch: null`，phase `Completed`），与其会话的历史 transcript 已无关联代码路径。

## 3. 为什么用「归档不许 rm」

本批硬约束：团队相关数据**一律归档、绝不 rm**。团队是每个会话激活都在重建的活数据面（`FlowTreeActor.restoreTeams` 会把盘上所有 `teams/*/team.json` 自动挂载），历史上曾误判为死数据；因此即使本文件双端已死、确认无读者，也只移入归档而非删除，保证可回滚。

## 4. 还原命令

单个文件还原（原样拷贝回原位，恢复权限/时间戳）：

```bash
# 1) 解冻归档（归档目录被 chmod -R a-w 冻结）
chmod -R u+w "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104"

# 2) 拷回原位（-p 保留权限与时间戳；源文件带 com.apple.provenance xattr，cp -a 可连 xattr 一起恢复）
mkdir -p "/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3"
cp -a "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json" \
      "/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json"

# 3) 校验（与 manifest 的 sha256 比对）
shasum -a 256 "/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json"
# 期望：97f111f80222874f8476b8f2f1d74938078974800c5b6089cff3081c381bd74a
```

**注意**：还原只恢复数据文件；让该文件重新可用的**代码路径不存在**（写端 `TurnStateStore.save` 与读端消费者均已删除，见 §2）。因此「还原」在此只意味着「拿回字节」，不是「恢复功能」。

## 5. 冻结说明

- 归档根（含本文件）在阶段 1 收尾执行了 `chmod -R a-w`：目录**不可新建/删除子项，文件不可写**（防误改）。
- 唯一例外操作是上面 §4 第 1 步的显式 `chmod -R u+w` 解冻；若阶段 4 需要写入 `teams/` 归档，必须先解冻、写完 manifest、再重新 `chmod -R a-w`。
- 归档根不在任何 git 仓内（`<dataRoot>` 非 repo），因此不随本批 commit 提交；commit 里只有证据目录 `reports-evidence/team-feature-deletion/stage1/`。

---

# 阶段 4 追记（2026-11-04 22:51–22:55）：`teams/` 全量归档 + `agents/` 零命中判据

> 本节由阶段 4 节点**追加**，未改动上文 §1–§5 任何一个字、未重排、未覆盖阶段 1 已归档的
> `sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json`（该文件此刻仍在原位、内容 sha256 未变）。
> 上文第 16 行预写的「阶段 4 会继续往本归档根写 `teams/`（9 个团队目录 / 103 文件）」在此兑付。

**归档日期**：2026-11-04（22:51 cp / 22:52 mv / 22:55 冻结）
**依据**：作者 2026-11-04 22:13 指令「团队功能整体删掉」，并要求「**一律归档，不许 rm —— 删除的是代码路径，不是别人写下的记录**」。
因此本步零 `rm`、零代码改动，只做「复制 → 逐文件 sha256 双跑比对 → `mv` 移走 → 冻结 → manifest」。

## 6. 阶段 4 归档了什么

| 槽位 | 内容 | 文件数 | 字节 | 现位置 |
|---|---|---|---|---|
| `teams/` | 9 个团队目录全树（`team.json` / `rules.md` / `agents/<n>/{agent.json,system.md}` / `.DS_Store`） | **103** | **235,687** | 本归档根 `teams/**` |
| `agents/` | **零命中**：没有任何「团队声明」需要搬（判据见 `agents/JUDGE-INDEX.md`，原始输出 `agents/raw/`）⇒ 该槽位只放证据，**未动源目录任何字节** | 7（索引 1 + raw 6，均为本步**生成**的证据） | 13,023 | 本归档根 `agents/**` |
| `sessions/` | 阶段 1 已归档的 1 个 `flow-tree.json`，本步**未动** | 1 | 862 | 本归档根 `sessions/9d7638fb-…/flow-tree.json` |
| 根 | 本文件 + `MANIFEST.tsv` | 2 | — | 本归档根 |

9 个团队原目录（相对 `<dataRoot>/teams/`）：`czt-project`、`html-deck-studio`、`nebflow-project`、
`nebflow-rust`、`nebflow-website`、`ReminderIsland`、`sipm-paper`、`slideblocks`、`voice-recognition-test`
（其中 `nebflow-project/team.json` 早在归档前已被作者手工改名为 `team.json.archived`，`cp -a` 原样保留该文件名，
归档后路径为 `teams/nebflow-project/team.json.archived`）。

## 7. 原路径 → 归档路径（层级不重排）

本归档根把 `<dataRoot>` 当根，因此归档相对路径 = 原绝对路径去掉 `/Users/kaiyu/.stand/` 前缀：

| 原绝对路径 | 归档路径 |
|---|---|
| `/Users/kaiyu/.stand/teams/` （整棵树，9 个目录） | `/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/teams/` |
| `/Users/kaiyu/.stand/agents/` （**零命中，未搬迁**） | 无（原样留在原位；仅 `agents/JUDGE-INDEX.md` + `agents/raw/` 判据证据在新家） |
| `/Users/kaiyu/.stand/sessions/9d7638fb-b2ea-4630-af34-4d0d32794ce3/flow-tree.json` | 阶段 1 已归档，见 §1 |

**移走机制**：`teams/` 用 `mv`（同一 APFS 卷 `st_dev=16777232`，原子 rename，不搬字节）移到
`~/.Trash/team-feature-deletion-20261104__teams-originals`——**这是「移送」不是删除**，且它只是**冗余副本**：
权威副本是本归档根 `teams/`（已与移走前的源做过 103/103 逐文件 sha256 比对，全等）。
系统若清空废纸篓，丢的只是冗余，归档不受影响。移走后 `mkdir -p` 重建了**空的** `~/.stand/teams/` 容器
（`EntityLoader.listTeams` 有 `!os.exists` 兜底，留空目录纯属保守）。

## 8. 完整性证据（可复核）

```bash
# 归档总数 / 总字节
find /Users/kaiyu/.stand/archive/team-feature-deletion-20261104 -type f | wc -l      # → 113（含 MANIFEST.tsv 自身）
awk -F'\t' 'NR>1 {s+=$2} END {print s}' \
  /Users/kaiyu/.stand/archive/team-feature-deletion-20261104/MANIFEST.tsv            # → 数据行字节之和（112 行；不含 MANIFEST.tsv 自身）

# 归档内 teams/ 的 103 文件
find /Users/kaiyu/.stand/archive/team-feature-deletion-20261104/teams -type f | wc -l # → 103
find /Users/kaiyu/.stand/archive/team-feature-deletion-20261104/teams -type f \
  -exec stat -f '%z' {} \; | awk '{s+=$1} END {print s}'                             # → 235687
```

`MANIFEST.tsv` 四列：`相对路径 <TAB> bytes <TAB> sha256 <TAB> mtime`（**113 行 = 1 行表头 + 112 数据行**；
表头行以 `#` 开头；`MANIFEST.tsv` 自身不入表）。归档**前**（源）与归档**后**两次独立计算的
`shasum -a 256` 清单 `diff` 为空，且 bytes+sha256+mtime 三列全等（103/103）。

## 9. 完整还原命令（`chmod` 回去 + 拷回来 + 校验）

```bash
ARCH=/Users/kaiyu/.stand/archive/team-feature-deletion-20261104

# 1) 解冻归档根（冻结后不可建子项、不可写）
chmod -R u+w "$ARCH"

# 2) 把 103 个文件拷回原位（-a 保权限/时间戳/xattr；`.` 结尾保证 .DS_Store 也回去）
mkdir -p /Users/kaiyu/.stand/teams
cp -a "$ARCH/teams/." /Users/kaiyu/.stand/teams/

# 3) 逐文件校验：期望 diff 无输出 + RESTORE_OK
cd "$ARCH" && awk -F'\t' 'NR>1 && $1 ~ /^teams\// {print $3"  "$1}' MANIFEST.tsv \
  | sed 's#  teams/#  #' | sort -k2 > /tmp/exp.sha
cd /Users/kaiyu/.stand/teams && find . -type f -print0 | xargs -0 shasum -a 256 \
  | sed 's#  \./#  #' | sort -k2 | diff /tmp/exp.sha - && echo RESTORE_OK
find /Users/kaiyu/.stand/teams -type f | wc -l        # 期望 103

# 4) 还原完重新冻结
chmod -R a-w "$ARCH"
```

**若废纸篓里那份还在**（更省事，直接移回来）：

```bash
chmod -R u+w "$ARCH"                      # 若已冻结
mkdir -p /Users/kaiyu/.stand/teams
mv ~/.Trash/team-feature-deletion-20261104__teams-originals/. /Users/kaiyu/.stand/teams/
find /Users/kaiyu/.stand/teams -type f | wc -l        # 期望 103
```

**注意**：还原只拿回**数据**。让 `teams/*` 重新「起作用」的代码路径已在本批阶段 1/2 删除
（`FlowTreeActor.restoreTeams` 无入口可达、`Load(type:"team")` 分支已摘、`FlowTreeRegistry.getOrCreate`
零调用方），因此「还原」= 拿回字节，**不等于恢复功能**。`agents/` 槽位零命中，**无还原动作**。

## 10. 只读（冻结）说明

- 归档根整树在本步收尾执行 `chmod -R a-w`：**目录不可新建/删除/改名子项，文件不可写**。
- 唯一允许的破窗操作是 §9 第 1 步的 `chmod -R u+w "$ARCH"`；改完**必须**回到 `chmod -R a-w "$ARCH"`。
- 本步已遵守：写入前先 `chmod -R u+w`（阶段 1 §4 第 1 步规定的唯一例外），写完 manifest 后重新冻结。
- 归档根不在任何 git 仓内（`<dataRoot>` 非 repo），故只有 manifest/RESTORE 的**副本**随 commit 进仓
  （`reports-evidence/team-feature-deletion/data-archive/`）。

## 11. 本步明确**没有**动的东西

| 未动对象 | 读数（本步实测） | 理由 |
|---|---|---|
| `~/.stand/sessions/*/flows.json` | **24 个，全部原地** | 活数据，`MountedFlowStore` 仍在读写；**团队 ≠ 挂载记录** |
| `~/.stand/sessions/*/flow-tree.json` | **0 个残留**（阶段 1 已归档，源目录只剩空壳） | 双端已死，见 §2 |
| `~/.stand/agents/**` | 99 文件，**一个字节没动** | 零命中 ⇒ 无团队声明可搬 |
| `~/.stand/projects/*`、`~/.stand/chain*`、`~/.stand/flowmap*` | 全部未动 | 不在本批范围 |
| `~/.stand/archives/26d25e9d/flow-creator/team.json` | 未动（263 B，2026-08-15） | 是会话历史归档里的旧快照，不是活团队；已在他处归档，见 `agents/JUDGE-INDEX.md` §5 |
| 生产实例 PID 78176（端口 6138） | 未重启、未 signal、未用 `stand` CLI 改其状态 | 本步纯文件系统操作 |
