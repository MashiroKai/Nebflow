# Feature: MCP Tool Auto-Discovery and Registration

> **[必填]** 标记为必须填写，其余按需填写。
> 术语定义见 [`_glossary.md`](./templates/_glossary.md)。
> **必填时机说明**：`[创建]` = 创建时必须填写；`[结束]` = 关闭前必须填写；未标注 = 按需填写
> 状态流转见 `_glossary.md` → 对应类型。
>
> **升级规则**：见 `_glossary.md` → Quick Fix 升级规则。涉及行为变更时，关闭 quickfix，改用本模板。

---

## [创建] 元信息

| 字段 | 内容 |
|------|------|
| 负责人 | @username |
| 状态 | 草稿 |
| 标签 | `backend`, `api` |
| 优先级 | P1-高 |
| 创建日期 | 2025-01-XX |
| 目标日期 | 未确定 |
| 预估工时 | 8h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

基于 `010-plugin-system-architecture.md` 的插件基础设施，实现 MCP 服务器的自动发现、连接和工具注册，使外部 MCP 服务器提供的工具无需修改 Nebflow 源码即可被 LLM 调用。

---

## 背景（可选）

Nebflow 已有完整的 MCP 客户端实现（`McpClient`、`StdioTransport`、`HttpTransport`、`createMcpToolWrapper`），但这些代码尚未与 `ToolRegistry` 和启动流程打通：

- `McpClient` 可以 listTools 和 callTool，但没有自动初始化逻辑
- `ToolRegistry` 仅包含硬编码的内置工具
- `executeTool` 对 `mcp__` 前缀有 fallback 处理，但如果 MCP 工具没有预注册到 `ToolRegistry`，LLM 的 tool schema 中不会出现这些工具，LLM 根本不会发起调用

本 Issue 完成 MCP 集成的"最后一公里"。

---

## [创建] 动机

**为什么需要这个功能？**

- 用户场景：开发者已经在使用 MCP 生态（如 Postgres MCP Server、Filesystem MCP Server、GitHub MCP Server），希望 Nebflow 能直接调用这些工具
- 当前 workaround：无 — MCP 工具对 LLM 不可见，无法被调用
- MCP 是社区标准协议，支持 MCP = 支持一个庞大的外部工具生态

---

## [创建] 目标

交付物：Nebflow 启动时自动连接配置的 MCP 服务器，将其工具列表注册到 `ToolRegistry`，LLM 可以像使用内置工具一样调用 MCP 工具。

---

## 优先级（可选）

| 属性 | 评估 |
|------|------|
| 优先级 | P1-高 |
| 目标版本 | v1.1.0 / 未确定 |
| 目标发布日期 | 未确定 |

---

## [创建] 范围

### In Scope

- [ ] MCP 服务器配置读取（从插件 `plugin.yaml` 或独立配置文件 `~/.nebflow/mcp-servers.json`）
- [ ] 启动时并发连接所有配置的 MCP 服务器（带超时）
- [ ] 通过 `tools/list` 获取工具列表，用 `createMcpToolWrapper` 包装后注册到 `ToolRegistry`
- [ ] 每个 MCP 服务器的工具名统一前缀 `mcp__{serverId}__{toolName}` 避免冲突
- [ ] 连接失败的服务器记录日志并跳过，不阻塞其他服务器和核心系统
- [ ] Nebflow 关闭时优雅断开所有 MCP 连接

### Out of Scope

- [ ] MCP 工具卡片自定义渲染（由 `012-extensible-tool-cards.md` 处理）
- [ ] MCP 服务器运行时动态添加/删除（v1 仅启动时加载）
- [ ] MCP Resource/Prompt 支持（v1 仅支持 Tools）
- [ ] 非插件方式配置 MCP 服务器的 GUI 界面

> 本次不实现 MCP 运行时热插拔，仅支持启动时静态加载。

---

## [创建] 需求

### 功能需求

- [ ] **F1** 支持从 `~/.nebflow/mcp-servers.json` 读取 MCP 服务器配置（作为插件系统之外的快速配置方式）
- [ ] **F2** 支持从 `plugin.yaml` 的 `mcp:` 区块读取 MCP 服务器配置（插件化方式）
- [ ] **F3** 启动时对每个 MCP 服务器调用 `initialize()` 和 `tools/list()`
- [ ] **F4** 每个发现的工具通过 `createMcpToolWrapper` 包装并注册到 `ToolRegistry`
- [ ] **F5** MCP 工具的描述和 inputSchema 原样透传给 LLM
- [ ] **F6** MCP 连接失败时，记录 error 日志并继续，不阻塞启动流程
- [ ] **F7** MCP 工具调用超时与内置工具一致（默认 120s）

### 非功能需求

- [ ] MCP 初始化总时间 < 10s（5 个服务器并发）
- [ ] 单个 MCP 服务器连接超时 5s
- [ ] 向后兼容：无 MCP 配置时行为完全一致

---

## 设计

### 接口/行为变更

- **新增接口**：
  - `McpManager.servers: Ref[IO, Map[String, McpClient]]`
  - `McpManager.startAll(): IO[Unit]`
  - `McpManager.stopAll(): IO[Unit]`
  - 配置文件 `~/.nebflow/mcp-servers.json`
- **变更接口**：`ToolRegistry` 启动后接受 MCP 工具动态注册
- **废弃接口**：无

### 关键决策

| 决策点 | 选项 A | 选项 B | 选择 | 理由 |
|--------|--------|--------|------|------|
| 配置格式 | 独立 JSON 文件 | 仅 plugin.yaml | **两者都支持** | JSON 文件适合快速试用；plugin.yaml 适合正式插件开发 |
| 连接超时 | 5s | 10s | **5s** | 本地 MCP 服务器启动很快；远程可配置更长 |
| 并发策略 | 并行连接所有服务器 | 串行 | **并行** | 启动速度最优；失败的服务器独立超时 |
| 工具前缀 | `mcp__{serverId}__` | `{serverId}__` | **`mcp__{serverId}__`** | 已有 `summarizeToolCall` 中对 `mcp__` 前缀的处理，保持一致 |

### 待决策项

- [ ] `mcp-servers.json` 是否支持环境变量引用（如 `"${DATABASE_URL}"`）？
- [ ] 是否需要在 Agent 配置 UI 中显示/隐藏特定 MCP 工具？

### 代码示例

**~/.nebflow/mcp-servers.json**
```json
{
  "postgres": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-postgres"],
    "env": {
      "DATABASE_URL": "postgresql://localhost/mydb"
    }
  },
  "github": {
    "command": "npx",
    "args": ["-y", "@modelcontextprotocol/server-github"],
    "env": {
      "GITHUB_PERSONAL_ACCESS_TOKEN": "${GITHUB_TOKEN}"
    }
  }
}
```

**McpManager 实现**
```scala
class McpManager(config: McpManagerConfig):
  private val servers = Ref.unsafe[IO, Map[String, (McpClient, List[Tool])]](Map.empty)
  
  def startAll(): IO[Unit] = 
    config.servers.toList.parTraverse_ { case (id, cfg) =>
      connectServer(id, cfg)
        .timeout(5.seconds)
        .handleErrorWith { e =>
          NebflowLogger.forName("mcp").error(s"MCP server '$id' failed: ${e.getMessage}") *> IO.unit
        }
    }
  
  private def connectServer(id: String, cfg: ServerConfig): IO[Unit] =
    val transport = cfg.url match
      case Some(url) => new HttpTransport(url, cfg.headers.getOrElse(Map.empty))
      case None => new StdioTransport(cfg.command.get, cfg.args.getOrElse(Nil), cfg.env.getOrElse(Map.empty))
    val client = new McpClient(transport)
    for
      _ <- client.initialize()
      mcpTools <- client.listTools()
      wrapped = mcpTools.map(t => createMcpToolWrapper(id, t, client))
      _ <- IO.delay(wrapped.foreach(ToolRegistry.registerTool))
      _ <- servers.update(_ + (id -> (client, wrapped)))
    yield ()
  
  def stopAll(): IO[Unit] =
    servers.get.flatMap(_.values.toList.traverse_ { case (client, _) => client.close() })
```

**executeTool 的 MCP fallback 完善**
```scala
// 当前代码已有 mcp__ 前缀的 fallback，但需要确保 MCP 工具已被注册
// 如果采用动态注册，ToolRegistry.TOOL_MAP 中已包含 MCP 工具，无需额外 fallback
```

---

## [创建] 兼容性

- **向后兼容：** Yes — 无 MCP 配置时 `ToolRegistry` 与当前完全一致
- **迁移指南：** 无
- **废弃计划：** 无

---

## [结束] 成功标准

- [ ] 配置一个 MCP 服务器（如 filesystem）后重启 Nebflow，LLM 可以成功调用其工具
- [ ] `ToolRegistry.ALL_TOOLS` 包含已注册的 MCP 工具
- [ ] MCP 服务器连接失败时，Nebflow 正常启动，其他工具可用
- [ ] 单元测试覆盖 MCP 连接成功/失败/超时场景

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| MCP 服务器进程泄漏 | High | `McpManager.stopAll()` 在 JVM shutdown hook 中调用；`StdioTransport` 已管理进程生命周期 |
| MCP 工具与内置工具同名冲突 | Medium | 强制 `mcp__{serverId}__` 前缀 |
| MCP 服务器返回巨大 schema 拖慢启动 | Medium | schema 大小限制 100KB，超限则跳过该工具并 warn |
| MCP 调用阻塞 actor 线程 | Medium | `callTool` 已返回 IO，在 cats-effect 中调度 |

---

## [结束] 验证

### 功能验证

- [ ] 步骤一：创建 `~/.nebflow/mcp-servers.json`，配置 filesystem MCP server
- [ ] 步骤二：重启 Nebflow，查看日志确认 MCP 初始化成功
- [ ] 步骤三：在对话中要求 LLM "列出当前目录的文件"，确认 LLM 调用了 `mcp__filesystem__list_directory`
- [ ] 步骤四：断开 MCP 服务器进程，确认 Nebflow 仍正常响应内置工具调用

### 测试策略

- [ ] 单元测试：`McpManager.startAll()` 的并行连接和错误隔离
- [ ] 集成测试：使用 mock MCP 服务器验证工具注册和调用
- [ ] 回归测试：无 MCP 配置时的启动流程

### 回归检查

- [ ] 内置工具（Bash、Read、Edit 等）调用不受影响
- [ ] `summarizeToolCall` 对未知工具的处理逻辑仍然正确
- [ ] Agent 配置中的工具列表正确显示/隐藏 MCP 工具

---

## 关联

- Depends on `010-plugin-system-architecture.md` — 依赖插件系统的配置解析和生命周期管理
- Blocks 无 — 本 Issue 是 MCP 集成的终点，不阻塞后续工作
- Related to `012-extensible-tool-cards.md` — MCP 工具的卡片渲染需要前端扩展
- Introduced by `8f39c18` — MCP 客户端代码在 `core/mcp/` 中已存在

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `McpManager.scala` | **Create** | MCP 服务器生命周期管理 |
| `McpManagerConfig.scala` | **Create** | 配置数据模型和解析 |
| `GatewayMain.scala` | **Modify** | 启动时调用 `McpManager.startAll()`，关闭时调用 `stopAll()` |
| `ToolRegistry.scala` | **Modify** | 确保 MCP 工具注册线程安全 |
| `handlers.scala` | **Modify** | 移除或简化 `mcp__` fallback（因工具已预注册） |
| `McpManagerSpec.scala` | **Create** | 单元测试 |

> 创建时填写"计划"，关闭前更新为"实际"。

---

## [结束] 关闭原因

> 见 `_glossary.md` → 关闭原因。必须选择一项。

- [ ] 已发布
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
| 用户反馈如何？ | |
| 工时预估偏差原因？ | |
