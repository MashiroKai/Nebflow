---
type: batch
status: done
related: "[[20261102_085500_mobile-two-stage-title__delegate-nebflow-1bae7308]]"
---

# 面板头胶囊高度 + canvas 控件尺寸对齐批（2026-11-02）

> 前序批：[[20261102_085500_mobile-two-stage-title__delegate-nebflow-1bae7308|面板头两段式标题批]]（本批只改其「吸附胶囊」一段的高度档与内距，形态/滚型/让位全部沿用）；相邻面见 [[20261102_083638_mobile-canvas-ring-tabbar__delegate-nebflow-807819e2|手机 canvas 环带页签批]]（本批 ⑥ 断言的相交对家）。文档索引：[[index]]。

## 摘要（作者两令、一批落地）

- **令 A（09:33）**「面板的进入胶囊状态的胶囊大小要和返回按钮上下宽度差不多大」：手机端面板头吸附态胶囊 `--mcap-h` 32 → **48**（= 返回钮 48，|差| = 0px），垂直中心仍 40（只向上下对称生长），左右内距 16 → **24**（= 新半径 h/2，文字落直段内不被圆弧压边）；宽度策略（`max-content` + 左缘 ≥ 72）、大标题、插值区间 8→56、内容让位、返回钮**零触碰**。
- **令 B（09:41）**「包括 canvas 的这些控件宽度也要保持一致」：canvas 文档 chrome 三档尺寸（条内钮 26 / 独立刷新圆钮 40 / 工具簇圆钮 40）收到**一个真值 48**（= 手机层 `--mtap-lg` = 返回钮 = 吸附胶囊），条高 36 → **58**、条宽 138/170 → **222/278**、底锚 18 → **11**（条内钮中线 = 圆钮行中线 40）、钮距 4 → **8**（比例同档：4/26 ≈ 15.4% ⇒ 8/48 ≈ 16.7%）、读数 11px/40 → **13px/44**。ask 入口钮桌面 44 → 48（座位 22 → 24）。桌面腿与手机腿同档同源。
- 静态不变面（逐条守住）：玻璃材质/圆角语义/SVG 图标/控件顺序/左右落位/亮暗适配/`--cv-size-circle` 40 面板级档（deck-fab / canvas-head-close / .g2-fab）。
- 证据：`evidence/20261102_title-capsule-height/`（before/after 各 24 张手机帧 + 移动 html 面 before/after 双主题 + 桌面 1280 帧 + 三张合成帧），spec 双跑读数见 §3。

## 1. 契约（token 与几何）

| token / 值 | 修前 | 修后 | 取值理由 |
|---|---|---|---|
| `--mcap-h`（`mobileLayer.css` 手机层 token 块） | 32px | **48px** | = 返回钮高 48（`--mtap-lg` / `.g2-fab`）；作者令「和返回按钮上下宽度差不多大」 |
| `--mcap-pad` | 16px | **24px** | = 新半径 h/2（48/2）；48 高下 r999 两端为 24 半圆，内距取同值使文字整段落直段 |
| `--mcap-top`（垂直中心） | 40px | 40px（不动） | = 返回钮中心 16 + 48/2；高度只向上下对称生长 |
| `--mcap-radius` | 999px | 999px | 胶囊形态不变 |
| 横向（左缘 / 缝） | 72 / 8 | 72 / 8 | 胶囊横向居中、宽 `max-content`；高度变大不改横向 |
| `--cv-size-circle-lg`（新增，`sapphire.css`） | — | **48px** | canvas 文档 chrome 档（缩放条钮 + 圆钮簇 + 壳刷新钮 + ask-fab） |
| `--cv-icon-btn-lg`（新增） | — | **20px** | 48 档钮内图标（= 手机层 48 钮统一图标档） |
| `--cv-row-pitch-lg`（新增） | — | **56px** | 48 钮 + 8 缝（簇槽距） |
| `--cv-size-circle`（保留） | 40px | 40px | **面板级**圆钮档（deck-fab / canvas-head-close / .g2-fab）；canvas-stack A1-5/A13-3 钉 40，本批不碰 |
| `--ask-fab-size` / `--ask-fab-bottom` | 44 / 22 | **48 / 24** | 全站悬浮圆钮单一真值 48；同排共中线 48 − 24 = 24 |
| `REFRESH_CIRCLE_SIZE` / `REFRESH_ROW_PITCH` / 行底锚（`shared.js`） | 40 / 48 / [16,64] | **48 / 56 / [16,72]** | 槽距 = 钮 + 8 缝；第二行 = 圆钮行顶 16+48 + 8 |
| 簇槽位（源码/引用/查找/缩放条，html） | 16 / 64 / 112 / 160 | **16 / 72 / 128 / 184** | 56 槽距派生（md 查找 64 → 72，monaco 16 不变） |

## 2. 改动面

- `css/mobileLayer.css`：胶囊 token 三值（上表）+ 批次注记；①b canvas 条块**删掉自带几何**（原四条手机层覆盖规则），条几何由 `split.css` 单源承担（手机 canvas pane 恒 375 < 414，容器查询同拍命中第二行）。
- `css/sapphire.css`：新增 `--cv-size-circle-lg` / `--cv-icon-btn-lg` / `--cv-row-pitch-lg` 三个 token + 48 与 40 两档的分层说明。
- `css/split.css`：`.canvas-zoom-bar`（gap 8 / bottom 11）、`.canvas-zoom-btn`（48 / 图标 20 / 圆角仍 7px）、`.canvas-zoom-pct`（13px / min-width 44）、`.canvas-source-toggle` / `.canvas-ref-select`（left 72）/ `.canvas-find-toggle` / `.canvas-refresh-toggle`（48）、html 条槽位 184 + 容器查询阈值 355 → **414**（184 + 222 + 8）、阅读器底栏让位 64 → **72**。
- `css/ask.css` + `js/askDock.js`：入口钮 48 / 座位 24（CSS token 与 JS `FAB_BOTTOM_BASE` 两处同步，JS 写内联变量会覆盖 CSS 缺省）。
- `js/viewers/shared.js`（槽距常量 + 第二条 seatRefreshButton 行锚）、`markdown.js`（查找 72）、`html.js`（查找 128）。
- spec：`mobile-panel-title.spec.mjs`（胶囊七条 + 新增 ⑦ 全页壳一致腿）、新增 `canvas-chrome-size-align.spec.mjs`（Order B ①–⑥ + ⑤b + 读数/条高）、`canvas-ask-dock.spec.mjs`（T1a 48 / T1c 24 / T9b 期望按活 token 拼装）、`reader-p1.spec.mjs`（A5 bottom 72）、`canvas-html-toolbar-overlap.spec.mjs`（Fit 钮探针由 i18n title 字面量改为按位置取最后一钮）。
- `tests/visual/baseline/darwin-chromium-151/`：`canvas-markdown-tab-*` / `canvas-code-tab-*` 四张基准重采集（差异区 = 底部 chrome 一行：三条圆钮 40 → 48 的像素变化，人工复核 diff 图确认仅此）。

## 3. 实测账本（§3.1 手机 375×812 @2x，§3.2 桌面 1280×800）

### 3.1 手机

| 读数 | 修前 | 修后 |
|---|---|---|
| 吸附胶囊高 / 中心 / 内距 | 32 / 40 / 16+16 | **48 / 40 / 24+24** |
| 胶囊 ↔ 返回钮高差 / 中心偏 | 16 / 0 | **0 / 0** |
| 胶囊左缘（真实页名 / 上限饱和） | ≥72 / 8 缝 | **≥72 / 8 缝（不变）** |
| 缩放条 | 140×36，left 160，bottom 18 | **222×58，left 16，bottom 72（第二行）** |
| 条内钮 / 图标 / 钮距 | 26 / 14 / 4 | **48 / 20 / 8** |
| 读数 | 40×13 @11px | **44×16 @13px**（两侧净空各 8） |
| 圆钮簇槽位 | 16 / 64 / 112（40 钮 → 手机层覆盖 48） | **16 / 72 / 128（48）** |
| 条 ↔ 环带签 / 小点 / 左右浮钮 / ask | 零相交 | **零相交**（⑥ 实测 clean） |
| 全页壳（21 非根页）胶囊高 / 中心 / 内距 / 圆角 / 左缘 / 文字裁切 | — | **48 / 40 / 24 / 999 / ≥72 / 无裁切**（逐壳断言） |

### 3.2 桌面（canvas 控件尺寸对齐，令 B 的主现场）

| 控件 | 修前 | 修后 |
|---|---|---|
| 缩放条内钮（抓手 / 缩小 / 放大 / 适应视图） | 26×26 | **48×48** |
| 缩放条本体（图片 4 钮 / html 3 钮） | 170×36 / 140×36 | **278×58 / 222×58** |
| 条底锚 | 18 | **11**（条内钮中线 = 圆钮行中线 40） |
| 条内钮距 | 4 | **8** |
| `24%` / `100%` 读数 | 40×13 @11px | **44×16 @13px**（竖直居中 dy = 0） |
| 壳刷新圆钮 `#canvas-content > .canvas-refresh-toggle` | 40×40 | **48×48** |
| 工具簇圆钮（源码 / 引用 / 查找） | 40×40，左 16 / 64 / 112 | **48×48，左 16 / 72 / 128** |
| html 条槽位 | 160 | **184**；窄面板 ≤414px 回落第二行（left 16 / bottom 72） |
| ask 入口钮 | 44×44，bottom 22 | **48×48，bottom 24** |
| 阅读器底栏让位（epub 刷新钮） | bottom 64 | **bottom 72** |
| 命中面 | 26 / 40 | **48 ≥ 44**（Apple 硬标准，零外扩） |

断言读数（`canvas-chrome-size-align.spec.mjs`，**63 PASS / 0 FAIL**）：① 条内钮宽 48/48/48/48；② 刷新 48 + 簇 48/48/48；③ 高 = 宽全方；④ 钮高 = 档高 48（手机取吸附胶囊实测高，桌面取 `--cv-size-circle-lg`）；⑤ 条内缝 8 两两差 0、簇槽距缝 8/8；⑥ 零相交（手机：环带签 / 小点 / ✕ / 堆叠钮 / ask；桌面：条 / 簇 / 刷新 + 全部落在 pane 内）；⑤b 读数两侧各 8。三腿（图片 pane / html pane / 窄 320）双主题逐值一致。

## 4. 「canvas 工具条尺寸对齐」逐控件 原值 → 新值

| 控件 | 原值 | 新值 | 备注 |
|---|---|---|---|
| 抓手 / 平移钮 | 26×26 | **48×48** | 图标 14 → 20，SVG 本体零改 |
| 缩小钮 | 26×26 | **48×48** | 同上 |
| `24%` 读数 | 宽 40 / 11px | **宽 44 / 13px** | 文本件、宽度随内容（tabular-nums），竖直居中 |
| 放大钮 | 26×26 | **48×48** | 同上 |
| 四向箭头 / 适应视图钮 | 26×26 | **48×48** | 同上 |
| 独立圆形刷新钮 | 40×40 | **48×48** | 作者实拍「旁边那颗又大一号的」 |
| 条容器 | 170×36（图片）/ 140×36（html），内距 4、缝 4、底锚 18 | **278×58 / 222×58**，内距 4、缝 8、底锚 11 | 条高 = 48 + 2×4 + 2×1 描边 |
| 工具簇圆钮（同批连带） | 40×40 @16/64/112 | **48×48 @16/72/128** | 单真值 48 |

零改面：条玻璃材质（`--cv-*` 单源）、条圆角 10px、钮圆角 7px（条内方钮语义）、SVG 图标路径、控件顺序、左右落位语义（左锚 16）、亮暗适配（纯 token）。

## 5. 「桌面腿」

- 桌面 canvas 面板与手机 canvas 页**同档同源**：48 / 内距 4 / 缝 8 / 底锚 11 / 读数 13 一律写在 `split.css` 通用块，手机层零重复真值（`@container nfpane (max-width:414px)` 回落第二行同时服务「桌面窄面板」与「手机 375 pane」）。
- 唯一有意差异：**面板级圆钮仍 40**（`--cv-size-circle`：`#deck-fab` / `#canvas-head-close` / `.g2-fab`）——它们是「面板 chrome」不是「canvas 文档 chrome」，且 `canvas-stack.spec.mjs` A1-5/A13-3 钉死 40px；本批只把**文档 chrome**（缩放条 / 圆钮簇 / 壳刷新 / ask 入口）收到 48。
- ask 入口钮桌面从 44 升到 48 是「全站悬浮圆钮一个真值」的直接后果，座位随中线 22 → 24（CSS token 与 `askDock.js` 两处同步）。

## 6. 回归面（本批已跑）

`mobile-panel-title`（**634 PASS / 0 FAIL**，含新增 ⑦ 21 壳）、`canvas-chrome-size-align`（新，63/0）、`mobile-canvas-sheet`（14/14）、`mobile-dock`（21/21）、`mobile-topbar` + `mobile-pinned-search`（14/14）、`ime-guard`（37/37）、`mobile-avatar-chip-title-gap`（152/152）、`canvas-html-toolbar-overlap`（22/0）、`reader-p1`（全 PASS）、`canvas-ask-dock`（74 PASS / 1 FAIL）、`canvas-glass-unify`（30 PASS / 1 FAIL）、`canvas-file-refresh`（8 PASS / 1 FAIL）、`make check-visual`（canvas 四张基准已按期望重采集，其余 8 项为 main 既有差异）。

三处 FAIL 与一条环境注记均为 **main 既有**（同机 `HEAD` 干净 worktree 复跑逐条红同样断言，非本批引入）：`canvas-ask-dock` T12c（气泡材质断言与 v3 液体玻璃后写胜出的 `--g2-fab-*` 事实不符）、`canvas-glass-unify` GU2-3（`--g2-fab-*` token 名已不在根作用域）、`canvas-file-refresh` A2（同一 pane 内两个 `.canvas-md-viewer` 触发 strict mode 冲突）；`make check-visual` 在本机 Makefile L27 有既存 parse error（`$(shell …)` 未闭合），改用 `node scripts/visual-baseline.mjs --check` 等价执行。

## 7. 证据

`evidence/20261102_title-capsule-height/`：
- `shots/before-*` / `shots/after-*`（深/浅 × 项目/设置/待办 × 静止/吸附中/吸附/回顶，各 24 张）：胶囊 32 → 48 的同机位对比；
- `shots/canvas-html-before-* / canvas-html-after-*`：手机 375 canvas html 面（条 140×36 同行 → 222×58 第二行 + 簇右移）；
- `shots/desktop-before-* / desktop-after-*`：1280×800 图片 pane 与 html pane（条 170×36 + 26 钮 → 278×58 + 48 钮；刷新圆钮 40 → 48）；
- 合成帧：`shots/composite-capsule-before-after.png`（胶囊前后裁切）、`shots/composite-canvas-toolbar-mobile.png`（手机 canvas 条前后 × 双主题）、`shots/composite-canvas-toolbar-desktop.png`（桌面条前后）、`shots/composite-capsule-and-toolbar.png`（胶囊与工具条并排，同一 48 档）；
- `canvas-chrome/`：`canvas-chrome-size-align.spec.mjs` 五腿实拍（desktop-image / desktop-html / mobile-dark / mobile-light）；
- 日志：`mobile-panel-title.spec.log`、`canvas-chrome-size-align.log`、`logs/`（12 个 spec + 4 个 HEAD 基线复跑 + 视觉门禁）。

零 console error（各 spec 硬零断言；仅探针夹具的 `/agents/Nebula/agent.json` 404 噪声，spec 侧已 route 掉）。
