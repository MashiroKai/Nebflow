# Nebflow Git 管理协助设计

> 状态：草案
> 创建：2025-05-06
> 目标：让 agent 遵循 Git 工作流规范，不在 main 分支上直接开发

---

## 1. 问题

当前 agent 通过 `BashTool` 执行裸 `git` 命令，存在以下问题：

1. **无分支保护**：agent 可能在 main 上直接 commit
2. **无上下文感知**：agent 不知道当前分支、工作区状态，靠每次 `git status` 手动查询
3. **多 agent 冲突**：多个 agent/session 可能同时修改同一分支
4. **安全保障脆弱**：仅靠正则拦截 + prompt 规则，LLM 可能忽略

## 2. 设计目标

- Agent **不能**在 protected 分支（main/master）上直接 commit
- Agent 修改文件时**自动**切换到 feature 分支
- Git 状态通过 **系统上下文**注入，agent 无需主动查询
- 多 agent/session 之间**分支隔离**
- 用户可通过配置文件自定义 Git 策略

## 3. 架构概览

```
┌─────────────────────────────────────────────────────┐
│                    Agent Actor                       │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │ EditTool │  │WriteTool │  │    GitTool (新)    │  │
│  └────┬─────┘  └────┬─────┘  └────────┬──────────┘  │
│       │              │                 │              │
│       └──────────────┼─────────────────┘              │
│                      ▼                                │
│            ┌─────────────────┐                        │
│            │  GitGuard (新)   │  ← 策略引擎           │
│            └────────┬────────┘                        │
│                     │                                 │
│              ┌──────┴──────┐                          │
│              │ JGit / CLI  │  ← 实际 Git 操作        │
│              └─────────────┘                          │
└─────────────────────────────────────────────────────┘

        ┌──────────────────────┐
        │  GitContext (新)      │  ← 每轮注入 agent 上下文
        │  分支、状态、最近提交  │
        └──────────────────────┘

        ┌──────────────────────┐
        │  GitPolicy (新)      │  ← 可配置策略
        │  git-policy.json     │
        └──────────────────────┘
```

三个新组件，职责清晰分离：

| 组件 | 职责 | 位置 |
|------|------|------|
| **GitContext** | 感知当前 Git 状态 | 注入 SystemReminders |
| **GitGuard** | 执行 Git 策略（分支保护、自动切分支） | Edit/Write/GitTool 调用链中 |
| **GitTool** | 结构化的 Git 操作（commit/push/branch） | 新 Tool |

## 4. GitContext — Git 上下文感知

### 4.1 数据结构

```scala
// core/git/GitContext.scala
case class GitContext(
  branch: String,              // 当前分支名
  isClean: Boolean,            // 工作区是否干净
  hasStagedChanges: Boolean,
  stagedFiles: List[String],
  unstagedFiles: List[String],
  untrackedFiles: List[String],
  recentCommits: List[GitCommit],  // 最近 5 条 commit
  isProtectedBranch: Boolean       // 是否在受保护分支上
)

case class GitCommit(hash: String, message: String, author: String)
```

### 4.2 注入方式

复用现有的 `SystemReminders` 机制，与 file change tracking 并列：

```scala
// 在 AgentCore.pipeLlmCall 的 reminders 收集阶段加入：
gitContextOpt <- resources.gitGuard.getContext(resources.projectRoot)
```

注入到 agent 上下文的格式示例：

```
<git-context>
Current branch: main (PROTECTED)
Working tree: dirty (2 modified, 1 untracked)
Modified: src/main/Foo.scala, src/main/Bar.scala
Untracked: src/main/Baz.scala
Recent commits:
  8e43615 fix: restore ToolCallRecord imports
  a886277 feat: add timeout defaults
</git-context>
```

当 `isProtectedBranch=true` 时，额外追加警告：

```
WARNING: You are on a protected branch (main). File modifications will trigger automatic branch creation.
```

### 4.3 刷新时机

- 每次 `pipeLlmCall` 时重新获取（与 file change tracking 同频）
- 每次 Git 操作后立即刷新

## 5. GitGuard — 策略引擎

### 5.1 GitPolicy 配置

```json
// nebflow.json 中的 git 配置，或 ~/.nebflow/git-policy.json
{
  "git": {
    "protectedBranches": ["main", "master"],
    "autoBranch": {
      "enabled": true,
      "prefix": "agent/",
      "includeSessionId": true
    },
    "autoCommit": {
      "enabled": false,
      "onTurnEnd": false
    },
    "mergeStrategy": "pull-request"
  }
}
```

配置项说明：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `protectedBranches` | `["main", "master"]` | 禁止直接 commit 的分支 |
| `autoBranch.enabled` | `true` | Edit/Write 时自动创建分支 |
| `autoBranch.prefix` | `"agent/"` | 分支名前缀 |
| `autoBranch.includeSessionId` | `true` | 分支名包含 session ID |
| `autoCommit.enabled` | `false` | 是否自动 commit |
| `autoCommit.onTurnEnd` | `false` | 每轮结束是否自动 commit |
| `mergeStrategy` | `"pull-request"` | 合并策略 |

### 5.2 GitGuard 接口

```scala
// core/git/GitGuard.scala
trait GitGuard:
  /** 获取当前 GitContext */
  def getContext(projectRoot: os.Path): IO[Option[GitContext]]

  /** 确保不在 protected 分支上。如果当前在 protected 分支，自动切出新分支。 */
  def ensureFeatureBranch(projectRoot: os.Path, sessionId: Option[String]): IO[String]
    // 返回分支名

  /** 执行 commit */
  def commit(projectRoot: os.Path, message: String, files: List[String]): IO[String]
    // 返回 commit hash

  /** 获取策略 */
  def policy: GitPolicy
```

### 5.3 自动分支创建流程

当 agent 调用 `EditTool` 或 `WriteTool` 时，在 **tool 执行前** 插入检查：

```
Agent 调用 EditTool("src/main/Foo.scala", ...)
  │
  ▼
EditTool.call()
  │
  ├─ 1. GitGuard.ensureFeatureBranch(projectRoot, sessionId)
  │     ├─ 当前分支是 agent/xxx？→ 通过，返回分支名
  │     ├─ 当前分支是 main？
  │     │   ├─ 工作区干净？→ git checkout -b agent/<session-id>
  │     │   └─ 有未提交修改？→ git stash → checkout -b → stash pop
  │     └─ 当前分支是其他用户分支？→ 询问 agent 是否要切分支
  │
  ├─ 2. 执行实际的文件编辑
  │
  └─ 3. 返回结果（包含当前分支信息）
```

### 5.4 分支命名规则

```
agent/<session-id-prefix>
```

示例：
- `agent/a1b2c3d4` — session ID 前 8 位
- `agent/a1b2c3d4-fix-auth` — 含任务描述（可选）

同一 session 内所有 Edit/Write 共享同一分支。通过 `ShellSession` 的 sessionId 关联。

### 5.5 多 Agent 协调

```
Session A (main agent)
  ├── 自动分支: agent/abc123
  └── 委派子 agent
        ├── 自动分支: agent/abc123-def456  (父子 session 组合)
        └── 在自己的分支上工作
```

子 agent 的分支名包含父 session ID，便于追溯关系。

## 6. GitTool — 结构化 Git 操作

### 6.1 为什么需要 GitTool

虽然 BashTool 能执行 git 命令，但：

1. **策略执行**：GitTool 调用 GitGuard，确保每次操作都经过策略检查
2. **强类型参数**：避免 LLM 写出格式错误的 git 命令
3. **结果结构化**：返回结构化 JSON 而非文本，LLM 更容易解析
4. **与 BashTool 解耦**：Git 安全规则不再混杂在 BashTool 的正则里

### 6.2 Tool Schema

```json
{
  "name": "Git",
  "description": "Performs Git operations with policy enforcement...",
  "inputSchema": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["status", "commit", "branch", "checkout", "push", "log", "diff", "merge"]
      },
      "message": { "type": "string", "description": "Commit message (for commit action)" },
      "branch": { "type": "string", "description": "Branch name (for branch/checkout action)" },
      "files": { "type": "array", "items": { "type": "string" }, "description": "Files to stage" },
      "remote": { "type": "string", "description": "Remote name (default: origin)" },
      "count": { "type": "number", "description": "Number of log entries (default: 10)" }
    },
    "required": ["action"]
  }
}
```

### 6.3 Action 行为

| Action | 行为 | 策略检查 |
|--------|------|----------|
| `status` | 返回 GitContext | 无 |
| `commit` | stage 指定文件 + commit | 检查是否在 protected 分支 |
| `branch` | 创建新分支 | 检查命名规范 |
| `checkout` | 切换分支 | 禁止切到 protected 分支（除非工作区干净） |
| `push` | push 当前分支 | 禁止 force push |
| `log` | 返回 commit 历史 | 无 |
| `diff` | 返回差异 | 无 |
| `merge` | 合并分支 | protected 分支合并需要用户审批 |

### 6.4 BashTool 拦截（兜底）

保留 BashTool 中的 Git 安全拦截作为**兜底**：

```scala
// BashTool 中新增 Git 命令检测
private val GitCommands = """^git\s+(\w+)""".r

// 在 call() 中，如果是 git 命令，给出提示
command match
  case GitCommands(subcmd) if Set("commit", "push", "checkout", "merge", "reset").contains(subcmd) =>
    // 提示 agent 使用 GitTool 而非裸 git 命令
    Left(ToolError("Please use the Git tool instead of raw git commands for Git operations."))
  case _ => // 正常 BashTool 流程
```

注意：这只拦截**写操作**（commit/push/checkout/merge/reset），允许通过 BashTool 执行只读操作（git log、git diff、git status），因为 agent 可能需要更灵活的查询。

## 7. 与现有系统的集成点

### 7.1 SharedResources

```scala
case class SharedResources(
  // ... 现有字段 ...
  gitGuard: GitGuard          // 新增
)
```

### 7.2 ToolContext

```scala
case class ToolContext(
  // ... 现有字段 ...
  gitGuard: Option[GitGuard]  // 新增
)
```

### 7.3 EditTool / WriteTool

在 `call()` 开头加入分支保护检查：

```scala
// EditTool.call() 中
ctx.gitGuard.foreach { guard =>
  guard.ensureFeatureBranch(
    os.Path(ctx.projectRoot),
    ctx.sessionId
  ).unsafeRunSync()  // 或通过 IO 链
}
```

### 7.4 SystemReminders

在 `SystemReminders.collectAll` 中新增 Git 上下文收集：

```scala
def collectAll(...): IO[List[SystemReminder]] = for
  // ... 现有的 reminders ...
  gitReminder <- gitContextReminder(gitGuard, projectRoot)
yield baseReminders ++ gitReminder
```

### 7.5 BashTool

在 `DangerousPatterns` 中，不再需要专门的 git force push 正则（由 GitTool 接管）。但保留作为兜底防护，不删除。

## 8. 实现路径（分阶段）

### Phase 1：GitContext + 分支保护（核心）

**目标**：agent 不再在 main 上直接开发

1. 创建 `GitContext` 数据类
2. 创建 `GitPolicy` 配置解析
3. 实现 `GitGuard` 核心逻辑（`getContext`, `ensureFeatureBranch`）
4. 集成到 `SystemReminders`
5. 在 `EditTool` / `WriteTool` 中调用 `ensureFeatureBranch`

**涉及文件**：
- 新建：`core/git/GitContext.scala`, `core/git/GitPolicy.scala`, `core/git/GitGuard.scala`
- 修改：`core/reminders.scala`, `core/tools/EditTool.scala`, `core/tools/WriteTool.scala`
- 修改：`agent/SharedResources.scala`, `core/tools/types.scala`
- 修改：`agent/AgentCore.scala`（reminders 收集）

### Phase 2：GitTool

**目标**：结构化的 Git 操作

1. 创建 `GitTool`（实现 `Tool` trait）
2. 注册到 `ToolRegistry`
3. 在 BashTool 中添加 git 写操作拦截
4. 在 `AgentDef` 的 tools 中默认包含 `Git`

**涉及文件**：
- 新建：`core/tools/GitTool.scala`
- 修改：`core/tools/BashTool.scala`, `core/tools/registry.scala`

### Phase 3：高级功能（可选）

- 自动 commit（turn 结束时）
- PR 创建（通过 GitHub/GitLab API）
- 冲突检测与通知
- Git 操作的审批流程（PermissionPolicy 扩展）

## 9. 配置示例

### 9.1 nebflow.json

```json
{
  "git": {
    "protectedBranches": ["main", "master", "release/*"],
    "autoBranch": {
      "enabled": true,
      "prefix": "agent/",
      "includeSessionId": true
    },
    "mergeStrategy": "pull-request"
  }
}
```

### 9.2 关闭自动分支（手动模式）

```json
{
  "git": {
    "protectedBranches": ["main"],
    "autoBranch": {
      "enabled": false
    }
  }
}
```

关闭后，agent 在 protected 分支上尝试 Edit/Write 时会收到错误提示，需要手动通过 GitTool 创建分支。

### 9.3 完全关闭

```json
{
  "git": {
    "enabled": false
  }
}
```

关闭后恢复现有行为，不做任何 Git 管理。

## 10. 边界情况

| 场景 | 处理方式 |
|------|----------|
| 项目没有 Git 仓库 | `GitGuard.getContext` 返回 `None`，跳过所有 Git 逻辑 |
| `.git` 存在但当前不在任何分支（detached HEAD） | 视为 protected，自动创建分支 |
| stash pop 冲突 | 中止操作，返回错误让 agent 处理 |
| 多个 agent 同时创建分支 | 分支名包含 session ID，天然隔离 |
| agent 只读不改文件 | 不触发分支创建，保持在当前分支 |
| 用户在 agent 工作期间手动 commit | 下次 `getContext` 时感知到变化 |
| 子 agent 的分支名冲突 | 包含 UUID，几乎不可能冲突 |

## 11. 不做的事

以下功能**不在**本次设计范围内：

- **自动 merge**：agent 不应自动合并到 main，这需要用户审批
- **CI/CD 集成**：与 CI 系统的交互超出范围
- **代码 review**：这属于协作流程，不是 Git 管理
- **Git hook 管理**：不修改用户的 git hooks
