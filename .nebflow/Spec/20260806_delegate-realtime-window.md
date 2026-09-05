# Delegate 实时消息窗口方案

## 1. 现状分析

### 1.1 当前事件流（已确认）

Delegate 子代理的事件**已经通过 WS 发送到前端**，但前端无法正确展示。

**后端链路**：

1. `DelegateTool.spawnBackground()` 创建子代理 Actor，传入参数：
   - `sessionId = parentSessionId`（父会话 ID）
   - `wsSend = routeWsSend(wsSend, parentSessionId)` — 包装 wsSend，注入父 sessionId
   - `depth = parentDepth + 1`（标记为子代理）

2. `routeWsSend`（`DelegateTool.scala:37-44`）做了一件事：将父 session 的 `sessionId` 注入到每个事件 JSON 中。但**没有注入唯一的子代理标识符**。

3. 子代理 Actor 的 `depth > 0`，所以 `AgentStreamEvent.toJson()`（`protocol.scala:227-382`）以 `isSubagent=true` 模式生成事件：
   - 事件类型加 `agent*` 前缀：`agentTextDelta`、`agentToolStart`、`agentToolEnd`、`agentDone`
   - 同时注入 `nodeSessionId = state.sessionId`（= 父 sessionId）
   - 事件携带 `agentId = ctx.self.path.name`（= `delegate-coder-XyZ` 形式的唯一 ID）

4. 最终每个事件的 JSON 结构：
   ```json
   {
     "type": "agentTextDelta",
     "agentId": "delegate-coder-XyZ",
     "sessionId": "abc123",        ← 父 session（来自 routeWsSend）
     "nodeSessionId": "abc123"     ← 父 session（来自 toJson，因为 state.sessionId=abc123）
   }
   ```

**问题**：`nodeSessionId = sessionId = 父 session ID`。所有同一父的子代理共享同一个 `nodeSessionId`，无法区分。

### 1.2 前端处理（已确认）

1. **ws.js**（`ws.js:248`）：检查 `msg.nodeSessionId`，有则调用 `flowStepInterceptor`。
   - Delegate 子代理事件有 `nodeSessionId`（= 父 session），所以被拦截。
   - `flowStepInterceptor`（`flowAgentPopup.js:460`）为每个 `nodeSessionId` 创建隐藏 ChatView。
   - 但所有 Delegate 子代理的 `nodeSessionId = 父 session`，**全部路由到同一个隐藏 ChatView**（如果该 ChatView 恰好被流面板占用了，就会产生冲突）。

2. **main.js**（`main.js:1428-1500`）：对 `agentStart`、`agentToolStart`、`agentDone` 等事件有 handler，更新 `state.sessionDelegates[sid][aid]`。
   - **只记录状态**（running/done + 当前工具名），**不渲染任何聊天内容**。
   - 头部显示 delegate indicator（带计数 + dropdown），但 dropdown 里只有一行文字摘要。

3. **flowAgentPopup.js**：已存在完整的 popup 机制（隐藏 ChatView + 可移动模态窗口），但只用于 Flow 节点代理（通过 Flow 画布的 agent pill 点击打开）。

### 1.3 核心问题总结

| 组件 | 现状 | 问题 |
|------|------|------|
| 后端 routeWsSend | 注入 `sessionId=父session` | 没有注入唯一的子代理路由 ID |
| 后端 toJson | `nodeSessionId = state.sessionId` = 父 session | 与其他子代理/主会话冲突 |
| 前端 ws.js | 拦截 `nodeSessionId` → flowStepInterceptor | Delegate 子代理事件被错误拦截到 flow 通道 |
| 前端 main.js | `agentStart`/`agentDone` → dropdown 状态跟踪 | 只显示状态文字，无内容渲染 |
| 前端 flowAgentPopup.js | 完整的 popup 机制 | 只关联 Flow 画布，未接入 Delegate 入口 |

**用户看到什么**：Delegate 工具卡片（旋转 → 结果）+ 头部下拉列表显示 "running/done · 工具名"。**看不到子代理的思考、阅读、编辑等任何实际过程。**

---

## 2. 方案设计

### 2.1 总体思路

```
后端：routeWsSend 注入唯一 subagentId 到 nodeSessionId 字段
      ↓
前端：ws.js 新增 subagentId 拦截分支（在 flowStepInterceptor 之前）
      ↓
前端：delegatePopup.js 复用 flowAgentPopup 的 popup 机制
      ↓
前端：dropdown 条目可点击 → 打开 popup
```

### 2.2 后端改动

#### 文件：`src/main/scala/nebflow/core/tools/DelegateTool.scala`

**改动 1：修改 `routeWsSend` 方法**（第 37-44 行）

当前代码：
```scala
private def routeWsSend(
  wsSend: Option[io.circe.Json => IO[Unit]],
  parentSessionId: Option[String]
): io.circe.Json => IO[Unit] =
  val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
  parentSessionId match
    case Some(sid) => json => base(json.deepMerge(Json.obj("sessionId" -> sid.asJson)))
    case None => base
```

改为：
```scala
private def routeWsSend(
  wsSend: Option[io.circe.Json => IO[Unit]],
  parentSessionId: Option[String],
  subagentId: String       // NEW: 唯一子代理标识
): io.circe.Json => IO[Unit] =
  val base = wsSend.getOrElse((_: io.circe.Json) => IO.unit)
  val routeJson = Json.obj("nodeSessionId" -> subagentId.asJson)  // 覆盖 toJson 设置的 nodeSessionId
  parentSessionId match
    case Some(sid) => json => base(json.deepMerge(Json.obj("sessionId" -> sid.asJson)).deepMerge(routeJson))
    case None => json => base(json.deepMerge(routeJson))
```

**关键点**：`deepMerge` 是右优先的，所以我们的 `nodeSessionId = subagentId` 会覆盖 `toJson` 生成的 `nodeSessionId = parentSessionId`。

**改动 2：`spawnBackground` 和 `spawnPersistent` 调用处传入 `subagentId`**

`spawnBackground`（第 279 行）：
```scala
childWsSend = routeWsSend(wsSend, parentSessionId, subagentId)  // 添加第三个参数
```

`spawnPersistent`（第 384 行）：
```scala
childWsSend = routeWsSend(wsSend, parentSessionId, subagentId)  // 添加第三个参数
```

**改动后的事件 JSON**：
```json
{
  "type": "agentTextDelta",
  "agentId": "delegate-coder-XyZ",
  "sessionId": "abc123",                      ← 父 session（会话路由用）
  "nodeSessionId": "delegate-coder-XyZ"       ← 子代理唯一 ID（popup 路由用）
}
```

**改动量**：3 处（方法签名 + 2 个调用点），约 6 行代码变更。

### 2.3 前端改动

#### 文件：`src/main/resources/web/js/ws.js`

**改动 1：新增 delegateStepInterceptor 变量**（文件顶部，第 10 行附近）

```javascript
// Delegate sub-agent popup interceptor
let delegateStepInterceptor = null;
export function setDelegateStepInterceptor(fn) { delegateStepInterceptor = fn; }
```

**改动 2：在 onmessage 中，flowStepInterceptor 之前添加 delegate 拦截**（第 248 行之前）

```javascript
// ── Delegate sub-agent popup ─────────────────────────────
// Delegate 子代理事件携带 nodeSessionId = subagentId（唯一）。
// 在 flowStepInterceptor 之前拦截，避免与 flow 画布冲突。
if (msg.nodeSessionId && msg.agentId && msg.agentId.startsWith('delegate-') 
    && delegateStepInterceptor && delegateStepInterceptor(msg)) {
  const converted = convertAgentEvent(msg);
  if (converted) {
    const convList = handlers[converted.type];
    if (convList) for (const h of convList) {
      try { h(converted, activeView); }
      catch (e) { console.error('[ws] handler error for', converted.type, ':', e.message); }
    }
  }
  // 同时分发原始事件给 main.js 的状态跟踪 handler
  const list = handlers[msg.type];
  if (list) for (const h of list) {
    try { h(msg, activeView); }
    catch (e) { console.error('[ws] handler error for', msg.type, ':', e.message); }
  }
  return;
}
```

**改动 3：修改 `flowStepInterceptor` 调用条件**，排除 delegate 事件（第 248 行）

```javascript
// 原：
if (msg.nodeSessionId && flowStepInterceptor && flowStepInterceptor(msg)) {
// 改为（agentId 以 delegate- 开头的不走 flow 拦截器）：
if (msg.nodeSessionId && msg.agentId && !msg.agentId.startsWith('delegate-')
    && flowStepInterceptor && flowStepInterceptor(msg)) {
```

**改动量**：约 25 行新增代码。

#### 新建文件：`src/main/resources/web/js/delegatePopup.js`

复用 `flowAgentPopup.js` 的核心机制，但入口从 delegate dropdown 触发：

```javascript
// delegatePopup.js — 实时消息窗口 for Delegate 子代理
//
// 复用 flowAgentPopup 的 ensureStepView + openStepPopup 机制。
// 入口：delegate dropdown 条目点击 → openDelegatePopup()
// 事件路由：ws.js 的 delegateStepInterceptor → hidden ChatView

import { ChatView, setActiveView, activeView, chatViews } from './chatView.js';
import { setDelegateStepInterceptor } from './ws.js';

const stepViews = new Map();  // subagentId → { view, container, meta }
let currentPopupId = null;
let popupOverlay = null;

// CSS 复用 flowAgentPopup 的样式（同 class 前缀 flow-agent-*）

export function ensureDelegateView(subagentId) {
  // 与 flowAgentPopup.ensureStepView 完全相同的逻辑
  // 创建隐藏 DOM container + ChatView 实例
  ...
}

export function interceptDelegateStep(msg) {
  if (!msg.nodeSessionId) return false;
  if (!msg.agentId || !msg.agentId.startsWith('delegate-')) return false;
  
  const entry = ensureDelegateView(msg.nodeSessionId);
  
  // 捕获生命周期事件
  if (msg.type === 'agentStart') {
    entry.meta.agentName = msg.name || '';
    entry.meta.task = msg.taskDescription || '';
    entry.meta.status = 'running';
  } else if (msg.type === 'agentDone' || msg.type === 'agentEnd') {
    entry.meta.status = 'done';
  }
  
  setActiveView(entry.view);
  
  // 如果 popup 打开且显示这个子代理，自动滚动 + 更新 footer
  if (currentPopupId === msg.nodeSessionId) {
    requestAnimationFrame(() => { /* auto-scroll */ });
    updateFooterStatus(entry);
  }
  return true;
}

// 注册拦截器
setDelegateStepInterceptor(interceptDelegateStep);

export function openDelegatePopup(subagentId, agentName, taskDescription) {
  closeDelegatePopup();
  currentPopupId = subagentId;
  
  const entry = ensureDelegateView(subagentId);
  
  // 构建并挂载 popup overlay（同 flowAgentPopup 的 modal 结构）
  popupOverlay = document.createElement('div');
  popupOverlay.className = 'flow-agent-overlay';  // 复用样式
  popupOverlay.innerHTML = `
    <div class="flow-agent-modal">
      <div class="flow-agent-header">
        <span class="flow-agent-name">${agentName}</span>
        <span class="flow-agent-subtitle">${subagentId}</span>
        <div class="flow-agent-close">✕</div>
      </div>
      <div class="flow-agent-footer running">
        <span class="fa-status-dot"></span>
        <span class="fa-task">${taskDescription}</span>
      </div>
    </div>
  `;
  
  // 挂载到主聊天区域（不是 flow 画布）
  const mountEl = document.querySelector('#chat') || document.body;
  mountEl.appendChild(popupOverlay);
  
  // 将预渲染的 container 移入 modal
  const modal = popupOverlay.querySelector('.flow-agent-modal');
  const footer = popupOverlay.querySelector('.flow-agent-footer');
  modal.insertBefore(entry.container, footer);
  entry.footerEl = footer;
  
  // 关闭事件
  popupOverlay.addEventListener('click', (e) => {
    if (e.target === popupOverlay || e.target.classList.contains('flow-agent-close')) {
      closeDelegatePopup();
    }
  });
}

export function closeDelegatePopup() {
  if (!popupOverlay) return;
  if (currentPopupId) {
    const entry = stepViews.get(currentPopupId);
    if (entry) {
      // 将 container 移回隐藏根（保留内容，用户可以重新打开）
      getHiddenRoot().appendChild(entry.container);
      entry.footerEl = null;
    }
  }
  popupOverlay.remove();
  popupOverlay = null;
  currentPopupId = null;
}

// 子代理完成后的清理（在 agentDone 事件中延迟调用）
export function cleanupDelegateView(subagentId) {
  // 保留 60 秒后清理（用户可能想回顾）
  setTimeout(() => {
    if (currentPopupId === subagentId) return; // popup 还开着，不清理
    const entry = stepViews.get(subagentId);
    if (entry) {
      entry.container.remove();
      stepViews.delete(subagentId);
    }
  }, 60000);
}
```

**关键设计**：
- `stepViews` 与 `flowAgentPopup.js` 的 `stepViews` 是**独立的 Map**，不会互相干扰
- 复用 `flow-agent-*` CSS class（视觉一致）
- popup 挂载在主聊天区域，而不是 flow 画布
- 子代理完成后保留内容 60 秒，允许用户回顾

**改动量**：约 150 行新文件。

#### 文件：`src/main/resources/web/js/main.js`

**改动 1：导入 delegatePopup**

```javascript
import { openDelegatePopup } from './delegatePopup.js';
```

**改动 2：修改 `renderDelegateDropdown()`，让条目可点击**

当前代码（`main.js:1374-1399`）渲染 dropdown 条目为纯文本。改为可点击的条目：

```javascript
function renderDelegateDropdown() {
  ...
  listEl.innerHTML = entries.map(([id, info]) => {
    ...
    return '<div class="bg-task-row delegate-entry" data-agent-id="' + id + '">' +
      '<div class="bg-task-info">' +
        '<span class="bg-task-name">' + status + ' ' + label + '</span>' +
        toolPart +
      '</div>' +
    '</div>';
  }).join('');
  
  // 绑定点击事件
  listEl.querySelectorAll('.delegate-entry').forEach(el => {
    el.addEventListener('click', () => {
      const aid = el.dataset.agentId;
      const info = delegates[aid];
      if (info) {
        openDelegatePopup(aid, info.name || aid, info.task || 'Sub-agent');
      }
    });
  });
}
```

**改动 3：在 `agentDone` handler 中触发清理**（`main.js:1464`）

在现有的 `onMessage('agentDone', ...)` handler 中，sub-agent done 后调用 cleanup：

```javascript
import { cleanupDelegateView } from './delegatePopup.js';

onMessage('agentDone', (msg, view) => {
  ...existing code...
  // 新增：触发 delegate popup 清理
  const aid = msg.agentId;
  if (aid && aid.startsWith('delegate-')) {
    cleanupDelegateView(aid);
  }
});
```

**改动量**：约 20 行代码变更。

#### 文件：`src/main/resources/web/index.html`

**改动：添加 delegatePopup.js 的 script 标签**

在 `<script type="module" src="js/flowAgentPopup.js"></script>` 之后添加：
```html
<script type="module" src="js/delegatePopup.js"></script>
```

---

## 3. 交互设计

### 3.1 用户操作流程

```
1. Agent 调用 Delegate 工具
   → 主聊天显示 Delegate 工具卡片（spinner）
   → 头部 delegate indicator 显示计数（如 "2"）

2. 用户想查看子代理进展
   → 点击头部 delegate indicator
   → 展开下拉列表，显示每个子代理的状态
   → 每条显示：代理名 · 任务描述 · 当前工具 · running/done 状态

3. 用户点击某条子代理
   → 弹出实时消息窗口（glass modal）
   → 窗口内实时显示子代理的：文本输出、工具调用卡片、思考状态
   → footer 显示状态：running（蓝点脉冲）→ done（绿点）

4. 子代理完成
   → popup footer 变为 "done"
   → 主聊天的 Delegate 工具卡片显示结果
   → popup 保持打开，用户可以回顾内容
   → 用户关闭 popup 后，60 秒后自动清理

5. 用户可以同时只打开一个 popup
   → 打开新子代理的 popup 会关闭前一个
```

### 3.2 与现有系统的关系

| 实体 | 实时窗口机制 | 路由 ID | 触发入口 |
|------|-------------|---------|----------|
| Teams/Agents | 持久化标签页 | sessionId | 侧边栏点击 |
| Flows 节点代理 | flowAgentPopup | nodeSessionId（唯一） | Flow 画布 agent pill 点击 |
| **Delegate 子代理** | **delegatePopup**（新） | **nodeSessionId = subagentId**（新） | **Delegate dropdown 条目点击**（新） |

三套机制完全独立，互不干扰：
- Teams/Agents 用持久化 session 标签页
- Flows 用 `flowStepInterceptor` + flow 画布入口
- Delegate 用 `delegateStepInterceptor`（新）+ dropdown 入口

---

## 4. 预期变更展示

### 4.1 行为变更说明

**当前行为**：
> 当 Agent 使用 Delegate 工具派出子代理后，用户在头部可以看到一个小指示器和下拉列表。列表只显示每个子代理的名字、任务摘要和当前在调用的工具名（如 "running · Coder · Implement API · Read"）。用户**看不到子代理在做什么**——读什么文件、写什么代码、思考什么——只能等子代理完成后在主聊天看到最终结果摘要。

**改完后行为**：
> 同样的指示器和下拉列表照常显示。但现在**下拉列表中每个子代理条目可以点击**。点击后弹出一个实时消息窗口（类似 Flow 画布中点击 agent 节点弹出的窗口），里面实时展示子代理的完整工作过程：正在输出的文本、正在调用的工具（Read、Write、Bash 等的卡片）、思考状态。窗口底部有状态指示灯（蓝色脉冲=运行中，绿色=完成）。子代理完成后，窗口保持打开，用户可以回顾整个过程。关闭窗口后内容保留 60 秒以防用户想重新查看。

### 4.2 视觉预览

详见 Pop 展示的 UI 线框图（`/tmp/delegate_ui.html`）和架构对比图（`/tmp/delegate_current.svg` vs `/tmp/delegate_proposed.svg`）。

---

## 5. 验收条件

### 5.1 后端

| 条件 | 验证方式 | 预期 |
|------|---------|------|
| routeWsSend 注入 subagentId 到 nodeSessionId | 编译 + 单元测试 | `sbt compile` 通过 |
| DelegateToolSpec 测试通过 | `sbt "testOnly nebflow.core.tools.DelegateToolSpec"` | 全部通过 |
| 事件 JSON 中 nodeSessionId != sessionId | 手动测试：Delegate 一个子代理，检查 WS 事件 | nodeSessionId 为 `delegate-xxx-yyy` 唯一值 |

### 5.2 前端

| 条件 | 验证方式 | 预期 |
|------|---------|------|
| delegatePopup.js 正确加载 | 浏览器控制台无报错 | 无 JS 错误 |
| Dropdown 条目可点击 | Agent 调用 Delegate 后，点击头部 indicator → 点击条目 | 弹出 popup |
| Popup 显示实时流 | 在 popup 中观察子代理工作 | 可见文本流、工具卡片、思考状态 |
| 多个子代理不冲突 | Agent 并行 Delegate 2 个子代理 | 两个条目各自独立，点击各自打开不同内容 |
| Popup 关闭后可重开 | 关闭 popup 后再点 dropdown 条目 | 内容仍在，可重新查看 |
| agentDone 后 popup 状态正确 | 等子代理完成 | footer 从蓝色脉冲变为绿色 done |
| Flow popup 不受影响 | 运行一个 Flow（如 code-review） | Flow agent popup 正常工作，不被 delegate 拦截器干扰 |
| 主聊天 Delegate 工具卡片正常 | 子代理完成后 | 工具卡片显示结果（不变） |

### 5.3 回归测试

| 条件 | 验证方式 |
|------|---------|
| 现有 Teams 实时消息窗口正常 | 开一个 Team session，观察 Manager 和成员消息 |
| 现有 Flows 实时消息窗口正常 | 运行一个 Flow，点击 agent pill 查看 popup |
| 现有 Agent 详情页对话窗口正常 | 打开一个 standalone agent 的 session |
| Delegate persistent 模式正常 | `lifecycle: "persistent"` 的 Delegate 也能打开 popup |

---

## 6. 涉及文件清单

### 后端（1 文件，~6 行变更）

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `src/main/scala/nebflow/core/tools/DelegateTool.scala` | 修改 | `routeWsSend` 新增 `subagentId` 参数 + 覆盖 `nodeSessionId` |

### 前端（4 文件，~195 行变更）

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `src/main/resources/web/js/ws.js` | 修改 | 新增 `delegateStepInterceptor` 分支（~25 行） |
| `src/main/resources/web/js/delegatePopup.js` | **新建** | 完整的 delegate popup 模块（~150 行） |
| `src/main/resources/web/js/main.js` | 修改 | dropdown 条目可点击 + agentDone 清理（~20 行） |
| `src/main/resources/web/index.html` | 修改 | 添加 `delegatePopup.js` 的 `<script>` 标签（1 行） |

### 测试文件（1 文件）

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `src/test/scala/nebflow/core/tools/DelegateToolSpec.scala` | 修改 | 添加 `routeWsSend` 注入 subagentId 的测试用例 |

---

## 7. 风险和注意事项

### 7.1 nodeSessionId 语义变更

当前 `nodeSessionId` 用于 Flow 节点代理。修改后 Delegate 子代理也使用 `nodeSessionId`，但值不同（Flow 用 flow 内 session ID，Delegate 用 `delegate-xxx` 前缀 ID）。通过 `agentId.startsWith('delegate-')` 区分，互不干扰。

### 7.2 popup 位置

Flow popup 挂载在 flow 画布内（`.canvas-tab-pane`）。Delegate popup 需要挂载在主聊天区域（`#chat` 或 `document.body`），因为用户在主聊天中触发。

### 7.3 CSS 复用

delegatePopup.js 复用 `flow-agent-*` CSS class。需要确保这些样式不在 flow 画布之外有布局问题。可能需要为 delegate popup 调整 `position` 策略（从 flow card 内的 absolute 改为 document 级别的 fixed/absolute）。

### 7.4 并发子代理

多个子代理同时运行时，dropdown 显示多条，但同一时间只能打开一个 popup（打开新的会关闭旧的）。这是合理的行为——用户一次关注一个子代理。

### 7.5 内存管理

每个子代理的 ChatView 在完成后 60 秒清理。如果用户大量使用 Delegate（比如 5+ 并发），隐藏 DOM 容器会短暂累积。60 秒后自动清理，不会持续增长。
