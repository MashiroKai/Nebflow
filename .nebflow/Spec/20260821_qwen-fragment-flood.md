> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# qwen 碎片 WARN 刷屏定性：纯日志噪声，零数据污染

> 阶段文档（诊断，完成即冻结）· 2026-08-21 05:20 · 排查范围：`archive/scala` @ 6d4cd863，只读分析
> 现象：`WARN nebflow.llm.openai - degenerate tool-call fragment (empty id/name) treated as continuation: {...}` 以每秒多条速率刷屏（04:27 重启后 40 分钟 3932 条，报告时点已 6151+ 且仍在涨）

## 结论（先读这段）

| 问题 | 结论 |
|------|------|
| 定性 | **纯日志设计噪声。合并逻辑正确，数据零污染，工具调用 100% 健康执行。** "degenerate" 这个词定性错了——qwen 把参数流延续帧的 id/name 写成字面空字符串（`""`）而非按 OpenAI 规范省略字段，这是它的**正常线上行为**，不是异常帧 |
| 直接原因 | 5e109ef8 把这类帧改道 continuation 合并（修复正确），bf62becd 让 WARN 真正落地（也正确）——但 **WARN 挂在了每个参数增量帧上**：qwen 每个工具调用的参数 JSON 分 ~26 块流式下发，每块触发一条 WARN。速率 = qwen 工具调用 token 吞吐 × 26 |
| 触发面 | 100% 是 Vision preset agents（qwen 是该 preset 的 **preferred** 模型，不是 fallback）。夜间 html-deck-studio + nebflow-project 前端工作产生 195 个 qwen 请求 / 223 个工具调用 |
| 修复方向 | 日志聚合：每个工具调用在 finish flush 时汇总**一条** WARN（帧数 + 字节数 + 首帧原文样本），不砍内容只改聚合粒度（符合「日志完整性优先」裁定） |

![WARN 速率时间线](assets/20260821_qwen-fragment-flood-rate.svg)

---

## 1. 背景：昨晚的修复链

- **5e109ef8**（08-20 21:52）：DashScope qwen 偶发「id 和 name 为字面空串但 arguments 有效」的工具调用帧。旧代码 `(Some, Some)` 匹配把它们当 start，状态被 `("", "", args)` 覆盖 → flush 出 `ToolCall(name="")` → 白名单全灭 → 两天全员工具死亡重试风暴。修复：空 id/name 帧改道 continuation 分支（只追加参数、不 start、不覆盖），并加 WARN 记录原始帧
- **bf62becd**（08-20 22:12）：qa 发现 `IO.delay(logger.warn(...))` 构造了 `IO[IO[Unit]]` 但外层执行后内层被丢弃——WARN 是死日志。修复：直接序列化 `logger.warn`，并加 logback ListAppender spec 钉死回归

今天 00:28:34 出现过 2 条 WARN（参数是 `{"command":"echo test","description":"probe"}`——手工探针，验证修复部署）。04:27 网关重启后，04:30 起 Vision preset agents 恢复工作，WARN 开始持续刷屏。

## 2. 证据链

### 2.1 Q1 定性：qwen 的空 id/name 延续帧是正常流式行为，合并正确

**协议语义**：OpenAI 流式规范中，工具调用的首个 delta 携带 `id` + `function.name`（+ 可选首段 arguments），后续参数增量 delta 只带 `index` + `function.arguments`、**省略** id/name。DashScope compatible-mode 的 qwen 不省略字段，而是显式写 `"id":"","name":""` —— 等价语义，消费端应视同省略。

**生产数据实证**（这是比文档更硬的证据）：

重组 04:30:18.033–18.194 的 9 条连续 WARN 碎片（nebflow.log 原文，只做 JSON 反转义拼接）：

```
{"file_path": "/Users/k`  +  `aiyu/.neb`  +  `flow/teams/html`
+ `-deck-studio`  +  `/agents/visual`  +  `-reviewer/memory.md`
+  `"`  +  `}`
= {"file_path": "/Users/dev/.nebflow/teams/html-deck-studio/agents/visual-reviewer/memory.md"}
```

与 router 日志（`2026-08-20_full.jsonl`，UTC 20:30:18 = 本地 04:30:18）中 request `1dfefea7` 的**最终合并结果逐字节一致**：

```
content_block_start: {type: tool_use, id: call_0bb0ee61600b4919835bfd30, name: Read}
delta_tool_json:     {"file_path": "/Users/dev/.nebflow/teams/html-deck-studio/agents/visual-reviewer/memory.md"}
```

三个关键推论，每一条都有代码路径背书（`OpenAiAdapter.scala:443-481`）：

1. **最终 ToolCall 的 id/name 非空** ⇒ 状态表只能由「id+name 双非空」的 start 分支写入 ⇒ **每个工具调用都存在合法 start 帧**，空 id/name 帧全部是 start 之后的参数增量
2. **合并后 JSON 完整合法** ⇒ continuation 分支的 `sb.append` 顺序无丢失、无覆盖
3. **如果 start 帧晚于延续帧到达**，延续帧会因状态为空被静默丢弃（`case None => (m, Nil)`）→ JSON 必然残缺。实测 0 例残缺 ⇒ start 永远先到

### 2.2 Q2 合并健康度：窗口内零故障

对洪泛窗口（本地 04:27–05:14，router 日志 UTC 2026-08-20T20:27+）全部 qwen 响应做全量校验（`2026-08-20_full.jsonl`，`full` 字段解析）：

| 指标 | 数值 |
|------|------|
| qwen 响应总数 | 195 |
| 含 tool_use 的响应 | 188 |
| tool_use 总数（合并后） | 223（Bash 99 / Read 69 / Edit 36 / Grep 8 / Mail 6 / Write 5） |
| 空 name 的 tool_use | **0** |
| 空 id 的 tool_use | **0** |
| input 不是合法 JSON 对象的 | **0** |
| 空响应（无 text/thinking/tool_use） | **0** |
| 下游 "Tool not available" | **0**（仅有的 2 处 grep 命中：一处是碎片参数内容里恰好含该词，一处是本排查自己的命令回显） |
| 窗口内工具执行 ERR | **0**（唯一疑似命中实为 `Mail(...)[INTERRUPT] OK` 的子串误匹配） |

端到端链路抽查：上述 9 碎片 Read 调用 → 04:30:18.758 `visual-reviewer Tool Read(memory.md)` 成功执行。Frontend 在 04:30 还通过 Mail 向 Manager 汇报「TTL 面板断点恢复」——Vision agents 全程在正常干活。

窗口内仅有的 3 个异常是 qwen provider 级超时（04:33 / 04:41 / 04:49，各 <15s 自愈），与碎片合并无关（超时发生在 HTTP 层，不产生部分合并）。

**两种碎片形态的占比**（5990 条 WARN 统计）：

| 形态 | 占比 | 定性 |
|------|------|------|
| ② 带真实参数内容（1–53 字符，中位数 7） | 98.6%（5898 条） | 正常参数流增量——每个工具调用平均 26.4 条 |
| ① 全空（`arguments:""`） | 1.4%（81 条） | qwen 偶发空参数帧，无数据载荷，无害 |

### 2.3 Q3 谁在 qwen 上：Vision preset 的 preferred，不是 fallback

`~/.nebflow/model-presets.json`：`Vision` preset = **preferred: qwen/qwen3.8-max**，fallbacks: [kimi/k3-256k, zhipu/GLM-5V-Turbo]。qwen 是该 preset 的首选模型——**不存在「zhipu 挂了逃到 qwen」**：general preset 的 preferred（zhipu/GLM-5.3）窗口内全程 UP 无 DOWN 事件。

窗口内 qwen 流量的 agent 分布（router 日志按请求统计）：

| Agent | qwen 请求数 | 归属 |
|-------|------------|------|
| visual-reviewer | 89 | html-deck-studio（agent.json: preset=Vision） |
| Frontend | 68 | nebflow-project（preset=Vision） |
| qa-frontend | 30 | nebflow-project（preset=Vision） |
| Explorer（前一次委托） | 6 | 04:27–05:00 的一次 qwen 委托会话 |
| html-builder | 2 | html-deck-studio（preset=Vision） |

旁证：Vision 的两个 fallback 在 04:33 双双 DOWN（kimi「tool call id duplicated」、GLM-5V-Turbo「套餐未开放」）——但 qwen 本身 UP，fallback 根本没被触发。本次排查会话的 Explorer 走的是 zhipu/GLM-5.3（general preset），不在污染源内。

### 2.4 Q4 量级分析

- **总量**：00:28 探针 2 条；04:30 起持续，报告时点（05:20）6151 条且仍在增长
- **时间分布**：均匀持续刷（每分钟 15–370 条），峰值 05:09 达 1038 条/分钟（≈17 条/秒）——多 Vision agent 并发回合 + 长参数工具调用（本排查自己的长 Bash 命令在 05:09 后也在贡献）
- **独立请求数**：窗口内 195 个 qwen 请求、223 个工具调用，平均每调用 26.4 条 WARN
- **机制**：每参数增量帧一条 WARN ⇒ 速率 ∝ qwen 工具调用 token 吞吐。昨天同类流量（修复前）没有这条日志，所以是「新日志 × 既有流量」叠加出的刷屏，不是 qwen 行为突变

## 3. 根因

`OpenAiAdapter.scala:474-477`——WARN 挂在 `(Some(""), Some(""))` 分支的**每次帧到达**上：

```scala
case (Some(_), Some(_)) =>
  logger.warn(                                        // ← 每个 continuation 帧一条
    s"degenerate tool-call fragment (empty id/name) treated as continuation: " +
      tc.noSpaces.take(160)
  ) *> continueFragment(acc, index, args)
```

qwen 的正常参数流（每调用 ~26 帧）全部命中该分支。合并逻辑（continueFragment）是对的；错的是日志粒度和措辞（"degenerate" 名不副实——这是 qwen 的标准延续帧格式）。

次级问题：WARN 行不含 session/agent/request 关联字段，多流并发时（io-compute 多线程交错）无法归因到具体请求，只能靠时间窗猜。

## 4. 修复方案（日志聚合，不砍内容）

> 约束：用户裁定「日志完整性优先——不能砍内容只能改聚合方式」。级别维持 WARN，仅改触发粒度。

**改法**（`OpenAiAdapter.scala`，`sendMessageStream`/`processOpenAiData` 内）：

1. 状态元组 `(String, String, StringBuilder)` 扩展为 `(id, name, sb, degenCount: Int, degenSamples: ListBuffer[String])`（或并行 `Ref[IO, Map[Int, Int]]` + samples，避免元组膨胀）
2. `(Some(""), Some(""))` 分支不再直接 WARN：计数 + 采样原文（每帧仍取 `noSpaces.take(160)`，保留前 3 帧原文，其余只计数累计字节数）
3. WARN 移到 finish flush 点（`finishReason.contains("tool_calls")` 触发 `toolCallState.getAndSet(Map.empty)` 处，行 484/506 两处 flush 都要覆盖）——**每个工具调用一条汇总**：

```
WARN nebflow.llm.openai - qwen streamed tool args as 26 empty-id/name continuation frames
(412 bytes, tool Read index 0, session 168609d7, agent visual-reviewer);
first frames: {"id":"","...","arguments":"{\"file_path\": \"/Users/k"} | ... | ...;
merged args 214 chars parsed OK
```

内容保全核算：现状 = 每调用 26 行 × 各 ≤160 字符 ≈ 3.4KB；聚合后 = 1 行，含帧数、字节数、首 3 帧原文（480 字符）+ 合并校验状态。逐帧全文从 5e109ef8 起就从未完整落盘（每帧 160 截断），聚合不损失既有信息量；若需逐帧全文，追加方案 B：把全部帧原文写入 LlmLogWriter 的 per-request SSE 记录（已有 request_id 关联结构），WARN 汇总行指向该 request_id。

**同时修**：汇总行带上 `params.sessionId`/`params.agentId`（makeMeta 已有这两个字段，直接复用），解决多流并发不可归因问题。

**措辞**：`degenerate` → `empty-id/name continuation`（qwen 常态帧，别再用事故词汇吓人）。

**注意**：bf62becd 的 log-appender spec（断言 WARN 触发）需同步改为断言「每流一条汇总且含帧数」——纯 chunk 断言检测不到日志回归的教训保持有效。

## 5. 验收条件（二值）

1. **冒烟**：真实启动网关（`sbt run` 或既有启动方式），派发一个含工具调用的 Vision-preset agent 任务（走 qwen 流式），工具调用成功执行
2. **刷屏消除**：该任务期间 `grep -c "degenerate tool-call fragment\|empty-id/name continuation" ~/.nebflow/logs/nebflow.log` 增量 ≤ 工具调用数（每调用至多 1 条汇总）；对照本次基线 26.4 条/调用
3. **内容等价**：汇总行包含帧数 N>0、参数总字节数、工具名、session/agent 关联字段、至少首帧原文（≤160 字符截断，与修复前单帧信息量一致）
4. **回归**：`OpenAiAdapterSpec` 全绿，含改造后的 log-appender spec（断言每流一条汇总、帧数正确、无逐帧 WARN）
5. **健康度不回退**：重跑本报告 §2.2 的校验脚本（python，读 `*_full.jsonl` qwen 响应）——tool_use 空 name/id 计数 = 0、input 非法计数 = 0
6. **旧病不复发**：degenerate-start spec（5e109ef8 引入）保持通过——空 id/name 帧仍走 continuation 合并、不产生 `ToolCall(name="")`

## 附录：证据复现命令

```bash
# WARN 计数与每分钟分布
grep -c "degenerate tool-call fragment" ~/.nebflow/logs/nebflow.log
grep "degenerate tool-call fragment" ~/.nebflow/logs/nebflow.log | awk '{print substr($2,1,5)}' | sort | uniq -c

# 碎片重组（04:30:18 窗口 9 条拼接 = 最终 merged input）
grep "degenerate tool-call fragment" ~/.nebflow/logs/nebflow.log | grep "04:30:1[89]"

# qwen 响应健康度（注意 router 日志时间戳是 UTC：本地 04:27 = UTC 20:27 前一日）
# 见 §2.2 表格，脚本按 full.jsonl 的 type=response + full.tool_use 解析

# 谁在 qwen
grep '"model":"qwen/qwen3.8-max"' ~/.nebflow/logs/router/2026-08-20_summary.jsonl | grep '"type":"request"'

# provider 状态
grep -E "marked DOWN|recovered" ~/.nebflow/logs/nebflow.log | awk '$1=="2026-08-21" && $2>="04:27:00"'
```

代码定位：`src/main/scala/nebflow/llm/providers/OpenAiAdapter.scala:443-501`（碎片分类与 flush）；`src/main/scala/nebflow/core/LlmLogWriter.scala:423-447`（router 日志只落最终 ToolCallChunk，ToolArgDelta 不落盘——这也是为何 WARN 是唯一原始帧捕获点）。
