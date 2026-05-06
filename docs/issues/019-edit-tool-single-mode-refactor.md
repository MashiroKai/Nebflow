# Refactor: EditTool 单模式重构 + 模糊匹配与鲁棒性增强

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`templates/_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | backend |
| 优先级 | P1-高 |
| 创建日期 | 2025-07-14 |
| 目标日期 | 未确定 |
| 预估工时 | 12h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

将 EditTool 从三模式（replace / line_replace / insert）重构为 Claude Code 风格的单模式（old_string → new_string），同时引入字符串模糊匹配、双重并发安全、结构化 diff 输出和安全校验。

---

## 背景

当前 EditTool 设计了三种编辑模式（`replace`、`line_replace`、`insert`），意图是给 LLM 提供行号操作能力以减少大块文本传输。但实际使用中发现：

1. LLM 使用 `line_replace` / `insert` 时，仍需先 Read 获取行号，传回的行号经常与文件实际行号偏差（尤其是 LLM 在单轮内多次编辑时行号漂移）
2. 三模式增加了 LLM 的认知负担，mode 选错的失败率不低
3. 严格精确匹配对引号（curly quotes）、尾空白等差异零容忍，在非代码文件（markdown、配置）上失败率高
4. 并发修改检测仅靠 mtime 快照，在 macOS/Windows 上存在云同步/杀毒软件导致的误报

Claude Code 经过大规模验证的单模式 + 模糊匹配方案证明：**一个宽松的匹配策略比多个精确模式更可靠**。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

1. **三模式复杂度未带来预期收益**：`line_replace` 和 `insert` 本质上是 `replace` 的语法糖，LLM 完全可以用 `old_string` 限定范围来达到同样效果。三模式增加了 schema 复杂度和 mode 路由逻辑，但实际编辑成功率并未因此提升。

2. **精确匹配过于脆弱**：当文件含弯引号 `""''`、尾空白、或 CRLF 行尾时，LLM 输出的 `old_string` 与文件内容不匹配，直接失败。用户需要反复重试，浪费 token 和时间。

3. **mtime-only 检测在跨平台场景不可靠**：macOS 上 iCloud、Time Machine；Windows 上杀毒软件、OneDrive 都可能改变 mtime 而不改变内容，导致编辑被误拒。

4. **缺少关键安全检查**：无文件大小上限（OOM 风险）、无 `.ipynb` 拦截（损坏笔记本）、无敏感内容检测。

| Concern | Current Location | Should Be |
|---------|------------------|-----------|
| 模式路由 (Mode enum + 3个 doXxx 方法) | EditTool.scala | 删除，统一为单路径 |
| 字符串匹配 (精确 indexOf) | EditTool.doReplace | 模糊匹配：引号归一化 + 尾空白剥离 |
| 并发安全 (mtime snapshot) | EditTool (各 doXxx) | mtime + 内容双重比较 |
| Diff 输出 (自定义格式) | DiffUtil.makeDiff | 结构化 unified diff hunks |
| 安全校验 | 无 | 文件大小、笔记本拦截、敏感词检测 |

---

## [创建] 目标

将 EditTool 简化为仅 `old_string → new_string` 的单模式编辑工具，同时引入四项鲁棒性增强：模糊字符串匹配、双重并发安全、结构化 diff 输出、安全校验。编辑成功率显著提升，模式选择失败归零。

---

## [创建] 范围

### In Scope

- [ ] 移除 `line_replace` 和 `insert` 模式，统一为 `replace` 单模式
- [ ] 更新 `inputSchema`（移除 mode/line 相关字段，简化为 Claude Code 风格）
- [ ] 实现 `findActualString` 模糊匹配（引号归一化、尾空白剥离）
- [ ] 实现 `preserveQuoteStyle`（引号风格保持）
- [ ] 重构并发安全：mtime + 内容双重比较，替代 mtime-only
- [ ] 添加文件大小上限检查（1 GiB）
- [ ] 添加 `.ipynb` 拦截（引导至 NotebookEdit 工具，即使该工具尚不存在，也先拦截）
- [ ] 重构 DiffUtil 输出为结构化 unified diff format（含 hunks 信息）
- [ ] 更新 tool description prompt
- [ ] 更新 `summarize` / `summarizeResult`

### Out of Scope

- [ ] 不实现 Claude Code 的 desanitization（XML tag 反转义）— Nebflow 的 API 不做 XML sanitization，无此需求
- [ ] 不实现 UTF-16LE BOM 检测 — 当前仅服务 JVM 环境，统一 UTF-8
- [ ] 不实现 LSP 集成、VSCode 通知、Skills 发现 — 属于编辑器集成层面，与编辑逻辑无关
- [ ] 不实现 `fileHistoryTrackEdit` 文件历史备份 — 独立功能，另开 issue
- [ ] 不实现 `inputsEquivalent` 重试去重 — Nebflow 的 agent loop 不需要此功能
- [ ] 不实现 `normalizeFileEditInput` 的 de-sanitize 路径 — 原因同上
- [ ] 不实现 `gitDiff` 集成 — 独立功能
- [ ] 不修改 WriteTool — WriteTool 职责不同（全量写入），不在本次范围

---

## 设计原则

1. **单模式优先**：一个宽松的匹配策略比多个精确模式更可靠。删除 `line_replace` 和 `insert`，所有编辑操作统一为 `old_string → new_string`。
2. **模糊但不随意**：模糊匹配仅在精确匹配失败后触发，且只做确定性的归一化（引号、空白），不做概率性猜测。
3. **结构化输出**：diff 结果从自定义格式迁移到标准 unified diff hunks，为未来 UI 渲染提供结构化数据。
4. **向后兼容的 API**：`replace_all` 保持不变；新增的 `old_string=""` 新建文件语义对齐 Claude Code。

---

## 目标架构

### EditTool 重构后的执行流程

```
input(file_path, old_string, new_string, replace_all?)
  │
  ├─ 1. validateInput（前置校验，不读文件内容）
  │     ├─ old_string == new_string → 提前返回
  │     ├─ .ipynb → 拒绝
  │     ├─ readTracker.hasBeenRead → 必须先读
  │     └─ old_string.isEmpty && replace_all → 拒绝（replace_all 无意义）
  │
  ├─ 2. call（执行编辑）
  │     ├─ fileLockManager.withWriteLock {
  │     │     ├─ if old_string.isEmpty && file not exists → 创建新文件
  │     │     ├─ if old_string.isEmpty && file exists && content.trim.nonEmpty → 拒绝（防覆盖）
  │     │     ├─ 文件大小 > 1 GiB → 拒绝（在读内容之前检查，防 OOM）
  │     │     ├─ 读文件内容 + 记录 mtime
  │     │     ├─ findActualString(content, old_string)
  │     │     │     ├─ 精确匹配 → 直接使用
  │     │     │     └─ 引号归一化后匹配 → preserveQuoteStyle(new_string)
  │     │     ├─ 唯一性检查（multiple matches + !replace_all → 报错）
  │     │     ├─ 并发安全双重检查：mtime + content 比较
  │     │     ├─ 执行替换
  │     │     ├─ 写入文件
  │     │     └─ 生成结构化 diff
  │     │   }
  │     └─ 返回 EditResult(filePath, added, removed, hunks, diff)
  │
  └─ 3. summarizeResult → 人类可读摘要
```

### 新增组件

| 组件 | 文件 | 职责 |
|------|------|------|
| `StringMatcher` | `tools/StringMatcher.scala` | `findActualString` + `preserveQuoteStyle` + `normalizeQuotes` |
| `EditResult` | `tools/EditResult.scala` | 结构化编辑结果（hunks, diff 文本, 行数统计） |

### 修改组件

| 组件 | 文件 | 变更 |
|------|------|------|
| `EditTool` | `tools/EditTool.scala` | 删除 Mode enum / doLineReplace / doInsert；简化为单路径 |
| `DiffUtil` | `tools/DiffUtil.scala` | 新增 `makeUnifiedDiff` 返回结构化 hunks |

---

## [结束] 详细变更

### 变更点 1：移除三模式，统一为单模式

**Current:** EditTool 定义了 `Mode` 枚举（Replace / LineReplace / Insert），通过 `resolveMode` 路由到三个独立方法 `doReplace` / `doLineReplace` / `doInsert`。inputSchema 包含 `mode`、`start_line`、`end_line`、`insert_after_line`、`content` 等字段。

**New:** 移除 `Mode` 枚举和 `resolveMode`。inputSchema 简化为 `{ file_path, old_string, new_string, replace_all }`。`call` 方法只有一条路径。`old_string=""` 表示新建文件（与 WriteTool 互补，WriteTool 是全量覆盖，Edit 的空 old_string 是增量语义）。

```scala
val inputSchema = JsonObject.fromIterable(
  List(
    "type" -> "object".asJson,
    "properties" -> io.circe.Json.obj(
      "file_path" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "...".asJson),
      "old_string" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "...".asJson),
      "new_string" -> io.circe.Json.obj("type" -> "string".asJson, "description" -> "...".asJson),
      "replace_all" -> io.circe.Json.obj("type" -> "boolean".asJson, "description" -> "...".asJson)
    ),
    "required" -> io.circe.Json.arr("file_path".asJson, "old_string".asJson, "new_string".asJson)
  )
)
```

**Rationale:** LLM 用 `old_string` 框定上下文比用行号更可靠。行号在多轮编辑中漂移，而文本内容不变。单模式降低 LLM 认知负担和 schema 复杂度。

---

### 变更点 2：字符串模糊匹配（StringMatcher）

**Current:** `doReplace` 使用 `original.contains(oldString)` 精确匹配，零容错。

**New:** 新建 `StringMatcher` 对象，实现两步匹配：

```scala
object StringMatcher:
  private val CurlyQuotes = Map(
    '\u2018' -> '\'',  // '
    '\u2019' -> '\'',  // '
    '\u201C' -> '"',   // "
    '\u201D' -> '"',   // "
  )

  /** Step 1: 精确匹配。Step 2: 引号归一化后匹配。返回文件中的实际字符串。 */
  def findActualString(content: String, search: String): Option[String] =
    if content.contains(search) then Some(search)
    else
      val normalizedContent = normalizeQuotes(content)
      val normalizedSearch  = normalizeQuotes(search)
      val idx = normalizedContent.indexOf(normalizedSearch)
      if idx >= 0 then Some(content.substring(idx, idx + search.length))
      else None

  /** 引号归一化：弯引号 → 直引号 */
  private def normalizeQuotes(s: String): String =
    s.map(c => CurlyQuotes.getOrElse(c, c))

  /** 当 old_string 通过引号归一化匹配到文件中的弯引号版本时，
    * 对 new_string 应用相同的弯引号风格。 */
  def preserveQuoteStyle(oldRaw: String, oldActual: String, newStr: String): String =
    if oldRaw == oldActual then newStr
    else applyCurlyQuotes(newStr, oldActual)

  private def applyCurlyQuotes(s: String, ref: String): String = ...
end StringMatcher
```

**Rationale:** LLM API 会将弯引号归一化为直引号，导致 `old_string` 与文件中的弯引号不匹配。引号归一化是确定性变换，不引入歧义。尾空白剥离暂不实现（可在后续 issue 中追加）。

---

### 变更点 3：双重并发安全

**Current:** 在读文件后记录 `mtime`，写文件前再次检查 mtime 是否变化。若变化则拒绝编辑。

```scala
// Current — mtime-only
val mtime = Files.getLastModifiedTime(filePath)
// ... 修改 ...
if Files.getLastModifiedTime(filePath) != mtime then Left(...)
```

**New:** mtime 检查作为快速路径；若 mtime 变化但内容未变（云同步等），重新读文件内容做字符串比较放行。

```scala
// New — mtime + content 双重比较
val mtime = Files.getLastModifiedTime(filePath)
val content = DiffUtil.readFile(filePath)  // 快照原始内容
// ... 修改 ...
val currentMtime = Files.getLastModifiedTime(filePath)
if currentMtime != mtime then
  // mtime 变了，但内容可能没变（云同步/杀毒软件等）
  val currentContent = DiffUtil.readFile(filePath)
  if currentContent != content then
    Left(ToolError("File was modified externally. Please re-read and retry."))
  else
    // mtime 变但内容相同，放行（继续写入）
    Right(())
```

**Rationale:** Windows/macOS 上云同步、杀毒软件、Time Machine 会改变 mtime 但不改变内容。纯 mtime 检查导致误报。双重检查消除误报，同时保持对真正并发修改的检测能力。

---

### 变更点 4：结构化 Diff 输出

**Current:** `DiffUtil.makeDiff` 返回自定义格式的字符串：

```
  1 |unchanged line
  2 |-removed line
  2 |+added line
  3 |unchanged line
```

**New:** 新增 `makeUnifiedDiff` 返回结构化 case class，同时保留文本 diff 用于日志。

```scala
case class DiffHunk(
  oldStart: Int, oldLines: Int,
  newStart: Int, newLines: Int,
  lines: List[String]  // unified diff lines: " " / "-" / "+" prefix
)

case class EditResult(
  filePath: String,
  addedLines: Int,
  removedLines: Int,
  hunks: List[DiffHunk],
  diffText: String   // human-readable for tool result
)
```

**Rationale:** 结构化输出为后续前端 diff 渲染提供数据基础，文本格式保持向后兼容（现有 tool result 消费者不受影响）。

---

### 变更点 5：安全校验

**Current:** 仅检查文件是否存在和是否已读。无大小限制，无文件类型拦截。

**New:** 安全校验分两阶段执行——不需要文件内容的检查在锁外前置完成，需要文件内容的检查在锁内完成：

**阶段 1 — 锁外前置检查（validateInput，不读文件内容）：**

1. **Notebook 拦截**：`file_path.endsWith(".ipynb")` → 拒绝，提示使用 NotebookEdit 工具
2. **readTracker.hasBeenRead** → 必须先读文件
3. **空 old_string + replace_all=true** → 拒绝（`replace_all` 对空字符串无意义，语义冲突）
4. **old_string == new_string** → 提前返回（无变更）

**阶段 2 — 锁内检查（在 writeLock 内，读文件内容之前/之后）：**

5. **文件大小上限**：`Files.size(filePath) > 1 GiB` → 拒绝（**在读内容之前**检查，防 OOM）
6. **空 old_string + 文件已存在且有内容** → 拒绝（防止误创建覆盖）

```scala
// 阶段 1：锁外前置校验（不需要读文件内容）
private def validateInput(filePath: Path, input: JsonObject): Either[ToolError, Unit] =
  val oldString = input("old_string").flatMap(_.asString).getOrElse("")
  val newString = input("new_string").flatMap(_.asString).getOrElse("")
  val replaceAll = input("replace_all").flatMap(_.asBoolean).getOrElse(false)

  if filePath.toString.endsWith(".ipynb") then
    Left(ToolError("File is a Jupyter Notebook. Use the NotebookEdit tool to edit this file."))
  else if oldString.isEmpty && replaceAll then
    Left(ToolError("replace_all cannot be used with empty old_string."))
  else if oldString == newString then
    Left(ToolError("old_string and new_string are identical — no change needed."))
  else Right(())

// 阶段 2：锁内校验（需要文件大小/内容）
private def validateInLock(filePath: Path, content: String, oldString: String): Either[ToolError, Unit] =
  // 文件大小检查在读内容之前
  val size = Files.size(filePath)
  if size > MaxEditFileSize then
    Left(ToolError(s"File too large to edit (${formatSize(size)}). Maximum is ${formatSize(MaxEditFileSize)}."))
  // 空 old_string + 非空文件
  else if oldString.isEmpty && content.trim.nonEmpty then
    Left(ToolError("Cannot create new file - file already exists. Use the Write tool to overwrite."))
  else Right(())
```

**Rationale:** 这些是 Claude Code 经过大规模验证的必要安全护栏。1 GiB 上限防止大文件 OOM。Notebook 是 JSON 结构，字符串替换会破坏其完整性。

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 移除 line_replace/insert 后，LLM 需要适应新 schema | Medium | 更新 system prompt 提供清晰的编辑指导；旧模式调用会收到明确的错误提示 |
| 模糊匹配误匹配（引号归一化后匹配到错误位置） | Low | 仅在精确匹配失败后触发；保留 `replace_all` 唯一性检查不变 |
| 内容比较对大文件的性能影响 | Low | 仅在 mtime 变化时触发（快速路径不比较内容）；1 GiB 上限控制最坏情况 |
| 结构化 diff 输出破坏现有 tool result 消费者 | Low | 保留 `diffText` 字段兼容现有消费者；新增 `hunks` 字段为扩展点 |

---

## 兼容性

- **向后兼容：** No — inputSchema 变更（移除 mode / line 相关字段）
- **行为变更：**
  - 不再支持 `line_replace` 和 `insert` 模式
  - `old_string` 匹配变为模糊（引号归一化）
  - 并发修改检测从 mtime-only 变为 mtime + content
  - 新增文件大小上限和 notebook 拦截
- **迁移指南：**
  - 前端 tool schema 配置需更新（移除 mode/line 字段）
  - 已有的 agent system prompt 引用 line_replace/insert 的需更新

---

## 关联

- Related to `012-extensible-tool-cards.md` — 前端 tool 卡片可能需要适配新的 inputSchema
- Related to `010-plugin-system-architecture.md` — 插件系统中若包含自定义编辑工具，需了解本次变更

---

## 迁移步骤

### Phase 1: 新建 StringMatcher + EditResult（Low）

1. 创建 `StringMatcher.scala` — 实现 `findActualString`、`normalizeQuotes`、`preserveQuoteStyle`
2. 创建 `EditResult.scala` — 定义 `DiffHunk` 和 `EditResult` case class
3. 在 `DiffUtil.scala` 中新增 `makeUnifiedDiff` 方法
4. 编写 StringMatcher 单元测试

### Phase 2: 重构 EditTool 核心逻辑（Medium）

1. 移除 `Mode` 枚举、`resolveMode`、`doLineReplace`、`doInsert`
2. 简化 `inputSchema` 为单模式
3. 重写 `call` 方法：集成 StringMatcher + 双重并发安全 + 安全校验
4. 更新 `summarize` / `summarizeResult`
5. 更新 `description` prompt

### Phase 3: 验证与清理（Low）

1. 确认所有现有 EditTool 测试通过（适配新 schema）
2. 补充模糊匹配、并发安全、安全校验的测试
3. 更新 agent system prompt 中的编辑相关指导

---

## [结束] 成功标准

- [ ] EditTool 仅接受 `{ file_path, old_string, new_string, replace_all }` 四个字段
- [ ] `old_string=""` + `replace_all=true` 被拒绝
- [ ] `old_string=""` + 文件不存在 → 创建新文件
- [ ] `old_string=""` + 文件已存在且非空 → 被拒绝
- [ ] 精确匹配失败后，引号归一化匹配能成功匹配含弯引号的文件内容
- [ ] `preserveQuoteStyle` 在匹配到弯引号时，new_string 保留弯引号风格
- [ ] mtime 变化但内容相同时，编辑不被误拒
- [ ] mtime 变化且内容变化时，编辑被正确拒绝
- [ ] 文件 > 1 GiB 时编辑被拒绝
- [ ] `.ipynb` 文件编辑被拒绝
- [ ] 结构化 diff 输出包含 hunks 信息
- [ ] All existing EditTool tests pass after adaptation

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 确认 Mode 枚举已移除
grep -rn "Mode\." src/main/scala/nebflow/core/tools/EditTool.scala
# Expected: no output

# 确认 line_replace / insert 不再出现在 inputSchema
grep -rn "line_replace\|insert_after\|Mode\." src/main/scala/nebflow/core/tools/EditTool.scala
# Expected: no output

# 确认 findActualString 已引入
grep -rn "findActualString" src/main/scala/nebflow/core/tools/
# Expected: matches in EditTool.scala and StringMatcher.scala

# 确认双重检查已引入
grep -rn "content.*!=" src/main/scala/nebflow/core/tools/EditTool.scala
# Expected: at least one match showing content comparison
```

### 运行时检查

- [ ] 测试含弯引号的文件编辑（法语、中文引号等）
- [ ] 测试 mtime 变化但内容不变的并发场景
- [ ] 测试大文件编辑被拒绝
- [ ] 测试 .ipynb 编辑被拒绝
- [ ] 测试旧模式参数（mode=line_replace）返回清晰错误

### Code Review Checklist

- [ ] StringMatcher 的模糊匹配仅在精确匹配失败后触发
- [ ] 双重并发检查的 content 比较在 writeLock 内执行
- [ ] inputSchema 不包含 mode / start_line / end_line / insert_after_line / content 字段
- [ ] DiffUtil.makeUnifiedDiff 返回 List[DiffHunk]
- [ ] 安全校验在 readTracker 检查之后、文件写入之前执行

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `tools/EditTool.scala` | **Rewrite** | 移除三模式，简化为单路径；集成 StringMatcher、双重安全、安全校验 |
| `tools/StringMatcher.scala` | **Create** | 模糊字符串匹配：引号归一化 + 风格保持 |
| `tools/EditResult.scala` | **Create** | 结构化编辑结果 case class（DiffHunk, EditResult） |
| `tools/DiffUtil.scala` | **Modify** | 新增 makeUnifiedDiff 返回 List[DiffHunk] |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已完成
- [ ] 重复 — 重复 issue：
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘（可选）

> `[结束]` 时填写。若关闭原因为"重复"/"无法复现"/"已过时"，可跳过。

| 问题 | 回答 |
|------|------|
| 目标达成了吗？ | |
| 有什么意外？ | |
| 代码质量改善可量化吗？ | |
| 工时预估偏差原因？ | |
