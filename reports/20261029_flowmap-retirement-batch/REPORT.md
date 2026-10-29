# R3 chain-ledger 退役机制 + 存量膨胀清理 · 批报告

- 批次：R3（映射表 P3/P10，作者点名批）
- 依据：作者令 2026-10-29 18:12；设计稿 `docs/design/20261028_213056_delegate-dual-mode-redesign__delegate-nebflow-192d10a5.zh-CN.md` §5.6（per-delegate flowmap 生命周期，Q10 已裁=删）＋§8.3 存量清理建议；审计报告二 §3.4-②③（`docs/20261028_171800_审计报告二五机制__delegate-nebflow-a13975e4.md`）
- 工作分支：`delegate/delegate-nebflow-88974c4d`（独立 commit，未 push；在产零触碰）

## 1. 现状基线（本批复核过的实证）

| 读数 | 值 | 说明 |
|---|---|---|
| 双模式总闸 | **关**（`~/.stand/nebflow.json` 无 `delegate.dualMode` 键 ⇒ 缺省 false） | 双模式批已落地的 flowmap 生命周期腿（`38621827a`）当前 Inert——这是目录持续堆积的直接原因 |
| delegate 作用域目录 | **498 个 / 17,335,259 B**（11 个工作区；审计时 Stand 单区 ~284，现 365） | 明细见 §4 dry-run 清单 |
| 账实不一致（审计 §3.4-②） | **Stand 项目面台账**：`active=4, archived=1660, flow-map 节点=0, rounds=54,309` | 定位=**项目面**（`<ws>/.nebflow/chain-ledger.json`），非 delegate 作用域面；根因见 §5.2 |
| 压缩轮转（审计 §3.4-③） | `chain-ledger-archive/`=**2.3GB / 54,309 个 round 文件**；checkpoint 20.1MB（54,309 条 manifest 内嵌）；rounds 分布 retire=53,962 / compact=341 / dissolve=6 | 28 天 ≈ 日均 1,940 轮；触发条件复查=独立批（§5.1 建议） |

## 2. 机制落点（§5.6 五点 → 文件:行）

§5.6 的载体 = 双模式批已落地的 `DelegateFlowMapLifecycle`（闸 on × mode=dispatcher 才生效；闸关=逐字节现状，本批不扩权）。本批**补齐三处缺口**并钉死时序 spec：

| §5.6 要点 | 落点 | 本批动作 |
|---|---|---|
| ① 终态（completed/failed/cancelled，终态唯一写点）→ onTerminalCleanup 序列内 unmount | `DelegateTerminal.onTerminalCleanup`（`DelegateTerminal.scala:672-676`，`hostUnmount = flushBeforeUnmount *> unmount`）；ready-to-merge 停靠（HoldForMerge）不经此面（既有） | 既有，零改动（序列注释更新对齐 R3 语义） |
| ② unmount 前摘要先行降级登记：sweep 产 chain-archived 事件 + ChainLedger 摘要行；blocked 永不自动归档 | `DelegateFlowMapLifecycle.flushBeforeUnmount`（`DelegateFlowMapLifecycle.scala:86-125`）；sweep 内同刻绑台账 Archived（`FlowMapStore.sweepCompletedChainsDetailed` → `ChainLedgerStore.onChainsArchived`，`ChainLedgerStore.scala:279`）；blocked 不入 `chainArchivable`（`FlowMapStore.scala:1559`） | **扩**：`flushBeforeUnmount` 从「只 sweep」扩为三段收口（出库 → **在飞链收口** → **台账收敛拍**） |
| ②' **取消链走同一收口**（P10 根因修复） | `DelegateFlowMapLifecycle.finalizeAbandonedChains`（`DelegateFlowMapLifecycle.scala:131-176`，**本批新增**）：终态时仍有非终态成员的链 → 台账控制态 `cancelled`（与 NodeCanceller 链级取消**同一个写原语** `setChainControl`）+ `chain-cancelled` 审计事件落基工作区；**节点盘面零触碰**（复活窗安全）；**含 blocked 成员的链整链跳过**（blocked 永不自动处置） | **新增** |
| ③ unmount 后延迟删除：宽限窗 10min（sys prop `nebflow.delegate.flowmap.graceMs`）只读补拉 → 过期删；删除前提=结果已交付 | `DelegateFlowMapLifecycle.scheduleScopeRemoval`（`DelegateFlowMapLifecycle.scala:227-272`）：双闸=未重挂 ∧ archivedAt 在（archivedAt=onTerminalCleanup 收尾 autoArchive 的盘面证据=结果已交付读数）；补拉面=`ChainsReadFace.scopeProjection` 探针腿 | **扩**：探针腿（未挂载）改读活动区∪归档区（`combinedNodes`，`ChainsReadFace.scala:475-489`）——出库成员只有归档区还持有，不读归档区=宽限补拉承诺空转；已挂载腿照旧活动区（活动面板投影零变化） |
| ③' **孤儿收割接续删除** | `DelegateFlowHosts.reapOrphans`（`DelegateFlowHosts.scala:351-383`，**本批扩展**）：孤儿（会话灭∧图静默∧**无 blocked**）⇒ flush → unmount → fork 宽限删除 fiber（**孤儿腿**：宽限过期点盘面静默复核 `scopeQuietRecheck`——复核不过（复活/盘面复活/blocked 在场）= 跳过） | **扩展**（判据仍走 `applies` 单点，闸关=只灭实例现状） |
| ④ 跨进程复活窗不删 | 宽限过期点 `isMounted` 闸（机制面）；跨进程=grace fiber 随进程死（结构不删）＋boot sweep 重挂认领（Running/Interrupted 残留）不受退役影响（盘面零触碰） | 既有＋spec 钉死（D11） |
| ⑤（运维镜像）存量清算的摘要先行 | `reports/20261029_flowmap-retirement-batch/flowmap-backlog-scan.py` `register_retirement()`：有活动区残留的目录删除前落 `scope-dir-retired` 事件（与 FlowMapEventLog 行形逐字同式） | **新增**（运维件） |

## 3. spec 读数（验收断言 · 全绿）

新增断言（`DualModeDispatcherScopeSpec` D8–D11，2026-10-29 20:31 跑批 11/11 通过）：

| 断言 | 验收点 | 读数 |
|---|---|---|
| **D8 全链时序**：终态→摘要行→unmount→宽限窗可读→过期删 | 完成链先经在生期 reconcile 出生台账行 → flush 出库：活动区清空 + `chain-archived` 事件 + 台账行翻 `Archived ∧ archivedAt` 在（**摘要行在 unmount 前落定**）；unmount 后 `ChainsReadFace` 盘面探针腿 `mounted=false` 且**仍能从归档区补拉出库成员**（宽限窗只读补拉落地）；目录在宽限窗内存在；宽限过期后目录整删、投影降级 null | ✅ 0.388s |
| **D9 取消链同一收口**：终态屏障单点 | 在飞链（pending×2）经 flush → 台账控制态 `active→cancelled`（与链级取消**同一写原语**）+ `chain-cancelled` 审计事件落基工作区；**节点盘面零触碰**（两节点保持 pending——复活窗/认领面不受退役收口影响） | ✅ 0.162s |
| **D10 blocked 永不自动处置** | 含 blocked 成员的链：出库腿整链跳过（节点留守活动区）∧ 收口腿整链跳过（台账控制态保持 `active`）∧ 零 `chain-cancelled` 事件——「blocked 永不自动归档」的退役侧镜像 | ✅ 0.112s |
| **D11 复活窗不删** | flush→unmount 后重挂（boot sweep 认领形态）→ 宽限过期点 `isMounted` 闸命中 ⇒ **目录保留**（复活/认领赢过整洁） | ✅ 0.241s |
| D7（既有，双模式批）摘要先行先于 unmount | 出库发生在宿主仍在册窗口（flush→事件→活动区清空→unmount），序列次序断言 | ✅ 0.252s |

回归（全部通过）：核心三套 `DualModeDispatcherScopeSpec` 11/11＋`DelegateTerminalSpec` 14/14＋`DelegateFlowMapLifecycleSpec` 5/5；邻接六套 `ChainsReadFaceSpec` / `DelegateFlowHostsSpec` / `DelegateUiDataPlaneSpec` / `DelegateAutoArchiveSpec` / `ChainArchivedEventSpec` / `ChainLedgerSpec` **52/52**。其中 H6（孤儿收割判据）在首跑揪出本批重构的一处回归（卸载腿被写成无条件，活会话宿主也被卸）——已修复（卸载仍按「会话灭 ∧ 图静默」门控，删除接续腿才走 retire 判据）并复跑 19/19 通过。闸关=逐字节现状由 D6（gate off 走现状注册路径）＋既有 lifecycle spec 的 applies 矩阵共同钉死；H6 的 dead 宿主无 registry 记录 ⇒ `applies=false` ⇒ 只卸载不删目录（闸关形态的活证人）。

## 4. 存量清理（一次性卫生动作 · 已执行）

### 4.1 执行对账（dry-run 20261029_202740 → apply 20261029_202751）

| 指标 | 执行前 | 删除 | 执行后 |
|---|---|---|---|
| 作用域目录数 | **500** | **463** | **37** |
| 字节 | 17,729,767 | **12,893,321** | 4,836,446 |
| 失败 / 跳过 | — | **0 / 0** | — |

- 判据（与 §2 机制逐条同源）：DELETE = registry 记录在 ∧ `archivedAt` 在（终态清理序列跑完=结果已交付）∧ 活动区无 open ∧ 无 blocked ∧ 非本批自身。执行时**逐条复核**（记录不再 archived / 盘面不再静默 / 目录已消失 ⇒ 跳过）。
- 摘要先行降级登记：4 个含活动区残留的目录（0ae466e0 / 253ec234 / 2c615a00 / c93d27ad）删除前落 `scope-dir-retired` 事件（与 FlowMapEventLog 行形逐字同式，残留节点清单随行）；零残留目录零登记。（执行首漏把 nodeId 落成节点对象，已原地修正为 id 字符串并修脚本——4/4 行现在合法。）
- 保留并列表报（**37 个，人工裁定，不擅杀**）：逐目录 id/原因/节点状态/字节 见 `20261029_202740_flowmap-backlog-dry-run.md` §KEEP。原因分布：`not-archived`（无终态清理证据——含 ready-to-merge 停靠、硬死会话）、`blocked`（人工裁定域）、`self`（本批自身作用域）等，多数 KEEP 同时为 `no-record`（历史无主目录）。
- 最年轻 DELETE 类 archivedAt = 51min 前（≫10min 宽限窗），无取件竞态风险。
- 附注：`Stand/.nebflow/flow-map-events.jsonl` 存在 2 行**既有**畸形（line 20213/20214，ts≈2026-10-上旬并发写撕裂的 node-message 行），非本批产物，建议随独立批的事件文件卫生一并处理。

### 4.2 工具留存

`flowmap-backlog-scan.py` 可重跑（dry-run/apply 两态；apply 必须携带 dry-run manifest 且逐条复核）——作为周期卫生工具的候选交付运维。

## 5. 报告外的读数与建议（独立批）

### 5.1 压缩轮转（54,309 轮 GB 级——独立批，本批只给读数建议）

实测（Stand 项目面，2026-10-29）：

- `chain-ledger-archive/` = **2.3 GB / 54,309 个 `round-<n>.json`**（**单轮文件均值 ≈ 44KB**——审计读数「44KB manifest」的实体）；`chain-ledger.json` checkpoint = **20.1 MB**（内嵌全部 54,309 条 RoundManifest，单条 ≈ 371B）；journal 342KB。
- rounds 按类：**retire=53,962**、compact=341、dissolve=6——膨胀主驱是 **retire 轮逐拍落盘**：30s TtlTick 每拍 reconcile，凡有「Archived ∧ refCount≤0」行即写一轮（round 文件＋manifest），委托高峰期几乎每拍命中 ⇒ 日均 ~1,940 轮。
- 结构性放大器：`State.rounds` append-only 永不裁撤 ⇒ checkpoint 随轮数线性膨胀 ⇒ journal 折叠（`rotateBytes=1MB`）每次整写 20MB+ 级 checkpoint。

建议（供独立批裁定，按性价比排序）：

1. **manifest 外置**：`State.rounds` 只留最近 K 条（如 1,000），更早 manifest 追加进 append-only 侧卷 `chain-ledger-rounds.jsonl`（每行已有 digest 可核对，`readRounds` 合并两源读取）——checkpoint 回落 KB 级，折叠成本随之下行。
2. **retire 轮批化/退避**：retire 腿按 N 分钟合并落一轮（或热增量 ≥X% 才落，滞回消抖）——54k 轮 → 百级；冷档文件同步按字节轮转（多轮一文件）。
3. **阈值复查**：`MaxHotBytes=1MB` 对「行小而多」的台账偏紧，每轮 round 开销（44KB manifest+文件）常超被搬载荷本身；建议抬到 8MB 并让 compact 轮吃批量。
4. **冷档压缩**：2.3GB 全为未压缩 JSON；round 文件 gzip 后预计 ~10:1（归档只写不读，读路径已有 `resolveDeep` 逐档回翻，可透明解压）。

### 5.2 「4 条 active vs 0 节点」的定位与根因（项目面；独立批修复建议）

- 定位：`/Users/kaiyu/Claude code/Stand/.nebflow/chain-ledger.json`（**项目 face**，非 delegate 作用域）。4 行 active：`chain-n-70916824`（refCount=1）/ `chain-n-fb990b2f`（refCount=4）/ `chain-n-527b1b92`（refCount=1）/ `zcode-484-fix`（refCount=0），全部 `compacted=true, members=[]`。
- 根因（机械可复述）：① retire 判据=`Archived ∧ refCount≤0`（`ChainLedger.scala:925-928`）——前三行 refCount>0（外部引用面「只计不减」），永不 retire；② 四行 status=active（sweep 同刻绑定未命中它们——绑定只在出库那一刻发生，行出生晚于出库/或绑定漏接）；③ dissolve 判据=「分量无命中」（`hitKeys` 含 **anchor 面**）——这四行的 anchor 节点躺在**归档区**，`topologicalChains(活动∪归档)` 仍派生出含 anchor 的分量 ⇒ 永远「命中」⇒ 永不 dissolve。**归档区成员把台账行钉活**，就是「图没了账还赖着」的项目面形态。
- 处置建议（独立批）：planRetire/dissolve 对「成员全部落在归档区且已 compact」的行按「已离场」处置（或 compact 行的 hitKeys 撤掉 anchor 面）；另加一次性对账（本批不动项目面——运维边界，见 §6）。
- delegate 作用域面实测**零不一致**（498 目录全扫：active>0 ∧ 图空=0 例）——不一致被压缩在项目面一处，且本批的收口机制（②'）封住作用域面的同类路径。

## 6. 边界与本批不做的事

- **在产零触碰**：机制面全部走既有总闸（缺省 false=逐字节现状）；代码不合并不 push，落分支等合并队列。
- **项目面台账**（§5.2 的 4 行 + 2.3GB 冷档）不在本批清理面（独立批，含压缩轮转）；本批只给读数与建议。
- **KEEP 类不擅杀**：38 个保留目录（无终态/not-archived/blocked/self/无记录）全数列表报人工裁定（§4）。
- 复活窗纪律：终态收口对节点盘面**零触碰**（D9 断言）；删除闸=未重挂∧（终态证据∨孤儿盘面复核）。

## 7. 交付物

| 文件 | 说明 |
|---|---|
| `src/main/scala/nebflow/core/delegate/DelegateFlowMapLifecycle.scala` | 三段收口（出库/在飞链取消处置/台账收敛）+ 孤儿腿删除 + 盘面静默复核 |
| `src/main/scala/nebflow/core/project/DelegateFlowHosts.scala` | 孤儿收割接续删除（flush→unmount→宽限删 fiber） |
| `src/main/scala/nebflow/core/project/ChainsReadFace.scala` | 探针腿组合读（宽限窗只读补拉落地） |
| `src/main/scala/nebflow/core/delegate/DelegateTerminal.scala` | 终态屏障注释对齐（序列本体零改动） |
| `src/test/scala/nebflow/core/tools/DualModeDispatcherScopeSpec.scala` | D8–D11 四条验收断言 |
| `reports/20261029_flowmap-retirement-batch/flowmap-backlog-scan.py` | 存量清算扫描/执行器（可重跑的卫生工具） |
| `reports/20261029_flowmap-retirement-batch/*dry-run.md` / `*manifest.json` / `*apply-result.json` | 清单与前后对账 |
