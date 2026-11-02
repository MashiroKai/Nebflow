---
type: audit
status: current
---

# 文档与编辑体验：勘察清单 + 第一批落地（2026-11-01）

- **范围**：文档阅读（14 个格式渲染器）与编辑（Monaco）、标注、文档树/导航、viewer 生命周期。键位/焦点环/命令面板属另一条并行线，本批不碰。
- **结论先行**：阅读**排版面**（markdown / 大文本虚拟滚动 / pdf 懒渲染）质量已经不错，不造需求；真缺口集中在**三处**——① viewer 协议没有 `dispose`，关页签后阅读器/document 钩子/pdf worker 全部存活；② 阅读位置在页签切换与重渲后普遍丢失（只有 markdown 做了记账）；③ 编辑链路的脏点与未保存保护有洞。本批落第一批低风险项，并补齐视觉基线覆盖（3 个新场景 × 双主题），使后续优化第一次具备「可自动判定」的前提。
- **关联**：阅读器设计件 [[20261030_181655_reader-experience-design__delegate-nebflow-38afccc8.zh-CN]]（本批的缺口即该设计件的未落地部分）、阅读器 P0 实现 [[20261030_191500_reader-p0-implementation__delegate-nebflow-a1e28e75.zh-CN]]、大文件 P2 [[20261030_190200_reader-p2-large-file__delegate-nebflow-f8da1d4a]]。

---

## 一、勘察（带证据）

### 1. 阅读体验

| 现象 | 证据 |
|---|---|
| 阅读器外壳只覆盖 epub/mobi/azw3；md/html/txt/pdf/docx/xlsx/pptx/图片/code 各自裸渲染 | `js/viewers/readerChrome.js` 仅由 `epub.js:680`、`mobi.js:403` 的 `mountReader(dot)` 调用 |
| 外壳本身能力不弱：目录抽屉 / 进度条 / 跳百分比 / 字号 / 行距 / 翻页模式 / 标注面 | `readerChrome.js:122-138`（装配）、`:333-359`（TOC）、`:237-286`（进度与页码）、`:405-427`（排版） |
| 外壳缺三件基础件：**全文搜索**、**书内链接跳转**、**阅读位置持久化** | 搜索：全文件无 find 代码（对比 `largeText.js:668-705` 有服务端扫描、`fileSearch.js:92` 有 dom find）；书内链接：`readerChrome.js:948-951` 对 `a[href]` 一律 `preventDefault()` 后不跳转；续读：启动硬编码 `mountChapter(0)` + `scrollTop=0`（`:1000-1003`） |
| 大文件：largeText 真虚拟滚动（256KiB 窗口 / 12 窗驻留 / 稀疏索引 / 服务端扫描搜索）；pdf 视口驱动懒渲染（±2 屏 / 并发 2 / 位图 ≤6） | `largeText.js:87-98`、`textStream.js:29-32`；`pdf.js:40-48`、`:342-355` |
| **epub 无懒加载**：全书 spine 串行解压后才挂壳 | `epub.js:548-568` |
| pdf 打开前逐页量尺寸（≤2000 页），首屏被这轮扫描阻塞；且 pdf 无页码/无跳页输入 | `pdf.js:144-149`、`:173-174`（全文只有 `_scrollToPage` 给外部调用 `:106-120`） |
| 滚动位置：**只有 markdown 有记账**（`_mdScrollPos` + `canvas-tab-activated`）；epub/pdf/txt 一律回顶部 | `markdown.js:862-896`；派发点 `canvas.js:1069-1075`（判据只认 `_editorHandle` / `_mdScrollPos`）；重置机制见 `split.css:1040-1057` 与 `markdown.js:858-861` 的实测记录 |
| 阅读器键盘劫持：pane 上的方向键无输入焦点守卫，而 TOC/跳转输入/笔记 textarea 都在 pane 内 | `readerChrome.js:970-974`（修前）+ `annotations.js:396-433` |
| `.txt` 阅读面重渲只 append 不清 pane ⇒ 每激活一次叠一份 `.canvas-txt-scroll`（absolute 全覆盖） | 修前 `textReader.js:52` 只有 `pane.appendChild(wrap)`；重渲入口 `canvas.js:1089` → `:1462-1467` |

### 2. 编辑体验

| 项 | 状态 | 证据 |
|---|---|---|
| 覆盖格式 | code/json/csv 直开；md/html/txt 的源码态复用同一编辑器 | `viewers/monaco.js:57-82`；`shared.js:648-693` |
| 查找替换 / 多光标 / 撤销 | 有（Monaco 原生；`.monaco-editor` 焦点让位给 Monaco） | `fileSearch.js:58-65`、`chatSearch.js:297-306` |
| 格式化文档 / 自动保存 / 保存按钮 | **无**（唯一保存触发 = ⌘S） | 全仓 `formatDocument|formatOnSave` 零命中；`monacoEditor.js:444-451` |
| 保存链路 | 唯一通道 WS `writeFile`（`monacoEditor.js:392-398` → `WsFileOpsHandlers.scala:1171-1239`），失败面**可见**：`fileSaveError` → error toast（`:381-389`）、20s 超时 toast（`:362-368`）、本地 10MB 闸带常驻横幅 | 同上 |
| 保存链路的洞 | 缺 `sessionId` 时服务端静默 `IO.unit`（不回帧）→ 前端只表现为 20s 超时；前端 10MB 闸与服务端 64MB 上限不一致；`messageTooLarge` 无监听 | `WsFileOpsHandlers.scala:1187`；`WebSocketRoutes.scala:1858,1920-1936` vs `monacoEditor.js:39` |
| 未保存保护 | **无**：关页签/预览顶替直接销毁脏缓冲，全仓无 `beforeunload` | `canvas.js:815-870`、`:793-809`、`:696-709` |
| 源码↔渲染往返 | 内容与撤销栈存活（model 复用），**脏点被误清零** | 修前 `shared.js:694-707` + `monacoEditor.js:250-255,292`；本批已修（见 §三） |
| 外部变更检测 | 无：脏时自动刷新静默 return（无提示），`nf-cache-invalidate` 不触达编辑器 | `canvas.js:907`；`main.js:1979-1984` |

### 3. 标注

- 类型只有**高亮（4 色）+ 批注**，无下划线/绘制：`annotations.js:39`、`:226-230`。
- 持久化在**服务端整件 json** `<projectRoot>/.nebflow/reader-annotations.json`（通道 WS `reader.anno`）：`annotations.js:17-21,94,117` ↔ `ReaderAnnoStore.scala:27`；localStorage 只是镜像，且是**全局单键**而非 per-file（分区靠记录字段 `bookRef`，`annotations.js:43,131-134`）。
- 重开能恢复；失去锚点有三级降级（quote → ±400 模糊 → unresolved 灰显），数据不丢：`annotations.js:160-185,491-509`。
- 不写回新 offset（每次打开重跑模糊定位），无版本/ETag，与编辑器的保存事件无联动。
- 只有 epub/mobi 有标注面（`epub.js:630-640`、`mobi.js:389-393`）；pdf/md/html/txt 无。

### 4. 文档树 / 导航

- 单文件 `explorer.js`（2190 行），行是 createElement 逐条 append，**无虚拟滚动、无上限、无分页**（`explorer.js:728-858`、`:1364-1377`；服务端 `FsOps.scala:112-133` 也无 take）。
- 排序固定「目录优先 + 名称小写序」，由服务端硬编码（`FsOps.scala:124`）；**无排序控件、无筛选、无搜索框**（`index.html:672-685` 只有新建/打开）。
- 缩进是内联像素（`explorer.js:752,804`；无 CSS 规则），深度无上限、`#explorer-tree` 无 `overflow-x`（`split.css:169-174`）⇒ 20+ 层时标签被裁到 0 宽且不可达。
- 长文件名：尾省略（`split.css:341-344`），**无 `title` 属性**，全名只能打开文件才看得到（`explorer.js:762-764`）。
- 重渲状态：展开集合保留（`explorer.js:283,1387-1399`）、选择在 dir 重载时保留但在整树重建时清空（`:721`）；**滚动位置不保留**（全文件 0 处 scrollTop）。
- 键盘导航**完全没有**（只有 Esc 清选择 `:1615-1622`；行无 `tabindex`/`role`）。

### 5. 生命周期缺口（`dispose` 缺失的实际后果）

| viewer | 关页签后仍活着的东西 | 位置 |
|---|---|---|
| `ViewerProtocol` | **协议里根本没有 teardown 成员**；`canvas.js teardownTab` 只做 `disposeTabEditor`（Monaco/流式）+ `paneEl.remove()` | `utils.js:43-50`（修前）、`canvas.js:793-809` |
| readerChrome（epub/mobi） | document keydown ×1、documentElement MutationObserver、matchMedia 监听、**永不 disconnect 的 ResizeObserver**；整本书 DOM 因此无法回收 | `readerChrome.js:208-215,995,993-1020` |
| pdf | PDFDocumentProxy + worker 线程永不 `destroy()`；IntersectionObserver 从不断开；缩放 debounce 定时器 | `pdf.js:77,342-355,359,399` |
| image | ResizeObserver 永不 disconnect；缩放引擎的 document 级 ⌘±、模块级 `hovered` 里那条永不出栈的 ctl；Space 平移 document 键 | `image.js:118-120,135,203-204`、`zoom.js:17-18,23-31` |
| pptx | staging iframe 挂在 `document.body` 上（60s 超时兜底），`_pptxTeardown` 全仓无赋值（死约定） | `pptx.js:507-521,399` |
| html | 幂等单例，bounded；但 zoom 引擎靠 `_nfZoomDestroy` 手存，关页签无人调 | `html.js:568-603,311-313` |
| largeText | **唯一合格**：`destroy()` = cancel + disconnect + 清窗，且被 `canvas.js:648-652` 调用 | `largeText.js:758-764` |

---

## 二、优化清单（按 收益 ÷ 成本 排序）

| # | 痛点 | 影响面 | 成本 | 风险 | 视觉基线可验 | 分批 |
|---|---|---|---|---|---|---|
| 1 | 视觉基线只覆盖 14 渲染器里的 2 个 | 全部后续优化的判定前提 | S | 极低（只加夹具） | —（它就是基线） | **第一批** |
| 2 | viewer 无 `dispose`（阅读器钩子/pdf worker/observer 泄漏） | epub/mobi/pdf/image/html 五类 | M | 低（协议可选 + 单点调用） | 否（脚本可验） | **第一批** |
| 3 | `.txt` 重渲叠 DOM + 阅读位置丢失 | 所有 .txt 页签 | S | 低 | 是（脚本验） | **第一批** |
| 4 | 源码↔渲染往返把脏缓冲误判为干净 | md/html/txt 编辑链路（数据丢失风险） | S | 中低（判据带内容指纹） | 否（脚本验） | **第一批** |
| 5 | 阅读器方向键劫持输入框 | epub/mobi 笔记/跳转 | S | 极低 | 否 | **第一批** |
| 6 | 关脏页签静默丢编辑（无确认、无 beforeunload） | 所有编辑页签 | M | 中（要动 closeTab 交互，且与键位并行线相邻） | 否 | 后续批（需先定交互：确认框 vs 依从 VS Code 的「保留为未命名缓冲」） |
| 7 | 外部改动无冲突提示 | 编辑链路 | M | 中（要接 nv-cache-invalidate） | 否 | 后续批 |
| 8 | epub 无懒加载 / 无续读 / 无书内链接 | epub 大书 | L | 中高 | 否 | 后续批 |
| 9 | pdf 首屏量尺寸阻塞、无页码/跳页 | 数百页 pdf | M | 中（`tests/pdf-lazy-render.spec.mjs` 有护栏断言） | 部分 | 后续批 |
| 10 | Ctrl+F 在 pdf/epub/docx 里弹「搜索聊天」 | 全部非 md/html 阅读面 | M | 中（要统一 find 通道） | 否 | 后续批 |
| 11 | 文档树无搜索/排序/筛选、深层不可达、长名无 tooltip | 大仓文档树 | M | 低（纯增量） | 是 | 后续批 |
| 12 | 文档树键盘导航（上下键/Enter） | 可达性 | M | 中（与键位并行线同域，必须让位） | 否 | 后续批（等键位线落地后做） |
| 13 | 阅读器搜索 / 书签 | epub 用户 | L | 中 | 否 | 不做（先看 6/8 落地后的真实反馈；本批无证据表明它是首要痛点） |
| 14 | monaco 自动保存 | 编辑链路 | S | **中高**（自动保存会放大「外部改动覆盖」问题，须先有 #7） | 否 | 不做（顺序依赖 #7） |
| 15 | 标注 offset 回写 | 标注 | M | 低 | 否 | 不做（现状三级降级已不丢数据，收益小于面） |

### 本批选择说明

选 1–5：全部满足「低风险 / 不碰主链路 / 可验证」，且 1 是 2–5 的判定前提。**调整说明**：任务建议里的「阅读体验一两处确定性改进」我选了 `.txt` 位置保持 + 阅读器键盘守卫，而不是 epub 续读——后者要动阅读器启动时序与大书内存，成本 L，属后续批。编辑链路只修了「脏点误清零」（有自动化脚本可断言），没碰关页签确认框（交互待裁 + 与并行线相邻）。

## 三、第一批落地

| 文件 | 改动 |
|---|---|
| `js/utils.js` | `ViewerProtocol` 新增可选 `dispose(pane)`（含「什么该由它清、什么随 DOM 死」的契约注释） |
| `js/fileViewers.js` | 新增 `disposeViewer(pane)`；`renderFile` 渲染前先卸上一个 viewer，声明了 `dispose` 的 viewer 记账到 `pane._nfViewerDispose` |
| `js/canvas.js` | ① `disposeTabEditor` 开头消费 `_nfViewerDispose`（关页签的全部路径都汇到这里）；② `setActiveTab` 的 `canvas-tab-activated` 派发判据加 `_nfTxtScrollPos` / `_nfReaderScrollPos` 两个记账键 |
| `js/viewers/textReader.js` | 重渲先 `pane.innerHTML=''`（不再叠 DOM）；滚动位置记账 + 渲染后 rAF 还位 + 切签还位 |
| `js/viewers/readerChrome.js` | 方向键加输入焦点守卫；ResizeObserver 提为具名变量并在 teardown 里 disconnect；滚动位置记账 + 切签还位；删掉挂在 pane 上的 `canvas-tab-closed` 死监听 |
| `js/viewers/pdf.js` / `image.js` / `html.js` / `epub.js` / `mobi.js` | 各自实现 `dispose(pane)`：pdf（observer/定时器/缩放引擎/document + worker destroy）、image（ResizeObserver/缩放引擎/Space 键）、html（缩放引擎）、epub/mobi（readerChrome teardown + 本书 object URL） |
| `js/monacoEditor.js` | handle 新增 `markDirty()`（不靠内容变更也能标脏） |
| `js/viewers/shared.js` | 源码→渲染时把「脏 + 当时内容指纹」记在 pane 上，回源码态按指纹补回脏点 |
| `scripts/visual-baseline.mjs` | 新增 3 场景 × 双主题：`canvas-txt-tab` / `canvas-html-tab` / `canvas-image-tab`（12 → 18 张基线） |
| `scripts/verify-viewer-reading-batch.mjs` | 13 条断言覆盖 ①②③（含「切走时浏览器确实把滚动盒重置为 0」的前提自检） |

### 验红实证

把 `textReader.js` / `shared.js` / `monacoEditor.js` / `canvas.js` 换回 HEAD 版本跑同一脚本：`10/13`（①b 叠出 2 份 `.canvas-txt-scroll`、②d 卸载槽未消费、③c 往返后 `dirty=false` 三条红），本批代码 `13/13` 绿。

### 闸

- `node scripts/check-js-types.mjs` → `checkJs: 0 errors … PASS`
- `node scripts/build-web.mjs` → PASS（203 sources，orphans 0）
- `npm run visual:check` → 18/18 SAME（原有 12 张逐字节未变：`--update` 后 `git status` 只显示 6 新增 png + manifest）
- `node scripts/check-circular.mjs` → 仍为既有 2 个 SCC（`popArtifacts/reference/msgQuote`、`mobile/navController/taskPanel`），无新增

### 未验证 / 需人工确认

- 真实 epub/mobi 页签的 lean-up 效果（仓库无 epub 夹具，`dispose` 路径只在 image 上做了自动化断言；readerChrome teardown 的正确性靠代码审读 + 既有重渲入口同函数）。
- pdf `doc.destroy()` 后重开同一文件的表现（无 pdf 夹具纳入基线）。
- 运行实例上的实际体感（本批全程未起服务、未碰 6138；一切验证都在零端口 harness 里完成）。
