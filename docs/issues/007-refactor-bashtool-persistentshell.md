# Refactor BashTool / PersistentShell to cats-effect with structured concurrency and per-session isolation

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭/发布/完成前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。
>
> **升级规则**：见 `_glossary.md` → Quick Fix 升级规则。满足任一条件时，改用完整模板。
> 小型重构可跳过可选区块。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @MashiroKai |
| 状态 | 草稿 |
| 标签 | backend, security |
| 优先级 | P1-高 |
| 创建日期 | 2026-05-04 |
| 目标日期 | 2026-05-04 |
| 预估工时 | 4h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

将 `BashTool` 和 `PersistentShell` 从 Java 并发原语迁移到纯 cats-effect `IO`，消除全局单例，实现按 `ToolContext` 隔离的 shell 会话，并修复安全策略、超时检测、后台任务生命周期等设计缺陷。

---

## 背景（可选）

`BashTool` 是 Nebflow 最核心的工具之一，负责执行用户提供的任意 shell 命令。其底层 `PersistentShell` 是项目早期实现，使用了大量 Java `java.util.concurrent` 原语（`Executors`、`CompletableFuture`、`Promise`、`AtomicBoolean`），与项目其余工具（`ReadTool`、`GrepTool`、`GlobTool` 等统一使用 `IO.blocking`/`IO.interruptible`）的 cats-effect 风格严重不一致。随着多会话、多 Agent 并行场景的引入，全局单例 `PersistentShell` 已成为状态隔离和可测试性的瓶颈。

---

## [创建] 动机

**现状是什么？为什么它是个问题？**

当前 `BashTool` 和 `PersistentShell` 存在以下 14 项设计缺陷，按严重程度排列：

| # | 问题 | 严重程度 | 说明 |
|---|------|----------|------|
| 1 | 混合并发模型 | High | `PersistentShell` 使用 Java `Executors`/`CompletableFuture`/`Promise`/`AtomicBoolean`，破坏 cats-effect 取消语义和结构化并发 |
| 2 | 全局可变单例 | High | `PersistentShell.get()` 是 JVM 级单例，`currentDir` 是 `@volatile var`，多会话/多 Agent 并发时状态冲突 |
| 3 | 目录持久化失效 | Medium | `ProcessBuilder.directory(cwd)` 只设置子进程初始目录；子进程内 `cd` 不会回写 `currentDir`，跨命令 `cwd` 状态实际上不变（仅当命令包含 `cd` 时触发） |
| 4 | 超时检测不可靠 | Medium | 通过字符串匹配 `output.contains("timed out")` / `msg.contains("timed out")` 判断超时，非结构化，易漏判/误判 |
| 5 | stderr/stdout 混为一体 | Medium | `redirectErrorStream(true)` 将 stderr 合并到 stdout，简化了输出格式但导致错误信息无法区分。重构后通过 `[stderr]:` 标记保持兼容的同时实现分离 |
| 6 | 后台任务查询接口缺失 | Medium | `PersistentShell` 实现了 `getBackgroundResult`/`listBackgroundJobs`，但没有任何 Tool 暴露这些能力，后台任务一旦启动无法获取结果 |
| 7 | 注入检查只警告不阻止 | Medium | `InjectionPatterns` 检测到的命令仍会继续执行，仅附加一行警告文本，不符合安全最佳实践 |
| 8 | 代码重复 | Low | 注入检查路径和非注入路径的 `shell.execute` / `prefix` / `dirLine` / `handleError` 逻辑完全重复 |
| 9 | 安全 bypass 字段缺失 | Low | `inputSchema` 未声明 `dangerouslyDisableSandbox` 字段；安全策略升级需要引入该字段以支持显式 bypass |
| 10 | 资源清理无保证 | Medium | `PersistentShell.kill()` 仅在 JVM 退出时可能调用，没有与 `ToolContext` 生命周期绑定的清理机制 |
| 11 | 后台任务内存泄漏 | Medium | `backgroundJobs` 是 `ConcurrentHashMap`，已完成任务仅在 `getBackgroundResult`/`listBackgroundJobs` 调用时清理，长期运行会堆积 |
| 12 | 缺少取消机制 | Low | 后台任务一旦启动，没有 `killBackgroundJob` 或取消接口 |
| 13 | 后台任务启动异常未捕获 | Medium | `executeBackground` 中 `executor.execute` 抛出的未检查异常不会被捕获，后台任务启动失败时无反馈 |
| 14 | 线程池永久泄漏 | Medium | `PersistentShell.kill()` 无任何调用点，`executor` 和 `scheduler` 在 JVM 生命周期内永不关闭 |

### 关键代码片段

```scala
// shell.scala:16-20 — 全局可变状态 + Java 线程池
class PersistentShell:
  @volatile private var currentDir: String = System.getProperty("user.dir")
  private val executor = Executors.newFixedThreadPool(4)
  private val scheduler = Executors.newSingleThreadScheduledExecutor()

// shell.scala:27-85 — IO.async 包裹 Scala Future + Java scheduler，取消语义断裂
  def execute(command: String, timeoutMs: Long): IO[String] =
    IO.async[String] { cb =>
      IO.delay {
        val p = Promise[String]()
        val completed = new AtomicBoolean(false)
        // ... executor.execute + scheduler.schedule + p.future.onComplete
      }
    }

// BashTool.scala:140-202 — 代码重复 + 字符串匹配超时
      shell.execute(command, timeout).map { output =>
        // ...
        if output.contains("timed out") then Left(ToolError(s"[Command timed out after ${timeout}ms]"))
        else Right(fullOutput)
      }.handleError { e =>
        val msg = e.getMessage
        if msg.contains("timed out") then Left(ToolError(s"[Command timed out after ${timeout}ms]"))
        else Left(ToolError(s"Error: $msg"))
      }
```

---

## [创建] 目标

`BashTool` 和 `PersistentShell` 完全基于 cats-effect `IO` 实现，按 `ToolContext` 隔离 shell 状态，超时检测结构化，后台任务生命周期完整，安全策略统一，且所有现有功能保持不变。

---

## [创建] 范围

### In Scope

- [ ] 将 `PersistentShell` 从 Java 并发原语重写为纯 cats-effect `IO`（`IO.blocking`/`IO.interruptible` + `IO.race`/`IO.timeout`）
- [ ] 消除 `PersistentShell` 全局单例，改为按 `ToolContext` 创建/销毁（或至少按 session）
- [ ] 修复 `currentDir` 持久化：每次命令执行后通过 `pwd` 回读并更新状态（覆盖复合命令中的 `cd`）
- [ ] 超时检测改为结构化（`IO.timeout` 或自定义 `IO.race`，非字符串匹配）
- [ ] 分离 stdout 和 stderr（取消 `redirectErrorStream(true)`，分别读取）
- [ ] 统一安全策略：注入检查升级为阻止执行（或至少要求 `dangerouslyDisableSandbox` 显式 bypass）
- [ ] 消除 `BashTool.call` 中的重复代码（提取公共 wrap 逻辑）
- [ ] 补充 `inputSchema` 中缺失的 `dangerouslyDisableSandbox` 字段
- [ ] 后台任务支持查询结果和取消（通过新增工具参数或独立接口）
- [ ] 添加后台任务自动清理机制（如定时 evict 或上限控制）

### Out of Scope

- [ ] 不修改 `BashTool.name` 和返回值格式
- [ ] `inputSchema` 字段扩展属于 In Scope（新增 `dangerouslyDisableSandbox`、`background_job_id`、`cancel_background_job`）
- [ ] 不引入新的外部依赖（保持在现有 cats-effect + JDK 范围内）
- [ ] 不修改其他 Tool 的实现
- [ ] 本次不处理 `ToolRegistry` 的并发模型（它仍用 `ConcurrentHashMap`，但不在本 issue 范围内）
- [ ] 不修改 `handlers.scala` 中 `ToolContext` 的构造方式（但需确认 `sessionId` 已传入）

> 例：No behavior changes — all WebSocket messages sent should be identical.

---

## 当前架构（可选）

### 组件关系

| 组件 | 职责 | 问题 |
|------|------|------|
| `BashTool` (object) | 解析输入、安全检查、调用 shell | 代码重复、字符串超时检测、schema 不完整 |
| `PersistentShell` (class) | 进程创建、IO 读取、后台任务、目录状态 | 混合并发模型、全局单例、目录持久化失效、资源泄漏 |
| `PersistentShell$` (object) | 全局单例管理 | `@volatile var instance` |

---

## 设计原则（可选）

**Cats-effect 优先**：所有异步/并发/取消逻辑使用 cats-effect 原语，不引入 Scala `Future` 或 Java `ExecutorService`。

**按上下文隔离**：shell 状态（当前目录、后台任务）绑定到 `ToolContext.sessionId`，而非 JVM 全局。

**安全默认拒绝**：检测到注入模式时默认阻止执行，仅当显式传入 `dangerouslyDisableSandbox` 时才允许 bypass。

---

## 目标架构（可选）

| 组件 | 技术栈 | 职责 |
|------|--------|------|
| `BashTool` (object) | cats-effect IO | 输入解析、安全检查、结果格式化 |
| `ShellSession` (class) | cats-effect IO + `java.lang.Process` | 按会话隔离的 shell 执行器，管理 cwd、后台任务 |
| `ShellSession$` (object) | cats-effect `Ref` / `MapRef` | 按 `sessionId` 管理 `ShellSession` 生命周期 |
| `BackgroundJob` (case class) | cats-effect `Fiber` + `Deferred` | 后台任务包装，`Fiber` 管理取消，`Deferred` 存储结果 |

---

## [结束] 详细变更

### 1. PersistentShell -> ShellSession（cats-effect 化）

**Current:** Java `Executors` + `CompletableFuture` + `Promise` + `AtomicBoolean`

**New:** 纯 cats-effect `IO.blocking` / `IO.interruptible` + `IO.race`

```scala
// 变更后的核心执行逻辑示例（readStream 为辅助函数，使用 BufferedReader.readLine 在 IO.blocking 中读取）
private def runProcess(command: String, cwd: String, timeout: FiniteDuration): IO[ProcessResult] =
  IO.blocking {
    val pb = new ProcessBuilder("bash", "-c", command)
    pb.directory(new File(cwd))
    pb.redirectInput(new File("/dev/null"))
    pb.redirectErrorStream(false) // stdout/stderr 分离
    pb.start()
  }.flatMap { proc =>
    val stdoutIO = IO.blocking { readStream(proc.getInputStream) }
    val stderrIO = IO.blocking { readStream(proc.getErrorStream) }
    val waitIO   = IO.blocking { proc.waitFor(); proc.exitValue() }

    // 需要 import cats.syntax.all.* 以使用 parMapN
    (stdoutIO, stderrIO, waitIO).parMapN { (out, err, code) =>
      ProcessResult(out, err, code)
    }.timeout(timeout)
     .onCancel(IO.blocking(proc.destroyForcibly()).void)
  }
```

**Rationale:** `IO.blocking` 让 cats-effect 自动管理阻塞线程；`timeout` 提供结构化超时；`onCancel` 确保进程被杀死；stdout/stderr 分离保留错误信息。`readStream` 实现为 `IO.blocking` 包裹的 `BufferedReader` 逐行读取。

### 2. 消除全局单例，按 sessionId 隔离

**Current:** `PersistentShell.get()` 返回 JVM 全局单例

**New:** `ShellSession.forSession(sessionId: String): IO[ShellSession]` 从 `Ref` 管理的 Map 中获取或创建

```scala
object ShellSession:
  // Ref.of 在 IO 中创建，通过 unsafeRunSync 初始化（仅 object 初始化时执行一次）
  private val sessions: Ref[IO, Map[String, ShellSession]] =
    Ref.of[IO, Map[String, ShellSession]](Map.empty).unsafeRunSync()

  def forSession(sessionId: String): IO[ShellSession] =
    sessions.modify { m =>
      m.get(sessionId) match
        case Some(s) => (m, s)
        case None    =>
          // ShellSession.create 在 IO 中初始化 Ref[currentDir]
          val sIO = ShellSession.create(sessionId)
          // modify 需要返回纯元组，因此这里用 unsafe 模式；实际实现中可改用 Ref + Deferred
          val s = sIO.unsafeRunSync()
          (m + (sessionId -> s), s)
    }

  def destroySession(sessionId: String): IO[Unit] =
    sessions.modify { m =>
      m.get(sessionId) match
        case Some(s) => (m - sessionId, s.kill())
        case None    => (m, IO.unit)
    }.flatten
```

**注：** 更纯函数式的实现是将 `forSession` 改为直接返回 `IO[ShellSession]`，在 `IO` 上下文中用 `Ref.of` 创建 `ShellSession`，避免 `unsafeRunSync`。示例代码展示了概念，实际编码时应优先使用纯 `IO` 路径。

**Rationale:** 多会话并行时，各会话的 cwd 和后台任务互不干扰；生命周期与 `ToolContext` 绑定。

### 3. currentDir 持久化修复

**Current:** `@volatile var currentDir` 初始为 `user.dir`，执行 `cd` 后永不更新

**New:** 每次命令执行后，如果命令包含 `cd`，额外执行 `pwd` 获取真实目录并更新内部 `Ref`

```scala
// ShellSession.create 在 IO 中初始化 Ref
def create(sessionId: String): IO[ShellSession] =
  for
    dirRef <- Ref.of[IO, String](System.getProperty("user.dir"))
  yield new ShellSession(sessionId, dirRef)

def execute(command: String, timeout: FiniteDuration): IO[ProcessResult] =
  for
    cwd    <- currentDir.get
    result <- runProcess(command, cwd, timeout)
    // 每次命令后都执行 pwd 回读，覆盖复合命令中的 cd（如 "echo 1 && cd /tmp"）
    pwdResult <- runProcess("pwd", cwd, timeout)
    _        <- currentDir.set(pwdResult.stdout.trim)
  yield result
```

**Rationale:** 子进程的 `cd` 不会回写父进程状态，必须通过显式 `pwd` 同步。

### 4. 超时检测结构化

**Current:** 字符串匹配 `output.contains("timed out")` / `msg.contains("timed out")`

**New:** 使用 `IO.timeout` 或 `IO.race(IO.sleep(timeout), processIO)`，超时产生 `Left(ToolError(...))`

```scala
processIO.timeout(timeout).handleErrorWith {
  case _: TimeoutException => IO.pure(Left(ToolError(s"[Command timed out after ${timeout}]")))
  case e => IO.pure(Left(ToolError(s"Error: ${e.getMessage}")))
}
```

**Rationale:** 结构化超时不受输出内容影响，语义清晰。

### 5. stdout/stderr 分离

**Current:** `redirectErrorStream(true)` 合并输出

**New:** `redirectErrorStream(false)`，分别读取；结果格式化为包含 stdout、stderr、exitCode 的结构

```scala
case class ProcessResult(stdout: String, stderr: String, exitCode: Int)

// BashTool 输出格式保持兼容：stderr 追加到输出末尾（带标记）
val output = result.stdout
val errLine = if result.stderr.nonEmpty then s"\n[stderr]:\n${result.stderr}" else ""
```

**Rationale:** 保留错误信息的同时保持现有输出格式兼容。

### 6. 注入检查升级为阻止执行

**Current:** `InjectionPatterns` 检测后仅附加 `[Warning: ...]` 文本，命令继续执行

**New:** 默认阻止；仅当 `dangerouslyDisableSandbox = true` 时允许 bypass（并在结果中标记）

```scala
def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
  val bypass = input("dangerouslyDisableSandbox").flatMap(_.asBoolean).getOrElse(false)
  // ...
  val injectionWarning = checkInjection(command)
  (injectionWarning, bypass) match
    case (Some(warning), false) =>
      IO.pure(Left(ToolError(s"[Blocked by sandbox] Injection detected: $warning")))
    case (Some(warning), true) =>
      // bypass 模式下继续执行，但结果中附加审计标记
      executeWithMark(s"[Sandbox bypassed] Injection: $warning\n")
    case _ =>
      // 正常执行
      executeWithMark("")
```

**Rationale:** 安全默认拒绝，bypass 需显式授权。当 `dangerouslyDisableSandbox=true` 时，结果中附加 `[Sandbox bypassed]` 标记以便审计。

### 7. 消除 BashTool.call 中的重复代码

**Current:** 注入路径和非注入路径各自包含完整的 `shell.execute` / `prefix` / `dirLine` / `handleError` 逻辑

**New:** 提取 `executeAndFormat` 辅助函数

```scala
private def executeAndFormat(
  shell: ShellSession,
  command: String,
  timeout: FiniteDuration,
  desc: Option[String],
  warning: Option[String]
): IO[Either[ToolError, String]] =
  shell.execute(command, timeout).map { result =>
    val prefix = desc.map(d => s"[$d]\n").getOrElse("")
    val dirLine = s"(cwd: ${result.cwd})\n"
    val warnLine = warning.map(w => s"[Warning: $w]\n").getOrElse("")
    val output = result.stdout + (if result.stderr.nonEmpty then s"\n[stderr]:\n${result.stderr}" else "")
    val full = warnLine + prefix + dirLine + output
    if full.trim.isEmpty then Right("[Command executed successfully with no output]")
    else Right(full)
  }.handleErrorWith {
    case _: TimeoutException => IO.pure(Left(ToolError(s"[Command timed out after ${timeout}]")))
    case e => IO.pure(Left(ToolError(s"Error: ${e.getMessage}")))
  }
```

### 8. 补充 dangerouslyDisableSandbox 字段

**Current:** `inputSchema` 只有 `command`、`timeout`、`description`、`run_in_background`

**New:** 添加 `dangerouslyDisableSandbox` boolean 字段

```scala
"dangerouslyDisableSandbox" -> io.circe.Json.obj(
  "type" -> "boolean".asJson,
  "description" -> "ONLY use when absolutely necessary. This bypasses security checks and may allow destructive commands. Use with extreme caution.".asJson
)
```

### 9. 后台任务支持查询和取消

**Current:** `executeBackground` 返回 jobId，但无查询/取消接口

**New:** `BashTool` 的 `inputSchema` 添加 `background_job_id` 和 `cancel_background_job` 字段。由于新增了后台任务查询模式，`command` 不再无条件 required——当提供 `background_job_id` 时 `command` 可省略。

```scala
"command" -> io.circe.Json.obj(
  "type" -> "string".asJson,
  "description" -> "The bash command to run. Required unless background_job_id is provided.".asJson
),
"background_job_id" -> io.circe.Json.obj(
  "type" -> "string".asJson,
  "description" -> "Job ID to query or cancel. When provided, command is not required.".asJson
),
"cancel_background_job" -> io.circe.Json.obj(
  "type" -> "boolean".asJson,
  "description" -> "If true with background_job_id, cancel the background job.".asJson
)
```

运行时校验：
- 若 `background_job_id` 为空，则 `command` 必须非空
- 若 `background_job_id` 非空，则忽略 `command`，进入查询/取消模式

### 10. 后台任务自动清理

**Current:** `backgroundJobs` 仅在查询时清理

**New:** 启动定时清理 fiber（或设置上限），自动移除已完成且超过保留时间的任务

```scala
// 在 ShellSession 中
private def startCleanupFiber: IO[Fiber[IO, Throwable, Unit]] =
  IO.sleep(5.minutes).flatMap(_ => evictCompleted).foreverM.start

private def evictCompleted: IO[Unit] =
  backgroundJobs.update(_.filter { case (_, job) => !job.isComplete })
```

**注：** cleanup fiber 的取消句柄需存入 `ShellSession` 内部，在 `kill()` 中取消。更健壮的做法是使用 `Resource` 模式管理 `ShellSession` 的生命周期，确保 fiber 和后台任务在 session 销毁时全部清理。

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| `IO.blocking` 线程池行为与旧 `Executors.newFixedThreadPool` 不同 | Medium | 在 `IOApp` 环境下测试高并发命令执行，确认线程池不会耗尽 |
| stdout/stderr 分离改变输出格式，影响下游解析 | Low | 保持输出格式兼容：stderr 追加在 `[stderr]:` 标记后；现有 `"timed out"` 等标记保留 |
| 注入检查升级为阻止可能误报合法命令 | Medium | 提供 `dangerouslyDisableSandbox` bypass；误报时用户可以显式绕过 |
| 按 sessionId 隔离后，无 sessionId 的调用方无法获取 shell 状态 | Low | `ToolContext.sessionId` 已存在，fallback 到 `"default"` 单会话 |
| `cd` + `pwd` 同步增加一次额外进程启动 | Low | 每次命令后都执行 `pwd`，开销极小（子进程已 warmed） |
| session 生命周期未绑定到 Actor/Session 终止 | Medium | 需确认 `SessionActor` 停止时是否调用 `ShellSession.destroySession`，否则 session 泄漏。在 `SessionActor` 的 `PostStop` 信号中添加清理调用 |

---

## 兼容性

- **向后兼容：** 部分兼容（Partial）
  - `name`、返回值格式保持不变
  - `inputSchema` 新增可选字段，旧客户端不传时不影响
- **行为变更：**
  - 注入检测从「警告后继续执行」变为「默认阻止」——无 `dangerouslyDisableSandbox` 的调用：旧行为为警告后继续执行，新行为为阻止执行
  - `cd` 命令后的目录现在正确持久化，跨命令 cwd 状态与旧版本不同（正面变更）
  - stdout/stderr 从合并变为分离展示（格式兼容）
  - 超时从字符串匹配变为结构化检测（用户无感知）
- **迁移指南：** 无（调用方无需调整），但需通知用户某些之前仅警告的注入命令现在会被阻止

---

## 关联

- Depends on `003-extract-wssend-from-sharedresources.md` — `ToolContext.wsSend` 已可用
- Related to `006-tasklist-not-displaying-sessionid-mismatch.md` — sessionId 隔离思路一致
- Introduced by early project bootstrap — `PersistentShell` 为初始实现

---

## [结束] 成功标准

- [ ] `PersistentShell` 完全移除，替换为 `ShellSession`
- [ ] 所有 Java 并发原语（`Executors`、`CompletableFuture`、`Promise`、`AtomicBoolean`）从 `shell.scala` 中消失
- [ ] `BashTool.call` 中无重复代码块
- [ ] 超时检测不依赖字符串匹配
- [ ] `currentDir` 在 `cd` 命令后正确持久化（每次命令后通过 `pwd` 回读，覆盖复合命令中的 `cd`）
- [ ] 注入检测默认阻止执行（bypass 需 `dangerouslyDisableSandbox`）
- [ ] `inputSchema` 包含 `dangerouslyDisableSandbox`、`background_job_id`、`cancel_background_job`
- [ ] 后台任务支持查询结果和取消
- [ ] `dangerouslyDisableSandbox=true` 时注入检测被 bypass，结果包含 `[Sandbox bypassed]`
- [ ] 所有现有功能 preserved：`run_in_background`、`timeout`、`description`、目录前缀输出
- [ ] sbt compile 通过

---

## [结束] 验证

### 编译检查

```bash
sbt compile
```

### 静态检查

```bash
# 验证 Java 并发原语已移除
grep -rn "Executors\.\|CompletableFuture\|Promise\|AtomicBoolean\|AtomicReference" src/main/scala/nebflow/core/tools/shell.scala
# Expected: no output

# 验证字符串超时检测已移除
grep -rn 'contains("timed out")' src/main/scala/nebflow/core/tools/BashTool.scala
# Expected: no output

# 验证注入检测升级为阻止
grep -A 5 'checkInjection(command)' src/main/scala/nebflow/core/tools/BashTool.scala
# Expected: 包含 Left(ToolError(...)) 而非仅 warnLine

# 验证 dangerouslyDisableSandbox 在 schema 中
grep -rn "dangerouslyDisableSandbox" src/main/scala/nebflow/core/tools/BashTool.scala
# Expected: 至少 2 处（schema + 逻辑）
```

### 运行时检查

- [ ] 步骤一：执行 `echo hello`，验证正常输出
- [ ] 步骤二：执行 `cd /tmp`，再执行 `pwd`，验证 cwd 前缀变为 `/tmp`
- [ ] 步骤三：执行 `sleep 5` with timeout=1000ms，验证超时返回 `[Command timed out after 1000ms]`
- [ ] 步骤四：执行 `echo hello >&2`，验证 stderr 出现在 `[stderr]:` 标记后
- [ ] 步骤五：执行 `run_in_background=true sleep 2`，验证返回 jobId；随后用 `background_job_id` 查询结果
- [ ] 步骤六：执行 `echo $(rm file.txt)`，验证默认被阻止；再传 `dangerouslyDisableSandbox=true` 验证 bypass，结果中包含 `[Sandbox bypassed]`
- [ ] 步骤七：执行 `echo 1 && cd /tmp`，再执行 `pwd`，验证 cwd 变为 `/tmp`（复合命令中的 cd 也正确持久化）

### Code Review Checklist

- [ ] 无 Java `Executors`/`Future`/`Atomic` 原语
- [ ] 超时使用 cats-effect 结构化机制
- [ ] `currentDir` 通过 `Ref` 管理
- [ ] 注入检查默认阻止
- [ ] 后台任务有取消和清理机制
- [ ] 无代码重复（提取公共辅助函数）

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `shell.scala` | **Rewrite** | 目标：PersistentShell -> ShellSession，~160 行 -> ~120 行，纯 cats-effect |
| `BashTool.scala` | **Modify** | 消除重复代码、升级安全策略、补充 schema 字段、结构化超时 |
| `types.scala` | **Modify** | 新增 `ProcessResult` case class |
| `handlers.scala` | **Modify** | 确认 `ToolContext` 传入 `sessionId`；如 `SessionActor` 有 session 销毁钩子，添加 `ShellSession.destroySession` 调用 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## 权衡总结（可选）

| 指标 | 当前 | 目标 |
|------|------|------|
| 并发模型 | Java 线程池 + Scala Future + cats-effect IO 混用 | 纯 cats-effect IO |
| 状态隔离 | JVM 全局单例 | 按 `sessionId` 隔离 |
| 超时检测 | 字符串匹配 | 结构化 `IO.timeout` |
| 安全策略 | 注入警告后继续执行 | 注入默认阻止，可 bypass |
| 后台任务 | 启动后无法查询/取消 | 支持查询、取消、自动清理 |
| 代码重复 | `execute`/`wrap` 逻辑复制两份 | 提取单一 `executeAndFormat` |
| 测试性 | 全局单例难以 mock | 按上下文注入，可替换 |

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
