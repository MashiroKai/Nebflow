---
type: fix-report
status: published
date: 2026-11-02
scope: 引用牌文字图标改为复用 composer footer 引用入口键同一枚 SVG（web/js/quoteLogo.js 新增 QUOTE_ENTRY_ICON 唯一来源 + web/js/msgQuote.js 去掉内联 QUOTE_SVG 改为引用 + web/js/reference.js 注释口径 + 删除 QUOTE_LOGO_CANDIDATES A/B/C 死代码）+ 验收钉 tests/quote-icon-unify.spec.mjs、tests/quote-icon-unify-shots.mjs
---

# 引用牌文字图标 = footer 引用入口图标（2026-11-02 12:58 作者令）

## 摘要（人话）

- **为什么弃用自制 logo**：上一批（`[[20261102_124055_quote-iconify__delegate-nebflow-7297e18b]]`）
  为文字引用牌设计了 A/B/C 三个候选字形并默认落 A「双引号 + 双横线」。作者看了对比帧后
  裁定——**不要这枚新 logo**，直接用 composer footer 那颗引用入口按钮
  （`#quote-item-btn`）上已有的图标。理由很直白：那颗按钮本来就是「引用」这个动作的
  全球唯一入口图标，引用牌用同一枚，用户扫一眼就知道「这块内容是从别处引来的」，
  不必再学第二套符号。自制 logo 反而给同一语义造了第二种图形语言。
- **改了什么**：把 `#quote-item-btn` 用的那枚 SVG（lucide `quote` 双路径，此前内联在
  `msgQuote.js` 的 `QUOTE_SVG`）**上提**到 `js/quoteLogo.js`，成为唯一来源
  `QUOTE_ENTRY_ICON`。footer 引用键、footer 徽章引用钮、气泡引用牌
  （`.msg-quote-head .nf-qicon`）、composer 引用 chip（`.att-ref-icon .nf-qicon`）
  **四处都调这一份**——改一处四处同步，全仓零第二份 path 抄写（grep 实测：该 `d` 串
  字面量本仓只剩 1 处，`vendor/lucide.min.js` 是第三方库不算）。
- **清掉哪些死代码**：A/B/C 三个候选字形（`QUOTE_LOGO_CANDIDATES` 整块）、
  `QUOTE_LOGO_TEXT` 导出，以及取证脚本里读候选的那段逻辑，全部删除。grep 证据见下文
  「断言③」。历史对比帧 `evidence/20261102_quote-iconify/after/f7-text-logo-candidates.png`
  **保留不删**（证据不作废，只作历史记录）。
- **只复用形状、不复用颜色**：SVG 一律 `stroke="currentColor"`。footer 键吃 `.icon-btn`
  的色链（`--color-frame-text-muted`），引用牌吃引用牌自己的
  `--color-text-muted`，两者各自继承宿主，深浅主题自动跟随（断言④）。
- **尺寸零变化**：图标盒仍是 16px（chip 侧 18px），四类引用牌（图片缩略 / 文档色块 /
  文字图标 / 历史腿图片字形）同盒同尺寸；引用块高度与改前逐值相同（断言②，有改前
  纯净树实测基线）。
- 其它三类引用牌（图片真缩略 / 文档 `.fc-icon` 色族 / 历史腿图片字形）**逐项零变化**。

## 断言读数表（①-⑤，实测）

| # | 断言 | 改前 | 改后 | 判据 / 读数口 |
|---|---|---|---|---|
| ① | footer 键 与 文字引用牌 SVG `path d` | footer 2 条 path、引用牌 4 条 path（不同字形） | **两处逐字相同**（各 2 条 path） | `__quoteEntryIconShape().footer.d === .pill.d`；徽章引用钮同源 |
| ① | `data-icon` 标识 | 无 | `quote-entry`（footer / 气泡牌 / chip 三处同值） | `svg[data-icon]` |
| ① | path `d` 字面量在本仓的出现次数 | 2（msgQuote 内联 + 候选 A 独立） | **1**（仅 `js/quoteLogo.js`） | 全仓 grep（排除 `vendor/`） |
| ② | 气泡引用牌图标盒 | 16×16 | **16×16** | 四类引用牌逐一取 `getBoundingClientRect` |
| ② | 图标行高 / 摘要行高 | 16 / 35 | **16 / 35** | 改前纯净树 `git archive HEAD` 同 harness 实测基线 |
| ② | 文字引用牌块高 | 65 | **65**（Δ=0） | 同上（无附件带的牌，结构稳定） |
| ② | chip 侧图标盒 / chip 高 | 18 / 28 档 | **18 / 28 档** | `__chipIconShape()` |
| ② | 图标行代价（摘掉 head 的差值） | — | **≤22**（实测落在旧档内） | `__iconDelta()`（沿用上一批口径） |
| ③ | `QUOTE_LOGO_CANDIDATES` / `QUOTE_LOGO_TEXT` 残留 | 定义在 quoteLogo.js | **0 命中** | `src/main/resources/web` + `tests` 全扫 |
| ③ | 候选 A / B / C 各自特征 path 片段 | 3 处 | **各 0 命中** | 同上（字形级拦截，防改名换壳） |
| ③ | 历史帧 `f7-text-logo-candidates.png` | 存在 | **保留**（非产品用途，历史证据） | 文件仍在 `evidence/20261102_quote-iconify/after/` |
| ④ | 引用牌图标 `color` vs 引用牌块 `color` | — | **相等**（继承链成立） | `getComputedStyle` 双读，浅 `rgb(139,142,150)` / 深 `rgb(123,126,136)` |
| ④ | 引用牌图标色 == `--color-text-muted` 解析值 | — | **相等**（吃引用牌那条变量） | 变量探针 `span{color:var(--color-text-muted)}` |
| ④ | footer 键图标色 | — | == 按钮自身色链（**未改按钮外观**） | 同上 |
| ④ | 深浅主题图标色 | — | 两主题**不同值**（自适应实证） | 两腿各读一次后比 |
| ④ | SVG `stroke` 属性 | — | `currentColor`（两处） | 属性直读 |
| ⑤ | 图片引用牌 | `thumb` + `data:image/` + 摘要含 `[图片]` | **逐项相同** | `__quoteBlockShape()` |
| ⑤ | 文档引用牌 | `file` + `fcCls=pdf` + 角标 `PDF` | **逐项相同** | 同上 |
| ⑤ | 历史腿（无 preview） | `glyph` + 图片字形（无 `data-icon`） | **逐项相同**，且字形 ≠ 入口图标 | `svgId === ''` + `svgD` 与入口图标不等 |
| ⑤ | 文字引用牌跳转语义 | `ready` + `role=button` | **相同** | 目标行在场时读 `dataset.quoteJump` |

## 改动清单（文件级）

| 文件 | 改动 |
|---|---|
| `src/main/resources/web/js/quoteLogo.js` | 新增 `QUOTE_ENTRY_ICON_ID` / `QUOTE_ENTRY_ICON_PATHS` / `QUOTE_ENTRY_ICON` / `quoteEntryIcon()`；`quoteTypeIcon()` 的文字分支改用它；**删除** `QUOTE_LOGO_CANDIDATES`、`QUOTE_LOGO_TEXT` |
| `src/main/resources/web/js/msgQuote.js` | 删掉内联的 `QUOTE_SVG` 字面量，改为 `const QUOTE_SVG = QUOTE_ENTRY_ICON`（同名 re-export，既有调用点/测试零改动） |
| `src/main/resources/web/js/reference.js` | 仅注释口径（文字图标 = 入口图标），逻辑零改动 |
| `tests/fixtures/quote-message/harness.html` | 新增 `__quoteEntryIconShape()` 读数口；`__quoteBlockShape` / `__chipIconShape` 追加 `svgD` / `svgId` / `svgColor` / `boxColor` 等字段 |
| `tests/quote-iconify-shots.mjs` | F7 帧从「三候选对比」改为「文字引用牌图标 = footer 入口图标」对照帧（候选已删，不再渲染） |
| `tests/quote-icon-unify.spec.mjs` | **新增**，8 条断言（① ② ③ ④×2 ⑤ + console×2） |
| `tests/quote-icon-unify-shots.mjs` | **新增**，U1-U7 证据帧生成器（改前/改后双腿） |
| `evidence/20261102_quote-icon-unify/{before,after}/` | **新增** 9 + 14 帧 |

## 测试读数

| 套件 | 结果 | 备注 |
|---|---|---|
| `tests/quote-icon-unify.spec.mjs` | **8 passed** | 本批新增，①-⑤ + console 双腿 |
| `tests/quote-iconify.spec.mjs` + `tests/stackcard-quote.spec.mjs` | **10 passed** | 上一批的验收钉全绿 |
| `tests/quote-message.spec.mjs` | 12 passed / **3 failed** | B2-B4 / B5 / B11 —— **既有红**，改前纯净树逐条同红（见下） |
| `tests/mobile-gesture-tribunal.spec.mjs` + `tests/mobile-dock.spec.mjs` | 21 passed / **1 failed** | mobile-dock D14 unread badge —— **既有红**，改前纯净树同红 |
| `tests/ime-guard.spec.mjs` | **ALL PASS** | 独立脚本形态（17 项） |
| `tests/pop-footer-family.spec.mjs` | **ALL PASS** | 独立脚本形态（含 footer 引用钮在场） |
| `tests/quote-icon-unify-shots.mjs` | 改后 **8/8 PASS**、改前 **8/8 PASS** | 改前腿判据取反（那是红面），两腿都 PASS = 对照成立 |
| `tests/quote-iconify-shots.mjs` | 改后 **8/8 PASS** | F7 已改成「入口图标同源」帧 |
| `node scripts/check-circular.mjs` | 与改前**逐字节同值** | 两个 SCC（3 + 2）成员与边完全不变，`exit=1` 为基线既有 |
| `node scripts/check-js-types.mjs` | 21 errors —— 与改前**逐条同值** | 全部为基线既有文件（msgTokenChip / messages / perception / todoFace / todoStore），本批触及的 4 个文件零新增 |
| `node scripts/visual-baseline.mjs --check` | 8 项 DIFF —— 与改前**逐目逐值相同** | 见「已知红灯」 |

### 既有红灯（改前纯净树 `git archive HEAD` 复现，非本批）

- `tests/quote-message.spec.mjs` B2-B4 / B5 / B11：`chipText` 期望 `Nebula：`，
  实际 `主 Agent：` —— 发送方标签的既有基线差异，改前纯净树**逐条同红**。
- `tests/mobile-dock.spec.mjs` D14（unread badge `toBeHidden`）—— 改前纯净树同红。
- `node scripts/visual-baseline.mjs --check`：8 项 DIFF（app-empty ×2 / chat-messages ×2 /
  settings-llm-empty ×2 / bgtask-panel ×2 尺寸不一致），改前纯净树**逐目逐值相同**（宿主
  渲染基线漂移，非本批）。`make check-visual` 本机因 `Makefile:27` 的
  `unterminated call to function 'shell'` 不可用（基线既有，申报）。

## 证据帧（`evidence/20261102_quote-icon-unify/`）

| 帧 | 内容 |
|---|---|
| `before/u3-desktop-light-footer-vs-pill.png` / `after/u3-...` | **核心对照**：左 footer 引用键、右文字引用牌 —— 改前左右**不同**（右是候选 logo A：引号 + 双横线），改后左右**同一枚**（双引号双路径） |
| `u1/u2-mobile-{light,dark}-footer-vs-pill.png` | 手机 375×812 亮/暗，同一对照 |
| `u4-desktop-dark-footer-vs-pill.png` | 桌面暗主题同一对照（`currentColor` 随主题） |
| `u3/u4-desktop-{light,dark}-pill.png` | 引用牌实样（色上下文实证） |
| `u5-four-pill-kinds.png` | 四类引用牌并排：图片缩略 / 文档 PDF 色块 / 文字图标 / 历史腿图片字形 —— 图标盒同为 16px |
| `u6-text-pill-icon-zoom.png` | 文字引用牌图标 5× 放大 + 16px 实尺（改前是候选 logo A，改后是入口图标） |
| `u7-icon-identity.png` | 字形身份读数帧：两处 `path d` 串并排打屏 + 「逐字相同 = true/false」结论行 |

## 工作纪律与申报

- **只改这一件**：非目标 diff 已回退（跑回归时被顺手改写的
  `evidence/20261102_quote-iconify/after/*.png` 4 帧已 `git checkout` 复原；
  `evidence/20261102_053734_pop-footer-family/` 是既有 suite 的副产物，已删）。
- **无工作区外改动**；`node_modules` 在本 worktree 以符号链接指向主仓（不提交）。
- **console 噪声申报**：历史腿（无 preview 的图片引用）会走既有取字节链
  （`/api/nf-file`、`/api/nf-ticket`），静态 harness 无这两条后端路由 ⇒
  `Failed to load resource` 501/404。**改前纯净树实测逐条相同**，非本批引入；
  验收网把它单列为一条断言（只允许资源加载噪声，脚本 error 仍是红）。
- **在飞同区域批**：扁平按钮组件库**未落地**（`js/` 下无按钮组件模块，`footer` 那颗仍是
  `.icon-btn` 原生 `<button>`）⇒ 本批以 `.icon-btn` 上的内联 SVG 为准；若将来它被收编进
  组件库，`QUOTE_ENTRY_ICON` 只需改一处（这正是本次上提的目的）。
  `todo-label-interactions` 批未触及 `msgQuote/reference/quoteLogo`（`todo-face.css` 与
  本批文件无交集），零冲突。

## 假设（显式声明）

1. 作者所说的「footer 那颗引用图标」= `#quote-item-btn`（composer 动作行里由
   `initQuoteItemButton` 装配的那枚 `.icon-btn`）当前实际渲染的 SVG；它与 footer 徽章上
   的 `.msg-quote-btn` 本就是同一枚（同一个 `QUOTE_SVG` 常量），两处同源无歧义。
2. 「同一个图标」按**形状**判（path `d` 逐字相同 + `data-icon` 标识相同），不要求颜色
   相同 —— 任务书明确「只复用形状，不复用颜色」。
3. 历史帧 `f7-text-logo-candidates.png` 属历史证据，非产品代码，不列入「死代码清零」。

## 关联

- 上一批（本批修正的图标化批）：`[[20261102_124055_quote-iconify__delegate-nebflow-7297e18b]]`
- 叠卡单张引用一期（footer 入口的出处）：
  `[[20261102_103000_stackcard-ref-and-synccard-center__delegate-nebflow-def82876]]`
- 附件引用 chip 的先例：`[[20261101_233954_composer-attach-icon__delegate-nebflow-4251fd3d]]`
