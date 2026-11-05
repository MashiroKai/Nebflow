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
| `~/.stand/sessions/*/flows.json` | **24 个 → 阶段 3 已归档（见 §12）** | 活数据，`MountedFlowStore` 仍在读写；**团队 ≠ 挂载记录** |
| `~/.stand/sessions/*/flow-tree.json` | **0 个残留**（阶段 1 已归档，源目录只剩空壳） | 双端已死，见 §2 |
| `~/.stand/agents/**` | 99 文件，**一个字节没动** | 零命中 ⇒ 无团队声明可搬 |
| `~/.stand/projects/*`、`~/.stand/chain*`、`~/.stand/flowmap*` | 全部未动 | 不在本批范围 |
| `~/.stand/archives/26d25e9d/flow-creator/team.json` | 未动（263 B，2026-08-15） | 是会话历史归档里的旧快照，不是活团队；已在他处归档，见 `agents/JUDGE-INDEX.md` §5 |
| 生产实例 PID 78176（端口 6138） | 未重启、未 signal、未用 `stand` CLI 改其状态 | 本步纯文件系统操作 |

## 12. 阶段 3 追加：24 个 `flows.json`（MountedFlowStore 投影数据）

归档时间：2026-11-05（阶段 3：作者裁定「`MountedFlowStore` + 24 个 flows.json 一并归档清掉」）
归档手法：与阶段 4 同一套 —— `cp -a` → 逐文件 sha256 比对（全等才继续）→ `mv`（同盘 rename）
→ `chmod a-w` 冻结。**全程零 `rm`**。归档相对路径保留原层级（`sessions/<sid>/flows.json`）。

- 源目录：`/Users/kaiyu/.stand/sessions/<sid>/flows.json` × **24**（实测 24 → 0；归档侧 24）
- 总字节：96K（24876 B 文件体）
- 冻结后权限：文件 `-r--r--r--`（0444）；`sessions/` 下各 sid 目录 `dr-xr-xr-x`（0555）
- 校验：`MANIFEST.tsv` **137 行 = 1 表头 + 136 数据行**，逐行 `sha256sum` 复核 **0 不符**

| # | 归档相对路径 | 原绝对路径 | 字节 | sha256 | mtime |
|---|--------------|-----------|------|--------|-------|
| 1 | `sessions/5cc7590a-6dcb-4dba-8979-0ce5eb0a14fe/flows.json` | `/Users/kaiyu/.stand/sessions/5cc7590a-6dcb-4dba-8979-0ce5eb0a14fe/flows.json` | 497 | `05c4a72cbbb4f25dbe74a1b6d2fcb0b63f9463c202638b098fe0e434ec5e20ed` | 2026-08-18 13:01:12 |
| 2 | `sessions/61160b68-9d1d-46b7-bba7-4aabb2d5c440/flows.json` | `/Users/kaiyu/.stand/sessions/61160b68-9d1d-46b7-bba7-4aabb2d5c440/flows.json` | 67 | `1d745da1f49b62eba7a08180705bdc2f2c1fadd4943b29f4aa091c9e6b57e796` | 2026-08-02 22:04:26 |
| 3 | `sessions/771d76fc-2347-4a08-bb52-67eada3936ac/flows.json` | `/Users/kaiyu/.stand/sessions/771d76fc-2347-4a08-bb52-67eada3936ac/flows.json` | 497 | `05c4a72cbbb4f25dbe74a1b6d2fcb0b63f9463c202638b098fe0e434ec5e20ed` | 2026-08-27 18:56:20 |
| 4 | `sessions/857a2723-1bd8-4033-a88b-33d0b3b25f26/flows.json` | `/Users/kaiyu/.stand/sessions/857a2723-1bd8-4033-a88b-33d0b3b25f26/flows.json` | 345 | `29f000a929a2ef0266372f123c50773b2e6fc28dabc67360e5f62160edaf2f66` | 2026-08-09 21:56:58 |
| 5 | `sessions/9f3c4826-47aa-4fd5-967f-b9de85898b35/flows.json` | `/Users/kaiyu/.stand/sessions/9f3c4826-47aa-4fd5-967f-b9de85898b35/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-09-06 09:00:50 |
| 6 | `sessions/a5120141-d8f9-48e9-9de9-4b33090431e1/flows.json` | `/Users/kaiyu/.stand/sessions/a5120141-d8f9-48e9-9de9-4b33090431e1/flows.json` | 389 | `c6a9a01eaf6ef846ddbc5725d973b86cce6ee692222619353c9560cc8d3201a6` | 2026-08-10 17:34:25 |
| 7 | `sessions/aac1c33a-c2d1-4b13-9ecd-3d5c4dc37f88/flows.json` | `/Users/kaiyu/.stand/sessions/aac1c33a-c2d1-4b13-9ecd-3d5c4dc37f88/flows.json` | 389 | `c6a9a01eaf6ef846ddbc5725d973b86cce6ee692222619353c9560cc8d3201a6` | 2026-08-10 17:42:34 |
| 8 | `sessions/ask-0023e0a8/flows.json` | `/Users/kaiyu/.stand/sessions/ask-0023e0a8/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-01 13:29:38 |
| 9 | `sessions/ask-12accd68/flows.json` | `/Users/kaiyu/.stand/sessions/ask-12accd68/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-01 19:41:30 |
| 10 | `sessions/ask-3247adca/flows.json` | `/Users/kaiyu/.stand/sessions/ask-3247adca/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-01 16:14:45 |
| 11 | `sessions/ask-37c189d1/flows.json` | `/Users/kaiyu/.stand/sessions/ask-37c189d1/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-10-31 10:36:16 |
| 12 | `sessions/ask-3a9b062c/flows.json` | `/Users/kaiyu/.stand/sessions/ask-3a9b062c/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-04 09:21:42 |
| 13 | `sessions/ask-6c7e4d40/flows.json` | `/Users/kaiyu/.stand/sessions/ask-6c7e4d40/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-01 09:24:29 |
| 14 | `sessions/ask-7c42c298/flows.json` | `/Users/kaiyu/.stand/sessions/ask-7c42c298/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-10-31 13:37:14 |
| 15 | `sessions/ask-bdc2ec5d/flows.json` | `/Users/kaiyu/.stand/sessions/ask-bdc2ec5d/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-03 16:28:07 |
| 16 | `sessions/ask-e5294458/flows.json` | `/Users/kaiyu/.stand/sessions/ask-e5294458/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-02 10:34:43 |
| 17 | `sessions/ask-e8e30cee/flows.json` | `/Users/kaiyu/.stand/sessions/ask-e8e30cee/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-03 16:28:07 |
| 18 | `sessions/ask-fa062a13/flows.json` | `/Users/kaiyu/.stand/sessions/ask-fa062a13/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-03 12:02:28 |
| 19 | `sessions/ask-fbc8b5b8/flows.json` | `/Users/kaiyu/.stand/sessions/ask-fbc8b5b8/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-01 18:20:08 |
| 20 | `sessions/ask-fd69c219/flows.json` | `/Users/kaiyu/.stand/sessions/ask-fd69c219/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-11-02 18:05:24 |
| 21 | `sessions/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/flows.json` | `/Users/kaiyu/.stand/sessions/d7c3cb54-dd90-4f3f-bbf6-e7898c425cff/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-10-10 10:53:13 |
| 22 | `sessions/dag-git-merge-scanner-405090/flows.json` | `/Users/kaiyu/.stand/sessions/dag-git-merge-scanner-405090/flows.json` | 339 | `48ea8e24fc6ce31a523817cf29dacb5d9737467c2ead2501f2c3c1f43208f5fc` | 2026-08-09 22:57:38 |
| 23 | `sessions/e7facbfa-d6b1-4f44-b475-6575d39ccdd5/flows.json` | `/Users/kaiyu/.stand/sessions/e7facbfa-d6b1-4f44-b475-6575d39ccdd5/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-10-15 18:13:17 |
| 24 | `sessions/ec8ae43f-88bf-4e87-b1ab-a1acea5d3935/flows.json` | `/Users/kaiyu/.stand/sessions/ec8ae43f-88bf-4e87-b1ab-a1acea5d3935/flows.json` | 441 | `c49f43fb2ae3c3b6d73df8f50c65d9465d23dfb1a84d57297bd1df3fdf4e1ae6` | 2026-10-15 18:13:47 |

校验命令（应打印上表同一 sha256，示例第 1 行）：

```bash
shasum -a 256 "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104/sessions/5cc7590a-6dcb-4dba-8979-0ce5eb0a14fe/flows.json"
# 05c4a72cbbb4f25dbe74a1b6d2fcb0b63f9463c202638b098fe0e434ec5e20ed
```

### 为什么它们是死的

- **写端已死**：`MountedFlowStore.scala` 随本阶段删除（`git rm`），`git grep -n "MountedFlowStore" src/`
  归零；项目挂载入口（`Load(type:"flow")` / `MountFlow` / `mountMountedFlow`）已在**阶段 2** 摘除，
  再无任何代码路径调用 `save`。
- **读端已死**：唯一读者是 `MountedFlowStore.load`（同文件），以及 `GET /api/teams/mounted`
  的 `buildMountedTeamsJson`（阶段 2 已退役）。`src/` 内 `flows.json` 仅剩本文件自述字符串（0 代码引用）。
- 数据形态：每文件是一条「会话挂载过哪些 flow 实例」的名字表（`name` / `flowName`），
  不含运行态；重启即重置的设计注记见 `MountedFlowStore` 头部（已随删除一并归档进 git 历史）。

### 还原

```bash
chmod -R u+w "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104"
for f in "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104"/sessions/*/flows.json; do
  sid=$(basename "$(dirname "$f")")
  mkdir -p "/Users/kaiyu/.stand/sessions/$sid"
  cp -a "$f" "/Users/kaiyu/.stand/sessions/$sid/flows.json"
done
chmod -R a-w "/Users/kaiyu/.stand/archive/team-feature-deletion-20261104"
```

**注意**：还原只拿回字节，**不恢复功能** —— `MountedFlowStore` 类已在阶段 3 删除，
`/api/teams/mounted` 路由已在阶段 2 删除。

### 本步明确没有动的东西（阶段 3 追加面）

| 未动对象 | 读数（本步实测） | 理由 |
|---|---|---|
| `~/.stand/sessions/*/` 各 sid **目录本体** | 24 个目录保留（`flows.json` 取走后可能为空壳） | 会话本体 / `_index.json` 记录属另一件事，删目录不在本批范围 |
| `~/.stand/sessions/*/flow-tree.json` | 0 个（阶段 1 已归档） | 见 §2 |
| `~/.stand/teams/**` | 阶段 4 已归档（103 文件） | 见 §9 |
| 生产实例进程 | 未重启、未 signal | 本步纯文件系统操作 |
