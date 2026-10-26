# #943 合并队列回「纯排队+通知」——拆闸 + 自动判性 + 阻塞必通知（引擎修复批报告 2026-10-26）

- 批次：台账 #943（作者裁定 2026-10-26 15:13 逐字）：「我们不是把合并改为只是一个排队 通知的逻辑吗 它怎么还有阻拦的逻辑，然后也不告知」。
- 诊断基线：`reports/20261026_153500_merge-gate-diagnosis/REPORT.md`（Mac main 入册；KAI 侧按任务文本的文件/函数级落点施工）。
- 病灶定性：#896（4e1fa1c87）把旧制 fail-soft 工件卫生升级成 fail-closed 阻拦闸（重构走样），且阻塞路径零通知——排队系统长出了「阻拦 + 静默」两颗牙，违背「交通引导员 = 只持账、只发通知」的裁定。
- 红线遵守：设计零超时（计数/拍全部事件驱动，零 wall-clock 新增）；不重启引擎；真人消息 2026-09-15 逐条独立 turn 裁定面与两个显式豁免零接触；与在飞 10 批的 web/css/js + DeviceProvenance 文件面零重叠（本批只动引擎 scala + 测试 + 本报告）。

## 1. 三件主修

| # | 修复 | 语义 | 代码锚 |
|---|---|---|---|
| ① | **拆闸** | `baseReady` 删 fail-closed 拦截：base 余脏**不再 requeueBlocked 留队停靠**——hygiene 尽力清（还原/收编/判性）后余脏只 **WARN 放行**，delegate 的 `git merge` 诚实撞脏进 failed（原因可见关账）。合并队列回到「纯排队+通知」。**方向闸保留**（base 分支非检出分支 ⇒ Left 留队——绝不把合并轮放进错误座位），但该腿走新通知面，绝不静默 | `MergeQueue.baseReady/requeueBlocked`（MergeQueue.scala） |
| ② | **自动判性**（MergeArtifactCare triage 面） | untracked 工件按 blob 判性：与 delegate 分支 tip **同 blob**（`git hash-object` ∈ `git ls-tree -r` 集）⇒ 陈旧复本**自动清理**（内容分支已保真，合并落地自然回来）；**路径外无关 untracked**（白名单外、分支无同内容，含 porcelain 整目录折叠形 `?? dir/`）⇒ **自动移隔离区**（repo 外 `dataRoot/merge-quarantine/<ws-slug>/<ts>/`，保相对结构，旁挂 **PROVENANCE.json** 记录来源——工作区/原路径/delegate/分支/项目/时间戳/复原指引），零丢失、不再堵队；白名单内、分支无同内容者仍走收编（交付物语义不变） | `MergeArtifactCare.triageUntracked/quarantineInto/allUntrackedPaths`；`hygiene(ws, context)` 增 CareContext；Report 增 triagedRemoved/triagedQuarantined/quarantineDir |
| ③ | **阻塞必通知** | 任何 blocked / failed 路径向父会话发**可行动通知，原因全文不截断**：`requeueBlocked`（方向闸留队）与 `closeFailed`（合并回合拒绝）经新增 `MergeQueue.BlockNotifyPort`（M2 注册器形态，OwnerGonePort 同款，core 不 import agent）；生产装配在 `SharedResources`，通知腿**复用既有 message/notify 通道**（`BgNotifyDelivery.resolveRootSessionId` + `deliverToRootOrInbox`——root 活投/持久收件箱补投）。owner-gone 沿既有 OwnerGonePort（原本就有，不动）。**去重与升级**：同 (kind, reason) 只发一次，原因/状态变化重发；blocked 连续 **N=10 拍**（`EscalateAfterBlockedRounds`，拍=放行尝试，事件驱动零超时）仍堵 ⇒ 恰一次 **escalate** 升级作者；放行成功清账 | `MergeQueue.BlockNotifyPort/noteBlockedOrFailed/clearNotifyMemoLocked`；`SharedResources` BlockNotifyPort.install |

- 面板截断面同步放宽：`lastBlockReason` 账本上限 300 → **2000**；`baseReady` 原因里的 `remaining.take(6)` 截断删除（余脏 WARN 与通知都走全文；审计 `artifact-hygiene` 行的 `dirtyPaths` cap 提到 20）。
- logback：全仓（logback.xml / logback-test.xml / KAI 运行根）核查——**不存在**对 `nebflow.core.delegate` 的 INFO 过滤（root=INFO，第三方才 WARN），无需放行；`requeueBlocked` 的「grant blocked」日志行同步升 **WARN**（可行动路径可见性），通知本身走端口面、与日志级别无关。

## 2. 两件顺带

| # | 修复 | 代码锚 |
|---|---|---|
| ④ **WARN 刷屏根治** | `chain summary downgrade failed: next on empty iterator`（KAI stand.log 每 30s 一条实证）：链摘要成员行 `n.result.map(_.linesIterator.next()…)` 在结果为**空串**时对空迭代器取 next ⇒ 每拍 NoSuchElementException ⇒ 批永不记账、刷屏不止。先判空再取首行（`nextOption()` 兜底）；同族 `chainTitle` 的 task 首行同法加固 | `FlowMapStore.renderChainSummary.row`（FlowMapStore.scala :1417-1426）；`chainTitle` :2002 |
| ⑤ **KAI 合并轮体检** | 结论：**同族代码、未同病发作，另有差异**——见 §3 | — |

## 3. KAI 侧 merge pump 体检（任务顺带⑤）

实测（KAI 引擎，jar `nebflow-assembly-2026.10.26-beta.1.jar`，boot 2026-10-26 08:36）：

1. **同病（代码面）**：jar 内 `MergeQueue$.class` 实测含 `"still dirty after hygiene"` 与 `"grant-blocked"` 字符串——**#896 fail-closed 闸就在在飞 jar 里**；本批修复编译装配后即覆盖（下次部署生效）。
2. **未发作（运行面）**：boot 以来 **merge 队列零活动**——`~/.stand/merge-ledger.json` 不存在、log 全程零 `held for merge`/`[merge-queue]` 审计行。KAI 在飞 10 批是 device-delegate 共享分支模式（branch=delegate-nebflow-c2e6b6d0、共享主工作树），其终态**没有一路走到 `DelegateTerminal.holdForMerge` 入队**——队列无成员、pump 无拍可打。
3. **积压差异**：KAI main 停在 85a79ff74（14:02:31 合入共享分支，git 作者 MashiroKai、**无 merge-queue 审计痕迹** = 非本泵所并）；此后共享分支积压 8+ commit（14:53→15:27）。即：KAI 侧积压**不是** baseReady 闸卡的（队列从未见它们），而是 device-delegate/shared-branch 形态的终态压根不进 KAI 合并队列——**报差异**：该腿属 Mac 侧 project 引导员职责面（分支由 host project 的队列记账），KAI 本批修复覆盖「同族闸病」但**不改变** device-delegate 终态不入队这一形态差异；积压合并仍按既有共享分支流程由 host 侧放行/人合并。

## 4. 各文件落点

**主代码 4**：
- `src/main/scala/nebflow/core/delegate/MergeQueue.scala`：baseReady 拆闸（余脏 WARN 放行）；requeueBlocked 必通知 + lastBlockReason 2000；closeFailed 必通知 + 日志全文；新增 BlockNotifyPort / NotifyMemo / noteBlockedOrFailed / EscalateAfterBlockedRounds=10；grant 成功清账；类 doc 语义更新。
- `src/main/scala/nebflow/core/delegate/MergeArtifactCare.scala`：判性面（triageUntracked/quarantineInto/allUntrackedPaths/branchBlobIds/gitRaw2/quarantineRoot）；hygiene 增 context 参与判性步；Report 扩 triagedRemoved/triagedQuarantined/quarantineDir；类 doc 更新。
- `src/main/scala/nebflow/agent/SharedResources.scala`：BlockNotifyPort 生产装配（blocked/failed/escalate 三文案，原因全文；correlationId 每发唯一——去重语义由队列侧记账负责，投递层不吞重发）。
- `src/main/scala/nebflow/core/project/FlowMapStore.scala`：空迭代器根治两处（row 首行 + chainTitle task 预览）。

**测试 2**：
- `src/test/scala/nebflow/core/delegate/MergeArtifactCareSpec.scala`（新增 6 用例）：同 blob 清理 / 路径外隔离+PROVENANCE / 白名单收编不变 / tracked 还原+路径外不碰 / 整目录折叠隔离 / 非 repo fail-soft。
- `src/test/scala/nebflow/core/delegate/MergeQueueSpec.scala`（+3 用例）：⑨ 余脏不拦仍放行（零 grant-blocked、零误报通知）；⑩ 方向闸留队 + 首拍必通知 + 同因去重 + 第 10 拍恰一次 escalate + lastBlockReason 在案；⑪ failed 关账必通知且原因**全文等长不截断**（>300 字符夹具钉死旧截断面）。

## 5. 验证

- 编译：`Compile / compile` 全绿；`Test / compile` 唯一红点 = 同窗在飞他批 `DeviceProvenanceSpec`（ClientDevice arity 中间态，非本批面，#939 同款会话级排除后跑测试）。
- spec 实跑（session 级排除 DeviceProvenanceSpec）：
  - `MergeQueueSpec` **13/13 绿**——⑨ 余脏放行（零 grant-blocked、零误报通知）/ ⑩ 方向闸留队 + 首拍必通知 + 同因去重 + 第 10 拍恰一次 escalate + lastBlockReason 在案 / ⑪ failed 通知原因**全文等长不截断**（>300 字符夹具钉死旧截断面）；②⑤⑥⑦⑦b⑧ 既有语义回归全绿；
  - `MergeArtifactCareSpec`（新增）**6/6 绿**——同 blob 清理 / 路径外整目录隔离 + PROVENANCE / 白名单收编不变 / tracked 还原 + 路径外不碰 / 整目录折叠隔离 / 非 repo fail-soft；
  - care-tick 族 `MergeQueueBootSweepSpec` **4/4** + `MergeQueueRestartReminderSpec` **3/3** + `WaitingDockCareSpec` **2/2** 全绿。
- **Windows 文件锁 flake（存量在案）根因与根治**：git 松散对象带只读位（444），Windows `Files.delete` 拒删只读文件 ⇒ `os.remove.all` 半途 Abort（表现即 `AccessDeniedException …\.git\objects\…`）——非进程句柄死锁。两 spec 清理腿改为 **attrib -R 清属性 + 重试**（`removeForce/removeRetry`，Windows-only 守卫，零语义变化）；`MergeArtifactCareSpec` 并改为**每用例独立仓目录**（MergeQueueSpec 同款，不跨用例复删带只读位的仓）。spec 侧修复，引擎零接触。
- 既有语义回归：②单持有人、⑤账实核对、⑦/⑦b owner-gone（OwnerGonePort 不动）、⑧持久化全数保持。

## 6. 红线与豁免

- 零 wall-clock 新增（N 拍计数是事件驱动）；不重启引擎（修复进 jar 待既有部署节拍）。
- 真人消息 2026-09-15 裁定面、两个显式豁免（windowItems 先例、InjectionBatchDrainSpec 登记）：零接触。
- 与在飞批文件面避让：本批只动上列 4 主代码 + 2 测试 + 本报告；工作树上他批 web/css/js、DeviceProvenance* 未暂存未触碰。

## 7. 遗留 / 移交

1. KAI device-delegate 终态不入合并队列（§3.3 差异）——归 host 侧 project 队列职责，本批不扩权。
2. 修复生效需按既有部署节拍重装 jar（本批不改运行中引擎）。
3. `git hash-object` 判性按「分支 tip 全树 blob 集」判定同内容——跨路径同内容也判复本清理（语义：内容已在册即可安全清理）；如需收窄为「同路径同 blob」再裁。
