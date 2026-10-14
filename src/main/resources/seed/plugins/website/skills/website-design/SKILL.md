---
name: website-design
description: Nebflow 官网视觉设计域知识——设计 tokens（Tailwind v4 @theme）复用纪律、视觉层级/间距节奏/排版（Press Start 2P 像素展示字体 + ZCOOL 站酷庆科黄油体）/动效规范（Framer Motion 组件动画 + GSAP 滚动序列，150-300ms 微交互）、Pop 目检验证流与三端响应式、硬约束（无 emoji/克制高级感/wordmark 全小写 nebflow）。Use when nebflow-website 的视觉设计任务：配色方案、动效设计、交互优化、布局调整、视觉一致性。
when_to_use: 官网 UI/UX 设计与视觉实现（写 CSS/Tailwind、改 JSX 的视觉部分）；结构性与 API 逻辑归 website-frontend；wordmark 大小写等高主观偏好改动前先低成本对比展示。
language: zh
status: active
last_verified: 2026-09-03
---

# Website Design — Nebflow 官网视觉设计域知识

## 一致性优先

- **先复用后新建**——新建样式前查既有等价物，有则精确匹配。
- 设计 tokens 来自 `app/globals.css` 的 Tailwind v4 `@theme`：`--color-primary`、`--color-accent`、`--color-card`、`--color-card-border`、`--color-muted`、`--color-foreground`、`--color-background`——**永不裸写 hex**。
- 圆角、阴影、间距从既有值里选，不发明新值；同组件处处同款。

## 视觉原则

1. **层级**：视线先看哪二看哪三——尺寸/间距/对比造层级，不只靠颜色；每视口节一个主强调，不过用 primary/accent。
2. **间距节奏**：Tailwind 4px 基数刻度；留白宁多勿挤；主章节间距 64-96px（py-16 到 py-24）；相关近、无关远。
3. **排版**：展示字体 Press Start 2P（像素风）+ 站酷庆科黄油体（中文展示）+ 系统字体正文；每视图字号档位克制；字重做强调不做装饰；正文 `leading-relaxed`、标题 `leading-tight`。
4. **动效**：组件动画用 Framer Motion（匹配代码库既有动画模式）；滚动触发/复杂序列用 GSAP；微交互 150-300ms、页面/章节转场 400-800ms；缓动用 ease 或自定义 cubic-bezier，避免 linear；交互元素 hover/focus/active 平滑过渡。
5. **打磨**：所有可交互元素有 hover/focus 态；空态有引导；错误态信息可行动；键盘焦点环；`cursor: pointer`。

## 硬约束（违反即 bug）

- **无 emoji**——视觉趣味靠排版/间距/布局。
- **克制的高级感**——更少颜色、更少效果、更多留白；装饰做减法。
- **wordmark 全小写 `nebflow`**（用户裁定 2026-08-25，Press Start 2P 像素样式不变；NebFlow/NEBFLOW 变体已作废）——大小写属高主观偏好，改动前先低成本对比展示。

## 验证流

- **改前**：读现有代码理解设计系统；定位 `@theme` tokens；查既有组件样式模式。
- **改后**：dev server 起着；Pop 打开页面目检；**三端必测**（desktop 1440 / tablet 768 / mobile 375）；不像样立即迭代；不验证不许报完成。
- 交付：描述视觉问题与设计方案；CSS/布局改动展示 before/after；解释 WHY 不只 WHAT。

> 历史注记：官网曾采用的视觉流程变化——localhost 截图交差已于 2026-08-27 废弃（真实功能直接推 neblink.space 测试域在线验证）；双域发布与测试功能标记纪律见仓库 AGENTS.md。
