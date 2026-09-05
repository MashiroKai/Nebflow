> 关联资产仍在 ~/.nebflow/docs/Nebflow/assets/（暂留）

# Nebflow Agent Benchmark 参赛方案

**日期**: 2026-08-16 · **性质**: 纯调研 + 方案（不改源码） · **决策摘要见 TL;DR**

---

## TL;DR

| 决策点 | 结论 |
|---|---|
| 第一站 | **Terminal-Bench 2.1**（Harbor harness，PR 提交，适配量最小、成本最低、agent/harness 独立署名） |
| 最佳匹配赛道 | **Gaia2 (ARE)**——Agent2Agent 维度是全生态唯一的多 agent 协作评测，与 Nebflow 的 Mail/Team/Flow 体系直接对应 |
| 曝光天花板 | **SWE-bench Verified**——成本最高（$1-3k 级），作为第三阶段 |
| 关键前置 | Nebflow 缺 headless 一次性执行入口：CLI `chat send` 发送的 `userMessage` 在 WS 处理器中**无对应 case**（链路断裂），且无"发送→等待完成→取回结果"的同步端点。需先开发 `nebflow run -p` + 同步 REST（约 1-2 周） |
| 总预算 | 全部三阶段 API 成本 **≈ $1,300 – $3,900**；先跑阶段 1 只需 **$50-300** |
| 多 agent 专属赛道 | **不存在权威的 multi-agent 编排 benchmark**（搜索仅见个人 repo 与媒体横评）。编排优势需借 Gaia2 A2A + Terminal-Bench 长任务体现——这本身是 Nebflow 的差异化叙事机会 |

---

## 1. 榜单对比矩阵

![Benchmark 对比矩阵](assets/benchmark_matrix.svg)

**评分说明**：1-5，越高越好。"适配容易度" 5 = 需要开发量最小；"成本友好" 5 = 越便宜。

| Benchmark | 活跃度 | 曝光度 | 编排匹配 | 适配容易度 | 成本友好 | 榜单 |
|---|---|---|---|---|---|---|
| SWE-bench Verified | 5 | 5 | 2 | 2 | 1 | [swebench.com](https://www.swebench.com/) |
| Terminal-Bench 2.1 | 5 | 4 | 3 | 4 | 4 | [tbench.ai/leaderboard](https://www.tbench.ai/leaderboard) |
| Terminal-Bench 3.0 | 5 | 4 | 3 | 4 | 3 | 同上 |
| Gaia2 (ARE) | 4 | 4 | 5 | 3 | 4 | [HF Space](https://huggingface.co/spaces/meta-agents-research-environments/leaderboard) |
| τ²-bench | 4 | 3 | 1 | 2 | 5 | [taubench.com](https://taubench.com/) |
| OSWorld 2.0/Verified | 4 | 4 | 1 | 1 | 3 | [osworld-v1.xlang.ai](https://osworld-v1.xlang.ai) |
| MLE-bench | 3 | 3 | 3 | 2 | 1 | [github.com/openai/mle-bench](https://github.com/openai/mle-bench) |
| WebArena | 3 | 3 | 1 | 1 | 3 | [webarena.ai](https://webarena.ai) |
| GAIA (legacy) | 2 | 3 | 2 | 3 | 4 | [HF Space](https://huggingface.co/spaces/gaia-benchmark/leaderboard) |
| ProgramBench (观察) | 3 | 3 | 2 | 2 | 2 | [swebench.com](https://www.swebench.com/) |

---

## 2. 生态盘点（2026-08 状态）

### 2.1 SWE-bench 家族 —— 最主流，接近饱和但仍是曝光之王

- **Verified 子集 500 instances**（人工校验），指标 = % Resolved（Docker 测试执行）。([swebench.com/verified.html](https://www.swebench.com/verified.html))
- **2026-08 榜单**（[steel.dev 镜像](https://leaderboard.steel.dev/leaderboards/swe-bench-verified/)，更新至 2026-05-28）：Claude Mythos 5 95.5% 居首；**国产模型第一梯队**：DeepSeek-V4-Pro-Max 80.6%、Kimi K2.6 80.2%、MiniMax M2.5 80.2%、GLM-5.2 80.0%、Qwen3.6 Plus 78.8%、GLM-5 77.8%（[docs.z.ai](https://docs.z.ai/guides/llm/glm-5)）。
- **饱和信号**：OpenAI 已官方停评 SWE-bench Verified（污染问题，[openai.com](https://openai.com/index/why-we-no-longer-evaluate-swe-bench-verified/)）；SWE-bench 团队 2026-05 转向 **ProgramBench**（从零写软件）、2025-11 推出 CodeClash（[swebench.com](https://www.swebench.com/)）。衍生榜：SWE-bench Pro（Scale SEAL harness，731 任务）、SWE-bench-Live（自动更新防污染）。
- **参加流程**：官方 harness（Docker，`SWE-bench/experiments` 流程）驱动 agent：输入 issue + repo → agent 产出 patch → 测试执行打分。提交按 [swebench.com/submit.html](https://www.swebench.com/submit.html) 指引 PR 到 [princeton-nlp/SWE-bench](https://github.com/princeton-nlp/SWE-bench)；开源 harness（OpenHands、mini-SWE-agent 等）有独立条目，团队复核标注 "Run performed or directly checked by the SWE-bench team"。无报名费。
- **成本参考**：单任务数万至数十万 token；500 任务全跑 frontier 模型约 **数千美元**（[benchmarkingagents.com/cost-per-eval](https://benchmarkingagents.com/cost-per-eval/)）；每解决一个 issue 的推理成本 $0.46–$74（模型间差 62x，[agentmarketcap.ai](https://agentmarketcap.ai/blog/2026/04/06/ai-agent-inference-cost-race-2026-swe-bench-token-efficiency)）。自托管开源模型只需 $5-30 GPU 时。

### 2.2 Terminal-Bench —— 增长最快，harness 生态最开放

- **版本格局**（[tbench.ai](https://www.tbench.ai/)）：3.0 已发布（shipped）、2.1 active（89-142 任务级）、2.0 active（89 任务）、1.0（80 任务）、TB Science 开发中、TB Challenges（单任务赛：Rust 编译器加速、WASM 渲染等）。Stanford × LAURE 合作。
- **2026-08 成绩**（TB 2.1，[codingfleet 镜像](https://codingfleet.com/blog/terminal-bench-leaderboard-2026/) 2026-08-14）：GPT-5.6 Sol 88.8 / Grok 4.6 88.4 / **GLM-5.3 88.2（开源最强，与闭源前沿打平**，[the-agent-report](https://the-agent-report.com/2026/08/glm-5-3-zai-post-training-coding-cyber/)）/ **DeepSeek V4 Pro 87.9 / Qwen3.8 Max 86.6**。GLM-5.2 为 81.0（[github.com/zai-org/GLM-5](https://github.com/zai-org/GLM-5)）。国产模型完全可打。
- **harness = Harbor**（Terminal-Bench 2.0 起官方 harness，[harborframework.com](https://www.harborframework.com/docs/tutorials/running-terminal-bench)）。Harbor 明确支持评估任意 agent（Claude Code、OpenHands、Codex CLI 等 installed agents 在容器内安装运行，[github.com/harbor-framework/harbor](https://github.com/harbor-framework/harbor)）——**这是 Nebflow 最现实的接入路径：把 headless CLI 装进 Docker 即可**。
- **评估**：每个任务在隔离 Docker 容器内跑 agent，end-state 验证测试打分，pass@1。
- **提交**：跑完 Harbor job → 整理 submissions → 填元数据 → **每个 submission 一个 PR**（[terminal-bench-2-1/leaderboard/SUBMIT.md](https://github.com/harbor-framework/terminal-bench-2-1/blob/main/leaderboard/SUBMIT.md)）→ 团队 review 后上榜；榜单条目标注 "Terminal-Bench team member ran the evaluation and verified"。无报名费。
- **成本**：89-142 任务 × 每任务约 10-50 万 token；国产 API 估算 **$50-300/全跑**。

### 2.3 Gaia2 / ARE (Meta+HF) —— 唯一的多 agent 协作赛道，Nebflow 最佳匹配

- **Gaia2**：800 场景 × 10 个模拟宇宙 × 11 个应用（邮件/日历/ChatsApp/购物/打车等），7 项能力等权：execution / search / adaptability / time / ambiguity（各 160）+ **Agent2Agent（160，与其他应用 agent 通信协作而非直接调 API）** + Noise。环境异步演化、事件驱动、含时间约束（[Gaia2 论文 arxiv 2602.11964](https://arxiv.org/abs/2602.11964)、[ARE 论文 arxiv 2509.17158](https://arxiv.org/abs/2509.17158)、[HF blog](https://huggingface.co/blog/gaia2)）。
- **原版 GAIA**（466 题，2023）榜单仍在 HF 运行但重心已迁 Gaia2——不推荐主投。
- **harness**：`uvx --from meta-agents-research-environments are-benchmark gaia2-run`，LiteLLM 集成支持任意模型；**ARE Agents API 支持自定义 agent**（[Agents API 文档](https://facebookresearch.github.io/meta-agents-research-environments/api_reference/agents.html)）——Nebflow 写一个 Python 适配器类即可接入，而不是只能用 ARE 内置 scaffold。
- **评估**：LLM judge + oracle events 在线校验；每场景强制 3 runs 算方差。
- **提交**：validation 集（800+320）上跑 `gaia2-run --hf_upload <org>/traces` 自动上传 traces 到 HF dataset → 在 [HF leaderboard Space](https://huggingface.co/spaces/meta-agents-research-environments/leaderboard) 填表提交。test 集私有。无报名费。([提交指南](https://facebookresearch.github.io/meta-agents-research-environments/user_guide/gaia2_evaluation.html))
- **成本**：模拟环境纯 API 调用，无 Docker 集群。Gaia2-mini（160 场景×3）估算 **$100-400**；全量 800×3 约 5 倍。

### 2.4 其余榜单（快速结论）

| 榜单 | 2026-08 状态 | 对 Nebflow 的判定 |
|---|---|---|
| **τ²-bench** ([sierra-research/tau2-bench](https://github.com/sierra-research/tau2-bench)) | active，2026-07 v1.0.1 重打分，τ³ 疑似在路上；pass^k 可靠性指标；框架 agent-agnostic 可注册自定义 agent（[DeepWiki](https://deepwiki.com/sierra-research/tau2-bench/4.5-agent-development)） | 单 agent 客服对话，无编排空间；榜单以模型为主。**可选不主投** |
| **OSWorld-Verified / 2.0** ([osworld-v1.xlang.ai](https://osworld-v1.xlang.ai)) | active；2.0 为 108 个长程 GUI 工作流（~318 tool calls/任务）（[steel.dev](https://leaderboard.steel.dev/leaderboards/osworld-2/)） | 需要 GUI 截图+鼠标键盘 agent 栈，Nebflow 无视觉操作工具。**不适配** |
| **MLE-bench** ([openai/mle-bench](https://github.com/openai/mle-bench)) | 维护中，75 个 Kaggle 比赛，Docker 长时训练 | 长程 ML 任务匹配 Manager 拆解叙事，但 GPU/时长成本高。**观察** |
| **WebArena** | 812 任务自托管网站（[steel.dev](https://leaderboard.steel.dev/leaderboards/webarena/) 2026-06 更新）；热度被 OSWorld/BrowseComp 分流 | 需浏览器自动化栈。**不适配** |
| **AgentBench** | 2026 生态参考页仍在（[benchmarkingagents.com](https://benchmarkingagents.com/agent-benchmarks/)）但边缘化 | **不投** |
| **ProgramBench / CodeClash / TB Science** | 新兴（SWE-bench 团队 2026-05 / TB 团队开发中） | **观察名单**——冷启动期上榜竞争小 |

### 2.5 多 agent 编排类 benchmark：不存在

搜索 2026 年生态：多 agent 相关结果均为**框架横评**（LangGraph vs AutoGen vs CrewAI，如 [agentmarketcap.ai](https://agentmarketcap.ai/blog/2026/04/11/langgraph-autogen-crewai-dspy-multi-agent-orchestration-2026)）或个人 repo（[rachitpareek/multi-agent-orchestration-evals](https://github.com/rachitpareek/multi-agent-orchestration-evals)），**无权威榜单**。最接近的官方评测是 **Gaia2 Agent2Agent 维度** 与 **OSWorld 2.0 长程工作流**（后者不适配）。

> **含义**：Nebflow 的编排能力没有现成计分赛道。策略 = ①在 Gaia2 A2A 打"协作"标签；②在 Terminal-Bench/SWE-bench 用"Nebula Manager + Coder + QA check-fix"的 team 模式跑单 agent 榜，把故事讲在 harness 层（榜单允许 harness/framework 独立署名）。

### 2.6 国产模型跑分预期（Nebflow 底层模型）

| 模型 | SWE-bench Verified | Terminal-Bench 2.1 | 来源 |
|---|---|---|---|
| GLM-5.3（2026-08 新） | — | **88.2**（开源第一，打平闭源前沿） | [the-agent-report](https://the-agent-report.com/2026/08/glm-5-3-zai-post-training-coding-cyber/) |
| GLM-5.2 | 80.0% | 81.0 | [zai-org/GLM-5](https://github.com/zai-org/GLM-5)、[steel.dev](https://leaderboard.steel.dev/leaderboards/swe-bench-verified/) |
| GLM-5 | 77.8% | — | [docs.z.ai](https://docs.z.ai/guides/llm/glm-5) |
| DeepSeek-V4-Pro(-Max/0813) | 80.6% | 87.9 | [steel.dev](https://leaderboard.steel.dev/leaderboards/swe-bench-verified/)、[codingfleet](https://codingfleet.com/blog/terminal-bench-leaderboard-2026/) |
| DeepSeek-V4-Flash-0731 | 79.0%(Flash-Max) | 82.7（$0.14/$0.28 per M tokens） | [felloai](https://felloai.com/deepseek-v4/) |
| Kimi K2.6 | 80.2% | — | [steel.dev](https://leaderboard.steel.dev/leaderboards/swe-bench-verified/) |
| Qwen3.6/3.8 Plus/Max | 78.8% | 86.6 | 同上 |

**结论**：国产模型（尤其 GLM-5.2/5.3、DeepSeek-V4）在两大主流榜已进入 80+ 第一梯队。Nebflow 用 GLM-5.x 跑 Terminal-Bench 2.1 的合理预期为 **75-85 分区间**（模型分减去 harness 适配损耗），足以进入榜单开源 harness 前列；SWE-bench Verified 预期 **65-75%**。

---

## 3. Nebflow 适配缺口分析（源码级）

### 3.1 现有驱动接口盘点

| 接口 | 位置 | 能力 | 缺口 |
|---|---|---|---|
| WS `/ws` + `immediateInput` | `gateway/WebSocketRoutes.scala:868` | 按 sessionId 注入用户消息（`AgentCommand.ImmediateInput`），异步 | 无完成通知协议约定；需 WS 客户端订阅 |
| `POST /api/command` | `gateway/RestApiRoutes.scala:74` | REST 镜像 WS 消息，**只捕获第一条响应**即返回 | 长任务流式/完成语义不适用 |
| `POST /api/callbacks/inject` | `gateway/WebSocketRoutes.scala:3794` | `{"agent","session","message"}` → `AgentCommand.ExternalEvent` 异步注入，立即返回 ok | **不等待 agent 完成、不返回 agent 回复** |
| `GET /api/sessions/:id/history` | `gateway/RestApiRoutes.scala:105` | 拉取会话消息 | 可轮询判完成，但无权威"回合结束"标记 |
| CLI `nebflow chat "q"` | `cli/ChatCommands.scala:49` | 发送 `{"type":"userMessage"}` | **该 type 在 `WebSocketRoutes.handleMessage` 中无处理 case（全文仅出现在发送方）——single-shot 链路当前断裂** |

### 3.2 结论：Headless 缺口是真实的，但比想象小

架构是 gateway 常驻 + WS/REST 驱动，agent 执行内核（AgentActor、工具、Team/Mail）与 UI 无耦合。缺的只是三件事：

1. **同步回合端点**：`POST /api/sessions/:id/turn` —— 发消息 → 阻塞至回合完成 → 返回最终 assistant 消息 + 工具轨迹摘要（超时可配，如 30 min）。
2. **一次性 CLI**：`nebflow run -p "<task>" [--agent X] [--session Y] [--json] [--timeout 1800]` —— 自动拉起 gateway（`ProcessManager` 已有进程管理）→ 调 turn 端点 → stdout 输出 → 退出码 0/1。这是 Harbor installed-agent 接入的全部要求。
3. **确定性模式**：环境变量/配置开关——禁 memory 注入、禁 AskUser（自动选默认）、safety 全放行、English 输出、禁 telemetry——保证 500 instance 批跑无人值守。

### 3.3 各 harness 对 Nebflow 的接口要求

| Harness | 要求的 agent 接口 | Nebflow 适配层 |
|---|---|---|
| Harbor (Terminal-Bench) | installed agent：容器内 CLI，接收 task 描述（文件/stdin），产出终端侧结果 | `nebflow run -p "$(cat task.md)"` 即可 + Dockerfile 安装 JRE+JAR。**零 Python 适配** |
| ARE (Gaia2) | Python custom agent 类（Agents API），与 ARE 工具/API 消息循环交互 | ~200-400 行 Python adapter：ARE 消息 → `POST /turn` → 回传。LLM 由 Nebflow 自己管（ARE 允许自定义 agent） |
| SWE-bench | agent 拿 issue+repo → 产出 git patch（OpenHands/mini-swe-agent 模式，Docker 内跑） | 复用 `nebflow run`：容器内 checkout repo → Nebula/Coder team 干活 → harness 收集 `git diff`。可选提交 Nebflow 官方 adapter repo |
| τ²-bench | 注册自定义对话 agent 类（Python） | 类似 ARE adapter，但无编排增益，列为可选 |

---

## 4. 推荐路线（分阶段）

![分阶段路线图](assets/benchmark_roadmap.svg)

### 阶段 0（W1-W2）：基础设施 —— headless 层（必做，全部后续依赖）

`nebflow run -p` + `POST /api/sessions/:id/turn` + 确定性模式开关。验收：容器内 `nebflow run -p "list files" --json` 无人工干预返回结果。

### 阶段 1（W2-W3）：Terminal-Bench 2.1 —— 最小成本首秀 ⭐ 推荐第一站

- **为什么先上**：适配量最小（CLI 装容器即可，无 Python adapter）；任务量小（89）；成本 $50-300；榜单 2026-08 仍在密集收条目；**harness/framework 独立署名** = "Nebflow (GLM-5.x)" 作为一行品牌曝光。
- **打法**：配置一个 `terminal-solver` agent（Bash/Read/Edit/Grep 工具白名单 + 精简 system prompt），先用 GLM-5.2 或 DeepSeek-V4（便宜）跑；若 GLM-5.3 开放 API 则冲高配行。
- **里程碑**：20-task pilot（校准 prompt/超时）→ 89-task 全跑 → [PR 提交](https://github.com/harbor-framework/terminal-bench-2-1/blob/main/leaderboard/SUBMIT.md)。
- **合理预期**：开源 harness 前列；若 GLM-5.3 可用可望 80+。

### 阶段 2（W3-W6）：Gaia2 —— 编排优势主战场 ⭐ 最佳匹配

- **为什么**：Agent2Agent（160 场景）是全生态唯一官方多 agent 协作评测；time/adaptability 维度（异步事件、3 分钟超时改约、环境突变）恰好考验**事件驱动编排**——Nebflow 的 Mail 队列、Team 常驻、Scheduler 是现成机制；Meta+HF 榜单 + 论文引用价值；成本可控（mini $100-400）。
- **打法**：Python ARE adapter 把 ARE 的消息/工具循环桥到 Nebflow（agent = Nebula，应用 agent 交互走 Mail 语义映射）；先 mini（160×3）再决定是否全量 800。
- **里程碑**：adapter 开发（W3-4）→ mini 提交 HF（W5）→ 全量（W6-7，可选）。

### 阶段 3（W5-W9）：SWE-bench Verified —— 曝光天花板（可选，视前两阶段结果）

- **为什么最后**：成本最高（全跑 $1-3k+，含重试）；OpenAI 已弃评（污染）导致叙事边际减弱；但社区关注度仍是第一。
- **打法**：Nebula(Manager) + Coder + QA 的 team 模式跑单 instance issue→patch；mini-SWE-agent（100 行，65%）证明 scaffold 简单性不是门槛，差异在 team check-fix loop。
- **里程碑**：20-instance pilot（W7）→ 500 全跑（W8-9）→ [PR 提交](https://www.swebench.com/submit.html)。

### 明确不投：OSWorld / WebArena（无 GUI/浏览器栈）、AgentBench（边缘化）、GAIA legacy（被 Gaia2 取代）。

---

## 5. 适配开发清单

### P0 — Headless 基础（阶段 0，预估 5-8 个工作日）

| # | 事项 | 涉及位置（参考现有架构） | 估时 |
|---|---|---|---|
| 1 | `POST /api/sessions/:id/turn` 同步回合端点（阻塞至完成，返回最终消息+轨迹） | `gateway/RestApiRoutes.scala`（新 route）+ AgentActor 完成事件桥（InteractionHub 已有 RegisterRoot 机制可复用） | 2-3d |
| 2 | `nebflow run -p "<task>"` CLI 一次性执行（--agent/--session/--json/--timeout） | `cli/` 新增 RunCommand + `GatewayClient`；自动拉起 gateway（`ProcessManager`） | 2d |
| 3 | 修复/替换 CLI `chat send` 的 `userMessage` 断链（发送方 `cli/ChatCommands.scala:49`，接收端无 case） | `WebSocketRoutes.handleMessage` 或统一走 turn 端点 | 0.5d |
| 4 | 确定性模式：`NEBFLOW_HEADLESS=1`（禁 memory/AskUser 自动默认/safety 放行/English/禁 telemetry） | `ContextRefresher`（memory 注入开关）、`ask/`、safety mode、`PromptSections` | 1-2d |

### P1 — Terminal-Bench 接入（阶段 1，预估 3-5 个工作日）

| # | 事项 | 说明 |
|---|---|---|
| 5 | `terminal-solver` agent 定义（agent.json + system.md） | 工具白名单 Bash/Read/Edit/Write/Grep/Glob；无 Pop/Card 等 GUI 工具 |
| 6 | Docker 镜像：JRE + Nebflow JAR + 启动脚本 | Harbor installed-agent 规范；预配置 API key 走环境变量 |
| 7 | Harbor 运行配置 + 20-task pilot 脚本 | `harbor run terminal-bench-2.1 --agent nebflow`；超时/重试参数校准 |
| 8 | 全量 89 任务跑批 + 结果整理 + PR | 按 [SUBMIT.md](https://github.com/harbor-framework/terminal-bench-2-1/blob/main/leaderboard/SUBMIT.md) 格式 |

### P2 — Gaia2/ARE adapter（阶段 2，预估 5-8 个工作日）

| # | 事项 | 说明 |
|---|---|---|
| 9 | ARE custom agent（Python）：消息循环 ↔ `POST /turn` 桥接 | 遵循 [ARE Agents API](https://facebookresearch.github.io/meta-agents-research-environments/api_reference/agents.html)；LLM key 由 Nebflow 配置管理 |
| 10 | A2A 策略：把 ARE 应用 agent 映射为 Nebflow Mail 成员语义 | 在 Nebula 的 system prompt 注入协作协议说明 |
| 11 | `gaia2-run --config mini`（160×3）+ traces 上传 + 榜单表单提交 | mini 结果达标（参考榜首区间）再全量 |

### P3 — SWE-bench（阶段 3，预估 5-7 个工作日）

| # | 事项 | 说明 |
|---|---|---|
| 12 | SWE runner：per-instance 容器内 checkout → `nebflow run -p <issue>` → `git diff` 收集 | 参考 mini-SWE-agent / OpenHands 的 experiments 流程（[SWE-bench/experiments](https://github.com/princeton-nlp/SWE-bench)） |
| 13 | swe-team 实体：Manager/Coder/QA check-fix loop 配置 | issue→patch 单人也可，team 模式是叙事差异点 |
| 14 | 官方评估 harness 跑分（`swebench.harness.run_evaluation`）+ 20-instance pilot | 验证 patch 格式/重测通过 |
| 15 | 500 全量 + PR（含 cost/token 报告，社区喜欢透明数据） | [swebench.com/submit.html](https://www.swebench.com/submit.html) |

---

## 6. 预算

| 阶段 | 规模 | Token 估算 | API 成本（国产模型） | 说明 |
|---|---|---|---|---|
| P1 Terminal-Bench 2.1 | 89 任务 | 9-45M | **$50-300** | GLM-5.2 / DeepSeek-V4-Flash（$0.14/$0.28/M）便宜；pilot+全跑+一次重跑 |
| P2 Gaia2 mini | 160×3 场景 | 30-80M | **$100-400** | 纯 API 模拟环境；3 runs 是榜单要求 |
| P2 Gaia2 全量（可选） | 800×3 场景 | 150-400M | $400-1,500 | 视 mini 结果决定 |
| P3 SWE-bench Verified | 500 实例 | 250-600M | **$800-2,500** | 含 pilot+失败重试；frontier 模型对照跑则另计（数千刀，不建议） |
| **合计（全做）** | — | — | **≈ $1,350 – $4,200** | 阶段 1 单独做仅 $50-300 |

**模型策略**：主力 GLM-5.2/5.3（Terminal-Bench 2.1 开源第一的血统 + z.ai API）；降本备选 DeepSeek-V4-Flash-0731（TB 2.1 82.7 分、$0.14/$0.28 per M）；SWE-bench 可用 DeepSeek-V4-Pro（SWE-V 80.6）。Nebflow 的 provider 链（preferred+fallbacks）正好做模型故障降级。

**时间总账**：W1-W2 基建 → W3 TB 上榜 → W6 Gaia2 mini 上榜 → W9 SWE 上榜 → 全程 9-10 周，开发投入约 18-28 人日。

---

## 7. 关键来源汇总

- SWE-bench 官方/榜单/提交: [swebench.com](https://www.swebench.com/) · [Verified 榜(steel 镜像)](https://leaderboard.steel.dev/leaderboards/swe-bench-verified/) · [OpenAI 停评声明](https://openai.com/index/why-we-no-longer-evaluate-swe-bench-verified/) · [提交指南](https://www.swebench.com/submit.html)
- Terminal-Bench/Harbor: [tbench.ai](https://www.tbench.ai/) · [harbor-framework/harbor](https://github.com/harbor-framework/harbor) · [TB2.1 提交](https://github.com/harbor-framework/terminal-bench-2-1/blob/main/leaderboard/SUBMIT.md) · [TB 2.1 榜(2026-08-14)](https://codingfleet.com/blog/terminal-bench-leaderboard-2026/)
- Gaia2/ARE: [评估+提交指南](https://facebookresearch.github.io/meta-agents-research-environments/user_guide/gaia2_evaluation.html) · [Agents API](https://facebookresearch.github.io/meta-agents-research-environments/api_reference/agents.html) · [HF 榜](https://huggingface.co/spaces/meta-agents-research-environments/leaderboard) · [Gaia2 论文](https://arxiv.org/abs/2602.11964) · [ARE 论文](https://arxiv.org/abs/2509.17158)
- τ²-bench: [repo](https://github.com/sierra-research/tau2-bench) · [taubench.com](https://taubench.com/) · [论文](https://arxiv.org/abs/2506.07982)
- OSWorld: [榜单](https://osworld-v1.xlang.ai) · [OSWorld 2.0(steel 镜像)](https://leaderboard.steel.dev/leaderboards/osworld-2/)
- 成本: [cost-per-eval](https://benchmarkingagents.com/cost-per-eval/) · [inference cost race](https://agentmarketcap.ai/blog/2026/04/06/ai-agent-inference-cost-race-2026-swe-bench-token-efficiency)
- 国产模型: [GLM-5.2 TB/SWE 成绩](https://github.com/zai-org/GLM-5) · [GLM-5.3](https://the-agent-report.com/2026/08/glm-5-3-zai-post-training-coding-cyber/) · [DeepSeek-V4-Flash-0731](https://felloai.com/deepseek-v4/)
- Nebflow 源码（本地）: `gateway/RestApiRoutes.scala` · `gateway/WebSocketRoutes.scala` (immediateInput:868, handleInject:3794) · `cli/ChatCommands.scala` · `cli/ProcessManager.scala`

---

*本方案基于 2026-08-16 检索快照。榜单分数变动快（GLM-5.3 2026-08 刚发布），执行前建议复核各榜单一周内更新。*
