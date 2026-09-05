# AGENTS.md 机制调研——市面 agent 规则/记忆文件规范对比与统一建议

> 阶段文档 · 完成即冻结 · 2026-09-02
> 调研范围：OpenAI Codex AGENTS.md 规范、Claude Code CLAUDE.md、Cursor Rules、GitHub Copilot 自定义指令、Gemini CLI GEMINI.md、agents.md 社区规范（Agentic AI Foundation）
> 现状对象：Project 工作区 `.nebflow/Agent.md`（Node 项目级 agent 指令）

---

## 0. 结论摘要

1. **市面已形成事实标准 `AGENTS.md`**：由 Linux Foundation 下属 Agentic AI Foundation 托管，60k+ 开源项目采用，Codex / Cursor / Copilot / Gemini CLI / Jules / Windsurf / Zed / Aider 等十余家 agent 工具原生读取。**命名与位置的兼容性是最大公约数**。
2. **我们的 `.nebflow/Agent.md` 有三点与市面不一致**：
   - 命名 `Agent.md` 在市面无先例（社区迁移指南明确 `mv AGENT.md AGENTS.md`）；
   - 位置藏在隐藏目录 `.nebflow/` 内，不可见、不可被发现；
   - **实际未进 git**（`.gitignore:18 .nebflow/` 整体忽略）——任务描述「已跟踪，进 git」与实际不符，指令当前无版本控制。
3. **统一建议：迁移到工作区根 `AGENTS.md`**（保留 `/api/projects/<name>/agent.md` API 路径不变，仅改磁盘读写位置），一举解决命名、位置、git 跟踪三个问题，并获得跨工具生态兼容。
4. 我们「Node 分发器任务引用 Agent.md」的**加载机制是特色**（非会话自动注入），本次迁移不改机制，只改文件落点。

---

## 1. 市面规范逐一调研

### 1.1 agents.md 社区规范（Agentic AI Foundation / Linux Foundation）

- **定位**：开放格式，60k+ 开源项目采用。「README for agents」——给 agent 的构建步骤、测试命令、代码约定。
- **文件位置**：仓库根 `AGENTS.md`；大型 monorepo 在每个包内放嵌套 `AGENTS.md`（OpenAI 主仓当时有 88 个）。
- **加载机制**：agent 自动读取目录树中**最近**的 AGENTS.md，closest wins；显式用户 prompt 覆盖一切。
- **冲突处理**：最近的 AGENTS.md 优先；不要求字段、纯 Markdown。
- **迁移指引**（官方 FAQ 原文）：`mv AGENT.md AGENTS.md && ln -s AGENTS.md AGENT.md`——即**单数命名 AGENT.md 是官方建议迁移掉的旧形态**。
- **托管**：原由 OpenAI/Amp/Jules/Cursor/Factory 协作发起，现由 **Agentic AI Foundation（Linux Foundation 下）** 托管维护。

来源：<https://agents.md/>

### 1.2 OpenAI Codex — AGENTS.md 规范

- **发现顺序**（每次运行构建一次指令链）：
  1. **全局层**：`CODEX_HOME`（默认 `~/.codex/`）下先找 `AGENTS.override.md`，否则 `AGENTS.md`，取第一个非空；
  2. **项目层**：从 git root 向下走到当前目录，每目录检查 `AGENTS.override.md` → `AGENTS.md` → fallback 文件名（`project_doc_fallback_filenames`），**每目录最多取一个文件**；
  3. **合并**：从 root 到 cwd 拼接，**靠近当前目录的在后、覆盖在前**；
  4. 空文件跳过；合并总量超 `project_doc_max_bytes`（默认 **32 KiB**）即截断。
- **层次覆盖**：用 `AGENTS.override.md` 做临时覆盖（无需删除基础文件），如 `services/payments/AGENTS.override.md` 覆盖仓库根 AGENTS.md。
- **glob 匹配：无**。Codex 是纯目录层次发现，不做 glob 作用域（与 Cursor/Copilot 不同）。
- **代码审查**：`## Code Review Rules` 段落实例。
- **配置**：`~/.codex/config.toml` 可设 `project_doc_fallback_filenames`（如 `["TEAM_GUIDE.md", ".agents.md"]`）和 `project_doc_max_bytes`。

来源：<https://developers.openai.com/codex/guides/agents-md>（codex 仓库 docs/agents_md.md 指向此页）

### 1.3 Claude Code — CLAUDE.md

- **四级作用域**（load order：broad → specific）：

| 层 | 位置 | 共享范围 |
|---|---|---|
| Managed policy | macOS `/Library/Application Support/ClaudeCode/CLAUDE.md`；Linux `/etc/claude-code/CLAUDE.md` | 组织全员 |
| User | `~/.claude/CLAUDE.md` | 仅本人、所有项目 |
| Project | `./CLAUDE.md` 或 `./.claude/CLAUDE.md` | 团队成员经版本控制共享 |
| Local | `./CLAUDE.local.md`（gitignore） | 仅本人、当前项目 |

- **加载机制**：启动时加载 cwd 及其**所有上级目录**的 CLAUDE.md / CLAUDE.local.md，**全部拼接而非覆盖**，顺序从文件系统根到 cwd（离启动点近的读最后）；子目录的 CLAUDE.md **按需加载**（读该目录文件时才注入）。
- **规则模块化**：`.claude/rules/*.md` 支持 YAML frontmatter `paths` 做 **glob 路径作用域**（如 `src/api/**/*.ts`），无 paths 则无条件加载；用户级 `~/.claude/rules/` 先加载、项目规则优先级更高。
- **与 AGENTS.md 的关系**：Claude Code 只读 CLAUDE.md 不读 AGENTS.md，官方推荐 `@AGENTS.md` 导入或 `ln -s AGENTS.md CLAUDE.md` 复用同一份指令。
- **规模建议**：每文件目标 <200 行；monorepo 可用 `claudeMdExcludes` 跳过其他团队文件。

来源：<https://code.claude.com/docs/en/memory>

### 1.4 Cursor — Rules

- **四种规则**：Project Rules（`.cursor/rules/*.mdc`）、User Rules（全局）、Team Rules（团队面板）、AGENTS.md（项目根纯 Markdown 替代品）。
- **.mdc 文件三字段 frontmatter** 决定应用方式：
  - `alwaysApply: true` → 每次会话都注入；
  - `globs: src/**/*.tsx` → 匹配文件在上下文时自动附加（**glob 作用域**）；
  - `description` → Agent 按相关性智能决定是否拉入；
  - 无字段 → 仅 `@-mention` 手动应用。
- **优先级**：Team Rules → Project Rules → User Rules，冲突时**先者优先**，不冲突则全部合并。
- **嵌套 AGENTS.md**：支持子目录 AGENTS.md，自动应用于该目录及子目录文件，与父级合并、**更具体者优先**（与 agents.md 社区规范一致）。
- **进 git**：官方 best practice 明确「Check your rules into git so your whole team benefits」。

来源：<https://docs.cursor.com/context/rules>

### 1.5 GitHub Copilot — 自定义指令

- **三级体系**：Personal（个人）> Repository（仓库）> Organization（组织），**全部提供不覆盖**。
- **仓库级**：`.github/copilot-instructions.md`；
- **路径级**：`.github/instructions/*.instructions.md`，frontmatter `applyTo` 用 **glob 语法**（如 `applyTo: "**/*.ts,**/*.tsx"`）；
- **Agent 指令**：`AGENTS.md` 可放仓库任意位置，**nearest wins**；也接受根目录单个 `CLAUDE.md` 或 `GEMINI.md`。
- 审查 PR 时读 head 分支的指令文件（可在 PR 内测试改动）。

来源：<https://docs.github.com/en/copilot/customizing-copilot/adding-repository-custom-instructions-for-github-copilot>

### 1.6 Gemini CLI — GEMINI.md

- **默认名** `GEMINI.md`，可在 `~/.gemini/settings.json` 配 `context.fileName: ["AGENTS.md", "CONTEXT.md", "GEMINI.md"]` 自定义（即官方支持 AGENTS.md 作为候选名）。
- **三级加载**：全局 `~/.gemini/GEMINI.md` → 工作区目录及父目录 → JIT（工具访问文件/目录时向上扫描到 trusted root），**全部拼接**发送；`/memory show` 查看、`/memory reload` 重载。
- **支持 `@file.md` 导入**模块化。

来源：<https://www.geminicli.com/docs/cli/gemini-md>

### 1.7 其他（简要）

- **Devin**：`.devin/rules/` 目录（Claude Code `/init` 提及，官方详情未抓到，标注：待用户提供）。
- **Windsurf**：`.windsurf/rules/` 或 `.windsurfrules`（同上，待用户提供）。
- **Aider**：`.aider.conf.yml` 配 `read: AGENTS.md`（agents.md 官网 FAQ）。
- **VS Code / Zed / JetBrains Junie / Factory / Jules**：均在 agents.md 官网「View all supported agents」名单中，原生读取 AGENTS.md。

---

## 2. 对比矩阵

| 维度 | agents.md 社区 | OpenAI Codex | Claude Code | Cursor | GitHub Copilot | Gemini CLI | **我们的现状** |
|---|---|---|---|---|---|---|---|
| 文件位置 | 仓库根 + 子目录嵌套 | 全局 `~/.codex/` + git root→cwd 每目录 | 项目根 / `.claude/CLAUDE.md` / `~/.claude/` / 子目录按需 | `.cursor/rules/*.mdc` + 根 AGENTS.md | `.github/copilot-instructions.md` + `.github/instructions/` | `~/.gemini/` + 工作区及父目录 | **`.nebflow/Agent.md`（隐藏目录内）** |
| 命名 | `AGENTS.md` | `AGENTS.md` / `AGENTS.override.md` | `CLAUDE.md` / `CLAUDE.local.md` | `*.mdc` / `AGENTS.md` | `copilot-instructions.md` / `*.instructions.md` / `AGENTS.md` | `GEMINI.md`（可配 `AGENTS.md`） | **`Agent.md`（市面无先例）** |
| 加载机制 | closest wins | root→cwd 拼接，靠后覆盖；32KiB 上限 | 上级→下级全拼接（不覆盖）；子目录按需 | frontmatter 控制（alwaysApply/globs/description） | nearest wins | 全拼接 + `@file` 导入 | **Node 分发器任务引用**（非自动注入） |
| glob 匹配 | 无 | 无（目录层次） | 有（`.claude/rules/` paths） | 有（.mdc globs） | 有（applyTo） | 无 | 无（项目级整体） |
| 层次覆盖 | closest wins | `AGENTS.override.md` 每层优先 | 拼接不覆盖（近启动点读最后） | 嵌套 AGENTS.md 更具体者优先 | nearest wins | JIT 最近 | 单层，无覆盖概念 |
| 作用域 | 项目/目录级 | 全局 + 项目/目录级 | 组织/用户/项目/本地 四级 | Team/Project/User 三级 | Personal/Repo/Org 三级 | 全局/工作区/JIT | 项目级 |
| 是否进 git | 是（建议） | 是 | 项目级进，local 不进 | 是（建议） | 是 | 是 | **否（`.gitignore:18` 忽略）** |

---

## 3. 我们的现状与差异分析

### 3.1 现状核实（与任务描述存在出入）

- **文件**：`<workspace>/.nebflow/Agent.md`（如 `/Users/dev/Claude code/Nebflow/.nebflow/Agent.md`），内容为项目级 agent 指令（技术栈、分支约定、协作协议、版本发布规则等），由 `createProject` 脚手架写入模板。
- **git 跟踪**：**实际未跟踪**。`git ls-files .nebflow/` 为空；`git check-ignore -v .nebflow/Agent.md` 命中 `.gitignore:18 .nebflow/`（项目根 `.gitignore` 将整个 `.nebflow/` 目录视为运行时数据忽略）。任务描述「已跟踪，进 git」与事实不符——**Agent.md 当前没有任何版本控制**。
- **产品链路**（全部硬编码 `.nebflow/Agent.md`）：
  - 后端 `src/main/scala/nebflow/gateway/RestApiRoutes.scala:324,339`：GET/PUT `/api/projects/<name>/agent.md` 读写 `workspace/.nebflow/Agent.md`；
  - 后端 `src/main/scala/nebflow/core/project/ProjectStore.scala:92-93`：脚手架写 `Agent.md` 模板；
  - 前端 `src/main/resources/web/js/nodeData.js`、`agentFileViewer.js`、`projectTab.js` + locales（提示文案「此文件是项目工作区的 agent 指令（.nebflow/Agent.md）」）。

### 3.2 差异点

1. **命名**：`Agent.md` 单数命名在市面无先例；agents.md 社区官方迁移指南明确把 `AGENT.md` 改名 `AGENTS.md`。
2. **位置**：藏在 `.nebflow/` 隐藏目录内，人类与外部工具均不可发现；市面主流均放仓库根（或至少可见目录）。
3. **git**：指令无版本控制，无法回溯、无法团队共享——与市面「进版本控制」的共识相反。
4. **加载机制（我们的特色，保留）**：市面均为会话启动/按路径自动注入；我们是由 Node 分发器按任务引用注入——这是 Project+Node 架构下的设计，**与文件命名/位置正交**，迁移不触碰。

---

## 4. 统一建议

### 4.1 结论：迁移到工作区根 `AGENTS.md`

理由（按权重排序）：

1. **生态兼容**：`AGENTS.md` 是市面最大公约数（60k+ 项目、Linux Foundation 托管、Codex/Cursor/Copilot/Gemini CLI/Jules/Windsurf/Zed/Aider 原生读取）。迁移后同一文件未来可被任意市面 agent 工具直接复用，无需 fallback 配置。
2. **修复版本控制缺口**：根目录 `AGENTS.md` 天然进 git（根 `.gitignore` 不覆盖它），一举解决「指令无版本控制」的真实问题（任务描述误以为已跟踪）。
3. **可发现性**：仓库根、README 同级，人类与工具都能看到。
4. **官方迁移路径成熟**：`mv Agent.md AGENTS.md` 即社区推荐做法，无需发明新机制。

### 4.2 改动方案

| # | 改动 | 涉及文件 |
|---|---|---|
| 1 | 将 `<workspace>/.nebflow/Agent.md` 内容迁移为工作区根 `AGENTS.md`（内容不变） | 各项目工作区（含 Nebflow 主仓） |
| 2 | 后端 API 磁盘读写位置改为 `workspace/AGENTS.md`（URL 路径 `/agent.md` 不变，前端零改动契约） | `src/main/scala/nebflow/gateway/RestApiRoutes.scala:324,339` |
| 3 | 脚手架模板写入位置改 `workspace/AGENTS.md`；兼容旧项目（旧位置存在且新位置不存在时读旧、保存时写新并提示迁移） | `src/main/scala/nebflow/core/project/ProjectStore.scala:92-93` |
| 4 | `createProject` 文档注释同步 | `src/main/scala/nebflow/core/tools/NodeTools.scala:553,579` |
| 5 | 前端注释与提示文案更新（「（AGENTS.md）」） | `nodeData.js`、`agentFileViewer.js`、`locales/en.js`、`locales/zh-CN.js` |
| 6 | 迁移期：旧 `.nebflow/Agent.md` 删除或保留 symlink 兼容（`ln -s ../AGENTS.md .nebflow/Agent.md`），一个版本后清理 | — |
| 7 | `.gitignore` 无需改动（`.nebflow/` 继续忽略，根 `AGENTS.md` 自动进 git） | — |

**不改**：加载机制（Node 分发器引用注入）、API URL、单层无覆盖语义（当前项目级单文件已够，无需嵌套 AGENTS.md）。

### 4.3 验收点（二值）

| # | 验收点 | 验证方式 |
|---|---|---|
| AC-1 | 工作区根存在 `AGENTS.md`，内容与迁移前 `.nebflow/Agent.md` 一致 | `diff <(git show 迁移前内容) AGENTS.md`（或手动比对） |
| AC-2 | `AGENTS.md` 进 git：`git ls-files AGENTS.md` 非空；`.nebflow/` 仍被忽略：`git check-ignore .nebflow/Agent.md` 退出码 0 | 两条 git 命令 |
| AC-3 | 后端 GET `/api/projects/<name>/agent.md` 返回根 `AGENTS.md` 内容（200）；PUT 保存后磁盘文件为根 `AGENTS.md` | curl GET/PUT + `cat <workspace>/AGENTS.md` |
| AC-4 | `createProject` 新建项目在工作区根生成 `AGENTS.md` 模板 | 建测试项目后 `ls <workspace>/AGENTS.md` |
| AC-5 | 旧项目迁移兼容：仅存在 `.nebflow/Agent.md` 时 GET 仍 200（读旧），保存后写入根 `AGENTS.md` | 用现有 Nebflow 工作区 curl 验证 |
| AC-6 | 前端 Agent.md 查看/编辑入口正常（标题/提示文案更新为 AGENTS.md） | Playwright 打开项目面板 → 点 Agent.md → 断言文案 + 保存回写根文件 |
| AC-7 | 无残留 `.nebflow/Agent.md` 硬编码引用（或 symlink 已建） | `git grep -n "\.nebflow.*Agent\.md"` 为空或仅剩兼容注释 |

---

## 5. 来源链接汇总

| 规范 | 来源 |
|---|---|
| agents.md 社区规范 | <https://agents.md/> |
| OpenAI Codex AGENTS.md | <https://developers.openai.com/codex/guides/agents-md> |
| Claude Code CLAUDE.md | <https://code.claude.com/docs/en/memory> |
| Cursor Rules | <https://docs.cursor.com/context/rules> |
| GitHub Copilot 自定义指令 | <https://docs.github.com/en/copilot/customizing-copilot/adding-repository-custom-instructions-for-github-copilot> |
| Gemini CLI GEMINI.md | <https://www.geminicli.com/docs/cli/gemini-md> |
| Devin `.devin/rules` | 待用户提供（Claude Code /init 提及，官方文档未直接抓到） |
| Windsurf `.windsurf/rules` | 待用户提供（同上） |

---

*调研完成 · 冻结（只分析未改码，改动方案待裁定后派发）*
