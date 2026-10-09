# slideblocks — 插件使用指导（Plugin.md）

> 本文件是 slideblocks 插件的**包根使用指导件**（Plugin.md）：装载器不注入本文件、不参与选配；维护者与按需 Read 的节点/用户以此为包内地图（用户＋模型可见的按需 Read 面）。选配判定只看 plugin.json 的 description；作业指令在各 `skills/<skill>/SKILL.md`（spawn 时全文注入）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`，该替换仅发生在 SKILL.md 注入文本）。

## 1. 速览

slideblocks 是 Slidev 演示全流程制作包：节点自主从「简短请求＋混合素材」（PDF、Word、网页、Markdown、表格、图片、品牌资产、PowerPoint 源 deck）制作完整、可运行、可编辑的 Slidev 演示，每个成品项目交付经过验证的自包含 `offline.html`（`file://` 直接打开）。内含 1 个 skill：

- `skills/slideblocks/`——制作纪律全量：八阶段工作流（路线判定 → 内部完整 deck 设计 → Blocks/Decks/Recipes 选配 → 资产收口与执行锁 → 项目实现 → 静默视觉精修 → 离线/导出验证 → 交付）、Registry 资产门控选配、图表三路由（FigureSpec/DiagramSpec/Mermaid）、演示者工作台接线、QA 与交付校验；携带 21 件 references 按需 Read 面、交付客户端与结构校验脚本、离线构建器与图表引擎资产。

**包纪律一句话**：用户素材与既有 deck 是真相源——不发明证据、指标、日期、引语与客户主张，缺失事实与资产显式标注；**格式边界只进不出**：最终交付物要求 PPT/PPTX/Office 文件时说明边界并停手，PowerPoint 只作只读迁移输入或视觉参考。

## 2. 安装与启用

- **安装**：本包已退出实例默认预装清单（不再随新实例 seed 自动安装）；包体仍在 Nebflow 分发树与既有实例中——已装实例在位即用；新实例走**手工安装**：自分发树获取整个包目录，拷入实例的插件目录即落位生效（拷包时连同本文件一起带走）。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（做 PPT、写演示、出幻灯片、制作 slides、按材料生成演示、迁移到 Slidev、改进已有 deck 等），派发时 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/slideblocks/revoke` / `nebflow plugin revoke slideblocks`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载；新内容对新派发即刻生效）。

## 3. 目录结构

```
slideblocks/                              （包根）
  plugin.json
  Plugin.md                               ← 本文件（包根使用指导件：速览 / 安装启用 / 工作流导览 / 总目录 / 资产登记）
  skills/
    slideblocks/
      SKILL.md                            ← 制作纪律全量：八阶段工作流＋Reference routing（注入面；英文原件）
      delivery-manifest.json              ← 上游自带交付协议元数据（功能件·非残留：slideblocks-client.mjs 客户端功能性读取）
      references/                         ← 按需 Read 面（21 件，索引见 §5）
      scripts/
        slideblocks-client.mjs            ← 交付客户端（无 launcher 时的 <slideblocks-client> 实现）
        validate-slideblocks-project.mjs  ← 交付前结构校验（确定性检查，不替代视觉 QA）
      assets/
        build-offline.mjs                 ← 离线单文件构建器（逐字节拷入项目）
        workbench/                        ← 演示者工作台 9 件（接线/拷贝，见 §7）
        diagram/                          ← 图表引擎 4 件（拷入项目使用，见 §7）
```

> skill 内另携带上游自带物料（LICENSE、agents/openai.yaml），不画入工作面树——登记见回报件异常申报节。

## 4. 包级核心用法（八阶段工作流速查）

> 全量口径以注入的 `skills/slideblocks/SKILL.md` 为准（含 Portable client runtime 节）；本节是管线速查。

| 阶段 | 做什么 | 关键判据 |
|---|---|---|
| ① 路线判定与素材归一 | 内部选路线（新 deck / 既有 Slidev / PowerPoint 迁移 / 续作），不问用户要路线；盘点分类全部素材 | 素材只读；建 `.slideblocks/source-map.md`＋`workflow-state.json`；候选视觉载体先给稳定 `A<NN>` 资产行 |
| ② 内部完整 deck 设计 | 推断受众/目标/叙事弧/交付语境；定精确页面名册与逐页职责；选一个最强端到端视觉方向（不出备选除非用户点名） | 专用 briefing 按主听众任务路由（系统性技术简报 / 技术深潜 / 研究证据简报）；`.slideblocks/page-plan.md`＝内部设计权威，非审批闸 |
| ③ Registry 选配 | Blocks/Decks/Recipes 发现与门控决策 | live Catalog 优先（每次 ≤3 语义检索 ≤10 秒），不可用即落快照兜底并记录；候选按语义/证据匹配排序，preview 签名只用于存活候选；custom 页可以全胜，不硬凑 Registry 复用 |
| ④ 资产收口与执行锁 | 逐资产定性（ready / approved placeholder / rights-review / blocked）；建 `.slideblocks/execution-lock.json` | 每个选中的代码类图表资产锁一个 `renderRoute`；资产 ID 与路由经 `data-slideblocks-*` 属性落到载体上，静态校验与渲染 DOM 查同一资产 |
| ⑤ 项目实现 | 建/改 Slidev 项目：工作台接线、拷入离线构建器与验证器、逐页实现 | `build-offline.mjs`/`verify-runtime.mjs` **逐字节**拷入项目；每页恰一枚 `<!-- slideblocks-page: P<NN> -->` 标记；图表走 §7 三引擎纪律 |
| ⑥ 静默视觉精修 | 起托管 dev server，真实渲染视口逐状态检查（层级/溢出/碰撞/资产行为/console） | 构建成功≠视觉验收；证据槽逐像素复核（去掉标题与叙述后载体仍须独立通过验收测试）；完整 deck 由未参与制作的评审者以观众身份拒审不合格页 |
| ⑦ 离线与导出验证 | `offline.html` 单文件拷到他处经真实 `file://` 复验；断言零外部请求；reduced-motion、打印/静态导出逐项验证 | 只有单独构建且经浏览器验证的 `offline.html` 承诺双击直开；普通 `dist/` 不是直开产物；最后一次改动后所有导出须重新生成复验 |
| ⑧ 交付 | 跑结构校验脚本；对齐 page plan / 执行锁 / QA 证据 / 构建回执 / 离线产物 | 校验脚本＝确定性文件与配置检查，不替代视觉 QA；占位、缓决与已知限制如实列出；先给用户看审阅后的首稿 |

- **Portable client runtime**：`<slideblocks-client>` 从技能所在目录解析——有 `slideblocks` launcher 用之，否则 `node "skills/slideblocks/scripts/slideblocks-client.mjs"`；技能激活不得改 PATH/装运行时/自拷到别的 Agent 目录，Node 不可用则跳过 live 客户端动作、用内置 Registry 快照并如实报告。
- **匿名交付边界**：公开 Catalog 检索与已发布 Artifact 取回不需要账号；匿名交付上下文、token 与签名 URL **绝不**写进 deck、项目文件、共享日志或交接产物。
- **与其他包的分工**：HTML 卡片与社交图另配 `design-cards`；deck 页面图表由本包内部路由解决，独立可视化报告生产另配 `visual-report`。

## 5. 各技能总目录与按需 Read 索引

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| slideblocks/SKILL.md | 制作纪律全量：原则、Portable client runtime、八阶段工作流、Reference routing |

`skills/slideblocks/references/`（何时读什么，照 SKILL「Reference routing」节）：

| 件 | 何时读 |
|---|---|
| autonomous-workflow.md | 要看确定性路线、内部阶段、闸与续作点时 |
| source-intake.md | 用户给了文件/URL/粘贴素材/既有演示时（素材归一与 source-map 纪律） |
| project-contract.md | 创建、续作、校验或交付项目前（权威链与产物归属） |
| quality-contract.md | 实现前（canonical 规则的生成快照；随包升级更新，勿直接编辑） |
| content-planning.md | 新完整 deck 或全局叙事重设计（页组规划；孤立编辑/纯视觉修/保叙事迁移跳过） |
| design-principles.md | 故事清楚之后、选定或实现视觉方向前后（设计推理与审美批评） |
| style-profiles.md | 内部选定视觉方向前（学术/科学风为全题材默认档） |
| systemic-technical-briefing.md | deck 主任务是「复杂技术域的整体认知」（行业全景/能力框架/趋势）时 |
| technical-deep-dive.md | deck 主任务是「讲透一个具体系统/算法如何运作」时（深度优先专家路线） |
| research-evidence-briefing.md | deck 主任务是传达实证研究或正式政策/项目/临床/模型评估、听众须判断证据可信度时 |
| composition-director.md | 三页以上完整 deck 或整体重设计（构图导演；孤立编辑跳过） |
| visual-evidence-director.md | 仅与 composition-director 同用（先定每页须展示什么，再定放哪里） |
| diagram-direction.md | 页面依赖过程/架构/关系/结构/科学图时（载体分类与路由） |
| mermaid-presentation.md | diagram 路由选中原生 Mermaid 后（简单关系；非默认） |
| figure-spec.md | 路由选中重复解释性对比、带标注的不规则概念图或 FigureSpec graph 时 |
| diagram-spec.md | 页面选中自定义 ELK/SlideBlocks 图路线时（语义契约，非第二页案） |
| block-selection.md | 故事与页面名册清楚后（页面职责 → Registry 资产或 custom 的门控映射） |
| registry-index.md | live Catalog 不可用时（离线快照兜底；随包升级更新，须在 page plan 记录 fallback） |
| delivery.md | 安装、连接、live Catalog 检索、取回 Artifact、更新、卸载之前 |
| runtime-workbench.md | 接线或验证绘图、右键菜单、PDF 导出、离线导出、演示者模式之前 |
| qa.md | 宣称 deck 视觉完成之前 |

路由缺失某件 references 时：按 SKILL 核心工作流继续、如实报告缺件，不自行发明其细则。

## 6. 选 skill 决策树

1. **做演示/幻灯片**（「做个 PPT」「把这些材料做成演示」「迁移/改进/重设计 Slidev deck」）→ `slideblocks`（八阶段全流程）。
2. **最终交付物必须是 PPT/PPTX/Office 文件** → 不进本包：说明格式边界并停手（PowerPoint 只作只读迁移输入或视觉参考；未经用户新授权不切换实现路径）。
3. **HTML 卡片与社交图** → 另配 `design-cards`。
4. **独立可视化报告**（非 deck 页面的图表生产）→ 另配 `visual-report`。
5. deck 内的数据图/概念图 → 本包内路由（`diagram-direction.md` → 三引擎之一），不外溢。

## 7. 共享件专节（脚本与资产）

| 资产 | 用途 | 关键语义 |
|---|---|---|
| `scripts/slideblocks-client.mjs` | 交付客户端实现 | 无 launcher 时的 `<slideblocks-client>`；读取同目录上游交付元数据件；匿名交付上下文自动建立、私密持久，绝不外泄 |
| `scripts/validate-slideblocks-project.mjs` | 交付前结构校验 | 确定性文件与配置检查；错误须先解决或如实报告阻塞，**不得当视觉 QA 替身** |
| `assets/build-offline.mjs` | 离线单文件构建器 | **逐字节**拷为项目 `.slideblocks/build-offline.mjs`；不用未经验证的临时导出器替代 |
| `assets/workbench/`（9 件） | 演示者工作台 | `context-menu.vue` 合并入 `global-top.vue`；`context-menu.ts`/`pdf-export.ts`/`routes.ts`/`shortcuts.ts`/`text-edit.ts`/`offline-export.ts`/`vite-plugins.ts` 拷到项目 `setup/` 对应文件（保留并组合既有行为，不替换）；`verify-runtime.mjs` 拷为项目 `.slideblocks/verify-runtime.mjs`——工作台机械验证器（路由漂移/文本溢出/裁剪/资产损坏等即 FAIL，Chromium/WebKit 双引擎） |
| `assets/diagram/diagram-engine.mjs` | DiagramSpec 图引擎 | 复杂关系图：ELK 出候选几何、标签归属边、最终视觉审定归页面；拷入项目使用 |
| `assets/diagram/figure-engine.mjs` | FigureSpec 编译器 | 三配方（comparison/annotated/graph）的解释图；Node/构建期编译器，**禁**直接进浏览器 bundle；不按标题关键词套 stock 图形 |
| `assets/diagram/icon-library.mjs` | 图标库助手 | 拷引擎时**成对**必拷（含无图标 graph 路线——engine 会 import 它） |
| `assets/diagram/presentation-diagram.mjs` | 有界适配器（opt-in） | 仅矩形节点/显式端口/分支/反馈/层级；swimlane、嵌套、任意形状不支持——拒绝而非静默压平 |

> 各件的全量用法以 `skills/slideblocks/SKILL.md` 与其 references 为准；本节只登记防漂移。

## 8. 扩展位

暂无登记候选。Registry 生态与质量规则随上游验证包升级演进（`quality-contract.md`/`registry-index.md` 为生成快照，随包升级更新，不入本表）。

## 9. 维护约定

- **上游快照件不可手改**：`references/quality-contract.md` 与 `references/registry-index.md` 带生成头注——更新只随经验证的技能包升级到达。
- **上游物料中性化**：本包源自上游 SlideBlocks 项目，包内修改只落本包文件，不回改上游分发物料。
- **引擎成对纪律**：拷任何图表引擎时按 §7 表核对伴生件（icon-library 随 engine、verify-runtime 随项目锁文件），防单侧漂移。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
