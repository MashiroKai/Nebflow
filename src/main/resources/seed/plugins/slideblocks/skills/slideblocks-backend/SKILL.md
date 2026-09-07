---
name: slideblocks-backend
description: SlideBlocks 后端开发域知识——REST API、database schema、Auth、object storage、Worker、monorepo tooling；TypeScript 严格类型、最小改动/向后兼容、lint+build 验证闭环。Use when 开发 slideblocks 项目的服务端（API 路由、数据库、认证、对象存储、Worker、monorepo 构建）。
when_to_use: slideblocks 仓库后端任务（API、database、Auth、object storage、Worker、monorepo build）；新建逻辑先读相关源码与规格。
language: zh
status: active
last_verified: 2026-09-03
---

# SlideBlocks Backend — 服务端开发域知识

## 职责域

REST API、database schema、Auth、object storage、Worker、monorepo tooling（Astro + Vue + TypeScript 栈）。

## 编码规范

- 遵循项目现有代码风格和命名约定。
- **TypeScript 严格类型，避免 any**。
- **最小改动**：不做过早抽象，不引入未要求的依赖；先读相关源码和规格，理解改动范围再动手。
- **向后兼容**：不破坏现有 API 和组件接口。

## 验证闭环

- lint + build 必跑（改了什么跑什么：Registry/website 改动从仓库根跑 `npm test` + `npm run build`）。
- 构建通过不等于完成——按任务验证真实行为（API 行为、产物、回归路径）。
- 交付报告：改动文件清单 + commit hash + WHY（不只 WHAT）+ lint/build 结果；阻塞立即上报不自行猜测决策。
- 分支/worktree 纪律：每任务从当前远端 Base SHA 新建分支或干净 worktree，禁止复用旧 PR 分支；完成后等合并，不自行 merge/push。

## 仓库协作红线（详见仓库根 AGENTS.md）

- 禁止 tarball/整目录覆盖、整文件 ours/theirs 一刀切；顺手重构及无关依赖或 lockfile 变更禁止。
- 测试失败只有在同一干净环境 Base SHA 上也能复现才算「历史问题」；不得删除或弱化测试。
