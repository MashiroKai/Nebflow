# Nebflow「响应超时」机制审计与误判统计

> 阶段文档（只读审计，未改任何代码）。2026-09-03。作者原话：「统计一下响应超时出现的机制，我总感觉它有很多误判」。
>
> 结论先行：**代码共盘点出 20 个把「慢/静默」判成超时或失败的判定点**。近 3 天（UTC 09-01~09-03）日志中**流式三级 watchdog（首 token 90s / 流间隙 120s / 整流 600s）触发次数 = 0**——作者的「误判很多」直觉在流超时层没有日志证据；但**广义的「provider 驱逐」（markDown）共 40 次，其中 25 次（62.5%）属于「provider 本身健康、失败原因是客户端重放形状」的误伤**，且健康探测 0.9~9.6s 就把状态打回 UP，形成 flap 循环。用户可见的「响应超时」文案实际来自**前端 630s 纯静默 busy timer**（与后端停摆标准不一致，存在误杀长前台命令的结构性风险）。

---

## 一、机制总表（20 个判定点）

### A. LLM 请求层（7 个）

| # | 机制 | 默认值 | 代码位置 | 触发后动作 |
|---|------|--------|---------|-----------|
| 1 | 首 token 超时（phase-1 watchdog） | `LlmFirstTokenTimeoutSec=90s` | `shared/Defaults.scala:29`；`llm/interface.scala:164-213`（两阶段管道）、`:546-556`（挂载） | `TimeoutException` → 分类 **Permanent** → `markDown` + 跳到下一 provider（不原地重试） |
| 2 | 流间隙超时（phase-2 watchdog，每个 chunk 重置） | `LlmStreamInactivitySec=120s`（08-26 由 60s 扩，因 kimi k3 60s 停摆事故） | `Defaults.scala:44`；`interface.scala:546-556`（`transientPhase2=true`） | `StreamInactivityTimeout` → 分类 **Transient**，但因 `isTimeout=true` 不原地重试 → **仍 markDown** + 跳下一 provider（`interface.scala:787-808`） |
| 3 | 整流无进展守卫（外层，覆盖 intake→首 chunk 盲区） | `LlmStreamNoProgressTimeoutSec=600s` | `Defaults.scala:61`；`interface.scala:839-844` | 裸 `TimeoutException` → **Permanent**，整个 sendStream 失败（不 markDown，agent 层接手；Permanent → 不重试 fail-fast） |
| 4 | 非流式请求整体超时 | `LlmTimeoutMs=600s` | `Defaults.scala:64`；`llm/fallback.scala:57,196`（`withTimeoutIO`） | `TimeoutException` → Permanent → 下一 provider |
| 5 | HTTP readTimeout（socket 层兜底） | `LlmReadTimeoutSec=600s` | `Defaults.scala:67`；`providers/AnthropicAdapter.scala:344`、`OpenAiAdapter.scala:440` | IOException 类连接错误 → 多为 Transient |
| 6 | all-Down 等待门超时 | `ProbeIntervalSec=120s` | `llm/HealthMonitor.scala:26`；`interface.scala:459-464` | `AllProvidersDownTimeout` → **Transient**（刻意非 TimeoutException 类型，保住 agent 级重试） |
| 7 | 健康探测超时 | `ProbeTimeoutSec=30s`（注释自认「thinking models are slow」仍只给 30s） | `HealthMonitor.scala:29,235`；探测请求 = "hi" + maxTokens=4096 + 无历史 | 探测失败 → 保持 Down（仅 debug 日志）；成功 → markUp 唤醒等待者 |

### B. Provider 健康层（2 个）

| # | 机制 | 默认值 | 代码位置 | 触发后动作 |
|---|------|--------|---------|-----------|
| 8 | Timeout/Permanent → 无条件 markDown（已知刻意设计，Rust 对齐） | — | `interface.scala:760-769`（Permanent 分支）、`:787-808`（Transient-but-timeout 分支，注释明说 always markDown） | provider 移出候选链；恢复仅靠 #7 探测成功（后台 120s 周期 or all-Down 时立即并行探测） |
| 9 | markDown 分类源头 | — | `fallback.scala:81-158` `classifyError`：`TimeoutException`→Permanent；**`message 含 "timeout"` 字样也→Permanent**；400/401/404/EmptyStream→Permanent；429/529/连接重置→Transient | 误伤面：Format(400) 类错误（如 deepseek thinking 回传校验）也走 markDown |

### C. 监管层（6 个）

| # | 机制 | 默认值 | 代码位置 | 触发后动作 |
|---|------|--------|---------|-----------|
| 10 | TaskStuckWatcher（Processing 态 10min 零活动；idle 永不判） | `StuckThresholdMs=10min`，30s 扫描 | `Defaults.scala:159`；`processor/TaskStuckWatcher.scala` | 子 agent：Stop×2 无效 → 硬取消在飞 LLM fiber（`StuckAbort`→Fatal 不重发）；×4 → supervisor Cancelled 释放 barrier。Project flow 会话：第 1 次即硬取消。Team/Root：仅广播 taskStuck |
| 11 | Bash 前台 no-progress ceiling | 10min 零输出 **且** 零 CPU（<10ms/窗口）；`sleep N` 豁免 | `tools/shell.scala:368-390` | killProcessTree + 报错。前台无命令级超时（#26 裁定） |
| 12 | Bash 显式后台硬超时 + 停滞窗口 | `BashBackgroundHardTimeoutMs=30min` + `BashStuckWindowSec=120s`（双条件：零输出+零 CPU） | `Defaults.scala:114,117`；`shell.scala:127-138,795-849` | 30min 后进入观察，连续停滞 120s 才杀 |
| 13 | 后台任务 idle 自动取消 | `BgIdleTimeoutSec=300s`（无输出即杀，无 CPU 条件） | `Defaults.scala:98`；`shell.scala:765-838` | kill + timeout 告警通知 agent |
| 14 | RemoteExecutor | 同步 fallback 120s；前台远程网络兜底 3600s；**30s 活动心跳** touch lastActivityMs（防 #10 误判） | `tools/RemoteExecutor.scala:32,35,142-145` | 网络层超时返回错误 |
| 15 | **前端 busy timer（用户可见「响应超时」的真正来源）** | `StreamTimeoutSec=600s` + 前端 +30s buffer = **630s 纯静默** | `WebSocketRoutes.scala:517,812`（下发 streamTimeoutMs）；`web/js/input.js:750-770`、`main.js:333-346`（活动重置）、`chat.js:1329`（renderTimeoutNotice = `chat.timeout`「响应超时」+ 重试按钮） | 显示「响应超时」卡片 + **发送 WS interrupt 杀掉后端正在跑的 turn**。重置事件 = 各类 delta/tool 事件；**前台长命令执行期间（toolStart→toolEnd 之间）零事件** |

### D. 其他等待类（5 个）

| # | 机制 | 默认值 | 代码位置 | 触发后动作 |
|---|------|--------|---------|-----------|
| 16 | 权限确认等待 | `PermissionTimeout=5min` | `agent/AgentCore.scala:29` | 自动拒绝（InteractionHub 注释：ghost hub 场景 5min 超时拒绝） |
| 17 | AskUser 等待 | **无超时**（`timeout=None`） | `tools/AskUserQuestionTool.scala:196-200` | 永久等待用户 |
| 18 | REST/headless turn 整体等待 | 默认 1800s（可配 1~7200s） | `gateway/RestApiRoutes.scala:157-166`；`TurnEndpoint.scala:104-122` | 504 + status=timeout |
| 19 | MCP 调用 | connect 5s / tools/list 30s / tool call 120s / close 3s | `core/mcp/McpManager.scala:27,40`；`McpClient.scala:50,82` | 失败/告警 |
| 20 | 错误冻结升级窗口 / supervisor 重启 | `ErrorEscalateAfterMs=10min`；BackoffSupervisor 5→60s 退避、maxRestarts=2/5min | `Defaults.scala:185`；`agent/BackoffSupervisor.scala:70-73` | 升级父干预 / 放弃重启 |

Flow 节点/委托 barrier：**刻意无 wall-clock 超时**（`entity/FlowDagExecutor.scala:1077-1089`，5min 节点超时曾试过并因误杀 8min 编译被移除 09f4be58）；phantom barrier 由 #10 的 Cancelled 兜底释放。

**nebflow.json 可配超时字段**（`llm/config.scala`）：`llm.streamTimeouts.{firstTokenSec, inactivitySec, noProgressSec}`、`stuckThresholdMs`、`bashBackgroundHardTimeoutMs`、`bashStuckWindowSec`、`bashHealthCheckIntervalSec`。本机配置仅 `107.queueTimeoutMs=120000` 一处——**注意 `queueTimeoutMs`/`maxConcurrency`/`rpm` 均不在 `ProviderConfig` schema 内（circe 静默忽略），属死配置**。

---

## 二、误判面分析（机制 × 场景 × 证据）

### 2.1 首 token 90s 对 thinking 模型是否够——结构性风险存在，本窗口未触发

- watchdog 计数的是**任何 chunk（含 thinking delta）**（`interface.scala:187` evalTap 全量元素）。GLM/kimi/deepseek 的 Anthropic 兼容端点均流式输出 reasoning → 实际首事件很快（SSE 日志可见 `delta_reasoning` 从流开始即有）。
- 真正暴露的形状：**不流式 reasoning 的端点**（reasoning 全程静默、首个 SSE 事件 = 推理结束）× 长上下文。Defaults 注释自认「GLM-5.2 reasoning 使首内容 token 超过 30s」而给 3 倍余量到 90s；250k 上下文 + 深 reasoning 的组合可能超 90s。**探测路径只给 30s**（`HealthMonitor.scala:28` 注释「thinking models are slow」），探测比生产更容易假失败。
- 日志佐证：近 3 天 **0 次触发**（无 `no response within` 事件）。误判率无法统计，风险停留在结构层面。
- **附带发现**：`Fallback.classifyError` 对 `message.contains("timeout")` 的非类型化异常也判 Permanent（`fallback.scala:141-142`）——第三方 SDK 文案里带 timeout 字样的瞬时错误会被当成 provider 死亡。

### 2.2 流间隙 120s vs 长思考停顿

- 已修过一轮：60→120s（kimi k3 60s 停摆事故，`Defaults.scala:38-43`）。>120s 的合法静默仍会被掐：provider 内部批量缓冲、网关聚合、超长工具参数生成都可能产生。
- **结构性问题：掐断后即使分类是 Transient，也走「跳到下一 provider + markDown」**（`interface.scala:771` `retriesLeft>0 && !isTimeout` 才原地重试；timeout 恒假）。「上游抖动」被当成「provider 死亡」驱逐出链。
- 部分内容已输出时，seam guard（`interface.scala:719-731`）禁止切换 provider → 整流直接失败 → agent 层 30s 退避重发全量上下文（重试放大，见 2.6）。
- 日志佐证：0 次触发。

### 2.3 「慢 ≠ 死」：Timeout→Permanent 无条件 markDown 的误伤面（本次审计的核心实锤）

机制：任何一次 90s/120s 级超时 → markDown → 该 provider 从**所有**并发请求的候选链中被剔除，直到探测成功（后台周期 120s；all-Down 时立即）。三类误伤：

1. **probe 形状 ≠ 真实请求形状**：probe 是 "hi"+maxTokens=4096+空历史。真实失败若是「上下文过长 / thinking 回传缺失 / 特定工具 schema」类，probe 永远成功 → 状态在 DOWN/UP 间打摆（flap），每次真实请求仍要付一次失败尝试 + fallback。**日志实锤：deepseek 25 次 DOWN 全部在 1s 内被 probe 打回 UP（p50=0.9s）**。
2. **Format(400) 类也走 markDown**（`fallback.scala:102` 400→Permanent）：一次请求形状错误 = 全链路驱逐。
3. **限流类（429/529）是 Transient 但重试 1 次耗尽后同样 markDown**（`interface.scala:787-808`）：zhipu 1302 限流抖动期被反复驱逐（11 次，p50 9.6s 恢复）。

**日志佐证（BJ 2026-09-03 00:59:32–01:06:09 flap 风暴）**：
```
00:59:32 zhipu/GLM-5.3-Flash DOWN: rate_limit_error 1302（限流）
00:59:34 deepseek/deepseek-v4-flash DOWN: "The content[].thinking in the thinking
         mode must be passed back to the API"（400 invalid_request）
00:59:34 deepseek UP（probe 0.6s）→ 01:00:06 DOWN → 01:00:08 UP → …35 次/7 分钟
```
根因链：zhipu 限流 → 流量 fallback 到 deepseek → **deepseek Anthropic 模式要求 assistant 历史回传 thinking 块（`registry.scala:38` 默认开启），跨 provider 会话历史缺块 → 400** → markDown → probe（空历史，无此约束）秒回 UP → 下一个 fallback 请求再 400 → flap。**这 25 次驱逐里 provider 本身完全健康，属于机制误伤；且 deepseek 是 general 链最后一道防线，等于防线空转。**

### 2.4 markDown 后的恢复与流量迁移（实测分布）

| provider | DOWN 次数 | 恢复 p50 | 恢复 p90 | 恢复 max | 未恢复（审计截止） |
|---|---|---|---|---|---|
| deepseek/deepseek-v4-flash | 25 | **0.9s** | 3.9s | 67.5s | 0 |
| zhipu/GLM-5.3-Flash | 12 | **9.6s** | 19.9s | 25.9s | 0 |
| kimi/k3-256k、kimi/kimi-k3、qwen/qwen3.8-max | 3 | — | — | — | **3（每周配额耗尽，probe 必然失败，直至配额重置）** |

- flap 集群（间隔<10min 聚类）：BJ 09-03 00:59–01:06（**35 次**）、02:32（kimi+qwen 配额）、05:34（kimi-k3 配额）、11:38（zhipu exhausted→18s 恢复）。
- 流量迁移实测（router summary）：deepseek 日请求量 6457（09-01）→ 1822（09-02）→ **5（09-03）**；zhipu 0 → 5513 → 2687。配额死亡的 qwen/kimi 从未出现在请求日志（health filter 生效，无浪费请求）。
- all-Down 门超时 2 次（BJ 05:36、05:40，kimi 配额死亡会话）：等 120s → `AllProvidersDownTimeout` → error-freeze 120s → resume 再失败 → 再冻结。**对每周配额这种「确定死亡」，冻结-重试循环每 ~4min 空转一轮**（升级链同 reason ≥3 次会升级父干预，但循环不终止）。

### 2.5 前端 630s busy timer——「响应超时」文案的真正来源，与后端标准不一致

- 后端前台 Bash 停摆标准：10min 零输出**且零 CPU**（有 CPU 就不杀，编译类任务安全）。
- 前端标准：630s **纯静默**（只看 WS 事件流，不感知后端 CPU/进程）。前台长命令执行期间 toolStart→toolEnd 之间零事件 → 用户看到「响应超时」卡片，**且前端主动发 interrupt 杀掉后端正在正常工作的 turn**（`input.js:763` `sendWs({type:'interrupt'})`）。
- 实测：359 个可配对 turn 中 **56 个（15.6%）超过 630s**（p90=924s、p95=1926s、max=4421s）。其中多数是 subagent/flow turn（前端 spinner 未 arm，不触发）；但 **root/team 用户可见会话一旦出现 >10.5min 的静默工具段就会误杀**。本窗口日志未见 interrupt 命中记录（WS interrupt 不落日志，无法精确统计），max=4421s 的 turn 存活说明至少 subagent 路径未被打断。
- 后端向 WS 发 `type=timeout` 事件的通道在聊天主链路**不存在**（仅 REST TurnEndpoint 返回 504 timeout），前端 `onMessage('timeout')` 实际是死代码——「响应超时」100% 来自前端自己的 timer。

### 2.6 重试放大的 token 成本面

- 超时不原地重试（跳 provider）→ 每跳全量上下文重发；general 链 3 hop，叠加 agent 级 llm-fail 重试（预算 `OverloadRetryMax=1`/turn + `MaxTurnLlmCalls=4` 封顶）与 error-freeze 自动续跑 → 单次卡死回合最多 ~5 次全量重发。250k 上下文场景即 ~1.25M 输入 token/回合（96% cache read 可对冲，但 DOWN 驱逐后换 provider 会破 cache）。
- `TurnBudgetExceeded`（Permanent）与 seam guard（部分内容后禁 fallback）是既有止损，设计合理。

### 2.7 TaskStuckWatcher 的误判边界

- 防误杀设计到位：idle 永不判；重试链每次动作 touch 活动戳（10min ≫ 重试链上限）；Bash 前台有活动桥接（#319）；远程执行有 30s 心跳。
- 残余盲区：**执行中且不产出活动戳的长工具**（本地 WebFetch 120s/Curl 120s/MCP 120s 均短于 10min，实际风险小；远程 BgTimeout 3600s 场景靠心跳补）。单工具 >10min 且无桥接的形状目前只有「本地前台命令有 CPU」已被 #319 覆盖。
- 实测：root agent（Nebula 自身）6 条 stuck notice（601→751s，BJ 09-03 12:01–12:03）——正常长 turn 逼近阈值时每 30s 刷一条广播，**只广播不动作**，无实际伤害但有噪音。

---

## 三、实测统计（近 3 天：UTC 2026-09-01 ~ 09-03，BJ 09-01 08:00 → 09-03 18:13）

**日志口径说明**：router 日志（`~/.nebflow/logs/router/*.jsonl`）由 `LlmLogWriter` 批量缓冲落盘，request/response/sse 时间戳**无真实时延语义**（实测 request→response 全部 <1.2s 而 output_tokens 高达 2 万）——首 token 延迟、chunk 间隙无法从现有日志实测。超时判定事件全部落主日志 `nebflow.log`（北京时区）。本表主日志窗口 = BJ 08-31 00:00 → 09-03 18:13（合并 5 个文件）。

| 指标 | 数值 |
|---|---|
| 流式 watchdog 触发（首token 90s / 间隙 120s / 整流 600s） | **0 / 0 / 0**（含 08-27~29 老日志也是 0） |
| markDown 事件总数（同窗口） | **40**（另有 4 条为我审计命令回显噪音，已剔除） |
| markDown 按原因 | deepseek thinking-passback 400 ×**25**；zhipu 限流 1302 ×**11**；配额/权限（qwen1+kimi2）×3；zhipu provider exhausted ×1 |
| markDown 按 BJ 小时 | 09-03 01 时 **33** 次（flap 风暴）；00 时 2、02 时 2、05 时 1、11 时 2 |
| DOWN→UP 恢复 | deepseek p50 **0.9s**；zhipu p50 **9.6s** max 25.9s；配额类 3 个未恢复 |
| flap 风暴 | BJ 09-03 00:59:32–01:06:09，7 分钟 35 次 markDown |
| all-Down 门 120s 超时 | 2 次（BJ 05:36、05:40）→ error-freeze ×2（resumeIn=120s），freeze-retry 循环至配额外因解除 |
| TaskStuckWatcher | root notice ×6（601–751s idle，仅广播）；子 agent 硬取消/Cancelled **0** 次 |
| 前端 630s 超时卡片/interrupt | 无法从后端日志统计（WS interrupt 不落日志）；同窗口 >630s 的 turn 有 56/359，均正常完成（未被 630s 掐断的至少含全部 subagent turn） |
| router 请求量 | 09-01: 10715（deepseek 6457）；09-02: 10575（zhipu 5513）；09-03: 3935（zhipu 2687）——qwen/kimi/107 全窗口 0 请求（DOWN 过滤生效） |
| permission 5min 超时 / AskUser 超时 | 0 次 / 无超时设计 |

**疑似误判比例（抽样口径）**：
- 流超时类：0 触发 → 无样本，作者对流 watchdog 的怀疑在本窗口**不成立**（它们足够保守，宁可不动）。
- markDown 类：**25/40 = 62.5%** 属「provider 健康、失败=客户端 thinking 重放形状」的结构性误伤（flap 佐证：p50 0.9s 即恢复）；zhipu 限流 11 次属真实限流但「驱逐全链路」的处置偏重（每次仅 9.6s 就恢复）。
- 抽样 10 条超时结局追踪的替代口径：因超时事件为 0，改为追踪全部 4 个 flap 集群 + 2 次 all-down：结局均为「fallback 成功/冻结后自愈」，无 turn 级永久失败、无用户可见 turn 丢失。

---

## 四、修复建议（按预期消除误判量排序；只建议，未改码）

1. **probe 形状对齐失败原因**（预期消除 ~62.5% 的 markDown 误伤，即 deepseek 400 类 flap 全部）：markDown 时记录失败请求指纹（长度/是否含图/历史 provider 来源）；probe 时若指纹表明是形状类失败，直接以「非健康问题」处理——400 Format/重放类不 markDown，只降级本次请求（改走下一 provider 或就地报错）。改动点：`fallback.scala classifyError`（400 区分 400-recoverable）+ `interface.scala:760-769`。
2. **Timeout 类 markDown 降级为「软下线」**（消除「慢≠死」误伤）：首 token/流间隙超时只跳过本次请求 + 30~60s 软回避窗口，不进 health states；markDown 保留给 Auth/404/配额/EmptyStream 等「确证死亡」。改动点：`interface.scala:787-808` 增加 `classification == Timeout` 分支绕过 `healthMonitor.markDown`。
3. **前端 busy timer 感知工具执行**（消除「响应超时」误杀长前台命令）：后端在长工具执行期每 30s 发 toolHeartbeat WS 事件（RemoteExecutor 已有同款心跳先例），前端 `resetStreamTimeout` 增加该事件类型；或前端在收到 toolStart 后暂停 timer 直到 toolEnd。改动点：`AgentCore`/`BashTool` 心跳广播 + `web/js/main.js:2042-2061` 重置表。
4. 流间隙超时命中时不 markDown（跳过本次即可），并按 provider 可配（thinking 非流式端点给 180s+）；首 token 超时按 provider/model 配置化（`llm.streamTimeouts` 现为全局值，deepseek-reasoner 类需独立余量）。预期：进一步压缩 2.2 节结构性风险面。
5. 整流 600s 守卫按候选链 hop 数放大（`600s → N×150s + buffer`），避免 4+ hop 链的合法沉默（60s overload backoff + 90s 首 token/hop）撞线误杀整流。
6. 对「配额类确定性死亡」（permission_error/quota 文案）跳过 freeze-retry 循环，改为长间隔（如 30min）探测或直接终态 + 用户卡片，消除 all-down 门每 ~4min 空转一轮。
7. 清理死配置：`nebflow.json` 的 `queueTimeoutMs`/`maxConcurrency`/`rpm` 不在 schema 内被静默忽略（`llm/config.scala:37-48`），要么接线要么文档声明，避免「配置了却没生效」的错觉。
8. 观测性补齐（本次审计的最大障碍）：router SSE 日志改逐事件实时落盘（当前批量缓冲，时间戳失真，无法实测首 token/chunk 间隙）；WS interrupt 落一条日志。有这两个数据源，首 token 90s 是否够、前端 630s 误杀率才能定量回答。

---

## 附：关键文件索引

| 主题 | 文件:行 |
|---|---|
| 两阶段 watchdog 管道 | `src/main/scala/nebflow/llm/interface.scala:164-213` |
| per-provider 挂载 + transientPhase2 | `interface.scala:546-556` |
| 整流 600s 守卫 | `interface.scala:823-844` |
| Timeout→markDown（无条件） | `interface.scala:760-769, 787-808` |
| seam guard（部分内容禁切换） | `interface.scala:701-731` |
| 错误分类（Permanent/Transient 表） | `src/main/scala/nebflow/llm/fallback.scala:81-158` |
| all-Down 门 + AllProvidersDownTimeout | `interface.scala:431-485`；`fallback.scala:20-32` |
| 健康探测（30s/120s/形状） | `src/main/scala/nebflow/llm/HealthMonitor.scala:24-37, 219-243` |
| agent 级重试/冻结（预算 4/退避） | `src/main/scala/nebflow/agent/AgentActor.scala:108-171, 1527-1660`；`AgentCore.scala:727-738` |
| TaskStuckWatcher | `src/main/scala/nebflow/core/processor/TaskStuckWatcher.scala` |
| Bash 三层防护 | `src/main/scala/nebflow/core/tools/shell.scala:362-390, 765-849` |
| 前端 630s busy timer | `src/main/resources/web/js/input.js:750-770`；`main.js:290-346, 2042-2061`；`chat.js:1329-1360` |
| 默认值集中地 | `src/main/scala/nebflow/shared/Defaults.scala` |
| 流超时可配入口 | `src/main/scala/nebflow/llm/config.scala:119-130`；`gateway/GatewayMain.scala:261` |
