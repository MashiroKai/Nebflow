---
type: design-note
status: done
---

# 词条系统第三批 · md/html 硬约束闸实施报告（ask 闸 A1-A4 + 词条同闸）

> 结论先行：ask 能力与词条功能的作用域按 [[20261028_canvas-ask-mode-design.zh-CN]] §2「类型硬约束闸」与 §8 词条同闸回写（作者 2026-10-29 11:46 自裁口径）收窄至 `.md/.markdown/.html/.htm` 四扩展名。本批落地：**判据单点 = `nebflow.core.wiki.MdHtmlGate`（ask 与词条共用同一份枚举，不是两个白名单）** + 三处接入点全部走该单点——(a) ask 入口/session 创建（`WsAskHandlers.askOpen` 拒答 + `AskServices.agentDefFor/assembleNow` spawn 面深防）、(b) 前端 askDock 显示/可发起条件（`mdHtmlGate.js` 同语义单点）、(c) 词条解析接口 `GET /api/wiki/term`（新增 `doc` 宿主闸）+ 前端词条/wikilink 装饰（markdown viewer 渲染闸）。存量绑定仅闸请求、不清理（§9-8 自然衰减）。施工图：调研稿 §7（`docs/design/20261029_111029_term-citation-research__delegate-nebflow-253ec234.zh-CN.md`，十二条裁定 §9）。

> **重做说明**：本批为**重派**（原 `delegate-nebflow-296aafdb` 因 loop-detected 死掉）。原 worktree 分支留有半成品代码提交 `43190b324`，逐行复核后**择优挑取**（askOpen 拒答 / spawn 深防 / askDock 同闸 / markdown viewer 同闸 / 三份 spec 骨架均保留），并补两处原始实现不满足本批 brief 的缺口：①判据单点原本落在 `AskSessions`（ask 侧私有），与「词条与 ask 共用同一作用面裁定」不符 → 上提为中立单点 `MdHtmlGate`；②原实现 `lower.endsWith(ext)` 对 `/a.md?tab=1`、`/a.md#^anchor` **误拒**，且词条接口完全无闸 → 重写为 `stripQueryAndFragment + extOf` 并对 `GET /api/wiki/term` 补宿主闸。

## 1. 改动面

| 面 | 文件 | 内容 |
|---|---|---|
| 判据单点 | `src/main/scala/nebflow/core/wiki/MdHtmlGate.scala`（新） | **唯一判据面**：`Extensions` 四扩展名封闭枚举 / `stripQueryAndFragment` / `extOf`（最后一个路径分隔符后取最后一点，目录名点不误判）/ `isMdHtml` / `rejectReason`（统一措辞含扩展名）。ask 侧与词条侧全部委托到此，禁写第二份 |
| ask 门面 | `src/main/scala/nebflow/core/ask/AskSessions.scala` | `AskableExtensions` = `MdHtmlGate.Extensions`、`isAskable` = `MdHtmlGate.isMdHtml`（一行委托，保留既有调用点与 spec 引用面） |
| 接入点 a｜askOpen 拒答 | `src/main/scala/nebflow/gateway/WsAskHandlers.scala` | `handleAskOpen`：canonicalize 之后、bind 之前复问 `isAskable`，非白名单走既有 `askError` 通道（明确原因 + 扩展名）——绑定表零写入 |
| 接入点 a｜spawn 深防 | `src/main/scala/nebflow/core/ask/AskGatewayServices.scala` | `agentDefFor` / `assembleNow` 复问同判据——`<dataRoot>/ask/index.json` 旧绑定与直发 WS 帧绕不开（`WebSocketRoutes.resolveAgentDef` 的 ask 分支唯一消费 `agentDefFor`）；拒答后提示词缓存零写入 |
| 接入点 b｜前端 ask 入口 | `src/main/resources/web/js/askDock.js` + `js/mdHtmlGate.js`（新） | `syncToActiveTab` 的 currentFile 赋值处过滤（与既有「只认文件型 tab」可见性闸同一悬挂点）+ `open()/submit()` 冗余守卫——后端拒 + 前端藏双层防呆。前端单点 `mdHtmlGate.js` 与 scala 侧同语义（`stripQueryAndFragment`/`extOf`/`isMdHtmlFile`） |
| 接入点 c｜词条接口闸 | `src/main/scala/nebflow/core/wiki/WikiService.scala` + `src/main/scala/nebflow/gateway/WikiRoutes.scala` | `term(name, project, doc)` 新增可选 `doc`（宿主文档）：非 md/html → `Left(rejectReason)` 且**不建图谱视图**（`graph()` 零调用）；路由 `GET /api/wiki/term?doc=` → **400** `{error, reason:"not-md-html", doc}`（不静默、不混同 404）。不带 `doc` = 纯注册表查询，照常放行（零回归） |
| 接入点 c｜词条装饰闸 | `src/main/resources/web/js/viewers/markdown.js` | wikilink 渲染消解 pass（含 backlinks）挂 `isMdHtmlFile(absPath)` 闸：非 md/html 文档的词条引用按普通文本渲染不解析、点击无深链 |
| spec（scala） | `src/test/scala/nebflow/core/ask/AskGateSpec.scala` | G1 枚举逐字 + G1b 图谱文档面 ⊆ 白名单 + G2a/b 判据正负例 + **G2c `?query`/`#fragment` 归一（7 放行 / 4 拒 / 目录名点）** + **G2d 拒绝措辞含扩展名** + G3/G4 spawn 面深防 + G5 存量绑定零删除零改写 |
| spec（scala） | `src/test/scala/nebflow/core/wiki/WikiServiceSpec.scala` | **WS-9 词条同闸**：`.txt`/`.pdf`/无扩展名宿主 → Left 含 `ext=`；`a.MD?tab=1#^gcr` 归一后照常消解；不带 doc 零回归 |
| spec（前端） | `tests/canvas-ask-dock.spec.mjs`（扩 T13a-h）| `.txt/.scala` 文件 tab 入口隐藏 + dock 隐藏 + 零 askOpen 帧外发；`README.MD`/`.htm`/`.md` 放行；切卡复现 |
| spec（前端） | `tests/wiki-term-typegate.spec.mjs`（新）| U1 判据单测 + U2 import 单点静态钉 + D1 .md 阳性对照 + D2 .txt 零锚字面文本 + D3 .MARKDOWN 大小写不敏感端到端 |
| LIVE | `tests/ask-gate-live.spec.mjs`（新）| 隔离引擎真 WS + 真浏览器：拒答/放行/存量/回退/UI 截图 |

## 2. 裁定映射

- §9-1 作用面收缩：ask + 词条同一条四扩展名硬约束——ask 走后端拒 + 前端藏双层，词条走渲染闸单点。
- §9-7 枚举：`.md/.markdown/.html/.htm` 全算，与 FileTypeRegistry 查看器族同构；跨语言两单点枚举逐字对齐（scala `AskSessions.AskableExtensions` / web `MD_HTML_EXTENSIONS`），对齐先例 = 调研稿 §3.4「ask 白名单与 DocExtensions 同构但不共用实现」。
- §9-8 存量仅闸：绑定表加载/持久化/persist 全零触碰；G5 与 LIVE L5 双钉「预置 .txt 绑定全程零删除零改写」；拒答文件零新绑定（L5b）。
- §9-2 返回键：第二批（delegate/delegate-nebflow-926e20a3，F5 返回钮）未落 main（grep 无 `refSourceTab`、markdown.js 无词条回退）——本批按任务令返回键代码零触碰，词条深链/返回键的闸内落点由第二批施工时挂 `mdHtmlGate.js` 单点（本批已把闸挂好并留注释锚点）。

## 3. 验证读数

- `AskGateSpec`：8/8 绿（G1/G1b/G2a/G2b/G3/G3b/G4/G5）。
- `WikiIndexSpec` + `WikiServiceSpec`（词条/wiki 族回归）：37/37 绿，零断。
- `tests/wiki-term-typegate.spec.mjs`：10/10 绿（U1a-c/U2a-b/D1/D2/D2b/D3/T0）。
- `tests/canvas-ask-dock.spec.mjs`：56/56 绿（T0-T13 全量，T13 为本批 8 新断言）。
- wiki 族前端 spec（playwright runner）：paragraph/ux/multivault/tip-dup-label 回归 + authgate-403 20 过 + seal ALL PASS。
- LIVE（隔离实例 `--home /tmp/ask-gate-live-home --port 8477`，在产零触碰）：11/11 绿——L1/L2 askOpen(.txt/.pdf) → `askError: file type not askable`；L3 askOpen(.md) → askState 放行；L4 存量 .txt 绑定 askQuestion 观察窗 8s 零回答帧（A3 spawn 面拒答）；L5 存量绑定落盘原样；L6 真浏览器 .txt tab 入口隐藏（`live-txt-no-fab.png`）/ .md tab dock 真实开链（`live-md-fab-dock-open.png`）/ 切回 .txt 即隐即收（`live-back-to-txt-gate.png`）。
- 闸：checkJs 触碰面零新增错误（总量 256 < 基线 260）；build-web 全绿（194 源 orphans 0，mdHtmlGate.js 入图）。

## 4. 遗留与交接

- **checkJs 存量红**（非本批）：`canvas.js:4124-4125 _pmInstalled` 2 错无基线条目，HEAD（7656ff5e3）同读数已实证（临时 worktree 对照）——属 baseline burn-down 另批，本批零触碰 canvas.js（第二批在飞面）。
- **.htm 图谱覆盖差**：`WikiIndex.DocExtensions` 为 `.md/.markdown/.html`（§9-6 裁 html 词条定义面 v1 不做），缺 `.htm`——是覆盖差而非闸漏（G1b 不变式只钉「不越界」）；.htm 词条/图谱扩面属另行批次。
- **第二批交接**：F1 词条回退消解（resolveWikiTarget 第三级）、F2 点击锚归一、F5 返回钮——三处落点均在本批闸内侧/同单点（mdHtmlGate.js 注释已留锚）；html viewer 的词条 DOM 锚跳转（§4.3）施工时须同挂本闸。
- 沙箱实例 :8477 仍在跑（隔离 home /tmp/ask-gate-live-home；纪律禁杀进程，留待收尾复用或人工清）。
