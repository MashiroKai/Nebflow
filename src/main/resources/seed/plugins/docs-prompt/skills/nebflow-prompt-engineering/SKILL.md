---
name: nebflow-prompt-engineering
description: 提示词工程方法论——六核心原则（唯一解析/渐进披露/token 效率/结构化/行为性/示例优先）、系统提示词解剖结构、编辑既有提示词的纪律与反模式清单、工具描述写法、agent 定义周期体检清单。Use when 设计或优化 system prompt、工具 description、agent 定义（system.md/agent.json）等指示 LLM 行为的文本。
when_to_use: 设计/优化系统提示词、工具描述、agent system.md；改善提示词清晰度、消除歧义、重构提示词章节、降低 token 消耗；agent 定义周期体检（长度分布/路由偏离/孤儿 skill 排查）。
language: zh
status: active
last_verified: 2026-09-03
---

# Prompt Engineering — 提示词设计方法论

## 六核心原则

1. **精确优先于简短**：每句话必须只有一种解析。读者能读出两种意思就要重写。反例「Handle the task appropriately」→ 正例「Read the file, identify the bug, fix it, then run tests」。
2. **渐进披露**：分层结构——目录短驻上下文，细节文件按需加载。系统提示词短到塞得进上下文预算，长到无歧义。
3. **Token 效率**：系统提示词每个 token 在每次 LLM 调用都要付费。砍填充词（please / you should / it is important to）；用祈使句（「Track progress」而非「You should track progress」）；合并冗余规则；一行一条规则。
4. **为解析而结构化**：LLM 解析结构——顺序用编号列表、关键术语加粗、示例用代码块、无关章节间用分隔线、全部提示词格式一致。
5. **行为性而非描述性**：写 agent 该做什么，不写它该是什么。反例「You are a careful agent」→ 正例「Read the file before editing. Verify the edit applied correctly.」
6. **示例胜过解释**：一个具体例子教会的东西多于三句解释。展示输入→输出模式、错误写法与正确写法对照、用 agent 真实会遇到的格式。

## 提示词解剖

```
1. Identity（1-2 行）     — 你是谁、做什么
2. Core Behavior（3-5 条）— 最重要的行为约束
3. Workflows（分步）      — 常见任务类型的处理流程
4. Edge Cases             — 出错时怎么办
5. Output Style           — 格式/语气/长度预期
```

章节尺寸：Identity ≤2 行；核心规则 5-7 条（LLM 超过 7±2 条失焦）；每工作流 3-7 步；单节超 10 条就拆子节。

## 编辑既有提示词

**动手前**：通读全文 → 识别今天产出的行为 → 识别期望行为 → 找桥接两者的最小改动。

**编辑纪律**：改动词数最少化；除非任务就是重构，否则保留既有结构；编辑时自问「如果我是读这段的 LLM，我会怎么做」；改完重读全节查连贯性。

**常见反模式清单（要修的）**：
- 模糊限定词：「appropriate / relevant / as needed」→ 换成具体判据
- 矛盾规则：两条冲突 → 解决冲突或加优先级
- 死指令：描述已不存在功能的规则 → 删除
- 冗余：同一规则两处出现 → 合并
- 缺省值缺失：「decide what to do」→ 给默认行为

## 工具描述

```
做什么 — 一句话。
何时用 — 一句话或要点列表。
关键参数 — 只写不显然的，带示例。
```

100 词以内；更多细节交给工具错误消息与参数 description 承载。
### 工具面按角色分化：先定义层，再提示词

同一工具要按角色/身份给不同能力或形态时，**先问能不能在 schema/定义层分化**（不同 `inputSchema`/`description`，或不同变体名）——不要先把多角色语义并集写进一段 description，再靠提示词纪律与执行期错误兜。提示词是本通则的兜底层（作者令 2026-09-13）。
反例（现状）：`MailTool` 把 Nebula/dispatcher/team 三个角色的地址面写在同一段 description（`MailTool.scala:49-60`），`address` 仍是自由字符串、schema 零分化；`node_report` 用并集 enum + 角色说明文本（`NodeReportTool.scala:113-138`）。
纪律：授权面（谁能对谁做什么）不因 schema 分化而豁免——运行期闸仍须 fail-closed。

## 周期体检（agent 定义审计）

事件驱动触发（并入 consolidation 例行），产出**报告**：无硬阈值、不强制、不自动退役；裁决归 Owner；新建冷启动期豁免。检查项：

- **system.md 长度分布**：超 150 行软预算者给拆层建议（收益与代价并列），拆不拆由 Owner 裁量——连贯性优先于长度指标。
- **useWhen 与实际路由偏离**：catalog/useWhen 声明 vs 实际派发记录对照。
- **skills 空置率**：agent.json skills 全空的成员，列出即止。
- **跨团队复制残留（换域校验）**：一个 agent 查出漂移时，**同批来源的其余 agent 整批一起查**（同批漂移漏网教训）。
- **孤儿 skill**：零订阅 skill 列出 + 保留/修订/退役建议，退役决定归 Owner。
- **memory 行数**：仅参考信号（明显膨胀再提示），不触发动作。

## 输出风格

- 改提示词时展示 before/after diff；解释 WHY 每处改动更好（不是改了什么）；重构时先给新大纲再写内容；提示词内容不用 emoji（费 token 且添噪）。
