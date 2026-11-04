# 三层数据根 —— `PathUtil.dataRoot` 引用盘点（490 处逐行分级）

- 范围：`src/main`（生产面）全部 `.scala`。测试面（`src/test`，713 处）只做统计，不逐行入表（见 §1.3）。
- 配套契约：同目录 `design-api.md`（类型/方法名唯一权威）。本文件的「层」与「需改」结论都以该契约的
  分层定义为准。
- 只读：本腿未改任何 `src/main` 生产逻辑、未跑 sbt、未起实例、未触碰生产根 `~/.stand`。

---

## 0. 结论摘要（先行）

1. **490 处引用、198 个文件**（不含 64 处纯注释/文档字符串时为 426 处代码引用）。派单给的「约 139 处」
   与实测不符 —— 见 §1.2，本表按实测 490 处全覆盖。
2. **必须改的只有 3 组、66 处**：机器层文件（17 处：.pid/.pid.meta/auth.json/daemons*/生命周期 logs/bin）、
   实例身份与端口判定（17 处：`InstanceScope`/`proveHome`/`otherHome` 文案 + 根本身的解析回落）、
   相对路径基准（32 处：`os.Path(x, dataRoot)`）；另有 11 处「需核」（4 处进程握手 intent 路径 +
   7 处对象级 `val` 冻结面）。
3. **其余 ~430 处数据面（agents/teams/flows/sessions/projects/secrets/…）零改动**：它们都写成
   `PathUtil.dataRoot / "<面>"`，只要 `dataRoot` 的语义换成「有效根」，账号层自动成立；
   而平铺根下 `dataRoot == machineRoot`，逐字节等于今日。
4. **「无指针 ⇒ 未登录层」必须加前提**：只有当机器层带 `layout.json` 标记时才判未登录层；没有标记的
   老平铺根一律判 `flat`（= 今日行为）。这条不加，490 处引用与既有 713 处测试引用会集体崩 ——
   见 §5（硬兼容裁定）。
5. **未登录层在既有 490 处引用里没有独立锚点**（0 处）：它是同一批路径在「无指针 + 有标记」模式下的
   解析结果，其全部实现面都是新增代码（`RootLadder` + 收窄的 HTTP 面 + 铸造守卫）。见 §6。
6. **`accountSub` 不能直接当目录名**：既有线上 `accountSub` 形态是 `logto|user-42`（仓内既有断言），
   `|` 在 Windows 文件名非法、`/` 是路径分隔符 ⇒ 必须经 `AccountRoot.dirNameOf` 映射。见 §7 R1。

---

## 1. 盘点方法与口径

### 1.1 命令

```bash
# 主盘点（本文件附录 A = 该输出的逐行分级）
grep -rn "PathUtil.dataRoot" --include=*.scala src/main | sort
# 测试面（仅统计）
grep -rn "PathUtil.dataRoot" --include=*.scala src/test | wc -l      # 713
# 非 Scala 面（shell / ps1 / logback）
grep -rn "STAND_HOME" --include=*.sh --include=*.ps1 \
     ops release packaging scripts | wc -l                          # 121（14 个文件）
```

`grep` 模式同时命中裸写 `PathUtil.dataRoot` 与全限定 `nebflow.shared.PathUtil.dataRoot`（子串匹配），
两类都算一处引用。

### 1.2 与派单数字的偏差（必须校准）

| 口径 | 数量 |
|---|---|
| 派单（作者批注「约 139 处」） | ~139 |
| 实测 `src/main` 全部引用 | **490** |
| 实测 `src/main` 文件数 | 198 |
| 其中纯注释 / KDoc / 文档字符串行 | 64 |
| 其中代码引用 | 426 |
| 实测 `src/test` 引用（`setDataRoot` 换根主战场） | 713 |
| 实测全部（main + test） | 1203 |

偏差原因（判定，非猜测）：139 与本仓任何可复现的切片都对不上 —— 既不是文件数（198）、也不是
非注释行数（426）、也不是 main+`shared` 子集（512）。合理推断是**某次较早提交/较窄子集**的读数。
处置：**以实测 490 为准**，全部入表；后续实现腿的自查脚本建议直接跑 §1.1 的命令并与附录 A 的行数对账
（`grep ... | wc -l` 应等于附录表格行数），对不上即说明有人新增了引用点。

### 1.3 为什么测试面不入逐行表

测试面 713 处是本批（乃至任何「换根」批）的**不回归主体**，但它们全部经由
`PathUtil.setDataRoot(临时根)` + 事后还原这一种模式使用（典型：`val original = PathUtil.dataRoot`
→ 建临时根 → `setDataRoot(临时根)` → 断言 → `setDataRoot(original)`）。契约 §1 已把
`setDataRoot(p)` 定义为「机器层与有效根同钉一个值」的平铺语义，故 713 处**零改动、零行为变化**。
若某实现腿把 `setDataRoot` 改成「钉机器层 + 走阶梯」，测试会立刻大面积变红 —— 那是最快的自检信号，
已写进契约 §1 与验收断言 A1。

---

## 2. 分层的操作定义（判定顺序，自上而下先命中先定）

| 层 | 操作定义 |
|---|---|
| **机器层** | 该路径在「账号根不存在 / 不可读 / 未登录」时也必须可读写；或它描述的是**本机**资源（进程、端口、守护进程、安装物、破玻璃令牌）。 |
| **账号层** | 该路径承载某一账号的内容（会话、记忆、密钥、项目、模型、设备身份、内容日志）。 |
| **未登录层** | 只允许在「无指针 + 有 layout 标记」时存在的最小落盘（登录页会话、只读配置）。 |
| **跨层** | 该处**不是**落盘根，而是（a）实例身份/端口的判定输入、（b）相对路径的解析基准、（c）提示词/文案渲染值、（d）根本身的解析面。跨层条目必须单独判「用 machineRoot 还是有效根」，不能跟着 `dataRoot` 跑。 |

判定要点：**「同一行代码在不同档下落到不同根」不算跨层**（那是账号层条目的正常表现）。
只有「这一行的语义取决于根是哪一个」才算跨层。

---

## 3. 分布

### 3.1 按层

| 层 / 组 | 处数 | 需改 | 说明 |
|---|---|---|---|
| 机器层 | 17 | **是**（17/17） | §4.A 逐条 |
| 跨层 · 实例身份 / 端口 / 根本身 | 17 | **是**（17/17） | §4.B 逐条（16 处身份面 + `Main.scala:138` 的根解析回落） |
| 跨层 · 相对路径基准 | 32 | **是**（32/32） | §4.C 逐条 |
| 跨层 · 进程握手（hot restart intent） | 4 | 核 | §4.D（建议机器层，非阻断） |
| 跨层 · 文案 / 渲染值面 | 20 | 否 | `dataRootRenderValue` 族（`SkillService`×3、`LoadTool`×3、`PopTool`×2、`TaskListTool`×3、`TaskTool`×2、`ProjectCreateService`×2、`RgHelper`×1、`MemoryPaths`×4）—— 随有效根渲染，见 §7 R4 |
| 账号层 · 对象级 `val` 冻结 | 7 | 核 | §4.E（+ `GatewayClient.scala:140` 已计入机器层） |
| 账号层 · 内容数据面 | 393 | **否** | 自动跟随有效根 |
| 未登录层 | 0 | — | §6 |

校验：17 + 17 + 32 + 4 + 20 + 7 + 393 + 0 = **490**；必改 66、需核 11、零改动 413。

### 3.2 按目录（处数）

| 目录 | 处数 | 目录 | 处数 |
|---|---|---|---|
| `core/` | 259 | `service/` | 14 |
| `gateway/` | 97 | `perception/` | 7 |
| `cli/` | 27 | `dropbox/` | 5 |
| `neblink/` | 27 | `Main.scala` | 5 |
| `shared/` | 22 | `llm/` | 4 |
| `agent/` | 21 | `social/` | 2 |

---

## 4. 必改清单（A–C 必改 66 处 / D–E 需核 11 处 / F 保留 413 处）

### A. 机器层（`PathUtil.dataRoot` → `PathUtil.machineRoot`）—— 17 处

| file:line | 面 | 不改的后果 |
|---|---|---|
| `cli/ProcessManager.scala:10` | `.pid` | `ops/bin/stand-cli-lib.sh:265/277/354` 读的是 `$STAND_HOME/.pid`（机器根）⇒ 账号模式下 `stand stop` 归属证明断链、pid 记账分叉 |
| `cli/PidMeta.scala:55` | `.pid.meta` | 同上（启动形态记账） |
| `cli/GatewayClient.scala:140` | `auth.json`（对象级 `private val`） | 与下一行必须同根，否则 CLI 拿不到网关令牌（且冻结在对象初始化） |
| `gateway/auth.scala:18` | `auth.json` | 裁定：机器层「破玻璃」令牌；落账号层则换账号即掉本地 CLI 授权 |
| `core/daemon/DaemonStore.scala:9` | `daemons.json` | 守护进程是本机进程；账号模式下会「看不见自己起的 daemon」 |
| `core/daemon/DaemonService.scala:32` | `daemon-pids.json` | 同上；残留 pid 无人回收 |
| `core/daemon/DaemonPanelStore.scala:33` | daemon 面板根（构造默认值） | 同上 |
| `core/secrets/SecretsStore.scala:73` | `daemonsPath` 默认值 | 同上（与 `daemons.json` 必须同根） |
| `gateway/PerceptionDaemonDock.scala:162` | dock 的 `home` 参数 | dock 出来的感知守护进程会绑到账号根 |
| `gateway/PerceptionDaemonDock.scala:178` | `prepare` 的 `home` 参数 | 同上 |
| `core/RestartHelper.scala:52` | `logs/restart.log` | 重启诊断必须在账号根坏掉时也可写 |
| `core/RestartHelper.scala:65` | `logs/restart.log` | 同上 |
| `core/RestartHelper.scala:107` | `logs` 目录 | 同上 |
| `core/hotrestart/HotRestart.scala:913` | 后继进程 `logs` | 热重启窗诊断 = 生命周期日志 |
| `core/AutoStartService.scala:190` | `logs/autostart.log`（macOS） | 开机自启失败必须在未登录 / 账号根坏掉时可诊断 |
| `core/AutoStartService.scala:228` | `logs/autostart.log`（Windows） | 同上 |
| `core/tools/RgHelper.scala:128` | 提示文案里的 `…/bin` | `bin/` 是机器层（裁定）；文案指错路径会教坏 LLM 的自救动作 |

> 相邻说明（**不改**，列在此处只为消除误改）：`shared/config.scala:755` 的
> `def NebflowHome = PathUtil.dataRoot` 是**有效根**别名（`Config.DefaultConfigPath`、
> `WebSocketRoutes:2056/2124` 的 `uploads/` 消费），必须继续跟随有效根；
> `shared/InstanceScope.scala:57` 归 B 组（身份面，用机器层根）。

### B. 实例身份 / 端口判定（必须 `machineRoot`）—— 17 处

**头注（这条最容易做错，也是本盘点最重要的一条发现）**：`InstanceScope.callerOf` 判
`overridden = root 不是默认根`，而 `overridden = true` 时端口解析**拒绝回落到品牌缺省口 6138**
（`shared/InstanceScope.scala` 文件头注原文：「R 被覆盖…配置也没有 ⇒ 拒绝并要求显式 --port」）。
账号根 `~/.stand/accounts/<sub>` 恒 ≠ 默认根 ⇒ **若这些点沿用 `dataRoot`，所有账号登录后的实例都会
拒绝在 6138 启动**。端口是机器级属性（裁定：6138 全局唯一），故实例身份面一律 `machineRoot`，
且比较前用 `RootLadder.machineRootOf` 把 `--home` 的两种拼法（机器根 / 账号根）归一。

| file:line | 面 |
|---|---|
| `shared/InstanceScope.scala:57` | `liveCaller` 的 `root`（端口解析 + `overridden` 判据） |
| `gateway/gatewayConfig.scala:96` | `PortInputs.root` |
| `cli/ProcessManager.scala:65` | `InstanceScope.proveHome(…, root, …)` |
| `cli/ProcessManager.scala:155` | `InstanceScope.homeRefusal(…)` |
| `cli/ProcessManager.scala:295` | 「隔离根必须显式端口」文案 |
| `cli/RestartCommand.scala:133` | `SingleInstanceGuard.occupantHomeOf(port, root)` |
| `cli/RestartCommand.scala:139` | 「不同数据根 = 不同实例」文案 |
| `cli/SingleInstanceGuard.scala:128` | `probeOnce` 的 `ownRoot` |
| `cli/SingleInstanceGuard.scala:254` | `otherInstanceRoot(cmd, ownRoot)` |
| `gateway/GatewayMain.scala:69` | `StaleProcessGuard.otherInstanceRoot` |
| `gateway/GatewayMain.scala:78` | 「foreign brand home」拒启文案 |
| `gateway/GatewayMain.scala:98` | `InstanceScope.proveHome` 归属门 |
| `gateway/GatewayMain.scala:109` | 「NEVER killed」拒启文案 |
| `Main.scala:115` | daemon child 拒启文案 |
| `Main.scala:138` | **根解析回落**（`case None => PathUtil.dataRoot`）—— `machineRoot` 的落点本身，改法是搬到 `PathUtil.machineRoot` 的解析链（契约 §1） |
| `Main.scala:336` | `preemptOtherHome(port, root)` |
| `Main.scala:345` | 拒启文案 |

### C. 相对路径基准（`os.Path(x, PathUtil.dataRoot)` —— 不能跟着有效根跑）—— 32 处

语义：这些点把 `dataRoot` 当**相对路径的 cwd 基准**（第二参），不是落盘根。基准从
`~/.stand` 变到 `~/.stand/accounts/<sub>` 会让历史配置里的**相对 workspace** 解析漂移
（指到账号目录下，文件找不到或指错）。契约 §9.4 规定改为 `os.pwd`（默认 home 下与今日等价）
或显式传项目根；**不改用 machineRoot**（那会引入另一种默认）。判定式（可复核，恰 32 行）：

```bash
grep -rn 'os\.Path(' --include=*.scala src/main | grep 'PathUtil\.dataRoot *[,)]'
```

> 坑：`grep 'os\.Path([^)]*PathUtil\.dataRoot'` 会漏掉内层带括号的调用
> （`os.Path(ProjectStore.brainWorkspace(pd), …)`），本腿首轮就漏了 4 处，已纠正 ——
> 实现腿请用上面这条判定式。

| file:line | 用途 |
|---|---|
| `core/delegate/DelegateFlowMapLifecycle.scala:64` | flowmap 目录基准 |
| `core/project/BootDispatcherWake.scala:234` | `.nebflow` 基准 |
| `core/project/ChainsReadFace.scala:454` | flowmap 基准 |
| `core/project/DelegateFlowHosts.scala:65` | flowmap 目录基准 |
| `core/project/DelegateFlowHosts.scala:87` | `AGENTS.md` 基准 |
| `core/project/DelegateFlowHosts.scala:231` | flowmap 根基准 |
| `core/project/DocIndexConsumer.scala:556` | 事件日志基准 |
| `core/project/DocIndexConsumer.scala:559` | 归档目录基准 |
| `core/project/DocIndexConsumer.scala:562` | 报告基准 |
| `core/project/DocIndexConsumer.scala:724` | `Spec` 基准 |
| `core/project/FileChangeReport.scala:118` | 变更报告基准 |
| `core/project/FileChangeReport.scala:442` | 同上 |
| `core/project/FlowMapEventLog.scala:980` | 事件日志基准 |
| `core/project/FlowMapStore.scala:1149` | 结果文件基准 |
| `core/project/FlowMapStore.scala:2050` | `.nebflow` 根基准 |
| `core/project/NodeReportRegistry.scala:147` | 期刊基准 |
| `core/project/ProjectCreateService.scala:565` | `ensureScaffold` 基准 |
| `core/project/ProjectCreateService.scala:596` | workspace 规范化基准 |
| `core/project/ProjectCreateService.scala:649` | workspace 名取末段基准 |
| `core/project/ProjectMemory.scala:51` | 项目记忆基准 |
| `core/project/ProjectStore.scala:94` | `docs` 库基准 |
| `core/project/ProjectStore.scala:231` | `migrateAgentMd` 基准 |
| `core/project/ProjectStore.scala:506` | workspace 基准 |
| `core/project/ScopeHandover.scala:38` | flowmap 基准 |
| `core/project/TaskBoardHistory.scala:96` | `.nebflow` 基准 |
| `core/project/TaskBoardStore.scala:113` | `task-board.json` 基准 |
| `core/tools/ProjectTool.scala:261` | workspace 基准 |
| `core/wiki/WikiService.scala:511` | wiki 基准 |
| `core/wiki/WikiService.scala:882` | wiki 基准 |
| `gateway/ProjectsRoutes.scala:461` | `AGENTS.md` 基准 |
| `gateway/ProjectsRoutes.scala:480` | 同上 |
| `neblink/EgressWhitelist.scala:69` | 白名单路径基准 |

### D. 进程握手 / hot restart intent（需核，建议机器层）—— 4 处

`core/hotrestart/HotRestart.scala:84`（`SuccessorGate.intentPath`）、`:559`（后继 `home`）、
`:569`（后继 intent home）、`:900`（intent 路径文案）。语义 = **同机两个进程的活动交接**，
建议落到机器层（账号根坏掉时热重启仍要能收尾）。但改动触及重启链，收益小于风险 ⇒
本批标「核」，由 V1 实现腿在动 `Main` 启动序时顺带判定（§7 R5）。

### E. 对象级 `val` 冻结面（需核）—— 7 处（另有 1 处在 A 组）

| file:line | 形态 |
|---|---|
| `gateway/TtsService.scala:14` | `private val configPath` |
| `gateway/WebSocketRoutes.scala:1641` | `private val inputHistoryPath` |
| `dropbox/DropboxService.scala:161` | `private val messagesPath` |
| `dropbox/DropboxService.scala:206` | `private val transfersPath` |
| `service/NebflowBackup.scala:24` | `private val home` |
| `shared/RulesStore.scala:24` | `private val baseDir` |
| `shared/MemoryStore.scala:80` | `@volatile private var boundRoot`（另带 `bind` 换根面） |

（第 8 处 = `cli/GatewayClient.scala:140`，已在 A 组按机器层计。）
判据：这些值在**对象首次被触碰**时固化。生产面 `Main.run` 在 dispatch 之前就 `setDataRoot`/`setMachineRoot`
（`Main.scala:153`），故「根早于对象初始化」成立；登录成功后一律**重启进程**（禁热切）⇒ 不破。
需核的是测试面：某 spec 若先触达这些对象再换根，会读到旧根 —— 这是**今日已存在**的脆弱性，本批不加剧。

### F. 保留不动（举要；完整的 393 条 + 20 处文案面见附录 A）

`agents/`（AgentLibrary、BuiltinAgents、EntityLoader、ModelChainMigration…）、`teams/`、`flows/`、
`tools/`（ToolLoader）、`plugins/`（PluginRegistry、PluginSource*、PluginDispatchPolicy…）、`skills/`、
`projects/`（ProjectStore、ProjectCreateService…）、`sessions/`（SessionStore、MailQueueStore、
MsgTokenRegistry、BgTaskLedger…）、`tasks.json` / `tasks-v2.json` / `history`、
`secrets/`（SecretsStore、SecretInjection、ConfigService、PerceptionService…）、
memory（`User.md` / `Soul.md` / `agents/*/memory.md` / `memory-backups` / `memory-archive`）、
`dropbox/`、`uploads/`、`tool-results/`、`cache/`、`index/`、`imagegen/`、`perception/`、`delegates/`、
`chains/`、`merge-*`、`usage-log.jsonl`、`pat.json`、`canvas_tabs.json`、`input_history*`、
`onboarding.json`、`gateway-sessions.json`、`model(s|-presets).json`、`neblink/*`（device.json 凭据、
devices.json、config.json、binding-state.json、captures、device-profiles.json、remote-agent-sessions）、
`auth` 之外的配置文件族（`configJsonReadPath/WritePath(PathUtil.dataRoot)`，共 30 余处）。

---

## 5. 硬兼容裁定（本批关键，V7 不回归）

### 5.1 裁定原文（本腿写定，实现腿与验收腿共同遵守）

> **无 `layout.json` 的平铺根，行为逐字节不变。**
> 具体三条：
> 1. `RootLadder.resolve(machineRoot)` 在「无 `layout.json`」时**恒返回** `Flat(machineRoot, machineRoot)`，
>    即 `PathUtil.dataRoot == PathUtil.machineRoot`；
> 2. `PathUtil.dataRoot` 在平铺档下与今日实现**同值**（今日解析链逐字保留）；
> 3. `deviceIdScope` 在平铺档下与今日**逐字同值**（`default` / `isolatedHomeScope`，判定顺序见契约 §7 第 3/4 档）。

### 5.2 与「无指针 ⇒ 未登录层」的关系（必须显式写出来，否则 490 处引用集体崩）

裁定的朴素读法是「无指针 ⇒ 未登录层」。**直接照做会把老用户的数据根整体换到
`machineRoot/unauthenticated/`**：所有 ~430 处数据面瞬间指向空目录，用户看到「数据全没了」；
同时既有 713 处测试换根（临时根没有 layout 标记）会被判进未登录层，套件集体崩。

故本批把两个条件**串联**（AND，不是 OR）：

```
未登录层 ⟺ 无 active-account 指针  ∧  机器层带 layout.json 标记
```

- `layout.json` 是**「这台机器已经进入账号布局」的显式声明**：它只由
  `RootLayout.migrate`（首次登录 / 显式迁移动作）写入，**绝不由 boot 自动写**。
- 因此：装机到首次登录之间，一旦迁移腿跑过（或全新安装的首启写了标记）就走未登录层；
  而**任何升级上来的老平铺根，在第一次登录之前仍是 flat**，字节不变。
- 指针损坏（能读文件但解不出 accountSub）**降级 flat**，不降级未登录层（`design-api.md` §2 第 3 档）。
  「坏 JSON ⇒ 用户被踢回登录页且数据根消失」是把单点坏档放大成数据不可见事故。
- 反过来：有 layout 标记 + 指针指向的账号目录**不存在**（被手工删）⇒ 也降级 flat + WARN，
  同样不落未登录层（数据在平铺处，别假装它不存在）。

### 5.3 本腿判定：**不另立更优口径**

派单允许「若判定另有更优口径，写进『口径分歧』段」。本腿的结论是：**兼容性要件必须原样保留**，
故 §5.2 的 AND 口径不是替代方案，而是把裁定落成可验收形式的**唯一可行解**（已写进契约 §2 阶梯）。
另有 4 条**加严/实现面**分歧（不是替换裁定）列在 §7.2 与 `design-api.md` §12，需裁定方点头。

---

## 6. 未登录层：既有引用零独立锚点（0 / 490）

- 既有 490 处引用里**没有一处**只会存在于未登录层：它们要么是机器层（§4.A），要么属于账号内容
  （§4.E）。未登录层是**模式的解析结果**，不是一批新路径。
- 该层的实现面**全部是新增代码**：`RootLadder` 第 2 档、`RootLayer.Unauthenticated`、
  HTTP 面收窄（属 B2 裁定范围）、`NeblinkModel.loadOrCreate` 的铸造守卫、`/api/health.rootLayer` 字段、
  日志标记 `UNAUTHENTICATED-LAYER`（契约 §6）。
- 该层唯一「既存但不该被写」的面：`device.json`。既有 `loadOrCreate` 会在首次启动时铸造并落盘
  （`neblink/NeblinkModel.scala:122` 的 `devicePath`），未登录层必须**拒绝**（契约 §6.2 的 fail-closed 守卫，
  A5 断言用 `find <machineRoot> -name device.json` 为空来验）。
- 该层所需的落盘：`gateway-sessions.json`（登录页会话）与 `stand.json`（只读配置）—— 两者在附录里
  记作**账号层**（同一批引用），它们的「未登录层形态」由 `dataRoot` 自动给出。

---

## 7. 风险与口径分歧

### 7.1 风险

| # | 风险 | 判据 / 处置 |
|---|---|---|
| R1 | `accountSub` 直当目录名：线上形态 `logto\|user-42`（`GatewaySessionStoreSpec.scala:30`、`ConsoleTicketStoreSpec.scala:26`），`\|` 在 Windows 文件名非法、`/` 是分隔符（穿越） | 契约 §3.1 `AccountRoot.dirNameOf`：合法子集逐字、其余 `x-` + sha256 前 24 |
| R2 | 端口面沿用 `dataRoot` ⇒ 账号模式下 6138 起不来（`overridden` 拒回落） | §4.B：一律 `machineRoot`；A8 断言 |
| R3 | 对象级 `val` 冻结面 8 处（§4.D） | 生产序已成立（根先定案）；测试面为既存脆弱性，不加剧 |
| R4 | `dataRootRenderValue` 在账号模式下不再输出 `~/.stand` 而输出绝对路径 ⇒ **提示词/工具描述字节变化** | 契约 §1 已声明为期望行为；交 B5 提示词腿复核（是否要渲染 `~/.stand/accounts/<sub>` 的缩写形态） |
| R5 | 热重启后继的 `SuccessorGate` intent 文件（`HotRestart.scala:84/559/569/684/900`）挂在哪个根 | 建议机器层（进程握手 = 本机事务）；本批**不强制改**（改了收益小、风险在重启链），标「核」 |
| R6 | autostart 已落盘的 plist / schtasks **不会自动带 `--home`** | 契约 §8.2 尾注：升级后首登时幂等重写一次；V3 实现腿判定 |
| R7 | `--home` 兼两种输入（机器根 / 账号根）靠 `profile.json` 存在判别 | 契约 §2 第 0 档 + `RootLadder.machineRootOf`；A1/A2 覆盖 |

### 7.2 口径分歧（加严项，非替换；逐条待裁定方点头）

| # | 内容 | 默认取向 |
|---|---|---|
| P1 | `deviceIdScope` 在**非默认机器根**（canary/`--home`）上追加 `\|iso\|<hash>`：否则 canary 与生产同账号共用 `acct\|<sub>`，服务端 `one-live-session-per-(device,network)` 会**踢掉作者主客户端**（`NeblinkModel.scala:190-207` 记录的两起事故） | 加严（保留隔离） |
| P2 | 「logs/ 归机器层」整目录裁定 ⇒ 含 prompt/工具正文的内容日志跨账号合流 | 分置：生命周期 4 类（stand.log/daemon.out/restart.log/autostart.log）机器层，内容日志账号层（契约 §9.1/§9.2） |
| P3 | 目录名 = `accountSub` 原文（裁定字面） | 不可行（R1），改 `dirNameOf` 映射 |
| P4 | `--home` 语义（机器根 vs 账号根） | 阶梯判别，零新旗标（备选：新增 `--account <sub>`） |

---

## 8. 非 Scala 引用面（不在 490 内，但是同一契约的消费方）

| 面 | 位置 | 与本契约的关系 |
|---|---|---|
| 停机 / 归属证明 | `ops/bin/stand-cli-lib.sh:265/277/354`（读 `$STAND_HOME/.pid`）、`:128-133`（从命令行抓 `--home`） | **决定了 `.pid` 必须在机器根**（§4.A 第 1 行）；shell 侧 `stand_resolve_port` 是 `InstanceScope` 的 bash 镜像 |
| 启动器 home 解析 | `ops/bin/stand-cli-lib.sh:52-55`（`stand: STAND_HOME > ~/.stand`，`nebflow: NEBFLOW_HOME > ~/.nebflow`） | 不读源码即定机器根 ⇒ `machineRoot` 解析链必须与之一致；`121` 处 `STAND_HOME` 分布在 14 个 `ops/`+`release/`+`scripts/` 脚本里 |
| 日志落位 | `src/main/resources/logback.xml:24/29`（`${STAND_HOME:-${NEBFLOW_HOME:-~/.stand}}/logs/…`） | `Main.scala:152` 的 `sys.props("NEBFLOW_HOME")` 改为 `machineRoot` 后，网关日志落机器层；该 prop 全仓仅 logback 读（`Branding.env` 只读 `sys.env`，`branding.scala:200-210`） |
| 迁移工具箱 | `scripts/data-root-migration/`（dryrun/precopy/finalize/verify/rollback） | 是「换品牌 home」的旧工具箱，与本批「平铺 → 账号布局」是两件事；术语对齐但**不复用**其语义（契约 §4 尾注） |
| 其它 | `scripts/`、`tools/` 下 12 处 `.stand/` 字面、`ops/*.ps1`、`release/install*.ps1` | 本批不改；契约落地后由 V3/发布腿复核 |

---

## 附录 A：490 处逐行表

列说明：
- **层** ∈ 机器层 / 账号层 / 未登录层 / 跨层（定义见 §2）。纯注释与文档字符串行按「账号层」计，并在
  **说明**里标 `(注释)`。
- **改** ∈ 是（必须改）/ 否（零改动，自动跟随）/ 核（需核对，见 §4.D / §7 R5）。
- **路径面** = 该行表达式里 `dataRoot` 之后的路径段（或该行用途），过长截断为 `…`。

| # | file:line | 层 | 改 | 路径面 | 说明 |
|---|---|---|---|---|---|
| 1 | `src/main/scala/nebflow/agent/AgentLibrary.scala:202` | 账号层 | 否 | `/folders/folderId/agents/name` | 内容数据面（自动跟随有效根） |
| 2 | `src/main/scala/nebflow/agent/AgentLibrary.scala:299` | 账号层 | 否 | `/agents` | 内容数据面（自动跟随有效根） |
| 3 | `src/main/scala/nebflow/agent/AgentSessionExecution.scala:1328` | 账号层 | 否 | `，与读白名单 nebflowReadExtras 同源——NEBFLOW_HOME/` | (注释/文档字符串) 口径说明，随有效根 |
| 4 | `src/main/scala/nebflow/agent/AgentSessionExecution.scala:714` | 账号层 | 否 | `/general).toString)` | 内容数据面（自动跟随有效根） |
| 5 | `src/main/scala/nebflow/agent/CompactionQueueStore.scala:163` | 账号层 | 否 | `/sessions/sessionId/injection-queues.json` | 内容数据面（自动跟随有效根） |
| 6 | `src/main/scala/nebflow/agent/ContextRefresher.scala:130` | 账号层 | 否 | `/projects/folderName` | 内容数据面（自动跟随有效根） |
| 7 | `src/main/scala/nebflow/agent/ContextRefresher.scala:151` | 账号层 | 否 | `/projects/folderName` | 内容数据面（自动跟随有效根） |
| 8 | `src/main/scala/nebflow/agent/MailboxBootSweep.scala:202` | 账号层 | 否 | `/sessions` | 内容数据面（自动跟随有效根） |
| 9 | `src/main/scala/nebflow/agent/MailboxBootSweep.scala:71` | 账号层 | 否 | `/mailbox-reconcile.json` | 内容数据面（自动跟随有效根） |
| 10 | `src/main/scala/nebflow/agent/MailboxBootSweep.scala:80` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 11 | `src/main/scala/nebflow/agent/PendingAskLedger.scala:48` | 账号层 | 否 | `/pending-asks.json):` | 内容数据面（自动跟随有效根） |
| 12 | `src/main/scala/nebflow/agent/PingpongStateFile.scala:73` | 账号层 | 否 | `/sessions/sessionId/pingpong/state.json` | 内容数据面（自动跟随有效根） |
| 13 | `src/main/scala/nebflow/agent/PromptSections.scala:626` | 账号层 | 否 | `/prompts/sections` | 内容数据面（自动跟随有效根） |
| 14 | `src/main/scala/nebflow/agent/PromptSections.scala:638` | 账号层 | 否 | `/prompts/sections` | 内容数据面（自动跟随有效根） |
| 15 | `src/main/scala/nebflow/agent/PromptSections.scala:791` | 账号层 | 否 | `.toString` | 内容数据面（自动跟随有效根） |
| 16 | `src/main/scala/nebflow/agent/SharedResources.scala:226` | 账号层 | 否 | `，同 FileChangeLedger.unsafe 先例）` | (注释/文档字符串) 口径说明，随有效根 |
| 17 | `src/main/scala/nebflow/agent/SharedResources.scala:57` | 账号层 | 否 | `/scheduled-tasks),` | 内容数据面（自动跟随有效根） |
| 18 | `src/main/scala/nebflow/agent/SharedResources.scala:63` | 账号层 | 否 | `),` | 内容数据面（自动跟随有效根） |
| 19 | `src/main/scala/nebflow/agent/SharedResources.scala:81` | 账号层 | 否 | `/workspace-items),` | 内容数据面（自动跟随有效根） |
| 20 | `src/main/scala/nebflow/agent/SharedResources.scala:82` | 账号层 | 否 | `/subagent-tasks),` | 内容数据面（自动跟随有效根） |
| 21 | `src/main/scala/nebflow/agent/SharedResources.scala:83` | 账号层 | 否 | `/usage-records),` | 内容数据面（自动跟随有效根） |
| 22 | `src/main/scala/nebflow/cli/AgentCommand.scala:79` | 账号层 | 否 | `/agents/name` | 内容数据面（自动跟随有效根） |
| 23 | `src/main/scala/nebflow/cli/CliModel.scala:65` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 24 | `src/main/scala/nebflow/cli/CliRouter.scala:144` | 账号层 | 否 | `))` | 内容数据面（自动跟随有效根） |
| 25 | `src/main/scala/nebflow/cli/CliRouter.scala:147` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 26 | `src/main/scala/nebflow/cli/CliRouter.scala:148` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 27 | `src/main/scala/nebflow/cli/ConfigCommand.scala:227` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 28 | `src/main/scala/nebflow/cli/DetachedApply.scala:694` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 29 | `src/main/scala/nebflow/cli/GatewayClient.scala:140` | 机器层 | 是 | `/auth.json` | `auth.json`（机器层破玻璃令牌；对象级 `private val`） |
| 30 | `src/main/scala/nebflow/cli/GatewayClient.scala:179` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 31 | `src/main/scala/nebflow/cli/LogsCommand.scala:14` | 账号层 | 否 | `/logs`，与 logback.xml` | (注释/文档字符串) 口径说明，随有效根 |
| 32 | `src/main/scala/nebflow/cli/McpCommand.scala:29` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 33 | `src/main/scala/nebflow/cli/ModelCommand.scala:151` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 34 | `src/main/scala/nebflow/cli/PidMeta.scala:55` | 机器层 | 是 | `/.pid.meta` | `.pid.meta`（机器层：启动形态记账） |
| 35 | `src/main/scala/nebflow/cli/ProcessManager.scala:10` | 机器层 | 是 | `/.pid` | `.pid`（机器层：shell 停机面读 `$STAND_HOME/.pid`） |
| 36 | `src/main/scala/nebflow/cli/ProcessManager.scala:155` | 跨层 | 是 | `.toString, failClosedWhenUnknown = true)` | `homeRefusal` 归属拒停 → machineRoot |
| 37 | `src/main/scala/nebflow/cli/ProcessManager.scala:295` | 跨层 | 是 | `} — an isolated root must declare its port; the brand default +` | 「隔离根必须显式端口」拒启文案 → machineRoot |
| 38 | `src/main/scala/nebflow/cli/ProcessManager.scala:65` | 跨层 | 是 | `.toString, ProcessManager.readPid())` | `proveHome(…, root, …)` 归属证明 → machineRoot |
| 39 | `src/main/scala/nebflow/cli/RestartCommand.scala:133` | 跨层 | 是 | `.toString))` | `occupantHomeOf(port, root)` → machineRoot |
| 40 | `src/main/scala/nebflow/cli/RestartCommand.scala:139` | 跨层 | 是 | `}. Different data roots are different instances: +` | 「不同数据根=不同实例」文案 → machineRoot |
| 41 | `src/main/scala/nebflow/cli/RestartCommand.scala:92` | 账号层 | 否 | `, cfg.port.value, GatewayConfig.noBrowser)` | 内容数据面（自动跟随有效根） |
| 42 | `src/main/scala/nebflow/cli/SecretsCommand.scala:122` | 账号层 | 否 | `/secrets).toString.asJson,` | 内容数据面（自动跟随有效根） |
| 43 | `src/main/scala/nebflow/cli/SecretsCommand.scala:137` | 账号层 | 否 | `}/secrets` | 内容数据面（自动跟随有效根） |
| 44 | `src/main/scala/nebflow/cli/SecretsCommand.scala:46` | 账号层 | 否 | `}/secrets` | 内容数据面（自动跟随有效根） |
| 45 | `src/main/scala/nebflow/cli/SecretsCommand.scala:89` | 账号层 | 否 | `/secrets)` | 内容数据面（自动跟随有效根） |
| 46 | `src/main/scala/nebflow/cli/SingleInstanceGuard.scala:128` | 跨层 | 是 | `.toString)` | `probeOnce` 的 ownRoot → machineRoot |
| 47 | `src/main/scala/nebflow/cli/SingleInstanceGuard.scala:254` | 跨层 | 是 | `.toString)` | `otherInstanceRoot(cmd, ownRoot)` → machineRoot |
| 48 | `src/main/scala/nebflow/cli/uiStore.scala:228` | 账号层 | 否 | `/input_history.json` | 内容数据面（自动跟随有效根） |
| 49 | `src/main/scala/nebflow/core/AgentRuntimePort.scala:74` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 50 | `src/main/scala/nebflow/core/AutoStartService.scala:190` | 机器层 | 是 | `/logs` | `logs/autostart.log`（机器层生命周期日志） |
| 51 | `src/main/scala/nebflow/core/AutoStartService.scala:228` | 机器层 | 是 | `/logs` | `logs/autostart.log`（机器层生命周期日志） |
| 52 | `src/main/scala/nebflow/core/compact/CompactUtils.scala:244` | 账号层 | 否 | `/tool-results/sessionId` | 内容数据面（自动跟随有效根） |
| 53 | `src/main/scala/nebflow/core/compact/CompactUtils.scala:270` | 账号层 | 否 | `/tool-results/sessionId/s$toolUseId.txt}.replace('\\', '/')` | 内容数据面（自动跟随有效根） |
| 54 | `src/main/scala/nebflow/core/compact/HistoryArchiver.scala:55` | 账号层 | 否 | `/sessions` — see GatewayMain wiring).` | (注释/文档字符串) 口径说明，随有效根 |
| 55 | `src/main/scala/nebflow/core/compact/PingpongConfig.scala:27` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 56 | `src/main/scala/nebflow/core/compact/PingpongConfig.scala:47` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 57 | `src/main/scala/nebflow/core/daemon/DaemonPanelStore.scala:33` | 机器层 | 是 | `):` | daemon 面板根（机器层，构造默认值） |
| 58 | `src/main/scala/nebflow/core/daemon/DaemonService.scala:32` | 机器层 | 是 | `/daemon-pids.json` | `daemon-pids.json`（机器层） |
| 59 | `src/main/scala/nebflow/core/daemon/DaemonStore.scala:9` | 机器层 | 是 | `/daemons.json):` | `daemons.json`（机器层：本机守护进程） |
| 60 | `src/main/scala/nebflow/core/delegate/BaseDriftWatch.scala:31` | 账号层 | 否 | `/base-drift.json` | 内容数据面（自动跟随有效根） |
| 61 | `src/main/scala/nebflow/core/delegate/ChainStore.scala:41` | 账号层 | 否 | `/chains` | 内容数据面（自动跟随有效根） |
| 62 | `src/main/scala/nebflow/core/delegate/DelegateDeliveryReceipts.scala:116` | 账号层 | 否 | `/delegates` | 内容数据面（自动跟随有效根） |
| 63 | `src/main/scala/nebflow/core/delegate/DelegateFlowMapLifecycle.scala:64` | 跨层 | 是 | `)/.nebflow/FlowMapStore.FlowMapsDirName/delegateId` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 64 | `src/main/scala/nebflow/core/delegate/DelegateRegistry.scala:218` | 账号层 | 否 | `/delegates` | 内容数据面（自动跟随有效根） |
| 65 | `src/main/scala/nebflow/core/delegate/DeviceDelegate.scala:187` | 账号层 | 否 | `}/neblink/devices.json) — +` | 内容数据面（自动跟随有效根） |
| 66 | `src/main/scala/nebflow/core/delegate/DeviceDelegate.scala:641` | 账号层 | 否 | `/device.json` | 内容数据面（自动跟随有效根） |
| 67 | `src/main/scala/nebflow/core/delegate/DeviceResultOutbox.scala:91` | 账号层 | 否 | `/device-outbox` | 内容数据面（自动跟随有效根） |
| 68 | `src/main/scala/nebflow/core/delegate/DualMode.scala:45` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 69 | `src/main/scala/nebflow/core/delegate/DualMode.scala:57` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 70 | `src/main/scala/nebflow/core/delegate/MergeArtifactCare.scala:212` | 账号层 | 否 | `/merge-quarantine` | 内容数据面（自动跟随有效根） |
| 71 | `src/main/scala/nebflow/core/delegate/MergeQueue.scala:173` | 账号层 | 否 | `/merge-ledger.json` | 内容数据面（自动跟随有效根） |
| 72 | `src/main/scala/nebflow/core/delegate/MergeQueue.scala:228` | 账号层 | 否 | `/s${ledgerPath.last}.tmp` | 内容数据面（自动跟随有效根） |
| 73 | `src/main/scala/nebflow/core/delegate/MergeQueueChannel.scala:64` | 账号层 | 否 | `/merge-channel` | 内容数据面（自动跟随有效根） |
| 74 | `src/main/scala/nebflow/core/device/DeviceHmac.scala:158` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 75 | `src/main/scala/nebflow/core/device/DeviceRegistry.scala:43` | 账号层 | 否 | `is redirectable (setDataRoot); a val would` | (注释/文档字符串) 口径说明，随有效根 |
| 76 | `src/main/scala/nebflow/core/device/DeviceRegistry.scala:45` | 账号层 | 否 | `/neblink/devices.json` | 内容数据面（自动跟随有效根） |
| 77 | `src/main/scala/nebflow/core/device/FleetConfig.scala:47` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 78 | `src/main/scala/nebflow/core/entity/AgentIdentityStore.scala:60` | 账号层 | 否 | `/agents` | 内容数据面（自动跟随有效根） |
| 79 | `src/main/scala/nebflow/core/entity/BuiltinAgents.scala:101` | 账号层 | 否 | `/agents/nebflow.actor.RootAgentIdentity.Name/agent.json` | 内容数据面（自动跟随有效根） |
| 80 | `src/main/scala/nebflow/core/entity/BuiltinAgents.scala:119` | 账号层 | 否 | `/agents/name/agent.json` | 内容数据面（自动跟随有效根） |
| 81 | `src/main/scala/nebflow/core/entity/EntityLoader.scala:18` | 账号层 | 否 | `/teams` | 内容数据面（自动跟随有效根） |
| 82 | `src/main/scala/nebflow/core/entity/EntityLoader.scala:19` | 账号层 | 否 | `/flows` | 内容数据面（自动跟随有效根） |
| 83 | `src/main/scala/nebflow/core/entity/EntityLoader.scala:20` | 账号层 | 否 | `/agents` | 内容数据面（自动跟随有效根） |
| 84 | `src/main/scala/nebflow/core/executor/ExecutorRegistry.scala:158` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 85 | `src/main/scala/nebflow/core/executor/ExternalSpawnConfig.scala:38` | 账号层 | 否 | `))` | 内容数据面（自动跟随有效根） |
| 86 | `src/main/scala/nebflow/core/flow/FlowMailStore.scala:46` | 账号层 | 否 | `/sessions/sessionId/flow-mailbox` | 内容数据面（自动跟随有效根） |
| 87 | `src/main/scala/nebflow/core/flow/FlowTreeActor.scala:593` | 账号层 | 否 | `/flows).toIO.toPath` | 内容数据面（自动跟随有效根） |
| 88 | `src/main/scala/nebflow/core/flow/FlowTreeActor.scala:594` | 账号层 | 否 | `/agents).toIO.toPath` | 内容数据面（自动跟随有效根） |
| 89 | `src/main/scala/nebflow/core/flow/FlowTreeActor.scala:595` | 账号层 | 否 | `/teams).toIO.toPath` | 内容数据面（自动跟随有效根） |
| 90 | `src/main/scala/nebflow/core/flow/FlowTreeRegistry.scala:106` | 账号层 | 否 | `/projects/folderName).toString)` | 内容数据面（自动跟随有效根） |
| 91 | `src/main/scala/nebflow/core/flow/MailDeliveryDedup.scala:44` | 账号层 | 否 | `/mail-dedup.json` | 内容数据面（自动跟随有效根） |
| 92 | `src/main/scala/nebflow/core/flow/MailQueueStore.scala:42` | 账号层 | 否 | `/sessions/sessionId` | 内容数据面（自动跟随有效根） |
| 93 | `src/main/scala/nebflow/core/flow/MountedFlowStore.scala:35` | 账号层 | 否 | `/sessions/sessionId` | 内容数据面（自动跟随有效根） |
| 94 | `src/main/scala/nebflow/core/flow/TurnStateStore.scala:26` | 账号层 | 否 | `/sessions/sessionId/turn-state.json` | 内容数据面（自动跟随有效根） |
| 95 | `src/main/scala/nebflow/core/FriendsSeal.scala:84` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 96 | `src/main/scala/nebflow/core/Guardrails.scala:50` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 97 | `src/main/scala/nebflow/core/hotrestart/HotRestart.scala:559` | 跨层 | 核 | `.toString,` | 后继进程 home（进程握手身份；建议机器层，§7 R5） |
| 98 | `src/main/scala/nebflow/core/hotrestart/HotRestart.scala:569` | 跨层 | 核 | `.toString,` | 后继 intent home（同上，§7 R5） |
| 99 | `src/main/scala/nebflow/core/hotrestart/HotRestart.scala:684` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 100 | `src/main/scala/nebflow/core/hotrestart/HotRestart.scala:84` | 跨层 | 核 | `)` | `SuccessorGate.intentPath`（同上，§7 R5） |
| 101 | `src/main/scala/nebflow/core/hotrestart/HotRestart.scala:900` | 跨层 | 核 | `).toString,` | intent 路径文案（同上，§7 R5） |
| 102 | `src/main/scala/nebflow/core/hotrestart/HotRestart.scala:913` | 机器层 | 是 | `.toIO, logs)` | 后继进程 `logs`（机器层生命周期日志） |
| 103 | `src/main/scala/nebflow/core/hotupdate/FleetUpdateCoordinator.scala:743` | 账号层 | 否 | `/hotupdate` | 内容数据面（自动跟随有效根） |
| 104 | `src/main/scala/nebflow/core/hotupdate/Rollback.scala:32` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 105 | `src/main/scala/nebflow/core/index/EmbeddingService.scala:102` | 账号层 | 否 | `/secrets/name.trim` | 内容数据面（自动跟随有效根） |
| 106 | `src/main/scala/nebflow/core/index/VectorStore.scala:13` | 账号层 | 否 | `/index`，测试` | (注释/文档字符串) 口径说明，随有效根 |
| 107 | `src/main/scala/nebflow/core/index/VectorStore.scala:73` | 账号层 | 否 | `/IndexDirName/FileName` | 内容数据面（自动跟随有效根） |
| 108 | `src/main/scala/nebflow/core/jev/JevConfigReader.scala:30` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 109 | `src/main/scala/nebflow/core/jev/JevFallback.scala:82` | 账号层 | 否 | `/logs/jev-allocation.jsonl` | 内容数据面（自动跟随有效根） |
| 110 | `src/main/scala/nebflow/core/jev/JevShadow.scala:65` | 账号层 | 否 | `/logs/jev-shadow.jsonl` | 内容数据面（自动跟随有效根） |
| 111 | `src/main/scala/nebflow/core/LlmLogWriter.scala:104` | 账号层 | 否 | `: CLI --home` | (注释/文档字符串) 口径说明，随有效根 |
| 112 | `src/main/scala/nebflow/core/LlmLogWriter.scala:113` | 账号层 | 否 | `/logs/router).toNIO)` | 内容数据面（自动跟随有效根） |
| 113 | `src/main/scala/nebflow/core/LlmLogWriter.scala:18` | 账号层 | 否 | `（根本身）` | (注释/文档字符串) 口径说明，随有效根 |
| 114 | `src/main/scala/nebflow/core/mail/SessionMailbox.scala:134` | 账号层 | 否 | `/sessions/sid/mailbox` | 内容数据面（自动跟随有效根） |
| 115 | `src/main/scala/nebflow/core/mail/SessionMailbox.scala:427` | 账号层 | 否 | `/sessions` | 内容数据面（自动跟随有效根） |
| 116 | `src/main/scala/nebflow/core/mcp/AgentMcpLoader.scala:35` | 账号层 | 否 | `/agents)` | 内容数据面（自动跟随有效根） |
| 117 | `src/main/scala/nebflow/core/mcp/AgentMcpLoader.scala:36` | 账号层 | 否 | `/teams)` | 内容数据面（自动跟随有效根） |
| 118 | `src/main/scala/nebflow/core/mcp/AgentMcpLoader.scala:37` | 账号层 | 否 | `/flows)` | 内容数据面（自动跟随有效根） |
| 119 | `src/main/scala/nebflow/core/MemoryRecallDaemon.scala:218` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 120 | `src/main/scala/nebflow/core/MemoryRecallDaemon.scala:39` | 账号层 | 否 | ``（CLI --home/STAND_HOME/NEBFLOW_HOME/默认` | (注释/文档字符串) 口径说明，随有效根 |
| 121 | `src/main/scala/nebflow/core/ModelChainMigration.scala:122` | 账号层 | 否 | `/agents/name/agent.json` | 内容数据面（自动跟随有效根） |
| 122 | `src/main/scala/nebflow/core/ModelChainMigration.scala:70` | 账号层 | 否 | `/model-presets.json` | 内容数据面（自动跟随有效根） |
| 123 | `src/main/scala/nebflow/core/msgtokens/MsgTokenRegistry.scala:362` | 账号层 | 否 | `——测试应自建实例隔离）。 */` | (注释/文档字符串) 口径说明，随有效根 |
| 124 | `src/main/scala/nebflow/core/msgtokens/MsgTokenRegistry.scala:363` | 账号层 | 否 | `/sessions)` | 内容数据面（自动跟随有效根） |
| 125 | `src/main/scala/nebflow/core/msgtokens/MsgTokenRegistry.scala:371` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 126 | `src/main/scala/nebflow/core/OnboardingArtifacts.scala:274` | 账号层 | 否 | `/agents/nebflow.actor.RootAgentIdentity.Name` | 内容数据面（自动跟随有效根） |
| 127 | `src/main/scala/nebflow/core/OnboardingArtifacts.scala:289` | 账号层 | 否 | `/agents/nebflow.actor.RootAgentIdentity.Name/agent.json` | 内容数据面（自动跟随有效根） |
| 128 | `src/main/scala/nebflow/core/OnboardingModelStep.scala:217` | 账号层 | 否 | `/agents/nebflow.actor.RootAgentIdentity.Name` | 内容数据面（自动跟随有效根） |
| 129 | `src/main/scala/nebflow/core/OnboardingService.scala:55` | 账号层 | 否 | `may be swapped (tests) after object init. */` | (注释/文档字符串) 口径说明，随有效根 |
| 130 | `src/main/scala/nebflow/core/OnboardingService.scala:56` | 账号层 | 否 | `/onboarding.json` | 内容数据面（自动跟随有效根） |
| 131 | `src/main/scala/nebflow/core/permissions.scala:141` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 132 | `src/main/scala/nebflow/core/plugin/PluginBlockPolicy.scala:145` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 133 | `src/main/scala/nebflow/core/plugin/PluginBlockPolicy.scala:190` | 账号层 | 否 | `/logs/plugin-block.jsonl, line + \n, createFolders = true)` | 内容数据面（自动跟随有效根） |
| 134 | `src/main/scala/nebflow/core/plugin/PluginBlockPolicy.scala:49` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 135 | `src/main/scala/nebflow/core/plugin/PluginDispatchPolicy.scala:236` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 136 | `src/main/scala/nebflow/core/plugin/PluginDispatchPolicy.scala:288` | 账号层 | 否 | `/logs/plugin-dispatch.jsonl, line + \n, createFolders = true)` | 内容数据面（自动跟随有效根） |
| 137 | `src/main/scala/nebflow/core/plugin/PluginDispatchPolicy.scala:74` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 138 | `src/main/scala/nebflow/core/plugin/PluginMcpManager.scala:247` | 账号层 | 否 | `/plugin-data/java.nio.file.Paths.get(pluginDir).getFileName.toString` | 内容数据面（自动跟随有效根） |
| 139 | `src/main/scala/nebflow/core/plugin/PluginMcpManager.scala:260` | 账号层 | 否 | `/secrets,` | 内容数据面（自动跟随有效根） |
| 140 | `src/main/scala/nebflow/core/plugin/PluginRegistry.scala:1241` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 141 | `src/main/scala/nebflow/core/plugin/PluginRegistry.scala:1468` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 142 | `src/main/scala/nebflow/core/plugin/PluginRegistry.scala:90` | 账号层 | 否 | `/plugins` | 内容数据面（自动跟随有效根） |
| 143 | `src/main/scala/nebflow/core/plugin/PluginRemovePolicy.scala:179` | 账号层 | 否 | `/logs/plugin-remove.jsonl, line + \n, createFolders = true)` | 内容数据面（自动跟随有效根） |
| 144 | `src/main/scala/nebflow/core/plugin/PluginRemovePolicy.scala:53` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 145 | `src/main/scala/nebflow/core/plugin/PluginRemovePolicy.scala:93` | 账号层 | 否 | `/plugins/name` | 内容数据面（自动跟随有效根） |
| 146 | `src/main/scala/nebflow/core/plugin/PluginsConfig.scala:36` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 147 | `src/main/scala/nebflow/core/plugin/PluginSourceCatalog.scala:97` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 148 | `src/main/scala/nebflow/core/plugin/PluginSourceInstall.scala:73` | 账号层 | 否 | `/plugins` | 内容数据面（自动跟随有效根） |
| 149 | `src/main/scala/nebflow/core/plugin/PluginSourceInstall.scala:74` | 账号层 | 否 | `/.plugin-staging` | 内容数据面（自动跟随有效根） |
| 150 | `src/main/scala/nebflow/core/plugin/PluginSourceLedger.scala:110` | 账号层 | 否 | `/plugin-sources` | 内容数据面（自动跟随有效根） |
| 151 | `src/main/scala/nebflow/core/plugin/PluginSourceSync.scala:59` | 账号层 | 否 | `/plugins` | 内容数据面（自动跟随有效根） |
| 152 | `src/main/scala/nebflow/core/plugin/PluginSourceSync.scala:60` | 账号层 | 否 | `/.plugin-staging` | 内容数据面（自动跟随有效根） |
| 153 | `src/main/scala/nebflow/core/plugin/PluginSourceSync.scala:61` | 账号层 | 否 | `/plugins-backups` | 内容数据面（自动跟随有效根） |
| 154 | `src/main/scala/nebflow/core/plugin/PluginSourceSync.scala:62` | 账号层 | 否 | `/logs/plugin-source-sync.jsonl` | 内容数据面（自动跟随有效根） |
| 155 | `src/main/scala/nebflow/core/presets/ModelChainRegistry.scala:109` | 账号层 | 否 | `): Unit =` | 内容数据面（自动跟随有效根） |
| 156 | `src/main/scala/nebflow/core/presets/ModelChainRegistry.scala:41` | 账号层 | 否 | `): os.Path = root/FileName` | 内容数据面（自动跟随有效根） |
| 157 | `src/main/scala/nebflow/core/presets/ModelChainRegistry.scala:48` | 账号层 | 否 | `): Option[os.Path] =` | 内容数据面（自动跟随有效根） |
| 158 | `src/main/scala/nebflow/core/presets/ModelChainRegistry.scala:58` | 账号层 | 否 | `): Option[PresetFile] =` | 内容数据面（自动跟随有效根） |
| 159 | `src/main/scala/nebflow/core/presets/ModelChainRegistry.scala:82` | 账号层 | 否 | `): Option[String] =` | 内容数据面（自动跟随有效根） |
| 160 | `src/main/scala/nebflow/core/presets/ModelChainRegistry.scala:97` | 账号层 | 否 | `): Unit =` | 内容数据面（自动跟随有效根） |
| 161 | `src/main/scala/nebflow/core/presets/PresetStore.scala:110` | 账号层 | 否 | `/model-presets.json,` | 内容数据面（自动跟随有效根） |
| 162 | `src/main/scala/nebflow/core/presets/PresetStore.scala:332` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 163 | `src/main/scala/nebflow/core/presets/PresetStore.scala:355` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 164 | `src/main/scala/nebflow/core/presets/PresetStore.scala:400` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 165 | `src/main/scala/nebflow/core/processor/DeliveredReceiptLedger.scala:71` | 账号层 | 否 | `/sessions/sessionId/delivered-receipts.json` | 内容数据面（自动跟随有效根） |
| 166 | `src/main/scala/nebflow/core/processor/HeldReceiptStore.scala:101` | 账号层 | 否 | `/sessions/sessionId/held-receipts.json` | 内容数据面（自动跟随有效根） |
| 167 | `src/main/scala/nebflow/core/processor/LoopGuard.scala:73` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 168 | `src/main/scala/nebflow/core/processor/WatchdogEventLog.scala:23` | 账号层 | 否 | `` 语义 = CLI `--home`/`NEBFLOW_HOME`/默认 `~/.nebflow`；` | (注释/文档字符串) 口径说明，随有效根 |
| 169 | `src/main/scala/nebflow/core/processor/WatchdogEventLog.scala:58` | 账号层 | 否 | `/logs/watchdog).toNIO)` | 内容数据面（自动跟随有效根） |
| 170 | `src/main/scala/nebflow/core/project/BootDispatcherWake.scala:234` | 跨层 | 是 | `)/.nebflow` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 171 | `src/main/scala/nebflow/core/project/BootDispatcherWake.scala:303` | 账号层 | 否 | `/ShutdownMarkerFileName` | 内容数据面（自动跟随有效根） |
| 172 | `src/main/scala/nebflow/core/project/BootDispatcherWake.scala:350` | 账号层 | 否 | `/sessions,` | 内容数据面（自动跟随有效根） |
| 173 | `src/main/scala/nebflow/core/project/BootWakeInventory.scala:122` | 账号层 | 否 | `/sessions` | 内容数据面（自动跟随有效根） |
| 174 | `src/main/scala/nebflow/core/project/BootWakeInventory.scala:153` | 账号层 | 否 | `/sessions` | 内容数据面（自动跟随有效根） |
| 175 | `src/main/scala/nebflow/core/project/BootWakeInventory.scala:170` | 账号层 | 否 | `/sessions` | 内容数据面（自动跟随有效根） |
| 176 | `src/main/scala/nebflow/core/project/ChainsReadFace.scala:139` | 账号层 | 否 | `.toString +: defs.map(_.workspace)).toSet)` | 内容数据面（自动跟随有效根） |
| 177 | `src/main/scala/nebflow/core/project/ChainsReadFace.scala:454` | 跨层 | 是 | `)/.nebflow/FlowMapStore.FlowMapsDirName/rec.id` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 178 | `src/main/scala/nebflow/core/project/DelegateFlowHosts.scala:231` | 跨层 | 是 | `)/.nebflow/FlowMapStore.FlowMapsDirName` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 179 | `src/main/scala/nebflow/core/project/DelegateFlowHosts.scala:241` | 账号层 | 否 | `/general).toString` | 内容数据面（自动跟随有效根） |
| 180 | `src/main/scala/nebflow/core/project/DelegateFlowHosts.scala:65` | 跨层 | 是 | `)/.nebflow/FlowMapStore.FlowMapsDirName/delegateId` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 181 | `src/main/scala/nebflow/core/project/DelegateFlowHosts.scala:87` | 跨层 | 是 | `)/AGENTS.md).toString,` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 182 | `src/main/scala/nebflow/core/project/DocIndexConsumer.scala:556` | 跨层 | 是 | `)/.nebflow/FlowMapEventLog.FileName` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 183 | `src/main/scala/nebflow/core/project/DocIndexConsumer.scala:559` | 跨层 | 是 | `)/.nebflow/FlowMapStore.ArchiveDirName` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 184 | `src/main/scala/nebflow/core/project/DocIndexConsumer.scala:562` | 跨层 | 是 | `)/.nebflow/tmp/ReportFileName` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 185 | `src/main/scala/nebflow/core/project/DocIndexConsumer.scala:723` | 账号层 | 否 | `/docs).toString,` | 内容数据面（自动跟随有效根） |
| 186 | `src/main/scala/nebflow/core/project/DocIndexConsumer.scala:724` | 跨层 | 是 | `)/.nebflow/Spec).toString` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 187 | `src/main/scala/nebflow/core/project/FileChangeReport.scala:118` | 跨层 | 是 | `)/.nebflow/FileChangesDirName/s$nodeId.json).toString` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 188 | `src/main/scala/nebflow/core/project/FileChangeReport.scala:442` | 跨层 | 是 | `)/.nebflow/FileChangesDirName` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 189 | `src/main/scala/nebflow/core/project/FlowMapEventLog.scala:980` | 跨层 | 是 | `)/.nebflow/FileName, line + \n, createFolders = true)` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 190 | `src/main/scala/nebflow/core/project/FlowMapStore.scala:1149` | 跨层 | 是 | `)/.nebflow/ResultsDirName/s$DescriptionLongPrefix$nodeId.md` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 191 | `src/main/scala/nebflow/core/project/FlowMapStore.scala:2050` | 跨层 | 是 | `)/.nebflow` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 192 | `src/main/scala/nebflow/core/project/NodeReportRegistry.scala:147` | 跨层 | 是 | `)/.nebflow/JournalFileName` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 193 | `src/main/scala/nebflow/core/project/ProjectCreateService.scala:142` | 账号层 | 否 | `)).flatMap {` | 内容数据面（自动跟随有效根） |
| 194 | `src/main/scala/nebflow/core/project/ProjectCreateService.scala:147` | 账号层 | 否 | `)).flatMap {` | 内容数据面（自动跟随有效根） |
| 195 | `src/main/scala/nebflow/core/project/ProjectCreateService.scala:520` | 跨层 | 否 | `RenderValue}/projects/$resolvedName/project.json to restore it first.` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 196 | `src/main/scala/nebflow/core/project/ProjectCreateService.scala:536` | 跨层 | 否 | `RenderValue}/projects/$resolvedName/project.json.` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 197 | `src/main/scala/nebflow/core/project/ProjectCreateService.scala:561` | 账号层 | 否 | `)).flatMap {` | 内容数据面（自动跟随有效根） |
| 198 | `src/main/scala/nebflow/core/project/ProjectCreateService.scala:565` | 跨层 | 是 | `), AgentMdTemplateFull)` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 199 | `src/main/scala/nebflow/core/project/ProjectCreateService.scala:596` | 跨层 | 是 | `).toString).toOption.map(stripTrailingSlashes)` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 200 | `src/main/scala/nebflow/core/project/ProjectCreateService.scala:649` | 跨层 | 是 | `)).map(_.last).getOrElse()` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 201 | `src/main/scala/nebflow/core/project/ProjectMemory.scala:48` | 账号层 | 否 | `) 同规）。` | (注释/文档字符串) 口径说明，随有效根 |
| 202 | `src/main/scala/nebflow/core/project/ProjectMemory.scala:51` | 跨层 | 是 | `)/.nebflow/FileName` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 203 | `src/main/scala/nebflow/core/project/ProjectStore.scala:231` | 跨层 | 是 | `), name)` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 204 | `src/main/scala/nebflow/core/project/ProjectStore.scala:45` | 账号层 | 否 | `/projects` | 内容数据面（自动跟随有效根） |
| 205 | `src/main/scala/nebflow/core/project/ProjectStore.scala:506` | 跨层 | 是 | `)` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 206 | `src/main/scala/nebflow/core/project/ProjectStore.scala:94` | 跨层 | 是 | `)/docs` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 207 | `src/main/scala/nebflow/core/project/ScopeHandover.scala:38` | 跨层 | 是 | `)/.nebflow/FlowMapStore.FlowMapsDirName/delegateId` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 208 | `src/main/scala/nebflow/core/project/TaskBoardHistory.scala:96` | 跨层 | 是 | `)/.nebflow` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 209 | `src/main/scala/nebflow/core/project/TaskBoardStore.scala:113` | 跨层 | 是 | `)/.nebflow/task-board.json` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 210 | `src/main/scala/nebflow/core/project/TaskLedgerHistory.scala:77` | 账号层 | 否 | `` can be re-rooted by tests (`setDataRoot`) --` | (注释/文档字符串) 口径说明，随有效根 |
| 211 | `src/main/scala/nebflow/core/project/TaskLedgerHistory.scala:80` | 账号层 | 否 | `/FileName` | 内容数据面（自动跟随有效根） |
| 212 | `src/main/scala/nebflow/core/project/TaskLedgerHistory.scala:82` | 账号层 | 否 | `/ArchiveFileName` | 内容数据面（自动跟随有效根） |
| 213 | `src/main/scala/nebflow/core/project/TaskLedgerStore.scala:133` | 账号层 | 否 | `` can be re-rooted by tests (`setDataRoot`). */` | (注释/文档字符串) 口径说明，随有效根 |
| 214 | `src/main/scala/nebflow/core/project/TaskLedgerStore.scala:134` | 账号层 | 否 | `/FileName` | 内容数据面（自动跟随有效根） |
| 215 | `src/main/scala/nebflow/core/project/TaskLedgerStore.scala:71` | 账号层 | 否 | `` ⇒` | (注释/文档字符串) 口径说明，随有效根 |
| 216 | `src/main/scala/nebflow/core/repl.scala:92` | 账号层 | 否 | `/agents/RootAgentIdentity.Name/system.md` | 内容数据面（自动跟随有效根） |
| 217 | `src/main/scala/nebflow/core/RestartHelper.scala:107` | 机器层 | 是 | `.toString, logs)` | `logs`（机器层生命周期日志） |
| 218 | `src/main/scala/nebflow/core/RestartHelper.scala:52` | 机器层 | 是 | `.toString, logs), restart.log)` | `logs/restart.log`（机器层生命周期日志） |
| 219 | `src/main/scala/nebflow/core/RestartHelper.scala:65` | 机器层 | 是 | `.toString, logs), restart.log)` | `logs/restart.log`（机器层生命周期日志） |
| 220 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:159` | 账号层 | 否 | `（rebrand/测试 setDataRoot 均生效）。` | (注释/文档字符串) 口径说明，随有效根 |
| 221 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:164` | 账号层 | 否 | `/s)` | 内容数据面（自动跟随有效根） |
| 222 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:183` | 账号层 | 否 | `/User.md,` | 内容数据面（自动跟随有效根） |
| 223 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:184` | 账号层 | 否 | `/Soul.md,` | 内容数据面（自动跟随有效根） |
| 224 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:185` | 账号层 | 否 | `/agents/RootAgentIdentity.Name/memory.md` | 内容数据面（自动跟随有效根） |
| 225 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:189` | 账号层 | 否 | `/agents` | 内容数据面（自动跟随有效根） |
| 226 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:244` | 账号层 | 否 | `（数据根，与 nebflowReadExtras 同源——NEBFLOW_HOME/` | (注释/文档字符串) 口径说明，随有效根 |
| 227 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:269` | 账号层 | 否 | `.toString` | 内容数据面（自动跟随有效根） |
| 228 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:302` | 账号层 | 否 | `（NEBFLOW_HOME/CLI --home/setDataRoot` | (注释/文档字符串) 口径说明，随有效根 |
| 229 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:307` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 230 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:55` | 账号层 | 否 | `，取代早期` | (注释/文档字符串) 口径说明，随有效根 |
| 231 | `src/main/scala/nebflow/core/sandbox/SandboxPolicy.scala:85` | 账号层 | 否 | `——与 nebflowReadExtras 同一数据根推导（NEBFLOW_HOME/` | (注释/文档字符串) 口径说明，随有效根 |
| 232 | `src/main/scala/nebflow/core/scheduler/ConditionEvaluators.scala:197` | 账号层 | 否 | `/projects/project/.nebflow/flow-map.json` | 内容数据面（自动跟随有效根） |
| 233 | `src/main/scala/nebflow/core/SchemePolicy.scala:118` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 234 | `src/main/scala/nebflow/core/SchemePolicy.scala:141` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 235 | `src/main/scala/nebflow/core/SchemePolicy.scala:78` | 账号层 | 否 | `/agents/agent/agent.json` | 内容数据面（自动跟随有效根） |
| 236 | `src/main/scala/nebflow/core/secrets/SecretsStore.scala:643` | 账号层 | 否 | `}/secrets/` | 内容数据面（自动跟随有效根） |
| 237 | `src/main/scala/nebflow/core/secrets/SecretsStore.scala:67` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 238 | `src/main/scala/nebflow/core/secrets/SecretsStore.scala:73` | 机器层 | 是 | `/daemons.json,` | `daemonsPath` 默认值（机器层，与 daemons.json 同根） |
| 239 | `src/main/scala/nebflow/core/seed/SeedService.scala:216` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 240 | `src/main/scala/nebflow/core/seed/SeedService.scala:655` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 241 | `src/main/scala/nebflow/core/seed/SeedService.scala:702` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 242 | `src/main/scala/nebflow/core/SessionStore.scala:1149` | 账号层 | 否 | `/uploads/id` | 内容数据面（自动跟随有效根） |
| 243 | `src/main/scala/nebflow/core/SessionStore.scala:1154` | 账号层 | 否 | `/tool-results/id` | 内容数据面（自动跟随有效根） |
| 244 | `src/main/scala/nebflow/core/skill/SkillAudit.scala:134` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 245 | `src/main/scala/nebflow/core/skill/SkillService.scala:144` | 跨层 | 否 | `RenderValue}/skills/<ns>/<name>/SKILL.md` — their identifier is the re` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 246 | `src/main/scala/nebflow/core/skill/SkillService.scala:184` | 跨层 | 否 | `RenderValue}/skills/<name>/SKILL.md` with frontmatter + instructions` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 247 | `src/main/scala/nebflow/core/skill/SkillService.scala:251` | 跨层 | 否 | `RenderValue}/skills/<name>/SKILL.md. When a task matches a skill, read` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 248 | `src/main/scala/nebflow/core/skill/SkillService.scala:66` | 账号层 | 否 | `/skills` | 内容数据面（自动跟随有效根） |
| 249 | `src/main/scala/nebflow/core/SoulMigration.scala:103` | 账号层 | 否 | `/memory-archive/sagents-$stamp` | 内容数据面（自动跟随有效根） |
| 250 | `src/main/scala/nebflow/core/SoulMigration.scala:113` | 账号层 | 否 | `/agents` | 内容数据面（自动跟随有效根） |
| 251 | `src/main/scala/nebflow/core/task/TaskStore.scala:79` | 账号层 | 否 | `may be swapped after this object's` | (注释/文档字符串) 口径说明，随有效根 |
| 252 | `src/main/scala/nebflow/core/task/TaskStore.scala:83` | 账号层 | 否 | `/tasks` | 内容数据面（自动跟随有效根） |
| 253 | `src/main/scala/nebflow/core/task/TaskStore.scala:95` | 账号层 | 否 | `on every` | (注释/文档字符串) 口径说明，随有效根 |
| 254 | `src/main/scala/nebflow/core/tools/AskTools.scala:208` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 255 | `src/main/scala/nebflow/core/tools/BashTool.scala:172` | 账号层 | 否 | `（根本身）` | (注释/文档字符串) 口径说明，随有效根 |
| 256 | `src/main/scala/nebflow/core/tools/BashTool.scala:188` | 账号层 | 否 | `.toString` | 内容数据面（自动跟随有效根） |
| 257 | `src/main/scala/nebflow/core/tools/BashTool.scala:476` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 258 | `src/main/scala/nebflow/core/tools/BashTool.scala:509` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 259 | `src/main/scala/nebflow/core/tools/BashTool.scala:511` | 账号层 | 否 | `/secrets, entries)` | 内容数据面（自动跟随有效根） |
| 260 | `src/main/scala/nebflow/core/tools/BgTaskLedger.scala:133` | 账号层 | 否 | `/sessions/sessionId/bg-task-ledger.json` | 内容数据面（自动跟随有效根） |
| 261 | `src/main/scala/nebflow/core/tools/BgTaskLedger.scala:278` | 账号层 | 否 | `/sessions` | 内容数据面（自动跟随有效根） |
| 262 | `src/main/scala/nebflow/core/tools/BgTaskOutputStore.scala:111` | 账号层 | 否 | `]]）；测试不调用 = 纯内存。` | (注释/文档字符串) 口径说明，随有效根 |
| 263 | `src/main/scala/nebflow/core/tools/DelegateTool.scala:339` | 账号层 | 否 | `/DefaultWorkspaceName` | 内容数据面（自动跟随有效根） |
| 264 | `src/main/scala/nebflow/core/tools/DeviceProfile.scala:158` | 账号层 | 否 | `/neblink/device-profiles.json` | 内容数据面（自动跟随有效根） |
| 265 | `src/main/scala/nebflow/core/tools/DeviceProfile.scala:491` | 账号层 | 否 | `/neblink/captures` | 内容数据面（自动跟随有效根） |
| 266 | `src/main/scala/nebflow/core/tools/FileOpenCache.scala:126` | 账号层 | 否 | `/cache/fileopen/v1` | 内容数据面（自动跟随有效根） |
| 267 | `src/main/scala/nebflow/core/tools/FileRefs.scala:160` | 账号层 | 否 | `and the project` | (注释/文档字符串) 口径说明，随有效根 |
| 268 | `src/main/scala/nebflow/core/tools/LoadTool.scala:151` | 账号层 | 否 | `/flows/s$name.json` | 内容数据面（自动跟随有效根） |
| 269 | `src/main/scala/nebflow/core/tools/LoadTool.scala:18` | 跨层 | 否 | `RenderValue —— 默认 home ⇒ `~/.nebflow/...`（与旧字面` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 270 | `src/main/scala/nebflow/core/tools/LoadTool.scala:24` | 跨层 | 否 | `RenderValue}/teams/<name>/team.json, validate structure + agent refere` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 271 | `src/main/scala/nebflow/core/tools/LoadTool.scala:26` | 跨层 | 否 | `RenderValue}/flows/<name>.json, validate DAG structure + agent referen` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 272 | `src/main/scala/nebflow/core/tools/LoadTool.scala:73` | 账号层 | 否 | `/teams/name/team.json` | 内容数据面（自动跟随有效根） |
| 273 | `src/main/scala/nebflow/core/tools/PointerPickup.scala:40` | 账号层 | 否 | `/uploads/pickups` | 内容数据面（自动跟随有效根） |
| 274 | `src/main/scala/nebflow/core/tools/PopTool.scala:121` | 跨层 | 否 | `RenderValue` at CALL time — a` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 275 | `src/main/scala/nebflow/core/tools/PopTool.scala:151` | 跨层 | 否 | `RenderValue}/projects/<name>/— write that full path (not `~/projects/<` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 276 | `src/main/scala/nebflow/core/tools/ProjectTool.scala:261` | 跨层 | 是 | `)) match` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 277 | `src/main/scala/nebflow/core/tools/RelayExecAudit.scala:101` | 账号层 | 否 | `/logs/relay-exec-audit.jsonl).toNIO` | 内容数据面（自动跟随有效根） |
| 278 | `src/main/scala/nebflow/core/tools/RelayExecAudit.scala:27` | 账号层 | 否 | `（根本身）` | (注释/文档字符串) 口径说明，随有效根 |
| 279 | `src/main/scala/nebflow/core/tools/RgHelper.scala:121` | 跨层 | 否 | `RenderValue 插值（home` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 280 | `src/main/scala/nebflow/core/tools/RgHelper.scala:128` | 机器层 | 是 | `RenderValue}/bin). Run 'nebflow update' or reinstall to get it: https:` | 提示文案里的 `…/bin`（机器层安装物） |
| 281 | `src/main/scala/nebflow/core/tools/RgHelper.scala:36` | 账号层 | 否 | `): an` | (注释/文档字符串) 口径说明，随有效根 |
| 282 | `src/main/scala/nebflow/core/tools/TaskListHistory.scala:100` | 账号层 | 否 | `/ArchiveFileName` | 内容数据面（自动跟随有效根） |
| 283 | `src/main/scala/nebflow/core/tools/TaskListHistory.scala:94` | 账号层 | 否 | `` 可被测试换根（TaskListStore.file 同款陷阱）。` | (注释/文档字符串) 口径说明，随有效根 |
| 284 | `src/main/scala/nebflow/core/tools/TaskListHistory.scala:99` | 账号层 | 否 | `/ActiveFileName` | 内容数据面（自动跟随有效根） |
| 285 | `src/main/scala/nebflow/core/tools/TaskListTool.scala:1102` | 跨层 | 否 | `RenderValue —— 默认 home ⇒ `~/.nebflow/...`（与旧字面` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 286 | `src/main/scala/nebflow/core/tools/TaskListTool.scala:1106` | 跨层 | 否 | `RenderValue}/tasks.json (runtime data — never in git, never injected w` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 287 | `src/main/scala/nebflow/core/tools/TaskListTool.scala:1121` | 跨层 | 否 | `RenderValue}/tasks-history.archive-<date>` (zero impact on tasks.json)` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 288 | `src/main/scala/nebflow/core/tools/TaskListTool.scala:175` | 账号层 | 否 | `可被测试换根（FileTaskStore 同款陷阱）。 */` | (注释/文档字符串) 口径说明，随有效根 |
| 289 | `src/main/scala/nebflow/core/tools/TaskListTool.scala:176` | 账号层 | 否 | `/tasks.json` | 内容数据面（自动跟随有效根） |
| 290 | `src/main/scala/nebflow/core/tools/TaskTool.scala:213` | 跨层 | 否 | `RenderValue}/$historyFile (append-only, rotated at ~5 MiB/20,000 lines` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 291 | `src/main/scala/nebflow/core/tools/TaskTool.scala:238` | 跨层 | 否 | `RenderValue}/$historyFile, INDEPENDENT of the entry: pruning an entry ` | 文案/渲染值面（随有效根渲染，见 §7 R4） |
| 292 | `src/main/scala/nebflow/core/tools/ToolLoader.scala:112` | 账号层 | 否 | `/teams).map(teamToolsDir)` | 内容数据面（自动跟随有效根） |
| 293 | `src/main/scala/nebflow/core/tools/ToolLoader.scala:113` | 账号层 | 否 | `/flows).map(flowToolsDir)` | 内容数据面（自动跟随有效根） |
| 294 | `src/main/scala/nebflow/core/tools/ToolLoader.scala:12` | 账号层 | 否 | `/tools` | 内容数据面（自动跟随有效根） |
| 295 | `src/main/scala/nebflow/core/tools/ToolLoader.scala:129` | 账号层 | 否 | `/teams))` | 内容数据面（自动跟随有效根） |
| 296 | `src/main/scala/nebflow/core/tools/ToolLoader.scala:13` | 账号层 | 否 | `/teams/team/tools` | 内容数据面（自动跟随有效根） |
| 297 | `src/main/scala/nebflow/core/tools/ToolLoader.scala:131` | 账号层 | 否 | `/flows))` | 内容数据面（自动跟随有效根） |
| 298 | `src/main/scala/nebflow/core/tools/ToolLoader.scala:14` | 账号层 | 否 | `/flows/flow/tools` | 内容数据面（自动跟随有效根） |
| 299 | `src/main/scala/nebflow/core/tools/ToolResultGuard.scala:28` | 账号层 | 否 | `/tool-results/sessionId` | 内容数据面（自动跟随有效根） |
| 300 | `src/main/scala/nebflow/core/ToolsLogWriter.scala:19` | 账号层 | 否 | `（根本身）` | (注释/文档字符串) 口径说明，随有效根 |
| 301 | `src/main/scala/nebflow/core/ToolsLogWriter.scala:83` | 账号层 | 否 | `) — same` | (注释/文档字符串) 口径说明，随有效根 |
| 302 | `src/main/scala/nebflow/core/ToolsLogWriter.scala:89` | 账号层 | 否 | `/logs/tools).toNIO)` | 内容数据面（自动跟随有效根） |
| 303 | `src/main/scala/nebflow/core/UsageTracker.scala:90` | 账号层 | 否 | `is mutated by tests between suites; a` | (注释/文档字符串) 口径说明，随有效根 |
| 304 | `src/main/scala/nebflow/core/UsageTracker.scala:96` | 账号层 | 否 | `/usage-log.jsonl` | 内容数据面（自动跟随有效根） |
| 305 | `src/main/scala/nebflow/core/UsageTracker.scala:97` | 账号层 | 否 | `/usage-pattern.json` | 内容数据面（自动跟随有效根） |
| 306 | `src/main/scala/nebflow/core/wiki/WikiService.scala:511` | 跨层 | 是 | `)` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 307 | `src/main/scala/nebflow/core/wiki/WikiService.scala:882` | 跨层 | 是 | `)` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 308 | `src/main/scala/nebflow/dropbox/DropboxService.scala:141` | 账号层 | 否 | `/dropbox/.tmp` | 内容数据面（自动跟随有效根） |
| 309 | `src/main/scala/nebflow/dropbox/DropboxService.scala:161` | 账号层 | 核 | `/dropbox/messages.json` | `private val messagesPath`（对象级冻结） |
| 310 | `src/main/scala/nebflow/dropbox/DropboxService.scala:206` | 账号层 | 核 | `/dropbox/transfers.json` | `private val transfersPath`（对象级冻结） |
| 311 | `src/main/scala/nebflow/dropbox/DropboxService.scala:286` | 账号层 | 否 | `/dropbox/.tmp/s$transferId.tmp` | 内容数据面（自动跟随有效根） |
| 312 | `src/main/scala/nebflow/dropbox/TargetDirGuard.scala:51` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 313 | `src/main/scala/nebflow/gateway/AskUserAttachmentRoutes.scala:126` | 账号层 | 否 | `/uploads` | 内容数据面（自动跟随有效根） |
| 314 | `src/main/scala/nebflow/gateway/AskUserAttachmentRoutes.scala:76` | 账号层 | 否 | `/uploads/rootSessionId/dirName(requestId)` | 内容数据面（自动跟随有效根） |
| 315 | `src/main/scala/nebflow/gateway/auth.scala:13` | 账号层 | 否 | `is redirected by the` | (注释/文档字符串) 口径说明，随有效根 |
| 316 | `src/main/scala/nebflow/gateway/auth.scala:18` | 机器层 | 是 | `/auth.json` | `auth.json`（机器层破玻璃令牌） |
| 317 | `src/main/scala/nebflow/gateway/ConfigRoutes.scala:114` | 账号层 | 否 | `/canvas_tabs.json).load().flatMap {` | 内容数据面（自动跟随有效根） |
| 318 | `src/main/scala/nebflow/gateway/ConfigRoutes.scala:130` | 账号层 | 否 | `/canvas_tabs.json)` | 内容数据面（自动跟随有效根） |
| 319 | `src/main/scala/nebflow/gateway/ConfigRoutes.scala:59` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 320 | `src/main/scala/nebflow/gateway/gatewayConfig.scala:96` | 跨层 | 是 | `,` | `PortInputs.root` → 必须 machineRoot |
| 321 | `src/main/scala/nebflow/gateway/GatewayMain.scala:109` | 跨层 | 是 | `}; different data roots are different instances, so it is NEVER killed` | 「NEVER killed」拒启文案 → machineRoot |
| 322 | `src/main/scala/nebflow/gateway/GatewayMain.scala:167` | 账号层 | 否 | `, cfg.host.toString, cfg.port.value)` | 内容数据面（自动跟随有效根） |
| 323 | `src/main/scala/nebflow/gateway/GatewayMain.scala:1769` | 账号层 | 否 | `/weekly-report/state.json).toNIO,` | 内容数据面（自动跟随有效根） |
| 324 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2053` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 325 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2057` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 326 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2212` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 327 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2217` | 账号层 | 否 | `) *>` | 内容数据面（自动跟随有效根） |
| 328 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2264` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 329 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2306` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 330 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2316` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 331 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2424` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 332 | `src/main/scala/nebflow/gateway/GatewayMain.scala:2435` | 账号层 | 否 | `, None)` | 内容数据面（自动跟随有效根） |
| 333 | `src/main/scala/nebflow/gateway/GatewayMain.scala:543` | 账号层 | 否 | `/gateway-sessions.json)` | 内容数据面（自动跟随有效根） |
| 334 | `src/main/scala/nebflow/gateway/GatewayMain.scala:546` | 账号层 | 否 | `/tasks)` | 内容数据面（自动跟随有效根） |
| 335 | `src/main/scala/nebflow/gateway/GatewayMain.scala:557` | 账号层 | 否 | `/sessions)` | 内容数据面（自动跟随有效根） |
| 336 | `src/main/scala/nebflow/gateway/GatewayMain.scala:558` | 账号层 | 否 | `).unsafeRunSync()` | 内容数据面（自动跟随有效根） |
| 337 | `src/main/scala/nebflow/gateway/GatewayMain.scala:679` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 338 | `src/main/scala/nebflow/gateway/GatewayMain.scala:69` | 跨层 | 是 | `.toString).map(root => (pid, cmd, root))` | `StaleProcessGuard.otherInstanceRoot` → machineRoot |
| 339 | `src/main/scala/nebflow/gateway/GatewayMain.scala:712` | 账号层 | 否 | `/sessions),` | 内容数据面（自动跟随有效根） |
| 340 | `src/main/scala/nebflow/gateway/GatewayMain.scala:78` | 跨层 | 是 | `}) — +` | 「foreign brand home」拒启文案 → machineRoot |
| 341 | `src/main/scala/nebflow/gateway/GatewayMain.scala:98` | 跨层 | 是 | `.toString, nebflow.cli.ProcessManager.readPid()) match` | `InstanceScope.proveHome` 归属门 → machineRoot |
| 342 | `src/main/scala/nebflow/gateway/JevProbe.scala:65` | 账号层 | 否 | `/secrets/name.trim` | 内容数据面（自动跟随有效根） |
| 343 | `src/main/scala/nebflow/gateway/NfFilePolicy.scala:111` | 账号层 | 否 | `(the data root; `overrideRoot` respected — never` | (注释/文档字符串) 口径说明，随有效根 |
| 344 | `src/main/scala/nebflow/gateway/NfFilePolicy.scala:125` | 账号层 | 否 | `` subtrees whose contents may be served.` | (注释/文档字符串) 口径说明，随有效根 |
| 345 | `src/main/scala/nebflow/gateway/NfFilePolicy.scala:186` | 账号层 | 否 | ``); `workspaceRoot` = P3 = the project's` | (注释/文档字符串) 口径说明，随有效根 |
| 346 | `src/main/scala/nebflow/gateway/NfFilePolicy.scala:264` | 账号层 | 否 | `.toString))` | 内容数据面（自动跟随有效根） |
| 347 | `src/main/scala/nebflow/gateway/NfFilePolicy.scala:288` | 账号层 | 否 | ``/the process` | (注释/文档字符串) 口径说明，随有效根 |
| 348 | `src/main/scala/nebflow/gateway/NfFilePolicy.scala:297` | 账号层 | 否 | `.toString}\u0000${os.pwd.toString}` | 内容数据面（自动跟随有效根） |
| 349 | `src/main/scala/nebflow/gateway/NfOfficeRoutes.scala:290` | 账号层 | 否 | `/cache/office-preview` | 内容数据面（自动跟随有效根） |
| 350 | `src/main/scala/nebflow/gateway/NfServeGate.scala:113` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 351 | `src/main/scala/nebflow/gateway/NfTicketStore.scala:227` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 352 | `src/main/scala/nebflow/gateway/NfTicketStore.scala:75` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 353 | `src/main/scala/nebflow/gateway/PatAuth.scala:117` | 账号层 | 否 | `/neblink/config.json` | 内容数据面（自动跟随有效根） |
| 354 | `src/main/scala/nebflow/gateway/PatAuth.scala:80` | 账号层 | 否 | `` 可重定向（测试/隔离实例），val 会把它冻在对象初始化。 */` | (注释/文档字符串) 口径说明，随有效根 |
| 355 | `src/main/scala/nebflow/gateway/PatAuth.scala:81` | 账号层 | 否 | `/pat.json` | 内容数据面（自动跟随有效根） |
| 356 | `src/main/scala/nebflow/gateway/PerceptionDaemonDock.scala:162` | 机器层 | 是 | `): IO[Unit] =` | 感知守护 dock home（机器层） |
| 357 | `src/main/scala/nebflow/gateway/PerceptionDaemonDock.scala:178` | 机器层 | 是 | `): IO[Option[Upserted]] =` | 感知守护 prepare home（机器层） |
| 358 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1262` | 账号层 | 否 | `/agents/agentName` | 内容数据面（自动跟随有效根） |
| 359 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1464` | 账号层 | 否 | `/agents/agentName/avatar` | 内容数据面（自动跟随有效根） |
| 360 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1653` | 账号层 | 否 | `/plugins/name +` | 内容数据面（自动跟随有效根） |
| 361 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1792` | 账号层 | 否 | `, set)).flatMap(json => Ok(json))` | 内容数据面（自动跟随有效根） |
| 362 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1808` | 账号层 | 否 | `, p)))` | 内容数据面（自动跟随有效根） |
| 363 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1836` | 账号层 | 否 | `, feishu, sid))` | 内容数据面（自动跟随有效根） |
| 364 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1848` | 账号层 | 否 | `, feishu))` | 内容数据面（自动跟随有效根） |
| 365 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1882` | 账号层 | 否 | `, channel, sid))` | 内容数据面（自动跟随有效根） |
| 366 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:1895` | 账号层 | 否 | `, channel))` | 内容数据面（自动跟随有效根） |
| 367 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:2047` | 账号层 | 否 | `, channelId, body)).flatMap {` | 内容数据面（自动跟随有效根） |
| 368 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:2062` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 369 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:2066` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 370 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:2082` | 账号层 | 否 | `, channelId, set)).flatMap {` | 内容数据面（自动跟随有效根） |
| 371 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:2116` | 账号层 | 否 | `/teams/teamName` | 内容数据面（自动跟随有效根） |
| 372 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:271` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 373 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:274` | 账号层 | 否 | `))` | 内容数据面（自动跟随有效根） |
| 374 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:2818` | 账号层 | 否 | `, bearer).flatMap {` | 内容数据面（自动跟随有效根） |
| 375 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:297` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 376 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:298` | 账号层 | 否 | `),` | 内容数据面（自动跟随有效根） |
| 377 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:301` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 378 | `src/main/scala/nebflow/gateway/PresenceRoutes.scala:351` | 账号层 | 否 | `.toString),` | 内容数据面（自动跟随有效根） |
| 379 | `src/main/scala/nebflow/gateway/ProjectsRoutes.scala:434` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 380 | `src/main/scala/nebflow/gateway/ProjectsRoutes.scala:461` | 跨层 | 是 | `)/AGENTS.md` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 381 | `src/main/scala/nebflow/gateway/ProjectsRoutes.scala:480` | 跨层 | 是 | `)/AGENTS.md` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 382 | `src/main/scala/nebflow/gateway/SecretsRoutes.scala:39` | 账号层 | 否 | `` never sees a stale root.` | (注释/文档字符串) 口径说明，随有效根 |
| 383 | `src/main/scala/nebflow/gateway/SecretsRoutes.scala:42` | 账号层 | 否 | `/secrets` | 内容数据面（自动跟随有效根） |
| 384 | `src/main/scala/nebflow/gateway/StaticRoutes.scala:685` | 账号层 | 否 | `/uploads` | 内容数据面（自动跟随有效根） |
| 385 | `src/main/scala/nebflow/gateway/SttService.scala:19` | 账号层 | 否 | `可被测试 setDataRoot` | (注释/文档字符串) 口径说明，随有效根 |
| 386 | `src/main/scala/nebflow/gateway/SttService.scala:22` | 账号层 | 否 | `/stt-config.json` | 内容数据面（自动跟随有效根） |
| 387 | `src/main/scala/nebflow/gateway/TtsService.scala:14` | 账号层 | 核 | `/tts-config.json` | `private val configPath`（对象级冻结） |
| 388 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:1634` | 账号层 | 否 | `/voice-models` | 内容数据面（自动跟随有效根） |
| 389 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:1641` | 账号层 | 核 | `/input_history.jsonl` | `private val inputHistoryPath`（对象级冻结） |
| 390 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:1750` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 391 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:1772` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 392 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:1791` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 393 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:1950` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 394 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:212` | 账号层 | 否 | `/projects` | 内容数据面（自动跟随有效根） |
| 395 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:250` | 账号层 | 否 | `）。判定基准=WS 根会话 ∧ agent==Nebula——此处` | (注释/文档字符串) 口径说明，随有效根 |
| 396 | `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:2540` | 账号层 | 否 | `, until)` | 内容数据面（自动跟随有效根） |
| 397 | `src/main/scala/nebflow/gateway/WsConfigHandlers.scala:150` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 398 | `src/main/scala/nebflow/gateway/WsConfigHandlers.scala:336` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 399 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:1027` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 400 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:1097` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 401 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:1149` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 402 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:1197` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 403 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:1266` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 404 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:305` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 405 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:309` | 账号层 | 否 | `/projects) }` | 内容数据面（自动跟随有效根） |
| 406 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:939` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 407 | `src/main/scala/nebflow/gateway/WsFileOpsHandlers.scala:986` | 账号层 | 否 | `/projects).toString)` | 内容数据面（自动跟随有效根） |
| 408 | `src/main/scala/nebflow/gateway/WsSessionChatHandlers.scala:293` | 账号层 | 否 | `,` | 内容数据面（自动跟随有效根） |
| 409 | `src/main/scala/nebflow/gateway/WsSessionChatHandlers.scala:343` | 账号层 | 否 | `))` | 内容数据面（自动跟随有效根） |
| 410 | `src/main/scala/nebflow/llm/decision/JevSettings.scala:102` | 账号层 | 否 | `/secrets/name.trim` | 内容数据面（自动跟随有效根） |
| 411 | `src/main/scala/nebflow/llm/imagegen/ImageGenService.scala:158` | 账号层 | 否 | `/imagegen` | 内容数据面（自动跟随有效根） |
| 412 | `src/main/scala/nebflow/llm/imagegen/ImageGenService.scala:68` | 账号层 | 否 | `/secrets/name.trim` | 内容数据面（自动跟随有效根） |
| 413 | `src/main/scala/nebflow/llm/ModelRegistry.scala:75` | 账号层 | 否 | `/models.json` | 内容数据面（自动跟随有效根） |
| 414 | `src/main/scala/nebflow/Main.scala:115` | 跨层 | 是 | `}; different data roots are different instances, +` | daemon child 拒启文案 → machineRoot |
| 415 | `src/main/scala/nebflow/Main.scala:138` | 跨层 | 是 | `//dual-env read (Branding.env) + default resolution` | 根解析回落 —— `machineRoot` 的落点本身（契约 §1） |
| 416 | `src/main/scala/nebflow/Main.scala:336` | 跨层 | 是 | `.toString)` | `preemptOtherHome(port, root)` → machineRoot |
| 417 | `src/main/scala/nebflow/Main.scala:345` | 跨层 | 是 | `}; different data roots are different instances, +` | 拒启文案 → machineRoot |
| 418 | `src/main/scala/nebflow/Main.scala:384` | 账号层 | 否 | `, cfg.port.value, GatewayConfig.noBrowser)` | 内容数据面（自动跟随有效根） |
| 419 | `src/main/scala/nebflow/neblink/AgentIdentitySync.scala:274` | 账号层 | 否 | `/agents` | 内容数据面（自动跟随有效根） |
| 420 | `src/main/scala/nebflow/neblink/AgentIdentitySync.scala:84` | 账号层 | 否 | `/agents/rec.agent/avatar)` | 内容数据面（自动跟随有效根） |
| 421 | `src/main/scala/nebflow/neblink/AgentSessionSync.scala:499` | 账号层 | 否 | `/neblink/remote-agent-sessions.json))` | 内容数据面（自动跟随有效根） |
| 422 | `src/main/scala/nebflow/neblink/AttachUploadId.scala:12` | 账号层 | 否 | `/attach-uploads/s$uploadId.part`）。` | (注释/文档字符串) 口径说明，随有效根 |
| 423 | `src/main/scala/nebflow/neblink/DeviceCredentialStore.scala:219` | 账号层 | 否 | `is redirectable (setDataRoot); a val would` | (注释/文档字符串) 口径说明，随有效根 |
| 424 | `src/main/scala/nebflow/neblink/DeviceCredentialStore.scala:221` | 账号层 | 否 | `/neblink/device.json` | 内容数据面（自动跟随有效根） |
| 425 | `src/main/scala/nebflow/neblink/EgressWhitelist.scala:33` | 账号层 | 否 | `/neblink/captures` | 内容数据面（自动跟随有效根） |
| 426 | `src/main/scala/nebflow/neblink/EgressWhitelist.scala:36` | 账号层 | 否 | `/uploads` | 内容数据面（自动跟随有效根） |
| 427 | `src/main/scala/nebflow/neblink/EgressWhitelist.scala:41` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 428 | `src/main/scala/nebflow/neblink/EgressWhitelist.scala:69` | 跨层 | 是 | `)).toOption match` | 相对路径基准（第二参）—— 改 `os.pwd` 或显式项目根，见 §4.C |
| 429 | `src/main/scala/nebflow/neblink/FriendService.scala:1471` | 账号层 | 否 | `/attach-uploads/s$uploadId.part` | 内容数据面（自动跟随有效根） |
| 430 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:120` | 账号层 | 否 | `is redirectable (setDataRoot); a val would` | (注释/文档字符串) 口径说明，随有效根 |
| 431 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:122` | 账号层 | 否 | `/device.json` | 内容数据面（自动跟随有效根） |
| 432 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:1351` | 账号层 | 否 | `is redirectable (setDataRoot); a val would` | (注释/文档字符串) 口径说明，随有效根 |
| 433 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:1353` | 账号层 | 否 | `/peer-descriptions.json` | 内容数据面（自动跟随有效根） |
| 434 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:1382` | 账号层 | 否 | `is redirectable (setDataRoot); a val would` | (注释/文档字符串) 口径说明，随有效根 |
| 435 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:1384` | 账号层 | 否 | `/friend-remarks.json` | 内容数据面（自动跟随有效根） |
| 436 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:190` | 账号层 | 否 | `）的 scope` | (注释/文档字符串) 口径说明，随有效根 |
| 437 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:235` | 账号层 | 否 | `).` | (注释/文档字符串) 口径说明，随有效根 |
| 438 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:238` | 账号层 | 否 | `.toNIO.toAbsolutePath.normalize !=` | 内容数据面（自动跟随有效根） |
| 439 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:249` | 账号层 | 否 | `) else DefaultScope` | 内容数据面（自动跟随有效根） |
| 440 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:798` | 账号层 | 否 | `is redirectable (setDataRoot); a val would` | (注释/文档字符串) 口径说明，随有效根 |
| 441 | `src/main/scala/nebflow/neblink/NeblinkModel.scala:800` | 账号层 | 否 | `/neblink/config.json` | 内容数据面（自动跟随有效根） |
| 442 | `src/main/scala/nebflow/neblink/PortalBindingState.scala:34` | 账号层 | 否 | `/neblink/binding-state.json` | 内容数据面（自动跟随有效根） |
| 443 | `src/main/scala/nebflow/neblink/PortalUplinkBackpressure.scala:207` | 账号层 | 否 | `/portal-uplink-deadletter` | 内容数据面（自动跟随有效根） |
| 444 | `src/main/scala/nebflow/neblink/ProfileDescriptions.scala:74` | 账号层 | 否 | `` 可被 `setDataRoot` 重定向，` | (注释/文档字符串) 口径说明，随有效根 |
| 445 | `src/main/scala/nebflow/neblink/ProfileDescriptions.scala:80` | 账号层 | 否 | `/profile-descriptions.json` | 内容数据面（自动跟随有效根） |
| 446 | `src/main/scala/nebflow/perception/PerceptionService.scala:1057` | 账号层 | 否 | `/secrets)` | 内容数据面（自动跟随有效根） |
| 447 | `src/main/scala/nebflow/perception/PerceptionService.scala:1058` | 账号层 | 否 | `/secrets/name)` | 内容数据面（自动跟随有效根） |
| 448 | `src/main/scala/nebflow/perception/PerceptionService.scala:1068` | 账号层 | 否 | `/secrets/account.authCodeSecret` | 内容数据面（自动跟随有效根） |
| 449 | `src/main/scala/nebflow/perception/PerceptionService.scala:1092` | 账号层 | 否 | `/secrets/a.authCodeSecret` | 内容数据面（自动跟随有效根） |
| 450 | `src/main/scala/nebflow/perception/PerceptionService.scala:1094` | 账号层 | 否 | `/secrets).delete(a.authCodeSecret)` | 内容数据面（自动跟随有效根） |
| 451 | `src/main/scala/nebflow/perception/PerceptionService.scala:478` | 账号层 | 否 | `/secrets/account.authCodeSecret` | 内容数据面（自动跟随有效根） |
| 452 | `src/main/scala/nebflow/perception/PerceptionStore.scala:177` | 账号层 | 否 | `/perception` | 内容数据面（自动跟随有效根） |
| 453 | `src/main/scala/nebflow/service/ConfigService.scala:236` | 账号层 | 否 | `), merged)` | 内容数据面（自动跟随有效根） |
| 454 | `src/main/scala/nebflow/service/ConfigService.scala:278` | 账号层 | 否 | `), updated.spaces2)` | 内容数据面（自动跟随有效根） |
| 455 | `src/main/scala/nebflow/service/ConfigService.scala:312` | 账号层 | 否 | `), updated.spaces2)` | 内容数据面（自动跟随有效根） |
| 456 | `src/main/scala/nebflow/service/ConfigService.scala:33` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 457 | `src/main/scala/nebflow/service/ConfigService.scala:355` | 账号层 | 否 | `), updated.spaces2)` | 内容数据面（自动跟随有效根） |
| 458 | `src/main/scala/nebflow/service/ConfigService.scala:410` | 账号层 | 否 | `), updated.spaces2)` | 内容数据面（自动跟随有效根） |
| 459 | `src/main/scala/nebflow/service/ConfigService.scala:469` | 账号层 | 否 | `), updated.spaces2)` | 内容数据面（自动跟随有效根） |
| 460 | `src/main/scala/nebflow/service/ConfigService.scala:557` | 账号层 | 否 | `), updated.spaces2)` | 内容数据面（自动跟随有效根） |
| 461 | `src/main/scala/nebflow/service/ConfigService.scala:603` | 账号层 | 否 | `/secrets/name.trim` | 内容数据面（自动跟随有效根） |
| 462 | `src/main/scala/nebflow/service/ConfigService.scala:660` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 463 | `src/main/scala/nebflow/service/ConfigService.scala:857` | 账号层 | 否 | `/model-presets.json` | 内容数据面（自动跟随有效根） |
| 464 | `src/main/scala/nebflow/service/ConfigSnapshot.scala:16` | 账号层 | 否 | `/backups` | 内容数据面（自动跟随有效根） |
| 465 | `src/main/scala/nebflow/service/ConfigSnapshot.scala:53` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 466 | `src/main/scala/nebflow/service/NebflowBackup.scala:24` | 账号层 | 核 | `（根本身）` | `private val home`（对象级冻结） |
| 467 | `src/main/scala/nebflow/shared/config.scala:752` | 账号层 | 否 | `and the config-file dual-read must be` | (注释/文档字符串) 口径说明，随有效根 |
| 468 | `src/main/scala/nebflow/shared/config.scala:755` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 469 | `src/main/scala/nebflow/shared/FileHistory.scala:136` | 账号层 | 否 | `), evaluated` | (注释/文档字符串) 口径说明，随有效根 |
| 470 | `src/main/scala/nebflow/shared/FileHistory.scala:14` | 账号层 | 否 | `— CLI --home flag/` | (注释/文档字符串) 口径说明，随有效根 |
| 471 | `src/main/scala/nebflow/shared/FileHistory.scala:140` | 账号层 | 否 | `/history).toNIO,` | 内容数据面（自动跟随有效根） |
| 472 | `src/main/scala/nebflow/shared/InstanceScope.scala:19` | 账号层 | 否 | `]]）+ **R 自己的端口**：` | (注释/文档字符串) 口径说明，随有效根 |
| 473 | `src/main/scala/nebflow/shared/InstanceScope.scala:57` | 跨层 | 是 | `, os.home, Branding.homeDirName, PathUtil.LegacyHomeDirName)` | `liveCaller` 的 root（端口判定 + overridden 判据）→ 必须 machineRoot |
| 474 | `src/main/scala/nebflow/shared/MemoryPaths.scala:11` | 跨层 | 否 | `(注释) ` 是**进程级可换根**的` | (注释) 运行时根可换的说明 |
| 475 | `src/main/scala/nebflow/shared/MemoryPaths.scala:19` | 跨层 | 否 | `(注释) `）—— 隔离实例下的真实位置；` | (注释) 运行时数据根 = 有效根 |
| 476 | `src/main/scala/nebflow/shared/MemoryPaths.scala:36` | 跨层 | 否 | `.toString)` | 运行时根识别（= 有效根，账号层自动成立） |
| 477 | `src/main/scala/nebflow/shared/MemoryPaths.scala:8` | 跨层 | 否 | `(注释) `，为的是隔离实例` | (注释) 记忆路径基准 = 有效根 |
| 478 | `src/main/scala/nebflow/shared/MemorySnapshot.scala:53` | 账号层 | 否 | `/memory-backups` | 内容数据面（自动跟随有效根） |
| 479 | `src/main/scala/nebflow/shared/MemorySnapshot.scala:61` | 账号层 | 否 | `).toString` | 内容数据面（自动跟随有效根） |
| 480 | `src/main/scala/nebflow/shared/MemoryStore.scala:38` | 账号层 | 否 | `/User.md` | 内容数据面（自动跟随有效根） |
| 481 | `src/main/scala/nebflow/shared/MemoryStore.scala:41` | 账号层 | 否 | `/Soul.md` | 内容数据面（自动跟随有效根） |
| 482 | `src/main/scala/nebflow/shared/MemoryStore.scala:45` | 账号层 | 否 | `/agents/agentName/memory.md` | 内容数据面（自动跟随有效根） |
| 483 | `src/main/scala/nebflow/shared/MemoryStore.scala:60` | 账号层 | 否 | `/teams/teamName/agents/agentName/memory.md` | 内容数据面（自动跟随有效根） |
| 484 | `src/main/scala/nebflow/shared/MemoryStore.scala:72` | 账号层 | 否 | `` never moves, so the binding is permanent and` | (注释/文档字符串) 口径说明，随有效根 |
| 485 | `src/main/scala/nebflow/shared/MemoryStore.scala:80` | 账号层 | 核 | `（根本身）` | `@volatile private var boundRoot`（有 bind 面） |
| 486 | `src/main/scala/nebflow/shared/MemoryStore.scala:92` | 账号层 | 否 | `（根本身）` | 内容数据面（自动跟随有效根） |
| 487 | `src/main/scala/nebflow/shared/OutputBlockGate.scala:35` | 账号层 | 否 | `)` | 内容数据面（自动跟随有效根） |
| 488 | `src/main/scala/nebflow/shared/RulesStore.scala:24` | 账号层 | 核 | `/folders` | `private val baseDir`（对象级冻结） |
| 489 | `src/main/scala/nebflow/social/FeishuCredentials.scala:81` | 账号层 | 否 | `]]).` | (注释/文档字符串) 口径说明，随有效根 |
| 490 | `src/main/scala/nebflow/social/outbound/RichFeishuAdapter.scala:450` | 账号层 | 否 | `/tmp/outbound-render` | 内容数据面（自动跟随有效根） |

