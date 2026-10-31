---
type: design
status: implemented
---

# ask mode 工具面换装批：只读五件 + 思考低档（2026-10-31 12:25 作者裁定）

> 裁定原文：「askmode窗口 工具就使用 glob grep read websearch webfetch 这几个工具就行
> 然后思考强度低 让它不仅可以回答这个文件的内容 这个文件相关的文件 它都能回答
> 工具工程不可见 仅气泡消息对话」

关联：[[20261028_canvas-ask-mode-design.zh-CN]] §5/§6 ·
[[20261031_113700_ask-readonly-tool-design__delegate-nebflow-09ef7b2e.zh-CN]]（v1 路线，
superseded 留档）

## 背景与裁定

10-31 上午批给 ask 会话补了专用只读工具 `read_file_content`（路径锁绑定表、2MB
文件顶、64KB 输出顶）——解决「agent 报无法读取文件」。同日中午作者改裁：**不要
专用单件，直接给通用只读五件**（glob / grep / read / websearch / webfetch），让
ask 窗口「不仅可以回答这个文件的内容，这个文件相关的文件都能回答」；**思考强度
调低档位**；**工具过程不可见**（只出最终气泡回复，与主会话「工具过程折叠」同
口径）。安全边界不变：只读五件零写操作。

## 工具面 diff（实施前后）

| 项 | 前（上午批 v1） | 后（本批） |
|---|---|---|
| ask 白名单 AllowedTools | message_record_search / send_message_to_main_chat / **read_file_content** | 上述两件（不变）+ **Read / Glob / Grep / WebSearch / WebFetch** |
| 只读五件来源 | — | `AgentCore.SubagentFixedTools` **同源引用**（Subagent 只读侦察面单点，改面两侧同步） |
| read_file_content | 新增（当天） | **退役**：工具对象 + registry 注册 + spec 一并删除（能删则删；Read 的 512KB/2000 行自带顶 + ToolResultGuard 覆盖面是其超集） |
| read 范围 | 仅绑定文件（路径锁，无 path 参数） | 绑定文件 + **相关文件**（同目录 / glob / grep 可达，靠工具自然探，不做硬白名单） |
| 思考强度 | 继承全局 ThinkingConfig | ask 会话逐请求 **effort 强制 low** |
| 反剥集 | AllowedTools 整体 | 拆出 `AskExclusiveTools`（只两件）——只读五件是全员注册工具，进反剥集会连主会话读面一起剥掉 |
| 工具过程 UI | askDock 默认分支静默消费 tool*/thinking 帧（§6 透明纪律，上午批前已在场） | **不变**（本批核实为既有事实，无代码改动） |

## 实施点

1. **白名单**（`AskSessions`）：`AllowedTools = AskExclusiveTools ++ AgentCore.SubagentFixedTools`；
   新增 `AskExclusiveTools`（message_record_search / send_message_to_main_chat）作为
   buildAllowedToolSet 尾段 `askStripped` 的反剥集——原实现 `withDelegateMail --
   AllowedTools` 在 AllowedTools 扩容后会连主会话的 Read/Glob/Grep/WebSearch/WebFetch
   一并剥掉，是本批最大陷阱，拆分后消除。
2. **registry**：删 `read_file_content` 注册；ask 注释块更新。
3. **思考低档**（`AgentSessionExecution` 请求构造点）：ask 会话（`AskSessions
   .isAskSession`，ask- 前缀单点）的 `req.thinking` 语义载体 deepMerge
   `{"effort":"low"}`——`toLlmJson` 主面不变，**非 ask 会话该键恒缺席、行为逐字节
   不变**；`interface.resolveThinkingFor` 单点读回该键进全局基座，再走
   `ThinkingEffortGate` 族 gate 出口（GLM Toggle 族 / Kimi K3 顶层族已点亮；
   Budget 族无 effort wire，原预算语义不受影响）。
4. **提示词**（`AskContextAssembler.promptFor`）：签名增 `absPath`（绑定文件绝对
   路径进【上下文】段——通用 Read 需要路径锚，v1 专用工具路径锁不需要）；工具面
   描述改为只读五件 + 检索一件 + 转交一条，明示「范围不止当前文件，同目录/同仓库
   相关文件都可读可搜，不自我设限」。
5. **UI**：零改动——askDock.js 对 tool*/thinking/usageUpdate 等过程帧走 default
   分支静默消费（文件内注释「透明纪律（§6）」），工具过程本来就不进气泡列。
6. **安全边界**：只读五件零写操作（Read/Glob/Grep 读盘与进程检索、WebSearch/
   WebFetch 联网只读）；ask 会话依旧没有 bash/edit/write/编排任何面（白名单封闭
   枚举早退不变）；read 无 2MB 专用限制则沿用工具自身默认（Read 自带 512KB/2000
   行顶）。

## 明确不做

- 不动 `message_record_search` / `send_message_to_main_chat` 任何行为（ask 原生
  两件，机制稿 §5/§7 契约不变）；
- 不给 ask 会话任何写/执行/编排面；
- 不改主会话及其他身份的工具面（非 ask 会话零变化）；
- 不恢复 read_file_content（同日新增同日退役，无存量会话依赖——ask 会话每轮
  重新按白名单下发工具 schema）。

## 回归口径（ask 相关套件）

`AskToolFaceSpec`（新，F1-F5：白名单逐字钉死 / read_file_content 双缺席 /
反剥集零连坐 / 五件在册 / 提示词锚）+ `AskGateSpec`（类型闸与深防，换装批未触及）
+ 五件工具各自 spec（GlobToolSpec / GrepToolRankedSpec / ReadTool 系 /
WebSearch/WebFetch 系）+ `ThinkingBudgetCapSpec`（toLlmJson 形态不变）+ 全量 compile。
