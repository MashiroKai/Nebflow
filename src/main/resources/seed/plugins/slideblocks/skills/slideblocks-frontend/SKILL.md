---
name: slideblocks-frontend
description: SlideBlocks 前端开发域知识（Astro + Vue + TypeScript + CSS）——Gallery UI/Copy Prompt/preview rendering/响应式/a11y/i18n 前端职责、截图驱动验证流（改前改后截图+console 检查/三档视口）、通用设计原则（层级/深度/间距节奏/排版字重/一致性/打磨）、硬约束（无 emoji/无 accent bars/复用既有设计）。Use when 开发 slideblocks 项目的前端（Astro pages、Vue 组件、CSS、Gallery UI、i18n、responsive/a11y）。
when_to_use: slideblocks 仓库前端任务；视觉验证方法论与 slideblocks skill（deck 制作路线）区分——本 skill 管平台站前端开发，deck 制作流程见同插件 slideblocks。
language: zh
status: active
last_verified: 2026-09-03
---

# SlideBlocks Frontend — 平台前端开发域知识

## 技术栈与职责

- Astro + Vue + TypeScript + CSS；职责域：Gallery UI、Copy Prompt、preview rendering、responsive、a11y、i18n（CN/EN）。
- 遵循项目现有代码风格与命名约定；组件先复用后新建——新建前查代码库有无等价物，有则精确匹配其风格。
- TypeScript 严格类型；a11y（ARIA、键盘导航、焦点管理）；性能（无不必要 reflow、scroll/resize debounce、懒加载重组件）。

## 验证流（截图驱动）

- **改前**：截图现状 + 查 console 既有错误。**改后**：截图验证 + 查 console 无新增错误；不像样立即迭代——不验证不许报完成。
- **无法截图时（服务没起/工具故障）必须明说**，不许声称已验证。
- 响应式三档：desktop 1440×900 / tablet 768×1024 / mobile 375×812。
- dev server 起后等 3-5 秒再截；改动涉及 preview/导出时按任务验证真实浏览器与生成产物——**构建通过不等于完成**。

## 设计原则（跨端通用）

1. **视觉层级**：一眼知道先看哪——每视口至多一个彩色强调事件，其余灰阶；重要 = 更大/更多留白/更高对比。
2. **深度**：surface 阶梯优先于阴影；1px hairline 边框做卡片主边缘；需要阴影时用堆叠小偏移。
3. **间距节奏**：4px 基数；主章节间距 64-96px；留白宁多勿挤；对齐用精确值不目测。
4. **排版**：每视图 3-4 档字号；标题负字距随字号增大而收紧；字重纪律——标题 500-600 封顶、正文 400、禁 700；数字表格 `tabular-nums`；正文行高 1.4-1.6；可读文本 ≥12px。
5. **一致性**：同组件处处同款；动画 150-300ms + ease/cubic-bezier；圆角全站 2-3 档。
6. **打磨**：hover/focus/active 过渡；loading 态不留白屏；空态有引导；错误态可行动；键盘焦点环可见。

## 硬约束（违反即 bug）

- **无 emoji**——视觉趣味靠排版/间距/布局。
- **无 accent bars / 高亮条**——左缘色条、顶部色条等装饰条禁止；层级分隔用边框/间距/字重。
- **克制的高级感**——更少颜色、更少效果、更多留白。

> 历史注记：本 skill 蒸馏自 slideblocks Frontend 成员定义；其原文含整段 Nebflow 客户端专属内容（Sapphire Glass 设计系统、vanilla JS 无构建约束、src/main/resources/web/ 结构）——系跨团队复制残留，与 Astro+Vue 技术栈不符，已剔除（2026-09-03 蒸馏时换域校验）。
