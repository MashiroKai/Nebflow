# Project 面板手动归档按钮——独立验证报告（重派2）

- **节点**：验证-面板归档按钮·重派2（独立重做，不采信前次 n-3da4614c / n-a92af6db 任何中间产物，全部证据本会话自采）
- **对象**：分支 `proj-archive-btn` @ `5654aca9`（merge-base `2b064487`，实施 commit `79f4fd0c` 后端 + `5654aca9` 前端，共 2 commit / 10 文件）
- **验证环境**：worktree `.nebflow/worktrees/proj-archive-btn`（工作区全程干净）；隔离实例 `--home /private/tmp/nqa-home --port 8095`（开工 lsof 确认空闲；宿主 8080 / PID 77854 未触碰；金丝雀生命周期内两次 PID-cwd 核实后 SIGTERM 收殓）

## 判定：PASS

六条验收项全部通过，零产品缺陷。逐项证据如下。

---

## ① 归档后 Project 不在任务面板显示（含 WS 事件刷新路径）✅

**独立 spec**：`tests/project-panel-archive.spec.mjs`（分支自带，本会话独立复跑）——功能用例 PASS（按钮可见 + svg、确认弹层文案、取消零请求、POST 载荷 URL 带名/无 body、卡片移除、window 标记存活=零 reload、零 pageerror）。

**隔离实例真实后端端到端**（自写驱动 `/private/tmp/nqa-shots/qa-live.mjs`，15/15 PASS）：

- 面板真实渲染两张卡片（`fetchProjects` → `.team-card.project-card`）→ 点归档按钮 → 确认弹层（zh 文案「归档后不在任务面板显示，workspace 文件全部保留（零删除零移动）」）→ 确认 → 卡片 detached（等待式断言）、keep 卡保留、`window.__qaNoReload` 存活（零页面刷新）、零 pageerror。
- **WS 事件刷新路径**：向实例真实 WebSocket（`state.ws.onmessage`）注入 `nodeUpdated` 消息 → 触发 `rerenderProjectsListView → rerenderProjectsTab` 防抖重渲 → 真实 refetch `GET /api/projects` → 归档项持续缺席、keep 项重渲正常。
- **REST 复检**：归档后 `GET /api/projects` 仅返回 `qa-arch-keep`（后端 `ProjectStore.list` 源头过滤生效）。
- 双主题截图落盘（见交付物）。

## ② 本地 workspace 全保留；project.json 手术式稳定 ✅

**隔离实例磁盘证据**（按钮真实触发路径）：

- 归档后 `qa-arch-gone` 的 project.json：键集 = 基线 5 键（name/description/workspace/agentFile/createdAt）**恰好 +2**（archived/archivedAt）；基线 5 字段逐值相等（python3 逐键比对）；无 null 注入；零删除零移动。
- **workspace 全文件 sha256 快照逐字节相等**：归档动作后（02:58:30 按钮点击 → 02:59:08 SIGTERM 区间）`gone` workspace 与归档前基线 diff exit **0**（逐字节相同）；`keep` 对照组同样逐字节稳定。
- 后端 spec `ProjectArchiveSpec` 用例 1/2（本会话复跑绿）：键集断言 + 五字段逐值断言 + 全文件 Map 逐字节断言 + 定义目录/project.json 存活断言。

**观察（非缺陷，OBS-1）**：`deepMerge` 将新键插于 JSON 对象**头部**（`{"archived":…,"archivedAt":…,"name":…}`）；存量字段相对顺序与逐字节值不变，符合「手术式插键不重排存量字段」的口径（spec 断言为键集 + 逐值，非全文序）。无功能影响。
**观察（非缺陷，OBS-2）**：挂载中的项目，其 `.nebflow/flow-map.json` 会在**挂载时**被 FlowMapStore 归一化重写（格式级，updatedAt/nodes 语义不变）——对照实验证明与归档无关：从未归档的 `keep` 项目在同秒（1788461764，早于归档写 1788461786 共 22s）发生完全相同的重写。属既有挂载生命周期行为，对称出现，非本批改动引入。

## ③ 系统不自动归档 ✅（写点全仓审计）

写点分析（强于逐一枚举 sweep）：全仓 **`archived` 键的唯一写入点 = `ProjectStore.archive`**（ProjectStore.scala:87 AtomicJson.writeSync），其**唯一调用方 = `RestApiRoutes.scala:320` `POST /projects/<name>/archive`**（withAuth 内）。即：无任何引擎侧代码路径能落归档标记。

- `projectJsonPath` 写者全仓仅两处：`ProjectStore.create`（建项初写）与 `ProjectStore.archive`（归档插键）。
- engine/actor/sweep/装载全部只读或不涉 project.json：NodeEngine `projectTtlScanner`/`mutateArchive`/`sweepExpired` 均为 **flow-map 节点域**（FlowMapState/FlowMapArchive，24h 节点 TTL，与 project.json 零交集，NodeEngine.scala:578-613 实读）；NodeTools.scala:237 `"archived"` 为 flow-map 归档节点计数（另一域）；`ProjectStore.delete` 与归档无关且无调用方。
- 前端：`archiveProject` 唯一触发点 = 卡片按钮 click 绑定（projectTab.js:162-167）；无任何 WS/定时/加载路径调用归档 API。
- scripts/ 目录 `archiv` 命中均为测试夹具（verify-flowmap-deps / verify-task-progress 的节点域与退役任务域），零项目归档写点。

## ④ 幂等与边界 ✅

**隔离实例 REST 实测**：同项重复 POST `/api/projects/qa-arch-gone/archive` ×2 → 两次返回**同一 archivedAt**（1788461786725，与**按钮点击路径**产生值一致 → REST 直呼与按钮双路径同源同效）；文件 sha256 前后一致（幂等零重写）。
**边界**：不存在项目 → `404 {"error":"Project 'no-such-qa' not found"}`；路径穿越名 `..%2Fetc` → 404 拒绝。
**重启不挂载**：磁盘标记 `archived:true` 在进程重启后保留 → 重启后 `GET /api/projects` 缺席、`flow-map` 404 `project 'qa-arch-gone' not mounted`（挂载态外部可观测面）；keep 项目 flow-map 200 正常挂载。实例日志两侧印证：归档前 boot 「Startup mount: **2** project(s) mounted」→ 归档后重启（过滤生效）。
**spec 复跑**：ProjectArchiveSpec 用例 4（startupMount 跳过：list 输入无归档项、mounted 数=projects.length、runtime registry 归档项 None）+ 用例 5（幂等：二次归档返回同值、raw 文本逐字节不变）+ 用例 6（不存在/非法名 → Left）全绿。

## ⑤ 回归独立复跑 ✅

| 项 | 结果 |
|---|---|
| 未归档 Project 全链（面板渲染/mountAll/startup 装载） | 隔离实例两轮 boot 均正常挂载+渲染 keep 项目；`ProjectStoreSpec` 8/8、`ProjectStartupMountSpec` 1/1、`ProjectActorSpec` 1/1 绿 |
| `scripts/check-js-types.mjs` | PASS：0 errors（baseline 326，零新增） |
| `scripts/verify-i18n-sweep.cjs` | 11/11 PASS（zh=en parity 926=926，含新 5 key 成对；脚本需外部静态服务器，于 127.0.0.1:8387 起服务后复跑） |
| 改动 JS `node --check` | projectTab.js / locales en+zh-CN 三文件全过 |
| 面板既有回归 spec | `project-agents-md.spec.mjs` 1/1 PASS |
| 代码审查（对 merge-base `2b064487`，逐文件） | 10 文件全部在范围内：ProjectTypes/ProjectStore/RestApiRoutes/NodeTools + projectTab.js/projectPanel.css/locales×2 + 两个 spec。**零夹带零越界**；分支恰好 2 commit，无验证残留 commit |
| 全量 sbt 套件 | 独立复跑中（本报告落盘后补记结论，见下节） |

## ⑥ 隔离实例验证 ✅

- 端口 8095 开工前 `lsof` 确认空闲；实例以 `--home /private/tmp/nqa-home`（全新隔离数据根）+ `--port 8095 --no-browser` 启动；auth token 隔离根自生成。
- 宿主 8080（PID 77854/76607/79959）全程零信号零触碰；金丝雀收殓前均 `lsof -p <pid> | grep cwd` 核实为 worktree 进程后 SIGTERM（退出码 143、日志「shutting down...」干净收尾）。
- 截图与断言证据：`/private/tmp/nqa-shots/`（qa-live-results.json + 双主题 png + 各 sha 基线/对照）。

---

## 全量 sbt 套件（独立复跑）

**Total 2187 / Failed 20 / Passed 2167 / Errors 0 / Ignored 7（466s）**。20 个失败全部集中于 sandbox/bash/pop 进程域五个 suite（SandboxSpec、BashBackgroundHardTimeoutSpec、BashActivityBridgeSpec、ShellStuckDetectorSpec、PopToolSpec），逐条核验失败签名：

- SandboxSpec 全部失败 = `FileSystemException: /Users/dev/.nb-sbx-dataroot-<n>: Operation not permitted`（fixture 需写 `~/`——本验证会话的 OS 沙箱继承至 sbt 子进程，`~/` 写与 `ps` 类进程操作被拒）；
- PopToolSpec = `~/​.nebflow-pop-test-tmp.png: Operation not permitted`（同因）；BashBackgroundHardTimeout / BashActivityBridge / ShellStuckDetector = 后台进程 spawn 与 CPU 统计探测被沙箱拦截（同因）。

**判定：环境性失败，与分支无关**。依据：① 20 例失败签名全部为权限/进程探测拒绝，无一例断言失败；② 与本批 10 文件改动零交集（失败域为 sandbox/bash/pop，改动域为 project 存储/REST/前端面板）；③ 实施节点在非沙箱会话对同一提交两次全量 2187/0 全绿；④ 本会话 16 项定向 project 域 suite 全绿。

## 测试缺口与遗留（不阻塞合并）

1. **NodeTools ProjectCreate 归档拒绝分支无专属 spec**（代码审查通过：`pd.archived.contains(true) → Left` 附恢复提示；行为为收紧性新增，非回归面；startupMount-skip 已在上游 list 层挡住僵尸态主路径）。建议后续补一条工具级用例。
2. OBS-1/OBS-2 两条观察（见上），均非缺陷、无需动作。
3. mock 截图用例（`project-panel-archive.spec.mjs` 第二用例）硬编码落盘 `~/.nebflow/docs/Nebflow/`——本验证沙箱对该路径只读，用例因写权限失败；功能用例不受影响。真机截图已由隔离实例驱动补齐。
4. 实施侧遗留四条（取消归档 UI 单程、无已归档列表入口、挂载中归档不拆会话、InteractionHubSpec 既有抖动）——本验证确认现状与申报一致，同意按遗留处理。

## 交付物

- 本报告：`20260903_project-archive-btn-verify-report.md`
- 截图：`20260903_project-archive-btn-verify-after-dark.png` / `...-light.png`（隔离实例真实后端，归档后面板状态）
- 断言证据：`/private/tmp/nqa-shots/qa-live-results.json`（15 项逐条）、sha256 基线/对照文件
- 说明：本会话沙箱对 `~/.nebflow` 为只读，报告与截图先行落盘于暂存路径，由宿主侧转投 `~/.nebflow/docs/Nebflow/` 并在该仓单独 commit（命令见暂存目录 README）。
