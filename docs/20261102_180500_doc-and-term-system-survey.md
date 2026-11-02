---
type: research
status: open
---

# 文档系统与术语体系勘察 · 「调查产出 → 文档 → 术语」联动基础

**一句话结论**：文档系统已经有一套**能跑、能校验、能出图**的 Obsidian 契约（词表块锚 + 词条注册表 + 三规则校验器），但它的**物理底座是漏的**——`docs/` 被 `.gitignore` 整体忽略、两个注册项目各有一份 vault、几处「权威落点」指向盘上不存在的路径。术语侧则是**两套成熟但互不相通**的表（仓根 `GLOSSARY.md` 管名字与落点、`docs/glossary.md` 管概念与块锚）。因此调查插件的联动设计**不需要新机制**：实体登记照抄词条注册表的形态、证据引用照抄段落锚段落，只要各占一个命名空间、并把 `docs/` 的入库腿补上。

本件只做勘察与设计，零代码、零既有文档改动。勘察范围 = `nebflow-stand` 工作区（Scala 线）＋本机两个已注册项目的 vault；云端与 Rust 线的文档系统未逐一核对（见 §6 假设）。

---

## 一、文档系统现状

### 1.1 物理结构与命名

vault 根 = `<project workspace>/docs/`（`src/main/scala/nebflow/core/wiki/WikiIndex.scala:11-12`、`:28` `VaultDirName = "docs"`；发现式注册、零配置见 `src/main/scala/nebflow/core/wiki/WikiService.scala:15-28`）。本仓 vault（`docs/`）实况：

| 位置 | 内容 | 计数 |
|---|---|---|
| `docs/*.md` | 根散件：批报告、设计稿、调研 | 40（其中 32 带 `YYYYMMDD_HHMMSS_` 前缀，29 带 `__<chainId>` 尾段） |
| `docs/design/` | 设计方案、spec、设计稿 | 46 md ＋ 6 html 样张（静态设计演示页，见 [[glossary#^mockup-sample]]） |
| `docs/templates/` | 文档批格式范本 | 2 |
| `docs/runbooks/` | 运维手工流程 | 2 |
| `docs/attachments/` | 被引用的图片/PDF/patch | 18 个文件（含 2 个子目录） |
| 全 vault | markdown | 90 篇 |

命名规范是**引擎注入的纪律**，不是文档里的约定，两处同源：

- 阶段件 `<YYYYMMDD>_<HHMMSS>_<topic>__<chainId>.md`、活文档 `<topic>__<chainId>.md`（无日期前缀）、无归属不带尾段、多链 `__<主链Id>+<次链Id>.md`：`src/main/scala/nebflow/core/project/NodeEngineContract.scala:697-715`（`DocProvenanceBlock`，随链头注入），同款条文在 `src/main/scala/nebflow/prompts/PromptCatalog.scala:264`。
- 「溯源只进文件名尾段，正文零元数据头」：`src/main/scala/nebflow/agent/PromptSections.scala:289`；`:282` 注明旧版正文元数据头模板已作废、`~/.nebflow/docs/CONVENTIONS.md` 冻结不改。

**缺口**：该 `CONVENTIONS.md` 盘上不存在（`~/.nebflow/docs/` 整个目录都不在），但它被三处当作「权威条文」引用：`NodeEngineContract.scala:697`、`PromptSections.scala:282`、`src/test/scala/nebflow/core/project/NodeChainAttributionSpec.scala:94` 与 `:101`。命名纪律的**条文**只剩提示词字面量，没有可核对的规范件。

### 1.2 frontmatter、index 与 glossary 怎么维护

- frontmatter 只有两键 `type:` / `status:`（`docs/index.md:1-4`、`docs/glossary.md:1-4`）。校验器只要求两者**至少一个在场**（`check_wiki_docs.py:501-508`），**没有闭集校验**。实测取值发散：`type` 在用 19 个不同取值（`design-note` 27、`design` 16、`fix-report` 10、`design-spec` 10、`audit-report` 5、`audit` 4 …… 各 1 的若干），`status` 在用 14 个（`done` 15、`published` 14、`current` 13、`draft` 11 …… 另有若干中文自由文本如「作者已批（2026-10-30）」）。合法的 type 枚举只活在一份设计稿的**引文**里（`docs/design/visual-report-gate-plan.zh-CN.md:47` 抄了一段 `research-log|problem|…)` 的旧正则），那份正则今天在任何脚本里都已不存在。
- `docs/index.md` 是**手工维护**的分类入口：自带 `存目（v1.0.0 清仓移出）` 一节，把库外旧稿降级为反引号纯文本、明确「不作图谱链接」（`docs/index.md:22-31`），写作纪律段指向词表与校验器（`:33-36`）。全仓 grep 未发现任何生成 `index.md` 的脚本；校验器的 `--graph` 只导出 `{nodes,edges}` JSON（`check_wiki_docs.py:634-689`、`:822-825`）。
- 反面先例值得借用：`triple-star/docs/INDEX.md`（另一条线）首页就写明「本件只列目录职责与主题指针，**不逐件列名**——逐件列名在这次重构中被证明是腐化之源」，并把状态收束到 `status.md` 单点。本仓 `index.md:14-21` 恰恰是逐件列名的形态，已经出现描述与实际文档不齐的迹象（多篇新文档未挂入）。

### 1.3 wiki-docs 规范与校验器（逐条）

规范只有一个权威件：`visual-report/skills/wiki-docs/SKILL.md`（运行时副本 `C:\Users\Administrator\.stand\plugins\visual-report\skills\wiki-docs\`，仓内源 `src/main/resources/seed/plugins/visual-report/skills/wiki-docs/`）。五条强制条款（`SKILL.md:14-64`），四条的机器面在 `scripts/check_wiki_docs.py`（Python 3 stdlib，零依赖）：

| 规则 | 名字 | 判据 | 代码位置 |
|---|---|---|---|
| R1 | `obsidian-format` | markdown 文档必须带 front-matter（[[glossary#^front-matter]]），且 `type` 或 `status` 至少一个在场（html 豁免） | `:501-508` |
| R2a | `broken-link` | 两侧双方括号的目标依次按「精确 vault 相对路径 → 大小写不敏感 → 全库唯一 basename → 词条注册表」四腿解析，全落空即违规（带锚不回退；bracket 语法见 §1.5 围栏） | `:512-531`、解析器 `:434-466`、`normalize_target` `:421-427` |
| R2b | `broken-anchor` / `broken-block` | `#标题` 必须精确命中目标文档标题（小写比对）；`#^块锚` 必须在该文档的块锚集内 | `:524-531` |
| R2c | `orphan-doc` | 零出链且零入链即违规；`index`/`glossary`/`readme` 豁免 | `:533-550`、豁免 `:546-548` |
| R3a | `concept-first-use` | 词表每个术语在非词表文档的**首个正文命中**（跳过标题行）处，若**同一行**没有引用词表、且术语后既非破折号/冒号定义式也非开括号注解 ⇒ 违规 | `:552-594`、定义式正则 `:66-67`、首现 `:481-489` |
| R3b | `duplicate-definition` | 同一术语在 **≥2 篇**非词表文档各自就地定义 ⇒ 违规（词表是唯一定义源） | `:591-594` |
| R3c | `glossary-format` | 词表条目缺块锚 ⇒ 违规 | `:558-560` |
| R4a | `term-format` | 词条块（词表每个块锚段落，或普通文档里 `^term-*` 块）必须能按「名 — 定义」（`—`/`–`/`：`/`:` 分隔）抽出名字 | `:596-617`、`TERM_NAME_SEP_RE` `:329` |
| R4b | `term-duplicate` | 同一文档内词条名唯一 | `:598-605` |

阈值与硬参数（全部集中在代码里）：

| 参数 | 值 | 位置 |
|---|---|---|
| 段落 `text` 摘录上限 | 280 字符（截断加 `…`） | `:229` |
| 段落 `title` 上限 | 60 字符 | `:230` |
| 每文档段落条数上限 | 64 | `:231` |
| 子 vault 递归深度 / 个数上限 | 3 层 / 32 个 | `:730-731` |
| 文档扩展名 | `.md` `.markdown` `.html`（其余一律当附件） | `:63` |
| 退出码 | 0 干净 / 1 有违规 / 2 用法或环境错 | `:38`、`:868`、`:909` |
| 段落锚引用语法 | `#^块锚`＝一段；`#标题`＝一节（完整 bracket 形态见 §1.5 围栏）；**没有 `§` 语法** | `:18-22`、SKILL `:84-88` |

`type` 的合法值：SKILL 只给**场景包的语义集**（`research-log` / `problem` / `literature-note` / `conclusion` / `learning-map` / `adr` / `manuscript` / `source-note` / `outline` / `runbook` / `faq` / `onboarding` / `index` / `glossary`，`SKILL.md:316-331`、场景包 `:338-392`），**校验器不校验**。所以「合法值」今天只是建议，不是闭集。

已知裁决：frontmatter **不得**做术语声明面——`terms:` 键被设计裁定否决（双真源漂移），理由写在 `check_wiki_docs.py:43-44` 与 `:322-326`，设计稿 `docs/design/20261029_123653_term-citation-batch1-spec__delegate-nebflow-dbfd1ffc.zh-CN.md`。这条裁决对证据字段同样适用（见 §4.4）。

### 1.4 vault 有几份：漂移的真实机制

本机存在**两份**功能重复的 vault，各自绑定一个已注册项目：

| 项目（`~/.stand/projects/<name>/project.json`） | workspace | vault | 文件数 |
|---|---|---|---|
| `nebflow` | `D:\workspace@zcode\nebflow` | `nebflow\docs\` | 189（md 142） |
| `nebflow-stand` | `D:\workspace@zcode\nebflow\nebflow-stand` | `nebflow-stand\docs\` | 126（md 93） |

实测差集（以相对路径比对）：**共用 118 份，其中 13 份内容不同**；仅 A（`nebflow/docs`）有 71 份，仅 B（`nebflow-stand/docs`）有 8 份；A 多一个 `ops/` 目录。内容不同的样例：`index.md`、`20261028_171800_审计报告二五机制…md`、`design/20261030_human-readable-presentation.zh-CN.md`、`design/20261028_canvas-ask-mode-design.zh-CN.md`。A 独有的关键件包括 `20261101_173605_doc-render-terminology-design.md`（词条/术语路线勘察，**B 侧图谱完全看不见它**）。

引擎侧把差异放大成结构问题：`GET /api/wiki/graph` 不带 `project` 时是**所有注册项目 vault 的合并图**（`src/main/scala/nebflow/gateway/WikiRoutes.scala:13-24`、`WikiService.scala:15-20`），所以同题双份在合并图里表现为**两个同题节点**；带 `?project=` 才是单 vault 隔离图。

三条叠加的失守腿（按破坏力排序）：

1. **`docs/` 被 gitignore 整体忽略**：`.gitignore:30` = `docs/`。仓内 122 个 `docs/` 文件是靠历史上 `git add -f` 进去的（`git ls-files docs | wc -l` = 122），而**新文档默认进不了 git**——现存 4 例未入库：`20261101_153225_cloud-debug-access.md`、`20261101_154306_neblink-host-access__delegate-nebflow-7d5da51e.md`、`20261102_171500_neblink-device-identity-and-ownership.md`、`attachments/neblink-device-identity-chain.svg`。它们在工作区里可读、可出图，但 `git status` 看不见、切 worktree 即消失、`reset` 即丢。另一例反向漂移：`docs/parallel-session-resources.zh-CN.md` 在主干工作区是**未提交修改**，worktree 里是旧版。
2. **两个项目各写各的 vault**：同一个调查/设计在 A 侧写一份、B 侧写一份（会话所在项目决定写向哪里），无同步机制，只在合并图里见到两个节点。
3. **人读件落点文件级过期**：`visual-report/skills/visual-report/SKILL.md:272` 仍规定人读件落 `~/.nebflow/docs/<域>/`，该目录不存在（实盘落点已是 `<workspace>/docs/`）。这类「指向旧家」的条文会让节点把文档写到图谱扫不到的地方。

（日程里 2026-11-08 那条检查任务的口径与此一致：比对 `D:\workspace@zcode\nebflow\docs\`（现为「vault 唯一真相」）与 `nebflow-stand\docs\`（deploy/ustc-main 分支上的源目录），回收需先取作者授权。）

### 1.5 互链与检索

引用语法的完整形态（本件只能用围栏展示，正文裸写会被当链接解析——正是 `docs/glossary.md:10` 提醒的那条）：

```
[[目标]]                    引用整篇文档
[[目标#标题]]                引用一节
[[目标#^块锚]]               引用一个段落（词表条目、词条、证据段都是块锚段落）
[[目标|显示别名]]            带别名的引用
![[attachments/x.png]]      内嵌附件
[文本](topic.md)            普通 markdown 链接（指向库内 md 时同为图谱边）

本件在后续章节里提议的两类新引用（形态同上，只是命名空间不同）：
[[entities#^ent-<slug>]]                     实体条目
[[<调查档案>#^ev-<抓取时间>-<sha前4>]]        证据条目
```

- 互链形态就是 wikilink 四式 ＋ 段落锚二式（`docs/glossary.md:12-19` 的语法一览，括注「裸写双方括号会被当真链接解析」，示例一律进代码围栏）。全文 437 处 wikilink；**4 篇零出链**（`20261030_084500_dock-message-badge…`、`20261101_125600_mobile-chat-blank-hotfix…`、`design/20261028_215449_thinking-config-research…`、`design/20261028_triple-line-integration-contract.zh-CN.md`），按 R2c 只有「零出且零入」才违规，实测基线 1 篇孤儿。
- 反向链接有，且是**算子**不是索引：文档查看器在正文末尾追加可折叠的「反向链接」区（`src/main/resources/web/js/viewers/markdown.js:627-677`，标题词条 `wiki.backlinks`），边来自 `GET /api/wiki/graph`。
- 图工具链：`GET /api/wiki/graph`（合并/单项目/子 vault 三态）、`/api/wiki/vaults`、`/api/wiki/paragraph`（段落快照，hover 小窗）、`/api/wiki/term`（词条注册表单查，`WikiRoutes.scala:13-44`）；引擎侧索引器 `WikiIndex.scala`（与校验器逐字段同构：段落索引、`terms[]` 投影）。
- **没有文档全文检索**。检索面今天只有一个：`GET /api/search/chat`（`src/main/scala/nebflow/gateway/ChatSearchRoutes.scala:15`、`:38`），数据层 `SearchIndexService` 是「一 face 一个 SQLite」（`<dataRoot>/search-index/<face>.db`），实装 face 只有 chat（`ChatSearchAdapter`）。`tools/stand-recall` 是**对话历史**库：头注明确「只收主 agent 会话，排除一切工具结果/thinking/压缩摘要」，写 `~/.stand/memory-db/`，与文档无交集。结论：文档只有**图**（节点/边/词条/段落），没有**文**（正文检索）；调查插件要「查以往查过什么」，检索必须另设或复用图的结构信息。

---

## 二、术语体系现状

### 2.1 有，而且是两套

| | 仓根 `GLOSSARY.md`（202 行） | `docs/glossary.md`（vault 词表，19 条） |
|---|---|---|
| 管什么 | **名字**（UI 分区、组件、后端分层、门禁、流程语汇、易混对） | **概念**（vault/图谱/引用/术语机制自身的名词） |
| 形态 | markdown 四列表格：术语 / 英文或代码拼写 / 一行定义 / **权威落点**（`GLOSSARY.md:1-8`、`:17-20`） | 段落条目 + 块锚：`名 — 定义 ^块锚`（`docs/glossary.md:21-59`） |
| 唯一性 | 无机器校验，靠人 | 机器校验（R3/R4，「词表 = 唯一定义源」） |
| 冲突优先级 | 显式四级：`brand.conf` > 协议/兼容字段名 > 门禁脚本常量 > **本表** > 其它文档与代码注释（`GLOSSARY.md:6`） | 单一来源，无优先级问题 |
| 维护规则 | 只增不改：改口径须**并置**新条目并注明日期与理由；无落点的词不进表（`GLOSSARY.md:8-9`、`:165-170`） | 新增概念进词表、其余引用；同批校验 |
| 一致性检查脚本 | **无**（`grep -rl GLOSSARY scripts/` = 0；`scripts/check-*` 17 个门禁无一读它） | **有**（`check_wiki_docs.py` R3/R4） |
| 谁在消费 | 人（作者、节点读表对齐词） | 引擎（`GET /api/wiki/term`、图谱 hover 词条、校验器） |

两套表**互不引用**：`GLOSSARY.md` 里没有一条指向 `docs/glossary.md` 的链接，词表里也没有一条引用 `GLOSSARY.md`。

### 2.2 没有统一术语表时，术语实际靠什么维持

精确说：**有表，但没有一条跨表、跨仓的一致性通道**。实际维持术语一致的四个载体：

1. **提示词注入的命名纪律**（`NodeEngineContract.scala:697-715`、`PromptCatalog.scala:264`、`PromptSections.scala:289`）——管文档命名与溯源，不管词义。
2. **代码注释里的裁定并置**（`GLOSSARY.md:145-148`「裁定 / 并置注 / 铁律 / 红线」条目本身）——术语变性时在注释里追加新注，旧注逐字保留；这是仓内最硬的术语执法方式，但只在代码面。
3. **文档批的格式范本**（`docs/templates/console-403-diagnosis.md:1-12`、`templates/console-load-perf.md`）——范本示范「概念首现即解释或引用 `[[glossary#^concept-first-use]]`」，把术语纪律变成可模仿的样板。
4. **词表 + 校验器**（`docs/glossary.md` + R3/R4）——唯一机器执法的一层，但覆盖面只有 vault 概念，不含 GLOSSARY.md 的名字域。

### 2.3 代码层的隐式术语体系

`src/main/scala/nebflow/`、`nebflow/neblink/`、以及 Rust 线 `triple-star/cap/apps/stand/src/neblink/` 的中文术语是**真实存在、且被工程纪律默认的**，但只有**局部集中定义**：

- 「共存帧」「契约帧」「真身」三个词在 Rust 侧有集中的定义块：`triple-star/cap/apps/stand/src/neblink/codec.rs:54-73`（共存帧 = 与 `proto:1` 契约帧同跑一条 `relay-ws`、但**没有 `proto` 字段**的那批帧；只识别放行、语义由调用方按 `kind` 处理）、`:195`（真身 = 原 Scala 实现的形状基准，逐处点出 `PortalWireCodec.scala:340-349`）、`:284`、`:345`（共存帧不带 `proto` 必须放行的回归锚点）。用法统计：`帧` 30 处、`真身` 18、`共存帧` 6、`契约帧` 5。
- 也就是说：这些词在**使用点就地定义**（文件头 doc comment），没有进 `GLOSSARY.md`（全表 202 行里 neblink 相关只有 `:137` 一条「子系统名保持 `neblink` 不改名」与 `:195` 的 `capability-server` 规划态条目）。Rust 线自带自己的文档树（`triple-star/docs/`，`INDEX.md` + `status.md` + `decisions/`），与 Scala 线 `docs/` 不通。
- 已有跨机同步的**身份/展示名单点**可借：`src/main/scala/nebflow/core/entity/AgentIdentityStore.scala:10-40`（`displayName` 写穿单点 + `identityUpdatedAt` LWW 时间戳 + 内容 sha，日志只见名字与 sha 前 8 位）。

结论：**隐式体系成立、无中心定义**。`GLOSSARY.md` 自称唯一术语源（`GLOSSARY.md:171` 附近「裁定称 GLOSSARY 为唯一术语源」），但 neblink/互联域的词从未入表——这是「表存在但与代码并行演进」的典型。

---

## 三、与调查插件的联动设计

前置：调查插件的产物不是页面，是**知识与证据**；它的循环是「抓取 → 整理建模 → 分析 → 发现缺口 → 再抓」，天然增量。所以设计的三个硬要求：**一人一档可复用**、**结论可追到原始字节**、**循环重复抓手不产生重复文档**。

### 3.1 产物形态：三件套 + 一张登记表（推荐）

```
docs/
  investigations/
    20261102_180500_<topic>__<chainId>.md   ← 调查档案（阶段件命名，一次调查一份，逐轮追加）
  entities/
    <canonical-slug>.md                     ← 实体档案（活文档命名，一人一档，跨调查复用）
  entities.md                               ← 实体登记表（规范名 + 别名集，块锚条目）
  attachments/
    evidence/<investigation-id>/
      manifest.jsonl                        ← 每次抓取一行（URL / 时间 / sha256 / 状态码 / 快照路径）
      20261102T174512Z_example.com_org.html ← 原始快照（不可变，内容寻址）
```

**一次调查循环落几个文件**（推荐口径）：

| 产物 | 数量 | 性质 | 理由 |
|---|---|---|---|
| 调查档案 | 1（同一调查**恒定一份**，每轮追加一节） | 追加不改 | 时间是调查的第一维度；轮次分节让「这几轮没变化」本身成为可读结论 |
| 实体档案 | 0..N（只对**新出现或需改写**的实体） | 活文档，跨调查复用 | 实体是长期对象，调查是短期过程；混在一处必然重复 |
| 快照文件 | 每次抓取 1 个 | 不可变，内容寻址 | 证据的原子单位；不重写、不覆盖，sha 相同即复用不新增 |
| `manifest.jsonl` | 每次抓取 1 行 | 追加 | 增量账本，去重与「上次抓到什么时候」都由它回答 |
| 实体登记表 | 新实体各 1 条 | 只增不改 | 消歧的权威表，见 §3.2 |

理由合并成一句：**过程进档案（追加）、对象进实体（复用）、字节进快照（不可变）、指针进 manifest（增量）**。四个面各有一个「改法」，因此不会互相污染——这正是仓库既有纪律（`GLOSSARY.md:8` 的「只增不改／并置」、`.nebflow/` 过程件与人读件的分离）在调查域的翻版。

不建议的形态：把证据塞进调查档案正文（无法哈希、无法复用、档案体量失控）；把实体写成调查档案的一节（同一人在三次调查里就有三份互不相识的档案）。

### 3.2 实体消歧就是术语问题：分两层，共用一套机制

**推荐：共用机制、两个命名空间，不共用一张表。**

- L1 **概念术语**：`docs/glossary.md`（沿用），块锚 `^slug`。
- L2 **实体登记**：`docs/entities.md`，块锚 **`^ent-<slug>`**（刻意避开 `^term-` 前缀）。

形态照抄词条（校验器已实现的抽取规则直接可读）：

```markdown
- 张某某（张某某 微信 / @zhang_gh / Zhang Mou） — 某协会 2026 年理事会成员；与 [[entities#^ent-li-si]] 同期出现于同一批公告。 ^ent-zhang-moumou
```

登记表的每条要带的东西（实体档 vs 术语条的区别只有这三列）：

| 列 | 内容 | 为什么必须有 |
|---|---|---|
| 规范名 | 唯一、稳定、文件名 slug 的来源 | 一切引用的锚 |
| 别名集 | 账号/昵称/备注/曾用名，**显式声明**（括注内以 ` / ` 分隔） | 消歧的全部弹药；不做模糊匹配 |
| 身份锚 | 可核验的稳定标识（平台 ID、URL、机构+任期） | 别名会漂，ID 不会 |
| 一句话身份 | 像术语定义一样只有一句 | 让「这是谁」在任何文档里只定义一次 |
| 关联证据 | 指向 `^ev-*` 块锚或 manifest 行 id | 身份判断本身也要有据 |

**为什么不合表**（三条硬理由，都有现成证据）：

1. **注册表会被污染**：词条注册表的消解优先级是「词表唯一命中 > 同级唯一命中 > 多命中不猜」（`check_wiki_docs.py:379-395`、引擎同构 `WikiIndex.scala:536`）。若实体进词表，人名短形引用会走同一条优先级链，人名与概念名在同一条解析链上竞争；`docs/glossary.md` 的「词表 = 唯一定义源」语义被稀释。
2. **R3 会把人名当概念审**：R3 要求每个词表术语在文档首现处「就地解释或引用」。人名若入词表，**每一次提到某人**都要背上解释或引用的义务——这会让所有调查档案变成引用地狱，而解释一个人名本身又把定义复制到多篇文档，触发 R3b 重复定义。
3. **生命周期不同**：术语「只增不改」（改口径＝并置新条，`GLOSSARY.md:8`）；实体必然**合并/拆分/改判**（同一人两个号合并、同名不同人拆开）。用「只增不改」的容器装「必然重判」的对象，第一次重判就会破坏纪律。

**为什么仍要共用机制**（而不是另造一套）：

- 声明形态相同（`名（别名） — 一句话 … ^锚`）、别名派生相同（尾括号组拆 lookup 键，`check_wiki_docs.py:336-345`）、消解哲学相同（**多命中一律不猜，回候选列表**）、引用语法相同（目标落在 `entities#^ent-<slug>` 上，形态见 §1.5 围栏）、校验器可**同一个脚本里扩一条规则**（§3.5）。差别只有命名空间前缀与「谁允许重判」。

同类先例已在代码里跑了一年：好友名册的解析链是 **L0 `remark`（用户自设别名）→ L1 `username`（稳定 ID）→ L2 `displayName` → L3 唯一前缀**，多命中一律报错并附候选，绝不猜（`src/main/scala/nebflow/neblink/FriendRoster.scala:78-139`）。实体登记表的解析链照这个形状落地即可，不需要发明新语义。

**取舍声明**：分层成本 = 两个文件、两套引用前缀、实体表中引用概念时须写 `glossary#^<块锚>` 形式的引用。合表收益（一次解析、一份文档）抵不上上面三条代价。

### 3.3 增量与去重

三层各管一段，互不替代：

1. **字节层去重 = 内容寻址**。抓回来先算 sha256，与 `manifest.jsonl` 里同 URL 的上一行比对：
   - sha 相同 ⇒ **只追加一行 manifest（`status: unchanged`），不新增快照文件，不新增实体变更行**，档案里只记「本轮 N 源全部无变化」。
   - sha 不同 ⇒ 新增快照文件（旧快照永不覆盖）＋ manifest 一行（`status: changed`）＋ 档案该轮段记「变在哪」＋ 实体档案追加变更行。
2. **过程层增量 = 档案轮次段**（追加）：

   ```markdown
   ## 轮次 3（2026-11-02 17:45）
   - 新增：A 协会 2026 年理事名单（3 人，2 已知 / 1 新实体 → [[entities#^ent-wang-wu]]）
   - 变化：张某某职务由「副秘书长」改为「秘书长」（证据 [[<本档>#^ev-20261102T174512Z-9f2c]]）
   - 无变化：gov.example.org/a 等 7 个源（sha 一致，见 manifest 本轮行）
   - 新缺口：B 公司的实际控制人仍无一手来源 → 下一轮目标
   ```
3. **对象层增量 = 实体档案的 `## 变更史` 表**（追加，不重写上文）：`日期 | 变更 | 证据锚`。旧判断永不删除，只被新行取代——与 `GLOSSARY.md` 的「并置」纪律同款。

选型理由：**不选「每轮一个快照文件」**——那会把「同一调查」切成 N 份互不相链接的文档，图谱里是 N 个孤儿；不选「纯更新日志」——那会把证据字节挤进正文。**轮次段 + 变更史 + manifest** 三段式让「上次抓到哪、这次变没变、变化凭什么」各有一处唯一答案。

### 3.4 证据可回溯：最小引用格式

**正典形态（推荐）**——证据本身是正文里的一个块锚段落，结论侧只写锚引用：

```markdown
[结论] 张某某 2026 年任秘书长 ^claim-zhang-secretary

> 依据：协会官网理事名单页显示 2026 年度秘书长为张某某。[[<本档>#^ev-20261102T174512Z-9f2c]]

原文快照（不可变）：![[attachments/evidence/inv-20261102-a/20261102T174512Z_example.com_org.html]] ^ev-20261102T174512Z-9f2c

- 来源 URL：https://example.org/about/council
- 抓取时间：2026-11-02T17:45:12+08:00（UTC 09:45:12）
- 内容哈希：sha256:9f2c1a7b4e…（完整 64 位十六进制见 manifest.jsonl 同行）
- 抓取方式：HTTP 200 / text/html / 未登录
```

也就是**四要素 + 一锚**：`URL` ＋ `抓取时间（带时区）` ＋ `sha256` ＋ `本地快照路径`，外层一个 `^ev-*` 块锚供引用。时间用秒级＋时区（调查里「先后」是判断依据）；哈希给短形摘要、完整值放 manifest（避免正文漂移）；快照路径用 vault 相对路径经内嵌附件语法（§1.5 围栏末行）引入（附件即图谱节点，`SKILL.md:394-400`）。

**能否被现有体系承接——四问四答**：

| 候选落点 | 判定 | 依据 |
|---|---|---|
| 正文块锚段落 | **承接得了，推荐** | 段落锚是现成语法（`#^块锚` 形态，§1.5 围栏第三行），R2b 会校验块锚存在，图谱 hover 直接显示「那一段」 |
| `attachments/evidence/<id>/` | **承接得了** | `attachments/` 是规范指定的唯一附件根，子目录合法（现有 `attachments/canvas-ask-mode/`、`attachments/wiki-graph-redesign/` 先例） |
| frontmatter 字段（`evidence:` / `sources:`） | **不要** | 与已被否决的 `terms:` 同罪：双真源，正文改了 frontmatter 不知（`check_wiki_docs.py:43-44`、`:322-326`） |
| 仓根 `evidence/` 目录 | **不要混用** | 那是**过程取证**面（208 个批目录，`evidence/<日期>_<批>/shots…`），属机器面；调查证据要进图谱就得在 vault 里 |

一个必须承认的限制：`manifest.jsonl` 不是文档，图谱不索引它（`collect_docs` 只收 `.md/.markdown/.html`，其余归附件，`check_wiki_docs.py:63`、`:694-725`）。所以 **manifest 是机器的账本、块锚段落是人的入口**——两者由 `^ev-<抓取时间>-<sha 前 4>` 这个 id 对齐，不要指望二选一。

### 3.5 术语/实体一致性检查：可行性与代价

可行性**高**：校验器已有全部零件（词条抽取 `terms_of`、别名派生 `term_alias_keys`、选择器 `select_term`、段落索引、首现扫描 `first_occurrence`）。建议以 **R5 `entity-registry`** 增补，三条规则、分三次落地：

| 规则 | 判据 | 代价 |
|---|---|---|
| R5a 实体引用可解析 | 指向 `entities#^ent-*` 的块锚引用必须命中已声明的实体块（今天已被 R2b 覆盖一半） | ~0（复用） |
| R5b 实体首现必须引用（实体版 R3） | 在 `investigations/`、`entities/` 两棵树里，**规范名或显式别名**的首个正文命中行必须同时出现指向该实体的引用 | 中：需为每个别名标「强别名/弱别名」（「李伟」这类大众名必须可豁免，否则整篇报红）；参考 R3 的实现 ~60 行 |
| R5c 命名空间不交叉 | 实体名不得作为概念术语声明（`^term-*` 与词表条目都不许出现实体规范名）；同一实体不得重复声明 | 低：~30 行，纯集合比对 |

**真正的代价不在代码，在误报治理**：R3 今天已经会因为术语短形与常见词重叠而报红（项目 vault 基线读数 21 条：14 条 `broken-link`、3 条缺 front-matter、3 条概念首现、1 条孤儿）；实体名的分布比术语更脏（同名的不同人、缩写撞词）。所以落地顺序建议 **R5a → R5c →（用一个真实调查跑一轮）→ 再决定 R5b**，并给 R5b 留行级豁免标记。

另一条更便宜的成果：**跨表通道**。`GLOSSARY.md`（名字）与 `docs/glossary.md`（概念）今天零互链，最低成本的一致化是「`GLOSSARY.md` 里每个涉及 vault 概念的条目补一条 `glossary#^<条目块锚>` 引用」——一次性人工成本，收益是两套表第一次连成一张图。

### 3.6 与 PTC 的关系：机械部分可以进 PTC，写文档不该走 `tools.write`

现状约束（三处硬读数）：

1. **PTC 的可写根是插件自己的数据区**：manifest 声明 `workspace.default_cwd` / `fs_roots` = `${PLUGIN_DATA}/workspace`（`plugins/ptc-python/plugin.json` 的 `extensions.ptc.workspace`；契约同形 `contracts/ptc/v1/README.md:74-75`、`:92`）。
2. **`${PLUGIN_DATA}` = `<dataRoot>/plugin-data/<plugin>/`**，与项目工作区无关（`src/main/scala/nebflow/core/plugin/PluginMcpManager.scala:235-250`）。
3. **请求只能收窄、不能扩根**：执行器把 manifest 根算成 `DEFAULT_ROOTS`（`plugins/ptc-python/skills/ptc-python/scripts/ptc_executor.py:65`），请求期 `fs_roots` 必须是其子集，否则 `Invalid`（`:341-353`）；fs 工具 server 侧再做一次前缀判定（`ptc_tools_fs.py:60`）。契约把这条钉成不变量：「代码只能碰 `fs_roots`」（`contracts/ptc/v1/README.md:225`），并有「路径逃出 fs_roots 一律拒绝」的符合性用例（`contracts/ptc/v1/conformance/cases.v1.json:330`）。占位符面只认 `${PLUGIN_ROOT}` / `${PLUGIN_DATA}` 两种形态，不接受任意绝对路径（旁证：MCP `cwd` 的同款校验 `PluginRegistry.scala:1109-1124`）。

结论：**`D:\workspace@zcode\nebflow\nebflow-stand\docs\` 落在 fs_roots 之外，PTC 脚本直接 `tools.write` 写 vault 会被拒**。所以分三层：

| 层 | 干什么 | 走什么 |
|---|---|---|
| 取与算 | 抓取、解析、抽字段、算 sha256、比对上次 sha、生成候选段落 | **PTC 脚本**（`ptc_run` 只读档已够；需要落工作区临时件才用 `ptc_run_rw`），写在 `${PLUGIN_DATA}/workspace/` |
| 落盘 | 把候选段落写进 vault、写快照与 manifest 行 | **宿主侧**：节点自身的 `Write`/`Edit`，或一个小工具（见下） |
| 校验 | 落盘后跑 `check_wiki_docs.py` | 任何进程都行（纯读） |

推荐 v1 做法：**PTC 只产结构化结果（stdout JSON/段片），文档由宿主侧一次 `Write` 落地**；若嫌"证据快照"这步太碎，加**一个宿主工具** `evidence.put(url, body, fetchedAt)`——由宿主写 `docs/attachments/evidence/…` 并回一行 manifest，PTC 侧只传内容与元数据。这样 fs_roots 边界一字不动，证据仍然落在 vault 里。

要彻底放开则需要机制改动（三选一，成本递增）：(a) manifest 认识项目工作区占位（改动面 = `PluginRegistry` 占位校验 + `PluginMcpManager` 展开 + `ptc_executor.py:65` + 契约 schema/cases）；(b) 宿主在请求期签发临时根（授权令牌化）；(c) 维持现状（本件推荐 v1 走 (c)）。红线：无论哪条，`fs_roots` 逃逸必须继续报错——符合性用例 `cases.v1.json:330` 不能被改软。

### 3.7 收束：调查循环 → 文件动作

| 循环步 | 文件动作 | 产物 |
|---|---|---|
| 抓取 | 算 sha → 比对 manifest → 新快照（仅 changed）＋ manifest 行 | 快照 ＋ 账本 |
| 整理建模 | 抽实体 → 查 `entities.md`（规范名/别名）→ 命中则复用 `^ent-*`，未命中则开新条 | 实体登记表增量 |
| 分析 | 结论写成 `^claim-*` 段落，每条挂 `^ev-*` 证据锚引用 | 调查档案轮次段 |
| 发现缺口 | 缺口写成档案末节「新缺口 → 下一轮目标」（可被下一轮直接读回） | 同一份档案 |
| 再抓 | 只对「新缺口」定向抓取；未变源只追加 manifest 行 | 回到第 1 步 |

---

## 四、现存缺口（按修复优先级）

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| 1 | `docs/` 被整体 gitignore，新文档不入库 | `.gitignore:30`；存量 4 例仅在工作区（含 `20261102_171500_neblink-device-identity-and-ownership.md`） | 切 worktree / 清工作区即丢；合并队列看不见 |
| 2 | 两个注册项目各一份 vault，内容已漂 13 份、各 8/71 份独有 | `~/.stand/projects/{nebflow,nebflow-stand}/project.json`；合并图 `WikiRoutes.scala:13-24` | 合并图同题双节点；「唯一真相」无机制保证 |
| 3 | `CONVENTIONS.md` 被 4 处引用但盘上不存在 | `NodeEngineContract.scala:697`、`PromptSections.scala:282`、`NodeChainAttributionSpec.scala:94/101` | 命名纪律只有提示词字面量，无可核对规范件 |
| 4 | 人读件落点条文指向不存在的 `~/.nebflow/docs/` | `seed/plugins/visual-report/skills/visual-report/SKILL.md:272` | 照条文写 = 写到图谱扫不到的地方 |
| 5 | frontmatter `type` 无闭集，在用 19 个取值 | `check_wiki_docs.py:501-508`；枚举只活在设计稿引文 `design/visual-report-gate-plan.zh-CN.md:47` | 图谱按 type 过滤形同虚设 |
| 6 | 无文档全文检索；检索面只有对话 | `ChatSearchRoutes.scala:15/38`、`SearchIndexService` 单 face；`tools/stand-recall` 头注 | 「查查以前调查过什么」只能靠图，无法按正文 |
| 7 | `GLOSSARY.md` 无任何机器校验，且与词表零互链 | `grep GLOSSARY scripts/` = 0；两表互不引用 | 名字域与概念域各自漂移 |
| 8 | 存量违规 21 条（14 断链 / 3 缺 front-matter / 3 条首现未解释（R3）/ 1 孤儿） | 校验器基线读数（`--root D:/workspace@zcode/nebflow/nebflow-stand/docs`，本件落笔前不动存量） | 新规则上线前先清存量，否则噪声掩盖新违规 |
| 9 | neblink 域术语（共存帧/契约帧/真身）只在代码注释就地定义 | `triple-star/.../codec.rs:54-73`、`:195` | 跨线（Scala/Rust）指同一事物用同词但无共享定义源 |

## 五、建议的下一步

1. **先补入库腿**（缺口 1）：给 `.gitignore` 加 `!docs/` 并 `git add` 现存 4 例，或在派发侧加一条「docs 新文件必须 `git add -f`」的机械校验。验收：`git ls-files docs | wc -l` 与 `find docs -name '*.md' | wc -l` 相等。
2. **定 vault 单源**（缺口 2）：`nebflow-stand/docs` 为唯一 vault；按 11-08 任务口径比对后回收 `nebflow/docs`（**需作者授权，属破坏性操作**）。
3. **实体登记表最小样本**（§3.2）：拿一次真实调查的 10-20 个实体落 `docs/entities.md`，验证「规范名 + 别名 + `^ent-*`」不干扰 R3/R4（判据：校验器零新增违规）。
4. **证据格式先跑一轮**（§3.4）：一次调查里实落 manifest ＋ `^ev-*` 段 ＋ `attachments/evidence/`，再决定是否进校验器。
5. **校验器 R5 分两批**：R5a/R5c（确定性高）先上；R5b 待一轮真实数据后再定，并预留行级豁免。
6. **PTC 侧只加一个宿主工具**（§3.6）：`evidence.put`，不动 fs_roots。

## 六、假设与限制

- 勘察对象是 `nebflow-stand` 工作区（Scala 线）＋ 本机两个已注册项目的 vault；Rust 线（`triple-star/`）与云端服务端的文档系统只抽读了术语与索引形态，未做完整比对。
- `nebflow/docs` 被日程描述为「vault 唯一真相」，本件按实测口径（文件数/差集/内容比对）陈述，不替作者做回收裁定。
- 本机未安装 `ptc-python` / `ptc-typescript` 运行时（`~/.stand/plugins/` 下无 ptc 包），PTC 结论基于仓内包源、契约与执行器代码，未做端到端复现。
- 本件零代码、零既有文档改动；未新增 `index.md` 挂载（`index.md` 属既有文件，红线内不动），故本文的入链靠词表与既有文档呈现，若需挂入分类入口由后续批次补。
