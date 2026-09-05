# Nebflow 贡献指南（中文版）

感谢你有兴趣为 Nebflow 做贡献！本指南覆盖构建、测试与提交变更所需的全部内容。Nebflow 采用 [Apache License 2.0](LICENSE) 授权。

产品概览请看 [README](README.md)。本文档与英文版 [CONTRIBUTING.md](CONTRIBUTING.md) 内容一致。

## Nebflow 是什么？

Nebflow 是一个完全运行在本机的自托管 AI 编码助手（agent harness）：浏览器聊天界面、流式响应、多 agent 编排、Flow 引擎、Skills 与 MCP 支持——打包为单个 fat JAR，运行时只依赖 Java。生产实现为 **Scala 3（JVM），使用 sbt 构建**。Rust 移植版位于 [`nebflow-rs/`](nebflow-rs/)，独立演进（见 [关于 Rust 移植](#关于-rust-移植)）。

## 开发环境

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | CI 在 Temurin 21 上构建与测试 |
| sbt | 1.10.10 | 固定在 `project/build.properties`，标准 sbt 启动器自动识别 |
| Node.js | 20 | 仅前端打包与前端检查需要 |
| Git | 任意较新版本 | |

```bash
git clone https://github.com/MashiroKai/Nebflow.git
cd Nebflow
sbt compile        # 编译
sbt run            # 启动 Web 服务 http://localhost:8080
```

`sbt run` 默认在 **8080** 端口启动网关。如果 8080 已被占用（比如你已在运行生产实例），用独立 home 和端口隔离开发服务：

```bash
sbt "run --home /tmp/nb-dev --port 8090"
```

`Makefile` 封装了常用 sbt 命令——`make compile`、`make run`、`make assembly`、`make check`（见[代码规范](#代码规范)）。`sbt assembly` 构建 fat JAR，`make install` 安装到 `~/.local/bin`。

## 架构概览

后端模块地图（`src/main/scala/nebflow/` 下）：

```
actor/     Actor 系统（ActorRef、ActorSystem、Behavior）
agent/     Agent 生命周期、会话路由、agent 库
cli/       CLI 命令、REPL
core/      工具、权限、LLM 衔接、上下文压缩
  flow/      Flow 引擎（多步工作流）
  skill/     Skill 加载（SKILL.md + YAML frontmatter）
  mcp/       Model Context Protocol 客户端与管理
  project/   Project / Node / Flow Map 编排
  task/      任务列表状态
  tools/     内置工具
gateway/   HTTP 服务、WebSocket 路由、静态资源
llm/       Provider 抽象（GLM、DeepSeek、Qwen、OpenAI/Anthropic 兼容）
neblink/   跨设备 mesh 同步
service/   会话存储、配置
shared/    共享类型与工具
```

写代码前值得知道的两条架构规则：

- **Actor 与 cats-effect 互不侵入。** Actor 负责消息传递与状态管理；cats-effect `IO` 负责副作用编排。两层各管各的。
- **品牌字符串单一来源**：全部定义在 [`brand.conf`](brand.conf)，后端经 `nebflow.core.Branding`、前端经 `window.__BRAND__` 读取。禁止硬编码产品名/URL——改名请走 `scripts/rebrand.sh`。

### Project/Node 编排模型

- **Nebula** 是根 agent，永远存在——用户对话的唯一入口。
- **Project**（`core/project/`）是磁盘上的一个工作区，由 `project.json` 描述（名称、工作区路径、agent 文件）。每个项目有一张 **Flow Map**（`flow-map.json`）——一张由 **Node** 组成的 DAG。
- **Node** 是一次性的叶子级 agent 运行（无 Mail 身份、无记忆）。节点声明 `in`/`out` 边；节点完成后，结果沿 `out` 边投递给下游节点。节点可选运行在隔离的 **git worktree** 中；每个节点自带配置：使用哪个 agent 定义、skill、MCP 服务和 preset。
- Agent 定义正向三种形态收敛：根 **Nebula** agent、把任务分解进 Flow Map 的项目**分发器**、以及**通用节点模板**。

## 分支与发布

| 分支 | 角色 |
|---|---|
| `main` | 开发分支。所有功能改动经 PR 落到这里。每次 push 与 PR 都会触发 CI。 |
| `beta` | 预发布通道。push 触发 **Beta Release** 工作流：全量测试、fat JAR、桌面安装包（macOS `.dmg` arm64/x64、Windows `.msi`、Linux `.deb` + AppImage），打 `v<VERSION>` 标签并发布 GitHub 预发布版。 |
| `release` | 稳定通道。push 触发 **Auto Release** 工作流（同样的打包，正式发布），并把 `VERSION` 同步回 `main`。 |

- 仓库根目录的 `VERSION` 文件是版本号唯一来源；`build.sbt` 构建时读取。
- 发版（`main` → `beta` → `release` 的合并）由维护者执行。外部协作者只需把改动送进 `main`——发布从那里切出。

## 开发流程

1. 从 `main` 拉分支——仓库使用 `feat/`、`fix/`、`refactor/`、`test/` 前缀。
2. 用最小且精准的 diff 解决问题。
3. 编译、跑相关测试、过格式门禁（见下）。
4. 向 `main` 发 PR。

### Commit message

仓库遵循 Conventional Commits：

```
feat(project): 简短祈使句摘要
fix(web): another summary
```

- 使用的类型：`feat`、`fix`、`test`、`docs`、`chore`（合并提交形如 `Merge branch '...'`）。
- scope 可选但常见（`feat(project):`、`fix(web):`、`fix(llm):`）。
- 摘要英文中文皆可——本仓两种都常用。
- 有关联 issue 时在摘要中带 `#123`。

并行做多件事时，优先用独立 checkout（`git worktree add ../Nebflow-feat-x main -b feat/x`），不要在一个工作目录里反复切分支。

## 代码规范

### Scala

格式化与静态检查由 **scalafmt**（`.scalafmt.conf`，Scala 3 方言，120 列，2 空格缩进）和 **scalafix**（`.scalafix.conf`：`DisableSyntax` 的 `noNull`/`noReturns`/`noXmlLiterals`/`noFinalize`、`OrganizeImports` 等）强制执行。编译开启 `-Xfatal-warnings`。

```bash
make lint          # 检查格式 + scalafix（CI 门禁同款）
make check         # 编译 + 格式检查 + lint
make fmt           # 自动格式化
make fix           # 自动应用 scalafix
```

推送前先跑 `make check`——这是本仓的质量门禁（编译 + 格式检查 + lint）。

### 前端（vanilla JS）

Web UI 是原生 ES modules，直接从 `src/main/resources/web/` 服务——**开发不需要构建步骤**。没有框架，没有 npm 运行时依赖。

每个 PR 在 CI 跑两道静态门禁：

- **checkJs 基线门禁**（`jsconfig.json` + `tests/type-baseline.json`）：TypeScript 检查的 JS，错误基线只许降不许升——新增错误即红；
- **循环依赖门禁**：静态 import 图必须无环（动态 `import()` 是唯一豁免通道）。

生产打包时用 esbuild 打 bundle：

```bash
npm ci                      # 安装 esbuild（唯一 dev 依赖）
node scripts/build-web.mjs  # 打包到 build/web-dist/
```

CI 会把 bundle 挂进 JAR；本地裸 `sbt run` 永远服务源码树，不会误用过期 dist。任何 `web/` 下的改动，合并前必须过静态资源门禁（对运行实例跑 `scripts/verify-web-assets.mjs`）——每个新文件都必须真实返回 HTTP 200。

## 测试

### 后端（Scala，munit）

```bash
sbt test                                   # 全量（预期约 25–30 分钟）
sbt "testOnly nebflow.core.BrandingSpec"   # 定向运行——快速迭代
```

测试**串行**执行（`Test / parallelExecution := false`），因为各 suite 共享部分全局状态——不要随手恢复并行。快速迭代请始终用 `testOnly <package>.<SpecName>` 定向，不要跑全量。

### 前端（Playwright）

浏览器测试在 `tests/*.spec.mjs`，分两类：

- **自带打桩的 spec** 用一次性 HTTP server（临时端口）stub 后端——不需要 Nebflow 实例，也绝不碰 8080 端口。例：`tests/orbit-anim.spec.mjs`。
- **依赖实例的 spec**（如 `tests/smoke.spec.mjs`）驱动真实运行的服务；用 `BASE_URL` 指向它，用 `NEBFLOW_TOKEN` 认证。

```bash
# 一次性安装（Playwright 不在 package.json 依赖里；CI 同款安装方式）
npm install --no-save --no-package-lock @playwright/test@1.62.0
npx playwright install chromium        # 仅在本地无浏览器缓存时需要

npx playwright test tests/orbit-anim.spec.mjs      # 自带打桩
BASE_URL=http://localhost:8090 NEBFLOW_TOKEN=... npx playwright test tests/smoke.spec.mjs
```

CI 会对新构建的 JAR 跑 smoke 套件——本地绿 + CI 绿是前端改动的达标线。

## 提交 PR

开 PR 前确认：

- [ ] `sbt compile` 与定向测试通过（格式门禁跑 `make check`）
- [ ] 前端改动过静态门禁；`web/` 改动过资源可达性检查
- [ ] diff 最小且聚焦——一个 PR 只解决一件事

PR 描述请覆盖：改了**什么**、**为什么**、如何验证的（跑过哪些命令/测试），UI 改动附截图。CI 会跑完整流水线——Linux 与 Windows 编译、全量测试、JS 门禁、JAR smoke 测试、对构建 JAR 的 Playwright 套件。流水线全绿即可合并。

## 关于 Rust 移植

Nebflow 正在从 Scala 迁移到 Rust；移植版在 [`nebflow-rs/`](nebflow-rs/)，有独立 CI。在移植版达到验证过的功能对等之前，Scala 版仍是运行中的生产构建——两者视为独立项目：Rust 改动进 `nebflow-rs/`，其余进 Scala 代码树。

## 面向编码 agent 的仓库指令

仓库根目录的 `AGENTS.md` 是本项目对 AI 编码 agent 的指令文件（遵循 [AGENTS.md](https://agents.md) 惯例的 harness 会将其自动注入为系统上下文）。如果你用 agent 参与贡献，确保它读过该文件——其中编码了与本指南相同的约定，外加工作规则（分支安全、worktree 纪律、进程安全）。

## 许可

提交贡献即表示你同意你的贡献以 [Apache License 2.0](LICENSE) 授权。
