# send-btn 绿色身份强化 — 实施报告

- 任务：PR #44 三维评审（报告 `20260903_pr44-review.md`，节点 n-46e25f90）后续——按作者裁定（2026-09-03 18:54）把 logo 的微信绿 #07C160 强化到 #send-btn，玻璃材质标准不动。
- 分支：`sendbtn-green`（worktree `.nebflow/worktrees/sendbtn-green`，独占施工，未合并回 main——合并由下游节点在视觉验收 PASS 后执行）。
- 基点：main @ c0e67306（开工时 HEAD，已 merge；施工中 main 前进到 7710190c（askuser-refresh），已二次 merge，零冲突——该节点不触 input.css）。
- 改动 commit：**127e7faf**（input.css 单文件，54+/18−）；merge commit e3f1491c。

## 一、逐态定值表（暗 / 亮）

主题机制：`prefers-color-scheme` 媒体查询（跟随系统），`:root` 变量翻转；#send-btn 规则族此前无主题分值，本批新增 dark 媒体块做暗主题覆盖（亮=基础规则，暗=覆盖），与 input.css 既有模式一致（base.css :root 亮 + dark 媒体块）。

| 态 | 亮主题 | 暗主题 | 选值理由（一句） |
|---|---|---|---|
| enabled | bg rgba(7,193,96,**0.55**)，border rgba(7,193,96,0.30)，glow `0 1px 4px .25 + 0 0 12px .20` | bg rgba(7,193,96,**0.62**)，border rgba(7,193,96,0.34)，glow 同亮 | 0.42 在部分背景读作禁用（评审确认为真）；同 alpha 在暗底合成更暗（0.55→≈rgb(12,116,65) 暗海绿），暗主题 +0.07 才落到明确的 logo 绿区间（任务给定 0.55–0.65 内各选一值）；glow 0.2 级克制不喧宾 |
| hover | bg rgba(7,193,96,**0.68**)，border 0.40 | bg rgba(7,193,96,**0.72**)，border 0.44 | 玻璃族内继续加深一档（~0.7 级），alpha<1 + blur 仍在，禁实心达成 |
| active | bg rgba(7,193,96,**0.75**)，border 0.45 + `inset 0 1px 3px rgba(0,0,0,0.15)` | bg rgba(7,193,96,**0.78**)，border 0.48 + 同 inset | 比 hover 再深一档（显式声明 bg，原来 active 不声明 bg 依赖 hover 残留）+ 按压内阴影；仍半透明非实心 |
| disabled | bg rgba(7,193,96,**0.16**)，border 0.08，svg opacity **0.45** | 同亮 | 采纳 PR #44：淡绿（非灰）保住绿色系统语义且明确"暂不可用"；两主题同值——0.16 本就是极淡 tint，双底均可读 |
| disconnected | bg rgba(7,193,96,**0.16**)，border 0.08，svg **0.45**，`:hover` 守卫回 0.16 | 同亮 | 采纳 PR #44 + 保留其 `.disconnected:hover` 守卫（(0,1,2,0) 特异度压过 hover 0.68，防断连时 hover 变深绿误导可点） |
| frozen（守卫） | `#input-bar.frozen #send-btn[disabled] svg { opacity: 1 }` | 同（媒体查询无关） | 冻结态图标保持不淡化是原刻意设计（send = wake）；防新增的 `:disabled svg 0.45` 波及冻结态——上游评审明确提醒项 |

不动项（材质锚点）：`blur(8px) saturate(1.3)`、`inset 0 1px 0 rgba(255,255,255,.35)` 顶内缘、`inset 0 -1px 0 rgba(0,0,0,.08)` 底内缘、1px 细边框结构——全部保留；sapphire.css:158 与 #stop-btn 处的材质注释引用关系不受影响。

级联顺序自查：`:hover(1,1,0)` → `:disabled(1,1,0)` 源序在后，禁用钮被 hover 时淡绿胜出（与原序一致）；`.disconnected(1,1,0)` 在 `:disabled` 后（同值一致）；`.disconnected:hover(1,2,0)` 最高；暗主题媒体块在族尾，同特异度源序覆盖亮值；frozen svg 守卫 (0,2,2,1) 压过 `:disabled svg` (0,1,1,1)。

## 二、与 PR #44 原案差异对照（pr-44-send-btn @ 2f95bea6，只读参照）

| 态 | PR #44 原案 | 本批 | 裁定 |
|---|---|---|---|
| enabled | rgba(7,193,96,**0.92**) 近实心 + border 0.45 | 亮 0.55 / 暗 0.62 + border 0.30/0.34 | **否决其值，按裁定加深**（保玻璃，任务区间内自定） |
| enabled glow | 0 1px 4px .35 + 0 2px 10px .25 | .25 + 0 0 12px .20 | **收窄**（克制不喧宾） |
| hover | **#07C160 实色** | 亮 0.68 / 暗 0.72 半透明 | **否决实心，改为玻璃内加深** |
| active | **#06A952 实色** | 亮 0.75 / 暗 0.78 + inset 按压影 | **否决实心，改为再深一档 + 内阴影** |
| disabled | rgba(7,193,96,0.16) + border 0.08 + svg 0.45 | 同值照搬 | **采纳** |
| disconnected | 0.16 族 + svg 0.45 + `:hover` 守卫 0.16 | 同值照搬（守卫逻辑保留） | **采纳** |
| 冻结守卫 | 无（PR #44 未处理，其 svg 淡化会波及冻结态） | 新增 `#input-bar.frozen #send-btn[disabled] svg{opacity:1}` | **新增**（上游评审提醒项） |

## 三、改动清单

- `src/main/resources/web/css/input.css`（唯一文件）：
  1. `#send-btn` 基础规则（~L759）：bg 0.42→0.55、border 0.15→0.30、glow 重排（新增 0 0 12px 0.20 环境光），注释改写为 logo 绿裁定记录；
  2. `#send-btn:hover`（~L788）：bg 0.55→0.68、border 0.25→0.40、glow 微升；
  3. `#send-btn:active`（~L800）：新增显式 bg 0.75 + border 0.45，inset 按压影保留加深（0.12→0.15）；
  4. `#send-btn:disabled`（~L808）：灰 → 淡绿 0.16 族（PR #44 采纳）；
  5. 新增 `#send-btn:disabled svg { opacity: 0.45 }`；
  6. `#send-btn.disconnected` / `.disconnected svg` / `.disconnected:hover`（~L817-826）：灰 → 淡绿 0.16 族 + svg 0.45 + hover 守卫；
  7. 新增 `@media (prefers-color-scheme: dark)` 暗主题覆盖块（~L835）：enabled/hover/active 的 bg/border 暗值；
  8. 冻结区（~L228 后）新增 `#input-bar.frozen #send-btn[disabled] svg { opacity: 1 }` 守卫。
- 规则族外零触碰：`git diff c0e67306..sendbtn-green --stat` = input.css 1 文件。

## 四、取证截图（10 张，五态 × 双主题）

真实 input.css/base.css/sapphire.css（符号链入 harness）+ index.html 摘录的真实 #input-bar/#send-btn DOM 结构 + 真实 vendored lucide `createIcons()` 渲染图标；hover=page.hover、active=鼠标按住截取、disabled=置 disabled 属性、frozen=#input-bar 加 .frozen + 全钮 disabled（对应 input.css L216-231 真实冻结组合）；Playwright 一次跑通（未降级），每态附 computed-style 断言（bg/border/svgOpacity/blur）10/10 PASS：

- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-enabled-dark.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-enabled-light.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-hover-dark.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-hover-light.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-active-dark.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-active-light.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-disabled-dark.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-disabled-light.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-frozen-dark.png
- /Users/dev/.nebflow/docs/Nebflow/20260903_sendbtn-green-frozen-light.png

关键断言实测：frozen 态 svgOpacity=**1**（守卫生效，淡化未波及冻结态）；disabled/disconnected svgOpacity=0.45；全部态 bg alpha<1 且 blur=true（无实心、玻璃语言保留）。

## 五、回归结论

1. **check-js-types**：`node scripts/check-js-types.mjs` 纯 CSS 预期零变化，实测零变化——但注意该 gate 在 main 基线本来就是红的（基线漂移为 main 既有状态，非本批引入）：
   - 基线错误集（main @ c0e67306 与 @ 7710190c 相同）：`checkJs: 343 errors (baseline 326)`——flowAnim.js 新文件 7 错、flowMapTab.js 新文件 5 错、micOrb.js 新文件 2 错、orbSettingsUI.js 新文件 2 错、chat.js TS2339 7>6、总错误 343>326；
   - 本分支（merge 7710190c + 本批 CSS）与裸 main @ 7710190c 输出**逐字节一致**（临时 worktree 对照跑）→ 本批零新增。
2. **改动范围**：`git diff c0e67306..sendbtn-green --stat` 仅 input.css；规则族外零触碰。
3. **既有 spec 自查**：`ls tests/` + grep——涉 #send-btn 的既有 spec 仅 `tests/smoke.spec.mjs`（3 处，均为 `not.toHaveClass(/disconnected/)` **class 状态断言**，非视觉断言；纯 CSS 改动无法影响 class 逻辑）。该 spec 需运行中的 Nebflow 服务（默认 8080=宿主实例，本批纪律严禁触碰；隔离拉起需 sbt 全量编译+独立 NEBFLOW_HOME，与纯 CSS 改动不成比例）→ **未真实运行**，以 harness computed-style 断言（10/10 PASS）覆盖按钮状态验证。`tests/tmp-verify-friends.mjs` 命中的是 `.fm-send-btn`（flow-map 域，非本按钮）。合并进来的 `tests/askuser-refresh-survive.spec.mjs` 不涉 send-btn/input-bar 样式。既有 spec 均只读未改。

## 六、生效说明

本批未重建前端产物、未重启宿主（纪律禁止）：改动仅落 worktree 分支 `sendbtn-green`，**生效需下游视觉验收 PASS 后合并回 main + 前端产物重建 + 应用重启**。

## 七、遗留问题

1. 暗主题 enabled 0.62 合成色（≈rgb(12,128,70)）是否达到作者预期的"一眼 logo 绿"，建议视觉验收时重点看暗主题 enabled/hover 两张（玻璃后景随聊天内容变化，截图为代表性后景）；
2. 冻结态 send-btn 背景随本批从"灰玻璃"变为"淡绿 0.16"（`:disabled` 新族的自然结果，评审仅要求守卫图标不淡化）——若验收认为冻结态应保留灰底，需追加 `#input-bar.frozen #send-btn[disabled]` 背景覆盖（一行）；
3. main 上 check-js-types gate 基线漂移（343>326，含 4 个新文件错误）为既有债务，与本批无关，建议另行处理（`--update` 或补注解）。
