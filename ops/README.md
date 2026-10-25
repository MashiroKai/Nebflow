# ops — Mac 部署资产（权威副本 · ops 入库纪律）

> 本目录是 Mac 侧部署脚本的**权威版本库**。live 副本（`~/.stand/bin/…`）只是部署物：
> 修脚本先修这里、commit，再部署出去；禁止只改 live 副本（否则下次 OS 重启 / 换机就丢，
> 2026-10-25 修复批病根之一）。

## 文件清单

| 权威副本（这里） | live 部署位 | 用途 |
|---|---|---|
| `ops/bin/stand` | `~/.stand/bin/stand` | stand CLI launcher：JDK 选择 + jar 选择 + **start/stop 生命周期守卫**（2026-10-25 修复批） |
| `ops/bin/stand-jdk` | `~/.stand/bin/stand-jdk` | pinned JDK（`$STAND_HOME/jdk-21`）自愈 installer（装 / 修 / 体检） |
| `ops/nebflow-autostart.sh` | `~/.stand/bin/nebflow-autostart.sh` | 开机自启入口（v2：从 main 树 `sbt run` 起，带 6138 端口守卫） |
| `ops/nebflow-restart.sh` | `~/.stand/bin/nebflow-restart.sh` | 开发实例自重启工具 v4.2（verbatim 入库，改动仍走它自己的版本纪律） |
| `ops/test/stand-lifecycle.test.sh` | —（不入 live） | 守卫的纯 bash 断言测试（沙箱化，零真实 daemon） |

## 部署（Mac 侧）

```bash
REPO="/Users/kaiyu/Claude code/Stand"        # 或任意 stand 仓 checkout
cp "$REPO/ops/bin/stand"       ~/.stand/bin/stand       && chmod +x ~/.stand/bin/stand
cp "$REPO/ops/bin/stand-jdk"   ~/.stand/bin/stand-jdk   && chmod +x ~/.stand/bin/stand-jdk
cp "$REPO/ops/nebflow-autostart.sh" ~/.stand/bin/nebflow-autostart.sh && chmod +x ~/.stand/bin/nebflow-autostart.sh
bash ~/.stand/bin/stand-jdk check        # pinned JDK 体检
bash "$REPO/ops/test/stand-lifecycle.test.sh"   # 回归：41 断言全绿再部署
```

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

## 测试

```bash
bash ops/test/stand-lifecycle.test.sh
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
