# 阶段 2d 工具体系改造报告（设计 §D 逐项落地）

> 日期：2026-09-04 ｜ 节点：阶段 2d（barrier，继承上游 2cQC 六项 nits）
> worktree：`/Users/dev/Claude code/Nebflow/.nebflow/worktrees/qc-2d-refactor`（分支 `qc-2d-refactor`）
> 上游：`7e945d2f`（2cQC nits 单笔已落库、工作区干净 → **无需代提交/两笔编排**，本笔 2d 直接叠加）

---

## 1. §D.1 逐项落地对照表

| # | 任务项 | 改动 | 文件 | 证据一句 |
|---|---|---|---|---|
| D.1-1 | fixedToolsFor BaseTools 全员注入函数本体删除——三角色静态集收口 | 三角色 name 分支从 fixedToolsFor 删除，收口为静态集常量直接派发（Nebula=十四件+Issue parity carry；dispatcher=七件；general=八件）；team/flow 分支与 standalone catch-all 为双轨期 legacy 保留（`legacyFixedTools`，阶段 3 删）。**catch-all 不删的依据**：Coder/Explorer/design-engineer 的 agent.json 未声明文件工具（实测清单），删即断活 agent 工具面；§D.3「2d/阶段 3 删 legacy」分期语义。**【2026-09-06 增补】team/flow legacy 分支已随工具面裁撤批减项**：FlowExecute（team 固定面）/FlowReport（flow 固定面）移出——双轨路径本身保留至阶段 3 | AgentCore.scala | Phase2dToolRefactorSpec 4 用例：三角色面逐件不变 + `legacyFixedTools(三角色名)==BaseTools`（name 分支已删的结构证明）+ legacy 三分支逐件不变（2026-09-06 起 team/flow 分支按裁撤批口径断言） |
| D.1-11 | SendFriendMessage 声明式注入通道删除 | `buildAllowedToolSet` 对 base 一律剥离该工具名（`base - "SendFriendMessage"`，`*` 亦然）；Nebula 静态集照常携带（2c 机制固定为唯一授权源）；FriendMessageTool/registry 注释同步 | AgentCore.scala, FriendMessageTool.scala, registry.scala | Phase2dToolRefactorSpec：standalone 显式声明/`*` 均不授能；Nebula 机制携带；注册名保留 |
| D.1-12 | skill 目录注入 order 800 新模型 node 会话停注；Nebula 保留 | `ContextRefresher.refreshTurn`（每轮含首条消息的唯一生产点）按 `skillCatalogEnabledFor` 开关置空；converged 非 Nebula → 停，Nebula/legacy → 保留 | ContextRefresher.scala | 隔离实例双向取证：general（声明 skills:["*"]+磁盘有 skill）CATALOG=NO，legacy 对照 CATALOG=YES 并引用条目原文（§4） |
| D.1-14 + D.2 | PromptSections 条件段下迁工具定义 | order 400 提问指南→AskUserQuestion description（含 When NOT to use 反模式+Rule of thumb+场景）；410 Read live 语义→Read description（Live results 段）；415 Pop 指南→Pop description（Visual reporting workflow，含「professional tool→SVG→Pop」+never hand-draw 反模式）；630 team 协议→TeamTask 三件 description（双轨期，代码注释注明阶段 3 退役）；999 Worker Identity→注入删除（general 模版 §C.4 第 4 节已取代；legacy worker 硬边界由 isSubTaskWorker 机制剥离保证）。395 身份条款按 D.2 表双轨期保留 | PromptSections.scala, AskUserQuestionTool.scala, ReadTool.scala, PopTool.scala, TeamTask{List,Update,Create}Tool.scala, TaskStore.scala(注释) | description 自包含 spec 4 组 + wire 层判据（Phase2dSkillCatalogSpec：LLM 请求的 tools 定义里实测含下迁文本）+ PromptSectionsSpec 反向断言（含工具也无旧段） |
| D.1-9 | MCP 过滤保留 + plugin 前缀确认 + converged mcpServers 退役 | 2b 命名 `mcp__plugin_<p>_<s>__<t>`（PluginMcpManager.serverIdFor）+ AgentCore 前缀追加（:1597-1602）已覆盖过滤语义 → 只补 spec 钉不重复造；机制层 `effectiveMcpServers`：converged 三角色 agent.json mcpServers 声明退役（与 tools 同批失效），legacy 授权保留 | AgentCore.scala | Phase2dToolRefactorSpec：converged 声明+授权双失效 vs legacy 双轨保留；plugin 前缀 allocated→可见/unallocated→不可见（链路 E2E 已有 NodePluginChainSpec） |
| D.4 | 死引用清理 | 见 §3 核查记录 | —（定义层走宿主命令） | 逐条行为验证见 §3 |

## 2. 定义层处置（沙箱 EPERM → staging，宿主命令见 §7-②③）

先真实尝试直接读写 `~/.nebflow/agents/`：**EPERM（宿主白名单只读，已知现场）** → staging 路径 `/tmp/nb-2d-toolrefactor/nebflow-defs/`，全文镜像待落地文件：

| staging 文件 | 处置 | 逐条记录 |
|---|---|---|
| `agents/Nebula/agent.json` | tools 死声明六件移除；**CheckIssues/SendFriendMessage/Task 三声明保留**；`flows:["*"]` 退役；**`mcpServers:["opendataloader-pdf"]` 整段退役**；skills:["*"] 保留 | ① 死六件（Read/Glob/Edit/Write/Grep/Bash）：ConvergedAgentNames → base=∅，AllowedToolSetSpec「Nebula 显式移除六件…声明也无效」实测绿——不在 allowed set，删=零行为变化。② **CheckIssues：任务保护令保留**（作者未裁定）。⚠ 判定：CheckIssues **不在 mcpServers 段内**（在 tools 段）——「若位于 mcpServers 段内则保留」分支未触发；mcpServers 段内仅 opendataloader-pdf 一项，无 Issue/CheckIssues 声明，整段退役与保护令无冲突。③ SendFriendMessage/Task：机制已授予（非死声明），声明冗余但保留（最小改动面）。④ flows:["*"]：2c 起 FlowTrigger 对 Nebula 机制固定（AllowedToolSetSpec「Nebula gets FlowTrigger without flows declaration」实测绿）——no-op 死声明，退役。⑤ mcpServers：D.1-9 退役对象；机制层已失效（effectiveMcpServers=Nil），定义层移除完成闭环 |
| `agents/project-dispatcher/agent.json` | tools 死声明两件移除（Write/Edit） | DispatcherFixedTools 无 Write/Edit，AllowedToolSetSpec「dispatcher 不给 Write/Edit…声明无效」实测绿——不在 allowed set；其余六件为机制已授予的冗余声明，保留 |
| `agents/general/agent.json` | **零改动** | 实文仅 name+description，无任何死声明 |

## 3. D.4 死引用核查记录（逐条证据）

**（a）converged 三角色 agent.json 死声明**（机制依据：ConvergedAgentNames → base=∅ + fixedToolsFor 静态集；allowed set = base∪fixed ∪ 过滤追加，见 AgentCore.buildAllowedToolSet）：

| 声明 | 位置 | 是否在 allowed set | 处置 |
|---|---|---|---|
| Nebula: Read/Glob/Edit/Write/Grep/Bash | tools 段 | 否（base=∅；静态集无文件工具；spec 实测绿） | 移除（staging） |
| Nebula: CheckIssues | tools 段 | 否（同上） | **保护令保留**（作者未裁定） |
| Nebula: SendFriendMessage/Task | tools 段 | 是（静态集授予） | 保留（非死声明） |
| Nebula: flows:["*"] | flows 段 | —（FlowTrigger 已机制固定，spec 实测绿） | 退役（staging） |
| Nebula: mcpServers:["opendataloader-pdf"] | mcpServers 段 | 否（2d 机制退役生效） | 退役（staging） |
| dispatcher: Write/Edit | tools 段 | 否（spec 实测绿） | 移除（staging） |
| dispatcher: NodeEdit/NodeList/NodeCancel/Read/Glob/Grep/Bash | tools 段 | 是（静态集授予） | 保留 |
| general | — | —（无任何声明） | 零改动 |

（Explorer CheckIssues / design-engineer Screenshot 为 **legacy** 定义死声明，不在本节点 converged 三角色范围；随 2e 归档处理。）

**（b）`~/.nebflow/tools/` 外部工具残留**（check-issues/issue/screenshot 三目录）：

- 实文核查：三目录各含 tool.json + 脚本（check-issues.sh/issue.sh/screenshot.cjs），外部工具注册名分别为 **CheckIssues / Issue / Screenshot**。
- 加载机制：GatewayMain.loadExternalTools → ToolLoader.reload（启动时扫描 `~/.nebflow/tools/` 注册 ScriptTool，带 file watcher 热载）——三目录的「消费方」只有这个**通用加载器**，主仓代码零直接消费方：
  - `grep -rn "check-issues" src/` → 0 命中；
  - `"CheckIssues"`/`"Screenshot"` 字面量 → 0 命中；
  - `"Issue"` 字面量 → 仅 AgentCore 两处（NebulaExclusiveTools 条目 + fixedToolsFor 派发处 parity carry——见下「未裁定价差」）与 AllowedToolSetSpec 断言；
  - "screenshot" 两处命中为无关注释（CompactService 提示词样例 / WebSocketRoutes 注释）。
- 结论：零业务消费方 → **归档命令给出（§7-③）**。注意：`~/.nebflow/tools/` 在 `~/.nebflow` repo 的 whitelist .gitignore 内（**未跟踪层**）——**git mv 不适用**，归档用普通 `mv`。
- 归档后效果（重启生效）：三 ScriptTool 不再注册；Nebula 的 mechanism 层 `+ "Issue"` 条目成为纯惰性名（本环境「No external tools loaded」日志即此形态的实测）。
- **未裁定价差（逐条记录）**：机制层 `"Issue"`（NebulaExclusiveTools 条目 + 派发处 parity carry）本节点**保留不删**——D.1-1 重构时作为 2c 行为 parity carry 迁移至静态集派发处并注释；理由：① Issue 现为活 ScriptTool（未归档前 Nebula 运行时真实收到），贸然删=语义变化；② 作者对「重启后 Nebula 是否保留 Issue/CheckIssues」尚未裁定（任务书明示）；③ 定义层 CheckIssues/Issue 声明受保护令。D.4 对机制层 Issue 的清理随作者裁定+归档动作自然收敛。

## 4. 隔离实例取证（NEBFLOW_HOME=/tmp/nb-2d-toolrefactor/isolated-home，端口 8283，开工 lsof 查 8283-8286 空闲；fat jar=2c 先例）

1. 启动：`NEBFLOW_HOME=… NEBFLOW_GATEWAY_PORT=8283 nohup java -jar nebflow-assembly-1.4.1-beta.54.jar --port 8283 --no-browser`；日志「gateway listening on 0.0.0.0:8283」「No external tools loaded」（isolated home 无 tools/ → Issue ScriptTool 缺席=惰性形态实测）。token 取自隔离 HOME auth.json。（首次后台托管启动被宿主 watchdog「无输出+无 CPU」启发误杀一次——与 ShellStuckDetector 同类启发；改 nohup 脱管重启，未触碰任何 8080 进程。）
2. **三角色工具面（REST GET /api/agents/:name，rest-*.json 存档）**：
   - Nebula：fixedTools=15 = **§C.1 十四件全 present**（Task/ProjectCreate/NodeList/AgentControl/Mail/SendFriendMessage/Delegate/FlowTrigger/FlowExecute/AskUserQuestion/Pop/Schedule/TransferFile/MemoryEdit）+ Issue（**已记录的 parity carry**，本环境未注册故 LLM 交付面=十四件整）；零文件工具 ✓；declared tools=[CheckIssues,SendFriendMessage,Task]（staged 定义解析正常，保护声明在位）。**【2026-09-06 00:48 增补】NodeList 已从 Nebula 面摘除**（out 边自动投递取代主动查图；dispatcher 面不受影响）——现恰十三件；本行取证反映 2026-09-04 时点快照。
   - project-dispatcher：fixedTools **== 7 件整**（Node 三件+读四件）✓。
   - general：fixedTools **== 8 件整**（裁定 5 原文序）✓——且该 fixture 声明缺省被实体层默认为 `["*"]`（EntityTypes:19 存量语义）仍只交付 8 件 = **机制收口胜过声明的额外佐证**。
3. **node 模版会话停注（会话结果取证）**：general 会话（隔离 fixture 声明 skills:["*"]+磁盘放 skill `catalog-probe`）三问证据（evidence-general-answer.txt）：
   > 1) **CATALOG=NO** — my prompt's "Skills" section only describes the mechanism … but this turn contains no catalog entries; the only "catalog-probe" match is under Standalone Agents … which is an agent listing, not a skill catalog entry.
   > 2) "Reads a file from the local filesystem."（Read description 首句原文）
   > 3) "When NOT to use (anti-pattern): if you can make a reasonable decision yourself, do NOT ask …"（order 400 下迁反模式句原文）
4. **双轨期对照（legacy 会话保留）**：catalog-probe-agent（legacy standalone，skills:["*"]）同问：**CATALOG=YES** <quote>- catalog-probe: 2d isolated instance skill catalog probe fixture</quote> ✓。
5. **description 自包含抽查（3 工具）**：AskUserQuestion（反模式句原文引用 ✓）/Read（首句引用 ✓ + 单元层「Live results/Never re-read/git diff」关键句断言 ✓）/Pop（professional tool→SVG→never hand-draw 单元+wire 层断言 ✓）——「删条件段后未见段 agent 不退化」判据成立：一般 agent 从未见旧段，用法指南经工具定义完整到达。
6. 清理：`lsof -p 35208`（cwd=/private/tmp/nb-2d-toolrefactor）≠ 宿主 94384（cwd=主仓）核实后 kill，端口释放，`rm -rf` 隔离 HOME ✓。

## 5. Spec 与全量 sbt

- **受影响 spec 更新**（只增不删语义——旧段断言的语义「指南必达」迁移到新断言，覆盖不降）：
  - 新文件 Phase2dToolRefactorSpec（16 用例）：D.1-1 收口/legacy 分层/D.1-11 通道删除/D.1-9 双向/D.2 四组关键句。
  - 新文件 Phase2dSkillCatalogSpec（4 用例）：开关逐角色 + 真实 NodeEngine 链 RecordingLlm 捕获（node 会话 systemStable 无 per-agent 目录；legacy 对照有；wire 层 tools description 含下迁文本）。
  - PromptSectionsSpec：8 个旧段注入断言 → 4 个「内建段不再注入（数据根隔离，不受宿主文件版条件段影响）」+ 排序链/630 断言同步（18 用例）。
  - ReminderWiringSpec：1 处 order-630 段断言 → 钉新不变量（无段 + TeamTaskList description wire 层含协议），**[verify-fix] 性质小修**。
- **变异验红 ≥1（真实执行）**：变异=临时删除 ReadTool description 的 order-410 下迁文本段（"Live results: read results are live…" 至 "Parameters:" 前）→ **红**：`Phase2dToolRefactorSpec.D.2: Read description 含 live 语义关键句（order 410 下迁）`（assert "live 语义段" 失败）+ `Phase2dSkillCatalogSpec.D.2 wire 层判据`（"Read description carries the order-410 live semantics at the wire level" 失败）→ 19 用例 2 红 17 绿；恢复备份后 **19/19 绿**（git diff 确认恢复后 ReadTool 仅含预期 +2 行）。
- **全量 sbt test（worktree 内前台真实跑，timeout 3600000ms 一次跑完）**：修复后终态 **Total 2314 / Failed 30 / Passed 2284 / Ignored 7**（约 8 分钟）。基线（2c，任务书口径 2286/21）→ 新增 28 用例（2314−2286）。
- **30 红逐条签名归因（全部环境性，零语义新增）**：
  | 套件 | 数 | 签名 | 基线对照 |
  |---|---|---|---|
  | SandboxSpec | 24 | `java.nio.file.FileSystemException: /Users/dev/.nb-sbx-dataroot-*: Operation not permitted`（~/EPERM） | 基线 15 同类；+9 为 e511a614（沙箱读白名单合并）新增 READLIST/MSG/MUT 用例，同签名同构 |
  | PopToolSpec | 1 | `~/.nebflow-pop-test-tmp.png: Operation not permitted` | 基线同签名 |
  | BashBackgroundHardTimeoutSpec / BashActivityBridgeSpec / ShellStuckDetectorSpec | 1/1/1 | CPU-busy 探测（"CPU-busy task must not be killed"/"CPU delta"/"no CPU activity detected"） | 基线「CPU 探测 Bash×2+ShellStuckDetector」同构 |
  | NodeSessionDeathFinalizeSpec | 首跑 1（终跑绿） | 时序 flake，单跑绿（本轮隔离复跑绿） | 基线同款 |
  | AcademicSearchSpec | 1 | `Crossref: Exception when sending request: GET https://api.crossref.org/...`（10s 超时） | **纯上游 7e945d2f stash 对照复现**——存量环境性（JVM HTTP 客户端对 crossref 不可达；shell curl 200 可达），与 2d 零关联；基线清单未列但同类网络环境性 |
  - （首次全量 31 红 = 上表 + ReminderWiringSpec 1 语义适配项，小修后终态 30。）

## 6. Commit 状态

| commit | 内容 |
|---|---|
| `7e945d2f` | 上游 2cQC nits 六项（本节点开工前已落库，无需代提交） |
| `83d4b8ea` | **2d 本体**（16 files，+594/−272：12 主代码 + 2 spec 修改 + 2 spec 新建） |
| `6dc4b398` | `git merge qc-2d-refactor --no-ff` 入 **main**（含 83d4b8ea + 7e945d2f；hook 自动 QC 因宿主自身 EPERM `~/.claude.json` 报错，合并本身已落） |

未 push、未碰 origin。**生效说明：机制层改动需宿主重启生效——本批未重启（纪律），主仓 main 已含全部改动。**

## 7. 宿主侧落地命令全集

```bash
# ① 主仓：已完成（本沙箱内真实落库）——
#    qc-2d-refactor: 83d4b8ea（含上游 7e945d2f）；main: 6dc4b398（--no-ff 合并）

# ② ~/.nebflow 定义层（EPERM → staging 已备 /tmp/nb-2d-toolrefactor/nebflow-defs/，逐文件 cp + 按文件 add）：
cp /tmp/nb-2d-toolrefactor/nebflow-defs/agents/Nebula/agent.json ~/.nebflow/agents/Nebula/agent.json
cp /tmp/nb-2d-toolrefactor/nebflow-defs/agents/project-dispatcher/agent.json ~/.nebflow/agents/project-dispatcher/agent.json
cd ~/.nebflow && git add agents/Nebula/agent.json agents/project-dispatcher/agent.json && \
  git commit -m "defs: 2d mcpServers 退役与死声明清理（Nebula 六件文件工具+flows no-op+mcpServers 退役，CheckIssues/SendFriendMessage/Task 声明按保护令/授予态保留；dispatcher Write/Edit 死声明移除）"

# ②b D.2 定义层闭环——文件版条件段退役（三文件与已下迁的工具 description 双重注入，须一并退役；.disabled 后缀不再匹配 *.md 加载 glob）：
cd ~/.nebflow && git mv prompts/sections/ask-user.md prompts/sections/ask-user.md.disabled-2d && \
  git mv prompts/sections/read-live.md prompts/sections/read-live.md.disabled-2d && \
  git mv prompts/sections/visual-reporting.md prompts/sections/visual-reporting.md.disabled-2d && \
  git commit -m "defs: 2d D.2 文件版条件段退役（内容已下迁 AskUserQuestion/Read/Pop description，双注入消除；voice.md/environment 不在范围保留）"

# ③ ~/.nebflow/tools 归档（⚠ 未跟踪层——whitelist .gitignore 不含 tools/，git mv 不适用，用 mv）：
mkdir -p ~/.nebflow/.archived-tools-2d
mv ~/.nebflow/tools/check-issues ~/.nebflow/tools/issue ~/.nebflow/tools/screenshot ~/.nebflow/.archived-tools-2d/
# （tools.md 为无关存量导出，不在 D.4 清单，不动）

# ④ 本报告归档：
cp /tmp/nb-2d-toolrefactor/20260904_phase2d-tool-refactor-report.md ~/.nebflow/docs/Nebflow/20260904_phase2d-tool-refactor-report.md
cd ~/.nebflow && git add docs/Nebflow/20260904_phase2d-tool-refactor-report.md && \
  git commit -m "docs: 阶段 2d 工具体系改造报告（§D 逐项落地/取证/归因）"
```

## 8. 遗留问题

1. **需重启生效**：机制层（fixedToolsFor 收口/SendFriendMessage 剥离/skill 停注/段下迁/converged mcpServers 退役）与定义层均待宿主重启后生效；本批未重启。
2. **Issue/CheckIssues 终裁**：作者未裁定项——现状=定义层声明保留（保护令）+ 机制层 parity carry（`+ "Issue"`）+ ~/.nebflow/tools 三外部工具待归档（§7-③ 执行后 Issue ScriptTool 消失，机制条目自动失活为纯惰性名）。D.4 机制层残留随裁定一并清理。
3. **legacy standalone catch-all（BaseTools）**：双轨期保留（Coder/Explorer/design-engineer 依赖实测）；随 2e 归档/阶段 3 删除。
4. **文件版条件段（§7-②b）**：宿主执行前，production 会话中三段仍经文件注入（与 description 双份并存，行为冗余无害）；执行后 D.2 完全闭环。
5. AcademicSearchSpec Crossref 红 = 沙箱 JVM→外网环境性（纯上游复现），非本批引入；网络恢复或宿主环境跑即绿。
6. general 实体层 tools 缺键默认 `["*"]`（EntityTypes:19 存量语义）——converged 下被收口压制（REST 实测仍 8 件），如需字段显式化属定义层美观问题，不影响工具面。
7. **【2026-09-06 增补】flow 三工具（FlowTrigger/FlowExecute/FlowReport）提前退役**——作者裁定 2026-09-06 00:11 提前执行工具面+自配置面子集（绕开 14 天稳定期判据）：注册摘除+工具文件删除+注入/剥离链路清名+面板三区与写回通道退役+per-agent tools 扫描停用。本报告 §3 各表相应行以该批为准；逐项核对与钉死断言见 `20260906_stage2d-toolface-retirement-report.md`。
