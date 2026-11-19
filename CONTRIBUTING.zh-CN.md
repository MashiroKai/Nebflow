# 贡献指南

本指南面向为本仓库提交变更的开发者，覆盖环境准备、构建、测试与分支/提交/PR 规范。开发在内部 USTC Git 实例（`https://git.ustc.edu.cn/kaiyu/nebflow`）上进行，不接受外部 PR；GitHub 仓（`github.com/MashiroKai/Nebflow`）就地冻结为只读历史存档，不再接收推送、issue 与 PR。

## 环境准备

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 本地开发使用 23 | GitHub 构建/测试使用 Temurin 23；运行时最低 21，产物字节码目标 17 |
| sbt | 1.10.10 | 钉在 `project/build.properties` |
| Node.js | 20 | 仅 web bundle 构建、前端测试与前端检查需要 |

仓库不使用 `.jvmopts` / `.sdkmanrc`；工具链版本以 `project/build.properties` 与 CI workflow 为准。

## 构建与本地运行

```bash
sbt compile        # 编译
sbt assembly       # fat JAR -> target/scala-3.5.2/
sbt run            # 启动 web 服务 http://localhost:6138（引擎默认口，2026-10-25 端口对调，曾 8080）
```

如 6138 已被占用（生产实例或其他服务），用独立 home 与端口隔离开发实例（`--home` / `--port` 为全局旗标）：

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

- 分支模型、作者专属操作、守卫用法及 USTC 保护清单以 [Contributing](CONTRIBUTING.md#branches-and-commits) 为准。批次从 `main` 拉 `feat/*` 或 `bug/*`；既有 `fix/*` 不追溯改名。不得直接推 USTC。
- 按文件显式暂存（`git add <file>`）；避免 `git add -A`。
- 实际提交风格：主题行可选 `type(scope):` 前缀——类型如 `fix` / `feat` / `test` / `docs` / `refactor`，scope 如 `web` / `tools` / `engine` / `project` / `gateway`。主题行必须使用英文，应写清原因或意图，而非仅陈述表象。合入 `main` 由作者在上游核验后以 fast-forward 方式执行。

### 发布流转

作者批准并晋级经核验的变更至 `main` 后：检出 `main`，在 `main` 上跑完门禁与发布前全量 E2E，全绿后**以快进（fast-forward）方式推送**到 release 线（`release/beta`；`release/stable` 同法），由管线发布对应通道。**所有 release 支都只从 `main` 派生**（`release/beta`、`release/stable` 各自从 `main` 拉出）。发布线只向下游流转，永不合并或回写 `main`；发布问题从 `main` 修复，再按同一路径推进。发布流程的权威正文在 stand-dev 插件包规范面，措辞冲突以规范面为准。

USTC 保护与旧分支清理由作者按[保护清单](CONTRIBUTING.md#ustc-server-protection-checklist-author-only)执行：先归档为 `achieve/beta-20261116`、`achieve/release-20261116`，核对归档指向原分支尖端后再删除旧分支。

## PR 要求

全平台 worktree 与临时产物规则以已安装的 stand-dev 插件 `skills/stand-dev/references/engine/workspace-artifacts.md` 为权威正文。

本仓使用 `python3 scripts/stand-wt.py [--root <项目checkout>] new <wtname> [--branch <branch>]`，`list`、`audit`、`clean` 同样支持 `--root`（别名 `--project`）选择项目；`python3 scripts/check-delivery.py --root <checkout> --manifest-only` 检查指定 checkout。

`npm run check:wt` 与默认 static 检查自动推断关联 worktree 的清单；用 `stand-wt.py audit <wtname>` 刷新。主 checkout 明确输出跳过本地清单的原因。

- USTC PR 只由作者创建，源分支仅限 `feat/*`、`bug/*`，目标 `main`；由作者核验后 ff-only 合入。diff 保持最小、聚焦单一改动。
- 开 PR 前：`sbt compile` 与受影响测试全绿。Scala 改动另跑 `make check`（scalafmt + scalafix）；前端改动跑类型/循环依赖门禁与受影响的 Playwright spec（见下）。
- `.github/workflows/ci.yml` 与 `.gitlab-ci.yml` 的静态质量任务均为阻断门禁，后者覆盖 MR 与所有分支。`.github/workflows/auto-release.yml` 在构建前要求同一入口通过。现有浏览器观察任务仍保留非阻断状态。配置仅说明预期执行方式，不表示远程 CI 已经运行或通过。

## 代码风格

- **Scala 3**（3.5.2，缩进语法）。格式由 scalafmt 与 scalafix 强制——`make check` 运行 `sbt scalafmtCheckAll "scalafix --check"`。编译器开 `-Xfatal-warnings`：修掉 warning，而不是压制它。
- **前端**是原生 JS，没有 TypeScript 构建步骤。`scripts/run-checks.mjs` 是 checkout 静态检查成员的单一注册表；先运行 `npm ci` 安装锁定工具，不会隐式下载编译器或 linter。

### Checkout 静态质量门禁

```bash
npm ci
npm run check:static
npm run test:scripts
npm run lint:tools
npm run check:sdd
npm run check:sdd-changed -- --base main
npm run check:openspec -- --download        # 固定版本官方校验器，需要网络
node scripts/run-checks.mjs --profile static --list
make frontend-gates
make check-static
```

`test:scripts` 执行 `node --test scripts/tests/*.test.mjs`，`lint:tools` 仅检查本批新增/修改的工程工具。`make check-sdd`、`make test-scripts`、`make lint-tools` 对应上述 npm 命令。Node 专用目标不会探测 Maven 镜像或启动服务；`make check` 另含 Scala 编译、格式和 lint。已安装插件对账仍需单独运行 `make plugin-seed-parity`，不能当作仅有 checkout 就完成的验证。JVM、浏览器、运行实例和 live 插件检查均保持独立，需要各自环境与证据。

`static` 复用既有前端/CI 静态检查，加入工具 lint、脚本单测和 SDD 验证。每个具名命令记录耗时和退出状态，并有超时与心跳；启动失败、信号终止、超时都使门禁失败。`--list`/`--dry-run` 只列命令。默认无 base 验证全部 SDD 工件，不声称检查了变更路径覆盖；可选 `sdd-changed` 必须显式给出 `--base`，无效 Git 引用直接失败。结构/引用验证不会执行被引用的测试，也不证明语义正确。

类型门禁仅使用锁定的本地 TypeScript，将可识别的源码诊断与 `tests/type-baseline.json` 比较。配置错误、未知输出（含无法解释的缩进行）、失败却空输出、进程错误、信号或超时均拒绝通过。`--update` 仅在编译器输出合法后写入基线，必须用于经评审的债务减少，不能掩盖回归。静态 import 环仍会失败；确有需要时采用既有动态 `import()` 方式。

### 视觉回归基线

`tests/visual/` + `scripts/visual-baseline.mjs`（2026-11-01 采纳）以**零端口**装置渲染 6 个场景（`--list` 可列）的暗/亮两态——`page.route` 从磁盘供给 `src/main/resources/web` + MockWS 注入，结构上碰不到运行中的实例（更碰不到宿主 gateway 端口）——并把每张截图与入库基准图逐像素比对。

```bash
make check-visual              # = node scripts/visual-baseline.mjs --check --max-ratio 0.0005（门禁）
make check-visual VISUAL_MAX_RATIO=0   # 零差异严格把关
make visual-baseline           # = node scripts/visual-baseline.mjs --update  （重录基准）
node scripts/visual-baseline.mjs --check --filter canvas --determinism
```

基准图**按宿主平台键**分目录：`tests/visual/baseline/<process.platform>-<browserLabel>/`（如 `win32-msedge-156`、`darwin-chromium-151`）。像素跨 OS/浏览器构建不可比，因此没有本平台基准目录的宿主会逐张报 `MISS`——是红，绝不静默通过。`win32-msedge-156/` 是作者 Windows + Edge 宿主采集的那套，归属 **Windows runner 腿**；在别的宿主上先用 `make visual-baseline` 录一套自己的，人眼过一遍 `.nebflow/visual-baseline/<platform>/` 再提交 `tests/visual/baseline/<platform>/`。脚本默认严格零差异（`--tolerance` / `--max-ratio` 是显式放宽——真出现像素变化时放宽基线不是修法）；`make check-visual` 显式带 `--max-ratio 0.0005`，依据是两套已录宿主上两个 monaco 场景的 1px 页签边框存在 12–16 px 随机反走样漂移（0.002–0.003%，同轮双拍自身一致 ⇒ 宿主渲染噪声，非产品抖动）。要零差异把关跑 `make check-visual VISUAL_MAX_RATIO=0`（Windows runner 腿建议如此，那套基准是无噪声采集的）。前端改动可能动布局时在受影响 spec 之外加跑这一腿；GitHub CI 侧暂不跑（那边没有对应平台基准）。
