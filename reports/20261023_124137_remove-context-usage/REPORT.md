# REPORT · 删除批：桌面端「上下文用量显示」整链移除

批：remove-context-usage（分支 `delegate/delegate-nebflow-8b4f04e9`）
日期：2026-10-23
作者令（2026-10-23 20:4x 微信，逐字）：「把桌面端的上下文用量显示也去掉」——
「也」= 与同日「活动面板」移除同族口径：整链移除，不留 UI 残迹。

## 0. 移除对象（考古定位）

桌面端上下文用量显示 = **ctxring 环族**，三处渲染点 + 一个交互模块：

1. **主窗头部用量环** `#header-model-info`（index.html 挂载，`main.js`
   `updateHeaderModelInfo()` 渲染）：28px SVG 环 + 用量 % 读数 + 压缩阈值刻线 +
   tooltip（tokens/模型/阈值）。数据源 = `usageUpdate`/`done` WS 帧 →
   `state.sessionModelInfo`。ctxring 批（2026-09-15 方案 A 起；2026-09-17 恢复
   「环上拖动」）。
2. **环上拖动模块** `js/ctxthresh.js`（整文件）：压缩阈值拖改 = **唯一入口是环本身**
   （文件头自证「入口 = 环本身，唯一」）。环删则它成隐形 slider + boot 补拉
   `getCompactThreshold` + 注入 CSS + aria 残迹 ⇒ 整文件随批删。
3. **弹窗头部同款用量环 ×2**：`flowAgentPopup.js`（`#flow-agent-ctx`）与
   `bgAgentPopup.js`（`#bgagent-ctx`）——与主环同 SVG 结构/tip 口径的 agent
   上下文用量环（含挂载点、`updatePopupCtxRing`、`fmtTokens`、专用样式段）。

## 1. 整链移除清单（UI + 接线 + 专用 CSS + locale + 陈旧钉脚本）

| 面 | 件 | 动作 |
|---|---|---|
| 挂载点 | `web/index.html` | 删 `<span id="header-model-info">`（留退役记录注释，不留挂载点） |
| 渲染器 | `web/js/main.js` | 删 `updateHeaderModelInfo()`/`formatTokens()` + `state.updateHeaderModelInfo` 导出 + 4 处调用（usageUpdate/done/compactComplete/modelChanged）+ `ctxthresh.js` import + 头注释换代 |
| 交互模块 | `web/js/ctxthresh.js` | **整文件删除**（环上拖动/键盘/WS 收发/注入 CSS 全在此） |
| 弹窗环 | `web/js/flowAgentPopup.js` | 删挂载 span + `updatePopupCtxRing` + `fmtTokens` + `.flow-agent-ctx` 样式段 + `openStepPopup` 内渲染调用；`usageUpdate`/`done` 订阅**保留**（只喂模型徽章） |
| 弹窗环 | `web/js/bgAgentPopup.js` | 同上（`#bgagent-ctx` 腿） |
| dom 槽 | `web/js/main.js`（state.dom + initChatView）+ 两 popup 视图桩 | 删 `headerModelInfoEl` 槽（无读者，纯死槽） |
| 接线 | `web/js/sidebar.js` | 删 `initHeaderModelInfo()` 导出 + import + 2 处调用 + 会话切换 guard-call |
| 状态槽 | `web/js/state.js` | 删 `COMPACT_THRESHOLD`（唯一读者 = 环 tooltip）+ `updateHeaderModelInfo: null` 槽；**`sessionModelInfo` 保留** |
| 自适应链 | `web/js/main.js` 头部碰撞链 + `scripts/e2e-header-collision.cjs` | `header-model-info` 移出 CHAIN（两处）+ LEAVES + ro.observe 表，注释同步（两侧严格同步口径） |
| 专用 CSS | `web/css/chat.css` | 删 172-225 段：`#header #header-model-info`/`.ctx-model-label`/`.ctx-ring-wrap`/`.ctx-ring-svg`/`.ctx-ring-pct`（原 `.ctx-model-label` 本就是孤儿样式） |
| locale 键 | `web/js/locales/zh-CN.js` + `en.js` | 删 `ctxthresh.title`（环 aria-label）+ 批注释换代为退役记录 |

## 2. 管道边界（任务③：只摘显示面）

- **保留**：`usageUpdate`/`done` → `state.sessionModelInfo`（localStorage
  `model_info`）——弹窗**模型徽章**（`renderModelBadge` 读 `.model`）仍消费；
  `compactComplete` 的 `inputTokens` 回写保留。spec A2 钉管道存活（注帧后
  `sessionModelInfo[SID]` 照常记录）。
- **保留（后端零改动）**：`getCompactThreshold`/`setCompactThreshold` WS ops、
  `compactThresholdInfo`/`compactThresholdError` 回帧、`CompactThresholdOverride`
  钳制与 `_index.json` 持久化——压缩阈值是**行为特性**非显示面；前端唯一消费方
  （拖动模块）已删，spec A5 钉 boot 零外发这两条腿。
- **非本批对象**：Usage Dashboard（bar-chart-3 仪表盘，spec §7.5，`usage.*` 键族、
  `usageDashboard.js`、`usage.css`）= 聚合 token 统计面板，非「上下文用量显示」，
  零触碰。

## 3. spec 处置 + 防复活钉（任务④）

- **改删**（钉「它存在」的断言随载体退役）：
  - `tests/tmp-usage-e2e.mjs`、`tests/tmp-usage-display-verify.mjs` **删除**
    （tmp 钉脚本，断言环渲染存在，全数作废）；
  - `scripts/e2e-ctxthresh.cjs` **删除**（环上拖动批 after/baseline 硬断言脚本）；
  - `scripts/e2e-header-collision.cjs` CHAIN/LEAVES 同步（见 §1）。
- **新增防复活钉** `tests/remove-context-usage.spec.mjs`，**23/23 PASS**（exit 0）：
  - R 组静态钉：ctxthresh.js 文件已删 / index.html 无挂载 / css 全目录无环族规则 /
    locale 双侧无键 / 全 `web/js` 零活引用（`ctxthresh.js|header-model-info|
    headerModelInfoEl|ctx-ring|updateHeaderModelInfo|initHeaderModelInfo|
    updatePopupCtxRing|flow-agent-ctx|bgagent-ctx|ringDragPreviewPct|
    COMPACT_THRESHOLD` 任一命中即红；退役记录注释已按钉面措辞避让）；
  - A 组运行时钉：真实 boot（静态路由 + 假后端 + 真实 ws.js 分发）后环宿主/环
    DOM/注入样式/slider 角色全不在；注入 usageUpdate + done 用量帧 ⇒ 环仍不存在
    （防半复活）；管道照常写 state；渲染钩子槽不回来；boot 零
    `get/setCompactThreshold` 出站；
  - N 组零波及钉：`#sidebar-toggle`/`.header-left`/`#session-name`/`#search-btn`/
    `#memory-btn`/`#bg-indicator` 原样在场；
  - P 组：console 零新增错误 + `/js/` 模块图零 404（悬空 import 即红）。

## 4. 手机端确认（任务⑤）

手机端**本就没有**该显示：`mobileLayer.css` 手机层把桌面头部簇整体退场
（`body.mobile-layer #header .header-left { display:none !important }`，环所在簇），
中簇/液态下拉列均无环。本批删的是 DOM 源头，对手机层零增量；未扩散改手机面。

## 5. 全库孤儿引用盘点

`grep ctx-ring|header-model-info|headerModelInfoEl|ctxthresh.js|updateHeaderModelInfo|
initHeaderModelInfo|updatePopupCtxRing|COMPACT_THRESHOLD`（web js/css/html）：
仅余退役记录注释（index.html / chat.css / main.js ×3 / sidebar.js / 两 popup /
locale 注释）+ spec 本体钉名。`tests/devnamefix-window-header.spec.mjs`、
`tests/unifyfix-eight-points.spec.mjs`、`scripts/verify-preset-empty-state.cjs`
中 `ctxthresh` 字样为历史先例引注（注释），非活代码，未动。

## 6. 验证

| 项 | 结果 |
|---|---|
| `node tests/remove-context-usage.spec.mjs`（防复活钉） | **23/23 PASS**（exit 0） |
| `node tests/turn-activity-stream.spec.mjs`（同族防复活回归） | PASS（exit 0） |
| `node tests/devnamefix-window-header.spec.mjs`（头部护栏 28 条） | 全绿（exit 0） |
| `node tests/agent-identity-sync.spec.mjs`（弹窗模型徽章邻接） | ALL PASS（exit 0） |
| `node scripts/check-js-types.mjs`（checkJs gate） | 257 = 冻结基线 PASS |
| `node scripts/build-web.mjs`（bundle 图 + orphan guard） | 180 sources，orphans: 0 |
| `node scripts/e2e-header-collision.cjs` | O1/O2/O5 79/79 不变；O3/O4 与 **未改动 HEAD 基线逐位一致**（hardFail=43 = 环境既有红，临时 worktree 实测 HEAD 同值，非本批引入，零回归增量） |
| `tests/bg-merged-panel.spec.mjs` | 未跑通 = @playwright/test runner 装载错（import 期 harness 环境错，未触应用代码；与本批无关） |

## 7. 截图证据（亮暗）

`evidence/20261023_124137_remove-context-usage__delegate-nebflow-8b4f04e9/
after-light-no-ring.png` / `after-dark-no-ring.png` —— 真实 boot + usageUpdate
用量帧在飞（82000/128000 tokens, 64%）⇒ 头部左簇零环、输入区零残迹（亮暗各一；
取证脚本 `tests/remove-context-usage-shots.mjs`）。

## 8. 红线自核

最小改动（删 4 文件 + 10 文件定点清理 + 新增 1 钉 1 取证脚本）；跟随仓库风格
（退役记录注释 + 防复活钉沿用 turn-activity-stream 先例）；未重启引擎；
未动后端 Scala；全部作业在本 worktree 分支，未 merge。

## 9. 开放项

- `e2e-header-collision` 的 O3/O4 环境性红与 `bg-merged-panel` 的 runner 装载错
  为**既有面**（HEAD 同状），不在本批处置范围，留待独立批。
