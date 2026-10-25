# 面板合并 + 移动端 UX 七件 · 实施报告

- 作者令：2026-10-23 七项（①合并面板 ②头像点击即换 ③手机头像直入 ④设置页入口退役 ⑤手机面板居中 ⑥后台合并单面板 ⑦画板 bottom sheet）
- 分支：`delegate/delegate-nebflow-8fc678a2`，提交 `340f4a922`（main 未触碰）
- 截图证据：`reports/20261023_182241_panels-mobile-ux/shots/`（18 张 = 桌面 1440×900 + 手机 375×812 × dark/light）
- 截图脚本：`tests/panels-mobile-ux-shots.mjs`（route 夹具 + WS 注入，零后端）

## 七项逐条

### ① 记忆面板与智能体面板合并为一个面板（选型：标签页）
- 形态：`#memory-modal` 顶部 `.merged-tabs` 双页签（智能体/记忆，文案复用 `profile.title`/`memory.title`），`data-pane` 属性驱动 CSS 分区显隐。选标签页而非分区堆叠的理由：两个分区都是整面交互体（记忆 scope 页签族 + 智能体档案），堆叠需双滚动且互相挤压；页签保持各自原有布局零重排。
- 实现：`js/memory.js` — `ensureMergedChrome()`（:150，页签行注入 + `.ap-pane` 分区插入）、`switchPane()`（:48，写 `#memory-modal[data-pane]` + 标题切换）、`openMergedPanel(pane)`（:63 导出）；`js/agentProfile.js` — `buildProfilePane()`（:178）返回 `.ap-pane` 一件式分区，旧独立 `#agent-profile-panel` 弹窗退役（memory.js:9 头注）；`css/modal.css` — `.merged-tabs/.merged-tab`（:393-417）+ `#memory-modal[data-pane='agent']` 隐记忆族（:420-421）。
- 入口：桌面会话头头像 + `#memory-btn` 都走 `openMergedPanel`（memory.js:192）。

### ② 头像点击即更换；删除「重置头像」；无单独「设置头像」
- `js/agentProfile.js:184-188`：`previewBox` 即 `<button class="ap-avatar-box">`，点击直通隐藏 `fileInput.click()`；`resetAvatar` 及 `profile.reset` locale 键删除（`js/locales/zh-CN.js` / `en.js`）；无任何独立「设置头像」钮——预览图就是唯一入口。

### ③ 手机 header 头像直入合并面板；记忆面板按钮退役
- `js/mobile/floatController.js:58-60`：`PANEL_FACE` 增 `{keyId:'agent-avatar-btn', panel: memory-modal, visible: memory-overlay.on}`（头像腿 morph 同款）；`FLOAT_IDS`（:35）去 `memory-btn`；`css/mobileLayer.css:1525` `#memory-btn{display:none}`（桌面入口保留）。

### ④ 手机设置页智能体面板按钮退役
- `js/mobile/navController.js`：`syncProfileBlock/profileEntry` 及 imports/teardown 清理全删；`css/mobileLayer.css` `.profile-entry` 样式块删除；设置页页体首块 = 设置面（`tests/mobile-p12-settings.spec.mjs` T1 断言零入口行 + 无 `.ap-btnrow`）。

### ⑤ 手机各面板居中
- `css/mobileLayer.css`：`.mh-tpanel` 族 / `#daemon-config-overlay(!important)` / `#search-modal` / `#reminder-panel` / `#daemon-panel` / `#bg-dropdown` / `#memory-modal` 全改 `left:0; right:0; margin:0 auto`（:135/:536/:753/:772/:805/:904/:943/:999/:1039/:1172 等；不用 transform 以免与 morph 冲突）。
- 波及断言同步：p2/p3/p4 T1 `left 6 → 33.5`（(375-308)/2）。

### ⑥ 后台任务 + 后台子智能体合并单面板（手机空白紧凑化，桌面同步受益）
- 新文件 `js/bgMergedPanel.js`（`initBgMergedPanel()` :28，`main.js:83` import、initMemory 后装配）：把 `#bgagent-dropdown` 列表节点 re-parent 进 `#bg-dropdown`（「智能体」`.mh-bg-grp` 分组签下；取数/渲染/事件全走桌面既有 `renderBgDropdown`/`renderBgAgentDropdown`，零重造）；空节收签 MutationObserver；幂等。
- `css/chat.css`：`#bgagent-dropdown{display:none}`（:309 幽灵面退场，节点保留作状态载体）；`#bg-dropdown` flex column + `max-height:60vh` + `.bg-dropdown-list` flex 收缩（:363-387）。
- 键位：bg 键照旧开载体（`main.js:1866`）；bgagent 键改开载体（`main.js:3150` 附近 + `mobile/bgStatusFace.js` capture toggle：手机 bgagent 键 capture 拦截 → 载体开/收 + 幽灵押回 hidden + 160ms morph）。
- 行弹层三处关闭改载体（`main/bgAgents.js:619/655/666`）；uptime tick 读载体（:214）。
- 手机紧凑化：`css/mobileLayer.css` bg 面板 `height:auto + max-height:min(560px,…)` + 行 padding 6px。
- **偏差申报 ⑥**：令文「手机端空白紧凑化」落为「空节收签 + 面板高度随内容收口」——空面板不再撑固定高，但面板本体不因空节自动关闭（关闭语义归 Esc/外点，与桌面一致）。

### ⑦ 手机画板 bottom sheet
- 形态 `css/mobileLayer.css:1263-1304`：`.mf-cards-live #canvas-panel` 改 `left/right 8px, top calc(safe+76px), bottom calc(6px+safe), r20`，入场 `mhSheetIn 0.34s spring`（@keyframes :1299；reduced-motion 直落终态）。
- 手势 `js/mobile/canvasGestures.js`（重写）：底缘带（40px，home-indicator 同位）下滑跟手（cap 260px）→ 释放 ≥120px 或速度 >0.6px/ms = `closeCanvas()` 收起整叠（**保留状态不销毁**：页签数据模型留存可再开）；左右缘带（26px，top 64 / bottom 40）内滑 ≥64px = `closeCard()`（邻卡落位既有）；内容区/卡头带零劫持（旧「内容区横滑切卡」退役）；`touch-action:none` 只在四条缘带。
- 卡头胶囊（顶缘）`js/mobile/navController.js` `finish()` 语义改写（:339 pointer capture；纵滑段 ~403-407）：下滑 >48px = 收起整叠（⑦ 令「顶部边缘下滑=收起」，**取代批⑨纵滑换卡的下半向**，上滑换卡/左右关卡/长按引用钮照批⑨保留）。
- **偏差申报 ⑦**：批⑨「胶囊纵滑换卡」下半向按 ⑦ 令改判收起；换卡仅存上滑一向。
- **指针捕获补齐**：`canvasGestures.js:106` / `navController.js:339` 显式 `setPointerCapture`——touch 有隐式捕获，鼠标管线（桌面窄窗/自动化）不捕获则拖离元素后 move/up 事件流断。属⑦手势可用性修复，非仅测试便利。

## 手势验证记录（playwright 触摸/鼠标指针管线）
`tests/mobile-canvas-sheet.spec.mjs`（375×812 + hasTouch，15/15 × 3 遍稳定）：
- T1 形态：底距 6 / 顶 76 / 宽 359 / r20 / `mhSheetIn` / 侧缘带 2 + 底缘带 1。
- T2 底缘带下滑 170px 收起；`hasTab('doc-1')` 仍 true（状态保留）；`toggleCards()` 重开恢复。
- T2b 卡头胶囊下滑 80px 收起；24px 未越阈回弹不收。
- T3 右缘内滑 90px 关最后一张卡 → 层收；双卡时左缘内滑关**激活卡**（后开卡乙）→ 邻卡落位卡甲。
- T4 内容区滚动（pane 内滚 scroller）原生、横拖 160px 零劫持、sheet 不收、卡留存。

## spec 清单（更新 + 新增）
- 新增：`tests/bg-merged-panel.spec.mjs`（3/3：结构收编/幽灵退场/flex+max-height；双键同面板/两节渲染/Esc/aria-expanded/外点关；空节收签 + 种入即现）
- 新增：`tests/mobile-canvas-sheet.spec.mjs`（15/15 ×3）
- 新增：`tests/panels-mobile-ux-shots.mjs`（截图，非断言）
- 更新：`mobile-p5-memory`（avatar 入口→agent 分区→记忆页签；居中 + merged-tabs=2 + ap-pane 可见）、`mobile-p6-bgstatus`（T1 居中 33.5 + 紧凑 80<h≤560）、`mobile-p12-settings`（重写：无入口行 + avatar→合并面板 + 非绿键 + 回车存/零残留；bootApp stub 掉 SW 注册消 harness 噪声）、`mobile-v3` T3（合并面板形态）、`delegate-ui-twolayer` / `bgagent-dedupe` / `bgagent-project-sessions`（载体 `#bg-dropdown:not(.hidden)` + 行选择器 `.bg-dropdown-list:last-child`）、`mobile-p2/p3/p4`（居中 33.5）、`uifix-mobile6-shots`（入口行点击段改残留警告）。

## 验证结果
- `node scripts/check-js-types.mjs` → **PASS 257 = 基线**（零新增）
- `node scripts/check-circular.mjs` → 输出与干净 HEAD **逐字相同**（既有 SCC：msgQuote→reference 等，零回归；对比取证 = 临时 HEAD worktree 复跑 diff）
- 受影响 spec 族全绿：bg-merged 3/3、canvas-sheet 15/15、p5/p6/p12/v3/twolayer/project-sessions/dedupe、p2/p3/p4 13/13、uifix-glass 29/0
- 截图 18 张目检：合并面板双分区（桌面/手机）、后台合并单面板（桌面两节 + 手机紧凑居中）、canvas sheet（底部弹出 + 底缘抓手）、设置页无入口行、搜索面板居中——均符合

## 偏差与已知项（申报）
1. ①选「标签页」形态（理由见①节）。
2. ⑥空节语义 = 收签不关面板。
3. 批⑨胶囊纵滑换卡下半向被⑦令取代为收起。
4. bgagent 指示器与 bg 键：双键同开一个面板（合并语义的必然落点，桌面/手机一致）。
5. `tests/legacy-ui-retire.spec.mjs` T2/T3 失败为**先前既有**：干净 HEAD 临时 worktree 复跑同样失败（plugins 页签可见性断言与桌面壳现状脱节），不属本批范围，未改动。
6. harness 启动期 2 条 console error（`/agents/Nebula/agent.json` 404 + SW script fetch）为数据缺省所致，干净 HEAD 同样存在；相关 spec 已各自补夹具（agent.json 200 `{}` / SW register stub）。
