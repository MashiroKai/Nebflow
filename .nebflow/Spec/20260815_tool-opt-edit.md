# Nebflow Edit/Write 大内容编辑优化方案

> 分析范围：`/Users/dev/Claude code/Nebflow`（archive/scala 分支，HEAD 7408a4b4）
> 约束：纯分析，不改代码。目标读者：Manager + 实施者
> 日期：2026-08-15

---

## 0. 结论先说

**保留现有 Edit/Write 不动，新增 `MultiEdit` 工具（JSON 数组原子批量编辑），并在失败返回中附带行号提示。**

三条理由：

1. **零训练兼容最稳**：Nebflow 后端是 GLM / DeepSeek / Kimi 等通用模型，JSON 结构化 tool-call（原生 function calling）是这些模型遵循度最高的形式；自定义补丁文本协议（apply_patch / SEARCH-REPLACE）依赖模型格式遵循能力，弱模型错误率高。
2. **一次解决两个痛点**：同文件多处修改从「N 次串行往返」变「1 次调用」，从「并行执行结果不确定」变「锁内顺序应用、全成或全不成（原子）」。
3. **工作量今晚可完成**：核心匹配逻辑全部复用现有 `StringMatcher`（四级模糊匹配资产），新增代码约 200 行 + 测试 150 行，预计 4-6 小时。

---

## 1. 现状分析

### 1.1 工具链现状

| 工具 | 参数 schema | 关键限制 | 位置 |
|------|------------|---------|------|
| Edit | `file_path`, `old_string`, `new_string`, `replace_all` | MaxEditFileSize = 1GiB；空 `old_string` = 建文件 | `src/main/scala/nebflow/core/tools/EditTool.scala` |
| Write | `file_path`, `content` | MAX_WRITE_BYTES = 512KB，**整文件覆写** | `src/main/scala/nebflow/core/tools/WriteTool.scala` |
| Read | `file_path`, `offset`, `limit`, `filter` | 输出 `cat -n` 行号格式（`"N\t行内容"`），MAX 2000 行 / 512KB | `src/main/scala/nebflow/core/tools/ReadTool.scala` |

注册表 `registry.scala:13-17` 只有 Read/Write/Edit 三个，**没有任何批量编辑工具**。

现有资产（本方案的复用基础）：

- `StringMatcher.scala`：四级匹配（精确 → 引号归一 → 空白不敏感 → 引号+空白），外加 `preserveQuoteStyle`——这已经比多数同类产品的 Edit 更宽容；
- `DiffUtil.scala`：行分隔符检测/保持、`makeUnifiedDiff`（2 行上下文统一 diff）；
- `EditTool.scala:137-139`：`fileLockManager.withWriteLock` 文件级写锁；
- `EditTool.scala:218-224`：mtime + content 双重外部修改检查。

### 1.2 Token 成本量化

估算基准：Scala 代码约 40 字符/行 ≈ 11 tokens/行。

![Token 成本对比](/tmp/edit-opt-tokens.svg)

| 场景 | 现状最优路径 | 成本 | 问题 |
|------|-------------|------|------|
| 改 1 行（300 行文件） | Edit | ~60 tok | 无问题，Edit 已是最优 |
| | Write | ~3,300 tok | **55 倍浪费**——模型有时偷懒用 Write |
| 移动/重写 100 行块 | Edit | ~2,200 tok | `old_string` + `new_string` **双传输**，匹配失败则全部作废 |
| | Write | ~3,300 tok | 更贵，且受 512KB 限制 |
| 10 处分散小改 | Edit × 10 | ~700 tok + **10 次往返** | 往返才是大头：每次往返整个对话历史重发（见下） |

**往返放大是隐藏的最大成本**：每次工具调用返回后，完整对话历史（假设当时已 20K tokens）随下一轮请求重发。10 次串行 Edit ≈ 累计处理 200K+ tokens；MultiEdit 一次调用只需 1 次往返。上表和图表均为单次输出 token 估算（不含往返放大），计入往返后 MultiEdit 优势更大。

### 1.3 失败模式与根因

**模式一：大段内容 old/new 双传输，失败整次作废。**
模型搬迁 100 行代码块时，`old_string` 和 `new_string` 内容高度重叠，token 接近翻倍。一旦 `old_string` 与实际内容有偏差（即使有四级模糊匹配兜底），本次调用全部作废，模型要带着完整 old/new 重试——错误成本 = 全额。

**模式二：Write 整文件覆写放大一切。**
改一行也要传全文件。300 行文件的 55 倍 token 浪费只是中等情形；更危险的是大文件超过 512KB 后 Write 直接不可用，而 Edit 的 1GiB 上限宽裕得多——两个工具的体积上限不匹配，没有引导模型优先用 Edit 的机制。

**模式三：同文件多个并行 Edit，结果不确定。**
根因在 `src/main/scala/nebflow/agent/AgentCore.scala:585`：

```scala
freshResults <- filteredCalls.parTraverse { call => ... }
```

同一 LLM 响应里的多个工具调用**并行执行**。写锁（`EditTool.scala:138`）保证不会撕裂写，但带来两个后果：

- **顺序不确定**：哪个 Edit 先拿到锁是随机的；
- **快照失效**：模型基于同一次 Read 的快照生成全部 old_string。后拿到锁的 Edit 读到的是「前序 Edit 已落盘」的内容——若它的 `old_string` 覆盖了前序 Edit 改过的区域，`StringMatcher` 匹配失败，报 `old_string not found`。

实际表现就是用户观察到的「多个 Edit 发出去，只有部分生效 / 最后一个生效，其余报错」，然后模型被迫重新 Read、重新发 Edit——又回到模式一的浪费循环。

> 注意：模糊匹配失败的主因**不是缩进**（四级匹配已覆盖空白归一），而是并行快照失效和模型记忆漂移。

---

## 2. 业界方案对比

### 2.1 四种流派

| # | 方案 | 代表产品 | 形式 | 资料可信度 |
|---|------|---------|------|-----------|
| 1 | **str_replace + 批量数组** | Claude Code（Edit 至今为主力，MultiEdit 曾作为批量补充）；Cursor | JSON tool-call，`edits: [{old,new}]` | 高（广泛公开记录 + 本仓库同构） |
| 2 | **SEARCH/REPLACE block** | Aider 的 diff 格式 | 文本协议：`<<<<<<< SEARCH` / `=======` / `>>>>>>> REPLACE` | **高（一手资料，已抓取 aider 官方 edit-formats 文档）** |
| 3 | **补丁文本格式** | OpenAI Codex 的 apply_patch | `*** Begin Patch` / `*** Update File` / `@@ 上下文锚点` / `±行` | 中（训练期知识，格式细节可靠；一手抓取失败——raw.githubusercontent SSL / 官方文档 500） |
| 4 | **行号范围编辑** | 部分本地产品 | JSON：`start_line/end_line/new_content` | 中（业界共识：模型数行号不可靠） |

Aider 官方文档一手结论（已验证）：

- diff 格式（SEARCH/REPLACE block）**最省 token**，因为它无 JSON 转义开销、无重复上下文；
- 但它对模型能力有要求——**GPT-4 级模型表现最好，弱模型会退回 whole-file 格式**（Aider 自己的 fallback 链就是证据）；
- whole 格式（整文件重写，等价于我们的 Write）是弱模型的兜底，token 最贵。

Claude Code 的演进路径也说明问题：str_replace Edit（简单可靠）→ MultiEdit（批量，后因与并行执行语义纠缠而淘汰）→ 回归单 Edit + 行号提示辅助（Read 输出行号 + 错误消息含定位信息）。**批量编辑的价值是真实的，但「批量」应该由工具内部保证原子性，而不是把多个单发调用丢给并行调度器**——这正是 Nebflow 当前架构（parTraverse）踩中的坑。

### 2.2 对比表（评估维度 = 本项目约束）

| 维度 | ① MultiEdit JSON 数组（推荐） | ② SEARCH/REPLACE | ③ apply_patch | ④ 行号范围 |
|------|------|------|------|------|
| **零训练兼容（GLM/DeepSeek/Kimi）** | **高**——原生 function calling，JSON 数组是强项 | 中——格式在开源语料常见，但分隔符纪律要求高 | 低-中——自定义格式，语料少 | 高（JSON）但**语义脆**（见下） |
| Token 成本 | 中——old/new 仍双传，但往返 1 次 | **低**——无 JSON 转义、无冗余 | 低 | **最低**——大块移动只传行号 |
| 失败恢复 | **好**——单 edit 粒度定位 + 行号提示自愈 | 中——整块失败可整块重试 | 中——格式错则整个补丁解析失败 | **差**——行号漂移一次，后续全错且难自愈 |
| 实现工作量 | **低**——完全复用 StringMatcher/DiffUtil/锁 | 中——自写文本 parser，匹配逻辑可复用 | 中-高——自写 parser + 格式校验 | 低-中——但需要行号↔内容对齐处理 |
| 长块搬迁成本 | 仍双传（短板，接受，见 §4.6） | 同样双传 | 同样双传 | 最优 |

**关键判断**：②③ 的 token 优势在「单次调用内」，但 ① 把「N 次往返」压成「1 次」，往返放大收益（§1.2）远超格式本身的节省。且 ②③ 需要自建 parser，放弃 StringMatcher 现成资产，把风险从「模型抄错内容」转移到「模型写错格式」——对国产通用模型是净恶化。

---

## 3. 零训练兼容性约束

Nebflow 不控制模型训练，方案必须对任意通用模型鲁棒：

1. **GLM / DeepSeek / Kimi 全部原生支持 function calling**，JSON 结构化输出的遵循度是它们被验证最强的能力（各家 API 文档明确承诺 tool-call 格式）；
2. 自定义文本协议的可靠性取决于该格式在训练语料中的密度。SEARCH/REPLACE 因 Aider 开源多年语料较广；apply_patch 语料集中在 2025 后且 OpenAI 生态内。**没有一个能保证**；
3. 行号定位的问题不是格式而是**行为**：模型对「第 137 行是什么」的记忆随文件变长迅速劣化，且一次编辑后所有行号漂移——错误是级联的。Aider/Claude Code 都只用行号做**辅助提示**而非主定位手段，这是业界趋同的选择；
4. 结论：**主定位 = 内容匹配（StringMatcher 资产），辅助 = 行号提示，批量 = JSON 数组**。

---

## 4. 推荐方案：MultiEdit + 失败行号提示

### 4.1 设计原则

- **原子性**：edits 数组要么全部应用并写盘，要么一个都不应用。杜绝「改了一半」；
- **顺序语义**：`edits[i]` 在 `edits[0..i-1]` 应用后的 buffer 上匹配——模型按 Read 快照给的一串互不重叠的编辑，天然全部成功；
- **单锁单读单写**：一次 `withWriteLock`、一次读盘、一次写盘，从结构上绕开 parTraverse 并发问题（MultiEdit 是单次 tool call，不与任何兄弟调用竞争）；
- **复用而非重写**：匹配用 StringMatcher，diff 用 DiffUtil，行尾保持用现有逻辑。

### 4.2 参数 schema

```json
{
  "name": "MultiEdit",
  "description": "对同一文件按顺序应用多处编辑。原子性：任一编辑失败则全部不生效。每个 edit 在前序 edit 应用后的内容上匹配。适合同一文件 2 处以上修改；单处修改请用 Edit。",
  "parameters": {
    "file_path": { "type": "string", "description": "绝对路径" },
    "edits": {
      "type": "array",
      "minItems": 1,
      "maxItems": 50,
      "items": {
        "type": "object",
        "properties": {
          "old_string": { "type": "string", "description": "要替换的精确内容，唯一匹配；空字符串仅允许出现在首个 edit（建文件）" },
          "new_string": { "type": "string", "description": "替换后的内容" },
          "replace_all": { "type": "boolean", "default": false }
        },
        "required": ["old_string", "new_string"]
      }
    }
  },
  "required": ["file_path", "edits"]
}
```

设计要点：

- `maxItems: 50` 防御性上限（Claude Code 的 MultiEdit 同量级）；
- description 本身就是零训练提示工程——明确「顺序应用」「原子」「单处用 Edit」，引导模型正确分流；
- 不加 `expected_content_hash` 之类的乐观锁参数：增加模型负担，现有 mtime 检查已够。

### 4.3 执行语义（伪代码）

```
MultiEdit.run(file_path, edits):
  withWriteLock(file_path):
    original = read(file_path)                    // 一次读盘（空 old_string 首条 = 建文件分支）
    mtime    = stat(file_path)
    buffer   = original
    for i, edit in edits:                         // 顺序应用
      match = StringMatcher.findActualString(buffer, edit.old_string)   // 复用四级模糊匹配
      if match is None:
        return failure(i, closestCandidates(buffer, edit.old_string))   // 不写盘
      if !edit.replace_all && countOccurrences(buffer, edit.old_string) > 1:
        return failure(i, "old_string 不是唯一匹配，请扩大上下文或设 replace_all")  // 不写盘
      buffer = replace(buffer, match, preserveQuoteStyle(edit.new_string))
    if buffer == original:
      return "没有产生任何变更（new_string 与 old_string 相同？）"      // 不写盘
    currentMtime = stat(file_path)
    if currentMtime != mtime && read(file_path) != original:
      return "File was modified externally. Please re-read and retry."   // 复用现有语义
    write(file_path, buffer)                      // 一次写盘，保持行分隔符
    recordRead / recordAgentModification / MemoryChangeNotifier        // 复用 Edit 的后处理链
    return "OK:UPDATED " + makeUnifiedDiff(original, buffer)           // 聚合 diff：对最终结果做一次，无中间态噪声
```

`failure(i, hint)` 的消息格式（自愈的关键，见下节）：

```
ERROR: edits[3] failed: old_string not found.
已完成前 3 个编辑的预览（未写盘，文件未修改）。
最接近的候选位于第 42-58 行（相似度 87%）:
  | 42  def process(items: List[Item]): Result =
  | ...
文件共 300 行。建议 Read(offset=38, limit=25) 后修正 old_string 重试。
```

### 4.4 失败自愈路径（零训练下最重要的一环）

错误消息本身承担「纠错教练」角色，让模型下一轮必然走对：

1. **失败序号** `edits[3]`——模型知道改哪一条，不用全部重发；
2. **最近候选 + 行号 + 相似度**——直接告诉模型「你要找的东西大概在这里」，省一次盲猜；
3. **可执行的下一步** `Read(offset=38, limit=25)`——与 ReadTool 的 offset/limit 参数直接呼应，形成闭环；
4. **明确「未写盘」**——杜绝模型误以为部分成功而跳过重试。

这条路径完全复用现有能力：Read 已有行号输出（`ReadTool.scala` 的 `cat -n` 格式），StringMatcher 已能给出候选（缺的只是把候选位置换算成行号返回——一个小增强）。

### 4.5 预期行为变更（改造前后对比）

![现状 vs 改后](/tmp/edit-opt-diagram.svg)

用大白话说：

- **现在**：模型想改同一个文件的 5 个地方，要么发 5 个 Edit（并行跑，运气好全成、运气差部分失败再补一轮），要么干脆 Write 重写全文件（token 爆炸）。失败时它只收到「not found」，不知道去哪找，经常再猜一次又错。
- **改后**：模型发 1 个 MultiEdit，5 处修改一次说完。成功——文件一次改完，返回一张完整 diff；失败——文件一个字没动，返回消息里写清楚「第 3 条编辑没找到，最像的内容在第 42 行附近，建议你 Read 一下那片区域」。模型照着提示走，通常一轮就能修复。

### 4.6 明确不做的事（及理由）

| 不做 | 理由 |
|------|------|
| 删除/替换现有 Edit | 单处修改 Edit 仍是最优路径（~60 tok）；且存量 agent 提示词全在引用它，动了是全局回归风险 |
| 行号范围编辑作为主方案 | 模型数行号不可靠 + 级联漂移 + 与 StringMatcher 资产不兼容；其 token 优势场景（超长块搬迁）由 Write 兜底即可 |
| apply_patch / SEARCH-REPLACE 文本协议 | 自建 parser 成本 + 弱模型格式错误率 + 放弃模糊匹配资产；收益（格式内 token 节省）小于往返压缩收益 |
| 修改 AgentCore 的 parTraverse | 并行执行对 Read/Grep 等只读工具是正确设计；MultiEdit 单调用天然规避写冲突，不动调度器风险最小 |
| Write 与 Edit 体积上限对齐（512KB→1GiB） | 本批次不做。更好的解法是在 Write 工具 description 里加「文件超过 N 行时优先用 Edit/MultiEdit」，零代码成本 |
| MultiEdit 支持建文件（空 old_string 首条） | 可留作 follow-up。首版聚焦批量编辑，建文件走 Write，语义更清晰 |

---

## 5. 实施工作量评估（今晚批次，4-6 小时）

### 5.1 涉及文件与改动范围

| 文件 | 改动 | 预估 |
|------|------|------|
| `src/main/scala/nebflow/core/tools/EditTool.scala` | 抽取纯函数 `applyEditToBuffer(buffer, oldString, newString, replaceAll): Either[EditFailure, String]`（匹配 + 唯一性 + 引号保持），Edit 自身改为调用它——**行为不变的重构** | ~80 行重构 |
| `src/main/scala/nebflow/core/tools/MultiEditTool.scala`（新增） | schema + 锁 + 顺序循环 + 原子写盘 + 聚合 diff + 失败消息（含行号提示） | ~200 行 |
| `src/main/scala/nebflow/core/tools/registry.scala:13-17` | 注册 MultiEdit（+1 行） | 1 行 |
| `src/main/scala/nebflow/core/tools/StringMatcher.scala`（可选小增强） | 匹配失败时返回最近候选的**行号范围**（现在只有内容） | ~30 行 |
| agent 系统提示词（若工具列表有静态描述处） | 加入 MultiEdit 使用引导 | 若干行 |
| `src/test/scala/.../MultiEditToolSpec.scala`（新增） | 见 5.2 | ~150 行 |

### 5.2 测试点

1. 3 个 edits 互不重叠 → 全部应用，diff 正确，文件一次写盘；
2. `edits[2]` old_string 失效 → **文件内容与 mtime 均不变**（原子性核心断言）；
3. 前序 edit 改变了后续 edit 的匹配区域（顺序语义）→ 后续 edit 按新 buffer 匹配成功；
4. old_string 多处出现且 replace_all=false → 该 edit 失败，报「非唯一匹配」；
5. 四级模糊匹配在 MultiEdit 内同样生效（缩进/引号变体用例）；
6. 外部并发修改（写盘前 mtime 变化 + 内容变化）→ 报 modified externally；
7. 空数组 / 51 条 / buffer 无变化 → 对应错误消息；
8. 行分隔符保持（CRLF 文件进出一致）；
9. 与现有 EditToolSpec 回归：抽取重构后全部用例不变绿不合并。

### 5.3 验收标准

- [ ] **编译**：`sbt compile` 通过，零新警告；
- [ ] **单测**：`sbt test` 全绿——现有用例 0 回归 + 新增 MultiEdit 用例 ≥ 9 条全过；
- [ ] **原子性**：用例 2 的断言写成「失败后文件字节级相同」（非仅语义相同）；
- [ ] **并发冒烟**：真实启动 Nebflow（`sbt run` 或等效），同一响应里对同一文件并发发 1 个 MultiEdit + 1 个 Edit，断言写锁串行化、无撕裂写、最终内容符合其中一种合法顺序（说明：这是现有锁语义，MultiEdit 不引入新并发行为）；
- [ ] **端到端冒烟**：真实 agent 会话（GLM 或 DeepSeek 任一后端）给一个「把这个文件里 3 处 XX 改成 YY」的任务，观察模型主动选择 MultiEdit（而非 3 次 Edit 或 1 次 Write）、单轮完成、返回 diff 可读；
- [ ] **失败自愈验证**：人为构造一次 old_string 失配，确认返回消息含行号提示，且模型下一轮据此修正成功。

---

## 6. 风险与边界

| 风险 | 概率 | 缓解 |
|------|------|------|
| 模型在单处修改时误用 MultiEdit（多一层包装浪费） | 低 | description 明示「单处用 Edit」；多花的 ~15 tok 可忽略 |
| edits 之间区域重叠导致顺序敏感 | 中 | 这与模型逐个发 Edit 的现状语义一致，无恶化；失败消息可指出重叠 |
| 50 条上限被长重构打爆 | 低 | 模型自然分多次 MultiEdit，每次原子 |
| 提示词中工具列表变长挤占上下文 | 低 | 一个工具的描述 ~100 tok |

**边界**：本方案不解决「超长代码块搬迁的 old/new 双传输」（§4.6 第 2 行）。该场景的正确解是模型先 Read 目标区域、用行号引用的专用 MoveTool 或结构化 AST 编辑——那是另一个量级的工程，且收益场景频率低，不进今晚批次。

---

## 附：本方案引用的代码位置

| 位置 | 内容 |
|------|------|
| `AgentCore.scala:585` | `filteredCalls.parTraverse` —— 并行执行根因 |
| `EditTool.scala:137-139` | `withWriteLock` 包裹完整 read-match-write |
| `EditTool.scala:189-224` | 内容归一、模糊匹配、mtime 双重检查 |
| `StringMatcher.scala` | 四级匹配 + `preserveQuoteStyle` |
| `DiffUtil.scala` | 行分隔符保持、统一 diff、UTF-8 读写 |
| `registry.scala:13-17` | 工具注册表（现无批量编辑） |
| `ReadTool.scala` | `cat -n` 行号输出、offset/limit —— 行号提示的配套 |
