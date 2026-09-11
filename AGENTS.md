# nebflow — Agent.md

项目级 agent 指令（取代 team rules.md）。分发器任务文本可引用本文件；Node 是 leaf（无记忆、无 Mail 身份、ephemeral），结果沿 out 边投递。

## 项目
Nebflow 是一个开源（MIT）AI Agent 编排平台。本仓为 **Scala 版**（main 分支），Rust 版由 nebflow-rust 独立负责，两个项目不要有耦合。

## 工作区
`/Users/dev/Claude code/Nebflow`（主仓；`.nebflow/` 已 gitignore，不落 repo）

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

## 前端规范
- 设计风格必须统一——弹窗、按钮、字体、配色等，能复用已有设计就复用，不要造新轮子
- 前端修改先预览确认再推送：主仓经宿主 8080（改动需宿主重启生效，重启窗口由 Nebula 统一安排，见「作者预览与端口纪律」）；其他项目走各自 daemon 固定端口——禁止另起新端口旁路
- **静态资源可达性验收（2026-08-16，P0 Canvas viewers 404 教训）**：任何新引用的 JS/CSS/模块 URL——**包括动态 import 的子模块**——必须在运行实例上实测返回 200。服务端静态路由按目录逐条挂载、http4s DSL 单段匹配，resources/ 里新增**子目录**必须同步加服务端路由；动态 import 链要整条验证
- **合并关卡（2026-08-16，质量路线 W1）**：前端改动（web/ 下任何文件）合并前必须过 `scripts/verify-web-assets.mjs`（遍历 web/ 全文件对真实实例断言 200，隔离实例跑）；新静态文件不可达 = 红 = 不合
- **WS 命令链冒烟（2026-08-18，P0 定时任务静默丢失教训）**：涉 WS 命令链批次合并前，另跑 `scripts/smoke-scheduled-task.mjs`（隔离实例，`NEBFLOW_URL`+`NEBFLOW_HOME_DIR` 指向隔离环境）——fire-and-forget 静默失败类回归的哨兵
- **checkJs 类型门（2026-09-10，CI 只管 push/PR 的漏检教训）**：前端改动（web/ 下任何文件）合并前必须过 `node scripts/check-js-types.mjs`（仓内钉版 tsc、禁 npx 网络拉取；拿 `tests/type-baseline.json` 比基线，与 CI `js-types` job 同判据）；新文件报错 / (file, TS code) 计数上升 / 总数上升 = 红 = 不合——红了修代码，**不得为过门改 `tests/type-baseline.json`**（禁以 `--update` 刷新掩盖新错、禁加或放宽条目）

## 文档产出路径（派发纪律）
派发含文档产出的任务（方案/规格书/设计/调研报告等）时，prompt 必须写明目标路径 `~/.nebflow/docs/Nebflow/`，禁止指定 /tmp。同秒重名用 `-<n>` 消歧；存量文档零改名、零搬移、零回改。权威规范见 `~/.nebflow/docs/CONVENTIONS.md`。

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
