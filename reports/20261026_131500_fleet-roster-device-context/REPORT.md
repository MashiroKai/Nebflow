# REPORT — 台账 #913 + #917 两小件一批（多设备交互上下文 + 项目描述可更新）

批次：fleet-roster + project-device-annotation + enforce-explicit-device + project-description-update（单批四件，作者令 2026-10-26 12:37）
分支：`delegate/delegate-nebflow-c2e6b6d0`（基于 KAI main `21f19dfaf`；Mac bundle 第 0 步已核——`kai-sync-ec5c.bundle` main = `ba6963d72`，已是本地 main 祖先，无新前进可吸收）

## 交付对照（作者令 ①②③④ → 落点）

### ① 设备表进 agent 上下文（fleet roster）
- **渲染单点**（新件，纯函数）：`src/main/scala/nebflow/core/tools/FleetRoster.scala`
  - 数据源 = 本机 `device.json` 身份（DeviceIdentity：deviceId/名称/platform/**capabilities**）+ presence 名册（`NeblinkService.peers` = 在线集）+ 配对登记表（`neblink/devices.json`，`DeviceRegistry`）+ 画像 store（`device-profiles.json`）；四源按 deviceId 去重，presence 在场优先。
  - 行格式（一行一台，无列表符号——devicesDelta 差量按 `[;\n]` 切行，条目保持 `+<entry>`/`-<entry>` 净形）：
    `KAI (40742cd4) [local · windows · v2026.10.26-beta.1] - 描述 · caps: git,node`
    `Mac (dd8cfd22) [online · macos] · caps: java [cwd=… | os=Darwin …]`
    `OldBox (aaaabbbb) [offline · linux]`
  - **上下文预算**：行数超 `MaxRows=24` 截断 + 指针行 `(+N more device(s) not shown — full roster: GET /api/neblink/status)`；capabilities 只列**键名**（值是绝对路径，spec 钉「值不进名册」）；对端无引擎版本 wire 字段 ⇒ 对端行不显示 version（诚实不装知），本机行带 `nebflow.Version.string`。
- **装配点**：`AgentSessionExecution.deviceInfoSnapshot`（`# Devices` 段，order 600）——旧的单行 `;` 拼接替换为逐台一行名册；**设备增删/上下线自动反映沿既有机制零新增**（30s memo + `devicesDeltaLines` 差量提醒 + F-2 成员键计数；成员键域 = 本机 + 在线对端，与改前口径一致）。
- **端口面**：`DeviceIdentityView`（core/ports.scala）按「消费点实读字段」纪律增员 `platform`/`capabilities` 两成员（neblink.DeviceIdentity 字段即成员，原地满足；唯一实现点，无其他实现类需改）。

### ② 项目标注所属设备
- **存储**：`ProjectDef.device: Option[String]`（ProjectTypes.scala，追加尾位）——值域 `self` | 对端 deviceId；存量无键解码 None（withDefaults 零迁移）。
- **写入面**：`ProjectStore.createWithScaffold` 新定义带值（本机 = `self`、设备项目 = `origin.device`）；**回填** = `ProjectStore.load` 缺键时手术式原位插键（读原始 Json → deepMerge → AtomicJson，同 archive 形状；幂等、失败只 WARN 绝不阻断加载）——存量项目在下次 load/启动挂载时自动补齐。
- **判定单点**：`ProjectStore.effectiveDevice`（显式 device 字段 > origin 回导 > self）+ `displayDevice`（self→local；deviceId→名册现查，未命中 = 原样 id 诚实降级）。全部消费面禁各自判。
- **携带面**（四口全带）：
  1. `Project` 工具新增只读 `action="list"`（逐行 `- name [device=…, mounted] workspace — desc`）与 `action="show"`（全量定义：device 存储值+显示名、origin、brainRoot、挂载态）；
  2. agent 上下文的 Mounted Projects 表（根会话 order 630）：跨设备项目渲染 `- stand-kai (on device KAI): desc`（新 `MountedProjectList.renderLinesWithDevice`；本机项目维持原行形零噪声）；
  3. REST `GET /api/projects` 行 additive `device` 键（存储值；老前端忽略未知键零破坏）；
  4. project.json 落盘键本身。

### ③ 强制显式设备参数（`fleet.enforceExplicitDevice`）
- **开关**（新件 `core/device/FleetConfig.scala`）：`nebflow.json` 顶层 `fleet.enforceExplicitDevice`，**缺省 false = 现状零变化**（热读仿 ExecutorRegistry.readSettings 先例：坏形/缺失回落缺省绝不抛；2s memo 削热路径峰）。
- **引擎闸**：`AgentSessionExecution.executeToolInner`——开关开 ∧ 六件远端工具 ∧ 调用未显式带 `device` ⇒ **直接报错零执行**（hook-Block 先例同形），文案列可用设备名（本机名(=local) + 在线对端）+ 显式 `device="local"` 逃生门说明；设备项目会话在场时点名会话默认设备（语义仍强制显式，告诉模型该写什么）。
- **schema 面同源**：`RemoteExecutor.augmentSchema` 的 device 描述二态——关 = 既有文案逐字；开 = REQUIRED 措辞（缺省调用会被拒）。
- **提示面同源**：`# Devices` 尾行提示二态（同文件 FleetRoster.hintLine）。
- **brief 面同源**：Delegate 设备项目 workspace 行 + 非（Flag on）设备项目 workspace 行尾追加纪律句。
- **AGENTS.md 模板节**：`ProjectCreateService.AgentMdTemplateFull`（基模板 + `FleetConfig.agentsMdSection`）——开关关 = 空节 = 与基模板字节相等（W3 裁定的空模板零变化）；开 = 新脚手架带「Fleet device discipline」节（六件强制显式 + Project(action="list") 查询面）。消费点五处全切（本地 create/幂等重挂补缺/createRemote/远端脚手架 DeviceProjectMount），禁有调用点直用基模板。

### ④ Project 工具 description 可更新
- `ProjectStore.updateDescription(name, description)`：**只改 description 一个字段**的手术式原位写（deepMerge / 清空 = 删键；其余字段逐字节稳定）；幂等（同值 → `Right("unchanged")` 零写入）；报错面照 archive/markUnmounted 族（Invalid project name / not found / Corrupt project.json）。
- `Project` 工具 `action="update"`（requires name + description；archived/unmounted 项目允许改 description——定义级元数据，非挂载动作）。工具 schema enum 扩为 `create|list|show|update|unmount`。

## 门禁读数

（编译：`sbt compile` 本批触及文件零错误——并行 #919 热更新批的 `cli/DetachedApply.scala`、`cli/UpdateStage.scala` 同仓在写，其编译期错误不属本批面；等待其收敛后全量 `Test/compile` 与目标 suite 读数见下。）

## 判据声明（实施裁定的自报口径）
1. 「Project 工具的 list/show 输出」——工具面此前**不存在** list/show（仅 create/unmount），本批按任务口径**新增**两只读 action 作为设备标注的查询面（additive，零既有语义改动）。
2. 强制模式的**会话默认设备**：设备项目会话原本缺省路由对端；开关开后缺省不再兜底（点名要求显式）——作者令「每次命令都必须要强制使用设备参数」逐字口径。
3. 显式 `device="local"` 仍合法（被禁的是「不写」，不是「本机」）——错误文案与 schema/提示/brief 四面同源声明。
4. 版本字段：对端 presence wire 不携带引擎版本 ⇒ 名册对端行不显示 version；若后续 wire 加版本，本渲染面单点补。
5. AGENTS.md 模板节只进**新脚手架**（缺件即补、既有永不覆盖的模板纪律不变）；既有工作区的硬约束由引擎侧报错承担，不回写既有 AGENTS.md。
