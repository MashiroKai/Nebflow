---
type: design-note
status: done
---

# 词条（术语）引用系统 · 第一批实施规格（引擎消解 + 规范面）

> 结论先行：词条引用 = 已建成的[[glossary#^paragraph-anchor]]段落级引用系统之上的「命名层」。本批落地：引擎侧词条注册表（WikiIndex 扩展）、词条名短形引用（双方括号包词条名，示例见 §2 裁定 3 围栏）第三优先级回退、`GET /api/wiki/term` 单查 API、`nodeJson.terms[]` 投影、wiki-docs SKILL 词条条款 + check_wiki_docs R4 校验。设计依据：调研稿 `docs/design/20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md`（§1/§2/§4/§5 已有内容）+ 作者 2026-10-29 11:46 自裁清单授权。hover 气泡/前端深链/返回键 = 第二批；ask md/html 闸 = 第三批（本批零触碰 AskSessions/askDock）。

## 1. 改动面

| 面 | 文件 | 内容 |
|---|---|---|
| 注册表 | `src/main/scala/nebflow/core/wiki/WikiIndex.scala` | `WikiTerm` 模型、`termsOf` 抽取、`selectTerm` 裁定、`buildEdges` 词条腿、`VaultScan.terms`、`nodeJson.terms[]` |
| API | `src/main/scala/nebflow/core/wiki/WikiService.scala` + `gateway/WikiRoutes.scala` | `GET /api/wiki/term?name=&project=`（withAuth 沿 wiki 现有面；30s 图谱 TTL 缓存复用，无第二扫描路径） |
| spec | `src/test/scala/nebflow/core/wiki/WikiIndexSpec.scala`（WI-28~32）+ `WikiServiceSpec.scala`（WS-2 扩充、WS-7/8） | 断言读数见 §4 |
| 插件面 | visual-report `skills/wiki-docs/SKILL.md` + `scripts/check_wiki_docs.py` | 词条条款（第④强制条款 + Terms 节）、R4 规则、--graph 导出 `terms[]`、消解第三腿 |

## 2. 实施裁定（开放问题自裁落地，逐条可追）

1. **声明面 = 正文块锚，frontmatter `terms:` 否决**（调研稿 §4.2-B 先例：双真源漂移）。两路词条：(a) 词表文档（rel 基名去尾扩展名含 `glossary`，与 R3 探测同构）的每个块锚段落；(b) 普通文档中 `^term-<slug>` 锚记的块段落。普通 `^锚` 段落一律不是词条（噪音控制，调研稿 §4.2-A）。
2. **开头词条块形态**：`## 词条` 节近文档顶部，每条目独立一段（**条目之间空行**——相邻行会被段落索引并作一段，这是段落索引的既有语义，不为词条改它）；条目形态 `- 名（注） — 定义 ^term-<slug>`。落 SKILL「Terms and short-form citations」节。
3. **词条名抽取**：定义文本首个 ` —`/`–`/`：`/`:` 之前的部分，剥列表/标题前缀（`^[#*-]+\s*`，与 blockTitle 同构）；无分隔符 = 不注册 + R4 `term-format` 缺陷。**显式 `|` 别名不做**——竖线是引用侧别名分隔符，声明面零新语法。引用形态（示例按 SKILL 条款必须进围栏）：

   ```
   [[DCL]]            词条名短形引用（注册表唯一命中时合法）
   [[DCL|显示名]]      带显示别名（既有别名切分照常工作）
   [[doc#^term-dcl]]  指名具体文档的词条（同档/歧义时的纪律形态）
   ```

4. **别名派生**：尾括号组派生查找键——`DCL（DOMContentLoaded 事件）`（[[glossary#^dcl]]）注册 `dcl`、`domcontentloaded 事件` 与全名三个键（大小写不敏感）。这是「作者写 DCL 短形引用能命中词表全名」的关键。
5. **消解优先级**（文档优先级零变化）：精确路径 → 大小写不敏感 → 全库唯一 basename → **词条注册表唯一命中 → (所在文档, `#^块锚`)**；带锚目标（名#锚 形态）不回退；多命中不猜（保持 unresolved 幽灵，tooltip 候选列 = 第二批面）。**同名词条裁定：词表命中优先于开头词条块命中**（唯一定义源纪律延伸，作者自裁清单明示）；同级多命中 = unresolved。
6. **注册表作用域 = 单 vault**（随 VaultScan 派生）；跨 vault 引用维持 external/断链分类，v1 不跨库查词条。
7. **API 面**：唯一命中 → `{found:true, resolved:true, term:{name,aliases,source,rel,anchor,line,endLine,definition}}`；多命中 → `{found:true, resolved:false, ambiguous:true, candidates:[…]}`；未注册 → 404；缺 `name` → 400。`project` 可选（缺省 = 合并视图全项目查）。
8. **校验 R4 分工**：R4 = 词条声明面（`term-format` 结构 / `term-duplicate` 同档同名唯一）；「引用词条已注册」由 **R2 断链检查承担**——文档三腿未命中的无锚目标先过注册表第三腿，唯一命中 = 非断链（边 = 词条所在文档 + 词条锚），未命中 = 断链（消息注明「既非文档也非已注册词条」）。R3 的词表引用判定同步认账：词条名短形引用解析进词表 = 合规引用。孤儿判定的入链面同步认词条边。
9. **html 词条定义面 v1 不做**（自裁清单）：定义仅 md 侧；html 引用跳转走 DOM 锚 + 文档级 summary 兜底——第二批交互面的事，本批只在 SKILL 写明作用域。
10. **`--graph` 导出与引擎逐字段同构**：`terms[]` 进每个 doc 节点（双生产者契约）；python 侧消解第三腿与 R4 同步。

## 3. 与既有行为的兼容边界

- 文档消解三腿一字未动：同名文档存在时词条腿永不运行（GCR 短形引用今天解析为 `GCR.md`，明天仍解析为它）；大小写不敏感文档腿命中时边骑**作者书写的大小写**（`proj:GCR.md`）——既有面，WI-31 按现状钉死。
- `buildEdges` / `buildVaultScan` 新参全带默认值（`terms = Nil`），旧调用点与 spec 零改动编译。
- `nodeJson` 增 `terms[]` 键为加法面（与段落索引批同先例）；合并视图消费者忽略未知键。
- wiki 族 spec 回归：37/37 全绿（WI-1~32 引擎面 + WS-1~8 服务面，含新增 12 条词条断言）。

## 4. 验证读数

- `sbt testOnly nebflow.core.wiki.WikiIndexSpec nebflow.core.wiki.WikiServiceSpec` → **37/37 绿**（2026-10-29 12:29 本 worktree）。
- `check_wiki_docs.py --root docs --json`（本 worktree vault）→ 6 条违规，与批次前基线逐条一致（R1×2 / broken-link×1 / orphan×2 / R3 首现×1，全部历史遗留），**零新增**。
- 主工作区 vault（只读跑校验+--graph 到 /tmp）：词表 17 条全部注册（括号派生别名全数生效：DCL/SWR/附件/引用关系/图谱视图/未解析引用/Service Worker）；37 条违规均为该库自身存量（33 条 orphan 为老文档零链接，词条面只会减孤儿误报不会增）。
- 端到端形态（spec 断言即样张）：

  ```
  [[DCL]]            → 边 (report.md, #^term-dcl)，无幽灵节点（无同名文档）
  [[GCR]]            → 文档腿胜出 gcr.md（同名文档存在，词条腿不运行）
  [[银河宇宙线]]      → 别名键命中 glossary.md#^gcr
  ```

## 5. 裁定记录与遗留

- 作者自裁清单（2026-10-29 11:46 授权）落地面：作用域 = 词条 + ask 仅 md/html（ask 闸第三批）；html 定义面 v1 不做（裁定 9）；同名词条词表优先（裁定 5）；ask 白名单 `.md/.markdown/.html/.htm` 与存量绑定闸新问题不清理 = 第三批事项，本批未动。
- **作者改判 2026-10-29 11:49：返回按钮落点 = 左上角**（明令推翻此前「右上角」口头确认）。返回键属第二批，本批未实现；本记录为第二批的裁定基线。
- 遗留（第二批）：文档阅读面 hover 气泡、resolved 态渲染（前端 markdown.js 消解链消费 `terms[]`）、返回键（左上、单层来源）；（第三批）ask md/html 闸——须等在飞 askmode 批落地后再动。
- 接入图谱：[[index]] · [[glossary]] · 段落引用先例见 [[wiki-graph-paragraph-ref-design.zh-CN]]。
