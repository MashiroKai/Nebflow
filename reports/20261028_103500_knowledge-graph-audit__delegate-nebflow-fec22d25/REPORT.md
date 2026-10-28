# Stand 知识图谱（KG）现状审计

审计触发：作者令 2026-10-28（纯只读，未改任何代码/文档/数据；未起 dev server、未动在产引擎 pid 70481 / 6138 口）。
作者原始设计（verbatim）：「我们通过 visual report 插件创建出来的文档，会按 stand 的知识图谱的格式去写，然后和我们的知识图谱的面板联动。」

**现状一句话**：设计链路的四个环（格式定义 → 插件纪律 → 引擎索引 → 面板联动）在代码层面已全部存在且字段级同构、面板功能完整在产；但**数据面断供**——图谱只扫注册项目 `<workspace>/docs`，而近 3 天 114 篇新知识文档有 96 篇（84%）落在 repo `reports/` 与 `~/.stand/docs/` 这两个图外位置，实际收录率 **15.8%**，今天的批次/审计报告收录率为 **0%**。链路「通而不流」。

---

## 1. 格式面：KG 格式定义在哪、插件产出对不对得上

### 1.1 引擎侧（权威定义）

| 定义点 | 位置 | 内容 |
|---|---|---|
| 解析器/格式权威 | `src/main/scala/nebflow/core/wiki/WikiIndex.scala:8-34` | Obsidian 兼容 vault：`[[target]]`/`[[target\|alias]]`/`[[t#Heading]]`/`[[t#^blockid]]`/`![[embed]]`/`[text](rel.md)`（含 `#L42` 行锚扩展）；vault=`docs/`，附件=`docs/attachments/` |
| 文档扩展名 | `WikiIndex.scala:96` | `.md`/`.markdown`/`.html`（html 覆盖 visual-report HTML 交付件，同等节点待遇） |
| 附件扩展名 | `WikiIndex.scala:101-105` | pdf/office/图片/音视频/csv → `kind=attachment` 节点 |
| frontmatter | `WikiIndex.scala:118-135`（读取）、`:523-524`（入节点） | `type:`/`status:` 平铺标量；status∈完成态（solved/done/…）图上打 ✓ 环 |
| 段落索引 | `WikiIndex.scala:66-79, 326-437` | 每文档 ≤64 段（block `#^id` + heading `#标题`），excerpt ≤280 字符，图载荷内嵌 |
| 汇总行 | `WikiIndex.scala:274-320` | 每节点 1 条 ≤140 字符预览（首个 H1 后首段） |
| 服务/发现 | `src/main/scala/nebflow/core/wiki/WikiService.scala:16-26` | 零配置发现：项目 vault 恒为 `<workspace>/docs`，子 vault=`<sub>/docs` 递归（#744-B）；远端项目走 NebLink 通道扫对端 |
| API | `src/main/scala/nebflow/gateway/WikiRoutes.scala:13-38` | `GET /api/wiki/graph`（合并/单库/下钻/`__user__` 占位）、`/api/wiki/vaults`、`/api/wiki/paragraph`、`/api/wiki/remote-doc`、`POST /api/wiki/vault` |

### 1.2 文档侧（设计件与插件纪律）

- 设计稿（在 vault 内、自带 frontmatter、15 处 wikilink，格式典范）：`docs/design/wiki-graph-redesign-design.zh-CN.md:1-7`、`docs/design/wiki-graph-paragraph-ref-design.zh-CN.md`（段落级引用批，§3 与校验器逐字段镜像，见 `WikiIndex.scala:68`）。
- 多 vault 批设计：`design/20261022_190000_multivault-wiki-graph__delegate-nebflow-56930fbd.md:31`（「check_wiki_docs 的命令行与图谱口径永远一致」）、`:93`（校验管线）。
- **visual-report 插件的 wiki-docs 纪律**（作者令 2026-10-21 三条强制条款）：`src/main/resources/seed/plugins/visual-report/skills/wiki-docs/SKILL.md:14-59` ——①Obsidian 格式（vault 内 + frontmatter + wikilink + attachments/）；②论文式引用图（断链/断锚/断块/孤儿都是缺陷）；③概念首现必释或必引（词表单一来源）。校验器 `skills/wiki-docs/scripts/check_wiki_docs.py`（753 行，stdlib 零依赖，`--graph` 导出与 WikiIndex 同构的 `{nodes,edges}` JSON）。frontmatter 规格 `SKILL.md:151-166`，vault 布局 `:63-67`，五套编排包 `:168-227`。
- 交接条款：`skills/visual-report/SKILL.md:197-214`——「凡属于项目知识库的 markdown/HTML 报告 MUST 落 `<workspace>/docs/`，wikilink 互引，零出链的报告是不完整交付」。

### 1.3 一致点与偏差点（插件产出 vs KG 期望）

**一致（机制已闭环）**：
1. 格式规格两端同构：校验器 `--graph` 导出与引擎扫描逐字段互为镜像（`WikiIndex.scala:68,327,374` 自证），e2e 用真实 vault 导出数据回放面板（`tests/wiki-graph-multivault-shots.mjs:5,24`）。
2. HTML 交付件与 md 同等入图（`WikiIndex.scala:93-96`），对齐 visual-report 以 HTML 为主交付形态的现实。
3. 附件（文献 PDF/图）作为一等节点，`[[paper.pdf]]` 一键 canvas 打开（`WikiService.scala:67-70`、`wikiGraph.js` openDoc 分支）。

**偏差（三处，均为流程性而非机制性）**：
1. **交付位置三分天下**：wiki-docs 规定落 `<workspace>/docs/`；同插件 `skills/visual-report/SKILL.md:193,235` 又规定人读件落 `~/.nebflow/docs/<域>/`；而本仓实际分发惯例是阶段文档落仓根、批次报告落 `reports/`（本次任务简报同款）——三个口径并存，后两者图谱一概不收。
2. **场景 SKILL 不传纪律**：17 个场景 SKILL（engineering/office/…）无一提及 wiki-docs/vault/frontmatter（实测 grep 零命中）；纪律只活在 wiki-docs 一个 SKILL 里，选配 visual-report 时是否注入 wiki-docs 由分发器触发词决定，批次报告类任务通常不触发。
3. **校验器无强制点**：`check_wiki_docs.py` 只在 SKILL 文本里要求「交付前跑」，分发简报、收尾模板、CI 均未接（grep 全仓：仅插件自述与 e2e 引用）。

## 2. 数据面：图谱数据从哪来、实际有什么、收录率

**数据源**：零配置——每个注册项目的 `<workspace>/docs`（`WikiService.scala:16-26`），合并图 = 全项目根 vault 并集（`:206-239`），30s TTL 缓存。无独立索引文件，实时扫描。

**存量（实数，2026-10-28 实测）**：
- 注册项目 54 个，vault 在位仅 **6 个（11.1%）**：stand 99（96 md + 3 html + 4 附件）、slideblocks 41、CZT 7、chisel-fpga 3、jev-test 1、视觉工作室 1。
- 校验器 `--graph` 实测（与引擎同构）：stand 库 **nodes=103 / edges=154 / unresolved=0 / 段落索引 2364**；slideblocks 41/66；CZT 7/0；六库合计约 **156 节点 / 220 边**。
- **图外存量**：本仓 `reports/` 195 篇 md/html、`~/.stand/docs/` 13 篇 md——全部不在任何注册 vault，**0 收录**。

**抽查 5 篇近期真实产物**：

| 样本 | 落点 | frontmatter | wikilink/引用 | 被图谱收录 |
|---|---|---|---|---|
| `docs/design/wiki-graph-redesign-design.zh-CN.md`（10-26 设计稿） | vault ✓ | ✓ `type: design-note / status: open` | ✓ 15 处（含 `[[glossary#^block-id]]`） | **✓**（节点+边齐全，格式典范） |
| `docs/design/20261027_cli-isolation-and-detached-restart-spec.zh-CN.md` | vault ✓ | **✗**（校验器 R1 违规） | ✗ 引用为反引号路径非链接 | ✓ 收录但零出边 |
| `reports/20261028_100900_cli-isolation-detached-batch__delegate-nebflow-585087f7.md`（昨日批次报告） | repo reports/ ✗ | ✗ | ✗ | **✗ 0%** |
| `~/.stand/docs/20261027_053000_三日交代落地审计/REPORT.md` | 用户 docs/ ✗ | ✗ | ✗ | **✗ 0%** |
| `~/.stand/projects/slideblocks/docs/architecture.md` | vault ✓ | ✗ | ✗（0 wikilink） | ✓ 收录但零出边 |

**收录率实数**：近 3 天（10-25→10-28）新产出知识文档 **114 篇**，其中 vault 内 **18 篇 = 15.8%**；vault 外 96 篇（repo reports/ 与 ~/.stand/docs/）收录率 0%。今日（10-27/28）批次+审计报告 8 篇，收录 **0 篇**。

**存量格式质量**（stand 库，校验器实测 39 条违规）：96 md 中 92 篇有 frontmatter（95.8%）但仅 **16 篇（16.7%）有 wikilink**；**35 篇孤儿文档**（零出链零入链）+ 4 篇缺 fm。slideblocks 41 篇 fm=0（66 条边全靠 md 链接）、CZT 7 篇 fm=0、chisel-fpga 3 篇 fm=0。

## 3. 面板面：控制台图谱面板现状

- **位置**：前端 `src/main/resources/web/js/wikiGraph.js`（1736 行，canvas tab `wiki-graph`；`main.js:3995` 注册星标按钮，`canvas.js:569` 热加载，`index.html:155` 挂载）。
- **渲染什么**：节点 = 文档/附件（石墨圆点，度数→半径 5-11，`wikiGraph.js:38-44`）；边 = wikilink/md-link 引用（1px 直线）；悬停**锚定边显示目标段落快照、点击跳转并高亮该段**（段落级引用批，`:91-93`）；节点 = 项目库切换器 + 子 vault 聚合下钻 + 面包屑（#744-B，`:4-9`）；左栏控制面：搜索/目录 chips/选中详情卡（徽标、摘要、目录、**引出/被引两栏**、断链清单、统计行）（`:11-22`）。
- **数据源**：`GET /api/wiki/graph`（`wikiGraph.js:50,611`）+ `/api/wiki/vaults`（`:624`）——实时扫盘，无前端写死/示例数据（grep 无 mock/demo 分支；测试注入用真实 vault 导出 JSON 回放，非硬编码）。
- **双向联动**：图→文 ✓ 节点详情卡「打开文档」/锚定边点击 → `openDoc`（`:1656-1700`）本地走 workspace 开件、远端走 `/api/wiki/remote-doc`（PDF/图片 blob 直开），带锚跳段高亮；文→图 ✓ markdown viewer 把 `[[wikilink]]` 渲染成可点引用，经图索引解析跳转（`viewers/markdown.js:4-12,272-330`）。
- **缺口**：文档阅读页**没有 backlinks 区块**（grep 全 web/js 零命中）——「被引」只在图谱左栏详情卡；`project=__user__` 用户历史图谱是 C 批空占位（`WikiService.scala:457-472`）。
- **截图证据**（未起服务，用既有 e2e 产物）：`evidence/20261022_wiki-graph-paragraph/shots/paragraph-hover-dark.png` —— 面板实拍：力导向图 + 节点「词汇表」+ 悬停段落快照卡（「§ 文档库（vault）…点击跳转并高亮该段」）；另有 `evidence/20261025_capsule-fixes/shots/6-graph-tab-capsule-{dark,light}.png`、`evidence/20261020_mobile-screens-impl/shots/m375-dark-09-p9-graph.png`（移动端）。

## 4. 差距与补齐（设计目标 → 现状 → 差距 → 补齐动作）

| 设计目标 | 现状 | 差距 | 补齐动作（工作量档） |
|---|---|---|---|
| ① visual-report 产出按 KG 格式写（vault+frontmatter+wikilink） | 格式规格与校验器齐备且两端同构；但实际产出三分流：vault（stand 库 95.8% 有 fm、仅 16.7% 有 wikilink）、repo reports/（195 篇 0 收录）、~/.stand/docs（0 收录）；分发惯例「报告落 reports/」与 vault 纪律冲突，插件自身还有 `~/.nebflow/docs/<域>/` 第三口径（visual-report/SKILL.md:193,235） | 产出位置不受纪律约束；场景 SKILL 不传 wiki-docs；校验器无强制接入点 | **顺手修**：在分发简报/收尾模板写死边界——知识性产物（设计/审计/调研/结论）必须落 `<workspace>/docs/` + fm + ≥1 wikilink，过程件才走 reports/；模板附一行校验命令 `check_wiki_docs.py --root <ws>/docs` |
| ② 产出与图谱面板联动 | 四环闭环：面板多 vault/下钻/段落跳转/双向开件全部在产（见 §3） | 文档页无 backlinks 区块；reports/ 的 195 篇活文档面板完全不可见 | **顺手修**：markdown viewer 加 backlinks 区块（入边已在图载荷里，纯前端可算） |
| ③ 知识全量入图 | 六库 152 文档 ≈156 节点/220 边/2364 段落，0 unresolved；近 3 天收录率 15.8%、今日 0%；54 项目仅 6 个有 vault；存量 35 孤儿 + 4 缺 fm | 大多数新文档与大多数项目在图外；存量质量问题待清 | **一批**：①stand 库 39 违规清零（补 4 fm + 35 孤儿接 index/glossary）；②slideblocks/CZT/chisel-fpga 三库 49 篇补 fm+链接；③近 30 天高价值 reports/ 精选回填 vault |
| ④ 链路可持续（作者设计本意：插件产出自动成为图谱一部分） | 收录完全依赖节点自觉落 vault，无观测、无 gate | 没有「收录率」指标，断供不可见（本次审计靠手算） | **专项**：①收录率可观测——收尾/合并轮自动跑 `check_wiki_docs.py --graph`，快照入库+面板加统计行（在图/孤儿/断链三数）；②`__user__` 用户历史图谱 C 批落地（现为空占位，WikiService.scala:457）；③统一裁决 `~/.nebflow/docs/<域>/` 人读件口径与 vault 的关系。观测两周后再决定是否上硬 gate |

**还差几步（按档）**：顺手修 3 件（简报模板边界句、viewer backlinks 区块、4 篇近期设计稿补 fm）≈ 小时级；一批 3 件（存量违规清零、三库补齐、reports 回填）≈ 1-2 天；专项 3 件（收录率可观测、__user__ 图谱、人读件口径统一）≈ 一个专项周。**结论：机制层零缺口，差的全是「让数据流进管道」的流程三步——落点约束、存量整备、收录率可观测。**

---

*证据基线：worktree `delegate/delegate-nebflow-fec22d25` @ c4cf7388c（与主仓 main 同源）；数据面实数实测自主仓与 ~/.stand 在产数据（只读）。校验器导出临时件写 /tmp（kg-stand.json / kg-sb.json / kg-czt.json），未触碰仓内与数据目录。*
