# 107 网关「长输出卡死」根因定位报告

- 日期：2026-08-20（取证窗口：08-19 夜间 → 08-20 10:45）
- 取证人：Explorer（DELEGATE 单次调用，未修改任何产品代码）
- 状态：完成即冻结（阶段文档）
- 关联：#300（Explorer 挂死 7.5h）、#296（排队无限等待）、#22（turn 静默死亡 ×8）、#311

## 0. 根因一句话

**不是 107 网关流挂死，也不是 Bash 挂——是 Nebflow 自己的 ConcurrencyGate（gate[107]）在「#296 取消排队超时 + 取消清理竞争」下楔死（permits=0 永不恢复 8.5 小时），后续所有 107 请求在 gate 排队层无限等待、零超时、零 fallback、零 SSE 事件——而两段式流 watchdog（首 token 90s / 中途断流 60s）挂在 `acquire` 之后，对排队中的请求永远不生效；agent 恰好在「工具执行完 → 下一个大段输出的 LLM 调用」处卡住，用户看到的就是「一到写报告就死」。**

```markdown
用户观察：agent 到「现在写最终报告」就死
实际卡点：下一个 LLM 请求 → gate[107].acquire → 排队（无限）→ 请求从未发出
为什么日志干净：SSE 日志只记录已发出的流事件；排队中的请求一个字节都没发出 → 零痕迹
为什么 watchdog 不救：inactivityTimeout 挂在 stream 上，stream 在 acquire 之后才存在
```

![因果链](/tmp/hang-chain.svg)

## 1. 时间线（全部本地时间 +08:00）

| 时间 | 事件 | 证据 |
|---|---|---|
| 08-19 夜 | Explorer（#300 夜间 107 稳定性测试）高频打 107，烧 RPM 窗口（20/60s） | sse 08-19 文件；20260818 报告 §5-6 |
| 00:05 / 00:31 | qa-backend 的 provider-down/e2e 测试在主实例上跑，多 provider 标 DOWN（测试流量叠加） | nebflow.log 00:05:34-40、00:31:27-33 |
| 00:26:50 | 夜间 Explorer 最后一个 107 请求完成（stop=tool_use，glm-5.2-107），随后执行 3 个 Read | sse 5419acde；nebflow.log 00:26:50.727 |
| ~00:29 | delegate-Explorer-d27d5623 进入 Processing 后再无进展（后面 7.5h 卡死起点） | TaskStuckWatcher 反推：03:04:16 报 stuck 9301s |
| ~00:50-00:55 | gate[107] 楔死形成：permits=0，此后**永不恢复** | gate 日志 10 分钟分桶：00:5x 起 perm=0 连续到 09:2x |
| 00:55-01:50 | 多个请求在 gate 排队/获准（出现过 3 个当事方 → 昨晚进程 maxConcurrency=默认 3，非现在的 1），期间用户/Watcher 的 Stop 取消了其中若干 | gate 日志 238× queued + 120× granted 变体 |
| 01:05-09:24 | **11318 条** `gate[107] acquire waiting: permits=0 waiters=0 thisWaiter=granted(rpm-wait)`，平均 2.6s 一条（≈2 个泄漏 tracer × 5s 周期），持续 8.3h | nebflow.log 聚合 |
| 02:37:54 | 主实例最后一条 SSE 事件（design-engineer，deepseek，end_turn）；此后 5.4h SSE 静默——主实例活着（日志连续无 >10min 间隙）但所有 agent 无 LLM 流量 | sse 两文件边界 + 日志间隙扫描 |
| 03:04 → 08:00+ | TaskStuckWatcher **每 30s 重发一次「sending Stop for supervised restart」，共数千次，无一生效**——Stop 是 mailbox 消息，agent 正忙等 LLM fiber，永不消费 | nebflow.log 03:04:16、07:00:18/48、07:01:18… |
| ~02:30 | 用户以为的「重启」只重启了测试实例（8106 @01:57、02:18）——**主实例（8080）从未重启**，楔死状态带进了早晨 | nebflow.log 仅 8106/8097 listening，8080 仅 09:24:40 |
| 08:00:12 | 用户晨间活动恢复流量（zhipu/deepseek 正常）——107 仍楔死 | sse 08-20 文件首事件 |
| 09:24:40 | 主实例真正重启（新代码 + 新配置 `107.maxConcurrency=1`），gate 状态清零，107 恢复服务 | nebflow.log 09:24:40；此后 63 个 107 请求全部完成，含 8144 token 长输出（本次取证的 Explorer 即在 107/glm-5.2-107 上跑） |

## 2. 证据链

**E1｜SSE 日志「零异常」恰恰是铁证**：两日 sse 文件全部请求（08-19：5629 个；08-20 09:24 后：1081 个）**孤儿数为 0**——所有发出过的流都完整终止。卡死的请求**从未发出**，所以在流日志里不可见。「表面无异常」=「死亡发生在流层之前」。

**E2｜gate[107] 楔死日志**：`permits=0 waiters=0 thisWaiter=granted(rpm-wait)` 从 00:55 连续到 09:24:17（11318 条）。permits=0 = 全部并发许可被占且无人排队无人释放；`waitForWindow`（gate.scala:69-89）数学上最多 limit×60s（rpm=20 → ≤20 分钟）必然滑出窗口条目——**楔 8.5 小时超出该函数一切正常行为**，指向许可泄漏/永久停驻，而非正常 RPM 等待。

**E3｜watchdog 全程未开火**：01:05-09:24 期间 **零条** `Stream retry / Stream fallback / inactivity / no response within` ——若持有者在「开火→90s 超时→fallback→重新排队」活锁，必有周期性 WARN。零命中 = 请求停在 `acquire`/`waitForWindow` 内部，从未到达流层。interface.scala:496-505：`inactivityTimeout` 挂在 stream 上，而 stream 在 gate 许可获得之后才 `Stream.force` 求值——**排队/等窗口的请求不受任何 watchdog 保护**（这正是 #296 注释自己写的设计：「LLM timeout 从 stream 开始计时（acquire 之后），不受排队影响」）。

**E4｜昨晚进程 maxConcurrency=3（非现配置 1）**：01:40-01:50 的 gate 日志同时出现 `waiters=1 queued`、`waiters=1 granted(rpm-wait)`、`waiters=0 granted(rpm-wait)` 三种视角——单许可下不可能有 ≥3 个当事方。昨晚进程用的是默认值 3（Defaults.scala:105），`maxConcurrency:1` 是今晨才写入配置的。3 个许可全部卡死后 permits=0。

**E5｜Stop 救援机制失效**：TaskStuckWatcher 每 30s 发一次 Stop 持续 6.5h+ 无效果。Stop 是 actor mailbox 消息；agent 正 suspended 在 LLM 调用上，mailbox 永不轮转 → 消息堆积无人消费。系统没有任何「硬取消 LLM fiber」的升级路径（inflight abort 注册表 interface.scala:29-46 只用于 JVM shutdown）。

**E6｜「02:30 重启」是误判**：主实例日志 00:01→09:24 连续无断裂（含每 5s 的 gate tracer、每 15min 的提醒），唯一中断在 09:24:40（gateway listening 8080）。02:30 前后只有测试实例 8106 的两次 listening（01:57/02:18）。**楔死的主进程带着 wedge 跑了一整夜**——这也解释了「为什么重启了还挂」。

**E7｜107 网关本身是清白的（今晨复验）**：09:24 重启后 63 个 `107/glm-5.2-107` 请求全部正常终止，含 8144 token 的长输出（本次取证 Explorer 的首个大 turn）——**网关长输出流式没有问题**。网关的真实角色只是「帮凶」：20 req/60s 限流 + glm-5.2-107 推理模型高负载 p95 44.8s（20260818 报告 §5），把 RPM 窗口填满，制造了 gate 排队风暴的触发条件。

**E8｜幽灵 tracer 机制（代码级）**：gate.scala:152-161——`tracer <- traceLoop.start; _ <- d.get; _ <- tracer.cancel`。fiber 在 `d.get` 处被取消时，`onCancel(cleanup)` 会跑清理，但 **`tracer.cancel` 永远执行不到**（它在 d.get 之后）→ tracer fiber 泄漏，每 5s 永久打印「granted(rpm-wait)」（该标签是推断值：不在 waiting 队列≠真的获准）。这批日志污染了现场，让「有请求在等 RPM」的假象持续了 8 小时。

## 3. 根因假设排序

| # | 假设 | 概率 | 支持证据 | 反证/缺口 |
|---|---|---|---|---|
| 1 | **gate 许可泄漏（取消竞争）**：granted-but-canceled 请求的 permit 在 dispatch/cleanup 竞争中丢失，3 许可陆续漏光 → permits=0 永不恢复；11318 条日志是泄漏 tracer 的遗作 | 高 | E2 超出 waitForWindow 数学上限；E4 三许可全灭；E8 tracer 泄漏机制实锤（同类取消路径缺陷）；ee7af0ac「gate cancelation leaks」前科 | 精确竞争窗口未从日志复现（需单测注入） |
| 2 | **waitForWindow 永久停驻**：某 fiber 获准后卡在 RPM 等待内部（时钟/调度异常） | 中低 | E2/E3（从未开火）；interface.scala:139 注释自认「时钟回拨类」风险 | 日志时间戳零回归（NTP 回拨排除）；CE 3.6.1 realTime 若为单调钟则不可能 |
| 3 | 107 网关侧流挂死（用户原始假设） | **排除** | E1 零孤儿、E3 零超时、E7 今日 8k token 长输出正常 | — |
| 4 | Bash 工具挂死 | **排除**（对本次） | Bash START/RUNNING 留痕后无孤儿命令；卡点前最后一个动作是 LLM 调用排队 | #22 历史 8 次中或仍有 Bash 型，需逐案复核 |

**一句话裁定**：假设 1 为主因（假设 2 为备选，两者修复方案高度重叠）。触发条件可复述：`多 agent 并发打同一 provider + RPM 窗口打满 + TaskStuckWatcher/用户 Stop 取消排队中的请求`——昨晚三条件齐备。今晨 `maxConcurrency=1` 让单许可成为单点，**复发概率反而更高**（一个泄漏即全堵）。

## 4. 修复建议（含止损）

### P0-1 排队等待必须有界（修正 #296 的过度矫正）
`gate.acquire` 恢复排队超时（建议 120s，`QueueTimeout` 已存在且分类 Transient→fallback 下一个 provider）。#296 的合理诉求（排队不该立即 fail）用「长上界 + 到期 WARN + fallback」满足，而不是无限。**无限等待 = 按设计不可观测的静默死亡**，这是本次 8.5h 的直接放大器。
- 附带：把 watchdog 的保护范围扩到 `acquire`（或给整条 LLM 调用链加 turn 级 deadline），保证「gate 内外没有任何位置可以永久停车」。

### P0-2 修 gate 取消竞争 + 幽灵 tracer
- tracer 生命周期改挂 `guaranteeCase`/`onCancel`（取消路径必 cancel tracer），杜绝 8h 假日志；
- dispatch 的 `d.complete` 与 cleanup 的「补许可」竞争窗口收口（如 modify 内校验 waiter 存活代际），杜绝许可泄漏；
- tracer 日志行**补上 window.length 与最老条目年龄**——这次若有一个数字，判别假设 1/2 只要 5 秒。

### P1-1 TaskStuckWatcher 升级为硬取消
Stop 连发 N 次（如 2 次）无响应 → 通过 inflight 注册表（interface.scala:29-46，现仅用于 shutdown）按 agent/session **cancel 在飞 LLM fiber** → turn 走 llm-fail 重试路径。mailbox 消息对 suspended agent 无效是这次 6.5h 无救援的根因。

### P1-2 配置与链路
- `107.maxConcurrency` 建议 2（20260818 报告压测：并发 2 无 429 且吞吐最高；=1 是单点）；
- **fallback 链不要主备同网关**：`107/deepseek-v4-flash-ascend → 107/glm-5.2-107` 全在 107，gate 楔死 = 全链死。改为跨 provider（107 → zhipu/deepseek），gate 楔死时排队超时能落到健康网关。

### P2 观测补强
- sse 路由日志补记**请求受理事件**（pre-gate，含 request_id）——「从未发出的请求」目前在流日志里零痕迹；
- gate 周期快照升级为结构化（permits/waiters/window 长度/最老条目年龄/drain 预估）。

### 止损（token 烧钱防护，用户点名要求）
- 排队超时 fallback 时**不要回攻同一 provider**（现有语义已对 Timeout 跳过同 provider 重试，保持）；
- 大上下文（>100k token）请求的 fallback 重试计入 retry-only budget（e88a8242 已有机制），超限直接 fail turn 并提示用户，避免「重试大上下文 × N provider」的 token 放大；
- 429 类错误维持 ≥60s 退避（litellm 窗口），不要在窗口边缘高频试探。

## 5. 修复验收条件（给实施方）

1. **冒烟（硬性）**：`sbt stage && ./nebflow` 真实启动 → `curl /api/health` 200 → 前端 `curl /` 返回 HTML。
2. **楔死复现单测**：模拟「acquire 排队中取消 × 3」后断言 `permits` 恢复满额、无 tracer 再打日志（5s 观察窗零新增 gate WARN）；`waitForWindow` 满窗 + 时钟不变断言 ≤ limit×60s 内返回。
3. **端到端**：3 个 agent 并发打配置 `maxConcurrency=1, rpm=20` 的 provider，窗口打满后发 Stop → 断言：每个 turn 在 QueueTimeout+ε 内落到 fallback provider 正常完成（用户可见回复），gate 状态恢复，无请求挂 >5min。
4. **回归**：现有 `ConcurrencyGateSpec / StreamWatchdogSpec / FallbackRetryPolicySpec / LlmInflightAbortSpec` 全绿；新增用例进 `gate` 套件。
5. **留痕可判别**：制造一次 rpm-wait，断言日志行含 `window=N oldest=Xs` 字段。

## 6. 遗留不确定点

- 假设 1 的精确竞争指令序列未定格（需要按 P0-2 写注入式单测时顺手固化）；假设 2 的「waitForWindow 永久停驻」在 CE 3.6.1 realTime 时钟语义（wall vs nano-base）上仍有一丝可能，修 P0-1 后两类都会被兜底，判别意义降为学术。
- 昨晚 00:29-00:50 之间 holder 如何持满 3 许可的精确顺序，日志粒度（5s tracer）不足以完全重建；P2 的结构化快照就是为了下次不需要重建。
- #22 历史 8 次「turn 静默死亡」建议按本报告框架回溯归档（gate 楔死型 vs Bash 隐形型），预期大部分与 E8/E5 同族。
