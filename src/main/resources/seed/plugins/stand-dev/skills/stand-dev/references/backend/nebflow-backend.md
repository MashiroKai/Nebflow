> **迁址说明（2026-10-31 stand-dev 合并批）**：本文 = 原 `backend-dev` 插件 `skills/nebflow-backend/SKILL.md` 全文迁入，能力零删改；文内出现的自包根相对路径（`skills/...`、`scripts/...`、`assets/...`）为原包内路径，对应物现位于 `skills/stand-dev/` 下（脚本 `scripts/`、资产 `assets/`）；跨包引用（原各 nebflow-* 插件名）已改写指向 stand-dev 对应章。深读顺序：先读章导览（`skills/stand-dev/SKILL.md`）。

---
name: nebflow-backend
description: Nebflow Scala 后端开发域知识——Scala 3/自研 actor/http4s/cats-effect 技术栈、Actor 与 IO 分层铁律、最小改动/向后兼容编码规范、sbt 编译测试实践（-batch/锁冲突/注释坑）、工具链全谱（MUnit/scalafmt/-Xfatal-warnings）、最小可信基构建原则、worktree 纪律。Use when 在 Nebflow 主仓或其 worktree 实现/修复/重构 Scala 后端代码，或排查 sbt 构建问题。
when_to_use: Nebflow 主仓 Scala 后端任务（src/main/scala/ 下功能实现、bug 修复、重构、测试编写、构建配置、工具链口径与可信基接线）；与同包 qa 章 verification-rigor（验证方法论）互补——本 skill 管"怎么写"，那边管"怎么验证"。
language: zh
status: active
last_verified: 2026-11-04
---

# Nebflow Backend — Scala 后端开发域知识

## 技术栈

- Scala 3, 自研 actor, http4s, cats-effect
- 构建 sbt；测试 `sbt test`；源码在 `src/main/scala/`
- 主仓 = 任务书给定的 worktree 座位（禁写死机器路径）；Rust 版（`nebflow-rs/`、`/tmp/nebflow-rust`）禁触

## 工具链全谱与最小可信基（2026-11-04 作者裁定）

> 三条口径均为作者 2026-11-04 裁定注入（19:54 裁定/20:06 定义/20:12 全谱口径）；「」内为原话逐字，不带引号文字为执行侧整理，语义以原话为准。

### 口径一：最小可信基构建原则（19:54 裁定，20:06 定义）

- 作者原话：「所有仓库代码编写保持最小可信基构建原则。有限确保底层逻辑的正确性」。
- **「可信」两定义（20:06）**：
  - **安全性可信** =「能够通过我们设计的测试」。
  - **功能完备性可信** =「这个可信基能够让上层代码构建时不会引入新的基础性代码，上层单方向引用下层」。
- 当前实例：stand-scala 的 `src/main/scala/nebflow/db/` 与 `src/main/scala/nebflow/pilogic/` 两模块（2026-11-04 落地）即一次可信基重新构筑——上层（主链/工具/界面）**单向引用**它们，不在上层另写基础性代码。
- **接线分歧分级制**：技术等价（实现选型不影响语义）→ 执行侧自决；语义分歧（影响行为/契约/依赖方向）→ 上报作者裁定。

### 口径二：工具链标准 = 全谱口径（20:12）

- 作者原话：「不仅仅是项目管理工具和编译器（sbt，cargo，scala-cli，rustc），我还希望有标准化测试工具（scalaTest，Munit），代码风格和质量工具（scalafmt，rustfmt，clippy等）这些标准也很重要」。
- stand-scala 全谱现状（2026-11-04 20:12 实测）：

| 层位 | 工具 | 版本 / 口径 |
|---|---|---|
| 构建 / 项目管理 | sbt | 1.10.10 |
| 语言 / 编译器 | Scala | 3.5.2（ThisBuild 钉版） |
| JDK | Eclipse Temurin | 21.0.12.1 LTS |
| 测试框架 | MUnit + munit-cats-effect | 仓内 build.sbt 实测在用；**不是 ScalaTest** |
| 代码格式化 | scalafmt | `.scalafmt.conf` + sbt-scalafmt 2.5.2 |
| 质量线 | `-Xfatal-warnings` | 编译警告即错 |
| scala-cli | —（未装） | 2026-11-04 本机 PATH 实测未装；本仓构建口径 = sbt |

### 口径三：Rust 侧对照（跨线参考，不扩域）

- Rust 测试框架为**语言内建**：`#[test]` + `cargo test` + `#[cfg(test)]` 单测 + `tests/` 目录集成测试 + doc test——无第三方测试框架选型问题；事实标准第三方（proptest / criterion / mockall）按需引入。
- triple-v2 线（`D:/workspace@zcode/nebflow/开发区/triple-v2`，stand-rust 的可信基）四件套——toolchain 钉版 / `rustfmt.toml` / clippy `-D warnings` 门 / `.cargo/config.toml`——**落地中**（截至 2026-11-04；未完成态，勿写成已完成）。
- 域声明：本节仅作跨线对照参考；本包域 = stand 本体（scala + web），Rust 内容不入包域。

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
- **上游衔接（2026-11-01 二批并入）**：方案与技术选型方法（需求拆解/技术调研/trade-off 对比/双文档交付）见同包方法章 `grill`——其 `_ARCH_SPEC.md` 实施规格书是本域实现的输入，任务清单即验收对照物；需求模糊回问不猜。
