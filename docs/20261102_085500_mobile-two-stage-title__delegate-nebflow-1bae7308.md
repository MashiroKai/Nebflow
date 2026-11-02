---
type: batch
status: done
related: "[[20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31]]"
---

# 手机端面板头「两段式标题」批（2026-11-02）

> 作者令（07:12，两张实拍 `IMG_1678`「项目」/ `IMG_1677`「设置」）：手机端功能面板标题行为改造——① **静止态水平居中**（不再左对齐，返回钮原位不重叠）；② **上滑吸附**：内容滚过标题位置后标题收成一颗**毛玻璃胶囊**，横向居中、窄条、导航栏级字号，**悬浮于内容之上**（不把内容压下去，内容从胶囊下方流过），顶区不再占固定高度、屏占比还给内容；③ 胶囊材质走既有玻璃样式包单源，深浅双主题成立、文字对比度足，返回按钮吸附后仍在胶囊左侧可点；④ 向下滚回顶部平滑展开回大标题（随滚动手感，不跳变），reduced-motion 无动画；⑤ **全部手机功能面板统一**（[[20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31|B 批]]标题批覆盖的 19 个页壳），桌面端零触碰。

## 1. 契约（数值钉死面）

几何 token 全落在 `css/mobileLayer.css` 手机层 token 单源块「两段式标题几何」小节（`body.mobile-layer` 作用域，桌面永不命中）：

| token | 值 | 含义 / 取值理由 |
|---|---|---|
| `--mtitle-h` | 52px | 静止态大标题盒高 = 上内距 10 + 行盒 36 + 下内距 6（行高 36 = 1.286×28） |
| `--mtitle-pad-t` | 10px | 盒内上留白（与下 6 共同撑 52；上下不对称使文字行盒中心落在 40） |
| `--mtitle-pad-b` | 6px | 盒内下留白 |
| `--mtitle-lead` | 12px | 页体顶留白 = 标题盒顶距（盒顶 12 / 盒底 64 = 返回钮底缘） |
| `--mtitle-inset` | 72px | 大标题左右内缩 = 返回钮左 16 + 钮宽 48 + 缝 8 ⇒ 居中标题与返回钮**横向零重叠**；胶囊同档取同一内缩 |
| `--mtitle-start` | 8px | 收起进度起点（scrollTop px） |
| `--mtitle-snap` | 56px | 吸附阈值（进度 = 1 且 `.rolled` 成立的 scrollTop；沿用 B 批 56） |
| `--mcap-h` | 32px | 吸附胶囊高（导航栏级窄条） |
| `--mcap-pad` | 16px | 胶囊左右内距 |
| `--mcap-radius` | 999px | 胶囊全圆角 |
| `--mcap-top` | 40px | 胶囊垂直中心 = 返回钮中心（16 + 48/2） |
| `--mtitle-p` | 0→1 | 收起进度（JS 写，三位小数） |

**进度公式**（`js/mobile/navController.js` `applyRoll`）：`p = clamp((scrollTop − 8) / (56 − 8), 0, 1)`。

**形态量全部由 p 派生**（无 transition，逐帧跟手）：

- 大标题：`opacity = 1 − p`、`transform: translateY(−8p px)`、pane 页额外 `height/padding-t/padding-b × (1 − p)`（收高）；
- 胶囊：`opacity = p`，几何**恒定**（高 32 / 中心 40 / 内缩 72）——刻意不做缩放插值：胶囊一旦缩放，吸附中的 32px/15px 读数随进度漂移，几何契约失去单一真值；两段的「生长」读作由透明度插值 + 大标题收高承担。

**滚型两分**（`primaryScroller` / `.mpt-pane-scroll`）：主滚轴 = 事件目标到 section 之间**最外层**有余量的纵向滚动器。

- 页体自身是主滚轴（较矮面）：大标题由滚动自然送出视口，**不收高**（收高会内容上跳）；
- 主滚轴在收编面内部（pane/内滚面：项目 `team-scroll`、设置 `#settings-content`、待办 `todo-page-body` 等）：大标题不随滚走 ⇒ 按进度**收高**把整条顶带还给 `flex:1` 的收编面，吸附态顶区零占位。

**材质单源**：胶囊 = `--cv-bg / --cv-blur / --cv-saturate / --cv-border / --cv-shadow`（`css/sapphire.css` L211-258 定义、glass.css 组装，双主题自随），本文件零新色值；旧 WebView/X5 与 `html.no-backdrop` 降级摘掉采样层、落 `--cv-bg` 实底（mobileLayer.css 文件尾两块，因 mobileLayer.css 在 glass.css 之后加载，避免反向改玻璃包序）。

**z 序**：页头带 20 层内 胶囊 5 < 返回钮 6（返回钮吸附后仍在胶囊左侧可点，胶囊 `pointer-events: none` 不拦事件）。

**reduced-motion**：JS 写二值进度（`p >= 0.5 ? 1 : 0`）+ CSS 摘掉唯一位移量（大标题 `translateY`）⇒ 形态切换瞬时成立、零动画。

## 2. 改动面

- `css/mobileLayer.css`：
  - token 块新增「两段式标题几何」小节（12 个变量 + 进度变量 + 逐条取值理由）；
  - `.mobile-page-title` 整块重写：`text-align: center` + 左右内缩 72 + 盒高 52 + 行高 36 + `flex:none`（B 批防裁字基座保留）+ 由 p 派生的 opacity/位移；`margin: 0`（标题→首块的 12px 呼吸缝仍由收编面自带 `padding-top` 承担，本批零新增间距）；
  - `.mobile-page-title-mini` 改写为**毛玻璃胶囊**（绝对悬浮、`left:50%` 居中、`width:max-content` + `max-width: calc(100% − 2×72)`、材质单源、`z-index:5`）；
  - 新增 `.mobile-page.mpt-pane-scroll .mobile-page-title` 收高规则；
  - `.mobile-page-title-band`（顶缘渐隐带）规则块**整段删除**；
  - `.mobile-page-body` 顶让位 `calc(72px + safe)` → `calc(12px + safe)`；
  - 文件尾 reduced-motion 改为摘位移 + no-backdrop 两段降级；
  - 文件头补「面板头两段式标题批」批次注记，B 批注记条目②③ 标注退役。
- `js/mobile/navController.js`：
  - `ROLL_START = 8` / `ROLL_THRESHOLD = 56` + `reducedMotion()` / `primaryScroller()` / `applyRoll()` / `bindRollProbe()`（捕获相位滚动监听，页内一切滚区零逐面接线）/ `refreshRoll()`（进场对账，DOM 常驻、滚动位保留 ⇒ 离场吸附态不带进下次进场）；
  - `buildSection` 只建 `head.append(back, mini)`，不再建 `.mobile-page-title-band` 节点；
  - `renderPages` live 分支 `title.focus()` 后调 `refreshRoll(section)`。
- `tests/mobile-panel-title.spec.mjs`：按新契约**全文改写**（见 §4）。
- `tests/mobile-dock.spec.mjs` D18：`titlePadLeft` 16px → **72px**，并补 `titlePadRight` 72px / `titleAlign` center 三条断言。
- 新增 `tests/mobile-two-stage-title-shots.mjs`：375 双主题 × 3 面板 × 静止/吸附中/回顶实拍 + `--web-root` 指向修前资源根产出对比帧。

## 3. 实测账本（375×812 @2x，`npm` spec 读数）

| 读数 | 修前（main 8ec4832fa） | 修后 |
|---|---|---|
| 大标题 `text-align` | left（padding 6px 16px 10px） | **center**（padding 10px 72px 6px） |
| 大标题盒 | 顶 72、高 52、底 124 | **顶 12、高 52、底 64 = 返回钮底缘** |
| 文字行盒中心 | 72 + 10 + 18 = 100（钮中心 40，不同轴） | **12 + 10 + 18 = 40 = 返回钮中心，同轴** |
| 首块内容起点（项目首卡实测） | 136px（72 顶带 + 52 盒 + 12 面自带 pt） | **76px（= 盒底 64 + 收编面自带 pt 12）** |
| 首屏内容增量 | — | **+60px**（实拍对比帧 `compare-rest-1/2.png`） |
| 吸附态胶囊 | 15px 小字 + 顶缘渐隐带（渐变遮罩） | **32px 高毛玻璃胶囊 r999、中心 40、左缘 ≥72、opacity = p** |
| 吸附态顶区占位 | 渐隐带整条 + 大标题位移淡出（面内仍留 12px 顶带） | **0（pane 页收高至 0；流内页标题滚出视口）** |
| 渐隐带节点 | `.mobile-page-title-band` 存在 | **DOM 计数 0** |

几何一致性（双引擎 × 双主题 × 3 面板全部同值，spec 断言钉死）：标题盒 `h=52 / top=12 / bottom=64`、文字中心 `= backCenterY = 40`、胶囊 `h=32 / centerY=40 / 横向居中 cx=188（vw 375 的中轴）/ left≥72`。

## 4. 验收（spec 全绿面）

`node tests/mobile-panel-title.spec.mjs`（双引擎 Chromium/WebKit × 双主题 × 3 面板 + reduced-motion 腿 + 桌面腿）：

- P1 静止态 24 条/面板：static + 页体首件、`center`、`72px/72px`、28px/600、字距负档、墨色 = `--color-frame-text-bright`、`opacity 1`、`h=52 / top=12 / bottom=64`、文字中心 = 钮中心 40、胶囊 `opacity 0 / h=32 / cy=40 / cx=中轴 / left≥72 / 15px / r999 / blur`、`pointer-events none`、渐隐带计数 0、返回钮在场可点、面内同名双标题退役。
- P2 阈值 + 插值：`scrollTop 40` ⇒ `p = 0.667`（实测值换算，容差 0.02）、不切 `.rolled`、大标题 `opacity = 1−p`、胶囊 `opacity = p`、胶囊几何不漂；`scrollTop 300` ⇒ `p = 1`、`.rolled`、胶囊满透明、大标题 `opacity 0 / pointer-events none / h=0`（顶区零占位）、返回钮 z 6 > 胶囊 5。
- P3 真滚轮回顶：`mouse.wheel` 上滚到顶 ⇒ `p=0`、`.rolled` 消失、大标题复现、胶囊退回 0。
- P4 面板切换对账：projects 吸附态离场 → 进 settings ⇒ `p=0 / h=52`（不继承）。
- P5 reduced-motion：进度二值、`transform: none`。
- **ALL PASS，零 console error。**

联动回归（同 worktree 实跑）：

- `tests/mobile-avatar-chip-title-gap.spec.mjs` —— **ALL PASS（152 条）**，其中 `projects/usage 标题→首块 = 12±2`（实测 12）、`settings ≤16`（实测 16）、`todo ≤16`（实测 0）、`标题盒 52±2`（实测 52）全部不变 ⇒ 本批未破坏 [[20261101_172500_mobile-panel-title-b__delegate-nebflow-0c24df31|B 批]]「标题→首块 12」标准档。
- `tests/mobile-dock.spec.mjs` —— **21 passed**（D18 已按新契约改 72px/center）。
- `tests/mobile-p12-settings.spec.mjs` / `mobile-p8-projects.spec.mjs` / `mobile-topbar.spec.mjs` —— **9 passed**（首件 = 大标题、次 = 收编面；详情页标题文案；根页无页头无标题全部不变）。
- 桌面腿（1280×800）：`body.mobile-layer` 不激活、页面壳/标题节点计数 0、`#header` 在场 ⇒ 桌面零触碰。

## 5. 未做 / 边界

- 只改手机档标题形态：面板内部内容布局零触碰（收编面几何块、面头退役四件、间距档全部沿用 B 批）；
- dock 四根页（消息/联系人/看板/工作台）维持无页头（手机顶栏批在先令）；
- 桌面端零改动（mobileLayer.css 全规则挂 `body.mobile-layer`，navController.js 本就是手机层专属控制器）；
- 未触碰在飞批工作区（全设备统一更新 / 顶部头像调小）。

## 6. 产物

- `css/mobileLayer.css`、`js/mobile/navController.js`
- `tests/mobile-panel-title.spec.mjs`（改写）、`tests/mobile-dock.spec.mjs`（D18）、`tests/mobile-two-stage-title-shots.mjs`（新增）
- 实拍：`evidence/20261102_085500_mobile-two-stage-title/shots/`（375×812 @2x 双主题：设置 静止/吸附中/吸附/回顶 4 拍 × 2 主题 + 项目、待办 静止/吸附中/吸附 3 拍 × 2 主题 = 20 帧修后 + 同机位 20 帧修前 + 2 张「修前 vs 修后」同屏对比拼版 `compare-rest-1/2.png`）；spec 日志同目录 `*.spec.log`。

## 7. 已知既有失败（本批无关，基线复现）

`tests/mobile-p6-bgstatus.spec.mjs` T2（`.bg-origin-nebula` 文案）与 `tests/mobile-v3.spec.mjs` T2（任务徽标 10 vs 8）在 **未打本批改动的基线（stash 后同 worktree 同夹具）上同样失败**，属夹具/上游面读数漂移的在飞问题，本批不触碰该两面（后台任务面 / 任务徽标数据面）。其余 52 条全绿。
