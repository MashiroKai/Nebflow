> **迁址说明（2026-10-31 stand-dev 合并批）**：本文 = 原 `frontend-dev` 插件 `skills/nebflow-frontend/SKILL.md` 全文迁入，能力零删改；文内出现的自包根相对路径（`skills/...`、`scripts/...`、`assets/...`）为原包内路径，对应物现位于 `skills/stand-dev/` 下（脚本 `scripts/`、资产 `assets/`）；跨包引用（原各 nebflow-* 插件名）已改写指向 stand-dev 对应章。深读顺序：先读章导览（`skills/stand-dev/SKILL.md`）。
>
> **章内衔接（2026-11-01 二批并入）**：新 UI/交互的实现以上游**设计章** `design-spec` 的设计规格书为唯一依据（防线 2 规格化实现——规格书未定义之处回问，不自由发挥）；设计规格书方法见 `references/design/design-spec.md`。

---
name: nebflow-frontend
description: Nebflow 客户端前端开发域知识——web/ 无构建 vanilla JS 结构、Sapphire Glass 设计系统（CSS 变量/玻璃组件/双主题）、设计原则（层级/间距/字重/一致性/打磨）、硬约束（无 emoji/无 accent bars）、截图驱动验证流与响应式三档。Use when 修改 src/main/resources/web/ 下的 JS/CSS/HTML。
when_to_use: Nebflow 客户端 UI 实现（布局/组件/交互/主题）。与同章 visual-style（用户裁定硬规则，最高优先级）和 stand-dev qa 章 frontend-verification（自验方法论）分层配合：本 skill 管结构与实现规范，visual-style 管视觉裁定，frontend-verification 管怎么自验。
language: zh
status: active
---

# Nebflow Frontend — 客户端前端开发域知识

## 技术标准

- **无构建步骤**：JS/CSS 直接加载，无 bundler、无转译器；vanilla JS（ES modules、optional chaining 等现代特性），无框架。
- **CSS**：所有颜色/尺寸用 custom properties；布局用 flexbox/grid。
- **HTML**：语义标签（`<nav>`、`<main>`、`<aside>`、`<dialog>`）。
- **无障碍**：ARIA labels、键盘导航、焦点管理；可点击元素 `cursor: pointer`。
- **性能**：无不必要 reflow；scroll/resize handler 用 debounce。
- **XSS**：动态文本用 `textContent`，不用 `innerHTML`。

## 文件结构

```
src/main/resources/web/
  css/    base.css（根变量/重置）、sapphire.css（玻璃设计系统）、nav/chat/sidebar/modal.css…
  js/     功能模块（vanilla JS，无构建）
  index.html
```

新增子目录必须同步加服务端静态路由（http4s DSL 单段匹配）。

## Sapphire Glass 设计系统

### CSS 变量（禁止裸 hex，一律 `var(--color-*)`）

```
--color-bg        页面背景（#f5f6f8 light / #13161c dark）
--color-surface   卡片/面板表面
--color-text      主文本（#1b1e26 light / #e8eaed dark）——永不写 color:#fff
--color-text-muted  次要文本（仅限 caption，正文对比度不足）
--color-border    边框与分隔
--color-frame-*   导航/侧栏/弹窗表面
--sapphire        91 127 191（RGB 三元组，供 alpha 混合的 Sapphire 蓝强调色）
```

### 玻璃组件与主题

- `.glass` 类：backdrop-blur + 半透明背景；玻璃面板顶缘细折射线；阴影用堆叠（多个小偏移），不用单枚 drop shadow。
- 双主题经 `@media (prefers-color-scheme: dark)` 切换——改一个变量即全 UI 换肤；light/dark 两主题都要看起来是有意为之的，两主题都测。
- 用户视觉硬裁定（弹窗毛玻璃无 overlay 遮罩、控件玻璃质感、字重、阴影激活态）见同章 `visual-style`，与本 skill 冲突时以它为准。

## 设计原则

1. **视觉层级**：一眼知道先看哪——每视口至多一个彩色强调事件（Sapphire 蓝），其余灰阶；重要 = 更大/更多留白/更高对比。
2. **深度**：surface 阶梯（`--color-bg` → `--color-surface` → `--color-frame-*`）优先于阴影；1px hairline 边框做卡片主边缘。
3. **间距节奏**：4px 基数（4/8/12/16/24/32/48/64）；主章节间距 64-96px；留白宁多勿挤；对齐用精确值不目测。
4. **排版**：系统字体栈；每视图 3-4 档字号（12/14/16/20px）；标题负字距（-0.02em@20px 起，越大越紧）；字重纪律——标题 500-600 封顶、正文 400、禁 700；数字表格 `tabular-nums`；正文行高 1.4-1.6；可读文本 ≥12px。
5. **一致性**：同组件处处同款，不造一次件；动画 150-300ms + `ease`/`cubic-bezier(0.4,0,0.2,1)`；圆角全站 2-3 档（卡片 16 / 按钮 10 / 输入 8）。
6. **打磨（最后的 10%）**：交互元素 hover/focus/active 过渡；loading 态不留白屏；空态有引导；错误态可行动；键盘焦点环可见。

## 硬约束（违反即 bug）

- **无 emoji**——视觉趣味靠排版/间距/布局。
- **无 accent bars / 高亮条**——左缘色条、顶部色条等装饰条元素禁止；层级分隔用边框/间距/字重。
- **克制的高级感**（Apple 式简约）——更少颜色、更少效果、更多留白。
- **先复用后新建**——新建组件前先查代码库有无等价物，有则精确匹配其风格，不引入一次件变体。

## 验证流（截图驱动）

- **改前**：截图现状 + 查 console 既有错误。
- **改后**：截图验证视觉结果 + 查 console 无新增错误；不像样立即迭代——不验证不许报完成。
- **无法截图时（服务没起/工具故障）必须明说**，不许声称已验证。
- 响应式三档：desktop 1440×900 / tablet 768×1024 / mobile 375×812。
- dev server `http://localhost:3000`；起服务 `npm run dev`（后台）后等 3-5 秒再截。
- 深度自验方法（Playwright harness/wire 探针/契约断言）见同包 qa 章 `frontend-verification`。
