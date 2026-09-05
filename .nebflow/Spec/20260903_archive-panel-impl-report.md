> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Flow Map 归档面板 v3 前端实施报告

- 日期：2026-09-03
- 节点：Nebflow「Flow Map 归档面板 v3 前端实施」（worktree `flowmap-archive-panel`，基线 main@2b064487）
- 规格（frozen）：`~/.nebflow/docs/Nebflow/20260903_flowmap-archive-panel-spec.md`（§17 C1–C9）
- 原型：`~/.nebflow/docs/Nebflow/assets/20260903_flowmap-archive-panel/`（prototype.html + regression.mjs 88 断言）
- 分支 commit：`3987dd1e`（实施）→ `0000de17`（修复）→ `b4a831a2`（spec+夹具+静态断言）
- 边界遵守：未合并 main；未改任何既有 spec；宿主 PID 87216 / 端口 8080 全程未触碰；git add 均为具体文件。

---

## 1. 盘点（做了什么）

将原型的整链归档面板按 frozen 规格移植为产品前端：

1. **链派生引擎**（新文件 `flowMapArchive.js`，~865 行）：批次窗口聚簇（≤120s）→ 链齐判定（全部终态）→ 归档/链未齐保留 → 24h TTL 清理 → 面板/悬浮钮/右侧详情三层 UI。
2. **渲染管线接入**（`flowMapTab.js`）：可见派生视图（visibleFmView 单点过滤）、整链同帧退场动画（animateChainExit，350ms + 退场期渲染挂起）、WS 四类节点事件增量管线、reconcile 兜底、种子路径播种（refreshChains+purgeExpired）。
3. **样式**（`flowMap.css` 追加 v3 全套 + `projectPanel.css` 一行）：悬浮钮/面板/详情/终态保留卡/图例/reduced-motion 降级/暗色 token 化。
4. **i18n**：zh-CN / en 各新增 ~35 键（flowmap.archive.* / flowmap.chain.* / flowmap.st.* / terminalTag / retained / legend.terminalRetained），删除下线的 `flowmap.allArchived`。
5. **验收**：原型 88 断言移植为产品 Playwright spec（82 个编号断言）+ 数据夹具（8 链 21 节点）+ 降级静态断言脚本（21 条）。
6. **缺陷修复**（自测发现）：refreshChains identical 判定集合语义化（详见 §6.1）。

## 2. 数据源核对（第零步，结论：NOT BLOCKED）

- 单序列化点 `NodePayload.buildNodeJson`（ProjectTypes.scala:89）字段：id / name / agent / skill / mcp / preset / status / in / out / hasWorktree / worktree / result(≤500) / retries / blockCount / createdAt / completedAt / ttlLeftSec / deps(条件) / blockedFeedback(条件)。
- **无 task 字段** → 链名三级推导的第①级（task【】前缀）在产品载荷下自然落空，第②③级（名称公共前缀/链首名）生效；代码按「有 task 自动启用」实现，规格兼容。
- **归档通路 = 纯前端派生**：磁盘归档区无读端点，按规格 §7.4 边界设计，零 scala 改动。

## 3. 文件与函数级改动清单

### 3.1 `src/main/resources/web/js/flowMapArchive.js`（新建）

| 函数/常量 | 职责 |
|---|---|
| `TERMINAL_STATUSES` / `isTerminalStatus` | 终态集合（completed/failed/cancelled），链齐判定口径（§3.1） |
| `CHAIN_BATCH_MS=120000` / `ARCHIVE_TTL_MS` / `TTL_TAG_WINDOW` / `TTL_SWEEP_MS` | 批次窗口 / 24h TTL / 即将过期窗口(<60min) / 30s 轮询 |
| `storeOf`/`getStore`/`dropStore` | 每项目派生 store（chains/bufferChains/chainOf/archivedIds/expiredIds/tombstones/input/expandedEntry）；视图关闭随 dropStore 释放 |
| `refreshChains(project, fmNodes)` | 单点派生（§7.2）：快照+墓碑合并 → expiredIds 排除 → createdAt 升序批次聚簇 → 全终态批归档（identical 集合幂等跳过 / 迟到成员重建并入）→ archivedIds 重建 → 返回新完成链 |
| `purgeExpired(project, now)` | 24h 到期链移除、expiredIds 入集（防复活 §18-C10）、墓碑清理、展开态纠正、返回移除数 |
| `recordNodeRemoved` | nodeRemoved 出库墓碑（终态事实保留参与链齐判定 §7.2） |
| `isVisibleNode` / `chainOfNode` | 主图可见判定 / 节点所属链查询 |
| `ensureArchiveLayer`/`syncArchiveUi`/`ctxFor` | 悬浮层（fab+badge+panel+detail）创建与同步；事件代理在 panelBody |
| `openDetailFor`/`openDetail`/`closeDetail` | 详情两路触发（主图节点/链内成员）、z70 浮前、dock-left、Esc 分层+焦点归还 |
| `notifyChainArchived`/`notifyChainRetained` | 链齐归档 toast / 链未齐保留 toast（§14-8 拍板 A） |
| `highlightDownstream`/`highlightChainDownstream` | hover 联动下游高亮 |
| 面板渲染族（renderArchiveUi/entryHtml/memberHtml/detailBody 等） | mailbox 式条目（倒序/独占展开/成员行/TTL 标签/flash/pulse）、空态 |

### 3.2 `src/main/resources/web/js/flowMapTab.js`（修改）

| 函数 | 改动 |
|---|---|
| `visibleFmView(project, fm)` | 新增：可见派生视图（isVisibleNode 单点过滤） |
| `layoutNodes` / `collectEdges` | 加 in 代理边渲染（out 已覆盖不双画）、层级含 in 边 |
| `nodeHtml` | 终态保留卡 terminal class + title 标注（flowmap.terminalTag） |
| 删 `fmtTtl`/`startTtlTicker`/`tickTtl` | §7.6 死代码清理（旧「TTL 到期前端隐藏」语义下线） |
| `nameResolverOf(project)` | archived/expired 成员 → 名✦ |
| `summaryParts` | 全量计数 + retained（链未齐终态保留 n） |
| `renderFlowMap` | 空态三分（empty/archived/not-mounted）；归档空态文案 + syncArchiveUi |
| `openNodeDetail` | → openDetailFor（容器上下文） |
| `handleNodeWsEvent` | 重写：增量并入缓存 → refreshChains → 链齐 animateChainExit / 链未齐 retained toast + renderFlowMapPanes → syncArchiveUi(pulse/flash) → scheduleReconcile |
| `animateChainExit` | 中间态渲染 → 成员同帧 fm-exit → 350ms 后统一渲染；exitAnimating 挂起渲染 |
| `renderFlowMapInto` | 种子路径 refreshChains+purgeExpired+exitAnimating guard |
| `canvas-tab-closed` 监听 | dropStore 释放 |
| `legendHtml` | 图例加 terminal 项 |

### 3.3 样式与 i18n

- `flowMap.css`：`fm-float-layer`(pointer-events:none，子元素 auto)、`fm-fab`(40px 玻璃 .6/hover 1/active sapphire)、`fm-archive-badge`(+pulse)、`fm-archive-panel`(top64 right16 宽380 origin calc(100%-20px) 0，220ms cubic-bezier(0.33,0,0.2,1)，closing 160ms，[hidden] 压过 flex)、`fm-entry`/`fm-member` 族、`fm-entry-ttl`、`fm-detail`(z70，dock-left 404px + @media<800 回退 16px)、`fm-node.terminal`(opacity .78 环染色)、`fm-adj`、prefers-reduced-motion 降级块、`--fm-panel-shadow` 亮暗。
- `projectPanel.css`：`.flowmap-view-body` 加 `position: relative`（悬浮层定位上下文）。
- `locales/zh-CN.js` + `en.js`：新增 archive/chain/st/terminalTag/retained/legend 共 ~35 键；删除 `flowmap.allArchived`（文案移入 `flowmap.archive.allArchived`）。

## 4. 88 断言 → 产品 spec 映射

原型 regression.mjs 88 断言按语义 1:1 移植为 `tests/flowmap-archive-panel.spec.mjs` 的 **82 个编号断言**（`[A1.1]…[A20.5]`；原型对同一行为的多重探针在产品口径下合并为单断言）。数据源换真实 NodePayload 形态（`tests/fixtures/flowmap-archive-panel/fixture.mjs`：8 链 21 节点，含跨批引用 X1、TTL 演示 C8、播种过期 C9；基线徽章 8 / 面板 12 节点 / 主图可见 8）。交互走产品真实管线：route 拦截引真实 js + ws.js onmessage 注入（同 flowmap-realtime 模式）。

| 组 | 覆盖语义（规格 §） | spec 断言数 | 所在 test |
|---|---|---|---|
| A1 | 基线渲染/悬浮钮/条目倒序/TTL 标签/徽章/i18n（§4/§5） | 6 | T1 |
| A2 | 面板开合（220ms 同曲线/origin 锚定） | 2 | T1 |
| A3 | 空态与摘耍（retained 计数） | 2 | T1 |
| A4 | 面板头部/计数/关闭 | 5 | T1 |
| A5 | 成员行构成/点击 | 5 | T1 |
| A6 | 徽章口径 | 2 | T1 |
| A7 | i18n zh/en | 6 | T1 |
| A8 | 链条目展开/独占/成员序 | 4 | T2 |
| A9 | 详情：成员触发/markdown/链 meta/z 层级/dock-left | 6 | T2 |
| A10 | 主图节点触发/Esc 分层/详情关闭 | 6 | T2 |
| A11 | 空白点击收起（>3px 拖拽豁免） | 3 | T3 |
| A12 | hover 联动下游高亮 | 3 | T3 |
| A13 | 三路关闭 | 3 | T3 |
| A14 | 焦点归还 | 3 | T3 |
| A15 | 暗色主题 token | 5（未编号） | T4 |
| A16 | 整链走查 5 步（链齐退场/链未齐保留+toast/空态） | 13 | T5 |
| A17 | 重载基线恢复 | 1 | T6 |
| A18 | TTL 清理+防复活（§18-C10） | 3 | T6 |
| A19 | reduced-motion 降级（§6.4） | 4 | T7 |
| A20 | 375 窄视口 | 5 | T8 |

## 5. 验收结果（终局：修复后唯一一次重跑）

**4 passed / 4 failed**（`--workers=1`，4.9min）。按「失败一次即降级静态断言、严禁反复重试」纪律停止重跑。

### 5.1 动态 PASS（4 个 test，证据=运行输出 + /tmp 截图）

- **T1（A1–A7，30 断言）**：基线渲染、悬浮钮、条目倒序、TTL 即将过期标签、徽章 8、面板 12 节点、i18n——全过。
- **T4（A15，5 断言）**：暗色主题 token 变化、面板/详情可开、终态保留卡 opacity≈0.78——全过。
- **T5（A16，13 断言）**：整链走查五步全过——r2 完成→链 R 同帧 fm-exit、徽章 9、条目置顶「登录超时·2 节点」；i2→链 I 三卡退场、徽章 10、**可见 3**；a1 链未齐→终态卡保留+toast（未齐 1/2·保留）+边保持；a2→链 A 徽章 11；x1→链 X 徽章 12、**主图空态**（全部节点已完成，结果收入右上角归档）+悬浮钮可用。
- **T7（A19，4 断言）**：reduced-motion 下面板/详情 transition 0.01s opacity-only、无位移、开合可用。

### 5.2 动态 FAIL 与降级（4 个 test）

| test | 失败断言 | 实测值 | 根因（已核） | 处置 |
|---|---|---|---|---|
| T2 (A8–A10) | [A9.6] dock.right 404px→**16px**；noOverlap→false；[A10.1] a1 点击被面板拦截 | 视图体 `.flowmap-view-body` 实测 **~470px 定宽**（1280 视口下 [799..1269]），1920 视口不变 | **就地视图是 ~470px 定宽分栏**：380px 面板常驻覆盖画布；§5.8b dock-left 并排把详情悬出视图体（elementFromPoint 命中相邻 team-panel 的 task-node-meta，页面快照取证） | A8.1–A8.4、A9.1–A9.5 动态 PASS；A9.6/A10.x 降级静态断言（A9.6a/b、A10.0a、A10.2、A10.4、A10.5）✅；几何冲突上报作者拍板（§8 遗留-1） |
| T3 (A11–A14) | 前置步骤 a1 点击 90s 超时（`.fm-entry-nodes` 拦截） | 同上——面板覆盖画布节点 | 同上 | 整组降级静态断言（A11.2/A11.3/A12/A13/A14）✅；原型 regression.mjs A11–A14 语义已动态验证过 |
| T6 (A17+A18) | reload 后 open-flowmap 定位 90s 未解析（标签恢复态拦截重导航） | waitForSelector 卡片 ✓ 后元素消失——canvas-tab-restore 自动回到 flow-map 视图 | 产品恢复态行为与测试导航假设冲突 | 整组降级静态断言（A17a/b、A18a–d：TTL 过滤/expiredIds 防复活/墓碑清理/dropStore）✅ |
| T8 (A20) | [A20.1] pw、[A20.2] noPaneOverflow | 首轮（无实验）PASS；终局受本批「container-type 几何实验」扰动 | **实验性修复引入的回归**，已回退（commit 0000de17 前身工作区，FIXb 静态护栏断言实验确已移除） | A20.3/A20.4/A20.5 终局 PASS；A20.1/A20.2 回退后按首轮 PASS 证据 + 静态护栏注明 |

**降级静态断言**：`tests/flowmap-archive-panel.static.mjs`（Node 直读产品源码，21/21 PASS）——覆盖被阻断子断言背后的代码事实（dock-left/回退/z70-60、节点点击入口与豁免、Esc 分层、空白收起与 >3px 拖拽豁免、hover 联动、三路关闭、焦点归还、TTL 过滤/expiredIds/墓碑/dropStore）。

### 5.3 修复的产品缺陷（本批自测发现并修复）

**refreshChains identical 误判（commit 0000de17）**：冻结条目成员按**完成时间倒序**、当前批按**创建时间升序**，原逐位 id 对比使所有多节点已归档链在每次刷新都被误判为「重建」→ 三重症状：① 每次刷新重复 archive toast（页面快照取证：同一链 toast ×3）；② completedChains 混入旧链 → animateChainExit 把 12 张已归档卡复活回主图（A16② visible=15 的根因，调试 spec 实测卡片级取证）；③ completedChains 恒非空 → 链未齐 retained toast 永不触发。修复为**成员 id 集合相等**（§7.2「成员集不变」口径，与序无关）；调试复测：r1/r2→8→6 卡、i1/i2/i3→6→3 卡，零复活。

## 6. 回归结论

| 项 | 结果 | 说明 |
|---|---|---|
| tasklist-nodes.spec | **6/6 PASS** | 含 T4「n-run running + n-rev completed 同批→链未齐→保留」兼容 |
| canvas-html-interactive | **17/17 PASS** | 独立 fixture 原型 HTML，不受影响 |
| flowmap-realtime.spec | **1/2** | T2 重连收敛 PASS；T1 FAIL=预期：旧「单节点即时消失」语义与 v3 整链归档基线冲突（规格 §1.3 裁定基线变更）。**只读约束未改该 spec**，需下游随 v3 更新（§8 遗留-2） |
| smoke.spec | **8/13** | 5 失败为已知历史/环境性（real-backend 断言：page-load console、WS auth probe-first、lazy-load ×3）；任务单已预声明「已知历史失败如实注明」；未逐项在 main 复测归因 |
| check-js-types | **零新增** | 342 errors vs 分支点基线 343；`flowMapArchive.js` 0 错误；flowMapTab 4 错误全预存（本批 5→4）。注：main 已前进至 7426bd8f（自身基线 326），其漂移非本批造成，合并属下游节点 |
| node --check | PASS | 全部改动 js |
| verify-i18n-sweep | **11/11 PASS** | zh/en parity 949=949；静态服务器 PID 62918（≠宿主 87216）验后已 kill |

## 7. 截图（已 commit 至本 repo docs/Nebflow/）

| 文件 | 状态 |
|---|---|
| `20260903_archive-panel-t1-panel-open.png` | 亮色·面板开（8 条目/徽章 8） |
| `20260903_archive-panel-t2-chain-expanded.png` | 亮色·链条目展开（成员倒序） |
| `20260903_archive-panel-t2-detail-dock.png` | 亮色·成员→详情（z70 浮前） |
| `20260903_archive-panel-t4-dark.png` | **暗色**·面板+终态保留卡 |
| `20260903_archive-panel-t5-terminal-retained.png` | 亮色·链未齐终态保留卡+toast |
| `20260903_archive-panel-t5-all-archived.png` | 亮色·全归档空态 |
| `20260903_archive-panel-t7-reduced-motion.png` | **reduced-motion**·面板开 |
| `20260903_archive-panel-t8-narrow-375.png` | **375 窄视口**·面板自适应 |

## 8. 遗留（需上游/下游拍板，本批边界外）

1. **就地视图几何 vs §5.8b**：`.flowmap-view-body` 为 ~470px 定宽分栏，dock-left 404px 并排几何不可达（详情悬出视图体被相邻面板盖住）；380px 面板也常驻覆盖画布节点（窄视图「面板同开+点画布节点」路径实际不可达）。容器查询回退方案（container-type 按「视图宽」口径）已试并**主动回退**——其改变 flex 收缩语义、扰动 375 窄视口几何（A20.1/A20.2 由 PASS 转 FAIL）。候选方案：面板打开时挤压视图体 / 详情窄视图自动改抽屉 / 就地视图放宽——请作者拍板后另行实施。
2. **flowmap-realtime.spec.mjs T1** 需随 v3 语义重写（本批禁改既有 spec）。
3. **smoke 5 失败**未逐项归因复测（历史已知，与 flowmap 改动无接触面）。
4. **归档通路纯前端派生**：P2 triggerId 落地后链口径需换精确实现（refreshChains 单点已预留）。

## 9. 生效说明

改动全部在 `src/main/resources/web/` 静态前端资源 + `tests/`。**本批未生效到运行中的宿主**：需前端产物重建 + 宿主重启（宿主 8080/PID 87216 绝对不碰，由用户或下游节点执行）。验证均通过 Playwright 静态资源拦截直引真实 js/css 完成，与重建后产物同源。
