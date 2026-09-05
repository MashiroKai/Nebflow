# 工具执行结构化 JSONL 日志（方案 B）实施报告

**日期**: 2026-09-03 · **执行**: Coder（tools-log worktree 节点）
**依据**: `~/.nebflow/docs/Nebflow/20260903_tool-failure-logging-audit.md` §5 方案 B（方案 A 并入 B 同点改造）
**范围**: `/Users/dev/Claude code/Nebflow` 主仓，tools-log worktree 施工后合并回本地 main（**未 push 远程**）

---

## 一、背景

作者需要分析工具失败原因并改进。现状：`AgentCore.executeToolInner` 埋点只有 nebflow.log 文本行——错误截断 100 字、无关联 ID、无法统计（审计 §4 七项盲区）。本批实施方案 B：新建 ToolsLogWriter 结构化 JSONL 落盘 + requestId 关联 + 全量 errorText。方案 C（聚合 + 失败率面板）明确不在本批范围，未实施、前端 web/ 零改动。

## 二、盘点结论（第零步，带代码行证据）

### 2.1 埋点唯一性确认

- 唯一 choke point 确认：`AgentCore.executeTool`（:1318-1333，kimi echo 前置 + malformed JSON 检测）→ `executeToolInner`（:1335-1422），核心埋点 flatTap :1415-1420（WARN 失败 100 字截断 / INFO 成功）。`handlers.scala:24` 注释明确 executeTool 已统一收敛进 AgentCore，无第二执行路径。
- **§2.1 九类失败路径过闸核查**（以符号定位，行号有漂移）：
  - **经 flatTap（已覆盖）**: Hook 阻断（:1349-1352）、远程执行失败（:1369-1375）、Left(ToolError)（:1385-1392）、抛异常（:1410-1412）。
  - **绕过 flatTap（本次收敛，逐条）**:
    1. 工具不存在（executeToolInner `case None`，原 :1422）——原先直接 return，无文本行无埋点 → **补结构化写入**（文本行保持零改动仍不新增）；
    2. 参数 JSON 畸形（executeTool :1330-1331）→ 补；
    3. 会话权限策略 Deny（:972-974）→ 补；
    4. 权限请求 pending（:1183）→ 补；
    5. 用户拒绝（:1232）→ 补；
    6. 权限超时（:1244-1249）→ 补；
    7. dropped calls 工具不在白名单（:1007-1009）→ 补（`val` 改 `traverse` generator）；
    8. attempt 兜底（:979-983 `.map` 改 `.flatMap`）→ 补；
    9. kimi 原生搜索 echo（:961-967，成功路径绕过）→ 补成功行。
  - **UserAbort（:1411）维持不记**：用户主动中断非工具失败，重抛语义保留（有意排除，非遗漏）。
- 所有收敛点共用 AgentCore 私有 helper `logToolStructured(call, ctx, result, elapsedMs)`，一处定义八处调用（+flatTap 主点）。

### 2.2 LlmLogWriter 镜像点（ToolsLogWriter 照抄的模式）

`core/LlmLogWriter.scala`：object 单例 / `enabled` AtomicBoolean 开关 / 硬编码 `~/.nebflow/logs/<dir>` 目录 / `writeLock.synchronized` 逐行 `Files.write(CREATE+APPEND)` / `lastPruneDate` 双检每日一次 prune / `scanFullLogsForRefs` 日期前缀删除扫描（package-visible 供测试）。**差异**：ToolsLogWriter 按任务要求增加异步有界队列（LlmLogWriter 为同步阻塞写）。

### 2.3 ToolContext requestId：新增字段

`core/tools/types.scala` ToolContext 原无 requestId 可复用（既有 sessionId 是会话 id 非请求 id）→ **新增 `requestId: Option[String] = None`**（带默认值，5 个既有构造点零改动——全部具名参数）。sessionId 复用既有字段（`state.sessionId` 真实会话 id，AgentCore :917 注入）。

### 2.4 sessionId 与 kind 取值来源

- **sessionId**: `ToolContext.sessionId = state.sessionId`（AgentCore :917）——真实会话 id（非 sessionName）。sessionName 另有字段，工具行不采用（重名不可区分，审计 §1.3）。
- **kind**: `SharedResources.agentRegistry: Ref[IO, Map[String, AgentRecord]]`（SharedResources.scala:67），`AgentRecord.kind: AgentKind`（protocol.scala:252 enum Root/Team/Flow/Delegate/Ephemeral/Plan/SubTask）。logToolStructured 经 `ctx.sharedResources.agentRegistry.get` 按 ctx.sessionId 运行时查（best-effort；非注册上下文/spec harness → null，key 必在）。

### 2.5 requestId 生成与同源链路

审计要求「与 LlmLogWriter 写 router JSONL 同源 id」。原实现：id 在 `LlmLogWriter.log` 内部生成，调用侧不可见 → 改造：

```
pipeLlmCall（AgentCore :453 区域）生成 llmRequestId = UUID
  ├─→ LlmLogWriter.log(..., requestId = Some(id))   # router JSONL request_id 同源（log 新增可选参，缺省自生成=旧行为）
  └─→ cr.copy(requestId = Some(id))                  # ConsumeResult 新增尾字段（唯一构造点 aggregateChunks，零破坏）
        → LlmComplete(r) → pipeToolExecutions(result.requestId)
            → toolCtx = ToolContext(..., requestId = result.requestId)   # AgentCore :911 构造点
                → logToolStructured → tools JSONL requestId
```

Retry 重跑同一 ConsumeResult → id 不变（同一 LLM 响应，语义正确）。非 LLM 触发（REST 直调 / 权限阶段失败 / dropped）→ requestId=null、key 必在。

## 三、改动清单（文件/函数级）

| 文件 | 改动 |
|---|---|
| `core/ToolsLogWriter.scala` | **新文件**（221 行）：object 单例、log()、异步队列+后台 fiber、flushSync、appendJsonl、maybePrune/pruneOldLogs、测试钩子 |
| `agent/AgentCore.scala` | ① pipeLlmCall：生成 llmRequestId + 传 LlmLogWriter + 附入 ConsumeResult；② pipeToolExecutions toolCtx 注入 requestId；③ 新增 logToolStructured helper；④ flatTap 追加结构化写入（文本行零改动）；⑤-⑫ 八处绕过路径收敛（见 2.1） |
| `core/LlmLogWriter.scala` | log() 新增可选参 `requestId: Option[String] = None`（内部 reqId 合并）；logDir 改 AtomicReference 可覆写 + `setLogDirForTest/resetLogDirForTest`（private[nebflow]）；`retentionDays` private → **private[core] 共享常量** |
| `agent/protocol.scala` | ConsumeResult 新增尾字段 `requestId: Option[String] = None` |
| `core/tools/types.scala` | ToolContext 新增 `requestId: Option[String] = None` |
| `test/core/ToolsLogWriterSpec.scala` | **新 spec**（6 用例：①②③④） |
| `test/agent/ToolsLogAgentCoreSpec.scala` | **新 spec**（5 用例：⑤⑥ + 真链路） |

## 四、JSONL 字段表

落盘 `~/.nebflow/logs/tools/<yyyy-MM-dd>.jsonl`，每工具执行一行，固定 11 key 每行必在：

| key | 类型 | 说明 |
|---|---|---|
| ts | string | ISO8601 固定毫秒 UTC（`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`），可排序 |
| tool | string | 纯工具名（不含 summarize 参数） |
| agent | string\|null | agent 名（ctx.agentDef.name；无则 null） |
| sessionId | string\|null | 真实会话 id（非 sessionName） |
| kind | string\|null | 会话 kind（agentRegistry 运行时查，AgentKind 名） |
| isError | boolean | 失败=true |
| elapsedMs | long | 执行耗时（仅 flatTap 主点计时；绕过路径即时返回=0） |
| errorText | string | **失败=result.content 全量不截断；成功=空串** |
| inputSummary | string | 与文本行同源=tool.summarize(input)；无 tool 实例时退化 summarizeToolCall(call) |
| resultChars | int | 结果内容字符数（成功行 >0） |
| requestId | string\|null | 当轮 LLM 请求 id，与 router JSONL 同源；非 LLM 轮=null |

**样例（真实产出原文）**——成功行：

```json
{"ts":"2026-09-03T03:52:25.584Z","tool":"Read","agent":"demo-agent","sessionId":"sess-a","kind":"Root","isError":false,"elapsedMs":12,"errorText":"","inputSummary":"Read(demo-arg)","resultChars":4310,"requestId":"req-demo-align-7f3a"}
```

失败行（errorText 全量，WebFetch 连接拒绝长错误 720 字完整保留）：

```json
{"ts":"2026-09-03T03:52:25.601Z","tool":"WebFetch","agent":"demo-agent","sessionId":"sess-a","kind":"Root","isError":true,"elapsedMs":5010,"errorText":"ConnectException: connection refused to 10.0.0.7:9200 — detail detail ...（720 字全量）","inputSummary":"WebFetch(demo-arg)","resultChars":720,"requestId":"req-demo-align-7f3a"}
```

## 五、异步与保留设计

- **异步不阻塞**：`Queue.bounded[IO, Json](4096)` + 后台 fiber（`workerLoop.start` 于首个 log() 懒启动一次，global runtime）。log() 仅做 Ref 读 + registry 查 + `tryOffer`（非阻塞）；**队列满丢弃 + WARN**（nebflow.tools.logger），工具主路径绝不等待。取队→写盘全程 `IO.blocking`，appendJsonl `writeLock.synchronized` 多 agent 安全。
- **flushSync()**：排空队列 + writeLock 等待在途写 + pendingWrites 计数器闭环等待（杜绝 take→获锁间隙的竞态，spec 稳定三连跑验证）。JVM shutdown hook 调用做关闭时 best-effort flush。
- **保留策略**：`retentionDays = LlmLogWriter.retentionDays`（共享常量 = router 现行默认 **3 天**）；maybePrune 每日一次（lastPruneDate 双检，镜像 LlmLogWriter）；删除复用 `LlmLogWriter.scanFullLogsForRefs` 的日期前缀删除逻辑（tools 文件无对象 refs，ref 收集自然不触发）。
- **测试钩子**（private[nebflow]）：setDirForTest / setClockForTest / setWriteDelayMsForTest / flushSync；LlmLogWriter 同样加 setLogDirForTest（供对齐证据测试，不碰真实日志目录）。

## 六、spec 逐条结果（11 用例全绿，稳定三连跑）

| # | 要求 | 用例 | 结果 |
|---|---|---|---|
| ① | 写入格式字段齐全类型正确 | format: 11 key 全在无多余（keys 集合相等断言）；成功行 isError=false/errorText=""/resultChars=345>0；失败行 2500 字 errorText **长度相等+逐字节相等**；ts 正则断言固定毫秒；requestId=null 时 key 必在 | ✅ 2 用例 |
| ② | 按天滚动 | 注入时钟 2030-01-01T23:30 → +26h 跨日：两个日期文件各 1 行，day-1 文件不被污染，ts 跟随注入时钟 | ✅ |
| ③ | 保留 prune | retentionDays == LlmLogWriter.retentionDays == 3（共享常量断言）；T-9d 文件删除、T-2d 保留、当日保留 | ✅ |
| ④ | 异步 | writeDelay=500ms 注入：log() 返回 <250ms 且行未落盘（入队不阻塞），5s 内行落盘（后台完成）；溢出测试：Capacity+500 入队全部正常返回、ListAppender 捕获 ≥1 条 queue-full WARN、落盘 ≤Capacity+1 | ✅ 2 用例 |
| ⑤ | 真链路 | CoreProbe extends AgentCore（HookExecutionIntegrationSpec 模式）跑真实 executeTool：成功行（Read 真文件）+ 三失败变体各一行——工具不存在（收敛路径）/Left(ToolError)（Read 缺文件）/抛异常（注册 ExplodingTool 抛 2500 字 RuntimeException，errorText 含全量原文），字段齐全落临时目录 | ✅ 4 用例 |
| ⑥ | requestId | 同一 id `req-align-toolslog-42` 同时出现在真实 LlmLogWriter 写的 router summary 行与真实 AgentCore 链产生的 tools 行；非 LLM 行 requestId key 在值 null | ✅ |
| — | 变异验红 | 见下节 | ✅ |

### 变异验红记录

| 变异 | 方式 | 结果 |
|---|---|---|
| MUTATION-1 | 注释 flatTap 内 `logToolStructured` 调用 | **4 红**（success / Left 变体 / 异常变体 / ⑥ requestId）；「工具不存在」变体仍绿——证明 None 分支收敛独立于 flatTap 生效 |
| 恢复 | 还原代码 | 11/11 绿 |
| MUTATION-2 | helper 内 errorText 改 `result.content.take(100)` | **1 红**：异常变体 2500 字符断言 `errorText.length >= 2500` 失败 |
| 恢复 | 还原代码 | 11/11 绿 |

## 七、jq 演示与 requestId 对齐证据（真实执行）

对 10 条真实经 ToolsLogWriter.log 产出的 JSONL（/tmp/toolslog-demo，演示后已清理）：

**jq 1 失败率视角**：`jq -s 'group_by(.tool) | map({tool: .[0].tool, total: length, failed: (map(select(.isError)) | length)})' 2026-09-03.jsonl`（节选）：

```json
[{"tool":"Bash","total":2,"failed":1},{"tool":"WebFetch","total":1,"failed":1},
 {"tool":"NodeEdit","total":1,"failed":1},{"tool":"Read","total":2,"failed":0}, ...]
```

**jq 2 失败明细**：`jq 'select(.isError) | {tool, elapsedMs, errHead: .errorText[0:60]}'` → WebFetch/5010ms、Bash/98ms、NodeEdit/0ms 三行。

**jq 3 字段完整性**：`jq -s 'map(keys) | add | unique'` → 恰为 11 个固定 key 并集（ts/tool/agent/sessionId/kind/isError/elapsedMs/errorText/inputSummary/resultChars/requestId）。

**requestId 对齐证据**（id=`req-demo-align-7f3a` 同现两文件）：

- tools 行原文：`{"ts":"2026-09-03T03:52:25.584Z","tool":"Read",...,"requestId":"req-demo-align-7f3a"}`
- router summary 行原文：`{"timestamp":"2026-09-03T03:52:25.512840Z","type":"request","request_id":"req-demo-align-7f3a","model":"demo-agent":...}`

## 八、全量 sbt test（worktree 内前台真实跑）

```
Passed: Total 2063, Failed 0, Errors 0, Passed 2063, Ignored 7
Total time: 472 s (0:07:52.0)
```

= 主仓基线 2052 + 新增 11 用例，吻合。一次跑完无锁冲突重试。

## 九、合并与清理

- 分支 commit：`cee6981b`（tools-log，实现全量）
- 开工 merge main：Already up to date（并行节点当时未入 main）
- **merge commit：`2d8dc9f1`**（main，`git merge tools-log --no-ff`，ort 策略**零冲突**；并行节点 9fc85182 NodeEdit 提交已在 main，与本批文件域无交集自动并入）
- 主仓 spec 子集复跑：ToolsLogWriterSpec + ToolsLogAgentCoreSpec + HookExecutionIntegrationSpec + FlowExecuteToolSpec + AllowedToolSetSpec + WebSearchWiringSpec + PermissionDenialMessageSpec + LoopGuardWiringSpec + LlmLogWriterPruneSpec + AgentLlmFailRetrySpec → **Passed: Total 108, Failed 0, Errors 0**（94s）
- 清理：`git worktree remove .nebflow/worktrees/tools-log` ✅、软链 `.nebflow/tools-log` 已删 ✅、`git branch -d tools-log`（曾为 cee6981b，--no-ff 保全历史）✅
- 全程未 push、未动 origin ✅；`git add` 均按具体文件 ✅；既有 spec 零改动 ✅；前端 web/ 零改动 ✅；未触碰宿主进程（PID 87216/端口 8080）✅

## 十、生效说明（重要）

**合并进 main 即代码交付，但运行时未生效**——宿主（PID 87216）仍运行旧代码。需后续**重建 + 重启宿主**后，工具执行才开始落盘 `~/.nebflow/logs/tools/<date>.jsonl`。**本批严禁且未执行任何重启动作。**

## 十一、遗留问题

1. **kind 值在非注册上下文为 null**：spec harness / REST 直调无 agentRegistry 注册 → kind=null（key 必在）。生产 agent 会话均经注册，值正常。
2. **errorText 无分类字段**：超时/权限/参数/异常混为纯文本（审计 §4.8）。方案 C 的错误分类器落地时建议加 `errorClass` key（JSONL 行自描述，jq `group_by(.errorClass)` 直接出分类失败率）。
3. **方案 C 预留字段建议**：本轮 11 字段已固定并全量落盘，后续加 key 为纯增量（jq 消费向后兼容）。建议预留：`errorClass`（超时/权限/参数/异常/不存在）、`depth`（agent 层级，跨 agent 链路聚合）、`rootSessionId`（已可得，按根会话聚合）、`resultChars` 已在本批覆盖（结果规模异常观察）。
4. **elapsedMs 在绕过路径为 0**：Deny/dropped/malformed 等即时返回路径未计时（语义上无执行耗时）；如需统一可在各路径包 delay 计时（改动大收益小，未做）。
5. **router 侧 intake 与 request 双 id 族**：LlmLogWriter.logIntake 的 request_id（inflight key）与 log() 的 request_id 历史上即两族（本批未改，工具行对齐的是 log() 族即 summary/full 主 id）。如需统一建议单独小任务。
6. **retain 天数 3 天与 nebflow.log 7 天不一致**：与 router 对齐是本批验收要求（共享常量）；若复盘窗口需延长，改一处常量即可（`LlmLogWriter.retentionDays`，两日志同步生效）。
