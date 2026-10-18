# 任务面板「退役台账」泄漏 + project 过滤返空 —— 取证与修复读数

批次：引擎微批 #506（面板退役台账泄漏 + project 过滤器缺陷）
分支：delegate/delegate-nebflow-3b08f64b
纪律：归档/台账文件零字节改动（全程只读取证）；在飞批 #500/#504/#505 工作树零触碰。

## 一、症状复述与实测

面板「任务列表」STAND 组出现 6 条 8–9 天前旧任务（#33/#36/#38/#49/#64/#86，状态标「阻塞/进行中」），
在现役 v2 台账（`~/.nebflow/tasks-v2.json`）中同号不同物：

| 面板行 | 面板标题 | v2 同号条目（实测） |
|---|---|---|
| #33 | UI 修复任务 | 「nebflow — 【新批 · ZCode 扫码绑定机制研究…」(open) |
| #36 | 引擎加固 P1+P2 全修 | 「nebflow — 【新批 · @提及路由…」(open) |
| #38 | 引擎加固重派 | 「nebflow — 【合并落地令 · 两份已批§16出件…」(open) |
| #49 | delegate/subagent UI 修订批 | 「nebflow — 【modelcfg-impl 派发…」(completed) |
| #64 | Neblink 设备无关架构设计 | 「jev-test2 — 【批 C · 多标签打分轮…」(completed) |
| #86 | Logto 门户应用自动创建+登录腿开通批 | 「nebflow — 【渲染优化占位…」(open) |

## 二、面板「项目任务组」数据读取路径（要求①的定位结论）

前端 `taskList.js`（buildNodeSection：按 project 分组、大写组头）的行数据源 = `GET /api/chains`
（`PresenceRoutes.scala:453`）→ `ChainsReadFace.rowsJson` → **`DelegateRegistry.list()`**
= `~/.nebflow/delegates/*.json` 全量现读，仅按 `archivedAt` 分窗。

🔴 面板**不读任何 legacy 台账文件**。两份退役台账（`~/.nebflow/tasks.json` v1 编排账，冻结于
2026-09-25；`<workspace>/.nebflow/task-board.json` 项目板账，即
`/Users/kaiyu/Claude code/Stand/.nebflow/task-board.json`）均不在面板读取路径上——brief 的
「读 legacy board」假设不成立，真实机制如下。

真实泄漏机制：delegate registry 是**实例级共享地址簿**。2026-10-08/10 的 coldstart 隔离实例
测试在共享的 registry 里落了 **105 条**记录（座位 `cwd=/tmp/nebflow-coldstart/general`）。
其中 77 条 finish+archived（不出现在现役窗），**6 条** `reportStatus=None/blocked` 且未归档
→ 前端 rowFromRegistry 推导为 RUNNING/BLOCKED → 渲染成「进行中/阻塞」行，project 缺席 ⇒
落无组头分组、紧贴 STAND 组下方（作者读作 STAND 组条目）。它们的 taskNo/title 是 coldstart
实例**退役台账**派发时刻的快照（6 条与面板泄漏逐条对上：

- `delegate-nebflow-746c079a`（taskId 33, None, 10-09 00:03）
- `delegate-nebflow-13fcebb7`（taskId 36, None, 10-09 00:42）
- `delegate-nebflow-5059ddac`（taskId 38, None, 10-09 06:54）
- `delegate-nebflow-83f47b37`（taskId 49, None, 10-09 07:47）
- `delegate-nebflow-b8a7fc80`（taskId 64, None, 10-09 14:36）
- `delegate-nebflow-70462fee`（taskId 86, blocked, 10-10 03:34）

）——这就是「退役台账条目渲染进现役面板」的真实形态：**退役实例的 registry 残行**。

## 三、修复（要求②：读取面）

`ChainsReadFace.rowsJson`：现役窗（archived=false）加**世界座位闸**——记录座位
（projectWorkspace.getOrElse(cwd)）须落在本实例世界：

- `PathUtil.dataRoot` 子树（缺省 general 座位等引擎侧工作区）；∪
- 各未归档/未卸载项目定义的 workspace 子树（`ProjectStore.list()`，与项目面板/启动挂载同源）。

canonical 比较（相等或子树），确定性解析 = 现存最深祖先 `toRealPath`（symlink 全解，吃掉
`Nebflow→Stand` 改名别名）+ 不存在尾巴词面回接。世界解析失败 = fail-open（不过滤，宁见旧
勿丢现役）。历史窗（?archived=1）零过滤——归档抽屉是全量查看窗，零删除纪律不破。

效果（按现网 registry 实测推演）：现役窗 78 条 coldstart 残行全部消失，在飞三条（#505/#507/#509
的 delegate 行）保留；#500/#504 行不在窗是因其 delegate 已 finish+archived（既有语义，非本闸）。

前端零改动（行源头清了，组渲染自动干净；冲突面只在读取路径）。

## 四、project=stand 过滤返空（要求③）：根因 = 过滤字段错位

`Task list project=stand` 走的是**现役** `Task` 工具（TaskList 是退役名，RetiredToolGuides 墓碑）
→ `TaskLedgerStore.listSync`，精确匹配 `entry.project` 字段。而 #496–#505、#507 的 `project`
字段全是 **None**：派发面 `DelegateTool.createLedgerEntry`（DelegateTool.scala:306）只把 tag
拼进**标题前缀** `"stand — <brief>"`，从不落 `project` 字段（`DeviceDelegate.spawnLocalDelegate`
同病）。字段缺席 ⇒ 精确过滤返空（仅 #506 是 Nebula 手工 `Task create` 落的字段，例外存在）。

修复（写面 + 读面各一刀）：
1. 写面：`DelegateTool.createLedgerEntry` / `DeviceDelegate.spawnLocalDelegate` 补传
   `project = resolvedProject`（标题前缀保留——面板/回执既有显示契约）；
2. 读面：`TaskLedgerStore.listSync` 过滤值 trim 后走 `matchesProjectTag` 单点——字段在场 =
   精确相等（大小写敏感：CZT/czt-project 同仓实证，折叠会串组）；字段缺席 = 回退匹配遗留
   标题前缀 `"<tag> — "`（带两侧空格 + U+2014 定界符，`stand` 不吃 `stand-website — …`）。
   存量 #500/#504/#505 无需数据回写即入窗；活跃台账字节零改动。

## 五、测试（要求④）

- `ChainsReadFaceSpec` C4：外来座位记录不入现役窗、归档窗全量（零删除）。
- `ChainsReadFaceSpec` C4b：symlink 别名座位（改名形态）canonical 判定入世界。
- `TaskLedgerStoreSpec` project filter ×6：字段权威 / 遗留前缀回退 / 定界符防串扰
  （stand ≠ stand-website）/ 字段在场不回退 / trim + 大小写敏感 / 空结果文案回显 trim 值。
- 邻接面回归：DelegateUiDataPlane（夹具座位按新契约落世界内）/ DelegateAutoArchive /
  Registry 三 spec / TaskSearchIndex / TaskSearchTool / DispatcherTaskSlot /
  MailProjectTriggerDemolition / DeviceDelegate / PromptSections —— 全绿；全仓 Test/test 见批次报告。

## 六、归档零改动声明（要求①红线）

全程只读取证：`~/.nebflow/tasks.json`、`~/.nebflow/tasks-v2.json`、两份 history JSONL、
`/Users/kaiyu/Claude code/Stand/.nebflow/task-board.json`、`task-history*.jsonl`、
`~/.nebflow/delegates/*.json`、`~/.nebflow/projects/**` 均零写零删（mtime 未动）。
本分支改动仅：上列 4 个主源文件 + 3 个测试文件 + 本取证件。
