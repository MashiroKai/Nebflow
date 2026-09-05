# 贡献指南

本指南面向为本仓库提交变更的开发者，覆盖环境准备、构建、测试与分支/提交/PR 规范。仓库当前为私有，不存在外部贡献流程。

## 环境准备

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | CI（`.github/workflows/ci.yml`）以 Temurin 21 构建与测试；`build.sbt` 的 JVM 参数（分代 ZGC）同样要求 21 |
| sbt | 1.10.10 | 钉在 `project/build.properties` |
| Node.js | 20 | 仅 web bundle 构建、前端测试与前端检查需要 |

仓库不使用 `.jvmopts` / `.sdkmanrc`；工具链版本以 `project/build.properties` 与 CI workflow 为准。

## 构建与本地运行

```bash
sbt compile        # 编译
sbt assembly       # fat JAR -> target/scala-3.5.2/
sbt run            # 启动 web 服务 http://localhost:8080
```

如 8080 已被占用（生产实例或其他服务），用独立 home 与端口隔离开发实例（`--home` / `--port` 为全局旗标）：

```bash
sbt "run --home /tmp/nb-dev --port 8090"
```

web 前端是 `src/main/resources/web` 下的原生 JS——`sbt run` 直接服务实时源码，无需 npm 步骤。生产 bundle 单独构建：

```bash
npm ci
node scripts/build-web.mjs    # esbuild bundle -> build/web-dist
```

`Makefile` 封装了常用命令：`make compile`、`make run`、`make assembly`、`make install`（JAR 装到 `~/.local/bin`）、`make check`（编译 + 格式/lint 检查，见 [代码风格](#代码风格)）。

## 测试

### 后端（Scala，munit）

```bash
sbt test                                  # 全量：约 2000+ 用例，预计 35–40 分钟
sbt "testOnly nebflow.core.BrandingSpec"  # 定向运行——快速迭代
```

测试串行执行（`Test / parallelExecution := false`），因为各 suite 共享部分全局状态——不要随意重新开启并行。迭代时用 `testOnly <package>.<SpecName>` 定向跑；开 PR 前跑全量。

### 前端（Playwright）

浏览器 spec 位于 `tests/*.spec.mjs`，分两类：

- **自含 spec** 在临时端口上自建后端桩（如 `tests/orbit-anim.spec.mjs`）——无需运行中的实例。
- **实例 spec**（如 `tests/smoke.spec.mjs`）驱动真实服务：用 `BASE_URL` 指向实例、用 `NEBFLOW_TOKEN` 认证（未设时回退读实例的 `~/.nebflow/auth.json`）。

```bash
# 一次性安装（Playwright 不在 package.json 依赖里；CI 同款安装方式）
npm install --no-save --no-package-lock @playwright/test@1.62.0
npx playwright install chromium        # 仅在无浏览器缓存时需要

npx playwright test tests/orbit-anim.spec.mjs
BASE_URL=http://localhost:8090 NEBFLOW_TOKEN=... npx playwright test tests/smoke.spec.mjs
```

CI 以新构建的 JAR 跑 smoke 套件，因此前端改动的门槛 = 本地绿 + CI 绿。

## 分支与提交

- 从 `main` 拉短描述性分支名；一个分支只做一件事。
- 按文件显式暂存（`git add <file>`）；避免 `git add -A`。
- 实际提交风格：主题行可选 `type(scope):` 前缀——类型如 `fix` / `feat` / `test` / `docs` / `refactor`，scope 如 `web` / `tools` / `engine` / `project` / `gateway`。主题行中英文皆可，应写清原因或意图，而非仅陈述表象。合入 `main` 的 merge commit 遵循 `Merge <branch>: <summary>`。

## PR 要求

- PR 目标 `main`。diff 保持最小、聚焦单一改动。
- 开 PR 前：`sbt compile` 与受影响测试全绿。Scala 改动另跑 `make check`（scalafmt + scalafix）；前端改动跑类型/循环依赖门禁与受影响的 Playwright spec（见下）。
- CI（`.github/workflows/ci.yml`）运行 JS 类型/循环门禁、编译 + 全量测试（Ubuntu 与 Windows）、JAR assembly、smoke + Playwright 前端套件、Docker 构建。CI 全绿即可合并。

## 代码风格

- **Scala 3**（3.5.2，缩进语法）。格式由 scalafmt 与 scalafix 强制——`make check` 运行 `sbt scalafmtCheckAll "scalafix --check"`。编译器开 `-Xfatal-warnings`：修掉 warning，而不是压制它。
- **前端**是原生 JS，无 TS 构建步骤；正确性由两个静态检查门禁（CI 均会运行）：

```bash
node scripts/check-js-types.mjs    # checkJs vs tests/type-baseline.json（基线只降不升）
node scripts/check-circular.mjs    # 静态 import 循环依赖门禁
```

有意修复类型问题后用 `check-js-types.mjs --update` 重新生成基线。动态 `import()` 是打破真实循环依赖的正规手段；静态 import 循环会挂门禁。
