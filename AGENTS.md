# nebflow — Agent.md

项目级 agent 指令（取代 team rules.md）。分发器任务文本可引用本文件；Node 是 leaf（无记忆、无 Mail 身份、ephemeral），结果沿 out 边投递。

## 项目
Nebflow 是一个开源（MIT）AI Agent 编排平台。本仓为 **Scala 版**（main 分支），Rust 版由 nebflow-rust 独立负责，两个项目不要有耦合。

## 工作区
`/Users/dev/Claude code/Nebflow`（主仓；`.nebflow/` 已 gitignore，不落 repo）

## 过程件落位（禁落根 · 2026-09-13 作者裁定落地）

`.nebflow/` 根（`<workspace>/.nebflow/` 与 `~/.nebflow/` 的 `maxdepth=1` 层）只放两类东西：**引擎必需条目**（名单由脚本从 `src/main/scala` 机械生成，落 `.nebflow/tools/root-whitelist.json`，**禁手工增补**）与**索引/自述件**（`INDEX.md` / `README.md`）。**新产出禁止直接写根**：

- 证据（截图/原始输出/复现材料）→ `.nebflow/evidence/<YYYYMMDD>_<topic>/`
- 报告/台账（agent 读）→ `.nebflow/reports/<YYYYMMDD>_<topic>.md`
- 一次性脚本（验证/门禁/冒烟）→ `.nebflow/tools/<YYYYMMDD>_<topic>.{sh,py,mjs}`
- 日志/临时中间件（可弃）→ `/tmp`；引擎对账件落 `.nebflow/tmp/`（agent 禁写）
- 规格（引擎运行期消费）→ `.nebflow/Spec/`
- 人类交付/阶段文档 → `~/.nebflow/docs/<域>/`（命名/溯源见 `~/.nebflow/docs/CONVENTIONS.md` §1/§2 与「文件名尾溯源规范」）
- 人工备份 → `~/.nebflow/backups/`
- 定义层/数据根落地中转（交宿主消费）→ `<ws>/.nebflow/incoming/<YYYYMMDD>_<批次名>/`（显式契约见下）

**`incoming/` 通道契约（宿主落地中转；取代仓内 `staging/`——该树已 untracked + 被 `.gitignore` 的 `staging/` 忽略，工作区副本仅存主仓、worktree 内不存在）**——落位 `<ws>/.nebflow/incoming/<YYYYMMDD>_<批次名>/`，通道自述 = `.nebflow/incoming/README.md`：

1. **用途**：节点（含 worktree 内节点）把「定义层 / 数据根」的落地件**交给宿主**时的中转——**不是存档**。本目录随 repo `.gitignore` 的 `/.nebflow/` 整体忽略 ⇒ 中转件**永不进公开面**、**永不需要 `git rm`**。
2. **谁写（写入者身份与入口）**：写入者 = 在项目工作区内执行任务的 **agent 节点**（含 worktree 内节点），入口 = 本文件内的落位条目 + 该通道 `README.md`，逐件按**工作区根绝对路径**写入（相对路径随 `git worktree remove` 消失）；**宿主/作者是消费方，不写**。
3. **写什么（内容形态与命名）**：每批一份 `MANIFEST.md`，逐件一行 `<源绝对路径> | <目标绝对路径> | <sha256> | <动作：cp|rm|none>`；`sha256` 写入后**现取**（`shasum -a 256 <file>`），禁抄旧值；**禁写任何 git 跟踪路径**、**禁写 `~/.nebflow/agents/**` 与 `~/.nebflow/memory/**`**；**零凭据纪律**——任何凭据 / 密钥 / 令牌（API key / OAuth token / 私钥 / 会话 cookie / `auth.json` 类件）**一律不得入通道**（不加密、无访问控制），发现即在**写入侧**停手上报，不得先写上再补处置。
4. **何时清空（触发条件与执行者）**：① 探针批次（验证 / 冒烟 / 勘察）**由写入方在收尾时即删**，不等消费；② 交付批次由**写入方在宿主消费完成**（逐件 `cp` + 双侧 `shasum -a 256` 比对一致）**后删除**；③ 双侧 sha 不一致、或消费时发现凭据类件 ⇒ **消费方停手上报**，不得继续；④ 删除后三方断言：批次目录已删 / `ls <ws>/.nebflow/incoming` 仅剩 `README.md` / `git status --porcelain` 为 0 行。
5. **保留策略（保留期 / 上限 / 归档去向）**：**保留期** = 见第 4 条（探针批次收尾即删、交付批次消费完成即删、**用完即清**；**通道无固定天数、无归档职能**）；**上限（兜底）** = **空闲满 24h 的批次条目应清空**——判据 = 通道内除自述件 `README.md` 外的条目与批次目录的 **`mtime`**：早于 `now-24h` 者不得留存（与巡检 I-1 的 24h 宽限**同源同值**，可机械核：`find <ws>/.nebflow/incoming -mindepth 1 -maxdepth 1 ! -name README.md -mmin +1440` 应无输出），超限即违约，由巡检 / 宿主上报；**归档去向** = 需留存者落 `~/.nebflow/docs/<域>/`（人类交付 / 阶段文档，遵 `CONVENTIONS.md` §1/§2）或 `~/.nebflow/staging-archive-<YYYYMMDD_HHMMSS>/`（整树历史）。

**零存量处置**：本节不要求、也不授权对任何现存文件做搬移/改名/删除——存量处置另立实施批，前置 = 逐件引用面断言 + 回滚快照 + 悬空门禁。规范全文 = `~/.nebflow/docs/CONVENTIONS.md` §6。

巡检（只读）：`node .nebflow/tools/check-root-hygiene.mjs`（退出码非 0 = 基线之后的新增越界件）；名单一致性 `node .nebflow/tools/gen-root-whitelist.mjs --check`（**判据 = 允许类集合**：阶段文档等过程件落根**不判名单过期**，归巡检 I-1/I-2 + 基线增量，含 24h 宽限）；索引新鲜度 `node .nebflow/tools/gen-root-index.mjs --check`（表内条目集 vs 根层 `.md` 实数）。
**何时跑**：合并 / verify / 报告节点**收尾前**各一次（收口清单项）＋每日一次。**红了谁看见**：读数写进该节点报告（沿 `out` 边投递）⇒ 合并 sink 与分发器当轮可见 ⇒ 分类处置；🔴 禁长挂 RED。

## 技术栈
- **后端**: Scala 3, Pekko actors, http4s, cats-effect。源码在 `src/main/scala/`
- **前端**: Vanilla JS (ES modules), CSS, HTML。源码在 `src/main/resources/web/`
- **构建**: sbt。测试: `sbt test`
- **官网**: nebflow.space (Next.js on Vercel) — 见 nebflow-website 项目

## 分支与工作目录
- **主开发分支**: `main`（Scala 开发主线，2026-09-03 核对：main 上活跃 Scala 开发；旧口径「主开发分支 archive/scala」「main 已切换为 Rust」均已过时——archive/scala 分支已删除）；`beta`/`release` 为发版线
- **工作目录**: `/Users/dev/Claude code/Nebflow`
- **禁止触碰** `nebflow-rs/` 目录（那是 Rust 版的代码）和 `/tmp/nebflow-rust`（nebflow-rust 的工作目录）

## 协作分工参考（nebflow-project team 已于 2026-09-03 归档迁入 Project 架构；按方向组建节点，领域知识见 nebflow-* 系列插件，映射标准源见 .nebflow/Spec/skills-to-plugins.md）
- 后端代码（Scala, build, API, flow engine）→ 后端方向
- 前端代码（JS, CSS, HTML, UI）→ 前端方向
- 文档（CODEBASE.md, README, API 文档）→ 文档方向
- 系统提示词、工具描述、agent 定义优化 → prompt 工程方向
- 工具设计、实现、权限、Schema → 工具工程方向

## 协作协议（防双等，2026-08-27 作者令）

**RESULT 处置义务**：成员的 RESULT/汇报 = 完成回报 + 等待信号，不是计划文档不是 FYI——收到必须当轮明确处置并回复：a) 通过 → 派下一步或告知「已收 + 待命原因」；b) 不过 → 打回 + 具体意见；c) 需上报 → 转报并告知等待原因。禁止无响应搁置。

**对称义务**：①汇报结尾标注「等待指令」或「继续自治到 X」；②等待指令超 ~10min 未到 → 主动催办——不静默等待、不重复干活。

## 开发约定
- 先设计文档再写代码。最小最精准改动，避免过早抽象
- **provider 模型列表端点逐面声明（禁全局拼接）**：模型列表端点必须由各协议面自己声明（`nebflow.llm.providers.ModelListFaces` + `AnthropicAdapter` / `OpenAiAdapter` 的 `modelListUrls`），**禁止**在网关侧对 `baseUrl` 拼路径——`baseUrl` 是对话前缀，两个面对「版本段在哪」的约定不同（Anthropic 面自己补 `/v1`）。面的声明允许**有序候选**（只在「端点不存在」时前进）与**显式「不支持」态**（必须给出可判读的拒绝，不得报成空清单成功）；2xx 体内携带厂家错误信封（如 zhipu 的 `{"code":500,"msg":"404 NOT_FOUND"}`）判失败并带出原文，**不得**降级成「无模型」。改这两面（`src/main/scala/nebflow/llm/providers/**`、`RestApiRoutes` 的 provider 路由面）合并前必须过 `node scripts/check-provider-modellist.mjs`（离线静态、与 CI `provider-modellist` step 同判据；红了修代码，不得放宽断言）；需要行为面读数时跑 `--live`（含代理探活环境面前置）。判据详述见 `scripts/check-provider-modellist.mjs` 头部注释与 `src/main/scala/nebflow/llm/providers/ModelListFaces.scala` 的 scaladoc

## 版本与发布管理
- **VERSION 文件是唯一版本号来源**，格式 `MAJOR.MINOR.PATCH`（如 `1.1.12`）
- GitHub 仓库三个分支：`main`、`release`、`beta`
- **合并 feature 分支到 main** → 递增 PATCH（第三位 +1）
- **推送到 release 分支** → 递增 MINOR（第二位 +1），PATCH 归零
- **准备 beta** → 版本号追加 `-beta.N`（N 从 1 递增），基于下一个 minor 版本
- Release 必须有 `CHANGELOG.md`
- **合并一律 `git merge --no-ff`（无条件）** — 「发版场景才强制」的旧限定已作废；保留合并语义与双父历史，合并后 `git log -1 --format="%P"` 验双父；逐支串行 + 预检工作区 CLEAN + merge-base 核对
- **零 push / 零 tag / 零 VERSION** — 合并动作不改版本号（版本只在发版动作时按上文规则递增）

## Git 安全
- **禁止在 main 上直接修改** — 使用 `feat/`、`bug/`、`test/` 等分支
- **例外条款（2026-09-14 作者裁定；默认禁令不变；2026-09-17 作者补：seed 提示词文件归类）**：默认 🔴 **禁止直提 main** —— **代码 / 行为 / 构建 / 发布 / CI 配置类改动一律走 sink（`--no-ff` 合并提交）**。唯一例外 = **纯文档面**，判据**机械可判**：**仅 `*.md` 文档 / 规则 / 约定件**；🔴 **不含** `VERSION`；🔴 **不含** `.github/**`；🔴 **不含**任何构建 / 发布 / CI 配置与脚本；🔴 **不含** `src/**` 的代码与资源逻辑件（**`src/main/resources/seed/agents/**` 的提示词文本属例外内**）、`web/**`、`scripts/**`（`.nebflow/**` 本就 gitignore，不涉）。**归类：seed 提示词文件（`src/main/resources/seed/agents/**`）按文档面例外论 ⇒ 直提 main 合法**；**生效闸 = 启动镜像 digest 对账 + pre-sync 备份（`#667①` 在册族口径）——风险控制在镜像层、不在编译层**；**先例：本文件（`AGENTS.md`）全部历史即 main 单亲直提先例（复核读数：全史 27 笔中 25 笔单亲直提、2 笔为例外合并且均早于 2026-09-14 本例外裁定；2026-09-14 起 5 笔全单亲）——作论据引用，🔴 非授权扩张**。例外仍受约束：**单件单笔** + **按文件 `add`**（禁 `-A` / `-u` / 通配）+ **零 push** + **报告显式申报「形态 + 理由」**。🔴 **PR-only 生效 ⇒ 本例外自动失效**（届时连文档也只能经 PR 合并）。🔴 **禁靠「我觉得是文档」判** —— 一律按上述**路径判据**判；判据拿不准 ⇒ **走 sink（默认严）**。
- 切换分支前必须先保存当前分支进度（commit 或 stash），禁止未保存就切分支
- 合并必须等用户明确指示——不要自行 `git merge`
- **不 push**——除非触发方明确指示；推送与发布永远显式授权。

## 并行开发 — Worktree 工作流
多个 agent 同时工作时，**禁止共享同一个 working directory 切换分支**。每个并行任务必须使用独立的 git worktree：
1. 为每个任务创建独立 worktree：`git worktree add /tmp/nb-<task-name> main -b feat/<task-name>`
2. Mail 中告知工作目录路径；完成后 Manager 审查 → 合并 → 清理 worktree
3. 编译测试在自己的 worktree 里跑：`cd /tmp/nb-<task-name> && sbt compile` / `sbt test`——**禁止 `sbt run`**
4. 合并审查：简单改动（单文件、<50 行）直接审查合并；复杂改动（多文件、架构变更）先过 review 审查再合并（原 code-review flow 已蒸馏为 review skill，2026-09-03）。分支名与 worktree 目录名一致（`feat/<feature-name>` ↔ `/tmp/nb-<feature-name>`）

## 运行安全
- **🔴 环境表里的宿主 PID 绝对禁杀**（2026-09-05 裁定）：会话 Environment 注入了宿主 PID（GatewayMain 启动时 `ProcessHandle.current().pid()` 写入 system prop → environment/data.sh 渲染）——对它执行 kill/pkill/信号 = kill 宿主 = kill 自己和用户会话。宿主 PID 是第一道防线，逐字核对，无例外
- **自己起的测试进程跑完即清**（2026-09-05 裁定）：e2e/冒烟/测试结束必须清理自己 spawn 的进程——脚本用 `trap 'cleanup' EXIT`（后台 PID 登记 → EXIT 逐个 kill + wait + 端口复查），REPL/手跑用完显式 kill。不得依赖「会自己退出」：`while True` 死循环/长 sleep 残留要手动清
- **8080 端口识别仍是第二道防线**：**绝对禁止对 8080 宿主实例执行 kill、pkill、kill -INT/-TERM/-9 或任何信号发送**（2026-08-19 00:20 事故）
- **非宿主进程可按需管理**：隔离测试实例（端口 ≠ 8080）、canary 实例、静态文件服务允许 kill/信号——它们不是宿主
- **kill 前必须 PID 验身**：`lsof -ti :<端口>` 定位 + `lsof -p <pid> | grep cwd` 确认目标工作目录是目标实例，确认 PID ≠ 宿主再动手
- **shutdown/Ctrl+C 行为验证必须用隔离进程**，绝不动真实运行的宿主进程
- **禁止运行 `sbt run`**（默认 home + 默认端口 8080 会抢宿主实例）——需要起服务必须带隔离参数（`--home /tmp/qa-* --port 809x`）
- 需要验证编译用 `sbt compile`，需要验证测试用 `sbt test`——不要启动服务

### 隔离实例标准启动配方（P0 2026-09-06，宿主误杀事故产物）

QA/e2e/冒烟需要起真实 gateway 实例时**一律用本配方**：`NEBFLOW_GATEWAY_PORT` env 为 fork-proof 主保险（被任何 exec/fork 子进程继承，即使某层 argv 解析链断裂也不会回落 8080），`--port` 为冗余 belt（仅 nebflow.Main 全局旗标解析，一旦哪层 fork 只传 argv 也有 env 兜底）。**禁止 `java nebflow.gateway.GatewayMain` 直启**——它不是用户入口，带任何参数即 fail-fast 非零退出（2026-09-06 前 `--port` 被静默忽略、端口回落 8080，正是 09:07 宿主被杀事故根因链）。

```bash
P=8095; TMP=/tmp/qa-<task>; JAR=<构建产物 nebflow-assembly-*.jar>
# ── pre-flight：目标端口必须空（非空=换端口，禁止顶掉占用者）──
lsof -nP -iTCP:$P -sTCP:LISTEN                                   # 必须无输出
H8080_BEFORE=$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t | sort | tr '\n' ' ')   # 宿主监听集合快照
# ── 启动（--home 隔离数据目录；--no-browser 防弹浏览器）──
NEBFLOW_GATEWAY_PORT=$P java --add-opens java.base/java.lang=ALL-UNNAMED \
  -cp "$JAR" nebflow.Main --home "$TMP" --port $P --no-browser start &
echo $! > /tmp/qa-<task>.pid     # 记下自起 PID——收尾只准 kill 它，kill 前 PID 验身（≠环境表宿主 PID）
# ── post-flight 双断言：① 本实例 LISTEN 目标端口 ② 8080 宿主集合与启动前完全一致 ──
lsof -nP -iTCP:$P -sTCP:LISTEN                                   # 期望：仅自起 PID
H8080_AFTER=$(lsof -nP -iTCP:8080 -sTCP:LISTEN -t | sort | tr '\n' ' ')
[ "$H8080_BEFORE" = "$H8080_AFTER" ] && echo OK || echo "VIOLATION: 8080 host set changed"
```

8080 集合出现任何变化 = VIOLATION：立即停手并上报，绝不继续；收尾 kill 前先 `ps -p <pid> -o command=` 验身确认是自起实例且 PID ≠ 宿主 PID。

## 长跑与后台纪律（硬约束 · 五条）

以下五条一律携带：每个节点、每份任务书都必须写入。

1. 🔴 禁把取数/长跑放进 wait-set 后台任务；长跑一律**前台 + 显式 timeout + ≤60s 心跳 + 每步落盘**。
2. **30s 无输出 + 无 CPU ⇒ 被 stall watchdog 收割 ⇒ 判 failed**。
3. 同族 `#462`「涓流/零输出族」为**机制性复发、禁当偶发**；**任务书漏写即任务书缺陷**。
4. 无界扫描须**按路径分段 + `timeout 20~60`**、**一件一命令**、**逐段落盘**、**先小输出探针**。
5. 持续无输出 ⇒ **换方法、禁重试同一调用**。

## 分支基线与判词绑定（硬约束 · 三条 · 2026-09-14 作者立规）

动因：b64 批「支骑旧基 ⇒ 支 tip 天生编译不过 ⇒ 白跑一轮」。三条一律携带；分发器建位 / 复核位 / 落地位任务书逐条内嵌。

1. **起支起点 = 建位那一刻的 `main` tip**：新建实施位（worktree / 分支）起点一律取建位那刻 `git rev-parse main`，该 sha **逐批记入任务书基线栏**；🔴 禁用旧 tip / 禁复用旧 worktree 起点 / 禁从别支拓扑起。开工前复读：`git -C <ws> rev-parse main` + `git merge-base --is-ancestor <分支基> main`。
2. **批内 main 落了「影响编译」的修复 ⇒ 该批立刻重锚（不等轮次末尾）**：做法 = 新 worktree 从新 tip 起、delta 原样重放。判据逐条给读数：`git apply --check` 退出码 ≠ 0 即**停手**；`git add` 仅逐件显式路径；`--cached` 越界即 `git reset`；**逐件 sha256 两列全等**；`merge-tree` 零冲突；**被修文件逐字节等于 main 版**。🔴 禁原位 `rebase` / 禁原位 `reset`；🔴 禁用「parked 位 + 改任务书再唤醒」编排技巧 ⇒ 一律「**新位承接 + 原位保持不动**」。
3. **判词绑「内容树」，不绑「提交号」**：判词绑定被验对象的 **tree sha**（commit sha 作上下文）；落地能证明「**合并结果树 ≡ 判词树**」（`git rev-parse HEAD^{tree}` ≡ 支 tip 树 ≡ `merge-tree` 结果树）⇒ **免重跑复核**；**仅当合并改变内容（树不等）** 才在新基重取判词 —— 「**基线前移但内容零改写**」**不再**是重取判词的理由。落地报告仍须**同列**「判词绑定 sha」与「实际合并 sha」+ 逐件 sha256 全等。PR-only 后读数来源改**远端 merged commit 的树**（`gh api repos/<o>/<r>/git/commits/<sha>` 的 `tree.sha`，或 fetch 后 `git rev-parse <merge-sha>^{tree}`）、落地证据面改**远端 merged 态**；不一致 ⇒ **重取判词**（禁硬合）。

## 作者预览与端口纪律（2026-09-05 作者令）
- **通用规则（daemon 固定端口）**：项目开发内容一律走心跳进程（daemon）固定端口——换内容不换端口，禁止为看新改动另起新端口旁路
- **主仓特殊形态（宿主 8080）**：主仓前端预览入口为宿主 8080（非 daemon 端口），前端改动需宿主重启生效，重启窗口由 Nebula 统一安排
- **回报纪律（验证生效）**：任何「看效果」回报必须指向实际可访问入口且已验证内容=最新改动（前端改动未重启时必须显式声明「待重启生效」，不得让作者误以为已生效）

## 架构原则
- **Keep your Actors out of your cats-effect, and your cats-effect out of your actors**
- Actor 负责 message passing 和状态管理；cats-effect IO 负责副作用编排。两层不要交叉混用。

## 文档一致性
当改动涉及架构、工具系统、提示词机制、Agent/Team/Flow 定义等基础设施时，必须同步更新所有相关内容，保持一致：
1. **提示词**（`~/.nebflow/prompts/`）：更新受影响部分
2. **Agent 记忆**：清理过时条目
3. **文档**：README.md、CODEBASE.md（如存在）、代码注释——与实际代码一致
4. **工具描述**：与行为一致

**原则：代码改了，文档/提示词/记忆也要跟着改。不留过时内容。**

## Prompt hygiene (hard rules)

Agent prompt text is engineer-written text, not memory: keep it English, keep it stable, keep every face identical. **Scope = the three faces of agent prompt text only**: the in-repo seed (`src/main/resources/seed/agents/*/system.md`), the runtime copy (`~/.nebflow/agents/*/system.md`), and the code-embedded prompt literals (`Seeds.*.systemPrompt`). `AGENTS.md` itself and `.nebflow/Spec/*` are **not** in scope — they are repo-level engineering documents shared by humans and agents; widening the rule to them is a separate change. Memory files and project docs are not prompt text — they are where the content banned by rule 2 belongs.

**1. English only.** Prompt prose is English — zero CJK characters, zero full-width punctuation, zero full-width brackets (the machine-checkable part; check 1 below also catches the Chinese spellings of the markers named in rules 2-4). Keep the rest ASCII. The only tolerated non-ASCII is the typographic punctuation already widespread in the existing English prompts (em dash, en dash, arrows, `≤`, `×`) — keep those inside English sentences, never let one carry a Chinese fragment, and never read this carve-out as licence for non-English prose. Code identifiers, file paths, tool names, JSON enum values and necessary proper nouns pass through unchanged. A non-ASCII literal demanded by an external contract (a fixed output heading, a quoted user-visible string) is owned by the plugin/spec that defines it — reference the spec instead of inlining the literal.

**2. Stable rules only — no dated rulings, no history.** Prompt text carries the standing rule, never its provenance or its timeline. Forbidden: dated rulings (`(author decree <date>)`, `(author ruling <date>)`), supersede/void narratives (`supersedes the earlier version`, `the old rule is void`), incident and event-history narrative, and one-off ruling text pasted verbatim. Time-bound content belongs in memory, at its own layer: `~/.nebflow/User.md` (user-facing) / `~/.nebflow/agents/<name>/memory.md` (agent-facing) / `<workspace>/.nebflow/memory.md` (project-facing). The rule itself stays, rewritten as a dateless, sourceless English sentence — "Route every open question through AskUserQuestion" carries the full force of the same sentence followed by a dated citation.

**3. Violation test (mechanical).** Run from the repo root; a hit on any of checks 1-5 is a violation — rewrite the line as a dateless stable English rule and move the dated/incident content to the memory layer named in rule 2. Check 1 shadows the Chinese spellings of checks 2-5, so no Chinese pattern is needed.

```bash
P=(src/main/resources/seed/agents "$HOME/.nebflow/agents")
rg -n  --pcre2 --glob system.md '[\x{2E80}-\x{9FFF}\x{F900}-\x{FAFF}\x{FE30}-\x{FE4F}\x{FF00}-\x{FFEF}\x{3000}-\x{303F}]' "${P[@]}"   # 1 CJK / full-width
rg -n  --pcre2 --glob system.md '\b(19|20)[0-9]{2}[-/.][0-9]{1,2}[-/.][0-9]{1,2}\b' "${P[@]}"                                        # 2 dates
rg -ni --pcre2 --glob system.md '\bdecree\b|\b(author|user|owner)[- ](decree|ruling|injunction|order)\b' "${P[@]}"                  # 3 provenance citations
rg -ni --pcre2 --glob system.md '\bsupersede[sd]?\b|(?<!["`|])\bobsolet(e|ed)\b|\bdeprecat\w*\b|\bno longer (processed|supported|used|valid|in force)\b|\breplaces? (the|its) (old|previous|earlier)\b' "${P[@]}"   # 4 lineage / void narrative
rg -ni --pcre2 --glob system.md '\bincident\b|\bpost-?mortem\b|\boutage\b|\baccident\b|\bretrospective\b|\blearnings? (from|learned)\b' "${P[@]}"      # 5 incident / event history
rg -nc --pcre2 --glob system.md '[^\x00-\x7F]' "${P[@]}"   # advisory only: remaining non-ASCII (typographic marks fine, prose not)
```

Two properties the check must keep: (a) the CJK class is written with `\x{...}` escapes, so the checker itself contains no Chinese; (b) check 1 is the narrow class (CJK + full-width), not "any non-ASCII" — every compliant English prompt already carries typographic marks, so a blanket non-ASCII gate would be red on the whole corpus and carry no signal. Quoted enum values (`result="obsolete"`) are exempt as code identifiers; the narrative around them is not.

**4. One prompt, three faces — change them together, then byte-compare.** A prompt change must land on every face it exists on and be verified byte-for-byte, because the faces drift silently (`seed/general` and `seed/project-dispatcher` already differ from their runtime copies, while `seed/kernel` and `seed/memory-consolidator` are byte-identical):
- faces: `src/main/resources/seed/agents/<name>/system.md` ↔ the code-embedded literal `Seeds.<Name>.systemPrompt` (`src/main/scala/nebflow/agent/AgentLibrary.scala`) ↔ runtime `~/.nebflow/agents/<name>/system.md`;
- verify with `cmp`, not `diff`. Two traps: the code literal ends without a trailing newline (`AgentLibrary.scala:383` closes the delimiter on the text line) while every on-disk `system.md` ends in `0a`, so a blind copy flips the last byte; and `{{data_root}}` must stay a literal placeholder on the seed/code faces (it is substituted at runtime — expanding it in the seed breaks every install);
- seeding is per-family, not one rule for every agent:
  - **`Nebula`** — its runtime files are written only when absent (`AgentLibrary.scala:67` guards the write with `!os.exists`) and never overwritten afterwards — a seed edit does not reach an existing install by itself. `seed/manifest.json` lists no `Nebula` entry, so the startup reconcile pass never touches it;
  - **agents `seed/manifest.json` lists** (`project-dispatcher`, `general`, `kernel`, `memory-consolidator`) — reconciled against the seed digest at every startup; on a seed ↔ runtime digest difference the pass first takes a pre-sync backup (`<root>/agents-backups/<ts>_pre-sync-<name>/`, with `PRE-SHA256.txt`) and then mirror-overwrites from the seed — so a seed edit **does** reach an existing install on the next startup, unless the runtime copy holds content the seed lacks, in which case the pass writes nothing and logs the runtime-only lines;
  - **agents in neither list** (runtime-only agents such as `design-engineer`) — no repo-side seed exists, so the runtime copy is the sole authority and nothing reconciles it;
- existing exception: `src/main/resources/seed/manifest.json` does not list `Nebula`, so Nebula has no repo-side seed copy — its faces are the runtime file `~/.nebflow/agents/Nebula/system.md` plus the code literal `Seeds.Nebula.systemPrompt` (`AgentLibrary.scala:324-384`). For Nebula, three-face sync means runtime ↔ code literal.

## 前端规范
- 设计风格必须统一——弹窗、按钮、字体、配色等，能复用已有设计就复用，不要造新轮子
- 前端修改先预览确认再推送：主仓经宿主 8080（改动需宿主重启生效，重启窗口由 Nebula 统一安排，见「作者预览与端口纪律」）；其他项目走各自 daemon 固定端口——禁止另起新端口旁路
- **静态资源可达性验收（2026-08-16，P0 Canvas viewers 404 教训）**：任何新引用的 JS/CSS/模块 URL——**包括动态 import 的子模块**——必须在运行实例上实测返回 200。服务端静态路由按目录逐条挂载、http4s DSL 单段匹配，resources/ 里新增**子目录**必须同步加服务端路由；动态 import 链要整条验证
- **合并关卡（2026-08-16，质量路线 W1）**：前端改动（web/ 下任何文件）合并前必须过 `scripts/verify-web-assets.mjs`（遍历 web/ 全文件对真实实例断言 200，隔离实例跑）；新静态文件不可达 = 红 = 不合
- **WS 命令链冒烟（2026-08-18，P0 定时任务静默丢失教训）**：涉 WS 命令链批次合并前，另跑 `scripts/smoke-scheduled-task.mjs`（隔离实例，`NEBFLOW_URL`+`NEBFLOW_HOME_DIR` 指向隔离环境）——fire-and-forget 静默失败类回归的哨兵
- **checkJs 类型门（2026-09-10，CI 只管 push/PR 的漏检教训）**：前端改动（web/ 下任何文件）合并前必须过 `node scripts/check-js-types.mjs`（仓内钉版 tsc、禁 npx 网络拉取；拿 `tests/type-baseline.json` 比基线，与 CI `js-types` job 同判据）；新文件报错 / (file, TS code) 计数上升 / 总数上升 = 红 = 不合——红了修代码，**不得为过门改 `tests/type-baseline.json`**（禁以 `--update` 刷新掩盖新错、禁加或放宽条目）

## 文档产出路径（派发纪律）
派发含文档产出的任务（方案/规格书/设计/调研报告等）时，prompt 必须写明目标路径 `~/.nebflow/docs/Nebflow/`，禁止指定 /tmp。同秒重名用 `-<n>` 消歧；存量文档零改名、零搬移、零回改。权威规范见 `~/.nebflow/docs/CONVENTIONS.md`。

### 作者图文报告·图片内嵌（2026-09-14 作者令落地 · 硬要求）
给**作者**的图文报告（人读件；含 `~/.nebflow/docs/<域>/` 交付件与节点 result 一屏摘要）：
- **图片一律以图语法内嵌**：`![](<绝对路径>)`——🔴 **禁止**只给路径文本清单充当报告正文（作者 2026-09-14 10:13 裁定：只有路径没图 = 报告不合格）；路径/文件清单只能作**附录**或清单件（证据批次的 `MANIFEST.md`/`index.json` 即清单载体）。
- **大图集（> 20 张）**：正文只内嵌**决策相关子集** = 对照拼图（如 `cmp-*` 三态并排）+ 逐项裁定配对图（如 `adj-R<n>-opt{A,B}`）；全量清单进附录/清单件，禁逐张平铺正文。
- **内嵌路径须落在可服务面**（nf-file 白名单）：`<ws>/.nebflow/evidence*/**` 或 `~/.nebflow/{projects,uploads,plots,workspace-items,voice-models}/**`——`~/.nebflow/docs/**` 会被拒（reason `credential-path`）；工作区路径含空格为常态，作者侧渲染依赖 nf-file 编码面（在册缺陷 #487），未验证生效的报告须标注「作者侧渲染待验」。
- 执行面副本 = `~/.nebflow/plugins/nebflow-docs-prompt/skills/nebflow-docs/SKILL.md`「图文报告·图片内嵌」节（两处同步改，禁两套口径）。

## 用户裁定（User Rulings）
- 2026-08-17 ｜ 引导放工具说明里，不注入任何 system.md（agent 行为引导的载体是工具 description）
- 2026-08-18 ｜ "不能接受出新功能就功能不能用" ｜ 既有功能回归零容忍——涉 WS 命令链批次合并前必须跑定时任务冒烟；根因排查先验证"被改坏"假设再转机制性根因
- 2026-08-19 ｜ QA 由产出者直接触发（Backend→qa-backend / Frontend→qa-frontend，Mail 附验收条件），Manager 不做 QA 派发中转——只保留合并把关、收 PASS/FAIL 信号、FAIL 升级仲裁
- 2026-08-20 ｜ 用户明确表示已亲自检查/无需 QA 时，QA 环节免除直接收尾
- 2026-08-24 ｜ **Skill 创建流程 proposal 审批环节已废止**——新能力直接按 skill-creator 规范落地成 SKILL.md 或写入 memory；禁止再写 proposal 落盘
- 2026-08-25 ｜ 进程保护边界=只保护 8080 宿主实例；非宿主进程（端口≠8080）可按需 kill/信号；kill 前必须 PID 验身
- 2026-09-05 ｜ **宿主 PID 放进提示词作为系统环境**（绝对禁杀）+ e2e/测试跑完顺手 kill 遗留进程 + 脚本用 `trap 'cleanup' EXIT` 退出时自动清理——进程保护从「禁杀进程类型」细化为「环境里的宿主 PID 绝对禁杀；自起测试进程跑完即清」
- 2026-08-23 ｜ 成员需要跨 team 协作，先报 Manager 确认路由，不得直触对方 team 成员；同 team 内产出者直触 QA 模式不变
- 2026-08-26 ｜ ~~给 Nebula 的 RESULT/汇报邮件禁用 ask 模式~~ **已被 2026-08-27 裁定取代：Mail ask 模式整体移除**（delivery 只剩 immediate/queue）
- 2026-09-01 ｜ **QC 429 降级策略**——merge 触发自动 QC 遇 API 429 时，接受人工 QA 覆盖（qa-frontend/qa-backend PASS 即等价覆盖），配额重置后不单独补跑
- 2026-09-05 ｜ 预览端口纪律——daemon 固定端口/主仓 8080 重启形态/回报须验证生效（详见「作者预览与端口纪律」节）
