# 功能批：subagent 通用化 + agentflow 用法审计 —— 交付 REPORT

- 批次：subagent-generalization + agentflow-audit（作者令 2026-10-24 10:15）
- 分支：`delegate/delegate-nebflow-1cb7b55c`
- 腿① = 实现 + spec（已改码）；腿② = 纯调研报告（本文件，零代码改动）

---

## 腿① subagent 通用化

### 1. 现状侦察（改前）

| 面 | 位置 | 改前形态 |
|---|---|---|
| 工具白名单单点 | `src/main/scala/nebflow/agent/AgentCore.scala:707` | `SubagentFixedTools` = 恰 5 件只读（Read/Glob/Grep/WebSearch/WebFetch） |
| 唯一注入入口 | `AgentCore.scala:755 fixedToolsFor` → `case "subagent"`（:802 改前） | 按名收敛，机制固定，agent.json 声明恒失效 |
| spawn 点 | `src/main/scala/nebflow/core/tools/BlockingSubagent.scala:53` | NodeRunner spawn `subagent` def，阻塞到完成，最终文本 = 工具结果；15min 硬顶（:45）；叶子 reap（:175） |
| 提示词 | `src/main/scala/nebflow/core/entity/BuiltinAgents.scala:589 SubagentPrompt` | 「read-only recon specialist… NO write tools, no shell」 |
| 只读根因 | —— | 不是 Guardrails/权限层特判，而是固定集常量本身只列 5 件只读工具——**纯定义面收窄**，与 project node 差的就是 Write/Edit/Bash/AskUserQuestion 四件未列 |
| 与 project node 面差 | `AgentCore.scala:700 NebflowFixedTools`（9 件）vs 5 件 | 缺 Write/Edit/Bash/AskUserQuestion；node 另有 node_report/引擎接线，subagent 无 |

### 2. 升级方案（已实施）

`subagent` = 通用 agent 位，固定面扩为 **恰 9 件 = BaseTools 六件（Read/Write/Edit/Glob/Grep/Bash）+ AskUserQuestion + WebSearch + WebFetch**：

- 能写文件、跑命令、提问用户、上网——端到端小任务闭环（写→跑→验→交付）。
- 身份/授权收敛**零放宽**（机制层，非提示词）：
  - **叶子**：Subagent/AgentFlow/ChainWrite ∈ RootExclusiveTools → `exclusiveToolsFor("subagent")` 全量剥（`AgentCore.scala:272-282`）；固定集本身也不列。
  - **派发权**：Delegate ∈ RootExclusiveTools 恒剥；Mail/SubTask 不在面内且收敛名声明恒死。
  - **根专属闸**：Pop/AgentControl/Task/Schedule/node_report/ListFriends/TaskSearch 照常全剥。
  - 与 project node 同闸——「不许越过 delegate 派发权与 Pop 根专属」结构性不可达。
- 文案面同步（模型可见面与机制面一致）：
  - `SubagentPrompt` 重写（BuiltinAgents）：通用 agent 身份 + 工具面 + 端到端纪律 + 凭证/进程安全句；不再出现 read-only。
  - `SubagentDescription` / `SubagentTool.description` / `AgentFlowTool` Family 段与身份闸文案 / nebflow 节点面与 delegate 面提示词的 Subagent 行（分工句升级为「bounded single-leg work」）。
  - `BlockingSubagent.promptWithRule` 增注 `PluginDiscoveryLine`（子面现有 Bash，查询通道可达，补为第三注入面；Defaults.scala 注释同步）。
- 面板交互与 delegate 同权核对（侦察结论，本批零改动即成立）：
  - **可见**：subagent 行经 activeAgents 快照 + wsSend 路由在子智能体面板渲染（`WebSocketRoutes.scala:3012`、`DelegateTool.routeWsSendFor`）。
  - **可审计**：SubAgentTaskStore 任务行（prompt/status/时间戳全留痕）+ 父会话通知 + InjectedInboxMirror UI 流镜像。
  - **可续**：**设计上不开放**——subagent 是一次性阻塞调用契约（prompt 进、交付物出），续作 = 调用方再发一次 Subagent；人类点击续聊需要 Mail 地址/复活链/reap 语义整套改造，且与「结果已作为工具值返回」语义冲突。该续聊通道归 delegate（`delegate:<id>`）专属。此为明确的设计决定，列入未做项。

### 3. 安全模型（只读解除后的护栏清单）

| 护栏 | 状态 | 说明 |
|---|---|---|
| 全局 SafetyMode + ToolReversibility 危险命令确认 | **原样在场** | 判据按档位、会话身份无关（`permissions.scala:164-199`）——subagent 的 Bash/Write 拿到的是同一条确认链；ConfirmEdits/AutoEdits 档 `rm -rf` 类命令需用户点卡确认 |
| 身份闸剥离（RootExclusiveTools） | **原样在场** | subagent 身份全量剥（见上），spec 钉死 |
| 凭据面 | **不动** | SecretInjection C-case 白名单按 agent 名解析（`BashTool.scala:508-520`），subagent 名默认空白名单 ⇒ 零注入，与改前一致 |
| AskUserQuestion secret/OTP 通道 | **不动** | 工具内机制，与身份面无关 |
| sleep-poll 护栏 | **原样在场** | shell 级 `SleepPollGuard`，会话无关 |
| 叶子纪律（无派发/消息/建链） | **保留** | 固定集 + RootExclusiveTools 双保险；理由：扇出归调用方，子会话不建链（与 delegate 面 ChainWrite/Mail 身份闸同哲学） |
| 15min 硬顶 + 深度闸（MaxDepth=5） | **原值在场** | `BlockingSubagent.Timeout` / `DelegateTool.MaxDepth`，spec 钉死 |
| 面板 cancel | **原样在场** | SubTask ∈ CancelableKinds（降级直杀路径，留痕完整） |
| 工作区姿态 | 随父会话 | subagent 继承父 delegate/node 的 projectRoot（无独立扩大）；与父自身能触及的范围一致 |

**仍保留的限制**：叶子（不派活/不建链/不消息）、无 Pop/AgentControl/Task 等根专属件、一次性阻塞契约（终态 reap，无复活）、不注册进 SessionStore（不进侧栏）。理由：subagent 定位 = 调用方的「手足」，不是「分身」；分身能力（续聊/复活/派发）在 delegate 位。

### 4. 工具面前后对照

| 件 | 改前 | 改后 |
|---|---|---|
| Read / Glob / Grep | ✓ | ✓ |
| WebSearch / WebFetch | ✓ | ✓ |
| Write / Edit | ✗ | ✓ |
| Bash | ✗ | ✓ |
| AskUserQuestion | ✗ | ✓ |
| Delegate / Mail / SubTask | ✗ | ✗（保留限制） |
| Subagent / AgentFlow / ChainWrite | ✗ | ✗（叶子，保留限制） |
| Pop / AgentControl / Task / Schedule / node_report 等 | ✗ | ✗（根专属闸，保留限制） |

### 5. 改动文件（腿①）

- `src/main/scala/nebflow/agent/AgentCore.scala` — SubagentFixedTools 9 件 + 注释
- `src/main/scala/nebflow/core/entity/BuiltinAgents.scala` — SubagentPrompt/Description/NebflowDescription/节点面/delegate 面文案
- `src/main/scala/nebflow/core/tools/SubagentTool.scala` — 工具描述
- `src/main/scala/nebflow/core/tools/AgentFlowTool.scala` — Family 段 + notDelegateError + MaxSteps 可见性（private[nebflow]）
- `src/main/scala/nebflow/core/tools/BlockingSubagent.scala` — promptWithRule 增查询行 + 注释
- `src/main/scala/nebflow/shared/Defaults.scala` — 注入面注释
- `src/main/resources/web/js/main/bgAgents.js` — 注释订正
- spec：`SubagentGeneralizationSpec`（新）、`DelegateToolSpec`（面钉改写）、`SleepPollStartupRuleSpec`（promptWithRule 新形态）

---

## 腿② agentflow 用法审计（纯报告）

### 三句话结论

1. **agentflow 是活代码且形态健康**：已收敛为「一个引擎（NodeEngine + delegate 作用域 flow-map）、两个提交面（AgentFlow 薄壳 / ChainWrite 整链登记），同闸同底座」；但**只有 delegate 会话够得到**——root 与 project dispatcher 都不可直调，派发唯一入口是 Delegate。
2. **僵尸面集中在旧 flow 引擎与三处不对称**：`FlowDagRunner/FlowDagExecutor` 生产零调用方（仅 spec 驱动，挂 #1229 物理删除）；ChainWrite 建的链不升 `orchestrationMode` 导致链视图 steps 恒空（UI 不对称）；MailTool 的 Dispatcher 腿挂载面已死、代码面还活着。
3. **明确建议**：保留「一引擎两提交面」与 delegate 身份闸不动；修三处不对称（升级器挪进 registerChain 单点 / 删 Dispatcher Mail 腿 / 清过时注释）；退役挂 #1229 批次随 flow 引擎收口闸一并物理删除，**不与 subagent 通用化合并**（两体系职责正交：agentflow 管 delegate 作用域内结构化并行，subagent 管单腿执行）。

### 1. 子系统现状

- **节点/链/分发器语义**：ChainWrite 整链校验（≤12 节点、id/name 唯一、Kahn 判环、deps 规范化）→ 原子落盘 → 入口节点后台放行 → 末端自动补 `(pass)Nebula` 根边（重映射为投创建者 delegate 本人，`notifyTargetKind=CreatorDelegate`）；out 边 `:result` 载荷投递 + 三闸收口（verdict/互斥/链控）；Mail `node:<id>` 路由三态（running 注入 / pending 追加 / 终态拒绝，`NodeDelivery.sendNodeMessage` 单点）。
- **入口**：AgentFlow（delegate-only 薄壳）+ ChainWrite/NodeEdit/NodeList/NodeCancel（delegateSession 尾段挂载，`NodeTools.DelegateNodeToolsFace`）+ Mail node: 路由（delegate 作用域腿）。Nebula 直调被双层挡住（RootOrchestrationTools 无此件 + 工具内 isDelegateSession 闸）。
- **谁在用**：生产调用面 = delegate 会话的提示词三件（AgentFlow/ChainWrite/Mail node:）；root 只经 Delegate 间接触发；dispatcher 只操作 per-project 旧面 flow-map。

### 2. 用法审计关键数字

| 数字 | 值 | 锚点 |
|---|---|---|
| 步/节点上限 | 12（两提交面单源 ChainWriteTool.MaxNodes） | `ChainWriteTool.scala:55`，spec 已钉 |
| registerChain 生产调用方 | 2（ChainWriteTool.call + AgentFlowTool 薄壳） | `ChainWriteTool.scala:267`、`AgentFlowTool.scala:181` |
| AgentFlowTool.call 生产直接调用 | 0（唯二入口 = 注册表 + 通用分发） | `registry.scala:71` |
| spawnAgentActor 生产调用点 | 8（delegate×2 / subagent×2 / 节点×2 / dispatcher×1 / **legacy flow×1 死**） | `DelegateTool:764`、`DelegateRevival:160`、`SubTaskTool:275`、`BlockingSubagent:82`、`NodeStarter:696`、`NodeLoopRunner:91`、`ProjectActor:2080`、`FlowDagExecutor:1203` |
| ChainRead 工具 | **不存在**（读面 = NodeList chains[] + REST ChainsReadFace） | 全仓零匹配 |
| orchestrationMode 写点 | 3（DelegateTool 置 single / SubAgentTaskStore 升 subagent-star / 仅 AgentFlowTool 升 agentflow） | `DelegateTool:823`、`SubAgentTaskStore:125`、`AgentFlowTool:187` |
| dispatcher 的 Mail | 挂载面死（2026-10-02 批摘除）、代码腿活（deliverToNode + 描述文案） | `AgentCore.scala:644-647`、`MailTool.scala:1060-1098,527` |
| 旧 flow 引擎活消费 | `nebflow.core.flow` 包 13 文件仅 RunningFlowRegistry/MailIdleGate 还被 idle/cancelFlow 消费；FlowDagRunner 零调用方 | `FlowDagRunner.scala`、`AgentFinishTurn.scala:794` |

### 3. 死路径 / 僵尸语义清单

1. `FlowDagRunner` / `FlowDagExecutor`+`FlowDagCompiler`（1387 行，仅 spec 驱动）——挂 #1229。
2. MailTool Dispatcher 腿（`SenderRole.Dispatcher` + `deliverToNode` + `:527` 文案）——挂载面死。
3. orchestrationMode 半僵尸：ChainWrite 不升级 ⇒ 其链在 ChainsReadFace 视图 steps 恒空。
4. `delegateApi.js:25`「候 N1 定稿挂真 provider」注释过时（provider 已挂）。
5. `flowAgentPopup.js` 旧引擎 popup 残留面——随 #1229 核。
6. per-project 旧面 `FlowMapStore.open`（:2020）——#1229 收口后转只读留档再删。

### 4. 与 delegate 体系的重叠面

- **AgentFlow vs Delegate**：会话内结构化并行 vs 跨会话后台单任务——分工清晰（AgentFlowTool Family 段即口径），legacy 派发形态被案 A guard 显式改道 Delegate。无功能冗余。
- **ChainWrite vs NodeEdit**：同底座两粒度（整链原子登记 vs 单节点编辑/restoreChain），description 已互相引用——保留。
- **node_report vs delegate_report**：同构双件按会话身份分化——保留。

### 5. 结论建议（不和稀泥）

| 项 | 裁决 | 理由 |
|---|---|---|
| 「一引擎两提交面」+ delegate 身份闸 | **保留** | 结构干净，调用面全部在册 |
| ChainWrite 后补 upgradeOrchestrationMode(agentflow)（或 ChainsReadFace 改按链存在性显示 steps） | **修**（小改，挪进 registerChain 单点） | 消除两提交面 UI 不对称 |
| MailTool Dispatcher 腿 | **删**（代码腿 + 文案，单小批） | 挂载面已死，代码面活着是误导源 |
| `delegateApi.js:25` 过时注释 | **顺手清** | — |
| FlowDagRunner / FlowDagExecutor / flowAgentPopup / per-project 旧 flow-map 面 | **退役，挂 #1229 收口闸** | 生产零调用方；活消费面（idle/cancelFlow）先改挂再删 |
| agentflow 与 subagent 通用化合并 | **不合** | 职责正交：agentflow = delegate 作用域链编排；subagent = 单腿执行器（本批已升级为通用 agent 位）。唯一联动点：subagent 保持叶子（无 AgentFlow/ChainWrite）正是为了不侵蚀 agentflow 的派发权边界 |

---

## 交付物

- 代码 + spec：本分支（上列 8 个 main 文件 + 3 个 spec 文件）
- 本报告：`reports/20261024_103508_subagent-generalization-agentflow-audit__delegate-nebflow-1cb7b55c/REPORT.md`
