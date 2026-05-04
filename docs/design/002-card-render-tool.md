# CardRender Tool — Design Document

> Status: Design Phase  
> Goal: Allow agents to push structured card data to the frontend for rich rendering (project structure, workflow progress, etc.)

---

## 1. Overview

Currently, Nebflow renders chat messages as plain text bubbles and simple tool cards. There is no way for an agent to push a **rich, structured UI card** — e.g., a project directory tree, a workflow step tracker, or a file comparison diff — without embedding raw HTML in markdown.

**CardRender** is a new tool that lets agents emit typed JSON payloads. The frontend maintains a **card registry** that maps each card `type` to a renderer function, producing rich, interactive DOM elements inline in the chat stream.

---

## 2. Design Principles

| Principle | Rationale |
|-----------|-----------|
| **Agent-driven** | The agent decides when a card is appropriate, not the frontend. |
| **Schema-first** | Each card type declares its JSON schema; the LLM produces valid data. |
| **Frontend-agnostic backend** | The backend only validates and forwards JSON; rendering is 100% frontend. |
| **Extensible** | New card types are added by registering a schema + renderer pair. No backend changes needed for new visualizations. |
| **Non-blocking** | Cards are informational. They do not pause agent execution or require user interaction (unless the card itself opts in). |

---

## 3. User Scenarios

### 3.1 Project Structure Overview
User: "帮我看看这个项目的框架结构"  
Agent calls `CardRender` with `type: "project_tree"`, pushing a collapsible directory tree card.

### 3.2 Workflow Progress
User: "运行测试工作流"  
Agent calls `CardRender` with `type: "workflow_steps"`, updating step states as the workflow proceeds.

### 3.3 File Diff Summary
Agent calls `CardRender` with `type: "file_diff"`, showing a side-by-side diff of proposed changes.

### 3.4 Task Board Snapshot
Agent calls `CardRender` with `type: "task_board"`, rendering a Kanban-style board of current tasks.

---

## 4. Backend Design

### 4.1 Tool Definition

```scala
// src/main/scala/nebflow/core/tools/CardRenderTool.scala

package nebflow.core.tools

import cats.effect.IO
import io.circe.{Json, JsonObject}
import nebflow.shared.*

object CardRenderTool extends Tool:
  val name = "CardRender"

  val description =
    """Render a structured UI card in the chat frontend.
      |
      |Use this when you want to present information in a rich visual format
      |instead of plain text. Examples: project directory tree, workflow step
      |tracker, task board, file diff, chart, table.
      |
      |The card will appear inline in the user's chat stream.
      |""".stripMargin

  val inputSchema: JsonObject = JsonObject.fromIterable(List(
    "type" -> Json.fromString("object"),
    "properties" -> Json.fromFields(List(
      "cardType" -> Json.fromFields(List(
        "type" -> Json.fromString("string"),
        "description" -> Json.fromString(
          "The card type to render. Supported: project_tree, workflow_steps, file_diff, task_board, markdown_table, chart_bar, alert"
        )
      )),
      "title" -> Json.fromFields(List(
        "type" -> Json.fromString("string"),
        "description" -> Json.fromString("Optional card title displayed at the top")
      )),
      "data" -> Json.fromFields(List(
        "type" -> Json.fromString("object"),
        "description" -> Json.fromString("Card-specific payload. Schema depends on cardType.")
      )),
      "options" -> Json.fromFields(List(
        "type" -> Json.fromString("object"),
        "description" -> Json.fromString("Optional rendering options (e.g. collapsible, theme)")
      ))
    )),
    "required" -> Json.fromValues(List(Json.fromString("cardType"), Json.fromString("data")))
  ))

  def call(input: JsonObject, ctx: ToolContext): IO[Either[ToolError, String]] =
    // Validation only — ensure cardType is known and data conforms to registered schema
    val cardType = input("cardType").flatMap(_.asString).getOrElse("")
    val data     = input("data").getOrElse(Json.obj())
    val title    = input("title").flatMap(_.asString).getOrElse("")
    val options  = input("options").getOrElse(Json.obj())

    CardSchemaRegistry.validate(cardType, data) match
      case Left(err) => IO.pure(Left(ToolError(s"Card validation failed: $err")))
      case Right(_)  =>
        // Push to frontend via WebSocket
        val payload = Json.obj(
          "type"     -> "cardRender".asJson,
          "cardType" -> cardType.asJson,
          "title"    -> title.asJson,
          "data"     -> data,
          "options"  -> options,
          "sessionId"-> ctx.sessionId.asJson
        )
        ctx.wsSend match
          case Some(send) => send(payload).as(Right(s"Card [$cardType] rendered"))
          case None       => IO.pure(Left(ToolError("No WebSocket connection available")))

  def summarize(input: JsonObject): String =
    val cardType = input("cardType").flatMap(_.asString).getOrElse("unknown")
    s"CardRender($cardType)"

  def summarizeResult(input: JsonObject, result: String): String = result
```

### 4.2 Schema Registry (Backend)

```scala
// src/main/scala/nebflow/core/tools/CardSchemaRegistry.scala

package nebflow.core.tools

import io.circe.{Json, JsonObject}
import io.circe.schema.Schema

object CardSchemaRegistry:
  private val schemas = scala.collection.mutable.Map[String, Schema]()

  // Built-in schemas registered at startup
  def register(cardType: String, schemaJson: String): Unit =
    schemas += (cardType -> Schema.load(schemaJson))

  def validate(cardType: String, data: Json): Either[String, Unit] =
    schemas.get(cardType) match
      case Some(schema) =>
        schema.validate(data.asJson) match
          case Nil => Right(())
          case errs => Left(errs.mkString("; "))
      case None => Left(s"Unknown card type: $cardType")
```

> **Note:** JSON Schema validation is optional in v1. We can start with a lightweight check (cardType exists in registry) and add full schema validation later.

### 4.3 WebSocket Protocol Extension

New message type pushed from backend to frontend:

```json
{
  "type": "cardRender",
  "cardType": "project_tree",
  "title": "Project Structure",
  "data": { ... },
  "options": { "collapsible": true },
  "sessionId": "sess-abc123"
}
```

---

## 5. Frontend Design

### 5.1 Card Registry

```javascript
// src/main/resources/web/js/cards/registry.js

const cardRenderers = new Map();

export function registerCardType(cardType, rendererFn) {
  cardRenderers.set(cardType, rendererFn);
}

export function renderCard(cardType, title, data, options) {
  const renderer = cardRenderers.get(cardType);
  if (!renderer) {
    console.warn('[card] No renderer for type:', cardType);
    return renderFallbackCard(cardType, title, data);
  }
  try {
    return renderer(title, data, options);
  } catch (e) {
    console.error('[card] Render error:', e);
    return renderFallbackCard(cardType, title, data);
  }
}

function renderFallbackCard(cardType, title, data) {
  const el = document.createElement('div');
  el.className = 'card card-fallback';
  el.innerHTML = `<div class="card-title">${escapeHtml(title || cardType)}</div>
    <pre>${escapeHtml(JSON.stringify(data, null, 2))}</pre>`;
  return el;
}
```

### 5.2 Built-in Card Types

#### 5.2.1 `project_tree` — Directory Tree

```javascript
// src/main/resources/web/js/cards/projectTree.js

import { registerCardType } from './registry.js';

registerCardType('project_tree', (title, data, options) => {
  const container = document.createElement('div');
  container.className = 'card card-project-tree';

  if (title) {
    const h = document.createElement('div');
    h.className = 'card-title';
    h.textContent = title;
    container.appendChild(h);
  }

  const tree = document.createElement('ul');
  tree.className = 'project-tree';
  renderNode(tree, data.root || data);
  container.appendChild(tree);

  return container;
});

function renderNode(parentUl, node, level = 0) {
  const li = document.createElement('li');
  li.style.paddingLeft = `${level * 16}px`;

  const isDir = node.children && node.children.length > 0;
  const icon = isDir ? '📁' : '📄';

  const row = document.createElement('div');
  row.className = 'tree-row';
  row.innerHTML = `<span class="tree-icon">${icon}</span><span class="tree-name">${escapeHtml(node.name)}</span>`;

  if (isDir && node.collapsible !== false) {
    row.classList.add('collapsible');
    row.onclick = () => {
      const childUl = li.querySelector('ul');
      if (childUl) {
        childUl.classList.toggle('collapsed');
        row.classList.toggle('collapsed');
      }
    };
  }

  li.appendChild(row);

  if (isDir) {
    const childUl = document.createElement('ul');
    node.children.forEach(child => renderNode(childUl, child, level + 1));
    li.appendChild(childUl);
  }

  parentUl.appendChild(li);
}
```

**Expected `data` schema:**
```json
{
  "root": {
    "name": "Nebflow",
    "children": [
      { "name": "src", "children": [
        { "name": "main", "children": [
          { "name": "scala", "children": [
            { "name": "nebflow", "children": [
              { "name": "agent" },
              { "name": "core" },
              { "name": "cli" }
            ]}
          ]}
        ]},
        { "name": "test" }
      ]},
      { "name": "build.sbt" },
      { "name": "README.md" }
    ]
  }
}
```

#### 5.2.2 `workflow_steps` — Step Progress

```javascript
// src/main/resources/web/js/cards/workflowSteps.js

registerCardType('workflow_steps', (title, data, options) => {
  const container = document.createElement('div');
  container.className = 'card card-workflow';
  container.dataset.stepCardId = data.id || '';

  if (title) {
    const h = document.createElement('div');
    h.className = 'card-title';
    h.textContent = title;
    container.appendChild(h);
  }

  const stepsEl = document.createElement('div');
  stepsEl.className = 'workflow-steps';
  (data.steps || []).forEach((step, idx) => {
    const stepEl = document.createElement('div');
    stepEl.className = `workflow-step step-${step.status || 'pending'}`;
    stepEl.dataset.stepIndex = idx;
    stepEl.innerHTML = `
      <div class="step-indicator"></div>
      <div class="step-body">
        <div class="step-name">${escapeHtml(step.name)}</div>
        ${step.detail ? `<div class="step-detail">${escapeHtml(step.detail)}</div>` : ''}
      </div>
    `;
    stepsEl.appendChild(stepEl);
  });
  container.appendChild(stepsEl);

  return container;
});
```

**Expected `data` schema:**
```json
{
  "id": "workflow-001",
  "steps": [
    { "name": "Clone repo", "status": "done", "detail": "Cloned into /tmp/repo" },
    { "name": "Install deps", "status": "running", "detail": "npm install in progress..." },
    { "name": "Run tests", "status": "pending" },
    { "name": "Deploy", "status": "pending" }
  ]
}
```

**Update mechanism:** If a card with the same `id` already exists in the chat, update its DOM in place instead of appending a new card. This allows the agent to push progress updates.

#### 5.2.3 `file_diff` — Side-by-Side Diff

```javascript
// src/main/resources/web/js/cards/fileDiff.js

registerCardType('file_diff', (title, data, options) => {
  const container = document.createElement('div');
  container.className = 'card card-diff';

  if (title) {
    const h = document.createElement('div');
    h.className = 'card-title';
    h.textContent = title;
    container.appendChild(h);
  }

  const diffWrap = document.createElement('div');
  diffWrap.className = 'diff-wrapper';

  (data.hunks || []).forEach(hunk => {
    const hunkEl = document.createElement('div');
    hunkEl.className = 'diff-hunk';
    hunkEl.innerHTML = `<div class="diff-hunk-header">${escapeHtml(hunk.header || '')}</div>`;

    const table = document.createElement('table');
    table.className = 'diff-table';
    (hunk.lines || []).forEach(line => {
      const tr = document.createElement('tr');
      tr.className = `diff-line diff-${line.type}`; // 'add', 'del', 'ctx'
      tr.innerHTML = `
        <td class="diff-ln">${line.oldLn || ''}</td>
        <td class="diff-ln">${line.newLn || ''}</td>
        <td class="diff-code">${escapeHtml(line.text)}</td>
      `;
      table.appendChild(tr);
    });
    hunkEl.appendChild(table);
    diffWrap.appendChild(hunkEl);
  });

  container.appendChild(diffWrap);
  return container;
});
```

#### 5.2.4 `task_board` — Kanban Board

```javascript
// src/main/resources/web/js/cards/taskBoard.js

registerCardType('task_board', (title, data, options) => {
  const container = document.createElement('div');
  container.className = 'card card-task-board';

  if (title) {
    const h = document.createElement('div');
    h.className = 'card-title';
    h.textContent = title;
    container.appendChild(h);
  }

  const board = document.createElement('div');
  board.className = 'kanban-board';

  (data.columns || []).forEach(col => {
    const colEl = document.createElement('div');
    colEl.className = 'kanban-column';
    colEl.innerHTML = `<div class="kanban-header">${escapeHtml(col.name)}</div>`;

    const cardsEl = document.createElement('div');
    cardsEl.className = 'kanban-cards';
    (col.tasks || []).forEach(task => {
      const card = document.createElement('div');
      card.className = `kanban-card kanban-priority-${task.priority || 'normal'}`;
      card.innerHTML = `
        <div class="kanban-card-title">${escapeHtml(task.title)}</div>
        ${task.tags ? `<div class="kanban-tags">${task.tags.map(t => `<span class="tag">${escapeHtml(t)}</span>`).join('')}</div>` : ''}
      `;
      cardsEl.appendChild(card);
    });
    colEl.appendChild(cardsEl);
    board.appendChild(colEl);
  });

  container.appendChild(board);
  return container;
});
```

#### 5.2.5 `alert` — Status Alert Banner

```javascript
// src/main/resources/web/js/cards/alert.js

registerCardType('alert', (title, data, options) => {
  const container = document.createElement('div');
  container.className = `card card-alert card-alert-${data.level || 'info'}`;

  const iconMap = { info: 'ℹ️', success: '✅', warning: '⚠️', error: '❌' };
  const icon = iconMap[data.level] || iconMap.info;

  container.innerHTML = `
    <div class="alert-icon">${icon}</div>
    <div class="alert-body">
      ${title ? `<div class="alert-title">${escapeHtml(title)}</div>` : ''}
      <div class="alert-message">${escapeHtml(data.message)}</div>
    </div>
  `;
  return container;
});
```

### 5.3 CSS Base Styles

```css
/* src/main/resources/web/css/cards.css */

.card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  padding: 12px 14px;
  margin: 6px 0;
  max-width: 90%;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
  animation: fadeIn 0.2s ease;
}

.card-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 8px;
  color: var(--color-text);
}

/* --- project_tree --- */
.project-tree { list-style: none; margin: 0; padding: 0; font-size: 13px; }
.project-tree ul { list-style: none; margin: 0; padding: 0; }
.tree-row { display: flex; align-items: center; gap: 6px; padding: 2px 0; cursor: default; }
.tree-row.collapsible { cursor: pointer; }
.tree-row.collapsible::before {
  content: '▾'; font-size: 10px; color: #999; width: 12px; text-align: center;
}
.tree-row.collapsible.collapsed::before { content: '▸'; }
.project-tree ul.collapsed { display: none; }
.tree-icon { font-size: 13px; }
.tree-name { font-family: ui-monospace, SFMono-Regular, monospace; }

/* --- workflow_steps --- */
.workflow-steps { display: flex; flex-direction: column; gap: 8px; }
.workflow-step {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 8px 10px; border-radius: 8px; background: #f8f9fa;
}
.workflow-step.step-done { opacity: 0.7; }
.workflow-step.step-running { background: #e3f2fd; }
.workflow-step.step-error { background: #ffebee; }
.step-indicator {
  width: 18px; height: 18px; border-radius: 50%; flex-shrink: 0;
  border: 2px solid #ccc; margin-top: 2px;
}
.step-done .step-indicator { background: var(--color-success); border-color: var(--color-success); }
.step-running .step-indicator { border-color: var(--color-primary); border-top-color: transparent; animation: spin 1s linear infinite; }
.step-error .step-indicator { background: var(--color-error); border-color: var(--color-error); }
.step-name { font-weight: 500; font-size: 13px; }
.step-detail { font-size: 12px; color: #666; margin-top: 2px; }

/* --- file_diff --- */
.diff-wrapper { font-family: ui-monospace, SFMono-Regular, monospace; font-size: 12px; overflow-x: auto; }
.diff-hunk { margin-bottom: 8px; }
.diff-hunk-header { color: #666; font-size: 11px; margin-bottom: 4px; }
.diff-table { width: 100%; border-collapse: collapse; }
.diff-table td { padding: 1px 6px; white-space: pre; }
.diff-ln { color: #999; min-width: 28px; text-align: right; user-select: none; background: #f5f5f5; }
.diff-add { background: #e8f5e9; }
.diff-del { background: #ffebee; }
.diff-ctx { background: #fff; }

/* --- task_board --- */
.kanban-board { display: flex; gap: 10px; overflow-x: auto; }
.kanban-column { min-width: 160px; flex: 1; background: #f5f5f5; border-radius: 8px; padding: 8px; }
.kanban-header { font-weight: 600; font-size: 12px; margin-bottom: 8px; text-align: center; }
.kanban-cards { display: flex; flex-direction: column; gap: 6px; }
.kanban-card { background: #fff; border-radius: 6px; padding: 8px; font-size: 12px; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
.kanban-card-title { font-weight: 500; }
.kanban-tags { margin-top: 4px; display: flex; gap: 4px; flex-wrap: wrap; }
.tag { background: #e3f2fd; color: #1565c0; padding: 1px 6px; border-radius: 10px; font-size: 10px; }

/* --- alert --- */
.card-alert { display: flex; align-items: flex-start; gap: 10px; }
.card-alert-success { border-left: 4px solid var(--color-success); }
.card-alert-warning { border-left: 4px solid #ff9800; }
.card-alert-error { border-left: 4px solid var(--color-error); }
.card-alert-info { border-left: 4px solid var(--color-primary); }
.alert-icon { font-size: 16px; }
.alert-title { font-weight: 600; font-size: 13px; }
.alert-message { font-size: 12px; color: #555; margin-top: 2px; }

/* dark theme */
@media (prefers-color-scheme: dark) {
  .card { background: #1e1e1e; border-color: #333; }
  .tree-row.collapsible::before { color: #666; }
  .workflow-step { background: #2a2a2a; }
  .workflow-step.step-running { background: #1a3a4a; }
  .workflow-step.step-error { background: #3a1a1a; }
  .diff-ln { background: #2a2a2a; color: #777; }
  .diff-add { background: #1a3a1a; }
  .diff-del { background: #3a1a1a; }
  .kanban-column { background: #2a2a2a; }
  .kanban-card { background: #1e1e1e; }
}
```

### 5.4 WebSocket Handler Integration

In `main.js`, register the new handler:

```javascript
import { renderCard } from './js/cards/registry.js';

onMessage('cardRender', (msg) => {
  if (!isActive(msg)) {
    // Non-active session: save serialized card for later restore
    saveMsg({
      type: 'card',
      cardType: msg.cardType,
      title: msg.title,
      data: msg.data,
      options: msg.options
    }, msg.sessionId);
    markSessionUnread(msg.sessionId);
    return;
  }

  const chat = state.dom.chat;
  const existing = msg.data?.id ? chat.querySelector(`[data-card-id="${msg.data.id}"]`) : null;

  if (existing) {
    // In-place update for cards with an ID (e.g., workflow progress)
    const newEl = renderCard(msg.cardType, msg.title, msg.data, msg.options);
    if (newEl) {
      newEl.dataset.cardId = msg.data.id;
      existing.replaceWith(newEl);
    }
  } else {
    const row = document.createElement('div');
    row.className = 'row card-row';
    const cardEl = renderCard(msg.cardType, msg.title, msg.data, msg.options);
    if (cardEl) {
      if (msg.data?.id) cardEl.dataset.cardId = msg.data.id;
      row.appendChild(cardEl);
      chat.appendChild(row);
      smartScroll();
    }
  }

  saveMsg({
    type: 'card',
    cardType: msg.cardType,
    title: msg.title,
    data: msg.data,
    options: msg.options
  }, msg.sessionId);
});
```

### 5.5 Persistence / Restore

Cards must survive page reload. In `persistence.js`, extend `restoreFromStorage` to handle `type: 'card'` messages by re-calling `renderCard`.

---

## 6. Integration into Agent System

### 6.1 Tool Registration

Add `CardRenderTool` to `ToolRegistry.ALL_TOOLS`:

```scala
// In ToolRegistry.scala
"CardRender" -> CardRenderTool,
```

### 6.2 Schema Registration at Boot

```scala
// In Main.scala or a dedicated init block
CardSchemaRegistry.register("project_tree",  ProjectTreeSchema.json)
CardSchemaRegistry.register("workflow_steps", WorkflowStepsSchema.json)
CardSchemaRegistry.register("file_diff",      FileDiffSchema.json)
CardSchemaRegistry.register("task_board",     TaskBoardSchema.json)
CardSchemaRegistry.register("alert",          AlertSchema.json)
```

### 6.3 System Prompt Guidance

Add a brief note to the system prompt (or agent system prompt) so the LLM knows when to use `CardRender`:

```
When presenting structured information such as:
- Project directory structures
- Multi-step workflow progress
- File diffs or comparisons
- Task boards or lists

Use the CardRender tool with the appropriate cardType instead of plain text.
```

---

## 7. Example Agent Conversation Flow

```
User: 帮我看看这个项目的结构

Agent: [thinks] The user wants to see the project structure. I'll use Glob + Read
       to explore, then render a project_tree card.

Agent -> CardRender:
  cardType: "project_tree"
  title: "Project Structure"
  data: { root: { name: "Nebflow", children: [...] } }

[Frontend renders a collapsible directory tree card]

Agent: 项目采用 Scala + Akka/Pekko 架构，核心模块如下...
```

---

## 8. Open Questions / Future Work

| Topic | Proposal |
|-------|----------|
| **User interaction inside cards** | v1 is read-only. v2 could support buttons inside cards that send WS messages back (e.g., "Approve this diff"). |
| **Custom card types via plugins** | Allow users to drop a `.js` file into `~/.nebflow/cards/` that registers a new renderer at frontend boot. |
| **Schema validation depth** | v1: check cardType exists. v2: full JSON Schema validation against registered schemas. |
| **Mobile layout** | Cards should be responsive. Task board may stack vertically on narrow screens. |
| **Animation on update** | Workflow step transitions could animate (progress bar, checkmark pop). |

---

## 9. File Changes Summary

### New Files

| Path | Description |
|------|-------------|
| `src/main/scala/nebflow/core/tools/CardRenderTool.scala` | Tool implementation |
| `src/main/scala/nebflow/core/tools/CardSchemaRegistry.scala` | Schema registry |
| `src/main/resources/web/js/cards/registry.js` | Frontend card registry |
| `src/main/resources/web/js/cards/projectTree.js` | Project tree renderer |
| `src/main/resources/web/js/cards/workflowSteps.js` | Workflow steps renderer |
| `src/main/resources/web/js/cards/fileDiff.js` | File diff renderer |
| `src/main/resources/web/js/cards/taskBoard.js` | Task board renderer |
| `src/main/resources/web/js/cards/alert.js` | Alert banner renderer |
| `src/main/resources/web/css/cards.css` | Card styles |

### Modified Files

| Path | Change |
|------|--------|
| `src/main/scala/nebflow/core/tools/registry.scala` | Register `CardRenderTool` |
| `src/main/resources/web/js/main.js` | Add `cardRender` WS handler, import card modules |
| `src/main/resources/web/js/persistence.js` | Handle `type: 'card'` on restore |
| `src/main/resources/web/index.html` | Add `<link rel="stylesheet" href="css/cards.css">` |

---

## 10. Acceptance Criteria

- [ ] Agent can call `CardRender` with `project_tree` and frontend displays a collapsible directory tree.
- [ ] Agent can push `workflow_steps` updates; same card updates in place when `id` matches.
- [ ] Unknown card types fall back to a JSON dump instead of crashing.
- [ ] Cards survive page reload (persisted to `localStorage` and restored correctly).
- [ ] Dark theme renders cards correctly.
- [ ] Tool appears in agent tool list and is selectable per-agent.
