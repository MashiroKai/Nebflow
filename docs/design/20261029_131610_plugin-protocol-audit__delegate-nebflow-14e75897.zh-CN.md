---
type: audit-report
status: final
---

# 插件协议现状盘点（2026-10-29 作者令「把我们目前插件协议检测一下」）

> **定位**　纯现状盘点（读码为准、逐条带 `路径:行号` 锚），零代码改动、零实施建议混排；改进建议单列 §8 并标注「未与作者对齐」。基线 = 本仓 main `5c2ad9b57`（2026-10-29 午后，含双模式批的插件三层规范 `8b5138640` 与卫生批 `1dcbb1517`；当日 origin 不可达，以本地 main 为准）。实例实证 = 本机 `~/.stand`（data root）现读快照（§7）。
>
> **术语速查**　**manifest**（插件根的 `plugin.json` 声明文件）；**digest**（目录内容树 SHA-256——按相对路径排序、逐文件 `rel\0<字节>\0` 喂入，改任何字节即变）；**deny-list**（点名封禁表 `nebflow.json → plugins.revoked`，default-allow 下唯一止损手段）；**在位即信任**（装载判定 =「目录存在 ∧ 未被封禁」，2026-09-13 作者令「装了就是信任」）；**seed**（打进分发 jar 的种子资源树 `src/main/resources/seed/plugins/`）；**实例副本**（运行时数据根下的 `~/.stand/plugins/<name>/`，引擎实际装载的面）；**delegate**（委托出去跑单个任务的后台子会话）；**分发器**（把任务拆成节点图派发的编排会话）；**flow map**（持久节点图）；**fail-open**（分配失败时空集放行、绝不阻塞派发）。更多见 [[glossary]]。

目录：

- [0. 协议总览一页（规则表）](#0-协议总览一页规则表)
- [1. 插件定义面（manifest 与 schema）](#1-插件定义面manifest-与-schema)
- [2. 三能力面（skills / MCP servers / tool grants）](#2-三能力面skills--mcp-servers--tool-grants)
- [3. 挂载链（谁挂、何时挂、双副本关系）](#3-挂载链谁挂何时挂双副本关系)
- [4. 生命周期（安装/更新/卸载/校验/失败面）](#4-生命周期安装更新卸载校验失败面)
- [5. 安全面（凭据 / grants 边界 / 引擎内置面）](#5-安全面凭据--grants-边界--引擎内置面)
- [6. 消费面（声明式挂载 / 自动分配 / 无插件边界）](#6-消费面声明式挂载--自动分配--无插件边界)
- [7. 已知缝隙清单（与今日实证对齐）](#7-已知缝隙清单与今日实证对齐)
- [8. 改进建议（未与作者对齐）](#8-改进建议未与作者对齐)

---

## 0. 协议总览一页（规则表）

| # | 规则点 | 现行口径（一句话） | 权威锚点 |
|---|---|---|---|
| 1 | manifest 必填 | `$schema`（canonical 1.0.0 逐字）+ `name`（§5.5 命名约束），缺失/非 canonical → **拒载** | PluginRegistry.scala:110,133-146,640-661 |
| 2 | manifest 可选 | version/description/author(object)/homepage/repository/license/keywords/useWhen/extensions；类型违规 = fatal，未知键 = 宽容告警 | PluginRegistry.scala:666-696,631-636 |
| 3 | 装载下限 | `skills/` 与 `mcp.json` **至少其一**，全无 → 拒载 | PluginRegistry.scala:834-838 |
| 4 | 包根条目闭合集 | `plugin.json` / `mcp.json` / `skills` / `org.nebflow` / `Plugin.md` 五件；未知条目告警跳过 | PluginRegistry.scala:600-609 |
| 5 | 信任模型 | **在位即信任** + **点名封禁**（deny-list）；判定顺序先查封禁再默认受信，不可交换 | PluginRegistry.scala:856-866；PluginBlockPolicy.scala:10-33 |
| 6 | 派发许可独立成面 | 内容信任 ≠ 派发许可：`effective = trusted ∧ (authorEnabled ∨ transition)`；关闭只挡未来派发 | PluginDispatchPolicy.scala:127-128 |
| 7 | 官方身份 | `nebflow-` 前缀 = 保留 namespace，digest ∉ 分发内置允许列表 → 拒载 `OFFICIAL_IMPERSONATION`（fail-closed） | OfficialPackages.scala:51-54,115-116 |
| 8 | 工具白名单 | 插件只能授予 `{WebSearch, WebFetch, Curl}` 三件既有 builtin 工具，越界 → 拒载 `PLUGIN_TOOLS_ILLEGAL`；编排类永不入列 | PluginRegistry.scala:92-104,774-780 |
| 9 | MCP 传输 | `stdio` / `streamable-http` 实现；`sse` 声明合法但客户端未实现 → 逐条 skip+告警 | PluginRegistry.scala:1064-1093 |
| 10 | 注入物 | 分配注入的提示词 = **包根 Plugin.md 全文**（`<plugin name="…">` 块）；SKILL.md 退役为按需查阅件 | PluginInjection.scala:189-221 |
| 11 | 双副本 | seed（jar 内资源树）为 canonical 来源，实例副本按 digest 仲裁同步：干净 → 备份+镜像+重审；用户改过 → 保留实例 | SeedService.scala:456-566 |
| 12 | 失败语义 | 分配失败 = fail-open；解析/注入/MCP 启动失败 = 节点级失败（failNode），绝不静默降级 | PluginInjection.scala:44-47；PluginMcpManager.scala:19-27 |
| 13 | 消费入口 | 分发器按目录段声明 `NodeEdit plugins:[…]`；delegate/链步空声明走自动分配（JeV→LLM 兜底→fail-open） | NodeEditTool.scala:929-951；PluginInjection.scala:68-105 |
| 14 | 分发器自身 | dispatcher 模式 delegate 本体**零插件**（`dispatcher-off`），节点插件在链登记时按任务自动分配；ChainWrite 拒绝 `nodes[].plugins` 键 | DelegateTool.scala:784-794；ChainWriteTool.scala:305-335 |

---

## 1. 插件定义面（manifest 与 schema）

### 1.1 目录格式（Agent Plugins 1.0.0）

包 = 数据根 `plugins/` 下一个目录（`PluginRegistry.pluginsDir`，PluginRegistry.scala:90）。协议声明对齐 Agent Plugins 1.0.0 官方 spec（文件头注 PluginRegistry.scala:15-39），五件已知条目（PluginRegistry.scala:600）：`plugin.json`（必需）、`skills/`（可选）、`mcp.json`（可选）、`org.nebflow/`（可选，仅 `tools.json` + `viewers.json` 两个合法子条目，PluginRegistry.scala:604-608）、`Plugin.md`（可选在场、注入面必需——见 §2.1 与 [[plugin-md-required-file-spec.zh-CN]]）。

### 1.2 manifest 字段与 fatal 面

- **必填 + canonical**：`$schema` 必须逐字等于 `https://agent-plugins.org/schemas/1.0.0/plugin.schema.json`（`CanonicalSchema`，PluginRegistry.scala:110；校验 :640-647，非 canonical → `PLUGIN_SCHEMA_UNSUPPORTED` 拒载）；`name` 必填且须过 §5.5 命名约束——1-64 字符、`a-z 0-9 - .`、首尾字母数字、禁连续 `--`/`..`（`validPluginName`，:486-491；校验 :650-658）。目录名 ≠ manifest name 时以 manifest 为准并告警（:660-661）。
- **可选元数据**：`version`/`description`/`homepage`/`repository`/`license` 为 string（present 必须是 string，类型违规 = manifest invalid → 拒载，`stringField` :895-898）；`author` 仅允许 `{name?, email?, url?}` 三 string 字段的 object（:684-696）；`keywords` 为 string 数组（:673-678）。**version 不做 semver 强制**——协议明示 MUST NOT 因非 semver 拒载，version bump 即换 digest 重进审计（:663-666 注）。
- **增量键**（双模式批 2026-10-28，`8b5138640`）：`useWhen`——「何时该用本包」一句陈述；**容忍读法**（缺键 = None、类型错 = 忽略，增量键永不成为拒载面，:679-681 与 PluginDef 字段注 :233-239）。`capability` 键已 deprecated（描述单源批 2026-09-10）：登记在 `KnownManifestKeys` 里存量包不报错，但读取/承载/渲染逻辑全部退役，`description` 是唯一描述源（:127-146）。
- **未知字段**：manifest 未知键、包根未知条目、`org.nebflow` 未知子条目一律「宽容忽略 + 告警」（前向兼容 §B.8-5，:601-609,631-636）。

### 1.3 版本与兼容语义

版本语义 = **内容 digest 而非版本号**：`computeDigest` 对整目录内容树算确定性 SHA-256（路径排序、`rel\0<bytes>\0` 喂入，:426-438）；manifest 任何字段改动都变 digest（:422-424 注）。兼容 = canonical schema 锁定 + 未知宽容：客户端只识别 1.0.0 canonical 标识，不支持声明版本 → 拒绝并报 unsupported version（§5.2/§5.3）。机械校验镜像 `validate_plugin.py`（nebflow-plugin-creator 内，:1-150）把同一组规则做成三档 profile（official/creator/user），自我定位是「装载器上游前置镜像，冲突时以装载器为准回改脚本」。

## 2. 三能力面（skills / MCP servers / tool grants）

### 2.1 skills

- **结构**：`skills/<skill>/SKILL.md`——一级子目录含精确命名 `SKILL.md` 的常规文件才算一个 skill；缺 SKILL.md → skip+告警（§7.1，:707-721）；frontmatter 须含非空 `name` + `description`（agentskills.io 一致性门，`readConformantSkill` :916-924），不符 → skip+告警不拒载。skill id = `<plugin>/<skill>`（:717）。
- **注入路径（现状）**：作者令 2026-10-21「plugin.md = 插件必要文件机制」后，**分配注入的提示词 = 包根 Plugin.md 全文**，包裹为 `<plugin name="…">…</plugin>` 块，`{{data_root}}` 占位渲染为本实例数据根；`Plugin.md` 缺失/不可读 → Left（节点级失败，不静默降级）；小写 `plugin.md` 回退同认（PluginInjection.scala:189-221）。旧「逐 skill 拼 SKILL.md 全文」链已退役——SKILL.md 回归按需查阅件（面板胶囊点击打开）。
- **Plugin.md 与装载器的关系**：装载器 known-set 已把 `Plugin.md` 收为正字（卫生批 2026-10-28 六-1，PluginRegistry.scala:592-600）；小写名注入面同认但装载面维持「恰一文件、逐字命名」纪律。

### 2.2 MCP servers

- **声明**：`mcp.json` = `{"$schema": <canonical mcp>, "mcpServers": {…}}`（`CanonicalMcpSchema`，PluginRegistry.scala:116）；顶层未知字段告警忽略；`$schema` 缺失/非 canonical → **MCP 组件整体 invalid**（告警 + 无 MCP，插件继续装载——§6.2 组件级边界，`parseMcpJson` :932-969）。
- **server entry 校验**（`validateServerEntry` :977-1097）：`type ∈ stdio|streamable-http|sse`；stdio 要求 `command` 单 token 无空白、`./` 相对命令按插件根解析 + 围栏；`env`/`args`/`headers` 类型封闭；env 禁声明 `PLUGIN_ROOT`/`PLUGIN_DATA`（客户端注入占位，§9.1，:1028-1032）；`cwd` 须 `./`/`${PLUGIN_ROOT}`/`${PLUGIN_DATA}` 前缀并归一为占位形态（:1033-1052）；http URL 绝对、无 userinfo/fragment、**非 loopback 必须 https**（`validMcpUrl` :1103-1114）。**单条 entry 违规只跳该条**（§7.2.2 隔离边界）。`sse` 传输显式 skip+告警（客户端未实现，:1073-1079）。
- **进程/传输面**（PluginMcpManager.scala）：plugin 级独立 McpManager 实例（不复用全局实例与开关面，:21-22）；**引用计数**——同一插件被多会话分配只起一份 server，最后一个会话 release 后关闭（:12-18）；启动超时 15s（:60-62）；占位符展开 + 环境注入——`PLUGIN_ROOT` = 插件根、`PLUGIN_DATA` = `<dataRoot>/plugin-data/<plugin>/`（启动前幂等创建，:107-120）；工具注册名 `mcp__plugin_<plugin>_<server>__<tool>`（:240-241）。
- **凭据引用方式**：mcp.json `env` 值为**字面量明文**，随包落盘——引擎侧只有**红标启发**（键名命中 `password|secret|token|api key|…` → 审批清单红标项，`CredentialKeyPattern` :158、`approvalFlags` :351-364）与**键名打码**（`approvalManifest` 的 `envKeys` 只出键名不出值，:331）。「值不落盘」的指针纪律（keyRef → secrets 0600 文件）目前只存在于 **AskUser secret 题**通道（见 §5.1），mcp.json env 本身没有指针形态——样例包 imagegen 用的是「复用实例 nebflow.json 顶层 imagegen 节」的约定而非协议字段。

### 2.3 tool grants

- **声明**：`org.nebflow/tools.json`（或 manifest `extensions["org.nebflow/tools"]` 指定文件名，命名空间相对解析为主、插件根相对兼容，两处均围栏校验，:735-760）；内容 = `{"tools": ["WebSearch", …]}`。
- **粒度与白名单**：`BuiltinToolWhitelist = {WebSearch, WebFetch, Curl}`（:104）——插件**只能授予既有 builtin 工具且限于白名单**；不能发明新工具、不能授予编排类（Mail/NodeEdit 等永不进白名单，§C.1 静态矩阵守角色边界）；**Pop 已移出**（2026-09-10 作者裁定收归 Nebula 专属，堵「第三方包声明 tools:["Pop"] 绕过剥离面」的越权面，:98-103）。越界 → **整包拒载** `PLUGIN_TOOLS_ILLEGAL`（:774-780）。
- **执行侧纵深**：`buildAllowedToolSet` 在全部角色过滤与 NebulaExclusiveTools 剥离**之后**再按白名单过滤一次——「损坏的 AgentDef 也造不出白名单外授予」（AgentSessionExecution.scala:2422-2430）。TaskBoard/node_report/delegate_report/AskUserQuestion/Mail 等编排与用户面全部按**会话身份**由引擎挂载，注记明写「插件声明不授能」（:2446-2447,2465-2466,2489）。
- **MCP 工具面的授权源**：节点分配链（NodeEdit plugins → 信任门解析 → PluginMcpManager 启动）是唯一授权源；allowedSet 按 `mcp__plugin_<p>_<s>__` 前缀从注册表捞取并入，未注册名自然落空（AgentSessionExecution.scala:2361-2378）；收敛三角色 agent 的 agent.json `mcpServers` 声明已退役——**node.plugins 分配是 MCP 唯一通道**（:2356-2360）。

## 3. 挂载链（谁挂、何时挂、双副本关系）

### 3.1 谁有权挂载

**挂载权只在派发侧**：`NodeEdit` 工具的 `plugins` 参数（replace-on-provide）是声明式挂载的唯一写点，且仅分发器/创建者 delegate 身份持有 NodeEdit（节点会话拿不到）。自动分配腿（§6.2）是引擎代行、不是节点自挂。只读查询面 `tools/stand-plugins` CLI 对所有身份开放，但「只读零副作用，绝不提供挂载/改配置通道——delegate 不能给自己热挂」（tools/stand-plugins:7-9；同口径注入提示词 Defaults.scala:136-137）。

### 3.2 挂载时机（dispatch 时注入）

- **闸 A（新派发决策）**：NodeEdit 落库时刻 `dispatchFaceCheck` 逐名校验 `effective(name, trusted)`——被封禁 → `PLUGIN_BLOCKED`、被作者关闭 → `PLUGIN_DISPATCH_DISABLED`（NodeEditTool.scala:929-951）；plugins 键在引擎总闸 `plugins.enabled=false` 时整参数忽略（:664-679）。
- **闸 B/C/E（装载门）**：spawn / crash-recovery resume / loop 双会话在状态翻转前走 `prepareNodePlugins`（NodeStarter.scala:337-367,419-427,511-516）——只判内容面可用性（`resolve` = 存在 ∧ 未封禁），**内容变更不拒启动**，派发面不在判定内（:499-510）。解析产出 `<injected-plugins>` 块拼进节点首条消息、MCP 插件清单与 builtin 授予；随后 `pluginMcp.acquire` 起 server，任一失败 → failNode（:351-366）。
- **闸 D（运行期重验）**：TtlTick 30s 驱动 `PluginMcpManager.revalidate`——只有**封禁**或**包从注册表消失**才停在飞 MCP 并向持有会话发系统提醒；digest 漂移**不停飞**（「装了就是信任」字面落地，PluginMcpManager.scala:152-209）；派发面变更在此零动作。

### 3.3 双副本关系与同步规则（seed ↔ 实例）

- **两副本**：种子副本 = jar 内资源树 `src/main/resources/seed/plugins/`（`OfficialPackages.BuiltinRoot`，OfficialPackages.scala:57；sbt 期 = target classes 树，分发期 = jar 条目）；实例副本 = `<dataRoot>/plugins/<name>/`（引擎唯一装载面，PluginRegistry.scala:90）。
- **首装**：`seed/manifest.json` 的 `plugins:` 条目 = 默认预装集（现 7 包：visual-report、slideblocks、nebflow-plugin-creator、web-search-toolkit、browser-use、computer-use、document-production）；fresh home 完整播种、已存在跳过不覆盖，装后即写审批记录（SeedService.scala:297-345——approve 落 digest 基准，否则该包永远落入「无基准 ⇒ 保守不覆盖」分支，:331-333 注）。
- **一致性 reconcile（每次 boot，所有分支都跑）**：默认集逐包 seed↔runtime digest 比对仲裁（SeedService.scala:62-86,456-566）：
  1. 实例缺失 ∧ 无 userRemoved 标记 → 自愈安装 + approve；带 `userRemoved` 墓碑 → 不装回（存在台账 `.plugin-presence.json`，:87-104,127-208）；
  2. digest 相等 → 无动作（常态静默）；
  3. 不等 ∧ 实例 digest == 信任记录 digest（干净快照）→ **备份到 `plugins-backups/<ts>_pre-sync-<name>/`（逐文件 pre-sha256）→ 种子镜像覆盖（含删除实例独有文件）→ 自动重审**（refreshFromSeed，:502-529）；
  4. 不等 ∧ 实例 digest ≠ 信任记录 → **用户改过 > 种子**，跳过 + WARN（:539-543）；
  5. 无信任记录 → 无仲裁基准，保守不覆盖 + WARN（:544-550）；
  6. **官方 namespace 例外**（2026-10-21 诊断批）：`nebflow-` 保留前缀包 runtime ≠ seed 一律走出厂字节赢——因为装载层 OfficialPackages 硬闸只认出厂 digest，「用户改过保留」在保留 namespace 上没有合法存在形态（:493-501,534-538,544-545）。
- **迭代面边界**：reconcile 只对 **manifest 默认集**收敛，种子树全集可手动装、永不因 reconcile 落盘（:429-436）。**种子 = canonical 源，但只对「干净」实例生效**——实例直改后种子更新即被仲裁挡住（无版本管理，见 §7）。
- **同源性闸**：官方允许列表对种子树**现算** digest（同算法、零漂移），允许列表信任根 = 分发载体（OfficialPackages.scala:21-34）；jar 分支与 file: 分支的路径/digest 等价性由 OfficialIdentitySpec 对真实内置包逐包断言（:193-201）。

## 4. 生命周期（安装/更新/卸载/校验/失败面）

- **安装路径三条**：① seed 首装（§3.3）；② 缺失自愈（reconcile）；③ 手动 `stand plugin add <git-url|本地路径>`——git 源 `--depth 1` 克隆、本地 copy，manifest name 为准落目录，同名已存在拒绝不覆盖，**落盘即生效**（在位即信任：下个扫描周期可派发，PluginRegistry.scala:1215-1297；CLI PluginCommand.scala:90-104）。点路径/cwd 祖先守卫防整树误拷（:1246-1316）。
- **更新路径**：**没有独立 update 命令**——更新 = 种子 reconcile 的 digest 仲裁（干净才镜像）或手动删目录重装。面板/REST/CLI `approve` 只是刷新审计记录 + 前移 seed 基准（不再决定装载，:1183-1210）。
- **卸载路径**：**产品无卸载入口**（SeedService.scala:100-103 诚实申报）——删除只能带外 `rm -rf`；存在台账把它判为「用户定向删除」落墓碑、自愈面跳过，目录再次出现时清墓碑恢复纳管；软移除 = 封禁（revoke），包仍在盘上但出目录、拒装载、停在飞。
- **校验**：装载期全套（§1/§2）+ **无签名**——信任根 = 分发载体（dmg/jar 本身）+ 目录 digest；审批记录 `{sha256, approvedAt, files}` 只剩两个用途：面板变更摘要数据源 + seed 仲裁基准（:61-65）。**漂移检测 = 非拦截可见性**三出口：REST 字段 `contentChanged`、目录段尾点名注记、启动/重扫健康摘要 `[content-changed] <name>: 记录 digest → 当前 digest`（:67-83,1394-1466）；内容变更不拦装载。
- **扫描节奏**：进程内 mtime 缓存——目录树任一 mtime 变化即全量重扫，改后即时生效（:493-536）；封禁表在 `nebflow.json`（不在树内）→ 封禁/解封写面必须显式失效缓存（PluginBlockPolicy.scala:30-33,92,117）。
- **失败面对 dispatch 的影响分层**：**分配**失败（JeV/LLM 两臂全败、目录空、端口未装、autoAllocate 关）= fail-open/惰性态，空集注入派发照走（PluginInjection.scala:44-47,68-105）；**解析/注入**失败（选中包被封禁/Plugin.md 不可读）= Left → failNode/显式拒 spawn（DelegateTool.scala:796-798 `DELEGATE_PLUGINS_REFUSED`）；**MCP 启动**失败 = 本批已启动者回滚停用 + failNode `PLUGIN_MCP_START_FAILED`（PluginMcpManager.scala:67-82）。总闸 `plugins.enabled`（缺省 true，热读）= 一键回旧行为：NodeEdit 忽略参数、目录不注入、spawn 不注入不启 MCP（PluginsConfig.scala:7-16）。

## 5. 安全面（凭据 / grants 边界 / 引擎内置面）

### 5.1 凭据引用

- **AskUser secret 题通道（指针纪律所在）**：密.Secret 值经专用 WS 键直达 `AskUserSecretSink`——落盘 `~/.stand/secrets/.askuser/`（长期）与 `.otp/`（一次性，TTL 10 分钟钳 10s-24h）内 0600 文件；**值从不进会话消息/审计/载荷**；agent 只拿指针回执 `{keyRef, location, variableName}`，消费经 `$(cat <location>)` 在当条子进程内展开，OTP 原子 rename 出目录再读、读后即焚、过期明文不回读（AskUserSecretSink.scala:14-45,120-209,316-401）。
- **MCP 卡面遮蔽**：MCP/ScriptTool 审批卡参数经 `GateRedact` 字段名启发遮蔽（`password|token|secret|credential|key` → `***`，递归嵌套）+ 截断，不发送原始 input（McpToolGate.scala:245-292）。
- **插件目录内的 env 明文**：mcp.json env 值是随包字面量（§2.2），红标启发 + 键名打码是现有全部对策——**没有**协议级凭据指针字段（现状陈述，改进见 §8）。

### 5.2 grants 边界与越权面

见 §2.3：白名单三件 + 装载层拒载 + allowedSet 二次过滤双保险；编排类/用户面/引擎身份面（Pop、Mail、NodeEdit、TaskBoard、node_report、delegate_report、AskUserQuestion）全部「挂载面 + 工具内身份拒绝」双保险且插件声明不授能（AgentSessionExecution.scala:2422-2508）。**运行期越权面**：MCP/ScriptTool 工具调用过 P-M1 审批门——未声明 = 缺省 confirm（反转了旧「MCP 缺省自动批准」）、会话级放行只内存不落盘零跨会话残留、L3 宿主红线不可升档免审、判点挂在 `permissionDecision` 的 `ToolReversibility` 分支之前且内置工具路径零改动（McpToolGate.scala:9-28,207-243,316-332；AgentSessionExecution.scala:1638-1651）。**声明档真值面（`org.nebflow/sandbox.json`）属 P0-4 未落地**——现缺省实现 `alwaysUndeclared`（一律未声明 → 全部走 confirm 卡，McpToolGate.scala:103-130）。

### 5.3 引擎内置面不可替代裁定落码情况

落到代码的：①工具白名单封闭（插件不能发明工具/授予编排类，§2.3）；②收敛 agent 的 `tools`/`mcpServers` 定义层声明退役——能力差异化唯一通道 = plugins（AgentSessionExecution.scala:2356-2360；NodeEditTool 注 NODE_AGENT_RETIRED——无 per-node agent，`plugins` 是唯一能力机制）；③Pop 收归 Nebula 专属后插件授予通道关闭（PluginRegistry.scala:98-103）；④路径围栏防 symlink 逃逸（`containedUnder` :468-480，plugin.json 越界 = 拒载档，SKILL.md/mcp.json = 跳过档）；⑤官方 namespace 防冒名（§0 表 #7）。**未见对应代码锚点的**：「auth/sessions/routing 不可替代」没有以该措辞成文的代码断言——现状的等价保障是间接的：plugin.json/mcp.json 封闭 schema 里没有任何引擎配置/auth 字段可声明，插件运行时只有 `PLUGIN_ROOT`/`PLUGIN_DATA` 两个占位与自身 `plugin-data/` 数据目录，唯一系统影响面 = MCP 子进程与提示词注入。此条如实报为「裁定在机制上成立、无单点代码落字」。

## 6. 消费面（声明式挂载 / 自动分配 / 无插件边界）

### 6.1 dispatch 文本怎么点名插件

- **目录段**：分发器 spawn/重入 prompt 注入 Plugin Catalog 段（`ProjectActor.pluginCatalogText` → `DispatcherContextCatalog.pluginSection` 单点委托 `PluginRegistry.renderCatalog`，DispatcherContextCatalog.scala:37-63）——行格式 `- <name>: <description> — keywords: … — use when: … [skills: … | mcp: … | tools: …]`（PluginRegistry.scala:1506-1524，useWhen/keywords 增量渲染、缺省逐字节）；过滤链 = trusted ∧ dispatch effective，被封禁不出现、被作者关闭**完全不可见**（既无能力行也无点名行，:1539-1565）；段尾两类注记：缺席计数（装载失败/封禁）+ 内容已变点名（:1403-1425）。目录段与调试 REST `GET /api/plugins/catalog` 同字节输出。
- **声明式挂载**：分发器在 NodeEdit/ChainWrite 里按 name 逐字引用目录行；`NodeEdit plugins` 参数 replace-on-provide，创建时缺键拒（NODE_PLUGINS_UNDECLARED）、多个包一次挂载（系统提示词 NodeEdit schema :190）。派发器纪律文本要求「resolve against the currently effective Plugin Catalog…never hardcode plugin names」（BuiltinAgents.scala:306）。
- **REST/CLI 面**：`GET /api/plugins`（清单+拒载清单）、`GET /api/plugins/catalog`、`POST /api/plugins/:name/approve|revoke|unblock|enable|disable|dispatch/grant`（PresenceRoutes.scala:1439-1558）；CLI `stand plugin list|add|approve|revoke|unblock|enable|disable`（PluginCommand.scala:28）；执行体资产走令牌化静态路由 `/api/plugin-assets/<name>/…`（封禁包永不内联下发，StaticRoutes.scala:669-737、PluginRegistry.scala:335-340）。查询 CLI `tools/stand-plugins list|search|mounts`（作者令 2026-10-23）读同一盘面、只读。

### 6.2 自动分配（contextless delegate 的插件从哪来）

- **触发点**：delegate spawn（内置执行器腿，含 Mail 续聊复活腿同点复用）与 flow/链每步 spawn——节点**空声明** ∧ delegate 作用域引擎才走分配面；per-project 引擎空声明 = 空注入旧行为（PluginInjection.scala:21-35,115-148）。声明集在场 ⇒ 声明权威、零分配。
- **三态链**：`PluginCatalog.entries`（trusted ∧ effective 的 CatalogEntry，含 keywords/useWhen，PluginCatalog.scala:21-29）→ `PluginAllocator` 端口（JeV 决策服务 → LLM 兜底单次 JSON 调用 → fail-open 空集；端口未注册 = `off`，内部异常 = fail-open，PluginAllocator.scala:8-94）→ 解析注入。总闸 `plugins.enabled` 之外还有专用闸 `plugins.autoAllocate`（缺省 true；false = 仅声明不问模型，惰性态 `auto-off`，PluginsConfig.scala:17-32）。
- **模式词台账**：`jev` / `llm-fallback` / `fail-open` / `disabled` / `empty-catalog` / `off` / `auto-off` / `dispatcher-off`；分配动作真实发生才写审计与 `step-plugins` 结构帧行（PluginInjection.scala:52-58,127-148）。

### 6.3 contextless / dispatcher 的无插件边界

- **分发器本体零插件**：dispatcher 模式 delegate「itself gets zero plugins」（DelegateTool schema :107-111）；插件腿显式跳过 allocatePreparation、记 `dispatcher-off`（:784-794）；编排纪律行明写「Node plugins are allocated automatically per-node…never name plugins and never pass a plugins key」（:157-158）；ChainWrite 对 `nodes[].plugins` 键显式拒（:305-335）。即：**分发器会话没有任何插件面，插件只存在于节点/内核执行腿**。
- **kernel（含 contextless delegate 缺省形态）**：spawn 时对整 brief 一次分配（现状逐字），分配产出照常注入 brief 尾部 `<injected-plugins>` 块 + agentDef 运行时授予（pluginMcpServers 前缀 + pluginTools 白名单过滤，:808-810）。
- **节点侧自查指引**：节点协议脚注前缀注入 `[plugin discovery]` 行——节点有 Bash 可查 `tools/stand-plugins`，缺能力时「declare the needed plugin(s) and the intended use in your result——挂载权在派发侧」（Defaults.scala:122-137；NodeEngineContract.scala:660-669——skill 分配与插件查询同缝、MCP server 声明仍走 mcp.json）。

## 7. 已知缝隙清单（与今日实证对齐）

以下全部为**现状事实**，非建议。实例实证 = 本机 `~/.stand` 2026-10-29 13:16 快照（引擎同款算法现算比对）。

1. **实例副本无版本管理**。包目录唯一「版本」= manifest `version` 字符串（作者自述、无强制语义）+ 目录 digest；无历史、无回滚面——唯一回滚材料是 seed 刷新路径的 `plugins-backups/`（只覆盖「干净→镜像」分支，SeedService.scala:502-529）。一旦实例被直改，引擎侧没有任何机制记录「改成了什么、何时改的」。
2. **种子/实例漂移史（今日词条批同类案例的现行状态）**。默认集 7 包实测：document-production / nebflow-plugin-creator / slideblocks / web-search-toolkit 与种子一致且信任记录同步（后三者分别于 10-28 09:37、**今日 10:17** 刷新重审）；**visual-report / browser-use / computer-use 三包实例自 2026-10-08 approve 后被直改（实例 mtime 10-14），种子已前进（wiki-docs 批 `353f903e8`、`bc90b5e2d`、`b02710876`）——实例 digest ≠ 信任记录 ⇒ 仲裁判「用户改过」⇒ 种子更新永远到不了实例**。具体地：main 种子里的 `check_wiki_docs.py`（含 `--graph` 段落级索引等 10-28/10-29 改进）不是本实例实际执行的字节；但本机派发纪律引用的校验器路径正是实例副本（`~/.stand/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py`）——「作者在 main 上看到的协议」与「实例在跑的协议」已经分叉。
3. **无基准包的漂移不可见**。非默认集包多无信任记录（实测 16/23 包 no-rec）：其与种子/历史的任何差异都不产生 `contentChanged`（无基准可比，PluginRegistry.scala:256-263）——实测 nebflow-qa、nebflow-frontend-dev 与种子 DIFF 但静默。漂移可见性依赖「曾经 approve 过」这个前提。
4. **approve 前移 seed 仲裁基准（已登记未修）**。对手动 approve 一个用户改过的默认集包，下次 boot 的种子镜像覆盖会视为「干净」——已知代价登记在 PluginRegistry.scala:1177-1181（设计 §3.3① / C5），无审批批下风险面收窄未消失。
5. **卸载/重装语义依赖带外手势**。无卸载命令：删除靠 `rm -rf` + 台账推断，首次启动无法回溯判定历史删除（SeedService.scala:100-103）；带外消失一律保守判「用户删除」（不装回）。
6. **mcp.json env 明文**。凭据指针纪律（keyRef→secrets 0600）只覆盖 AskUser 通道；插件 MCP env 是随盘字面量，红标/打码只是人审辅助（§5.1）。
7. **MCP 审批声明档未落地**。`org.nebflow/sandbox.json` 档表解析属 P0-4 未实现，现一律「未声明 → confirm 卡」——协议面有声明形态、运行面恒走人工（McpToolGate.scala:103-130）；ScriptTool 注册名模式核实不存在，识别走注册表身份（:184-205）。
8. **sse 传输声明与实现脱节**。schema 接受 `sse`，客户端 MUST skip+report（PluginRegistry.scala:1073-1079）——声明合法但必然不可用，包作者侧无静态拦截（validator 亦未禁）。
9. **viewer 贡献服务端零深校验**。`viewers.json` 只做「object + ≤32KB」两检（:815-832），schema 归前端渲染器注册表面——控制面内联的内容正确性无服务端闸。

## 8. 改进建议（未与作者对齐）

> ⚠️ 本节纯侦查产出，**未与作者对齐**，不构成任何实施依据；作者裁定后才可立项。

- 针对缝隙 2/3：给「实例直改」补一条低噪声可见面（如 boot 健康摘要把「种子 ≠ 实例且无基准/有漂移」的默认集包点名），或引入「接受种子版本」的一条显式命令——把今日「词条批改进到不了实例」的silent 状态变成可决策项。
- 针对缝隙 1：实例包目录引入引擎侧记录（如 `.plugin-state.json`：上次 seed 同步时间/来源 digest），使「种子 canonical/实例直改」有对账面。
- 针对缝隙 5：补 `stand plugin remove`（写 userRemoved 墓碑 + 可选删目录），替代带外 rm -rf 推断。
- 针对缝隙 6：mcp.json env 支持 `${secret:keyRef}` 指针形态（复用 SecretsStore/AkUserSecretSink ACL 纪律），红标启发降级为兜底。
- 针对缝隙 7/8：sandbox.json 档表（P0-4）与 validator 增加「sse 传输 → 警告不可用」静态提示。

> **后续批**：本文档的现状事实面已成为插件远程发布源架构设计（[[20261029_174545_plugin-remote-source-design__delegate-nebflow-585363ad.zh-CN]]）的基线——该稿把本文档 §7 缝隙 1/2/3/4/5 归并为同一根因（本体与来源同住分发载体），并提出源引用模型、拉取式安装、用户自选更新与信任根迁移方案（仍为设计稿，裁定点未拍板）。

## 9. 证据基线

- 读码基线：main `5c2ad9b57`（本地，含 `8b5138640` 插件三层规范、`1dcbb1517` 卫生批；origin fetch 当日网络不可达，以本地 main 最新为准）。
- 主要文件：`src/main/scala/nebflow/core/plugin/`{PluginRegistry,PluginDispatchPolicy,PluginBlockPolicy,OfficialPackages,PluginMcpManager,PluginsConfig,DispatcherContextCatalog}.scala；`core/project/PluginInjection.scala`、`core/project/NodeStarter.scala`、`core/project/NodeEngineContract.scala`；`core/tools/{NodeEditTool,DelegateTool,ChainWriteTool}.scala`；`core/McpToolGate.scala`；`core/seed/SeedService.scala`；`core/jev/{PluginCatalog,PluginAllocator}.scala`；`cli/PluginCommand.scala`；`gateway/{PresenceRoutes,StaticRoutes}.scala`；`shared/Defaults.scala`；`core/entity/BuiltinAgents.scala`；`core/delegate/DualMode.scala`；`agent/AgentSessionExecution.scala`；`core/secrets/AskUserSecretSink.scala`；`src/main/resources/seed/{manifest.json,plugins/}`；`plugins/imagegen/`（仓内样例）；`tools/stand-plugins`；`seed/plugins/nebflow-plugin-creator/skills/plugin-packaging/scripts/validate_plugin.py`。
- 实例快照：`~/.stand/plugins/`（23 包）× seed 树 digest 比对、`~/.stand/nebflow.json` plugins 键族（仅 `trust` 7 条记录；`dispatch`/`revoked` 空表）、`.plugin-presence` 机制在案。比对脚本按 `PluginRegistry.computeDigest` 同款算法现算（§7 数据即其输出）。
