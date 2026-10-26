# 合并闸阻塞诊断 + 疏通报告（视觉工作室 931/938）

日期：2026-10-26 15:12–15:35 +0800 · 执行：delegate-nebflow-437a0348（stand）· 红线遵守：未重启引擎、媒体资产零丢失（全量备份 + 逐字节校验后处置）

**TL;DR：#896 代码全量在运行（`4e1fa1c87`，12:23 重启构建树 == HEAD `21f19dfaf`）；但对照作者裁定「合并队列=纯排队+通知，不设阻拦」，#896 把旧制 fail-soft 的工件卫生**升级成了 fail-closed 阻拦闸**（重构走样），且阻塞路径零通知（只落账本行+面板字符串）——三个验收点 a/b/c 全部坐实为缺口。阻塞根因 = `~/.stand` 数据仓数周活现实未入册（1455 条脏，媒体 delegate 连坐被堵）；已备份→校验→三笔归位提交清零，两员于 15:30/15:31 获放行合并（`b722acf`/`b0656f8`），媒体成品全数入册，零丢失、零重启。「回纯排队+通知」引擎改动清单见 §4（派 stand-kai）。**

## 1. 对照作者裁定逐项核实

**裁定原文（逐字）**：「我们不是把合并改为只是一个排队 通知的逻辑吗 它怎么还有阻拦的逻辑，然后也不告知」——验收基准：a) 运行构建含 #896 全部改动；b) 若含，hygiene 阻拦=重构走样/残留缺口；c) 阻塞时无父会话通知=「不告知」坐实。

### a) 12:23 运行构建含 #896 全部改动 —— 坐实（diff 对照，非只看提交号）

- 构建树核对：sbt cwd=`/Users/kaiyu/Claude code/Stand`，HEAD 自 08:32（`21f19dfaf`）后 reflog 零移动，`git status --porcelain -- src/` = **0** ⇒ 12:22–12:23 编译树 == HEAD，`4e1fa1c87` 在其历史内 ⇒ **#896 全量在编译产物里**；
- 运行时行为对照 diff：持久账本 `merge-ledger.json`（#896 新文件，schema=state 机 waiting/turn-granted/merged）、`grant-blocked` 账本行、`careTick` 每拍 pump——全部是 #896 diff 引入的行为，逐一在现役引擎上可观察。

### b) hygiene 阻拦 = #896 自己新写的，违背裁定 —— 坐实（既非旧二进制也非残留）

- 阻拦代码在 #896 diff 本体内：`git show 4e1fa1c87 -- .../MergeQueue.scala` 含 `+ baseReady`/`+ hygiene` 8 处、`+ "still dirty after hygiene"` 1 处；
- **关键对照——旧制语义**：#896 之前（`4e1fa1c87~1`）的 `artifactHygiene(entry)` 是 **fail-soft 非阻断**：「全 fail-soft：任何故障只 WARN，**注入照常**（delegate 的拒绝面兜底…）」——清完白名单工件照样放合并轮，脏基地由 delegate 的 `git merge` 诚实撞死、failed 关账可见；
- **#896 把它升级成了 fail-closed 阻拦闸**：`baseReady()` 余脏非空 ⇒ `Left` ⇒ `requeueBlocked` 留队等下一拍——把「合并轮会可见地失败」变成「整队静默停靠」。这正是裁定指认的「怎么还有阻拦的逻辑」：**重构走样（把该退役的拦截语义以更强形态带进了新制），不是旧逻辑残留、不是构建没带上**。

### c) 「不告知」—— 坐实（阻塞路径无任何通知腿）

- **代码面**：`requeueBlocked()`（MergeQueue.scala :541-549）只做 state→waiting + `persistLocked()` + `record(grant-blocked)` + `logger.info`——**无任何向父会话/作者的通知调用**；`notifyTurn`/`notifyAlreadyLanded` 都只在放行/收尾路径，阻塞路径没有对偶通知；
- **运行时面**：① `chains/931.json` 落了 **26+ 条内容相同的 grant-blocked 账本行**（14:46 起每扫描拍一条），零通知动作；② 项目事件日志 `projects/视觉工作室/.nebflow/flow-map-events.jsonl` 全天 41 条**全部 type=dispatcher-wake，零 message/notify 类事件**——父会话 `d7c3cb54` 从未收到任何合并阻塞消息；③ `stand.log` 全天无一条 merge-queue 阻塞日志（且 logback 对 `nebflow.core.delegate` 的 INFO 有过滤，即便打了也无人看见）；
- 结论：阻塞信息的全部去向 = 账本行 + 面板 `lastBlockReason` 字符串。裁定「然后也不告知」逐字成立。

### 补充证据链（首次核实读数）

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

注：放行得以发生依赖本次**人工疏通**（三笔归位提交清零大仓）——若按裁定的「纯排队+通知」形态，delegate 的合并轮 14:46 首拍就该自己执行：要么直接落册，要么撞上未入册副本诚实 failed 并通知父会话，断不至于静默停靠 44 分钟。

## 4. 缺口结论（按作者裁定改写）

**裁定基准**：合并队列 = 纯排队 + 通知，不设阻拦；卡住必须主动通知（到主会话/作者），不是把原因埋在面板字符串里。对照 §1：#896 落地形态（fail-closed 净空闸 + 阻塞零通知）**违背裁定，a/b/c 三点全部坐实**。本报告初版「停而上报属 #896 设计内行为」的结论**作废**——代码确实照此实现，但该实现本身偏离裁定，属重构走样：旧制（10-25 批）hygiene 本是 fail-soft 非阻断，#896 把它升级成了拦截闸。

**「回到纯排队 + 通知」引擎改动清单（结论；代码另派 stand-kai）**：

### 4.1 拆闸（主改动）

- 位置：`src/main/scala/nebflow/core/delegate/MergeQueue.scala` `baseReady()`（:500-545）+ `requeueBlocked`（:541-549）。
- 改法：删除 fail-closed 语义——余脏非空不再 `Left` 留队；`MergeArtifactCare.hygiene` 降回 best-effort 清障（回到 10-25 批语义），清完照常 `notifyTurn`。脏基地由属主合并轮自己撞上：untracked 覆写/本地改动冲突时 `git merge` 诚实失败 → 合并轮 **failed 关账一次**（可见、面板呈现、dispatcher 处置）——这正是 #896 自己在退役旧制时立下的语义（「a refused round closes the membership failed once」），只是被自家的净空闸挡在门前从未有机会发生。
- 「base 分支非检出分支」的 Left 同理降级：改在放行 brief 里如实告知属主实际座位（属主自行裁决），默认不拦。
- specs：`MergeQueueSpec` 净空闸 fail-closed 断言改判为「脏基地 ⇒ 照常放行 + 合并轮诚实失败」；`MergeQueueRestartReminderSpec` 相应更新。

### 4.2 脏基地自动判性/隔离而非拦截（MergeArtifactCare 从「闸」改「护」）

- 位置：`src/main/scala/nebflow/core/delegate/MergeArtifactCare.scala`（新增 triage 面）+ `MergeQueue.baseReady/pump`（消费点）。
- 放行前 best-effort 四性判定（全部 `record()` 落账，**绝不改变放行结果**）：
  1. 白名单工件（evidence/、reports/）——收编/还原（现状保留）；
  2. tracked 改动且 worktree blob == 即将合入 blob（本次 `说明.md` 形态）——自动 `git restore`，可证安全零丢失；
  3. 合并路径之外的 untracked 文件——自动移入 `<dataRoot>/merge-quarantine/<ts>/`（保留相对路径 + PROVENANCE 清单），放行 brief 告知属主；
  4. 其余（合并路径上的真实未入册改动）——不动、不拦，留给合并轮诚实失败。
- 不变量保持：「源码路径绝不碰」纪律只放宽到 2/3 两种可证安全形态。

### 4.3 阻塞必通知（保底腿，拆闸后仍需要）

- 触发面：合并轮 **failed / owner-gone** 关账时，向**父会话**（`parentSessionId`）补发一条可行动通知：原因 + 全量路径清单 + 隔离路径（现有终态回执只到 dispatcher 面板，父会话无感）；
- 同因去重：reason 集合不变不重发——同时治 `grant-blocked` 记账刷屏（chains/931.json 26+ 条同文行，改「原因集合变化才落行」）；
- 升级阶梯：同一 member 连续 N 拍未推进 ⇒ 通知升级到作者主会话（Nebula）；
- 落点：`MergeQueue` 合并轮失败/阻塞回调 + `DelegateTerminal` 通知腿；通知必须显式落 `node-message` 类事件到 `flow-map-events.jsonl`（本次实证该日志 41 条全是 dispatcher-wake、零 message 事件——不落事件的通知等于没发）。

### 4.4 配套小项

- 面板/API：`take(6)`（:528）与 `lastBlockReason` 300 字符截断修正，`remainingDirty` 全量分页口（账本里已有全列表）；
- 日志：logback 对 `nebflow.core.delegate` 的 INFO 过滤使阻塞不可见——合并队列日志单独 appender 或升 WARN 级。

### 4.5 结构性根因（引擎外，长期）

数据根 `~/.stand` 同时是引擎运行态家、插件目录家、文档/媒体内容仓——媒体项目被大仓任何积存连坐（本次 `bin/nebflow-restart.sh` 堵媒体闸即此机制）。长期：内容线（docs/、projects/ 媒体仓）与运行态分仓，或 projects 各自成仓。

## 5. 产出文件

- 本报告：`reports/20261026_153500_merge-gate-diagnosis/REPORT.md`（stand 仓，delegate/delegate-nebflow-437a0348 分支）
- 隔离区：`~/stand-quarantine/20261026_152602_merge-gate-unclog/`（备份 tar + 快照 + 1 个隔离文件 + PROVENANCE）
- 数据仓归位提交：`c1d5722`（活配置）、`9b6557b`（插件目录 283 文件 + 文档线 1901 文件；插件内容因 add 部分暂存语义并入本笔，消息侧记）、`22217a9`（记忆归档层）
- 隔离区内文件均未删改，如需回滚任何处置：tar 解包回原路径即可。
