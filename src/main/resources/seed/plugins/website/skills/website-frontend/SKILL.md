---
name: website-frontend
description: Nebflow 官网（nebflow.space）前端开发域知识——Next.js 16 App Router + React 19 + TypeScript strict + Tailwind CSS v4 @theme tokens、i18n [lang] 动态路由与 dictionaries、组件复用纪律、API Route Handlers 与 JWT 认证模式、性能规范（3D/图表懒加载、next/image）、验证流（dev server/tsc --noEmit/build）。Use when 开发 nebflow-website 项目（React 组件、页面布局、API 路由、TypeScript 逻辑、构建问题）。
when_to_use: nebflow-website 仓库前端开发任务；简单文案/小样式调整可直接做；部署与双域发布纪律见仓库 AGENTS.md 与同插件 deploy-website。
language: zh
status: active
last_verified: 2026-09-03
---

# Website Frontend — Nebflow 官网前端开发域知识

## 技术标准

- **Next.js 16 App Router**：`app/` 目录、server/client components、layouts、loading states。
- **React 19**：现代特性（use、Suspense、优先 server components）。
- **TypeScript**：严格类型，无 any；正经 interface/type。
- **Tailwind CSS v4**：`@theme` tokens + utility classes 优先于自定义 CSS；品牌色用 CSS 变量（`--color-primary`、`--color-accent` 等）。
- **i18n**：路由用 `[lang]` 动态段；词典在 `lib/dictionaries.ts`；**永远同时支持 zh 和 en**。
- **组件复用**：新建前先查 `components/` 有无既有组件——有则复用并匹配既有模式与命名约定。
- **API Routes**：Next.js Route Handlers（`app/api/*/route.ts`）；遵循既有 auth 与错误处理模式。
- **性能**：重组件（3D、图表）懒加载；`next/image` 优化图片；最小化客户端 JS。

## 代码安全

- **XSS**：用 React 内建转义；`dangerouslySetInnerHTML` 仅在内容已消毒时用。
- **Auth**：API 路由内校验 JWT（jose）；密钥绝不暴露给客户端。
- **Env vars**：`NEXT_PUBLIC_*` 前缀只给真正公开的值（build 时烘焙进产物——测试功能标记机制依赖此语义）。
- **env 值严禁带引号**（Vercel 存裸值；`vercel env pull` 文件里的引号是 dotenv 书写格式不是存储内容）——详见仓库 AGENTS.md「部署与环境变量」。

## 验证流

- **改前**：读现状代码；查 console/build 既有问题。
- **改后**：dev server 起着（`npx next dev --turbopack -p 3000`）验证页面渲染（Pop 打开或 curl）；构建检查（`npx next build` 或 dev server 输出）；TypeScript 检查 `npx tsc --noEmit`。
- 不像样立即迭代；**无法验证时（服务没起/build 挂）必须明说**，不许声称已验证。
- 功能验证按双域纪律：需真实环境的功能（登录、设备互联）直接推 neblink.space 测试域在线验证——localhost 预览交差已废弃（2026-08-27 裁定）；正式域 nebflow.space 严格两步发布。

## 交付口径

- 展示改动 before/after diff；解释 WHY 不只 WHAT；解释简洁让代码说话。
