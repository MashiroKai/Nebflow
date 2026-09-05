# 2026-08-30 nebflow-restart.sh 重启失败根因分析与修复方案

> 阶段文档（一次性诊断，完成即冻结）。只分析，未改任何代码/脚本。
> 事件：2026-08-30 00:48 Nebula 以 `--force` 执行 `~/.nebflow/bin/nebflow-restart.sh` 失败；用户 00:59 手动启动成功（pid 28393，8080 唯一 LISTEN）。

---

## 0. 结论速览

**8080 从头到尾没有任何活进程占用。** 直接根因是：

```
旧实例被杀 → 其全部 TCP 连接进入 TIME_WAIT（macOS 2×MSL = 30s，net.inet.tcp.msl=15000）
→ 新实例启动时 SingleInstanceGuard 端口探测用「严格 bind」（setReuseAddress(false)，SingleInstanceGuard.scala:55）
→ bind 撞 TIME_WAIT 套接字 → BindException → 误报 "port 8080 is held by another program"
→ sbt run 退出（全程仅 25s，00:48:53 定局）
```

三个放大器把一次 25s 的快速失败拖成了 10 分钟的「重启不稳定」：

| # | 放大器 | 性质 |
|---|--------|------|
| A1 | 脚本等待循环**不检测 sbt 子进程存活**——sbt 00:48:53 就死了，脚本对尸体盲等满 300s | 设计缺陷 |
| A2 | 脚本以「端口释放」替代「进程死亡」——旧 JVM 半死状态（监听已关、进程存活）又活了约 7 分钟（nebflow.log 45s 调度日志 00:48:34→00:53:49 不间断），并与 00:54:41 拉起的 26671 形成 NEBFLOW_HOME 双写窗口 | 设计缺陷 |
| A3 | `--force` 跳过编译预检（脚本 :379-380）——今晚未爆发（编译仅 ~20s），但属风险面 | 设计缺陷 |

**watchdog 竞态假说证伪**（疑点 1 的主怀疑对象）：watchdog 在 00:44:19 / 00:46:19 / 00:48:19 的 tick 全部健康通过、无任何日志（旧实例 agent 层楔死但 HTTP 403 正常——恰是 watchdog 设计上「不碰」的场景，watchdog.sh:5-7）；首次失败记录 00:50:19，Stage1 触发 00:54:19，且**成功**拉起 pid 26671（21s 就绪）。watchdog 在整个失败窗口之外，且事后证明了「等 TW 过期后再启」就能成功。

**300s 超时不是瓶颈**（疑点 2）：31 commits 增量编译只花了 ~20s（zinc 热缓存；00:54:23 watchdog 预检 `Total time: 0 s` 佐证）。脚本放弃时 sbt 子进程**已死 4 分 40 秒**，本次未留孤儿——但代码路径上放弃时确实不清理，慢编译场景会留。

---

## 1. 完整时序还原（三方视角对齐）

时间锚点：sbt 批次完成 00:48:53（`Total time: 25 s` → 批次起点 00:48:28）；捕获日志 mtime 00:53（脚本退出 = spawn+306s → spawn ≈00:48:28）；watchdog tick 相位 :19。**「00:43 执行」与反推的「脚本实际启动 ≈00:48:0x」有约 5 分钟偏差**（五个独立锚点相互印证，见 §6）——不改变因果链，疑为任务发起时刻与脚本实际执行时刻之差。

| 时刻 | restart.sh 视角 | 旧实例(15779) 视角 | watchdog 视角 | 其他 |
|------|----------------|-------------------|---------------|------|
| ≤00:47:49 | — | agent 楔死但 HTTP 403 正常；nebflow.log 45s discovery 日志正常 | 每日 tick 全部健康通过（日志静默=通过） | — |
| 00:48:19 | 击杀准备中 | 仍在应答 HTTP（该 tick 无失败记录=通过） | tick 通过 ✓ | 旧实例最后一次被外部确认存活 |
| ~00:48:21 | TERM 全树 + TERM sbt launchers 15455/16666（:289-291 全机器扫杀） | 收到 TERM；监听随之关闭 | — | 15455/16666 身份不明，为扫杀副作用 |
| ~00:48:24-26 | 端口等待循环（≤10s）见 LISTEN 消失 → 判定实例已死（:292-300） | **进程未退出**：半死状态开始，45s 调度器继续写日志（00:48:34 起无间断） | — | 内核：旧连接全部进 TIME_WAIT（30s 寿命） |
| 00:48:28 | `nohup sbt run`（:304，SBT_OUT 被截断） | 半死中 | — | sbt 批次启动 |
| 00:48:33-53 | 每 5s 轮询：无 LISTEN、curl 零耗时（循环数学：60×5s+4s 开销=304s ✓） | 半死中 | — | **00:48:53：严格 bind 撞 TIME_WAIT → 误报端口被占 → sbt 退出** |
| 00:48:53-00:53:35 | 盲等（不检测 sbt 存活，:307-321） | 半死中（…00:50:04、00:50:49…持续写日志） | 00:50:19 失败#1；00:52:19 失败#2 | sbt 已死 4 分 40 秒 |
| 00:53:35 | `✗ 300s 未就绪` → 报告「失败」→ exit 1（:322-324，无清理动作） | 半死中 | — | 捕获日志 mtime 00:53 ✓ |
| 00:54:19 | — | 半死中 | 失败#3 → **Stage1**：`sbt -batch compile` **4s 通过**（`/tmp/wd-compile.log` Total time: 0 s，缓存热） | — |
| 00:54:23-24 | — | 半死中 | `restart.sh --force`：探到「端口无进程」（半死实例无监听，杀不到它）→ spawn sbt | watchdog 的 force **杀不到半死实例**——验证 A2 的危害 |
| 00:54:28-44 | — | **半死实例与新实例并行写 NEBFLOW_HOME（双写窗口开启）** | pid 26671 就绪（21s，API=403）→ 报告「成功」→ notify Nebula（00:54:44 userMessage 达） | 26671 自动拉起 4 daemon |
| ~00:54:59 | — | 半死实例仍有残留活动（00:55:11 freeze-resume、00:55:13 LLM 重试失败、00:55:29 `shutting down...`——仅见于 nebflow.log，不见于 SBT_OUT → 归属半死实例） | 00:54:59 Stage1 成功后退出 | **26671 收到 TERM 优雅关机**（daemon 停止日志齐全）→ 用户手动清理窗口（推断） |
| 00:55:3x-00:59 | — | 半死实例最终消亡 | 00:56:59 失败#1；00:58:59 失败#2 | 端口空窗 |
| 00:59:33 | — | — | — | **用户手动 `sbt run` 成功**（pid 28393，launcher 28070）；01:00:59 watchdog 见恢复、计数复位 |

![故障因果链](/tmp/fault-chain.svg)

**排除项**（均有证据）：`com.nebflow.gateway` launchd 任务（RunAtLoad 无 KeepAlive，非运行中，exit 1）不可能中途拉起实例；`com.nebflow.mail-monitor`（python 邮件轮询）不绑 8080；canary 路径 force 模式不经过；Stage2 claude 修复 agent 未触发（最后记录 08-25 05:38）。

---

## 2. 三个疑点的裁定

### 疑点 1：谁占了 8080 —— **TIME_WAIT 套接字（内核态），不是任何进程**

证据链：
1. 脚本 spawn 前已验证 LISTEN 为空（:300 通过，否则打印「端口仍被占用」放弃）；00:48:33-53 每 5s 轮询全部瞬时失败（循环总耗时 304s = 60×5s+4s lsof 开销，**零 curl 耗时**→期间从无监听者）；
2. 00:48:53 Nebflow 自己的探测报「端口被占」（`/tmp/nebflow-sbt-restart.log`）→ 占用者出现在 00:48:48-53 之间、且在 00:50:19（watchdog HTTP=000=无监听）前消失——寿命 <90s、且不应答 HTTP；
3. `SingleInstanceGuard.scala:49-56`：探测 bind 刻意 `setReuseAddress(false)`（防「127.0.0.1 占用者半启动」的 E2E 验证修复）——严格 bind 对 **TIME_WAIT 同样抛 BindException**；macOS `net.inet.tcp.msl=15000` → TW 寿命 2×MSL=**30s**，旧实例连接关闭于 ~00:48:24 → TW 存活至 ~00:48:54 → 探测时刻 00:48:53 正中窗口；
4. `probeNebflow`（:61-63, :72-73）HTTP 探测占用者身份：TW 不是监听者、连接被拒 → `None` → `ForeignOccupant("port 8080 is held by another program")`——与日志逐字吻合；
5. 反证：00:54:41（TW 过期 5 分钟后）watchdog 的实例绑定同一端口立即成功。

**watchdog 竞态假说证伪**：watchdog.log 在 00:44-00:50 无任何记录（tick 静默=健康通过）；其 Stage1 需要 3 次连续失败，首次失败 00:50:19——整个失败窗口内 watchdog 无动作。它 00:54 的 Stage1 反而是**成功路径**。

### 疑点 2：300s 太短 & 放弃后 sbt 死活 —— **编译非瓶颈；sbt 已死，脚本盲等；本次无孤儿、路径会留孤儿**

- 编译：31 commits 增量编译 ~20s（sbt 批次 Total 25s 含编译+启动+探测失败）。00:54:23 watchdog 预检 `Total time: 0 s` 证明 zinc 缓存全热。「冷启动编译数分钟」的担忧今晚不成立——但作为鲁棒性问题保留（见 R3）。
- 放弃时刻（00:53:35）sbt 子进程**已死 4 分 40 秒**（00:48:53 退出）。脚本等待循环（:307-321）只探端口不探子进程 → 对尸体盲等，本次浪费 4.7 分钟才宣布失败。
- 孤儿问题：`nohup … & disown`（:304-305）+ 放弃路径无清理（:322-324）。本次 sbt 自死故无孤儿；**若慢编译 >300s，脚本放弃后 sbt 会继续编译并在数分钟后绑 8080**，与后续 watchdog Stage1 / 手动启动形成绑口竞态——今晚 00:54 Stage1 恰好因 sbt 已死而躲过。

### 疑点 3：--force 跳过预检 —— **确认，但编译不是今晚的失败点**

`--force` 分支（:379-380）直接 `do_restart`：跳过 `preflight_compile`、`preflight_canary`、四信号、终检。今晚编译恰好是热的、通过了——失败在 bind 阶段。风险保留：force 对「31 commits 未预检」的代码，一旦编译坏 = 杀了旧实例必然起不来（watchdog 有编译预检兜底，手动 force 没有）→ R4。

---

## 3. 根因链定稿（按「设计缺陷 / 竞态 / 参数不合理」分类）

**设计缺陷（D）**
- **D1** `SingleInstanceGuard` 端口探测语义过严：严格 bind 无法区分「活监听者」与「TIME_WAIT」→ 把内核回收期误报为「被另一程序占用」。（今晚直接根因的**机制侧**）
- **D2** restart.sh 以「端口释放」充当「实例死亡」：半死 JVM（监听已关、进程存活 7 分钟）骗过 :292-300 的全部检查；其 KILL 升级分支（:293-298）只由「端口仍被占」触发——本次永不触发。后果：①半死实例与后继实例**双写 NEBFLOW_HOME**（00:54:28-00:55:3x 实际发生）；②watchdog 的 force 同样杀不到它。
- **D3** 等待循环不检测 spawn 的 sbt 子进程存活（:307-321）→ 快速失败被拖成 300s 盲等，失败信号滞后 4.7 分钟。
- **D4** 放弃路径不清理子进程（:322-324）→ 慢编译场景必然留孤儿 sbt，成为后续绑口竞态源。
- **D5** 附带击杀 `pgrep -f sbt-launch` 全机器扫杀（:289-291）→ 15455/16666 两个身份不明的 sbt launcher 被误杀（可能是其他项目会话）。
- **D6** `--force` 无编译预检（:379-380）→ 编译坏的代码会「杀了起不来」（watchdog 路径有预检，手动路径裸奔）。
- **D7** notify_nebula 的 `sid` 在 `set -u` 下触发「未绑定的变量」（:275 附近，watchdog.log 08-28 与 08-30 两次复现；通知本身已送达，属卫生问题）。

**竞态（C）**
- **C1** 杀旧实例与启新实例之间的 **TIME_WAIT 排空窗口**（30s）：脚本杀完即刻 spawn，新实例探测撞 TW。今晚直接根因的**时序侧**。
- **C2** 重启动作与 watchdog 未协调：端口空窗 >6 分钟必然累积 3 次失败触发 Stage1 force。今晚 Stage1 在 00:54:19 触发——若原重启再慢 2-3 分钟（冷编译），Stage1 的 force 将与正在编译/启动的实例相撞（双 sbt 写同一 SBT_OUT + 绑口竞态）。**差 2 分钟没发生的竞态。**
- **C3** 半死实例 × 新实例双写 NEBFLOW_HOME（D2 的后果，今晚 00:54:28-00:55:3x 实际发生，未观察到来得及造成的可见损坏——会话/任务文件损坏风险真实存在）。

**参数不合理（P）**
- **P1** 300s 固定等待窗：对热编译过长（今晚浪费 4.7 分钟在尸体上），对冷编译又可能不足（31 commits 全量重编译可达数分钟）。正确形态是「自适应」而非「调大调小」。
- **P2** 杀后端口等待仅 10s（:292）：JVM 优雅关机（会话保存）常超 10s；当前靠 KILL 升级兜底，但升级条件错误（见 D2）。

---

## 4. 修复方案（建议方向，未改码）

![修复后的重启时序](/tmp/fixed-flow.svg)

### R1【根治·源码】SingleInstanceGuard 探测区分「活监听」与「TIME_WAIT」
- **方向**：保持严格 bind（不回退「半启动」修复），BindException 后增加一步 **TCP connect 探测**：connect 被拒（RST=无监听者，仅 TW）→ 判定 `PortDraining`，短暂等待后重探（≤30s）；connect 成功 → 真有活占用者 → 走现有 `probeNebflow` 身份判定。改动点：`src/main/scala/nebflow/cli/SingleInstanceGuard.scala:44-63`。
- **行为变化**：现在「杀完立刻重启」会误报端口被占而失败；改后「杀完立刻重启」能在 TW 排空后自动就绪（≤30s），而真正的外来占用者仍被拒之门外。
- **验收条件**（可断言）：
  - AC1.1 实例 A 建立至少 1 条 TCP 连接（curl 一次即可）→ `kill -9` A → **5s 内**启动新实例 → 新实例 8080 LISTEN 成功且 `curl /api/sessions` 返回 200/401/403；
  - AC1.2 python 起一个 `127.0.0.1:8080` 监听（复现注释中的半启动场景）→ 新实例启动 → 仍报 `ForeignOccupant` 且**不半启动**（8080 仅 python 在 LISTEN）；
  - AC1.3 `sbt compile` 通过 + 既有 SingleInstanceGuard 相关测试全绿（数量以现状为准，不回退）。

### R2【脚本】杀后验证「进程死亡」而非「端口释放」
- **方向**：`do_restart` 杀树前快照 root 全部后代 pid；TERM 后轮询 `ps -p <pid集>` 直至全灭（≤45s），超时对存活者 KILL 升级；**进程全灭后**再做 TW 排空轮询（用与 SingleInstanceGuard 同语义的 python 严格 bind 探测，≤45s）才 spawn。改动点：`nebflow-restart.sh:281-300`（kill 分支的触发条件从「端口仍被占」改为「进程仍存活」）。
- **行为变化**：现在半死实例（监听已关、进程活着）会骗过脚本、留下双写隐患；改后脚本确认进程树真正消亡才继续，半死实例必被 KILL 升级回收。
- **验收条件**：
  - AC2.1 构造半死场景（TERM 后进程存活、端口已释放——可用 SIGSTOP 的 fork JVM 模拟）→ 脚本日志出现 KILL 升级且 spawn 前 `ps -p <root>` 为空；
  - AC2.2 正常重启全程结束后 `pgrep -P <旧root>` 与 `lsof -nP -iTCP:8080 -sTCP:LISTEN` 同时为空；
  - AC2.3 杀完到 spawn 的间隔在 TW 窗口内（<45s）时脚本不 spawn（TW 排空轮询生效）——可用 AC1.1 同款场景在脚本路径复验。

### R3【脚本】启动等待自适应 + 子进程存活检测 + 放弃即清扫
- **方向**：spawn 时记录 `$!`；监控循环每轮三查：①子进程死且端口未就绪 → **立即失败**并 tail 日志（不再盲等）；②子进程活且 SBT_OUT 在增长（编译进行中）→ 续期，总上限放宽到 900s；③端口+API 就绪 → 成功。放弃路径 KILL 整个 sbt 树（ TERM→KILL 两段，防孤儿）。改动点：`nebflow-restart.sh:302-324`。
- **行为变化**：现在「sbt 25s 就死」要等满 300s 才报失败、「冷编译 6 分钟」会在 300s 被误判失败并留孤儿；改后「秒死秒报、慢编慢慢等、放弃不留尸」。
- **验收条件**：
  - AC3.1 注入秒死场景（占住 8080 再跑脚本）→ 脚本 ≤60s 内报失败退出（而非 305s），且退出后 `pgrep -f "nebflow.Main"` 为空；
  - AC3.2 注入慢编译场景（`touch src/**/*.scala` 强制全量重编）→ 脚本在 300s 时**不**放弃、SBT_OUT 持续增长期间持续等待，最终实例就绪报成功；
  - AC3.3 成功路径行为不变：热启动场景 ≤300s 内照常报成功并写 REPORT。

### R4【脚本】--force 保留编译预检（编译失败不杀旧实例）
- **方向**：force 分支在 `do_restart` 前执行 `preflight_compile`（增量、缓存热时秒级），失败即放弃——「绝不杀了起不来」原则对 force 同样成立。提供 `--no-compile` 显式逃生舱（打印双重警告）。改动点：`nebflow-restart.sh:379-380`。
- **行为变化**：现在 force 对编译坏的代码会先杀后失败（宕机窗口拉长到人工介入）；改后 force 遇编译失败直接拒绝动作，旧实例原地保留继续服务。
- **验收条件**：
  - AC4.1 引入一个编译错误（改坏一个文件不提交）→ `restart.sh --force` 退出码 ≠0，期间 8080 的旧实例 LISTEN 不中断、`curl /api/sessions` 始终 200/401/403；
  - AC4.2 撤销错误后同命令正常完成重启；
  - AC4.3 `--force --no-compile` 跳过预检且输出含显式警告行。

### R5【协调】重启全程暂停 watchdog（watchdog-paused 机制已存在）
- **方向**：动作模式（canary/watch/wait-idle/force）进入杀树前 `touch ~/.nebflow/logs/watchdog-paused`，脚本所有出口经 `trap` `rm` 该标志；顺带给 watchdog 加陈旧标志告警（>30min 的 paused 在日志中提示，不自动删——用户手动维护场景仍受保护）。改动点：`nebflow-restart.sh`（trap + 入出口）、`nebflow-watchdog.sh:48-53`（可选告警行）。
- **行为变化**：现在重启造成的端口空窗 >6 分钟会把 watchdog 拖进 Stage1，与重启动作本身相撞（今晚差 2 分钟触发）；改后重启期间 watchdog 明确让位，结束后自动恢复守望。
- **验收条件**：
  - AC5.1 脚本执行期间任意时刻 `test -f ~/.nebflow/logs/watchdog-paused` 成立；脚本退出（成功/失败/中断 Ctrl-C）后 5s 内消失（trap 生效）；
  - AC5.2 重启期间手动触发一轮 watchdog（`launchctl kickstart` 或等 tick）→ watchdog.log 出现「暂停中」且无任何 Stage1/Stage2 动作；
  - AC5.3 双重启演练：人工制造端口空窗 8 分钟（暂停标志就位）→ watchdog.log 零 Stage1 记录；对照（无标志）→ 00:50:19 型失败累计复现。

### R6【脚本】sbt 附带击杀范围收窄到本项目
- **方向**：`:289-296` 的 `pgrep -f sbt-launch` 全机器扫杀，改为仅杀 **cwd 在 `$NEBFLOW_DIR`** 的 sbt 进程（`lsof -a -p <pid> -d cwd -Fn` 过滤），其余项目 sbt 会话不碰。
- **行为变化**：现在任何项目的 sbt 都会被 Nebflow 重启连带击杀（15455/16666 即受害样本）；改后只清本项目的 sbt。
- **验收条件**：
  - AC6.1 起一个 cwd=/tmp 的伪装 sbt-launch 进程 → 跑一次重启 → 该进程仍存活；
  - AC6.2 cwd 在 Nebflow 目录的 sbt（含 fork JVM）仍被正确终止（AC2.2 覆盖）。

### R7【脚本·卫生】notify_nebula 的 `sid` 未绑定告警修复
- **方向**：`:254-279` 中 `sid` 的引用路径在 `set -u` 下有一条未防御分支（watchdog.log 两次复现「行 275: sid: 未绑定的变量」）；将 `sid` 初始化为空串并收敛引用。
- **验收条件**：AC7.1 完整重启成功路径后，脚本输出与 watchdog.log 中不再出现「未绑定的变量」；AC7.2 通知功能不回退（重启后 Nebula 会话收到 userMessage，nebflow.log 出现 `User text (userMessage)` 记录）。

---

## 5. 总验收（冒烟 + 端到端重放）

冒烟是硬性前置——以下任一不过，其余无效：

1. **SMK-1 真实启动**：`sbt run` 真实进程启动 → `curl /api/sessions` 返回 200/401/403 → `curl /` 返回 HTML（含前端挂载点）。
2. **SMK-2 脚本冒烟**：`nebflow-restart.sh`（无参数）→ 输出四信号状态与 port_pid，退出码 0。
3. **E2E-R 重放今晚场景**（修复后必须 5/5 通过）：健康实例 + 1 条活动连接 → `restart.sh --force` → 断言：①全流程 ≤120s 内报告「成功」；②新实例 pid ≠ 旧 pid；③期间无 ForeignOccupant 误报；④`pgrep -P <旧root>` 为空；⑤watchdog.log 零 Stage1 记录（R5 生效）。
4. **E2E-W watchdog 演练**：杀掉实例不重启 → watchdog 3 次失败后 Stage1 自动拉起成功（回归确认今晚 00:54 的成功路径不被 R2-R5 改坏）。
5. **回归**：`sbt compile` + 既有测试全绿；canary 路径（`--canary --dry-run`）全流程通过。

---

## 6. 证据文件与推断依据

| 证据 | 路径 | 关键内容 |
|------|------|---------|
| 严格 bind 探测 | `src/main/scala/nebflow/cli/SingleInstanceGuard.scala:49-63` | `setReuseAddress(false)`；BindException→probeNebflow→ForeignOccupant 逐字对应日志 |
| TW 寿命 | `sysctl net.inet.tcp.msl` = 15000 | TIME_WAIT = 2×MSL = 30s，正覆盖 00:48:24→00:48:53 |
| 脚本逻辑 | `~/.nebflow/bin/nebflow-watchdog.sh`、`~/.nebflow/bin/nebflow-restart.sh` | force 分支 :379-380；盲等 :307-321；扫杀 :289-291；KILL 升级条件 :293 |
| watchdog 时间线 | `~/.nebflow/logs/watchdog.log:789-803` | 00:50:19#1 → 00:54:19 Stage1 → 00:54:59 成功；00:44-00:48 零记录=健康通过 |
| 半死实例 | `~/.nebflow/logs/nebflow.log:40-72,105-140` | 45s discovery 日志跨击杀时刻不间断（00:47:49→00:53:49）；00:55:11-29 残留活动仅见于本文件 |
| sbt 批次 | `/tmp/nebflow-sbt-restart.log`（mtime 00:54:59.6） | `Total time: 25 s, completed 00:48:53`；尾部为 26671 的 00:54 启动与关机（SBT_OUT 被两次截断复用） |
| 脚本退出时刻 | `/tmp/nb-restart-20260830.log`（mtime 00:53） | spawn(00:48:28)+306s=00:53:34 ✓；循环 304s=60×5s+4s lsof 开销 → 零 curl 耗时 → 期间无监听者 |
| watchdog 编译缓存 | `/tmp/wd-compile.log` | `Total time: 0 s, completed 00:54:23` → zinc 全热，编译非瓶颈 |
| 成功报告 | `/tmp/nebflow-restart-last-run.txt` | `00:54:44 成功 pid=26671`（watchdog 路径） |
| 「00:43 vs ≈00:48」偏差依据 | 五锚点互证：①sbt 批次起点 00:48:28；②捕获日志 mtime 00:53=spawn+306s；③watchdog 00:48:19 tick 通过→击杀在其后；④force 无任何可停留 ≥5min 的代码路径；⑤若 spawn 在 00:43，循环退出 00:48:3x，mtime 应为 00:48 而非 00:53 | 脚本实际启动 ≈00:48:0x；00:43 为任务发起/记忆时刻。不影响因果链 |
| launchd 排除 | `~/Library/LaunchAgents/com.nebflow.{gateway,mail-monitor,watchdog}.plist` | gateway 无 KeepAlive 非运行态；mail-monitor 不绑 8080；watchdog 每 120s |
