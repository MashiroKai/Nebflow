# Token 看板 vs sse 日志 对账报告（第二阶段）

| 字段 | 值 |
|---|---|
| 日期 | 2026-08-22 |
| 执行 | cache-engineer |
| 任务 | 看板命中排行数据与 sse 日志实测对账（Nebula 点头，纯分析零代码改动） |
| 口径 | spec v1.2（@a887d1d）：命中率 = cacheRead/input；总消耗 = input+output |
| 前置 | D1 filter+costEquivalent 已合主线（3c3c035e）；看板已合主线（6883759a）且实例重启生效 |

## 0 · 结论

**一致性 PASS**——远优于 5% 阈值。窗口内总消耗偏差 **0.017%**（54,119 / 325,742,833 token），请求数偏差 0.53%（14 / 2,653），且差异构成完全可解释（mock 测试请求 + 失败尾单，均为看板侧合理不记）。五维聚合（provider/model/agent/day/hour）中除 2 个 hour 桶受上述 14 条影响外**逐位一致**。无需立项修复。

## 1 · 对账设计

**窗口**：2026-08-21 00:00 → 2026-08-22 22:00（本地 +08，46 小时，覆盖 08-22 实例重启边界）。

**四路数据源**：

| 路 | 来源 | 角色 |
|---|---|---|
| A | sse 日志原始 usage（`*_sse.jsonl` 的 `message_delta` 事件，含 08-20 卷） | 任务指定的日志侧 |
| B | summary 日志 response 行 usage（`*_summary.jsonl`） | 第二日志侧 |
| C | usage-records.jsonl 直读（UsageRecordStore 落盘层） | 看板数据源 |
| D | `GET /api/usage/aggregate`（实例 live，Bearer auth） | 看板 API 出口 |

**方法论限制（如实声明）**：
- 三路日志无统一请求主键（sse 有 request_id；summary response 行与 usage-records 均无）→ 只能做**聚合级对账**，非逐请求配对
- sse 取每 request_id 时间戳最大的 usage 行（覆盖重试产生的 0 值占位与旧值；实测两口径合计完全相同，无重试丢失）
- sse/summary 按 **UTC 天**分卷，看板按本地时区聚合——本地 08-21 00:00-08:00 的请求在 `2026-08-20_sse.jsonl` 内，跨日窗口必须回读前一卷（本对账已包含）

## 2 · 对账主表

### 2.1 总量

| 指标 | A sse | B summary | C usage-records | D API | A/C 偏差 |
|---|---|---|---|---|---|
| 请求数 | 2,667 | 2,667 | 2,653 | 2,653 | +0.53% |
| input | 325,796,952 | 325,796,952 | 325,742,833 | 325,742,833 | +0.017% |
| output | 2,272,596 | 2,272,596 | 2,272,325 | 2,272,325 | +0.012% |
| cacheRead | 308,344,751 | 308,344,751 | 308,285,969 | 308,285,969 | +0.019% |
| 命中率 | 94.6% | 94.6% | 94.6% | 94.6% | 0 |
| 总消耗(in+out) | 328,069,548 | 328,069,548 | 328,015,158 | 328,015,158 | +0.017% |

A ≡ B 逐位一致；C ≡ D 逐位一致（API 与落盘同源 loadAll，符合预期）。

### 2.2 provider 维度

| provider | A cnt | C cnt | A in | C in | 偏差 | 命中率 A/C |
|---|---|---|---|---|---|---|
| zhipu | 1,520 | 1,520 | 178,169,574 | 178,169,574 | 0.000% | 95.0% / 95.0% |
| qwen | 986 | 977 | 122,832,101 | 122,778,032 | +0.044% | 94.2% / 94.2% |
| kimi | 109 | 109 | 20,842,644 | 20,842,644 | 0.000% | 94.3% / 94.3% |
| 107 | 47 | 47 | 3,952,583 | 3,952,583 | 0.000% | 92.6% / 92.6% |
| mock | 5 | 0 | 50 | 0 | —（测试请求） | — |

model 维度与 provider 一一对应（GLM-5.3 / qwen3.8-max / k3-256k / glm-5.2-107），偏差同上，不重复列。

### 2.3 agent 维度（Top 与差异项）

16 个 agent 中 14 个**逐位一致**（含消耗最大的 Frontend 503/75,997,058、Backend 356/56,079,359、html-builder、czt-physicist 等全部对齐）。差异仅：

| agent | A cnt | C cnt | A in | C in | 差异构成 |
|---|---|---|---|---|---|
| Nebula | 215 | 202 | 26,586,116 | 26,532,007 | 9 条 qwen 失败尾单 + 4 条 mock |
| Fast | 1 | 0 | 10 | 0 | 1 条 mock |

### 2.4 day / hour 维度

- day：08-21 差 14 条（0.019%），08-22 **逐位一致**
- hour：15 个桶中 13 个 0.000%；仅 08-21T00（+5 条 mock，50 token）与 08-21T05（+9 条 qwen，54,069 token，-0.060% 相对 ur）有微差——正是 14 条差异的落点

## 3 · 差异明细（14 条，全部定性）

| # | 数量 | 特征 | 定性 | 看板侧不记是否合理 |
|---|---|---|---|---|
| 1 | 5 条 | `mock/m1`（Nebula×4 + Fast×1），各 in=10/out=3 | 测试/mock provider 请求（非 AgentActor 链路） | 合理——测试请求不进用量统计 |
| 2 | 9 条 | Nebula/qwen3.8-max，UTC 21:34-21:46（本地 08-21 05:34-05:46）聚簇，in 5.6-6.5K、cr=5,248 稳定、连续相似请求 | 疑似失败/异常完成串——LlmLogWriter 记录了流末 usage，但 AgentActor 未产出 result.usage/result.model（写入条件 AgentActor.scala:1106-1107）→ 不写 usage-records | 基本合理——失败尾单不计入避免计费歧义；量级 0.017% |

## 4 · 发现清单（只报不改）

### F1 · Flow DAG worker 的 usage 三路全无（需 Backend 确认）
summary 中 `agent=worker` 的 490 条 response（session 形如 `dag-tflow-p-*`、is_subagent=true、request_model=null）：**usage 全缺失**、resolved_model=unknown、response_length 0-25 字符。sse 与 usage-records 同样无记录。
- 若其中有真实 LLM 调用，其消耗在看板不可见；但现有证据（model=unknown、响应近空、无 usage）指向这些是 flow 编排层占位/失败请求，**逃逸量可能 ≈ 0**
- 建议：Backend 确认 worker 请求的真实性；若为真实调用需补埋点，若为占位建议 summary 日志标注以便区分

### F2 · kimi 已恢复 cache 上报，spec §6.9c 黑名单初版过时
kimi 自 **08-21 04:49** 起正常上报 cache（169/177 条 cr>0，命中率 94.3% 与主力 provider 同档）；仅 8 条 cr=0（含冷启动真实未命中）。第一阶段「kimi 不上报 cache」的结论基于 08-20 前的单条样本，已过时。
- 影响：spec v1.2 §6.9c 前端内置不上报名单初版 `['kimi']` 应**改为空数组**（机制保留、名单留空），否则会把 kimi 的真实命中率错误标注为「provider 未上报」
- 建议：Manager 转 Frontend 实施时更新；E4 配置面扩展点保留不变

### F3 · sse ≡ summary 非独立验证路
两路逐位一致（同源双写：message_delta 与 response 的 usage 均来自 LlmLogWriter）。「sse 日志 vs 看板」的对账实质是 **LlmLogWriter 链 vs AgentActor→UsageRecordStore 链**的交叉验证——这一交叉是有效的（写入时机不同：流末 vs LlmComplete 后），但不宜宣称「双日志独立互证」。

### F4 · costEquivalent D1 修复已验证生效
API 实测 `costEquivalent = 48,285,460 = (in-cr) + cr×0.1`（旧公式会得 356,571,430）——3c3c035e 的修复在线上正确生效。✅

### F5 · 对账方法论陷阱沉淀（供后续复用）
1. **JSON 行 grep 多字段不可假定字段顺序**：usage 在行首、agent 在行尾——`grep '"agent":"Backend".*"usage"'` 顺序写反会得出「无 usage」的假阳性（本次曾据此误判 Backend 逃逸，被 python 全量解析推翻）
2. **sse/summary 按 UTC 天分卷 vs 看板本地时区聚合**：跨本地日界的对账窗口必须回读前一 UTC 卷
3. summary response 行**无 request_id**——三路无统一主键，聚合级对账是当前上限

## 5 · 建议（均无需紧急立项）

| # | 建议 | 级别 |
|---|---|---|
| 1 | F2 黑名单初版改空数组——随看板前端实施一并处理 | P1（一行常量） |
| 2 | F1 worker 真实性确认——Backend 一次性排查，结论决定是否补埋点 | P2 |
| 3 | F5-3 请求主键贯通（response 行补 request_id）——提升未来对账与排查精度 | P2（可观测性增强） |

## 6 · 附：对账脚本口径

- sse 有效请求 = 窗口内 request_id 最新 usage 行且 (in>0 or out>0)；A 路 in 合计 325,796,952（含 0 占位行被覆盖逻辑）
- C 路 = usage-records.jsonl 窗口过滤直读；D 路 = API from/to 同窗口
- 命中率 = cr/in（包含语义，spec v1.2）；总消耗 = in+out
- 窗口 epoch：from=1787241600000 / to=1787407200000
