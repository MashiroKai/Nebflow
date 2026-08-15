# Nebflow Hook 与 Callback 系统

Nebflow 提供两个独立的双向通道，让 agent 与外部世界交互：

- **Hook（内→外）**：agent 内部事件触发外部 shell 命令
- **Callback（外→内）**：外部系统向 agent 注入消息

两者完全独立，互不依赖。

---

## 1. Hook 系统（内→外）

### 1.1 概念

Hook 在 agent 执行特定动作时自动触发预配置的 shell 命令。用于强制执行工作流规则，不依赖 LLM 遵守 prompt 指令。

### 1.2 事件类型

| 事件 | 分组 | 触发时机 | 支持匹配器 |
|------|------|---------|-----------|
| `PreToolUse` | Tool | tool 执行前 | ✅ |
| `PostToolUse` | Tool | tool 执行成功后 | ✅ |
| `PostToolUseFailure` | Tool | tool 执行失败后 | ✅ |
| `PreCompact` | Compact | 上下文压缩前 | ❌ |
| `PostCompact` | Compact | 上下文压缩完成后 | ❌ |
| `SessionStart` | Lifecycle | root session 建立时触发一次（WS 连接建立，非每个会话） | ❌ |
| `SessionEnd` | Lifecycle | 会话结束时 | ❌ |
| `Stop` | Lifecycle | agent 停止时（仅 depth==0 根 agent） | ❌ |

### 1.3 配置

在项目根目录的 `nebflow.json` 中配置：

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "command",
            "command": "bash pre-edit-check.sh",
            "timeout": 30,
            "continueOnError": true
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "bash log-tool-use.sh"
          }
        ]
      }
    ]
  }
}
```

**字段说明：**

| 字段 | 说明 | 默认值 |
|------|------|--------|
| `matcher` | 匹配 tool 名称。支持精确匹配、管道分隔（`Edit\|Write`）、通配符（`*`）、正则 | `*` |
| `type` | Hook 类型。当前仅支持 `command` | `command` |
| `command` | 要执行的 shell 命令 | 必填 |
| `timeout` | 超时秒数 | `60` |
| `continueOnError` | 执行失败时是否继续（否则阻止 tool 执行） | `true` |

### 1.4 环境变量

Hook 进程自动获得以下环境变量：

| 变量 | 说明 |
|------|------|
| `HOOK_EVENT` | 事件名称（如 `PreToolUse`） |
| `SESSION_ID` | 当前 session ID |
| `PROJECT_ROOT` | 项目根目录 |
| `TOOL_NAME` | 触发的 tool 名称 |
| `TOOL_INPUT_FILE_PATH` | tool 输入中的 file_path 参数 |
| `NEBFLOW_URL` | Nebflow 网关地址（如 `http://localhost:8080`） |

### 1.5 stdin JSON 协议

Hook 进程通过 stdin 接收 JSON：

```json
{
  "event": "PostToolUse",
  "session_id": "abc123",
  "project_root": "/path/to/project",
  "cwd": "/path/to/project",
  "timestamp": "2025-05-15T10:00:00Z",
  "tool_name": "Edit",
  "tool_input": { "file_path": "/path/to/file.scala", "old_string": "...", "new_string": "..." },
  "tool_output": "OK",
  "tool_success": true
}
```

### 1.6 stdout JSON 协议

Hook 进程通过 stdout 返回 JSON（可选，不输出则默认 allow）：

```json
{
  "decision": "allow",
  "reason": "optional reason text",
  "additional_context": "Information appended to agent's context",
  "updated_input": { "file_path": "/modified/path" },
  "continue": true,
  "stop_reason": "optional reason when continue=false"
}
```

**decision 取值：**
- `allow`（默认）— 允许继续
- `block` — 阻止 tool 执行（仅 PreToolUse 有效）

**updated_input**（仅 PreToolUse）— 替换 tool 的输入参数。

**additional_context** — 追加到 agent 上下文的文本，agent 会在后续操作中看到。

**continue** — 设为 `false` 时请求 agent 停止当前回合（`stop_reason` 可附原因）。

### 1.7 示例

#### 自动 lint 检查

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [{
          "type": "command",
          "command": "bash -c 'FILE=$TOOL_INPUT_FILE_PATH; if [ -f \"$FILE\" ]; then npx eslint \"$FILE\" 2>&1 || true; fi'"
        }]
      }
    ]
  }
}
```

#### 阻止在 main 分支提交

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [{
          "type": "command",
          "command": "bash -c 'if echo \"$TOOL_INPUT\" | grep -q \"git commit\" && [ \"$(git rev-parse --abbrev-ref HEAD)\" = \"main\" ]; then echo \"{\\\"decision\\\":\\\"block\\\",\\\"reason\\\":\\\"Cannot commit on main branch\\\"}\"; fi'",
          "continueOnError": false
        }]
      }
    ]
  }
}
```

---

## 2. Callback 系统（外→内）

### 2.1 概念

Callback 允许外部系统向 Nebflow agent 注入消息。通过 HTTP 端点或 CLI 命令实现，使用网关 token 认证，零配置。

### 2.2 端点

```
POST /api/callbacks/inject
```

认证：与网关使用同一个 token，支持三种方式：
- `Authorization: Bearer <token>` （推荐）
- `Cookie: nebflow_token=<token>`
- `?token=<token>` query param

### 2.3 请求体

```json
{
  "agent": "Nebula",
  "message": "Build completed with 3 warnings",
  "session": "abc123"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `agent` | ✅ | 目标 agent 名称 |
| `message` | ✅ | 消息内容 |
| `session` | ❌ | Session ID。不传则自动创建新 session |

可选字段：`source`（默认 `"callback"`）、`metadata`（JSON 对象）、`correlationId`。

### 2.4 响应

```json
{
  "status": "ok",
  "sessionId": "def456",
  "agent": "Nebula"
}
```

### 2.5 Session 解析规则

| 情况 | 行为 |
|------|------|
| 传了 `session` 且存在 | 使用该 session |
| 传了 `session` 但不存在 | 返回 404 |
| 不传 `session` | 自动创建新 session，agent 名为 `{name}-callback` |

### 2.6 CLI 命令

`scripts/nebflow-inject.sh` 封装了 HTTP 调用：

```bash
# 基本用法 — agent 必填，自动创建 session
nebflow-inject --agent Nebula --message "lint found 3 errors"
# OK → agent=Nebula session=def456

# 指定 session
nebflow-inject --agent Nebula --session abc123 --message "build failed"

# 发送文件内容
nebflow-inject --agent Nebula --message ./prompt.md
nebflow-inject --agent Nebula --message @docs/instructions.md

# 指定网关地址和 token
nebflow-inject --url http://192.168.1.100:8080 --token mytoken \
  --agent CodeReview --message "review this PR"
```

**参数：**

| 参数 | 缩写 | 必填 | 说明 |
|------|------|------|------|
| `--agent` | `-a` | ✅ | Agent 名称 |
| `--message` | `-m` | ✅ | 消息文本，或文件路径（`@` 前缀强制读文件） |
| `--session` | `-s` | ❌ | Session ID |
| `--url` | | ❌ | 网关地址（默认 `$NEBFLOW_URL` 或 `http://localhost:8080`） |
| `--token` | `-t` | ❌ | 网关 token（默认 `$NEBFLOW_TOKEN`） |

### 2.7 curl 示例

```bash
# 注入消息
curl -X POST http://localhost:8080/api/callbacks/inject \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_GATEWAY_TOKEN" \
  -d '{"agent":"Nebula","message":"Deploy v2.3.1 completed"}'

# 注入消息到指定 session
curl -X POST http://localhost:8080/api/callbacks/inject \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_GATEWAY_TOKEN" \
  -d '{"agent":"Nebula","session":"abc123","message":"CI build passed"}'

# 使用 query param 认证
curl -X POST "http://localhost:8080/api/callbacks/inject?token=YOUR_GATEWAY_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"agent":"Nebula","message":"Hello from external system"}'
```

---

## 3. Hook + Callback 组合使用

Hook 和 Callback 完全独立，但可以手动组合。例如 hook 脚本在检测到问题时通过 callback 注入反馈：

```bash
#!/bin/bash
# post-edit-lint.sh — 作为 PostToolUse hook 配置

FILE=$TOOL_INPUT_FILE_PATH
if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  exit 0
fi

RESULT=$(npx eslint "$FILE" 2>&1)
if [ $? -ne 0 ]; then
  # Hook 标准输出会作为 additionalContext 注入，无需 callback
  echo "{\"additional_context\": \"ESLint errors in $FILE:\\n$RESULT\"}"
fi
```

> 注意：Hook 的 stdout `additional_context` 已经能将信息注入回 agent，大多数场景不需要通过 Callback 绕一圈。Callback 主要用于**非 hook 触发**的外部系统（CI/CD、定时任务、其他机器上的 nebflow 实例等）。

---

## 4. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                      Nebflow Gateway                     │
│                                                         │
│  ┌─── Hook 系统（内→外）───────────────────────────┐    │
│  │                                                 │    │
│  │  agent 执行 tool                                │    │
│  │      ↓                                          │    │
│  │  HookEngine 匹配事件 + matcher                  │    │
│  │      ↓                                          │    │
│  │  HookRunner 启动 shell 进程                     │    │
│  │      ↓          ↑                               │    │
│  │  stdin JSON   stdout JSON                       │    │
│  │      ↓          ↑                               │    │
│  │  hook 脚本进程                                   │    │
│  │                                                 │    │
│  │  配置: nebflow.json → hooks                     │    │
│  └─────────────────────────────────────────────────┘    │
│                                                         │
│  ┌─── Callback 系统（外→内）────────────────────────┐    │
│  │                                                 │    │
│  │  POST /api/callbacks/inject                     │    │
│  │      ↓                                          │    │
│  │  网关 token 认证                                │    │
│  │      ↓                                          │    │
│  │  解析 agent + session（无则创建）               │    │
│  │      ↓                                          │    │
│  │  AgentCommand.ExternalEvent → agent mailbox     │    │
│  │                                                 │    │
│  │  调用方式: nebflow-inject CLI / curl            │    │
│  │  配置: 无（零配置，复用网关 token）              │    │
│  └─────────────────────────────────────────────────┘    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## 5. 实现文件索引

### Hook 系统

| 文件 | 说明 |
|------|------|
| `src/main/scala/nebflow/core/hooks/HookTypes.scala` | 事件类型、配置类型、结果类型 |
| `src/main/scala/nebflow/core/hooks/HookEngine.scala` | 匹配 → 执行 → 聚合结果 |
| `src/main/scala/nebflow/core/hooks/HookRunner.scala` | 启动 shell 进程，stdin/stdout JSON |
| `src/main/scala/nebflow/core/hooks/HookMatcher.scala` | Tool 名称匹配（精确/管道/通配符/正则） |
| `src/main/scala/nebflow/core/hooks/HooksConfigLoader.scala` | 从 nebflow.json 解析 hooks 配置 |

### Callback 系统

| 文件 | 说明 |
|------|------|
| `src/main/scala/nebflow/gateway/WebSocketRoutes.scala` | `POST /api/callbacks/inject` 端点 |
| `scripts/nebflow-inject.sh` | CLI 封装 |

### 集成点

| 文件 | 说明 |
|------|------|
| `src/main/scala/nebflow/agent/AgentCore.scala` | Hook 在 executeTool 前后触发 |
| `src/main/scala/nebflow/core/compact/CompactService.scala` | Hook 在 compact 前后触发 |
| `src/main/scala/nebflow/gateway/GatewayMain.scala` | 启动时加载 HookEngine |
