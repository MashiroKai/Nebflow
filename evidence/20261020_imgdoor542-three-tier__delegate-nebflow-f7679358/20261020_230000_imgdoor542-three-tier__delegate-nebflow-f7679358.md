# imgdoor #542 · 文件打开三档加速批 — 交付报告

分支 `delegate/delegate-nebflow-f7679358`（独立分支合 main）· 零引擎面改动 · 2026-10-20

## 作者令（逐字）

「这里应该要有加速，1.本机文件打开应该很快。2.已点击下载过的文件（存在本机），应该也打开要很快。慢的应该是非本机文件，点击有一个下载过程，但是也应该立即在canvas显示标签页，然后标签页显示一个圈作为下载进度显示。」

## 结论（一句话）

点击→开图的链从「铸票→全量缓冲→才开标签」倒置为「**同步开标签→进度环→流式取数→完成即渲染→失败就地可见+重试**」，并叠加设备侧 blob 缓存（档2）与合帧批量铸票（N 图 ≤1 铸票）；三档各有双引擎（Chromium + WebKit/Safari 引擎族）断言。前端面 dev 刷新即全量生效；引擎面仅一处（#538 测量批缓存头 patch 的裁量吸收，见「追批」节）候重启窗，生效前零行为变化。

## 三档实现（file:line 锚）

### 档1 本机文件（零等待体感）
- `messages.js` 设备 path 腿（`previewDeviceSavedAttachment`，~2600）：`previewWithProgress` 同步出标签后，才走 `ticketUrl(path)` ⇒ `fetchBlobWithProgress` 流式直出（边收边渲，不再 `savedPathBlob` 全量缓冲完才响应）。`savedPathBlob` 保留给下载键腿（`downloadDeviceSavedAttachment:2656`，行为逐字不变）。
- 会话级票据复用（既有 `nfTicket.js` per-path 缓存 + TTL 1800s + in-flight 去重）之上新增**合帧批量铸票** `mintTicketsQueued`/`queuedTicketUrl`（`nfTicket.js:352-431`）：同一渲染帧内 N 个路径合成 **1 次** `POST /api/nf-ticket`（服务端本就批量出票，`NfFileRoutes.nfTicketRoutes` 收 `paths[]`）；点击开图时 `ticketUrl` 全部命中缓存 ⇒ 零追加铸票。消费点：`messages.js` 设备内联直显腿（`queuedTicketUrl(s.path)`）。
- 引擎侧本就是本地句柄流式直出（`NfFileRoutes.nfFileRoutes` StaticFile + Range），零改动。

### 档2 已下载缓存（命中即按档1 速度开）
- 新模块 `nfFileCache.js`：字节存 Cache Storage（`caches`，同源持久，刷新页面仍命中）；`caches` 不可用 ⇒ 退化**会话级内存 Map**（申报的降级面，非失败）。索引（LRU 序/字节/时间戳）存 localStorage。
- **LRU + 字节预算**淘汰；TTL 过期即重取（陈旧窗 ≤ TTL，与浏览器 HTTP 缓存同级语义；命中不做服务端再验证——nf-file 无条件 GET，再验证=全量重下，违背档2 目标）。
- **配置（nebflow.json 缺省值，报告申报）**：经既有 configData 通道 → `state.parsedConfig.nfFileCache`，零新机制（featureFlags.js 同款），改动刷新页面生效：
  ```json
  "nfFileCache": { "enabled": true, "maxBytes": 268435456, "ttlMs": 1800000 }
  ```
  缺省 = **256 MiB / 30 分钟**（30min 与 nf-ticket TTL 同族）；配置下限保护 1 MiB / 10s；单件大于预算不缓存。
- 缓存键：设备件 `nf:<path>`；好友附件 `attach:<id>`。**清缓存入口本批不做（候作者示）**，仅留测试 seam `nfCacheReset`。

### 档3 远程未缓存（先标签 + 进度圈 + 失败可见）
- `attachmentPreview.js` `previewWithProgress`（:470-500）：`openTab` **同步**执行（点击当帧出标签，任何取数都在其后）→ 进度面板：Content-Length 可得 ⇒ 百分比环（SVG ring，`data-nf-progress="determinate"` + `data-nf-pct`），不可得 ⇒ 转圈（`"indeterminate"`）→ 完成交既有 viewer 注册表渲染（与 `previewBlob` 同一条渲染腿，`renderIntoPane` 从 `renderIntoTab` 抽出复用）。
- **失败可见 + 重试**：取数/渲染失败 ⇒ 标签内就地 `[data-nf-error]` 面板 + 重试键（`[data-nf-retry]`，复入同链）。401/403 沿既有 sessionGate 可见面（#520/#522 修复腿，未动）。
- **messages.js:2588 族静默降级可见化**：改前「`savedPathBlob` 拿不到 ⇒ 悄悄回落 `previewLocalPath` 文本腿（对图片零反馈，#520 取证判『症状放大器』）」——该回落对 blob 腿类型**移除**，失败态改为标签内可见 + 重试。文本腿（markdown/csv/code…，票据路由白名单外）**逐字保留**（那是设计路，非静默降级）。
- 好友面（`previewFriendAttachment`）同链：`friendsApi.downloadAttachment(id, { onProgress })`（可选进度参数，缺省 = 逐字现状）；410 过期保留权威 toast + 就地错误面。
- CSS：`split.css` `.nf-progress-pane` 族（环/转圈/百分比/重试键）；i18n：`canvas.loadingFile` / `canvas.loadFailed` / `canvas.retry`（zh-CN + en）。

## 验收（全部双引擎 Chromium + WebKit）

`tests/nffile-three-tier.spec.mjs`（新，57 检查全绿，输出见同目录 spec-output.txt）：
- **站1 本机即开断言**：慢路（1.2s 延迟）下点击 → 标签+进度面板**先**在场、取数计数 **0**（tab-first 机械证）；完成即渲染（img naturalWidth>0）、取数恰一次。
- **站2 缓存命中断言**：关标签重开同一件 ⇒ nf-file 取数 Δ=0、铸票 Δ=0、直渲染成功。
- **站3 远程先标签+进度圈+失败可见态**：nf-file 500 ⇒ `[data-nf-error]` + 重试键在场；改回 200 点重试 ⇒ 渲染成功；进度环形态：onProgress(40,100) ⇒ determinate+`data-nf-pct="40"`，total 缺席 ⇒ indeterminate。
- **站4 票据计数断言（N 图 ≤1 铸票）**：N=3 路径同帧 `queuedTicketUrl` ⇒ **恰好 1 次** POST 且携带全部 3 路径；同路径 `ticketUrl` 复用 ⇒ 0 追加。
- **站5 LRU+配置**：maxBytes=1MiB（configData 注入 nebflow.json 键）⇒ 预算内淘汰最久未用者、配置读回生效。
- **站6 接线钉**（静态）：messages.js 三处消费点 + 静默回落行已移除 + 文本腿逐字在场。

回归：`imgcard-click-safari-compat`（#520 四站链）0 失败、`card-ticket-credential` 44/44、`canvas-preview-nav-parity` 63 PASS/0 FAIL、`check-js-types` PASS（250 < 基线 273，触及文件全清）、`check-circular` 无新环（既有两 SCC 与 HEAD 全同）、`build-web` 163 源 0 孤儿。
预存在失败（与 HEAD 逐字相同，非本批）：`imgmsg-send` E/B②（HEAD 同败）、`friend-chain-ui`（harness 自身 server boot 失败，HEAD 同崩）、`canvas-office-viewers`（worktree 缺未跟踪 evidence 夹具）。

## 分界申报（引擎面 / 前端面）

- **本批零引擎面（Scala）改动**——nf-file/nf-ticket 路由、铸票存储、白名单一字未动。**dev 刷新页面即可验全部行为**，无重启窗依赖。
- 考虑过并**否决**的引擎面选项：给 nf-file 200 响应加 `Cache-Control: private`（浏览器 HTTP 缓存级档2）——该路由是 C 批「断凭据腿」加固面，动缓存语义需作者裁定；如要做属独立批 + 重启窗。
- 可选后续（同需重启窗，未做）：mint 响应附 per-path size/mtime（或 ETag），档2 命中可升级为「即时渲染 + 后台再验证」。

## 追批：#538 量化结果吸收（同批续作）

#538 测量批结论送达后（evidence/20261019_082500_imgslow538-segmented-timing.md）逐条对账：

1. **①档已达标**（本机直连全链 ≈45ms）——本批只补**分派判据钉**（spec 站7f）：设备 path 腿字节源 = 本机票据路（`ticketUrl` ⇒ `/api/nf-file`，引擎本机直读），好友鉴权路由（`/api/friends/attachments`）只属好友/群腿，本机件不误走下载腿。
2. **②档账本**（无「已下载账本」+ 浏览器缓存形同虚设 + 票 URL 随重铸变键）——本批 `nfFileCache.js` 即账本（**path 键**，对重铸变键免疫）。
3. **③档结构性**（全量收完才开面板零反馈）——本批 tab-first + 进度环即其解；进度数据源与测量批所指一致（fetch reader received/total）。
4. **推荐不做**（bytes LRU / 票据复用）——未做：nfFileCache 是文件级账本（作者令档2 明令），非字节级 LRU；票据复用全走既有 `nfTicket` 缓存（实测 0 冗余），本批只加合帧批量（N 图 ≤1 POST），非重复铸票。

**候选吸收（测量批裁量项）**：`nf-file-cache-header.patch`（evidence/20261019_imgslow538-timing/，212 行）**已吸收**——
- `NfFileRoutes.scala`：200/206/304 正腿 ⇒ `Cache-Control: private, max-age=300`（远小于票据 TTL 1800s ⇒ 缓存条目不可能活过凭据；`private` ⇒ 永不进共享/CDN 缓存）；缺票 401 / 拒票 403 / 416 ⇒ `no-store`（凭据拒绝响应永不入库）；404 腿不动（最小 diff）。
- `NfFileRoutesSpec.scala`：+4 契约钉（200/206/304 头、304 复验、拒绝腿 no-store）。
- **分层互补**（吸收后档2 的三层快路）：同票 5min 内 ⇒ 浏览器 HTTP 缓存 0 字节；票重铸变键后 ⇒ nfFileCache 按 path 键仍 0 字节；账本过期且票重铸 ⇒ ETag 条件请求 304 一个 RTT（字节不穿隧）。
- **spec 回归**：真实契约回归 = Scala 侧 `NfFileRoutesSpec`（本批运行，输出 `nfileroutes-spec.txt`）；前端 spec 加**站8 源码钉**（三腿头契约 + 拒绝腿 no-store + 404 不动），保证前端树与服务端地基不脱钩。

**分界（更新）**：吸收后本批**含引擎面**——引擎 face（Cache-Control/304）候重启窗生效；其生效前前端行为零变化（nfFileCache 不依赖 HTTP 缓存头，两层各自独立成立）。UI/档1/档3 全部 dev 刷新即验。

## 偏离 / 开放项

1. 无扩展名文件（好友面 att.name 无扩展名）今天 `previewBlob` 有内容嗅探腿；流式链保留该嗅探但在**开标签之后**判（嗅探需字节）——判不了时标签内可见「不支持」面 + 卡片 toast（原行为：仅 toast）。可渲染性结论不变，呈现面更可见。
2. #538 量化批（分段计时）在飞、其表未落仓 —— 本批以**行为断言**（先标签后字节、零取数、计数）代替毫秒级对照；#538 表落地后可直接对账。
3. 多标签并发写 localStorage 索引为可容忍竞态（最坏少记一次 LRU touch，预算由下次 put 收敛）——申报。
4. `previewDeviceSavedAttachment`/`previewFriendAttachment` 返回契约语义收窄：失败不再让卡片叠 `attachPreviewFailed` toast（失败态已在标签内可见 + 重试，双 toast 反噪音）；`unsupported` 仍 toast。

## 文件清单

- 新增 `src/main/resources/web/js/nfFileCache.js`（档2 + 流式读出器）
- 新增 `tests/nffile-three-tier.spec.mjs`（三档验收）
- 改 `attachmentPreview.js`（previewWithProgress/进度/失败面 + renderIntoPane 抽取）
- 改 `nfTicket.js`（mintTicketsQueued/queuedTicketUrl 合帧批量铸票）
- 改 `messages.js`（设备/好友预览腿接线 + 内联合帧铸票 + 2588 族可见化）
- 改 `friendsApi.js`（downloadAttachment 可选 onProgress）
- 改 `split.css`（进度面板族）、`locales/{zh-CN,en}.js`（3 键）
