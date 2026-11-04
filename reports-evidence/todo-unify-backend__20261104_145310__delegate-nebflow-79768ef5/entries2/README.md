# 入口收敛验收证据（todo-unify-v2 / 节点验：全套机械验收）

分支 `delegate/delegate-nebflow-79768ef5` · 工作区
`/Users/kaiyu/Claude code/Stand/.nebflow/worktrees/delegate-nebflow-79768ef5`

## 0. 结论

| 项 | 结果 |
|---|---|
| 上游 persist2 首行 | `VERDICT: PASS`（放行撤入口） |
| merge main | 无冲突（ort），HEAD `712242e85`（parents `4537d8779` + main `0fffa26b5`） |
| 基线（merge 后、撤入口前） | frontend-gates EXIT=0 · check-js-types 0 errors（`00a` / `00b`） |
| 两个入口撤除 | 完成，**零物理删除**（全部注释退役 + 批号 + 理由） |
| 承载信息 | 全部并入顶带待办胶囊（计数节点 `.tfl-cap-face` 同源渲染于胶囊与展开面板头） |
| `#todo-page` 处置 | **(a) 整面退役**（DOM 保留、不可达）；理由见 §3 |
| 新 spec | `tests/todo-entry-collapse.spec.mjs` 改前 **红 2/2 FAIL** → 改后 **绿 2/2 passed** |
| 相关既有 spec | 全绿（`50_final_specs_and_shots.log`：8 passed / EXIT=0） |
| 五道门禁 | 全部 EXIT=0（`30_gates.log`：design-spec-sync / component-contract / circular / process-artifacts / make frontend-gates） |
| 预存红 | 3 条（非本批引入，已在改前树实测，见 §7）|

## 1. 撤了哪些入口（file:line）

### 1.1 消息列表顶部「待办事项」入口行（桌面 + 手机同现）

| 文件 | 位置 | 处理 |
|---|---|---|
| `src/main/resources/web/js/messages.js` | `:44-50` | `import { buildTodoEntryRow }` **注释退役**（含批号与理由） |
| `src/main/resources/web/js/messages.js` | `:641-646` | `box.appendChild(buildTodoEntryRow())` 注释退役 ⇒ 消息列表 = 纯会话名册 |
| `src/main/resources/web/js/scheduled-task.js` | `:244` | `updateBadge()` 内 `refreshTodoEntry()` 调用注释退役 |
| `src/main/resources/web/js/scheduled-task.js` | `:1521` (`paintTodoEntry`) | 函数体加 ⛔ 退役注记（无调用方；信息映射写明） |
| `src/main/resources/web/js/mobile/navController.js` | `:2815-2818` | `nf:mobile-todo-entry` 监听注释退役 |
| `src/main/resources/web/index.html` | `:641-645` | activity bar 待办注释补 ⛔ 批注（唯一入口 = 胶囊） |
| `src/main/resources/web/css/todo-page.css` | `:5-13` | 文件头补整面退役注记（`.fm-todo-entry` 图标规则等无宿主） |

### 1.2 手机左上角待办（任务悬浮圆钮 + 任务面板 + 两个导航页）

| 文件 | 位置 | 处理 |
|---|---|---|
| `src/main/resources/web/js/mobile/chatChrome.js` | `:44-51` | import `taskBadgeCount` / `todoStore` 注释撤除 |
| `src/main/resources/web/js/mobile/chatChrome.js` | `:105-115` | `syncTaskBadge()` 整函数注释留档（无调用方） |
| `src/main/resources/web/js/mobile/chatChrome.js` | `:185-245` | taskBtn 建层块注释留档；`fabLayer.append(taskBtn, searchBtn)` → `appendChild(searchBtn)`；徽标三腿（首刷 / 镜像+会话监听 / `todoStore.subscribe`）注释退役，locale 腿只留搜索钮标题 |
| `src/main/resources/web/js/mobile/chatChrome.js` | `:11-14` | 文件头补退役注记（fab 层现役唯一键 = search） |
| `src/main/resources/web/js/mobile/taskPanel.js` | `:3-17` | 文件头 ⛔ 整模块退役块（已无 import 方、信息去向、代码零删除） |
| `src/main/resources/web/js/mobile/taskPanel.js` | `:67-69` | `ensurePanel()` 退役注释 |
| `src/main/resources/web/js/mobile/navController.js` | `:59-63` | import（`ensureTaskPanel` 等）注释撤除 |
| `src/main/resources/web/js/mobile/navController.js` | `:122-135` | `PAGES` 里 `{id:'tasks'}` 与 `{id:'todo'}` 注释退役（常数 20 → 18） |
| `src/main/resources/web/js/mobile/navController.js` | `:581-585` | `overlayFaceByPage` tasks/todo 两行注释 |
| `src/main/resources/web/js/mobile/navController.js` | `:668-677` / `:696-704` | `ensurePanelContent` 的 `case 'todo'` / `case 'tasks'` 注释 |
| `src/main/resources/web/js/mobile/navController.js` | `:2326-2330` / `:2340-2344` | `closePageFace` 两个 case 注释 |
| `src/main/resources/web/js/mobile/navController.js` | `:2496-2498` | `CLOSE_WATCH` 的 todo 行注释 |
| `src/main/resources/web/css/mobileLayer.css` | `:502-507` | FAB 块注记（`[data-chrome='tasks']` / `.mh-chrome-badge` 宿主已不存在；与 search 键定位零耦合，两键各自 absolute） |

## 2. 承载信息去哪了（逐项映射 + 断言）

| 原信息 | 原宿主 | 现宿主 | 断言（新 spec） |
|---|---|---|---|
| 待销账待办数徽标 | 入口行徽标 / 任务圆钮徽标 | 胶囊计数节点（`.tfl-cap-faces` 内 `.tfl-capsule-count`；展开时**同一节点**进面板头 `.tfl-card-header`） | 桌面「同一节点」断言：`#task-list .tfl-capsule-count` 与展开面板头计数为同一 DOM 节点（`===`）；读数「4 待办」 |
| 逾期数 | 入口行红点 | 胶囊逾期翻面 `.tfl-capsule-overdue` + `.is-overdue` 红点 `rgb(255,59,48)` | `mobile/desktop` 均断言「1 逾期」+ 红点色值 |
| 未完成计数（含计数翻牌） | `#todo-page-count` | 胶囊计数 + `.tfl-count-flip` 翻牌类簇 | 完成一条后 4→3 且出现 flip 类簇（`todo-split` ④ + 新 spec ④） |
| 重要度筛选四签（无/低/中/高） | 待办页筛选栏 | 展开面板 `.tfl-pri-chip` | 四签逐字「无/低/中/高」+ `aria-pressed` 往返 |
| 分组可读性 | 待办页分组固定序页签 | 分组 chips `.tfl-chip-tag`（project 投影；All 视图行内徽标） | chips 含 `DOSTEL`/`stand` + 行内徽标 + 单选后行集 2 |
| 行集 / 逾期行红字 / 完成钮 | `.reminder-row` / `.reminder-circle` | `.tfl-row` / `.tfl-complete` | 行集 5（跨会话 4 人待办 + 镜像行不入）|
| 新建待办 | `#todo-create-btn` | 面板右下 `.tfl-fab-add`（内联创建） | `createScheduledTask` 帧带 `kind:'todo'`（`todo-split` ⑤） |

## 3. `#todo-page` 处置 = (a) 整面退役（DOM 保留、不可达）

写在 `src/main/resources/web/index.html:830-841` 与
`src/main/resources/web/js/scheduled-task.js` 的 `openTodoPage` 注记，理由三点：

1. **信息已完整迁移**：顶带胶囊 + 展开面板（`js/todoFace.js`，真源 `todoStore` ↔
   `ScheduledTaskStore`）已承载全部读数与写口，两套面并存必然状态分叉。
2. **入口天然归零**：唯一入口（消息列表行 + 手机 `pushPage('todo')`）本批撤除，
   页面已无开合路径 ⇒ 「可达但功能半残」不成立（选 (b) 需要再造一套全屏形态，
   与本批「入口收敛」的口径相反，且会与胶囊面板形成第二写面）。
3. **可逆**：DOM / 渲染核 / CSS 零删除，回切只需恢复入口那一行。

## 4. 选择器清单（本批验收面）

- 退役面（必须零命中）：`.fm-todo-entry`、`[data-chrome="tasks"]`、`.mh-chrome-badge`、
  `#mobile-task-panel`、`.mobile-page[data-page="tasks"]`、`.mobile-page[data-page="todo"]`、
  `#mobile-fab-layer` 内除 `[data-chrome="search"]` 外任何键。
- 现役面（唯一入口）：`#top-overlays > #task-list`、`.tfl-capsule`、`.tfl-cap-faces`、
  `.tfl-capsule-count`、`.tfl-capsule-overdue`、`.tfl-card[data-state]`、
  `.tfl-row`、`.tfl-complete`、`.tfl-chip-tag`、`.tfl-pri-chip`、`.tfl-fab-add`、
  `.tfl-collapse-btn`、`.tfl-count-flip`。

## 5. 测试读数

### 5.1 新 spec `tests/todo-entry-collapse.spec.mjs`（零后端 / WS 夹具 / 端口回避 6138）

改前红做法：`git worktree add /tmp/todo-collapse-pre 712242e85`（= merge 后未改口
前的树）+ 软链 `node_modules` + 拷入新 spec，其余文件为改前版本。

| 阶段 | 日志 | 读数 |
|---|---|---|
| 改前红 | `10_new_spec_before_red.log` | **2 failed**（desktop 因 `.fm-todo-entry` 存在；mobile 因 `[data-chrome="tasks"]` 存在） |
| 改后绿 | `50_final_specs_and_shots.log` | `todo-entry-collapse` + `todo-split` 合计 **8 passed / EXIT=0** |

测试体：
- `tests/todo-entry-collapse.spec.mjs:149` 桌面 1280×800（断言 ①③④）
- `tests/todo-entry-collapse.spec.mjs:262` 手机 375×812（断言 ②③④⑤）

### 5.2 既有 spec 改动（不删测试，逐处 file:line）

| 文件 | hunk（改后行） | 改动 |
|---|---|---|
| `tests/todo-split.spec.mjs` | 全文（:184 / :219 / :246 / :272 / :300 / :348） | 承载面从「入口行 + `#todo-page`」改写到「胶囊 + 展开面板」；①② 增加退役断言；夹具 `completedIds` Set + `completeTodo` 回 `scheduledTaskCompleted`（收口「完成 ⇒ 刷新后仍已完成」） |
| `tests/todo-page-shots.mjs` | `:2-7` `:99-131` | 不再拍 `#todo-page`，改拍 胶囊 / 展开态 / 消息页零入口行 |
| `tests/todo-face-shots.mjs` | `:14-21` `:133-139` `:315-357` | 手机腿改拍胶囊；退役断言（tasks FAB / 任务面板零命中） |
| `tests/mobile-panel-title.spec.mjs` | `:201-211`（openTodo 与 panelLeg todo 腿退役）`:560-563` `:616-625` | 页壳表下限 19 → **17**（PAGES 20 → 18，两页退役） |
| `tests/mobile-v3.spec.mjs` | `:236-275`（T2 改写）`:378-381`（T5 双钮→单键）`:404-408`（T6 `#task-list` toBeHidden → `.task-chain-block` count 0） | T2 变成退役护栏；T6 校正为胶囊槽现役语义 |
| `tests/mobile-dock.spec.mjs` | `:419-435` | D7 → 退役护栏（tasks 键 / tasks 页 / 面板全零 + `pushPage` no-op） |
| `tests/mobile-touch-target.spec.mjs` | `:350-356` `:372-373` | tasks/todo `measurePanel` 腿退役 |
| `tests/cognition-phase2.spec.mjs` | `:192-202` `:211-219` `:249-270` | R2b/R3c/R6f 改读 `.tfl-capsule-count`；R3c 前补 `scheduledTaskCompleted` 回执 |
| `tests/conversation-heads-unify.spec.mjs` | `:394-395` | taskKey 注释（恒 null 的原因） |
| `tests/desktop-avatarfix-shots.mjs` | `:124-131` `:163-172` | 入口行参考件腿退役 |
| `tests/liquid-glass-shots.mjs` | `:111-121` `:165-166` | tasks 圆钮 → search 圆钮特写 |
| `tests/liquid-glass-v3-shots.mjs` | `:155-157` `:190` | F1 材质门改钉 search 键 |
| `tests/mobile-interaction-standard-shots.mjs` | `:170-186` | PANELS 表去掉 tasks/todo 页 |
| `tests/mobile-muse-head-restore-shots.mjs` | `:282-287` `:320-324` | 口径改「头像 ↔ 现役唯一键 search」 |
| `tests/mobile-muse-head-same-row-shots.mjs` | `:179-184` `:457-458` | 同上 |
| `tests/mobile-title-morph.spec.mjs` | `:687-689` | todo 页面腿注释退役 |
| `tests/mobile-two-stage-title-shots.mjs` | `:89-95` `:145-151` | todo 页面腿注释退役 |
| `tests/mobile-avatar-chip-title-gap.spec.mjs` | `:363-364` `:384` | todo 页面腿注释退役 |
| `tests/mobile-taskpanel-active-only-shots.mjs` | `:104-119` | 手机腿注释退役（面板已无宿主） |

## 6. 门禁读数（`30_gates.log` 原文摘录）

```
EXIT_DESIGN_SPEC_SYNC=0        PASS —— spec 表与令牌源逐条同值（56 行 / 37 条对位）
EXIT_COMPONENT_CONTRACT=0      PASS 五条组件契约零新增（TOTAL=937；R1/R4 = 0）
EXIT_CIRCULAR=0                circular-deps PASS: 236 files, acyclic
EXIT_PROCESS_ARTIFACTS=0       ✓ git ls-files evidence/ reports/ docs/ = 0 违规
EXIT_FRONTEND_GATES=0          make frontend-gates（`02_frontend_gates_after_retire.log`）
check-js-types               0 errors（`01_js_types_after_retire.log`）
```

`tests/type-baseline.json` 保持 `{"total":0,"files":{}}`（未改任何 baseline）。

## 7. 预存红（非本批引入，改前树实测）

在同一 merge 基点树（`712242e85`，未含本批改动）跑同组 spec
（`/tmp/prechange_3tests.log`：4 failed / 25 passed）：

1. `tests/conversation-heads-unify.spec.mjs:359` ① 身份头 `opacity` 读数 0（期望 1）。
2. `tests/mobile-dock.spec.mjs:895` D18 `titlePosition` 实测 `sticky`（期望 `static`）。
3. `tests/mobile-v3.spec.mjs:405` T6 `#task-list` `toBeHidden`（胶囊面交互批后该槽已现役）。
4. `tests/mobile-v3.spec.mjs:231` T2 —— 改前树上是**旧 T2**（本批已换代为退役护栏）。

5. `tests/todo-face-shots.mjs` 桌面腿 `swipe-down deletes tag`（4 引擎全红）+
   紧随其后的 `.tfl-filter-toggle` 30s click 超时（`#modal-overlay` 拦截）——
   本批未改 `js/todoFace.js` / `css/todo-face.css`（`git diff 712242e85` 该两文件
   零改动），且该腿代码与基点树**逐字节相同**（`diff` 校验 = identical）；
   同脚本在基点工作树 `/tmp/tfc-base`（detached `712242e85`）复跑
   `41_face_shots_base_tree.log`：**96 PASS / 8 FAIL**，FAIL 集合与本树
   `50_final_specs_and_shots.log`（**108 PASS / 8 FAIL**，多出的 12 PASS = 手机腿
   新增的退役/读数断言）**逐条相同** ⇒ 预存，非本批引入。

改后同组只剩 1/2 两条（`40_respec_run.log`：1 failed / 33 passed，唯一红 = D18
sticky），T6 已按现役语义校正并转绿，T2 已换代为退役护栏并转绿。**未改任何
baseline 去凑绿。**

`tests/todo-face-shots.mjs` 的**手机腿 4 引擎 × 9 断言全 PASS**（含本批两条退役
断言 `mobile tasks FAB retired` / `mobile task panel retired` 与胶囊读数
`mobile capsule count` / `mobile capsule overdue face`），见
`50_final_specs_and_shots.log`；其桌面腿的预存红见上条第 5 项。

## 8. 让位链 / 双端读数（新 spec 实测）

| 端 | 读数 |
|---|---|
| 桌面 1280×800 | 胶囊挂 `#top-overlays`；`#chat` `padding-top` 让位 150px ≥ 渐隐带底 134px；计数「4 待办」/「1 逾期」；红点 `rgb(255,59,48)`；face `aria-hidden` 翻转；展开 `.tfl-card[data-state=open]` |
| 手机 375×812 | 胶囊 y≈102 居中；`elementFromPoint` 命中自身；身份头 bottom 68 ≤ 胶囊 top 102；`#chat` pad 150 ≥ band 134；面板底 ≤ 输入区顶；`#mobile-fab-layer` 只剩 search；消息页零入口行 |

截图（与 recon 改前同机位）：`shots/desktop-1280-capsule.png`、
`shots/desktop-1280-capsule-expanded.png`、`shots/mobile-375-capsule.png`、
`shots/mobile-375-capsule-expanded.png`、`shots/mobile-375-messages-no-entry.png`。

## 9. 日志索引

| 文件 | 内容 |
|---|---|
| `00a_baseline_frontend_gates.log` | merge 后、撤入口前 frontend-gates 基线（EXIT=0） |
| `00b_baseline_js_types.log` | 同上，check-js-types 0 errors |
| `01_js_types_after_retire.log` / `02_frontend_gates_after_retire.log` | 撤除后复跑 |
| `10_new_spec_before_red.log` | 改前红（worktree `712242e85`） |
| `11` / `12` / `13` / `14` | 新 spec 与 todo-split 迭代（含失败过程，保留不删） |
| `20_pw_suite.log` | merge 后整组相关 spec（33 passed / 4 failed，其中 3 预存 + 1 待修） |
| `21_node_group2.log` | cognition-phase2 ALL PASS / todo-page-shots SHOTS OK |
| `22_node_group3.log` | mobile-avatar-chip-title-gap ALL PASS |
| `23_node_group4.log` | liquid-glass-v3 28/44 断言（2 条预存红：ask-send bg / canvas toggle F1） |
| `30_gates.log` | 五道门禁原文读数 |
| `40_respec_run.log` | 修订后再跑（1 failed = D18 预存 / 33 passed） |
| `50_final_specs_and_shots.log` | 新 spec + todo-split 8 passed / EXIT=0 + 截图脚本 |
