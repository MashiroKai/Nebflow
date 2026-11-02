---
type: fix-report
status: published
date: 2026-11-02
scope: 前端引导面字标与状态点（src/main/resources/web/index.html、css/cards.css）+ 验收钉（tests/entry-veil.spec.mjs、cards-family.spec.mjs、console-takeover.spec.mjs）
---

# 启动加载卡品牌图标批：破图根因 + emoji/字形当图标全仓登记（2026-11-02 08:16 作者报障）

> 结论先行：作者实拍里入场加载卡顶部那个**蓝底问号方块不是 emoji 回落，是 404 破图**——
> 卡上字标原为两张 PNG `<img>`（`/brand/stand/lockup-{light,dark}.png`），而 `/brand/`
> 路由只读 **classpath** 资源树、页面与其余资产走 **disk dist**：cards-family 批
> （2026-11-01 16:43）新入仓的这两张 PNG 晚于最后一次 Scala 编译（11-01 13:23），
> 于是 dist 脸引用了一张只活在「未来 classpath」里的图，实跑实例上两图均
> `naturalWidth: 0` / HTTP 404，浏览器画成缺图占位。修复 = 字标改**单档内联 SVG**
> （几何逐 path 抄仓内集中源，`fill="currentColor"` 随主题），零请求、零第二份资产、
> 零硬编码色值；顺带把同一张卡 done/fail 的 `content:'✓'`/`content:'×'` **字体字形当
> 图标**换成纯 CSS 几何勾叉（作者点名的「缺字形必坏」类），并修 `prefers-color-scheme:
> reduce` 的无效特性值（减少动效用户的加载环一直在转）。同批交 emoji/字形当图标的
> 全仓清单（§4）。家族语言零漂移依据 [[20261101_162000_cards-family-design__delegate-nebflow-67f23d42.zh-CN]]；
> 引导门机制背景见 [[20261030_204500_boot-render-gate__delegate-nebflow-ca1a7b93]]。

## 1. 根因（实证，非推断）

| 证据 | 读数 |
|---|---|
| 实跑实例（PID 30450 / 端口 6138）直接取图 | `/brand/stand/lockup-light.png` → **404**、`lockup-dark.png` → **404**；同树 `/brand/stand/logo/logo-512-light.png` → 200、`/brand/stand/illustrator/*` → 200 |
| 页面内 `<img>` 状态 | `complete: true` 但 `naturalWidth: 0, naturalHeight: 0`（两张均是）⇒ 浏览器画**缺图占位**。占位形态随引擎而异：作者截图（WebKit）是 48px **蓝底白问号小方块**，Chromium 复现是「小破图图标 + 灰色 alt 文本 STAND」（见 `before/before-clip-*.png`）——同一根因的两种画法 |
| 资源路径判定 | `/assets/app-<hash>.css` → 200、`/css/cards.css` → **404** ⇒ 页面走 **disk dist**（`build/web-dist`），不是 dev 源树 |
| 路由实现 | `src/main/scala/nebflow/gateway/StaticRoutes.scala:874-888` `brandSubtree` → `StaticFile.fromResource("web/brand/$path")`，**只读 classpath**；`:60-145` `resolveStaticSource` 选中 `DiskDist` |
| 时间线 | 引擎 jar 构建 11-01 13:23；lockup PNG 由 cards-family 批 11-01 16:43 入仓 ⇒ 不在 jar 的 classpath 树里 |
| 网络面 | 单页 16 条 4xx，全部落在 `/brand/` 与 lockup 图 |

**机制一句话**：`/brand/` 的 serving 面（classpath）与页面/资产的 serving 面（disk dist）
不是同一棵树；新品牌资产进了 dist 树的引用侧却没进 classpath 的内容侧，中间这层错位
只有「实跑实例 + 网络读数」能看见——这正是三份旧 spec 全绿却挡不住本次报障的原因（§5）。

## 2. 修复（本批范围 = 启动加载卡 + 接管弹窗同字标）

1. **字标改内联 SVG**（`src/main/resources/web/index.html:550` 入场卡、`:1001` 接管弹窗）：
   `viewBox="0 0 1375.18 343.15"`（≡ 4:1），几何逐 path 抄仓内集中源
   `web/brand/stand/illustrator/svg/stand-horizontal-black.svg`，`fill="#000000"` →
   `fill="currentColor"`；`role="img" aria-label="STAND"`。零请求、零网络依赖、
   浅深两档共用一档（墨色继承 `--nfc-heading`）。
2. **CSS 单档契约**（`css/cards.css:100-121`）：`.nfc-lockup` =
   `display:block; width: var(--nfc-lockup-w); height: var(--nfc-lockup-h);
   color: var(--nfc-heading); flex-shrink: 0`；新增 token `--nfc-lockup-w: 96px`
   （`:43-44`，与既有 `--nfc-lockup-h: 24px` 同源，24 × 4.0075 ≈ 96）。原先的
   `.nfc-lockup-light` / `.nfc-lockup-dark` 双档显隐切换（含 `prefers-color-scheme`
   回退腿）整块退役——那条腿正是 404 的来源。
3. **三态点改纯 CSS 几何**（`css/cards.css:248-310`）：done 的 `content:'✓'` →
   `::after` 4×7 三边框小矩形 `rotate(45deg)`（2px 描边 ≡ 环的笔宽）；fail 的
   `content:'×'` + 硬编码 `#d1242f` → `::before`/`::after` 两根 7×2 圆头杆 ±45°、
   `background: var(--status-error-icon)`。三态字色分层：pending `--nfc-subtle` <
   done `--nfc-link` < active `--nfc-heading`（状态不靠单一通道辨识）。暗档走
   `--sapphire-glow` 变体，系统回退腿同构。
4. **减少动效缺陷**（`index.html:167`）：`@media (prefers-color-scheme: reduce)` →
   `@media (prefers-reduced-motion: reduce)`。原值是不存在的特性值，永不命中，
   减少动效偏好的用户加载环照转。

## 3. 动效与三态读数（验收项③④）

| 判据 | 实测 |
|---|---|
| 进行中真在跑（非静帧） | 同一活动行两帧 `transform` 变化：`matrix(-0.383,0.924,…)` → `matrix(-0.981,-0.189,…)`；`animationName = nf-boot-spin 0.8s linear infinite` |
| done / pending 不动 | 两者 `animationName = none` |
| 三态环几何互异 | done 实环（`rgba(91,127,191,.9)`）/ active 断口弧（top 边 `transparent`）/ pending 中性空环（`--nfc-heading` 18% color-mix） |
| 三态墨色单调 | 以卡面 bg 的对比度距离为尺：浅档 Δ 92.7 < 141.5 < 231.4；暗档同理分区 |
| 字标几何 | 96×24，`aria-label=STAND`，标记内零 `#hex`、含 `currentColor`；卡内 `<img>` 计数 0 |
| 深浅双档 | 浅档 `rgb(17,24,39)`；暗档 `rgb(230,237,243)`（= `--nfc-heading` 暗值 #e6edf3） |
| 家族语言零漂移 | 卡体 bg `#fff`/暗 `#161b22`、radius 18px、width 384px、边 `#e5e7eb`/`#2d333b`、阴影两档官方栈——与 cards-family 设计稿逐值一致 |
| console | 浅/深 × 桌面/手机四档：console error **0**、page error **0**、HTTP ≥400 **0**（dev 树自建服务器） |

证据目录：`evidence/20261102_bootcard-brandicon__delegate-nebflow-f555a161/`
- `before/`（实跑旧实例，生产脸）：`author-report-0816.jpg`（作者原报障帧）、
  `before-{light,dark}-{desktop,mobile}.png`（四档整页）、`before-clip-{light,dark}.png`
  （卡面特写：字标位置是一枚**破图方框 + 灰色 alt 文本 STAND**——Chromium 的缺图占位
  画法；作者截图同位置是 WebKit 的**蓝底白问号方块**，同一根因）、
  `compare-{light,dark}.png`（左右对照片，同机位同尺寸：dark 的左半直接取作者原帧，
  蓝底问号 → 品牌字标；light 的左半取本机 Chromium 的破图占位）；
- `after/`（dev 树自建服务器，与既有 spec 同法）：`after-{light,dark}-{desktop,mobile}.png`
  （四档整页）、`after-clip-{light,dark}.png`（卡面特写，与 before-clip 同机位）、
  `after-motion-frame{1,2}.png`（同一活动行两帧，环的角度可见变化 ⇒ 动效在跑）。

## 4. emoji / 字形当图标全仓扫描（验收项②）

扫描口径：`src/main/resources/web` 下 `.js`/`.css`/`.html`（跳过 `vendor/`），只看
**字符串字面量与 CSS `content:`** 内的补形字符（emoji、dingbat、箭头、几何块），
排除注释。结果 **119 处 / 32 个文件**，其中 U+1F000–1FAFF 段（pictographic）仅 1 处
功能性使用。

### 4.1 功能性：真当 UI 图标用（建议换内联 SVG，按批处置）

| 位置 | 字形 | 用途 | 建议 |
|---|---|---|---|
| `js/chatSearch.js:1027` | 📎 | 搜索结果里附件前缀 | **唯一 emoji 功能图标**；换 `paperclip` SVG 片段（`<svg …><path …/></svg>`）内联 |
| `js/viewers/readerChrome.js:141, 430` | ☰ | 阅读器目录钮 | 换既有 lucide `menu`/`list`（`createIconsIn` 已可用） |
| `js/viewers/readerChrome.js:434`、`js/viewers/annotations.js:300` | ✎ | 批注钮 | 换 lucide `pencil`/`message-square` |
| `js/viewers/annotations.js:304` | × | 批注浮条关闭 | 换 `CLOSE_X_SVG`（`js/reference.js:260` 正范例） |
| `js/flowAgentPopup.js:482`、`js/bgAgentPopup.js:492` | ✕ | 弹窗关闭 | 同上 `CLOSE_X_SVG` |
| `js/main.js:3548` | ▸ / ▾ | 后台任务族折叠箭头 | 换 lucide `chevron-right` + CSS `rotate` |
| `js/chatSearch.js:620-621` | ▾ | 日历 pill 下拉箭头 | 同上 chevron + `rotate(90deg)` |
| `js/sidebar.js:2608` | ▸ | 子菜单指示 | 同上 |
| `js/dropbox.js:407`、`:313` | ✓ / ● | 保存成功反馈 / 设备徽标 | ✓ 换小几何勾（本批 `.nf-entry-dot::after` 同法）或 lucide `check`；● 本就是小圆点，可留（字形安全） |
| `js/fileSearch.js:116,121,126` | ↑ ↓ × | 查找条上/下/关 | chevron + `CLOSE_X_SVG` |
| `js/largeText.js:188,195`、`js/usageDashboard.js:621-639` | ↑ ↓ | 翻页 / 环比 | 箭头字形属基础拉丁扩展区，系统字体齐备；**低风险，可留但建议统一到 lucide** |
| `js/delegatePanoramaView.js:774,1094,1096` | ↻ ✓ − | 图例/重启标记 | 换 lucide `rotate-cw`/`check`/`minus` |
| `js/delegatePanoramaView.js:908` | ⎇ | 分支标记 | 换 lucide `git-branch` |
| `css/humanFace.css:42` | ▸ | `<summary>` 折叠指示 | 换纯 CSS 三角（`border` 三角）或 SVG |
| `css/modal.css:992` | ↗ | Canvas 打开提示 | 换 lucide `arrow-up-right` |
| `js/chat/durationBadges.js:54` | ✻ | 思考中标记（前缀字符） | 半装饰半功能；建议换一个小 SVG 星形或去字形 |
| 其余 × 关闭钮 16 处 | × | 关闭钮文本 | `js/messages.js:1292`、`js/managePanel.js:193-194`、`js/reference.js:432`、`js/usageDashboard.js:818`、`js/wikiGraph.js:503`、`js/workspacePicker.js:530`、`js/scheduled-task.js:645`、`js/staleTabHint.js:50`、`js/bgTaskOutputPopup.js:792`、`js/main/bgAgents.js:422,899,1087`、`js/askAttachments.js:175`、`js/friendGroups.js:392`、`js/modelPanel.js:297,827` — 统一换 `CLOSE_X_SVG` |

### 4.2 非功能性（不计入图标债，登记备查）

- `×{n}` **计数语法**（不是图标）：`managePanel.js:193`、`bgAgents.js:422/899/1087`、
  `usageDashboard.js:818`、`delegatePanoramaView.js:576/663/672`、`fmNodeCard.js:370`、
  以及 locale `running/finished/family ×{count}`、`Blocked ×{n}`。乘号是排版字符，
  语义上不该画成图标；若某天要统一，用 `×` → 文本 `x`/`times` 或词形改写，不必 SVG。
- 箭头**关系语法**：`a → b`（版本跃迁 `plugins.js:456`、上下文压缩 `locales`、
  分支基 `delegateChainView.js:378`、`delegatePanoramaView.js:744/777`、`bgAgents.js:1116`、
  依赖 `← deps`）、`↑/↓ {n} new messages`、覆盖率 `↑{pct}%`。同上：排版符号，非图标。
- 步骤分隔符 `→`（`projectTab.js:621-623`）、`·` 分隔符、`—` 破折号、`⌘`/`Ctrl` 键名、
  `⌈⌉` 数学（`attachPreviewField.js:119`）：排版字符。
- locale 文案里的 ✓/✗ 状态前缀：`zh-CN.js`/`en.js` 的 `login.success`、`ob2.model.probeOk`、
  `ob2.write.*`、`settings.saved`、`flowViewers.saved`、`secrets.saved` 等（约 14 组）。
  它们由**状态行 CSS 面**承载（图标列 + 文案列），文案里的字形是冗余；建议按批把
  字形从 locale 串里移出、交给 CSS 几何（本批 `.nf-entry-dot` 即范例）。
- `web/brand/stand/email/templates/*.html` 的 lockup `<img>`：指向 `mystand.space` **官网
  域**的静态文件，与本地 `/brand/` 路由无关（邮件模板由官网签发），不在本仓 serving 面。

## 5. 为什么旧 spec 全绿却漏掉本次报障

三份 spec（`entry-veil` / `cards-family` / `console-takeover`）都用**自建静态服务器从
`src/main/resources/web` 直服 dev 树**取样。lockup PNG 在 dev 树里存在 ⇒ 原判据
（「`.nfc-lockup-light` 这张 `<img>` 可见」）永远成立；而生产脸（disk dist + classpath
`/brand/`）的 404 从未被覆盖。**判据钉的是「文件存在」，不是「serving 面能取到」**。

新契约判据（本批改写，与本仓「零请求引导面」纪律同构）：

- `svg.nfc-lockup` 在场 + `aria-label=STAND` + 卡内 `img.nfc-lockup` 计数 **0**
  ⇒ 一旦有人把字标改回外部图片，判据立刻红；
- 几何 96×24 + 标记零 `#hex` 且含 `currentColor` ⇒ 钉住 token 配色契约；
- 暗档 `getComputedStyle(svg).color === 'rgb(230, 237, 243)'` ⇒ 钉住「单档随主题换墨」。

## 6. 遗留与建议

1. **`/brand/` 的 classpath-vs-dist 错位是引擎侧结构问题**，不是本批能改的面（改它要动
   `StaticRoutes` 的 serving 面选择逻辑，影响全部品牌资产与 rebrand 切换路径）。本批以
   「字标改内联、零 `/brand/` 依赖」规避。建议单独批次：`brandSubtree` 在 `DiskDist`
   脸下先查 dist 树的 `brand/`，再回落 classpath（`resolveStaticSource` 已有同源分派设施）。
2. **生产脸生效条件**：`build/web-dist` 是构建产物（`.gitignore:78` 不入库），本批改动要在
   实跑实例上看到效果需 `scripts/build-web.mjs` 重打 dist 后重启引擎（重启不在执行 agent
   的可为范围）。`after/` 证据为 dev 树渲染，与生产脸差异仅限静态资源 sourcing 路径，
   不影响内联 SVG / 几何 CSS 的渲染结果。
3. **emoji/字形图标债**：§4.1 共 17 类（约 38 行）待处置；本批只修启动卡，其余逐条登记。
4. **字标几何有两份内联副本，是有意为之**：登录卡走 `js/brand.js:155 standLockupSvg()`
   （源 = `stand-horizontal-compact-black.svg`，模块态可 import）；引导门字标走
   index.html 内联（源 = `stand-horizontal-black.svg`，`viewBox 1375.18×343.15`≡4:1，
   与 `--nfc-lockup-{h,w}` 同源）。引导门在模块图执行前就要画完、且守「零请求」纪律，
   不能 import；两者源文件不同（compact 与 full 两个官方导出），不是同一份资产的漂移。

## 7. 验收读数汇总

| 验收项 | 状态 |
|---|---|
| ① 修前/修后对照、浅深各一、动效取帧、桌面/手机各一 | before：作者原帧 + 四档整页 + 卡面特写（破图方框）；after：四档整页 + 卡面特写（同机位品牌字标）+ 动效两帧 |
| ② emoji 当图标全仓清单（位置/是否功能性/建议处置） | 本文 §4（119 处 / 33 文件） |
| ③ 启动/加载相关 spec 全绿 + 零 console error | `entry-veil` 31 断言 ALL PASS、`cards-family` 84 断言 ALL PASS、`console-takeover` 51 断言 ALL PASS、`boot-render-gate` 45 断言 ALL PASS；四档取样 console/page/HTTP 错误均 0 |
| ④ 真实合并 main 报 commit hash | 分支 `delegate/delegate-nebflow-f555a161` 提交，经合并队列落 main（hash 见交付报告） |

## 8. 词条

- **serving 面错位** — 同一个 URL 前缀在「谁提供字节」上分叉：页面与前端资产按
  `resolveStaticSource` 选中 disk dist（`build/web-dist`）或 classpath 树，而 `/brand/`
  子树**恒走 classpath**（`StaticRoutes.brandSubtree`）。新增品牌资产若只进 dist 引用侧、
  没进 classpath 内容侧，dev 树与服务树全绿、实跑实例 404——本批报障即此形态。
- **字形当图标（glyph-as-icon）** — 用字体里的字符（✓ × ✕ ☰ ✎ ▸ ⬇ 📎）充当 UI 图标。
  两类失效：缺字形环境回落成豆腐块/问号，以及同名字形随系统字体换形态（尺寸、基线、
  字重都不可控）。正确面 = 内联 SVG（`currentColor` 继承 token）或纯 CSS 几何。
