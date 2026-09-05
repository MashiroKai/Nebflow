# 工具结果 TTL 清理（Tool Result TTL）设计 — 2026-08-20

- 日期：2026-08-20
- 状态：实施中（Backend，分支 feat/tool-result-ttl）
- 前作考古：FastMicroCompact（e32d78ba 一系）——git -S "ToolResult" core/compact 命中
- 关联：2026-08-20 成本主题（system-reminder 重构同日，44K token/半天）
- 勘误（v1.1，qa FAIL 修复）：首版 keepRecent 用 `Set.takeRight` 取保留窗——哈希迭代序非消息序，
  「最近 N」实为「任意 N」（真实 UUID 类 toolUseId 下常态随机）；首版 spec 用 tu-N 系 id
  哈希序恰好=插入序而巧合绿。修复=candidates 改消息序列表（distinct），takeRight 取真最近 N；
  回归 spec 换真实感 UUID ids 并经「故意注入 bug 验红」验证载力。§3 的构造性保证自本版起成立。

## 0. 问题

长会话里大量陈旧的大块工具结果（Read/Bash/Grep 输出）持续占据 LLM 上下文。
现有 FastMicroCompact 已做一轮治理，但有一个结构性缺陷与两个盲区：

1. **结构性缺陷——平面混淆**：FastMicroCompact 直接改写 `state.messages` 并持久化。
   旧工具结果在**会话文件**里被占位符覆盖（不可逆），前端回看历史时原内容已丢。
   「LLM 上下文要省」被实现成「历史记录也要丢」。
2. **盲区一——无开关**：参数硬编码（keep 5、cold 30min、savings 0.6），无法按需关闭/调参。
3. **盲区二——触发面窄**：仅在「冷缓存 + 总量省 40%+」时一次性触发；
   60min 空闲后回访的长会话若不满足 savings 比例则完全不清理。

## 1. 语义三分（本设计核心约束）

三个平面严格分离，各自独立取舍：

| 平面 | 内容 | 本设计 |
|------|------|--------|
| **会话文件**（durable） | 完整工具结果，永久保留 | **永不触碰**——清理只发生在请求构建时（request-only），`state.messages` 与落盘历史零改动 |
| **LLM 上下文**（request） | 每 turn 实际发给模型的 messages | TTL 清理作用面：过期+超大+超出保留窗的工具结果在**请求副本**中替换为自描述占位符 |
| **前端显示**（display） | 用户回看的历史 | 不受影响（读会话文件全量）。长输出折叠显示是**独立的前端课题**——参考 Settings 里 STT advance 的折叠面板模式，交 Frontend 另立任务，不在本批 |

对比：FastMicroCompact 作用在会话文件平面（连带 LLM）；本设计作用在请求平面（文件不动）。

## 2. 机制

### 配置（nebflow.json 顶层 `toolResultTtl` 节，默认关）

```json
{
  "toolResultTtl": {
    "enabled": false,
    "ttlMinutes": 60,
    "keepRecent": 5,
    "minChars": 2000
  }
}
```

- `enabled: false` 默认——**默认关**（用户裁定）。非法配置 fail-safe 视为关闭（镜像 FreezeSchedule.load 模式）。
- `ttlMinutes`：结果年龄超过该值才可清理（按消息 timestamp）。
- `keepRecent`：最近 N 个可清理工具的结果永不清理——同时是**turn 中途安全性的构造性保证**（见 §3）。
- `minChars`：小于该长度的结果不清理（小结果信息密度通常高，清理收益低）。

### 清理规则（纯函数，请求构建时调用）

作用对象：`CompactableTools`（Read/Bash/Grep/Glob/WebSearch/WebFetch/Curl/Edit/Write——
与 FastMicroCompact 同集，皆为**可重跑**工具；Mail/SubTask 等结果不在内，v1 保守）。

一个工具结果被替换，当且仅当全部满足：

1. `enabled` 且非 compact/save/ask turn（压缩需要全量输入做摘要；save turn 提取记忆需要全量；ask fork 语义要答当前问题）
2. **冷缓存**：距最后一条 assistant 消息 > 30min（复用 `FastMicroCompact.DefaultColdAfterMs`）
3. 结果年龄 > `ttlMinutes`
4. 在全部候选中序位超出 `keepRecent` 保留窗
5. 内容长度 > `minChars`

替换文本（自描述，模型可自救）：

```
[Tool output archived: age 87min, 18432 chars — full content kept in session
history; re-run the tool if you need it again]
```

**与 FastMicroCompact 的 savings-ratio 对比**：FastMicroCompact 有 40% 最小节省比例，
因为它永久销毁信息，小收益不值得；本设计 request-only + 冷缓存门控下**任何收缩都是净节省**
（缓存已死，前缀本来就全价重读），故不设比例门。两机制并存：TTL 是平面干净的 opt-in 版，
迁移路径（后续批次）：TTL 验证稳定后 FastMicroCompact 退役其「改写持久化历史」部分。

### 落点

- `core/compact/ToolResultTtl.scala`：config case class + fail-safe load + `cleanRequestMessages(messages, cfg, nowMs): Option[List[Message]]`
- `SharedResources.toolResultTtl`（带禁用默认值——既有测试构造零改动，镜像 freezeScheduleRef 模式）
- `GatewayMain` 启动时 fail-safe 加载注入
- `AgentCore.pipeLlmCall`：request 构建处 `messages = cleaned.getOrElse(stateWithReminder.messages) ++ contextMsg ...`——**stateWithReminder 本身不动**

## 3. turn 中途 vs idle 取舍（写明）

**决策：每个 LLM 请求构建时都跑清理判定，但清理条件使 turn 中途天然安全。**

- turn 中途（工具循环内）的请求：当前 turn 的工具结果必然是**最近的**，落在 `keepRecent`
  保留窗内 → 永不被清理。不需要额外的「mid-turn 禁用」开关，条件 4 构造性保证。
- 真正被清理的只有「上一轮会话/很久之前」的结果——它们此刻不被任何进行中的逻辑消费。
- 不选「只在 idle 时一次性清理」的原因：idle 触发需要额外的调度器与状态（上次清理位点），
  而冷缓存门控（条件 2）已经把实际生效时机约束在「回访」场景——效果等价于 idle 后清理，
  机制上零新增状态。
- **风险与边界**：清理改变请求前缀 → 本请求缓存全 miss。冷缓存门控使该成本为零
  （缓存已过期，全价重读是基线）。若未来放开 cold-cache-only（配置项预留讨论，v1 未做），
  必须补「节省量 > 尾部重读成本」的估算——本批不做。

## 4. 验收（二值断言）

1. 默认（无配置/非法配置）：清理零生效（enabled=false fail-safe）
2. enabled + 过期 + 超窗 + 超大：请求中该结果为占位符
3. **会话文件三分**：清理生效后，落盘会话消息仍含完整原内容（wiring 级断言）
4. keepRecent 窗内结果不被清理（含 turn 中途场景）
5. 冷缓存内（30min 内有 assistant 消息）不清理
6. 非 compact/save/ask 门控生效
7. 全量测试绿

## 5. 明确不做（本批）

- 前端折叠显示（STT advance 模式）——Frontend 独立任务
- 非可重跑工具（Mail/SubTask/Delegate 结果）——v1 保守
- hot-cache 下激进清理 + 成本估算——见 §3
- FastMicroCompact 退役——TTL 稳定后的后续批次
