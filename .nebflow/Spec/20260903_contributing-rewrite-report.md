# CONTRIBUTING 双语文档重写 — 结果报告（2026-09-03）

## 改动摘要

| 文件 | 定位 | 章节结构 | 篇幅 |
|---|---|---|---|
| `CONTRIBUTING.md` | 面向为本仓提 PR 的开发者的精简开发规范 | Contributing → Prerequisites → Build and run locally → Testing (Backend/Frontend) → Branches and commits → Pull requests → Code style | 10,412 B → **4,761 B**（-54%） |
| `CONTRIBUTING.zh-CN.md` | EN 逐节镜像 | 贡献指南 → 环境准备 → 构建与本地运行 → 测试（后端/前端） → 分支与提交 → PR 要求 → 代码风格 | 9,580 B → **4,475 B**（-53%） |

## 事实核对表（版本/命令 → 证据）

| 事实 | 证据 |
|---|---|
| Java 21 (Temurin) | `.github/workflows/ci.yml` 全部 job `java-version: 21`；`build.sbt` `-XX:+UseZGC -XX:+ZGenerational`（分代 ZGC 需 21+） |
| sbt 1.10.10 | `project/build.properties`；CI 同版 |
| Scala 3.5.2 | `build.sbt` `ThisBuild / scalaVersion` |
| Node 20 | `ci.yml` 全部 Node job `node-version: '20'`（无 .nvmrc、package.json 无 engines） |
| 全量测试 ~2000+ 用例、35–40 分钟 | `grep -rE '^\s*test\(' src/test` = 2067；串行 `Test / parallelExecution := false`（build.sbt） |
| `sbt "testOnly nebflow.core.BrandingSpec"` | `src/test/scala/nebflow/core/BrandingSpec.scala` 存在 |
| Playwright `@playwright/test@1.62.0`、`npx playwright test tests/smoke.spec.mjs` | `ci.yml` frontend-smoke job；`tests/` 40 个文件以 `*.spec.mjs` 为主体 |
| 自含 spec（临时端口桩后端） | `tests/orbit-anim.spec.mjs` L16-41 `createServer` + `listen(0)` |
| 实例 spec `BASE_URL` / `NEBFLOW_TOKEN`（回退 `~/.nebflow/auth.json`） | `tests/smoke.spec.mjs` L11-22 |
| 前端门禁 `node scripts/check-js-types.mjs` / `check-circular.mjs` | `scripts/` 实文件 + `ci.yml` js-types job（基线 `tests/type-baseline.json` 只降不升） |
| `sbt run` 默认 8080、`--home`/`--port` 全局旗标 | `Main.scala` L15-46（无参/start/-s 均 startGateway）；`gatewayConfig.scala` `DefaultPort = 8080` |
| web bundle：`npm ci && node scripts/build-web.mjs` → build/web-dist | `ci.yml` assembly job；`scripts/build-web.mjs` |
| `make check` = compile + scalafmtCheckAll + scalafix --check | `Makefile` L42-58；`.scalafmt.conf` / `.scalafix.conf` 存在 |
| 提交风格 `type(scope):` 前缀、中英混排、`Merge <branch>: <summary>` | `git log --oneline -30` 抽样归纳 |

## 与旧文档差异

**删除**：Apache 2.0 授权与贡献授权条款（开源定位）；"What is Nebflow" 产品定位与架构概览（含 Project/Node 编排模型小节）；"A note on the Rust port"（nebflow-rs/ 经查**确实存在**于仓库根，按裁定仍不写入）；"面向编码 agent 的仓库指令"章节。

**修正**：JDK "17+"（README/旧文档口径）→ **21**（CI + build.sbt 实际）；全量测试时长 25–30 分钟 → **35–40 分钟**（任务裁定口径）。

## 自查清单（commit 前逐项过）

- [x] 两文件均不含 Team/Flow/agent/架构清单/路线图承诺（grep 零命中）
- [x] 无 Apache 2.0 / 开源共建定位表述
- [x] 不提 nebflow-rs / Rust
- [x] 全部版本与命令有仓库证据（见上表），无虚构命令
- [x] EN/ZH 章节一一对应（标题行号 5/15/38/40/49/67/73/79 完全镜像）

## Commits

- ~/.nebflow 存档：**e371f715**（旧版双语文档快照 10,412 B / 9,580 B）
- 主仓 main：**fda7c9d9**（仅 add 两个目标文件，暂存区提交前已复核）

## 声明

未 push、未动 origin；主仓其他文件零改动；未碰 8080 宿主进程。

## 遗留问题

无。注：主仓 HEAD 在开工时为 968dd84b（任务描述 69313552 的正常漂移，为并行节点的合法提交）。
