---
name: stand-dev
description: stand 本体开发六章总纲——方法章（需求拆解/技术调研/trade-off/并行调研）、设计章（UI/UX 设计规格书/视觉评审）、前端章（vanilla JS 规范/Sapphire Glass/视觉裁定铁律/范式案例库）、后端章（Scala actor 分层/sbt/prompt cache/工具设计）、qa 验证章（五维审核/隔离冒烟/自验 harness/验红实证）、引擎章（合并发版审查/技术文档/提示词工程/插件封装）。Use when 开发 stand 本体：需求拆解、技术调研与选型、写设计规格书、视觉评审、改 web/ 前端、写 src/main/scala/ 后端、交付前验证、合并分支、发版、写文档与系统提示词、封装插件；按任务域进对应章读 references 深件。
audience: nebflow-project
language: zh
status: active
---

# stand-dev — stand 本体开发六章总纲

> 本文件是 stand-dev 包的**内容权威导览**（按需查阅件）：六章结构 + 每份 references 的核心指令摘要。深细节一律进 `references/<章>/<件>.md`（原八包 SKILL.md 全文迁入，能力零丢失）。与 Plugin.md 不一致时以本文件与 references 为准。

## 0. 怎么用本包

1. 按任务信号选章（§8 决策树）；2. 读本文件对应章的**核心指令摘要**（执行级口径）；3. 按摘要内指引 Read `references/` 深件（结构/清单/范例/证据全在里面）；4. 章间配合关系：选题与方案（方法章）→ 设计先行（设计章）→ 写（前端/后端章）→ 自验+验收（qa 章）→ 审查/合并/发版（引擎章）是关卡顺序，不跳关。

## 1. 方法章（需求与调研域）

> 原 `engineering-methods` 包（2026-11-01 二批并入）。核心问题：**需求怎么拆、方案怎么选、证据怎么查？** 深件目录 `references/methods/`。

### grill — 需求拆解与技术选型（双文档交付）
- 需求模糊**先提问不猜**（四场景：需求模糊/关键选型/发现风险/范围蔓延）；关键选型给 2-3 方案 + trade-off，每方案优点与代价并陈；只推荐一个方案不给对比是失职。
- 调研四维：性能/复杂度/生态/成本（按项目相关性选取）。
- 响应三级：快速咨询（表格对比直接答）/ 局部分析（聚焦+推荐）/ 完整架构（**双文档交付**：`_ARCH_SUMMARY.md` 给人类讲「为什么」+ `_ARCH_SPEC.md` 给工程 Agent 无歧义实施）。
- 输出风格：表格优先、代码即文档、有 trade-off；**不写生产代码**（示例代码/接口定义/类型签名除外）；架构图用 graphviz/mermaid 落盘，禁 ASCII 画图。

### parallel-research — 并行调研纪律
- 四步：**正交拆题**（子题互斥覆盖完整、每子题一句范围声明）→ **并行深挖**（每论断带来源链接、≥3 独立来源、一手优先、事实与解读显式分开）→ **逐轨核验**（核验者亲自重访来源：可达性/内容匹配/一致性，VERIFIED/FAILED/UNCHECKED 逐条定性）→ **双轨综合**（FAILED 剔除、UNCHECKED 标注、来源清单全覆盖、不做新调研）。
- 报告结构：Executive summary → 主题章节（跨轨组织）→ Confidence & gaps → Sources。

### 章间衔接
- `grill` 的 `_ARCH_SPEC.md` 是**后端章/前端章**实现的输入；实施后交付把关移交 **qa 章**（方法/验证分工）；合并走**引擎章** merge-pipeline、审查走引擎章 review。
- `parallel-research` 是 `grill` 调研阶段的证据链升级路径；落盘路径与**引擎章** nebflow-docs 产出路径纪律同口径。
- 代码库现状探索与实施方案设计另配包外 `explorer-toolkit` 插件。

## 2. 设计章（UI/UX 设计域）

> 原 `design-spec` 包（2026-11-01 二批并入）。核心问题：**新 UI 怎么出规格、怎么验收？只设计不实现。** 深件目录 `references/design/`。

### design-spec — 设计规格书与视觉评审（四道防线）
- **角色边界**：设计方只出规格书/评审报告/案例库条目，禁改产品源码；实现归前端章；功能验收归 qa 章；视觉评审归设计方（QA 不重复做）。
- 防线 1 **设计先行**：必读前端章 `visual-style`（铁律）+ `design-system`（案例库）→ 按功能类型检索 1-2 个权威范式（HIG/Material/APG 等，检索源清单见深件）→ 按模板 §0-9 写规格书（落项目人读件目录 `docs/<域>/`，头部状态 draft→frozen→dispatch→shipped，禁 /tmp）→ 展示确认后**冻结**。
- 防线 2 **规格化实现**：规格书是实现的唯一依据，未定义之处指示实现方回问，不自由发挥。
- 防线 3 **双重验收（视觉评审）**：隔离实例截图（亮/暗主题、响应式断点、关键状态）+ vision 对照规格书逐项核对 + DOM 断言交叉验证，**绝不裸看图下 PASS/FAIL**；结论二值化附证据清单。
- 防线 4 **教训沉淀**：打回 UI 归因四类（裁定冲突/缺参考/规格缺失/实现走样）入前端章 design-system 案例库。
- 模板 §0-9 速查：目标/参考带链接/布局与信息架构/交互状态机/动效规范/视觉规格全 token/边界与异常/无障碍/**可断言验收点 ≥5 条二值（qa 转 Playwright，口径写规格时锁死）**/参考链接。

### 章间衔接
- 与**前端章**：design-system 是防线 1 内部先例来源 + 防线 4 沉淀载体；visual-style 铁律与外部范式冲突时终裁。
- 与 **qa 章**：nebflow-qa-frontend 只做功能验收不重复视觉评审；规格书 §8 可断言点由 qa 转 Playwright 断言，口径含糊回问设计方。

## 3. 前端章（web/ 实现域）

> 原 `frontend-dev` 包。核心问题：**web/ 页面怎么写才对？** 深件目录 `references/frontend/`。

### nebflow-frontend — 实现规范
- 技术标准：无构建 vanilla JS（无 npm 依赖、无 bundler）；文件结构与模块化纪律。
- **Sapphire Glass 设计系统**：CSS 变量双主题，禁裸 hex 一律 `var(--color-*)`；玻璃组件规范。
- 设计原则（层级/间距/字重/一致性/打磨）；**硬约束**（无 emoji、无 accent bars 等，违反即 bug）；截图驱动验证流、响应式三档。
- 实现 以上游设计章 design-spec 的设计规格书为唯一依据（规格书未定义处回问）。

### visual-style — 用户视觉裁定铁律（最高优先级）
- 弹窗**禁 overlay 遮罩**但面板必须毛玻璃；可交互控件统一玻璃质感；中度字重；克制的专业感。
- 与任何规范/范式冲突时**以本件终裁**。

### design-system — UI/UX 范式案例库（软参考）
- 功能类型 → 成熟范式（微信/Spotlight/HIG）→ Nebflow 适配规则 → 可断言点 → 踩坑记录。
- 新 UI 设计/评审前检索同类案例；闭环教训按模板追加。**软参考**：冲突以 visual-style 与用户裁定为准。
- 设计规格书的产出方法（四道防线/模板 §0-9）见设计章 design-spec；本库是防线 1 先例来源 + 防线 4 沉淀载体。

## 4. 后端章（Scala 开发域）

> 原 `backend-dev` 包。核心问题：**后端代码怎么写/怎么优化/工具怎么设计？** 深件目录 `references/backend/`。

### nebflow-backend — 技术栈与编码铁律
- 技术栈：Scala 3 / 自研 actor / http4s / cats-effect。
- **架构铁律**：Actor 与 IO 分层（互不越界）；编码规范：最小改动、向后兼容。
- sbt 实践（`-batch`/锁冲突/注释坑）；worktree 纪律；交付口径（自测后交 QA，不自行合并）。
- 上游衔接：方案与技术选型方法见方法章 `grill`（`_ARCH_SPEC.md` 是实现输入，任务清单即验收对照物）。

### nebflow-cache — prompt cache 命中率
- 指标定义（cache_read/cache_write）与数据源（router 日志/usage-records 五维聚合）。
- 已知事实：间隔悬崖衰减、冷启动路由；**改 system prompt 前缀前必读**（任何前缀改动使全部 agent 共享缓存失效）。

### nebflow-tool-dev — 工具系统设计
- 工具管线：schema → 权限 → 校验 → 执行 → 结果格式化；内置 Scala 工具与外部 ScriptTool 架构；权限模型。
- **六条设计原则**：最小参数面/可描述错误/结构化结果/向后兼容/schema 即文档/按角色分化先走定义层；新建工具先查既有工具能否扩展。

## 5. qa 章（验证域）

> 原 `qa` 包。核心问题：**这交付物能不能合？** 只验不改。深件目录 `references/qa/`。

### nebflow-qa-backend — Scala 后端五维审核
- 每轮交付**五维全覆盖**：① `sbt compile` ② `sbt test`（锁冲突错峰重试）③ 验收条件逐项核对 ④ 代码质量三维（架构/质量/安全运维）⑤ 回归验证。
- **运行安全红线**：不打宿主 gateway 端口主意、kill 前 PID 验身、绝不杀未知 sbt/java。
- 报告格式 ✅/❌ 逐维结论 + 验收条件逐项 + 问题清单（仅 FAIL 时）；只审不改。

### nebflow-qa-frontend — 前端合并回归
- **隔离真实例三步验证缺一不可**：起隔离 sbt 实例 → 跑 C2 Playwright 冒烟套件 → 资源可达性实测（含动态 import 链）。
- **禁替身规则（CRITICAL）**：静态服务器/手造 HTML 替真实例 = 验证无效；FAIL 三条款任一即 FAIL。
- 契约静态审核：双端（Scala 路由 ↔ web client）四维比对。
- 与设计章分工：视觉评审归设计方，本件只做功能验收；断言口径来源是设计规格书 §8 可断言点。

### frontend-verification — 实现侧自验 harness
- 无后端 smoke harness + `data-*` 断言契约；**wire-probe 侦查法**查「功能没接通」类 bug；contract-test-harness 验 API client 契约。
- 分层验证顺序、Playwright 高频坑、静态 serve 验收、QA 视角盲点补验、进程清理纪律。

### isolated-smoke-verification — 隔离实例冒烟
- 临时 `NEBFLOW_HOME` + 独占端口 + mock 路由起隔离实例；断言锚定**持久化产物**而非易失 stdout。
- 沙盒泄漏三信号三角定位；kill 前 PID 验身；Delegate 极简内核链验证需在隔离 home 内存在 kernel def。

### verification-rigor — 验证严谨性
- **验红实证**：修复后必证 spec 真红过（变异安全）；**spec 载力**：防巧合绿；字段改名四面验证。
- sbt 挂起/OOM/异步 hang 排障；日志断言韧性。

## 6. 引擎章（工程纪律域）

> 原 `docs-prompt` + `pipelines` + `nebflow-plugin-creator` 三包。核心问题：**合并/发版/审查/文档/提示词/插件封装怎么把关？** 深件目录 `references/engine/`。

### merge-pipeline — Git 合并纪律
- 四步：**扫描**（commit/文件/冲突/测试四事实）→ **评估**（go/no-go 三判据全过才可合并）→ **执行**（`--no-ff` 无条件强制；简单冲突自行融合核对两侧语义，复杂冲突中止上报）→ **报告**。
- 红线：全程不 push 除非明确指示；有问题的合并不硬修，blocked 如实上报。

### release-pipeline — 发版纪律
- 分支与版本模型：VERSION 文件单一版本号来源（semver）；主线/beta/release 分工。
- **四角色闭环**（审→做→包→复审）；tag 必打在 release 分支的 merge commit 上；三平台打包验证；上传 gated（永不自动 push/publish）；低频高危，执行前通读验证清单。

### review — 三维质量审查
- 工程哲学三条：先理解再行动 / 能删则删 / 保持简单；核心原则：功能保持。
- **Phase 0 自动化门禁**（compile/test/格式/lint）未过不进人工审查；Phase 1 变更分析 + 三维深度评估（架构/质量/安全运维，各 1-5 锚定）；Phase 2 问题报告（Critical/Warning/Suggestion）；Phase 3 check-fix 循环 ≤3 轮。
- 审查范围含合并后 diff 审查；前端审查按「前端适配」段替换工具链与检查项。

### nebflow-docs — 技术文档域
- 写作规范：准确/简洁/结构化/示例驱动/不过度展开；中英双语同步；导航结构维护。
- **文档一致性联动**：代码改了文档跟着改；产出路径纪律（方法章 parallel-research/grill 落盘同守本节纪律）；图文报告图片内嵌规范（执行面副本与本节同步维护）。

### nebflow-prompt-engineering — 提示词工程
- 六核心原则：唯一解析/渐进披露/token 效率/结构化/行为性/示例优先；提示词解剖结构。
- 编辑既有提示词的纪律与反模式；工具描述写法（工具面按角色分化：先定义层再提示词）；agent 定义周期体检清单。

### plugin-packaging — 插件封装主流程
- 四步管线：判定输入形态（迁移/MCP/全新三态）→ 能力域分析与 skill 拆分 → 逐件生成（manifest/skill 目录/mcp.json/Plugin.md）→ 落盘即生效。
- **机械自检**：`python3 ${SKILL_DIR}/scripts/validate_plugin.py --profile creator <包目录>`，FAIL 自修重跑 ≤2 轮，仍 FAIL = blocked 上报；**落盘后冻结目录**。
- 六分叉处置表（同名冲突/digest 漂移等）；终检报告模板（包结构/来源/digest/停止手段）。

### description-quality — manifest 描述规范
- **五段式 description**：定位句/适用段/内含 skills 段/边界段/组件面收尾句；≤400 字符；触发词核心词表圈定法（字面匹配，近义词不兜底）。
- M1-M15 判定口径速查 + 好坏范例四例；独立的描述体检任务按本件执行。

## 7. 脚本与资产

- `scripts/validate_plugin.py` — 包校验（M1-M15 + C 组、三档 profile、digest 复算），plugin-packaging 第五步使用。
- `assets/manifest.template.json` / `assets/pluginmd.template.md` / `assets/skill-template/` — 封装起步模板。
- 以上自原 nebflow-plugin-creator 迁入（引用链最短取舍，见 Plugin.md §7）。

## 8. 选章决策树

- 任务是**定方向**（需求拆解、技术选型、架构设计、方案对比）→ **方法章** grill。
- 任务是**查证据**（多视角、来源可验证的深度调研/文献调研/事实核查）→ **方法章** parallel-research。
- 任务是**出设计**（新 UI/交互要设计规格书；实现完成要视觉评审/设计走查）→ **设计章** design-spec。
- 改 `src/main/resources/web/` 页面/组件/样式 → **前端章**（visual-style 终裁视觉冲突）。
- 写/改 `src/main/scala/`、调 flow engine、设计工具 → **后端章**。
- 交付前验证、verify 节点、验收核对 → **qa 章**（后端交付→nebflow-qa-backend；前端交付→nebflow-qa-frontend；修 bug→verification-rigor 验红）。
- 合并分支 / 发版 / 代码审查 → **引擎章**（merge-pipeline / release-pipeline / review）。
- 写文档 / 改系统提示词 / agent 体检 → **引擎章**（nebflow-docs / nebflow-prompt-engineering）。
- 封装插件 / 写 plugin.json / 描述体检 → **引擎章**（plugin-packaging + description-quality）。
- 官网 mystand.space → `website` 插件；通用 Office 文档 → `document-production` 插件；代码库现状探索与实施方案 → `explorer-toolkit` 插件（不在本包）。

## 9. 维护约定

- references 21 件 = 原八包 skill 全文（一批 2026-10-31 六包：qa/frontend-dev/backend-dev/docs-prompt/pipelines/nebflow-plugin-creator，原包已卸除；二批 2026-11-01 两包：engineering-methods/design-spec，原独立包实例侧仍装载未退役——退役与否等作者令）。改章内容同步本文件摘要口径。
- 双副本纪律：仓内 seed（`src/main/resources/seed/plugins/stand-dev/`）与运行时（`<data_root>/plugins/stand-dev/`）逐字节同步。
- 章内 red lines（运行安全/不 push/上传 gated/落盘冻结/只设计不实现/无链接不作论断）任何改动不得稀释。
