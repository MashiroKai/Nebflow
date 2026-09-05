# 响应超时/驱逐误伤修复第二批报告

> 2026-09-04。依据：`docs/Nebflow/20260903_timeout-mechanisms-audit.md`（~/.nebflow commit 422fe81，行号级证据；行号按符号定位复核无漂移）。
> 工作区：worktree `timeout-evict-fix`（基线 main@3c6f9864，独占使用），已按任务要求合并回本地 main 并清理。未 push、未动 origin。

## 一、开工基线与上游盘点

- worktree 预建基线 main@3c6f9864 → 开工 `git merge main` fast-forward 至 **a97d69b0**（上游 hold MVP 的 merge commit，零冲突）
- 上游「实施-节点暂停hold-MVP」（n-a9c374b6）已确认合并：其分支 commit f6a0b846、merge a97d69b0，以 `merge-base --is-ancestor` 口径核实
- 本任务域（LLM interface/HealthMonitor/adapters/LlmLogWriter/前端 timer）与上游 hold 域（NodeEngine/NodeTools/ProjectTypes）零交叠
- 合并回 main 前复检：main 已前进（proj-create-tool 并入，7426bd8f）→ worktree 内二次 `git merge main` 产生 merge commit c107d36c（零冲突，仅带入 NodeTools/AskUserQuestionTool/ProjectCreate 域文件，与我的前端 main.js/ws.js 零交集，逐文件核对无融合点）；主仓工作区 clean（无他人未提交改动涉我文件域，无需等待窗口）

## 二、五子项逐项

### ① 前端 busy timer 感知工具执行（作者症状直接根因）

**根因**（审计 §2.5）：前端 busy timer = streamTimeoutMs+30s（630s）纯静默，只看 WS 事件流；前台长工具执行期 toolStart→toolEnd 之间零事件 → 误显示「响应超时」卡且 send-path timer 发 interrupt 杀掉正在干活的 turn（实测 56/359 turn 超 630s）。后端停摆标准是「零输出+零 CPU 双条件 10min」，前端不感知——两套标准不一致是结构性误杀。

**改动清单**：
- `agent/ToolHeartbeat.scala`（新增）：`span(emit, interval)(io)` 心跳原语——io 运行期每 interval 发射 emit，值/异常穿透，结束必取消 fiber（RemoteExecutor 的 `hbFiber <- (IO.sleep(30s) *> touchAgentActivity).foreverM.start` 先例的 WS 面移植，先例位置 `RemoteExecutor.scala:160-163`）
- `shared/Defaults.scala`：`ToolHeartbeatSec = 30`（与 RemoteExecutor 心跳、BgHeartbeatIntervalSec 同频，明显小于前端 630s 预算）
- `agent/protocol.scala`：`AgentStreamEvent.ToolHeartbeat(label)` + toJson（主会话 `{"type":"toolHeartbeat","sessionId",...}` / 子代理 `{"type":"agentToolHeartbeat","agentId",...}`，与 ToolStart/ToolEnd 同款双形态）
- `agent/AgentCore.scala`：`withToolHeartbeat(...)` helper；包裹点 = `pipeToolExecutions` 的 `PermissionDecision.Allow => executeTool(call, callCtx)`——**仅实际执行段**；权限 Ask/AskUserQuestion 等待不包裹（前端 askUser 处理器既有「抑制 timer」契约，包裹会反向重武装 timer 造成回归）
- 前端 `ws.js`：`toolHeartbeat`/`agentToolHeartbeat` 入 STREAM_MSG_TYPES（否则非 active 会话被入口过滤器丢弃）；`convertAgentEvent` 增 `agentToolHeartbeat → toolHeartbeat` 映射
- 前端 `main.js`：`onMessage('toolHeartbeat'/'agentToolHeartbeat') → resetStreamTimeout`（与 agentToolEnd 既有重置面一致）

**取舍**：停摆标准统一方案取「后端心跳喂活前端 timer」而非「前端 toolStart 后暂停 timer」——前者保持前端 timer 语义不变（真停摆仍会触发，T2 回归面锁定），且与 RemoteExecutor/TaskStuckWatcher 的既有心跳模式同构；后者会在 toolEnd 丢失（连接断开）时让 timer 悬空。

### ② probe 形状对齐：400 Format/重放类失败不 markDown

**根因**（审计 §2.3/建议#1）：deepseek thinking 模式要求历史回传 thinking 块 → 跨 provider 会话缺块 → 400 → markDown → 探测「hi」空历史永远成功（p50 0.9s 打回 UP）→ 下一个 fallback 再 400。7 分钟 35 次 DOWN/UP flap，25 次驱逐里 provider 完全健康。**probe 形状对齐真实请求不可行**（失败根源在会话历史形状，探测无历史，永远对不齐），故取审计建议方向：**400 Format 类失败不参与驱逐判定**。

**改动清单**：
- `shared/fallback.scala`：`ErrorClassification` 增 `evict: Boolean = true` 字段（默认 true = 除 400 外全部维持现行为，所有既有构造零改动）
- `llm/fallback.scala` `classifyError`：结构化 HttpError 400（非 context-overflow）→ Format/Permanent/**evict=false**；catch-all 的 stringly 400（"invalid request"/"bad request"/"400"）同款 evict=false；context-overflow 400 保持 Fatal/evict=true；Auth/404/429/5xx 全部不变
- `llm/providers/AnthropicAdapter.scala` + `OpenAiAdapter.scala`：非 2xx 的 `case Left(error)` 从 `RuntimeException("... API error: ...")`（无状态码 → classifyError 落 Unknown/Transient → 重试耗尽照样 markDown——这才是生产 flap 的真实分类路径，audit 表 #8 的「400→markDown」经由该弯路）**升格为 `sttp.client4.HttpError(error, response.code)`**（结构化状态码，classifyError 的结构化分支直接接管）
- `llm/interface.scala` Permanent 分支：`!classification.evict → 不 markDown 也不 softAvoid，只 tryCandidate(rest)`（跳本次请求）；非流式路径 `tryProviderWithFallback` 的 `onProviderExhausted`（interface 挂 markDown）同步按 evict 门控

**取舍**：选「400 不驱逐」而非「探测对齐真实请求」，理由见上——前者零探测成本、根治 flap 循环，且 400 秒回本身证明 provider 活着；残余风险（provider API 契约变更导致持续 400）= 每请求一次失败尝试，远小于原 flap 风暴 + 探测烧钱。Auth/404/配额仍驱逐（T3 回归面）。

### ③ Timeout 类降级软下线回避窗

**根因**（审计 §2.3/建议#2）：首 token（phase-1 TimeoutException→Permanent）与流间隙（phase-2 StreamInactivityTimeout→Transient 但 isTimeout 恒真）超时全部无条件 markDown（interface.scala 原 760-769/787-808）——「慢 ≠ 死」，被驱逐的 provider 根本没病（flap p50 0.9s 恢复佐证）。

**改动清单**：
- `llm/HealthMonitor.scala`：`avoidUntilRef: Ref[IO, Map[String, Long]]` + `softAvoid(providerId, model, windowMs)`；`filterCandidates` 重构——avoid 窗口内候选从 up 剔除且**不进 down**（down = 探测集，不给刚超时的慢 provider 烧探测请求）；`getAvoidUntil` 观测口
- `llm/interface.scala`：Permanent 分支三分流（`!evict → 跳过`；`reason==Timeout → softAvoid(TimeoutAvoidWindowMs)`；其余 → markDown）+ Transient-timeout-exhausted 分支（isTimeout → softAvoid；429 等非超时 Transient 耗尽维持原 markDown）
- `shared/Defaults.scala`：`TimeoutAvoidWindowMs = 45_000`

**回避窗取值 45s 的理由**（审计建议 30~60s 区间内取定）：flap 实测恢复 p50<10s/p90<20s，45s ≈ 5×p90 抖动恢复余量；all-Down 门最坏等待 45s+5s tick（waitForAnyUp 的 5s 重查天然驱动窗口到期回链，无需新信号）≪ 120s 探测周期，不会误触发 AllProvidersDownTimeout；亦小于首个 90s 首 token 预算量级，不会让「偶尔慢一次」的 provider 冷却过久。

### ④ 跨 provider 重放 thinking 块适配（flap 风暴底层缺陷）

**根因**（审计 §2.3）：会话历史由不产出 thinking 块的 provider 生成（所有 OpenAI 协议 adapter 的 history 序列化丢弃 thinking，`OpenAiAdapter.toOpenAiMessages` 实证）→ fallback 到 requireThinkingPassback 的 deepseek（`registry.scala:38` 默认开）→ thinking 模式下 assistant 历史缺块 → 400 `"The content[].thinking in the thinking mode must be passed back to the API"`。

**改动清单**（`llm/providers/AnthropicAdapter.scala`）：
- `effectiveThinking(params)`（sendMessage/sendMessageStream 共用）：`requireThinkingPassback && thinking.isDefined && 历史存在无 thinking 块的 assistant 消息 → 返回 None`（本请求去掉 thinking 参数）+ WARN 日志
- `hasAssistantWithoutThinking`：assistant 消息（Right 无 Thinking 块 / Left 纯文本）判定

**取舍（转换优先、不可转换则剥离）**：历史「有」thinking 块 → 原样回传（2026-08-15 既有契约，AnthropicThinkingPassbackSpec 锁定，含签名块回传行为不变）；历史「缺」块 → 转换不可能（不可伪造模型没产生的推理内容）→ 剥离 = 本请求不进 thinking 模式（deepseek 对纯文本历史在非 thinking 模式正常接受；后续轮次 deepseek 自产 thinking 块后自然恢复）。**不选注入占位 thinking 块**：对 deepseek 空/伪 thinking 块的接受度无契约依据，赌 API 行为风险大于收益。

### ⑤ router 日志逐事件落盘（观测性）

**根因**（审计 §三 日志口径说明）：`LlmLogWriter.log()` 在流收集完成后一次性批量写 request/response/全部 SSE 行，时间戳均为写盘时刻——request→response 实测全部 <1.2s 而 output_tokens 高达 2 万，首 token 延迟/chunk 间隙无法实测（90s/120s 阈值复核因此无数据）。

**改动清单**：
- `core/LlmLogWriter.scala`：`log()` 拆三段——`logRequest`（流派发时写 summary/full+objects）、`StreamEventEncoder`（单请求 block-index 状态持有者，原 `chunksToSseEvents` 批量版重构）、`logStreamEvent`（每 chunk 到达即编码入队，**ts=到达时刻**；有界队列 8192+后台 fiber 异步写，ToolsLogWriter 已合并模式同款，溢出丢弃+WARN 不阻塞流）、`logResponse`（流结束写 response 行，**新增 request_id 字段**与 request 行对齐）；`flushSync()`+JVM shutdown hook；`setWriteDelayMsForTest` 注入面
- `agent/AgentCore.scala` `pipeLlmCall`：sendStream 派发前 `logRequest`；流管道 `.evalTap(chunk => logStreamEvent(sseEncoder, chunk))`；收集完成后 `logResponse`
- 兼容面：request 行 model 字段流派发时未知 → "unknown"（原实现是事后回填的 resultModel；response 行仍携带真实 model，viewer 按 request_id/相邻序关联不受影响）

## 三、测试与变异验红

### 新 spec（6 个，既有 spec 仅 ToolsLogAgentCoreSpec 因 log() 移除做编译级适配——fixture 调用改 logRequest+logResponse，断言语义零改动）

| spec | 用例 | 结果 |
|---|---|---|
| `ToolHeartbeatSpec`（4） | 心跳连续发射/值穿透/失败取消无泄漏/首跳在 interval 后 | ✅ 4/4 |
| `LlmLogWriterStreamSpec`（4） | request→response 两行落盘/逐事件 ts 可区分/tool-call 块 start+delta 对/慢盘不阻塞调用方 | ✅ 4/4 |
| `TimeoutSoftAvoidSpec`（5） | softAvoid 排除不 Down/窗口到期回链/Down+avoid 分层/接口 E2E 超时不驱逐 fallback 成功/Auth 驱逐回归 | ✅ 5/5 |
| `FormatErrorNoEvictSpec`（3） | 400 E2E 不驱逐跳下家/classifyError 单元（400 evict=false·溢出 Fatal·Auth/404 evict=true）/401 驱逐回归 | ✅ 3/3 |
| `AnthropicThinkingReplaySpec`（6） | effectiveThinking 四态单元/mock 出站 body 无 thinking 字段/deepseek 原生历史保持回传 | ✅ 6/6 |
| `tests/tool-heartbeat-timer.spec.mjs`（4，Playwright mock-WS+时间压缩 400ms） | 心跳喂活零 interrupt 零超时卡/agentToolHeartbeat 经 rootSessionId 重置/活动路径真停摆仍弹卡/send 路径真停摆仍发 interrupt | ✅ 4/4 |

**模拟口径**：严禁真实等待——心跳间隔参数化（spec 50ms）；回避窗直接传参（300ms 缩窗）；首 token 看门狗经 `streamInactivityOverride` 注入 800ms；前端 timer 经 init 脚本把 ≥35s setTimeout 压到 400ms（busy timer 唯一命中域 40s，重连退避 ≤30s 不受影响）。

### 变异验红（每子项至少一条，红后还原、还原后复绿）

| # | 变异方式 | 红证据（原文摘录） | 绿 |
|---|---|---|---|
| M1 后端 | `ToolHeartbeat.span` 改纯 io（去掉发射） | `T1 ... expected ≥2 heartbeats in 120ms at 50ms interval, got 0`；`T3 ... expected ≥1 beat before failure, got 0` | ✅ |
| M1 前端 | 删 main.js 两行 onMessage 注册 | T1/T1b 红：`expect(after.busy).toBe(true)` 失败（心跳段 timer 到点清了 busy）+ `sent.filter(m=>m.type==='interrupt')` 非空（发了 interrupt）；T2/T2b 精准保持绿 | ✅ |
| M2 | `fallback.scala` 400 分支改 `evict = true` | `T1 ... 400 Format must NOT evict the provider (audit #2), got Some(Down(statusCode: 400, response: {...thinking must be passed back...}, ...))`——红点即生产 flap 签名 | ✅ |
| M3 | interface.scala 两超时分支 softAvoid 恢复 markDown | `T1 ... timeout must NOT mark the provider Down (audit #3 慢≠死), got Some(Down(timeout,...))` | ✅ |
| M4 | `effectiveThinking` 直通 `params.thinking` | `T1 ... unconvertible history must strip thinking mode`；`T5 ... outgoing body must NOT carry a thinking field, got: {"thinking":{"budget_tokens":512,"type":"enabled"},...}`——红点即生产 400 形态 | ✅ |
| M5 | StreamEventEncoder 改单时间戳（批量模式签名） | `T2 ... first-token → next-event gap must reflect real arrival times (≥50ms), got 0ms` | ✅ |

### 既有 spec 回归

受影响域既有 spec（StreamWatchdog/SendStreamNoProgress/AllProvidersDownTimeout/HealthMonitor/FallbackRetryPolicy/FallbackSeam/Anthropic 三件套/OpenAiAdapter 两件套/LlmLogWriterPrune/ToolsLogWriter/ToolsLogAgentCore）**108/108 绿**；前端同域 wait-timeout-frozen-timer.spec.mjs **3/3 绿**。

## 四、全量 sbt（worktree 内前台一次跑完）

**Passed: Total 2213, Failed 0, Errors 0, Ignored 7，耗时 510s（8:30）**
（上游 hold 合并基线 2191 + 本批新增后端 22 = 2213 吻合；期间遇一次 sbt 测试迭代编译失败已修，未遇锁等待）

## 五、分支与合并

- 分支 commit：**94e0e7a1**（timeout-evict-fix，20 文件 +1626/−198）
- worktree 二次 merge main：**c107d36c**（拿 proj-create-tool 7426bd8f，零冲突）
- **merge commit：f1e8b5a3**（main，--no-ff，零冲突，未 push 未动 origin）
- 合并后主仓子集复跑：后端五子项 spec+HealthMonitor+FallbackSeam **41/41 绿**；前端 timer 域 tool-heartbeat-timer + wait-timeout-frozen-timer **7/7 绿**

## 六、清理确认

- `git worktree remove .nebflow/worktrees/timeout-evict-fix` ✅（worktree list 已除名）
- `.nebflow/timeout-evict-fix` 软链已删 ✅
- `git branch -d timeout-evict-fix` ✅（曾为 c107d36c，历史经 --no-ff f1e8b5a3 保全）
- 其余在飞 worktree（flowmap-archive-panel/proj-archive-btn 等）为并行任务域，未触碰

## 七、生效说明

**运行时生效需前端产物重建（esbuild bundle）+ 宿主重启——本次严禁任何重启动作，未做。** 当前运行宿主行为不变。生效后预期：前台长工具执行期每 30s 有 toolHeartbeat WS 帧（630s 误杀面消除）；超时类失败只软回避 45s 不驱逐（DOWN 日志量骤降）；400 形状类不产生 DOWN/UP flap；router sse 日志逐事件落盘（首 token 延迟/chunk 间隙可实测）。

## 八、遗留

1. **90s/120s 阈值复核留待逐事件日志有数据后**（本批按任务要求不改阈值，只改落盘粒度）
2. 流间隙超时按 provider 可配（thinking 非流式端点 180s+）、整流 600s 按 hop 数放大——审计建议 #4/#5，未入本批
3. 配额类确定性死亡跳过 freeze-retry 循环（审计建议 #6）未入本批
4. signed thinking 块（真 Anthropic 签名）回传 deepseek 时保留原签名——无生产失败证据 + 既有 spec 锁定该契约，维持现状；若日后发现 deepseek 拒签名字段，可在 effectiveThinking 同点位做签名剥离转换
5. 流间隙超时命中且已输出部分内容时（seam guard 域）整流失败仍不 markDown——该路径本就无害，未动
6. request 行 model="unknown"（流派发时无法预知 resolved model）——viewer 若强依赖 request 行 model 展示需后续把 resolved_model 从 response 行回读

## 九、上游 hold MVP 要点转述（链条可见性）

上游 n-a9c374b6 交付了节点「暂停/人在回路」MVP：NodeLifecycle 增 held 非终态（completeNode 分流：blocked 优先于 hold，held 落库全文不投递不结算），NodeEdit 增 hold/release/note 三参数与八条校验（release 单事务改两节点+note 注入下游 task+fork 传播链），NodeHoldSpec 十用例全绿并以 hold 删除/顺序反转两轮变异验红锁定语义（f6a0b846 → merge a97d69b0）。其 held/sweep/deps 机制全部在 NodeEngine/NodeTools 域，与本批 LLM/前端域零交叠，合并产物经二次 merge 确认无冲突面。
