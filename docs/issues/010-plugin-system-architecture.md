# Feature: Plugin System Architecture

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
| 标签 | `backend`, `frontend`, `api` |
| 优先级 | P1-高 |
| 创建日期 | 2025-01-XX |
| 目标日期 | 未确定 |
| 预估工时 | 16h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

建立统一的插件系统，允许外部开发者通过 `~/.nebflow/plugins/` 目录向后端扩展工具、向前端扩展卡片渲染器和 UI 组件，无需修改 Nebflow 核心代码。

---

## 背景（可选）

当前 Nebflow 的工具系统（`ToolRegistry`）和前端渲染（`chat.js` 中的 `renderTool`）均为硬编码实现：
- 新增工具必须修改 Scala 源码并重新编译
- 前端工具卡片使用统一的文本渲染，无法为特定工具提供富交互（如 Diff 高亮、图片预览、图表渲染）
- 已有的 MCP 客户端代码（`McpClient`）尚未接入工具注册表，缺乏自动发现机制

本 Issue 作为插件系统的顶层设计，为后续的 MCP 自动发现（`011-mcp-tool-auto-discovery.md`）和前端卡片扩展（`012-extensible-tool-cards.md`）提供统一的架构基础。

---

## [创建] 动机

**为什么需要这个功能？**

- 用户场景：开发者想用 Nebflow 调用内部的私有 API、数据库查询工具、或自定义的 AI 模型服务
- 用户场景：用户希望在 Nebflow 中看到工具结果的富渲染（如代码差异的语法高亮、搜索结果的可折叠面板、图片生成的缩略图预览）
- 如果不做，当前的 workaround 是 Fork Nebflow 并修改核心代码，维护成本高
- 技能系统（`~/.nebflow/skills/`）已经证明了外部可配置扩展的价值，工具系统和 UI 渲染需要同等的能力

---

## [创建] 目标

交付一个可运行的插件系统，满足以下要求：

1. 后端插件可以声明新工具（通过 MCP 服务器或本地脚本）
2. 前端插件可以为特定工具注册自定义卡片渲染器
3. 插件格式简单、文档清晰，第三方开发者可在 30 分钟内写出第一个插件
4. 插件错误不影响核心系统稳定性
5. 完全向后兼容现有内置工具和行为

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

- [ ] 插件目录结构与扫描机制（`~/.nebflow/plugins/`）
- [ ] 插件 Manifest 格式定义（`plugin.yaml`）
- [ ] 后端插件加载器（PluginLoader）：解析 manifest、注册工具、管理生命周期
- [ ] 前端插件加载器：动态注入 JS/CSS、注册渲染器和 WS 消息处理器
- [ ] 后端 HTTP 路由 `/plugins/:name/*` 提供插件静态资源
- [ ] 插件隔离与错误处理（单个插件崩溃不影响其他插件和核心系统）
- [ ] 插件热重载（可选，v1 至少支持重启后生效）

### Out of Scope

- [ ] 插件市场/远程安装（v1 仅支持本地目录）
- [ ] 插件签名/安全沙箱（v1 信任本地文件系统，依赖 OS 权限控制）
- [ ] 前端组件库（v1 仅支持自定义卡片渲染和基础 WS 处理）
- [ ] 插件间的依赖管理（v1 插件独立加载，无依赖声明）

---

## [创建] 需求

### 功能需求

- [ ] **F1** 后端启动时扫描 `~/.nebflow/plugins/` 目录，读取每个子目录下的 `plugin.yaml`
- [ ] **F2** `plugin.yaml` 支持声明 MCP 服务器配置（command/args/env）和前端资源入口（JS/CSS 文件列表）
- [ ] **F3** 后端通过 `PluginLoader` 统一初始化 MCP 连接，将发现的工具自动注册到 `ToolRegistry`
- [ ] **F4** 前端在页面加载时通过 `/plugins/manifest.json` 获取所有插件列表，按序加载 JS/CSS
- [ ] **F5** 前端暴露 `Nebflow.registerCardRenderer(toolName, rendererFn)` API 供插件注册自定义卡片渲染
- [ ] **F6** 前端暴露 `Nebflow.registerMessageHandler(type, handlerFn)` API 供插件处理自定义 WS 消息
- [ ] **F7** 插件 JS 执行在 try/catch 隔离中，单个插件报错不阻塞其他插件加载
- [ ] **F8** 内置工具的卡片渲染保持现有行为，不受插件系统影响

### 非功能需求

- [ ] 插件加载时间 < 500ms（单个插件）
- [ ] 向后兼容：无插件时系统行为与当前完全一致
- [ ] 插件错误不得导致 WebSocket 连接断开或核心工具不可用

---

## 设计

### 接口/行为变更

- **新增接口**：
  - `PluginLoader.scan(): IO[List[PluginManifest]]`
  - `PluginLoader.load(manifest: PluginManifest): IO[Unit]`
  - 前端 `Nebflow.registerCardRenderer(toolName, renderer)`
  - 前端 `Nebflow.registerMessageHandler(type, handler)`
  - HTTP 路由 `GET /plugins/manifest.json`
  - HTTP 路由 `GET /plugins/:name/*path`
- **变更接口**：`ToolRegistry` 在启动后接受动态注册（已有 `registerTool`，需要确保并发安全）
- **废弃接口**：无

### 关键决策

| 决策点 | 选项 A | 选项 B | 选择 | 理由 |
|--------|--------|--------|------|------|
| 插件配置格式 | YAML（与 skill 一致） | JSON | **YAML** | 与现有 skill 系统保持一致，降低用户认知负担 |
| MCP 连接生命周期 | 随 Nebflow 启动/关闭 | 按需连接 | **随启动/关闭** | 工具调用延迟更低，实现更简单；v2 可考虑按需 |
| 前端插件加载方式 | 动态 `<script>` 注入 | Webpack 打包 | **动态注入** | 无需构建步骤，插件开发门槛低 |
| 渲染器注册粒度 | 按 toolName 精确匹配 | 按正则/类别匹配 | **按 toolName** | 简单、可预测，v2 可扩展为通配符 |

### 待决策项

- [ ] 是否支持插件热重载？实现复杂度 vs 用户体验
- [ ] 插件 CSS 是否需要 scoped（如 Shadow DOM）以避免样式冲突？
- [ ] 前端渲染器 API 是否支持异步加载（如需要动态 import 图表库）？

### 代码示例

**plugin.yaml（示例：数据库查询插件）**
```yaml
name: db-query
version: 1.0.0
description: Query internal databases via MCP

mcp:
  command: npx
  args: ["-y", "@modelcontextprotocol/server-postgres"]
  env:
    DATABASE_URL: ${DATABASE_URL}

frontend:
  scripts:
    - dist/card.js
  styles:
    - dist/card.css
```

**前端插件 JS（示例）**
```javascript
// ~/.nebflow/plugins/db-query/dist/card.js
Nebflow.registerCardRenderer('db-query__execute_sql', (container, data) => {
  const { input, content, isError } = data;
  container.innerHTML = `
    <div class="db-query-card">
      <div class="db-query-header">
        <span class="db-query-icon">🗃️</span>
        <span class="db-query-sql">${Nebflow.escapeHtml(input.sql)}</span>
      </div>
      ${isError
        ? `<div class="db-query-error">${Nebflow.escapeHtml(content)}</div>`
        : `<div class="db-query-results">${renderResultsTable(content)}</div>`
      }
    </div>
  `;
});
```

**后端 PluginLoader 接口**
```scala
case class PluginManifest(
  name: String,
  version: String,
  mcp: Option[McpConfig],
  frontend: Option[FrontendConfig]
)

object PluginLoader:
  val PLUGIN_DIR = os.home / ".nebflow" / "plugins"
  
  def scan(): IO[List[PluginManifest]] = IO.blocking {
    if !os.exists(PLUGIN_DIR) then Nil
    else os.list(PLUGIN_DIR)
      .filter(os.isDir)
      .flatMap { dir =>
        val yamlPath = dir / "plugin.yaml"
        if os.exists(yamlPath) then parseManifest(yamlPath) else None
      }
      .toList
  }
  
  def load(manifest: PluginManifest): IO[Unit] = ???
```

---

## [创建] 兼容性

- **向后兼容：** Yes — 无插件时行为完全一致
- **迁移指南：** 无，现有用户无需操作
- **废弃计划：** 无

---

## [结束] 成功标准

**如何量化地判断是否成功？**

- [ ] 开发者可以在不修改 Nebflow 源码的情况下，通过放置一个 `plugin.yaml` 和一个 JS 文件，为任意工具实现自定义卡片渲染
- [ ] MCP 工具自动注册后，LLM 可以正确调用这些工具并获取结果
- [ ] 单插件加载失败时，其他插件和核心系统正常运行
- [ ] 单元测试覆盖率 > 80%（PluginLoader + 渲染注册表）

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 插件 JS 代码导致前端崩溃 | High | 每个插件的 JS 在独立 try/catch 中加载；渲染器调用包裹 try/catch |
| MCP 服务器连接失败导致启动变慢 | Medium | 连接超时限制 5s；失败的 MCP 服务器记录日志但不阻塞启动 |
| 插件 CSS 全局污染 | Medium | 插件 CSS 建议以 `.plugin-<name>` 为前缀；文档中强调最佳实践 |
| 工具名冲突（MCP vs 内置） | Medium | MCP 工具统一前缀 `mcp__{serverId}__{toolName}`；已有实现 |

---

## [结束] 验证

### 功能验证

- [ ] 步骤一：在 `~/.nebflow/plugins/test-plugin/` 创建最小插件（含 `plugin.yaml` + `card.js`）
- [ ] 步骤二：重启 Nebflow，确认前端 Network 面板中加载了 `/plugins/test-plugin/card.js`
- [ ] 步骤三：触发对应工具调用，确认渲染器输出的 DOM 结构正确
- [ ] 步骤四：故意写错一个插件的 JS，确认其他插件和核心系统不受影响

### 测试策略

- [ ] 单元测试覆盖 `PluginLoader.scan()`（空目录、无效 YAML、有效 manifest）
- [ ] 单元测试覆盖 `PluginLoader.load()`（MCP 连接成功/失败场景）
- [ ] 集成测试：端到端验证插件工具被 LLM 调用并返回结果
- [ ] 前端单元测试：验证 `Nebflow.registerCardRenderer` 和渲染器调用链

### 回归检查

- [ ] 现有内置工具（Bash、Read、Edit 等）的卡片渲染不受影响
- [ ] 现有 MCP 代码（如果有手动使用场景）不受影响
- [ ] 无插件目录时系统正常启动

---

## 关联

- Depends on `003-extract-model-client.md` — 无直接依赖，但插件可能需要模型客户端
- Blocks `011-mcp-tool-auto-discovery.md` — 本 Issue 提供 MCP 插件加载的基础设施
- Blocks `012-extensible-tool-cards.md` — 本 Issue 提供前端渲染注册的基础设施
- Related to `004-subagent-dynamic-discovery.md` — 插件系统未来可支持子 Agent 发现

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `PluginLoader.scala` | **Create** | 插件扫描、加载、生命周期管理 |
| `PluginManifest.scala` | **Create** | 配置数据模型 |
| `ToolRegistry.scala` | **Modify** | 确保动态注册并发安全 |
| `GatewayMain.scala` | **Modify** | 启动时调用 PluginLoader |
| `WebSocketRoutes.scala` | **Modify** | 新增 `/plugins/*` 静态资源路由 |
| `chat.css` | **Modify** | 新增插件卡片基础样式 |
| `main.js` | **Modify** | 加载插件 manifest 和 JS/CSS |
| `utils.js` | **Modify** | 新增 `Nebflow.*` 全局 API |
| `PluginLoaderSpec.scala` | **Create** | 单元测试 |
| `plugins/README.md` | **Create** | 插件开发文档 |

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
