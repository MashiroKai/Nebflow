# 阶段 2d 工具面裁撤批实施报告——agent 自配置面退役 + flow 三工具退役

> 日期：2026-09-06 ｜ 节点：实施-agent自配置面与flow工具退役（Caller: project-dispatcher）
> worktree：`/Users/dev/Claude code/Nebflow/.nebflow/worktrees/实施-agent自配置面与flow工具退役`（分支同名）
> 基线：`b7456716`（main HEAD）
> 性质：作者裁定提前执行阶段 2 迁移的 2d 子集（原计划阶段 3 拆除项提前收口）
>
> **提交形态**：worktree index.lock 位于主仓 `.git/worktrees/<名>/`（沙箱 EPERM），git add/commit/rm 不可用——本节点按预案以 **commit-ready 文件清单**申报，提交由合并节点代执行。文件物理删除（三工具 .scala / 三个 spec .scala）用普通 rm 完成。

---

## 0. 决策钉死复核（执行未偏移）

| 决策点 | 钉死内容 | 执行符合性 |
|---|---|---|
| **A = 选项①** | 存量 team/flow 成员 agent.json 的 tools/skills/flows 声明**解析保留至阶段 3**；legacy 授能保留（禁写「字段已不授能」）；本批退役=面板三区+写回通道+flow 三工具+per-agent tools 扫描 | ✅ EntityTypes flows 字段仅改**注释**为 legacy 口径；AgentJson Decoder/Encoder/loadFromDir/toAgentDef **零改动**（含三字段 agent.json 解析照旧）；legacyFixedTools 双轨路径保留（team/flow 分支成员按本批口径更新） |
| **B = 最小口径** | Agent 详情页删三区；保留摘要头+Model preset 选择+System Prompt 编辑器（WS updateAgentSystemPrompt 保留） | ✅ 见 §1 保留清单；WebSocketRoutes updateAgentSystemPrompt 案例未触碰 |

---

## 1. 前端域核对表（§1 逐项）

### 1.1 删除项

| 项 | 文件 | 状态 |
|---|---|---|
| 三区渲染（tools/skills/flows） | `js/agentManager.js` | ✅ 删（含 tools toggle） |
| loadSkillsFlowsSection / fetchSkills / fetchFlows / setAgentSkillsFlows | `js/agentManager.js` | ✅ 删（写回通道前端侧） |
| FIXED_BASE_TOOLS / NEBULA_FIXED_TOOLS / resolveFixedTools / FIXED_CLASS_LABEL_KEYS / fixedClassLabelKey / splitFixedTools / LOCK_ICON_SVG | `js/agentManager.js` | ✅ 删 |
| state import | `js/agentManager.js` | ✅ 删 |
| agent modal 段：currentAgentName / renderToolsGrid / getCheckedTools / showAgentModal / hideAgentModal + initModals agent 绑定 | `js/modal.js` | ✅ 删（留退役注释） |
| import showAgentModal/hideAgentModal + `agentSystemPrompt`/`agentSystemPromptSaved` 两 WS handler | `js/main.js` | ✅ 删（**注意**：updateAgentSystemPrompt 的**发送侧**在 agentManager.js 保留，此两 handler 是保存确认广播的 UI 反馈，随 modal 删除） |
| `#agent-overlay` 整块（原 :310-325） | `index.html` | ✅ 删（运行实例 HTML 断言零残留） |
| `#agent-overlay/#agent-modal/#agent-modal-actions/#agent-modal-save` 从共享选择器组摘除 + Agent Editor Modal 专属块（#agent-tools-grid/#agent-mcp-grid/.agent-tool-check/.agent-field-label/#agent-name-input/#agent-desc-input/#agent-system-input/#agent-model-input 死样式） | `css/modal.css` | ✅ 删（留退役注释） |
| `#agent-modal-actions button` 两行（:211/:239 附近） | `css/sapphire.css` | ✅ 摘 |
| `.agent-detail-tools-grid/.agent-detail-tool-check(含.fixed)/.agent-detail-tools-fixed-label/.agent-detail-tools-config-label/.agent-detail-tools-specialty-label/.agent-detail-sub-hint/.agent-detail-skills-grid/.agent-detail-flows-grid/.agent-detail-skill-check/.agent-detail-flow-check/.agent-detail-chips-loading/.agent-detail-chips-empty` + 死样式 `.agent-detail-empty/.agent-detail-tools/.agent-detail-tool/.agent-detail-prompt` | `css/sidebar.css` | ✅ 删（留退役注释） |
| 孤儿 i18n keys：`modal.agentTitle`、`agent.namePlaceholder/descPlaceholder/systemPrompt/systemPromptPlaceholder/save/toolsFixedLabel/toolsConfigLabel/toolsFixedTip/toolsFixedSpecialty.{flow,team,orchestrator}/editTitle/toolsLabel`、`agentManager.{tools,skills,flows,noSkills,noFlows}` | `js/locales/en.js` + `zh-CN.js` | ✅ 删 |
| `'agent-modal-title'` map 行 / placeholders map（agent-*-input）/ agent modal buttons 段 | `js/i18n.js` | ✅ 删 |
| :557 注释（三区描述） | `js/plugins.js` | ✅ 改为「summary / preset / system prompt — sections retired 2026-09-06」 |

### 1.2 保留项（决策 B 最小口径）

| 项 | 证据 |
|---|---|
| 壳：fetchAgents / fetchAgentDetail / fetchAgentModel / populateModelTag / openAgentDetail / openAgents | agentManager.js 全部在位（DOM 断言 PASS） |
| 摘要头 + Model preset 选择 | openAgentDetail 渲染链保留 |
| System Prompt 编辑器（`#agent-detail-prompt-section` + viewSource/viewRendered toggle + textarea） | agentManager.js :314-338 在位 |
| WS `updateAgentSystemPrompt` 发送侧 | agentManager.js 保留 |
| i18n keys：agentManager.{loading,noAgents,saved,save,systemPrompt,viewSource,viewRendered} | en.js/zh-CN.js 保留（DOM 断言 PASS） |

---

## 2. 定义层写回通道退役核对表（三通道，可验证拒绝）

| 通道 | 文件 | 改动 | 拒绝形态 |
|---|---|---|---|
| ① AgentLibrary.updateTools | `agent/AgentLibrary.scala` | 方法删除（留退役注释） | 编译期不可达（无调用方） |
| ② AgentService.updateTools 代理 | `service/AgentService.scala` | 代理删除 | 同上 |
| ③ WS `updateAgentTools` case | `gateway/WebSocketRoutes.scala` :2959-2970 | case 保留但改为 `logger.warn` + wsSend error 帧 | **运行时可验证拒绝**：error 帧「updateAgentTools retired: per-agent tools are mechanism/plugin-managed since 2026-09-06; agent.json is no longer written from the panel」 |
| ④ REST `PUT /api/agents/:name`（skills\|flows） | `gateway/RestApiRoutes.scala` :1826-1831 | 改为 `logger.warn` + `Gone(...)` | **运行时可验证拒绝**：HTTP 410「agent skills/flows write-back retired 2026-09-06: per-agent capability config is definition/plugin-managed; agent.json is no longer written from the panel」 |

**GET 侧保留**：`GET /api/agents/:name` 响应字段（tools/skills/flows/fixedTools/systemPrompt）零改动——面板摘要头与观测面不受影响。

**定义层零改动清单**：`AgentJson`（Decoder/Encoder/loadFromDir/toAgentDef）零改动；`EntityTypes.scala` flows 字段仅注释改 legacy 口径。**含三字段的存量 agent.json 解析不报错**（钉死断言⑤，spec 解析路径回归绿）。

---

## 3. 机制层 flow 三工具退役核对表

### 3.1 删除/清名项

| 项 | 文件 | 状态 |
|---|---|---|
| FlowTrigger / FlowExecute / FlowReport 三注册 | `core/tools/registry.scala` | ✅ 删（留退役注释；grep 三工具名零残留） |
| FlowTriggerTool.scala / FlowExecuteTool.scala / FlowReportTool.scala | `core/tools/` | ✅ 物理删除（普通 rm，git 不可用） |
| FlowReportData + FlowReportStore | `core/tools/FlowReportStore.scala`（**新建**，1384 B） | ✅ 原样抽出——**裁决依据**：FlowDagExecutor（红线零触碰）import FlowReportStore（原定义在 FlowReportTool.scala 内），「删三文件」与「引擎零触碰」冲突的唯一解法=独立文件保留；引擎消费面零变化 |
| withFlowTrigger 注入步骤 | `agent/AgentCore.scala` | ✅ 删（isNebula val 保留） |
| 剥离集清名（:1653/:1655 原 FlowTrigger/FlowExecute 名） | `agent/AgentCore.scala` | ✅ 清 |
| buildToolList FlowReport flowContract describe 分支 | `agent/AgentCore.scala` :1680 | ✅ 删（留退役注释） |
| legacyFixedTools team 分支=BaseTools+Mail+SubTask+TeamTask*；flow 分支=BaseTools | `agent/AgentCore.scala` | ✅ 按 2d 口径更新（双轨路径本身保留至阶段 3） |
| fixedToolsFor / buildAllowedToolSet 注释 | `agent/AgentCore.scala` | ✅ 更新 |
| ContextRefresher.applyRuntimeOverrides FlowReport append | `agent/ContextRefresher.scala` | ✅ 删（flowContract 保活保留——slots 契约仍注入，引擎消费面） |
| ContextRefresher.refreshTurn flowCatalog binding | `agent/ContextRefresher.scala` | ✅ 改 `flowCatalog = ""`（停注；TurnContext 字段保留；for 缩进语法无尾逗号） |
| buildPerAgentFlowCatalog | `core/skill/SkillService.scala` | ✅ 删（留退役注释） |
| executeSkill flow 分支 | `gateway/WebSocketRoutes.scala` :4498 | ✅ 删（skill 激活不再触发 flow；直接走 skill 文件分支，留注释） |
| DelegateTool：类注释 / description「use FlowTrigger instead」行 / flowName 读取+拒绝分支 | `core/tools/DelegateTool.scala` | ✅ 删 |
| MailTool description「For triggering flows, use FlowTrigger.」 | `core/tools/MailTool.scala` | ✅ 删 |
| FlowNodeContract 注释（两个消费点已退役，contract 数据引擎仍注入） | `agent/AgentDef.scala` | ✅ 更新 |
| flows 字段注释改 legacy 口径 | `core/entity/EntityTypes.scala` | ✅ 仅注释 |

### 3.2 红线零触碰清单（grep 实证残留=原文保留）

| 文件 | 残留位置 | 内容 | 保留理由 |
|---|---|---|---|
| `core/entity/FlowDagExecutor.scala`（1347 行） | — | 零改动 | 红线：引擎执行器。import FlowReportStore 不变（抽出后仍编译） |
| `core/flow/FlowTreeActor.scala` | :19 | `// via the FlowTrigger tool.` 注释 | 红线零触碰 |
| `core/flow/FlowDagRunner.scala` | :14、:34 | 「Spawned by FlowTriggerTool…」/「one-shot FlowExecute flows」注释 | 红线零触碰 |
| `core/flow/RunningFlowRegistry.scala`（208 行） | :11 | 「one-shot flow triggered via the FlowTrigger tool」注释 | 红线零触碰 |
| `core/entity/EntityLoader.scala`（482 行） | :228 | 「predefined-flow compilation (LoadTool/FlowTriggerTool)」注释 | 红线零触碰 |
| `core/entity/FlowDagCompiler.scala` | :8、:101、:363 | 「在 FlowExecute 调用返回前」/错误 fix 文案「pass \"name\" to FlowExecute」/「dynamic (inline FlowExecute)」 | 红线零触碰（错误文案为引擎诊断语，非工具授能） |
| `core/project/NodeEngine.scala` | :28 | 「（无 FlowReport/verdict/slots）」注释 | 红线零触碰 |
| `agent/PromptSections.scala` | :171、:184 | flow node 提示词模板「中间产物…在 FlowReport 给出绝对路径」 | 红线口径：本批未触碰（提示词文案清理留阶段 3 提示词批次） |
| MCP 全部 | — | 零改动 | 钉死范围 |

> 引擎可工作性说明：flow 引擎（编译/执行/barrier/fanout）是 Node 架构运行时基座，与本批退役的「agent 侧工具授能」正交——flow 定义内 agent 不再通过 FlowTrigger/FlowExecute 工具触发 flow，但 DAG 编译校验（E-xxx）、barrier、并行 fan-out 全部照旧（DynamicFanoutSpec/BarrierSpec 语义断言全绿为证）。

---

## 4. 扫描层：ToolLoader per-agent 层退役

| 项 | 文件 | 状态 |
|---|---|---|
| 三处 loadNestedTools 调用 | `core/tools/ToolLoader.scala` | ✅ 删 |
| loadNestedTools 本体 | `core/tools/ToolLoader.scala` | ✅ 删 |
| layerPriority 删 "agent" 键 | `core/tools/ToolLoader.scala` | ✅ 删 |
| reload/loadAll 注释 | `core/tools/ToolLoader.scala` | ✅ 改 three layers |
| 层注释删 agent 行 | `core/tools/ExternalToolConfig.scala` | ✅ 删 |

**保留**：global / team / flow 三层外部工具加载 + watcher 的 team/flow 级监听——per-agent tools 扫描停用（钉死断言③）。

---

## 5. 钉死断言五条逐条落地证据

| # | 断言 | 落地证据 |
|---|---|---|
| ① | flow 三工具不在任何 agent 工具面（含 team/flow 类别） | AllowedToolSetSpec 新增「retired flow tools are unregistered」（TOOL_MAP/ALL_TOOLS 无三工具）+「retired flow tools are on NO agent's LLM tool face」六形态（team `*` / flow `*` / flow 显式声明 / flows 白名单 / standalone 显式 / Nebula `*`）→ buildToolList 实测无三工具。**两 test 绿** |
| ② | 面板写回通道关闭（可验证拒绝） | WS updateAgentTools → warn+error 帧；REST PUT → 410 Gone（:1830-1831 实文）。AgentLibrary.updateTools 方法本体删除=代码级不可达 |
| ③ | per-agent tools 扫描停用而 global/team/flow 级保留 | ToolLoader 三处调用+本体删除、layerPriority 无 "agent"；global/team/flow 加载与 watcher 保留（外部工具链路 spec 未触碰） |
| ④ | legacy 声明授能保留至阶段 3 | AgentJson Decoder/Encoder/loadFromDir 零改动；Phase2dToolRefactorSpec legacy 双轨断言改后仍验证 legacy 分支授能（team=BaseTools+Mail+SubTask+TeamTask*、flow=BaseTools）；EntityTypes flows 注释=「legacy 授能保留至阶段 3」口径 |
| ⑤ | 存量含三字段 agent.json 解析不报错 | AgentDef 结构体与 Decoder 零改动；定向 spec 集 AgentDef/EntityLoader 相关解析用例全绿（204/205，唯一红与本批无关，见 §7） |

---

## 6. 测试改写清单

| spec | 改动 |
|---|---|
| FlowTriggerToolSpec.scala / FlowExecuteToolSpec.scala / FlowTriggerExecutionSpec.scala | **物理删除**（纯工具层 tests 随工具退役） |
| AllowedToolSetSpec.scala | 类注释更新；CoreProbe.face() 探针新增；两退役钉死 test 新增（断言①）；team 期望集去 FlowExecute；FlowReport 三类别/flow wildcard/FlowTrigger whitelist 组/FlowExecute 组改退役断言 |
| ContextRefresherFlowInjectSpec.scala | 全文重写：flowContract+modelOverride 保活断言保留 + 第三 test「no longer re-appends retired FlowReport」 |
| Phase2dToolRefactorSpec.scala | legacy 双轨断言改 2d 口径；遗留问题第 7 条增补 |
| DelegateToolSpec.scala | flow 参数 test 改「stray flow parameter is ignored」（missing-agent 路径+无 FlowTrigger 文案） |
| StrictVerdictSpec.scala | 全文重写：tool-level 5 tests+sv5 删；sv4 改 StoreReportingLlm 直写 FlowReportStore；sv1/sv2/sv3+describe 保留 |
| BarrierSpec.scala | VerifyLlm fake 改 verdictQueue.evalTap 直写 FlowReportStore |
| DynamicFanoutSpec.scala | PlannerLlm+D9 内嵌 fake 改直写 FlowReportStore |
| FlowNodeSupervisionSpec.scala | **零改动**——fake 发 FlowReport tool call → 引擎回 No such tool ToolError → fake 第二轮回 done，实测绿（退役后自然兼容路径） |

**fake 直写 FlowReportStore 模式**（本批测试改写的统一手法）：引擎 spec 的 LlmHandle fake 原以 FlowReport tool call 上报 verdict/slots；工具注册删除后该管线不存在，fake 改为直接调用 `FlowReportStore.set(sessionId, FlowReportData(...))`——引擎消费面（FlowDagExecutor 读 store）零变化，全部语义断言（barrier 计时/fanout 聚合/verdict 路由）原样保留并通过。

---

## 7. 验证结果（门禁六项）

| # | 门禁 | 结果 |
|---|---|---|
| ① | 分域 commit | **git 不可用**（worktree index.lock EPERM）→ 按预案以 commit-ready 文件清单申报（见最终回报） |
| ② | sbt compile + 定向 spec | **compile 绿（EXIT=0）**；定向 spec 集 15 suites：Total 205 / Passed 204 / Failed 1（唯一红=FlowDagCompilerSpec 用户数据失败，归因见 §8） |
| ③ | verify-web-assets.mjs（隔离实例） | **PASS**：281 文件全 200（详见 §7.1） |
| ④ | checkJs | **PASS**：289 errors < baseline 313（-24，删死代码顺带消减既有错误），无新增；typescript 解析用主仓 node_modules 软链（worktree 无 node_modules，非 npx fallback） |
| ⑤ | 面板改前/改后截图（降级 DOM 断言） | **PASS**（修正口径 5/5，详见 §7.1——首轮 3 FAIL 均为注释字样假阳性） |
| ⑥ | 逐项退役核对表 | 本报告 §1-§4 |

### 7.1 隔离实例取证（门禁③⑤）

- 隔离参数：`--home /private/tmp/qa-ft-toolface --port 8093 --no-browser`（≠8080）
- 进程纪律：脚本 trap 'cleanup' EXIT INT TERM；cleanup 内 lsof 定位 PID → `lsof -p <pid> | grep cwd` 验身（仅杀 cwd 匹配 qa-ft-toolface/worktree/sbt 隔离目录者）→ kill -KILL → 端口复查释放；**实测**：instance 9s 就绪，收尾 killed 67444（java，cwd=worktree）+ sbt 66820，`PORT_RELEASED_FINAL_OK`；8093 复查 free；宿主 PID 73808 与 :8080 全程未触碰（8080 监听者=宿主 java 73808，仅观测）
- 门禁③结果：**VERIFY_EXIT=0**——`checked=281 skipped=0 failures=0`，`C1 PASS: every web asset is served with 200`
- 门禁⑤结果：浏览器不可用 → 按任务书预案降级 DOM 断言留痕。首轮 21 项断言中 3 项 FAIL 经查**全部为退役注释字样的 substring 假阳性**（agentManager.js:40 / modal.js:214 / modal.css:332 注释提及已删符号名）——非功能残留。**修正口径（剥离注释行后匹配）重跑：5/5 全 PASS**（`DOM_ASSERTS_ALL_PASS (comment-stripped)`，脚本 /private/tmp/nb-ft-dom-assert.mjs）：删除面 index.html 无 #agent-modal/#agent-overlay、agentManager.js（code-only）无 loadSkillsFlowsSection/FIXED_BASE_TOOLS/setAgentSkillsFlows、modal.js（code-only）无 showAgentModal/getCheckedTools、modal.css（code-only）无 agent-modal 规则；保留面 fetchAgentDetail/fetchAgentModel/populateModelTag/fetchAgents/#agent-detail-prompt-section/updateAgentSystemPrompt 全在位
- 改前/改后截图：节点环境无浏览器 → 降级为上述断言留痕

---

## 8. 唯一 spec 红：FlowDagCompilerSpec 归因（非本批引入）

- 失败用例：`all predefined flows in ~/.nebflow/flows compile with zero errors`（FlowDagCompilerSpec.scala:337）
- 失败内容：宿主真实 home 数据 `/Users/dev/.nebflow/flows/memory-consolidation/flow.json` 仅 1 个 node（scanner）→ 编译器 E-212「needs at least 2 nodes — a single agent is an agent + skill, not a flow」
- 归因：该 spec 直接扫描**宿主 home 用户数据**（环境性依赖）；`memory-consolidation/flow.json` 修改时间 **2026-09-05 22:58**（宿主侧，晚于本节点开工）；E-212 是 FlowDagCompiler 既有校验（本批零触碰该文件）。**基线 b7456716 上跑同 spec 必然同红**——用户数据失败，非代码回归
- 处置：不谎报绿；同 suite 其余 42 用例（E-001…E-401/R3/error format/collect）全绿。建议随宿主批次修复该 flow 定义（单节点改 agent+skill）或 spec 侧对预定义 flow 失败改 warn 口径——超出本节点范围

---

## 9. 生效声明

**宿主 8080 跑的是旧构建**：本批全部改动（前端三区删除、写回通道拒绝、flow 三工具退役、ToolLoader 层裁撤）**需宿主实例重启后才在 :8080 生效**。重启窗口由 Nebula 统一安排（作者预览与端口纪律 2026-09-05）。

---

## 10. spec 文档同步加注（本批）

| 文档 | 加注点 |
|---|---|
| 20260903_stage2-migration-plan.md | §4.1 编排执行行/注入层行/agent 配置面行/M5 行——四处【2026-09-06 增补】 |
| 20260904_phase2d-tool-refactor-report.md | D.1-1 表行（legacy team/flow 分支减项）+ 遗留问题第 7 条 |
| 20260904_phase2c-agent-convergence-report.md | 表格 #2（NebulaOrchestrationTools 十二件）/ #3（双轨保留提前终结）/ 配套 spec 行 / §3.3 Nebula fixedTools 取证行——四处【2026-09-06 增补】 |
| 20260904_phase2e-finale-report.md | §4.a Nebula 恰十四件行——【2026-09-06 增补】现恰 12 件 |
| skills-to-plugins.md | flow-execute 归档证据③——FlowExecuteTool.scala 已物理删除 |

---

## 11. 追加域记录（2026-09-06 00:48 裁定：Nebula 工具面摘 NodeList）

同 worktree 追加域节点（作者 00:48 裁定）在本批之上再摘 Nebula 面的 NodeList：

- **改动**：`AgentCore.NebulaOrchestrationTools` 移除 `"NodeList"`（恰十四件 → **恰十三件**）；dispatcher 的 `DispatcherFixedTools` 不动（边界：仅摘 agent 工具面）；NodeListTool 本体/服务端 API/前端 Flow Map 零改动。理由：节点结果沿 out 边自动投递 Nebula，主动查图与「全量派发 + pending 节点、不维护状态清单」的裁定职责重叠。
- **spec 断言同步**：NebulaSixBaseToolsSpec（计数 14→13 + 负向钉死 + 锚点扩至 NodeList）、Phase2dToolRefactorSpec（静态集/交付面）、AgentConvergenceSpec（交付矩阵 + forbidden 集 += NodeList）、AllowedToolSetSpec（§C.1 固定集 + 新增退役钉死 test 含 dispatcher 边界保留断言）、CardToolRegistrationSpec（注释计数）。
- **本报告 §10 增补注记的计数纠偏**：§10 中「NebulaOrchestrationTools 现为十二件」「现恰 12 件」与代码实况不符（08:40/23:34 裁定后恰 14 件，本批 NebulaSixBaseToolsSpec size==14 绿为证）——已在 20260904_phase2c/phase2e 两报告对应行以【00:48 再增补·计数纠偏】显式纠正，现恰十三件。
