# REPORT — markdown 支持 mermaid 渲染（mermaid-md 批）

作者令：2026-10-23 19:24 微信，逐字「让我们的markdown支持mermaid渲染」。
交付分支：`delegate/delegate-nebflow-18fa62b6`。

## 1. 结论

`​```mermaid` 围栏块在**所有共享 markdown 渲染面**渲染为 SVG 图表：懒加载、视口触发、亮暗双主题实时跟随、strict 安全消毒、错误态保留源码可查。spec 19 项全绿，checkJs / build-web 双闸全绿，截图证据见本目录。

## 2. 考古结论（实现依据）

- 共享 md 渲染器 = `web/js/utils.js#renderMarkdownWithMath`（marked v12 + KaTeX），文档查看器（`viewers/markdown.js`）、wiki 文档、聊天气泡、flow 卡片、报告卡片、agentManager 等全部经它出 HTML——在共享管线落占位符即覆盖全局面。
- 主题体系 = `prefers-color-scheme` 媒体查询（无手动开关），既有先例：monaco（`monacoEditor.js` theme define + change 监听）、highlight css 双档 link。
- vendor 惯例 = `web/vendor/` 本地库 + README 台账（`vendor/pptx-renderer/README.md` 先例）；懒加载惯例 = pdf.js/monaco 的单飞 Promise script 注入。
- MutationObserver 是仓内既有模式（colResizer / wikiGraph / activityBar）。

## 3. 改动清单（最小面）

| 件 | 变更 |
|---|---|
| `web/vendor/mermaid/mermaid.min.js` | **新增** mermaid 12.2.0 官方 `dist/mermaid.min.js`（5.5MB，esbuild IIFE，结尾无条件 `globalThis.mermaid` 赋值，无 `define.amd` 分支 ⇒ 与 monaco AMD loader 零冲突面；内联 CJS 依赖的 module/exports 均为 bundle 局部变量） |
| `web/vendor/mermaid/README.md` · `LICENSE` | **新增** 台账（npm tarball sha256 `0719…f3a0a` / 文件 sha256 `a8a6…3b9f` / MIT 随包件 / 升级方法） |
| `web/js/mermaidRenderer.js` | **新增** 全部运行时逻辑（见 §4） |
| `web/js/utils.js` | marked.parse 后新增一道 pass：`language-mermaid` 代码块 → `.nf-mermaid` 占位 div（源码进 `data-mermaid-src`；先于复制按钮 pass，故图表不裹复制 chrome） |
| `web/js/main.js` | import + `armMermaidAutoHydrate()` 一次（全局 MutationObserver，一处接线覆盖全部 markdown 面，chat/flow 卡/弹窗零改动） |
| `web/js/viewers/markdown.js` | import + 主文档面显式 `hydrateMermaidIn(mdViewer)`（幂等，state 属性防重入） |
| `web/css/mermaid.css` + `index.html` link | **新增** 占位骨架（脉冲族）/错误态/源码面板样式，token-only |
| `web/js/locales/zh-CN.js` · `en.js` | `mermaid.renderFailed` / `mermaid.showSource` / `mermaid.hideSource` 三键双语言 |
| `tests/mermaid-md.spec.mjs` | **新增** Playwright 验收 spec（真链路：workspace-open-item → viewMarkdown → 共享管线 → hydrate） |

服务端 / 构建脚本 / 包管理器：**零改动**（vendor 走 build-web 既有 copy-through；sw.js `/vendor/` 前缀缓存自动覆盖）。

## 4. 五项要求逐条落点

1. **库引入**：mermaid 12.2.0 vendored，README 台账随件；build-web 既有流程直通（vendor/ 目录 copy-through），无新包管理器。
2. **渲染**：占位框保留布局（72px 骨架槽 + 脉冲，无零高闪烁）；`mermaid.render` 异步出 SVG 后贴入；失败块 = 块内错误行（错误文案 + 详情）+「查看源码」展开原始围栏源码（textContent 注入，不空白不吞）。截图证据：`mermaid-light.png`（flowchart + sequenceDiagram）、`mermaid-dark.png`。
3. **主题**：`matchMedia('(prefers-color-scheme: dark)')` change → 完成块全部重渲染（generation 守卫丢弃在途旧主题写入，无悬挂态）；SVG 配色读 sapphire token（`--sapphire` 三元组单源），light=neutral+sapphire 染色、dark=dark+transparent 背景。spec R6 断言 light≠dark 且切换后无悬挂。
4. **安全**：`securityLevel:'strict'`（标签消毒、禁 click 回调/脚本）+ SVG 落 DOM 前防御扫描（`<script`/`on*=`/`javascript:`，命中即 fail-closed 落错误态）+ `suppressErrorRendering:true`（mermaid 默认会把「Syntax error」炸弹 div 追加到 `<body>`——实测截图暴露后已关掉，错误面完全归块内错误态）；渲染经 mermaid 自持游离临时容器完成，成品 SVG 才入 DOM。
5. **性能**：库 5.5MB **不进开屏**——首个 mermaid 块进入视口（320px 提前量）才注入 script（单飞 Promise）；视口外/长文档懒渲染（spec R5：底部块滚动前 pending、滚动后渲染）；渲染结果按 `主题|源码` LRU 缓存（24 条），流式重挂载命中缓存同步贴图。

## 5. 验收证据

- `spec-run.log`：19 PASS / 0 FAIL——R1 双图 SVG 非零尺寸；R2 语法错误块错误态 + 源码可查看；R3 普通 js 代码块原样（pre>code + 复制按钮 + 逐字内容）；R4 普通 md 结构零回归 + 页面零未捕获异常 + 零新增 console 错误；R5 视口外懒渲染；R6 亮暗切换配色变化 + 无悬挂；R7 其它 markdown 面（任意挂 body 容器）全局 observer 自动渲染。
- `mermaid-light.png` / `mermaid-dark.png`：1280x900 实机截图，flowchart + sequenceDiagram 双样例双主题。
- 闸门：`node scripts/check-js-types.mjs` PASS（257=baseline，新文件 checkJs-clean）；`node scripts/build-web.mjs` OK（181 源文件孤儿守卫 0，29 个 css 连带 mermaid.css）。
- 窄列证据：桌面阅读列宽 ≈410px（截图可见）≈ mobile 内容列量级，双图无横向溢出（svg `max-width:100%`）。

## 6. 申报与开放项

- **既有红（非本批）**：`tests/mdscroll-toc-scroll.spec.mjs` 在 HEAD 即红（`#activity-bar` hidden 超时；以 `git archive` HEAD 纯净树差分复跑确认，与本批无关）。疑似今日「面板合并 + 移动端 UX 七件」批改了 activity-bar 可见性语义，其 harness 可见性判据过期。按最小改动红线未越界修，建议该批归属侧跟进。
- **mobile 层**：≤768px 进 mobile 层（bottom-sheet canvas），harness 桌面 boot 判据不适用（已注明于 spec 头注释）；mobile 面挂同一套 canvas viewer DOM，管线与 chrome 无关。
- **未覆盖腿**：流式聊天逐帧重挂载的缓存命中路径（R7 间接覆盖 observer 面，未做逐帧断言）；gantt/pie 等其余图类型的逐类型截图（mermaid 12 全类型内联，管线性无差别）。

## 7. 关键假设

- 「渲染沙箱化」落点 = strict 消毒 + 防御扫描 + 游离容器渲染 + suppressErrorRendering（未采用 mermaid `securityLevel:'sandbox'` 的 iframe 面：其与主题跟随/阅读面样式冲突，且 strict 已封死脚本执行路径）——如作者要 iframe 级沙箱另行批次。
- 「报告 md 卡片」= flowMapArchive/flowViewers 等经 `renderMarkdownWithMath` 的卡片面，由共享管线 + 全局 observer 覆盖，未逐一改卡内代码。
