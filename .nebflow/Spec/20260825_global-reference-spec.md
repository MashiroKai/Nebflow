# 设计规格书 · Nebflow 客户端「全局引用能力」（Global Reference）

- 日期：2026-08-25 · 作者：design-engineer · 状态：**frozen v1.2（2026-08-26 用户裁定两项决策点后冻结，B6/C5 正式开工）**
- 派发基线：2026-08-25 · 用户确认冻结 · commit **`d8290bc`**（v1.1 冻结基线）· v1.2 冻结 commit **`f4e072c`** + 本行状态更新
- **v1.1（2026-08-25 用户澄清裁定）**：① 任务栏「引用=打回」单一语义（取消独立「引用任务」分支），打回采用 `@` mention 格式（§3.3/§2.3）；② 文件引用入口除右键外**拖拽到输入框**同样产出引用块；Canvas 标签页**可拖到输入框**（§3.1/§3.6）；③ P0–P2 **全做**（含 HTML 元素引用 + 引用块点击滚动跳转）；④ 拉到根目录确认为**单独小修独立派发**。修订处以「**(v1.1)**」标注。
- **v1.1 拖拽取舍确认（用户 2026-08-25 拍板）**：**内部工作区文件拖入输入框 = 引用块**（`@path` 指针）；**外部 Finder 文件拖入 = 附件上传**（`#303` 原语义）。用户同意**内部分支方案**——内部文件（explorer 树内，`application/x-nebflow-file` 带 `path`+`rootPath`）走 `makeReference` 产出 `@path` 引用块；外部文件（OS/Finder）仍走 `addFileAttachment` 上传。详见 §3.1 文件引用入口②。
- **v1.2（2026-08-25 B6/C5 补全；2026-08-26 用户两项裁定后冻结）**：补全 P2 剩余两个章节的完整设计——§3.4 HTML 元素选择引用（B6：入口与选择模式状态机/悬停高亮/postMessage 跨 iframe 通道/选择器与快照提取/静态快照取舍/边界降级/无障碍）+ §5.6 跨源能力差异矩阵与统一降级规则（C5）；§7 新增 B6-A1–A9 / C5-A1–A6 断言系列，标注与已实施 D1/D5 管线的衔接。**用户 2026-08-26 两项裁定（落进实施）**：① §3.4.8 入口位置——**不采用推荐 A（独立顶栏按钮）**，改为 Canvas HTML 查看器工具栏内、与现有「渲染/源码」转换按钮**同位置同款式的模式切换按钮**（进入/退出选择模式 toggle）；② 非文本元素引用**不附截图缩略图**（§3.4.4 已按此写死，无需改）。
- 适用代码：`src/main/resources/web/js/{explorer,input,chat,taskList,canvas,persistence,i18n}.js` + `css/{split,chat,input}.css` + `src/main/scala/nebflow/gateway/WebSocketRoutes.scala`
- 铁律依据：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`（最高优先级）
- 案例库依据：`~/.nebflow/skills/design-system/SKILL.md`（软参考，冲突以 visual-style 与用户 taste 为准）
- 相关既有规格：`file-drag-drop-spec.md`（#303 拖文件进输入框，复用 addFileAttachment 链路）· `todo-panel-spec.md`（v2 §5/§6.3 打回引用块）

---

## 0 · 一句话目标

把现有「**打回任务** 单点引用」升级为**全局引用能力**——文件/文档/任务/HTML 元素统一引用模型，所有入口（**右键 / 选择 / 拖拽到输入框**）产出同一形态引用块，引用块用 `@` mention 格式（`@path` / `@#task` / `@url:sel`），在输入框**定高截断可展开**（不再随输入变长），在消息里**带来源+摘要+元信息角标+可跳转**。

## 1 · 参考与依据

| 来源 | 提炼的可执行规则 | 链接 |
|---|---|---|
| 微信「引用」(quote-and-reply) | ① 引用一条消息时，气泡上方引用原消息**一段截图式摘要**（标题+内容首行），被引消息保持原样不复制全文；② 引用用于「跨时段应答」——回一条**非相邻**历史消息，让接收方看得到「你在回哪条」；③ 引用块是**单行/矮卡片**，不随你正在输入的意见变长（意见在下方独立输入区） | 产品行为观察（微信桌面/移动）；引用回复的会话分析：https://files.eric.ed.gov/fulltext/EJ1478736.pdf |
| Notion · 页面/块链接 | ① 内联 `@` mention——动态链接、随标题更新、可点击跳转；② `/link to page` **块链接**——独立于正文的整宽可见引用块（图标+标题，点击打开目标页）；③ **Bookmark（富预览）**——URL 粘贴即得「标题+描述+链接」卡片；页面可嵌入时内嵌、iframe 被禁时**回退为 bookmark 卡片** | https://www.notion.com/help/create-links-and-backlinks ；https://thomasjfrank.com/notion-links/ |
| VS Code / AI 编码助手 `@path:lines` | ① 文件被引用为**指针**而非内联内容——`@path:lines` 携带路径+选区行号，AI 凭指针**按需读取**（不整读）；② **文件树右键菜单**提供「Copy Path / Copy Reference」动作；③ 行号/选区来自**当前文本选择** | https://code.visualstudio.com/docs/chat/copilot-chat-context ；https://marketplace.visualstudio.com/items?itemName=Wraithy.copy-ai-ref |
| Apple HIG · 链接与导航 | ① 链接目标清晰、点击行为可预期（打开文件/定位到段落）；② 非破坏性——引用不移动/不复制源对象，只指向它；③ 来源信息（文件类型/名称）在链接标签内可见 | https://developer.apple.com/design/human-interface-guidelines/ |
| Nebflow 现状 | 已有「打回任务」type='taskRef' 引用块（输入 chip + 消息「打回：{subject}」标签 + 后端 `[打回任务]` 注入块）；已暴露 `#attachment-preview`/`.att-taskref` 复用点。本规格在此之上**统一为全局引用模型，不推翻既有打回任务链路** | `src/main/resources/web/js/{taskList,chat,input}.js` · `WebSocketRoutes.scala §6.2` |

**取舍声明**：范式冲突时以 visual-style 铁律（毛玻璃面板/玻璃控件/中度字重/克制专业感）为准。不采用「整文引用复制进正文」（微信/Notion 都在引用处**保留指针+摘要**，而非复制全文），避免占用 token 与聊天膨胀——与现有 `[用户附加文件: path]` 指针式语义一致。

---

## 2 · 引用数据模型（核心）

### 2.1 引用对象类型与元信息字段

一个引用 = **指向某一内容对象的指针 + 可选锚点 + 展示元信息**。统一用单个 `Reference` 对象承载，`refType` 判别具体类型。

| refType | 含义 | anchor 锚点 | 关键元信息 |
|---|---|---|---|
| `file` | 文件浏览器里的文件 | 无 / 文本行范围 | 来源路径 path · 文件名 · 类型 · 大小 |
| `document` | Canvas 打开的 PDF/epub/md/latex/excel | 页码范围 / 行范围 / sheet+cell 范围 / 章节 | 来源路径 · 标题 · MIME · 页数/行数 |
| `task` | 任务栏任务（含现有打回语义扩展） | 无 | taskId · sessionId · 标题 subject |
| `html-element` | HTML 页面上的元素 | CSS 选择器路径 + 提取文本 | 来源 url · 元素标签 · 选择器 · 摘要 |

**统一 `Reference` 对象（JS 态，存入 `pendingAttachments`）**：

```jsonc
{
  "type": "ref",                       // 统一 attachment 类型（取代分散的 'taskRef'）
  "refType": "document",               // 'file' | 'document' | 'task' | 'html-element'
  "id": "ref:doc:a3f9c",               // 稳定 ref 标识（内部用；非用户可见）
  "source": {
    "kind": "workspace",               // 'workspace' | 'canvas' | 'task' | 'web'
    "path": "/abs/.../paper.pdf",      // workspace/canvas 源文件绝对路径
    "url": "https://...",              // web / html-element 来源页
    "fileName": "paper.pdf",           // 显示用文件/文档名
    "title": "paper.pdf",              // 显示标题（task 为 subject）
    "taskId": 101,                     // task 时
    "sessionId": "sess-xxx"            // task 时
  },
  "anchor": {
    "kind": "range",                   // 'range'(行) | 'page'(页) | 'cell' | 'element' | 'none'
    "pageStart": 3, "pageEnd": 4,      // document(PDF) 页码，1-based
    "lineStart": 12, "lineEnd": 45,    // 文本/代码行范围
    "sheet": "Sheet1", "cellRange": "A1:D10", // excel
    "text": "…选中/提取的内容摘要…",      // 元素/选区文本摘要（多类型共用）
    "selector": "html>body>div.c>p:nth-of-type(2)" // html-element CSS 路径
  },
  "meta": {
    "mimeType": "application/pdf", "sizeBytes": 123456,
    "icon": "file-text", "typeLabel": "PDF"        // 展示角标用
  },
  "display": {
    "label": "paper.pdf", "preview": "…截断摘要…",
    "pageBadge": "p.3–4", "lineBadge": "L12–45"     // 角标
  }
}
```

### 2.2 四种类型的具体示例

**file**（右键菜单产出）：

```jsonc
{ "type":"ref", "refType":"file",
  "source":{"kind":"workspace","path":"/Users/dev/Claude code/Nebflow/README.md","fileName":"README.md","title":"README.md"},
  "anchor":{"kind":"none"}, "meta":{"mimeType":"text/markdown","icon":"file-text","typeLabel":"MD"},
  "display":{"label":"README.md","preview":"Nebflow is a multi-agent…","lineBadge":""} }
```

**document**（PDF 选择页码产出）：

```jsonc
{ "type":"ref", "refType":"document",
  "source":{"kind":"canvas","path":"/abs/paper.pdf","fileName":"paper.pdf","title":"paper.pdf"},
  "anchor":{"kind":"page","pageStart":3,"pageEnd":4},
  "meta":{"mimeType":"application/pdf","icon":"file-text","typeLabel":"PDF"},
  "display":{"label":"paper.pdf","preview":"…第3页摘要…","pageBadge":"p.3–4"} }
```

**task**（任务栏引用，现有打回语义扩展）：

```jsonc
{ "type":"ref", "refType":"task",
  "source":{"kind":"task","taskId":101,"sessionId":"sess-xxx","title":"修复登录"},
  "anchor":{"kind":"none"},
  "display":{"label":"#101 修复登录","preview":"","pageBadge":""} }
```

**html-element**（页面选元素产出）：

```jsonc
{ "type":"ref", "refType":"html-element",
  "source":{"kind":"web","url":"https://ex.com/post","title":"Post title"},
  "anchor":{"kind":"element","selector":"html>body>div.post>p:nth-of-type(2)","text":"…选中段落文本…"},
  "display":{"label":"Post title","preview":"…元素文本截断…","pageBadge":"<p>"} }
```

### 2.3 序列化格式（四层表示）

引用块在消息/载荷中**四层表示**，各司其职：

| 层 | 位置 | 格式 | 用途 |
|---|---|---|---|
| ① UI 态 | 前端 `pendingAttachments` | `Reference` 对象（§2.1） | 存储/渲染/preview |
| ② WS 载荷 | 发给后端的消息 | `{ refs: [Reference…] }`（与现有 `taskRefs` 并列，见 §5.4） | 后端解析；逐 ref resolve |
| ③ 后端注入块 | 进 agent prompt | `[引用: 文档 · paper.pdf · p.3–4 · /abs/paper.pdf]`（+ `@path:range` 指针，agent 按需用工具读） | agent 可见；指针式，不整读全文 |
| ④ 文本/标记 | 供复制/还原/历史 | **`@` mention 格式**（见下） | 复制粘贴、压缩摘要 |

**④ `@` mention 文本格式（用户 08-25 裁定：任务打回改用 `@`；文件/文档/元素同理）**：

| refType | 文本表示 | 示例 |
|---|---|---|
| task | `@#<taskId> <subject>` | `@#101 修复登录` |
| file | `@<source.path>` | `@/abs/README.md` |
| document | `@<source.path>:<页/行范围>` | `@/abs/paper.pdf:p3-4` |
| html-element | `@<url>#<selector>` | `@https://ex.com/post#p:nth-of-type(2)` |

> `@` 前缀对应三种成熟范式（Notion `@mention` / VS Code `@path:lines` / 微信引用），也是输入框识别「这是引用」的**视觉与语义锚点**——引用块在输入框/消息里统一以 `@` 起头，与正文文本（不含 `@`）区分。

**③ 后端注入块模板**（由 `resolveRef()` 统一构建，取代 / 并存于现有 `Task.returnInjectionBlock`）：

```
[引用: {typeLabel} · {title}{anchor 摘要如 "· p.3–4 · L12–45"} · {来源}] 
```
示例：
- `[引用: 文档 · paper.pdf · p.3–4 · /abs/paper.pdf]`
- `[引用: 文件 · README.md · L12–45 · /abs/README.md]`
- `[引用: 任务 · #101 修复登录]`
- `[引用: 页面元素 · Post title · <p> · https://ex.com/post]`

> agent 侧可见的是一个**稳定指针**（来源+锚点），不是被引用内容的副本——与现状「文件给路径让 agent 用工具读」一致，token 可控（大文件只引摘要，见 §5.2）。

### 2.4 统一性与兼容（与现有 taskRef 迁移）

**统一性保障点**：所有入口（右键/选择/拖拽）最后都调用**同一个工厂** `makeReference(input) -> Reference`，产出同一 `type:'ref'` 结构；渲染统一走 `renderRefBlock(ref, {mode})`（mode 区分输入预览/消息气泡）。不允许多个入口各自拼不同的对象结构。

**兼容/迁移**：
- 现有 `type:'taskRef'`（taskId/sessionId/subject）**保留并继续工作**（向后兼容，老历史/刷新还原路径 `persistence.js` 不动）。
- 新任务引用 = `type:'ref', refType:'task'`，文本表示 = `@#<taskId> <subject>`。后端 `processTaskReturns` 同时读取 `taskRefs` 与 `refs`（过滤 `refType==='task'`）；前端 `taskList.js requestReturn` 改为产出 `ref` 形式并在输入框显示 `@` 块。
- **引用 = 打回（用户 v1.1 裁定）**：任务引用即打回。不再提供「只引用不触发打回」的独立分支。`ref`（refType='task'）缺省即走打回语义（后端 return），无需额外 `_return` 标记。
- 输入 chip 渲染：`.att-taskref` 类名保留（视觉复用），新增 `.att-ref` 统一卡片。旧 chip（taskRef）与新卡片（ref）可同屏，视觉一致是目标，但**不强制**一次迁移——先新卡片，旧 chip 渐进收敛。

---

## 3 · 交互设计：各入口操作流程

### 3.1 文件浏览器 · 右键引用

现有 `explorer.js showContextMenu`（L897）已具备右键菜单（New File/New Folder/Delete）。**新增「引用」(Reference) 菜单项**，放在 New File 之前，仅对文件/文件夹（`.explorer-item`）显示：

```
New File
New Folder
───
引用                ← 新增（选中态高亮，点击 → 键入输入框）
───
Delete
```

流程：
1. 右键文件行 → `showContextMenu`（现有行为，若未选中则先单选）。
2. 点「引用」→ 构造 `Reference`（refType='file'，source.path=explorer 相对路径 → 转 workspace 绝对路径，anchor.kind='none'）→ 调 `makeReference` → 推入 `activeView.pendingAttachments` → `renderAttachmentPreview(activeView)`。
3. 输入框获得焦点，`@` 引用卡片出现在附件预览区（§3.5）。
4. 可连续右键引用多个文件 → 多个 chip。
5. 引用文件夹：resolve 为 `[引用: 目录 · dirName · /abs/dir]`（agent 可用工具列目录；**不**注入目录内容全文）。

> 实现落在 `explorer.js`，只加一个菜单项 + 一个 `makeReference` 调用，纯前端。

**文件引用入口 ②**（**(v1.1)** 用户裁定）：**把 explorer 里的文件行（或文件树里的文件）拖拽到输入框 → 产出引用块**，而非上传附件。

- **语义对齐**：内部工作区文件已在磁盘上、agent 可凭 path 读取，拖入输入框应产出**引用块（指针）**而非 base64 上传。这是对 `#303`（外部 Finder 文件拖入=附件上传）的**内部文件分支扩展**：外部文件（OS/Finder）仍走 `addFileAttachment` 上传；内部工作区文件（explorer 树内）拖入 = `makeReference` 产出 `@path` 引用块。
- **实现**：`explorer.js` 的 `startRowDrag`（L301）对文件行已携带 `application/x-nebflow-file` MIME（`#303`）。拖入输入框时，输入栏 drop handler 需**区分**：载荷含内部路径（`application/x-nebflow-file` 且有 `path` + `rootPath`）→ 走 `makeReference`（refType='file'）；否则视为外部文件 → 走 `addFileAttachment`。`#303` 的 `initGlobalFileDrop`（input.js）相应扩展分支。
- **视觉**：拖拽时输入框预览区出现 `@` 引用卡片（§3.5 定高/截断/可展开），与右键引用**完全一致**。

**文件引用入口 ③**（**(v1.1)** 用户裁定）：**Canvas 标签页可拖到输入框** → 产出该文档的引用块（携带当前页/选区锚点）。见 §3.6。

### 3.2 Canvas 文档 · 选择引用

适用 PDF/epub/md/latex/excel 等 Canvas 打开文档。**「选中后引用」**——优先复用**当前选区/当前浏览锚点**，不另开选择工具：

- **PDF**（`viewers/pdf.js`）：每页是带 `data-page="i"` 的 canvas。选择引用时取**当前滚动命中的页区间**（`pages.scrollTop` 覆盖的 `[minPage, maxPage]`，取整）作为 `anchor.pageStart/end`；若用户在 pdf 文本层有文本选区，可额外带 `anchor.text`。→ 产出 `[引用: 文档 · paper.pdf · p.3–4 · /abs/paper.pdf]`。
- **文本/代码（md/monaco 打开）**：取当前**选区行范围** `lineStart/lineEnd`（无选区则当前光标行）→ `anchor.kind='range'` + `anchor.text`=选区前 N 字符摘要。
- **Excel**（`viewers/xlsx.js`）：取 SheetJS 当前激活 sheet + 选区 `range`（`sheet.cellRange`）→ `anchor.kind='cell'`。
- **epub**：取当前阅读位置章节（视图当前可见章节标题）→ 章节页码。

**入口 UI**：在 Canvas 文档标签的右键菜单（或顶栏）新增「引用当前选区/页码」。现选即引，无需额外选择工具——**克制**（不造新轮子）。

### 3.3 任务栏 · 引用任务（引用=打回，`@` mention）

现有「打回」= `requestReturn`（taskList.js L331）产出 type='taskRef'。**(v1.1)** 用户裁定：**引用与打回是同一语义**（点任务行打回 = 引用该任务并打回），不再提供独立的「只引用不打回」动作；打回采用 `@` mention 文本格式。

- 点任务行「打回」→ 草拟一个 `type:'ref', refType:'task'` 引用块，输入框显示 `@#<taskId> <subject>`（§3.5 定高卡片）。
- 提交时后端按现有 `processTaskReturns` 语义把任务打回 in_progress（引用即打回，无独立 `_return` 标记）。
- **`@` 语义**：任务引用文本在输入框/历史里以 `@#101 修复登录` 呈现，与 Notion `@mention` 一致，是用户可辨识的引用锚点；发起引用=发起打回（任务状态变更在发送时生效，与 C16 草拟语义一致：点击只草拟，发送才 return）。

> 任务行右键不再新增「引用（非打回）」项（v1.1 取消）。

### 3.4 HTML 元素 · 选择引用（B6 完整设计，v1.2 补全）

适用：Canvas 查看任意 HTML 页面（`viewers/html.js`，sandbox iframe——含 srcdoc 装配的自包含卡片/图表，与 URL 页）。目标：用户选中页面内某个元素（截图卡片/图表/表格/段落）→ 产出 `refType:'html-element'` 引用块。

#### 3.4.1 入口与选择模式状态机

**入口（2026-08-26 用户裁定，见 §3.4.8）**：Canvas HTML 查看器工具栏内、与现有「渲染/源码」转换按钮**同位置同款式的模式切换按钮**（crosshair 图标，进入/退出选择模式 toggle，`aria-pressed` 反映开关态），仅当前 tab 为可达 HTML 查看器（srcdoc/同源）时可用；Esc 随时退出。

状态机（`htmlRefSel` 模式，挂在 canvas 当前 tab 状态上，tab 级非全局）：

| 状态 | 进入条件 | 行为 | 退出 |
|---|---|---|---|
| S0 关闭 | 默认 | 正常浏览 | — |
| S1 选择模式 | 点「选择元素」按钮 | 按钮置 active 态；iframe 内选择脚本激活；光标 crosshair；hover 高亮生效 | Esc / 再点按钮 / 切换或关闭 tab → S0 |
| S2 悬停 | S1 中 mousemove 命中元素 | 目标元素 2px 强调色描边 + 8% 强调色底（注入样式，不改原 DOM） | 移出 → S1 |
| S3 已选中 | S2 中 click | 高亮冻结 200ms 确认反馈 → 提取快照 → 产出 Reference → 自动回 S0 | — |
| S4 不可用 | S1 请求激活但目标 iframe 跨源且无内嵌选择脚本 | 顶栏按钮旁 toast「该页面不支持元素选择（跨源）」，停留 S0 | toast 自动消隐 |

- 选择模式 **tab 级**：切走 tab 即退出并清理，不泄漏监听器到其他 tab。
- 点击拦截：S1/S2 中 iframe 内 click 一律 `preventDefault + stopPropagation`——选中链接不触发导航，元素内原有交互在选择模式下全部挂起。

#### 3.4.2 悬停高亮与选中视觉

- **hover 高亮**：`outline: 2px solid var(--color-accent)` + `outline-offset:1px` + `background: color-mix(in srgb, var(--color-accent) 8%, transparent)`。用 `outline` 不用 `border`——不参与盒模型，**零布局抖动**。高亮由注入脚本置 `data-nf-ref-hover` 属性 + 注入 `<style>` 实现，**不改写页面原有 class/style**，退出模式时全部清除。
- **选中确认**：click 后描边加粗（2px→3px）停留 200ms 作确认反馈，再 140ms ease 淡出；`prefers-reduced-motion` 时 ≤0.01s（沿用库内 REDUCED_MOTION 约定）。
- **文案**：按钮 title / toast / 角标全部走 `t()` i18n key（§8），禁硬编码中文（案例 001 踩坑①）。

#### 3.4.3 跨 iframe 边界（postMessage 通道）

按可达性三种情形分治：

| 情形 | 可达性 | 通道 |
|---|---|---|
| ① srcdoc 自包含 HTML（html.js 装配） | 选择脚本**在 srcdoc 装配时内嵌**（与既有 `imgClickScript`/`anchorNavScript` 同法，休眠态常驻，收到激活消息才工作） | 双向 postMessage |
| ② 同源 src iframe（本地 `/api/nf-file` 渲染） | 父窗口可直取 `contentDocument` | 直接注入选择脚本，不走 postMessage |
| ③ 跨源 URL 页（http(s) 外链） | 不可达 | **降级** → S4 不可用提示（→ §5.6 C5） |

**postMessage 协议**（复用 askuser-canvas `_nfAskAnswer` 模式与 2026-08-25 实测教训）：

```js
// 父 → 子：激活/退出选择模式
iframe.contentWindow.postMessage({ _nfRefMode: { on: true } }, '*')
// 子 → 父：选中元素回传
parent.postMessage({ _nfRefPick: { selector, tag, text, rect } }, '*')
```

- **targetOrigin 必须 `'*'`**：sandbox iframe 的 `location.origin` 是 opaque `"null"`，直接作 targetOrigin 抛 TypeError、消息发不出——askuser-canvas spec §11.2 实测教训（`location.origin` 方案已被用户实测否掉），本设计直接沿用 `'*'`。
- **父窗口不校验 `e.origin`**，校验全部在 payload 内部（同 `_nfAskAnswer` e4-e9 模式）：
  - V1：仅当父窗口**确实处于该 tab 的选择模式**（`htmlRefSel.activeIframe === e.source`）才受理 `_nfRefPick`，否则丢弃；
  - V2：`selector` 必须为字符串、长度 ≤512、只含选择器合法字符集，否则丢弃；
  - V3：`text` 截断到 4KB（对齐 §5.2 excerpt 上限）；
  - V4：校验失败一律静默丢弃 + console.warn 一条，不弹错不阻断。
- iframe 内容不可信（agent 产出），**父窗口是唯一校验点，绝不裸转发**（沿用 askuser-canvas §3.2 安全约束语义）。

#### 3.4.4 选择器路径与快照提取

**选择器生成**（iframe 内脚本）：
1. 沿 DOM 链向上：每级取 `tag:nth-of-type(n)`（同胞同 tag 序号），直到 `body`。
2. 每追加一级做**唯一性校验**：`document.querySelectorAll(path).length === 1` 即停；最多上溯 8 级，仍不唯一则用 body 起全路径（必唯一）。
3. 元素带唯一稳定 `id` 时直接 `#id` 短路（最短路径优先）。

**快照字段**（选中瞬间一次性提取，之后不再读 DOM）：

| 字段 | 来源 | 上限 |
|---|---|---|
| `anchor.selector` | 上述算法 | 512 字符 |
| `anchor.text` | `el.innerText` 折叠空白后截断 | 4KB（UI preview 再截 160） |
| `anchor.tag` | `el.tagName.toLowerCase()` | — |
| `anchor.rect` | `getBoundingClientRect()`（宽×高，展示备用） | — |
| `source.url` / `source.path` | tab 的 URL；srcdoc 本地文件时为来源 path | — |
| `display.pageBadge` | `<{tag}>` | — |

**非文本元素**（img/canvas/svg/嵌套 iframe）：`text` 退化为 `alt`/`aria-label`/title 属性，均无则 `text=""`、preview 显示「`<{tag}>` 元素」。**默认不附截图缩略图**——体积与 token 成本否决（对齐 §5.1 截图方案否决理由：对 agent 是黑盒、「已修复」精细判断易误报）；后续若确需，作独立增强单开。

#### 3.4.5 静态快照 vs 活引用（取舍：**静态快照**）

| 方案 | 含义 | 取舍 |
|---|---|---|
| **静态快照** | 选中瞬间提取 selector+text 固化进 Reference；之后页面变化不影响引用 | ✅ 采用 |
| 活引用 | 只存 selector，点击/注入时回页面实时重取 | ❌ 不采用 |

**理由**：① 与全局引用模型「指针+摘要」原则一致——注入给 agent 的是稳定文本快照，不依赖页面运行时状态；② Canvas HTML 多为 agent 产出的一次性卡片，DOM 随时被下一条消息重渲染，活引用几乎必失效；③ 点击跳转仍用 selector 尝试定位，**定位失败降级为打开页面顶部**（§4 跳转规则），但快照文本始终完整可用——**静态快照保证「引用永不为空」**。

#### 3.4.6 边界与降级（衔接 §5.6 C5）

| 边界 | 行为 |
|---|---|
| 跨源 URL 页 | S4 toast 提示不可用；用户可改用整页 URL 引用（anchor.kind='none'） |
| PDF/图片视图（无 DOM） | 「选择元素」按钮置灰 + title 提示；PDF 走 §3.2 页码引用（B2），图片走整文件引用（B1） |
| srcdoc 内嵌套 iframe（页中页） | 选择脚本只覆盖顶层 srcdoc DOM；嵌套 iframe 内元素不可选，hover 停在嵌套 iframe 边界元素本身 |
| 选择模式中切换/关闭 tab | 自动 S1→S0，高亮样式与事件监听全部清除 |
| 点击 body 空白 | body 本身可选（= 整页引用），不设歧义「取消」——Esc 才是取消 |

#### 3.4.7 无障碍

- 「选择元素」按钮可 Tab 达，`aria-pressed` 反映 S1 开关态；toast `role="status"`。
- 选择模式是鼠标密集交互；键盘-only 用户的等价路径 = §3.2 文本选区引用（md/文本视图）或整页引用，功能无损（降级保证非鼠标可达）。
- hover 高亮不单靠颜色通道：选中确认附加描边 2px→3px 形态变化。

#### 3.4.8 B6 入口位置（2026-08-26 用户裁定：**已定**）

~~三选项待裁定~~ → **用户裁定：入口 = Canvas HTML 查看器工具栏、与现有「渲染/源码」转换按钮同位置同款式的模式切换按钮（进入/退出选择模式 toggle）**——不是独立顶栏按钮、不是右键、不是快捷键。理由（裁定语义）：模式切换按钮与「渲染/源码」同为查看器视图模式控制，同位置同样式保持工具栏一致性；顶栏不加新按钮。

### 3.5 输入框引用块（用户点名问题：定高/截断/可展开）

**问题根因（现状）**：`renderAttachmentPreview`（chat.js L2006）里 taskRef chip 实时把「正在输入的意见首行」塞进 chip（`syncOpinion` L2057-2069），且 title/subject 是非截断长文本 → chip 高度随输入内容与 title 长度变化，挤压输入布局，体验差。

**目标**：引用块在输入框**固定footprint**，不随输入变长。方案：

```
┌────────────────────────────────────────────────────┐
│ [icon] #101 · 修复登录 · 「用户正在输入的意见…」  … │  ↑ 固定单行高（~36px）
│              p.3–4 · PDF · /abs/paper.pdf   [⤢][✕] │     [⤢]=展开
└────────────────────────────────────────────────────┘
     展开态（点击 ⤢ / 点击卡片头，max-height ~72px 可滚动）
      [icon] #101 · 修复登录
            意见全文（换行）
            来源 + 页码 + 类型   [⤡][✕]
```

**具体规则**：
1. **固定单行高**：`.att-ref` 卡片 `height:36px`（含 1px 边框），`overflow:hidden`。**高度恒定，不随任何内容变化**——这是 A 类断言基准（§7 A1）。
2. **截断**：标题/预览/意见三块均为单行 `text-overflow:ellipsis; white-space:nowrap`。意见预览只取输入框首行、**截断在 ~180 字符**（过长只显示开头 + 「…」，全文在展开态或 title 提示里）。
3. **可展开**：卡片头部右侧 `⤢`（展开/收起）。展开态把卡片高度放大到 `max-height:72px`、内部多行可滚动；内容=完整标题+完整意见+完整元信息。收起回到单行固定高。展开态不与「输入区」争高度（展开后仍占固定附加带宽，可再收起）。
4. **不触发输入区撑高**：附件预览区（`#attachment-preview`）本身始终**单行高度恒定的容器**（`height:44px`，弹性 wrap）。引用块再多也只横向滚动/换行到**固定区**内，不把 textarea 推下去。→ 与「输入内容变长只撑 textarea（现状正常）」解耦。
5. **移除**：✕（现有 `.att-remove`）照旧，本地删除 chip（C16 草拟语义不变，发送才生效）。
6. **意见实时预览去噪音**：意见预览在 chip 里**保留首行 + 截断**，但**去掉会导致高度变化的即时重排**——用 CSS 定高 + ellipsis 取代「hidden 切换」（现状 hidden 切换导致 chip 高度跳变），高度永远固定。展开态才展示完整意见。

> 这一点是用户 08-25 点名问题，同为最高优先级；§7 A1-A6 直接钉死。

### 3.6 Canvas 标签页 · 拖到输入框引用（**(v1.1)** 新增）

**(v1.1)** 用户裁定：**Canvas 标签页可拖到输入框** → 产出该文档的引用块（携带当前页/选区锚点）。

- **入口**：Canvas 打开的文件/文档/url 标签页标签（`#canvas-tabs` 里的 tab）可拖拽（`draggable=true`）。拖到输入框 → 生成 `type:'ref'` 引用块。
- **锚点**：拖拽瞬间读取该标签页的**当前浏览状态**——PDF 取当前滚动命中的页区间、文本/代码取当前选区行、Excel 取当前 sheet+cell、html/url 取当前元素（若处于元素选择模式）。无选区/无滚动则取当前页/当前行（默认）。
- **产物**：`Reference`（refType='document' 或 'file' / 'html-element'），输入框出现 `@<path>:<页/行>` 引用卡片（§3.5）。
- **与右键/选择引用的关系**：标签页拖拽 = 「把当前正在看的这一页/这段」整体引用，是 §3.2 选择引用的**便捷代理入口**；二者产出同一 `Reference`（复用 §3.2 锚点逻辑）。
- **实现**：`canvas.js` tab 行加 `dragstart` 绑定；`input.js` 的 document 级 drop handler（`initGlobalFileDrop`）扩展识别 canvas 标签页 payload MIME（如 `application/x-nebflow-ref`, 携带当前 tab 锚点）。纯前端。

---

## 4 · 引用块渲染（消息里）

消息气泡里引用块是**一个独立的块级卡片**（不是内联标签），结构：

```
┌──────────────────────────────────────────────┐
│ [icon] 📄  paper.pdf                     [p.3–4]│   header：来源文件名 + 角标
│         /abs/paper.pdf                        │   source：来源路径（可点击）
│  摘要文本（2 行截断，ellipsis）                 │   content：内容摘要
└──────────────────────────────────────────────┘
```

- **来源标识**：图标（按 refType：file=file-text / document=file-text / task=clipboard / html-element=code）+ 标题/文件名。
- **内容摘要**：1-2 行截断（`preview`，上限 ~160 字符）。
- **元信息角标**：右上/标题行尾 badge——`p.3–4`（页码）/ `L12–45`（行）/ `Sheet1!A1:D10`（cell）/ `<p>`（元素标签）/ 类型+大小（`PDF · 1.2 MB`）。角标用 `--color-text-muted` 弱化，不抢主体。
- **点击跳转**：
  - file/document → `openWorkspaceItem({absPath})`（复用 canvas L597）打开该文件；若带页码/行范围，打开后**滚动到对应该页/行**（canvas 支持 `scrollToPage`，需新增；行则 Monaco `revealLine`）。
  - task → taskList 面板定位任务行 + 高亮，或切换到任务所在会话。
  - html-element → 打开该 url 的 HTML 查看器，滚到元素（重新计算 selector 高亮）。
- **视觉（visual-style 铁律）**：消息内引用块不用弹窗形态（无毛玻璃问题）；用 `--glass-etched-bg` 底 + 1px `--glass-etched-border` + 圆角 6-8px，避免与消息气泡本体（`rgba(0,0,0,0.04)` 边框）冲突。字重用 **500**（正文 400 / 标题 600 / 按钮 500 铁律——卡片标题用 600，来源行与摘要用 400，badge 用 400 muted）。**不新增颜色 token**，全走既有 `var(--*)`。
- **注入消息 vs 用户消息**：用户发的引用块在用户气泡下（`.bubble.user.att-ref-card`）；agent 返回的 `[引用: …]` 是文本，走现有注入消息渲染，**不特殊处理**（除非后续需求）。

---

## 5 · 边界与实现路径

### 5.1 HTML 元素引用的实现方式（给推荐）

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **CSS 选择器路径 + 提取文本** | ① 稳定指针、可重建；② agent 可读（文本/可操作）；③ 轻量 | 跨源 iframe 无法取 `contentDocument`；需稳健的路径算法 | ✅ **主方案** |
| 截图 | 精确、免解释 | ① 对 agent 是黑盒（无文本/结构）；② 体积大；③ 「已修复」判断困难（vs 二次评审误报教训） | ❌ 不做主方案；非文本元素（图片/iframe）可选缩略图 |
| 选区文本 | 最简 | 丢元素身份（agent 无法定位元素）；无范围 | 作为 `anchor.text` 附加，不单独成方案 |

**推荐** = **CSS 选择器 + 提取文本**为主，非文本元素（img/canvas/iframe）可附缩略图。**实现前提**：HTML 查看器对**同源**内容（本地 `/api/nf-file` 渲染、或同源 html 卡片）可 `iframe.contentDocument` 注入 selection-mode 脚本；对**跨源**页面，选择模式降级为「不可用」并在 UI 提示（或回退选区文本）。这一降级边界必须写进验收（§7 C5）。

### 5.2 大文件引用策略

| 文件类型 | 引用策略 | agent 可见 |
|---|---|---|
| 文本/代码（< 40 行范围） | `anchor.lineStart/end` + `anchor.text`（首 40 行/截断） | `[引用: 文件 · f.md · L12–45 · path]` + 摘要 |
| PDF | `anchor.pageStart/end`（不注入页文本） | 页码指针；agent 用工具开 PDF 定位 |
| Excel | `anchor.sheet + cellRange` | 地址指针；agent 用工具读该区 |
| 大二进制（>5MB / 图片等） | 只引元数据（路径+类型+大小+标题），锚点 none | 零内容注入；agent 只取它需要的信息 |
| 文本超长（>1MB 或 >500 行） | 同「只引元数据 + 首 行 预览」 | 指针 + 摘要，不整读 |

**通用原则**：引用块**默认不注入被引用内容全文**，只给「来源 + 锚点 + 截断摘要」。需要全文时 agent 用工具按指针读取（与现状 `[用户附加文件: path]` 一致）。摘要上限：UI 160 字符，注入块 token 预算 ≤ ~100 token。
**例外**：用户显式「引用含文本」（如引用一段代码/邮件正文）→ `anchor.text` 可放大到整段（上限 4KB），注入块带完整 excerpt——**通过引用类型开关表达，不默认**。

### 5.3 跨类型统一性

- 统一 `Reference` + `refType` 判别；统一工厂 `makeReference`；统一渲染 `renderRefBlock`；统一后端 `resolveRef`。
- **契约**：`refs`（WS 载荷）与 `Reference`（前端对象）字段一一对应；`resolveRef` 对 4 种 refType 分支，产出同构 `[引用: …]` 注入块。
- 所有入口（右键/选择/拖拽）产出**同一结构**——不可达就用工具断言（§7 D 类）。

### 5.4 与现有打回引用（任务系统）的兼容/迁移

- **数据层**：`taskRefs` 字段保留；新增 `refs`。后端 `processTaskReturns`（WebSocketRoutes.scala L3566）改为同时读 `taskRefs` 与 `refs.filter(refType==='task')`。
- **语义**：引用 = 打回（v1.1 裁定）。`type:'ref', refType:'task'` 缺省即走打回语义（return），无需 `_return` 标记；现有 taskRef 行为不变（向后兼容）。文件/文档/元素引用（refType≠task）走 `resolveRef` → `[引用: …]`，不触发任务打回。
- **后端注入块**：`Task.returnInjectionBlock`（任务打回）保留；文件/文档/元素引用走 `resolveRef` → `[引用: …]`。二者可并存（一条消息既打回又引用其他内容）。
- **历史还原**：`persistence.js` 对 `ref` 存 mini 字段（source/anchor/meta/display）以便刷新后重建引用块；对旧 taskRef 走现有路径。

### 5.5 无障碍与动效

- **键盘**：引用块卡片可聚焦（`.att-ref` `tabindex=0`），Enter/Space 展开/移除；展开态焦点不逃逸（focus trap，见 §7 C6）。右键菜单动作可 Tab 达。
- **ARIA**：引用块 `role="group"`/`aria-label`（含来源+标题）；角标 `aria-hidden`（避免读屏噪音，标题含完整信息）；展开按钮 `aria-expanded`。
- **对比度**：badge/来源行 muted 色须 ≥4.5:1（现状 muted 已达标，见案例 001 踩坑 ③ 教训——断言用**增量口径**，不引入既有面板本底溢出误报）。
- **动效**：展开/收起 140ms ease；`prefers-reduced-motion` 时动画时长 ≤0.01s（沿用库内 REDUCED_MOTION 约定）。
- **语言**：所有文案走 `t()` i18n key（新增 key 见 §8），禁硬编码中文（案例 001 踩坑 ① 教训）。

### 5.6 跨源能力差异矩阵与统一降级规则（C5 完整设计，v1.2 补全）

#### 5.6.1 能力差异矩阵

| 来源 | 可选粒度 | 元信息可用性（来源/页码/类型） | 能力不足时的降级路径 |
|---|---|---|---|
| 文件浏览器 · 文件 | 整文件（anchor none） | 路径/文件名/类型/大小 全有 | 无（粒度即整文件） |
| 文件浏览器 · 文件夹 | 整目录 | 路径/目录名/类型 | 不注入内容（agent 用工具列目录） |
| Canvas · PDF | 页码范围 / 文本选区 | 路径/标题/页数 全有 | 无文本层 PDF → 仅页码（`anchor.text` 为空） |
| Canvas · epub | 章节 | 路径/标题/章节名 | 章节定位失败 → 当前阅读位置 → 整文件 |
| Canvas · md/latex | 行范围 / 文本选区 | 路径/类型 | 无选区 → 当前光标行 |
| Canvas · excel | sheet + 单元格范围 | 路径/sheet 名/类型 | **无页码概念** → 用 `sheet!cellRange` 等价锚点（`Sheet1!A1:D10`）；无选区 → 当前激活 sheet 整表 |
| Canvas · HTML（srcdoc/同源） | 元素（selector + 静态快照） | url 或来源 path/标题/tag | 嵌套 iframe 内元素不可选 → 停在边界元素；无 DOM → 整文件 |
| Canvas · 跨源 URL 页 | 整页 URL | url/标题/类型 | 元素选择不可用（S4 toast 明示）→ 整页引用（anchor none） |
| Canvas · 图片/大二进制 | 整文件 | 路径/类型/大小 | 零内容注入，仅元数据（§5.2） |
| 任务栏 · 任务 | 整任务 | taskId/subject/sessionId 全有 | 引用=打回（v1.1 裁定），无降级分支 |

#### 5.6.2 统一降级规则

**粒度优先级链**（从细到粗）：

```
元素/文本选区  >  页码/行范围  >  sheet+cell / 章节  >  整文件/整页  >  仅元数据
```

规则：
1. **取当前上下文可用的最细粒度**：有选区用选区；无选区用当前浏览锚点（页/行/sheet/章节）；再无则整文件。
2. **降级沿链逐级、不跳级**：元素不可选 → 页/行 → 整文件 → 仅元数据。每级降级在引用块上**显式体现**（badge 从 `<p>` → `p.3` → 空），用户对粒度变化可感知，不静默吞掉。
3. **元信息缺失不阻断**：缺页码（excel）用等价锚点（sheet+cell）替代；缺摘要（非文本元素）preview 显示类型描述。任何来源**至少保证「来源路径/URL + 类型角标」两项元信息**（用户裁定「元信息标注」硬规则的底线）。
4. **能力缺失必须明示**：入口不可用置灰 + title 提示（如 PDF 视图的「选择元素」按钮），或激活时 toast 说明（S4 跨源）——禁止点了没反应的静默失败（C5-A 类断言钉死）。
5. **降级产物仍是合法 Reference**：所有降级终点产出同一 `type:'ref'` 结构（§2.1），走同一 `makeReference` 工厂与 `resolveRef` 注入管线（衔接已实施 D1/D5）——降级只改 anchor/meta 内容，不改模型。

---

## 6 · 拉到根目录修复（单独小修）

> 用户 08-25 需求①「文件浏览器拖到根目录能力缺失」。定性为**独立小修**，与全局引用**解耦**（不并入 §3 引用流程），单 commit 可回滚。

**现状定性**：`explorer.js` 已有移动逻辑（`bindTreeDragMove` L263 / `dropDirForEvent` L252）与 `.explorer-root.drop-target` 样式（split.css L250），**根目录 drop 是「空白区域」**——`dropDirForEvent` 仅当命中 `.explorer-root > .explorer-children` 的**空白**且不在任何 `.explorer-item` 上时返回 `''`。

**缺口**：
1. **无恒定根 drop 区**：`.explorer-root`（根容器）自身不是 drop target，只有 `.explorer-children` 的空白才有效。当根目录列表**填满**面板（常见），`.explorer-children` 无空白 → 无法拖到根。
2. **根级文件行不是 drop target**：dropping 在根级 `.explorer-file` 上，`closest('.explorer-item.explorer-folder')` 为 null，rootChildren 检查因落在 `.explorer-item` 上而返回 null → 无效。
3. **无锚点引导**：用户拖到根目录区域时缺少明确的「可拖到根」视觉落点。

**修复方案（小，纯前端）**：
1. `dropDirForEvent` 根分支放宽：若事件命中 `#explorer-tree` 且**不在任何 `.explorer-item`** 上，且目标在 `.explorer-root` 子树内（含根容器）、或落入树的底部空白区 → 返回 `''`。同时把 `.explorer-root`（根容器）本身作为显式根 drop zone（`closest('.explorer-root')`）。
2. 给 `#explorer-tree` 底部加一块**恒定空白 drop 区**（`::after` 或一个 `min-height:24px` 的 `.explorer-root-drop-margin`，flex-grow:1），保证即使列表满也能「拖到面板底部空白 = 移到根」。
3. 视觉复用 `.explorer-root.drop-target` 高亮（已存在，x r 补 `min-height` 保证可见）。
4. 回归：`movePath` 后端已支持 `targetDir:''`（空串 = 根），无需后端改动。

**验收（小修专用，独立于 §7）**：见 §7 末尾「独立小修验收」。

---

## 7 · 可断言验收点清单

> 均为二值判断；标注「(截图)」表示需截图对照，「(auto)」表示可 qa-frontend 转 Playwright 自动化断言（增量口径，避免既有面板本底溢出误报，案例 001 踩坑③）。

### A · 输入框引用块（用户点名问题）——最高优先级

| # | 断言 | 判定 |
|---|---|---|
| A1 (auto) | 引用块为单行固定高：拖入/引用后、输入任意长字、展开/收起，`.att-ref` **高度恒为 36px**（±1px） | 恒定 |
| A2 (auto) | 引用块内容单行截断：`.att-ref` 标题/预览/意见子元素 `white-space:nowrap + overflow:hidden + text-overflow:ellipsis` | 不溢出 |
| A3 (auto) | 意见预览不动布局：向输入框输入「123456…(>180 字符)」，`.att-ref` 高度不变（A1 覆盖）+ 预览截断在 ~180 字符 | 定高截断 |
| A4 (auto) | 展开态：点击 `⤢`/卡片头 → `.att-ref.expanded` 高度 ≤72px、内部多行可滚动；再点收起 → 回到 36px | 可展开 |
| A5 (auto) | ✕ 移除：点击 ✕ → chip 从 `pendingAttachments` 移除并 re-render | 局部删除 |
| A6 (auto) | 意见预览不再「hidden 切换」导致高度跳变：输入/清空输入时 `.att-ref` 高度 A1 恒定，无布局抖动 | 稳定 |

### B · 各入口产出

| # | 断言 | 判定 |
|---|---|---|
| B1 | 文件浏览器右键文件 →「引用」→ 输入框出现 ref 卡片，`refType='file'`、source.path 正确 | 产出 |
| B1b (auto) | 拖 explorer 文件行到输入框 → 产出 `@path` 引用块（非附件上传）((v1.1)) | 拖拽引用 |
| B1c (auto) | 拖 Finder 外部文件到输入框 → 仍走附件上传（`#303` 回归）((v1.1)) | 外部附件 |
| B2 | Canvas PDF 选择「引用当前页/选区」→ ref 卡片带 `anchor.pageStart/end`（如 3-4） | 页码 |
| B3 | Canvas 文本选区「引用」→ ref 卡片带 `anchor.lineStart/end` | 行号 |
| B4 | Excel 选择「引用」→ ref 卡片带 `anchor.sheet+cellRange` | cell |
| B5 | 任务栏「打回」→ 输入框显示 `@#<id> <subject>`，发送后任务被打回（引用=打回，v1.1） | @ 打回 |
| B6 | HTML 元素选择「引用」→ ref 卡片带 `anchor.selector + text`（详见 B6-A 系列）((截图)) | 元素 |
| B6b (auto) | 拖 Canvas 标签页到输入框 → 产出带当前页/选区锚点的引用块((v1.1)) | 标签页拖拽 |
| B7 (auto) | 任意入口：`makeReference` 产出的对象含 `{type:'ref', refType, source, anchor, meta, display}` 六键且类型正确 | 模型统一 |

#### B6-A · HTML 元素选择引用明细（§3.4；衔接已实施 D1/D5：产物走同一 `refs[]` → `resolveRef` 注入管线）

| # | 断言 | 判定 |
|---|---|---|
| B6-A1 (auto) | 选择模式 S1 下 hover 元素：目标计算样式 `outline-width === '2px'` 且 outline-color 为强调色；前后 `document.body.scrollWidth` 不变（outline 零布局抖动） | hover 高亮 |
| B6-A2 (auto) | S1 中点击链接元素 → 页面**未导航**（click 被 preventDefault），200ms 内 `pendingAttachments` 新增一条 ref | 点击选中 |
| B6-A3 (auto) | 产出对象 `refType==='html-element'`，含 `anchor.selector`（≤512 字符）+ `anchor.text`（≤4KB）+ `source.url`（或 srcdoc 时 source.path）+ `display.pageBadge==='<{tag}>'` | 元信息形态 |
| B6-A4 (auto) | 选中时即刻校验 `iframeDoc.querySelectorAll(anchor.selector).length === 1`（选择器唯一） | 唯一锚点 |
| B6-A5 (auto) | sandbox srcdoc 链路：父→子 `_nfRefMode` 以 `'*'` targetOrigin 发出**不抛异常**；伪造 `_nfRefPick`（非激活态/非法 selector/超长 text）按 V1–V4 全部丢弃、无产出、无 WS 帧 | postMessage 通道 |
| B6-A6 (auto) | 静态快照：选中后重渲染 iframe DOM（换内容），已产出 Reference 的 `anchor.text` 不变 | 静态快照 |
| B6-A7 | Esc / 再点按钮 / 切 tab → 退出 S1；iframe 内 `[data-nf-ref-hover]` 计数为 0、无残留监听器 | 干净退出 |
| B6-A8 (截图) | 引用卡片显示页面标题 + `<tag>` 角标 + 摘要截断，视觉遵守 glass/字重铁律（衔接 §4 C1） | 引用块形态 |
| B6-A9 (auto) | 发送后后端注入 `[引用: 页面元素 · {title} · <{tag}> · {url}]` 块，token ≤ ~100（衔接 D1/D5 已实施管线，非新管线） | 注入衔接 |

### C · 渲染与跳转

| # | 断言 | 判定 |
|---|---|---|
| C1 (截图) | 消息里引用块卡片含：图标 + 来源标题 + 摘要 + 元信息角标，视觉遵守 glass/字重铁律 | 视觉 |
| C2 | 点击引用卡片 → 打开对应文件/定位任务/滚到元素（对应类型路径正确） | 跳转 |
| C3 | 带页码的 document 引用打开后 scroll 到该页 | 定位 |
| C4 | 明/暗主题下引用块背景/边框用 `--glass-etched-*`，对比度 ≥4.5:1((截图)) | 主题 |
| C5 | 跨源 HTML 页进入元素引用模式 → 明确提示「不可用/降级」，不静默失败（详见 C5-A 系列） | 降级 |
| C6 | 展开态 keyboard Tab 不逃逸（focus trap），Enter/Space 可达，`aria-expanded` 正确 | 无障碍 |
| C7 | 所有文案走 `t()`，无硬编码中文；reduced-motion 动画 ≤0.01s | i18n/动效 |

#### C5-A · 跨源降级明细（§5.6）

| # | 断言 | 判定 |
|---|---|---|
| C5-A1 | 跨源 URL 页点「选择元素」→ toast 提示「该页面不支持元素选择（跨源）」，停留 S0，无产出、无静默失败 | 明示降级 |
| C5-A2 | 跨源页降级产出整页引用：`anchor.kind==='none'`、`source.url` 保留、类型角标在 | 降级产物 |
| C5-A3 | PDF/图片视图「选择元素」按钮 `disabled===true` 且带 title 提示；PDF 走 §3.2 产出带 `pageStart/end` 引用 | 无 DOM 降级 |
| C5-A4 | excel 引用 `anchor` 必含 `sheet` + `cellRange`、**不含页码字段**；badge 为 `Sheet1!A1:D10` 形态；无选区时 cellRange 为激活 sheet 整表 | 等价锚点 |
| C5-A5 | 矩阵（§5.6.1）每一来源行的降级终点产出对象均含六键 `{type:'ref', refType, source, anchor, meta, display}`（B7 同口径），且至少含来源路径/URL + 类型角标 | 模型不破 |
| C5-A6 | 图片/大二进制（>5MB）引用零内容注入：注入块仅元数据（路径+类型+大小），无 base64/全文（衔接 D5） | 仅元数据 |

### D · 兼容 & 后端

| # | 断言 | 判定 |
|---|---|---|
| D1 | 含 `refs` 的消息发送后，后端注入 `[引用: …]` 块（文件/文档/元素各一） | 注入 |
| D2 | `refs` 含 refType='task' → 触发打回（引用=打回，v1.1） | 打回 |
| D3 | 现有 `taskRefs`（打回）消息行为完全不变（回归） | 兼容 |
| D4 | 刷新后历史里 `ref` 引用块重建成功（persistence 持久化） | 持久化 |
| D5 (auto) | 大文件/PDF 仅注入指针+页码，不注入全文（agent 可见块 token ≤ ~100） | 指针式 |
| D6 (auto) | `refs` 里 refType≠task（文件/文档/元素）不触发任务打回（任务状态不变） ((v1.1)) | 只引用 |

### 独立小修验收（§6 拉到根目录）

| # | 断言 | 判定 |
|---|---|---|
| E1 | 拖文件到根容器空白/底部空白 → 显示 `.explorer-root.drop-target` 高亮 | 高亮 |
| E2 | 松手 → `movePath` 发送 `targetDir:''`，文件移到根目录（刷新后可见） | 移动 |
| E3 | 列表填满时仍能拖到面板底部空白 = 移到根 | 恒定区 |
| E4 | 拖到文件夹行 → 仍移进该文件夹（回归）；拖到文件行 → 无效（不误移） | 回归 |

---

## 8 · 实施拆解（哪些 Frontend 独立做 / 哪些需 Backend 配合）

### 8.1 数据与工厂层（Frontend 独立）

| 改动 | 文件 | 是否需 Backend |
|---|---|---|
| 新增 `makeReference()` 工厂（RefType 判分支，产出统一 Reference） | 新 `js/reference.js` | 否 |
| 新增 `renderRefBlock(ref, {mode})`（输入预览 mode='input' / 消息气泡 mode='message'） | reference.js | 否 |
| 重构 `renderAttachmentPreview` 的 taskRef 分支 → `.att-ref` 卡片（定高/截断/展开，§3.5） | chat.js | 否 |
| 输入框附件区定高容器 + 展开态 CSS | css/chat.css | 否 |

### 8.2 各入口接线（Frontend 独立，或需 Backend 小配合）

| 入口 | 改动 | 需 Backend 吗 |
|---|---|---|
| 文件浏览器右键「引用」 | explorer.js: `showContextMenu` 加菜单项 → `makeReference` | 否（路径转绝对路径可用现有 ws/tool 或前端拼接） |
| 文件浏览器拖文件到输入框 = 引用 | explorer.js `startRowDrag` + input.js `initGlobalFileDrop` 扩展：内部 `application/x-nebflow-file` 载荷 → `makeReference`（refType='file'）；外部文件 → `addFileAttachment`（#303 回归） | 否 |
| Canvas 文档选择引用 | canvas.js + viewers/{pdf,xlsx,markdown}.js: 取当前选区/页区间 → makeReference | 否（数据在 DOM/前端） |
| Canvas 标签页拖到输入框 = 引用 | canvas.js tab `dragstart` 携带当前锚点 + input.js 识别 tab payload MIME | 否 |
| 任务栏「打回」= 引用（`@` 打回） | taskList.js: `requestReturn` 产出 `type:'ref', refType:'task'` 并在输入框显示 `@#<id>` | **是**（后端需识别 `refs` 里 refType='task' 走打回） |
| HTML 元素选择引用（B6，§3.4） | viewers/html.js：srcdoc 装配时内嵌休眠选择脚本 + `_nfRefMode`/`_nfRefPick` postMessage 协议 + canvas.js 顶栏「选择元素」按钮（入口待 §3.4.8 裁定） | 否（srcdoc 内嵌/同源直取；跨源降级 C5） |

### 8.3 Backend 配合项（需 Backend）

| 改动 | 文件 | 说明 |
|---|---|---|
| WS 消息读 `refs` 数组 | WebSocketRoutes.scala: 在 `processTaskReturns` 旁新增 `processRefs`（对 refType≠task 的逐条 resolve → 注入 `[引用: …]` 块） | refType='task' 走现有打回；非 task 分支 resolve→ 注入块 |
| `resolveRef()` 分支 | WebSocketRoutes.scala 或新 helper | 产出同构 `[引用: …]` 注入块（§2.3 ③）；refType='task' 复用 `returnInjectionBlock` |
| 现有 `taskRefs` 兼容（同时读） | WebSocketRoutes.scala: `processTaskReturns` 扩展读 `refs` 中 refType='task' 的，或保留 taskRefs 二者兼容 | 不破坏现有打回 |
| 大文件/PDF page→prompt token 预算 | WebSocketRoutes.scala（注入块组装处） | 指针式，≤100 token |

### 8.4 建议分期（v1.1：P0–P2 全做）

- **P0（前端独立，先行）**：§3.5 输入框引用块定高/截断/可展开（A 类）+ 文件浏览器右键引用 + **拖文件到输入框=引用**（B1/B1b）+ Canvas 文档/标签页选择引用 & 拖拽（B2-B4/B6b）。覆盖用户 08-25 核心痛点 + 全局引用雏形，纯前端可先验证。
- **P1（需后端）**：WS `refs` + `resolveRef` 注入块（D1/D5）+ 任务栏「打回=引用 `@` 语义」（B5/D2/D6）+ 持久化重建（D4）。
- **P2（前端+后端小配合）**：HTML 元素选择引用（B6）+ 跨源降级（C5）+ 引用块点击「滚动到页/行/元素」（C3）+ 消息内引用卡片点击跳转（C2）。（v1.1 裁定全做，不砍。）

> **Frontend 独立的判断依据**：只改 `src/main/resources/web/` 静态资源、无协议/后端/Scala 改动 → QA 可隔离实例验证（S 类冒烟，见 file-drag-drop-spec §5.1 方法）；凡涉及 WS 新字段/注入块 → 需 backend 配合，禁止前端伪注入绕过。

---

## 9 · 参考链接

- Apple HIG · Searching / 链接与导航：https://developer.apple.com/design/human-interface-guidelines/
- Notion · 链接与反向链接：https://www.notion.com/help/create-links-and-backlinks ；页面链接详析：https://thomasjfrank.com/notion-links/
- VS Code · Copilot Chat context（`#`/`@path` 引用）：https://code.visualstudio.com/docs/chat/copilot-chat-context ；Copy AI Reference：https://marketplace.visualstudio.com/items?itemName=Wraithy.copy-ai-ref
- 微信引用回复对话分析：https://files.eric.ed.gov/fulltext/EJ1478736.pdf ；微信官网：https://weixin.qq.com/
- WAI-ARIA APG（focus trap/combobox 契约）：https://www.w3.org/WAI/ARIA/apg/
- Nebflow visual-style 铁律：`~/.nebflow/skills/nebflow/visual-style/SKILL.md`
- Nebflow 现状代码：`src/main/resources/web/js/{explorer,input,chat,taskList,canvas,persistence}.js` · `src/main/scala/nebflow/gateway/WebSocketRoutes.scala`

## 10 · 版本日志

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-08-25 | 初版。全局引用模型（file/document/task/html-element）+ 四大入口流程 + 输入框引用块定高截断可展开（用户 08-25 点名）+ 消息渲染 + 边界（HTML 元素实现/大文件/统一性/taskRef 兼容）+ 拉到根目录独立小修诊断 + A/B/C/D/E 五组可断言验收 + 实施拆解（Frontend 独立 / Backend 配合）。状态 draft，待用户确认冻结。 |
| v1.1 | 2026-08-25 | 用户澄清裁定后修订：① 任务「引用=打回」单一语义，打回改用 `@` mention 格式（§2.3/§3.3/§5.4），取消独立「只引用不打回」分支；② 文件引用入口除右键外**拖到输入框=引用**；**Canvas 标签页可拖到输入框**（§3.1/§3.6）；③ P0–P2 **全做**（含 HTML 元素引用 + 点击滚动跳转）；④ 拉到根目录确认单独小修独立派发。新增 B1b/B1c/B6b/D6 断言。 |
| v1.2 | 2026-08-25 | B6/C5 章节补全（draft 待用户确认）：§3.4 扩为完整设计（tab 级状态机 S0–S4 / outline 零抖动 hover 高亮 / srcdoc 内嵌+同源直取+跨源降级三通道 / `_nfRefMode`·`_nfRefPick` postMessage 协议沿用 `'*'` targetOrigin 实测教训与 e4-e9 payload 校验模式 / 唯一性校验选择器算法 / **静态快照取舍**（活引用否决）/ 边界与无障碍）；§5.6 新增跨源能力差异矩阵（10 来源行）+ 五级粒度降级链与五条统一规则；§7 新增 B6-A1–A9、C5-A1–A6 断言系列（标注衔接已实施 D1/D5 管线）。§3.4.8 B6 入口位置三选项推荐 A（顶栏按钮），待用户裁定。 |
