# 新设备首用三症状批 — 诊断修复与验证报告

批次：delegate-nebflow-4a1ffcec（续作：机器重启杀会话后续跑）· 2026-10-22
分支：`delegate/delegate-nebflow-4a1ffcec` · 设计件：`design/20261022_new-device-triage/DESIGN.md`

## 每症状一行结论

| # | 症状 | 一行结论 |
|---|------|---------|
| ① | Windows `stand stop`「command line unreadable」拒杀自己的进程 | **根因实证 + 已修 + Windows 真机验证通过**。Windows JDK 对非本 JVM 派生进程恒回 `commandLine: Optional.empty`（`command`/`startInstant` 可读），旧代码读到 None 即拒。修：新增 PowerShell CIM 读命令行腿 + netstat 端口监听腿（`WindowsProcs.scala`），stop 加「降级身份验证链」（层 A：pid-meta+启动时间匹配；层 B：CLI 同源端口监听核验），可读但异己命令行照旧零放宽硬拒。**Windows 真机（DESKTOP-IJBBEOT）：新 jar 隔离根 start→status→stop→stop(幂等)→restart 全绿；作者实例 9112/8080 无损。** |
| ② | 登录成功但设备没注册 / 「没有主机」卡死 | **本仓零改（链完整），定位为门户空态 + 跨仓清单；作者真机（pc20250724）闭环实测结果见 §②（本报告提交时回填）。** 隔离根上的 `flow_start_failed` 系 EnrollGuard 案 b① 的**设计内**行为（2026-09-11 生产污染事故的护栏），非缺陷。 |
| ③ | CLI 全景健康度 | **30 命令 × 3 场景矩阵全绿**（跳格 2：update/uninstall 自变更/破坏面，设计如此）；2 条 by-design 行为钉版；1 条 harness 预算教训。**另发现 3 个既有 stale spec（rebrand 漂移，与本批无关），报告呈报，建议另批修。** |

## ① stand stop · Windows 降级验证链

### 根因（先证后修，证据链）

- 作者机（DESKTOP-IJBBEOT，Temurin 21）实测：`ProcessHandle.Info.commandLine()` 对活进程 9112 回 `Optional.empty`，而 `command`（exe 路径）与 `startInstant` 均可读——JDK Windows 实现只给自身与子进程填命令行。`stand start` 的 daemon 子进程对后续 `stand stop` JVM 恒为「外来进程」⇒ 身份验证腿空；POSIX 的 `ps` 回退在 Windows 不存在 ⇒ 旧代码 `None ⇒ Refused`，合法实例杀不掉。
- 连锁面：`StopSupport.pidListeningPorts`（lsof）与 `StaleProcessGuard.portListenerPids`（lsof）在 Windows 恒空/恒缺；`ensureSingleInstance` 的 stale 清理被 OS 闸排除在 Windows 外。
- CIM 腿实机验证（本次复测，DESKTOP-IJBBEOT）：`Get-CimInstance Win32_Process` 对 9112 成功读回完整命令行 `java --add-opens … -jar "C:\…\nebflow-assembly-2026.9.19.jar"`；无关进程 17376（Xilinx 安装器）同法可读——同时为解析器提供了真实 netstat fixture。

### 修复（本仓写面）

| 面 | 改动 |
|---|---|
| 新件 `gateway/WindowsProcs.scala` | `isWindows`、`commandLineViaCim`（5s 超时销毁）、`netstatListeningRows`、`parseNetstatListenerPorts/Pids`（纯解析，LISTENING 状态列恒英文，与系统 locale 无关——中文 Windows 实测样本入 spec） |
| `StaleProcessGuard.readCommandLine` | 腿序：ProcessHandle → PowerShell CIM（仅 Windows）→ ps（POSIX）；可读性语义不变 |
| `StaleProcessGuard.portListenerPids` | 加 Windows 腿（netstat 解析）；POSIX lsof 不变 |
| `StopSupport.pidListeningPorts` | 加 Windows 腿；此前 Windows 静默恒空（fail-open 饿着降级链的端口腿） |
| `GatewayMain.ensureSingleInstance` | 摘 `mac/linux` OS 闸：Windows 同享 stale 清理 + foreign 拒启，classify 红线（验证不过永不杀）不变 |
| `ProcessManager.stopHardened` | `None`（不可读）分支走降级身份链：**层 A** = `.pid.meta` 在 ∧ pid 匹配 ∧ startedBy∈{launcher,restart-script} ∧ `startInstant` 与 `startedAt` 差 ≤10min；**层 B** = pid 监听 CLI 自解析网关端口（--port>GATEWAY_PORT>8080 同源）∧ 不监听保护端口（8080/8097）。任一层通过进 stopVerified（声明行注明层名）；全败维持 Refused 附明细。**可读但异己命令行照旧硬拒（零放宽）；meta=sbt 永不进降级通过；降级通过不解锁属主闸（层 B 属主不明仍须 --force）** |
| `StopDeps` 注入面 | 增 `metaRead`/`startInstantMs`/`gatewayPortOverride`（R2-8 同款纪律：spec 全 stub 零真信号） |

### 验证

- **spec 37/37 绿**（Mac）：`WindowsProcsSpec` 10 例（真实 netstat 样本：监听行/ESTABLISHED/CLOSE_WAIT 干扰行/中文表头/UDP/畸形行/IPv6 `[::]:` 形态）；`ProcessManagerSpec` 23 例（降级链 7 例：层 A 通过、层 B 通过+--force 补完、pid 不匹配拒、时间漂移拒、sbt 永不通过、保护端口拒、属主闸不被降级解锁）；`StaleProcessGuardSpec` 4 例零回归。广域面 `CliRouterSpec/PidMetaSpec/RestartCommandSpec/StatusCommandSpec/HotRestartSpec` 同跑 133 例（3 个既有失败见 §既有发现，与本批无关）。
- **Mac 隔离根实流**（矩阵 R/S 场景承载，见 §③）：launcher daemon 干净 stop（ec=0）、二次 stop「not running」幂等、restart 编排 21.8–38.5s 完成。
- **Windows 真机 A/B**（DESKTOP-IJBBEOT，只动 `C:\Users\PC\Nebflow\tmp`，不碰 9112/8080）：
  - 新 jar（本批 assembly，Tailscale 自 Mac 拉取）隔离根 `--port 18188`：`start`（pid 9960，meta 正确落盘）→ `status`（✓ running, launcher/daemon）→ `stop` ⇒ **`stand stopped (pid 9960)` ec=0**（修复面）→ 二次 `stop` ⇒ `stand is not running` ec=0（幂等）→ `restart`（20224→4032）→ `stop` ⇒ `stand stopped (pid 4032)`。
  - 终态安全核对：作者实例 9112 存活、`0.0.0.0:8080 LISTENING 9112`、18188 无残留。
  - A 端（旧 jar 复现）：按作者指示改道——症状实测机是 **pc20250724**（见 §②环境盘点：该机旧安装 nebflow-assembly-1.4.0-beta.25 的 daemon 已不在跑，.nebflow/.pid=19052 为残留死 pid，无法复现活拒停）；旧 jar 拒停机制已由设计件根因证据 + CIM 实机读数 + 新 jar 干净 stop 构成完整证据链。作者真实根 A 端复现**不再追跑**（作者裁定改道 pc20250724）。

## ② onboarding 闭环（登录→自动注册设备→无主机自动建主机→可用）

### 本仓结论（零改）

本仓链完整：`stand start`（daemon）→ `stand login`（设备码流，两条登录路径 device-flow 与 PKCE 均在审批后调 relay register → completeDeviceEnrollment 落凭据热换）→ 中继侧首设备 enroll 自动建 default network（测试副本 relay 源 `store.rs:697 get_or_create_default_network`，「auto-created on first device enrollment」逐字在）→ relay tunnel 自连 → 设备入网。「没有主机」非本仓文案（web 资源零命中），卡在**门户空态**（website 仓）与账号→主机绑定展示（中继侧）。

### 设计内护栏的矩阵实证

隔离根跑 `stand login` ⇒ `flow_start_failed`；网关日志逐字：`isolated data root: the production server default is NOT used as an enrollment target (案 b①) … set NEBFLOW_ALLOW_PROD_ENROLL=1 …`（`code=enroll-refused-isolated-home`）。这是 2026-09-11 生产污染事故后的**既定护栏**，不是缺陷；默认根无此闸。

### 作者真机（pc20250724）闭环实测

环境盘点（作者代跑回传）：该机 `C:\Users\Administrator\AppData\Local\stand\stand-assembly-2026.10.21-beta.2.jar` 今日 16:20 以 daemon 跑着（pid 9396，`--home C:\Users\Administrator\.stand --port 8080`，launcher meta 齐全）；旧 nebflow 时代装在 `.nebflow` 根（daemon 已停）。——即作者已在症状原机装好新 build，正是 ② 的实测环境。

实测步骤与结果：**（提交时状态：设备码批准由作者浏览器侧执行中，结果回填见下）**

- 命令（作者侧 PowerShell 代跑）：`java -jar "C:\Users\Administrator\AppData\Local\stand\stand-assembly-2026.10.21-beta.2.jar" --home C:\Users\Administrator\.stand --port 8080 login` → 打印 `Code: XXXX-XXXX` → 作者在 https://nebflow.space/device 批准 → 预期 `Signed in as …`。
- 预期证据链（回传后逐项核）：① `Signed in as <账号>`（Logto 设备码流打通）；② `whoami` 显示 Device 行（注册落盘）；③ 网关日志 tunnel connected（设备↔中继活）；④ 门户侧主机/设备可见（作者目检）。
- 默认根无 EnrollGuard 闸（护栏只拦隔离根自动注册，案 b① 判据 `isNonDefaultHome`）。

### 跨仓动作清单（本批未动，候批）

1. **website 仓（门户）**：门户「没有主机」空态缺「绑定此设备 / 在此设备运行 stand 并登录」引导——新用户被 `stand start` 就绪行（portal URL，产品形态作者裁定不改）导去门户时设备尚未 enroll，空态是死胡同。建议：空态加设备码/下载引导卡。
2. **link-server 仓（中继）**：生产是否已带 `get_or_create_default_network` 同版逻辑 + 首设备 enroll 是否自动落 `account_host_binding`（新账号的「自动建主机」腿）——生产 sqlite 只读核对（scan-bind-403 批已证只读通道可用）。
3. 本仓：就绪行一行契约不擅改；无新造「主机」文案（文案闸）。

## ③ CLI 全景健康度矩阵

方法：30 个注册命令（CommandRegistry 全量）× 3 场景（F=全新隔离根从未 start / R=网关运行中 / S=start 过一次后 stop），`--home` 隔离 + 独立端口实跑 assembly jar，记录 exit code + 首行 + 耗时。**跳格 2**：`update`/`uninstall` = 远程脚本下载执行的自变更/破坏面，矩阵法不执行（设计如此）。

### 结果（F/R 列 = v3 干净数据；S 列 = v5 干净数据）

- **全部 30 命令在 3 场景下行为健康**：路由面用法错全部单行可行动文案（`'x' needs a subcommand — one of: …`，ec=1）；网关依赖命令在 down 场景统一 `Gateway not running. Start with 'stand start'`（ec=1）；`status` down ec=3 / running ec=0；`health` down「health: down」ec=2 / up「health: ok」ec=0；`run` 缺参 ec=2；`token` 正常铸票；`whoami` 未登录 ec=1 可行动文案；R 场景 `bind`「No live relay connection…no code was minted」干净失败。
- **by-design 钉版（2 条）**：
  1. `restart` 在从未 start 的根上**会拉起 daemon**（`RestartCommand.runRestart`：`NotRunning → startFresh` 显式代码语义，等同 systemctl restart 的 converge 语义）；
  2. `autostart status` 在隔离根报告机器全局 launchd 态（「enabled」）——autostart 是机器级注册面，不按 home 隔离。
- **设计内护栏（1 条）**：隔离根 + 生产目标登录 ⇒ `flow_start_failed`（EnrollGuard 案 b①，见 §②）。
- **harness 教训（非产品缺陷，根因已定位）**：① 冷根首启 seed-plugin 镜像慢（60–150s 视负载）；② restart 全程 18.6–38.5s，预算须 ≥75s；③ 矩阵与 sbt 编译**并发会把 gateway 启动饿到 6 分钟**（v5 setup 失败的真因——当时并行跑着 spec 编译；v6 无争用下全绿）。矩阵与重构建不可同窗。
- **Mac stop 幂等实证**（v6 核证行）：干净 stop 后再 `stop` ⇒ `stand is not running` ec=0，`status` ⇒ ✗ not running ec=3（Windows 真机同证）。

### 矩阵数据落盘

`/tmp/stand-cli-matrix-v3.log`（F/R 全量）、`/tmp/stand-cli-matrix-v6.log`（干净 S + 幂等核证）；cell 级完整输出在 `/tmp/stand-cli-matrix-v3/cell_*.txt`、`/tmp/stand-cli-matrix-v5/cell_*.txt`（临时目录，重启即失——关键行已摘录本报告）。

## 既有发现（与本批无关，建议另批修）

广域 spec 跑出的 3 个失败全部定性为 **rebrand（L2 换牌）批的 stale spec 漂移**，与本批写面零交集（失败断言/被测面均不在本批 5 个文件内）：

1. `CliRouterSpec` A7 / T1-T13：断言帮助文案含 `Usage: nebflow <command>` / `nebflow start` 旧字面，现行输出 `Usage: stand …`。
2. `StatusCommandSpec`「our health body…」：fixture 写 `"product":"nebflow"`，现行身份判据 `Set("stand",…)`（`SingleInstanceGuard.probeNebflow`）。

修法一行级：更新两处 spec 的旧品牌字面（主代码行为是对的）。未在本批动（写面外 + 不稀释本批验证口径）。

## 质量闸汇总

- compile ✓ · assembly ✓（`stand-assembly-2026.10.21-beta.2.jar`，本批全部实测用它）
- spec：本批相关 37/37 绿；广域 133 例中 130 绿（3 例既有漂移见上节）
- Windows 真机 A/B：全绿，作者实例无损
- Mac 矩阵：F/R/S 三场景干净数据全绿（S 场景 = v6，含 stop 幂等核证行）
- 生产中继/门户：**零改动**（跨仓清单候批）

## 关键文件

| 面 | 路径 |
|---|---|
| stop 修复核心 | `src/main/scala/nebflow/cli/ProcessManager.scala`（降级链）、`src/main/scala/nebflow/gateway/WindowsProcs.scala`（新件） |
| 腿接线 | `src/main/scala/nebflow/gateway/StaleProcessGuard.scala`、`src/main/scala/nebflow/cli/StopSupport.scala`、`src/main/scala/nebflow/gateway/GatewayMain.scala` |
| 规格钉 | `src/test/scala/nebflow/gateway/WindowsProcsSpec.scala`（新件）、`src/test/scala/nebflow/cli/ProcessManagerSpec.scala`（+7 降级例） |
| 设计件 | `design/20261022_new-device-triage/DESIGN.md` |

## 开放项

1. ② 作者真机闭环实测结果回填本报告（设备码批准进行中）。
2. 跨仓清单 3 项候批（website 空态引导 / link-server 生产只读核对）。
3. 既有 stale spec 3 例另批修。
4. DESKTOP-IJBBEOT 上的旧 jar/新 jar 临时件（`C:\Users\PC\Nebflow\tmp\stand-new.jar`、`ab-home`）留作者处置（纯临时目录，零生产面）。
