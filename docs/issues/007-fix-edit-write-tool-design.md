# Fix Edit/Write Tool Design Defects

> 基于 `docs/issues/templates/refactor.md` 模板。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | backend |
| 优先级 | P1-高 |
| 创建日期 | 2026-05-04 |
| 目标日期 | 未确定 |
| 预估工时 | 4h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

修复 `EditTool` / `WriteTool` 的描述-实现裂缝、模式耦合与边界处理缺陷，使其行为与文档声明一致、错误更明确、调用更安全。

---

## 背景

`EditTool` 与 `WriteTool` 是 LLM 写入工作区的唯一入口。两者在 `core/tools/` 下并行演化，目前已暴露多处矛盾：

- Tool description 中向 LLM **承诺**了未实施的安全约束（"必须先 Read 才能 Write/Edit"）。
- `EditTool` 把 3 种语义（按字符串替换 / 按行号替换 / 行号插入）通过可选字段叠加在同一 schema 上，调用方无法静态判断模式合法性。
- 仅 `EditTool` 检查 mtime，`WriteTool` 不检查；行计数与行分隔符处理多处重复且口径不一致。

这些问题独立出现都不算 P0，但它们共同削弱了"模型写文件"这条关键路径的可预期性，必须一次梳理清楚。

---

## [创建] 动机

### 当前问题清单

| Concern | Current Location | Should Be |
|---------|------------------|-----------|
| "必须先 Read" 约束 | 仅写在 description 字符串里 | 由调用层强制 *或* 从 description 中删除（实现与文档一致） |
| Edit 三种模式判别 | `call` 内部根据可选字段优先级分支 | 显式 `mode` 字段 + schema 约束 |
| 文件并发写保护 | 仅 `EditTool` 检查 mtime；`WriteTool` 无 | 两者一致 |
| 行分隔与行计数 | `EditTool` / `WriteTool` / `DiffUtil` 各算一次 | `DiffUtil` 统一封装 |
| `new_string` 为空 | 在 line-replace 中被拒（`"new_string is required"`) | 允许（用于纯删除多行） |
| Write 无大小上限 | `WriteTool.call` 无校验 | 与 `ReadTool.MAX_FILE_BYTES` 对齐或独立配置 |
| `summarizeResult` 字符串协议 | 依赖正则解析自家 `OK: ...` 文案 | 结构化结果 *或* 单一来源的格式常量 |

### 关键代码片段

**问题 1 — 描述与实现的裂缝**

`WriteTool` description 第 17 行：

```scala
// WriteTool.scala
val description = """Writes a file to the local filesystem.

Usage:
- This tool will overwrite the existing file if there is one at the provided path.
- If this is an existing file, you MUST use the Read tool first to read the file's contents.
  This tool will fail if you did not read the file first.
```

但 `WriteTool.call` 没有任何 read-tracker 校验。`grep -rn "ReadTracker\|hasBeenRead" src/main/scala` 返回空。LLM 被告知有约束，实际无人执行。

`EditTool.description` 同样写着 "You must use your Read tool at least once in the conversation before editing."，同样未实施。

**问题 2 — Edit 三模式耦合**

```scala
// EditTool.scala — 模式优先级在 call 内部隐式定义
startLineOpt match
  case Some(_) => /* line-replace 模式 */
  case None =>
    insertLine match
      case Some(_) => /* insert 模式 */
      case None    => /* string-replace 模式 */
```

JSON schema 仅 `required: ["file_path"]`，所有其他字段都是可选的。LLM 看到的是一个"任意字段组合都可能被接受"的工具，因而生成的 tool call 经常误用：例如同时给 `start_line` 和 `old_string`，或只给 `start_line` 不给 `new_string`。错误检测必须等到 `call()` 才能反馈。

**问题 3 — Write 无 mtime 检查 + 无大小限制**

```scala
// WriteTool.scala — 直接覆盖，无并发保护、无大小校验
val isNew = !Files.exists(filePath)
if isNew then
  DiffUtil.writeFile(filePath, content, "\n")
  Right(s"OK:CREATED $filePath")
else
  val original = DiffUtil.readFile(filePath)
  // ... 计算 diff，直接写入
  DiffUtil.writeFile(filePath, content, lineSep)
```

如果用户在 Write 调用与实际写入之间手动改动了文件，老内容直接丢失。`EditTool` 已经有 `Files.getLastModifiedTime(filePath) != mtime` 检查（虽然同样存在 TOCTOU 窗口），`WriteTool` 完全没有。

`content` 长度无上限，理论上可写入几 GB 字符串到磁盘。

**问题 4 — 行分隔与行计数三处重复**

```scala
// EditTool.scala 同一段函数内调用 split 4 次：
val lines = original.split("\\r?\\n", -1).toList         // 1
// ... 计算 diff
val (added, removed) = DiffUtil.lineStats(
  original.split("\\r?\\n").toList,                       // 2 — 注意没有 -1
  updatedStr.split("\\r?\\n").toList                      // 3
)
val diff = DiffUtil.makeDiff(short, original, updatedStr) // 4 (内部再 split 2 次)
```

`-1` 与默认 `split` 在文件以 `\n` 结尾时会产生不同的 line 数（默认会丢掉末尾空串）。`replacedCount` 计算与 `lineStats` 报告的 `removed` 在边界情况下不一致。

**问题 5 — `new_string` 为空被错误拒绝**

```scala
// EditTool.scala line-replace 模式：
val newString = input("new_string").flatMap(_.asString).getOrElse("")
if newString.isEmpty then Left(ToolError("new_string is required when using start_line"))
```

合法用例：用户希望把第 10–15 行整段删除，自然会传 `new_string=""`。当前实现拒绝该调用，迫使 LLM 改用别的 workaround（例如先用 string-replace 删除）。

**问题 6 — `OK:` 字符串契约**

```scala
// WriteTool.summarizeResult 用正则解析自家文案：
if result.startsWith("OK:CREATED") then "File created"
else if result.startsWith("OK:UPDATED") then
  val added = "(\\d+) added".r.findFirstMatchIn(result).map(_.group(1))
  // ...
```

只要 `call()` 返回串里多一个 "added" 字样（例如 diff body 含 "1 added line in scope"），统计就被打乱。

---

## [创建] 目标

让 `EditTool` / `WriteTool` 的 **行为与 description 一致**、**模式静态可判别**、**错误反馈在 schema 层就能给出**、**并发写有统一保护**、**行计数口径单一**。

---

## [创建] 范围

### In Scope

- [ ] 删除或实现 description 中的 "必须先 Read" 约束（推荐：从 description 中删除该承诺，直到引入真正的 read-tracker。原因见下方"权衡总结"）
- [ ] 给 `EditTool` 增加显式 `mode` 字段，schema 标注 `enum: [replace, line_replace, insert]`；保留 fallback 推断以兼容旧 prompt（`mode` 暂不进入 `required`，待 prompt 稳定后再升级，详见变更 2 Rationale）
- [ ] 给 `WriteTool` 增加 mtime 检查（与 `EditTool` 同款，已存在文件覆盖前比对）
- [ ] 给 `EditTool` insert 模式补 mtime 检查（当前仅 line-replace / replace 模式有；insert 缺失，与其他两种模式不一致）
- [ ] 给 `WriteTool` 增加 content 大小上限（默认与 `ReadTool.MAX_FILE_BYTES = 512KB` 一致；按 UTF-8 编码后字节数计算，与 `ReadTool` 口径一致；可后续配置化）
- [ ] 在 `DiffUtil` 中提供 `splitLines(content): List[String]` 单一切分入口（统一使用 `split("\\r?\\n", -1)`），`EditTool` / `WriteTool` / `DiffUtil.makeDiff` 全部改用它
- [ ] 修复 `EditTool` line-replace 模式拒绝空 `new_string` 的问题：当 `new_string == ""` 时，**完全删除**指定行范围（不留空行）
- [ ] 修复 `EditTool` insert 模式拒绝空 `content` 的问题：允许插入空字符串（即在指定位置插入一个空行，用于占位/分隔）
- [ ] 把 `WriteTool` / `EditTool` 的 `OK:...` 文案集中为 `private` 常量 + 共享格式函数：所有结果字符串只在一处生成，`summarizeResult` 用同一格式函数解析，消除散落的 `(\d+) added` 正则
- [ ] 顺手清理 `EditTool.summarizeResult` 中对 `result.contains("matches")` 的字符串嗅探（与变更 7 同一收口）

### Out of Scope

- [ ] **不**实现真正的 read-tracker。read-tracker 涉及 `ToolContext` 增加状态、跨工具共享，需要单独 issue 评估
- [ ] **不**新增 atomic write（write-to-temp + rename）。当前需求是修正描述与 mtime 检查，原子写是独立的健壮性议题
- [ ] **不**为 `WriteTool` 在覆盖前读取现有文件做大小校验（`Files.readString` 在 GB 级文件上 OOM 是已知问题）。本次只在 *写入侧* 设上限；读取侧的防护属独立议题，与变更 4 正交
- [ ] **不**改动 `DiffUtil.detectLineSep` 对纯 CR（Mac OS Classic）文件的判定；该场景近乎不存在
- [ ] **不**改动 `BashTool` / `ReadTool` / 其他工具
- [ ] **不**新增测试文件（项目当前不在 `src/test` 内维护单测；按 feedback `feedback_test_cleanup.md`，验证以编译 + 静态 grep + 手动运行为准）

---

## 当前架构

### 组件关系

| 组件 | 职责 | 当前问题 |
|------|------|---------|
| `EditTool` | 字符串/行替换、行插入 | 三模式靠可选字段优先级判别；mtime 检查存在但 split 口径不一致 |
| `WriteTool` | 整文件写入 | 无 mtime 检查；无大小上限；description 撒谎 |
| `DiffUtil` | 行分隔、写入、diff、行统计 | 行分隔与切分逻辑只封装了一半，`split` 散落各处 |
| `ReadTool` | 读取文件（含截断） | 与 Edit/Write 没有任何会话级耦合（无 read-tracker） |

---

## 设计原则

1. **Description = Implementation**：description 中向 LLM 承诺的约束必须有代码兜底；做不到就删
2. **Schema is the contract**：能在 schema 层被 LLM 看见的约束，就不要拖到 `call` 里报错
3. **Single source of truth for line splitting**：`DiffUtil` 是唯一切分点
4. **Defensive on Write**：写入是不可逆动作，mtime + 大小上限是最低门槛

---

## [结束] 详细变更

> 实施时按下文逐项落地；最终关闭前更新本节为"实际"。

### 变更 1：删除 description 中未实施的 Read 承诺

**Current (`WriteTool.scala`):**

```scala
val description = """Writes a file to the local filesystem.

Usage:
- ...
- If this is an existing file, you MUST use the Read tool first to read the file's contents.
  This tool will fail if you did not read the file first.
- ...
```

**New:**

```scala
val description = """Writes a file to the local filesystem.

Usage:
- ...
- Recommended: read the existing file first so the new content is informed by current state.
- ...
```

`EditTool.description` 同步删除 "You must use your Read tool at least once in the conversation before editing." 一行（替换为软建议）。

**Rationale:** description 是 LLM 行为的 prompt，不是断言。除非有真正的 read-tracker 兜底，承诺就是噪声 — 模型有时会跳过 Read，发现"什么也没发生"，于是学会忽略类似约束。

---

### 变更 2：`EditTool` 加显式 `mode` 字段

**Current:** mode 由 `start_line` / `insert_after_line` / `old_string` 是否出现决定。

**New:** schema 增加 `mode: { type: "string", enum: ["replace", "line_replace", "insert"] }`，并保留向后兼容（未传 `mode` 时按当前优先级推断）。

```scala
// EditTool.scala 简化片段
val mode = input("mode").flatMap(_.asString) match
  case Some("replace")      => Mode.Replace
  case Some("line_replace") => Mode.LineReplace
  case Some("insert")       => Mode.Insert
  case Some(other)          => return IO.pure(Left(ToolError(s"Unknown mode: $other")))
  case None                 => inferMode(input) // 旧逻辑作为 fallback

mode match
  case Mode.Replace      => doReplace(input, ctx)
  case Mode.LineReplace  => doLineReplace(input, ctx)
  case Mode.Insert       => doInsert(input, ctx)
```

每个分支自己校验自己需要的字段（例如 `LineReplace` 必须有 `start_line` 与 `new_string`），错误信息精确指向缺失字段。

**Rationale:**

- schema 显式 `enum` 让 LLM 看到合法模式集合；`required` 仍仅为 `["file_path"]`（因为各模式必填字段不同），由代码做精细校验
- 这是当前 schema 复杂度下的最佳折衷：JSON Schema 的 `oneOf` 在 Anthropic / OpenAI tool calling 上的支持参差，且会让 schema 体积膨胀
- **`mode` 暂为软引导**：未传 `mode` 时按当前优先级推断，向后兼容旧 prompt。待 prompt 更新覆盖率稳定（例如 90%+ 的调用主动传 `mode`）后，再单开小 issue 把 `mode` 列入 `required`

---

### 变更 3：`WriteTool` 与 `EditTool` insert 模式补 mtime 检查

**Current:** `WriteTool` 直接覆盖；`EditTool` insert 模式无 mtime 检查（line-replace 与 replace 模式已有）。

**New (`WriteTool`):**

```scala
// WriteTool.scala 已存在文件分支
val original = DiffUtil.readFile(filePath)
val mtime = Files.getLastModifiedTime(filePath)
// ... 计算 diff
if Files.getLastModifiedTime(filePath) != mtime then
  Left(ToolError("File was modified externally between read and write. Please re-check and retry."))
else
  DiffUtil.writeFile(filePath, content, lineSep)
```

**New (`EditTool` insert 模式):**

```scala
// 在 readFile 之后立即记 mtime，writeFile 之前再比对，与其他两种模式一致
val original = DiffUtil.readFile(filePath)
val mtime = Files.getLastModifiedTime(filePath)
// ... 构造 newLines
if Files.getLastModifiedTime(filePath) != mtime then
  Left(ToolError("File was modified externally. Please re-read and retry."))
else
  DiffUtil.writeFile(filePath, updated, lineSep)
```

**TOCTOU 说明：** `getLastModifiedTime` → 比对 → `writeFile` 之间仍有 ns 级窗口；这是已知保留风险（见风险表）。本 issue 仅消除"分钟级"的常见竞争（用户手改 + 模型 Write）。原子写（write-to-temp + atomic rename）属于独立议题，已声明 Out of Scope。

**Rationale:** 三种 Edit 模式 + Write 在并发保护上行为一致，避免 LLM 因模式选择而触发不同的失败路径。

---

### 变更 4：`WriteTool` content 大小上限

**Current:** 无校验。

**New:**

```scala
val MAX_WRITE_BYTES: Int = 512 * 1024 // 与 ReadTool.MAX_FILE_BYTES 对齐
val contentBytes = content.getBytes(StandardCharsets.UTF_8).length
if contentBytes > MAX_WRITE_BYTES then
  Left(ToolError(s"Content too large: $contentBytes bytes (limit $MAX_WRITE_BYTES). Split into multiple Edit calls."))
```

**口径说明：**

- 这里的 "bytes" 指 **UTF-8 编码后的字节数**，与 `ReadTool` 的 `Files.size(filePath)` 口径完全一致（`Files.size` 也是磁盘字节数，写入采用 UTF-8）
- 中文 / emoji 字符在 UTF-8 下每字符 3-4 字节；512KB 字节上限对应约 130k-170k 个 ASCII 字符或约 40k 中文字符，覆盖绝大多数源码场景
- 与 `content.length`（UTF-16 code unit 计数）不同，本上限在多字节字符上更严格，这是为了和 ReadTool 对称（"读得回来的，最大就这么大"）

**Rationale:** 与 `ReadTool.MAX_FILE_BYTES` 对齐。比上限更大的文件应通过多次 `Edit` 写入；超过该规模的整文件覆盖通常意味着 prompt 失控。

---

### 变更 5：`DiffUtil` 统一行切分

**Current:** `EditTool` / `WriteTool` 内多次直接 `split("\\r?\\n", ...)`，参数与 `-1` 不统一。`DiffUtil.makeDiff` 内部也用不带 `-1` 的 split。

**New:** 在 `DiffUtil` 中加：

```scala
/** 唯一的行切分入口；保留末尾空串（与 line-replace 等下标语义一致）。 */
def splitLines(content: String): List[String] = content.split("\\r?\\n", -1).toList

/** 用指定分隔符拼接为字符串。 */
def joinLines(lines: List[String], lineSep: String): String = lines.mkString(lineSep)

/** 行统计：基于 splitLines 结果。 */
def lineStats(oldContent: String, newContent: String): (Int, Int) =
  lineStats(splitLines(oldContent), splitLines(newContent))
```

并将 `DiffUtil.makeDiff` 内部的两处 `split("\\r?\\n")` 也改为 `splitLines`。`EditTool` / `WriteTool` 中 `original.split(...)` / `updated.split(...)` 全部替换为 `DiffUtil.splitLines(...)`。

**口径一致性说明：** 全部统一为 `split("\\r?\\n", -1)` 的语义。对以 `\n` 结尾的文件，会比"无 -1"多出一个尾部空串，但：

- diff-utils 在三个调用方（line-replace 索引、`lineStats`、`makeDiff`）使用同一份 `lines` 时，会一致地将该尾部空串识别为相等行，不会出现在 diff 输出中
- 仅当文件确实增删了末尾换行时才会被计入 `added`/`removed`，这正是期望行为

**Rationale:** 当前三处口径分歧导致 `replacedCount`、`added/removed`、diff 渲染的边界行为难以推理。统一为 `-1` 后，行号与统计完全对齐。

---

### 变更 6：line-replace 与 insert 允许空字符串

**Current:**

```scala
// EditTool.scala — line-replace 模式
if newString.isEmpty then Left(ToolError("new_string is required when using start_line"))

// EditTool.scala — insert 模式
if insertContent.isEmpty then Left(ToolError("content is required when using insert_after_line"))
```

**New:** 删除这两个判断。空字符串语义如下：

- **line-replace 模式** `new_string=""`：**完全删除**指定行范围（`[start_line, end_line]`），不留空行：

  ```scala
  val newContentLines: List[String] =
    if newString.isEmpty then Nil  // 完全删除
    else DiffUtil.splitLines(newString)
  val updated = lines.take(start) ++ newContentLines ++ lines.drop(end + 1)
  ```

- **insert 模式** `content=""`：在指定位置插入一个空行（即 `splitLines("")` 的结果 `List("")`，长度 1）：

  ```scala
  val insertLines = DiffUtil.splitLines(insertContent) // 空字符串 → List("")
  ```

  这与"插入一个空行作为分隔符"的直觉一致。

**Rationale:**

- 当前 line-replace 限制把"删除多行"这一常用操作排除在 line-replace 之外，迫使 LLM 走 string-replace 模式手工拼接 `old_string`，更易出错。
- insert 同理，禁止空 `content` 排除了"插入空行占位"用例。
- 两个判断同源、同质（都是把"空"误判为"无意图"），一并清理。

---

### 变更 7：结果文案集中到一处

**Current:** `OK:CREATED ...` / `OK: $short updated, $added added, $removed removed` 字符串散落在 `WriteTool.call` 与 `EditTool.call` 各分支中；`summarizeResult` 用 `(\d+) added` / `(\d+) removed` / `result.contains("matches")` 等正则在自家文案上做模糊匹配。

**New:** 字符串契约不变（`call()` 仍返回 `Either[ToolError, String]`），但格式生成集中到一处：

```scala
// DiffUtil.scala — 公共格式器
private val OkCreatedPrefix = "OK:CREATED"
private val OkUpdatedPrefix = "OK:UPDATED"

def renderCreatedResult(filePath: java.nio.file.Path): String =
  s"$OkCreatedPrefix $filePath"

def renderUpdatedResult(fileName: String, added: Int, removed: Int, diff: String): String =
  s"$OkUpdatedPrefix $fileName, $added added, $removed removed\n$diff"

/** 解析 OK:UPDATED 文案，返回 (added, removed)。 */
def parseUpdatedStats(result: String): Option[(Int, Int)] = ...
```

`WriteTool` / `EditTool` 的 `summarizeResult` 通过 `DiffUtil.parseUpdatedStats` 而非各自的本地正则提取数字。`EditTool` 的 `result.contains("matches")` 分支保留语义但改用 `DiffUtil` 暴露的常量前缀（如有）做匹配。

**这是一次性"消除散落正则"的改动，不引入新的结构化结果对象。** 之所以不改 `Tool` trait 的返回类型为结构化 result，是因为：

1. `Tool` 的 `call: ... => Either[ToolError, String]` 是所有工具共用的 trait 签名，仅为 Edit/Write 修改会扩散
2. 当前需要解决的是"自家正则解析自家文案"的脆弱性，不是"工具结果不结构化"的更大议题

**承认折衷：** `summarizeResult` 仍解析字符串，但解析与生成共享同一格式定义，正则不再散落。如果未来要全面结构化，再单开 issue。

**Rationale:** 单一来源 + 单一解析点；变更最小、收益明确。

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 删除 description 中的 Read 承诺后，LLM 可能跳过 Read 直接 Write | Low | 该承诺当前本就未生效；保留软建议；未来由 read-tracker issue 真正实施 |
| `mode` 字段加入后，旧 prompt 不传 `mode` 仍可工作（fallback 推断） | Low | 保留向后兼容路径；待 prompt 稳定后再升级为 required |
| `WriteTool` 大小上限可能误伤合法的大文件覆盖 | Medium | 错误信息明确指引使用 `Edit`；阈值与 `ReadTool` 对齐，已读得了的就写得回去 |
| line-replace / insert 允许空字符串后，旧 prompt 偶尔传空字符串期望"无操作"会变成"删除/插空行" | Medium | 新行为通过 description 明确说明；观察灰度期 `OK:UPDATED` 中 `0 added, N removed` 的异常率 |
| `DiffUtil.splitLines` 改 `-1` 后，部分依赖默认 `split` 的行计数会变化（含尾空串） | Low | 三处调用方同时迁移；compile 兜底；变更 5 内说明对 diff-utils 的影响 |
| mtime 检查仍有 TOCTOU 窗口（`getLastModifiedTime` → 比对 → `writeFile`） | Low | 已知保留；本 issue 仅拦截分钟级竞争；原子写（temp + rename）属独立议题，待单开 issue |

---

## 兼容性

- **向后兼容：** 部分（`mode` 字段为新增可选项；空 `new_string` 行为变化是已知的 breaking）
- **行为变更：**
  - line-replace 模式空 `new_string` 由 "报错" 变为 "删除整段"
  - Write 在文件被并发改动时由 "覆盖" 变为 "拒绝并提示重读"
  - Write 在内容超过 512KB 时拒绝
- **迁移指南：** LLM prompt（tool description）会自动同步描述新行为；调用方代码无需变更

---

## 关联

- Related to `005-tool-result-cross-session-leak-and-detail-toggle-broken.md` — 同样涉及 tool 层的契约与 UI 层解析
- Introduced by initial Edit/Write/DiffUtil implementation（无单一引入 commit；为渐进式累积的设计裂缝）

---

## [结束] 成功标准

- [ ] `WriteTool` / `EditTool` description 中无任何未实施的强约束（grep "fail if you did not read" 应无结果）
- [ ] `EditTool.inputSchema` 包含 `mode` 字段，enum 列出三种模式
- [ ] `WriteTool` 在文件 mtime 变化时返回 `ToolError`
- [ ] `EditTool` 三种模式（replace / line_replace / insert）均在 mtime 变化时返回 `ToolError`
- [ ] `WriteTool` 在 content 超过 512KB（UTF-8 字节）时返回 `ToolError`
- [ ] `EditTool` line-replace 模式接受 `new_string=""` 并完全删除指定行范围
- [ ] `EditTool` insert 模式接受 `content=""` 并插入一个空行
- [ ] `DiffUtil.splitLines` 是 `core/tools/` 内唯一的 `split("\\r?\\n", ...)` 出现点（含 `makeDiff` 内部）
- [ ] `summarizeResult` 中无 `(\d+) added` / `(\d+) removed` 字符串字面量正则；解析逻辑集中在 `DiffUtil`

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 1. description 中无未实施的强约束
grep -nE "fail if you did not read|MUST use your Read" \
  src/main/scala/nebflow/core/tools/EditTool.scala \
  src/main/scala/nebflow/core/tools/WriteTool.scala
# Expected: no output

# 2. EditTool 的 mode enum 已声明
grep -n '"enum"' src/main/scala/nebflow/core/tools/EditTool.scala
# Expected: 至少一行包含 "replace" / "line_replace" / "insert"

# 3. 行切分仅在 DiffUtil 中
grep -rnE 'split\("\\\\r\?\\\\n"' src/main/scala/nebflow/core/tools/
# Expected: 仅 DiffUtil.scala 出现（包含 makeDiff 内部调用）

# 4. WriteTool 与 EditTool 三种模式都有 mtime 检查
grep -nc "getLastModifiedTime" src/main/scala/nebflow/core/tools/WriteTool.scala
# Expected: >= 2（读取与比对各一次）
grep -nc "getLastModifiedTime" src/main/scala/nebflow/core/tools/EditTool.scala
# Expected: >= 6（三种模式 × 读取 + 比对，各两次）

# 5. WriteTool 有大小上限
grep -nE "MAX_WRITE_BYTES|contentBytes\s*>" src/main/scala/nebflow/core/tools/WriteTool.scala
# Expected: 至少 2 行（常量定义 + 使用）

# 6. summarizeResult 不再有散落正则
grep -nE '"\(\\\\d\+\) added"|"\(\\\\d\+\) removed"' \
  src/main/scala/nebflow/core/tools/EditTool.scala \
  src/main/scala/nebflow/core/tools/WriteTool.scala
# Expected: no output（正则统一搬到 DiffUtil）
```

### 运行时检查

- [ ] 启动 nebflow，让 LLM 用 Edit 修改一个普通文件，观察行为正常
- [ ] 让 LLM 在 Edit 模式选择 `line_replace` 删除连续 5 行（`new_string=""`），验证完全删除（行数减少 5）
- [ ] 让 LLM 在 Edit 模式选择 `insert` 插入空行（`content=""`），验证文件新增一个空行
- [ ] 在 Write / Edit (任一模式) 调用前手动 `touch` 修改目标文件 mtime，验证返回 mtime 错误
- [ ] 让 LLM 尝试写入 600KB 内容，验证返回大小上限错误
- [ ] 三种 `EditTool` 模式分别走通：replace / line_replace / insert

### Code Review Checklist

- [ ] 所有 `OK:CREATED` / `OK:UPDATED` 文案由 `DiffUtil.renderXxxResult` 生成，不在 `WriteTool` / `EditTool` 内独立拼接
- [ ] `EditTool` / `WriteTool` 内无 `split("\\r?\\n", ...)` 直接调用，统一走 `DiffUtil.splitLines`
- [ ] `DiffUtil.makeDiff` 内部也使用 `splitLines`，行号与 `lineStats` 对齐
- [ ] `EditTool` 所有错误分支返回的 `ToolError` 信息能让 LLM 自我纠正（说明哪个字段缺失/冲突，例如 `"line_replace mode requires start_line"` 而非 `"new_string is required when using start_line"`）
- [ ] `WriteTool` description 与实现一致，不含 "will fail if ... not read"
- [ ] `EditTool` insert / line-replace 接受空字符串，且 description 中的语义说明（删除 / 插空行）与实现一致

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `src/main/scala/nebflow/core/tools/EditTool.scala` | **Modify** | 加 `mode` 字段（schema enum + 软推断 fallback）、放开空 `new_string`/`content`、insert 模式补 mtime 检查、所有切分改用 `DiffUtil.splitLines`、`OK:UPDATED` 文案改用 `DiffUtil.renderUpdatedResult`、`summarizeResult` 改用 `DiffUtil.parseUpdatedStats` |
| `src/main/scala/nebflow/core/tools/WriteTool.scala` | **Modify** | 删除 description 强约束、加 mtime 检查、加 UTF-8 字节大小上限、所有切分改用 `DiffUtil.splitLines`、`OK:CREATED`/`OK:UPDATED` 文案改用 `DiffUtil.renderXxxResult`、`summarizeResult` 改用 `DiffUtil.parseUpdatedStats` |
| `src/main/scala/nebflow/core/tools/DiffUtil.scala` | **Modify** | 新增 `splitLines` / `joinLines` / `lineStats(String, String)` 重载、`renderCreatedResult` / `renderUpdatedResult` / `parseUpdatedStats`、`makeDiff` 内部改用 `splitLines` |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## 权衡总结

**为什么不直接实现 read-tracker？**

要让"必须先 Read"成为真约束，需要：

1. `ToolContext` 加入 `readTrackerRef: Ref[IO, Set[Path]]`
2. `ReadTool.call` 成功后注入路径
3. `EditTool` / `WriteTool` 在 call 开头校验
4. 跨子 agent 的可见性（state 共享 vs 独立）需要决策
5. session 切换时的清理策略需要决策

这些都是独立设计议题，超出本 issue 想解决的"description 与实现裂缝"。当前阶段先把假承诺删掉，避免 prompt 被 LLM 学会忽略；read-tracker 留给后续 issue。

**为什么不在 schema 用 `oneOf` 表达 mode 必填字段？**

JSON Schema 的 `oneOf` 在 Anthropic / OpenAI tool calling 上的支持参差，且会让 schema 体积膨胀。当前折衷是：用 `enum` 把模式名公开给 LLM，由 `call` 做字段级校验并返回结构化错误。这是行业通用做法。

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已完成
- [ ] 重复 — 重复 issue：
- [ ] 不予处理 — 理由：
- [ ] 已过时
- [ ] 已取消 — 理由：

---

## [结束] 复盘

| 问题 | 回答 |
|------|------|
| 目标达成了吗？ | |
| 有什么意外？ | |
| 代码质量改善可量化吗？ | |
| 工时预估偏差原因？ | |
