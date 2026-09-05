# 2026-08-30 标签页重启后丢失 — 分析报告

> 阶段文档（诊断分析，完成即冻结）。纯只读分析，未修改任何产品代码。
> 范围：Nebflow 客户端（`src/main/resources/web/`）标签页体系 + 重启恢复行为。
> 用户报告：未手动关闭标签页，JVM 重启后 Sub-Agents 面板 / flow-run 标签页 / Canvas 文档标签 / 设置 消失。

---

## 一、结论先行

**不是单点 bug，而是「三类 UI 混在一起 + 一个真 bug + 一个设计盲区」的组合。**

| 问题 | 性质 | 判定 |
|---|---|---|
| Canvas 标签页（Teams/Flows/Agents/文件）有 localStorage 持久化 + 恢复机制 | 设计正确 | 正常恢复 |
| `agent:<名>` 详情标签、`flow-def:<名>` DAG 标签：**持久化了但恢复后渲染空白面板** | **真 bug** | 应恢复未恢复（恢复成空壳） |
| flow-run 标签页：刻意排除持久化（运行时 instanceId），后端运行态重启即重置 | 设计预期 + 后端限制 | 重启后必丢，且无法重开 |
| 设置 modal、Sub-Agents（bg-agent）弹窗：会话级 UI，非标签页，无持久化 | 设计预期（未告知用户） | 重启后必丢 |
| URL 标签页：刻意排除持久化（有注释「per spec acceptable」） | 设计预期 | 重启后必丢 |
| 关闭最后一个标签页会 `removeItem` 清空已存列表；新四态 toggle 点活动标签按钮 = 关闭该标签 | 潜在的误触清空向量 | 边缘风险 |

**用户感知的「标签页没了」= 上述 2+3+4 叠加**：flow-run、设置、Sub-Agents 弹窗按设计重启即失（用户不知情）；若同时开着 agent 详情 / flow DAG 标签，重启后恢复成空白面板（bug）；Teams/Flows/Agents/文件标签本应恢复——若这些也丢了，则说明 localStorage 保存被清空或 schema 失效（见 §四 失败模式）。

---

## 二、标签页体系清单

### 2.1 Canvas 标签页（`js/canvas.js` 统一管理）

状态存储：**内存 Map `tabs` + localStorage `nebflow_canvas_tabs`（v2 schema）**
- 持久化触发：`openTab` / `closeTab` / `setActiveTab` / `pinTab` 均调 `persistTabs()`（canvas.js:1046）
- 恢复入口：`restoreTabs()`（canvas.js:1091），main.js:3074 在 `initCanvas()` 之后调用；失败则 fallback `flowCanvas.openTeams()`
- 恢复方式：panel 标签（无 absPath）同步重建 + 派发 `canvas-tab-restore` 事件；文件标签异步 `pop.readFile` → `fileContent` → `workspace-open-item`（含 WS 未就绪延迟到 onopen 的竞态防护）

| Tab id | type | 打开入口 | 持久化 | 恢复 | 恢复后渲染 |
|---|---|---|---|---|---|
| `teams` | teams | flowCanvas.js:618 `openTeams()`（活动栏按钮） | ✅ | ✅ | ✅ `canvas-tab-restore` → `renderTeamsTab()`（flowCanvas.js:677） |
| `flows` | flow | flowCanvas.js:639 `openFlows()`（活动栏按钮） | ✅ | ✅ | ✅ `renderFlowsTab()`（flowCanvas.js:680） |
| `agents` | agents | agentManager.js:171 `openAgents()`（活动栏按钮） | ✅ | ✅ | ✅ `renderAgentManager()`（agentManager.js:578） |
| `agent:<名>` | agent | agentManager.js:300（点 agent 卡片，单击预览/双击钉住） | ✅ | ⚠️ 重建 tab 但**空白面板** | ❌ 无 `canvas-tab-restore` 处理器（handler 只匹配 `id === 'agents'`） |
| `flow-def-<名>` | flow | flowCanvas.js:156 / flowList.js:71（流程卡片「查看 DAG」） | ✅ | ⚠️ 重建 tab 但**空白面板** | ❌ handler 只匹配 `id === 'flows'`，不匹配 `flow-def-*` |
| `flow-run-<instanceId>` | flow-run | flowCanvas.js:231（流启动自动开 / 运行中 flows badge 点开） | ❌ 刻意排除（注释：重启后 instanceId 失效，恢复=死标签） | ❌ 设计不恢复 | — 仅当流**仍在运行**且未被 dismiss 时由 `maybeAutoOpenFlowsTab()` 重开；**但后端运行态不持久化，重启后必然为空** |
| `url:*` | url | canvas.js:810（Pop URL） | ❌ 刻意排除（注释：恢复会成空死面板，「per spec acceptable」） | ❌ 设计不恢复 | — |
| `file:<路径>` | 按 MIME | `openWorkspaceItem()`（canvas.js:791：Pop / 资源管理器 / reference 跳转） | ✅ | ✅ 异步 | ✅ readFile 回包 → 渲染（explorer.js:636 `fileContent`） |

### 2.2 非 Canvas 会话级 UI（用户可能误以为「标签页」）

| UI | 载体 | 打开入口 | 持久化 | 重启行为 |
|---|---|---|---|---|
| 设置 | 居中 modal `#settings-overlay` | activityBar.js:215（活动栏齿轮按钮） | ❌ 无 | 必丢（设计：modal 会话级） |
| Sub-Agents（后台子 agent）查看器 | 全屏 modal popup `flow-agent-overlay fullscreen` | bgAgentPopup.js:85 `openStepPopup()`（聊天头 bg-agent dropdown 点开） | ❌ 无 | 必丢（设计：popup 会话级；WS 事件重连后仅隐藏 view 状态保留在 `chatViews`，但弹窗不自动重开） |
| flow-agent 弹窗 | modal popup | flowAgentPopup.js | ❌ 无 | 必丢 |

---

## 三、每类标签页重启后行为与代码依据

页面两种重启形态，行为不同：

### 形态 A：浏览器页面保持打开（WS 断线自动重连）
- ws.js 断线自动指数退避重连（scheduleReconnect），**无页面重载**（ws.js:383-402；errorRecovery 无 reload 逻辑）
- 内存 `tabs` Map 原样存活 → **所有 Canvas 标签页（含 flow-run）都在**，仅流事件重放
- 设置 modal / Sub-Agents 弹窗也保持打开状态
- 结论：此形态**什么都不丢**。用户报告「丢了」→ 实际经历的是形态 B

### 形态 B：页面重载（浏览器窗口关闭重开 / 刷新 / 浏览器会话恢复）
`restoreTabs()` 从 localStorage 恢复：

| 标签 | 重启后 | 原因（代码位置） |
|---|---|---|
| Teams / Flows / Agents 面板 | ✅ 恢复 | persistTabs 保存（无 absPath 分支）+ canvas-tab-restore 渲染 |
| Canvas 文档标签（file:*） | ✅ 恢复 | readFile 异步管线（explorer.js:636） |
| agent 详情 / flow-def DAG | ⚠️ 恢复成**空白面板** | persistTabs 正常保存，restoreTabs 正常重建 tab，但 `canvas-tab-restore` 无对应 handler（flowCanvas.js:675 只匹配 teams/flows；agentManager.js:578 只匹配 agents） |
| flow-run | ❌ 丢，且无法重开 | ① persistTabs 排除 flow-run（canvas.js:1058）；② 即使 running，`maybeAutoOpenFlowsTab` 依赖后端 `/api/running-flows` —— 但 `MountedFlowStore.scala:13` 明确「Run state is NOT persisted — flows reset to idle on restart」→ 重启后 runningFlows 恒为空；③ 终态 flow-run 无任何重开入口（badge 只列 running） |
| url:* | ❌ 丢（设计） | canvas.js:1060-1063 注释「per spec acceptable」 |
| 设置 modal / Sub-Agents 弹窗 | ❌ 丢（设计） | 非标签页，无持久化 |

---

## 四、附加失败模式（可能导致「连 Teams/Flows/Agents 也没恢复」）

1. **关闭最后一个标签页 = 清空存档**：`closeTab` 在 `tabs.size === 0` 时 `localStorage.removeItem(LS_TABS_KEY)`（canvas.js:518-522）。结合 2026-08-30 新四态 toggle（commit 50144b6e）：**点击当前显示标签对应的活动栏按钮 = toggle 关闭它**（canvas.js:360-364 state 2）。若用户习惯性点活动按钮、而该标签是唯一标签 → 存档清空 → 下次重启 restoreTabs 返回 false → fallback 只开 Teams（main.js:3074-3076）。
2. **旧 schema 残留被丢弃**：`data.v !== 2` 时 removeItem 不恢复（canvas.js:1103-1107，2026-08-10 引入 v2 前的存档会失效）。
3. **localStorage 配额满**：persistTabs 静默 catch（canvas.js:1080），存档停留在旧列表 → 恢复出过期标签（而非全丢）。
4. **多浏览器 / 多 profile / localhost vs 127.0.0.1 手输**：localStorage 按 origin 隔离；启动 URL 恒为 `http://localhost:<port>?token=`（GatewayMain.scala:304-305），正常路径 origin 稳定，但手输 127.0.0.1 会读到空存档。

---

## 五、结论：是否设计缺陷？

**部分缺陷，部分是设计预期但用户不知情：**

1. **真 bug（应修）**：`agent:<名>`、`flow-def-<名>` 被持久化但恢复后渲染空白——持久化承诺了恢复，恢复却交付空壳。这违反 persistTabs 注释自述的「Tabs without absPath are restored synchronously and re-render from live state」。
2. **设计预期但体验断档（建议修或至少明确）**：flow-run 重启即失且**无法重开**（后端不持久化运行态 + badge 只列 running + 终态无重开入口）。用户「看了一半的流」重启后彻底消失。这是 flow-run 体系的既有设计（「被关闭后不复活」只覆盖 dismiss，未覆盖重启）。
3. **设计预期（可不修，建议 UI 层面说明）**：设置 modal、Sub-Agents 弹窗、URL 标签会话级不恢复。
4. **边缘风险（建议加固）**：last-tab-close 清空存档 + 四态 toggle 误触组合。

---

## 六、修复建议（方案 + 涉及文件 + 验收点）

### 修复 1（推荐，低风险）：agent 详情 / flow-def DAG 标签恢复后渲染内容
- **方案**：在 `canvas-tab-restore` 处理器中补全分支——
  - flowCanvas.js:675：`id.startsWith('flow-def-')` → `fetch('/api/flow/dag/' + encodeURIComponent(name))` → `renderStaticDag`（复用 flowCanvas.js:153-165 现有逻辑，抽公共函数 `openFlowDefTab(name)`）
  - agentManager.js:578：`id.startsWith('agent:')` → 复用 `openAgentDetail(name)` 的 fetch 管线（agentManager.js:298-314 抽公共函数）
- **涉及文件**：`js/flowCanvas.js`、`js/agentManager.js`（均只读→改 restore handler）
- **验收点**：
  - 打开一个 agent 详情标签 + 一个 flow DAG 标签 → 刷新页面 → 两标签重建且面板渲染出内容（非空白）
  - 无 JS console 错误；`[restoreTabs] restoring N panel tabs` 日志正常
  - Playwright：`page.goto` 后 `page.locator('.canvas-tab-pane')` 内出现 `.agent-mgr-*` / `.dag-card` 内容节点

### 修复 2（推荐，中风险）：flow-run 重启可重入
- **方案 A（最小）**：持久化 flow-run 标签的**终态快照元数据**（instanceId、flowName、status），restoreTabs 对终态 flow-run 重建 tab 并渲染「已结束」占位卡片（内容不完整但可感知「此流此前在跑」）；running 态继续依赖 maybeAutoOpenFlowsTab（当前后端做不到）
- **方案 B（根治）**：后端持久化 flow 运行态（`MountedFlowStore` 扩展 run state 持久化，重启后恢复 running 态）——改动面大，超出本次纯分析范围，需另行立项
- **涉及文件**：`js/canvas.js`（persistTabs/restoreTabs 对 flow-run 分支）、`js/flowCanvas.js`（终态渲染）、后端 `MountedFlowStore.scala`（方案 B）
- **验收点**：流跑完留标签 → 刷新 → 标签重建并显示终态信息；流 running 中重启 JVM → 恢复后标签重开（方案 B）/ 至少显示「运行中断」（方案 A）

### 修复 3（可选，低风险）：last-tab-close 清空存档加固
- **方案**：`closeTab` 的 `removeItem` 仅当关闭动作来自标签 X 按钮；活动栏 toggle 关闭不触发清空（或延迟到确认）
- **涉及文件**：`js/canvas.js` closeTab / onCanvasPanelButtonClick
- **验收点**：唯一标签用活动栏按钮关闭 → 刷新 → 该标签按存档恢复（行为变更需用户裁定）

### 修复 4（可选，体验说明）：设置 / Sub-Agents 弹窗的会话级属性
- 不改代码，或在 UI 上对重启后消失的会话级面板不加承诺；如用户期望恢复，另立「重启恢复 open modal/popup」需求

---

## 八、复查追加：作者实测 file:* 标签重启后也丢失（2026-08-30 复盘）

> 复查方式：纯只读分析 + Playwright 最小复现（独立临时 Chromium profile 连真实 8080 后端，不污染宿主）。复现脚本：`/tmp/repro-tab-restore.cjs`、`/tmp/repro-tab-restore-extra.cjs`。

### 8.1 结论先行

**「file 标签恢复」的链路本身是好的——但作者浏览器里 `nebflow_canvas_tabs` 存档根本不存在，恢复无从谈起。** 报告 §二/§三 的「✅ 恢复」验证的是**链路正确性**（存档存在时 readFile 异步管线能恢复），未覆盖「**存档缺失/被清空**」这一上游失效点。作者的真实情况是：存档介质（浏览器 localStorage）不可靠 + 一个多窗口清空存档的代码缺陷叠加，导致 file 标签（连同其他所有标签）在重启后静默不恢复。

| # | 证据 | 结论 |
|---|---|---|
| 1 | Playwright 场景 A：预置 v2 存档（teams + file README.md）→ 重载 → 恢复出 `["Teams","README.md"]` | 恢复链路**工作正常**（与报告一致） |
| 2 | Playwright 场景 D：无存档 → `[restoreTabs] no saved tabs` → fallback 只开 Teams | **存档缺失 = file 标签必丢**，且「Teams 在」正是 fallback 产物 |
| 3 | Chrome Default profile 的 localhost:8080 origin 只有 token/sessions/drafts，**无 canvas_tabs**；leveldb 最后写入 8月27 17:34 | 作者浏览器中 canvas_tabs **不存在**（8月27后 Chrome 普通模式未再访问） |
| 4 | 用户确认用 **Safari**；但 macOS Safari 存储（`~/Library/Safari`、`~/Library/Containers/com.apple.Safari`、`~/Library/WebKit/com.apple.Safari`）**全空** | Safari 侧 localStorage **从未落盘**（无痕/会话级） |
| 5 | Playwright 场景 E：窗口2 关闭恢复的标签 → `removeItem` 清空共享存档 → 窗口1 重载后 file 标签丢 | **多窗口清空存档竞态（代码缺陷）复现成功**；作者「常多开」高概率命中 |
| 6 | Playwright 场景 B/C：文件不存在（`file not found`）/相对路径（`path must be absolute`）→ `fileContent{error}` → 静默丢弃 | readFile 失败 = file 标签**静默消失**（仅 console.warn） |

### 8.2 与报告结论的差异解释

- 报告 §五「**文件标签 ✅ 恢复**」依据的是代码链路分析（restoreTabs Phase 2 → pop.readFile → fileContent → workspace-open-item）。**该链路在存档存在时确实工作**（场景 A 实证）。
- **报告未覆盖的失效点**：存档本身可能缺失/被清空。报告 §四 列了 last-tab-close / schema / 多浏览器三类风险，但**未实测浏览器存储**，未识别出「多窗口共享存档的 removeItem 竞态」与「Safari 无痕/存储不可靠」这两条对 file 标签丢失的主因路径。
- 作者观察「面板标签恢复、file 标签丢」的真相：**没有任何标签被「恢复」**——fallback `openTeams()`（main.js:3074-3076）无条件开了 Teams 面板，让用户误以为「面板恢复了、只有文件丢了」。实际是**全部标签都没恢复**。

### 8.3 存档缺失的成因（三条路径，按命中概率）

1. **多窗口 removeItem 竞态（代码缺陷，已复现）**：`closeTab`（canvas.js:518-522）在**本窗口** `tabs.size === 0` 时 `localStorage.removeItem(LS_TABS_KEY)`——localStorage 是**全浏览器共享**的，一个窗口关闭恢复出的标签即清空全局存档，其他仍开着的窗口重载后全部标签丢失。作者「常多开」→ 高概率。
2. **Safari 无痕/存储不可靠（环境因素）**：无痕模式 localStorage 为**内存级**，关闭最后一个无痕窗口即清空；JVM 重启后 `open` 链接若落在无痕窗口（或 Safari 退出重开），存档即失。用户机器 Safari 存储目录全空与之一致。普通模式 Safari 若被「清除历史记录与网站数据」同样清空。
3. **readFile 失败静默丢弃（代码缺陷，已复现）**：存档中 file 标签指向的文件若已被清理（/tmp 临时文件、uploads 清理）或路径非绝对，`pop.readFile` 返回 error → explorer.js:656-659 `if (msg.error) { console.warn(...); return; }` → **标签静默丢弃**，无骨架、无提示。

### 8.4 修复方案（含浏览器环境因素对策）

| 修复 | 方案 | 级别 | 要点 |
|---|---|---|---|
| **F1 服务端持久化 tabs（根治）** | 后端新增 `~/.nebflow/canvas_tabs.json`（存 v2 元数据），`restoreTabs` 优先拉服务端存档，localStorage 仅作缓存 | 推荐·根治 | 彻底摆脱浏览器存储不可靠（无痕/清理/跨浏览器）；与 sessionStore 同生命周期，JVM 重启可恢复 |
| **F2 closeTab 清空加固** | `removeItem` 仅当确认无其他窗口持有存档（storage 事件广播/心跳），或改为写「空标记」而非删除，重启时区分「用户清空」与「窗口误清」 | 推荐·低风险 | 修复多窗口竞态；四态 toggle 关闭唯一标签同样适用 |
| **F3 readFile 失败不静默** | `fileContent{error}` 时保留 tab 骨架（标题 + 「文件不可读」提示），或从存档剔除该条目并提示「文件已被清理」 | 推荐·低风险 | 用户可感知而非标签无声消失 |
| F4 localStorage 写入可观测 | persistTabs 失败（配额/隐私模式）输出 console 警告或 UI 提示；Safari 无痕下提示「标签恢复不可用」 | 可选 | 当前 try/catch 静默吞掉（canvas.js:1080） |
| F5 UI 承诺说明 | 设置页说明「标签恢复依赖浏览器本地存储，无痕/清理后不保证」 | 可选 | 与 F1 二选一（有 F1 则无需） |

**明确不推荐 sessionStorage**：生命周期比 localStorage 更短（页面级），Safari 无痕下同样不可靠，对「JVM 重启后点新链接」场景毫无帮助。

### 8.5 修复验收点

- **F1**：打开 file 标签 → `~/.nebflow/canvas_tabs.json` 出现该条目；清空浏览器 localStorage → 重载 → 从服务端恢复；Safari 无痕窗口打开 file 标签 → 关窗重开 → 恢复
- **F2**：双窗口各开 file 标签 → 窗口2 关闭全部标签 → 窗口1 重载后**两个 file 标签仍恢复**
- **F3**：存档指向不存在文件 → 重载后标签保留为「文件不可读」骨架（或明确提示），不静默消失
- 冒烟：真实启动 → 页面加载 → `[restoreTabs] restoring N panel tabs, M file tabs` 日志符合预期；Playwright 断言重载后 `.canvas-tab` 含 `file:*` id

### 8.6 本次复查关键代码位置

| 关注点 | 位置 |
|---|---|
| restoreTabs Phase 2（file 标签 readFile 恢复） | canvas.js:1149-1187 |
| WS 未就绪 defer（onReconnect） | canvas.js:1170-1183（onopen 无条件回调，ws.js:379） |
| closeTab 最后标签 removeItem（多窗口毒点） | canvas.js:518-522 |
| fileContent error 静默丢弃 | explorer.js:656-659 |
| 后端 pop.readFile（绝对路径校验/文件存在校验） | WebSocketRoutes.scala:2098-2164 |
| 后端 readFile（相对 root 解析，需 sessionId） | WebSocketRoutes.scala:2024-2096 |
| fallback openTeams（存档缺失时的「伪恢复」） | main.js:3074-3076 |
| 存档 key / schema 守卫 | canvas.js:24, 1103-1107 |

---

## 七、关键代码位置速查

| 关注点 | 位置 |
|---|---|
| Tab 状态 Map / activeTabId | canvas.js:31-32 |
| openTab（含 persist） | canvas.js:404-480 |
| closeTab（last-tab 清空存档） | canvas.js:486-530（518-522） |
| 四态 toggle（点活动标签=关闭） | canvas.js:360-364 |
| persistTabs（flow-run/url 排除） | canvas.js:1046-1081（1058/1063） |
| restoreTabs（v2 schema / 两阶段恢复） | canvas.js:1091-1191 |
| 恢复派发 canvas-tab-restore | canvas.js:1137 |
| restore handler（teams/flows） | flowCanvas.js:675-683 |
| restore handler（agents） | agentManager.js:578-582 |
| 运行中 flows badge + dismissed | flowCanvas.js:262-276, 693-699 |
| fetchRunningFlows | flowCanvas.js:525-534 |
| 后端 flow 运行态不持久化 | MountedFlowStore.scala:13 |
| 启动 URL（origin 稳定） | GatewayMain.scala:304-305 |
| restoreTabs 调用点 / fallback openTeams | main.js:3074-3076 |
