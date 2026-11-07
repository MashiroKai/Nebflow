---
type: design
status: draft-for-author-ruling
---

# stand-scala 最小可信基总体设计方案（路线图）

> **一句话**：作者 11-05 12:50 令「目前核心目标就是构建『最小』可信基。按照这个目标给出接下来的设计方案吧」——本件给出「最小」的可机械执行判据、五基清单定案（两已落 + 三待建）、压缩策略面两案对比、按依赖实测重排的构筑批次 DAG、上层引用边界与机械门方案、总验收清单、待拍板整合清单。零代码纯 docs；不推翻已落两基与已拍口径，只设计「接下来怎么走」。**11-05 补格轮增补（当晚）**：作者 12:58 六基点名中「云端链接器」原文全件零格——本轮补格＝§2.7 现状散面盘点（14 面 file:line 亲读锚点）＋三案落位＋Q-22…Q-29 语义边界问句；格数判定＝维持五基、第六格开放待作者拍板（作者信源仅五字，不立格即不臆测语义边界）；原五基定案、批次 DAG、Q-1…Q-21 编号零改动。

**立论基础（作者口径，逐字在档，不加码）**：19:54「所有仓库代码编写保持最小可信基构建原则。有限确保底层逻辑的正确性」「本次 db 与 pilogic 就是一次可信基的重新构筑」；20:06 可信两定义——①安全性可信＝通过我们设计的测试，②功能完备性可信＝可信基让上层构建不引入新的基础性代码、上层单方向引用下层；20:12 工具链标准全谱（测试+风格质量工具同属标准）；22:50 认可的分析框架——pilogic✓ db✓ llm-provider✗（半基）＋两实缺口（工具执行基、权限判定基）＋一半缺口（压缩策略面）。

**术语就地解释**（全文通用，首现即释）：**基（可信基）**＝被上层单方向引用、自身零反向依赖、以测试钉住全部行为的底层能力模块；**最小面**＝恰好消除「上层造基冲动」的最小公开签名集——多一则超编、少一则上层自造；**反证表**＝对基内每个公开面逐个点名「上层谁会引用它」的核对表，引用面为空者即超编候选；**超编**＝基内存在无上层消费者的签名（浪费维护面、引诱基内长业务逻辑）；**造基冲动**＝上层因基缺位而自己写持久化/循环/执行/判定原语的行为；**位点**＝pilogic HookPoint 分类学中的具名接入位置；**G-2**＝历史压缩面的位点预留族；**割接**＝pilogic AgentLoop 接管主链驱动腿的批次（轮 A 已影子织入、注册面空挂）；**R 编号**＝代码分布导航件的风险条目号；**病编号**＝llm-provider 审计件的病灶条目号。

**补充输入（作者 2026-11-05 13:11，triple-v2 侧拍板，经分发器逐字转达，与本案直接相关）**：「db在我的构想里是后端无关的连接器。可以使用pg作为首个实例。所以数据库显然都要容器化。另外，controller负责承载所有高阶逻辑。我期望controller的形态是可编程的pub-sub事件转发和操作器。比如actor类的启动链理论上就可以通过controller实现。同时controller也是系统整体状态的监控位点」——两处落点：①hook 引擎形态参照（§2.6）；②db 基后端无关连接器语义（Q-21）。注：该令在 v2 侧尚无地面真值件落盘（本轮实测 `triple-v2/docs/` 无今日文件），在档最近邻＝`D:/workspace@zcode/nebflow/开发区/triple-v2/docs/author-interaction-canonical-flow.md`（controller＝pub-sub 路由器＋事件拦截器、hook 切面 C1、J5 路由行）。

**输入件（均已亲读）**：pilogic 设计 v1 [[20261104_121214_scala-pi-logic-design-v1__delegate-nebflow-6513b1ad|pi-logic 模块设计方案 v1]]、实现轮二 [[20261104_143636_scala-pi-logic-impl-round2__delegate-nebflow-cadd6584|pi-logic 增量轮交付报告]]、接线轮 A [[20261104_215400_scala-pi-logic-wiring-a-round-a__delegate-nebflow-9db9d0d1|轮 A 收尾报告]]；db 设计 v1 [[20261104_121502_scala-db-module-design-v1__delegate-nebflow-006825e8|scala db 模块设计 v1]]、实现 [[20261104_135608_scala-db-module-impl-v1__delegate-nebflow-ebb64e95|db 模块实现 v1]]、一阶计划 [[20261104_152049_scala-db-wiring-plan-phase1__delegate-nebflow-678885ee|db 接线第一阶计划]]、二阶报告（`reports/20261104_da-2b-scala-db-wiring/REPORT.md`，vault 外路径引用）；llm 审计重筑 [[20261104_230447_scala-llm-provider-audit-redesign__delegate-nebflow-0bd60c6b|llm-provider 全貌审计与重筑设计]]；代码分布导航 [[20261103_212253_scala-core-agent-code-map__delegate-nebflow-617684c5|scala 核心代码分布导航]]（有证伪前科——本件凡引现网行为处均本轮亲读复核）。文档库纪律见 [[glossary]]，索引见 [[index]]。

## 0. 基线与实测口径

- 代码基线 = main `996b66377`（本 worktree 同位实测）；`src/main/scala/nebflow/` 下 pilogic 14 件、db 13 件已落。文档件基线：`docs/` 被 `.gitignore:30` 忽略，输入件多为盘上产物，读数以主工作区实测为准。
- 消费关系实测（本轮 grep，亲证）：pilogic 的主链消费者 = `actor/AgentState.scala`（RunLedger）、`agent/PiLoopWiring.scala`（轮 A 织入）、`core/project/FlowMapEventLog.scala`（KernelEvent）；db 的主链消费者 = PendingAskLedger、HistoryArchiver、DispatchNotify、FlowMapEventLog、ScheduledTaskStore、SessionStore、HealthMonitor、PiLoopWiring 共 8 文件。反向（pilogic→主链、db→主链）零命中。
- 关键锚点本轮亲读复核（行号为 `996b66377` 现值，非导航件旧值）：工具分发口 `AgentSessionExecution.scala:2110`（`ToolRegistry.TOOL_MAP.get`）；权限闸调用位 `:1471`、判定体 `:1715`（`permissionDecision`）；远端路由 `:2146-2167`（`RemoteExecutor.remoteableTools` 六件）；BashTool 全文零 `fileLockManager` 引用（rg 实测 0 命中）；锁缺省 `core/tools/types.scala:37` `Option = None`；档位阶梯 `core/permissions.scala:28/:34-35/:125-138/:180-195`；权限卡内存槽 `agent/InteractionHub.scala:81`（`Ref.unsafe`，db D-8 重装腿注释同文件在场）；压缩阈值 `shared/CompactThreshold.scala:9-30`（硬编码全局）；ws 闸门 `agent/PiLoopWiring.scala:43/:167`（翻译钉形状、闸门关）；窄口先例 `core/LlmRuntimePort.scala:9-23`；主链真轮帽仍 NOT FOUND（`maxTurns/MaxTurns` 主链零命中，RetryPolicy 闸 `:1105` 只数失败重试）；agent/gateway 直引 `nebflow.llm` 内部 = 10 文件（病-11 的现值，比审计记的 4 文件更宽）。
- 补格轮基线（11-05 晚，main `33cad0d99`，本 worktree 同位实测）：`996b66377..33cad0d99` 仅 docs 两提交（`e64b796f1`/`01de6e214`）＋合并件，src/ 零位移——上条既有锚点继续有效；「云端链接器」全仓零命中本轮亲测复核（scala/md/json 后缀 rg 含 docs/，零命中）；§2.7 散面表锚点全部为本轮 `33cad0d99` 现值亲读。

## 一、「最小」的判据句

**判据句（可机械执行的一把尺）**：

> 一个能力**必须进基**，当且仅当它同时满足：〔J1 复用〕两个及以上上层消费位反复需要它，且各自实现已经或必然漂移（有双实现漂移/多制并存的现网证据）；**或**〔J2 位点承接〕它落在 pilogic 位点表（含 G-1…G-5 预留族）已声明的执行域内——凡位点表已声明的执行域，对应基必须承接；**并且**〔J3 叶子可行〕它能以零反向依赖的叶子形态存在（不 import agent/gateway/上层任何包）。
> 一个能力**明确不进基**，若命中任何一条：〔J4 高阶状态机/业务策略〕阈值数值、档位取舍、路由选择、压缩策略等策略本体——基承执行与裁决机制，策略值永远由上层注入；〔J5 单一消费位〕只有一个上层用得上的能力留上层；〔J6 表示层/人机面〕帧序、渲染、卡片 UX 属接线层与前端；〔J7 可再生派生面〕索引、缓存等可从事实面重建的数据不占基的公开签名（内部可有）。
> **超编判定**：基内每个公开方法必须能点名至少一个非测试上层引用者、或一条已排期的解锁批次（如割接轮），否则标注〔可裁〕；无人认领又无批次解锁的方法＝超编，防贪全。**防漏判定**：上层新代码出现「自造持久化 / 自造循环与轮帽 / 自造工具执行口 / 自造权限判定口 / 自造事件 envelope」任一信号，即判基有漏（grep 门承载，见§五），防漏。

判据句的操作性：J1/J2 给「进」的两条正路（漂移证据或位点表承接——本仓现网证据分别是病-4 双状态机、db 设计的多制并存审计、pilogic 位点表 78 条）；J4–J7 给「不进」的四条硬线；超编反证表与自造信号 grep 门是两端的机械检查。作者 20:06 完备性定义（「上层不引入新的基础性代码」）被翻译为可判红的五类自造信号；安全性定义（「通过我们设计的测试」）被翻译为§六的总验收。

## 二、基清单定案

**总形**：五基两层。底层两基已落——pilogic（循环与位点，零依赖叶子）、db（事实与持久化，零 nebflow 依赖）；待建三基落其上——llm-provider 基（一次调用的全部故障语义）、权限判定基（一次工具调用的裁决）、工具执行基（一次工具调用的执行管线）；基间允许的依赖仅「向下到 db」与「工具基→权限基」，pilogic 永远零依赖（现状实测维持）。

**口径同步（11-05 补格轮）**：作者 12:58 六基点名的第三件「云端链接器」经§2.7 散面盘点与三案落位，本轮判定＝**维持五基两层，第六格开放**——语义边界未展开（作者信源仅「云端链接器」五字），立格即代作者臆测；其已定部位（远端工具腿，§2.7 丙2）不因补格变动，仍归位工具执行基/权限基既定批次（批 4/批 5）。第六格立否归 Q-22（语义边界）→Q-23（格数）链式拍板；若立，前置依赖倒置手术批（§2.7 案①反证），本路线图批次 DAG 不重排、连接基批以增补轮插入。

### 2.1 pilogic（已落，最小面确认）

已落能力面：AgentLoop（run/continueFrom）、EventSink + LoopEvent 十二支、ProviderPort、ToolExecutor/ToolSpec/ToolSet、LoopPolicy/RetryPolicy/CostMeter、HookPoint 29 采纳位点 + 14 预留壳、HookRegistry（快照语义）、Sequence（TurnNumber/MessageSeq）、KernelEvent envelope（冻结）、RunLedger、SiteEvents（29 位点强类型）、SessionStages。轮 A 已影子织入十点，测试 80+18 绿，叶子门/数字帽门/全局态门三机械门代码级绿。

反证表（「上层谁会引用它」，非测试消费者实测）：

| 面 | 上层引用者 | 判定 |
|---|---|---|
| AgentLoop.run/continueFrom | PiLoopWiring 空挂适配，割接轮转正为唯一驱动 | 割接解锁，非超编 |
| EventSink.emit/审计行/影子写 | PiEventBridge（轮 A 已产审计行+KernelEvent 影子写） | 已引用 |
| EventSink.toChannel / collecting | 零生产消费者（fs2 桥留接线期；collecting 测试用） | 〔冻结〕 |
| synthesizeTruncatedEvents | crashTail 三处织入（Llm 腿/context 超时/工具管线） | 已引用 |
| ProviderPort.stream | PiProviderPort 空挂，割接轮对位 llm.base | 割接解锁 |
| ToolExecutor/ToolSet/ToolSpec | PiToolExecutor 空挂，割接后＝工具执行基的实现位 | 割接解锁 |
| RetryPolicy.allows | 主链重试闸 `AgentSessionExecution.scala:1105`（轮 A 点3） | 已引用 |
| LoopPolicy/CostMeter | 割接轮（轮帽）+ llm 账本（病-3）解锁 | 批次解锁 |
| HookRegistry 四法 | 装配层（割接轮）；L-4 已裁「仅装配层」运行期增删 | 装配解锁；〔冻结·禁插件运行期〕 |
| HookPoint 29 + Reserved 14 | SiteEvents wire 对拍（29）+ G-1…G-5 格子（14） | 已引用；Reserved＝格子非功能 |
| TurnNumber/MessageSeq | 主链取号 `:587`（轮 A 点1）、stale 判定类型化 | 已引用 |
| KernelEvent | FlowMapEventLog C5 影子写；db 存储包内核 | 已引用（冻结） |
| RunLedger | AgentState.pilogicLedger 值传递（轮 A 件③） | 已引用 |
| SessionStages | PiLoopWiring 会话级触发单点 | 已引用 |

**结论**：pilogic 最小面成立，无需增删；两处〔冻结〕（toChannel、HookRegistry 运行期增删面）不是裁——是「已够用、不再扩」的封条。G-2 预留位的激活与否见§三。

### 2.2 db（已落，最小面确认）

已落能力面：DbWriteFace / DbReadFace / DbRecovery 三 trait（`db/DbCore.scala:145-209`）+ DbHost 共享句柄 + RetentionSweeper 内嵌调度；V1–V10 全覆盖、真 kill-9 chaos 三场景绿；六个宿主已接线（C2–C8 + B2），九静默面已收编三态。反证表：

| 面组 | 上层引用者（非测试，实测） | 判定 |
|---|---|---|
| appendEvent | FlowMapEventLog（C5 并行写）、PiLoopWiring 影子写 | 已引用 |
| appendMessage | SessionStore UI 面（C8）；轮 C 扩 LLM 面 | 已引用/批次扩展 |
| appendCompactPoint + writeSnapshot + readSnapshot | HistoryArchiver（C7，含读回校验） | 已引用 |
| openPending/closePending/readPending | PendingAskLedger（C3，boot 对账） | 已引用 |
| appendScheduleTask/tombstoneScheduleTask/liveScheduleTasks/readSchedule | ScheduledTaskStore（C6 写穿+恢复读） | 已引用 |
| writeNotifyGuard/readNotifyGuard/recoverNotifyGuardConservative | DispatchNotify（C2 窗口态写穿+重水合） | 已引用 |
| writeAvoidGuard/readAvoidGuard | HealthMonitor（B2 写穿+重水合+保守向） | 已引用 |
| readEvents/readSession | FlowMapEventLog 读回路 / SessionStore 影子恢复读（C8） | 已引用 |
| flush | DbHost close 链 | 已引用 |
| recoverPendingD8/orphanOpenAsks | boot 重装腿（C3；InteractionHub 重装槽在场） | 已引用 |
| rebuildEventIndex | 派生面重建（启动/恢复腿内部） | 内聚使用 |
| sessionIndex/eventIndex/verify | 诊断与验收面，零生产调用 | 〔可裁·诊断面〕建议冻结保留 |
| quarantineEventShard/quarantinePending/quarantineGuardDoc | 恢复腿与九静默面收编（C4 走 Quarantine 纯文件助手，不经 handle） | 部分内聚；〔冻结〕 |

**结论**：db 最小面成立；〔可裁·诊断面〕三项建议冻结不裁——诊断/取证本身是可信基的验收面（§六 W 项消费它们），裁了总验收少一条腿。db 设计 D-1…D-12 中仅 D-11（完整双读切换）仍在途＝轮 C 承载，其余已按推荐落定。

### 2.3 llm-provider 基（待建一：重筑）

最小 trait 面照审计件 §5.1 四口采纳（能力级，不加新口）：

| 口 | 签名能力 | 上层引用者（反证） |
|---|---|---|
| LlmClient（complete/stream 合一，CallContext 携 signal/deadline/tokenBudget） | 一次调用的唯一入口，非流＝流聚合的语法糖 | 流腿＝主链会话管线；非流腿＝分配器/压缩/小判官/搜索（病-1 的受害面就是它们） |
| FaultClassifier.classify | 故障语义单点（21 行矩阵的可执行形态） | 流/非流两消费点（根治病-1/病-4 的结构位） |
| HealthLedger（markDown/markUp/softAvoid/filter） | 健康账画面，轮 B 语义原样 | interface/fallback/HealthMonitor 现消费面收编 |
| GuardStore（persist/recover） | db 写穿窄口 | HealthMonitor B2 写穿/重水合（现语义平移） |

反证结论：四口全有既有消费者——现网直引 llm 内部的 agent 7 文件 + gateway 3 文件（本轮实测 10 文件）就是待收口的引用面本身（病-11 证据即反证表）。错误类型全部下沉 `nebflow.shared`（LlmRuntimePort 窄口先例推广）。**不进口的**：链构建四级顺序、储备层、时段闸（策略配置面，J4/J5 判留上层配置）；adapter 构造与 sttp 细节（实现内部）。

### 2.4 工具执行基（待建二）

最小 trait 面（新建 `nebflow.toolbase`，纸面形状）：

| 口 | 签名能力 | 上层引用者（反证） |
|---|---|---|
| ToolRuntime.dispatch(call, ctx) | 唯一执行口：闸内（权限判定）→统一超时→写串行→执行→结果帽→结构化留痕，一条管线 | 主链 executeToolInner（`:2110` 唯一分发口收编）、MCP 调用腿、远端执行腿（R6 修后同口）、割接后 pilogic ToolExecutor 的实现位 |
| 写串行原语（FileLockManager 必选化） | per-path 互斥从 `Option=None` 缺省升为管线内建 | Write/Edit 现消费者；Bash 接入（R9 闭合——本轮 rg 复核 BashTool 零锁仍在） |
| 统一 per-tool 超时 wrapper | 引擎侧统一超时面（#144 §2.4 实测 NOT FOUND 的补位） | 全部工具（现各自为政：Bash 自带帽/后台无硬超时/BlockingSubagent 15 分） |
| 结果帽（ToolResultGuard 收编） | 大结果落盘与批预算 | 现挂载点 `:1501-1505` |
| 取消注册面 | 远端/BgTask/AgentControl 取消族收编为单一注册语义 | RemoteExecCancelRegistry/BgTaskCancel 现消费者 |

**不进基**：工具注册面（`ToolRegistry.TOOL_MAP` 已是稳定面，保持）；各工具业务语义；提示词与描述文案；MCP 传输细节。J2 依据：pilogic 位点表的 tool.* 族（Pre/Post/BatchSettled/Execution 三观察）声明了执行域，执行域的原语必须进基；J1 依据：R9 零锁、超时 NOT FOUND、取消面三套并存＝漂移现证。

### 2.5 权限判定基（待建三）

最小 trait 面（新建 `nebflow.perm`，纸面形状）：

| 口 | 签名能力 | 上层引用者（反证） |
|---|---|---|
| PermissionOracle.judge(req) → Allow/Confirm(card)/Deny | 一次工具调用的裁决唯一口；req 携 tool/input/档位/会话与来源（本地/远端/MCP） | 主链权限闸（`:1471` 调用位、`:1715` 判定体收编）、MCP 权限面（`:1885+`）、远端腿（R6 裁定后同判）、工具基 dispatch 内闸 |
| 阶梯判定逻辑（SafetyMode × isReversible × Bash/Curl 危险检查） | `permissions.scala:180-195` 现逻辑收编为基内单点 | 现消费位即上列三面——三面今天各自接线正是漂移面 |

**不进基**：卡流与渲染（InteractionHub 的 UI 面，J6）；档位值与配置（SafetyMode 是策略输入不是基逻辑，J4——oracle 读它、不定义它）；权限单持久化（已在 db，C3/D-8 重装腿在场，本轮亲读 InteractionHub 注释）；pilogic Tool.Pre 改参重校验（L-5 已在 pilogic 位点级钉死，与 oracle 是「改参后复裁」的邻位互补，不重复建设）。J1 依据：R6 对端绕闸、R8 名单外工具永不出卡、MCP 面同构不合并——三面三种接法＝漂移现证。

### 2.6 hook 引擎形态裁定建议（新增输入：归一/分层两案，不代裁）

背景：作者 12:58 给 scala 的 hook 引擎构思（pub-sub 事件中转站）与 13:11 v2 controller 形态令（可编程的 pub-sub 事件转发和操作器＋高阶逻辑承载＋系统整体状态监控位点）是同一思想。v2 侧在档最近邻：`D:/workspace@zcode/nebflow/开发区/triple-v2/docs/author-interaction-canonical-flow.md`（controller＝「pub-sub 路由器＋事件拦截器」，C1「hook＝controller 对 pub-sub 的事件拦截」，J5 行）与 `D:/workspace@zcode/nebflow/开发区/triple-v2/docs/status.md` J5。

| 维度 | 甲案：归一（hook 引擎＝pilogic hook 面的对外化） | 乙案：分层（pilogic 位点不动＋新增 hook 引擎层，形参照 v2 controller） |
|---|---|---|
| 形状 | HookRegistry/dispatch 已是进程内单点分派——把它对装配层暴露为可编程注册面即得「可编程」；观察面＝EventSink；跨模块事件＝KernelEvent→db events/。零新层 | 新立引擎层：可编程 pub-sub 转发＋操作器（可编程挂接改变流经事件的行为）＋监控位点；主链/pilogic/db/llm 事件统一流经 |
| 得 | 零新增基面；位点语义与 L-4 信任边界不动；割接后 Interceptor 族即「操作器」的进程内形态 | 系统级监控位点单点化；与 v2 思想同构（两线互证）；跨进程/多服务扩展时形态已备 |
| 失 | 「监控位点」「转发」语义分散在三处（EventSink/KernelEvent/db events/），无单一可编程中转站的观感 | 新一层＝新基面，按判据句 J1 需两消费位＋漂移实证——scala 线单进程内现状观察面已覆盖、漂移证据不足；与 db events/ 事实流有职责重叠风险；L-4 信任边界（谁可运行期编程）未开，先行开闸违轮 A 保守档 |
| 判据落位 | J5/J7 无违；新增面＝零 | J1 不成立（暂无漂移实证）；与 J4 相邻（「高阶逻辑承载」恰是判据句永不进基的那一类——v2 由 controller 承载高阶逻辑＝该线把高阶逻辑从引擎挪进 controller，scala 线对应物是主链接线层，不是又一层基） |

**推荐甲案起步＋乙案挂账**：把「hook 引擎」诉求拆为三件事逐件对位——事件转发（db events/＋KernelEvent，已落）、可编程操作器（pilogic Interceptor 族，割接轮后可用）、监控位点（EventSink＋db 事件流读面，已落）。三件事在 scala 线均有已落或已排期载体，分层案在出现「跨进程/多服务」或「运行期可编程挂接」的实证需求（J1）前不立项；届时参照 v2 controller 形态立批。裁定归作者（Q-20）。两线不要求同构，作者思想一致性已在此对位中体现。

### 2.7 云端链接器补格（11-05 补格轮：现状散面盘点＋三案落位＋问句，不代裁）

**信源与缺口**：作者 11-05 12:58 逐字「还需要的可信基有工具调用引擎，云端链接器，hook引擎。这些都是非常基础的功能」——点名三件中，工具调用引擎已对位工具执行基（§2.4，与§2.5 权限基成对）、hook 引擎已对位§2.6/Q-20；**「云端链接器」五字之外零展开**，本路线图原文全件零格（分发器 Grep 实测零命中；本轮全仓 rg 复核 scala/md/json 后缀仍零命中）。纪律：不代作者臆测语义边界——本节主产出＝现状散面盘点＋三案落位＋语义边界问句（Q-22…Q-29），不是设计；一切方案均〔提案〕级。

**现状散面盘点（本轮亲读，锚点=`33cad0d99` 现值）**——scala 线一切「对上/对外连接」既有实现面，按语义分三组：

| 组/面 | 实现在哪（锚点） | 现在谁在用 | 与基的关系（判读） |
|---|---|---|---|
| 甲1 云客户端本体（登录态/心跳/端点发现/对端名册/更新挂账） | `neblink/NeblinkClient.scala:137-141`（类注释：device login, periodic heartbeat, peer discovery）；heartbeat `:503-538`（POST /api/device/heartbeat，携 endpoints/build/channel/uiPort/update.target） | 唯一构造点 `gateway/GatewayMain.scala:1247-1293`；FriendService/RemoteExecutor/AttachUpload 经它发请求 | 独立面，五基无一承接 |
| 甲2 身份登录族（浏览器 PKCE/静默重登/无头密码，汇合 device register） | `neblink/LogtoTransport.scala:10-24`（两条登录路径汇合于 /api/device/register；族件 LogtoAuthCode/PkceLoginSession/LogtoSilentRelogin/LogtoPasswordFlow）；`GatewayMain.scala:1254-1276`（401 自愈钩装配） | NeblinkRoutes auth 族路由、NeblinkClient 凭据腿自愈 | 独立面（会话自愈原语） |
| 甲3 中继隧道（跨网 relay 承载 WS） | `neblink/NeblinkRelayTunnel.scala:21-49`（server↔device B，relay_request/relay_response/ping-pong，always-on、指数退避重连）；relayExec `NeblinkClient.scala:1330-1358`（P2P 不通时的远端执行兜底） | relayExec/relayTransfer/relayNotify 族、门户腿（甲4） | 工具执行基远端腿（R6 位）的承载通道之一 |
| 甲4 门户隧道（网页端经云门户达本机控制台）＋一次性票据 | `neblink/PortalTunnelBridge.scala:25-55`（downlink http_request/ws_open/ConsoleTicketMint/BindingControl → PortalWireCodec 唯一编码点；HTTP 面直入本机 Router 切片）；`neblink/ConsoleTicketStore.scala:8-31`（账号绑定一次性控制台票据，redeemed-then-destroyed） | 云门户网页端→本机控制台（含 gzip 静态树） | 表示层邻近（J6 边界，Q-29 同族） |
| 甲5 入网编排（踢停摆/自动重注册/45s 同步拍） | `neblink/NeblinkService.scala:73-94`（停摆位=三消费者共读同一真值：隧道/自动登录/静默重登）；`:451-452`（runSyncCycle=discovery/heartbeat）；NeblinkDiscovery/NeblinkEnrollment/EnrollGuard | `GatewayMain.scala:1299-1324`（setRelayClient/setPresenceService/RemoteExecutor.initialize:1304/发现循环权威持有者） | 独立面（发现/编排原语） |
| 乙1 设备在线心跳（presence 拨号侧＋受话面） | `neblink/NeblinkPresenceService.scala:192-203`（5s ping/10s 判死、一条连接一条心跳线程同生同灭）；受话面 `gateway/PresenceRoutes.scala:491`（GET /neblink/presence）＋PresenceSessionRegistry（单会话顶替） | 前端面板在线点；AgentSessionSync/IdentitySync 的 sendData 数据通道 | 独立面（判活原语） |
| 乙2 配对与验签＋fleet 开关 | `core/device/DeviceRegistry.scala:25-26,45`（devices.json 现读盘；分层门禁注释：core 消费方 DeviceDelegate/RemoteExecutor 不得 import neblink）；DeviceHmac；FleetConfig.enforceExplicitDevice（引擎侧消费位 `AgentSessionExecution.scala:2161`） | AgentSessionSync/AgentIdentitySync、DeviceDelegate、RemoteExecutor P2P 签头（`:1377-1395`）、FleetRoster | 权限相邻（allowSpawn 设备白名单）；**自造持久化**（db 基 G4 门存量观察项，Q-26） |
| 乙3 跨设备文件/文本流（dropbox 域） | `dropbox/DropboxService.scala:57-67`（WS 信令 channel="dropbox"＋HTTP 字节；messages.json 落盘）；`ChunkTransport.scala:9-15`（传输层缝：P2P 主腿/relay 兜底/测试自环共契约，failover offset 不变）；`neblink/FileTransferAction.scala:9-43`（get/put/list/probe；browse 白名单出口） | SendMessage 设备腿（字节底座）、A1 读图回拉、远端文件浏览器 | 通道缝已抽象；messages.json=自造持久化（G4 存量观察项，Q-26） |
| 乙4 附件上传（E1＋E2×n 分块） | `neblink/AttachUpload.scala:7-24`（唯一分块驱动：桌面腿与网页腿同源）；AttachmentCapability（fail-closed 能力探测）/AttachmentAck（E4 回执闸）/AttachUploadRegistry（取消位） | FriendService.sendAsAgent（桌面腿）、SocialRoutes 网页腿 | 独立面（分块续传原语） |
| 乙5 跨设备状态投影 | `neblink/AgentSessionSync.scala:11-32`（后台子智能体会话投影，作者令 #929「多设备设计的核心就是透明」；presence sendData＋relay 双腿、LWW 收敛）；`AgentIdentitySync.scala:13-29`（身份收敛同族同构） | SubAgentTaskStore 写钩外推→对端投影仓（RemoteAgentSessions） | 独立面；**上行依赖实证**（AgentSessionSync:7 import nebflow.agent.SubAgentTaskStore） |
| 丙1 消息面（Mail/SendMessage/DeviceMail） | 注册口 `core/tools/registry.scala:59,100,109`（Mail=统一消息原语三地址腿；SendMessage=A2A 好友/设备/群——`FriendMessageTool.scala:39-45` 职责边界：字节一律 DropboxService.sendLocalFiles；ListFriends 只读名册）；`core/mail/SessionMailbox.scala:13-30`（收件方持久邮箱：磁盘唯一真源、actor 消息只是门铃）；`neblink/DeviceMailInbox.scala:15-38`（跨设备收件腿＋事件号去重闸；账本进程内不落盘=作者待裁项 F2）；`NeblinkClient.scala:1470-1489`（mailUrl 唯一构造点/relayAgentMail） | agent 会话工具面；装配 `GatewayMain.scala:1420`（FriendMessageTool.initialize）、DropboxService.create `:1498` | 工具执行基只接执行口；消息语义（原语/邮箱/去重）无基承接 |
| 丙2 远端工具腿 | `agent/AgentSessionExecution.scala:2143-2178`（显式 device＞会话默认＞本机三段判据、fleet 强制、stray 豁免、RemoteExecutor.execute）；`core/tools/RemoteExecutor.scala:175-181`（P2P POST /api/neblink/remote-exec＋relay 兜底双路）、`:1853-1857`（remoteableTools=六件 Bash/Read/Write/Edit/Glob/Grep）；RemoteExecCancelRegistry＋`neblink/BgCancelAction.scala`（取消面） | 主链 executeToolInner 远端分支（`:2177-2178`） | **已定位点**：§2.4 反证表「远端执行腿（R6 修后同口）」＋§2.5 远端腿同判（Q-4）＋批 5 取消面收编——本节零新增 |
| 丙3 外部渠道桥（飞书/微信/邮件感知） | `social/FeishuBridgePlugin.scala:26-49`（feishu-chan 长连接→会话绑定→injectMessage）；`WeixinIlinkBridgePlugin.scala:26-51`（iLink 协议在官方 ClawBot side-car，本仓只做缝）；注入单点 `gateway/WebSocketRoutes.scala:924-946`（THE ONE bridge→session injection point）；`perception/MailClient.scala:13-25`（零第三方依赖 IMAP/SMTP）；`gateway/PerceptionIngestRoutes.scala:63-100`（唯一根才注入 ImmediateInput） | 用户飞书/微信/邮箱↔会话；perception=独立 daemon 进程（DaemonDock 托管） | 人机面邻近（J6）；Q-29 位置待裁 |
| 丙4 图床/头像代理 | `neblink/AvatarProxy.scala:9-24`（服务端代取本账号头像，非开放代理，只取 identity.avatarUrl） | web 本地优先头像缓存（avatarCache.js） | 表示层（J6） |

消费关系实测（本轮 grep 亲证）：`import nebflow.neblink`＝gateway 14 文件＋neblink 内 2＋agent 1（SharedResources）；actor/cli 零直引；core 经端口缝（NeblinkServicePort/NeblinkClientPort/RelayTunnelPort/WsHubPort）反向消费，分层门禁在场（DeviceRegistry:25-26）。**方向关键读数：neblink 不是叶子**——上行 import 实证三处：`AgentSessionSync.scala:7`（nebflow.agent.SubAgentTaskStore）、`DeviceMailInbox.scala:6-9`（nebflow.actor.AgentCommand＋core.AgentRuntimePort＋core.tools.MailTool）、`NeblinkRelayTunnel.scala:9-11`（nebflow.core.RelayTunnelPort/hotupdate.RemoteUpdateAction/core.tools.ToolRegistry）——连接域现状是**上层编排件**（写钩外推/会话注入/工具路由都在它体内），不是基。

**跨仓漂移读数（J1 证据）**：neblink 客户端在产品线三仓各有一份——本仓 1932 行、`nebflow-cli/src/main/scala/nebflow/neblink/NeblinkClient.scala` 1811 行、`nebflow-bchain/src/main/scala/nebflow/extensions/neblink/NeblinkClient.scala` 1841 行（本轮 wc＋diff 亲测）；字段已漂移：统一更新 M3 ③ 的 HeartbeatResponse `update` 挂账键仅本仓有（cli 件仍为裸对端列表），cli 件尚存本仓已退役的 LogtoDeviceFlow。仓内则相反：唯一实现点纪律密集（AttachUpload「唯一分块驱动」、PortalWireCodec「唯一编码点」、handleBridgeMessage「THE ONE injection point」、统一登录批 #924 三路径收口、hblife 心跳自振批）——**漂移发生在仓外复制体之间，仓内靠逐批整改手工保持同源**；「连接原语若在，三线可同引」正是基的用武之地，但前提是语义边界先钉死。

**三案落位**（判据编号引用；均〔提案〕级）：

| 维度 | 案①：立「云链接基」第六格 | 案②：归位现有基位点（不立新基） | 案③：不立格、挂问句（本轮采） |
|---|---|---|---|
| 形状 | 新建基（纸面名 nebflow.linkbase）：连接原语候选六族——会话自愈/心跳判活/端点发现/中继路由/配对验签/分块续传——抽最小 trait 面；门户/好友/dropbox/桥等上层单向引用 | 丙2 随既定批次走（§2.4/§2.5/批4/批5，零增量）；乙2/乙3/丙1 的持久化散面作 db 基扩展观察项（Q-26）；通道缝（ChunkTransport 等）维持现状 | 格数维持五基；语义边界问句 Q-22…Q-29 直问作者；散面表＋六族最小面候选备档，拍板后增补轮落位 |
| 论据 | 作者逐字点名三基之一且唯一无对位件；J1 跨仓漂移现证（三份客户端字段已漂）；域内整改批密集（#924/attachcl/hblife）＝漂移反复发生的历史负空间 | 丙2 已在五基既定承接面，零增量即接住最贴工具链的一角；J3 现状不满足使案①必先大手术；J6 部分命中（甲4/丙3/丙4 表示层）留上层 | 作者信源仅五字；「连接器」语汇有先例——作者 13:11「db 在我的构想里是后端无关的连接器」——云端链接器＝同一抽象思想的云侧实例，还是独立第六基，五字不可判；最小面无地面真值，立格即臆测语义边界 |
| 反证 | J3 不满足（上行 import 三实证，见上）；J2 无位点承接（pilogic 位点表无网络/连接域格子）；语义未定先定口＝超编风险（防贪全门正面命中） | 连接域主体（入网/在线/文件流/消息语义）不在任何现有基既定面——案②只接住丙2 一角，甲/乙组仍悬空 | 纯等待零产出——不成立：本轮主产出即问句清单（纪律如此），散面盘点已备档，拍板后无空转 |
| 判据落位 | J1 仓外成立/仓内不成立（仓内已同源）；J3 前置手术；J4 部分（心跳间隔/端点策略是配置值） | J3 无需（形状零动）；J1/J2 对悬空面未触发 | 判据句两正路（J1/J2）在本仓内证据不足或前置未备——「进基」条件未成就；防漏门亦无新自造信号（存量豁免，Q-26） |

**建议〔提案〕：案③为底座，案②局部已在执行**——维持五基两层口径（§二总形已同步）；第六格开放，归 Q-22（语义边界）→Q-23（格数）链式拍板；散面表已定部位（丙2）随既定批次走，本路线图批次 DAG 零改动。若作者拍板立基（案①）：前置＝依赖倒置手术批（上行 import 清零，J3 门槛），落位＝批 5 后批 6 前插「连接基批」（增补轮，不重排既有批），最小面以 Q-24 答案钉死（防贪全）。**格数最终判定待作者——本节不代裁。**

## 三、压缩策略面裁定建议（半缺口，不代裁）

现状（亲读）：阈值硬编码全局（`CompactThreshold.scala:9-30`：`cw>300k ⇒ 256k`，否则 `cw×0.8`，注释自宣 hardcoded by design）；压缩管线全在主链引擎内（maybeAutoCompact、0.95 硬护+60s 冷却、FullCompact/应急清理）；G-2 预留四位点保持壳态；db 侧快照+压缩点已接（C7）。

| 维度 | 甲案：进基（激活 G-2 + 策略面抽基） | 乙案：不进基（推荐） |
|---|---|---|
| 形状 | 激活 Reserved.Compact 四拦截位；把阈值计算/摘要策略/保尾参数抽成基内 CompactionPolicy trait | 压缩保持主链策略件；基只承两件事——取证（db C7 快照+压缩点，已接）与位点（G-2 类型层预留壳，已在） |
| 收益 | 策略可插拔、位点可拦截、跨上层一致 | 零增量；半缺口的「事实面」一半已被两基接住 |
| 代价 | 违 J4（阈值/保尾/摘要取舍是业务策略本体）；core/compact 全家族迁移＝大批次；与割接轮叠加风险 | 策略可插拔性维持现状（主链内可自行演进） |
| 判据落位 | J1 不成立（消费位只有主链一处）；J4 命中 | J4 直判留上层；J2 位点承接仅到「格子」——L-7 已裁类型层预留，激活与否是 P-8 未决项 |

**推荐乙案**，理由：判据句 J4/J5 双杀甲案；pi 侧 P-8 本身未决，预留位形态已备，将来要开属「接线激活批」不是「建基批」；现网压缩无事故实证（对照 J1 的漂移证据要求，压缩面无双实现漂移——只有一套）。若作者要开，前置＝P-8 拍板 + 割接轮落定（位点触发依赖循环接管）。

## 四、构筑顺序与依赖 DAG

锚点重排说明（对任务书建议锚 `llm 二段→轮 C→割接→工具基→权限基` 的两处实测修正）：①**权限基先于工具基**——工具基 dispatch 管线的第一环就是权限闸（工具基引用权限基，反之为零），故顺序倒转；②割接先于两基——三批次都动 `AgentSessionExecution.scala`（全仓提交密度最高、九条 R 挂载），串行化避免同窗多改；llm 二段先于割接——PiProviderPort 适配器一次对位 llm.base，避免先接旧面再返工。轮 C 先于割接——消息面/事件面先稳定，割接的事件源切换落在已迁移的存储面上（双源窗口最小）。

| 批 | 内容 | 前置拍板 | 产出 | 验收判据 | 回归门 |
|---|---|---|---|---|---|
| 批 0 机械前置 | llm 错误类型下沉 shared（病-11 第一半，零行为批） | Q-15（推荐先沉） | 类型搬家 + import 改写 | 全量编译绿、零行为差 | 基线差集零新增红 |
| 批 1 llm 重筑 | llm.base 四口立 trait + 适配器包旧实现 + 非流并流（D-1=A）+ 故障事件进 db（D-5=B）+ 账本挂点（D-4） | Q-2、Q-5、Q-12/13/14 | llm.base 包；21 行矩阵逐行 spec；guard 写穿平移 | 病-1/2/3/4/5/8 对位测试绿；LlmClient 成唯一调用口 | llm 域全绿 + 全量差集 |
| 批 2 轮 C | LLM 消息流迁 `sessions/<sid>/messages.jsonl` 双读切换（D-11 完成态） | Q-3（D-12 对齐会：KernelEvent 已冻结+轮 A 已落，可开）；二阶报告前置五项 | SessionStore LLM 面双读切换 | V9/V1 对消息流形态；崩窗已 ack 行不丢 | 同上 |
| 批 3 割接轮 | AgentLoop.run 接管驱动腿（D-A）+ ws 开闸（D-C 同批，单一事件源）+ D-B 并行档（缺省串行不变）+ 三病灶残留收尾（主链三处 @volatile、真跨 run 轮帽、迟到语义显式化） | Q-1、Q-7、Q-8 | 驱动腿割接；影子面转正；pilogic 判据 A-1/A-2 在真链生效 | 行为等价差分（影子审计行 vs 割接后事件流对拍）；pilogic 80+18 测保持绿 | 双跑基线差集 |
| 批 4 权限判定基 | perm 基 trait + permissionDecision 收编 + MCP 面同判 + 远端腿过闸（按 R6 裁定落法） | Q-4（R6）、Q-9 | PermissionOracle 唯一判定口；R6/R8 闭合或显式裁定豁免 | 三面（本地/MCP/远端）同判测试；权限单三态重装（V6）保持 | 同上 |
| 批 5 工具执行基 | toolbase 唯一执行口 + FileLockManager 必选化 + Bash 接锁（R9）+ 统一超时 wrapper + 结果帽/取消面收编 | Q-10、Q-11 | ToolRuntime 管线；割接后实现 pilogic ToolExecutor | R9 闭合（Bash 写有互斥）；超时 NOT FOUND 补位；并发写双会话测试 | 同上 |
| 批 6 总验收 | §六清单全跑 + 门固化进 CI 面 | 全部 Q 项闭合 | 验收报告 | W1–W8 | 全量 |

依赖边：批 0→批 1；批 1+批 2→批 3；批 3→批 4→批 5→批 6。批 1 与批 2 文件面不相交（llm/* vs SessionStore/db 面），作者若开并行写码轮可并行，当前无并行写码轮故按串行排。压缩面若裁进基（§三甲案），其批次插在批 3 之后（位点触发依赖循环接管）。

## 五、上层引用边界表

现状实测 vs 目标（引用方向一律「上层→基」；基→基仅允许向下到 db 与工具基→权限基）：

| 层 | pilogic | db | llm 面 | 权限面 | 工具面 |
|---|---|---|---|---|---|
| 主链 agent/actor | ✓ 三文件（PiLoopWiring/AgentState/FlowMapEventLog）——目标维持 | ✓ 八文件——目标维持 | **现状：10 文件直引 llm 内部（病-11 现值）→ 目标：只引 llm.base + shared 类型** | 现状：permissionDecision 内嵌 `AgentSessionExecution` → 目标：只经 perm oracle | 现状：executeToolInner 内嵌 → 目标：只经 toolbase |
| gateway/前端 | 零（ws 帧＝接线层桥，闸门关）——维持零 | 零——维持零 | 经路由转发，不直引——维持 | 零——维持 | 零——维持 |
| 工具/MCP/远端 | pilogic ToolExecutor ← 接线层适配（空挂） | 零（结果落盘自持 → 批 5 收编进 toolbase） | 零 | **现状：远端腿绕闸（R6）→ 目标：同过 oracle** | 目标：远端/MCP/本地同一执行口 |
| 接线层（PiLoopWiring 等） | ✓ | ✓ | ✓（批 1 后） | ✓（批 4 后） | ✓（批 5 后） |
| 基→基 | **零依赖（实测 0 命中，冻结）** | 零 nebflow 依赖（V7，冻结） | llm.base→db 允许（GuardStore 实现）；llm.base→pilogic 禁 | perm→db 允许（pending 单）；其余禁 | toolbase→perm、→db 允许；→pilogic 禁（经接线层） |

**机械门方案**（把 pilogic 三门模式扩展为五基边界门，落 `src/test/` 固化 spec，沿用 PilogicHygieneSpec/DbSourceHygieneSpec 先例形态）：

- G1 叶子门：pilogic/db 零 `import nebflow.{agent,core,gateway,llm}`（现状绿，保持）；llm.base/perm/toolbase 禁 agent/gateway（llm.base 另禁 pilogic）。
- G2 反向门：五基禁上行 import（pilogic/db 现状 0 命中为基准）。
- G3 上层口门：agent/gateway 禁 `import nebflow.llm`（非 `.base`，病-11 闭合判据）；`TOOL_MAP.get` 全仓唯一命中（分发口不增殖）；oracle 化后 `PermissionOracle.judge` 唯一判定口（`permissionDecision` 消失或退化为委托）。
- G4 自造原语门：上层新代码零裸写（`os.write.over` 于 db 管辖树外的新增面）、零自造事件 envelope、零自造轮帽/重试帽、零第二套危险命令检查——四类信号即§一「防漏」判红的机械载体。

## 六、「最小可信基完成」的总验收（可判定完成态）

完成态定义：**五基落成且上层新代码在四类自造信号上零命中、三门（叶子/口/原语）全绿、全量测试绿**——即作者两定义同时可判：安全性＝下述每条都有测试读数；完备性＝上层无需再造任何基础性原语。

| # | 验收项 | 判红形 |
|---|---|---|
| W1 | 全量测试绿（基线差集法：与合批前基线同命令双跑，差集为空；环境红以当日基线复现豁免） | 差集非空即红 |
| W2 | 五基边界门 G1–G4 固化 spec 全绿 | 任一门红即红 |
| W3 | 自造原语 grep 门零命中（裸写/envelope/轮帽/危险检查四类，§五 G4） | 任一命中即红 |
| W4 | 单一口断言：LlmClient 唯一调用口（含非流面）、TOOL_MAP 唯一分发口、PermissionOracle 唯一判定口（远端/MCP/本地三面同过闸，R6 闭合态或作者显式豁免在档） | 出现第二口即红 |
| W5 | 既有判据继承绿：pilogic 三机械门+A-1/A-2 真链生效、db V1–V10+chaos 保持绿 | 任一退化即红 |
| W6 | llm 21 行故障语义矩阵逐行 spec 绿；权限三面同判测试绿 | 缺行/不同判即红 |
| W7 | 完备性反证：五基反证表全非空（每方法组 ≥1 非测试引用者或显式批次解锁注记），〔可裁/冻结〕项全部有作者处置记录 | 引用面为空且无解锁即红 |
| W8 | 路线图 Q 项全部闭合（拍板或显式搁置在档）；本路线图与实际批次偏差有订正记录 | 悬空即红 |

## 七、待拍板清单（整合，按解锁面大小排序，带推荐不代裁）

| # | 问句（一句可答） | 来源 | 推荐 |
|---|---|---|---|
| Q-1 | 驱动腿割接范围：影子织入+割接轮空挂（现状）→ 批 3 整体割接？ | 轮 A D-A | 批 3 割接（影子已钉可信基，差分验证条件已备） |
| Q-2 | 非流面收口口径：A 并入流面单状态机 / B 最小补丁 / C 降级单候选 | llm D-1 | A（B 已被历史证伪） |
| Q-3 | D-12 字段对齐会现在开？（KernelEvent 已冻结、轮 A 已落——障碍已清） | db D-12/轮 C 前置 | 开，批 2 前置 |
| Q-4 | R6 对端绕闸：裁定（对端自持策略）还是漏接（须同过闸）？ | 导航件 §10-6/权限基 | 漏接——同过 oracle（远端信任面归发起侧档位）；若裁定自持，权限基记录豁免 |
| Q-5 | 轮帽/预算灌入方向：A 参数灌入+B 保守默认兜底？ | llm D-4 | A+B 混合（纯 A 在割接前无保护） |
| Q-6 | 压缩策略面：进基（甲）还是不进基（乙）？ | 本方案§三 | 乙（不进基，G-2 维持预留） |
| Q-7 | ws 面开闸时机：随割接同批？ | 轮 A D-C | 随批 3（单一事件源，避免双源窗口） |
| Q-8 | 工具批执行形态：runBatch 增并行档（缺省串行）？ | 轮 A D-B | A（缺省不变＝行为等价面零扩） |
| Q-9 | SafetyMode 阶梯逻辑归权限基，档位值留配置？ | 本方案新增（P-1） | 是（J4：值是策略，阶梯是机制） |
| Q-10 | FileLockManager 必选化 + Bash 接锁（R9 闭合）？ | 本方案新增（T-1） | 是（缺省 None＝保护不在场，管线内建后 Write/Edit/Bash 同护） |
| Q-11 | 统一 per-tool 超时 wrapper 语义：声明帽为下限的硬取消？ | 本方案新增（T-2） | 是（现 NOT FOUND 的补位；watcher 判死保留为第二道） |
| Q-12 | AdaptiveThrottle 缺省：默认开？ | llm D-3 | 开（排队不丢弃，风险仅延迟） |
| Q-13 | 故障事件进 db 粒度：档位转换级（B）？ | llm D-5 | B（全量事件化＝搬 llm log 进 db，检索价值低） |
| Q-14 | baseUrl 配置校验：保存时校验+存量扫描（A）+请求期分类优化（B）都做？ | llm D-6 | A+B 都做 |
| Q-15 | 错误类型下沉时机：重筑前单独机械批（B）？ | llm D-7 | B（批 0，降低批 1 diff 噪声） |
| Q-16 | 部分内容后失败：维持整 turn 重发（A）+ 重发计入账本？ | llm D-2 | A+账本（正确性优先，代价可见化） |
| Q-17 | §一判据句（J1–J7+超编反证+四类自造信号）采纳为后续批验收基准？ | 本方案新增 | 采纳（「最小」的尺子本身需作者认可） |
| Q-18 | db〔可裁·诊断面〕三项（sessionIndex/eventIndex/verify）：冻结保留还是裁？ | 本方案§2.2 | 冻结保留（总验收 W 项消费它们） |
| Q-19 | 基间分层确认：基→db 允许、pilogic 零依赖冻结、工具基→权限基允许？ | 本方案新增 | 确认（现状实测即此形，钉死防漂移） |
| Q-20 | hook 引擎形态：归一（pilogic hook 面对外化，甲）还是分层（新引擎层，形参照 v2 controller，乙）？ | 作者 12:58/13:11＋本方案§2.6 | 甲案起步＋乙案挂账（J1 漂移实证出现前不立新层；参照件＝v2 canonical-flow 的 controller 形） |
| Q-21 | db 基要不要为「后端无关连接器」预留 backend 抽象位（现有 JSONL 文件族＝后端之一，pg 潜在实例）？ | 作者 13:11（v2 思想）＋本方案§2.2 | 预留不封死：trait 面（D-3 对冲）即抽象位，维持；pg 第二实例不在本路线图批次内立（J5 单消费位、无漂移实证；抽象未被第二实现检验前不宣称可信），待跨进程/多实例数据共享需求出现再立批。两线不同构、思想一致 |
| Q-22 | 「云端链接器」指涉边界：A＝仅用户↔云服务器链（link-server 客户端：登录/心跳/发现/中继/门户）；B＝A＋设备间通道（presence/dropbox/附件/投影）；C＝B＋外部渠道（飞书/微信/邮件感知）；D＝云服务器侧组件本身（scala 线之外）？ | 作者 12:58 五字＋§2.7 散面分组（甲/乙/丙） | 无推荐——语义归作者；散面表已按四选项切组备查 |
| Q-23 | 格数裁定：立第六基（云链接基，案①）还是维持五基＋归位位点（案②/③）？ | 本方案§2.7 三案 | 维持五基直至 Q-22 拍板（语义未展开前立格＝臆测；案③为底座、案②局部已在执行） |
| Q-24 | （立基才答）最小面＝哪些口：六族原语候选（会话自愈/心跳判活/端点发现/中继路由/配对验签/分块续传）哪些进基、哪些留上层？ | 本方案§2.7 | 无推荐——口清单随 Q-22 边界才能钉死；散面表现网唯一实现点已备档（防贪全） |
| Q-25 | （立基才答）依赖倒置前置：neblink 上行 import agent/actor/core 三实证（§2.7），是否接受「原语下沉＋注入面上翻」倒置手术批（J3 门槛）？ | 本方案§2.7 | 接受（若立基则无捷径——J3 是判据句硬条件） |
| Q-26 | 连接域存量自造持久化（devices.json/dropbox messages.json/DeviceMail 去重账本进程内[作者待裁 F2 同族]）是否收编 db 基扩展面（G4 存量豁免注销）？ | 本方案§2.7＋§五 G4 | 收编方向（防漏门口径一致：事实面多制并存即 J1 证据）；批次后置至轮 C 后评估，不进当前 DAG |
| Q-27 | 「连接器」语汇：13:11「db 是后端无关的连接器」与「云端链接器」是同一抽象思想的两实例（连接器＝后端无关抽象层通名），还是两个独立基？ | 作者 13:11＋12:58 | 待作者——此答直接改写 Q-23 的形状（若同一思想，云链接基可能形同 db：后端无关连接器、传输后端可换，而非六族原语大杂烩） |
| Q-28 | 云端链接器的「基」落 scala 线还是 link-server 侧仓？（云侧独立演进实证：`NeblinkClient.scala:527-529` 字段与 link-server M2 收侧逐字对齐、`LogtoTransport.scala:26-31` relay 侧退役＝link-server 批次台账 #924） | 本方案§2.7 | 若作者答 D（云侧组件），本路线图只保留客户端对位面＋契约冻结件，scala 侧不立基 |
| Q-29 | 外部渠道（飞书/微信桥、邮件感知）在基清单的位置：连接域一部分/接线层/永不进基（J6 人机面）？ | 本方案§2.7 丙3 | 接线层（J6：入站注入单点＋出站聚合已是表示/接线形态；协议细节在 side-car/长连接，无基消费者） |

## 8. 收尾自证

- 本件路径 `docs/20261105_125232_scala-minimal-trusted-base-roadmap__delegate-nebflow-92abb5ed.md`；开工第一动作＝大纲落盘 commit `e64b796f1`（铁律①已履）；铁律②（卡死失败报告）未触发。
- wiki 校验：开工基线实测 1 条既有 broken-link（keyboard-layer 件，他人存量）；落盘后复跑（主工作区 docs/ 同名副本）＝**仍 1 条、同一处** ⇒ 本件新增断链 0、本件违规 0。
- 亲读核验范围：§0 所列锚点全部为本轮在 `996b66377` 现值复核；导航件旧值（如分发口 `:2068`）已被轮 A/B 织入推移，本件一律用现值。
- 零代码：本件为纯 docs，src/ 零改动；不 push。
- 补充输入处置：作者 13:11 令在本轮写作中段到达（§2.6/Q-20/Q-21 三处落位）；其 v2 侧地面真值件当日未落盘（`triple-v2/docs/` 实测无今日文件），本件以在档最近邻 `author-interaction-canonical-flow.md`（本轮亲读 controller＝pub-sub 路由器＋事件拦截器、C1、J5 行）为参照并如实标注——v2 侧真值件落盘后如与该参照有出入，以真值件为准回改§2.6。
- 自曝：①本会话中途曾因宿主中断恢复一次（重启后从已读材料续写，无材料丢失）；②PTC 未试——纯 docs 轮无需外部工具（既往轮已证 command not found）；③主链 llm 直引文件数（10）与审计件（4）不一致，因审计只数了错误类型 import、本轮数了全量 import——口径差异非证伪，已在§0 标明；④§2.6 编辑期一度误删§三标题行（Edit 匹配失手），当即发现补回，成件无缺；⑤其余未发现。

**补格轮增量自证（11-05 晚，`33cad0d99` 基线，delegate-nebflow-3e54d58f）**：

- 本轮改动＝纯 docs 增量六处：顶部一句话／§0 补格轮基线行／§二总形口径同步段／§2.7 新节（散面表 14 行＋消费读数＋跨仓漂移读数＋三案表＋建议）／Q-22…Q-29 八行／本段。§一判据句、§2.1–2.6、§三–§六、批次 DAG、Q-1…Q-21 原文零改动（红线遵守，diff 可验）。
- 亲读核验：§2.7 散面表锚点全部为本轮在 `33cad0d99` 现值亲读（Read 逐处复核）；消费关系 grep 本轮亲跑；跨仓读数（cli 1811 行/bchain 1841 行/scala 1932 行＋update 键漂移）本轮 wc＋diff 亲测；「云端链接器」全仓零命中亲测复核。
- 编辑事故自曝：§2.7 插入时 Edit 匹配曾吞掉§三标题行一次，当即发现补回（与本轮§2.6 的④同型失误，成件无缺——diff 复核§三标题在场）。
- wiki 校验：本 worktree docs/ 缺未跟踪的兄弟输入件（pilogic/db/导航等件为盘上产物），绝对断链读数＝16 处/8 目标（编辑前后差集法：编辑后同为 16 处/8 目标、零新增——本节零新增 wikilink）；主工作区 docs/ 全量读数以合批后复跑为准。
- 侦察兵声明：本轮曾派一只读 Subagent 做广度消费地图（包→职责→消费者），本节引用的一切锚点仍为本会话亲读复核——广度线索来自侦察、锚点真值亲读。
- link-server 仓定位：开发区 18 仓实测无 link-server 名命中（stand-rust＝triple 运行时线；nebflow-cli/nebflow-bchain＝neblink 客户端复制体所在）；云侧组件独立演进的证据面＝本仓注释引用（NeblinkClient:527-529/LogtoTransport:26-31），Q-28 以此为据。

相关：[[20261104_121214_scala-pi-logic-design-v1__delegate-nebflow-6513b1ad|pi-logic 设计 v1]] · [[20261104_121502_scala-db-module-design-v1__delegate-nebflow-006825e8|db 设计 v1]] · [[20261104_230447_scala-llm-provider-audit-redesign__delegate-nebflow-0bd60c6b|llm 审计重筑]] · [[20261104_215400_scala-pi-logic-wiring-a-round-a__delegate-nebflow-9db9d0d1|轮 A 报告]] · [[index]]
