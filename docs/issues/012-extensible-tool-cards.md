# Feature: Extensible Tool Card Renderer

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
| 标签 | `frontend` |
| 优先级 | P1-高 |
| 创建日期 | 2025-01-XX |
| 目标日期 | 未确定 |
| 预估工时 | 10h |
| 实际工时 | 进行中 |

---

## [创建] 一句话描述

建立前端可扩展的卡片渲染系统，允许插件和外部开发者为特定工具注册自定义 DOM 渲染器，将工具结果从纯文本升级为富交互卡片（如表格、图表、Diff 面板、图片预览）。

---

## 背景（可选）

当前 Nebflow 前端对所有工具使用统一的 `renderTool` 函数，无论工具类型如何，都渲染为：
- 固定的绿色/红色对勾图标
- 工具名 + summary 文本
- 可点击展开的原始文本 body

这种设计对简单文本输出足够，但对以下场景体验很差：
- `db-query` 工具返回 JSON/CSV 数据，用户希望看到格式化表格而非原始文本
- `image-generation` 工具返回 base64 图片，用户希望直接看到缩略图
- `web-search` 工具返回多条结果，用户希望看到可点击的链接列表
- 自定义 MCP 工具的输出格式各异，需要各自的展示方式

本 Issue 基于 `010-plugin-system-architecture.md` 的前端插件基础设施，实现渲染器的注册和调度机制。

---

## [创建] 动机

**为什么需要这个功能？**

- 用户场景：开发者编写了自定义工具（或接入了 MCP 服务器），但前端只能展示原始文本，体验不佳
- 当前 workaround：无 — 所有工具输出都经过 `renderTool` 统一处理
- 如果实现可扩展渲染，Nebflow 的 UI 可以从"聊天工具"升级为"通用 Agent 工作台"

---

## [创建] 目标

交付物：前端暴露渲染器注册 API，插件可以注册针对特定工具名的自定义渲染函数；无注册时回退到现有的通用渲染。

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

- [ ] 前端渲染器注册表（`CardRendererRegistry`）
- [ ] `Nebflow.registerCardRenderer(toolName, renderer)` API
- [ ] `Nebflow.registerCardRenderer(pattern, renderer)` API（支持 glob 匹配）
- [ ] `renderTool` 改造：先查询注册表，无匹配时回退通用渲染
- [ ] 插件 CSS 注入机制（通过 `<link>` 标签动态加载）
- [ ] 渲染器数据协议标准化（input、content、isError、label、summary 等字段）
- [ ] 向后兼容：所有现有内置工具保持当前渲染行为

### Out of Scope

- [ ] 插件系统的后端部分（由 `010-plugin-system-architecture.md` 处理）
- [ ] 前端非工具卡片的扩展（如自定义消息气泡、自定义侧边栏面板）
- [ ] 渲染器的 React/Vue 等框架支持（v1 仅支持原生 DOM API）
- [ ] 服务端渲染（SSR）支持

> 本次仅聚焦工具结果卡片的渲染扩展。

---

## [创建] 需求

### 功能需求

- [ ] **F1** 前端提供 `window.Nebflow.registerCardRenderer(toolName, rendererFn)` API
- [ ] **F2** `rendererFn` 接收 `(container: HTMLElement, data: ToolCardData) => void`
- [ ] **F3** `ToolCardData` 包含：`label`、`summary`、`content`、`isError`、`input`（JSON 字符串）、`sessionId`
- [ ] **F4** `renderTool` 调用时，先按 `toolName` 精确匹配注册表；无匹配时按 glob 模式匹配；仍无匹配时回退到现有通用渲染
- [ ] **F5** 插件 CSS 文件通过动态 `<link>` 标签加载，与插件 JS 同步注入
- [ ] **F6** 渲染器执行包裹 try/catch，单个渲染器报错不阻塞其他卡片渲染
- [ ] **F7** 持久化消息（localStorage）中的工具卡片在页面刷新后，仍能根据当前渲染器重新渲染

### 非功能需求

- [ ] 渲染器注册和调用延迟 < 10ms
- [ ] 插件 CSS 加载不阻塞首屏渲染（异步加载）
- [ ] 向后兼容：未加载任何插件时 UI 行为完全一致

---

## 设计

### 接口/行为变更

- **新增接口**：
  - 全局 `window.Nebflow.registerCardRenderer(namePattern, renderer)`
  - 全局 `window.Nebflow.escapeHtml(text)`（工具函数，供渲染器使用）
  - `CardRendererRegistry` 内部模块
- **变更接口**：
  - `renderTool()` 增加注册表查询逻辑
  - `chat.js` 引入 `cardRegistry.js` 依赖
- **废弃接口**：无

### 关键决策

| 决策点 | 选项 A | 选项 B | 选择 | 理由 |
|--------|--------|--------|------|------|
| 渲染器 API | 原生 DOM | Web Components | **原生 DOM** | 门槛低，无需学习成本；v2 可扩展 |
| 匹配策略 | 精确匹配 | 精确 + glob | **精确 + glob** | glob 可以一次注册覆盖一类 MCP 工具（如 `mcp__postgres__*`）|
| 渲染时机 | 收到 toolEnd 时 | 懒加载（IntersectionObserver） | **收到时** | 工具调用是用户关注焦点，即时渲染体验更好 |
| CSS 隔离 | 无（信任插件） | Shadow DOM | **无（信任插件）** | Shadow DOM 与现有样式系统和事件委托不兼容；v2 可考虑 |

### 待决策项

- [ ] 是否提供预置的渲染器组件库（如 `Nebflow.components.Table`、`Nebflow.components.Diff`）供插件复用？
- [ ] 渲染器是否支持异步（返回 Promise）？例如需要额外 fetch 数据来渲染。

### 代码示例

**cardRegistry.js（核心模块）**
```javascript
const exactRenderers = new Map();
const patternRenderers = [];

export function registerCardRenderer(pattern, renderer) {
  if (pattern.includes('*') || pattern.includes('?')) {
    const regex = new RegExp('^' + pattern.replace(/\*/g, '.*').replace(/\?/g, '.') + '$');
    patternRenderers.push({ pattern, regex, renderer });
  } else {
    exactRenderers.set(pattern, renderer);
  }
}

export function findRenderer(toolName) {
  if (exactRenderers.has(toolName)) {
    return exactRenderers.get(toolName);
  }
  for (const entry of patternRenderers) {
    if (entry.regex.test(toolName)) return entry.renderer;
  }
  return null;
}

export function renderWithRegistry(container, data) {
  const renderer = findRenderer(data.label);
  if (renderer) {
    try {
      renderer(container, data);
      return true;
    } catch (e) {
      console.error(`[cardRegistry] Renderer for "${data.label}" failed:`, e);
      // Fall through to default
    }
  }
  return false;
}
```

**chat.js 中的 renderTool 改造**
```javascript
import { renderWithRegistry } from './cardRegistry.js';

export function renderTool(label, summary, content, isError, inputJson, sessionId) {
  // ... existing guard code ...
  
  const row = document.createElement('div');
  row.className = 'row tool';
  const card = document.createElement('div');
  card.className = 'tool-card';
  
  const data = { label, summary, content, isError, input: inputJson, sessionId };
  
  // Try plugin renderer first
  if (renderWithRegistry(card, data)) {
    row.appendChild(card);
    chat.appendChild(row);
    smartScroll();
    return { type: 'tool', label, summary, content, isError, input: inputJson };
  }
  
  // Fallback to default rendering
  // ... existing renderTool body ...
}
```

**插件渲染器示例（SQL 查询结果表格）**
```javascript
// ~/.nebflow/plugins/db-query/dist/card.js
Nebflow.registerCardRenderer('mcp__postgres__query', (container, data) => {
  const { input, content, isError } = data;
  
  const header = document.createElement('div');
  header.className = 'db-card-header';
  header.innerHTML = `<span class="db-icon">🗃️</span>
    <code>${Nebflow.escapeHtml(JSON.parse(input).sql || '')}</code>`;
  container.appendChild(header);
  
  if (isError) {
    const err = document.createElement('div');
    err.className = 'db-card-error';
    err.textContent = content;
    container.appendChild(err);
  } else {
    try {
      const rows = JSON.parse(content);
      const table = renderJsonTable(rows);
      container.appendChild(table);
    } catch {
      const pre = document.createElement('pre');
      pre.textContent = content;
      container.appendChild(pre);
    }
  }
});
```

**持久化消息的重新渲染**
```javascript
// persistence.js 中加载历史消息时
default:
  // 工具卡片 — 使用当前渲染器重新渲染
  const cardData = { label: m.label, summary: m.summary, content: m.content, 
                     isError: m.isError, input: m.input, sessionId: sid };
  const row = document.createElement('div');
  row.className = 'row tool';
  const card = document.createElement('div');
  card.className = 'tool-card';
  if (!renderWithRegistry(card, cardData)) {
    // Fallback: reconstruct with default renderTool logic
    // ...
  }
```

---

## [创建] 兼容性

- **向后兼容：** Yes — 无渲染器注册时行为完全一致
- **迁移指南：** 无
- **废弃计划：** 无

---

## [结束] 成功标准

- [ ] 注册一个自定义渲染器后，对应工具的卡片显示自定义 DOM（而非默认文本）
- [ ] 未注册渲染器的工具仍然使用默认卡片渲染
- [ ] 渲染器抛出异常时，该卡片回退到默认渲染，其他卡片不受影响
- [ ] 页面刷新后，历史工具消息仍正确渲染（使用当前渲染器）

---

## 风险与缓解

| 风险 | 严重程度 | 缓解措施 |
|------|----------|----------|
| 渲染器 XSS（插件注入恶意脚本） | High | 提供 `Nebflow.escapeHtml` 工具；文档强调安全最佳实践；v2 可做 CSP 限制 |
| 渲染器 DOM 结构与现有 CSS 冲突 | Medium | 插件 CSS 使用 BEM 命名（`.plugin-name__element`）；基础样式尽量使用 Nebflow 提供的 CSS 变量 |
| 大量插件 JS 拖慢页面加载 | Medium | JS 异步加载；使用 `defer` 或动态 `import()` |
| 渲染器改变了 card 的 DOM 结构导致 attachToolClick 失效 | Low | `attachToolClick` 只在默认渲染时调用；自定义渲染器自行管理交互 |

---

## [结束] 验证

### 功能验证

- [ ] 步骤一：在 `~/.nebflow/plugins/test/` 创建 `card.js`，注册一个 `Bash` 工具的自定义渲染器
- [ ] 步骤二：刷新页面，触发 Bash 工具调用，确认卡片显示自定义样式
- [ ] 步骤三：注册一个抛出异常的渲染器，确认卡片回退到默认样式
- [ ] 步骤四：刷新页面，确认历史消息中的工具卡片正确重新渲染

### 测试策略

- [ ] 单元测试：`registerCardRenderer` 的精确匹配和 glob 匹配
- [ ] 单元测试：`renderWithRegistry` 的成功和 fallback 路径
- [ ] 前端集成测试：模拟 WS `toolEnd` 消息，验证自定义渲染器被调用

### 回归检查

- [ ] 内置工具（Read、Edit、Bash 等）的默认卡片渲染保持不变
- [ ] `attachToolClick` 在默认渲染下仍然工作
- [ ] `formatDiff` 在 Edit 工具中仍然正确显示

---

## 关联

- Depends on `010-plugin-system-architecture.md` — 依赖插件系统的 JS/CSS 加载机制
- Blocks 无 — 本 Issue 是前端扩展的终点
- Related to `011-mcp-tool-auto-discovery.md` — MCP 工具需要本 Issue 的渲染扩展才能展示富结果
- Related to `005-tool-result-cross-session-leak-and-detail-toggle-broken.md` — 改造 renderTool 时注意不引入回归

---

## 变更文件

| File | Action | Notes |
|------|--------|-------|
| `cardRegistry.js` | **Create** | 渲染器注册表和调度逻辑 |
| `chat.js` | **Modify** | `renderTool` 集成注册表查询；`renderToolPending` 保持通用 |
| `main.js` | **Modify** | 暴露 `window.Nebflow` 全局 API |
| `persistence.js` | **Modify** | 历史消息加载时支持自定义渲染器重新渲染 |
| `chat.css` | **Modify** | 新增 `.tool-card` 的基础变量和插件卡片容器样式 |
| `cardRegistry.test.js` | **Create** | 单元测试 |

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
