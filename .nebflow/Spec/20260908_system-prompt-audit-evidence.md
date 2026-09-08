# 系统提示词架构审计 · 阶段一取证报告（三 keeper 组装链）

- 日期：2026-09-08 ｜ 轨道：取证（只读，src/ 零写入）
- 对象：Nebula / project-dispatcher / general 三 keeper 的系统提示词完整组装链
- 代码态基准：主仓 HEAD `e12cb242`（含 `f5a9f18c` 2026-09-07 节点记忆移除）
- 运行态基准：宿主实例 v1.4.1-beta.56（VERSION=`2bfaa92e` 2026-09-06 14:57；环境表 PID 11317，全程未触碰）
- 方法：exploration-method（入口定位→顺调用链→发散补全）；每条论断带 `路径:行号`
- 过时判定基准：作者 2026-09-08 裁定（system prefix 等隐式注入退役、层级扁平三 keeper、Teams&Flows 整节死重、任务记忆归 TaskList、裁定/方法论属记忆或 skill、生命周期协议引 NodeList 已过时）

---

## 1. 总组装链（结论先行）

**最终系统提示词 = `systemPrefix` + `agentDef.systemPrompt`（即 `~/.nebflow/agents/<name>/system.md`）+ 条件段（PromptSections 注册表按 order 拼装）**，三者单点拼装于：

- 拼装函数：`PromptSections.assembleSystemPrompt` — `src/main/scala/nebflow/agent/PromptSections.scala:592-594`（prefix 必须最前 = provider prefix-cache 契约，`PromptSections.scala:587-590` 注释 + spec 钉死）
- 入口函数：`AgentCore.buildSystemPrompt` — `src/main/scala/nebflow/agent/AgentCore.scala:1957-1970`：
  1. `agentDef.systemPrompt.nonEmpty` → 用之；否则 `Repl.loadSystemPrompt()`（fallback = `~/.nebflow/agents/Nebula/system.md`，`src/main/scala/nebflow/core/repl.scala:90-93`）
  2. SubTask worker → `SubTaskPrompt.stripTeamContent`（`PromptSections.scala:697-705`，剥 Teams & Flows / Team Catalog 段 + Mail 相关行）
  3. `stripAllMigrated`（剥 Voice Output，`PromptSections.scala:607-609`）
  4. 条件段 = `PromptSections.buildConditionalBlocks(ctx)`（`PromptSections.scala:575-581`：filter shouldInclude → sortBy order → 双换行连接）
- 调用时机（cache v2）：**仅 lifecycle 节点重建**（新会话/压缩/重启，`AgentCore.scala:665` `isLifecycleRebuild = isCompactTurn || cachedSystemStable.isEmpty`），轮间字节级复用缓存（`AgentCore.scala:745-767`，快照结构 `SystemStableSnapshot` `protocol.scala:1027-1060`），mid-session 变更走 system-reminder 增量；`LlmRequest.systemStable = Some(systemStable)`（`AgentCore.scala:863`）
- 每 turn 前置：`ContextRefresher.refreshTurn`（`ContextRefresher.scala:506-598`）产出 `TurnContext`（`protocol.scala:776-791`），`PromptContext` 组装在 `AgentCore.scala:719-744`

---

## 2. 逐层取证

### L1 基座模板 builder（PromptSections 条件段注册表）

| 项 | 证据 |
|---|---|
| 机制 | `PromptSections` 自描述条件段注册表：`order` + `shouldInclude(ctx)` + `render(ctx)` — `PromptSections.scala:111-150`；注册表 `dynamicSections` — `PromptSections.scala:247-348` |
| PromptContext | 运行态旗标与预渲染文本 — `PromptSections.scala:45-95` |
| 现役段清单 | 395 身份条款（guardrailsOn 门控，`254-261`）｜500 voice（`269-273`）｜600 devices（`276-280`）｜610 sessions（`281-285`）｜620 language（`286-290`）｜630 mounted projects（**仅 isRootAgent**，`297-301`）｜800 skill catalog（`308-312`）｜808 flow catalog（`313-317`，生产点已置空）｜816 team catalog（`318-322`）｜810 memory block（`323-327`）｜895 AGENTS.md（`332-336`）｜900 project rules（`339-343`） |
| 已删除段（阶段 2d 自包含原则） | 条件工具指南段（askUser/readLive/visualReporting/tasksGuide/workerBlock）已下迁工具 description — `PromptSections.scala:26-28, 163-166, 263-266, 302-305, 344-347` |
| 文件段覆盖 | `~/.nebflow/prompts/sections/` 下 `.md` 与子目录（condition.json+prompt.md+data.sh，data.sh 执行做 `{{var}}` 替换）— `PromptSections.scala:354-534`；同 Order 文件覆盖内置（`542-545`） |
| 判定 | **现役、机制健康**。 |
| 量级 | 文件 707 行；三 keeper 实际命中的条件段见 §3 实测表。 |

### L2 systemPrefix 隐式注入现状

| 项 | 证据 |
|---|---|
| 四个源 | `ContextRefresher.scala:39-92`：`system-prefix-for-all`（`~/.nebflow/prompts/system-prefix-for-all.md`，带 JAR fallback `/system-prefix-for-all.md` + 旧 `/system-prefix.md` 双兜底，`40-54`）、`system-prefix-for-teams`、`system-prefix-for-flows`、`manager-prefix`（仅 name=="Manager"） |
| 拼装 | `refreshTurn` `ContextRefresher.scala:523-536`：isWorker→空；category team/flow 各自源；`systemPrefix = allPrefix + categoryPrefix + managerPrefix` |
| 文件实测 | for-all **5495B**（内容：Process Safety / Git Discipline / Skills / Memory / Session Management）；teams **13651B**（团队状态承载/压缩恢复/沉淀标准）；flows **260B**（纯注释壳）；manager **6073B**（Manager 指引） |
| JAR 内置 | `src/main/resources/system-prefix-for-all.md` 与 `src/main/resources/system-prefix.md` 均存在（ls 实证） |
| 判定（对照 09-08 裁定「隐式注入退役、层级已扁平」） | **过时存活（residual）**：代码仍每轮给所有会话（含三 keeper）注入 for-all prefix 5495B——按裁定层级已扁平，该段应下沉进各 keeper system.md 后退役。附加自过时：prefix 内 Memory 段对无记忆注入的 dispatcher/general 是死文本（memoryBlock 恒空，见 L4）。teams/flows/manager 三源仅 legacy 双轨会话消费，随阶段 3 清。 |

### L3 Teams & Flows / Standalone Agents 目录注入

| 项 | 证据 |
|---|---|
| 渲染器 | `TeamCatalog.buildGlobalCatalog` — `src/main/scala/nebflow/core/entity/TeamCatalog.scala:41-98`：`=== Teams & Flows ===` 整节（When to use what 的 Mail 路由指导 + Standalone Agents + Teams + Flows）；team 成员变体 `buildCatalog` `10-35`（+team rules 追加 `ContextRefresher.scala:646-647`） |
| 生产点 | `ContextRefresher.buildTeamCatalogForSession` — `ContextRefresher.scala:621-664`：session 有 team 注册→team 专属目录；否则（Nebula/standalone/无注册）→ global 目录 |
| 注入 | order 816，condition nonEmpty — `PromptSections.scala:318-322` |
| 触发条件 | `isWorker==false` 且非 team 注册会话 → **三 keeper 全部命中**（Nebula 根会话、dispatcher 单次会话、node 会话均无 team 注册） |
| 判定 | **死重确认（裁定正确）**：整节指导「Mail(team/flow/agent)」，但 Nebula 工具面已无 Mail（`NebulaOrchestrationTools` `AgentCore.scala:2172-2195` 无 Mail；`~/.nebflow/agents/Nebula/system.md:36-38`「旧体系退役」自述），dispatcher/general 也无 Mail（`DispatcherFixedTools` `AgentCore.scala:2208-2217` 八件、`GeneralFixedTools` `AgentCore.scala:2249` 七件均无 Mail）。每份 keeper prompt 常驻整节 team/flow 清单（本次审计会话实测 ≈2.7KB）且无任何工具可消费。 |
| 先例 | flowCatalog 已停注：生产点置空 `ContextRefresher.scala:571-575`（2026-09-06 工具面裁撤批，FlowTrigger 退役失去消费工具），order 808 永不渲染；`SkillService.buildPerAgentFlowCatalog` retired — `SkillService.scala:248-251`。teamCatalog 可复刻同款处置。 |

### L4 memory 注入钩子

| 项 | 证据 |
|---|---|
| gate | `ContextRefresher.shouldInjectMemory` — `ContextRefresher.scala:318-323`：`!headless && !isWorker && agentName == "Nebula"`（2026-08-31 裁定①：仅 Nebula 有记忆；headless 基准模式全跳） |
| 内容组装 | `buildMemoryBlock` — `ContextRefresher.scala:350-363`：`MemoryStore.loadUserMemory`（`~/.nebflow/User.md`）+ `loadAgentMemory`（`~/.nebflow/agents/Nebula/memory.md`）+ `MemoryHygieneSignal.takePending()`（重启/压缩事件）+ `TaskListStore.openSummaryLine()`（2026-09-06 TaskList 批：open 任务一行摘要进记忆块尾部，`342-348`） |
| 渲染 | `renderMemoryBlock` `369-391`（`# Memory` 头 + 两级 section + `---` 分隔）；`memoryHygieneNotice` `395-430`（>80% 软线→即时整理措辞；重启/压缩→轻清扫提示） |
| 注入 | order 810 — `PromptSections.scala:323-327`；生效时机=lifecycle 重建点（`AgentCore.scala:745-748` 注释：memory 编辑下一 lifecycle 节点生效） |
| 预算 | `src/main/scala/nebflow/service/MemoryBudget.scala:33-44`：User 硬 50KB/软 40KB；agent 硬 30KB/软 24KB；project 硬 10KB/软 8KB（project 独立定值依据：注入面乘法效应 `41-44`） |
| 实测 | `~/.nebflow/User.md` **41938B（已过 40KB 软线，软警区）**；`~/.nebflow/agents/Nebula/memory.md` **23886B**（< 24466B 软线） |
| 项目记忆（第三层） | `ProjectMemory` — `src/main/scala/nebflow/core/project/ProjectMemory.scala:41-95`：文件 `<workspace>/.nebflow/memory.md`；注入面=分发器 prompt（`ProjectActor.scala:325-335 projectMemoryText` → `newTaskPrompt`/`reentryPrompt` `343-348, 365+`）；**节点侧 HEAD 已移除**（`f5a9f18c` 2026-09-07 22:56，`NodeEngine.buildInput` 现只组 task+上游+协议脚注，`NodeEngine.scala:710-727`）；Nebula 常驻不注入（`ProjectMemory.scala:37-39` 瘦身边界）；写口=MemoryEdit `target=project:<name>`（`MemoryEditTool.scala:25-48`，路径经项目注册表解析）；渲染三态单点 `injectionBlock` `69-94` |
| 判定 | 机制现役且与裁定一致（任务记忆归 TaskList：openSummaryLine 过渡态已落）；**User.md 处软警区**，按纪律需当轮安排整理（见 §6 未决项）。 |

### L5 skills / plugins 能力注入

| 项 | 证据 |
|---|---|
| skill catalog | `SkillService.buildPerAgentCatalog` — `src/main/scala/nebflow/core/skill/SkillService.scala:222-246`：agent.json `skills` 声明 → `# Skills` 目录段（`*`=全量；`disable-model-invocation` 排除；`alwaysVisible`（skill-creator）无条件追加 `230-233`）；注入 order 800 — `PromptSections.scala:308-312` |
| gate | `skillCatalogEnabledFor` — `ContextRefresher.scala:612-613`：收敛三角色中**仅 Nebula 保留**（用 skill-creator 造 skill）；general/dispatcher 停注（D.1-12：plugin 全文注入取代「目录+自读」，裁定 8/9）；legacy 保留至阶段 3 |
| 实测量级 | 全局 skills 目录仅 3 项（`_example`/`memory-consolidation`/`skill-creator`）→ Nebula 目录段 ≈1KB，非死重 |
| plugin 全文注入 | `NodeEngine.prepareNodePlugins` — `src/main/scala/nebflow/core/project/NodeEngine.scala:736-875`：spawn 前解析 `node.plugins`（未信任/非法→failNode）→ skill 全文读出+`${SKILL_DIR}` 替换+frontmatter 剥除 → `<injected-plugins>` 块（`866-871`）**追加进节点首条消息**（`754-756`）；MCP server 启动（`PluginMcpManager`）+ builtin 工具授予写运行时 AgentDef（`pluginMcpServers/pluginTools` `AgentDef.scala:68-74`；MCP 前缀追加 `AgentCore.scala:1779-1784`；per-turn 热重载保活 `ContextRefresher.scala:499-504`） |
| 分发器目录段 | `DispatcherContextCatalog` — `src/main/scala/nebflow/core/plugin/DispatcherContextCatalog.scala:33-87`：Plugin Catalog（受信插件 capability 行）+ Model Preset Catalog 双段；挂接 `ProjectActor.pluginCatalogText` `ProjectActor.scala:317-323` → 进 newTaskPrompt/reentryPrompt 首条消息 |
| 判定 | 现役，收敛口径正确。 |

### L6 reminder / environment 注入

| 项 | 证据 |
|---|---|
| Environment 表 | `~/.nebflow/prompts/sections/environment/`（condition.json order=100 always + prompt.md 模板 + data.sh）：字段 working_dir/platform/shell/os_version/nebflow_version/pid/gateway_port；chatWidth 已删（data.sh 头注释，2026-08-20 裁定）；变量由 `renderWithScript` 注入 env（`NEBFLOW_VERSION`/`NEBFLOW_PID`=sysprop `nebflow.gateway.pid`/`NEBFLOW_GATEWAY_PORT` — `PromptSections.scala:508-534`）；单独渲染入口 `envInfoSection`（order<200）— `PromptSections.scala:555-560`，常驻 systemStable（`AgentCore.scala:710-716`） |
| Devices | `deviceInfoBlock` — `AgentCore.scala:1983-2016`：neblink 本机+peers，30s 缓存；order 600 hasDevices 门控；mid-session 变化→delta reminder（`devicesDeltaLines` `762`） |
| Reminder 体系 | `SystemReminders.collectAllIO` — `src/main/scala/nebflow/core/reminders.scala:88-113`：time（真实用户轮必注；系统事件轮 ≥1h 间隔 `injectTime` 门控 `AgentCore.scala:689`；time 持久化为 User 消息+旧条目 prune `AgentCore.scala:791-814`）｜schedule（Nebula only，`reminders.scala:139-156`，id+name 句柄 2026-09-06 升级）｜devices delta（`116-118`）｜tasks（team 会话 only，`120-124`）｜language（`127-128`）｜projects delta（Nebula only，`130-136`）｜**sessions/environment 变更提醒已删**（2026-08-20 审计裁定，`73-75` 注释） |
| 判定 | 现役，与 2026-08-20 reminder 审计口径一致。 |

### L7 agent.json prompt 字段与面板编辑入口

| 项 | 证据 |
|---|---|
| **schema 结论** | **agent.json 无 `prompt` 字段**。`AgentJson` decoder — `AgentLibrary.scala:212-254`：name/displayName/description/useWhen/tools/mcpServers/avatar/voice/model/preset/category/skills/flows |
| prompt 唯一来源 | 同目录 `system.md`：`AgentLibrary.loadFromDir` — `AgentLibrary.scala:141-174`（`readSystemMd` `203-204` → `AgentDef.systemPrompt` `AgentDef.scala:48`）；`EntityLoader.loadAgentFromDir` 同构 — `EntityLoader.scala:133-150`（team/flow lead 各有 `teams|flows/<name>/agents/lead/system.md`，`EntityLoader.scala:67-72, 121-126`） |
| Nebula 代码兜底 | `Seeds.Nebula` — `AgentLibrary.scala:303-366`：仅盘面缺失/损坏时启用（`loadAll` `76-84`）；`all = List(Nebula)` 唯一 seed（F.3 收敛 `359-366`）；**兜底文本本身严重过时**（Mail/Load/TaskCreate/WebFetch 旧工具 + Teams&Flows 路由叙述 `305-357`） |
| 面板写链 | Canvas 详情页（唯一写通道；旧 modal 已退役 2026-09-06 — `web/js/main.js:2335-2337`、`web/js/modal.js:213-216`）：`web/js/agentManager.js:363-368` `sendWs({type:'updateAgentSystemPrompt', name, systemMd})` → `WebSocketRoutes.scala:3039-3046` → `AgentService.updateSystemPrompt`（`src/main/scala/nebflow/service/AgentService.scala:24-25`）→ `AgentLibrary.updateSystemPrompt`（`AgentLibrary.scala:91-95`，`os.write.over(dir/"system.md")`） |
| 面板读链 | WS `getAgentSystemPrompt` — `WebSocketRoutes.scala:3025-3036` → `AgentService.getSystemPrompt`（`AgentService.scala:20-21`）→ `AgentLibrary.readSystemPrompt`（`AgentLibrary.scala:125-128`） |
| 生效面 | 每 turn `ContextRefresher.loadCurrentDef` — `ContextRefresher.scala:456-478` 重读盘 → 面板改动即时生效于运行 actor；spawn 时运行时注入（modelOverride/flowContract/plugin 分配）由 `applyRuntimeOverrides` `493-504` 保活 |
| REST 面 | `RestApiRoutes.scala:1724, 1773, 1783, 1793, 1817` 各 agents 列表端点均回传 `systemPrompt` 字段（读面）；写回通道 = WS（updateTools 写回已退役并响亮拒绝，`WebSocketRoutes.scala:3048-3052`、`AgentLibrary.scala:97-101`） |
| 三 keeper agent.json 现状 | **均无 prompt 字段**。Nebula（354B）：preset=LowCost、tools 声明 17 件、skills=["*"]；project-dispatcher（697B）：tools 声明 7 件、category=standalone、preset=general、skills=[]；general（111B）：preset=general。注意：收敛三角色的 agent.json `tools`/`mcpServers` 声明**整体 no-op**（`AgentCore.scala:1714-1724` base=∅；`1777-1778`）——机制面唯一来源是 `fixedToolsFor`（`AgentCore.scala:2271-2292`）→ `NebulaOrchestrationTools` 14 件（`2172-2195`）/ `DispatcherFixedTools` 8 件（`2208-2217`）/ `GeneralFixedTools` 7 件（`2249`）。 |

### L8 隐式段落块来源 + Task 派发注入面

**段落块（dispatcher-ctx-rules / merge-node-rules / failed-notify-rules / merge-node-discipline）**：

- 来源：**内嵌于 `~/.nebflow/agents/project-dispatcher/system.md` 本体**，HTML 注释标记划界：
  - `<!-- dispatcher-ctx-rules:start/end -->` — 行 25-33（能力目录选配）
  - `<!-- merge-node-rules:start/end -->` — 行 35-72（合并节点接线/创建模板/完成标准/blocked 后续）
  - `<!-- failed-notify-rules:start/end -->` — 行 74-87（failed 通知四动作处置）
  - `<!-- merge-node-discipline:start/end -->` — 行 89-103（合并节点生命周期纪律，含 in≤4 上限）
- **无代码常量**（src/ 全文 grep 四个块名零命中）、**无 blocks store**——标记只是定义文件内的段落划界，运行时整文件原样注入 system prompt。
- 量级：dispatcher system.md 共 **12303B**，四块合计 ≈5.5KB（行 25-103），占 ~45%。
- 判定（对照裁定「裁定/方法论属记忆或 skill 不属提示词」）：**过时**——merge-node-rules/discipline、failed-notify-rules 是项目方法论/判例（自引用主仓 `.nebflow/Spec/20260907_merge-node-design-guide.md` 行 100），应迁项目 `.nebflow/memory.md` 或 Spec/skill；system.md 回归分发协议本体。dispatcher-ctx-rules 块是对两目录段（DispatcherContextCatalog）的消费说明，属目录用法而非裁定。

**Task 派发注入面（Nebula→Task→ProjectActor→dispatcher→Node）**：

1. Nebula `Task` 工具 — `src/main/scala/nebflow/core/tools/TaskTool.scala:21-38`（fire-and-forget；分发器行为配置在 agent 定义承载，`16-19`）→ 挂载项目的 `ProjectActor`（GatewayMain 启动挂载，`TaskTool.scala:37`）。
2. `ProjectActor.dispatchNewTask` — `ProjectActor.scala:476-481` → `spawnDispatcher(newTaskPrompt(...))`；**分发器首条消息** = `newTaskPrompt` `ProjectActor.scala:343-348`：「你是项目 X 的任务分发器」+ Plugin/Preset Catalog 双段 + **项目记忆块**（`projectMemoryText` `325-335` → `ProjectMemory.injectionBlock`）+ 任务文本 + NodeList 先行指引（无 Flow Map 快照——20260907 裁定①方向 B `337-342`）。重入形态 `reentryPrompt`/`reentryActions` `350-371`。活跃会话注入形态不重复注记忆（`329-332`）。
3. **分发器 system prompt** = prefix(5495B) + dispatcher/system.md(12303B) + 条件块（**含 AGENTS.md order 895**——gating `agentsMdEnabledFor` `ContextRefresher.scala:142-143`：sandboxEnabled && projectRoot 非空 && name≠"Nebula"；置位点=NodeEngine+ProjectActor `131-134`）+ Teams&Flows 目录(816)。
4. **Node 链**：NodeEdit 建节点 → `NodeEngine.runNode` → 节点统一跑 `general`（agent 已退役 2026-09-05，NODE_AGENT_RETIRED 硬闸 — `~/.nebflow/agents/project-dispatcher/system.md:11`）：
   - 首条消息 = `buildInput`（`NodeEngine.scala:717-727`）：HEAD 代码态 = own task + 上游 result（`=== Node <name> ===` 头）+ `ProtocolFootnote`（`── 节点协议 ──` BLOCKED 五分类，`NodeEngine.scala:2353-2360`）+ `<injected-plugins>` 追加（`754-756`）；
   - **运行态（宿主 09-06 构建）** = 项目记忆块前置 + 上述（`f5a9f18c` 之前格式：`memBlock + "\n\n" + base`，见 §5）；
   - 节点 system prompt = prefix + general/system.md(2530B) + 条件块（AGENTS.md 895 + **Teams&Flows 全局目录 816**——无 Mail 工具的节点收到 Mail 路由指导，死重最刺眼处）。
5. AGENTS.md 读取 — `resolveAgentsMd` `ContextRefresher.scala:152-174`：`<projectRoot>/AGENTS.md` 每 turn 重读盘；16KB 截断护栏（`AgentsMdMaxBytes` `119`）；旧位 `.nebflow/Agent.md` 残留仅 WARN（`158-162`）。主仓 AGENTS.md 实测 **12598B**（< 16KB 不截断）。

---

## 3. 三 keeper 现役组装面实测（量级表）

### Nebula（根会话，system prompt 常驻面）

| 段 | 来源 | 实测量级 |
|---|---|---|
| systemPrefix | for-all | 5495B |
| system.md | `agents/Nebula/system.md` | 4516B |
| Environment 表 | sections/environment (order 100) | ≈500B |
| Skill catalog | order 800（skills=["*"]，3 skill） | ≈1KB |
| Teams & Flows 目录 | order 816 | ≈2.7KB（**死重**：无 Mail） |
| Memory 块 | order 810（User.md 41938B + memory.md 23886B + 头/分隔/hygiene） | ≈66-67KB（**占绝对大头；User.md 处软警区**） |
| Mounted Projects | order 630（isRootAgent only） | 数百 B |
| AGENTS.md | —（name=="Nebula" 排除，`ContextRefresher.scala:142-143`） | 不注入 |
| 每轮 reminder | time/schedule/devices/projects delta | 请求级，非 systemStable |

### project-dispatcher（单次会话）

| 段 | 来源 | 实测量级 |
|---|---|---|
| systemPrefix | for-all | 5495B |
| system.md | `agents/project-dispatcher/system.md`（含四段落块 ≈5.5KB） | 12303B |
| Environment 表 | order 100 | ≈500B |
| Teams & Flows 目录 | order 816 | ≈2.7KB（**死重**） |
| AGENTS.md | order 895（主仓实测 12598B） | 12598B |
| skill/flow catalog | —（收敛角色停注，`ContextRefresher.scala:612-613`） | 0 |
| memory | —（shouldInjectMemory 仅 Nebula） | 0（**项目记忆走首条消息**：plugin/preset 目录段+项目记忆+任务文本） |

### general（节点会话）

| 段 | 来源 | 实测量级 |
|---|---|---|
| systemPrefix | for-all | 5495B |
| system.md | `agents/general/system.md` | 2530B |
| Environment 表 | order 100 | ≈500B |
| Teams & Flows 目录 | order 816 | ≈2.7KB（**死重**：无 Mail 的节点收到 Mail 指导） |
| AGENTS.md | order 895（worktree 根同文件） | 12598B |
| memory 块 | —（仅 Nebula） | 0（HEAD 代码态项目记忆也不注；运行态仍注，见 §5） |
| `<injected-plugins>` | NodeEngine spawn 前组装，追加首条消息 | 随 plugins 分配浮动（本审计节点两插件实测 ≈9KB） |

---

## 4. 过时判定汇总（对照 2026-09-08 裁定）

| # | 项 | 代码态 | 运行态 | 判定 + 依据 |
|---|---|---|---|---|
| 1 | system-prefix-for-all 注入 | 活（`ContextRefresher.scala:523`） | 活 | **过时——退役对象**。层级已扁平三 keeper，5.5KB/份头部死重；prefix 内 Memory 段对无记忆 keeper 亦死文本。去向：有效内容（Process Safety/Git Discipline/Skills 机制说明）下沉各 keeper system.md，prefix 源退役 |
| 2 | teams/flows/manager prefix | 活（legacy 双轨） | 活 | 过时——仅 legacy 会话消费，随 team/flow 阶段 3 清 |
| 3 | Teams & Flows 整节（order 816） | 活，三 keeper 全中 | 活 | **过时——整节死重**（裁定正确）。生产点置空即可复刻 flowCatalog 先例（`ContextRefresher.scala:575`）；正文 Mail 路由指导与三 keeper 工具面（均无 Mail）失配 |
| 4 | flowCatalog（order 808） | 停注 | 停注 | 已清（`ContextRefresher.scala:571-575`；TurnContext 字段留至阶段 3 `protocol.scala:789`） |
| 5 | skillCatalog（order 800） | 仅 Nebula | 仅 Nebula | 口径正确（dispatcher/general 已停，plugin 全文注入取代）；Nebula 保留有因（skill-creator 造 skill），量级 ≈1KB 非死重 |
| 6 | 记忆注入面 | 仅 Nebula + 项目记忆（分发器首条消息） | 同左（节点侧多一份，见 #7） | 与裁定一致；TaskList openSummaryLine 已入记忆块尾部（`ContextRefresher.scala:342-348`）——「任务记忆归 TaskList」下的过渡态 |
| 7 | 节点侧项目记忆注入 | **已删**（`f5a9f18c` 2026-09-07 22:56） | **仍在注**（宿主构建 09-06） | 代码已收敛、运行态滞后——宿主下次重启自动消失，无需改码 |
| 8 | AGENTS.md 注入（order 895） | dispatcher+nodes，Nebula 排除 | 同 | 现役，gating 口径正确（`ContextRefresher.scala:131-143`）；16KB 护栏（119） |
| 9 | dispatcher 四段落块（≈5.5KB） | 内嵌 system.md | 同 | **过时**——方法论/判例属项目记忆或 Spec/skill（裁定），不属提示词本体；块内含生命周期协议（引用 NodeList 消费面，见 #10） |
| 10 | Nebula system.md「NodeList 只读观测」（`agents/Nebula/system.md:7`） | 在 | 在 | **过时**——`NebulaOrchestrationTools` 已无 NodeList（2026-09-06 00:48 摘除，`AgentCore.scala:2172-2176` 注释），提示词仍写 `NodeList(project)` 用法 = 引用已退役工具（裁定点名项） |
| 11 | Nebula agent.json tools 声明 17 件 | no-op（收敛角色 base=∅） | no-op | 过时残留——声明含 Write/Edit/Mail 面件，实际面 14 件（`AgentCore.scala:2172-2195`）；机制固定零配置（裁定 11），定义层未清 |
| 12 | Seeds.Nebula 兜底 prompt（`AgentLibrary.scala:305-357`） | 旧体系文本 | 同 | 过时——Mail/Load/TaskCreate 全是退役工具；仅盘面损坏兜底态出现，建议随阶段 3 重写 |
| 13 | voice 段（order 500 + sections/voice.md） | 条件注入（voiceEnabled） | 视会话 | 现役条件段，keeper 无死重（未开 voice 不注） |
| 14 | guardrails 身份条款（order 395） | 默认关（`Guardrails.scala:24-26,36-38` 缺 key false） | 关 | 现役休眠；三 keeper 不命中（目标是 flow node/team member）；阶段 3 可议删 |

---

## 5. 运行态 vs 代码态（关键发现）

1. **宿主构建早于 09-07 节点记忆收敛**：宿主 v1.4.1-beta.56（VERSION 提交 `2bfaa92e` 2026-09-06 14:57）早于 `f5a9f18c`（2026-09-07 22:56，节点侧去除项目记忆注入）。
2. **运行实例亲测佐证**：本审计节点（2026-09-08 spawn）首条消息结构 = `# Project Memory — nebflow（<路径>…）` 前置 + 任务文本 + `── 节点协议 ──` + `<injected-plugins>`——与 `f5a9f18c` **之前**的 `buildInput` 格式逐字吻合（旧代码：`if memBlock.isEmpty then base else memBlock + "\n\n" + base`，见 `git show f5a9f18c` diff 删除段）；`ProjectMemory.injectionBlock` 头格式（`ProjectMemory.scala:78-80`）与收到的块头逐字一致。
3. **审计口径启示**：判定「是否过时」必须区分代码态（HEAD）与运行态（宿主构建日）；节点侧记忆死重在宿主下次重启后自动消失，属「零改动自愈」项，不需进阶段二改动面。

---

## 6. 未决项（移交规格轨）

1. **User.md 41938B 已过 40KB 软线**（软警区）——按预算纪律应安排整理轮；注入侧不截断（裁定），WARN 依赖 lifecycle 重建点快照。
2. **dispatcher 四段落块（≈5.5KB）迁出去向**需裁决：项目 `.nebflow/memory.md` vs 项目 `.nebflow/Spec/` vs skill——块内含 blocked/failed 处置判例与 in≤4 上限等活口径，迁移须防丢。
3. **Nebula system.md NodeList 条目**（行 7）与 **agent.json tools 声明**（17 件 no-op）的定义层清理——属阶段二改动面，量小。
4. **TaskList openSummaryLine 过渡态**（记忆块尾部一行，`ContextRefresher.scala:342-348`）——「任务记忆归 TaskList」完全体下是否保留，规格轨定。
5. **Teams & Flows 整节停注的波及面**：`buildTeamCatalogForSession` 同时服务 team 成员（团队专属目录+team rules，`ContextRefresher.scala:637-649`）——停注只能裁 global 分支（`650-664`），team 分支随阶段 3 team 退役一并处理。
6. **prefix 内容下沉清单**：Process Safety（宿主 PID 红线）/测试进程清理/Git Discipline/Skills 机制说明等段哪些下沉进哪个 keeper 的 system.md，阶段二逐条裁决；`Seeds.Nebula` 兜底文本同步重写。
7. **本报告按产物落位规范应入项目 repo 的 `.nebflow/Spec/`**（本项目 Spec 子目录进 git 的配方）——是否 commit 待 Nebula 指示（本节点只读纪律，未做 git 操作）。

---

## 附：关键文件清单（取证覆盖面）

| 文件 | 角色 |
|---|---|
| `src/main/scala/nebflow/agent/PromptSections.scala`（707 行） | 条件段注册表 + 拼装函数 + 文件段加载 |
| `src/main/scala/nebflow/agent/AgentCore.scala`（2308 行） | buildSystemPrompt（1957）/ turn 装配（634-870）/ 固定工具面（2168-2306） |
| `src/main/scala/nebflow/agent/ContextRefresher.scala`（666 行） | prefix 源 / refreshTurn / memory gate / AGENTS.md / catalog 生产点 |
| `src/main/scala/nebflow/agent/AgentLibrary.scala`（372 行） | agent.json+system.md 读取 / Seeds 兜底 |
| `src/main/scala/nebflow/agent/protocol.scala` | TurnContext（776-791）/ SystemStableSnapshot（1027-1060） |
| `src/main/scala/nebflow/agent/InjectionSource.scala`（37 行） | FileInjectionSource mtime 缓存 |
| `src/main/scala/nebflow/core/entity/TeamCatalog.scala`（100 行） | Teams & Flows / Team 目录渲染 |
| `src/main/scala/nebflow/core/skill/SkillService.scala` | buildPerAgentCatalog（222-246） |
| `src/main/scala/nebflow/core/plugin/DispatcherContextCatalog.scala`（87 行） | 分发器双目录段 |
| `src/main/scala/nebflow/core/project/NodeEngine.scala` | buildInput（717-727）/ ProtocolFootnote（2353-2360）/ plugin 注入（736-875） |
| `src/main/scala/nebflow/core/project/ProjectActor.scala` | 分发器 prompt 双形态（317-371, 476-481, 517-518） |
| `src/main/scala/nebflow/core/project/ProjectMemory.scala`（95 行） | 项目记忆单点（路径/读写/注入块三态） |
| `src/main/scala/nebflow/core/reminders.scala`（217 行） | SystemReminders 六类 |
| `src/main/scala/nebflow/core/tools/TaskTool.scala` | Task→ProjectActor 入口 |
| `src/main/scala/nebflow/core/repl.scala:90-93` | system.md 兜底读取 |
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala:3025-3052` | 面板 prompt 读写 WS 命令 |
| `src/main/resources/web/js/agentManager.js:363-368` | 面板唯一写通道前端侧 |
| `~/.nebflow/prompts/system-prefix-for-all.md`（5495B）等四 prefix | 注入源文件 |
| `~/.nebflow/prompts/sections/environment/` | Environment 表（condition.json/prompt.md/data.sh） |
| `~/.nebflow/agents/{Nebula,project-dispatcher,general}/{agent.json,system.md}` | 三 keeper 定义（prompt 全在 system.md） |
