# Agent 监管三件套设计 — Manager AgentControl / Nebula 全局管控 / 循环检测中止

> 版本日志：v1.0（2026-08-27，Explorer 初稿，待用户裁定）
> **v1.2（2026-08-27 深夜，Backend 实施状态）——四 Block 全部实施完毕**（feat/agent-supervision-trio，基线 main 含 #8 ask 移除）：Block 0 注册链补全 @1f20778b（Team/Manager/Ephemeral parentSessionId 链 + managerOf/parentForRecord；fork 部分按 19:22 裁定砍除）；Block 1 Manager AgentControl @813233af（controlGrant 机制层授予 + 子树 scope 收紧 §B3/§B4）；Block 3 LoopGuard @740b85f7（S1/S2/S3 三信号 × L0 提醒/L1 终止 turn/L2 冻结阶梯 + save-turn 豁免 + AgentRecord 镜像字段）；Block 2 Nebula 全局权 @aba3a995（Team 分支 callerIsRoot 放行 + 杀 Manager confirm=true+reason 双确认门 + nebflow.audit 审计行 + doList Team 不再标 read-only）。验证：AgentControlToolSpec 16/16（含 ListAppender 审计行捕获）、AgentControlE2ESpec 5/5（AC-T1/T2 更新至 Block 1 子树门 + Block 0 注册链生产形态）、LoopGuardSpec 15/15 + LoopGuardWiringSpec 3/3（主会话豁免=Root 变体、depth=1=成员/SubTask 变体，§F4 覆盖）、变异验红 ×2 独立（root 放行/确认门各一）。AC-T1/T2 语义迁移记录：v2 时代「同桶非父 → kind read-only 拒绝」在 Block 1 子树门下变为「非注册 Manager → Permission denied（子树外）」；kind read-only 分支保留为纵深防御。
> **v1.1（2026-08-27 晚，Backend 修订，实施基线）**——两项后续事实落地后的裁剪与校准：
> ① **用户 19:22 裁定**：「ask 删除后 fork 问题随之消失，监管三件套不建 fork 专用机器（Fork kind/注册点全砍），仅保留 Team/Ephemeral 的 parentSessionId 补全 + Manager 子树工具 + 循环检测器」。据此本文所有 fork 专属条目（§B1 AgentKind.Fork、§B2 fork 行、§C1 Fork 列、§D6、Block 0.1/0.2、F1 fork 用例、F4-2）**全部作废**，正文中以「~~删除线~~ ⛔v1.1」标注；Block 0 收缩为「Team/Ephemeral 注册链补全」。
> ② **#8 Mail ask 移除已实施**（feat/mail-ask-remove @28de86c9，doFork 整块删除 ~470 行）——本文 v1.0 行号基于 pre-#8 的 cc7b05f0，post-#8 锚点对照表见本节末尾；正文行号未逐一改写，实施时以下表为准。
> ③ 用户裁定背书（任务转达）：「turn 级中止≠actor 级 Stop」成立（§D3 划界定案）；S2 轮预算 save 阶段豁免定案（§D1）。
> **Block 2（Nebula 全局权）保留 Team 部分**（Fork 列作废）——裁定针对 fork 机器，Block 2 的 Team cancel/restart + 杀 Manager confirm/审计非 fork 机器，且任务书明列。
>
> **v1.1 post-#8 行号锚点表**（base = feat/mail-ask-remove @28de86c9）：
>
> | v1.0 主张 | post-#8 实际 | 备注 |
> |---|---|---|
> | AgentKind enum protocol.scala L248 | L247 | 7 case，无 Fork（维持） |
> | MailTool doFork L266-407 | 已删除 | #8 |
> | Team activateAgent 注册 L1083 | **L748-758** | 无 parentSessionId 确认 |
> | EphemeralAgentRunner L93-99 | L93（rootSid=L55 self） | 无 parentSessionId 确认 |
> | AgentControlTool CancelableKinds L90 / RestartableKinds L91 | L90 / L91 | 不变 |
> | AgentControlTool kindRejection L100-107 / withGuardedRecord L168-209 / doRestart L381-425 | L93-110 / L168-208 / L375+ | 微移 |
> | AgentCore buildAllowedToolSet L1345 / teamTaskGrant L1353-1357 | **L1317 / L1352-1356** | isTeamLead 参数已在签名 |
> | AgentCore NebulaExclusiveTools L1775-1780 | L1766 | = {Schedule, Delegate, AgentControl, Issue} |
> | AgentCore pipeToolExecutions L819-994 / touchRegistryActivity L976 | L818-993 / def L1720 | L976 是环内调用点 |
> | AgentActor guardSaveTurn L3899 | **L3911** | |
> | SubTaskTool 注册 L317/L331 | L182（parentSessionId=ctx.sessionId ✓） | 已写 |
> | DelegateTool 注册 L451/L463 | L340/L357（✓） | 已写 |
> | TaskStuckWatcher scannedKinds L75 / 判据 L115-127 / 提示语 L143 | 不变 | L143 提示语随 turn 级裁定更新 |
> | FlowTreeActor Manager 注册（v1.0 未列） | TeamSessionRegistry.registerActor L62-71 | **Manager 本身也无 parentSessionId**——同属注册链缺口，v1.1 纳入 0.3 |
>
> 背景：用户 2026-08-27 19:02 三项需求；同日 fork/html-builder 事故（幻觉路径 measure-slots.mjs 无限重读 20 分钟、两次 cancelAgent 无效）把三缺口同时暴露。
> 方法：只读探索 Nebflow 主仓（`/Users/dev/Claude code/Nebflow` @cc7b05f0）+ 当日 nebflow.log 取证 + 既有方案交叉（let-it-crash / 冻结式错误恢复 v2 e61d664 / flow 节点监管 f5bc6059 / 分类基线 §C）。未改任何代码或定义。
> 结论速览见 §0；验收见 §F。

## 0 结论速览

1. **fork 不可见是一切缺口的地基**：`MailTool.doFork` 从头到尾不写 agentRegistry（零注册代码），fork 对 AgentControl list / TaskStuckWatcher / cancelAgent 面板三面同时隐形——今日两次 cancelAgent 均因 `registry.get → None` 无声失败。注册表补全（§B）是三需求的共同前置。
2. **Manager 无武器是两层断点的叠加**：授权层 `NebulaExclusiveTools` 把 AgentControl 从一切非 Nebula agent 剥掉（agent.json 声明了也没用）；身份层 Team 成员注册时 `parentSessionId` 为空，v2 冻结方案已落地的「直接父放行」判定（`callerIsDirectParent`）对 Team 永远为 false——武器和弹药都在，扳机被两颗螺丝锁死（§A3）。
3. **Nebula 全局权缺口极小**：工具层对 root 桶调用者的 cancel/restart 白名单只差 Team/Fork 两个 kind 分支 + 杀 Manager 的确认与审计（§C）。
4. **循环检测 = 补「高活动零进展」轴**：TaskStuckWatcher 的哲学是「无活动 = 病」，今日事故是「每 4 秒失败一次 = 更重的病」；检测器挂在 AgentCore 工具环（单一 choke point、fork 天然覆盖），处置阶梯 警告→终止 turn→冻结待人工，复用 checkpoint restart（§D）。
5. **与既有机制最大冲突**：watcher「Team agents are never auto-stopped」政策——本设计以「turn 级中止 ≠ actor 级 Stop」划界：Team 成员的循环 turn 可被自动终止，actor 永不自动停（与政策字面冲突但与意图一致，需用户裁定确认，§D3）。

## A 现状差距：三需求 × 现有能力 × 缺口根因

### A1 差距总表

| 需求 | 现有能力 | 缺口 | 根因（源码级） |
|---|---|---|---|
| 1. Manager 管自己 team 子树 | AgentControlTool 已有 `callerIsDirectParent` 分支（L100-107）+ doRestart Team 分支（L381-425，Stop + MailTool.activateAgent 断点重建）——**工具层 v2 §5.3.1 已落地** | Manager 根本拿不到这个工具；拿到了也管不了 Team 成员（父判定失效） | 双层断点见 §A3 |
| 2. Nebula 全局 cancel/restart | root 桶调用者全权：Delegate/SubTask/Ephemeral cancel + Delegate(ephemeral)/SubTask restart（CancelableKinds L90 / RestartableKinds L91） | Team kind 只读（kindRejection L103-108）；fork kind 不存在；无杀 Manager 确认/审计 | kind 白名单未随 let-it-crash 裁定第 6 条（20260819 文档「AgentControl team 只读政策放宽」）更新 |
| 3. 循环检测与中止 | TaskStuckWatcher「Processing + 10min 无活动」（L115-127）；guardSaveTurn 三防线（轮预算 10 / 同 (path,hash) 写 ×3 / 漂移 2 击，AgentActor L3899+）——仅 save 阶段 | 「高活动零进展」零覆盖；fork 不在扫描面（未注册）；检测后无处置阶梯 | watcher 判据只有 lastActivityMs 一个轴；工具环无通用轮预算 |

### A2 fork 不可见·不可杀的源码级根因

fork 生成路径 `MailTool.doFork`（L266-363）：createSession(`fork-<uuid8>`) → spawn AgentActor（parentRef=ctx.agentActorRef、rootSessionId=callerRootSessionId、forkContext=true）→ UserInput → waitForForkAnswer。**全程没有一行 `agentRegistry.update`**——对照其余五个注册点（Delegate L451 / SubTask L317 / Team activateAgent L1083 / Flow FlowDagExecutor L1193 / Ephemeral EphemeralAgentRunner L93），fork 是唯一不注册的 spawn 路径。连锁后果：

- AgentControl `doList` 遍历 `registry.values` → fork 行不存在（连 kind 列都没有——今日 Manager list 看不到 fork 的直接原因）；
- TaskStuckWatcher `scan` 以 registry 为数据源（L115）→ fork 卡死/循环零检测；
- WS `cancelAgent`（WebSocketRoutes L1061 `registry.get(cSessionId)`）→ None → 回 `ok=false "No live agent"`——**今日 18:54:38 与 19:01:19 两次面板取消均走此死路**；
- fork 60s 转后台后 `waitForForkAnswer` 以 detached fiber 等待（L395-407），无人等它结束、无超时——循环可无限烧。

今日事故时间线（nebflow.log 实证）：18:37:04 fork/html-builder spawn（parent=Manager aa15dbcc）→ 18:38:28 首次 `Read(measure-slots.mjs)` 失败「File does not exist」→ 之后 **~13-15 次/分钟 × 20 分钟（280+ 次同参同败）**，每轮重发 ~200k cacheRead 上下文 → 18:54:38 cancelAgent 失败 → 19:01:19 cancelAgent 再失败 → 18:58 后循环自灭、19:04:50 turn-complete（msgs=958，近 30 分钟、纯烧 token 零产出）。

### A3 Manager 无武器的双层根因（v2 ⑪B 落差点定位）

v2 冻结式错误恢复（e61d664）§5.3.1/§6.1-7 设计了「直接父可管 Team 成员」并声称落地，实际**工具层确实落地、外围两处没接**：

| 层 | 断点 | 源码 |
|---|---|---|
| 授权层 | `NebulaExclusiveTools` 含 "AgentControl"；`buildAllowedToolSet` 对一切 `name != "Nebula"` 的 agent 剥除（显式声明也无效）——Manager 即使在 agent.json 写了也拿不到 | AgentCore L1775-1780、L1345 |
| 身份层 | Team 成员注册不写 `parentSessionId`（activateAgent L1083-1092 只有 sessionId/ref/kind/rootSessionId/parentRef）→ `callerIsDirectParent = rec.parentSessionId.nonEmpty && …` 恒 false → kindRejection 的 Team 放行分支永不触发 | AgentControlTool L205、MailTool L1083 |
| 旁证 | SubTask/Delegate 注册点都写 parentSessionId（SubTaskTool L331、DelegateTool L463）——只有 Team 漏 | 同左 |

另有两处相关事实：Ephemeral 注册 rootSessionId=self、无 parentSessionId（EphemeralAgentRunner L93-99，同桶判定先天不稳）；Manager 授权有现成机制先例——`TeamTaskTools` 的 teamTaskGrant（AgentCore L1353-1357：先剥后授、isTeamLead 为唯一来源、agent.json 声明不算数），Manager AgentControl 照此办理即可（§C2）。

### A4 循环检测盲区（为什么现有机制全部漏过今日事故）

| 机制 | 为什么没拦住 |
|---|---|
| TaskStuckWatcher 10min 无活动 | fork 未注册（盲区①）；且即便注册，每次工具失败都 touch lastActivityMs（AgentCore L976）——「每 4 秒活动一次」永不满足无活动判据（盲区②，检测哲学缺轴） |
| guardSaveTurn 同文件 hash ×3 | 只在 Save→Compact 阶段生效；fork 的 ForkSideEffectTools 剥掉 SaveTurn（#30），连 save 阶段都不存在 |
| MaxTurnLlmCalls=4 | 数的是 LLM 调用预算内的 overload 重试，正常 turn 的 LLM 调用不受此限（每轮都是新调用），280 轮 = 280 次合法新调用 |
| 劝停 #12 permissionDenials | 管用户拒绝，不管工具自身报错 |
| watcher 提示语自相矛盾 | Team 卡死时广播「user/Nebula can restart via AgentControl」（TaskStuckWatcher L143）但 AgentControl 对 Team 只读——let-it-crash 文档 §1 早已记录此矛盾，至今未修 |

## B 会话注册与可见性统一模型

### B1 统一原则：凡 spawn 必注册，sessionId 是唯一身份

现有六个 spawn 路径五个已注册。~~新增 `AgentKind.Fork`（protocol.scala L248 enum 加 case）~~ ⛔v1.1 用户裁定砍除——fork 随 ask 移除（#8）已不存在，注册链补全只剩 Team 成员（activateAgent L748）与 Ephemeral（L93）两处 + Manager 挂载注册（FlowTreeActor L71，v1.1 补列）。父子两条链各司其职（沿用 v2 §5.1 语义）：

- **parentSessionId 链（管理链）**：谁创建谁负责——Manager→成员→成员的 SubTask/fork/Delegate 逐级回溯，子树判定（§B3）与升级链都走它；
- **rootSessionId 链（权限桶）**：同 Nebula 窗口 = 同桶，Nebula 全局权的边界。

~~不复用 Ephemeral：fork 有独立生命周期语义……逐个补齐即是安全网。~~ ⛔v1.1 整段随 Fork kind 作废。

### B2 注册点补全清单

| spawn 路径 | 改动 | 位置 |
|---|---|---|
| ~~Mail fork~~ | ⛔v1.1 随 #8 doFork 删除整体作废 | — |
| Team 成员（Mail 激活） | 注册时补 `parentSessionId = TeamSessionRegistry.managerMap[instance]`（成员的父=Manager，v2 §5.1 语义；查不到 fallback = 激活调用者 ctx.sessionId） | MailTool.activateAgent **post-#8 L748** |
| **Team Manager（挂载）**（v1.1 补列） | FlowTreeActor 挂载注册同样无 parentSessionId（TeamSessionRegistry.registerActor L62-71）——补 `parentSessionId = rootSid`（Manager 的父=挂载它的 root 会话；使「Manager 自身被谁管」链完整） | core/flow/FlowTreeActor.scala L71 |
| Ephemeral（SubTask self-clone） | 注册补 `parentSessionId = 调用者 sid`、`rootSessionId = 调用者 root`（现为 self，同桶判定先天不稳）——需把调用者上下文贯通进 EphemeralAgentRunner | EphemeralAgentRunner L93 |
| SubTask / Delegate / Flow / Team(FlowTreeActor) | 已有 parentSessionId（SubTask post-#8 L182 / Delegate L340、L357）；Flow 保持（生命周期归 DAG，不可单杀——本设计不动） | 零改动 |

注册即全量可见：AgentControl doList、TaskStuckWatcher scan、WS cancelAgent、getActiveAgents 四处自动获得补全的会话——不需要逐个改查询逻辑，改的是数据源完备性。（~~+Fork~~ ⛔v1.1）

### B3 可管理性判定（纯函数，可单测）

```
manageableBy(callerSid, registry, teamRegistry):
  caller 是 root 桶（registry[caller].rootSessionId == caller，即 Nebula）
    → 全集 − {kind ∈ Root, Plan} − {caller 自身}
  caller 是 Manager（TeamSessionRegistry.isManager(callerSid) == true，instance=T）
    → {caller} ∪ {rec : rec.kind==Team ∧ teamOfSession(rec.sid)==T}      // 本队成员
      ∪ {rec : parentSessionId 链（深度≤8，防环）回溯到 caller 或本队任一成员}  // 子孙（SubTask/fork/Delegate/Ephemeral）
  其他 → ∅（无 AgentControl，见 §C）
```

关键修正：现行 `withGuardedRecord` 对非 root 调用者只查同桶（rootSessionId 相等）——一旦 Manager 获得工具，同桶判定会放行它管**别的 team** 的 SubTask。§C 的守卫必须把非 root 调用者的判定从「同桶」收紧为「子树」（§B3）；root 调用者维持同桶=全局。今日无非 root 调用者，收紧无回归。

### B4 可见面（list/status 各自看什么）

| 调用者 | list/status 范围 | 说明 |
|---|---|---|
| Nebula | 全部注册会话（含其他 team、所有 fork） | 全局监管面，现行为，fork 注册后自动扩充 |
| Manager | 仅 §B3 子树 | 新增 scope 过滤参数（调用者身份 → 过滤谓词），表头加 `parent` 列辅助辨认层级 |
| 成员 | 无工具（授权层不给） | ForkSideEffectTools 已剥 AgentControl，fork 也无——零改动 |

## C 权限矩阵：Nebula / Manager / 成员 × 四操作

### C1 矩阵总表

| 调用者 \ 目标 | Delegate | SubTask | Ephemeral | **Team 成员（非 Manager）** | **Team Manager** | Flow | Root/Plan | 自身 |
|---|---|---|---|---|---|---|---|---|
| **Nebula**（root 桶） | cancel+restart（现状） | cancel+restart（现状） | cancel（现状） | **cancel+restart**（新；restart 走 doRestart Team 分支断点重建） | **cancel+restart**（新；需 confirm，见 C3） | 只读→cancelFlow（现状不动） | 禁（自杀守卫，现状） | 禁（现状） |
| **Manager**（本队） | 子树内 cancel+restart | 子树内 cancel+restart | 子树内 cancel | **本队成员 cancel+restart**（callerIsDirectParent 修复后天然放行） | 禁（他人 team 的 Manager 不归我管；自己的 Manager 身份不在子树内） | 只读→cancelFlow | 禁 | 禁 |
| **成员** | — 无 AgentControl（授权层不给） — | | | | | | | |

（~~Fork 列~~ ⛔v1.1 随 fork 机制作废；原「Fork restart 拒绝并提示 re-ask」条目随之作废。）

### C2 Manager 授权机制（照 TeamTaskTools 先例，机制层授予）

```
buildAllowedToolSet：nebulaFiltered 之后追加
  controlGrant = if (isNebula || isTeamLead) Set("AgentControl") else Set.empty
  withControl  = (nebulaFiltered -- Set("AgentControl")) ++ controlGrant
```

- isTeamLead 判定复用 `isTeamLeadStatus`（AgentCore L280-283 → TeamSessionRegistry.isManager）；agent.json 声明不算数（工具名即权限边界，TeamTaskTools 同款理由）；
- NebulaExclusiveTools 集合本身不动（保留语义「非 Nebula 声明无效」），lead 走先剥后授；
- ~~forkContext 剥离（ForkSideEffectTools 含 AgentControl）不动——fork 无管理权~~ ⛔v1.1 forkContext/ForkSideEffectTools 已随 #8 整体退役，此条作废；
- 副作用：Manager 的系统提示需同步说明工具范围（「仅限本队子树，跨队/根会话会被拒绝」），避免无谓调用。

### C3 安全条目（Nebula 全局权的边界）

1. **禁杀 Root / 禁自杀**：现状守卫保留（kindRejection Root/Plan 分支 + withGuardedRecord self-guard）。
2. **杀 Team Manager（Team ∧ isManager）需强确认**：inputSchema 加 `confirm: boolean`——目标 isManager 且 action=cancel/restart 时，`confirm != true` 返回错误并说明后果（「Manager 被杀后其成员会话继续存在但失去协调者，成员仍可由 Nebula 管理」）；`confirm = true` 必须**同时**携带非空 `reason`。
3. **杀普通 Team 成员**：无需 confirm（其协调者 Manager 还在，恢复语义完整），reason 可选。
4. **审计留痕**：每次 Team/Fork 的 cancel/restart 写一条结构化审计（logger `nebflow.audit`，随 nebflow.log 落盘）：
   `AUDIT ts caller=<sid> callerAgent=<name> action=cancel|restart target=<sid> targetKind=Team|Fork targetAgent=<name> confirm=true|false reason="<text>"`
   同时写入目标 task.lastError（现有 reason 通道）与父通知 payload——事后可归因「谁在何时为什么杀的」。
5. **Flow 不动**：DAG 生命周期归 cancelFlow（kindRejection 现有指引保留）——f5bc6059 已给 flow 节点 supervisor 语义，单杀节点会破坏状态机，维持现状。
6. **WS 面板（用户路径）**：用户是最高权限，杀 Manager 无需 confirm 但写同款审计；面板前端对 Team 的灰禁（managePanel OPERABLE_KINDS）是否解禁属前端工作，P2 再议（本设计只保后端通道畅通）。

## D 循环检测器：信号 · 挂载 · 处置 · 豁免

### D1 检测信号（三个，互补不重叠）

**指纹定义**：`fp = sha256(toolName + canonicalJson(args)).take(12)`；同败 = fp 相同 ∧ `result.isError` ∧ 错误签名相同（错误文本前 120 字符的 hash——「File does not exist: <同一路径>」恒定，「timeout after 30s」与「timeout after 31s」不同）。

| 信号 | 定义 | 抓什么 | 今日事故对照 |
|---|---|---|---|
| **S1 同参同败**（turn 内） | 同 fp 连续失败计数：该 fp 一旦成功或换参数即清零；≥N1(3) 触发 L0 警告，≥N2(8) 触发 L1 终止 turn | 幻觉路径重读、死命令重跑、错误方案反复重试 | 18:38:28 起 3 次内即命中（280 次实际发生） |
| **S2 高活动零进展**（turn 内） | turn 工具轮预算：轮数 > maxToolRoundsPerTurn(60) 触发 L1；70% 处 L0 提醒。轮预算不数「有进展轮」——轮内含真实文件变更（Edit/Write 非同 hash）或任务状态迁移则该轮不计 | S1 抓不住的**变参数循环**（每次改一点仍零产出）与超长漫游 | 今日 280 轮远超 60 |
| **S3 跨轮同调用** | 跨 turn 持久计数（AgentState 内）：同 fp 在 ≥K(3) 个不同 turn 都失败且无一次成功 → L2 冻结 | turn 被终止后换个 turn 又开始同款重试——会话级病理 | （今日是单 turn，S3 是防复发层） |

S1/S3 与既有 Write-only 保护（guardSaveTurn 同 (path,hash) ×3）的关系：后者管「成功但无变化的写」，S1 管「失败的重试」，S2 管「总量」——三轴正交。save 阶段 turn 豁免 S2（guardSaveTurn 自有更严的 10 轮预算，避免双重治理）。

### D2 挂载层取舍：AgentCore 工具环为主，watcher 只做观测

| 方案 | 优势 | 劣势 | 取舍 |
|---|---|---|---|
| A. AgentCore 工具环（pipeToolExecutions，guardBatch 之后、ToolsComplete 之前） | 单一 choke point 全 kind 覆盖（**fork 也是 AgentActor，天然在环内**）；turn 上下文完整可注入提醒；即时（当轮生效，不等扫描周期） | 跨轮状态需 AgentState 持有；无全局视角 | **采纳（检测 + L0/L1/L2 处置全在此）** |
| B. TaskStuckWatcher 扩展 | 处置阶梯现成（Stop→硬取消→giveUp）；跨 agent 一致 | 高活动循环必须由环喂数据（watcher 只看 lastActivityMs）；30-60s 延迟；与环双头判定易漂移 | 仅采纳**观测镜像**：环把 streak/rounds 计数随 touchRegistryActivity（L976 同一 choke point）写进 AgentRecord 新字段 `loopStreak/loopRounds`——AgentControl list 的 stuck? 列旁显示 `loop×N`，watcher 不做循环判定 |

L2 冻结直接复用 `enterFrozen(reason="loop")`（v2 冻结骨架：Frozen 态 watcher 天然豁免 F5、零 token 铁律、CheckFreezeGate 轮询、用户输入唤醒）——不新造状态。

### D3 处置阶梯（警告 → 终止 turn → 冻结待人工）

```
L0 警告（S1≥3 / S2≥70%）：向消息流注入 LoopReminder（同 saveTurnDriftReminder 模式）
    「工具 X 以相同参数失败 N 次且错误相同——改变方法或放弃该路径」；零成本给模型自纠机会
L1 终止 turn（S1≥8 / S2 超预算）：环内调 ctx.cancelCurrentTurn 等价路径 → turn 以 LoopDetected 失败
    → 走现有失败链：Delegate/SubTask → supervisor notifyParentAndStop(retryable=true) 父可重派；
    Team 成员 → ExternalEvent(failed) 通知 Manager；fork → waitForForkAnswer Left → cleanupFork + 通知提问者；
    计数器清零（turn 边界）
L2 冻结会话（S3≥3 turn / L1 后同 fp 立即复发）：enterFrozen("loop") + WS loopDetected 广播
    + 父 ExternalEvent("loop-detected")——等待人工/Manager/Nebula 决策
```

**恢复路径（全部复用现有机制）**：L2 冻结的会话可 ① 用户输入直接唤醒（R3，既有）；② Manager/Nebula `AgentControl restart`（SubTask/Delegate 走 supervisor 断点续跑、Team 走 activateAgent history 重建——§C1）；③ `cancel` 终态清偿。checkpoint 载体：lastDispatch + 已持久化消息（F1 边界）。

**Root 例外**：Nebula 自身循环 → 只 L0 + L2（冻结 + 广播给用户），**不 L1**——与 watcher「根 agent 只广播不自动处置」政策对齐（root 错误由用户裁决）。fork/成员的 turn 失败即任务失败，无自动重启（无 supervisor 的不造 supervisor——fork 转后台后无人等待，L1 失败完成 deferred 即闭环）。

**与「Team never auto-stopped」的划界（需用户裁定）**：L1 终止的是 **turn**（cancelCurrentTurn，actor 存活、会话保留），非 actor Stop——watcher 政策禁的是后者。语义上「永不自动停的 Team agent」其 turn 仍可因循环被终止，否则政策保护的是烧 token 的僵尸。§F-F5 以断言固化此边界。

### D4 误报防护（合法重试如何豁免）

| 合法模式 | 豁免机制 |
|---|---|
| 网络抖动重试（Curl/Bash 偶发失败后成功） | S1 是**连续**计数：成功即清零；2 次失败 1 次成功的 flaky 永远到不了 N1=3。若 flaky 连败 8 次同错——本就是病理，L1 正确 |
| flaky 错误文本含变化内容（耗时/时间戳） | 错误签名 hash 不同 → fp 同但签不同 → 不累计（签名设计即豁免） |
| 轮询类工具（TaskQuery/TaskList/TeamTaskList 反复查状态，结果相同） | `exemptTools` 配置白名单：按 toolName 整体跳过 S1/S3 计数（S2 轮预算仍适用——轮询也该有界） |
| 用户权限拒绝（连点 deny） | isError 且属 PermissionDecision.Deny 的结果不计入 S1（#12 劝停机制已独立治理） |
| 「等文件出现」型轮询（构建产物） | 结果从失败翻成功即清零；纯失败轮询 8 次仍触发 L1——阈值给足余地 + L0 先警告 |
| overload-only retry（LLM 层） | 引擎内部重试不是工具调用，不进环——08-18 token 防护轴完全正交，零触碰 |

### D5 阈值配置（nebflow.json，stuckThresholdMs 先例）

```
supervision: {
  loopGuard: {
    enabled: true,
    identicalFailureSoft: 3,     // N1 → L0
    identicalFailureHard: 8,     // N2 → L1
    maxToolRoundsPerTurn: 60,    // S2 → L1（save 阶段豁免）
    crossTurnFailureTurns: 3,    // K  → L2
    exemptTools: ["TaskQuery", "TaskList", "TeamTaskList"]
  }
}
```

Defaults 兜底 + GatewayMain 装配（llm/config.scala L175-176 模式）；测试可收紧到 2/3/5 加速用例。

### D6 ~~fork 覆盖闭环~~ ⛔v1.1 作废（fork 随 #8 移除）；检测器覆盖面 = 一切经 AgentCore 工具环的 AgentActor（Delegate/SubTask/Ephemeral/Team/Root 天然在环内）——今日事故同款（任一 kind 的幻觉路径重读）在 S1/S2 下全链路可达：第 3 次失败注入提醒（若模型自纠即软着陆）→ 第 8 次终止 turn → 走各 kind 既有失败链（Delegate/SubTask → supervisor notifyParentAndStop(retryable=true) 父可重派；Team 成员 → ExternalEvent(failed) 通知 Manager）。

## E 实施拆分：四块独立可合并 + 依赖顺序

**Block 0（共同前置）：注册链补全（Team 成员 + Manager + Ephemeral）** —— Block 1/2 都依赖它（父判定与子树判定需要 parentSessionId）。单独可合并（纯数据源完备性，无行为变更——注册不改变任何现有判定路径）。

| # | 改动 | 文件 |
|---|---|---|
| ~~0.1~~ | ⛔v1.1 AgentKind.Fork 随裁定作废 | — |
| ~~0.2~~ | ⛔v1.1 doFork 注册/cleanup 随 #8 删除作废 | — |
| 0.3 | Team 成员注册补 parentSessionId（managerMap 查询）；**Manager 挂载注册补 parentSessionId=rootSid（v1.1 补列）** | core/tools/MailTool.scala post-#8 L748；core/flow/FlowTreeActor.scala L71 |
| 0.4 | Ephemeral 注册补 parentSessionId/rootSessionId（调用者上下文贯通） | core/flow/EphemeralAgentRunner.scala L93 |
| 0.5 | ~~CancelableKinds += Fork；kindRejection Fork 分支；TaskStuckWatcher scannedKinds += Fork~~ ⛔v1.1 作废；改为 **TaskStuckWatcher L143 提示语更新为 turn 级语义**（裁定①：Team 成员 turn 可被自动终止、actor 永不自动停——消除「可 restart」与只读现实的矛盾表述） | core/processor/TaskStuckWatcher.scala L143 |
| 0.6 | 测试：Team/Ephemeral 注册链（parentSessionId/rootSessionId 正确写入）、Manager 挂载注册、Ephemeral 进调用者桶后 root 可 cancel | 新 RegistrationChainSpec / AgentControlToolSpec 扩展 |

**Block 1：Manager AgentControl**（依赖 0.3——父判定需要 parentSessionId）

| # | 改动 | 文件 |
|---|---|---|
| 1.1 | controlGrant 机制层授予（isNebula ∨ isTeamLead → AgentControl；先剥后授） | agent/AgentCore.scala L1345 后 |
| 1.2 | 非 root 调用者守卫从「同桶」收紧为「子树」（§B3 纯函数）；list/status 加 scope 过滤 | core/tools/AgentControlTool.scala L168-209、doList |
| 1.3 | 测试：Manager 管本队成员/成员 fork/SubTask 放行；跨队/根会话拒绝；成员声明 AgentControl 无效 | AgentControlToolSpec 扩展 |

**Block 2：Nebula 全局权**（依赖 0.1/0.5；与 Block 1 无相互依赖）

| # | 改动 | 文件 |
|---|---|---|
| 2.1 | kindRejection Team 分支放宽：callerIsDirectParent ∨ callerIsRoot → 放行（杀 Manager 需 confirm+reason） | core/tools/AgentControlTool.scala L100-108 |
| 2.2 | inputSchema 加 confirm；doCancel/doRestart 审计行（nebflow.audit）；Manager 目标强确认 | 同文件 + NebflowLogger |
| 2.3 | 测试：杀普通成员/杀 Manager 双 confirm 门/审计行存在/Root 自杀拒绝回归 | AgentControlToolSpec 扩展 |

**Block 3：循环检测器**（检测逻辑不依赖 0，但 fork 覆盖依赖 0.2；与 1/2 无依赖，可并行开发）

| # | 改动 | 文件 |
|---|---|---|
| 3.1 | LoopGuardState（AgentState.execution 内）+ 纯函数 evaluate（S1/S2/S3 判定，全部可单测） | agent/protocol.scala、新 core/processor/LoopGuard.scala（纯函数对象） |
| 3.2 | 环挂载：pipeToolExecutions 在 guardBatch 后调 evaluate → L0 注入提醒 / L1 终止 turn（LoopDetected 失败链）/ L2 enterFrozen("loop") | agent/AgentCore.scala L819-994 |
| 3.3 | save-turn 豁免标记（guardSaveTurn 路径跳过 S2） | agent/AgentActor.scala L3899 |
| 3.4 | AgentRecord 镜像字段 loopStreak/loopRounds + touchRegistryActivity 同步 + doList 显示 | agent/protocol.scala、AgentCore L976、AgentControlTool doList |
| 3.5 | 配置管线（Defaults → config → GatewayMain）+ WS loopDetected 事件 | shared/Defaults.scala、llm/config.scala、gateway/ |
| 3.6 | 测试：LoopGuardSpec 纯函数全覆盖 + 三信号 × 三级处置集成用例（短阈值） | 新 spec |

**推荐合并顺序**：0 → 1（今日最痛的「下令无武器」）→ 3（长期防护）→ 2（最小改动收尾）。1/2/3 在 0 之后任意顺序皆可合并，互不阻塞。

## F 验收标准（二值断言）

**F0 隔离纪律（全部 E2E 前提）**：所有用例跑在隔离实例（端口 ≠ 8080、NEBFLOW_HOME 指向临时目录），绝不触碰 8080 host。

**F1 Block 0 · 注册链补全 E2E**（⛔v1.1 fork 用例作废，改版）：
1. 隔离实例 `sbt run` 启动 → `curl /api/health` 200（冒烟硬性）；
2. team 挂载 → Manager 记录 parentSessionId=rootSid；成员经 Mail 激活 → 记录 parentSessionId=Manager.sid、rootSessionId=挂载 root；
3. 成员 spawn SubTask（Ephemeral self-clone）→ Ephemeral 记录 parentSessionId=成员 sid、rootSessionId=挂载 root（进桶）；
4. Nebula `AgentControl cancel` 该 Ephemeral → ok=true（0.4 前因 rootSessionId=self 同桶判定必拒——回归对照断言）。

**F2 Block 1 · Manager 管控 E2E**：
1. 双 team 实例（A/B），A 的 Manager `AgentControl list` 仅见本队子树（B 队成员不可见）；
2. A 成员的 SubTask 循环中，A Manager `cancel` 该 SubTask → ok=true，成员收到 cancelled 通知（⛔v1.1 fork 变体作废，以 SubTask 代）；
3. A Manager 对 B 队任意会话 cancel → Permission denied（子树守卫）；
4. A 普通成员 agent.json 显式声明 AgentControl → 工具仍不可用（机制层授予唯一来源）。

**F3 Block 2 · Nebula 全局权 E2E**：
1. Nebula cancel 循环中的普通 Team 成员 → ok=true + nebflow.audit 审计行存在（断言格式 §C3-4）；
2. Nebula cancel Manager：无 confirm → 拒绝并返回后果说明；confirm=true 无 reason → 拒绝；confirm=true + reason → ok=true + 审计行含 reason；
3. Nebula cancel/restart 自身与 Root → 拒绝（回归）；Flow 目标 → 指引 cancelFlow（回归）。

**F4 Block 3 · 今日事故复现（核心 E2E）**：测试 agent 定义带恒败工具（fixture tool 固定返回同错）→
1. 单 turn：第 3 次同败后消息流出现 LoopReminder（L0 断言）；第 8 次 turn 以 LoopDetected 终止，父收到 failed 通知（L1 断言）；测试阈值收紧为 3/5 加速；
2. ~~fork 变体~~ ⛔v1.1 作废（fork 已不存在）；Team 成员变体即用例 1 的目标本身；
3. S3：跨 3 个 turn 重复同败 → 会话进入 Frozen(loop) + WS loopDetected 广播 + 父通知；`AgentControl restart` 后从 checkpoint 续跑（Team 成员走 activateAgent 重建）；
4. Root 例外：根 agent 循环 → 无 L1 自动终止（只有 L0 + L2 冻结 + 广播）。

**F5 误报豁免与政策边界**：
1. flaky 工具「败-败-成」循环 10 轮 → 无警告无终止（连续计数清零语义）；
2. TaskQuery 轮询相同结果 20 次 → S1/S3 零计数（exemptTools）；
3. save-turn 同文件重复写 → 仅 guardSaveTurn 治理（S2 豁免，无双重终止）；
4. Team 成员循环被 L1 终止 turn 后 actor 存活、状态非 Stopped（「never auto-stop actor」政策字面成立）。

**F6 回归红线**：
1. 08-18 token 防护零触碰：overload-only retry / MaxTurnLlmCalls=4 / seam guard / 600s no-progress 用例全绿；
2. TaskStuckWatcher 既有用例（无活动卡死三阶梯）全绿；冻结时间表行为不变；
3. 现有 AgentControlToolSpec / guardSaveTurn 相关 spec 全绿；`sbt compile` + 全量 `sbt test` 通过。
