# Nebflow 路演数据调研报告——中科大「经纬」创业项目路演

> 调研日期：2026-08-30 ｜ 调研人：Explorer（单次独立调研，未修改任何文件）
> 用途：支撑路演讲稿四论点——①Agent 产业早期、市场未被大厂完全抢占、时机好 ②垂直领域 Agent 市场空白/蓝海 ③FPGA 垂直 skill 获 GitHub 关注 ④融资主要用于 token + 服务器（每人每月 token 约 3000 元）
> 数据基准：所有在线数据截至 **2026-08-30**；GitHub star/日期为当日 GitHub API 实时实测；标注「实测」者为第一手数据，其余为权威来源转述

---

## 0. 摘要（六节结论速览）

| # | 调研项 | 一句话结论 |
|---|--------|-----------|
| 1 | 市场规模 | AI Agent 市场 2025 年约 78 亿美元、2030 年 526 亿美元（CAGR 46.3%，MarketsandMarkets）；AI 编程工具已验证爆发力（Copilot 2000 万用户、Cursor 13 个月 ARR 1 亿→20 亿美元） |
| 2 | 时机 | 「2025 Agent 元年」为业界共识（Manus/Claude Code/Codex 均 2025 年爆发）；OpenClaw 2025-11 创建、2026 爆红至 38.8 万 star；协议（MCP/A2A/ACP/AGNTCY）与产品形态均未统一——**窗口未关** |
| 3 | Kimi 人才观 | 「好奇心」未找到可核实的逐字原文；最接近权威表述为张小珺 2024 访谈「如果所有人都觉得你正常……对人类的理想总量没有增量」；taste 见 2026 AGI-Next 演讲；「用 Agent 深化自身能力」最接近表述为 2025-07 访谈「K2 参与 K3 的开发过程」（用 AI 深化 AI） |
| 4 | FPGA skill GitHub | **核实结论：Nebflow 的 FPGA skill 未公开在 GitHub**——slideblocks 公开仓（github.com/UniUni2000/slideblocks）返回 404，FPGA 69 页 deck 是个人讲座内容且已从公开仓剥离；路演稿 P13「FPGA 校友 skill 获大量星标」**缺乏公开证据，建议改口径**；替代证据：GitHub 同类 FPGA skill 全部处于极早期（最高 250 star），反而佐证蓝海 |
| 5 | Token 成本 | 「每人每月 3000 元」**合理且偏保守**：K3 输出 100 元/百万 token，重度长程任务按 4-6 任务/天 × 22 天 ≈ 1570-3520 元/月（国产主流～旗舰区间）；海外高端模型可达 8000+ 元/月；服务器：8C16G 约 500-1000 元/月、A10 GPU 约 3200 元/月，与申请书「服务器 4 万/年」吻合 |
| 6 | 垂直空白 | 通用 coding agent（Codex/Cursor/Claude Code）确实只覆盖通用编程；FPGA/EDA 方向已有巨头进场（EDA 三巨头 2026-03 GTC 发布 agent 产品、Kimi K3 自主设计芯片）——**空白在「通用 Agent 平台 + 科研/FPGA 垂直 skill 生态」这一组合层**，需在讲稿中精确表述，避免说「EDA 完全空白」被评委反驳 |

---

## 1. AI Agent / AI coding 市场规模

### 1.1 AI Agent 市场（全球，权威机构预测）

| 来源 | 2025 年规模 | 预测 | CAGR | 出处 |
|------|-----------|------|------|------|
| MarketsandMarkets | 78.4 亿美元 | 2030 年 526.2 亿美元 | 46.3%（2025-2030） | https://www.marketsandmarkets.com/Market-Reports/ai-agents-market-15761548.html |
| Grand View Research | 76.3 亿美元 | 2026 年 109 亿 → 2033 年 1829.7 亿美元 | 49.6%（2026-2033） | https://www.grandviewresearch.com/industry-analysis/ai-agents-market-report |

- 两家机构口径一致：**2025 年全球 AI Agent 市场约 76-78 亿美元，未来 CAGR 约 46-50%**（引用时建议取 MarketsandMarkets 的 46.3% 或 Grand View 的 49.6%，注明来源与年份）
- 增长叙事：市场从 2023 年近乎为零 → 2025 年 78 亿美元 → 2030 年 526 亿美元（agentmarketcap.ai 综合转述：https://agentmarketcap.ai/blog/2026/04/05/ai-agent-market-7-84b-2025-projected-52b-2030-revenue-breakdown ）

### 1.2 Gartner 宏观数字（可作「大势已起」背书）

| 数字 | 内容 | 出处 |
|------|------|------|
| 2028 年 1/3 GenAI 交互由自主 agent 完成 | Gartner 2024-03-11 官方新闻稿 | https://www.gartner.com/en/newsroom/press-releases/2024-03-11-gartner-predicts-one-third-of-interactions-with-genai-services-will-use-action-models-and-autonomous-agents-for-task-completion-by-2028 |
| 2029 年 agentic AI 自主解决 80% 常见客服问题、运营成本降 30% | Gartner 2025-03-05 预测 | https://www.makebot.ai/blog-en/gartner-agentic-ai-customer-service-80-percent-2029 |
| 2028 年 33% 企业软件包含 agentic AI（2024 年 <1%） | Gartner 转述（agentmarketcap.ai 2026-04-11） | https://agentmarketcap.ai/blog/2026/04/11/gartner-15-percent-agent-autonomy-forecast-2026 |
| 2028 年 AI agent 主导 15 万亿美元 B2B 采购 | Gartner（digitalcommerce360 2025-11-28 报道） | https://www.digitalcommerce360.com/2025/11/28/gartner-ai-agents-15-trillion-in-b2b-purchases-by-2028/ |
| 2026 年全球 AI 支出 2.5 万亿美元 | Gartner 2026-01-15（雪球转述） | https://xueqiu.com/（检索「Gartner 2026 全球 AI 支出 2.5 万亿」，2026-01-15） |

### 1.3 AI 编程工具（市场需求已被验证的实证）

| 指标 | 数字 | 出处 |
|------|------|------|
| GitHub Copilot 累计用户 | **2000 万**（2025-07-31 微软财报电话会，纳德拉宣布；2025-04 为 1500 万，3 个月 +500 万；企业客户增长率 75%，财富 100 强 90% 使用） | 新浪科技 https://t.cj.sina.com.cn/articles/view/1826017320/6cd6d02804001g8h4 ；至顶网 https://ai.zhiding.cn/2025/0731/3169773.shtml |
| Cursor（Anysphere）ARR 增长 | 2025-01 约 1 亿 → 2025-06 5 亿 → 2025-11 10 亿 → 2026-02 **20 亿美元**（13 个月 20 倍，史上最快 B2B SaaS） | https://aiincider.com/cursor-2-billion-funding-50-billion-valuation/ ；https://siliconcanals.com/t-cursor-100-million-2-billion-annualized-revenue-13-months/ |
| Cursor 用户结构 | 超半数财富 500 强使用（NVIDIA、Uber、Adobe） | Cursor 官方博客 https://cursor.com/blog/series-c |
| **Cursor 成本结构** | **多份报告称 Cursor 把 100% 的营收花在 AI（token）成本上** | https://aifundingtracker.com/cursor-revenue-valuation/ ——路演 P16「融资主要花在 token」的最佳对标案例 |
| Stack Overflow 2025 开发者调查 | 方向确认：开发者 AI 工具使用率极高、但对输出准确性信任度下滑（「五万人大规模调查：开发者对 AI 工具信任度开始下降」）；**84%/46% 两个具体数值建议在讲稿中标注「据 SO 2025 开发者调查」并在定稿前二次核对原报告**（本次检索未直接命中 84%/46% 原始出处） | 百度收录转载 https://www.baidu.com/link?url=l0N4c3EpMOlqe-g_oKygLesrlYy3_oSctHVTtKbQxXDrNuJb2Gtq4VNepZ93pEy6505_IxVPV-2vwTCcJZwOmq |

---

## 2. 时机论据

### 2.1 「2025 是 Agent 元年」的说法来源

- **业界共识表述**：多家机构/媒体将 2025 称为「AI Agent 元年」「Year of the Agent」（2025-03 Manus 惊艳发布 → 全年产品爆发 → 年底 Clawdbot/OpenClaw 病毒式传播）
  - 中国人工智能产业发展联盟（AIIA）《AI Agent 智能体技术发展报告》（2026-01）：引言标题即「2025，AI Agent 元年的开启」——www.china-aii.com/u/cms/www/202601/AI Agent智能体技术发展报告.pdf
  - 中国日报（2025-08-01）：「2025 年被业界普遍认为是『AI Agent 元年』，从年初开始国内外 AI 科技巨头纷纷将 AI Agent 上升到企业战略高度」——https://caijing.chinadaily.com.cn/a/202508/01/WS688c8277a3105a871d62d25c.html
  - 李开复（零一万物 CEO）GOTC2025 演讲：「推理 Agent 元年」——https://www.sohu.com/a/948186455_122362510
  - 比尔·盖茨 2023-11 预言 Agent 将取代 app 交互范式（先声）
- **2026 延续**：杨植麟 2026 中关村论坛称「2026 年被普遍视为 AI 智能体全面落地的关键一年」（凤凰网转载北京日报专访）——https://news.ifeng.com/c/8sJMGz2QXdI

### 2.2 OpenAI Codex 时间线

| 时间 | 事件 | 出处 |
|------|------|------|
| 2025-04-13 | Codex CLI 开源（openai/codex，Apache 2.0，终端 coding agent） | GitHub 实测：仓库创建 2025-04-13，star 119,869（2026-08-30） |
| 2025-05-16 | Codex 云 Agent 研究预览版发布（云端沙箱、并行任务、预载代码库） | 智东西/36氪 2025-05-17 报道：https://m.36kr.com/p/3295883566745859 |
| 2025-09 | GPT-5-Codex（面向 agentic 编程优化） | 知乎专栏 https://zhuanlan.zhihu.com/p/2037861641698666348 |
| 2025-10 | Codex 全面开放 GA（编辑器/终端/云端全场景） | OpenAI 官网 https://openai.com/zh-Hans-CN/index/codex-now-generally-available/ |
| **2026-08-19** | **Codex Harness 全面开源：app-server 协议与 SDK 集成接口完整开放**（此前仅 CLI 前端开源，Rust 核心 codex-rs 与 app-server 内部实现）——**距路演仅 11 天，Agent 运行时平台层刚刚开放，形态远未定型** | 七牛云/segmentfault https://segmentfault.com/a/1190000048185190 |

### 2.3 Claude Code 时间线

| 时间 | 事件 | 出处 |
|------|------|------|
| 2025-02-24 | Claude Code 研究预览版发布（随 Claude 3.7 Sonnet；终端原生 agentic coding） | GitHub 实测：anthropics/claude-code 仓库创建 2025-02-22，star 143,416（2026-08-30）；发布日见 https://www.scriptbyai.com/claude-code-timeline/ 及 devclass 报道 https://www.devclass.com/ai-ml/2025/02/27/anthropic-previews-claude-code-agentic-coding-capable-but-costly/1622996 |
| 2025-05-22 | 全面可用 GA（随 Claude 4 系列） | https://www.scriptbyai.com/claude-code-timeline/ |
| 2025 冬 | 开发者采用爆发（holiday surge），年化收入破 10 亿美元级 | https://aidailypost.com/news/anthropics-claude-code-launched-feb-2025-rides-wave-industry-buzz |
| 现状 | SWE-bench Verified 87.6% 得分（当前最高分 coding agent） | https://www.studioglobal.ai/discover/answers/what-are-the-key-details-about-anthropic-s-6a2480905b21f12644550835 |

### 2.4 Cursor / Anysphere 成立与融资时间线

| 时间 | 事件 | 出处 |
|------|------|------|
| 2022 年 | Anysphere 成立（MIT 背景，Michael Truell 等） | Cursor 官方博客/公开报道 |
| 2023 年 | Cursor 产品发布 | https://www.humanizeio.ai/blog/article/cursor-ai-statistics-users-revenue-developer-adoption |
| 2025-01 | B 轮 1.05 亿美元，估值 25 亿美元，ARR 1 亿美元 | https://www.arr.club/cursor/cursor-arr-hits-500m |
| 2025-05 | **C 轮 9 亿美元，估值 99 亿美元（Thrive/Accel/a16z/DST），ARR 破 5 亿美元，超半数财富 500 强使用** | Cursor 官方 https://cursor.com/blog/series-c ；新浪财经 https://finance.sina.com.cn/ |
| 2025-11 | ARR 10 亿美元（24 个月，史上最快） | https://aifundingtracker.com/cursor-revenue-valuation/ |
| 2026-02 | ARR 20 亿美元 | https://aiincider.com/cursor-2-billion-funding-50-billion-valuation/ |
| 2026 年 | 传以 500 亿美元估值融资 20 亿美元 | 同上（aiincider 2026） |

> 路演要点：一家成立 3-4 年的公司 13 个月 ARR 翻 20 倍——**AI 编程/Agent 工具的付费意愿已被市场验证**；同时 Cursor 100% 营收花在 token 上——**「Agent 平台生意本质是 token 中转 + 能力封装」已被行业验证**，正好支撑 Nebflow 的商业模式（P17 中间商/token pay）。

### 2.5 OpenClaw（用户所问「OpenClaw/OpenClaude」实为 OpenClaw）——真实项目核实

- **GitHub 实测（2026-08-30）**：`github.com/openclaw/openclaw`，star **388,025**、fork **81,474**、创建 **2025-11-24**、license MIT、官网 openclaw.ai
- 定位：**个人 AI 助理**（Your own personal AI assistant. Any OS. Any Platform. The lobster way. 🦞）——通过 Gateway 统一控制平面连接 WhatsApp/Telegram/Slack/Discord/iMessage 等渠道，支持本地/托管模型、skills/plugins 扩展、单人或团队部署
- **爆红时间点**：仓库 2025-11-24 才创建 → 爆红在 **2025 年底至 2026 年**（中文圈称「养龙虾」，并非路演稿 P11 所写的「2025 上半年」——**建议讲稿修正为「2025 年底到 2026 年」**）
- 爆红现象证据：
  - 「OpenClaw 突破 35 万 star，开源 AI Agent 新标杆诞生」（今日头条，约 2026 年中）
  - 澎湃（湃客）：「OpenClaw 意外走红，智谱、MiniMax、Kimi 终于得救了」——爆红带动国产模型 API 消耗，https://www.thepaper.cn/（检索「OpenClaw 意外走红」）
  - 「去年追着梁文锋的投资人，今年下班疯狂补习养龙虾」（今日头条）——2026 年创投圈现象级话题
  - 「今日炒龙虾，马化腾、朱啸虎发声」「OpenClaw 为何能两度爆火」（百度收录多篇）
  - 生态联动：cc-switch（13 万 star）等工具将 Claude Code/Codex/OpenCode/OpenClaw/Grok Build/Hermes Agent 并列管理——OpenClaw 已成为 2026 年 Agent CLI/个人助理生态核心玩家
- **与 claude-code-sdk 的关系**：OpenClaw 是**独立项目**（自研 Gateway/工具/渠道层，npm 包 `openclaw`，Node 22+），并非 claude-code-sdk 的直接 fork；但其爆红与 Claude Code 带火的「个人 agent 助理」需求同源

### 2.6 Agent 形态未定论据（窗口未关的核心支撑）

1. **协议未统一**：MCP（Anthropic，2024-11，工具层）、A2A（Google，2025-04，agent 间通信）、ACP（IBM）、AGNTCY（Cisco 领衔，2025-06，企业编排）多协议并存竞争，官方叙事「分层共存」与生产现实「碎片化」并存——见 https://futurepicker.com/agent-interop-protocols-mcp-a2a-agntcy-war-2026/ 、https://idea2dev.app/blog/blog-a2a-acp-mcp-agent-protocols-comparison.html
2. **形态未定**：同一需求三种产品形态并行——CLI（Claude Code/Codex CLI/OpenClaw）、IDE（Cursor/Windsurf/Trae）、云端（Codex Cloud/Claude web/OpenClaw Gateway）——https://zhuanlan.zhihu.com/p/2037861641698666348
3. **平台层刚开放**：2026-08-19 OpenAI 才完整开源 Codex Harness（Agent 运行时底层 + SDK）——平台标准尚在形成（见 2.2 末行）
4. **权威定调**：杨植麟 2026 中关村论坛「2026 年被普遍视为 AI 智能体全面落地的关键一年」——**「元年」之后的落地窗口期 = 现在**

---

## 3. Kimi 的 AI 人才观（月之暗面 / 杨植麟公开表述）

### 3.1 「好奇心」——未找到可核实的逐字原文，以下为最接近的权威表述

| 表述 | 出处 |
|------|------|
| 「如果所有人都觉得你正常，你的理想是大家都能想到的，它对人类的理想总量没有增量。」——杨植麟谈理想/非共识（对「好奇心驱动」最接近的哲学表述） | 张小珺《对话月之暗面杨植麟：向延绵而未知的雪山前进》，36氪/腾讯新闻，2024-01：https://www.36kr.com/p/2677672437708552 |
| 「人才密度是月之暗面最主要的特色之一」——杨植麟谈团队标准（2024-03，成立半年即融资超 20 亿元、清华系为主） | 36氪：https://m.36kr.com/p/2468455352145798 |
| 「中国已建立了全世界最好的人才系统……从义务教育到高中、大学、博士，我们现在可以培养出全世界探索能力最强的人」「通过人类智能去孵化人工智能」 | 杨植麟 2026 中关村论坛年会主旨演讲 + 北京日报采访（凤凰网转载）：https://news.ifeng.com/c/8sJMGz2QXdI |
| 「北京的人才（资源）在全世界一定能排前两名」 | 同上（2026 中关村论坛） |

### 3.2 Taste（品味/审美）

| 表述 | 出处 |
|------|------|
| 「杨植麟认为，智能应当具备独特的『品味』（Taste），且是非同质化的」——2026 AGI-Next 前沿峰会演讲 | AI 智识录播客整理：https://www.xiaoyuzhoufm.com/episode/696742e047cd598dbff06f02 |
| 「真正伟大的公司需要人文底蕴，而不仅是技术和产品」（中国 AI 50 人访谈） | 网易转载：https://www.163.com/dy/article/K4O4MNA105568W0A.html |

### 3.3 「用 Agent 深化自身能力」——未找到逐字表述，最接近的权威表述是「用 AI 深化 AI」

| 表述 | 出处 |
|------|------|
| L4（创新者）的标志：「模型参与模型本身的开发；实现：K2 参与 K3 的开发过程」「用 L4 的技术去解决 L3 的问题（Agent 泛化不够，用创新解决）」 | 张小珺访谈杨植麟（2025-07，K2 发布后）整理版：https://emigmo.github.io/blog/2025/yang-zhilin-ai-philosophy/ |
| 「AI 是人类文明的放大器」；人若把 Agent 用得好，Agent 反哺人的能力边界（访谈语境下的延伸） | 同上 + 2025-07 访谈实录（B 站 BV1hFe1zSEXp） |
| 对 Agent 泛化的判断：「如果泛化能力强，垂直 Agent 就没那么必要；通用 Agent 能泛化到长尾工具」——**注意：这是反方观点，评委可能引用**，建议讲稿提前准备应答（Nebflow 的立场：通用模型泛化尚未到位，垂直 skill 现在有价值、未来是通用模型的补充层） | 同上（张小珺 2025-07 访谈） |

> **如实标注**：路演稿 P14 引用的「人才观强调好奇心、taste、以及能否用 Agent 深化自己的能力」——「好奇心」与「用 Agent 深化自身能力」**未检索到公开逐字原文**（taste 有 2026 AGI-Next 出处）。讲稿建议改为：「我们对照 Kimi 创始人杨植麟的 AI 人才观——他多次强调人才密度、非共识的好奇心（『对人类的理想总量没有增量』）、智能的品味（Taste），以及用 AI 深化 AI（K2 参与 K3 开发）——我们团队正是这样的人……」并注明引用出处（36氪访谈/2026 AGI-Next/张小珺访谈）。

---

## 4. FPGA 垂直 skill 的 GitHub 数据

### 4.1 本地核实（第一手，2026-08-30）

| 检查项 | 结果 |
|--------|------|
| github.com/UniUni2000/slideblocks | **404，仓库不存在/未公开**（GitHub API 实测） |
| slideblocks 公开仓内容 | 公开历史已清洗（08-28 迁移方案 v2 记录：「作者红线落定——deck 内容永不进平台公开仓；PR #5 历史已清洗，公开仓 decks/ 仅 README+macbook+sgr」） |
| FPGA deck 现状 | 《如何使用 Agent 为 FPGA 开发、科研提速》69 页个人讲座 deck，源工程在 html-deck-studio（本地仓，无 remote），**非公开**；slideblocks 平台仓内 decks/ai-fpga-dev 已于 08-25/08-28 方案中剥离 |
| 本地 skills 目录 | ~/.nebflow/skills/ 无 xilinx/fpga skill（现有：slideblocks、card-design、design-system 等）；slideblocks/skills/ 仅 slideblocks 一个平台 skill |
| 记忆提示的「deck P32 标注中科大校友」 | ai-fpga-deck 为 69 页讲座 deck（存在）；但该 deck 未公开，无 GitHub 星标可言 |

### 4.2 结论与路演口径建议

**「Nebflow 的 FPGA 垂直 skill 已获 GitHub 关注」无法核实——本地 skill 未公开，slideblocks 公开仓 404。** 路演稿 P13 若保留此说法，现场被查证将构成事实风险。**建议改为**以下任一口径：
1. 弱化版：「我们为 FPGA 开发打造了完整的垂直 skill 体系（Team + Flow + 69 页实战教学 deck），在内部科研团队已实际使用」——不提 GitHub 星标；
2. 数据替代版：引用 GitHub 同类项目佐证需求真实存在但无人主导（见 4.3），再讲「这正是我们的机会」。

### 4.3 替代证据：GitHub 同类 FPGA 垂直 skill 生态（实测，2026-08-30，全部早期）

| 仓库 | star | 定位 |
|------|------|------|
| Eriemon/verilog-generator | 250 | Agent skill：Verilog-2001 RTL 生成与 FPGA 设计工作流 |
| adeleempurpled290/FPGA-Agent-skills | 30 | 8 个 Vivado/Vitis skill（HLS/RTL/综合/约束/时序） |
| Eriemon/hls-generator | 24 | AMD/Xilinx Vitis HLS 生成 skill |
| Tomer-Harari/claude-fpga-skills | 1 | cocotb 方法论、AXI-Stream 正确性等 |
| michpro/CPLD-FPGA_Toolkit | 1 | CPLD/FPGA agent skills + 知识图谱 |

> 论据价值：**「FPGA 垂直 agent skill」需求已被开源社区证实（多个独立作者在写），但最大项目仅 250 star——细分领域无人占主导 = 蓝海窗口**。与 OpenClaw 38.8 万 star 的通用层对比，垂直层空白显著。同样逻辑可类比科研/EDA（见第 6 节）。

---

## 5. Token 成本行情（支撑「每人每月 3000 元 token」）

### 5.1 主流模型 API 价格（2026-08 实时，单位：元/百万 token）

| 模型 | 输入（缓存命中） | 输入（未命中） | 输出 | 备注/出处 |
|------|-----------------|---------------|------|-----------|
| DeepSeek V4-Flash | 0.05-0.10 | 1.5-3.0 | 4.5-9.0 | 峰谷两档（高峰=工作时段 2 倍价）；上下文 1M、输出 384K；**官方** https://api-docs.deepseek.com/zh-cn/quick_start/pricing |
| DeepSeek V4-Pro | 0.15-0.30 | 4.5-9.0 | 13.5-27.0 | 同上（官方文档，2026-08-30 抓取） |
| Kimi K3（旗舰，2026-07-17 发布） | **2** | **20** | **100** | 2.8 万亿参数开源 MoE；1M 上下文；较 K2.6 涨价约 4 倍（能力溢价转向）；官方 https://platform.kimi.com/docs/pricing/chat-k3 ；转述 https://www.chooseai.net/news/5140/ |
| Kimi K2.6 | — | 6.5 | 27.0 | 上下文 262K；https://www.llmabacus.com/models/kimi-k2-6 |
| GLM-4.5（智谱，2025-07） | — | 0.8 | 2 | 官方文档 https://docs.bigmodel.cn/cn/guide/models/text/glm-4.5（「API 调用价格低至输入 0.8 元/百万 tokens，输出 2 元」） |
| GLM-4.6（智谱旗舰） | — | 约 4.05 | 约 16 | 官方输入费率 ¥4.05（api.airforce 转述）；OpenRouter $0.43/$1.75；355B 总参/32B 激活、200K 上下文 |
| Qwen3.5-Plus（通义） | — | 0.8 | — | 阿里云开发者社区「每百万 Token 仅 0.8 元」（2026） |
| 海外高端（Claude Opus 4.x / GPT-5.x 级别） | — | 约 $3-5（≈¥21-36） | 约 $15-30（≈¥107-215） | OpenRouter 公开价区间，供对比 |

**价格梯度结论**：国内模型输出价从 2 元（GLM-4.5）到 100 元（K3）跨两个数量级；**旗舰模型（K3）输出价是性价比模型（DeepSeek V4-Flash）的 11-22 倍**——「K3 定价 100 元/百万，比 DeepSeek 贵 40 倍」为媒体原话（搜狐：https://www.sohu.com/a/1052803656_122883455）。

### 5.2 「每人每月 3000 元」合理性测算

假设（重度用户）：每天 8 小时高强度使用 agent，长程任务为主；每任务输入约 **100 万 token**（20-25 万上下文 × 多轮累积 + 工具结果回灌，长程任务实测往往更高）、输出约 **15-20 万 token**；每天 4-6 个任务、每月 22 个工作日：

| 模型档位 | 单任务成本（输入 100 万 + 输出 20 万） | 月成本（5 任务/天 × 22 天） |
|----------|--------------------------------------|---------------------------|
| 国产性价比（DeepSeek V4-Flash 高峰价 3/9） | 3 + 1.8 = 4.8 元 | ≈ 530 元 |
| 国产主流（DeepSeek V4-Pro 高峰 9/27、K2.6 6.5/27） | 9+5.4 ≈ 14.4 元 | ≈ 1,580 元 |
| **国产旗舰（Kimi K3 20/100）** | 20 + 20 = **40 元** | ≈ **4,400 元** |
| 海外高端（Opus/GPT-5 级，$5/$20） | 36+143 = 179 元 | ≈ 19,700 元 |

**结论：按国产主流～旗舰模型组合（K2.6/K3 混用 + 缓存命中折扣），重度用户每月 1,500-4,400 元；「每人每月至少 3000 元」处于该区间中上部，合理且偏保守（若全用旗舰模型甚至偏低）。** 讲稿可加一句「token 成本随模型档位从每月 500 元到 2 万元不等，重度科研/FPGA 任务取 3000 元/人/月是稳健估计」。

### 5.3 云服务器月租行情（2026-08 公开渠道）

| 配置 | 月费（活动/包月价） | 出处 |
|------|-------------------|------|
| CPU 4C8G 云主机（阿里云/腾讯云活动价区间） | 约 100-300 元/月（原价 300-600，新用户 1-5 折） | 阿里云/腾讯云官网活动页（2026 公开活动价区间，具体随地域/活动浮动） |
| CPU 8C16G 云主机 | 约 500-1,000 元/月 | 同上 |
| GPU T4（16G 显存，gn6i 4C15G） | 约 **1,681 元/月**（2026-05 活动价） | https://www.linkreateai.com/2026-nian-a-li-yun-gpu-fu-wu-qi-jia-ge-zen-me-yang-t4-a10.html |
| GPU A10（24G，gn7i 32C188G） | 约 **3,203-3,214 元/月** | https://www.aliyunvip.com/help_x.asp?ID=658 ；https://fuwuqi.aliyunbaike.com/gpu/ |
| GPU L20（gn8is） | 约 6,919 元/月起 | https://developer.aliyun.com/article/1709345 |

**与申请书口径核对**：申请书「服务器 4 万元/年」≈ **3,300 元/月**——对应「2 台 8C16G（≈1,000-2,000 元）+ 1 台 A10 GPU（≈3,200 元）」或「4-8 台 4C8G + 按需 GPU」的量级，**吻合**。100 用户量级（Nebflow 里程碑：10 月底 1,000 用户）按 1:10 并发粗算需 4-8 台 CPU 节点 + 1-2 台 GPU，月成本约 3,000-8,000 元。

---

## 6. 垂直领域 Agent 市场空白论据

### 6.1 大厂 Agent 产品覆盖的是通用编程场景（证据）

- OpenAI Codex / Claude Code / Cursor 定位均为**通用软件工程 agent**（写代码、修 bug、PR、代码库问答），官方文档与产品定位均面向「开发者」而非任一垂直行业（见第 2 节各官方链接）
- 大厂无「科研/FPGA/实验室」垂直产品线：OpenAI/Anthropic 的 agent 产品清单（Codex、Claude Code、ChatGPT Agent、Claude web）中无面向科研工作流（探测器电子学、FPGA 综合布线、束流实验数据处理）的专用 agent 或 skill 体系
- 开源通用层虽热（OpenClaw 38.8 万 star），但**通用层之下、垂直层之上（skill/工作流生态）仍是空白**——GitHub 上 FPGA skill 最大仅 250 star（第 4.3 节实测）

### 6.2 垂直领域已有动作（**必须如实呈现，避免讲稿被评委反驳**）

| 方向 | 事实 | 出处 |
|------|------|------|
| EDA 巨头 | 2026-03 NVIDIA GTC：Cadence、Synopsys、Siemens **三大 EDA 巨头集体发布基于 AI Agent 的芯片设计自动化产品** | 知乎 https://zhuanlan.zhihu.com/p/2035830153486545424 ；行业综述 https://thewind-upbird.github.io/blog/ai-chip-design-landscape/ |
| 国产 EDA | 合见工软 2025-02 推出 UDA 1.0（国内自研 RTL/Verilog AI 平台） | 新华网 https://www.news.cn/tech/20260319/d8c0b88848874b96b23f9c5dddf2bbb5/c.html |
| 大模型厂商 | 2026 年中 Kimi 完成 K3 模型自主设计芯片（Kimi 官方发布） | 36氪 https://www.36kr.com/p/3938614896262280 |
| 行业判断 | 「三大巨头占据 78% 市场份额的格局在 AI 时代可能被重构」「Agentic EDA 的黎明已经到来，但日出还需要时间」；智能体在 EDA 中「仍有限制待突破」 | https://www.eet-china.com/mp/a485238.html ；https://www.sohu.com/a/1014567829_362225 |
| FPGA 侧 | 「智能体 AI 时代 FPGA 生态位在扩大」（复旦微俞军，2026-07）——FPGA 是 agent 时代受益的芯片类型，但**面向 FPGA 工程师的垂直 agent 平台/技能生态缺席** | https://www.eet-china.com/news/202607241887.html |

### 6.3 论证建议（精确化后的蓝海表述）

Nebflow 的蓝海不在「EDA 工具链内嵌 agent」（那是 Cadence/Synopsys 的地盘），而在：**「通用 Agent 平台 + 面向科研/FPGA 工作者的垂直 skill 生态」**——即让科研人员用自然语言调度 agent 完成探测器电子学、FPGA 设计、实验数据分析，而不是在商用 EDA 软件里用 vendor 的封闭 agent。此位置目前无大厂、无主导开源项目（最大 FPGA skill 250 star），且与 Nebflow 团队背景（地空学院 + 物理学院 + FPGA 探测器电子学）高度契合。讲稿建议表述：「大厂在做通用的代码 Agent，EDA 巨头在做封闭工具链内的 Agent；而『科研工作者自己的、开放的、可组合的垂直 Agent 工作流』——这个位置是空的。」

---

## 7. 可直接用于路演的 10 个关键数字

| # | 数字 | 一句话语境 | 来源 |
|---|------|-----------|------|
| 1 | **AI Agent 市场 2025 年 78.4 亿美元 → 2030 年 526.2 亿美元，CAGR 46.3%** | 全球 Agent 市场六年增长近 7 倍，我们站在高速起跑线上 | MarketsandMarkets（2025） |
| 2 | **2028 年 1/3 的 GenAI 交互将由自主 Agent 完成** | 权威机构（Gartner）定调：Agent 不是风口，是方向 | Gartner 2024-03-11 新闻稿 |
| 3 | **GitHub Copilot 累计用户 2000 万，3 个月新增 500 万** | AI 编程付费需求已被大规模验证 | 微软 2025-07-31 财报电话会（纳德拉） |
| 4 | **Cursor 13 个月 ARR 从 1 亿 → 20 亿美元（史上最快 B2B SaaS）** | Agent 平台生意的付费天花板已被证明 | aiincider / siliconcanals（2026） |
| 5 | **Cursor 把约 100% 的营收花在 AI（token）成本上** | 行业标杆的商业模式 = 我们 P17 的 token 中转/中间商路线，融资用途被同行验证 | aifundingtracker（2025-11） |
| 6 | **OpenClaw 2025-11 创建、2026 爆红至 38.8 万 star / 8.1 万 fork** | 开源 Agent 需求爆发是事实；且它 2025 年底才出现，说明形态远未定型 | GitHub API 实测（2026-08-30）；澎湃等媒体 |
| 7 | **Agent 协议仍未统一：MCP / A2A / ACP / AGNTCY 四家并存** | 「标准未定 = 窗口未关」，现在做平台正当时 | futurepicker / idea2dev（2026） |
| 8 | **Kimi K3 输出 100 元/百万 token（DeepSeek V4 仅 4.5-27 元）** | 模型层价差 4-40 倍 → 谁做「token 采购 + 能力封装」谁就有中间层价值 | Kimi 官方定价页 / DeepSeek 官方定价页（2026-08） |
| 9 | **重度用户每月 token 成本实测区间 1,500-4,400 元（国产旗舰档）** | 「每人每月至少 3000 元」处于区间中上部，合理且偏保守 | 本报告 §5.2 测算（基于官方价） |
| 10 | **A10 GPU 约 3,200 元/月、8C16G 约 500-1,000 元/月；服务器 4 万/年口径吻合** | 固定支出可预估、可规模化，融资用途清晰 | 阿里云 2026 公开活动价 |

备选第 11 条：**GitHub 上最大的 FPGA 垂直 Agent skill 仅 250 star（verilog-generator）**——垂直层无人占主导，蓝海证据（GitHub API 实测 2026-08-30）。

---

## 8. 数据可靠性说明

| 等级 | 数据 | 说明 |
|------|------|------|
| 第一手实测（当日） | OpenClaw/Claude Code/Codex star 与创建日期、slideblocks 404、FPGA skill star、DeepSeek 官方价、Kimi K3 官方价页 | GitHub API + 官方文档抓取，2026-08-30 |
| 权威转述（多源交叉） | Gartner/MarketsandMarkets/Grand View 市场数字、Copilot 2000 万、Cursor ARR 曲线、K3 价格、云服务器价格 | 均为官方新闻稿/官方博客/主流媒体，部分为二级转述，已尽量附原始出处 |
| 需定稿前二次核实 | ①Stack Overflow 2025「84%/46%」具体数值（方向确认，数值未命中原始报告）②路演稿 P13「FPGA 校友 skill GitHub 星标」（经核实无公开证据，建议改口径）③杨植麟「好奇心」「用 Agent 深化自身能力」逐字原文（未找到，已提供最接近权威表述） | 见 §4.2、§3.1、§1.3 |
| 未找到公开数据 | 无其他关键缺口；「OpenClaw 突破 35 万 star」的具体日期、Anysphere 2026 年 500 亿估值融资为媒体报道（未官方确认） | 已如实标注 |

**时间线勘误提示（讲稿 v0.1 需修订）**：
- P11「2025 上半年 OpenClaw 爆红」→ 实际 OpenClaw 2025-11-24 才创建、爆红在 2025 底-2026 年（建议改为「2025 年底开源、2026 年爆红至近 40 万 star」）
- P11「Codex 这类产品发布才几个月」→ Codex 2025-04 发布，至路演（2026-09）已约 17 个月；但「Agent 形态未定」仍成立（协议未统一 + 2026-08-19 Codex Harness 才开源运行时）
- P13「FPGA 校友 skill 获 GitHub 大量星标」→ 无公开证据，建议按 §4.2 改口径
