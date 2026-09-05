> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# 文件拖拽到聊天输入框 — 实现规格（#303）

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-08-19 | 初版。方向已用户裁定：仅输入栏本体命中 / 视觉保持仅虚线高亮（蒙层+文案本期不做）/ 弹窗视图一并覆盖（事件委托自然覆盖） |

---

## 1. 现状分析

### 1.1 附件上传机制（完整链路，全部复用）

```
入口（3 个）                    归一化                     发送
────────────────────────────────────────────────────────────────
attach 按钮 (input.js:1204) ─┐
输入框 paste (input.js:1070) ─┼→ addFileAttachment(file, cb, target)
body 级 drop (input.js:1241) ─┘   (input.js:400)
                                     │
                                     ├─ image/*：≤10MB → canvas 压缩(≤1920px, q=0.8)
                                     │   → base64 → attachments.push
                                     │   （失败回退：原文件 arrayBuffer→base64）
                                     │
                                     └─ 非 image：arrayBuffer + SHA-256 hash
                                         → <5MB 附 base64，≥5MB 仅元数据
                                         → attachments.push
                                     │
                                     ▼
                                 renderAttachmentPreview(target)  (chat.js:1778)
                                 图片缩略图 / 文件名 chip + ✕ 移除
                                     │
                                     ▼
                                 send() (input.js:480)
                                 pendingAttachments → WS {mimeType,data,name,hash,size}
```

**关键结论：上传/预览/发送链路完备，无需新建任何通道。** 拖拽只是第 3 个入口，且**已经存在**——`input.js:1219-1267` 在 `document.body` 上注册了 dragenter/dragover/dragleave/drop（仅 `view.id === 'primary'` 时注册）。

### 1.2 现有拖拽的四个缺陷（"看起来不支持"的原因）

| # | 缺陷 | 位置 | 表现 |
|---|------|------|------|
| 1 | **高亮卡死 bug**：`dragleave` 里检查 `e.dataTransfer.types.includes('Files')`，但部分引擎 dragleave 阶段 types 不可读（空数组）→ 计数器永不归零 → `body.drag-over` 类残留，输入栏虚线框永久不消失 | input.js:1232-1240 | 拖一次后高亮卡死，下次拖拽行为混乱，观感像"坏了" |
| 2 | **强制路由主窗口**：drop 一律 `setActiveView(chatViews.primary)` | input.js:1246 | 弹窗（flow/bg-agent 子代理）打开时拖文件，附件落进主窗口会话而非眼前的弹窗 |
| 3 | **弹窗完全无拖拽**：监听仅注册于 primary | input.js:1220 | 弹窗输入栏拖文件无高亮、无响应（且被 #2 劫持到主窗口） |
| 4 | **命中语义过宽**：整个 body 都是 drop 区，`dragover` 无条件 preventDefault | input.js:1228-1231 | 与"拖到输入框"的心智模型不符；文本拖拽等原生行为也被拦截 |

### 1.3 相关既有机制（不得破坏）

- `dropbox.js:243-259` — dropbox 模态打开时在 body 上 **capture 阶段** stopPropagation，屏蔽聊天侧 drop。文档级监听（bubble）会被其正确屏蔽，行为兼容。
- `input.js:1253-1266` — document 级图片粘贴（Cmd/Ctrl+V），与拖拽无关，保留。
- 弹窗 `_inputBound` 只绑定一次（flowAgentPopup.js:351-361 / bgAgentPopup.js:174-184），但弹窗每次打开会**重建输入栏 DOM**——元素级监听在重开后会失效（既有 bug，keydown/paste 同样受累，不在本期范围）。**因此拖拽监听必须走 document 级事件委托，天然免疫该问题。**

### 1.4 现状架构图

![现状架构](assets/file-drag-drop-current.svg)

---

## 2. 改动方向

**一句话：删除 body 级拖拽，改为 document 级事件委托 + 命中判定「输入栏本体」，按输入栏归属路由到对应 ChatView；高亮沿用现有虚线样式，选择器从 body 级改为元素级。**

### 2.1 目标架构图

![目标架构](assets/file-drag-drop-new.svg)

### 2.2 行为变更说明（用户视角）

- **现在**：拖文件到页面任意位置，主输入栏出现蓝色虚线框；松手后文件加入**主窗口**当前会话（无论你在哪个窗口）。拖完一次后虚线框经常永久卡住不消失。弹窗（子代理会话）里拖文件，附件却进了主窗口。
- **改完后**：拖文件**只有悬停在输入栏上**（主窗口或弹窗的输入栏）才会出现蓝色虚线框；松手后文件加入**这个输入栏所属的会话**；拖离输入栏虚线立即消失，永不卡死。拖到输入栏以外的区域松手＝忽略（页面不会跳转去打开文件）。拖文本选区进输入框＝原生文本插入，不受影响。

---

## 3. 精确改动点

> 行号以 2026-08-19 工作区为准（HEAD 881634fd）。共 5 个文件，纯前端，无后端/协议变更。

### 改动 1 — `src/main/resources/web/js/input.js`

**(a) 删除 body 级拖拽块**：`initInput()` 内 `if (view.id === 'primary') { ... }`（L1219-1267）中的 **L1220-1252**（dragenter/dragover/dragleave/drop 四个 body 监听 + dragCounter）整段删除。保留 L1253-1266 的 document 级图片粘贴，guard 不变：

```js
  // Paste image support (Cmd/Ctrl+V) — primary only, unchanged
  if (view.id === 'primary') {
    document.addEventListener('paste', (e) => { /* 原 L1254-1266 原样保留 */ });
  }
```

**(b) 新增导出函数 `initGlobalFileDrop()`**（建议放在 `initInput` 之后、文件末尾前），document 级事件委托：

```js
// ---------- Global drag & drop onto input bars (#303) ----------
// Event delegation on document: popup views recreate their input bar DOM on
// every open (per-element binding would go stale — see _inputBound), so all
// drag listeners live here and resolve the owning ChatView at event time.
const INPUT_BAR_SELECTOR = '#input-bar, .fa-input-bar';

export function initGlobalFileDrop() {
  let dragDepth = 0;    // child-element nesting depth inside the bar
  let activeBar = null; // currently highlighted input bar

  const hasFiles = (e) =>
    !!(e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files'));
  const barOf = (e) =>
    e.target instanceof Element ? e.target.closest(INPUT_BAR_SELECTOR) : null;
  const clearHighlight = () => {
    if (activeBar) activeBar.classList.remove('drag-over');
    activeBar = null; dragDepth = 0;
  };

  document.addEventListener('dragenter', (e) => {
    if (!hasFiles(e)) return;
    const bar = barOf(e);
    if (!bar) return;
    if (bar !== activeBar) clearHighlight();
    activeBar = bar;
    dragDepth++;
    bar.classList.add('drag-over');
  });

  document.addEventListener('dragover', (e) => {
    if (!hasFiles(e)) return;      // native text drags pass through untouched
    e.preventDefault();            // block browser default "open dropped file"
    e.dataTransfer.dropEffect = 'copy';
  });

  // No hasFiles() check here: dataTransfer.types is unreadable during
  // dragleave in some engines (this is the old stuck-highlight bug).
  document.addEventListener('dragleave', () => {
    if (!activeBar) return;
    dragDepth--;
    if (dragDepth <= 0) clearHighlight();
  });

  document.addEventListener('drop', (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();            // never navigate to the dropped file
    const bar = barOf(e);
    clearHighlight();
    if (!bar) return;              // dropped outside any input bar — ignore
    const view = Object.values(chatViews)
      .find(v => v.mounted && v.dom && v.dom.inputBar === bar);
    if (!view || view.dom.input?.readOnly) return; // disabled popup guard
    setActiveView(view);
    const target = { attPreviewEl: view.dom.attPreview,
                     attachments: view.pendingAttachments };
    Array.from(e.dataTransfer.files || []).forEach(f => addFileAttachment(f, null, target));
    view.dom.input?.focus();
  });
}
```

> 设计要点：
> 1. **委托而非元素绑定** — 免疫弹窗重开 DOM 重建（§1.3 既有 bug）。
> 2. **dragleave 无条件递减** — 修复卡死 bug（缺陷 #1）。
> 3. **按 bar 归属路由 view** — 修复劫持主窗口（缺陷 #2）+ 弹窗天然支持（缺陷 #3）。
> 4. **dragover 仅对 Files preventDefault** — 命中收窄（缺陷 #4）+ 保留全页防跳转（浏览器默认会打开拖入的文件，必须拦截）+ 文本拖拽不被拦截。
> 5. **readOnly 守卫** — 弹窗 `!nodeSessionId` 禁用态（attachBtn 已 disabled）下不允许用拖拽绕过。

### 改动 2 — `src/main/resources/web/js/main.js`

**(a)** L184-208 `initChatView({...})` 的 primaryDom 对象中，L189 `chat:` 行后新增一行：

```js
    inputBar: document.getElementById('input-bar'),
```

**(b)** L2295 `initInput(chatViews.primary);` 之后新增一行：

```js
initGlobalFileDrop();
```

并把它加入 L51 的 import 列表（`initInput` 之后插入 `initGlobalFileDrop`）。

### 改动 3 — `src/main/resources/web/js/flowAgentPopup.js`

L330-336 dom 接线区，`v.dom.input = ...` 行后新增：

```js
  v.dom.inputBar = popupOverlay.querySelector('#flow-input-bar');
```

（每次 openStepPopup 都重新赋值 → 委托查找 `v.dom.inputBar === bar` 始终命中当前元素。）

### 改动 4 — `src/main/resources/web/js/bgAgentPopup.js`

L153-159 dom 接线区，`v.dom.input = ...` 行后新增：

```js
  v.dom.inputBar = popupOverlay.querySelector('#bgagent-input-bar');
```

### 改动 5 — `src/main/resources/web/css/input.css`

L51-55 选择器从 body 级改为元素级（覆盖主窗口 + 弹窗两类输入栏）：

```css
/* Drag-over highlight — the input bar being dragged over (#303) */
#input-bar.drag-over,
.fa-input-bar.drag-over {
  outline: 2px dashed var(--color-primary);
  outline-offset: -2px;
}
```

（视觉与现状完全一致：2px 虚线 + `--color-primary`，自动适配暗/亮色。`body.drag-over` 全库唯一消费者就是本条，删除安全。）

---

## 4. 拖拽视觉反馈设计（用户已裁定：仅虚线，不加蒙层/文案）

| 状态 | 触发 | 视觉 |
|------|------|------|
| 默认 | — | 输入栏原样 |
| dragover（悬停输入栏） | dragenter 命中 `#input-bar` / `.fa-input-bar` 且拖拽物含 Files | 该输入栏出现 `outline: 2px dashed var(--color-primary)`（现状同款样式） |
| 移出 | dragleave 计数归零 / drop | 虚线立即消失（修复卡死） |
| drop 后 | 文件加入 pendingAttachments | **复用现有** `renderAttachmentPreview`：图片缩略图 / 文件名 chip + ✕ 移除，位于输入栏上方（与 attach 按钮、粘贴完全一致） |
| 拖拽物不含 Files（如文本选区） | — | 无高亮、无拦截，浏览器原生行为（文本插入输入框） |
| 弹窗禁用态（无 sessionId） | drop 命中禁用弹窗输入栏 | 无高亮、忽略 |

状态流转图：

```
            dragenter(Files)              
   ┌──────┐ ─────────────→ ┌─────────────┐
   │ 默认  │                │ .drag-over  │
   └──────┘ ←───────────── └─────────────┘
            dragleave归零/drop
                │ drop(在栏上)
                ▼
   ┌──────────────────────────┐
   │ addFileAttachment(target) │
   │ → renderAttachmentPreview │ (图片缩略图/文件chip+✕)
   └──────────────────────────┘
```

---

## 5. 验收条件（二值化，前端 QA 简化 — 用户自验）

### 5.1 自动冒烟（可脚本化，先于手工项）

| # | 条件 | 命令/方法 | 通过标准 |
|---|------|----------|---------|
| S1 | 资源服务正常 | `sbt run` 后 `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/js/input.js` | 200 |
| S2 | 新代码已生效 | `curl -s http://localhost:8080/js/input.js \| grep -c initGlobalFileDrop` | ≥ 2（定义+调用经 main.js） |
| S3 | 新 CSS 已生效 | `curl -s http://localhost:8080/css/input.css \| grep -c "fa-input-bar.drag-over"` | ≥ 1 |
| S4 | 旧 body 监听已移除 | `curl -s http://localhost:8080/js/input.js \| grep -c "body.addEventListener('drop'"` | 0 |
| S5 | 页面加载无 JS 错误 | 打开 http://localhost:8080，DevTools Console | 无红色报错 |

### 5.2 手工验收清单（用户自验，每项二值判断）

**主窗口：**

| # | 操作 | 通过标准 |
|---|------|---------|
| M1 | 从 Finder 拖一个文件悬停在主输入栏上（不松手） | 输入栏出现蓝色虚线边框 |
| M2 | 拖离输入栏（移到聊天区，不松手） | 虚线**立即消失**；反复进出 5 次无残留（卡死 bug 修复） |
| M3 | 拖到聊天空白区松手 | 无任何附件产生；**页面不跳转/不打开文件** |
| M4 | 拖一张 ≤10MB 图片到输入栏松手 | 预览区出现图片缩略图 + ✕（与 attach 按钮效果一致） |
| M5 | 拖一个 pdf/txt 到输入栏松手 | 预览区出现文件名 chip + ✕ |
| M6 | 点预览的 ✕ | 对应附件移除 |
| M7 | 加附件后输入文字按 Enter | 用户气泡带附件，agent 正常收到并引用文件 |
| M8 | 拖多个文件（一次 3 个）到输入栏 | 3 个附件全部出现在预览区 |
| M9 | 在输入框内拖动选中文本 | 原生文本拖拽/插入行为不变（不被 Files 逻辑拦截） |
| M10 | Cmd/Ctrl+V 粘贴截图 | 图片附件照常添加（粘贴入口回归） |

**弹窗（flow / bg-agent 二选一验证即可）：**

| # | 操作 | 通过标准 |
|---|------|---------|
| P1 | 打开 flow 步骤弹窗（或 bg-agent 弹窗），拖文件悬停弹窗输入栏 | 弹窗输入栏出现虚线（主窗口输入栏**无**虚线） |
| P2 | 松手 | 预览出现在**弹窗内**的 attachment-preview，主窗口无变化 |
| P3 | 弹窗内输入文字发送 | 附件随消息进入弹窗会话 |
| P4 | 关闭弹窗再重开同一会话，重复 P1-P2 | 依然生效（委托免疫 DOM 重建） |
| P5 | 对"Agent not running"禁用态弹窗拖文件 | 无高亮、无附件（readOnly 守卫） |

**相邻功能回归：**

| # | 操作 | 通过标准 |
|---|------|---------|
| R1 | attach 按钮选文件 | 照常添加（入口并存） |
| R2 | 打开 Dropbox 模态，拖文件到其 dropzone | 走 dropbox 传输流程，**不**进入聊天附件（capture 屏蔽仍有效） |
| R3 | 暗色/亮色模式各看一次 M1 | 虚线颜色随主题正确（--color-primary） |

---

## 6. 风险与回滚

| 风险 | 等级 | 缓解 |
|------|------|------|
| 行为收窄：习惯"全页松手"的用户需拖到输入栏 | 低 | 即 #303 的本意（拖到输入框）；dragover 全页 preventDefault 保留防跳转，误松手无副作用 |
| 移除 body preventDefault 后非 Files 拖拽行为变化 | 低 | 新逻辑仅对 Files preventDefault，文本拖拽反而恢复原生行为（改进） |
| document 级监听与未来新增拖拽功能冲突 | 低 | dropbox 已用 capture stopPropagation 正确隔离；新功能同理可隔离 |
| 弹窗重开监听失效（既有 bug，非本期引入） | — | 本方案委托架构免疫；弹窗 keydown/paste 既有失效问题记录在案，建议另开 issue |
| iframe（HTML 卡片）内拖拽不冒泡 | — | 与现状一致，不处理 |

**回滚**：5 个文件纯前端改动、单 commit 可完成 → `git revert <commit>` 即全量回滚。无数据迁移、无协议变更、无后端改动。回滚后回到"body 级拖拽 + 卡死 bug"现状。

---

## 7. Agent 派发清单（结构化摘要）

```
任务: #303 文件拖拽到聊天输入框（前端，纯静态资源，无构建步骤）
基线: HEAD 881634fd (archive/scala)
约束: 只复用 addFileAttachment/renderAttachmentPreview；禁新建上传通道；禁加蒙层/文案（仅虚线）；不改后端

改动文件 (5):
1. src/main/resources/web/js/input.js
   - 删 L1220-1252 (body 级 drag 四监听+dragCounter)，保留 L1253-1266 粘贴
   - 新增 export initGlobalFileDrop() — document 级委托，见规格 §3(1b) 完整代码
2. src/main/resources/web/js/main.js
   - L51 import + initGlobalFileDrop
   - L189 后加 inputBar: document.getElementById('input-bar')
   - L2295 后调 initGlobalFileDrop()
3. src/main/resources/web/js/flowAgentPopup.js — L330 后加 v.dom.inputBar = popupOverlay.querySelector('#flow-input-bar')
4. src/main/resources/web/js/bgAgentPopup.js — L153 后加 v.dom.inputBar = popupOverlay.querySelector('#bgagent-input-bar')
5. src/main/resources/web/css/input.css — L51-55 选择器改为 "#input-bar.drag-over, .fa-input-bar.drag-over"

关键实现红线:
- dragleave 监听内禁止读 dataTransfer.types（卡死 bug 根因），无条件递减计数
- dragover 仅 hasFiles() 时 preventDefault（防文本拖拽被拦 + 防页面跳转打开文件）
- drop 路由: bar → Object.values(chatViews).find(v => v.dom.inputBar === bar)，禁用回退到 primary
- view.dom.input?.readOnly 时忽略 drop（禁用态弹窗守卫）
- target 显式传 { attPreviewEl: view.dom.attPreview, attachments: view.pendingAttachments }

验收: 规格 §5.1 (S1-S5 curl/console) + §5.2 (M1-M10 / P1-P5 / R1-R3 用户自验)
回滚: 单 commit git revert
```
