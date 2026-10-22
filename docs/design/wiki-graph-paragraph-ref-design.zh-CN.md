---
type: design-note
status: current
---

# wiki-graph 段落级引用 + hover 快照 · 设计稿（图谱演进 A 批）

> 本篇是台账 #744-A 的实施设计稿：把图谱引用从文档级精确到**段落级**——名词解释场景下 hover 小窗显示「那一段」的定义内容，点击跳转目标文档并高亮该段。承接 [[wiki-graph-redesign-design.zh-CN]]（图谱极简化重设计），复用其已建成的左栏控制面与画布手感。

## 1. 背景与现状

现状链路三段都是**文档级**：

- 边数据：引擎 `GET /api/wiki/graph` 的 edges 已带 `anchor` 字段（`"#^块锚"` / `"#标题"`），但前端按 `from|to` 去重时把 anchor 丢弃，画布上没有任何段落信息；
- hover 小窗（`.wg-tip`）：节点 hover 只显示文档标题 + 全文档摘要 + 度数，引用边的目标段落内容不可见；
- 点击跳转：详情卡「打开文档」只到文档首屏；markdown 查看器的锚点通道（`openLocalFileTab(path, anchor)` → `applyJumpAnchor` → `.nf-jump-flash` 高亮）**已存在**（前批建成），只是图谱侧从不传 anchor，段落高亮全链断在最后一公里。

词表（[[glossary]]）就是名词解释场景的真源：每条术语 = 一个块锚段落（形如 `^vault`），文档以「glossary#^块锚」精确引用（真例：[[glossary#^paragraph-anchor]]）。本批让图谱认识段落。

## 2. 段落锚定语法（决定）

**不新增语法**。段落锚（[[glossary#^paragraph-anchor]]）沿用 vault 已在用的双形态（语法示例放代码围栏——正文裸写双方括号会被当真链接解析）：

```
[[doc#^块锚]]   单个段落（块锚行及其连续非空行组）——段落级主形态，名词解释场景（真例：[[glossary#^vault]]）
[[doc#标题]]    整节（标题行到下一标题前）——节级
```

理由：`#` 锚语义已被 R2 校验（broken-anchor / broken-block）、WikiIndex.scala 与 markdown 查看器三方共同锁定，新增 `§` 语法只会制造第三套解析口径。「§」仅作 hover 浮层上的视觉记号，不进语法。

校验落点：`check_wiki_docs.py` R2 已校验锚存在性；本批在其 `--graph` 导出中补段落索引（见 §3），并在 `--rules` 文案中写明段落锚双形态。

## 3. 段落索引格式（构建期产出）

构建期 = 两个同构生产者，语义必须逐字段一致：

- `check_wiki_docs.py --graph`（离线/样张（[[glossary#^mockup-sample]]）/校验面）；
- `WikiIndex.scala`（在线 `/api/wiki/graph` 载荷，本地 + 远端 vault 共用同一纯函数）。

### 3.1 节点新增字段 `paragraphs`

每篇文档节点内嵌段落数组（**图谱载荷内嵌，hover 零请求延迟**）：

```json
{
  "id": "stand:docs/glossary.md",
  "rel": "docs/glossary.md",
  "title": "词汇表",
  "paragraphs": [
    { "anchor": "#^vault", "kind": "block",
      "title": "文档库（vault）",
      "line": 21, "endLine": 21,
      "text": "文档库（vault） — 项目工作区内 docs/ 目录整体：Obsidian 兼容的文档库，文档、词表与附件（glossary#^attachment）都住在这里，图谱视图只扫这个目录。" },
    { "anchor": "#引用语法一览", "kind": "heading",
      "title": "引用语法一览",
      "line": 12, "endLine": 19,
      "text": "[[目标]] 引用整篇文档 [[目标#标题]] 引用某一节 …" }
  ]
}
```

### 3.2 字段口径（钉死）

| 字段 | 口径 |
|---|---|
| `anchor` | 与 edge.anchor **同形**（带 `#` 前缀）：块锚 `"#^id"`、标题 `"#标题"`——前端 `paragraphs.find(p => p.anchor === edge.anchor)` 直查，零变换 |
| `kind` | `"block"`（块锚段落）/ `"heading"`（节） |
| `title` | block = 块锚行首行文本去块锚/行内标记后截 60 字符；heading = 标题文本 |
| `line` / `endLine` | 1-based 全文件坐标（含 front-matter（[[glossary#^front-matter]]）偏移，与 WikiIndex 既有行号口径一致）。block = 连续非空行组（遇空行/标题/围栏截止）；heading = 标题行至下一标题行前一行 |
| `text` | 行组内联标记折叠（wikilink→目标、md 链接→文本、强调/反引号去除）+ 空白归一，**上限 280 字符**，截断加 `…`；围栏行不计入 |
| 上限 | 每文档至多 64 段（超出截断，图谱规模保护）；附件与 stub 节点 = `[]` |

### 3.3 边数据

edges 不改结构——`anchor` 字段本批起被前端消费（去重时保留首个非空 anchor）。规范句：**边携带段落锚，节点携带段落内容，锚字符串同形直查**。

## 4. 交互规格（画布）

| 交互 | 行为 |
|---|---|
| 带锚边渲染 | 有 anchor 的边在中点画 2.4px 石墨圆点（--wg-muted）——段落级引用在画布上可被发现，也是 hover 的语义提示 |
| 引用边 hover | 命中边（点到线段距离 < 6px，节点优先）→ 毛玻璃浮层显示**目标那一段**：`§ 段标题` + 段落 text（≤6 行截断）+ 底行 `目标文档标题 · 点击跳转`；段落索引未覆盖该锚 → 回退目标文档摘要（文档级预览，不裸空） |
| 引用边点击 | 打开目标文档（本地走 `openLocalFileTab`，远端走 remote-doc 同一事件通道）并传 anchor：`#^id` → `{kind:'block',id}`、`#标题` → `{kind:'heading',text}`、`#L42` → `{kind:'line',line:42}`——markdown 查看器滚动定位 + `.nf-jump-flash` 高亮（既有能力，本批接通） |
| 节点 hover/点击 | 不变（文档级小窗与选中详情，承前批契约） |
| 详情卡引出/被引 | 词条按钮带锚时后缀小字显示锚文本（信息性，不改变点击=聚焦 canvas 的既有契约） |

浮层材质：`.wg-tip` 升毛玻璃（--glass-bg / --glass-blur saturate(1.15) / --glass-border / --frost-shadow，与画布胶囊、左栏搜索框逐项相等，承 §F 批单源），`pointer-events: none` 不遮挡画布操作；亮暗双主题跟 prefers-color-scheme 既有 --wg-* / --glass-* 双表。

## 5. 数据管线与 xerj 集成边界

```
hover ──► 图谱载荷内嵌 paragraphs（零请求，主路径）
              │ 未覆盖（锚在索引中缺失，如摘要级截断外的全文需求）
              ▼
GET /api/wiki/paragraph?project=&rel=&anchor=      ← 接口边界
              │ 1) 段落索引直查（本地 vault 现场重扫该文档）
              │ 2) 未命中 → best-effort tools/stand-recall（xerj 按段检索，
              │    短超时，进程缺席/超时/零命中都吞掉）
              ▼
        3) 回退文档级预览 {fallback:"doc", summary}   ← 契约底线
```

边界条款（钉死）：

1. **图谱运行时不依赖 memory-db**：hover 主路径纯内嵌载荷；`/api/wiki/paragraph` 的 stand-recall 腿是 best-effort（进程不存在、超时 2s、零命中一律沉默），任何失败都落到文档级预览；
2. **语料口径不混**：stand-recall 现行语料 = 主 agent 会话（其头部作者令锁定），不含 vault 文档；vault 段落的真源是构建期段落索引，stand-recall 是索引未覆盖时的可选增强位，命中语义为「记忆系统相关段落」；
3. 远端（peer）vault 段落一律走索引内嵌（扫描时已在宿主侧解析），不跨设备调 stand-recall。

## 6. 移动层

画布命中逻辑（节点 > 边、6px 阈值、点击≠拖拽的 moved 判定）对指针类型无差别，触控天然受益；浮层 pane-local 不遮挡。手机层无左栏，交互面 = 画布 + panebar，本批零额外工作。

## 7. 验收清单

- [ ] 词表真实场景端到端：`docs/index.md` 引 [[glossary#^paragraph-anchor]]（新词条）→ 索引 → 带锚边 → hover 小窗 = 那一段 → 点击 → 高亮；
- [ ] `check_wiki_docs.py --root docs` 零违规；`--graph` 产物 paragraphs 与 WikiIndex 产物逐字段同构；
- [ ] spec `tests/wiki-graph-paragraph.spec.mjs` 钉：带锚边标记、边 hover 段落小窗、边点击 anchor 载荷、亮暗双主题截图、console 零错误；
- [ ] `WikiIndexSpec` 段落抽取用例全绿；
- [ ] 既有 `tests/wiki-graph-ux.spec.mjs` 无回归。

## 相关文档

- [[wiki-graph-redesign-design.zh-CN]] — 图谱极简化重设计（本批承接）
- [[glossary]] — 词表（名词解释场景真源，段落锚主形态的发源地）
- `src/main/resources/seed/plugins/visual-report/skills/wiki-docs/scripts/check_wiki_docs.py` — 校验器 + 离线图谱导出
