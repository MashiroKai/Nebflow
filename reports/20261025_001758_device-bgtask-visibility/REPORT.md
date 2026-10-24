# REPORT — 多设备后台任务可见批（设备通道任务入统一账本）

作者令 2026-10-25 00:00 逐字：「这里有个问题就是多设备的后台任务我们也要可见」。
分支 `delegate/delegate-nebflow-3b80716f`（基于 main a03fd0ba3）。**编译与 spec 由主会话代跑**（纪律：本批未跑 sbt）。

## 五条需求落位

| # | 需求 | 落位 | 本批产出 |
|---|---|---|---|
| 1 | 设备任务注册进统一账本 + 设备 origin | **本批** | `BgTaskRegistry.deviceOrigin` 单点（origin=`"device"`，originLabel=设备名原样）；`RemoteExecutor.executeRemoteBackground` 注册面与 WS 发射面同源携带（activeTasksJson 快照与实时信封两路径同字段） |
| 2 | 完成通知同腿（exit chip/耗时段/尾段=20/截断标注；失败带 stderr 尾段） | **#823 批已覆盖**（WIP 分支 `delegate-nebflow-f98fc1e2`） | 本批**零触碰** `notifyRemoteBgResult`/`BgNotifyPayload`（该面在 #823）：设备腿与本地腿同一组装单点，#823 落地即同权生效。避免三路改同一函数的合并冲突 |
| 3 | 持久账本（先落账再投递/idle 补投/bg-wait cap 孤儿登记） | **#824 批已覆盖**（WIP 分支 `delegate-nebflow-a8a4de17`） | 本批零触碰：#824 已把 `BgTaskLedger.recordCompletion` 加进设备腿同点（`notifyRemoteBgResult`）。同上，禁重复实现 |
| 4 | persistent（服务型）设备任务同语义豁免 | **本批** | 注册面解析 `persistent`（与本地 BashTool 同判）→ 登记行 `persistent=true` ⇒ 节点完成闸/热重启静默闸/bg-wait 屏障三消费面自动豁免（既有判据 `ActiveTask.persistent`）；**HTTP 等待豁免**：`bgHttpWaitTimeout` 判据（等待型=BgTimeout 3600s 零漂移；persistent=30 天事实无界档），fiber 等待与对端 Bash `timeout` 参数同源同值（防对端 1h 先杀） |
| 5 | 前端设备 origin 芯片 | **本批** | `bgTaskOrigin` device 路径 + 旧后端快照回退（`[<设备>] ` 描述前缀 + kind=remote 判据）；`.bg-origin-device` chip 样式（既有中性令牌，无新色）；title 独立词 `bg.runsOn`（跑在 KAI ≠ 由 KAI 开启）；en/zh-CN 各一键 |

## 改动面

- `src/main/scala/nebflow/core/tools/BgTaskRegistry.scala`：`deviceOrigin` 单点 + `tasksOf(sessionId)` 原始行读面（含 persistent，取证/断言源；`waitingBySession` 的无过滤反面）。
- `src/main/scala/nebflow/core/tools/RemoteExecutor.scala`：
  - `executeRemoteBackground`：persistent 解析/登记、deviceOrigin、fiber 等待上限参数化；
  - 伴生判据单点：`BgTimeout`（自类内上移，引用改限定名）/`PersistentBgHttpTimeout`(30d)/`bgHttpWaitTimeout`/`stripLocalBgFlags`（`run_in_background`+`persistent` 不过线）/`parsePersistent`；
  - `ensureRemoteTimeout` 加 `waitTimeout` 参（缺省 `RemoteExecutor.BgTimeout`，前台腿零漂移）；前台腿同法摘旗标；
  - **未触碰** `notifyRemoteBgResult`/`emitBgTaskFinished`/`startRemoteHeartbeat`（#823/#824 战场，刻意避让 → 三分支对 RemoteExecutor 的改动区域互不相交，合并免冲突）。
- `src/main/resources/web/js/main.js`：`bgTaskOrigin` device 回退链 + `bgDeviceFromDescription` + chip title 分流（`bg.runsOn`）。
- `src/main/resources/web/css/chat.css`：`.bg-origin-device`（同 dispatcher 档中性亮字，标签即区分，无新色令牌）。
- `locales/en.js`+`zh-CN.js`：`bg.runsOn` = `Runs on {origin}` / `跑在 {origin}`。
- 描述面沿用既有 `[设备名] 命令摘要` 形态（未改，已满足「描述取命令摘要」）；等待区（idle 细分行）显示 `w.description` 同前缀自然带设备名。

## spec（主会话代跑清单）

- **新增 `src/test/scala/nebflow/core/tools/DeviceBgTaskSpec.scala`**（7 断言组）：deviceOrigin 单点；activeTasksJson 设备 origin 快照断言；persistent 设备任务等待集三消费面豁免；persistent 旗标登记 round-trip；`bgHttpWaitTimeout` 两档（含 persistent 严格长于 1h）；parsePersistent/stripLocalBgFlags。
- **新增 `tests/bg-device-origin-chip.spec.mjs`**（Playwright，3 test）：T1 设备行 chip=KAI+`.bg-origin-device`+title「跑在 KAI」+远程 kind chip；T2 旧后端快照回退（无 origin 字段→描述前缀提设备名）；T3 nebula 对照行逐字回归。零后端零 sbt。
- **既有回归**：`BgTaskRegistrySpec`（originFor 未动）、`RemoteExecutorResolveSpec`/`RemoteableDeviceParamSpec`（伴生只增不改）、前端 `bg-merged-panel`/`mobile-p6-bgstatus`（chip 面未改既有类别）。

## spec 钉位分工声明（按批令四条钉）

- 「activeTasksJson 含设备 origin 断言」→ DeviceBgTaskSpec ✅ 本批。
- 「完成事件负载 exit chip/尾段断言」→ #823 的 `BgNotifyPayloadSpec`（负载组装单点；设备腿同点消费）。本批不重复钉、也钉不了（该类不在本批基线）。
- 「账本落账+补投断言」→ #824 的 `BgTaskLedgerSpec`/`BgLedgerRedeliverySpec`/`BgWaitCapLedgerSpec`（设备腿同点消费 recordCompletion）。同上。
- 「persistent 豁免断言」→ DeviceBgTaskSpec ✅ 本批（等待集 + HTTP 等待两判据面）。

## 假设与边界

- 需求①「origin=设备名（如 device:KAI）」落为 `origin="device"` + `originLabel="KAI"`（既有 chip 数据模型 = 类别+显示名两字段；CSS 类 `bg-origin-device` 不能带冒号）。若作者要 origin 字段字面量 `device:KAI`，改 `deviceOrigin` 一行 + 前端取 label 逻辑。
- persistent 豁免的「30 天事实无界」：dispatch/sttp 界面收 `FiniteDuration`（无 Inf 档）的工程取舍；语义 = 不被 1h 网络兜底杀，非字面永久。
- 前端 stuck 标签（heartbeat idleMs>10min）对长跑远程任务会显示「无输出」——本地 persistent 同现状，**未改**（守卫不杀，仅标签；改标签超出批令）。
- 合并次序建议：#823 → #824 → 本批（本批对两者零依赖、区域不相交，任意次序可合并；notifyRemoteBgResult 在 #823/#824 之间自身有冲突需主会话裁决）。
