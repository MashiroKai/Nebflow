---
type: spec
status: active
---

# 移动端 UI chrome —— dock 悬浮与页面内悬浮条（mobile chrome spec）

> 作者裁定（2026-11-21 10:41 console，逐字）：
> 「手机端 dock 在其他页面都是悬浮效果，但主会话却是截断的——落成标准和规范，
> 进行自动化检查，都得是悬浮的」。
>
> 本文是**「移动端底部 dock（全局导航条）长什么样、以什么形态贴在屏幕上」的唯一规范
> 正文**：悬浮要求、禁形清单、与页面内悬浮操作条的分层与间距、垫层登记表。
> 可执行面 = `scripts/check-mobile-dock-float.mjs`（前端门禁链 `make frontend-gates` +
> CI + 单闸 `make mobile-dock-float`）；本文件 §1.3 / §3 两张表**是它的数据面**，
> 脚本逐行读。**改判据改本文，改登记也改本文**，脚本只是执行器。

## 0. 两个层次：dock ≠ 页面内悬浮条

作者说的「悬浮」在手机层里落在**两个不同层次**上，混为一层就会互相越界：

| 层 | 是什么 | 座位 | 出现面 | 例 |
|---|---|---|---|---|
| **L1 dock** | 全局导航条（五栏胶囊） | 视口底缘之上，浮在**所有**页面内容之上（`z 200`） | 恒在、跨页恒定 | `#mobile-dock` |
| **L2 页面内悬浮条** | 某页自己的浮件带（底右浮钮组 / 输入条 / 工具条） | 浮在 dock **之上**，与 dock 留呼吸缝 | 按页出现 | `.ta-fab-wrap` / `.fm-input-bar` / `#input-area` / `.proj-create-fab` / `[data-plugins-add]` |

两条铁律把两层隔开：**L1 恒悬浮、跨页零差**；**L2 的座位必须由 L1 派生**
（`--mobile-dock-clearance` = dock 高 + 呼吸缝 + 底内距 + 安全区），**禁贴视口底
（`bottom: 0`）**——贴底的浮条会落进 dock 带、被胶囊压住。

## 1. dock 悬浮（作者裁定 2026-11-21 10:41 的判据正文）

**判定一句话：dock 在**任何**手机层页面上都是同一枚悬浮胶囊 —— 内容从它背后穿过，
玻璃糊到真实内容；不存在「贴底直角」「被垫层截断」这类第二种形态。**

### 1.1 要求条目

| 号 | 要求 | 机械口径（闸读得到的那一半） |
|---|---|---|
| D-1 全页悬浮 | 所有页面（**含聊天根 / 消息页**）dock 形态逐值相同：胶囊圆角、左右底三边离屏边、浮于内容之上 | `#mobile-dock` 单源规则声明 `position: fixed` + `left/right: var(--dock-inset-x)` + `bottom: calc(env(safe-area-inset-bottom,0px) + var(--dock-inset-b))` + `height: var(--dock-h)` + `border-radius: var(--ds-radius-pill)` |
| D-2 禁截断变体 | 禁贴底直角（`bottom: 0` + 直角）、禁通栏满宽（`left/right: 0`）、禁任何页面把 dock 改藏 / 改形 | 同在 `#mobile-dock` 规则上禁 `bottom: 0` / `border-radius: 0` / `left: 0` / `right: 0` / `width: 100%` / `height: 100%` |
| D-3 单源 | dock 的几何只有一处正文（`css/mobileLayer.css`）；别处只许提供**材质**（毛玻璃名单） | `#mobile-dock` 的几何声明出现在任何**其它** CSS 文件 = 红；`css/glass.css` 只许 `backdrop-filter` / `background` 两类声明 |
| D-4 带区无垫层 | dock 身后到底缘那条带区**不得有任何不透明垫底层**——垫层会把「内容穿过玻璃」变成「内容被硬切」 | `#mobile-pages` 容器（含 `::before` / `::after`）零背景（`transparent` / `none` / 缺省）；容器是零交互壳，它一上色就盖住宿主聊天根 |
| D-5 页面全高 | 页面壳 `top: 0; bottom: 0` 铺满视口；禁把页面底缘收到 dock 上缘（收短 = dock 读起来在「页面外」，内容不再穿过玻璃） | `.mobile-page` 的几何规则必须 `top: 0` 且 `bottom: 0`，禁 `bottom: var(--mobile-dock-*)` 之类收短 |
| D-6 呼吸缝 | L2 与 dock 之间留 `--dock-gap`；输入卡底缘 = `--mobile-dock-clearance`（= dock 上缘 + 呼吸缝） | L2 浮条登记表（§1.3）逐行核：座位令牌必须是 `--mobile-dock-clearance` 派生，不是 `bottom: 0` |

### 1.2 本次报障的实锤根因（留档：为什么这条要求需要一道闸）

改前（`mobileLayer.css:867-877`）：`body.mobile-layer #mobile-pages::before` 是一条
`position:absolute; bottom:0; height: var(--mobile-dock-top); background: var(--color-bg)`
的**扁平不透明带**。它 z-index 压到页面 section 之下，本意是「收编根页的带区不
透出下层聊天根」。

- **有 `.live` 页时**：页面是全视口不透明面，天然盖住这条带 ⇒ 零影响（所以
  「其他页面都是悬浮效果」）；
- **聊天根（无 `.live` 页）时**：这条带就是 `#mobile-pages`（z 156）里唯一上色的东西，
  而 `#mobile-pages` 整层压在 `#chat` 之上 ⇒ 滚进该带区（375×812 视口 y750–812）的
  聊天气泡被**硬切遮住**，dock 的毛玻璃采样到的是平坦常量色 = 读起来「半透明实底
  覆盖 / 截断」（作者所见「主会话却是截断的」）。

像素证据（375×812 / 390×844 双档、亮暗双主题）：同一滚动位带区开/关，y799–843
逐行差 86–284 px；把该带底色改红，dock 带立刻跟着变红 ⇒ 它就是挡住内容的那个面。
**修法 = 删掉这条带**（页面全高不透明面本来就承底；聊天根因此真正透过），并把
「容器零背景」钉成闸的 D-4。

### 1.3 L2 浮条座位登记表（本表被 `scripts/check-mobile-dock-float.mjs` 逐行解析，禁改格式）

登记语义：`座位` 列必须是 `--mobile-dock-clearance`（或它的派生式）；写 `bottom: 0`
的浮条 = 落进 dock 带 = 红。加新浮条 = 先改本表再写 CSS。

| 浮条 | 选择器 | 归属页 | 座位（令牌） |
|---|---|---|---|
| 会话输入卡 | `.fm-input-bar` / `#input-area` | 会话页 / 聊天根 | `--mobile-dock-clearance` |
| 待办底右浮钮组 | `.ta-fab-wrap` | 待办根页 | `--mobile-dock-clearance` + `--ds-space-2` |
| 待办详情工具条 | `.ta-tool` | 待办详情页 | `--mobile-dock-clearance`（经 `--ta-band-bottom`） |
| 项目新建钮 | `.proj-create-fab` | 项目/看板页 | `--mobile-dock-clearance` |
| 插件加号 | `[data-plugins-add]` | 插件页 | `--mobile-dock-clearance` |
| 定时任务钮 | `.reminder-fab-add` | 定时任务页 | `--mobile-dock-clearance` |
| Ask 气泡钮 | `.ask-fab` | 看板页 | `--mobile-dock-clearance` |

### 1.4 垫层豁免登记表（本表被 `scripts/check-mobile-dock-float.mjs` 逐行解析，禁改格式）

登记语义：`body.mobile-layer` 下、**底锚（`bottom: 0`）+ 不透明底 + 不在 `.mobile-page`
之内**的规则，逐条具名登记（选择器 + 理由）。**表里空理由 = 红，缺表 = 红；登记的
选择器在 CSS 里不存在 = 陈旧登记 = 红**。`#mobile-pages` 容器本身**不可登记**——
容器零背景是 D-4 的硬判据，不是豁免位。

| 选择器 | 理由 |
|---|---|
| `.mobile-page` | 页面壳本体：全视口不透明面，dock 在它之上悬浮（D-5 的另一半） |
| `.mf-stack-view` | 看板页叠卡视口（页面体内自持面，非 dock 带区垫层；不撑到视口底缘） |
| `.mf-stack-view::before` | 叠卡视口遮罩垫（页内，收在视口边界内） |
| `.mf-deck-thumb .thumb-grid` | 叠卡缩略图的网格图案底（小卡内，非带区） |
| `.mf-deck-thumb .thumb-img` | 叠卡缩略图底色（小卡内，非带区） |
| `.mf-deck-snap` | 叠卡吸附面（小卡内，非带区） |
| `.fm-context-menu` | 长按/右键上下文菜单的**全屏动作表**（B 族，z 320）：那是模态接管整屏、有自己出口，不是「dock 身后的垫层」——它盖住 dock 是模态语义，不是截断 |

## 2. 机检

```
node scripts/check-mobile-dock-float.mjs              # 门禁（红/绿）
node scripts/check-mobile-dock-float.mjs --json       # 机器读
node scripts/check-mobile-dock-float.mjs --inventory  # 盘点（底锚不透明面 + dock 相关规则）
node scripts/check-mobile-dock-float.mjs --root=<dir> # 跑在另一棵检出上（改前/改后对照）
make mobile-dock-float                               # 单闸入口（= FRONTEND_GATES 成员）
```

- **F1**（D-1/D-2）：`#mobile-dock` 单源规则的五条几何判据逐条在场，且禁形清单零命中。
- **F2**（D-3）：`#mobile-dock` 的几何声明只许在 `mobileLayer.css`；`glass.css` 同类
  规则只许材质声明（别的文件出现几何 = 第二套在长）。
- **F3**（D-4）：`#mobile-pages` 容器（含伪元素）零背景 = 硬判据；另加「底锚不透明面
  必须登记」（§1.4）。
- **F4**（D-5）：`.mobile-page` 几何规则 `top:0` + `bottom:0`，禁收短到 dock 上缘。
- **F5**（D-6）：§1.3 登记表逐行的座位令牌必须在 CSS 里真实被该浮条消费。
- 改前/改后同一份脚本（`--root`）：**改前 = 2 红**（F3 容器上色 + F4 缺失/越界按实读），
  **改后 = 0 红**；真渲染读数 = `tests/mobile-dock.spec.mjs`（D13 胶囊几何 / D18 悬浮
  chrome 带）+ `tests/mobile-dock-capsule-shots.mjs`（带区无垫底、玻璃采样真实内容）。
