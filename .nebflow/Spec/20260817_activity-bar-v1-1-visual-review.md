# 视觉评审报告 · Activity Bar v1.1 实现

**日期**：2026-08-17 23:25 · **评审人**：design-engineer（四道防线 · 防线 3）
**对象**：`feat/activity-bar-v1-1` @17a652a5（worktree `/tmp/nb-activity-bar`，只读评审，未改动）
**依据**：`activity-bar-spec.md` **v1.2**（冻结版，含 [U1] 待办不进栏 / [U2] 图标序终审）+ nebflow/visual-style 铁律
**过渡态口径**：A2A 落地前 `#messages-btn`/`#contacts-btn` 「未注册不出现」，正确图标序 = **头像 → Files → 空隙 → Teams/Flows/Agents → Settings**（v1.2 A1 注）

## 结论：PASS（视觉 + 静态 DOM/代码三证交叉通过；运行时二值断言以 qa-frontend 并行结果为最终门槛）

---

## 逐条证据

| # | 规格条目（v1.2） | 结果 | 证据 |
|---|---|---|---|
| A1 | 图标序（过渡态口径）：avatar → files-btn → spacer → 沉底组；无 todos-btn / messages-btn / contacts-btn | ✅ | DOM 静态：index.html:41-53 `#activity-bar` 子元素序 = `#activity-avatar` → `#files-btn`(data-panel-btn="files") → `.activity-spacer` → teams/flows/agents/settings-btn；全仓 Grep 无 `todos-btn`/`messages-btn`/`contacts-btn`。运行时 nth-child 断言归 qa |
| §5/C1 + A9 | 激活态纯阴影无边条（铁律 5） | ✅ | CSS：nav.css:290-294 `.activity-btn.active` = `--color-frame-active` + `inset 0 1px 3px rgba(0,0,0,.08), 0 1px 2px rgba(0,0,0,.04)`；nav.css:289 显式 `border-left-width: 0`（含 C1 注释）。**像素取证**：light-expanded/dark-expanded 激活钮左缘 x=15-45 列扫描，无低方差深色/饱和竖线（亮主题 x=20-35 为均匀激活灰 RGB≈219，暗主题 ≈41,44,53；std 均 <20，仅 x=40+ 为图标字形）；collapsed 态左缘为底背景（253/22），无任何条。截图证据：ab-v11-{light,dark}-expanded.png |
| §3.1 hover | 玻璃 hover（铁律 2） | ✅ | CSS nav.css:270-279 `--glass-control-bg-hover` + blur + 立体边缘 + `--glass-control-border`。像素 diff（hover vs expanded）：变化区精确落在 files-btn 矩形 (19,142)-(94,218)，max channel diff=16 —— 克制小幅反馈，符合铁律 6（hover 打在 active 钮上，active 样式后写优先，小幅变化属预期） |
| §3.1 focus | `:focus-visible` 焦点环（§7 既有 `:focus{outline:none}` 补环） | ✅ | nav.css:281-282 `box-shadow: 0 0 0 2px var(--glass-control-border)` |
| §3.1 pressed | `scale(0.97)` | ✅ | nav.css:283 `.activity-btn:active { transform: scale(0.97) }` |
| §3.2 + A8 | 折叠态 Activity Bar 常显 48px | ✅ | 截图 light/dark-collapsed：面板收起后栏完整可见；nav.css:181-185 `body.sidebar-collapsed #activity-bar { width:48px; opacity:1 }`。运行时 offsetWidth 断言归 qa |
| §3.2/§4 | 收展动画 0.32s / `cubic-bezier(0.32,0.72,0,1)` 零改动 | ✅ | 规格声明复用既有 `--panel-*` token；nav.css/base.css 未见新增动效值，无回归 |
| §4 + A13 | prefers-reduced-motion → #sidebar ≤0.01s | ✅ | nav.css:188-190 `@media (prefers-reduced-motion: reduce) { #sidebar { transition-duration: 0.01s } }` |
| §3.3 + A5/A6 | 首帧无闪烁恢复 + `sidebar_active_panel` 持久化 + 未注册 id 回退 files | ✅（静态） | index.html:36 inline script 首帧恢复 `sidebar-collapsed` + `__sidebarRestore.panel`；activityBar.js:136-139 `stored && sidePanels.has(stored) ? stored : 'files'` 回退路径在码。运行时断言归 qa |
| §9 + A7/A12 | ⌘B / header toggle 单一状态源 | ✅（静态） | main.js:2395-2410 两个入口均调 `toggleSideBar()`（activityBar 模块统一读写），不再各自 toggle class |
| §7 | `aria-pressed` 二值同步 | ✅（静态） | activityBar.js:115-128 `syncPanelDom()` 同步 `.active` class 与 `aria-pressed` |
| §8 i18n + A15 | `activity.files`=文件/Files、`panel.explorer`=文件浏览器/Explorer，无硬编码 | ✅（静态） | zh-CN.js:14-15 / en.js:14-15 双语言 key 齐全；i18n.js:66 挂 `panel-title-explorer`；截图 zh 界面显示「文件浏览器」。运行时切语言断言归 qa |
| §6 + A11 | 文件浏览器零回归 | ✅ | 截图 expanded：标题「文件浏览器」+ 三个动作图标（新建文件/夹、打开文件夹）齐全可见；index.html:63-68 `#explorer-section`/`#explorer-new-file-btn` 等在 `#panel-sessions`(data-panel-id="files") 内原样保留；spec §9 声明 explorer.js/sidebar.js/colResizer.js 零改动 |
| §2.3 [U1] | 无 Todos 槽位、无 badge | ✅ | DOM 无 todos 元素；截图无任何 badge 角标；css 无 `.activity-badge` 新增 |
| §6 边界 | 面板互斥（0 或 1 个 .panel.active） | ✅（静态） | `syncPanelDom()` 单 activePanelId 遍历赋值，结构上互斥；运行时断言归 qa |
| §10 375px | 窄视口增量口径 | ⏳ 归 qa | 五张截图均为 680px 宽，无 375px 证据；A10 增量断言由 qa Playwright 覆盖 |

## 视觉核对（vision 项）

- **亮/暗双主题**：active 态在两主题下均为「背景填充 + 柔和阴影」，无左缘条、无发光条；`.nav-agent.active` 的 drop-shadow 发光未迁移到面板钮（C1 注）✅
- **玻璃质感**：Activity Bar 独立玻璃卡、Side Bar 玻璃卡、hover 玻璃控件三层质感一致，克制无新色值 ✅
- **面板标题**：截图中「文件浏览器」为既有 `.explorer-title` 样式（比 `.panel-title` 的 muted 更深）——§6「文件浏览器零回归」+ §9「sidebar.css 零改动」明确此为保留项，非走样 ✅
- **Loading…**：explorer 树加载占位为既有内容，零回归范围内 ✅

## 证据缺口（非 FAIL 项）

1. **沉底组（Teams/Flows/Agents/Settings）不在五张截图取景内**（截图高 1000px，底部 70px 像素扫描 max-dev≈16 为玻璃本底纹理，无控件）：DOM 序已静态验证，运行时 nth-child 由 qa A1 覆盖。建议 Frontend 后续补全帧截图。
2. A2-A8/A10/A13/A15 的运行时二值断言全部依赖 qa-frontend 并行结果——本报告 PASS 为视觉+静态维度；qa 若任一 FAIL 则整体打回。

## 证据清单

- 截图：`/tmp/ab-v11-light-{expanded,collapsed,hover}.png`、`/tmp/ab-v11-dark-{expanded,collapsed}.png`
- 像素取证：激活钮左缘列扫描（无竖线）、hover diff bbox (19,142)-(94,218) maxΔ16
- 代码引用：index.html:36,41-53,63-68 · nav.css:181-190,254-294 · activityBar.js:50-145 · main.js:2395-2410 · locales zh-CN.js:14-15 / en.js:14-15 · i18n.js:66
