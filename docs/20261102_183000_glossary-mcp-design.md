---
type: design
status: current
---

# 词条构建 + 检索 MCP 面（visual-report 插件的功能扩展）· 设计与验收

**一句话结论**：词条体系原本有「表 + 校验器」，缺「检索」与「写入通道」；本批把这两件事做成 visual-report 插件的一个 stdio MCP 面（`plugin_visual-report_glossary`），**文件仍是唯一真相源**（概念 `docs/glossary.md` 的 `^slug`、实体 `docs/entities.md` 的 `^ent-<slug>`），SQLite FTS5 只当可删可重建的索引。真实 vault 上实测：检索命中真实词条、反向链接与 `grep` 逐条对齐、一致性检查复现出存量读数（21 条）、写入后条款校验器零新增违规。

本文所用概念：文档库（[[glossary#^vault]]）、front-matter（[[glossary#^front-matter]]）、wiki-link（[[glossary#^wiki-link]]）、引用关系（[[glossary#^citation-edge]]）、概念首现（[[glossary#^concept-first-use]]）、段落锚（[[glossary#^paragraph-anchor]]）、图谱视图（[[glossary#^graph-view]]）、附件（[[glossary#^attachment]]）、插件包（[[glossary#^plugin-package]]）、引擎种子（[[glossary#^engine-seed]]）、运行时实例（[[glossary#^plugin-instance]]）。分层与消歧的设计依据来自 [[20261102_180500_doc-and-term-system-survey]]；证据块锚格式与 [[20261102_180000_network-search-plugin-survey]] 共用。

---

## 一、要解决的问题（缺口证据）

| # | 缺口 | 现状证据 | 本批怎么落 |
|---|---|---|---|
| 1 | 文档没有全文检索 | 检索面只有对话库：`tools/stand-recall` 头注明确只收主 agent 会话；`GET /api/search/chat` 的实装 face 只有 chat（`ChatSearchRoutes.scala`、`SearchIndexService`）。想搜文档只能 grep | `glossary_search`：词条级检索（名称/别名/slug/定义全文 + 模糊兜底） |
| 2 | 反向链接只能看不能问 | 文档查看器末尾那段「反向链接」是渲染算子（`web/js/viewers/markdown.js`），拿不到「谁在第几行引了它」 | `glossary_lookup` / `glossary_links`：`file:line` 级反向链接，双向可查 |
| 3 | 新词条只能手工改文件 | 没有任何写入通道，节点要新增词条得自己重排整篇词表 | `glossary_put`：按声明格式**写进 markdown**，只替换目标那一段 |
| 4 | 一致性检查只知道「有违规」，不知道「词条层缺什么」 | `check_wiki_docs.py` 覆盖 R1-R4，不含「孤儿词条」「两层命名空间混用」 | `glossary_validate`：**直接调用**那个校验器（不改它任何判定）+ 本层两项新增 |

## 二、数据模型：两层，两个命名空间，不合并

| 层 | 文件 | 块锚 | 一条的形态 | 允许重判 |
|---|---|---|---|---|
| L1 概念 | `docs/glossary.md` | `^<slug>` | `规范名（别名1 / 别名2） — 一句话定义 ^slug` | 否（只增不改，改口径＝并置新条） |
| L2 实体 | `docs/entities.md` | `^ent-<slug>` | `规范名（别名1 / 别名2） — 一句话身份 ^ent-slug` | 是（同一人两个号合并、同名不同人拆开） |

**为什么不合并**（三条，第 2 条本批有实测）：① 注册表消歧链会被污染——词条解析优先级是「词表唯一命中 > 同级唯一命中 > 多命中不猜」，人名短形一旦入表就在同一条链上与本库概念竞争；② **R3 会把人名当概念审**——本次自测把一个实体名「Nebflow」临时塞进词表，同一文档库的概念首现报红从 0 涨到 **54 行**（负控见 §七）；③ 生命周期不同，「只增不改」的容器装不下「必然重判」的对象。

实体层刻意避开 `^term-`：那个前缀是条款校验器 R4 的注册面（`TERM_ANCHOR_PREFIX = "#^term-"`），实体若占它，`terms_of` 会把实体当词条抽出，直接把 ② 的代价搬到校验器里。

**索引里的字段**（`entries` 表：一层一条）：`slug` / `kind`(`concept`|`entity`) / `name` / `aliases` / `definition` / `note`（如 `来源：…`）/ `rel` + `line` + `endLine` + `anchor`（定位）/ `valid`（能抽出名与定义）。别名派生与校验器同规则（尾括号组拆 lookup 键，组内再按 ` / ` 拆），因此**同一个实体在检索面与校验面看到的是同一组键**。

## 三、块锚约定（与网络调查插件共用一套）

| 锚 | 谁写 | 语义 |
|---|---|---|
| `^<slug>` | 词表条目 | 概念定义（唯一定义源） |
| `^ent-<slug>` | 实体登记表条目 | 实体身份（规范名 + 别名 + 一句话身份） |
| `^claim-<slug>` | 调查档案的结论段 | 一条结论，正文里只写锚引用 |
| `^ev-<抓取时间>-<sha前4>` | 调查档案的证据段 | 一次抓取（四要素：URL / 抓取时间带时区 / sha256 / 本地快照路径） |

四个前缀共用同一套语法与同一套解析语义——引用形态是「目标 ＋ `#^锚`」（原文示例只能进围栏，正文裸写双方括号会被当真链接解析）：

```
[[目标#^锚]]
```

锚必须存在于目标文档，否则校验器 R2b 判 `broken-block`；因此**一个文档库里不需要为「证据」再发明一套引用语法**。本批只实现 `^slug` 与 `^ent-*` 两个前缀的登记与检索；`^claim-*` / `^ev-*` 属于网络调查插件那一侧，它们的**入链**在本批索引里已经能读（`refs.to_anchor` 记录任意锚；只是不会成为 `entries`）。

## 四、索引重建策略（单一纪律）

- **文件是唯一真相源，DB 是缓存**：删除 `${PLUGIN_DATA}/index/` 不丢任何信息；下次工具调用自动全量重建。反过来，任何写操作都先落 markdown，再重建索引——不存在「只改了库」的路径。
- **库位置**：`${PLUGIN_DATA}/index/glossary-<sha1(vault绝对路径)[:12]>.db`。`${PLUGIN_DATA}` = `<数据根>/plugin-data/visual-report/`，与 [[glossary#^plugin-instance|运行时实例]] 的插件目录分离——插件包被种子同步覆盖也不会带走索引；反过来索引也绝不写进插件包（写入前把 `sys.dont_write_bytecode` 关掉，避免 `__pycache__` 落包漂 digest）。
- **失效判定**：每次调用比对文件树签名（文件数 + 总字节 + 最新 `mtime_ns`）。签名变 ⇒ 下次调用整库重建。实测整库重建 **77-100 ms**（vault 125 篇文档 / 154 个文件，Windows + Python 3.12）。
- **多 vault**：根解析顺序为「工具参数 `vault=` → env `GLOSSARY_VAULT_ROOT` → `${PLUGIN_DATA}/config.json` 的 `vaultRoot`」。一个实例下多个项目各有自己的 `docs/`，靠这个显式切换，不猜。
- **中文检索**：SQLite FTS5 的 `unicode61` 分词器把连续汉字当一个 token，直接 MATCH 检索不到子串。索引侧做**逐字切分**（CJK 逐字、拉丁/数字成词），查询侧同样切分后走**短语查询**——中文子串命中即等价，不需要额外分词库。

## 五、工具面（第一批 5 个 + 1 个维护）

| 工具 | 输入 | 输出要点 |
|---|---|---|
| `glossary_search` | `query, limit?, kind?, vault?` | slug / 规范名 / 一句话定义 / 别名 / 命中片段 / `file:line` / 反向引用计数 / 命中类型（name/alias/definition/fuzzy/fulltext） |
| `glossary_lookup` | `slug`（也可给块锚、规范名、别名） | 完整条目 + 反向链接清单（`doc` + `line` + 引用原文）+ `backlinkDocs` |
| `glossary_put` | `slug, kind, name, definition, aliases?, source?, vault?` | 写盘动作（created/updated）、落盘 `file:line`、块锚；索引自动重建 |
| `glossary_links` | `slug?` 或 `doc?`（二选一） | slug 模式 = 引用它的文档；doc 模式 = 它用到的词条 + 全部出链解析状态 |
| `glossary_validate` | `vault?` | 条款校验器 R1-R4 原样结果（含 `file:line`）+ 本层两项（孤儿词条、命名空间混用）+ 分桶计数 |
| `glossary_reindex` | `vault?` | 强制重建耗时 + 条目/文档/引用统计 |

**写盘纪律**：`glossary_put` 只改目标块的行（同 slug 已存在则替换该块，不存在则追加到文件末尾），文件其余字节逐字保留——实测 diff 只落两处（更新一条 = 1 行变化；新增一条 = 空行 + 2 行）。`kind=entity` 且文件不存在时按规范骨架新建（带 front-matter 与一层说明），`kind=concept` 且词表不存在时**拒绝**（概念层不自动新建文件）。

## 六、与网络调查插件的共用点

1. **同一套块锚语法**（§三）：调查侧的 `^claim-*` / `^ev-*` 与本层的 `^slug` / `^ent-*` 都按「目标 ＋ `#^锚`」的同一形态被引用，都由同一条 R2b 判 `broken-block`——不需要第二套引用机制。
2. **同一套消歧哲学**：多命中一律不猜、回候选列表（与 `selectTerm`、好友名册 `L0 remark → L3 唯一前缀` 同款）。
3. **同一处登记表**：调查循环「整理建模」步抽出的实体直接 `glossary_put(kind=entity, slug=…, aliases=[…], source=<证据锚>)` 落 `docs/entities.md`，`source` 写成词条块第二行的 `来源：<证据锚>`——**身份判断本身挂了据**，且不新增语法。
4. **下一批可增的检查项**（本批不做）：R5a 实体引用可解析（今天已被 R2b 覆盖一半）、R5c 命名空间不交叉（本批已在 `glossary_validate` 里以 `namespace-mixed` 实现，只是没进 `check_wiki_docs.py`——改那个脚本的判定属另一批，红线内不动）。

## 七、验收记录（真实 vault：`D:/workspace@zcode/nebflow/nebflow-stand/docs`）

| 项 | 读数 | 判据 |
|---|---|---|
| 已装载 | 引擎日志 `MCP server 'plugin_visual-report_glossary' connected, 6 tools registered` | 实例级 `~/.stand/plugins/` 的 `mcp.json` 确实在装载器扫描面内 |
| 检索真实词条 | `glossary_search("段落锚")` → `paragraph-anchor`（`glossary.md:31`）；`glossary_search("词表")` 命中定义含「词表」的条目 | 词条真实存在（不是自造 slug） |
| 反向链接 | `glossary_lookup("paragraph-anchor")` → 6 条引用 / 4 篇文档；`concept-first-use` 10、`mockup-sample` 7、`force-layout` 1——与 `grep -rn "#\^<slug>"` 计数**逐条相等** | 反向链接是真实边，不是近似 |
| 一致性检查 | 复现 **21 条**（14 断链 / 3 缺 front-matter / 3 概念首现 / 1 孤儿），与 [[20261102_180500_doc-and-term-system-survey]] §四 缺口 8 的读数一致 | 数字对得上，分桶也对得上 |
| 负控：实体名进词表 | R3 报红 0 → **54 行**（同一 vault，只加一条同名概念） | 支持「两层不合并」的裁决 |
| 负控：命名空间混用 | 三处构造（概念与实体同名 / 实体落 `templates/` / 文档内 `^term-*` 与实体同名）→ `namespace-mixed` 三条，`file:line` 精确 | 新增检查项真的会报 |
| 写入后校验 | `glossary_put` 新建 3 条实体 → `check_wiki_docs.py --root docs` **零新增违规**（违规数不变，且该文件本来只有 1 条存量断链） | 「写进去不破坏条款」 |

> 说明：验收期间该文档库是**共享活检出**，另一个会话在同期落地了新文档（文件数 128 → 161），因此「存量违规」读数随文件状态变动；上表的 21 条是同一树上与调研稿口径对齐的那次读数，其余各项均在同一会话内复算。

**装载侧的事实（首批实测，供后续插件复用）**：装载器扫的是**数据根** `plugins/`（`PluginRegistry.scala:90` `pluginsDir = dataRoot / "plugins"`），实例级与仓内插件走同一条路；`mcp.json` 必须在**插件根**（`PluginRegistry.scala:784-794`），放子目录不认；`$schema` 必须是 canonical `https://agent-plugins.org/schemas/1.0.0/mcp.schema.json`，否则整个 MCP 组件判 invalid（插件本身继续装载）；server entry 的字段 allowlist 只有 `type/command/args/env/cwd/url/headers`——`timeoutMs` 之类会被「unknown field ignored」丢掉；`command` **不展开占位符**（只 `args`/`env`/`cwd` 展开 `${PLUGIN_ROOT}`/`${PLUGIN_DATA}`），所以 Windows 上必须写死绝对路径（裸 `python` 会命中 Microsoft Store 转发桩）；`env` 不得声明 `PLUGIN_ROOT`/`PLUGIN_DATA` 两个键（装载层直接判该 entry invalid），但运行期这两个变量会被注入子进程。

## 八、不做的事 / 风险

- **不改** `check_wiki_docs.py` 的任何既有判定（本批对它只有「调用」关系），也不新增它的 R5 规则——那属另一批。
- **不清理**存量违规（红线：不改既有文档），`glossary_validate` 只报不修。
- **不合并**两层登记表（§二），也不给实体开第二个定义源。
- 风险 1：索引是缓存，若有人绕过本面直接改 markdown，索引靠签名失效重建（不会读到旧内容），但**并发写入同一文件**的两个人仍是最后写者胜——这是文件真相源的固有代价，不是本层的 bug。
- 风险 2：`glossary_put` 的块内第二行（`来源：…`）参与「连续非空行成块」的分组，若有人在条目块内手工插入空行，块会被拆开、块锚落到后半段——此时 `glossary_validate` 会以 `orphan-term` / `term-format` 显形。

## 九、相关文件

- 实现：`<插件根>/mcp/glossary_server.py`（协议面）、`<插件根>/mcp/glossary_vault.py`（解析/索引/写回）、`<插件根>/mcp/selftest_stdio.py`（不经引擎的 stdio 冒烟）、`<插件根>/mcp.json`（装载声明）。
- 登记表：[[glossary]]（概念层）、[[entities]]（实体层）。
- 实体层首批真实条目：本插件包 [[entities#^ent-visual-report]]、引擎与产品名 [[entities#^ent-nebflow]]、互联子系统 [[entities#^ent-neblink]]——它们同时是本面的反向链接用例（引用方就是本文件）。
- 条款与校验器：`skills/wiki-docs/SKILL.md`、`skills/wiki-docs/scripts/check_wiki_docs.py`。
