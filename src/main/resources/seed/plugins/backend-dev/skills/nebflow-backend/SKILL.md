---
name: nebflow-backend
description: Nebflow Scala 后端开发域知识——Scala 3/自研 actor/http4s/cats-effect 技术栈、Actor 与 IO 分层铁律、最小改动/向后兼容编码规范、sbt 编译测试实践（-batch/锁冲突/注释坑）、worktree 纪律。Use when 在 Nebflow 主仓或其 worktree 实现/修复/重构 Scala 后端代码，或排查 sbt 构建问题。
when_to_use: Nebflow 主仓 Scala 后端任务（src/main/scala/ 下功能实现、bug 修复、重构、测试编写、构建配置）；与 nebflow-qa 插件的 verification-rigor（验证方法论）互补——本 skill 管"怎么写"，那边管"怎么验证"。
language: zh
status: active
last_verified: 2026-09-03
---

# Nebflow Backend — Scala 后端开发域知识

## 技术栈

- Scala 3, 自研 actor, http4s, cats-effect
- 构建 sbt；测试 `sbt test`；源码在 `src/main/scala/`
- 主仓 `/Users/kaiyu/Claude code/Nebflow`；Rust 版（`nebflow-rs/`、`/tmp/nebflow-rust`）禁触

## 架构铁律

**Keep your Actors out of your cats-effect, and your cats-effect out of your actors.**

- Actor 只管 message passing 与状态管理；cats-effect IO 只管副作用编排；两层不交叉混用。
- 判断标准：消息路由/状态机 → Actor；网络/定时/资源获取等副作用 → IO。需要桥接时用显式边界（IO 里 ask 模式发消息、Actor 侧 unsafe 风格收口），不做隐式互渗。
- 审查同款判据：IO 错误路径显式（`raiseError` / `handleErrorWith`），不吞异常；资源获取用 bracket/guarantee 保证释放；Actor 停止时的清理逻辑完整。

## 编码规范

- **先设计再编码**：改动涉及架构时先落设计文档；最小最精准改动，避免过早抽象。
- **向后兼容**：新增字段有默认值；改动不破坏现有 API。
- **注释安全坑**：Scala 块注释内避免 `/*` 序列（如路径 `tools/mcp/*.json` 会让注释嵌套解析出错）——用行注释 `//` 或改写措辞。
- **依赖方向**：`shared ← core ← cli/gateway`，禁止反向导入与循环依赖。

## sbt 实践

- 验证顺序：`sbt compile` 验编译 → `sbt test` 验测试；不要启动服务（起服务必须带隔离参数，见运行安全）。
- `sbt test` 用 `-batch` 模式（前台日志输出，避免后台执行被超时误杀）。
- 多 agent 并行时 sbt 可能锁冲突——错峰重试；仍失败如实报告为阻塞，不误判为代码失败。
- 全量测试 OOM 挂起（~0% CPU、无 summary）：`grep OutOfMemoryError` 确认后 `SBT_OPTS="-Xmx3g"` + 聚焦子集重跑。

## worktree 纪律

- 并行任务禁止共享工作目录切分支：每任务独立 worktree，`git worktree add /tmp/nb-<feature> main -b feat/<feature>`——分支名与 worktree 目录名一致。
- 所有操作在 worktree 目录内完成，编译测试也在其中跑；完成后 commit 等合并，不自行 merge、不自行 push。

## 交付口径

- 报告改动文件清单 + commit hash；说明 WHY（为什么这样改），不只 WHAT；测试结果通过数/总数。
- 完成自测后直接交 QA 验收（新架构：产出节点后接 verify 节点，in 边自动投递），不自行合并。
