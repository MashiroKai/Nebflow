# document-production — 插件使用指导（Plugin.md）

> 本文件是 document-production 插件的**包根使用指导件**（Plugin.md）：**插件分配时注入给 agent 的提示词 = 本文件内容**（作者令 2026-10-21「plugin.md = 插件必要文件机制」批；SKILL.md 回归按需查阅件——面板胶囊点击打开，不再随分配整文注入）。选配判定仍看 plugin.json 的 description；逐 skill 作业指令见各 `skills/<skill>/SKILL.md`（按需查阅）。**本文件是导览不是手册：与 SKILL.md 不一致时以 SKILL.md 为准并回改本文。** 文内路径均为**自包根的文件相对形式**（不使用 `${SKILL_DIR}`）。

## 1. 速览

document-production 是文档制作包：节点获得 Word/PDF/PPT/Excel 四类交付件的制作、编辑与成稿能力，外加交付前的成稿页级视觉验收关卡。内含 5 个 skill：

- `skills/docx/`——DOCX 制作、编辑与分析：五路 Task Router（create/edit/format/comment/read）＋ 七场景 Scene Router（学术/报告/合同/简历/试卷/公文/文案）、封面配方 R1-R7 强制（禁自由发挥封面代码）、双层后检（人工清单＋`postcheck.py` 自动 14 规则）、公式与图表模板；附 routes/scenes/references/scripts/env_setup 配套件。
- `skills/pdf/`——PDF 工作台四产线：报告（ReportLab）、创意视觉（HTML→PDF）、学术 LaTeX（Tectonic）、既有 PDF 处理（合并/拆分/表单/提取）；Triage 轻重分档控上下文加载、场景路由矩阵、渲染引擎铁律（禁手写 Playwright 脚本、走封装脚本）；附 briefs/configs/typesetting/scripts/env_setup 配套件。
- `skills/pptx/`——幻灯片设计规范与文件级制作：内容先行（每页一个具体主张）、BACKGROUND→PRIMARY→ACCENT 配色模型、每页一个视觉焦点、禁「AI 味」清单；pptxgenjs 深度用法与常见坑（文件损坏/视觉 bug）。
- `skills/xlsx/`——电子表格制作与分析：Pre-Flight 意图门（先确认真的需要表格）、Scene Router（创建/编辑/分析/转换/财务/VBA）、设计原则与质量门、能力矩阵；附 scenes/engines/templates/env_setup 配套件与 `xlsx.py` 工具。
- `skills/visual-acceptance/`——成稿页级视觉验收：交付件先渲染成逐页 PNG，逐页判三项（视觉资产/版式构图/内容一致性），每页一行 JSON 判据；判据是用户验收线——程序化自检跑绿不能替代这一遍。

**包纪律一句话**：四产线各走各的 skill，交付前一律过 `visual-acceptance` 页级过目；做 Slidev 演示另配 `slideblocks`、单图与可视化汇报另配 `visual-report`、插件封装另配 `nebflow-plugin-creator`——本包不管这三样。

## 2. 安装与启用

- **安装**：本包属 Nebflow seed 官方分发，随实例 seed mirror 自动下发，无需手工安装。
- **启用/选配**：装载为「在位即生效」；分发器按 description 触发词选配（做 DOCX/Word 文档、产出 PDF、做 Excel 表格、做 PPT 幻灯片、文档排版、表格格式互转、成稿视觉验收等），派发时对应 SKILL.md 全文注入节点。
- **停用**：面板「更多 → 封禁该插件」/ `POST /api/plugins/document-production/revoke` / `nebflow plugin revoke document-production`。
- **内容变更**：包内任何字节改动都会漂移 digest，面板把该包标为「内容已变更」（可见性提示，不拦截装载）。

## 3. 目录结构

```
document-production/                （包根）
  plugin.json
  Plugin.md                         ← 本文件（包根使用指导件：速览 / 安装启用 / 产线导览 / 总目录 / 资产登记）
  skills/
    docx/                           ← DOCX 产线（SKILL＋routes 5＋scenes 7＋references 10＋scripts＋env_setup）
    pdf/                            ← PDF 产线（SKILL＋briefs 9＋configs 3＋typesetting 9＋references 2＋scripts＋env_setup）
    pptx/                           ← 幻灯片产线（SKILL 单文件）
    xlsx/                           ← 电子表格产线（SKILL＋scenes 10＋engines 4＋quality＋templates＋xlsx.py＋env_setup）
    visual-acceptance/SKILL.md      ← 成稿页级视觉验收（单文件）
```

- 各 skill 内配套件全部是**按需 Read 面**：加载时机由各 SKILL.md 自己的路由表承载（docx Task/Scene Router、pdf Triage 与场景路由矩阵、xlsx Scene Router 与 File Loading Rules），本文件不复制逐件索引。
- docx/pdf/pptx/xlsx 四 skill 带 `LICENSE.txt`（外来源 skill 许可随包保留）；各 skill 的 `env_setup/`（环境自检＋分平台安装脚本＋字体清单）与 docx/`setup.sh`、xlsx/`setup.sh` 为环境引导面。

## 4. 包级核心用法（四产线＋验收关怎么配合）

> 全量口径以注入的各 SKILL.md 为准；本节是产线速查。

| 产线 | 用哪个 skill | 关键纪律（速查） |
|---|---|---|
| Word 文档 | docx | 先过 Task Router 定路线、再过 Scene Router 定场景；封面必须用 R1-R7 验证配方（自由发挥封面代码禁）；生成后跑 `postcheck.py` 清零 ❌ |
| PDF | pdf | Triage 先分轻重（Light 任务只载 process brief，不载排版资产）；按场景路由矩阵进对应 brief；HTML→PDF 走封装脚本（`html2poster.js`/`html2pdf-next.js`），禁手写 Playwright 脚本 |
| 幻灯片 | pptx | 先定三件事（主题配色/主次权重/明暗与母题）；每页一个具体主张＋一个视觉焦点；对表「AI 味」avoid list |
| 电子表格 | xlsx | Pre-Flight 意图门先确认形态（报告→docx、演示→pptx、打印件→pdf）；再过 Scene Router；多意图请求全部件交付不静默丢件 |
| 交付前验收 | visual-acceptance | 先渲染成逐页 PNG 再判（禁拿源文件当图片读）；每页判三项、每页一行 JSON、通过与不通过都出；fail 页修完重渲染再判 |

- **环境引导**：docx/pdf/xlsx 首次使用先跑各自 `env_setup` 自检（`bash setup.sh` 或 `env_check.sh`）；大件依赖（Playwright/Chromium、LaTeX 引擎）按需且征得用户同意后安装；字体 local-font-first，装新字体先问用户。
- **中文排印硬点**：docx 中文弯引号必须 Unicode 转义（JS 串内最常见的生成事故源）；TOC 后强制 PageBreak、三段式页码（封面无页码/前置罗马/正文阿拉伯）后处理。
- **验收线优先**：任何产线的脚本自检跑绿 ≠ 验收通过——最终结论以 visual-acceptance 的页级判据为准。

## 5. 各技能总目录

| skill 路径（自 skills/） | 一句话括注 |
|---|---|
| docx/SKILL.md | DOCX 制作编辑分析：双路由器、封面配方 R1-R7、双层后检、公式图表、交付口径（Nebflow 节） |
| pdf/SKILL.md | PDF 四产线工作台：Triage 分档、场景路由矩阵、共享资产、渲染铁律、Preflight 质检 |
| pptx/SKILL.md | 幻灯片设计规范＋pptxgenjs 制作：配色模型、版式与图文叠放、avoid list、常见坑 |
| xlsx/SKILL.md | 电子表格产线：意图门、场景路由、设计原则、工具链、质量门、交付口径（Nebflow 节） |
| visual-acceptance/SKILL.md | 成稿页级视觉验收：渲染前置、每页三项判、JSON 判据输出、打回重渲染节拍 |

配套件形态（按需 Read 面，加载时机以各 SKILL 路由表为准）：

| skill | 配套件 |
|---|---|
| docx | `routes/` 5 件（create/edit/format/comment/read）、`scenes/` 7 件（学术/报告/合同/简历/试卷/公文/文案）、`references/` 10 件（设计系统与封面配方、OOXML、TOC、公式、图表模板等）、`scripts/`（postcheck、TOC 占位、页脚域修复等＋批注模板）、`env_setup/` 5 件 |
| pdf | `briefs/` 9 件（报告/海报/学术/简历/创意三态/处理两态）、`configs/` 3 件（组件/字体/视觉框架）、`typesetting/` 9 件（封面/配色/字体/溢出/分页/几何等）、`references/` 2 件（LaTeX 简历模板）、`scripts/` 10 件（pdf.py 主入口、HTML→PDF 两封装、QA/封面/TOC 校验等）、`env_setup/` 5 件 |
| xlsx | `scenes/` 10 件（创建/编辑两态/分析两态/转换/财务两态/VBA/进阶）、`engines/` 4 件（图表/设计/VBA 模板）、`quality/` 1 件、`templates/` 2 件（基础与配色库）、`xlsx.py`＋`setup.sh`、`env_setup/` 5 件 |
| pptx / visual-acceptance | 无配套件（方法全量在各自 SKILL.md 单文件内） |

## 6. 选 skill 决策树

1. 目标是 **.docx/Word**（新建、改写、修订批注、保格式提取）→ `docx`（有附件走 edit/comment/read 路线，无附件走 create）。
2. 目标是 **PDF**（产出报告/海报/论文/简历，或加工既有 PDF）→ `pdf`（先 Triage 分档，再按场景路由矩阵进 brief）。
3. 目标是 **.pptx 幻灯片** → `pptx`；做 **Slidev 演示** → 不在本包：另配 `slideblocks`。
4. 目标是 **.xlsx/.csv 表格**（新建、编辑、分析出表、格式互转）→ `xlsx`（先过意图门确认形态）。
5. 交付件已成型、要**逐页过目验收** → `visual-acceptance`（只判不改稿、不重渲染、不修代码）。
6. 要**单图/图表可视化汇报** → 不在本包：另配 `visual-report`；要**插件封装** → 另配 `nebflow-plugin-creator`。
7. 代码与契约层审查 → 不在本包：归 `nebflow-qa` 插件（visual-acceptance 边界原文）。
8. 常连用：一次文档交付＝产线 skill 成稿 → visual-acceptance 页级过目 → fail 页回产线修→重渲染→再判。

## 7. 共享件专节（脚本与资产）

| 资产 | 用途 | 关键语义 |
|---|---|---|
| `skills/docx/scripts/postcheck.py` | DOCX 生成后自动后检（14 业务规则） | 生成任何文档后必须跑，❌ 清零才算过 |
| `skills/docx/scripts/add_toc_placeholders.py` | TOC 占位与页码域后处理 | `--auto` 退出码 0 为过；页脚域 instrText 后处理规则见 `references/toc.md` |
| `skills/pdf/scripts/pdf.py` | PDF 产线主入口（env.check/env.fix 及各子命令） | 环境自检/修复先行；Core 与可选依赖分离（大件按需征用户同意） |
| `skills/pdf/scripts/html2poster.js` / `html2pdf-next.js` | HTML→PDF 两封装（海报单页/多页文档） | 唯二合法 HTML 渲染通道，禁手写 Playwright 脚本（铁律） |
| `skills/pdf/scripts/pdf_qa.py`（及 cover/toc/poster 校验件） | PDF 成稿质检与封面/TOC/海报校验 | Preflight 阶段按 brief 指引调用 |
| `skills/xlsx/xlsx.py` | 电子表格工具入口 | 绝对路径调用形态见 xlsx SKILL Toolchain 节 |
| `skills/xlsx/templates/base.py`＋`palettes.py` | 工作簿构建基座与配色库 | 生成脚本 import 这两件，不各写各的 |
| 各 skill `env_setup/`（docx/pdf/xlsx） | 环境自检与分平台安装引导 | 首次使用先自检；字体 local-font-first |
| `skills/{docx,pdf,pptx,xlsx}/LICENSE.txt` | 外来源 skill 许可件 | 随包保留，上游同步不清除 |

> 用法与全量口径以各 SKILL.md 为准；本表只登记防漂移。references/scenes/briefs/typesetting/engines 等按需 Read 面不重复登记（见 §3/§5）。

## 8. 扩展位（未来候选）

| 候选项 | 前置条件 | 备注 |
|---|---|---|
| 新文档格式产线 skill | 该格式生产中稳定跑通且路由/质检清单齐备 | 先入本表，落地后迁出并入 §5 |

> 只登记候选与前置条件；候选落地后迁出本表、入 §5 总目录。

## 9. 维护约定

- **外来源 skill 上游同步**：docx/pdf/pptx/xlsx 为带 LICENSE 的外来源 skill——上游同步时许可件随包保留，**包内增补节不得被冲掉**（docx 与 xlsx 的「交付与引用（Nebflow 口径）」节为 Nebflow 增补，同步后核对在位）。
- **路由与配套件同步**：SKILL 路由表新增/改名配套件时，同步本文件 §3/§5/§7 的件数与清单；配套件增删以各 SKILL 路由表为权威。
- **验收口径**：visual-acceptance 的判据分类（`Spec/Content/Visual/Design/Unverified`）与 JSON 输出格式变更时，本文件 §4/§6 同步。
- **跨包口径**：与 `slideblocks`（Slidev）、`visual-report`（单图可视化）、`nebflow-qa`（代码契约审查）的分工边界若调整，两侧同步。
- **本文件定位**：Plugin.md 是按需 Read 面——装载器不注入；SKILL.md 是注入面权威，两者不一致时以 SKILL.md 为准并回改本文。
