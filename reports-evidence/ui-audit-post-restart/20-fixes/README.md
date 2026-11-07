# 六类 UI 缺陷处置对照（2026-11-07）__20261107_1210

作者原话（2026-11-07 11:40）：「ui的问题包括但不限于 你的聊天气泡也是绿色的 设置里改的边缘布局非常拥挤 好友顶部 canvas 以及各种顶部 我说了要像主会话这样 没有边缘 按钮是悬浮的 消息记录搜索按钮是歪的 很多交互是直角 而不是圆角 ask对话框的消息会突然透明等」

## 取证方法（必读，关系到下面的数字怎么来的）

运行中的引擎（pid 45825）服务的是**主检出**的 web 树（`/Users/kaiyu/Claude code/Stand/src/main/resources/web`），本批改动在 worktree 里。
**不重启引擎**的前提下做 A/B：用 Playwright `page.route()` 把**只被我改过的三个文件**（`css/base.css`、`css/mobileLayer.css`、`js/askDock.js`）在请求处就地换成工作树版本，引擎/数据/真实布局一毫不改。
- `ROUTE=0`＝引擎现served 的主树文件 = **改前**
- `ROUTE=1`＝工作树文件 = **改后**

复现：`node reports-evidence/ui-audit-post-restart/compare.mjs before` / `ROUTE=1 node compare.mjs after`；读数落 `_data/compare-{before,after}.json`。

## 六类对照

| 类 | 作者原话 | 定位（file:line，改前） | 改法 | 状态 | 客观读数（改前 → 改后） |
|---|---|---|---|---|---|
| ① 气泡配色 | 「你的聊天气泡也是绿色的…禁止出现微信式高饱和绿；单点 token，好友页/群页/主会话三处同源」 | `css/base.css:26` `--color-bubble-user: #95ec69`（该行旧注释自己就写着「微信绿」）；单点消费方 `css/glass.css:269-290`「气泡归属方底色单点」块 | 只换**色相来源**，两档结构与单点纪律不动：亮档 `#cdd9ec`（sapphire 淡染）、暗档新增 `#29354d`（`css/base.css:120` 后的 dark 块）。正文仍吃 `--color-text`，故亮/暗字色随主题自动成立；好友/群/主会话三处同源自动跟随。门禁 `node scripts/check-bubble-side.mjs` → **PASS（exit 0）** | **已修** | 我方气泡实测底色：亮 `srgb(0.584,0.925,0.412)`=微信绿 #95ec69 → `srgb(0.804,0.851,0.925)`=#cdd9ec；暗 → `srgb(0.161,0.208,0.302)`=#29354d（`_data/compare-*.json` → `runs.*.chat.bubble.bg`） |
| ② 顶部悬浮无边缘、不压标题 | 「好友顶部 canvas 以及各种顶部 我说了要像主会话这样 没有边缘 按钮是悬浮的」「顶部胶囊与页面标题重叠」「消息记录搜索按钮压在消息上」 | `css/mobileLayer.css:1056` `body.mobile-layer .mobile-page-nohead .mobile-page-body { padding-top: 0 }`（dock 根页＝消息记录/画布/联系人/看板/工作台，顶归零 ⇒ 首行直接落进 48px 浮钮命中区） | 改为与详情页同档让位：`padding-top: calc(var(--mtitle-lead) + env(safe-area-inset-top,0px))`（`css/mobileLayer.css:1055-1065`，含推翻理由批注）。浮钮仍是悬浮形态（无边框、无分隔条，未动 .g2-fab 材质） | **部分已修**（顶让位 +12px 已落地；「滚动动态穿钮」维持既裁定，见下） | 消息列表/好友页 `padding-top` 0px → 12px；`padding-bottom` 0px → 72px（`_data/compare-*.json` → `runs.*.list/settingsBottom.dock`） |
| ③ 边距/拥挤、底栏压正文 | 「设置里改的边缘布局非常拥挤」「底部悬浮导航必须给正文留安全底距，不得压住最后一行内容」 | `css/mobileLayer.css:1042-1056`（页体只有顶 padding，**无底部净空**；设置页滚到底时「文件缓存」行贴到胶囊上缘） | 新增 `body.mobile-layer .mobile-page-body { padding-bottom: calc(var(--dock-h) + var(--dock-gap) + var(--dock-inset-b)) }`（`:1066-1074`，= 54+10+8 = 72px 净空） | **已修（底距）** | 设置页滚到底 `padding-bottom` 0px → 72px；末行与 dock 相交面积 0（改前是贴边 0 缝，视觉上被压） |
| ④ 圆角统一 | 「很多交互是直角 而不是圆角」 | 现存交互件已大量走 `--ds-radius-*`（`css/settingsRows.css:37/232` 等）；本次实测**未找到成规模直角面**：可见交互件中直角项仅手机 dock 的 5 个 `.dock-key`（胶囊底色透明、视觉圆角由父层给） | 未改代码 | **未修（需作者点名具体面）** | `squareTotal`：手机 5 / 桌面 0（`_data/compare-*.json` → `runs.*.chat.squareTop`）——若作者指的是设置「服务商列表」卡片，那是 `js/sidebar/providerPanel.js:529` 渲染的 `#provider-list .provider-card-list`，其样式不在 `css/*.css` 任何 `.provider-card` 规则里（待定位到注入点再改） |
| ⑤ 搜索按钮归位 | 「消息记录搜索按钮是歪的」 | `css/mobileLayer.css:555` `.mh-chrome-btn[data-chrome='search']{ right: var(--mchrome-top,16px) }`（绝对定位悬浮圆钮，与主会话同一规格） | 未改定位；其「歪」的观感来自钮下压着消息文本——该文本现已由②的顶让位推开 | **未修（根因待裁定）** | 顶让位生效后，消息列表首行不再整块落在钮下；若作者要它并入页标题行（而非悬浮），需裁定是否推翻 2026-10-31「悬浮钮」批 |
| ⑥ ask 对话框突然透明 | 「ask对话框的消息会突然透明」 | `js/askDock.js:syncAskFade()`：`opacity = clamp(d/fadeH)` ⇒ **泡顶一旦越过列顶 d<0 即落 0**，整条消息彻底透明（注释称只有 12px 衰减带，与真实渲染不符） | 改「边缘提示」而非「隐形」：`opacity = 0.55 + 0.45*ratio`（底限 0.55），顶缘仍有一档柔化 | **已修** | 代码级单点；衰减带内 `opacity ∈ [0.55,1]`，不再有 0（`js/askDock.js` syncAskFade 批注） |

## 对比图（亮/暗 × 手机 375×812 + 桌面 1440×900）

改前（`__*-before.png`，引擎 serverd 主树文件）与改后（`__*-after.png`，工作树文件）同引擎同数据逐面成对：

```
20-fixes/01-bubble/<主题>-<尺寸>__chat-{before,after}.png        面：主会话（气泡配色，类①）
20-fixes/02-settings/<主题>-<尺寸>__top-{before,after}.png       面：设置页顶（类②③）
20-fixes/02-settings/<主题>-<尺寸>__bottom-{before,after}.png    面：设置页滚到底（类③末行压 dock）
20-fixes/03-peer/<主题>-<尺寸>__chat-{before,after}.png          面：好友聊天（类①②）
20-fixes/04-list/<主题>-<尺寸>__list-{before,after}.png          面：消息记录列表（类②⑤）
20-fixes/05-canvas/<主题>-<尺寸>__canvas-{before,after}.png      面：画布（类②③）
```
主题尺寸共四档：`light-mobile` / `dark-mobile` / `light-desktop` / `dark-desktop`（手机为 375×812 DPR2，桌面 1440×900）。

## 未改的原因（逐条）

- **②⑤ 的剩余部分**：「滚动态内容自浮钮下穿过」是 2026-10-31 悬浮钮批的**既有裁定**（`css/mobileLayer.css:1036-1041` 批注原文），静态帧有 `tests/mobile-chrome-clearance.spec.mjs` C1 断言守着。本次只把「静止态首行落进钮下」这一个真错位修掉，不动浮钮形态本身——要改成作者说的「按钮是悬浮的但内容不穿」需要推翻该裁定，属设计决策。
- **④ 圆角**：实测盘面已无成规模直角，改之前需要作者点名是哪个面（服务商列表卡片最可疑，但其样式来源不在 `css/*.css`）。
- **未跑全量 sbt**：本批只碰 `css/base.css`、`css/mobileLayer.css`、`js/askDock.js` 三个文件，另跑了 `node scripts/check-bubble-side.mjs`（PASS）。

## 纪律核对

- 改设计不改功能：三处改动均为样式/透明度载体，零 DOM 结构变更、零行为分支变更。
- 不动存量红：未触碰 `js/ws.js` 颜色基线；`--search-kind-user` 仍为绿族（属搜索徽标，不属气泡，未并改）。
- 未 push / 未打 tag / 未改版本号 / 未重启引擎。
