---
type: spec
status: active
---

# 面板设计语言（panel spec）—— 面板族唯一版式 · 唯一标准

> 作者令（contact-panel-unify 链 · 2026-11-06，逐字）：
> (c)「面板宽度与形态：不要做成弹窗，要运用整个面板的宽度」；
> (d)「按钮去重：不要保留功能重复的按钮，比如已经有返回键了，就不要再放叉号键（X）」；
> (e)「规范统一：顶部文字大小等面板设计，可以参考项目面板，统一整理出一套面板的设计语言」。

本文是**「面板设计语言」的唯一落点**：面板的顶部标题、行高节奏、分组间距、按钮规格、
分隔线、危险项配色、滚动吸顶、宽度形态、按钮去重——谁听到「面板该长什么样」，答案就在
这一页。可执行面 = `scripts/check-panel-unify.mjs`（前端门禁链 `make frontend-gates` +
CI + 单闸 `make panel-unify`）。**改判据改本文，改豁免也改本文**，脚本只是执行器。

令牌单源 = `css/design-system.css` §⑲ `--ds-panel-*` 段（值全部 = 既有刻度的别名；
唯一新数值 = `--ds-panel-title-lh: 1.4`，作者令逐字 15px/600/1.4 档，按
`--ds-fs-bubble 14.5` 具名例外槽先例出生）。

## 0. 面板分类（三类表面，一套语言）

| 类 | 表面 | 例 |
|---|---|---|
| P-A 页化面板 | 覆层铺平 / 画布页签 pane / 手机页壳——占满所在表面，内容自体滚动 | 好友·群信息面板（`#profile-face-overlay .pf-modal`）、项目面板（`.canvas-tab-pane[data-type="projects"]`）、执行器面板（type=executors） |
| P-B 弹窗头面板 | 玻璃卡但头部 = 面板标题条（cfg-modal 族） | 设置模态、自我资料卡（settingsIdentity）、dropbox、规则 |
| P-C 手机页 | `body.mobile-layer` 页面壳（大标题 + 悬浮返回钮） | 手机消息/联系人/后台页 |

同一类面板只许一套版式；三类共用同一套标题/行距/按钮/分隔线令牌。

## 1. 顶部标题（P1 判据：同类面板标题只能取规定令牌，裸字面量即红）

标题字重一律走 `--ds-fw-*` 档（禁 700 / 禁 300，`--ds-fw-title` 同规）；行高只有
标题条声明 1.4 档，胶囊/卡名行不声明行高（—）。

### 1.1 面板标题登记表（本表被 `scripts/check-panel-unify.mjs` P1 解析，禁改格式）

| 槽 | 选择器 | 表面 | 字号令牌 | 字重令牌 | 行高令牌 |
|---|---|---|---|---|---|
| T1 | `.cfg-modal-title` | cfg-modal 族面板标题条（设置 / 自我资料卡 / 好友·群信息 / dropbox / 规则…） | `--ds-panel-title-fs` | `--ds-panel-title-fw` | `--ds-panel-title-lh` |
| T2 | `.canvas-header` | canvas 页签胶囊头（项目面板 / 执行器面板等画布面） | `--ds-fs-subtitle` | `--ds-fw-btn` | — |
| T3 | `.flow-agent-name` | 后台智能体 / Flow 弹窗头身份行 | `--ds-fs-body` | `--ds-fw-title` | `--ds-lh-title` |
| T4 | `.executor-card-name` | 执行器卡名行（后台智能体面板） | `--ds-fs-body` | `--ds-fw-title` | — |
| T5 | `.pf-hero-name` | 好友 / 群信息面板主体名 | `--ds-fs-title` | `--ds-fw-title` | — |

登记行的语义：① 该选择器在 CSS 里必须存在（陈旧登记 = 红）；② 表内三令牌列（非 —）
的声明必须出现在命中规则里，且值**逐字等于令牌**（`var(--ds-panel-title-fs)` 形态；
裸字面量 / 借别的令牌名 = 红）。加新面板标题 = 先改本表再写 CSS。

## 2. 行高节奏与间距

| 项 | 令牌 | 值 | 用在哪 |
|---|---|---|---|
| 点面行高节奏 | `--ds-panel-row-h` | 48px（= `--ds-hit-tap-lg`） | 导航行 / 开关行等整行可点行（`.pf-nav` / `.pf-switch`） |
| 分组间距 | `--ds-panel-group-gap` | 16px（= `--ds-space-4`） | 面板正文单列分组流（`.pf-fields` 的行间） |
| 面板左右内距 | `--ds-panel-pad-x` | 16px（= `--ds-space-4`） | 行起笔 / 收笔离面板缘（hero / fields / note） |

参考面 = 项目面板：卡片列表留白分隔、无分隔线、无卡片盒；面板正文一律同一节奏。

### 2.1 与设置页 iOS 行语言（--set-* 族）的划界（dispatcher 补令 2026-11-06）

**面板 ≠ 设置行**：main 已落 settings-ios-rows 批（`js/settingsRows.js` ×
`css/settingsRows.css` × `--set-*` 令牌段：设置页 iOS 行高 44/50、chevron 盒 20、
图标盒 20、间隙 12、iOS 分组卡）。本规范的 `--ds-panel-row-h` 48（点面行）管的是
**面板正文的整行可点行**（导航 / 开关行，profile / canvas 族面板）；`--set-*` 管的是
**设置页 iOS 行**。两类表面不同档（48 点面 vs 44/50 设置行），互不收编、互不重复出生：
设置页里的行**不**进本规范登记表，面板里的行**不**改用 `--set-*`。若 rebase 后某一面
同时消费两族，以表面归属定档（面板面读本表、设置页读 --set-*），禁为同一几何发明
第二套令牌。

## 3. 按钮规格

- **扁平**：不靠阴影 / 浮雕 / 高光——判据与例外 = `flat-button-spec.md`（按钮面六态同规）。
- **圆角**：面板档按钮 `--ds-radius-button`（10px）/ 输入件 `--ds-radius-input`（8px）/
  chip `--ds-radius-chip`（6px），8 档阶梯（`design-language-tokens.md` §2.1）。
- **focus-visible 环**：键盘焦点必须可见——`outline: 2px solid var(--ds-focus-ring);
  outline-offset: 2px`（组件档先例 `.ds-turn-btn:focus-visible`）；输入件走
  `--nf-field-ring` 族（`input-surface-spec.md` 单源）。禁裸 `outline: none` 不留替代。
- **危险项配色**：红单源 = `--color-danger`（`base.css`，亮 `#c0392b` / 暗 `#f26b6b`）；
  按钮族同一红的令牌组 = `--btn-danger-ink` / `--btn-danger-stroke`；面板行级危险项
  字 / 图标取 `--ds-panel-danger-fg`（§⑲ 槽，= `--color-danger` 别名）。

## 4. 分隔线

默认**无分隔线**（留白分隔，参考面 = 项目面板卡片列表）。密排行列表确需分隔时 =
`1px solid var(--ds-panel-sep)`（= `--color-frame-border-light` 别名，§⑲ 槽）。
字段行 / 导航行画 border 分隔 = 红判据（真渲染腿：`.pf-fields` 行级零分隔线零卡片盒，
`tests/friend-info-panel.spec.mjs` P2 已钉）。

## 5. 面板宽度与形态（3(c) 判据）

**不要做成弹窗，要运用整个面板的宽度。** 机械口径：

1. 禁「居中窄卡 + 遮罩」形态：面板主容器不得写死窄宽度再由覆层居中
   （改前 `.pf-modal { width: 420px }` 即此形态，已退役）。
2. 手机层（`body.mobile-layer`，375 视口）：全宽 = `mobileLayer.css`「A 族」既有铺平
   规则（width/height 100%）。
3. 桌面层（1440 视口）：面板内全宽 = 撑满所在表面（覆层 fixed inset 0 → 面板
   100%×100%；覆层底 `--overlay-bg` 全透明 ⇒ 无压暗遮罩、无居中卡）。
4. 实测读数（`tests/panel-language.spec.mjs` W2/W3）：`.pf-modal` 宽度 = 所在容器
   （`#profile-face-overlay`）宽度——375 档 375/375、1440 档 1440/1440；
   改前读数 = 420/1440（窄卡）与 375/375（手机层 A 族既有）。

## 6. 滚动与吸顶

- **画布族面板**（项目面板等）：头带让位令牌 `--panel-head-clear`（单行档，56）/ 
  `--panel-head-2row`（自带返回条的双行档，102）——语义别名 `--ds-panel-head-clear` /
  `--ds-panel-head-2row`（§⑲ 槽；几何单源 = `projectPanel.css` :root 的
  `--cv-head-*` 派生，禁各面板另抄魔数）。滚动体让位 = 自身 `padding-top`（内容从
  头带下真穿过），不是遮罩。
- **页化弹层面**（好友·群信息等）：面板自体滚动（`overflow-y: auto`），标题条为
  流内行；当前四面内容单屏，不设吸顶带——加吸顶前先改本节。

## 7. 按钮去重（3(d) 判据：有返回键的面不许同时有 X 键）

一面一出口：手机层返回键（`mobileSheetBack` 注入的 `.mfs-back`）在 ⇒ **关闭 X 键不建**
（DOM 级，不是 CSS 藏）；桌面层无返回键 ⇒ X 键保留。判定单源 =
`popSheet.isMobileLayer()`（禁各面板自写 `body.classList` 检查）；守卫与创建必须相邻
（机检 P2：锚形出现行 ±3 行内必须见 `isMobileLayer(`）。删键后可达性对账见登记表
「删后可达性」列——Esc / 点外 / 返回键三条路逐面如实核。

### 7.1 按钮去重登记表（本表被 `scripts/check-panel-unify.mjs` P2 解析，禁改格式）

判定档：`dom-guard` = 模块调 `mobileSheetBack` + 锚形恰好 1 处且 ±3 行内有
`isMobileLayer(` 守卫；`css-hide` = `mobileLayer.css` 必须仍有该键的手机层隐藏规则
（沿袭 2026-10-30「重复返回钮清查批」机制，键常驻 DOM 由页面壳统管）；`record` = 记录行。

| 面 | 模块 | 返回键 | 关闭键锚（创建形态） | 判定档 | 删后可达性 |
|---|---|---|---|---|---|
| 好友信息面板 / 群信息面板（同一实现） | `js/profileFace.js` | mobileSheetBack | `'pf-close'` | dom-guard | 手机层：返回键 ✓ / Esc ✓ / 点外 ✓；桌面层：X ✓ / Esc ✓ / 点外 ✓ |
| 后台智能体弹窗 | `js/bgAgentPopup.js` | mobileSheetBack | `id="bgagent-close"` | dom-guard | 手机层：返回键 ✓ / 点外 ✓（✕ 原为 div，本就不可键盘达）；桌面层：✕ ✓ / 点外 ✓ |
| Flow 弹窗（后台智能体族） | `js/flowAgentPopup.js` | mobileSheetBack | `id="flow-agent-close"` | dom-guard | 同上（同族同款守卫） |
| 设置·自我资料卡 | `js/settingsIdentity.js` | mobileSheetBack | `id="settings-profile-close"` | dom-guard | 手机层：返回键 ✓ / Esc ✓ / 点外 ✓（壳只建一次 ⇒ open 时对账摘除）；桌面层：X ✓ / Esc ✓ / 点外 ✓ |
| 项目面板 | `js/projectTab.js`（画布 pane） | flowmap-nav-bar（链视图返回条） | 页签 × | record | 页签 × 关**页签**、返回条导**视图内**返回——功能不同，非重复，双保留 |
| 执行器面板 | `js/executorsPanel.js`（画布 pane） | 无（页签切换） | 页签 × | record | 页签 × 即出口，面内无第二关闭键 |
| 搜索面板 | `js/chatSearch.js` | 页壳返回（收编页） | `search-modal-close` | css-hide | 手机层：页壳返回 ✓ / Esc ✓（X 由 2026-10-30 批隐藏；搜索面归后续节点，本批不动） |
| 设置面板（设置模态） | `index.html` 壳 + `js/sidebar.js`（关闭接线） | 页壳返回（收编页） | `settings-modal-close` | css-hide | 手机层：页壳返回 ✓ / Esc ✓（X 由 2026-10-30 批隐藏） |
| 转发窗 | `js/messages.js` | 无（浮面板） | 无 X | record | 取消键 ✓ / Esc ✓ / 点聊天窗收口；chip 上 × = 移除已选项，非关闭键，非重复 |

## 8. 例外清单（豁免必须有理由；陈旧例外 = 红）

| 选择器 | 判据 | 理由 |
|---|---|---|
| `.daemon-config-close` | 7 | daemonPanel 守护进程配置模态与手机返回键并存，同款重复——不在本批四面对照表内，留待面板族收编批统一 dom-guard（机检 --inventory 可见，未登记不判红） |
| `.dropbox-close` | 7 | dropbox.js 模态 X 与手机返回键并存，同款重复——留待面板族收编批统一 dom-guard |
| `.nf-lightbox-close` | 7 | lightbox.js 看图浮层 X 与手机返回键并存，同款重复——留待面板族收编批统一 dom-guard |

## 9. 机检

```
node scripts/check-panel-unify.mjs              # 门禁（红/绿）
node scripts/check-panel-unify.mjs --json       # 机器读
node scripts/check-panel-unify.mjs --inventory  # 盘点（P1 命中 / P2 登记 / 未收编清查）
node scripts/check-panel-unify.mjs --root=<dir> # 跑在另一棵检出上（改前/改后对照）
make panel-unify                                # 单闸入口（= FRONTEND_GATES 成员）
```

- **P1**（§1.1 登记表）：登记选择器在 CSS 里必须存在；字号 / 字重 / 行高令牌列的
  声明必须在场且值逐字 = 令牌（裸字面量即红）。
- **P2**（§7.1 登记表）：dom-guard 行 = 模块调 `mobileSheetBack` + 锚形恰 1 处 +
  ±3 行内 `isMobileLayer(` 守卫；css-hide 行 = `mobileLayer.css` 隐藏规则仍在场；
  record 行 = 登记即可（语义豁免在表内写明）。
- 改前/改后同一份脚本（`--root`）：本批**改前 = 7 红**——P1 ×3（T1 `.cfg-modal-title`
  字号借档 `--ds-fs-subtitle` + 裸字重 `600` + 行高缺声明；T4 `.executor-card-name` /
  T5 `.pf-hero-name` 裸字重 `600`）+ P2 ×4（`'pf-close'` / `id="bgagent-close"` /
  `id="flow-agent-close"` / `id="settings-profile-close"` 锚行无
  `isMobileLayer(` 守卫）；**改后 = 0 红**（5 槽 + 9 面 + 3 例外全绿）。真渲染读数 =
  `tests/panel-language.spec.mjs`（四面板标题 computed 读数 + 375/1440 宽度 +
  手机层无 `.pf-close`）。
