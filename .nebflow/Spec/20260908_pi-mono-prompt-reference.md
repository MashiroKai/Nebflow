# badlogic/pi-mono Agent 默认 System Prompt 参照（系统提示词架构审计 · 参照轨）

- 仓库：<https://github.com/badlogic/pi-mono>（shallow clone @ `/tmp/pi-mono-ref`）
- 快照 commit：`b2602be77cb7b0de45dd616407fd210daa48aa75`（2026-09-07，main HEAD）
- **提示词文件（仓内路径）**：`packages/coding-agent/src/core/system-prompt.ts`（`buildSystemPrompt()`，默认分支 L127–L144 为静态骨架）
- 佐证：全仓 src 内唯一的非测试/非示例 "You are…" 主体提示词即此处；`packages/agent`（底层引擎包）不内置提示词，`systemPrompt` 由调用方传入；`.pi/prompts/*.md` 是用户斜杠命令模板，非 agent 默认提示词。

---

## 一、原文全文（逐字）

### A. 静态骨架（system-prompt.ts L127–L144 模板字符串，逐字照录）

```text
You are an expert coding assistant operating inside pi, a coding agent harness. You help users by reading files, executing commands, editing code, and writing new files.

Available tools:
${toolsList}

In addition to the tools above, you may have access to other custom tools depending on the project.

Guidelines:
${guidelines}

Pi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, skills, or TUI):
- Main documentation: ${readmePath}
- Additional docs: ${docsPath}
- Examples: ${examplesPath} (extensions, custom tools, SDK)
- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... under Examples, not the current working directory
- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers (docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md), environment variables (docs/environment-variables.md)
- When working on pi topics, read the docs and examples, and follow .md cross-references before implementing
- Always read pi .md files completely and follow links to related docs (e.g., tui.md for TUI API details)
```

`${toolsList}` 插槽：每个在场工具一行 `- <name>: <snippet>`，snippet 来自工具定义的 `promptSnippet`（一行短语）；无可见工具时为 `(none)`。`${guidelines}` 插槽：bullets，来源 = ①工具可用性推导的探索规则（如仅有 bash 而无 grep/find/ls 工具时注入 `Use bash for file operations like ls, rg, find`）+ ②各工具 `promptGuidelines` + ③调用方追加，经 Set 去重；**无条件恒注入仅两条**：`Be concise in your responses` 与 `Show file paths clearly when working with files`。

### B. 运行时组装顺序（同一函数内按序追加，逐字）

骨架之后依次拼接（各段存在才追加，不存在即跳过）：

1. `appendSystemPrompt`（扩展/调用方追加段，原样字符串）
2. 项目上下文文件（逐字模板）：

```text
<project_context>

Project-specific instructions and guidelines:

<project_instructions path="${filePath}">
${content}
</project_instructions>

</project_context>
```

3. 技能目录块（`formatSkillsForPrompt`，逐字开头三行 + XML 条目）：

```text
The following skills provide specialized instructions for specific tasks.
Use the read tool to load a skill's file when the task matches its description.
When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.

<available_skills>
  <skill>
    <name>${name}</name>
    <description>${description}</description>
    <location>${filePath}</location>
  </skill>
  ...
</available_skills>
```

（XML 元素值经 `escapeXml` 转义 `& < > " '`；`disableModelInvocation` 的技能不注入；技能文件读取工具按 `read` → `bash` 降级选择。）

4. 末行：`Current working directory: ${promptCwd}`（反斜杠归一为正斜杠）

自定义 `customPrompt` 存在时**整体替换**默认骨架，但仍走同一套追加管线（contextFiles/skills/cwd）。

### C. 默认四工具的 snippet 与 per-tool guideline（逐字）

| 工具 | promptSnippet（进 Available tools 行） | promptGuidelines（进 Guidelines bullets） |
|---|---|---|
| read | `Read file contents` | `Use read to examine files instead of cat or sed.` |
| bash | `Execute bash commands (ls, grep, find, etc.)` | `You can inspect PI_* environment variables for current model and session details.` |
| edit | `Make precise file edits with exact text replacement, including multiple disjoint edits in one call` | `Use edit for precise changes (edits[].oldText must match exactly)` / `When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls` / `Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit.` / `Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions.` |
| write | `Create or overwrite files` | `Use write only for new files or complete rewrites.` |
| grep | `Search file contents for patterns (respects .gitignore)` | （无） |
| find | `Find files by glob pattern (respects .gitignore)` | （无） |
| ls | `List directory contents` | （无） |
| powershell | `Execute PowerShell commands` | 同 bash 的 PI_* 条 |

### D. 操作协议的真实载体：工具 description（read 工具样本，逐字）

```text
Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). Images are sent as attachments. For text files, output is truncated to 2000 lines or 512KB (whichever is hit first). Use offset/limit for large files. When you need the full file, continue with offset until complete.
```

（截断常量来自 `truncate.ts`：`DEFAULT_MAX_LINES=2000`、`DEFAULT_MAX_BYTES=512*1024`。）

### E. 体积量级

按默认工具集（read/bash/edit/write）+ 恒注入 guidelines + 文档段组装后实测：**约 1,733 字节 / 25 行**。项目 AGENTS.md、技能目录、扩展追加段按存在与否叠加——典型的满配运行时提示词仍在数 KB 量级。对比：主流 coding agent 默认提示词普遍 5–15KB，pi 的默认骨架是极小的一档。

---

## 二、结构解剖

**段落构成（骨架仅 5 段）**：

1. **身份段（2 句）**——你是谁（"expert coding assistant operating inside pi, a coding agent harness"）+ 你做什么（动词即工具：reading files, executing commands, editing code, writing new files）。无人格叙事、无品牌故事、无动机描述。
2. **工具段**——仅一行式 snippet 清单 + 一句"可能还有项目自定义工具"的开放声明。详细用法不在系统提示词，在工具 schema 的 `description`（见 D 节）。
3. **Guidelines 段**——全部 bullet 化；绝大多数条目是**条件注入**（工具在场才注入对应规则）或**工具贡献**（工具自带 guideline），恒注入仅 2 条通用规则；Set 去重保证同一规则不出现两次。
4. **自文档化段**——pi 把关于自身的文档路径列出，但读取条件写死："read only when the user asks about pi itself"，且给出主题→文档映射表与"完整阅读+跟随交叉引用"的阅读纪律。
5. **尾部锚点**——`Current working directory: …` 一行收尾。

**身份-协议-工具三段如何组织**：身份极简（一段话）；协议几乎不驻留系统提示词——操作协议下沉到工具 description 与 per-tool guidelines，行为规范以"工具在场 ⇒ 规则在场上"的条件方式聚合；工具以"一行 snippet 在提示词 + 全量协议在 schema"两级分离。系统提示词本质是**组装函数的输出**（参数：cwd、工具集、snippets、contextFiles、skills、append），工具集变化即整体重建（`_rebuildSystemPrompt`）——提示词是派生状态而非手写静态文本。

**工具用法写法**：description 一段话内按"能力 → 支持类型 → 边界行为（截断/上限）→ 续用策略（offset 续读直到读完）"推进，全是可执行事实，无营销语；易错点写成正向规则（"continue with offset until complete"）而非禁令。

---

## 三、可迁移写法原则（对照 Nebflow 三 keeper）

| # | 原则 | Nebula | project-dispatcher | general |
|---|------|--------|--------------------|---------|
| 1 | **身份一段话**：≤2 句，身份+能力动词，删人格叙事与动机描写 | ✔ 现身份段偏长，可砍半 | ✔ 同样适用 | ✔ 同样适用 |
| 2 | **单一来源、零重复注入**：工具用法只写在工具 description；系统提示词最多一行 snippet+交叉引用，同一规则不出现两处 | ✔ 防止"提示词复述工具规则" | ✔ 防止重复编排规则 | ✔ |
| 3 | **条件注入代替全量注入**：规则跟着能力走——工具/技能不在场，其规则整体不进 prompt | ✔ 技能目录已按需，平台段落可再条件化 | ✔ 只注入本项目所需段落 | ✔ |
| 4 | **大文档用路径引用+按需读取**：README/docs/skills 不全文进 prompt，给路径+触发条件+阅读纪律 | ✔ memory.md 渐进披露同构，可强化触发条件措辞 | ✔ Flow Map 用 NodeList 只读引用而非全文注入 | ✔ |
| 5 | **自文档化边界写死**：关于平台自身的文档，显式声明"仅当用户问起才读"+主题→文件映射 | ✔ Nebflow 自身文档引用适用 | ✔ | ✔ |
| 6 | **协议条目化+机制去重**：规则全部 bullet，无叙事段；注入侧做 Set 去重 | ✔ 口径/裁定条目单行化 | ✔ | ✔ |
| 7 | **操作协议进工具 description**：按"能力→类型→边界行为→失败/续用策略"一段话写全，易错点写成正向规则 | ✔ 对照补齐 Nebflow 工具的边界值与失败策略描述 | ✔ NodeEdit/Mail 类工具适用 | ✔ |
| 8 | **提示词是派生状态**：由参数组装（cwd/工具集/技能/项目上下文），环境变化即重建；不在定义文件里硬编码会过时的事实 | ✔ 环境表注入已是此形态，keeper 定义可效仿参数化 | ✔ 工作区路径/项目名应注入而非写死 | ✔ |
| 9 | **项目上下文标注来源**：`<project_instructions path="…">` 式包裹，模型知道规则出处与层级 | ✔ project memory 注入可标注来源路径 | ✔ | ✔ |
| 10 | **禁令具体化/正向化**：不用宽泛否定（"别啰嗦"），用具体正向规则（"Be concise"、"Use read instead of cat"） | ✔ 全部适用 | ✔ | ✔ |

**对 Nebflow 最有行动价值的三条**：#2（消除提示词与工具 description 间的规则重复）、#3（条件注入收窄 keeper 体积）、#7（工具 description 补边界行为与失败策略）。
