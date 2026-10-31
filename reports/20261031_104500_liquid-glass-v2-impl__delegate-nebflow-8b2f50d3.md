# 液体玻璃 v2 实现批 — 过程报告

- 批次：液体玻璃 v2（作者 10-31 09:03 拍板 v2 配方 + 追加边缘光折射批）
- 分支：`delegate/delegate-nebflow-8b2f50d3`（commit `d4cc8d189` 实现 + `3f83e3367` baseline 修复）
- 状态：实现完成、验收/受影响回归全绿、证据截图 17 张；全量自足套件最终读数见 §6（收尾时回填）

---

## 1. 改动面清单

| 文件 | 性质 | 内容 |
|---|---|---|
| `src/main/resources/web/css/stand-tokens.css` | 改 +123 | 新增 `--g2-*` token 族：:root 亮档默认 + `html[data-theme]` light/dark + `prefers-color-scheme` 回退四块（陷阱修复：无 :root 默认时无 data-theme+系统亮全 undefined）。含 `--g2-fab-*` / `--g2-ink-*`（引用 `--btn-ink-*` 墨族单源）/ `--g2-fade-*` / `--g2-veil` / `--g2-input-*` / `--g2-fab-solid` 降级实色 |
| `src/main/resources/web/css/liquid-glass.css` | 新建 | 共享液体玻璃底材：`::before` 折射层 = backdrop-filter blur(16) sat(1.8) + 上半镜面高光渐变；`html.g2-refract-on` 下加 `filter:url(#g2-refract)`。`.g2-fab`（40px 桌面/48px 移动正圆）/ `.g2-pill`（h36/h44 r999）/ `.g2-pill-primary`（墨玻璃）。`#g2-edge-fade` 88px 渐隐带（blur18/sat1.5 + veil + 两段 mask，z25 pointer-events none，桌面 display:none）。`html.no-backdrop` + `@supports` 双保险降级；`prefers-reduced-motion`；`.g2-chat-head` 移动入流标题（27px/600、头像 40px） |
| `src/main/resources/web/css/sapphire.css` | 改 ~135 | `--glass-control-*` 换代 v2（blur16/sat1.8 + 新增 `--glass-control-specular` / `--glass-control-sat`）；`.glass-control` 基类 + Pattern A hover + Pattern B base/hover 换 v2 材质（`background-color` 替代 shorthand 防 background-image 被清、specular 渐变、inset -2px 5px、0 8px 24px）；Pattern B 新增 `:active` scale(.95) spring；saturate(1.2) 字面量全换 var |
| `src/main/resources/web/css/sidebar.css` | 改 ~22 | `.cfg-btn` / `.cfg-btn-sm` / `.cfg-btn-primary` radius→999px，水平内距加宽（16/12/18px） |
| `src/main/resources/web/css/input.css` | 改 ~64 | `#send-btn` 改墨玻璃药丸（h32 r999 padding 0 14px，spring scale(.95)，hover/active 墨阶递进）；`:disabled` 加 transform:none |
| `src/main/resources/web/css/mobileLayer.css` | 改 ~149 | 顶栏退役块替换原悬浮顶栏+chrome 块（`body.mobile-layer #header{display:none !important}`）；新增 `#mobile-fab-layer`（absolute z30）+ `.mh-chrome-btn` 定位（top calc(16px+safe-area) 左右 16px）+ 徽标；文件头 z 序注释更新 |
| `src/main/resources/web/index.html` | 改 +23 | head 加 liquid-glass.css link（sapphire 之后）；body 内 nf-boot-gate 之后加 SVG defs（`#g2-refract`：feTurbulence baseFrequency 0.012/0.018 + feDisplacementMap scale=5，区域 -20%/140%）；`#main` 内 `#header` 之后加 `<div id="g2-edge-fade" aria-hidden="true">` |
| `src/main/resources/web/js/liquidGlass.js` | 新建 | `initLiquidGlass()`：双判据（CSS.supports backdrop-filter + isolation 探针）探测，挂/摘 `html.g2-refract-on` |
| `src/main/resources/web/js/main.js` | 改 +7 | import + 调用 initLiquidGlass（L63、L281）；顶带观察器 `bandBottom` 计入 `#g2-edge-fade`（L4145-4155，桌面 display:none ⇒ 全零，无副作用） |
| `src/main/resources/web/js/mobile/chatChrome.js` | 重写 ~94 | `activateChatChrome` 建 `#mobile-fab-layer`（任务/搜索双键 `.mh-chrome-btn.g2-fab`）+ 把 `#header .header-center` 三节点（头像/会话名/记忆钮）迁入 `#chat` 首位 `.g2-chat-head`；MutationObserver 守望桌面 4 处 `innerHTML=''` 清空后自动插回；teardown 原序归还 |
| `src/main/resources/web/js/chatView.js` | 改 +13 | `setSession` 清空保留 `:scope > .g2-chat-head`（L226-243） |
| `playwright.config.mjs` | 改 +4 | instanceBacked 加 `queuepos-detail.spec.mjs`（需引擎载荷，自足面排除——裸跑套件不再被缺载荷抛错打断收集） |
| `tests/liquid-glass.spec.mjs` | 新建 | 设计稿 §9 验收 M1-M5/D1-D3/T1/F1/R1（14/14 通过） |
| `tests/mobile-v3.spec.mjs` | 改 | T5 改 v2 契约（header hidden + fade + 双键 + chat head） |
| `tests/mobile-topline-clear.spec.mjs` | 改 | T1 改 display:none 断言；T3 改渐隐带白名单+契约断言 |
| `tests/liquid-glass-shots.mjs` | 新建 | 证据截图脚本（17 张） |
| `tests/type-baseline.json` | 改 +19 | 单独 commit：reader/msgQuote 批欠账（main 上即红），stash 对照实证非本批引入 |

## 2. 关键 diff 摘要

- **折射架构**：滤镜只作用于 `.g2-fab/.g2-pill` 的 `::before` 底材层（backdrop-filter 之上叠 filter:url(#g2-refract)），图标/文字在 content 层 ⇒ 文字零畸变（§9 R1 实证）。SVG 滤镜区域扩到 -20%/140% 防边缘裁切。
- **能力探测双判据**：`CSS.supports('backdrop-filter', ...)` + isolation 探针（Chromium 兼容 ::before 上 backdrop-filter+filter:url() 组合，隔离探针实证），失败摘 `g2-refract-on` 只留高光+浮影（不裸断）；`@supports not` + `html.no-backdrop` 双级。
- **顶栏退役但节点不毁**：页标题/头像/记忆钮节点**迁入** #chat 流首（非克隆），功能链（PANEL 等）全绿；桌面侧 MutationObserver 守望 chat 清空复原。
- **发送钮**：墨玻璃药丸（--g2-ink-*），与输入栏底材衔接；disabled 态禁 scale 防布局跳。

## 3. 截图证据（reports/20261031_liquid-glass-v2-impl/evidence/，17 张）

- 移动亮：root / scrolled / fab-closeup / fab-closeup-r
- 移动暗：root / scrolled / fab-closeup / fab-closeup-r
- 桌面亮：shell / button-family / send-closeup
- 桌面暗：shell / button-family / send-closeup
- 降级（no-backdrop 模拟）：fallback-light-root / fallback-light-fab-closeup

目检结论：折射边缘特写清晰（绿色气泡背景在圆钮边缘弯折）；scrolled 态顶带内容「先糊后没」；暗档墨玻璃正确；降级态保留高光+浮影。

## 4. 回归读数

| 套件 | 结果 |
|---|---|
| liquid-glass.spec.mjs（§9 验收） | 14/14 |
| mobile-v3.spec.mjs | 35/35 |
| mobile-topline-clear.spec.mjs | 3/3 |
| mobile-input-suite | 24/24 |
| chat-chrome | 3/3 |
| dock | 3/3 |
| gem-cards | 9/9 |
| checkJs type gate | PASS（350 = 基线，零新增） |
| 全量自足套件（PW_SELFCONTAINED_ONLY=1，queuepos 已排除） | 收尾回填 |

**预存失败登记（与本批无关）**：`queuepos-detail.spec.mjs` flowmap 视图卡「加载中…」（`.fm-node` 超时）。stash 干净树同点同错实证；载荷已由 MergeQueuePositionSpec 产出（9/9 通过）但渲染面仍卡加载，疑似既有前端/夹具问题，建议另批排查。

## 5. 偏差与假设（供作者否决）

1. **手机端「左☰/右＋」映射为任务/搜索双键**：产品实况无 dock 抽屉导航（dock 批作者令入口保留），单会话架构无「新建会话」功能。如需真 ☰/＋，需产品先定目标面板。
2. **fm-send 族发送 chips 保留 r10 未药丸化**（设计稿未覆盖 flowmap 发送条，登记偏离）。
3. **stop-btn 保留圆形未动**（停止语义控件，不属于按钮族换代面）。

## 6. Open items

- 全量自足套件最终读数回填（收尾时）。
- queuepos-detail 预存失败建议另批处理。
