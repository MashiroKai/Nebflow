# GLOSSARY.md — Nebflow 术语表（中英对照）

> **本表只管「名字」。** 每条 = 一行定义 + 一个仓内权威落点（路径相对仓库根）。
> 机制的完整解释在落点处，不在本表；本表不复制代码，也不承载一次性事实（版本号、提交哈希、行号）。
>
> **冲突优先级**（同名不同义时，左边赢）：
> `brand.conf` > 协议 / 兼容性字段名（`REBRAND.md`「兼容性承诺」）> 门禁脚本常量（`scripts/check-scala-layers.mjs` 的 `LAYER_ORDER`、`scripts/layer-exemptions.json`）> **本表** > 其它文档与代码注释。
>
> **维护规则**（与代码注释同款）：只增不改——改定义 = 改口径，须在原条目旁**并置**新条目，注明日期与理由；
> 新增术语必须带权威落点，没有落点的词不进本表。

## 1. 布局分区（三区 + 两类外挂）

前端只有一个页面（`src/main/resources/web/index.html`），`body` 是一条横向 flex 容器，
列宽与折叠状态都在 CSS 里（`css/base.css` 的 `body` 规则 + `css/split.css` / `css/nav.css`）。

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| **左区** | left zone | 活动栏 + 侧边栏两件合成的**一个**区。按「一区 = 一个 DOM 兄弟」数会数出四个区，故三区模型把这两件并为一区。 | `index.html` 的 `#activity-bar`、`#sidebar` |
| 活动栏 | activity bar | 48px 竖直图标条，`order:-1`；侧边栏收起时**不跟着收**（仍留 48px）。 | `css/nav.css` 的 `#activity-bar`；`js/activityBar.js` |
| 侧边栏 | side bar | 236px 可切换面板列；收起 = 宽 0 且透明。 | `css/split.css` 的 `#sidebar`；`body.sidebar-collapsed` |
| 面板 | panel | 侧边栏里可切换的一页，由 `data-panel-id` 标识（`files` / `messages` / `contacts` / `social`）。 | `index.html` 的 `.panel[data-panel-id]` |
| **中央区** | center zone / `#main` | 恒在的中间列（`flex:1`），**没有开关**；内含顶栏、消息流、输入区三段。 | `css/split.css` 的 `#main` |
| 顶栏 | header | 中央区顶部一行；**左右两区的开关按钮在这里**（`#sidebar-toggle`、`#canvas-toggle-btn`）——中央区持有两侧的遥控器。 | `index.html` 的 `#header` |
| 消息流 | chat stream | 会话消息的渲染容器与滚动主体。 | `index.html` 的 `#chat`；`js/chat.js` |
| 输入区 | input area | 输入栏（textarea、附件预览、ask/skill/compact 指示器、发送/停止）+ 斜杠下拉 + 队列条 + 语音浮层。 | `index.html` 的 `#input-area`；`js/input.js` |
| **右区** | right zone | Canvas 面板，**可选区**：默认关闭（`flex:0 0 0`），打开后宽度 `var(--canvas-width, 45vw)`。 | `css/split.css` 的 `#canvas-panel`；`body.canvas-open` |
| **浮层** | overlay layer | **不属于任何列**的一层：固定定位模态、模块自持浮层、弹窗会话视图、轻量提示。 | 见下方清单 |
| **布局配件** | layout fittings | 分隔件与装饰条：两个 `.col-resizer`（分区界线本体）+ `.edge-bar-right`（纯装饰，钉住右区右边界）。 | `index.html` 的 `.col-resizer`、`.edge-bar-right`；`js/colResizer.js` |

**分区界线**：两条 `.col-resizer` 自带机器可读的分区语义 —— `data-left="sidebar" data-right="main"`
与 `data-left="main" data-right="canvas"`。三区模型的两条界线就钉在这两个元素上。

**折叠状态**：都挂在 `body` 上，JS 只切 class —— `body.sidebar-collapsed`（左区收）、`body.canvas-open`（右区开）。

**浮层清单**：body 直属固定定位层 `#modal-overlay`、`#memory-overlay`、`#rules-overlay`、
`#path-picker-overlay`、`#settings-overlay`、`#search-overlay`、`#social-overlay`；模块自持浮层
（dropbox、usage 面板、neblink 账号模态）；弹窗会话视图（flow / bg-agent step 弹窗，独立 `ChatView`）；
lightbox 与 toast。**归类例外**：`socialPanel.js` 的入口按钮在活动栏（左区），面板本身是浮层。

**模块归属**（路径前缀 `src/main/resources/web/js/`）：

| 区 | 模块 |
|---|---|
| 左区 | `activityBar.js`、`explorer.js`、`messages.js`、`contacts.js`、`sidebar.js`（+ `js/sidebar/*`） |
| 中央区 | `main.js`（+ `js/main/*`）、`chat.js`（+ `js/chat/*`）、`chatView.js`、`input.js`（+ `js/input/*`）、`micOrb.js`、`cardRegistry.js`、`turnGroup.js`、`errorRecovery.js`、`askPending.js` |
| 右区 | `canvas.js`、`canvasDrop.js`、`fileViewers.js`、`viewers/*`、`projectTab.js`、`plugins.js`、`agentManager.js`、`flowMapTab.js`（+ `js/flowMap/*`）、`colResizer.js` |
| 浮层 | `modal.js`、`memory.js`、`chatSearch.js`、`usageDashboard.js`、`dropbox.js`、`socialPanel.js`、`lightbox.js`、`notificationBanner.js`、`bgTaskOutputPopup.js`、`neblink.js` |

## 2. 界面部件

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| **Canvas** | Canvas panel | 右区的通用展示面板（画板），标签式。**与 HTML `<canvas>` 元素无关**——输入区那个麦克风光球才是真 `<canvas id="mic-canvas">`。 | `js/canvas.js`；`index.html` 的 `#canvas-panel` |
| Canvas 标签 | canvas tab | 右区的一个标签页；`type` 决定由谁渲染（文件查看器 / 面板标签 / `flow-map` / `url`）。 | `js/canvas.js` 的 `openTab` |
| 预览标签 | preview tab | 未钉住（未 pin）的标签：开下一个预览时被顶掉（VS Code 同款语义）。 | `js/canvas.js` 的 `openTab` / `pinTab` |
| Canvas 查看器 | viewer | 按 `itemType` 渲染文件内容的协议对象（monaco、markdown、yaml、html、image、pdf、docx、xlsx、pptx、epub、largeText，另有横切的 shared / zoom）。 | `js/viewers/*` |
| 查看器注册表 | viewer registry | `itemType → viewer` 的注册表，`registerViewer` 可扩展。 | `js/fileViewers.js` |
| 卡片 | card | 聊天流里由 `cardRegistry` 渲染的沙箱 HTML 卡片（`___<AGENT>_HTML___` 标记），与 Canvas 的 HTML 查看器共用同一链接桥。 | `js/cardRegistry.js`、`js/viewers/shared.js` |
| 视图 | view / `ChatView` | 会话级视图实例，自持 DOM 引用、流式状态、输入态与分页。 | `js/chatView.js` |
| 弹窗视图 | popup view | 注册在 `chatViews` 里的非主视图（flow / bg-agent step 弹窗）；隐藏时事件直接跳过渲染。 | `js/chatView.js` |
| 会话列表 | conversation list | 左区 messages 面板里的会话 / 好友会话列表。 | `js/messages.js` 的 `#fm-conversations` |
| 右缘装饰条 | edge bar | 右区右边界上的细装饰条，非功能区。 | `index.html` 的 `.edge-bar-right` |

## 3. 会话与智能体

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| 会话 | session | 一次对话的载体，前端一个会话对应一个 `ChatView`。 | `js/chatView.js`；`src/main/scala/nebflow/actor/AgentState.scala` |
| 主会话 | root session | 顶层会话（不是任何派发链的下游）；项目挂载时的 `rootSessionId` 即顶层 Nebula 主会话 id。 | `src/main/scala/nebflow/core/project/ProjectActor.scala` |
| 根身份 | root identity | 「这个会话是不是根」的**单点**判定。 | `src/main/scala/nebflow/actor/RootAgentIdentity.scala` |
| Nebula | Nebula | 内置根智能体名（`a.name === 'Nebula'`）；展示与终审权归它——专属工具由根身份闸放行。 | `js/agentManager.js`；`src/main/scala/nebflow/agent/AgentCore.scala`（`POP_NEBULA_ONLY`） |
| 子代理 | sub-agent | 根会话派生的执行体，聚合显示在顶栏 Sub-Agents 面板/下拉里。 | `js/main.js`（Sub-Agents 行清理）；`js/locales/zh-CN.js` |
| 后台代理 | background agent / bg | 脱离当前轮的活体任务（心跳、后台任务输出），顶栏两个指示器分别对应后台任务与子代理。 | `js/main/bgAgents.js`；`index.html` 的 `#bg-indicator`、`#bgagent-indicator` |
| 会话种类 | `Kind` | 会话的类别枚举：`Root` / `Team` / `Flow` / `Delegate` / `Ephemeral` / `Plan` / `SubTask`。 | `src/main/scala/nebflow/actor/AgentState.scala` |
| 项目 | project | 工作区级任务容器，挂载后常驻。 | `src/main/scala/nebflow/core/project/ProjectActor.scala` |
| 节点 | node | 流程地图里的一个执行单元（普通节点持主会话三字段；loop 节点另带 verify 会话）。 | `src/main/scala/nebflow/core/project/NodeEngineContract.scala` |
| 分发器 | dispatcher | 负责把任务拆成流程地图的会话（会话标记 `isDispatcher`）。 | `src/main/scala/nebflow/actor/AgentState.scala`；`README.md` 的 Architecture 段 |
| 流程地图 | Flow Map | 项目任务的有向图视图，以 Canvas 标签（`flow-map-<项目>`）常驻展示。 | `js/flowMapTab.js` |
| 链 | chain | 流程地图里按连通分量分组的节点集合（折叠状态按 `chainId` 记忆）。 | `js/flowMap/collapse.js` |
| 流程运行 | flow-run | 运行态流程的标签类型；运行实例不随重启恢复。 | `js/canvas.js`（持久化排除项） |
| 展示权 / Pop | Pop | 「把内容显示给人看」的动作，由 Nebula 专属工具承担；节点不调用，只把绝对路径写进交付文本。 | `src/main/scala/nebflow/agent/AgentCore.scala`；`js/canvas.js` 的 `popFile` 帧处理 |
| AskUser | AskUserQuestion | 智能体向用户提问的工具与卡片（未答时在顶栏常驻条列出）。 | `js/chat/askBubbles.js`、`js/askPending.js` |
| 技能 | skill | 可动态注册进斜杠命令表的执行单元。 | `js/input/slashCommands.js` 的 `registerSkillCommands` |
| 插件 | plugin | 活动栏「插件」入口对应的扩展包管理面。 | `js/plugins.js` |

## 4. 前端模块域

域目录 = 从巨型文件拆出的**自包含族**的落点；巨型文件保留原路径，并用
`export { x } from './<域>/x.js'` 转发维持导出面（调用方零改动）。

| 域 | 内容 | 权威落点 |
|---|---|---|
| `js/chat/` | 聊天流的自包含簇（注入行、时长徽章、卡内 HTML、ask 气泡、折叠、pop 卡） | `src/main/resources/web/js/chat/` |
| `js/main/` | 装配根 `main.js` 抽出的族（后台代理、冻结、thinking 计时、权限 toast、历史指示） | `src/main/resources/web/js/main/` |
| `js/input/` | 输入族（附件、斜杠命令、输入模式） | `src/main/resources/web/js/input/` |
| `js/messages/` | 消息/好友族（时间格式、元素、好友信任、文案） | `src/main/resources/web/js/messages/` |
| `js/flowMap/` | 流程地图族（徽章、折叠） | `src/main/resources/web/js/flowMap/` |
| `js/sidebar/` | 侧边栏族（提供商 UI、预设管理、路径规则） | `src/main/resources/web/js/sidebar/` |
| `js/explorer/` | 文件树族（选择、文件规则） | `src/main/resources/web/js/explorer/` |
| `js/viewers/` | Canvas 查看器协议对象 | `src/main/resources/web/js/viewers/` |
| `js/locales/` | 界面文案表（`en.js` / `zh-CN.js`），键按域前缀组织 | `src/main/resources/web/js/locales/` |

## 5. 后端分层

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| 层位 | layer | 顶层包的层次序号，依赖只准向下；新包必须显式登记进层序。 | `scripts/check-scala-layers.mjs` 的 `LAYER_ORDER` |
| 底层 | bottom layer | `shared`、`actor` 两层：不得 import / 引用 `core`、`agent`、`gateway` 等。 | 同上（`BOTTOM`） |
| 根文件 | root files | 允许留在 `nebflow/` 根下的两个文件。 | 同上（`ROOT_FILES`） |
| 装配面 | assembly face | 最上层 `gateway`。 | 同上（`LAYER_ORDER` 注释） |
| 反向边 | reverse edge | 目标层位不低于自身的跨包**文件边**；出现即违规。 | `scripts/check-scala-layers.mjs` |
| 豁免 / 未豁免 | exempted / unexempted | 反向边经台账按 (文件, 目标包) 粒度豁免；门禁断言「实测反向边集合 ⊆ 台账」且台账只减不增。 | `scripts/layer-exemptions.json` |

## 6. 门禁与基线

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| 门禁 | gate | 一条可复跑的判定命令；战役里「一次改动 = 一批门禁全绿」。 | `scripts/check-*.mjs`、`scripts/check-*.sh` |
| 冻结基线 | frozen baseline | 前端类型错误按 (文件, TScode) 计数冻结；规则 = **只降不升**。 | `tests/type-baseline.json`；`scripts/check-js-types.mjs` |
| 重锚 | re-anchor | 用 `--update` 以当前树重写基线（用于分批拆分的合法迁移），必须与改动同批提交。 | `scripts/check-js-types.mjs --update` |
| 哨兵 Spec | source sentinel | 直接读 `src/main/scala` 或前端源码、钉住字面串的 Scala 测试；被钉的代码搬家时必须**重钉**。 | 例：`src/test/scala/nebflow/agent/InjectionSourceContractSpec.scala` |
| 冒烟 | smoke | 端到端的窄面验收脚本（WS 帧型、单场景）。 | `scripts/smoke-*.mjs` |
| 里程碑全量回归 | milestone full regression | 全量 `sbt test` 一轮，作为战役收尾的判定。 | `CONTRIBUTING.md`「Testing」段 |
| 身份门禁 | local-coupling gate | 「出货面不得携带贡献者本机身份串」的门禁；命中计数即 `ship_hits`。 | `scripts/check-local-coupling.mjs`；判据源 `scripts/user-knowledge-patterns.txt` |

## 7. 改名与协议红线

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| 唯一品牌事实源 | brand source of truth | 一切品牌名的唯一编辑点；改名 = 改此文件 + 跑 `scripts/rebrand.sh`。 | `brand.conf` |
| 兼容性承诺 | compatibility promise | 改名后必须持续成立的用户侧承诺（数据目录 / 环境变量 / 配置文件名 / 前端存储键 / 全局名 / 协议字段）。 | `REBRAND.md` 末节 |
| 双读回落 | dual-read fallback | 旧名与旧前缀在读取侧长期回落识别（如旧 `NEBFLOW_*` 环境变量）。 | `brand.conf` 注释、`REBRAND.md` |
| copy 迁移 | copy migration | 数据目录以**复制**（非移动）方式迁移，原目录保留可回滚，标记防重跑。 | `REBRAND.md` 末节 |
| 协议字段冻结 | protocol freeze | 设备互联的字段名与路径**不变**（存量配对设备零影响）——改名不得触碰。 | `REBRAND.md` 末节（D2 条） |
| 子系统名 | subsystem name | 设备互联子系统名保持 `neblink`，不随品牌改名。 | `brand.conf` 的 `subsystemName` |
| 凭据红线 | credential red line | VPS 凭据只存在于 gitignored 的交接文件中；任何代码 / 文档只允许引用其**路径**，一律不复制内容。 | `REBRAND.md`「凭据红线」段 |

## 8. 作者裁定用语

这些词在代码注释与提交信息里反复出现，是**流程语汇**（不是产品术语）。

| 术语 | 含义与用法 |
|---|---|
| 裁定 | 带日期的作者判定，写在代码注释里；命中处**不得改写或删除**，只能旁置新注。 |
| 并置注 | 追加式注释：旧注逐字保留，紧邻写新注，且同时含裁定编号与适用批次字样以便字面核验。 |
| 封存闸 | 默认关闭的「封存待启用」开关（`SEALED`）：主闸关闭时 `/` 当纯文本，仅白名单命令可达。权威落点 `js/input/slashCommands.js`。 |
| 铁律 | 战役内在脚本「执行员」与「审计员」两侧同时钉死的不可触碰条款。 |
| 作废路径 | 预注册的「做不成即判合格」出口：证据不足时降级或作废，禁止硬凑；硬凑即红线。 |
| 红线 | 字节稳定性约束（协议 JSON 逐字符、顶层挂钩逐字原位与时序零变化、裁定注释零改动）。 |

## 9. 易混对速查

| 易混 | 判据 |
|---|---|
| 左区 / 侧边栏 / 活动栏 | 左区 = 活动栏 + 侧边栏；活动栏不随侧边栏收起，它是左区里恒在的那条。 |
| 中央区 / 会话区 | 中央区含顶栏与输入区，不只是消息流；「会话区」若只指消息流，必须写「消息流」。 |
| 右区 / Canvas / `<canvas>` | 右区 = Canvas 面板；HTML `<canvas>` 元素只出现在输入区的麦克风光球上。 |
| 浮层 / 弹窗视图 / 模态 | 浮层是层名（不属于任何列）；弹窗视图是浮层里承载会话的那一类；模态是浮层里阻塞交互的那一类。 |
| Canvas 标签 / Canvas 查看器 | 标签是容器（可关可 pin），查看器是按类型渲染内容的实现。 |
| 主会话 / Nebula / 子代理 / 后台代理 | 主会话是**会话**（root）；Nebula 是根智能体的**名字**；子代理与后台代理是它派生的执行体（前者聚合在 Sub-Agents 面板，后者脱离当前轮存活）。 |
| 门禁 / 哨兵 / 基线 / 白名单 | 门禁 = 判定命令；哨兵 = 钉字面串的测试；基线 = 冻结的错误计数（只降）；白名单 = 环境性失败套件的登记表（战役期产物）。 |
| 内容档位 / 治理 | 档位管**采集时记什么**（Metadata / Content / Full 三档）；治理管**存后怎么处置**（脱敏 / 删除 / 留存采样）；两域互相独立，不得互换（契约 §3.3）。 |

## 10. 维护

- 新增术语：先确认它在仓内有唯一落点，再按上表格式补一行（术语 / 英文或代码拼写 / 一行定义 / 权威落点）。
- 改口径：**并置**新条目并注明日期与理由，原条目保留（与代码注释同款规则）。
- 术语与代码冲突时：以落点处的代码为准，同时把本表修订提交在同一批里。
- 本表不含一次性事实（版本号、提交哈希、行号、环境相关路径）；这类信息请写在对应的 Spec / PR 描述里。

## 11. 南北分离（B0 批次，2026-09-30 规划态）

> **批次说明（2026-09-30）**：本区词条统一为**规划态**——代码落点尚未成形，B3/B4 落地后回锚为仓内路径。
> **出处（2026-09-30 裁定链）**：bridge 归北 → capability-server 定名 → server 内含 engine → 四行拓扑（信息流向）→ app×wheel 模型。
> **与 §10 维护规约的冲突（并置注明，不删改上文）**：§10 要求「新增术语必须带权威落点」；本批是裁定先行的
> 规划词汇（先入表、后入码），仓内暂无版本化落点——裁定文本与 IR 标准母本均在 gitignored 的 `.zcode/plans/`
> （`../nebflow-src/.zcode/plans/north-south-architecture.md`），独立仓规范文本跨仓可寻址。落点列暂记规划去向
> 与规范文本现址，随批次回锚。

**五级词汇**（app×wheel 模型的词层；裁定称 GLOSSARY 为唯一术语源）：

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| app | app | 常驻进程原型：薄 main，只做装配与服面；既裁三例 = `gateway` / `capability-server` / `organic-server`。类比：C 实际运行的只有 main。 | 规划态：三例成形后回锚（B3/B4） |
| server | server | 角色名：某 app 的服务面角色。裁定：server 内含 engine 驱动（engine 不独立成 app）。〔2026-10-04 措辞修正：原句「engine 不独立成进程」——按 app×wheel 五级词汇改写（常驻进程原型＝app，「进程」泛称不在词层），裁定含义不变〕 | 规划态：B3/B4 回锚 |
| engine | engine | **执行面口径（2026-10-04 回锚，随 IR v0.3 批准批；欠账登记见 v0.3 §5.5）**：术语与解释器分立——解释器管「表面→IR」、执行引擎管「IR→效果」，engine 一词归执行面专用（bash/cmd/ps 引擎族、EngineManager、device 三元组 engineId）；**家族准入判据＝承接来自实体（agent/LLM）指令的面**，系统规律调用/被动后端＝非 engine。〔B0 原句（2026-09-30）并置保留：域语义：域驱动件（如 `mem-engine` / `skill-engine` / llm 运行时），被 server 装配驱动〕 | 口径 = `../skill-engine/docs/ir-v0.2-normalized-form.md` §3A-1（跨仓）；准入判据 = `../nebflow-src/.zcode/plans/trajectory-接口方案.md` A9 终裁（gitignored 计划侧，§11 头注同款现址记法）；代码落点仍规划态：B3/B4 回锚 |
| wheel | wheel | 物理形态：无 main 的库包；零依赖纯库 = 理想 wheel。`skill-engine` 与 `document-based-memory` 两独立仓已是 wheel 形态。 | 规划态；两独立仓现址 `../skill-engine/`、`../document-based-memory/`（跨仓） |
| 底座与契约面 | `shared` + `contract` | 底座（`shared`）与北向契约面（`contract`）：UiMessage 密封族等北向契约落此处。 | 规划态：`contract` 面 B5 析出后回锚；现 UiMessage 密封族在 `src/main/scala/nebflow/shared/protocol.scala` |

**组件词条**（app / wheel / 契约面已收于五级词汇表，不重复立目）：

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| capability-server | capability-server | 南面之一：ir 两平面（身份面 + 能力面）+ 执行围栏（sandbox / VFS / worktree）+ 能力后端域（tools / mcp / plugin / llm 运行时 / neblink / dropbox / usage）。 | 规划态：B3 新顶层域包 `nebflow.capability` 成形后回锚 |
| organic-server | organic-server | 南面之二：project / flow / entity / processor / agent / compact / scheduler / task / SessionStore。 | 规划态：B4 新顶层域包 `nebflow.organic` 成形后回锚 |
| mem-engine | mem-engine | 记忆域 engine（域驱动件），挂 capability-server 的 direct-call 面。 | 规划态：随 P1/B7 回锚 |
| skill-engine | skill-engine | 技能域 engine；独立仓，已是 wheel 形态（无 main 库包）。 | `../skill-engine/`（跨仓）；spec = `../skill-engine/docs/skill-engine-spec.md` |
| direct-call | direct-call | 应用间协议：IR 标准 §9 候审稿升格为总线；现阶段 = 包间协议（单 JVM 内），§5.5 loopback IPC 为未来物理承载。 | 候审稿 = `../skill-engine/docs/direct-call-spec.md`（跨仓）；dbm spec §5.5 = `../document-based-memory/docs/20260927_nebflow-sandbox.md` |
| function | `^engine:function` 的 function 分量 | ^ 词位（Subject 面）冒号后段分量：**engine 提供的功能点**——`^engine:function` 一体指定执行后端与被调用的功能点，两段保持（engine 分量与 device 三元组 engineId 不同值域）。**与 caps 分立**：caps＝信任档原子、function＝功能点（展开求值归注册面，随定稿批落定）。 | `../skill-engine/docs/ir-v0.3-command-surface.md` §3.11（裁定 §10-2/§10-3 Q2②，跨仓；2026-10-04 v0.3 转正批收录） |
| 四行拓扑 | four-line topology | 终态信息流向四行：north(web, cli, bridge, social)→gateway；gateway→organic-server & capability-server；organic-server→capability-server & shared；capability-server→shared；`mem-engine` / `skill-engine` 挂 capability 的 direct-call 面。 | 规划态：终态机器落点 = `scripts/check-scala-layers.mjs` 的 `LAYER_ORDER`（B1 起渐次成形） |

**大小写裁定（2026-09-30，B0）**：组件名全小写（`organic-server`、`capability-server` 等）；句首位置按英文惯例首字母大写。已核：两仓 src / scripts / tests / docs 内组件名 organic 零出现（seed 插件设计语料含英文语义词 organic，非代码标识符），无存量需扫。

## 12. 轨迹线（trajectory，2026-10-03 立项）

> **批次说明（2026-10-03）**：本区为 trajectory 线（agent 行为分析工具）既定词汇，契约槽⑧随小批齐落收录。
> 契约 v0.2 = 唯一权威源（跨仓 `../trajectory-store/docs/trajectory-contract.md`，镜像 `.zcode/plans/trajectory-contract.md`）；
> 接口方案 = `.zcode/plans/trajectory-接口方案.md`（含 2026-10-03 选择题批裁定记录）。
> 采集组件本体已裁住 `extensions/trajectory`（A1，2026-10-03；装配批①落码，现为规划态）。

**概念词条**：

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| 轨迹 | trajectory | agent 会话行为的结构化事件流：单一 `TrajectoryEvent` 信封（关联键组 + EventKind 密封族〔10 生效 + 1 预留〕+ 内容档位）；一切视图（轮次时间线 / 会话摘要 / 失败链）从事件流**派生**，非快照、非只读投影。 | 契约 §3（跨仓 `../trajectory-store/docs/trajectory-contract.md` v0.2） |
| 事件汇 | event sink | 采集 port：organic 各缝（轮次 / LLM 受理 / 供应商尝试 / 完成 / 失败 / 中止 / 工具 / 会话生命周期）上行的统一入口；内部有界队列 + 后台 fiber，**汇故障零打断现役行为**。 | 方案 §1（`.zcode/plans/trajectory-接口方案.md`）；组件本体 = `extensions/trajectory`（A1 裁定） |
| turn | turn | 轮次关联键：一轮用户输入到回合结束；turnId 由 TurnBoundary 侧产生，铸造 = `s"$sessionId:$counter"` 单点；无轮次上下文的事件（会话生命周期 / 北面直连）turnId 缺省。 | 契约 §4；方案 §4 槽 A4 |
| step | step | 步关联键：轮内**每次 LLM 调用**（send / sendStream）一步；供应商 attempt 不占步（挂单亲事件链）；stepId = `s"$turnId:$stepCounter"`。 | 契约 §4（裁定槽② 2026-10-03 转正）；方案 §4 |
| 内容档位 | ContentLevel | 采集内容三级档：Metadata（计数 / 时长 / 供应商 / 错误类别，零 PII，默认开）/ Content（消息文本与工具摘要，默认关）/ Full（完整原始负载，显式开）；**档位管采集、治理管存后**，两域不得互换。 | 契约 §3.3 |

**组件词条**（wheel 形态，与 `skill-engine` / `document-based-memory` 同款跨仓先例）：

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| trajectory-store | trajectory-store | A 轨**存储 wheel**（独立仓）：schema + 同步 port（治理四能力一等：redact / deleteSession 同步回执 / applyRetention / export）+ JSONL 落盘内存索引 + **conformance corpus 持有方**（两轨等价性语料）。 | `../trajectory-store/`（跨仓）；契约权威源 = 其 `docs/trajectory-contract.md` |
| trajectory-analysis | trajectory-analysis | B 轨**分析套件 wheel**（独立仓）：schema 独立镜像 + InMemoryTrajectoryStore + 缝适配器（纯函数，镜像输入类型）+ 分析视图（轮次时间线 / 会话摘要 / 失败链 / 供应商切换序列）；payload 唯一解释方。 | `../trajectory-analysis/`（跨仓）；README = 分歧裁决记录 |

## 13. fsm 接入线（fsm-runtime，2026-10-04 立项）

> **批次说明（2026-10-04）**：本区为 F 线（fsm-runtime 接入）词汇，随 F1 设计段落字——**规划态**，nebflow 侧零落码，落点随 F1/F2 批次回锚。
> 计划 = 跨仓 `../nebflow-src/.zcode/plans/fsm-runtime-integration.md`（gitignored；词条清单在其 §5）；实现锚 = fsm-runtime 独立仓 `../default/fsm-runtime/`（符号锚——行号与版本串是易漂事实，记在计划侧，不入本表）。
> **与 §10 维护规约的冲突（并置注明，不删改上文；同 §11 先例）**：本批裁定先行、先入表后入码，仓内暂无版本化落点；落点列暂记计划文本与 fsm-runtime 仓现址，随批次回锚。

**概念词条**：

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| fsm-runtime | fsm-runtime | 作者自写的编译计算图工具（独立仓，零第三方依赖），钩子子系统与 agent 运行面**新实现的统一底座**（2026-10-01 裁定「后续所有 hook 和 agent 使用这个工具实现」）。形态 = 编译期冻结图 + lint 唯一守门 + 确定性超步解释器 + A1-A4 字节等价契约 + canonical 文法；接入 = publishLocal 工件 + hash 钉（槽 E 终裁 a）。 | `../default/fsm-runtime/`（跨仓）；接入计划 = `../nebflow-src/.zcode/plans/fsm-runtime-integration.md` §1/§6.3 |
| 冻结图 | `FrozenGraph` / `Combinators.define` | 经 `Combinators.define` 在定义期定稿的**不可变**计算图：状态、转移与算子树此后冻结，运行期只解释执行；lint 是入图唯一守门。agent 三态（idle/processing/frozen）建模为图状态——**「冻结」指图定稿，非 agent 冻结态**（后者是运行态暂停，入图后是图的一个状态）。 | `../default/fsm-runtime/` Core.scala（`FrozenGraph`）；计划 §2.2 |
| 超步 | superstep | 确定性解释器的原子执行单元，亦是**逻辑时钟**单位（不引墙钟；墙钟 deadline 只能作外部输入注入）。一次 turn（LLM→工具→LLM 循环）= 超步序列，turn 边界 = 静默点（快照点）；rehook 前实例必须已跑过至少一个超步（日志含 StepMark）。 | `../default/fsm-runtime/`（`EventLog.appendSuperstep` / StepMark）；计划 §2.2/§6.5 |
| canonical bytes | canonical bytes | 快照与渲染的**规范化字节序**：同图同状态必产出逐字节相同的 canonical 文本；「同指纹必须同渲染」的载体——nebflow 黑盒快照测试对执行后端切换失明即靠此纪律。 | `../default/fsm-runtime/`（Snapshot / canonical 文法）；计划 §2.3 |
| 图指纹 | graph fingerprint / `graph_fingerprint` | 冻结图的**内容寻址**指纹（持久化六字段之首，CHAR(64) 主键）：同指纹 ⇒ 同图源渲染，冲突 fail loud 不自行合并；与 B8 成熟三件套注册面的内容寻址同构（远期可互认不绑定）。 | `../default/fsm-runtime/`；计划 §2.3（六字段） |
| 算子（十四） | `Operator`（14 cases） | RuntimeHook 的返回代数，**全集 14 例闭集**（加算子 = 改代数 = 走治理）：Pass（单位元）/ Drop / Defer / Rewrite / Synthesize / Veto（仅⑤）/ Force（仅③）/ Reweight / Tag / Allow / Ask / Deny（⑥ 词，消费位 = 宿主 `EffectRunner.verdict`）/ Wrap（载荷 WrapDecl）/ Patch（应用面 MetaHooks）。 | `../default/fsm-runtime/` Core.scala（`enum Operator`，14 case 2026-10-04 亲数）；计划 §2.1 |
| 席位 | seat / {Schedule, Commit, Enqueue} | 运行时钩子可介入的图内阶段，**收缩面仅三**：Schedule（③ 候选集）/ Commit（⑤ 待提交变更）/ Enqueue（② 排队）；HookStage 八阶段中其余不开（Lifecycle = 闭集占位）。席位 patterns 只匹配**图事件名** ⇒ 观察型/生命周期型事件入不了席，只有图内裁决点能入席。 | `../default/fsm-runtime/` Core.scala（`enum HookStage` / 收缩面检查）；计划 §2.1 |
| Tier-P | `HookTier.Pure`（纯档） | 钩子分档之纯档：**纯函数、无 IO、无墙钟**，违约 = fail loud；`RuntimeHook.invoke` 属 Tier-P，享受 A1-A4。nebflow 侧纯谓词（如 `FreezeSchedule.evalWithSkip`）可作 Tier-P。 | `../default/fsm-runtime/` Core.scala（`enum HookTier`）；计划 §2.2 |
| Tier-X | `HookTier.Extended`（扩展档） | 钩子分档之扩展档：有状态/有 IO 的效果域执行面（LLM/工具调用、外部子进程），**放弃 A1-A4**，重放走 playback「记录即事实」不重跑——只承诺已记录轨迹可重放，不承诺新跑可复现（槽 F 已裁：接受此成文语义）。 | `../default/fsm-runtime/` Core.scala（`enum HookTier`）；计划 §2.2/槽 F |
| playback | playback（记录即事实） | 重放机制：决策与效果**事实从事件日志回读**，LLM/工具/子进程不被再次调用；轨迹确定性是「日志充分统计量」意义上的确定（`Replay.replayManaged` 同日志 + 同版本定义/钩子完整重放）。 | `../default/fsm-runtime/` Replay.scala；计划 §2.2 |

**组件词条**（fsm-runtime 侧类型；nebflow 侧注入缝规划 = `SharedResources` 的 `fsmHooks` / `effectRunner`，B4 归巢时留——计划 §5.2）：

| 术语 | English / 代码拼写 | 定义 | 权威落点 |
|---|---|---|---|
| HookSet | `HookSet` | **实例级**运行时钩子集（私造走 build 的收缩面检查）；rehook = 逐实例手工换装面（最后一次换装胜）；rehook 须先跑过超步，hotSwap 换图后 hooks 不携带须重新 rehook；升版本走 rehook/restore 显式校验（HookVersionMismatch）。 | `../default/fsm-runtime/` Core.scala（`HookSet`）；计划 §2.1/§6.5 |
| HookLibrary | `HookLibrary` | **世界级**钩子库：宿主侧库，**不入快照不入日志，出生时物化**；继承 = scope 覆盖判定，屏蔽 = 同 id 深作用域覆盖浅作用域。装配点落在哪个 app 内是 hooks 归属裁定（槽 B）的直接后果。 | `../default/fsm-runtime/` Core.scala（`HookLibrary`）；计划 §3 |
| EffectRunner | `EffectRunner` | 宿主侧效果执行器，两组合子：`wrapped(decl, base)(f)` 包裹记录、`verdict(decl, base)(decide)` 裁决（词表 {Allow, Ask, Deny}：Deny ⇒ 投递 effect.denied、Ask ⇒ effect.ask）。**base 先求值** ⇒ verdict 保不住「工具零执行」，先裁后跑（PreToolUse 类）必须宿主自组 runner。 | `../default/fsm-runtime/` Interpreter.scala（`EffectRunner` / `wrapped` / `verdict`）；计划 §2.1 |
| WrapDecl | `WrapDecl(label, version)` | 效果声明（纯数据）：runner 以 `WrapDecl(label="llm"/"tools", version=…)` 声明；**prompt/模型变更 = 升 version，不静默替换**；快照 (hkm) 尾槽 + hook.manifest/hook.decision 审计。 | `../default/fsm-runtime/` Core.scala（`WrapDecl`）；计划 §2.2 |

## 14. 事件命名避让表（fsm Event 名铸造，2026-10-04）

> **批次说明（2026-10-04）**：本表是契约件（计划 §5.4，与槽 C 关联——先立 nebflow 侧表，B8 前再议升 contract 面）。范围 = AgentCommand/AgentEvent → fsm Event 名映射的铸造面；保留面取自 fsm-runtime 的 Lint 保留名闭集（计划 §6.4）；五项映射决策为 F1 设计段交接文本落字（机械核对输出录于 F1 设计文档 §2.4，该文档落盘后此处回锚出处）；**落码段 lint 实跑（define 断言）为权威门**。

**保留面**（禁用/避让清单）：

| 避让面 | 保留值 | 规则 |
|---|---|---|
| 保留事件名（六闭集） | `timer.fired` / `deadline.expired` / `join.completed` / `cancelled` / `data.ready` / `graph.completed` | 可作转移 on 的仅 {join.completed, deadline.expired, data.ready}（内核合成、有生产者）；其余三名 on 与 emit **双禁**；emit 侧六名全禁 |
| 保留端口 | `deadletter` / `system` | 端口声明即拒（deadletter = 自动建立的保留死信渠道；system = 系统渠道保留名） |
| 效果域保留名 | `compile` | CompileRuntime.runner 接管（运行时构图） |
| 算子保留名 | `noop` | 单位元算子名；非单位元算子取名 noop 即拒（K19） |
| 完成事件前缀 | `done.state.*` | 独立前缀名空间（整名禁 K1 / 前缀禁 K10） |
| 审计 marker 命名空间 | `op` / `op.ctx` / `hook.decision` / `hook.manifest` / `lifecycle.spawned` / `gate.paused` / `dead_letter` 等 | 字符串命名空间只增不删改 |

（锚 = `../default/fsm-runtime/` Lint.scala 的 `reservedEventNames` / `matchableReservedEventNames` / `reservedPortNames`，2026-10-04 亲验）

**映射决策**（F1 设计段，2026-10-04）：

1. **命名规则 = 小写点命名空间基名 + 分支后缀双名法**（如 `user.input.wake` / `llm.failed.freeze`）：后缀在铸造侧消歧，使图转移表 exact-match 单目标；dispatch 类统一 `.dispatch` / `.gated` 闸门后缀（`pipeLlmCall` 唯一冻结闸；压缩轮静态解析不过闸除外）。
2. **`AgentEvent.Cancelled` 必改**：朴素小写 `cancelled` 撞保留名（on/emit 双禁），定名 **`turn.cancelled`**，与 Completed→`turn.completed` / Failed→`turn.failed` 同族；不取裸 `completed`（与保留 `graph.completed` 邻近易误读）。
3. **冻结恢复驱动禁用 `timer.fired`**（保留名双禁，属宿主/timer 子系统）：CheckFreezeGate 定名 **`freeze.check`**（stay/resume/refreeze）。
4. **机械核对**：36 项词汇表零撞六保留名 / 保留端口 deadletter·system / 效果域 compile / 算子 noop / `done.state.` 前缀 / 自重复；朴素小写映射唯一撞名 = `cancelled`，证实必改；设计期静态核对（node 脚本），落码段 lint 实跑（define 断言）为权威门。
5. **避让面边界登记**：AgentEvent 三终态的图外名与 Ephemeral bridge 取消结算日志字符串（非事件名）分置，后者不改。

## 15. vm 词族（架构叙述词；规范词优先·triple 线回写，2026-10-07）

> 本族为**叙述词**：判据文本/裁定/命令面/代码标识一律用规范词；vm 名与规范词一一对应，冲突以规范词为准。出处＝triple 仓《术语重述-vm词族与防撞车》（2026-10-06；源提交 `b08886d`；裁定-二轮 §D 发令入册，执行＝主线批次 2026-10-07）。

- **vm:核心**（虚机核心）＝机器的特权面整体（装载＋特权检查＋能力表＋指令发射＋审计）；其内件与规范词对应：中枢／三检查点／注册面／执行编排／审计缝。
- **vm:设备**（device）＝一台**主机**及其经**驻机代理**抽象暴露的**能力集合**；既有「设备」概念（DeviceProfile/deviceId/跨设备执行）由此统一。
- **vm:驻机代理**（vm:supervisor）＝每主机一个的框架标准件：把本机能力抽象暴露给 VM，VM 只识别 supervisor 与其能力表示；与 BackoffSupervisor／责任链监督为**不同物**。
- **vm:终端**（vm:terminal）＝人面标准件＝**聚合者**（身份认证＋人类侧抽象＋GUI/TUI 挂接）；**非自身即交互门面**，各 GUI/TUI 是挂接其下的终端类型。
- **vm:程序／vm:指令／vm:装载器／vm:特权检查／vm:能力表／vm:系统调用／vm:核侧·设备侧**＝定义见源件 §3（分别对应：计划·图·配置集／IR 节点／中枢／三检查点／caps＋授权账本／direct-call／权限二分）。
- **B 级 lens 四枚**：vm:机器日志（＝审计缝）／vm:管理台（＝控制面）／vm:接入口（＝三体接入面）／vm:时钟（＝「语义不读环境」的时间注入源）。
- **撞车处置**：弃「内核」（撞种子 agent 名 `kernel`）；弃「用户态」（撞三体之「用户」）；「机」不作限定语（撞三体之「机」）；「核心」跨文档必须写限定形「虚机核心」。

## 16. 表述层（三体／双锥／业务环·治理环／中枢=解析面；架构叙述词·裁定轮收录，2026-10-07）

> **批次说明（2026-10-07）**：本区＝[10-07·裁定轮]㉖「表述层＝收录入 GLOSSARY」的落字；四组＝三体／双锥／业务环·治理环／中枢=解析面。
> **出处与状态**：chronicle/2026-10.md［10-07·裁定轮］＋`三体与解释器中枢提案-评估.md` §九（作者澄清）／§十（址：`../nebflow-ir/.zcode/plans/`）；状态＝已裁采纳（表述层收录，2026-10-07 裁定轮）。
> **与 §10 维护规约的冲突（并置注明，不删改上文；同 §11/§13 先例）**：本批裁定先行、先入表后入码；现址在 gitignored 计划侧（nebflow-ir/.zcode/ 已 ignore〔.gitignore:57〕；叙事稿/fact 在 nebflow-src/.zcode/plans/），随回锚。

- **三体**（人／LLM／机；别名：三体类、users／agents／computers）＝跨面叙述与命名轴：系统参与方三类——**人**（users）／**LLM**（agents）／**机**（computers，提案原话＝经典计算机）；三类按**调用通路判据**分野：人／LLM＝不规律调用者经路由，机＝系统算法决定的规律调用免经直调。三体各配一条统一类型化表示的适配通路（提案原话）：人→gateway、机→ir-router、LLM→fsm-runtime 的 Port；层图第①层「三体接入面」三线＝人：词位串→解析→结构化信封／机：IR 命令面＋规律调用直调免经／LLM：tool-call JSON≈恒等 lowering（效果面经 fsm Port 缝）。
- **双锥几何**（三角双锥；别名：三角双锥结构、双锥）＝系统组织的几何叙述（作者原话逐字，2026-10-06 晚）：「这里的图像是一个3角双锥结构。中心赤道面是agents，users，computers。整个几何体中心是解释器作为可编程业务调度枢纽。上顶点是探针面，下顶点是控制面。」——赤道面＝三体（agents／users／computers）；体心＝解释器（＝中枢，可编程业务调度枢纽）；上顶点＝探针面（采集·读数）；下顶点＝控制面（授权·判定·动作）。
- **业务环／治理环**（两个正交环）＝双锥几何的直读叙述词：**业务环**＝三体 ⇄ 中枢（解析→成形→路由出去→执行→回执），频率高、面宽；**治理环**＝探针（采集）→判定→动作→回读（闭环五守则：判定滞回／动作冷却／防震荡阶梯／回读确认／执行传感分离），传感腿永不发起控制动词。正交性的落点：业务环上的节点（解释器层）不在治理环的判定/执行链条上；治理环的两个顶点（探针面／控制面）不承担业务。
- **中枢**（解析面；别名：解释器中枢）＝解释器层的叙述名＝**可编程业务调度枢纽**：职责＝解析与调整通信；「计算」＝**解析通信中本身带有可解析的信息**（北侧命令＝「解释器可解析的信息＋路由出去的信息」两分解）；**不是中转面**——接线动态变化（解释器本身＝可变应用逻辑）。**≠控制面**：控制面不负担业务（控制动词不含业务动词，CE-P2 同向）；中枢不执行、不做准入（三检查点在解释器之外、原地不动）。

## 17. 损坏处置（triple 线口径；裁定轮发令入册，2026-10-09）

> **出处注记（跨仓搬运；2026-10-09 补）**：三词条源＝triple 仓 `docs/fact/损坏处置清单-2026-10-08.md` §1（定义逐字；源提交 `24edcee`，末次触及 `e0d7967` 未动 §1）；定义正本＝该线《评估-灾难恢复能力-2026-10-07.md》§5.3（行 321–323）；入册令＝《裁定-十轮-2026-10-08.md》§B-8（作者原文「三枚入册（推荐）」）；执行＝主线批次 2026-10-09。
> **本节引称**：「清单」＝`../triple/docs/fact/损坏处置清单-2026-10-08.md`；「十轮」＝`../triple/docs/fact/裁定-十轮-2026-10-08.md`；「五轮」＝`../triple/docs/fact/裁定-五轮-2026-10-07.md`；「DR」＝`../triple/docs/chronicle/评估-灾难恢复能力-2026-10-07.md`。
> **与 §10 维护规约的冲突（并置注明，不删改上文；同 §11 先例）**：本批裁定先行、权威落点在跨仓（triple）——出处注记含提交 hash／行号属并置例外；落点列照记跨仓现址，随真源面落位回锚。

**三枚（定义逐字沿用；分配依据＝可再生性，五轮 §C-P4 逐字「按可再生性分配」）**：

- **显式拒绝**（fail-closed）：**不可再生的真源**（权威定义面、正式账本）；
- **隔离留档**（改名保内容＋按缺席处理）：**含不可再生内容但可在缺失下运行**的件；
- **记账降级**（WARN＋空/缺省起步＋计数）：**可重建的缓存/派生面**。

**分野（与「无档」「降级冷启」区分）**：三枚归座的对象是「**坏**」——**存在但读不出**（清单 §4.7 表头）；「**无档**」＝「**空**」的档案面形＝**合法未配置**（该会话尚未被 archive 过；五轮 §A-①）⇒ 走「**降级冷启**＋显式标注」；**不得**由坏档静默降级为无档（清单 §2 行 A1；**空 ≠ 坏**，DR §5.3）。

**权威落点**：「清单」§1（三枚定义）／§2（逐件归座表：①行 D1–D3/E3（类别已定·但本仓未落）/F1/F3/F4、②行 A1/A2/B1、③行 F2）／§4.7（空≠坏可辨位）；入册令＝「十轮」§B-8。

## 18. 能力两面分野（需求／持有／供给；triple 线回写，2026-10-09）

> **出处注记（跨仓搬运；2026-10-09 补）**：三词条源＝triple 仓 `docs/fact/术语重述-vm词族与防撞车.md` §8（依《裁定-十六轮》§C-1；第三枚「供给」依《裁定-十九轮》P-13 增；源提交 `ba04f4e`）；执行＝主线批次 2026-10-09。
> **本节引称（锚列路径基；2026-10-09 补，依 triple 线精度建议）**：「锚」列除注明者外均为**triple 仓内相对路径**（基＝`../triple/`）；`ir-v0.3`＝`../skill-engine/docs/ir-v0.3-command-surface.md`；`adjudications.md`＝`../nebflow-src/.zcode/plans/fact/adjudications.md`（工作树；行号以实测为准）。
> **与 §10 维护规约的冲突（并置注明，不删改上文；同 §11/§17 先例）**：本批裁定先行、权威落点在跨仓（triple）——出处注记含提交 hash 属并置例外。

| 词 | 定义一句 | 真值源（谁说了算） | 锚 |
|---|---|---|---|
| **能力需求**（capability **requirement**） | 命令／引擎**声明**的「执行它需要什么能力」——**是声明，不是持有位** | 命令 caps 声明（`FsRead`／`FsWrite(prefix)`／`Net`／`Exec`／`Secret`／`MemoryWrite`）＋ engine manifest **自述** | `ir-v0.3` §8.1；`docs/fact/答复-身份与权限四问（参考Linux）.md` §1.4 |
| **能力持有**（capability **holding**） | 账本记的「**谁实际拥有**什么能力」——**持有面的唯一真值源** | grants／超眼表／caps 策略／trust 档（真源＝`registry/` 文件族；**单写通道、禁自授、可审计**） | `adjudications.md:141`〔工作树，行号以实测为准〕；`设计-terminal-2026-10-07.md` §4.2 |
| **供给**（provisioning） | **内核 → 模块**的**能力面编排**（＝把哪些内核函数放进某模块的**导入表**）；**不是**「对主体的授权」 | 三类表面**共用** `registry/` 账本本体（「分的是来源与表面，**合的是本体**」）；**授能记录的作用域＝主体粒度＋scope＋时效**（《裁定-十九轮》P-01） | 《裁定-十九轮》§B3·P-13；《十七轮》B1（副作用只住用户件）；`docs/fact/设计-权限与能力供给（Linux对位）-2026-10-09.md` §5 |

**纪律句（同条；防实现混用）**：**需求 ≠ 持有**——判定＝`decide(需求, 持有)`；**禁**把 **manifest 自述当持有**、**禁**把**账本条目当需求**。

**分野句（第三枚；《裁定-十九轮》P-13）**：**提权 ≠ 供给**——**提权＝对主体的授能写**（账本动作者；`sudo`/`su` 形）；**供给＝内核→模块的能力面编排**（**导入表**；住**同一账本本体**，但**表面不同**）。另：本线已裁 **DAC 作基线 ＋ grants 作增量**（判定＝与关系）⇒ 读「持有」时须分清**基线（owner）**与**增量（grants）**。

**分隔线句**：「本两枚为**两面分野**词条——Linux `capabilities(7)` 在本线**被拆成两面**：**判据面看需求、持有面看账本**；与既有『一物一名』纪律相容（**两面＝两物**）。」

**依据**：《`docs/fact/裁定-十六轮-2026-10-09.md`》§B（设计约束令）＋§C-1（连带裁定）；来源＝作者 2026-10-09 提问「尽可能的使用Linux的权限控制概念以及能力供给方式」＋确认句「确认。」。
