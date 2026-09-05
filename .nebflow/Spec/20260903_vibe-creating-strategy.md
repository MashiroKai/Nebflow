# Vibe Creating 战略调研——从「改造数字世界」到「改造物理世界」

> 阶段文档（战略调研，完成即冻结）。日期：2026-09-03 ｜ 作者：Explorer
> 任务来源：作者「vibe creating」战略构想——AI 目前只能 vibe coding（改造数字世界），Nebflow 要做 vibe creating（改造物理世界）：用户在 Nebflow 本地完成设计 → NebLink A2A（好友体系）把订单发给厂家 agent → 厂家安排生产 → 设计、下单、付款全流程在 Nebflow 完成。
> 本报告只调研不写代码。所有关键论断带来源；检索不到的明确标注。

---

## 0. 执行摘要

**还差多远：协议与生态的「外壳」行业已经备好，Nebflow 差的是「订单信封 + 回流」这一层薄工程（4-6 周可做到人扮厂家的端到端闭环演示），以及真正难的生态问题——国内小批量厂家没有公开下单 API，厂家接入是商务问题不是技术问题。** 具体判断：① agent 互操作标准（Google A2A 已入 Linux 基金会、SDK 六语言）与 agent 支付标准（AP2 含蚂蚁国际/银联国际、Stripe+OpenAI 的 ACP、x402）都已成型，NebLink 自建 A2A 完全站得住——好友式 P2P 恰是 A2A「opaque agents」理念的社会化实现；② 但全球 agentic commerce 全部扎堆消费零售（ChatGPT Instant Checkout/Perplexity 购物），**「个人设计直连制造」的 agent 闭环未检索到任何已落地者**——这是空白窗口；③ 制造侧的数字化地基已成熟（Gerber/STL 强制标准 + 嘉立创式自动报价 + 嘉立创EDA 一键下单先例），证明「设计文件→订单」可完全自动化，agent 版是自然延伸；④ 最小闭环路径：阶段 1 做结构化订单 envelope（`messages.kind` 字段已预留）+ 厂家回复自动回流 + 订单状态机（约 2-3 周），阶段 2 用「人当厂家 agent + 人扫付款」跑通全链路演示（再 1-2 周），零合规风险；⑤ 真厂家接入走商务合作（或浏览器自动化过渡），支付托管（代收代付需支付牌照）明确标为二期。**先做什么：本周可定订单 envelope schema + 写第一个 KiCad/OpenSCAD 设计 skill；品类首推 PCB 打样（下单数字化/文件标准化/供应链成熟度/价格交期四项全满分）。**

---

## 1. 现有格局

### 1.1 协议与标准：三层骨架已成型

**（a）Agent 互操作层——Google A2A Protocol（已入 Linux 基金会）**

- A2A（Agent2Agent）是开放标准，定位「不透明 agent 应用之间的通信与互操作」：agent 互发任务（task）、产物（artifact）、状态更新，**不共享内部记忆/工具/私有逻辑**。官方定位：MCP 管「agent↔工具」，A2A 管「agent↔agent」。SDK 已覆盖 Python/JavaScript/Java/C#/.NET/Go/Rust 六语言，并有 DeepLearning.AI 官方课程。
  来源：[A2A 官方文档](https://a2a-protocol.org/latest/)
- 2025-06-23 Google 将 A2A 捐赠给 Linux 基金会成立开源治理项目，发布时 100+ 企业成员参与（Amazon、Microsoft、Salesforce、SAP 等在列）。
  来源：[Linux Foundation 公告](https://www.linuxfoundation.org/press/linux-foundation-launches-the-agent2agent-protocol-project-to-enable-secure-intelligent-communication-between-ai-agents)
- **对 NebLink 的意义**：NebLink 的「好友体系 + agent 互发消息」与 A2A 同构但更社会化（A2A 是机器发现机器，NebLink 是好友关系背书的信任链）。兼容/借鉴点见 §1.4。

**（b）支付与交易层——三条路线并行**

| 协议 | 主导方 | 机制 | 与本场景的关系 |
|---|---|---|---|
| **AP2**（Agent Payments Protocol） | Google + 60+ 机构（2025-09-16 发布） | A2A/MCP 扩展；核心是 **Mandate（授权凭证）**：Intent Mandate（用户授权意图）+ Cart Mandate（购物车加密签核，不可篡改），基于可验证凭证（VC）；支持人在环（human-present）与纯自动（human-not-present）两种模式 | **蚂蚁国际（Ant International）、银联国际（UnionPay International）在创始合作方名单**——国内支付通道已入场；阶段 4 支付托管可直接借鉴 Mandate 模式。来源：[Google Cloud 官方公告](https://cloud.google.com/blog/products/ai-machine-learning/announcing-agents-to-payments-ap2-protocol) |
| **ACP**（Agentic Commerce Protocol） | Stripe + OpenAI（2025-09 开源） | Apache 2.0 开源规范；REST + MCP 兼容；**商家保留 merchant of record（交易主体）地位**，agent 只传递加密支付令牌（Stripe Shared Payment Token），不碰持卡人敏感信息 | **「agent 代谈、商家收单、支付走既有通道」的同构范式**——Nebflow「人付款」模式与 ACP 的 merchant-of-record 保留哲学一致（平台不碰钱 → 无需牌照）。来源：[agenticcommerce.dev](https://www.agenticcommerce.dev/) |
| **x402** | Coinbase 发起，x402 基金会（Coinbase + Cloudflare） | HTTP 原生支付（HTTP 402 状态码 + 稳定币），近 30 天 7541 万笔 / $2424 万交易额 / 9.4 万买家 / 2.2 万卖家（官网实时数据） | 加密货币路线，**中国大陆不适用**（合规不可行）；但其「零账户摩擦」目标值得借鉴。来源：[x402.org](https://www.x402.org/) |

**（c）国内动向**

- 消费级：支付宝 2024-09 发布 AI 生活助手「支小宝」（App 内闭环，尚未形成 agent 跨平台下单协议）；淘宝侧 2025 双 11 主推商家侧 AI（AI 店长 + 数字员工，覆盖 500 万商家），**消费端「agent 代用户跨平台下单」未检索到公开的成熟产品**。来源：腾讯新闻支小宝报道（2024-09-06）、百度检索结果（淘宝 AI 店长双 11 报道）。
- 支付机构层：2026-06 央行批准鲲鹏支付、公众通支付等多家支付机构相关变更（百度检索到，细节未展开）——国内支付牌照通道仍在正常流转，阶段 4 有合作标的。
- 协议层：**未检索到阿里/字节发布对标 AP2/ACP 的 agent 交易开放协议**。蚂蚁国际、银联国际选择加入 AP2 阵营（见上）是国内机构当前的公开站位。

### 1.2 先行者与竞品：agentic commerce 已落地清单

| 玩家 | 形态 | 状态 | 来源 |
|---|---|---|---|
| OpenAI Operator | 浏览器操作 agent，可代订外卖/购物（支付环节需用户接管） | 2025-01 发布（US Pro） | [openai.com/index/introducing-operator](https://openai.com/index/introducing-operator/) |
| OpenAI ChatGPT Instant Checkout | ChatGPT 内即时结账，首批 Etsy 商家 + Shopify 接入，基于 ACP | 2025-09 发布（US） | [openai.com/index/buy-it-in-chatgpt-instant-checkout](https://openai.com/index/buy-it-in-chatgpt-instant-checkout/) |
| Perplexity Shopping | 搜索结果内购（Buy with Pro，US），2025-05 与 PayPal 合作「聊天即买」 | 已上线 | Perplexity 官方博客（2024-11）；亿恩网《Perplexity携手PayPal开启聊天即买新体验》 |
| Amazon「Buy For Me」 | Amazon app 内代购他站商品（人确认支付） | 2025-04 起 US 试点 | 公开报道（未单独核验原文，方向确定） |
| 国内 agent 下单试点 | 支小宝/淘宝 AI 均为平台内助手；**跨平台 agent 代下单未检索到公开落地** | 早期 | 见 §1.1(c) |

⚠️ **风险信号**：检索中出现 AIbase 报道标题称「OpenAI 调整电商战略：放弃 ChatGPT Instant Checkout，转向商品发现与研究」——**未能核实原文与时间，存疑**；若属实说明连 OpenAI 也在结账闭环上遇阻（利益分配/商家接入成本），侧面支持「人付款的轻模式先行」的路线选择。

**结论：agentic commerce 的全部真实落地都在消费零售「搜→比→买」链路上；「设计→制造」方向无人闭环。**

### 1.3 「设计→制造」方向的数字化地基（先行者拆解）

这是对 vibe creating 最重要的一节——**没有人做 agent 闭环，但「设计文件→自动报价→下单→生产」的无人化链路早已存在**，agent 版只是把它从「网页表单」搬到「好友对话」：

| 平台 | 模式 | API 化程度 | 来源 |
|---|---|---|---|
| **Fictiv** | 数字制造平台（CNC/3D 打印/注塑/钣金），全球分布式制造商网络 | 面向企业客户的 API（报价+下单+物流状态） | [fictiv.com](https://www.fictiv.com/) |
| **Xometry** | 按 co 制造 + AI 即时报价引擎 | 合作伙伴 API（即时报价集成） | 官网/公开报道 |
| **Treatstock** | 3D 打印/机加工/雕刻分布式制造网络 | **有公开 API 服务**（第三方集成文档） | 幂简集成《全面解读 Treatstock API 服务商》（explinks.com） |
| **嘉立创/JLCPCB** | PCB 打样全球最大个人级工厂 + 3D 打印 | **未检索到公开的个人级 Web 下单 API**；但官方自动化路径成熟：①嘉立创EDA（云 EDA）内置**一键下单**（设计→订单参数全自动映射）；②KiCad 第三方插件（fabrication toolkit）一键生成嘉立创格式 Gerber/BOM/CPL | 嘉立创EDA 专业版用户指南（prodocs.lceda.cn）；CSDN《KiCad 安装嘉立创插件实现 Gerber 下单》 |
| **未来工场** | 互联网制造服务平台（3D 打印/CNC），A 轮过亿元 | 面向 B 端系统对接（个人级 API 未检索到公开文档） | 网经社融资报道 |
| **云工厂** | 共享制造平台（B 端），36 氪报道 A+ 轮 | 企业 API 对接 | [36氪报道](https://36kr.com/p/1721349120001) |
| **Zoo（原 KittyCAD）** | **text-to-CAD**（文本生成参数化 CAD 模型）+ 3D 打印即服务，设计 API 已开源 | API 原生——「生成即可制造」的直接先例 | 百度检索《ZOO 开源了 3D 打印建模》 |

**关键判断**：
1. **企业级「API 化制造」已成熟（Fictiv/Xometry/Treatstock），个人级「网页化制造」已成熟（嘉立创/未来工场）——两层之间缺的正是 agent 这层皮**：把网页表单翻译成对话，把个人设计文件翻译成订单。
2. **嘉立创EDA 一键下单是「设计直连制造」的最成熟形态**——它证明 Gerber→订单参数（板层/尺寸/数量/工艺）的映射可完全无人化。agent 闭环 = 把这个映射规则变成厂家 agent 的能力卡。
3. 「个人设计直连制造的 agent 闭环」：**未检索到公开信息**——确认是空白。

### 1.4 NebLink 自建 A2A 与标准的兼容/借鉴点

| 标准 | 借鉴什么 | 是否兼容 |
|---|---|---|
| A2A（LF） | ① Agent Card（能力发现）→ **厂家 agent 公开「能力卡」**：品类/材料/报价规则/交期/文件格式要求；② task 生命周期对象 → 订单任务；③ artifact → 设计文件/成品文件 | NebLink 消息可封装 A2A 语义（task/artifact 字段），远期可出 A2A 网关（NebLink 好友 ↔ 外部 A2A agent） |
| AP2 | Mandate 思路 → **「意图授权 + 报价签核」**：用户对「本次采购意图+预算上限」签核（Intent），厂家 agent 回报价后用户对具体报价签核（Cart），双方凭证可追溯防抵赖 | 阶段 4 支付托管时引入；阶段 2/3 用「人在环确认」替代（等价于 human-present 模式的人工实现） |
| ACP | merchant-of-record 保留 → **Nebflow 永远不做交易主体**，用户直接付款给厂家，平台零资金过手 | 完全兼容——这就是「人付款」模式的协议化表述 |
| x402 | 无账户摩擦目标 | 不兼容（加密货币国内不可行），仅理念借鉴 |

---

## 2. 品类试点推荐（关键输出）

评估维度：下单数字化程度 / 单品价格与支付摩擦（几百元级适合人工确认付款）/ 设计文件格式标准化 / 交付周期 / 设计工具链对 LLM 的可用性 / 生态先例。

### 排序结果

**🥇 Top 1：PCB 打样（嘉立创/捷多邦类工厂）**

| 维度 | 评分 | 依据 |
|---|---|---|
| 下单数字化 | ★★★★★ | Gerber 上传→自动识别板层/尺寸→自动报价→自动工艺审核，全程无人化已被嘉立创EDA 一键下单证明 |
| 价格/支付摩擦 | ★★★★★ | 纯打样几十元/批（4 层约百元级），人工扫码付款零摩擦；不含 SMT 则无 BOM 复杂度 |
| 文件标准化 | ★★★★★ | **Gerber（RS-274X）是行业强制标准**——全世界所有 PCB 工厂吃同一格式，零适配成本；钻孔文件/BOM/CPL 同为标准格式 |
| 交付周期 | ★★★★★ | 2-4 天（加急 24h），天数级长任务恰好考验订单状态机 |
| 设计工具链 | ★★★★★ | KiCad 开源（s-expression 文本格式 LLM 可读写 + kicad-cli 命令行出 Gerber + Python 自动化），DRC 校验自动化成熟 |
| 生态先例 | ★★★★★ | 嘉立创EDA 一键下单 + KiCad 嘉立创插件 = 「设计→制造」数字化的直接先例，agent 版是自然延伸 |

**理由**：五个维度全满分且每个环节都有现成自动化先例；国内供应链（嘉立创）是全球个人级 PCB 制造效率最高的基础设施。最小闭环演示的技术风险最低。

**🥈 Top 2：3D 打印（FDM/光固化，嘉立创三维猴/未来工场类）**

- 优势：STL/OBJ/3MF 格式标准统一；上传即自动报价（体积×材料单价）；**OpenSCAD 纯代码参数化建模是 LLM 亲和度最高的 CAD 形态**（vibe creating 的字面最佳载体——写代码→出可打印模型）；Zoo text-to-CAD 开源 API 证明「生成即可制造」路线成立。
- 劣势：可打印性校验（壁厚/悬垂/流形）成熟度低于 PCB 的 DRC，打印失败率由设计质量决定；模型文件较大（几十 MB 级）对消息附件通道压力更大；失败纠纷处理比 PCB 麻烦。
- 定位：**阶段 2 演示的第二选择/阶段 3 扩展品类**——若演示要突出「vibe」感（自然语言→实物），OpenSCAD→3D 打印的叙事冲击力最强。

**🥉 Top 3：激光切割/雕刻**

- 优势：DXF/SVG 2D 矢量文件 LLM 可直接生成（最简单的设计文件）；单件几十到几百元；材料便宜适合反复试错。
- 劣势：报价维度多（材料/厚度/工艺/是否代料），国内个人级云工厂（未来工场/云工厂）**未检索到公开的个人级下单 API**，网页流程自动化程度待核实；生态先例最弱。
- 定位：阶段 3+ 的横向扩展品类，不作首发。

**可补充候选（评估后落选首发）**：CNC 小件（单价高、报价需人工介入）、注塑（开模费万元级，支付摩擦过大）、PCB SMT 贴片（BOM 元器件采购链复杂，留作 PCB 打样跑通后的加购项）。

---

## 3. Nebflow 侧能力差距盘点

依据：NebLink 好友与消息系统架构（`friends-messaging-arch.md`，frozen v1.2）+ Project/Node 新架构方案（`20260831_project-node-architecture.md`）。

### 3.1 已有（直接复用）

| 能力 | 现状 |
|---|---|
| 好友体系 + NL 号 | friendships 单表状态机（pending/accepted/declined/blocked）+ NL 号自定义查重，15 个 REST 端点，frozen v1.2 |
| agent 发消息 | `SendFriendMessage(to, message)` 工具，权限三档 auto/ask/off |
| 人转发给 agent | 纯客户端行为（消息包装后走用户输入管线注入会话） |
| 多 agent 编排 | Project + Node + Flow Map（创建即运行/改接/barrier/结果持久投递） |
| skill 体系 | 本地 skill 注入，可承载设计工具链 |
| 本地执行 | Nebflow 本地跑 Bash/CLI——KiCad/OpenSCAD 本地出文件的天然载体（云厂商做不到的隐私优势） |

### 3.2 需补（按阶段）

| # | 缺口 | 现状与差距 | 落点 |
|---|---|---|---|
| 1 | **结构化订单 envelope** | messages 表 `kind` 字段已预留 `'file/task/order'` 扩展（DDL 注释明示二期）；现在只有 text ≤4000 字符 | 复用 kind 字段定义 order envelope schema：`{订单号, 品类, 设计文件引用, 参数表(板层/尺寸/数量/材料), 状态, 报价, 时间戳}`；建议对齐 A2A task/artifact 语义以便远期互通 |
| 2 | **设计文件附件通道** | 消息 4000 字符限制，无文件传输；Gerber 包几 MB、STL 几十 MB | neblink-server 加文件上传端点（对象存储 presigned URL 或服务端存储+哈希引用），消息只传引用+校验和 |
| 3 | **厂家回复自动回流** | 现在收到消息靠人点「转发」；厂家报价/状态变更若每次人工转发，闭环无人化不成立 | 「auto 转发规则」：按会话+消息类型订阅（如该会话的 order 类消息自动注入指定 agent 会话）；人在环降级为「关键节点确认」（报价确认/付款确认必须人签核，其余自动） |
| 4 | **订单状态机与长任务跟踪** | 无订单实体；relay store-and-forward + 离线补拉已具备离线消息基础 | 订单实体（独立表）：`requesting→quoted→approved→in_production→shipped→delivered→closed`，含 rejected/cancelled；天数级生命周期 + 关键状态变化推送（复用 friend_event 推送通道，加 `order_event` 类型）；Project/Node 的 Flow Map 可直接可视化订单工单流 |
| 5 | **agent 代理用户身份语义** | 好友关系挂 user_id，agent 借用户关系发消息；「对面是谁」的语义未定义（厂家 agent 看到的对话方=用户本人还是其 agent？） | 协议约定：消息携带 `on_behalf_of`（agent 代用户）标记；厂家侧身份引入「组织/企业 NL 号」概念（个人 NL 号 vs 企业认证号两级），防冒充 |
| 6 | **设计工具链 skill** | skill 体系已有；无设计类 skill | 三个首发 skill：①`kicad-pcb`（s-expression 生成→DRC→kicad-cli 出 Gerber→嘉立创格式校验）②`openscad-model`（代码建模→STL→可打印性校验）③`quote-parser`（厂家报价单解析）；全部本地执行，设计文件不出本机（对齐 vibe creating 隐私叙事） |

### 3.3 明确不需要的

- E2E 加密：一期已裁定不做（TLS+服务端可见）——订单场景反而**要求服务端可见**（对账/纠纷仲裁），现设计恰好正确。
- 自建支付：阶段 2/3 人付款完全绕开；阶段 4 才碰。

---

## 4. 分阶段路线图

### 阶段 0：已有 ✅
好友体系/NL 号/SendFriendMessage/人转发/多 agent 编排（Project+Node）/skill 体系/本地执行。

### 阶段 1：结构化消息 + 回流（约 2-3 周）
- order envelope schema（复用 `messages.kind`，对齐 A2A task 语义）+ 文件附件通道（上传端点 + 引用/校验和）
- auto 转发规则（会话级订阅，关键节点仍人工确认）+ `order_event` 推送类型
- 订单实体 + 状态机（requesting→…→closed）+ 离线补拉兼容
- **验收点**：两个 Nebflow 实例互发订单 envelope → 厂家侧 agent 自动收到 → 回报价 → 用户侧自动回流 → 状态机推进全程落库可查

### 阶段 2：MVP 闭环演示（+1-2 周）——人当厂家 agent + 人付款
- 一个真实演示：用户对话描述需求 → agent 用 `kicad-pcb`（或 `openscad-model`）skill 出设计文件 + DRC 通过 → envelope 发「厂家 agent」（**真人扮演**，在另一台 Nebflow 上）→ 回报价 → 用户点确认 → **真人扫码付款给真厂家**（网页手工下单或人工转账）→ 状态逐段推进 → 收货闭环
- 支付=人付款：个人对厂家直接付款是个人消费行为，平台零资金过手，无合规问题（见 §5）
- **验收点**：从自然语言到实物到手全程在 Nebflow 内可见可追溯；设计文件全程不出本机（发货的只有生产文件）
- **总耗时估计：阶段 1+2 合计 4-6 周（单人全职当量；与现有 neblink-server ~5300 行 Rust 代码库规模匹配）**

### 阶段 3：厂家接入产品化（季度级，瓶颈在生态不在技术）
- 厂家身份：企业 NL 号 + 能力卡（品类/材料/报价规则/格式要求/交期 SLA）
- 接入两条路：①**商务合作拿 API/半自动接口**（嘉立创级大厂商务谈；小厂给「人工回单工具」——厂家员工在网页下单后把单号贴回 NebLink，本质是厂家侧的「人肉 agent」，与阶段 2 演示同构，可规模化到 5-10 家）②浏览器自动化过渡（脆弱，仅自家演示用）
- 厂家侧工具：厂家 agent SDK/工作台（收单→解析设计文件→核价→回单→状态同步）

### 阶段 4：支付托管与合规（二期问题，见 §5；6-12 个月级）
- AP2 式 Mandate（意图+报价双签核）+ 持牌支付通道合作 + 担保交易（货稳后放款）

---

## 5. 合规与支付（事实梳理，标注为二期问题）

> 本节只做事实梳理，均为**二期问题**——阶段 2/3 的「agent 代下单、人付款」模式不触碰以下任何资质需求。

1. **个人对厂家直接付款**：个人通过支付宝/微信/银行转账向厂家支付货款，属个人消费行为，**不需要任何资质**——这是阶段 2/3 模式的合规基础。
2. **「agent 代下单、人付款」的合规边界**：agent 只是信息传递与意思表示辅助，**支付主体始终是人**——资金流不经过 Nebflow。此模式与 ACP 的 merchant-of-record 保留哲学一致（平台不做交易主体）。边界：平台不得代收代付、不得资金池。
3. **平台化托管（代收代付）需要的资质**：依据《非银行支付机构监督管理条例》（国务院令第768号，2023-12 公布、**2024-05-01 施行**，中国政府网 gov.cn 可查原文），从事储值账户运营/支付交易处理需**支付业务许可证**；无证从事资金二清（平台收用户钱再结给商家）属违规。合规路径：①持牌支付机构合作分润；②申请牌照（门槛高）；③仅做信息撮合+跳转支付（当前模式的自然延伸）。二类账户（银行 II 类户）可作托管账户的技术形态之一，仍需持牌机构落地。
4. 其他（远期留意）：设计文件出口管制场景（涉军/涉密Gerber）不在个人级品类射程；厂家 agent 冒充风险由企业 NL 号认证缓解（阶段 3）。

---

## 6. 风险清单

| # | 类别 | 风险 | 等级 | 对策 |
|---|---|---|---|---|
| R1 | 技术 | 国内个人级厂家无公开下单 API（嘉立创未检索到公开 Web API）——阶段 3 自动化路径受阻 | 高 | 商务合作优先；「人工回单工具」模式可立即规模化小厂；浏览器自动化仅过渡 |
| R2 | 技术 | 大文件传输（Gerber/STL 数十 MB）超出消息系统设计假设 | 中 | 附件走对象存储引用，消息只传哈希；分期分块上传 |
| R3 | 技术 | 长任务（天数级）状态跟踪：设备离线/换机/跨周 | 中 | 订单实体独立于消息持久化；复用 store-and-forward + 离线补拉；关键节点多通道通知 |
| R4 | 技术 | LLM 设计质量：DRC 通过率/可打印性不稳定，演示翻车 | 中 | 首发品类选 PCB（DRC 最成熟）；演示用预先验证过的板子兜底；设计 skill 内置校验回路 |
| R5 | 合规 | 二清红线：若提前做资金过手 | 高（但可完全回避） | 阶段 2-4 恪守「人付款、零资金过手」；托管留给阶段 4 与持牌方合作 |
| R6 | 生态 | 冷启动：厂家无接入动力（单量小），用户无厂家可用（鸡生蛋） | 高 | 先做「人肉 agent」接入 5-10 家小厂（边际成本≈0）；单城单品类打透；演示视频驱动自然增长 |
| R7 | 生态 | 标准之争：A2A/ACP/AP2 演进可能改写接口假设 | 低 | NebLink envelope 对齐 A2A task 语义，保留网关可能性；不押注单一支付标准 |
| R8 | 生态 | 大厂入场（OpenAI 已在零售闭环，若延伸到制造） | 中 | 差异化=本地设计隐私 + 好友信任链 + 中国供应链密度；窗口期内跑出厂家网络即护城河 |
| R9 | 产品 | 「人当厂家」阶段与「全自动」叙事的落差管理 | 低 | 明确阶段 2 是可信度演示（真实工厂真实付款），自动化是渐进替换人工环节 |

---

## 7. 结论

1. **方向成立且是空白**：agent 商务全在零售，「设计→制造」agent 闭环无公开先例；制造侧数字化地基（强标准文件格式+自动报价+一键下单先例）完全成熟。
2. **Nebflow 的位置极好**：本地设计（隐私）+ 好友信任链（厂家发现）+ 人付款（合规零门槛）三点组合无竞品重合。
3. **最短路径**：订单 envelope（`kind` 已预留）→ 回流自动化 → 订单状态机（阶段 1，2-3 周）→ 人扮厂家+人付款闭环演示（阶段 2，1-2 周）→ 首发品类 PCB 打样。
4. **真正的护城河不在代码在生态**：厂家网络（先人肉后 API）与「设计文件不出本机」的产品哲学。

---

## 来源清单

**直接核验（本次抓取原文）**
- A2A 官方文档：https://a2a-protocol.org/latest/
- Linux Foundation A2A 项目公告（2025-06-23）：https://www.linuxfoundation.org/press/linux-foundation-launches-the-agent2agent-protocol-project-to-enable-secure-intelligent-communication-between-ai-agents
- Google AP2 公告（含 60+ 合作方名单、Mandate 机制）：https://cloud.google.com/blog/products/ai-machine-learning/announcing-agents-to-payments-ap2-protocol
- Agentic Commerce Protocol（Stripe+OpenAI，Apache 2.0）：https://www.agenticcommerce.dev/
- x402（基金会、机制、实时交易数据）：https://www.x402.org/

**训练知识高置信 + 检索旁证**
- OpenAI Operator：https://openai.com/index/introducing-operator/
- OpenAI ChatGPT agent / Instant Checkout（ACP 首个实现，Etsy+Shopify）：https://openai.com/index/buy-it-in-chatgpt-instant-checkout/
- Perplexity Shopping（2024-11）与 PayPal 合作（2025-05）：Perplexity 官方博客；亿恩网《Perplexity携手PayPal开启聊天即买新体验》（ennews.com）
- Amazon「Buy For Me」（2025-04 US 试点）：公开报道

**中文检索结果（标题+域名，原始链接为搜索重定向）**
- 支付宝「支小宝」（2024-09）：腾讯新闻 new.qq.com/rain/a/20240906A08MNG00
- 淘宝 AI 店长/数字员工（2025 双 11，500 万商家）：百度检索（百度/link 重定向）
- 《非银行支付机构监督管理条例》（国务院令第768号，2024-05-01 施行）：中国政府网 gov.cn（检索标题可获原文）
- 嘉立创EDA 专业版一键下单文档：prodocs.lceda.cn《PCB 下单》
- KiCad 嘉立创插件：CSDN《【KiCad】安装嘉立创插件实现Gerber下单》
- Treatstock API：幂简集成《全面解读 Treatstock API 服务商》（explinks.com）
- Zoo（原 KittyCAD）text-to-CAD 开源：百度检索报道
- 未来工场 A 轮过亿元：网经社；云工厂 A+ 轮：36氪
- 央行 2026-06 批准鲲鹏支付等：百度检索报道

**未检索到公开信息（明确标注）**
- 嘉立创/JLCPCB、捷多邦面向个人开发者的公开下单 API
- 国内阿里/字节对标 AP2/ACP 的 agent 交易开放协议
- 「个人设计直连制造」的 agent 闭环任何已落地产品
- 国内消费级跨平台 agent 自动下单产品
- OpenAI「放弃 Instant Checkout」传闻的原始出处（仅见 AIbase 标题，未采信）
