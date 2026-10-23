# REPORT · 删除批：对话框上方「活动」面板整链移除

批：remove-activity-panel（分支 `delegate/delegate-nebflow-6c055b42`）
日期：2026-10-23
作者令（2026-10-23 15:13 逐字）：「这个活动面板我不是让删掉吗，就是对话框上面这个」——
此前删除令未落地成批，本批补做。

## 0. 移除对象

对话框上方「活动」实时活动流面板（对话层活性批 2026-10-22 落地）：标题「活动 · N 条」
+ 折叠钮，展开后为 delegate 更新/工具调用实时行（如「Grep(…)」「Read(…)」带 HH:MM
时间戳），挂载点 `#input-area` 顶部、`#queue-bar` 之上。

## 1. 整链移除清单（UI + 接线 + 账本 + CSS + locale）

| 面 | 件 | 动作 |
|---|---|---|
| UI 组件 | `src/main/resources/web/js/main/turnActivity.js` | **整文件删除**（渲染/聚合/账本/queuebar-refresh 监听全在此） |
| 挂载点 | `src/main/resources/web/index.html` | 删 `<div id="activity-strip">` + 批注释（留退役记录注释，不留挂载点） |
| 事件接线 | `src/main/resources/web/js/main.js` | 删 import + 五个 `noteActivity` 消费点（agentStart 派发 / agentToolStart 工具行 / delegateWaiting 待合并 / agentDone 收工 / backgroundTaskUpdate 状态）+ done 终态 `clearActivity` 收口 |
| 数据账本 | `src/main/resources/web/js/state.js` | 删 `sessionActivity: {}` 槽 + 注释 |
| 专用 CSS | `src/main/resources/web/css/liveness.css` | 删①段 `#activity-strip`/`.activity-strip-*` 全部规则；②段 `#conn-banner`（断连横幅）**保留**，文件头改写为存续面说明 |
| locale 键 | `js/locales/zh-CN.js` + `en.js` | 删 `activity.title` / `activity.count` 两键 + 批注释（留退役记录） |

设置开关项：无 —— 该面板从未挂 featureFlags/settings 开关（考古核实），无此项可删。
保留不动：ws.js `TERMINAL_MSG_TYPES` 路由修复（delegateWaiting/heldReceipts 补入入口
过滤集）——服务 Sub-Agents 行 waiting 标与 heldReceipts 胶囊，非面板专属。

## 2. 邻接零波及

聊天输入框（`#input`/`#input-bar`）、排队条（`#queue-bar`）、slash-dropdown、mic-orb、
右下角按钮族、断连横幅（`#conn-banner`）零触碰。spec N 组钉邻接元素 boot 后全数在场。

## 3. spec 处置 + 防复活钉

- `tests/turn-activity-stream.spec.mjs` **重写为防复活钉**（原 22 断言钉「面板存在」
  全数作废）：
  - R 组静态源钉：模块文件已删 / index.html 无挂载 / css 无规则 / locale 无键 /
    全 `web/js` 零引用（noteActivity|clearActivity|renderActivityStrip|sessionActivity|
    activity-strip 任一命中即红）；
  - A 组运行时钉：真实 boot（静态路由+假后端+真实 ws.js 分发）后 `#activity-strip`
    不在 DOM；**注入五类活动帧后仍不存在**（防半复活）；`state.sessionActivity`
    槽不得回来；
  - N 组零波及钉：`#input-area`/`#queue-bar`/`#slash-dropdown`/`#input`/`#input-bar`/
    `#conn-banner` 在场；
  - P 组 console 干净钉（豁免既有 harness 面 `/agents/*/agent.json` 404 一对已知
    报错；`/js/` 模块图 404 盘点兜住悬空 import）。
  - 结果：**21/21 PASS**（R1-R5、A0-A2、N1-N7、P1-P2）。
- `tests/turn-liveness-shots.mjs`：活动条三腿截图撤销（面板已不存在），保留断连横幅腿。
- `docs/design/turn-liveness-spec.zh-CN.md`：场景一改写为退役记录（含作者令逐字），
  保留存续遗产 §1.1 路由修复与场景二/三；非目标节新增「活动面板不再回来」条款。

## 4. 全库孤儿引用 grep（要求④登记）

全库 grep `turnActivity|noteActivity|renderActivityStrip|clearActivity|sessionActivity|
activity-strip|activityStrip|'activity.title'|'activity.count'`（js/mjs/cjs/html/css/
scala/md，排除 .git/node_modules/evidence/reports 历史证据面，`activityBar` 左侧竖条
非本面板不涉）：仅余三类**有意保留**命中——①防复活 spec 本体（钉子必须点名钉谁）；
②退役记录注释（main.js 头 / liveness.css 头 / design doc 退役节 / locale 注释）；
③**零活代码引用**（无 import、无 DOM 挂载、无 CSS 规则、无 locale 键、无 Scala 面）。

## 5. 验证

| 项 | 结果 |
|---|---|
| `node tests/turn-activity-stream.spec.mjs`（防复活钉） | 21/21 PASS |
| `node tests/conn-banner.spec.mjs`（存续面回归） | ALL PASS |
| `node tests/queuebar-informative.spec.mjs`（邻接排队条） | ALL PASS |
| `node tests/f1-send-queue.spec.mjs`（邻接发送腿） | ALL PASS（exit 0） |
| `node tests/send-bubble-independence.spec.mjs`（邻接气泡腿） | ALL PASS（exit 0） |
| `node tests/chat-autoscroll-follow.spec.mjs`（对话面回归） | exit 0 |
| `node scripts/check-js-types.mjs`（checkJs gate） | 257 = 冻结基线 PASS |
| `node scripts/build-web.mjs`（bundle 图 + orphan guard） | 179 sources，orphans: 0 |
| 截图证据（亮/暗） | 注入五类活动帧后输入区特写：输入框上方零面板 |

截图：`evidence/20261023_152732_remove-activity-panel__delegate-nebflow-6c055b42/
after-light-no-panel.png` / `after-dark-no-panel.png`（busy 回合 + 活动帧在飞，
面板不出现；亮暗各一）。

## 6. 红线自核

最小改动（删 1 文件 + 6 处定点清理 + spec/doc 换代）；跟随仓库风格（退役记录注释
+ 防复活钉沿用本库 spec 惯例）；未重启引擎；全部作业在本 worktree 分支，未 merge。

## 7. 开放项

- 无功能开放项。历史 evidence/reports 内的旧批次记录（含活动条截图）按证据不改写。
- 设计 doc §2 插话确认节已注记：排队面形态以 2026-10-23 排队回退批为准（该批已落）。
