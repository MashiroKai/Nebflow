---
type: audit-redesign
status: pending-author-review
---
# llm-provider 可信基化第一段：全貌审计 + 重筑设计方案

> 范围计划见 [[20261104_225355_llm-provider-audit-scope-plan]]；轮 B guard 档（db 接线二阶）背景见 [[20261104_152049_scala-db-wiring-plan-phase1__delegate-nebflow-678885ee]]；loop-guard 误触发史见 [[20261102_195200_loop-guard-misfire-diagnosis-and-fix]]。
> 轮次定位：零代码轮（只审计+设计，实现=作者过目后的第二段）。基线 main `d932f1509`（worktree 实测）。
> 作者裁定（2026-11-04 22:50）承接：pilogic✓db✓ 为真可信基；llm-provider 现状=「被持久化加固过的存量」——各次事故的**止血补丁**都在（轮帽、软回避、配额窗、看门狗、transport abort、并发闸），但从未**重构为一个基座**。本件给全貌病灶与重筑形状，不代裁。

## §0 结论速览

1. **病灶 14 条**：高 3 / 中 7 / 低 4（§3）。最高优先不是缺功能，而是**同一语义的两套实现已经漂移**——非流面（分配器/压缩/小判官走的 `sendMessage`）绕过了流面 2026-09 已修复的全部三层驱逐分流，一次 400 就把 provider 全局打 Down。
2. **三锚核验**：A1 属实（双 leg）、A2 属实（域属轮 A，登记移交）、A3 属实且为本轮设计基座（B2 语义原样保留）。
3. **重筑方向**：不是推倒，是**收口**——把「分类/链遍历/退避/健康档/守卫写穿」五件事各收敛到单点，对外暴露一个窄 trait 面（§5.1），故障处置一张矩阵（§5.2），三方边界一张表（§5.3），7 项待作者拍板（§5.4）。

## §1 审计范围与边界

- 亲读域：`src/main/scala/nebflow/llm/`（interface/registry/fallback/HealthMonitor/AdaptiveThrottle/adapter/ModelRegistry/SearchProviderResolver + providers/ 两 adapter + decision/ 11 文件）、`shared/fallback.scala`、`shared/HealthTypes.scala`、`shared/Defaults.scala`（常数面）、`core/LlmRuntimePort.scala`、`core/LlmLogWriter.scala`（面读）、`core/jev/JevFallback.scala`、`agent/`（只读：AgentActor/AgentProcessing/AgentSessionExecution/AgentStreamPipelines 的 llm 消费腿）、`pilogic/Provider.scala`+`LoopCore.scala`+`KernelEvent.scala`（只读：接缝）。实配清点：`~/.nebflow/nebflow.json`。
- 零触碰（轮 A 独占，引用只读）：AgentSessionExecution / AgentProcessing / pilogic 包的**修改权**；本件对其病灶只登记不动。
- 轮 B 产物（HealthMonitor guard 档写穿/重启回填/坏档保守）**衔接吸收，未推翻**——§5 设计中原样保留为基座语义。

## §2 三锚核验（逐条亲读）

| # | 锚 | 核验结论 | 实况 |
|---|---|---|---|
| A1 | `fallback.scala:81` MaxTurnLlmCalls=4 只数失败重试、真轮次上限 NOT FOUND | **属实（双 leg）** | :81 常量在；注释 :71-80 明示「incremented only in the AgentActor LlmFailed retry branch. Normal tool-loop calls do NOT count」；行为证实：AgentState.scala:589-599（计数器语义注释）、AgentSessionExecution.scala:1094（dispatch 前查 `llmCallsThisTurn >= MaxTurnLlmCalls`）、AgentProcessing.scala:230-232（只在 LlmFailed 重试分支 +1）；「真轮次上限不存在」证实——成功工具循环调用无任何帽（见病-2） |
| A2 | main 腿 `unsafeRunSync:2740/2752-2753` 阻塞点 | **属实**（域属轮 A，登记移交） | :2740 = `deviceInfoSnapshot` def 起点；:2752-2753 = `ms.identity.unsafeRunSync()` / `ms.peers.unsafeRunSync()`（neblink 名册读，提示词装配同步路径）。30s memo 缓存（:2745）缓解稳态，缓存过期帧阻塞 actor 线程；`try/catch` 吞异常回 None（:2789） |
| A3 | 轮 B guard 档（softAvoid/markDown/markUp 写穿 db、重启不归零、坏档 300s 有界保守） | **属实，为现状基座** | HealthMonitor.scala:137-158 `persistGuardDoc`（转换时写穿 `writeAvoidGuard`，best-effort WARN 回落内存）；:109-131 `hydrateGuards` 重启回填（Present 回填 / Absent 空 / db 故障降级内存）；db/DbCore.scala:752-771 `recoverAvoidGuardConservative`（Corrupt ⇒ 全键保守避 `QuotaAvoidWindowMs=300s` + 隔离，不静默空起步）。设计吸收：§5.1 `GuardStore` 原样承载 |

## §3 病灶清单（能力级，严重度排序）

### 高（P0，3 条）

**病-1｜非流面驱逐语义与流面分叉——辅助调用可把 provider 全局打 Down**
行为：非流 `send` 的 `onProviderExhausted` 对一切 Permanent/Fatal/重试耗尽统一 `markDown("provider exhausted")`，**不读** classification 的 `evict`/`quota` 分流；流面 2026-09 已修的三层分流（Format-400 `evict=false` 不驱逐、Timeout 软回避 45s、配额软回避 300s+换链）在非流面全部缺席。后果：分配器/压缩/小判官等辅助面一次 400-Format 或一次慢超时，就把该 provider 在**共享健康档**上打 Down，主链立刻绕道；流面当年 deepseek 7min 35 次 DOWN/UP flap 的形态在非流面仍活着。证据：fallback.scala:441-457（`notifyExhausted` 三分支统一触发，无 evict 判读）× interface.scala:651（非流面 `onProviderExhausted = markDown("provider exhausted")`）× interface.scala:1151-1168（流面三层分流对照）。

**病-2｜真轮次上限缺失——一次 turn 的 LLM 调用数与 token 消耗无帽**
行为：`MaxTurnLlmCalls=4` 只数**失败重试**再派发；正常工具循环的成功调用零计数、零上限；turn 级 token/时长预算不存在（pilogic `LoopPolicy` 三元帽 turns/tokens/wallClock 只在 pilogic，主链未接）。后果：2026-08-18 事故堵住的是**失败重试放大**；一个行为失控但每次都成功的工具循环 agent（读→改→编译→再改的病态循环）可以无限烧钱，直到 600s 看门狗或用户手动停。证据：fallback.scala:71-81 × AgentState.scala:589-599（「tool-intensive agents … free to call the LLM as often as their loop needs」）× AgentProcessing.scala:226-232 × pilogic/LoopRuntime.scala:242-244（三元帽唯一存在处，主链侧无对应物）。

**病-3｜成本无账本——usage 只有读数没有预算执行面**
行为：token usage 逐响应抽取（`TokenUsage`）→ 回传前端（UsageUpdate）+ 落 llm log JSONL；无 per-turn / per-session / per-day 累计账本，无 spend 上限判定点；guard 档记「谁被避坑」不记「花了多少」。后果：预算只能事后从日志文件反推（grep JSONL）；「这个会话今天烧了多少」无权威答案；病-2 的帽即使要加也没有计量基座可挂。证据：OpenAiAdapter.scala:401-406、:555（抽取即回传）× AgentStreamPipelines.scala:284-289（聚合仅显示）× LlmLogWriter.scala:146/231（request/response 落文件，无 db 账本写入）。

### 中（P1，7 条）

**病-4｜流/非流双实现语义漂移（病-1 的结构性根）**
行为：`Fallback.tryProviderWithFallback`（非流）与 `interface.tryCandidate/attemptWithHealthCheck`（流）两套独立状态机；六项能力只在流面：全链轮帽 MaxChainRounds、全灭阻塞等待 waitForAnyUp+probeNow、seam guard（部分内容不换链）、软回避窗、配额窗、4xx 响应体留档 retain4xx。非流面全灭时直接 `RuntimeException("All providers unavailable")`（interface.scala:586，无结构化类型，上游不可分类）。后果：每修一个流面语义都要记得给非流面补——历史上已经漏了三层分流；新能力默认只覆盖主会话腿。证据：fallback.scala:387-470 vs interface.scala:754-1290。

**病-5｜退避/重试常数零配置面**
行为：MaxRetries=1、InitialBackoffMs=1s、MaxBackoffMs=10s、OverloadBackoffMinMs=60s、MaxChainRounds=2、MaxTurnLlmCalls=4 全是编译期常量；对照 streamTimeouts（`applyStreamTimeouts` 热配）与 adaptiveThrottle（每次现读热配置）。后果：事故中调参（如限流风暴把 OverloadBackoffMinMs 从 60s 提到 120s）= 改代码发版；只能靠 sys-prop 散点（probeTimeoutSec 一处有，其余无）。证据：fallback.scala:50-52/69/81/90 × interface.scala:115-127（对照面）× HealthMonitor.scala:28-29。

**病-6｜AdaptiveThrottle 缺省关——并发维保护不在场**
行为：429 反应式并发闸已建成（账户键=baseUrl、90%/70%/50%/floor2 阶梯、5min 静默回松、zhipu 1302 实证判据、FIFO 排队零丢弃），但 `llm.adaptiveThrottle.enabled` 缺省 false，实配也未开（nebflow.json 无该节）。后果：zhipu 429-1308 类限流风暴复发时，时间维退避（60s）在场、并发维降档不在场——同账户多 agent 并发会在退避窗结束后同步再撞墙。证据：registry.scala:47-55 × AdaptiveThrottle.scala:53-54 × ThrottleParams.from（`c.isEnabled` 缺省 false）× 实配 nebflow.json（adaptiveThrottle: None）。

**病-7｜部分内容后失败=整 turn 全量重发**
行为：seam guard 有意禁止部分内容后换链（防跨 provider 拼接污染，2026-08-20 实证），配套恢复=agent 层同 checkpoint 整 turn 重发（全上下文 ~250k token 再发一次）。后果：流抖动/中断高发期，每次 mid-stream 失败的 token 代价=整轮重发；无检查点/续传能力。这是**有意的设计权衡**而非缺陷，但其成本面从未被计量（连回病-3），也未被拍板确认过。证据：interface.scala:1036-1066（seam guard）× AgentProcessing.scala:257-266（全量重发）× AgentActor.scala:30-36（设计说明）。

**病-8｜故障事件零进 db 事件流**
行为：markDown/markUp/softAvoid/throttle 降档/轮帽触发/TurnBudgetExceeded，全部只写 guard 档（B2）+ WARN/INFO log 行；KernelEvent envelope 已在 pilogic 定稿、db 侧可存（appendEvent），但 provider 域零衔接。后果：与「db 为可信基」方向脱节——事后审计（如复盘一次限流风暴的完整时间线）要 grep 多个 log 文件，guard 档只有终态没有事件序。证据：HealthMonitor.scala:137-158（只写 AvoidGuardDoc）× interface.scala 全文无 KernelEvent 引用 × pilogic/KernelEvent.scala:1-16（envelope 已定稿）。

**病-9｜主腿提示词装配同步阻塞（锚 A2，域属轮 A——登记移交）**
行为：`deviceInfoSnapshot` 在同步装配路径 `unsafeRunSync` 读 neblink 名册；30s memo 缓解稳态，缓存过期帧阻塞 actor 线程；异常吞为 None。后果：装配延迟尖峰 + 名册缺失时静默降级。本作业零触碰，移交轮 A（或作者统一排）。证据：AgentSessionExecution.scala:2740/2752-2753。

**病-10｜配置面无 baseUrl 校验，坏 URL 到请求期才炸**
行为：provider 保存走通用 `updateConfig` 单帧嵌套写（无 URI 校验）；实配已有全角冒号 URL（`107-openai` 的 `http：api.llm.ustc.edu.cn/v1/`）。后果：候选照常进链，每次轮到它=请求期异常→落入 stringly 兜底桶（Unknown/Transient）→白付一次同 provider 重试+退避；用户在面板上看不出配置坏了。证据：cli/ProviderCommand.scala:64（写面=通用嵌套写）× 实配 nebflow.json（5 provider 清点时命中）× fallback.scala:363（兜底桶）。

### 低（P2，4 条）

**病-11｜错误类型出口未收口——上层直插 llm 包类型**
行为：`TurnBudgetExceeded`/`StuckAbort`/`RecoverableAbort`/`ShutdownAbort`/`AllProvidersDownTimeout` 定义在 `nebflow.llm`，被 agent 层 4 文件直接 import；`LlmRuntimePort` 已示范窄口倒置先例但只覆盖在飞/取消面。后果：llm 不可作为「被单向引用的可替换基座」——抽进程/换实现要搬类型、改 4+ 处 import。证据：AgentCore.scala:15 × AgentRegistryEmit.scala:16 × AgentSessionExecution.scala:16 × AgentStreamPipelines.scala:16 × interface.scala:16-45（定义处）× core/LlmRuntimePort.scala:9-23（窄口先例）。

**病-12｜ProviderAdapter 面无取消/预算参数——不符可抽进程形状**
行为：`SendMessageParams` 携带 attemptBackend（传输替换位）但无 AbortSignal/deadline/budget 位；取消靠外层 `interruptWhen`+transport kill，adapter 面不可见；pilogic `ProviderPort.stream(req, signal)` 已示范信号内参形状。后果：trait 面今天不能原样搬到进程边界后。证据：adapter.scala:8-65 vs pilogic/Provider.scala:22-23/57-58。

**病-13｜三套健康面并行（主链/决策/搜索）**
行为：ProviderHealthMonitor、DecisionHealthMonitor、SearchApiHealth 三份 Up/Down 状态机，判据与恢复模式三份维护（decision 面刻意独立有 golden-diff 先例注释，属可辩护裁量）。后果：健康语义演进（如未来加熔断窗口）需三处同步；/api/health 载荷拼装多点。证据：HealthMonitor.scala:160-194 × DecisionHealthMonitor.scala:9-35 × shared/HealthTypes.scala:14-17。

**病-14｜stringly 分类兜底腿的误判面**
行为：无结构化状态码时按 message 关键词匹配分类（"timeout"/"400"/"429"/"auth"…），报文措辞撞车即误分类；完全未识别的错误兜底 Unknown/Transient 必获一次同 provider 重试。后果：误判概率低但存在（如错误体恰好含 "400" 字样被判 Format-Permanent 跳 provider）；未知错误重试一次属可接受浪费。证据：fallback.scala:331-363。

### 锚点导航勘误（相对 #144 导航）
本轮三锚全部属实，**无第 4 处证伪**；#144 的前科（1 处证伪）不在本轮锚内。补充勘误：导航措辞「main 腿 unsafeRunSync:2740」易读成 2740 行本身是 unsafeRunSync——实况是 :2740 为函数起点、:2752-2753 才是阻塞点（§2 已按实况记）。

## §4 七面审计明细

### 4.1 接入面清点
- **协议面只有两条腿**：`LlmProtocol.{OpenAI, Anthropic}`（shared/config.scala:40-41）→ OpenAiAdapter（793 行）/ AnthropicAdapter（807 行）；provider 差异靠特判散布：ThinkingFamily（deepseek 重放/GLM 族）、SearchProviderResolver（zhipu web_search / qwen enable_search / kimi builtin_function）、budgetToEffort、requireThinkingPassback 缺省判据——新增 provider=改多点（病-11 同族，列入设计而非本轮病灶主列）。
- **实配 5 provider / 10 model**：USTC(openai,3) / deepseek(anthropic,2) / zhipu(anthropic,3) / 107-openai(openai,1，**baseUrl 含全角冒号**=病-10) / router(anthropic,1 本地 9997)。zhipu 走 anthropic 协议面（`open.bigmodel.cn/api/anthropic/`）。
- **链构建四级**（registry.scala）：session override（去重前置）→ agent 链（preferred+fallbacks，`getCandidatesForAgentUnfiltered`）→ 全局链（/model 主链锚，`getCandidatesUnfiltered`，SchemePolicy.nebulaChain）→ seed 兜底（首 provider 首 model）→ **储备层**（reserveTier：全部未引用 provider/model 按字典序追加，:439-473）→ disabled fail-closed（:236-237）→ 时段闸（schedule gate，请求级补滤单点 interface.scala:84-107）。
- **独立小链两条**：decision 面（jev/laya，DecisionProviders.build，fail-open）；Tier2a 搜索 API（zhipu web_search 独立计费面，SearchApiHealth 独立健康）。

### 4.2 fallback 与切换语义
- 流面：`attemptWithHealthCheck`（健康过滤→全灭 probeNow+waitForAnyUp 120s 窗→AllProvidersDownTimeout=Transient）⇄ `tryCandidate`（候选遍历，attempted 集合防轮内重试，`MaxChainRounds=2` 轮帽封 400-Format 闭环——2026-09-21 硬杀波定谳的修复）；切换后回切=成功即 `markUp`（事件驱动，2026-10-21 腿②）+ 探测流首事件判活（修「恢复不回绿」）。
- 与避坑 guard 分工（轮 B 吸收）：markDown=确证死亡（探测集）/ softAvoid=窗口型（45s 超时/300s 配额，不探测、到期自然回链）/ throttle=并发维（降档排队）。三层互补。
- 非流面：无轮帽、无等待、无分流（病-1/病-4）。

### 4.3 重试/退避/配额
- 同 provider 重试：MaxRetries=1（流面超时类跳过不重试 :1202）；指数退避 1s→10s 封顶+jitter；overload 类（429/529）≥60s 地板（retryDelayMs 单点，两路径共用）。
- agent 层：OverloadRetryMax=1（overload/inactivity/RecoverableAbort 可重试，其余 fail-fast）；退避 2s-10s、inactivity ≥30s；llmFailRetries（连续）与 llmCallsThisTurn（turn 累计）双计数；#692 网络不可达=ErrorFrozen 退避等待不烧预算（5s→5min）。
- 配额：QuotaUpstreamCodes={1308}+403→Permanent+quota 标志→300s 软回避+换链（流面）；1302 频率类=60s 退避（不换链语义正确）。
- 幂等性：chat 请求重试无副作用面（最坏=重复计费 token），设计可接受。

### 4.4 流式
- chunk 处理：adapter 内 SSE 增量解析（OpenAI data 行/Anthropic event+data 行），toolCall 状态机累积 id/name/args 分片，流死于 tool-call 中途时 finalizer 补空 id/name 摘要（不静默丢内容）；usage 经 stream_options.include_usage 随 Done 块回传。
- 中断/断流：两相看门狗（首 token 90s / 间隙 120s transient）+ 整流 600s no-progress（扣宿主睡眠秒）；seam guard（病-7）；per-attempt transport（PerRequestTransport 默认 true，shutdownNow 唯一实证解 parked-read 原语）。
- **db 事件流衔接：零**（病-8）——流事件只进 LlmLogWriter JSONL+SSE 前端。

### 4.5 超时与取消
- 超时金字塔：connect 30s → read 600s（sttp）→ 非流 IO.timeout 600s（双层）→ 首 token 90s / 间隙 120s / 整流 600s。决策面（DecisionHttpTransport）read timeout 显式 Undefined，靠上层分配预算兜底。
- 取消：三型中止（ShutdownAbort/StuckAbort/RecoverableAbort）+ halt Deferred + per-attempt abort thunk + L1(cancel)/L2(transport abort) 升级链；取消安全在 AdaptiveThrottle 排队面已处理（onCancel 回扣）。
- 阻塞点：锚 A2（病-9，轮 A 域）。

### 4.6 成本/预算
- 计量=逐响应读数（病-3）；预算=无（病-2）；pilogic RunSeed.policy 三元帽（LoopRuntime.scala:242-244）是主链未来接缝——provider 侧接法见 §5.4 D-4。

### 4.7 错误分类覆盖度
- 四类机制齐全：可重试（Transient：overload/network/reset/inactivity）✓；不可重试（Permanent：Format/Auth/404/quota/EmptyStream）✓；需换链（Permanent→next candidate + 配额窗）✓；需熔断（markDown+探测恢复 / softAvoid 窗 / throttle 降档）✓。
- 缺口：判定双轨（结构化+stringly，病-14）；流/非流两处消费不一致（病-1）；Fatal 族（上下文溢出/StuckAbort/RecoverableAbort/turn 预算）语义正确但散在 classifyBase 的 match 深处，无单点故障语义表（§5.2 补）。

## §5 重筑设计方案（第二段的施工图，本轮零代码）

### 5.1 trait 面（最小可信基口径）

```
package nebflow.llm.base            // 新包；现有 llm 包实现迁入，对外面先立后迁

// ① 客户端面——上层唯一调用口（send/stream 合一，现状双实现收口为单状态机）
trait LlmClient[F[_]]:
  def complete(req: LlmRequest, ctx: CallContext): F[LlmResponse]
  def stream(req: LlmRequest, ctx: CallContext): fs2.Stream[F, StreamChunk]
// CallContext = { signal: AbortSignal[F]      // 取消内参（补病-12，对位 pilogic ProviderPort）
//               , deadline: Option[FiniteDuration]   // 请求级预算（对位 RunSeed.policy wallClock）
//               , tokenBudget: Option[TokenBudget] } // 轮/会话 token 帽（补病-2/3，D-4 拍板后灌入）

// ② 分类面——故障语义单点（§5.2 矩阵的可执行形态）
trait FaultClassifier:
  def classify(err: Throwable): FaultVerdict   // reason/permanence/evict/quota/retryClass 单点产出
// 非流/流两消费点均只读 FaultVerdict，禁止各自 match（根治病-1/病-4）

// ③ 健康账画面——轮 B 语义原样保留（锚 A3 吸收）
trait HealthLedger[F[_]]:
  def markDown(id: ProviderModelId, reason: String): F[Unit]     // 写穿（B2 persistGuardDoc 语义）
  def markUp(id: ProviderModelId): F[Unit]                        // 写穿
  def softAvoid(id: ProviderModelId, window: FiniteDuration, label: String): F[Unit]
  def filter(candidates: List[ModelCandidate]): F[(List[ModelCandidate], List[ModelCandidate])]
// probe/waitForAnyUp 留在实现内（探测是恢复机制，不是接口承诺）

// ④ 守卫存储面——db 写穿的窄口（GuardDocs 原样，hydration 保守语义不动）
trait GuardStore[F[_]]:
  def persist(doc: GuardDocs.AvoidGuardDoc): F[Unit]
  def recover(now: Long, keys: List[String], conservativeWindow: FiniteDuration): F[GuardDocs.AvoidGuardDoc]
```

约束（作者口径逐条落位）：
- **上层单方向引用**：agent/gateway/decision → `llm.base` 接口；错误类型全部下沉 `nebflow.shared`（补病-11，`LlmRuntimePort` 窄口先例推广）；`llm.base` 不 import agent/pilogic。
- **不引新三方依赖**：sttp/fs2/cats/circe 现有面内；adapter 构造（StreamBackend）收到实现内部，接口面零 sttp 类型漏出。
- **可抽进程形状**：全 `F[_]` + 纯值类型；取消经 CallContext.signal（不再靠全局 inflight 注册表——注册表降级为本地实现细节）；LlmHandle 的 sessionOverrides/registry 依赖注入化。
- **迁移路径**：先立接口+适配器包旧实现（零行为变化），再逐面收口（非流并流面状态机= D-1 拍板项），最后搬类型（shared）。

### 5.2 故障语义矩阵（错误类别 × 处置 × 持久化）

现状列=实读行为；目标列=重筑后。持久化列：g=guard 档、ev=db 事件流、L=log、N=无。

| # | 错误类别 | 同provider重试 | 退避 | 换链 | 驱逐/避坑 | agent层 | 现状持久化 | 目标持久化 |
|---|---|---|---|---|---|---|---|---|
| 1 | Auth 401/403（配置错） | 否 | — | 是 | markDown+探测 | fail-fast | g+L | g+ev |
| 2 | Format 400（非溢出） | 否 | — | 是 | **流:不驱逐/非流:markDown（病-1）** | fail-fast | 流:L / 非流:g+L | 统一不驱逐+ev |
| 3 | 400 上下文溢出 | 否 | — | 否(Fatal) | 否 | 压缩/失败 | L | ev |
| 4 | 404 ModelNotFound | 否 | — | 是 | markDown | fail-fast | g+L | g+ev |
| 5 | 429 频率类（1302） | 是×1 | ≥60s | 耗尽后换 | 耗尽 markDown；throttle 降档（缺省关，病-6） | overload 可重试×1 | g+L（throttle 无持久化） | g+ev+throttle 档 |
| 6 | 429/403 配额类（1308/5h 闸） | 否 | — | 是（立即） | softAvoid 300s（流面）；**非流面 markDown（病-1）** | fail-fast | g+L | g+ev（窗口事件） |
| 7 | 529 Overloaded | 是×1 | ≥60s | 耗尽后换 | 耗尽 markDown | overload 可重试×1 | g+L | g+ev |
| 8 | 5xx ServerError | 是×1 | 1→10s | 耗尽后换 | 耗尽 markDown | fail-fast | g+L | g+ev |
| 9 | 首 token 超时（90s） | 否（跳过） | — | 是 | softAvoid 45s（流面）；**非流 markDown（病-1）** | fail-fast | g+L | g+ev |
| 10 | 流间隙超时（120s，Transient） | 否 | — | 是 | softAvoid 45s | inactivity 可重试×1（≥30s） | g+L | g+ev |
| 11 | 整流 600s no-progress | — | — | — | 否 | fail-fast | L | ev |
| 12 | ConnectionReset/断流 | 是×1 | 1→10s | 耗尽后换 | 耗尽 markDown | fail-fast | g+L | g+ev |
| 13 | NetworkUnreachable（本机断网） | 否 | — | 否（无意义） | 否 | ErrorFrozen 退避等待（不烧预算） | L | ev（等待/恢复对） |
| 14 | EmptyStream（完成无内容） | 否 | — | 是 | markDown+探测 | fail-fast | g+L | g+ev |
| 15 | StuckAbort（watcher L1） | 否 | — | 否(Fatal) | 否（provider 无辜） | turn 失败（Stop 即恢复） | L | ev |
| 16 | RecoverableAbort（kick/L2） | 否（流层） | — | 否(Fatal，防拼接) | 否 | 可重试×1 或让位队列输入 | L | ev |
| 17 | ShutdownAbort | 否 | — | 否 | 否 | 终止 | L | N（进程级） |
| 18 | TurnBudgetExceeded | 否 | — | 否(Permanent) | 否 | 快速失败（重试不再触发） | L | **ev+账本（病-2/3）** |
| 19 | AllProvidersDownTimeout | 否 | — | —（等待） | — | Transient 可重试×1 | L | ev |
| 20 | 全链轮帽 MaxChainRounds 触发 | — | — | 终局 | — | FallbackExhausted 上抛 | L | ev |
| 21 | 未知错误（兜底桶） | 是×1 | 1s+jitter | 耗尽后换 | 耗尽 markDown | fail-fast | g+L | g+ev+样本留档 |

21 行。矩阵即 `FaultClassifier` 的规格表——第二段实现时逐行钉 spec。

### 5.3 三向边界表（哪些归基、哪些留上层）

| 面 | 归 provider 基（llm.base） | 留 pilogic | 留 db | 留主链 agent（轮 A 域） |
|---|---|---|---|---|
| 重试 | 单 provider 重试+退避执行、链遍历、轮帽 | **循环级** RetryPolicy（turn 间重试策略） | — | turn 级重试裁决（llmFailureRetryable）、ErrorFrozen 网络等待 |
| 预算 | CallContext 接收 deadline/tokenBudget 并**执行**（超限即抛） | **LoopPolicy 三元帽裁决**（帽的来源与判定） | 账本存储（tokens/day 累计表） | 帽值的配置与用户呈现 |
| 健康 | markDown/softAvoid/probe/throttle 全部 | — | guard 档存储+hydration | — |
| 事件 | 故障事件**产出**（KernelEvent 载荷） | 事件 envelope 与 sink 契约 | 事件流存储/检索 | 会话侧消费（UI） |
| 取消 | signal 执行+transport abort | AbortSignal 契约 | — | watcher/SessionKick 触发权、unsafeRunSync 消除（病-9） |
| 分类 | FaultClassifier 单点 | — | — | 对 RecoverableAbort 的用户意图优先裁决 |

**边界表结论一句话**：provider 基管「一次调用的全部故障语义与执行」，pilogic 管「循环与预算的裁决」，db 管「事实的持久化」，主链管「用户意图与 turn 级裁决」——四者经 CallContext（预算/取消内参）与 KernelEvent（事实外流）两个窄口相接，现状已有一半窄口（pilogic ProviderPort/db GuardDocs），缺的是 provider 侧的对应端。

### 5.4 待拍板清单（编号+选项+推荐，不代裁）

- **D-1 非流面收口口径**（病-1/病-4 的修法）：A=非流并入流面单状态机（`LlmClient` 合一，非流=流聚合的语法糖）/ B=非流面最小补丁（补 evict/quota/timeout 三层分流，保持双实现）/ C=非流面降级为单候选不驱逐语义（辅助面失败不进健康档）。**推荐 A**（B 已被历史证伪——每修一处漏一处；C 牺牲辅助面的可用性语义）。
- **D-2 部分内容后失败的恢复策略**（病-7）：A=维持整 turn 重发（现状，正确性优先）/ B=检查点续传（provider 无续传 API，需 prompt 锚点拼接——违 seam guard 初衷，不推荐）/ C=部分内容保留+前端标注（不重发，接受截断）。**推荐 A**，配套=重发计入成本账本使代价可见。
- **D-3 AdaptiveThrottle 缺省值**（病-6）：A=默认开 / B=默认关+运维文档引导 / C=首次账户级 429 风暴自动开（一次性升级）。**推荐 A**（闸为排队不丢弃，默认开的风险=排队延迟，默认关的风险=限流风暴；作者 2026-10-29 建闸语义即保护）。
- **D-4 轮帽/预算的灌入方向**（病-2/病-3）：A=provider 基只接收 CallContext 预算并执行，帽值由主链/pilogic 灌入（LoopPolicy 对位）/ B=provider 基内建默认帽（全局常数兜底）。**推荐 A+B 混合**：接口按 A（预算是参数不是常量），实现带 B 的保守默认（如 per-turn token 帽=配置缺省 2M，可关）——否则 A 单挂在主链接线前无保护。
- **D-5 故障事件进 db 的粒度**（病-8）：A=全量事件化（每次 attempt 一条 KernelEvent）/ B=仅档位转换+轮帽/预算触发进事件流（attempt 级留 llm log）。**推荐 B**（A 体量=把 llm log 搬进 db，检索价值低；B 覆盖复盘时间线的刚需面）。
- **D-6 baseUrl 配置校验**（病-10）：A=保存时 URI 校验+doctor 存量扫描（报告 107-openai 全角冒号）/ B=仅请求期分类优化（坏 URL 直接 Permanent 跳过不重试）。**推荐 A+B 都做**（A 治存量与入口，B 治兜底语义）。
- **D-7 错误类型下沉时机**（病-11）：A=随 trait 面重筑一并下沉 shared / B=单独小批先沉（零行为变化）。**推荐 B**（先沉类型=第二段开工前的机械批，降低重筑批的 diff 噪声）。

## §6 校验与自检

- wiki 校验：`check_wiki_docs.py --root docs` 基线 6 违规（4 条为存量文档断链=基线不动；2 条为本轮计划件初稿，已修复：补 front-matter+图谱接线）；本件目标违规 0、新增断链 0（复跑读数见收尾报告）。
- 零代码核验：本轮全部产物=docs/ 两文件；src/ 零改动（git status 佐证）。
- 亲读核验：病灶清单全部 file:line 为 worktree 实读（main `d932f1509` 同基），无一处转抄导航。
