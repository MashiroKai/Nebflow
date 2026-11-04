# ops — Mac 部署资产（权威副本 · ops 入库纪律）

> 本目录是 Mac 侧部署脚本的**权威版本库**。live 副本（`~/.stand/bin/…`）只是部署物：
> 修脚本先修这里、commit，再部署出去；禁止只改 live 副本（否则下次 OS 重启 / 换机就丢，
> 2026-10-25 修复批病根之一）。

## 文件清单

| 权威副本（这里） | live 部署位 | 用途 |
|---|---|---|
| `ops/bin/stand` | `~/.stand/bin/stand` | stand CLI 入口（薄品牌壳）：只管辖 `~/.stand`（6138 缺省）实例 |
| `ops/bin/nebflow` | `~/.nebflow/bin/nebflow` | nebflow CLI 入口（薄品牌壳）：只管辖 `~/.nebflow`（6140 缺省，双生分家批 2026-10-31）实例（2026-10-27 隔离批） |
| `ops/bin/stand-cli-lib.sh` | `~/.stand/bin/` 与 `~/.nebflow/bin/` **各一份** | 两入口的**共享实现库**（隔离契约/守卫/横幅/`restart --detached` 全在此；单一代码路径） |
| `ops/bin/stand-jdk` | `~/.stand/bin/stand-jdk` | pinned JDK（`$STAND_HOME/jdk-21`）自愈 installer（装 / 修 / 体检） |
| `ops/stand-autostart.sh` | `~/.stand/bin/stand-autostart.sh` | 开机自启入口（v2：从 main 树 `sbt run` 起，带 6138 端口守卫；2026-11-03 双根分家批由 `nebflow-autostart.sh` git mv 改名——stand 的自启住 stand 家，旧名留弃用垫片转发） |
| `ops/bin/nebflow-autostart-shim` | `~/.stand/bin/nebflow-autostart.sh` | **旧名弃用垫片**（2026-11-03 双根分家批）：打印弃用提示后 `exec` 转发同目录 `stand-autostart.sh`，参数逐字透传。launchd 现挂 `com.nebflow.gateway` 作业的 ProgramArguments 仍指旧名路径，经垫片继续工作（改 plist 属 launchd 操作，须作者裁定） |
| `ops/stand-restart.sh` | `~/.stand/bin/stand-restart.sh` | 开发实例自重启工具 v4.4（**开发者/编排侧**；v4.4 主路径 `--hot` = 直接热重启：编译预检 + 隔离 canary → 立即重启，跳过四信号/观察窗——断点续跑兜底〔作者令 2026-11-03 17:59〕；`--canary`/`--watch`/`--wait-idle` 守候语义保留；`--force` 语义收敛=等同 `--hot`；2026-11-03 改名批由 `nebflow-restart.sh` git mv 而来，verbatim 入库，改动仍走它自己的版本纪律） |
| `ops/bin/nebflow-restart-shim` | `~/.stand/bin/nebflow-restart.sh` | **旧名弃用垫片**（2026-11-03 改名批）：打印 `nebflow-restart.sh is deprecated — use stand-restart.sh` 后 `exec` 转发同目录 `stand-restart.sh "$@"`，参数逐字透传（与 CLI 改名「老 nebflow 命令留垫片转发」同一手法） |
| `ops/release.sh` | —（不入 live：与仓绑定，从仓内跑） | **发版流水线（工程化 M2）**：`stage` → `approve` → `rollback` / `tag`，显式批准门（见下节） |
| `ops/bin/stand-jar-pull` | —（不入 live：与仓绑定，从仓内跑） | **KAI → Mac jar 流转（KAI 主线迁移批 2026-10-26）**：拉 KAI 出包位最新 jar → SHA 对账 → 原子落位 → 切指针 → start 指针解析验证（`--verify`/`--dry-run` 零触碰预演；用法一页：`ops/stand-jar-pull.md`） |
| `release/install.sh` `release/install.ps1` | release 出包路径 | release 安装器：`stand` 主命令 + `nebflow` legacy 转发垫片（与本目录 CLI 的隔离契约分工见 REBRAND「双命令并存」） |
| `ops/test/stand-lifecycle.test.sh` | —（不入 live） | 守卫的纯 bash 断言测试（沙箱化，零真实 daemon；覆盖 stand+nebflow 两品牌隔离、`restart --dry-run` 假点火、`restart --help` 三面口径、`stand-restart.sh`/`stand-autostart.sh` 弃用垫片的零副作用面） |
| `ops/bin/sbt-gate` | `~/.stand/bin/sbt`（**PATH 首位**，遮蔽 `/opt/homebrew/bin/sbt`） | **sbt 全局串行闸 + 默认堆帽**（内存即时缓解批 M1，2026-11-04）：flock 并发 1、默认 `-Xmx1500m`（显式 `-Xmx` 尊重用户值）、排队可见（waiting/acquired）、超时放行 + `SBT_GATE=off` 逃生口、`run/console` 服务形态旁路。日志 `~/.stand/logs/sbt-gate.log` |
| `ops/memsample.sh` | `~/.stand/bin/memsample.sh` | 60s 内存环形采样器（M4，2026-11-04）：宿主端口**从运行实例解析**（`.pid` → `lsof`；不再写死 8080），列追加 `hostPid,hostPort,compressedMB,totalMemMB`（压缩内存此前完全不在任何 RSS 里）。launchd 作业 `com.nebflow.memsample`（StartInterval=60） |
| `ops/test/sbt-server-bench.sh` | —（不入 live） | sbt 常驻 server 的 A/B 实测采集器（墙钟 + 峰值 RSS + 闸排队秒数；只测量不改变语义）。用法见「sbt 常驻 server」节 |
| `ops/test/sbt-gate.test.sh` | —（不入 live） | sbt 闸的沙箱化自测（假 sbt，零真实构建）：串行/排队可见/`SBT_GATE=off` 旁路/服务形态旁路/堆帽与 override/超时放行 |
| `ops/test/release-pipeline.test.sh` | —（不入 live） | release.sh 的纯 bash 断言测试（沙箱化：假构建/假冒烟/假 java + fixture git 仓） |

## 部署（Mac 侧）

```bash
REPO="/Users/kaiyu/Claude code/Stand"        # 或任意 stand 仓 checkout
# stand 根: 入口 + 库 + JDK 体检（PATH 垫片已退役——2026-11-03 作者令，见下节）
cp "$REPO/ops/bin/stand"       ~/.stand/bin/stand       && chmod +x ~/.stand/bin/stand
cp "$REPO/ops/bin/stand-cli-lib.sh" ~/.stand/bin/stand-cli-lib.sh
cp "$REPO/ops/bin/stand-jdk"   ~/.stand/bin/stand-jdk   && chmod +x ~/.stand/bin/stand-jdk
cp "$REPO/ops/stand-autostart.sh" ~/.stand/bin/stand-autostart.sh && chmod +x ~/.stand/bin/stand-autostart.sh
cp "$REPO/ops/bin/nebflow-autostart-shim" ~/.stand/bin/nebflow-autostart.sh && chmod +x ~/.stand/bin/nebflow-autostart.sh
# 开发实例自重启工具（2026-11-03 改名批）+ 旧名弃用垫片
cp "$REPO/ops/stand-restart.sh"  ~/.stand/bin/stand-restart.sh  && chmod +x ~/.stand/bin/stand-restart.sh
cp "$REPO/ops/bin/nebflow-restart-shim" ~/.stand/bin/nebflow-restart.sh && chmod +x ~/.stand/bin/nebflow-restart.sh
# 开发重启工具的两个 helper（2026-11-03 双根分家批由 nebflow-* 改名；live 专属件，
# 仓内无权威副本——与 stand-restart.sh 同目录部署）
#   ~/.stand/bin/stand-setsid-exec.py / ~/.stand/bin/stand-daemonize.py（chmod +x）
# nebflow 根 (9.19 自有根): 真入口 + 库
cp "$REPO/ops/bin/nebflow"     ~/.nebflow/bin/nebflow   && chmod +x ~/.nebflow/bin/nebflow
cp "$REPO/ops/bin/stand-cli-lib.sh" ~/.nebflow/bin/stand-cli-lib.sh
# 内存即时缓解批（2026-11-04）: sbt 全局闸垫片 + 内存采样器（权威副本 = 仓内）
cp "$REPO/ops/bin/sbt-gate"    ~/.stand/bin/sbt          && chmod +x ~/.stand/bin/sbt   # PATH 首位，遮蔽 homebrew sbt
cp "$REPO/ops/memsample.sh"    ~/.stand/bin/memsample.sh && chmod +x ~/.stand/bin/memsample.sh
bash "$REPO/ops/test/sbt-gate.test.sh"          # 闸自测：全绿再依赖
bash ~/.stand/bin/stand-jdk check        # pinned JDK 体检
bash "$REPO/ops/test/stand-lifecycle.test.sh"   # 回归：全绿再部署
```

**品牌隔离契约（2026-10-27 作者令）**：`stand` 只动 `~/.stand`（端口 `--port > STAND_GATEWAY_PORT > 6138`），
`nebflow` 只动 `~/.nebflow`（`--port > NEBFLOW_GATEWAY_PORT > 6140`；分家批后引擎侧解析链同为 --port > env > config "port" > 品牌缺省）——互不拉起、互不探活、互不误杀；
home/端口/JDK 覆写 env 各只认自家变量，stop 双探针（自家端口 ∪ 自家 `.pid`）+ 身份门按品牌收窄。
**PATH 与双根（2026-11-03 作者令，替代旧「不编辑用户 shell rc」口径）**：`~/.zshrc` 同时收两个根——
`~/.stand/bin`（stand 命令）与 `~/.nebflow/bin`（nebflow 命令）；两目录互不放对方文件。
原 PATH 垫片 `~/.stand/bin/nebflow`（转投 `~/.nebflow/bin/nebflow`）同批退役删除，
删除时 sha256 = `6df461b3c714b94d710822dbd6beddd90804e35d24fcbe234a4a8815bbc79e2d`（留档，文件已不存在）。

> **2026-11-03 作者令（双根分家）**：nebflow 命令住自己的家（`~/.nebflow/bin` 进 PATH），
> stand 家里不再放 nebflow 任何文件。

改 live 前先备份（沿用该目录既有 `.bak-YYYYMMDD` 惯例）：

```bash
cp ~/.stand/bin/stand ~/.stand/bin/stand.bak-$(date +%Y%m%d-%H%M%S)
```

## sbt 常驻 server（`sbt --client`）—— 条件性建议 · **默认不启用**（2026-11-04 复核批）

> **结论：本机（16GB / swap 已用 61%）实测净负，默认维持现状（每次冷启）。本节是条件性建议，
> 不是推荐配置**。什么时候再考虑启用，见本节末「重启判据」。

### 机制（先钉三条会被踩的物理事实）

1. **server 按「构建根」注册与寻址**：每个构建根自己一份 `<build>/project/target/active.json`
   指向 `~/.sbt/1.0/server/<hash>/sock`。**两个 worktree = 两个 server**，不是「6 个 worktree
   共享一个编译进程」。能共享的只有「同一 worktree 内的多次调用」。
2. **常驻 server 必须用「真 sbt」启动，不能经闸垫片** `~/.stand/bin/sbt`：闸会把重活锁
   （`~/.stand/run/sbt.lock`）交给它 exec 的真 sbt 子进程，而 server 常驻不退 ⇒ **锁被永久占用**，
   全机器下一次编译就卡在 waiting（本批复核时实测踩到，闸日志见 `waiting for …sbt.lock`）。
   正确起法是绕开垫片直接用 `/opt/homebrew/bin/sbt`（或 `SBT_GATE=off`）。
3. `sbt --client` 需要 sbtn 原生客户端（`~/.cache/sbt/boot/sbtn/<版本>/sbtn`），首次使用会联网
   下载；离线或代理不可用时 `--client` 整条路不可用，只能冷启。

### 手工启用（不需要任何新代码）

```bash
WT=/path/to/worktree
# 起常驻 server（绕开闸垫片；堆帽沿用闸口径 1500m）
( cd "$WT" && nohup bash -c "exec sleep infinity | exec env SBT_OPTS='-Xmx1500m' /opt/homebrew/bin/sbt" \
      >/tmp/sbt-server.log 2>&1 & )
# 之后同一个 worktree 内的调用走热 server（~0.6s 往返，而不是每次约 5s 冷启）
( cd "$WT" && /opt/homebrew/bin/sbt --client compile )
# 关掉（内存立刻回吐；不关就一直常驻）
( cd "$WT" && /opt/homebrew/bin/sbt --client shutdown )
```

闸的并发=1 语义不受影响的前提是：**走 server 的调用必须仍受闸串行**。手工上面这套是**绕过闸**的
（`--client` 不经垫片），多会话并发时会出现「server 在编译 + 另一个会话的闸调用也在编译」，
即闸的并发=1 失效——这也是本批**没有把它接进闸**的原因之一（接线版需要闸翻译命令 + 超时旁路，
设计见知识文档，未落地）。

### 实测读数（2026-11-04 14:2x，本机 16GB / 12 核 / JDK23 / sbt 1.10.10）

| 场景 | 现状（每次冷启） | 常驻 server | 备注 |
|---|---|---|---|
| 同 worktree 重复调用（目标已热） | **6.7 / 4.9 / 5.1 s**（mean 5.6s） | **1.6 s（首连）/ 0.6 / 0.6 s** | 收益 ≈ **4.5–5 s/次**，即这类短调用快 ~8 倍 |
| 新 worktree 首次调用（全量编译 741 源） | 98.7 / 84.0 / 88.5 s | server 帮不上（编译时间一分不省）；聚合形态下首次触达反而 **+3~4 s** | 收益在这一类为 **0** |
| 常驻内存（每构建根一个 server） | 调用结束即退出，常驻 **0** | 空闲 **369 MB** → 编译后 **617–636 MB** | 6 个活跃 worktree ⇒ **2.2–3.8 GB 常驻** |
| server 冷启动（单构建根） | — | **4.0 s**（boot → ready） | 聚合多构建根形态：**3 个构建根 116.6 s** |
| 聚合形态（一个 JVM 承载多构建根，`RootProject`） | — | 3 构建根空闲 **587 MB**、每构建根首触达 **8.4–9.5 s**、重复 0.6–0.9 s | 见下节，**不建议** |

按闸日志真实流量折算（本机今日 n=68 次调用、median 11 s、≤15 s 短调用占 59%、总墙钟 3 429 s）：
全量命中热 server 约省 **240 s ≈ 7%** 的 sbt 墙钟，换成「每次调用省 4.5 s × 短调用次数」也是同一量级。
**7% 的墙钟换 2.2–3.8 GB 常驻**，在本机（swap 2519/4096 MB = 61%、压缩内存约 4.2 GB、load1 12）是净负——
这正是 M1 闸当初要压的同一类内存压力。

### 跨 worktree 共享（聚合形态）：技术可行，但**不建议**

`RootProject(file("<worktree>"))` 聚合可以把多个构建根装进**同一个 JVM**，各自加载自己的
`project/` 定义、各自写自己的 `target/`（实测见知识文档 §4），因此「物理上不可能共享」要修正为
「sbt server 原生不支持跨根，但可用聚合 build 绕过」。不采用的理由是实测出来的：

- **冷启 116.6 s**（3 个构建根），每多一个活跃 worktree 都要付这份加载成本；
- 每构建根**首次触达 8.4–9.5 s**，比冷启（5–6.7 s）更慢；
- 对 build 定义有硬要求：`build.sbt` 里任何 **CWD 相对路径**（本仓 `IO.read(file("VERSION"))`）
  在聚合根下会 `FileNotFoundException`，必须在聚合根摆副本（实测踩到并绕过）；
- 注册表一变就要 `reload` 或重建 server，新 worktree 频繁出现的场景下抖动明显。

### 复测方法（数字变了先复测再决定）

```bash
# 同一 worktree 重复调用 A/B：3 次冷启 + 3 次冷启（对照组），再看 server 组
bash ops/test/sbt-server-bench.sh baseline-3wt -- /tmp/wt1 "compile" -- /tmp/wt2 "compile"
# 输出 /tmp/sbt-server-bench/<label>.log（原始输出）与 .summary.tsv（wall/sbtRssMB/allRssMB/gateWait/rc）
```

### 重启判据（满足任一条再谈启用）

- 机器内存 ≥ 32 GB，或 `sysctl vm.swapusage` 的 used 长期 < 20%；
- 或 sbt 调用量级再上一个数量级（例如常驻 > 200 次/小时），使 4.5 s/次 × N 的绝对收益压过常驻成本；
- 或将来 sbt 2.x 提供原生多构建根 server（届时「每 worktree 一个 400–600 MB」这条成本项消失）。

## 2026-10-25 修复批（双病）

**病1 · 双启动无守卫**（病历：`stand start` ×2 → 双 daemon 86244/86479，第二个
strictBoot 崩溃 `SessionStore.scala:376 → Main.scala:237`；随后 `stand stop` 报
not running —— pid 记账失配）：

- `start`：探端口（`lsof -ti tcp:$PORT -sTCP:LISTEN`，与 autostart v2 同源守卫）。
  已有 stand listener → `stand is already running (pid X)` **exit 0**，不进 JVM。
  端口被外姓进程占 → 拒启 exit 1（镜像应用侧 `ForeignOccupant`）。
- 端口一致性：守卫把探的端口钉进子进程 env（`STAND_GATEWAY_PORT` +
  `NEBFLOW_GATEWAY_PORT`）。引擎默认口自端口对调落地批（2026-10-25）起 = 6138
  （`Defaults.GatewayPort` 单一来源），env 注入保留为双保险——裸 jar、旧版本
  jar 与 ops 脚本间任何一环不对齐时守卫仍成立。
  优先级与 `GatewayConfig`/`paths.scala` 对齐：`--port` > `STAND_GATEWAY_PORT` >
  `NEBFLOW_GATEWAY_PORT` > 6138。
- `stop`：**双探针**（lsof listener + `$STAND_HOME/.pid` 文件并集）→ 身份门
  （cmdline 须含 `stand-assembly` / `nebflow` / `sbt`，`--force` 跳过）→
  TERM → ≤10s 宽限 → KILL → 清陈年 pid 记账。双空（真没在跑）→
  `stand is not running` exit 0（幂等，与应用侧 `StopOutcome.NotRunning` 同语义）。
  `--tree`（sbt 链止损）仍透传给应用侧 `ProcessManager`。
- 非生命周期子命令（version/status/…）零改动直透。
- **守卫面在 JDK 门之前**（2026-10-25 横幅批顺带强化）：already-running / stop /
  not-running 面零 JVM 依赖——java 全坏也能 `stop` 真停、也能识别已启动，
  这些面也不再先吃 JDK WARN 噪声。

**品牌横幅**（作者追加 2026-10-25 13:10:「One entry, Every agent.」）：

- 仅 `stand start` 且守卫放行（真的要起 daemon）时打印；already-running /
  拒启 / stop 不印。URL/pid 行紧随其后（应用侧输出），保持终端最后一行 = 最显眼。
- 候选三款（待作者定稿，实截见
  `reports/20261025_132822_stand-cli-lifecycle/shots/`）：
  - `STAND_BANNER=A` 徽标款——黑底白 S 方块（favicon 同构）+ 标语（宽终端 ≥80 列缺省）
  - `STAND_BANNER=B` 双行紧凑款（窄终端 <80 列缺省）
  - `STAND_BANNER=C` 单行字标款
- `STAND_BANNER_COLS` 覆写宽度探测（测试/ops 旋钮）。
- 分寸：非 TTY / `NO_COLOR` / `TERM=dumb` → 单行纯文本
  `stand — One entry, Every agent.`，autostart / 重定向日志不刷艺术字。

**病2 · pinned JDK 缺失路径**（合同：作者令 2026-09-21「缺了不得静默降级」）：

- launcher 缺 `$STAND_HOME/jdk-21/bin/java` 时：**WARN 不静默**，回落 PATH java
  （仍受 Java≥21 版本门约束，exit 11 拒旧 JVM），但修复指引改为可执行动作：
  `bash ~/.stand/bin/stand-jdk install`（或仓内 `ops/bin/stand-jdk install`）。
- `stand-jdk install` 路线（无 sudo，不动系统 JVM）：brew `openjdk@21` →
  symlink `$STAND_HOME/jdk-21` → keg home；Linux 走包管理器；兜底 Adoptium
  Temurin 21 tarball 直落 `$STAND_HOME`。
- `STAND_JAVA_BIN` env：显式钉死 java（测试沙箱 / ops 逃生门），设置则跳过 pinned 探测。
- 路径全部 `STAND_HOME` 感知（`STAND_HOME` > `NEBFLOW_HOME` > `~/.stand`，
  与 `shared/paths.scala` dataRoot 同优先级）——数据根迁移不破 launcher。

## 发版流水线（工程化 M2 · `ops/release.sh`）

一条命令流水线 + 显式批准门（发布纪律红线：staged 自动化、**晋级必须作者逐次显式批准**）。
对齐 `scripts/boot-smoke.sh`（M1）契约：stage/approve 都吃它的 exit code 与
`BOOT-SMOKE: PASS/FAIL` 结论行作闸。

```bash
bash ops/release.sh stage     # 预检(main 干净/origin 齐平——不阻塞但列漂移) → sbt assembly
                              # → boot-smoke(FAIL 即中止, 零候选) → 装 versions/<v>/ + staged-release.json
bash ops/release.sh approve   # 作者逐次批准唯一入口: 对 staged jar 再跑一次 boot-smoke 探针
                              # (FAIL = 拒晋级, 指针不动) → 原子切 current-version/previous-version → 打印回退指令
bash ops/release.sh rollback  # 指针回退上一版(staged-release.json 记 previous), 幂等
bash ops/release.sh tag       # 只在 approve 之后; tag 名 == VERSION@approved commit == jar 内 runtime version
                              # 三方对账, 不匹配即拒(10-24 死 tag 教训的机械卡点); 永不 push
bash ops/release.sh status    # 只读: 指针 / previous / staged 状态
```

- 发布根 = `~/.stand/bin`（`--root`/`STAND_RELEASE_ROOT` 可沙箱化），即
  `ops/bin/stand` 的 POINTER-SELECT 读的同一布局：`current-version` +
  `previous-version` + `versions/<v>/stand-assembly-<v>.jar` + `staged-release.json`。
- **换手只写文件**：approve/rollback 零信号——重启交给 `bin/stand`
  （already-running 守卫），本脚本绝不触碰在产进程 / 6138。
- 版本包不可变：`versions/<v>` 已存在即拒（要发请 bump VERSION）。
- 可测接缝（env）：`STAND_RELEASE_ROOT` / `STAND_RELEASE_REPO` /
  `STAND_RELEASE_BUILD_CMD` / `STAND_RELEASE_SMOKE_CMD` / `STAND_RELEASE_JAVA_BIN`。
- 完整证据链见 `reports/20261025_142200_release-pipeline-m2__delegate-nebflow-187d2ff9/REPORT.md`。

## KAI 主线构建约定（2026-10-26 正式切换 · 作者令 00:55「把STAND的开发转移到Kai上去」）

- **切换事实**：引擎构建主线自本批起 = **KAI**（`C:\Users\Kai\stand`，ssh alias `kai`，git-bash）；
  Mac 本地 sbt 构建**退役**，仅作 KAI 不可用（Tailscale 断 / 机器关）时的紧急兜底。
  迁移基线 `38b81eb4f`（2026-10-26 凌晨，含回执去重修复批）。
- **首替事实与更正**（2026-10-26 01:03→01:07）：delegate 首次真替换误用 release 版名
  `2026.10.21-beta.2` 落位，把 Mac 侧原发布包挤进 `.bak`；revival 后当批更正——
  原发布包字节级还原（SHA256 `502fcf04…`，runtime 探针复核）→ dev 版名重建重拉。
  现行指针：`current-version = 2026.10.26-dev.38b81eb4f`（KAI @`38b81eb4f` 出包，
  SHA256 `fa94a929…`，jar 内 runtime == 指针名一致），`previous-version = 2026.10.21-beta.2`。
  详见 `reports/20261026_010800_kai-migration-phase2/REPORT.md` §3。
- **版本名纪律**（首替事故后写死）：KAI 日常出包**构建时覆写**仓 VERSION 为 dev 版名
  `YYYY.MM.DD-dev.<main HEAD 短sha>`（sbt 启动时读 VERSION，jar 文件名与 jar 内 runtime
  version 一并生效；**打包完立即还原**，tree 保持干净）。dev 构建**不得挂 release 版名**——
  release 名包在 Mac 侧不可变（`ops/release.sh` 口径），错挂会被 `stand-jar-pull`
  落进 release 版本目录、把原发布包挤去 `.bak`（即上面首替事故）。
- **日常流（引擎构建三步，顺序固定）**：
  1. **bundle 同步**（Mac → KAI）：Mac 读当前 main HEAD →
     `git bundle create /tmp/kai-sync.bundle <KAI当前HEAD>..main`（薄包）→
     `scp` 到 KAI（如 `kai:C:/Users/Kai/kai-phase2/`）→ KAI 侧
     `git fetch <bundle> main && git merge --ff-only FETCH_HEAD`，完事 `git status` 核干净。
     （SSH 直 push 在 Windows 非交互 PATH 下不可用，bundle 通道是唯一约定通道——
     见 `reports/20261026_002700_kai-migration-phase1/REPORT.md` §2。）
  2. **KAI 出包**：KAI 仓跑 `sbt -batch compile [快验面 testOnly] assembly`
     （前台 + 心跳落盘纪律；testOnly 红会 fail-fast 短路 assembly，assembly 单独补跑——两段各有日志落
     `/c/Users/Kai/kai-phase2/` 惯例位）。固定出包位 =
     `C:/Users/Kai/stand/target/scala-3.5.2/stand-assembly-<VERSION>.jar`（版名=仓 VERSION 文件）。
  3. **Mac 拉取替换**：仓内 `ops/bin/stand-jar-pull`（certutil↔shasum SHA256 双端对账 →
     `.part` 原子落位 → 旧包 `.bak-时间戳` 备份 → `current-version` 指针原子切换 →
     `stand start` 指针解析只读复演）。新包**下次 stop/start 生效**；
     脚本绝不触碰在产 daemon / 6138。用法一页：`ops/stand-jar-pull.md`。
- **测试电池约定**：白天全量电池在 **KAI** 跑（批名 `test7`，对照 2026-10-26 凌晨基线：
  phase-1 18:24 终验账 Total 1365/Failed 157 @`4c866d61d` + phase-2 快验族读数 @`38b81eb4f`，
  见 `reports/20261026_*_kai-migration-phase2/REPORT.md`）；Mac 侧不再跑全量电池。
  快验固定面：`ImageGenSpec` + agent 三锚（`RootSixBaseToolsSpec`/`FriendsSealSpec`/`Phase2dToolRefactorSpec`）
  + `PerceptionCoreSpec`（末者 KAI 上有 4 稳定红，Windows 环境差定因候选见 phase-2 报告，非合并回归判读）。
- **Mac 本地构建（紧急兜底）**：仅 KAI 不可用时走 `ops/release.sh stage` 老路；恢复后回到三步主线流。

## 内建分离式重启/两阶段更新（台账 #916 · 2026-10-26）

引擎内建（`nebflow.cli.DetachedApply`），设计件：`~/.stand/projects/stand/.stand/docs/design/20261026_detached-apply-restart-update-spec.zh-CN.md`（文档库落位 = 项目注册目录 `.stand/docs/`，不在仓库 `docs/`，见「文档落位」条）。

**`stand restart` 三条腿（作者裁定 2026-11-03 07:45：restart 的 CLI 从用户角度设计，用户不用编译版 stand）**

| 腿 | 命令 | 适用场景 | 依赖 |
|---|---|---|---|
| 用户面（默认） | `stand restart`（= `--jar-apply`） | 装了官网发布版的终端用户：热重启当前已装版本 | 引擎活着才能注册 OS 作业（需可用 java） |
| 自救面 | `stand restart --detached` / `--dry-run` | 引擎挂死、jar 面注册不了时 | **零 JVM 依赖**，只要 shell 能跑 |
| 开发面 | `~/.stand/bin/stand-restart.sh`（**不进 bare 路径、CLI 不暴露开发旗标**） | 本机有源码树、在跑开发实例 | sbt + canary 端口，属开发者/编排侧工具 |

`stand restart --help` 即按此三段渲染（用户面刻意不出现 sbt/编译/canary 字样；用户面文字：
`ops/bin/stand-cli-lib.sh` 的 `stand_restart_help`）。开发面工具 2026-11-03 由
`nebflow-restart.sh` 改名 `stand-restart.sh`，旧名保留为弃用垫片转发（见部署表）。

- **`stand restart`（默认 detached）**：CLI 只向 OS 注册一次性 apply 作业
  （macOS = launchd agent bootstrap+kickstart；Windows = schtasks 固定名任务
  `/Create /F` + `/Run`）后立即返回——stop→start→健康闸由 **OS 托管的作业**执行，
  任何调用形态（ssh/console/引擎树内）注册后即可死，绝不落在垂死进程树里
  （2026-10-26 12:06 Mac 事故的直接修法）。`--inline` = 旧同步面。
  日志：`<dataRoot>/logs/apply-restart.log`；状态相：`<dataRoot>/apply/state.json`。
- **`stand update --from <jar>`（两阶段）**：Phase1 stage+verify（sha256、`.part`
  原子落位、版本目录不可变：同版同 sha 幂等命中、异 sha 拒绝；不碰指针不碰引擎）
  → Phase2 指针晋升 + 注册 detached apply（健康闸含版本断言，失败自动回滚旧版）。
  `--stage-only` = 只做 Phase1。裸 `stand update`/`--beta`/`--device` 行为不变。
- **选取面**：launcher 目录含 `current-version` = 指针布局（Mac 生产）；否则
  winsort 扁平（KAI `AppData\Local\stand` 现状，最大版本胜）。中断安全锚：
  指针原子写 / 包只经同卷 ATOMIC_MOVE 进选取面——任何时刻断电，重启后非旧版
  可用即新版可用。
- **引擎 boot 对账**：发现上一 apply 死在中途 → WARN + state 记 `interrupted`，
  只留证据不自作主张。

**用户面语义（作者裁定 2026-11-03 07:45）**：`stand restart` 只重跑当前已安装的版本，
不涉及编译与 staging——用户装的官网发布版没有源码树，sbt 编译预检对他们不适用。
失败语义按引擎实况（`DetachedApply` 渲染脚本 `do_apply`，`src/main/scala/nebflow/cli/DetachedApply.scala:512-540`）：
restart 失败 = `phase=failed` + 写因留证（stop 腿失败 rc=3 / start 健康闸失败 rc=4），
**服务保持停止**；**只有 `stand update` 才有回滚腿**（切回旧指针并起回旧版）。
`--jar-apply` = 同一用户面的显式具名（与 bare 同路径，落 `RestartCommand.runDetached`），
供脚本化调用自证意图；与 `--detached`/`--dry-run` 互斥（rc=2）。

### CLI 零 JVM detached 面（`restart --detached` / `--dry-run`，2026-10-27 批）

与上节 jar 面**互补不冲突**（设计件：`~/.stand/projects/stand/.stand/docs/design/20261027_cli-isolation-and-detached-restart-spec.zh-CN.md`）：

- `stand restart --detached`：CLI 层双 fork 孤儿化点火器（过继 launchd，ppid=1）
  + 点火前自检 ppid=1（不达标 ABORT rc=3，引擎分毫未动）+ stop 腿（launcher
  硬化 stop 同语义）→ 端口空闲闸 → `exec java -jar … start`。全程日志
  `<dataRoot>/logs/restart-detached-*.log`。**零 JVM 依赖**：JDK 坏/缺也能点火
  （jar 面注册作业需要 java 可用）。防双点火：`<dataRoot>/.restart-detached.pid`
  （活点火器在飞 ⇒ 拒绝；陈年记账 ⇒ WARN 覆写）。
- `stand restart --dry-run`（隐含 detached）：**假点火** —— 只写「自身 ppid +
  引擎树可达探针（双探针 pids、signal 权限、点火器祖先链、引擎是否在链内）」
  进日志，不停不启。验证判据：日志见 `ppid=1 OK` 且 `engine pid in igniter
  ancestor chain: NO` ⇒ 树杀不可达，机制成立。
- bare `stand restart`（含 `run --inline --force/--tree`）**逐字透传 jar 面**，
  #916 语义零改动。`nebflow restart --detached/--dry-run` 同代码路径
  （9.19 jar 无 restart 命令，bare 报错 rc=2 并指路 detached 面）。
- **三腿关系（2026-11-03 裁定批）**：jar 面（用户面）要求引擎活着才能注册 OS 作业；
  `--detached`/`--dry-run` 是引擎挂死时**用户唯一的自救腿**（零 JVM），因此帮助文本里
  排在用户面之后、开发面之前。`stand-restart.sh`（原 `nebflow-restart.sh`）是**第四个面**：
  开发护栏工具（编译预检 + canary + 四信号 + 观察窗），CLI **刻意不接进 bare 路径**，
  只在帮助的「开发面」段与本节指路。

## 测试

```bash
bash ops/test/stand-lifecycle.test.sh
bash ops/test/release-pipeline.test.sh        # 89 断言全绿; RELEASE_TEST_REAL_SMOKE=1 追加真冒烟用例
```

纯 bash 断言（无 bats 依赖），沙箱化：假 java（`STAND_JAVA_BIN`）+ 假 jar +
随机空闲端口 + C 编译的标记监听器（macOS ps 只认真实可执行路径，`exec -a` 假
argv[0] 对 .app 二进制无效——测试基建里踩过的坑）。覆盖：start 命中/外姓拒启/
未命中放行（子命令与端口 env 钉一致）、stop 双探针命中/失配态（pid 腿）/双空幂等/
身份门拒杀/`--force`/`--tree` 透传、Java 版本门 rc=11、passthrough。

**改名批 2026-11-03**（作者令 2026-10-29「这个脚本是不是也改改名为stand了」+ 裁定 2026-11-03 07:45
「restart 的 CLI 从用户角度设计」）新增断言：弃用垫片 `nebflow-restart.sh` 打印弃用提示并逐字
转发（含未知旗标透传、正文缺失时 rc=66 指路）；`stand restart --dry-run` 与
`stand-restart.sh --check` **双零终止**实证（活监听器前后仍在、零 java exec、不留 watchdog
暂停标志）；`stand restart --help` 三面齐全且**用户面段落不含 sbt/canary 字样**；
`--jar-apply` 透传与 `--detached/--dry-run` 互斥。

**双实例并存批 2026-10-30**（作者令「和 nebflow 隔离，同时可用」）在此基础上新增：
默认值解析断言（`stand`→`~/.stand`+6138、`nebflow`→`~/.nebflow`+6140，经隐藏探针
`__brand-defaults`，零 JVM）；start 撞**对家品牌实例**占口时 rc=1、报错点名「另一实例」
（含其默认家/口与共存提示）。引擎侧对账 spec 见 `src/test/scala/nebflow/cli/CliBrandDefaultsSpec.scala` 等。

## 版本纪律

1. 改动只发生在本目录（commit 进 stand 仓）。
2. 部署 = `cp`（先 `.bak-时间戳` 备份 live）。
3. `ops/stand-restart.sh` 为 verbatim 入库（2026-11-03 由 `ops/nebflow-restart.sh` git mv 改名，
   `git log --follow` 保完整历史）：它的真正开发流仍在 live 侧按其自身头注释的版本纪律走；
   入库目的是换机/灾备有源可寻。旧名 `nebflow-restart.sh` 的自造副本在 live 侧已被
   `ops/bin/nebflow-restart-shim` 的弃用垫片取代（打印一行提示后 exec 转发），
   老调用点/老脚本/老文档里的旧名一律继续可用。
