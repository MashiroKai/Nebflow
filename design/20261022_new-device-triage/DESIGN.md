# 新设备首用三症状 · 诊断修复批 — 设计与执行令（自批）

批：delegate-nebflow-4a1ffcec · 2026-10-22 · 工作树 `delegate/delegate-nebflow-4a1ffcec`

## 0. 诊断结论（先证后修）

### 症状 1：`stand stop`「command line unreadable」拒杀自己的进程 — **根因已实证**

Windows 上 JDK `ProcessHandle.Info.commandLine()` 对**非本 JVM 派生**的进程恒回
`Optional.empty`（`command`/exe 路径可读，完整命令行不可读——JDK Windows 实现只为
自身与子进程填 cmdline）。实测（作者机 DESKTOP-IJBBEOT，Temurin 21，PidProbe 对
活进程 9112/17376）：

```
== pid 9112
  isAlive: true
  commandLine: Optional.empty      ← 病灶
  command: Optional[...java.exe]
  startInstant: Optional[2026-10-18T10:04:48.130Z]   ← startInstant 可读！
```

`stand start` 的 daemon 子进程对后续 `stand stop` 进程而言恒是「外来进程」⇒
ProcessHandle 腿空；`ps -p` 回退腿在 Windows 不存在 ⇒ `readCommandLine = None` ⇒
`ProcessManager.stopHardened` piece 1 判「command line unreadable」→ Refused →
合法实例杀不掉。Mac/Linux 无此症（sysctl/procfs 可读）。

连锁面（同根因）：
- `StopSupport.pidListeningPorts` 用 `lsof` —— Windows 没有 ⇒ 恒空（fail-open，端口确认腿退化）。
- `StaleProcessGuard.portListenerPids` 用 `lsof` —— Windows 没有 ⇒ `GatewayMain.ensureSingleInstance`
  直接被 `os.contains("mac")||os.contains("linux")` 闸在门外（Windows 无 stale 清理）。
- 降级验证材料 **已在手**：`.pid.meta`（pid/startedBy/startedAt）+ ProcessHandle
  `startInstant`（Windows 可读）+ netstat 端口监听者（Windows 自带 netstat -ano）。

### 症状 2：新设备/新账号「没有主机」卡死 — **定位：portal/中继侧，不在本仓**

证据链：
1. 本仓 web 资源（现行 web/js + locales zh-CN/en）**零**「主机」字串；作者机上旧版
   jar（2026.9.19）的 locales 同样零命中 ⇒ 「没有主机」非本仓控制台文案。
2. 登录初始化卡设计批（reports/20261019_205500_login-init-card）§1 已定位：「账号下
   一台主机都没绑定」那张卡在**官网门户**（mystand.space/portal，website 独立仓，
   `brand.conf:portalUrl`），不在本仓。
3. 中继侧证据（生产 sqlite 只读，scan-bind-403 批 2026-10-22）：`account_host_binding`
   表 = 账号→网络→主机 的绑定（Mac 机 = bound）；「主机」= 中继上与账号绑定的宿主机。
   测试副本 relay 源（`~/.neblinktest/projects/neblink-server/src/store.rs:696`）：
   **default network 已是「首次设备 enroll 自动创建」**（get_or_create_default_network）。
4. 本仓 onboarding 链完整：`stand start`（daemon 起）→ `stand login`（设备码流，网关
   必须在跑）→ enroll（中继自动建 default network + 设备注册）→ relay tunnel 自连
   （作者 Windows 机日志实证 tunnel connected）→ 设备入网。本仓**无缺失环节**。
5. ⚠ 关键导流缺陷（本仓可议面）：`stand start` 就绪行打印的是 **portal URL**
   （`DaemonSpawn.readyLine` ← `PortalUrl.openUrl`，`https://mystand.space/portal?device=<id>`）
   ——新用户被第一行导去门户，而此时设备尚未 enroll ⇒ 门户「没有主机」= 死胡同墙。
   就绪行是「一行 ≤200B」钉死契约（spec），portal URL 是产品形态（作者裁定的浏览器
   入口），**不擅改**；病根在门户「无主机空态」缺「绑定此设备」引导（website 仓）+
   中继「首设备 enroll 自动绑 host」（link-server 仓，若尚未实现）。

⇒ 本批动作：本仓零改（无 bug 可修）；交**跨仓动作清单**（报告 §onboarding）+ Mac 隔离
根全流程实证（新设备场景走通到可用态）。开放问题（报告呈报）：作者 Oct 21 实测用的
账号（kaiyu@mail.ustc.edu.cn 若为全新账号）其 enroll 是否发生、中继是否已绑 host——
需作者侧/生产库只读核对，不阻塞本批。

### 症状 3：CLI 全景健康度 — 矩阵法

30 个注册命令（CommandRegistry）× 场景（新设备隔离根 / 网关停 / 网关运行），隔离
home+port 冒烟，exit code + 首行记录成矩阵；坏的修，设计如此标注。

## 1. stop 修复设计

### 1a. Windows 命令行读取（修「读不到」）

`StaleProcessGuard.readCommandLine` 链改为：
`viaProcessHandle → viaPowershellCim(仅 Windows) → viaPs(POSIX)`。

- `viaPowershellCim`：`powershell -NoProfile -NonInteractive -Command
  "(Get-CimInstance Win32_Process -Filter 'ProcessId=<pid>').CommandLine"`；
  5s 超时 + 超时销毁；空输出/非零退出 ⇒ None。pid 为 Long 无注入面。
- 新件 `gateway/WindowsProcs.scala`：`isWindows`、`powershellCimCommandLine`、
  `netstatListeningRows`（`netstat -ano -p tcp`，5s 超时）、
  `parseNetstatListenerPorts(rows, pid)`、`parseNetstatListenerPids(rows, port)`
  ——纯解析函数（fixture 可测，实机实测样本进 spec）。
- `StaleProcessGuard.portListenerPids` 加 Windows 腿（netstat 解析）。
- `GatewayMain.ensureSingleInstance` 摘 OS 闸（Windows 同享 stale 清理 + foreign 拒启；
  classify 红线不变：验证不过永不杀）。

### 1b. 降级验证链（修「读到 None 才走」的兜底）

`ProcessManager.stopHardened` piece 1 的 `case None`（命令行不可读）改走降级链，
**可读但非 stand 命令行照旧硬拒**（零放宽）：

- 层 A（pid-meta + 启动时间匹配）：`.pid.meta` 存在 ∧ `meta.pid == pid` ∧
  `startedBy ∈ {launcher, restart-script}` ∧ `startInstant` 与 `meta.startedAt`
  差 ≤ 10min（meta 是启动时写；pid 复用则 startInstant ≫ startedAt ⇒ 拒）。
  ⇒ launcher 从不启动 dev host ⇒ dev-host 闸解析成立，8080 默认端口不误伤。
- 层 B（端口同源核验）：`pidListeningPorts(pid)` 含 CLI 自解析网关端口
  （--port > GATEWAY_PORT > 8080，与 GatewayClient.readPort 同源）∧ 目标**不**监听
  保护端口（8080/8097）——保护端口上命令行不可读 = 无法排除 dev host = 拒。
- 层 A 需 meta.startedAt 与 startInstant 双双在场；任一层通过 ⇒ 进入 stopVerified
  （声明行注明降级层）；全败 ⇒ 维持 Refused（附降级链明细）。
- `meta=sbt` 永不进降级通过（sbt 属主面走 --tree/--force 既有路；--tree 需可读命令
  行，树成员不可读照旧整树拒）。
- StopDeps 增注入面：`metaRead`、`startInstantMs`、`gatewayPortOverride`（R2-8 同款
  纪律：spec 全 stub，零真信号）。

### 1c. 验证

- spec：WindowsProcs 纯解析（真实 netstat 样本）+ ProcessManagerSpec 降级链六例 +
  既有 21 例零回归。
- Mac 隔离根实流：`--home tmp --port 18108` start→status→stop→stop(幂等)→restart。
- Windows 真机 A/B（作者机，只动临时目录，不碰 9112/8080）：
  旧 jar（作者安装件）+ 隔离 STAND_HOME/port ⇒ 复现作者拒停原文；
  新 jar 同位 ⇒ stop 干净落地；终态核对作者实例无损（9112 存活、8080 仍听）。

## 2. 不做 / 开放

- 不动生产中继/门户（跨仓+生产动作，候批）。
- 不改就绪行一行契约（portal URL 面属作者裁定产品形态）。
- 不为本仓新造「主机」文案（文案闸）。
