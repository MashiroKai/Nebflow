---
name: nebflow-cache
description: LLM provider prompt cache 命中率优化域知识——指标定义（cache_read/cache_write）、数据源（router 日志/usage-records 五维聚合）、已知事实（间隔悬崖衰减/107 网关低命中/冷启动路由/基线上下文精简）、排查方法（先量化再动手、前缀漂移假设检验）、system prompt 共享前缀维护纪律。Use when 排查命中率下降、分析 cache 指标、统一 system prompt 前缀、调保活/冷启动策略、精简基线上下文。
when_to_use: LLM prompt cache 优化与监控任务：命中率下降排查、cache_read/cache_write 指标分析、system prompt 前缀改动评估（改前必读——任何前缀改动都使全部 agent 共享缓存失效）、Token 看板数据联动。
language: zh
status: active
last_verified: 2026-09-03
---

# Prompt Cache — LLM 缓存命中率优化域知识

## 指标定义

- **命中率** = cacheReadInputTokens / (cacheReadInputTokens + inputTokens)——cache_read 是省钱关键。
- **cache_write**（cache_creation_input_tokens）：写入本身也是成本，警惕重复写入。

## 数据源

1. `~/.nebflow/logs/router/<YYYY-MM-DD>_summary.jsonl` — 每请求 usage 字段（cache_read/cache_creation/input/output tokens）+ agent / request_model / resolved_model / timestamp / status_code。
2. `~/.nebflow/usage-records/usage-records.jsonl` — 结构化 LlmUsageRecord：provider/model/agent/sessionId/时间 + 四 token 桶；五维聚合 `GET /api/usage/aggregate?dim=provider|model|agent|hour|day`。
3. 历史基线分析：`~/.nebflow/docs/Nebflow/20260818_cache-miss-analysis.md`（93.8% 基线、间隔悬崖、107 网关低命中、方案 A–E 对比）。

## 已知事实（分析沉淀，勿重复推导）

- **命中率随请求间隔悬崖式衰减**：<5min 92.8% → 5-30min 53.3% → >30min 4.4%（provider 缓存 TTL ~30min）。
- 大上下文 agent 活跃时命中率反而高（system 60-65K + 200+ 条历史，94%+）；真正的问题是「大上下文 × 长间隔」的唤醒组合。
- **107 免费网关命中率系统性偏低**（litellm 代理层缓存策略弱）——但 107 免费，命中率低 ≠ 要换，先算成本当量。
- flow 一次性 agent 命中率 65-75%（生命周期短、每次新上下文）——优化空间有限，不投入。
- 已落地机制：**冷启动路由**（>30min 闲置 agent 首唤醒走 107 免费网关，`llm.coldStart.*` 配置，idleThresholdMs 与缓存 TTL 对齐）；**基线上下文精简**（组件 283K→109K，-62%）。

## 职责域

1. **持续监控**：定期从 router 日志与 usage-records 算命中率（总体/按 agent/模型/小时/provider），对比目标，识别衰减趋势与异常窗口。
2. **命中率下降排查**：定位根因——间隔变长（闲置唤醒）、前缀漂移（system prompt 变动破坏共享前缀缓存）、特定 agent/model/gateway；用数据定位到 agent×模型×时间，**先验证「被改坏」假设再转机制性根因**。
3. **统一 system prompt 前缀策略**：维护 `~/.nebflow/prompts/system-prefix-for-all.md`（及各前缀变体）的稳定共享前缀——多 agent 共前缀复用 provider 前缀缓存。**任何前缀文件改动都使所有 agent 的共享缓存失效，改动前必须评估缓存代价。**
4. **活跃 agent 保活策略**：保活请求 ~1K/次，仅对高频 agent 值得；警惕保活失控变成新消耗源；与冷启动路由联动调参。
5. **会话历史按需加载**：闲置 agent 压缩历史 → 唤醒输入更小；优先评估与现有生命周期压缩机制合并，不重复造机制。
6. **基线上下文精简**：继续压 system prompt 基数（记忆渐进披露收紧/工具描述精简/规则压缩），每轮改动后量化验证。

## 工作方式

1. **先量化再动手**：任何优化前先算当前基线（命中率、计费当量、按维度分解），假设必须可证伪。
2. 方案按性价比分级：P0 零代码（调度/配置）→ P1 代码/提示词 → P2 需产品决策；涉用户偏好或跨项目影响先请示，不自行决定。
3. **验证闭环**：改动前留存基线快照；落地后对比前后命中率/成本数据证明收益；无效改动回滚。
4. 数据局限如实说明（如 session_id 缺失时用 agent 时间序列近似），不夸大统计置信度。
