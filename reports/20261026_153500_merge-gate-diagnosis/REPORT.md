# 合并闸阻塞诊断 + 疏通报告（视觉工作室 931/938）

日期：2026-10-26 15:12–15:35 +0800 · 执行：delegate-nebflow-437a0348（stand）· 红线遵守：未重启引擎、媒体资产零丢失（全量备份 + 逐字节校验后处置）

**TL;DR：#896 引导员已生效（`4e1fa1c87`，12:23 重启即带上）；阻塞根因 = `~/.stand` 数据仓数周活现实未入册（1455 条脏，媒体 delegate 连坐被堵）；已备份→校验→三笔归位提交清零，两闸于 15:30/15:31 自动放行（`b722acf`/`b0656f8`），媒体成品全数入册，零丢失、零重启。**

## 1. 合并优化落地了吗 —— 落地了

**结论：#896「合并队列交通引导员」已在现役引擎上生效，面板那句 `base workspace still dirty after hygiene` 就是引导员自己的上报，不是旧逻辑残句。**

证据链：

| 证据 | 读数 |
|---|---|
| #896 合入 main | `4e1fa1c87`，2026-10-26 02:43:04 +0800（`Merge queue reworked into a traffic controller`；02:49 补 `b83032441` boot-smoke 证据） |
| 引擎重启 | PID 87923，`.pid.meta` startedAt `2026-10-26T04:23:17Z`=12:23:17 +0800，sbt cwd=`/Users/kaiyu/Claude code/Stand`；重启时 main HEAD=`21f19dfaf`（08:32），`git merge-base --is-ancestor 4e1fa1c87 main` ⇒ **YES**，重启编译必然带上 #896 |
| 运行时物证 | `~/.stand/merge-ledger.json` 存在且有内容——这是 #896 新建的持久账本（`MergeQueue.ledgerPath`），旧制没有 |
| 报错串出处 | `src/main/scala/nebflow/core/delegate/MergeQueue.scala:528`；`git log -S "still dirty after hygiene"` 唯一命中 `4e1fa1c87`（#896 引入） |
| 面板截断之谜 | 也是 #896 自身设计：`remaining.take(6)`（:528）只报前 6 个路径；账本 `lastBlockReason` 截 300 字符 |

两个 delegate 都在 #896 账本里：`delegate-nebflow-7fc476ff`（任务 931，载人深空探测时间线 v2/v3，14:46:49 入队）与 `delegate-nebflow-0cb6e8b4`（任务 938，深空范围示意图图A，15:04:56 入队），state=waiting，单窗 FIFO（7fc476ff 在前）。

## 2. 脏工作区定位与来源判定

### 2.1 关键结构事实

base 工作区 = **`/Users/kaiyu/.stand` 数据根大仓本身**（视觉工作室只是其子目录 `projects/视觉工作室`，projectKey=`/Users/kaiyu/.stand/.git`）。因此：

- **`bin/nebflow-restart.sh` 不是视觉工作室批次写的**——它是数据仓根级运维脚本（`~/.stand/bin/`），脏的是 2026-10-23 运维批（默认端口 8080→6138 对调、NF_HOME→`~/.stand`、NEBFLOW_DIR→Stand 仓、`--restart-if-idle` 解析臂回炉）改动后一直没入册；
- 大仓里**任何位置**的脏都会堵媒体项目的合并闸——这就是「媒体仓的闸被引擎运维脚本堵死」的机制；
- `.gitignore` 的脏是 11:06 ops 批（`0beb0b7` untrack 运行态）只做了 `git rm` 却没提交规则本体，改动一直挂在工作区。

### 2.2 全量清单（诊断时点）

`git status --porcelain` 共 **1455 条 = 47 M + 12 D + 1396 ??**（面板只见 6 条系 take(6) 截断）。分组与判定：

| 组 | 规模 | 内容 | 来源判定 | 处置 |
|---|---|---|---|---|
| 媒体交付物重复副本 | 12 ?? + 1 M | `docs/20261025_载人深空时间线/v3_20261026/`（7 文件）、`docs/20261026_深空定义与范围页/`（6 文件）、`说明.md`(M, 14:57) | 931/938 两 delegate 的**交付物**，但正本**已提交在各自分支**（fb1b57c 8 files +639 / c921daa 6 files +606），base 是未跟踪副本 | 逐字节校验（git blob sha）**13/13 全同** ⇒ 移除副本（正本随合并入册）；`说明.md` 与分支全同 ⇒ `git restore` |
| 非分支交付物 | 1 ?? | `docs/20261026_深空定义与范围页/深空定义与范围_页文案.md` | 图A文案过程稿，delegate 分支未收 | **隔离保全**：`~/stand-quarantine/20261026_152602_merge-gate-unclog/媒体交付物-非分支副本/`（含 PROVENANCE.txt），内容未改动 |
| 活配置 | 2 M | `.gitignore`、`bin/nebflow-restart.sh` | 10-23/10-25 运维批在册欠账 | 归位提交 `c1d5722`（磁盘内容零变更） |
| 在役插件目录 | 283 文件（M+D+??） | visual-report v1.1→v1.5 原位扩容（79 新文件）、`nebflow-backend-dev/docs-prompt/pipelines`→`backend-dev/docs-prompt/pipelines` **更名退役**（D 旧目录 + ?? 新目录）、document-production 全新 110 文件、browser-use/computer-use 新 skill、各包 SKILL.md 修订、skills/ 2 件 | **数周来在数据仓原位生效的在役插件目录**，引擎可能正在读 | 归位提交（随 `9b6557b` 入册，磁盘内容零变更——隔离/还原反而会打断在役插件） |
| 文档线积存 | 1901 文件 | docs/Nebflow（assets 457 + 截图/收口报告/INDEX，mtime 09-16~10-19）、nebflow-website 116、neblink-server 23、research/reports/NebLink/skill-proposals-archive、CONVENTIONS.md、各期 stage 文档 | 历史工作线未入册积存（非媒体线、无运行时影响） | 归位提交 `9b6557b` |
| 记忆归档层 | 13 ?? | `memory-archive/20260914_*_manual/`×11、`20260923_nebflow-project-trim.jsonl`、`20261021_memory-pre-retrieval.md`、`details/` | #103 R1-A 裁定「归档层入册」范围（仓内已有 20260910/20260924 同类 tracked 先例） | 归位提交 `22217a9` |

### 2.3 零丢失保障

1. **全量预备份**：处置前 2299 个脏文件打包 `~/stand-quarantine/20261026_152602_merge-gate-unclog/backup-preclean.tar.gz`（72MB）+ `git-status-preclean.txt` + `dirty-paths.txt` 快照；
2. **删除仅限两处**：媒体重复副本（13/13 事先逐字节验证与分支一致）；其余一切文件内容原样入册或隔离，无一处靠删解决问题；
3. 未触碰两个 delegate 的工作树（`projects/视觉工作室/.nebflow/worktrees/…7fc476ff/0cb6e8b4`）与分支；三笔归位提交经核验**不含任何媒体交付物路径**（grep=0），不会与合并冲突。

## 3. 疏通结果 —— 两闸放行情况

疏通操作时间线（15:26–15:34）：备份 → 媒体副本处置 → 三笔归位提交 → `git status --porcelain` = **0**（全净）。

**两闸均已自动放行，媒体资产全部入册。** 引导员每扫描拍自动复检（`MergeQueueCare.tick` ← `TaskStuckWatcher.scan` 尾部，「净空转洁/阻碍消除后自然推进」），15:29:42 最后一笔归位提交落账后，守望（job 2798fba0）实录放行序列——零人工信号、零重启：

| 时刻 (+0800) | 事件 |
|---|---|
| 15:30:25 | 7fc476ff → turn-granted（引导员授窗） |
| 15:30:20 | 数据仓 main 落合并提交 `b722acf` Merge delegate-nebflow-7fc476ff（15:30:55 账本关账 merged, "merge landed (via merge-round)"） |
| 15:30:55 | 0cb6e8b4 → turn-granted（窗口让给下一位） |
| 15:31:42 | main 落 `b0656f8` Merge delegate-nebflow-0cb6e8b4（15:32:10 关账 merged） |

入册核验：`docs/20261025_载人深空时间线/v3_20261026/` 7 文件（v3 三版时间线 PNG + 渲染脚本）、`docs/20261026_深空定义与范围页/` 6 文件（图A双版式 PNG + 构建脚本）全数在 main；工作区保持 `git status --porcelain` = 0；被隔离的 `深空定义与范围_页文案.md` 不在册（符合预期，见 §2.2）。

## 4. 缺口结论

**「hygiene 清不掉就停」是 #896 设计内行为**：净空闸 fail-closed（余脏非空 ⇒ 留队 `grant-blocked` 记账，绝不放合并轮进脏座位）、零超时、每拍补试、面板显式呈现——这半边没有缺口，且复检是自动的（本次全净后下一拍即应放行，无需任何信号或重启）。

真正的缺口在**闸外**（结论，代码另派 stand-kai）：

1. **结构性根因（最大缺口，非引擎单点可修）**：数据根 `~/.stand` 同时是引擎运行态家、插件目录家、文档/媒体内容仓——净空闸量的是整个大仓，媒体项目被别人的积存连坐。建议长期：内容线（docs/、projects/ 媒体仓）与运行态分仓，或 projects 各自成仓，让闸只量项目自己的工作区。
2. **hygiene 白名单过窄且静态**（仅根级 `evidence/`、`reports/`）：面对慢性脏仓只能停机等人工。建议：per-project 声明式工件目录配置（保持「源码路径绝不碰」不变量），或对「worktree 内容 == 即将合入 blob」的 tracked 改动（本次 `说明.md` 形态）做可证安全的自动还原。
3. **grant-blocked 审计刷屏**：每拍一条完全相同的记账（chains/931.json 里 26+ 条同文），建议仅在原因集合变化时记账或聚合计数。
4. **无升级阶梯**：零超时正确，但堵死 N 拍后应主动把可行动清单（`remainingDirty`）推给 dispatcher/作者（带路径清单的通知），而不是等人在面板发现——本次若非人工巡查，两个成品会无限期停在队里。
5. 小项：`take(6)`/300 字符截断让面板原因天然不完整，建议面板/API 给全量路径的分页口（账本里其实有 `remainingDirty` 全列表）。

## 5. 产出文件

- 本报告：`reports/20261026_153500_merge-gate-diagnosis/REPORT.md`（stand 仓，delegate/delegate-nebflow-437a0348 分支）
- 隔离区：`~/stand-quarantine/20261026_152602_merge-gate-unclog/`（备份 tar + 快照 + 1 个隔离文件 + PROVENANCE）
- 数据仓归位提交：`c1d5722`（活配置）、`9b6557b`（插件目录 283 文件 + 文档线 1901 文件；插件内容因 add 部分暂存语义并入本笔，消息侧记）、`22217a9`（记忆归档层）
- 隔离区内文件均未删改，如需回滚任何处置：tar 解包回原路径即可。
