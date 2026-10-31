---
type: design
status: implemented
---

# ask mode 只读工具设计清单（read_file_content）

> ask 模式修复批 2026-10-31（作者令 10-31 10:43，证据截屏 3 张）第 ③ 件：
> agent 报「无法读取文件」——ask 会话白名单缺只读文件工具。本清单 = 作者过目面
> （它能用什么只读工具、各能读什么格式、体积上限），实施逐字按本清单落地。

关联：[[20261028_canvas-ask-mode-design.zh-CN]] §5/§6 ·
[[20261029_171456_term-ask-mdhtml-gate-batch3__delegate-nebflow-8312db45.zh-CN]]

## 背景与裁定

ask 会话工具面原为封闭枚举两件（`message_record_search` 只读检索 +
`send_message_to_main_chat` 单向转交，见 [[20261028_canvas-ask-mode-design.zh-CN]] §5）。
组装腿上下文（文件全文 16k 字预算截断 + 图谱邻接 + 记忆命中）是**快照**——文件
稍长/问题稍细即超出快照，agent 无第二读取通道，只能答「读不到原文」。
作者令补一件只读工具，**不扩写面、不放宽白名单外任何东西**。

## 工具清单（v1 只此一件）

| 项 | 设计 |
|---|---|
| 工具名 | `read_file_content`（snake_case，机制稿 §5 契约名同式） |
| 形态 | 引擎内置工具（AskTools.scala 新对象），只读 |
| 读什么 | **仅本会话绑定文件**——路径不开放参数，工具内侧经绑定表回查（AskSessionRegistry.byId），等于「当前这份文件的原文」，与 ask 使命（只服务这份文件）同边界 |
| 可读格式 | UTF-8 文本；扩展名双保险复问 `isAskable`（md/markdown/html/htm 四件——绑定时已过闸，此处防直调绕过） |
| 路径参数 | **无**（刻意最小参数面） |
| 行窗口参数 | `start_line`（int，可选，1 起，缺省 1）；`max_lines`（int，可选，缺省 200，硬顶 500） |
| 文件体积上限 | 单文件 ≤ **2MB**，超出拒绝（显式错误 `ASK_READ_TOO_LARGE`，不静默截断文件级语义） |
| 单次输出上限 | ≤ **64KB**（行窗口内再按字符截断，`truncated:true` + 截断标记——窗口语义不被单行巨型文件撑爆上下文） |
| 返回 | JSON：`{file, totalLines, startLine, returnedLines, truncated, content}`（结构化结果，可描述错误） |
| 授权面 | 三处同源：`AskSessions.AllowedTools` 封闭枚举 +1 → `buildAllowedToolSet` ask 早退集自动生效；registry 注册；工具内侧 `isAskSession(ctx.sessionId)` fail-closed 拒绝（DelegateReportTool 双保险同款） |
| 频控 | 无需（只读、本盘、行窗口自带预算）；2MB/64KB 双上限即资源闸 |

## 明确不做（防扩面）

- 不给任意路径读取（无 `path` 参数）——读非绑定文件 = 越出 ask 使命边界；
- 不给目录列举 / grep / glob——文件内查找需求先用行窗口定位，真不够再按本清单
  流程追加（独立评审）；
- 不给写/执行/编排任何新面——白名单封闭枚举纪律不变（机制稿 §5）；
- 不动 `message_record_search` 与 `send_message_to_main_chat` 任何行为。

## 提示词同步

AskContextAssembler.promptFor【边界】①由「没有任何工具」更正为「两件只读 +
一条转交通道」，并告知 read_file_content 的存在（agent 此前不知道自己能读，
报「无法读取文件」的直接原因）。
