# 调研：agent 文件修改的内联 diff 审阅（Cursor / VS Code Copilot / Claude Code）

> 批次：canvas-inline-diff-review（A 段交付）· 日期：2026-11-16
> 动机（作者原话）：「canvas ask修改文件的diff是直接显示在原文档 建议查一下cursor是怎么做的 而不是在会话里问  agent编辑都是直接成功的。它可以每条同意 也可以一起统一」
> 来源：Cursor 官方文档 / VS Code 官方文档与博客 / Claude Code 官方文档（网络查证，非印象）。

## 一、呈现形态

- **Cursor Agent**：编辑**自动写入磁盘**（官方工具描述 "Suggest edits to files and apply them automatically"），改动以绿增红删叠加在原文件位置；审阅集中在 Agents Window 的 Changes/Review 视图，「先应用、后集中审阅」。
  来源：cursor.com/docs/agent/overview.md、/agent/agents-window.md、/agent/review
- **VS Code Copilot（edit mode / inline chat）**：edit mode 在编辑器内就地流式应用，顶部 overlay 提供上下导航与 Keep/Undo；inline chat 以幽灵文本 + Keep/Undo 气泡呈现。
  来源：code.visualstudio.com/docs/copilot/copilot-edits、/copilot/chat/inline-chat
- **Claude Code VS Code 扩展**：Manual 权限模式先弹 side-by-side 批准；批准后仍可在 diff 中逐 change 审阅；CLI 经 ide MCP 在 VS Code 原生 diff viewer 打开。
  来源：code.claude.com/docs/en/vs-code

## 二、接受/拒绝粒度

- **Cursor**：没有 pending 式逐 hunk 按钮（自动应用 + Checkpoint 整体回滚模型）；历史「Partial Accepts」设置在现行文档无细节（未查到确证）。
- **VS Code Copilot**：editor overlay 逐 hunk/逐文件 Keep/Undo + Accept All / Discard All。
- **Claude Code 扩展**：真正 per-change——每处改动下有 Accept/Reject this change（v2.1.275+）；拒绝把该处在 proposed content 中回退；>100 处改动退化为整文件审阅。
- 「Cursor 整文件、Copilot 才 per-hunk」的说法：方向对但表述不准——准确结论是 Cursor 没有 pending per-hunk 审阅；提供逐块按钮的是 VS Code Copilot 与 Claude Code 扩展。

## 三、全量操作与快捷键

- VS Code Copilot：⌘Enter Keep、⌘Backspace Undo/Discard、⌥F5/⇧⌥F5 在改动间导航、Accept All/Discard All、`chat.editing.autoAcceptDelay` 倒计时自动接受。
- Claude Code 扩展：命令面板 Accept/Reject Change at Cursor；权限模式 default/plan/acceptEdits/bypassPermissions。
- Cursor：Cmd+Shift+Backspace 停止 agent；接受 diff 的专用快捷键未查到确证。

## 四、过期/并发（stale）处理

- **Claude Code**：在 diff 视图直接编辑 proposed content 后，扩展告知 Claude「文件与其原始提议不符」由模型重算——把冲突显式回传，不静默覆盖。
- **Cursor / VS Code Copilot**：对「审阅期间文件被外部改动」的专门机制均未查到确证。

## 五、拒绝/回退语义

- Cursor Checkpoints：重大改动前自动快照（本地、与 Git 分离）；restore 只回滚文件不删消息；回滚前二选一（Revert Files / Keep Files）。
- VS Code Copilot：Undo 回退到最近一次 edit 工具调用之前；pending edits 跨窗口重载持久。
- Claude Code：Reject a change = 仅回退该处 proposed content；rewind 三选项（Fork conversation / Rewind code / Fork+Rewind）。

## 六、三家差异对照

| 维度 | Cursor | VS Code Copilot | Claude Code 扩展 |
|---|---|---|---|
| 模型 | 自动应用 + 快照回滚 | 就地流式 + hunk Keep/Undo | Manual 先审后应用 / per-change |
| 粒度 | Checkpoint 整体 / Changes 集中审 | hunk + 文件 + 会话三级 | per-change（>100 退化） |
| 快捷键 | —（未确证） | ⌘Enter / ⌘Backspace / ⌥F5 | at-Cursor 命令 |
| 并发 | 未查到确证 | 未查到确证 | 通知 agent 重算 |
| 回退 | Restore checkpoint | Undo 到上次 edit 前 | Reject 该处 / rewind |

## 七、可借鉴结论（→ 本仓 canvas 实现的取用清单）

1. **双态合一**：编辑直接成功落盘（Cursor 模型，作者原话），审阅是事后动作——不把编辑流程卡在批准上。
2. **内联呈现**：绿增红删直接叠在原文档位置；本仓等价物 = canvas 文件面上的审阅页签渲染「原文档全文 + 就地增删行」（含上下文行，可折叠长未变段）。
3. **三级粒度**：逐块接受/拒绝（Copilot/Claude Code 式）+ 一键全部接受/全部拒绝（作者原话「每条同意/一起统一」）。
4. **快捷键对齐 VS Code 习惯**：⌘/Ctrl+Enter 全部接受、⌘/Ctrl+Backspace 全部拒绝（审阅页签激活时作用域生效）。
5. **接受 = 标记已审（不写盘）**；**拒绝 = 回写回退该块**（Claude Code 语义，其余块继续可审）。
6. **大 diff 降级要显式**：>100 处改动退化文件级（本仓实现按 hunk 无硬截断 + 长上下文折叠，暂不需要该降级，保留为边界）。
7. **回退前显式确认 + 只回文件不动对话**（Cursor Revert 二选一先例）。
8. **偏离显式回传，绝不静默覆盖**（Claude Code 先例）：接受/拒绝动作前后端校验盘上内容仍等于审阅基线current，任何偏差 = 显式冲突提示 + 拒绝执行 + 给重试路径（重试 / 保留盘上现状关闭审阅）。
9. **审阅状态跨重载复现**：askState 回放携带在列审阅（本仓既有 askOpen 回放腿沿用）。

## 八、本仓落地映射（B 段实现要点）

- 链路：`ask_edit/ask_write`（直接落盘 + 记账）→ WS `askEditApplied`（聚合 base/current + hunk 表）→ canvas 审阅页签（原文档内联 diff）→ `askEditAccept/askEditReject`（可带 `hunkId` 逐块；缺省 = 全部）→ `askEditResolved`（携带余下审阅态或 error）→ `refreshFileTabByPath` 重载文件页签。
- 冲突闸：接受/拒绝/逐块拒绝（有写盘动作）一律先验「盘上 == 审阅 current」，不等即 `outcome:error`（stale 文案），前端出冲突条（重试 / 保留现状关闭）；`askEditDismiss` = 显式保留盘上现状并关闭审阅的逃生口。
- 会话问答路径摘除：askDock 的 diff 卡族（DOM/按钮/CSS/INTERNAL_CLASSES）整体退役，问答列只留用户/助手气泡。
