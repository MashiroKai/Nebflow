# REPORT — Pop 图片预览与 Canvas 打开二次加载（imgdoor 二次加载批）

- 批次：优化批 · Pop 图片预览与 Canvas 打开二次加载（作者报障 2026-10-24 15:0x）
- 分支：`delegate/delegate-nebflow-8472f2d3`（独立交付，候合并）
- 日期：2026-10-24

## 0. 一句话根因

**Pop 消息气泡的图片预览走的是裸 `<img src=票据URL>` 直取（字节不进 nfFileCache 账本），点「在 Canvas 打开」走 image viewer 的账本查询（`nf:<path>`）必然未命中，于是按「未命中取数」把同一张图再全量下载一次——且账本取数腿 `cache:'no-store'`，浏览器 HTTP 缓存帮不上（服务端 #538 批本就已发 `Cache-Control: private, max-age=300` + ETag/304，但取数腿主动绕开）。两条取数路零共享 ⇒ 同图两次网络抓取。**

## 1. 修复点（前端单点，零新取字节路）

全部在 `src/main/resources/web/js/popArtifacts.js::hydrateMedia`（Pop 媒体脸唯一取数单点）：

1. **图片脸字节改经账本单点 `nfViewerUrl`**：`nf:<path>` 命中 = blob URL 直显；未命中 = 既有票据链取数并 `nfCachePut` 入账。预览取数即入账 ⇒ Canvas 打开（image viewer 同走 `nfViewerUrl`，`viewers/image.js:166`）账本命中零取数 ⇒ **预览 + 打开 = 恰一次网络抓取**；灯箱复用同一 `hydrateMedia` ⇒ 同样零追加。
2. **互斥分派（恰一腿）**：`el.tagName === 'IMG'` 才走账本腿；视频/音频维持既有票腿原样（Range/流式播放语义不进账本，非本批症状面）。
3. **降级安全（失败面逐字保留）**：`nfViewerUrl` 拒绝（铸票/取数失败）⇒ 回落既有 `legacyTicketFace`（ticketUrl + error→reMint 自愈链，与改前逐字一致）；**blob 解码失败（坏字节）⇒ 先 `nfCacheDrop` 逐出该键再走 reMint 直取**——坏字节不粘死账本（否则 TTL 内每次渲染都命中同一份坏字节）。
4. **内容失效语义不变**（作者令第 4 条）：缓存键 = 既有账本键 `nf:<path>`，内容变更由既有三腿把关——引擎 `nf-cache-invalidate {path}` 广播精确淘汰（main.js:1650）、列目录 size:mtime 对账、TTL 兜底；本批只是把预览脸接进同一账本，未另造键族。

## 2. spec 计数结果（作者令钉死的三条 + 降级安全）

新增 `tests/pop-image-single-fetch.spec.mjs`（+ `tests/fixtures/pop-image-single-fetch/harness.html`，驱动**真实**生产链：`buildPopArtifactRow` 装配 + `renderFile`→image viewer 渲染，小源服务器按真实 nf-ticket 协议铸票/取件并计数）。**4/4 通过**：

| # | 断言 | 结果 |
|---|---|---|
| T1 | 同图预览 + Canvas 打开 ⇒ `/api/nf-file` **恰 1 次抓取**（修前 = 2）；预览脸 = blob URL；账本 entries = 1 | ✅ |
| T2 | 缓存命中路径：重开预览脸 ×2、Canvas 打开 ×2 ⇒ 仍 **1 次抓取**（一份字节多面共享） | ✅ |
| T3 | 内容变更（1×1→2×1 PNG + 事件失效）⇒ 账本逐出（entries 0）⇒ 重取（第 2 次抓取）且 `naturalWidth=2`（**新字节，不拿旧图**）⇒ 变更后 Canvas 打开仍零追加 | ✅ |
| T4 | 文件不存在 ⇒ 账本腿失败回落票腿，终态失败注记**恰一行**、失败件不入账（禁静默契约不破） | ✅ |

**验红（反事实）**：`git stash` 还原修复后 T1 即红（图片脸非 blob、非账本面），恢复修复后全绿——spec 确实钉住报障症状。

## 3. 回归面

- `tests/media-resolver-fallback.spec.mjs`（共用 `hydrateMedia` 的用户附件面）4/4 通过；A1/A2 两处「src 必为 /api/nf-file 形态」的旧形状钉死按本批新行为更新（= blob 账本面或票腿形态二择一 + `mintCount ≥ 1` 证票据路确实被走），A3 双腿终态注记恰一次、A4 data-URI 快路恒赢均原样通过。
- 回归扫描 7 套件（pop-artifacts / pop-media-index / social-media-unify / attach-bubble-unify / uploads-restore-image / user-attach-canvas-open / msgqueue-image-mime）：**63 通过 / 1 失败**。
  - 唯一失败 `pop-artifacts.spec.mjs` UI-G P7（subagent 窗 rAF 计数 2>1）经 HEAD 对照（还原修复后同红）证实为**本分支既有失败，与本批无关**。
- checkJs 门禁：本批文件 `popArtifacts.js` 已回归 0 错；门禁剩余失败 = `nearpath.js` 3 错——该文件由 console-fastpath 批（ab81fbc02）引入、从未入基线，**本批零触碰的既有债**（本批改动前门禁同样红），不在本批处置。

## 4. 作者令逐条对账

| 排查方向 | 结论 |
|---|---|
| ① 票据 URL 不同导致缓存不命中 | 部分成立但不为主因：nfTicket per-path 票据缓存使两腿通常同 URL；主因是预览字节根本不入账本 + 账本取数腿 no-store |
| ② /nf-file 响应头 | **无需改**：#538 批已发 `Cache-Control: private, max-age=300` + ETag/Last-Modified + 304（`NfFileRoutes.scala:41-59,263-272`）；max-age < 票据 TTL 不会陈旧越凭据。二次加载的根因在前端取数路不共享，不在响应头 |
| ③ 预览字节复用给 Canvas 打开 | **本批主体**：接既有 nfFileCache 账本（Cache Storage + 内存 Map，键=账本键、字节=内容 cid），命中零网络 |
| ④ 降级安全 | 事件失效/TTL/对账三腿照旧；本批追加「blob 解码失败 ⇒ 逐出账本再自愈」，坏字节不粘死 |

## 5. 未做 / 边界

- 视频/音频脸不进账本（Range/流式播放语义，非本批症状面）——维持票腿原样。
- 好友/设备附件卡的内联直显腿（`messages.js::attachInlineImage` 的 server-ticket 形态）存在**同型**双取数（作者报障面是 Pop 媒体脸，该面未动）；如需同治，接同一 `nfViewerUrl` 单点即可，建议另批。
- Pop 载荷内联 data-URI 件（≤5MB，`item.src`）零网络，不涉本批。
- 既有债（非本批）：`pop-artifacts.spec.mjs` UI-G P7 红、`nearpath.js` checkJs 3 错未入基线。

## 6. 交付物

- 改：`src/main/resources/web/js/popArtifacts.js`（hydrateMedia 账本腿 + 互斥分派 + 失败回落）
- 改：`tests/media-resolver-fallback.spec.mjs`（A1/A2 形状断言对齐新行为，语义断言全保留）
- 新：`tests/pop-image-single-fetch.spec.mjs`、`tests/fixtures/pop-image-single-fetch/harness.html`
