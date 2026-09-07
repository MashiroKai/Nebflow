# Docker 级沙箱方案（seatbelt 之外的 provider）— 分析与设计

> 阶段文档（20260906-09-07 批）｜状态：**final——M1 输入已冻结**（2026-09-07 作者裁定 A1-A10 全部落定，裁定记录见 §5；**A4-A5 为作者改向（参照 codex），以裁定为准、优先于本文件 §2.1/§2.5 原建议表述**——相关节已按裁定改写）
> 冻结版本落位：本文件随 M1 PoC 批 commit 进 `实施-M1Docker沙箱PoC与Spec冻结` 分支（基准 draft 存于主仓 `.nebflow/Spec/` 同名 untracked 文件，内容以本冻结版为准）。
> 批次边界：分析批（§1 取证）零产品代码改动；M1 批 = PoC 脚本 + 本 Spec 冻结 + 数据回填（§7），**零产品代码改动**；实现另起 M2/M3 批。
> 全文结论均附源码 file:line 或本机实测数据。

---

## §0 一句话目标

为 Bash 层引入第二个 OS 强制后端 provider（Docker 容器），让 agent 测试负载（隔离实例/构建/测试/浏览器类）跑在容器命名空间里——与宿主**端口零冲突、进程零互扰、缓存可写不再假绿**，同时保持现有 seatbelt 路径零回归。

**作者痛点 → 方案靶心映射**：

| # | 痛点 | 容器路径下的结局 |
|---|------|----------------|
| ① | 端口冲突（09:07 宿主被隔离实例击杀事故）、CPU/IO 争抢、浏览器/测试进程干扰桌面 | 容器独立 netns/PID ns——容器内 8080 ≠ 宿主 8080，此类事故**结构性不可能**（§1.3-T4 已实证预演）；资源争抢靠 VM 配额显性化 |
| ② | 补丁式 workaround：npx 缓存写拒、sbt 缓存重定向（`-Dsbt.global.base` 等）、HOME 重定向、tmp 隔离 home | 容器内文件系统自洽、缓存卷可写——**天然消失**（§1.4 逐条标注） |
| ③ | 沙箱自报「绿」不可信（check-js-types 门禁曾因沙箱禁写 npm 缓存假绿） | 容器内缓存可写、命令真实执行——**此类假绿消失**（§1.4-W2 源头实证） |

---

## §1 取证结论

### 1.1 现沙箱体系（源码级）

**三层结构**：

1. **策略层** `SandboxPolicy`（`src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala`）
   - 单一策略源：`root`（会话沙箱根）+ 全部根集合唯一推导点：
     - `writableRoots(p)` = `root :: nebflowDataRoot :: extraWritable ::: tempRoots`（SandboxPolicy.scala:259-262）——即「会话根 + ~/.nebflow 数据根 + additionalRoots + /tmp + java.io.tmpdir」
     - `readableRoots(p)` 恒为 `List("/")` 全盘读（SandboxPolicy.scala:274-275，2026-09-06 读宽写窄裁定）
   - 会话根推导 `sessionRoot()`（:205-218）：Nebula 根会话→dataRoot；worktree 节点→显式 `sandboxRoot`（工作区根，2026-09-05 21:05 裁定）→projectRoot→fallback。构造点在 `AgentCore.scala:908-933`；置位点 `NodeEngine.scala:844-853`（`sandboxEnabled = true, sandboxRoot = Some(workspace)`）；信号载体 `SessionContext.sandboxEnabled/sandboxRoot`（`agent/protocol.scala:824-831`）
   - 配置面 `SandboxConfig`（SandboxPolicy.scala:329-361）：nebflow.json 顶层 `sandbox` 节——`enabled`（默认 true）/`additionalRoots`/`bash.failIfUnavailable`（默认 true=fail-closed），fail-safe 加载（absent/非法→默认值）
2. **进程级后端** `SandboxBackend`（`src/main/scala/nebflow/core/sandbox/SandboxBackend.scala`）——**provider 抽象点**
   - trait 契约（SandboxBackend.scala:14-24）：`name` / `available: Boolean`（probe）/`wrap(argv, policy): Option[List[String]]`（argv 包裹，`None`=不可用/不支持）
   - 现实现 `Seatbelt`（:48-61）：`List("bash","-c",cmd)` → `/usr/bin/sandbox-exec -p <profile> -D SB_GIT_HOOKS=... -- /bin/bash -c <cmd>`；probe（:77-94）= 只读 profile 跑 `/bin/bash -c true`，exit 0 才可用；profile 由 `writableRoots` 渲染（:106-122），`(allow default)(deny file-write*)(allow file-write* 子路径)(deny .git/hooks)`，SBPL ≤900B，按根集合缓存
   - 注释明示预留：「预留 SandboxBackend trait（Linux 可移植性）」——**第二 provider 的插入点是设计内行为，不是新抽象**
3. **JVM 文件围栏** `FileSandbox`（`src/main/scala/nebflow/core/sandbox/FileSandbox.scala:31-135`）：五族文件工具（Read/Write/Edit/MultiEdit + Glob/Grep 根）写闸（:37-65，resolve→contain→fresh re-resolve，readDenied 负向规则一票优先）读闸（:72-80）

**运行时装配**：`SandboxRuntime`（SandboxBackend.scala:133-158）——`@volatile var backend`，`init(cfg)` 启动时 probe 一次缓存（**GatewayMain.scala:327-328 是唯一置位点**，硬编码 `new SandboxBackend.Seatbelt()`——provider 选择的改造点就在这两行+init 内部）；`Unavailable` 后端（:26-32）永远 wrap 不出。

**fail-closed 语义**（§A.4-4）：BashTool 入口（`BashTool.scala:322-335`）——`sandbox.enabled && !backend.available` → `failIfUnavailable=true`：返回 `SANDBOX_UNAVAILABLE`（SandboxBackend.scala:154-158）拒绝执行；`=false`：WARN + 结果加 `[unsandboxed]` 前缀（BashTool.scala:476-479）。Seatbelt 违规归因：stderr 含 "Operation not permitted" 时标注拒绝来源（BashTool.scala:483-492）。

**关键结论**：docker provider 与 seatbelt 同位——只接 Bash 层（`wrap` 契约 + probe 语义 + fail-closed 消息族），**FileSandbox 五族文件工具不动**（它们是宿主 JVM 内围栏，与 Bash 后端正交）。这把改造面收窄到：SandboxBackend trait 扩展 + 新 DockerBackend + SandboxRuntime.init 选择逻辑 + shell.scala 执行/看护集成点（§2.4）。

### 1.2 Bash 工具执行链（源码级）

**命令路径**：`BashTool.call`（BashTool.scala:322-335，fail-closed 门）→ `ShellSession.forSession(sessionId, initialDir = sandbox.root, sandbox = Some(policy))`（BashTool.scala:362/413）→ `shell.execute`（前台）或 `shell.executeBackground`（后台）→ `buildProcessBuilder`（shell.scala:319-368）→ `ProcessBuilder.start` + bracket。

**进程形态**（shell.scala）：
- argv：macOS `bash -c <command>`；沙箱开启时经 `SandboxRuntime.current.wrap(List("bash","-c",command), policy)` 包裹（shell.scala:346-351）——**docker provider 的 wrap 消费点**
- cwd：`pb.directory(safeCwd)`（:353-357）；初 cwd=沙箱根；每条命令后跑 `pwd` 更新会话 cwd（:106-124）——**cwd 持久由宿主侧会话状态承载，shell 状态本就不跨命令持久**（BashTool 描述原文，BashTool.scala:27）
- stdin：`redirectInput(/dev/null)`（:364）——无交互输入；stdout/stderr 分离（:365）；输出 poll-based 读取（readStream :895+，25ms 轮询 + 进程退出 250ms grace——孤儿管道持有者兜底）

**看护与超时语义**（容器化必须逐项保持/映射，§2.4）：

| 机制 | 现值/位置 | 语义 |
|------|-----------|------|
| 显式 timeout watchdog | shell.scala:607-612 | 到点 `ProcessTree.killProcessTree`（杀树 → 管道 EOF → timeout 毫秒级浮出） |
| 前台 no-progress ceiling | 10min（shell.scala:384, :572-597） | **无输出且无 CPU** → 杀（sleep-like 豁免） |
| 后台 B1 idle 杀 | 300s 无输出无 CPU（Defaults.scala:119；shell.scala:840-851） | 同上双条件 |
| 后台 B2 硬超时+停滞 | 30min（节点 4h，Defaults.scala:135/151）+ 120s 停滞窗（:138） | 超时后「零输出零 CPU」连续 120s 才杀 |
| CPU 采样 | `sampleProcessCpuTime`（shell.scala:401-410）：ProcessHandle descendants ∪ `ps -A -o pid=,ppid=,time=` 树遍历（:417-463） | **宿主进程树视角**——容器内进程对它不可见（§1.3-T6 实证） |
| kill 链 | `ProcessTree.killProcessTree`（util/ProcessTree.scala:38-67）：descendants∪ps-ppid 联合枚举 + 记忆 PID 集 + TERM→5s→KILL×2 扫 | 杀宿主进程树；调用点：watchdog/no-progress/B1B2/cancelBackgroundJob/killActiveProcesses/kill/bracket release（shell.scala:246,272,292,594,612,690） |
| exit code / 信号 | `proc.waitFor()+exitValue()`（shell.scala:542-545）；被看护杀 → `TimeoutException`（stuckFlag 路径 :668-684） | 退出码透传；看护杀以 TimeoutException 形态浮出 |

### 1.3 本机 docker daemon 实测（2026-09-07 分析批，全部轻量、已清理，记账见 §6）

**T1 daemon 现状**（`docker info`/`version`，只读）：
- Docker Desktop，client=server=**29.1.3**，API 1.52
- `aarch64` **原生 arm64**（宿主 M2 Pro / 12 核 / 16GB）；VM 配额 **8GB 内存 / 12 CPU**（宿主 16GB——sbt 并行构建是否够用待 M1，配额上调是作者裁定项）
- driver=overlayfs，cgroup=v2，runtime=runc
- **非空 daemon：分析批时点 9 容器（2 运行）/ 57 镜像——作者存量，全程只读未动**（含 eclipse-temurin:17-jre-alpine、nit-alpine320-{arm64,amd64}——本地双架构镜像构建已是作者既有工作流；M1 批时点 8 容器/44 镜像，同样只读）

**T2 镜像拉取**（alpine 13.6MB，预算内）：
- 直连 `docker.io`：**当日成功**（6.8s）——「直连易失败需国内 mirror」在本机当前网络下未复现，但按国内网络经验直连不可作为依赖
- 显式 registry 前缀 `docker.m.daocloud.io/library/alpine`：**成功**（0.9s）——mirror 形态可用；**daemon.json 全程未动**（红线遵守）
- 结论：镜像策略按「mirror 前缀拉取为准、直连为运气」设计（§2.1）；Makefile 的 `COURSIER_REPOSITORIES` aliyun 配方（Makefile:5-9 + scripts/detect-mirror.sh）先例可烘焙进镜像

**T3 容器启动延迟**（`docker run --rm alpine echo`，5 次）：**330/190/200/200/200ms**——warm ≈200ms。判定：秒级门槛远优，**per-command 容器形态对 QA 短命令都可接受，per-task 形态开销可忽略**（§2.5）。

**T4 端口命名空间隔离**（核心卖点实证）：
- 容器内 `nc` 监听 `:::8080`（容器 netstat 确认 LISTEN）期间：宿主 `lsof :8080` 三次快照**始终 = java PID 53186（宿主 Gateway，环境表实时值）**；宿主 `curl localhost:8080` 照常 HTTP 200（宿主网关响应）
- 发布端口通道：`-p 18080:8080` → `docker port` 显示 `0.0.0.0:18080→8080/tcp`，宿主 `curl 127.0.0.1:18080` 取回容器响应 `published-ok`——**QA 截图/作者预览通道可用**（注：首测空响应是我漏传 `-p` 参数的脚本错误，非 docker 行为）

**T5 kill 语义**（设计关键输入，实证两条）：
- 容器内进程对**宿主 `ps` 不可见**（进程在 Linux VM 内；`docker top` 才可见）
- **宿主侧 `docker exec` 客户端被 SIGKILL 后，容器内目标进程存活**（实测：杀 host 客户端 87527 → 容器内 `sleep 600` 仍活，PPID=containerd-shim）→ **现有 `ProcessTree.killProcessTree` 语义不穿透容器**；`docker rm -f` → 整个容器 PID namespace 全灭（5 进程容器 → No such container，零残留）
- 推论：docker provider 下所有 kill 调用点必须映射为容器销毁（§2.4）；「容器销毁=进程树全灭」比宿主清扫更干净（无 reparent 孤儿问题——ProcessTree.scala:10-27 治理的那类残留在容器形态下结构性不存在）

**T6 CPU 可见性**（由 T5 推论 + 实测佐证）：`sampleProcessCpuTime`（shell.scala:401-410）基于宿主进程树——docker exec 客户端自身 CPU≈0（等待 IO），容器内真实负载不可见 → **no-progress ceiling（10min）/B1（300s）/B2（停滞窗）会对「安静但忙」的容器任务（sbt 编译、无输出测试）误杀**。这是 docker provider 集成里最大的语义缺口，对策 §2.4-D。

**T7 挂载与路径一致性**（设计要点 2 实证）：
- `/tmp` 默认共享进 VM（bind mount 读写通）
- 主仓 workspace **只读挂载**可用（`touch` → `Read-only file system` 正确拒绝）
- **同路径挂载可行**：`-v "/Users/dev/Claude code/Nebflow:/Users/dev/Claude code/Nebflow"` → 容器内 `pwd` 与宿主路径**逐字节一致**，VERSION 直读成功——路径一致性方案成立（工具输出路径双向有效、git 操作透明）

**T8 IO 微基准**（256MB dd urandom 写+读 / 2000 个小文件，三处同法对照；dd from /dev/zero 会 APFS 压缩虚高故用 urandom）：

| 位置 | 大文件（写+读） | 2000 小文件 |
|------|----------------|------------|
| 宿主原生 /tmp | 1.14s（基线） | 0.184s（基线） |
| 容器 bind mount（VirtioFS，挂 /tmp 子目录） | 1.63s（**~1.4×**） | 1.01s（**~5.5×**） |
| 容器内 overlayfs（VM 本地） | 1.13s（≈基线 0.99×） | 0.442s（含 0.2s 启动，≈1.3×） |

- 结论：**VM 本地 overlayfs ≈ 原生；bind mount 元数据风暴 ~5.5× 慢**——大缓存（coursier/ivy/npm）必须落 VM 本地（缓存卷），不能放 bind mount 工作区；源码树读多写少可接受
- 边界（分析批钉死）：**sbt 大 IO 真实负载（compile/test 全程）的 VirtioFS 实测留给 M1**（本文件 §7-a 回填）；分析批仅微基准方向性结论 + 公开基准旁证（Docker Desktop VirtioFS 官方口径：顺序大文件接近原生、metadata 密集显著劣化，与本实测一致）

### 1.4 存量 workaround 全清单（逐条：出处 → 容器化后消掉/仍需保留）

| # | workaround | 出处（file:line） | 容器化后 |
|---|-----------|------------------|---------|
| W1 | sbt 缓存重定向 `-Dsbt.global.base=... -Dsbt.boot.directory=...`（沙箱拒写 ~/.sbt 的补丁） | `.nebflow/Spec/20260904_sandbox-readlist-verify-report.md:39`（「重定向后启动链全通」）；AGENTS.md 任务配方沿用 | **消掉**——容器内缓存卷可写（验收点⑥） |
| W2 | check-js-types 移除 `npx -y -p typescript@5.5` fallback（沙箱禁写 npm 缓存 → **静默假绿 PASS**，已改 loud-fail，但根因——沙箱内 npm 缓存不可写——未除） | `scripts/check-js-types.mjs:18-22`（注释原文记载假绿事故） | **消掉**——容器内 npm 缓存可写，npx 真实执行（验收点⑤） |
| W3 | 隔离实例标准配方：`--home /tmp/nb-* + --port 809x`（NEBFLOW_HOME 重定向 + 端口分配约定） | `scripts/smoke-headless.sh:6-8`；AGENTS.md 运行安全节；scripts 各冒烟脚本（8095/8096/8097/8123/8285/8300 散布） | **仍需保留**（实例数据隔离是实例层语义，与命令沙箱正交）；但**测试负载**进容器后 809x 端口竞争面缩小——容器内监听零冲突，仅发布端口池需要治理（§2.3） |
| W4 | trap cleanup 模式（`lsof -tiTCP:$PORT | xargs kill -KILL` + 信号路径显式 exit——zsh trap 陷阱 + 2026-09-05 残留治理） | `smoke-headless.sh:15-23`；AGENTS.md 2026-09-05 裁定 | **容器形态下天然消失**（`docker rm -f`=进程树全灭，T5 实证）；宿主直跑路径仍保留 |
| W5 | ToolsLogWriter/LlmLogWriter 以 user.home 定根、沙箱下写 `~/.nebflow/logs` EPERM | readlist-report:24 | 已被 2026-09-05「数据根入写面」裁定解决；容器路径无新增（日志由宿主 JVM 写，不经沙箱 Bash） |
| W6 | COURSIER_REPOSITORIES aliyun mirror + detect-mirror.sh | `Makefile:5-9`；`.github/workflows/ci.yml:11` | **仍需保留**——并**烘焙进镜像层**（§2.1） |
| W7 | HOME 重定向 / tmp 隔离 home（防写穿真实 ~/.nebflow） | `PathUtil.dataRoot` 推导纪律（SandboxPolicy.scala:243-248 注释）；e2e 脚本 fixture 惯例 | **仍需保留**（机制层：`setDataRoot` 跟随）；容器路径下测试命令的 HOME 即容器内路径，写穿真实 home 结构性不可能 |
| W8 | 端口治理注释（「8094=Manager gate, 8095=Frontend e2e — do not collide」人工记账） | `smoke-headless.sh:4` | **大幅缩小**——容器 netns 零冲突（T4）；剩余面=发布端口池分配（§2.3 固定段方案） |

**09:07 事故形态归位**：宿主被隔离实例击杀 = 「lsof+kill 宿主端口进程」这一动作本身出错。容器路径下测试服务不占宿主端口、清理动作是 `docker rm -f <自家容器>`——**该动作从配方中消失**，事故类别消除。

---

## §2 设计（逐项方案 + 取舍；A4-A5 相关节已按 2026-09-07 作者改向改写）

### 2.1 镜像策略

> **裁定更新（A4-A5 改向，2026-09-07）**：镜像定位从「单一 nebflow-sbx-base 大而全」转向**轻量工具链模板**——每次按任务实例化容器（codex 形态），镜像只承载最小工具链，alpine 系优先、ubuntu 作 musl 兼容兜底；M1 双 build 实测对比（§7-e）供 A5 终裁。缓存 named volume 方向保留（T8 实测未被裁定推翻）。

**分层（轻量化后）**：

```
L0 base:      alpine（musl，~14MB）优先 / ubuntu:24.04（glibc 兜底，本地已有）
              ＋ 包管理国内源（apk=USTC / apt=USTC）
L1 工具链:    openjdk-21 JDK ＋ git ＋ bash ＋ procps ＋ python3 ＋ 常用 CLI
              ＋ sbt-launch.jar（单 jar 启动器，非完整 sbt 发行版）
L2 源配置:    COURSIER_REPOSITORIES（detect-mirror.sh 结果，ENV 烘焙进镜像）
L3 缓存:      **不进镜像层**——coursier/sbt/npm 大缓存放 named volume（T8）
```

**取舍**：
- **镜像=工具链模板，容器=任务实例**（裁定核心）：镜像构建一次、按任务反复 `docker create` 实例化（§2.4-A per-task 模型）——镜像保持轻（目标：alpine 系 ≤400MB 量级），不烘任何项目依赖/缓存。
- **冷启动 vs 缓存命中**：T3 实测 warm 容器启动 200ms——启动不是瓶颈；瓶颈是缓存未命中时的网络拉取。故**大缓存不放镜像层、放 named volume**（`nebflow-cache-coursier` / `nebflow-cache-npm`，VM 本地 ≈原生速度，T8）——缓存卷独立演进去重。备选（缓存全烘进镜像层）：不可变、可复现，但每次依赖变化重建镜像、磁盘膨胀——**不取**（A4 裁定）。
- **arm64 原生 + x86 依赖兼容**：daemon aarch64（T1），本机同架构原生跑——**无 Rosetta/QEMU 需求**；多架构构建（buildx）仅 CI 分发用。
- **构建落点**：本地构建为主路径（`make sandbox-image` 复用 detect-mirror.sh 机制，具体落点 M2 定）；CI 构建+分发为 M3 可选。
- **拉取策略**：mirror 前缀为准（T2 实测 daocloud 可用）、直连为运气；**不动 daemon.json**（产品红线——镜像内烘焙 mirror，不碰宿主 daemon 全局配置）。

### 2.2 workspace 挂载与路径一致性

**方案：同路径 bind mount**（T7 实证）——`-v "$WORKSPACE:$WORKSPACE"`，容器内外路径逐字节一致。

**分工铁律**：**只有 Bash 进容器**；Read/Write/Edit/Glob/Grep 仍走宿主 JVM + FileSandbox（§1.1 结论）——docker provider 与 seatbelt 同位，是 Bash 层 OS 后端。由此：

- **Flow Map / 主仓在宿主、工具在容器跑**的矛盾消解：同路径挂载下容器内 git/构建直写宿主仓（worktree 元数据 `.git/worktrees/<name>/` 在工作区根内——与 `sandboxRoot=工作区根` 裁定语义对齐，SandboxPolicy.scala:15-22）；**git 操作落容器手上（Bash 命令），产物落宿主盘上（bind mount 直写）**——无双份状态。
- RW 默认（节点要 commit/产物落仓）；RO 挂载形态可用（T7 实证写拒正确）——M2 可加 per-mount 选项，默认不开（最小面）。
- **性能**：源码树读多写少，bind mount 可接受（T8）；**target/ 与 node_modules 可选 volume-over-dir**（`-v nebflow-cache-target:<ws>/target` 具名卷盖目录——编译产物 VM 本地速度，代价=宿主侧不可见 class 文件/产物）。是否启用**由 M1 PoC 实测数据裁定**（§7-a 回填，A6）；默认先纯 bind mount（保产物宿主可见）。
- **dataRoot（~/.nebflow）默认不挂**：节点 Bash 少量涉数据根操作（git commit 项目记忆在工作区内，不涉 dataRoot）；需要时按需 RO 挂。挂 RW 须显式配置（面收窄）。
- 工作区在容器内的写穿面：bind mount RW = 容器内可写整个工作区——与 seatbelt 现语义一致（写根=工作区根+数据根+tmp），**无扩大**；容器 FS 其余部分（/、/etc、/usr）天然隔离（写穿宿主不可能——这是对 seatbelt 的净改进：seatbelt 只能拒写，容器根本看不见宿主 FS）。

### 2.3 端口与网络

- **默认 bridge（独立 netns）= 端口零冲突**——核心卖点（T4 实证，写成验收点①）。容器内监听 8080/809x 任意端口互不冲突、不碰宿主。
- **宿主访问容器内服务的端口发布**（QA 截图、作者预览）：
  - **固定段池方案（推荐）**：engine 分配 `8100-8199` 段（-p 8180:8080 形态），分配记账进会话元数据，端口写进 Bash 结果回执（agent 可见可转述）；段池可预期、便于宿主过滤层白名单。
  - 备选 `-P` 随机+`docker port` 回查：零治理但端口不可预期，QA/预览链路转述成本高——不取为默认。
- **egress**：默认 bridge 直出 = 真实国内网络观测（A8 裁定），对「验证国内环境下拉包」类任务是正确语义；收紧白名单（egress allow-list）为 **M3 可选**（有真实需求再上，避免过早抽象）。
- **宿主网络过滤层 vs 浏览器**：M1/M2 形态下 QA 的 playwright 跑**宿主侧**打 `localhost:发布端口`（A7 裁定先行形态）——宿主进程级过滤行为不变（不因容器绕开）；**浏览器进容器**（headless chromium 容器镜像）走 VM 网络栈、绕开宿主进程级过滤，且顺带解决痛点①的浏览器干扰桌面——M3 候选形态。

### 2.4 执行链集成（改造面最准确的一节）

**A. 容器形态：per-task 轻量容器 + docker exec 单命令**（**A4-A5 作者改向后的主形态**，2026-09-07 冻结；原 per-session 方案的 exec 语义全部保留，生命周期改为任务级 retention 模型）

- **镜像=工具链模板（构建一次复用），容器=任务实例（按任务创建）**：
  1. **任务激活**：任务开始（首条沙箱 Bash 或任务预创建）→ ensure 任务容器 `nebflow-sbx-<taskKey>`（`docker create+start`：同路径 workspace 挂载 + 缓存卷 + netns bridge + 发布端口预留）。冷 create+start ~300ms（T3）。
  2. **命令执行**：`docker exec -w <cwd> nebflow-sbx-<taskKey> /bin/bash -c <cmd>`——与原方案逐字相同。
  3. **任务完成 → retention**：容器**不立即销毁**，保留 TTL（**缺省建议 30-60min，可配**）。
  4. **再激活复用**：retention 期间同任务/同 session/project 再次激活（任务重跑、续跑、追加命令）→ `docker start` 秒级唤醒（warm ~200ms，T3）——**文件系统状态保持**（容器 fs 未销毁，缓存半成品/zinc 增量/临时产物直接复用；M1 PoC-c 实证）。
  5. **TTL 到期 → auto-destroy**：retention 期间无再激活 → `docker rm -f`。**驱动实现位 M2 定**：引擎侧 TtlTick（宿主 JVM 定时扫 retention 容器）或容器侧 `--rm`+超时机制，二选一按 M2 集成面裁定。
- **归属键**：taskKey↔容器名映射、retention 期间「再激活」的匹配规则（同 taskId？同 session 同 project 合并复用？）——**M2 细化**，M1 只冻结模型与 PoC 证据（§7-c）。
- **语义保持论证**：cwd 持久=宿主 ShellSession 现有 pwd 机制 + `-w` 传参（今日每条命令本就是独立 `bash -c`，状态不持久——BashTool.scala:27 原文语义零变化）；stdin 不传 `-i`（=今日 /dev/null）；stdout/stderr 分离照旧；**exit code 经 docker exec 原样透传**；显式 timeout 到点 → 容器销毁 → 管道 EOF → `TimeoutException` 浮出（与今日 stuckFlag 路径同构，shell.scala:668-684）。
- per-command `docker run --rm`（M1 PoC 简化形态）：200ms/命令（T3）可接受，但 per-task 形态下任务内命令共享容器状态（cwd/环境/缓存半成品）——**M1 用脚本手工编排，M2 统一 per-task 容器**。
- **NodeRunner/子 agent 会话归属**：容器=per-task；同节点多轮 Bash 命令落在同一任务容器内（任务=节点任务粒度）——与今日 ShellSession 生命周期同构放大一档，无新归属维度。

**B. trait 扩展（最小面）**：`SandboxBackend` 增 lifecycle 方法（带默认实现，Seatbelt 全 no-op+沿用现 wrap——**零回归**）：

```scala
trait SandboxBackend:
  def prepareSession(policy): Option[BackendSessionHandle]   // docker: ensure 任务容器（含 retention 复用判定）
  def execShape(handle, argv, cwd): List[String]             // docker: docker exec -w ... 形态
  def destroySession(handle): IO[Unit]                       // docker: rm -f；seatbelt: no-op
  // retention/TtlTick 归属：handle 生命周期内由 backend 自管（M2 细化，可能上探为独立接口）
```

- 置位点不变：`SandboxRuntime.init`（GatewayMain.scala:327-328）按 config 选 Docker/Seatbelt/Unavailable——**provider 选择的唯一闸门**，与今日 fail-closed 语义同源。
- 消费点：`buildProcessBuilder`（shell.scala:346-351）改调 `execShape`；ShellSession 构造/终局挂 `prepareSession/destroySession`。

**C. kill 映射（T5 实证驱动）**：现有三族 kill 调用点（前台 watchdog/no-progress、cancelCurrentTurn→killActiveProcesses、会话 kill/后台取消——shell.scala:246,272,292,594,612,690）在 docker provider 下统一映射 `docker rm -f <任务容器>`：

- 容器 PID namespace 全灭（T5 实测零残留）——**比宿主 ProcessTree 清扫更干净**：reparent 孤儿（ProcessTree.scala:10-27 治理的 #22 事故形态）在容器形态下结构性不存在。
- 挂起会话/AgentControl restart 的 `killSessionProcesses` 组合 → `destroySession`——语义「容器销毁=进程树全灭」。kill 的容器**不进 retention**（被 kill=异常终局，直接销毁；retention 只属于正常完成的任务——M2 细化）。
- 宿主侧 ProcessTree 保留：杀 docker exec 客户端本身（防客户端卡死），但**权威清理是容器销毁**（客户端死≠容器内进程死，T5 实证）。

**D. CPU 进度盲区对策（T6，集成最大语义缺口）**：宿主 `sampleProcessCpuTime` 看不见容器内进程 → 10min no-progress / 300s B1 / B2 停滞窗会**误杀安静忙任务**（sbt 编译、无输出测试套件）。两层对策：

- **M2 正解**：backend 提供 `progressProbe(handle)`——`docker stats --no-stream --format {{.CPUPerc}}` 采样容器整体 CPU（含全部容器内进程），shell.scala 三处看护在 docker 会话下以该探针替代宿主进程树采样（输出探针照旧）；采样开销以 M1 PoC-b 实测为准（§7-b 回填）。
- **M1 过渡口径**：docker 会话禁用 CPU 判杀（只保留输出判据+硬超时）——宁可少杀不误杀，PoC 期任务全部短时。
- 硬超时本身不受盲区影响（时间驱动），4h 节点档（Defaults.scala:151）照常兜底。

**E. 沙箱内无 docker 通道**：任务容器**不挂 docker socket、不装 docker CLI**——容器内代码不能反操作 daemon（mount 宿主路径等逃逸动作不可达）。engine（宿主 JVM）独占 docker 客户端。另：BashTool DangerousPatterns 已拦 `docker system prune` 类（BashTool.scala:124）——双保险。验收点④。

### 2.5 生命周期与成本（A4-A5 改向后重写：per-task retention 模型）

- **生命周期四段**：任务激活（create+start，冷 ~300ms）→ 命令执行（docker exec N 次，每次 ~100-200ms 开销）→ retention（**TTL 30-60min 可配**，容器 stopped/exited 态驻留，文件系统保持）→ 再激活（`docker start` warm ~200ms + fs 状态直续）或 TTL 到期 auto-destroy（`docker rm -f`）。
- **retention 的价值**：任务重跑/续跑/验收复验时**免冷启动**——JVM/sbt/zinc/依赖缓存半成品全在容器 fs 与缓存卷里，再激活即用；这是 codex 形态的核心收益，M1 PoC-c 全程计时实证。
- **成本模型**（作者资源视角，M1 实测后回填精确值）：
  - 镜像：轻量工具链 ×2（alpine/ubuntu）一次性，目标 alpine ≤400MB 量级（§7-e 实测）。
  - 缓存卷：coursier/sbt/npm 共享 named volume 1-3GB（跨任务复用、独立演化）。
  - retention 容器驻留：每个驻留容器占 overlay 可写层磁盘（任务产物量级，通常 <1GB）——**磁盘预算须含「并发任务 × retention 驻留」项**（A10 ≤4GB 总盘算内）。
  - 运行内存受 VM 8GB 配额（多容器并发上限≈2-3 个 sbt 级任务；配额先观察，A9）。
- **warm pool 不做**（M3 再议）：per-task retention 已提供复用，冷 create 仅 ~300ms；仅当 M2 实测暴露并发首命令热点才值得。
- **镜像预拉取**：engine 启动 probe（`docker info` + `docker image inspect nebflow-sbx-*`，缺失→mirror 前缀拉取，一条日志）；不阻塞启动（异步拉、期间 provider 视为 unavailable 走既定降级链）。
- **短命令 vs 长任务判定**：QA 截图类（秒级命令+浏览器宿主侧）——开销无感；sbt compile/test（分钟级）——容器启动占比 <1%，真正的变量是 VirtioFS IO（§7-a 实测定 A6 target 卷方案）。

### 2.6 可用性与降级

- **配置面**（最小面，M2）：nebflow.json `sandbox` 节扩一字段——`"provider": "seatbelt" | "docker" | "auto"`（缺省 `seatbelt` = 今日行为零变化，渐进灰度安全；A2/A3 裁定）。
- **fail 语义对齐 `bash.failIfUnavailable`**（§A.4-4 不变量）：
  - `provider=docker` 且 probe 失败（daemon 停/镜像缺）：`failIfUnavailable=true` → `SANDBOX_UNAVAILABLE` 拒绝（消息族沿用，SandboxBackend.scala:154-158 同款结构）；`=false` → `[unsandboxed]` WARN 降级（今日语义原样）。
  - `provider=auto`：docker probe 失败 → **静默回落 seatbelt**（WARN 日志一条）——auto 的降级链是 docker→seatbelt→（都失败才）fail-closed/unsandboxed，三级。
- **项目/节点级覆盖**：不做（配置面最小化，A3 裁定）；`SessionContext.sandboxRoot` 先例（protocol.scala:831）表明信号链已通，真需求出现再加 `providerHint`。
- **与 plugins 体系**：不进 plugins——sandbox 是核心安全配置（今日也在 nebflow.json 顶层 `sandbox` 节），不是可插拔能力（A3 裁定确认）。

### 2.7 安全诚实分级（必须写进产品的陈述）

**威胁分档与两 provider 的真实边界**：

| 档 | 威胁 | docker provider | seatbelt provider |
|----|------|----------------|-------------------|
| T0 事故类 | 误杀宿主进程、端口抢占、缓存写拒、垃圾文件污染 | **全防住**（独立 netns/PID ns/FS，T4/T5 实证） | 部分（写面收窄；进程/端口不隔离） |
| T1 不可信依赖 | 恶意 postinstall/供应链代码 | macOS 上容器跑在 Docker Desktop Linux VM 内——**VM 边界隔离内核与 FS**（强于 seatbelt 的 syscall 策略）；共享宿主 CPU/RAM/盘与挂载面 | syscall 策略拒绝越界写——能挡一部分 |
| T2 主动恶意 | 以宿主为目标的对抗代码 | **不宣称**——VM 逃逸/挂载滥用/engine API 是真实攻击面（缓解：socket 不入容器、无 docker CLI、DangerPatterns） | **不宣称**——sandbox-exec 非 VM，是内核策略 |
| 结论 | | 两 provider 都**只对 T0+T1 负责**；T2 明确不承诺（与 Codex 容器沙箱同款诚实口径） | |

补充澄清：macOS 上 seatbelt（sandbox-exec profile）**不是 VM**——它是内核级 syscall 策略；Docker Desktop 容器反而在 Linux VM 边界内。**「docker=共享内核弱于 VM」的通用 Linux 说法在 macOS Docker Desktop 形态下不适用**（容器与 macOS 宿主之间隔着 VM 边界；「共享内核」发生在容器与 VM 之间）。此澄清按任务书要求原文进 Spec，避免以讹传讹。

---

## §3 验收点（二进制，M1/M2 分别覆盖）

| # | 验收点 | 判定 | 批次 |
|---|--------|------|------|
| ① | 容器内监听 8080，宿主 8080 服务不中断 | 起容器监听 8080 → 宿主 `curl :8080` 返回宿主网关 200 且 `lsof :8080` PID 不变 → 容器销毁后复测仍 200 | M1 脚本固化（分析批 T4 已手工预演） |
| ② | 宿主 kill 容器 = 进程树零残留 | 容器起多级后台子进程 → `docker rm -f` → `docker ps -a` 无该容器 + `docker top` No such container + 宿主 `ps` 无残留 | M1（T5 已预演） |
| ③ | docker 停止时 fail 行为可配（对齐 failIfUnavailable） | 停 Docker Desktop：`provider=docker,failIfUnavailable=true` → SANDBOX_UNAVAILABLE；`=false` → [unsandboxed] WARN；`provider=auto` → 回落 seatbelt 且 Bash 可用（日志一条 WARN） | M2 |
| ④ | 沙箱内命令不可达 docker | 会话容器内 `docker ps` → command not found；socket 未挂载（`/var/run/docker.sock` 不存在） | M1 |
| ⑤ | npx 假绿消失 | 容器内 `npx -y -p typescript@5.5 tsc --version` 真实执行 exit 0 且输出版本号（缓存卷可写） | M1 |
| ⑥ | sbt 零重定向 | 容器内裸 `sbt compile`（无 `-Dsbt.global.base` 等 flag）成功——缓存卷可写 | M1 |
| ⑦ | 发布端口通道 | `-p 8180:8080` 起服务 → 宿主 `curl localhost:8180` 200 → 端口写进 Bash 回执 | M1（T4 已预演） |
| ⑧ | 会话 cwd 持久 | 连续两条容器内 Bash，第二条 `pwd` = 第一条 `cd` 后的目录（`-w` 传参链） | M2 |
| ⑨ | CPU 看护不误杀 | 容器内 busyloop 15min 零输出 → **不被** no-progress/B1 杀（progressProbe 生效）；对照：有输出的卡死任务仍被硬超时兜底 | M2 |
| ⑩ | 资源记账 | 一批节点跑完：`docker ps -a` 无 `nebflow-sbx-*` 残留、缓存卷保留、磁盘增量 ≤ 预算（A10） | M2（含 CI 常驻断言） |
| ⑪ | per-task retention 生命周期 | 任务完成容器驻留（fs 状态保持）→ 再激活 `docker start` 秒级唤醒且状态可读 → TTL 到期 auto-destroy 零残留（A4-A5 改向后新增） | M1 脚本预演（§7-c） |

---

## §4 分期（M1 = 本批：PoC + Spec 冻结）

**M1 取证+PoC（零产品代码，脚本+Dockerfile；产物落 `scripts/sandbox-poc/`）**
- **PoC-e 镜像**：`images/{alpine,ubuntu}/Dockerfile` 双工具链镜像 build + 计时 + 大小对比 + JVM musl 兼容验证（A5 终裁输入）。→ §7-e
- **PoC-a sbt 真实负载 VirtioFS 实测**：compile+test 全程计时，三臂对照（宿主裸跑 / 容器 bind mount·target 落 VirtioFS / 容器 bind mount·target 落 named volume）→ A6 裁定数据。→ §7-a
- **PoC-b progressProbe 可行性**：`docker stats --no-stream` 采样开销与稳定性实测（§2.4-D 依据）。→ §7-b
- **PoC-c per-task 生命周期**：create→执行→retention→再激活复用（fs 状态保持）→TTL auto-destroy 全程计时与资源记账（§2.5 模型实证）。→ §7-c
- **PoC-d 前端 QA 隔离实例进容器全链**：fat JAR 容器内起隔离实例（NEBFLOW_HOME 容器内路径 + 发布端口）→ 宿主 playwright 截图验收 + verify-web-assets 资产断言 → `docker rm -f` 零残留。覆盖验收点①②④⑤⑥⑦。→ §7-d
- 产出：PoC 报告回填本 Spec §7（M2 输入冻结）。

**M2 provider 集成（产品代码）**
- trait lifecycle 扩展 + DockerBackend 实现（per-task 容器 + retention/TtlTick）+ `SandboxRuntime.init` provider 选择 + 配置面（`sandbox.provider`）+ shell.scala 集成（kill 映射/progressProbe/cwd `-w`）+ SandboxSpec 单测（含 G.1 回滚用例扩展：provider=seatbelt 缺省路径逐字节回归）+ 隔离实例 E2E。验收点③⑧⑨⑩。

**M3 优化（按 M2 实测数据裁剪）**
- warm pool（仅若实测显示冷路径热点）；浏览器进容器 QA 形态（A7）；egress 白名单；缓存卷治理（LRU/预算）；CI 镜像构建分发。

---

## §5 作者裁定记录（2026-09-07 落定，A1-A10 全项）

> 逐字基准：A4-A5 以本裁定为准而非本文件原建议表述；相关设计节（§2.1/§2.4-A/§2.5）已按裁定改写。

| # | 裁定项 | 裁定结果 |
|---|--------|---------|
| A1 | **分期落地顺序** | **按建议**：M1→M2→M3 串行（M1 数据冻结 M2 设计） |
| A2 | **provider 默认值** | **按建议**：缺省 `seatbelt` 灰度渐进 |
| A3 | **配置面形态** | **按建议**：全局 `sandbox.provider` 一字段；**无项目/节点级覆盖、不进 plugins** |
| A4 | **镜像与容器生命周期落点** | **作者改向（参照 codex）**：每次按任务构建轻量 docker 容器，任务完成后容器保留一段时间，再次激活时复用，超时自动销毁。镜像随之偏轻量：alpine 系最小工具链优先，ubuntu 作 musl 兼容兜底，M1 顺手双 build 对比（缓存 named volume 方向保留——T8 实测未被推翻） |
| A5 | **基础镜像选型** | **随 A4 改向**：alpine 优先 + ubuntu 兜底，**M1 双 build 实测后终裁**（§7-e 数据） |
| A6 | **sbt target / node_modules volume-over-dir** | **按建议**：默认不启用、以 M1 实测数据说话（§7-a 回填后终裁） |
| A7 | **QA 浏览器形态** | **按建议**：宿主侧 playwright 打发布端口先行；浏览器进容器排 M3 |
| A8 | **egress 策略** | **按建议**：默认直出=真实国内网观测 |
| A9 | **Docker Desktop VM 配额** | **按建议**：维持 8GB/12CPU 先观察 |
| A10 | **磁盘预算** | **按建议**：镜像+缓存卷+retention 驻留总量 ≤4GB |

---

## §6 红线遵守与资源记账（分析批执行报告，2026-09-07）

- **零产品代码改动**：全程只读源码取证；唯一写入=本 Spec 文档（`.nebflow/Spec/` 层）。
- **宿主 8080/PID 53186 绝对未碰**：三次 `lsof :8080` 快照（测试前/中/后）均=java 53186；`curl :8080` 全程 HTTP 200（宿主网关照常服务）；无任何 kill/信号指向宿主进程。
- **daemon.json 未动**；无 daemon 全局配置变更。
- **docker 资源记账**：创建容器 4 个（nfsbx-port/nfsbx-pub×2 重用/nfsbx-tree）全部 `docker rm -f` 清净（终态 `docker ps -a` 零 nfsbx）；零新增 volume（未用 named volume）；拉取镜像 2 tag（alpine:latest 直连 + daocloud tag）——daocloud tag 已 `docker rmi`；`alpine:latest`（13.6MB）保留。作者存量容器/镜像全程只读。
- **/tmp 实测产物**（nfsbx-bench/nfsbx-mnt，含 256MB×2 临时文件）已 `rm -rf`。
- **与在飞批零文件交集**；**凭据/token 未打印**。

---

## §7 M1 PoC 执行报告（数据回填——M2 输入冻结）

> 本节由 M1 批（`scripts/sandbox-poc/`）执行后回填；各小节数据表 + 结论行 + 复现脚本路径。占位骨架随冻结版 commit，数据行以第二次 commit 落入。

### 7-a sbt 真实负载 VirtioFS 实测（A6 数据）
（待回填：三臂计时表 / 结论行）

### 7-b docker stats --no-stream 采样 PoC（§2.4-D 依据）
（待回填：采样延迟分布 / CPU% 稳定性 / 结论行）

### 7-c per-task 容器生命周期演示（A4-A5 模型实证）
（待回填：create/start/retention/再唤醒/TTL 销毁计时 / fs 状态保持断言 / 结论行）

### 7-d 前端 QA 容器化全链 PoC
（待回填：实例启动/健康探活/截图验收/资产断言/清理计时 / 结论行）

### 7-e alpine/ubuntu 双工具链镜像对比（A5 终裁输入）
（待回填：构建时长 / 镜像大小 / musl 兼容验证 / 结论行）

### 7-f 资源记账（M1 批）
（待回填：容器/卷/镜像净增量 / docker system df 前后 / 红线自检记录）
