# visual-report 机械门禁方案（zh-CN · 仅方案，未实现）

- **目标**：把「md/html 产出按图谱编排（论文式 / wiki-docs 规范）」从 **skill 行为约束**升级为**机械可查的检查器**——产出文件静态扫描，逐特征给 PASS/FAIL，可接入判定链作门禁。
- **背景缺口**：审计 a7a04f4e 遗留 2 在案——「『汇报必须使用插件』是约定级强制：md/html 产出走图谱的保证靠 wiki-docs skill 规则（行为规范），非机械门禁；若要硬约束需后续加 lint/CI 类机制」。本件即该机制的**方案件**：特征清单＋正则草案＋挂点评估＋推荐案。**不含任何实现件**（正则草案写入文档代码块属文档内容，不算实现）。
- **规范底料**：`~/.nebflow/plugins/visual-report/skills/wiki-docs/SKILL.md`（vault 布局/论文式/引用纪律/frontmatter/场景包）、同插件 `skills/core/SKILL.md`（图预算/嵌入路径/dark-mode）、审计 a7a04f4e M1③（论文式引用、点击跳转＋行级高亮的验收口径）。
- **配套**：`docs/production-ops-checklist.zh-CN.md`、`docs/parallel-session-resources.zh-CN.md`。

---

## 1. 检查特征清单（正则草案）

> 通用扫描前提（影响所有特征，先声明）：
> - **扫描对象**：`<workspace>/docs/**` vault 内的 `*.md` 与 `*.html`（visual-report 产出的 .html 报告已被后端入图，同一规范适用）。
> - **围栏跳过**：``` 围栏代码块与行内反引号 span 内的文本不参与特征匹配（否则本文档自身的正则示例就会自爆）。围栏切换判法可参照 `.nebflow/evidence/20260917_promptopt/count-rule-v1.py` 的 fence toggle 实现。
> - **frontmatter 剥离**：`---...---` 块先摘除（保行号，同 markdown.js 渲染器做法），供 R3 单独检查。
> - **三态退出**：`0 = PASS / 1 = FAIL / 2 = 用法错`；FAIL 输出按特征编号逐行 `R<n> FAIL <file>:<line> <detail>`。

### R1 · vault 落位

- 规则：交付文档必须在 `<workspace>/docs/` 内；附件必须在 `docs/attachments/`（wiki-docs「Vault layout」节）。
- 判法：路径前缀断言（非正则）。
- 红条件：文档落在 vault 外；`![[...]]`/`[[...]]` 引用的附件不在 `attachments/`。

### R2 · 首个 H1 即标题

- 规则：文档首个非空正文行必须是 `# ` 一级标题——图谱用它当节点标签（wiki-docs「Document form」节）。
- 正则草案：

```regex
^# \S.*
```

- 红条件：frontmatter 之后的首个非空行不匹配 R2（例如先出现 `##` 或正文段）。

### R3 · frontmatter type/status 存在且枚举合法

- 规则：每个 vault 文档带 `type:` ＋ `status:` 小 frontmatter，图谱据此做类型/状态筛选；完结态 status 在图谱打 ✓ 环（wiki-docs「Frontmatter」节）。
- 正则草案（枚举值 = 场景包角色与状态草案，待作者扩）：

```regex
\A---\n
type:[ ]*(research-log|problem|literature-note|conclusion|learning-map|adr|manuscript|source-note|outline|runbook|faq|onboarding|index|glossary)\n
status:[ ]*(open|solved|done|accepted|mastered|published|to-learn|learning|proposed|superseded)\n
---\n
```

- 红条件：无 frontmatter；type/status 缺一；值不在枚举内（枚举外值提示「待扩枚举」而非硬红——误报控制见 §3）。
- 误报面：非场景包的普通工程文档（如本方案件）可能两字段皆无 → 用**豁免清单**（§3）或对 `docs/design/**` 放宽为 advisory。

### R4 · wikilink 语法良构

- 规则：引用用 Obsidian wikilink，纯路径字符串不是图边（wiki-docs「References」「What not to do」节）。
- 正则草案（整链捕获：目标 / 锚 / 别名三段）：

```regex
\[\[([^\[\]|#]+)(#[^\[\]|]*)?(?:\|([^\[\]]*))?\]\]
```

- 红条件：出现 `[[` 而全文无一段匹配上式的完整 wikilink（残缺链）；或 wikilink 目标段为空/以空白开头。
- 误报面：嵌套方括号、转义 `\[[...]]`——靠围栏跳过＋残缺链检测兜底。

### R5 · embed 目标存在

- 规则：`![[...]]` 嵌入的附件必须实际存在（本地图谱可打开），且按 R1 在 `attachments/`。
- 正则草案：

```regex
!\[\[([^\]#|]+)(#[^\]#|]*)?(?:\|([^\]]*))?\]\]
```

- 判法：捕获组 1 解析为 vault 相对路径，`docs/attachments/<target>`（或声明的 vault 相对位置）必须存在。
- 红条件：目标文件不存在。

### R6 · 锚必须可解析（`#heading` 与 `^block-id`）

- 规则：「Anchors must exist: `#heading` must match a heading in the target document; `^block-id` must match a block id. A reference that resolves to nothing is the document-level equivalent of a broken build」（wiki-docs 原文）。这是 M1③「点击引用跳到目标文档具体行并高亮」验收口径的静态投影。
- 正则草案：

```regex
# heading 锚：目标文档内必须存在同名标题（去空白精确匹配，# 层级不限）
^#{1,6} <heading>$
# block 锚：目标文档内必须存在以 block-id 结尾的块（glossary 术语块即此形态）
\^[A-Za-z0-9][A-Za-z0-9-]*[ \t]*$
```

- 判法：对 R4 捕获的每个 `[[doc#anchor]]`，打开 `doc` 验锚存在；`^id` 形态按 block 锚验。
- 红条件：任一锚解析不到 = 红（与「broken build」同级）。
- 误报面：标题大小写/全半角差异；标题含 wikilink 或格式记号导致精确匹配失败 → 匹配前做与渲染器一致的归一化（去记号、trim），残留误差进 advisory。

### R7 · 零链接缺陷（图不孤立）

- 规则：「A document with zero outgoing links and zero incoming links is a defect unless it is the first document in a new domain——至少连 glossary 或 index」（wiki-docs「Citation discipline」节）。
- 判法：出链数 = R4 匹配数；入链数 = 全 vault 扫描其他文档的 R4/R5 目标是否引用本文档；两者皆 0 且文档不在豁免清单（index.md / glossary.md / 任务书声明的「域首文档」）→ 红。

### R8 · 图预算 ≤3（硬性）

- 规则：单文档 ≤3 图，n>3 只画权重前 3 且首屏声明「图预算超限」（visual-report core 铁律 3）。
- 判法：图片引用计数——md 数 `![...](...)` 与 `![[...]]` 中图片扩展名（svg/png/jpg/gif/webp）；html 数 `<img`；>3 且正文无「图预算超限」声明 → 红。

### R9 · 图片路径形态与白名单

- 规则：图片一律 `~/...` 绝对路径（或 `<...>` 包裹含空格路径），落可服务面白名单（core §2；AGENTS.md 第 6 条 nf-file 白名单同源）：`~/.nebflow/plots/**`、`<ws>/.nebflow/evidence*/**`、`~/.nebflow/{projects,uploads,workspace-items}/**` 等。
- 正则草案：

```regex
!\[[^\]]*\]\((<[^>]+>|[^)\s]+)\)          # md 图引用，捕获路径
路径断言：^(\~|<ws 绝对路径前缀>)          # 禁相对路径/裸文件名/file://
含空格路径 → 必须被 <...> 包裹
白名单前缀：~/.nebflow/plots/ | <ws>/.nebflow/evidence | ~/.nebflow/projects/ | ~/.nebflow/uploads/ | ~/.nebflow/workspace-items/
```

- 红条件：相对路径、`file://`、白名单外头（如 `logs/**`）、含空格未包裹。

### R10 · glossary 术语块锚格式

- 规则：跨文档复用概念在 `docs/glossary.md` 一块一定义，块尾 block-id（wiki-docs「Document form」节示例）。
- 正则草案（对 glossary.md 逐块）：

```regex
^.+[ \t]\^[a-z0-9][a-z0-9-]*$      # 每个定义块以 ^kebab-id 结尾
```

- 红条件：glossary 存在却零 block-id（术语没法被 `[[glossary#^id]]` 精确引用）。

### R11 · html 产出面

- 规则：html 报告同守图谱纪律，用普通锚点连到 vault 内伴生 md（wiki-docs「Concept-reference format」节末句）；后端已把 `.html` 报告入图为文档节点（审计 a7a04f4e #8 实测）。
- 判法（advisory 级）：`<h1>`/`<title>` 存在（图节点标签来源）；`href="...md#..."` 指向 vault 内文档时目标存在（复用 R6 判法）。

### R12 · 文献本地副本

- 规则：「Remote-only links (URLs without a local copy) are a defect——文献调研必须产出原件」（wiki-docs research 包纪律）。
- 判法：`[[x.pdf]]`/`![[x.pdf]]` 引用必须在 `attachments/` 有本地副本；正文文献引用只有外链 URL 无本地件 → 红。
- 误报面：许可不允许下载的文献 → 豁免清单登记。

### R13 · 首现术语有定义或引用（advisory）

- 规则：「Every new concept must be understandable at first mention——inline 定义或链到定义块」（wiki-docs「Document form」节）。
- 判法：**不做全文 NLP**——只做弱投影：文档引用了 `[[glossary#^...]]` ≥1 条，或正文含 inline 定义形态（`（即...）`/`— ... —` 等草案特征）。完全不沾者标 advisory，不硬红（语义级误报面过大，见 §3 推荐案的灰度策略）。

## 2. 候选挂点评估（三案）

### 挂点① 节点交付前 lint 脚本

- **原理**：实现为独立确定性脚本（node 或 python，落 `scripts/` 或 `.nebflow/tools/` 一次性脚本位），输入 = vault 路径或文件参数，输出 = §1 的逐特征 PASS/FAIL 行＋三态退出码。节点交付 md/html 前自查跑一遍，红即修完再交。
- **误报面**：全部集中在正则层——围栏/行内代码泄漏、标题归一化误差、枚举外新 type/status、R13 语义级误报。控制手段即 §1 开头的扫描前提＋advisory 分级＋豁免清单。脚本本身无运行时误报。
- **实施代价**：**低**。纯脚本工程，零运行时改动，零跨仓协调；`count-rule-v1.py` 已示范「机械规则脚本＋围栏跳过」的仓内先例形态。
- **强制力**：**弱**——依赖 agent 自觉运行，与现状（skill 行为约束）同级；审计已经指认这层不构成机械门禁。它的真实价值是作为挂点②③共用的**机械核**。

### 挂点② 运行时完成核验器加检（推荐案）

- **原理**：/goal 运行时的完成核验器（completion verifier）在 `finish=completed` 判定前，把挂点①的脚本作为**确定性预检**接入：交付面含 vault 文档 ⇒ 必须附脚本绿读数（或登记 advisory 项），否则核验器打回。先例：审计 a7a04f4e M1——完成核验器**两轮打回弱验证项**（跳转高亮时窗证据、跨设备未实测），agent 补硬验证后才放行 completed。该点位已被实证「会咬人且咬得准」，是全链路里现成的、无需新造的强制执行点。
- **误报面**：机械部分 = 挂点①的正则层（可控）；新增风险在核验器模型层对脚本输出的解读——用固定字段输出（`R<n> PASS/FAIL file:line`＋exit code）压歧义；脚本侧内建豁免清单与 advisory 分级，硬红面收窄到「锚不存在/嵌入目标缺失/图超限」这类客观断言。
- **实施代价**：**中**。动运行时判定链（goal-loop 完成核验环节），且核验器属运行时层、不在本仓——需跨面协调排期；脚本本体（挂点①）仍要先做。
- **强制力**：**强**——不跑完不了 completed；机械确定性＋既有执行点，双保险。

### 挂点③ plugin org.nebflow/tools.json 授权面

- **原理**：PluginRegistry 支持 `org.nebflow/tools.json` 给插件授予 builtin 工具（`{"tools":["WebSearch","WebFetch"]}`，web-search-toolkit 在案先例；授予面解析见 `src/main/scala/nebflow/core/plugin/PluginRegistry.scala` 注释与 §4.1 路径围栏）。设若存在 `DocLint` builtin，visual-report 插件即可用 tools.json 把它授给所有装挂节点，交付前人人可自查。
- **诚实限制**：tools.json 只能授予**已存在**的 builtin ⇒ 前提是先实现 DocLint builtin（运行时工程先行，代价落在这一步）；且「授予 = 可用性」，**不是义务**——节点可以不调用，不承载强制力。新增工具面另受 AGENTS.md 第 16 条（作者逐字过目）约束。
- **误报面**：与挂点①同（同一机械核），工具化后参数校验可控。
- **实施代价**：**中高**——builtin 工具实现＋plugin 文件＋§16 过目流程。
- **强制力**：弱（可用性面）。

### 三案对照

| | ① lint 脚本 | ② 完成核验器加检 | ③ tools.json 授权面 |
|---|---|---|---|
| 强制力 | 弱（自觉） | **强（判定链）** | 弱（可用性） |
| 实施代价 | 低 | 中（跨面协调） | 中高（builtin 先行） |
| 误报面 | 正则层，可控 | 正则层＋模型层，可控 | 同① |
| 新增执行机制 | 无（新脚本） | 无（复用既有点位） | 有（新 builtin） |

## 3. 推荐案：挂点②（以挂点①脚本为机械核）

**推荐：完成核验器加检**，理由四条：

1. **强制力落在已实证的执行点**：完成核验器在 /goal 链上有 a7a04f4e M1 两轮打回的在案先例——不需要新造任何执行机制，机械门禁的「牙齿」已经长在那里，缺的只是喂给它一套确定性判据。
2. **误报面收窄到脚本层**：判定 = 脚本 exit code＋固定字段，模型层只消费不改写；硬红项（R5/R6/R8/R9/R12 客观断言）与 advisory 项（R3 枚举/R13 语义）分级，灰度期可只报不改判。
3. **演进路径顺滑、无重复建设**：挂点①脚本先落地（独立可用，交付前自查立即受益）→ 挂点②把同一脚本接进核验器承载强制力 → 挂点③可选地把同一脚本工具化（DocLint builtin）作可用性增强。三案共享一个机械核，推荐②不排斥①③，只是排序。
4. **与既有纪律同构**：「机械规则脚本＋固定判据＋豁免单列」与仓内 count-rule-v1（AGENTS.md 计数规则）、verify-web-assets/check-js-types 质量闸同一形态，作者审阅与维护成本最低。

**落地建议（灰度）**：① 脚本先行＋advisory-only 跑一个观察窗；② 核验器接线，先 monitor（读数入报告不改判）后 enforcing；③ 豁免清单（域首文档/许可受限文献/枚举扩充）走作者核——与 §2.2 端口表同款「约定值待作者核」口径。

---

## 附 · 本件边界声明

本件为**方案件**：§1 正则草案均为文档内容，未落任何脚本/配置/运行时改动；三挂点的实施排期、枚举定稿与豁免清单属后续实施批，须经作者裁定（工具面改动另过 AGENTS.md 第 16 条过目闸）。
