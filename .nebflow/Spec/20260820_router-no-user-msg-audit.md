# Router 日志「无用户消息」记录排查

- 日期：2026-08-20 21:41 报告，当晚排查完成
- 现象：`~/.nebflow/logs/router/*_full.jsonl` 里大量请求记录只有 `system_ref` 和 `tools_ref`，`message_refs` 为空——表面看像「消息丢了」
- 性质：**一次性诊断报告（阶段文档），完成即冻结**

## 一句话结论

**这不是消息丢失，也不是探测请求——是 2026-08-18 commit `6ea379c2`（token incident P0）引入的设计内日志降采样：`input_tokens > 100,000` 的大请求跳过 messages 逐条落盘（`message_refs = Nil`），system/tools 照存。消息实际完整送达了模型（response 里 input_tokens 可证），只是日志不落盘。**

## 机制（代码对应）

`src/main/scala/nebflow/core/LlmLogWriter.scala` L79-95：

```scala
// ── Token 止损（2026-08-18）：大请求降采样 ──
// >100k input 的请求（占比最高的烧钱源）跳过 messages 的 object
// 落盘与 SSE 正文事件，只保留 usage/元数据——审计统计完整，IO 大砍。
// 今日实测：432MB sse/天 + 30687 个 objects 文件，大头就是大请求。
val bigRequest = resultUsage.exists(_.inputTokens > 100000L)

val messageRefs: List[String] =
  if bigRequest then Nil // 大请求：跳过 messages 逐条落盘（最大 IO 源）
  else messages.map(m => storeObject(messageToJson(m)))
```

同时 L150/L404：bigRequest 的 SSE 正文事件也全跳（`keepDetail = false`），只留 usage。Response 记录的 `full` 字段（thinking/text/tool_use）不受影响、照常落盘。

引入 commit：`6ea379c2`（2026-08-18 16:29:56 +0800，"llm: token incident mitigation — per-turn budget, overload-only retry, log downsampling (P0)"）。

## 验证证据链

| # | 证据 | 结果 |
|---|------|------|
| 1 | 2231 条 zero-refs 请求与后续 response 配对，读 input_tokens | **全部 >100k**（最小 100085，无一条 ≤100k） |
| 2 | 非 zero-refs 请求 3253 条中 input_tokens >100k 的 | 仅 1 条（126242，边缘杂音）——阈值分界 99.96% 干净 |
| 3 | 首条 zero-refs 记录（Nebula 主会话，mc=126） | input_tokens=104712——消息真实存在且送达 |
| 4 | 对照 8/17（降采样上线前） | 4367 条请求 **0 条** zero-refs；8/18 16:29 上线后 8/19 起大量出现 |
| 5 | delegate 子代理会话（design-engineer 等，mc=2~16） | refs 全量记录（refs_len == messages_count）——小请求不受影响 |

## 量化

### 条数与占比

| 日期 | 请求数 | zero-refs（bigRequest） | 占比 | 涉及 session |
|------|--------|------------------------|------|--------------|
| 08-17（上线前） | 4,367 | 0 | 0% | — |
| 08-19 | 5,629 | 2,605 | 46.3% | 19 |
| 08-20（至 21:41） | 5,495 | 2,233 | 40.6% | 18 |

### Agent 分布（8/20 top）

`Backend 754 · Frontend 468 · Manager 323 · Nebula 203 · qa-backend 145 · tool-engineer 121 · qa-frontend 97 · swift-dev 66`——全是**长会话工作 agent**（mc=126~566，system 6~10 万字符），不是系统探测。

### 时间分布（8/20）

`00时 7 · 01时 293 · 02时 403 · 03时 265 · 04时 377 · 05时 313 · 06时 1 · 10时 148 · 11时 168 · 12时 24 · 13时 234`——跟随夜间 batch + 白天工作时段，**无固定周期**，排除健康检查/周期探测。

### Token 成本

| 日期 | 总 input tokens | 其中 bigRequest | 占比 | bigRequest 均值 |
|------|----------------|-----------------|------|-----------------|
| 08-20 | 448,712,759 | 364,271,815 | 81.2% | 163,131/条 |
| 08-19 | 520,762,489 | 424,776,426 | 81.6% | 163,061/条 |

这些 token 花在**真实任务的长上下文请求**上（降采样不改变 API 成本，只省日志 IO）。降采样上线后 sse.jsonl 从 ~432MB/天（8/17: 395MB）降到 ~170MB/天（8/19: 169MB，8/20: 178MB），objects 文件增速同步放缓——降采样目的达成。

## 排除的候选假设

| 假设 | 排除依据 |
|------|---------|
| a. 模型探测（probe） | probe 不携带 22 个 tools、thinking budget 64k、126~566 条消息、10 万+ input token |
| b. agent 冷激活探测 | 同上；且 agent 分布覆盖全部主力 agent，与激活时机无关 |
| c. 健康检查周期探测 | 时间分布跟随人类工作时段（夜间 batch + 白天），无固定周期；8/17 前不存在 |
| d. 消息组装 bug（用户消息丢失） | input_tokens 10 万+ 证明 messages 完整送达模型；消息仅在**日志侧**不落盘 |
| （真凶） | 日志降采样（见上） |

## 建议（非 bug，可读性改进）

1. **加显式标记字段**（低成本、防再误读）：`bigRequest` 时在 fullEntry 写 `"messages_omitted": true`（或 `"downsampled": "big_request"`）。本次排查需要请求-响应配对才能定性，就是因为日志里没有这个自描述字段。
2. **request 记录带上 input_tokens**（现在只在 response 里）或 `messages_chars` 总长度——审计时免配对。
3. Token 成本角度无需为「探测」降频——没有探测请求；真正的大头是长会话每轮全量重发 163k input（占总量 81%），那是 compaction/上下文管理策略的议题，不在本次范围。

## 排查方法备注（复用）

- `*_full.jsonl` 混存 `type=request` 与 `type=response` 两类行，统计前必须按 type 过滤
- `message_refs` 是 content-addressed 引用（hash → `objects/<hash>.json`），空列表 ≠ 无消息，须与 response 的 `input_tokens` 配对判断
