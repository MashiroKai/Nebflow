# Anthropic MHS（Model Hardware Standard）协议深度调研报告

> 调研日期：2026-09-01 ｜ 调研方式：WebSearch/WebFetch 多来源交叉验证（官方公告 + 一手机构新闻 + 独立技术分析）
> 状态说明：MHS 目前处于 **research preview（研究预览）阶段，官方尚未公开完整规范文档**。本报告将「官方已确认」「第三方分析」「待确认」三类信息严格区分，凡未经官方或可靠来源确认的内容一律标注「待确认」。

---

## 目录

1. [摘要](#1-摘要)
2. [定位与背景](#2-定位与背景)
3. [核心概念与实体](#3-核心概念与实体)
4. [协议结构与工作机制](#4-协议结构与工作机制)
5. [设备发现与连接](#5-设备发现与连接)
6. [权限与安全模型](#6-权限与安全模型)
7. [与 MCP 及其他标准的关系](#7-与-mcp-及其他标准的关系)
8. [工作流示例：agent 操控设备的完整链路](#8-工作流示例agent-操控设备的完整链路)
9. [参考实现与生态](#9-参考实现与生态)
10. [对 Nebflow 的参考价值](#10-对-nebflow-的参考价值)
11. [待确认事项清单](#11-待确认事项清单)
12. [来源清单](#12-来源清单)

---

## 1. 摘要

**MHS（Model Hardware Standard，模型硬件标准）** 是 Anthropic 于 **2026-08-27** 以研究预览（research preview）形式发布的开放规范，目标是为 AI agent 提供**安全操作物理设备**（实验室仪器、机器人、制造设备、量子计算机硬件等）的统一接口层。它与 MCP（Model Context Protocol）是姊妹关系：MCP 是 agent 与软件工具/数据之间的标准，MHS 是 agent 与物理设备之间的标准，且 **MHS 构建在 MCP 之上（MCP 是三种访问路径之一）**，而非与 MCP 竞争。

核心机制可概括为四件事：

| 机制 | 作用 |
|---|---|
| **标准化驱动（MHS driver）** | 每种设备一个 driver，把厂商私有的编程接口映射为极简原语集（`read`/`write` + 发现），消除"每种设备单独写翻译器"的集成爆炸 |
| **自然语言标签（tags）→ 参考文件（reference file）** | 把纸面手册/老师傅脑中的隐性知识（重量、扭矩极限、校准怪癖）以自然语言写入 driver，自动编译为 agent 可推理的设备参考文件 |
| **标准格式发现（discovery）** | 设备以统一格式在网络中可被发现，agent 与设备互相找到对方，无需专属翻译程序 |
| **三层控制路径** | 通过 **MCP**、**命令行 CLI**、**代码文件（code/API）** 三种方式操控设备，适应交互式探索与确定性批量执行两种模式 |

**关键事实**：MHS 起源于 Anthropic（Beneficial Deployments 团队 Alek Kemeny）与 HHMI Janelia 研究园区博士后 Arco Bast 的合作——Bast 为协调显微镜部件（激光/相机/电动对焦器）发明了**共享内存字典**，让异构仪器在内存速度下直接读写同一份状态。2026-02 双方会面后开始合作，最终发展为 MHS。截至调研日（2026-09-01），MHS **尚无公开 spec、无开源仓库、无版本号**，访问需申请（gated research preview），Anthropic 承诺预览期后开源。

---

## 2. 定位与背景

### 2.1 解决什么问题

现代实验室/工厂的设备集成是物理 AI 的最大瓶颈之一：

- 每种设备有自己的 SDK、语言、GUI、串口协议、文件掉落接口，甚至只有 Windows 老式界面
- 连接多台设备需要专家逐对编写专属"翻译器"（bespoke translator），通常耗时**数周到数月**
- 设备之间没有共同语言，agent 无法发现设备、无法理解设备能力、更无法安全操作

MHS 的目标是：**每个设备描述并接入一次，之后通过统一接口被任意 agent 和 workflow 复用**，把集成时间从数周/数月压缩到数小时/数分钟。

### 2.2 起源故事（HHMI Janelia 合作）

一手来源：[HHMI 新闻](https://www.hhmi.org/news/how-one-postdocs-problem-solving-changing-way-scientists-work)：

- Arco Bast（Janelia 博士后，Spruston Lab）研究神经元通信，需要自定义显微镜（mesoscope）跟踪神经元活动
- mesoscope 的相机、扫描仪、传感器由不同厂商、不同语言、各自私有内存的软件控制，协调它们做新实验要数月定制工程
- Bast 的突破：**共享内存池**——各组件不再通过操作系统转发消息，而是直接读写同一份实时数据。他用 Claude Code 实现了这个应用，把设置时间从数月降到数天
- 2026 年 2 月，Bast 与 Anthropic 工程师（Alek Kemeny，Beneficial Deployments 团队）在 Janelia 会面，发现该工作与 Anthropic 正在探索的机器学习方向互补，随即开始合作
- MHS 由此诞生；Janelia 科学家开始测试"AI-in-the-Loop"实验（AI 监控数据、实时调整实验参数）

### 2.3 发布时间线与当前版本状态

| 时间 | 事件 |
|---|---|
| 2026-02 | Anthropic 与 Janelia 会面，确立合作 |
| 2026-08-27 | 官方公告「Previewing the Model Hardware Standard」发布，research preview 开放申请（[官方公告](https://www.anthropic.com/news/model-hardware-standard-research-preview)、[官方站点](https://modelhardwarestandard.com)） |
| 2026-08-27~ | 生态反应：CNBC、Ars Technica、The Decoder 等独立报道；Hugging Face（LeRobot）、Raspberry Pi、AWS（Strands Robots）等宣布支持 |
| 2026-09-01（今日） | **无版本演进、无 spec 发布、无开源**。所有来源确认仍停留在 8-27 research preview 状态 |
| 未来（无日期） | Anthropic 承诺：预览期构建安全评估与最佳实践后**开源**，并发布 physical safety roadmap 与部署指南 |

**重要**：用户提到「刚发布」准确无误。截至今日没有任何版本演进或新发布。部分中文媒体（如智脑时代）误写"8 月 31 日发布"，实际为 8 月 27 日。

---

## 3. 核心概念与实体

> 说明：以下概念为官方公告 + 官方学习页明确描述；官方尚未给出正式的 schema/类型定义（如 JSON Schema），实体字段结构为「待确认」。

### 3.1 MHS driver（标准化驱动）

官方定义（[官方公告](https://www.anthropic.com/news/model-hardware-standard-research-preview)）：driver 是**翻译计算机操作系统与硬件设备之间的软件**，是 MHS 的"整个把戏"（PacketNebula 语：一整层就是一个 driver 契约）。

- 每种设备一个 driver，负责处理厂商 API/SDK/CLI/文件交换/其他可编程接口的细节
- 对外暴露**极简原语集**，让"一台离心机、一台显微镜、一台工业机械臂"共用同一 driver 格式
- driver 中携带**安全边界**——这是设计上最关键的一点：限制编译进 driver，作用于**所有调用方**（包括未来不可预见的 agent），而不是放在某个 agent 的 system prompt 里（prompt 级安全随 harness 切换即失效）

### 3.2 原语（primitives）：read / write

官方确认的原语只有两个 + 发现能力：

| 原语 | 含义 | 官方示例 |
|---|---|---|
| `read` | 从设备读取值 | "get temperature"（读温度） |
| `write` | 向设备写入值 | "set temperature"（设温度） |
| discovery | 设备以标准格式在网络中被发现 | 设备与 agent 跨网络互相找到 |

派生应用（官方示例）：读取板位、激光频率、传感器值、相机流、液体体积、运行协议状态；写入温度、曝光、流速、目标位置。

> **注意**：第三方分析（Essa Mamdani）提出过更完整的 5 原语模型——`read`/`write`/`describe`（发布能力）/`observe`（流式状态、故障、完成事件）/`stop`（急停语义）——但这**不是官方公布的原语列表**，属作者基于公开描述的合理推断，仅作参考。官方文本只确认了 read/write + discovery。

### 3.3 自然语言标签（tags）与参考文件（reference file）

这是 MHS 最有区分度的设计（官方公告确认）：

- **问题**：设备的大量安全相关信息（机械臂的重量、扭矩极限、校准怪癖、碰撞包络）不在代码里，而在纸质手册、某台电脑上、或操作员脑中（tacit knowledge）
- **tags**：driver 内含标签，允许用户**直接用自然语言**写入这些信息——可以自己写，也可以"通过对话让 agent 采访自己"来生成
- **reference file**：driver 根据 tags 自动生成设备的参考文件，包含：
  - 设备能测量什么（what it can measure）
  - 什么可调整（what can be adjusted）
  - 将强制执行的**安全限制**（what safety limits will be enforced）
  - 物理特性（如机械臂重量——对安全操作至关重要，代码本身无法体现）
- agent 在发送任何 write 之前先读参考文件，即可操作**从未见过的设备**

### 3.4 设备（device）

官方没有给 device 一个正式抽象定义，但从全部公开材料可归纳其语义：**任何有可编程接口（programmable interface）的物理设备**。官方明示适用对象：

- 显微镜（microscope）、移液机器人（liquid handler/pipette robot）、机械臂（robot arm）、相机（camera）、离心机（centrifuge）、分光光度计（spectrometer）、培养箱（incubator）、板式读取器（plate reader）、激光系统（laser system）等
- 明确**不适用**：无编程接口的设备（官方承认此类设备是当前限制，正与厂商合作内置 MHS driver）

### 3.5 共享内存状态字典（shared-memory state dictionary）

源自 Janelia 原型（[HHMI 新闻](https://www.hhmi.org/news/how-one-postdocs-problem-solving-changing-way-scientists-work)、[XenoSpectrum 转述](https://xenospectrum.com/en/anthropic-mhs-physical-ai-standard/)）：

- 整台 rig 的状态存放在**共享内存中的标准化字典**
- MATLAB 的探测器、Python 的相机、C# 的测量系统都能从同一格式读取当前值
- 之前每个设备要重写显示和分析代码，现在可按数据类型复用组件
- 这使 agent 能够**实时观察实验数据、注意到模式、在实验进行中调整参数**（AI-in-the-Loop）

---

## 4. 协议结构与工作机制

### 4.1 分层架构

综合官方描述与第三方分析（[Kingy.ai 分层表](https://kingy.ai/blog/anthropic-model-hardware-standard-mhs/)、[Essa Mamdani 架构图](https://essamamdani.com/blog/anthropic-model-hardware-standard-guide-2026)），MHS 生态是一个六层栈：

| 层 | 角色 | 公开证据状态 |
|---|---|---|
| **物理设备**（Physical equipment） | 通过原生接口执行测量/运动 | 多方 partner 演示；硬件支持逐案而异 |
| **MHS driver + 参考数据** | 以通用形式暴露状态、流程、特性、命令、声明限制 | 官方文字描述；**无公开 schema/conformance suite** |
| **访问机制**（Access mechanism） | 让软件/agent 调用 driver | 官方确认三种：MCP、CLI、code/API |
| **Agent 编排**（Agent orchestration） | 规划步骤、监控结果、调整参数、协调多设备 | 所有发布案例均为 Claude/Claude Code |
| **确定性执行**（Deterministic execution） | 以机器速度运行学习到的序列，无需逐步骤模型推理 | QuEra 测试床最强；非所有设备类别通用 |
| **人机安全控制**（Human & hardware controls） | 约束危险动作、保留专家权威 | 各 pilot 有演示；无跨平台安全认证 |

### 4.2 三种控制路径（官方确认）

| 路径 | 定位 | 适用场景 |
|---|---|---|
| **MCP** | 通过 MCP 把设备暴露为 agent 的 tool | agent 交互式探索、与既有 MCP 生态无缝衔接；MHS 模型无关的关键——任何支持 MCP 的 harness 都能访问设备 |
| **命令行 CLI** | 人类与 agent 用相同命令检查设备 | 交互式控制、快速操作、人机同界面 |
| **代码文件（code/API）** | 把多个 driver 命令链成确定性脚本 | 长时任务、或需快于 agent 在线推理速度的操作；设备自主执行，agent 只做高层监督 |

**关键设计**：agent 在**意图层**工作（决定做什么实验、什么顺序、如何响应结果），driver 和代码文件在**精确、快速、可重复的执行层**工作，共享内存状态字典保持 agent 对设备状态的认知实时更新。

### 4.3 消息格式与传输（待确认）

**必须如实说明**：MHS 官方**未公开**消息格式规范。可确认的信息边界如下：

- ✅ 官方确认：MHS 可通过 **MCP** 访问，而 MCP 本身的传输基于 **JSON-RPC 2.0**（agent 与 MCP server 之间的标准消息封装，含 `initialize`/`tools/list`/`tools/call` 等方法的生命周期与 JSON 结构）
- ✅ 官方确认：MHS 也可通过 CLI 与 code 文件访问
- ❌ **待确认**：MHS driver 与设备之间的线缆协议格式（是否 JSON-RPC、是否有独立的消息 schema、session 如何建立/销毁）——官方未发布任何 spec 文本
- ❌ **待确认**：`read`/`write` 原语是否有标准化的参数结构（如 `{"device": "liquid_handler_1", "parameter": "dispense_volume_ul", "value": 200}`）——第三方浏览器模拟器（[kdpisda.in 模拟控制台](https://kdpisda.in/anthropic-model-hardware-standard-mhs/)）基于 Anthropic 描述构建了此形态的交互模拟，但**这是社区推断，非官方 schema**

### 4.4 Capability 描述与协商（部分待确认）

- ✅ 官方确认：设备以**标准格式可发现**，agent 可读取设备的「能测量什么、能调整什么、安全限制」——这就是能力描述层
- ✅ 官方确认：参考文件（reference file）是能力与限制的载体，agent 在操作前读取
- ❌ **待确认**：是否存在类似 MCP `initialize` 握手 + `capabilities` 协商的正式流程；多 agent 同时发现同一设备时如何仲裁；能力描述的机器可读格式（JSON? YAML?）

### 4.5 官方学习页的机制归纳

[官方学习页 what-is-mhs](https://model-hardware-standard.com/learn/what-is-mhs) 归纳 MHS 如何工作：

1. **标准化驱动**：把厂商接口映射为 read/write 等简单原语
2. **可发现清单（discoverable manifests）**：描述设备状态、流程、传感器、网络位置
3. **物理上下文**：记录代码无法完全解释的重量、能力、单位、安全限制
4. **多重控制路径**：通过 MCP、CLI 或代码 API 连接 agent

---

## 5. 设备发现与连接

### 5.1 发现机制（官方描述）

- 每个设备通过 MHS driver 以**标准格式可发现**（discoverable in a standard format）
- 设备与 agent 可**跨网络互相找到并通信**，无需中间专属翻译程序
- 发现的描述必须携带足够上下文：仅名字不够，**身份（identity）、能力（capabilities）、状态（state）、限制（limits）需要一起传递**（MHSBase 归纳）

### 5.2 连接与接入流程（MHSBase 归纳的 8 步实用流程）

> 来源：[MHSBase](https://mhsbase.com/what-is-mhs)——独立社区资源，明确标注"这是对公开方向的实用解释，不是官方强制配方"

1. **连接设备**：识别可编程接口，构建或适配 driver
2. **描述设备**：记录能力、状态、操作、单位、相关特性
3. **设置边界**：检查权限、安全限制、审批点、急停行为、日志
4. **发现设备**：让 agent/应用通过共享描述找到设备
5. **读取状态**：运行前与运行中观察测量值、就绪度、进度、数据流
6. **请求操作**：以显式输入和预期结果调用被允许的动作
7. **协调设备**：排序仪器、等待完成信号、把结果传给下一步
8. **验证结果**：对比实际与预期，必要时改进集成

### 5.3 driver/适配层角色

- driver 是**适配器**：理解特定仪器的可编程接口，把厂商特定命令映射为少量可预测操作
- 一个 agent 只需实现一次标准，就能发现并操作任何符合规范的仪器——集成负担从「agent 数 × 设备数」降为「每方各实现一次 spec」
- 有人仍需为每种硬件写 driver，但 driver 写一次可复用，不再为每个「设备 × 控制器」组合重建（The Decoder 确认）

---

## 6. 权限与安全模型

### 6.1 安全设计总原则（官方确认）

MHS 把安全视为中心，因为**失败模式是物理的**（错误答案只是麻烦，错误操作可能毁掉样本、仪器或人）。官方确认的防护层级：

| 层级 | 机制 |
|---|---|
| **Driver 级强制限制** | 设备可拒绝超出安全操作范围的命令——**无论 agent 请求什么**。厂商可在 MHS 文件中规定 AI 如何安全移动重臂（限制速度与角度），该限制对所有调用方生效 |
| **自动错误恢复** | 系统设计为无需人工介入即可从硬件错误中恢复（如 QuEra 激光 relock） |
| **运行前安全校验** | 自动化运行前验证条件，阻止危险状态（CMU 演示：6 种注入的不安全状态全部在设备运动前被阻止） |
| **人工审批** | 高风险决策支持要求人工批准，保留人在回路（QuEra 报告：任何稍显风险的操作都等待人类批准，有时导致实验过夜暂停） |
| **分阶段发布** | preview 先行、安全评估后再开源——发布节奏本身就是安全机制，让失败模式在受控环境（有专家在场）中暴露 |
| **物理安全路线图** | Anthropic 声明正在制定 physical safety roadmap，强化 safeguards 政策与滥用防护执法覆盖 |

### 6.2 关键设计决策：限制在 driver，不在 prompt

PacketNebula 的评论（[原文](https://www.packetnebula.com/articles/model-hardware-standard-mhs-research-preview/)）：

> "Prompt-level safety is per-agent and evaporates the moment somebody swaps harnesses. A limit compiled into the driver applies to every caller, including the one you did not anticipate."
> （prompt 级安全是每个 agent 私有的，换 harness 就失效；编译进 driver 的限制作用于所有调用方，包括你没想到的那个。）

这是 MHS 与「让 agent 自觉」路线的根本区别：**安全护栏内置在硬件边界，而非依赖模型判断**。

### 6.3 多 agent 并发访问（待确认）

- ❌ 官方未公开：多个 agent 同时向同一设备发命令时的**优先级仲裁**如何决定（XenoSpectrum 明确将此列为悬而未决问题）
- ❌ 官方未公开：driver 更新时安全设置如何保留
- ❌ 官方未公开：网络中断、传感器数据过期、自然语言 tag 值错误时系统如何停机
- ✅ 已知周边事实：CMU 案例中「设备不可达」「急停激活」等条件会在运动前被阻止；Gently 项目（Janelia 独立公开仓库）实现了**单操作员锁（single-operator lock）**——但这属于 Gently 实现，不代表 MHS 规范

### 6.4 安全边界与局限（官方承认）

- Claude 通过文本和图像学习物理世界，**空间与物理推理有限**，仍需专家监督
- Genentech 案例：蛋白质样本发泡导致的错误，Claude 起初当成软件 bug 反复重试（越搅越多泡），人类解释后才意识到是物理问题
- QuEra 案例：纯硬件故障 AI 无法解决；保守等待批准会牺牲无人值守运行时间
- 自然语言 tag 是**描述性元数据，不是经过验证的安全证书**——生产部署应把 tag 作为策略与规划的输入，硬限制留在硬件控制器/PLC/运动限位/独立测试的互锁中（Essa Mamdani 强调）

### 6.5 监管语境（第三方分析）

[Santage](https://santageai.com/learn/concepts/model-hardware-standard) 指出：因为 MHS 文件可约束机器如何移动，它**可能构成安全组件**，落入欧盟机械法规（EU Machinery Regulation 2023/1230，2027-01-20 生效，首次覆盖 AI 安全功能）等监管范围。此为第三方法务推断，非官方立场。

---

## 7. 与 MCP 及其他标准的关系

### 7.1 MHS 与 MCP：互补而非竞争

官方学习页（[what-is-mhs](https://model-hardware-standard.com/learn/what-is-mhs)）给出的对比：

| 维度 | MCP | MHS |
|---|---|---|
| 定位 | **软件与上下文层** | **物理设备层** |
| 连接对象 | AI 应用 ↔ 工具、服务、数据 | AI agent ↔ 真实设备 |
| 核心原语 | tools、resources、prompts | read/write（driver 内） |
| 典型失败 | 错误答案/失败的 tool 调用 | 损坏的样本/不安全的机器状态 |
| 关系 | — | **MHS 可通过 MCP 暴露硬件能力；MHS 构建在 MCP 之上** |
| 成熟度 | 开放标准、广泛采用、已捐赠给 Linux 基金会下属 Agentic AI Foundation | research preview、Anthropic 控制、计划开源 |

**架构关系**（PacketNebula 的准确表述）：MCP 是 agent 与 tool server 之间的协议；**MHS 是它底下的设备驱动契约**。MCP 是进入 MHS 层的三种门之一，不是 MHS 本身。"MCP for hardware" 是好的第一近似，却是错误的最終解释。

**对开发者意味着什么**（kdpisda.in 的观察）：Anthropic 没有从零发明物理世界协议，而是**扩展了 MCP**，把 read/write 原语作为传输内容复用。若此模式在开源后成立，MCP server 开发者的技能可直接迁移——为数据库写 MCP server 的 tool 定义与安全边界思维，同样适用于设备驱动。

### 7.2 与 AGENTS 协议的关系（待确认）

- AGENTS.md（Agentic AI Agent Communication Protocol，2025-05 由 Anthropic/OpenAI/Google 等联合提出）是 agent 之间/agent 与工具之间互操作的开放协议
- **MHS 官方公告与学习页均未提及 AGENTS 协议**，三种控制路径只列了 MCP、CLI、code
- 合理推断（待确认）：AGENTS 协议属于「agent 与 agent/tool 交互」层，MHS 属于「agent 与物理设备」层，二者层级不同，但未来 agent harness 可能通过 AGENTS 协议编排多个 agent，再经 MCP/MHS 触及设备——**这是推断，官方无表态**

### 7.3 与既有设备标准的对比

[XenoSpectrum 对比表](https://xenospectrum.com/en/anthropic-mhs-physical-ai-standard/)（2026-08-28 核对）：

| 技术 | 主要连接对象 | 公开规范覆盖范围 | 与 MHS 的关系 |
|---|---|---|---|
| **MCP** | AI host 与 server | resources、tools、prompts、session | MHS 设备的上层访问路径 |
| **SiLA 2** | 实验室仪器与软件 | Features、Commands、Properties、发现、认证 | 实验室自动化功能重叠 |
| **OPC UA** | 工厂传感器到企业系统 | 信息模型、服务、通信、发现、安全交换 | 可作为工业领域的既有基础设施 |
| **ROS 2** | 组成机器人的节点 | topic、service、长时 action | 可处理机器人体内通信 |

**竞争动态**：2026 年 4 月 OPC Foundation 宣布准备 430+ 个 OPC UA Companion Specifications 用于 RAG 与 MCP——从另一方向逼近同一连接点。MHS 走轻量自然语言 tag 路线，OPC UA 携带工业界积累的严格语义模型与一致性认证。Anthropic 尚未披露 MHS 如何映射/桥接 OPC UA、SiLA 2、ROS 2（待确认）。

---

## 8. 工作流示例：agent 操控设备的完整链路

### 8.1 官方叙述的典型场景（激光校准）

官方公告描述了 Claude 的行为模式（科学家的探索式操作）：

1. agent 通过 MHS **读取**激光设备状态
2. agent **调整**激光（write）
3. agent 通过**相机**观察结果（read），评估调整如何移动了激光束
4. 重复上述循环，**理解事件序列**
5. 把学到的封装为**代码文件**，写出确定性脚本
6. 整个对准流程以**单条命令**运行，无需 agent 逐步推理

### 8.2 一次「读取传感器」的完整消息序列（推断模型，待确认）

> **重要声明**：以下序列是**基于官方公开描述与 MCP 既有机制的合理重建**，用于帮助理解。MHS 官方未发布正式消息格式，字段名/流程步骤均有待 spec 确认。

```
[1] 发现阶段
Agent → (mDNS/广播/目录) : 谁在线？（MHS discovery，标准格式）
设备   → Agent          : 我是 liquid_handler_1；能力 {dispense, aspirate}；
                         状态 {idle}；限制 {dispense_volume_ul: 0–200}；网络位置

[2] 理解阶段
Agent → Driver          : 给我参考文件（reference file）
Driver → Agent          : {可测量: [温度, 液面], 可调整: [dispense_volume_ul,
                          speed], 物理特性: {重量: 45kg}, 强制限制: {...}}

[3] 操作阶段（经 MCP 路径，JSON-RPC 封装——MCP 层已公开，MHS 层待确认）
Agent → MCP Server      : tools/call {name: "liquid_handler_1.read",
                          args: {parameter: "temperature"}}
MCP Server → Driver     : read(temperature)
Driver → 硬件            : <厂商私有协议>
硬件   → Driver          : 22.5°C
Driver → MCP Server     : {value: 22.5, unit: "°C"}
MCP Server → Agent      : 结果返回

[4] 安全拦截演示
Agent → Driver          : write(dispense_volume_ul = 500)   ← 超出限制 200
Driver → Agent          : 拒绝：值超出安全上限 200 µL（命令未到达硬件）
```

第三方浏览器模拟器（[kdpisda.in](https://kdpisda.in/anthropic-model-hardware-standard-mhs/)）实现了此形态的交互演示：选择 device（liquid_handler_1 / plate_reader_2 / laser_calibrator）→ 选择 operation（read/write）→ 指定 parameter（temperature / position_mm / dispense_volume_ul）→ 发送；超过安全上限的 write 会被拒绝——与 MHS driver 拒绝不安全命令的行为一致（该模拟为社区基于公开描述构建，非官方实现）。

### 8.3 跨设备协调（CMU 案例形态）

CMU 的剂量-响应实验（[官方公告](https://www.anthropic.com/news/model-hardware-standard-research-preview) + [XenoSpectrum](https://xenospectrum.com/en/anthropic-mhs-physical-ai-standard/)）：

1. 机械臂、移液处理器、板式读取器、监控相机分布在**三台不兼容的电脑**上
2. agent 写 driver + 编排层，约 **8 小时**完成（厂商方案需数周）
3. 第一轮实验浓度上限过高、曲线拟合差 → agent **丢弃板子、缩小浓度范围、重跑**
4. 第二轮得到可用曲线——**「测量结果 → 改变实验条件」的闭环在同一接口内完成**
5. 安全测试：注入 6 种异常（无板、方向错、读取器占用、相机断开、设备不可达、急停激活），**全部在设备运动前被阻止**

---

## 9. 参考实现与生态

### 9.1 官方资源状态（截至 2026-09-01）

| 资源 | 状态 |
|---|---|
| 规范文档（spec） | **未发布**（无 spec、无 schema、无版本号） |
| 官方站点 | [modelhardwarestandard.com](https://modelhardwarestandard.com)（landing + 申请表单 + 学习页） |
| 官方博客 | [anthropic.com/news/model-hardware-standard-research-preview](https://www.anthropic.com/news/model-hardware-standard-research-preview) |
| SDK/参考实现 | **无公开**（`github.com/anthropics/mhs` 404） |
| License/治理模型 | **未公开** |
| Conformance/认证 | **未公开** |
| 申请入口 | research preview 申请制（面向科研机构、制造商、机器人团队、硬件厂商） |

### 9.2 相关公开代码：Gently（Janelia 独立项目）

[github.com/gently-project/gently](https://github.com/gently-project/gently)（GPL-3.0-or-later，Shroff Lab 开发）——**注意**：这是 HHMI/Janelia 的 agentic microscopy 项目，**不是 MHS 源码仓库**，但其架构展示了与 MHS 同源的安全设计理念：

- **四层架构**：core（零领域知识基础层）→ harness（可复用 agent 框架：tools/perception/memory/plan_mode）→ organisms（可换领域插件）→ app（显微 agent）
- **安全栈**（五层独立保护）：进程隔离（HTTP API 分离 agent 与设备层，客户端崩溃不影响显微镜）→ 设备限制（`set()` 前硬校验边界，保护 stage/piezo/galvo）→ 计划约束（Bluesky plans 用受限安全原语词汇表）→ 模板化动作（agent 操作 Embryo 对象而非裸坐标）→ 自动清理（try-finally 保证任何错误时激光关闭）
- **明确欢迎编码 agent**："safety stack exists precisely so that coding agents can iterate rapidly without risking hardware"——安全栈存在正是为了让编码 agent 快速迭代而不冒硬件风险
- 单操作员锁（single-operator lock）、viewer/operator/admin 角色、PBKDF2 密码哈希

### 9.3 生态与厂商支持（官方公告确认）

**科研合作方（6 家，均有早期结果）**：

| 机构 | 场景 | 关键数字 |
|---|---|---|
| Genentech | BCA 蛋白测定自动化（移液处理器+机械臂+板读取器） | 水 ~140 µL/s、蛋白液 ~10 µL/s 优化；泡沫物理问题需人类解释 |
| UW Baker/Pinglay labs | 远程监控仪表盘、AI 监督 qPCR、机械臂-移液处理器无碰撞板交接 | 一周内连接 6 台设备（含写 driver） |
| CMU | 串联稀释剂量-响应实验自动化 | 驱动+编排约 8 小时（厂商需数周）；实验提速约 3 倍；6 种不安全状态全部预拦截 |
| HHMI Janelia | 显微镜研究提速（7 个厂商程序 → 1 个 dashboard） | 新增相机仅需数分钟 |
| QuEra Computing | 量子计算机激光稳定（relock + tuning） | 盲验证 695/700（99.3%）；简单故障 0.9–5.4 s，难故障 10–14 s；43 次自然 mode hop 全部检测恢复 |
| Tetsuwan Scientific | qPCR 自动化（ResearchOS 平台） | 9,143 次分液、300 种转移类型、1,508 个测量条件；精度预测比厂商规格高约 12% |

**硬件/平台厂商（10 家宣布支持或测试）**：

| 厂商 | 动作 |
|---|---|
| AWS | 通过 **Strands Robots**（连接 agent 与物理设备的库）支持；preview 期间提供私有预发布版 |
| Automata | 在 LINQ 实验室自动化平台加 MHS，智能错误处理 |
| Danaher | 与 Anthropic 探索 MHS 能力支持智能仪器与自主实验室 |
| Doosan Robotics | 测试 MHS（机械臂自动质检、多机器人任务协调） |
| MBF Bioscience | 为 **ScanImage**（数百神经科学实验室使用的激光扫描显微镜软件）构建 MHS driver |
| QIAGEN | QIAsymphony Connect 平台 MHS 概念验证（故障排查、操作员引导恢复） |
| Tecan | Fluent 移液平台加 MHS 支持 |
| Universal Robots | 早期访问，计划加入其机器人平台 |
| Hugging Face | 在 **LeRobot**（机器人库）加 MHS 支持 |
| Raspberry Pi | 多产品启用 MHS 集成（Camera MHS Driver 测试成功） |

**数量级证据汇总**（全部为 partner 报告，无独立方法论背书——PacketNebula 与 Kingy 均提醒勿直接进商业案例）：集成时间从数周/数月 → 数小时（CMU 8h）/数天（UW 一周 6 设备）；QuEra 激光 relock 从 58% 成功率/150 s 每次 → 99.3%/约 6 s。

---

## 10. 对 Nebflow 的参考价值

（简短，不展开）

1. **能力协商/能力描述层**：MHS 的「参考文件（reference file）」——设备先自述能力与限制，agent 操作前必须读取——与 Nebflow 的 agent 声明式能力注册是同一思路。Nebflow 的 skill/agent 声明体系可借鉴"**先读清单、后操作**"的强制顺序，把能力描述从 prompt 里挪进机器可读的注册文件。
2. **权限分层（driver 级 vs prompt 级）**：MHS 把安全边界编译进 driver（作用于所有调用方、不随 harness 切换失效）而非依赖每个 agent 的 prompt——对应 Nebflow 的权限系统设计原则：**权限应挂在执行通道（工具/网关）而非挂在调用者上下文**。
3. **编排 harness 的位置**：MHS 明确「模型无关、任何 agent harness 可用标准协议访问」，且 MCP 是三种路径之一——印证 Nebflow 作为 agent 编排 harness 应保持**协议层中立**（支持 MCP 等开放协议接入，而非绑定单一模型/工具栈）。
4. **可观察性**：共享内存状态字典 / Gently 的"样本即数据单元 + 推理痕迹全暴露"——agent 决策可观察是物理与科研场景的硬需求，与 Nebflow 的 turn 级可观察/审计思路同构。
5. **分阶段发布**：research preview → 安全评估 → 开源，发布节奏本身就是安全机制，可作为 Nebflow 大特性发布节奏的参考。

---

## 11. 待确认事项清单

以下内容官方未公开，调研无法确认（如实标注，未编造）：

| # | 事项 | 说明 |
|---|---|---|
| 1 | 正式 spec 文本 / schema | 无公开规范文档，全部协议细节基于官方描述与第三方推断 |
| 2 | 消息格式（是否 JSON-RPC、字段结构） | 仅确认 MCP 层为 JSON-RPC 2.0；MHS driver 层线缆格式未知 |
| 3 | session/握手/能力协商正式流程 | 无公开定义；仅有「发现 + 读参考文件」的流程描述 |
| 4 | 多 agent 并发访问仲裁 | 官方未公开优先级/互斥机制 |
| 5 | 认证/授权/凭据管理/审计日志要求 | 官方未公开威胁模型 |
| 6 | read/write 之外是否还有正式原语 | describe/observe/stop 为第三方推断 |
| 7 | AGENTS 协议与 MHS 的关系 | 官方未提及 |
| 8 | 与 OPC UA / SiLA 2 / ROS 2 的桥接 | 官方未披露映射方案 |
| 9 | 开源日期、License、治理模型 | 承诺开源但无日期 |
| 10 | 非 Claude 模型的公开演示 | 所有演示均为 Claude；模型无关是宣称 |
| 11 | QuEra 原文博客 | 官方博客 URL 无法直接抓取（反爬），数据经 XenoSpectrum/Kingy 转述 |
| 12 | 独立复现的基准 | 全部数字为 partner 自报，无独立验证 |

---

## 12. 来源清单

### 官方一手来源
- **Anthropic 官方公告**（2026-08-27）：[Previewing the Model Hardware Standard](https://www.anthropic.com/news/model-hardware-standard-research-preview) — 全部机制描述、6 家科研案例、10 家厂商名单、安全声明
- **MHS 官方站点**：[modelhardwarestandard.com](https://modelhardwarestandard.com)（landing、申请入口、起源、加入 preview）
- **MHS 官方学习页**：[What Is MHS? / MHS hub](https://model-hardware-standard.com/learn/what-is-mhs)（MHS vs MCP 官方对比、四机制归纳、开源状态）
- **HHMI 新闻**：[How One Postdoc's Problem Solving is Changing the Way Scientists Work](https://www.hhmi.org/news/how-one-postdocs-problem-solving-changing-way-scientists-work)（起源故事：Arco Bast、共享内存、2026-02 会面、AI-in-the-Loop）

### 独立媒体报道
- CNBC（2026-08-27）：[Anthropic pushes into physical world with new standard to help AI agents operate machines](https://www.cnbc.com/2026/08/27/anthropic-pushes-into-physical-world-with-new-standard-to-help-ai-agents-operate-machines.html)
- The Decoder（2026-08-29）：[Anthropic wants to do for physical hardware what its Model Context Protocol did for software](https://the-decoder.com/anthropic-wants-to-do-for-physical-hardware-what-its-model-context-protocol-did-for-software/)
- Ars Technica（引述自 Essa Mamdani 文章，原页被反爬）：[Anthropic's new hardware standard lets AI agents control the physical world](https://arstechnica.com/ai/2026/08/anthropics-new-hardware-standard-lets-ai-agents-control-the-physical-world/)

### 技术分析（第三方，含机制重构与批判视角）
- **XenoSpectrum**（2026-08-28）：[Anthropic Previews MHS, a Physical Safety Layer for AI-Controlled Lab Hardware](https://xenospectrum.com/en/anthropic-mhs-physical-ai-standard/) — MHS vs MCP/SiLA 2/OPC UA/ROS 2 对比表、QuEra/CMU 细节、OPC Foundation 竞争动态、未决问题清单
- **Kingy.ai**（证据优先）：[Anthropic Model Hardware Standard (MHS) Explained](https://kingy.ai/blog/anthropic-model-hardware-standard-mhs/) — 六层架构表、MHS 定义 vs 未公开清单、6 案例细节与限制、安全缺口分析、Gently 独立性的澄清
- **PacketNebula**（2026-08-31）：[Model Hardware Standard: MHS is gated, and MCP sits above](https://www.packetnebula.com/articles/model-hardware-standard-mhs-research-preview/) — MHS 是 driver 层、MCP 在其上；driver 级安全 vs prompt 级安全；"目前无法构建"的直言
- **Essa Mamdani**（验证优先）：[A Safe Developer Guide to AI-Controlled Devices](https://essamamdani.com/blog/anthropic-model-hardware-standard-guide-2026) — 5 原语解释模型（read/write/describe/observe/stop，标注为推断）、分层职责表、安全采用路径（模拟起步/读写分离/参数验证/审批门/急停/审计）
- **Santage AI**：[What Is the Model Hardware Standard? The Definitive Guide](https://santageai.com/learn/concepts/model-hardware-standard) — MHS vs MCP/ROS/SiLA/OPC UA、共享内存状态字典、欧盟机械法规 2023/1230 关联、MCP 原语对比
- **kdpisda.in**：[Anthropic's Model Hardware Standard: MCP for Physical Devices](https://kdpisda.in/anthropic-model-hardware-standard-mhs/) — read/write 原语形态、浏览器模拟控制台、MCP 复用观察
- **MHSBase**（独立社区资源）：[What Is MHS? A Practical Guide](https://mhsbase.com/what-is-mhs) — 8 步工作流、MHS 标准 vs 实现 vs 生态的区分

### 相关公开代码
- **Gently**（HHMI/Janelia Shroff Lab，GPL-3.0-or-later，非 MHS 本体）：[github.com/gently-project/gently](https://github.com/gently-project/gently) — agentic microscopy harness、五层安全栈、单操作员锁

### 竞争/相关动态
- **OPC Foundation**（2026-04，转引自 XenoSpectrum）：430+ OPC UA Companion Specifications 面向 RAG/MCP
