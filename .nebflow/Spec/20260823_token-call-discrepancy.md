> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 2026-08-23 Nebflow 侧 LLM 调用统计 vs Provider 后台次数差异审计

> 阶段文档（诊断类，完成即冻结）。触发：用户 08:54 提出「总感觉有 token 泄露」——后台 API 调用次数与 Nebflow 里 agent 干活的感觉对不上。
> 数据源：`~/.nebflow/logs/router/2026-08-23_{summary,full,sse}.jsonl`（快照 09:02 冻结）+ `~/.nebflow/usage-records/usage-records.jsonl` + `~/.nebflow/logs/nebflow.log` + 代码（AgentCore / interface.scala / HealthMonitor / LlmLogWriter / UsageRecordStore）。
> 审计者：Explorer delegate（本次任务本身）。

## TL;DR（先给结论）

1. **没有「泄露」**——没有发现任何用户不可见的非预期 LLM 调用。今天窗口内 151 次调用全部有据可查，可逐条归因到 3 个会话。
2. **差异主因 = 口径不同 + 昨天用量打爆额度**：
   - Nebflow 侧统计的是「成功完成的 turn 数」（1 turn = 1 条日志），provider 后台统计的是「HTTP 请求数」（1 turn 可能 = 多次 HTTP：重试 + fallback + 健康探测）。
   - 今天 08:27 起 **zhipu/GLM-5.3 与 qwen/qwen3.8-max 双双额度用尽**（昨天 3380 次调用、3.55 亿 input token 烧光周/月配额），Nebflow 自动 fallback 到 deepseek。你在 zhipu/qwen 后台看到的「多出来的调用」= 配额拒绝的尝试 + 每 2 分钟一次的健康探测（不计费，但控制台有请求记录）。
   - 今天 108/151（72%）的调用是 subagent（Manager 团队 78 次 + Explorer delegate 30 次），主对话只占 28%——用户对「agent 干活量」的感知天然低估后台计数。
3. **两个真实的口径缺口（非泄露，是统计盲区）**：
   - summary/usage 日志只记成功 turn，**重试次数与健康探测不落盘**（今天 ≈40 次 HTTP 请求无对应日志条目）；
   - flow DAG worker 调用（08-22 有 610 次）**usage 为空**——token 真实消耗但未计入看板。

---

## 一、今日（08-23 08:27–08:58 CST）实际聚合

快照：**151 request + 151 response，全部 status 200，全部 deepseek/deepseek-v4-flash，thinking enabled，stream。**

| 维度 | 值 |
|---|---|
| 总请求数 | 151 |
| 按 model | deepseek/deepseek-v4-flash: 151（100%）——**无 fallback 链在 summary 层触发** |
| 输入 token | 17,628,695（均值 116,746/次） |
| 输出 token | 99,098（均值 656/次） |
| cache_read | 16,852,864（**占输入 95.6%**） |
| cache_write | 0 |
| 非缓存输入（全价部分） | 775,831 |
| 成本等价（输入，cache 0.1x） | 2,461,117 |

### 按 agent × session

| agent | session | 次数 | 输入 | 输出 | cache_read | 非缓存输入 | 角色 |
|---|---|---|---|---|---|---|---|
| Nebula | 5cc7590a（主对话） | 43 | 9,706,172 | 25,566 | 9,051,392 | 654,780 | 用户可见主会话 |
| Manager | 531c61ae（html-deck-studio 团队） | 78 | 6,793,409 | 58,485 | 6,716,928 | 76,481 | subagent，团队批处理 |
| Explorer | delegate-Explorer-095c（本任务） | 30 | 1,129,114 | 15,047 | 1,084,544 | 44,570 | subagent，ask/delegate 临时实例 |

### 按 is_compaction × is_subagent

| 组合 | 次数 |
|---|---|
| compaction=false, subagent=true | 108（72%） |
| compaction=false, subagent=false | 43（28%） |
| compaction=true | **0**（窗口内无压缩） |

### 时间线（本地时间，分钟级）

![调用时间线](assets/20260823_token-call-discrepancy/call-timeline.svg)

- 08:27–08:30：Nebula 主会话（27 次）
- 08:31–08:52：Manager 团队工作（78 次，08:45 峰值 17 次/分钟——「三连批次」+ deck 3030 daemon 修复）
- 08:55–08:58+：Explorer delegate（本审计任务，30 次）

### 单调用上下文规模（防 2 亿事故复现检查）

| 指标 | 值 |
|---|---|
| input ≥ 200k 的调用 | **43 次**（全部为主对话；最高 244,726，逼近 250k 窗口） |
| input ≥ 100k | 75 次 |
| 平均上下文 | 195 条消息 / 系统 prompt 50–80k 字符 |

**结论**：大上下文调用存在，但 cache 命中率 95.6% 意味着每次只有 ~5k token 按全价计费；重试预算（方案 C，MaxTurnLlmCalls）与 per-turn 止损已生效，未见 08-18 式失控迹象。

---

## 二、调用全景与证据链

### 2.1 08:27 首次调用的完整 fallback 链（日志摘录，nebflow.log）

```
08:27:01.139 WARN nebflow.llm      - Stream retry zhipu/GLM-5.3: Unknown (1 left, 2319ms)
08:27:04.018 WARN nebflow.llm      - Stream fallback: zhipu/GLM-5.3 retries exhausted
08:27:04.021 WARN nebflow.llm.health - Provider zhipu/GLM-5.3 marked DOWN:
   rate_limit_error code=1310 「您已达到每周/每月使用上限，限额将在 2026-08-26 10:57:18 重置」
08:27:04.582 WARN nebflow.llm      - Stream retry qwen/qwen3.8-max: Unknown (1 left, 1138ms)
08:27:05.998 WARN nebflow.llm      - Stream fallback: qwen/qwen3.8-max retries exhausted
08:27:05.999 WARN nebflow.llm.health - Provider qwen/qwen3.8-max marked DOWN:
   insufficient_quota 「Your token-plan 1-week quota has been exhausted. Reset 08-27 10:58 UTC」
08:27:06.895 INFO nebflow.llm.anthropic - message_start: model=deepseek-v4-flash ✓
```

即：用户感知的「第 1 次提问」在 provider 层实际打了 **4 次 HTTP**（zhipu×2 + qwen×2，全部配额拒绝）+ 1 次 deepseek 成功。此后 zhipu/qwen 被标记 DOWN，全部流量直达 deepseek。

### 2.2 intake == 完成 turn，无楔死/无丢失（#27 事故对照）

| agent | sse intake | summary request | 差 |
|---|---|---|---|
| Nebula | 43 | 43 | 0 |
| Manager | 78 | 78 | 0 |
| Explorer | 30 | 30 | 0 |

**每次 sendStream 启动（intake）都有对应的成功日志**——无 gate 楔死、无排队丢弃、无幽灵请求。

### 2.3 窗口内 3 次 deepseek 重试（真实失败重试）

```
08:54:48 Stream retry deepseek/deepseek-v4-flash (1 left, 1606ms)
08:55:23 Stream retry deepseek/deepseek-v4-flash (1 left, 2611ms)
08:58:06 Stream retry deepseek/deepseek-v4-flash (1 left, 2331ms)
```

3 次重试均成功 → 产生 **3 次额外的 provider HTTP 调用**，summary 无对应独立记录（合并进最终成功 turn）。

### 2.4 差异全景图

![调用全景与差异来源](assets/20260823_token-call-discrepancy/discrepancy.svg)

左列 = Nebflow 日志可见的 turn；右列 = provider 后台真实 HTTP 请求。红色虚线 = 用户不可见/未落盘的请求路径。

---

## 三、差异归因清单

| # | 机制 | 产生「后台多、用户感知少」的调用？ | 今日数量级（实测） | 是否正常 | 可优化性 |
|---|---|---|---|---|---|
| 1 | **fallback 链尝试**：preset 首选失败 → 顺延下一候选，每次尝试 1 次 HTTP | 是（配额拒绝的尝试仍计为请求；部分 provider 不计费） | zhipu 2 次 + qwen 2 次（08:27 一次性） | 正常（预期机制，用户可见 RetryStatus） | 可优化：额度类错误（1310/insufficient_quota）应提前探测并跳过，避免每轮重复打 |
| 2 | **turn 内重试**（超时/Transient，同候选 backoff） | 是（失败的 attempt 计费 input token，若已开始处理） | deepseek 3 次 | 正常 | 可优化：统计落盘（见第五节） |
| 3 | **健康探测**：Down 候选每 120s 发最小 completion（"hi"） | 是（控制台可见请求；配额拒绝 → 基本 0 token） | zhipu+qwen 自 08:27 Down → 35 分钟内 ~17 轮 × 2 ≈ **34 次**（推断） | 正常但**静默**（仅 DEBUG 日志） | 可优化：探测应计费感知——对已知配额耗尽的 provider 停止探测直到配额重置时间 |
| 4 | **subagent 工作**（Manager 团队/Explorer delegate） | 是（用户只看主对话，72% 调用发生在背后） | 108/151 | 正常（团队模式的本质） | 可优化：前端按 session 分组展示「主对话 vs 后台」用量 |
| 5 | **flow DAG worker**（dag-tflow-*，1 条消息 "work" + 7 工具） | 是（真实调用但 usage 空，看板 token 缺失） | 今日 0；**08-22 有 610 次 ≈ 150 万 input token 未入账** | **异常**：usage 解析缺失 | 必修：修复 Done chunk usage 传播（见第五节） |
| 6 | **压缩 turn**（is_compaction） | 是（系统调用，用户不可见） | 今日 0；08-22 有 10，08-20 有 34 | 正常，已带 is_compaction 标记 | 低 |
| 7 | **token 计费口径**：cache_read 95.6% | 否（cache 是省钱不是泄露；看板 costEquivalent 已按 0.1x 折算） | 今日非缓存输入仅 775,831 | 正常 | 低：dashboard 标注「cache 0.1x 折算」说明 |
| 8 | **并发排队**（ConcurrencyGate + queueTimeout 120s） | 仅当排队超时才产生额外 fallback 尝试 | 今日 0（无 QueueTimeout 日志） | 正常（#27 已修复，intake 落盘验证无楔死） | 低 |
| 9 | **跨 provider 对照混乱** | 是（看 zhipu 控制台却今天全走 deepseek） | 昨天 zhipu 1125 / qwen 917 / kimi 572 / deepseek 76 | 正常 | 可优化：看板按 provider 汇总，方便对照各后台 |

---

## 四、明确回答用户：「泄露」是否存在？

**不存在「看不见的调用在烧 token」的异常证据。** 今天的差异是三个正常机制叠加 + 两个统计盲区：

1. **昨天干得太多，额度被烧完了**：08-22 一天 3380 次调用、3.55 亿 input token（其中 95% 命中缓存），把 zhipu 的周/月额度和 qwen 的周额度都用尽。今天 zhipu/qwen 后台的「调用记录」主要是配额拒绝和健康探测——它们出现在控制台里，但几乎不产生 token 费用。
2. **后台数的是 HTTP 请求，Nebflow 数的是成功回合**：一次提问在后台可能记 1–4 次请求（重试 + fallback），Nebflow 只记最终成功那 1 次。今天 ≈40 次 HTTP 请求（配额尝试 4 + 重试 3 + 探测 ~34）在 Nebflow 侧没有任何日志条目。
3. **你看到的「agent 干活量」只有冰山一角**：主对话只占 28%，另外 72% 是团队 Manager（html-deck-studio 三连批次 + deck 修复）和临时 delegate（比如本次审计本身就用掉了 30 次调用）——这些都在后台真实发生，但不在主聊天窗口里。

> 如果你担心「2 亿 token 事故重演」：今天最坏情况是 43 次 ≥200k 上下文调用，但 95.6% 命中缓存（实际全价 token 仅 77 万），且有 turn 级重试预算兜底；未发现 08-18 式「并发 × 250k 上下文 × 重试放大」组合。

---

## 五、建议：让用户能对上账的最小改动

### 5.1 summary 日志补三列（改动最小，直接可对账）

`LlmLogWriter.log` 已有 `result` 上下文，缺 attempt 信息。在 `FallbackAttempt` 已收集到 `onAttemptCb` 的前提下：

| 字段 | 来源 | 含义 |
|---|---|---|
| `attempts_count` | AgentCore 里累计 `onAttemptCb` 调用次数 | 该 turn 实际打的 HTTP 次数（1 = 无重试） |
| `fallback_chain` | `firstFailedModel` + provider 列表 | 该 turn 尝试过的 provider/model 序列 |
| `turn_kind` | 现有 `isCompactTurn`/`isSaveTurn`/`isAskTurn` 已可区分 | compact / save / ask / normal 分类，ask fork 直接可对账 |

改动点：`AgentCore.scala` 中 `onAttemptCb` 已是 Ref 可累计次数；`LlmLogWriter.log` 签名加 `attempts: Int` 即可。

### 5.2 健康探测落盘（消除静默请求）

`HealthMonitor.probe` 失败仅 DEBUG。改为：每次探测在 summary 文件记一条 `type=probe` 记录（provider/model/result/timestamp），或至少 WARN 级日志。另建议：**对 `1310`/`insufficient_quota` 类配额错误标记 `quota-exhausted-until`，探测循环跳过该候选直到重置时间**——既省 HTTP 又消除控制台噪音。

### 5.3 flow DAG worker usage 为空（真实缺口，修）

08-22 的 610 次 worker 调用（dag-tflow-*）response usage 为空、model=unknown——token 消耗未入看板。定位：这些调用走正常 AgentCore 路径但 `Done` chunk 的 usage/model 未回传。需查 deepseek adapter 对 1 消息小请求的 usage 解析分支（疑似 `aggregateChunks` 对缺省 usage 的处理）。

### 5.4 看板展示建议（非必需）

- 用量按「主对话 / 团队 / delegate / flow / 系统」分桶展示（现有 `session_id` 前缀已可区分：`5cc…`/`531c…`/`delegate-`/`dag-`）；
- 显式标注 cache 折算口径与「重试/探测未计入」说明，避免再次误读。

---

## 附：昨日（08-22）基线对照（理解「额度被打爆」的背景）

| 指标 | 08-22 | 08-21 | 08-20 |
|---|---|---|---|
| 总请求 | 3,380 | 768 | 8,552 |
| subagent 占比 | 91% | 92% | 92% |
| compaction | 10 | 4 | 34 |
| model 分布 | zhipu 1125 / qwen 917 / kimi 572 / deepseek 76 / **unknown 690** | qwen 448 / zhipu 320 | zhipu 4003 / unknown 2879 / qwen 884 / deepseek 639 |
| input token | 355,885,659 | — | — |
| cache_read 占比 | 96% | — | — |

08-22 的 kimi 572 次说明 fallback 链（general: zhipu→qwen→deepseek；Vision: qwen→kimi）大面积触发——zhipu/qwen 在前一天已接近额度上限，当天消耗 3.55 亿 token 后于 08-23 08:27 触顶。

---

*审计完成于 2026-08-23 09:05 CST。数据快照 /tmp/router-snapshot/（冻结于 09:02），分析脚本与图表 /tmp/call-timeline.svg、/tmp/discrepancy.svg。*
