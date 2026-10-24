# REPORT — 文件字节消费面双取数清账 + diff 打开链首帧/反馈（二次加载清剿批）

- 批次：优化批 · /api/nf-file 族双取数全量盘点与 #816 同法清账；+ 作者补充令（2026-10-24 15:42 逐字：「我点diff，然后再侧边栏选diff文件在canvas打开的体验还是很慢，甚至点了没反应。」）
- 分支：`delegate/delegate-nebflow-7627758a`（独立交付；收口批已 `--no-ff` 并入 main，含收尾三处 `toList` 编译修正）
- 日期：2026-10-24
- 工作区：`.nebflow/worktrees/delegate-nebflow-7627758a`

## 0. 一句话根因（两面）

**字节面**：#816（Pop 媒体脸）之外还有 6 个消费脸在账本之外裸取 `/api/nf-file`（或鉴权附件路由）——设备面内联直显、好友面内联直显、灯箱、markdown 文档图、chatSearch 缩略、html viewer 的 SVG 内联化 fetch；这些脸取的字节不进 `nfFileCache` 账本 ⇒ 同一面之后的 Canvas 打开（账本单点 `nfViewerUrl`）必然未命中、把同一份字节再全量拉一遍（同图两次网络抓取，#816 同型）；服务端侧 uploads/friends-attachments/own-avatar 三个端点有 ETag/304 却从未发 `Cache-Control`，重复消费走启发式新鲜度（RFC 9111 §4.2.1）形同零缓存。**diff 面**：整条打开链**零网络**（载荷 = WS 帧内存载荷，spec T4 请求计数 = 0 钉死）；「慢/点了没反应」= 修前 `renderDiffChunked` 先把整份 diffText `split('\n')`（十几 MB = 秒级主线程冻结 = 白屏无响应）+ 两处失败腿 `.catch(() => {})` 静默吞掉（动态 import 失败 / 渲染装配抛错都无任何可见反馈）。

## 1. 盘点矩阵（作者令主令①⑤）

| # | 消费面 | 修前取数形态 | 修后 | 账本键 | spec 钉 |
|---|---|---|---|---|---|
| A | 七个 viewer（Canvas 打开） | `nfViewerUrl` 账本单点（#816 前 #538 批已接） | 不动 | `nf:<path>` | — |
| B | Pop hydrateMedia | 账本单点（#816） | 不动 | `nf:<path>` | pop-image-single-fetch 4/4 ✅ |
| C | attachmentPreview（Canvas 附件预览） | 账本单点 | 不动 | `attach:<id>` | — |
| 1 | **设备面内联直显**（messages.attachInlineImage device/path 腿） | 裸 `img.src=票据URL` 直取，字节不进账本 ⇒ 预览+Canvas 打开 = 2 抓；批内曾挂 `devnf:` LRU 早退（**spec T6 验红钉出：LRU 在账本前，事件失效够不着 ⇒ 内容变更后重渲照挂失效前旧字节 = 静默陈旧**） | `nfViewerSource` 账本单点（命中零抓取/未命中取数即入账）；**LRU 早退删除**（账本即复用面，URL 生命周期归 `trackViewerUrl`）；坏字节自愈恰一次（`nfCacheDrop`+票腿重挂，第二败摘槽） | `nf:<path>` | T1/T2/T6 ✅ |
| 2 | **好友面内联直显**（ready 腿） | 既有鉴权路由直取，字节不进账本 ⇒ 与 Canvas 预览各取一次 | `nfCachedBlob('attach:<id>')` 查账 → 未命中 `downloadAttachment` + `nfCachePut` 入账 ⇒ 气泡与 Canvas 预览共享同一份字节；会话内 objectURL LRU 复用保留（id 寻址内容无失效事件，T3 钉复用） | `attach:<id>` | T3 ✅ |
| 3 | **灯箱**（lightbox.openLightbox 本地路径腿） | 每开必 reMint + 全量重拉（已入账同件也重取） | 账本先行（`nfViewerSource`），失败/自愈回落既有 reMint 腿（`legacyFetch`）；`imgEl.onerror` 坏字节自愈（`nfCacheDrop`+reMint 恰一次/每开，`data-nf-lb-retried` 按开清零） | `nf:<path>` | T4 ✅ |
| 4 | **markdown 文档图**（viewers/markdown 图片改写） | 每 img 直挂票据 URL，每 pane 各取一次；`window.__nfStripCredential` 内联属性发布（checkJs 欠账） | 占位符 token 两段替换（`__NF_MD_IMG_N__` 在 src 值内 + `data-nf-md-fb` 属性位）→ `Promise.all` 逐图 `nfViewerUrl` 账本解析终局 src；`__nfMdImgFail` 单点自愈（`nfCacheDrop` + 回落票据 URL 恰一次；票据 URL 也败 = 既有 tooltip 失败面，凭据经 `stripCredentialParams` 单点剥除）；onerror 由模块 import 直调发布 | `nf:<path>` | T5 ✅ |
| 5 | **chatSearch 媒体缩略**（mediaEntryCard 图片支） | 票据 URL 直取（每次搜出都重拉） | `resolveEntryImageThumb` 走 `nfViewerSource` 账本；视频留票腿（Range/流式语义不进账本） | `nf:<path>` | （回归批覆盖 pop-media-index） |
| 6 | **html viewer SVG 内联化** | `fetch(src)` 无缓存指令 + 判据 `.svg(\?|$)` 自 2026-09-11 C 批票据化起**恒假**（`.svg` 落 query 中段）= 内联化静默失效 + 同 SVG 双取 | 判据改 `.svg([?&]|$)`（恢复设计行为）+ `fetch(src, {cache:'force-cache'})`（img 标签刚取过同一 URL，命中即零字节往返） | `nf:<path>` | T7 ✅ |
| 7 | **nfViewerSource 未命中腿** | 逐调用 `ticketUrl` ⇒ 同帧 N 个未命中 = N 发 POST /api/nf-ticket；同键**并发**未命中各抓一次 | 铸票改 `queuedTicketUrl` 合帧批量（N 面 ≤ 1 铸票）；**在飞合流表 `viewerFetchInFlight`**（同键并发未命中合流为恰一次抓取，settle 即摘、失败不粘表） | `nf:<path>` | T1-T7 全过即覆盖 |
| S1 | **服务端 uploadsServe**（StaticRoutes） | ETag+Last-Modified+304 在，**无 Cache-Control** | 200/304 加 `Cache-Control: private, max-age=300`；404 不染色 | —（HTTP 缓存） | UploadsRoutesSpec +2 test ✅ |
| S2 | **服务端 friends attachments**（SocialRoutes） | 同上（且无 ETag 可协商） | 200 加同头（`private`：withAuth 鉴权路由） | — | （同上家族语义） |
| S3 | **服务端 own avatar**（NeblinkRoutes） | 零缓存策略，设置页/列表每开全拉 | 加同头 300（对端 `avatars/:userId` 早有 3600，本路由对齐 #538 家族保守值） | — | AvatarProxyRouteSpec 断言 ✅ |

**不动面（盘点结论，非遗漏）**：下载键族（`workspace-open-item`/导出——落盘数据正确性优先，缓存语义不适用）；`/api/plugin-assets` 等四族（作者已裁定 no-cache 反增请求数，保持）；历史页 `fileChanges` 载荷体积（引擎存储形态，diff 字符 cap 已被作者 2026-10-10 裁定全撤——「Canvas renders oversized bodies in full」）；explorer.js 无预览取字节腿（`nfCacheReconcile`/`nfCacheNoteHash` 接线已活在 explorer.js:1115/1243）。

## 2. 修复点（关键 file:line）

前端（`src/main/resources/web/js/`）：
- `nfFileCache.js`：`nfViewerSource` 508-549（未命中腿铸票合帧 + **在飞合流表**）；`viewerFetchInFlight` 470-475；`nfCachedBlob` 256 / `nfCachePut` 316 / `nfCacheDrop` 363 / `nfCacheInvalidatePath` 377 / `nfCacheStats` 425 / `nfCacheNoteHash` 480（既有，接线已活）。
- `messages.js`：import 105-106；`attachInlineImage` export（测试 seam）2156；img error 自愈监听 2191-2203；device/path 腿 2220-2256（账本单点 + LRU 早退删除 + 自愈 `legacyRetry` 2230）；好友腿 2298-2326（`attach:<id>` 入账共享）。
- `lightbox.js`：import 16；账本先行腿 225-231；自愈 201-204（`nfLbRetried` 每开清零 164）。
- `viewers/markdown.js`：import 15-21；占位符改写+两段替换 515-546；`__nfMdImgFail` 自愈单点 556-569。
- `chatSearch.js`：import 58；`resolveEntryImageThumb` 1814-1843。
- `viewers/html.js`：SVG 内联化 503-534（判据 `[?&]` 511、`force-cache` 515）。
- `diffRender.js`：`classifyDiffLine` export 30（parse 单一源 45）。
- `diffCanvas.js`：`CHUNK_ROWS` 4000 = 23、`FRAME_BUDGET_MS` 10ms = 32；`renderDiffChunked` 流式重写 46-90（首帧同步落壳+文件头+`.nf-diff-loading`，行按帧预算渐进 slice，**不整份 split**，gen 取消守卫保留）。
- `diffPanel.js`：meta 面 helper `setMetaError`/`setMetaTotals` 149-190（失败面/成功面单点，成功即清）；`renderBatch` 行点击失败腿 `.catch ⇒ setMetaError(row)` 213-217；`openDiffBatch` 装配 try/catch 253-262 + canvas 腿失败/成功面 275-285。
- `locales/zh-CN.js`+`en.js` 26-27：`panel.diffRendering` / `panel.diffOpenFailed`。
- `css/diffView.css` 387-403+：`.nf-diff-loading`（脉冲装载态）、`.nfp-meta-err`、`.nf-fc-openfail`。

服务端（`src/main/scala/nebflow/gateway/`）：
- `StaticRoutes.scala` 532-541：uploadsServe 200/304 加 `private, max-age=300`（#538 同款；404 腿不动）。
- `SocialRoutes.scala` 366-371：friends attachments 200 attHeaders 加同头。
- `NeblinkRoutes.scala` 325-329：own avatar 加同头。
- 测试：`UploadsRoutesSpec.scala` +2 test（120-165：200 头断言；If-None-Match 304 重申策略 + 404 不染色）；`AvatarProxyRouteSpec.scala` 162 upstream-200 头断言。

## 3. diff 补充令专节（15:42 令①②③逐条）

**① 全链审计 + 逐腿计时**（链 = 点摘要条/行 → 左栏面板 → Canvas 阅读面）：
- 腿0 摘要条/面板渲染：`openDiffBatch` → `renderBatch`（本地 DOM，0 网络）+ 动态 import activityBar/diffCanvas（首次 ~10ms 级模块编译，后续缓存）。
- 腿1 行点击 → `openFileChangesCanvas` → `openTab`（标签复用/建签，0 网络）。
- 腿2 `renderReader` → **首帧同步**：壳 + 文件头（路径/±计数）+ `.nf-diff-loading` 装载态同一次宏任务落地（T1 断言：一个宏任务拍后 hasHead+loading 必在场）。
- 腿3 行流式挂载：每帧 ≤ `CHUNK_ROWS` 4000 行且 ≤ `FRAME_BUDGET_MS` 10ms（先到为准），从原串 slice 渐进（**不整份 split**——修前在这里冻结秒级）；完成时装载态退场。
- 腿4 网络：**零**（载荷 = WS 帧内存；T4 /api 请求计数 = 0 钉死，`/api/canvas-tabs` 标签存档 PUT 为标签架构面、非本链取数腿，已排除计数）。
- 结论：修后「点了没反应」的两个根（整份 parse 冻结 + 失败静默）都已拔掉；大 diff 体验 = 装载态立即出现 + 行按帧渐进到全量。

**② loading 态 + 失败可见反馈**：渲染链装载态 = `.nf-diff-loading`（同步首帧）；失败链 = 面板 meta 槽错误文案（`panel.diffOpenFailed`，两处入口：行 `.catch` / `openDiffBatch` 装配 try/catch）+ 行 `nf-fc-openfail` 失败态；成功即清（`setMetaTotals`，错误面不粘死）。修前 = `.catch(() => {})` 静默。

**③ spec**：`tests/diff-canvas-first-frame.spec.mjs`（+ fixtures harness，驱动生产 diffPanel→diffCanvas→canvas.openTab 链）：**4/4 ✅**
- T1 行点击 → 一个宏任务拍后文件头+装载态首帧落地（修前 = 整份 parse 冻结白屏）✅
- T2 12 万行 diff：流式中途两连 rAF 墙钟差 < 500ms（禁冻结）+ 全量行数 = 精确值（BIG_LINES+1，无截断——作者 2026-10-10 承接）✅
- T3 行腿渲染抛错 ⇒ meta 错误+行失败态；开批腿装配抛错 ⇒ meta 错误面；重试成功 ⇒ 失败面清除 ✅
- T4 打开链 /api 取数腿计数 = 0 ✅
- 失败注入形态说明：载荷字段抛错 getter（= 修前被静默吞掉的同类装配/渲染抛错）；「摘 canvas 容器」不构成失败腿——`openTab` 自建 DOM 自愈（spec 开发中验明，harness 备忘注记）。

## 4. spec 计数结果（主令④：每修复面一条 N→1 断言）

`tests/file-bytes-single-fetch.spec.mjs`（+ fixtures harness；小源服务器按真实 nf-ticket 协议铸票/取件并计数）：**7/7 ✅**

| # | 断言 | 结果 |
|---|---|---|
| T1 | 设备面直显 + Canvas 打开 ⇒ nf-file 恰 1 抓（修前 2）；直显脸 = blob 账本面；entries=1 | ✅ |
| T2 | 重渲 ×2 + 打开 ×2 ⇒ 仍 1 抓（一份字节多面共享） | ✅ |
| T3 | 好友面 ⇒ 鉴权路恰 1 次 + `attach:att-1` 入账（size 相等）+ LRU 复用零追加 | ✅ |
| T4 | 灯箱开已入账同件 ×2 ⇒ 零追加抓取（修前每开 reMint 全量重拉） | ✅ |
| T5 | markdown 图两 pane + Canvas 打开 ⇒ 恰 1 抓；出帧前账本解析 = blob；回落属性按设计在场（自愈腿票据 URL） | ✅ |
| T6 | 好图入账→内容换坏字节（事件失效）⇒ 重渲染 blob 解码败逐出账本、票腿自愈恰一次、终态摘槽、坏字节不粘死账本（entries=0）——**本条验出并钉死 devnf LRU 陈旧缺陷** | ✅ |
| T7 | SVG 内联化 ⇒ 同一 SVG 恰 1 抓（修前：判据恒假 = 内联化静默失效） | ✅ |

反事实（修前形态验红）：T1 在 LRU/账本腿任一缺席时红（box 非 blob / 计数 = 2）；T6 在 devnf LRU 早退在场时红（`boxInDom=true` 旧字节照挂）；T7 在旧判据下红。

## 5. 服务端 Cache-Control/ETag 覆盖盘点（主令②）

| 端点 | 修前 | 修后 |
|---|---|---|
| `/api/nf-file`（NfFileRoutes） | `private, max-age=300` + ETag/Last-Modified + 304（#538 批已备） | 不动（本批前端把消费脸接进账本兑现它） |
| uploadsServe（StaticRoutes） | ETag+304 在、无 Cache-Control | 200/304 加 `private, max-age=300`；404 不染色 ✅（spec 钉） |
| `/api/friends/attachments/:id` | 无（且无 ETag 可协商） | 200 加同头 ✅ |
| own avatar（NeblinkRoutes） | 零策略 | 加同头 300 ✅（对端 avatars/:userId 3600 既有不动） |
| `/api/plugin-assets` 等四族 | no-cache（作者裁定面） | 不动 |
| 视频 ≥64MiB Range 腿 | 206 带 `private, max-age=300` 无 no-store | 不动（观察项，见 §8） |

## 6. 票据 ↔ 内容寻址对应（主令③）

| 键/凭据 | 字节锚 | 生命周期 | 失效腿 |
|---|---|---|---|
| 票据 ticket（per-path per-mint SecureRandom） | 只作取件凭据，**不锚内容** | TTL 30min；per-path 票据缓存 = 同一票据纪元内 URL 稳定 | 过期即 reMint（自愈链既有） |
| `nf:<absPath>` | Cache Storage blob + 内存 Map；hash = `nfCacheNoteHash` 16-hex 前缀（设备 fileContent 帧免费带回）；srcPath 锚 | TTL 兜底 | 引擎 `nf-cache-invalidate {path}` 广播精确淘汰（main.js:1650）+ 列目录 size:mtime 对账 + TTL |
| `attach:<id>` | Cache Storage blob（好友附件字节） | TTL 兜底 | 附件 id 寻址无变更事件（上传一次定身）；失败/超预算即摘槽 |
| viewer objectURL | 从账本 blob 现铸 | pane 断连即撤（`trackViewerUrl`/canvas-tab-closed）；好友面 LRU 有界 64、淘汰即撤 | — |
| HTTP 缓存（本批 S1-S3） | ETag（StaticFile 内建）/无 | `private, max-age=300`，304 重申策略 | max-age ≪ 票据 TTL ⇒ 不存在陈旧越凭据窗 |

## 7. 回归 + 门禁

- **新增 spec**：diff-canvas-first-frame 4/4 ✅、file-bytes-single-fetch 7/7 ✅（均在最终代码态——含在飞合流表——复跑通过）。
- **回归批**（workers=1 一把跑，最终代码态）：media-resolver-fallback / nffile-503-backoff / card-image-zoom / card-ticket-credential / mdscroll-toc-scroll / mermaid-md / canvas-html-interactive / canvas-html-zoom / msgqueue-image-mime / uploads-restore-image / pop-media-index / card-persist-fe-search / social-panel / attach-bubble-unify / user-attach-canvas-open + pop-image-single-fetch：**44/44 全绿**。
- **checkJs**：本批触碰文件全部 0 错（html.js 的 12 错 = 开发中一次模板字面量注释反引号事故，当场修复后归零）。门禁现状红 = `nearpath.js` 3 错（`__BRAND__` ×2 + kind 收窄）——该文件由 console-fastpath 批（ab81fbc02）引入、从未入基线，**本批零触碰的既有债**（#816 REPORT §3 同款申报；本批改动前门禁同样红）。
- **check-circular**：本批新增静态边全部指入 `nfFileCache`（只 import state/branding，叶）；与 main HEAD 同一既有 `SCC[3]`（popArtifacts/reference/msgQuote），零新环。
- **Scala**：UploadsRoutesSpec（+2 test）+ AvatarProxyRouteSpec `testOnly` 终局 **18/18 PASS（Failed 0，EXIT=0，日志 /tmp/dualfetch-sbt2.log，2026-10-24 19:47 完成态）**——含收尾三处 `NonEmptyList.mkString` 缺 `.toList` 编译修正（AvatarProxyRouteSpec.scala:162、UploadsRoutesSpec.scala:131/160）。

## 8. 未做 / 候作者裁（风险面证据，主令⑤）

1. **readFile WS 文本面**（聊天附件 `readFile` 每开全量重读）：引擎存储形态（帧内文本），账本化 = 引擎侧协议改动。
2. **chat.js `probeCanvasFile`**：探针 + 打开必双读同件；候选 = 探针结果入账或探针/打开合并，涉及 Canvas 打开语义，候裁。
3. **票据 per-mint SecureRandom = URL 不稳** ⇒ 浏览器 HTTP 缓存只在同一票据纪元内有效（跨纪元 reMint 换 URL = 必 miss）；候选 = URL 带内容 hash query 参数——**协议变更**（票据语义从凭据变寻址），候裁。
4. **cardRegistry iframe reMintAll**：卡片 iframe 内的媒体脸结构上各自铸票；账本化需 iframe 桥，结构性改动。
5. **视频 ≥64MiB Range 腿**：服务端 206 带 `private, max-age=300` 无 `no-store`——Range 响应可缓存语义（RFC 9110 §3.1）观察项；真问题 = 账本不收流式大件（有意，64MiB 闸），维持。
6. **devnf LRU 陈旧缺陷**（本批 T6 钉出并修复）：好友面 `attach:<id>` 的 LRU 复用保留——若未来附件出现「同 id 换内容」语义，同型陈旧面会在那里复现（现状无失效事件，不构成缺陷）。
7. **nearpath.js 3 checkJs 错**（§7 申报）：console-fastpath 批的债，本批不越界代修。
8. **历史页 fileChanges 载荷体积**：引擎存储形态（diff 全文入库），前端已流式渐进渲染兜住消费端；存储端瘦身另议。

## 9. 交付物

- 改：`nfFileCache.js`、`messages.js`、`lightbox.js`、`viewers/markdown.js`、`chatSearch.js`、`viewers/html.js`、`diffRender.js`、`diffCanvas.js`、`diffPanel.js`、`locales/zh-CN.js`、`locales/en.js`、`css/diffView.css`
- 改：`StaticRoutes.scala`、`SocialRoutes.scala`、`NeblinkRoutes.scala`、`UploadsRoutesSpec.scala`、`AvatarProxyRouteSpec.scala`
- 新：`tests/diff-canvas-first-frame.spec.mjs`、`tests/fixtures/diff-canvas-first-frame/harness.html`、`tests/file-bytes-single-fetch.spec.mjs`、`tests/fixtures/file-bytes-single-fetch/harness.html`
- 本 REPORT

## 10. 关键假设

- 「点了没反应」的主根 = 主线程冻结 + 失败静默（链路逐腿核实为**零网络**）；若作者在真机上仍感到慢，下一嫌疑位 = `openDiffBatch` 的 activityBar 面板展开动画与 12 万行渐进挂载的布局抖动（spec T2 已钉帧间隔有界，真机手感另测）。
- 大 payload 处置 = **渐进渲染全量必达**（不截断）——依作者 2026-10-10 裁定「Canvas renders oversized bodies in full」；服务端 diff 字符 cap 已全撤，本批不加。
- `/api/canvas-tabs` PUT 是标签架构面（任何 openTab 防抖 500ms 触发），不计入 diff 打开链取数腿（harness 计数器注释钉死此口径）。
- 好友附件 id 寻址内容不可变（现状无失效事件）——LRU 复用在此前提下安全。
