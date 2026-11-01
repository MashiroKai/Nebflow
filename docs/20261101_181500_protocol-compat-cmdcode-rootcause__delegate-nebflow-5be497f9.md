---
type: design
status: implemented
---

# protocol-compat 批：cmdcode 不可用的根因链 + 双协议归一化层（2026-11-01 作者令）

作者令：为什么 cmdcode 不可用？怀疑是 OpenAI 协议 vs 我们现有的 Anthropic 协议不
兼容导致报错——如果是，要做兼容层。后续两轮补充（17:45 / 17:49 / 17:56 / 17:58）
把口径升级为：引擎解析鲁棒性 + 失败可诊断 + 单 provider 不许打死整批 + 会话自述
路由 + LLM 客户端必须带 User-Agent。

本文件是这一批的**事实与决策存档**：根因链（可复现）、架构盘点、改动清单、验收
证据。它直接改写上一批的结论——[[20261030_cmdcode-provider-removal]]（当时把
cmdcode 判为「key 死、整体摘除」）在本批被证伪。相关代码见 `providers/WireShape.scala`、
`providers/HttpHeaders.scala`、`AnthropicAdapter`、`OpenAiAdapter`。

---

## 1. cmdcode 不可用的根因链（三段，全有证据）

作者的可错假设是"key 死了"与"openai vs anthropic 协议不兼容"。实测**两个都不是**。

### 1.1 第一段（2026-10-28）：请求发错了端点 —— 与协议无关

引擎日志原话（`stand.2026-10-28.0.log`，5 次）：

```
Stream: cmdcode/deepseek/deepseek-v4.1-flash permanent error (Format)
— statusCode: 400, response: {"type":"error","error":{"type":"invalid_request_error",
"message":"Model \"deepseek/deepseek-v4.1-flash\" is not supported on this endpoint.
Use /provider/v1/chat/completions for OpenAI and OSS models."}}
```

上游在**教我们该打哪个端点**：`/provider/v1/chat/completions` 是 OpenAI/OSS 面，
我们当时打到了另一个面（anthropic 形态的 messages 面）。这是**路径/协议面错配**，
不是 key、不是模型名。

### 1.2 第二段（2026-10-29 ～ 10-30）：无 User-Agent 被 Cloudflare 边缘挡

```
Provider cmdcode/deepseek/deepseek-v4.1-flash marked DOWN:
Exception when sending request: POST https://api.commandcode.ai/provider/v1/chat/completions
```

本批独立复现（同 key、同 URL、同 body，只换客户端）：

| 客户端 | User-Agent | 结果 |
|---|---|---|
| Python urllib（默认 UA） | `Python-urllib/3.x` | **HTTP 403 `error code: 1010`**（Cloudflare browser-signature 封禁） |
| curl | `curl/8.x` | **HTTP 200**，正文正常 |

作者 17:56 用 urllib 与 curl 得到同一结论。`1010` 是 Cloudflare 在**边缘**就把请求
挡掉，业务层根本没收到——所以它长得像"鉴权失败"，被读成"厂家 key 失效"，于是有了
配置里的 `disabledNote: "厂家重置 key 后启用"`。**这是误判，key 从未死。**

### 1.3 第三段（2026-11-01）：key 活着，所有引擎线上的形状都 200

本批用 curl 逐字段复刻引擎会发出的每一种形状（只打状态码与错误码，不碰 key）：

| 形状 | 结果 |
|---|---|
| A 最小（model+messages） | 200 |
| B tools | 200 |
| C `reasoning_effort:high` | 200 |
| D `thinking:{type:enabled}` | 200 |
| E `metadata{session_id,agent_id}` | 200 |
| F `stream:true` + `stream_options.include_usage` | 200（SSE 正常，`reasoning_content` 分片） |
| G 工具调用轮 | 200，`finish_reason=tool_calls`，`tool_calls[0].function.name=Bash` |
| H2 多轮回放（不带 reasoning_content） | 200 |
| H3 多轮回放（带 reasoning_content） | 200 |
| I 流式 + tools | 200 |

**结论**：cmdcode 的 key 活、协议面完整可用；历史不可用是「端点面错配」+「无 UA 被
边缘挡」两段运维/客户端问题，**不是"OpenAI 协议与我们的 Anthropic 协议不兼容"**。

### 1.4 但要修的确实存在：openai 面的「哑开关」与「内容丢失」

盘点 openai 面时发现三处引擎缺陷（不是 cmdcode 专属，是所有 openai 协议 provider
都会中的）：

1. `requireThinkingPassback` 在 openai 面**是个哑开关**——`ProviderRegistry
   .createAdapter` 只把它交给 `AnthropicAdapter`，`OpenAiAdapter` 从来没有回传
   `reasoning_content`。cmdcode 的配置写了 `requireThinkingPassback: true`，
   实际从未生效。
2. 一条消息同时带 `tool_result` 与正文时，`toOpenAiMessages` 走 tool 分支**把正文
   整段静默丢弃**。
3. `content` 是块数组（兼容端点常见）、`arguments` 是对象（非规范）时，正文/参数
   被当成空。

---

## 2. 现有 LLM 客户端架构盘点（本批改前的真实状态）

**架构结论：协议适配器层已经存在，不需要"再造一个翻译器"。** 要修的是这层的
鲁棒性与可诊断性。

| 层 | 位置 | 职责 |
|---|---|---|
| 内部统一消息模型 | `shared/protocol.scala` | `Message(role, Left(text) \| Right(List[ContentBlock]))`；`ContentBlock = Text \| Image \| ToolUse \| ToolResult \| Thinking`（**sealed**） |
| 流式块模型 | 同上 | `StreamChunk = TextDelta \| ThinkingDelta \| ThinkingSignature \| ToolCallStart \| ToolArgDelta \| ToolCallChunk \| Done` |
| 适配器接口 | `llm/adapter.scala:95` | `ProviderAdapter[F] { sendMessage; sendMessageStream }`——**按 protocol 收口的唯一缝** |
| anthropic 面 | `providers/AnthropicAdapter.scala` | content block / thinking / tool_use 翻译；`wireThinkingFragment` = 思考 wire 的单点翻译表 |
| openai 面 | `providers/OpenAiAdapter.scala` | messages / tool_calls / SSE delta / reasoning_content 翻译 |
| 选择 | `llm/registry.scala:58` | `provider.protocol match { OpenAI => OpenAiAdapter; Anthropic => AnthropicAdapter }` |
| 失败分类 | `llm/fallback.scala:245` | `classifyError`：400→`Format`/Permanent/evict=false；401/403→Auth；429→RateLimit |
| 兜底编排 | `llm/interface.scala` | 候选链、轮次上限、接缝锁（`lockedRef`：部分内容已发则禁止换腿） |

### 2.1 各环节的完成度（改前）

| 环节 | anthropic 面 | openai 面 | 判定 |
|---|---|---|---|
| 请求构造 | 完整（system 块 + cache_control） | 完整 | 完整 |
| 非流式响应解析 | 按 `type=="text"` **精确筛**，未知块丢正文 | `content` 只认字符串 | **半成品** |
| 流式 delta | 未知 delta/块类型 → `Nil`（静默丢） | `reasoning_content`/`thinking` 已认 | **半成品** |
| tool call | 完整（含参数修复 `ToolInputJson`） | 完整（空名帧防护） | 完整 |
| thinking/reasoning | 完整 + passback 剥离腿 | 流式认，**历史不回传** | **缺桩** |
| 失败诊断 | 只有上游原话，无请求侧形状 | 同 | **缺桩** |
| HTTP 头 / UA | 各 adapter 自己 `header()` | 同 | **缺桩** |

---

## 3. deepseek 的 `Format` 与同根性（作者 17:45 追问的归因）

作者原判断是"响应以 thinking 块开头，引擎解析崩"。**实测不是解析崩，是请求被 400**：

```
Stream: deepseek/deepseek-flash permanent error (Format) — statusCode: 400,
{"error":{"message":"The `content[].thinking` in the thinking mode must be passed back
to the API.","type":"invalid_request_error"}}
```

根因链（本批真端点四格实测，见下表）：

1. `ThinkingFamily.requiresPassback("deepseek", …) = true` ⇒ 适配器开 passback 剥离腿；
2. 历史里存在**不带思考块**的助手消息（跨 provider 回放、压缩丢块、某轮本就没思考）⇒ `effectiveThinking` 返回 `None`；
3. `wireThinkingFragment(None)` 旧实现发 **`Json.obj()` = 省略 thinking 键**；
4. DeepSeek 类端点**缺键 ≠ 关思考，缺键 = 进默认思考模式** ⇒ 要求回传思考块 ⇒ **400**；
5. `classifyError` 把任何 400 归 `Format`/Permanent/不重试 ⇒ 候选判死 ⇒ 候选链全灭 ⇒ **整批阵亡**。

四格实测（`api.deepseek.com/anthropic`，同一历史 = 缺思考块）：

| 发的 thinking | 结果 |
|---|---|
| **省略键** | **400**（正是事故报文，逐字一致） |
| `{type:"disabled"}` | **200** |
| `{type:"enabled"}` | 400 |
| `{type:"enabled"}` + 回传思考块 | 200 |

⇒ 剥离腿必须发**该族可表达的关态**，不能靠省略键赌对方默认值。

**与 cmdcode 的关系**：不同根，但同一处解析器/翻译器的鲁棒性缺陷族——都是「对端能力
目录是开放集，引擎映射表是封闭集，遇到表外形态一律判死」。本批把这条族缺陷一并收口。

---

## 4. 兼容层设计与改动清单

设计原则：**不新建第二套翻译器**（那会立刻变成两份真相）；把已有的两个协议适配器
收口到同一套判据上，新增协议只加一个 adapter + 一处 registry 分支。

### 4.1 三条判据（新文件 `providers/WireShape.scala`，纯函数、零 IO）

1. **不认的块不判死**（`classifyBlock`）：未知块带 `text` → Text；带
   `thinking`/`reasoning_content`/`reasoning` → Thinking；都不带 → `Opaque(type)` 只计数。
   `tool_use`/`image` 永不降级成文本（否则破坏工具配对与图像通道）。
2. **失败必可诊断**（`requestShape` / `responseShape` / `diagnose` / `upstreamExcerpt`）：
   一行说全 `provider`、`model`、我们发的块类型序列、对方响应类型序列、上游原话。
3. **路由自述**（`routeBanner`）：每请求把 `provider=<id> model=<id>` 写进 **dynamic**
   system 段尾部（不碰稳定前缀，cache 不破）。

另加可自愈判据 `isThinkingPassbackRejection(400, body)`：只有"上游明说思考必须回传"
才允许降级重试。

### 4.2 头装配单点（新文件 `providers/HttpHeaders.scala`）

- 引擎缺省必带 `user-agent: stand/<version> (nebflow-llm-client)` + `accept`；
- `ProviderConfig.headers`（新字段）按 provider 追加/覆盖，键大小写不敏感去重；
- 两个 adapter 的**所有**请求（流式/非流式）都经 `HttpHeaders.applyTo`；
- `isEdgeBlock` / `edgeBlockHint`：403/429 + 云厂商边缘签名 ⇒ 诊断串追加
  `EDGE-BLOCK: … this is a REQUEST-HEADER problem, not a dead credential`。

### 4.3 改动清单

| 文件 | 改动 |
|---|---|
| `providers/WireShape.scala` | **新增**：三条判据 + 形状指纹 + 可自愈判据 |
| `providers/HttpHeaders.scala` | **新增**：UA/头装配单点 + 边缘拦截判据 |
| `shared/config.scala` | `ProviderConfig.headers: Option[Map[String,String]]` |
| `providers/AnthropicAdapter.scala` | ①`stripWire` 剥离腿显式关态（**根因修复**）②`warnSync` 让剥离诊断真落盘（旧代码 `logger.warn` 返回 IO 被丢弃，从未打印）③宽容解析（未知 delta/块保留文本，`redacted_thinking` 认签名）④`wireError` 诊断串 ⑤passback 400 就地降级重试 ⑥`providerId`/`providerHeaders` 入参 ⑦头装配收口 |
| `providers/OpenAiAdapter.scala` | ①`requireThinkingPassback` 真正生效（`reasoning_content` 回传）②`tool_result`+正文不丢 ③`content` 数组 / `arguments` 对象降级 ④`reasoning` 变体名 ⑤诊断串 + passback 重试 ⑥路由自述 ⑦头装配收口 |
| `llm/registry.scala` | 两面都拿 `requireThinkingPassback`，都传 `providerId`/`providerHeaders` |
| `shared/fallback.scala` | `FallbackExhaustedError` 每行加**失败族标签**（quota/format/auth/timeout/…）+ 上游摘录 + 族总结判词 |

### 4.4 降级旁路（单 provider 失败不许打死整批）

撞 passback 400 时**就地重试一次**，方向两面相反（各自语义对称）：

- anthropic 面：显式离开思考模式（`thinking:{type:disabled}`）——历史不可转换时唯一可行方向；
- openai 面：把历史里的 `reasoning_content` 补回去——缺它才是根因。

接缝安全论证：4xx 在**响应头**就判定，抛错发生在流的第一个元素，**零块已发出**，所以
重试不可能拼接两家输出（`interface.scala` 的 `lockedRef` 防的是另一个场景）。测试
`AnthropicCompatSpec B3` 钉死"重试前零块"。

---

## 5. 验收证据

### 5.1 单测（`sbt testOnly nebflow.llm.providers.*`）

**159 passed / 0 failed**（终局，含 4 个新增 spec + 3 处口径变更 + `FormatErrorNoEvictSpec`
跨包钉子同步）。本批新增 4 个 spec：

| spec | 钉什么 |
|---|---|
| `WireShapeSpec` | 未知块降级（N1-N5）、形状指纹（S1-S6）、路由自述（R1-R2）、可自愈判据（P1-P3） |
| `HttpHeadersSpec` | UA 必带、provider 覆盖大小写不敏感、边缘拦截人话（H1-H4） |
| `AnthropicCompatSpec` | 剥离腿显式关态（A1-A2）、回传保持（A3）、路由上线（A4）、UA 上线（A5）、降级重试与接缝（B1-B3）、诊断串（B4-B5）、thinking-first/未知块/未知 delta 不崩不丢（C1-C4） |
| `OpenAiNormalizationSpec` | reasoning 回传（O1）、tool_result+正文不丢（O2）、数组 content / 对象 arguments（O3）、路由自述（O4） |

口径变更（同步改红→绿）：
- `AnthropicThinkingWireSpec W8`：剥离腿由"空片段"改为 `{type:disabled}`（新增 W8b 钉 Budget 族仍省略键，防回归）；
- `AnthropicThinkingReplaySpec T5`：E2E 断言由"不得带 thinking 字段"改为"必须带 `{type:disabled}`"；
- `FormatErrorNoEvictSpec T1`（**分块全量回归补出的第 3 处**，见 §8.2）：该 spec 在
  `nebflow.llm` 包（本批初验只扫了 `llm.providers.*`，漏了跨包钉子）——T1 的 mock 正是
  passback 形状 400，钉的是旧契约"Permanent 400 立即换腿（hits=1）"，与作者令 §4.4
  "降级旁路就地重试一次"直接冲突。已同步为新契约：**hits=2 且有界**（重试发生 + 不循环），
  可观测结论不变（b 服务 "BB"、a/m1 不驱逐）。全仓核查：另外 3 处 `hits==1` 断言
  （ScheduleChainSwitchSpec:257 / FallbackSeamSpec:211 / VisionWireCaptureSpec:201）
  用的都是非 passback 失败形状，不受降级旁路影响，保持原断言。

### 5.2 真端点冒烟（`LiveProviderSmokeSpec`，env-gated，凭据只从 env 读、不打印）

**cmdcode（openai 面）4/4 通过**：

```
[live-smoke] L1 provider=cmdcode model=deepseek/deepseek-v4.1-flash reply=pong
[live-smoke] L2 toolCalls=List(Bash(echo nebflow-live-smoke))
[live-smoke] L3 textChars=9 thinkingChars=96 kinds=ThinkingDelta,TextDelta,Done
[live-smoke] L4 replay reply=The command printed `hello`.
```

**deepseek（anthropic 面）4/4 通过**（L4 的历史正是"助手消息不带思考块"，改前必 400）：

```
[live-smoke] L1 provider=deepseek model=deepseek-flash reply=pong
[live-smoke] L2 toolCalls=List(Bash(echo nebflow-live-smoke))
[live-smoke] L3 textChars=9 thinkingChars=101 kinds=ThinkingDelta,ThinkingSignature,TextDelta,Done
[live-smoke] L4 replay reply=It printed `hello`.
```

⇒ **同一批对话在两面上都跑通，文本与工具调用一致**；cmdcode 的 `content` 空、
正文在 reasoning 的形态，L3 的 `thinkingChars=96` 证明 reasoning 被保留（走
`ThinkingDelta`，面板折叠面消费）。

### 5.3 作者已做的配置（本批不碰、只核对）

- `cmdcode` 已启用（`disabled`/`disabledNote` 已由作者移除）；
- `model-chains.json` general 链 fallback 末尾含 `cmdcode/deepseek/deepseek-v4.1-flash`；
- `deepseek`（原 `deepseej`）更名保留、已启用。
- 本批**未改动** `disabled` 任何位；`headers` 字段是新增可选键（缺省 = 零行为变化）。

---

## 6. 已知边界 / 未做

1. **非流式面的 reasoning 文本不落到 `AdapterResponse.reply`**（契约无 thinking 字段）：
   主会话走流式（`ThinkingDelta` 已覆盖）；非流式只服务探针/judge/allocator 等辅助面，
   它们的语义不需要推理正文。若将来需要，须扩 `AdapterResponse` 并同步所有消费方。
2. **`redacted_thinking` 只保留类型与签名位，不伪造正文**（密文不可读，伪造 = 双倍
   计费 + 幻觉污染）。真 anthropic 回传该块需要原样 `data`，属未建模能力，留待需要时加。
3. **"可折叠展示"** 是前端面：本批保证推理内容进 `ThinkingDelta` 通道（面板既有折叠面
   消费），未新增前端组件。
4. `HealthMonitor` 的 `marked DOWN` 行仍只打请求 URL、不打异常 cause 链——第二批修。

---

## 7. 全量回归与 A/B 归因（19:36–19:41 实测存档）

### 7.1 挂死事件与处置（作者裁决：终止并分块重跑）

全量 `sbt test` 在 neblink tunnel 系挂死 ~40 分钟：munit runner 线程 park 在
`PlatformCompat$.awaitResult`，`PortalTunnelBridge.scala:119` 的 `LinkedBlockingQueue.take()`
无超时无人取消。经作者授权终止（jobId 9bf062ac），改分块执行（非 neblink → neblink
非 tunnel → tunnel 组单列最后）。

### 7.2 A/B 归因（stash 往返，同机同条件）

| 套件 | 干净基线（本批改动 stash 后） | 带本批改动（安静环境） | 判定 |
|---|---|---|---|
| `PresetCatalogInToolDocSpec` | **红**（:80 键集，同位置） | **红**（同位置） | 基线既有：Delegate schema 实际键集缺 `mode`/`executor`（external-executors/双模式批的漂移），与本批无关 |
| `PluginRemoveSpec` | **红**（`seed/plugins/nebflow-plugin-creator` resource null → NPE） | **红**（同） | 基线既有：2026-10-31「开发插件统一批」卸除了 nebflow-plugin-creator 包，seed 资源随之消失，spec ⑤ 未同步，与本批无关 |
| `BgTaskReaperSpec` | **绿**（安静） | **绿**（安静） | 全量跑时红 = 负载抖动（waitUntil 时序类），非回归 |

其余 10 个 waitUntil 时序失败（DelegateBgWaitHold / BgWaitCapLedger / DelegateRevival /
EmptyShellNotify / MergeReceiptNotifyChain / OfficialIdentity / RemoteableDeviceParam /
UpdateOrchestrator / MailDelegateContinuation / PresetCatalog 以外）与 BgTaskReaper 同类：
主仓 10-26 记录全绿、本机高负载下复现、安静环境抽检绿——归为负载抖动，非本批回归。

**结论：本批改动面（llm/providers、registry、ProviderConfig.headers、fallback 文案）
零回归；12 个失败套件全部可归因到基线既有问题或负载抖动。**

### 7.3 Chunk A 死跑结算（jobId feb3bb61，21:28 随会话收轮被杀）

后台作业 `feb3bb61`（845 非 neblink 套件）在 19:28:25 启动、21:28:59 被引擎以
"Session reclaimed" 取消（会话强制收轮连带杀死后台作业；**不是测试挂死**）。作业日志
`/Users/kaiyu/.stand/bg-task-logs/feb3bb61.log`（2.7MB）+ `target/test-reports/*.xml`
逐套件结算：

| 段 | 结果 |
|---|---|
| 已跑完 | **720 / 845**（逐套件 XML 结算：**702 绿 / 18 红**） |
| 停摆套件 | `nebflow.service.MemoryWriteGateSpec`（第 721 个）起跑后 ~74 分钟无产出直到被杀——真挂死候选，续跑单列短链重验 |
| 未跑到 | **125** 套 |

18 红清单（XML `failures` 数）：`AdaptiveThrottleSpec`(1/13)、`FormatErrorNoEvictSpec`(1/3)、
`decision.PluginCatalogLayerSpec`(2/4)、`PresetCatalogInToolDocSpec`(1/7，**基线既有 :80**)、
`RemoteableDeviceParamSpec`(1/7)、`MailDelegateContinuationDeliverySpec`(3/5)、
`OfficialIdentitySpec`(5/9)、`PluginRemoveSpec`(1/6，**基线既有 NPE**)、
`hotupdate.UpdateOrchestratorSpec`(10/15)、`hotrestart.HotRestartSpec`(7/25)、
`DelegateBgWaitHoldSpec`(3/4)、`DelegateRevivalSpec`(2/9)、`EmptyShellNotifySpec`(3/3)、
`BgWaitCapLedgerSpec`(1/1)、`InteractionHubSpec`(1/11)、`MergeReceiptNotifyChainSpec`(2/2)、
`PendingAskLedgerSpec`(1/6)、`SubAgentInboxMirrorSpec`(1/9)。

注意：live log 只能看到 `==> X`（91m 红）行，早前的「6,587 绿 0 红」是 grep 图案
错误（`  x ` vs `==> X `）；**套件级真值以 test-reports XML 为准**。

除 2 个基线既有外，16 个红的失败形态全部是 `waitUntil: condition not met (in 10–30s)`
/ `test timed out after 30 seconds` 一类**时序超时**，与 §7.2 首轮全量的失败族同型
（本机高负载：另一 delegate 的 sbt 自 19:34 起同机在跑）。按 §7.2 同一方法（安静环境
隔离重跑）在续跑 C 相归因，不预先判绿。

### 7.4 分相续跑（jobId 8ecce029，21:55 起）

按 §7.1 作者令顺序（非 neblink → neblink 非 tunnel → tunnel 单列最后），五相一链、
每相带看门狗（挂死可杀不阻塞后相）：

| 相 | 内容 | 看门狗 |
|---|---|---|
| A'a | 124 个未跑到的非 neblink 套件 | 75 min |
| A'b | `MemoryWriteGateSpec` 单列（死跑停摆 74min 的套件） | 15 min |
| B1 | neblink 非 tunnel 87 套 | 60 min |
| C | 失败隔离归因：死跑 18 红 ∪ A'/B1 新增红，安静重跑定基线/抖动 | 45 min |
| B2 | tunnel 组 10 套单列最后（预期可能 park，看门狗截断取证） | 30 min |

相日志：`/tmp/chunk_aa.log`、`/tmp/chunk_ab.log`、`/tmp/chunk_b1.log`、`/tmp/chunk_c.log`、
`/tmp/chunk_b2.log`；套件名单 `/tmp/chunk_a2_list.txt` 等。结果结算在 §8（续跑结束后补）。

---

## 8. 分块全量回归终局结算（22:28 链跑完 + 基线对照）

### 8.1 五相执行结果（jobId 8ecce029，21:55–22:28，31m42s）

| 相 | 内容 | 结果 |
|---|---|---|
| A'a | 124 个未跑到的非 neblink 套件 | **全绿**（rc=0，0 红） |
| A'b | `MemoryWriteGateSpec` 单列（死跑停摆 74min） | **5 秒跑绿** ⇒ 死跑停摆是会话收轮环境性的，非套件本身 |
| A'a 补漏 | `FileHistorySpec`（名单 FQCN 错写，真名 `nebflow.shared.FileHistorySpec`） | **5/5 绿** |
| A'a 补漏 | `WatchdogCriteriaReplaySpec` | **语料门自 ignore（0 断言）**= 合法 skip，非失败 |
| B1 | neblink 非 tunnel 87 套 | **86 绿 + 1 红**：`NeblinkPresenceHeartbeatLifecycleSpec` ①（190.9s，计数类断言） |
| C | 失败隔离重跑 19 套 | **45 红**：死跑 18 红中 16 个**逐测试同位复现**，2 个翻绿；B1 的 1 红同位复现 |
| B2 | tunnel 组 10 套单列最后 | **全绿**（rc=0，2m23s，无 park）⇒ 首轮全量的 tunnel 挂死是「942 套同 JVM 数小时后的状态污染」环境性事件，非 tunnel 套件固有 |

### 8.2 归因判定（§7.2 方法：确定性 × 基线对照）

**逐测试同位复现证据**：C 相 45 个红与死跑 46 个红**逐字符同名**
（`perl` 提取 `==> X` 行 diff：C ⊂ 死跑，仅 2 个差异）：

| 归因 | 套件（失败数） | 判据 |
|---|---|---|
| **真抖动（2）** | `AdaptiveThrottleSpec` T9（30s timeout）、`InteractionHubSpec`（1） | 死跑红、安静 C 相绿 |
| **确定性复现（16+1）** | §7.3 的 16 个 waitUntil/计数类 + `NeblinkPresenceHeartbeatLifecycleSpec` | 负载与安静两种条件同测试名复现 ⇒ **非抖动**，基线对照定归属（见 8.3） |
| **本批引入的口径变更（1）** | `FormatErrorNoEvictSpec` T1 | 见 §5.1 口径变更第三条——mock 恒 400 下降级重试使 hits 1→2，spec 同步后 3/3 绿 |

**§7.2 修正**：当时把这 10 个 waitUntil 套件归"负载抖动（安静抽检绿）"是**抽样不足**
——C 相证明其中大部分在安静环境同样红，后经 §8.3 三次实验定案为**同 JVM 跑序污染**
（干净基线同序重跑 44 红，与本批树 45 红的差集只有 1 条本批口径变更）。全量回归的
诚实结论以 §8.3 为准。

### 8.3 干净基线对照（三次实验，定案）

方法：`git clone --no-hardlinks` → `git checkout 7d18dabb0`（本批改动前的 HEAD）到
`/tmp/pc-baseline`，**同一台机、同一批套件、同 JVM 配置**，唯一变量 = 有无本批改动。

| 实验 | 套件顺序 | 结果 |
|---|---|---|
| ① 基线首跑（jobId 0c725095） | HotRestart / UpdateOrchestrator 排**列表最前** | 19 套中 **27 红**；HotRestart **25/25 全绿**、NeblinkPresence **3/3 全绿** |
| ② 基线同序重跑（jobId 7f0aeda7） | **与 C 相逐字相同的 19 套顺序** | **44 红** |
| ③ C 相（本批树，同序） | 同上（含修 spec 前的 T1） | **45 红** |
| ④ **定案跑**（jobId 7ed58d4d） | 本批树，同序，**含已修的 T1** | **44 红 = 与②逐数相等，差集空** |

**③−② 差集 = 恰好 1 条**：

```
nebflow.llm.FormatErrorNoEvictSpec :: T1: 400 (thinking-passback shape) does NOT evict…（旧标题）
```

**④ 收口**：把 T1 的 spec 按新契约改好后，本批树同序重跑落到 **44 红**，与基线同序的
44 红**逐套件失败数一一相等**（HotRestart 7=7、UpdateOrchestrator 10=10、OfficialIdentity 5=5、
DelegateBgWaitHold 3=3、EmptyShellNotify 3=3、MailDelegateContinuationDelivery 3=3、
PluginCatalogLayer 2=2、MergeReceiptNotifyChain 2=2、DelegateRevival 2=2、
NeblinkPresence Heartbeat 1=1、RemoteableDeviceParam 1=1、PresetCatalogInToolDoc 1=1、
PluginRemove 1=1、SubAgentInboxMirror 1=1、PendingAskLedger 1=1、BgWaitCapLedger 1=1），
**双向差集为空**。⇒ 本批改动对全量回归的净影响 = **0**。

#### 8.3.1 结论

1. **本批改动零回归（净影响 0）**。定案跑（④）与干净基线同序（②）的失败集**逐数相等、
   双向差集为空**；唯一行为变更面（`FormatErrorNoEvictSpec T1`，作者令 §4.4 要求）已按
   批内先例同步 spec 并实测 3/3 绿，同步后该条在差集里消失。
2. **跑序（同 JVM 状态污染）是这些红的主因**，不是负载抖动，也不是本批改动。证据：
   同一棵基线树、同一台机——①把 HotRestart 排最前就 25/25 绿，②按 C 相顺序排到 8 个
   后台任务套件之后就 7 红。失败形态直接指向共享静态注册表残留：
   `at map @ nebflow.core.tools.BgTaskRegistry$.waitingTasks (BgTaskRegistry.scala:588)`，
   甚至 trace 里混进 `BgWaitCapLedgerSpec` 的帧。⇒ **这是既有测试隔离缺陷（前序套件
   遗留的 bg task / LLM 在飞登记未被清理），与本批无关，应立独立批修**。
3. `AdaptiveThrottleSpec T9` / `InteractionHubSpec` 三次实验里只在**死跑**（会话收轮前
   高负载窗口）红，两次安静重跑均绿 ⇒ 真抖动，非确定性。
4. `NeblinkPresenceHeartbeatLifecycleSpec ①` 在**批内树与基线树上都红**（1=1），失败形态是
   计数断言 `count(presence-hb-*) ≤ count(connections)`，日志里可见 `[hb-roster]` 抓到了
   **同机其它 delegate worktree 的 java 进程**（`delegate-nebflow-c58a5*`）⇒ 该 spec 的
   会话隔离不严，同机并行 delegate 会污染计数。**同样是既有缺陷**，与本批无关。
5. **`FormatErrorNoEvictSpec T1` 是本批唯一的行为变更面**，且是作者令 §4.4 要求的正向
   变更（降级重试一次）；spec 已同步为 `hits==2 且有界`，本树实测 3/3 绿。

#### 8.3.2 §7.2 结论的修正

§7.2 当时把 10 个 waitUntil 套件判为"负载抖动（安静抽检绿）"——**抽样不足，已证伪**：
它们不是抖动，是**同 JVM 跑序污染**，在干净基线上同样无条件复现（②的 44 红）。
判定方法应改为：**必须在干净基线上用同序重跑做差集**，而不是"安静环境抽检若干个"。
本批因此新增一条回归纪律（见 §9）。

---

## 9. 待办 / 交接（本批不做，需独立批）

| # | 缺陷 | 证据 | 建议 |
|---|---|---|---|
| 1 | **测试隔离缺陷：同 JVM 跨套件状态污染** | 基线同序跑 44 红；HotRestart 排最前 25/25 绿、排到后台任务套件之后 7 红；trace 指向 `BgTaskRegistry.waitingTasks` | 每个 bg/LLM 套件 `afterAll` 强制清空 `BgTaskRegistry` + 在飞登记；或按 suite 分 JVM fork |
| 2 | **NeblinkPresence 会话隔离不严** | `[hb-roster]` 抓到同机其它 delegate worktree 的 java 进程 ⇒ 计数断言受并行会话污染 | roster 计数限定到本 spec 自己的 session/port 前缀 |
| 3 | 全量回归的跑序无稳定性保证 | 本次 942 套同 JVM 数小时后台作业被会话收轮杀死；tunnel 组首轮 park 40min、单列后 2m23s 全绿 | 全量回归默认分块执行（本批 §7.1 已实证），每块独立 JVM |
| 4 | `HealthMonitor` 的 `marked DOWN` 行只打 URL 不打异常 cause 链（§6.4 遗留） | — | 随第 2 批 |

**回归纪律新增（建议进 AGENTS）：** 判定"是否本批回归"必须做**干净基线同序重跑差集**，
不得用"安静环境抽检若干套件变绿"作判据——本批 §7.2 的误判即是反例。
