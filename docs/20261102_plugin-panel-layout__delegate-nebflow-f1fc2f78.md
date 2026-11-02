---
type: batch
status: done
related: "[[20261102_085500_mobile-two-stage-title__delegate-nebflow-1bae7308]]"
---

# 插件面板文字让位 + 展开卡操作区一行化（2026-11-02 08:46 作者令）

## 摘要（人话，20 行内）

作者说两句话：① 插件面板的正文被右上/左上那几颗悬浮圆钮压住了，「内容都要从按钮下开始」，只有往上滑的时候才允许从钮下面穿过去；② 展开的插件卡里，`toggle`、`卸载`、`Plugin.md` 三个件要收进一行，省纵向空间。

做了什么：

1. 让位这件事，正好撞上另一个在飞的批（「面板头两段式标题批」）——它已经把页名大标题摆成「盒底 64 = 返回钮底缘」。**没有覆盖别人的改动**：那个批先合（main `d5b9df5f3`），我再在其上加冕。
2. 让位责任归标题盒，我**不**再叠一条 72px 的让位（那会把首屏内容推掉 60px）。我把「钮簇几何」收成一组单源 token（`--mchrome-*`，= 钮顶 16 + 钮 48 + 呼吸 8 = 72），让标题内缩、胶囊左右内缩、canvas 标签钳位全从它派生，不许再写死像素。
3. 顺手修了真正的压字 bug：canvas 环标签「点活签展开全名」的宽度钳位原来是 `100vw − 96px`，375 宽下展开签跨 48→327，右端实打实压进层叠视角浮钮（311→359）。改成 `100vw − 2×72` 后跨 72→303，两端各余 8px。
4. 操作区一行化：`Plugin.md` 左对齐、`toggle` 与 `卸载` 右对齐，三者垂直中心差 **0.00px**（改前 31.5px），命中区都补到 44（改前卸载 22 / doc 20 高）。
5. **375 宽实测不需要降级**：三者占 224px，可用 321px，余 97px。
6. 机械断言写进了新的验收 spec（7 组、双引擎 × 双主题 × 全 25 页）；改前基线红 18 项、改后 285 项全 PASS，全在 `evidence/20261102_plugin-panel-layout/` 里，改前/改后同视口成对。

---

作者令原话（逐字）：「插件面板的 文字会被悬浮的按钮 遮挡，要求，内容都需要从按钮下开始。只有向上滑动的时候才允许从按钮下过。然后展开来的插件，toggle和卸载按钮和plugin.md放一行，节约空间。」

两件事，只改这两件：① 静止首屏的正文让位悬浮钮簇（滚动后才允许穿过）；② 展开卡里 `toggle` / `卸载` / `Plugin.md` 收进同一行，375 宽实测合规、热区不缩水。

## 1. 一句话结论

- **让位**：让位责任方 = 页名标题盒（[[20261102_085500_mobile-two-stage-title__delegate-nebflow-1bae7308|两段式标题批]] 已把它们摆成「盒顶 12 / 盒底 64 = 返回钮底缘」）。本批不再叠第二条 72px 让位，而是把**簇几何收成单源 token**（`--mchrome-*`，`--mtitle-inset` 改从它派生），并用机械断言把「25 个页壳 × 双引擎 × 双主题，静止帧零文字入钮命中区」钉死。滚动帧由标题盒收高 + 胶囊浮层承担，实测内容确实滑到半透明浮钮之下（且无任何不透明遮罩挡在钮带）。
- **一行化**：三者同排、垂直中心差 **0.00px**（改前 31.50px），命中区 **51×44 / 59×44 / 94×44**（改前卸载 22px、Plugin.md 20px 高），375 宽下占 **224px / 可用 321px（余 97px）** ⇒ **无需降级**。
- **顺带的真 bug（同类面板）**：canvas 环标签「点活签展开全名」的横向钳位原写 `min(100vw - 96px, 280px)`，375 宽下展开签跨 48→327，右端压进层叠视角浮钮（311→359）—— 就是作者那句「右上那颗层叠图标撑到统计行右侧」的同一类。钳位改 `100vw − 2×--mchrome-inset` 后签体跨 72→303，两端各余 8px。

## 2. 与在飞批的关系（纪律 1，必须先读）

本批开工时「面板头两段式标题批」（分支 `delegate/delegate-nebflow-1bae7308`）正在做同一片区域。**处理方式 = 不覆盖、不抢形状**：

1. 该批先合入 main（`d5b9df5f3`），本批再 merge main，冲突只在 `mobileLayer.css` 的两处：`--mtitle-inset` 与 `.mobile-page-body` 的 `padding-top`。
2. 裁定：**让位归标题盒**（该批的 `--mtitle-lead 12 + 盒高 52 = 盒底 64 = 钮底`，本身已满足作者令）。本批**不**把 72px 让位加回去（那会把首屏内容推掉 60px，正是该批消掉的浪费）。
3. 本批只做加冕：`--mtitle-inset: var(--mchrome-inset)`（同一读数不写两份字面量）、新增 `--mchrome-clear/--mchrome-inset` 簇几何族、canvas 签钳位改吃新 token、插件操作行合并、以及全页机械断言。
4. 结论：两批**无功能冲突**，先后顺序 = 两段式标题批 → 本批（实际已按此顺序落地）。

## 3. 契约（数值钉死面）

新增 token 全在 `css/mobileLayer.css` 的 `body.mobile-layer` token 单源块：

| token | 值 | 依据 |
|---|---|---|
| `--mchrome-top` | 16px | 悬浮钮统一批（2026-10-31 16:03）`top: calc(16px + safe)` |
| `--mchrome-btn` | `var(--mtap-lg)` = 48px | g2-fab 圆钮基准（`--mtap-lg` 既有档） |
| `--mchrome-breathe` | 8px | 钮下沿 → 正文首行的呼吸缝（作者「不得贴边到视觉拥挤」） |
| `--mchrome-clear` | `calc(top + btn + breathe)` = 72px | **= 顶栏控件高度 + 8px 呼吸** |
| `--mchrome-inset` | `var(--mchrome-clear)` = 72px | 左右钮带占位（横向钳位用） |
| `--mtitle-inset` | `var(--mchrome-inset)` | 改前是同值字面量 72px，现单源 |

插件操作行沉浸式指标（375×812，chromium 与 webkit 同值）：

| 件 | 视觉本体 | 命中区 | 行内 x |
|---|---|---|---|
| `Plugin.md` | 94×20 | 94×44（`::before` 补纵向） | 27（左缘，`margin-right:auto`） |
| `toggle` | 51×31 | 51×44（既有 `::before`） | 中段 |
| `卸载` | 59×22 | 59×44（`::before` 补纵向） | 右缘对齐 348 |

**占地实测 vs 可用**：`94 + 10 + 51 + 10 + 59 = 224px`，行可用 `375 − 12×2(padding) − 14×2(卡内距) = 321px`，余 **97px** ⇒ 三个控件一行在合规热区下**放得下**，未触发降级序列（卸载不需要收进二次菜单、不需要图标化）。

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `web/js/plugins.js` | 详情区 DOM：`Plugin.md` 入口自独立行移入 `.plugins-item-actions` 首件（同一 DOM，桌面同步一行） |
| `web/css/sidebar.css` | `.plugins-item-doc` 退役 `align-self:flex-start`、加 `margin-right:auto`（左缘贴齐，把 toggle/卸载推右）；操作行块注释记一行化口径 |
| `web/css/mobileLayer.css` | ① `--mchrome-*` token 族；② `--mtitle-inset` 改派生；③ `.mobile-page-body` 让位注释按合并裁定改写；④ `.mf-strip-chip.is-expanded` 钳位改 `100vw − 2×--mchrome-inset`；⑤ 插件操作行手机层热区 `::before` 补到 `--mhit` |
| `tests/mobile-chrome-clearance.spec.mjs` | **新增验收 spec**（427→600 行）：C1 全 25 页静止让位机械断言 / C2 滚动穿越 + 无遮罩 / C3 一行化 + 同心 + 热区 / C4 375 占地 / C5 零 console error / C6 桌面零触碰 / C7 canvas 展开签 vs 浮钮 |

## 5. 机械断言（不是口头验收）

`node tests/mobile-chrome-clearance.spec.mjs`（自足 harness：route 静态 + WS 夹具 + `/api` 夹具，零后端零 sbt；双引擎 × 双主题 × 375×812）。判据面：

1. **C1** 静止帧：控件簇 = 页壳内 `position:absolute|fixed` 的 `.g2-fab / .mh-chrome-btn / .mobile-back-btn / .mf-cv-btn / .dock-key`（含 `::before` 外扩命中盒）；正文 = 可见文本节点的 **Range 字墨矩形 ∩ 裁剪祖先**（不做裁剪会因 ellipsis 多报 100+px 假阳）；相交面积 > 0.5px² 即失败。
2. **C2** 滚动后：正文顶须越过钮带下沿，**且**钮带上方不得存在「覆盖半屏 + 背景不透明度 ≥0.98」的绝对/sticky 件（= 反「拿白条糊住按钮」）。
3. **C3** 三者 y 区间重叠 + 垂直中心差 ≤1px + 命中区 ≥44×44。
4. **C4** 行 `scrollWidth ≤ clientWidth`、三者命中宽之和 + 间距 ≤ 可用宽、按钮右缘不越行界。
5. **C6** 桌面 1280×800：`mobile-layer` 不激活、操作行仍一行、`Plugin.md` 在左 / toggle+卸载 在右、卡宽仍在桌面档。
6. **C7** 展开签字墨不越屏、不进左右浮钮命中盒。

**改前 / 改后同视口对照**（同一份 spec、同一台机、`MCC_WEB` 指向 `git archive main` 解包出的干净 web 根）：

| 判据 | 改前 main | 改后本批 |
|---|---|---|
| C3 三者同一行 | FAIL（`doc` 独立成行） | PASS |
| C3 垂直中心差 | FAIL `31.50px` | PASS `0.00px` |
| C3 命中区 ≥44 | FAIL（卸载 22px / doc 20px 高） | PASS 三者皆 44 |
| C7 展开签压层叠钮 | FAIL（INK 交叠 288px²） | PASS（0） |
| C6 桌面三者一行 | FAIL | PASS |
| C1/C2/C4/C5 | PASS | PASS（285 项全 PASS） |

## 6. 影响面与不变量

- **桌面零触碰**：全部新规则挂 `body.mobile-layer`（≤768px 才激活）；桌面唯一变化即本批**有意**的一行化（`Plugin.md` 从独立行并入操作行，同一行左缘、右侧两件对齐不变）。
- **手机交互标准不缩水**：`toggle` 仍 51×31（`--msw-*` 族未动）、热区 44；卸载/doc 视觉本体未动（59×22 / 94×20），只补 `::before` 纵向热区。间距走既有 `gap: 10px`（`sidebar.css` 原值），**未新造间距 token**。
- **未改**配色/字体/其它面板布局；除上述四文件外无新增 diff。

## 7. 测试（分块、各自独立进程）

**合入 main 后复跑（commit `5ed0b3940`，工作区 `/Users/kaiyu/Claude code/Stand`）**：

| 块 | 结果 |
|---|---|
| `node tests/mobile-chrome-clearance.spec.mjs`（双引擎×双主题×全 25 页） | **ALL PASS** |
| `npx playwright test mobile-p10-plugins + plugins-panel-itemize + plugins-panel-pure-plugins + sidebar-plugins` | 22 passed |
| `npx playwright test mobile-dock + mobile-pinned-search + mobile-topbar` | 35 passed |
| `node tests/mobile-panel-title.spec.mjs` | ALL PASS |
| `node tests/ime-guard.spec.mjs` | ALL PASS |

分支上（已合并 main、尚未合入 main 时）的同批读数为 21 + 36 passed + ALL PASS ×3，与上表一致。

| 块 | 命令 | 结果 |
|---|---|---|
| 本批验收 spec | `node tests/mobile-chrome-clearance.spec.mjs` | **285 PASS / 0 FAIL**（双引擎×双主题×全页） |
| 插件 suite | `npx playwright test tests/mobile-p10-plugins tests/plugins-panel-itemize tests/plugins-panel-pure-plugins tests/sidebar-plugins` | 19 + 3 PASS |
| mobile-dock | `npx playwright test tests/mobile-dock.spec.mjs` | 21 PASS |
| mobile-pinned-search / mobile-topbar | `npx playwright test tests/mobile-pinned-search.spec.mjs tests/mobile-topbar.spec.mjs` | 14 PASS |
| mobile-panel-title | `node tests/mobile-panel-title.spec.mjs` | ALL PASS |
| ime-guard | `node tests/ime-guard.spec.mjs` | ALL PASS |
| 视觉门禁 | `node scripts/visual-baseline.mjs --check --max-ratio 0.0005` | 8 项 DIFF —— **基线既有红**：在干净 main worktree 复现同样 8 项、同一 bbox（`app-empty`/`chat-messages`/`settings-llm-empty`/`bgtask-panel` 尺寸不一致），与本批无关；canvas 四场景 SAME |

**已复现的既有红（非本批）**：`plugins-panel-autosync.spec.mjs:521`「④ toggle.js contract」在干净 main worktree 同样失败（sidebar.css `nb-toggle` 节内既有 `#07c160` 字面量），本批未触碰该节。`make check-visual` 在本机 **Makefile:27 解析错**（`$(shell ...)` 未闭合，main 同样复现），故直接跑底层 `scripts/visual-baseline.mjs`，阈值口径同 `VISUAL_MAX_RATIO`。

## 8. 证据

`evidence/20261102_plugin-panel-layout/`：

- `before/shots/`（126 张，改前 main）、`before/run.log`（18 FAIL）
- `after/shots/`（126 张，本批）、`after/run.log`（285 PASS）
- 手机 375×812 亮/暗：`plugins-rest`（静止首屏）/ `plugins-scrolled`（向上滚动中途，内容穿过浮钮）/ `plugins-expanded-row`（操作行）/ `plugins-expanded`（整卡）/ `canvas-chip-expanded`
- 桌面：`desktop-{light,dark}-plugins-expanded.png`
- 全 25 页静止帧：`{engine}-{scheme}-{page}-rest.png`
- 零 console error（C5 全腿硬零，噪声过滤同族 spec 表）

## 9. 遗留 / 开放项

1. `check-visual` 的 8 项与 `plugins-panel-autosync:521` 是**既有红**，已在干净 main 复现；建议由各自所属批处理（本批不动它们的基线/断言）。
2. 本机 `make check-visual` 因 Makefile:27 语法错不可用（main 同样），建议单开一批修 Makefile 或改用 `node scripts/visual-baseline.mjs`。
3. `--mchrome-*` 目前只被两个消费点吃（标题横向内缩、canvas 签钳位）；后续若再出现「右侧浮钮压字」，应继续从该族取值，不要新写像素。

## 10. 校验

在干净 vault 上跑（本批分支工作区，含本篇 149 篇）：

```
$ python3 /Users/kaiyu/.stand/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py --root "…/worktrees/delegate-nebflow-f1fc2f78/docs"
wiki-docs 校验：通过（…/docs，149 篇文档，零违规）
```

主工作区（`/Users/kaiyu/Claude code/Stand/docs`）报 29 条 `orphan-doc`，逐条核对 **全部是未跟踪的本地遗留文件**（`design/design.md`、`actor-mailbox-pattern-review.md` 等，`git ls-files` 零命中）；干净 main worktree 上同一脚本 **零违规**，本篇不在违规清单内。该 29 条与本批无关，属工作区卫生问题（未跟踪的旧设计稿本子）。
