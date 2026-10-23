# 图谱按钮会话过期死 toast · 403 族收口 + viewers/ 同族盘点批报告（微批 #762）

批次：2026-10-23 · 关联 #762（作者实测 2026-10-22 21:49）· 作者症状原话：文档里点图谱按钮报「文档图谱不可用」死 toast——长命标签页会话过期后 REST 直调 403。

---

## 每症状一行结论

| # | 症状 | 一行结论 |
|---|------|---------|
| ① | 点图谱按钮 →「文档图谱不可用」死 toast | `viewers/shared.js addGraphToggle`：`/api/wiki/graph` 被裸 401/403 时只 `throw → catch → toast`，无任何重认证面（点一下死一次，永不自愈）。**本批已修**：401/403 交 sessionGate `noteAuthRejected('wiki-graph-toggle')`（可见横幅 + 1.5s 自愈 reload），其余状态照旧落既有 toast。 |
| ② | 会话过期后点 `[[wikilink]]` 误报「未解析的引用」 | `viewers/markdown.js onWikilinkClick`：点击腿重拉图谱 401/403 → 落 `wiki.unresolved` 误导文案。**本批已修**：点击腿 401/403 交伞（`wiki-link`）；渲染腿（背景面）保持静默降级（authgate 批「渲染即重登」禁令，见下）。 |
| ③ | 会话过期后点远端文档链接 →「远端文档获取失败」死 toast | `viewers/markdown.js` 远端文档腿（`/api/wiki/remote-doc`）401/403 只裸 toast。**本批已修**：交伞（`wiki-remote-doc`），其余非 2xx 照旧。 |
| ④ | EPUB 票据腿无自愈（同族盘点发现） | `viewers/epub.js` 是 office 族最后一个**裸取件**（无 reMint 重取票重试；xlsx/docx/pptx/image/video 都有 T3）。**本批已修**：镜像 xlsx.js T3 形态（恰好一次 reMint 重取票＋重试，第二败落既有错误面板）。 |

## 根因链

```
长命标签页（WS 全通信，零 HTTP 请求） ⇒ 会话 cookie 过期（relay 15min 票 / 引擎重启清零宿主会话）
  └→ /api/wiki/graph REST 直调被裸 403（ws.js showTicketEntry 对「WS 活着 + HTTP 403」致盲，#520 同形）
       ├→ addGraphToggle：catch → toast「文档图谱不可用」＝死 toast（无重认证、无自愈）——作者实测面
       ├→ onWikilinkClick：catch → resolved=null → 误报「未解析的引用」
       └→ /api/wiki/remote-doc：catch → 死 toast「远端文档获取失败」
```

图谱腿的病与 #665 图片 403 族同族：图片族有**铸票伞**（T3 reMint 重取票），图谱 REST 腿连**会话伞**都没有。修法复用同日 scan-bind-403 批（#717 关联批）的同一条路：**401/403 ⇒ `sessionGate.noteAuthRejected(source)`**——可见横幅「登录状态已过期，正在重新进入控制台…」+ 1.5s 后 `location.reload()`（relay 源：长会话在位时静默重铸 15min 票；本地源：落既有未登录卡）。单飞 + 60s 冷却防环在 sessionGate 本体，零新增防环逻辑。

## 纪律分流（为什么渲染腿不交伞）

`markdown.js wikiGraph()` 有两条腿，按 authgate 批（2026-10-22，nfTicket 注释在案）的既定纪律分流：

- **渲染腿**（`renderWikilinks`，文档打开即跑）＝背景面：401/403 **静默降级**（链接留待点击时重解析），绝不拉重登——同 nfTicket authgate 批修掉的「上滑历史渲染即被迫重登」登出级 bug 家族；会话恢复归 12h 保活腿（`sessionGate.armSessionKeepalive`）。
- **点击腿**（`onWikilinkClick`，用户主动点链接）＝用户面：401/403 交 sessionGate 重认证伞。

实现上 `wikiGraph()` 给抛出的错误挂 `err.status`，由点击腿调用点分流——渲染腿代码路径零改动。

## 修复点（全部本仓客户端面）

| 面 | 改动 |
|---|---|
| `web/js/viewers/shared.js` `addGraphToggle` | fetch 后 `res.status === 401 \|\| 403` ⇒ `noteAuthRejected('wiki-graph-toggle')` + return（不再裸 toast）；其余 `!res.ok` 照旧 throw → 既有 `wiki.graphUnavailable` toast |
| `web/js/viewers/markdown.js` `wikiGraph()` | `!res.ok` 时抛出错误挂 `err.status`（渲染腿/点击腿分流的依据），渲染腿行为逐字不变 |
| `web/js/viewers/markdown.js` `onWikilinkClick` 解析腿 | `catch (e)` 中 `e.status === 401 \|\| 403` ⇒ `noteAuthRejected('wiki-link')` + return（不再误报 unresolved）；其余照旧 `resolved=null` |
| `web/js/viewers/markdown.js` 远端文档腿 | 401/403 ⇒ `noteAuthRejected('wiki-remote-doc')` + return（不再裸 remoteFetchFailed toast）；其余照旧 |
| `web/js/viewers/epub.js` | 镜像 xlsx.js T3 形态：`nfViewerUrl`（账本未命中时内部就地取件，401/403 在此抛）进 try ⇒ 恰好一次 `reMint(absPath)` 重取票＋重试；`objectUrl`（blob 腿）无可刷新凭据不重试（image.js 同守卫）；第二败落既有错误面板 |
| locales | **零新键**（横幅文案复用 sessionGate 既有；toast 面全部维持原文案） |

## viewers/ 裸 fetch 直调面盘点（任务令第 2 项）

| 站点 | 取什么 | 判定 |
|---|---|---|
| `shared.js:806` 图谱按钮 | `/api/wiki/graph`（会话 REST） | **本批上伞**（①） |
| `markdown.js:204` `wikiGraph()` | `/api/wiki/graph`（会话 REST） | **本批分流上伞**（②，渲染腿静默＝纪律） |
| `markdown.js:351` 远端文档 | `/api/wiki/remote-doc`（会话 REST，用户主动） | **本批上伞**（③） |
| `epub.js` 取件 | `/api/nf-file`（票据腿） | **本批补 T3 reMint 自愈**（④，office 族最后一个裸取件） |
| `xlsx.js` / `docx.js` / `pptx.js` 取件 | `/api/nf-file`（票据腿） | 已有 T3 reMint（canvas-office-render-fix 批），零改动 |
| `image.js` / `video.js` / `pdf.js` | `<img>`/`<video>` src ＋ onerror reMint | 已有 T3（#665 族本体），零改动 |
| `html.js:508`（srcdoc 内联脚本） | svg 再取（票据已注入 HTML） | 票据腿＋iframe 上下文＋渲染时失败＝svg 不内联（外观降级面）；按渲染腿纪律不上会话伞，不动 |
| `docx.js:50` mammoth / `pptx.js:49` LIB_URL | 静态 vendor 资产 | 非鉴权面，不入族 |
| `pptx.js:117` | `blob:` 帧内自读 | 非鉴权面，不入族 |
| （图外邻面，仅备案）`wikiGraph.js` 图谱 tab `loadData/loadVaultList` | `/api/wiki/graph`、`/api/wiki/vaults` | **不在 viewers/ 任务面**；现状 403＝静默空图谱（比死 toast 温和，无误导文案）；图谱按钮修好后会话先经重登恢复，tab 自然可用。候选另批议（上伞即 `noteAuthRejected`，一处 if） |

## 质量闸（全部本工作树实测）

| 闸 | 读数 |
|---|---|
| `tests/authgate-wiki-graph-403.spec.mjs`（**新，20 断言**） | **20/20 PASS**：①403 零 toast+横幅（source=wiki-graph-toggle）+reload 腿 / ②401 同收口 / ③503 既有 toast 面保持零横幅零 reload / ④渲染腿 403 完全静默（零横幅零 reload 零 toast、锚照常渲染）/ ⑤点击腿交伞（wiki-link）零误导 toast / ⑥远端文档腿交伞（wiki-remote-doc）/ ⑦epub T3 自愈（两轮铸票＋两次取件＋阅读器出片） |
| `tests/authgate-projects-403.spec.mjs`（邻族） | 7/7 PASS |
| `tests/canvas-office-viewers.spec.mjs`（epub 邻族） | **17/17 PASS**（PORT=8152 + NF_PPTX_FIXTURE 指主仓 evidence；xlsx/docx 自愈档全绿＝T3 家族形状未被我批扰动） |
| `tests/wiki-graph-paragraph.spec.mjs`（markdown 邻族） | 10/10 PASS |
| `tests/wiki-graph-ux.spec.mjs` | 12/13（§F frosted 视觉档挂＝**pristine HEAD 同位同形失败**（git archive HEAD 提树重跑实锤），与本批无关——无 CSS 改动） |
| `checkJs`（scripts/check-js-types.mjs） | **257 = 冻结基线 257，PASS（零新增）** |
| `check-circular` | 与 main HEAD **同一既有 SCC[3]**（popArtifacts/reference/msgQuote，上批已备案），零回归 |
| `tests/session-gate.spec.mjs` | ①腿挂＝**pristine HEAD 同位失败（A/B 实锤）**：该 spec 仍钉「铸票 403 ⇒ 横幅 nf-ticket」——正是 authgate 批（2026-10-22）已**退役**的契约（铸票 401/403 改静默退避，绝不登出）。**存量陈旧 spec，本批不动**（属 authgate 批主权面），已在开放问题登记 |

## 证据

| 件 | 路径 |
|---|---|
| BEFORE（pristine HEAD，403 下点图谱按钮＝死 toast「文档图谱不可用」，零横幅） | `evidence/20261023_graph-button-403/before-toast.png`（入库）；`.nebflow/evidence/20261023_graph-button-403/`（工作副本） |
| AFTER（本批，同场景＝重登引导横幅「登录状态已过期，正在重新进入控制台…」，零 toast） | `evidence/20261023_graph-button-403/after-banner.png`（入库）；同上工作副本 |
| 验收断言（20 项程序化） | `tests/authgate-wiki-graph-403.spec.mjs`（运行：`node tests/authgate-wiki-graph-403.spec.mjs`） |

## 作者自查步骤（合入后）

1. 本批为 web 前端面，随 jar 走，候下一次引擎重启窗生效。
2. 复现原症状验证：长命标签页放过夜（或引擎重启清零会话）→ 打开文档 → 点右上角图谱按钮：应弹红色横幅「登录状态已过期，正在重新进入控制台…」并 ~1.5s 后自动恢复（relay 源静默重铸 / 本地源落登录卡）——不再出现死 toast「文档图谱不可用」。
3. 5xx / 断网场景（真传输错）仍见「文档图谱不可用」toast——那是既有降级面，语义未动。

## 开放问题

1. **`tests/session-gate.spec.mjs` ①腿陈旧**（A/B 实锤 pristine HEAD 同挂）：仍钉 authgate 批已退役的「铸票 403 ⇒ 横幅」契约。建议 authgate 批主权方把 ①腿改钉「铸票 403 ⇒ 零横幅（静默退避）」，本批不动以免跨批冲突。
2. `wikiGraph.js` 图谱 tab 的 403 面（静默空图谱）在 viewers/ 任务面之外，候选另批上同一把伞。
3. `resolveWikiTarget` 大小写口径存量语义（`withMd` 保留原文大小写、比较值左侧小写——大写书写的 `[[Link]]` 在部分形态下不命中，本批 spec 夹具以小写命中）：与 #762 无关的独立缺陷，候选另批（改动面＝口径统一，需回归全量 wikilink spec 后再动）。

## 关键文件

| 面 | 路径 |
|---|---|
| 图谱按钮 403 收口 | `src/main/resources/web/js/viewers/shared.js`（addGraphToggle） |
| wikilink 点击腿 / 渲染腿分流 | `src/main/resources/web/js/viewers/markdown.js`（wikiGraph / onWikilinkClick / 远端文档腿） |
| EPUB T3 自愈 | `src/main/resources/web/js/viewers/epub.js` |
| 统一自愈面 | `src/main/resources/web/js/sessionGate.js`（既有，零改动，仅复用） |
| 规格钉 | `tests/authgate-wiki-graph-403.spec.mjs`（新，20 断言） |
| 证据 | `evidence/20261023_graph-button-403/`（before/after 截图，入库） |
