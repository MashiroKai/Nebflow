# 启动 OOM 归因报告（boot-oom-attribution）

日期：2026-10-25 · 调查：delegate-nebflow-85233591 · 目标构建：main `deebc278c`（与 2026-10-24 22:07 首次 OOM 构建 `a03fd0ba3` 的 filewatch 代码一致）

## ROOT CAUSE（一句话）

**网关启动时以 `System.getProperty("user.dir")`（进程当前工作目录）为根做全量文件树快照扫描（`FileChangeTracker.create` → `scanTree`，GatewayMain.scala:579），而 2026-10-25 凌晨的生产启动链（launchd `com.nebflow.gateway` 的 `WorkingDirectory=/Users/kaiyu` → 旧版 `bin/stand` 包装脚本从不 `cd`）使 user.dir = 用户整个家目录：扫描器对家目录每个文件做 stat + relativize + 正则 quote，产生 GB/s 级路径字符串分配风暴，2G 堆在 +15-17s 被 ZGC「分配停顿」（大对象无法放置，死时仍有 974M 空闲）打穿 → OOM。**

这不是数据问题、不是面板/桥接/neblink/LLM 问题——是「启动期目录遍历 × 启动器工作目录」的耦合事故。

## 证据链

### 1. 死亡形态（gc.log，主树 `.nebflow/logs/jvm/`）
- `gc.log`（02:27 试图，pid 49572）：`GC(0)@0.541s` 垃圾 85M → `GC(1)@0.810s` **195M**；15 秒内 **29 个 GC 周期**；14.5-14.9s 之间 young 大页 67→**90 页/1548M**（单对象 ~10MB 级，~17GB/s 瞬时分配）；`GC(15) O: Large Pages 2 页/454M`（~227MB 级瞬态大对象）；**终结态：`GC(28) O: Used 1074M(52%)、Free 974M` + `Major Collection (Allocation Stall)` → `Allocation Stall (io-compute-blocker-7) 155ms` → `Out Of Memory`** ——死于大对象「放置失败」，不是堆容量不足。
- `gc.log.4`（02:20 试图）与 `gc.log` 数字**逐字节一致**（85M→195M@0.82s）→ 风暴是**确定性**的启动期行为，与夜间负载无关。
- OOM 线程名 `io-compute-blocker-N` = IO.blocking 线程形态 = 文件系统操作。

### 2. 对照实验矩阵（全部隔离临时数据根 + 808x 端口，绝不触碰 PID 49839/6138 与生产数据）

| 实验 | 增量 | 结果 |
|---|---|---|
| E1 | 空数据根 | 存活 |
| E2b/E3 | +stand 工作区(5.4 万轮台账)/+6.5GB 会话 | 存活 |
| E4 | rsync 近全量 36GB 克隆（21 项目/冷档/flow-maps） | **存活，3 分钟全程仅 2 个 GC 周期** |
| E5/E6 | +REST 客户端/+票据认证真浏览器 WS（accepted=1） | 存活 |
| E7 | +真实 neblink 身份（内置隔离守卫拒绝注册生产 relay） | 存活 |
| E8 | +tool-results(1.4GB)/browser-use(1.3GB) + 认证浏览器 | 存活 |
| E9 | +生产 `logs/`(202MB) | **复现风暴**：t=13s 起 179M→335M 垃圾/0.3s，4.4 分钟 95 个 GC |
| E10 | 同 E9 但 CWD=主仓库（复刻 v2 启动器） | **风暴**：128 秒 98 次 GC，270M 垃圾/周期 + old-gen 403M |
| **E11** | **CWD=/Users/kaiyu（复刻凌晨 v1 启动链）+ -Xmx2g** | **完全复现：4.6 秒内 96 次 GC 暂停，每 ~100ms 300M 垃圾（≈3GB/s），G1 Humongous Allocation 连发** |

### 3. 风暴分配点（E9/E11 现场 JFR，`jdk.ObjectAllocationSample`）
- `allocation-by-site`：`UnixPath.getName` 21.65%、`Arrays.copyOfRange`(StringBuilder.toString) 20.64%、`sun.nio.fs.Util.toString` 19.37%、`UnixFileAttributes.get` 9.09%、`Path.iterator` 4.84% —— **~76% 分配压力 = 目录遍历的路径字符串操作**。
- 命中的应用栈：`nebflow.core.FileChangeTracker$$anon$1.visitFile (filewatch.scala:171)` ← `Files.walkFileTree` ← `scanTree`。
- E9 风暴起点秒级对齐 `LLM log recording: disabled … retention=armed`（GatewayMain.scala:454，紧邻 :579 的 tracker 创建）。
- 健康对照（E4，3 分钟）仅 2 个 GC；生产死亡引导 15 秒 29 个。

### 4. 为什么凌晨必炸、而白天实验存活
- 凌晨启动链 v1：launchd plist `WorkingDirectory=/Users/kaiyu`（`~/Library/LaunchAgents/com.nebflow.gateway.plist`）+ `bin/stand`（`exec java -jar`，**无 cd**）→ user.dir=家目录。家目录去除 `ExcludedDirs`（.git/.nebflow/target/node_modules 等）后仍有 **~/Library、~/.stand、Documents、Downloads… 数十万文件**；`~/Library` 中的巨型目录使遍历时间与分配量爆炸。
- 白天实验一律从 worktree/主仓库目录启动（prune 后仅数千文件）→ 风暴小 20-50×，未致死（E9/E10 仍可见）。
- 02:39 的 v2 `nebflow-autostart.sh` 已改为 `cd 主仓库`，风暴量级下降但**依然存在**（E10 实测 98 GC/2min）——代码修复在任何 cwd 下都必要。
- 22:16 曾有一次存活 >29s 的启动：22:07 首次 OOM 的遍历已把家目录元数据拉进页缓存，重试遍历大幅加速——解释了「时炸时不炸」。
- 佐证：c59f51f7f（10-25 02:29）修复留言中「UnixPath/os.Path 5-6 万实例的目录遍历风暴签名」即本风暴的堆直方图侧面。

### 5. 排除项（均经实验证伪）
sessions(.ui.json 303 个超大文件)/台账冷档/flow-maps/task-board（E2b/E3/E4）；面板 REST+认证 WS 客户端突发（E5/E6，`/api/sessions/{id}/history` 实测 3.1MB/0.11s）；feishu/weixin 桥接真实连接（E6）；neblink 注册态（E7，内置隔离守卫生效）；LLM retention 扫描（router/ 目录当前仅 14KB）；tool-results/browser-use 载荷（E8）。

## 修复（本 worktree，`deebc278c` + 1 commit）

`src/main/scala/nebflow/core/filewatch.scala` —— 双保险，均 fail-soft：

1. **根守卫 `isUnscannableRoot`**：user.home 与文件系统根永不遍历；`create` 命中时告警并产出**空快照 tracker**（空快照使周期性全扫描永不触发——`fullScanNeeded` 要求非空快照——拒绝态自我保持）。
2. **遍历预算**：任何扫描受文件数（默认 200,000，`nebflow.filewalk.maxFiles` 可调）与墙钟（默认 10s，`nebflow.filewalk.maxMillis`）双重封顶，超限抛 `ScanBudgetExceeded` → 沿用既有 fail-soft 契约丢弃整扫（Map.empty）。任何 cwd、任何目录规模下启动成本从此有上界。

回归测试：`src/test/scala/nebflow/core/FileChangeTrackerBootGuardSpec.scala`（根守卫 3 断言 + 家目录 create 拒绝 + 预算丢弃/解除对照）。既有 `FileChangeTrackerNebflowExcludeSpec`/`FileChangeLedgerSpec` 保证语义不变。

**后续建议（不阻塞本修复）**：a) 启动器仍应显式 `cd` 到明确目录（v2 已做，保留）；b) 中期把 tracker 的根从 user.dir 换成显式项目清单（数据根 projects/*），语义更准；c) `GatewayMain.scala:579` 处可加一行启动日志打印实际扫描根，便于未来同类问题 5 分钟定位。

## 遗留清理项（非代码）

1. **3 个杂散 neblink 设备身份**：E1-E3 实验各铸造过一个（896651f1…、c6049534…、57669c98…，见 /tmp/nf-oom-repro/home1-3），需在 NebLink Server 侧吊销；E7+ 因隔离守卫未再铸造。
2. **/tmp/nf-oom-repro ≈ 60GB+**（home1-4 克隆 + 11 个 JFR + 采样输出）：确认后 `rm -rf`。home4 内含生产数据副本（sessions/台账），勿外传。
3. `/tmp/mat2.dmg`、`/tmp/MAT.app`、`/tmp/mat*.ini`、`/tmp/hv`（venv）、`/tmp/nf-cp.txt`、`/tmp/nf-e8*`：可删。MAT 经 brew 装后两度从 /Applications 消失（疑似用户/清理策略），本次改从 /tmp 运行，未再安装。
4. `~/.nebflow/logs/`：E11f 的受监督子进程曾按相对路径写入 gc 日志（11:15-11:21），如见多余 `jvm/gc.log*` 可删。
5. 凌晨实验（本报告前一轮）曾重启过 home3/home4 的克隆 daemon（CZT Garfield Sim/AI FPGA Deck，均为克隆内 vite/npm 实例），现已全部停止。
6. 生产引擎（6138，PID 49839，2026.10.21-beta.2 旧构建）全程未触碰；本修复合入 main 后正常重启即可生效。

## 修复验证（E12/E13，守卫生效实证 + 残留 churn 说明）

- **E12**（CWD=/Users/kaiyu，**已修复**构建，默认堆）：启动日志出现 `FileChangeTracker: refusing to walk unscannable root '/Users/kaiyu' (user.home) — snapshot disabled`（根守卫生效）；进程存活，没有复现死前风暴形态。
- **E13**（同配置 + JFR，exit 后 dump）：`jdk.ObjectAllocationSample` 栈中**不再出现** `FileChangeTracker$$anon$1.visitFile ← Files.walkFileTree`；walkFileTree 样本指向唯一的第二扫描点 `nebflow.core.plugin.PluginRegistry.computeDigest`（`os.walk` + 逐文件读字节做 SHA-256），其根为 `SeedService.ensureSeeded()` 内部的 **`PathUtil.dataRoot`（SeedService.scala:216）= dataRoot/plugins，与 CWD 无关**——E4（干净对照，同一 dataRoot 克隆）3 分钟仅 2 个 GC 证明该路径廉价。
- **残留 churn 归因（非阻塞）**：修复后 home-CWD 启动仍有 ~61 GC/6.3s 的中等分配噪声，但 E13 的分配样本显示其主体是 **`-cp` 直启方式下 JVM 从 ~120 个 classpath jar 逐个 defineClass 的类加载（单事件 21MB/77MB byte[]）**——这是实验方法（java -cp 直启）特有的形态，生产走单个 fat assembly jar，加载剖面完全不同；叠加种子 digest（如上，廉价）。二者都不是 cwd 树遍历驱动，无法复现生产死亡签名（15 秒 29 GC + Allocation Stall + Free=974M），因此不改变 ROOT CAUSE 结论，也不阻塞本修复。
- **生产死亡机理补全**：实验实例（E11i）未死于风暴，是因为随机崩溃点（feishu 隔离守卫 strictBoot）抢先把进程带走；生产凭据匹配、无守卫拦截，遍历与启动挂载阶段并发跑到 +15-17s，2G 堆被大对象放置失败（ZGC Allocation Stall）终结。修复移除遍历本身后该机制不存在。

## 修复验证（合入前建议复跑）

```
sbt -J-Xmx4g 'testOnly nebflow.core.FileChangeTrackerBootGuardSpec nebflow.core.FileChangeTrackerNebflowExcludeSpec nebflow.core.FileChangeLedgerSpec'
# 终极复现对照（可选）：cd /Users/kaiyu 后按 E11 方式启动 → 修复前 96 GC/4.6s；修复后应仅见
# 一条 WARN「refusing to walk '/Users/kaiyu'」且 GC 次数回到 E4 量级（3 分钟 2 个周期）。
```
