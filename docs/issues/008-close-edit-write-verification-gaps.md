# Close Edit/Write Tool Verification Gaps

> 基于 `docs/issues/templates/refactor.md` 模板。Issue `007-fix-edit-write-tool-design.md` 已完成工具描述与契约修复，本次 issue 关闭剩余的三个验证缺口。

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
| 预估工时 | 3h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

为 `Edit`/`Write` 补全运行时安全约束：引入真正的 read-tracker、将 `mode` 提升为必填字段、建立可复现的运行时验证流程。

---

## 背景

Issue `007-fix-edit-write-tool-design.md` 已将 `EditTool`/`WriteTool` 的描述、schema 契约与结果格式统一为单一来源，评分遗留三项扣分：

1. **无运行时验证（-7）**：仅通过编译与静态 grep 确认正确性，未实际跑通空 `new_string` 整段删除、insert 空内容插空行、mtime 冲突拒绝、超大 Write 拒绝等边界路径。
2. **read-tracker 仍是空头承诺（-5）**：`Read`/`Edit`/`Write` 的 description 已改为 `Recommended: read first`，但调用层没有任何机制阻止未读先写。
3. **`mode` 字段软引入（-3）**：schema 未将 `mode` 标记为 required，旧 prompt 仍可能发送歧义字段组合，依赖内部推断兜底。

本次 issue 是 007 的收尾，完成后该模块应达到满分。

---

## [创建] 动机

### 当前问题清单

| Concern | Current Location | Should Be |
|---------|------------------|-----------|
| 未读先写约束 | 仅写在 description 字符串里 | `ReadTracker` 在调用层硬拦截 |
| `mode` 字段必填性 | `required: ["file_path"]` | `required: ["file_path", "mode"]` |
| 运行时验证 | 编译 + grep | 编译 + grep + 场景脚本验证 |

### 关键代码片段

**问题 1 — read-tracker 缺失**

```scala
// ReadTool.scala — 成功读取后没有任何记录
Right(result + suffix)

// EditTool.scala / WriteTool.scala — 没有校验
// LLM 可以直接 Write 覆盖或 Edit 一个从未 Read 过的文件
```

**问题 2 — mode 软引入**

```scala
// EditTool.scala — schema 未要求 mode，内部靠推断
input("mode").flatMap(_.asString) match
  case None =>
    if input.contains("start_line") then Right(Mode.LineReplace)
    else if input.contains("insert_after_line") then Right(Mode.Insert)
    else Right(Mode.Replace) // fallback inference
```

**问题 3 — 无运行时验证**

007 的验证节仅列出编译和 grep 命令，没有可执行的运行时检查步骤。`WriteTool` 新增的大小限制、mtime 守护、`EditTool` 各 mode 的字段冲突检测均未在真实文件系统上被触发过。

---

## [创建] 目标

1. 引入 `ReadTracker`，在 `Edit`/`Write` 覆盖路径上拒绝未读先写。
2. 将 `EditTool` schema 的 `mode` 提升为 required，移除推断 fallback。
3. 补充运行时验证脚本，跑通所有边界场景后删除（测试不进入 src）。

---

## [创建] 范围

### In Scope

- [ ] 新增 `core.tools.ReadTracker` 类（`Ref[IO, Set[Path]]`）
- [ ] `ToolContext` 增加 `readTracker: Option[ReadTracker]` 字段
- [ ] `AgentState` 增加 `readTracker`，在 `AgentActor` 初始化时创建并注入
- [ ] `ReadTool` 成功读取后记录路径
- [ ] `EditTool` 对已有文件编辑前检查 tracker（存在时）
- [ ] `WriteTool` 覆盖已有文件前检查 tracker（存在时）
- [ ] `WriteTool` 写入成功（无论新建/覆盖）后记录路径
- [ ] `EditTool` 写入成功后记录路径
- [ ] `EditTool` schema 将 `mode` 加入 `required`
- [ ] `EditTool` description 声明 `mode` 为必填，删除推断说明
- [ ] `resolveMode` 移除无 mode 的推断分支；缺失时返回 ToolError
- [ ] 运行时验证脚本（写于 `/tmp`，跑完后删除），覆盖：
  - `Edit` line_replace 空 `new_string` → 整段删除
  - `Edit` insert 空 `content` → 插入单个空行
  - `Read` 不存在的文件 → 不记录 tracker
  - `Write` 超大小限制 → 拒绝
  - `Write` mtime 冲突 → 拒绝
  - `Edit`/`Write` 未读先写 → 拒绝（需 tracker 已注入）
  - `Edit` 无效 mode → 拒绝并提示有效值

### Out of Scope

- `ReadTool` 改用 `DiffUtil.splitLines`（纯展示行为，无用户可见差异）
- `detectLineSep` 处理纯 `\r` 分隔符（边界过窄，无实际影响）
- ReadTracker 行范围追踪（只追踪"是否读过"，不追踪读了哪些行）
- 测试文件提交到 src 仓库（遵循"test 验证完就删"的惯例）
- `ContextManage` / compaction 触发时清空 tracker（当前会话内文件在 compaction 后仍视为已读）

---

## 当前架构

```
AgentActor (per session)
  └── AgentState
        └── readTracker: None   <-- 本次新增

executeTool(call, projectRoot, llm, ...)
  └── ToolContext(..., readTracker = None)  <-- 本次注入
        └── ReadTool / EditTool / WriteTool
```

`executeTool` 的签名目前在 `handlers.scala:10`，调用方在 `AgentActor` 的 `pipeToolExecutions` 内（3 处）。

---

## 设计原则

- **向后兼容兜底**：`ToolContext.readTracker` 为 `Option`；当外部调用方未注入 tracker 时，行为退化为当前模式（description 提示但不拦截）。这允许非 AgentActor 路径（如测试或 CLI）不受影响。
- **写入即知晓**：Write/Edit 成功后把目标文件加入 tracker，允许"写后立即改"不用再 Read。
- **schema 即契约**：LLM 看到的 schema 必须完整表达必填约束，不依赖内部推断兜底。

---

## [结束] 详细变更

### 1. 新增 ReadTracker

**Current:** 无

**New:** `src/main/scala/nebflow/core/tools/ReadTracker.scala`

```scala
package nebflow.core.tools

import cats.effect.{IO, Ref}
import java.nio.file.Path

class ReadTracker private (state: Ref[IO, Set[Path]]):
  def recordRead(path: Path): IO[Unit] = state.update(_ + path)
  def hasBeenRead(path: Path): IO[Boolean] = state.get.map(_.contains(path))
  def clear(): IO[Unit] = state.set(Set.empty)

object ReadTracker:
  def create: IO[ReadTracker] =
    Ref.of[IO, Set[Path]](Set.empty).map(new ReadTracker(_))
```

**Rationale:** 最小可复用单元。用 `Path` 而非 `String` 避免绝对/相对路径不匹配。

---

### 2. ToolContext 注入 readTracker

**Current:** `ToolContext` 无 readTracker 字段

**New:** `types.scala`

```scala
case class ToolContext(
  projectRoot: String,
  llm: Option[LlmHandle[IO]] = None,
  sessionStore: Option[nebflow.gateway.SessionStore] = None,
  agentActorRef: Option[org.apache.pekko.actor.typed.ActorRef[nebflow.agent.AgentCommand]] = None,
  contextWindow: Int = Defaults.ContextWindow,
  sessionId: Option[String] = None,
  taskStore: Option[TaskStore] = None,
  wsSend: Option[Json => IO[Unit]] = None,
  readTracker: Option[ReadTracker] = None   // NEW
)
```

**Rationale:** `Option` 保证外部未注入时不会破坏已有调用路径。

---

### 3. AgentState 与 AgentActor 初始化

**Current:** `AgentState` 无 readTracker

**New:** `protocol.scala` + `AgentActor.scala` + 调用方（SessionActor 或 spawn 点）

- `AgentState` 增加 `readTracker: Option[ReadTracker] = None`（`copy` 默认保留，不影响已有 `state.copy(...)` 调用）
- **`ReadTracker` 由 spawner（`AgentActor.apply` 的调用方）在 actor 外部创建，作为参数传入 `AgentActor.apply`**，确保 Pekko restart 时不会丢失 tracker
- `AgentActor.apply` 将传入的 `readTracker` 注入到初始 `AgentState`

**Rationale:** Pekko `SupervisorStrategy.restart` 会重新执行 `Behaviors.setup`，如果在 setup 内创建 Ref，restart 后 tracker 被清空，导致同一会话中已有的 Read 记录丢失。由外部创建可保证 restart 后状态恢复时 tracker 仍然有效。

---

### 4. executeTool 透传 readTracker

**Current:** `executeTool` 的 3 处 `AgentActor` 调用均不传 readTracker

**New:** 所有 3 处调用增加 `readTracker = state.readTracker`。

---

### 5. ReadTool 记录成功读取

**Current:** `ReadTool.call` 成功后直接返回 `Right(...)`

**New:** 仅在成功读取（`Right` 路径）后调用 `ctx.readTracker.traverse_(_.recordRead(filePath))`；文件不存在、目录、超大等错误路径不记录。

---

### 6. EditTool 未读拦截 + IO.blocking 重构

**Current:** `call` 将整个逻辑包在 `IO.blocking { ... }` 内，read-check 无法以 IO 形式插入。

**New:** 将路径解析、mode 解析、read-check 提到 `IO.blocking` 外；仅文件 I/O 留在 `IO.blocking` 内：

```scala
def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
  val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
  val filePath = ...

  resolveMode(input) match
    case Left(err) => IO.pure(Left(err))
    case Right(mode) =>
      if !Files.exists(filePath) then IO.pure(Left(ToolError(s"File does not exist: $filePath")))
      else
        val readCheck: IO[Either[ToolError, Unit]] = ctx.readTracker match
          case Some(rt) =>
            rt.hasBeenRead(filePath).map {
              case true => Right(())
              case false =>
                Left(ToolError(s"File was not read in this session: $filePath. Read it first with the Read tool."))
            }
          case None => IO.pure(Right(()))

        readCheck.flatMap {
          case Left(err) => IO.pure(Left(err))
          case Right(()) => IO.blocking {
            // 原有编辑逻辑（mode dispatch、mtime 检查、write、diff）
            // 写入成功后：
            // ctx.readTracker.traverse_(_.recordRead(filePath)).unsafeRunSync()
            // ↑ 在 blocking thunk 内用 unsafeRunSync 是安全的，因为此处仅为副作用记录
          }
        }
```

**Rationale:** `hasBeenRead` 返回 `IO[Boolean]`，不能在 `IO.blocking` 内直接 flatMap。将前置校验提升到 `IO` 层面，仅保留磁盘操作在 `blocking` 内。记录 tracker 的副作用发生在 write 成功后的同步块内，用 `unsafeRunSync` 安全（仅状态更新，无外部依赖）。

---

### 7. WriteTool 未读拦截 + IO.blocking 重构

**Current:** `call` 将整个逻辑包在 `IO.blocking { ... }` 内，read-check 无法以 IO 形式插入。

**New:** 将路径解析、size 校验、read-check 提到 `IO.blocking` 外；仅磁盘操作留在 `IO.blocking` 内：

```scala
def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
  val filePathStr = input("file_path").flatMap(_.asString).getOrElse("")
  val content = ...
  val filePath = ...

  val contentBytes = content.getBytes(StandardCharsets.UTF_8).length
  if contentBytes > MAX_WRITE_BYTES then
    IO.pure(Left(ToolError(s"Content too large: ...")))
  else if Files.exists(filePath) && Files.isDirectory(filePath) then
    IO.pure(Left(ToolError(s"Path is a directory...")))
  else
    val isNew = !Files.exists(filePath)
    val readCheck: IO[Either[ToolError, Unit]] =
      if !isNew then
        ctx.readTracker match
          case Some(rt) =>
            rt.hasBeenRead(filePath).map {
              case true => Right(())
              case false =>
                Left(ToolError(s"File was not read in this session: $filePath. Read it first with the Read tool."))
            }
          case None => IO.pure(Right(()))
      else IO.pure(Right(()))

    readCheck.flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(()) => IO.blocking {
        // 原有写入逻辑（新建/覆盖、mtime 检查、writeFile、diff）
        // 写入成功后：
        // ctx.readTracker.traverse_(_.recordRead(filePath)).unsafeRunSync()
      }
    }
```

**Rationale:** 同 EditTool — 前置 IO 校验（read-check）必须在 `IO.blocking` 外完成。

---

### 8. EditTool mode 提升为 required

**Current:** schema `required: ["file_path"]`；description 写 `"if omitted the mode is inferred"`；`resolveMode` 有推断分支。

**New:**
- schema: `required: ["file_path", "mode"]`
- description 删除推断说明，改为 `"mode"` 为必填字段
- `resolveMode` 中 `case None => ...` 分支改为 `Left(ToolError("mode is required"))`

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| AgentActor 重启后 readTracker 丢失 | Low | 由 spawner 外部创建并传入，restart 后从 AgentState 恢复，不会丢失 |
| 子 agent 继承父 readTracker（当前不继承）导致重复 Read | Low | 设计如此：子 agent 有独立上下文，必须 Read 才能编辑 |
| mode required 导致旧 prompt 生成的 tool call 被 schema 拒绝 | Medium | schema 校验由 upstream LLM API 执行；旧 prompt 会收到 validation error 并自动纠正（Claude 会重试） |
| 非 AgentActor 路径（如 CLI）缺少 readTracker → 退化为无约束 | Low | Option 兜底；CLI 调用可显式传入 `None` 以跳过约束 |

---

## 兼容性

- **向后兼容：** Yes（readTracker 为 Option；旧调用方不传则行为不变）
- **行为变更：**
  - AgentActor 路径下，未 Read 先 Write/Edit 现在会失败
  - EditTool schema 要求 `mode` 字段
- **迁移指南：** 无（所有变更由系统内部消化）

---

## 关联

- Depends on `007-fix-edit-write-tool-design.md` — 前置修复，本次在其基础上收口
- Related to `006-tasklist-not-displaying-sessionid-mismatch.md` — 同属工具层质量提升

---

## [结束] 成功标准

- [ ] `sbt -client compile` 无错误
- [ ] 静态 grep 确认 `resolveMode` 内无 `case None =>` 推断分支
- [ ] 静态 grep 确认 `required` 包含 `"mode"`
- [ ] 运行时脚本验证以下场景全部通过：
  - line_replace 空 `new_string` → 整段删除，diff 正确
  - insert 空 `content` → 插入单空行，diff 正确
  - `Read` 不存在的文件 → 不记录 tracker
  - Write 超 512KB → 返回 `Content too large`
  - 未 Read 先 Edit → 返回 `File was not read in this session`
  - 未 Read 先 Write 覆盖 → 返回 `File was not read in this session`
  - 缺失 mode → 返回 `mode is required`
- [ ] 所有运行时脚本执行完毕后从 `/tmp` 删除

---

## [结束] 验证

### 编译检查

```bash
sbt -client compile
```

### 静态检查

```bash
# 确认 resolveMode 中无推断分支
grep -n "case None =>" src/main/scala/nebflow/core/tools/EditTool.scala
# Expected: 仅指向 mode 缺失的错误分支，无推断逻辑

# 确认 mode 在 required 中
grep -n 'mode' src/main/scala/nebflow/core/tools/EditTool.scala | grep 'required'
# Expected: required 行包含 mode

# 确认 ReadTracker 引用链完整
grep -rn "ReadTracker" src/main/scala/nebflow/agent/
grep -rn "ReadTracker" src/main/scala/nebflow/core/
grep -rn "readTracker" src/main/scala/nebflow/core/handlers.scala
```

### 运行时检查

运行时脚本位于 `/tmp/nebflow_verify_008/`，全部手写，跑完删除。

- [ ] 步骤一：新建临时文件，用 `EditTool` line_replace 空 `new_string` 删除整段 → 确认 diff 正确
- [ ] 步骤二：用 `EditTool` insert 空 `content` → 确认插入单空行
- [ ] 步骤三：`Read` 不存在的文件 → 确认不记录 tracker
- [ ] 步骤四：构造超大 Write（>512KB）→ 确认拒绝
- [ ] 步骤五：未 Read 先 Edit 已有文件 → 确认拒绝
- [ ] 步骤六：未 Read 先 Write 覆盖已有文件 → 确认拒绝
- [ ] 步骤七：发送无 mode 的 Edit input → 确认拒绝 `mode is required`

### Code Review Checklist

- [ ] ReadTracker 使用 `Path` 而非 `String`
- [ ] ToolContext.readTracker 为 Option
- [ ] EditTool/WriteTool 仅在文件存在且为覆盖时检查 tracker
- [ ] Write 新建文件不触发 read 检查
- [ ] 所有工具成功写入后记录 tracker
- [ ] mode required 的 schema 变更与代码变更一致
- [ ] ReadTool 读取不存在的文件时**不**记录 tracker

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `src/main/scala/nebflow/core/tools/ReadTracker.scala` | **Create** | 新增 read-tracker 实现 |
| `src/main/scala/nebflow/core/tools/types.scala` | **Modify** | ToolContext 增加 readTracker 字段 |
| `src/main/scala/nebflow/core/tools/ReadTool.scala` | **Modify** | 成功后记录 tracker |
| `src/main/scala/nebflow/core/tools/EditTool.scala` | **Modify** | 编辑前检查 tracker；成功后记录；mode 必填 |
| `src/main/scala/nebflow/core/tools/WriteTool.scala` | **Modify** | 覆盖前检查 tracker；成功后记录 |
| `src/main/scala/nebflow/core/handlers.scala` | **Modify** | executeTool 透传 ToolContext（含 readTracker）|
| `src/main/scala/nebflow/agent/protocol.scala` | **Modify** | AgentState 增加 readTracker 字段 |
| `src/main/scala/nebflow/agent/AgentActor.scala` | **Modify** | 初始化 ReadTracker 并注入 AgentState；executeTool 透传 |

---

## [结束] 关闭原因

- [ ] 已完成

---

## [结束] 复盘

| 问题 | 回答 |
|------|------|
| 目标达成了吗？ | |
| 有什么意外？ | |
| 代码质量改善可量化吗？ | |
| 工时预估偏差原因？ | |
