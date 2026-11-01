---
type: report
status: final
date: 2026-11-01
topic: 进程级 ProcessRecorder 实施批（F1 recorder 上线 + F2 设备腿接广播；F3 明确不做——作者令 2026-11-01 19:44）
---

# 进程级 ProcessRecorder 实施批（F1+F2）· 批报告

批令：作者 2026-11-01 19:44「recorder 按设计稿实施——F1 + F2 做，F3 明确不做」。设计基线 =
[[20261101_192000_process-recorder-design__delegate-nebflow-24e5c4c3]]（按稿实施，不重新设计）；
上游取证 = [[20261101_183000_crash-recovery-ws-send__delegate-nebflow-7f75c4c6]]（开放项 F1/F2 的
出处批，census 仪表同源）；RCA 参照 = [[20261101_180000_continuation-panel-blank-rca__delegate-nebflow-d86b41ad]]。

## 1. 落地面

### F1 —— ProcessRecorder 上线 + agent\* 单写者移交

- **新增 `src/main/scala/nebflow/gateway/ProcessRecorder.scala`**：gateway 层单例类，构造入参
  `SessionStore`，`apply(json): IO[Unit]` 与 `wsHub.registerListener` 对齐。快路径 = 一次 `type`
  嗅探；在册四帧（agentTextDelta / agentThinking / agentToolEnd / agentDone）的缓冲/冲刷/终态
  语义**逐字**迁自 `makeRecordingWsSend`（含 `team-` 前缀规整、Ai 上 Tool 下冲刷序、
  terminal=true 终态行、段感知 `markRoundTerminal` 回填）——单一来源，无第二份实现。孤儿缓冲扫
  （`sweepOrphans` / `launchOrphanSweep`，idle 冲刷成 Ai 行后清除，不写 terminal）+ 耗时读数
  （AtomicLong count/total/max，`stats` 读数面）。
- **`WebSocketRoutes.scala`**：`makeRecordingWsSend` 的 agent\* 四分支整体迁除（同提交，P1 注记
  在位禁回潮），wrapper 自此只承载 root 帧族；`normalizeNodeSessionId` 随迁（wrapper 内已无
  使用者）。`makeRecordingWsSend` 放宽为 `private[gateway]`（S5 行为钉 test seam）。
- **`GatewayMain.scala`**：`val processRecorder = new ProcessRecorder(sharedResources.sessionStore)`
  （wsHub 构造邻位）；`recorderSetup`（`registerListener` + 孤儿扫纤维 1h 周期 / 24h idle）入
  boot 链，链序 `hubSetup *> recorderSetup *> … *> startupMount`——**先于 startupMount**，腿④
  （startupMount :892 广播）与腿⑤（boot 重挂 :942 广播）的帧自恢复第一帧起必录。腿④⑤的
  「广播≠录制」诚实边界注释改写为 recorder 收口注记（P4）。
- **`ProjectCrashRecovery.scala`**：`recoverDelegateScopes` 的 `wsSend` 参数注释「🔴 诚实边界
  （本参数不覆盖录制）」改写为 F1 收口注记（P4）。

### F2 —— 设备腿接广播（治腿⑥）

- **`DeviceDelegate.scala`**：`initialize` 增第二可选参 `broadcast: Option[Json => IO[Unit]] = None`
  （窄端口**函数值**，禁传 WsHub 本体——core 禁 import gateway 门禁不破）；对象级
  `processBroadcast` 存放 + `currentBroadcast` 读数；`spawnLocalDelegate` 的 `wsSend = None`
  改 `wsSend = processBroadcast`；「不修」三判据注释整块改写为「F2 已接入」收口注记 + 历史注记
  （P3：注释-代码一致性）。
- **`GatewayMain.scala`**：`DeviceDelegate.initialize(sharedResourcesFinal, Some(json =>
  wsHub.broadcast(json)))` 装配点接线。
- 腿⑥链路：帧出 DeviceDelegate → `routeWsSend` 盖 nodeSessionId → wsHub 广播 → recorder 落盘
  ＋ live 同源。

### F3 —— 未做（作者已裁）

零行代码：无历史重建脚本、无渲染层降级改动（`src/main/resources/web/` 零触碰）。

## 2. 验收结果

### 2.1 行为 spec（S1-S7 + P1-P4）

- 新增 `src/test/scala/nebflow/gateway/ProcessRecorderSpec.scala`（9 测）：S1 帧→行
  （三帧序列 ⇒ Ai+Tool+终态 Ai，键=nodeSessionId）、S2 `team-` 规整归属、S3 单写者行为级、
  S4 写路径契约源码钉（走 appendUiMessages/markRoundTerminal，禁 AtomicJson.write/os 直写/fsync）、
  S5 root 零漂移（真实 routes + 真实 SessionStore 跑 root 帧序列，形态与改前一致；recorder 对
  root 帧零动作）、S6 孤儿缓冲扫（超限冲刷+清除+幂等）、S7 真 WsHub 集成（生产装配形态 +
  控制帧/root 帧快路径不录 + 耗时读数）、P1/P2/P4 源码钉。
- 新增 `src/test/scala/nebflow/core/delegate/DeviceDelegateBroadcastSpec.scala`（4 测）：S1 装配缝
  （广播面在册 / 缺省 None 旧形态）、S2 源码钉（禁回退 `wsSend = None` + GatewayMain 必给
  wsHub.broadcast）、S3 = P3 一致性钉（原 S6 改写）、S4 腿⑥链路机械集成（routeWsSend 盖章 →
  真 WsHub → recorder → `.ui.json` 有行 + live 有帧）。
- `DelegateScopeRecoveryWsSendSpec` S6 钉按 F2 落地现实改写（一致性钉，原「不修」判定钉退役）。
- **目标套件 8 件全绿：Total 62, Failed 0**（ProcessRecorderSpec / DeviceDelegateBroadcastSpec /
  DelegateScopeRecoveryWsSendSpec / DelegateContinuationWsSendSpec / DeviceDelegateSpawnTrustSpec /
  RouteSubagentWsSendSpec / SessionStoreHistorySpec / IndexEntryAliasSpec）。
- 既有录制腿回归零变化：S5 行为钉 + P1 源码钉（root 分支逐字未动）+ 上列邻接套件全绿。

### 2.2 census 验红（`evidence/20261101_crash-recovery-ws-send/census.py`，实盘 `~/.stand`）

- A 面：node-\* `.ui.json` **4071 个，零 ai+tool 3899，其中「真干过活却零正文行」3872，有
  正文 172**——与设计稿 §0/§2.6 引的取证批读数**完全一致**（改前红色形态在盘）。
- B 面：boot-recovery 认领 7（resume 4 / failed 3），可测 3，claim 后零行 2 + 只剩注入行 1
  ——腿⑤修复窗口的空白形态在盘。
- C 面：`delegatedBy` 命中 0（腿⑥零实盘样本，与取证批一致）。
- **改后验绿口径**：本批在生产实例部署重启后，新起会话（mtime 晚于部署点的 node-\*.ui.json）
  应全部落入「有 ai/tool」桶——届时复跑 `python3 evidence/20261101_crash-recovery-ws-send/census.py`
  即可验证零新增命中（census 脚本为取证批所有，本批未改）。机制链的集成级证据见 2.1 的
  S7（真 hub 必录）/ S4（腿⑥全链）/ S1/S2/S6（真落盘三面）。

### 2.3 磁盘量级（设计稿 §2.6 口径抽查，2026-11-01 20:05 实盘读数）

- node-\* `.ui.json` 全量：4071 个 / 258.4 MB / 均值 **65 KB**/会话。
- 近 24h 新写：**39 个 / 10.38 MB**（重写窗含在内；当日为重派发日）。
- 结论：与设计稿「30-100 会话/天 × 66KB ≈ 2-7 MB/天」口径吻合；recorder 增量 =
  「今天会丢的那部分会话」开始落盘，量级为 transcript 的零头（24h transcript ≈ 124 MB）。

### 2.4 编译 / 门禁

- `sbt compile`（主源 704 文件）与 `Test/compile` 全绿；改动文件经 scalafmt 检查形态一致。
- 层门禁 `node scripts/check-scala-layers.mjs`：**exit 2 = 配置错「未知顶层包 perception」**——
  该包由 perception 批（commit 985327778 / cddb3d0b0，已落 main）引入而 LAYER_ORDER 未同步，
  **先于本批存在、非本批引入**（本批零新建包；门禁配置文件本批零触碰）。本批新增边全部向下：
  ProcessRecorder(gateway) → core(SessionStore) / shared；DeviceDelegate(core) 仅新增
  `io.circe`/`cats` 函数值（无 gateway import，`grep nebflow.gateway src/main/scala/nebflow/core/`
  零命中）；GatewayMain(gateway 顶层)。门禁 LAYER_ORDER 补录需 perception 批自己的分层裁定，
  本批不代裁。

### 2.5 测试跑法声明（按批令单列）

- 已跑：`testOnly` 分套执行，**未跑全量 `sbt test`**。
- **neblink 隧道组未跑**（`nebflow.neblink.*`，含 RelayTunnel 系列）：该组需真设备在线，
  2026-11-01 当日已挂死过一次，按批令「分套跑，隧道组单列注明」执行。本批改动不触及
  neblink 包（唯一相关面 = DeviceDelegate 的 neblink 数据通道收件装配，其 spec
  DeviceDelegateSpawnTrustSpec 已绿）。

### 2.6 邻接面回归（合并树上串行全跑）

- 范围 = `nebflow.gateway.* + nebflow.core.{project,tools,delegate,node}.*`：
  **Total 3353 / Passed 3345 / Failed 7 / Skipped 1**。
- **7 个失败全部为基线存量红，非本批引入**——判定性实验：把基线 commit `28f626564`
  （不含本批任何提交）纯导出到临时目录跑同四套件，**同 7 个失败逐一复现**（失败集与
  合并树完全一致）：
  1. `RemoteableDeviceParamSpec` + `PresetCatalogInToolDocSpec`：Delegate LLM 面 schema
     键集断言未随「面板命名批」（`name` 参数入 schema）更新——外部批遗留的测试侧重锚欠账；
  2. `MailDelegateContinuationDeliverySpec`（(2)-(5) 四测，15/30s waitUntil 超时）与
     `NodeReportReminderSpec`（R4 投递断言）：投递/完成判据面与基点内的状态矩阵批
     （f62a1795c「完成判定改状态矩阵」）语义变更相关——该批自己的回归欠账。
- 本批自测面（2.1 的 8 套件 62 测）在合并树复跑仍全绿。
- 过程留痕：广回归首跑曾与本批合并树编译并行，同 target 目录双 sbt 竞态产生
  `ClassNotFoundException(NodeStarter$)` 类加载污染——该首跑结果作废，已按串行重跑为准；
  并行 sbt 属本批操作失误（对结果零影响，重跑已覆盖），记录防复发。

## 3. 与设计稿的差异清单

| # | 设计稿口径 | 实施现实 | 判定 |
|---|---|---|---|
| 1 | §2.2「顺手补掉现状 wrapper 的 sessionTextBuffers 只增不清的小泄漏面」 | **未改 wrapper 缓冲**：agent\* 迁离后泄漏面只剩 root 键（root 帧族正常轮必有 done 清底；残留仅崩溃半轮、进程内存生命周期、随进程消亡）。扩扫入 wrapper 需改 root 分支或给它挂触碰读数——违背「root 分支不动缩爆炸半径」。建议并入「root 二期迁 hub」的远景收口一并清账 | 差异（记开放项⑥） |
| 2 | §7 开放项 3：孤儿 TTL 值本稿不裁 | 采纳设计稿自身建议值 **24h**（`launchOrphanSweep(1.hour, 24.hours)`），扫描周期 1h；作者可调 | 按建议值先行 |
| 3 | §6 R1「耗时计数建议挂 /api/health/conn 邻位」 | recorder 内置 `stats`（AtomicLong framesHandled/totalNanos/maxNanos）读数面已备；**未挂** health 端点（挂载点属开放项 #3，留作者裁） | 按开放项留置 |
| 4 | §4 F1 内容④「三处注释钉改写」 | 腿⑤ GatewayMain、ProjectCrashRecovery、DeviceDelegate 三处均已改写，并加 P4 源码钉防回潮 | 落实 |
| 5 | §5 面板端到端「boot 重挂会话重开面板有正文」实拍 | **未做**：需真机 boot+崩溃+重挂+浏览器实拍链路（tests/continuation-panel-blank.spec.mjs 场景 B 复用），超出本批沙箱能力；机制链已由集成级 spec 钉死（S7/S4）。记开放项 | 差异（记开放项⑦） |
| 6 | §2.2 归档缓冲「agentToolEnd 前冲刷正文」等语义 | 逐字搬运（含 team- 规整、冲刷顺序、terminal/markRoundTerminal 分支、注释留痕） | 落实 |

## 4. 开放项

1. **F3**（作者已裁不做）：历史 3872 条零正文会话保持现状，待作者未来重启议题。
2. root 录制二期迁 hub、wrapper 退役（设计稿 §2.3 远景）——届时一并清账差异 #1。
3. 孤儿扫 TTL 值（现 24h）与耗时读数的 /api/health 挂载点——现值可调，等作者确认。
4. sessions 目录长期清理策略（设计稿 R3：并入 O9 议题，不另起炉灶）。
5. **层门禁 LAYER_ORDER 缺 `perception`**：perception 批的分层裁定欠账（本批前已存在），
   合并队列应知悉 `check-scala-layers.mjs` 当前恒 exit 2。
6. （=差异 #1）wrapper root 键缓冲的崩溃半轮残留——随 root 迁 hub 收口。
7. （=差异 #5）boot 重挂面板实拍——待有真机窗口补一次场景 B 实拍。
8. 生产部署后复跑 census（2.2 指引）：新会话零新增命中 = 判别变量转负，验收闭环。

## 5. 提交面

- 分支 `delegate/delegate-nebflow-bf078e5e`：码+spec = `bdfcea3b7`，批报告 = `eb8d7d424`
  （+本次回归读数补注）；真实合并 main 两次均零冲突：`4683e3bfc`（并入 main
  f320d93fd，output-block-gate/usage-scroll/bg-source-chain 等）与 `d47dc84ad`（并入 main
  f026336e4，fm-att-card-unify/todo-face 等；入向零 Scala 主源触碰）。分支基点 =
  `28f626564`。改动面：6 文件修改 + 4 文件新增（2 主源 + 3 测试 + 本报告），
  码面 `git diff --stat` 口径 119+/183-（外科式，无整文件重排噪音——scalafmt 整文件
  重排曾误开，已回退为仅保留本批 hunks）。
