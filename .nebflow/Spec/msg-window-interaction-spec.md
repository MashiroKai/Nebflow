> 归并自：msg-window-interaction-spec-tmp.md（v1.0 初稿，已被 v1.1 实现/v1.2 用户裁定取代；git 历史 ~/.nebflow repo 可溯）

# 消息窗口交互暴露方案（子 agent 对话窗口）

> **状态**: superseded v1.2 · 输入栏方向被 2026-08-22 用户裁定覆盖（管理面板已实施）
> **最后更新**: 2026-08-22
> **所属**: Nebflow 前端

## 版本日志

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-08-19 | 初稿（确认只读现状 + 提出交互暴露方案） |
| v1.1 | 2026-08-19 | **核心交互已由 commit 881634fd 实现**（04:16），本版更新为「已实现 + 缺口清单」审查版 |
| v1.2 | 2026-08-22 | **用户裁定覆盖（2026-08-22 22:20）**：子窗口去输入栏、改管理面板——v1.0/v1.1 的输入栏交互暴露方向作废，§2/§3/§5 输入栏段失效；新增 §9 管理面板设计（commit 244acdb1 落地） |

---

## 1. 结论摘要（TL;DR）

- **背景判断正确**：子 agent 弹窗消息窗口曾完全只读——模板无输入区、`fakeDom` 把交互控件全置 `null`、`initInput` 从未对 popup view 调用。用户看到的子 agent 窗口只能看输出。
- **后端早已具备全部路由能力**：`interrupt`（WebSocketRoutes.scala:897）与 `immediateInput`/`userMessage`（:914/:928）→ `handleUserText`（:3167）都是 sessionId 驱动，通过 `agentRegistry` 直达子 agent actor（Delegate/SubTask/Flow 节点均已注册）。
- **关键进展**：commit `881634fd`（2026-08-19 04:16）已将方案核心实现落地——两个 popup 文件都加入了输入区 DOM、view.dom 指针、`initInput` 绑定、send/stop 显隐切换、null sessionId 灰显。当前 HEAD 即该 commit。
- **遗留缺口**（本文档第 5 节详述）：输入区配套 CSS 类（`fa-input-bar`/`fa-input-wrap`/`fa-input-icon-btn`/`flow-agent-input-area`）与 ID 选择器（`#bgagent-input` 等）无任何样式定义 → textarea/badge 未美化、slash-dropdown 与 queue-bar 在主窗口外的可视行为未定义。这是验收前必须补齐或确认的关键点。

---

## 2. 现状分析（已实现核心，仍有缺口）

### 2.1 子 agent 窗口如何打开（入口全确认）

| 入口 | 调用链 | 弹窗类型 |
|---|---|---|
| 头部 Sub-agents 下拉（`#bgagent-dropdown`） | `main.js:1330-1356` → `openBgAgentPopup` → `bgAgentPopup.js:82` | delegate/subtask agent |
| 头部 Sub-agents 下拉（dag-*/bare sid） | `main.js:1350` → `openFlowStepPopup` → `flowAgentPopup.js:252` | flow agent / team agent |
| Flows / Teams 面板 agent pill | `flowTeams.js:207` | flow agent |
| DAG 画布节点 | `flowDag.js:253,394` | flow agent |

实现后：所有入口的弹窗都已带输入区。

### 2.2 已实现与未实现对照（commit 881634fd 后）

**已实现（两个 popup 文件）**：

| 能力 | 实现位置（bgAgentPopup / flowAgentPopup） |
|---|---|
| 输入框 DOM + view.dom.input 绑定 | 模板 `#bgagent-input` / `#flow-input`；`bgAgentPopup.js:153` / flow 对应 :330 |
| 发送按钮 + stop 显隐 | `#bgagent-send-btn`/`#bgagent-stop-btn`，`syncInputButtons`（bg `:394-399` / flow `:647-652`） |
| 附件按钮 + 预览区 | `#bgagent-attach-btn`/`#bgagent-attachment-preview` |
| slash 下拉挂载点 | `#bgagent-slash-dropdown class="slash-dropdown"` |
| 队列栏挂载点 | `#bgagent-queue-bar`，`renderQueueBar` 经 `findViewBySessionId` 命中 |
| initInput 绑定（幂等 `_inputBound` guard） | `bg:177-183` / `flow:354-360`，仅 sessionId 非空时 |
| nodeSessionId 为空灰显 | `bg:167-173` / `flow:344-350`（readonly + disabled） |
| lucide 图标渲染 | `createIconsIn(输入区)` 两处 |
| voice 兜底（dummy 按钮，v1 不支持语音） | `bg:161-164` / `flow:338-341` |

**未实现/缺口（本方案剩余工作）：**

| # | 缺口 | 说明 | 位置 |
|---|---|---|---|
| G1 | **输入区无任何 CSS 样式** | `.fa-input-bar`/`.fa-input-wrap`/`.fa-icon-btn`/`.flow-agent-input-area` 均无定义。textarea 继承浏览器默认样式（白底方框）而不是主窗口玻璃输入条——即使功能可用，视觉上会非常突兀甚至溢出/走位 | 全局 CSS |
| G2 | **popup 控件不匹配主窗口 ID 选择器** | 主窗口样式全部用 ID（`#input`/`#send-btn`/`#stop-btn`/`#attach-btn`/`#slash-dropdown`/`#queue-bar`/`#attachment-preview`），popup 使用 `#bgagent-*`/`#flow-*`，无法继承。`textarea` 无边框底色透明——纯默认文本控件样式 | 同上 G1 |
| G3 | **slash-dropdown / queue-bar 类样式缺失** | 无 `.slash-dropdown` 类，slash 下拉在 popup 内可能完全不可见/错位；queue-bar 的容器定位依赖 `#queue-bar` ID 选择器 | input.css |
| G4 | **stop 按钮语义** | `syncInputButtons` 依赖 `entry.meta.status`；当 popup 直接打开（尚未收到 agentStart）时 status 为空，按钮按「空闲」显示 send——若 agent 实际忙但事件未达，会短暂显示 send 而非 stop。实际由 intercept 事件驱动 | 两个 popup |
| G5 | `_inputBound` 状态在 view 复用后保留 | LRU 复用同一 ChatView 时，二次 open 可能跳过 initInput(但 DOM 已被替换为 popup 内新元素) → 事件绑定丢失。需要 `_inputBound = false` 配合每次 open 重新拼接 DOM | 两个 popup openStepPopup |

**G5 修复方案**（close 时重置 flag，open 重新绑定）：
```js
// closeStepPopup 内（bg + flow 各一处）
if (entry.view._inputBound) entry.view._inputBound = false;
```
效果：每次 open 都重新绑定到新生成的输入区 DOM；同一 open 流程内不重复绑定。改动最小（每文件 1 行）。
### 2.3 后端支持（已确认无需改动）

- `interrupt`（WebSocketRoutes.scala:897-899）→ `ensureAgent(intSessionId)` → `AgentCommand.Interrupt()`；子 agent 已注册，直接命中。
- `immediateInput`（:914-918）→ `handleUserText`（:3167-3175）→ `ensureAgent` → `AgentCommand.ImmediateInput`；AgentActor idle 态当作 UserInput（AgentActor.scala:721-723）。子 agent 已注册，直接命中。
- `askUserAnswer`/`permissionAnswer` 等交互按 sessionId 路由，子 agent 已注册，无需改动。

---

## 3. 目标行为（改后 vs 现状）

| 场景 | commit 881634fd 之前 | 现在（commit 881634fd 之后语义层面） | 期望的最终形态 |
|---|---|---|---|
| 打开子 agent 窗口 | 只读，无输入条 | 有输入区 DOM（textarea+按钮+预览+slash/queue 挂载点），但**无配套样式** | 玻璃输入条 + 可交互 |
| 子 agent 正在运行 | 无法打断 | stop 按钮可点 | 正常 |
| 子 agent 空闲 | 无输入能力 | 输入框可输入、Enter 发送 | 正常 |
| 子 agent 忙碌 | 无输入 | 队列栏自动出现 | **队列栏依赖 view.dom.queueBar 样式** |

**新增控件清单**（均已存在于 DOM）：
1. 输入框 textarea（Enter 发送 / Shift+Enter 换行 / IME / 粘贴图片 / 自动增高）→ `initInput` 已绑定
2. 发送按钮（空闲显示）→ 已绑定
3. 打断按钮（忙时显示）→ 点击即 `sendWs({type:'interrupt', sessionId})`，input.js stopBtn.onclick
4. 附件按钮（隐藏 file input → `addFileAttachment`）→ 已绑定
5. 附件预览区 → 已绑定 dom.attPreview
6. slash 下拉 → 已挂载点，但**样式缺失**
7. 队列栏 → 已挂载点，但**样式缺失**

**明确不做（v1）**：语音输入（dummy 按钮隐藏）、plan/compact/ask 独立模式标签、对主窗口任何改动。

---

## 4. 精确改动点（commit 881634fd 已落地部分）

> 记录实现位置，供验收/回归参考。

### 4.1 `bgAgentPopup.js` —— delegate/subtask 弹窗（已实现）

- **模板**（`openStepPopup` :106-135）：`.flow-agent-input-area` 含 slash-dropdown / queue-bar / input-bar / attach-btn / send-btn / stop-btn，插在 chat 与 footer 之间。
- **DOM 序**（:147-149）：chat 容器插到 input-area 之前 → header / chat / input-area / footer。
- **view.dom 绑定**（:153-164）：input/sendBtn/stopBtn/attachBtn/attPreview/slashDropdown/queueBar 指向真实元素；voice 三件套 dummy 隐藏。
- **initInput 幂等**（:174-184）：仅 `nodeSessionId` 非空且未绑定过时 `import initInput` + `refreshSendButtonState`。
- **null 灰显**（:167-173）：sessionId 为空 → readonly + disabled + opacity 0.4。
- **syncInputButtons**（:394-399）：running → stop 显示 / 空闲 → send 显示，由 `updateFooterStatus` 与 `interceptBgAgentStep` 驱动调用。

### 4.2 `flowAgentPopup.js` —— flow/team/dag 弹窗（已实现）

与 4.1 同构，id 前缀 `flow-`。额外处理 `nodeSessionId 为 null`（flowTeams.js:207 传 `sid || null`、flowDag.js:253 传 null）灰显不动。

### 4.3 后端 —— 无需改动

已确认。`interrupt`/`immediateInput` 均 sessionId 驱动，子 agent 已注册 agentRegistry。

---

## 5. 遗留缺口清单（本方案剩余工作量）

### G1+G2（样式缺失，关键）——popup 输入区无任何样式定义

现状：`.fa-input-bar`、`.fa-input-wrap`、`.fa-icon-btn`、`.flow-agent-input-area` 均无 CSS 定义；`#bgagent-input`/`#flow-input` 是 textarea，无样式 → **弹窗打开后输入区是浏览器默认控件样式，与整体玻璃 UI 割裂**；`#bgagent-attachment-preview` 无样式、`#bgagent-slash-dropdown` 无 `.slash-dropdown` 类样式 → slash 提示框不可见/错位、queue-bar 不可见。

改动位置：`flowAgentPopup.js` 的 `POPUP_CSS` 块（:26-188）新增输入区样式（只此一处，bg/flow 共用类）。

拟定样式（对齐主窗口 `#input-bar` 玻璃风格）：
```css
/* popup input area */
.flow-agent-input-area { flex-shrink: 0; padding: 0 16px 6px; display: flex;
  flex-direction: column; gap: 4px; }
.flow-agent-input-area #bgagent-slash-dropdown, /* 两种 id */
.flow-agent-input-area #flow-slash-dropdown { position: static; }
.fa-input-bar {
  display: flex; align-items: flex-end; gap: 8px;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: 16px; padding: 8px 10px;
  box-shadow: inset 0 1px 0 rgba(255,255,255,0.15),
              0 2px 8px rgba(0,0,0,0.04);
}
.fa-input-bar::before { /* sapphire refraction */
  content: ''; position: absolute; top: 0; left: 10%; right: 10%;
  height: 1px; background: linear-gradient(90deg, transparent 10%,
    var(--sapphire-refraction, rgba(99,179,237,0.25)) 50%, transparent 90%);
  pointer-events: none; z-index: 1;
}
.fa-input-wrap { flex: 1; display: flex; flex-direction: column; }
.fa-input-wrap textarea {
  border: none; background: transparent; border-radius: 12px;
  padding: 8px 10px; font-size: 14px; outline: none; width: 100%;
  line-height: 1.4; resize: none; max-height: 200px; font-family: inherit;
  box-sizing: border-box; color: var(--color-text);
}
.fa-input-wrap textarea::placeholder { color: var(--color-text-muted); }
.fa-icon-btn { /* 附件按钮 */ }
.fa-input-bar #bgagent-send-btn, .fa-input-bar #flow-send-btn, ... (玻璃按钮尺寸同主窗口)
```

同时把 `.fa-input-wrap textarea` 的选择器做成弹窗通用（`.flow-agent-input-area textarea` 更好，避免影响主窗口 #input 的样式）。

### G3（slash-dropdown / queue-bar 类样式缺失）

主窗口 `#slash-dropdown` 是 ID 选择器（input.css:296）+ `#slash-dropdown.on`。popup 用 class `.slash-dropdown` → 无样式。queue-bar 同理 `#queue-bar` ID 选择器 → popup 内无样式。

G3 并入 G1 一起修复：在 POPUP_CSS 里写好 popup 版 slash-dropdown / queue-bar 的定位与显隐规则。

### G4（stop 按钮状态初值）

当 popup 打开但尚未收到 `agentStart` 时，`entry.meta.status` 为空串 → `syncInputButtons` 视为空闲 → 显示 send。若该 agent 实际正在运行（只是事件尚未到或已被路由），用户会短暂看到一个可点的 send 而看不到 stop。影响轻微，由事件流自动纠正。可加：open 时若无 status 且 session 忙，显示 stop。

### G5（view 复用后丢失事件绑定）

`ensureStepView` 对已有 nodeSessionId 返回缓存 entry——但 `openStepPopup` 每次打开都会重建 DOM 模板（新的 input 元素），且 `_inputBound` 为 true 时跳过 `initInput` → **同一个 agent 第二次打开弹窗时输入框无事件响应**（无法发送/打断）。明确 bug，需修复。

修复方案：`closeStepPopup` 中把 `view._inputBound = false`（bg+flow 各一行）；或 open 时检测 DOM 已重建。推荐 close 重置。

---

## 6. 可复用能力（已具备、无需新建）

| 模块 | 函数 | 用途 | 现状 |
|---|---|---|---|
| `input.js` | `send()`（:480） | 发送；已按 `v.sessionId` 路由 | 已接入 popup |
| `input.js` | `initInput(view)`（:1030） | 绑定输入/发送/打断/附件/队列事件 | 已对 popup 调用（commit 后） |
| `input.js` | `addFileAttachment`（:400） | 附件（图片压缩/文件 hash） | 已接入 |
| `chat.js` | `renderAttachmentPreview` | 附件预览渲染 | 已接入 |
| `chatQueue.js` | `renderQueueBar` | 队列栏 | 已接入（findViewBySessionId 命中 popup view） |
| `chatView.js` | `ChatView` | 消息流渲染类 | popup 一直在用，核心基础 ✅ |
| `chat.js` | `setBusy/clearBusy` | 驱动 send/stop 显隐（经 activeView.dom） | 已接入（activeView=popup view 时） |

**仍未解决的根本缺口**：DOM 有了、事件绑定了，但**视觉层没有任何 CSS**。方案仍需补充样式改动点。

---

## 7. 验收条件

> 纯前端改动：G1-G3 样式、G4/G5 小修复。前端 + 交互 → 冒烟测试（真实启动）列为第一项硬性。

### 7.1 冒烟测试（硬性条件，第一项）

```bash
# 真实启动（端口自选）
cd "/Users/dev/Claude code/Nebflow"
nohup ./scripts/dev.sh --port 8099 --no-browser > /tmp/nf-msg-smoke.log 2>&1 &
# sbt run --port 8099 --no-browser 亦可；等待 listening 出现（grep 日志）

curl -sf -o /dev/null -w "root: %{http_code} %{content_type}\n" http://localhost:8099/
#  期望 root: 200 text/html
curl -s http://localhost:8099/ | grep -q 'id="chat"' && echo MOUNT_OK || echo MOUNT_FAIL
#  弹窗相关静态资源 200 且非空
for f in js/bgAgentPopup.js js/flowAgentPopup.js js/input.js js/chatQueue.js css/input.css css/chat.css; do
  code=$(curl -sf -o /tmp/r -w "%{http_code}" "http://localhost:8099/$f") && sz=$(wc -c < /tmp/r)
  echo "$code $sz $f"
done
# 期望: 全部 200 且 size>0
```

### 7.2 缺口修复断言（grep 可脚本化、二值）

```bash
JS=src/main/resources/web/js
# G1/G2/G3 样式:
grep -q '\.fa-input-bar'              $JS/flowAgentPopup.js && echo CSS1_PASS || echo CSS1_FAIL
grep -q '\.fa-input-wrap'             $JS/flowAgentPopup.js && echo CSS2_PASS || echo CSS2_FAIL
grep -q '\.fa-icon-btn'               $JS/flowAgentPopup.js && echo CSS3_PASS || echo CSS3_FAIL
grep -q 'flow-agent-input-area'       $JS/flowAgentPopup.js && echo CSS4_PASS || echo CSS4_FAIL
grep -q 'flow-agent-input-area textarea' $JS/flowAgentPopup.js && echo CSS5_PASS || echo CSS5_FAIL
# G3 slash + queue 样式在 popup 内定义
grep -q 'bgagent-slash-dropdown'      $JS/flowAgentPopup.js && echo CSS6_PASS || echo CSS6_FAIL
# G4: open 时同步按钮状态
grep -q 'syncInputButtons(entry)'     $JS/bgAgentPopup.js && echo G4_PASS || echo G4_FAIL
# G5: close 重置 _inputBound —— 防二次打开丢事件
grep -q '_inputBound = false'         $JS/bgAgentPopup.js && echo G5_PASS || echo G5_FAIL
grep -q '_inputBound = false'         $JS/flowAgentPopup.js && echo G6_PASS || echo G6_FAIL
```

> `flowAgentPopup.css` 是唯一注入点（POPUP_CSS style id="flow-agent-popup-css"），bg/flow 共用类——断言从该方法保持两位（同 id 元素）前缀，使用 class 选择器，避免 ID 纠缠。

### 7.3 端到端（Playwright，真实组件）

前置：真实服务已启动（7.1）；触发一个 delegate 子 agent 使其运行。

```js
// msg-window-e2e.spec.js  (Playwright)
test('popup input/interact', async ({ page }) => {
  await page.goto('http://localhost:8099');
  await page.waitForLoadState('networkidle');
  // 触发 delegate 子 agent（主输入框发送任务）
  await page.fill('#input', '用 Delegate 调研并总结两个知识点');
  await page.click('#send-btn');
  // 等待子 agent 指示出现
  await page.waitForSelector('#bgagent-indicator', { timeout: 15000 });
  await page.click('#bgagent-indicator');
  const first = page.locator('#bgagent-dropdown .bg-task-row, #bgagent-dropdown [data-node-session-id]').first();
  await first.click();
  // popup 内输入框可见 → 可输入
  const inputArea = page.locator('#bgagent-input');
  await expect(inputArea).toBeVisible();
  await inputArea.fill('补充要求');
  await inputArea.press('Enter');
  // 断言 popup 内出现新的 user 气泡
  await expect(page.locator('#bgagent-chat .row.user').last()).toContainText('补充要求');
  await page.screenshot({ path: '/tmp/window-msg-input.png' });
  // G5: 关闭再打开 → 仍可输入
  await page.click('#bgagent-close');
  await page.waitForTimeout(300);
  await page.click('#bgagent-indicator');
  await page.click(first);
  await page.fill('#bgagent-input', '第二轮消息');
  await page.press('.flow-agent-input-area textarea', 'Enter');
  await expect(page.locator('#bgagent-chat .row.user').last()).toContainText('第二轮消息');
});
```

注：无 Playwright 环境时按规范回退——curl 全量静态资源 + grep 断言 `#bgagent-input` 等存在，渲染正确性标注人工确认。

### 7.4 回滚 / 一致性

- 纯前端改动，revert 两个 popup 文件即可；无迁移、无数据风险。
- 不改主窗口输入，主会话不受影响；后端无协议变更。

### 7.5 自查清单

- [x] 冒烟是否真实启动？→ 7.1 使用真实启动命令 + curl
- [x] 端到端从用户操作开始？→ 7.3 从 `fill #input` 开始
- [x] 每条二值可自动验证？→ grep/curl/Playwright 均可
- [x] 覆盖启动流程？→ 7.1
- [x] 前端样式是否先展示？→ 第 4.1 节样式草案

---

## 8. 资料 / 代码索引

- popup 实现在 `src/main/resources/web/js/bgAgentPopup.js` 与 `flowAgentPopup.js`（模板 :106 / 283、DOM wire :153 / 330、initInput guard :174 / 351、syncInputButtons :394 / 647）
- 主窗口 DOM `main.js:183-208`；initInput 唯一调用 primary `main.js:2295`
- 主窗口样式 `input.css`（`:2` input-area、`:12` input-bar、`:46` input-wrap、`:99` #input、`:153` #send-btn、`:207` #stop-btn、`:296` #slash-dropdown、`:392` #queue-bar）
- `ChatView` 类 `chatView.js:29-235`；`input.js:480(send)/:1030(initInput)`
- 后端路由 `WebSocketRoutes.scala:897(interrupt)/:914(immediateInput)/:3167(handleUserText)/:299(ensureAgent)`
- 子 agent 注册 `DelegateTool.scala:417-427`、`SubTaskTool.scala:290-300`、`FlowDagExecutor.scala:742`、`FlowTreeActor.scala:71`
- 旧版方案（draft v1.0 视角）见 tmp 文件：`msg-window-interaction-spec-tmp.md`（本文件写到一半的旧版）



---

## 9. 管理面板设计（2026-08-22 用户裁定，commit 244acdb1 落地）

> **用户原话（2026-08-22 22:20）**：「我们现在的子agent的消息窗口，设计是跟主agent一样的消息输入栏。但是就是其实子agent需要不同的管理方式。1.去掉消息输入栏，只是让用户有可以控制agent 停止 重试 等状态显示和管理的按钮就行。」
> 核心思想：**主窗口=对话（输入栏），子窗口=管理（无输入栏）**。交互模型分离。本段取代 v1.0/v1.1 全部输入栏段（§2 已实现输入区、§3 交互方案、§5 缺口 G1-G5 均失效）。

### 9.1 移除范围

子窗口（Delegate/SubTask/Team/Flow/Ephemeral 会话弹窗）删除全部输入形态：textarea、attach、voice、slash-dropdown、queue-bar、send/stop 输入按钮；POPUP_CSS 254 行输入区样式同步删除。主窗口输入栏不受影响。

### 9.2 管理按钮组（映射后端既有 AgentControl，前端零 Scala 改动）

| 按钮 | WS 命令 | 后端 handler |
|---|---|---|
| 停止 | `{type:'interrupt', sessionId}` | WebSocketRoutes `case "interrupt"` → AgentCommand.Interrupt() |
| 重试 | `{type:'restartAgent', sessionId, level:'soft'}` | `case "restartAgent"` → AgentCommand.RestartAgent |

权限矩阵（AgentControl spec §4）：Delegate/SubTask/Ephemeral 可操作；Team/Flow/Root 只读置灰 + tooltip 说明原因（「团队 agent 只读，不可操作」/「Flow 节点 agent 只读」）。

### 9.3 状态可视化与按钮联动

- 数据源：agentStart/agentThinking/agentToolStart/agentToolEnd/agentTextDelta/agentDone（既有事件流）+ `taskStuck` 广播（2026-08-22 加入 ws.js TERMINAL 白名单；{sessionId, kind, idleSecs, action=attention|restart}）
- stuck 标红克制：footer `.stuck` = 红点脉动 + 红任务文案（既有 --color-error token，不引新色）；action=restart 计 retries（×N chip，amber）
- 联动：Processing 相位才显停止；failed/stuck 才显重试；**不常驻死按钮**
- uptime：agentStart 到达时刻为锚（前端记）；刷新态降级「-」，待 Backend 在 activeAgents 快照补 startedAt
- kind 来源：openStepPopup kindHint（team tile=Team / DAG node=Flow），bg 弹窗默认 Delegate，activeAgents 快照 kind 精炼（main.js 已存 kind/startedAt 到 sessionBgAgents）

### 9.4 验收断言（harness /tmp/ttl-verify/verify-manage.mjs 23/23 PASS）

- S1 Delegate：无输入栏残留（textarea/.fa-input-bar/voice 全不存在）；idle 无死按钮；running 显停止；stop 发 interrupt；stuck 显重试+红标签；retry 发 restartAgent soft；活动清除 stuck；done 全隐
- S2 Team：置灰 disabled + tooltip；点击零帧；team 分支 meta 更新（running/stuck 可见但仍只读）
- S3 Flow：只读 + tooltip
- S4 retries chip ×2（两次 taskStuck action=restart）

### 9.5 后端缺口（已报 Manager 转 Backend，前端优雅降级）

1. WS 无 `cancelAgent` handler（cancel 链仅 tool 层）——停止暂用 interrupt，补上后可升级 cancel 语义
2. activeAgents 快照缺 status/startedAt/retryCount（activeAgentEntryJson 仅 6 字段）
3. 子 agent failed 无 WS 事件（BackoffSupervisor failed 仅父 agent 内部通知）——failed 态暂不可达，retry 按钮经 stuck 路径触发
