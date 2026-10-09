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
| server | server | 角色名：某 app 的服务面角色。裁定：server 内含 engine 驱动（engine 不独立成进程）。 | 规划态：B3/B4 回锚 |
| engine | engine | 域语义：域驱动件（如 `mem-engine` / `skill-engine` / llm 运行时），被 server 装配驱动。 | 规划态：B3/B4 回锚 |
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
| 四行拓扑 | four-line topology | 终态信息流向四行：north(web, cli, bridge, social)→gateway；gateway→organic-server & capability-server；organic-server→capability-server & shared；capability-server→shared；`mem-engine` / `skill-engine` 挂 capability 的 direct-call 面。 | 规划态：终态机器落点 = `scripts/check-scala-layers.mjs` 的 `LAYER_ORDER`（B1 起渐次成形） |

**大小写裁定（2026-09-30，B0）**：组件名全小写（`organic-server`、`capability-server` 等）；句首位置按英文惯例首字母大写。已核：两仓 src / scripts / tests / docs 内组件名 organic 零出现（seed 插件设计语料含英文语义词 organic，非代码标识符），无存量需扫。
