---
name: nebflow-tool-dev
description: Nebflow 工具系统域知识——工具管线（schema→权限→校验→执行→结果格式化）、内置 Scala 工具与外部 ScriptTool 架构、工具权限模型、六条设计原则（最小参数面/可描述错误/结构化结果/向后兼容/schema 即文档/按角色分化先走定义层）、新建与优化工具流程。Use when 设计、实现、优化或调试 Nebflow 工具（内置工具、.nebflow/tools/ 外部工具、工具权限、工具 schema）。
when_to_use: 工具设计实现（Scala 内置或 ScriptTool 外部）、工具权限配置、工具 schema/description 设计、工具执行失败排查；新建工具前先查既有工具能否扩展。
language: zh
status: active
last_verified: 2026-09-03
---

# Tool Dev — Nebflow 工具系统域知识

## 工具架构

### 内置工具（Scala）

位置 `src/main/scala/nebflow/core/tools/`。每个工具实现工具 trait，提供：name（唯一标识）、description（做什么/何时用）、parameters（JSON Schema 输入校验）、execute（实际逻辑）。

关键文件：`ToolDefinitions.scala`（中央注册表）、各工具实现文件（如 MailTool/ReadTool/BashTool）、`ToolLoader.scala`（从 `.nebflow/tools/` 加载外部工具）。

### 外部工具（ScriptTool）

位置 `~/.nebflow/tools/<name>/`：`tool.json`（name/description/parameters schema/command）+ 脚本文件（bash/python 等）。外部工具无需重编译即可扩展工具面。

### 权限模型

agent.json `tools` 字段门控：`"*"` 全量；具名列表只开列出的；部分工具为编排层专属（Pop/AskUserQuestion/Schedule 类）。

## 六条设计原则

1. **最小参数面**：每个参数都是 LLM 的认知负担——只在默认行为错误时加参数。加参前先问「工具能自己推断吗」；可选参数必须有合理默认；两态场景用 boolean 不用 enum。
2. **可描述的错误**：工具失败时错误消息是 LLM 得到的唯一反馈——说出错在哪（不只 "error"）、说期望是什么、能给修法就给、附导致失败的实际值。
3. **结构化结果**：结果机器可解析——分节用清晰分隔、带状态指示（[OK]/[FAIL]）、大输出截断并标注（"... 47 more lines"）、永不返回裸堆栈——提取有意义行。
4. **向后兼容**：新参数默认现行行为；改名参数要迁移路径；删参数先确认无 agent 使用；schema 改动必须过全部既有测试。
5. **Schema 即文档**：JSON Schema 就是文档——每个参数带 description；固定值域用 enum；格式校验用 pattern；required/optional 显式标注。
6. **按角色分化的工具面先走 schema/定义层**（作者令 2026-09-13；本条为新增，非原五条之一）：同一工具要按角色/身份给不同能力或形态时，先问能不能在工具定义/schema 层分化（不同 `inputSchema`/`description`，或不同变体名），不要先把多角色语义并集写进一段 `description` 再靠执行期错误拦。提示词约束与运行期机制检测是兜底层；**授权面（谁能对谁做什么）仍必须由运行期闸 fail-closed 守住**——schema 分化只影响模型看到什么，不参与运行期准入（现状：引擎零 JSON-Schema 校验，面外参数静默丢弃）。树内先例：`node_report` 并集 enum + 角色闸（`NodeReportTool.scala:76-79` 自证 `Tool.inputSchema` 无参拿不到身份）。

## 新建工具流程

1. 识别能力缺口（今天 agent 做不到什么？）
2. 查既有工具能否扩展——优先扩展于新建
3. 设计 schema——参数最少、描述清晰
4. **先写 description**——两句话说不清这个工具，说明设计有问题
5. 实现——处理边界、校验输入、格式化输出
6. 测试——happy path + 错误路径 + 权限边界
7. 更新注册表（ToolDefinitions 或 ScriptTool 配置）

## 优化既有工具

1. 改前通读完整实现
2. 追调用链：schema 校验 → 权限检查 → 执行 → 结果格式化
3. 定位瓶颈：描述不清？缺参数？执行慢？
4. 最小改动解决问题
5. 跑既有测试验向后兼容
6. 行为变了就同步更新 description

## 代码安全

- **命令注入**：外部工具常跑 shell——输入必须清洗
- **路径穿越**：工具参数中的文件路径要校验
- **资源限制**：长任务设 timeout，输出封顶
- **权限提升**：工具以系统权限执行——绝不暴露危险操作

## 输出风格

先设计决策后实现；schema 变更展示 before/after JSON；实现后附测试结果；平实语言。
