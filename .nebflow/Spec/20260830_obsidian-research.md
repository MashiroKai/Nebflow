# Obsidian 调研报告

> 调研日期：2026-08-30 ｜ 类型：阶段文档（一次性调研，完成即冻结）
> 调研对象：Obsidian（笔记/知识管理软件）——产品全貌 + 科研用户视角适用性
> 数据口径：网络检索（官网/官方文档/公开报道/官方 GitHub 仓库实测），关键数字均标来源；第三方估算数字明确标注"第三方估算"，未经官方证实。
> 版本背景：当前稳定版 v1.13.6（2026-08-10，Wikipedia 引用官方 changelog）

---

## 1. 产品定位与核心功能

**结论**：Obsidian 是**本地优先（local-first）的 Markdown 个人知识库（PKM）软件**——你的笔记就是你自己设备上的纯文本文件，软件只负责编辑、链接、检索与可视化。定位语："Sharpen your thinking"、"The free and flexible app for your private thoughts"（官网）。

### 1.1 关键事实

| 维度 | 事实 | 来源 |
|---|---|---|
| 数据模型 | 一个 "vault" = 本地文件夹里的 Markdown 纯文本文件，每条笔记一个 .md 文件 | 官网 / Wikipedia |
| 编辑 | Source Mode（源码）与 Live Preview（所见即所得预览）双模式 | Wikipedia |
| 双链 | `[[Wikilinks]]` 或标准 Markdown 链接，反链面板（backlinks） | 官网 / Wikipedia |
| 图谱 | Graph view：节点=笔记、边=内部链接，交互式可视化 | 官网 / Wikipedia |
| Canvas | 核心插件：无限 2D 画布，可排布笔记/附件/网页嵌入，2022-12 发布 | Wikipedia / heise |
| Bases | 核心插件：把笔记属性（YAML frontmatter）变成可编辑、可过滤的数据库视图（表格/卡片/画廊/地图），v1.9.0 早测 → v1.9.10（2025-08-18）正式发布 | 官方 changelog |
| Daily Notes / 模板 | Daily Notes 与 Templates 均为核心插件；社区 Calendar / Periodic Notes 增强 | 官网 / 官方 stats |
| 搜索 | 核心全文搜索；社区 Omnisearch 插件提供模糊搜索（180 万下载） | 官方 stats |
| 同步 | Obsidian Sync：端到端加密（AES-256）、1 年版本历史、共享 vault 多人协作、选择性同步（按文件类型） | 官方定价页 |
| 发布 | Obsidian Publish：把笔记发布为在线 wiki/知识库/数字花园，自定义主题/域名/密码，内置图谱与全文搜索 | 官方定价页 |
| 平台 | Windows / macOS / Linux / iOS / Android；**无 Web 版**；46 种语言 | Wikipedia |
| API/插件体系 | 开源插件 API（TypeScript），社区插件 7115 个 + 主题 717 个（2026-08-30 官方仓库实测，另有 175 个历史移除项） | obsidianmd/obsidian-releases 实测 |
| 剪藏 | 官方 Web Clipper 浏览器扩展 | 官网 |

### 1.2 官方三大承诺（官网原意）

1. **Your thoughts are yours.** 笔记本地存储，离线可用，"无人能读，包括我们"（无遥测、不卖数据）。
2. **Your mind is unique.** 数千插件+主题，把 Obsidian 塑造成贴合个人思维方式的形态。
3. **Your knowledge should last.** 开放文件格式，永不锁定，长期拥有数据。

---

## 2. 核心卖点

**结论**：四大卖点——**本地优先**、**纯文本可迁移**、**双链图谱的「第二大脑」理念**、**插件自由**。其中"数据完全在自己手里"是 Obsidian 与 Notion 等云工具的本质分野。

- **本地优先**：数据在本地磁盘，官方明确"不收集遥测数据、绝不卖用户数据"（定价页 FAQ）。2025-02 起商业使用也完全免费（oflight.co.jp 综述）。
- **纯文本可迁移**：.md 开放格式，任何编辑器可读；换工具/换电脑无锁定成本；天然适合 git 版本管理（Obsidian Git 插件 308 万下载）。
- **双链图谱「第二大脑」**：被学界/媒体描述为 Zettelkasten（卡片盒笔记法）的数字化实践工具（Wikipedia 引多个学术来源），图图谱揭示单看笔记发现不了的隐性关联。
- **插件自由**：7115 个社区插件把 Obsidian 从"笔记软件"塑造成 任务管理器、数据库、写作工具、白板、文献阅读器……几乎任何形态（Fast Company 2023："not prescriptive"）。

---

## 3. 生态

**结论**：Obsidian 拥有笔记软件领域最庞大的社区插件生态（实测 7115 插件 / 717 主题），社区规模处于 PKM 工具第一梯队；中文社区活跃（知乎/论坛/公众号教程生态成熟）。

### 3.1 插件生态（数据为 2026-08-30 官方仓库实测）

| 插件 | 下载量 | 用途 |
|---|---|---|
| Excalidraw | 762 万 | 手绘风格画布/绘图 |
| Templater | 545 万 | 高级模板引擎 |
| Dataview | 487 万 | 类 SQL 查询笔记元数据 |
| Tasks | 413 万 | 跨 vault 任务追踪 |
| Table Editor | 315 万 | 表格编辑 |
| **Obsidian Git** | 308 万 | git 版本管理（程序员向） |
| Calendar | 305 万 | 日历视图 |
| Style Settings | 264 万 | 主题微调 |
| Kanban | 261 万 | 看板 |
| Icon Folder | 220 万 | 文件夹图标 |
| Remotely Save | 219 万 | 第三方同步（WebDAV/S3 等，Sync 免费替代） |
| QuickAdd | 205 万 | 快捷捕获 |
| **Claudian** | 195 万 | Claude Code/Codex 嵌入 vault |
| Omnisearch | 181 万 | 模糊搜索 |
| **Copilot**（AI） | 178 万 | vault 对话 AI 助手 |
| Homepage | 130 万 | 启动页 |
| **Smart Connections**（AI） | 118 万 | 语义搜索/关联推荐 |
| Local REST API | 70 万 | 本地 HTTP API / MCP |
| Annotator | 59 万 | Obsidian 内 PDF 标注 |
| Pandoc | 55 万 | 导出 Word/PDF/LaTeX/ePub/PPT |
| Zotero Integration | 55 万 | Zotero 文献桥接 |
| ZotLit / ZotFlow | 7 万 / 1.3 万 | Zotero 新式桥接（持续维护中） |

![Obsidian 社区插件下载量 Top 15](/tmp/obsidian_top_plugins.svg)

### 3.2 社区规模

| 指标 | 数值 | 来源 |
|---|---|---|
| 用户量（2023 估算） | ~100 万（按 GitHub 下载数估算） | Fast Company 2023 |
| 用户量（2026 官方口径） | CEO Steph Ango 估算 **400 万+**，含 1 万+ 组织 | readthesignal.co 引 CEO 访谈 |
| 用户量（2026 第三方） | ~150 万 MAU | finance.biggo.com 第三方报道 |
| Discord | 11 万+（2023）→ ~19 万（2026，Hive Index） | Wikipedia / thehiveindex.com |
| Reddit r/ObsidianMD | ~9.46 万（2023，top 5% 社区） | Wikipedia 引 Fast Company |
| 插件/主题 | 7115 / 717（2026-08-30 实测） | obsidianmd/obsidian-releases |

> 注：用户量数字均为估算，口径不一（MAU vs 总用户），以官方 CEO 口径 400 万+ 与第三方 150 万 MAU 为参考区间。

---

## 4. 商业模式

**结论**：**核心功能免费且无限制，靠可选的云增值服务（Sync/Publish）和自愿性许可证盈利；100% user-supported，无广告、无 VC 融资。** 这是 Obsidian 与几乎所有竞品（Notion 订阅制、Logseq 开源赞助、思源笔记功能买断）最大的商业模式差异。

### 4.1 定价（官方 pricing 页）

| 项目 | 价格 | 说明 |
|---|---|---|
| 核心 App | **免费**，无限制、无注册 | 个人+商业均免费（2025-02 起商业免费） |
| Obsidian Sync | $4/用户/月（年付）或 $5 月付 | E2EE、1 年版本历史、共享 vault |
| Obsidian Publish | $8/站点/月（年付）或 $10 月付 | 发布为在线知识库 |
| Catalyst | $25 一次性 | 提前体验 beta + 社区徽章（自愿赞助） |
| Commercial License | $50/用户/年 | 组织内工作使用（鼓励性质，非强制） |
| 教育折扣 | Sync/Publish **40% 折扣** | 学生/教师/非营利组织 |

### 4.2 公司情况

| 维度 | 事实 | 来源 |
|---|---|---|
| 公司 | Dynalist Inc.（加拿大），前身产品为大纲工具 Dynalist | Wikipedia |
| 创始人 | Shida Li（CTO）、Erica Xu（COO），滑铁卢大学校友；2020 疫情隔离期间开发 | Wikipedia / 36kr |
| CEO | Steph Ango（kepano），2023-02 加入（原社区贡献者/创业者） | Wikipedia |
| 团队规模 | **约 7-8 人 + 1 只猫**（3-4 名工程师） | readthesignal / biggo 第三方报道 |
| 融资 | **无融资**，成立即盈利（"profitable from day one"） | readthesignal / Wunderlandmedia |
| 营收 | 第三方估算 ARR ~$2500 万 | biggo / readthesignal（第三方估算，未官方证实） |
| 估值 | 第三方报道 ~$3.5 亿 | 多家第三方报道（未官方证实） |
| 数据政策 | 无遥测、不卖数据；Sync 数据 AES-256 E2EE，官方无法读取 | 官方定价页 FAQ |

---

## 5. 用户群体与适用场景

**结论**：Obsidian 的核心用户是**知识工作者**——研究者、程序员、写作者、学生；典型用法按插件组合分化成不同"流派"。

| 场景 | 用法 | 代表插件 |
|---|---|---|
| 科研笔记 | 文献笔记 + 标注桥接 + 实验记录 | Zotero Integration / ZotLit / Annotator / Pandoc |
| PKM / 卡片盒笔记法（Zettelkasten） | 原子笔记 + 双链 + 图谱 | 核心功能 |
| 写作 | 长文写作 + 导出出版 | Pandoc / Longform |
| 项目管理 | 看板 / 任务 / 数据库视图 | Kanban / Tasks / Bases |
| 编程笔记 | 代码块 + 版本控制 + 模糊检索 | Obsidian Git / Omnisearch / Code Styler |
| 日记/日志 | Daily Notes + 周期笔记 | Periodic Notes / Calendar |
| 个人数据库 | 元数据驱动 | Dataview / Bases |

---

## 6. 优缺点与竞品对比

**结论**：Obsidian 的核心优势 = **本地性 × 开放性 × 可塑性**；核心代价 = **学习曲线陡 + 默认缺协作/数据库能力（需插件补齐）+ 官方同步要付费**。对比之下：Notion 协作强但数据锁定云端；Logseq 大纲化但文档写作弱；思源笔记中文友好+内置 AI 但生态小；Typora 只是编辑器不是知识库。

![笔记工具定位：本地性 × 结构范式](/tmp/obsidian_positioning.svg)

### 6.1 对比矩阵

| 维度 | Obsidian | Notion | Logseq | 思源笔记 (SiYuan) | Typora |
|---|---|---|---|---|---|
| 数据本地性 | **本地纯文本** | 云端 | 本地（Markdown/块） | 本地（块存储） | 本地纯文本 |
| 开源 | 否（专有，格式开放） | 否 | **是（AGPL）** | **是（AGPL）** | 否 |
| 双链/图谱 | ✅ 核心 | 有限 | ✅ 核心（块级引用） | ✅（块级） | ❌ |
| 结构范式 | 文档 + 双链 | 数据库优先 | 大纲（outliner） | 块级 | 纯文档 |
| 插件生态 | **7115 个** | 少 | 中等 | 中等（数百） | ❌ |
| 协作 | 弱（共享 vault 需 Sync） | **强（实时协作）** | 弱 | 弱 | ❌ |
| 移动端 | ✅ 全平台 | ✅ | ✅（一般） | ✅ | ❌（iOS 有精简版） |
| AI | 插件生态（178 万下载 Copilot 等）+ 本地模型 | 内置 Notion AI（付费） | 插件 | **内置 AI**（续写/润色等） | ❌ |
| 价格 | 核心免费；Sync $4/月 | 免费 + AI 订阅 $10/月 | 免费；Sync $5/月 | 免费 + 功能买断 ¥72 / 云同步 ¥148/年 | **$14.99 一次性** |
| 中文支持 | 46 语言，社区活跃 | 中文可用 | 一般 | **原生中文** | 中文良好 |
| 适合 | 重度知识管理/本地优先 | 团队协作/数据库 | 大纲思考/开源洁癖 | 中文用户/内置 AI | 纯 Markdown 写作 |

### 6.2 各自的短板（来源：多家 2026 对比评测 + 社区共识）

- **Obsidian**：学习曲线陡（Markdown + 插件选择成本，Wikipedia 批评）；官方同步/发布收费（免费替代可用 Remotely Save + git，219 万/308 万下载证明需求旺盛）；默认无协作；图图谱华而不实常被诟病。
- **Notion**：数据在云端（隐私/迁移风险）、离线能力弱、数据库锁死在自家格式。
- **Logseq**：大纲范式写长文弱、移动端体验一般、插件生态远小于 Obsidian（itsfoss 评测）。
- **思源笔记**：块存储格式导出有损、生态/国际化小于 Obsidian、AI 内置但模型调用需自备（知乎/CSDN 多篇对比共识）。
- **Typora**：定位纯编辑器，无双链/知识管理能力（官方定位"reader & writer"）。

---

## 7. 对科研用户的价值（结合用户背景：中科大博士 · 行星探测/FPGA 电子学）

**结论**：**Obsidian 是与该背景匹配度最高的主流笔记工具之一**——本地数据（适合涉密/实验数据敏感场景）、纯文本长期可读（博士 5-6 年跨度）、文献-笔记-写作全链路生态成熟、AI 生态可接本地模型。以下是按科研工作流拆解的结合点。

### 7.1 文献管理（Zotero 联动）
- 主流工作流：**Zotero 管文献库与 PDF 标注，Obsidian 管文献笔记与写作**，桥接插件把 Zotero 条目/标注/元数据导入为笔记，笔记内保留回链（可跳回 Zotero 原文位置）。
- 插件选择（2026 维护状态）：Zotero Integration（老牌，2024-08 后未更新）、**ZotLit / ZotFlow（持续维护，推荐新用户）**（vaultpicks.net 2026 综述）。
- Annotator 插件（59 万下载）：不依赖 Zotero 直接在 Obsidian 内做 PDF 高亮/标注，轻量方案。

### 7.2 实验笔记
- Daily Notes + Templates 模板化实验记录；Dataview 或 **Bases（官方数据库视图）**把散落的实验记录变成可筛选的数据库（如"探测器测试记录：日期/晶体/偏压/能谱分辨率"字段表）。
- 本地存储 + git 版本管理 = 实验记录可追溯、可回滚——工程/电子学背景（FPGA 开发习惯）会非常顺手。

### 7.3 论文写作
- 数学公式：原生 MathJax（LaTeX 语法 `$...$`）——物理公式无压力。
- 导出：内置 PDF 导出；**Pandoc 插件导出 Word/PDF/LaTeX/ePub**（配合学校 Word 模板或转投 Overleaf 精修 LaTeX）。
- 文献引用：Citations/Zotero 插件支持在笔记中插入引用条目（生成 bib 需配合 Zotero/Better BibTeX）。

### 7.4 图谱化知识体系
- 行星探测涉及多学科交叉（探测器物理/电子学/轨道力学/信号处理）——双链图谱天然适合建立跨领域概念网络，写综述时能"顺着链接找回旧笔记"。
- 注意：图谱对**长期积累型**用户价值最大，初期笔记少时效果有限（诚实的预期管理）。

### 7.5 面向博士生的其他要点
- **价格**：核心免费 + 学生 40% 折扣（Sync/Publish）。
- **数据主权**：毕业后数据完整带走，不受学校账号/商业工具政策影响；纯文本 20 年后仍可读。
- **代价**：需要投入时间搭建体系（插件选择、模板、文件夹 vs 纯双链的组织哲学之争）。

---

## 8. AI 相关（Obsidian 的 AI 生态现状）

**结论**：**Obsidian 官方对 AI 态度审慎（"社区 > AI"），未内置官方 AI 助手；但社区 AI 插件生态极其繁荣（Copilot 178 万下载），且"本地 Markdown + MCP"使它成为 AI agent 最友好的知识库载体之一。** 官方已小步进场：Bases 含 AI 工具、2025 年访谈明确 AI 须符合本地优先与用户支持价值观。

### 8.1 官方 AI 策略
- CEO Steph Ango（2025-08 The Verge/Decoder 访谈）："生产力工具更需要**社区**而非 AI"；Obsidian 的 AI 必须符合自身原则（本地优先、用户支持、不锁定）。[来源：The Verge 2025-08-18]
- 官方产品动向：Bases（2025-08）已带 AI 辅助能力（卡片视图 + AI 工具，Geeky Gadgets 2025 报道）；无内置聊天助手；无官方 MCP 服务器（截至调研，MCP 由社区提供）。

### 8.2 社区 AI 插件（按下载量）

| 插件 | 下载量 | 能力 |
|---|---|---|
| **Copilot**（logancyang） | 178 万 | vault 对话问答（带引用）、自定义 prompt、**V4 集成 Claude Code/Codex/OpenCode 代理**；支持 OpenAI/Gemini/Claude/本地 Ollama |
| **Smart Connections** | 118 万 | 语义搜索、笔记关联推荐（"被动回忆"）、AI 摘要 |
| **Claudian (realclaudian)** | 195 万 | **把 Claude Code 嵌入 Obsidian，vault 即 agent 工作目录**（读写/搜索/bash/多步任务） |
| Text Generator / BMO / Local GPT | — | 内联续写；本地模型（Ollama/LM Studio） |
| Knowledge AI | — | 基于笔记的问答（带引用）、生成学习指南/思维导图/幻灯片 |
| Cortex | — | Claude Code 驱动的 vault agent（侧栏对话，无需 API key） |

### 8.3 与 LLM 的结合方式
1. **插件内对话**：Copilot/Smart Connections 直接在 vault 上下文内问答——"AI 读你的笔记回答你"。
2. **MCP 协议**：Local REST API 插件内置 MCP server；obsidian-mcp 等社区项目——Claude Desktop/Cursor/CLI agent 可通过 MCP 读写 vault。**vault = agent 的上下文库**。
3. **Agent 直接操作文件**：Claudian/Cortex 把 vault 当工作目录跑 Claude Code——**这与 Nebflow 的"项目目录即 agent 工作区"理念同构**（见下节）。
4. **本地模型**：Ollama/LM Studio 全套支持——数据不出本机，科研数据安全友好。

---

## 9. 与 Nebflow 的结合可能（简评，参考性）

**结论**：Obsidian 与 Nebflow 是**互补而非竞争**关系——Obsidian 是"人的知识资产层"（长期、文件化、图谱化），Nebflow 是"agent 执行层"（任务、记忆、多智能体编排）。对用户（Nebflow 开发者 + 重度用户）而言，Obsidian 可作为个人 phd-notebook 知识库的载体，并通过两条路径与 Nebflow 打通。

| 维度 | Obsidian | Nebflow | 结合点 |
|---|---|---|---|
| 知识组织 | 文件 + 双链图谱（人工编织） | memory.md + docs 活文档（agent 维护） | Nebflow 的 memory 沉淀可导出/镜像为 vault 笔记 |
| 数据 | 本地 .md | ~/.nebflow 本地 | 同构：都是本地纯文本 |
| AI 接入 | MCP / 插件 / agent 直读 | 多 agent + 工具 | **Nebflow 通过 MCP 或文件系统读写 vault，把用户笔记变成 agent 上下文** |
| 记忆渐进披露 | 链接图谱（显式关联） | 分层记忆（短条目常驻 + 细节按需读） | 可互为参考：Obsidian 图谱补足 Nebflow 记忆的"关联发现"维度 |

- **可参考点 1（vault-as-agent-workspace）**：Claudian/Cortex 的"vault 即 agent 工作目录"模式，与 Nebflow 的项目目录工作区设计同构——Obsidian 的 phd-notebook 可直接作为 Nebflow agent 的知识型工作区。
- **可参考点 2（记忆设计）**：Obsidian 的渐进式关联（双链+图谱）与 Nebflow 的渐进式披露（memory 分层）解决同一问题（知识规模增长下的检索），两者机制可互相借鉴。
- **可参考点 3（生态策略）**：Obsidian 以"核心免费 + 7115 插件开放生态"换取社区繁荣，与其"用户支持、不融资"模式绑定——Nebflow 的 skill/flow 开放生态建设可对照观察。
- 未探索项（标注）：Obsidian 官方未提供 API 服务端能力（无官方 web API），Nebflow 集成需走文件系统或 Local REST API/MCP 社区方案——**此项未实测，属推断**。

---

## 10. 5 分钟速览

- **定位一句话**：本地优先的 Markdown 个人知识库——你的笔记是本地纯文本文件，双链+图谱+7115 个插件把它塑造成你的"第二大脑"。
- **3 个核心卖点**：
  1. **数据自有**：本地存储、无遥测、无人能读（"Your thoughts are yours"）；
  2. **永不锁定**：开放 .md 格式，可 git 管理、可迁移，数据 20 年可读；
  3. **自由可塑**：7115 插件/717 主题，从笔记软件到数据库/任务台/文献阅读器皆可。
- **价格**：核心**免费**（个人+商业）；Sync $4/月、Publish $8/月（年付）；学生/教师 **40% 折扣**；无融资、7-8 人团队、第三方估算 ARR ~$2500 万。
- **适不适合他（科研博士）**：**适合**——本地数据（实验/涉密友好）、纯文本长期可读（博士周期）、Zotero 文献联动 + Pandoc 论文导出全链路成熟、AI 生态可接本地模型；代价是搭建学习曲线与插件折腾时间。若完全接受云同步可考虑 $4/月 Sync 或免费 Remotely Save/git 方案。
- **一句话风险提示**：功能深度靠插件堆砌，官方默认体验朴素；对"开箱即用 + 协作"需求（如课题组共享知识库）Notion 更合适。

---

## 附：来源清单

**官方**
- 官网：https://obsidian.md/ （定位/功能/三大承诺）
- 定价页：https://obsidian.md/pricing （Sync $4/8、Publish $8/10、Catalyst $25、Commercial $50、教育 40% 折扣、E2EE/无遥测声明）
- Changelog（Bases 发布）：https://obsidian.md/changelog/2025-08-18-desktop-v1.9.10/
- 插件/主题/下载量统计：https://github.com/obsidianmd/obsidian-releases （community-plugins.json 7115 项、community-css-themes.json 717 项、community-plugin-stats.json 下载量，2026-08-30 实测）
- 插件目录：https://obsidian.md/plugins ；统计站 https://www.obsidianstats.com/

**媒体报道/第三方**
- Wikipedia "Obsidian (software)"（历史、功能、2023 社区规模、批评）
- Fast Company 2023-10 "The cult of Obsidian"（100 万用户估算、Discord 11 万/Reddit 9.46 万）
- The Verge 2025-08-18 "Obsidian's CEO on why productivity tools need community more than AI"（CEO AI 态度）
- readthesignal.co "Obsidian: $25M ARR, 8 People, 1 Cat"（ARR/团队/CEO 用户估算）
- finance.biggo.com / versaedits.com / 36kr EN（150 万 MAU、$25M ARR、$350M 估值、团队 7-8 人——第三方估算）
- vaultpicks.net "Obsidian + Zotero for academic research (2026)"（Zotero 桥接插件维护状态）
- it's FOSS / daily.dev / toolchase 等 Obsidian vs Logseq 对比（Logseq 特性与定位）
- 思源笔记官网/定价 b3log.org/siyuan（开源 AGPL、¥72 功能买断、¥148/年云同步 8GB、S3/WebDAV）
- Typora 官网/定价 store.typora.io（$14.99 一次性、3 设备）
- Logseq 定价 logseq.com/pricing（免费开源、Sync $5/月）
- 知乎/CSDN/什么值得买多篇 Obsidian vs 思源对比（中文社区视角，作为共识性参考）

**未查实/标注项**：官方营收/估值数字（第三方估算，官方未披露）；Nebflow 与 Obsidian 集成方案（推断，未实测）；Bases AI 工具的具体能力边界（公开报道有限）。
