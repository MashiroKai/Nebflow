# ops — Mac 部署资产（权威副本 · ops 入库纪律）

> 本目录是 Mac 侧部署脚本的**权威版本库**。live 副本（`~/.stand/bin/…`）只是部署物：
> 修脚本先修这里、commit，再部署出去；禁止只改 live 副本（否则下次 OS 重启 / 换机就丢，
> 2026-10-25 修复批病根之一）。

## 文件清单

| 权威副本（这里） | live 部署位 | 用途 |
|---|---|---|
| `ops/bin/stand` | `~/.stand/bin/stand` | stand CLI 入口（薄品牌壳）：只管辖 `~/.stand`（6138 缺省）实例 |
| `ops/bin/nebflow` | `~/.nebflow/bin/nebflow` | nebflow CLI 入口（薄品牌壳）：只管辖 `~/.nebflow`（8080 缺省）实例（2026-10-27 隔离批） |
| `ops/bin/stand-cli-lib.sh` | `~/.stand/bin/` 与 `~/.nebflow/bin/` **各一份** | 两入口的**共享实现库**（隔离契约/守卫/横幅/`restart --detached` 全在此；单一代码路径） |
| `ops/bin/nebflow-stand-shim` | `~/.stand/bin/nebflow` | PATH 垫片：`nebflow` → `~/.nebflow/bin/nebflow` 真入口（`~/.zshrc` 只把 `~/.stand/bin` 加进 PATH；2026-10-22 旧垫片是「转发 stand」，即跨品牌拉起的总根，本垫片取而代之） |
| `ops/bin/stand-jdk` | `~/.stand/bin/stand-jdk` | pinned JDK（`$STAND_HOME/jdk-21`）自愈 installer（装 / 修 / 体检） |
| `ops/nebflow-autostart.sh` | `~/.stand/bin/nebflow-autostart.sh` | 开机自启入口（v2：从 main 树 `sbt run` 起，带 6138 端口守卫） |
| `ops/nebflow-restart.sh` | `~/.stand/bin/nebflow-restart.sh` | 开发实例自重启工具 v4.2（verbatim 入库，改动仍走它自己的版本纪律） |
| `ops/release.sh` | —（不入 live：与仓绑定，从仓内跑） | **发版流水线（工程化 M2）**：`stage` → `approve` → `rollback` / `tag`，显式批准门（见下节） |
| `ops/bin/stand-jar-pull` | —（不入 live：与仓绑定，从仓内跑） | **KAI → Mac jar 流转（KAI 主线迁移批 2026-10-26）**：拉 KAI 出包位最新 jar → SHA 对账 → 原子落位 → 切指针 → start 指针解析验证（`--verify`/`--dry-run` 零触碰预演；用法一页：`ops/stand-jar-pull.md`） |
| `ops/test/stand-lifecycle.test.sh` | —（不入 live） | 守卫的纯 bash 断言测试（沙箱化，零真实 daemon；覆盖 stand+nebflow 两品牌隔离与 `restart --dry-run` 假点火） |
| `ops/test/release-pipeline.test.sh` | —（不入 live） | release.sh 的纯 bash 断言测试（沙箱化：假构建/假冒烟/假 java + fixture git 仓） |

## 部署（Mac 侧）

```bash
REPO="/Users/kaiyu/Claude code/Stand"        # 或任意 stand 仓 checkout
# stand 根: 入口 + 库 + 垫片 + JDK 体检
cp "$REPO/ops/bin/stand"       ~/.stand/bin/stand       && chmod +x ~/.stand/bin/stand
cp "$REPO/ops/bin/stand-cli-lib.sh" ~/.stand/bin/stand-cli-lib.sh
cp "$REPO/ops/bin/nebflow-stand-shim" ~/.stand/bin/nebflow && chmod +x ~/.stand/bin/nebflow
cp "$REPO/ops/bin/stand-jdk"   ~/.stand/bin/stand-jdk   && chmod +x ~/.stand/bin/stand-jdk
cp "$REPO/ops/nebflow-autostart.sh" ~/.stand/bin/nebflow-autostart.sh && chmod +x ~/.stand/bin/nebflow-autostart.sh
# nebflow 根 (9.19 自有根): 真入口 + 库
cp "$REPO/ops/bin/nebflow"     ~/.nebflow/bin/nebflow   && chmod +x ~/.nebflow/bin/nebflow
cp "$REPO/ops/bin/stand-cli-lib.sh" ~/.nebflow/bin/stand-cli-lib.sh
bash ~/.stand/bin/stand-jdk check        # pinned JDK 体检
bash "$REPO/ops/test/stand-lifecycle.test.sh"   # 回归：全绿再部署
```

**品牌隔离契约（2026-10-27 作者令）**：`stand` 只动 `~/.stand`（端口 `--port > STAND_GATEWAY_PORT > 6138`），
`nebflow` 只动 `~/.nebflow`（`--port > NEBFLOW_GATEWAY_PORT > 8080`）——互不拉起、互不探活、互不误杀；
home/端口/JDK 覆写 env 各只认自家变量，stop 双探针（自家端口 ∪ 自家 `.pid`）+ 身份门按品牌收窄。
**不编辑用户 shell rc**：`~/.zshrc:18` 的 PATH 原样保留，隔离由垫片换向达成。

改 live 前先备份（沿用该目录既有 `.bak-YYYYMMDD` 惯例）：

```bash
cp ~/.stand/bin/stand ~/.stand/bin/stand.bak-$(date +%Y%m%d-%H%M%S)
```

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

引擎内建（`nebflow.cli.DetachedApply`），设计件：`docs/design/20261026_detached-apply-restart-update-spec.zh-CN.md`。

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

### CLI 零 JVM detached 面（`restart --detached` / `--dry-run`，2026-10-27 批）

与上节 jar 面**互补不冲突**（设计件：`docs/design/20261027_cli-isolation-and-detached-restart-spec.zh-CN.md`）：

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

## 版本纪律

1. 改动只发生在本目录（commit 进 stand 仓）。
2. 部署 = `cp`（先 `.bak-时间戳` 备份 live）。
3. `ops/nebflow-restart.sh` 为 verbatim 入库：它的真正开发流仍在 live 侧按其自身
   头注释的版本纪律走；入库目的是换机/灾备有源可寻。
